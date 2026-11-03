# payment_db

payment-service exclusively owns this database on the existing business PostgreSQL instance. Create the database through the normal deployment process, then review and manually apply `V001__payment_schema.sql` while connected to `payment_db`. The application does not run migrations or create the database automatically.

The schema has three tables:

- `payment_plan` stores server-controlled purchase terms. The only initial purchasable plan is `PLUS`: CNY 19.90, 30 days, 50 model calls per day.
- `payment_order` snapshots those terms and scopes each idempotency UUID to its user. The only states are `PENDING` and `PAID`; expired pending orders stay pending and payment confirmation rejects them.
- `user_entitlement` holds one row per paying user. No row, or `valid_until` at or before the current instant, resolves to virtual `FREE` / 免费 / 10 calls per day without inserting a free-plan row.

Amounts use integer cents and all timestamps use `timestamptz`. User IDs are business references only; there are no cross-database foreign keys. Order snapshots deliberately have no foreign key to mutable plan data.

Payment confirmation locks the order, then creates the entitlement row with `INSERT ... ON CONFLICT DO NOTHING` and locks that row before extending `max(now, valid_until)` by the purchased duration. The paid order and entitlement update commit in one local transaction. Replaying a paid order returns its original state without extending the entitlement again. No remote service call, Seata transaction, or message queue participates.

This is a development payment demonstration, not a real payment-provider integration. Mock payment is disabled by default and requires both the active `dev` profile and explicit `payment.mock-enabled=true`.
