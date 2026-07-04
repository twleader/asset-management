--liquibase formatted sql

--changeset steven:v1.42.0-market-analysis-batch
--comment 今日股市分析（Requirement 31）改用 Anthropic Message Batches API（省 50% token 成本）：daily_market_analysis 新增 batch_id 欄，暫存在製中批次 id。分析改為非同步——送出批次後 status=PROCESSING（batch_id 記錄），背景 poller 於批次 ENDED 後取結果落庫（OK/FAILED）並清 batch_id。既有列 batch_id 為 NULL、不受影響。

ALTER TABLE daily_market_analysis
    ADD COLUMN batch_id VARCHAR(64);
