--liquibase formatted sql

--changeset steven:v1.49.0-drop-investment-profile-horizon
--comment 資產配置建議（Requirement 32 / Task 166）：移除 investment_profile.investment_horizon_years——有生日（→年齡）與退休日期（→退休時點）＋退休現金流試算固定推到 100 歲後，「投資年限」已多餘（累積期＝今天到退休日、退休後守成期＝退休到 100 歲，皆由生日與退休日衍生）。portfolio_advice.investment_horizon_years（歷史條件快照）保留、不動，供舊紀錄回顧；新紀錄不再寫入（null）。
ALTER TABLE investment_profile DROP COLUMN investment_horizon_years;
