# API contract notes

`openapi.yaml` is an OpenAPI 3.1 contract organized by explicit `x-service-owner` values. It describes externally visible capabilities, not a mandatory service-call graph.

## Browser API documentation

Swagger UI is part of the same local Compose group as the databases and middleware. From `infra/`:

```powershell
docker compose up -d api-docs
```

Open `http://localhost:18081`. The UI reads the repository's `openapi.yaml` through a read-only bind mount, so refreshing the page shows contract edits without copying the file into a Java module. Operation IDs are visible, schemas can be expanded, and `Try it out` targets the local Gateway at `http://localhost:8080` once it is running and allows the local Swagger UI origin through CORS.

Swagger UI is a viewer and request client; `openapi.yaml` remains the source of truth. Do not generate or hand-edit a second independent API document in each service.

## Ownership summary

| Prefix | Owner | Current delivery status |
| --- | --- | --- |
| `/api/base/v1` | base-service | Java transport template exists; application implementation is TODO and currently returns explicit `501` |
| `/api/image/v1` | image-service | Java transport template exists; storage/application implementation is TODO and currently returns explicit `501` |
| `/api/history/v1` | history-service | Java transport template exists; Assessment implementation is TODO and currently returns explicit `501` |
| `/api/ai/v1` | ai-service | **FUTURE_NOT_IMPLEMENTED**; frontend finite Mock implements equivalent behavior locally |

The eventual Gateway may keep these public paths or map them to internal service addresses. Internal discovery names and direct service ports do not belong in the client contract.

## Visual terminology

- `Picture` is the image-service-owned object metadata and delivery view.
- `VisualSourceType` says how the image originated: 3D snapshot, bundled standard reference, or user upload.
- `AnnotationSet` is one versioned set of normalized shapes for a picture.
- `VisualReference` is history-service's business reference to a picture and optional annotation set. It is not a database relationship.
- `SymptomLocation.regionId` is the coarse anatomical business vocabulary. It locates a reported symptom without assuming that every report is pain.
- `SymptomType` is a multi-select vocabulary (`PAIN`, `ACHING`, `DISTENSION`, `NUMBNESS`, `SWELLING`, `WEAKNESS`, `STIFFNESS`, `OTHER_UNCERTAIN`); it records the user's expression, not a diagnosis.

## Mock-to-wire mapping

The frontend Mock is allowed to use a convenient aggregate in local storage. The HTTP adapter explicitly maps:

- local `Assessment` to history-service `Assessment` plus its sub-resources;
- a local temporary or standard picture to an image-service multipart upload and returned `pictureId`;
- canvas shapes to `AnnotationSet.shapes` in `NORMALIZED_0_1` space (documented as top-left origin in the contract);
- the finite Mock state to `AgentSessionView` semantics;
- Mock outcome to the history result display model.

An empty Assessment draft may be created before first-input validation. The
required hand/onset/activity/impact fields are enforced when the user advances
or submits, matching the nullable draft columns in `history_db`.

Pages must call the adapter port, never construct `/api/...` URLs themselves.

## Compatibility rules

- This is still the undeployed `0.1.0` contract, so earlier pain-specific draft terminology was replaced directly rather than preserved as a misleading compatibility alias.
- Breaking wire changes increment the major path or contract major version.
- Adding an optional field or enum value still requires resilient clients; clients must not crash on an unknown display-only value.
- Persist stable IDs and enum codes, not translated Chinese labels.
- Mutating requests use UUID idempotency keys where the contract requires them.
- Optimistic versions are server-issued integers. A client never resolves a conflict by silently overwriting a newer version.
- Visual-reference replacement checks the current Assessment version but does not increment it; this keeps the returned list response sufficient and avoids an unknowable client version after a child-resource PUT.
- `application/problem+json.code` is stable for programmatic handling; `title` and `detail` are user/developer text.

## Security note

`userId` in returned resources is informational. Ownership always comes from verified authentication context, never from a user ID supplied in a request body. Signed MinIO URLs are short-lived bearer material and should be kept out of logs and durable frontend storage.
