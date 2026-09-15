from io import BytesIO

import pytest

from heatroute.models import DatasetVersion
from heatroute.services.artifacts import (
    EmptyArtifactError,
    LocalArtifactStorage,
    UploadTooLargeError,
    safe_display_filename,
)
from heatroute.services.ingestion import (
    InvalidDatasetTransitionError,
    transition_dataset_version,
)


def test_local_artifact_storage_is_content_addressed_and_deduplicated(tmp_path) -> None:  # type: ignore[no-untyped-def]
    storage = LocalArtifactStorage(tmp_path, max_upload_bytes=1024)

    first = storage.store(BytesIO(b'{"type":"FeatureCollection","features":[]}'))
    second = storage.store(BytesIO(b'{"type":"FeatureCollection","features":[]}'))

    assert first.sha256 == second.sha256
    assert first.storage_key == f"sha256/{first.sha256[:2]}/{first.sha256}"
    assert first.created is True
    assert second.created is False
    assert (tmp_path / first.storage_key).read_bytes().startswith(b'{"type"')


def test_local_artifact_storage_rejects_oversize_and_cleans_temporary_file(
    tmp_path,  # type: ignore[no-untyped-def]
) -> None:
    storage = LocalArtifactStorage(tmp_path, max_upload_bytes=4)

    with pytest.raises(UploadTooLargeError):
        storage.store(BytesIO(b"12345"))

    assert not list((tmp_path / ".tmp").iterdir())


def test_local_artifact_storage_rejects_empty_artifact(tmp_path) -> None:  # type: ignore[no-untyped-def]
    storage = LocalArtifactStorage(tmp_path, max_upload_bytes=4)

    with pytest.raises(EmptyArtifactError):
        storage.store(BytesIO(b""))

    assert not list((tmp_path / ".tmp").iterdir())


def test_original_filename_is_reduced_to_safe_display_metadata() -> None:
    assert safe_display_filename(r"..\..\district.geojson") == "district.geojson"
    assert safe_display_filename("../../roads.csv") == "roads.csv"


def test_dataset_version_state_machine_disallows_skipped_stages() -> None:
    version = DatasetVersion(status="uploaded")

    transition_dataset_version(version, "inspected")
    assert version.status == "inspected"
    with pytest.raises(InvalidDatasetTransitionError):
        transition_dataset_version(version, "published")
