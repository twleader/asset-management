--liquibase formatted sql

--changeset steven:1.12.0-drop-stock-price-table
--comment 抓股價拆出獨立 price-service 微服務後，盤中即時行情改存 Redis（key price:{market}:{code}），盤後寫入 stock_price_history。stock_price 表不再使用，DROP。

DROP TABLE IF EXISTS stock_price;
