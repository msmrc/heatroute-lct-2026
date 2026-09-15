from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.domain.rules import RuleProfileVersion as DomainRuleProfileVersion
from heatroute.models import Project, RuleProfile, RuleProfileVersion


class RuleProfileValidationError(ValueError):
    pass


class StaleRuleProfileRevisionError(ValueError):
    def __init__(self, *, expected: int, received: int) -> None:
        super().__init__("stale rule profile revision")
        self.expected = expected
        self.received = received


@dataclass(frozen=True)
class RuleProfileRecord:
    profile: RuleProfile
    versions: tuple[RuleProfileVersion, ...]


def definition_hash(definition: dict[str, Any]) -> str:
    payload = json.dumps(
        definition,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def validate_definition(
    definition: dict[str, Any],
    *,
    expected_revision: int,
    expected_name: str,
    expected_domain_id: str | None = None,
) -> DomainRuleProfileVersion:
    try:
        parsed = DomainRuleProfileVersion.from_dict(definition)
    except (KeyError, TypeError, ValueError) as error:
        raise RuleProfileValidationError(str(error)) from error
    if parsed.version != expected_revision:
        raise RuleProfileValidationError(
            f"definition version must be {expected_revision}, received {parsed.version}"
        )
    if parsed.name != expected_name:
        raise RuleProfileValidationError("definition name must match the profile name")
    if expected_domain_id is not None and parsed.id != expected_domain_id:
        raise RuleProfileValidationError("definition id cannot change between revisions")
    return parsed


def create_rule_profile(
    session: Session,
    *,
    project: Project,
    name: str,
    definition: dict[str, Any],
) -> RuleProfileRecord:
    parsed = validate_definition(
        definition,
        expected_revision=1,
        expected_name=name,
    )
    profile = RuleProfile(
        workspace_id=project.workspace_id,
        project_id=project.id,
        name=name,
        current_revision=1,
    )
    session.add(profile)
    session.flush()
    version = RuleProfileVersion(
        profile_id=profile.id,
        revision=1,
        definition_hash=definition_hash(definition),
        status=parsed.status,
        definition=definition,
    )
    session.add(version)
    session.commit()
    session.refresh(profile)
    session.refresh(version)
    return RuleProfileRecord(profile=profile, versions=(version,))


def revise_rule_profile(
    session: Session,
    *,
    profile_id: UUID,
    workspace_id: UUID,
    expected_revision: int,
    definition: dict[str, Any],
) -> RuleProfileRecord | None:
    profile = session.scalar(
        select(RuleProfile)
        .where(
            RuleProfile.id == profile_id,
            RuleProfile.workspace_id == workspace_id,
        )
        .with_for_update()
    )
    if profile is None:
        return None
    if profile.current_revision != expected_revision:
        raise StaleRuleProfileRevisionError(
            expected=profile.current_revision,
            received=expected_revision,
        )
    first = session.scalar(
        select(RuleProfileVersion).where(
            RuleProfileVersion.profile_id == profile.id,
            RuleProfileVersion.revision == 1,
        )
    )
    if first is None:
        raise RuntimeError("rule profile has no initial version")
    next_revision = profile.current_revision + 1
    parsed = validate_definition(
        definition,
        expected_revision=next_revision,
        expected_name=profile.name,
        expected_domain_id=str(first.definition["id"]),
    )
    version = RuleProfileVersion(
        profile_id=profile.id,
        revision=next_revision,
        definition_hash=definition_hash(definition),
        status=parsed.status,
        definition=definition,
    )
    profile.current_revision = next_revision
    session.add(version)
    session.commit()
    session.refresh(profile)
    session.refresh(version)
    versions = tuple(
        session.scalars(
            select(RuleProfileVersion)
            .where(RuleProfileVersion.profile_id == profile.id)
            .order_by(RuleProfileVersion.revision)
        ).all()
    )
    return RuleProfileRecord(profile=profile, versions=versions)


def get_rule_profile(
    session: Session, *, profile_id: UUID, workspace_id: UUID
) -> RuleProfileRecord | None:
    profile = session.scalar(
        select(RuleProfile).where(
            RuleProfile.id == profile_id,
            RuleProfile.workspace_id == workspace_id,
        )
    )
    if profile is None:
        return None
    versions = tuple(
        session.scalars(
            select(RuleProfileVersion)
            .where(RuleProfileVersion.profile_id == profile.id)
            .order_by(RuleProfileVersion.revision)
        ).all()
    )
    return RuleProfileRecord(profile=profile, versions=versions)


def list_rule_profiles(
    session: Session, *, project_id: UUID, workspace_id: UUID
) -> tuple[RuleProfileRecord, ...]:
    profiles = session.scalars(
        select(RuleProfile)
        .where(
            RuleProfile.project_id == project_id,
            RuleProfile.workspace_id == workspace_id,
        )
        .order_by(RuleProfile.created_at, RuleProfile.id)
    ).all()
    if not profiles:
        return ()
    versions = session.scalars(
        select(RuleProfileVersion)
        .where(RuleProfileVersion.profile_id.in_([profile.id for profile in profiles]))
        .order_by(RuleProfileVersion.profile_id, RuleProfileVersion.revision)
    ).all()
    grouped: dict[UUID, list[RuleProfileVersion]] = {}
    for version in versions:
        grouped.setdefault(version.profile_id, []).append(version)
    return tuple(
        RuleProfileRecord(profile=profile, versions=tuple(grouped.get(profile.id, [])))
        for profile in profiles
    )
