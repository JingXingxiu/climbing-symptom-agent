# Development guide

## 1. Repository map

```text
frontend/                    Vue 3 + uni-app + TypeScript mini-program
database/
  base_db/                   active base-service schema
  image_db/                  active image-service schema
  history_db/                active history-service schema
  future/ai_db/              FUTURE / NOT APPLIED review SQL
infra/                       one Compose group: 2x PostgreSQL, MinIO, RabbitMQ, Nacos, Sentinel, Swagger UI
docs/api/openapi.yaml        canonical HTTP contract
tools/process_hand_asset.py  reproducible 3D conversion/render helper
```

The repository currently contains no Java service implementation. The Terra prompt in this directory describes a later backend skeleton task without taking over the user's Spring Cloud design exercises.

## 2. Run the frontend

Requirements:

- Node.js 20 or later;
- pnpm 11 (the exact package-manager declaration is in `frontend/package.json`);
- current WeChat DevTools for device and simulator verification.

From `frontend/`:

```powershell
pnpm install
pnpm run type-check
pnpm run dev:mp-weixin
```

Import `frontend/dist/dev/mp-weixin` into WeChat DevTools. The checked-in `touristappid` is for local demonstration only; replace it through the normal private/local configuration path for a real account rather than committing a secret AppID.

For a release-shaped build:

```powershell
pnpm run build:mp-weixin
```

The default data mode is Mock and needs no backend:

```text
VITE_API_MODE=mock
```

To exercise the network adapter, set `VITE_API_MODE=http` and `VITE_API_BASE_URL` in a local uncommitted environment file. `src/api/http.ts` maps the frontend aggregate to the four service-owned resources in `docs/api/openapi.yaml`; page code does not depend on service URLs or wire DTOs.

## 3. High-risk visual acceptance checklist

Do not treat a successful Vite build as proof of WebGL compatibility. Verify the following in WeChat DevTools and at least one physical device:

1. The GLB loads without remote-domain access and stays within mini-program package limits.
2. One-finger orbit, pinch zoom and page scrolling do not fight each other.
3. Raycast/hotspot selection emits a stable left/right `regionId` and visibly highlights the chosen coarse region.
4. Repeated enter/leave does not accumulate animation loops, WebGL contexts, event handlers or GPU resources.
5. Freeze view prevents accidental camera movement.
6. Capture produces a correctly oriented, non-black PNG on both iOS and Android.
7. The captured picture opens in the same annotation editor used by standard and uploaded pictures.
8. Point, circle/freehand, undo, clear and reload preserve normalized coordinates after a resize.
9. Multi-image selection preserves per-image annotation and does not upload unbounded files in parallel.
10. Left-hand mirroring does not reverse the business side or label.

If the platform adapter breaks on a newer DevTools/runtime, keep the current proven package versions while investigating `threejs-miniprogram` or XR-Frame as an explicit fallback. Do not silently replace the 3D requirement with a static image.

## 4. Local infrastructure

Docker Compose starts:

- business PostgreSQL on `5432`, with `base_db`, `image_db`, and `history_db`;
- AI PostgreSQL + pgvector on `5433`, with `ai_db`;
- MinIO API/console on `9000`/`9001`;
- RabbitMQ AMQP/management on `5672`/`15672`;
- Nacos server/console on `8848`/`18080` (plus client gRPC `9848`);
- Sentinel Dashboard on `8858`.
- Swagger UI API documentation on `18081`.

From `infra/`:

```powershell
Copy-Item .env.example .env
docker compose up -d --build --wait
docker compose ps
```

This is one Compose project and one default network; do not start separate infrastructure stacks for individual services. Local defaults are intentionally development-only. Use non-default secrets outside a local machine and never expose a database, MinIO/RabbitMQ/Nacos/Sentinel management port directly to the internet.

Open `http://localhost:18081` while developing backend endpoints. It renders the canonical OpenAPI file and points `Try it out` at the local Gateway on port `8080`; Gateway must allow this local origin through CORS when request execution is needed. Swagger UI does not generate a second contract from Controller annotations.

Nacos and Sentinel are runtime prerequisites only. The repository does not yet add Spring Cloud Alibaba starters, register services, persist Sentinel rules, or decide retry/fallback policy. Nacos standalone uses its own embedded storage rather than either project PostgreSQL. See `infra/README.md` for addresses, first login, logs and lifecycle commands.

The Compose bootstrap creates the three business databases and enables the pgvector extension in `ai_db`. It does **not** apply any service schema. Apply current business schemas to an empty development database as documented in `infra/README.md`.

Never execute this file during the current phase:

```text
database/future/ai_db/V001__FUTURE_NOT_APPLIED_ai_schema.sql
```

Its mount is read-only for review; no init hook refers to it.

## 5. API and DTO workflow

`docs/api/openapi.yaml` is OpenAPI 3.1 and is the source of truth for wire names, ownership and errors. Recommended workflow for the later Java skeleton:

1. review the contract and freeze a small version;
2. generate or hand-write transport DTOs/interfaces only;
3. keep generated transport types outside domain models;
4. map frontend domain objects inside the HTTP adapter;
5. implement one service at a time against its owned paths and database;
6. add contract tests before introducing service-to-service calls.

Mutation rules:

- create/upload/submit/Agent-answer operations carry `X-Idempotency-Key`;
- update operations carry an optimistic `version` or `revision`;
- all errors use `application/problem+json` with a stable `code` and `traceId`;
- a missing resource and a resource owned by another user both return a non-enumerating `404` where appropriate;
- signed object URLs are responses, never stable IDs.

The OpenAPI `ai-service` routes are marked `FUTURE_NOT_IMPLEMENTED`. They describe the future seam and Mock semantics; they are not permission to apply the future AI schema or add a model dependency now.

## 6. Database change rules

- Each service migration connects only to its own logical database.
- Never add a foreign key from one service database to another.
- Prefer normal columns for stable, searchable fields. Use JSONB for truly dynamic annotation geometry, Agent checkpoint state, dynamic answers and structured explanation lists.
- Generate UUIDs in one consistent layer and preserve them across retries.
- A new schema version is a new migration; do not edit an applied migration in a shared environment.
- AI schema design must be re-reviewed after the Spring Cloud phase, particularly embedding dimension, retention and indexes.

## 7. Suggested verification before a handoff

```powershell
cd frontend
pnpm run type-check
pnpm run build:mp-weixin
```

Then perform the visual acceptance checklist, parse `docs/api/openapi.yaml` with an OpenAPI 3.1-aware validator, start Compose, verify all health checks, and apply only the three current business schemas to clean databases. A successful SQL parse is useful, but ownership and “no cross-database FK” should also be reviewed manually.

## 8. Known non-goals for this delivery

- Real WeChat authentication backend;
- real MinIO upload endpoint or RabbitMQ consumer;
- Java service business implementations and Nacos client integration;
- OpenFeign/Sentinel rules/retry/fallback configuration;
- real model calls, Agent orchestration or RAG;
- production medical validation, privacy policy or regulatory claims.
