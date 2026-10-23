-- Flow doc steps 6-7: once the customer approves a part's quotation, Customer NPD raises a Sample PO for it and submits
-- it to the ERGO Key Account Manager. The KAM either returns it with a comment or accepts it by creating the sample
-- development project (assigning ERGO NPD and ERGO Quality). ERGO NPD then builds the plan and releases it to the
-- supplier. This replaces the manual "conversion queue".

CREATE SEQUENCE sample_po_number_seq;

CREATE TABLE sample_pos (
    sample_po_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sample_po_number    VARCHAR(20)  NOT NULL UNIQUE,
    rfq_id              BIGINT       NOT NULL REFERENCES rfqs,
    -- one Sample PO per approved part
    rfq_item_id         BIGINT       NOT NULL UNIQUE REFERENCES rfq_items,
    customer_company_id BIGINT       NOT NULL REFERENCES companies,
    part_number         VARCHAR(100) NOT NULL, -- copied from the part, so the customer's list needs no RFQ lookup
    customer_po_number  VARCHAR(60)  NOT NULL,
    quantity            INTEGER      NOT NULL CHECK (quantity > 0),
    required_date       DATE         NOT NULL,
    remarks             VARCHAR(2000),
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('SUBMITTED', 'RETURNED', 'ACCEPTED')),
    -- why the KAM last sent it back (every return is also audited with its reason)
    return_comment      VARCHAR(2000),
    created_by_user_id  BIGINT       NOT NULL REFERENCES users,
    row_version         BIGINT       NOT NULL DEFAULT 0, -- optimistic lock
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_sample_pos_customer ON sample_pos (customer_company_id);
CREATE INDEX ix_sample_pos_status ON sample_pos (status);

-- The project the KAM creates from the accepted Sample PO. Projects created before this flow have none.
-- plan_status: AWAITING_PLAN = ERGO NPD is still building the plan template (the supplier cannot see the project yet),
-- ASSIGNED_TO_SUPPLIER = released to the supplier's NPD / RFQ / Quality teams, AGREED = in execution. Existing
-- projects are already running, so they start AGREED (also what raw-SQL fixtures get).
ALTER TABLE npd_projects
    ADD COLUMN sample_po_id          BIGINT UNIQUE REFERENCES sample_pos,
    ADD COLUMN quality_owner_user_id BIGINT REFERENCES users,
    ADD COLUMN plan_status           VARCHAR(25) NOT NULL DEFAULT 'AGREED'
        CHECK (plan_status IN ('AWAITING_PLAN', 'ASSIGNED_TO_SUPPLIER', 'AGREED'));

-- Notifications about a Sample PO file under it, not under an NPD project (there is none until the KAM accepts).
ALTER TABLE notifications DROP CONSTRAINT notifications_linked_module_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_linked_module_check
    CHECK (linked_module IN ('rfq', 'quotation', 'npd', 'sample-po', 'ofp', 'inspection', 'ncr', 'deviation', 'document', 'company'));

-- Role sheet: the KAM creates the sample project (A on NPD, still limited to their assigned customer accounts), and the
-- supplier's RFQ team can read the plan assigned to it (V). Only rows still at the old shipped value are changed, so a
-- level an ERGO Admin has already edited is left alone.
UPDATE role_module_permissions SET access_level = 'A'
WHERE role_code = 'ERGO_KAM' AND module_code = 'NPD' AND access_level = 'V';
UPDATE role_module_permissions SET access_level = 'V'
WHERE role_code = 'SUPPLIER_RFQ' AND module_code = 'NPD' AND access_level = 'NV';
-- Running instances reload the sheet when this version changes.
UPDATE access_config SET version = version + 1;

-- Sidebar: ERGO (KAM, Management, NPD ...) and the customer reach the Sample POs next to NPD.
INSERT INTO nav_items (item_key, section_title, section_order, sort_order, label, path, icon, module_code, sides) VALUES
    ('sample-pos', 'Sourcing & Workflow', 1, 35, 'Sample POs', '/npd/sample-pos', 'description', 'NPD', 'ERGO,CUSTOMER');
