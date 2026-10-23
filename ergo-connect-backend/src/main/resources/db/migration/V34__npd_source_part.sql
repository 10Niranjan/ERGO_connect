-- Flow doc §1/§5: an RFQ has several parts, and each part the customer approves proceeds on its own. An NPD project
-- is therefore started from one RFQ part, not from the whole RFQ.

ALTER TABLE npd_projects ADD COLUMN source_rfq_item_id BIGINT REFERENCES rfq_items;

UPDATE npd_projects p
SET source_rfq_item_id = (SELECT i.rfq_item_id FROM rfq_items i WHERE i.rfq_id = p.source_rfq_id AND i.line_no = 1);

ALTER TABLE npd_projects ALTER COLUMN source_rfq_item_id SET NOT NULL;
ALTER TABLE npd_projects ADD CONSTRAINT npd_projects_source_rfq_item_id_key UNIQUE (source_rfq_item_id);
ALTER TABLE npd_projects DROP CONSTRAINT npd_projects_source_rfq_id_key;
