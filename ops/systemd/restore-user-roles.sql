\set ON_ERROR_STOP on

-- Run after pg_restore as a role that can execute auth.provision_login_role. Cluster roles are
-- not contained in a database-only pg_dump, so stable tenant NOLOGIN roles must be reconstructed.
SELECT auth.provision_login_role(account.user_id, credential.password_hash)
FROM auth.user_account AS account
JOIN auth.user_credential AS credential USING (user_id)
WHERE account.status = 'ACTIVE'
ORDER BY account.user_id;

SELECT meta.assert_security_invariants();
