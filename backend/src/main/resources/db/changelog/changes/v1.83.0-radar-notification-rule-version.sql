--liquibase formatted sql

--changeset steven:v1.83.0-radar-notification-rule-version
--comment Requirement 43 修訂（Task 264，TW_RULES_V9）：交易雷達通知設定新增 rule_version，記錄「該筆 last_action 是哪個規則版本算出來的」。V9 改變了動作映射結構（新增 TimingState 的雙向覆寫），既有列的 last_action 是 V8 動作，直接與 V9 動作比對必然大量不相等而觸發假通知。Requirement 44 明訂「規則版本變更後通知基準須全部重建、升級後首輪評估一律只建基準不寄信」。採加欄而非「一次性把 initialized 重設為 false」的理由：後者只解決這一次升版，日後每次升版都要再寫一支 migration；有了本欄，TradingRadarNotificationService 比對 RULE_VERSION 不符即視同未初始化，重建自動發生。既有列一律填 NULL（代表「版本未知」），與任何 RULE_VERSION 皆不相等，故首輪自動重建基準——這正是所需行為，不需另外 UPDATE。冪等寫法（ADD COLUMN IF NOT EXISTS），避免版號避讓改名時 Liquibase 視為新 changeset 重跑而失敗。
ALTER TABLE trading_radar_notification_setting
    ADD COLUMN IF NOT EXISTS rule_version VARCHAR(30);
