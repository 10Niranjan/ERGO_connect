-- Phase 6c: threaded per-record comments (SRS §13.7.3, §10.10, §12.12.1). A comment belongs to one record of one
-- module (linked_module / linked_record_id, same free-string convention as documents), carries an audience
-- (comment_type: internal = ERGO only; external-customer = ERGO + that record's customer; external-supplier = ERGO +
-- that record's supplier) and may have one level of replies. Not append-only: the author may edit until someone
-- replies or the query is resolved (client decision, ARCHITECTURE.md §8 2026-10-02); every change is audited.
-- SRS 13.7.3's editable_flag is derived from that rule (replies / resolved), not stored.

CREATE TABLE comments (
    comment_id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    linked_module         VARCHAR(30)   NOT NULL
        CHECK (linked_module IN ('rfq', 'quotation', 'npd', 'ofp', 'inspection', 'ncr', 'deviation')),
    linked_record_id      BIGINT        NOT NULL,
    comment_type          VARCHAR(30)   NOT NULL
        CHECK (comment_type IN ('INTERNAL', 'EXTERNAL_CUSTOMER', 'EXTERNAL_SUPPLIER')),
    -- The one external company that may read this comment, fixed when it is written (security review H1): a record
    -- whose customer/supplier is later reassigned must not hand the old party's thread to the new one. NULL = INTERNAL.
    audience_company_id   BIGINT        REFERENCES companies,
    -- One level only: a reply cannot itself be replied to (enforced in CommentService; the CHECK below only keeps
    -- the query / escalation markers off replies).
    parent_comment_id     BIGINT        REFERENCES comments (comment_id),
    comment_text          VARCHAR(4000) NOT NULL,
    created_by_user_id    BIGINT        NOT NULL REFERENCES users,
    created_by_company_id BIGINT        NOT NULL REFERENCES companies,
    -- Unresolved-query marker (SRS §12.12.1): a top-level comment flagged as a question, closed by resolved_at.
    query_flag            BOOLEAN       NOT NULL DEFAULT false,
    resolved_at           TIMESTAMPTZ,
    resolved_by_user_id   BIGINT        REFERENCES users,
    -- Escalation marker: set by ERGO only.
    escalated_flag        BOOLEAN       NOT NULL DEFAULT false,
    escalated_at          TIMESTAMPTZ,
    escalated_by_user_id  BIGINT        REFERENCES users,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    row_version           BIGINT        NOT NULL DEFAULT 0,
    CHECK (parent_comment_id IS NULL OR (NOT query_flag AND NOT escalated_flag)),
    CHECK ((resolved_at IS NULL) = (resolved_by_user_id IS NULL)),
    CHECK ((escalated_flag) = (escalated_at IS NOT NULL)),
    CHECK ((comment_type = 'INTERNAL') = (audience_company_id IS NULL))
);

CREATE INDEX ix_comments_record ON comments (linked_module, linked_record_id);
CREATE INDEX ix_comments_parent ON comments (parent_comment_id);
CREATE INDEX ix_comments_company ON comments (created_by_company_id);
CREATE INDEX ix_comments_audience ON comments (audience_company_id);
CREATE INDEX ix_comments_open_queries ON comments (linked_module) WHERE query_flag AND resolved_at IS NULL;
CREATE INDEX ix_comments_escalated ON comments (linked_module) WHERE escalated_flag;

-- Who a comment mentions (SRS §12.12.1). Notifications off these rows arrive with Phase 6e.
CREATE TABLE comment_mentions (
    comment_id        BIGINT      NOT NULL REFERENCES comments (comment_id) ON DELETE CASCADE,
    mentioned_user_id BIGINT      NOT NULL REFERENCES users,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, mentioned_user_id)
);

CREATE INDEX ix_comment_mentions_user ON comment_mentions (mentioned_user_id);
