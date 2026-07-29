#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
secret_dir="$project_dir/docker/secrets"
umask 077
if [[ -L "$secret_dir" ]]; then
    echo "Error: the database secret directory must not be a symbolic link: $secret_dir" >&2
    exit 1
fi
mkdir -p "$secret_dir"
chmod 700 "$secret_dir"

temporary_secret=""
cleanup() {
    if [[ -n "$temporary_secret" ]]; then
        rm -f -- "$temporary_secret"
    fi
}
trap cleanup EXIT HUP INT TERM

validate_secret() {
    local candidate="$1"
    [[ -f "$candidate" && ! -L "$candidate" ]] \
        && [[ "$(wc -c < "$candidate" | tr -d '[:space:]')" -eq 64 ]] \
        && LC_ALL=C grep -Eq '^[0-9a-f]{64}$' "$candidate"
}

create_secret() {
    local destination="$1"
    if [[ -s "$destination" ]]; then
        if ! validate_secret "$destination"; then
            echo "Error: existing database secret is not a regular 64-character lowercase hexadecimal file: $destination" >&2
            exit 1
        fi
        chmod 600 "$destination"
        return
    fi
    if [[ -e "$destination" || -L "$destination" ]]; then
        echo "Error: existing database secret is empty or is not a regular file: $destination" >&2
        exit 1
    fi
    temporary_secret="$(mktemp "$secret_dir/.cilexec-secret.XXXXXX")"
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 | tr -d '[:space:]' > "$temporary_secret"
    elif [[ -r /dev/urandom ]] && command -v od >/dev/null 2>&1 \
            && command -v tr >/dev/null 2>&1; then
        od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]' > "$temporary_secret"
    else
        echo "Error: a cryptographically secure random source is required." >&2
        exit 1
    fi
    if ! validate_secret "$temporary_secret"; then
        echo "Error: secure secret generation produced insufficient output." >&2
        exit 1
    fi
    chmod 600 "$temporary_secret"
    # Hard-link publication is atomic and never replaces a secret another terminal won the
    # race to create. This avoids an initialized database and a later password file diverging.
    if ln "$temporary_secret" "$destination" 2>/dev/null; then
        rm -f -- "$temporary_secret"
        temporary_secret=""
    else
        rm -f -- "$temporary_secret"
        temporary_secret=""
        if ! validate_secret "$destination"; then
            echo "Error: cannot publish database secret: $destination" >&2
            exit 1
        fi
        chmod 600 "$destination"
    fi
}

create_secret "$secret_dir/postgres-admin-password"
create_secret "$secret_dir/cilexec-migrator-password"
create_secret "$secret_dir/cilexec-runtime-password"
create_secret "$secret_dir/cilexec-effect-worker-password"
create_secret "$secret_dir/cilexec-readonly-password"

echo "CilExec internal database secrets are ready."
