import argparse
import json
import time
from datetime import date, datetime
from pathlib import Path
from typing import Any
from uuid import UUID, uuid4

from celery.result import AsyncResult

from heatroute.api.app import create_app
from heatroute.api.schemas import DemoRunRequest
from heatroute.db import SessionLocal
from heatroute.models import CalculationRun, Job, RouteAlternative, ScenarioRevision
from heatroute.services.demo_runs import create_demo_run
from heatroute.services.runs import create_revision_run
from heatroute.workers.celery_app import celery_app


def export_openapi(output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(create_app().openapi(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def worker_smoke(timeout: float) -> dict[str, object]:
    result: AsyncResult = celery_app.send_task("heatroute.system_smoke")
    value = result.get(timeout=timeout)
    if not isinstance(value, dict):
        raise RuntimeError("worker returned an invalid smoke result")
    return value


def run_passport(run_id: UUID) -> dict[str, Any]:
    with SessionLocal() as session:
        run = session.get(CalculationRun, run_id)
        if run is None:
            raise LookupError(f"calculation run {run_id} was not found")
        job = session.get(Job, run.job_id)
        alternatives = (
            session.query(RouteAlternative)
            .filter_by(run_id=run.id)
            .order_by(RouteAlternative.rank)
            .all()
        )
        return {
            "schema_version": "1.0",
            "generated_by": "heatroute run-passport",
            "run": {
                "id": run.id,
                "job_id": run.job_id,
                "job_state": run.job_state,
                "outcome": run.outcome,
                "phase": run.phase,
                "algorithm": {"name": run.algorithm_name, "version": run.algorithm_version},
                "versions_snapshot": run.versions_snapshot,
                "parameters": run.parameters,
                "statistics": run.statistics,
                "runtime_library_versions": run.runtime_library_versions,
                "assumptions": run.assumptions,
                "findings_summary": run.findings_summary,
                "search_completion": run.search_completion,
                "optimality_scope": run.optimality_scope,
                "cache_info": run.cache_info,
                "error_code": run.error_code,
                "started_at": run.started_at,
                "finished_at": run.finished_at,
            },
            "job": None
            if job is None
            else {
                "attempt": job.attempt,
                "max_attempts": job.max_attempts,
                "progress_current": job.progress_current,
                "progress_total": job.progress_total,
                "progress_unit": job.progress_unit,
                "timings": job.timings,
            },
            "alternatives": [
                {
                    "id": item.id,
                    "rank": item.rank,
                    "candidate_id": item.candidate_id,
                    "objective_tags": item.objective_tags,
                    "geometry_hash": item.geometry_hash,
                    "geometry_status": item.geometry_status,
                    "metrics": item.metrics,
                    "validation_report": item.validation_report,
                    "segments": item.segments,
                    "quantity_items": item.quantity_items,
                    "cost_breakdown": item.cost_breakdown,
                    "comparison": item.comparison,
                }
                for item in alternatives
            ],
        }


def start_run(
    revision_id: UUID,
    *,
    algorithm: str,
    idempotency_key: str,
    wait_seconds: float,
) -> dict[str, Any]:
    with SessionLocal() as session:
        revision = session.get(ScenarioRevision, revision_id)
        if revision is None:
            raise LookupError(f"scenario revision {revision_id} was not found")
        run = create_revision_run(
            session,
            revision=revision,
            algorithm=algorithm,
            idempotency_key=idempotency_key,
        )
        run_id = run.id
    deadline = time.monotonic() + wait_seconds
    state = "queued"
    outcome = "pending"
    while wait_seconds > 0 and time.monotonic() < deadline:
        with SessionLocal() as session:
            current = session.get(CalculationRun, run_id)
            if current is None:
                raise RuntimeError("accepted calculation run disappeared")
            state, outcome = current.job_state, current.outcome
        if state in {"succeeded", "partial", "failed", "cancelled"}:
            break
        time.sleep(0.1)
    return {
        "run_id": run_id,
        "job_state": state,
        "outcome": outcome,
        "status_url": f"/api/v1/runs/{run_id}",
    }


def seed_demo(wait_seconds: float) -> dict[str, Any]:
    request = DemoRunRequest(
        entry_point_wgs84=(37.61, 55.752),
        goal_point_wgs84=(37.64, 55.752),
        forbidden_rectangles_wgs84=[(37.623, 55.748, 37.628, 55.756)],
        corridor_width_m=8,
        search_settings={
            "resolution_m": 20,
            "search_buffer_m": 500,
            "budget": {"max_expanded_states": 100_000},
            "max_alternatives": 3,
        },
        explicit_assumptions=["synthetic seed; not engineering data"],
    )
    snapshot = request.model_dump(mode="json", exclude={"algorithm"})
    with SessionLocal() as session:
        run = create_demo_run(
            session,
            snapshot=snapshot,
            algorithm=request.algorithm,
            idempotency_key="heatroute-seed-demo-v1",
        )
        run_id = run.id
        project_id = run.project_id
        revision_id = run.scenario_revision_id
    result = start_run_status(run_id, wait_seconds)
    return {
        **result,
        "project_id": project_id,
        "scenario_revision_id": revision_id,
        "workspace_url": f"/projects/{project_id}/workspace?run={run_id}",
    }


def start_run_status(run_id: UUID, wait_seconds: float) -> dict[str, Any]:
    deadline = time.monotonic() + wait_seconds
    state = "queued"
    outcome = "pending"
    while wait_seconds > 0 and time.monotonic() < deadline:
        with SessionLocal() as session:
            current = session.get(CalculationRun, run_id)
            if current is None:
                raise RuntimeError("accepted calculation run disappeared")
            state, outcome = current.job_state, current.outcome
        if state in {"succeeded", "partial", "failed", "cancelled"}:
            break
        time.sleep(0.1)
    return {
        "run_id": run_id,
        "job_state": state,
        "outcome": outcome,
        "status_url": f"/api/v1/runs/{run_id}",
    }


def _json_default(value: object) -> str:
    if isinstance(value, UUID | date | datetime):
        return value.isoformat() if isinstance(value, date | datetime) else str(value)
    raise TypeError(f"unsupported JSON value: {type(value).__name__}")


def main() -> int:
    parser = argparse.ArgumentParser(prog="heatroute")
    subparsers = parser.add_subparsers(dest="command", required=True)

    openapi_parser = subparsers.add_parser("export-openapi")
    openapi_parser.add_argument(
        "--output",
        type=Path,
        default=Path("packages/api-client/openapi.json"),
    )

    smoke_parser = subparsers.add_parser("worker-smoke")
    smoke_parser.add_argument("--timeout", type=float, default=30.0)

    passport_parser = subparsers.add_parser("run-passport")
    passport_parser.add_argument("run_id", type=UUID)
    passport_parser.add_argument("--output", type=Path)

    run_parser = subparsers.add_parser("start-run")
    run_parser.add_argument("revision_id", type=UUID)
    run_parser.add_argument("--algorithm", choices=("astar", "dijkstra"), default="astar")
    run_parser.add_argument("--idempotency-key", default=None)
    run_parser.add_argument("--wait", type=float, default=0, dest="wait_seconds")

    seed_parser = subparsers.add_parser("seed-demo")
    seed_parser.add_argument("--wait", type=float, default=30, dest="wait_seconds")

    args = parser.parse_args()
    if args.command == "export-openapi":
        export_openapi(args.output)
        print(f"OpenAPI written to {args.output}")
        return 0
    if args.command == "worker-smoke":
        print(json.dumps(worker_smoke(args.timeout), ensure_ascii=False, indent=2))
        return 0
    if args.command == "run-passport":
        payload = json.dumps(
            run_passport(args.run_id),
            ensure_ascii=False,
            indent=2,
            default=_json_default,
        )
        if args.output is None:
            print(payload)
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(payload + "\n", encoding="utf-8")
            print(f"Run passport written to {args.output}")
        return 0
    if args.command == "start-run":
        print(
            json.dumps(
                start_run(
                    args.revision_id,
                    algorithm=args.algorithm,
                    idempotency_key=args.idempotency_key or str(uuid4()),
                    wait_seconds=max(0, args.wait_seconds),
                ),
                ensure_ascii=False,
                indent=2,
                default=_json_default,
            )
        )
        return 0
    if args.command == "seed-demo":
        print(
            json.dumps(
                seed_demo(max(0, args.wait_seconds)),
                ensure_ascii=False,
                indent=2,
                default=_json_default,
            )
        )
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
