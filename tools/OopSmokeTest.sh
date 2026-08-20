#!/usr/bin/env bash
set -euo pipefail

# Imports the published FCL object-oriented smoke test into local's VFS root,
# then runs it through an isolated durable terminal session.
project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
source_file="$project_dir/docs/examples/fcl-oop-smoke-test.fcl"
destination="/fcl-oop-smoke-test.fcl"

if [[ ! -r "$source_file" ]]; then
    echo "Error: missing FCL smoke test source: $source_file" >&2
    exit 1
fi

# FCL strings use backslash escapes. Build one string literal without evaluating
# the test source as shell code.
fcl_content=""
while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line//\\/\\\\}"
    line="${line//\"/\\\"}"
    fcl_content+="${line}\\n"
done < "$source_file"

submission="file.write(\"$destination\", \"$fcl_content\")"
submission+=$'\n'
submission+="process.exec(\"$destination\")"

# A fresh context avoids changing the caller's usual interactive terminal state.
context="oop-smoke-$(date +%s)-$$"
echo "Enter the local account password when prompted."
CILEXEC_HEADLESS_CONTEXT="$context" "$project_dir/tools/Headless.sh" "$submission"
