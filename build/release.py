#!/usr/bin/env python3
"""Build and verify the complete CilExec release directory.

The default mode runs all tests, builds both Java applications, builds every
FCL package that has dist/<name>/package.json plus market.json, publishes those
packages into the content-addressed market repository, and writes SHA256SUMS.
Nothing in dist is replaced until every staged artifact has been validated.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
RUNTIME_JAR = ROOT / "target" / "cilexec-app.jar"
MARKET_JAR = ROOT / "market-server" / "target" / "cilexec-market-server.jar"
SHA256_NAME = "SHA256SUMS"


def fail(message: str) -> None:
    raise RuntimeError(message)


def command(arguments: list[str]) -> None:
    print("+", " ".join(arguments), flush=True)
    subprocess.run(arguments, cwd=ROOT, check=True)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def build_revision() -> str:
    configured = os.environ.get("CILEXEC_BUILD_REVISION", "").strip()
    if configured:
        return configured
    try:
        revision = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip()
        dirty = subprocess.check_output(
            ["git", "status", "--porcelain", "--untracked-files=normal"],
            cwd=ROOT, text=True
        ).strip()
        return revision + ("-dirty" if dirty else "")
    except (OSError, subprocess.CalledProcessError) as error:
        fail("Cannot determine build revision; set CILEXEC_BUILD_REVISION")
        raise AssertionError from error


def build_java(skip_tests: bool) -> None:
    maven = "mvn.cmd" if os.name == "nt" else "mvn"
    revision = build_revision()
    root_goal = "package" if skip_tests else "verify"
    server_goal = "package" if skip_tests else "verify"
    root_arguments = [maven, "--batch-mode", "--no-transfer-progress", "clean", root_goal,
                      f"-Dbuild.revision={revision}"]
    server_arguments = [maven, "--batch-mode", "--no-transfer-progress", "-f",
                        str(ROOT / "market-server" / "pom.xml"), "clean", server_goal]
    if skip_tests:
        root_arguments.append("-DskipTests")
        server_arguments.append("-DskipTests")
    command(root_arguments)
    command(server_arguments)


def market_metadata(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Invalid market metadata {path}: {error}")
    if not isinstance(value, dict):
        fail(f"Market metadata must be an object: {path}")
    allowed = {"summary", "description", "tags"}
    if set(value) - allowed:
        fail(f"Unknown market metadata keys in {path}: {sorted(set(value) - allowed)}")
    for key in ("summary", "description"):
        if key in value and (not isinstance(value[key], str) or len(value[key]) > 16_384
                             or any(ord(character) < 32 for character in value[key])):
            fail(f"Market metadata {key} must be text: {path}")
    if "tags" in value and (not isinstance(value["tags"], list)
                            or len(value["tags"]) > 128
                            or not all(isinstance(item, str) and len(item) <= 16_384
                                       and not any(ord(character) < 32 for character in item)
                                       for item in value["tags"])):
        fail(f"Market metadata tags must be an array of text: {path}")
    return value


def package_sources() -> list[Path]:
    result = sorted(
        path.parent for path in DIST.glob("*/package.json")
        if (path.parent / "market.json").is_file()
    )
    if not result:
        fail("No publishable package sources were found under dist/")
    return result


def build_packages(staging: Path) -> dict[str, Any]:
    sys.path.insert(0, str(ROOT))
    try:
        from PackageBuild import build as build_package
    finally:
        sys.path.pop(0)

    catalog: dict[str, Any] = {}
    existing_repository = DIST / "repository"
    existing_catalog = DIST / "catalog.json"
    if existing_repository.is_dir():
        shutil.copytree(existing_repository, staging / "repository", dirs_exist_ok=True)
    if existing_catalog.is_file():
        try:
            previous = json.loads(existing_catalog.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            fail(f"Invalid existing release catalog: {error}")
        if not isinstance(previous, dict):
            fail("Existing release catalog must contain an object")
        catalog.update(previous)
    built_coordinates: set[str] = set()
    for source in package_sources():
        manifest = json.loads((source / "package.json").read_text(encoding="utf-8"))
        coordinate = "/".join(str(manifest.get(key, ""))
                              for key in ("namespace", "name", "version"))
        if coordinate in built_coordinates:
            fail(f"Duplicate package coordinate: {coordinate}")
        built_coordinates.add(coordinate)
        namespace, name, version = coordinate.split("/", 2)
        output = staging / "repository" / "packages" / namespace / name / version / f"{name}.db"
        output.parent.mkdir(parents=True, exist_ok=True)
        previous_digest = sha256(output) if output.is_file() else None
        candidate = output.with_name(f".{name}.candidate.db") if previous_digest else output
        actual_coordinate, digest = build_package(source, candidate)
        if actual_coordinate != coordinate or sha256(candidate) != digest:
            fail(f"Package builder returned inconsistent identity: {coordinate}")
        if previous_digest is not None and previous_digest != digest:
            candidate.unlink(missing_ok=True)
            fail(f"Published package {coordinate} changed content; bump its version")
        if candidate != output:
            os.replace(candidate, output)
        output.chmod(0o644)
        catalog[coordinate] = market_metadata(source / "market.json")
        print(f"Built {coordinate} ({digest})", flush=True)
    (staging / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return catalog


def jar_main_class(path: Path) -> str:
    if not path.is_file():
        fail(f"Missing JAR: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            damaged = archive.testzip()
            if damaged is not None:
                fail(f"Damaged JAR entry in {path}: {damaged}")
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="strict")
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile) as error:
        fail(f"Invalid JAR {path}: {error}")
    for line in manifest.replace("\r\n ", "").splitlines():
        if line.startswith("Main-Class: "):
            return line.removeprefix("Main-Class: ").strip()
    fail(f"JAR has no Main-Class: {path}")
    raise AssertionError


def package_coordinate(path: Path) -> str:
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        version = connection.execute("PRAGMA user_version").fetchone()[0]
        if version != 2:
            fail(f"Unsupported package format {version}: {path}")
        metadata = dict(connection.execute(
            "SELECT metadata_key, metadata_value FROM package_metadata"
        ))
    finally:
        connection.close()
    return "/".join(metadata.get(key, "") for key in ("namespace", "name", "version"))


def expected_files(directory: Path) -> dict[str, Path]:
    result = {
        "cilexec-app.jar": directory / "cilexec-app.jar",
        "cilexec-market-server.jar": directory / "cilexec-market-server.jar",
    }
    repository = directory / "repository" / "packages"
    if repository.is_dir():
        for path in sorted(repository.rglob("*.db")):
            result[path.relative_to(directory).as_posix()] = path
    return result


def write_checksums(directory: Path) -> None:
    files = expected_files(directory)
    if len(files) < 3:
        fail("Release must contain both JARs and at least one package")
    content = "".join(f"{sha256(files[name])}  {name}\n" for name in sorted(files))
    (directory / SHA256_NAME).write_text(content, encoding="ascii")


def verify_market_server(directory: Path) -> None:
    command(["java", "--enable-native-access=ALL-UNNAMED", "-jar",
             str(directory / "cilexec-market-server.jar"),
             "--repository", str(directory / "repository"),
             "--catalog", str(directory / "catalog.json"), "--check"])


def verify(directory: Path) -> None:
    if jar_main_class(directory / "cilexec-app.jar") != "com.follarce.Main":
        fail("Runtime JAR has the wrong entry point")
    if jar_main_class(directory / "cilexec-market-server.jar") \
            != "com.follarce.market.server.MarketServerMain":
        fail("Market server JAR has the wrong entry point")

    try:
        catalog = json.loads((directory / "catalog.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Invalid release catalog: {error}")
    if not isinstance(catalog, dict) or not catalog:
        fail("Release catalog must contain at least one package")

    package_files = expected_files(directory)
    published: set[str] = set()
    for name, path in package_files.items():
        if name.endswith(".db"):
            coordinate = package_coordinate(path)
            relative = Path(name).parts
            expected = "/".join(relative[2:5])
            if coordinate != expected or coordinate not in catalog:
                fail(f"Package path/catalog mismatch: {name}")
            published.add(coordinate)
    if published != set(catalog):
        fail("Catalog and repository contain different package coordinates")

    checksum_path = directory / SHA256_NAME
    try:
        lines = checksum_path.read_text(encoding="ascii").splitlines()
    except OSError as error:
        fail(f"Cannot read {SHA256_NAME}: {error}")
    declared: dict[str, str] = {}
    for line in lines:
        if len(line) < 67 or line[64:66] != "  ":
            fail(f"Invalid checksum line: {line}")
        digest, name = line[:64], line[66:]
        if name.startswith("/") or ".." in Path(name).parts or name in declared:
            fail(f"Unsafe or duplicate checksum path: {name}")
        declared[name] = digest
    if set(declared) != set(package_files):
        fail(f"{SHA256_NAME} does not describe exactly the release artifacts")
    for name, path in package_files.items():
        if not path.is_file() or sha256(path) != declared[name]:
            fail(f"Checksum mismatch: {name}")
    verify_market_server(directory)
    print(f"Verified {len(package_files)} release artifacts in {directory}", flush=True)


def publish(staging: Path) -> None:
    # Package files become visible before the catalog that references them. Existing package
    # versions were copied into staging, so replacing the repository never removes history.
    names = ("cilexec-app.jar", "cilexec-market-server.jar", "repository",
             "catalog.json", SHA256_NAME)
    backup = Path(tempfile.mkdtemp(prefix=".release-backup-", dir=DIST))
    backed_up: list[str] = []
    installed: list[str] = []
    cleanup_backup = True
    try:
        for name in names:
            destination = DIST / name
            if destination.exists() or destination.is_symlink():
                os.replace(destination, backup / name)
                backed_up.append(name)
        for name in names:
            os.replace(staging / name, DIST / name)
            installed.append(name)
    except BaseException as publish_error:
        rollback_errors: list[str] = []
        for name in reversed(installed):
            destination = DIST / name
            try:
                if destination.is_dir() and not destination.is_symlink():
                    shutil.rmtree(destination)
                elif destination.exists() or destination.is_symlink():
                    destination.unlink()
            except OSError as error:
                rollback_errors.append(f"remove {name}: {error}")
        for name in reversed(backed_up):
            try:
                os.replace(backup / name, DIST / name)
            except OSError as error:
                rollback_errors.append(f"restore {name}: {error}")
        if rollback_errors:
            cleanup_backup = False
            fail(f"Release publish failed and rollback was incomplete; recovery files remain "
                 f"in {backup}: " + "; ".join(rollback_errors))
        print("Release publish failed; previous dist artifacts were restored", file=sys.stderr)
        raise publish_error
    finally:
        if cleanup_backup and backup.exists():
            shutil.rmtree(backup)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-tests", action="store_true",
                        help="package without rerunning tests (intended for CI after verify)")
    parser.add_argument("--verify-only", action="store_true",
                        help="verify the existing dist artifacts without rebuilding")
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    try:
        if arguments.verify_only:
            verify(DIST)
            return 0
        build_java(arguments.skip_tests)
        with tempfile.TemporaryDirectory(prefix="cilexec-release-", dir=ROOT / "build") as name:
            staging = Path(name)
            shutil.copy2(RUNTIME_JAR, staging / "cilexec-app.jar")
            shutil.copy2(MARKET_JAR, staging / "cilexec-market-server.jar")
            build_packages(staging)
            write_checksums(staging)
            verify(staging)
            publish(staging)
        verify(DIST)
        print("Release complete: dist/", flush=True)
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError, sqlite3.Error,
            json.JSONDecodeError, ValueError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
