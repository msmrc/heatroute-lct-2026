import csv
import io
import json
import time
from collections.abc import Iterator
from html import escape
from typing import Annotated, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Response, status
from fastapi.responses import PlainTextResponse, StreamingResponse
from sqlalchemy import or_, select
from sqlalchemy.orm import Session

from heatroute.api.readiness import run_readiness_checks
from heatroute.api.schemas import (
    AuditEventResponse,
    CapabilitiesResponse,
    DemoRunRequest,
    JobResponse,
    ReadinessResponse,
    RouteAlternativeResponse,
    RunAcceptedResponse,
    RunCancelResponse,
    RunEventResponse,
    RunResponse,
    RunSummaryResponse,
)
from heatroute.config import get_settings
from heatroute.db import SessionLocal, get_db_session
from heatroute.domain.construction import CONSTRUCTION_METHODS
from heatroute.models import (
    AuditEvent,
    CalculationRun,
    DatasetImport,
    Job,
    RouteAlternative,
    RunEvent,
)
from heatroute.observability import http_metrics
from heatroute.services.auth import Principal, require_admin, require_editor, require_reader
from heatroute.services.demo_runs import create_demo_run
from heatroute.services.exports import safe_csv_cell
from heatroute.services.runs import IdempotencyConflictError, append_run_event

router = APIRouter()
DbSession = Annotated[Session, Depends(get_db_session)]
Reader = Annotated[Principal, Depends(require_reader)]
Editor = Annotated[Principal, Depends(require_editor)]
Admin = Annotated[Principal, Depends(require_admin)]
IdempotencyKey = Annotated[
    str, Header(alias="Idempotency-Key", min_length=1, max_length=128)
]


@router.get("/health/live", tags=["health"])
def live() -> dict[str, str]:
    return {"status": "alive"}


@router.get("/health/ready", response_model=ReadinessResponse, tags=["health"])
def ready(response: Response) -> ReadinessResponse:
    result = run_readiness_checks(get_settings())
    if not result.ready:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
    return ReadinessResponse(
        status="ready" if result.ready else "not_ready",
        checks=result.checks,
    )


@router.get("/capabilities", response_model=CapabilitiesResponse, tags=["system"])
def capabilities() -> CapabilitiesResponse:
    settings = get_settings()
    return CapabilitiesResponse(
        schema_version="1.1",
        imports=["geojson", "gpkg", "csv", "shapefile_zip", "geoparquet"],
        solvers=["astar", "dijkstra"],
        exports=["geojson", "json", "csv", "html"],
        demo_seed=settings.demo_mode,
        engineering={"hydraulics": "available", "vertical_geometry": "available"},
        construction_methods=list(CONSTRUCTION_METHODS),
    )


@router.get("/metrics", response_class=PlainTextResponse, tags=["system"])
def metrics(_principal: Admin) -> str:
    return http_metrics.render_prometheus()


@router.get("/audit-events", response_model=list[AuditEventResponse], tags=["audit"])
def list_audit_events(
    session: DbSession,
    principal: Admin,
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
) -> list[AuditEventResponse]:
    rows = session.scalars(
        select(AuditEvent)
        .where(AuditEvent.workspace_id == principal.workspace_id)
        .order_by(AuditEvent.created_at.desc(), AuditEvent.id.desc())
        .offset(offset)
        .limit(limit)
    ).all()
    return [
        AuditEventResponse(
            id=row.id,
            workspace_id=row.workspace_id,
            actor_user_id=row.actor_user_id,
            request_id=row.request_id,
            method=row.method,
            path=row.path,
            status_code=row.status_code,
            action=row.action,
            metadata=row.metadata_json,
            created_at=row.created_at,
        )
        for row in rows
    ]


@router.post(
    "/demo/runs",
    response_model=RunAcceptedResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["demo", "runs"],
)
def start_demo_run(
    request: DemoRunRequest,
    idempotency_key: IdempotencyKey,
    session: DbSession,
    _principal: Editor,
) -> RunAcceptedResponse:
    if not get_settings().demo_mode:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="not found")
    snapshot = request.model_dump(mode="json", exclude={"algorithm"})
    try:
        run = create_demo_run(
            session,
            snapshot=snapshot,
            algorithm=request.algorithm,
            idempotency_key=idempotency_key,
        )
    except IdempotencyConflictError as error:
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=str(error)) from error
    return RunAcceptedResponse(
        run_id=run.id,
        job_id=run.job_id,
        job_state=run.job_state,
        status_url=f"/api/v1/runs/{run.id}",
    )


@router.get("/runs/{run_id}", response_model=RunResponse, tags=["runs"])
def get_run(run_id: UUID, session: DbSession, principal: Reader) -> RunResponse:
    run = session.scalar(
        select(CalculationRun).where(
            CalculationRun.id == run_id,
            CalculationRun.workspace_id == principal.workspace_id,
        )
    )
    if run is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="run not found")
    alternatives = session.scalars(
        select(RouteAlternative)
        .where(RouteAlternative.run_id == run.id)
        .order_by(RouteAlternative.rank, RouteAlternative.id)
    ).all()
    job = session.get(Job, run.job_id)
    return RunResponse(
        id=run.id,
        job_id=run.job_id,
        job_state=run.job_state,
        outcome=run.outcome,
        phase=run.phase,
        algorithm_name=run.algorithm_name,
        algorithm_version=run.algorithm_version,
        versions_snapshot=run.versions_snapshot,
        parameters=run.parameters,
        statistics=run.statistics,
        runtime_library_versions=run.runtime_library_versions,
        assumptions=run.assumptions,
        findings_summary=run.findings_summary,
        search_completion=run.search_completion,
        optimality_scope=run.optimality_scope,
        cache_info=run.cache_info,
        started_at=run.started_at,
        finished_at=run.finished_at,
        job_progress={
            "current": None if job is None else job.progress_current,
            "total": None if job is None else job.progress_total,
            "unit": None if job is None else job.progress_unit,
            "attempt": None if job is None else job.attempt,
        },
        error_code=run.error_code,
        alternatives=[
            RouteAlternativeResponse(
                id=item.id,
                rank=item.rank,
                objective_tags=item.objective_tags,
                centerline_wgs84=item.centerline_wgs84,
                corridor_wgs84=item.corridor_wgs84,
                geometry_hash=item.geometry_hash,
                geometry_status=item.geometry_status,
                metrics=item.metrics,
                validation_report=item.validation_report,
                candidate_id=item.candidate_id,
                segments=item.segments,
                quantity_items=item.quantity_items,
                cost_breakdown=item.cost_breakdown,
                assumptions=item.assumptions,
                comparison=item.comparison,
                postprocessing_log=item.postprocessing_log,
            )
            for item in alternatives
        ],
    )


@router.get(
    "/projects/{project_id}/runs",
    response_model=list[RunSummaryResponse],
    tags=["runs"],
)
def list_project_runs(
    project_id: UUID,
    session: DbSession,
    principal: Reader,
    limit: Annotated[int, Query(ge=1, le=200)] = 100,
) -> list[RunSummaryResponse]:
    runs = session.scalars(
        select(CalculationRun)
        .where(
            CalculationRun.project_id == project_id,
            CalculationRun.workspace_id == principal.workspace_id,
        )
        .order_by(CalculationRun.created_at.desc(), CalculationRun.id)
        .limit(limit)
    ).all()
    result: list[RunSummaryResponse] = []
    for run in runs:
        alternatives = session.scalars(
            select(RouteAlternative)
            .where(RouteAlternative.run_id == run.id)
            .order_by(RouteAlternative.rank, RouteAlternative.id)
        ).all()
        first = alternatives[0] if alternatives else None
        cost = first.cost_breakdown if first is not None else {}
        result.append(
            RunSummaryResponse(
                id=run.id,
                project_id=run.project_id,
                scenario_revision_id=run.scenario_revision_id,
                job_id=run.job_id,
                job_state=run.job_state,
                outcome=run.outcome,
                phase=run.phase,
                algorithm_name=run.algorithm_name,
                algorithm_version=run.algorithm_version,
                input_hash=run.input_hash,
                alternatives_count=len(alternatives),
                route_length_m=(
                    None if first is None else _number(first.metrics.get("route_length_m"))
                ),
                modeled_cost=_number(cost.get("total")),
                cost_completeness=(
                    None if first is None else str(cost.get("status", "unknown"))
                ),
                started_at=run.started_at,
                finished_at=run.finished_at,
                created_at=run.created_at,
            )
        )
    return result


def _number(value: object) -> float | None:
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    return float(value)


@router.get("/jobs", response_model=list[JobResponse], tags=["jobs"])
def list_jobs(
    session: DbSession,
    principal: Reader,
    project_id: UUID | None = None,
    limit: Annotated[int, Query(ge=1, le=500)] = 100,
) -> list[JobResponse]:
    jobs_query = select(Job).where(Job.workspace_id == principal.workspace_id)
    if project_id is not None:
        jobs_query = jobs_query.where(
            or_(
                Job.id.in_(
                    select(CalculationRun.job_id).where(
                        CalculationRun.project_id == project_id,
                        CalculationRun.workspace_id == principal.workspace_id,
                    )
                ),
                Job.id.in_(
                    select(DatasetImport.job_id).where(
                        DatasetImport.project_id == project_id,
                        DatasetImport.workspace_id == principal.workspace_id,
                    )
                ),
            )
        )
    jobs = session.scalars(
        jobs_query.order_by(Job.created_at.desc(), Job.id).limit(limit)
    ).all()
    responses: list[JobResponse] = []
    for job in jobs:
        run = session.scalar(select(CalculationRun).where(CalculationRun.job_id == job.id))
        dataset_import = session.scalar(
            select(DatasetImport).where(DatasetImport.job_id == job.id)
        )
        linked_project_id = (
            run.project_id
            if run is not None
            else dataset_import.project_id
            if dataset_import
            else None
        )
        responses.append(
            JobResponse(
                id=job.id,
                kind=job.kind,
                state=job.state,
                phase=job.phase,
                attempt=job.attempt,
                progress_current=job.progress_current,
                progress_total=job.progress_total,
                progress_unit=job.progress_unit,
                retryable=job.retryable,
                error_code=job.error_code,
                project_id=linked_project_id,
                resource_type="run" if run is not None else "import" if dataset_import else None,
                resource_id=(
                    run.id
                    if run is not None
                    else dataset_import.id
                    if dataset_import
                    else None
                ),
                created_at=job.created_at,
                updated_at=job.updated_at,
            )
        )
    return responses


@router.get("/runs/{run_id}/export", tags=["runs", "exports"])
def export_run(
    run_id: UUID,
    session: DbSession,
    principal: Reader,
    format: Literal["geojson", "json", "csv", "html"] = Query(default="geojson"),
) -> Response:
    run = session.scalar(
        select(CalculationRun).where(
            CalculationRun.id == run_id,
            CalculationRun.workspace_id == principal.workspace_id,
        )
    )
    if run is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="run not found")
    alternatives = session.scalars(
        select(RouteAlternative)
        .where(RouteAlternative.run_id == run.id)
        .order_by(RouteAlternative.rank, RouteAlternative.id)
    ).all()
    filename = f"heatroute-run-{run.id}.{format if format != 'geojson' else 'geojson'}"
    headers = {"Content-Disposition": f'attachment; filename="{filename}"'}
    if format == "geojson":
        features: list[dict[str, object]] = []
        for alternative in alternatives:
            properties = {
                "run_id": str(run.id),
                "alternative_id": str(alternative.id),
                "rank": alternative.rank,
                "objective_tags": alternative.objective_tags,
                "geometry_status": alternative.geometry_status,
                "metrics": alternative.metrics,
                "cost_breakdown": alternative.cost_breakdown,
            }
            features.extend(
                [
                    {
                        "type": "Feature",
                        "properties": {**properties, "geometry_role": "centerline"},
                        "geometry": alternative.centerline_wgs84,
                    },
                    {
                        "type": "Feature",
                        "properties": {**properties, "geometry_role": "corridor"},
                        "geometry": alternative.corridor_wgs84,
                    },
                ]
            )
        return Response(
            content=json.dumps({"type": "FeatureCollection", "features": features}),
            media_type="application/geo+json",
            headers=headers,
        )
    passport = {
        "run_id": str(run.id),
        "project_id": str(run.project_id),
        "scenario_revision_id": str(run.scenario_revision_id),
        "input_hash": run.input_hash,
        "state": run.job_state,
        "outcome": run.outcome,
        "algorithm": {"name": run.algorithm_name, "version": run.algorithm_version},
        "versions_snapshot": run.versions_snapshot,
        "parameters": run.parameters,
        "statistics": run.statistics,
        "assumptions": run.assumptions,
        "findings_summary": run.findings_summary,
        "runtime_library_versions": run.runtime_library_versions,
        "alternatives": [
            {
                "id": str(item.id),
                "rank": item.rank,
                "objective_tags": item.objective_tags,
                "metrics": item.metrics,
                "validation_report": item.validation_report,
                "quantity_items": item.quantity_items,
                "cost_breakdown": item.cost_breakdown,
                "assumptions": item.assumptions,
            }
            for item in alternatives
        ],
    }
    if format == "json":
        return Response(
            content=json.dumps(passport, ensure_ascii=False, indent=2, default=str),
            media_type="application/json",
            headers=headers,
        )
    if format == "csv":
        stream = io.StringIO(newline="")
        writer = csv.writer(stream)
        writer.writerow(
            ["alternative", "length_m", "modeled_cost", "cost_completeness", "geometry_status"]
        )
        for item in alternatives:
            writer.writerow(
                [
                    item.rank,
                    item.metrics.get("route_length_m", ""),
                    item.cost_breakdown.get("total", ""),
                    item.cost_breakdown.get("completeness", "unknown"),
                    safe_csv_cell(item.geometry_status),
                ]
            )
        return Response(content=stream.getvalue(), media_type="text/csv", headers=headers)
    rows = "".join(
        "<tr>"
        f"<td>{item.rank}</td>"
        f"<td>{escape(str(item.metrics.get('route_length_m', '—')))}</td>"
        f"<td>{escape(str(item.cost_breakdown.get('total', '—')))}</td>"
        f"<td>{escape(item.geometry_status)}</td>"
        "</tr>"
        for item in alternatives
    )
    return Response(
        content=(
            "<!doctype html><html lang='ru'><meta charset='utf-8'>"
            f"<title>HeatRoute · {run.id}</title>"
            "<style>body{font:14px system-ui;margin:40px;color:#242227}"
            "table{border-collapse:collapse;width:100%}"
            "th,td{border:1px solid #ddd;padding:10px;text-align:left}"
            "@media print{body{margin:16mm}}</style>"
            f"<h1>Паспорт расчёта</h1><p>Run <code>{run.id}</code></p>"
            f"<p>Статус: {escape(run.outcome)} · Алгоритм: "
            f"{escape(run.algorithm_name)} {escape(run.algorithm_version)}</p>"
            "<table><thead><tr><th>Вариант</th><th>Длина, м</th>"
            "<th>Стоимость</th><th>Геометрия</th></tr></thead>"
            f"<tbody>{rows}</tbody></table>"
            "<h2>Допущения</h2><pre>"
            f"{escape(json.dumps(run.assumptions, ensure_ascii=False, indent=2))}</pre>"
            "</html>"
        ),
        media_type="text/html; charset=utf-8",
        headers=headers,
    )


@router.get("/runs/{run_id}/events", response_model=list[RunEventResponse], tags=["runs"])
def get_run_events(
    run_id: UUID,
    session: DbSession,
    principal: Reader,
    after_sequence: int = Query(default=0, ge=0),
) -> list[RunEventResponse]:
    if session.scalar(
        select(CalculationRun.id).where(
            CalculationRun.id == run_id,
            CalculationRun.workspace_id == principal.workspace_id,
        )
    ) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="run not found")
    events = session.scalars(
        select(RunEvent)
        .where(RunEvent.run_id == run_id, RunEvent.sequence > after_sequence)
        .order_by(RunEvent.sequence)
    ).all()
    return [
        RunEventResponse(
            id=event.id,
            run_id=event.run_id,
            sequence=event.sequence,
            event_type=event.event_type,
            phase=event.phase,
            payload=event.payload,
            created_at=event.created_at,
        )
        for event in events
    ]


@router.get("/runs/{run_id}/events/stream", tags=["runs"])
def stream_run_events(
    run_id: UUID,
    session: DbSession,
    principal: Reader,
    after_sequence: int = Query(default=0, ge=0),
    last_event_id: Annotated[str | None, Header(alias="Last-Event-ID")] = None,
) -> StreamingResponse:
    if session.scalar(
        select(CalculationRun.id).where(
            CalculationRun.id == run_id,
            CalculationRun.workspace_id == principal.workspace_id,
        )
    ) is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="run not found")
    cursor = after_sequence
    if last_event_id is not None:
        try:
            cursor = max(cursor, int(last_event_id))
        except ValueError as error:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Last-Event-ID must be an integer event sequence",
            ) from error

    def event_stream() -> Iterator[str]:
        current = cursor
        idle_cycles = 0
        while True:
            with SessionLocal() as stream_session:
                events = stream_session.scalars(
                    select(RunEvent)
                    .where(RunEvent.run_id == run_id, RunEvent.sequence > current)
                    .order_by(RunEvent.sequence)
                ).all()
                state = stream_session.scalar(
                    select(CalculationRun.job_state).where(CalculationRun.id == run_id)
                )
            for event in events:
                current = event.sequence
                payload = {
                    "id": str(event.id),
                    "run_id": str(event.run_id),
                    "sequence": event.sequence,
                    "event_type": event.event_type,
                    "phase": event.phase,
                    "payload": event.payload,
                    "created_at": event.created_at.isoformat(),
                }
                yield (
                    f"id: {event.sequence}\n"
                    f"event: {event.event_type}\n"
                    f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"
                )
            if state in {"succeeded", "partial", "failed", "cancelled"}:
                break
            idle_cycles += 1
            if not events and idle_cycles % 40 == 0:
                yield ": keep-alive\n\n"
            time.sleep(0.25)

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.post(
    "/runs/{run_id}/cancel",
    response_model=RunCancelResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["runs"],
)
def cancel_run(run_id: UUID, session: DbSession, principal: Editor) -> RunCancelResponse:
    run = session.scalar(
        select(CalculationRun)
        .where(
            CalculationRun.id == run_id,
            CalculationRun.workspace_id == principal.workspace_id,
        )
        .with_for_update()
    )
    if run is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="run not found")
    if (
        run.job_state not in {"succeeded", "partial", "failed", "cancelled"}
        and run.job_state != "cancel_requested"
    ):
        run.job_state = "cancel_requested"
        run.phase = "cancel_requested"
        job = session.get(Job, run.job_id)
        if job is not None:
            job.state = "cancel_requested"
            job.phase = "cancel_requested"
        append_run_event(
            session,
            run_id=run.id,
            event_type="run.cancel_requested",
            phase="cancel_requested",
        )
        session.commit()
        session.refresh(run)
    return RunCancelResponse(run_id=run.id, job_state=run.job_state, outcome=run.outcome)
