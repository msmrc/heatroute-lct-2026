ALTER TABLE official_imports
    ADD COLUMN IF NOT EXISTS contract_version varchar(80),
    ADD COLUMN IF NOT EXISTS feature_count bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS completed_at timestamptz;

CREATE INDEX IF NOT EXISTS ix_official_imports_state_created
    ON official_imports (state, created_at);
