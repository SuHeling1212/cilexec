#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
RUNTIME_ROOT="$ROOT/packageTEST/.runtime"
TOOL_SOURCE="$ROOT/packageTEST/tests/tools/FclTestRuntime.java"
HTTP_FIXTURE_SOURCE="$ROOT/packageTEST/tests/tools/LocalHttpFixture.java"
TOOLS_CLASSES="$RUNTIME_ROOT/tools"
CLASSPATH="$ROOT/target/classes:$ROOT/target/dependency/*"
ONLY_CASE="${1-}"

mkdir -p "$RUNTIME_ROOT" "$TOOLS_CLASSES"
bash "$ROOT/packageTEST/tests/check-fcl-api-coverage.sh"
mvn -q -f "$ROOT/pom.xml" compile dependency:copy-dependencies
javac -cp "$CLASSPATH" -d "$TOOLS_CLASSES" "$TOOL_SOURCE" "$HTTP_FIXTURE_SOURCE"
TOOL_CLASSPATH="$CLASSPATH:$TOOLS_CLASSES"

run_case() {
    local name="$1"
    local script="$2"
    local result_path="$3"
    local stdin_text="${4-}"
    local runtime
    runtime="$(mktemp -d "$RUNTIME_ROOT/${name}.XXXXXX")"
    LAST_RUNTIME="$runtime"

    java -cp "$TOOL_CLASSPATH" FclTestRuntime seed "$runtime" "$script"
    if [ -n "$stdin_text" ]; then
        printf '%s' "$stdin_text" | (cd "$runtime" && java -cp "$CLASSPATH" com.follarce.Main)
    else
        (cd "$runtime" && java -cp "$CLASSPATH" com.follarce.Main)
    fi
    java -cp "$TOOL_CLASSPATH" FclTestRuntime assert "$runtime" "$result_path"
    printf 'FCL_RUNTIME:%s:%s\n' "$name" "$runtime"
}

selected() {
    [ -z "$ONLY_CASE" ] || [ "$ONLY_CASE" = "$1" ]
}

run_recovery_case() {
    local runtime
    local engine_pid
    runtime="$(mktemp -d "$RUNTIME_ROOT/recovery.XXXXXX")"
    java -cp "$TOOL_CLASSPATH" FclTestRuntime seed "$runtime" "$ROOT/packageTEST/tests/full/recovery.fcl"

    (cd "$runtime" && exec java -cp "$CLASSPATH" com.follarce.Main) &
    engine_pid=$!
    sleep 0.4
    if kill -0 "$engine_pid" 2>/dev/null; then
        kill -9 "$engine_pid"
        wait "$engine_pid" 2>/dev/null || true
    fi

    (cd "$runtime" && java -cp "$CLASSPATH" com.follarce.Main)
    java -cp "$TOOL_CLASSPATH" FclTestRuntime assert "$runtime" "/user/local/app/data/fcl-tests/recovery-result.json"
    printf 'FCL_RUNTIME:%s:%s\n' "recovery" "$runtime"
}

run_reset_case() {
    local runtime
    local engine_pid
    local reset_observed=false
    runtime="$(mktemp -d "$RUNTIME_ROOT/reset.XXXXXX")"
    java -cp "$TOOL_CLASSPATH" FclTestRuntime seed "$runtime" "$ROOT/packageTEST/tests/full/reset.fcl"

    (cd "$runtime" && exec java -cp "$CLASSPATH" com.follarce.Main) &
    engine_pid=$!
    for _ in $(seq 1 100); do
        if [ ! -e "$runtime/cilexec_root/system/config/users.json" ]; then
            reset_observed=true
            break
        fi
        if ! kill -0 "$engine_pid" 2>/dev/null; then
            break
        fi
        sleep 0.05
    done

    if kill -0 "$engine_pid" 2>/dev/null; then
        kill "$engine_pid" 2>/dev/null || true
    fi
    wait "$engine_pid" 2>/dev/null || true
    if [ "$reset_observed" != true ]; then
        printf 'FCL_CASE_FAIL:reset:VFS root was not deleted\n' >&2
        return 1
    fi
    printf 'FCL_CASE_PASS:reset:{"case":"reset","passed":true}\n'
    printf 'FCL_RUNTIME:%s:%s\n' "reset" "$runtime"
}

run_package_case() {
    local runtime
    run_case "package" "$ROOT/packageTEST/tests/real-environment.fcl" "/user/fclreal/app/e2e/result.json"
    runtime="$LAST_RUNTIME"

    java -cp "$TOOL_CLASSPATH" FclTestRuntime seed "$runtime" "$ROOT/packageTEST/tests/full/package-gc.fcl"
    (cd "$runtime" && java -cp "$CLASSPATH" com.follarce.Main)
    java -cp "$TOOL_CLASSPATH" FclTestRuntime assert "$runtime" "/user/local/app/data/fcl-tests/package-gc-result.json"
    printf 'FCL_RUNTIME:%s:%s\n' "package-gc" "$runtime"
}

if selected "core"; then run_case "core" "$ROOT/packageTEST/tests/full/core.fcl" "/user/local/app/data/fcl-tests/core-result.json"; fi
if selected "swap"; then run_case "swap" "$ROOT/packageTEST/tests/full/swap.fcl" "/user/local/app/data/fcl-tests/swap-result.json"; fi
if selected "process"; then run_case "process" "$ROOT/packageTEST/tests/full/process.fcl" "/user/local/app/data/fcl-tests/process-result.json"; fi
if selected "exec"; then run_case "exec" "$ROOT/packageTEST/tests/full/exec.fcl" "/user/local/app/data/exec-output/exec-result.json"; fi
if selected "socket"; then run_case "socket" "$ROOT/packageTEST/tests/full/socket.fcl" "/user/local/app/data/fcl-tests/socket-result.json"; fi

if selected "network"; then
    java -cp "$TOOL_CLASSPATH" LocalHttpFixture 18765 &
    http_pid=$!
    cleanup_http() {
        kill "$http_pid" 2>/dev/null || true
        wait "$http_pid" 2>/dev/null || true
    }
    trap cleanup_http EXIT
    sleep 0.2
    run_case "network" "$ROOT/packageTEST/tests/full/network.fcl" "/user/local/app/data/fcl-tests/network-result.json"
    cleanup_http
    trap - EXIT
fi

if selected "system"; then run_case "system" "$ROOT/packageTEST/tests/full/system.fcl" "/user/local/app/data/fcl-tests/system-result.json"; fi
if selected "input-util"; then run_case "input-util" "$ROOT/packageTEST/tests/full/input-util.fcl" "/user/local/app/data/fcl-tests/input-util-result.json" $'alpha\n'; fi
if selected "input-io"; then run_case "input-io" "$ROOT/packageTEST/tests/full/input-io.fcl" "/user/local/app/data/fcl-tests/input-io-result.json" $'beta\n'; fi
if selected "read-char"; then run_case "read-char" "$ROOT/packageTEST/tests/full/read-char.fcl" "/user/local/app/data/fcl-tests/read-char-result.json" "Z"; fi
if selected "package"; then run_package_case; fi
if selected "recovery"; then run_recovery_case; fi
if selected "reset"; then run_reset_case; fi
