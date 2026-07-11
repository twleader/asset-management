--liquibase formatted sql

--changeset steven:v1.50.0-retirement-two-stage-annual-expense
--comment 資產配置建議（Requirement 32 / Task 167）：退休後拆兩階段（長照前／長照後），生活費由月改年。移除 retirement_monthly_expense（v1.48.0 新增、改用年）；新增 retirement_annual_expense（長照前年生活費，今日幣值）、long_term_care_annual_expense（長照後年生活費，通常較高）、long_term_care_start_age（長照起始年齡，null 且有填長照後年費時 service 以預設 80 計）。皆為試算輸入、非衍生值。
ALTER TABLE investment_profile DROP COLUMN retirement_monthly_expense;
ALTER TABLE investment_profile ADD COLUMN retirement_annual_expense       NUMERIC(20, 2);
ALTER TABLE investment_profile ADD COLUMN long_term_care_annual_expense   NUMERIC(20, 2);
ALTER TABLE investment_profile ADD COLUMN long_term_care_start_age        INTEGER;
