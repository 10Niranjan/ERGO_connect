-- A part may be with several suppliers at once; ERGO compares their quotes and selects one (2026-11-22, slice C).
-- The row is still "one part sent to one supplier" (rfq_supplier_assignments); what changes is the uniqueness:
--   * a supplier holds a part once (a recalled part can be sent again),
--   * a part has at most one SELECTED quote: the one ERGO accepted, which then goes to the customer, is decided by
--     the customer, or is sent back for correction. The database refuses a second one even when two ERGO users select
--     at the same moment.
-- CLOSED: a supplier's unfinished quote on a part the customer has decided (the supplier is not told why).

ALTER TABLE rfq_supplier_assignments DROP CONSTRAINT rfq_supplier_assignments_status_check;
ALTER TABLE rfq_supplier_assignments ADD CONSTRAINT rfq_supplier_assignments_status_check CHECK (status IN (
    'NOT_STARTED', 'DRAFT_SAVED', 'SUBMITTED', 'REVISION_REQUIRED', 'RESUBMITTED', 'ACCEPTED', 'WITH_CUSTOMER',
    'CUSTOMER_ON_HOLD', 'CUSTOMER_CORRECTION', 'CUSTOMER_APPROVED', 'CUSTOMER_REJECTED', 'RECALLED', 'CLOSED'));

DROP INDEX ux_assignments_item_active;

CREATE UNIQUE INDEX ux_assignments_item_supplier_active
    ON rfq_supplier_assignments (rfq_item_id, supplier_company_id) WHERE status <> 'RECALLED';

CREATE UNIQUE INDEX ux_assignments_item_selected
    ON rfq_supplier_assignments (rfq_item_id)
    WHERE status IN ('ACCEPTED', 'WITH_CUSTOMER', 'CUSTOMER_ON_HOLD', 'CUSTOMER_CORRECTION', 'CUSTOMER_APPROVED',
                     'CUSTOMER_REJECTED');
