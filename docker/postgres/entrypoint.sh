#!/bin/sh
set -eu

tls_directory=/var/lib/postgresql/cilexec-tls
install -d -m 0700 -o postgres -g postgres "$tls_directory"
install -m 0600 -o postgres -g postgres \
    "${CILEXEC_POSTGRES_TLS_KEY_FILE:-/run/cilexec-tls/server.key}" \
    "$tls_directory/server.key"
install -m 0644 -o postgres -g postgres \
    "${CILEXEC_POSTGRES_TLS_CERTIFICATE_FILE:-/run/cilexec-tls/server.crt}" \
    "$tls_directory/server.crt"
install -m 0644 -o postgres -g postgres \
    "${CILEXEC_POSTGRES_TLS_CA_FILE:-/run/cilexec-tls/ca.crt}" \
    "$tls_directory/ca.crt"

# Compose file-backed secrets retain host ownership on native Linux. Stage every
# database password while this wrapper is still root so the postgres account can
# read owner-only copies during the official entrypoint's second, unprivileged pass.
password_directory=/run/cilexec-postgres-secrets
install -d -m 0700 -o postgres -g postgres "$password_directory"
for variable in POSTGRES_PASSWORD_FILE CILEXEC_MIGRATOR_PASSWORD_FILE \
        CILEXEC_RUNTIME_PASSWORD_FILE CILEXEC_EFFECT_PASSWORD_FILE \
        CILEXEC_READONLY_PASSWORD_FILE CILEXEC_EXPORTER_PASSWORD_FILE; do
    eval "source_path=\${$variable:-}"
    if [ -z "$source_path" ] || [ ! -f "$source_path" ] || [ -L "$source_path" ]; then
        echo "invalid PostgreSQL password source for $variable" >&2
        exit 1
    fi
    target_path="$password_directory/$variable"
    install -m 0400 -o postgres -g postgres "$source_path" "$target_path"
    export "$variable=$target_path"
done

exec docker-entrypoint.sh "$@" \
    -c ssl=on \
    -c ssl_cert_file="$tls_directory/server.crt" \
    -c ssl_key_file="$tls_directory/server.key" \
    -c ssl_ca_file="$tls_directory/ca.crt"
