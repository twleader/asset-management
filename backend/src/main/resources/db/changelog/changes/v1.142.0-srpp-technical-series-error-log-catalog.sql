--liquibase formatted sql
--changeset steven:v1.142.0-srpp-technical-series-error-log-catalog
-- Task 473: immutable operation identity for the market-only completed technical series route.
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES
    ('OPEN_API', 'OPEN_SRPP_TECHNICAL_SERIES', 'SRPP 逐日技術序列', 190)
ON CONFLICT (source, operation_key) DO NOTHING;
