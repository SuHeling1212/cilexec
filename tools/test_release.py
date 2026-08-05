#!/usr/bin/env python3

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import release


GENERATED = ("cilexec-app.jar", "cilexec-market-server.jar", "catalog.json",
             "repository", release.SHA256_NAME)


def write_set(root: Path, prefix: str, omit: str | None = None) -> None:
    for name in GENERATED:
        if name == omit:
            continue
        path = root / name
        if name == "repository":
            path.mkdir()
            (path / "value").write_text(prefix, encoding="utf-8")
        else:
            path.write_text(prefix, encoding="utf-8")


def read_set(root: Path) -> dict[str, str]:
    result = {}
    for name in GENERATED:
        path = root / name
        result[name] = (path / "value").read_text(encoding="utf-8") \
            if name == "repository" else path.read_text(encoding="utf-8")
    return result


class ReleasePublishTest(unittest.TestCase):
    def test_publish_replaces_complete_generated_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            distribution = root / "dist"
            staging = root / "staging"
            distribution.mkdir()
            staging.mkdir()
            write_set(distribution, "old")
            write_set(staging, "new")
            with patch.object(release, "DIST", distribution):
                release.publish(staging)
            self.assertEqual(set(read_set(distribution).values()), {"new"})
            self.assertFalse(list(distribution.glob(".release-backup-*")))

    def test_publish_restores_every_old_artifact_after_partial_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            distribution = root / "dist"
            staging = root / "staging"
            distribution.mkdir()
            staging.mkdir()
            write_set(distribution, "old")
            write_set(staging, "new", omit=release.SHA256_NAME)
            with patch.object(release, "DIST", distribution):
                with self.assertRaises(FileNotFoundError):
                    release.publish(staging)
            self.assertEqual(set(read_set(distribution).values()), {"old"})
            self.assertFalse(list(distribution.glob(".release-backup-*")))


if __name__ == "__main__":
    unittest.main()
