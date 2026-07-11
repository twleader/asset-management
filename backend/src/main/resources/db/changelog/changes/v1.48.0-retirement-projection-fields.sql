--liquibase formatted sql

--changeset steven:v1.48.0-investment-profile-retirement-projection
--comment 資產配置建議（Requirement 32 / Task 165）：退休現金流試算所需的三個試算假設欄。retirement_monthly_expense＝退休後每月生活費（今日幣值，逐年名目提領由 service 依通膨現算、不入庫）；accumulation_annual_return_rate／retirement_annual_return_rate＝累積期／退休後試算用年報酬率%（使用者可自訂的試算假設，null 時 service 依「獲利預期」區間帶入預設，退休後預設較保守）。三者皆為試算輸入、非衍生值，故入庫。
ALTER TABLE investment_profile ADD COLUMN retirement_monthly_expense      NUMERIC(20, 2);
ALTER TABLE investment_profile ADD COLUMN accumulation_annual_return_rate NUMERIC(5, 2);
ALTER TABLE investment_profile ADD COLUMN retirement_annual_return_rate   NUMERIC(5, 2);
