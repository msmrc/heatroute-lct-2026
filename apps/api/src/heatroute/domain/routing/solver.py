from collections.abc import Callable
from heapq import heappop, heappush
from itertools import count
from math import isclose
from time import monotonic

from heatroute.domain.routing.types import (
    ComputationBudget,
    GraphProvider,
    GridState,
    RouteOutcome,
    SearchCompletion,
    SolverResult,
)


def solve_grid(
    graph: GraphProvider,
    *,
    algorithm: str = "astar",
    budget: ComputationBudget | None = None,
    should_cancel: Callable[[], bool] | None = None,
    on_progress: Callable[[int], None] | None = None,
) -> SolverResult:
    if algorithm not in {"astar", "dijkstra"}:
        raise ValueError("algorithm must be 'astar' or 'dijkstra'")
    active_budget = budget or ComputationBudget()
    if not graph.valid_initial_state():
        return SolverResult(
            outcome=RouteOutcome.INVALID_INPUT,
            search_completion=SearchCompletion.NOT_STARTED,
            algorithm=algorithm,
            diagnostic="START_OR_GOAL_CORRIDOR_INVALID",
        )
    started_at = monotonic()

    start = graph.start_state
    sequence = count()
    frontier: list[tuple[float, float, int, GridState]] = []
    heappush(frontier, (0.0, 0.0, next(sequence), start))
    best_cost: dict[GridState, float] = {start: 0.0}
    predecessor: dict[GridState, GridState] = {}
    expanded = 0

    while frontier:
        _, queued_cost, _, current = heappop(frontier)
        current_best = best_cost[current]
        if not isclose(queued_cost, current_best, rel_tol=0, abs_tol=1e-12):
            continue
        if expanded % 256 == 0 and should_cancel is not None and should_cancel():
            return SolverResult(
                outcome=RouteOutcome.CANCELLED,
                search_completion=SearchCompletion.CANCELLED,
                algorithm=algorithm,
                expanded_states=expanded,
                diagnostic="CANCEL_REQUESTED",
            )
        if (
            expanded % 256 == 0
            and active_budget.max_wall_time_s is not None
            and monotonic() - started_at >= active_budget.max_wall_time_s
        ):
            return SolverResult(
                outcome=RouteOutcome.BUDGET_EXCEEDED,
                search_completion=SearchCompletion.BUDGET_EXHAUSTED,
                algorithm=algorithm,
                expanded_states=expanded,
                diagnostic="MAX_WALL_TIME",
            )
        if (
            expanded % 256 == 0
            and active_budget.max_memory_mb is not None
            and ((len(best_cost) + len(predecessor) + len(frontier)) * 192 / (1024 * 1024))
            >= active_budget.max_memory_mb
        ):
            return SolverResult(
                outcome=RouteOutcome.BUDGET_EXCEEDED,
                search_completion=SearchCompletion.BUDGET_EXHAUSTED,
                algorithm=algorithm,
                expanded_states=expanded,
                diagnostic="MAX_MEMORY",
            )
        if graph.is_goal(current):
            path = _reconstruct_path(graph, predecessor, current)
            return SolverResult(
                outcome=RouteOutcome.ROUTES_FOUND,
                search_completion=SearchCompletion.COMPLETE,
                algorithm=algorithm,
                path=path,
                cost=current_best,
                expanded_states=expanded,
            )
        if expanded >= active_budget.max_expanded_states:
            return SolverResult(
                outcome=RouteOutcome.BUDGET_EXCEEDED,
                search_completion=SearchCompletion.BUDGET_EXHAUSTED,
                algorithm=algorithm,
                expanded_states=expanded,
                diagnostic="MAX_EXPANDED_STATES",
            )
        expanded += 1
        if expanded % 2_048 == 0 and on_progress is not None:
            on_progress(expanded)

        for neighbor, edge_cost in graph.neighbors(current):
            candidate_cost = current_best + edge_cost
            previous_cost = best_cost.get(neighbor)
            if previous_cost is not None and candidate_cost >= previous_cost - 1e-12:
                continue
            best_cost[neighbor] = candidate_cost
            predecessor[neighbor] = current
            heuristic = graph.heuristic(neighbor) if algorithm == "astar" else 0.0
            heappush(
                frontier,
                (candidate_cost + heuristic, candidate_cost, next(sequence), neighbor),
            )

    return SolverResult(
        outcome=RouteOutcome.NO_ROUTE_IN_MODEL,
        search_completion=SearchCompletion.COMPLETE,
        algorithm=algorithm,
        expanded_states=expanded,
        diagnostic="BOUNDED_GRID_EXHAUSTED",
    )


def _reconstruct_path(
    graph: GraphProvider,
    predecessor: dict[GridState, GridState],
    goal: GridState,
) -> tuple[tuple[float, float], ...]:
    states = [goal]
    current = goal
    while current in predecessor:
        current = predecessor[current]
        states.append(current)
    states.reverse()
    return tuple(graph.state_coordinate(state) for state in states)
