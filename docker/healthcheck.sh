#!/bin/sh
set -eu

check_kind="${1:-ready}"
case "$check_kind" in
    live|ready) ;;
    *) exit 64 ;;
esac

health_port="${CILEXEC_HEALTH_PORT:-8080}"
case "$health_port" in
    ''|*[!0-9]*) exit 64 ;;
esac
if [ "${#health_port}" -gt 5 ] || [ "$health_port" -lt 1 ] || [ "$health_port" -gt 65535 ]; then
    exit 64
fi
exec /usr/local/bin/cilexec-terminal-client --health "$check_kind" "$health_port"
