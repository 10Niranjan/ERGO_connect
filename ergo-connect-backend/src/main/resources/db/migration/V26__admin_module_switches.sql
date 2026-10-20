-- Module switches: one platform-wide ceiling per configurable module, chosen with the same levels as the role sheet.
-- A role's effective level on a module is the LOWER of its own level (role_module_permissions) and the module's switch:
--   O  on        - the module works as the role sheet says (default, changes nothing)
--   A  no final approvals - nobody can give the module's final approval, everything else works
--   V  read-only - nobody can create, edit, submit or approve; records stay readable
--   NV hidden    - the module is closed for every role (menu, pages, API, dashboard); data stays untouched
-- Client decision 2026-10-20 (ARCHITECTURE.md section 8). Only configurable modules can be switched: a fixed module's
-- backend checks are not level-aware, so a switch there would only hide its menu entry.

ALTER TABLE app_modules ADD COLUMN access_cap VARCHAR(2) NOT NULL DEFAULT 'O'
    CHECK (access_cap IN ('NV', 'V', 'A', 'O'));
ALTER TABLE app_modules ADD CONSTRAINT ck_switch_only_configurable CHECK (configurable OR access_cap = 'O');

-- audit_logs.record_id needs a BIGINT for the module row, like permission_id for a role cell
ALTER TABLE app_modules ADD COLUMN module_id BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE app_modules ADD CONSTRAINT ux_app_modules_module_id UNIQUE (module_id);
