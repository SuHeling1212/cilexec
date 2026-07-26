SET ROLE cilexec_owner;

-- A deliberately narrow application-level privilege. User LOGIN roles remain
-- subject to their owner-scoped RLS policies; only the trusted runtime service
-- may exercise this capability through an audited system transaction.
INSERT INTO auth.capability(capability_id, capability_key, description, system_capability)
VALUES (
    '00000000-0000-4000-8000-00000000000c',
    'system_admin',
    'Perform audited cross-user CilExec administration',
    true
);

RESET ROLE;
