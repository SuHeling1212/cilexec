#!/usr/bin/env bash
set -euo pipefail

market_dir="$(cd "$(dirname "$0")" && pwd)"
project_dir="$(cd "$market_dir/.." && pwd)"
application_jar="$project_dir/target/cilexec-app.jar"
umask 022

if [[ ! -f "$application_jar" ]] || [[ "$project_dir/pom.xml" -nt "$application_jar" ]] \
        || [[ -n "$(find "$project_dir/src" -type f -newer "$application_jar" -print -quit)" ]]; then
    echo "Building the CilExec package tool..."
    mvn -f "$project_dir/pom.xml" --batch-mode --no-transfer-progress -DskipTests package
fi

temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/cilexec-market.XXXXXX")"
publication_temp=""
cleanup() {
    if [[ -n "$publication_temp" ]]; then
        rm -f -- "$publication_temp"
    fi
    rm -rf -- "$temporary_dir"
}
trap cleanup EXIT HUP INT TERM

for package_name in editor market; do
    source_dir="$market_dir/sources/$package_name"
    package_version="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["version"])' "$source_dir/package.json")"
    if [[ ! "$package_version" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
        echo "Refusing unsafe package version in $source_dir/package.json: $package_version" >&2
        exit 65
    fi
    repository_dir="$market_dir/repository/packages/cilexec/$package_name/$package_version"
    destination="$repository_dir/$package_name.db"
    java --enable-native-access=ALL-UNNAMED \
        -jar "$application_jar" package build "$source_dir" "$temporary_dir/$package_name.db"
    mkdir -p "$repository_dir"
    if [[ -e "$destination" ]]; then
        if [[ ! -f "$destination" || -L "$destination" ]]; then
            echo "Refusing non-regular published package path: $destination" >&2
            exit 73
        fi
        if ! cmp -s "$temporary_dir/$package_name.db" "$destination"; then
            echo "Refusing to replace immutable package $destination; bump its version first." >&2
            exit 73
        fi
    else
        publication_temp="$(mktemp "$repository_dir/.${package_name}.db.XXXXXX")"
        install -m 0644 "$temporary_dir/$package_name.db" "$publication_temp"
        mv -f -- "$publication_temp" "$destination"
        publication_temp=""
    fi
    echo "Published cilexec/$package_name/$package_version:"
    echo "  $destination"
done
