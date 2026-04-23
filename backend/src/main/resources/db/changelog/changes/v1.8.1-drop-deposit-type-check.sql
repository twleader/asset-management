--liquibase formatted sql

--changeset steven:v1.8.1-drop-deposit-type-check
-- 移除 bank_deposit.deposit_type 的舊 CHECK 約束（早期 Enum 留下的列舉清單），
-- 改由 deposit_type / transit_fund_type 設定表動態管理，否則新增的「買股待付款」「賣股待收款」等類型會違反約束無法存檔。
ALTER TABLE bank_deposit DROP CONSTRAINT IF EXISTS bank_deposit_deposit_type_check;
