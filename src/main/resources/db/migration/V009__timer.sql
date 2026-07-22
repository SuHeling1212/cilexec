SET ROLE cilexec_owner;

-- name: migration.V009.create_timer
CREATE TABLE process.timer (
    timer_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    wake_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SCHEDULED', 'CLAIMED', 'FIRED', 'CANCELLED')),
    claimed_by uuid,
    claimed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    fired_at timestamptz,
    cancelled_at timestamptz,
    payload_json jsonb NOT NULL DEFAULT 'null'::jsonb,
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    CHECK ((status = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL) OR status <> 'CLAIMED'),
    CHECK ((status = 'FIRED' AND fired_at IS NOT NULL) OR status <> 'FIRED'),
    CHECK ((status = 'CANCELLED' AND cancelled_at IS NOT NULL) OR status <> 'CANCELLED')
);

-- name: timer.claimDue.index
CREATE INDEX ix_timer_claim_due ON process.timer(wake_at, timer_id) WHERE status = 'SCHEDULED';
CREATE INDEX ix_timer_process ON process.timer(process_uid, status);

-- name: migration.V009.timer_rls
ALTER TABLE process.timer ENABLE ROW LEVEL SECURITY;
ALTER TABLE process.timer FORCE ROW LEVEL SECURITY;
CREATE POLICY timer_owner_control ON process.timer TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY timer_runtime_control ON process.timer TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY timer_readonly_control ON process.timer FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY timer_principal ON process.timer TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id());

-- name: migration.V009.timer_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON process.timer TO cilexec_runtime;
GRANT SELECT ON process.timer TO cilexec_readonly;

COMMENT ON TABLE process.timer IS 'Authoritative timer state; JVM sleeps are only an optimization';

RESET ROLE;
