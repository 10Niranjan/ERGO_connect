-- Super Admin (the ERGO_ADMIN role) can only ever be held by a user of the ERGO company.
-- V2 already stops a role being *added* to a user of the wrong company type (user_role_mappings trigger). These two
-- triggers close the other two ways to break the same rule after the fact: moving a user to another company, and
-- changing a company's type while it still has users holding roles of the old type. Same invariant, enforced by the
-- database whatever code (or manual SQL) touches the data.

CREATE FUNCTION users_check_role_types_on_company_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
               FROM user_role_mappings m
                        JOIN roles r ON r.role_code = m.role_code
                        JOIN companies c ON c.company_id = NEW.company_id
               WHERE m.user_id = NEW.user_id
                 AND r.role_type <> c.company_type) THEN
        RAISE EXCEPTION 'User % holds roles that do not match the type of company %', NEW.user_id, NEW.company_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_check_role_types_on_company_change
    BEFORE UPDATE OF company_id ON users
    FOR EACH ROW
    WHEN (OLD.company_id IS DISTINCT FROM NEW.company_id)
EXECUTE FUNCTION users_check_role_types_on_company_change();

CREATE FUNCTION companies_check_role_types_on_type_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1
               FROM users u
                        JOIN user_role_mappings m ON m.user_id = u.user_id
                        JOIN roles r ON r.role_code = m.role_code
               WHERE u.company_id = NEW.company_id
                 AND r.role_type <> NEW.company_type) THEN
        RAISE EXCEPTION 'Company % has users whose roles do not match the new type %', NEW.company_id, NEW.company_type;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_companies_check_role_types_on_type_change
    BEFORE UPDATE OF company_type ON companies
    FOR EACH ROW
    WHEN (OLD.company_type IS DISTINCT FROM NEW.company_type)
EXECUTE FUNCTION companies_check_role_types_on_type_change();
