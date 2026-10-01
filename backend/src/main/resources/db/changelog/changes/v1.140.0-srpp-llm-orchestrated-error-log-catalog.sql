--liquibase formatted sql
--changeset steven:v1.140.0-srpp-llm-orchestrated-error-log-catalog
-- Task467: immutable OPEN_API operation identities used by the three SRPP gateway routes.
-- Keep these rows in a new insert-only changeset; api_error_log_operation rejects UPDATE/DELETE.
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES
    ('OPEN_API', 'OPEN_SRPP_CALCULATION_CONTEXT', 'SRPP 計算脈絡', 150),
    ('OPEN_API', 'OPEN_SRPP_CALCULATIONS', 'SRPP 按需計算', 160),
    ('OPEN_API', 'OPEN_SRPP_MARKET_FACTS', 'SRPP 批次市場事實', 170)
ON CONFLICT (source, operation_key) DO NOTHING;
