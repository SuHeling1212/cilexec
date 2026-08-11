#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$project_dir"

if [[ -z "${CILEXEC_IMAGE:-}" && -f "$project_dir/.env" ]]; then
    while IFS='=' read -r key value; do
        if [[ "$key" == "CILEXEC_IMAGE" && "$value" =~ ^[A-Za-z0-9._/:@-]+$ ]]; then
            export CILEXEC_IMAGE="$value"
        fi
    done < "$project_dir/.env"
fi

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
rebuild=false

if [[ "${1:-}" == "--rebuild" ]]; then
    rebuild=true
    shift
fi

if ! command -v docker >/dev/null 2>&1; then
    echo "Error: Docker not found. Please install and start Docker Desktop." >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "Error: Docker Compose plugin is not available." >&2
    exit 1
fi

bash "$project_dir/docker/create-secrets.sh" >/dev/null

compose=(docker compose -f compose.yml -f docker/compose/persistent.yml)

# Ensure postgres is running.
if ! "${compose[@]}" ps postgres 2>/dev/null | grep -q 'Up'; then
    echo "Starting postgres..."
    "${compose[@]}" up -d postgres
fi

if [[ "$rebuild" == true ]]; then
    if [[ ! -d "$project_dir/src" ]]; then
        echo "Error: --rebuild requires a source distribution." >&2
        exit 1
    fi
    echo "Building image $image_name..."
    "${compose[@]}" build
    echo "Image $image_name built."
elif ! docker image inspect "$image_name" >/dev/null 2>&1; then
    if [[ -n "${CILEXEC_IMAGE:-}" && "$image_name" != "cilexec:local" ]]; then
        docker pull "$image_name"
    elif [[ -d "$project_dir/src" ]]; then
        "${compose[@]}" build
    else
        echo "Error: release image $image_name is unavailable." >&2
        exit 1
    fi
else
    echo "Reusing image $image_name (use --rebuild to rebuild it)."
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
