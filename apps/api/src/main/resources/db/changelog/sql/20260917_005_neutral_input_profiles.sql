UPDATE official_imports
SET input_profile = CASE input_profile
    WHEN 'strict_official' THEN 'extended_input'
    WHEN 'official_contest_dataset' THEN 'baseline_input'
    ELSE input_profile
END
WHERE input_profile IN ('strict_official', 'official_contest_dataset');

ALTER TABLE official_imports
    ALTER COLUMN input_profile SET DEFAULT 'extended_input';
