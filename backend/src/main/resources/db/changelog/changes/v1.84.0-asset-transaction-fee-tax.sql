--liquibase formatted sql

--changeset steven:v1.84.0-asset-transaction-fee-tax
--comment Requirement 49（Task 268）：交易紀錄新增「手續費」fee 與「證交稅」transaction_tax 兩欄，皆為 NUMERIC(15,2) nullable、無 DEFAULT、不回填既有列。NULL 語意＝「這筆沒記費用」，0 語意＝「確實免收」，兩者不同故不給 DEFAULT 0。兩欄為純記錄欄，不參與 amountTwd 與年度彙總計算。ADD COLUMN IF NOT EXISTS 使本 changeset 冪等，重跑無害。
ALTER TABLE asset_transaction ADD COLUMN IF NOT EXISTS fee NUMERIC(15,2);
ALTER TABLE asset_transaction ADD COLUMN IF NOT EXISTS transaction_tax NUMERIC(15,2);
