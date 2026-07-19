#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FCL_SOURCES=("$ROOT/packageTEST/tests/full" "$ROOT/packageTEST/tests/real-environment.fcl")
covered=0

check_provider() {
    local namespace="$1"
    local provider="$2"
    local name
    while IFS= read -r name; do
        [ -n "$name" ] || continue
        if ! rg -q --glob '*.fcl' "${namespace}\\.${name}[[:space:]]*\\(" "${FCL_SOURCES[@]}"; then
            printf 'Missing FCL coverage: %s.%s\n' "$namespace" "$name" >&2
            return 1
        fi
        covered=$((covered + 1))
    done < <(sed -n 's/.*case "\([A-Za-z0-9_]*\)".*/\1/p' "$provider" | sort -u)
}

PROVIDERS="$ROOT/src/main/java/com/follarce/function"
check_provider file "$PROVIDERS/FileFunctionProvider.java"
check_provider io "$PROVIDERS/IOFunctionProvider.java"
check_provider math "$PROVIDERS/MathFunctionProvider.java"
check_provider network "$PROVIDERS/NetworkFunctionProvider.java"
check_provider package "$PROVIDERS/PackageFunctionProvider.java"
check_provider path "$PROVIDERS/PathFunctionProvider.java"
check_provider system "$PROVIDERS/PrivilegedFunctionProvider.java"
check_provider process "$PROVIDERS/ProcessFunctionProvider.java"
check_provider socket "$PROVIDERS/SocketFunctionProvider.java"
check_provider swapPool "$PROVIDERS/SwapFunctionProvider.java"
check_provider term "$PROVIDERS/TermFunctionProvider.java"
check_provider user "$PROVIDERS/UserFunctionProvider.java"
check_provider util "$PROVIDERS/UtilFunctionProvider.java"

printf 'FCL_API_COVERAGE:pass:%d functions\n' "$covered"
