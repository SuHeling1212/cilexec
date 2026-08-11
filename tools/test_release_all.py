#!/usr/bin/env python3

import hashlib
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

import release_all


class WindowsPackageVerificationTest(unittest.TestCase):
    @staticmethod
    def write_package(archive_path: Path, version: str, contents: dict[str, bytes],
                      images: dict[str, dict[str, str]], manifest: dict) -> None:
        prefix = f"cilexec-{version}-windows/"
        all_contents = dict(contents)
        all_contents["package-manifest.json"] = json.dumps(manifest).encode("utf-8")
        with zipfile.ZipFile(archive_path, "w") as archive:
            for relative, content in all_contents.items():
                archive.writestr(prefix + relative, content)
            checksum_lines = [
                f"{hashlib.sha256(content).hexdigest()}  {relative}"
                for relative, content in all_contents.items()
            ]
            archive.writestr(prefix + "SHA256SUMS",
                             "\n".join(checksum_lines) + "\n")

    def test_accepts_required_platform_archives(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            archive_path = root / "windows.zip"
            images = {}
            contents = {}
            for architecture in ("amd64", "arm64"):
                path = f"images/cilexec-linux-{architecture}.docker.tar.gz"
                content = architecture.encode("ascii")
                contents[path] = content
                images[architecture] = {
                    "archive": path,
                    "sha256": hashlib.sha256(content).hexdigest(),
                }
            contents["Cilexec.ps1"] = b""
            contents["compose.yml"] = b""
            self.write_package(archive_path, "1.0.0", contents, images,
                               {"images": images})
            release_all.verify_windows_zip(archive_path, "1.0.0")

    def test_rejects_modified_platform_archive(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            archive_path = root / "windows.zip"
            images = {
                architecture: {
                    "archive": f"images/cilexec-linux-{architecture}.docker.tar.gz",
                    "sha256": hashlib.sha256(architecture.encode("ascii")).hexdigest(),
                }
                for architecture in ("amd64", "arm64")
            }
            contents = {
                "Cilexec.ps1": b"",
                "compose.yml": b"",
                images["amd64"]["archive"]: b"changed",
                images["arm64"]["archive"]: b"arm64",
            }
            self.write_package(archive_path, "1.0.0", contents, images,
                               {"images": images})
            with self.assertRaisesRegex(RuntimeError, "amd64"):
                release_all.verify_windows_zip(archive_path, "1.0.0")


if __name__ == "__main__":
    unittest.main()
