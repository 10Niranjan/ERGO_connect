-- Module switches (docs/PERMISSIONS.md §9): a notification belongs to the workflow module its record belongs to, so a
-- module switched to NV (hidden) can hide its notifications - from the list, the unread count and mark-as-read - without
-- deleting them. They reappear unchanged when the module is switched back on. Notifications about documents ('document')
-- take the module of the document's own record; NULL = no switchable module (never hidden).
--
-- Existing rows are back-filled from the same mapping the application uses (ModuleSwitch.ownerOf).

ALTER TABLE notifications
    ADD COLUMN module_code VARCHAR(20)
        CHECK (module_code IN ('RFQ', 'QUOTATION', 'NPD', 'OFP', 'QUALITY'));

UPDATE notifications
SET module_code = CASE linked_module
                      WHEN 'rfq' THEN 'RFQ'
                      WHEN 'quotation' THEN 'QUOTATION'
                      WHEN 'npd' THEN 'NPD'
                      WHEN 'ofp' THEN 'OFP'
                      WHEN 'inspection' THEN 'QUALITY'
                      WHEN 'ncr' THEN 'QUALITY'
                      WHEN 'deviation' THEN 'QUALITY'
    END
WHERE linked_module <> 'document';

UPDATE notifications n
SET module_code = CASE d.linked_module
                      WHEN 'rfq' THEN 'RFQ'
                      WHEN 'quotation' THEN 'QUOTATION'
                      WHEN 'OFP_SHIPMENT' THEN 'OFP'
                      WHEN 'QUALITY_CAPA' THEN 'QUALITY'
                      WHEN 'comment' THEN (SELECT CASE c.linked_module
                                                      WHEN 'rfq' THEN 'RFQ'
                                                      WHEN 'quotation' THEN 'QUOTATION'
                                                      WHEN 'npd' THEN 'NPD'
                                                      WHEN 'ofp' THEN 'OFP'
                                                      ELSE 'QUALITY'
                                                      END
                                           FROM comments c
                                           WHERE c.comment_id = d.linked_record_id)
    END
FROM documents d
WHERE n.linked_module = 'document'
  AND d.document_id = n.linked_record_id;

CREATE INDEX ix_notifications_recipient_module ON notifications (recipient_user_id, module_code);
