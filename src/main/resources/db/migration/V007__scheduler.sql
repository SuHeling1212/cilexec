SET ROLE cilexec_owner;

-- name: migration.V007.create_runner
CREATE TABLE scheduler.runner (
    runner_id uuid PRIMARY KEY,
    boot_id uuid NOT NULL REFERENCES meta.boot(boot_id) ON DELETE RESTRICT,
    runner_kind text NOT NULL CHECK (runner_kind IN ('SCHEDULER', 'EFFECT', 'TIMER', 'RECOVERY')),
    status text NOT NULL CHECK (status IN ('STARTING', 'ACTIVE', 'DRAINING', 'STOPPED', 'FENCED')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    heartbeat_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    stopped_at timestamptz,
    CHECK ((status = 'STOPPED' AND stopped_at IS NOT NULL) OR status <> 'STOPPED')
);

-- name: migration.V007.create_queue
CREATE TABLE scheduler.queue (
    process_uid uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    queue_state text NOT NULL CHECK (queue_state IN ('READY', 'CLAIMED', 'BLOCKED', 'REMOVED')),
    ready_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    enqueued_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claimed_at timestamptz,
    claimed_by uuid REFERENCES scheduler.runner(runner_id) ON DELETE SET NULL,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK ((queue_state = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL)
        OR queue_state <> 'CLAIMED')
);

-- name: migration.V007.create_lease
CREATE TABLE scheduler.lease (
    process_uid uuid PRIMARY KEY REFERENCES process.process(process_uid) ON DELETE CASCADE,
    owner_id uuid NOT NULL,
    runner_id uuid NOT NULL REFERENCES scheduler.runner(runner_id) ON DELETE RESTRICT,
    boot_id uuid NOT NULL REFERENCES meta.boot(boot_id) ON DELETE RESTRICT,
    execution_epoch bigint NOT NULL CHECK (execution_epoch > 0),
    claimed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    heartbeat_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK (heartbeat_at >= claimed_at),
    CHECK (expires_at > heartbeat_at)
);

-- name: scheduler.claimNext.index
CREATE INDEX ix_queue_claim_next
    ON scheduler.queue(enqueued_at, process_uid) INCLUDE (ready_at)
    WHERE queue_state = 'READY';
-- name: scheduler.expiredLease.index
CREATE INDEX ix_lease_expired ON scheduler.lease(expires_at, process_uid);
CREATE INDEX ix_runner_heartbeat ON scheduler.runner(status, heartbeat_at);

-- queue is user-visible; runner and lease are explicitly system-owned.
-- name: migration.V007.queue_rls
ALTER TABLE scheduler.queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE scheduler.queue FORCE ROW LEVEL SECURITY;
CREATE POLICY queue_owner_control ON scheduler.queue TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY queue_runtime_control ON scheduler.queue TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY queue_readonly_control ON scheduler.queue FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY queue_principal ON scheduler.queue TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

-- name: migration.V007.scheduler_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler.queue TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON scheduler.runner, scheduler.lease TO cilexec_runtime;
GRANT SELECT ON scheduler.runner, scheduler.queue, scheduler.lease TO cilexec_readonly;

COMMENT ON INDEX scheduler.ix_queue_claim_next IS
    'Stable FIFO claim order for SELECT FOR UPDATE SKIP LOCKED';
COMMENT ON TABLE scheduler.lease IS
    'System-only lease; execution_epoch fences stale workers from committing';

RESET ROLE;
