--liquibase formatted sql
--changeset steven:v1.25.0-deposit-annual-interest-rate
-- 存款新增「年利率」欄位（百分比；1.5 表示 1.5%）
-- 預估利息為衍生值 amount × rate / 100，不存 DB
ALTER TABLE bank_deposit ADD COLUMN annual_interest_rate NUMERIC(7,4);
