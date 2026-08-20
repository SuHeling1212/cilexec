#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$project_dir"

if [[ -L "$project_dir/.env" ]]; then
    echo "Error: .env must not be a symbolic link." >&2
    exit 1
fi

# Use a unique project name per install directory so volumes and networks
# don't conflict with other CilExec installations on the same machine.
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
# Read a key from the project .env without overriding an already-exported
# environment variable. This mirrors docker compose precedence:
# exported environment > .env file > script default.
dotenv_value() {
    local sought="$1"
    local line
    while IFS= read -r line || [[ -n "$line" ]]; do
        if [[ "$line" == "$sought="* ]]; then
            printf '%s' "${line#*=}"
            return 0
        fi
    done < "$project_dir/.env"
    return 1
}
dotenv_or_env() {
    local name="$1"
    local value="${!name:-}"
    if [[ -z "$value" ]]; then
        value="$(dotenv_value "$name" 2>/dev/null || true)"
    fi
    printf '%s' "$value"
}
project_hash="$(printf '%s\n' "$project_dir" | hash_text)"
project_hash="${project_hash:0:8}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
rebuild=false

if [[ "$#" -gt 1 ]]; then
    echo "Error: too many arguments." >&2
    echo "Usage: ./tools/Install.sh [--rebuild]" >&2
    exit 2
fi

case "${1:-}" in
    "") ;;
    --rebuild) rebuild=true ;;
    --help|-h)
        echo "Usage: ./tools/Install.sh [--rebuild]"
        echo "  --rebuild  rebuild the shared application image before opening this terminal"
        exit 0
        ;;
    *)
        echo "Error: unknown option: $1" >&2
        echo "Usage: ./tools/Install.sh [--rebuild]" >&2
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

compose=(docker compose -f compose.yml -f docker/compose/persistent.yml)
compose_environment="$("${compose[@]}" config --environment)"
compose_environment_value() {
    local sought="$1"
    local line
    while IFS= read -r line; do
        if [[ "${line%%=*}" == "$sought" ]]; then
            printf '%s' "${line#*=}"
            return
        fi
    done <<< "$compose_environment"
}

configured_image="$(compose_environment_value CILEXEC_IMAGE)"
configured_uid="$(compose_environment_value CILEXEC_CONTAINER_UID)"
configured_gid="$(compose_environment_value CILEXEC_CONTAINER_GID)"
if [[ -z "${CILEXEC_IMAGE:-}" && -n "$configured_image" ]]; then
    export CILEXEC_IMAGE="$configured_image"
fi
if [[ -z "${CILEXEC_CONTAINER_UID:-}" && -n "$configured_uid" ]]; then
    export CILEXEC_CONTAINER_UID="$configured_uid"
fi
if [[ -z "${CILEXEC_CONTAINER_GID:-}" && -n "$configured_gid" ]]; then
    export CILEXEC_CONTAINER_GID="$configured_gid"
fi

host_uid="$(id -u)"
host_gid="$(id -g)"
if [[ "$host_uid" -eq 0 ]]; then
    host_uid=10001
    host_gid=10001
fi
export CILEXEC_CONTAINER_UID="${CILEXEC_CONTAINER_UID:-$host_uid}"
export CILEXEC_CONTAINER_GID="${CILEXEC_CONTAINER_GID:-$host_gid}"
if [[ ! "$CILEXEC_CONTAINER_UID" =~ ^[0-9]+$ \
        || ! "$CILEXEC_CONTAINER_GID" =~ ^[0-9]+$ ]] \
        || (( 10#$CILEXEC_CONTAINER_UID == 0 || 10#$CILEXEC_CONTAINER_GID == 0 )); then
    echo "Error: CILEXEC_CONTAINER_UID/GID must be positive numeric IDs." >&2
    exit 2
fi
touch "$project_dir/.env"
chmod 600 "$project_dir/.env"
if [[ -z "$configured_uid" ]]; then
    printf 'CILEXEC_CONTAINER_UID=%s\n' "$CILEXEC_CONTAINER_UID" >> "$project_dir/.env"
fi
if [[ -z "$configured_gid" ]]; then
    printf 'CILEXEC_CONTAINER_GID=%s\n' "$CILEXEC_CONTAINER_GID" >> "$project_dir/.env"
fi

image_name="${CILEXEC_IMAGE:-cilexec:local}"
bash "$project_dir/docker/create-secrets.sh" >/dev/null
# Permit HTTP downloads only from the market origin, without allowing arbitrary
# socket access to the Docker host or other private-network services.
market_port="${CILEXEC_MARKET_PORT:-8787}"
if [[ ! "$market_port" =~ ^[0-9]+$ ]] \
        || (( 10#$market_port < 1 || 10#$market_port > 65535 )); then
    echo "Error: CILEXEC_MARKET_PORT must be an integer from 1 to 65535." >&2
    exit 2
fi
CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS="$(dotenv_or_env CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS)"
export CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS
if [[ -z "$CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS" ]]; then
    export CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS="http://host.docker.internal:${market_port}"
fi
CILEXEC_NETWORK_ALLOW_PRIVATE_HOSTS="$(dotenv_or_env CILEXEC_NETWORK_ALLOW_PRIVATE_HOSTS)"
export CILEXEC_NETWORK_ALLOW_PRIVATE_HOSTS

echo "Starting CilExec..."
"${compose[@]}" up -d postgres
runtime_running=false
runtime_active=false
runtime_state=""
runtime_container="$("${compose[@]}" ps -a -q cilexec)"
if [[ -n "$runtime_container" ]]; then
    runtime_state="$(docker inspect --format '{{.State.Status}}' "$runtime_container")"
fi
if [[ "$runtime_state" == "running" ]]; then
    runtime_running=true
fi
case "$runtime_state" in
    running|paused|restarting) runtime_active=true ;;
esac

if [[ "$rebuild" == true ]]; then
    if [[ ! -d "$project_dir/src" ]]; then
        echo "Error: --rebuild requires a source distribution." >&2
        exit 1
    fi
    echo "Building image $image_name..."
    "${compose[@]}" build
    echo "Image $image_name built."
elif [[ -n "${CILEXEC_IMAGE:-}" && "$image_name" != "cilexec:local" ]]; then
    echo "Pulling release image $image_name..."
    docker pull "$image_name"
elif ! docker image inspect "$image_name" >/dev/null 2>&1; then
    if [[ -d "$project_dir/src" ]]; then
        echo "Building image $image_name..."
        "${compose[@]}" build
        echo "Image $image_name built."
    else
        echo "Error: release image $image_name is unavailable." >&2
        exit 1
    fi
else
    echo "Reusing image $image_name (use --rebuild to rebuild it)."
fi

target_image_id="$(docker image inspect --format '{{.Id}}' "$image_name")"
running_image_id=""
if [[ "$runtime_active" == true ]]; then
    running_image_id="$(docker inspect --format '{{.Image}}' "$runtime_container")"
fi
if [[ "$runtime_active" == true \
        && ( "$runtime_state" != "running" || "$rebuild" == true \
             || "$running_image_id" != "$target_image_id" ) ]]; then
    echo "Stopping the shared Runtime to migrate before activating image $image_name..."
    if [[ "$runtime_state" == "paused" ]]; then
        docker unpause "$runtime_container" >/dev/null
    fi
    "${compose[@]}" stop cilexec
    runtime_running=false
fi

if [[ "$runtime_running" != true ]]; then
    "${compose[@]}" run --rm migrate
else
    echo "Shared Runtime is already running; skipping migration JVM startup."
fi

echo
echo "On first use you will be prompted to create the administrator password."
echo "Choose login and enter username ${CILEXEC_TERMINAL_USERNAME:-local} with the password you set."
echo "Type :exit to quit."
echo

# One persistent JVM owns the bounded worker pools. Host terminals are lightweight raw byte
# bridges into independent authenticated connections; they never start another Runtime/JVM.
terminal_port="${CILEXEC_TERMINAL_PORT:-8022}"
if [[ ! "$terminal_port" =~ ^[0-9]+$ ]] \
        || (( 10#$terminal_port < 1 || 10#$terminal_port > 65535 )); then
    echo "Error: CILEXEC_TERMINAL_PORT must be an integer from 1 to 65535." >&2
    exit 2
fi
"${compose[@]}" up -d --no-deps cilexec

terminal_ready=false
for _ in {1..60}; do
    if "${compose[@]}" exec -T cilexec /usr/local/bin/cilexec-terminal-client \
            --probe "$terminal_port" >/dev/null 2>&1; then
        terminal_ready=true
        break
    fi
    sleep 0.25
done
if [[ "$terminal_ready" != true ]]; then
    echo "Error: shared CilExec Runtime did not open terminal port $terminal_port." >&2
    "${compose[@]}" logs --tail=80 cilexec >&2 || true
    exit 1
fi

echo "Connecting to the shared CilExec Runtime."
terminal_context="${CILEXEC_TERMINAL_CONTEXT:-}"
if [[ -z "$terminal_context" ]]; then
    terminal_tty="$(tty 2>/dev/null || true)"
    if [[ -z "$terminal_tty" || "$terminal_tty" == "not a tty" ]]; then
        echo "Error: an interactive terminal is required to establish a durable terminal session." >&2
        exit 2
    fi
    terminal_context="host-${project_hash}-$(printf '%s\0%s\n' "$project_dir" "$terminal_tty" | hash_text)"
fi
if [[ ! "$terminal_context" =~ ^[A-Za-z0-9._:-]{1,128}$ ]]; then
    echo "Error: CILEXEC_TERMINAL_CONTEXT must contain 1-128 letters, digits, ., _, :, or -." >&2
    exit 2
fi
"${compose[@]}" exec cilexec /usr/local/bin/cilexec-terminal-client \
    --session "$terminal_context" "$terminal_port"

runtime_stopped=false
for _ in {1..12}; do
    if [[ -z "$("${compose[@]}" ps --status running -q cilexec)" ]]; then
        runtime_stopped=true
        break
    fi
    sleep 0.25
done
if [[ "$runtime_stopped" == true ]]; then
    echo "CilExec Runtime shut down."
else
    echo "Terminal disconnected. The shared Runtime and background processes are still running."
fi
