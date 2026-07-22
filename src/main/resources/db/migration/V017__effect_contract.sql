SET ROLE cilexec_owner;

-- Effect handlers are addressed by a stable lower-case namespace and member.
-- name: migration.V017.constrain_effect_type
ALTER TABLE effect.effect
    ADD CONSTRAINT ck_effect_type_namespaced
    CHECK (
        char_length(effect_type) <= 128
        AND effect_type ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9_-]*)+$'
    );

-- Requests, like completed results, have one authoritative representation.
-- name: migration.V017.constrain_effect_request_representation
ALTER TABLE effect.effect
    ADD CONSTRAINT ck_effect_request_exactly_one
    CHECK (num_nonnulls(request_json, request_object_hash) = 1);

-- Upgrade rows that relied on the old empty-object default before validating
-- the complete policy document. Column values remain the source of truth.
UPDATE effect.effect
SET retry_policy_json = jsonb_build_object(
        'idempotent', idempotent,
        'idempotencyKey', idempotency_key,
        'remotelyQueryable', remote_status_queryable,
        'retryable', false,
        'unknownAction', 'MANUAL'
    )
WHERE retry_policy_json = '{}'::jsonb;

ALTER TABLE effect.effect
    ALTER COLUMN retry_policy_json SET DEFAULT
        '{"idempotent":false,"idempotencyKey":null,"remotelyQueryable":false,"retryable":false,"unknownAction":"MANUAL"}'::jsonb;

-- The policy document is deliberately closed: adding a policy field requires
-- an explicit migration and matching domain change.
-- name: migration.V017.constrain_effect_retry_policy
ALTER TABLE effect.effect
    ADD CONSTRAINT ck_effect_retry_policy_structure
    CHECK (
        jsonb_typeof(retry_policy_json) = 'object'
        AND retry_policy_json ?& ARRAY[
            'idempotent',
            'idempotencyKey',
            'remotelyQueryable',
            'retryable',
            'unknownAction'
        ]::text[]
        AND retry_policy_json - ARRAY[
            'idempotent',
            'idempotencyKey',
            'remotelyQueryable',
            'retryable',
            'unknownAction'
        ]::text[] = '{}'::jsonb
        AND jsonb_typeof(retry_policy_json -> 'idempotent') = 'boolean'
        AND jsonb_typeof(retry_policy_json -> 'remotelyQueryable') = 'boolean'
        AND jsonb_typeof(retry_policy_json -> 'retryable') = 'boolean'
        AND jsonb_typeof(retry_policy_json -> 'idempotencyKey') IN ('string', 'null')
        AND (
            retry_policy_json -> 'idempotencyKey' = 'null'::jsonb
            OR btrim(retry_policy_json ->> 'idempotencyKey') <> ''
        )
        AND jsonb_typeof(retry_policy_json -> 'unknownAction') = 'string'
        AND retry_policy_json ->> 'unknownAction' IN (
            'QUERY_REMOTE', 'RETRY_IDEMPOTENT', 'MANUAL'
        )
        AND retry_policy_json -> 'idempotent' = to_jsonb(idempotent)
        AND retry_policy_json -> 'remotelyQueryable'
            = to_jsonb(remote_status_queryable)
        AND (retry_policy_json ->> 'idempotencyKey')
            IS NOT DISTINCT FROM idempotency_key
        AND idempotent = (idempotency_key IS NOT NULL)
        AND (
            retry_policy_json -> 'retryable' = 'false'::jsonb
            OR idempotent
        )
        AND (
            retry_policy_json ->> 'unknownAction' <> 'QUERY_REMOTE'
            OR remote_status_queryable
        )
        AND (
            retry_policy_json ->> 'unknownAction' <> 'RETRY_IDEMPOTENT'
            OR (
                idempotent
                AND retry_policy_json -> 'retryable' = 'true'::jsonb
            )
        )
    );

-- Ordinary LOGIN roles intentionally have no table-wide UPDATE grant on the
-- effect journal. This function exposes only a manual UNKNOWN compare-and-set
-- for the authenticated owner with the effect_request capability.
-- name: effect.resolve_unknown_as
CREATE FUNCTION effect.resolve_unknown_as(
    p_database_role name,
    p_claim text,
    p_effect_id uuid,
    p_target_status text,
    p_result_json jsonb,
    p_result_object_hash bytea,
    p_failure_message text,
    p_updated_at timestamptz
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, effect
AS $function$
DECLARE
    actor uuid;
    changed_count integer;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required'
            USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM auth.effective_capabilities_as(p_database_role, p_claim, actor)
        WHERE capability_key = 'effect_request'
    ) THEN
        RAISE EXCEPTION 'effect_request capability is required'
            USING ERRCODE = '42501';
    END IF;
    IF p_updated_at IS NULL THEN
        RAISE EXCEPTION 'effect resolution time is required' USING ERRCODE = '22004';
    END IF;
    IF p_target_status = 'COMPLETED' THEN
        IF num_nonnulls(p_result_json, p_result_object_hash) <> 1
           OR p_failure_message IS NOT NULL THEN
            RAISE EXCEPTION 'completed manual resolution requires exactly one result'
                USING ERRCODE = '22023';
        END IF;
    ELSIF p_target_status = 'FAILED' THEN
        IF p_result_json IS NOT NULL OR p_result_object_hash IS NOT NULL
           OR p_failure_message IS NULL OR btrim(p_failure_message) = '' THEN
            RAISE EXCEPTION 'failed manual resolution requires only a failure message'
                USING ERRCODE = '22023';
        END IF;
    ELSE
        RAISE EXCEPTION 'manual resolution target must be COMPLETED or FAILED'
            USING ERRCODE = '22023';
    END IF;

    UPDATE effect.effect AS target
    SET status = p_target_status,
        result_json = CASE WHEN p_target_status = 'COMPLETED' THEN p_result_json END,
        result_object_hash = CASE
            WHEN p_target_status = 'COMPLETED' THEN p_result_object_hash
        END,
        completed_at = CASE WHEN p_target_status = 'COMPLETED' THEN p_updated_at END,
        failure_code = CASE WHEN p_target_status = 'FAILED' THEN 'MANUAL_FAILURE' END,
        failure_message = CASE
            WHEN p_target_status = 'FAILED' THEN p_failure_message
        END,
        updated_at = p_updated_at
    WHERE target.effect_id = p_effect_id
      AND target.owner_id = actor
      AND target.status = 'UNKNOWN'
      AND target.retry_policy_json ->> 'unknownAction' = 'MANUAL'
      AND target.updated_at <= p_updated_at;
    GET DIAGNOSTICS changed_count = ROW_COUNT;
    RETURN changed_count = 1;
END
$function$;

-- name: effect.resolve_unknown
CREATE FUNCTION effect.resolve_unknown(
    p_effect_id uuid,
    p_target_status text,
    p_result_json jsonb,
    p_result_object_hash bytea,
    p_failure_message text,
    p_updated_at timestamptz
)
RETURNS boolean
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, effect
AS $function$
    SELECT effect.resolve_unknown_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_effect_id,
        p_target_status,
        p_result_json,
        p_result_object_hash,
        p_failure_message,
        p_updated_at
    )
$function$;

REVOKE ALL ON FUNCTION effect.resolve_unknown_as(
    name, text, uuid, text, jsonb, bytea, text, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION effect.resolve_unknown(
    uuid, text, jsonb, bytea, text, timestamptz
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION effect.resolve_unknown(
    uuid, text, jsonb, bytea, text, timestamptz
) TO PUBLIC;

COMMENT ON COLUMN effect.effect.retry_policy_json IS
    'Closed effect policy document mirrored by idempotency and remote-query columns';
COMMENT ON FUNCTION effect.resolve_unknown(
    uuid, text, jsonb, bytea, text, timestamptz
) IS 'Identity-bound manual UNKNOWN compare-and-set; no general effect UPDATE is granted';

RESET ROLE;
