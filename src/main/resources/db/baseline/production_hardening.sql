-- CilExec 1.0 production storage, retention, and garbage-collection policy.
SET ROLE cilexec_owner;

CREATE TABLE auth.login_throttle (
    principal_key text PRIMARY KEY CHECK (btrim(principal_key) <> '' AND char_length(principal_key) <= 128),
    failure_count integer NOT NULL CHECK (failure_count BETWEEN 1 AND 32),
    last_failed_at timestamptz NOT NULL,
    blocked_until timestamptz NOT NULL CHECK (blocked_until >= last_failed_at)
);
INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('auth', 'login_throttle', 'SYSTEM_RUNTIME', NULL,
        'durable terminal authentication backoff without credential material');
GRANT SELECT, INSERT, UPDATE, DELETE ON auth.login_throttle TO cilexec_runtime;

CREATE FUNCTION object_store.enforce_owner_byte_quota()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, object_store
AS $function$
BEGIN
    IF NEW.created_by IS NULL OR EXISTS (
        SELECT 1 FROM object_store.object WHERE object_hash = NEW.object_hash
    ) THEN
        RETURN NEW;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.created_by::text, 703));
    IF COALESCE((SELECT sum(byte_size) FROM object_store.object
                 WHERE created_by = NEW.created_by), 0) + NEW.byte_size > 4294967296 THEN
        RAISE EXCEPTION 'per-user object storage quota of 4 GiB has been reached'
            USING ERRCODE = '54000';
    END IF;
    RETURN NEW;
END
$function$;
REVOKE ALL ON FUNCTION object_store.enforce_owner_byte_quota() FROM PUBLIC;
CREATE TRIGGER object_owner_byte_quota
BEFORE INSERT ON object_store.object
FOR EACH ROW EXECUTE FUNCTION object_store.enforce_owner_byte_quota();

CREATE FUNCTION vfs.enforce_owner_node_quota()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, vfs
AS $function$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.owner_id::text, 704));
    IF (SELECT count(*) FROM vfs.node WHERE owner_id = NEW.owner_id) >= 100000 THEN
        RAISE EXCEPTION 'per-user VFS node quota of 100000 has been reached'
            USING ERRCODE = '54000';
    END IF;
    RETURN NEW;
END
$function$;
REVOKE ALL ON FUNCTION vfs.enforce_owner_node_quota() FROM PUBLIC;
CREATE TRIGGER vfs_owner_node_quota
BEFORE INSERT ON vfs.node
FOR EACH ROW EXECUTE FUNCTION vfs.enforce_owner_node_quota();

CREATE FUNCTION ipc.enforce_owner_message_quota()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, ipc
AS $function$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended(NEW.owner_id::text, 705));
    IF (SELECT count(*) FROM ipc.message WHERE owner_id = NEW.owner_id) >= 100000 THEN
        RAISE EXCEPTION 'per-user IPC message quota of 100000 has been reached'
            USING ERRCODE = '54000';
    END IF;
    RETURN NEW;
END
$function$;
REVOKE ALL ON FUNCTION ipc.enforce_owner_message_quota() FROM PUBLIC;
CREATE TRIGGER ipc_owner_message_quota
BEFORE INSERT ON ipc.message
FOR EACH ROW EXECUTE FUNCTION ipc.enforce_owner_message_quota();
CREATE INDEX ix_message_owner_created
    ON ipc.message(owner_id, created_at, message_id);

-- Delete only content that has had no durable reference for at least one hour. Foreign keys
-- remain the final safety boundary; chunk chains become collectible only when no root reaches them.
CREATE FUNCTION object_store.gc_orphans(p_limit integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, object_store
AS $function$
DECLARE
    deleted_manifests integer;
    deleted_objects integer;
BEGIN
    IF p_limit IS NULL OR p_limit < 1 OR p_limit > 10000 THEN
        RAISE EXCEPTION 'GC limit must be between 1 and 10000' USING ERRCODE = '22023';
    END IF;
    PERFORM set_config('app.cilexec_gc', 'on', true);

    -- Freeze object creation and every durable root while reachability is computed.
    -- Acquiring object_store.object first follows the normal object -> manifest -> root
    -- write order and avoids waiting writers deadlocking with GC.
    LOCK TABLE object_store.object, object_store.chunk_manifest,
        program.program, process.variable, ipc.message, vfs.node,
        vfs.file_revision, package.release, effect.effect IN SHARE ROW EXCLUSIVE MODE;

    WITH RECURSIVE reachable(object_hash) AS (
        SELECT source_object_hash FROM program.program
        UNION SELECT compiled_object_hash FROM program.program WHERE compiled_object_hash IS NOT NULL
        UNION SELECT value_object_hash FROM process.variable WHERE value_object_hash IS NOT NULL
        UNION SELECT payload_object_hash FROM ipc.message WHERE payload_object_hash IS NOT NULL
        UNION SELECT current_object_hash FROM vfs.node WHERE current_object_hash IS NOT NULL
        UNION SELECT object_hash FROM vfs.file_revision
        UNION SELECT database_object_hash FROM package.release
        UNION SELECT request_object_hash FROM effect.effect WHERE request_object_hash IS NOT NULL
        UNION SELECT result_object_hash FROM effect.effect WHERE result_object_hash IS NOT NULL
        UNION
        SELECT child.object_hash
        FROM reachable AS root
        JOIN object_store.chunk_manifest AS manifest ON manifest.manifest_hash = root.object_hash
        CROSS JOIN LATERAL (VALUES
            (manifest.previous_manifest_hash),
            (manifest.base_object_hash),
            (manifest.tail_object_hash)
        ) AS child(object_hash)
        WHERE child.object_hash IS NOT NULL
    ), candidates AS (
        SELECT manifest.manifest_hash
        FROM object_store.chunk_manifest AS manifest
        JOIN object_store.object AS stored ON stored.object_hash = manifest.manifest_hash
        WHERE NOT EXISTS (SELECT 1 FROM reachable
                          WHERE reachable.object_hash = manifest.manifest_hash)
          AND NOT EXISTS (SELECT 1 FROM object_store.chunk_manifest AS child
                          WHERE child.previous_manifest_hash = manifest.manifest_hash)
          AND stored.created_at < clock_timestamp() - interval '1 hour'
        ORDER BY stored.created_at, manifest.manifest_hash
        LIMIT p_limit
        FOR UPDATE OF manifest SKIP LOCKED
    )
    DELETE FROM object_store.chunk_manifest AS manifest
    USING candidates
    WHERE manifest.manifest_hash = candidates.manifest_hash;
    GET DIAGNOSTICS deleted_manifests = ROW_COUNT;

    WITH candidates AS (
        SELECT stored.object_hash
        FROM object_store.object AS stored
        WHERE stored.created_at < clock_timestamp() - interval '1 hour'
          AND NOT EXISTS (SELECT 1 FROM program.program WHERE source_object_hash = stored.object_hash
                          OR compiled_object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM process.variable WHERE value_object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM ipc.message WHERE payload_object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM vfs.node WHERE current_object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM vfs.file_revision WHERE object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM package.release WHERE database_object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM effect.effect WHERE request_object_hash = stored.object_hash
                          OR result_object_hash = stored.object_hash)
          AND NOT EXISTS (SELECT 1 FROM object_store.chunk_manifest
                          WHERE manifest_hash = stored.object_hash
                             OR previous_manifest_hash = stored.object_hash
                             OR base_object_hash = stored.object_hash
                             OR tail_object_hash = stored.object_hash)
        ORDER BY stored.created_at, stored.object_hash
        LIMIT GREATEST(0, p_limit - deleted_manifests)
        FOR UPDATE OF stored SKIP LOCKED
    )
    DELETE FROM object_store.object AS stored
    USING candidates
    WHERE stored.object_hash = candidates.object_hash;
    GET DIAGNOSTICS deleted_objects = ROW_COUNT;
    RETURN deleted_manifests + deleted_objects;
END
$function$;
REVOKE ALL ON FUNCTION object_store.gc_orphans(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION object_store.gc_orphans(integer) TO cilexec_runtime;

CREATE FUNCTION object_store.admin_gc_orphans_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_limit integer
)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store
AS $function$
BEGIN
    PERFORM auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id
    );
    RETURN object_store.gc_orphans(p_limit);
END
$function$;
REVOKE ALL ON FUNCTION object_store.admin_gc_orphans_as(name, text, uuid, integer) FROM PUBLIC;

CREATE FUNCTION object_store.admin_gc_orphans(p_administrator_id uuid, p_limit integer)
RETURNS integer
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, object_store
AS $function$
    SELECT object_store.admin_gc_orphans_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_administrator_id,
        p_limit
    )
$function$;
REVOKE ALL ON FUNCTION object_store.admin_gc_orphans(uuid, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION object_store.admin_gc_orphans(uuid, integer) TO PUBLIC;
GRANT EXECUTE ON FUNCTION object_store.admin_gc_orphans_as(
    name, text, uuid, integer
) TO PUBLIC;

INSERT INTO meta.security_definer_public_allowlist(function_signature, rationale)
VALUES (
    'object_store.admin_gc_orphans_as(name,text,uuid,integer)'::regprocedure::text,
    'verified system administrator object garbage collection'
);

INSERT INTO audit.retention_policy(event_type, retain_for, enabled)
VALUES ('*', interval '90 days', true);

CREATE OR REPLACE FUNCTION audit.purge_expired_events(p_limit integer)
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
    purge_at := clock_timestamp();
    WITH expired AS MATERIALIZED (
        SELECT candidate.event_id
        FROM audit.event AS candidate
        JOIN LATERAL (
            SELECT policy.retain_for, policy.enabled
            FROM audit.retention_policy AS policy
            WHERE policy.event_type IN (candidate.action, '*')
            ORDER BY (policy.event_type = candidate.action) DESC
            LIMIT 1
        ) AS selected_policy ON selected_policy.enabled
        WHERE candidate.created_at < purge_at - selected_policy.retain_for
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

COMMENT ON FUNCTION object_store.gc_orphans(integer) IS
    'Deletes bounded, one-hour-old content that is unreachable from every durable root';
COMMENT ON TABLE audit.retention_policy IS
    'Exact-action retention with a seeded 90-day wildcard fallback';

SELECT meta.assert_security_invariants();
RESET ROLE;
