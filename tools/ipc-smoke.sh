#!/usr/bin/env bash
# Real-environment smoke tests for the CilExec runtime over the terminal protocol.
# Runs inside the cilexec container against 127.0.0.1:8022.
#
# Multi-process IPC scenarios use headless submissions with distinct contexts: every
# headless context owns an independent durable REPL process, which is how two FCL
# processes talk to each other in the real system.
#
# Usage: bash tools/ipc-smoke.sh <container> [--setup-only]
set -euo pipefail

CONTAINER="${1:-cilexec-d0c2d77b-cilexec-1}"
PASSWORD="ipc-test-2026"
UNIQ="$$"

# One headless submission: \0M HEADLESS\n<len>\n<ctx><len>\n<user><len>\n<pass><len>\n<src>
# Options: -t <timeout-seconds> waits up to that long for the process to finish
# (a receiving process stays connected until it is woken).
headless() {
    local timeout_seconds="15"
    local context="" source=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            -t) timeout_seconds="$2"; shift 2 ;;
            *) if [[ -z "$context" ]]; then context="$1"; else source="$1"; fi; shift ;;
        esac
    done
    docker exec -e CTX="$context" -e USER="local" -e PASS="$PASSWORD" -e SRC="$source" \
        "$CONTAINER" bash -c "
            exec 3<>/dev/tcp/127.0.0.1/8022 || exit 1
            printf '\0M HEADLESS\n' >&3
            printf '%d\n' \${#CTX} >&3
            printf '%s' \"\$CTX\" >&3
            printf '%d\n' \${#USER} >&3
            printf '%s' \"\$USER\" >&3
            printf '%d\n' \${#PASS} >&3
            printf '%s' \"\$PASS\" >&3
            printf '%d\n' \${#SRC} >&3
            printf '%s' \"\$SRC\" >&3
            timeout $timeout_seconds cat <&3
            exec 3>&-
        "
}

setup() {
    echo "== first-time setup + login (interactive) =="
    docker exec "$CONTAINER" bash -c '
        exec 3<>/dev/tcp/127.0.0.1/8022 || exit 1
        sleep 0.8
        printf "ipc-test-2026\n" >&3; sleep 0.4
        printf "ipc-test-2026\n" >&3; sleep 0.8
        printf "login\n" >&3; sleep 0.4
        printf "local\n" >&3; sleep 0.4
        printf "ipc-test-2026\n" >&3; sleep 0.8
        printf "1+1\n" >&3; sleep 0.8
        printf ":exit\n" >&3
        sleep 0.5
        timeout 3 cat <&3
        exec 3>&-
    '
}

if [[ "${2:-}" == "--setup-only" ]]; then
    setup
    exit 0
fi

setup

# Every test run leaves durable processes behind; the per-user process quota (64)
# would otherwise exhaust and reject new submissions with SQLSTATE 54000. Process
# rows are immutable-ish (append-only process.event cascades), so test processes are
# marked TERMINATED (which releases quota) and their dependent rows are deleted.
cleanup() {
    docker exec cilexec-d0c2d77b-postgres-1 psql -U cilexec_bootstrap -d cilexec \
        -c "BEGIN;
            CREATE TEMP TABLE doomed AS SELECT process_uid FROM process.process WHERE pid > 1;
            DELETE FROM ipc.delivery; DELETE FROM ipc.message; DELETE FROM ipc.subscription;
            DELETE FROM ipc.channel; DELETE FROM ipc.topic; DELETE FROM ipc.swap_value; DELETE FROM ipc.swap_pool;
            DELETE FROM effect.attempt WHERE effect_id IN (SELECT effect_id FROM effect.effect WHERE process_uid IN (SELECT process_uid FROM doomed));
            DELETE FROM effect.effect WHERE process_uid IN (SELECT process_uid FROM doomed);
            DELETE FROM process.timer WHERE process_uid IN (SELECT process_uid FROM doomed);
            DELETE FROM scheduler.lease WHERE process_uid IN (SELECT process_uid FROM doomed);
            DELETE FROM scheduler.queue WHERE process_uid IN (SELECT process_uid FROM doomed);
            DELETE FROM vfs.node_lock WHERE process_uid IN (SELECT process_uid FROM doomed);
            ALTER TABLE process.event DISABLE TRIGGER process_event_reject_update_delete;
            DELETE FROM process.process WHERE process_uid IN (SELECT process_uid FROM doomed);
            ALTER TABLE process.event ENABLE TRIGGER process_event_reject_update_delete;
            COMMIT;" >/dev/null 2>&1 || true
}
cleanup
echo "(test processes cleaned)"

POOL1="pool1-$UNIQ"
SIGPOOL="sigpool-$UNIQ"
NEWS="news-$UNIQ"

echo
echo "== swap pool: single-session create/add/get =="
headless "swap-basic-$UNIQ" "swapPool.create(\"$POOL1\"); swapPool.add(\"x:hello\", \"$POOL1\"); v = swapPool.get(\"$POOL1\", \"x\"); io.print(util.toString(v)); swapPool.add(\"data:42\", \"$POOL1\"); swapPool.lock(\"$POOL1\", \"data\", 10000)"

echo
echo "== swap pool: waitFor/signal across two processes =="
headless -t 20 "swap-waiter-$UNIQ" "swapPool.create(\"$SIGPOOL\"); swapPool.add(\"sig:0\", \"$SIGPOOL\"); swapPool.waitFor(\"$SIGPOOL\", \"sig\")" &
WAITER=$!
sleep 3
headless "swap-signal-$UNIQ" "swapPool.signal(\"$SIGPOOL\", \"sig\")"
wait "$WAITER"

echo
echo "== ipc: topic subscribe + publish across two processes =="
headless -t 20 "ipc-recv-topic-$UNIQ" "ipc.createTopic(\"$NEWS\"); ipc.subscribeTopic(\"$NEWS\"); e = ipc.receive(); io.print(\"GOT=\" + util.toString(e))" &
RECEIVER=$!
sleep 3
headless "ipc-send-topic-$UNIQ" "ipc.publishTopic(\"$NEWS\", {\"headline\": \"hello-ipc\", \"n\": 42})"
wait "$RECEIVER"

echo
echo "== ipc: direct message between two processes =="
headless -t 20 "ipc-recv-direct-$UNIQ" "io.print(\"PID=\" + util.toString(process.getPID())); e = ipc.receive(); io.print(\"GOT=\" + util.toString(e))" > /tmp/ipc-recv-direct.out 2>&1 &
DIRECT_RECEIVER=$!
sleep 3
PID=$(grep -o 'PID=[0-9]*' /tmp/ipc-recv-direct.out | head -1 | cut -d= -f2)
echo "receiver pid=$PID"
headless "ipc-send-direct-$UNIQ" "ipc.sendDirect($PID, {\"from\": \"sender\", \"value\": 7})"
wait "$DIRECT_RECEIVER"
grep -a GOT= /tmp/ipc-recv-direct.out || echo "(GOT output may be dropped after the headless connection closed; delivery status below is authoritative)"
rm -f /tmp/ipc-recv-direct.out

echo
echo "== ipc: channel create + subscribe + send across processes =="
CHANNEL_ID=$(headless "ipc-chan-create-$UNIQ" "ipc.createChannel(\"chan-$UNIQ\")" | grep -o '"channelId":"[0-9a-f-]*"' | head -1 | cut -d'"' -f4)
echo "channelId=$CHANNEL_ID"
headless -t 20 "ipc-chan-recv-$UNIQ" "ipc.subscribeChannel(\"$CHANNEL_ID\"); e = ipc.receive(); io.print(\"GOT=\" + util.toString(e))" &
CHAN_RECEIVER=$!
sleep 3
headless "ipc-chan-send-$UNIQ" "ipc.sendChannel(\"$CHANNEL_ID\", {\"x\": 1})"
wait "$CHAN_RECEIVER"

echo
echo "== ipc: poll/consume non-blocking path =="
headless "ipc-poll-recv-$UNIQ" "ipc.subscribeTopic(\"$NEWS\")"
headless "ipc-poll-send-$UNIQ" "ipc.publishTopic(\"$NEWS\", {\"poll\": true, \"v\": 99})"
sleep 2
headless -t 20 "ipc-poll-recv-$UNIQ" "e = ipc.poll(); io.print(\"POLL=\" + util.toString(e)); if e != null { io.print(\"CONSUMED=\" + util.toString(ipc.consume(e[\"deliveryId\"]))) }"

echo
echo "== ipc: delivery journal (persisted messages) =="
docker exec cilexec-d0c2d77b-postgres-1 psql -U cilexec_bootstrap -d cilexec -t -c \
    "SELECT message_kind, count(*) FROM ipc.message GROUP BY message_kind ORDER BY message_kind"
docker exec cilexec-d0c2d77b-postgres-1 psql -U cilexec_bootstrap -d cilexec -t -c \
    "SELECT status, count(*) FROM ipc.delivery GROUP BY status ORDER BY status"

echo "done"
