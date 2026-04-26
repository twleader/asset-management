--liquibase formatted sql

--changeset steven:v1.11.0-stock-alert-trigger
CREATE TABLE stock_alert_trigger (
    id            BIGSERIAL PRIMARY KEY,
    alert_id      BIGINT       NOT NULL REFERENCES stock_alert(id) ON DELETE CASCADE,
    stock_code    VARCHAR(20)  NOT NULL,
    market        VARCHAR(20)  NOT NULL,
    triggered_at  TIMESTAMP    NOT NULL,
    price         NUMERIC(10, 4),
    monthly_ma    NUMERIC(10, 4),
    quarterly_ma  NUMERIC(10, 4),
    annual_ma     NUMERIC(10, 4),
    k_value       NUMERIC(10, 4),
    d_value       NUMERIC(10, 4),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_alert_trigger_alert_time
    ON stock_alert_trigger (alert_id, triggered_at DESC);

CREATE INDEX idx_stock_alert_trigger_created_at
    ON stock_alert_trigger (created_at);
