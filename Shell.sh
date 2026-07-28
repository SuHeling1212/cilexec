#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

project_hash="$(echo "$project_dir" | shasum -a 256 | cut -c1-8)"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-cilexec-${project_hash}}"
export CILEXEC_POSTGRES_VOLUME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"

if ! command -v docker >/dev/null 2>&1; then
    echo "Error: Docker not found. Please install and start Docker Desktop." >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "Error: Docker Compose plugin is not available." >&2
    exit 1
fi

# Ensure secrets exist (same as Install.sh)
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

# Ensure postgres is running
if ! "${compose[@]}" ps postgres 2>/dev/null | grep -q 'Up'; then
    echo "Starting postgres..."
    "${compose[@]}" up -d postgres
fi

# Build image if source code is present (development mode)
if [ -d "$project_dir/src" ]; then
    echo "Image cilexec:local Building"
    "${compose[@]}" build
    echo "Image cilexec:local Built"
fi

enter_program() {
    if ! "${compose[@]}" ps cilexec 2>/dev/null | grep -q 'Up'; then
        echo "Starting cilexec..."
        "${compose[@]}" up -d cilexec
    fi

    echo "Entering the running cilexec application container (root, read-only root filesystem)..."
    echo "Type 'exit' or press Ctrl+D to leave."
    echo
    "${compose[@]}" exec --user root -it cilexec /bin/bash "$@"
}

enter_data() {
    echo "Entering postgres 数据库容器..."
    echo "Type 'exit' or press Ctrl+D to leave."
    echo
    "${compose[@]}" exec -it postgres /bin/bash
}

echo
echo "  [1] program  — 当前 cilexec 应用容器 (root；系统目录只读)"
echo "  [2] data     — postgres 数据库容器 (直接操作数据库)"
echo

if [[ -t 0 ]]; then
    read -r -p "选择要进入的容器 [1/program]: " choice
    target_args=("$@")
else
    choice="${1:-program}"
    shift 2>/dev/null || true
    target_args=("$@")
fi

case "${choice:-1}" in
    1|program|p)
        enter_program "${target_args[@]}"
        ;;
    2|data|d)
        enter_data
        ;;
    *)
        echo "无效选择，默认进入 program。" >&2
        enter_program "${target_args[@]}"
        ;;
esac
