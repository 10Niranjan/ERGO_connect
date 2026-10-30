-- Super Admin "Login as" now acts as the viewed user (docs/PERMISSIONS.md section 13). The audit row keeps the
-- viewed user as the actor (the record shows them as its author, as the workflow expects) and names the Super Admin
-- who was really behind the change here. Null for every ordinary change.
ALTER TABLE audit_logs ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;

-- "what did Super Admin X do as other users" without a full scan
CREATE INDEX ix_audit_logs_acting_admin ON audit_logs (acting_admin_user_id, changed_at)
    WHERE acting_admin_user_id IS NOT NULL;
