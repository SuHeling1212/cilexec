#!/usr/bin/env python3

import contextlib
import io
import json
import sqlite3
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch

import release


GENERATED = release.PUBLISHED_NAMES


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


class FormalReleaseValidationTest(unittest.TestCase):
    REVISION = "a" * 40

    def test_accepts_matching_clean_formal_release(self) -> None:
        release.validate_formal_release("1.2.3", self.REVISION, "v1.2.3", False,
                                        self.REVISION, self.REVISION)

    def test_rejects_dirty_release(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "clean Git"):
            release.validate_formal_release("1.2.3", self.REVISION, "v1.2.3", True)

    def test_rejects_snapshot_release(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "not releasable"):
            release.validate_formal_release("1.2-SNAPSHOT", self.REVISION,
                                            "v1.2-SNAPSHOT", False)

    def test_rejects_unknown_revision(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "full Git commit"):
            release.validate_formal_release("1.2.3", "unknown", "v1.2.3", False)

    def test_rejects_mismatched_tag(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "must be v1.2.3"):
            release.validate_formal_release("1.2.3", self.REVISION, "v1.2.4", False)

    def test_rejects_tag_not_pointing_at_head(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "does not point at HEAD"):
            release.validate_formal_release("1.2.3", self.REVISION, "v1.2.3", False,
                                             self.REVISION, "b" * 40)

    def test_rejects_skipped_tests_for_formal_release(self) -> None:
        with patch("sys.argv", ["release.py", "--formal", "--tag", "v1.2.3",
                                "--skip-tests"]):
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    release.parse_arguments()


class ReleaseIntegritySetTest(unittest.TestCase):
    def test_checksums_cover_documents_sbom_manifest_and_packages(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in release.REQUIRED_PAYLOAD_NAMES:
                (root / name).write_text(name, encoding="utf-8")
            package = root / "repository/packages/example/tool/1.0/tool.db"
            package.parent.mkdir(parents=True)
            package.write_bytes(b"package")
            (root / release.MANIFEST_NAME).write_text("{}", encoding="utf-8")

            release.write_checksums(root)

            declared = {line[66:] for line in
                        (root / release.SHA256_NAME).read_text(encoding="ascii").splitlines()}
            self.assertEqual(declared, set(release.expected_files(root)))


class PackageBuildTest(unittest.TestCase):
    def test_build_uses_current_sources_instead_of_old_release_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            distribution = root / "dist"
            staging = root / "staging"
            source = distribution / "editor"
            old_package = distribution / "repository/packages/example/old/1.0/old.db"
            source.mkdir(parents=True)
            staging.mkdir()
            old_package.parent.mkdir(parents=True)
            old_package.write_bytes(b"old")
            (distribution / "catalog.json").write_text(
                json.dumps({"example/old/1.0": {}}), encoding="utf-8")
            (source / "package.json").write_text(json.dumps({
                "namespace": "example", "name": "editor", "version": "2.0",
            }), encoding="utf-8")
            (source / "market.json").write_text(
                json.dumps({"summary": "Current editor"}), encoding="utf-8")

            package_builder = types.ModuleType("tools.PackageBuild")

            def build_package(_source: Path, output: Path) -> tuple[str, str]:
                with sqlite3.connect(output) as connection:
                    connection.execute("PRAGMA user_version = 2")
                    connection.execute(
                        "CREATE TABLE package_metadata(metadata_key TEXT, metadata_value TEXT)")
                    connection.executemany(
                        "INSERT INTO package_metadata VALUES (?, ?)",
                        (("namespace", "example"), ("name", "editor"), ("version", "2.0")),
                    )
                return "example/editor/2.0", release.sha256(output)

            package_builder.build = build_package
            with patch.object(release, "DIST", distribution), \
                    patch.dict(sys.modules, {"tools.PackageBuild": package_builder}):
                catalog = release.build_packages(staging)

            self.assertEqual(catalog, {"example/editor/2.0": {"summary": "Current editor"}})
            self.assertFalse((staging / "repository/packages/example/old").exists())


if __name__ == "__main__":
    unittest.main()
