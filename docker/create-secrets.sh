#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
secret_dir="$project_dir/docker/secrets"
container_uid="${CILEXEC_CONTAINER_UID:-10001}"
container_gid="${CILEXEC_CONTAINER_GID:-10001}"
if [[ ! "$container_uid" =~ ^[0-9]+$ || ! "$container_gid" =~ ^[0-9]+$ ]] \
        || (( 10#$container_uid == 0 || 10#$container_gid == 0 )); then
    echo "Error: CILEXEC_CONTAINER_UID/GID must be positive numeric IDs." >&2
    exit 1
fi
umask 077
if [[ -L "$secret_dir" ]]; then
    echo "Error: the database secret directory must not be a symbolic link: $secret_dir" >&2
    exit 1
fi
mkdir -p "$secret_dir"
chmod 700 "$secret_dir"

temporary_secret=""
temporary_directory=""
cleanup() {
    if [[ -n "$temporary_secret" ]]; then
        rm -f -- "$temporary_secret"
    fi
    if [[ -n "$temporary_directory" ]]; then
        rm -rf -- "$temporary_directory"
    fi
}

create_postgres_tls_identity() {
    local ca_certificate="$secret_dir/postgres-ca.crt"
    local server_certificate="$secret_dir/postgres-server.crt"
    local server_key="$secret_dir/postgres-server.key"
    local existing=0
    local path
    for path in "$ca_certificate" "$server_certificate" "$server_key"; do
        [[ ! -e "$path" && ! -L "$path" ]] || existing=$((existing + 1))
    done
    if (( existing == 3 )); then
        if [[ -L "$ca_certificate" || -L "$server_certificate" || -L "$server_key" ]] \
                || ! openssl verify -CAfile "$ca_certificate" "$server_certificate" >/dev/null 2>&1 \
                || ! openssl x509 -checkend 2592000 -noout -in "$server_certificate" >/dev/null 2>&1; then
            echo "Error: existing PostgreSQL TLS identity is invalid or expires within 30 days." >&2
            exit 1
        fi
        chmod 644 "$ca_certificate" "$server_certificate"
        chmod 600 "$server_key"
        return
    fi
    if (( existing != 0 )); then
        echo "Error: PostgreSQL TLS identity is incomplete in $secret_dir." >&2
        exit 1
    fi
    if ! command -v openssl >/dev/null 2>&1; then
        echo "Error: OpenSSL is required to create the local PostgreSQL TLS identity." >&2
        exit 1
    fi

    temporary_directory="$(mktemp -d "$secret_dir/.cilexec-tls.XXXXXX")"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
        -out "$temporary_directory/ca.key" >/dev/null 2>&1
    openssl req -x509 -new -sha256 -days 3650 \
        -key "$temporary_directory/ca.key" \
        -subj "/CN=CilExec Local PostgreSQL CA" \
        -out "$temporary_directory/ca.crt" >/dev/null 2>&1
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 \
        -out "$temporary_directory/server.key" >/dev/null 2>&1
    openssl req -new -sha256 -key "$temporary_directory/server.key" \
        -subj "/CN=postgres" -out "$temporary_directory/server.csr" >/dev/null 2>&1
    printf '%s\n' \
        'basicConstraints=critical,CA:FALSE' \
        'keyUsage=critical,digitalSignature,keyEncipherment' \
        'extendedKeyUsage=serverAuth' \
        'subjectAltName=DNS:postgres,DNS:localhost,IP:127.0.0.1' \
        > "$temporary_directory/server.ext"
    openssl x509 -req -sha256 -days 825 \
        -in "$temporary_directory/server.csr" \
        -CA "$temporary_directory/ca.crt" -CAkey "$temporary_directory/ca.key" \
        -CAcreateserial -extfile "$temporary_directory/server.ext" \
        -out "$temporary_directory/server.crt" >/dev/null 2>&1
    openssl verify -CAfile "$temporary_directory/ca.crt" \
        "$temporary_directory/server.crt" >/dev/null

    install -m 0644 "$temporary_directory/ca.crt" "$ca_certificate"
    install -m 0644 "$temporary_directory/server.crt" "$server_certificate"
    install -m 0600 "$temporary_directory/server.key" "$server_key"
    rm -rf -- "$temporary_directory"
    temporary_directory=""
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
    # race to create. On bind mounts that reject hard links (Docker Desktop for Windows),
    # fall back to a same-directory move.
    if ln "$temporary_secret" "$destination" 2>/dev/null; then
        rm -f -- "$temporary_secret"
        temporary_secret=""
    elif [[ -e "$destination" || -L "$destination" ]]; then
        rm -f -- "$temporary_secret"
        temporary_secret=""
        if ! validate_secret "$destination"; then
            echo "Error: cannot publish database secret: $destination" >&2
            exit 1
        fi
        chmod 600 "$destination"
    elif mv "$temporary_secret" "$destination" 2>/dev/null; then
        temporary_secret=""
    else
        rm -f -- "$temporary_secret"
        temporary_secret=""
        echo "Error: cannot publish database secret: $destination" >&2
        exit 1
    fi
}

set_application_secret_owner() {
    local path="$1"
    if [[ "$(id -u)" -eq 0 ]]; then
        # The helper container runs as root on a bind mount where ownership cannot be
        # changed (Docker Desktop for Windows). Ownership is fabricated per container there,
        # so an EPERM is not fatal; warn instead of aborting the installation.
        if ! chown "$container_uid:$container_gid" "$path" 2>/dev/null; then
            echo "Warning: cannot chown $path to $container_uid:$container_gid on this bind mount." >&2
        fi
    elif [[ "$(id -u)" != "$container_uid" || "$(id -g)" != "$container_gid" ]]; then
        echo "Error: database secrets must be owned by container identity $container_uid:$container_gid; run Install.sh or set CILEXEC_CONTAINER_UID/GID to the current user." >&2
        exit 1
    fi
    chmod 600 "$path"
}

create_secret "$secret_dir/postgres-admin-password"
create_secret "$secret_dir/cilexec-migrator-password"
create_secret "$secret_dir/cilexec-runtime-password"
create_secret "$secret_dir/cilexec-effect-worker-password"
create_secret "$secret_dir/cilexec-readonly-password"
create_secret "$secret_dir/cilexec-exporter-password"
for secret in "$secret_dir"/*-password; do
    set_application_secret_owner "$secret"
done
create_postgres_tls_identity

echo "CilExec internal database secrets and PostgreSQL TLS identity are ready."
