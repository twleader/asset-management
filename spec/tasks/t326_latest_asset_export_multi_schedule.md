# [t326] 最新資產每日匯出支援多個執行時間

**對應 Requirements:** Requirement 69（擴充 Requirement 34 的最新資產排程匯出）
**前置任務:** Task 171（單一時間排程）、Task 243（Google Drive）、Task 271／282（雙格式匯出）
**Liquibase changeset:** `v1.102.0-export-schedule-multi-time.sql`（實作前須查運行中 `databasechangelog`；若已佔用則整套改號）

## 背景

`ExportScheduleService` 現在每位 owner 只有一列 `export_schedule_setting`，時間與 `last_run_date` 都放在 parent。第一個時間跑完就會把 owner 當日整體 guard 設為已執行，因此資料模型本身無法承載第二個每日時間。

本任務把時間拆成正規化 children。輸出路徑、總啟用、Google Drive 與最近一次整體執行摘要仍由 parent 共用；每個時間各自 enabled、guard 與 status。每次仍查一次 `liveAssetsDoc*()` 後產出同主檔名 `.xlsx/.json`，後一時段覆寫同日檔案成較新的內容，維持「最新資產」而非建立盤中歷史版本。

## 要做什麼

- [x] **326.1 DB migration：**
  - 建立 `export_schedule_time`：`id BIGSERIAL PK`、`schedule_id BIGINT NOT NULL REFERENCES export_schedule_setting(id) ON DELETE CASCADE`、`run_hour`、`run_minute`、`enabled DEFAULT TRUE`、`last_run_date`、`last_run_at`、`last_run_status VARCHAR(500)`、`updated_at`。
  - 加 `UNIQUE(schedule_id,run_hour,run_minute)`、hour 0–23 CHECK、minute 0–59 CHECK 與 `schedule_id` index。
  - 對每一筆既有 parent 以原 `run_hour/run_minute` 建一列 child，`enabled=true`，並複製 parent `last_run_date/last_run_at/last_run_status/updated_at`。如此部署當天若原排程已跑過，新 child 仍被 guard，不會重跑。
  - 本次採 expand/contract：搬移後仍保留 parent 的 hour/minute CHECK 與三個 legacy 欄位作 rollback shadow；`run_hour/run_minute` 維持 NOT NULL，`last_run_date` 維持 nullable，新 scheduler 不再以它們判斷 due。真正 drop 必須留待後續獨立 Requirement／changeset，確認不再需要舊 image 後才做。
  - 新程式以「最早啟用 child；若全停用則最早 child」作 rollback representative；每次設定儲存同步 parent `run_hour/run_minute` 與 representative `last_run_date`，representative 排程執行時亦同步 legacy guard，確保 rollback 至舊 image 可啟動並只執行代表時間。
  - SQL 需以 `IF NOT EXISTS`／`ON CONFLICT DO NOTHING` 保護重複建立；`v1.53.0` 是 master 中已固定先套用的 prerequisite，因此可直接讀取 parent 既有欄位，並保留 Liquibase 預設逐 statement 切分，不需要條件式 `DO $$` 或 `splitStatements:false`。在 `db.changelog-master.yaml` 最尾端 include。先以運行中 DB 的 `databasechangelog`、`\d export_schedule_setting` 確認 parent 與預定 v1.102.0；若版本被佔用，檔名、changeset id、本 task 與 design 同步改號。
- [x] **326.2 Entity／Repository：**
  - `ExportScheduleSetting` 保留 `runHour/runMinute/lastRunDate`，但明確標記為 rollback shadow、不得作新 scheduler 的 due 來源；保留 parent `lastRunAt/lastRunStatus`，並新增 `@OneToMany(mappedBy="schedule", cascade=ALL, orphanRemoval=true)` times。排序可由 `@OrderBy("runHour ASC, runMinute ASC, id ASC")` 或 repository 明確排序；提供 `addTime` 維持雙向關係。
  - 新增 `ExportScheduleTime` entity。它不另存 owner id，owner 由 parent 取得；parent 關聯不可讓 HTTP 以任意 schedule id 繞過 owner filter。
  - 視 lazy/eager 決策增加 `ExportScheduleTimeRepository`，或在 parent repository 用 `@EntityGraph`／fetch query 取得 times。背景 `findAll` 必須在 transaction 內可靠讀到所有 parent children，HTTP 必須先由 `findByOwnerUserId` 限縮本人。
- [x] **326.3 DTO 契約：**
  - `SettingResponse` 改為 `{enabled,outputSubpath,lastRunAt,lastRunStatus,baseDir,gdrive*,times}`；`times` 每項 `{id,runHour,runMinute,enabled,lastRunAt,lastRunStatus}`，依時分排序。Top-level `runHour/runMinute` 從新 response 移除。
  - `SettingRequest` 改為 `{enabled,outputSubpath,gdriveEnabled,gdriveSubpath,times:[{runHour,runMinute,enabled}]}`，不接受 client id，採整包取代。
  - `RunNowResponse`、browse DTO 與 BFF endpoint paths 全部不變。BFF 保持 `Map<String,Object>` passthrough，不新增 DTO 鏡像。
- [x] **326.4 設定讀寫：**
  - `getForCurrentUser()` 無 DB parent 時回 transient default parent＋一列 `08:00 enabled=true`，不得寫 DB。
  - `updateForCurrentUser()` 加 `@Transactional`。先完整驗證再異動：times 不得 null/empty；hour/minute 合法；同 request 無重複時分；parent enabled=true 時至少一列 child enabled=true；本機路徑與 Drive 的 null／主要管理者／必填／self-check 語意不變。
  - 以 `(runHour,runMinute)` 對映舊 child。相同時分的新列必須 copy `id` 所代表的既有 entity或保留其 `lastRunDate/At/Status`，避免存設定清 guard；新時分 guard=null；不在 body 的 child 由 orphan removal 刪除。最後回 repository 實際保存後的完整排序 response。
  - Parent `updatedAt` 每次設定變更更新；不得把 child status 當使用者輸入接受。儲存後依最早啟用 child（全停用時最早 child）同步 parent rollback shadow 的 `runHour/runMinute/lastRunDate`。
- [x] **326.5 背景多時間 due runner：**
  - 保留且只保留既有一個 `@Scheduled(cron="0 * * * * *",zone="Asia/Taipei")` 與一個 `ApplicationReadyEvent`；兩者都先走同一個 `tryRunDueExports`／`AtomicBoolean.compareAndSet` 入口才可呼叫 runner，防止啟動瞬間與跨分鐘 tick 重入。Compose 維持 business-services 單 replica；若未來多 replica，須另加 DB atomic claim／row lock。
  - 對每個 parent 先驗 parent enabled，再依時分逐 child；條件為 child enabled、`now >= LocalTime.of(hour,minute)`、child `lastRunDate != today`。一個 child 跑完（成功或失敗）只寫該 child 的 guard/time/status，並把同一結果同步為 parent 最近一次摘要；若它是 rollback representative，亦同步 parent `lastRunDate`。
  - 單一 child 發生 render／寫檔／Drive／資料錯誤只記錄並繼續下一個到點 child與下一 owner。若啟動時 08:00 與 12:00 都過期且均未 guard，self-heal 必須依序嘗試兩次；不得在第一輪後 return 或靠 parent lastRun 擋第二輪。
  - 每輪仍只呼叫一次 `ExcelExportService.liveAssetsDocForOwner(ownerId)`，再由既有 renderers + `DualFormatExportWriter` 產兩份。檔名維持 `資產總覽_{ownerId}_{yyyyMMdd}`；同日後一時段覆寫同名 local／Drive 檔是明確產品語意。
- [x] **326.6 run-now：**
  - `runNowForCurrentUser()` 忽略 parent/child enabled，沿用目前共用路徑與 Drive 設定立即產檔。已有 parent 時不得新增或修改 children。
  - 無 parent 時維持既有持久化語意：建立 disabled parent，並建立一列 `08:00 enabled=true`、guard/status 皆 null 的預設 child；本次 run-now 不消耗該 child 排程。
  - 它只更新 parent `lastRunAt/lastRunStatus` 與 Drive last-run 欄，不可修改既有 child guard/status。既有七欄雙格式 response 不變。
- [x] **326.7 前端時間清單：**
  - `AssetHistoryView.vue` 移除單值 `scheduleTime`，新增帶 client-only key 的 `scheduleTimes`。每列有 `el-time-picker`、enabled switch、移除鈕，以及該列 `lastRunAt/lastRunStatus`；另有「新增時間」按鈕。
  - Load 優先把 `s.times[]` 正規化、排序為 `HH:mm` rows；只在 rolling upgrade 遇到沒有 `times` 的舊 response 時，將 top-level `runHour/runMinute` 映成一列。新後端 response 空 times 視為異常，不靜默補預設。
  - Save 前擋：空清單、無效時間、重複時分、總啟用但全 child disabled、Drive enabled 但無資料夾。送 `times:[{runHour,runMinute,enabled}]`；成功後以 response 完整重建 rows，不能 client merge。
  - 本機／Drive folder picker、run-now、parent 整體上次執行、雙格式說明保持。提示文字改為「於下列每個啟用時間更新同日最新檔」，清楚說明較晚時段覆寫同日同名檔。
- [x] **326.8 排程列表與 active docs：**
  - `SchedulePublicBffController.JOBS` 不增減項目，只把既有最新資產匯出描述改為「每分鐘檢查歷年資產頁設定的多個每日時間」；annotation count 與列表總數維持。
  - `CLAUDE.md`／steering 若有單時間敘述要同步；Requirement 34、design 舊 schema/guard 段落就地註記由 Requirement 69 取代。不得把其他單時間匯出模組順便改成多時間。
- [x] **326.9 測試：**
  - Migration／repository：舊 parent 轉一 child且時分、guard、status 無損；FK cascade／unique／checks 存在；parent 舊三欄仍保留既有 nullability 的 rollback shadow、摘要兩欄保留，且 migration 可讓舊 image schema 啟動。
  - Service：transient default、times 排序、多時間整包取代、重複/空/非法/總啟用但無 active 驗證、相同時分保 guard、新時分無 guard、owner isolation，以及 representative parent shadow 的選擇與同步。
  - Scheduler：第一 child 已 guard 不阻止第二 child；兩個 overdue 都執行；第一個失敗仍執行第二個；disabled parent/child 不執行；每 child 成功失敗都 guard；parent 摘要指向最後完成的一輪；startup self-heal 與 minute tick 共用 CAS 且不重複執行。
  - run-now：既有 parent 不修改 child guard/status；無 parent 時只建立 disabled parent＋未消耗的 08:00 child；雙格式、本機先行、Drive best-effort 與 self-check 測試不回歸。更新所有手動建構 `ExportScheduleService`／`ExportScheduleDto` 的現有測試。
  - Frontend build／靜態測試釘住 add/remove、times payload、重複驗證與 legacy fallback；BFF passthrough response 不漏 `times`。
- [x] **326.10 Docker 實機驗證與清理：**
  - 從本 worktree rebuild/recreate `business-services`、`bff`、`frontend`，bounded wait healthy；確認 Liquibase changeset 套用一次且 schema/legacy migration 正確。
  - 使用實際登入 owner 先備份原設定，再儲存至少 `08:00`／`12:00` 兩列（或以當前時間前後的受控等價時間），GET 讀回排序、per-time status 與 representative parent shadow；以受控 DB guard/time 案例觸發 runner，證明同日兩列各跑一次、產出兩份格式且第二輪更新同名檔。
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

- 實作：新增正規化 `export_schedule_time` child、per-time enabled/guard/status 與 `times[]` GET/PUT 契約；parent 保留 legacy rollback shadow 與整體最近結果。設定採整包取代但相同時分保留 guard，scheduler 的 startup/tick 共用 JVM CAS 並逐一執行所有已到點 child，一列失敗不阻斷後續；run-now 不消耗 child guard。歷年資產頁已改為可新增、停用與移除多個時間，保留本機／Drive／立即匯出與雙格式行為。
- Migration runtime：`v1.102.0-export-schedule-multi-time` 在 `databasechangelog` 恰為一筆 `EXECUTED`；實機確認 child PK、`ON DELETE CASCADE` FK、同 parent 時分 unique、hour/minute CHECK 與 index 均存在，parent legacy 欄位與 nullability 未被移除或放寬。原單時間與 guard 已搬成一列 child。
- 自動驗證（2026-08-14）：backend 907/907、BFF 63/63 全量測試與 frontend production build 全數通過；涵蓋 transient 08:00、空／重複／非法／全停用驗證、guard 保留、兩個 overdue、首列失敗續跑、startup/tick CAS、run-now 不改 guard、owner/Drive/雙格式回歸。`spec-check` 為 `BLOCK 0 / CHECK 0`。
- Docker runtime：以 owner 真實設定暫存後，儲存 `00:00`、`00:01`、`08:00` 三列並讓下一輪 scheduler 同日依序執行兩個已到點 child；立即匯出產出 xlsx/json（211166／1063059 bytes），run-now 前後每個 child guard 完全相同。Parent 與 child 備份／還原內容雜湊分別維持 `ddc38718…`／`f578508d…`。
- 清理：驗證後已恢復原設定（enabled、08:00、`Project/SRPP/data/input`、Drive enabled、原 guard/status），DB 只保留原 child id 1；測試輸出與暫存目錄均已移除，未留下額外排程或檔案。
