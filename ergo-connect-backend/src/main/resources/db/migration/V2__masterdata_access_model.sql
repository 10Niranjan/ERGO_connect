-- Phase 1: access model — companies, roles, users, role mappings, login sessions (SRS §13.2.1–13.2.4).
-- Decisions (ARCHITECTURE.md §8): one company per user, one active role per session, only ERGO Admin manages users.

CREATE TABLE companies (
    company_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_code          VARCHAR(30)  NOT NULL UNIQUE,
    company_name          VARCHAR(200) NOT NULL,
    company_type          VARCHAR(20)  NOT NULL CHECK (company_type IN ('ERGO', 'CUSTOMER', 'SUPPLIER')),
    address_line_1        VARCHAR(200),
    address_line_2        VARCHAR(200),
    city                  VARCHAR(100),
    state                 VARCHAR(100),
    country               VARCHAR(100),
    postal_code           VARCHAR(20),
    tax_id                VARCHAR(50),
    website               VARCHAR(200),
    primary_contact_name  VARCHAR(200),
    primary_contact_email VARCHAR(254),
    primary_contact_phone VARCHAR(30),
    active                BOOLEAN      NOT NULL DEFAULT true,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Natural key: role codes are referenced from code (@PreAuthorize) and must never change.
CREATE TABLE roles (
    role_code   VARCHAR(40)  PRIMARY KEY,
    role_name   VARCHAR(100) NOT NULL,
    role_type   VARCHAR(20)  NOT NULL CHECK (role_type IN ('ERGO', 'CUSTOMER', 'SUPPLIER')),
    active      BOOLEAN      NOT NULL DEFAULT true
);

INSERT INTO roles (role_code, role_name, role_type, active) VALUES
    ('ERGO_ADMIN',          'ERGO Admin',                   'ERGO',     true),
    ('ERGO_MANAGEMENT',     'ERGO Management',              'ERGO',     true),
    ('ERGO_KAM',            'ERGO Key Account Manager',     'ERGO',     true),
    ('ERGO_RFQ',            'ERGO RFQ',                     'ERGO',     true),
    ('ERGO_NPD',            'ERGO NPD',                     'ERGO',     true),
    ('ERGO_OFP',            'ERGO OFP',                     'ERGO',     true),
    ('ERGO_QUALITY',        'ERGO Quality',                 'ERGO',     true),
    ('CUSTOMER_RFQ',        'Customer RFQ',                 'CUSTOMER', true),
    ('CUSTOMER_NPD',        'Customer NPD',                 'CUSTOMER', true),
    ('CUSTOMER_OFP',        'Customer OFP',                 'CUSTOMER', true),
    ('CUSTOMER_QUALITY',    'Customer Quality',             'CUSTOMER', true),
    ('CUSTOMER_KAM',        'Customer Key Account Manager', 'CUSTOMER', true),
    ('CUSTOMER_MANAGEMENT', 'Customer Management',          'CUSTOMER', false), -- inactive for MVP (SRS §9.2, §16.2)
    ('SUPPLIER_RFQ',        'Supplier RFQ',                 'SUPPLIER', true),
    ('SUPPLIER_NPD',        'Supplier NPD',                 'SUPPLIER', true),
    ('SUPPLIER_OFP',        'Supplier OFP',                 'SUPPLIER', true),
    ('SUPPLIER_QUALITY',    'Supplier Quality',             'SUPPLIER', true),
    ('SUPPLIER_KAM',        'Supplier Key Account Manager', 'SUPPLIER', true);

CREATE TABLE users (
    user_id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id           BIGINT       NOT NULL REFERENCES companies,
    first_name           VARCHAR(100) NOT NULL,
    last_name            VARCHAR(100) NOT NULL,
    email                VARCHAR(254) NOT NULL,
    mobile               VARCHAR(30),
    password_hash        VARCHAR(200) NOT NULL,
    must_change_password BOOLEAN      NOT NULL DEFAULT true,
    default_role_code    VARCHAR(40)  REFERENCES roles,
    active               BOOLEAN      NOT NULL DEFAULT true,
    failed_login_count   INT          NOT NULL DEFAULT 0,
    locked_until         TIMESTAMPTZ,
    last_login_at        TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE INDEX ix_users_company ON users (company_id);

CREATE TABLE user_role_mappings (
    user_role_mapping_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT      NOT NULL REFERENCES users,
    role_code            VARCHAR(40) NOT NULL REFERENCES roles,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, role_code)
);

-- Security invariant enforced by the database: a customer user can never hold an ERGO or supplier role, etc.
CREATE FUNCTION user_role_mappings_check_type() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1
                   FROM users u
                            JOIN companies c ON c.company_id = u.company_id
                            JOIN roles r ON r.role_code = NEW.role_code
                   WHERE u.user_id = NEW.user_id
                     AND r.role_type = c.company_type) THEN
        RAISE EXCEPTION 'Role % does not match the company type of user %', NEW.role_code, NEW.user_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_user_role_mappings_check_type
    BEFORE INSERT OR UPDATE ON user_role_mappings
    FOR EACH ROW EXECUTE FUNCTION user_role_mappings_check_type();

-- One row per login. The browser holds the raw token in an HttpOnly cookie; only its SHA-256 is stored.
CREATE TABLE user_sessions (
    session_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id          BIGINT      NOT NULL REFERENCES users,
    token_hash       CHAR(64)    NOT NULL UNIQUE,
    active_role_code VARCHAR(40) NOT NULL REFERENCES roles,
    ip_address       VARCHAR(45),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_at       TIMESTAMPTZ
);

CREATE INDEX ix_user_sessions_user ON user_sessions (user_id) WHERE revoked_at IS NULL;

-- Audit log: record which entity a row belongs to, reference roles by code, and link actors to real rows.
ALTER TABLE audit_logs DROP COLUMN changed_by_role_id;
ALTER TABLE audit_logs ADD COLUMN changed_by_role_code VARCHAR(40) REFERENCES roles;
ALTER TABLE audit_logs ADD COLUMN entity_name VARCHAR(60) NOT NULL;
ALTER TABLE audit_logs ADD CONSTRAINT fk_audit_logs_user FOREIGN KEY (changed_by_user_id) REFERENCES users;
ALTER TABLE audit_logs ADD CONSTRAINT fk_audit_logs_company FOREIGN KEY (changed_by_company_id) REFERENCES companies;
DROP INDEX ix_audit_logs_record;
CREATE INDEX ix_audit_logs_record ON audit_logs (entity_name, record_id, changed_at);
