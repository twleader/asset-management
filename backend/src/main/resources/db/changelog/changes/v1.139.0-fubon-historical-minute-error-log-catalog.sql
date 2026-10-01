--liquibase formatted sql
--changeset steven:v1.139.0-fubon-historical-minute-error-log-catalog
-- Task466's new historical minute route writes diagnostics through the existing catalog FK.
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES
    ('FUBON_API', 'FUBON_HISTORICAL_INTRADAY_CANDLES_READ', '個股歷史分鐘K線查詢', 180)
ON CONFLICT (source, operation_key) DO NOTHING;
