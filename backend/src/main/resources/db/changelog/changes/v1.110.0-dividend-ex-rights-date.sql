--liquibase formatted sql

--changeset steven:v1.110.0-dividend-ex-rights-date
--comment Task 357 / Requirement 94：新增除權日欄，修正「除息與除權被壓成同一欄」的既有缺陷——過去 CashExDividendTradingDate（除息日）與 StockExDividendTradingDate（除權日）互相 fallback 落地成同一個 ex_dividend_date，只配股事件的除權日因此被壓合遺失。既有 ex_dividend_date 在本任務回補完成前，對純配股事件可能存的其實是除權日，不是除息日。新增 ex_rights_date DATE（nullable，無預設值，不得 NOT NULL）：「這個事件沒有除權」與「還沒抓到／尚未回補」都須能表示成 null，用預設值會讓兩者無法區分。冪等（ADD COLUMN IF NOT EXISTS），可重複執行。
ALTER TABLE stock_dividend_snapshot_event ADD COLUMN IF NOT EXISTS ex_rights_date DATE;

ALTER TABLE stock_dividend_history ADD COLUMN IF NOT EXISTS ex_rights_date DATE;
