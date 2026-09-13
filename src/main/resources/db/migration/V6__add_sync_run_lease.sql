ALTER TABLE job_source_sync_runs
    ADD COLUMN lease_expires_at TIMESTAMP(6);

-- A run left RUNNING by an older process must be recoverable immediately after upgrade.
UPDATE job_source_sync_runs
SET lease_expires_at = clock_timestamp()::timestamp
WHERE status = 'RUNNING';

ALTER TABLE job_source_sync_runs
    ADD CONSTRAINT job_source_sync_runs_lease_state_check CHECK (
        (status = 'RUNNING' AND lease_expires_at IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_expires_at IS NULL)
    );

CREATE INDEX ix_job_source_sync_runs_running_lease
    ON job_source_sync_runs (lease_expires_at, job_source_id)
    WHERE status = 'RUNNING';
