--liquibase formatted sql

--changeset steven:v1.106.0-portfolio-advice-engine
--comment 資產配置建議（Requirement 80 / Task 339）三態引擎：portfolio_advice_setting 新增 engine 欄（local＝完全本機、hybrid＝本機計算＋AI 撰寫敘述、llm＝現行完整 AI 分析）。既有單列一律回填 local——沿用 llm 則部署後成本毫無變化。冪等（IF NOT EXISTS ＋ 條件式 UPDATE），可重複執行。

ALTER TABLE portfolio_advice_setting ADD COLUMN IF NOT EXISTS engine VARCHAR(16) NOT NULL DEFAULT 'local';
UPDATE portfolio_advice_setting SET engine = 'local' WHERE engine IS NULL OR engine = '';
