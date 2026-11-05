#!/usr/bin/env python3
"""Derive verified OS-specific Runtime JARs from the shaded general JAR."""

from __future__ import annotations

import argparse
import hashlib
import shutil
import zipfile
from pathlib import Path

from versioning import project_version


NATIVE = {
    "postgres-linux-x86_64.txz": "linux",
    "postgres-linux-arm_64.txz": "linux",
    "postgres-windows-x86_64.txz": "windows",
    "postgres-darwin-x86_64.txz": "macos",
    "postgres-darwin-arm_64.txz": "macos",
}


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def build(source: Path, destination: Path, platform: str) -> None:
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(destination, "w") as output:
        for entry in original.infolist():
            if entry.filename in NATIVE and NATIVE[entry.filename] != platform:
                continue
            output.writestr(entry, original.read(entry.filename))
    with zipfile.ZipFile(destination) as result:
        actual = {name for name in result.namelist() if name in NATIVE}
        expected = {name for name, owner in NATIVE.items() if owner == platform}
        if actual != expected:
            raise RuntimeError(f"{destination}: native payload mismatch: {actual} != {expected}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("target/cilexec-app.jar"))
    parser.add_argument("--output-dir", type=Path, default=Path("build/platform-jars"))
    args = parser.parse_args()
    version = project_version()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.input) as original:
        actual = {name for name in original.namelist() if name in NATIVE}
        if actual != set(NATIVE):
            raise RuntimeError(f"General JAR native payload mismatch: {actual} != {set(NATIVE)}")
    general = args.output_dir / f"cilexec-{version}-general.jar"
    shutil.copy2(args.input, general)
    for platform in ("linux", "windows", "macos"):
        build(args.input, args.output_dir / f"cilexec-{version}-{platform}.jar", platform)
    files = sorted(args.output_dir.glob(f"cilexec-{version}-*.jar"))
    (args.output_dir / "SHA256SUMS").write_text(
        "".join(f"{digest(path)}  {path.name}\n" for path in files), encoding="ascii")
    for path in files:
        print(f"{path}: {path.stat().st_size} bytes, SHA-256 {digest(path)}")


if __name__ == "__main__":
    main()
