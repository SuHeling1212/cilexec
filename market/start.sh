#!/usr/bin/env bash
set -euo pipefail

market_dir="$(cd "$(dirname "$0")" && pwd)"
port="${1:-8787}"

bash "$market_dir/build.sh"

index_url="http://127.0.0.1:$port/v1/index.json"
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

exec python3 "$market_dir/server.py" --bind 0.0.0.0 --port "$port"
