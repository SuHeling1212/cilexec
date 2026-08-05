#!/usr/bin/env bash
set -euo pipefail

# Compile FCL source into its flat instruction program (bytecode JSON).
# Usage: ./tools/fcl_compile.sh <source.fcl> [<output.json>]

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
exec java -jar "$project_dir/dist/cilexec-app.jar" compile "$@"
