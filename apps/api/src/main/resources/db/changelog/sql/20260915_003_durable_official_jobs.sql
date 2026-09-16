CREATE TABLE IF NOT EXISTS official_jobs (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES official_imports(id) ON DELETE CASCADE,
    job_type varchar(40) NOT NULL,
    state varchar(20) NOT NULL,
    phase varchar(80) NOT NULL,
    progress_current bigint NOT NULL DEFAULT 0,
    progress_total bigint NOT NULL DEFAULT 1,
    attempt integer NOT NULL DEFAULT 0,
    cancellation_requested boolean NOT NULL DEFAULT false,
    lease_owner uuid,
    lease_until timestamptz,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    result jsonb,
    error_code varchar(80),
    error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    heartbeat_at timestamptz,
    completed_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_official_job_type CHECK (job_type IN ('topology_analysis', 'calculation')),
    CONSTRAINT ck_official_job_state CHECK (
        state IN ('queued', 'running', 'completed', 'failed', 'cancelled')
    ),
    CONSTRAINT ck_official_job_progress CHECK (
        progress_current >= 0 AND progress_total > 0 AND progress_current <= progress_total
    )
);

CREATE INDEX IF NOT EXISTS ix_official_jobs_claim
    ON official_jobs (state, lease_until, created_at);
CREATE INDEX IF NOT EXISTS ix_official_jobs_import_created
    ON official_jobs (import_id, created_at DESC);
