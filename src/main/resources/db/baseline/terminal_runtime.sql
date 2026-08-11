-- Component: suspend terminal processes
-- ============================================================================
SET ROLE cilexec_owner;

-- Older runtimes ended one process for every REPL submission. The newest active
-- attachment becomes the user's permanent suspended terminal process during upgrade.
UPDATE process.process AS process
SET status = 'PAUSED',
    state_version = process.state_version + 1,
    updated_at = clock_timestamp(),
    terminated_at = NULL,
    exit_code = NULL,
    failure_code = NULL,
    failure_message = NULL
FROM terminal.attachment AS attachment
WHERE attachment.process_uid = process.process_uid
  AND attachment.owner_id = process.owner_id
  AND attachment.status = 'ATTACHED'
  AND process.status IN ('TERMINATED', 'FAILED', 'FAILED_RECOVERY');

RESET ROLE;

-- ============================================================================
-- Component: least-privilege diagnostics and logical export
-- ============================================================================
SET ROLE cilexec_owner;

CREATE TABLE meta.security_definer_public_allowlist (
    function_signature text PRIMARY KEY CHECK (btrim(function_signature) <> ''),
    rationale text NOT NULL CHECK (btrim(rationale) <> '')
);

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('meta', 'security_definer_public_allowlist', 'SYSTEM_READONLY', NULL,
        'reviewed public execution boundary for SECURITY DEFINER functions');

INSERT INTO meta.security_definer_public_allowlist(function_signature, rationale)
VALUES
    ('auth.is_system_administrator_as(name,text)'::regprocedure::text,
     'binds the claimed administrator to the actual database invoker'),
    ('auth.require_system_administrator_as(name,text,uuid)'::regprocedure::text,
     'requires the verified matching system administrator'),
    ('auth.admin_list_users_as(name,text,uuid)'::regprocedure::text,
     'verified system administrator account listing'),
    ('auth.admin_create_user_as(name,text,uuid,uuid,text,text,text[],uuid,timestamptz)'::regprocedure::text,
     'verified atomic administrator account creation'),
    ('auth.admin_disable_user_as(name,text,uuid,uuid,uuid,timestamptz)'::regprocedure::text,
     'verified atomic administrator account disable'),
    ('vfs.require_admin_target(name,text,uuid,uuid)'::regprocedure::text,
     'verified administrator target binding'),
    ('vfs.admin_list_nodes_as(name,text,uuid,uuid,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator VFS listing'),
    ('vfs.admin_read_file_as(name,text,uuid,uuid,uuid,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator VFS read'),
    ('vfs.admin_replace_file_as(name,text,uuid,uuid,uuid,bytea,text,bytea,uuid,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator VFS replacement'),
    ('vfs.admin_create_directory_as(name,text,uuid,uuid,uuid,uuid,text,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator directory creation'),
    ('vfs.admin_create_file_as(name,text,uuid,uuid,uuid,uuid,text,bytea,text,bytea,boolean,uuid,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator file creation'),
    ('vfs.admin_rename_as(name,text,uuid,uuid,uuid,text,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator VFS rename'),
    ('vfs.admin_delete_as(name,text,uuid,uuid,uuid,uuid,timestamptz)'::regprocedure::text,
     'verified and audited administrator VFS delete'),
    ('vfs.admin_find_child_as(name,text,uuid,uuid,uuid,text)'::regprocedure::text,
     'verified administrator path traversal'),
    ('object_store.admin_object_reachable_as(name,text,uuid,uuid,bytea)'::regprocedure::text,
     'verified target-scoped administrator object reachability'),
    ('object_store.admin_logical_object_size_as(name,text,uuid,uuid,bytea)'::regprocedure::text,
     'verified target-scoped administrator object sizing'),
    ('object_store.admin_read_object_range_as(name,text,uuid,uuid,bytea,bigint,integer)'::regprocedure::text,
     'verified target-scoped bounded administrator object read'),
    ('object_store.append_chunk_manifest_as(name,text,bytea,bytea,bytea)'::regprocedure::text,
     'identity-bound immutable chunk manifest append'),
    ('object_store.logical_object_size_as(name,text,bytea)'::regprocedure::text,
     'identity-bound logical object sizing'),
    ('object_store.read_object_range_as(name,text,bytea,bigint,integer)'::regprocedure::text,
     'identity-bound bounded object read');

-- New tables are not exportable by default. Every exported relation is reviewed here;
-- credentials and volatile scheduler/runtime identities are intentionally absent.
UPDATE meta.table_security_classification AS classification
SET exportable = true
FROM (VALUES
    ('meta', 'instance'),
    ('meta', 'boot'),
    ('meta', 'table_security_classification'),
    ('meta', 'security_definer_public_allowlist'),
    ('auth', 'user_account'),
    ('auth', 'group_account'),
    ('auth', 'group_member'),
    ('auth', 'capability'),
    ('auth', 'user_capability'),
    ('auth', 'group_capability'),
    ('auth', 'environment_variable'),
    ('auth', 'shared_environment_variable'),
    ('auth', 'shared_environment_policy'),
    ('object_store', 'object'),
    ('object_store', 'chunk_manifest'),
    ('program', 'program'),
    ('program', 'statement'),
    ('program', 'module_binding'),
    ('process', 'process'),
    ('process', 'call_frame'),
    ('process', 'scope'),
    ('process', 'variable'),
    ('process', 'exception_frame'),
    ('process', 'wait_state'),
    ('process', 'relationship'),
    ('process', 'event'),
    ('process', 'timer'),
    ('process', 'package_binding'),
    ('scheduler', 'queue'),
    ('ipc', 'channel'),
    ('ipc', 'topic'),
    ('ipc', 'subscription'),
    ('ipc', 'message'),
    ('ipc', 'delivery'),
    ('ipc', 'swap_pool'),
    ('ipc', 'swap_value'),
    ('vfs', 'node'),
    ('vfs', 'file_revision'),
    ('vfs', 'mount'),
    ('vfs', 'node_lock'),
    ('package', 'release'),
    ('package', 'release_dependency'),
    ('package', 'release_module'),
    ('package', 'release_entrypoint'),
    ('package', 'release_export'),
    ('package', 'release_capability'),
    ('effect', 'effect'),
    ('effect', 'attempt'),
    ('terminal', 'session'),
    ('terminal', 'input'),
    ('terminal', 'attachment'),
    ('terminal', 'command_history'),
    ('audit', 'event'),
    ('audit', 'retention_policy')
) AS approved(schema_name, table_name)
WHERE classification.schema_name = approved.schema_name::name
  AND classification.table_name = approved.table_name::name;

-- The readonly role receives no application-table ACL or broad RLS policy. Views below
-- deliberately expose only redacted identities and aggregate operational state.
REVOKE USAGE ON SCHEMA auth, object_store, vfs, program, process, scheduler,
    ipc, effect, package, terminal, audit FROM cilexec_readonly;
REVOKE ALL ON ALL TABLES IN SCHEMA meta, auth, object_store, vfs, program, process,
    scheduler, ipc, effect, package, terminal, audit FROM cilexec_readonly;
GRANT USAGE ON SCHEMA meta, diagnostic TO cilexec_readonly;

CREATE VIEW diagnostic.instance_status WITH (security_barrier = true) AS
SELECT instance_id, instance_name, status, created_at, updated_at
FROM meta.instance;

CREATE VIEW diagnostic.runtime_status WITH (security_barrier = true) AS
SELECT runtime_version, fcl_runtime_format_version, status,
       started_at, last_seen_at, stopped_at
FROM meta.kernel_instance;

CREATE VIEW diagnostic.boot_status WITH (security_barrier = true) AS
SELECT boot_id, status, runtime_version, schema_version,
       fcl_runtime_format_version, started_at, recovery_completed_at,
       ready_at, ended_at, shutdown_reason
FROM meta.boot;

CREATE VIEW diagnostic.account_status WITH (security_barrier = true) AS
SELECT user_id, username, status, credential_version,
       created_at, updated_at, disabled_at
FROM auth.user_account;

CREATE VIEW diagnostic.process_status WITH (security_barrier = true) AS
SELECT status, count(*)::bigint AS process_count,
       min(created_at) AS oldest_created_at, max(updated_at) AS latest_updated_at
FROM process.process
GROUP BY status;

CREATE VIEW diagnostic.scheduler_status WITH (security_barrier = true) AS
SELECT queue_state, count(*)::bigint AS queue_count,
       min(enqueued_at) AS oldest_enqueued_at
FROM scheduler.queue
GROUP BY queue_state;

CREATE VIEW diagnostic.effect_status WITH (security_barrier = true) AS
SELECT effect_type, status, count(*)::bigint AS effect_count,
       min(prepared_at) AS oldest_prepared_at, max(updated_at) AS latest_updated_at
FROM effect.effect
GROUP BY effect_type, status;

CREATE VIEW diagnostic.storage_status WITH (security_barrier = true) AS
SELECT media_type, count(*)::bigint AS object_count,
       COALESCE(sum(byte_size), 0)::numeric AS total_bytes
FROM object_store.object
GROUP BY media_type;

CREATE VIEW diagnostic.audit_status WITH (security_barrier = true) AS
SELECT action, result, count(*)::bigint AS event_count,
       max(created_at) AS latest_event_at
FROM audit.event
GROUP BY action, result;

REVOKE ALL ON diagnostic.instance_status, diagnostic.runtime_status,
    diagnostic.boot_status, diagnostic.account_status, diagnostic.process_status,
    diagnostic.scheduler_status, diagnostic.effect_status, diagnostic.storage_status,
    diagnostic.audit_status FROM PUBLIC;
GRANT SELECT ON diagnostic.instance_status, diagnostic.runtime_status,
    diagnostic.boot_status, diagnostic.account_status, diagnostic.process_status,
    diagnostic.scheduler_status, diagnostic.effect_status, diagnostic.storage_status,
    diagnostic.audit_status TO cilexec_readonly;

-- Exporter can read only explicitly exportable tables. Forced-RLS tables receive a
-- SELECT-only policy; the role cannot assume cilexec_owner or mutate any relation.
GRANT USAGE ON SCHEMA meta, auth, object_store, vfs, program, process, scheduler,
    ipc, effect, package, terminal, audit TO cilexec_exporter;
DO $exporter_access$
DECLARE
    approved record;
BEGIN
    FOR approved IN
        SELECT schema_name::text AS schema_name,
               table_name::text AS table_name,
               classification
        FROM meta.table_security_classification
        WHERE exportable
        ORDER BY schema_name, table_name
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

REVOKE ALL ON auth.user_credential FROM cilexec_readonly, cilexec_exporter;

COMMENT ON TABLE meta.security_definer_public_allowlist IS
    'Exact reviewed SECURITY DEFINER signatures allowed to retain PUBLIC EXECUTE';
COMMENT ON COLUMN meta.table_security_classification.exportable IS
    'Explicit logical-export allowlist flag; defaults false for every new table';
COMMENT ON SCHEMA diagnostic IS
    'Only database objects directly readable by cilexec_readonly';

-- Final security assertion replaces the early bootstrap form after every baseline object,
-- diagnostic view, public function grant, and exporter policy exists.
CREATE OR REPLACE FUNCTION meta.assert_security_invariants()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, meta
AS $function$
DECLARE
    relation_record record;
    security_record meta.table_security_classification%ROWTYPE;
    owner_role oid := 'cilexec_owner'::regrole::oid;
    migrator_role oid := 'cilexec_migrator'::regrole::oid;
    readonly_role oid := 'cilexec_readonly'::regrole::oid;
    exporter_role oid := 'cilexec_exporter'::regrole::oid;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'cilexec_owner'
          AND (rolcanlogin OR NOT rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
               OR rolreplication OR rolbypassrls)
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname = 'cilexec_migrator'
          AND (NOT rolcanlogin OR NOT rolinherit OR rolsuper OR rolcreatedb
               OR NOT rolcreaterole OR rolreplication OR rolbypassrls)
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_roles
        WHERE rolname IN (
            'cilexec_runtime', 'cilexec_effect_worker',
            'cilexec_readonly', 'cilexec_exporter'
        )
          AND (NOT rolcanlogin OR rolinherit OR rolsuper OR rolcreatedb OR rolcreaterole
               OR rolreplication OR rolbypassrls)
    ) THEN
        RAISE EXCEPTION 'CilExec database roles have unexpected cluster attributes';
    END IF;

    IF NOT pg_has_role('cilexec_migrator', 'cilexec_owner', 'MEMBER')
       OR EXISTS (
           SELECT 1
           FROM unnest(ARRAY[
               'cilexec_runtime', 'cilexec_effect_worker',
               'cilexec_readonly', 'cilexec_exporter'
           ]) AS service(role_name)
           WHERE pg_has_role(service.role_name, 'cilexec_owner', 'MEMBER')
       ) THEN
        RAISE EXCEPTION 'CilExec owner-role membership is invalid';
    END IF;

    IF NOT COALESCE((SELECT rolconfig @> ARRAY['default_transaction_read_only=on']
                     FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_readonly'), false)
       OR NOT COALESCE((SELECT rolconfig @> ARRAY['default_transaction_read_only=on']
                        FROM pg_catalog.pg_roles WHERE rolname = 'cilexec_exporter'), false) THEN
        RAISE EXCEPTION 'readonly and exporter roles must default to read-only transactions';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_namespace AS namespace
        JOIN pg_catalog.pg_roles AS owner ON owner.oid = namespace.nspowner
        WHERE namespace.nspname IN (
            'meta', 'auth', 'object_store', 'program', 'process', 'scheduler',
            'ipc', 'vfs', 'package', 'effect', 'terminal', 'audit', 'diagnostic'
        )
          AND owner.rolname <> 'cilexec_owner'
    ) OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class AS relation
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = relation.relnamespace
        JOIN pg_catalog.pg_roles AS owner ON owner.oid = relation.relowner
        WHERE namespace.nspname IN (
            'meta', 'auth', 'object_store', 'program', 'process', 'scheduler',
            'ipc', 'vfs', 'package', 'effect', 'terminal', 'audit', 'diagnostic'
        )
          AND relation.relkind IN ('r', 'p', 'v', 'm', 'S')
          AND owner.rolname <> 'cilexec_owner'
    ) THEN
        RAISE EXCEPTION 'CilExec schema or relation has an unexpected owner';
    END IF;

    SELECT namespace.nspname AS schema_name,
           function.oid::regprocedure::text AS function_signature,
           pg_catalog.pg_get_userbyid(function.proowner) AS owner_name
    INTO relation_record
    FROM pg_catalog.pg_proc AS function
    JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = function.pronamespace
    WHERE namespace.nspname IN (
        'meta', 'auth', 'object_store', 'program', 'process', 'scheduler',
        'ipc', 'vfs', 'package', 'effect', 'terminal', 'audit', 'diagnostic'
    )
      AND (
          function.proowner NOT IN (owner_role, migrator_role)
          OR (function.proowner = migrator_role AND function.oid NOT IN (
              'auth.provision_login_role(uuid,text)'::regprocedure::oid,
              'auth.provision_principal(uuid,text)'::regprocedure::oid,
              'auth.disable_principal(uuid)'::regprocedure::oid,
              'auth.admin_create_user_as(name,text,uuid,uuid,text,text,text[],uuid,timestamptz)'::regprocedure::oid,
              'auth.admin_create_user(uuid,uuid,text,text,text[],uuid,timestamptz)'::regprocedure::oid,
              'auth.admin_disable_user_as(name,text,uuid,uuid,uuid,timestamptz)'::regprocedure::oid,
              'auth.admin_disable_user(uuid,uuid,uuid,timestamptz)'::regprocedure::oid
          ))
      )
    ORDER BY namespace.nspname, function.oid::regprocedure::text
    LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'CilExec function has an unexpected owner'
            USING DETAIL = format('%I.%s is owned by %I', relation_record.schema_name,
                                  relation_record.function_signature,
                                  relation_record.owner_name);
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc AS function
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = function.pronamespace
        WHERE function.prosecdef
          AND namespace.nspname IN (
              'meta', 'auth', 'object_store', 'program', 'process', 'scheduler',
              'ipc', 'vfs', 'package', 'effect', 'terminal', 'audit'
          )
          AND NOT EXISTS (
              SELECT 1
              FROM unnest(COALESCE(function.proconfig, ARRAY[]::text[])) AS setting(value)
              WHERE setting.value ~ '^search_path=pg_catalog(,|$)'
          )
    ) THEN
        RAISE EXCEPTION 'SECURITY DEFINER function lacks a pg_catalog-first search_path';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_proc AS function
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = function.pronamespace
        WHERE function.prosecdef
          AND namespace.nspname IN (
              'meta', 'auth', 'object_store', 'program', 'process', 'scheduler',
              'ipc', 'vfs', 'package', 'effect', 'terminal', 'audit'
          )
          AND EXISTS (
              SELECT 1
              FROM aclexplode(COALESCE(function.proacl,
                      acldefault('f', function.proowner))) AS acl
              WHERE acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'
          )
          AND NOT EXISTS (
              SELECT 1
              FROM meta.security_definer_public_allowlist AS allowed
              WHERE allowed.function_signature = function.oid::regprocedure::text
          )
    ) OR EXISTS (
        SELECT 1
        FROM meta.security_definer_public_allowlist AS allowed
        LEFT JOIN pg_catalog.pg_proc AS function
          ON function.oid = to_regprocedure(allowed.function_signature)
        WHERE function.oid IS NULL OR NOT function.prosecdef
           OR NOT EXISTS (
               SELECT 1
               FROM aclexplode(COALESCE(function.proacl,
                       acldefault('f', function.proowner))) AS acl
               WHERE acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'
           )
    ) THEN
        RAISE EXCEPTION 'SECURITY DEFINER PUBLIC EXECUTE ACL is outside the reviewed allowlist';
    END IF;

    FOR relation_record IN
        SELECT namespace.nspname AS schema_name,
               relation.relname AS table_name,
               relation.oid AS relation_oid,
               relation.relacl,
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
               FROM pg_catalog.pg_attribute AS attribute
               WHERE attribute.attrelid = relation_record.relation_oid
                 AND attribute.attname = security_record.owner_column
                 AND attribute.atttypid = 'uuid'::regtype
                 AND NOT attribute.attisdropped
           ) THEN
            RAISE EXCEPTION 'user-scoped table %.% lacks its declared UUID owner column',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.classification = 'USER_SCOPED'
           AND NOT EXISTS (
               SELECT 1
               FROM pg_catalog.pg_policy AS policy
               WHERE policy.polrelid = relation_record.relation_oid
                 AND owner_role = ANY(policy.polroles)
                 AND policy.polcmd = '*'
                 AND pg_get_expr(policy.polqual, policy.polrelid) = 'true'
                 AND pg_get_expr(policy.polwithcheck, policy.polrelid) = 'true'
           ) THEN
            RAISE EXCEPTION 'user-scoped table %.% lacks an unrestricted owner policy',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_catalog.pg_policy AS policy
            WHERE policy.polrelid = relation_record.relation_oid
              AND readonly_role = ANY(policy.polroles)
        ) THEN
            RAISE EXCEPTION 'readonly has a direct RLS policy on %.%',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.classification = 'USER_SCOPED'
           AND EXISTS (
               SELECT 1 FROM pg_catalog.pg_policy AS policy
               WHERE policy.polrelid = relation_record.relation_oid
                 AND 0::oid = ANY(policy.polroles)
                 AND pg_get_expr(policy.polqual, policy.polrelid) = 'true'
           ) THEN
            RAISE EXCEPTION 'user-scoped table %.% has an unconditional PUBLIC policy',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.exportable
           AND security_record.classification = 'USER_SCOPED'
           AND NOT EXISTS (
               SELECT 1 FROM pg_catalog.pg_policy AS policy
               WHERE policy.polrelid = relation_record.relation_oid
                 AND policy.polname = 'cilexec_exporter_read'
                 AND policy.polcmd = 'r'
                 AND policy.polroles = ARRAY[exporter_role]
                 AND pg_get_expr(policy.polqual, policy.polrelid) = 'true'
                 AND policy.polwithcheck IS NULL
           ) THEN
            RAISE EXCEPTION 'exportable user table %.% lacks its exporter SELECT policy',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF EXISTS (
            SELECT 1
            FROM aclexplode(COALESCE(relation_record.relacl, acldefault('r', owner_role))) AS acl
            WHERE acl.grantee = readonly_role
        ) THEN
            RAISE EXCEPTION 'readonly has a direct table ACL on %.%',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.exportable IS DISTINCT FROM (EXISTS (
            SELECT 1
            FROM aclexplode(COALESCE(relation_record.relacl, acldefault('r', owner_role))) AS acl
            WHERE acl.grantee = exporter_role AND acl.privilege_type = 'SELECT'
        )) OR EXISTS (
            SELECT 1
            FROM aclexplode(COALESCE(relation_record.relacl, acldefault('r', owner_role))) AS acl
            WHERE acl.grantee = exporter_role AND acl.privilege_type <> 'SELECT'
        ) THEN
            RAISE EXCEPTION 'exporter table ACL does not match export policy for %.%',
                relation_record.schema_name, relation_record.table_name;
        END IF;
        IF security_record.classification = 'SHARED_IMMUTABLE'
           AND (has_table_privilege('cilexec_runtime', relation_record.relation_oid, 'UPDATE')
                OR has_table_privilege('cilexec_runtime', relation_record.relation_oid, 'DELETE')) THEN
            RAISE EXCEPTION 'runtime has mutation privilege on immutable table %.%',
                relation_record.schema_name, relation_record.table_name;
        END IF;
    END LOOP;

    IF has_table_privilege('cilexec_readonly', 'auth.user_credential', 'SELECT')
       OR has_table_privilege('cilexec_exporter', 'auth.user_credential', 'SELECT') THEN
        RAISE EXCEPTION 'credential verifiers are visible to a read service role';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_catalog.pg_class AS view
        JOIN pg_catalog.pg_namespace AS namespace ON namespace.oid = view.relnamespace
        WHERE namespace.nspname = 'diagnostic' AND view.relkind = 'v'
          AND (NOT has_table_privilege('cilexec_readonly', view.oid, 'SELECT')
               OR EXISTS (
                   SELECT 1
                   FROM aclexplode(COALESCE(view.relacl,
                           acldefault('r', view.relowner))) AS acl
                   WHERE acl.grantee = 0 AND acl.privilege_type = 'SELECT'
               ))
    ) THEN
        RAISE EXCEPTION 'diagnostic view ACL is invalid';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_sequences
        WHERE schemaname = 'process' AND sequencename = 'pid_sequence' AND cycle
    ) THEN
        RAISE EXCEPTION 'process.pid_sequence must be NO CYCLE';
    END IF;
END
$function$;

REVOKE ALL ON FUNCTION meta.assert_security_invariants() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION meta.assert_security_invariants()
    TO cilexec_migrator, cilexec_runtime, cilexec_readonly;

SELECT meta.assert_security_invariants();

RESET ROLE;

-- ============================================================================
-- Component: terminal command history
-- ============================================================================
SET ROLE cilexec_owner;

-- Arrow-key history is user-owned durable state. It is intentionally separate from
-- terminal.input, whose rows may be consumed by a process waiting on io.input().
CREATE TABLE terminal.command_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES auth.user_account(user_id) ON DELETE CASCADE,
    command_text text NOT NULL CHECK (btrim(command_text) <> ''),
    submitted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_terminal_command_history_owner
    ON terminal.command_history(owner_id, history_id DESC);

ALTER TABLE terminal.command_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE terminal.command_history FORCE ROW LEVEL SECURITY;

CREATE POLICY command_history_owner_control ON terminal.command_history
    TO cilexec_owner USING (true) WITH CHECK (true);
CREATE POLICY command_history_runtime_control ON terminal.command_history
    TO cilexec_runtime USING (true) WITH CHECK (true);
CREATE POLICY command_history_principal ON terminal.command_history
    TO PUBLIC
    USING (owner_id = auth.current_cilexec_user_id())
    WITH CHECK (owner_id = auth.current_cilexec_user_id());

GRANT SELECT, INSERT, DELETE ON terminal.command_history TO cilexec_runtime;
GRANT USAGE, SELECT ON SEQUENCE terminal.command_history_history_id_seq TO cilexec_runtime;

-- Existing and future per-user LOGIN roles use these ACLs through PUBLIC. RLS still
-- binds every visible or writable row to auth.current_cilexec_user_id().
GRANT SELECT, INSERT, DELETE ON terminal.command_history TO PUBLIC;
GRANT USAGE, SELECT ON SEQUENCE terminal.command_history_history_id_seq TO PUBLIC;

INSERT INTO meta.table_security_classification
    (schema_name, table_name, classification, owner_column, rationale)
VALUES ('terminal', 'command_history', 'USER_SCOPED', 'owner_id',
        'durable per-user arrow-key command history');

COMMENT ON TABLE terminal.command_history IS
    'Complete REPL and colon commands only; never passwords or attached process input';

SELECT meta.assert_security_invariants();

RESET ROLE;

-- ============================================================================
-- Component: bounded chunk reads
-- ============================================================================
SET ROLE cilexec_owner;
-- Keep FCL range reads bounded to one download-sized chunk without changing the baseline after release.
CREATE OR REPLACE FUNCTION object_store.read_object_range_as(
    p_database_role name,
    p_claim text,
    p_object_hash bytea,
    p_offset bigint,
    p_maximum integer
)
RETURNS bytea
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, auth, object_store
AS $function$
DECLARE
    result bytea;
BEGIN
    IF p_offset < 0 OR p_maximum < 0 OR p_maximum > 4194304 THEN
        RAISE EXCEPTION 'invalid bounded object range' USING ERRCODE = '22023';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM object_store.read_object_as(
            p_database_role, p_claim, p_object_hash)) THEN
        RETURN NULL;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM object_store.chunk_manifest
                   WHERE manifest_hash = p_object_hash) THEN
        IF p_offset > 2147483646 THEN
            RETURN ''::bytea;
        END IF;
        SELECT substring(content FROM (p_offset + 1)::integer FOR p_maximum)
        INTO result FROM object_store.object WHERE object_hash = p_object_hash;
        RETURN COALESCE(result, ''::bytea);
    END IF;

    WITH RECURSIVE chain AS (
        SELECT manifest_hash, previous_manifest_hash, base_object_hash,
               tail_object_hash, total_size, tail_size
        FROM object_store.chunk_manifest WHERE manifest_hash = p_object_hash
        UNION ALL
        SELECT parent.manifest_hash, parent.previous_manifest_hash, parent.base_object_hash,
               parent.tail_object_hash, parent.total_size, parent.tail_size
        FROM object_store.chunk_manifest AS parent
        JOIN chain AS child ON parent.manifest_hash = child.previous_manifest_hash
    ), parts AS (
        SELECT base_object_hash AS part_hash, 0::bigint AS part_offset
        FROM chain WHERE base_object_hash IS NOT NULL
        UNION ALL
        SELECT tail_object_hash, total_size - tail_size
        FROM chain
    ), overlapping AS (
        SELECT part.part_offset, stored.content,
               GREATEST(p_offset - part.part_offset, 0)::integer AS local_offset,
               LEAST(stored.byte_size - GREATEST(p_offset - part.part_offset, 0),
                     p_offset + p_maximum - GREATEST(part.part_offset, p_offset))::integer AS take
        FROM parts AS part
        JOIN object_store.object AS stored ON stored.object_hash = part.part_hash
        WHERE part.part_offset < p_offset + p_maximum
          AND part.part_offset + stored.byte_size > p_offset
    )
    SELECT string_agg(substring(content FROM local_offset + 1 FOR take), ''::bytea
                      ORDER BY part_offset)
    INTO result FROM overlapping WHERE take > 0;
    RETURN COALESCE(result, ''::bytea);
END
$function$;

SELECT meta.assert_security_invariants();

RESET ROLE;

-- command_history is created after the main explicit export catalog above.
SET ROLE cilexec_owner;
UPDATE meta.table_security_classification
SET exportable = true
WHERE schema_name = 'terminal'::name AND table_name = 'command_history'::name;
GRANT SELECT ON terminal.command_history TO cilexec_exporter;
CREATE POLICY cilexec_exporter_read ON terminal.command_history
    FOR SELECT TO cilexec_exporter USING (true);
SELECT meta.assert_security_invariants();
RESET ROLE;
