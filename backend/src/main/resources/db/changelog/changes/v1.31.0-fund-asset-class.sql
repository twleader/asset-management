--liquibase formatted sql

--changeset steven:v1.31.0-fund-class-override
-- Requirement 25：基金逐檔資產類別 override。
-- 持有基金以「名稱」為穩定識別（fund_holding.fund_code 多為 NULL、且未必收錄於 fund_master），
-- 故 override 以 fund_name 為 key 存於本表：有列 = 人工指定、覆蓋 AssetClassifier.classifyFund 名稱規則；
-- 無列 = 依規則（含 債/bond/收益 → 債券）。供高收益債/收益型債券基金等規則漏網者修正。
CREATE TABLE IF NOT EXISTS fund_class_override (
    fund_name    VARCHAR(150) PRIMARY KEY,
    asset_class  VARCHAR(20)  NOT NULL
);
