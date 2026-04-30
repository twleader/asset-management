--liquibase formatted sql

--changeset steven:1.13.0-stock-dividend-history
--comment 股利歷史快取表：每日由 cron 從 FinMind 抓寫入；前端顯示直接讀 DB（避免每次開分析對話框都打 FinMind）。

CREATE TABLE IF NOT EXISTS stock_dividend_history (
    id                   BIGSERIAL PRIMARY KEY,
    stock_code           VARCHAR(20)  NOT NULL,
    market               VARCHAR(20)  NOT NULL,
    year                 INTEGER      NOT NULL,
    cash_dividend        NUMERIC(15,6),
    stock_dividend       NUMERIC(15,6),
    ex_dividend_date     DATE,
    yield_pct            NUMERIC(10,4),
    cash_payment_date    DATE,
    stock_payment_date   DATE,
    fill_days            INTEGER,
    previous_close       NUMERIC(15,4),
    source               VARCHAR(50),
    updated_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- 同一檔某年某除息日只保留一筆；ex_dividend_date 為 null 時當年彙總
CREATE UNIQUE INDEX uk_dividend_event ON stock_dividend_history
    (stock_code, market, year, COALESCE(ex_dividend_date, DATE '1970-01-01'));

CREATE INDEX idx_dividend_lookup ON stock_dividend_history
    (stock_code, market, year DESC);
