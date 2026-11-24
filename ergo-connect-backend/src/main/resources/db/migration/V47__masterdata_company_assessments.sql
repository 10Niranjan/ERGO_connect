-- Customer and supplier registration, step 3: supplier audit reports and customer assessments (client answers
-- 2026-11-19, questions 6, 14, 28 and 29; ARCHITECTURE.md §8 2026-11-23).
-- A supplier audit is a report file plus a short summary, added by ERGO Quality; at least one is needed before a
-- supplier can be sent for approval. A customer assessment is simpler: a desk review with notes, a risk rating and an
-- optional file. Both are ERGO-internal and kept as history: rows are never changed or deleted.
CREATE TABLE company_assessments (
    assessment_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id            BIGINT        NOT NULL REFERENCES companies,
    kind                  VARCHAR(30)   NOT NULL CHECK (kind IN ('SUPPLIER_AUDIT', 'CUSTOMER_ASSESSMENT')),
    assessment_date       DATE          NOT NULL,
    audit_type            VARCHAR(20)   CHECK (audit_type IN ('ON_SITE', 'VIRTUAL', 'DESK_REVIEW')),
    auditor               VARCHAR(200),
    findings              VARCHAR(4000) NOT NULL, -- audit findings, or the desk review notes of a customer assessment
    recommendation        VARCHAR(10)   CHECK (recommendation IN ('APPROVE', 'REJECT')),
    risk_rating           VARCHAR(10)   CHECK (risk_rating IN ('LOW', 'MEDIUM', 'HIGH')),
    document_id           BIGINT        REFERENCES documents,
    created_by_user_id    BIGINT        NOT NULL REFERENCES users,
    acting_admin_user_id  BIGINT        REFERENCES users,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_company_assessment_shape CHECK (
        (kind = 'SUPPLIER_AUDIT' AND audit_type IS NOT NULL AND auditor IS NOT NULL AND recommendation IS NOT NULL
            AND document_id IS NOT NULL AND risk_rating IS NULL)
        OR (kind = 'CUSTOMER_ASSESSMENT' AND risk_rating IS NOT NULL AND audit_type IS NULL
            AND recommendation IS NULL))
);

CREATE INDEX ix_company_assessments_company ON company_assessments (company_id, assessment_date DESC);

-- The kind must match the company: audits for suppliers, assessments for customers. Holds for manual SQL too.
CREATE FUNCTION company_assessments_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'company_assessments rows are history and cannot be changed or deleted';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM companies c WHERE c.company_id = NEW.company_id
                   AND c.company_type = CASE NEW.kind WHEN 'SUPPLIER_AUDIT' THEN 'SUPPLIER' ELSE 'CUSTOMER' END) THEN
        RAISE EXCEPTION 'A supplier audit belongs to a supplier and a customer assessment to a customer';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_company_assessments_check
    BEFORE INSERT OR UPDATE OR DELETE ON company_assessments
    FOR EACH ROW EXECUTE FUNCTION company_assessments_check();

-- like every other history table: no TRUNCATE either
CREATE TRIGGER trg_company_assessments_no_truncate
    BEFORE TRUNCATE ON company_assessments
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();

-- ERGO Quality adds the audit reports (question 6), so it reaches the Suppliers page: it acts (adds audits) but never
-- edits, sends or approves a company, which the endpoints enforce (A on the sheet, like ERGO RFQ in V30).
UPDATE role_module_permissions SET access_level = 'A' WHERE role_code = 'ERGO_QUALITY' AND module_code = 'SUPPLIERS';
COMMENT ON TABLE company_assessments IS 'ERGO-internal: never joined into a customer or supplier DTO (client question 9)';
