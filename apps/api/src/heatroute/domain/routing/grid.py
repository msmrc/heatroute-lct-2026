from dataclasses import dataclass
from math import ceil, floor, hypot, isclose

from shapely.geometry import LineString, Point, Polygon, box
from shapely.geometry.base import BaseGeometry
from shapely.prepared import PreparedGeometry, prep

from heatroute.domain.routing.types import Coordinate, GridState

DIRECTIONS: tuple[tuple[int, int], ...] = (
    (1, 0),
    (1, 1),
    (0, 1),
    (-1, 1),
    (-1, 0),
    (-1, -1),
    (0, -1),
    (1, -1),
)
START_HEADING = -1


@dataclass(frozen=True)
class CostZone:
    geometry: BaseGeometry
    cost_per_m: float

    def __post_init__(self) -> None:
        if self.cost_per_m < 0:
            raise ValueError("cost_per_m must be non-negative")


@dataclass(frozen=True)
class GridRoutingRequest:
    bounds: tuple[float, float, float, float]
    start: Coordinate
    goal: Coordinate
    resolution_m: float
    corridor_width_m: float
    forbidden_rectangles: tuple[tuple[float, float, float, float], ...] = ()
    forbidden_geometries: tuple[BaseGeometry, ...] = ()
    waypoints: tuple[Coordinate, ...] = ()
    construction_mode: str = "open_trench"
    objective: str = "shortest"
    default_cost_per_m: float = 1.0
    minimum_cost_per_m: float | None = 1.0
    cost_zones: tuple[CostZone, ...] = ()
    unverified_geometries: tuple[BaseGeometry, ...] = ()
    turn_cost: float = 0.0
    reuse_penalty_geometries: tuple[BaseGeometry, ...] = ()
    reuse_penalty_per_m: float = 0.0

    def __post_init__(self) -> None:
        min_x, min_y, max_x, max_y = self.bounds
        if min_x >= max_x or min_y >= max_y:
            raise ValueError("bounds must have positive area")
        if self.resolution_m <= 0:
            raise ValueError("resolution_m must be positive")
        if self.corridor_width_m <= 0:
            raise ValueError("corridor_width_m must be positive")
        if not self.construction_mode.strip():
            raise ValueError("construction_mode must not be blank")
        if self.objective not in {"shortest", "estimated_cost", "least_unverified"}:
            raise ValueError("unsupported routing objective")
        if self.default_cost_per_m < 0 or self.turn_cost < 0:
            raise ValueError("routing costs must be non-negative")
        if self.minimum_cost_per_m is not None and self.minimum_cost_per_m < 0:
            raise ValueError("minimum_cost_per_m must be non-negative")
        if self.reuse_penalty_per_m < 0:
            raise ValueError("reuse penalty must be non-negative")


class GridGraph:
    def __init__(self, request: GridRoutingRequest) -> None:
        self.request = request
        self.min_x, self.min_y, self.max_x, self.max_y = request.bounds
        self.resolution = request.resolution_m
        self.half_width = request.corridor_width_m / 2
        self.bounds_polygon: Polygon = box(*request.bounds)
        self.obstacles: tuple[BaseGeometry, ...] = (
            *(box(*rectangle) for rectangle in request.forbidden_rectangles),
            *request.forbidden_geometries,
        )
        self.prepared_obstacles: tuple[PreparedGeometry, ...] = tuple(
            prep(obstacle) for obstacle in self.obstacles
        )
        self.min_ix = ceil((self.min_x - self.min_x) / self.resolution)
        self.max_ix = floor((self.max_x - self.min_x) / self.resolution)
        self.min_iy = ceil((self.min_y - self.min_y) / self.resolution)
        self.max_iy = floor((self.max_y - self.min_y) / self.resolution)
        self.waypoint_indices = tuple(
            self.coordinate_to_index(waypoint) for waypoint in request.waypoints
        )
        start_waypoint_index = self._advance_waypoint(0, self.coordinate_to_index(request.start))
        self.start_state = (
            *self.coordinate_to_index(request.start),
            START_HEADING,
            0,
            start_waypoint_index,
        )
        self.goal_index = self.coordinate_to_index(request.goal)
        self._neighbor_cache: dict[GridState, tuple[tuple[GridState, float], ...]] = {}

    def coordinate_to_index(self, coordinate: Coordinate) -> tuple[int, int]:
        raw_x = (coordinate[0] - self.min_x) / self.resolution
        raw_y = (coordinate[1] - self.min_y) / self.resolution
        ix, iy = round(raw_x), round(raw_y)
        if not isclose(raw_x, ix, abs_tol=1e-9) or not isclose(raw_y, iy, abs_tol=1e-9):
            raise ValueError("start and goal must lie on the configured grid")
        return ix, iy

    def state_coordinate(self, state: GridState) -> Coordinate:
        return (
            self.min_x + state[0] * self.resolution,
            self.min_y + state[1] * self.resolution,
        )

    def is_goal(self, state: GridState) -> bool:
        return state[:2] == self.goal_index and state[4] == len(self.waypoint_indices)

    def heuristic(self, state: GridState) -> float:
        x, y = self.state_coordinate(state)
        remaining = [
            self.state_coordinate((*index, 0, 0, 0)) for index in self.waypoint_indices[state[4] :]
        ]
        remaining.append(self.request.goal)
        distance = 0.0
        current = (x, y)
        for target in remaining:
            distance += hypot(current[0] - target[0], current[1] - target[1])
            current = target
        if self.request.objective == "shortest":
            return distance
        if self.request.objective == "estimated_cost":
            return (
                0.0
                if self.request.minimum_cost_per_m is None
                else distance * self.request.minimum_cost_per_m
            )
        return 0.0

    def valid_initial_state(self) -> bool:
        return all(
            self._point_is_valid(point)
            for point in (self.request.start, *self.request.waypoints, self.request.goal)
        )

    def neighbors(self, state: GridState) -> tuple[tuple[GridState, float], ...]:
        key = state
        cached = self._neighbor_cache.get(key)
        if cached is None:
            cached = self._neighbors_for_state(state)
            self._neighbor_cache[key] = cached
        return cached

    def _neighbors_for_state(self, state: GridState) -> tuple[tuple[GridState, float], ...]:
        ix, iy, previous_heading, construction_mode, waypoint_index = state
        current = self.state_coordinate(state)
        result: list[tuple[GridState, float]] = []
        for heading, (dx, dy) in enumerate(DIRECTIONS):
            next_ix = state[0] + dx
            next_iy = state[1] + dy
            if not (self.min_ix <= next_ix <= self.max_ix):
                continue
            if not (self.min_iy <= next_iy <= self.max_iy):
                continue
            next_waypoint_index = self._advance_waypoint(waypoint_index, (next_ix, next_iy))
            next_state: GridState = (
                next_ix,
                next_iy,
                heading,
                construction_mode,
                next_waypoint_index,
            )
            target = self.state_coordinate(next_state)
            if not self._transition_is_valid(current, target):
                continue
            segment_length = hypot(dx, dy) * self.resolution
            cost = self._transition_cost(
                current,
                target,
                segment_length,
                changed_heading=(previous_heading != START_HEADING and previous_heading != heading),
            )
            result.append((next_state, cost))
        return tuple(result)

    def _advance_waypoint(self, waypoint_index: int, position: tuple[int, int]) -> int:
        current = waypoint_index
        while current < len(self.waypoint_indices) and self.waypoint_indices[current] == position:
            current += 1
        return current

    def _transition_cost(
        self,
        start: Coordinate,
        end: Coordinate,
        segment_length: float,
        *,
        changed_heading: bool,
    ) -> float:
        segment = LineString((start, end))
        if self.request.objective == "estimated_cost":
            cost = segment_length * self.request.default_cost_per_m
            for zone in self.request.cost_zones:
                overlap = segment.intersection(zone.geometry).length
                cost += overlap * (zone.cost_per_m - self.request.default_cost_per_m)
        elif self.request.objective == "least_unverified":
            unknown_length = sum(
                segment.intersection(geometry).length
                for geometry in self.request.unverified_geometries
            )
            lexicographic_scale = (
                hypot(self.max_x - self.min_x, self.max_y - self.min_y) + self.resolution
            )
            cost = unknown_length * lexicographic_scale + segment_length
        else:
            cost = segment_length
        if changed_heading:
            cost += self.request.turn_cost
        if self.request.reuse_penalty_per_m:
            reused_length = sum(
                segment.intersection(geometry).length
                for geometry in self.request.reuse_penalty_geometries
            )
            cost += reused_length * self.request.reuse_penalty_per_m
        if cost < 0:
            raise ValueError("edge cost must be non-negative")
        return cost

    def _point_is_valid(self, coordinate: Coordinate) -> bool:
        footprint = Point(coordinate).buffer(self.half_width)
        if not self.bounds_polygon.covers(footprint):
            return False
        return not any(obstacle.intersects(footprint) for obstacle in self.prepared_obstacles)

    def _transition_is_valid(self, start: Coordinate, end: Coordinate) -> bool:
        corridor = LineString((start, end)).buffer(self.half_width)
        if not self.bounds_polygon.covers(corridor):
            return False
        return not any(obstacle.intersects(corridor) for obstacle in self.prepared_obstacles)
