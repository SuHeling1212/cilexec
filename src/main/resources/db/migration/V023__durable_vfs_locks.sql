SET ROLE cilexec_owner;

CREATE TABLE vfs.node_lock (
    node_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    process_uid uuid NOT NULL REFERENCES process.process(process_uid) ON DELETE RESTRICT,
    execution_epoch bigint NOT NULL CHECK (execution_epoch >= 0),
    lease_until timestamptz NOT NULL,
    fencing_token bigint NOT NULL CHECK (fencing_token > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    FOREIGN KEY (node_id, owner_id) REFERENCES vfs.node(node_id, owner_id) ON DELETE CASCADE,
    CHECK (lease_until > updated_at)
);

ALTER TABLE vfs.node_lock ENABLE ROW LEVEL SECURITY;
ALTER TABLE vfs.node_lock FORCE ROW LEVEL SECURITY;
CREATE POLICY node_lock_owner_control ON vfs.node_lock TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY node_lock_runtime_control ON vfs.node_lock TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY node_lock_readonly_control ON vfs.node_lock FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY node_lock_principal ON vfs.node_lock TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id())
    WITH CHECK (owner_id = auth.current_cilexec_user_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON vfs.node_lock TO cilexec_runtime, PUBLIC;
GRANT SELECT ON vfs.node_lock TO cilexec_readonly;

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('vfs', 'node_lock', 'USER_SCOPED', 'owner_id',
        'durable process-generation VFS lease with fencing token');

RESET ROLE;
