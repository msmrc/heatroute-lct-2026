from __future__ import annotations

import hashlib
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import BinaryIO


class UploadTooLargeError(ValueError):
    def __init__(self, max_bytes: int) -> None:
        super().__init__(f"upload exceeds the {max_bytes}-byte limit")
        self.max_bytes = max_bytes


class EmptyArtifactError(ValueError):
    pass


@dataclass(frozen=True)
class StoredArtifact:
    sha256: str
    size_bytes: int
    storage_key: str
    created: bool


def safe_display_filename(filename: str | None) -> str:
    normalized = (filename or "").replace("\\", "/")
    leaf = PurePosixPath(normalized).name
    printable = "".join(character for character in leaf if character.isprintable()).strip()
    return printable[:255] or "upload"


class LocalArtifactStorage:
    def __init__(self, root: Path, *, max_upload_bytes: int) -> None:
        self.root = root.resolve()
        self.max_upload_bytes = max_upload_bytes

    def store(self, stream: BinaryIO) -> StoredArtifact:
        temporary_root = self.root / ".tmp"
        temporary_root.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(prefix="upload-", dir=temporary_root)
        temporary_path = Path(temporary_name)
        digest = hashlib.sha256()
        size_bytes = 0
        try:
            with os.fdopen(descriptor, "wb") as destination:
                while chunk := stream.read(1024 * 1024):
                    size_bytes += len(chunk)
                    if size_bytes > self.max_upload_bytes:
                        raise UploadTooLargeError(self.max_upload_bytes)
                    digest.update(chunk)
                    destination.write(chunk)
                destination.flush()
                os.fsync(destination.fileno())

            if size_bytes == 0:
                raise EmptyArtifactError("artifact must not be empty")

            sha256 = digest.hexdigest()
            storage_key = f"sha256/{sha256[:2]}/{sha256}"
            final_path = self.root / Path(storage_key)
            final_path.parent.mkdir(parents=True, exist_ok=True)
            if final_path.exists():
                temporary_path.unlink()
                created = False
            else:
                os.replace(temporary_path, final_path)
                created = True
            return StoredArtifact(
                sha256=sha256,
                size_bytes=size_bytes,
                storage_key=storage_key,
                created=created,
            )
        except BaseException:
            temporary_path.unlink(missing_ok=True)
            raise
