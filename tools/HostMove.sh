#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
export CILEXEC_BUILD_VERSION="${CILEXEC_BUILD_VERSION:-$("$project_dir/tools/Version.sh")}"
if [[ -z "${CILEXEC_IMAGE:-}" && -f "$project_dir/.env" ]]; then
    while IFS='=' read -r key value; do
        if [[ "$key" == "CILEXEC_IMAGE" && "$value" =~ ^[A-Za-z0-9._/:@-]+$ ]]; then
            export CILEXEC_IMAGE="$value"
        fi
    done < "$project_dir/.env"
fi
source_file="${1:-}"
vfs_target="${2:-}"
target_user="${3:-}"

if [[ "$#" -ne 3 || -z "$source_file" || -z "$vfs_target" || -z "$target_user" \
        || "$vfs_target" != /* || "$vfs_target" == "/" ]]; then
    echo "Usage: ./tools/HostMove.sh <host-file> <absolute-vfs-path> <username>" >&2
    echo "Example: ./tools/HostMove.sh ./editor.db /editor.db alice" >&2
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
hash_text() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | cut -c1-64
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | cut -c1-64
    else
        echo "Error: sha256sum or shasum is required." >&2
        return 1
    fi
}
project_hash="$(printf '%s\n' "$project_dir" | hash_text)"
project_hash="${project_hash:0:8}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
image_name="${CILEXEC_IMAGE:-cilexec:local}"
compose=(docker compose -f "$project_dir/compose.yml" \
    -f "$project_dir/docker/compose/persistent.yml")

bash "$project_dir/docker/create-secrets.sh" >/dev/null

if ! docker image inspect "$image_name" >/dev/null 2>&1; then
    if [[ -n "${CILEXEC_IMAGE:-}" && "$image_name" != "cilexec:local" ]]; then
        docker pull "$image_name"
    elif [[ -d "$project_dir/src" ]]; then
        echo "Building missing CilExec image $image_name..."
        "${compose[@]}" build
    else
        echo "Error: release image $image_name is unavailable." >&2
        exit 1
    fi
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
