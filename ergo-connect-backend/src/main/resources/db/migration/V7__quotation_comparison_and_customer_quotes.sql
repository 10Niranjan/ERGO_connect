-- Phase 2c: ERGO's comparison of supplier quotes and the quotes ERGO sends the customer (SRS §13.3.7-13.3.10, §14.2,
-- §14.4, §17.3). Everything here is ERGO-internal except customer_view, which is exactly what the customer received.

-- ERGO's written assessment of one RFQ's quotes.
CREATE TABLE quote_comparisons (
    quote_comparison_id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_id                 BIGINT      NOT NULL UNIQUE REFERENCES rfqs,
    internal_summary       TEXT,
    recommendation_summary TEXT,
    prepared_by_user_id    BIGINT      NOT NULL REFERENCES users, -- last saved by
    prepared_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ERGO's scores per supplier quote, 0-10 where higher is better (risk 10 = lowest risk). Shortlisting and selection
-- are the quote's own status (SRS §14.3), not flags here, so there is one source of truth.
CREATE TABLE quote_comparison_lines (
    quote_comparison_line_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    assignment_id            BIGINT      NOT NULL UNIQUE REFERENCES rfq_supplier_assignments,
    commercial_score         INT         CHECK (commercial_score BETWEEN 0 AND 10),
    technical_score          INT         CHECK (technical_score BETWEEN 0 AND 10),
    quality_score            INT         CHECK (quality_score BETWEEN 0 AND 10),
    risk_score               INT         CHECK (risk_score BETWEEN 0 AND 10),
    internal_remarks         TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A quote ERGO sent the customer: one supplier's quote as-is, or ERGO's own quote based on one (client decision).
-- customer_view is what the customer sees, frozen when sent. The *_hidden columns never leave ERGO.
CREATE TABLE customer_quote_submissions (
    customer_quote_submission_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    rfq_id                        BIGINT         NOT NULL REFERENCES rfqs,
    customer_company_id           BIGINT         NOT NULL REFERENCES companies,
    quote_type                    VARCHAR(20)    NOT NULL CHECK (quote_type IN ('SUPPLIER_QUOTE', 'ERGO_QUOTE')),
    assignment_id                 BIGINT         NOT NULL REFERENCES rfq_supplier_assignments, -- the quote it is based on
    supplier_quote_version        INT            NOT NULL,
    currency_id                   BIGINT         NOT NULL REFERENCES currencies,
    quoted_production_piece_price NUMERIC(18, 4) NOT NULL CHECK (quoted_production_piece_price >= 0),
    quoted_ppap_piece_price       NUMERIC(18, 4) CHECK (quoted_ppap_piece_price >= 0),
    quoted_tooling_cost           NUMERIC(18, 4) CHECK (quoted_tooling_cost >= 0),
    margin_amount_hidden          NUMERIC(18, 4), -- per piece, in currency_id: quoted minus the supplier's price
    margin_percentage_hidden      NUMERIC,        -- of the quoted price (unbounded: a mistyped price must not fail)
    customer_view                 JSONB          NOT NULL,
    submitted_by_user_id          BIGINT         NOT NULL REFERENCES users,
    submitted_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    response_status               VARCHAR(30)    NOT NULL DEFAULT 'WAITING'
        CHECK (response_status IN ('WAITING', 'APPROVED', 'REVISION_REQUESTED', 'DECLINED')),
    response_by_user_id           BIGINT         REFERENCES users,
    response_at                   TIMESTAMPTZ,
    response_comment              TEXT,
    CHECK ((response_status = 'WAITING') = (response_at IS NULL))
);

CREATE INDEX ix_customer_quote_submissions_rfq ON customer_quote_submissions (rfq_id);
CREATE INDEX ix_customer_quote_submissions_customer ON customer_quote_submissions (customer_company_id);

-- One quote at a time waits for the customer's answer.
CREATE UNIQUE INDEX ux_customer_quote_submissions_waiting ON customer_quote_submissions (rfq_id)
    WHERE response_status = 'WAITING';

-- Sent quotes go only to the RFQ's own customer and are based on that RFQ's quotes. Afterwards only the customer's
-- single response may be recorded; nothing is ever changed or deleted.
CREATE FUNCTION customer_quote_submissions_guard() RETURNS trigger
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
    IF OLD.response_status <> 'WAITING'
        OR (NEW.rfq_id, NEW.customer_company_id, NEW.quote_type, NEW.assignment_id, NEW.supplier_quote_version,
            NEW.currency_id, NEW.quoted_production_piece_price, NEW.quoted_ppap_piece_price, NEW.quoted_tooling_cost,
            NEW.margin_amount_hidden, NEW.margin_percentage_hidden, NEW.customer_view, NEW.submitted_by_user_id,
            NEW.submitted_at)
           IS DISTINCT FROM
           (OLD.rfq_id, OLD.customer_company_id, OLD.quote_type, OLD.assignment_id, OLD.supplier_quote_version,
            OLD.currency_id, OLD.quoted_production_piece_price, OLD.quoted_ppap_piece_price, OLD.quoted_tooling_cost,
            OLD.margin_amount_hidden, OLD.margin_percentage_hidden, OLD.customer_view, OLD.submitted_by_user_id,
            OLD.submitted_at) THEN
        RAISE EXCEPTION 'A quote sent to the customer cannot be changed; only its response can be recorded, once';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customer_quote_submissions_guard
    BEFORE INSERT OR UPDATE OR DELETE ON customer_quote_submissions
    FOR EACH ROW EXECUTE FUNCTION customer_quote_submissions_guard();

CREATE TRIGGER trg_customer_quote_submissions_no_truncate
    BEFORE TRUNCATE ON customer_quote_submissions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_update_delete();
