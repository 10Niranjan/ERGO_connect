-- The Suppliers screen (approved supplier list + approval queue) gets its own permission module, so it can be hidden
-- from a role without touching Customers & suppliers. Like the other fixed modules (V25) it is shown in the permission
-- matrix but not editable there: the company endpoints behind it enforce what each role may do.
-- Seeded: ERGO Admin and ERGO Management O (they add / approve), ERGO RFQ A (adds), every other role NV (hidden).
INSERT INTO app_modules (module_code, label, sort_order, configurable) VALUES ('SUPPLIERS', 'Suppliers', 145, false);

INSERT INTO role_module_permissions (role_code, module_code, access_level)
SELECT r.role_code, 'SUPPLIERS',
       CASE r.role_code WHEN 'ERGO_ADMIN' THEN 'O' WHEN 'ERGO_MANAGEMENT' THEN 'O' WHEN 'ERGO_RFQ' THEN 'A' ELSE 'NV' END
FROM roles r;

INSERT INTO nav_items (item_key, section_title, section_order, sort_order, label, path, icon, module_code, sides) VALUES
    ('suppliers', 'Administration', 4, 15, 'Suppliers', '/suppliers', 'business', 'SUPPLIERS', 'ERGO');
