#!/usr/bin/env python3

import hashlib
import io
import json
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

import release_all


def write_shell_installer(path: Path, version: str, *, uninstall_entry: bool = True,
                          embedded_uninstall: bool = True,
                          uninstall_cleanup: bool = True) -> None:
    uninstall_function = (
        "uninstall_installation() {\n"
        "  docker rm -f $(docker ps -aq) 2>/dev/null\n"
        "  docker rmi cilexec:local 2>/dev/null\n"
        "  docker volume rm cilexec-pgdata 2>/dev/null\n"
        "  docker network rm cilexec-net 2>/dev/null\n"
        "  rm -f docker/secrets/postgres-admin-password\n"
        "}\n"
    ) if embedded_uninstall else ""
    if uninstall_function and not uninstall_cleanup:
        uninstall_function = (
            "uninstall_installation() {\n"
            "  echo partial\n"
            "}\n"
        )
    parts = ["#!/usr/bin/env bash\n", "set -euo pipefail\n"]
    if uninstall_function:
        parts.append(uninstall_function)
    if uninstall_entry:
        parts.append("--uninstall) UNINSTALL=true ;;\n")
    parts.append("echo installing\n")
    if uninstall_entry and uninstall_function:
        parts.append("uninstall_installation\n")
    parts.append("__PAYLOAD__\n")
    header = "".join(parts)
    payload_members = [
        (".cilexec-image-platform", b"linux-amd64"),
    ]
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for name, content in payload_members:
            info = tarfile.TarInfo(name)
            info.size = len(content)
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(content))
    payload = buffer.getvalue()
    path.write_bytes(header.encode("ascii") + payload)


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


class ShellInstallerVerificationTest(unittest.TestCase):
    def test_accepts_installer_with_embedded_uninstall_function(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            installer = Path(name) / "cilexec-1.0.0-linux-amd64.sh"
            write_shell_installer(installer, "1.0.0")
            release_all.verify_shell_installer(installer, "1.0.0")

    def test_rejects_installer_without_uninstall_entry(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            installer = Path(name) / "cilexec-1.0.0-linux-amd64.sh"
            write_shell_installer(installer, "1.0.0", uninstall_entry=False)
            with self.assertRaisesRegex(RuntimeError, "uninstall entry"):
                release_all.verify_shell_installer(installer, "1.0.0")

    def test_rejects_installer_without_embedded_uninstall_function(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            installer = Path(name) / "cilexec-1.0.0-linux-amd64.sh"
            write_shell_installer(installer, "1.0.0", embedded_uninstall=False)
            with self.assertRaisesRegex(RuntimeError, "embedded uninstall"):
                release_all.verify_shell_installer(installer, "1.0.0")

    def test_rejects_installer_with_incomplete_uninstall_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as name:
            installer = Path(name) / "cilexec-1.0.0-linux-amd64.sh"
            write_shell_installer(installer, "1.0.0", uninstall_cleanup=False)
            with self.assertRaisesRegex(RuntimeError, "incomplete"):
                release_all.verify_shell_installer(installer, "1.0.0")


if __name__ == "__main__":
    unittest.main()
