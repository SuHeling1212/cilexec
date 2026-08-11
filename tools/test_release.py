#!/usr/bin/env python3

import contextlib
import io
import tempfile
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


if __name__ == "__main__":
    unittest.main()
