ALTER TABLE official_runs
    ADD COLUMN parameters jsonb NOT NULL DEFAULT
    '{"minimum_depth_m": 0.7, "maximum_depth_m": 10.0}'::jsonb;
