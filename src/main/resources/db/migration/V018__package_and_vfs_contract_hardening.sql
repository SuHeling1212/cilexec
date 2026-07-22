SET ROLE cilexec_owner;

-- Package lookup rows are derived from PackageIndex. Keep the relational
-- representation at least as strict as those Java value objects so that a
-- caller cannot publish an index shape that the runtime could never create.
-- name: migration.V018.package_domain_constraints
ALTER TABLE package.release
    ADD CONSTRAINT ck_package_release_coordinate_domain
    CHECK (
        namespace ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
        AND package_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
        AND char_length(package_version) BETWEEN 1 AND 128
        AND package_version ~ '[^[:space:]]'
    ) NOT VALID;

ALTER TABLE package.release_module
    ADD CONSTRAINT ck_package_release_module_domain
    CHECK (
        module_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
        AND module_object_path <> ''
        AND left(module_object_path, 1) <> '/'
        AND right(module_object_path, 1) <> '/'
        AND position(chr(92) IN module_object_path) = 0
        AND module_object_path !~ '[[:cntrl:]]'
        AND module_object_path !~ '(^|/)[[:space:]]*($|/)'
        AND module_object_path !~ '(^|/)(\.|\.\.)(/|$)'
    ) NOT VALID;

ALTER TABLE package.release_dependency
    ADD CONSTRAINT ck_package_release_dependency_domain
    CHECK (
        dependency_namespace ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
        AND dependency_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
        AND version_constraint ~ '[^[:space:]]'
        AND version_constraint !~ '[[:cntrl:]]'
    ) NOT VALID;

ALTER TABLE package.release_entrypoint
    ADD CONSTRAINT ck_package_release_entrypoint_domain
    CHECK (
        entrypoint_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
        AND module_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
        AND function_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
    ) NOT VALID;

ALTER TABLE package.release_export
    ADD CONSTRAINT ck_package_release_export_domain
    CHECK (
        export_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
        AND module_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
        AND symbol_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
    ) NOT VALID;

ALTER TABLE package.release_capability
    ADD CONSTRAINT ck_package_release_capability_domain
    CHECK (capability_key ~ '^[a-z][a-z0-9_.:-]*$') NOT VALID;

ALTER TABLE package.release VALIDATE CONSTRAINT ck_package_release_coordinate_domain;
ALTER TABLE package.release_module VALIDATE CONSTRAINT ck_package_release_module_domain;
ALTER TABLE package.release_dependency VALIDATE CONSTRAINT ck_package_release_dependency_domain;
ALTER TABLE package.release_entrypoint VALIDATE CONSTRAINT ck_package_release_entrypoint_domain;
ALTER TABLE package.release_export VALIDATE CONSTRAINT ck_package_release_export_domain;
ALTER TABLE package.release_capability VALIDATE CONSTRAINT ck_package_release_capability_domain;

-- name: package.register_release_bundle_as
CREATE OR REPLACE FUNCTION package.register_release_bundle_as(
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
    IF NOT EXISTS (
        SELECT 1
        FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_import'
    ) THEN
        RAISE EXCEPTION 'package_import capability is required' USING ERRCODE = '42501';
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
    IF p_namespace IS NULL
       OR p_namespace !~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
       OR p_package_name IS NULL
       OR p_package_name !~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
       OR p_package_version IS NULL
       OR char_length(p_package_version) NOT BETWEEN 1 AND 128
       OR p_package_version !~ '[^[:space:]]' THEN
        RAISE EXCEPTION 'invalid package coordinate' USING ERRCODE = '22000';
    END IF;
    IF p_created_at IS NULL OR p_created_at > clock_timestamp() + interval '5 minutes' THEN
        RAISE EXCEPTION 'invalid package creation time' USING ERRCODE = '22000';
    END IF;
    IF p_modules IS NULL OR jsonb_typeof(p_modules) <> 'array'
       OR p_dependencies IS NULL OR jsonb_typeof(p_dependencies) <> 'array'
       OR p_entrypoints IS NULL OR jsonb_typeof(p_entrypoints) <> 'array'
       OR p_exports IS NULL OR jsonb_typeof(p_exports) <> 'array'
       OR p_capabilities IS NULL OR jsonb_typeof(p_capabilities) <> 'array' THEN
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
        text_value := item->>'moduleObjectPath';
        IF jsonb_typeof(item) <> 'object'
           OR jsonb_typeof(item->'moduleName') IS DISTINCT FROM 'string'
           OR (item->>'moduleName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'moduleObjectPath') IS DISTINCT FROM 'string'
           OR text_value = ''
           OR left(text_value, 1) = '/'
           OR right(text_value, 1) = '/'
           OR position(chr(92) IN text_value) <> 0
           OR text_value ~ '[[:cntrl:]]'
           OR text_value ~ '(^|/)[[:space:]]*($|/)'
           OR text_value ~ '(^|/)(\.|\.\.)(/|$)'
           OR jsonb_typeof(item->'moduleHash') IS DISTINCT FROM 'string'
           OR (item->>'moduleHash') !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'invalid package module index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_module(
            package_hash, module_name, module_object_path, module_hash
        ) VALUES (
            p_package_hash, item->>'moduleName', text_value,
            decode(item->>'moduleHash', 'hex')
        );
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(p_dependencies)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR jsonb_typeof(item->'dependencyNamespace') IS DISTINCT FROM 'string'
           OR (item->>'dependencyNamespace') !~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'dependencyName') IS DISTINCT FROM 'string'
           OR (item->>'dependencyName') !~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'versionConstraint') IS DISTINCT FROM 'string'
           OR (item->>'versionConstraint') !~ '[^[:space:]]'
           OR (item->>'versionConstraint') ~ '[[:cntrl:]]'
           OR jsonb_typeof(item->'optional') IS DISTINCT FROM 'boolean' THEN
            RAISE EXCEPTION 'invalid package dependency index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_dependency(
            package_hash, dependency_namespace, dependency_name,
            version_constraint, optional
        ) VALUES (
            p_package_hash, item->>'dependencyNamespace', item->>'dependencyName',
            item->>'versionConstraint', (item->>'optional')::boolean
        );
    END LOOP;

    FOR item IN SELECT value FROM jsonb_array_elements(p_entrypoints)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR jsonb_typeof(item->'entrypointName') IS DISTINCT FROM 'string'
           OR (item->>'entrypointName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'moduleName') IS DISTINCT FROM 'string'
           OR (item->>'moduleName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'functionName') IS DISTINCT FROM 'string'
           OR (item->>'functionName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$' THEN
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
           OR jsonb_typeof(item->'exportName') IS DISTINCT FROM 'string'
           OR (item->>'exportName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'moduleName') IS DISTINCT FROM 'string'
           OR (item->>'moduleName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'
           OR jsonb_typeof(item->'symbolName') IS DISTINCT FROM 'string'
           OR (item->>'symbolName') !~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$' THEN
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
           OR jsonb_typeof(item->'capabilityKey') IS DISTINCT FROM 'string'
           OR text_value !~ '^[a-z][a-z0-9_.:-]*$'
           OR jsonb_typeof(item->'required') IS DISTINCT FROM 'boolean'
           OR jsonb_typeof(item->'rationale') IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'invalid package capability index' USING ERRCODE = '22000';
        END IF;
        INSERT INTO package.release_capability(
            package_hash, capability_key, required, rationale
        ) VALUES (
            p_package_hash, text_value, (item->>'required')::boolean,
            item->>'rationale'
        );
    END LOOP;
    RETURN true;
END
$function$;

-- A revision is a server-authored fact. The caller supplies only stable
-- identities and the object already installed on the file node; PostgreSQL
-- binds created_by and allocates the contiguous per-node revision number.
-- name: vfs.append_file_revision_as
CREATE FUNCTION vfs.append_file_revision_as(
    p_database_role name,
    p_claim text,
    p_revision_id uuid,
    p_node_id uuid,
    p_owner_id uuid,
    p_object_hash bytea
)
RETURNS SETOF vfs.file_revision
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs
AS $function$
DECLARE
    actor uuid;
    node_record vfs.node%ROWTYPE;
    next_revision_number bigint;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL OR actor IS DISTINCT FROM p_owner_id THEN
        RAISE EXCEPTION 'a verified matching VFS owner is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'vfs_write'
    ) THEN
        RAISE EXCEPTION 'vfs_write capability is required' USING ERRCODE = '42501';
    END IF;
    IF p_revision_id IS NULL OR octet_length(p_object_hash) <> 32 THEN
        RAISE EXCEPTION 'invalid VFS revision identity' USING ERRCODE = '22000';
    END IF;

    SELECT node.* INTO node_record
    FROM vfs.node AS node
    WHERE node.node_id = p_node_id
      AND node.owner_id = actor
    FOR UPDATE;

    IF NOT FOUND
       OR node_record.node_type <> 'FILE'
       OR NOT node_record.revision_enabled
       OR node_record.current_object_hash IS DISTINCT FROM p_object_hash THEN
        RAISE EXCEPTION 'revision must identify the current content of a versioned owned file'
            USING ERRCODE = '22000';
    END IF;

    SELECT COALESCE(max(revision.revision_number), 0) + 1
    INTO next_revision_number
    FROM vfs.file_revision AS revision
    WHERE revision.node_id = p_node_id;

    RETURN QUERY
    INSERT INTO vfs.file_revision(
        revision_id, node_id, owner_id, revision_number,
        object_hash, created_by, created_at
    ) VALUES (
        p_revision_id, p_node_id, actor, next_revision_number,
        p_object_hash, actor, clock_timestamp()
    )
    RETURNING file_revision.*;
END
$function$;

-- name: vfs.append_file_revision
CREATE FUNCTION vfs.append_file_revision(
    p_revision_id uuid,
    p_node_id uuid,
    p_owner_id uuid,
    p_object_hash bytea
)
RETURNS SETOF vfs.file_revision
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, auth, vfs
AS $function$
    SELECT *
    FROM vfs.append_file_revision_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_revision_id,
        p_node_id,
        p_owner_id,
        p_object_hash
    )
$function$;

REVOKE ALL ON FUNCTION vfs.append_file_revision_as(name, text, uuid, uuid, uuid, bytea)
    FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.append_file_revision(uuid, uuid, uuid, bytea)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION vfs.append_file_revision_as(name, text, uuid, uuid, uuid, bytea)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION vfs.append_file_revision(uuid, uuid, uuid, bytea)
    TO cilexec_runtime;

REVOKE INSERT ON vfs.file_revision FROM PUBLIC, cilexec_runtime,
    cilexec_effect_worker, cilexec_readonly;

-- Apply the least-privilege revision API to roles provisioned before V018.
DO $existing_principals$
DECLARE
    mapped_role name;
BEGIN
    FOR mapped_role IN
        SELECT account.postgres_role_name
        FROM auth.user_account AS account
        WHERE account.postgres_role_name IS NOT NULL
    LOOP
        IF EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = mapped_role) THEN
            EXECUTE format('REVOKE INSERT ON vfs.file_revision FROM %I', mapped_role);
            EXECUTE format('GRANT SELECT ON vfs.file_revision TO %I', mapped_role);
            EXECUTE format(
                'GRANT EXECUTE ON FUNCTION vfs.append_file_revision(uuid, uuid, uuid, bytea) TO %I',
                mapped_role
            );
            EXECUTE format(
                'GRANT EXECUTE ON FUNCTION vfs.append_file_revision_as(name, text, uuid, uuid, uuid, bytea) TO %I',
                mapped_role
            );
        END IF;
    END LOOP;
END
$existing_principals$;

-- Mount rows are only meaningful for MOUNT nodes, and host mounts are always
-- read-only. The path check mirrors VfsMount.validateContainerPath, including
-- its rejection of whitespace-only components.
-- name: migration.V018.vfs_node_and_mount_constraints
ALTER TABLE vfs.node
    ADD CONSTRAINT ck_vfs_revision_enabled_file_only
    CHECK (NOT revision_enabled OR node_type = 'FILE') NOT VALID;

ALTER TABLE vfs.mount
    ADD CONSTRAINT ck_vfs_mount_read_only CHECK (read_only) NOT VALID,
    ADD CONSTRAINT ck_vfs_mount_container_path_domain CHECK (
        left(container_path, 1) = '/'
        AND container_path <> '/'
        AND right(container_path, 1) <> '/'
        AND position('//' IN container_path) = 0
        AND position(chr(92) IN container_path) = 0
        AND container_path !~ '[[:cntrl:]]'
        AND container_path !~ '(^|/)[[:space:]]*($|/)'
        AND container_path !~ '(^|/)(\.|\.\.)(/|$)'
    ) NOT VALID;

ALTER TABLE vfs.node VALIDATE CONSTRAINT ck_vfs_revision_enabled_file_only;
ALTER TABLE vfs.mount VALIDATE CONSTRAINT ck_vfs_mount_read_only;
ALTER TABLE vfs.mount VALIDATE CONSTRAINT ck_vfs_mount_container_path_domain;

DO $validate_existing_mount_nodes$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM vfs.mount AS mount
        JOIN vfs.node AS node
          ON node.node_id = mount.node_id AND node.owner_id = mount.owner_id
        WHERE node.node_type <> 'MOUNT' OR node.revision_enabled
    ) THEN
        RAISE EXCEPTION 'existing VFS mount is not associated with a canonical MOUNT node';
    END IF;
END
$validate_existing_mount_nodes$;

CREATE FUNCTION vfs.enforce_mount_node_type()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, vfs
AS $function$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM vfs.node AS node
        WHERE node.node_id = NEW.node_id
          AND node.owner_id = NEW.owner_id
          AND node.node_type = 'MOUNT'
          AND NOT node.revision_enabled
    ) THEN
        RAISE EXCEPTION 'VFS mount must reference an owned MOUNT node'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER mount_enforce_node_type
BEFORE INSERT OR UPDATE OF node_id, owner_id ON vfs.mount
FOR EACH ROW EXECUTE FUNCTION vfs.enforce_mount_node_type();

CREATE FUNCTION vfs.prevent_mounted_node_type_change()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, vfs
AS $function$
BEGIN
    IF (NEW.node_type <> 'MOUNT' OR NEW.revision_enabled)
       AND EXISTS (
           SELECT 1
           FROM vfs.mount AS mount
           WHERE mount.node_id = OLD.node_id AND mount.owner_id = OLD.owner_id
       ) THEN
        RAISE EXCEPTION 'a mounted VFS node must remain a non-versioned MOUNT node'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$function$;

CREATE TRIGGER node_preserve_mounted_type
BEFORE UPDATE OF node_type, revision_enabled ON vfs.node
FOR EACH ROW EXECUTE FUNCTION vfs.prevent_mounted_node_type_change();

REVOKE ALL ON FUNCTION vfs.enforce_mount_node_type() FROM PUBLIC;
REVOKE ALL ON FUNCTION vfs.prevent_mounted_node_type_change() FROM PUBLIC;

-- Keep future LOGIN roles aligned with the new revision write boundary. This
-- is the V015 grant catalog with direct file_revision INSERT replaced by the
-- two identity-bound append functions.
-- name: auth.grant_login_role_access
CREATE OR REPLACE FUNCTION auth.grant_login_role_access(p_user_id uuid)
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
    EXECUTE format('GRANT EXECUTE ON FUNCTION vfs.append_file_revision(uuid, uuid, uuid, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION vfs.append_file_revision_as(name, text, uuid, uuid, uuid, bytea) TO %I', mapped_role);
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
    EXECUTE format('GRANT SELECT ON vfs.file_revision TO %I', mapped_role);
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

COMMENT ON FUNCTION vfs.append_file_revision(uuid, uuid, uuid, bytea) IS
    'Identity-bound append API; PostgreSQL assigns created_by, created_at, and contiguous revision_number';
COMMENT ON FUNCTION package.register_release_bundle_as(
    name, text, bytea, text, text, text, bytea, bytea, integer, text,
    jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz
) IS 'Atomically publishes a PackageIndex only after database-equivalent domain and capability checks';

RESET ROLE;
