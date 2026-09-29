-- Phase 4b: OFP packaging & dispatch (SRS §13.5.4, §14.6). Supplier-fillable readiness/dispatch record on an
-- assigned order. dispatch_ready_flag is a supplier readiness claim only; advancing orders.current_status to
-- DISPATCH_READY stays a separate ERGO OFP/Admin action (ARCHITECTURE.md §8, 2026-09-29).
--
-- Note: orders.current_status (V12) already lists DISPATCH_READY/DISPATCHED/IN_TRANSIT/DELIVERED/CLOSED in its
-- CHECK constraint, so no change is needed there for 4b.

CREATE TABLE order_packaging_dispatch (
    packaging_dispatch_id BIGINT         GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id               BIGINT         NOT NULL REFERENCES orders,
    packed_quantity         NUMERIC(14, 2) CHECK (packed_quantity >= 0),
    -- No SRS-given value list (self-decided, same footing as order_material_status.material_status/
    -- inspection_status and order_production_progress.status in V12).
    packaging_status        VARCHAR(20)    NOT NULL DEFAULT 'PENDING'
        CHECK (packaging_status IN ('PENDING', 'IN_PROGRESS', 'PACKED')),
    dispatch_ready_flag     BOOLEAN        NOT NULL DEFAULT false,
    dispatch_date           DATE,
    dispatch_remarks        TEXT,
    updated_by_user_id      BIGINT         NOT NULL REFERENCES users,
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX ix_order_packaging_dispatch_order ON order_packaging_dispatch (order_id);
