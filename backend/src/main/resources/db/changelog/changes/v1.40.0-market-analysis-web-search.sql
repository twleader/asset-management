--liquibase formatted sql

--changeset steven:v1.40.0-market-analysis-web-search
--comment 今日股市分析（Requirement 31）成本控管：市場分析設定表新增 web_search_max_uses 欄（0=關閉／3／4／6），管理者可於頁面調整新聞搜尋次數（次數越少越省 context 重複處理；0 為純技術面）。預設 6（維持既有行為）。既有單列補值。

ALTER TABLE market_analysis_setting
    ADD COLUMN web_search_max_uses INTEGER NOT NULL DEFAULT 6;
