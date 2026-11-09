-- Client's revised RFQ entry format (docs: ERGO_Connect_Revised_Complete_RFQ_Format.docx).
-- Every new column is nullable or defaulted: tests and tooling insert bare rfqs / rfq_items rows.

-- RFQ header: title, description, special instructions (the customer's own words; not shown to suppliers).
ALTER TABLE rfqs
    ADD COLUMN title                VARCHAR(200),
    ADD COLUMN description          TEXT,
    ADD COLUMN special_instructions TEXT;

-- Part row: part-specific remarks. The one customer Target Price is stored in target_production_piece_price.
ALTER TABLE rfq_items
    ADD COLUMN remarks VARCHAR(1000);

-- RFQ numbers read PREFIX-YEAR-SERIAL (IC-2026-001). The prefix is set per customer by ERGO; a customer without one
-- gets initials from its name. The serial restarts every year, per prefix.
ALTER TABLE companies
    ADD COLUMN rfq_prefix VARCHAR(6);
CREATE UNIQUE INDEX uq_companies_rfq_prefix ON companies (rfq_prefix) WHERE rfq_prefix IS NOT NULL;

CREATE TABLE rfq_number_counters (
    prefix      VARCHAR(6) NOT NULL,
    year        INT        NOT NULL,
    last_serial INT        NOT NULL,
    PRIMARY KEY (prefix, year)
);

-- What suppliers see instead of the RFQ number: the customer's prefix would tell a supplier whose RFQ it is (client
-- decision: suppliers never learn the customer). Neutral, from the old global sequence (RFQ-2026-00123). RFQs that
-- exist already keep their old number as their supplier reference.
ALTER TABLE rfqs
    ADD COLUMN supplier_ref VARCHAR(20) NOT NULL
        DEFAULT ('RFQ-' || to_char(now(), 'YYYY') || '-' || lpad(nextval('rfq_number_seq')::text, 5, '0'));
UPDATE rfqs SET supplier_ref = rfq_number WHERE rfq_number LIKE 'RFQ-%';
CREATE UNIQUE INDEX uq_rfqs_supplier_ref ON rfqs (supplier_ref);

-- Currencies the client's format lists that the master data lacked. INR/USD/EUR/RMB stay.
INSERT INTO currencies (currency_code, currency_name) VALUES
    ('GBP', 'Pound Sterling'),
    ('AUD', 'Australian Dollar'),
    ('CAD', 'Canadian Dollar')
ON CONFLICT (currency_code) DO NOTHING;

-- Which slot of a part a file belongs to. The file itself is a documents row (module 'rfq'); deleting the part
-- removes this link, and RfqService deletes the file with it.
CREATE TABLE rfq_item_files (
    document_id BIGINT      NOT NULL PRIMARY KEY REFERENCES documents ON DELETE CASCADE,
    rfq_item_id BIGINT      NOT NULL REFERENCES rfq_items ON DELETE CASCADE,
    kind        VARCHAR(20) NOT NULL CHECK (kind IN ('DRAWING_2D', 'MODEL_3D', 'SPECIFICATION', 'ADDITIONAL'))
);
CREATE INDEX ix_rfq_item_files_item ON rfq_item_files (rfq_item_id);
