from dataclasses import dataclass

from shapely.geometry import LineString, box

from heatroute.domain.routing.grid import GridRoutingRequest
from heatroute.domain.routing.types import Coordinate


@dataclass(frozen=True)
class RouteValidationReport:
    valid: bool
    corridor_area_m2: float
    finding_codes: tuple[str, ...]


def validate_route(
    path: tuple[Coordinate, ...],
    request: GridRoutingRequest,
) -> RouteValidationReport:
    if len(path) < 2:
        return RouteValidationReport(
            valid=False,
            corridor_area_m2=0.0,
            finding_codes=("ROUTE_GEOMETRY_TOO_SHORT",),
        )
    line = LineString(path)
    corridor = line.buffer(request.corridor_width_m / 2)
    findings: list[str] = []
    if not box(*request.bounds).covers(corridor):
        findings.append("ROUTE_OUTSIDE_AOI")
    if any(box(*rectangle).intersects(corridor) for rectangle in request.forbidden_rectangles):
        findings.append("FORBIDDEN_CORRIDOR_INTERSECTION")
    if any(obstacle.intersects(corridor) for obstacle in request.forbidden_geometries):
        findings.append("CANONICAL_HARD_CONSTRAINT_INTERSECTION")
    return RouteValidationReport(
        valid=not findings,
        corridor_area_m2=corridor.area,
        finding_codes=tuple(findings),
    )
