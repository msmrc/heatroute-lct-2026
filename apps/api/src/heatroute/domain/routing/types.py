from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol

type Coordinate = tuple[float, float]
type GridState = tuple[int, int, int, int, int]


class GraphProvider(Protocol):
    start_state: GridState

    def valid_initial_state(self) -> bool: ...

    def is_goal(self, state: GridState) -> bool: ...

    def heuristic(self, state: GridState) -> float: ...

    def neighbors(self, state: GridState) -> tuple[tuple[GridState, float], ...]: ...

    def state_coordinate(self, state: GridState) -> Coordinate: ...


class RouteOutcome(StrEnum):
    ROUTES_FOUND = "routes_found"
    NO_ROUTE_IN_MODEL = "no_route_in_model"
    BUDGET_EXCEEDED = "budget_exceeded"
    INVALID_INPUT = "invalid_input"
    CANCELLED = "cancelled"


class SearchCompletion(StrEnum):
    COMPLETE = "complete"
    BUDGET_EXHAUSTED = "budget_exhausted"
    NOT_STARTED = "not_started"
    CANCELLED = "cancelled"


@dataclass(frozen=True)
class ComputationBudget:
    max_expanded_states: int = 250_000
    max_wall_time_s: float | None = None
    max_memory_mb: float | None = None

    def __post_init__(self) -> None:
        if self.max_expanded_states <= 0:
            raise ValueError("max_expanded_states must be positive")
        if self.max_wall_time_s is not None and self.max_wall_time_s <= 0:
            raise ValueError("max_wall_time_s must be positive")
        if self.max_memory_mb is not None and self.max_memory_mb <= 0:
            raise ValueError("max_memory_mb must be positive")


@dataclass(frozen=True)
class SolverResult:
    outcome: RouteOutcome
    search_completion: SearchCompletion
    algorithm: str
    path: tuple[Coordinate, ...] = ()
    cost: float | None = None
    expanded_states: int = 0
    diagnostic: str | None = None
