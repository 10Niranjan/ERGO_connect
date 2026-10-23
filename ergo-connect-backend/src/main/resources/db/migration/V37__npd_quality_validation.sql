-- Flow doc steps 9-10: once the applicable NPD steps are done, Supplier Quality approves internally and submits the
-- project to ERGO Quality and Supplier Quality for validation; both must approve. Then Customer NPD and Customer Quality
-- review it; both must approve. A rejection sends the project back with comments (ERGO / Supplier Quality: to a
-- development stage for rework; the customer: to Final Sample Readiness for corrective action and resubmission).
--
-- validation_status: where the project is in that loop.
--   NOT_STARTED          nothing submitted yet (or sent back and waiting for a new submission)
--   IN_VALIDATION        submitted by Supplier Quality, waiting for ERGO Quality and Supplier Quality
--   VALIDATED            both approved; closing Final Sample Readiness submits the project to the customer
--   IN_CUSTOMER_REVIEW   with Customer NPD and Customer Quality
--   CUSTOMER_APPROVED    both approved; the project may leave Customer Feedback
-- validation_round counts the submissions (a rejection starts a new round).
-- Projects that already passed Final Sample Readiness before this flow existed count as customer approved.
ALTER TABLE npd_projects
    ADD COLUMN validation_status VARCHAR(25) NOT NULL DEFAULT 'NOT_STARTED'
        CHECK (validation_status IN ('NOT_STARTED', 'IN_VALIDATION', 'VALIDATED', 'IN_CUSTOMER_REVIEW', 'CUSTOMER_APPROVED')),
    ADD COLUMN validation_round  INTEGER     NOT NULL DEFAULT 1 CHECK (validation_round > 0);

UPDATE npd_projects SET validation_status = 'CUSTOMER_APPROVED'
WHERE stage IN ('CUSTOMER_FEEDBACK', 'APPROVED_FOR_PRODUCTION_HANDOVER', 'CLOSED');

-- Every approval and rejection of the loop, with the reason: the project's quality history. One decision per party per
-- round; never edited or deleted.
CREATE TABLE npd_validation_decisions (
    npd_validation_decision_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    npd_project_id             BIGINT      NOT NULL REFERENCES npd_projects,
    round_no                   INTEGER     NOT NULL CHECK (round_no > 0),
    party                      VARCHAR(20) NOT NULL
        CHECK (party IN ('SUPPLIER_INTERNAL', 'SUPPLIER_QUALITY', 'ERGO_QUALITY', 'CUSTOMER_NPD', 'CUSTOMER_QUALITY')),
    decision                   VARCHAR(10) NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    comment                    VARCHAR(2000),
    -- where a rejection sent the project back to
    return_stage               VARCHAR(40),
    decided_by_user_id         BIGINT      NOT NULL REFERENCES users,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (npd_project_id, round_no, party),
    -- a rejection always says why
    CHECK (decision = 'APPROVED' OR comment IS NOT NULL)
);

CREATE INDEX ix_npd_validation_decisions_project ON npd_validation_decisions (npd_project_id);

CREATE TRIGGER trg_npd_validation_decisions_append_only
    BEFORE UPDATE OR DELETE ON npd_validation_decisions
    FOR EACH ROW EXECUTE FUNCTION forbid_update_delete();

CREATE TRIGGER trg_npd_validation_decisions_no_truncate
    BEFORE TRUNCATE ON npd_validation_decisions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();
