#!/usr/bin/env bash
set -euo pipefail

market_dir="$(cd "$(dirname "$0")" && pwd)"
port="${1:-8787}"

if [[ "$#" -gt 1 || ! "$port" =~ ^[0-9]+$ ]] \
        || (( 10#$port < 1 || 10#$port > 65535 )); then
    echo "Usage: ./market/start.sh [port from 1 to 65535]" >&2
    exit 64
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "Cannot start CilExec market: python3 is required." >&2
    exit 69
fi

bash "$market_dir/build.sh"

index_url="http://127.0.0.1:$port/market/v1/index.json"
if response="$(curl --silent --show-error --max-time 2 "$index_url" 2>/dev/null)" \
        && [[ "$response" == *'"apiVersion": "cilexec.market/v1"'* ]]; then
    echo "CilExec market is already running: $index_url"
    exit 0
fi

if ! python3 - "$port" <<'PY'
import socket
import sys

port = int(sys.argv[1])
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as connection:
    try:
        connection.bind(("0.0.0.0", port))
    except OSError:
        raise SystemExit(1)
PY
then
    echo "Cannot start CilExec market: port $port is already used by another program." >&2
    echo "Choose another port, for example: ./market/start.sh 8788" >&2
    exit 1
fi

project_dir="$(cd "$market_dir/.." && pwd -P)"
project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
project_name="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
allow_arguments=()
docker_subnets=0

# Permit only the current Compose network when it already exists. The market remains
# loopback-only when Docker is unavailable or CilExec has not created its network yet.
if command -v docker >/dev/null 2>&1; then
    network_name="${project_name}_default"
    while IFS= read -r subnet; do
        if [[ -n "$subnet" && "$subnet" != "<no value>" ]]; then
            allow_arguments+=(--allow-cidr "$subnet")
            docker_subnets=$((docker_subnets + 1))
        fi
    done < <(docker network inspect "$network_name" \
        --format '{{range .IPAM.Config}}{{println .Subnet}}{{end}}' 2>/dev/null || true)
fi

# Docker Desktop for macOS may NAT host-gateway traffic through its private VM network
# instead of preserving the Compose container address.
if [[ "$(uname -s)" == "Darwin" ]]; then
    allow_arguments+=(--allow-cidr "192.168.64.0/23")
elif command -v docker >/dev/null 2>&1 && [[ "$docker_subnets" -eq 0 ]]; then
    echo "Cannot securely expose the market to CilExec: Docker network" >&2
    echo "${project_name}_default does not exist. Run ./Install.sh first." >&2
    exit 1
fi

exec python3 "$market_dir/server.py" --bind 0.0.0.0 --port "$port" \
    "${allow_arguments[@]}"
