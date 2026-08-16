--liquibase formatted sql

--changeset steven:v1.105.0-market-analysis-engine
--comment 今日股市分析（Requirement 78 / Task 337）成本歸零：market_analysis_setting 新增 engine 欄（local＝本機規則引擎、llm＝Claude 批次）。既有單列一律回填 local——沿用 llm 則部署後成本毫無變化。冪等（IF NOT EXISTS ＋ 條件式 UPDATE），可重複執行。

ALTER TABLE market_analysis_setting ADD COLUMN IF NOT EXISTS engine VARCHAR(16) NOT NULL DEFAULT 'local';
UPDATE market_analysis_setting SET engine = 'local' WHERE engine IS NULL OR engine = '';
