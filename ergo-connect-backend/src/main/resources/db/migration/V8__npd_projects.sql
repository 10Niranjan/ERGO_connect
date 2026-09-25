-- Phase 3a: NPD projects, manual RFQ->NPD conversion (SRS §13.4.1-13.4.3, §14.5, §17). ERGO-only for this slice;
-- 3b adds prompt checklists, issues/risks and approvals (SRS §13.4.4-13.4.7).

CREATE SEQUENCE npd_number_seq;

CREATE TABLE npd_projects (
    npd_project_id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_number                     VARCHAR(20)  NOT NULL UNIQUE,
    source_rfq_id                  BIGINT       NOT NULL UNIQUE REFERENCES rfqs, -- one RFQ converts to at most one NPD project
    customer_company_id            BIGINT       NOT NULL REFERENCES companies,
    -- ERGO-internal only: the customer never learns which supplier develops their part (§8.1 anonymity, extended to NPD).
    supplier_company_id            BIGINT       NOT NULL REFERENCES companies,
    part_number                    VARCHAR(100) NOT NULL,
    revision                       VARCHAR(30),
    description                    VARCHAR(1000),
    quote_reference                VARCHAR(50), -- copied from the approved supplier quote
    stage                          VARCHAR(40)  NOT NULL CHECK (stage IN (
        'PENDING_CONVERSION', 'PLANNING', 'COMMERCIAL_SOURCING_READINESS', 'TOOLING_MATERIAL_RELEASE',
        'TRIAL_PREPARATION', 'TRIAL_EXECUTION', 'FINAL_SAMPLE_READINESS', 'CUSTOMER_FEEDBACK',
        'APPROVED_FOR_PRODUCTION_HANDOVER', 'CLOSED')),
    target_sample_date             DATE,
    actual_sample_submission_date  DATE,
    handover_ready_flag             BOOLEAN      NOT NULL DEFAULT false,
    owner_user_id                  BIGINT       REFERENCES users,
    risk_level                     VARCHAR(10)  CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    created_by_user_id             BIGINT       NOT NULL REFERENCES users,
    row_version                    BIGINT       NOT NULL DEFAULT 0, -- optimistic lock
    created_at                     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_npd_projects_customer ON npd_projects (customer_company_id);
CREATE INDEX ix_npd_projects_supplier ON npd_projects (supplier_company_id);
CREATE INDEX ix_npd_projects_stage ON npd_projects (stage);
CREATE INDEX ix_npd_projects_risk_level ON npd_projects (risk_level);

-- A permanent record of every stage move (SRS §13.4.2); never edited or deleted (reuses V5's append-only trigger).
CREATE TABLE npd_stage_history (
    npd_stage_history_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_project_id        BIGINT      NOT NULL REFERENCES npd_projects,
    stage_name            VARCHAR(40) NOT NULL,
    previous_stage_name   VARCHAR(40), -- null for the first row
    changed_by_user_id    BIGINT      NOT NULL REFERENCES users,
    changed_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    remarks                TEXT
);

CREATE INDEX ix_npd_stage_history_project ON npd_stage_history (npd_project_id);

CREATE TRIGGER trg_npd_stage_history_append_only
    BEFORE UPDATE OR DELETE ON npd_stage_history
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_npd_stage_history_no_truncate
    BEFORE TRUNCATE ON npd_stage_history
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();

-- The activity board (SRS §13.4.3), trimmed to what 3a needs: no prompt-checklist/issue/approval linkage yet (3b).
CREATE TABLE npd_activities (
    npd_activity_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_project_id            BIGINT       NOT NULL REFERENCES npd_projects,
    phase_name                VARCHAR(40)  NOT NULL, -- one of npd_projects.stage's values (not FK-checked: free text)
    activity_name             VARCHAR(200) NOT NULL,
    activity_description      TEXT,
    owner_organization_type   VARCHAR(10)  NOT NULL CHECK (owner_organization_type IN ('ERGO', 'CUSTOMER', 'SUPPLIER')),
    owner_company_id          BIGINT       REFERENCES companies,
    responsible_user_id       BIGINT       REFERENCES users,
    planned_date              DATE,
    baseline_date             DATE,
    actual_date               DATE,
    dependency_activity_id    BIGINT       REFERENCES npd_activities,
    status                    VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'BLOCKED')),
    blocked_flag              BOOLEAN      NOT NULL DEFAULT false,
    risk_flag                 BOOLEAN      NOT NULL DEFAULT false,
    issue_flag                BOOLEAN      NOT NULL DEFAULT false,
    -- Not in the SRS field list: marks which activities gate a stage-close (client decision, ARCHITECTURE.md §8 2026-09-26).
    mandatory_flag            BOOLEAN      NOT NULL DEFAULT true,
    approval_required_flag    BOOLEAN      NOT NULL DEFAULT false, -- unused by 3a; wired up in 3b
    row_version               BIGINT       NOT NULL DEFAULT 0, -- optimistic lock
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_npd_activities_project ON npd_activities (npd_project_id);
CREATE INDEX ix_npd_activities_dependency ON npd_activities (dependency_activity_id);
CREATE INDEX ix_npd_activities_status ON npd_activities (status);
