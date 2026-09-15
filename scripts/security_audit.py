from __future__ import annotations

import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_PARTS = {
    ".git",
    ".mypy_cache",
    ".playwright-cli",
    ".pytest_cache",
    ".ruff_cache",
    ".venv",
    "artifacts",
    "dist",
    "node_modules",
    "output",
}
SKIP_NAMES = {"pnpm-lock.yaml", "uv.lock"}
TEXT_SUFFIXES = {
    ".env",
    ".html",
    ".ini",
    ".js",
    ".json",
    ".md",
    ".ps1",
    ".py",
    ".sql",
    ".toml",
    ".ts",
    ".tsx",
    ".txt",
    ".yaml",
    ".yml",
}
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "GitHub token": re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b"),
    "OpenAI token": re.compile(r"\bsk-[A-Za-z0-9_-]{24,}\b"),
}


def candidate_files() -> list[Path]:
    files: list[Path] = []
    for directory, children, names in os.walk(ROOT):
        children[:] = [child for child in children if child not in SKIP_PARTS]
        parent = Path(directory)
        for name in names:
            path = parent / name
            if name in SKIP_NAMES:
                continue
            if path.suffix.casefold() in TEXT_SUFFIXES or name.startswith(".env"):
                files.append(path)
    return files


def main() -> int:
    findings: list[str] = []
    committed_env = ROOT / ".env"
    if committed_env.exists():
        findings.append(".env exists at repository root; use .env.example only")

    for path in candidate_files():
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        relative = path.relative_to(ROOT)
        for label, pattern in PATTERNS.items():
            if pattern.search(content):
                findings.append(f"{relative}: possible {label}")

    if findings:
        print("Security audit failed:")
        for finding in findings:
            print(f"- {finding}")
        return 1

    print(f"Security audit passed: scanned {len(candidate_files())} repository text files.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
