--liquibase formatted sql

--changeset steven:v1.38.0-market-analysis-setting
--comment 今日股市分析（Requirement 31）模型頁面可調：單列設定表，管理者可切換分析模型（Opus 4.8 / Sonnet 5 / Haiku 4.5），持久化、下次分析生效。比照 backup_setting 單列慣例。

CREATE TABLE market_analysis_setting (
    id          INTEGER     PRIMARY KEY DEFAULT 1,
    model       VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8',
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT market_analysis_setting_single_row CHECK (id = 1)
);
INSERT INTO market_analysis_setting (id) VALUES (1);
