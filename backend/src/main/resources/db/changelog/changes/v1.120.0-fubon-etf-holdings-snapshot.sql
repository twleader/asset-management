--liquibase formatted sql
--changeset steven:v1.120.0-fubon-etf-holdings-snapshot
--comment: Requirement 123／Task 389：富邦 ETF 成分股持股明細抓取結果，同 key 覆寫、只留最新一筆，不解析原始回應
CREATE TABLE IF NOT EXISTS fubon_etf_holdings_snapshot (
    etf_stock_code    VARCHAR(20) NOT NULL,
    market            VARCHAR(10) NOT NULL DEFAULT '台股',
    fetched_at        TIMESTAMP NOT NULL,
    success           BOOLEAN NOT NULL,
    reason            VARCHAR(50),
    raw_response_json JSONB,
    updated_at        TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT pk_fubon_etf_holdings_snapshot PRIMARY KEY (etf_stock_code),
    CONSTRAINT ck_fubon_etf_holdings_snapshot_market CHECK (market = '台股')
);
