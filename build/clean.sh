#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

# Maven preserves target/cilexec-data, which may hold the standalone JAR's database.
exec mvn clean
