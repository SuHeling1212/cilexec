#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

if ! command -v docker >/dev/null 2>&1; then
    echo "错误：没有找到 Docker，请先安装并启动 Docker Desktop。" >&2
    exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
    echo "错误：当前 Docker 没有 Compose 插件。" >&2
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

# This secret provisions the local administrator when a new database is created.
printf '%s\n' '12345678' > "$secret_dir/cilexec-terminal-password"
chmod 600 "$secret_dir/cilexec-terminal-password"

compose=(docker compose -f compose.yml -f compose.persistent.yml)

cleanup() {
    "${compose[@]}" down >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "正在启动 CilExec……"
"${compose[@]}" up -d postgres
"${compose[@]}" run --rm --build migrate

echo
echo "管理员用户名：local"
echo "默认密码：12345678"
echo "请选择 login 登录；输入 :exit 可退出。"
echo

"${compose[@]}" run --rm --no-deps cilexec
