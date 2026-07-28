#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
secret_dir="$project_dir/docker/secrets"
mkdir -p "$secret_dir"

create_secret() {
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

create_secret "$secret_dir/postgres-admin-password"
create_secret "$secret_dir/cilexec-migrator-password"
create_secret "$secret_dir/cilexec-runtime-password"
create_secret "$secret_dir/cilexec-effect-worker-password"
create_secret "$secret_dir/cilexec-readonly-password"

echo "CilExec internal database secrets are ready."
