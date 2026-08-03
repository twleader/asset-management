# [t287] 股市大盤排程匯出支援多時間點與多指數 checkbox

**對應 Requirements:** Requirement 45（股市大盤指數日線 Excel／JSON 匯出與排程自動匯出；本任務把排程由單一時間＋單一指數改為多時間點、每時間點多指數）
**前置任務:** Task 216（既有 GDP-TWSE 手動／排程匯出）、Task 270（單表雙格式匯出）、Task 242–243（Google Drive 輸出）
**Liquibase changeset:** `v1.88.0-index-export-multi-time-market.sql`

## 背景

目前 GDP／台股大盤頁的 `index_export_schedule` 每位使用者只有一列，該列只有一個 `run_hour`／`run_minute` 與一個 `market`。設定卡的「每日執行時間」與「匯出指數」都是單選，因此無法設定「早上匯出台股、晚上匯出美股」，也無法在同一時段一次留存多個美股指數。

本任務只改 Requirement 45 的排程設定；日線圖上方的手動「匯出 Excel」仍依畫面目前選取的單一指數與日期區間下載，避免把 transient 分時資料或圖表互動混入伺服器排程契約。排程共用的輸出路徑、匯出範圍與 Google Drive 設定仍一位使用者一份；時間點與指數選擇拆成正規化的子資料，不能以逗號字串、JSON 或 PostgreSQL array 儲存多值。

## 要做什麼

- [ ] **287.1 DB migration：** 建立 `index_export_schedule_time`（`schedule_id` 外鍵、`run_hour`、`run_minute`、`enabled`、`last_run_date`、`last_run_at`、`last_run_status`、`updated_at`；`UNIQUE(schedule_id, run_hour, run_minute)`）與 `index_export_schedule_time_market`（`schedule_time_id` 外鍵、`market`；複合主鍵）。
  - migration 必須先以既有 `index_export_schedule` 的 `run_hour`／`run_minute`／`market`／`last_run_*` 為來源，為每一列建立一個時間點與一筆 market 關聯，保留既有時間、指數、guard、結果與檔案命名行為。
  - 遷移完成後移除 parent 的 `run_hour`、`run_minute`、`market`、`last_run_date`、`last_run_at`、`last_run_status` 與對應 hour/minute constraint；保留 `enabled`、`output_subpath`、`range_months`、`updated_at` 及四個 Drive 欄位。
  - SQL 使用 `IF EXISTS`／`IF NOT EXISTS`／`ON CONFLICT DO NOTHING`；需要條件性讀取舊欄位時以 PostgreSQL `DO $$ ... EXECUTE ... $$` block 實作，並在 Liquibase changeset 設 `splitStatements:false`（或提出可驗證的等價方案）；在 `db.changelog-master.yaml` 最尾端註冊；建檔前以運行中 DB 的 `databasechangelog` 確認 v1.88.0 未被佔用，若需避讓必須同步修改檔名、changeset id 與 master include。運行中 DB 才是現況基準；`db/schema.sql` 缺少這張歷史表，不得拿它斷言 parent 不存在。
  - 在建立子表前以運行中 DB 的 `\d index_export_schedule` 與 `databasechangelog` 確認 v1.66.0 parent 已存在且已套用；若環境缺 parent，先停止並釐清 baseline，不可讓 migration 靜默捏造與既有 v1.66.0 不一致的表。
  - `market` 不建 DB CHECK；Java 的 `MacroHistoryService.DAILY_INDEX_CODES` 是唯一白名單來源。
- [ ] **287.2 Entity／Repository：**
  - `IndexExportSchedule` 改為 owner 一列的共用設定 entity，不再宣告單一時分、market、owner 層 last-run 欄位；保留 `@Filter(ownerFilter)`、owner unique、輸出路徑／範圍／Drive 欄位。
  - 新增 `IndexExportScheduleTime` entity，`@ManyToOne(fetch = FetchType.EAGER)` 指向 parent；以 `@ElementCollection`／`@CollectionTable(name="index_export_schedule_time_market", joinColumns=@JoinColumn(name="schedule_time_id"))` 映射 `Set<String> markets`，不得把多個 market 串成單一字串欄位。時間點的 owner 必須透過 parent 取得，不得另存重複 owner id。
  - 新增時間點 repository，提供依 `scheduleId` 及時間排序的查詢，並提供背景 `findAll()` 取得所有 owner 的時間點；HTTP 查詢只能先取得當前 owner 的 parent，再由 parent id 取子列。
- [ ] **287.3 DTO／API 契約：**
  - `GET /api/index-export/schedule` 回 `{enabled, outputSubpath, rangeMonths, baseDir, gdrive*, times}`；`times` 每項為 `{id, runHour, runMinute, enabled, markets, lastRunAt, lastRunStatus}`。
  - `PUT /api/index-export/schedule` 為整包取代語意：body 含 `{enabled, outputSubpath, rangeMonths, times:[{runHour,runMinute,enabled,markets}], gdriveEnabled, gdriveSubpath}`。同一 owner 的時間點清單完全依 body 重建；相同時分沿用原列 `lastRunDate`，避免儲存設定清掉當日 guard。
  - 驗證：時分必須在 0–23／0–59；同一 owner 不得有重複時分；每個時間點 `markets` 至少一個；每個代碼都必須在 `DAILY_INDEX_CODES`；輸出子路徑仍須 normalize 後位於 `EXPORT_OUTPUT_DIR` 之下；range 仍為 1–120 或 null；Google Drive 欄位仍使用 `GdriveOutputSupport.resolveUpdate` 的 null／權限／必填語意。
  - `POST /api/index-export/run-now` 忽略時間點 enabled 與總 enabled，只要有設定的 market 聯集非空，就每個 market 各產生一組雙格式檔案；回應新增 `files[]`，每項含 `market`、`marketLabel`、`path`、`sizeBytes`、`jsonPath`、`jsonSizeBytes`、`gdrivePath`、`jsonGdrivePath`、`gdriveStatus`。保留既有 `path`／`jsonPath` 等欄位指向第一筆，讓舊版前端不會把合法回應當成錯誤；不修改任何時間點的當日 guard。若資料庫尚無設定列，沿用既有 transient `08:00＋TWSE` 預設立即匯出；只有已儲存且清空時間點的設定才回 400。
- [ ] **287.4 排程 service：**
  - `getForCurrentUser()` 無 DB 列時仍回預設共用設定與一列暫存的 `08:00 + TWSE` 時間點，不寫 DB；`runNowForCurrentUser()` 同樣把此 transient 預設當作可匯出來源，且不得因 run-now 將 transient parent 或 child 寫回 DB。既有列經 migration 後回傳一列與原值相同。`updateForCurrentUser()` 在一個 transaction 內 upsert parent、驗證並整批替換時間點與 market 關聯，保留相同時分的 `lastRunDate`／`lastRunAt`／`lastRunStatus`。
  - `@Scheduled(cron="0 * * * * *", zone="Asia/Taipei")` 與 `ApplicationReadyEvent` 都呼叫同一個 due runner；先檢查 parent `enabled`，再逐時間點判斷 `now >= LocalTime.of(runHour, runMinute)`、時間點 enabled、當日 guard。每個時間點成功或失敗都寫自己的 guard／狀態；單一 market 的 render／寫檔／Drive 失敗只記錄該 market 並繼續同一時間點的其他 market。
  - 每個 market 都呼叫 `ExcelExportService.indexDailyDoc(market,start,end)` 一次，再由既有 `ExcelDocRenderer`、`JsonDocRenderer`、`DualFormatExportWriter` 產生同主檔名 `.xlsx` 與 `.json`；手動 `exportIndexDaily` 只委派同一個 `indexDailyDoc`。檔名為 `{indexLabel}_{ownerId}_{yyyyMMdd}`。同一 market 被多個時間點選取時，當日後執行者覆寫前一份；不同 market 不得互相覆寫。
  - parent 的 Drive last-run 欄位記錄最近一次雙格式同步結果；run-now 與背景排程都必須先寫本機兩份，再依既有 best-effort 規則同步 Drive。背景排程不得依賴 request-scoped `CurrentUserContext` 取得 owner。
- [ ] **287.5 BFF／前端：**
  - BFF `/api/bff/gdp-twse/export/schedule` 的 GET／PUT／run-now 路由維持 page-specific passthrough，body 與 response 原樣保留 `times[]`／`markets[]`／`files[]`。
  - `GdpTwseView.vue` 的排程卡改成時間點清單：每列一個 `el-time-picker`、啟用開關、可移除按鈕，以及 `el-select multiple collapse-tags`；每個 option 內顯示實際 `el-checkbox`，勾選值是該列 `markets[]`。提供「新增時間點」與「儲存設定」，儲存前擋重複時間與空指數清單；「匯出範圍」、本機／Drive 資料夾、總啟用與既有提示保留。
  - `SchedulePublicBffController.JOBS` 保留既有「大盤指數匯出」項目並同步描述多時間點／多指數語意，不新增重複項目。
  - 讀取舊回應時把單一 `market`／`runHour`／`runMinute` 映射成一列；run-now 回傳多筆時逐 market 顯示 JSON／Excel 落點與 Drive 狀態。手動圖表匯出仍只送 `market.value`，不可改成抓排程聯集。
- [ ] **287.6 測試：**
  - service 測試覆蓋：多時間點整批取代、重複時分 400、空 markets 400、未知 market 400、保留相同時間 guard、背景只執行到點時間、同時間多 market 產生兩組檔案、單一 market 失敗仍嘗試下一個、run-now 不改任何 guard、owner 隔離與 Drive 雙格式回報。
  - migration／repository 測試或啟動驗證確認既有單一設定列轉成一列時間＋一筆 market，parent 與子表外鍵／唯一鍵存在；前端 build／靜態檢查確認使用 `multiple` 與 `markets[]`，且手動單市場匯出與「當日」停用規則未回歸。
- [ ] **287.7 驗證與部署：**
  - 先跑 `bash scripts/spec-check.sh`，再跑獨立 spec-auditor 並記錄 `bash .claude/hooks/spec-review-pass.sh`；spec hash 通過後才可寫 backend／BFF／frontend／db changelog。
  - `/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test`
  - `/Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build`（cwd `frontend`）
  - `docker compose -p asset-management build --no-cache business-services bff frontend`
  - `docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend`
  - 以 `curl` 驗證 `/actuator/health`、未登入 BFF schedule／run-now 回 401；登入測試 owner 以兩列（09:00＋TWSE、22:00＋DJI/SPX）儲存，確認 GET 回完整 times／markets、run-now 產生三組雙格式（六個檔案）落點，並查 DB 確認 parent／time／market 三層資料與每時間點 guard。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
curl -fsS http://localhost:8080/actuator/health
```

另外需以實際登入 owner 的 API／頁面完成 287.7 的兩時間點案例，不能只以編譯成功或單一 TWSE 檔案存在宣稱完成；驗證後不得留下未經使用者要求啟用的每日排程。

## 完成報告

（實作者完成後回填：實際修改檔案、migration 套用結果、單元／前端／容器驗證輸出、兩時間點多指數案例的實際檔案清單，以及與本計畫的偏差。）
