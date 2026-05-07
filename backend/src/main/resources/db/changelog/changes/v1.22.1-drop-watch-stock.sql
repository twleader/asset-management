--liquibase formatted sql

--changeset steven:v1.22.1-drop-watch-stock
--comment 廢止 watch_stock 表；觀察清單改由 stock_alert group by (stockCode, market) 衍生
-- 既有 watch_stock 中若有「純觀察、無 alert」的股票（例如 0000 大盤），
-- 在 drop 後不再出現於觀察清單；使用者需於警示條件頁重新建立至少一筆條件以重新觀察。

DROP TABLE IF EXISTS watch_stock;
