-- Shipment modes master (SRS §13.2.15), landed with Phase 4a per the 2026-09-28 decision even though it is only
-- consumed by the shipments table in 4c — the migration only needs to run once. Same shape as every other simple
-- master list (currencies/materials/processes/coatings/payment_terms/incoterms, V3__masterdata_companies_and_lookups.sql).
CREATE TABLE shipment_modes (
    shipment_mode_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    shipment_mode_code VARCHAR(30)  NOT NULL UNIQUE,
    shipment_mode_name VARCHAR(200) NOT NULL,
    active             BOOLEAN      NOT NULL DEFAULT true
);
