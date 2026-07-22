SET ROLE cilexec_owner;

-- name: migration.V010.create_node
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
            AND position('/' IN node_name) = 0)),
    CHECK ((node_type IN ('FILE', 'SYMLINK') AND current_object_hash IS NOT NULL
            AND symlink_target_node_id IS NULL)
        OR (node_type IN ('DIRECTORY', 'MOUNT') AND current_object_hash IS NULL
            AND symlink_target_node_id IS NULL))
);
CREATE UNIQUE INDEX ux_vfs_node_child_name ON vfs.node(parent_node_id, node_name) WHERE parent_node_id IS NOT NULL;
CREATE UNIQUE INDEX ux_vfs_owner_root ON vfs.node(owner_id) WHERE parent_node_id IS NULL;
CREATE INDEX ix_vfs_node_object ON vfs.node(current_object_hash) WHERE current_object_hash IS NOT NULL;

-- name: migration.V010.create_file_revision
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

-- name: migration.V010.create_mount
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

-- name: migration.V010.vfs_indexes
CREATE INDEX ix_vfs_revision_history ON vfs.file_revision(node_id, revision_number DESC);
CREATE INDEX ix_vfs_mount_active ON vfs.mount(owner_id, status) WHERE status = 'ACTIVE';

-- Revision rows are append-only history, even for direct database users.
CREATE TRIGGER file_revision_reject_update_delete
BEFORE UPDATE OR DELETE ON vfs.file_revision
FOR EACH ROW EXECUTE FUNCTION meta.reject_immutable_mutation();

-- name: migration.V010.vfs_rls
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

-- name: migration.V010.vfs_grants
GRANT SELECT, INSERT, UPDATE, DELETE ON vfs.node TO cilexec_runtime;
GRANT SELECT, INSERT ON vfs.file_revision TO cilexec_runtime;
GRANT SELECT, INSERT, UPDATE ON vfs.mount TO cilexec_runtime;
GRANT SELECT ON vfs.node, vfs.file_revision, vfs.mount TO cilexec_readonly;

COMMENT ON TABLE vfs.node IS 'Mutable pathname node pointing at an immutable content object';
COMMENT ON TABLE vfs.mount IS 'Database half of a mount; Docker bind mount and capability are also mandatory';

RESET ROLE;
