#!/usr/bin/env python3
"""Build one installable CilExec FCL package database without CilExec or extra JARs.

Usage:
    python3 PackageBuild.py <source-directory> <output.db>
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Any

try:
    from tools.fcl_check import FclSyntaxError, validate_module
except ImportError:  # pragma: no cover - depends on how the script is invoked
    from fcl_check import FclSyntaxError, validate_module


FORMAT_VERSION = 2
MAX_MANIFEST_BYTES = 1 * 1024 * 1024
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_PACKAGE_CONTENT_BYTES = 64 * 1024 * 1024

COMPONENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+\-]{0,127}\Z")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_.-]{0,127}\Z")
CAPABILITY = re.compile(r"[a-z][a-z0-9_.:-]{0,127}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")
FUNCTION = re.compile(r"\bfunc\s+([A-Za-z_][A-Za-z0-9_.-]{0,127})\s*\(([^)]*)\)")


def fail(message: str) -> None:
    raise ValueError(message)


def text(value: Any, label: str, maximum: int | None = None) -> str:
    if not isinstance(value, str) or not value.strip() or any(ord(ch) < 32 for ch in value):
        fail(f"{label} is required")
    if maximum is not None and len(value) > maximum:
        fail(f"{label} is too long")
    return value


def identifier(value: Any, label: str) -> str:
    value = text(value, label)
    if not IDENTIFIER.fullmatch(value):
        fail(f"Unsupported {label}: {value}")
    return value


def relative_path(value: Any, label: str) -> str:
    value = text(value, label, 1024).replace("\\", "/")
    if value.startswith("/") or value.endswith("/"):
        fail(f"{label} must be package-relative: {value}")
    for part in value.split("/"):
        if not part or part in (".", "..") or len(part) > 255:
            fail(f"{label} is not canonical: {value}")
    return value


def require_list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        fail(f"{label} must be an array")
    return value


def unique(values: list[str], label: str) -> None:
    if len(values) != len(set(values)):
        fail(f"Duplicate {label}")


def read_content(root: Path, logical_path: str) -> bytes:
    candidate = root / logical_path
    try:
        actual = candidate.resolve(strict=True)
    except OSError as error:
        fail(f"Cannot read package content {logical_path}: {error}")
    if not actual.is_file() or root not in actual.parents:
        fail(f"Package content escapes the source directory: {logical_path}")
    size = actual.stat().st_size
    if size > MAX_FILE_BYTES:
        fail(f"Package file exceeds 16 MiB: {logical_path}")
    return actual.read_bytes()


def function_definitions(source: bytes, logical_path: str) -> dict[str, int]:
    try:
        decoded = source.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        fail(f"Module is not valid UTF-8 ({logical_path}): {error}")
    # Full syntax validation (mirrors FclCompiler.java) so an un-compilable module can
    # never be packaged and shipped; the regex below only extracts the declaration
    # surface for the manifest, it is not a substitute for parsing.
    try:
        validate_module(decoded)
    except FclSyntaxError as error:
        fail(f"Module is not valid FCL ({logical_path}): {error}")
    result: dict[str, int] = {}
    for match in FUNCTION.finditer(decoded):
        name, parameters = match.groups()
        if name in result:
            fail(f"Duplicate function declaration in {logical_path}: {name}")
        stripped = parameters.strip()
        result[name] = 0 if not stripped else len([item for item in stripped.split(",") if item.strip()])
    return result


def normalize_manifest(source: bytes, root: Path) -> tuple[dict[str, str], list[tuple[str, bytes]],
                                                             list[tuple[str, str, str]],
                                                             list[tuple[str, int]],
                                                             list[tuple[str, str, str]],
                                                             list[tuple[str, str, str]],
                                                             list[tuple[str, int, str]]]:
    if len(source) > MAX_MANIFEST_BYTES:
        fail("package.json exceeds the 1 MiB limit")
    try:
        manifest = json.loads(source.decode("utf-8", errors="strict"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"Invalid package.json: {error}")
    if not isinstance(manifest, dict):
        fail("package.json must contain an object")

    namespace = text(manifest.get("namespace"), "namespace")
    name = text(manifest.get("name"), "name")
    version = text(manifest.get("version"), "version")
    if not COMPONENT.fullmatch(namespace): fail(f"Unsupported namespace: {namespace}")
    if not COMPONENT.fullmatch(name): fail(f"Unsupported name: {name}")
    if not VERSION.fullmatch(version): fail(f"Unsupported version: {version}")
    language = text(manifest.get("languageVersion"), "languageVersion", 128)
    kind = text(manifest.get("kind"), "package kind").lower()
    if kind not in ("application", "library"):
        fail(f"Unsupported package kind: {kind}")

    modules: list[tuple[str, str]] = []
    for item in require_list(manifest.get("modules"), "modules"):
        if not isinstance(item, dict): fail("module must be an object")
        modules.append((identifier(item.get("name"), "module name"),
                        relative_path(item.get("path"), "module path")))
    if not modules: fail("At least one module is required")
    unique([item[0] for item in modules], "module name")

    resources = [relative_path(item, "resource")
                 for item in require_list(manifest.get("resources", []), "resources")]
    content_paths = [item[1] for item in modules] + resources
    unique(content_paths, "module/resource path")

    contents = [(path, read_content(root, path)) for path in content_paths]
    if sum(len(content) for _, content in contents) > MAX_PACKAGE_CONTENT_BYTES:
        fail("Package content exceeds the 64 MiB package limit")
    definitions = {module: function_definitions(dict(contents)[path], path)
                   for module, path in modules}

    dependencies: list[tuple[str, int]] = []
    for item in require_list(manifest.get("dependencies", []), "dependencies"):
        if not isinstance(item, dict): fail("dependency must be an object")
        digest = text(item.get("sha256"), "dependency sha256").lower()
        if not SHA256.fullmatch(digest): fail("Dependency sha256 must be 64 lowercase hex characters")
        if not isinstance(item.get("optional"), bool): fail("dependency optional must be boolean")
        dependencies.append((digest, int(item["optional"])))
    unique([item[0] for item in dependencies], "dependency SHA-256")

    entrypoints: list[tuple[str, str, str]] = []
    for item in require_list(manifest.get("entrypoints", []), "entrypoints"):
        if not isinstance(item, dict): fail("entrypoint must be an object")
        entrypoint = (identifier(item.get("name"), "entrypoint name"),
                      identifier(item.get("module"), "entrypoint module"),
                      identifier(item.get("function"), "entrypoint function"))
        if entrypoint[1] not in definitions or entrypoint[2] not in definitions[entrypoint[1]]:
            fail(f"Entrypoint function is missing: {entrypoint[1]}.{entrypoint[2]}")
        if definitions[entrypoint[1]][entrypoint[2]] != 0:
            fail(f"Package entrypoint must not require arguments: {entrypoint[0]}")
        entrypoints.append(entrypoint)
    unique([item[0] for item in entrypoints], "entrypoint name")
    if kind == "application" and "run" not in {item[0] for item in entrypoints}:
        fail("Application packages must declare the universal run entrypoint")

    exports: list[tuple[str, str, str]] = []
    for item in require_list(manifest.get("exports", []), "exports"):
        if not isinstance(item, dict): fail("export must be an object")
        exported = (identifier(item.get("name"), "export name"),
                    identifier(item.get("module"), "export module"),
                    identifier(item.get("symbol"), "export symbol"))
        if exported[1] not in definitions or exported[2] not in definitions[exported[1]]:
            fail(f"Exported function is missing: {exported[1]}.{exported[2]}")
        exports.append(exported)
    unique([item[0] for item in exports], "export name")

    capabilities: list[tuple[str, int, str]] = []
    for item in require_list(manifest.get("capabilities", []), "capabilities"):
        if not isinstance(item, dict): fail("capability must be an object")
        key = text(item.get("key"), "capability key")
        if not CAPABILITY.fullmatch(key): fail(f"Unsupported capability key: {key}")
        if not isinstance(item.get("required"), bool): fail("capability required must be boolean")
        rationale = item.get("rationale", "")
        if not isinstance(rationale, str) or len(rationale) > 4096 or any(ord(ch) < 32 for ch in rationale):
            fail("capability rationale is invalid")
        capabilities.append((key, int(item["required"]), rationale))
    unique([item[0] for item in capabilities], "capability key")

    metadata = {"namespace": namespace, "name": name, "version": version,
                "language_version": language, "package_kind": kind}
    module_rows = [(module, path, hashlib.sha256(dict(contents)[path]).hexdigest())
                   for module, path in modules]
    return metadata, contents, module_rows, dependencies, entrypoints, exports, capabilities


def build(source_directory: Path, output: Path) -> tuple[str, str]:
    root = source_directory.resolve(strict=True)
    if not root.is_dir(): fail("Package source is not a directory")
    manifest_path = root / "package.json"
    if not manifest_path.is_file(): fail("Package source has no package.json")
    output = output.absolute()
    if output.suffix != ".db": fail("Package output must end with .db")
    if output.exists(): fail(f"Package output already exists: {output}")
    if not output.parent.is_dir(): fail(f"Package output directory does not exist: {output.parent}")

    metadata, contents, modules, dependencies, entrypoints, exports, capabilities = normalize_manifest(
        manifest_path.read_bytes(), root)
    descriptor = f"{metadata['namespace']}/{metadata['name']}/{metadata['version']}"
    fd, temporary_name = tempfile.mkstemp(prefix=".cilexec-package-", suffix=".tmp", dir=output.parent)
    os.close(fd)
    temporary = Path(temporary_name)
    try:
        connection = sqlite3.connect(temporary)
        try:
            cursor = connection.cursor()
            cursor.execute("PRAGMA page_size=4096")
            cursor.execute("PRAGMA journal_mode=OFF")
            cursor.execute("PRAGMA synchronous=OFF")
            cursor.execute(f"PRAGMA user_version={FORMAT_VERSION}")
            cursor.executescript("""
                CREATE TABLE package_metadata(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL) WITHOUT ROWID;
                CREATE TABLE package_file(file_path TEXT PRIMARY KEY, content BLOB NOT NULL) WITHOUT ROWID;
                CREATE TABLE package_module(module_name TEXT PRIMARY KEY, module_object_path TEXT NOT NULL UNIQUE, module_hash TEXT NOT NULL) WITHOUT ROWID;
                CREATE TABLE package_dependency(dependency_file_hash TEXT PRIMARY KEY, optional INTEGER NOT NULL) WITHOUT ROWID;
                CREATE TABLE package_entrypoint(entrypoint_name TEXT PRIMARY KEY, module_name TEXT NOT NULL, function_name TEXT NOT NULL) WITHOUT ROWID;
                CREATE TABLE package_export(export_name TEXT PRIMARY KEY, module_name TEXT NOT NULL, symbol_name TEXT NOT NULL) WITHOUT ROWID;
                CREATE TABLE package_capability(capability_key TEXT PRIMARY KEY, required INTEGER NOT NULL, rationale TEXT NOT NULL) WITHOUT ROWID;
            """)
            cursor.executemany("INSERT INTO package_metadata VALUES (?, ?)", sorted(metadata.items()))
            cursor.executemany("INSERT INTO package_file VALUES (?, ?)", sorted(contents))
            cursor.executemany("INSERT INTO package_module VALUES (?, ?, ?)", sorted(modules))
            cursor.executemany("INSERT INTO package_dependency VALUES (?, ?)", sorted(dependencies))
            cursor.executemany("INSERT INTO package_entrypoint VALUES (?, ?, ?)", sorted(entrypoints))
            cursor.executemany("INSERT INTO package_export VALUES (?, ?, ?)", sorted(exports))
            cursor.executemany("INSERT INTO package_capability VALUES (?, ?, ?)", sorted(capabilities))
            connection.commit()
            cursor.execute("VACUUM")
        finally:
            connection.close()
        if temporary.stat().st_size > MAX_PACKAGE_CONTENT_BYTES:
            fail("Built package database exceeds the 64 MiB installation limit")
        database_hash = hashlib.sha256(temporary.read_bytes()).hexdigest()
        os.replace(temporary, output)
        return descriptor, database_hash
    finally:
        if temporary.exists(): temporary.unlink()


def main(arguments: list[str]) -> int:
    if len(arguments) != 2 or arguments[0] in ("-h", "--help"):
        print("Usage: python3 PackageBuild.py <source-directory> <output.db>")
        return 0 if arguments and arguments[0] in ("-h", "--help") else 64
    try:
        coordinate, digest = build(Path(arguments[0]), Path(arguments[1]))
    except (OSError, ValueError, sqlite3.Error) as error:
        print(f"Package build failed: {error}", file=sys.stderr)
        return 1
    print(f"Built package {coordinate} at {Path(arguments[1]).absolute()}")
    print(f"File SHA-256: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
