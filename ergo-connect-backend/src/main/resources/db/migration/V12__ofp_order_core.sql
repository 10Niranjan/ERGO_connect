-- Phase 4a: OFP order core (SRS §13.5.1-13.5.3, §14.6). ERGO-only for this slice: order creation (via NPD handover
-- or a direct standalone create for repeat/running orders) and production tracking up to Packaging in Progress.
-- 4b adds order_packaging_dispatch and supplier participation; 4c adds shipments (ARCHITECTURE.md §8, 2026-09-28).

CREATE SEQUENCE order_number_seq;

CREATE TABLE orders (
    order_id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number             VARCHAR(20)  NOT NULL UNIQUE,
    customer_company_id      BIGINT       NOT NULL REFERENCES companies,
    -- ERGO-internal only: the customer never learns which supplier is fulfilling the order (§8.1 anonymity rule,
    -- already extended to NPD, extended here too).
    supplier_company_id      BIGINT       NOT NULL REFERENCES companies,
    -- Set by NPD's "Handover to OFP" action; both null for a direct standalone create (repeat/running orders,
    -- SRS §13.5.1 lists them nullable — ERGO OFP has a plain Create right too, not just a handover consequence).
    linked_npd_project_id    BIGINT       REFERENCES npd_projects,
    linked_rfq_id            BIGINT       REFERENCES rfqs,
    customer_po_no           VARCHAR(100),
    supplier_po_no           VARCHAR(100),
    part_number              VARCHAR(100) NOT NULL,
    revision                 VARCHAR(30),
    ordered_quantity         NUMERIC(14, 2) NOT NULL CHECK (ordered_quantity > 0),
    due_date                 DATE,
    committed_dispatch_date  DATE,
    actual_dispatch_date     DATE,
    -- No price/currency column here (client decision): §13.5.1 has none; order value, if ever shown on a
    -- dashboard, is joined from the linked RFQ/quotation instead of duplicated.
    current_status           VARCHAR(40)  NOT NULL CHECK (current_status IN (
        'ORDER_CREATED', 'MATERIAL_ORDERED', 'MATERIAL_RECEIVED', 'PRODUCTION_STARTED', 'IN_INSPECTION',
        'PACKAGING_IN_PROGRESS', 'DISPATCH_READY', 'DISPATCHED', 'IN_TRANSIT', 'DELIVERED', 'CLOSED', 'ON_HOLD')),
    owner_user_id            BIGINT       REFERENCES users,
    row_version              BIGINT       NOT NULL DEFAULT 0, -- optimistic lock (mirrors rfqs/npd_projects)
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_orders_customer ON orders (customer_company_id);
CREATE INDEX ix_orders_supplier ON orders (supplier_company_id);
CREATE INDEX ix_orders_status ON orders (current_status);
CREATE INDEX ix_orders_npd_project ON orders (linked_npd_project_id);
CREATE INDEX ix_orders_rfq ON orders (linked_rfq_id);

-- Incoming material tracking (SRS §13.5.2). material_status/inspection_status have no SRS-given value list
-- (self-decided, no access-control impact, same footing as npd_issues_risks.severity/status in 3b).
CREATE TABLE order_material_status (
    order_material_status_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id                 BIGINT      NOT NULL REFERENCES orders,
    material_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (material_status IN ('PENDING', 'ORDERED', 'RECEIVED', 'DELAYED')),
    material_order_date      DATE,
    expected_receipt_date    DATE,
    actual_receipt_date      DATE,
    inspection_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (inspection_status IN ('PENDING', 'PASSED', 'FAILED')),
    remarks                  TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_order_material_status_order ON order_material_status (order_id);

-- Per-process-step production tracking (SRS §13.5.3).
CREATE TABLE order_production_progress (
    production_progress_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id                BIGINT       NOT NULL REFERENCES orders,
    process_step_name       VARCHAR(200) NOT NULL,
    planned_quantity        NUMERIC(14, 2) CHECK (planned_quantity >= 0),
    completed_quantity      NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (completed_quantity >= 0),
    accepted_quantity       NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (accepted_quantity >= 0),
    rejected_quantity       NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (rejected_quantity >= 0),
    rework_quantity         NUMERIC(14, 2) NOT NULL DEFAULT 0 CHECK (rework_quantity >= 0),
    -- No SRS-given value list (self-decided); reuses npd_activities' own step-status set for consistency.
    status                  VARCHAR(20)  NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'BLOCKED')),
    blocker_reason           TEXT, -- mandatory at the service layer when status = BLOCKED / order goes ON_HOLD (SRS §14.6)
    revised_dispatch_date   DATE,
    updated_by_user_id      BIGINT       NOT NULL REFERENCES users,
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_order_production_progress_order ON order_production_progress (order_id);
CREATE INDEX ix_order_production_progress_status ON order_production_progress (status);
