--liquibase formatted sql

--changeset steven:v1.39.0-market-analysis-effort
--comment 今日股市分析（Requirement 31）成本控管：市場分析設定表新增 effort 欄（low/medium/high），管理者可於頁面調整思考深度（省 thinking 輸出 token）。預設 medium（平衡；較原隱含 high 省）。既有單列補值。

ALTER TABLE market_analysis_setting
    ADD COLUMN effort VARCHAR(16) NOT NULL DEFAULT 'medium';
