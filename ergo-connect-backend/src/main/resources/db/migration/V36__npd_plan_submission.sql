-- Flow doc step 8: the supplier fills in the plan ERGO NPD released to it (dates and owners of its own steps) and
-- submits it; ERGO NPD agrees, or sends it back with a comment; only an agreed plan goes to execution.
-- plan_status gains SUBMITTED (waiting for ERGO NPD) and CHANGES_REQUESTED (sent back to the supplier).
ALTER TABLE npd_projects DROP CONSTRAINT npd_projects_plan_status_check;
ALTER TABLE npd_projects ADD CONSTRAINT npd_projects_plan_status_check
    CHECK (plan_status IN ('AWAITING_PLAN', 'ASSIGNED_TO_SUPPLIER', 'SUBMITTED', 'CHANGES_REQUESTED', 'AGREED'));

-- The trail of the plan: released, submitted, sent back (with ERGO NPD's comment), agreed. Never edited or deleted.
CREATE TABLE npd_plan_history (
    npd_plan_history_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_project_id      BIGINT      NOT NULL REFERENCES npd_projects,
    event               VARCHAR(20) NOT NULL CHECK (event IN ('RELEASED', 'SUBMITTED', 'SENT_BACK', 'AGREED')),
    comment             VARCHAR(2000),
    by_user_id          BIGINT      NOT NULL REFERENCES users,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_npd_plan_history_project ON npd_plan_history (npd_project_id);

CREATE TRIGGER trg_npd_plan_history_append_only
    BEFORE UPDATE OR DELETE ON npd_plan_history
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_npd_plan_history_no_truncate
    BEFORE TRUNCATE ON npd_plan_history
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();
