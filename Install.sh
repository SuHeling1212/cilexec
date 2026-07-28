#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

# Use a unique project name per install directory so volumes and networks
# don't conflict with other CilExec installations on the same machine.
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
image_name="cilexec:${CILEXEC_IMAGE_TAG:-local}"
terminal_container="${COMPOSE_PROJECT_NAME}-terminal"
rebuild=false

case "${1:-}" in
    "") ;;
    --rebuild) rebuild=true ;;
    --help|-h)
        echo "Usage: ./Install.sh [--rebuild]"
        echo "  --rebuild  rebuild the image and recreate the persistent terminal container"
        exit 0
        ;;
    *)
        echo "Error: unknown option: $1" >&2
        echo "Usage: ./Install.sh [--rebuild]" >&2
        exit 2
        ;;
esac

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

echo "Starting CilExec..."
"${compose[@]}" up -d postgres

if [[ "$rebuild" == true ]] || ! docker image inspect "$image_name" >/dev/null 2>&1; then
    if [[ ! -d "$project_dir/src" ]]; then
        echo "Error: image $image_name is missing and this distribution has no source to build it." >&2
        exit 1
    fi
    echo "Building image $image_name..."
    "${compose[@]}" build
    echo "Image $image_name built."
else
    echo "Reusing image $image_name (use --rebuild to rebuild it)."
fi

"${compose[@]}" run --rm migrate

echo
echo "On first use you will be prompted to create the administrator password."
echo "Choose login and enter username ${CILEXEC_TERMINAL_USERNAME:-local} with the password you set."
echo "Type :exit to quit."
echo

recreate_terminal="$rebuild"
if docker container inspect "$terminal_container" >/dev/null 2>&1; then
    container_image_id="$(docker container inspect --format '{{.Image}}' "$terminal_container")"
    current_image_id="$(docker image inspect --format '{{.Id}}' "$image_name")"
    if [[ "$container_image_id" != "$current_image_id" ]]; then
        recreate_terminal=true
    fi
fi

if [[ "$recreate_terminal" == true ]] \
        && docker container inspect "$terminal_container" >/dev/null 2>&1; then
    if [[ "$(docker container inspect --format '{{.State.Running}}' "$terminal_container")" == true ]]; then
        echo "Error: terminal container $terminal_container is already running." >&2
        exit 1
    fi
    docker container rm "$terminal_container" >/dev/null
fi

if docker container inspect "$terminal_container" >/dev/null 2>&1; then
    echo "Reusing terminal container $terminal_container."
    docker start --attach --interactive "$terminal_container"
else
    echo "Creating persistent terminal container $terminal_container."
    "${compose[@]}" run --name "$terminal_container" --no-deps cilexec
fi

echo "CilExec terminal stopped. PostgreSQL and the terminal container were kept for fast restart."
