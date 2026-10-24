-- Flow doc step 11: after the sample is approved, the customer releases a production order and submits it in the software
-- under OFP; the ERGO KAM is notified, returns it or accepts it by creating the OFP project (assigning one ERGO OFP and one
-- ERGO Quality user). The OFP project is a project like the sample one - same plan, execution, quality validation and
-- final approval - with only the relevant steps and the OFP teams added. The order is then created from it (handover).

CREATE SEQUENCE production_po_number_seq;

CREATE TABLE production_pos (
    production_po_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    production_po_number VARCHAR(20)  NOT NULL UNIQUE,
    -- the approved sample project this production order continues
    source_project_id    BIGINT       NOT NULL REFERENCES npd_projects,
    customer_company_id  BIGINT       NOT NULL REFERENCES companies,
    part_number          VARCHAR(100) NOT NULL, -- copied from the sample project, so the customer's list needs no lookup
    customer_po_number   VARCHAR(60)  NOT NULL,
    quantity             INTEGER      NOT NULL CHECK (quantity > 0),
    required_date        DATE         NOT NULL,
    remarks              VARCHAR(2000),
    status               VARCHAR(20)  NOT NULL CHECK (status IN ('SUBMITTED', 'RETURNED', 'ACCEPTED')),
    -- why the KAM last sent it back (every return is also audited with its reason)
    return_comment       VARCHAR(2000),
    created_by_user_id   BIGINT       NOT NULL REFERENCES users,
    row_version          BIGINT       NOT NULL DEFAULT 0, -- optimistic lock
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_production_pos_customer ON production_pos (customer_company_id);
CREATE INDEX ix_production_pos_status ON production_pos (status);
-- one open production order per sample project (a repeat order follows once the earlier one was accepted)
CREATE UNIQUE INDEX ux_production_pos_open ON production_pos (source_project_id) WHERE status IN ('SUBMITTED', 'RETURNED');

-- kind: SAMPLE (the sample development project) or PRODUCTION (the OFP project). A production project continues the
-- same part, so it shares the source RFQ part with its sample project: the one-project-per-part rule is for samples only.
ALTER TABLE npd_projects
    ADD COLUMN kind              VARCHAR(12) NOT NULL DEFAULT 'SAMPLE' CHECK (kind IN ('SAMPLE', 'PRODUCTION')),
    ADD COLUMN production_po_id  BIGINT UNIQUE REFERENCES production_pos,
    ADD COLUMN source_project_id BIGINT REFERENCES npd_projects;

ALTER TABLE npd_projects DROP CONSTRAINT npd_projects_source_rfq_item_id_key;
CREATE UNIQUE INDEX ux_npd_projects_sample_part ON npd_projects (source_rfq_item_id) WHERE kind = 'SAMPLE';

-- A production project always has its production order and its sample project; a sample project has neither.
ALTER TABLE npd_projects ADD CONSTRAINT npd_projects_kind_links_check CHECK (
    (kind = 'SAMPLE' AND production_po_id IS NULL AND source_project_id IS NULL)
    OR (kind = 'PRODUCTION' AND production_po_id IS NOT NULL AND source_project_id IS NOT NULL));

-- Notifications about a production order file under it, not under a project (there is none until the KAM accepts).
ALTER TABLE notifications DROP CONSTRAINT notifications_linked_module_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_linked_module_check
    CHECK (linked_module IN ('rfq', 'quotation', 'npd', 'sample-po', 'production-po', 'ofp', 'inspection', 'ncr', 'deviation', 'document', 'company'));

-- Role sheet: the KAM decides a customer's production order and creates the OFP project (A on OFP, still limited to
-- their assigned customer accounts). Only a row still at the old shipped value is changed.
UPDATE role_module_permissions SET access_level = 'A'
WHERE role_code = 'ERGO_KAM' AND module_code = 'OFP' AND access_level = 'V';
UPDATE access_config SET version = version + 1;

-- Sidebar: the production orders next to Orders for ERGO and the customer; the OFP projects for the customer's and the
-- supplier's OFP people, who have no NPD menu.
INSERT INTO nav_items (item_key, section_title, section_order, sort_order, label, path, icon, module_code, sides) VALUES
    ('production-pos', 'Sourcing & Workflow', 1, 62, 'Production POs', '/ofp/production-pos',   'description', 'OFP', 'ERGO,CUSTOMER'),
    ('ofp-projects',   'Sourcing & Workflow', 1, 64, 'OFP projects',   '/npd/mine?kind=PRODUCTION', 'engineering', 'OFP', 'CUSTOMER,SUPPLIER');
