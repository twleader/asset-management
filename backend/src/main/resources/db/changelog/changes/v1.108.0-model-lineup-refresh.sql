--liquibase formatted sql

--changeset steven:v1.108.0-model-lineup-refresh
--comment 分析模型換代（Requirement 32／31 既有規格，模型汰換非新規則）：Opus 4.8 → Opus 5，Haiku 4.5 下架、新增 Fable 5，Sonnet 5 不變。舊 changeset（v1.38.0/v1.44.0）已套用不可回頭改，改用新 changeset 更新 DEFAULT 與既有資料列，避免既有環境仍殘留舊 model id。

ALTER TABLE market_analysis_setting ALTER COLUMN model SET DEFAULT 'claude-opus-5';
UPDATE market_analysis_setting SET model = 'claude-opus-5' WHERE model = 'claude-opus-4-8';
UPDATE market_analysis_setting SET model = 'claude-fable-5' WHERE model = 'claude-haiku-4-5';

UPDATE portfolio_advice_setting SET model = 'claude-opus-5' WHERE model = 'claude-opus-4-8';
UPDATE portfolio_advice_setting SET model = 'claude-fable-5' WHERE model = 'claude-haiku-4-5';
