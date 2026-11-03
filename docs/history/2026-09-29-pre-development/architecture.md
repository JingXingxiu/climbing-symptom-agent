# Architecture and design notes

## 1. Product boundary

This project is a learning project and a portfolio-ready Java backend project. Its MVP helps a climber describe hand symptoms or discomfort more precisely. Pain is one possible symptom rather than the center of the domain:

- where the discomfort is located;
- which symptoms are present, with multi-select support for pain, aching, distension, numbness, swelling, weakness, stiffness, and other/uncertain;
- when the symptoms started;
- which movement or situation makes them most obvious;
- whether it interferes with ordinary hand use;
- whether the current information suggests seeking professional help.

It is **not a diagnosis system**. Every result must distinguish observed user input from an inference, disclose uncertainty, and retain a visible notice that urgent or worsening symptoms need appropriate professional help.

The Agent is a bounded task flow, not an unlimited chatbot. It either narrows missing details, resolves ambiguity or contradictions, or produces a result after a finite number of meaningful steps.

## 2. Fixed service ownership

| Service | Owns | Does not imply |
| --- | --- | --- |
| `base-service` | WeChat login, account, basic user profile | Ownership of assessment or image records |
| `image-service` | MinIO upload, picture metadata, normalized annotation, processing status; later RabbitMQ image processing | Ownership of an Assessment |
| `history-service` | Assessment, symptom types, coarse symptom location, visual references, dynamic answers, history/resume state, final business result | Direct access to another service's database |
| `ai-service` | Future model calls, bounded Agent session/steps/checkpoints, optional future RAG | Current MVP dependency on RAG or an already implemented real Agent |

This is an ownership map, not a completed call graph. The Spring Cloud learning phase will decide Gateway routes, OpenFeign direction, timeouts, retries, Sentinel rules, fallback behavior and MQ orchestration. The current repository deliberately does not pre-implement those decisions.

## 3. Data topology

Two PostgreSQL **server instances** are used:

```text
Business PostgreSQL                         AI PostgreSQL + pgvector
├── base_db       <- base-service           └── ai_db <- ai-service (FUTURE)
├── image_db      <- image-service
└── history_db    <- history-service
```

Each database is private to its owning service. A row may retain another service's UUID as a business reference, but there are no cross-database foreign keys and no cross-service SQL joins. For example, `history_db` can hold `user_id` and `picture_id`, while their authoritative records remain in `base_db` and `image_db`.

The active schemas are in `database/base_db`, `database/image_db`, and `database/history_db`. The file under `database/future/ai_db` is explicitly **FUTURE / NOT APPLIED**. The AI PostgreSQL runtime has pgvector available, but the current Java services and frontend must not depend on its future tables.

For local development, these two database containers, MinIO, RabbitMQ, Nacos and Sentinel Dashboard share one Compose project/network. Nacos standalone keeps its own embedded metadata and does not become a fourth application database. Sentinel Dashboard has no persistence volume because its current learning-stage rules and monitoring data are intentionally ephemeral. Supplying these containers does not decide the later service registration, configuration, call, timeout, retry, circuit-breaker or fallback design.

## 4. Unified visual input

The three visual routes share one domain abstraction:

```text
Picture / Visual Source
├── MODEL_SNAPSHOT   fixed view captured from the 3D hand
├── STANDARD_REFERENCE   bundled palm, dorsal, or side image
└── USER_UPLOAD      one of the user's hand photos
        │
        └── AnnotationSet (normalized image coordinates + optional text)
```

The MinIO object is the original or a derivative picture. PostgreSQL stores its object metadata, processing state, business references, and annotation. A short-lived signed URL is a delivery detail and must not become the persistent business identity.

Annotation coordinates use the original image's top-left as `(0, 0)` and bottom-right as `(1, 1)`. A set also stores the source pixel dimensions used while editing. Freehand points, points, ellipses and polygons can therefore be reconstructed at a different display resolution. The original is immutable; a red ring is not permanently burned into the only copy. A flattened annotated preview may exist as a derivative, but never replaces the source plus structured annotation.

## 5. Two-level 3D location

The 3D model is an expression aid, not a triangle-level medical ontology.

1. The coarse level selects a stable, readable `regionId`, for example `hand.right.middle.pip`. A separate hotspot layer maps interaction volumes to these IDs and highlights the selected region in red. It is intentionally independent of model triangle numbering.
2. The precise level lets the user rotate and zoom, freeze the view, capture it as a picture, then use the same 2D annotation editor as every other visual source.

The capture retains useful camera/model-version metadata. Mesh or raycast details may be retained for implementation diagnostics, but AI input should primarily use the coarse location, snapshot, normalized annotation and user text.

Left/right is a business property. A mirrored render must still emit a side-specific region ID; it must never infer side solely from a negative scale in the scene graph.

## 6. Current Mock and future HTTP boundary

The frontend depends on an `AssessmentApi` port. Its default Mock adapter persists demo data locally and drives a deterministic finite Agent flow. The HTTP adapter is the replacement seam for Spring Cloud endpoints. Pages and components should not import `uni.request` directly.

The Mock demonstrates three important states:

- normal input leads to a narrowing question;
- conflicting text/location or ambiguous visual input leads to confirmation;
- enough information leads to a bounded, non-diagnostic result.

The OpenAPI contract in `docs/api/openapi.yaml` is the canonical network contract. Internal frontend objects do not need to mirror wire DTOs one-for-one; the HTTP adapter is responsible for mapping.

## 7. Image processing and simple idempotency

The intended RabbitMQ message contains at least `pictureId` and a processing generation. Processing may standardize orientation, resize, produce a thumbnail, remove EXIF data, or later prepare an AI-safe derivative.

This learning-stage design accepts an occasional lost processing message and deliberately excludes Outbox, Inbox and distributed transactions. Duplicate delivery must still be harmless:

- acquire work only when picture status and generation match the expected values;
- derive deterministic MinIO keys;
- use a unique `(picture_id, variant_type, processing_generation)` constraint;
- transition only the matching generation to complete;
- make upload and side-effecting HTTP creation accept a stable idempotency key.

These rules are a data contract, not an implementation of the consumer or producer.

## 8. Privacy and retention notes

- Hand photos and free text may be sensitive. Keep MinIO buckets private and expose only short-lived signed URLs.
- Remove unnecessary EXIF metadata from derivatives; define original retention explicitly before a production deployment.
- Do not log access tokens, full image URLs, raw prompts, raw model responses, or unrestricted checkpoint JSON.
- `model_call` records should keep provider/model/latency/token/error metadata. Full Prompt/Response retention is opt-in, redacted, and time-bounded rather than the default.
- Account deletion, picture deletion and Assessment retention need a product policy before this becomes more than a portfolio demo.

## 9. Decisions intentionally deferred

- Exact service-to-service call direction and whether the client or a backend coordinator advances the workflow.
- Gateway authentication propagation and token design.
- OpenFeign, timeout, retry, circuit breaker and fallback policy.
- RabbitMQ exchange/queue topology and manual reprocessing operation.
- Agent framework, model provider, model selection and checkpoint implementation.
- Embedding model and dimension, retrieval evaluation and vector index choice.

Deferring these is deliberate: they are the core Spring Cloud and Agent learning work, not missing frontend infrastructure.
