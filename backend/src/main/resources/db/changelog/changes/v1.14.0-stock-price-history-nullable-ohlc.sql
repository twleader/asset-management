--liquibase formatted sql

--changeset steven:1.14.0-stock-price-history-nullable-ohlc
--comment 外部抓價來源（如 NASDAQ ETF）對部分欄位不一定回傳。OHLC 改為一律可 null，避免上游用 0 偽裝「無資料」造成顯示為 $0.00 的 bug。close_price 仍 NOT NULL（一筆歷史紀錄沒有收盤價就沒寫入意義）。

ALTER TABLE stock_price_history ALTER COLUMN open_price DROP NOT NULL;
ALTER TABLE stock_price_history ALTER COLUMN high_price DROP NOT NULL;
