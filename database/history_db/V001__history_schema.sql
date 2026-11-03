-- history_db schema v1
-- Ownership: history-service only.
-- Apply while connected to history_db. IDs owned by other services are business references, not foreign keys.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE assessment (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 uuid NOT NULL,
    create_idempotency_key  uuid NOT NULL,
    status                  varchar(24) NOT NULL DEFAULT 'DRAFT',
    current_step_key        varchar(32) NOT NULL DEFAULT 'BASIC_INPUT',
    hand_side               varchar(8),
    onset_kind              varchar(24),
    onset_amount            integer,
    onset_date              date,
    onset_text              varchar(200),
    trigger_context         text,
    normal_use_impact       varchar(16),
    additional_note         text,
    version                 integer NOT NULL DEFAULT 1,
    last_saved_at           timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assessment_status
        CHECK (status IN ('DRAFT', 'COLLECTING', 'CLARIFYING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_assessment_current_step
        CHECK (current_step_key IN ('BASIC_INPUT', 'LOCATION_3D', 'VISUAL_ANNOTATION', 'AGENT', 'RESULT')),
    CONSTRAINT ck_assessment_hand_side
        CHECK (hand_side IS NULL OR hand_side IN ('LEFT', 'RIGHT')),
    CONSTRAINT ck_assessment_onset_kind
        CHECK (onset_kind IS NULL OR onset_kind IN ('TODAY', 'DAYS_AGO', 'WEEKS_AGO', 'MONTHS_AGO', 'EXACT_DATE', 'UNKNOWN')),
    CONSTRAINT ck_assessment_onset_amount
        CHECK (onset_amount IS NULL OR onset_amount > 0),
    CONSTRAINT ck_assessment_impact
        CHECK (normal_use_impact IS NULL OR normal_use_impact IN ('NONE', 'MILD', 'MODERATE', 'SEVERE', 'UNKNOWN')),
    CONSTRAINT ck_assessment_version CHECK (version > 0),
    CONSTRAINT uq_assessment_user_create_idempotency
        UNIQUE (user_id, create_idempotency_key)
);

CREATE INDEX idx_assessment_user_history
    ON assessment (user_id, updated_at DESC)
    WHERE status <> 'CANCELLED';

CREATE INDEX idx_assessment_resume
    ON assessment (user_id, last_saved_at DESC)
    WHERE status IN ('DRAFT', 'COLLECTING', 'CLARIFYING');

CREATE TABLE assessment_symptom (
    assessment_id       uuid NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    symptom_code        varchar(32) NOT NULL,
    detail_note         varchar(500),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (assessment_id, symptom_code),
    CONSTRAINT ck_assessment_symptom_code
        CHECK (symptom_code IN (
            'PAIN', 'ACHING', 'DISTENSION', 'NUMBNESS',
            'SWELLING', 'WEAKNESS', 'STIFFNESS', 'OTHER_UNCERTAIN'
        ))
);

CREATE INDEX idx_assessment_symptom_code
    ON assessment_symptom (symptom_code, assessment_id);

CREATE TABLE symptom_location (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id       uuid NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    hand_side           varchar(8) NOT NULL,
    region_id           varchar(100) NOT NULL,
    region_label        varchar(120) NOT NULL,
    finger_code         varchar(24),
    joint_code          varchar(24),
    surface_code        varchar(24),
    is_primary          boolean NOT NULL DEFAULT false,
    detail_note         varchar(500),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_symptom_location_region UNIQUE (assessment_id, region_id),
    CONSTRAINT ck_symptom_location_hand_side
        CHECK (hand_side IN ('LEFT', 'RIGHT')),
    CONSTRAINT ck_symptom_location_finger
        CHECK (finger_code IS NULL OR finger_code IN ('THUMB', 'INDEX', 'MIDDLE', 'RING', 'LITTLE')),
    CONSTRAINT ck_symptom_location_joint
        CHECK (joint_code IS NULL OR joint_code IN ('TIP', 'DIP', 'PIP', 'MCP', 'CMC')),
    CONSTRAINT ck_symptom_location_surface
        CHECK (surface_code IS NULL OR surface_code IN ('PALMAR', 'DORSAL', 'RADIAL', 'ULNAR', 'UNKNOWN'))
);

CREATE UNIQUE INDEX uq_symptom_location_primary
    ON symptom_location (assessment_id)
    WHERE is_primary;

CREATE TABLE assessment_visual (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id       uuid NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    picture_id          uuid NOT NULL,
    annotation_set_id   uuid,
    source_type         varchar(32) NOT NULL,
    sort_order          integer NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_assessment_visual_picture UNIQUE (assessment_id, picture_id),
    CONSTRAINT ck_assessment_visual_source
        CHECK (source_type IN ('MODEL_SNAPSHOT', 'STANDARD_REFERENCE', 'USER_UPLOAD')),
    CONSTRAINT ck_assessment_visual_sort CHECK (sort_order >= 0)
);

CREATE INDEX idx_assessment_visual_order
    ON assessment_visual (assessment_id, sort_order, created_at);

CREATE TABLE assessment_answer (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id       uuid NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    client_answer_id    uuid NOT NULL,
    sequence_no         integer NOT NULL,
    question_key        varchar(120) NOT NULL,
    question_text       varchar(500),
    answer              jsonb NOT NULL,
    answer_source       varchar(24) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_assessment_answer_client UNIQUE (assessment_id, client_answer_id),
    CONSTRAINT uq_assessment_answer_sequence UNIQUE (assessment_id, sequence_no),
    CONSTRAINT ck_assessment_answer_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_assessment_answer_json CHECK (jsonb_typeof(answer) IN ('object', 'array', 'string', 'number', 'boolean')),
    CONSTRAINT ck_assessment_answer_source
        CHECK (answer_source IN ('INITIAL_FORM', 'MOCK_AGENT', 'AI_AGENT', 'USER_CORRECTION'))
);

CREATE TABLE assessment_result (
    assessment_id           uuid PRIMARY KEY REFERENCES assessment(id) ON DELETE CASCADE,
    recommendation_level    varchar(32) NOT NULL,
    summary                 text NOT NULL,
    reasons                 jsonb NOT NULL DEFAULT '[]'::jsonb,
    suggested_next_actions  jsonb NOT NULL DEFAULT '[]'::jsonb,
    warning_signs           jsonb NOT NULL DEFAULT '[]'::jsonb,
    generated_by            varchar(16) NOT NULL,
    generator_reference     varchar(120),
    safety_notice           text NOT NULL,
    finalized_at            timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assessment_result_level
        CHECK (recommendation_level IN ('SELF_MONITOR', 'ROUTINE_PROFESSIONAL', 'PROMPT_PROFESSIONAL', 'URGENT')),
    CONSTRAINT ck_assessment_result_generated_by
        CHECK (generated_by IN ('MOCK', 'RULE', 'AI')),
    CONSTRAINT ck_assessment_result_reasons CHECK (jsonb_typeof(reasons) = 'array'),
    CONSTRAINT ck_assessment_result_actions CHECK (jsonb_typeof(suggested_next_actions) = 'array'),
    CONSTRAINT ck_assessment_result_warnings CHECK (jsonb_typeof(warning_signs) = 'array')
);

COMMENT ON COLUMN assessment.user_id IS
    'Business reference to base_db.user_account; intentionally has no cross-database foreign key.';
COMMENT ON COLUMN assessment.create_idempotency_key IS
    'Client-generated X-Idempotency-Key for createAssessment; uniqueness is scoped to the owning user.';
COMMENT ON TABLE assessment_symptom IS
    'Multi-select symptom vocabulary for the current assessment; detail_note carries optional user wording without replacing the stable code.';
COMMENT ON TABLE symptom_location IS
    'Coarse location of one or more reported symptoms. Region IDs are stable business vocabulary, not mesh triangle identifiers.';
COMMENT ON COLUMN assessment_visual.picture_id IS
    'Business reference to image_db.picture_asset; intentionally has no cross-database foreign key.';
COMMENT ON COLUMN assessment_visual.annotation_set_id IS
    'Business reference to image_db.annotation_set; intentionally has no cross-database foreign key.';
COMMENT ON TABLE assessment_result IS
    'Communication aid result only; it must not be represented as a medical diagnosis.';

COMMIT;
