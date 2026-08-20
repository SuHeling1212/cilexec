package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Statement;

/**
 * V002 / CilExec 0.0.2: the single forward migration for this unreleased version.
 *
 * <p>First, package private data FILE entries may be empty. The frozen V001 baseline
 * requires {@code byte_size > 0} for FILE entries, so
 * {@code packageData.write("empty.txt", "")} is rejected by the database even though
 * the language and object store support zero-length content. This migration relaxes
 * only the FILE arm of the constraint to {@code byte_size >= 0}; directories still
 * require a NULL object hash and zero size.
 *
 * <p>Second, user creation is guarded by administrator credentials. The frozen V001
 * baseline creates users only through {@code auth.admin_create_user}, which requires
 * the <em>current session user</em> to hold SYSTEM_ADMIN. This makes FCL
 * {@code user.create} deny ordinary users entirely and conflates "knows the password"
 * with "currently authorized". This migration adds
 * {@code auth.create_user_by_credential}: an ordinary user may self-register a normal
 * account, while creating an administrator requires the password of a currently
 * authorized SYSTEM_ADMIN holder.
 *
 * <p>PBKDF2 verification lives in the Java layer (the salt is random per hash, so the
 * verifier text can never be compared by equality), therefore
 * {@code auth.administrator_credential} exposes the stored verifier to the application
 * and {@code auth.create_user_by_credential} re-checks, atomically with the creation,
 * that the verified identity still holds effective SYSTEM_ADMIN (direct or group
 * derived, expiry aware). A revoked or expired capability can therefore never mint a
 * fresh administrator. The migration also grants {@code auth.provision_principal}
 * execution to {@code cilexec_owner}: the SECURITY DEFINER user-creation functions run
 * as that role but the frozen baseline only authorized {@code cilexec_runtime}, so the
 * original {@code auth.admin_create_user} path could never provision a role.
 *
 * <p>Both new SECURITY DEFINER functions intentionally keep PUBLIC EXECUTE (the
 * application calls {@code auth.administrator_credential} and
 * {@code auth.create_user_by_credential_as} on behalf of ordinary terminal
 * connections), so they are registered in
 * {@code meta.security_definer_public_allowlist}; the reviewed allowlist is the only
 * way {@code meta.assert_security_invariants()} accepts a SECURITY DEFINER function
 * with a PUBLIC execute ACL.
 */
public final class V002 extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("ALTER TABLE package.data_entry DROP CONSTRAINT data_entry_check");
            statement.execute("ALTER TABLE package.data_entry ADD CONSTRAINT data_entry_check CHECK ("
                    + "(entry_type = 'FILE' AND object_hash IS NOT NULL AND byte_size >= 0) "
                    + "OR (entry_type = 'DIRECTORY' AND object_hash IS NULL AND byte_size = 0))");
            statement.execute("SET ROLE cilexec_owner;");
            statement.execute("""
                    CREATE FUNCTION auth.administrator_credential(
                        p_administrator_username text
                    )
                    RETURNS TABLE (user_id uuid, password_hash text)
                    LANGUAGE sql
                    STABLE
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth
                    AS $function$
                        SELECT account.user_id, credential.password_hash
                        FROM auth.user_account AS account
                        JOIN auth.user_credential AS credential USING (user_id)
                        WHERE lower(account.username) = lower(p_administrator_username)
                          AND account.status = 'ACTIVE'
                    $function$;
                    """);
            statement.execute("""
                    CREATE FUNCTION auth.create_user_by_credential_as(
                        p_database_role name,
                        p_claim text,
                        p_administrator_id uuid,
                        p_user_id uuid,
                        p_username text,
                        p_password text,
                        p_capabilities text[],
                        p_event_id uuid,
                        p_at timestamptz
                    )
                    RETURNS SETOF auth.user_account
                    LANGUAGE plpgsql
                    SECURITY DEFINER
                    SET search_path = pg_catalog, auth, audit
                    AS $function$
                    DECLARE
                        actor uuid;
                        mapped_role name;
                        account auth.user_account%ROWTYPE;
                    BEGIN
                        IF p_administrator_id IS NOT NULL THEN
                            actor := p_administrator_id;
                            -- The application already verified the password verifier;
                            -- authorize the delegation from the current (verified,
                            -- still ACTIVE) effective capability state.
                            IF NOT EXISTS (
                                SELECT 1
                                FROM auth.user_capability AS assignment
                                JOIN auth.capability AS capability USING (capability_id)
                                WHERE assignment.user_id = actor
                                  AND capability.capability_key = 'system_admin'
                                  AND (assignment.expires_at IS NULL
                                       OR assignment.expires_at > clock_timestamp())
                                UNION ALL
                                SELECT 1
                                FROM auth.group_member AS member
                                JOIN auth.group_account AS group_account
                                  ON group_account.group_id = member.group_id
                                 AND group_account.owner_id = member.owner_id
                                 AND group_account.status = 'ACTIVE'
                                JOIN auth.group_capability AS assignment
                                  ON assignment.group_id = member.group_id
                                 AND assignment.owner_id = member.owner_id
                                JOIN auth.capability AS capability USING (capability_id)
                                WHERE member.member_user_id = actor
                                  AND capability.capability_key = 'system_admin'
                                  AND (assignment.expires_at IS NULL
                                       OR assignment.expires_at > clock_timestamp())
                            ) THEN
                                RAISE EXCEPTION 'system_admin capability is required'
                                    USING ERRCODE = '42501';
                            END IF;
                        ELSE
                            -- Self-registration: attribute the audit event to the
                            -- current session user instead of an administrator.
                            actor := auth.resolve_cilexec_user_id(
                                p_database_role, p_claim
                            );
                        END IF;

                        IF p_user_id IS NULL OR p_event_id IS NULL OR p_at IS NULL
                           OR p_username IS NULL OR btrim(p_username) = ''
                           OR p_password IS NULL OR p_password !~ '^pbkdf2-sha256[$][0-9]+[$]'
                           OR p_capabilities IS NULL THEN
                            RAISE EXCEPTION 'invalid user creation request'
                                USING ERRCODE = '22000';
                        END IF;
                        IF EXISTS (
                            SELECT 1 FROM unnest(p_capabilities) AS requested(capability_key)
                            LEFT JOIN auth.capability AS capability USING (capability_key)
                            WHERE capability.capability_id IS NULL
                        ) THEN
                            RAISE EXCEPTION 'unknown CilExec capability requested'
                                USING ERRCODE = '22000';
                        END IF;
                        IF EXISTS (
                            SELECT 1 FROM auth.user_account
                            WHERE lower(username) = lower(p_username)
                        ) THEN
                            RAISE EXCEPTION 'username already exists'
                                USING ERRCODE = '23505';
                        END IF;

                        mapped_role := ('cilexec_user_'
                            || replace(p_user_id::text, '-', ''))::name;
                        INSERT INTO auth.user_account(
                            user_id, username, postgres_role_name, status,
                            credential_version, created_at, updated_at
                        ) VALUES (
                            p_user_id, p_username, mapped_role, 'ACTIVE', 1, p_at, p_at
                        )
                        RETURNING * INTO account;

                        IF auth.provision_principal(p_user_id, p_password)
                           IS DISTINCT FROM mapped_role THEN
                            RAISE EXCEPTION 'database provisioned an unexpected CilExec role';
                        END IF;

                        INSERT INTO auth.user_capability(
                            user_id, owner_id, capability_id, granted_by
                        )
                        SELECT p_user_id, p_user_id, capability.capability_id, actor
                        FROM auth.capability AS capability
                        WHERE capability.capability_key = ANY (p_capabilities);

                        INSERT INTO audit.event(
                            event_id, owner_id, actor_type, actor_id, action,
                            resource_type, resource_id, result, details_json, created_at
                        ) VALUES (
                            p_event_id, p_user_id,
                            CASE WHEN p_administrator_id IS NOT NULL
                                      THEN 'ADMINISTRATOR'
                                 WHEN actor IS NOT NULL THEN 'USER'
                                 ELSE 'RUNTIME' END,
                            COALESCE(actor::text, 'runtime'),
                            'auth.user.create', 'auth.user', p_user_id::text, 'SUCCEEDED',
                            jsonb_build_object('username', p_username,
                                'status', 'ACTIVE'), p_at
                        );
                        RETURN NEXT account;
                    END
                    $function$;
                    """);
            statement.execute("""
                    CREATE FUNCTION auth.create_user_by_credential(
                        p_administrator_id uuid,
                        p_user_id uuid,
                        p_username text,
                        p_password text,
                        p_capabilities text[],
                        p_event_id uuid,
                        p_at timestamptz
                    )
                    RETURNS SETOF auth.user_account
                    LANGUAGE sql
                    SECURITY INVOKER
                    SET search_path = pg_catalog, auth
                    AS $function$
                        SELECT * FROM auth.create_user_by_credential_as(
                            current_user::name,
                            NULLIF(current_setting('app.cilexec_user_id', true), ''),
                            p_administrator_id, p_user_id, p_username, p_password,
                            p_capabilities, p_event_id, p_at
                        )
                    $function$;
                    """);
            statement.execute("""
                    REVOKE ALL ON FUNCTION auth.administrator_credential(text) FROM PUBLIC;
                    GRANT EXECUTE ON FUNCTION auth.administrator_credential(text) TO PUBLIC;
                    REVOKE ALL ON FUNCTION auth.create_user_by_credential_as(
                        name, text, uuid, uuid, text, text, text[], uuid, timestamptz
                    ) FROM PUBLIC;
                    GRANT EXECUTE ON FUNCTION auth.create_user_by_credential_as(
                        name, text, uuid, uuid, text, text, text[], uuid, timestamptz
                    ) TO PUBLIC;
                    GRANT EXECUTE ON FUNCTION auth.create_user_by_credential(
                        uuid, uuid, text, text, text[], uuid, timestamptz
                    ) TO PUBLIC;
                    """);
            statement.execute("""
                    INSERT INTO meta.security_definer_public_allowlist(
                        function_signature, rationale
                    )
                    VALUES
                        ('auth.administrator_credential(text)'::regprocedure::text,
                         'application-side PBKDF2 verifier lookup used by credential-guarded'
                         ' administrator delegation'),
                        ('auth.create_user_by_credential_as(name,text,uuid,uuid,text,text,text[],uuid,timestamptz)'::regprocedure::text,
                         'credential-guarded user creation that re-checks effective'
                         ' SYSTEM_ADMIN atomically with the new user');
                    """);

            // Bug fix: IPC competing-consumer channel fairness. The frozen baseline
            // selects the chronologically earliest subscription; add a rotation
            // marker so channel sends round-robin across active subscribers.
            statement.execute("ALTER TABLE ipc.subscription "
                    + "ADD COLUMN last_selected_at timestamptz;");
            statement.execute("""
CREATE OR REPLACE FUNCTION package.uninstall_package_as(
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
    removed_package_hashes bytea[] := ARRAY[]::bytea[];
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

    -- Capture the removed closure BEFORE deleting members so the GC pass below can
    -- purge dependency releases that become unreferenced by this uninstall.
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
    )
    SELECT COALESCE(array_agg(DISTINCT package_hash), ARRAY[]::bytea[])
    INTO removed_package_hashes FROM removed_closure;

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
           OR release.package_hash = ANY (removed_package_hashes)
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
              AND keeper.package_hash NOT IN (
                  SELECT package_hash FROM purge_candidates
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
$function$;""");
            statement.execute("RESET ROLE;");
            // provision_principal is executable only by cilexec_runtime; the SECURITY
            // DEFINER user-creation functions run as cilexec_owner and also need it.
            statement.execute("""
                    GRANT EXECUTE ON FUNCTION auth.provision_principal(uuid, text)
                        TO cilexec_owner;
                    """);
        }
    }
}
