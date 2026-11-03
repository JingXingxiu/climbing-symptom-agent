-- history-service only. Apply after V001 while connected to history_db.
-- Navigation may be saved by clients; it is not proof that submission succeeded.
-- Do not infer/backfill a submission merely because current_step_key = 'AGENT'.

BEGIN;

ALTER TABLE assessment
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN last_submit_version integer,
    ADD CONSTRAINT ck_assessment_submission_receipt CHECK (
        (submitted_at IS NULL AND last_submit_version IS NULL)
        OR (submitted_at IS NOT NULL AND last_submit_version IS NOT NULL
            AND last_submit_version > 0 AND last_submit_version < version)
    );

COMMENT ON COLUMN assessment.submitted_at IS
    'Time the last submission of local assessment data was accepted; not an AI completion time.';
COMMENT ON COLUMN assessment.last_submit_version IS
    'Expected root version of the last accepted submit request. Replays must not bump version or restart work.';

COMMIT;
