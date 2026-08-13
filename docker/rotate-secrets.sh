#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
secret_dir="$project_dir/docker/secrets"
force=false
recover=false

case "${1:-}" in
    "") ;;
    --force) force=true ;;
    --recover)
        force=true
        recover=true
        ;;
    --help|-h)
        echo "Usage: ./docker/rotate-secrets.sh [--force|--recover]"
        exit 0
        ;;
    *)
        echo "Error: unknown option: $1" >&2
        exit 2
        ;;
esac
if [[ "$#" -gt 1 ]]; then
    echo "Error: too many arguments." >&2
    exit 2
fi

for variable in CILEXEC_POSTGRES_ADMIN_PASSWORD_FILE CILEXEC_MIGRATOR_PASSWORD_FILE \
        CILEXEC_RUNTIME_PASSWORD_FILE CILEXEC_EFFECT_PASSWORD_FILE \
        CILEXEC_READONLY_PASSWORD_FILE CILEXEC_EXPORTER_PASSWORD_FILE; do
    if [[ -n "${!variable:-}" ]]; then
        echo "Error: $variable is externally managed; rotate it with that secret provider." >&2
        exit 1
    fi
done
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    echo "Error: Docker and the Docker Compose plugin are required." >&2
    exit 1
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
compose=(docker compose -f "$project_dir/compose.yml" \
    -f "$project_dir/docker/compose/persistent.yml")
compose_environment="$("${compose[@]}" config --environment)"
compose_environment_value() {
    local sought="$1"
    local line
    while IFS= read -r line; do
        if [[ "${line%%=*}" == "$sought" ]]; then
            printf '%s' "${line#*=}"
            return
        fi
    done <<< "$compose_environment"
}
for variable in CILEXEC_POSTGRES_ADMIN_PASSWORD_FILE CILEXEC_MIGRATOR_PASSWORD_FILE \
        CILEXEC_RUNTIME_PASSWORD_FILE CILEXEC_EFFECT_PASSWORD_FILE \
        CILEXEC_READONLY_PASSWORD_FILE CILEXEC_EXPORTER_PASSWORD_FILE; do
    if [[ -n "$(compose_environment_value "$variable")" ]]; then
        echo "Error: $variable is externally managed; rotate it with that secret provider." >&2
        exit 1
    fi
done
for variable in CILEXEC_CONTAINER_UID CILEXEC_CONTAINER_GID CILEXEC_DATABASE_NAME; do
    value="$(compose_environment_value "$variable")"
    if [[ -z "${!variable:-}" && -n "$value" ]]; then
        export "$variable=$value"
    fi
done

host_uid="$(id -u)"
host_gid="$(id -g)"
if [[ "$host_uid" -eq 0 ]]; then
    host_uid=10001
    host_gid=10001
fi
export CILEXEC_CONTAINER_UID="${CILEXEC_CONTAINER_UID:-$host_uid}"
export CILEXEC_CONTAINER_GID="${CILEXEC_CONTAINER_GID:-$host_gid}"
if [[ ! "$CILEXEC_CONTAINER_UID" =~ ^[0-9]+$ \
        || ! "$CILEXEC_CONTAINER_GID" =~ ^[0-9]+$ ]] \
        || (( 10#$CILEXEC_CONTAINER_UID == 0 || 10#$CILEXEC_CONTAINER_GID == 0 )); then
    echo "Error: CILEXEC_CONTAINER_UID/GID must be positive numeric IDs." >&2
    exit 1
fi

if [[ "$force" != true ]]; then
    echo "This briefly restarts PostgreSQL and the CilExec Runtime."
    read -r -p "Type rotate to replace all Compose-managed database passwords: " confirmation
    if [[ "$confirmation" != "rotate" ]]; then
        echo "Credential rotation cancelled."
        exit 0
    fi
fi

bash "$project_dir/docker/create-secrets.sh" >/dev/null
lock_dir="$secret_dir/.rotation.lock"
if [[ "$recover" == true ]]; then
    if [[ ! -d "$lock_dir" || -L "$lock_dir" ]]; then
        echo "Error: no regular rotation recovery directory exists at $lock_dir." >&2
        exit 1
    fi
elif ! mkdir "$lock_dir" 2>/dev/null; then
    echo "Error: another rotation may be active. Use --recover only after confirming the prior process stopped." >&2
    exit 1
fi
runtime_was_running=false
rotation_complete=false
database_rotated=false
secrets_published=false
cleanup_started=false
cleanup() {
    if [[ "$cleanup_started" == true ]]; then
        return
    fi
    cleanup_started=true
    if [[ "$rotation_complete" == true ]]; then
        rm -rf -- "$lock_dir"
        return
    fi
    if [[ "$database_rotated" == true && "$secrets_published" == true ]]; then
        "${compose[@]}" up -d --force-recreate postgres >/dev/null 2>&1 || true
        if [[ "$runtime_was_running" == true ]]; then
            "${compose[@]}" up -d --force-recreate --no-deps cilexec \
                >/dev/null 2>&1 || true
        fi
    elif [[ "$database_rotated" != true && "$recover" != true \
            && "$runtime_was_running" == true ]]; then
        "${compose[@]}" up -d --no-deps cilexec >/dev/null 2>&1 || true
    fi
    if [[ "$database_rotated" == true || "$recover" == true ]]; then
        echo "Error: rotation did not complete; recovery material remains in $lock_dir. Run with --recover after confirming this process stopped." >&2
    else
        rm -rf -- "$lock_dir"
    fi
}
trap cleanup EXIT HUP INT TERM
umask 077

secret_names=(
    postgres-admin-password
    cilexec-migrator-password
    cilexec-runtime-password
    cilexec-effect-worker-password
    cilexec-readonly-password
    cilexec-exporter-password
)
generate_secret() {
    local destination="$1"
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32 | tr -d '[:space:]' > "$destination"
    elif [[ -r /dev/urandom ]] && command -v od >/dev/null 2>&1; then
        od -An -N32 -tx1 /dev/urandom | tr -d '[:space:]' > "$destination"
    else
        echo "Error: a cryptographically secure random source is required." >&2
        exit 1
    fi
    chmod 600 "$destination"
}
validate_secret() {
    local candidate="$1"
    [[ -f "$candidate" && ! -L "$candidate" ]] \
        && [[ "$(wc -c < "$candidate" | tr -d '[:space:]')" -eq 64 ]] \
        && LC_ALL=C grep -Eq '^[0-9a-f]{64}$' "$candidate"
}
for secret_name in "${secret_names[@]}"; do
    if [[ "$recover" == true ]]; then
        if ! validate_secret "$lock_dir/$secret_name"; then
            if validate_secret "$secret_dir/$secret_name"; then
                cp "$secret_dir/$secret_name" "$lock_dir/$secret_name"
                chmod 600 "$lock_dir/$secret_name"
            else
                echo "Error: rotation recovery secret is missing or invalid: $secret_name" >&2
                exit 1
            fi
        fi
    else
        generate_secret "$lock_dir/$secret_name"
    fi
done

if [[ -n "$("${compose[@]}" ps --status running -q cilexec)" ]]; then
    runtime_was_running=true
    "${compose[@]}" stop cilexec
fi
"${compose[@]}" up -d postgres

{
    printf "\\set admin_password '%s'\n" "$(<"$lock_dir/postgres-admin-password")"
    printf "\\set migrator_password '%s'\n" "$(<"$lock_dir/cilexec-migrator-password")"
    printf "\\set runtime_password '%s'\n" "$(<"$lock_dir/cilexec-runtime-password")"
    printf "\\set effect_password '%s'\n" "$(<"$lock_dir/cilexec-effect-worker-password")"
    printf "\\set readonly_password '%s'\n" "$(<"$lock_dir/cilexec-readonly-password")"
    printf "\\set exporter_password '%s'\n" "$(<"$lock_dir/cilexec-exporter-password")"
    cat <<'SQL'
BEGIN;
SELECT format('ALTER ROLE cilexec_bootstrap PASSWORD %L', :'admin_password') \gexec
SELECT format('ALTER ROLE cilexec_migrator PASSWORD %L', :'migrator_password') \gexec
SELECT format('ALTER ROLE cilexec_runtime PASSWORD %L', :'runtime_password') \gexec
SELECT format('ALTER ROLE cilexec_effect_worker PASSWORD %L', :'effect_password') \gexec
SELECT format('ALTER ROLE cilexec_readonly PASSWORD %L', :'readonly_password') \gexec
SELECT format('ALTER ROLE cilexec_exporter PASSWORD %L', :'exporter_password') \gexec
COMMIT;
SQL
} | "${compose[@]}" exec -T postgres psql --username cilexec_bootstrap \
        --dbname postgres --set ON_ERROR_STOP=1 >/dev/null
database_rotated=true

for secret_name in "${secret_names[@]}"; do
    mv -f -- "$lock_dir/$secret_name" "$secret_dir/$secret_name"
    if [[ "$(id -u)" -eq 0 ]]; then
        chown "$CILEXEC_CONTAINER_UID:$CILEXEC_CONTAINER_GID" \
            "$secret_dir/$secret_name"
    fi
    chmod 600 "$secret_dir/$secret_name"
done
secrets_published=true

# Recreate PostgreSQL so Docker remounts files whose inodes were atomically replaced.
"${compose[@]}" up -d --force-recreate postgres
postgres_ready=false
for _ in {1..60}; do
    if "${compose[@]}" exec -T postgres pg_isready --quiet \
            --username cilexec_migrator --dbname "${CILEXEC_DATABASE_NAME:-cilexec}"; then
        postgres_ready=true
        break
    fi
    sleep 0.25
done
if [[ "$postgres_ready" != true ]]; then
    echo "Error: PostgreSQL did not become ready after credential rotation." >&2
    exit 1
fi
database_name="${CILEXEC_DATABASE_NAME:-cilexec}"
verify_login() {
    local role="$1"
    local secret_path="$2"
    local database="$3"
    # Positional parameters must be expanded by the container shell.
    # shellcheck disable=SC2016
    "${compose[@]}" exec -T postgres sh -eu -c \
        'PGPASSWORD="$(tr -d "\r\n" < "$1")" PGSSLMODE=verify-full \
         PGSSLROOTCERT=/run/cilexec-tls/ca.crt \
         psql --host=postgres --username "$2" --dbname "$3" \
              --set ON_ERROR_STOP=1 --no-psqlrc --tuples-only \
              --command "SELECT 1" >/dev/null' \
        sh "$secret_path" "$role" "$database"
}
verify_login cilexec_bootstrap /run/secrets/postgres_admin_password postgres
verify_login cilexec_migrator /run/secrets/cilexec_migrator_password "$database_name"
verify_login cilexec_runtime /run/secrets/cilexec_runtime_password "$database_name"
verify_login cilexec_effect_worker /run/secrets/cilexec_effect_worker_password "$database_name"
verify_login cilexec_readonly /run/secrets/cilexec_readonly_password "$database_name"
verify_login cilexec_exporter /run/secrets/cilexec_exporter_password "$database_name"

"${compose[@]}" run --rm migrate >/dev/null
if [[ "$runtime_was_running" == true ]]; then
    "${compose[@]}" up -d --force-recreate --no-deps cilexec
fi
rotation_complete=true

echo "CilExec Compose-managed database credentials were rotated and verified."
