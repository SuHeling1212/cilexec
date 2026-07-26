SET ROLE cilexec_owner;

CREATE TABLE ipc.swap_pool (
    pool_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    owner_process_uid uuid NOT NULL,
    pool_name text NOT NULL CHECK (pool_name ~ '^[A-Za-z0-9_-]{1,128}$'),
    created_at timestamptz NOT NULL,
    UNIQUE (owner_id, pool_name),
    FOREIGN KEY (owner_process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    UNIQUE (pool_id, owner_id)
);

CREATE TABLE ipc.swap_value (
    pool_id uuid NOT NULL,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    variable_name text NOT NULL CHECK (variable_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    value_type text NOT NULL CHECK (btrim(value_type) <> ''),
    value_payload text NOT NULL,
    retention_mode text NOT NULL CHECK (retention_mode IN ('ALWAYS', 'SYNC', 'TIMES')),
    remaining_reads integer CHECK (remaining_reads IS NULL OR remaining_reads > 0),
    changed boolean NOT NULL DEFAULT false,
    lock_process_uid uuid,
    lock_execution_epoch bigint,
    lease_until timestamptz,
    fencing_token bigint NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (pool_id, variable_name),
    FOREIGN KEY (pool_id, owner_id) REFERENCES ipc.swap_pool(pool_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (lock_process_uid)
        REFERENCES process.process(process_uid) ON DELETE RESTRICT,
    CHECK ((retention_mode = 'TIMES') = (remaining_reads IS NOT NULL)),
    CHECK ((lock_process_uid IS NULL AND lock_execution_epoch IS NULL AND lease_until IS NULL)
        OR (lock_process_uid IS NOT NULL AND lock_execution_epoch IS NOT NULL
            AND lock_execution_epoch >= 0 AND lease_until IS NOT NULL))
);

ALTER TABLE ipc.swap_pool ENABLE ROW LEVEL SECURITY;
ALTER TABLE ipc.swap_pool FORCE ROW LEVEL SECURITY;
ALTER TABLE ipc.swap_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE ipc.swap_value FORCE ROW LEVEL SECURITY;

CREATE POLICY swap_pool_owner_control ON ipc.swap_pool TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY swap_pool_runtime_control ON ipc.swap_pool TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY swap_pool_readonly_control ON ipc.swap_pool FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY swap_pool_principal ON ipc.swap_pool TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id())
    WITH CHECK (owner_id = auth.current_cilexec_user_id());

CREATE POLICY swap_value_owner_control ON ipc.swap_value TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY swap_value_runtime_control ON ipc.swap_value TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY swap_value_readonly_control ON ipc.swap_value FOR SELECT TO cilexec_readonly USING (true);
CREATE POLICY swap_value_principal ON ipc.swap_value TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id())
    WITH CHECK (owner_id = auth.current_cilexec_user_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON ipc.swap_pool, ipc.swap_value TO cilexec_runtime;
GRANT SELECT ON ipc.swap_pool, ipc.swap_value TO cilexec_readonly;

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES
    ('ipc', 'swap_pool', 'USER_SCOPED', 'owner_id', 'durable owner-scoped FCL swap pool'),
    ('ipc', 'swap_value', 'USER_SCOPED', 'owner_id', 'durable fenced FCL swap value');

-- Existing and future user roles receive the same owner-scoped DML grants through PUBLIC.
-- RLS plus the verified current_cilexec_user_id claim remains the mandatory boundary.
GRANT SELECT, INSERT, UPDATE, DELETE ON ipc.swap_pool, ipc.swap_value TO PUBLIC;

RESET ROLE;
