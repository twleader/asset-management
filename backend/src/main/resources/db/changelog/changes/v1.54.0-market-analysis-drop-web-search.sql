--liquibase formatted sql

--changeset steven:v1.54.0-market-analysis-drop-web-search
--comment 今日股市分析（Requirement 31 / Task 179）：新聞來源改為固定讀本地爬蟲 news_headline、移除 web_search，故「新聞搜尋次數」設定失去意義——移除 market_analysis_setting.web_search_max_uses 欄（頁面下拉、DTO、entity 欄、resolveWebSearchMaxUses 一併移除）。

ALTER TABLE market_analysis_setting
    DROP COLUMN web_search_max_uses;
