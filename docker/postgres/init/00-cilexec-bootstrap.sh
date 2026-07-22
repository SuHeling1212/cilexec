#!/bin/sh
set -eu

# This cluster bootstrap is intentionally outside Flyway. It creates the
# database identities required before a Flyway connection can exist.
read_secret() {
    secret_path="$1"
    if [ ! -r "$secret_path" ]; then
        echo "required secret file is not readable: $secret_path" >&2
        exit 78
    fi
    secret_value=$(tr -d '\r\n' < "$secret_path")
    if [ -z "$secret_value" ]; then
        echo "required secret file is empty: $secret_path" >&2
        exit 78
    fi
    printf '%s' "$secret_value"
}

database_name="${CILEXEC_DATABASE_NAME:-cilexec}"
migrator_password=$(read_secret "${CILEXEC_MIGRATOR_PASSWORD_FILE:-/run/secrets/cilexec_migrator_password}")
runtime_password=$(read_secret "${CILEXEC_RUNTIME_PASSWORD_FILE:-/run/secrets/cilexec_runtime_password}")
effect_password=$(read_secret "${CILEXEC_EFFECT_PASSWORD_FILE:-/run/secrets/cilexec_effect_worker_password}")
readonly_password=$(read_secret "${CILEXEC_READONLY_PASSWORD_FILE:-/run/secrets/cilexec_readonly_password}")

for service_password in "$migrator_password" "$runtime_password" "$effect_password" "$readonly_password"; do
    if [ "${#service_password}" -lt 16 ]; then
        echo "CilExec database service passwords must contain at least 16 characters" >&2
        exit 78
    fi
done
unset service_password

psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 \
    --set database_name="$database_name" \
    --set migrator_password="$migrator_password" \
    --set runtime_password="$runtime_password" \
    --set effect_password="$effect_password" \
    --set readonly_password="$readonly_password" <<'SQL'
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

ALTER ROLE cilexec_owner NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_migrator LOGIN INHERIT NOSUPERUSER NOCREATEDB CREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_effect_worker LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE cilexec_readonly LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

SELECT format('ALTER ROLE cilexec_migrator PASSWORD %L', :'migrator_password') \gexec
SELECT format('ALTER ROLE cilexec_runtime PASSWORD %L', :'runtime_password') \gexec
SELECT format('ALTER ROLE cilexec_effect_worker PASSWORD %L', :'effect_password') \gexec
SELECT format('ALTER ROLE cilexec_readonly PASSWORD %L', :'readonly_password') \gexec

GRANT cilexec_owner TO cilexec_migrator;

SELECT format(
    'CREATE DATABASE %I OWNER cilexec_owner ENCODING %L TEMPLATE template0',
    :'database_name', 'UTF8'
)
WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = :'database_name') \gexec

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'database_name') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO cilexec_migrator, cilexec_runtime, cilexec_effect_worker, cilexec_readonly', :'database_name') \gexec
SQL

psql --username "$POSTGRES_USER" --dbname "$database_name" --set ON_ERROR_STOP=1 <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA IF NOT EXISTS flyway AUTHORIZATION cilexec_migrator;
REVOKE ALL ON SCHEMA flyway FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA flyway TO cilexec_migrator;
GRANT USAGE ON SCHEMA flyway TO cilexec_runtime, cilexec_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE cilexec_migrator IN SCHEMA flyway
    GRANT SELECT ON TABLES TO cilexec_runtime, cilexec_readonly;
SQL

unset migrator_password runtime_password effect_password readonly_password secret_value
