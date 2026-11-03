-- ============================================================================
-- NOT APPLIED: Agent result-finalization is implemented; review before applying.
-- Ownership: history-service. Prerequisites: history_db V001 and V002.
-- Keep assessment_result unchanged as the current-result projection.
-- This migration does not backfill historical results or start Agent processing.
-- ============================================================================

BEGIN;

CREATE TABLE assessment_result_revision (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id           uuid NOT NULL REFERENCES assessment(id) ON DELETE CASCADE,
    revision_no             integer NOT NULL,
    agent_session_id        uuid,
    agent_attempt_no        integer,
    agent_step_id           uuid,
    agent_checkpoint_id     uuid,
    assessment_input_fingerprint char(64),
    recommendation_level    varchar(32) NOT NULL,
    summary                 text NOT NULL,
    reasons                 jsonb NOT NULL DEFAULT '[]'::jsonb,
    suggested_next_actions  jsonb NOT NULL DEFAULT '[]'::jsonb,
    warning_signs           jsonb NOT NULL DEFAULT '[]'::jsonb,
    generated_by            varchar(16) NOT NULL,
    generator_reference     varchar(120),
    safety_notice           text NOT NULL,
    finalized_at            timestamptz NOT NULL DEFAULT now(),
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_assessment_result_revision_no UNIQUE (assessment_id, revision_no),
    CONSTRAINT ck_assessment_result_revision_no CHECK (revision_no > 0),
    CONSTRAINT ck_assessment_result_revision_attempt CHECK (
        (agent_attempt_no IS NULL OR agent_attempt_no > 0)
        AND (generated_by <> 'AI' OR (agent_session_id IS NOT NULL AND agent_attempt_no IS NOT NULL))
    ),
    CONSTRAINT ck_assessment_result_revision_input_fingerprint CHECK (
        (assessment_input_fingerprint IS NULL OR assessment_input_fingerprint ~ '^[0-9a-f]{64}$')
        AND (agent_session_id IS NULL OR assessment_input_fingerprint IS NOT NULL)
    ),
    CONSTRAINT ck_assessment_result_revision_agent_references CHECK (
        (agent_step_id IS NULL AND agent_checkpoint_id IS NULL)
        OR agent_session_id IS NOT NULL
    ),
    CONSTRAINT ck_assessment_result_revision_level CHECK (
        recommendation_level IN ('SELF_MONITOR', 'ROUTINE_PROFESSIONAL', 'PROMPT_PROFESSIONAL', 'URGENT')
    ),
    CONSTRAINT ck_assessment_result_revision_generated_by CHECK (generated_by IN ('MOCK', 'RULE', 'AI')),
    CONSTRAINT ck_assessment_result_revision_reasons CHECK (jsonb_typeof(reasons) = 'array'),
    CONSTRAINT ck_assessment_result_revision_actions CHECK (jsonb_typeof(suggested_next_actions) = 'array'),
    CONSTRAINT ck_assessment_result_revision_warnings CHECK (jsonb_typeof(warning_signs) = 'array')
);

CREATE UNIQUE INDEX uq_assessment_result_revision_session
    ON assessment_result_revision (agent_session_id)
    WHERE agent_session_id IS NOT NULL;

COMMENT ON TABLE assessment_result_revision IS
    'Append-only finalized result history. Future history-service finalization must insert one revision and update assessment_result in the same local transaction; assessment deletion may cascade for data erasure.';
COMMENT ON COLUMN assessment_result_revision.revision_no IS
    'Positive result version within one assessment, allocated while locking the assessment row. Reanalysis appends a revision rather than editing an old one.';
COMMENT ON COLUMN assessment_result_revision.agent_session_id IS
    'External ai_db reference without a cross-database foreign key. At most one finalized result per Agent session; a new analysis attempt uses a new session.';
COMMENT ON COLUMN assessment_result_revision.agent_step_id IS
    'External ai_db reference to the output-result step that produced this revision; session ownership is validated by the application.';
COMMENT ON COLUMN assessment_result_revision.agent_attempt_no IS
    'Monotonic AI attempt number within the assessment. History rejects a late first delivery from an older attempt after a newer result is finalized.';
COMMENT ON COLUMN assessment_result_revision.assessment_input_fingerprint IS
    'SHA-256 of stable History input at Agent creation, excluding answers/version/status/timestamps. Retained for provenance and same-session payload comparison.';
COMMENT ON COLUMN assessment_result_revision.agent_checkpoint_id IS
    'External ai_db reference to the finalized state used for this result; no signed picture URLs or model hidden reasoning belong in the result.';
COMMENT ON COLUMN assessment_result_revision.finalized_at IS
    'Business time when this result was finalized, retained even if persistence is retried later.';
COMMENT ON COLUMN assessment_result_revision.created_at IS
    'Time this immutable revision was first persisted in history_db.';

COMMIT;
