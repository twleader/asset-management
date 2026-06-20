--liquibase formatted sql

--changeset steven:v1.32.0-fund-override-style-term
-- Requirement 26/27：基金細分 override 擴充——fund_class_override 由「僅 asset_class」擴成三個
-- nullable override 欄（asset_class / stock_style / bond_term），與 stock 主檔三欄結構平行。
-- asset_class 改 nullable：一列可能只設 stock_style 或 bond_term（該基金 asset_class 仍依規則）。
-- 任一欄非空即建列、全清則刪列。
ALTER TABLE fund_class_override ALTER COLUMN asset_class DROP NOT NULL;
ALTER TABLE fund_class_override ADD COLUMN stock_style VARCHAR(20);
ALTER TABLE fund_class_override ADD COLUMN bond_term VARCHAR(20);
