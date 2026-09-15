from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from pyproj import CRS
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.models import (
    DatasetImport,
    DatasetLayerVersion,
    DatasetVersion,
    ImportReport,
    Job,
    MappingProfile,
    MappingProfileVersion,
    Project,
)
from heatroute.services.feature_contracts import validate_attribute_mapping
from heatroute.services.ingestion import transition_dataset_version


class MappingNotReadyError(ValueError):
    pass


class MappingValidationError(ValueError):
    pass


class MappingRevisionConflictError(ValueError):
    def __init__(self, current_revision: int) -> None:
        super().__init__("mapping profile revision is stale")
        self.current_revision = current_revision


@dataclass(frozen=True)
class SavedMapping:
    profile: MappingProfile
    revision: MappingProfileVersion
    dataset_import: DatasetImport
    dataset_version: DatasetVersion


def _definition_hash(definition: dict[str, Any]) -> str:
    encoded = json.dumps(
        definition,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _validated_source_crs(value: str) -> str:
    try:
        crs = CRS.from_user_input(value)
    except Exception as error:
        raise MappingValidationError("source_crs is not a valid CRS") from error
    authority = crs.to_authority()
    return f"{authority[0]}:{authority[1]}" if authority is not None else crs.to_wkt()


def validate_mapping_definition(
    *,
    definition: dict[str, Any],
    report: ImportReport,
    declared_format: str,
    project: Project,
) -> dict[str, Any]:
    matching_layers = [
        layer for layer in report.layers if layer.get("name") == definition["layer_name"]
    ]
    if len(matching_layers) != 1:
        raise MappingValidationError("layer_name must match exactly one inspected layer")
    inspected_layer = matching_layers[0]
    available_fields = {
        str(field["name"]) for field in inspected_layer.get("fields", [])
    }
    referenced_fields = {str(definition["source_id_field"])}
    referenced_fields.update(
        str(mapping["source_field"])
        for mapping in definition["fields"].values()
        if mapping.get("source_field") is not None
    )
    coordinate_columns = definition.get("coordinate_columns")
    if declared_format == "csv":
        if coordinate_columns is None:
            raise MappingValidationError("CSV mapping requires explicit X and Y columns")
        referenced_fields.update(
            {str(coordinate_columns["x"]), str(coordinate_columns["y"])}
        )
    elif coordinate_columns is not None:
        raise MappingValidationError("coordinate_columns are only supported by the CSV adapter")
    missing_fields = sorted(referenced_fields - available_fields)
    if missing_fields:
        raise MappingValidationError(f"source fields are missing: {missing_fields}")
    if not project.working_crs or not project.crs_confirmed:
        raise MappingNotReadyError("project working CRS must be explicitly confirmed")
    contract_errors = validate_attribute_mapping(
        str(definition["target_kind"]), set(definition["fields"])
    )
    if contract_errors:
        raise MappingValidationError("; ".join(contract_errors))
    normalized = dict(definition)
    source_crs = _validated_source_crs(str(definition["source_crs"]))
    inspected_crs = inspected_layer.get("crs")
    if inspected_crs is None and not bool(definition.get("source_crs_confirmed")):
        raise MappingValidationError(
            "source_crs must be explicitly confirmed when the source has no CRS declaration"
        )
    if inspected_crs is not None and CRS.from_user_input(inspected_crs) != CRS.from_user_input(
        source_crs
    ):
        raise MappingValidationError("source_crs conflicts with the inspected layer CRS")
    normalized["source_crs"] = source_crs
    normalized["adapter_name"] = declared_format
    normalized["declared_format"] = declared_format
    return normalized


def save_import_mapping(
    session: Session,
    *,
    import_id: UUID,
    workspace_id: UUID,
    definition: dict[str, Any],
    profile_id: UUID | None,
    profile_name: str,
    expected_revision: int | None,
) -> SavedMapping:
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
    version = session.get(DatasetVersion, dataset_import.dataset_version_id)
    report = session.scalar(
        select(ImportReport).where(ImportReport.dataset_import_id == dataset_import.id)
    )
    job = session.get(Job, dataset_import.job_id)
    project = session.get(Project, dataset_import.project_id)
    if version is None or report is None or job is None or project is None:
        raise MappingNotReadyError("inspection report or import provenance is incomplete")
    if dataset_import.state not in {"mapping_required", "ready_to_validate"}:
        raise MappingNotReadyError(
            f"mapping cannot be saved while import is {dataset_import.state!r}"
        )
    declared_format = str(job.payload.get("declared_format", ""))
    normalized = validate_mapping_definition(
        definition=definition,
        report=report,
        declared_format=declared_format,
        project=project,
    )
    fingerprint = _definition_hash(normalized)
    revision: MappingProfileVersion | None = None

    if dataset_import.state == "ready_to_validate":
        if profile_id is not None and profile_id != version.mapping_profile_id:
            raise MappingNotReadyError("dataset version already uses another mapping profile")
        existing_revision = session.scalar(
            select(MappingProfileVersion).where(
                MappingProfileVersion.profile_id == version.mapping_profile_id,
                MappingProfileVersion.revision == version.mapping_profile_version,
            )
        )
        profile = session.get(MappingProfile, version.mapping_profile_id)
        if (
            existing_revision is not None
            and profile is not None
            and existing_revision.definition_hash == fingerprint
        ):
            return SavedMapping(profile, existing_revision, dataset_import, version)
        raise MappingNotReadyError("dataset version already has a different mapping")

    if profile_id is None:
        if expected_revision is not None:
            raise MappingValidationError("If-Match is not valid when creating a mapping profile")
        profile = MappingProfile(
            workspace_id=workspace_id,
            project_id=project.id,
            name=profile_name.strip(),
            current_revision=1,
        )
        session.add(profile)
        session.flush()
        revision_number = 1
    else:
        profile = session.scalar(
            select(MappingProfile)
            .where(
                MappingProfile.id == profile_id,
                MappingProfile.workspace_id == workspace_id,
                MappingProfile.project_id == project.id,
            )
            .with_for_update()
        )
        if profile is None:
            raise LookupError("mapping profile not found")
        if expected_revision is None or expected_revision != profile.current_revision:
            raise MappingRevisionConflictError(profile.current_revision)
        latest = session.scalar(
            select(MappingProfileVersion).where(
                MappingProfileVersion.profile_id == profile.id,
                MappingProfileVersion.revision == profile.current_revision,
            )
        )
        if latest is not None and latest.definition_hash == fingerprint:
            revision = latest
            revision_number = latest.revision
        else:
            profile.current_revision += 1
            revision_number = profile.current_revision

    if revision is None:
        revision = MappingProfileVersion(
            profile_id=profile.id,
            revision=revision_number,
            definition_hash=fingerprint,
            definition=normalized,
        )
        session.add(revision)
        session.flush()

    version.mapping_profile_id = profile.id
    version.mapping_profile_version = revision.revision
    version.source_crs = normalized["source_crs"]
    version.transform_definition = {
        "source_crs": normalized["source_crs"],
        "working_crs": project.working_crs,
        "operation_status": "pending_validation",
    }
    version.transform_hash = _definition_hash(version.transform_definition)
    layer_versions = session.scalars(
        select(DatasetLayerVersion).where(
            DatasetLayerVersion.dataset_version_id == version.id
        )
    ).all()
    for layer_version in layer_versions:
        if layer_version.name == normalized["layer_name"]:
            layer_version.mapped_kind = str(normalized["target_kind"])
            layer_version.status = "mapped"
    transition_dataset_version(version, "ready_to_validate")
    dataset_import.state = "ready_to_validate"
    report.publish_blockers = [
        blocker
        for blocker in report.publish_blockers
        if blocker.get("code") != "MAPPING_REQUIRED"
    ] + [
        {
            "code": "VALIDATION_REQUIRED",
            "message": "Mapped features have not completed validation.",
        }
    ]
    session.commit()
    for row in (profile, revision, dataset_import, version):
        session.refresh(row)
    return SavedMapping(profile, revision, dataset_import, version)
