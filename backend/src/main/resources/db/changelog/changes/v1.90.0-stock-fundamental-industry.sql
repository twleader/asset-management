--liquibase formatted sql

--changeset steven:v1.90.0-stock-fundamental-industry
--comment Requirement 43/46（Task 292）：台股個股基本面與全市場產業營收的 observation history。四表刻意 append-on-change，不以期間唯一鍵覆寫，避免日後修正版倒灌歷史回測。source_urls 保留同 provider observation 實際使用的全部端點；source_available_at 與 observed_at 都是 as-of 守門。revenue_yoy_pct 與產業彙總是為來源快照/as-of 回放刻意保留的 denormalization。pe_loss_flag=true 僅代表來源明確以空 PE 表示虧損；null 是無法判定，不得互換。

CREATE TABLE IF NOT EXISTS stock_valuation_daily (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    market VARCHAR(20) NOT NULL,
    trading_date DATE NOT NULL,
    pe_ratio NUMERIC(12,4),
    pb_ratio NUMERIC(12,4),
    dividend_yield_pct NUMERIC(12,4),
    pe_loss_flag BOOLEAN,
    provider VARCHAR(20) NOT NULL,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_available_at TIMESTAMPTZ NOT NULL,
    availability_basis VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_stock_valuation_asof
    ON stock_valuation_daily (stock_code, market, trading_date DESC, observed_at DESC);

CREATE TABLE IF NOT EXISTS stock_financial_quarter (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    market VARCHAR(20) NOT NULL,
    fiscal_year INTEGER NOT NULL,
    fiscal_quarter INTEGER NOT NULL CHECK (fiscal_quarter BETWEEN 1 AND 4),
    eps NUMERIC(12,4),
    net_income_parent BIGINT,
    equity_parent BIGINT,
    provider VARCHAR(20) NOT NULL,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_available_at TIMESTAMPTZ NOT NULL,
    availability_basis VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_stock_financial_asof
    ON stock_financial_quarter (stock_code, market, fiscal_year DESC, fiscal_quarter DESC, observed_at DESC);

CREATE TABLE IF NOT EXISTS stock_monthly_revenue (
    id BIGSERIAL PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    market VARCHAR(20) NOT NULL,
    revenue_year INTEGER NOT NULL,
    revenue_month INTEGER NOT NULL CHECK (revenue_month BETWEEN 1 AND 12),
    industry_name VARCHAR(100),
    revenue BIGINT,
    prior_year_revenue BIGINT,
    revenue_yoy_pct NUMERIC(12,4),
    provider VARCHAR(20) NOT NULL,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_available_at TIMESTAMPTZ NOT NULL,
    availability_basis VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_stock_revenue_asof
    ON stock_monthly_revenue (stock_code, market, revenue_year DESC, revenue_month DESC, observed_at DESC);

CREATE TABLE IF NOT EXISTS industry_monthly_revenue (
    id BIGSERIAL PRIMARY KEY,
    industry_name VARCHAR(100) NOT NULL,
    revenue_year INTEGER NOT NULL,
    revenue_month INTEGER NOT NULL CHECK (revenue_month BETWEEN 1 AND 12),
    revenue NUMERIC(24,0),
    prior_year_revenue NUMERIC(24,0),
    revenue_yoy_pct NUMERIC(12,4),
    company_count INTEGER NOT NULL,
    provider VARCHAR(20) NOT NULL,
    source_urls JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_available_at TIMESTAMPTZ NOT NULL,
    availability_basis VARCHAR(20) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_industry_revenue_asof
    ON industry_monthly_revenue (industry_name, revenue_year DESC, revenue_month DESC, observed_at DESC);

-- 每分鐘節拍讀 DB；預設 15:30。ON CONFLICT 僅保護同一時間點，使用者仍可另增時間。
INSERT INTO crawler_schedule (crawler_key, run_hour, run_minute, enabled)
SELECT 'fundamental', 15, 30, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM crawler_schedule
    WHERE crawler_key = 'fundamental' AND run_hour = 15 AND run_minute = 30
);
