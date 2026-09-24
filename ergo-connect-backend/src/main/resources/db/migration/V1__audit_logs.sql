-- Audit trail foundation (SRS §15.3). Exists before any business table so no write is ever unaudited.
CREATE TABLE audit_logs (
    audit_log_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    module_name           VARCHAR(50)  NOT NULL,
    record_id             BIGINT       NOT NULL,
    event_type            VARCHAR(50)  NOT NULL,
    field_name            VARCHAR(100),
    old_value             TEXT,
    new_value             TEXT,
    action_reason         TEXT,
    changed_by_user_id    BIGINT,      -- FKs to users/roles/companies added with those tables (Phase 1)
    changed_by_role_id    BIGINT,
    changed_by_company_id BIGINT,
    changed_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source_channel        VARCHAR(10)  NOT NULL CHECK (source_channel IN ('UI', 'API', 'IMPORT', 'SYSTEM')),
    ip_address            VARCHAR(45)
);

CREATE INDEX ix_audit_logs_record ON audit_logs (module_name, record_id, changed_at);

-- Append-only, enforced by the database so no code path can rewrite history.
-- Prod: the app's DB role must not own this table (an owner can disable triggers) — ARCHITECTURE.md §4.
CREATE FUNCTION audit_logs_block_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_audit_logs_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_block_mutation();

CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION audit_logs_block_mutation();
