--liquibase formatted sql

--changeset steven:v1.111.0-dividend-event-uniqueness
--comment 配息四個日期各自落地的收尾（Requirement 94 / Task 357.1）：補上 v1.110.0 只做了 ADD COLUMN、沒做的兩件事——(1) 兩個 ex_rights_date 欄位的 COMMENT；(2) 把除權日納入 uk_dividend_event 的事件身分。**必須把除權日納入唯一索引的理由**：回補之後純配股列的 ex_dividend_date 全部變成 null（那個日期被移到 ex_rights_date），舊索引的 COALESCE(ex_dividend_date, DATE '1970-01-01') 會讓同一檔、同一年的這些列全部塌成同一個 sentinel 值，於是「同年、同金額、只差除權日」的兩列會互撞唯一鍵——實例：2885 在 2020／2022／2023／2024／2025 每一年都有兩筆純配股事件。新索引比舊索引**多一個欄位、只增不減嚴格性**，因此不可能讓既有資料產生新的違規。**不重複 ADD COLUMN**：兩個欄位已由 v1.110.0 建立且該 changeset 已在運行中的 DB 執行完畢，此處不得重寫或修改它（會讓 checksum 失效並導致 business crash loop）。冪等：COMMENT 可重複執行，索引為 DROP IF EXISTS + CREATE UNIQUE INDEX IF NOT EXISTS。

COMMENT ON COLUMN stock_dividend_snapshot_event.ex_rights_date IS
    '除權日（FinMind StockExDividendTradingDate）。只配現金的事件為 null；不得以 ex_dividend_date 頂替。';

COMMENT ON COLUMN stock_dividend_history.ex_rights_date IS
    '除權日（FinMind StockExDividendTradingDate）。只配現金的事件為 null；不得以 ex_dividend_date 頂替。回補完成前，既有列的 ex_dividend_date 可能存的其實是除權日。';

DROP INDEX IF EXISTS uk_dividend_event;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dividend_event
    ON stock_dividend_history (
        stock_code,
        market,
        year,
        COALESCE(ex_dividend_date, DATE '1970-01-01'),
        COALESCE(ex_rights_date, DATE '1970-01-01'),
        COALESCE(cash_dividend, 0),
        COALESCE(stock_dividend, 0),
        COALESCE(cash_payment_date, DATE '1970-01-01'),
        COALESCE(stock_payment_date, DATE '1970-01-01'),
        COALESCE(event_key, '')
    );
