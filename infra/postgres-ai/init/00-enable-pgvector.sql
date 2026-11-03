-- Environment bootstrap only; this is not the FUTURE Agent schema migration.
-- The pgvector image already contains the extension binaries. This enables the
-- extension in ai_db so the environment is ready for a later, explicit review.
CREATE EXTENSION IF NOT EXISTS vector;
