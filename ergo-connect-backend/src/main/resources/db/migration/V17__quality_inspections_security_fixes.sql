-- Phase 5a security review fixes (H2, L3). V16's own comment claimed inspections' company columns are "populated
-- from the linked NPD/OFP record" - that was aspirational, not yet true when it was written: the application layer
-- now actually resolves NPD/OFP-linked company columns through npd.api/ofp.api server-side (InspectionService),
-- never trusting them from the client. Company columns stay free-form (nullable, ERGO-supplied) only for the three
-- inspection types with no backing NPD/OFP record (INCOMING/FINAL/IN_PROCESS); a supplier's own create always
-- self-tags supplier_company_id and ignores anything else supplied, regardless of linked_module.

-- H2: which company actually created the row, so Supplier Quality's edit/submit gate can be "my own company created
-- this inspection", not just "this inspection is tagged to my company" (ARCHITECTURE.md §8 2026-09-30) - an
-- ERGO-authored inspection tagged to a supplier (e.g. ERGO's own incoming inspection of that supplier's goods) must
-- stay read-only to that supplier.
ALTER TABLE inspections ADD COLUMN created_by_company_id BIGINT NOT NULL REFERENCES companies;
CREATE INDEX ix_inspections_created_by_company ON inspections (created_by_company_id);

-- L3: a customer-visible inspection must always name its customer.
ALTER TABLE inspections ADD CONSTRAINT ck_inspections_customer_visible
    CHECK (NOT customer_visible OR customer_company_id IS NOT NULL);

-- F1: inspection_date is a date the inspector records, not a moment in time (the frontend sends YYYY-MM-DD, matching
-- every other date-only field in this codebase, e.g. orders.due_date/npd_projects.target_sample_date).
ALTER TABLE inspections ALTER COLUMN inspection_date TYPE DATE;
