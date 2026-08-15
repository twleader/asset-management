# [t330] 已實現損益每日匯出支援多個執行時間

**對應 Requirements:** Requirement 72（擴充 Requirement 39 的已實現損益排程匯出）
**前置任務:** Task 196（單一時間排程）、Task 242／243（Google Drive）、Task 270（雙格式匯出）、Task 326（最新資產多時間點——本任務的參照實作）
**Liquibase changeset:** `v1.103.0-realized-gain-export-schedule-multi-time.sql`（實作前須查運行中 `databasechangelog`；若已佔用則整套改號）

## 背景

`RealizedGainExportScheduleService` 現在每位 owner 只有一列 `realized_gain_export_schedule`，時間與 `last_run_date` 都放在 parent。第一個時間跑完就會把 owner 當日整體 guard 設為已執行，因此資料模型本身無法承載第二個每日時間。

Task 326 已在「歷年資產／最新資產匯出」把同一個問題解掉（`export_schedule_setting` ＋ `export_schedule_time`）。本任務把**同一套做法原樣套到已實現損益頁**，讓兩頁用法一致：資料模型、DTO 欄位名、驗證訊息、rollback representative 策略、UI 版面與按鈕文案全部比照 Task 326，差別只有表名、entity 名、端點前綴與匯出內容。

輸出路徑、總啟用、Google Drive 與最近一次整體執行摘要仍由 parent 共用；每個時間各自 enabled、guard 與 status。每次仍查一次 `realizedGainsDoc*()` 後產出同主檔名 `.xlsx/.json`，後一時段覆寫同日檔案成較新的內容。

> **刻意不抽共用父類別／泛型 service。** 一致性以「相同結構與相同命名」維持，不以繼承維持——兩支 service 的 doc 來源、租戶隔離方式各自獨立演進（本頁背景產檔必須 `enableFilter`），過早抽象會互相牽制。**也不得順手把其餘單時間匯出頁**（交易日曆 R37／油價金價 R41／匯率 R42／GDP-TWSE R45／交易雷達 R48／交易紀錄 R49）**一併改成多時間**。

## 要做什麼

- [ ] **330.1 DB migration：**
  - 建立 `realized_gain_export_schedule_time`：`id BIGSERIAL PK`、`schedule_id BIGINT NOT NULL REFERENCES realized_gain_export_schedule(id) ON DELETE CASCADE`、`run_hour INT NOT NULL`、`run_minute INT NOT NULL`、`enabled BOOLEAN NOT NULL DEFAULT TRUE`、`last_run_date DATE`、`last_run_at TIMESTAMP`、`last_run_status VARCHAR(500)`、`updated_at TIMESTAMP`。
  - 加 `UNIQUE(schedule_id, run_hour, run_minute)`、hour 0–23 CHECK、minute 0–59 CHECK 與 `schedule_id` index。約束命名比照既有慣例（`uq_rg_export_schedule_time`、`ck_rg_export_schedule_time_hour`／`_minute`、`idx_rg_export_schedule_time_schedule`），長度須符合 PostgreSQL 63 字元識別字上限。
  - 對每一筆既有 parent 以原 `run_hour/run_minute` 建一列 child，`enabled=true`，並複製 parent `last_run_date/last_run_at/last_run_status/updated_at`。如此部署當天若原排程已跑過，新 child 仍被 guard，不會重跑。
  - 採 expand/contract：搬移後仍保留 parent 的 hour/minute CHECK 與三個 legacy 欄位作 rollback shadow；`run_hour/run_minute` 維持 NOT NULL，`last_run_date` 維持 nullable，新 scheduler 不再以它們判斷 due。真正 drop 留待後續獨立 Requirement／changeset。
  - SQL 需以 `CREATE TABLE/INDEX IF NOT EXISTS` 與 `ON CONFLICT DO NOTHING` 保護重複建立；`v1.59.0`（建 parent）與 `v1.76.0`（Drive 欄位）都是 master 中已固定先套用的 prerequisite，可直接讀取 parent 既有欄位，保留 Liquibase 預設逐 statement 切分，不需 `DO $$` 或 `splitStatements:false`。在 `db.changelog-master.yaml` 最尾端 include。
  - 先以運行中 DB 的 `databasechangelog`、`\d realized_gain_export_schedule` 確認 parent 現況與 `v1.103.0` 未被佔用（撰寫本任務時最後一筆為 `v1.102.0-export-schedule-multi-time`）；若版本被佔用，檔名、changeset id、本 task 與 design 同步改號。
- [ ] **330.2 Entity／Repository：**
  - `RealizedGainExportSchedule` 保留 `runHour/runMinute/lastRunDate`，但 javadoc 明確標記為 rollback shadow、不得作新 scheduler 的 due 來源；保留 parent `lastRunAt/lastRunStatus` 作整體摘要；新增 `@OneToMany(mappedBy="schedule", cascade=CascadeType.ALL, orphanRemoval=true)` ＋ `@OrderBy("runHour ASC, runMinute ASC, id ASC")` 的 `times`，並提供 `addTime` 維持雙向關係（比照 `ExportScheduleSetting`）。
  - 新增 `RealizedGainExportScheduleTime` entity（比照 `ExportScheduleTime`）：`@ManyToOne(fetch = LAZY, optional = false)` 指向 parent，不另存 owner id。
  - `RealizedGainExportScheduleRepository` 以 `@EntityGraph(attributePaths = "times")` 覆寫 `findByOwnerUserId` 與 `findAll`，確保背景 `findAll` 在 transaction 內可靠讀到所有 children，HTTP 仍先由 `findByOwnerUserId` 限縮本人。
- [ ] **330.3 DTO 契約：**
  - `RealizedGainExportDto.SettingResponse` 改為 `{enabled,outputSubpath,lastRunAt,lastRunStatus,baseDir,gdriveEnabled,gdriveSubpath,gdriveRemote,gdriveLastRunAt,gdriveLastStatus,gdriveSelfCheckWarning,times}`；`times` 每項 `{id,runHour,runMinute,enabled,lastRunAt,lastRunStatus}`，依時分／id 排序。Top-level `runHour/runMinute` 從 JSON 契約移除（如需保留 Java 端 convenience accessor，比照 `ExportScheduleDto` 加 `@JsonIgnore`）。
  - `SettingRequest` 改為 `{enabled,outputSubpath,gdriveEnabled,gdriveSubpath,times:[{runHour,runMinute,enabled}]}`，不接受 client id，採整包取代。
  - `RunNowResponse` 七欄與 BFF endpoint paths 全部不變。`RealizedGainBffController` 維持 `Map<String,Object>` passthrough（確認不會漏掉 `times`），不新增 DTO 鏡像。
- [ ] **330.4 設定讀寫：**
  - `getForCurrentUser()` 無 DB parent 時回 transient default parent ＋一列 `08:00 enabled=true`，不得寫 DB；DB parent 的 `times` 為空時（rolling upgrade／手動 fixture）以 legacy 時分補一列，同樣不寫 DB。
  - `updateForCurrentUser()` 加 `@Transactional`。先完整驗證再異動：times 不得 null/empty（「至少需要一個執行時間」）；hour/minute 合法（「執行時間必須介於 00:00～23:59」）；同 request 無重複時分（「執行時間不可重複」）；parent `enabled=true` 時至少一列 child enabled（「啟用排程時至少需啟用一個時間」）。錯誤訊息與 `ExportScheduleService` 逐字一致。本機路徑（`normalizeSubpath` ＋ `resolveDir` 防跳脫）與 Drive 的 null 解析／主要管理者／必填／self-check 語意不變。
  - 以 `(runHour,runMinute)` 對映舊 child：相同時分保留既有 entity 的 `lastRunDate/At/Status`，新時分 guard=null，不在 body 的 child 由 orphan removal 刪除。最後回 repository 實際保存後的完整排序 response。
  - Parent `updatedAt` 每次設定變更更新；不得把 child status 當使用者輸入接受。儲存後依最早啟用 child（全停用時最早 child）同步 parent rollback shadow 的 `runHour/runMinute/lastRunDate`。
- [ ] **330.5 背景多時間 due runner：**
  - 保留且只保留既有一個 `@Scheduled(cron="0 * * * * *",zone="Asia/Taipei")` 與一個 `ApplicationReadyEvent`；兩者都先走同一個 `tryRunDueExports`／`AtomicBoolean.compareAndSet` 入口才可呼叫 runner（現況 CAS 只包在 `tick()` 內、self-heal 沒經過，須一併改為共用入口），防止啟動瞬間與跨分鐘 tick 重入。Compose 維持 business-services 單 replica；若未來多 replica，須另加 DB atomic claim／row lock。
  - 對每個 parent 先驗 parent enabled，再依時分逐 child；條件為 child enabled、`now >= LocalTime.of(hour,minute)`、child `lastRunDate != today`。一個 child 跑完（成功或失敗）只寫該 child 的 guard/time/status，並把同一結果同步為 parent 最近一次摘要；若它是 rollback representative，亦同步 parent `lastRunDate`。
  - 單一 child 發生 render／寫檔／Drive／資料錯誤只記錄並繼續下一個到點 child 與下一 owner。若啟動時 08:00 與 12:00 都過期且均未 guard，self-heal 必須依序嘗試兩次；不得在第一輪後 return 或靠 parent lastRun 擋第二輪。
  - 每輪仍只呼叫一次 `excelExportService.realizedGainsDocForOwner(ownerId)`（**背景租戶隔離不得弱化**），再由既有 renderers ＋ `DualFormatExportWriter` 產兩份。檔名維持 `已實現損益_{ownerId}_{yyyyMMdd}`；同日後一時段覆寫同名 local／Drive 檔是明確產品語意。
- [ ] **330.6 run-now：**
  - `runNowForCurrentUser()` 忽略 parent/child enabled，沿用目前共用路徑與 Drive 設定立即產檔。已有 parent 時不得新增或修改 children。
  - 無 parent 時維持既有持久化語意：建立 `enabled=false` parent，並建立一列 `08:00 enabled=true`、guard/status 皆 null 的預設 child，同步 legacy shadow 時分；本次 run-now 不消耗該 child 排程。
  - 它只更新 parent `lastRunAt/lastRunStatus` 與 Drive last-run 兩欄，不可修改既有 child guard/status。既有七欄雙格式 response 不變。
- [ ] **330.7 前端時間清單：**
  - `RealizedGainView.vue` 移除單值 `scheduleTime`，新增帶 client-only key 的 `scheduleTimes`。每列有 `el-time-picker`、enabled switch、移除鈕（`scheduleTimes.length === 1` 時 disabled），以及該列 `lastRunAt/lastRunStatus`；另有「＋ 新增時間」按鈕。版面、文案與 `AssetHistoryView.vue` 逐項對齊。
  - Load 優先把 `s.times[]` 正規化、排序為 `HH:mm` rows；只在遇到沒有 `times` 欄位的舊 response 時，將 top-level `runHour/runMinute` 映成一列。新後端回空陣列視為異常，顯示錯誤訊息、不靜默補預設。
  - Save 前擋：空清單、無效時間、重複時分、總啟用但全 child disabled、Drive enabled 但無資料夾（既有檢查）。送 `times:[{runHour,runMinute,enabled}]`；成功後以 response 完整重建 rows，不能 client merge；`schedule.runHour/runMinute` 這兩個只服務單一時間的 reactive 欄位一併移除。
  - 本機／Drive folder picker、run-now、parent 整體上次執行、雙格式說明保持。提示文字改為說明「於下列每個啟用時間更新同日最新檔」，並點出較晚時段覆寫同日同名檔。
  - `frontend/src/api/index.js` 的 `realizedGain` 命名空間若對 schedule payload 有形狀假設，一併更新；BFF 路徑不變。
- [ ] **330.8 排程列表與 active docs：**
  - `SchedulePublicBffController.JOBS` 不增減項目，只把既有「已實現損益匯出」描述改為「每分鐘檢查已實現損益頁設定的多個每日時間，命中即同時產出 JSON 與 Excel 兩份…」；annotation count 與列表總數（51 筆）維持。若該 controller 有計數斷言／測試，須確認仍通過。
  - Requirement 39、design 舊 schema/guard 段落已就地註記由 Requirement 72 取代（本次 spec 變更已完成）；實作時若發現其他殘留單時間敘述（含 `CLAUDE.md`／`spec/steering/`）一併同步。
- [ ] **330.9 測試：**
  - Migration／repository：舊 parent 轉一 child 且時分、guard、status 無損；FK cascade／unique／checks 存在；parent 舊三欄仍保留既有 nullability 的 rollback shadow、摘要兩欄保留，且 migration 後舊 image schema 仍可啟動。
  - Service：transient default、times 排序、多時間整包取代、重複／空／非法／總啟用但無 active 驗證、相同時分保 guard、新時分無 guard、owner isolation，以及 representative parent shadow 的選擇與同步。
  - Scheduler：第一 child 已 guard 不阻止第二 child；兩個 overdue 都執行；第一個失敗仍執行第二個；disabled parent/child 不執行；每 child 成功失敗都 guard；parent 摘要指向最後完成的一輪；startup self-heal 與 minute tick 共用 CAS 且不重複執行；背景走 `realizedGainsDocForOwner`（owner-scoped）而非全域 doc。
  - run-now：既有 parent 不修改 child guard/status；無 parent 時只建立 disabled parent ＋未消耗的 `08:00` child；雙格式、本機先行、Drive best-effort 與 self-check 測試不回歸。更新所有手動建構 `RealizedGainExportScheduleService`／`RealizedGainExportDto` 的既有測試（至少 `GdriveSelfCheckTest`、`SingleTableScheduleServiceDualFormatTest`）。
  - Frontend build／靜態測試釘住 add/remove、times payload、重複驗證與 legacy fallback；BFF passthrough response 不漏 `times`。
- [ ] **330.10 Docker 實機驗證與清理：**
  - 從本 worktree rebuild/recreate `business-services`、`bff`、`frontend`（JVM 服務一律 `--no-cache`），bounded wait healthy；確認 Liquibase changeset 套用一次且 schema／legacy migration 正確。
  - 使用實際登入 owner 先備份原設定（parent 與 child 內容雜湊），再儲存至少 `08:00`／`12:00` 兩列（或以當前時間前後的受控等價時間），GET 讀回排序、per-time status 與 representative parent shadow；以受控 DB guard/time 案例觸發 runner，證明同日兩列各跑一次、產出兩份格式且第二輪更新同名檔。
  - 驗證 run-now 前後 child guard 完全相同。完成後還原原設定與原 guard；不得留下額外測試排程、測試輸出或未經使用者要求的新啟用時間。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
curl -fsS http://127.0.0.1:9090/actuator/health
```

## 完成報告

（實作後回填：實作摘要、migration runtime 證據、自動驗證數字、Docker runtime 證據、清理結果）
