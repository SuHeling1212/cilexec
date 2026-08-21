"""Read the one canonical CilExec release version.

The value lives in .mvn/maven.config so Maven, both Java applications, and release tooling use
the same input. The 0.0.N release convention maps N to the active Flyway/runtime format number.
"""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
_PATTERN = re.compile(r"^-Drevision=(0\.0\.[1-9][0-9]*)$")


def project_version(root: Path = ROOT) -> str:
    config = root / ".mvn" / "maven.config"
    try:
        matches = [match.group(1) for line in config.read_text(encoding="utf-8").splitlines()
                   if (match := _PATTERN.fullmatch(line.strip()))]
    except OSError as error:
        raise RuntimeError(f"Cannot read canonical release version {config}: {error}") from error
    if len(matches) != 1:
        raise RuntimeError(
            f"{config} must contain exactly one -Drevision=0.0.N release version")
    return matches[0]


def schema_version(root: Path = ROOT) -> int:
    return int(project_version(root).rsplit(".", 1)[1])
