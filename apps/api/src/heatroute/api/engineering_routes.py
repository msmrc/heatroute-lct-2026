from __future__ import annotations

from dataclasses import asdict
from typing import Annotated, Any
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import Field, model_validator
from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.api.schemas import StrictModel
from heatroute.db import get_db_session
from heatroute.domain.construction import CONSTRUCTION_METHODS
from heatroute.domain.vertical import (
    VerticalCrossing,
    VerticalProfilePoint,
    validate_vertical_profile,
)
from heatroute.models import Job, OutboxEvent
from heatroute.services.auth import Principal, require_editor, require_reader
from heatroute.services.runs import publish_outbox_event

router = APIRouter()
Reader = Annotated[Principal, Depends(require_reader)]
Editor = Annotated[Principal, Depends(require_editor)]
DbSession = Annotated[Session, Depends(get_db_session)]


class HydraulicNodeInput(StrictModel):
    node_id: str = Field(min_length=1, max_length=200)
    nominal_pressure_bar: float = Field(gt=0, le=100)
    temperature_k: float = Field(ge=273.15, le=473.15)
    elevation_m: float = Field(ge=-500, le=10_000)


class HydraulicPipeInput(StrictModel):
    pipe_id: str = Field(min_length=1, max_length=200)
    from_node_id: str = Field(min_length=1, max_length=200)
    to_node_id: str = Field(min_length=1, max_length=200)
    length_m: float = Field(gt=0, le=1_000_000)
    inner_diameter_mm: float = Field(gt=0, le=5_000)
    roughness_mm: float = Field(ge=0, le=20)
    loss_coefficient: float = Field(default=0, ge=0, le=100_000)


class HydraulicBoundaryInput(StrictModel):
    node_id: str = Field(min_length=1, max_length=200)
    pressure_bar: float = Field(gt=0, le=100)
    temperature_k: float = Field(ge=273.15, le=473.15)


class HydraulicDemandInput(StrictModel):
    node_id: str = Field(min_length=1, max_length=200)
    mass_flow_kg_per_s: float = Field(gt=0, le=100_000)


class HydraulicThresholdInput(StrictModel):
    minimum_pressure_bar: float = Field(ge=0, le=100)
    maximum_velocity_m_per_s: float = Field(gt=0, le=100)
    mass_balance_tolerance_kg_per_s: float = Field(ge=0, le=10)


class HydraulicCalculationRequest(StrictModel):
    nodes: list[HydraulicNodeInput] = Field(min_length=2, max_length=500)
    pipes: list[HydraulicPipeInput] = Field(min_length=1, max_length=1_000)
    boundaries: list[HydraulicBoundaryInput] = Field(min_length=1, max_length=50)
    demands: list[HydraulicDemandInput] = Field(default_factory=list, max_length=500)
    thresholds: HydraulicThresholdInput


class EngineeringJobResponse(StrictModel):
    job_id: UUID
    state: str
    phase: str | None
    result: dict[str, Any] | None
    error_code: str | None
    status_url: str


class VerticalProfilePointInput(StrictModel):
    chainage_m: float = Field(ge=0, le=10_000_000)
    elevation_m: float = Field(ge=-500, le=10_000)


class VerticalCrossingInput(StrictModel):
    crossing_id: str = Field(min_length=1, max_length=200)
    chainage_m: float = Field(ge=0, le=10_000_000)
    vertical_datum: str | None = Field(default=None, max_length=200)
    elevation_m: float | None = Field(default=None, ge=-500, le=10_000)
    surface_elevation_m: float | None = Field(default=None, ge=-500, le=10_000)
    depth_m: float | None = Field(default=None, ge=0, le=1_000)
    outside_diameter_m: float | None = Field(default=None, gt=0, le=20)


class VerticalValidationRequest(StrictModel):
    profile: list[VerticalProfilePointInput] = Field(min_length=2, max_length=10_000)
    crossings: list[VerticalCrossingInput] = Field(default_factory=list, max_length=10_000)
    vertical_datum: str = Field(min_length=1, max_length=200)
    route_outside_diameter_m: float = Field(gt=0, le=20)
    minimum_clearance_m: float = Field(ge=0, le=100)
    maximum_grade_percent: float = Field(gt=0, le=100)

    @model_validator(mode="after")
    def ordered_profile(self) -> VerticalValidationRequest:
        chainages = [item.chainage_m for item in self.profile]
        if any(right <= left for left, right in zip(chainages, chainages[1:], strict=False)):
            raise ValueError("profile chainage must be strictly increasing")
        return self


@router.get("/engineering/construction-methods", tags=["engineering"])
def list_construction_methods(_principal: Reader) -> list[dict[str, Any]]:
    return [asdict(method) for method in CONSTRUCTION_METHODS.values()]


@router.post(
    "/engineering/hydraulics/calculate",
    response_model=EngineeringJobResponse,
    status_code=status.HTTP_202_ACCEPTED,
    tags=["engineering"],
)
def run_hydraulic_calculation(
    request: HydraulicCalculationRequest,
    session: DbSession,
    principal: Editor,
) -> EngineeringJobResponse:
    job = Job(
        workspace_id=principal.workspace_id,
        kind="hydraulic_calculation",
        state="queued",
        phase="queued",
        payload=request.model_dump(mode="json"),
        max_attempts=1,
        retryable=False,
    )
    session.add(job)
    session.flush()
    event = OutboxEvent(
        aggregate_type="engineering_job",
        aggregate_id=job.id,
        event_type="engineering.hydraulics_requested",
        payload={"job_id": str(job.id)},
        state="pending",
        attempts=0,
    )
    session.add(event)
    session.commit()
    publish_outbox_event(session, event.id)
    session.refresh(job)
    return _engineering_job_response(job)


@router.get(
    "/engineering/jobs/{job_id}",
    response_model=EngineeringJobResponse,
    tags=["engineering"],
)
def get_engineering_job(
    job_id: UUID, session: DbSession, principal: Reader
) -> EngineeringJobResponse:
    job = session.scalar(
        select(Job).where(
            Job.id == job_id,
            Job.workspace_id == principal.workspace_id,
            Job.kind == "hydraulic_calculation",
        )
    )
    if job is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="job not found")
    return _engineering_job_response(job)


def _engineering_job_response(job: Job) -> EngineeringJobResponse:
    return EngineeringJobResponse(
        job_id=job.id,
        state=job.state,
        phase=job.phase,
        result=job.result,
        error_code=job.error_code,
        status_url=f"/api/v1/engineering/jobs/{job.id}",
    )


@router.post("/engineering/vertical/validate", tags=["engineering"])
def run_vertical_validation(
    request: VerticalValidationRequest, _principal: Editor
) -> dict[str, Any]:
    try:
        result = validate_vertical_profile(
            tuple(VerticalProfilePoint(**item.model_dump()) for item in request.profile),
            tuple(VerticalCrossing(**item.model_dump()) for item in request.crossings),
            vertical_datum=request.vertical_datum,
            route_outside_diameter_m=request.route_outside_diameter_m,
            minimum_clearance_m=request.minimum_clearance_m,
            maximum_grade_percent=request.maximum_grade_percent,
        )
    except ValueError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail={"code": "VERTICAL_INPUT_INVALID", "message": str(error)},
        ) from error
    return asdict(result)
