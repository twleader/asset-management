--liquibase formatted sql

--changeset steven:v1.93.0-radar-evidence-integrity splitStatements:false
--comment Requirement 65 / Task 309：已知台股外幣債券 ETF 的底層幣別補齊；只填補 null，保留人工 override，並對衝突值發出可稽核提示。
DO $$
DECLARE
    conflict_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO conflict_count
      FROM stock
     WHERE market = '台股'
       AND code IN ('00695B', '00751B', '00865B')
       AND underlying_currency IS NOT NULL
       AND UPPER(underlying_currency) <> 'USD';

    IF conflict_count > 0 THEN
        RAISE NOTICE 'Task 309 currency mapping preserved % non-USD manual override(s)', conflict_count;
    END IF;

    UPDATE stock
       SET underlying_currency = 'USD'
     WHERE market = '台股'
       AND code IN ('00695B', '00751B', '00865B')
       AND underlying_currency IS NULL;
END $$;
