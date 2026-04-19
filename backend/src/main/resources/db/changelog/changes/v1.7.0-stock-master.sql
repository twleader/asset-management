--liquibase formatted sql

--changeset steven:v1.7.0-stock-master
CREATE TABLE stock (
    code    VARCHAR(20)  NOT NULL,
    market  VARCHAR(20)  NOT NULL,
    name    VARCHAR(100) NOT NULL,
    PRIMARY KEY (code, market)
);

-- 從現有持倉資料 seed（每個 code+market 取一筆最新名稱）
INSERT INTO stock (code, market, name)
SELECT DISTINCT ON (stock_code, market) stock_code, market, stock_name
FROM stock_holding
WHERE stock_name IS NOT NULL AND stock_name <> ''
ORDER BY stock_code, market, stock_name
ON CONFLICT (code, market) DO UPDATE SET name = EXCLUDED.name;
