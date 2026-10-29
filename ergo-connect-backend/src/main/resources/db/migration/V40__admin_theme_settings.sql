-- Per-side (ERGO / CUSTOMER / SUPPLIER) UI theme, set by the Super Admin. NULL published_config = built-in default look.
-- A draft is private to the admin until published. version guards concurrent edits.
CREATE TABLE theme_settings (
    side             VARCHAR(20) PRIMARY KEY CHECK (side IN ('ERGO', 'CUSTOMER', 'SUPPLIER')),
    draft_config     TEXT,
    published_config TEXT,
    version          BIGINT      NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       BIGINT      REFERENCES users (user_id)
);

INSERT INTO theme_settings (side) VALUES ('ERGO'), ('CUSTOMER'), ('SUPPLIER');
