from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from heatroute.models import CanonicalFeature, DatasetLayerVersion, DatasetVersion


@dataclass(frozen=True)
class VersionDiff:
    previous_version_id: UUID | None
    added: list[str]
    changed: list[str]
    deleted: list[str]
    unchanged: int
    unmapped_layers: list[str]


def _semantic_hash(feature: CanonicalFeature, geometry_wkb: bytes) -> str:
    payload = json.dumps(
        {
            "kind": feature.kind,
            "lifecycle_status": feature.lifecycle_status,
            "quality_flags": feature.quality_flags,
            "attributes": feature.attributes,
            "geometry_wkb": bytes(geometry_wkb).hex(),
        },
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _feature_hashes(session: Session, version_id: UUID) -> dict[str, str]:
    rows = session.execute(
        select(CanonicalFeature, func.ST_AsEWKB(CanonicalFeature.geometry_wgs84)).where(
            CanonicalFeature.dataset_version_id == version_id
        )
    ).all()
    return {
        str(feature.logical_id): _semantic_hash(feature, geometry_wkb)
        for feature, geometry_wkb in rows
    }


def compare_dataset_version(session: Session, version: DatasetVersion) -> VersionDiff:
    previous = session.scalar(
        select(DatasetVersion)
        .where(
            DatasetVersion.dataset_id == version.dataset_id,
            DatasetVersion.version < version.version,
            DatasetVersion.status == "published",
        )
        .order_by(DatasetVersion.version.desc())
        .limit(1)
    )
    current_hashes = _feature_hashes(session, version.id)
    previous_hashes = {} if previous is None else _feature_hashes(session, previous.id)
    current_ids = set(current_hashes)
    previous_ids = set(previous_hashes)
    common_ids = current_ids & previous_ids
    unmapped_layers = list(
        session.scalars(
            select(DatasetLayerVersion.name)
            .where(
                DatasetLayerVersion.dataset_version_id == version.id,
                DatasetLayerVersion.status == "not_selected",
            )
            .order_by(DatasetLayerVersion.ordinal)
        ).all()
    )
    return VersionDiff(
        previous_version_id=None if previous is None else previous.id,
        added=sorted(current_ids - previous_ids),
        changed=sorted(
            logical_id
            for logical_id in common_ids
            if current_hashes[logical_id] != previous_hashes[logical_id]
        ),
        deleted=sorted(previous_ids - current_ids),
        unchanged=sum(
            current_hashes[logical_id] == previous_hashes[logical_id]
            for logical_id in common_ids
        ),
        unmapped_layers=unmapped_layers,
    )


def project_coverage_quality(
    session: Session, *, project_id: UUID, workspace_id: UUID
) -> tuple[str, list[dict[str, Any]]]:
    latest_versions = (
        select(
            DatasetVersion.dataset_id.label("dataset_id"),
            func.max(DatasetVersion.version).label("version"),
        )
        .where(DatasetVersion.status == "published")
        .group_by(DatasetVersion.dataset_id)
        .subquery()
    )
    rows = session.execute(
        select(
            CanonicalFeature.kind,
            CanonicalFeature.attributes,
            DatasetVersion.publication_policy,
        )
        .join(DatasetVersion, DatasetVersion.id == CanonicalFeature.dataset_version_id)
        .join(
            latest_versions,
            (latest_versions.c.dataset_id == DatasetVersion.dataset_id)
            & (latest_versions.c.version == DatasetVersion.version),
        )
        .where(
            CanonicalFeature.project_id == project_id,
            CanonicalFeature.workspace_id == workspace_id,
            DatasetVersion.status == "published",
        )
    ).all()
    data_kinds = {
        str(kind) for kind, _attributes, _policy in rows if kind != "coverage_area"
    }
    coverage_rows = [
        attributes for kind, attributes, _policy in rows if kind == "coverage_area"
    ]
    covered_kinds: set[str] = set()
    states: list[str] = []
    for attributes in coverage_rows:
        covered_kinds.update(str(kind) for kind in attributes.get("covered_kinds", []))
        states.append(str(attributes.get("completeness", "unknown")))
    findings: list[dict[str, Any]] = []
    limitations = {
        str(policy["coverage_limitations"])
        for _kind, _attributes, policy in rows
        if policy and policy.get("coverage_limitations")
    }
    for limitation in sorted(limitations):
        findings.append(
            {
                "code": "PUBLISHED_WITH_LIMITATIONS",
                "severity": "warning",
                "message": limitation,
            }
        )
    for kind in sorted(data_kinds - covered_kinds):
        findings.append(
            {
                "code": "COVERAGE_UNKNOWN",
                "severity": "warning",
                "kind": kind,
                "message": "No published coverage assertion exists for this feature kind.",
            }
        )
    if not coverage_rows or findings or "unknown" in states:
        coverage_state = "unknown"
    elif "partial" in states:
        coverage_state = "partial"
    elif states and all(state == "synthetic" for state in states):
        coverage_state = "synthetic"
    else:
        coverage_state = "known_complete"
    return coverage_state, findings
