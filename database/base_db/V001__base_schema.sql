-- base_db schema v1
-- Ownership: base-service only.
-- Apply while connected to base_db. Do not grant other services direct table access.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE user_account (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_status      varchar(24) NOT NULL DEFAULT 'ACTIVE',
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    deleted_at          timestamptz,
    CONSTRAINT ck_user_account_status
        CHECK (account_status IN ('ACTIVE', 'DISABLED', 'DELETED'))
);

CREATE TABLE user_login_identity (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             uuid NOT NULL REFERENCES user_account(id) ON DELETE CASCADE,
    identity_type       varchar(24) NOT NULL,
    issuer              varchar(100) NOT NULL,
    external_subject    varchar(255) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    last_login_at       timestamptz,
    CONSTRAINT ck_user_login_identity_type
        CHECK (identity_type IN ('WECHAT', 'DEVELOPMENT')),
    CONSTRAINT uq_user_login_identity_subject
        UNIQUE (identity_type, issuer, external_subject)
);

CREATE INDEX idx_user_login_identity_user
    ON user_login_identity (user_id);

CREATE TABLE user_profile (
    user_id             uuid PRIMARY KEY REFERENCES user_account(id) ON DELETE CASCADE,
    display_name        varchar(80),
    avatar_picture_id   uuid,
    locale              varchar(20) NOT NULL DEFAULT 'zh-CN',
    version             integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_profile_version CHECK (version > 0)
);

COMMENT ON COLUMN user_profile.avatar_picture_id IS
    'Business reference to image_db.picture_asset; intentionally has no cross-database foreign key.';
COMMENT ON COLUMN user_login_identity.external_subject IS
    'Provider subject, such as a WeChat OpenID. Treat as sensitive and never expose in normal API responses.';

COMMIT;
