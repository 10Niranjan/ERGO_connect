-- Two users racing the same supplier quote (save / submit) or the same customer quotation (answer) now lose with 409
-- through an optimistic lock instead of a database error (the guard trigger / the unique version number).
ALTER TABLE supplier_quotes ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE customer_quote_submissions ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
