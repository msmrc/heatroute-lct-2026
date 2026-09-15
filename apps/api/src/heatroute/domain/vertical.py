from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class VerticalProfilePoint:
    chainage_m: float
    elevation_m: float


@dataclass(frozen=True)
class VerticalCrossing:
    crossing_id: str
    chainage_m: float
    vertical_datum: str | None
    elevation_m: float | None = None
    surface_elevation_m: float | None = None
    depth_m: float | None = None
    outside_diameter_m: float | None = None


@dataclass(frozen=True)
class VerticalFinding:
    code: str
    severity: str
    crossing_id: str | None
    message: str
    measured_value: float | None = None
    threshold_value: float | None = None


@dataclass(frozen=True)
class VerticalValidationResult:
    status: str
    datum: str
    max_grade_percent: float
    crossings_checked: int
    findings: tuple[VerticalFinding, ...]


def _interpolate(profile: tuple[VerticalProfilePoint, ...], chainage_m: float) -> float:
    if chainage_m < profile[0].chainage_m or chainage_m > profile[-1].chainage_m:
        raise ValueError("crossing chainage is outside the vertical profile")
    for left, right in zip(profile, profile[1:], strict=False):
        if left.chainage_m <= chainage_m <= right.chainage_m:
            span = right.chainage_m - left.chainage_m
            fraction = 0.0 if span == 0 else (chainage_m - left.chainage_m) / span
            return left.elevation_m + fraction * (right.elevation_m - left.elevation_m)
    return profile[-1].elevation_m


def validate_vertical_profile(
    profile: tuple[VerticalProfilePoint, ...],
    crossings: tuple[VerticalCrossing, ...],
    *,
    vertical_datum: str,
    route_outside_diameter_m: float,
    minimum_clearance_m: float,
    maximum_grade_percent: float,
) -> VerticalValidationResult:
    if len(profile) < 2:
        raise ValueError("vertical profile requires at least two points")
    if any(
        right.chainage_m <= left.chainage_m
        for left, right in zip(profile, profile[1:], strict=False)
    ):
        raise ValueError("vertical profile chainage must be strictly increasing")
    grades = [
        abs(right.elevation_m - left.elevation_m) / (right.chainage_m - left.chainage_m) * 100
        for left, right in zip(profile, profile[1:], strict=False)
    ]
    max_grade = max(grades)
    findings: list[VerticalFinding] = []
    if max_grade > maximum_grade_percent:
        findings.append(
            VerticalFinding(
                "MAXIMUM_GRADE_EXCEEDED",
                "error",
                None,
                "Route profile exceeds the explicitly configured maximum grade.",
                max_grade,
                maximum_grade_percent,
            )
        )
    checked = 0
    for crossing in crossings:
        if crossing.vertical_datum is None or crossing.vertical_datum != vertical_datum:
            findings.append(
                VerticalFinding(
                    "VERTICAL_DATUM_INCOMPATIBLE",
                    "insufficient_data",
                    crossing.crossing_id,
                    "Crossing and route must use the same confirmed vertical datum.",
                )
            )
            continue
        asset_elevation = crossing.elevation_m
        if (
            asset_elevation is None
            and crossing.surface_elevation_m is not None
            and crossing.depth_m is not None
        ):
            asset_elevation = crossing.surface_elevation_m - crossing.depth_m
        if asset_elevation is None or crossing.outside_diameter_m is None:
            findings.append(
                VerticalFinding(
                    "CROSSING_ELEVATION_INCOMPLETE",
                    "insufficient_data",
                    crossing.crossing_id,
                    "Crossing elevation/depth and outside diameter are required.",
                )
            )
            continue
        route_elevation = _interpolate(profile, crossing.chainage_m)
        clear_distance = (
            abs(route_elevation - asset_elevation)
            - (route_outside_diameter_m + crossing.outside_diameter_m) / 2
        )
        checked += 1
        if clear_distance < minimum_clearance_m:
            findings.append(
                VerticalFinding(
                    "VERTICAL_CLEARANCE_INSUFFICIENT",
                    "error",
                    crossing.crossing_id,
                    "Calculated outside-to-outside vertical clearance is insufficient.",
                    clear_distance,
                    minimum_clearance_m,
                )
            )
    status = (
        "failed"
        if any(item.severity == "error" for item in findings)
        else "insufficient_data"
        if any(item.severity == "insufficient_data" for item in findings)
        else "passed"
    )
    return VerticalValidationResult(status, vertical_datum, max_grade, checked, tuple(findings))
