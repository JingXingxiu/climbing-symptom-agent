# Local infrastructure

All local dependencies live in **one Docker Compose project** named `symptom-assessment-dev`. There are no scattered Compose files and there is deliberately no PostgreSQL server per service.

| Container | Host access | Address from a future container in this Compose network | Purpose |
| --- | --- | --- | --- |
| `business-postgres` | `localhost:5432` | `business-postgres:5432` | `base_db`, `image_db`, `history_db`, `payment_db` |
| `ai-postgres` | `localhost:5433` | `ai-postgres:5432` | `ai_db` with pgvector runtime |
| `minio` | API `http://localhost:9000`; console `http://localhost:9001` | `minio:9000` | Private object storage |
| `rabbitmq` | AMQP `localhost:5672`; console `http://localhost:15672` | `rabbitmq:5672` | Future image-processing messages |
| `nacos` | server `localhost:8848`; console `http://localhost:18080` | `nacos:8848` | Local Config/Naming server |
| `sentinel-dashboard` | `http://localhost:8858` | `sentinel-dashboard:8080` | Local learning dashboard |
| `api-docs` | `http://localhost:18081` | `api-docs:8080` | Swagger UI for the canonical OpenAPI contract |
| `jaeger` (optional `observability` profile) | UI `http://localhost:16686`; OTLP HTTP `localhost:4318` | `jaeger:4318` | Agent execution traces; transient memory storage |

Redis, Seata, Outbox, and Inbox are intentionally absent.

## Start the whole group

Docker Desktop (or another Docker Engine with Compose v2) must be running. From `infra/`:

```powershell
if (!(Test-Path .env)) { Copy-Item .env.example .env }
docker compose up -d --build --wait
docker compose ps
```

The first run downloads the pinned images and builds the Sentinel image from the official release JAR, so it is slower than later starts. `--wait` returns only after every declared health check is healthy; omit it only when using an older Compose release that does not support the flag.

After startup, use `http://localhost:18081` as the normal API development entry. Swagger UI mounts `docs/api/openapi.yaml` read-only and exposes schemas, examples, operation IDs and `Try it out`. The latter becomes executable after the local Gateway is listening on `http://localhost:8080` and its development CORS policy allows `http://localhost:18081`.

Useful group commands:

```powershell
# Follow all logs, or just the two Spring Cloud learning components.
docker compose logs -f --tail=200
docker compose logs -f --tail=200 nacos sentinel-dashboard

# Stop without deleting containers/data, then start again.
docker compose stop
docker compose start

# Remove containers/network but retain named-volume data.
docker compose down
```

Run every command above from `infra/`; Compose then discovers the same `.env`, file, project name, network, and volumes automatically.

## Optional Agent trace UI

Jaeger uses the same Compose group and is not started by the default commands. Start only its optional container when inspecting Agent executions:

```powershell
docker compose --profile observability up -d jaeger
docker compose --profile observability logs --tail=100 jaeger
# Open http://localhost:16686 after the collector and query service have started.
docker compose --profile observability stop jaeger
```

The pinned Jaeger v2 image runs collector and query in one process with in-memory storage. Its restart loses traces; AI's PostgreSQL Session/Step/Checkpoint/ModelCall records remain the durable business state. There is no new Redis, ClickHouse or database container, and no business migration is triggered. See [Agent observability setup and reading order](../docs/agent-observability.md) to enable OTLP export in the Java process. Compose `.env` values do not automatically become environment variables of host-side Java applications.

## First login and local credentials

- Nacos 3 separates the console (`18080`) from the client server port (`8848`). Open `http://localhost:18080` and initialize the administrator password when the new console asks. Client API authentication is explicitly disabled only for this local learning phase; Admin and Console authentication remain enabled.
- Sentinel credentials are `SENTINEL_DASHBOARD_USERNAME` / `SENTINEL_DASHBOARD_PASSWORD` in the uncommitted `.env`. The sample development values are `sentinel` / `sentinel_dev_only`.
- MinIO and RabbitMQ credentials are also in `.env.example`; copy them to `.env` and replace them before using a shared machine.
- The new Nacos and Sentinel host ports bind to `127.0.0.1`. Do not publish any database or management console directly to the internet.

When changing `NACOS_SERVER_PORT`, keep `NACOS_GRPC_PORT` exactly 1000 higher because a host-side Nacos client derives the gRPC port from the server port. Containerized clients should use `nacos:8848`; they do not need a host-port override.

## What this stack does not configure

The Compose group only supplies infrastructure. Java modules now provide service registration, Gateway routes, OpenFeign clients, and reproducible Sentinel Gateway/QPS and AI-to-Payment circuit-breaker rules. Compose does not publish Nacos configuration or start the Java applications. Publish the checked-in samples explicitly and restart the affected services; Sentinel rules do not require Dashboard availability. See [Sentinel setup and verification](../docs/sentinel.md). Automatic retries and global rule persistence are not configured.

Nacos runs in standalone `microservice` mode with its own embedded Derby data under `nacos_data`; it does not use either project PostgreSQL instance. Sentinel Dashboard is a learning console: its rules and short-lived monitoring data are memory-based and can be lost on restart. No fake persistence volume is added for it.

The pinned Nacos `3.1.1` and Sentinel `1.8.9` versions follow the current Spring Cloud Alibaba `2025.1.0.0` component matrix. Re-check that matrix before introducing or upgrading the Alibaba BOM; do not change these tags to `latest` independently.

The business PostgreSQL init script creates only the four logical databases. It does **not** apply service schemas automatically. Existing volumes do not re-run this script; create `payment_db` explicitly when upgrading an existing environment. The AI bootstrap enables the `vector` extension, but it does **not** apply the future Agent schema.

## Apply current business schemas

Run these commands from `infra/` after the containers are healthy:

```powershell
docker compose exec -T business-postgres psql -U symptom_app -d base_db -v ON_ERROR_STOP=1 -f /workspace/database/base_db/V001__base_schema.sql
docker compose exec -T business-postgres psql -U symptom_app -d image_db -v ON_ERROR_STOP=1 -f /workspace/database/image_db/V001__image_schema.sql
docker compose exec -T business-postgres psql -U symptom_app -d image_db -v ON_ERROR_STOP=1 -f /workspace/database/image_db/V002__annotation_source_dimensions.sql
docker compose exec -T business-postgres psql -U symptom_app -d history_db -v ON_ERROR_STOP=1 -f /workspace/database/history_db/V001__history_schema.sql
docker compose exec -T business-postgres psql -U symptom_app -d history_db -v ON_ERROR_STOP=1 -f /workspace/database/history_db/V002__assessment_submission.sql
```

Apply each V001 schema to an empty development database once; image_db then needs V002 for annotation source dimensions, and history_db needs its V002 for internal submission receipts. If a database already has V001, apply only its V002, not V001 again. These scripts are not applied automatically. Later Java services should own and run only their own database migrations.

For the new payment service, create its database once if using an older volume, then apply its schema once:

```powershell
# Skip CREATE DATABASE if payment_db already exists. Do not reset the volume.
docker compose exec -T business-postgres psql -U symptom_app -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE payment_db;"
docker compose exec -T business-postgres psql -U symptom_app -d payment_db -v ON_ERROR_STOP=1 -f /workspace/database/payment_db/V001__payment_schema.sql
```

Payment is a host-side Java service on port 8085, registered in Nacos. Publish the six configs in `infra/nacos-config/` and explicitly activate `dev` plus `payment.mock-enabled=true` to allow simulation. No new PostgreSQL instance or payment container is required. See [payment setup and verification](../docs/payment-service.md).

## Verify isolation and pgvector

```powershell
docker compose exec -T business-postgres psql -U symptom_app -d postgres -c "SELECT datname FROM pg_database WHERE datname IN ('base_db','image_db','history_db','payment_db') ORDER BY datname;"
docker compose exec -T ai-postgres psql -U symptom_ai -d ai_db -c "SELECT extname, extversion FROM pg_extension WHERE extname = 'vector';"
```

Agent services are implemented, but AI V001, the daily-usage V002 and History V003/V004 remain **NOT APPLIED**. No init or compose hook executes them. Review the data model before deliberately enabling the Agent runtime; the historical future path remains mounted read-only. Updated History code expects V004 source-reference columns.

After that review, apply each new schema once (these commands have not been run by the agent):

```powershell
docker compose exec -T ai-postgres psql -U symptom_ai -d ai_db -v ON_ERROR_STOP=1 -f /workspace/future-ai-schema/V001__FUTURE_NOT_APPLIED_ai_schema.sql

docker compose exec -T ai-postgres psql -U symptom_ai -d ai_db -v ON_ERROR_STOP=1 -f /workspace/future-ai-schema/V002__FUTURE_NOT_APPLIED_user_daily_usage.sql
docker compose exec -T business-postgres psql -U symptom_app -d history_db -v ON_ERROR_STOP=1 -f /workspace/database/history_db/V003__assessment_result_revision.sql
docker compose exec -T business-postgres psql -U symptom_app -d history_db -v ON_ERROR_STOP=1 -f /workspace/database/history_db/V004__assessment_result_references.sql
```

Use your configured database usernames if they differ. Publish the updated ai-service and Gateway Nacos samples separately; saving repository YAML does not change live Nacos configuration. Set model/search secrets in the Java service environment. See [Agent Service reading guide](../docs/agent-service-reading.md).

## Verify the infrastructure group

```powershell
docker compose config --quiet
docker compose config --services
docker compose ps

Invoke-RestMethod http://localhost:8848/nacos/v3/admin/core/state/readiness
Invoke-WebRequest http://localhost:8858 -UseBasicParsing
```

Without a profile, the service list contains seven entries: `business-postgres`, `ai-postgres`, `minio`, `rabbitmq`, `nacos`, `sentinel-dashboard`, and `api-docs`. `docker compose --profile observability config --services` additionally lists `jaeger`. For a startup failure, inspect only the relevant service without splitting it into another Compose project:

```powershell
docker compose logs --tail=300 nacos
docker compose logs --tail=300 sentinel-dashboard
docker compose build --no-cache sentinel-dashboard
```

## Image-processing idempotency boundary

The initial RabbitMQ use case is intentionally small. A consumer should:

1. Receive `pictureId` and `processingGeneration`.
2. Acquire work with a conditional status update for that generation.
3. Write deterministic MinIO object keys for each derived variant.
4. Upsert `picture_variant` against `(picture_id, variant_type, processing_generation)`.
5. Mark the matching generation complete.

This makes duplicate delivery harmless without introducing Outbox/Inbox infrastructure. Occasional lost processing messages are accepted for the learning-stage scope and can be retried manually.

## Reset warning

`docker compose down` keeps data. `docker compose down -v` permanently deletes both PostgreSQL volumes, MinIO objects, RabbitMQ data, and Nacos standalone state. Use it only when a full development reset is intended.
