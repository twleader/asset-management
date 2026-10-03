--liquibase formatted sql
--changeset steven:v1.141.0-srpp-completed-technicals-error-log-catalog
-- Task 472: immutable operation identity for the market-only completed daily technical route.
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES
    ('OPEN_API', 'OPEN_SRPP_COMPLETED_TECHNICALS', 'SRPP 完成日技術事實', 180)
ON CONFLICT (source, operation_key) DO NOTHING;
