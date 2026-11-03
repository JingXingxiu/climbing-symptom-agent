-- NOT APPLIED. Review and apply manually after history_db V001, V002 and V003.
-- Source snapshots belong to History; knowledge IDs remain external business IDs.
BEGIN;

ALTER TABLE assessment_result
    ADD COLUMN source_references jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_assessment_result_source_references
        CHECK (jsonb_typeof(source_references) = 'array' AND jsonb_array_length(source_references) <= 20);

ALTER TABLE assessment_result_revision
    ADD COLUMN source_references jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_assessment_revision_source_references
        CHECK (jsonb_typeof(source_references) = 'array' AND jsonb_array_length(source_references) <= 20);

COMMENT ON COLUMN assessment_result.source_references IS
    'Frozen retrieved references (title, optional public URL, excerpt, sourceType, documentId, chunkId, contentVersion). Not a claim that the model cited every source. Empty for legacy/unreferenced results; no signed picture URL or hidden reasoning.';
COMMENT ON COLUMN assessment_result_revision.source_references IS
    'Immutable source provenance for this result revision. External AI knowledge identifiers have no cross-database foreign key.';

COMMIT;
