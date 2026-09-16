CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS official_imports (
    id uuid PRIMARY KEY,
    state varchar(32) NOT NULL,
    original_filename varchar(255) NOT NULL,
    raw_sha256 varchar(64),
    input_size_bytes bigint,
    feature_counts jsonb NOT NULL DEFAULT '{}'::jsonb,
    errors jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_official_import_state CHECK (
        state IN ('uploaded', 'validating', 'valid', 'invalid', 'failed', 'cancelled')
    )
);

CREATE TABLE IF NOT EXISTS official_features (
    id uuid PRIMARY KEY,
    import_id uuid NOT NULL REFERENCES official_imports(id) ON DELETE CASCADE,
    feature_id varchar(500) NOT NULL,
    object_type varchar(40) NOT NULL,
    attributes jsonb NOT NULL,
    geometry_wgs84 geometry(Geometry, 4326) NOT NULL,
    geometry_metric geometry(Geometry, 32637) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_official_feature_id UNIQUE (import_id, feature_id)
);

CREATE INDEX IF NOT EXISTS ix_official_features_import_type
    ON official_features (import_id, object_type);
CREATE INDEX IF NOT EXISTS ix_official_features_wgs84_gist
    ON official_features USING gist (geometry_wgs84);
CREATE INDEX IF NOT EXISTS ix_official_features_metric_gist
    ON official_features USING gist (geometry_metric);
