--liquibase formatted sql

--changeset steven:v1.108.0-market-analysis-factor-groups
--comment Requirement 94／Task 357——今日股市分析分類分點呈現：新增 factor_groups 欄位存放本機規則引擎分類 fragment（JSON）；LLM 路徑與既有列一律維持 NULL
ALTER TABLE daily_market_analysis ADD COLUMN IF NOT EXISTS factor_groups TEXT;
