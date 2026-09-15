from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID

from redis import Redis
from sqlalchemy import create_engine, select, text

from heatroute.config import get_settings
from heatroute.db import SessionLocal
from heatroute.domain.engineering import (
    HydraulicBoundary,
    HydraulicCalculationError,
    HydraulicDemand,
    HydraulicInputError,
    HydraulicNode,
    HydraulicPipe,
    HydraulicThresholds,
    calculate_hydraulics,
)
from heatroute.domain.network import CandidateDecision
from heatroute.models import (
    CalculationRun,
    CostCatalogVersion,
    DatasetImport,
    DatasetVersion,
    ImportReport,
    Job,
    OutboxEvent,
    Project,
    RouteAlternative,
    RuleProfileVersion,
    ScenarioRevision,
)
from heatroute.services.ingestion import execute_dataset_inspection, transition_dataset_version
from heatroute.services.inspection import InspectionRejectedError
from heatroute.services.model_context import (
    load_materialized_model,
    resolve_building_route_endpoints,
    screen_materialized_candidates,
)
from heatroute.services.route_pipeline import RouteEndpoint, execute_route_pipeline
from heatroute.services.runs import append_run_event, publish_outbox_event
from heatroute.services.validation import (
    execute_dataset_publication,
    execute_dataset_validation,
)
from heatroute.workers.celery_app import celery_app


def _system_smoke() -> dict[str, Any]:
    """Small safe task proving that a worker can reach durable dependencies."""
    settings = get_settings()
    engine = create_engine(settings.database_url, pool_pre_ping=True)
    try:
        with engine.connect() as connection:
            database = connection.execute(text("SELECT current_database()")).scalar_one()
            postgis = connection.execute(text("SELECT PostGIS_Version()")).scalar_one()
    finally:
        engine.dispose()

    redis_client = Redis.from_url(settings.redis_url)
    try:
        redis_ok = bool(redis_client.ping())
    finally:
        redis_client.close()

    return {
        "status": "ok",
        "database": database,
        "postgis": str(postgis),
        "redis": redis_ok,
    }


system_smoke = cast(
    Callable[[], dict[str, Any]],
    celery_app.task(name="heatroute.system_smoke")(_system_smoke),
)


def _calculate_hydraulics(job_id_text: str) -> dict[str, Any]:
    job_id = UUID(job_id_text)
    with SessionLocal() as session:
        job = session.scalar(select(Job).where(Job.id == job_id).with_for_update())
        if job is None or job.kind != "hydraulic_calculation":
            return {"status": "missing", "job_id": job_id_text}
        if job.state in {"succeeded", "failed", "cancelled"}:
            return {"status": "already_finished", "job_id": job_id_text}
        job.state = "running"
        job.phase = "hydraulic_solver"
        job.attempt += 1
        job.heartbeat_at = datetime.now(UTC)
        session.commit()
        try:
            payload = job.payload
            result = calculate_hydraulics(
                tuple(HydraulicNode(**item) for item in payload["nodes"]),
                tuple(HydraulicPipe(**item) for item in payload["pipes"]),
                tuple(HydraulicBoundary(**item) for item in payload["boundaries"]),
                tuple(HydraulicDemand(**item) for item in payload["demands"]),
                HydraulicThresholds(**payload["thresholds"]),
            )
        except (HydraulicInputError, HydraulicCalculationError) as error:
            session.rollback()
            failed_job = session.get(Job, job_id)
            if failed_job is not None:
                failed_job.state = "failed"
                failed_job.phase = "failed"
                failed_job.error_code = (
                    "HYDRAULIC_INPUT_INVALID"
                    if isinstance(error, HydraulicInputError)
                    else "HYDRAULIC_SOLVER_NOT_CONVERGED"
                )
                failed_job.result = {
                    "code": failed_job.error_code,
                    "message": str(error),
                }
                failed_job.heartbeat_at = datetime.now(UTC)
                session.commit()
            return {"status": "failed", "job_id": job_id_text}
        completed_job = session.get(Job, job_id)
        if completed_job is None:
            return {"status": "missing", "job_id": job_id_text}
        completed_job.state = "succeeded"
        completed_job.phase = "completed"
        completed_job.result = result
        completed_job.progress_current = len(payload["pipes"])
        completed_job.progress_total = len(payload["pipes"])
        completed_job.progress_unit = "pipes"
        completed_job.heartbeat_at = datetime.now(UTC)
        session.commit()
        return {"status": "ok", "job_id": job_id_text}


calculate_hydraulics_task = celery_app.task(name="heatroute.calculate_hydraulics")(
    _calculate_hydraulics
)


def _compute_route(run_id_text: str) -> dict[str, Any]:
    run_id = UUID(run_id_text)
    with SessionLocal() as session:
        locked = bool(
            session.execute(
                text("SELECT pg_try_advisory_lock(hashtext(:run_id))"),
                {"run_id": run_id_text},
            ).scalar_one()
        )
        if not locked:
            return {"status": "already_running", "run_id": run_id_text}
        try:
            run = session.scalar(
                select(CalculationRun).where(CalculationRun.id == run_id).with_for_update()
            )
            if run is None:
                return {"status": "missing", "run_id": run_id_text}
            if run.job_state in {"succeeded", "partial", "failed", "cancelled"}:
                return {"status": "already_finished", "run_id": run_id_text}
            job = session.get(Job, run.job_id)
            revision = session.get(ScenarioRevision, run.scenario_revision_id)
            if job is None or revision is None:
                raise RuntimeError("run references are incomplete")
            if run.job_state == "cancel_requested":
                now = datetime.now(UTC)
                run.job_state = "cancelled"
                run.outcome = "cancelled"
                run.phase = "cancelled"
                run.finished_at = now
                job.state = "cancelled"
                job.phase = "cancelled"
                job.heartbeat_at = now
                append_run_event(
                    session,
                    run_id=run.id,
                    event_type="run.cancelled",
                    phase="cancelled",
                    payload={"expanded_states": 0},
                )
                session.commit()
                return {"status": "cancelled", "run_id": run_id_text}
            now = datetime.now(UTC)
            run.job_state = "running"
            run.phase = "search"
            run.started_at = run.started_at or now
            job.state = "running"
            job.phase = "search"
            job.attempt += 1
            job.heartbeat_at = now
            job.lease_owner = "celery:compute_route"
            job.lease_expires_at = now + timedelta(seconds=get_settings().route_job_lease_seconds)
            job.progress_current = 0
            job.progress_unit = "expanded_states"
            append_run_event(
                session,
                run_id=run.id,
                event_type="run.started",
                phase="search",
                payload={"attempt": job.attempt},
            )
            session.commit()
            job_id = run.job_id

            def should_cancel() -> bool:
                state = session.scalar(
                    select(CalculationRun.job_state).where(CalculationRun.id == run_id)
                )
                return state == "cancel_requested"

            def report_progress(expanded_states: int) -> None:
                active_run = session.get(CalculationRun, run_id)
                active_job = session.get(Job, job_id)
                if active_run is None or active_job is None:
                    return
                active_run.statistics = {
                    **active_run.statistics,
                    "expanded_states": expanded_states,
                }
                active_job.heartbeat_at = datetime.now(UTC)
                active_job.lease_expires_at = active_job.heartbeat_at + timedelta(
                    seconds=get_settings().route_job_lease_seconds
                )
                active_job.progress_current = expanded_states
                append_run_event(
                    session,
                    run_id=run_id,
                    event_type="run.progress",
                    phase="search",
                    payload={"expanded_states": expanded_states},
                )
                session.commit()

            rule_profile_definition = None
            rule_profile_version_id = revision.input_snapshot.get("rule_profile_version_id")
            if rule_profile_version_id is not None:
                selected_rule_profile = session.get(
                    RuleProfileVersion, UUID(str(rule_profile_version_id))
                )
                if selected_rule_profile is None:
                    raise RuntimeError("selected rule profile version is missing")
                rule_profile_definition = selected_rule_profile.definition
            project = session.get(Project, run.project_id)
            if project is None or project.working_crs is None:
                raise RuntimeError("run project working CRS is missing")
            selected_dataset_ids = tuple(
                UUID(str(item["id"])) for item in run.versions_snapshot.get("datasets", [])
            )
            materialized_model = load_materialized_model(
                session,
                version_ids=selected_dataset_ids,
                working_crs=project.working_crs,
            )
            if materialized_model.findings or not materialized_model.topology.valid:
                raise RuntimeError("selected canonical model failed materialization")
            planning_date = None
            if revision.input_snapshot.get("planning_date") is not None:
                planning_date = datetime.fromisoformat(
                    str(revision.input_snapshot["planning_date"])
                ).date()
            candidate_decisions: tuple[CandidateDecision, ...] = ()
            selected_candidate_ids = tuple(
                str(item) for item in revision.input_snapshot.get("connection_candidate_ids", [])
            )
            if selected_candidate_ids and planning_date is not None:
                candidate_report = screen_materialized_candidates(
                    materialized_model,
                    planning_date=planning_date,
                    requested_load_kw=(
                        None
                        if revision.input_snapshot.get("requested_load_kw") is None
                        else float(revision.input_snapshot["requested_load_kw"])
                    ),
                    geometry_only=revision.input_snapshot.get("requested_load_kw") is None,
                    limit=int(revision.input_snapshot.get("candidate_limit", 5)),
                    mode=str(revision.input_snapshot.get("validation_mode", "strict")),
                    allowed_assumptions=frozenset(
                        str(item)
                        for item in revision.input_snapshot.get("explicit_assumptions", [])
                    ),
                    selected_source_ids=selected_candidate_ids,
                )
                candidate_decisions = candidate_report.selected
            endpoints: list[RouteEndpoint] = []
            if revision.input_snapshot.get("input_mode") == "building_to_network":
                if not candidate_decisions:
                    raise RuntimeError("building route has no eligible connection candidate")
                for decision in candidate_decisions:
                    endpoint_resolution = resolve_building_route_endpoints(
                        materialized_model,
                        snapshot=revision.input_snapshot,
                        candidate_source_id=decision.source_id,
                        working_crs=project.working_crs,
                    )
                    if endpoint_resolution.endpoints is None:
                        continue
                    resolved = endpoint_resolution.endpoints
                    endpoints.append(
                        RouteEndpoint(
                            decision.source_id,
                            (resolved.start.x, resolved.start.y),
                            (resolved.gate_approach.x, resolved.gate_approach.y),
                            resolved.entry_gate,
                            resolved.entry_connector,
                            (decision,),
                        )
                    )
            else:
                endpoints.append(RouteEndpoint(None, None, None))
            if not endpoints:
                raise RuntimeError("route endpoints failed resolution")

            cached_run = next(
                (
                    candidate
                    for candidate in session.scalars(
                        select(CalculationRun).where(
                            CalculationRun.project_id == run.project_id,
                            CalculationRun.id != run.id,
                            CalculationRun.job_state.in_(("succeeded", "partial")),
                        )
                    ).all()
                    if candidate.cache_info.get("key") == run.cache_info.get("key")
                ),
                None,
            )
            if cached_run is not None:
                cached_alternatives = session.scalars(
                    select(RouteAlternative)
                    .where(RouteAlternative.run_id == cached_run.id)
                    .order_by(RouteAlternative.rank)
                ).all()
                for source in cached_alternatives:
                    session.add(
                        RouteAlternative(
                            run_id=run.id,
                            rank=source.rank,
                            objective_tags=source.objective_tags,
                            centerline_wgs84=source.centerline_wgs84,
                            corridor_wgs84=source.corridor_wgs84,
                            geometry_hash=source.geometry_hash,
                            geometry_status=source.geometry_status,
                            metrics=source.metrics,
                            validation_report=source.validation_report,
                            candidate_id=source.candidate_id,
                            segments=source.segments,
                            quantity_items=source.quantity_items,
                            cost_breakdown=source.cost_breakdown,
                            assumptions=source.assumptions,
                            comparison=source.comparison,
                            postprocessing_log=source.postprocessing_log,
                        )
                    )
                run.job_state = cached_run.job_state
                run.outcome = cached_run.outcome
                run.phase = "completed"
                run.statistics = cached_run.statistics
                run.search_completion = cached_run.search_completion
                run.optimality_scope = cached_run.optimality_scope
                run.runtime_library_versions = cached_run.runtime_library_versions
                run.findings_summary = cached_run.findings_summary
                run.cache_info = {
                    "key": run.cache_info.get("key"),
                    "hit": True,
                    "source_run_id": str(cached_run.id),
                }
                run.finished_at = datetime.now(UTC)
                job.state = run.job_state
                job.phase = "completed"
                job.result = {
                    "run_id": run_id_text,
                    "outcome": run.outcome,
                    "cache_hit": True,
                }
                job.lease_owner = None
                job.lease_expires_at = None
                append_run_event(
                    session,
                    run_id=run.id,
                    event_type="run.completed",
                    phase="completed",
                    payload={"outcome": run.outcome, "cache_hit": True},
                )
                session.commit()
                return {
                    "status": "ok",
                    "run_id": run_id_text,
                    "outcome": run.outcome,
                    "cache_hit": True,
                }

            cost_catalog_definition = None
            cost_snapshot = run.versions_snapshot.get("cost_catalog")
            if cost_snapshot is not None:
                cost_version = session.get(CostCatalogVersion, UUID(str(cost_snapshot["id"])))
                if cost_version is None:
                    raise RuntimeError("selected cost catalog version is missing")
                cost_catalog_definition = cost_version.definition
            computation = execute_route_pipeline(
                revision.input_snapshot,
                run.algorithm_name,
                working_crs=project.working_crs,
                endpoints=tuple(endpoints),
                rule_profile_definition=rule_profile_definition,
                cost_catalog_definition=cost_catalog_definition,
                constraint_features=materialized_model.features,
                constraint_portals=materialized_model.portals,
                constraint_coverage=materialized_model.coverage,
                planning_date=planning_date,
                should_cancel=should_cancel,
                on_progress=report_progress,
            )
            cancelled = computation.outcome == "cancelled" or should_cancel()
            if not cancelled:
                for rank, alternative in enumerate(computation.alternatives, start=1):
                    existing = session.scalar(
                        select(RouteAlternative).where(
                            RouteAlternative.run_id == run.id,
                            RouteAlternative.geometry_hash == alternative["geometry_hash"],
                        )
                    )
                    if existing is None:
                        session.add(
                            RouteAlternative(
                                run_id=run.id,
                                rank=rank,
                                objective_tags=alternative["objective_tags"],
                                centerline_wgs84=alternative["centerline_wgs84"],
                                corridor_wgs84=alternative["corridor_wgs84"],
                                geometry_hash=alternative["geometry_hash"],
                                geometry_status=alternative["geometry_status"],
                                metrics=alternative["metrics"],
                                validation_report=alternative["validation_report"],
                                candidate_id=alternative["candidate_id"],
                                segments=alternative["segments"],
                                quantity_items=alternative["quantity_items"],
                                cost_breakdown=alternative["cost_breakdown"],
                                assumptions=alternative["assumptions"],
                                comparison=alternative["comparison"],
                                postprocessing_log=alternative["postprocessing_log"],
                            )
                        )
            run.outcome = "cancelled" if cancelled else computation.outcome
            run.statistics = computation.statistics
            run.search_completion = computation.search_completion
            run.optimality_scope = computation.optimality_scope
            run.runtime_library_versions = computation.runtime_library_versions
            run.findings_summary = computation.findings_summary
            run.error_code = None if cancelled else computation.error_code
            run.phase = "cancelled" if cancelled else "completed"
            if cancelled:
                run.job_state = "cancelled"
            else:
                run.job_state = (
                    "succeeded" if computation.outcome != "budget_exceeded" else "partial"
                )
            run.finished_at = datetime.now(UTC)
            job.state = run.job_state
            job.phase = "completed"
            job.result = {"run_id": run_id_text, "outcome": run.outcome}
            job.heartbeat_at = run.finished_at
            job.progress_current = int(computation.statistics.get("expanded_states", 0))
            job.lease_owner = None
            job.lease_expires_at = None
            append_run_event(
                session,
                run_id=run.id,
                event_type="run.cancelled" if cancelled else "run.completed",
                phase=run.phase,
                payload={
                    "outcome": run.outcome,
                    "expanded_states": computation.statistics.get("expanded_states", 0),
                },
            )
            session.commit()
            return {
                "status": "cancelled" if cancelled else "ok",
                "run_id": run_id_text,
                "outcome": run.outcome,
            }
        except Exception:
            session.rollback()
            run = session.get(CalculationRun, run_id)
            if run is not None:
                job = session.get(Job, run.job_id)
                retryable = job is not None and job.retryable and job.attempt < job.max_attempts
                if retryable and job is not None:
                    run.job_state = "queued"
                    run.outcome = "pending"
                    run.phase = "retry_queued"
                    run.error_code = "ROUTE_EXECUTION_RETRY"
                    job.state = "queued"
                    job.phase = "retry_queued"
                    job.error_code = "ROUTE_EXECUTION_RETRY"
                    job.lease_owner = None
                    job.lease_expires_at = None
                    session.add(
                        OutboxEvent(
                            aggregate_type="calculation_run",
                            aggregate_id=run.id,
                            event_type="run.requested",
                            payload={"run_id": str(run.id), "retry": job.attempt},
                            state="pending",
                            attempts=0,
                        )
                    )
                    append_run_event(
                        session,
                        run_id=run.id,
                        event_type="run.retry_queued",
                        phase="retry_queued",
                        payload={"attempt": job.attempt, "max_attempts": job.max_attempts},
                    )
                else:
                    run.job_state = "failed"
                    run.outcome = "execution_error"
                    run.phase = "failed"
                    run.error_code = "ROUTE_EXECUTION_ERROR"
                    run.finished_at = datetime.now(UTC)
                    if job is not None:
                        job.state = "failed"
                        job.phase = "failed"
                        job.error_code = "ROUTE_EXECUTION_ERROR"
                        job.lease_owner = None
                        job.lease_expires_at = None
                    append_run_event(
                        session,
                        run_id=run.id,
                        event_type="run.failed",
                        phase="failed",
                        payload={"error_code": "ROUTE_EXECUTION_ERROR"},
                    )
                session.commit()
            raise
        finally:
            session.execute(
                text("SELECT pg_advisory_unlock(hashtext(:run_id))"),
                {"run_id": run_id_text},
            )


compute_route = celery_app.task(name="heatroute.compute_route")(_compute_route)


def _recover_stale_route_jobs() -> dict[str, int]:
    now = datetime.now(UTC)
    recovered = 0
    failed = 0
    with SessionLocal() as session:
        jobs = session.scalars(
            select(Job)
            .where(
                Job.kind == "route_calculation",
                Job.state == "running",
                Job.lease_expires_at.is_not(None),
                Job.lease_expires_at < now,
            )
            .with_for_update(skip_locked=True)
        ).all()
        for job in jobs:
            run = session.scalar(select(CalculationRun).where(CalculationRun.job_id == job.id))
            if run is None:
                continue
            job.lease_owner = None
            job.lease_expires_at = None
            if job.retryable and job.attempt < job.max_attempts:
                job.state = "queued"
                job.phase = "lease_recovered"
                run.job_state = "queued"
                run.phase = "lease_recovered"
                session.add(
                    OutboxEvent(
                        aggregate_type="calculation_run",
                        aggregate_id=run.id,
                        event_type="run.requested",
                        payload={"run_id": str(run.id), "lease_recovery": job.attempt},
                        state="pending",
                        attempts=0,
                    )
                )
                append_run_event(
                    session,
                    run_id=run.id,
                    event_type="run.lease_recovered",
                    phase="lease_recovered",
                    payload={"attempt": job.attempt},
                )
                recovered += 1
            else:
                job.state = "failed"
                job.phase = "lease_expired"
                job.error_code = "JOB_LEASE_EXPIRED"
                run.job_state = "failed"
                run.outcome = "execution_error"
                run.phase = "lease_expired"
                run.error_code = "JOB_LEASE_EXPIRED"
                run.finished_at = now
                append_run_event(
                    session,
                    run_id=run.id,
                    event_type="run.failed",
                    phase="lease_expired",
                    payload={"error_code": "JOB_LEASE_EXPIRED"},
                )
                failed += 1
        session.commit()
    return {"recovered": recovered, "failed": failed}


recover_stale_route_jobs = celery_app.task(name="heatroute.recover_stale_route_jobs")(
    _recover_stale_route_jobs
)


def _inspect_dataset(import_id_text: str) -> dict[str, Any]:
    import_id = UUID(import_id_text)
    settings = get_settings()
    with SessionLocal() as session:
        locked = bool(
            session.execute(
                text("SELECT pg_try_advisory_lock(hashtext(:import_id))"),
                {"import_id": import_id_text},
            ).scalar_one()
        )
        if not locked:
            return {"status": "already_running", "import_id": import_id_text}
        try:
            result = execute_dataset_inspection(
                session,
                settings=settings,
                import_id=import_id,
            )
            if result is None:
                return {"status": "already_finished", "import_id": import_id_text}
            return {
                "status": "ok",
                "import_id": import_id_text,
                "dataset_version_status": result.version.status,
                "layer_count": len(result.report.layers),
            }
        except InspectionRejectedError as error:
            session.rollback()
            dataset_import = session.get(DatasetImport, import_id)
            if dataset_import is None:
                raise
            version = session.get(DatasetVersion, dataset_import.dataset_version_id)
            job = session.get(Job, dataset_import.job_id)
            if version is None or job is None:
                raise RuntimeError("dataset import provenance is incomplete") from error
            if version.status == "uploaded":
                transition_dataset_version(version, "rejected")
            dataset_import.state = "rejected"
            job.state = "succeeded"
            job.phase = "rejected"
            job.result = {"code": "INSPECTION_REJECTED", "message": str(error)}
            report = session.scalar(
                select(ImportReport).where(ImportReport.dataset_import_id == import_id)
            )
            if report is None:
                report = ImportReport(
                    workspace_id=dataset_import.workspace_id,
                    dataset_import_id=dataset_import.id,
                    stage="inspection",
                    counts={
                        "total": 0,
                        "read": 0,
                        "accepted": 0,
                        "quarantined": 0,
                        "rejected": 1,
                    },
                    layers=[],
                    errors=[
                        {
                            "code": "INSPECTION_REJECTED",
                            "message": str(error),
                        }
                    ],
                    warnings=[],
                    publish_blockers=[
                        {
                            "code": "INSPECTION_REJECTED",
                            "message": "The raw artifact cannot be imported.",
                        }
                    ],
                )
                session.add(report)
                session.flush()
            version.import_report_id = report.id
            session.commit()
            return {"status": "rejected", "import_id": import_id_text}
        except Exception:
            session.rollback()
            dataset_import = session.get(DatasetImport, import_id)
            if dataset_import is not None:
                version = session.get(DatasetVersion, dataset_import.dataset_version_id)
                job = session.get(Job, dataset_import.job_id)
                if version is not None and version.status == "uploaded":
                    transition_dataset_version(version, "failed")
                dataset_import.state = "failed"
                if job is not None:
                    job.state = "failed"
                    job.phase = "inspection_failed"
                    job.error_code = "INSPECTION_EXECUTION_ERROR"
                session.commit()
            raise
        finally:
            session.execute(
                text("SELECT pg_advisory_unlock(hashtext(:import_id))"),
                {"import_id": import_id_text},
            )


inspect_dataset = celery_app.task(name="heatroute.inspect_dataset")(_inspect_dataset)


def _validate_dataset(import_id_text: str) -> dict[str, Any]:
    import_id = UUID(import_id_text)
    settings = get_settings()
    with SessionLocal() as session:
        locked = bool(
            session.execute(
                text("SELECT pg_try_advisory_lock(hashtext(:import_id))"),
                {"import_id": import_id_text},
            ).scalar_one()
        )
        if not locked:
            return {"status": "already_running", "import_id": import_id_text}
        try:
            result = execute_dataset_validation(
                session,
                settings=settings,
                import_id=import_id,
            )
            if result is None:
                return {"status": "already_finished", "import_id": import_id_text}
            return {
                "status": "ok",
                "import_id": import_id_text,
                "dataset_version_status": result.dataset_version.status,
                "counts": result.report.counts,
            }
        except Exception:
            session.rollback()
            dataset_import = session.get(DatasetImport, import_id)
            if dataset_import is not None:
                version = session.get(DatasetVersion, dataset_import.dataset_version_id)
                job = session.get(Job, dataset_import.job_id)
                report = session.scalar(
                    select(ImportReport).where(ImportReport.dataset_import_id == import_id)
                )
                if version is not None and version.status in {"ready_to_validate", "validating"}:
                    transition_dataset_version(version, "failed")
                dataset_import.state = "failed"
                if job is not None:
                    job.state = "failed"
                    job.phase = "validation_failed"
                    job.error_code = "VALIDATION_EXECUTION_ERROR"
                if report is not None:
                    report.stage = "validation"
                    report.errors = report.errors + [
                        {
                            "code": "VALIDATION_EXECUTION_ERROR",
                            "message": "Validation did not complete.",
                        }
                    ]
                    report.publish_blockers = report.publish_blockers + [
                        {
                            "code": "VALIDATION_EXECUTION_ERROR",
                            "message": "Retry requires a new dataset version.",
                        }
                    ]
                session.commit()
            raise
        finally:
            session.execute(
                text("SELECT pg_advisory_unlock(hashtext(:import_id))"),
                {"import_id": import_id_text},
            )


validate_dataset = celery_app.task(name="heatroute.validate_dataset")(_validate_dataset)


def _publish_dataset(import_id_text: str) -> dict[str, Any]:
    import_id = UUID(import_id_text)
    with SessionLocal() as session:
        locked = bool(
            session.execute(
                text("SELECT pg_try_advisory_lock(hashtext(:import_id))"),
                {"import_id": import_id_text},
            ).scalar_one()
        )
        if not locked:
            return {"status": "already_running", "import_id": import_id_text}
        try:
            result = execute_dataset_publication(session, import_id=import_id)
            if result is None:
                return {"status": "already_finished", "import_id": import_id_text}
            return {
                "status": "ok",
                "import_id": import_id_text,
                "dataset_version_status": result.dataset_version.status,
                "published_features": result.published_features,
            }
        except Exception:
            session.rollback()
            dataset_import = session.get(DatasetImport, import_id)
            if dataset_import is not None:
                version = session.get(DatasetVersion, dataset_import.dataset_version_id)
                job = session.get(Job, dataset_import.job_id)
                if version is not None and version.status in {"ready_to_publish", "publishing"}:
                    transition_dataset_version(version, "failed")
                dataset_import.state = "failed"
                if job is not None:
                    job.state = "failed"
                    job.phase = "publication_failed"
                    job.error_code = "PUBLICATION_EXECUTION_ERROR"
                session.commit()
            raise
        finally:
            session.execute(
                text("SELECT pg_advisory_unlock(hashtext(:import_id))"),
                {"import_id": import_id_text},
            )


publish_dataset = celery_app.task(name="heatroute.publish_dataset")(_publish_dataset)


def _publish_pending_outbox(limit: int = 50) -> dict[str, int]:
    with SessionLocal() as session:
        event_ids = session.scalars(
            select(OutboxEvent.id)
            .where(OutboxEvent.state == "pending")
            .order_by(OutboxEvent.created_at, OutboxEvent.id)
            .limit(limit)
        ).all()
    published = 0
    for event_id in event_ids:
        with SessionLocal() as session:
            if publish_outbox_event(session, event_id):
                published += 1
    return {"selected": len(event_ids), "published": published}


publish_pending_outbox = celery_app.task(name="heatroute.publish_pending_outbox")(
    _publish_pending_outbox
)
