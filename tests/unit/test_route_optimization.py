from shapely.geometry import LineString, box

from heatroute.domain.routing.optimization import optimize_validated_shortcuts


def test_shortcut_optimizer_reduces_vertices_and_revalidates_candidates() -> None:
    path = ((0.0, 0.0), (0.0, 2.0), (2.0, 2.0), (4.0, 2.0), (4.0, 0.0))
    obstacle = box(1.5, -0.5, 2.5, 1.5)

    result = optimize_validated_shortcuts(
        path,
        is_valid=lambda candidate: not LineString(candidate).intersects(obstacle),
    )

    assert result.removed_vertices > 0
    assert result.accepted_shortcuts > 0
    assert not LineString(result.path).intersects(obstacle)


def test_shortcut_optimizer_preserves_mandatory_point() -> None:
    mandatory = (1.0, 1.0)
    result = optimize_validated_shortcuts(
        ((0.0, 0.0), mandatory, (2.0, 0.0)),
        is_valid=lambda _candidate: True,
        mandatory_points=frozenset({mandatory}),
    )

    assert mandatory in result.path
