SET ROLE cilexec_owner;

-- name: migration.V011.create_release
CREATE TABLE package.release (
    package_hash bytea PRIMARY KEY CHECK (octet_length(package_hash) = 32),
    namespace text NOT NULL CHECK (namespace ~ '^[A-Za-z0-9][A-Za-z0-9_.-]*$'),
    package_name text NOT NULL CHECK (package_name ~ '^[A-Za-z0-9][A-Za-z0-9_.-]*$'),
    package_version text NOT NULL CHECK (btrim(package_version) <> ''),
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
-- name: migration.V011.create_release_indexes
CREATE TABLE package.release_dependency (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    dependency_namespace text NOT NULL,
    dependency_name text NOT NULL,
    version_constraint text NOT NULL,
    optional boolean NOT NULL DEFAULT false,
    PRIMARY KEY (package_hash, dependency_namespace, dependency_name)
);

CREATE TABLE package.release_module (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    module_name text NOT NULL,
    module_object_path text NOT NULL,
    module_hash bytea NOT NULL CHECK (octet_length(module_hash) = 32),
    PRIMARY KEY (package_hash, module_name)
);

CREATE TABLE package.release_entrypoint (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    entrypoint_name text NOT NULL,
    module_name text NOT NULL,
    function_name text NOT NULL,
    PRIMARY KEY (package_hash, entrypoint_name),
    FOREIGN KEY (package_hash, module_name)
        REFERENCES package.release_module(package_hash, module_name) ON DELETE RESTRICT
);

CREATE TABLE package.release_export (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    export_name text NOT NULL,
    module_name text NOT NULL,
    symbol_name text NOT NULL,
    PRIMARY KEY (package_hash, export_name),
    FOREIGN KEY (package_hash, module_name)
        REFERENCES package.release_module(package_hash, module_name) ON DELETE RESTRICT
);

CREATE TABLE package.release_capability (
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    capability_key text NOT NULL,
    required boolean NOT NULL DEFAULT true,
    rationale text NOT NULL DEFAULT '',
    PRIMARY KEY (package_hash, capability_key)
);

CREATE TABLE package.signature (
    package_hash bytea PRIMARY KEY REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    signature_status text NOT NULL CHECK (signature_status IN (
        'UNSIGNED', 'VALID_TRUSTED', 'VALID_UNTRUSTED', 'INVALID', 'REVOKED'
    )),
    algorithm text,
    signer_identity text,
    signature_bytes bytea,
    verified_at timestamptz,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK ((signature_status = 'UNSIGNED' AND signature_bytes IS NULL)
        OR signature_status <> 'UNSIGNED')
);

-- name: migration.V011.create_environment
CREATE TABLE package.environment (
    environment_id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    environment_name text NOT NULL CHECK (btrim(environment_name) <> ''),
    parent_environment_id uuid,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (owner_id, environment_name),
    UNIQUE (environment_id, owner_id),
    FOREIGN KEY (parent_environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE RESTRICT
);

-- name: migration.V011.create_environment_binding
CREATE TABLE package.binding (
    environment_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    binding_name text NOT NULL CHECK (btrim(binding_name) <> ''),
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    bound_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    bound_by uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE RESTRICT,
    PRIMARY KEY (environment_id, binding_name),
    FOREIGN KEY (environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE CASCADE
);

-- name: migration.V011.create_data_scope
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
-- name: migration.V011.create_process_package_binding
CREATE TABLE process.package_binding (
    process_uid uuid NOT NULL,
    owner_id uuid NOT NULL,
    import_name text NOT NULL CHECK (btrim(import_name) <> ''),
    environment_id uuid NOT NULL,
    package_hash bytea NOT NULL REFERENCES package.release(package_hash) ON DELETE RESTRICT,
    resolved_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (process_uid, import_name),
    FOREIGN KEY (process_uid, owner_id)
        REFERENCES process.process(process_uid, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (environment_id, owner_id)
        REFERENCES package.environment(environment_id, owner_id) ON DELETE RESTRICT
);

-- name: migration.V011.package_indexes
CREATE INDEX ix_release_coordinate ON package.release(namespace, package_name, package_version);
CREATE INDEX ix_release_dependency_target
    ON package.release_dependency(dependency_namespace, dependency_name);
CREATE INDEX ix_binding_package ON package.binding(package_hash);
CREATE INDEX ix_process_package_hash ON process.package_binding(package_hash);

-- name: migration.V011.release_immutability
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

-- name: migration.V011.package_rls
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

-- name: migration.V011.package_grants
GRANT SELECT, INSERT ON package.release, package.release_dependency, package.release_module,
    package.release_entrypoint, package.release_export, package.release_capability,
    package.signature TO cilexec_runtime;
GRANT UPDATE ON package.signature TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON package.environment, package.binding, package.data_scope TO cilexec_runtime;
GRANT SELECT, INSERT ON process.package_binding TO cilexec_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA package TO cilexec_readonly;
GRANT SELECT ON process.package_binding TO cilexec_readonly;

COMMENT ON CONSTRAINT release_namespace_package_name_package_version_key ON package.release IS
    'A coordinate can never be associated with a different logical package hash';
COMMENT ON TABLE package.release IS
    'Immutable SQLite package identity; database bytes live only in object_store.object';

RESET ROLE;
