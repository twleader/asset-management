--liquibase formatted sql

--changeset steven:v1.107.0-deposit-type-withdrawal-order
--comment 資產配置建議的標的層級再平衡（Requirement 84 / Task 344）：deposit_type 新增 withdrawal_order（提領優先序，越小越優先被提領），供存款減碼的 waterfall 使用——優先動活存以避開定期存款中途解約的利息損失。禁止 Enum 寫死，故存入業務分類表。冪等（ADD COLUMN IF NOT EXISTS ＋ 條件式 UPDATE），可重複執行。

ALTER TABLE deposit_type ADD COLUMN IF NOT EXISTS withdrawal_order INTEGER NOT NULL DEFAULT 50;

-- 以下五筆為【既有資料回填】，不是產品 seed（Task 344.3）。
-- 產品 seed（供全新 DB 建立時用）在 DataInitializer.seedDepositTypes()，那支迴圈是
-- `if (findByCode(...).isEmpty()) { save(...) }`──只 INSERT、從不 UPDATE，故已部署的 DB
-- 光靠 seed 常數一列都回填不到，必須在此以一次性 UPDATE 處理。
-- 「優利活存 1.5%」是使用者自建的私有分類（不在 DataInitializer 的 seed 名單內），
-- 只能出現在這裡；寫進產品 seed 等於在任何乾淨 DB 上憑空建出別人的私有分類。
-- `AND withdrawal_order = 50` 的作用是讓本 changeset 可重複執行（正常單次執行時所有列
-- 皆為 DEFAULT 50），**不是**「保護使用者已調整的值」──使用者若剛好把某類型調成 50，
-- 重跑仍會被覆蓋；真正的保護是「這支 changeset 只跑一次」。
UPDATE deposit_type SET withdrawal_order = 10 WHERE code = '活存'          AND withdrawal_order = 50;
UPDATE deposit_type SET withdrawal_order = 20 WHERE code = '美元活存'      AND withdrawal_order = 50;
UPDATE deposit_type SET withdrawal_order = 30 WHERE code = '優利活存 1.5%' AND withdrawal_order = 50;
UPDATE deposit_type SET withdrawal_order = 90 WHERE code = '定存'          AND withdrawal_order = 50;
UPDATE deposit_type SET withdrawal_order = 91 WHERE code = '美元定存'      AND withdrawal_order = 50;
