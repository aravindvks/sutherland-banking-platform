-- Market packs, Stage 1 step 4 (docs/design/market-packs.md): columns that every market can use, beside the UK ones.
-- Additive only. The code writes both the old and the new column and reads the new one, falling back to the old
-- (COALESCE) while pods of the previous release may still write only the old one. The old columns are dropped in a
-- later release (step 8), once a release that writes both has run cleanly.

-- The payee name check (Confirmation of Payee in the UK): MATCH, CLOSE_MATCH, NO_MATCH, UNAVAILABLE.
ALTER TABLE payment          ADD COLUMN name_check_result TEXT;
ALTER TABLE payment_schedule ADD COLUMN name_check_result TEXT;

-- The creditor's account as the market's identifier: {"scheme": "UK_SORT_CODE", "fields": {"sortCode": ..., "accountNumber": ...}}.
ALTER TABLE payment          ADD COLUMN creditor_account JSONB;
ALTER TABLE payment_schedule ADD COLUMN creditor_account JSONB;

-- The Direct Debit originator's identifier (the service user number, SUN, in the UK's Bacs).
ALTER TABLE dd_mandate ADD COLUMN originator_id TEXT;

-- Fill the new columns for rows written before this release.
UPDATE payment SET name_check_result = cop_result
 WHERE name_check_result IS NULL AND cop_result IS NOT NULL;
UPDATE payment_schedule SET name_check_result = cop_result
 WHERE name_check_result IS NULL AND cop_result IS NOT NULL;

UPDATE payment
   SET creditor_account = jsonb_build_object('scheme', 'UK_SORT_CODE', 'fields',
           jsonb_build_object('sortCode', creditor->>'sortCode', 'accountNumber', creditor->>'accountNumber'))
 WHERE creditor_account IS NULL AND creditor ? 'sortCode' AND creditor ? 'accountNumber';
UPDATE payment_schedule
   SET creditor_account = jsonb_build_object('scheme', 'UK_SORT_CODE', 'fields',
           jsonb_build_object('sortCode', creditor->>'sortCode', 'accountNumber', creditor->>'accountNumber'))
 WHERE creditor_account IS NULL AND creditor ? 'sortCode' AND creditor ? 'accountNumber';

UPDATE dd_mandate SET originator_id = service_user_number WHERE originator_id IS NULL;
