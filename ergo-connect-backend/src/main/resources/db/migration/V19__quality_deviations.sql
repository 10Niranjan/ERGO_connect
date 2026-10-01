-- Phase 5c: Deviations (SRS §13.6.4, §14.7). Same polymorphic linked_module/linked_record_id link as
-- inspections (V16) and ncrs (V18), restricted to NPD/OFP only (a deviation is raised against a specific NPD
-- sample or OFP order, not a standalone inspection type). inspection_id is an optional extra link for
-- traceability when the deviation originated from an inspection's RELEASED_WITH_DEVIATION disposition.
-- Company columns, created_by_user_id/created_by_company_id and row_version/created_at/updated_at mirror
-- ncrs (V18) exactly, including the same customer_visible gate (ARCHITECTURE.md §8 2026-09-30) so a
-- deviation raised by/against a supplier is not shown to the customer until an ERGO editor turns it on.
-- Number format matches rfqs/npd_projects/ncrs: an app-side sequence, "DEV-YYYY-NNNNN".

CREATE SEQUENCE deviation_number_seq;

CREATE TABLE deviations (
    deviation_id                     BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deviation_number                 VARCHAR(20)  NOT NULL UNIQUE,
    linked_module                    VARCHAR(20)  NOT NULL CHECK (linked_module IN ('NPD', 'OFP')),
    linked_record_id                 BIGINT       NOT NULL,
    -- Optional traceability link to the inspection this deviation originated from (e.g. a "Released with
    -- Deviation" disposition) — not required, a deviation can also be requested standalone against an NPD/OFP
    -- record. No ON DELETE rule: inspections are never deleted, only closed.
    inspection_id                    BIGINT       REFERENCES inspections,
    customer_company_id              BIGINT       REFERENCES companies,
    supplier_company_id              BIGINT       REFERENCES companies,
    part_number                      VARCHAR(100) NOT NULL,
    deviation_description            TEXT         NOT NULL,
    requested_by_user_id             BIGINT       NOT NULL REFERENCES users,
    quality_impact                   TEXT,
    technical_impact                 TEXT,
    customer_approval_required_flag  BOOLEAN      NOT NULL DEFAULT false,
    validity_quantity                NUMERIC(14, 2) CHECK (validity_quantity > 0),
    validity_end_date                DATE,
    current_status                   VARCHAR(40)  NOT NULL DEFAULT 'REQUESTED' CHECK (current_status IN
        ('REQUESTED', 'UNDER_REVIEW', 'CUSTOMER_APPROVAL_PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CLOSED')),
    -- Mirrors inspections.customer_visible/ncrs.customer_visible: the customer's own read gate, independent of
    -- customer_approval_required_flag (which drives the approval workflow, not visibility).
    customer_visible                 BOOLEAN      NOT NULL DEFAULT false,
    -- Security review H3 fix: the supplier-side mirror of customer_visible - gates the supplier's own read access
    -- so a customer-authored deviation's free text is not visible to the supplier until an ERGO editor reviews and
    -- shares it (DeviationService's class comment covers exactly when this is set).
    supplier_visible                 BOOLEAN      NOT NULL DEFAULT false,
    created_by_user_id               BIGINT       NOT NULL REFERENCES users,
    created_by_company_id            BIGINT       NOT NULL REFERENCES companies,
    row_version                      BIGINT       NOT NULL DEFAULT 0,
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- L3-style rule (mirrors ck_inspections_customer_visible, V17): a customer-visible deviation must name its
    -- customer.
    CHECK (NOT customer_visible OR customer_company_id IS NOT NULL),
    -- Same rule, supplier side.
    CHECK (NOT supplier_visible OR supplier_company_id IS NOT NULL)
);

CREATE INDEX ix_deviations_customer ON deviations (customer_company_id);
CREATE INDEX ix_deviations_supplier ON deviations (supplier_company_id);
CREATE INDEX ix_deviations_status ON deviations (current_status);
CREATE INDEX ix_deviations_linked ON deviations (linked_module, linked_record_id);
CREATE INDEX ix_deviations_inspection ON deviations (inspection_id);
CREATE INDEX ix_deviations_validity_end_date ON deviations (validity_end_date);
CREATE INDEX ix_deviations_created_by_company ON deviations (created_by_company_id);
CREATE INDEX ix_deviations_requested_by ON deviations (requested_by_user_id);

-- One row per required approval (SRS §11.4: approver, timestamp, previous/new status and comments captured via
-- this row's own fields + the automatic field-audit listener on current_status) — same shape as npd_approvals
-- (V9), but dual-row-per-deviation instead of one-row-per-gate: ERGO Quality is always required, Customer
-- Quality only when customer_approval_required_flag is true. The service creates both/either row at submission
-- time; current_status reaches APPROVED only once every row for this deviation is APPROVED.
CREATE TABLE deviation_approvals (
    deviation_approval_id  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deviation_id            BIGINT      NOT NULL REFERENCES deviations,
    approver_role            VARCHAR(40) NOT NULL REFERENCES roles CHECK (approver_role IN ('ERGO_QUALITY', 'CUSTOMER_QUALITY')),
    approver_user_id          BIGINT      REFERENCES users, -- null until decided
    decision                   VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (decision IN
        ('PENDING', 'APPROVED', 'REJECTED', 'SENT_BACK')),
    comment                     TEXT,
    decided_at                   TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_deviation_approvals_deviation ON deviation_approvals (deviation_id);
CREATE INDEX ix_deviation_approvals_role ON deviation_approvals (approver_role);
CREATE INDEX ix_deviation_approvals_status ON deviation_approvals (decision);

-- Immutable snapshot per submission/approval decision (SRS §15.4), same shape and trigger pattern as
-- supplier_quote_versions (V5): reuses that migration's forbid_update_delete() function rather than redefining
-- it, since the function carries no table-specific logic (it just raises using TG_TABLE_NAME/TG_OP).
CREATE TABLE deviation_versions (
    deviation_version_id  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deviation_id           BIGINT      NOT NULL REFERENCES deviations,
    version_no              INT         NOT NULL,
    snapshot                 JSONB       NOT NULL,
    recorded_by_user_id       BIGINT      NOT NULL REFERENCES users,
    recorded_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (deviation_id, version_no)
);

CREATE INDEX ix_deviation_versions_deviation ON deviation_versions (deviation_id);

CREATE TRIGGER trg_deviation_versions_append_only
    BEFORE UPDATE OR DELETE ON deviation_versions
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_deviation_versions_no_truncate
    BEFORE TRUNCATE ON deviation_versions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();
