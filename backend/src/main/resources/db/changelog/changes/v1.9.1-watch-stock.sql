--liquibase formatted sql

--changeset steven:v1.9.1-watch-stock
CREATE TABLE watch_stock (
    id            BIGSERIAL PRIMARY KEY,
    stock_code    VARCHAR(20)  NOT NULL,
    stock_name    VARCHAR(100),
    market        VARCHAR(20)  NOT NULL,
    display_order INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_watch_stock_code_market UNIQUE (stock_code, market)
);

CREATE INDEX idx_watch_stock_market ON watch_stock (market);
