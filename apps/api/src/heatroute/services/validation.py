from __future__ import annotations

import csv
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any
from uuid import UUID, uuid5

import geopandas as gpd
import numpy as np
import shapely
from geoalchemy2.shape import from_shape
from pyogrio.raw import read
from pyproj import CRS, Transformer, network
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session

from heatroute.config import Settings
from heatroute.models import (
    CanonicalFeature,
    Dataset,
    DatasetImport,
    DatasetLayerVersion,
    DatasetVersion,
    DatasetVersionArtifact,
    ImportReport,
    Job,
    MappingProfileVersion,
    OutboxEvent,
    Project,
    RawArtifact,
    StagingFeature,
)
from heatroute.services.feature_contracts import REFERENCE_FIELDS, validate_attributes
from heatroute.services.ingestion import transition_dataset_version
from heatroute.services.inspection import (
    _json_value,
    _safe_gdal_configuration,
    prepared_vector_path,
    resolve_artifact_path,
)

BATCH_SIZE = 500
MAX_VALIDATION_ROWS = 1_000_000
MAX_REPORTED_ISSUES = 1_000
MAX_COORDINATES_PER_FEATURE = 1_000_000

GEOMETRY_TYPES_BY_KIND: dict[str, frozenset[str]] = {
    "building": frozenset({"Polygon", "MultiPolygon"}),
    "road": frozenset({"Polygon", "MultiPolygon"}),
    "utility_line": frozenset({"LineString", "MultiLineString"}),
    "network_node": frozenset({"Point"}),
    "network_edge": frozenset({"LineString"}),
    "connection_candidate": frozenset({"Point"}),
    "forbidden_zone": frozenset({"Polygon", "MultiPolygon"}),
    "coverage_area": frozenset({"Polygon", "MultiPolygon"}),
    "crossing_portal": frozenset({"LineString"}),
    "entry_gate": frozenset({"Polygon"}),
}


class ImportValidationError(ValueError):
    pass


class ImportStateConflictError(ValueError):
    pass


class PublishBlockedError(ValueError):
    pass


class TransformValueError(ValueError):
    pass


@dataclass(frozen=True)
class SourceFeature:
    source_row: int
    properties: dict[str, Any]
    geometry_wkb: bytes | None


@dataclass(frozen=True)
class ValidationResult:
    dataset_import: DatasetImport
    dataset_version: DatasetVersion
    job: Job
    report: ImportReport


@dataclass(frozen=True)
class PublicationResult:
    dataset_import: DatasetImport
    dataset_version: DatasetVersion
    job: Job
    published_features: int


def request_dataset_validation(
    session: Session,
    *,
    import_id: UUID,
    workspace_id: UUID,
) -> DatasetImport:
    dataset_import = session.scalar(
        select(DatasetImport)
        .where(
            DatasetImport.id == import_id,
            DatasetImport.workspace_id == workspace_id,
        )
        .with_for_update()
    )
    if dataset_import is None:
        raise LookupError("dataset import not found")
    if dataset_import.state in {"validating", "needs_review", "ready_to_publish", "published"}:
        return dataset_import
    if dataset_import.state != "ready_to_validate":
        raise ImportStateConflictError(
            f"validation cannot start while import is {dataset_import.state!r}"
        )
    job = session.get(Job, dataset_import.job_id)
    if job is None:
        raise RuntimeError("dataset import job is missing")
    existing = session.scalar(
        select(OutboxEvent).where(
            OutboxEvent.aggregate_id == dataset_import.id,
            OutboxEvent.event_type == "dataset_import.validate_requested",
            OutboxEvent.state == "pending",
        )
    )
    if existing is None:
        session.add(
            OutboxEvent(
                aggregate_type="dataset_import",
                aggregate_id=dataset_import.id,
                event_type="dataset_import.validate_requested",
                payload={"import_id": str(dataset_import.id)},
                state="pending",
                attempts=0,
            )
        )
    job.state = "queued"
    job.phase = "validation_queued"
    job.error_code = None
    session.commit()
    from heatroute.services.runs import publish_outbox_event

    event = session.scalar(
        select(OutboxEvent).where(
            OutboxEvent.aggregate_id == dataset_import.id,
            OutboxEvent.event_type == "dataset_import.validate_requested",
            OutboxEvent.state == "pending",
        )
    )
    if event is not None:
        publish_outbox_event(session, event.id)
    session.refresh(dataset_import)
    return dataset_import


def _iter_csv_rows(path: Path, *, delimiter: str) -> Iterator[list[SourceFeature]]:
    batch: list[SourceFeature] = []
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        reader = csv.DictReader(source, delimiter=delimiter, strict=True)
        if reader.fieldnames is None:
            raise ImportValidationError("CSV artifact has no header row")
        for row_number, row in enumerate(reader, start=2):
            if None in row:
                raise ImportValidationError(f"CSV row {row_number} has an invalid width")
            batch.append(
                SourceFeature(
                    source_row=row_number,
                    properties={str(key): value for key, value in row.items()},
                    geometry_wkb=None,
                )
            )
            if len(batch) == BATCH_SIZE:
                yield batch
                batch = []
        if batch:
            yield batch


def _iter_vector_rows(
    path: Path,
    *,
    layer_name: str,
    field_names: list[str],
    temp_root: Path,
    declared_format: str,
) -> Iterator[list[SourceFeature]]:
    if declared_format == "geoparquet":
        try:
            frame = gpd.read_parquet(path)
        except Exception as error:
            raise ImportValidationError("GeoParquet reader could not open the artifact") from error
        geometry_column = frame.geometry.name
        for offset in range(0, len(frame), BATCH_SIZE):
            batch = frame.iloc[offset : offset + BATCH_SIZE]
            vector_rows: list[SourceFeature] = []
            for row_index, (_, row) in enumerate(batch.iterrows()):
                geometry = row[geometry_column]
                vector_rows.append(
                    SourceFeature(
                        source_row=offset + row_index + 1,
                        properties={field: _json_value(row[field]) for field in field_names},
                        geometry_wkb=None if geometry is None else shapely.to_wkb(geometry),
                    )
                )
            yield vector_rows
        return
    offset = 0
    with (
        prepared_vector_path(
            path, declared_format=declared_format, temp_root=temp_root
        ) as prepared,
        _safe_gdal_configuration(temp_root),
    ):
        while True:
            _meta, _fids, geometry_data, field_data = read(
                prepared,
                layer=layer_name,
                columns=field_names,
                skip_features=offset,
                max_features=BATCH_SIZE,
                return_fids=False,
            )
            row_count = (
                len(field_data[0])
                if field_data
                else (0 if geometry_data is None else len(geometry_data))
            )
            if row_count == 0:
                break
            rows: list[SourceFeature] = []
            for row_index in range(row_count):
                raw_geometry = None if geometry_data is None else geometry_data[row_index]
                rows.append(
                    SourceFeature(
                        source_row=offset + row_index + 1,
                        properties={
                            field_name: _json_value(field_data[column_index][row_index])
                            for column_index, field_name in enumerate(field_names)
                        },
                        geometry_wkb=None if raw_geometry is None else bytes(raw_geometry),
                    )
                )
            yield rows
            offset += row_count
            if row_count < BATCH_SIZE:
                break


def _is_missing(value: Any) -> bool:
    return value is None or (isinstance(value, str) and not value.strip())


def _apply_transforms(value: Any, transforms: list[dict[str, Any]]) -> Any:
    current = value
    for transform in transforms:
        operation = str(transform["op"])
        try:
            if operation == "rename":
                continue
            if operation == "constant":
                current = transform["value"]
            elif operation == "trim":
                if not isinstance(current, str):
                    raise TransformValueError("trim requires a string")
                current = current.strip()
            elif operation == "parse_decimal":
                if isinstance(current, bool) or current is None:
                    raise TransformValueError("parse_decimal requires a numeric value")
                normalized = str(current).strip()
                if transform.get("decimal_separator") == ",":
                    normalized = normalized.replace(",", ".")
                current = Decimal(normalized)
                if not current.is_finite():
                    raise TransformValueError("decimal value must be finite")
            elif operation == "enum_map":
                mapping = transform["mapping"]
                key = str(current)
                if key in mapping:
                    current = mapping[key]
                elif transform.get("unmapped") is not None:
                    current = transform["unmapped"]
                else:
                    raise TransformValueError("value is not present in enum_map")
            elif operation == "unit_convert":
                numeric = current if isinstance(current, Decimal) else Decimal(str(current))
                current = numeric * Decimal(str(transform["factor"]))
                if not current.is_finite():
                    raise TransformValueError("converted value must be finite")
            elif operation == "date_parse":
                current = (
                    datetime.strptime(str(current), str(transform["format"])).date().isoformat()
                )
            else:
                raise TransformValueError(f"unsupported transform {operation!r}")
        except (InvalidOperation, ValueError, TypeError, KeyError) as error:
            if isinstance(error, TransformValueError):
                raise
            raise TransformValueError(f"{operation} could not transform the value") from error
    if isinstance(current, Decimal):
        return float(current)
    if isinstance(current, np.generic):
        return _json_value(current)
    return current


def _transformers(source_crs: str, working_crs: str) -> tuple[CRS, Transformer, Transformer]:
    network.set_network_enabled(False)  # type: ignore[attr-defined]
    source = CRS.from_user_input(source_crs)
    try:
        to_wgs84 = Transformer.from_crs(
            source,
            "EPSG:4326",
            always_xy=True,
            allow_ballpark=False,
            only_best=True,
        )
        to_working = Transformer.from_crs(
            source,
            working_crs,
            always_xy=True,
            allow_ballpark=False,
            only_best=True,
        )
    except Exception as error:
        raise ImportValidationError("required CRS transformation is unavailable") from error
    return source, to_wgs84, to_working


def _transform_geometry(geometry: Any, transformer: Transformer) -> Any:
    def transform_xy(x: Any, y: Any, z: Any | None = None) -> tuple[Any, ...]:
        transformed_x, transformed_y = transformer.transform(x, y, errcheck=True)
        if z is None:
            return transformed_x, transformed_y
        return transformed_x, transformed_y, z

    return shapely.transform(
        geometry,
        transform_xy,
        include_z=bool(shapely.has_z(geometry)),
        interleaved=False,
    )


def _validated_geometry(
    raw_wkb: bytes | None,
    *,
    csv_point: tuple[Any, Any] | None,
    source_crs: CRS,
    to_wgs84: Transformer,
    to_working: Transformer,
    target_kind: str,
) -> tuple[Any | None, str | None]:
    try:
        if csv_point is not None:
            x = float(Decimal(str(csv_point[0]).strip()))
            y = float(Decimal(str(csv_point[1]).strip()))
            if not np.isfinite([x, y]).all():
                return None, "COORDINATE_NOT_FINITE"
            geometry = shapely.Point(x, y)
        elif raw_wkb is None:
            return None, "GEOMETRY_MISSING"
        else:
            geometry = shapely.from_wkb(raw_wkb, on_invalid="raise")
    except Exception:
        return None, "GEOMETRY_PARSE_ERROR"
    if geometry is None or shapely.is_empty(geometry):
        return None, "GEOMETRY_EMPTY"
    coordinates = shapely.get_coordinates(geometry)
    if coordinates.size == 0 or not np.isfinite(coordinates).all():
        return None, "COORDINATE_NOT_FINITE"
    if len(coordinates) > MAX_COORDINATES_PER_FEATURE:
        return None, "GEOMETRY_TOO_COMPLEX"
    if source_crs.is_geographic and (
        (coordinates[:, 0] < -180).any()
        or (coordinates[:, 0] > 180).any()
        or (coordinates[:, 1] < -90).any()
        or (coordinates[:, 1] > 90).any()
    ):
        return None, "SOURCE_COORDINATE_OUT_OF_RANGE"
    if not shapely.is_valid(geometry):
        reason = str(shapely.is_valid_reason(geometry)).upper().replace(" ", "_")
        return None, "GEOMETRY_INVALID_" + reason.split("[")[0][:80]
    allowed_types = GEOMETRY_TYPES_BY_KIND[target_kind]
    if geometry.geom_type not in allowed_types:
        return None, "GEOMETRY_TYPE_MISMATCH"
    try:
        working_geometry = _transform_geometry(geometry, to_working)
        wgs84_geometry = _transform_geometry(geometry, to_wgs84)
    except Exception:
        return None, "CRS_TRANSFORM_FAILED"
    if (
        shapely.is_empty(working_geometry)
        or not np.isfinite(shapely.get_coordinates(working_geometry)).all()
    ):
        return None, "WORKING_GEOMETRY_INVALID"
    wgs84_coordinates = shapely.get_coordinates(wgs84_geometry)
    if (
        wgs84_coordinates.size == 0
        or not np.isfinite(wgs84_coordinates).all()
        or (wgs84_coordinates[:, 0] < -180).any()
        or (wgs84_coordinates[:, 0] > 180).any()
        or (wgs84_coordinates[:, 1] < -90).any()
        or (wgs84_coordinates[:, 1] > 90).any()
    ):
        return None, "WGS84_COORDINATE_OUT_OF_RANGE"
    target_crs = to_working.target_crs
    area = None if target_crs is None else target_crs.area_of_use
    if (
        area is not None
        and not (area.west <= -180 and area.east >= 180 and area.south <= -90 and area.north >= 90)
        and (
            (wgs84_coordinates[:, 0] < area.west).any()
            or (wgs84_coordinates[:, 0] > area.east).any()
            or (wgs84_coordinates[:, 1] < area.south).any()
            or (wgs84_coordinates[:, 1] > area.north).any()
        )
    ):
        return None, "GEOGRAPHIC_EXTENT_OUTSIDE_WORKING_CRS_AREA"
    return wgs84_geometry, None


def _append_issue(target: list[dict[str, Any]], issue: dict[str, Any]) -> bool:
    if len(target) >= MAX_REPORTED_ISSUES:
        return False
    target.append(issue)
    return True


def execute_dataset_validation(
    session: Session,
    *,
    settings: Settings,
    import_id: UUID,
) -> ValidationResult | None:
    dataset_import = session.scalar(
        select(DatasetImport).where(DatasetImport.id == import_id).with_for_update()
    )
    if dataset_import is None:
        raise LookupError("dataset import not found")
    if dataset_import.state not in {"ready_to_validate", "validating"}:
        return None
    version = session.get(DatasetVersion, dataset_import.dataset_version_id)
    job = session.get(Job, dataset_import.job_id)
    report = session.scalar(
        select(ImportReport).where(ImportReport.dataset_import_id == dataset_import.id)
    )
    project = session.get(Project, dataset_import.project_id)
    mapping = (
        session.scalar(
            select(MappingProfileVersion).where(
                MappingProfileVersion.profile_id == version.mapping_profile_id,
                MappingProfileVersion.revision == version.mapping_profile_version,
            )
        )
        if version is not None
        else None
    )
    link = session.scalar(
        select(DatasetVersionArtifact).where(
            DatasetVersionArtifact.dataset_version_id == dataset_import.dataset_version_id,
            DatasetVersionArtifact.ordinal == 0,
        )
    )
    artifact = session.get(RawArtifact, link.raw_artifact_id) if link is not None else None
    if any(row is None for row in (version, job, report, project, mapping, artifact)):
        raise ImportValidationError("validation provenance is incomplete")
    assert version is not None and job is not None and report is not None
    assert project is not None and mapping is not None and artifact is not None
    if not project.working_crs or not project.crs_confirmed:
        raise ImportValidationError("project working CRS is not confirmed")

    definition = mapping.definition
    source_crs, to_wgs84, to_working = _transformers(
        str(definition["source_crs"]), project.working_crs
    )
    dataset_import.state = "validating"
    if version.status == "ready_to_validate":
        transition_dataset_version(version, "validating")
    elif version.status != "validating":
        raise ImportStateConflictError(
            f"dataset version cannot validate while it is {version.status!r}"
        )
    job.state = "running"
    job.phase = "validating"
    job.attempt += 1
    job.heartbeat_at = datetime.now(UTC)
    session.execute(delete(StagingFeature).where(StagingFeature.dataset_import_id == import_id))
    session.commit()

    artifact_path = resolve_artifact_path(
        settings.artifact_root, artifact.storage_key, artifact.sha256
    )
    layer_name = str(definition["layer_name"])
    source_namespace = str(definition.get("source_namespace") or layer_name)
    inspected_layer = next(
        (layer for layer in report.layers if layer.get("name") == layer_name), None
    )
    if inspected_layer is None:
        raise ImportValidationError("mapped layer is absent from the inspection report")
    declared_format = str(definition["declared_format"])
    if declared_format == "csv":
        iterator = _iter_csv_rows(
            artifact_path,
            delimiter=str(inspected_layer.get("delimiter") or ","),
        )
    else:
        iterator = _iter_vector_rows(
            artifact_path,
            layer_name=layer_name,
            field_names=[str(field["name"]) for field in inspected_layer["fields"]],
            temp_root=settings.artifact_root / ".validate-tmp",
            declared_format=declared_format,
        )

    errors: list[dict[str, Any]] = []
    warnings: list[dict[str, Any]] = [
        warning for warning in report.warnings if warning.get("code") in {"SAMPLE_FIELDS_TRUNCATED"}
    ]
    issue_report_truncated = False
    total_rows = 0
    missing_value_distribution: dict[str, int] = {}
    coordinate_columns = definition.get("coordinate_columns")
    for batch in iterator:
        staged_batch: list[StagingFeature] = []
        for source in batch:
            total_rows += 1
            if total_rows > MAX_VALIDATION_ROWS:
                raise ImportValidationError(
                    f"validation exceeds the {MAX_VALIDATION_ROWS}-feature limit"
                )
            raw_source_id = source.properties.get(str(definition["source_id_field"]))
            source_id = None if _is_missing(raw_source_id) else str(raw_source_id).strip()
            status = "accepted"
            issue_codes: list[str] = []
            attributes: dict[str, Any] = {}

            if source_id is None or len(source_id) > 500:
                status = "quarantined"
                issue_codes.append("SOURCE_ID_REQUIRED")
            for target_field, field_mapping in definition["fields"].items():
                source_field = field_mapping.get("source_field")
                value = source.properties.get(str(source_field)) if source_field else None
                if _is_missing(value) and not any(
                    item["op"] == "constant" for item in field_mapping["transforms"]
                ):
                    missing_value_distribution[target_field] = (
                        missing_value_distribution.get(target_field, 0) + 1
                    )
                    policy = str(definition["missing_policy"])
                    if policy == "report_and_keep_null":
                        attributes[target_field] = None
                        issue_codes.append("MISSING_VALUE_KEPT_NULL")
                        issue_report_truncated |= not _append_issue(
                            warnings,
                            {
                                "code": "MISSING_VALUE_KEPT_NULL",
                                "source_row": source.source_row,
                                "source_id": source_id,
                                "field": target_field,
                                "message": "Missing mapped value was retained as null by policy.",
                            },
                        )
                        continue
                    status = "rejected" if policy == "reject" else "quarantined"
                    issue_codes.append("MISSING_REQUIRED_VALUE")
                    destination = errors if status == "rejected" else warnings
                    issue_report_truncated |= not _append_issue(
                        destination,
                        {
                            "code": "MISSING_REQUIRED_VALUE",
                            "source_row": source.source_row,
                            "source_id": source_id,
                            "field": target_field,
                            "message": "Mapped source value is missing.",
                        },
                    )
                    continue
                try:
                    attributes[target_field] = _apply_transforms(
                        value, list(field_mapping["transforms"])
                    )
                except TransformValueError:
                    if status != "rejected":
                        status = "quarantined"
                    issue_codes.append("FIELD_TRANSFORM_FAILED")
                    issue_report_truncated |= not _append_issue(
                        warnings,
                        {
                            "code": "FIELD_TRANSFORM_FAILED",
                            "source_row": source.source_row,
                            "source_id": source_id,
                            "field": target_field,
                            "message": "A whitelisted field transform rejected the value.",
                        },
                    )

            for attribute_issue, target_field in validate_attributes(
                str(definition["target_kind"]), attributes
            ):
                if status != "rejected":
                    status = "quarantined"
                issue_codes.append(attribute_issue)
                issue_report_truncated |= not _append_issue(
                    warnings,
                    {
                        "code": attribute_issue,
                        "source_row": source.source_row,
                        "source_id": source_id,
                        "field": target_field,
                        "message": "The mapped value violates the canonical feature contract.",
                    },
                )

            csv_point = None
            if coordinate_columns is not None:
                csv_point = (
                    source.properties.get(str(coordinate_columns["x"])),
                    source.properties.get(str(coordinate_columns["y"])),
                )
            geometry, geometry_issue = _validated_geometry(
                source.geometry_wkb,
                csv_point=csv_point,
                source_crs=source_crs,
                to_wgs84=to_wgs84,
                to_working=to_working,
                target_kind=str(definition["target_kind"]),
            )
            if geometry_issue is not None:
                if status != "rejected":
                    status = "quarantined"
                issue_codes.append(geometry_issue)
                issue_report_truncated |= not _append_issue(
                    warnings,
                    {
                        "code": geometry_issue,
                        "source_row": source.source_row,
                        "source_id": source_id,
                        "message": "Geometry was quarantined without automatic repair.",
                    },
                )
            staged = StagingFeature(
                workspace_id=dataset_import.workspace_id,
                dataset_import_id=dataset_import.id,
                source_row=source.source_row,
                source_id=source_id,
                source_layer=source_namespace,
                target_kind=str(definition["target_kind"]),
                status=status,
                issue_codes=sorted(set(issue_codes)),
                raw_properties=source.properties,
                attributes=attributes,
                geometry_wgs84=None if geometry is None else from_shape(geometry, srid=4326),
            )
            session.add(staged)
            staged_batch.append(staged)
        session.flush()
        for staged in staged_batch:
            session.expunge(staged)

    duplicate_ids = (
        session.execute(
            select(StagingFeature.source_id)
            .where(
                StagingFeature.dataset_import_id == import_id,
                StagingFeature.source_id.is_not(None),
            )
            .group_by(StagingFeature.source_id)
            .having(func.count(StagingFeature.id) > 1)
        )
        .scalars()
        .all()
    )
    for duplicate_id in duplicate_ids:
        duplicate_rows = session.scalars(
            select(StagingFeature).where(
                StagingFeature.dataset_import_id == import_id,
                StagingFeature.source_id == duplicate_id,
            )
        ).all()
        for row in duplicate_rows:
            if row.status != "rejected":
                row.status = "quarantined"
            row.issue_codes = sorted(set(row.issue_codes + ["DUPLICATE_SOURCE_ID"]))
            issue_report_truncated |= not _append_issue(
                warnings,
                {
                    "code": "DUPLICATE_SOURCE_ID",
                    "source_row": row.source_row,
                    "source_id": duplicate_id,
                    "message": "Every occurrence of the duplicate source ID was quarantined.",
                },
            )

    target_kind = str(definition["target_kind"])
    latest_published_versions = (
        select(
            DatasetVersion.dataset_id.label("dataset_id"),
            func.max(DatasetVersion.version).label("version"),
        )
        .where(DatasetVersion.status == "published")
        .group_by(DatasetVersion.dataset_id)
        .subquery()
    )
    for reference_field, referenced_kind in REFERENCE_FIELDS.get(target_kind, {}).items():
        rows_with_references = session.scalars(
            select(StagingFeature).where(
                StagingFeature.dataset_import_id == import_id,
                StagingFeature.status != "rejected",
            )
        ).all()
        reference_values = {
            str(row.attributes[reference_field])
            for row in rows_with_references
            if row.attributes.get(reference_field) is not None
        }
        existing_references = set(
            session.scalars(
                select(CanonicalFeature.source_id)
                .where(
                    CanonicalFeature.workspace_id == dataset_import.workspace_id,
                    CanonicalFeature.project_id == dataset_import.project_id,
                    CanonicalFeature.kind == referenced_kind,
                    CanonicalFeature.source_id.in_(reference_values),
                )
                .join(
                    DatasetVersion,
                    DatasetVersion.id == CanonicalFeature.dataset_version_id,
                )
                .join(
                    latest_published_versions,
                    (latest_published_versions.c.dataset_id == DatasetVersion.dataset_id)
                    & (latest_published_versions.c.version == DatasetVersion.version),
                )
            ).all()
        )
        for row in rows_with_references:
            reference_value = row.attributes.get(reference_field)
            if reference_value is None or str(reference_value) in existing_references:
                continue
            row.status = "quarantined"
            row.issue_codes = sorted(set(row.issue_codes + ["REFERENCE_NOT_FOUND"]))
            issue_report_truncated |= not _append_issue(
                warnings,
                {
                    "code": "REFERENCE_NOT_FOUND",
                    "source_row": row.source_row,
                    "source_id": row.source_id,
                    "field": reference_field,
                    "reference": str(reference_value),
                    "referenced_kind": referenced_kind,
                    "message": "Reference was not found in a published version of this project.",
                },
            )

    # SessionLocal deliberately disables autoflush, so duplicate quarantine updates
    # must reach PostgreSQL before the aggregate count determines the import state.
    session.flush()

    grouped_count_rows = session.execute(
        select(StagingFeature.status, func.count(StagingFeature.id))
        .where(StagingFeature.dataset_import_id == import_id)
        .group_by(StagingFeature.status)
    ).all()
    grouped_counts: dict[str, int] = {
        str(count_row[0]): int(count_row[1]) for count_row in grouped_count_rows
    }
    counts = {
        "total": total_rows,
        "read": total_rows,
        "accepted": int(grouped_counts.get("accepted", 0)),
        "quarantined": int(grouped_counts.get("quarantined", 0)),
        "rejected": int(grouped_counts.get("rejected", 0)),
    }
    if sum(counts[key] for key in ("accepted", "quarantined", "rejected")) != total_rows:
        raise RuntimeError("validation counts are inconsistent")

    blockers: list[dict[str, Any]] = []
    if total_rows == 0 or counts["accepted"] == 0:
        blockers.append(
            {"code": "NO_ACCEPTED_FEATURES", "message": "No feature is eligible to publish."}
        )
    if counts["rejected"]:
        blockers.append(
            {
                "code": "REJECTED_FEATURES_PRESENT",
                "message": "Rejected features require a mapping or source correction.",
            }
        )
    if counts["quarantined"]:
        blockers.append(
            {
                "code": "QUARANTINE_CONFIRMATION_REQUIRED",
                "message": "Publishing requires an explicit coverage limitation policy.",
            }
        )
    if issue_report_truncated:
        warnings.append(
            {
                "code": "ISSUE_REPORT_TRUNCATED",
                "message": f"Only the first {MAX_REPORTED_ISSUES} issues per severity are shown.",
            }
        )

    report.stage = "validation"
    report.counts = counts
    report.errors = errors
    report.warnings = warnings
    report.publish_blockers = blockers
    report.missing_value_distribution = missing_value_distribution
    report.repairs = []
    report.crs_diagnostics = [
        {
            "source_crs": str(definition["source_crs"]),
            "working_crs": project.working_crs,
            "api_crs": "EPSG:4326",
            "allow_ballpark": False,
            "only_best": True,
            "network_enabled": False,
        }
    ]
    report.topology_diagnostics = [
        issue
        for issue in warnings
        if issue.get("code") in {"REFERENCE_NOT_FOUND", "DUPLICATE_SOURCE_ID"}
    ]
    report.coverage_diagnostics = (
        []
        if target_kind == "coverage_area"
        else [
            {
                "code": "COVERAGE_UNKNOWN",
                "kind": target_kind,
                "message": "Coverage must be established by a published coverage_area.",
            }
        ]
    )
    selected_layer = session.scalar(
        select(DatasetLayerVersion).where(
            DatasetLayerVersion.dataset_version_id == version.id,
            DatasetLayerVersion.name == layer_name,
        )
    )
    if selected_layer is not None:
        selected_layer.status = "validated"
    if blockers:
        transition_dataset_version(version, "needs_review")
        dataset_import.state = "needs_review"
    else:
        transition_dataset_version(version, "ready_to_publish")
        dataset_import.state = "ready_to_publish"
    version.transform_definition = {
        **(version.transform_definition or {}),
        "source_crs": str(definition["source_crs"]),
        "working_crs": project.working_crs,
        "wgs84_crs": "EPSG:4326",
        "allow_ballpark": False,
        "only_best": True,
        "network_enabled": False,
        "operation_status": "validated",
    }
    job.state = "succeeded"
    job.phase = "validation_complete"
    job.result = {"counts": counts, "next_action": dataset_import.state}
    job.heartbeat_at = datetime.now(UTC)
    session.commit()
    for refreshed in (dataset_import, version, job, report):
        session.refresh(refreshed)
    return ValidationResult(dataset_import, version, job, report)


def request_dataset_publication(
    session: Session,
    *,
    import_id: UUID,
    workspace_id: UUID,
    confirm_quarantine: bool,
    coverage_limitations: str | None,
) -> DatasetImport:
    dataset_import = session.scalar(
        select(DatasetImport)
        .where(
            DatasetImport.id == import_id,
            DatasetImport.workspace_id == workspace_id,
        )
        .with_for_update()
    )
    if dataset_import is None:
        raise LookupError("dataset import not found")
    if dataset_import.state in {"publishing", "published"}:
        return dataset_import
    version = session.get(DatasetVersion, dataset_import.dataset_version_id)
    report = session.scalar(
        select(ImportReport).where(ImportReport.dataset_import_id == dataset_import.id)
    )
    job = session.get(Job, dataset_import.job_id)
    if version is None or report is None or job is None:
        raise PublishBlockedError("publication provenance is incomplete")
    rejected = int(report.counts.get("rejected", 0))
    quarantined = int(report.counts.get("quarantined", 0))
    accepted = int(report.counts.get("accepted", 0))
    if accepted == 0:
        raise PublishBlockedError("at least one accepted feature is required for publication")
    if rejected:
        raise PublishBlockedError("rejected features block publication")
    normalized_limitations = (coverage_limitations or "").strip()
    if quarantined and (not confirm_quarantine or not normalized_limitations):
        raise PublishBlockedError(
            "quarantined features require confirmation and coverage_limitations"
        )
    if dataset_import.state == "needs_review":
        if not quarantined:
            raise PublishBlockedError("validation blockers must be resolved before publication")
        transition_dataset_version(version, "ready_to_publish")
        dataset_import.state = "ready_to_publish"
    if dataset_import.state != "ready_to_publish":
        raise ImportStateConflictError(
            f"publication cannot start while import is {dataset_import.state!r}"
        )
    version.publication_policy = {
        "confirm_quarantine": confirm_quarantine,
        "coverage_limitations": normalized_limitations or None,
        "accepted": int(report.counts.get("accepted", 0)),
        "quarantined": quarantined,
        "rejected": rejected,
    }
    event = session.scalar(
        select(OutboxEvent).where(
            OutboxEvent.aggregate_id == dataset_import.id,
            OutboxEvent.event_type == "dataset_import.publish_requested",
        )
    )
    if event is None:
        event = OutboxEvent(
            aggregate_type="dataset_import",
            aggregate_id=dataset_import.id,
            event_type="dataset_import.publish_requested",
            payload={"import_id": str(dataset_import.id)},
            state="pending",
            attempts=0,
        )
        session.add(event)
    job.state = "queued"
    job.phase = "publication_queued"
    job.error_code = None
    session.commit()
    from heatroute.services.runs import publish_outbox_event

    if event.state == "pending":
        publish_outbox_event(session, event.id)
    session.refresh(dataset_import)
    return dataset_import


def execute_dataset_publication(
    session: Session,
    *,
    import_id: UUID,
) -> PublicationResult | None:
    dataset_import = session.scalar(
        select(DatasetImport).where(DatasetImport.id == import_id).with_for_update()
    )
    if dataset_import is None:
        raise LookupError("dataset import not found")
    version = session.get(DatasetVersion, dataset_import.dataset_version_id)
    job = session.get(Job, dataset_import.job_id)
    report = session.scalar(
        select(ImportReport).where(ImportReport.dataset_import_id == dataset_import.id)
    )
    if version is None or job is None or report is None:
        raise PublishBlockedError("publication provenance is incomplete")
    if dataset_import.state == "published":
        count = session.scalar(
            select(func.count(CanonicalFeature.id)).where(
                CanonicalFeature.dataset_version_id == version.id
            )
        )
        return PublicationResult(dataset_import, version, job, int(count or 0))
    if dataset_import.state != "ready_to_publish":
        return None

    transition_dataset_version(version, "publishing")
    dataset_import.state = "publishing"
    job.state = "running"
    job.phase = "publishing"
    job.attempt += 1
    job.heartbeat_at = datetime.now(UTC)
    session.commit()

    dataset = session.get(Dataset, version.dataset_id)
    if dataset is None:
        raise PublishBlockedError("dataset is missing")
    accepted = session.scalars(
        select(StagingFeature)
        .where(
            StagingFeature.dataset_import_id == import_id,
            StagingFeature.status == "accepted",
        )
        .order_by(StagingFeature.source_row)
    ).all()
    existing_count = session.scalar(
        select(func.count(CanonicalFeature.id)).where(
            CanonicalFeature.dataset_version_id == version.id
        )
    )
    if existing_count:
        raise PublishBlockedError("canonical snapshot already exists before publication")
    for staging in accepted:
        if staging.source_id is None or staging.geometry_wgs84 is None:
            raise PublishBlockedError("accepted staging feature is incomplete")
        session.add(
            CanonicalFeature(
                workspace_id=dataset_import.workspace_id,
                project_id=dataset_import.project_id,
                dataset_version_id=version.id,
                staging_feature_id=staging.id,
                logical_id=uuid5(
                    dataset.id,
                    f"{staging.target_kind}:{staging.source_layer}:{staging.source_id}",
                ),
                kind=staging.target_kind,
                source_id=staging.source_id,
                source_layer=staging.source_layer,
                source_type=version.source_type,
                lifecycle_status="unknown",
                quality_flags=staging.issue_codes,
                raw_properties=staging.raw_properties,
                attributes=staging.attributes,
                geometry_wgs84=staging.geometry_wgs84,
            )
        )
    published_at = datetime.now(UTC)
    transition_dataset_version(version, "published")
    dataset_import.state = "published"
    version.published_at = published_at
    report.stage = "published"
    report.publish_blockers = []
    layer_versions = session.scalars(
        select(DatasetLayerVersion).where(DatasetLayerVersion.dataset_version_id == version.id)
    ).all()
    for layer_version in layer_versions:
        layer_version.status = "published" if layer_version.mapped_kind else "not_selected"
    version.coverage_refs = [
        str(
            uuid5(
                dataset.id,
                f"{staging.target_kind}:{staging.source_layer}:{staging.source_id}",
            )
        )
        for staging in accepted
        if staging.target_kind == "coverage_area"
    ]
    if int(report.counts.get("quarantined", 0)):
        report.warnings = report.warnings + [
            {
                "code": "QUARANTINE_ACCEPTED_WITH_LIMITATION",
                "message": str((version.publication_policy or {})["coverage_limitations"]),
            }
        ]
    job.state = "succeeded"
    job.phase = "published"
    job.result = {
        "dataset_version_id": str(version.id),
        "published_features": len(accepted),
    }
    job.heartbeat_at = published_at
    session.commit()
    for row in (dataset_import, version, job):
        session.refresh(row)
    return PublicationResult(dataset_import, version, job, len(accepted))
