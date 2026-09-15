import json
import time
import tracemalloc
from pathlib import Path

from heatroute.domain.routing import GridRoutingRequest, solve_grid
from heatroute.domain.routing.grid import GridGraph


def measure(size: int, algorithm: str) -> dict[str, object]:
    request = GridRoutingRequest(
        bounds=(0, 0, size, size),
        start=(1, 1),
        goal=(size - 1, size - 1),
        corridor_width_m=0.2,
        resolution_m=1,
        forbidden_rectangles=((size * 0.45, 0, size * 0.55, size * 0.72),),
    )
    tracemalloc.start()
    started = time.perf_counter()
    result = solve_grid(GridGraph(request), algorithm=algorithm)
    elapsed = time.perf_counter() - started
    _, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    return {
        "size_m": size,
        "algorithm": algorithm,
        "outcome": result.outcome,
        "cost": result.cost,
        "expanded_states": result.expanded_states,
        "elapsed_ms": round(elapsed * 1000, 3),
        "python_peak_memory_mb": round(peak / 1024 / 1024, 3),
    }


def main() -> None:
    rows = [
        measure(size, algorithm)
        for size in (20, 40)
        for algorithm in ("astar", "dijkstra")
    ]
    output = Path("artifacts/benchmarks/m3-routing.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"measurements": rows}, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(output), "measurements": rows}, indent=2))


if __name__ == "__main__":
    main()
