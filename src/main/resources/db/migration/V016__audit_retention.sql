SET ROLE cilexec_owner;

-- Audit details are deliberately a flat diagnostic object. Keeping values as
-- strings makes the SQL representation identical to AuditEvent.details.
-- name: migration.V016.constrain_audit_details
ALTER TABLE audit.event
    ADD CONSTRAINT ck_audit_event_details_string_object
    CHECK (
        jsonb_typeof(details_json) = 'object'
        AND NOT jsonb_path_exists(
            details_json,
            '$.* ? (@.type() != "string")'
        )
    );

-- Java persists fixed, whole-second durations. Excluding calendar months and
-- fractional seconds prevents an interval from changing meaning when mapped.
ALTER TABLE audit.retention_policy
    ADD CONSTRAINT ck_audit_retention_fixed_whole_seconds
    CHECK (
        extract(YEAR FROM retain_for) = 0
        AND extract(MONTH FROM retain_for) = 0
        AND extract(EPOCH FROM retain_for)
            = trunc(extract(EPOCH FROM retain_for))
    );

-- Updates remain impossible. Deletes are available only through the bounded
-- SECURITY DEFINER function below; application roles never receive table DELETE.
-- name: migration.V016.audit_event_update_immutability
DROP TRIGGER audit_event_reject_update_delete ON audit.event;
CREATE TRIGGER audit_event_reject_update
BEFORE UPDATE ON audit.event
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- event_type is an exact match for audit.event.action. A disabled policy keeps
-- its events indefinitely without requiring destructive policy-row deletion.
-- name: audit.purge_expired_events
CREATE FUNCTION audit.purge_expired_events(p_limit integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, audit
AS $function$
DECLARE
    deleted_count integer;
    purge_at timestamptz;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'audit purge limit must be between 1 and 10000'
            USING ERRCODE = '22023';
    END IF;
    -- The caller cannot forge a future clock value to bypass retention.
    purge_at := clock_timestamp();

    WITH expired AS MATERIALIZED (
        SELECT candidate.event_id
        FROM audit.event AS candidate
        JOIN audit.retention_policy AS policy
          ON policy.event_type = candidate.action
        WHERE policy.enabled
          AND candidate.created_at < purge_at - policy.retain_for
        ORDER BY candidate.created_at, candidate.event_id
        LIMIT p_limit
        FOR UPDATE OF candidate SKIP LOCKED
    )
    DELETE FROM audit.event AS target
    USING expired
    WHERE target.event_id = expired.event_id;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END
$function$;

REVOKE UPDATE, DELETE ON audit.event
    FROM PUBLIC, cilexec_runtime, cilexec_effect_worker, cilexec_readonly;
REVOKE DELETE ON audit.retention_policy
    FROM PUBLIC, cilexec_runtime, cilexec_effect_worker, cilexec_readonly;
GRANT SELECT, INSERT, UPDATE ON audit.retention_policy TO cilexec_runtime;

REVOKE ALL ON FUNCTION audit.purge_expired_events(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION audit.purge_expired_events(integer)
    TO cilexec_runtime;

UPDATE meta.table_security_classification
SET classification = 'SYSTEM_RUNTIME',
    rationale = 'runtime-managed audit retention configuration'
WHERE schema_name = 'audit'::name
  AND table_name = 'retention_policy'::name;

COMMENT ON TABLE audit.retention_policy IS
    'Runtime-managed retention by exact audit.event.action; disable a row to retain indefinitely';
COMMENT ON FUNCTION audit.purge_expired_events(integer) IS
    'Deletes at most p_limit expired audit events under enabled exact-action policies';

RESET ROLE;
