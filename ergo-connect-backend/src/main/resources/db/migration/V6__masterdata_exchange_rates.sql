-- Phase 2c: exchange rates (SRS §13.2.7) for ERGO's internal currency conversion when comparing quotes (SRS §6).
-- On a given day the newest rate dated on or before it applies. A pair entered only the other way round is used
-- inverted, so INR/USD does not need entering twice.
CREATE TABLE exchange_rates (
    exchange_rate_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    from_currency_id   BIGINT         NOT NULL REFERENCES currencies,
    to_currency_id     BIGINT         NOT NULL REFERENCES currencies,
    rate_date          DATE           NOT NULL,
    exchange_rate      NUMERIC(18, 8) NOT NULL CHECK (exchange_rate > 0), -- 1 from = rate × to
    source             VARCHAR(200),
    created_by_user_id BIGINT         NOT NULL REFERENCES users,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CHECK (from_currency_id <> to_currency_id),
    UNIQUE (from_currency_id, to_currency_id, rate_date)
);
