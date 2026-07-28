#!/usr/bin/env bash
set -euo pipefail

market_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$market_dir/.." && pwd)"
source_dir="$market_dir/sources/editor"
repository_dir="$market_dir/repository/packages/cilexec/editor/1.0.0"
application_jar="$project_dir/target/cilexec-app.jar"

if [[ ! -f "$application_jar" ]] \
        || [[ -n "$(find "$project_dir/src" -type f -newer "$application_jar" -print -quit)" ]]; then
    echo "Building the CilExec package tool..."
    mvn -f "$project_dir/pom.xml" --batch-mode --no-transfer-progress -DskipTests package
fi

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/cilexec-market.XXXXXX")"
cleanup() {
    rm -rf "$temporary_dir"
}
trap cleanup EXIT

java --enable-native-access=ALL-UNNAMED \
    -jar "$application_jar" package build "$source_dir" "$temporary_dir/editor.db"
mkdir -p "$repository_dir"
install -m 0644 "$temporary_dir/editor.db" "$repository_dir/editor.db"

echo "Published cilexec/editor/1.0.0:"
echo "  $repository_dir/editor.db"
