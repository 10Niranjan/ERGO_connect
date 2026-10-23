-- Flow doc §2-4: ERGO distributes the RFQ to suppliers, either whole or split into sub-RFQs (a set of parts per
-- supplier). A supplier quotes each part number on its own, ERGO reviews each quote (revision required / accepted) and
-- generates one customer quotation with a line per part. Status is kept per part.
--
-- The existing invitation row (rfq_supplier_assignments) becomes "one part sent to one supplier": its status is that
-- part's quote status. A sub-RFQ groups the parts sent to a supplier together. There is no side-by-side comparison,
-- shortlist or competing quote for the same part in the flow, so those go.

CREATE TABLE sub_rfqs (
    sub_rfq_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_id              BIGINT      NOT NULL REFERENCES rfqs,
    sub_no              INT         NOT NULL CHECK (sub_no > 0),
    supplier_company_id BIGINT      NOT NULL REFERENCES companies,
    response_due_date   DATE        NOT NULL,
    sent_by_user_id     BIGINT      NOT NULL REFERENCES users,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (rfq_id, sub_no)
);

CREATE INDEX ix_sub_rfqs_supplier ON sub_rfqs (supplier_company_id);

ALTER TABLE rfq_supplier_assignments ADD COLUMN rfq_item_id BIGINT REFERENCES rfq_items;
ALTER TABLE rfq_supplier_assignments ADD COLUMN sub_rfq_id BIGINT REFERENCES sub_rfqs;

UPDATE rfq_supplier_assignments a
SET rfq_item_id = (SELECT i.rfq_item_id FROM rfq_items i WHERE i.rfq_id = a.rfq_id AND i.line_no = 1);

INSERT INTO sub_rfqs (rfq_id, sub_no, supplier_company_id, response_due_date, sent_by_user_id, created_at)
SELECT rfq_id, row_number() OVER (PARTITION BY rfq_id ORDER BY assignment_id), supplier_company_id, response_due_date,
       assigned_by_user_id, created_at
FROM rfq_supplier_assignments;

UPDATE rfq_supplier_assignments a
SET sub_rfq_id = s.sub_rfq_id
FROM sub_rfqs s
WHERE s.rfq_id = a.rfq_id AND s.supplier_company_id = a.supplier_company_id;

-- Quote status per part. Clarification, regret, shortlist and "selected for customer" are not in the flow.
ALTER TABLE rfq_supplier_assignments DROP CONSTRAINT rfq_supplier_assignments_status_check;

UPDATE rfq_supplier_assignments
SET status = CASE
                 WHEN status = 'CLARIFICATION_REQUESTED' THEN COALESCE(NULLIF(status_before_clarification, 'REOPENED'),
                                                                       'NOT_STARTED')
                 ELSE status
    END;

UPDATE rfq_supplier_assignments
SET status = CASE status
                 WHEN 'REOPENED' THEN 'REVISION_REQUIRED'
                 WHEN 'SHORTLISTED' THEN 'SUBMITTED'
                 WHEN 'NOT_SELECTED' THEN 'SUBMITTED'
                 WHEN 'SELECTED_FOR_CUSTOMER' THEN 'ACCEPTED'
                 WHEN 'REGRETTED' THEN 'RECALLED'
                 ELSE status
    END;

-- A part that already has a quotation with the customer carries the customer's answer.
UPDATE rfq_supplier_assignments a
SET status = CASE c.response_status
                 WHEN 'WAITING' THEN 'WITH_CUSTOMER'
                 WHEN 'APPROVED' THEN 'CUSTOMER_APPROVED'
                 WHEN 'REVISION_REQUESTED' THEN 'CUSTOMER_CORRECTION'
                 WHEN 'DECLINED' THEN 'CUSTOMER_REJECTED'
                 ELSE 'WITH_CUSTOMER'
    END
FROM (SELECT DISTINCT ON (assignment_id) assignment_id, response_status
      FROM customer_quote_submissions
      ORDER BY assignment_id, customer_quote_submission_id DESC) c
WHERE c.assignment_id = a.assignment_id AND a.status <> 'RECALLED';

-- A part goes to one supplier: of several competing old quotes, keep the one furthest along.
UPDATE rfq_supplier_assignments
SET status = 'RECALLED'
WHERE assignment_id IN (SELECT assignment_id
                        FROM (SELECT assignment_id,
                                     row_number() OVER (PARTITION BY rfq_item_id ORDER BY
                                         (status IN ('WITH_CUSTOMER', 'CUSTOMER_APPROVED', 'CUSTOMER_CORRECTION',
                                                     'CUSTOMER_REJECTED')) DESC,
                                         (status = 'ACCEPTED') DESC, assignment_id) AS rn
                              FROM rfq_supplier_assignments
                              WHERE status <> 'RECALLED') ranked
                        WHERE rn > 1);

ALTER TABLE rfq_supplier_assignments ADD CONSTRAINT rfq_supplier_assignments_status_check CHECK (status IN (
    'NOT_STARTED', 'DRAFT_SAVED', 'SUBMITTED', 'REVISION_REQUIRED', 'RESUBMITTED', 'ACCEPTED', 'WITH_CUSTOMER',
    'CUSTOMER_ON_HOLD', 'CUSTOMER_CORRECTION', 'CUSTOMER_APPROVED', 'CUSTOMER_REJECTED', 'RECALLED'));

ALTER TABLE rfq_supplier_assignments ALTER COLUMN rfq_item_id SET NOT NULL;
ALTER TABLE rfq_supplier_assignments ALTER COLUMN sub_rfq_id SET NOT NULL;
ALTER TABLE rfq_supplier_assignments DROP CONSTRAINT rfq_supplier_assignments_rfq_id_supplier_company_id_key;
ALTER TABLE rfq_supplier_assignments DROP COLUMN status_before_clarification;
ALTER TABLE rfq_supplier_assignments DROP COLUMN clarification_question;
ALTER TABLE rfq_supplier_assignments DROP COLUMN clarification_answer;
ALTER TABLE rfq_supplier_assignments RENAME COLUMN regret_reason TO recall_reason;

-- A part is with one supplier at a time (a recalled part can be sent again).
CREATE UNIQUE INDEX ux_assignments_item_active ON rfq_supplier_assignments (rfq_item_id) WHERE status <> 'RECALLED';
CREATE INDEX ix_assignments_sub_rfq ON rfq_supplier_assignments (sub_rfq_id);
CREATE INDEX ix_assignments_item ON rfq_supplier_assignments (rfq_item_id);

-- The part, the sub-RFQ and the supplier must all belong together.
CREATE FUNCTION rfq_supplier_assignments_part_check() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM rfq_items WHERE rfq_item_id = NEW.rfq_item_id AND rfq_id = NEW.rfq_id) THEN
        RAISE EXCEPTION 'Part % is not an item of RFQ %', NEW.rfq_item_id, NEW.rfq_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sub_rfqs WHERE sub_rfq_id = NEW.sub_rfq_id AND rfq_id = NEW.rfq_id
                                            AND supplier_company_id = NEW.supplier_company_id) THEN
        RAISE EXCEPTION 'Sub-RFQ % does not belong to RFQ % and supplier %', NEW.sub_rfq_id, NEW.rfq_id,
            NEW.supplier_company_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_rfq_supplier_assignments_part_check
    BEFORE INSERT OR UPDATE OF rfq_id, rfq_item_id, sub_rfq_id, supplier_company_id ON rfq_supplier_assignments
    FOR EACH ROW EXECUTE FUNCTION rfq_supplier_assignments_part_check();

-- No comparison in the flow.
DROP TABLE quote_comparison_lines;
DROP TABLE quote_comparisons;

-- ---------------------------------------------------------------- the quotation ERGO generates for the customer
-- One quotation per generation, with a line per part. The frozen customer_view is what the customer received; the
-- lines carry ERGO's hidden margin and the supplier quote version each price is based on (never shown to customers).

CREATE TABLE customer_quote_lines (
    customer_quote_line_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    submission_id            BIGINT         NOT NULL REFERENCES customer_quote_submissions,
    assignment_id            BIGINT         NOT NULL REFERENCES rfq_supplier_assignments,
    rfq_item_id              BIGINT         NOT NULL REFERENCES rfq_items,
    supplier_quote_version   INT            NOT NULL,
    currency_id              BIGINT         NOT NULL REFERENCES currencies,
    quoted_production_piece_price NUMERIC(18, 4) NOT NULL CHECK (quoted_production_piece_price >= 0),
    quoted_ppap_piece_price  NUMERIC(18, 4) CHECK (quoted_ppap_piece_price >= 0),
    quoted_tooling_cost      NUMERIC(18, 4) CHECK (quoted_tooling_cost >= 0),
    margin_amount_hidden     NUMERIC(18, 4),
    margin_percentage_hidden NUMERIC,
    UNIQUE (submission_id, assignment_id)
);

CREATE INDEX ix_customer_quote_lines_assignment ON customer_quote_lines (assignment_id);

INSERT INTO customer_quote_lines (submission_id, assignment_id, rfq_item_id, supplier_quote_version, currency_id,
                                  quoted_production_piece_price, quoted_ppap_piece_price, quoted_tooling_cost,
                                  margin_amount_hidden, margin_percentage_hidden)
SELECT s.customer_quote_submission_id, s.assignment_id, a.rfq_item_id, s.supplier_quote_version, s.currency_id,
       s.quoted_production_piece_price, s.quoted_ppap_piece_price, s.quoted_tooling_cost, s.margin_amount_hidden,
       s.margin_percentage_hidden
FROM customer_quote_submissions s
         JOIN rfq_supplier_assignments a ON a.assignment_id = s.assignment_id;

-- Quotations are numbered per RFQ (1, 2, ...): a correction leads to a new one.
ALTER TABLE customer_quote_submissions ADD COLUMN version_no INT;

ALTER TABLE customer_quote_submissions DISABLE TRIGGER trg_customer_quote_submissions_guard;

UPDATE customer_quote_submissions s
SET version_no = n.rn
FROM (SELECT customer_quote_submission_id, row_number() OVER (PARTITION BY rfq_id ORDER BY customer_quote_submission_id) AS rn
      FROM customer_quote_submissions) n
WHERE n.customer_quote_submission_id = s.customer_quote_submission_id;

-- The frozen customer view becomes {remarks, lines: [{part, ergoQuote}]}. Quotes that were forwarded as the
-- supplier's own lose the supplier's name and cost breakdown: only ERGO-style prices remain.
UPDATE customer_quote_submissions s
SET customer_view = jsonb_strip_nulls(jsonb_build_object(
        'remarks', s.customer_view -> 'remarks',
        'lines', jsonb_build_array(jsonb_build_object(
                'rfqItemId', i.rfq_item_id,
                'lineNo', i.line_no,
                'partNumber', i.part_number,
                'revision', i.revision,
                'description', i.description,
                'ergoQuote', COALESCE(s.customer_view -> 'ergoQuote', jsonb_build_object(
                        'currency', jsonb_build_object('id', c.currency_id, 'code', c.currency_code,
                                                       'name', c.currency_name),
                        'productionPiecePrice', s.quoted_production_piece_price,
                        'ppapPiecePrice', s.quoted_ppap_piece_price,
                        'toolingCost', s.quoted_tooling_cost))))))
FROM rfq_supplier_assignments a
         JOIN rfq_items i ON i.rfq_item_id = a.rfq_item_id,
     currencies c
WHERE a.assignment_id = s.assignment_id
  AND c.currency_id = s.currency_id;

ALTER TABLE customer_quote_submissions ALTER COLUMN version_no SET NOT NULL;
ALTER TABLE customer_quote_submissions ADD CONSTRAINT ux_customer_quote_submissions_version UNIQUE (rfq_id, version_no);

ALTER TABLE customer_quote_submissions
    DROP COLUMN quote_type,
    DROP COLUMN assignment_id,
    DROP COLUMN supplier_quote_version,
    DROP COLUMN currency_id,
    DROP COLUMN quoted_production_piece_price,
    DROP COLUMN quoted_ppap_piece_price,
    DROP COLUMN quoted_tooling_cost,
    DROP COLUMN margin_amount_hidden,
    DROP COLUMN margin_percentage_hidden;

CREATE OR REPLACE FUNCTION customer_quote_submissions_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'customer_quote_submissions rows cannot be deleted';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM rfqs WHERE rfq_id = NEW.rfq_id AND customer_company_id = NEW.customer_company_id) THEN
            RAISE EXCEPTION 'Company % is not the customer of RFQ %', NEW.customer_company_id, NEW.rfq_id;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.response_status NOT IN ('WAITING', 'ON_HOLD')
        OR (NEW.rfq_id, NEW.version_no, NEW.customer_company_id, NEW.customer_view, NEW.submitted_by_user_id,
            NEW.submitted_at)
           IS DISTINCT FROM
           (OLD.rfq_id, OLD.version_no, OLD.customer_company_id, OLD.customer_view, OLD.submitted_by_user_id,
            OLD.submitted_at) THEN
        RAISE EXCEPTION 'A quotation sent to the customer cannot be changed; only its response can be recorded, '
            'and a quotation on hold only its final response';
    END IF;
    RETURN NEW;
END;
$$;

ALTER TABLE customer_quote_submissions ENABLE TRIGGER trg_customer_quote_submissions_guard;

-- Lines are as frozen as the quotation: they carry ERGO's margin and what each price was based on.
CREATE FUNCTION customer_quote_lines_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM customer_quote_submissions s
                                JOIN rfq_supplier_assignments a ON a.rfq_id = s.rfq_id
                       WHERE s.customer_quote_submission_id = NEW.submission_id
                         AND a.assignment_id = NEW.assignment_id AND a.rfq_item_id = NEW.rfq_item_id) THEN
            RAISE EXCEPTION 'Quote % and part % do not belong to quotation %', NEW.assignment_id, NEW.rfq_item_id,
                NEW.submission_id;
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'customer_quote_lines is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_customer_quote_lines_guard
    BEFORE INSERT OR UPDATE OR DELETE ON customer_quote_lines
    FOR EACH ROW EXECUTE FUNCTION customer_quote_lines_guard();

CREATE TRIGGER trg_customer_quote_lines_no_truncate
    BEFORE TRUNCATE ON customer_quote_lines
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();
