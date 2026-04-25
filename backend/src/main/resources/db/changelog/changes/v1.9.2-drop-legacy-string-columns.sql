-- v1.9.2: 移除 legacy 字串欄位，這些欄位自 v1.1.0 起已由 FK (bank_id / broker_id) 取代，
--          Java Entity 未對應，資料庫中為空值或廢棄資料。

ALTER TABLE bank_deposit  DROP COLUMN IF EXISTS bank_name;
ALTER TABLE fund_holding  DROP COLUMN IF EXISTS bank;
ALTER TABLE stock_holding DROP COLUMN IF EXISTS broker;
