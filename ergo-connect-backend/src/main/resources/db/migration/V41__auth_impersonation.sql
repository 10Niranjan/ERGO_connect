-- Super Admin "Login as" (view-only shadow login, docs/PERMISSIONS.md §13).
-- A shadow session is an ordinary user_sessions row for the employee being viewed, plus who started it, from which
-- of their own sessions, and why. All three are set together or not at all, and nobody shadows themselves.
ALTER TABLE user_sessions
    ADD COLUMN impersonated_by_user_id BIGINT REFERENCES users,
    ADD COLUMN parent_session_id       BIGINT REFERENCES user_sessions,
    ADD COLUMN impersonation_reason    VARCHAR(500),
    ADD CONSTRAINT ck_user_sessions_impersonation CHECK (
        (impersonated_by_user_id IS NULL) = (parent_session_id IS NULL)
        AND (impersonated_by_user_id IS NULL) = (impersonation_reason IS NULL)
        AND impersonated_by_user_id IS DISTINCT FROM user_id);

-- one live shadow session per admin: starting a second ends the first (looked up by admin)
CREATE UNIQUE INDEX ux_user_sessions_impersonator ON user_sessions (impersonated_by_user_id)
    WHERE impersonated_by_user_id IS NOT NULL AND revoked_at IS NULL;
