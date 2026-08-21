#!/usr/bin/env python3
"""Build verified CilExec installers for every supported Docker host platform."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / "build"
DIST = ROOT / "dist"
WINDOWS_FILES = (
    "windows/Cilexec.ps1",
    "windows/README.md",
    "compose.yml",
    "Dockerfile",
    ".dockerignore",
    ".mvn/maven.config",
    "LICENSE",
    "README.md",
    "docker/create-secrets.sh",
    "docker/healthcheck.sh",
    "docker/terminal-client.c",
    "docker/compose/persistent.yml",
    "docker/postgres/entrypoint.sh",
    "docker/postgres/init/00-cilexec-bootstrap.sh",
    "docker/secrets/.gitignore",
)


def command(arguments: list[str], env: dict[str, str] | None = None) -> None:
    print("+ " + " ".join(arguments), flush=True)
    subprocess.run(arguments, cwd=ROOT, env=env, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def core_identity() -> tuple[str, str]:
    manifest = json.loads((DIST / "release-manifest.json").read_text(encoding="utf-8"))
    return str(manifest["version"]), str(manifest["revision"])


def build_core(arguments: argparse.Namespace) -> None:
    release_arguments = [sys.executable, str(ROOT / "tools/release.py")]
    if arguments.skip_tests:
        release_arguments.append("--skip-tests")
    if arguments.formal:
        release_arguments.extend(("--formal", "--tag", arguments.tag))
    command(release_arguments)


def build_shell_installer(version: str, revision: str, architecture: str, image: str) -> Path:
    env = os.environ.copy()
    env.update({
        "CILEXEC_DOCKER_TARGET": "release",
        "CILEXEC_TARGET_PLATFORM": f"linux/{architecture}",
        "CILEXEC_IMAGE": image,
        "CILEXEC_BUILD_REVISION": revision,
    })
    command(["bash", "build/package.sh", version], env)
    output = BUILD / f"cilexec-{version}-linux-{architecture}.sh"
    if not output.is_file():
        raise RuntimeError(f"Missing installer output: {output}")
    verify_shell_installer(output, version)
    return output


def verify_shell_installer(path: Path, version: str) -> None:
    """Verifies the self-extracting installer: script header, a self-contained
    uninstall function embedded in the script (never relying on extracted
    files), and a payload that carries the runtime image."""
    raw = path.read_bytes()
    if not raw.startswith(b"#!/usr/bin/env bash\n"):
        raise RuntimeError(f"Installer {path.name} is not a bash script")
    text = raw.decode("utf-8", errors="replace")
    if "--uninstall" not in text:
        raise RuntimeError(f"Installer {path.name} lacks an uninstall entry point")
    if "uninstall_installation" not in text:
        raise RuntimeError(f"Installer {path.name} lacks an embedded uninstall function")
    for required in ("docker rm -f", "docker rmi", "docker volume rm",
                     "docker network rm", "postgres-admin-password"):
        if required not in text:
            raise RuntimeError(f"Installer {path.name} uninstall logic is incomplete "
                               f"(missing: {required})")
    marker = b"\n__PAYLOAD__\n"
    payload_at = raw.find(marker)
    if payload_at < 0:
        raise RuntimeError(f"Installer {path.name} has no payload marker")
    payload = raw[payload_at + len(marker):]
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:*") as archive:
        names = {member.name.removeprefix("./") for member in archive.getmembers()}
        if ".cilexec-image-platform" not in names:
            raise RuntimeError(f"Installer {path.name} payload is missing platform metadata")
        for required in ("tools/Version.sh", ".mvn/maven.config"):
            if required not in names:
                raise RuntimeError(f"Installer {path.name} payload is missing {required}")


def verify_image_jar(image: str, expected_hash: str) -> None:
    container = subprocess.check_output(["docker", "create", image], cwd=ROOT, text=True).strip()
    try:
        with tempfile.TemporaryDirectory(prefix="cilexec-image-jar-") as name:
            copied = Path(name) / "cilexec-app.jar"
            command(["docker", "cp", f"{container}:/opt/cilexec/cilexec-app.jar", str(copied)])
            if sha256(copied) != expected_hash:
                raise RuntimeError(f"Image {image} does not contain the verified Runtime JAR")
    finally:
        subprocess.run(["docker", "rm", "-f", container], cwd=ROOT,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


def save_image(image: str, destination: Path) -> None:
    process = subprocess.Popen(["docker", "save", image], cwd=ROOT, stdout=subprocess.PIPE)
    assert process.stdout is not None
    try:
        with destination.open("wb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                shutil.copyfileobj(process.stdout, compressed, 1024 * 1024)
    finally:
        process.stdout.close()
    if process.wait() != 0:
        raise subprocess.CalledProcessError(process.returncode, ["docker", "save", image])


def write_internal_checksums(root: Path) -> None:
    files = sorted(path for path in root.rglob("*")
                   if path.is_file() and path.name != "SHA256SUMS")
    lines = [f"{sha256(path)}  {path.relative_to(root).as_posix()}" for path in files]
    (root / "SHA256SUMS").write_text("\n".join(lines) + "\n", encoding="ascii")


def build_windows_zip(version: str, revision: str, images: dict[str, str], output: Path) -> None:
    runtime_manifest = json.loads((DIST / "release-manifest.json").read_text(encoding="utf-8"))
    expected_jar = runtime_manifest["artifacts"]["cilexec-app.jar"]["sha256"]
    with tempfile.TemporaryDirectory(prefix="cilexec-windows-package-", dir=BUILD) as name:
        stage = Path(name) / f"cilexec-{version}-windows"
        stage.mkdir()
        for relative in WINDOWS_FILES:
            source = ROOT / relative
            windows_names = {
                "windows/Cilexec.ps1": "Cilexec.ps1",
                "windows/README.md": "WINDOWS.md",
            }
            destination_name = windows_names.get(relative, relative)
            destination = stage / destination_name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, destination)
        image_metadata: dict[str, dict[str, str]] = {}
        for architecture, image in images.items():
            verify_image_jar(image, expected_jar)
            archive_name = f"images/cilexec-linux-{architecture}.docker.tar.gz"
            archive = stage / archive_name
            archive.parent.mkdir(parents=True, exist_ok=True)
            save_image(image, archive)
            image_metadata[architecture] = {
                "platform": f"linux/{architecture}",
                "image": image,
                "archive": archive_name,
                "sha256": sha256(archive),
                "runtimeJarSha256": expected_jar,
            }
        manifest = {
            "schemaVersion": 1,
            "version": version,
            "revision": revision,
            "images": image_metadata,
        }
        (stage / "package-manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        write_internal_checksums(stage)
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED,
                             compresslevel=6, allowZip64=True) as archive:
            for path in sorted(stage.rglob("*")):
                if path.is_file():
                    archive.write(path, path.relative_to(stage.parent).as_posix())
    verify_windows_zip(output, version)


def verify_windows_zip(path: Path, version: str) -> None:
    prefix = f"cilexec-{version}-windows/"
    with zipfile.ZipFile(path) as archive:
        if archive.testzip() is not None:
            raise RuntimeError(f"Corrupt Windows ZIP: {path}")
        names = set(archive.namelist())
        required = {
            prefix + "Cilexec.ps1",
            prefix + "compose.yml",
            prefix + ".mvn/maven.config",
            prefix + "package-manifest.json",
            prefix + "SHA256SUMS",
            prefix + "images/cilexec-linux-amd64.docker.tar.gz",
            prefix + "images/cilexec-linux-arm64.docker.tar.gz",
        }
        missing = required - names
        if missing:
            raise RuntimeError(f"Windows ZIP is missing: {sorted(missing)}")
        manifest = json.loads(archive.read(prefix + "package-manifest.json"))
        checksum_text = archive.read(prefix + "SHA256SUMS").decode("ascii").splitlines()
        declared: dict[str, str] = {}
        for line in checksum_text:
            digest, relative = line.split("  ", 1)
            declared[relative] = digest
        covered = {prefix + relative for relative in declared}
        uncovered = (names - covered) - {prefix + "SHA256SUMS"}
        if uncovered:
            raise RuntimeError(f"Windows ZIP entries are not covered by SHA256SUMS: "
                               f"{sorted(uncovered)}")
        for relative, digest in declared.items():
            content = archive.read(prefix + relative)
            if hashlib.sha256(content).hexdigest() != digest:
                raise RuntimeError(f"Windows ZIP checksum mismatch: {relative}")
        for architecture in ("amd64", "arm64"):
            entry = manifest["images"][architecture]
            content = archive.read(prefix + entry["archive"])
            if hashlib.sha256(content).hexdigest() != entry["sha256"]:
                raise RuntimeError(f"Windows ZIP image checksum mismatch: {architecture}")


def write_outer_checksums(directory: Path, version: str, assets: list[Path]) -> Path:
    checksum = directory / f"cilexec-{version}-SHA256SUMS"
    checksum.write_text("".join(f"{sha256(path)}  {path.name}\n"
                                for path in sorted(assets)),
                        encoding="ascii")
    return checksum


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-tests", action="store_true")
    parser.add_argument("--formal", action="store_true")
    parser.add_argument("--tag")
    parser.add_argument("--package-only", action="store_true",
                        help="reuse verified dist and existing platform image tags")
    parser.add_argument("--version")
    parser.add_argument("--image-amd64", default="cilexec:all-platform-amd64")
    parser.add_argument("--image-arm64", default="cilexec:all-platform-arm64")
    parser.add_argument("--output-directory", type=Path)
    arguments = parser.parse_args()
    if arguments.formal and (arguments.skip_tests or not arguments.tag):
        parser.error("--formal requires --tag and cannot use --skip-tests")
    if arguments.tag and not arguments.formal:
        parser.error("--tag requires --formal")
    return arguments


def main() -> int:
    arguments = parse_arguments()
    try:
        if not arguments.package_only:
            build_core(arguments)
        core_version, revision = core_identity()
        version = arguments.version or core_version
        output = (arguments.output_directory or BUILD / "releases" / version).resolve()
        output.mkdir(parents=True, exist_ok=True)
        images = {"amd64": arguments.image_amd64, "arm64": arguments.image_arm64}
        assets: list[Path] = []
        if not arguments.package_only:
            for architecture, image in images.items():
                installer = build_shell_installer(version, revision, architecture, image)
                published_installer = output / installer.name
                shutil.copy2(installer, published_installer)
                assets.append(published_installer)
        windows_zip = output / f"cilexec-{version}-windows.zip"
        build_windows_zip(version, revision, images, windows_zip)
        assets.append(windows_zip)
        write_outer_checksums(output, version, assets)
        print(f"All-platform release complete: {output}", flush=True)
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError, KeyError,
            json.JSONDecodeError, zipfile.BadZipFile) as error:
        print(f"All-platform release failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
