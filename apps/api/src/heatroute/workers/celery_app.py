from celery import Celery

from heatroute.config import get_settings

settings = get_settings()

celery_app = Celery(
    "heatroute",
    broker=settings.celery_broker_url,
    backend=settings.celery_result_backend,
    include=["heatroute.workers.tasks"],
)
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_acks_late=True,
    task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1,
    broker_transport_options={"visibility_timeout": 900},
    result_backend_transport_options={"visibility_timeout": 900},
    visibility_timeout=900,
    task_routes={
        "heatroute.system_smoke": {"queue": "compute"},
        "heatroute.compute_route": {"queue": "compute"},
        "heatroute.calculate_hydraulics": {"queue": "compute"},
        "heatroute.inspect_dataset": {"queue": "ingest"},
        "heatroute.validate_dataset": {"queue": "ingest"},
        "heatroute.publish_dataset": {"queue": "ingest"},
        "heatroute.publish_pending_outbox": {"queue": "outbox"},
        "heatroute.recover_stale_route_jobs": {"queue": "outbox"},
    },
    beat_schedule={
        "publish-pending-outbox": {
            "task": "heatroute.publish_pending_outbox",
            "schedule": 2.0,
            "options": {"queue": "outbox"},
        },
        "recover-stale-route-jobs": {
            "task": "heatroute.recover_stale_route_jobs",
            "schedule": 10.0,
            "options": {"queue": "outbox"},
        },
    },
)
