# Database ownership and scope

| PostgreSQL instance | Database | Owner | Current status |
| --- | --- | --- | --- |
| Business | `base_db` | base-service | Apply in current stage |
| Business | `image_db` | image-service | Apply in current stage |
| Business | `history_db` | history-service | Apply in current stage |
| Business | `payment_db` | payment-service | New; apply V001 explicitly |
| AI / pgvector | `ai_db` | ai-service | **FUTURE / NOT APPLIED** |

Each service owns its data. References such as `history_db.assessment.user_id`, `history_db.assessment_visual.picture_id`, and `image_db.annotation_set.assessment_id` are business IDs only. There are no cross-database foreign keys.

## Current schemas

- `base_db`: account, login identity, and basic profile.
- `image_db`: MinIO object metadata, deterministic derivatives, annotation sets, and normalized annotation shapes.
- `history_db`: neutral assessment state, a small multi-select symptom vocabulary, coarse symptom regions, visual references, finite-workflow answers, and the final non-diagnostic result.
- `payment_db`: purchasable plans, immutable order terms and current user entitlements. Order payment and entitlement renewal share one local transaction; AI usage remains owned by ai-service.

The first symptom vocabulary is intentionally small and queryable. An Assessment may select any combination of `PAIN`, `ACHING`, `DISTENSION`, `NUMBNESS`, `SWELLING`, `WEAKNESS`, `STIFFNESS`, and `OTHER_UNCERTAIN` through `assessment_symptom`; optional wording belongs in `detail_note`. Location is modeled separately in `symptom_location`, so a location does not falsely imply that every reported sensation is pain.

Stable searchable fields are normal columns. JSONB is limited to data whose shape is genuinely flexible: normalized annotation geometry, saved 3D camera/view state, dynamic answers, and structured result lists.

The application is responsible for updating `updated_at` and optimistic `version`/`revision` columns. API validation must enforce that annotation coordinates remain within `[0,1]`; the database verifies the JSON container shape but does not attempt to encode every geometry variant as a SQL constraint.

## Agent schema (not applied)

`future/ai_db/V001__FUTURE_NOT_APPLIED_ai_schema.sql` has been implemented against by ai-service, but remains **NOT APPLIED** and is never run automatically. The historical path is retained so learning links stay valid. It models:

- bounded Agent sessions;
- meaningful task steps (not every internal tool/function call);
- resumable JSONB checkpoints with stable query fields kept on the session;
- privacy-conscious model call metadata without default full Prompt/Response retention;
- optional document/chunk storage used by the read-only RAG implementation.

The schema includes explicit pause/resume, parent-linked branches, immutable input/checkpoint snapshots, a single active-checkpoint pointer, execution generations for stale replies, and whole-session budgets. Read [the learning guide](../docs/agent-database-learning.md) and [Service reading guide](../docs/agent-service-reading.md) before enabling persistence. RAG is optional and disabled by default; its code is implemented, while these tables remain NOT APPLIED. No reference corpus is included.

`future/ai_db/V002__FUTURE_NOT_APPLIED_user_daily_usage.sql` is also **FUTURE / NOT APPLIED**. It adds only `ai_user_daily_usage`, keyed by `(user_id, usage_date)`. The AI service atomically reserves daily model-call quota in the same transaction as Session/ModelCall reservation. Each generation, reviewer, correction and re-review request reserves one unit; this is not a provider invoice and a reserved call may fail before remote delivery. Dates use Asia/Shanghai; failures after a committed reservation still consume quota. Payment supplies the current limit through API; purchasing a plan never resets this table. Apply V001 then V002 explicitly after review; neither is part of Docker bootstrap.

Optional final-result review reuses existing columns and needs no additional SQL migration. `model_call.purpose` distinguishes `NEXT_SYMPTOM_STEP`, `QUALITY_REVIEW`, and `RESULT_REVISION`; bounded verdict/issue codes belong in the existing `response_metadata` JSONB. Checkpoint state may hold `qualityReview` with verdict, review-call ID, correction count and issue codes. These are business review summaries, not raw prompts or hidden reasoning. `SUCCEEDED` describes one valid model response; final adoption must also consider Step links and review metadata. See [the review guide](../docs/agent-review.md).

`history_db/V003__assessment_result_revision.sql` is also **NOT APPLIED**. It adds result history while leaving the current `assessment_result` table and existing API reads unchanged. It is not included in the existing V001/V002 setup commands; review and apply it explicitly when enabling Agent finalization. The new History service writes both tables in one local transaction, checks stable input fingerprints and rejects late results from older attempts.

`history_db/V004__assessment_result_references.sql` is **NOT APPLIED**. Review and apply it after V003 before using the updated History service. It adds bounded JSONB source snapshots to the current and revision result tables, preserving references from AI finalization without cross-database foreign keys. Existing records receive empty arrays. The new application expects these columns; deploy this migration with the updated code.

The `embedding` column intentionally uses unconstrained `vector` and has no ANN index. It may remain empty after the schema is applied. The implemented exact retrieval matches `provider:model` and dimensions in a materialized CTE before evaluating cosine distance. An empty corpus returns EMPTY without calling the embedding provider; an unapplied schema is UNAVAILABLE. See [RAG configuration](../docs/rag.md). The Agent flow remains usable with RAG disabled.
