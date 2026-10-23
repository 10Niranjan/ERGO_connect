-- Flow doc §5: the customer may put a received quote ON HOLD (decision about moving to NPD pending). On hold is not
-- final: the customer answers once more (approve / correction required / reject). Every decision carries a comment.

-- The RFQ has a matching status while the quote is on hold.
ALTER TABLE rfqs DROP CONSTRAINT rfqs_status_check;
ALTER TABLE rfqs ADD CONSTRAINT rfqs_status_check CHECK (status IN (
    'DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'CLARIFICATION_REQUIRED', 'VALIDATED', 'SENT_TO_SUPPLIERS',
    'QUOTES_PARTIAL', 'QUOTES_COMPLETE', 'UNDER_COMPARISON', 'SENT_TO_CUSTOMER', 'CUSTOMER_ON_HOLD',
    'CUSTOMER_REVISION_REQUESTED', 'CUSTOMER_APPROVED', 'CUSTOMER_DECLINED', 'CLOSED', 'CANCELLED'));

ALTER TABLE customer_quote_submissions DROP CONSTRAINT customer_quote_submissions_response_status_check;
ALTER TABLE customer_quote_submissions ADD CONSTRAINT customer_quote_submissions_response_status_check
    CHECK (response_status IN ('WAITING', 'ON_HOLD', 'APPROVED', 'REVISION_REQUESTED', 'DECLINED'));

-- A quote on hold still waits for the customer's final answer: one such quote per RFQ at a time.
DROP INDEX ux_customer_quote_submissions_waiting;
CREATE UNIQUE INDEX ux_customer_quote_submissions_waiting ON customer_quote_submissions (rfq_id)
    WHERE response_status IN ('WAITING', 'ON_HOLD');

-- Same guard as before, except that a quote on hold can still receive its one final answer.
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
        IF NOT EXISTS (SELECT 1 FROM rfq_supplier_assignments WHERE assignment_id = NEW.assignment_id
                                                                AND rfq_id = NEW.rfq_id) THEN
            RAISE EXCEPTION 'Quote % does not belong to RFQ %', NEW.assignment_id, NEW.rfq_id;
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.response_status NOT IN ('WAITING', 'ON_HOLD')
        OR (NEW.rfq_id, NEW.customer_company_id, NEW.quote_type, NEW.assignment_id, NEW.supplier_quote_version,
            NEW.currency_id, NEW.quoted_production_piece_price, NEW.quoted_ppap_piece_price, NEW.quoted_tooling_cost,
            NEW.margin_amount_hidden, NEW.margin_percentage_hidden, NEW.customer_view, NEW.submitted_by_user_id,
            NEW.submitted_at)
           IS DISTINCT FROM
           (OLD.rfq_id, OLD.customer_company_id, OLD.quote_type, OLD.assignment_id, OLD.supplier_quote_version,
            OLD.currency_id, OLD.quoted_production_piece_price, OLD.quoted_ppap_piece_price, OLD.quoted_tooling_cost,
            OLD.margin_amount_hidden, OLD.margin_percentage_hidden, OLD.customer_view, OLD.submitted_by_user_id,
            OLD.submitted_at) THEN
        RAISE EXCEPTION 'A quote sent to the customer cannot be changed; only its response can be recorded, '
            'and a quote on hold only its final response';
    END IF;
    RETURN NEW;
END;
$$;
