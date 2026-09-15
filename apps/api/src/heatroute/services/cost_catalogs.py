from __future__ import annotations

from dataclasses import dataclass
from typing import Any
from uuid import UUID

from sqlalchemy import select
from sqlalchemy.orm import Session

from heatroute.domain.costing import CostCatalogDefinition
from heatroute.models import CostCatalog, CostCatalogVersion, Project
from heatroute.services.rule_profiles import definition_hash


class CostCatalogValidationError(ValueError):
    pass


class StaleCostCatalogRevisionError(ValueError):
    def __init__(self, *, expected: int, received: int) -> None:
        super().__init__("stale cost catalog revision")
        self.expected = expected
        self.received = received


@dataclass(frozen=True)
class CostCatalogRecord:
    catalog: CostCatalog
    versions: tuple[CostCatalogVersion, ...]


def validate_catalog_definition(
    definition: dict[str, Any],
    *,
    expected_revision: int,
    expected_domain_id: str | None = None,
) -> CostCatalogDefinition:
    try:
        parsed = CostCatalogDefinition.from_dict(definition)
    except (KeyError, TypeError, ValueError) as error:
        raise CostCatalogValidationError(str(error)) from error
    if parsed.version != expected_revision:
        raise CostCatalogValidationError(
            f"definition version must be {expected_revision}, received {parsed.version}"
        )
    if expected_domain_id is not None and parsed.id != expected_domain_id:
        raise CostCatalogValidationError("definition id cannot change between revisions")
    if parsed.estimate_status not in {"synthetic", "draft", "reviewed"}:
        raise CostCatalogValidationError("invalid estimate_status")
    return parsed


def create_cost_catalog(
    session: Session,
    *,
    project: Project,
    name: str,
    definition: dict[str, Any],
) -> CostCatalogRecord:
    parsed = validate_catalog_definition(definition, expected_revision=1)
    catalog = CostCatalog(
        workspace_id=project.workspace_id,
        project_id=project.id,
        name=name,
        current_revision=1,
    )
    session.add(catalog)
    session.flush()
    version = CostCatalogVersion(
        catalog_id=catalog.id,
        revision=1,
        definition_hash=definition_hash(definition),
        status=parsed.estimate_status,
        definition=definition,
    )
    session.add(version)
    session.commit()
    session.refresh(catalog)
    session.refresh(version)
    return CostCatalogRecord(catalog, (version,))


def revise_cost_catalog(
    session: Session,
    *,
    catalog_id: UUID,
    workspace_id: UUID,
    expected_revision: int,
    definition: dict[str, Any],
) -> CostCatalogRecord | None:
    catalog = session.scalar(
        select(CostCatalog)
        .where(
            CostCatalog.id == catalog_id,
            CostCatalog.workspace_id == workspace_id,
        )
        .with_for_update()
    )
    if catalog is None:
        return None
    if catalog.current_revision != expected_revision:
        raise StaleCostCatalogRevisionError(
            expected=catalog.current_revision, received=expected_revision
        )
    first = session.scalar(
        select(CostCatalogVersion).where(
            CostCatalogVersion.catalog_id == catalog.id,
            CostCatalogVersion.revision == 1,
        )
    )
    if first is None:
        raise RuntimeError("cost catalog has no initial version")
    next_revision = catalog.current_revision + 1
    parsed = validate_catalog_definition(
        definition,
        expected_revision=next_revision,
        expected_domain_id=str(first.definition["id"]),
    )
    version = CostCatalogVersion(
        catalog_id=catalog.id,
        revision=next_revision,
        definition_hash=definition_hash(definition),
        status=parsed.estimate_status,
        definition=definition,
    )
    catalog.current_revision = next_revision
    session.add(version)
    session.commit()
    return get_cost_catalog(session, catalog_id=catalog.id, workspace_id=workspace_id)


def get_cost_catalog(
    session: Session, *, catalog_id: UUID, workspace_id: UUID
) -> CostCatalogRecord | None:
    catalog = session.scalar(
        select(CostCatalog).where(
            CostCatalog.id == catalog_id,
            CostCatalog.workspace_id == workspace_id,
        )
    )
    if catalog is None:
        return None
    versions = tuple(
        session.scalars(
            select(CostCatalogVersion)
            .where(CostCatalogVersion.catalog_id == catalog.id)
            .order_by(CostCatalogVersion.revision)
        ).all()
    )
    return CostCatalogRecord(catalog, versions)


def list_cost_catalogs(
    session: Session, *, project_id: UUID, workspace_id: UUID
) -> tuple[CostCatalogRecord, ...]:
    catalogs = session.scalars(
        select(CostCatalog)
        .where(
            CostCatalog.project_id == project_id,
            CostCatalog.workspace_id == workspace_id,
        )
        .order_by(CostCatalog.created_at, CostCatalog.id)
    ).all()
    return tuple(
        record
        for catalog in catalogs
        if (
            record := get_cost_catalog(
                session, catalog_id=catalog.id, workspace_id=workspace_id
            )
        )
        is not None
    )
