from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Literal

from shapely.geometry import LineString
from shapely.geometry.base import BaseGeometry
from shapely.ops import substring

from heatroute.domain.constraints import CrossingEvent, CrossingPortal

MONEY_QUANTUM = Decimal("0.01")
QUANTITY_QUANTUM = Decimal("0.001")


@dataclass(frozen=True)
class RouteSegment:
    id: str
    start_chainage_m: Decimal
    end_chainage_m: Decimal
    construction_method: str
    geometry: LineString

    @property
    def length_m(self) -> Decimal:
        return self.end_chainage_m - self.start_chainage_m


@dataclass(frozen=True)
class QuantityItem:
    key: str
    quantity: Decimal | None
    unit: str
    per: Literal["corridor_m", "pipe_m", "m2", "event", "item"]
    construction_method: str
    geometry_refs: tuple[str, ...]
    measurement_method: str
    assumptions: tuple[str, ...] = ()


@dataclass(frozen=True)
class QuantityReport:
    route_length_m: Decimal
    segments: tuple[RouteSegment, ...]
    items: tuple[QuantityItem, ...]


@dataclass(frozen=True)
class CatalogRate:
    code: str
    description: str
    quantity_unit: str
    per: Literal["corridor_m", "pipe_m", "m2", "event", "item"]
    applies_to_method: str
    rate: Decimal
    lower_rate: Decimal | None
    upper_rate: Decimal | None
    source_reference: str


@dataclass(frozen=True)
class CostCatalogDefinition:
    id: str
    version: int
    currency: str
    price_date: date
    estimate_status: str
    region_scope: str
    tax_policy: str
    rounding_policy: str
    exclusions: tuple[str, ...]
    items: tuple[CatalogRate, ...]
    schema_version: str = "1.0"

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> CostCatalogDefinition:
        raw_items = value.get("items")
        if not isinstance(raw_items, list) or not raw_items:
            raise ValueError("cost catalog items must be a non-empty list")
        rates: list[CatalogRate] = []
        codes: set[str] = set()
        for raw in raw_items:
            if not isinstance(raw, dict):
                raise ValueError("cost catalog item must be an object")
            code = str(raw.get("code", "")).strip()
            if not code or code in codes:
                raise ValueError("cost catalog item codes must be non-empty and unique")
            codes.add(code)
            per = str(raw.get("per", ""))
            if per not in {"corridor_m", "pipe_m", "m2", "event", "item"}:
                raise ValueError(f"unsupported quantity basis: {per!r}")
            rate = _non_negative_decimal(raw.get("rate"), "rate")
            lower = _optional_decimal(raw.get("lower_rate"), "lower_rate")
            upper = _optional_decimal(raw.get("upper_rate"), "upper_rate")
            if lower is not None and upper is not None and lower > upper:
                raise ValueError("lower_rate must not exceed upper_rate")
            rates.append(
                CatalogRate(
                    code=code,
                    description=str(raw.get("description", "")),
                    quantity_unit=str(raw.get("quantity_unit", "")),
                    per=per,  # type: ignore[arg-type]
                    applies_to_method=str(raw.get("applies_to_method", "all")),
                    rate=rate,
                    lower_rate=lower,
                    upper_rate=upper,
                    source_reference=str(raw.get("source_reference", "")),
                )
            )
        version = int(value.get("version", 0))
        if version <= 0:
            raise ValueError("cost catalog version must be positive")
        return cls(
            id=str(value["id"]),
            version=version,
            currency=str(value["currency"]),
            price_date=date.fromisoformat(str(value["price_date"])),
            estimate_status=str(value["estimate_status"]),
            region_scope=str(value["region_scope"]),
            tax_policy=str(value["tax_policy"]),
            rounding_policy=str(value.get("rounding_policy", "ROUND_HALF_UP_2_DECIMALS")),
            exclusions=tuple(str(item) for item in value.get("exclusions", [])),
            items=tuple(rates),
            schema_version=str(value.get("schema_version", "1.0")),
        )


@dataclass(frozen=True)
class CostLine:
    rate_code: str
    description: str
    quantity_key: str
    quantity: Decimal
    unit: str
    rate: Decimal
    subtotal: Decimal
    source_reference: str


@dataclass(frozen=True)
class CostReport:
    status: Literal["complete", "partial", "unavailable"]
    currency: str | None
    total: Decimal | None
    lines: tuple[CostLine, ...]
    unpriced_items: tuple[str, ...]
    catalog_id: str | None
    catalog_version: int | None
    estimate_status: str | None
    price_date: date | None


@dataclass(frozen=True)
class CostComparison:
    comparable: bool
    delta: Decimal | None
    percentage: Decimal | None
    message: str


def build_quantity_report(
    centerline: LineString,
    *,
    circuit_layout: str,
    default_method: str,
    portals: tuple[CrossingPortal, ...] = (),
    crossing_events: tuple[CrossingEvent, ...] = (),
    corridor_width_m: float,
) -> QuantityReport:
    if centerline.length <= 0:
        raise ValueError("centerline must have positive length")
    if corridor_width_m <= 0:
        raise ValueError("corridor width must be positive")
    if circuit_layout not in {"single", "paired"}:
        raise ValueError("unsupported circuit layout")
    segments = _construction_segments(
        centerline,
        default_method=default_method,
        portals=portals,
        crossing_events=crossing_events,
    )
    route_length = _quantity(centerline.length)
    items: list[QuantityItem] = []
    for segment in segments:
        items.append(
            QuantityItem(
                key=f"corridor:{segment.id}",
                quantity=segment.length_m.quantize(QUANTITY_QUANTUM, rounding=ROUND_HALF_UP),
                unit="m",
                per="corridor_m",
                construction_method=segment.construction_method,
                geometry_refs=(segment.id,),
                measurement_method="metric_chainage",
            )
        )
        pipe_count = 2 if circuit_layout == "paired" else 1
        for pipe_index in range(pipe_count):
            items.append(
                QuantityItem(
                    key=f"pipe:{segment.id}:{pipe_index + 1}",
                    quantity=segment.length_m.quantize(QUANTITY_QUANTUM, rounding=ROUND_HALF_UP),
                    unit="m",
                    per="pipe_m",
                    construction_method=segment.construction_method,
                    geometry_refs=(segment.id,),
                    measurement_method="paired_axis_length"
                    if circuit_layout == "paired"
                    else "single_axis_length",
                    assumptions=("PAIRED_PIPE_LENGTH_EQUALS_CORRIDOR",)
                    if circuit_layout == "paired"
                    else (),
                )
            )
    event_by_portal = {event.portal_id: event for event in crossing_events}
    for portal in portals:
        if portal.id not in event_by_portal:
            continue
        items.append(
            QuantityItem(
                key=f"portal-event:{portal.id}",
                quantity=Decimal(1),
                unit="event",
                per="event",
                construction_method=portal.method,
                geometry_refs=(portal.id,),
                measurement_method="unique_named_crossing_event",
            )
        )
    bend_count = _bend_count(centerline)
    if bend_count:
        items.append(
            QuantityItem(
                key="bend-count",
                quantity=Decimal(bend_count),
                unit="item",
                per="item",
                construction_method="all",
                geometry_refs=("centerline",),
                measurement_method="direction_change_count",
            )
        )
    items.append(
        QuantityItem(
            key="tie-in",
            quantity=Decimal(1),
            unit="event",
            per="event",
            construction_method="demo_tie_in",
            geometry_refs=("route-start",),
            measurement_method="selected_connection_candidate",
        )
    )
    items.append(
        QuantityItem(
            key="restoration-area",
            quantity=_quantity(centerline.length * corridor_width_m),
            unit="m2",
            per="m2",
            construction_method=default_method,
            geometry_refs=("corridor",),
            measurement_method="route_length_times_corridor_width",
        )
    )
    return QuantityReport(route_length, segments, tuple(items))


def price_quantities(
    quantities: QuantityReport,
    catalog: CostCatalogDefinition | None,
) -> CostReport:
    if catalog is None:
        return CostReport(
            "unavailable",
            None,
            None,
            (),
            tuple(item.key for item in quantities.items),
            None,
            None,
            None,
            None,
        )
    lines: list[CostLine] = []
    unpriced: list[str] = []
    for item in quantities.items:
        if item.quantity is None:
            unpriced.append(item.key)
            continue
        matches = [
            rate
            for rate in catalog.items
            if rate.per == item.per
            and rate.quantity_unit == item.unit
            and rate.applies_to_method in {"all", item.construction_method}
        ]
        if not matches:
            unpriced.append(item.key)
            continue
        for rate in matches:
            subtotal = (item.quantity * rate.rate).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP)
            lines.append(
                CostLine(
                    rate.code,
                    rate.description,
                    item.key,
                    item.quantity,
                    item.unit,
                    rate.rate,
                    subtotal,
                    rate.source_reference,
                )
            )
    total = sum((line.subtotal for line in lines), Decimal(0)).quantize(
        MONEY_QUANTUM, rounding=ROUND_HALF_UP
    )
    return CostReport(
        "partial" if unpriced else "complete",
        catalog.currency,
        total,
        tuple(lines),
        tuple(unpriced),
        catalog.id,
        catalog.version,
        catalog.estimate_status,
        catalog.price_date,
    )


def compare_costs(
    baseline: CostReport,
    alternative: CostReport,
    *,
    same_model: bool,
) -> CostComparison:
    if (
        not same_model
        or baseline.catalog_id != alternative.catalog_id
        or baseline.catalog_version != alternative.catalog_version
        or baseline.status != "complete"
        or alternative.status != "complete"
        or baseline.total is None
        or alternative.total is None
    ):
        return CostComparison(
            False,
            None,
            None,
            "Cost estimates are not comparable under the same complete model.",
        )
    delta = (baseline.total - alternative.total).quantize(MONEY_QUANTUM, rounding=ROUND_HALF_UP)
    percentage = None
    if baseline.total > 0:
        percentage = (delta / baseline.total * Decimal(100)).quantize(
            MONEY_QUANTUM, rounding=ROUND_HALF_UP
        )
    return CostComparison(
        True,
        delta,
        percentage,
        "Preliminary estimate difference; not a construction savings guarantee.",
    )


def _construction_segments(
    centerline: LineString,
    *,
    default_method: str,
    portals: tuple[CrossingPortal, ...],
    crossing_events: tuple[CrossingEvent, ...],
) -> tuple[RouteSegment, ...]:
    active_portal_ids = {event.portal_id for event in crossing_events}
    special: list[tuple[float, float, str, str]] = []
    for portal in portals:
        if portal.id not in active_portal_ids:
            continue
        intersection = centerline.intersection(portal.footprint)
        for part in _line_parts(intersection):
            start = centerline.project(part.interpolate(0))
            end = centerline.project(part.interpolate(part.length))
            if end < start:
                start, end = end, start
            if end - start > 1e-9:
                special.append((start, end, portal.method, portal.id))
    special.sort()
    for left, right in zip(special, special[1:], strict=False):
        if right[0] < left[1] - 1e-6:
            raise ValueError("crossing portal construction intervals overlap")
    intervals: list[tuple[float, float, str, str]] = []
    cursor = 0.0
    for start, end, method, portal_id in special:
        if start > cursor + 1e-9:
            intervals.append((cursor, start, default_method, "route"))
        intervals.append((start, end, method, portal_id))
        cursor = end
    if cursor < centerline.length - 1e-9:
        intervals.append((cursor, centerline.length, default_method, "route"))
    if not intervals:
        intervals.append((0.0, centerline.length, default_method, "route"))
    segments: list[RouteSegment] = []
    for index, (start, end, method, source_id) in enumerate(intervals, start=1):
        geometry = substring(centerline, start, end)
        if not isinstance(geometry, LineString):
            raise ValueError("route segment must be a LineString")
        segments.append(
            RouteSegment(
                id=f"segment-{index}:{source_id}",
                start_chainage_m=_quantity(start),
                end_chainage_m=_quantity(end),
                construction_method=method,
                geometry=geometry,
            )
        )
    return tuple(segments)


def _line_parts(geometry: BaseGeometry) -> tuple[LineString, ...]:
    if geometry.is_empty:
        return ()
    if isinstance(geometry, LineString):
        return (geometry,)
    return tuple(
        part
        for part in getattr(geometry, "geoms", ())
        if isinstance(part, LineString) and part.length > 0
    )


def _bend_count(centerline: LineString) -> int:
    coords = tuple(centerline.coords)
    count = 0
    previous: tuple[float, float] | None = None
    for left, right in zip(coords, coords[1:], strict=False):
        direction = (right[0] - left[0], right[1] - left[1])
        length = (direction[0] ** 2 + direction[1] ** 2) ** 0.5
        if length <= 1e-12:
            continue
        normalized = (round(direction[0] / length, 9), round(direction[1] / length, 9))
        if previous is not None and normalized != previous:
            count += 1
        previous = normalized
    return count


def _quantity(value: float) -> Decimal:
    return Decimal(str(value)).quantize(QUANTITY_QUANTUM, rounding=ROUND_HALF_UP)


def _non_negative_decimal(value: object, field: str) -> Decimal:
    try:
        result = Decimal(str(value))
    except Exception as error:
        raise ValueError(f"{field} must be a decimal string") from error
    if not result.is_finite() or result < 0:
        raise ValueError(f"{field} must be a finite non-negative decimal")
    return result


def _optional_decimal(value: object, field: str) -> Decimal | None:
    return None if value is None else _non_negative_decimal(value, field)
