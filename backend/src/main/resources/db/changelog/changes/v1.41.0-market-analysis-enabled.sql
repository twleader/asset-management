--liquibase formatted sql

--changeset steven:v1.41.0-market-analysis-enabled
--comment 今日股市分析（Requirement 31）成本控管：市場分析設定表新增 enabled 開關。停用＝每日 07:30 cron／self-heal 跳過自動分析（零花費，不呼叫 LLM）；管理者仍可手動「重新分析」跑單次。預設 true（維持既有行為）。既有單列補值。

ALTER TABLE market_analysis_setting
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true;
