--liquibase formatted sql

--changeset steven:v1.85.0-drop-nonpositive-close splitStatements:false
--comment Requirement 62（Task 279）：刪除 stock_price_history 台股非正收盤髒列並加上 CHECK (close_price > 0)。成因：交易所對「當日無整股成交」不發布 OHLC（TWSE 回 '--'），FinMind 序列化為 0.0，而 PriceFetchClient.fetchTwHistoricalRange 舊版只擋 null，於是 0 被當成合法收盤寫入，實測累積 175 列（006208 87、7556 67，其餘 9 檔共 21）。單一列即讓當日乖離率變成 -100%，並污染 MA60 與波動度。判準只能是 close_price <= 0：實測 175 列中有 48 列 volume 不為 0（當日只有零股／盤後成交），另有 9 列是 close_price>0 AND volume=0 的合法資料，故不得用 volume 當判準。真值不存在（交易所沒有該日收盤價），因此一律刪除，不回補、不 carry-forward、不以成交金額除以成交股數反推。順序必須先刪後加，否則約束會因既有列建立失敗。冪等性：DELETE 天然冪等；ADD CONSTRAINT 以 pg_constraint 存在檢查包在 DO 區塊內，重跑無害。splitStatements:false 為 DO 區塊必要（預設以分號斷句會把區塊切碎），比照 v1.34.1 / v1.71.0 / v1.78.0 / v1.79.0 的既有慣例。
DELETE FROM stock_price_history WHERE market = '台股' AND close_price <= 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_sph_close_price_positive'
    ) THEN
        ALTER TABLE stock_price_history
            ADD CONSTRAINT ck_sph_close_price_positive CHECK (close_price > 0);
    END IF;
END $$;
