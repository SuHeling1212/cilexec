SET ROLE cilexec_owner;

-- Every application table must be registered. USER_SCOPED tables are required
-- to have both ENABLE and FORCE RLS; all exceptions are explicit and reviewable.
-- name: migration.V015.create_security_catalog
CREATE TABLE meta.table_security_classification (
    schema_name name NOT NULL,
    table_name name NOT NULL,
    classification text NOT NULL CHECK (classification IN (
        'USER_SCOPED', 'SYSTEM_RUNTIME', 'SYSTEM_READONLY', 'SHARED_IMMUTABLE'
    )),
    owner_column name,
    rationale text NOT NULL CHECK (btrim(rationale) <> ''),
    registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (schema_name, table_name),
    CHECK ((classification = 'USER_SCOPED' AND owner_column IS NOT NULL)
        OR (classification <> 'USER_SCOPED' AND owner_column IS NULL))
);

-- name: migration.V015.register_security_classes
INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES
    ('meta', 'instance', 'SYSTEM_RUNTIME', NULL, 'single database instance identity'),
    ('meta', 'kernel_instance', 'SYSTEM_RUNTIME', NULL, 'runtime incarnation lifecycle'),
    ('meta', 'boot', 'SYSTEM_RUNTIME', NULL, 'global boot and recovery lifecycle'),
    ('meta', 'table_security_classification', 'SYSTEM_READONLY', NULL, 'security invariant registry'),

    ('auth', 'user_account', 'USER_SCOPED', 'user_id', 'stable user to PostgreSQL LOGIN mapping'),
    ('auth', 'group_account', 'USER_SCOPED', 'owner_id', 'user-owned group'),
    ('auth', 'group_member', 'USER_SCOPED', 'owner_id', 'membership under group owner'),
    ('auth', 'capability', 'SYSTEM_READONLY', NULL, 'global capability dictionary'),
    ('auth', 'user_capability', 'USER_SCOPED', 'owner_id', 'user capability assignment'),
    ('auth', 'group_capability', 'USER_SCOPED', 'owner_id', 'group capability assignment'),

    ('object_store', 'object', 'SHARED_IMMUTABLE', NULL, 'content addressed bytes shared by authorized references'),

    ('program', 'program', 'USER_SCOPED', 'owner_id', 'owner-scoped immutable program'),
    ('program', 'statement', 'USER_SCOPED', 'owner_id', 'owner-scoped immutable statement'),
    ('program', 'module_binding', 'USER_SCOPED', 'owner_id', 'owner-scoped immutable module binding'),

    ('process', 'process', 'USER_SCOPED', 'owner_id', 'authoritative user process'),
    ('process', 'call_frame', 'USER_SCOPED', 'owner_id', 'process continuation call frame'),
    ('process', 'scope', 'USER_SCOPED', 'owner_id', 'process continuation scope'),
    ('process', 'variable', 'USER_SCOPED', 'owner_id', 'current process variable'),
    ('process', 'exception_frame', 'USER_SCOPED', 'owner_id', 'process exception continuation'),
    ('process', 'wait_state', 'USER_SCOPED', 'owner_id', 'durable process wait reason'),
    ('process', 'relationship', 'USER_SCOPED', 'owner_id', 'durable process relation'),
    ('process', 'event', 'USER_SCOPED', 'owner_id', 'append-only process event'),
    ('process', 'timer', 'USER_SCOPED', 'owner_id', 'database-authoritative timer'),
    ('process', 'package_binding', 'USER_SCOPED', 'owner_id', 'exact process package hash pin'),

    ('scheduler', 'runner', 'SYSTEM_RUNTIME', NULL, 'internal worker identity'),
    ('scheduler', 'queue', 'USER_SCOPED', 'owner_id', 'user process FIFO queue row'),
    ('scheduler', 'lease', 'SYSTEM_RUNTIME', NULL, 'internal execution fencing lease'),

    ('ipc', 'channel', 'USER_SCOPED', 'owner_id', 'user-owned named channel'),
    ('ipc', 'topic', 'USER_SCOPED', 'owner_id', 'user-owned topic'),
    ('ipc', 'subscription', 'USER_SCOPED', 'owner_id', 'user-owned subscription'),
    ('ipc', 'message', 'USER_SCOPED', 'owner_id', 'durable user message'),
    ('ipc', 'delivery', 'USER_SCOPED', 'owner_id', 'durable exact consumption unit'),

    ('vfs', 'node', 'USER_SCOPED', 'owner_id', 'user-owned VFS namespace'),
    ('vfs', 'file_revision', 'USER_SCOPED', 'owner_id', 'owner-scoped optional history'),
    ('vfs', 'mount', 'USER_SCOPED', 'owner_id', 'capability-gated declared host mount'),

    ('package', 'release', 'SHARED_IMMUTABLE', NULL, 'global immutable SQLite release'),
    ('package', 'release_dependency', 'SHARED_IMMUTABLE', NULL, 'derived immutable release index'),
    ('package', 'release_module', 'SHARED_IMMUTABLE', NULL, 'derived immutable release index'),
    ('package', 'release_entrypoint', 'SHARED_IMMUTABLE', NULL, 'derived immutable release index'),
    ('package', 'release_export', 'SHARED_IMMUTABLE', NULL, 'derived immutable release index'),
    ('package', 'release_capability', 'SHARED_IMMUTABLE', NULL, 'derived immutable release index'),
    ('package', 'signature', 'SYSTEM_RUNTIME', NULL, 'signature trust and revocation state for immutable bytes'),
    ('package', 'environment', 'USER_SCOPED', 'owner_id', 'user-owned package environment'),
    ('package', 'binding', 'USER_SCOPED', 'owner_id', 'environment binding to an exact hash'),
    ('package', 'data_scope', 'USER_SCOPED', 'owner_id', 'mutable package data outside SQLite'),

    ('effect', 'effect', 'USER_SCOPED', 'owner_id', 'owner-scoped external effect request'),
    ('effect', 'attempt', 'USER_SCOPED', 'owner_id', 'owner-scoped external effect attempt'),

    ('terminal', 'session', 'USER_SCOPED', 'owner_id', 'owner-scoped terminal session'),
    ('terminal', 'input', 'USER_SCOPED', 'owner_id', 'fully submitted terminal input'),
    ('terminal', 'attachment', 'USER_SCOPED', 'owner_id', 'terminal to process attachment'),

    ('audit', 'event', 'USER_SCOPED', 'owner_id', 'owner-visible append-only audit event'),
    ('audit', 'retention_policy', 'SYSTEM_READONLY', NULL, 'administrator-defined audit retention');

-- name: object_store.put_object_as
CREATE FUNCTION object_store.put_object_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea,
    p_media_type text,
    p_content bytea
)
RETURNS bytea
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, object_store
AS $function$
DECLARE
    actor uuid;
    actual_hash bytea;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    actual_hash := pg_catalog.sha256(p_content);
    IF p_object_hash IS DISTINCT FROM actual_hash THEN
        RAISE EXCEPTION 'object hash does not match content' USING ERRCODE = '22000';
    END IF;

    INSERT INTO object_store.object(object_hash, byte_size, media_type, content, created_by)
    VALUES (p_object_hash, octet_length(p_content), p_media_type, p_content, actor)
    ON CONFLICT (object_hash) DO NOTHING;

    IF NOT EXISTS (
        SELECT 1 FROM object_store.object
        WHERE object_hash = p_object_hash
          AND byte_size = octet_length(p_content)
          AND content = p_content
    ) THEN
        RAISE EXCEPTION 'existing object does not match supplied bytes' USING ERRCODE = '23505';
    END IF;
    RETURN p_object_hash;
END
$function$;

-- name: object_store.put_object
CREATE FUNCTION object_store.put_object(p_object_hash bytea, p_media_type text, p_content bytea)
RETURNS bytea
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, object_store
AS $function$
    SELECT object_store.put_object_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_object_hash,
        p_media_type,
        p_content
    )
$function$;

-- name: object_store.read_object_as
CREATE FUNCTION object_store.read_object_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea
)
RETURNS TABLE (
    object_hash bytea,
    byte_size bigint,
    media_type text,
    content bytea,
    created_at timestamptz
)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs, program, package, process
AS $function$
    SELECT stored.object_hash, stored.byte_size, stored.media_type, stored.content, stored.created_at
    FROM object_store.object AS stored
    WHERE stored.object_hash = p_object_hash
      AND (
          (p_database_role::text IN ('cilexec_runtime', 'cilexec_owner', 'cilexec_migrator')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END)
          OR EXISTS (
              SELECT 1
              FROM auth.user_account AS account
              WHERE account.user_id = stored.created_by
                AND account.user_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          )
          OR EXISTS (
              SELECT 1 FROM vfs.node AS node
              WHERE node.current_object_hash = stored.object_hash
                AND node.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          )
          OR EXISTS (
              SELECT 1 FROM vfs.file_revision AS revision
              WHERE revision.object_hash = stored.object_hash
                AND revision.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          )
          OR EXISTS (
              SELECT 1 FROM program.program AS source_program
              WHERE (source_program.source_object_hash = stored.object_hash
                     OR source_program.compiled_object_hash = stored.object_hash)
                AND source_program.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          )
          OR EXISTS (
              SELECT 1
              FROM package.release AS release
              JOIN package.binding AS binding ON binding.package_hash = release.package_hash
              WHERE release.database_object_hash = stored.object_hash
                AND binding.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          )
          OR EXISTS (
              SELECT 1
              FROM package.release AS release
              JOIN process.package_binding AS binding ON binding.package_hash = release.package_hash
              WHERE release.database_object_hash = stored.object_hash
                AND binding.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
          )
      )
$function$;

-- name: object_store.read_object
CREATE FUNCTION object_store.read_object(p_object_hash bytea)
RETURNS TABLE (
    object_hash bytea,
    byte_size bigint,
    media_type text,
    content bytea,
    created_at timestamptz
)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth, object_store
AS $function$
    SELECT * FROM object_store.read_object_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_object_hash
    )
$function$;

-- name: vfs.read_file_content_as
CREATE FUNCTION vfs.read_file_content_as(
    p_database_role name,
    p_claim text,
    p_node_id uuid
)
RETURNS bytea
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, vfs, object_store
AS $function$
    SELECT stored.content
    FROM vfs.node AS node
    JOIN object_store.object AS stored ON stored.object_hash = node.current_object_hash
    WHERE node.node_id = p_node_id
      AND node.owner_id = auth.resolve_cilexec_user_id(p_database_role, p_claim)
      AND node.node_type = 'FILE'
$function$;

-- name: vfs.read_file_content
CREATE FUNCTION vfs.read_file_content(p_node_id uuid)
RETURNS bytea
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT vfs.read_file_content_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_node_id
    )
$function$;

REVOKE ALL ON FUNCTION object_store.put_object_as(name, text, bytea, text, bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION object_store.read_object_as(name, text, bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION object_store.read_object(bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.read_file_content_as(name, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION object_store.put_object(bytea, text, bytea) FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.read_file_content(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION object_store.put_object(bytea, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION object_store.read_object(bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION vfs.read_file_content(uuid) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION object_store.put_object_as(name, text, bytea, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION object_store.read_object_as(name, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION vfs.read_file_content_as(name, text, uuid) TO cilexec_runtime;

-- name: scheduler.heartbeat_process_as
CREATE FUNCTION scheduler.heartbeat_process_as(
    p_database_role name,
    p_claim text,
    p_process_uid uuid,
    p_owner_id uuid,
    p_runner_id uuid,
    p_boot_id uuid,
    p_execution_epoch bigint,
    p_heartbeat_at timestamptz,
    p_expires_at timestamptz
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, scheduler
AS $function$
DECLARE
    actor uuid;
    observed_at timestamptz;
BEGIN
    observed_at := clock_timestamp();
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF NOT (
           p_database_role::text IN ('cilexec_runtime', 'cilexec_owner')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END
       ) AND actor IS DISTINCT FROM p_owner_id THEN
        RAISE EXCEPTION 'process lease heartbeat is not authorized' USING ERRCODE = '42501';
    END IF;
    IF p_expires_at <= p_heartbeat_at THEN
        RAISE EXCEPTION 'lease expiry must follow heartbeat' USING ERRCODE = '22000';
    END IF;

    UPDATE scheduler.lease
    SET heartbeat_at = GREATEST(p_heartbeat_at, observed_at),
        expires_at = p_expires_at
    WHERE process_uid = p_process_uid
      AND owner_id = p_owner_id
      AND runner_id = p_runner_id
      AND boot_id = p_boot_id
      AND execution_epoch = p_execution_epoch
      AND p_heartbeat_at >= heartbeat_at
      AND expires_at > observed_at
      AND p_expires_at > observed_at;
    RETURN FOUND;
END
$function$;

-- name: scheduler.heartbeat_process
CREATE FUNCTION scheduler.heartbeat_process(
    p_process_uid uuid,
    p_owner_id uuid,
    p_runner_id uuid,
    p_boot_id uuid,
    p_execution_epoch bigint,
    p_heartbeat_at timestamptz,
    p_expires_at timestamptz
)
RETURNS boolean
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, scheduler
AS $function$
    SELECT scheduler.heartbeat_process_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_process_uid,
        p_owner_id,
        p_runner_id,
        p_boot_id,
        p_execution_epoch,
        p_heartbeat_at,
        p_expires_at
    )
$function$;
REVOKE ALL ON FUNCTION scheduler.heartbeat_process_as(
    name, text, uuid, uuid, uuid, uuid, bigint, timestamptz, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION scheduler.heartbeat_process(
    uuid, uuid, uuid, uuid, bigint, timestamptz, timestamptz
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler.heartbeat_process_as(
    name, text, uuid, uuid, uuid, uuid, bigint, timestamptz, timestamptz
) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION scheduler.heartbeat_process(
    uuid, uuid, uuid, uuid, bigint, timestamptz, timestamptz
) TO cilexec_runtime;

-- name: scheduler.release_process_as
CREATE FUNCTION scheduler.release_process_as(
    p_database_role name,
    p_claim text,
    p_process_uid uuid,
    p_execution_epoch bigint
)
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, process, scheduler
AS $function$
DECLARE
    actor uuid;
    process_owner uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    SELECT owner_id INTO process_owner
    FROM process.process
    WHERE process_uid = p_process_uid;
    IF process_owner IS NULL THEN
        RETURN;
    END IF;
    IF NOT (
           p_database_role::text IN ('cilexec_runtime', 'cilexec_owner')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END
       ) AND actor IS DISTINCT FROM process_owner THEN
        RAISE EXCEPTION 'process lease release is not authorized' USING ERRCODE = '42501';
    END IF;

    DELETE FROM scheduler.lease
    WHERE process_uid = p_process_uid AND execution_epoch = p_execution_epoch;
    UPDATE scheduler.queue
    SET queue_state = CASE WHEN EXISTS (
            SELECT 1 FROM process.process AS current_process
            WHERE current_process.process_uid = p_process_uid
              AND current_process.status = 'READY'
        ) THEN 'READY' ELSE 'REMOVED' END,
        claimed_at = NULL,
        claimed_by = NULL,
        enqueued_at = clock_timestamp()
    WHERE process_uid = p_process_uid;
END
$function$;

-- name: scheduler.release_process
CREATE FUNCTION scheduler.release_process(p_process_uid uuid, p_execution_epoch bigint)
RETURNS void
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, scheduler
AS $function$
    SELECT scheduler.release_process_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_process_uid,
        p_execution_epoch
    )
$function$;
REVOKE ALL ON FUNCTION scheduler.release_process_as(name, text, uuid, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION scheduler.release_process(uuid, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION scheduler.release_process_as(name, text, uuid, bigint) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION scheduler.release_process(uuid, bigint) TO cilexec_runtime;

-- Global package release tables intentionally have no user RLS. Registration
-- therefore goes through one owner-executed function that binds the importer,
-- forbids caller-asserted trust, and publishes every derived index atomically.
-- name: package.register_release_bundle_as
CREATE FUNCTION package.register_release_bundle_as(
    p_database_role name,
    p_claim text,
    p_package_hash bytea,
    p_namespace text,
    p_package_name text,
    p_package_version text,
    p_database_object_hash bytea,
    p_database_file_hash bytea,
    p_package_format_version integer,
    p_signature_status text,
    p_modules jsonb,
    p_dependencies jsonb,
    p_entrypoints jsonb,
    p_exports jsonb,
    p_capabilities jsonb,
    p_created_at timestamptz
)
RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, package
AS $function$
DECLARE
    actor uuid;
    affected_rows integer;
    item jsonb;
    text_value text;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF p_signature_status IS DISTINCT FROM 'UNSIGNED' THEN
        RAISE EXCEPTION 'package trust can only be assigned by a signature verifier'
            USING ERRCODE = '42501';
    END IF;
    IF octet_length(p_package_hash) <> 32
       OR octet_length(p_database_object_hash) <> 32
       OR p_database_file_hash IS DISTINCT FROM p_database_object_hash
       OR p_package_format_version < 1 THEN
        RAISE EXCEPTION 'invalid package hash or format metadata' USING ERRCODE = '22000';
    END IF;
    IF p_created_at IS NULL OR p_created_at > clock_timestamp() + interval '5 minutes' THEN
        RAISE EXCEPTION 'invalid package creation time' USING ERRCODE = '22000';
    END IF;
    IF jsonb_typeof(p_modules) <> 'array'
       OR jsonb_typeof(p_dependencies) <> 'array'
       OR jsonb_typeof(p_entrypoints) <> 'array'
       OR jsonb_typeof(p_exports) <> 'array'
       OR jsonb_typeof(p_capabilities) <> 'array' THEN
        RAISE EXCEPTION 'package derived indexes must be JSON arrays' USING ERRCODE = '22000';
    END IF;

    INSERT INTO package.release(
        package_hash, namespace, package_name, package_version,
        database_object_hash, database_file_hash, package_format_version,
        metadata_json, imported_by, created_at
    ) VALUES (
        p_package_hash, p_namespace, p_package_name, p_package_version,
        p_database_object_hash, p_database_file_hash, p_package_format_version,
        jsonb_build_object(
            'modules', p_modules,
            'dependencies', p_dependencies,
            'entrypoints', p_entrypoints,
            'exports', p_exports,
            'capabilities', p_capabilities
        ), actor, p_created_at
    ) ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS affected_rows = ROW_COUNT;
    IF affected_rows = 0 THEN
        RETURN false;
    END IF;

    INSERT INTO package.signature(package_hash, signature_status)
    VALUES (p_package_hash, 'UNSIGNED');

    FOR item IN SELECT value FROM jsonb_array_elements(p_modules)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR NULLIF(btrim(item->>'moduleName'), '') IS NULL
           OR NULLIF(btrim(item->>'moduleObjectPath'), '') IS NULL
           OR COALESCE(item->>'moduleHash', '') !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'invalid package module index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_module(
            package_hash, module_name, module_object_path, module_hash
        ) VALUES (
            p_package_hash, item->>'moduleName', item->>'moduleObjectPath',
            decode(item->>'moduleHash', 'hex')
        );
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(p_dependencies)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR NULLIF(btrim(item->>'dependencyNamespace'), '') IS NULL
           OR NULLIF(btrim(item->>'dependencyName'), '') IS NULL
           OR NULLIF(btrim(item->>'versionConstraint'), '') IS NULL THEN
            RAISE EXCEPTION 'invalid package dependency index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_dependency(
            package_hash, dependency_namespace, dependency_name,
            version_constraint, optional
        ) VALUES (
            p_package_hash, item->>'dependencyNamespace', item->>'dependencyName',
            item->>'versionConstraint', COALESCE((item->>'optional')::boolean, false)
        );
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(p_entrypoints)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR NULLIF(btrim(item->>'entrypointName'), '') IS NULL
           OR NULLIF(btrim(item->>'moduleName'), '') IS NULL
           OR NULLIF(btrim(item->>'functionName'), '') IS NULL THEN
            RAISE EXCEPTION 'invalid package entrypoint index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_entrypoint(
            package_hash, entrypoint_name, module_name, function_name
        ) VALUES (
            p_package_hash, item->>'entrypointName', item->>'moduleName',
            item->>'functionName'
        );
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(p_exports)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR NULLIF(btrim(item->>'exportName'), '') IS NULL
           OR NULLIF(btrim(item->>'moduleName'), '') IS NULL
           OR NULLIF(btrim(item->>'symbolName'), '') IS NULL THEN
            RAISE EXCEPTION 'invalid package export index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_export(
            package_hash, export_name, module_name, symbol_name
        ) VALUES (
            p_package_hash, item->>'exportName', item->>'moduleName', item->>'symbolName'
        );
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(p_capabilities)
    LOOP
        text_value := item->>'capabilityKey';
        IF jsonb_typeof(item) <> 'object'
           OR COALESCE(text_value, '') !~ '^[a-z][a-z0-9_.:-]*$' THEN
            RAISE EXCEPTION 'invalid package capability index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_capability(
            package_hash, capability_key, required, rationale
        ) VALUES (
            p_package_hash, text_value, COALESCE((item->>'required')::boolean, true),
            COALESCE(item->>'rationale', '')
        );
    END LOOP;
    RETURN true;
END
$function$;

-- name: package.register_release_bundle
CREATE FUNCTION package.register_release_bundle(
    p_package_hash bytea,
    p_namespace text,
    p_package_name text,
    p_package_version text,
    p_database_object_hash bytea,
    p_database_file_hash bytea,
    p_package_format_version integer,
    p_signature_status text,
    p_modules jsonb,
    p_dependencies jsonb,
    p_entrypoints jsonb,
    p_exports jsonb,
    p_capabilities jsonb,
    p_created_at timestamptz
)
RETURNS boolean
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, package
AS $function$
    SELECT package.register_release_bundle_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_package_hash, p_namespace, p_package_name, p_package_version,
        p_database_object_hash, p_database_file_hash, p_package_format_version,
        p_signature_status, p_modules, p_dependencies, p_entrypoints, p_exports,
        p_capabilities, p_created_at
    )
$function$;
REVOKE ALL ON FUNCTION package.register_release_bundle_as(
    name, text, bytea, text, text, text, bytea, bytea, integer, text,
    jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz
) FROM PUBLIC;
REVOKE ALL ON FUNCTION package.register_release_bundle(
    bytea, text, text, text, bytea, bytea, integer, text,
    jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz
) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.register_release_bundle_as(
    name, text, bytea, text, text, text, bytea, bytea, integer, text,
    jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz
) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.register_release_bundle(
    bytea, text, text, text, bytea, bytea, integer, text,
    jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz
) TO cilexec_runtime;

-- name: auth.effective_capabilities_as
CREATE FUNCTION auth.effective_capabilities_as(
    p_database_role name,
    p_claim text,
    p_user_id uuid
)
RETURNS TABLE (capability_key text)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF NOT (
           p_database_role::text IN ('cilexec_runtime', 'cilexec_owner')
           AND p_database_role = CASE
               WHEN NULLIF(current_setting('role', true), 'none') IS NULL
                   THEN session_user::name
               ELSE current_setting('role', true)::name
           END
       ) AND actor IS DISTINCT FROM p_user_id THEN
        RAISE EXCEPTION 'effective capability lookup is not authorized'
            USING ERRCODE = '42501';
    END IF;

    RETURN QUERY
    SELECT capability.capability_key
    FROM auth.user_capability AS assignment
    JOIN auth.capability AS capability USING (capability_id)
    WHERE assignment.user_id = p_user_id
      AND (assignment.expires_at IS NULL OR assignment.expires_at > clock_timestamp())
    UNION
    SELECT capability.capability_key
    FROM auth.group_member AS member
    JOIN auth.group_account AS group_account
      ON group_account.group_id = member.group_id
     AND group_account.owner_id = member.owner_id
     AND group_account.status = 'ACTIVE'
    JOIN auth.group_capability AS assignment
      ON assignment.group_id = member.group_id
     AND assignment.owner_id = member.owner_id
    JOIN auth.capability AS capability USING (capability_id)
    WHERE member.member_user_id = p_user_id
      AND (assignment.expires_at IS NULL OR assignment.expires_at > clock_timestamp());
END
$function$;

-- name: auth.effective_capabilities
CREATE FUNCTION auth.effective_capabilities(p_user_id uuid)
RETURNS TABLE (capability_key text)
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, auth
AS $function$
    SELECT * FROM auth.effective_capabilities_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_user_id
    )
$function$;
REVOKE ALL ON FUNCTION auth.effective_capabilities_as(name, text, uuid) FROM PUBLIC;
REVOKE ALL ON FUNCTION auth.effective_capabilities(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.effective_capabilities_as(name, text, uuid) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION auth.effective_capabilities(uuid) TO cilexec_runtime;

-- The object owner owns this narrowly scoped grant function; only the
-- migrator can invoke it after provisioning a validated user LOGIN role.
-- name: auth.grant_login_role_access
CREATE FUNCTION auth.grant_login_role_access(p_user_id uuid)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    SELECT postgres_role_name INTO STRICT mapped_role
    FROM auth.user_account
    WHERE user_id = p_user_id AND status = 'ACTIVE';

    IF mapped_role::text <> 'cilexec_user_' || replace(p_user_id::text, '-', '')
       OR NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
        RAISE EXCEPTION 'validated LOGIN role is missing for user %', p_user_id;
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), mapped_role);
    EXECUTE format('GRANT USAGE ON SCHEMA auth, object_store, program, process, scheduler, ipc, vfs, package, effect, terminal, audit TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.current_cilexec_user_id() TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.resolve_cilexec_user_id(name, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION object_store.put_object(bytea, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION object_store.put_object_as(name, text, bytea, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION object_store.read_object(bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION object_store.read_object_as(name, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION vfs.read_file_content(uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION vfs.read_file_content_as(name, text, uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION scheduler.release_process(uuid, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION scheduler.release_process_as(name, text, uuid, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION scheduler.heartbeat_process(uuid, uuid, uuid, uuid, bigint, timestamptz, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION scheduler.heartbeat_process_as(name, text, uuid, uuid, uuid, uuid, bigint, timestamptz, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.register_release_bundle(bytea, text, text, text, bytea, bytea, integer, text, jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.register_release_bundle_as(name, text, bytea, text, text, text, bytea, bytea, integer, text, jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.effective_capabilities(uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.effective_capabilities_as(name, text, uuid) TO %I', mapped_role);

    EXECUTE format('GRANT SELECT ON auth.capability TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON auth.group_account, auth.group_member TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON auth.user_capability, auth.group_capability TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON program.program, program.statement, program.module_binding TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON process.process, process.call_frame, process.scope, process.variable, process.exception_frame, process.wait_state, process.relationship TO %I', mapped_role);
    EXECUTE format('GRANT DELETE ON process.call_frame, process.scope, process.variable, process.exception_frame, process.wait_state, process.relationship TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON process.event TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON process.timer, scheduler.queue TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON process.package_binding TO %I', mapped_role);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE process.pid_sequence TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ipc.channel, ipc.topic, ipc.subscription, ipc.message, ipc.delivery TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON vfs.node TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON vfs.file_revision TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON vfs.mount TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON package.release, package.signature TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON package.release_dependency, package.release_module, package.release_entrypoint, package.release_export, package.release_capability TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON package.environment, package.binding, package.data_scope TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON effect.effect TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON terminal.session, terminal.input, terminal.attachment TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON audit.event TO %I', mapped_role);
    RETURN mapped_role;
END
$function$;
REVOKE ALL ON FUNCTION auth.grant_login_role_access(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.grant_login_role_access(uuid) TO cilexec_migrator;

-- name: auth.provision_principal
RESET ROLE;
CREATE FUNCTION auth.provision_principal(p_user_id uuid, p_password text)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    mapped_role := auth.provision_login_role(p_user_id, p_password);
    PERFORM auth.grant_login_role_access(p_user_id);
    RETURN mapped_role;
END
$function$;
REVOKE ALL ON FUNCTION auth.provision_principal(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.provision_principal(uuid, text) TO cilexec_runtime;

-- name: auth.disable_principal
CREATE FUNCTION auth.disable_principal(p_user_id uuid)
RETURNS name
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth
AS $function$
DECLARE
    mapped_role name;
BEGIN
    SELECT postgres_role_name INTO STRICT mapped_role
    FROM auth.user_account
    WHERE user_id = p_user_id;
    IF mapped_role::text <> 'cilexec_user_' || replace(p_user_id::text, '-', '') THEN
        RAISE EXCEPTION 'invalid stable PostgreSQL role mapping for user %', p_user_id;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
        EXECUTE format('ALTER ROLE %I NOLOGIN', mapped_role);
    END IF;
    RETURN mapped_role;
END
$function$;
REVOKE ALL ON FUNCTION auth.disable_principal(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth.disable_principal(uuid) TO cilexec_runtime;

-- name: meta.assert_security_invariants
SET ROLE cilexec_owner;
CREATE FUNCTION meta.assert_security_invariants()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, meta
AS $function$
DECLARE
    relation_record record;
    security_record meta.table_security_classification%ROWTYPE;
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_roles
        WHERE rolname IN ('cilexec_runtime', 'cilexec_effect_worker', 'cilexec_readonly')
          AND (rolsuper OR rolbypassrls OR rolcreatedb OR rolcreaterole)
    ) THEN
        RAISE EXCEPTION 'runtime service roles have forbidden cluster privileges';
    END IF;

    FOR relation_record IN
        SELECT namespace.nspname AS schema_name,
               relation.relname AS table_name,
               relation.relrowsecurity,
               relation.relforcerowsecurity
        FROM pg_catalog.pg_class AS relation
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        WHERE relation.relkind = 'r'
          AND namespace.nspname IN (
              'meta', 'auth', 'object_store', 'program', 'process', 'scheduler',
              'ipc', 'vfs', 'package', 'effect', 'terminal', 'audit'
          )
    LOOP
        SELECT * INTO security_record
        FROM meta.table_security_classification AS classification
        WHERE classification.schema_name = relation_record.schema_name::name
          AND classification.table_name = relation_record.table_name::name;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'table %.% has no security classification',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.classification = 'USER_SCOPED'
           AND (NOT relation_record.relrowsecurity OR NOT relation_record.relforcerowsecurity) THEN
            RAISE EXCEPTION 'user-scoped table %.% must ENABLE and FORCE RLS',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.classification = 'USER_SCOPED'
           AND NOT EXISTS (
               SELECT 1
               FROM pg_catalog.pg_policy AS policy
               WHERE policy.polrelid = format('%I.%I',
                   relation_record.schema_name, relation_record.table_name)::regclass
           ) THEN
            RAISE EXCEPTION 'user-scoped table %.% has no RLS policy',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.classification = 'SHARED_IMMUTABLE'
           AND (has_table_privilege('cilexec_runtime',
                   format('%I.%I', relation_record.schema_name, relation_record.table_name), 'UPDATE')
                OR has_table_privilege('cilexec_runtime',
                   format('%I.%I', relation_record.schema_name, relation_record.table_name), 'DELETE')) THEN
            RAISE EXCEPTION 'runtime has mutation privilege on immutable table %.%',
                relation_record.schema_name, relation_record.table_name;
        END IF;
    END LOOP;

    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_sequences
        WHERE schemaname = 'process'
          AND sequencename = 'pid_sequence'
          AND cycle
    ) THEN
        RAISE EXCEPTION 'process.pid_sequence must be NO CYCLE';
    END IF;
END
$function$;

-- name: migration.V015.security_catalog_grants
GRANT SELECT ON meta.table_security_classification TO cilexec_runtime, cilexec_readonly;
REVOKE ALL ON FUNCTION meta.assert_security_invariants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION meta.assert_security_invariants() TO cilexec_migrator, cilexec_readonly;

-- Fails the migration itself if a table lacks classification or mandatory RLS.
-- name: migration.V015.verify_security_invariants
SELECT meta.assert_security_invariants();

COMMENT ON TABLE meta.table_security_classification IS
    'Mandatory classification for every CilExec application table; migration tests compare it with pg_class';

RESET ROLE;
