--liquibase formatted sql

--changeset steven:v1.44.0-investment-profile
--comment 資產配置建議（Requirement 32 / Task 152）：使用者理財條件 profile（一使用者一列，記住免重填）。owner_user_id 唯一（多租戶，Requirement 28）。goals 為理財目標複選 code 逗號分隔字串（本頁表單詞彙、非跨域分類，比照市場分析 model/effort 之服務層白名單）。

CREATE TABLE investment_profile (
    id                        BIGSERIAL PRIMARY KEY,
    owner_user_id             BIGINT NOT NULL,
    age                       INTEGER,
    investment_horizon_years  INTEGER,
    monthly_investment        NUMERIC(20, 2),
    goals                     VARCHAR(300),
    risk_tolerance            VARCHAR(20),
    expected_annual_return    VARCHAR(20),
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_investment_profile_owner UNIQUE (owner_user_id)
);

--changeset steven:v1.44.0-portfolio-advice
--comment 資產配置建議（Requirement 32 / Task 152）：歷次建議。owner_user_id 隔離（Hibernate @Filter ownerFilter）。條件快照（age/horizon/monthly/goals/risk/expected_return）與 based_on_total_assets/based_on_snapshot_date 為刻意 denormalize 的歷史快照（比照 realized_gain 名稱字串、asset_snapshot 匯總欄），供回顧時呈現「當時的條件與依據」；based_on_snapshot_id 為正規化參照（不複製明細）。result_json 存解析後結構化建議（比照 daily_market_analysis 存 parsed JSON）。

CREATE TABLE portfolio_advice (
    id                        BIGSERIAL PRIMARY KEY,
    owner_user_id             BIGINT NOT NULL,
    status                    VARCHAR(20) NOT NULL,
    model                     VARCHAR(64),
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at              TIMESTAMP WITH TIME ZONE,
    error_message             VARCHAR(1000),
    age                       INTEGER,
    investment_horizon_years  INTEGER,
    monthly_investment        NUMERIC(20, 2),
    goals                     VARCHAR(300),
    risk_tolerance            VARCHAR(20),
    expected_annual_return    VARCHAR(20),
    based_on_snapshot_id      BIGINT,
    based_on_snapshot_date    DATE,
    based_on_total_assets     NUMERIC(20, 2),
    raw_response              TEXT,
    result_json               TEXT
);

CREATE INDEX idx_portfolio_advice_owner_created
    ON portfolio_advice (owner_user_id, created_at DESC);

--changeset steven:v1.44.0-portfolio-advice-setting
--comment 資產配置建議（Requirement 32 / Task 152）：單列（id=1）成本控管設定——分析模型、思考深度 effort、web 搜尋次數。全域（不分租戶），比照 market_analysis_setting。seed 一列預設 opus-4-8 / medium / 4。

CREATE TABLE portfolio_advice_setting (
    id                   INTEGER PRIMARY KEY,
    model                VARCHAR(64) NOT NULL,
    effort               VARCHAR(16) NOT NULL,
    web_search_max_uses  INTEGER NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO portfolio_advice_setting (id, model, effort, web_search_max_uses, updated_at)
VALUES (1, 'claude-opus-4-8', 'medium', 4, now());
