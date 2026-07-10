--liquibase formatted sql

--changeset steven:v1.47.0-investment-profile-birth-date
--comment 資產配置建議（Requirement 32 / Task 164）：新增生日 birth_date，取代 age。年齡由生日與今天衍生、不冗存（正規化）。既有 age 值不遷移（使用者重填生日即可還原年齡）；產生建議時把當下衍生年齡寫入 portfolio_advice.age 歷史快照，歷次回顧不受影響。
ALTER TABLE investment_profile ADD COLUMN birth_date DATE;

--changeset steven:v1.47.0-investment-profile-drop-age
--comment 資產配置建議（Requirement 32 / Task 164）：移除 investment_profile.age——年齡改由 birth_date 衍生（禁止冗存可計算得出的衍生值）。portfolio_advice.age（歷史條件快照）不受影響、保留。
ALTER TABLE investment_profile DROP COLUMN age;

--changeset steven:v1.47.0-investment-profile-labor-and-inflation
--comment 資產配置建議（Requirement 32 / Task 164）：退休後現金流與通膨——勞保年金（月領＋起領年月）、勞退（一次領＋領取年月）金額為使用者填入的未來實際給付（不做通膨換算）；assumed_annual_inflation_rate 為假設年通膨率%（預設 2，供未來大筆花費今日幣值換算未來名目值，換算為衍生值不入庫）。
ALTER TABLE investment_profile ADD COLUMN labor_insurance_monthly       NUMERIC(20, 2);
ALTER TABLE investment_profile ADD COLUMN labor_insurance_start_date    DATE;
ALTER TABLE investment_profile ADD COLUMN labor_pension_lump_sum        NUMERIC(20, 2);
ALTER TABLE investment_profile ADD COLUMN labor_pension_claim_date      DATE;
ALTER TABLE investment_profile ADD COLUMN assumed_annual_inflation_rate NUMERIC(5, 2);

--changeset steven:v1.47.0-investment-planned-expense
--comment 資產配置建議（Requirement 32 / Task 164）：特定日期大筆花費（一使用者多筆，owner_user_id 隔離、Hibernate @Filter ownerFilter）。amount 為「今日幣值」原始值；未來名目金額＝amount × (1+r)^距花費日年數，由 service 依 investment_profile.assumed_annual_inflation_rate 現算、不入庫（正規化）。saveProfile 以「先刪 owner 全部再插入提交清單」replace。
CREATE TABLE investment_planned_expense (
    id             BIGSERIAL PRIMARY KEY,
    owner_user_id  BIGINT NOT NULL,
    expense_date   DATE NOT NULL,
    name           VARCHAR(100),
    amount         NUMERIC(20, 2) NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_planned_expense_owner ON investment_planned_expense (owner_user_id, expense_date);
