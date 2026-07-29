#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd -P)"
source_file="${1:-}"
vfs_target="${2:-}"
target_user="${3:-local}"

if [[ "$#" -lt 2 || "$#" -gt 3 || -z "$source_file" || -z "$vfs_target" \
        || "$vfs_target" != /* || "$vfs_target" == "/" ]]; then
    echo "Usage: ./HostMove.sh <host-file> <absolute-vfs-path> [username]" >&2
    echo "Example: ./HostMove.sh ./editor.db /editor.db local" >&2
    exit 64
fi
if [[ ! -f "$source_file" || -L "$source_file" ]]; then
    echo "Host source must be a regular non-symlink file: $source_file" >&2
    exit 66
fi
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    echo "Docker and Docker Compose are required." >&2
    exit 69
fi

source_file="$(cd "$(dirname "$source_file")" && pwd)/$(basename "$source_file")"
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
image_name="cilexec:${CILEXEC_IMAGE_TAG:-local}"
compose=(docker compose -f "$project_dir/compose.yml" \
    -f "$project_dir/docker/compose/persistent.yml")

bash "$project_dir/docker/create-secrets.sh" >/dev/null

if ! docker image inspect "$image_name" >/dev/null 2>&1; then
    echo "Building missing CilExec image $image_name..."
    "${compose[@]}" build
fi

if ! "${compose[@]}" ps postgres 2>/dev/null | grep -q 'Up'; then
    echo "Starting CilExec PostgreSQL..."
    "${compose[@]}" up -d --remove-orphans postgres
fi
"${compose[@]}" run --rm migrate

"${compose[@]}" run --rm --no-deps \
    --user "$(id -u):$(id -g)" \
    --volume "$source_file:/tmp/cilexec-host-import:ro" \
    cilexec host move /tmp/cilexec-host-import "$vfs_target" "$target_user"

echo "Copied host file into CilExec VFS; source retained: $source_file"
