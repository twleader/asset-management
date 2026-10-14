# [t498] Drive 備份排除 SRPP 盤中報告計算 base／package 資料並補齊子行程失敗原因日誌

**對應 Requirements:** Requirement 192（Drive 資料庫備份在 SRPP 報告計算包持續累積時仍須於時限內完成並驗證上傳）
**前置任務:** t489（報告計算包 producer）、t492（ai_flow 表的 pg_dump 排除）、t496（720 秒 cap）
**Liquibase changeset:** 無

## 背景

2026-10-12 起沒有任何一份備份成功（最後成功：2026-10-11 weekly 約 86 MB；每日 07:00 美股／15:30 台股 cron 全數失敗）。過去幾次修補只調 timeout（300→720 秒），但真正成因是資料量：

- Task 489 的 producer 每個開市 60 秒 tick，只要來源（即時報價、assets 估值等）有實質變動就 append 一筆 `srpp_report_calculation_base`（約 6 MB）與兩筆 `srpp_report_calculation_package`（Codex／Claude 各約 7.5 MB 原始 JSON）。兩表 DB trigger 拒絕 UPDATE 與 DELETE，只增不減。
- 2026-10-12～14 三日共 base 475 筆、package 950 筆：實際占用約 2.5 GB（package TOAST 1.66 GB＋base 0.8 GB），原始 JSON 約 10 GB（約 3 GB／日）。
- 在正式 DB 以完整正式參數（`pg_dump --format=custom --compress=9 --no-owner --no-acl` 加兩個 ai_flow 排除）實測：跑超過 14 分鐘、輸出已逾 1.2 GB 仍未結束，必然超過 720 秒單命令上限與 900 秒 workflow deadline；縱使放寬 cap，>1.2 GB 的加密上傳也不可能在時限內完成。
- 同一條指令追加兩個 SRPP 表的 `--exclude-table-data` 後：約 20 秒、約 109 MB，`pg_restore --list` 827 項正常。
- 失敗日誌只剩「備份子行程或本地索引目前不可用」（`BackupService.runScheduledBackup` 吞掉例外），所以每次都被誤判成 timeout 太短。2026-10-14 21:39 台股補救在 `Backup start` 後恰 720 秒失敗；接著 21:51 美股補救因 workflow deadline 在取鎖前就開始計時（等鎖 12 分鐘），只剩約 150 秒而再次失敗——那是連帶效果，不是第二個獨立成因，不需另改 deadline 模型。

**取捨（使用者已於 2026-10-15 明確選擇接受）：** 兩表不是可無損重建的資料。package 的 `content_jcs` 內含 `report-reservations` 來源，是 `origin=REPORT`「已建議、尚無精確成交連結而保留的建議腿預留」的唯一歷史來源（`SrppReportRepository.priorReportReservations`／`unlinkedReportReservations`、`SrppReportReservationCapture`）；`origin=DAILY_DECISION` 預留來自 `srpp_decision_run`，不在排除範圍、備份仍保留。排除後：由 Drive 備份還原，兩表 schema 完整但 rows 為 0，歷史預留歸零，producer 下一個開市 tick 以 `VERIFIED／COMPLETE／rows=[]` 重新起算，不會被標示不可驗證；app 內還原使用 `pg_restore --clean --if-exists`，會把 live 兩表整張清空重建，且還原前自救點同樣不含兩表資料，不可逆。取捨時點 950 個 package 中沒有任何 `ACTIONABLE` 建議腿，無實際預留遺失。預留帳本獨立保存與兩表無界成長政策是另案，不在本任務。

## 要做什麼

- [ ] 498.1 `backend/src/main/java/com/steven/assets/service/ProcessBackupDatabaseProcess.java` 的 `dump()` 在既有 `--exclude-table-data=public.ai_flow_run`、`--exclude-table-data=public.ai_flow_event` 之後，精確追加 `--exclude-table-data=public.srpp_report_calculation_package` 與 `--exclude-table-data=public.srpp_report_calculation_base`（共四個，表名完整限定 `public.`）。四個表名抽成 package-private 常數（供隔離 PG 測試取用同一份；單元測試仍以字面字串斷言，避免同義反覆）。不得改 `--format=custom`、`--compress=9`、`--no-owner`、`--no-acl`、連線參數、`pg_restore`／validate 參數與任何 timeout 常數。**不得修改** `scripts/db-export.sh`（它維持只排除兩張 ai_flow 表，`scripts/tests/test_collect_srpp_ai_flow.py::test_db_export_excludes_only_ai_table_data` 必須仍通過）；不得修改 schema、migration、trigger、`SrppReport*` producer 或任何 SRPP 程式，不得刪除或改寫任何既有 row、備份 archive 或 pending dump。
- [ ] 498.2 新增 package-private `BackupSafeFailureException extends IllegalStateException`（`com.steven.assets.service`，只有 `String message` 建構式，類別註解說明「訊息必為固定文字、不含 stderr／路徑／DB 內容，可安全寫入日誌」）。`ProcessBackupDatabaseProcess` 內目前所有 `throw new IllegalStateException(<固定訊息>)`（`run()` 的執行逾時／執行失敗／無法執行／被中斷／狀態未定已停止後續操作，以及 `terminateAndWait` 的無法安全停止）與 `BackupWorkflowDeadline` 中所有固定訊息的 `IllegalStateException` 改拋此型別；訊息文字逐字不變（既有測試的斷言不得被改動）。因是子類，所有既有 `isInstanceOf(IllegalStateException.class)`、`catch (IllegalStateException)` 與 `catch (RuntimeException)` 路徑行為不變。
- [ ] 498.3 `BackupService.runScheduledBackup(...)` 在既有 `catch (BackupRemoteUnavailableException)` 之後、通用 `catch (RuntimeException)` 之前新增 `catch (BackupSafeFailureException safe)`：`log.error("{}失敗: {}", label, safe.getMessage())` 並回傳 false。通用路徑維持原本的固定訊息，不得把任意 RuntimeException 的 message／cause／stack trace 寫進日誌；`doBackup` 的 preserveSource／清理／unlock 邏輯與 cron 入口、開機補救外層 catch 都不動（這些路徑的日誌不在本任務範圍）。
- [ ] 498.4 測試（皆不連 production DB、rclone、Google Drive，不觸發正式 backup）：
  - `ProcessBackupDatabaseProcessTest`：以既有可注入的 fake `ProcessStarter`（`CapturingStarter`）擷取實際 `ProcessBuilder.command()`，斷言 dump 參數恰含四個 exclusion（完整表名），並仍含 `--format=custom`、`--compress=9`、`--no-owner`、`--no-acl`；restore／validate 參數不變。另補 production `run()` 的失敗路徑案例，各斷言 `isInstanceOf(BackupSafeFailureException.class)` 與精確訊息：fake process `waitFor→false` 預期「pg_dump 執行逾時」；`exitValue→1` 預期「pg_dump 執行失敗」；starter 拋 `IOException` 預期「pg_dump 無法執行」；執行緒被 interrupt 預期「pg_dump 執行被中斷」；並以 `BackupWorkflowDeadline.within(Duration.ofSeconds(1), …)` 內呼叫 `commandSeconds(720)` 預期「備份流程期限不足，未啟動子行程」。`waitFor→false` 與 interrupt 兩案會進入 `terminateAndWait`（呼叫 `process.toHandle().descendants()`），而 JDK 預設 `Process.toHandle()` 拋 `UnsupportedOperationException`、既有 `CompletedProcess` fixture 也未覆寫，所以這兩案的 fake `Process` 必須覆寫 `toHandle()` 回傳無子孫且 `isAlive()=false` 的 stub `ProcessHandle`（例如 Mockito mock，`descendants()` 回空 Stream），fake `Process.isAlive()` 回 false；interrupt 案由 fake `waitFor` 拋 `InterruptedException`，測完須以 `Thread.interrupted()` 清除旗標。這些測試是 498.2 的唯一保護，不可省略（否則只新增類別與 catch 卻漏改 throw 時全綠但正式日誌不變）。
  - `BackupServiceTest`：dump 子行程逾時（fake `BackupDatabaseProcess` 拋 `BackupSafeFailureException("pg_dump 執行逾時")`）時，日誌含「台股每日備份失敗: pg_dump 執行逾時」；任意其他 `RuntimeException`（message 內放入 sentinel 字串）時只記通用訊息，日誌不含該 sentinel。
  - 新增一支隔離 PostgreSQL 測試類（置於 `com.steven.assets.service`，類別名須符合 `*BackupDatabase*Test` glob 以被下方驗證指令選到；沿用同族 PG 測試的純 `@Testcontainers`，無 Docker 時失敗而非靜默 skip，因真 PostgreSQL 驗證必須實跑；四個 exclusion 表名由 `ProcessBackupDatabaseProcess` 的 package-private 常數提供，測試取同一常數而非再寫一份；沿用 `SrppReportRepositoryPostgresTest` 的 `@BeforeEach` 前置：`app_user`、`srpp_policy_registry`、`api_error_log_operation`（`source varchar(16)`、`operation_key varchar(96)`、`operation_label varchar(96)`、`display_order int`、`PRIMARY KEY(source,operation_key)`，migration 無條件 INSERT 它）與 `reject_srpp_immutable_update()` 函式，再套用 `v1.150.0-srpp-report-calculations.sql`）：放入 sentinel rows（兩 SRPP 表與 `app_user`）→ 以與 production 相同的四個 exclusion 參數 `pg_dump --format=custom` → 再對**已有資料**的同一 DB 以 `pg_restore --clean --if-exists --no-owner --no-acl` 還原 → 斷言兩 SRPP 表 schema 仍在、`trg_srpp_report_base_no_update`／`trg_srpp_report_package_no_update` 與 `srpp_report_package_base_identity_fk` 仍在、兩表 rows 為 0、`app_user` sentinel 仍在。必須實際執行並在完成報告附結果。
- [ ] 498.5 重建並 recreate **只有** `business-services`，以 `-p asset-management` 執行，build 用 `--no-cache`（JVM 服務 cached build 可能不含變更）。merge 前依 `/run-stack` Step 1b 從本 worktree 驗收（驗收沒過不得 merge）；merge 進 main 後必須從已合併且乾淨的 `/Users/steven/Project/asset-management-main` 重建並再驗一次，使運行 image 與 main 一致（共用 stack 不被其他 worktree 洗回舊版）。build 前以 `cmp -s <build目錄>/.env /Users/steven/Project/asset-management-main/.env` 只回報同／不同，絕不把 `.env` 內容或 diff 印到輸出；並用 `grep -c "^ADMIN_EMAIL=\|^POSTGRES_" <build目錄>/.env` 確認必要鍵存在（從 main 目錄執行時兩者為同一檔）。等待 Docker health 與 BFF 到 business 的既有安全 GET 恢復，並 `docker restart asset-bff`（business recreate 換 IP 後 BFF 握舊 IP）。驗證：`docker exec asset-business-services sh -c 'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/service/ProcessBackupDatabaseProcess.class | strings | grep -c srpp_report_calculation_package'` 預期 ≥1（容器內無 `jar` 指令）；對 DB 唯讀地在容器 `/tmp` 以與 production 相同的四個 exclusion 參數跑一次 pg_dump（密碼只以 `PGPASSWORD="$DB_PASSWORD"` 置於 `sh -c` 內，不得 echo 或印出任何環境變數；立即刪檔，約 20 秒、約 109 MB），並 `pg_restore --list` 成功。**不得**手動呼叫 backup、sync、restore 或 rotate API，不得刪除 pending dump 或 Drive 物件，不得接觸任何券商交易 API。若部署時間晚於 07:00（美股）或 15:30（台股）且當日無 durable row，開機補救會自動提交——不要與之同時手動 pg_dump，以該次補救的日誌與 `backup_record` 為證據。

- [x] 498.6（已於本次 spec 變更中完成）`docs/ai-flow-observation.md` 第 54 行現況句「一般DB備份只排除ai_flow_run/event的data，schema及其餘金融data維持原備份流程」改為「Drive 備份 pg_dump 排除 ai_flow_run／ai_flow_event 與 SRPP 報告計算 base／package 的 data（Requirement 192／Task 498），schema 與其他金融 data 維持」。其他歷史任務檔（t492、t494）是當時 as-built 紀錄，不改。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME="$(/usr/libexec/java_home -v 25)" /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*BackupDatabase*Test,BackupServiceTest,ProcessBackupRemoteClientTest' test
python3 -m unittest scripts.tests.test_collect_srpp_ai_flow -k db_export
cd /Users/steven/Project/asset-management-main && docker compose -p asset-management build --no-cache business-services
cd /Users/steven/Project/asset-management-main && docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker restart asset-bff
```

部署後等 `asset-business-services` healthy，確認 `http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m` 回合法 JSON，並以上述方式確認 jar 內容與容器內 pg_dump 的耗時與大小。真正的成功證據只能是 `Backup uploaded and indexed` 日誌加 durable `backup_record`（開機補救或下一次排程：台北 07:00 美股 daily、15:30 台股 daily）；在那之前完成報告只可寫「已部署、參數與耗時已驗、待實際備份驗證」，不得宣稱備份已成功。

## 完成報告

待實作、獨立架構審查、容器重建與唯讀驗證後回填。
