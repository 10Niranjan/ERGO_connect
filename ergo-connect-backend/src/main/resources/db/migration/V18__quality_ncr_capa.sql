-- Phase 5b: NCRs and CAPA actions (SRS §13.6.3, §13.6.5, §14.7). Same polymorphic linked_module/linked_record_id
-- link as inspections (V16) — an NCR can be raised against an NPD, OFP, or any inspection type. Company columns are
-- denormalized (nullable) for the same record-scope-filtering reason as inspections; created_by_user_id/
-- created_by_company_id are present from the start here (V16 had to add them retroactively in V17's H2 fix).
-- Number format matches rfqs/npd_projects: an app-side sequence, "NCR-YYYY-NNNNN" (RfqService/NpdService pattern).

CREATE SEQUENCE ncr_number_seq;

CREATE TABLE ncrs (
    ncr_id                 BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ncr_number              VARCHAR(20)  NOT NULL UNIQUE,
    linked_module           VARCHAR(20)  NOT NULL CHECK (linked_module IN ('NPD', 'OFP', 'INCOMING', 'FINAL', 'IN_PROCESS')),
    linked_record_id        BIGINT       NOT NULL,
    customer_company_id     BIGINT       REFERENCES companies,
    supplier_company_id     BIGINT       REFERENCES companies,
    -- Free text, no lookup table — same call as inspection_defects.defect_category (V16).
    issue_category           VARCHAR(100) NOT NULL,
    issue_description        TEXT         NOT NULL,
    -- Filled progressively as the NCR moves through its workflow (§14.7); null until that stage is reached.
    containment_action        TEXT,
    root_cause                 TEXT,
    corrective_action           TEXT,
    preventive_action            TEXT,
    owner_user_id                 BIGINT       REFERENCES users, -- assigned owner; null until someone picks it up
    target_closure_date            DATE         NOT NULL,
    closure_date                    DATE,
    -- No SRS-enumerated value set for this one (unlike current_status below) — plain text for MVP.
    effectiveness_status             VARCHAR(40),
    -- Security review H2: gates the customer's OWN view only (mirrors inspections.customer_visible, V16) - false
    -- until an ERGO editor turns it on, so supplier-authored content raised against/by a customer's supplier is not
    -- shared with that customer the moment it exists, unreviewed.
    customer_visible                 BOOLEAN      NOT NULL DEFAULT false,
    current_status                    VARCHAR(40)  NOT NULL DEFAULT 'OPEN' CHECK (current_status IN
        ('OPEN', 'CONTAINMENT_IN_PROGRESS', 'ROOT_CAUSE_IN_PROGRESS', 'CAPA_IN_PROGRESS',
         'PENDING_CLOSURE_APPROVAL', 'CLOSED')),
    created_by_user_id                 BIGINT       NOT NULL REFERENCES users,
    created_by_company_id               BIGINT       NOT NULL REFERENCES companies,
    -- Security review H3: who actually moved this NCR into PENDING_CLOSURE_APPROVAL (set atomically by
    -- NcrService.submitForClosure, never a separately-editable assignment field like owner_user_id) - closes the
    -- self-approval gap owner_user_id alone left open, since it is null by default and reassignable.
    closure_requested_by_user_id         BIGINT       REFERENCES users,
    row_version                          BIGINT       NOT NULL DEFAULT 0,
    created_at                            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                             TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_ncrs_customer ON ncrs (customer_company_id);
CREATE INDEX ix_ncrs_supplier ON ncrs (supplier_company_id);
CREATE INDEX ix_ncrs_status ON ncrs (current_status);
CREATE INDEX ix_ncrs_linked ON ncrs (linked_module, linked_record_id);
CREATE INDEX ix_ncrs_owner ON ncrs (owner_user_id);
CREATE INDEX ix_ncrs_created_by_company ON ncrs (created_by_company_id);
CREATE INDEX ix_ncrs_created_by_user ON ncrs (created_by_user_id);

-- CAPA actions per NCR (SRS §13.6.5). Not append-only (unlike SRS §15.4's version tables): an owner edits a CAPA
-- action in place as it progresses, same as inspections/NCRs themselves. Cascades with its parent NCR.
CREATE TABLE capa_actions (
    capa_action_id        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ncr_id                 BIGINT      NOT NULL REFERENCES ncrs ON DELETE CASCADE,
    action_type              VARCHAR(20) NOT NULL CHECK (action_type IN ('CORRECTIVE', 'PREVENTIVE')),
    action_description        TEXT        NOT NULL,
    owner_user_id               BIGINT      REFERENCES users,
    target_date                   DATE        NOT NULL,
    -- SRS doesn't enumerate CAPA-specific states; the NCR's own CAPA_IN_PROGRESS covers the parent record while its
    -- individual actions track their own simple lifecycle underneath it.
    status                          VARCHAR(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN
        ('OPEN', 'IN_PROGRESS', 'CLOSED')),
    -- Real FK into the already-committed documents table (V4), same call as shipments.pod_document_id (V15) — not a
    -- placeholder column, the target table exists.
    evidence_document_id             BIGINT      REFERENCES documents (document_id),
    closed_at                          TIMESTAMPTZ,
    -- Security review L2: optimistic lock + timestamp, matching ncrs.row_version/updated_at, so a concurrent edit of
    -- the same CAPA action fails loudly (409) instead of silently overwriting the other.
    row_version                          BIGINT      NOT NULL DEFAULT 0,
    created_at                           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_capa_actions_ncr ON capa_actions (ncr_id);
CREATE INDEX ix_capa_actions_status ON capa_actions (status);
CREATE INDEX ix_capa_actions_owner ON capa_actions (owner_user_id);
CREATE INDEX ix_capa_actions_evidence ON capa_actions (evidence_document_id);
