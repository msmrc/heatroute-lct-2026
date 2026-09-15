from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from uuid import UUID, uuid4

from fastapi import UploadFile
from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.orm import Session

from heatroute.config import Settings
from heatroute.models import (
    Dataset,
    DatasetImport,
    DatasetLayerVersion,
    DatasetVersion,
    DatasetVersionArtifact,
    ImportReport,
    Job,
    OutboxEvent,
    Project,
    RawArtifact,
)
from heatroute.services.artifacts import (
    EmptyArtifactError,
    LocalArtifactStorage,
    safe_display_filename,
)
from heatroute.services.inspection import inspect_vector_artifact, resolve_artifact_path

SUPPORTED_UPLOAD_SUFFIXES = {".csv", ".geojson", ".gpkg", ".json", ".parquet", ".zip"}


class UnsupportedUploadFormatError(ValueError):
    pass


class EmptyUploadError(ValueError):
    pass


class InvalidDatasetTransitionError(ValueError):
    pass


DATASET_TRANSITIONS: dict[str, frozenset[str]] = {
    "uploaded": frozenset({"inspected", "failed", "rejected", "cancelled"}),
    "inspected": frozenset({"mapping_required", "ready_to_validate", "failed", "rejected"}),
    "mapping_required": frozenset({"ready_to_validate", "failed", "rejected", "cancelled"}),
    "ready_to_validate": frozenset({"validating", "failed", "cancelled"}),
    "validating": frozenset({"needs_review", "ready_to_publish", "failed", "cancelled"}),
    "needs_review": frozenset({"ready_to_validate", "ready_to_publish", "failed", "rejected"}),
    "ready_to_publish": frozenset({"publishing", "failed", "cancelled"}),
    "publishing": frozenset({"published", "failed"}),
    "published": frozenset(),
    "failed": frozenset(),
    "rejected": frozenset(),
    "cancelled": frozenset(),
}


@dataclass(frozen=True)
class AcceptedUpload:
    dataset: Dataset
    version: DatasetVersion
    artifact: RawArtifact
    dataset_import: DatasetImport
    job: Job
    original_filename: str
    physical_artifact_created: bool


@dataclass(frozen=True)
class CompletedInspection:
    dataset_import: DatasetImport
    version: DatasetVersion
    job: Job
    report: ImportReport


def transition_dataset_version(version: DatasetVersion, target: str) -> None:
    allowed = DATASET_TRANSITIONS.get(version.status, frozenset())
    if target not in allowed:
        raise InvalidDatasetTransitionError(
            f"dataset version cannot transition from {version.status!r} to {target!r}"
        )
    version.status = target


def _declared_format(filename: str) -> str:
    suffix = Path(filename).suffix.lower()
    if suffix not in SUPPORTED_UPLOAD_SUFFIXES:
        supported = ", ".join(sorted(SUPPORTED_UPLOAD_SUFFIXES))
        raise UnsupportedUploadFormatError(f"supported upload extensions: {supported}")
    if suffix in {".geojson", ".json"}:
        return "geojson"
    if suffix == ".parquet":
        return "geoparquet"
    if suffix == ".zip":
        return "shapefile_zip"
    return suffix.removeprefix(".")


def accept_dataset_upload(
    session: Session,
    *,
    settings: Settings,
    project: Project,
    upload: UploadFile,
    dataset_id: UUID | None,
    dataset_name: str | None,
    purpose: str | None,
    source_observed_at: datetime | None,
    license_note: str | None,
    contains_sensitive_infrastructure: bool,
) -> AcceptedUpload:
    original_filename = safe_display_filename(upload.filename)
    declared_format = _declared_format(original_filename)
    if dataset_id is None:
        normalized_name = (dataset_name or "").strip()
        if not normalized_name:
            raise ValueError("dataset_name is required when dataset_id is omitted")
        dataset = Dataset(
            workspace_id=project.workspace_id,
            project_id=project.id,
            name=normalized_name,
            purpose=purpose,
        )
        session.add(dataset)
        session.flush()
        version_number = 1
    else:
        existing_dataset = session.scalar(
            select(Dataset)
            .where(
                Dataset.id == dataset_id,
                Dataset.project_id == project.id,
                Dataset.workspace_id == project.workspace_id,
            )
            .with_for_update()
        )
        if existing_dataset is None:
            raise LookupError("dataset not found")
        dataset = existing_dataset
        current_version = session.scalar(
            select(func.coalesce(func.max(DatasetVersion.version), 0)).where(
                DatasetVersion.dataset_id == dataset.id
            )
        )
        assert current_version is not None
        version_number = current_version + 1

    try:
        stored = LocalArtifactStorage(
            settings.artifact_root,
            max_upload_bytes=settings.max_upload_bytes,
        ).store(upload.file)
    except EmptyArtifactError as error:
        raise EmptyUploadError("upload must not be empty") from error

    candidate_artifact_id = uuid4()
    inserted_artifact_id = session.scalar(
        insert(RawArtifact)
        .values(
            id=candidate_artifact_id,
            workspace_id=project.workspace_id,
            sha256=stored.sha256,
            size_bytes=stored.size_bytes,
            storage_key=stored.storage_key,
            media_type=upload.content_type,
        )
        .on_conflict_do_nothing(index_elements=[RawArtifact.workspace_id, RawArtifact.sha256])
        .returning(RawArtifact.id)
    )
    artifact = session.get(
        RawArtifact,
        inserted_artifact_id
        or session.scalar(
            select(RawArtifact.id).where(
                RawArtifact.workspace_id == project.workspace_id,
                RawArtifact.sha256 == stored.sha256,
            )
        ),
    )
    if artifact is None:
        raise RuntimeError("raw artifact upsert did not return a persisted row")

    version = DatasetVersion(
        dataset_id=dataset.id,
        version=version_number,
        status="uploaded",
        source_type="synthetic" if project.source_mode == "synthetic" else "user",
        source_name=original_filename,
        source_observed_at=source_observed_at,
        raw_hashes=[stored.sha256],
        working_crs=project.working_crs,
        coverage_refs=[],
        license_note=license_note,
        contains_sensitive_infrastructure=contains_sensitive_infrastructure,
    )
    session.add(version)
    session.flush()
    session.add(
        DatasetVersionArtifact(
            dataset_version_id=version.id,
            raw_artifact_id=artifact.id,
            original_filename=original_filename,
            ordinal=0,
        )
    )
    job = Job(
        workspace_id=project.workspace_id,
        kind="dataset_import",
        state="queued",
        phase="inspection_queued",
        payload={
            "dataset_version_id": str(version.id),
            "declared_format": declared_format,
        },
        result=None,
    )
    session.add(job)
    session.flush()
    dataset_import = DatasetImport(
        workspace_id=project.workspace_id,
        project_id=project.id,
        dataset_version_id=version.id,
        job_id=job.id,
        state="uploaded",
    )
    session.add(dataset_import)
    session.flush()
    event = OutboxEvent(
        aggregate_type="dataset_import",
        aggregate_id=dataset_import.id,
        event_type="dataset_import.inspect_requested",
        payload={"import_id": str(dataset_import.id)},
        state="pending",
        attempts=0,
    )
    session.add(event)
    session.commit()
    from heatroute.services.runs import publish_outbox_event

    publish_outbox_event(session, event.id)
    for row in (dataset, version, artifact, job, dataset_import):
        session.refresh(row)
    return AcceptedUpload(
        dataset=dataset,
        version=version,
        artifact=artifact,
        dataset_import=dataset_import,
        job=job,
        original_filename=original_filename,
        physical_artifact_created=stored.created,
    )


def execute_dataset_inspection(
    session: Session,
    *,
    settings: Settings,
    import_id: UUID,
) -> CompletedInspection | None:
    dataset_import = session.scalar(
        select(DatasetImport).where(DatasetImport.id == import_id).with_for_update()
    )
    if dataset_import is None:
        raise LookupError("dataset import not found")
    if dataset_import.state not in {"uploaded", "inspecting"}:
        return None
    version = session.get(DatasetVersion, dataset_import.dataset_version_id)
    job = session.get(Job, dataset_import.job_id)
    link = session.scalar(
        select(DatasetVersionArtifact).where(
            DatasetVersionArtifact.dataset_version_id == dataset_import.dataset_version_id,
            DatasetVersionArtifact.ordinal == 0,
        )
    )
    if version is None or job is None or link is None:
        raise RuntimeError("dataset import provenance is incomplete")
    artifact = session.get(RawArtifact, link.raw_artifact_id)
    if artifact is None:
        raise RuntimeError("dataset import raw artifact is missing")

    dataset_import.state = "inspecting"
    job.state = "running"
    job.phase = "inspecting"
    job.attempt += 1
    session.commit()

    artifact_path = resolve_artifact_path(
        settings.artifact_root,
        artifact.storage_key,
        artifact.sha256,
    )
    declared_format = str(job.payload["declared_format"])
    inspection = inspect_vector_artifact(
        artifact_path,
        declared_format=declared_format,
        temp_root=settings.artifact_root / ".inspect-tmp",
    )
    report = session.scalar(
        select(ImportReport).where(ImportReport.dataset_import_id == dataset_import.id)
    )
    if report is None:
        report = ImportReport(
            workspace_id=dataset_import.workspace_id,
            dataset_import_id=dataset_import.id,
            stage="inspection",
            counts=inspection["counts"],
            layers=inspection["layers"],
            errors=inspection["errors"],
            warnings=inspection["warnings"],
            publish_blockers=inspection["publish_blockers"],
            source_references=[
                {
                    "artifact_id": str(artifact.id),
                    "sha256": artifact.sha256,
                    "original_filename": link.original_filename,
                }
            ],
        )
        session.add(report)
        session.flush()
    existing_layers = session.scalars(
        select(DatasetLayerVersion).where(
            DatasetLayerVersion.dataset_version_id == version.id
        )
    ).all()
    if not existing_layers:
        for ordinal, layer in enumerate(inspection["layers"]):
            session.add(
                DatasetLayerVersion(
                    dataset_version_id=version.id,
                    name=str(layer["name"]),
                    ordinal=ordinal,
                    geometry_type=(
                        None if layer.get("geometry_type") is None else str(layer["geometry_type"])
                    ),
                    source_crs=None if layer.get("crs") is None else str(layer["crs"]),
                    feature_count=int(layer.get("feature_count", 0)),
                    status="inspected",
                    metadata_json={
                        "encoding": layer.get("encoding"),
                        "delimiter": layer.get("delimiter"),
                        "extent": layer.get("extent"),
                        "fields": layer.get("fields", []),
                    },
                )
            )
    version.import_report_id = report.id
    version.adapter_name = str(inspection["adapter_name"])
    version.adapter_version = str(inspection["adapter_version"])
    layer_crs = {str(layer["crs"]) for layer in inspection["layers"] if layer["crs"]}
    version.source_crs = next(iter(layer_crs)) if len(layer_crs) == 1 else None
    transition_dataset_version(version, "inspected")
    transition_dataset_version(version, "mapping_required")
    dataset_import.state = "mapping_required"
    job.state = "succeeded"
    job.phase = "inspection_complete"
    job.result = {
        "import_report_id": str(report.id),
        "layer_count": len(inspection["layers"]),
        "next_action": "mapping_required",
    }
    session.commit()
    for row in (dataset_import, version, job, report):
        session.refresh(row)
    return CompletedInspection(
        dataset_import=dataset_import,
        version=version,
        job=job,
        report=report,
    )
