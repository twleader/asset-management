--liquibase formatted sql
--changeset steven:v1.171.0-fubon-schedule-switch splitStatements:false
-- 富邦唯讀排程執行開關（Requirement 218／Task 612）。全域設定，無 owner。
-- seed 只在列不存在時寫入，重跑不覆蓋使用者已改的 enabled。
CREATE TABLE IF NOT EXISTS fubon_schedule_switch (
 schedule_key VARCHAR(64) PRIMARY KEY,
 enabled BOOLEAN NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO fubon_schedule_switch(schedule_key,enabled) VALUES
 ('inventory',TRUE),('bank-balance',TRUE),('settlement',TRUE),
 ('realized-gain',TRUE),('trade',TRUE),('etf-holdings',TRUE),
 ('srpp-order-source',FALSE)
ON CONFLICT (schedule_key) DO NOTHING;
