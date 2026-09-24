-- Phase 2a: RFQs (SRS §5, §13.3.1-13.3.2, §14.2, §17.1) and the basic document store they need (SRS §13.7.1).

CREATE SEQUENCE rfq_number_seq;

CREATE TABLE rfqs (
    rfq_id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_number                    VARCHAR(20)    NOT NULL UNIQUE,
    source_type                   VARCHAR(10)    NOT NULL CHECK (source_type IN ('CUSTOMER', 'ERGO')),
    customer_company_id           BIGINT         NOT NULL REFERENCES companies,
    created_by_user_id            BIGINT         NOT NULL REFERENCES users,
    owner_user_id                 BIGINT         REFERENCES users, -- the ERGO RFQ person who took the review
    status                        VARCHAR(30)    NOT NULL CHECK (status IN (
        'DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_REQUIRED', 'VALIDATED', 'SENT_TO_SUPPLIERS',
        'QUOTES_PARTIAL', 'QUOTES_COMPLETE', 'UNDER_COMPARISON', 'SENT_TO_CUSTOMER', 'CUSTOMER_REVISION_REQUESTED',
        'CUSTOMER_APPROVED', 'CUSTOMER_DECLINED', 'CLOSED', 'CANCELLED')),
    priority                      VARCHAR(10)    NOT NULL CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    part_number                   VARCHAR(100)   NOT NULL,
    revision                      VARCHAR(30),
    description                   VARCHAR(1000),
    eau                           INT            CHECK (eau > 0),
    net_weight_kg                 NUMERIC(12, 4) CHECK (net_weight_kg >= 0),
    gross_weight_kg               NUMERIC(12, 4) CHECK (gross_weight_kg >= 0),
    material_id                   BIGINT         REFERENCES materials,
    material_spec                 VARCHAR(500),
    coating_id                    BIGINT         REFERENCES coatings,
    coating_spec                  VARCHAR(500),
    target_quote_date             DATE,
    -- The customer's commercial targets. Never shown to suppliers (SRS §4, §5.6, §11.1).
    currency_id                   BIGINT         NOT NULL REFERENCES currencies,
    target_tooling_cost           NUMERIC(18, 4) CHECK (target_tooling_cost >= 0),
    target_ppap_piece_price       NUMERIC(18, 4) CHECK (target_ppap_piece_price >= 0),
    target_production_piece_price NUMERIC(18, 4) CHECK (target_production_piece_price >= 0),
    payment_term_id               BIGINT         REFERENCES payment_terms,
    material_lead_time_weeks      INT            CHECK (material_lead_time_weeks >= 0),
    tooling_lead_time_days        INT            CHECK (tooling_lead_time_days >= 0),
    sample_lead_time_days         INT            CHECK (sample_lead_time_days >= 0),
    production_lead_time_days     INT            CHECK (production_lead_time_days >= 0),
    moq                           INT            CHECK (moq > 0),
    customer_remarks              TEXT,
    internal_notes                TEXT,          -- ERGO only (SRS §11.4)
    clarification_question        TEXT,          -- ERGO's last question to the customer (SRS §14.2)
    clarification_answer          TEXT,          -- and the customer's answer
    created_at                    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX ix_rfqs_customer ON rfqs (customer_company_id);
CREATE INDEX ix_rfqs_status ON rfqs (status);

-- Planned process route (SRS §5.5). Each supplier fills its own copy in its quote (Phase 2b, client decision).
CREATE TABLE rfq_process_steps (
    rfq_process_step_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_id                   BIGINT         NOT NULL REFERENCES rfqs,
    sequence_no              INT            NOT NULL,
    process_id               BIGINT         REFERENCES processes,
    step_name                VARCHAR(200)   NOT NULL,
    cycle_time_seconds       NUMERIC(10, 2) CHECK (cycle_time_seconds > 0),
    sourcing_type            VARCHAR(20)    CHECK (sourcing_type IN ('INHOUSE', 'OUTSOURCED')),
    outsourced_supplier_name VARCHAR(200),
    remarks                  VARCHAR(500)
);

CREATE INDEX ix_rfq_process_steps_rfq ON rfq_process_steps (rfq_id);

-- Files attached to records. The owning module decides who may see the record; visibility narrows it further.
-- ponytail: bytes live in Postgres; Phase 6 moves them to Azure Blob and adds virus scanning (before go-live).
CREATE TABLE documents (
    document_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    linked_module          VARCHAR(30)  NOT NULL,
    linked_record_id       BIGINT       NOT NULL,
    file_name              VARCHAR(255) NOT NULL,
    content_type           VARCHAR(100) NOT NULL,
    file_size              BIGINT       NOT NULL CHECK (file_size > 0),
    sha256                 CHAR(64)     NOT NULL,
    visibility             VARCHAR(20)  NOT NULL CHECK (visibility IN ('INTERNAL', 'CUSTOMER', 'SUPPLIER', 'ALL')),
    uploaded_by_user_id    BIGINT       NOT NULL REFERENCES users,
    uploaded_by_company_id BIGINT       NOT NULL REFERENCES companies,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_documents_link ON documents (linked_module, linked_record_id);

CREATE TABLE document_contents (
    document_id BIGINT PRIMARY KEY REFERENCES documents ON DELETE CASCADE,
    content     BYTEA  NOT NULL
);
