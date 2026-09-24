-- Phase 1b: company approval, KAM account assignments (client decisions, ARCHITECTURE.md §8)
-- and the master lists RFQ/quotation needs (SRS §13.2.6–13.2.12).

-- Companies: an approval status replaces the active flag. ERGO Admin additions are ACTIVE at once;
-- ERGO RFQ / ERGO KAM additions wait for ERGO Admin or ERGO Management approval.
ALTER TABLE companies ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
    CHECK (status IN ('PENDING_APPROVAL', 'ACTIVE', 'INACTIVE', 'REJECTED'));
UPDATE companies SET status = CASE WHEN active THEN 'ACTIVE' ELSE 'INACTIVE' END;
ALTER TABLE companies ALTER COLUMN status DROP DEFAULT;
ALTER TABLE companies DROP COLUMN active;
ALTER TABLE companies ADD COLUMN created_by_user_id BIGINT REFERENCES users;

-- ERGO Key Account Managers see only the customer accounts assigned to them (SRS §8.1, §11.1, §13.2.5).
CREATE TABLE user_account_assignments (
    assignment_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT      NOT NULL REFERENCES users,
    customer_company_id BIGINT      NOT NULL REFERENCES companies,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, customer_company_id)
);

CREATE INDEX ix_user_account_assignments_customer ON user_account_assignments (customer_company_id);

-- Only ERGO users get accounts, and accounts are always customers: a mistake here would widen what a KAM sees.
CREATE FUNCTION user_account_assignments_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM users u JOIN companies c ON c.company_id = u.company_id
                   WHERE u.user_id = NEW.user_id AND c.company_type = 'ERGO')
        OR NOT EXISTS (SELECT 1 FROM companies c
                       WHERE c.company_id = NEW.customer_company_id AND c.company_type = 'CUSTOMER') THEN
        RAISE EXCEPTION 'Account assignments link an ERGO user to a customer company (user %, company %)',
            NEW.user_id, NEW.customer_company_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_account_assignments_check
    BEFORE INSERT OR UPDATE ON user_account_assignments
    FOR EACH ROW EXECUTE FUNCTION user_account_assignments_check();

-- Master lists. Codes are stored upper-case and never change once created; changes are audited by MasterDataService.
CREATE TABLE currencies (
    currency_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    currency_code VARCHAR(30)  NOT NULL UNIQUE,
    currency_name VARCHAR(200) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT true
);

INSERT INTO currencies (currency_code, currency_name) VALUES
    ('INR', 'Indian Rupee'),
    ('USD', 'US Dollar'),
    ('EUR', 'Euro'),
    ('RMB', 'Chinese Yuan Renminbi'); -- SRS §6 code; ISO 4217 is CNY (map it in ERP integrations)

CREATE TABLE materials (
    material_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    material_code VARCHAR(30)  NOT NULL UNIQUE,
    material_name VARCHAR(200) NOT NULL,
    specification VARCHAR(500),
    grade         VARCHAR(100),
    active        BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE processes (
    process_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    process_code VARCHAR(30)  NOT NULL UNIQUE,
    process_name VARCHAR(200) NOT NULL,
    process_type VARCHAR(20)  NOT NULL CHECK (process_type IN ('INHOUSE', 'OUTSOURCED', 'MIXED')),
    active       BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE coatings (
    coating_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coating_code  VARCHAR(30)  NOT NULL UNIQUE,
    coating_name  VARCHAR(200) NOT NULL,
    specification VARCHAR(500),
    active        BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE payment_terms (
    payment_term_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_term_code VARCHAR(30)  NOT NULL UNIQUE,
    payment_term_name VARCHAR(200) NOT NULL,
    description       VARCHAR(500),
    active            BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE incoterms (
    incoterm_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incoterm_code VARCHAR(30)  NOT NULL UNIQUE,
    incoterm_name VARCHAR(200) NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT true
);

INSERT INTO incoterms (incoterm_code, incoterm_name) VALUES -- Incoterms 2020
    ('EXW', 'Ex Works'),
    ('FCA', 'Free Carrier'),
    ('CPT', 'Carriage Paid To'),
    ('CIP', 'Carriage and Insurance Paid To'),
    ('DAP', 'Delivered at Place'),
    ('DPU', 'Delivered at Place Unloaded'),
    ('DDP', 'Delivered Duty Paid'),
    ('FAS', 'Free Alongside Ship'),
    ('FOB', 'Free on Board'),
    ('CFR', 'Cost and Freight'),
    ('CIF', 'Cost, Insurance and Freight');
