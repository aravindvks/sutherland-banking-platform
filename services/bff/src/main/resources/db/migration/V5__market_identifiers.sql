-- Market packs, Stage 1 step 4 (docs/design/market-packs.md): saved payees carry the market's account identifier
-- and a generic name-check column beside the UK ones. Additive only; the code writes both and reads the new one,
-- falling back to the old. The old columns and the sort-code unique key are replaced in step 8.

ALTER TABLE payee ADD COLUMN name_check_result  TEXT;   -- MATCH, CLOSE_MATCH, NO_MATCH, UNAVAILABLE
ALTER TABLE payee ADD COLUMN account_identifier JSONB;  -- {"scheme": ..., "fields": {...}}

UPDATE payee SET name_check_result = cop_result WHERE name_check_result IS NULL AND cop_result IS NOT NULL;
UPDATE payee
   SET account_identifier = jsonb_build_object('scheme', 'UK_SORT_CODE', 'fields',
           jsonb_build_object('sortCode', sort_code, 'accountNumber', account_number))
 WHERE account_identifier IS NULL;
