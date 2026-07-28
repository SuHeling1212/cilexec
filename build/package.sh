#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

version="${1:-$(date +%Y%m%d)}"
output="$project_dir/build/cilexec-${version}.sh"

echo "=== Building image ==="
docker compose -f compose.yml -f compose.persistent.yml build

echo
echo "=== Generating standalone installer ==="

cat > "$output" << 'HEADER'
#!/usr/bin/env bash
set -euo pipefail

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
INSTALL_DIR="${INSTALL_DIR:-$HOME/cilexec}"
mkdir -p "$INSTALL_DIR"
echo "Installing to: $INSTALL_DIR"

PAYLOAD_START=$(awk '/^__PAYLOAD__$/ {print NR+1; exit}' "$0")
echo "Extracting files ($(du -h "$0" | cut -f1) total)..."
tail -n +"$PAYLOAD_START" "$0" | tar xz -C "$INSTALL_DIR"
cd "$INSTALL_DIR"

echo "Loading container image..."
gunzip -c cilexec-local.tar.gz | docker load

chmod +x Install.sh Uninstall.sh Shell.sh

echo
echo "Installation complete!"
echo "  cd $INSTALL_DIR && ./Install.sh"
exit 0
__PAYLOAD__
HEADER

# Create payload
temp_dir=$(mktemp -d)
trap "rm -rf $temp_dir" EXIT

docker save cilexec:local | gzip > "$temp_dir/cilexec-local.tar.gz"
cp Install.sh "$temp_dir/"
cp Uninstall.sh "$temp_dir/"
cp Shell.sh "$temp_dir/"
cp compose.yml "$temp_dir/"
cp compose.persistent.yml "$temp_dir/"
cp Dockerfile "$temp_dir/"
cp .dockerignore "$temp_dir/"
cp -r docker "$temp_dir/docker"
rm -f "$temp_dir/docker/secrets/"*

tar czf - -C "$temp_dir" . >> "$output"
chmod +x "$output"

echo
echo "=== Done: build/cilexec-${version}.sh ==="
ls -lh "$output"
