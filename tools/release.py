#!/usr/bin/env python3
"""Build and verify the complete CilExec release directory.

The default mode runs all tests, builds both Java applications and every
publishable FCL package, then creates and validates the catalog, licenses,
notices, SBOM, release manifest, and checksums in staging. Nothing in dist is
replaced until every staged artifact has been validated.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import urllib.parse
import zipfile
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parent.parent
DIST = ROOT / "dist"
RUNTIME_JAR = ROOT / "target" / "cilexec-app.jar"
MARKET_JAR = ROOT / "market-server" / "target" / "cilexec-market-server.jar"
SHA256_NAME = "SHA256SUMS"
SBOM_NAME = "SBOM.cdx.json"
MANIFEST_NAME = "release-manifest.json"
NOTICES_NAME = "THIRD_PARTY_NOTICES.txt"
RUNTIME_DEPENDENCIES_NAME = "runtime-dependencies.txt"
MARKET_DEPENDENCIES_NAME = "market-dependencies.txt"
APPLICATIONS = {
    "cilexec-app.jar": (
        "cilexec",
        RUNTIME_DEPENDENCIES_NAME,
        ROOT / "target" / "dependency-lock.txt",
        ROOT / "target" / "runtime-sbom.json",
    ),
    "cilexec-market-server.jar": (
        "cilexec-market-server",
        MARKET_DEPENDENCIES_NAME,
        ROOT / "market-server" / "target" / "dependency-lock.txt",
        ROOT / "market-server" / "target" / "runtime-sbom.json",
    ),
}
REQUIRED_PAYLOAD_NAMES = (
    "cilexec-app.jar",
    "cilexec-market-server.jar",
    "catalog.json",
    "MARKET.md",
    "RELEASE.txt",
    "LICENSE",
    NOTICES_NAME,
    SBOM_NAME,
    RUNTIME_DEPENDENCIES_NAME,
    MARKET_DEPENDENCIES_NAME,
)
PUBLISHED_NAMES = (
    "cilexec-app.jar",
    "cilexec-market-server.jar",
    "repository",
    "catalog.json",
    "MARKET.md",
    "RELEASE.txt",
    "LICENSE",
    NOTICES_NAME,
    SBOM_NAME,
    RUNTIME_DEPENDENCIES_NAME,
    MARKET_DEPENDENCIES_NAME,
    MANIFEST_NAME,
    SHA256_NAME,
)


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


def git_output(arguments: list[str]) -> str:
    return subprocess.check_output(["git", *arguments], cwd=ROOT, text=True).strip()


def build_revision() -> str:
    configured = os.environ.get("CILEXEC_BUILD_REVISION", "").strip()
    if configured:
        return configured
    try:
        revision = git_output(["rev-parse", "HEAD"])
        dirty = git_output(["status", "--porcelain", "--untracked-files=normal"])
        return revision + ("-dirty" if dirty else "")
    except (OSError, subprocess.CalledProcessError) as error:
        fail("Cannot determine build revision; set CILEXEC_BUILD_REVISION")
        raise AssertionError from error


def project_version() -> str:
    try:
        root = ElementTree.parse(ROOT / "pom.xml").getroot()
    except (OSError, ElementTree.ParseError) as error:
        fail(f"Cannot read project version: {error}")
    element = root.find("{*}version")
    version = "" if element is None or element.text is None else element.text.strip()
    if not version:
        fail("Project version is missing")
    return version


def validate_formal_release(version: str, revision: str, tag: str | None, dirty: bool,
                            head_revision: str | None = None,
                            tag_revision: str | None = None) -> None:
    if dirty:
        fail("Formal releases require a clean Git working tree")
    if not version or version.lower() == "unknown" or "SNAPSHOT" in version.upper():
        fail(f"Formal release version is not releasable: {version or 'unknown'}")
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        fail(f"Formal release revision must be a full Git commit id, not {revision or 'unknown'}")
    expected_tag = f"v{version}"
    if tag != expected_tag:
        fail(f"Formal release tag must be {expected_tag}, not {tag or 'unknown'}")
    if head_revision is not None and revision != head_revision:
        fail("Configured build revision does not match HEAD")
    if tag_revision is not None and head_revision is not None and tag_revision != head_revision:
        fail(f"Release tag {tag} does not point at HEAD")


def release_identity(formal: bool, tag: str | None) -> dict[str, Any]:
    version = project_version()
    revision = build_revision()
    identity: dict[str, Any] = {
        "version": version,
        "revision": revision,
        "formal": formal,
        "tag": tag if formal else None,
    }
    if not formal:
        return identity
    try:
        status = git_output(["status", "--porcelain", "--untracked-files=normal"])
        validate_formal_release(version, revision, tag, bool(status))
        head_revision = git_output(["rev-parse", "HEAD"])
        tag_revision = git_output(["rev-list", "-n", "1", tag or ""])
    except (OSError, subprocess.CalledProcessError) as error:
        fail(f"Cannot validate formal release Git identity: {error}")
        raise AssertionError from error
    validate_formal_release(version, revision, tag, False, head_revision, tag_revision)
    return identity


def build_java(skip_tests: bool, revision: str) -> None:
    maven = "mvn.cmd" if os.name == "nt" else "mvn"
    root_goal = "package" if skip_tests else "verify"
    server_goal = "package" if skip_tests else "verify"
    root_arguments = [maven, "--batch-mode", "--no-transfer-progress", "clean", root_goal,
                      f"-Dbuild.revision={revision}"]
    server_arguments = [maven, "--batch-mode", "--no-transfer-progress", "-f",
                        str(ROOT / "market-server" / "pom.xml"), "clean", server_goal,
                        f"-Dbuild.revision={revision}"]
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
        from tools.PackageBuild import build as build_package
    finally:
        sys.path.pop(0)

    catalog: dict[str, Any] = {}
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
        actual_coordinate, digest = build_package(source, output)
        if actual_coordinate != coordinate or sha256(output) != digest:
            fail(f"Package builder returned inconsistent identity: {coordinate}")
        output.chmod(0o644)
        catalog[coordinate] = market_metadata(source / "market.json")
        print(f"Built {coordinate} ({digest})", flush=True)
    (staging / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return catalog


def jar_manifest(path: Path) -> dict[str, str]:
    if not path.is_file():
        fail(f"Missing JAR: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            damaged = archive.testzip()
            if damaged is not None:
                fail(f"Damaged JAR entry in {path}: {damaged}")
            content = archive.read("META-INF/MANIFEST.MF").decode("utf-8", errors="strict")
    except (OSError, KeyError, UnicodeError, zipfile.BadZipFile) as error:
        fail(f"Invalid JAR {path}: {error}")
    lines: list[str] = []
    for line in content.splitlines():
        if line.startswith(" ") and lines:
            lines[-1] += line[1:]
        else:
            lines.append(line)
    result = {}
    for line in lines:
        key, separator, value = line.partition(": ")
        if separator:
            result[key] = value.strip()
    return result


def jar_main_class(path: Path) -> str:
    main_class = jar_manifest(path).get("Main-Class", "")
    if not main_class:
        fail(f"JAR has no Main-Class: {path}")
    return main_class


def maven_purl(group: str, name: str, version: str) -> str:
    group_part = urllib.parse.quote(group, safe=".-_")
    name_part = urllib.parse.quote(name, safe=".-_")
    version_part = urllib.parse.quote(version, safe=".-_")
    return f"pkg:maven/{group_part}/{name_part}@{version_part}"


def jar_components(path: Path) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    try:
        with zipfile.ZipFile(path) as archive:
            properties_names = sorted(
                name for name in archive.namelist()
                if re.fullmatch(r"META-INF/maven/[^/]+/[^/]+/pom\.properties", name)
            )
            for properties_name in properties_names:
                parts = properties_name.split("/")
                group, artifact = parts[2], parts[3]
                properties = {}
                for line in archive.read(properties_name).decode("iso-8859-1").splitlines():
                    key, separator, value = line.partition("=")
                    if separator:
                        properties[key.strip()] = value.strip()
                version = properties.get("version", "")
                if not version:
                    fail(f"Missing Maven component version in {path}: {group}:{artifact}")
                coordinate = f"{group}:{artifact}:{version}"
                licenses = []
                pom_name = properties_name.removesuffix("pom.properties") + "pom.xml"
                if pom_name in archive.namelist():
                    pom = ElementTree.fromstring(archive.read(pom_name))
                    for license_element in pom.findall("./{*}licenses/{*}license"):
                        name_element = license_element.find("{*}name")
                        url_element = license_element.find("{*}url")
                        name = "" if name_element is None or name_element.text is None \
                            else name_element.text.strip()
                        url = "" if url_element is None or url_element.text is None \
                            else url_element.text.strip()
                        if name:
                            license_value = {"name": name}
                            if url:
                                license_value["url"] = url
                            licenses.append(license_value)
                component: dict[str, Any] = {
                    "type": "application" if group == "com.follarce" else "library",
                    "group": group,
                    "name": artifact,
                    "version": version,
                    "bom-ref": maven_purl(group, artifact, version),
                    "purl": maven_purl(group, artifact, version),
                }
                if licenses:
                    component["licenses"] = [{"license": item} for item in licenses]
                previous = result.get(coordinate)
                if previous is not None and previous != component:
                    fail(f"Conflicting Maven component metadata: {coordinate}")
                result[coordinate] = component
    except (OSError, UnicodeError, zipfile.BadZipFile, ElementTree.ParseError) as error:
        fail(f"Cannot inventory JAR {path}: {error}")
    if not result:
        fail(f"JAR contains no Maven component metadata: {path}")
    return result


def dependency_coordinates(path: Path) -> list[str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        fail(f"Cannot read Maven dependency inventory {path}: {error}")
    coordinates = set()
    for line in lines:
        candidate = line.strip().split(" -- ", 1)[0]
        parts = candidate.split(":")
        if len(parts) not in (5, 6) or parts[-1] not in ("compile", "runtime"):
            continue
        group, artifact, version = parts[0], parts[1], parts[-2]
        if not all(re.fullmatch(r"[A-Za-z0-9_.-]+", value)
                   for value in (group, artifact, version)):
            fail(f"Unsafe Maven dependency coordinate in {path}: {candidate}")
        coordinates.add(f"{group}:{artifact}:{version}")
    if not coordinates:
        fail(f"Maven dependency inventory is empty: {path}")
    return sorted(coordinates)


def write_dependency_inventories(directory: Path) -> None:
    for _, inventory_name, lock_path, _ in APPLICATIONS.values():
        coordinates = dependency_coordinates(lock_path)
        (directory / inventory_name).write_text(
            "".join(f"{coordinate}\n" for coordinate in coordinates), encoding="ascii"
        )


def read_dependency_inventory(path: Path) -> list[str]:
    try:
        lines = path.read_text(encoding="ascii").splitlines()
    except (OSError, UnicodeError) as error:
        fail(f"Cannot read release dependency inventory {path}: {error}")
    if not lines or lines != sorted(set(lines)):
        fail(f"Release dependency inventory is empty, duplicated, or unsorted: {path}")
    for coordinate in lines:
        if not re.fullmatch(r"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+",
                            coordinate):
            fail(f"Invalid release dependency coordinate: {coordinate}")
    return lines


def normalized_cdx_component(source: dict[str, Any], component_type: str) -> dict[str, Any]:
    group = source.get("group")
    name = source.get("name")
    version = source.get("version")
    if not all(isinstance(value, str) and value for value in (group, name, version)):
        fail("Maven SBOM component has an invalid coordinate")
    component: dict[str, Any] = {
        "type": component_type,
        "group": group,
        "name": name,
        "version": version,
        "bom-ref": maven_purl(group, name, version),
        "purl": maven_purl(group, name, version),
    }
    licenses = []
    for entry in source.get("licenses", []):
        if not isinstance(entry, dict):
            fail(f"Invalid license metadata for {group}:{name}:{version}")
        if isinstance(entry.get("expression"), str):
            licenses.append({"expression": entry["expression"]})
            continue
        license_source = entry.get("license")
        if not isinstance(license_source, dict):
            fail(f"Invalid license metadata for {group}:{name}:{version}")
        license_value = {key: license_source[key] for key in ("id", "name", "url")
                         if isinstance(license_source.get(key), str)
                         and license_source[key]}
        if not ("id" in license_value or "name" in license_value):
            fail(f"License has no id or name for {group}:{name}:{version}")
        licenses.append({"license": license_value})
    if licenses:
        component["licenses"] = licenses
    return component


def maven_sbom_components(path: Path, application_name: str) \
        -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    document = read_json_object(path, "Maven runtime SBOM")
    metadata = document.get("metadata")
    own_source = metadata.get("component") if isinstance(metadata, dict) else None
    if not isinstance(own_source, dict) or own_source.get("group") != "com.follarce" \
            or own_source.get("name") != application_name:
        fail(f"Cannot identify application component in Maven SBOM {path}")
    own = normalized_cdx_component(own_source, "application")
    dependencies: dict[str, dict[str, Any]] = {}
    for source in document.get("components", []):
        if not isinstance(source, dict):
            fail(f"Invalid component in Maven SBOM {path}")
        component = normalized_cdx_component(source, "library")
        coordinate = f"{component['group']}:{component['name']}:{component['version']}"
        if coordinate in dependencies:
            fail(f"Duplicate component in Maven SBOM {path}: {coordinate}")
        dependencies[coordinate] = component
    return own, dependencies


def sbom_document(directory: Path, version: str) -> dict[str, Any]:
    all_components: dict[str, dict[str, Any]] = {}
    dependency_entries = []
    application_refs = []
    for jar_name, application in APPLICATIONS.items():
        application_name, inventory_name, _, maven_sbom_path = application
        components = jar_components(directory / jar_name)
        own = [item for item in components.values()
               if item["group"] == "com.follarce" and item["name"] == application_name]
        if len(own) != 1:
            fail(f"Cannot identify application component in {jar_name}")
        maven_own, dependencies = maven_sbom_components(maven_sbom_path, application_name)
        if any(maven_own[key] != own[0][key] for key in ("group", "name", "version")):
            fail(f"Maven SBOM application identity does not match {jar_name}")
        inventory = read_dependency_inventory(directory / inventory_name)
        if set(inventory) != set(dependencies):
            fail(f"Maven SBOM and dependency inventory differ for {application_name}")
        application_component = own[0]
        application_ref = application_component["bom-ref"]
        application_refs.append(application_ref)
        dependency_entries.append({
            "ref": application_ref,
            "dependsOn": sorted(item["bom-ref"] for item in dependencies.values()),
        })
        application_coordinate = (f"{application_component['group']}:"
                                  f"{application_component['name']}:"
                                  f"{application_component['version']}")
        for coordinate, component in {application_coordinate: application_component,
                                      **dependencies}.items():
            previous = all_components.get(coordinate)
            if previous is not None and previous != component:
                fail(f"Conflicting component across release JARs: {coordinate}")
            all_components[coordinate] = component
    release_ref = maven_purl("com.follarce", "cilexec-release", version)
    return {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "group": "com.follarce",
                "name": "cilexec-release",
                "version": version,
                "bom-ref": release_ref,
            }
        },
        "components": [all_components[name] for name in sorted(all_components)],
        "dependencies": ([{"ref": release_ref, "dependsOn": sorted(application_refs)}]
                         + sorted(dependency_entries, key=lambda item: item["ref"])),
    }


def third_party_notices(sbom: dict[str, Any]) -> str:
    lines = [
        "CilExec third-party notices",
        "",
        "This inventory is generated from the resolved Maven runtime dependency graphs.",
        "The corresponding license resources remain embedded in those JARs.",
        "",
    ]
    for component in sbom["components"]:
        if component["group"] == "com.follarce":
            continue
        lines.append(f"{component['group']}:{component['name']}:{component['version']}")
        licenses = component.get("licenses", [])
        if not licenses:
            lines.append("  License: not declared in resolved Maven metadata")
        for entry in licenses:
            if "expression" in entry:
                lines.append(f"  License: {entry['expression']}")
                continue
            license_value = entry["license"]
            suffix = f" ({license_value['url']})" if "url" in license_value else ""
            lines.append(f"  License: {license_value.get('id', license_value.get('name'))}{suffix}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


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


def payload_files(directory: Path) -> dict[str, Path]:
    result = {name: directory / name for name in REQUIRED_PAYLOAD_NAMES}
    repository = directory / "repository" / "packages"
    if repository.is_dir():
        for path in sorted(repository.rglob("*.db")):
            result[path.relative_to(directory).as_posix()] = path
    return result


def expected_files(directory: Path) -> dict[str, Path]:
    result = payload_files(directory)
    result[MANIFEST_NAME] = directory / MANIFEST_NAME
    return result


def write_release_metadata(directory: Path, identity: dict[str, Any]) -> None:
    market_source = DIST / "MARKET.md"
    if not market_source.is_file():
        fail(f"Missing release document source: {market_source}")
    shutil.copy2(market_source, directory / "MARKET.md")
    catalog = read_json_object(directory / "catalog.json", "release catalog")
    packages = ", ".join(sorted(catalog))
    release_text = f"""CilExec {identity['version']}

Build revision: {identity['revision']}
Runtime requirement: Java 26
Market server requirement: Java 21 or newer
Market protocol: cilexec.market/v1
Published packages: {packages}

Contents: cilexec-app.jar, cilexec-market-server.jar, repository/, catalog.json,
MARKET.md, LICENSE, THIRD_PARTY_NOTICES.txt, SBOM.cdx.json, release-manifest.json,
runtime-dependencies.txt, market-dependencies.txt, and SHA256SUMS.

Verify every payload file before deployment:

sha256sum -c SHA256SUMS

Start the market server from this directory (repository/ and catalog.json are created
on first start):

java --enable-native-access=ALL-UNNAMED -jar cilexec-market-server.jar
"""
    (directory / "RELEASE.txt").write_text(release_text, encoding="utf-8")
    shutil.copy2(ROOT / "LICENSE", directory / "LICENSE")
    sbom = sbom_document(directory, identity["version"])
    (directory / SBOM_NAME).write_text(
        json.dumps(sbom, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (directory / NOTICES_NAME).write_text(third_party_notices(sbom), encoding="utf-8")

    files = payload_files(directory)
    if len([name for name in files if name.endswith(".db")]) < 1:
        fail("Release must contain at least one package")
    artifacts = {
        name: {"sha256": sha256(path), "size": path.stat().st_size}
        for name, path in sorted(files.items())
    }
    manifest = {"schemaVersion": 1, **identity, "artifacts": artifacts}
    (directory / MANIFEST_NAME).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def write_checksums(directory: Path) -> None:
    files = expected_files(directory)
    if len([name for name in files if name.endswith(".db")]) < 1:
        fail("Release must contain both JARs and at least one package")
    content = "".join(f"{sha256(files[name])}  {name}\n" for name in sorted(files))
    (directory / SHA256_NAME).write_text(content, encoding="ascii")


def verify_market_server(directory: Path) -> None:
    command(["java", "--enable-native-access=ALL-UNNAMED", "-jar",
             str(directory / "cilexec-market-server.jar"),
             "--repository", str(directory / "repository"),
             "--catalog", str(directory / "catalog.json"), "--check"])


def read_json_object(path: Path, description: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"Invalid {description}: {error}")
    if not isinstance(value, dict):
        fail(f"{description.capitalize()} must contain an object")
    return value


def verify_sbom_document(directory: Path, version: str) -> dict[str, Any]:
    document = read_json_object(directory / SBOM_NAME, "release SBOM")
    components_source = document.get("components")
    if not isinstance(components_source, list):
        fail("Release SBOM components must be an array")

    application_components: dict[str, dict[str, Any]] = {}
    application_dependencies: dict[str, list[str]] = {}
    expected_coordinates: set[str] = set()
    for jar_name, application in APPLICATIONS.items():
        application_name, inventory_name, _, _ = application
        embedded = jar_components(directory / jar_name)
        own = [item for item in embedded.values()
               if item["group"] == "com.follarce" and item["name"] == application_name]
        if len(own) != 1:
            fail(f"Cannot identify application component in {jar_name}")
        component = own[0]
        coordinate = f"{component['group']}:{component['name']}:{component['version']}"
        application_components[coordinate] = component
        expected_coordinates.add(coordinate)
        inventory = read_dependency_inventory(directory / inventory_name)
        expected_coordinates.update(inventory)
        application_dependencies[component["bom-ref"]] = inventory

    actual_components: dict[str, dict[str, Any]] = {}
    for source in components_source:
        if not isinstance(source, dict):
            fail("Release SBOM contains a non-object component")
        coordinate_values = (source.get("group"), source.get("name"), source.get("version"))
        if not all(isinstance(value, str) for value in coordinate_values):
            fail("Release SBOM component has an invalid coordinate")
        coordinate = ":".join(coordinate_values)
        expected_type = "application" if coordinate in application_components else "library"
        normalized = normalized_cdx_component(source, expected_type)
        if source != normalized or coordinate in actual_components:
            fail(f"Release SBOM component is noncanonical or duplicated: {coordinate}")
        actual_components[coordinate] = normalized
    if set(actual_components) != expected_coordinates:
        missing = sorted(expected_coordinates - set(actual_components))
        extra = sorted(set(actual_components) - expected_coordinates)
        fail(f"Release SBOM dependency set differs from Maven inventories; "
             f"missing={missing}, extra={extra}")
    for coordinate, expected in application_components.items():
        if actual_components[coordinate] != expected:
            fail(f"Release SBOM application component does not match its JAR: {coordinate}")

    application_refs = sorted(component["bom-ref"]
                              for component in application_components.values())
    dependency_entries = []
    for application_ref, inventory in application_dependencies.items():
        dependency_entries.append({
            "ref": application_ref,
            "dependsOn": sorted(actual_components[item]["bom-ref"] for item in inventory),
        })
    release_ref = maven_purl("com.follarce", "cilexec-release", version)
    expected_document = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "version": 1,
        "metadata": {
            "component": {
                "type": "application",
                "group": "com.follarce",
                "name": "cilexec-release",
                "version": version,
                "bom-ref": release_ref,
            }
        },
        "components": [actual_components[name] for name in sorted(actual_components)],
        "dependencies": ([{"ref": release_ref, "dependsOn": application_refs}]
                         + sorted(dependency_entries, key=lambda item: item["ref"])),
    }
    if document != expected_document:
        fail("Release SBOM structure or dependency graph is invalid")
    return document


def verify(directory: Path, identity: dict[str, Any] | None = None) -> None:
    runtime_manifest = jar_manifest(directory / "cilexec-app.jar")
    if runtime_manifest.get("Main-Class") != "com.follarce.Main":
        fail("Runtime JAR has the wrong entry point")
    market_manifest = jar_manifest(directory / "cilexec-market-server.jar")
    if market_manifest.get("Main-Class") != "com.follarce.market.server.MarketServerMain":
        fail("Market server JAR has the wrong entry point")

    release_manifest = read_json_object(directory / MANIFEST_NAME, "release manifest")
    manifest_identity = {name: release_manifest.get(name)
                         for name in ("version", "revision", "formal", "tag")}
    if not isinstance(manifest_identity["version"], str) \
            or not isinstance(manifest_identity["revision"], str) \
            or not isinstance(manifest_identity["formal"], bool) \
            or (manifest_identity["tag"] is not None
                and not isinstance(manifest_identity["tag"], str)):
        fail("Release manifest identity has invalid types")
    if release_manifest.get("schemaVersion") != 1:
        fail("Unsupported release manifest schema")
    if identity is not None and manifest_identity != identity:
        fail("Release manifest does not match the requested build identity")
    if manifest_identity["formal"]:
        validate_formal_release(manifest_identity["version"], manifest_identity["revision"],
                                manifest_identity["tag"], False)
    if runtime_manifest.get("Implementation-Version") != manifest_identity["version"]:
        fail("Runtime JAR version does not match the release manifest")
    if runtime_manifest.get("Build-Revision") != manifest_identity["revision"]:
        fail("Runtime JAR revision does not match the release manifest")
    if market_manifest.get("Implementation-Version") != manifest_identity["version"]:
        fail("Market JAR version does not match the release manifest")
    if market_manifest.get("Build-Revision") != manifest_identity["revision"]:
        fail("Market JAR revision does not match the release manifest")

    catalog = read_json_object(directory / "catalog.json", "release catalog")
    if not catalog:
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

    for name in ("MARKET.md", "RELEASE.txt", "LICENSE", NOTICES_NAME, SBOM_NAME):
        path = directory / name
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            fail(f"Missing or unsafe release document: {name}")
    if (directory / "LICENSE").read_bytes() != (ROOT / "LICENSE").read_bytes():
        fail("Release LICENSE does not match the project license")
    market_text = (directory / "MARKET.md").read_text(encoding="utf-8")
    if "cilexec-market-server.jar" not in market_text or "catalog.json" not in market_text:
        fail("MARKET.md does not describe the distributed market artifacts")
    release_text = (directory / "RELEASE.txt").read_text(encoding="utf-8")
    if "cilexec-app.jar" not in release_text or SHA256_NAME not in release_text:
        fail("RELEASE.txt does not describe release verification")
    if manifest_identity["version"] not in release_text \
            or any(coordinate not in release_text for coordinate in catalog):
        fail("RELEASE.txt does not match the release version and catalog")
    expected_sbom = verify_sbom_document(directory, manifest_identity["version"])
    if (directory / NOTICES_NAME).read_text(encoding="utf-8") \
            != third_party_notices(expected_sbom):
        fail("Third-party notices do not match the resolved Maven dependencies")

    files = payload_files(directory)
    expected_artifacts = {
        name: {"sha256": sha256(path), "size": path.stat().st_size}
        for name, path in sorted(files.items())
    }
    if release_manifest.get("artifacts") != expected_artifacts:
        fail("Release manifest does not describe exactly the release payload")

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
    # Replace the repository and catalog as one rollback-protected generated set.
    backup = Path(tempfile.mkdtemp(prefix=".release-backup-", dir=DIST))
    backed_up: list[str] = []
    installed: list[str] = []
    cleanup_backup = True
    try:
        for name in PUBLISHED_NAMES:
            destination = DIST / name
            if destination.exists() or destination.is_symlink():
                os.replace(destination, backup / name)
                backed_up.append(name)
        for name in PUBLISHED_NAMES:
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
    parser.add_argument("--formal", action="store_true",
                        help="require a clean, non-SNAPSHOT, tag-matched formal release")
    parser.add_argument("--tag", help="Git tag for --formal (must be v<project.version>)")
    arguments = parser.parse_args()
    if arguments.tag and not arguments.formal:
        parser.error("--tag requires --formal")
    if arguments.verify_only and arguments.skip_tests:
        parser.error("--verify-only and --skip-tests cannot be combined")
    if arguments.formal and arguments.skip_tests:
        parser.error("--formal cannot be combined with --skip-tests")
    return arguments


def main() -> int:
    arguments = parse_arguments()
    try:
        identity = release_identity(arguments.formal, arguments.tag)
        if arguments.verify_only:
            verify(DIST, identity if arguments.formal else None)
            return 0
        build_java(arguments.skip_tests, identity["revision"])
        if not arguments.skip_tests:
            command([sys.executable, str(ROOT / "tools" / "check_test_reports.py")])
        with tempfile.TemporaryDirectory(prefix="cilexec-release-", dir=ROOT / "build") as name:
            staging = Path(name)
            shutil.copy2(RUNTIME_JAR, staging / "cilexec-app.jar")
            shutil.copy2(MARKET_JAR, staging / "cilexec-market-server.jar")
            write_dependency_inventories(staging)
            build_packages(staging)
            write_release_metadata(staging, identity)
            write_checksums(staging)
            verify(staging, identity)
            publish(staging)
        verify(DIST, identity)
        print("Release complete: dist/", flush=True)
        return 0
    except (OSError, RuntimeError, subprocess.CalledProcessError, sqlite3.Error,
            json.JSONDecodeError, ValueError) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
