from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any, Literal

RULE_TYPES = frozenset(
    {
        "hard_exclusion",
        "clearance",
        "coverage_required",
        "crossing_allowed_only_via_portal",
        "candidate_eligibility",
        "temporal_availability",
        "soft_exposure",
    }
)
_PLACEHOLDER = re.compile(r"\{([^{}]+)\}")
ALLOWED_MESSAGE_PLACEHOLDERS = frozenset({"feature_id", "rule_id", "measured", "limit", "unit"})


@dataclass(frozen=True)
class RuleDefinition:
    id: str
    type: str
    applies_to_kind: str
    parameters: dict[str, Any]
    severity: Literal["blocker", "warning", "info"]
    missing_policy: Literal["block", "requires_review", "allow_with_assumption"]
    source_reference: str
    message_template: str | None = None

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> RuleDefinition:
        rule_type = str(value.get("type", ""))
        if rule_type not in RULE_TYPES:
            raise ValueError(f"unsupported rule type: {rule_type!r}")
        parameters = value.get("parameters", {})
        if not isinstance(parameters, dict):
            raise ValueError("rule parameters must be an object")
        clearance = parameters.get("clearance_m")
        if clearance is not None and (
            isinstance(clearance, bool) or not isinstance(clearance, (int, float)) or clearance < 0
        ):
            raise ValueError("clearance_m must be a non-negative number")
        template = value.get("message_template")
        if template is not None:
            if not isinstance(template, str) or len(template) > 1_000:
                raise ValueError("message_template must be a bounded string")
            unknown = set(_PLACEHOLDER.findall(template)) - ALLOWED_MESSAGE_PLACEHOLDERS
            if unknown:
                raise ValueError(f"unsupported message placeholders: {sorted(unknown)}")
        severity = str(value.get("severity", ""))
        if severity not in {"blocker", "warning", "info"}:
            raise ValueError("invalid rule severity")
        missing_policy = str(value.get("missing_policy", ""))
        if missing_policy not in {"block", "requires_review", "allow_with_assumption"}:
            raise ValueError("invalid rule missing_policy")
        return cls(
            id=str(value["id"]),
            type=rule_type,
            applies_to_kind=str(value["applies_to_kind"]),
            parameters=dict(parameters),
            severity=severity,  # type: ignore[arg-type]
            missing_policy=missing_policy,  # type: ignore[arg-type]
            source_reference=str(value["source_reference"]),
            message_template=template,
        )


@dataclass(frozen=True)
class RuleProfileVersion:
    id: str
    version: int
    name: str
    status: Literal["demo", "draft", "reviewed"]
    rules: tuple[RuleDefinition, ...]
    missing_data_policy: dict[str, Any]
    geometry_tolerances: dict[str, Any]
    source_references: tuple[str, ...]
    schema_version: str

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> RuleProfileVersion:
        status = str(value.get("status", ""))
        if status not in {"demo", "draft", "reviewed"}:
            raise ValueError("invalid rule profile status")
        version = int(value.get("version", 0))
        if version <= 0:
            raise ValueError("rule profile version must be positive")
        raw_rules = value.get("rules")
        if not isinstance(raw_rules, list):
            raise ValueError("rules must be a list")
        rules = tuple(RuleDefinition.from_dict(rule) for rule in raw_rules)
        ids = [rule.id for rule in rules]
        if len(set(ids)) != len(ids):
            raise ValueError("rule IDs must be unique inside a profile version")
        tolerances = value.get("geometry_tolerances", {})
        if not isinstance(tolerances, dict):
            raise ValueError("geometry_tolerances must be an object")
        precision = tolerances.get("precision_m", 0.01)
        if isinstance(precision, bool) or not isinstance(precision, int | float) or precision < 0:
            raise ValueError("precision_m must be a non-negative number")
        touch_policy = tolerances.get("touch_policy", "forbid_except_named_entry_contact")
        if touch_policy not in {
            "forbid",
            "forbid_except_named_entry_contact",
            "allow_boundary_touch",
        }:
            raise ValueError("invalid touch_policy")
        return cls(
            id=str(value["id"]),
            version=version,
            name=str(value["name"]),
            status=status,  # type: ignore[arg-type]
            rules=rules,
            missing_data_policy=dict(value.get("missing_data_policy", {})),
            geometry_tolerances=dict(tolerances),
            source_references=tuple(str(item) for item in value.get("source_references", [])),
            schema_version=str(value.get("schema_version", "1.0")),
        )


RuleEvaluator = Callable[[RuleDefinition, Any], list[Any]]


class RuleEvaluatorRegistry:
    def __init__(self) -> None:
        self._evaluators: dict[str, RuleEvaluator] = {}

    def register(self, rule_type: str, evaluator: RuleEvaluator) -> None:
        if rule_type not in RULE_TYPES:
            raise ValueError(f"cannot register unknown rule type {rule_type!r}")
        if rule_type in self._evaluators:
            raise ValueError(f"evaluator is already registered for {rule_type!r}")
        self._evaluators[rule_type] = evaluator

    def evaluate(self, rule: RuleDefinition, context: Any) -> list[Any]:
        evaluator = self._evaluators.get(rule.type)
        if evaluator is None:
            raise ValueError(f"no evaluator registered for rule type {rule.type!r}")
        return evaluator(rule, context)

    def assert_profile_supported(self, profile: RuleProfileVersion) -> None:
        missing = sorted({rule.type for rule in profile.rules} - set(self._evaluators))
        if missing:
            raise ValueError(f"profile contains rules without evaluators: {missing}")
