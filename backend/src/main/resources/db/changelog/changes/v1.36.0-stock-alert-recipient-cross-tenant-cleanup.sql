--liquibase formatted sql

--changeset steven:v1.36.0-delete-cross-tenant-alert-recipient
-- Requirement 30 defense-in-depth（Task 145）：清除 stock_alert_recipient 中「收件人 owner ≠ 警示 owner」的跨租戶殘列。
-- 背景：Task 144 修補寫入端（StockAlertService.replaceRecipients 只寫入當前租戶的收件人）前，攻擊者可能透過
-- IDOR 把他人 notification_recipient id 綁進自己的警示；此類殘列若存在，背景寄信 cron（AlertNotificationDispatcher，
-- 無 request context → ownerFilter 不啟用）仍會把觸發通知寄到他人租戶 email。
-- 查詢端亦已加 owner 條件（findActiveEmailsByAlertId join StockAlert 比對 owner_user_id）雙重防護；本 changeset
-- 一次性清潔既存殘列。冪等安全：無殘列時刪 0 列，Liquibase 以 changeset id 記錄不重跑。
DELETE FROM stock_alert_recipient sar
USING notification_recipient nr, stock_alert sa
WHERE sar.recipient_id = nr.id
  AND sar.alert_id = sa.id
  AND nr.owner_user_id <> sa.owner_user_id;
