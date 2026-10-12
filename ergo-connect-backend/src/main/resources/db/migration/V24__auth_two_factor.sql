-- Optional email-OTP two-factor authentication (SRS §12.4.1, ARCHITECTURE.md §8 Phase 8g). Mandatory for ERGO
-- Admin accounts - enforced in application code (UserAdminService, AuthService), not here.
ALTER TABLE users ADD COLUMN two_factor_enabled BOOLEAN NOT NULL DEFAULT false;

-- One row per emailed login code. Only its SHA-256 is stored, the same precedent password_reset_tokens (V23) and
-- user_sessions (V2) set - a database leak does not leak a usable code. Single-use and short-lived regardless.
CREATE TABLE otp_codes (
    otp_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users,
    code_hash   CHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ
);

CREATE INDEX ix_otp_codes_user ON otp_codes (user_id) WHERE consumed_at IS NULL;
