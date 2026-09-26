-- Phase 3b: NPD prompt checklists, issues/risks and approvals (SRS §13.4.4-13.4.7, §14.5, §17.4).
-- Ordinary mutable tables covered by the standard Hibernate field-audit listener; none are ledgers
-- (only npd_stage_history, V8, is append-only), so none get the forbid_update_delete() trigger.

-- Question bank: one row per template question for an activity (SRS §13.4.4).
CREATE TABLE npd_prompt_checklists (
    npd_prompt_checklist_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_activity_id          BIGINT      NOT NULL REFERENCES npd_activities,
    question_text            TEXT        NOT NULL,
    response_type            VARCHAR(10) NOT NULL CHECK (response_type IN ('TEXT', 'YES_NO', 'FILE')),
    mandatory_flag           BOOLEAN     NOT NULL DEFAULT true,
    sequence_no              INTEGER     NOT NULL,
    row_version               BIGINT      NOT NULL DEFAULT 0, -- optimistic lock
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_npd_prompt_checklists_activity ON npd_prompt_checklists (npd_activity_id);

-- One current answer per question (SRS §13.4.5); re-answering updates this row rather than inserting another.
CREATE TABLE npd_prompt_responses (
    npd_prompt_response_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    checklist_id            BIGINT      NOT NULL UNIQUE REFERENCES npd_prompt_checklists,
    response_value          TEXT,
    remarks                 TEXT,
    responded_by_user_id    BIGINT      NOT NULL REFERENCES users,
    responded_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    row_version              BIGINT      NOT NULL DEFAULT 0, -- optimistic lock
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- SRS §13.4.6. owner_organization_type/owner_company_id (beyond the SRS's literal field list, mirroring
-- npd_activities) give a company-scope basis for the two-way anonymity rule even when linked_activity_id is null.
CREATE TABLE npd_issues_risks (
    npd_issue_risk_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_project_id           BIGINT       NOT NULL REFERENCES npd_projects,
    linked_activity_id       BIGINT       REFERENCES npd_activities,
    type                     VARCHAR(10)  NOT NULL CHECK (type IN ('ISSUE', 'RISK')),
    title                    VARCHAR(200) NOT NULL,
    description              TEXT,
    severity                 VARCHAR(10)  CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    owner_organization_type  VARCHAR(10)  NOT NULL CHECK (owner_organization_type IN ('ERGO', 'CUSTOMER', 'SUPPLIER')),
    owner_company_id         BIGINT       REFERENCES companies,
    owner_user_id            BIGINT       REFERENCES users,
    target_date              DATE,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'CLOSED')),
    closure_date             DATE,
    row_version               BIGINT       NOT NULL DEFAULT 0, -- optimistic lock
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_npd_issues_risks_project ON npd_issues_risks (npd_project_id);
CREATE INDEX ix_npd_issues_risks_activity ON npd_issues_risks (linked_activity_id);
CREATE INDEX ix_npd_issues_risks_owner_company ON npd_issues_risks (owner_company_id);
CREATE INDEX ix_npd_issues_risks_status ON npd_issues_risks (status);

-- SRS §13.4.7, §11.4 (approver, timestamp, previous/new status and comments are the automatic field
-- audit's before/after on approval_status, same reasoning already used for npd_issues_risks above).
-- stage_name is free text against npd_projects.stage's own CHECK list (not re-checked here, matching
-- npd_activities.phase_name in V8). approval_type is a fixed enum the service maps to one approver
-- role/role-set server-side; approver_role is never a free-chosen field (ARCHITECTURE.md §8 2026-09-26).
CREATE TABLE npd_approvals (
    npd_approval_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_project_id         BIGINT      NOT NULL REFERENCES npd_projects,
    linked_activity_id     BIGINT      REFERENCES npd_activities,
    stage_name             VARCHAR(40) NOT NULL,
    approval_type          VARCHAR(40) NOT NULL CHECK (approval_type IN (
        'STAGE_GATE', 'QUALITY_GATE', 'CUSTOMER_SAMPLE_APPROVAL',
        'CUSTOMER_MILESTONE_ACKNOWLEDGMENT', 'CUSTOMER_QUALITY_ACCEPTANCE')),
    requested_by_user_id   BIGINT      NOT NULL REFERENCES users,
    requested_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    approver_role          VARCHAR(40) NOT NULL REFERENCES roles,
    approved_by_user_id    BIGINT      REFERENCES users,
    approval_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approval_comments      TEXT,
    approved_at            TIMESTAMPTZ,
    row_version             BIGINT      NOT NULL DEFAULT 0, -- optimistic lock
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_npd_approvals_project ON npd_approvals (npd_project_id);
CREATE INDEX ix_npd_approvals_activity ON npd_approvals (linked_activity_id);
CREATE INDEX ix_npd_approvals_approver_role ON npd_approvals (approver_role);
CREATE INDEX ix_npd_approvals_status ON npd_approvals (approval_status);
