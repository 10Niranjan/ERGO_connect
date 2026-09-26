-- Security review fix (Phase 3b, before commit): the Close-Stage approval gate must check that an activity's
-- APPROVED approval is the *right kind*, not just any approved approval linked to it (ARCHITECTURE.md §8
-- 2026-09-26 M2 fix). Nullable: non-null exactly when approval_required_flag is true, enforced in NpdService, not
-- here (matching npd_activities.owner_organization_type/owner_company_id, which are also app-validated, not
-- DB-cross-checked against approval_required_flag).
ALTER TABLE npd_activities ADD COLUMN required_approval_type VARCHAR(40)
    CHECK (required_approval_type IN (
        'STAGE_GATE', 'QUALITY_GATE', 'CUSTOMER_SAMPLE_APPROVAL',
        'CUSTOMER_MILESTONE_ACKNOWLEDGMENT', 'CUSTOMER_QUALITY_ACCEPTANCE'));
