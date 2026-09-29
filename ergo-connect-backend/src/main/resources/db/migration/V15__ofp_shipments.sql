-- Phase 4c: OFP shipments (SRS §13.5.5, §14.6). ERGO OFP/Admin-only write surface (§10.7's literal matrix gives
-- Supplier OFP RV only, no shipment/tracking fields in its E** footnote); Customer OFP is pure read-only.
-- 1 order : many shipments (partial/multi-shipment dispatch is not restricted by §13.5.1/§13.5.5). No company
-- columns here: scope is entirely via order_id -> orders.customer_company_id/supplier_company_id, same pattern as
-- order_material_status/order_production_progress/order_packaging_dispatch (V12/V14). Audit-only, no version table
-- (§15.4 doesn't list OFP/orders/shipments). See ARCHITECTURE.md §8, 2026-09-29 "4c scope" entry for full reasoning,
-- including why receipt_confirmed_by_customer_flag is ERGO-set (not a customer self-service action) and why
-- POD_document_id is a plain FK into the already-committed `documents` table (V4) rather than a new documents-like
-- column here.
--
-- orders.current_status (V12) already lists DISPATCHED/IN_TRANSIT/DELIVERED/CLOSED in its CHECK constraint
-- (verified directly against V12's own constraint list), so shipments.status below mirrors those same four names
-- without needing any change there.

CREATE TABLE shipments (
    shipment_id                        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id                           BIGINT      NOT NULL REFERENCES orders,
    -- Nullable: a shipment record can be opened (tracking number, transporter) before its mode is finalized,
    -- same "fill in as it's known" shape as this module's other optional descriptive fields.
    shipment_mode_id                   BIGINT      REFERENCES shipment_modes,
    transporter_name                   VARCHAR(200),
    forwarder_name                     VARCHAR(200),
    tracking_number                    VARCHAR(100),
    awb_bl_no                          VARCHAR(100),
    eta                                DATE,
    actual_delivery_date               DATE,
    -- Forward reference into the Documents module (`documents.document_id`, V4__rfq_core_and_documents.sql already
    -- has this table with linked_module/linked_record_id columns of its own) — a real FK, not a placeholder, since
    -- the table already exists.
    pod_document_id                    BIGINT      REFERENCES documents (document_id),
    -- Set by ERGO OFP/Admin on the customer's behalf (e.g. after a phone/email confirmation); no direct customer
    -- write path exists for this column (application-layer rule, ARCHITECTURE.md §8 2026-09-29).
    receipt_confirmed_by_customer_flag BOOLEAN     NOT NULL DEFAULT false,
    receipt_confirmed_at               TIMESTAMPTZ,
    -- Mirrors the order-level status names for this segment (OrderStatus.java); no separate enum invented.
    status                             VARCHAR(40) NOT NULL DEFAULT 'DISPATCHED'
        CHECK (status IN ('DISPATCHED', 'IN_TRANSIT', 'DELIVERED')),
    updated_by_user_id                 BIGINT      NOT NULL REFERENCES users,
    updated_at                         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_shipments_order ON shipments (order_id);
CREATE INDEX ix_shipments_status ON shipments (status);
