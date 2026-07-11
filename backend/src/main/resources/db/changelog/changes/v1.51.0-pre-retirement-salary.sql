--liquibase formatted sql

--changeset steven:v1.51.0-pre-retirement-salary-expense
--comment 資產配置建議（Requirement 32 / Task 168）：退休前收入以年薪計算。移除 investment_profile.monthly_investment（每月可投入），改為 pre_retirement_annual_salary（退休前年薪，今日幣值）＋pre_retirement_annual_expense（退休前年生活費，今日幣值）；退休前每年淨投入＝年薪 − 年生活費（兩者依通膨逐年膨脹），與退休後（收入勞保勞退／支出生活費）對稱。portfolio_advice.monthly_investment（歷史條件快照）保留、新紀錄不再寫入。
ALTER TABLE investment_profile DROP COLUMN monthly_investment;
ALTER TABLE investment_profile ADD COLUMN pre_retirement_annual_salary  NUMERIC(20, 2);
ALTER TABLE investment_profile ADD COLUMN pre_retirement_annual_expense NUMERIC(20, 2);
