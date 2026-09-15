import csv
import io
from html import escape
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response, status
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.api.schemas import (
    CostCatalogCreate,
    CostCatalogResponse,
    CostCatalogVersionCreate,
    CostCatalogVersionResponse,
)
from heatroute.db import get_db_session
from heatroute.models import Project
from heatroute.services.auth import Principal, require_editor, require_reader
from heatroute.services.cost_catalogs import (
    CostCatalogRecord,
    CostCatalogValidationError,
    StaleCostCatalogRevisionError,
    create_cost_catalog,
    get_cost_catalog,
    list_cost_catalogs,
    revise_cost_catalog,
)
from heatroute.services.exports import safe_csv_cell

router = APIRouter()
DbSession = Annotated[Session, Depends(get_db_session)]
Reader = Annotated[Principal, Depends(require_reader)]
Editor = Annotated[Principal, Depends(require_editor)]
IfMatch = Annotated[str | None, Header(alias="If-Match")]


def _precondition(value: str | None) -> int:
    if value is None:
        raise HTTPException(428, "If-Match revision is required")
    try:
        return int(value.strip().removeprefix("W/").strip('"'))
    except ValueError as error:
        raise HTTPException(400, "If-Match must contain an integer revision") from error


def _response(record: CostCatalogRecord) -> CostCatalogResponse:
    catalog = record.catalog
    return CostCatalogResponse(
        id=catalog.id,
        workspace_id=catalog.workspace_id,
        project_id=catalog.project_id,
        name=catalog.name,
        current_revision=catalog.current_revision,
        created_at=catalog.created_at,
        updated_at=catalog.updated_at,
        versions=[
            CostCatalogVersionResponse(
                id=version.id,
                catalog_id=version.catalog_id,
                revision=version.revision,
                definition_hash=version.definition_hash,
                status=version.status,
                definition=version.definition,
                created_at=version.created_at,
            )
            for version in record.versions
        ],
    )


def _project(session: Session, project_id: UUID, workspace_id: UUID) -> Project:
    project = session.scalar(
        select(Project).where(
            Project.id == project_id, Project.workspace_id == workspace_id
        )
    )
    if project is None:
        raise HTTPException(404, "project not found")
    return project


@router.get(
    "/projects/{project_id}/cost-catalogs",
    response_model=list[CostCatalogResponse],
    tags=["cost-catalogs"],
)
def list_project_cost_catalogs(
    project_id: UUID, session: DbSession, principal: Reader
) -> list[CostCatalogResponse]:
    _project(session, project_id, principal.workspace_id)
    return [
        _response(record)
        for record in list_cost_catalogs(
            session, project_id=project_id, workspace_id=principal.workspace_id
        )
    ]


@router.post(
    "/projects/{project_id}/cost-catalogs",
    response_model=CostCatalogResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["cost-catalogs"],
)
def create_project_cost_catalog(
    project_id: UUID,
    request: CostCatalogCreate,
    session: DbSession,
    principal: Editor,
) -> CostCatalogResponse:
    project = _project(session, project_id, principal.workspace_id)
    try:
        return _response(
            create_cost_catalog(
                session,
                project=project,
                name=request.name,
                definition=request.definition,
            )
        )
    except CostCatalogValidationError as error:
        raise HTTPException(
            422, {"code": "INVALID_COST_CATALOG", "message": str(error)}
        ) from error


@router.get(
    "/cost-catalogs/{catalog_id}",
    response_model=CostCatalogResponse,
    tags=["cost-catalogs"],
)
def read_cost_catalog(
    catalog_id: UUID, session: DbSession, principal: Reader
) -> CostCatalogResponse:
    record = get_cost_catalog(
        session, catalog_id=catalog_id, workspace_id=principal.workspace_id
    )
    if record is None:
        raise HTTPException(404, "cost catalog not found")
    return _response(record)


@router.post(
    "/cost-catalogs/{catalog_id}/versions",
    response_model=CostCatalogResponse,
    status_code=status.HTTP_201_CREATED,
    tags=["cost-catalogs"],
)
def create_cost_catalog_version(
    catalog_id: UUID,
    request: CostCatalogVersionCreate,
    session: DbSession,
    principal: Editor,
    if_match: IfMatch = None,
) -> CostCatalogResponse:
    try:
        record = revise_cost_catalog(
            session,
            catalog_id=catalog_id,
            workspace_id=principal.workspace_id,
            expected_revision=_precondition(if_match),
            definition=request.definition,
        )
    except StaleCostCatalogRevisionError as error:
        raise HTTPException(
            412,
            {
                "code": "STALE_COST_CATALOG_REVISION",
                "expected": error.expected,
                "received": error.received,
            },
        ) from error
    except CostCatalogValidationError as error:
        raise HTTPException(
            422, {"code": "INVALID_COST_CATALOG", "message": str(error)}
        ) from error
    if record is None:
        raise HTTPException(404, "cost catalog not found")
    return _response(record)


@router.get("/cost-catalogs/{catalog_id}/export", tags=["cost-catalogs"])
def export_cost_catalog(
    catalog_id: UUID,
    session: DbSession,
    principal: Reader,
    format: Literal["json", "csv", "html"] = Query(default="json"),
) -> Response:
    record = get_cost_catalog(
        session, catalog_id=catalog_id, workspace_id=principal.workspace_id
    )
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="catalog not found")
    version = record.versions[-1]
    definition = version.definition
    label = f"{record.catalog.name} — {version.status}"
    if format == "json":
        import json

        return Response(
            content=json.dumps(
                {"label": label, "status": version.status, "definition": definition},
                ensure_ascii=False,
                indent=2,
            ),
            media_type="application/json",
        )
    if format == "csv":
        stream = io.StringIO(newline="")
        writer = csv.writer(stream)
        writer.writerow(["catalog_label", safe_csv_cell(label)])
        writer.writerow(
            ["code", "description", "quantity_unit", "per", "method", "rate", "source"]
        )
        for item in definition["items"]:
            writer.writerow(
                [
                    safe_csv_cell(item.get("code")),
                    safe_csv_cell(item.get("description")),
                    safe_csv_cell(item.get("quantity_unit")),
                    safe_csv_cell(item.get("per")),
                    safe_csv_cell(item.get("applies_to_method")),
                    safe_csv_cell(item.get("rate")),
                    safe_csv_cell(item.get("source_reference")),
                ]
            )
        return Response(content=stream.getvalue(), media_type="text/csv; charset=utf-8")
    rows = "".join(
        "<tr>"
        + "".join(
            f"<td>{escape(str(item.get(key, '')))}</td>"
            for key in ("code", "description", "quantity_unit", "per", "rate")
        )
        + "</tr>"
        for item in definition["items"]
    )
    return Response(
        content=(
            "<!doctype html><meta charset='utf-8'>"
            f"<title>{escape(label)}</title><h1>{escape(label)}</h1>"
            "<table><thead><tr><th>Code</th><th>Description</th><th>Unit</th>"
            f"<th>Basis</th><th>Rate</th></tr></thead><tbody>{rows}</tbody></table>"
        ),
        media_type="text/html; charset=utf-8",
    )
