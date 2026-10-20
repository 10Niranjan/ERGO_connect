-- Dynamic permission management: the role-by-module access sheet ("role access.xlsx", ARCHITECTURE.md §8 2026-10-19) and
-- the sidebar menu move from code constants into the database. The seed below is EXACTLY the sheet and menu that were in
-- code (ModuleAccess.java, Sidebar.tsx), so behaviour is unchanged until ERGO Admin edits a cell.
-- Levels: NV no access, V view, A create/edit/submit, O final approval (a customer-side O is the sheet's "CO").

CREATE TABLE app_modules (
    module_code  VARCHAR(30) PRIMARY KEY,
    label        VARCHAR(80) NOT NULL,
    sort_order   INT         NOT NULL,
    -- false = the module's backend checks are still fixed in code: shown in the matrix, never editable there
    configurable BOOLEAN     NOT NULL DEFAULT false
);

INSERT INTO app_modules (module_code, label, sort_order, configurable) VALUES
    ('RFQ',            'RFQ',                   10, true),
    ('QUOTATION',      'Quotation',             20, true),
    ('NPD',            'NPD',                   30, true),
    ('OFP',            'OFP / Orders',          40, true),
    ('QUALITY',        'Quality',               50, true),
    ('DASHBOARD',      'Dashboard',            110, false),
    ('DOCUMENTS',      'Documents',            120, false),
    ('COMMUNICATION',  'Communication',        130, false),
    ('COMPANIES',      'Customers & suppliers', 140, false),
    ('COMPANY_PROFILE','Company profile',      150, false),
    ('MASTER_DATA',    'Master data',          160, false),
    ('USERS',          'Users',                170, false),
    ('PERMISSIONS',    'Permission Management', 180, false);

CREATE TABLE role_module_permissions (
    permission_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- audit_logs.record_id needs a BIGINT
    role_code          VARCHAR(40) NOT NULL REFERENCES roles,
    module_code        VARCHAR(30) NOT NULL REFERENCES app_modules,
    access_level       VARCHAR(2)  NOT NULL CHECK (access_level IN ('NV', 'V', 'A', 'O')),
    updated_by_user_id BIGINT REFERENCES users,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (role_code, module_code),
    -- ERGO Admin never creates or approves business records (client decision 2026-10-20); also enforced by the service
    CONSTRAINT ck_ergo_admin_view_only CHECK (role_code <> 'ERGO_ADMIN' OR access_level IN ('NV', 'V')
        OR module_code NOT IN ('RFQ', 'QUOTATION', 'NPD', 'OFP', 'QUALITY'))
);

-- One row: bumped by every permission save. Optimistic concurrency for the editor and the cache-refresh signal.
CREATE TABLE access_config (
    singleton BOOLEAN PRIMARY KEY DEFAULT true CHECK (singleton),
    version   BIGINT NOT NULL DEFAULT 1
);
INSERT INTO access_config (singleton, version) VALUES (true, 1);

-- The five workflow modules: the signed-off sheet. Columns: RFQ, QUOTATION, NPD, OFP, QUALITY.
INSERT INTO role_module_permissions (role_code, module_code, access_level)
SELECT s.role_code, m.module_code, m.access_level
FROM (VALUES
    ('ERGO_ADMIN',          'V',  'V',  'V',  'V',  'V'),
    ('ERGO_MANAGEMENT',     'O',  'O',  'O',  'O',  'O'),
    ('ERGO_KAM',            'A',  'V',  'V',  'V',  'V'),
    ('ERGO_RFQ',            'O',  'O',  'V',  'V',  'V'),
    ('ERGO_NPD',            'V',  'V',  'O',  'V',  'V'),
    ('ERGO_OFP',            'NV', 'NV', 'V',  'O',  'A'),
    ('ERGO_QUALITY',        'NV', 'NV', 'A',  'A',  'O'),
    ('CUSTOMER_RFQ',        'O',  'O',  'V',  'NV', 'V'),
    ('CUSTOMER_NPD',        'V',  'NV', 'O',  'NV', 'O'),
    ('CUSTOMER_OFP',        'NV', 'NV', 'NV', 'O',  'V'),
    ('CUSTOMER_QUALITY',    'V',  'NV', 'A',  'V',  'O'),
    ('CUSTOMER_KAM',        'A',  'A',  'V',  'V',  'V'),
    ('CUSTOMER_MANAGEMENT', 'A',  'A',  'A',  'A',  'A'),
    ('SUPPLIER_RFQ',        'A',  'A',  'NV', 'NV', 'NV'),
    ('SUPPLIER_NPD',        'NV', 'NV', 'A',  'NV', 'V'),
    ('SUPPLIER_OFP',        'NV', 'NV', 'NV', 'A',  'V'),
    ('SUPPLIER_QUALITY',    'V',  'NV', 'A',  'A',  'A'),
    ('SUPPLIER_KAM',        'A',  'A',  'A',  'A',  'A')
) AS s(role_code, rfq, quotation, npd, ofp, quality)
CROSS JOIN LATERAL (VALUES
    ('RFQ', s.rfq), ('QUOTATION', s.quotation), ('NPD', s.npd), ('OFP', s.ofp), ('QUALITY', s.quality)
) AS m(module_code, access_level);

-- The pages that are not (yet) configurable: their effective access today, so the dynamic sidebar and route guards
-- show them to exactly the roles that had them. The backend checks behind them stay in code; making one configurable
-- later is: flip app_modules.configurable and replace that controller's role list with @access.*.
INSERT INTO role_module_permissions (role_code, module_code, access_level)
SELECT r.role_code, m.module_code,
       CASE m.module_code
           WHEN 'DASHBOARD'     THEN 'V'
           WHEN 'COMMUNICATION' THEN 'A'
           WHEN 'DOCUMENTS'     THEN CASE WHEN r.role_code = 'ERGO_ADMIN' THEN 'O' ELSE 'A' END
           WHEN 'COMPANIES'     THEN CASE
               WHEN r.role_code IN ('ERGO_ADMIN', 'ERGO_MANAGEMENT') THEN 'O'
               WHEN r.role_code IN ('ERGO_RFQ', 'ERGO_KAM') THEN 'A'
               ELSE 'NV' END
           WHEN 'COMPANY_PROFILE' THEN CASE
               WHEN r.role_type = 'ERGO' THEN 'NV'
               WHEN r.role_code IN ('CUSTOMER_RFQ', 'CUSTOMER_KAM', 'CUSTOMER_MANAGEMENT', 'SUPPLIER_RFQ', 'SUPPLIER_KAM') THEN 'A'
               ELSE 'V' END
           WHEN 'MASTER_DATA'   THEN CASE r.role_code WHEN 'ERGO_ADMIN' THEN 'O' WHEN 'ERGO_MANAGEMENT' THEN 'V' ELSE 'NV' END
           ELSE CASE WHEN r.role_code = 'ERGO_ADMIN' THEN 'O' ELSE 'NV' END -- USERS, PERMISSIONS
       END
FROM roles r
CROSS JOIN (VALUES ('DASHBOARD'), ('DOCUMENTS'), ('COMMUNICATION'), ('COMPANIES'), ('COMPANY_PROFILE'),
                   ('MASTER_DATA'), ('USERS'), ('PERMISSIONS')) AS m(module_code);

-- The sidebar. A role sees an item when its level on module_code is at least min_level and its company type is in sides.
-- Structure (labels, order, paths) lives here; the pages themselves are routes in the frontend.
CREATE TABLE nav_items (
    nav_item_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    item_key      VARCHAR(60)  NOT NULL UNIQUE,
    section_title VARCHAR(60), -- null = the unheaded top group
    section_order INT          NOT NULL,
    sort_order    INT          NOT NULL,
    label         VARCHAR(80)  NOT NULL,
    path          VARCHAR(120) NOT NULL,
    icon          VARCHAR(40)  NOT NULL,
    module_code   VARCHAR(30)  NOT NULL REFERENCES app_modules,
    min_level     VARCHAR(2)   NOT NULL DEFAULT 'V' CHECK (min_level IN ('V', 'A', 'O')),
    sides         VARCHAR(40)  NOT NULL DEFAULT 'ERGO,CUSTOMER,SUPPLIER',
    active        BOOLEAN      NOT NULL DEFAULT true
);

INSERT INTO nav_items (item_key, section_title, section_order, sort_order, label, path, icon, module_code, sides) VALUES
    ('dashboard',        NULL,                  0, 10, 'Dashboard',             '/',                     'dashboard',   'DASHBOARD',       'ERGO,CUSTOMER,SUPPLIER'),
    ('rfqs',             'Sourcing & Workflow', 1, 10, 'RFQs',                  '/rfqs',                 'rfq',         'RFQ',             'ERGO,CUSTOMER'),
    ('assigned-rfqs',    'Sourcing & Workflow', 1, 20, 'Assigned RFQs',         '/quotes',               'assignment',  'RFQ',             'SUPPLIER'),
    ('npd',              'Sourcing & Workflow', 1, 30, 'NPD',                   '/npd',                  'engineering', 'NPD',             'ERGO'),
    ('npd-customer',     'Sourcing & Workflow', 1, 40, 'NPD',                   '/npd/mine',             'engineering', 'NPD',             'CUSTOMER'),
    ('npd-tasks',        'Sourcing & Workflow', 1, 50, 'NPD Tasks',             '/npd/mine',             'engineering', 'NPD',             'SUPPLIER'),
    ('orders',           'Sourcing & Workflow', 1, 60, 'Orders',                '/ofp',                  'shipping',    'OFP',             'ERGO,CUSTOMER,SUPPLIER'),
    ('inspections',      'Quality',             2, 10, 'Inspections',           '/quality',              'inspection',  'QUALITY',         'ERGO,CUSTOMER,SUPPLIER'),
    ('ncrs',             'Quality',             2, 20, 'NCR / CAPA',            '/quality/ncrs',         'ncr',         'QUALITY',         'ERGO,CUSTOMER,SUPPLIER'),
    ('deviations',       'Quality',             2, 30, 'Deviation Approvals',   '/quality/deviations',   'deviation',   'QUALITY',         'ERGO,CUSTOMER,SUPPLIER'),
    ('documents',        'Workspace',           3, 10, 'Documents',             '/documents',            'folder',      'DOCUMENTS',       'ERGO,CUSTOMER,SUPPLIER'),
    ('communication',    'Workspace',           3, 20, 'Communication',         '/communication',        'chat',        'COMMUNICATION',   'ERGO,CUSTOMER,SUPPLIER'),
    ('companies',        'Administration',      4, 10, 'Customers & suppliers', '/companies',            'business',    'COMPANIES',       'ERGO'),
    ('company-profile',  'Administration',      4, 20, 'Company profile',       '/company',              'account',     'COMPANY_PROFILE', 'CUSTOMER,SUPPLIER'),
    ('master-data',      'Administration',      4, 30, 'Master data',           '/admin/master-data',    'tune',        'MASTER_DATA',     'ERGO'),
    ('users',            'ERGO Admin',          5, 10, 'Users',                 '/admin/users',          'people',      'USERS',           'ERGO'),
    ('permissions',      'ERGO Admin',          5, 20, 'Permission Management', '/admin/permissions',    'security',    'PERMISSIONS',     'ERGO');
