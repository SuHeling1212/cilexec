#!/usr/bin/env bash
# ==============================================================================
# CilExec uninstaller — removes the CilExec instance for the current installation directory
#
# This script removes only the current Compose project's containers, volume, and network,
# generated password files, and default export directory. It does not clean up other
# installations or Docker caches globally.
#
# Usage: ./tools/Uninstall.sh [--force]
#   --force  skip the confirmation prompt and clean up immediately
# ==============================================================================
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

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
# Use the same project and volume naming logic as Install.sh so this script finds
# and removes resources for the intended installation.
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
IMAGE_NAME="${CILEXEC_IMAGE:-cilexec:local}"
REMOVE_IMAGE=false
[[ "$IMAGE_NAME" == "cilexec:local" ]] && REMOVE_IMAGE=true
VOLUME_NAME="${CILEXEC_POSTGRES_VOLUME:-cilexec-pgdata-${project_hash}}"
SECRET_DIR="$project_dir/docker/secrets"
SECRET_FILES=(
    postgres-admin-password
    cilexec-migrator-password
    cilexec-runtime-password
    cilexec-effect-worker-password
    cilexec-readonly-password
    cilexec-exporter-password
    postgres-ca.crt
    postgres-server.crt
    postgres-server.key
)
DEFAULT_EXPORT_DIR="$project_dir/exports"
configured_export_dir="${CILEXEC_EXPORT_DIRECTORY:-$DEFAULT_EXPORT_DIR}"
if [[ "$configured_export_dir" == /* ]]; then
    EXPORT_DIR="${configured_export_dir%/}"
else
    EXPORT_DIR="${project_dir}/${configured_export_dir#./}"
    EXPORT_DIR="${EXPORT_DIR%/}"
fi

FORCE=false
IMAGE_REMOVED=false
if [[ "${1:-}" == "--force" ]]; then
    FORCE=true
fi

# ---------------------------------------------------------------------------
# Coloured output
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BOLD='\033[1m'
NC='\033[0m' # No Color

info()  { printf "  ${GREEN}✓${NC} %s\n" "$*"; }
warn()  { printf "  ${YELLOW}⚠${NC} %s\n" "$*"; }
error() { printf "  ${RED}✗${NC} %s\n" "$*"; }
header() {
    echo ""
    printf "${BOLD}%s${NC}\n" "$*"
}
print_directory_entries() {
    local directory="$1"
    local found=false
    local path
    if [[ -d "$directory" ]]; then
        for path in "$directory"/*; do
            [[ -e "$path" || -L "$path" ]] || continue
            printf '  %s\n' "$path"
            found=true
        done
    fi
    if [[ "$found" != true ]]; then
        echo "  (none)"
    fi
}

# ---------------------------------------------------------------------------
# Confirmation
# ---------------------------------------------------------------------------
if [[ "$FORCE" != true ]]; then
    echo ""
    printf '%b%s%b\n' "$RED$BOLD" '╔══════════════════════════════════════════════════════════════╗' "$NC"
    printf '%b%s%b\n' "$RED$BOLD" '║  WARNING: This permanently deletes data for this CilExec instance. ║' "$NC"
    printf '%b%s%b\n' "$RED$BOLD" '║  It includes containers, database volume, passwords, and exports.  ║' "$NC"
    printf '%b%s%b\n' "$RED$BOLD" '║  This action cannot be undone.                                     ║' "$NC"
    printf '%b%s%b\n' "$RED$BOLD" '╚══════════════════════════════════════════════════════════════╝' "$NC"
    echo ""

    if command -v docker >/dev/null 2>&1; then
        echo "Docker resources related to this CilExec installation:"
        echo "──────────────────────────────────────────────"

        echo ""
        echo "[Containers]"
        docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
            --format '  {{.Names}} ({{.Status}})' 2>/dev/null || echo "  (none)"

        echo ""
        echo "[Image]"
        if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
            docker image inspect "$IMAGE_NAME" --format='  {{.RepoTags}} ({{.Size}})' 2>/dev/null
        else
            echo "  (none)"
        fi

        echo ""
        echo "[Volume]"
        if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
            docker volume inspect "$VOLUME_NAME" --format='  {{.Name}} ({{.Mountpoint}})' 2>/dev/null
        else
            echo "  (none)"
        fi

        echo ""
        echo "[Network]"
        docker network ls --filter "name=cilexec" --format '  {{.Name}}' 2>/dev/null || echo "  (none)"
    fi

    echo ""
    echo "[Password files]"
    print_directory_entries "$SECRET_DIR"

    echo ""
    echo "[Export files]"
    print_directory_entries "$EXPORT_DIR"

    echo ""
    echo "──────────────────────────────────────────────"
    read -r -p "Delete all listed CilExec resources? Type yes to continue: " confirm
    if [[ "$confirm" != "yes" ]]; then
        echo "Cancelled."
        exit 0
    fi
fi

# ---------------------------------------------------------------------------
# Step 1: stop and remove Compose containers and networks
# ---------------------------------------------------------------------------
header "Step 1: Stop and remove Compose services"

# Try the main Compose file together with the persistent variant (the usual deployment).
if [[ -f "$project_dir/compose.yml" ]]; then
    compose_files=(-f "$project_dir/compose.yml")

    persistent_compose="$project_dir/docker/compose/persistent.yml"
    if [[ -f "$persistent_compose" ]]; then
        compose_files+=(-f "$persistent_compose")
    fi

    echo "Stopping services with the Compose files..."
    if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
        docker compose "${compose_files[@]}" down --volumes --remove-orphans 2>/dev/null || true
        info "Compose services stopped and removed"
    else
        warn "Docker Compose is unavailable; skipping"
    fi
fi

# ---------------------------------------------------------------------------
# Step 2: remove remaining containers for this installation
# ---------------------------------------------------------------------------
header "Step 2: Remove remaining containers"

if command -v docker >/dev/null 2>&1; then
    containers=$(docker ps -a \
        --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
        -q 2>/dev/null || true)
    if [[ -n "$containers" ]]; then
        while read -r container; do
            [[ -z "$container" ]] || docker rm -f "$container" 2>/dev/null || true
        done <<< "$containers"
        info "Remaining containers removed"
    else
        info "No remaining containers"
    fi
else
    warn "Docker is unavailable; skipping container cleanup"
fi

# ---------------------------------------------------------------------------
# Step 3: remove the CilExec Docker image
# ---------------------------------------------------------------------------
header "Step 3: Remove Docker image"

if [[ "$REMOVE_IMAGE" == true ]] && command -v docker >/dev/null 2>&1; then
    if docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
        image_users=$(docker ps -a --filter "ancestor=$IMAGE_NAME" -q 2>/dev/null || true)
        if [[ -n "$image_users" ]]; then
            warn "Image $IMAGE_NAME is still used by another container; keeping it"
        elif docker rmi "$IMAGE_NAME" >/dev/null 2>&1; then
            IMAGE_REMOVED=true
            info "Image $IMAGE_NAME removed"
        else
            warn "Could not safely remove image $IMAGE_NAME; keeping it"
        fi
    else
        info "Image $IMAGE_NAME does not exist"
    fi
elif [[ "$REMOVE_IMAGE" == true ]]; then
    warn "Docker is unavailable; skipping image removal"
else
    info "Keeping shared release image $IMAGE_NAME"
fi

# ---------------------------------------------------------------------------
# Step 4: remove the CilExec data volume
# ---------------------------------------------------------------------------
header "Step 4: Remove data volume"

if command -v docker >/dev/null 2>&1; then
    if docker volume inspect "$VOLUME_NAME" >/dev/null 2>&1; then
        docker volume rm "$VOLUME_NAME" 2>/dev/null || true
        info "Data volume $VOLUME_NAME removed"
    else
        info "Data volume $VOLUME_NAME does not exist"
    fi
else
    warn "Docker is unavailable; skipping volume removal"
fi

# ---------------------------------------------------------------------------
# Step 5: remove Compose networks
# ---------------------------------------------------------------------------
header "Step 5: Remove networks"

if command -v docker >/dev/null 2>&1; then
    networks=$(docker network ls \
        --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" \
        -q 2>/dev/null || true)
    if [[ -n "$networks" ]]; then
        while read -r network; do
            [[ -z "$network" ]] || docker network rm "$network" 2>/dev/null || true
        done <<< "$networks"
        info "CilExec networks for this installation removed"
    else
        info "No CilExec networks"
    fi
else
    warn "Docker is unavailable; skipping network removal"
fi

# ---------------------------------------------------------------------------
# Step 6: remove password files
# ---------------------------------------------------------------------------
header "Step 6: Remove password files"

if [[ -d "$SECRET_DIR" ]]; then
    for secret_file in "${SECRET_FILES[@]}"; do
        # Secret paths must be regular files, but a stale directory at one of
        # these exact names (for example an accidentally created
        # postgres-ca.crt directory) previously survived `rm -f` and blocked a
        # reinstall. Remove regular files, symlinks, and directories alike;
        # the paths are hardcoded and never contain patterns.
        rm -rf -- "${SECRET_DIR:?}/$secret_file"
    done
    # Keep the directory inode stable. Docker Desktop for macOS may retain a
    # stale bind-mount view when a shared directory is removed and recreated,
    # causing an immediate reinstall to report randomly missing secret files.
    info "Password files removed (keeping directory $SECRET_DIR for reinstall)"
else
    info "Password directory $SECRET_DIR does not exist"
fi

# ---------------------------------------------------------------------------
# Step 7: remove export files
# ---------------------------------------------------------------------------
header "Step 7: Remove export files"

if [[ "$EXPORT_DIR" != "$DEFAULT_EXPORT_DIR" ]]; then
    warn "Custom export directory does not belong to this installation; keeping it: $EXPORT_DIR"
elif [[ -d "$DEFAULT_EXPORT_DIR" ]]; then
    rm -rf "$DEFAULT_EXPORT_DIR"
    info "Default export directory $DEFAULT_EXPORT_DIR removed"
else
    info "Default export directory $DEFAULT_EXPORT_DIR does not exist"
fi

# ---------------------------------------------------------------------------
# Step 8: keep shared Docker build cache
# ---------------------------------------------------------------------------
header "Step 8: Keep shared build cache"
info "Docker build cache may be used by other projects; no global cleanup was run"

# ---------------------------------------------------------------------------
# Complete
# ---------------------------------------------------------------------------
echo ""
printf '%b%s%b\n' "$GREEN$BOLD" '╔══════════════════════════════════════════════════════════════╗' "$NC"
printf '%b%s%b\n' "$GREEN$BOLD" '║  This CilExec installation has been removed.                      ║' "$NC"
printf '%b%s%b\n' "$GREEN$BOLD" '╚══════════════════════════════════════════════════════════════╝' "$NC"
echo ""
echo "Removed:"
echo "  • Containers for this Compose installation ($COMPOSE_PROJECT_NAME)"
if [[ "$IMAGE_REMOVED" == true ]]; then
    echo "  • Docker image not used by another container ($IMAGE_NAME)"
fi
echo "  • Data volume ($VOLUME_NAME) — all database data"
echo "  • Networks for this Compose installation ($COMPOSE_PROJECT_NAME)"
echo "  • Password files ($SECRET_DIR)"
if [[ "$EXPORT_DIR" == "$DEFAULT_EXPORT_DIR" ]]; then
    echo "  • Default export files ($DEFAULT_EXPORT_DIR)"
fi
echo ""
echo "Not affected:"
echo "  • Source directory ($project_dir)"
echo "  • Maven local cache (~/.m2)"
echo "  • Docker itself and other projects"
echo "  • Docker global build cache"
if [[ "$IMAGE_REMOVED" != true ]]; then
    echo "  • Shared or nonexistent Docker image ($IMAGE_NAME)"
fi
if [[ "$EXPORT_DIR" != "$DEFAULT_EXPORT_DIR" ]]; then
    echo "  • Custom export directory ($EXPORT_DIR)"
fi
echo ""
