-- image_db schema v1
-- Ownership: image-service only.
-- Apply while connected to image_db. MinIO stores bytes; PostgreSQL stores metadata and annotations.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE picture_asset (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id           uuid,
    source_type             varchar(32) NOT NULL,
    standard_asset_key      varchar(100),
    standard_hand_side      varchar(8),
    standard_view           varchar(16),
    bucket_name             varchar(100) NOT NULL,
    object_key              varchar(512) NOT NULL,
    original_filename       varchar(255),
    content_type            varchar(100) NOT NULL,
    size_bytes              bigint,
    sha256                  char(64),
    width_px                integer,
    height_px               integer,
    storage_status          varchar(24) NOT NULL DEFAULT 'INITIATED',
    processing_status       varchar(24) NOT NULL DEFAULT 'PENDING',
    processing_generation   integer NOT NULL DEFAULT 1,
    processing_error_code   varchar(80),
    safe_metadata           jsonb NOT NULL DEFAULT '{}'::jsonb,
    view_state              jsonb,
    upload_idempotency_key  varchar(100),
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    deleted_at              timestamptz,
    CONSTRAINT uq_picture_asset_object UNIQUE (bucket_name, object_key),
    CONSTRAINT ck_picture_asset_source
        CHECK (source_type IN ('MODEL_SNAPSHOT', 'STANDARD_REFERENCE', 'USER_UPLOAD')),
    CONSTRAINT ck_picture_asset_ownership
        CHECK ((source_type = 'STANDARD_REFERENCE' AND owner_user_id IS NULL)
            OR (source_type <> 'STANDARD_REFERENCE' AND owner_user_id IS NOT NULL)),
    CONSTRAINT ck_picture_asset_standard_metadata
        CHECK ((source_type = 'STANDARD_REFERENCE'
                AND standard_asset_key IS NOT NULL
                AND standard_hand_side IS NOT NULL
                AND standard_hand_side IN ('LEFT', 'RIGHT')
                AND standard_view IS NOT NULL
                AND standard_view IN ('PALMAR', 'DORSAL', 'SIDE'))
            OR (source_type <> 'STANDARD_REFERENCE'
                AND standard_asset_key IS NULL
                AND standard_hand_side IS NULL
                AND standard_view IS NULL)),
    CONSTRAINT ck_picture_asset_storage_status
        CHECK (storage_status IN ('INITIATED', 'UPLOADED', 'AVAILABLE', 'FAILED', 'DELETED')),
    CONSTRAINT ck_picture_asset_processing_status
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'NOT_REQUIRED')),
    CONSTRAINT ck_picture_asset_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_picture_asset_dimensions
        CHECK ((width_px IS NULL OR width_px > 0) AND (height_px IS NULL OR height_px > 0)),
    CONSTRAINT ck_picture_asset_generation CHECK (processing_generation > 0),
    CONSTRAINT ck_picture_asset_safe_metadata CHECK (jsonb_typeof(safe_metadata) = 'object'),
    CONSTRAINT ck_picture_asset_view_state
        CHECK (view_state IS NULL OR jsonb_typeof(view_state) = 'object')
);

CREATE UNIQUE INDEX uq_picture_asset_owner_idempotency
    ON picture_asset (owner_user_id, upload_idempotency_key)
    WHERE upload_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX uq_picture_asset_standard_key
    ON picture_asset (standard_asset_key)
    WHERE source_type = 'STANDARD_REFERENCE';

CREATE INDEX idx_picture_asset_owner_created
    ON picture_asset (owner_user_id, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_picture_asset_processing
    ON picture_asset (processing_status, created_at)
    WHERE processing_status IN ('PENDING', 'PROCESSING', 'FAILED');

CREATE TABLE picture_variant (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    picture_id              uuid NOT NULL REFERENCES picture_asset(id) ON DELETE CASCADE,
    variant_type            varchar(32) NOT NULL,
    processing_generation   integer NOT NULL,
    bucket_name             varchar(100) NOT NULL,
    object_key              varchar(512) NOT NULL,
    content_type            varchar(100) NOT NULL,
    size_bytes              bigint NOT NULL,
    width_px                integer NOT NULL,
    height_px               integer NOT NULL,
    sha256                  char(64),
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_picture_variant_object UNIQUE (bucket_name, object_key),
    CONSTRAINT uq_picture_variant_generation
        UNIQUE (picture_id, variant_type, processing_generation),
    CONSTRAINT ck_picture_variant_type
        CHECK (variant_type IN ('NORMALIZED', 'THUMBNAIL', 'AI_INPUT')),
    CONSTRAINT ck_picture_variant_generation CHECK (processing_generation > 0),
    CONSTRAINT ck_picture_variant_size CHECK (size_bytes >= 0),
    CONSTRAINT ck_picture_variant_dimensions CHECK (width_px > 0 AND height_px > 0)
);

CREATE TABLE annotation_set (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    picture_id          uuid NOT NULL REFERENCES picture_asset(id) ON DELETE CASCADE,
    assessment_id       uuid,
    owner_user_id       uuid NOT NULL,
    status              varchar(16) NOT NULL DEFAULT 'DRAFT',
    coordinate_space    varchar(24) NOT NULL DEFAULT 'NORMALIZED_0_1',
    schema_version      integer NOT NULL DEFAULT 1,
    revision            integer NOT NULL DEFAULT 1,
    note                text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_annotation_set_status CHECK (status IN ('DRAFT', 'FINAL')),
    CONSTRAINT ck_annotation_coordinate_space CHECK (coordinate_space = 'NORMALIZED_0_1'),
    CONSTRAINT ck_annotation_schema_version CHECK (schema_version > 0),
    CONSTRAINT ck_annotation_revision CHECK (revision > 0)
);

CREATE INDEX idx_annotation_set_picture
    ON annotation_set (picture_id, updated_at DESC);

CREATE UNIQUE INDEX uq_annotation_set_scope
    ON annotation_set (picture_id, owner_user_id, assessment_id) NULLS NOT DISTINCT;

CREATE INDEX idx_annotation_set_assessment
    ON annotation_set (assessment_id)
    WHERE assessment_id IS NOT NULL;

CREATE TABLE picture_annotation (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    annotation_set_id   uuid NOT NULL REFERENCES annotation_set(id) ON DELETE CASCADE,
    shape_type          varchar(20) NOT NULL,
    geometry            jsonb NOT NULL,
    style               jsonb NOT NULL DEFAULT '{}'::jsonb,
    label               varchar(120),
    sort_order          integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_picture_annotation_shape
        CHECK (shape_type IN ('POINT', 'CIRCLE', 'POLYLINE', 'POLYGON')),
    CONSTRAINT ck_picture_annotation_geometry CHECK (jsonb_typeof(geometry) = 'object'),
    CONSTRAINT ck_picture_annotation_style CHECK (jsonb_typeof(style) = 'object'),
    CONSTRAINT ck_picture_annotation_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_picture_annotation_set_sort
    ON picture_annotation (annotation_set_id, sort_order, created_at);

COMMENT ON COLUMN picture_asset.owner_user_id IS
    'Business reference to base_db.user_account; intentionally has no cross-database foreign key. NULL only for system standard references.';
COMMENT ON COLUMN picture_asset.standard_asset_key IS
    'Stable system asset key. Standard side/view are first-class searchable fields, not inferred from a filename or JSON metadata.';
COMMENT ON COLUMN annotation_set.assessment_id IS
    'Business reference to history_db.assessment; intentionally has no cross-database foreign key.';
COMMENT ON COLUMN picture_asset.safe_metadata IS
    'Allowlisted non-sensitive metadata only. Raw EXIF is not retained after sanitization.';
COMMENT ON COLUMN picture_asset.view_state IS
    'image-service-owned camera and model state for reproducing a frozen 3D model snapshot; optional implementation hit data is not medical semantics.';
COMMENT ON COLUMN picture_annotation.geometry IS
    'Shape-specific JSON using normalized [0,1] coordinates; validation is completed at the API boundary.';
COMMENT ON CONSTRAINT uq_picture_variant_generation ON picture_variant IS
    'Makes repeated RabbitMQ deliveries safe when consumers use deterministic generation and UPSERT semantics.';

COMMIT;
