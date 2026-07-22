SET ROLE cilexec_owner;

-- name: migration.V014.create_audit_event
CREATE TABLE audit.event (
    event_id uuid PRIMARY KEY,
    owner_id uuid,
    actor_type text NOT NULL CHECK (actor_type IN (
        'USER', 'RUNTIME', 'EFFECT_WORKER', 'ADMINISTRATOR', 'SYSTEM'
    )),
    actor_id text NOT NULL,
    action text NOT NULL CHECK (btrim(action) <> ''),
    resource_type text NOT NULL CHECK (btrim(resource_type) <> ''),
    resource_id text NOT NULL CHECK (btrim(resource_id) <> ''),
    result text NOT NULL CHECK (result IN ('SUCCEEDED', 'DENIED', 'FAILED')),
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (owner_id) REFERENCES auth.user_account(user_id) ON DELETE SET NULL
);

-- name: migration.V014.create_retention_policy
CREATE TABLE audit.retention_policy (
    event_type text PRIMARY KEY,
    retain_for interval NOT NULL CHECK (retain_for > interval '0 seconds'),
    enabled boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- name: migration.V014.audit_indexes
CREATE INDEX ix_audit_event_owner_time ON audit.event(owner_id, created_at DESC);
CREATE INDEX ix_audit_event_resource ON audit.event(resource_type, resource_id, created_at DESC);
CREATE INDEX ix_audit_event_action_time ON audit.event(action, created_at DESC);

-- name: migration.V014.audit_immutability
CREATE TRIGGER audit_event_reject_update_delete
BEFORE UPDATE OR DELETE ON audit.event
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: migration.V014.audit_rls
ALTER TABLE audit.event ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.event FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_event_owner_control ON audit.event TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY audit_event_runtime_control ON audit.event TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY audit_event_worker_insert ON audit.event FOR INSERT TO cilexec_effect_worker WITH CHECK (true);
CREATE POLICY audit_event_readonly_control ON audit.event FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY audit_event_principal ON audit.event FOR SELECT TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id());
CREATE POLICY audit_event_principal_insert ON audit.event FOR INSERT TO PUBLIC
    WITH CHECK (owner_id = auth.current_cilexec_user_id()
        AND actor_type = 'USER'
        AND actor_id = auth.current_cilexec_user_id()::text);

-- retention policy is an explicit system table and has no RLS.
-- name: migration.V014.audit_grants
GRANT SELECT, INSERT ON audit.event TO cilexec_runtime;
GRANT INSERT ON audit.event TO cilexec_effect_worker;
GRANT SELECT ON audit.event, audit.retention_policy TO cilexec_readonly;

COMMENT ON TABLE audit.event IS 'Append-only security, management, package, effect, and recovery audit trail';

RESET ROLE;
