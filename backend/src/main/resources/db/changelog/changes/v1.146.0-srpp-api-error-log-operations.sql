--liquibase formatted sql
--changeset steven:v1.146.0-srpp-api-error-log-operations
-- Task 484: immutable operation identity for the four SRPP POST/mail routes already listed by the BFF route catalog.
INSERT INTO api_error_log_operation (source, operation_key, operation_label, display_order) VALUES
    ('OPEN_API', 'OPEN_SRPP_DAILY_REPORT_MAIL', 'SRPP 日報寄送', 200),
    ('OPEN_API', 'OPEN_SRPP_DAILY_REPORT_MAIL_STATUS', 'SRPP 日報寄送狀態', 210),
    ('OPEN_API', 'OPEN_SRPP_EVENT_EVIDENCE', 'SRPP 事件證據收據', 220),
    ('OPEN_API', 'OPEN_SRPP_DAILY_DECISION', 'SRPP 日報決策收據', 230)
ON CONFLICT (source, operation_key) DO NOTHING;
