#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$project_dir"

benchmark_project="${CILEXEC_BENCHMARK_PROJECT:-cilexec-memory-bench-$$}"
benchmark_image_tag="${CILEXEC_BENCHMARK_IMAGE_TAG:-local}"
benchmark_users="${CILEXEC_BENCHMARK_USERS:-10}"
benchmark_password="12345678"
compose=(docker compose -p "$benchmark_project" -f compose.yml -f docker/compose/ephemeral.yml)

if [[ ! "$benchmark_users" =~ ^[1-9][0-9]*$ ]] || (( benchmark_users > 9999 )); then
    echo "CILEXEC_BENCHMARK_USERS must be an integer between 1 and 9999." >&2
    exit 2
fi

cleanup() {
    CILEXEC_IMAGE_TAG="$benchmark_image_tag" "${compose[@]}" down -v --remove-orphans \
        >/dev/null 2>&1 || true
}
trap cleanup EXIT HUP INT TERM

if ! docker image inspect "cilexec:${benchmark_image_tag}" >/dev/null 2>&1; then
    echo "Missing image cilexec:${benchmark_image_tag}. Build it before running this benchmark." >&2
    exit 1
fi

CILEXEC_IMAGE_TAG="$benchmark_image_tag" "${compose[@]}" up -d postgres >/dev/null
CILEXEC_IMAGE_TAG="$benchmark_image_tag" "${compose[@]}" run --rm migrate >/dev/null
CILEXEC_IMAGE_TAG="$benchmark_image_tag" "${compose[@]}" up -d --no-deps cilexec >/dev/null

runtime_id="$("${compose[@]}" ps -q cilexec)"
postgres_id="$("${compose[@]}" ps -q postgres)"
for _ in {1..80}; do
    if docker exec "$runtime_id" /usr/local/bin/cilexec-terminal-client --probe 8022 \
            >/dev/null 2>&1; then
        break
    fi
    sleep 0.25
done
if ! docker exec "$runtime_id" /usr/local/bin/cilexec-terminal-client --probe 8022 \
        >/dev/null 2>&1; then
    echo "Benchmark Runtime did not open terminal port 8022." >&2
    exit 1
fi

send_terminal_bytes() {
    local payload="$1"
    docker exec "$runtime_id" bash -lc \
        "exec 3<>/dev/tcp/127.0.0.1/8022; printf '%b' '$payload' >&3; exec 3>&-; exec 3<&-"
}

sample_phase() {
    local phase="$1"
    local sample_number java_rss_kib cgroup_usage
    for sample_number in 1 2 3 4 5 6 7; do
        java_rss_kib="$(docker exec "$runtime_id" sh -c 'ps -o rss= -p 1' | tr -d ' ')"
        cgroup_usage="$(docker stats --no-stream --format '{{.MemUsage}}' "$runtime_id" \
            | cut -d/ -f1 | tr -d ' ')"
        echo "$phase,$sample_number,$java_rss_kib,$cgroup_usage"
        sleep 1
    done
}

echo "phase,sample,java_rss_kib,runtime_cgroup_memory"
sample_phase baseline

send_terminal_bytes "${benchmark_password}\n${benchmark_password}\ndisconnect\n"
for _ in {1..40}; do
    if [[ "$(docker exec "$postgres_id" psql -U cilexec_bootstrap -d cilexec -Atc \
            "select count(*) from auth.user_account where username='local'")" == "1" ]]; then
        break
    fi
    sleep 0.25
done

create_sleeping_user() {
    local suffix="$1"
    local username created
    username="$(printf 'bench%04d' "$suffix")"
    send_terminal_bytes "create\n${username}\n${benchmark_password}\n${benchmark_password}\nN\nwhile(true){ util.sleep(250) }\ndisconnect\n"
    for _ in {1..80}; do
        created="$(docker exec "$postgres_id" psql -U cilexec_bootstrap -d cilexec -Atc \
            "select count(*) from process.process p join auth.user_account u on u.user_id=p.owner_id where u.username='${username}'")"
        if [[ "$created" == "1" ]]; then
            return
        fi
        sleep 0.125
    done
    echo "Timed out waiting for benchmark process owned by $username." >&2
    exit 1
}

create_sleeping_user 1
sleep 10
sample_phase one

if (( benchmark_users > 1 )); then
    for ((user_number = 2; user_number <= benchmark_users; user_number++)); do
        create_sleeping_user "$user_number"
    done
fi
sleep 10

process_count="$(docker exec "$postgres_id" psql -U cilexec_bootstrap -d cilexec -Atc \
    "select count(*) from process.process")"
if [[ "$process_count" != "$benchmark_users" ]]; then
    echo "Expected $benchmark_users benchmark processes, found $process_count." >&2
    exit 1
fi
sample_phase "$benchmark_users"
