#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

revision="$(git rev-parse --short=12 HEAD 2>/dev/null || true)"
if [[ -z "$revision" ]]; then
    revision="unknown"
fi

mvn --batch-mode --no-transfer-progress -Dbuild.revision="$revision" clean verify
printf 'Created %s\n' "$project_dir/target/cilexec-app.jar"
