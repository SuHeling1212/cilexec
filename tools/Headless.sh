#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd -P)"
cd "$project_dir"

if [[ "$#" -ne 1 ]]; then
    echo 'Usage: ./Headless.sh <fcl-source>' >&2
    echo 'Example: ./Headless.sh '\''counter = 1'\''' >&2
    exit 2
fi
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    echo 'Error: Docker and the Docker Compose plugin are required.' >&2
    exit 1
fi

hash_text() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | cut -c1-64
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | cut -c1-64
    else
        echo 'Error: sha256sum or shasum is required.' >&2
        return 1
    fi
}

# Keep this byte-for-byte compatible with Install.sh, which uses echo and therefore
# includes a trailing newline in the project-directory hash. The Compose project name
# must match or headless mode would inspect a different Runtime project.
project_hash="$(printf '%s\n' "$project_dir" | hash_text)"
project_hash="${project_hash:0:8}"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
terminal_port="${CILEXEC_TERMINAL_PORT:-8022}"
username="${CILEXEC_TERMINAL_USERNAME:-local}"
compose=(docker compose -f compose.yml -f docker/compose/persistent.yml)

if [[ -z "$("${compose[@]}" ps --status running -q cilexec)" ]]; then
    echo 'Error: the shared CilExec Runtime is not running; run ./Install.sh first.' >&2
    exit 1
fi

if [[ -n "${CILEXEC_HEADLESS_CONTEXT:-}" ]]; then
    context="$CILEXEC_HEADLESS_CONTEXT"
else
    terminal_device="$(tty 2>/dev/null || true)"
    if [[ -z "$terminal_device" || "$terminal_device" == 'not a tty' ]]; then
        echo 'Error: no host terminal was detected; set CILEXEC_HEADLESS_CONTEXT explicitly.' >&2
        exit 2
    fi
    terminal_hash="$(printf '%s' "$terminal_device" | hash_text)"
    context="tty-${terminal_hash:0:32}"
fi

if [[ -t 0 && -r /dev/tty ]]; then
    IFS= read -r -s -p "${username} password> " password </dev/tty
    printf '\n' >/dev/tty
else
    IFS= read -r password
fi
source_code="$1"
printf '%s\n%s' "$password" "$source_code" \
    | "${compose[@]}" exec -T cilexec /usr/local/bin/cilexec-terminal-client \
        --headless "$terminal_port" "$context" "$username"
unset password source_code
