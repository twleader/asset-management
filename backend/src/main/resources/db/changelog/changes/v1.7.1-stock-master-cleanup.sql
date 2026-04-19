--liquibase formatted sql

--changeset steven:v1.7.1-stock-master-cleanup
-- 清除 stock 主檔中名稱等於代號的無效資料（seed 時 stock_holding.stock_name 本身就是代號）
DELETE FROM stock WHERE LOWER(name) = LOWER(code);
