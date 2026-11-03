-- ============================================================================
-- FUTURE / NOT APPLIED: review and apply explicitly to ai_db.
-- No automatic migration, initializer or scheduler applies this file.
-- Daily execution attempts are shared across every session and AI instance.
-- usage_date is assigned by AI in Asia/Shanghai; a new date uses a new row.
-- Payment owns current limits; renewal never clears this usage table.
-- ============================================================================

BEGIN;

CREATE TABLE ai_user_daily_usage (
    user_id          uuid NOT NULL,
    usage_date       date NOT NULL,
    used_model_calls integer NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_ai_user_daily_usage PRIMARY KEY (user_id, usage_date),
    CONSTRAINT ck_ai_user_daily_usage_nonnegative CHECK (used_model_calls >= 0)
);

COMMENT ON TABLE ai_user_daily_usage IS
    'Committed AI execution attempts by user and Asia/Shanghai business date; no cross-service FK';
COMMENT ON COLUMN ai_user_daily_usage.used_model_calls IS
    'Reserved with session and ModelCall in one transaction; committed failures and discarded responses count';

COMMIT;
