# Nacos development configuration seeds

These six files are the reproducible development copies of the values published
to Nacos under the `CLIMBING_DEV` group. The running source of configuration is
Nacos; this directory makes a reset of the local Nacos volume recoverable.

Publish them after Nacos is healthy:

```powershell
.\publish-config.ps1
```

The four business-service entries use environment-variable placeholders for
PostgreSQL credentials. Their fallback values match `infra/.env.example` and
are local-development-only. Payment owns `payment_db`; its mock confirmation
requires both `SPRING_PROFILES_ACTIVE=dev` and `payment.mock-enabled=true`.
The AI service uses its separate PostgreSQL instance; AI migrations still remain
**FUTURE / NOT APPLIED**, including the new daily-usage migration.

Gateway now routes `/api/payment/**` to `lb://payment-service`, retaining the
complete controller path. `/internal/payment/**` is not exposed by the Gateway.
AI resolves payment entitlements with `agentPaymentClient` outside its local
transaction. Publishing repository samples is an explicit operation; editing
these files alone does not update live Nacos configuration.
