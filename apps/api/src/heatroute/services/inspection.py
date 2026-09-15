from __future__ import annotations

import csv
import json
import re
import shutil
import stat
import sys
import zipfile
from collections.abc import Iterator
from contextlib import contextmanager
from datetime import date, datetime
from pathlib import Path
from threading import Lock
from typing import Any, cast

import geopandas as gpd
import numpy as np
import pyarrow.parquet as pq
import pyogrio
from pyogrio.raw import read

MAX_LAYERS = 64
MAX_FIELDS_PER_LAYER = 256
SAMPLE_FEATURES = 5
SAMPLE_FIELDS = 32
MAX_SAMPLE_STRING = 500
MAX_ARCHIVE_ENTRIES = 256
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 512 * 1024 * 1024
MAX_ARCHIVE_COMPRESSION_RATIO = 100
MAX_GEOPARQUET_ROWS = 1_000_000
MAX_GEOPARQUET_UNCOMPRESSED_BYTES = 1024 * 1024 * 1024
_STORAGE_KEY = re.compile(r"sha256/(?P<prefix>[0-9a-f]{2})/(?P<digest>[0-9a-f]{64})\Z")
_GDAL_CONFIG_LOCK = Lock()
_CSV_CONFIG_LOCK = Lock()


class InspectionRejectedError(ValueError):
    pass


@contextmanager
def prepared_vector_path(path: Path, *, declared_format: str, temp_root: Path) -> Iterator[Path]:
    """Yield a GDAL-readable path, securely materializing zipped Shapefiles.

    Archives are never passed to GDAL's virtual filesystem.  This prevents nested
    archive/VSI handlers and validates every member before extracting the one
    Shapefile dataset into a server-owned temporary directory.
    """
    if declared_format != "shapefile_zip":
        yield path
        return

    temp_root.mkdir(parents=True, exist_ok=True)
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        raise InspectionRejectedError("Shapefile upload must be a valid ZIP archive") from error
    with archive:
        members = [item for item in archive.infolist() if not item.is_dir()]
        if not members or len(members) > MAX_ARCHIVE_ENTRIES:
            raise InspectionRejectedError(
                f"ZIP must contain between 1 and {MAX_ARCHIVE_ENTRIES} files"
            )
        total_size = 0
        basenames: set[str] = set()
        for member in members:
            member_path = Path(member.filename.replace("\\", "/"))
            if member_path.is_absolute() or ".." in member_path.parts:
                raise InspectionRejectedError("ZIP contains an unsafe path")
            unix_mode = member.external_attr >> 16
            if unix_mode and stat.S_ISLNK(unix_mode):
                raise InspectionRejectedError("ZIP symbolic links are not allowed")
            total_size += member.file_size
            if total_size > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
                raise InspectionRejectedError("ZIP uncompressed size exceeds the safety limit")
            if (
                member.file_size > 0
                and member.compress_size > 0
                and member.file_size / member.compress_size > MAX_ARCHIVE_COMPRESSION_RATIO
            ):
                raise InspectionRejectedError("ZIP compression ratio exceeds the safety limit")
            normalized_basename = member_path.name.casefold()
            if normalized_basename in basenames:
                raise InspectionRejectedError("ZIP contains duplicate file names")
            basenames.add(normalized_basename)

        shp_members = [item for item in members if Path(item.filename).suffix.casefold() == ".shp"]
        if len(shp_members) != 1:
            raise InspectionRejectedError("ZIP must contain exactly one .shp file")
        shp_member = shp_members[0]
        dataset_stem = Path(shp_member.filename).stem.casefold()
        matching = [item for item in members if Path(item.filename).stem.casefold() == dataset_stem]
        suffixes = {Path(item.filename).suffix.casefold() for item in matching}
        missing = {".shp", ".shx", ".dbf"} - suffixes
        if missing:
            raise InspectionRejectedError(
                "Shapefile ZIP is missing required sidecars: " + ", ".join(sorted(missing))
            )

        from tempfile import TemporaryDirectory

        with TemporaryDirectory(prefix="shape-", dir=temp_root) as directory:
            extracted_root = Path(directory)
            for member in matching:
                target = extracted_root / Path(member.filename).name
                with archive.open(member) as source, target.open("wb") as destination:
                    shutil.copyfileobj(source, destination, length=1024 * 1024)
            yield extracted_root / Path(shp_member.filename).name


def resolve_artifact_path(root: Path, storage_key: str, sha256: str) -> Path:
    match = _STORAGE_KEY.fullmatch(storage_key)
    if match is None or match.group("prefix") != sha256[:2] or match.group("digest") != sha256:
        raise InspectionRejectedError("artifact storage key does not match its SHA-256")
    resolved_root = root.resolve()
    unresolved_path = resolved_root / Path(storage_key)
    current = resolved_root
    for component in Path(storage_key).parts:
        current /= component
        if current.is_symlink():
            raise InspectionRejectedError("artifact path must not contain symbolic links")
    resolved_path = unresolved_path.resolve()
    if not resolved_path.is_relative_to(resolved_root) or not resolved_path.is_file():
        raise InspectionRejectedError("artifact file is unavailable")
    return resolved_path


@contextmanager
def _safe_gdal_configuration(temp_root: Path) -> Iterator[None]:
    temp_root.mkdir(parents=True, exist_ok=True)
    options: dict[str, str | None] = {
        "CPL_TMPDIR": str(temp_root),
        "GDAL_DISABLE_READDIR_ON_OPEN": "EMPTY_DIR",
        "GDAL_DRIVER_PATH": "disable",
        "GDAL_PYTHON_DRIVER_PATH": "disable",
        "GDAL_VRT_ENABLE_PYTHON": "NO",
        "OGR_SQLITE_LOAD_EXTENSIONS": "",
        "OGR_GEOJSON_MAX_OBJ_SIZE": "16",
    }
    with _GDAL_CONFIG_LOCK:
        previous = {name: pyogrio.get_gdal_config_option(name) for name in options}
        pyogrio.set_gdal_config_options(options)
        try:
            yield
        finally:
            pyogrio.set_gdal_config_options(previous)


def _json_value(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, np.generic):
        value = value.item()
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    if isinstance(value, float) and not np.isfinite(value):
        return None
    if isinstance(value, str):
        return value[:MAX_SAMPLE_STRING]
    if isinstance(value, (str, int, float, bool)):
        return value
    return str(value)[:MAX_SAMPLE_STRING]


def _read_sample(path: Path, layer: str | int, field_names: list[str]) -> list[dict[str, Any]]:
    selected_fields = field_names[:SAMPLE_FIELDS]
    _meta, _fids, _geometry, field_data = read(
        path,
        layer=layer,
        columns=selected_fields,
        read_geometry=False,
        max_features=SAMPLE_FEATURES,
    )
    if not field_data:
        return []
    row_count = len(field_data[0])
    return [
        {
            field_name: _json_value(field_data[column_index][row_index])
            for column_index, field_name in enumerate(selected_fields)
        }
        for row_index in range(row_count)
    ]


def _inspect_geoparquet(path: Path) -> dict[str, Any]:
    try:
        parquet = pq.ParquetFile(path)
        metadata = parquet.metadata
    except Exception as error:
        raise InspectionRejectedError("GeoParquet metadata is invalid") from error
    file_metadata = metadata.metadata or {}
    if b"geo" not in file_metadata:
        raise InspectionRejectedError("Parquet artifact does not contain GeoParquet metadata")
    if metadata.num_rows > MAX_GEOPARQUET_ROWS:
        raise InspectionRejectedError(
            f"GeoParquet exceeds the {MAX_GEOPARQUET_ROWS}-feature inspection limit"
        )
    uncompressed_bytes = sum(
        metadata.row_group(index).total_byte_size for index in range(metadata.num_row_groups)
    )
    if uncompressed_bytes > MAX_GEOPARQUET_UNCOMPRESSED_BYTES:
        raise InspectionRejectedError("GeoParquet uncompressed size exceeds the safety limit")
    try:
        frame = gpd.read_parquet(path)
    except Exception as error:
        raise InspectionRejectedError("GeoParquet reader could not open the artifact") from error
    geometry_columns = [str(name) for name in frame.columns if str(frame[name].dtype) == "geometry"]
    if len(geometry_columns) != 1:
        raise InspectionRejectedError("GeoParquet must contain exactly one geometry column")
    geometry_column = geometry_columns[0]
    field_names = [str(name) for name in frame.columns if str(name) != geometry_column]
    if len(field_names) > MAX_FIELDS_PER_LAYER:
        raise InspectionRejectedError(f"GeoParquet exceeds the {MAX_FIELDS_PER_LAYER}-field limit")
    sample = [
        {field: _json_value(row[field]) for field in field_names[:SAMPLE_FIELDS]}
        for _, row in frame.head(SAMPLE_FEATURES).iterrows()
    ]
    warnings: list[dict[str, Any]] = []
    if len(field_names) > SAMPLE_FIELDS:
        warnings.append(
            {
                "code": "SAMPLE_FIELDS_TRUNCATED",
                "layer": path.stem,
                "message": f"Sample contains the first {SAMPLE_FIELDS} fields.",
            }
        )
    bounds = None if frame.empty else [float(value) for value in frame.total_bounds]
    geometry_types = sorted(str(item) for item in frame.geom_type.dropna().unique())
    return {
        "adapter_name": "geopandas-geoparquet",
        "adapter_version": gpd.__version__,
        "gdal_version": None,
        "driver": "GeoParquet",
        "counts": {"total": len(frame), "read": 0, "accepted": 0, "quarantined": 0, "rejected": 0},
        "layers": [
            {
                "name": path.stem,
                "geometry_type": geometry_types[0] if len(geometry_types) == 1 else "Mixed",
                "crs": None if frame.crs is None else frame.crs.to_string(),
                "encoding": None,
                "feature_count": len(frame),
                "extent": bounds,
                "fields": [{"name": name, "dtype": str(frame[name].dtype)} for name in field_names],
                "sample": sample,
            }
        ],
        "errors": [],
        "warnings": warnings,
        "publish_blockers": [
            {
                "code": "MAPPING_REQUIRED",
                "message": "Canonical field and geometry mapping has not been confirmed.",
            }
        ],
    }


def _inspect_csv(path: Path) -> dict[str, Any]:
    with _CSV_CONFIG_LOCK:
        previous_limit = csv.field_size_limit()
        csv.field_size_limit(1024 * 1024)
        try:
            with path.open("r", encoding="utf-8-sig", newline="") as source:
                dialect_sample = source.read(64 * 1024)
                source.seek(0)
                try:
                    dialect = csv.Sniffer().sniff(dialect_sample, delimiters=",;\t")
                except csv.Error:
                    dialect = csv.excel
                reader = csv.reader(source, dialect=dialect, strict=True)
                try:
                    raw_header = next(reader)
                except StopIteration as error:
                    raise InspectionRejectedError("CSV artifact has no header row") from error
                fields = [field.strip() for field in raw_header]
                if not fields or any(not field for field in fields):
                    raise InspectionRejectedError("CSV header contains an empty field name")
                if len(fields) != len(set(fields)):
                    raise InspectionRejectedError("CSV header contains duplicate field names")
                if len(fields) > MAX_FIELDS_PER_LAYER:
                    raise InspectionRejectedError(
                        f"CSV exceeds the {MAX_FIELDS_PER_LAYER}-field limit"
                    )
                sample: list[dict[str, Any]] = []
                for row_index, row in enumerate(reader, start=2):
                    if len(row) != len(fields):
                        raise InspectionRejectedError(
                            f"CSV row {row_index} has {len(row)} values; expected {len(fields)}"
                        )
                    sample.append(
                        {
                            field: value[:MAX_SAMPLE_STRING]
                            for field, value in zip(
                                fields[:SAMPLE_FIELDS], row[:SAMPLE_FIELDS], strict=True
                            )
                        }
                    )
                    if len(sample) == SAMPLE_FEATURES:
                        break
        except UnicodeDecodeError as error:
            raise InspectionRejectedError("CSV must use UTF-8 encoding") from error
        except csv.Error as error:
            raise InspectionRejectedError("CSV structure is invalid") from error
        finally:
            csv.field_size_limit(previous_limit)

    warnings: list[dict[str, Any]] = [
        {
            "code": "FEATURE_COUNT_NOT_MATERIALIZED",
            "message": "CSV row count is deferred until validation.",
        },
        {
            "code": "CSV_COORDINATES_UNDECLARED",
            "message": "X/Y columns and source CRS must be explicitly mapped.",
        },
    ]
    if len(fields) > SAMPLE_FIELDS:
        warnings.append(
            {
                "code": "SAMPLE_FIELDS_TRUNCATED",
                "layer": "csv",
                "message": f"Sample contains the first {SAMPLE_FIELDS} fields.",
            }
        )
    return {
        "adapter_name": "stdlib-csv",
        "adapter_version": sys.version.split()[0],
        "gdal_version": None,
        "driver": "CSV",
        "counts": {
            "total": 0,
            "read": 0,
            "accepted": 0,
            "quarantined": 0,
            "rejected": 0,
        },
        "layers": [
            {
                "name": "csv",
                "geometry_type": None,
                "crs": None,
                "encoding": "UTF-8",
                "feature_count": -1,
                "extent": None,
                "fields": [{"name": field, "dtype": "string"} for field in fields],
                "sample": sample,
                "delimiter": dialect.delimiter,
            }
        ],
        "errors": [],
        "warnings": warnings,
        "publish_blockers": [
            {
                "code": "MAPPING_REQUIRED",
                "message": "Canonical fields, X/Y columns and source CRS are not confirmed.",
            }
        ],
    }


def inspect_vector_artifact(
    path: Path,
    *,
    declared_format: str,
    temp_root: Path,
) -> dict[str, Any]:
    if declared_format == "csv":
        return _inspect_csv(path)
    if declared_format == "geoparquet":
        return _inspect_geoparquet(path)
    expected_driver = {"geojson": "GeoJSON", "gpkg": "GPKG", "shapefile_zip": "ESRI Shapefile"}.get(
        declared_format
    )
    if expected_driver is None:
        raise InspectionRejectedError(
            f"inspection adapter is not implemented for {declared_format!r}"
        )

    with (
        prepared_vector_path(
            path, declared_format=declared_format, temp_root=temp_root
        ) as prepared,
        _safe_gdal_configuration(temp_root),
    ):
        try:
            discovered_layers = pyogrio.list_layers(prepared)
        except Exception as error:
            raise InspectionRejectedError("GDAL could not open the uploaded artifact") from error
        if len(discovered_layers) == 0:
            raise InspectionRejectedError("artifact contains no readable layers")
        if len(discovered_layers) > MAX_LAYERS:
            raise InspectionRejectedError(f"artifact exceeds the {MAX_LAYERS}-layer limit")

        layers: list[dict[str, Any]] = []
        warnings: list[dict[str, Any]] = []
        for layer_index, discovered in enumerate(discovered_layers):
            layer_name = str(discovered[0])
            info = pyogrio.read_info(prepared, layer=layer_index)
            actual_driver = str(info["driver"])
            if actual_driver != expected_driver:
                raise InspectionRejectedError(
                    f"declared {declared_format!r} artifact opened as {actual_driver!r}"
                )
            field_names = [str(item) for item in info["fields"]]
            if len(field_names) > MAX_FIELDS_PER_LAYER:
                raise InspectionRejectedError(
                    f"layer {layer_name!r} exceeds the {MAX_FIELDS_PER_LAYER}-field limit"
                )
            dtypes = [str(item) for item in info["dtypes"]]
            if len(field_names) > SAMPLE_FIELDS:
                warnings.append(
                    {
                        "code": "SAMPLE_FIELDS_TRUNCATED",
                        "layer": layer_name,
                        "message": f"Sample contains the first {SAMPLE_FIELDS} fields.",
                    }
                )
            bounds = info.get("total_bounds")
            layers.append(
                {
                    "name": layer_name,
                    "geometry_type": info.get("geometry_type"),
                    "crs": info.get("crs"),
                    "encoding": info.get("encoding"),
                    "feature_count": int(info.get("features", -1)),
                    "extent": None if bounds is None else [float(value) for value in bounds],
                    "fields": [
                        {"name": name, "dtype": dtypes[index]}
                        for index, name in enumerate(field_names)
                    ],
                    "sample": _read_sample(prepared, layer_index, field_names),
                }
            )

    known_counts = [layer["feature_count"] for layer in layers]
    total = sum(count for count in known_counts if count >= 0)
    if any(count < 0 for count in known_counts):
        warnings.append(
            {
                "code": "FEATURE_COUNT_NOT_MATERIALIZED",
                "message": "At least one driver did not provide a cheap feature count.",
            }
        )
    return {
        "adapter_name": f"pyogrio-{declared_format}",
        "adapter_version": pyogrio.__version__,
        "gdal_version": pyogrio.__gdal_version_string__,
        "driver": expected_driver,
        "counts": {
            "total": total,
            "read": 0,
            "accepted": 0,
            "quarantined": 0,
            "rejected": 0,
        },
        "layers": layers,
        "errors": [],
        "warnings": warnings,
        "publish_blockers": [
            {
                "code": "MAPPING_REQUIRED",
                "message": "Canonical field and geometry mapping has not been confirmed.",
            }
        ],
    }


def report_as_json(report: dict[str, Any]) -> dict[str, Any]:
    return cast(dict[str, Any], json.loads(json.dumps(report, allow_nan=False)))
