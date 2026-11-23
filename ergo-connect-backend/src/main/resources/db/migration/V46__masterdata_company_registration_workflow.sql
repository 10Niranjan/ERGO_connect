-- Customer and supplier registration, step 1: the approval workflow (client answers 2026-11-19, ARCHITECTURE.md §8
-- 2026-11-23). A registration starts as a DRAFT and is sent for approval explicitly; ERGO Management approves,
-- rejects or asks for more information (INFO_REQUESTED). Existing companies keep their status.
ALTER TABLE companies DROP CONSTRAINT companies_status_check;
ALTER TABLE companies ADD CONSTRAINT companies_status_check
    CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'INFO_REQUESTED', 'ACTIVE', 'INACTIVE', 'REJECTED'));

-- Management's latest comment (its question when it asks for more information), shown to ERGO on the company.
-- The full history of decisions and comments stays in audit_logs.
ALTER TABLE companies ADD COLUMN review_comment VARCHAR(1000);

-- Who last sent the company for approval: neither they nor the person who registered it may review it (approval
-- separate from edit, SRS §8.1 / §11.3; a user with both ERGO RFQ and ERGO Management could otherwise do both).
ALTER TABLE companies ADD COLUMN submitted_by_user_id BIGINT REFERENCES users;

-- Optimistic lock: two reviewers deciding at once, or a review racing an edit, cannot both win.
ALTER TABLE companies ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

-- Automatic company numbers for new companies (question 27): CUS-00001, SUP-00001. Existing codes are kept.
-- ERGO Admin still registers, edits and deactivates companies but no longer approves them (question 11): the role
-- sheet shows that (A, not O). Fixed modules, so this only changes what the Permission Management screen shows.
UPDATE role_module_permissions SET access_level = 'A'
WHERE role_code = 'ERGO_ADMIN' AND module_code IN ('COMPANIES', 'SUPPLIERS');

CREATE SEQUENCE company_customer_number_seq;
CREATE SEQUENCE company_supplier_number_seq;
