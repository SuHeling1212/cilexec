#!/usr/bin/env bash
set -euo pipefail

# Compile FCL source into an FCLB executable artifact.
# Usage: ./tools/fcl_compile.sh <source.fcl> [<output.fclb>]

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
exec java -jar "$project_dir/dist/cilexec-app.jar" compile "$@"
