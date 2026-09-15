import hashlib
import json
from datetime import datetime
from typing import Annotated
from uuid import UUID

from fastapi import (
    APIRouter,
    Depends,
    File,
    Form,
    Header,
    HTTPException,
    Query,
    Response,
    UploadFile,
    status,
)
from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from heatroute.api.schemas import (
    CanonicalFeatureResponse,
    DatasetImportResponse,
    DatasetLayerResponse,
    DatasetPublishRequest,
    DatasetUploadAcceptedResponse,
    DatasetVersionDiffResponse,
    DatasetVersionResponse,
    ImportReportResponse,
    MappingProfileResponse,
    MappingPutRequest,
    ProjectQualityResponse,
    RawArtifactResponse,
)
from heatroute.config import get_settings
from heatroute.db import get_db_session
from heatroute.models import (
    CanonicalFeature,
    Dataset,
    DatasetImport,
    DatasetLayerVersion,
    DatasetVersion,
    ImportReport,
    Job,
    Project,
)
from heatroute.services.artifacts import UploadTooLargeError
from heatroute.services.auth import Principal, require_editor, require_reader
from heatroute.services.ingestion import (
    EmptyUploadError,
    UnsupportedUploadFormatError,
    accept_dataset_upload,
)
from heatroute.services.mappings import (
    MappingNotReadyError,
    MappingRevisionConflictError,
    MappingValidationError,
    save_import_mapping,
)
from heatroute.services.validation import (
    ImportStateConflictError,
    PublishBlockedError,
    request_dataset_publication,
    request_dataset_validation,
)
from heatroute.services.versioning import compare_dataset_version, project_coverage_quality

router = APIRouter()
DbSession = Annotated[Session, Depends(get_db_session)]
Reader = Annotated[Principal, Depends(require_reader)]
Editor = Annotated[Principal, Depends(require_editor)]
IfMatch = Annotated[str | None, Header(alias="If-Match")]


def _get_project(session: Session, project_id: UUID, workspace_id: UUID) -> Project:
    project = session.scalar(
        select(Project).where(
            Project.id == project_id,
            Project.workspace_id == workspace_id,
        )
    )
    if project is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="project not found")
    return project


@router.post(
    "/projects/{project_id}/datasets/uploads",
    response_model=DatasetUploadAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["datasets", "imports"],
)
def upload_dataset(
    project_id: UUID,
    session: DbSession,
    principal: Editor,
    file: Annotated[
        UploadFile,
        File(description="GeoJSON, GeoPackage, CSV, GeoParquet or zipped Shapefile source"),
    ],
    dataset_id: Annotated[UUID | None, Form()] = None,
    dataset_name: Annotated[str | None, Form(max_length=120)] = None,
    purpose: Annotated[str | None, Form(max_length=64)] = None,
    source_observed_at: Annotated[datetime | None, Form()] = None,
    license_note: Annotated[str | None, Form(max_length=2_000)] = None,
    contains_sensitive_infrastructure: Annotated[bool, Form()] = False,
) -> DatasetUploadAcceptedResponse:
    project = _get_project(session, project_id, principal.workspace_id)
    try:
        accepted = accept_dataset_upload(
            session,
            settings=get_settings(),
            project=project,
            upload=file,
            dataset_id=dataset_id,
            dataset_name=dataset_name,
            purpose=purpose,
            source_observed_at=source_observed_at,
            license_note=license_note,
            contains_sensitive_infrastructure=contains_sensitive_infrastructure,
        )
    except UploadTooLargeError as error:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_413_CONTENT_TOO_LARGE,
            detail={"code": "UPLOAD_TOO_LARGE", "max_bytes": error.max_bytes},
        ) from error
    except UnsupportedUploadFormatError as error:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE, detail=str(error)
        ) from error
    except EmptyUploadError as error:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(error)
        ) from error
    except ValueError as error:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT, detail=str(error)
        ) from error
    except LookupError as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(error)) from error

    version = accepted.version
    return DatasetUploadAcceptedResponse(
        import_id=accepted.dataset_import.id,
        job_id=accepted.job.id,
        job_state=accepted.job.state,
        dataset_id=accepted.dataset.id,
        dataset_version=DatasetVersionResponse(
            id=version.id,
            dataset_id=version.dataset_id,
            version=version.version,
            status=version.status,
            source_type=version.source_type,
            source_name=version.source_name,
            source_observed_at=version.source_observed_at,
            raw_hashes=version.raw_hashes,
            working_crs=version.working_crs,
            license_note=version.license_note,
            contains_sensitive_infrastructure=version.contains_sensitive_infrastructure,
            published_at=version.published_at,
            publication_policy=version.publication_policy,
            created_at=version.created_at,
        ),
        artifact=RawArtifactResponse(
            id=accepted.artifact.id,
            sha256=accepted.artifact.sha256,
            size_bytes=accepted.artifact.size_bytes,
            media_type=accepted.artifact.media_type,
            original_filename=accepted.original_filename,
            deduplicated=not accepted.physical_artifact_created,
        ),
        status_url=f"/api/v1/imports/{accepted.dataset_import.id}",
    )


@router.get(
    "/imports/{import_id}",
    response_model=DatasetImportResponse,
    tags=["imports"],
)
def get_dataset_import(
    import_id: UUID, session: DbSession, principal: Reader
) -> DatasetImportResponse:
    row = session.execute(
        select(DatasetImport, Job)
        .join(Job, Job.id == DatasetImport.job_id)
        .where(
            DatasetImport.id == import_id,
            DatasetImport.workspace_id == principal.workspace_id,
        )
    ).one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="import not found")
    dataset_import, job = row
    return DatasetImportResponse(
        id=dataset_import.id,
        project_id=dataset_import.project_id,
        dataset_version_id=dataset_import.dataset_version_id,
        job_id=job.id,
        state=dataset_import.state,
        job_state=job.state,
        phase=job.phase,
        result=job.result,
        created_at=dataset_import.created_at,
    )


def _get_import_report(
    import_id: UUID, session: Session, workspace_id: UUID
) -> ImportReportResponse:
    row = session.execute(
        select(ImportReport, DatasetImport)
        .join(DatasetImport, DatasetImport.id == ImportReport.dataset_import_id)
        .where(
            ImportReport.dataset_import_id == import_id,
            DatasetImport.workspace_id == workspace_id,
        )
    ).one_or_none()
    if row is None:
        dataset_import = session.scalar(
            select(DatasetImport).where(
                DatasetImport.id == import_id,
                DatasetImport.workspace_id == workspace_id,
            )
        )
        if dataset_import is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="import not found")
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail={"code": "INSPECTION_NOT_READY", "import_state": dataset_import.state},
        )
    report, dataset_import = row
    return ImportReportResponse(
        id=report.id,
        import_id=dataset_import.id,
        stage=report.stage,
        import_state=dataset_import.state,
        counts=report.counts,
        layers=report.layers,
        errors=report.errors,
        warnings=report.warnings,
        publish_blockers=report.publish_blockers,
        missing_value_distribution=report.missing_value_distribution,
        source_references=report.source_references,
        repairs=report.repairs,
        crs_diagnostics=report.crs_diagnostics,
        topology_diagnostics=report.topology_diagnostics,
        coverage_diagnostics=report.coverage_diagnostics,
        created_at=report.created_at,
        updated_at=report.updated_at,
    )


@router.get(
    "/imports/{import_id}/inspection",
    response_model=ImportReportResponse,
    tags=["imports"],
)
def get_import_inspection(
    import_id: UUID, session: DbSession, principal: Reader
) -> ImportReportResponse:
    return _get_import_report(import_id, session, principal.workspace_id)


@router.get(
    "/imports/{import_id}/report",
    response_model=ImportReportResponse,
    tags=["imports"],
)
def get_import_report(
    import_id: UUID, session: DbSession, principal: Reader
) -> ImportReportResponse:
    return _get_import_report(import_id, session, principal.workspace_id)


@router.get(
    "/projects/{project_id}/dataset-versions",
    response_model=list[DatasetVersionResponse],
    tags=["datasets"],
)
def list_dataset_versions(
    project_id: UUID, session: DbSession, principal: Reader
) -> list[DatasetVersionResponse]:
    project = _get_project(session, project_id, principal.workspace_id)
    versions = session.scalars(
        select(DatasetVersion)
        .join(Dataset, Dataset.id == DatasetVersion.dataset_id)
        .where(
            Dataset.project_id == project.id,
            Dataset.workspace_id == project.workspace_id,
        )
        .order_by(DatasetVersion.created_at, DatasetVersion.id)
    ).all()
    return [
        DatasetVersionResponse(
            id=version.id,
            dataset_id=version.dataset_id,
            version=version.version,
            status=version.status,
            source_type=version.source_type,
            source_name=version.source_name,
            source_observed_at=version.source_observed_at,
            raw_hashes=version.raw_hashes,
            working_crs=version.working_crs,
            license_note=version.license_note,
            contains_sensitive_infrastructure=version.contains_sensitive_infrastructure,
            published_at=version.published_at,
            publication_policy=version.publication_policy,
            created_at=version.created_at,
        )
        for version in versions
    ]


@router.get(
    "/projects/{project_id}/dataset-versions/{version_id}/layers",
    response_model=list[DatasetLayerResponse],
    tags=["datasets"],
)
def list_dataset_layers(
    project_id: UUID, version_id: UUID, session: DbSession, principal: Reader
) -> list[DatasetLayerResponse]:
    project = _get_project(session, project_id, principal.workspace_id)
    version = session.scalar(
        select(DatasetVersion)
        .join(Dataset, Dataset.id == DatasetVersion.dataset_id)
        .where(
            DatasetVersion.id == version_id,
            Dataset.project_id == project.id,
            Dataset.workspace_id == project.workspace_id,
        )
    )
    if version is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="dataset version not found"
        )
    rows = session.scalars(
        select(DatasetLayerVersion)
        .where(DatasetLayerVersion.dataset_version_id == version.id)
        .order_by(DatasetLayerVersion.ordinal)
    ).all()
    return [
        DatasetLayerResponse(
            id=row.id,
            dataset_version_id=row.dataset_version_id,
            name=row.name,
            ordinal=row.ordinal,
            geometry_type=row.geometry_type,
            source_crs=row.source_crs,
            mapped_kind=row.mapped_kind,
            feature_count=row.feature_count,
            status=row.status,
        )
        for row in rows
    ]


@router.get(
    "/layers/{layer_id}/tiles/{z}/{x}/{y}.mvt",
    tags=["datasets", "tiles"],
    responses={200: {"content": {"application/vnd.mapbox-vector-tile": {}}}},
)
def get_layer_vector_tile(
    layer_id: UUID,
    z: int,
    x: int,
    y: int,
    session: DbSession,
    principal: Reader,
    if_none_match: Annotated[str | None, Header(alias="If-None-Match")] = None,
) -> Response:
    if z < 0 or z > 22 or x < 0 or y < 0 or x >= 2**z or y >= 2**z:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="tile not found")
    layer = session.execute(
        select(DatasetLayerVersion, DatasetVersion)
        .join(DatasetVersion, DatasetVersion.id == DatasetLayerVersion.dataset_version_id)
        .join(Dataset, Dataset.id == DatasetVersion.dataset_id)
        .where(
            DatasetLayerVersion.id == layer_id,
            Dataset.workspace_id == principal.workspace_id,
            DatasetVersion.status == "published",
            DatasetLayerVersion.status == "published",
        )
    ).one_or_none()
    if layer is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="layer not found")
    layer_version, dataset_version = layer
    cache_identity = (
        f"mvt-v1:{principal.workspace_id}:{layer_version.id}:{dataset_version.version}:{z}:{x}:{y}"
    )
    etag = f'"{hashlib.sha256(cache_identity.encode()).hexdigest()}"'
    headers = {"Cache-Control": "private, max-age=300", "ETag": etag}
    if if_none_match == etag:
        return Response(status_code=status.HTTP_304_NOT_MODIFIED, headers=headers)

    tile = session.scalar(
        text(
            """
            WITH bounds AS (
                SELECT
                    ST_TileEnvelope(:z, :x, :y) AS tile,
                    ST_Transform(
                        ST_TileEnvelope(:z, :x, :y, margin => (64.0 / 4096.0)),
                        4326
                    ) AS query_bounds
            ), features AS (
                SELECT
                    feature.logical_id::text AS feature_id,
                    feature.kind,
                    feature.lifecycle_status,
                    ST_AsMVTGeom(
                        ST_Transform(feature.geometry_wgs84, 3857),
                        bounds.tile,
                        4096,
                        64,
                        true
                    ) AS geom
                FROM canonical_features AS feature
                CROSS JOIN bounds
                WHERE feature.workspace_id = :workspace_id
                  AND feature.dataset_version_id = :dataset_version_id
                  AND feature.source_layer = :source_layer
                  AND feature.geometry_wgs84 && bounds.query_bounds
            )
            SELECT ST_AsMVT(features.*, 'canonical', 4096, 'geom')
            FROM features
            WHERE geom IS NOT NULL
            """
        ),
        {
            "z": z,
            "x": x,
            "y": y,
            "workspace_id": principal.workspace_id,
            "dataset_version_id": dataset_version.id,
            "source_layer": layer_version.name,
        },
    )
    payload = b"" if tile is None else bytes(tile)
    return Response(
        content=payload,
        media_type="application/vnd.mapbox-vector-tile",
        headers=headers,
    )


@router.get(
    "/projects/{project_id}/dataset-versions/{version_id}/diff",
    response_model=DatasetVersionDiffResponse,
    tags=["datasets"],
)
def get_dataset_version_diff(
    project_id: UUID, version_id: UUID, session: DbSession, principal: Reader
) -> DatasetVersionDiffResponse:
    project = _get_project(session, project_id, principal.workspace_id)
    version = session.scalar(
        select(DatasetVersion)
        .join(Dataset, Dataset.id == DatasetVersion.dataset_id)
        .where(
            DatasetVersion.id == version_id,
            Dataset.project_id == project.id,
            Dataset.workspace_id == project.workspace_id,
        )
    )
    if version is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="dataset version not found"
        )
    if version.status != "published":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="diff requires a published version",
        )
    diff = compare_dataset_version(session, version)
    return DatasetVersionDiffResponse(
        dataset_version_id=version.id,
        previous_dataset_version_id=diff.previous_version_id,
        added=diff.added,
        changed=diff.changed,
        deleted=diff.deleted,
        unchanged=diff.unchanged,
        unmapped_layers=diff.unmapped_layers,
    )


@router.get(
    "/projects/{project_id}/quality",
    response_model=ProjectQualityResponse,
    tags=["datasets"],
)
def get_project_quality(
    project_id: UUID, session: DbSession, principal: Reader
) -> ProjectQualityResponse:
    project = _get_project(session, project_id, principal.workspace_id)
    coverage_state, findings = project_coverage_quality(
        session, project_id=project.id, workspace_id=project.workspace_id
    )
    return ProjectQualityResponse(
        project_id=project.id,
        coverage_state=coverage_state,
        findings=findings,
    )


def _mapping_revision(if_match: str | None) -> int | None:
    if if_match is None:
        return None
    normalized = if_match.strip()
    if normalized.startswith("W/"):
        normalized = normalized[2:]
    normalized = normalized.strip('"')
    try:
        return int(normalized)
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="If-Match must contain an integer mapping profile revision",
        ) from error


@router.put(
    "/imports/{import_id}/mapping",
    response_model=MappingProfileResponse,
    tags=["imports", "mappings"],
)
def put_import_mapping(
    import_id: UUID,
    request: MappingPutRequest,
    session: DbSession,
    principal: Editor,
    if_match: IfMatch = None,
) -> MappingProfileResponse:
    if request.profile_id is not None and if_match is None:
        raise HTTPException(
            status_code=status.HTTP_428_PRECONDITION_REQUIRED,
            detail="If-Match is required when revising a mapping profile",
        )
    definition = request.model_dump(
        mode="json",
        exclude={"profile_id", "profile_name"},
    )
    try:
        saved = save_import_mapping(
            session,
            import_id=import_id,
            workspace_id=principal.workspace_id,
            definition=definition,
            profile_id=request.profile_id,
            profile_name=request.profile_name,
            expected_revision=_mapping_revision(if_match),
        )
    except LookupError as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(error)) from error
    except MappingRevisionConflictError as error:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_412_PRECONDITION_FAILED,
            detail={
                "code": "STALE_MAPPING_PROFILE_REVISION",
                "expected": error.current_revision,
            },
        ) from error
    except MappingNotReadyError as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error)) from error
    except MappingValidationError as error:
        session.rollback()
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail=str(error),
        ) from error
    return MappingProfileResponse(
        profile_id=saved.profile.id,
        revision_id=saved.revision.id,
        revision=saved.revision.revision,
        definition_hash=saved.revision.definition_hash,
        dataset_version_id=saved.dataset_version.id,
        dataset_version_status=saved.dataset_version.status,
        import_state=saved.dataset_import.state,
    )


def _dataset_import_response(
    session: Session, dataset_import: DatasetImport
) -> DatasetImportResponse:
    job = session.get(Job, dataset_import.job_id)
    if job is None:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="dataset import job is missing",
        )
    return DatasetImportResponse(
        id=dataset_import.id,
        project_id=dataset_import.project_id,
        dataset_version_id=dataset_import.dataset_version_id,
        job_id=job.id,
        state=dataset_import.state,
        job_state=job.state,
        phase=job.phase,
        result=job.result,
        created_at=dataset_import.created_at,
    )


@router.post(
    "/imports/{import_id}/validate",
    response_model=DatasetImportResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["imports"],
)
def validate_import(
    import_id: UUID, session: DbSession, principal: Editor
) -> DatasetImportResponse:
    try:
        dataset_import = request_dataset_validation(
            session,
            import_id=import_id,
            workspace_id=principal.workspace_id,
        )
    except LookupError as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(error)) from error
    except ImportStateConflictError as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error)) from error
    return _dataset_import_response(session, dataset_import)


@router.get(
    "/projects/{project_id}/imports",
    response_model=list[DatasetImportResponse],
    tags=["imports"],
)
def list_project_imports(
    project_id: UUID, session: DbSession, principal: Reader
) -> list[DatasetImportResponse]:
    project = _get_project(session, project_id, principal.workspace_id)
    imports = session.scalars(
        select(DatasetImport)
        .where(
            DatasetImport.project_id == project.id,
            DatasetImport.workspace_id == project.workspace_id,
        )
        .order_by(DatasetImport.created_at.desc(), DatasetImport.id)
    ).all()
    return [_dataset_import_response(session, item) for item in imports]


@router.post(
    "/imports/{import_id}/publish",
    response_model=DatasetImportResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["imports"],
)
def publish_import(
    import_id: UUID,
    request: DatasetPublishRequest,
    session: DbSession,
    principal: Editor,
) -> DatasetImportResponse:
    try:
        dataset_import = request_dataset_publication(
            session,
            import_id=import_id,
            workspace_id=principal.workspace_id,
            confirm_quarantine=request.confirm_quarantine,
            coverage_limitations=request.coverage_limitations,
        )
    except LookupError as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(error)) from error
    except (ImportStateConflictError, PublishBlockedError) as error:
        session.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error)) from error
    return _dataset_import_response(session, dataset_import)


@router.get(
    "/projects/{project_id}/dataset-versions/{version_id}/features",
    response_model=list[CanonicalFeatureResponse],
    tags=["datasets", "features"],
)
def list_canonical_features(
    project_id: UUID,
    version_id: UUID,
    session: DbSession,
    principal: Reader,
    limit: Annotated[int, Query(ge=1, le=1_000)] = 100,
    offset: Annotated[int, Query(ge=0)] = 0,
) -> list[CanonicalFeatureResponse]:
    project = _get_project(session, project_id, principal.workspace_id)
    version = session.scalar(
        select(DatasetVersion)
        .join(Dataset, Dataset.id == DatasetVersion.dataset_id)
        .where(
            DatasetVersion.id == version_id,
            Dataset.project_id == project.id,
            Dataset.workspace_id == project.workspace_id,
        )
    )
    if version is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="dataset version not found"
        )
    if version.status != "published":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="canonical features are unavailable until the dataset version is published",
        )
    rows = session.execute(
        select(CanonicalFeature, func.ST_AsGeoJSON(CanonicalFeature.geometry_wgs84))
        .where(
            CanonicalFeature.workspace_id == project.workspace_id,
            CanonicalFeature.project_id == project.id,
            CanonicalFeature.dataset_version_id == version.id,
        )
        .order_by(CanonicalFeature.source_layer, CanonicalFeature.source_id)
        .offset(offset)
        .limit(limit)
    ).all()
    return [_canonical_feature_response(feature, geometry_json) for feature, geometry_json in rows]


def _canonical_feature_response(
    feature: CanonicalFeature, geometry_json: str
) -> CanonicalFeatureResponse:
    return CanonicalFeatureResponse(
        id=feature.id,
        logical_id=feature.logical_id,
        dataset_version_id=feature.dataset_version_id,
        kind=feature.kind,
        source_id=feature.source_id,
        source_layer=feature.source_layer,
        source_type=feature.source_type,
        lifecycle_status=feature.lifecycle_status,
        quality_flags=feature.quality_flags,
        raw_properties=feature.raw_properties,
        attributes=feature.attributes,
        geometry=json.loads(geometry_json),
        created_at=feature.created_at,
    )


@router.get(
    "/features/{feature_id}",
    response_model=CanonicalFeatureResponse,
    tags=["features"],
)
def get_canonical_feature(
    feature_id: UUID, session: DbSession, principal: Reader
) -> CanonicalFeatureResponse:
    row = session.execute(
        select(CanonicalFeature, func.ST_AsGeoJSON(CanonicalFeature.geometry_wgs84))
        .join(DatasetVersion, DatasetVersion.id == CanonicalFeature.dataset_version_id)
        .where(
            CanonicalFeature.id == feature_id,
            CanonicalFeature.workspace_id == principal.workspace_id,
            DatasetVersion.status == "published",
        )
    ).one_or_none()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="feature not found")
    feature, geometry_json = row
    return _canonical_feature_response(feature, geometry_json)
