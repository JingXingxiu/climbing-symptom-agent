# Design notes for later study

These notes preserve decisions and questions without changing the fixed four-service boundary.

## Accepted decisions

### DN-001 — Visual sources share one annotation model

**Decision:** 3D snapshots, system standard images and user uploads all become a `Picture` with an `AnnotationSet`.

**Why:** one editor, one persistence shape and one future multimodal input format. The 3D scene remains responsible only for coarse selection and capture.

**Consequence:** capture quality and orientation are part of the critical path. Camera metadata is useful for reproduction, but triangle IDs are not primary business semantics.

### DN-002 — Coarse anatomy is independent of the mesh

**Decision:** stable `regionId` values map to a separate hotspot layer.

**Why:** mesh decimation or asset replacement must not invalidate historical records.

**Consequence:** hotspot placement is versioned and visually tested. It is not claimed to be a clinical anatomical atlas.

### DN-003 — Two PostgreSQL servers, four ownership domains

**Decision:** business PostgreSQL contains three logical databases; AI gets a separate pgvector-capable instance.

**Why:** it demonstrates service data ownership without the operational noise of four PostgreSQL servers, while keeping experimental AI/vector load isolated.

**Consequence:** no cross-database foreign keys. Missing external references are handled through APIs, reconciliation or UI states later, not SQL joins.

### DN-004 — AI schema is a review artifact

**Decision:** design the first AI tables now but mark the migration `FUTURE / NOT APPLIED`.

**Why:** preserve a plausible direction for session/step/checkpoint/model-call/RAG data without letting an unlearned Agent design constrain the Spring Cloud phase.

**Consequence:** the schema must be re-reviewed before use. pgvector availability does not make RAG a dependency.

### DN-005 — Local deterministic Agent Mock

**Decision:** the first frontend uses a finite local Mock behind an API port.

**Why:** it demonstrates narrow-down, contradiction clarification, completion and resume before a model is introduced.

**Consequence:** UI state must not assume arbitrary chat messages or an infinite conversation. The future HTTP adapter maps to the OpenAPI Agent DTOs.

### DN-006 — Symptom-centered domain language

**Decision:** the aggregate is `Assessment`, its coarse position is `SymptomLocation`, and pain is one value in a multi-select `SymptomType` vocabulary rather than the aggregate's identity.

**Why:** users may primarily report aching, distension, numbness, swelling, weakness, stiffness, or an uncertain sensation without describing it as pain.

**Consequence:** UI copy, TypeScript, HTTP DTOs, Mock data and PostgreSQL use the same neutral terminology. The initial controlled vocabulary is descriptive only and must not be presented as a diagnosis.

## Real questions to revisit during the Spring Cloud phase

These are learning topics, not blockers for the frontend delivery:

1. **Workflow orchestration owner.** Who starts an Agent session after Assessment submission, and who commits the final `history-service` result? The contract keeps AI outcome and history result distinct so this can be learned rather than hidden in the scaffold.
2. **External-reference validation.** At what points should `history-service` verify `picture_id` ownership/readiness, and what degraded state should be shown when image-service is unavailable?
3. **Authentication propagation.** Should user identity be forwarded as a verified token, gateway-injected claims, or another pattern? Do not trust a client-supplied `user_id`.
4. **Retry safety.** Which reads and writes are retryable, what is the timeout budget, and where does `X-Idempotency-Key` live? A generic retry on every POST would be unsafe.
5. **Image repair operation.** Since lost image-processing messages are accepted, define a manual/status-driven re-enqueue operation before relying on thumbnails in a demo.
6. **Final-result provenance.** Preserve whether a result was Mock, rule-based or AI-reviewed, and decide what evidence is user-visible without exposing private prompts or chain-of-thought.

## Technical risks that can change implementation effort

- `three-platformize` is proven but old. New WeChat runtime changes, iOS WebGL memory pressure and screenshot orientation/antialias behavior require real-device testing.
- The converted GLB and adapter code both count toward mini-program package constraints. Subpackages or asset delivery may become necessary if the full frontend grows.
- Canvas event coordinates must be corrected for object-fit/letterboxing before normalization; otherwise annotations move when reloaded at a different aspect ratio.
- Temporary mini-program file paths are not durable. A 3D capture or user photo must be copied/uploaded before the session ends; history should retain a picture ID, not a temp path.
- MinIO server licensing and maintenance posture deserve a fresh review before any deployment beyond a local learning environment.
- Model output cannot be treated as a safety guarantee. Urgent red-flag wording and bounded result categories need human review before public use.
