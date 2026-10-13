# [t496] 美股當日開機補救與備份長程序期限

**對應 Requirements:** Requirement 15（美股 daily 不可因當日服務晚啟動而漏執行；備份長程序保有 12 分鐘單次上限與既有總期限安全界線）
**前置任務:** t468、t486
**Liquibase changeset:** 無

## 背景

2026-10-13 Asia/Taipei 的 `business-services` 在 21:00 才啟動，已錯過 TUE–SAT 07:00 的美股 daily cron；DB 最後一筆 `asset_daily_us_*` 是 10/10。現有 Task 486 只會在同日 15:30 後補救台股 daily，無法補救同日 07:00 的美股 daily。

同日 15:30 台股 cron 記錄 `Backup start` 後，恰約 300 秒才安全失敗，期間沒有 `pg_dump done`，與 `ProcessBackupDatabaseProcess` 對 `pg_dump` 的 300 秒 hard cap 相符。先前的約 217 MB dump 曾在 rclone 300 秒上限後失敗。兩者都需要 720 秒單次 cap，但所有 create workflow 仍必須共用 900 秒總 deadline 和 30 秒清理保留額，不能把 timeout 放寬成無界執行。

跨日後的 dump 代表新狀態，絕不可宣稱是前一個執行日 07:00 的歷史快照；補救限當日，且不能手動觸發 backup、sync 或 restore 作為驗收手段。

## 要做什麼

- [ ] 496.1 在 `BackupService` 擴充既有 `ApplicationReadyEvent` 補救機制，新增美股 daily 的獨立一次性 admission。candidate 必須是 Asia/Taipei TUE–SAT 且嚴格晚於 07:00；只有 candidate 前一個曆日經既有 `MarketDataService.isUsTradingDay` 為 true，才可委派既有可替換背景啟動器。listener 不得等待 dump／rclone／DB commit；重複 ready event 對美股至多提交一次，啟動器或工作失敗只能記安全 warning。
- [ ] 496.2 將美股 cron 與開機補救收斂到同一個已持有既有 fair `operationLock` 的 daily US 共用入口。cron 必須以既有 `currentTaipei().toLocalDate()` 取得、補救以 candidate 的可測 Taipei 時鐘取得並傳入**台北執行日**；入口不得自行以 `LocalDate.now`／另一個時鐘重算日期，且以 exact `folder=daily`／`asset_daily_us_YYYYMMDD_` prefix durable row 去重；同日台股／manual／weekly row 不能抑制它。**只對開機補救**，取得鎖後與 `pg_dump` 命名前各自重新讀可測 Taipei 時鐘，兩次都必須仍是 candidate 日期且嚴格晚於 07:00；跨日、07:00（含）前、MON/SUN、停用、前日非美股交易日或已有 exact row 時，均零 dump、rclone 或 backup index mutation。既有精確 07:00:00 cron 不套用這個 recovery 時間守門，仍可在合法交易日進入相同共用入口並執行日別去重／交易日／停用檢查。不得新增 cron／輪詢、HTTP API、DTO、schema、Liquibase、前端或排程列表項目。
- [ ] 496.3 把 `ProcessBackupDatabaseProcess` 的 `pg_dump` cap 和 `ProcessBackupRemoteClient` 的一般 rclone command cap 都改為 720 秒；實際 `pg_restore` 還原的 900 秒 cap 不動（`pg_restore --list` 驗證維持既有 preflight 行為）。每個 cap 都要繼續透過 `BackupWorkflowDeadline.commandSeconds` 套用，故手動、台股、美股、weekly、monthly（如已啟用）與 restore 自救點 create 維持 900 秒 total workflow deadline，命令 timeout 是自身 cap 和扣除 30 秒清理保留額的剩餘預算較小者。不得重設 deadline、放鬆 process cleanup、把兩段 720 秒相加，或改變 timeout／不確定結果時保留來源、禁止盲目重試／覆寫／刪除的安全語意。
- [ ] 496.4 在既有 `BackupServiceTest` 加入美股 listener 的非阻塞、重複 ready 事件、safe warning、星期／07:00／開關／交易日／exact row 閘門、共用入口去重、非 US row 不誤判，以及取得鎖後與命名前跨日零 mutation 測試；必須另涵蓋合法交易日既有 cron 在精確 07:00:00 可進入共用入口並使用 `currentTaipei()` 控制時鐘日期與 exact US prefix 去重。讓 `ProcessBackupDatabaseProcess` 的實際 ProcessBuilder 路徑可注入假的 process runner，新增其單元覆蓋來驗證 wait timeout：`pg_dump=720`、`pg_restore=900`，以及以 `BackupWorkflowDeadline` 套用 30 秒保留額的 clamp；在既有 `ProcessBackupRemoteClientTest` 補實際 rclone command 的 720 秒 cap 與 workflow 剩餘預算 clamp。測試不使用真實 DB、rclone、Google Drive 或 pg_dump，且不以反射常數替代實際 command timeout 斷言。
- [ ] 496.5 只重建並 recreate `business-services`。從已合併且乾淨的 `/Users/steven/Project/asset-management-main` worktree 使用其 `.env` 執行部署，等待 Docker health 和 BFF 到 business 的既有安全 GET 恢復；檢查容器日誌與 DB `backup_record` 的唯讀結果。不得為驗收人工呼叫 backup、sync 或 restore、不得刪除 pending dump／Drive 物件，也不得接觸任何券商交易 API。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME="$(/usr/libexec/java_home -v 25)" /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='BackupServiceTest,*BackupDatabase*Test,ProcessBackupRemoteClientTest' test
cd /Users/steven/Project/asset-management-main && docker compose -p asset-management build business-services
cd /Users/steven/Project/asset-management-main && docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

部署後等待 `asset-business-services` healthy，確認既有 `http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m` 回合法 JSON，並從該容器唯讀讀取日誌及 `backup_record` 查詢確認結果。不得把健康檢查、測試或 scheduler admission 當成已成功上傳的宣稱；成功只能是 verified upload 加 durable `backup_record`。

## 完成報告

待實作、獨立架構審查、容器重建與唯讀 runtime 驗證後回填。
