-- Flow doc §1-2: one customer RFQ holds several RFQ items (part numbers). The RFQ keeps what is shared by the whole
-- request (customer, status, currency, payment terms, target quote date, remarks, clarification); everything about a
-- part moves to rfq_items, with its own process flow.
--
-- The ERGO "validate" step is not part of the flow: ERGO reviews, may change the RFQ, then distributes it to suppliers.

CREATE TABLE rfq_items (
    rfq_item_id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_id                        BIGINT         NOT NULL REFERENCES rfqs,
    line_no                       INT            NOT NULL CHECK (line_no > 0),
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
    -- The customer's commercial targets for this part, in the RFQ currency. Never shown to suppliers.
    target_tooling_cost           NUMERIC(18, 4) CHECK (target_tooling_cost >= 0),
    target_ppap_piece_price       NUMERIC(18, 4) CHECK (target_ppap_piece_price >= 0),
    target_production_piece_price NUMERIC(18, 4) CHECK (target_production_piece_price >= 0),
    material_lead_time_weeks      INT            CHECK (material_lead_time_weeks >= 0),
    tooling_lead_time_days        INT            CHECK (tooling_lead_time_days >= 0),
    sample_lead_time_days         INT            CHECK (sample_lead_time_days >= 0),
    production_lead_time_days     INT            CHECK (production_lead_time_days >= 0),
    moq                           INT            CHECK (moq > 0),
    created_at                    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (rfq_id, line_no) DEFERRABLE INITIALLY DEFERRED -- an edit may reuse line numbers within one transaction
);

CREATE INDEX ix_rfq_items_rfq ON rfq_items (rfq_id);

CREATE TABLE rfq_item_process_steps (
    rfq_item_id              BIGINT         NOT NULL REFERENCES rfq_items ON DELETE CASCADE,
    sequence_no              INT            NOT NULL,
    process_id               BIGINT         REFERENCES processes,
    step_name                VARCHAR(200)   NOT NULL,
    cycle_time_seconds       NUMERIC(10, 2) CHECK (cycle_time_seconds > 0),
    sourcing_type            VARCHAR(20)    CHECK (sourcing_type IN ('INHOUSE', 'OUTSOURCED')),
    outsourced_supplier_name VARCHAR(200),
    remarks                  VARCHAR(500),
    PRIMARY KEY (rfq_item_id, sequence_no)
);

-- Every existing single-part RFQ becomes an RFQ with one item.
INSERT INTO rfq_items (rfq_id, line_no, part_number, revision, description, eau, net_weight_kg, gross_weight_kg,
                       material_id, material_spec, coating_id, coating_spec, target_tooling_cost,
                       target_ppap_piece_price, target_production_piece_price, material_lead_time_weeks,
                       tooling_lead_time_days, sample_lead_time_days, production_lead_time_days, moq, created_at)
SELECT rfq_id, 1, part_number, revision, description, eau, net_weight_kg, gross_weight_kg, material_id, material_spec,
       coating_id, coating_spec, target_tooling_cost, target_ppap_piece_price, target_production_piece_price,
       material_lead_time_weeks, tooling_lead_time_days, sample_lead_time_days, production_lead_time_days, moq,
       created_at
FROM rfqs;

INSERT INTO rfq_item_process_steps (rfq_item_id, sequence_no, process_id, step_name, cycle_time_seconds, sourcing_type,
                                    outsourced_supplier_name, remarks)
SELECT i.rfq_item_id, s.sequence_no, s.process_id, s.step_name, s.cycle_time_seconds, s.sourcing_type,
       s.outsourced_supplier_name, s.remarks
FROM rfq_process_steps s
         JOIN rfq_items i ON i.rfq_id = s.rfq_id AND i.line_no = 1;

DROP TABLE rfq_process_steps;

ALTER TABLE rfqs
    DROP COLUMN part_number,
    DROP COLUMN revision,
    DROP COLUMN description,
    DROP COLUMN eau,
    DROP COLUMN net_weight_kg,
    DROP COLUMN gross_weight_kg,
    DROP COLUMN material_id,
    DROP COLUMN material_spec,
    DROP COLUMN coating_id,
    DROP COLUMN coating_spec,
    DROP COLUMN target_tooling_cost,
    DROP COLUMN target_ppap_piece_price,
    DROP COLUMN target_production_piece_price,
    DROP COLUMN material_lead_time_weeks,
    DROP COLUMN tooling_lead_time_days,
    DROP COLUMN sample_lead_time_days,
    DROP COLUMN production_lead_time_days,
    DROP COLUMN moq;

-- No validate step and no side-by-side comparison in the flow.
UPDATE rfqs SET status = 'UNDER_REVIEW' WHERE status = 'VALIDATED';
UPDATE rfqs SET status = 'QUOTES_COMPLETE' WHERE status = 'UNDER_COMPARISON';

ALTER TABLE rfqs DROP CONSTRAINT rfqs_status_check;
ALTER TABLE rfqs ADD CONSTRAINT rfqs_status_check CHECK (status IN (
    'DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_REQUIRED', 'SENT_TO_SUPPLIERS', 'QUOTES_PARTIAL',
    'QUOTES_COMPLETE', 'SENT_TO_CUSTOMER', 'CUSTOMER_ON_HOLD', 'CUSTOMER_REVISION_REQUESTED', 'CUSTOMER_APPROVED',
    'CUSTOMER_DECLINED', 'CLOSED', 'CANCELLED'));

-- Files ERGO shares with suppliers are shared per part: a supplier sees a shared file only if it holds one of the
-- parts the file is shared for (a drawing of one part must not reach the supplier of another).
CREATE TABLE rfq_document_parts (
    document_id BIGINT NOT NULL REFERENCES documents ON DELETE CASCADE,
    rfq_item_id BIGINT NOT NULL REFERENCES rfq_items ON DELETE CASCADE,
    PRIMARY KEY (document_id, rfq_item_id)
);

-- Files that were already shared with suppliers stay shared for the RFQ's (only) part.
INSERT INTO rfq_document_parts (document_id, rfq_item_id)
SELECT d.document_id, i.rfq_item_id
FROM documents d
         JOIN rfq_items i ON i.rfq_id = d.linked_record_id
WHERE d.linked_module = 'rfq' AND d.visibility IN ('SUPPLIER', 'ALL');
