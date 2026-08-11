#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd -P)"
cd "$project_dir"

# Use a unique project name per install directory so volumes and networks
# don't conflict with other CilExec installations on the same machine.
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
image_name="cilexec:${CILEXEC_IMAGE_TAG:-local}"
rebuild=false

if [[ "$#" -gt 1 ]]; then
    echo "Error: too many arguments." >&2
    echo "Usage: ./Install.sh [--rebuild]" >&2
    exit 2
fi

case "${1:-}" in
    "") ;;
    --rebuild) rebuild=true ;;
    --help|-h)
        echo "Usage: ./Install.sh [--rebuild]"
        echo "  --rebuild  rebuild the shared application image before opening this terminal"
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

bash "$project_dir/docker/create-secrets.sh" >/dev/null
# Permit HTTP downloads only from the market origin, without allowing arbitrary
# socket access to the Docker host or other private-network services.
market_port="${CILEXEC_MARKET_PORT:-8787}"
if [[ ! "$market_port" =~ ^[0-9]+$ ]] \
        || (( 10#$market_port < 1 || 10#$market_port > 65535 )); then
    echo "Error: CILEXEC_MARKET_PORT must be an integer from 1 to 65535." >&2
    exit 2
fi
export CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS="${CILEXEC_NETWORK_ALLOW_PRIVATE_HTTP_ORIGINS:-http://host.docker.internal:${market_port}}"

compose=(docker compose -f compose.yml -f docker/compose/persistent.yml)

echo "Starting CilExec..."
"${compose[@]}" up -d postgres
runtime_running=false
if [[ -n "$("${compose[@]}" ps --status running -q cilexec)" ]]; then
    runtime_running=true
fi

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

if [[ "$rebuild" == true && "$runtime_running" == true ]]; then
    echo "Stopping the shared Runtime to activate the rebuilt image..."
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
"${compose[@]}" exec cilexec /usr/local/bin/cilexec-terminal-client "$terminal_port"

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
