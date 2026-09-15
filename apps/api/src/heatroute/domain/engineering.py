from __future__ import annotations

from dataclasses import asdict, dataclass
from importlib.metadata import version
from typing import Any

import pandapipes as pp


@dataclass(frozen=True)
class HydraulicNode:
    node_id: str
    nominal_pressure_bar: float
    temperature_k: float
    elevation_m: float


@dataclass(frozen=True)
class HydraulicPipe:
    pipe_id: str
    from_node_id: str
    to_node_id: str
    length_m: float
    inner_diameter_mm: float
    roughness_mm: float
    loss_coefficient: float


@dataclass(frozen=True)
class HydraulicBoundary:
    node_id: str
    pressure_bar: float
    temperature_k: float


@dataclass(frozen=True)
class HydraulicDemand:
    node_id: str
    mass_flow_kg_per_s: float


@dataclass(frozen=True)
class HydraulicThresholds:
    minimum_pressure_bar: float
    maximum_velocity_m_per_s: float
    mass_balance_tolerance_kg_per_s: float


class HydraulicInputError(ValueError):
    pass


class HydraulicCalculationError(RuntimeError):
    pass


def calculate_hydraulics(
    nodes: tuple[HydraulicNode, ...],
    pipes: tuple[HydraulicPipe, ...],
    boundaries: tuple[HydraulicBoundary, ...],
    demands: tuple[HydraulicDemand, ...],
    thresholds: HydraulicThresholds,
) -> dict[str, Any]:
    if not nodes or not pipes or not boundaries:
        raise HydraulicInputError("nodes, pipes and at least one pressure boundary are required")
    node_ids = [node.node_id for node in nodes]
    if len(node_ids) != len(set(node_ids)):
        raise HydraulicInputError("hydraulic node IDs must be unique")
    known_nodes = set(node_ids)
    referenced = (
        {pipe.from_node_id for pipe in pipes}
        | {pipe.to_node_id for pipe in pipes}
        | {item.node_id for item in boundaries + demands}
    )
    missing = sorted(referenced - known_nodes)
    if missing:
        raise HydraulicInputError("unknown hydraulic nodes: " + ", ".join(missing))
    pipe_ids = [pipe.pipe_id for pipe in pipes]
    if len(pipe_ids) != len(set(pipe_ids)):
        raise HydraulicInputError("hydraulic pipe IDs must be unique")

    network = pp.create_empty_network(fluid="water")
    indexes: dict[str, int] = {}
    for node in nodes:
        indexes[node.node_id] = int(
            pp.create_junction(
                network,
                pn_bar=node.nominal_pressure_bar,
                tfluid_k=node.temperature_k,
                height_m=node.elevation_m,
                name=node.node_id,
            )
        )
    for pipe in pipes:
        pp.create_pipe_from_parameters(
            network,
            indexes[pipe.from_node_id],
            indexes[pipe.to_node_id],
            length_km=pipe.length_m / 1000,
            inner_diameter_mm=pipe.inner_diameter_mm,
            k_mm=pipe.roughness_mm,
            loss_coefficient=pipe.loss_coefficient,
            name=pipe.pipe_id,
        )
    for boundary in boundaries:
        pp.create_ext_grid(
            network,
            indexes[boundary.node_id],
            p_bar=boundary.pressure_bar,
            t_k=boundary.temperature_k,
            name=f"boundary:{boundary.node_id}",
        )
    for demand in demands:
        pp.create_sink(
            network,
            indexes[demand.node_id],
            mdot_kg_per_s=demand.mass_flow_kg_per_s,
            name=f"demand:{demand.node_id}",
        )
    try:
        pp.pipeflow(network, mode="hydraulics")
    except Exception as error:
        raise HydraulicCalculationError("pandapipes hydraulic solver did not converge") from error
    if not bool(network.converged):
        raise HydraulicCalculationError("pandapipes hydraulic solver did not converge")

    junction_results: list[dict[str, Any]] = [
        {
            "node_id": node.node_id,
            "pressure_bar": float(network.res_junction.at[indexes[node.node_id], "p_bar"]),
            "temperature_k": float(network.res_junction.at[indexes[node.node_id], "t_k"]),
        }
        for node in nodes
    ]
    pipe_results: list[dict[str, Any]] = [
        {
            "pipe_id": pipe.pipe_id,
            "velocity_m_per_s": float(network.res_pipe.iloc[index]["v_mean_m_per_s"]),
            "mass_flow_kg_per_s": float(network.res_pipe.iloc[index]["mdot_from_kg_per_s"]),
            "pressure_from_bar": float(network.res_pipe.iloc[index]["p_from_bar"]),
            "pressure_to_bar": float(network.res_pipe.iloc[index]["p_to_bar"]),
            "friction_loss_bar": float(network.res_pipe.iloc[index]["dp_friction_loss_bar"]),
            "reynolds": float(network.res_pipe.iloc[index]["reynolds"]),
        }
        for index, pipe in enumerate(pipes)
    ]
    supplied_mass = abs(float(network.res_ext_grid["mdot_kg_per_s"].sum()))
    demanded_mass = sum(item.mass_flow_kg_per_s for item in demands)
    mass_balance_error = abs(supplied_mass - demanded_mass)
    findings: list[dict[str, Any]] = []
    for result in junction_results:
        if float(result["pressure_bar"]) < thresholds.minimum_pressure_bar:
            findings.append(
                {
                    "code": "MINIMUM_PRESSURE_EXCEEDED",
                    "severity": "error",
                    "element_id": result["node_id"],
                    "measured_value": result["pressure_bar"],
                    "threshold_value": thresholds.minimum_pressure_bar,
                }
            )
    for result in pipe_results:
        if abs(float(result["velocity_m_per_s"])) > thresholds.maximum_velocity_m_per_s:
            findings.append(
                {
                    "code": "MAXIMUM_VELOCITY_EXCEEDED",
                    "severity": "error",
                    "element_id": result["pipe_id"],
                    "measured_value": abs(float(result["velocity_m_per_s"])),
                    "threshold_value": thresholds.maximum_velocity_m_per_s,
                }
            )
    if mass_balance_error > thresholds.mass_balance_tolerance_kg_per_s:
        findings.append(
            {
                "code": "MASS_BALANCE_EXCEEDED",
                "severity": "error",
                "element_id": None,
                "measured_value": mass_balance_error,
                "threshold_value": thresholds.mass_balance_tolerance_kg_per_s,
            }
        )
    return {
        "status": "threshold_exceeded" if findings else "passed",
        "converged": True,
        "solver": {"name": "pandapipes", "version": version("pandapipes"), "mode": "hydraulics"},
        "fluid": "water",
        "junctions": junction_results,
        "pipes": pipe_results,
        "balances": {
            "supplied_mass_kg_per_s": supplied_mass,
            "demanded_mass_kg_per_s": demanded_mass,
            "mass_balance_error_kg_per_s": mass_balance_error,
            "energy_balance": "not_performed",
        },
        "thresholds": asdict(thresholds),
        "findings": findings,
        "assumptions": [],
    }
