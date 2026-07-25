--liquibase formatted sql

--changeset steven:v1.74.0-asset-transaction-price-scale
--comment Requirement 49（Task 239）：交易紀錄「單價」精度由小數 4 位提升至 6 位。asset_transaction 由 v1.72.0 建立時 price 為 NUMERIC(15,4)，此處純加寬為 NUMERIC(17,6)（11 整數位不變、小數位 4→6），對既有列無截斷。加寬為冪等：重跑同型別 ALTER 無害。
ALTER TABLE asset_transaction ALTER COLUMN price TYPE NUMERIC(17,6);
