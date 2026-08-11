#!/usr/bin/env bash
set -euo pipefail

config_file="${CILEXEC_CONFIG_FILE:-/etc/cilexec/cilexec.env}"
postgres_os_user="${POSTGRES_OS_USER:-postgres}"

if [[ $EUID -ne 0 ]]; then
    printf 'run this bootstrap as root so it can read service secrets and invoke psql as %s\n' \
        "$postgres_os_user" >&2
    exit 77
fi
if [[ ! -r "$config_file" ]]; then
    printf 'configuration file is not readable: %s\n' "$config_file" >&2
    exit 78
fi

# The operator-owned environment file is trusted configuration, never application input.
set -a
# shellcheck disable=SC1090
source "$config_file"
set +a
database_name="${CILEXEC_DATABASE_NAME:-cilexec}"

if [[ ! "$database_name" =~ ^[A-Za-z_][A-Za-z0-9_-]{0,62}$ ]]; then
    printf 'CILEXEC_DATABASE_NAME is not a valid PostgreSQL database name\n' >&2
    exit 78
fi

read_secret() {
    local path="$1"
    local mode value
    if [[ ! -f "$path" || -L "$path" ]]; then
        printf 'secret must be a regular non-symlink file: %s\n' "$path" >&2
        exit 78
    fi
    mode="$(stat -c '%a' -- "$path")"
    if [[ "$mode" != "400" && "$mode" != "600" ]]; then
        printf 'secret must have mode 0400 or 0600: %s\n' "$path" >&2
        exit 78
    fi
    value="$(<"$path")"
    if [[ ! "$value" =~ ^[0-9a-f]{64}$ ]]; then
        printf 'secret must contain exactly 64 lowercase hexadecimal characters: %s\n' \
            "$path" >&2
        exit 78
    fi
    printf '%s' "$value"
}

run_psql() {
    runuser -u "$postgres_os_user" -- psql "$@"
}

migrator_password="$(read_secret "${CILEXEC_MIGRATOR_DATABASE_PASSWORD_FILE:-/etc/cilexec/secrets/cilexec_migrator_password}")"
runtime_password="$(read_secret "${CILEXEC_RUNTIME_DATABASE_PASSWORD_FILE:-/etc/cilexec/secrets/cilexec_runtime_password}")"
effect_password="$(read_secret "${CILEXEC_EFFECT_DATABASE_PASSWORD_FILE:-/etc/cilexec/secrets/cilexec_effect_worker_password}")"
readonly_password="$(read_secret "${CILEXEC_READONLY_PASSWORD_FILE:-/etc/cilexec/secrets/cilexec_readonly_password}")"
exporter_password="$(read_secret "${CILEXEC_EXPORTER_DATABASE_PASSWORD_FILE:-/etc/cilexec/secrets/cilexec_exporter_password}")"

{
printf "\\set database_name '%s'\n" "$database_name"
printf "\\set migrator_password '%s'\n" "$migrator_password"
printf "\\set runtime_password '%s'\n" "$runtime_password"
printf "\\set effect_password '%s'\n" "$effect_password"
printf "\\set readonly_password '%s'\n" "$readonly_password"
printf "\\set exporter_password '%s'\n" "$exporter_password"
cat <<'SQL'
SELECT 'CREATE ROLE cilexec_owner NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_owner') \gexec
SELECT 'CREATE ROLE cilexec_migrator LOGIN INHERIT NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_migrator') \gexec
SELECT 'CREATE ROLE cilexec_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_runtime') \gexec
SELECT 'CREATE ROLE cilexec_effect_worker LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_effect_worker') \gexec
SELECT 'CREATE ROLE cilexec_readonly LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_readonly') \gexec
SELECT 'CREATE ROLE cilexec_exporter LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_exporter') \gexec

ALTER ROLE cilexec_owner NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_migrator LOGIN INHERIT NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_effect_worker LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_readonly LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_exporter LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_readonly SET default_transaction_read_only TO on;
ALTER ROLE cilexec_exporter SET default_transaction_read_only TO on;

SELECT format('ALTER ROLE cilexec_migrator PASSWORD %L', :'migrator_password') \gexec
SELECT format('ALTER ROLE cilexec_runtime PASSWORD %L', :'runtime_password') \gexec
SELECT format('ALTER ROLE cilexec_effect_worker PASSWORD %L', :'effect_password') \gexec
SELECT format('ALTER ROLE cilexec_readonly PASSWORD %L', :'readonly_password') \gexec
SELECT format('ALTER ROLE cilexec_exporter PASSWORD %L', :'exporter_password') \gexec
GRANT cilexec_owner TO cilexec_migrator;

SELECT format('CREATE DATABASE %I OWNER cilexec_owner ENCODING %L TEMPLATE template0',
              :'database_name', 'UTF8')
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = :'database_name') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'database_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO cilexec_migrator, cilexec_runtime, cilexec_effect_worker, cilexec_readonly, cilexec_exporter', :'database_name') \gexec
SQL
} | run_psql --dbname postgres --set ON_ERROR_STOP=1

run_psql --dbname "$database_name" --set ON_ERROR_STOP=1 <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS flyway AUTHORIZATION cilexec_migrator;
REVOKE ALL ON SCHEMA flyway FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA flyway TO cilexec_migrator;
GRANT USAGE ON SCHEMA flyway TO cilexec_runtime, cilexec_exporter;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_migrator IN SCHEMA flyway
    GRANT SELECT ON TABLES TO cilexec_runtime, cilexec_exporter;
SQL

unset migrator_password runtime_password effect_password readonly_password exporter_password
printf 'CilExec PostgreSQL roles and database are ready; run cilexec-migrate.service next.\n'
