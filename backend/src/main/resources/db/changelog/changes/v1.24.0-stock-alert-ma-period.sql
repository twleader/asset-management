--liquibase formatted sql

--changeset steven:v1.24.0-stock-alert-ma-period
-- 均線警示通用化：把均線天數從 alertType 字串拆出來變獨立欄位 ma_period
-- 舊資料 QUARTERLY_MA_* → MA_*_PCT + ma_period=60
-- 舊資料 ANNUAL_MA_*    → MA_*_PCT + ma_period=240
ALTER TABLE stock_alert ADD COLUMN ma_period INTEGER;

UPDATE stock_alert
   SET ma_period = 60,
       alert_type = CASE alert_type
                      WHEN 'QUARTERLY_MA_ABOVE_PCT' THEN 'MA_ABOVE_PCT'
                      WHEN 'QUARTERLY_MA_BELOW_PCT' THEN 'MA_BELOW_PCT'
                    END
 WHERE alert_type IN ('QUARTERLY_MA_ABOVE_PCT', 'QUARTERLY_MA_BELOW_PCT');

UPDATE stock_alert
   SET ma_period = 240,
       alert_type = CASE alert_type
                      WHEN 'ANNUAL_MA_ABOVE_PCT' THEN 'MA_ABOVE_PCT'
                      WHEN 'ANNUAL_MA_BELOW_PCT' THEN 'MA_BELOW_PCT'
                    END
 WHERE alert_type IN ('ANNUAL_MA_ABOVE_PCT', 'ANNUAL_MA_BELOW_PCT');
