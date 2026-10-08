-- One row per forgot-password request. The email holds the raw token; only its SHA-256 is stored here, the same
-- precedent user_sessions set (V2) - a database leak does not leak usable reset links. Single-use (consumed_at)
-- and short-lived (expires_at) regardless.
CREATE TABLE password_reset_tokens (
    token_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX ix_password_reset_tokens_user ON password_reset_tokens (user_id) WHERE consumed_at IS NULL;
