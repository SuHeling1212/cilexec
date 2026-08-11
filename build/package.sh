#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

version="${1:-$(date +%Y%m%d)}"
if [[ ! "$version" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
    echo "Error: version must contain only letters, digits, dots, underscores, and hyphens." >&2
    exit 2
fi
image="${CILEXEC_IMAGE:-cilexec:local}"
target_platform="${CILEXEC_TARGET_PLATFORM:-}"
docker_target="${CILEXEC_DOCKER_TARGET:-runtime}"
skip_image_build="${CILEXEC_SKIP_IMAGE_BUILD:-false}"
case "$docker_target" in
    runtime|release) ;;
    *) echo "Error: CILEXEC_DOCKER_TARGET must be runtime or release." >&2; exit 2 ;;
esac
case "$skip_image_build" in
    true|false) ;;
    *) echo "Error: CILEXEC_SKIP_IMAGE_BUILD must be true or false." >&2; exit 2 ;;
esac
image_archive=""
temp_dir=""
cleanup() {
    [[ -z "$image_archive" ]] || rm -f -- "$image_archive"
    [[ -z "$temp_dir" ]] || rm -rf -- "$temp_dir"
}
trap cleanup EXIT

echo "=== Building image ==="
if [[ "$skip_image_build" == true ]]; then
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        echo "Error: prebuilt image is unavailable: $image" >&2
        exit 1
    fi
    echo "Using prebuilt image $image."
elif [[ -n "$target_platform" ]]; then
    case "$target_platform" in
        linux/amd64|linux/arm64) ;;
        *) echo "Error: CILEXEC_TARGET_PLATFORM must be linux/amd64 or linux/arm64." >&2; exit 2 ;;
    esac
    image_archive="$(mktemp "${TMPDIR:-/tmp}/cilexec-image.XXXXXX")"
    docker buildx build --platform "$target_platform" --target "$docker_target" \
        --build-arg "BUILD_VERSION=$version" \
        --build-arg "BUILD_REVISION=${CILEXEC_BUILD_REVISION:-development}" \
        --build-arg "BUILD_SOURCE=${CILEXEC_BUILD_SOURCE:-https://github.com/SuHeling1212/cilexec}" \
        --tag "$image" --output "type=docker,dest=$image_archive" .
    docker load --input "$image_archive" >/dev/null
else
    docker compose -f compose.yml -f docker/compose/persistent.yml build
fi

image_os="$(docker image inspect --format '{{.Os}}' "$image")"
image_arch="$(docker image inspect --format '{{.Architecture}}' "$image")"
case "$image_arch" in
    amd64|x86_64) image_arch="amd64" ;;
    arm64|aarch64) image_arch="arm64" ;;
    *) echo "Error: unsupported image architecture: $image_arch" >&2; exit 1 ;;
esac
if [[ "$image_os" != "linux" ]]; then
    echo "Error: standalone payload must contain a Linux image, not $image_os/$image_arch" >&2
    exit 1
fi
image_platform="linux-$image_arch"
if [[ -n "$target_platform" && "${target_platform/\//-}" != "$image_platform" ]]; then
    echo "Error: image platform $image_platform does not match requested $target_platform." >&2
    exit 1
fi
output="$project_dir/build/cilexec-${version}-${image_platform}.sh"

echo
echo "=== Generating standalone installer ==="

cat > "$output" << 'HEADER'
#!/usr/bin/env bash
set -euo pipefail

INSTALL_DIR="${INSTALL_DIR:-$HOME/cilexec}"

# ── Self-contained uninstaller ─────────────────────────────
# This installer script is the uninstaller: it never relies on files that were
# extracted into the installation directory, so uninstalling works even when
# that directory has already been deleted. It cleans the Docker resources,
# secrets, and exports that belong to this installation.
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

uninstall_installation() {
    local force="${1:-}"
    local project_hash compose_project volume_name image_name secret_dir
    project_hash="$(printf '%s\n' "$INSTALL_DIR" | hash_text)"
    project_hash="${project_hash:0:8}"
    compose_project="cilexec-${project_hash}"
    volume_name="cilexec-pgdata-${project_hash}"
    secret_dir="$INSTALL_DIR/docker/secrets"
    image_name="cilexec:local"
    if [[ -f "$INSTALL_DIR/.env" ]]; then
        while IFS='=' read -r key value; do
            if [[ "$key" == "CILEXEC_IMAGE" && "$value" =~ ^[A-Za-z0-9._/:@-]+$ ]]; then
                image_name="$value"
            fi
        done < "$INSTALL_DIR/.env"
    fi

    if [[ "$force" != "--force" ]]; then
        echo "This will permanently remove the CilExec instance installed at $INSTALL_DIR:"
        echo "  containers, the database volume ($volume_name), the image ($image_name),"
        echo "  secrets, and default exports. This cannot be undone."
        if command -v docker >/dev/null 2>&1; then
            echo
            echo "Current CilExec resources:"
            docker ps -a --filter "label=com.docker.compose.project=$compose_project" \
                --format '  container: {{.Names}} ({{.Status}})' 2>/dev/null || true
            docker volume inspect "$volume_name" --format '  volume: {{.Name}}' 2>/dev/null || true
            docker network ls --filter "label=com.docker.compose.project=$compose_project" \
                --format '  network: {{.Name}}' 2>/dev/null || true
        fi
        echo
        read -r -p "Type yes to continue: " confirm
        if [[ "$confirm" != "yes" ]]; then
            echo "Cancelled."
            exit 0
        fi
    fi

    if command -v docker >/dev/null 2>&1; then
        containers=$(docker ps -a --filter "label=com.docker.compose.project=$compose_project" \
            -q 2>/dev/null || true)
        if [[ -n "$containers" ]]; then
            while read -r container; do
                [[ -z "$container" ]] || docker rm -f "$container" >/dev/null 2>&1 || true
            done <<< "$containers"
        fi
        if [[ "$image_name" == "cilexec:local" ]]; then
            if docker image inspect "$image_name" >/dev/null 2>&1; then
                docker rmi "$image_name" >/dev/null 2>&1 || \
                    echo "Note: image $image_name is still in use and was kept." >&2
            fi
        fi
        docker volume rm "$volume_name" >/dev/null 2>&1 || true
        networks=$(docker network ls \
            --filter "label=com.docker.compose.project=$compose_project" \
            -q 2>/dev/null || true)
        if [[ -n "$networks" ]]; then
            while read -r network; do
                [[ -z "$network" ]] || docker network rm "$network" >/dev/null 2>&1 || true
            done <<< "$networks"
        fi
    fi

    if [[ -d "$secret_dir" ]]; then
        rm -f "$secret_dir/postgres-admin-password" \
            "$secret_dir/cilexec-migrator-password" \
            "$secret_dir/cilexec-runtime-password" \
            "$secret_dir/cilexec-effect-worker-password" \
            "$secret_dir/cilexec-readonly-password" \
            "$secret_dir/cilexec-exporter-password" \
            "$secret_dir/postgres-ca.crt" \
            "$secret_dir/postgres-server.crt" \
            "$secret_dir/postgres-server.key"
    fi
    if [[ -d "$INSTALL_DIR/exports" ]]; then
        rm -rf "$INSTALL_DIR/exports"
    fi
    echo "CilExec installation resources were removed."
}

# ── Uninstall entry point ──────────────────────────────────
UNINSTALL=false
FORCE=false
for argument in "$@"; do
    case "$argument" in
        --uninstall|-u) UNINSTALL=true ;;
        --force)        FORCE=true ;;
        *)
            echo "Unknown option: $argument" >&2
            echo "Usage: $0 [--uninstall [--force]]" >&2
            exit 2
            ;;
    esac
done
if [[ "$UNINSTALL" == true ]]; then
    echo "Uninstalling the CilExec instance installed at $INSTALL_DIR..."
    uninstall_installation "$([[ "$FORCE" == true ]] && echo --force || true)"
    exit 0
fi

echo "CilExec Standalone Installer"
echo "============================"
echo

# ── Detect OS ──────────────────────────────────────────────
OS="$(uname -s)"
case "$OS" in
    Darwin)  PLATFORM="macOS $(sw_vers -productVersion 2>/dev/null || echo)" ;;
    Linux)   PLATFORM="Linux ($(uname -m))" ;;
    MINGW*|MSYS*|CYGWIN*)
        PLATFORM="Windows"
        echo "Error: this installer must run in WSL on Windows."
        echo "  1. Install WSL: wsl --install"
        echo "  2. Run this script inside WSL"
        exit 1
        ;;
    *)
        PLATFORM="$OS ($(uname -m))"
        ;;
esac
echo "Platform: $PLATFORM"
echo

# ── Check Docker ───────────────────────────────────────────
check_docker() {
    if command -v docker &>/dev/null && docker info &>/dev/null 2>&1; then
        return 0
    fi
    return 1
}

if ! check_docker; then
    echo "Docker is not installed or not running."
    exit 1
fi

if ! docker compose version &>/dev/null 2>&1; then
    echo "Docker Compose plugin is not installed."
    echo "  https://docs.docker.com/compose/install/"
    exit 1
fi

# ── Extract payload ────────────────────────────────────────
mkdir -p "$INSTALL_DIR"
echo "Installing to: $INSTALL_DIR"

PAYLOAD_START=$(awk '/^__PAYLOAD__$/ {print NR+1; exit}' "$0")
echo "Extracting files ($(du -h "$0" | cut -f1) total)..."
tail -n +"$PAYLOAD_START" "$0" | tar xz -C "$INSTALL_DIR"
cd "$INSTALL_DIR"

expected_platform="$(<.cilexec-image-platform)"
docker_os="$(docker info --format '{{.OSType}}')"
docker_arch="$(docker info --format '{{.Architecture}}')"
case "$docker_arch" in
    amd64|x86_64) docker_arch="amd64" ;;
    arm64|aarch64) docker_arch="arm64" ;;
esac
if [[ "$docker_os-$docker_arch" != "$expected_platform" ]]; then
    echo "Error: this package requires Docker $expected_platform, not $docker_os-$docker_arch." >&2
    exit 1
fi

echo "Loading container image..."
gunzip -c cilexec-image.tar.gz | docker load

loaded_image="$(<.cilexec-image-name)"
loaded_os="$(docker image inspect --format '{{.Os}}' "$loaded_image")"
loaded_arch="$(docker image inspect --format '{{.Architecture}}' "$loaded_image")"
case "$loaded_arch" in
    amd64|x86_64) loaded_arch="amd64" ;;
    arm64|aarch64) loaded_arch="arm64" ;;
esac
if [[ "$loaded_os-$loaded_arch" != "$expected_platform" ]]; then
    echo "Error: loaded image architecture does not match $expected_platform." >&2
    exit 1
fi
if [[ "$loaded_image" != "cilexec:local" ]]; then
    docker tag "$loaded_image" cilexec:local
fi

chmod +x tools/Install.sh tools/Uninstall.sh tools/Shell.sh tools/Headless.sh \
    tools/HostMove.sh docker/*.sh

echo
echo "Installation complete!"
echo "  cd $INSTALL_DIR && ./tools/Install.sh"
echo "Uninstall later with:  $0 --uninstall"
exit 0
__PAYLOAD__
HEADER

# Create payload
temp_dir=$(mktemp -d)

if [[ -n "$image_archive" ]]; then
    gzip -c "$image_archive" > "$temp_dir/cilexec-image.tar.gz"
else
    docker save "$image" | gzip > "$temp_dir/cilexec-image.tar.gz"
fi
printf '%s\n' "$image" > "$temp_dir/.cilexec-image-name"
printf '%s\n' "$image_platform" > "$temp_dir/.cilexec-image-platform"
mkdir "$temp_dir/tools"
cp tools/Install.sh "$temp_dir/tools/"
cp tools/Uninstall.sh "$temp_dir/tools/"
cp tools/Shell.sh "$temp_dir/tools/"
cp tools/Headless.sh "$temp_dir/tools/"
cp tools/HostMove.sh "$temp_dir/tools/"
cp LICENSE "$temp_dir/"
cp README.md "$temp_dir/"
cp compose.yml "$temp_dir/"
cp Dockerfile "$temp_dir/"
cp .dockerignore "$temp_dir/"
if [[ -e docker/secrets/.rotation.lock ]]; then
    echo "Error: refusing to package database credentials from an active or stale rotation lock." >&2
    exit 1
fi
mkdir "$temp_dir/docker"
cp docker/create-secrets.sh docker/rotate-secrets.sh docker/healthcheck.sh \
    docker/terminal-client.c "$temp_dir/docker/"
cp -r docker/compose docker/postgres "$temp_dir/docker/"
mkdir "$temp_dir/docker/secrets"
cp docker/secrets/.gitignore "$temp_dir/docker/secrets/.gitignore"

tar czf - -C "$temp_dir" . >> "$output"
chmod +x "$output"

echo
echo "=== Done: build/cilexec-${version}-${image_platform}.sh ==="
ls -lh "$output"
