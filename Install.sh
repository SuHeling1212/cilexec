#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

# Use a unique project name per install directory so volumes and networks
# don't conflict with other CilExec installations on the same machine.
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="cilexec-${project_hash}"
export CILEXEC_POSTGRES_VOLUME="cilexec-pgdata-${project_hash}"

if ! command -v docker >/dev/null 2>&1; then
    echo "Error: Docker not found. Please install and start Docker Desktop." >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "Error: Docker Compose plugin is not available." >&2
    exit 1
fi

secret_dir="$project_dir/docker/secrets"
mkdir -p "$secret_dir"

create_internal_secret() {
    local destination="$1"
    if [[ -s "$destination" ]]; then
        return
    fi
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 > "$destination"
    else
        printf 'cilexec-internal-%s-%s-%s\n' "$RANDOM" "$RANDOM" "$RANDOM" > "$destination"
    fi
    chmod 600 "$destination"
}

create_internal_secret "$secret_dir/postgres-admin-password"
create_internal_secret "$secret_dir/cilexec-migrator-password"
create_internal_secret "$secret_dir/cilexec-runtime-password"
create_internal_secret "$secret_dir/cilexec-effect-worker-password"
create_internal_secret "$secret_dir/cilexec-readonly-password"

compose=(docker compose -f compose.yml -f compose.persistent.yml)

cleanup() {
    "${compose[@]}" down >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Starting CilExec..."
"${compose[@]}" up -d postgres

# Build the image only when source code is present (development mode).
# Pre-built distributions already include the image via docker load.
if [ -d "$project_dir/src" ]; then
    echo "Image cilexec:local Building"
    "${compose[@]}" build
    echo "Image cilexec:local Built"
fi

"${compose[@]}" run --rm migrate

echo
echo "On first use you will be prompted to create the administrator password."
echo "Choose login and enter username local with the password you set."
echo "Type :exit to quit."
echo

"${compose[@]}" run --rm --no-deps cilexec
