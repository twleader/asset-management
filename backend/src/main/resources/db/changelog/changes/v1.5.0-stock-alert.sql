--liquibase formatted sql

--changeset steven:v1.5.0-stock-alert
CREATE TABLE stock_alert (
    id          BIGSERIAL PRIMARY KEY,
    stock_code  VARCHAR(20)  NOT NULL,
    stock_name  VARCHAR(100),
    market      VARCHAR(20)  NOT NULL,
    alert_type  VARCHAR(50)  NOT NULL,
    threshold   DECIMAL(10,4) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    last_triggered_at TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_stock_alert_code_market ON stock_alert (stock_code, market);
CREATE INDEX idx_stock_alert_active ON stock_alert (active);
