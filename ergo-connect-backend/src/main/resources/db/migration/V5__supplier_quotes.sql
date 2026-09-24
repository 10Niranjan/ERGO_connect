-- Phase 2b: suppliers invited to quote and their quotes (SRS §13.3.3-13.3.6, §14.3, §17.2).

-- Supplier submissions and ERGO actions now change an RFQ's status concurrently: the later of two conflicting writes
-- fails (409) instead of silently overwriting the other.
ALTER TABLE rfqs ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

-- One invitation per supplier per RFQ. Its status is the supplier quote status of SRS §14.3 (+ REGRETTED).
CREATE TABLE rfq_supplier_assignments (
    assignment_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_id                     BIGINT      NOT NULL REFERENCES rfqs,
    supplier_company_id        BIGINT      NOT NULL REFERENCES companies,
    assigned_by_user_id        BIGINT      NOT NULL REFERENCES users,
    response_due_date          DATE        NOT NULL,
    status                     VARCHAR(30) NOT NULL CHECK (status IN ('NOT_STARTED', 'DRAFT_SAVED',
        'CLARIFICATION_REQUESTED', 'SUBMITTED', 'REOPENED', 'RESUBMITTED', 'SHORTLISTED', 'SELECTED_FOR_CUSTOMER',
        'NOT_SELECTED', 'REGRETTED')),
    status_before_clarification VARCHAR(30),
    quote_edit_open            BOOLEAN     NOT NULL DEFAULT true, -- SRS quote_edit_open_flag
    regret_reason              TEXT,
    clarification_question     TEXT,
    clarification_answer       TEXT,
    row_version                BIGINT      NOT NULL DEFAULT 0, -- optimistic lock
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rfq_id, supplier_company_id)
);

CREATE INDEX ix_rfq_supplier_assignments_supplier ON rfq_supplier_assignments (supplier_company_id);

-- Only supplier companies can be invited: a mistake here would show RFQs to the wrong party.
CREATE FUNCTION rfq_supplier_assignments_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM companies WHERE company_id = NEW.supplier_company_id AND company_type = 'SUPPLIER') THEN
        RAISE EXCEPTION 'Company % is not a supplier', NEW.supplier_company_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_rfq_supplier_assignments_check
    BEFORE INSERT OR UPDATE ON rfq_supplier_assignments
    FOR EACH ROW EXECUTE FUNCTION rfq_supplier_assignments_check();

-- The supplier's working copy. Locked after submission unless ERGO re-opens it.
CREATE TABLE supplier_quotes (
    supplier_quote_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id             BIGINT         NOT NULL UNIQUE REFERENCES rfq_supplier_assignments,
    quote_reference           VARCHAR(50),
    validity_date             DATE,
    currency_id               BIGINT         REFERENCES currencies,
    incoterm_id               BIGINT         REFERENCES incoterms,
    payment_term_id           BIGINT         REFERENCES payment_terms,
    tooling_cost_total        NUMERIC(18, 4) CHECK (tooling_cost_total >= 0),
    ppap_piece_price          NUMERIC(18, 4) CHECK (ppap_piece_price >= 0),
    production_piece_price    NUMERIC(18, 4) CHECK (production_piece_price >= 0),
    raw_material_cost         NUMERIC(18, 4) CHECK (raw_material_cost >= 0),
    processing_cost           NUMERIC(18, 4) CHECK (processing_cost >= 0),
    overhead_cost             NUMERIC(18, 4) CHECK (overhead_cost >= 0),
    packaging_cost            NUMERIC(18, 4) CHECK (packaging_cost >= 0),
    profit                    NUMERIC(18, 4) CHECK (profit >= 0),
    moq                       INT            CHECK (moq > 0),
    material_lead_time_weeks  INT            CHECK (material_lead_time_weeks >= 0),
    tooling_lead_time_days    INT            CHECK (tooling_lead_time_days >= 0),
    sample_lead_time_days     INT            CHECK (sample_lead_time_days >= 0),
    production_lead_time_days INT            CHECK (production_lead_time_days >= 0),
    assumptions_deviations    TEXT,
    remarks                   TEXT,
    current_version           INT            NOT NULL DEFAULT 0, -- submissions so far
    submitted_at              TIMESTAMPTZ,
    submitted_by_user_id      BIGINT         REFERENCES users,
    reopen_reason             TEXT,          -- why ERGO re-opened it; carried into the next version
    reopened_by_user_id       BIGINT         REFERENCES users,
    reopened_at               TIMESTAMPTZ,
    created_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- currency is tagged on every money row: no prices before the quote currency is chosen
    CHECK (currency_id IS NOT NULL OR num_nonnulls(tooling_cost_total, ppap_piece_price, production_piece_price,
        raw_material_cost, processing_cost, overhead_cost, packaging_cost, profit) = 0)
);

-- Tool costs are in the quote's currency.
CREATE TABLE supplier_quote_tooling (
    supplier_quote_tooling_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_quote_id         BIGINT         NOT NULL REFERENCES supplier_quotes,
    sequence_no               INT            NOT NULL,
    tool_name                 VARCHAR(200)   NOT NULL,
    tooling_cost              NUMERIC(18, 4) NOT NULL CHECK (tooling_cost >= 0),
    remarks                   VARCHAR(500)
);

CREATE INDEX ix_supplier_quote_tooling_quote ON supplier_quote_tooling (supplier_quote_id);

-- The supplier's own process route (client decision: RFQ plans the steps, each supplier fills its copy).
CREATE TABLE supplier_quote_process_steps (
    supplier_quote_process_step_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_quote_id              BIGINT         NOT NULL REFERENCES supplier_quotes,
    sequence_no                    INT            NOT NULL,
    process_id                     BIGINT         REFERENCES processes,
    step_name                      VARCHAR(200)   NOT NULL,
    cycle_time_seconds             NUMERIC(10, 2) CHECK (cycle_time_seconds > 0),
    sourcing_type                  VARCHAR(20)    CHECK (sourcing_type IN ('INHOUSE', 'OUTSOURCED')),
    outsourced_supplier_name       VARCHAR(200),
    remarks                        VARCHAR(500)
);

CREATE INDEX ix_supplier_quote_process_steps_quote ON supplier_quote_process_steps (supplier_quote_id);

-- Every submission is kept exactly as sent (SRS §14.3: suppliers cannot delete historical versions).
CREATE TABLE supplier_quote_versions (
    supplier_quote_version_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    supplier_quote_id         BIGINT      NOT NULL REFERENCES supplier_quotes,
    version_no                INT         NOT NULL,
    snapshot                  JSONB       NOT NULL,
    document_ids              BIGINT[]    NOT NULL DEFAULT '{}', -- the supplier's files submitted with it
    submitted_by_user_id      BIGINT      NOT NULL REFERENCES users,
    submitted_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    reopen_reason             TEXT,
    reopened_by_user_id       BIGINT      REFERENCES users,
    reopened_at               TIMESTAMPTZ,
    UNIQUE (supplier_quote_id, version_no)
);

CREATE FUNCTION forbid_update_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER trg_supplier_quote_versions_append_only
    BEFORE UPDATE OR DELETE ON supplier_quote_versions
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_supplier_quote_versions_no_truncate
    BEFORE TRUNCATE ON supplier_quote_versions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();
