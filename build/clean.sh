#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

# Runtime state belongs to PostgreSQL and is intentionally never deleted here.
exec mvn clean
