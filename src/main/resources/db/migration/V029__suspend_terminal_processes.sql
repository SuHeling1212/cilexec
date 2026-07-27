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
