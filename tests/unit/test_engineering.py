from fastapi.testclient import TestClient

from heatroute.api.app import create_app
from heatroute.domain.engineering import (
    HydraulicBoundary,
    HydraulicDemand,
    HydraulicInputError,
    HydraulicNode,
    HydraulicPipe,
    HydraulicThresholds,
    calculate_hydraulics,
)
from heatroute.domain.vertical import (
    VerticalCrossing,
    VerticalProfilePoint,
    validate_vertical_profile,
)


def test_pandapipes_hydraulic_calculation_returns_balances_and_element_results() -> None:
    result = calculate_hydraulics(
        (
            HydraulicNode("source", 6, 353.15, 150),
            HydraulicNode("consumer", 6, 353.15, 152),
        ),
        (HydraulicPipe("P-1", "source", "consumer", 200, 100, 0.1, 1),),
        (HydraulicBoundary("source", 6, 353.15),),
        (HydraulicDemand("consumer", 2),),
        HydraulicThresholds(2, 2, 0.0001),
    )

    assert result["status"] == "passed"
    assert result["converged"] is True
    assert result["solver"]["name"] == "pandapipes"
    assert result["balances"]["mass_balance_error_kg_per_s"] < 0.0001
    assert result["balances"]["energy_balance"] == "not_performed"
    assert result["pipes"][0]["velocity_m_per_s"] > 0
    assert result["junctions"][1]["pressure_bar"] < 6


def test_hydraulic_calculation_rejects_unknown_node_reference() -> None:
    try:
        calculate_hydraulics(
            (HydraulicNode("source", 6, 353.15, 0), HydraulicNode("target", 6, 353.15, 0)),
            (HydraulicPipe("P-1", "source", "missing", 20, 100, 0.1, 0),),
            (HydraulicBoundary("source", 6, 353.15),),
            (),
            HydraulicThresholds(2, 2, 0.001),
        )
    except HydraulicInputError as error:
        assert "missing" in str(error)
    else:
        raise AssertionError("unknown node was accepted")


def test_vertical_validation_calculates_grade_and_outside_clearance() -> None:
    result = validate_vertical_profile(
        (VerticalProfilePoint(0, 100), VerticalProfilePoint(100, 99)),
        (
            VerticalCrossing(
                "utility-1",
                50,
                "Baltic-1977",
                elevation_m=98,
                outside_diameter_m=0.4,
            ),
        ),
        vertical_datum="Baltic-1977",
        route_outside_diameter_m=0.5,
        minimum_clearance_m=0.6,
        maximum_grade_percent=3,
    )

    assert result.status == "passed"
    assert result.max_grade_percent == 1
    assert result.crossings_checked == 1


def test_vertical_validation_marks_unknown_datum_as_insufficient_data() -> None:
    result = validate_vertical_profile(
        (VerticalProfilePoint(0, 100), VerticalProfilePoint(100, 100)),
        (VerticalCrossing("utility-1", 50, None, elevation_m=98, outside_diameter_m=0.4),),
        vertical_datum="Baltic-1977",
        route_outside_diameter_m=0.5,
        minimum_clearance_m=0.6,
        maximum_grade_percent=3,
    )

    assert result.status == "insufficient_data"
    assert result.findings[0].code == "VERTICAL_DATUM_INCOMPATIBLE"


def test_engineering_api_exposes_real_method_catalog() -> None:
    client = TestClient(create_app())
    response = client.get("/api/v1/engineering/construction-methods")

    assert response.status_code == 200
    methods = {item["code"]: item for item in response.json()}
    assert methods["horizontal_directional_drilling"]["trenchless"] is True
    assert "geology_class" in methods["microtunneling"]["required_inputs"]
