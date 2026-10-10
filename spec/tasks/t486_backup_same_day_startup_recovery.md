# [t486] 台股每日備份限當日開機自癒

**對應 Requirements:** Requirement 15（台股交易日 15:30 資料庫備份必須在服務當日恢復後補救，但不得偽造跨日歷史備份）
**前置任務:** t468
**Liquibase changeset:** 無

## 背景

2026-10-08 是台股交易日，卻沒有 `asset_daily_tw_20261008_*`。唯讀核對顯示 `backup_record` 與 active Drive 的 `2026-10-08/` 都只有 07:00 的 `asset_daily_us_20261008_*`；不存在台股檔或未列冊 orphan。現有 `business-services` 容器在 2026-10-09 12:49 Asia/Taipei 才啟動，晚於 10/8 15:30 的台股 cron。10/9 為休市日，服務日誌證實既有排程正確略過。

目前台股排程只有精確的 `@Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Taipei")`；服務在當日排程點離線、當天稍後才恢復時，沒有同日補救。不可在隔日補造 `20261008`：dump 是當下資料庫快照，檔名日期與明文日期目錄必須代表實際台北執行日，不能捏造已失去的歷史時點。

## 要做什麼

- [ ] 486.1 在 `BackupService` 加入 `ApplicationReadyEvent` 的背景、一次性台股 daily 自癒入口。listener 捕捉 Asia/Taipei candidate instant，僅 candidate **嚴格晚於** 15:30 時才以 `AtomicBoolean` admission 委派可注入背景啟動器；listener 不得等待 dump／rclone／DB commit。重複 event 至多提交一次；背景啟動器或工作失敗只記安全 warning，不得拋回 ready 流程。不得新增輪詢、第四條 `@Scheduled`、HTTP API、DTO、資料表、Liquibase 或前端變更。
- [ ] 486.2 將決策本體做成可由單元測試控制 candidate 與鎖後 Asia/Taipei 時刻的 package-private 方法。台股 cron 與開機補救都必須進同一個已取得既有 `operationLock` 的 daily TW 共用入口：`backup_setting.backup_enabled=true`、`MarketDataService.isTwTradingDay(today)=true` 且查不到 exact `folder=daily` 加 `asset_daily_tw_yyyyMMdd_` 當日 filename 前綴 durable row 時，才可呼叫既有台股 daily workflow。任何一方成功後，另一方取得鎖時必須看到 row 並跳過，不能只讓自癒端去重。
- [ ] 486.3 背景工作取得鎖後，必須重驗目前日期仍等於 candidate 日期；實際命名／`pg_dump` 前再以同一可測 Taipei 時鐘驗證一次。若任一處跨日，15:30（含）前、休市、停用或已有 exact 台股 daily row，都必須零 dump／rclone／index mutation。美股、手動、weekly 與其他日期的 row 不得視為台股 daily 完成。跨日只記可讀日誌說明無法重建歷史時點；不得建立前日或新日檔、不得 sync／restore、不得觸發任何 Drive 寫入。
- [ ] 486.4 實際補救必須重用台股 cron 相同的 900 秒 workflow deadline、remote ID/config gate、`pg_dump → verified upload → durable backup_record` 邊界、rotation 與安全錯誤處理。自癒不繞過任何 gate、不改遠端資料、不盲目重試不確定結果；共用鎖內去重是避免自癒與 cron 產生雙寫的必要條件。
- [ ] 486.5 補充 `BackupServiceTest`：listener 在背景 dump 被阻塞時立即返回、重複 ready event 只提交一次、背景啟動失敗不拋出；15:30（含）前、停用、非交易日、已有 exact 台股 daily row、持鎖跨日與命名前跨日均不呼叫 dump；同日 15:30 後且無 exact 台股 row 時只跑一輪並寫入 daily TW backup；自癒先完成時 cron 必須跳過；另驗美股／手動／weekly row 不會誤判為完成。維持既有三條 cron、時區、DB schema、API path／DTO、SchedulePublicBffController.JOBS 與遠端路徑契約。

- [ ] 486.6 已提交背景 task 的內部失敗必須由 recovery wrapper 安全隔離：測試執行已提交 task 時不外拋，並驗 warning 不含植入敏感字串；此情境不得啟動重試、dump、rclone 或索引 mutation。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=BackupServiceTest test
```

驗收通過且合併至 main 後，從 main worktree 重建並 recreate `business-services`，確認容器 healthy，並以既有唯讀公開 endpoint 驗證 BFF 到 business-services 恢復。不得為驗收呼叫 backup、sync、restore，亦不得寫入 Drive。

## 完成報告

待實作與 runtime 驗收後填寫。
