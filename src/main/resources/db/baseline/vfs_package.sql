-- ============================================================================
-- Component: vfs
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_node
CREATE TABLE vfs.node (
    node_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    parent_node_id uuid,
    node_name text NOT NULL,
    node_type text NOT NULL CHECK (node_type IN ('DIRECTORY', 'FILE', 'SYMLINK', 'MOUNT')),
    current_object_hash bytea REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    symlink_target_node_id uuid,
    mode_bits integer NOT NULL DEFAULT 420 CHECK (mode_bits BETWEEN 0 AND 4095),
    capability_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    state_version bigint NOT NULL DEFAULT 0 CHECK (state_version >= 0),
    revision_enabled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (node_id, owner_id),
    FOREIGN KEY (parent_node_id, owner_id) REFERENCES vfs.node(node_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (symlink_target_node_id, owner_id) REFERENCES vfs.node(node_id, owner_id) ON DELETE RESTRICT,
    CHECK ((parent_node_id IS NULL AND node_name = '/')
        OR (parent_node_id IS NOT NULL AND node_name <> '' AND node_name NOT IN ('.', '..')
            AND char_length(node_name) <= 255
            AND position('/' IN node_name) = 0
            AND node_name !~ '[[:cntrl:]]')),
    CHECK ((node_type IN ('FILE', 'SYMLINK') AND current_object_hash IS NOT NULL
            AND symlink_target_node_id IS NULL)
        OR (node_type IN ('DIRECTORY', 'MOUNT') AND current_object_hash IS NULL
            AND symlink_target_node_id IS NULL))
);
CREATE UNIQUE INDEX ux_vfs_node_child_name ON vfs.node(parent_node_id, node_name) WHERE parent_node_id IS NOT NULL;
CREATE UNIQUE INDEX ux_vfs_owner_root ON vfs.node(owner_id) WHERE parent_node_id IS NULL;
CREATE INDEX ix_vfs_node_object ON vfs.node(current_object_hash) WHERE current_object_hash IS NOT NULL;

-- name: baseline.create_file_revision
CREATE TABLE vfs.file_revision (
    revision_id uuid PRIMARY KEY,
    node_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    revision_number bigint NOT NULL CHECK (revision_number > 0),
    object_hash bytea NOT NULL REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    created_by uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (node_id, revision_number),
    FOREIGN KEY (node_id, owner_id) REFERENCES vfs.node(node_id, owner_id) ON DELETE CASCADE
);

-- name: baseline.create_mount
CREATE TABLE vfs.mount (
    mount_id uuid PRIMARY KEY,
    node_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    host_source_key text NOT NULL CHECK (
        host_source_key ~ '^[A-Za-z0-9_.-]+$' AND host_source_key NOT IN ('.', '..')
    ),
    container_path text NOT NULL CHECK (
        left(container_path, 1) = '/'
        AND container_path <> '/'
        AND right(container_path, 1) <> '/'
        AND position('//' IN container_path) = 0
        AND position(chr(92) IN container_path) = 0
        AND container_path !~ '[[:cntrl:]]'
        AND container_path !~ '(^|/)(\.|\.\.)(/|$)'
    ),
    required_capability text NOT NULL CHECK (required_capability = 'vfs_mount_host'),
    read_only boolean NOT NULL DEFAULT true,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (node_id),
    UNIQUE (host_source_key, container_path),
    FOREIGN KEY (node_id, owner_id) REFERENCES vfs.node(node_id, owner_id) ON DELETE CASCADE
);

-- name: baseline.vfs_indexes
CREATE INDEX ix_vfs_revision_history ON vfs.file_revision(node_id, revision_number DESC);
CREATE INDEX ix_vfs_mount_active ON vfs.mount(owner_id, status) WHERE status = 'ACTIVE';

-- Revision rows are append-only history, even for direct database users.
CREATE TRIGGER file_revision_reject_update_delete
BEFORE UPDATE OR DELETE ON vfs.file_revision
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: baseline.vfs_rls
DO $rls$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY['node', 'file_revision', 'mount']
    LOOP
        EXECUTE format('ALTER TABLE vfs.%I ENABLE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('ALTER TABLE vfs.%I FORCE ROW LEVEL SECURITY', relation_name);
        EXECUTE format('CREATE POLICY %I ON vfs.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON vfs.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON vfs.%I FOR SELECT TO cilexec_readonly USING (true)',
            relation_name || '_readonly_control', relation_name);
        EXECUTE format('CREATE POLICY %I ON vfs.%I TO PUBLIC USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', relation_name);
    END LOOP;
END
$rls$;

-- name: baseline.vfs_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON vfs.node TO cilexec_runtime;
GRANT SELECT, INSERT ON vfs.file_revision TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE ON vfs.mount TO cilexec_runtime;
GRANT SELECT ON vfs.node, vfs.file_revision, vfs.mount TO cilexec_readonly;

COMMENT ON TABLE vfs.node IS 'Mutable pathname node pointing at an immutable content object';
COMMENT ON TABLE vfs.mount IS 'Database half of a mount; Docker bind mount and capability are also mandatory';

RESET ROLE;

-- ============================================================================
-- Component: package
-- ============================================================================
SET ROLE cilexec_owner;

-- name: baseline.create_release
CREATE TABLE package.release (
    package_hash bytea PRIMARY KEY CHECK (octet_length(package_hash) = 32),
    namespace text NOT NULL CHECK (namespace ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    package_name text NOT NULL CHECK (package_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$'),
    package_version text NOT NULL CHECK (
        package_version ~ '^[A-Za-z0-9][A-Za-z0-9._+-]{0,127}$'),
    database_object_hash bytea NOT NULL REFERENCES object_store.object(object_hash) ON DELETE RESTRICT,
    database_file_hash bytea NOT NULL CHECK (octet_length(database_file_hash) = 32),
    package_format_version integer NOT NULL CHECK (package_format_version > 0),
    metadata_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    imported_by uuid REFERENCES auth.user_account(user_id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (namespace, package_name, package_version),
    UNIQUE (database_object_hash),
    CHECK (database_file_hash = database_object_hash)
);

-- Derived indexes are rebuildable from the authoritative SQLite bytes, but a
-- published index row is immutable.
-- name: baseline.create_release_indexes
CREATE TABLE package.release_dependency (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    dependency_file_hash bytea NOT NULL CHECK (octet_length(dependency_file_hash) = 32),
    optional boolean NOT NULL DEFAULT false,
    PRIMARY KEY (package_hash, dependency_file_hash)
);

CREATE TABLE package.release_module (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    module_name text NOT NULL CHECK (module_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    module_object_path text NOT NULL CHECK (
        char_length(module_object_path) BETWEEN 1 AND 1024
        AND left(module_object_path, 1) <> '/'
        AND right(module_object_path, 1) <> '/'
        AND position(chr(92) IN module_object_path) = 0
        AND module_object_path !~ '[[:cntrl:]]'
        AND module_object_path !~ '(^|/)(\.|\.\.)(/|$)'),
    module_hash bytea NOT NULL CHECK (octet_length(module_hash) = 32),
    PRIMARY KEY (package_hash, module_name)
);

CREATE TABLE package.release_entrypoint (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    entrypoint_name text NOT NULL CHECK (
        entrypoint_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    module_name text NOT NULL CHECK (module_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    function_name text NOT NULL CHECK (
        function_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    PRIMARY KEY (package_hash, entrypoint_name),
    FOREIGN KEY (package_hash, module_name)
        REFERENCES package.release_module(package_hash, module_name) ON DELETE RESTRICT
);

CREATE TABLE package.release_export (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    export_name text NOT NULL CHECK (export_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    module_name text NOT NULL CHECK (module_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    symbol_name text NOT NULL CHECK (symbol_name ~ '^[A-Za-z_][A-Za-z0-9_.-]{0,127}$'),
    PRIMARY KEY (package_hash, export_name),
    FOREIGN KEY (package_hash, module_name)
        REFERENCES package.release_module(package_hash, module_name) ON DELETE RESTRICT
);

CREATE TABLE package.release_capability (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    capability_key text NOT NULL CHECK (capability_key ~ '^[a-z][a-z0-9_.:-]{0,127}$'),
    required boolean NOT NULL DEFAULT true,
    rationale text NOT NULL DEFAULT '' CHECK (
        char_length(rationale) <= 4096 AND rationale !~ '[[:cntrl:]]'),
    PRIMARY KEY (package_hash, capability_key)
);

-- name: baseline.create_environment
CREATE TABLE package.environment (
    environment_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    environment_name text NOT NULL CHECK (
        char_length(environment_name) BETWEEN 1 AND 128
        AND btrim(environment_name) <> '' AND environment_name !~ '[[:cntrl:]]'),
    parent_environment_id uuid,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_id, environment_name),
    UNIQUE (environment_id, owner_id),
    FOREIGN KEY (parent_environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE RESTRICT
);

-- name: baseline.create_environment_binding
CREATE TABLE package.binding (
    environment_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    binding_name text NOT NULL CHECK (
        binding_name ~ '^[A-Za-z_][A-Za-z0-9_]{0,127}$'),
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    bound_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    bound_by uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    PRIMARY KEY (environment_id, binding_name),
    FOREIGN KEY (environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE CASCADE
);

-- name: baseline.create_data_scope
CREATE TABLE package.data_scope (
    data_scope_id uuid PRIMARY KEY,
    environment_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    root_node_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (environment_id, package_hash),
    FOREIGN KEY (environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (root_node_id, owner_id)
        REFERENCES vfs.node(node_id, owner_id) ON DELETE RESTRICT
);

-- Exact process pinning is separate from mutable environment bindings.
-- name: baseline.create_process_package_binding
CREATE TABLE process.package_binding (
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    import_name text NOT NULL CHECK (
        import_name ~ '^[A-Za-z_][A-Za-z0-9_]{0,127}$'
        OR import_name ~ '^[0-9a-f]{64}$'),
    environment_id uuid NOT NULL,
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    resolved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (process_uid, import_name),
    FOREIGN KEY (process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE RESTRICT
);

-- name: baseline.package_indexes
CREATE INDEX ix_release_coordinate ON package.release(namespace, package_name, package_version);
CREATE INDEX ix_release_dependency_target
    ON package.release_dependency(dependency_file_hash);
CREATE INDEX ix_binding_package ON package.binding(package_hash);
CREATE INDEX ix_process_package_hash ON process.package_binding(package_hash);

-- name: baseline.release_immutability
DO $immutable$
DECLARE
    relation_name text;
BEGIN
    FOREACH relation_name IN ARRAY ARRAY[
        'release', 'release_dependency', 'release_module', 'release_entrypoint',
        'release_export', 'release_capability'
    ]
    LOOP
        EXECUTE format(
            'CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON package.%I FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation()',
            relation_name || '_reject_update_delete', relation_name
        );
    END LOOP;
END
$immutable$;

-- name: baseline.package_rls
DO $rls$
DECLARE
    schema_name text;
    relation_name text;
BEGIN
    FOR schema_name, relation_name IN
        SELECT * FROM (VALUES
            ('package', 'environment'),
            ('package', 'binding'),
            ('package', 'data_scope'),
            ('process', 'package_binding')
        ) AS relations(schema_name, relation_name)
    LOOP
        EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', schema_name, relation_name);
        EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', schema_name, relation_name);
        EXECUTE format('CREATE POLICY %I ON %I.%I TO cilexec_owner USING (true) WITH CHECK (true)',
            relation_name || '_owner_control', schema_name, relation_name);
        EXECUTE format('CREATE POLICY %I ON %I.%I TO cilexec_runtime USING (true) WITH CHECK (true)',
            relation_name || '_runtime_control', schema_name, relation_name);
        EXECUTE format('CREATE POLICY %I ON %I.%I FOR SELECT TO cilexec_readonly USING (true)',
            relation_name || '_readonly_control', schema_name, relation_name);
        EXECUTE format('CREATE POLICY %I ON %I.%I TO PUBLIC USING (owner_id = auth.current_cilexec_user_id()) WITH CHECK (owner_id = auth.current_cilexec_user_id())',
            relation_name || '_principal', schema_name, relation_name);
    END LOOP;
END
$rls$;

-- name: baseline.package_grants
GRANT SELECT, INSERT ON package.release, package.release_dependency, package.release_module,
    package.release_entrypoint, package.release_export, package.release_capability
    TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON package.environment, package.binding, package.data_scope TO cilexec_runtime;
GRANT SELECT, INSERT ON process.package_binding TO cilexec_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA package TO cilexec_readonly;
GRANT SELECT ON process.package_binding TO cilexec_readonly;

COMMENT ON CONSTRAINT release_namespace_package_name_package_version_key ON package.release IS
    'A coordinate can never be associated with a different logical package hash';
COMMENT ON TABLE package.release IS
    'Immutable SQLite package identity; database bytes live only in object_store.object';

RESET ROLE;
