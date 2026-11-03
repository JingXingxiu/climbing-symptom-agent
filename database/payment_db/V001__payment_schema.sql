-- payment_db schema v1
-- Ownership: payment-service only.
-- Apply manually while connected to payment_db on the business PostgreSQL instance.
-- This script is not run automatically by the application.

BEGIN;

SET LOCAL TIME ZONE 'UTC';

CREATE TABLE payment_plan (
    plan_code               varchar(40) PRIMARY KEY,
    name                    varchar(100) NOT NULL,
    price_cents             integer NOT NULL,
    currency                varchar(3) NOT NULL DEFAULT 'CNY',
    duration_days           integer NOT NULL,
    daily_model_call_limit  integer NOT NULL,
    active                  boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_payment_plan_code CHECK (plan_code <> '' AND plan_code <> 'FREE'),
    CONSTRAINT ck_payment_plan_name CHECK (name <> ''),
    CONSTRAINT ck_payment_plan_price CHECK (price_cents > 0),
    CONSTRAINT ck_payment_plan_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_payment_plan_duration CHECK (duration_days > 0),
    CONSTRAINT ck_payment_plan_daily_limit CHECK (daily_model_call_limit > 0)
);

CREATE TABLE payment_order (
    id                      uuid PRIMARY KEY,
    user_id                 uuid NOT NULL,
    idempotency_key         uuid NOT NULL,
    plan_code               varchar(40) NOT NULL,
    plan_name               varchar(100) NOT NULL,
    price_cents             integer NOT NULL,
    currency                varchar(3) NOT NULL,
    duration_days           integer NOT NULL,
    daily_model_call_limit  integer NOT NULL,
    status                  varchar(16) NOT NULL DEFAULT 'PENDING',
    expires_at              timestamptz NOT NULL,
    paid_at                 timestamptz,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_payment_order_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_payment_order_plan_code CHECK (plan_code <> '' AND plan_code <> 'FREE'),
    CONSTRAINT ck_payment_order_plan_name CHECK (plan_name <> ''),
    CONSTRAINT ck_payment_order_price CHECK (price_cents > 0),
    CONSTRAINT ck_payment_order_currency CHECK (currency = 'CNY'),
    CONSTRAINT ck_payment_order_duration CHECK (duration_days > 0),
    CONSTRAINT ck_payment_order_daily_limit CHECK (daily_model_call_limit > 0),
    CONSTRAINT ck_payment_order_status CHECK (status IN ('PENDING', 'PAID')),
    CONSTRAINT ck_payment_order_paid_at CHECK (
        (status = 'PENDING' AND paid_at IS NULL)
        OR (status = 'PAID' AND paid_at IS NOT NULL)
    ),
    CONSTRAINT ck_payment_order_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_payment_order_user_recent
    ON payment_order (user_id, created_at DESC, id DESC);

CREATE TABLE user_entitlement (
    user_id                 uuid PRIMARY KEY,
    plan_code               varchar(40) NOT NULL,
    plan_name               varchar(100) NOT NULL,
    daily_model_call_limit  integer NOT NULL,
    valid_until             timestamptz NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_entitlement_plan_code CHECK (plan_code <> '' AND plan_code <> 'FREE'),
    CONSTRAINT ck_user_entitlement_plan_name CHECK (plan_name <> ''),
    CONSTRAINT ck_user_entitlement_daily_limit CHECK (daily_model_call_limit > 0)
);

INSERT INTO payment_plan (
    plan_code, name, price_cents, currency, duration_days, daily_model_call_limit, active
) VALUES (
    'PLUS', 'PLUS会员', 1990, 'CNY', 30, 50, true
);

COMMENT ON TABLE payment_order IS
    'Immutable purchase terms are snapshotted from payment_plan when each order is created.';
COMMENT ON COLUMN payment_order.user_id IS
    'Trusted business user ID; intentionally has no cross-database foreign key.';
COMMENT ON COLUMN payment_order.idempotency_key IS
    'Client UUID, scoped to user_id; retries must resolve the existing order and compare plan_code.';
COMMENT ON COLUMN payment_order.price_cents IS
    'Integer CNY cents; supplied by the server-side plan, never by the client.';
COMMENT ON TABLE user_entitlement IS
    'One row per paying user; payment confirmations lock this row to serialize renewals.';
COMMENT ON COLUMN user_entitlement.user_id IS
    'Trusted business user ID; intentionally has no cross-database foreign key.';
COMMENT ON COLUMN user_entitlement.valid_until IS
    'UTC instant; absent or expired entitlement resolves to virtual FREE with daily limit 10.';

COMMIT;
