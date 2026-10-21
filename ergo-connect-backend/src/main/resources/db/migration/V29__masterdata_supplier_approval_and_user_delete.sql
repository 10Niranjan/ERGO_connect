-- Account and company management (client decisions 2026-10-21, ARCHITECTURE.md §8):
--  * every supplier company waits for approval by ERGO Management (ERGO Admin included: Admin cannot approve suppliers),
--  * ERGO Admin deletes user accounts (soft delete: the row stays, the audit trail keeps its foreign keys),
--  * "company" notifications (a company awaits approval / was approved or rejected).

-- A deleted user is hidden everywhere, cannot sign in and cannot be reactivated. The email stays reserved.
ALTER TABLE users
    ADD COLUMN deleted_at         TIMESTAMPTZ,
    ADD COLUMN deleted_by_user_id BIGINT REFERENCES users,
    ADD CONSTRAINT ck_users_deleted CHECK (deleted_at IS NULL OR (active = false AND deleted_by_user_id IS NOT NULL));

ALTER TABLE notifications DROP CONSTRAINT notifications_linked_module_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_linked_module_check
    CHECK (linked_module IN ('rfq', 'quotation', 'npd', 'ofp', 'inspection', 'ncr', 'deviation', 'document', 'company'));
