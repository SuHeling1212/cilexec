SET ROLE cilexec_owner;

-- name: migration.V012.create_effect
CREATE TABLE effect.effect (
    effect_id uuid PRIMARY KEY,
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    effect_type text NOT NULL CHECK (effect_type ~ '^[a-z][a-z0-9_.:-]*$'),
    idempotency_key text,
    idempotent boolean NOT NULL,
    remote_status_queryable boolean NOT NULL DEFAULT false,
    retry_policy_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status text NOT NULL CHECK (status IN ('PREPARED', 'CLAIMED', 'EXECUTING', 'COMPLETED', 'FAILED', 'UNKNOWN')),
    request_json jsonb,
    request_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    result_json jsonb,
    result_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    claimed_by uuid,
    prepared_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claimed_at timestamptz,
    executing_at timestamptz,
    completed_at timestamptz,
    failure_code text,
    failure_message text,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (effect_id, owner_id),
    FOREIGN KEY (process_uid, owner_id) REFERENCES process.process(process_uid, owner_id) ON DELETE RESTRICT,
    CHECK (request_json IS NOT NULL OR request_object_hash IS NOT NULL),
    CHECK ((status = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL) OR status <> 'CLAIMED'),
    CHECK ((status = 'EXECUTING' AND executing_at IS NOT NULL) OR status <> 'EXECUTING'),
    CHECK ((status = 'COMPLETED' AND completed_at IS NOT NULL) OR status <> 'COMPLETED'),
    CHECK ((status IN ('FAILED', 'UNKNOWN') AND failure_code IS NOT NULL)
        OR status NOT IN ('FAILED', 'UNKNOWN')),
    CHECK (
        (status = 'PREPARED' AND claimed_by IS NULL AND claimed_at IS NULL
            AND executing_at IS NULL AND result_json IS NULL AND result_object_hash IS NULL
            AND completed_at IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'CLAIMED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
            AND executing_at IS NULL AND result_json IS NULL AND result_object_hash IS NULL
            AND completed_at IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'EXECUTING' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
            AND executing_at IS NOT NULL AND result_json IS NULL AND result_object_hash IS NULL
            AND completed_at IS NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status = 'COMPLETED' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
            AND executing_at IS NOT NULL
            AND num_nonnulls(result_json, result_object_hash) = 1
            AND completed_at IS NOT NULL AND failure_code IS NULL AND failure_message IS NULL)
        OR (status IN ('FAILED', 'UNKNOWN') AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
            AND executing_at IS NOT NULL AND result_json IS NULL AND result_object_hash IS NULL
            AND completed_at IS NULL AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
    )
);
CREATE UNIQUE INDEX ux_effect_idempotency
    ON effect.effect(owner_id, effect_type, idempotency_key) WHERE idempotency_key IS NOT NULL;

-- name: migration.V012.create_effect_attempt
CREATE TABLE effect.attempt (
    attempt_id uuid PRIMARY KEY,
    effect_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    runner_id uuid NOT NULL REFERENCES scheduler.runner(runner_id) ON DELETE RESTRICT,
    status text NOT NULL CHECK (status IN ('CLAIMED', 'EXECUTING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    remote_reference text,
    result_json jsonb,
    error_code text,
    error_message text,
    UNIQUE (effect_id, attempt_number),
    FOREIGN KEY (effect_id, owner_id) REFERENCES effect.effect(effect_id, owner_id) ON DELETE CASCADE,
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN') AND finished_at IS NOT NULL)
        OR status NOT IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CHECK (
        (status IN ('CLAIMED', 'EXECUTING') AND finished_at IS NULL
            AND result_json IS NULL AND error_code IS NULL AND error_message IS NULL)
        OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL
            AND result_json IS NOT NULL AND error_code IS NULL AND error_message IS NULL)
        OR (status IN ('FAILED', 'UNKNOWN') AND finished_at IS NOT NULL
            AND result_json IS NULL AND error_code IS NOT NULL AND error_message IS NOT NULL)
    )
);

-- name: effect.claimNext.index
CREATE INDEX ix_effect_claim_next ON effect.effect(prepared_at, effect_id) WHERE status = 'PREPARED';
CREATE INDEX ix_effect_recovery ON effect.effect(status, claimed_at)
    WHERE status IN ('CLAIMED', 'EXECUTING', 'UNKNOWN');
CREATE INDEX ix_effect_attempt_history ON effect.attempt(effect_id, attempt_number DESC);

-- name: migration.V012.effect_rls
DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['effect', 'attempt']
    LOOP
        EXECUTE format('ALTER TABLE effect.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('ALTER TABLE effect.%I FORCE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('CREATE POLICY %I ON effect.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON effect.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON effect.%I TO cilexec_effect_worker USING (true) WITH CHECK (true)',
            relation_name || '_worker_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON effect.%I FOR SELECT TO cilexec_readonly USING (true)',
            relation_name || '_readonly_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON effect.%I FOR SELECT TO PUBLIC USING (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', relation_name);
        EXECUTE format('CREATE POLICY %I ON effect.%I FOR INSERT TO PUBLIC WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal_insert', relation_name);
    END LOOP;
END
$rls$;

-- name: migration.V012.effect_grants
GRANT SELECT, INSERT, UPDATE ON effect.effect, effect.attempt TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE ON effect.effect, effect.attempt TO cilexec_effect_worker;
GRANT SELECT ON effect.effect, effect.attempt TO cilexec_readonly;

COMMENT ON TABLE effect.effect IS 'Durable request/result boundary for every database-external operation';

RESET ROLE;
