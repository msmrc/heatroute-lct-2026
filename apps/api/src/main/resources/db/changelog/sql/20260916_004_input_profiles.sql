ALTER TABLE official_imports
    ADD COLUMN IF NOT EXISTS input_profile varchar(80) NOT NULL DEFAULT 'strict_official',
    ADD COLUMN IF NOT EXISTS warnings jsonb NOT NULL DEFAULT '[]'::jsonb;
