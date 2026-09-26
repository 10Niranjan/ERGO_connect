-- Phase 3b: a place for a customer/supplier's own free-text note when updating their own activity's progress
-- (SRS's "issue responses" otherwise has nowhere to go on the activity itself). ERGO's add/edit activity form
-- never sets this column; only NpdService.updateOwnActivity does (SRS §17.4).
ALTER TABLE npd_activities ADD COLUMN remarks TEXT;
