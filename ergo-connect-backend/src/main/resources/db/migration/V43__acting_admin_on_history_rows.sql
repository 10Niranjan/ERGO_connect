-- A Super Admin acting as another user ("Login as") must stay visible on the rows that screens show as "by <user>"
-- and on the immutable version/history snapshots (SRS §15.4), not only in audit_logs (V42). NULL = the user acted
-- themselves. Set by the app (AuditEntityListener for JPA rows, the insert itself for the two JDBC version tables).
ALTER TABLE supplier_quote_versions  ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
ALTER TABLE deviation_versions       ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
ALTER TABLE npd_stage_history        ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
ALTER TABLE npd_validation_decisions ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
ALTER TABLE npd_prompt_responses     ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
ALTER TABLE comments                 ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
ALTER TABLE documents                ADD COLUMN acting_admin_user_id BIGINT REFERENCES users;
