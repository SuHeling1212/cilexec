#!/usr/bin/env bash
# Prints the single CilExec release version configured for every build surface.
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
config="$project_dir/.mvn/maven.config"
version="$(sed -n 's/^-Drevision=//p' "$config")"

if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9._-]+)?$ ]]; then
    echo "Error: .mvn/maven.config must contain exactly one valid -Drevision=<version>." >&2
    exit 1
fi

printf '%s\n' "$version"
