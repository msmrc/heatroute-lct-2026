from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass

from heatroute.domain.routing.types import Coordinate


@dataclass(frozen=True)
class ShortcutOptimizationResult:
    path: tuple[Coordinate, ...]
    candidate_shortcuts: int
    accepted_shortcuts: int
    removed_vertices: int


def optimize_validated_shortcuts(
    path: tuple[Coordinate, ...],
    *,
    is_valid: Callable[[tuple[Coordinate, ...]], bool],
    mandatory_points: frozenset[Coordinate] = frozenset(),
    max_candidate_checks: int = 10_000,
) -> ShortcutOptimizationResult:
    """Greedily shortcut a polyline, revalidating the complete candidate corridor.

    Mandatory coordinates are never skipped. The validation callback is deliberately
    supplied by the caller so hard constraints and construction-specific exceptions
    remain authoritative.
    """
    if len(path) < 3:
        return ShortcutOptimizationResult(path, 0, 0, 0)
    original_count = len(path)
    current = list(path)
    checks = 0
    accepted = 0
    index = 0
    while index < len(current) - 2 and checks < max_candidate_checks:
        selected: int | None = None
        for end in range(len(current) - 1, index + 1, -1):
            skipped = current[index + 1 : end]
            if any(point in mandatory_points for point in skipped):
                continue
            candidate = tuple((*current[: index + 1], *current[end:]))
            checks += 1
            if is_valid(candidate):
                selected = end
                break
            if checks >= max_candidate_checks:
                break
        if selected is None or selected == index + 1:
            index += 1
            continue
        del current[index + 1 : selected]
        accepted += 1
    optimized = tuple(current)
    return ShortcutOptimizationResult(
        optimized,
        checks,
        accepted,
        original_count - len(optimized),
    )
