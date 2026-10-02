-- Phase 6a: versioning, categories and secondary links for the documents module (SRS §13.7.1-2, §12.11, §14 docs
-- states). Additive extension of the Phase 2a documents/document_contents pair (V4) — `linked_module`/
-- `linked_record_id` stays the primary link so RfqService, QuoteService, OrderService and NcrService are
-- untouched (ARCHITECTURE.md §8, 2026-10-02 "Phase 6a is an extension" decision).

-- Master list of document categories (SRS §13.2.13). Same shape as materials/processes/coatings (V3).
CREATE TABLE document_categories (
    category_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    active_flag BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO document_categories (name) VALUES
    ('Drawing'),
    ('Quotation'),
    ('PPAP'),
    ('Inspection Report'),
    ('NCR/CAPA Evidence'),
    ('Shipment/POD'),
    ('Other');

ALTER TABLE documents ADD COLUMN document_category_id BIGINT      REFERENCES document_categories;
ALTER TABLE documents ADD COLUMN version_no            INTEGER     NOT NULL DEFAULT 1;
-- Previous version this row replaces, once "replace" becomes "supersede" (2026-10-02 versioning decision).
-- No ON DELETE rule: a document is never hard-deleted once locked/approved/superseded.
ALTER TABLE documents ADD COLUMN supersedes_document_id BIGINT      REFERENCES documents (document_id);
ALTER TABLE documents ADD COLUMN lock_status            VARCHAR(20) NOT NULL DEFAULT 'UNLOCKED'
    CHECK (lock_status IN ('UNLOCKED', 'LOCKED'));
ALTER TABLE documents ADD COLUMN approval_status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
    CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE documents ADD COLUMN updated_at             TIMESTAMPTZ NOT NULL DEFAULT now();
-- Security review H5: optimistic lock, matching every other versioned entity in this codebase (orders, ncrs,
-- capa_actions, rfqs, npd_projects) - a concurrent external PATCH racing an admin's approve must 409, not silently
-- revert the approval with no audit trace of the reversion.
ALTER TABLE documents ADD COLUMN row_version             BIGINT      NOT NULL DEFAULT 0;

CREATE INDEX ix_documents_category ON documents (document_category_id);
CREATE INDEX ix_documents_uploaded_by_user ON documents (uploaded_by_user_id);
CREATE INDEX ix_documents_uploaded_by_company ON documents (uploaded_by_company_id);
CREATE INDEX ix_documents_created_at ON documents (created_at);
CREATE INDEX ix_documents_lock_status ON documents (lock_status);
CREATE INDEX ix_documents_approval_status ON documents (approval_status);
CREATE UNIQUE INDEX ix_documents_supersedes ON documents (supersedes_document_id);

-- Additive secondary links (SRS §13.7.2): a document can be relevant to more than one record (e.g. an RFQ
-- drawing reused by the NPD project it became) without changing its primary linked_module/linked_record_id.
CREATE TABLE document_links (
    document_link_id BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id      BIGINT      NOT NULL REFERENCES documents (document_id) ON DELETE CASCADE,
    linked_module    VARCHAR(30) NOT NULL,
    linked_record_id BIGINT      NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, linked_module, linked_record_id)
);

CREATE INDEX ix_document_links_document ON document_links (document_id);
CREATE INDEX ix_document_links_linked ON document_links (linked_module, linked_record_id);
