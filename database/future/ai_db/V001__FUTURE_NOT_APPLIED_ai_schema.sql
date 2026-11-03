-- ============================================================================
-- NOT APPLIED: Agent implementation is ready for review; RAG remains FUTURE.
-- ai_db: bounded Agent sessions, pause/resume and branching answer corrections.
-- Apply explicitly after review. No migration runner or init script executes it.
-- No cross-database foreign keys; no model SDK, raw prompt log or mandatory RAG.
-- ============================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE agent_session (
    id                          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id               uuid NOT NULL,
    user_id                     uuid NOT NULL,
    assessment_version          integer NOT NULL,
    attempt_no                  integer NOT NULL DEFAULT 1,
    client_request_id           uuid NOT NULL,
    request_fingerprint         char(64) NOT NULL,
    status                      varchar(24) NOT NULL DEFAULT 'CREATED',
    workflow_version            varchar(40) NOT NULL,
    preferred_locale            varchar(20) NOT NULL DEFAULT 'zh-CN',
    model_provider              varchar(80) NOT NULL,
    model_name                  varchar(120) NOT NULL,
    input_snapshot_schema_version integer NOT NULL DEFAULT 1,
    input_snapshot              jsonb NOT NULL,
    source_session_id           uuid,
    source_checkpoint_id        uuid,
    active_checkpoint_id        uuid,
    last_step_no                integer NOT NULL DEFAULT 0,
    version                     bigint NOT NULL DEFAULT 1,
    execution_generation        bigint NOT NULL DEFAULT 0,
    execution_deadline_at       timestamptz,
    total_model_calls           integer NOT NULL DEFAULT 0,
    max_model_calls             integer NOT NULL DEFAULT 20,
    max_steps                   integer NOT NULL DEFAULT 40,
    termination_reason_code     varchar(80),
    termination_detail          varchar(500),
    started_at                  timestamptz,
    completed_at                timestamptz,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_session_attempt UNIQUE (assessment_id, attempt_no),
    CONSTRAINT uq_agent_session_request UNIQUE (user_id, client_request_id),
    CONSTRAINT ck_agent_session_attempt CHECK (attempt_no > 0),
    CONSTRAINT ck_agent_session_assessment_version CHECK (assessment_version > 0),
    CONSTRAINT ck_agent_session_version CHECK (version > 0),
    CONSTRAINT ck_agent_session_generation CHECK (execution_generation >= 0),
    CONSTRAINT ck_agent_session_budget CHECK (
        max_model_calls > 0 AND total_model_calls BETWEEN 0 AND max_model_calls
        AND max_steps > 0 AND last_step_no BETWEEN 0 AND max_steps
    ),
    CONSTRAINT ck_agent_session_snapshot CHECK (
        input_snapshot_schema_version > 0 AND jsonb_typeof(input_snapshot) = 'object'
    ),
    CONSTRAINT ck_agent_session_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_session_source CHECK (
        (source_session_id IS NULL AND source_checkpoint_id IS NULL)
        OR (source_session_id IS NOT NULL AND source_checkpoint_id IS NOT NULL
            AND source_session_id <> id)
    ),
    CONSTRAINT ck_agent_session_status CHECK (status IN (
        'CREATED', 'RUNNING', 'WAITING_FOR_USER', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'
    )),
    CONSTRAINT ck_agent_session_active CHECK (status = 'CREATED' OR active_checkpoint_id IS NOT NULL),
    CONSTRAINT ck_agent_session_deadline CHECK (
        (status = 'RUNNING' AND execution_deadline_at IS NOT NULL)
        OR (status <> 'RUNNING' AND execution_deadline_at IS NULL)
    )
);

-- A FAILED attempt is recoverable; cancel it explicitly before starting a new one.
CREATE UNIQUE INDEX uq_agent_session_open_assessment
    ON agent_session (assessment_id)
    WHERE status IN ('CREATED', 'RUNNING', 'WAITING_FOR_USER', 'PAUSED', 'FAILED');
CREATE INDEX idx_agent_session_user_updated ON agent_session (user_id, updated_at DESC);

CREATE TABLE agent_step (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    step_no                 integer NOT NULL,
    parent_step_id          uuid,
    parent_step_no          integer,
    execution_generation    bigint NOT NULL,
    step_type               varchar(40) NOT NULL,
    status                  varchar(20) NOT NULL DEFAULT 'STARTED',
    client_operation_id     uuid,
    payload_fingerprint     char(64),
    question_key            varchar(80),
    question                jsonb,
    answer                  jsonb,
    input_references        jsonb NOT NULL DEFAULT '{}'::jsonb,
    extracted_facts         jsonb NOT NULL DEFAULT '{}'::jsonb,
    decision                jsonb NOT NULL DEFAULT '{}'::jsonb,
    contradiction_codes     jsonb NOT NULL DEFAULT '[]'::jsonb,
    user_visible_message    text,
    started_at              timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz,
    CONSTRAINT uq_agent_step_no UNIQUE (session_id, step_no),
    CONSTRAINT uq_agent_step_session_id UNIQUE (session_id, id),
    CONSTRAINT uq_agent_step_parent_target UNIQUE (session_id, id, step_no),
    CONSTRAINT uq_agent_step_operation UNIQUE (session_id, client_operation_id),
    CONSTRAINT ck_agent_step_no CHECK (step_no > 0),
    CONSTRAINT ck_agent_step_generation CHECK (execution_generation >= 0),
    -- The parent must be an older node in THIS session: no cross-session edge or cycle.
    CONSTRAINT fk_agent_step_parent FOREIGN KEY (session_id, parent_step_id, parent_step_no)
        REFERENCES agent_step (session_id, id, step_no),
    CONSTRAINT ck_agent_step_parent CHECK (
        (parent_step_id IS NULL AND parent_step_no IS NULL)
        OR (parent_step_id IS NOT NULL AND parent_step_no IS NOT NULL
            AND parent_step_no > 0 AND parent_step_no < step_no)
    ),
    CONSTRAINT ck_agent_step_operation CHECK (
        (client_operation_id IS NULL AND payload_fingerprint IS NULL)
        OR (client_operation_id IS NOT NULL AND payload_fingerprint IS NOT NULL
            AND payload_fingerprint ~ '^[0-9a-f]{64}$')
    ),
    CONSTRAINT ck_agent_step_type CHECK (step_type IN (
        'USER_INPUT', 'EXTRACT_INFORMATION', 'NARROW_DOWN',
        'DETECT_CONTRADICTION', 'REQUEST_CONFIRMATION', 'OUTPUT_RESULT'
    )),
    CONSTRAINT ck_agent_step_status CHECK (status IN (
        'STARTED', 'WAITING', 'COMPLETED', 'FAILED', 'DISCARDED'
    )),
    CONSTRAINT ck_agent_step_question CHECK (question IS NULL OR jsonb_typeof(question) = 'object'),
    CONSTRAINT ck_agent_step_answer CHECK (
        step_type <> 'USER_INPUT'
        OR (client_operation_id IS NOT NULL AND question_key IS NOT NULL
            AND answer IS NOT NULL AND answer <> 'null'::jsonb)
    ),
    CONSTRAINT ck_agent_step_input CHECK (jsonb_typeof(input_references) = 'object'),
    CONSTRAINT ck_agent_step_facts CHECK (jsonb_typeof(extracted_facts) = 'object'),
    CONSTRAINT ck_agent_step_decision CHECK (jsonb_typeof(decision) = 'object'),
    CONSTRAINT ck_agent_step_contradictions CHECK (jsonb_typeof(contradiction_codes) = 'array')
);
CREATE INDEX idx_agent_step_children ON agent_step (session_id, parent_step_id, step_no);

CREATE TABLE agent_checkpoint (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    step_id                 uuid,
    checkpoint_no           integer NOT NULL,
    state_schema_version    integer NOT NULL DEFAULT 1,
    state                   jsonb NOT NULL,
    state_checksum          char(64) NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_checkpoint_no UNIQUE (session_id, checkpoint_no),
    CONSTRAINT uq_agent_checkpoint_session_id UNIQUE (session_id, id),
    CONSTRAINT uq_agent_checkpoint_step UNIQUE (session_id, step_id),
    CONSTRAINT fk_agent_checkpoint_step FOREIGN KEY (session_id, step_id)
        REFERENCES agent_step (session_id, id),
    CONSTRAINT ck_agent_checkpoint_no CHECK (checkpoint_no > 0),
    CONSTRAINT ck_agent_checkpoint_schema CHECK (state_schema_version > 0),
    CONSTRAINT ck_agent_checkpoint_state CHECK (jsonb_typeof(state) = 'object'),
    CONSTRAINT ck_agent_checkpoint_checksum CHECK (state_checksum ~ '^[0-9a-f]{64}$')
);
-- One initial snapshot per session. Multiple parent=NULL steps may branch from it.
CREATE UNIQUE INDEX uq_agent_checkpoint_initial ON agent_checkpoint (session_id) WHERE step_id IS NULL;

ALTER TABLE agent_session
    ADD CONSTRAINT fk_agent_session_active_checkpoint
        FOREIGN KEY (id, active_checkpoint_id) REFERENCES agent_checkpoint (session_id, id),
    ADD CONSTRAINT fk_agent_session_source_checkpoint
        FOREIGN KEY (source_session_id, source_checkpoint_id)
        REFERENCES agent_checkpoint (session_id, id);

CREATE TABLE model_call (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id              uuid NOT NULL REFERENCES agent_session(id) ON DELETE CASCADE,
    step_id                 uuid,
    base_checkpoint_id      uuid NOT NULL,
    call_no                 integer NOT NULL,
    execution_generation    bigint NOT NULL,
    provider                varchar(80) NOT NULL,
    model_name              varchar(120) NOT NULL,
    purpose                 varchar(80) NOT NULL,
    status                  varchar(20) NOT NULL DEFAULT 'STARTED',
    provider_request_id     varchar(160),
    trace_id                varchar(160),
    latency_ms              integer,
    input_tokens            integer,
    output_tokens           integer,
    total_tokens            integer,
    error_category          varchar(80),
    error_code              varchar(120),
    request_fingerprint     char(64),
    response_metadata       jsonb NOT NULL DEFAULT '{}'::jsonb,
    started_at              timestamptz NOT NULL DEFAULT now(),
    completed_at            timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_model_call_no UNIQUE (session_id, call_no),
    CONSTRAINT fk_model_call_step FOREIGN KEY (session_id, step_id)
        REFERENCES agent_step (session_id, id),
    CONSTRAINT fk_model_call_base_checkpoint FOREIGN KEY (session_id, base_checkpoint_id)
        REFERENCES agent_checkpoint (session_id, id),
    CONSTRAINT ck_model_call_no CHECK (call_no > 0),
    CONSTRAINT ck_model_call_generation CHECK (execution_generation >= 0),
    CONSTRAINT ck_model_call_status CHECK (status IN ('STARTED', 'SUCCEEDED', 'FAILED', 'DISCARDED')),
    CONSTRAINT ck_model_call_latency CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_model_call_input_tokens CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT ck_model_call_output_tokens CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT ck_model_call_total_tokens CHECK (total_tokens IS NULL OR total_tokens >= 0),
    CONSTRAINT ck_model_call_fingerprint CHECK (
        request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_model_call_metadata CHECK (jsonb_typeof(response_metadata) = 'object')
);
-- At most one in-flight call in an execution generation. A new generation may
-- take over after a timeout while the provider still processes the old request.
CREATE UNIQUE INDEX uq_model_call_inflight_generation
    ON model_call (session_id, execution_generation) WHERE status = 'STARTED';
CREATE INDEX idx_model_call_session_created ON model_call (session_id, created_at DESC);
CREATE INDEX idx_model_call_provider_request
    ON model_call (provider, provider_request_id) WHERE provider_request_id IS NOT NULL;

-- FUTURE / OPTIONAL RAG. No Agent state transition depends on these two tables.
CREATE TABLE knowledge_document (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title               varchar(300) NOT NULL,
    source_type         varchar(40) NOT NULL,
    source_uri          text,
    content_version     varchar(80),
    content_sha256      char(64) NOT NULL,
    status              varchar(20) NOT NULL DEFAULT 'ACTIVE',
    metadata            jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_document_content UNIQUE (content_sha256),
    CONSTRAINT ck_knowledge_document_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_knowledge_document_hash CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_document_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE TABLE knowledge_chunk (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id             uuid NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
    chunk_no                integer NOT NULL,
    content                 text NOT NULL,
    token_count             integer,
    embedding_model         varchar(120),
    embedding_dimensions    integer,
    embedding               vector,
    metadata                jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_knowledge_chunk_no UNIQUE (document_id, chunk_no),
    CONSTRAINT ck_knowledge_chunk_no CHECK (chunk_no >= 0),
    CONSTRAINT ck_knowledge_chunk_tokens CHECK (token_count IS NULL OR token_count >= 0),
    CONSTRAINT ck_knowledge_chunk_dimensions CHECK (
        (embedding_model IS NULL AND embedding_dimensions IS NULL AND embedding IS NULL)
        OR (embedding_model IS NOT NULL AND embedding_dimensions IS NOT NULL
            AND embedding_dimensions > 0
            AND (embedding IS NULL OR vector_dims(embedding) = embedding_dimensions))
    ),
    CONSTRAINT ck_knowledge_chunk_metadata CHECK (jsonb_typeof(metadata) = 'object')
);

COMMENT ON TABLE agent_session IS
    'FUTURE: one bounded analysis attempt. One active checkpoint, with older branches retained.';
COMMENT ON COLUMN agent_session.assessment_version IS
    'History version captured with the input snapshot for provenance, not an end-of-run equality check: history answers also change version.';
COMMENT ON COLUMN agent_session.input_snapshot IS
    'Immutable initial input: symptoms, location, picture IDs/dimensions/view_state, annotation IDs/revision/shapes and user text. Never use expiring signed URLs as durable image identity.';
COMMENT ON COLUMN agent_session.active_checkpoint_id IS
    'Only source of current state. The checkpoint belongs to this session and identifies its active step. Initialize in one transaction: session, initial checkpoint, pointer.';
COMMENT ON COLUMN agent_session.source_checkpoint_id IS
    'Optional provenance when reanalyzing a completed/cancelled task in a new session. Application must validate same assessment/user and authorize the source.';
COMMENT ON COLUMN agent_session.last_step_no IS
    'Highest allocated step number across ALL branches, including failed/discarded nodes. Not current path depth. Never decrease on restore.';
COMMENT ON COLUMN agent_session.version IS
    'Optimistic concurrency version of session commands. Never restored from a checkpoint.';
COMMENT ON COLUMN agent_session.execution_generation IS
    'Invalidate in-flight work on pause, branch switch, model change or expired-run takeover. Late replies cannot advance state.';
COMMENT ON COLUMN agent_session.execution_deadline_at IS
    'RUNNING deadline for crash recovery. Expiry does not auto-update status; a future service must lock and take over with a new generation.';
COMMENT ON COLUMN agent_session.total_model_calls IS
    'Reserve before sending each model request, in a short local transaction. Failures and abandoned calls still consume budget; restores never reset it.';
COMMENT ON COLUMN agent_session.max_steps IS
    'Whole-session node budget, including discarded branches. Configurable limit, not medical advice or model reasoning depth.';

COMMENT ON TABLE agent_step IS
    'FUTURE: meaningful user/Agent decisions in a tree. Seal WAITING/COMPLETED nodes; corrections append siblings, never overwrite old answers.';
COMMENT ON COLUMN agent_step.parent_step_no IS
    'Deliberate duplicate of parent number. With composite FK and smaller-than-child CHECK this prevents cycles without a graph engine.';
COMMENT ON COLUMN agent_step.client_operation_id IS
    'User-answer idempotency key. Hash includes the starting checkpoint, question identity and answer. Replays do not reactivate old branches.';
COMMENT ON COLUMN agent_step.question IS
    'User-visible question/choices emitted by this step; USER_INPUT.answer answers the question in its parent checkpoint.';
COMMENT ON COLUMN agent_step.decision IS
    'Structured decision codes and concise user-facing reasons, never hidden chain-of-thought or raw model messages.';

COMMENT ON TABLE agent_checkpoint IS
    'FUTURE: immutable full business state after a sealed step; step_id NULL is the initial state. Session pointer replaces is_current flags.';
COMMENT ON COLUMN agent_checkpoint.state IS
    'Recoverable facts, pending question, active visual/annotation snapshot and outcome candidate. Excludes version/generation/budget counters, secrets and signed URLs.';
COMMENT ON COLUMN agent_checkpoint.state_checksum IS
    'SHA-256 of application-canonicalized state; future service calculates it, not a hash of arbitrary JSON text.';

COMMENT ON TABLE model_call IS
    'FUTURE: reserve STARTED before remote I/O; no transaction held during model request. Store timing/usage/errors, not full prompts or responses.';
COMMENT ON COLUMN model_call.base_checkpoint_id IS
    'Call starting state. Accept a reply only if session is RUNNING, generation and active checkpoint still match, in one local transaction.';
COMMENT ON COLUMN model_call.status IS
    'DISCARDED means a stale execution was not adopted, not that the provider failed. Keep returned usage/latency; provider errors may be retained as error metadata.';
COMMENT ON COLUMN model_call.response_metadata IS
    'Allowlisted provider metadata only. No raw prompts, responses, symptom descriptions, credentials or signed image URLs.';

COMMENT ON TABLE knowledge_document IS 'FUTURE / OPTIONAL: a small reviewed knowledge source, not a knowledge-base platform.';
COMMENT ON COLUMN knowledge_chunk.embedding IS
    'FUTURE vector dimension selected with embedding model. No vector client or ANN index yet; add dimension-specific indexing only when retrieval is actually implemented.';

COMMIT;
