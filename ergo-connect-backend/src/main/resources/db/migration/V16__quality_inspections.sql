-- Phase 5a: Quality inspections (SRS §13.6.1-13.6.2, §14.7). Links to NPD/OFP records polymorphically via
-- linked_module/linked_record_id (no FK: the target table varies) — same approach as npd_activities.phase_name
-- being free text against npd_projects.stage. Company columns are denormalized onto inspections (not derived via a
-- join through the polymorphic link) so record-scope filtering (SRS §8.2) stays a plain indexed WHERE, matching how
-- orders/npd_projects carry customer_company_id/supplier_company_id directly rather than joining out to rfqs.

CREATE TABLE inspections (
    inspection_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    linked_module         VARCHAR(20)  NOT NULL CHECK (linked_module IN ('NPD', 'OFP', 'INCOMING', 'FINAL', 'IN_PROCESS')),
    linked_record_id      BIGINT       NOT NULL,
    -- Nullable: an incoming inspection at a supplier's site may have no customer yet resolved, and vice versa;
    -- populated from the linked NPD/OFP record's own company columns at creation time.
    customer_company_id   BIGINT       REFERENCES companies,
    supplier_company_id   BIGINT       REFERENCES companies,
    part_number           VARCHAR(100) NOT NULL,
    batch_lot_no          VARCHAR(100),
    quantity_checked      NUMERIC(14, 2) CHECK (quantity_checked >= 0),
    quantity_rejected     NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (quantity_rejected >= 0),
    result_status         VARCHAR(40)  NOT NULL DEFAULT 'OPEN' CHECK (result_status IN
        ('OPEN', 'IN_INSPECTION', 'PASSED', 'REJECTED', 'RELEASED_WITH_DEVIATION', 'CLOSED')),
    inspected_by_user_id   BIGINT       REFERENCES users, -- null until an inspector picks it up (OPEN)
    inspection_date        TIMESTAMPTZ,
    remarks                TEXT,
    -- Marks the record visible to the Customer Quality role (SRS §8.2 record-scope rule for this module).
    customer_visible       BOOLEAN      NOT NULL DEFAULT false,
    created_by_user_id     BIGINT       NOT NULL REFERENCES users,
    row_version             BIGINT       NOT NULL DEFAULT 0, -- optimistic lock (mirrors rfqs/npd_projects/orders)
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (quantity_checked IS NULL OR quantity_rejected <= quantity_checked)
);

CREATE INDEX ix_inspections_customer ON inspections (customer_company_id);
CREATE INDEX ix_inspections_supplier ON inspections (supplier_company_id);
CREATE INDEX ix_inspections_status ON inspections (result_status);
CREATE INDEX ix_inspections_linked ON inspections (linked_module, linked_record_id);
CREATE INDEX ix_inspections_inspected_by ON inspections (inspected_by_user_id);

-- Defect lines per inspection (SRS §13.6.2). No defect-category master table in the SRS: kept as free text for MVP.
CREATE TABLE inspection_defects (
    inspection_defect_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    inspection_id          BIGINT       NOT NULL REFERENCES inspections ON DELETE CASCADE,
    defect_category        VARCHAR(100) NOT NULL,
    defect_description     TEXT,
    defect_quantity        NUMERIC(14, 2) NOT NULL CHECK (defect_quantity > 0),
    severity                VARCHAR(20)  NOT NULL CHECK (severity IN ('MINOR', 'MAJOR', 'CRITICAL')),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_inspection_defects_inspection ON inspection_defects (inspection_id);
