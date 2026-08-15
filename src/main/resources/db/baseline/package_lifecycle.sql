-- Atomic package lifecycle and private data: per-user installation ledger,
-- per-package private data spaces, exact-version quotas, and atomic per-user
-- uninstallation with controlled global release/object garbage collection.
-- Part of the pre-release 1.0 baseline (merged from the original V002 draft).

SET ROLE cilexec_owner;

-- ============================================================================
-- Component: package identity tombstones
-- ============================================================================

CREATE TABLE package.release_identity (
    package_hash bytea PRIMARY KEY CHECK (octet_length(package_hash) = 32),
    namespace text NOT NULL CHECK (namespace ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    package_name text NOT NULL CHECK (package_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    package_version text NOT NULL CHECK (
        package_version ~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$'),
    database_file_hash bytea NOT NULL CHECK (octet_length(database_file_hash) = 32),
    first_registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (namespace, package_name, package_version),
    UNIQUE (database_file_hash)
);

CREATE TRIGGER release_identity_reject_update_delete
BEFORE UPDATE OR DELETE ON package.release_identity
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- ============================================================================
-- Component: per-user installation ledger
-- ============================================================================

CREATE TABLE package.installation_root (
    installation_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    root_package_hash bytea NOT NULL
        REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    source text NOT NULL CHECK (source IN ('LOCAL', 'MARKET', 'LEGACY')),
    installed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (installation_id, owner_id),
    UNIQUE (owner_id, root_package_hash, source)
);

CREATE TABLE package.installation_member (
    installation_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    package_hash bytea NOT NULL CHECK (octet_length(package_hash) = 32)
        REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    dependency_depth integer NOT NULL CHECK (dependency_depth >= 0),
    optional boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (installation_id, package_hash),
    FOREIGN KEY (installation_id, owner_id)
        REFERENCES package.installation_root(installation_id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_installation_member_package ON package.installation_member(package_hash);
CREATE INDEX ix_installation_root_owner ON package.installation_root(owner_id, installed_at);

-- ============================================================================
-- Component: per-user per-package private data spaces
-- ============================================================================

CREATE TABLE package.data_space (
    space_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    package_hash bytea NOT NULL CHECK (octet_length(package_hash) = 32)
        REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    database_file_hash bytea NOT NULL CHECK (octet_length(database_file_hash) = 32),
    logical_bytes bigint NOT NULL DEFAULT 0 CHECK (logical_bytes >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_id, package_hash),
    UNIQUE (space_id, owner_id)
);

CREATE TABLE package.data_entry (
    space_id uuid NOT NULL
        REFERENCES package.data_space(space_id) ON DELETE CASCADE,
    relative_path text NOT NULL,
    entry_type text NOT NULL CHECK (entry_type IN ('FILE', 'DIRECTORY')),
    object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    byte_size bigint NOT NULL DEFAULT 0 CHECK (byte_size >= 0),
    state_version bigint NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (space_id, relative_path),
    CHECK ((entry_type = 'FILE' AND object_hash IS NOT NULL AND byte_size > 0)
        OR (entry_type = 'DIRECTORY' AND object_hash IS NULL AND byte_size = 0)),
    CHECK (relative_path <> ''
        AND left(relative_path, 1) <> '/'
        AND right(relative_path, 1) <> '/'
        AND position(chr(92) IN relative_path) = 0
        AND relative_path !~ '[[:cntrl:]]'
        AND relative_path !~ '(^|/)(\.|\.\.)(/|$)'
        AND char_length(relative_path) <= 1024)
);

CREATE INDEX ix_data_entry_object ON package.data_entry(object_hash)
    WHERE object_hash IS NOT NULL;

-- ============================================================================
-- Component: data quotas
-- ============================================================================

CREATE TABLE package.data_policy (
    policy_id uuid PRIMARY KEY,
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    default_quota_bytes bigint NOT NULL CHECK (default_quota_bytes >= 0),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO package.data_policy(policy_id, default_quota_bytes)
VALUES ('00000000-0000-4000-8000-0000000000a0', 268435456);

CREATE TABLE package.data_quota_override (
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    package_hash bytea NOT NULL CHECK (octet_length(package_hash) = 32),
    quota_bytes bigint NOT NULL CHECK (quota_bytes >= 0),
    updated_by uuid REFERENCES auth.user_account(user_id) ON DELETE SET NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (owner_id, package_hash)
);

-- ============================================================================
-- Component: managed artifacts
-- ============================================================================

-- VFS nodes that belong to a package installation rather than to the user's
-- ordinary documents. Uninstallation deletes these nodes and counts them as
-- removed cache files; ordinary VFS files are never touched.
CREATE TABLE package.managed_node (
    node_id uuid PRIMARY KEY REFERENCES vfs.node(node_id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    package_hash bytea NOT NULL CHECK (octet_length(package_hash) = 32),
    purpose text NOT NULL CHECK (purpose IN ('MARKET_CACHE', 'PACKAGE_DATA')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (node_id, owner_id)
);

-- ============================================================================
-- Component: security catalog
-- ============================================================================

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES
    ('package', 'release_identity', 'SHARED_IMMUTABLE', NULL,
     'permanent package coordinate and hash identity tombstone'),
    ('package', 'installation_root', 'USER_SCOPED', 'owner_id',
     'per-user explicit package acquisition ledger'),
    ('package', 'installation_member', 'USER_SCOPED', 'owner_id',
     'per-installation exact dependency closure'),
    ('package', 'data_space', 'USER_SCOPED', 'owner_id',
     'per-user per-package private data space'),
    ('package', 'data_entry', 'USER_SCOPED', 'space_id',
     'private package data entry'),
    ('package', 'data_policy', 'SYSTEM_RUNTIME', NULL,
     'default package data quota policy'),
    ('package', 'data_quota_override', 'USER_SCOPED', 'owner_id',
     'per-user per-package quota override'),
    ('package', 'managed_node', 'USER_SCOPED', 'owner_id',
     'package-owned VFS artifacts deletable on uninstall');

-- New tables are not exportable by default; every exported relation is
-- reviewed here. Package lifecycle and private data are authoritative
-- PostgreSQL state and belong in every logical backup.
UPDATE meta.table_security_classification AS classification
SET exportable = true
FROM (VALUES
    ('package', 'release_identity'),
    ('package', 'installation_root'),
    ('package', 'installation_member'),
    ('package', 'data_space'),
    ('package', 'data_entry'),
    ('package', 'data_policy'),
    ('package', 'data_quota_override'),
    ('package', 'managed_node')
) AS exported(schema_name, table_name)
WHERE classification.schema_name = exported.schema_name
  AND classification.table_name = exported.table_name;

-- The exporter can read only explicitly exportable tables. These relations
-- need the same reviewed SELECT grants and forced-RLS read
-- policies that the earlier baseline modules applied to their own set.
DO $exporter_access$
DECLARE
    approved record;
BEGIN
    FOR approved IN
        SELECT classification.schema_name::text AS schema_name,
               classification.table_name::text AS table_name,
               classification.classification
        FROM meta.table_security_classification AS classification
        WHERE classification.exportable
          AND (classification.schema_name, classification.table_name) IN (
              ('package', 'release_identity'),
              ('package', 'installation_root'),
              ('package', 'installation_member'),
              ('package', 'data_space'),
              ('package', 'data_entry'),
              ('package', 'data_policy'),
              ('package', 'data_quota_override'),
              ('package', 'managed_node')
          )
        ORDER BY classification.schema_name, classification.table_name
    LOOP
        EXECUTE format('GRANT SELECT ON TABLE %I.%I TO cilexec_exporter',
                approved.schema_name, approved.table_name);
        IF approved.classification = 'USER_SCOPED' THEN
            EXECUTE format(
                'CREATE POLICY cilexec_exporter_read ON %I.%I '
                'FOR SELECT TO cilexec_exporter USING (true)',
                approved.schema_name, approved.table_name);
        END IF;
    END LOOP;
END
$exporter_access$;

-- ============================================================================
-- Component: RLS
-- ============================================================================

DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'installation_root', 'installation_member',
        'data_space', 'data_quota_override', 'managed_node'
    ]
    LOOP
        EXECUTE format('ALTER TABLE package.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('ALTER TABLE package.%I FORCE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('CREATE POLICY %I ON package.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON package.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON package.%I TO PUBLIC USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', relation_name);
    END LOOP;
    -- data_entry carries no owner column; its principal policy resolves the
    -- owner through the enclosing data space.
    ALTER TABLE package.data_entry ENABLE ROW LEVEL SECURITY;
    ALTER TABLE package.data_entry FORCE ROW LEVEL SECURITY;
    CREATE POLICY data_entry_owner_control ON package.data_entry
        TO cilexec_owner USING (true) WITH CHECK (true);
    CREATE POLICY data_entry_runtime_control ON package.data_entry
        TO cilexec_runtime USING (true) WITH CHECK (true);
    CREATE POLICY data_entry_principal ON package.data_entry TO PUBLIC
        USING (space_id IN (
            SELECT space.space_id FROM package.data_space AS space
            WHERE space.owner_id = auth.current_cilexec_user_id()))
        WITH CHECK (space_id IN (
            SELECT space.space_id FROM package.data_space AS space
            WHERE space.owner_id = auth.current_cilexec_user_id()));
    ALTER TABLE package.release_identity ENABLE ROW LEVEL SECURITY;
    ALTER TABLE package.release_identity FORCE ROW LEVEL SECURITY;
    CREATE POLICY release_identity_owner_control ON package.release_identity
        TO cilexec_owner USING (true) WITH CHECK (true);
    CREATE POLICY release_identity_runtime_control ON package.release_identity
        TO cilexec_runtime USING (true) WITH CHECK (true);
    CREATE POLICY release_identity_migrator_control ON package.release_identity
        TO cilexec_migrator USING (true) WITH CHECK (true);
END
$rls$;

-- ============================================================================
-- Component: grants
-- ============================================================================

GRANT SELECT, INSERT ON package.release_identity TO cilexec_runtime;
GRANT SELECT, INSERT, DELETE ON package.installation_root, package.installation_member
    TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON package.data_space, package.data_entry
    TO cilexec_runtime;
GRANT SELECT, UPDATE ON package.data_policy TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON package.data_quota_override TO cilexec_runtime;
GRANT SELECT, INSERT, DELETE ON package.managed_node TO cilexec_runtime;

-- ============================================================================
-- Component: identity backfill
-- ============================================================================

INSERT INTO package.release_identity(
    package_hash, namespace, package_name, package_version,
    database_file_hash, first_registered_at
)
SELECT release.package_hash, release.namespace, release.package_name,
       release.package_version, release.database_file_hash, release.created_at
FROM package.release AS release
ON CONFLICT (package_hash) DO NOTHING;

-- Legacy per-user installations inferred from process bindings.
INSERT INTO package.installation_root(
    installation_id, owner_id, root_package_hash, source, installed_at
)
SELECT gen_random_uuid(), binding.owner_id, binding.package_hash, 'LEGACY',
       release.created_at
FROM process.package_binding AS binding
JOIN package.release AS release ON release.package_hash = binding.package_hash
GROUP BY binding.owner_id, binding.package_hash, release.created_at
ON CONFLICT (owner_id, root_package_hash, source) DO NOTHING;

-- Legacy per-user installations inferred from the original importer.
INSERT INTO package.installation_root(
    installation_id, owner_id, root_package_hash, source, installed_at
)
SELECT gen_random_uuid(), release.imported_by, release.package_hash, 'LEGACY',
       release.created_at
FROM package.release AS release
WHERE release.imported_by IS NOT NULL
ON CONFLICT (owner_id, root_package_hash, source) DO NOTHING;

-- Exact dependency closure for every legacy root.
WITH RECURSIVE closure(
    root_installation_id, root_owner, package_hash, dependency_depth, optional
) AS (
    SELECT root.installation_id, root.owner_id, root.root_package_hash, 0, false
    FROM package.installation_root AS root
    WHERE root.source = 'LEGACY'
    UNION
    SELECT closure.root_installation_id, closure.root_owner,
           dependency_release.package_hash, closure.dependency_depth + 1,
           dependency.optional
    FROM closure
    JOIN package.release AS current
        ON current.package_hash = closure.package_hash
    JOIN package.release_dependency AS dependency
        ON dependency.package_hash = current.package_hash
    JOIN package.release AS dependency_release
        ON dependency_release.database_file_hash = dependency.dependency_file_hash
    WHERE closure.dependency_depth < 256
)
INSERT INTO package.installation_member(
    installation_id, owner_id, package_hash, dependency_depth, optional, created_at
)
SELECT root_installation_id, root_owner, package_hash, dependency_depth, optional,
       clock_timestamp()
FROM closure
ON CONFLICT (installation_id, package_hash) DO NOTHING;

-- Empty private data spaces for every effective user installation.
INSERT INTO package.data_space(
    space_id, owner_id, package_hash, database_file_hash,
    logical_bytes, created_at, updated_at
)
SELECT gen_random_uuid(), member.owner_id, member.package_hash,
       release.database_file_hash, 0, clock_timestamp(), clock_timestamp()
FROM package.installation_member AS member
JOIN package.release AS release ON release.package_hash = member.package_hash
GROUP BY member.owner_id, member.package_hash, release.database_file_hash
ON CONFLICT (owner_id, package_hash) DO NOTHING;

-- ============================================================================
-- Component: managed artifact registration
-- ============================================================================

CREATE FUNCTION package.register_managed_node_as(
    p_database_role name,
    p_claim text,
    p_node_id uuid,
    p_file_sha256 bytea,
    p_purpose text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, vfs, package
AS $function$
DECLARE
    actor uuid;
    target package.release%ROWTYPE;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    IF p_purpose NOT IN ('MARKET_CACHE', 'PACKAGE_DATA') THEN
        RAISE EXCEPTION 'invalid managed node purpose' USING ERRCODE = '22000';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM vfs.node WHERE node_id = p_node_id AND owner_id = actor
    ) THEN
        RAISE EXCEPTION 'managed VFS node is not owned by the caller' USING ERRCODE = '55006';
    END IF;
    SELECT * INTO STRICT target
    FROM package.release
    WHERE database_file_hash = p_file_sha256;
    INSERT INTO package.managed_node(node_id, owner_id, package_hash, purpose)
    VALUES (p_node_id, actor, target.package_hash, p_purpose)
    ON CONFLICT (node_id) DO UPDATE
    SET package_hash = EXCLUDED.package_hash,
        purpose = EXCLUDED.purpose;
    RETURN jsonb_build_object('ok', true);
END
$function$;
REVOKE ALL ON FUNCTION package.register_managed_node_as(name, text, uuid, bytea, text)
    FROM PUBLIC;

CREATE FUNCTION package.register_managed_node(
    p_node_id uuid, p_file_sha256 bytea, p_purpose text
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.register_managed_node_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_node_id, p_file_sha256, p_purpose
    )
$function$;
REVOKE ALL ON FUNCTION package.register_managed_node(uuid, bytea, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.register_managed_node_as(name, text, uuid, bytea, text)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.register_managed_node(uuid, bytea, text)
    TO cilexec_runtime;

-- ============================================================================
-- Component: atomic installation publication
-- ============================================================================

CREATE FUNCTION package.publish_installation_as(
    p_database_role name,
    p_claim text,
    p_installation_id uuid,
    p_root_file_sha256 bytea,
    p_source text,
    p_members jsonb,
    p_created_at timestamptz
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    root_release package.release%ROWTYPE;
    item jsonb;
    item_hash bytea;
    member_depth integer;
    member_optional boolean;
    member_count integer := 0;
    root_seen boolean := false;
    inserted_root integer;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_import'
    ) THEN
        RAISE EXCEPTION 'package_import capability is required' USING ERRCODE = '42501';
    END IF;
    IF p_source NOT IN ('LOCAL', 'MARKET', 'LEGACY') THEN
        RAISE EXCEPTION 'invalid installation source' USING ERRCODE = '22000';
    END IF;
    IF octet_length(p_root_file_sha256) <> 32
       OR jsonb_typeof(p_members) <> 'array' THEN
        RAISE EXCEPTION 'invalid installation publication input' USING ERRCODE = '22000';
    END IF;

    SELECT * INTO STRICT root_release
    FROM package.release
    WHERE database_file_hash = p_root_file_sha256;

    FOR item IN SELECT value FROM jsonb_array_elements(p_members)
    LOOP
        IF jsonb_typeof(item) <> 'object'
           OR jsonb_typeof(item->'packageHash') IS DISTINCT FROM 'string'
           OR (item->>'packageHash') !~ '^[0-9a-f]{64}$'
           OR jsonb_typeof(item->'dependencyDepth') IS DISTINCT FROM 'number'
           OR jsonb_typeof(item->'optional') IS DISTINCT FROM 'boolean' THEN
            RAISE EXCEPTION 'invalid installation member index' USING ERRCODE = '22000';
        END IF;
        member_count := member_count + 1;
        IF member_count > 256 THEN
            RAISE EXCEPTION 'installation closure exceeds 256 packages' USING ERRCODE = '22000';
        END IF;
        item_hash := decode(item->>'packageHash', 'hex');
        member_depth := (item->>'dependencyDepth')::integer;
        member_optional := (item->>'optional')::boolean;
        IF member_depth < 0 THEN
            RAISE EXCEPTION 'invalid dependency depth' USING ERRCODE = '22000';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM package.release WHERE package_hash = item_hash) THEN
            RAISE EXCEPTION 'installation member release is missing' USING ERRCODE = '22000';
        END IF;
        IF item_hash = root_release.package_hash THEN
            IF member_depth <> 0 THEN
                RAISE EXCEPTION 'root package depth must be zero' USING ERRCODE = '22000';
            END IF;
            root_seen := true;
        END IF;
    END LOOP;
    IF NOT root_seen THEN
        RAISE EXCEPTION 'installation closure must include the root package' USING ERRCODE = '22000';
    END IF;

    INSERT INTO package.installation_root(
        installation_id, owner_id, root_package_hash, source, installed_at
    ) VALUES (
        p_installation_id, actor, root_release.package_hash, p_source, p_created_at
    )
    ON CONFLICT (owner_id, root_package_hash, source) DO NOTHING;
    GET DIAGNOSTICS inserted_root = ROW_COUNT;
    IF inserted_root = 0 THEN
        RETURN jsonb_build_object('created', false);
    END IF;

    FOR item IN SELECT value FROM jsonb_array_elements(p_members)
    LOOP
        INSERT INTO package.installation_member(
            installation_id, owner_id, package_hash, dependency_depth, optional
        ) VALUES (
            p_installation_id, actor, decode(item->>'packageHash', 'hex'),
            (item->>'dependencyDepth')::integer, (item->>'optional')::boolean
        ) ON CONFLICT (installation_id, package_hash) DO NOTHING;
    END LOOP;

    INSERT INTO package.data_space(
        space_id, owner_id, package_hash, database_file_hash,
        logical_bytes, created_at, updated_at
    )
    SELECT gen_random_uuid(), actor, release.package_hash,
           release.database_file_hash, 0, clock_timestamp(), clock_timestamp()
    FROM package.release AS release
    WHERE release.package_hash IN (
        SELECT decode(member_item->>'packageHash', 'hex')
        FROM jsonb_array_elements(p_members) AS member_item
    )
    ON CONFLICT (owner_id, package_hash) DO NOTHING;

    RETURN jsonb_build_object('created', true);
END
$function$;
REVOKE ALL ON FUNCTION package.publish_installation_as(
    name, text, uuid, bytea, text, jsonb, timestamptz
) FROM PUBLIC;

CREATE FUNCTION package.publish_installation(
    p_installation_id uuid,
    p_root_file_sha256 bytea,
    p_source text,
    p_members jsonb,
    p_created_at timestamptz
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.publish_installation_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_installation_id, p_root_file_sha256, p_source, p_members, p_created_at
    )
$function$;
REVOKE ALL ON FUNCTION package.publish_installation(uuid, bytea, text, jsonb, timestamptz)
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.publish_installation_as(
    name, text, uuid, bytea, text, jsonb, timestamptz
) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.publish_installation(uuid, bytea, text, jsonb, timestamptz)
    TO cilexec_runtime;

-- ============================================================================
-- Component: effective installation lookup
-- ============================================================================

CREATE FUNCTION package.installed_release_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF octet_length(p_file_sha256) <> 32 THEN
        RETURN NULL;
    END IF;
    RETURN (
        SELECT jsonb_build_object(
            'packageHash', encode(release.package_hash, 'hex'),
            'coordinate', release.namespace || '/' || release.package_name
                || '/' || release.package_version,
            'namespace', release.namespace,
            'name', release.package_name,
            'version', release.package_version,
            'databaseFileSha256', encode(release.database_file_hash, 'hex'),
            'databaseObjectSha256', encode(release.database_object_hash, 'hex')
        )
        FROM package.release AS release
        WHERE release.database_file_hash = p_file_sha256
          AND EXISTS (
              SELECT 1 FROM package.installation_member AS member
              WHERE member.owner_id = actor
                AND member.package_hash = release.package_hash
          )
        LIMIT 1
    );
END
$function$;
REVOKE ALL ON FUNCTION package.installed_release_as(name, text, bytea) FROM PUBLIC;

CREATE FUNCTION package.installed_release(p_file_sha256 bytea)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.installed_release_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256
    )
$function$;
REVOKE ALL ON FUNCTION package.installed_release(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.installed_release_as(name, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.installed_release(bytea) TO cilexec_runtime;

-- ============================================================================
-- Component: user installation listing
-- ============================================================================

CREATE FUNCTION package.list_user_installations_as(
    p_database_role name,
    p_claim text
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    RETURN (
        SELECT COALESCE(jsonb_agg(root_json ORDER BY installed_at, installation_id), '[]'::jsonb)
        FROM (
            SELECT jsonb_build_object(
                'installationId', root.installation_id::text,
                'source', root.source,
                'installedAt', root.installed_at,
                'rootFileSha256', encode(release.database_file_hash, 'hex'),
                'rootCoordinate', release.namespace || '/' || release.package_name
                    || '/' || release.package_version,
                'members', (
                    SELECT COALESCE(jsonb_agg(member_json ORDER BY dependency_depth,
                        package_hash), '[]'::jsonb)
                    FROM (
                        SELECT jsonb_build_object(
                            'packageHash', encode(member.package_hash, 'hex'),
                            'dependencyDepth', member.dependency_depth,
                            'optional', member.optional,
                            'databaseFileSha256', encode(member_release.database_file_hash, 'hex'),
                            'coordinate', member_release.namespace || '/'
                                || member_release.package_name || '/'
                                || member_release.package_version
                        ) AS member_json, member.dependency_depth AS dependency_depth,
                        member.package_hash AS package_hash
                        FROM package.installation_member AS member
                        JOIN package.release AS member_release
                            ON member_release.package_hash = member.package_hash
                        WHERE member.installation_id = root.installation_id
                    ) AS members
                )
            ) AS root_json, root.installed_at, root.installation_id
            FROM package.installation_root AS root
            JOIN package.release AS release
                ON release.package_hash = root.root_package_hash
            WHERE root.owner_id = actor
        ) AS installations
    );
END
$function$;
REVOKE ALL ON FUNCTION package.list_user_installations_as(name, text) FROM PUBLIC;

CREATE FUNCTION package.list_user_installations()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.list_user_installations_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), '')
    )
$function$;
REVOKE ALL ON FUNCTION package.list_user_installations() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.list_user_installations_as(name, text) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.list_user_installations() TO cilexec_runtime;

-- ============================================================================
-- Component: atomic per-user uninstallation
-- ============================================================================

CREATE FUNCTION package.uninstall_package_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_force boolean,
    p_caller_process_uid uuid
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, vfs, program, process,
    scheduler, ipc, effect, terminal, package
AS $function$
DECLARE
    actor uuid;
    target package.release%ROWTYPE;
    roots integer := 0;
    dependents integer := 0;
    removed_members integer := 0;
    removed_spaces integer := 0;
    removed_entries integer := 0;
    removed_processes integer := 0;
    removed_bindings integer := 0;
    cache_files_removed integer := 0;
    purged_releases integer := 0;
    purged_objects integer := 0;
    blockers text := '';
    blocker record;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key IN ('package_import', 'package_bind')
    ) THEN
        RAISE EXCEPTION 'package_import and package_bind capabilities are required'
            USING ERRCODE = '42501';
    END IF;
    IF p_force AND NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'process_control_own'
    ) THEN
        RAISE EXCEPTION 'process_control_own capability is required for forced uninstall'
            USING ERRCODE = '42501';
    END IF;
    IF octet_length(p_file_sha256) <> 32 THEN
        RAISE EXCEPTION 'invalid package SHA-256' USING ERRCODE = '22000';
    END IF;

    SELECT * INTO target
    FROM package.release
    WHERE database_file_hash = p_file_sha256;
    IF NOT FOUND THEN
        DELETE FROM package.installation_root
        WHERE owner_id = actor AND source IN ('LOCAL', 'MARKET', 'LEGACY')
          AND root_package_hash IN (
              SELECT identity.package_hash
              FROM package.release_identity AS identity
              WHERE identity.database_file_hash = p_file_sha256
          );
        RETURN jsonb_build_object(
            'removed', false,
            'packagesRemoved', 0, 'dependenciesRemoved', 0,
            'processesRemoved', 0, 'bindingsRemoved', 0,
            'cacheFilesRemoved', 0, 'dataNodesRemoved', 0,
            'releasesPurged', 0, 'objectsPurged', 0
        );
    END IF;

    -- Deterministic lock order: releases, roots, spaces.
    PERFORM 1 FROM package.release AS release
    WHERE EXISTS (
        SELECT 1 FROM package.installation_member AS member
        WHERE member.owner_id = actor
          AND member.package_hash = release.package_hash
          AND member.installation_id IN (
              SELECT root.installation_id
              FROM package.installation_root AS root
              WHERE root.owner_id = actor
                AND EXISTS (
                    SELECT 1 FROM package.installation_member AS target_member
                    WHERE target_member.installation_id = root.installation_id
                      AND target_member.package_hash = target.package_hash
                )
          )
    )
    ORDER BY release.package_hash
    FOR UPDATE OF release;

    SELECT count(*) INTO dependents
    FROM package.installation_root AS root
    WHERE root.owner_id = actor
      AND root.root_package_hash <> target.package_hash
      AND EXISTS (
          SELECT 1 FROM package.installation_member AS member
          WHERE member.installation_id = root.installation_id
            AND member.package_hash = target.package_hash
      );

    IF NOT p_force AND dependents > 0 THEN
        RAISE EXCEPTION 'cannot uninstall: % installed packages depend on it; retry with force=true',
            dependents USING ERRCODE = '55006';
    END IF;

    IF NOT p_force THEN
        blockers := '';
        FOR blocker IN
            SELECT process.pid, process.status
            FROM process.process AS process
            JOIN process.package_binding AS binding USING (process_uid)
            WHERE process.owner_id = actor
              AND binding.package_hash = target.package_hash
              AND process.status NOT IN ('TERMINATED', 'FAILED', 'FAILED_RECOVERY')
        LOOP
            blockers := blockers || ' pid ' || blocker.pid || ' (' || blocker.status || ')';
        END LOOP;
        IF blockers <> '' THEN
            RAISE EXCEPTION 'cannot uninstall: active processes are bound to the package:%',
                blockers USING ERRCODE = '55006';
        END IF;
    END IF;

    IF p_force THEN
        PERFORM 1 FROM process.process AS process
        WHERE process.process_uid = p_caller_process_uid
          AND process.owner_id = actor
          AND EXISTS (
              SELECT 1 FROM process.package_binding AS binding
              WHERE binding.process_uid = process.process_uid
                AND binding.package_hash = target.package_hash
          );
        IF FOUND THEN
            RAISE EXCEPTION 'the calling process imports the package; run uninstall from another terminal process'
                USING ERRCODE = '55006';
        END IF;
    END IF;

    -- Terminate and purge processes bound to the removed closure.
    WITH removed_closure AS (
        SELECT DISTINCT member.package_hash
        FROM package.installation_member AS member
        WHERE member.owner_id = actor
          AND member.installation_id IN (
              SELECT root.installation_id
              FROM package.installation_root AS root
              WHERE root.owner_id = actor
                AND (root.root_package_hash = target.package_hash OR p_force)
                AND EXISTS (
                    SELECT 1 FROM package.installation_member AS target_member
                    WHERE target_member.installation_id = root.installation_id
                      AND target_member.package_hash = target.package_hash
                )
          )
    ), doomed AS (
        SELECT process.process_uid, process.owner_id
        FROM process.process AS process
        JOIN process.package_binding AS binding USING (process_uid)
        WHERE process.owner_id = actor
          AND binding.package_hash IN (SELECT package_hash FROM removed_closure)
        FOR UPDATE OF process
    ), clear_effects AS (
        DELETE FROM effect.effect AS effect USING doomed
        WHERE effect.process_uid = doomed.process_uid
          AND effect.owner_id = doomed.owner_id
    ), clear_locks AS (
        DELETE FROM vfs.node_lock AS lock USING doomed
        WHERE lock.process_uid = doomed.process_uid
    ), clear_swap AS (
        UPDATE ipc.swap_value AS value SET lock_process_uid = NULL,
            lock_execution_epoch = NULL, lease_until = NULL
        FROM doomed
        WHERE value.lock_process_uid = doomed.process_uid
    ), clear_inputs AS (
        DELETE FROM terminal.input AS input USING doomed
        WHERE input.target_process_uid = doomed.process_uid
    ), clear_queue AS (
        DELETE FROM scheduler.queue AS queue USING doomed
        WHERE queue.process_uid = doomed.process_uid
          AND queue.owner_id = doomed.owner_id
    ), clear_leases AS (
        DELETE FROM scheduler.lease AS lease USING doomed
        WHERE lease.process_uid = doomed.process_uid
    ), removed AS (
        DELETE FROM process.process AS process USING doomed
        WHERE process.process_uid = doomed.process_uid
          AND process.owner_id = doomed.owner_id
        RETURNING process.process_uid
    )
    SELECT count(*) INTO removed_processes FROM removed;

    SELECT count(*) INTO removed_bindings
    FROM process.package_binding AS binding
    WHERE binding.owner_id = actor
      AND binding.package_hash = target.package_hash
      AND NOT EXISTS (
          SELECT 1 FROM process.process AS process
          WHERE process.process_uid = binding.process_uid
      );

    -- Delete private data spaces for the removed closure.
    WITH removed_closure AS (
        SELECT DISTINCT member.package_hash
        FROM package.installation_member AS member
        WHERE member.owner_id = actor
          AND member.installation_id IN (
              SELECT root.installation_id
              FROM package.installation_root AS root
              WHERE root.owner_id = actor
                AND (root.root_package_hash = target.package_hash OR p_force)
                AND EXISTS (
                    SELECT 1 FROM package.installation_member AS target_member
                    WHERE target_member.installation_id = root.installation_id
                      AND target_member.package_hash = target.package_hash
                )
          )
    ), orphan_spaces AS (
        SELECT space.space_id, space.package_hash
        FROM package.data_space AS space
        WHERE space.owner_id = actor
          AND space.package_hash IN (SELECT package_hash FROM removed_closure)
          AND NOT EXISTS (
              SELECT 1 FROM package.installation_member AS kept
              WHERE kept.owner_id = actor
                AND kept.package_hash = space.package_hash
                AND kept.installation_id NOT IN (
                    SELECT root.installation_id
                    FROM package.installation_root AS root
                    WHERE root.owner_id = actor
                      AND (root.root_package_hash = target.package_hash OR p_force)
                      AND EXISTS (
                          SELECT 1 FROM package.installation_member AS target_member
                          WHERE target_member.installation_id = root.installation_id
                            AND target_member.package_hash = target.package_hash
                      )
                )
          )
    ), counted_entries AS (
        SELECT count(*) AS entry_count FROM package.data_entry AS entry
        WHERE entry.space_id IN (SELECT space_id FROM orphan_spaces)
    ), deleted_entries AS (
        DELETE FROM package.data_entry AS entry
        WHERE entry.space_id IN (SELECT space_id FROM orphan_spaces)
        RETURNING 1
    ), deleted_spaces AS (
        DELETE FROM package.data_space AS space USING orphan_spaces
        WHERE space.space_id = orphan_spaces.space_id
        RETURNING space.space_id
    )
    SELECT (SELECT count(*) FROM deleted_entries),
           (SELECT count(*) FROM deleted_spaces)
    INTO removed_entries, removed_spaces;

    -- Delete managed VFS artifacts (market caches and registered package data)
    -- that belong to the removed closure, and count them.
    WITH removed_closure AS (
        SELECT DISTINCT member.package_hash
        FROM package.installation_member AS member
        WHERE member.owner_id = actor
          AND member.installation_id IN (
              SELECT root.installation_id
              FROM package.installation_root AS root
              WHERE root.owner_id = actor
                AND (root.root_package_hash = target.package_hash OR p_force)
                AND EXISTS (
                    SELECT 1 FROM package.installation_member AS target_member
                    WHERE target_member.installation_id = root.installation_id
                      AND target_member.package_hash = target.package_hash
                )
          )
    ), doomed_nodes AS (
        SELECT managed.node_id
        FROM package.managed_node AS managed
        WHERE managed.owner_id = actor
          AND managed.package_hash IN (SELECT package_hash FROM removed_closure)
    ), deleted_nodes AS (
        DELETE FROM vfs.node AS node USING doomed_nodes
        WHERE node.node_id = doomed_nodes.node_id
        RETURNING 1
    )
    SELECT count(*) INTO cache_files_removed FROM deleted_nodes;

    -- Test-only fault injection for atomic rollback verification. The setting
    -- is transaction-local and inert in production.
    IF current_setting('app.cilexec_test_fail', true) = 'uninstall_after_data' THEN
        RAISE EXCEPTION 'injected uninstall failure' USING ERRCODE = 'P0001';
    END IF;

    -- Remove installation roots and their member closures.
    WITH doomed_roots AS (
        SELECT root.installation_id
        FROM package.installation_root AS root
        WHERE root.owner_id = actor
          AND (root.root_package_hash = target.package_hash OR p_force)
          AND EXISTS (
              SELECT 1 FROM package.installation_member AS target_member
              WHERE target_member.installation_id = root.installation_id
                AND target_member.package_hash = target.package_hash
          )
        ORDER BY root.installation_id
        FOR UPDATE OF root
    ), deleted_members AS (
        DELETE FROM package.installation_member AS member USING doomed_roots
        WHERE member.installation_id = doomed_roots.installation_id
        RETURNING 1
    ), deleted_roots AS (
        DELETE FROM package.installation_root AS root USING doomed_roots
        WHERE root.installation_id = doomed_roots.installation_id
        RETURNING 1
    )
    SELECT (SELECT count(*) FROM deleted_members),
           (SELECT count(*) FROM deleted_roots)
    INTO removed_members, roots;

    -- Controlled global release payload GC for fully unreferenced releases.
    -- Transaction-local GC authorization is active only while purging; the
    -- immutable-mutation triggers still reject ordinary UPDATE/DELETE.
    PERFORM set_config('app.cilexec_gc', 'on', true);
    WITH purge_candidates AS (
        SELECT release.package_hash, release.database_object_hash
        FROM package.release AS release
        WHERE release.package_hash = target.package_hash
           OR EXISTS (
               SELECT 1 FROM package.installation_member AS gone_member
               WHERE gone_member.package_hash = release.package_hash
                 AND gone_member.owner_id = actor
           )
    ), unreferenced AS (
        SELECT candidate.package_hash, candidate.database_object_hash
        FROM purge_candidates AS candidate
        WHERE NOT EXISTS (
            SELECT 1 FROM package.installation_member AS member
            WHERE member.package_hash = candidate.package_hash
        )
          AND NOT EXISTS (
            SELECT 1 FROM process.package_binding AS binding
            WHERE binding.package_hash = candidate.package_hash
        )
          AND NOT EXISTS (
            SELECT 1 FROM package.data_space AS space
            WHERE space.package_hash = candidate.package_hash
        )
          AND NOT EXISTS (
            SELECT 1 FROM package.release_dependency AS dependency
            JOIN package.release AS keeper
                ON keeper.package_hash = dependency.package_hash
            WHERE dependency.dependency_file_hash = (
                SELECT retained.database_file_hash
                FROM package.release AS retained
                WHERE retained.package_hash = candidate.package_hash
            )
        )
    ), gced_entries AS (
        DELETE FROM package.release_capability WHERE package_hash IN
            (SELECT package_hash FROM unreferenced)
    ), gced_exports AS (
        DELETE FROM package.release_export WHERE package_hash IN
            (SELECT package_hash FROM unreferenced)
    ), gced_entrypoints AS (
        DELETE FROM package.release_entrypoint WHERE package_hash IN
            (SELECT package_hash FROM unreferenced)
    ), gced_modules AS (
        DELETE FROM package.release_module WHERE package_hash IN
            (SELECT package_hash FROM unreferenced)
    ), gced_dependencies AS (
        DELETE FROM package.release_dependency WHERE package_hash IN
            (SELECT package_hash FROM unreferenced)
    ), gced_releases AS (
        DELETE FROM package.release AS release USING unreferenced
        WHERE release.package_hash = unreferenced.package_hash
        RETURNING release.package_hash
    ), gced_objects AS (
        DELETE FROM object_store.object AS stored USING unreferenced
        WHERE stored.object_hash = unreferenced.database_object_hash
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
        RETURNING stored.object_hash
    )
    SELECT (SELECT count(*) FROM gced_releases),
           (SELECT count(*) FROM gced_objects)
    INTO purged_releases, purged_objects;
    PERFORM set_config('app.cilexec_gc', 'off', true);

    RETURN jsonb_build_object(
        'removed', true,
        'packagesRemoved', roots,
        'dependenciesRemoved', GREATEST(0, removed_members - roots),
        'processesRemoved', removed_processes,
        'bindingsRemoved', removed_bindings,
        'cacheFilesRemoved', cache_files_removed,
        'dataNodesRemoved', removed_entries + removed_spaces,
        'releasesPurged', purged_releases,
        'objectsPurged', purged_objects
    );
END
$function$;
REVOKE ALL ON FUNCTION package.uninstall_package_as(
    name, text, bytea, boolean, uuid
) FROM PUBLIC;

CREATE FUNCTION package.uninstall_package(
    p_file_sha256 bytea,
    p_force boolean,
    p_caller_process_uid uuid
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.uninstall_package_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_force, p_caller_process_uid
    )
$function$;
REVOKE ALL ON FUNCTION package.uninstall_package(bytea, boolean, uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.uninstall_package_as(name, text, bytea, boolean, uuid)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.uninstall_package(bytea, boolean, uuid)
    TO cilexec_runtime;

-- ============================================================================
-- Component: private package data
-- ============================================================================

CREATE FUNCTION package.data_usage_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    effective_quota bigint;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    SELECT COALESCE(
        (SELECT override.quota_bytes FROM package.data_quota_override AS override
         WHERE override.owner_id = actor AND override.package_hash = space.package_hash),
        (SELECT policy.default_quota_bytes FROM package.data_policy AS policy
         WHERE policy.singleton)
    ) INTO effective_quota;
    RETURN jsonb_build_object(
        'spaceId', space.space_id::text,
        'packageHash', encode(space.package_hash, 'hex'),
        'databaseFileSha256', encode(space.database_file_hash, 'hex'),
        'logicalBytes', space.logical_bytes,
        'quota', effective_quota,
        'files', (SELECT count(*) FROM package.data_entry AS entry
                  WHERE entry.space_id = space.space_id AND entry.entry_type = 'FILE'),
        'updatedAt', space.updated_at
    );
END
$function$;
REVOKE ALL ON FUNCTION package.data_usage_as(name, text, bytea) FROM PUBLIC;

CREATE FUNCTION package.data_usage(p_file_sha256 bytea)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_usage_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256
    )
$function$;
REVOKE ALL ON FUNCTION package.data_usage(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_usage_as(name, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_usage(bytea) TO cilexec_runtime;

CREATE FUNCTION package.data_write_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_path text,
    p_content bytea,
    p_media_type text,
    p_expected_version bigint
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    existing package.data_entry%ROWTYPE;
    new_hash bytea;
    old_bytes bigint := 0;
    new_usage bigint;
    effective_quota bigint;
    next_version bigint;
    result jsonb;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    IF p_path IS NULL OR p_path = '' OR left(p_path, 1) = '/' OR right(p_path, 1) = '/'
       OR position(chr(92) IN p_path) <> 0 OR p_path ~ '[[:cntrl:]]'
       OR p_path ~ '(^|/)(\.|\.\.)(/|$)' OR char_length(p_path) > 1024 THEN
        RAISE EXCEPTION 'invalid package data path' USING ERRCODE = '22000';
    END IF;
    IF octet_length(p_content) > 268435456 THEN
        RAISE EXCEPTION 'package data file exceeds the 256 MiB limit' USING ERRCODE = '22000';
    END IF;

    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;

    SELECT * INTO existing
    FROM package.data_entry
    WHERE space_id = space.space_id AND relative_path = p_path
    FOR UPDATE;

    IF p_expected_version >= 0 THEN
        IF NOT FOUND THEN
            RAISE EXCEPTION 'package data entry vanished during write' USING ERRCODE = '55006';
        END IF;
        IF existing.state_version <> p_expected_version THEN
            RAISE EXCEPTION 'package data entry changed concurrently' USING ERRCODE = '55006';
        END IF;
    ELSIF FOUND THEN
        RAISE EXCEPTION 'package data entry already exists' USING ERRCODE = '55006';
    END IF;

    IF FOUND THEN
        IF existing.entry_type <> 'FILE' THEN
            RAISE EXCEPTION 'package data path is a directory' USING ERRCODE = '55006';
        END IF;
        old_bytes := existing.byte_size;
    END IF;

    new_hash := pg_catalog.sha256(p_content);
    new_usage := space.logical_bytes - old_bytes + octet_length(p_content);
    SELECT COALESCE(
        (SELECT override.quota_bytes FROM package.data_quota_override AS override
         WHERE override.owner_id = actor AND override.package_hash = space.package_hash),
        (SELECT policy.default_quota_bytes FROM package.data_policy AS policy
         WHERE policy.singleton)
    ) INTO effective_quota;
    IF new_usage > effective_quota THEN
        RAISE EXCEPTION 'package data quota exceeded: % of % bytes',
            new_usage, effective_quota USING ERRCODE = '55006';
    END IF;

    INSERT INTO object_store.object(
        object_hash, byte_size, media_type, content, created_by, created_at
    ) VALUES (
        new_hash, octet_length(p_content),
        COALESCE(NULLIF(p_media_type, ''), 'application/octet-stream'),
        p_content, actor, clock_timestamp()
    ) ON CONFLICT (object_hash) DO NOTHING;

    IF p_expected_version >= 0 THEN
        next_version := existing.state_version + 1;
        UPDATE package.data_entry
        SET object_hash = new_hash,
            byte_size = octet_length(p_content),
            state_version = next_version,
            updated_at = clock_timestamp()
        WHERE space_id = space.space_id AND relative_path = p_path;
    ELSE
        INSERT INTO package.data_entry(
            space_id, relative_path, entry_type, object_hash, byte_size,
            state_version, created_at, updated_at
        ) VALUES (
            space.space_id, p_path, 'FILE', new_hash, octet_length(p_content),
            1, clock_timestamp(), clock_timestamp()
        );
        next_version := 1;
    END IF;

    UPDATE package.data_space
    SET logical_bytes = new_usage, updated_at = clock_timestamp()
    WHERE space_id = space.space_id;

    result := jsonb_build_object(
        'ok', true, 'version', next_version, 'bytes', octet_length(p_content),
        'logicalBytes', new_usage, 'quota', effective_quota
    );
    RETURN result;
END
$function$;
REVOKE ALL ON FUNCTION package.data_write_as(
    name, text, bytea, text, bytea, text, bigint
) FROM PUBLIC;

CREATE FUNCTION package.data_write(
    p_file_sha256 bytea, p_path text, p_content bytea, p_media_type text,
    p_expected_version bigint
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_write_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_path, p_content, p_media_type, p_expected_version
    )
$function$;
REVOKE ALL ON FUNCTION package.data_write(bytea, text, bytea, text, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_write_as(name, text, bytea, text, bytea, text, bigint)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_write(bytea, text, bytea, text, bigint)
    TO cilexec_runtime;

CREATE FUNCTION package.data_append_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_path text,
    p_content bytea,
    p_expected_version bigint
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    existing package.data_entry%ROWTYPE;
    combined bytea;
    result jsonb;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    SELECT * INTO existing
    FROM package.data_entry
    WHERE space_id = space.space_id AND relative_path = p_path
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data entry does not exist' USING ERRCODE = '55006';
    END IF;
    IF existing.entry_type <> 'FILE' THEN
        RAISE EXCEPTION 'package data path is a directory' USING ERRCODE = '55006';
    END IF;
    IF existing.state_version <> p_expected_version THEN
        RAISE EXCEPTION 'package data entry changed concurrently' USING ERRCODE = '55006';
    END IF;
    SELECT stored.content INTO combined
    FROM object_store.object AS stored
    WHERE stored.object_hash = existing.object_hash;
    IF octet_length(combined) + octet_length(p_content) > 268435456 THEN
        RAISE EXCEPTION 'package data file exceeds the 256 MiB limit' USING ERRCODE = '22000';
    END IF;
    result := package.data_write_as(
        p_database_role, p_claim, p_file_sha256, p_path,
        combined || p_content, 'application/octet-stream', existing.state_version
    );
    RETURN result;
END
$function$;
REVOKE ALL ON FUNCTION package.data_append_as(name, text, bytea, text, bytea, bigint)
    FROM PUBLIC;

CREATE FUNCTION package.data_append(
    p_file_sha256 bytea, p_path text, p_content bytea, p_expected_version bigint
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_append_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_path, p_content, p_expected_version
    )
$function$;
REVOKE ALL ON FUNCTION package.data_append(bytea, text, bytea, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_append_as(name, text, bytea, text, bytea, bigint)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_append(bytea, text, bytea, bigint)
    TO cilexec_runtime;

CREATE FUNCTION package.data_read_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_path text
)
RETURNS bytea
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store, package
AS $function$
DECLARE
    actor uuid;
    content bytea;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    SELECT stored.content INTO content
    FROM package.data_space AS space
    JOIN package.data_entry AS entry USING (space_id)
    JOIN object_store.object AS stored ON stored.object_hash = entry.object_hash
    WHERE space.owner_id = actor
      AND space.database_file_hash = p_file_sha256
      AND entry.relative_path = p_path
      AND entry.entry_type = 'FILE'
    LIMIT 1;
    RETURN content;
END
$function$;
REVOKE ALL ON FUNCTION package.data_read_as(name, text, bytea, text) FROM PUBLIC;

CREATE FUNCTION package.data_read(p_file_sha256 bytea, p_path text)
RETURNS bytea
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_read_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_path
    )
$function$;
REVOKE ALL ON FUNCTION package.data_read(bytea, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_read_as(name, text, bytea, text) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_read(bytea, text) TO cilexec_runtime;

CREATE FUNCTION package.data_list_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_path text
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    prefix text;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    IF p_path IS NULL OR p_path = '' THEN
        prefix := '';
    ELSIF p_path = '.' THEN
        prefix := '';
    ELSE
        IF left(p_path, 1) = '/' OR right(p_path, 1) = '/' OR p_path ~ '(^|/)(\.|\.\.)(/|$)'
           OR position(chr(92) IN p_path) <> 0 THEN
            RAISE EXCEPTION 'invalid package data path' USING ERRCODE = '22000';
        END IF;
        prefix := p_path || '/';
    END IF;
    RETURN (
        SELECT COALESCE(jsonb_agg(
            jsonb_build_object(
                'name', CASE WHEN prefix = '' THEN entry.relative_path
                             ELSE substr(entry.relative_path, char_length(prefix) + 1) END,
                'type', entry.entry_type,
                'size', entry.byte_size,
                'version', entry.state_version
            ) ORDER BY entry.relative_path), '[]'::jsonb)
        FROM package.data_entry AS entry
        WHERE entry.space_id = space.space_id
          AND (prefix = '' OR entry.relative_path LIKE prefix || '%')
          AND CASE WHEN prefix = '' THEN strpos(entry.relative_path, '/') = 0
                   ELSE strpos(substr(entry.relative_path, char_length(prefix) + 1), '/') = 0
              END
    );
END
$function$;
REVOKE ALL ON FUNCTION package.data_list_as(name, text, bytea, text) FROM PUBLIC;

CREATE FUNCTION package.data_list(p_file_sha256 bytea, p_path text)
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_list_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_path
    )
$function$;
REVOKE ALL ON FUNCTION package.data_list(bytea, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_list_as(name, text, bytea, text) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_list(bytea, text) TO cilexec_runtime;

CREATE FUNCTION package.data_remove_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_path text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    existing package.data_entry%ROWTYPE;
    children integer;
    freed bigint;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    SELECT * INTO existing
    FROM package.data_entry
    WHERE space_id = space.space_id AND relative_path = p_path
    FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('removed', false);
    END IF;
    SELECT count(*) INTO children
    FROM package.data_entry AS entry
    WHERE entry.space_id = space.space_id
      AND entry.relative_path LIKE p_path || '/%';
    IF children > 0 THEN
        RAISE EXCEPTION 'package data path is not empty' USING ERRCODE = '55006';
    END IF;
    freed := existing.byte_size;
    DELETE FROM package.data_entry
    WHERE space_id = space.space_id AND relative_path = p_path;
    UPDATE package.data_space
    SET logical_bytes = GREATEST(0, logical_bytes - freed),
        updated_at = clock_timestamp()
    WHERE space_id = space.space_id;
    RETURN jsonb_build_object('removed', true, 'freed', freed);
END
$function$;
REVOKE ALL ON FUNCTION package.data_remove_as(name, text, bytea, text) FROM PUBLIC;

CREATE FUNCTION package.data_remove(p_file_sha256 bytea, p_path text)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_remove_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_path
    )
$function$;
REVOKE ALL ON FUNCTION package.data_remove(bytea, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_remove_as(name, text, bytea, text) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_remove(bytea, text) TO cilexec_runtime;

CREATE FUNCTION package.data_mkdir_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_path text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    IF p_path IS NULL OR p_path = '' OR p_path = '.' OR left(p_path, 1) = '/'
       OR right(p_path, 1) = '/' OR position(chr(92) IN p_path) <> 0
       OR p_path ~ '[[:cntrl:]]' OR p_path ~ '(^|/)(\.|\.\.)(/|$)'
       OR char_length(p_path) > 1024 THEN
        RAISE EXCEPTION 'invalid package data path' USING ERRCODE = '22000';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    INSERT INTO package.data_entry(
        space_id, relative_path, entry_type, object_hash, byte_size,
        state_version, created_at, updated_at
    ) VALUES (
        space.space_id, p_path, 'DIRECTORY', NULL, 0, 0, clock_timestamp(), clock_timestamp()
    ) ON CONFLICT (space_id, relative_path) DO NOTHING;
    RETURN jsonb_build_object('ok', true);
END
$function$;
REVOKE ALL ON FUNCTION package.data_mkdir_as(name, text, bytea, text) FROM PUBLIC;

CREATE FUNCTION package.data_mkdir(p_file_sha256 bytea, p_path text)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_mkdir_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_path
    )
$function$;
REVOKE ALL ON FUNCTION package.data_mkdir(bytea, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_mkdir_as(name, text, bytea, text) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_mkdir(bytea, text) TO cilexec_runtime;

CREATE FUNCTION package.data_rename_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea,
    p_from text,
    p_to text
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    target_exists boolean;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    IF p_to IS NULL OR p_to = '' OR left(p_to, 1) = '/' OR right(p_to, 1) = '/'
       OR position(chr(92) IN p_to) <> 0 OR p_to ~ '[[:cntrl:]]'
       OR p_to ~ '(^|/)(\.|\.\.)(/|$)' OR char_length(p_to) > 1024 THEN
        RAISE EXCEPTION 'invalid package data path' USING ERRCODE = '22000';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM package.data_entry
        WHERE space_id = space.space_id AND relative_path = p_from
    ) THEN
        RAISE EXCEPTION 'package data source does not exist' USING ERRCODE = '55006';
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM package.data_entry
        WHERE space_id = space.space_id AND relative_path = p_to
    ) INTO target_exists;
    IF target_exists THEN
        RAISE EXCEPTION 'package data destination already exists' USING ERRCODE = '55006';
    END IF;
    UPDATE package.data_entry
    SET relative_path = p_to, updated_at = clock_timestamp()
    WHERE space_id = space.space_id AND relative_path = p_from;
    RETURN jsonb_build_object('ok', true);
END
$function$;
REVOKE ALL ON FUNCTION package.data_rename_as(name, text, bytea, text, text) FROM PUBLIC;

CREATE FUNCTION package.data_rename(p_file_sha256 bytea, p_from text, p_to text)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_rename_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256, p_from, p_to
    )
$function$;
REVOKE ALL ON FUNCTION package.data_rename(bytea, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_rename_as(name, text, bytea, text, text)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_rename(bytea, text, text) TO cilexec_runtime;

CREATE FUNCTION package.data_clear_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    space package.data_space%ROWTYPE;
    deleted_entries integer;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM auth.effective_capabilities_as(p_database_role, p_claim, actor) AS capability
        WHERE capability.capability_key = 'package_bind'
    ) THEN
        RAISE EXCEPTION 'package_bind capability is required' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO space
    FROM package.data_space
    WHERE owner_id = actor AND database_file_hash = p_file_sha256
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'package data space is missing; the package is not installed'
            USING ERRCODE = '55006';
    END IF;
    DELETE FROM package.data_entry WHERE space_id = space.space_id;
    GET DIAGNOSTICS deleted_entries = ROW_COUNT;
    UPDATE package.data_space
    SET logical_bytes = 0, updated_at = clock_timestamp()
    WHERE space_id = space.space_id;
    RETURN jsonb_build_object('ok', true, 'entriesRemoved', deleted_entries);
END
$function$;
REVOKE ALL ON FUNCTION package.data_clear_as(name, text, bytea) FROM PUBLIC;

CREATE FUNCTION package.data_clear(p_file_sha256 bytea)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_clear_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256
    )
$function$;
REVOKE ALL ON FUNCTION package.data_clear(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_clear_as(name, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_clear(bytea) TO cilexec_runtime;

-- ============================================================================
-- Component: quota administration
-- ============================================================================

CREATE FUNCTION package.data_quota_as(
    p_database_role name,
    p_claim text,
    p_file_sha256 bytea
)
RETURNS bigint
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    usage jsonb;
BEGIN
    usage := package.data_usage_as(p_database_role, p_claim, p_file_sha256);
    RETURN (usage->>'quota')::bigint;
END
$function$;
REVOKE ALL ON FUNCTION package.data_quota_as(name, text, bytea) FROM PUBLIC;

CREATE FUNCTION package.data_quota(p_file_sha256 bytea)
RETURNS bigint
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.data_quota_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        p_file_sha256
    )
$function$;
REVOKE ALL ON FUNCTION package.data_quota(bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.data_quota_as(name, text, bytea) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.data_quota(bytea) TO cilexec_runtime;

CREATE FUNCTION package.set_data_quota_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user uuid,
    p_file_sha256 bytea,
    p_quota_bytes bigint
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    target_space package.data_space%ROWTYPE;
BEGIN
    actor := auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id);
    IF p_quota_bytes IS NULL OR p_quota_bytes < 0 THEN
        RAISE EXCEPTION 'quota must not be negative' USING ERRCODE = '22000';
    END IF;
    SELECT * INTO target_space
    FROM package.data_space
    WHERE owner_id = p_target_user AND database_file_hash = p_file_sha256;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'target package data space does not exist' USING ERRCODE = '55006';
    END IF;
    IF p_quota_bytes < target_space.logical_bytes THEN
        RAISE EXCEPTION 'quota cannot be below current usage' USING ERRCODE = '55006';
    END IF;
    INSERT INTO package.data_quota_override(
        owner_id, package_hash, quota_bytes, updated_by, updated_at
    ) VALUES (
        p_target_user, target_space.package_hash, p_quota_bytes, actor, clock_timestamp()
    ) ON CONFLICT (owner_id, package_hash) DO UPDATE
    SET quota_bytes = EXCLUDED.quota_bytes,
        updated_by = EXCLUDED.updated_by,
        updated_at = EXCLUDED.updated_at;
    RETURN jsonb_build_object('ok', true, 'quota', p_quota_bytes);
END
$function$;
REVOKE ALL ON FUNCTION package.set_data_quota_as(
    name, text, uuid, uuid, bytea, bigint
) FROM PUBLIC;

CREATE FUNCTION package.set_data_quota(
    p_target_user uuid, p_file_sha256 bytea, p_quota_bytes bigint
)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.set_data_quota_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        NULLIF(current_setting('app.cilexec_user_id', true), '')::uuid,
        p_target_user, p_file_sha256, p_quota_bytes
    )
$function$;
REVOKE ALL ON FUNCTION package.set_data_quota(uuid, bytea, bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.set_data_quota_as(name, text, uuid, uuid, bytea, bigint)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.set_data_quota(uuid, bytea, bigint)
    TO cilexec_runtime;

CREATE FUNCTION package.clear_data_quota_as(
    p_database_role name,
    p_claim text,
    p_administrator_id uuid,
    p_target_user uuid,
    p_file_sha256 bytea
)
RETURNS jsonb
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, auth, package
AS $function$
DECLARE
    actor uuid;
    target_space package.data_space%ROWTYPE;
BEGIN
    actor := auth.require_system_administrator_as(
        p_database_role, p_claim, p_administrator_id);
    SELECT * INTO target_space
    FROM package.data_space
    WHERE owner_id = p_target_user AND database_file_hash = p_file_sha256;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'target package data space does not exist' USING ERRCODE = '55006';
    END IF;
    DELETE FROM package.data_quota_override
    WHERE owner_id = p_target_user AND package_hash = target_space.package_hash;
    RETURN jsonb_build_object('ok', true);
END
$function$;
REVOKE ALL ON FUNCTION package.clear_data_quota_as(name, text, uuid, uuid, bytea)
    FROM PUBLIC;

CREATE FUNCTION package.clear_data_quota(p_target_user uuid, p_file_sha256 bytea)
RETURNS jsonb
LANGUAGE sql
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.clear_data_quota_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), ''),
        NULLIF(current_setting('app.cilexec_user_id', true), '')::uuid,
        p_target_user, p_file_sha256
    )
$function$;
REVOKE ALL ON FUNCTION package.clear_data_quota(uuid, bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.clear_data_quota_as(name, text, uuid, uuid, bytea)
    TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.clear_data_quota(uuid, bytea)
    TO cilexec_runtime;

-- ============================================================================
-- Component: administrator recovery report
-- ============================================================================

CREATE FUNCTION package.recover_report_as(
    p_database_role name,
    p_claim text
)
RETURNS jsonb
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, process, package
AS $function$
DECLARE
    actor uuid;
    issues jsonb := '[]'::jsonb;
    item record;
BEGIN
    actor := auth.resolve_cilexec_user_id(p_database_role, p_claim);
    IF actor IS NULL THEN
        RAISE EXCEPTION 'a verified CilExec user identity is required' USING ERRCODE = '42501';
    END IF;
    PERFORM auth.require_system_administrator_as(p_database_role, p_claim, actor);

    FOR item IN
        SELECT space.space_id::text AS space_id,
               space.logical_bytes AS logical_bytes,
               COALESCE(SUM(entry.byte_size) FILTER (WHERE entry.entry_type = 'FILE'), 0)
                   AS actual_bytes
        FROM package.data_space AS space
        LEFT JOIN package.data_entry AS entry USING (space_id)
        GROUP BY space.space_id, space.logical_bytes
        HAVING space.logical_bytes <> COALESCE(
            SUM(entry.byte_size) FILTER (WHERE entry.entry_type = 'FILE'), 0)
    LOOP
        issues := issues || jsonb_build_object(
            'kind', 'data_usage_mismatch',
            'spaceId', item.space_id,
            'logicalBytes', item.logical_bytes,
            'actualBytes', item.actual_bytes);
    END LOOP;

    FOR item IN
        SELECT member.installation_id::text AS installation_id,
               encode(member.package_hash, 'hex') AS package_hash
        FROM package.installation_member AS member
        LEFT JOIN package.release AS release ON release.package_hash = member.package_hash
        WHERE release.package_hash IS NULL
    LOOP
        issues := issues || jsonb_build_object(
            'kind', 'installation_missing_release',
            'installationId', item.installation_id,
            'packageHash', item.package_hash);
    END LOOP;

    FOR item IN
        SELECT binding.process_uid::text AS process_uid,
               encode(binding.package_hash, 'hex') AS package_hash
        FROM process.package_binding AS binding
        WHERE NOT EXISTS (
            SELECT 1 FROM package.installation_member AS member
            WHERE member.owner_id = binding.owner_id
              AND member.package_hash = binding.package_hash
        )
    LOOP
        issues := issues || jsonb_build_object(
            'kind', 'binding_without_installation',
            'processUid', item.process_uid,
            'packageHash', item.package_hash);
    END LOOP;

    FOR item IN
        SELECT space.space_id::text AS space_id,
               encode(space.package_hash, 'hex') AS package_hash
        FROM package.data_space AS space
        WHERE NOT EXISTS (
            SELECT 1 FROM package.installation_member AS member
            WHERE member.owner_id = space.owner_id
              AND member.package_hash = space.package_hash
        )
    LOOP
        issues := issues || jsonb_build_object(
            'kind', 'space_without_installation',
            'spaceId', item.space_id,
            'packageHash', item.package_hash);
    END LOOP;

    RETURN jsonb_build_object('ok', issues = '[]'::jsonb, 'issues', issues);
END
$function$;
REVOKE ALL ON FUNCTION package.recover_report_as(name, text) FROM PUBLIC;

CREATE FUNCTION package.recover_report()
RETURNS jsonb
LANGUAGE sql
STABLE
SECURITY INVOKER
SET search_path = pg_catalog, package
AS $function$
    SELECT package.recover_report_as(
        current_user::name,
        NULLIF(current_setting('app.cilexec_user_id', true), '')
    )
$function$;
REVOKE ALL ON FUNCTION package.recover_report() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION package.recover_report_as(name, text) TO cilexec_runtime;
GRANT EXECUTE ON FUNCTION package.recover_report() TO cilexec_runtime;

-- ============================================================================
-- Component: per-user provisioning grants (forward replacement)
-- ============================================================================

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

    EXECUTE format('GRANT USAGE ON SCHEMA auth, object_store, program, process, scheduler, ipc, vfs, package, effect, terminal, audit TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.current_cilexec_user_id() TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.resolve_cilexec_user_id(name, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.visible_username(uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.resolve_visible_username(name, text, uuid) TO %I', mapped_role);
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
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.register_release_bundle(bytea, text, text, text, bytea, bytea, integer, jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.register_release_bundle_as(name, text, bytea, text, text, text, bytea, bytea, integer, jsonb, jsonb, jsonb, jsonb, jsonb, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.publish_installation(uuid, bytea, text, jsonb, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.publish_installation_as(name, text, uuid, bytea, text, jsonb, timestamptz) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.installed_release(bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.installed_release_as(name, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.list_user_installations() TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.list_user_installations_as(name, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.uninstall_package(bytea, boolean, uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.uninstall_package_as(name, text, bytea, boolean, uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_usage(bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_usage_as(name, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_write(bytea, text, bytea, text, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_write_as(name, text, bytea, text, bytea, text, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_append(bytea, text, bytea, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_append_as(name, text, bytea, text, bytea, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_read(bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_read_as(name, text, bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_list(bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_list_as(name, text, bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_remove(bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_remove_as(name, text, bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_mkdir(bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_mkdir_as(name, text, bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_rename(bytea, text, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_rename_as(name, text, bytea, text, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_clear(bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_clear_as(name, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_quota(bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.data_quota_as(name, text, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.set_data_quota(uuid, bytea, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.set_data_quota_as(name, text, uuid, uuid, bytea, bigint) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.clear_data_quota(uuid, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.clear_data_quota_as(name, text, uuid, uuid, bytea) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.register_managed_node(uuid, bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.register_managed_node_as(name, text, uuid, bytea, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.recover_report() TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION package.recover_report_as(name, text) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.effective_capabilities(uuid) TO %I', mapped_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION auth.effective_capabilities_as(name, text, uuid) TO %I', mapped_role);

    EXECUTE format('GRANT SELECT ON auth.capability TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON auth.group_account, auth.group_member TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON auth.user_capability, auth.group_capability TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON auth.environment_variable, auth.shared_environment_variable, auth.shared_environment_policy TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON program.program, program.statement, program.module_binding TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON process.process, process.call_frame, process.scope, process.variable, process.exception_frame, process.wait_state, process.relationship TO %I', mapped_role);
    EXECUTE format('GRANT DELETE ON process.call_frame, process.scope, process.variable, process.exception_frame, process.wait_state, process.relationship TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON process.event TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON process.timer, scheduler.queue TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON process.package_binding TO %I', mapped_role);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE process.pid_sequence TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ipc.channel, ipc.topic, ipc.subscription, ipc.message, ipc.delivery TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON vfs.node TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON vfs.file_revision TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON vfs.mount TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON package.release TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON package.release_dependency, package.release_module, package.release_entrypoint, package.release_export, package.release_capability TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON package.release_identity TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON package.installation_root, package.installation_member TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON package.data_space, package.data_entry TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON package.data_quota_override TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON package.managed_node TO %I', mapped_role);
    EXECUTE format('GRANT SELECT ON package.data_policy TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, DELETE ON effect.effect TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON terminal.session, terminal.input, terminal.attachment TO %I', mapped_role);
    EXECUTE format('GRANT SELECT, INSERT ON audit.event TO %I', mapped_role);
    RETURN mapped_role;
END
$function$;

RESET ROLE;
