-- Phase 6e: in-app notifications (SRS §13.7.4, §12.12.2, §14.9). One row per recipient per event, written by the
-- communication module after a business change commits. Derived, system-generated data: not audited row by row (the
-- audited business event that caused it is), never edited except for read_flag / read_at, and visible only to its
-- recipient. SRS 13.7.4 lists notification_type; event_kind is added so the exact trigger is known (filtering,
-- reminder de-duplication). Text is written for the recipient's audience at send time and frozen.

CREATE TABLE notifications (
    notification_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    notification_type    VARCHAR(30)  NOT NULL
        CHECK (notification_type IN ('ACTION_REQUIRED', 'OVERDUE_REMINDER', 'APPROVAL_PENDING', 'RESPONSE_RECEIVED',
                                     'DOCUMENT_UPLOADED', 'RECORD_UPDATED')),
    event_kind           VARCHAR(50)  NOT NULL,
    linked_module        VARCHAR(30)  NOT NULL
        CHECK (linked_module IN ('rfq', 'quotation', 'npd', 'ofp', 'inspection', 'ncr', 'deviation', 'document')),
    linked_record_id     BIGINT       NOT NULL,
    recipient_user_id    BIGINT       NOT NULL REFERENCES users,
    title                VARCHAR(200) NOT NULL,
    message              VARCHAR(500) NOT NULL,
    action_url           VARCHAR(200) NOT NULL,
    read_flag            BOOLEAN      NOT NULL DEFAULT false,
    sent_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at              TIMESTAMPTZ,
    -- Scheduled reminders only: a recipient gets at most one notification per key (e.g. one per item per day).
    dedupe_key           VARCHAR(120),
    -- Who caused it (never exposed): kept so support can trace a surprising notification.
    triggered_by_user_id BIGINT       REFERENCES users,
    CHECK (read_flag = (read_at IS NOT NULL))
);

CREATE INDEX ix_notifications_recipient ON notifications (recipient_user_id, read_flag, sent_at DESC);
CREATE UNIQUE INDEX ux_notifications_dedupe ON notifications (recipient_user_id, dedupe_key) WHERE dedupe_key IS NOT NULL;
