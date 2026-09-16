CREATE TABLE official_runs (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES official_imports(id) ON DELETE CASCADE,
    job_id uuid UNIQUE,
    state varchar(20) NOT NULL,
    algorithm_version varchar(80) NOT NULL,
    input_sha256 varchar(64) NOT NULL,
    result jsonb,
    error_code varchar(80),
    error_message varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT ck_official_run_state CHECK (
        state IN ('queued', 'running', 'completed', 'failed', 'cancelled')
    )
);

ALTER TABLE official_jobs
    ADD COLUMN run_id uuid REFERENCES official_runs(id) ON DELETE CASCADE;

ALTER TABLE official_runs
    ADD CONSTRAINT fk_official_run_job
    FOREIGN KEY (job_id) REFERENCES official_jobs(id) ON DELETE SET NULL;

CREATE INDEX ix_official_runs_import_created
    ON official_runs (import_id, created_at DESC);
