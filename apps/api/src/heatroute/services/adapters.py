from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol


@dataclass(frozen=True)
class RawArtifactRef:
    path: Path
    sha256: str
    declared_format: str


@dataclass(frozen=True)
class AdapterFeature:
    source_row: int
    source_layer: str
    properties: dict[str, Any]
    geometry_wkb: bytes | None


@dataclass(frozen=True)
class CanonicalFeatureBatch:
    features: list[AdapterFeature]


class DatasetAdapter(Protocol):
    name: str
    version: str

    def inspect(self, source: RawArtifactRef) -> dict[str, Any]: ...

    def read_batches(
        self,
        source: RawArtifactRef,
        mapping: dict[str, Any],
        batch_size: int,
    ) -> Iterator[CanonicalFeatureBatch]: ...
