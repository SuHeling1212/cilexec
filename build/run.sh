#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

if [[ ! -f target/cilexec-app.jar ]]; then
    "$project_dir/build/package.sh"
fi

exec java ${JVM_OPTIONS:-} -jar target/cilexec-app.jar "${1:-runtime}"
