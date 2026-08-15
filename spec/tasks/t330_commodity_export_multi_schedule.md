# [t330] 油價金價每日匯出支援多個執行時間

**對應 Requirements:** Requirement 72（擴充 Requirement 41 的油價金價排程匯出）
**前置任務:** Task 203（單一時間排程）、Task 242（Google Drive 同步）、Task 270（雙格式 xlsx/json 匯出）、Task 326（歷年資產已完成同一種多時間點改造，本任務模式直接比照）
**Liquibase changeset:** `v1.103.0-commodity-export-schedule-multi-time.sql`（實作前須查運行中 `databasechangelog`；若已佔用則整套改號）

## 背景

`CommodityExportScheduleService` 現在每位 owner 只有一列 `commodity_export_schedule`，時間（`run_hour`/`run_minute`）與 `last_run_date` 當日 guard 都放在 parent。第一個時間跑完就會把 owner 當日整體 guard 設為已執行，因此資料模型本身無法承載第二個每日時間。使用者要求「排程時間點要可以有多個」。

本任務把時間拆成正規化 children，做法與 Task 326（歷年資產頁的同一種改造）完全同構。輸出路徑、總啟用、**匯出範圍 `range_months`**、Google Drive 與最近一次整體執行摘要仍由 parent 共用；每個時間各自 `enabled`、guard 與 status。每次執行仍只查一次 `ExcelExportService.commodityPricesDoc(start, end)` 後產出同主檔名 `.xlsx`/`.json`，後一時段覆寫同日檔案成較新的內容，維持「當日最新兩份檔」而非建立盤中歷史版本。

**運行中 DB 現況（已於本次規劃前查證，勿改用 `db/changelog/**` 或 `db/schema.sql` 判斷）：**

```
\d commodity_export_schedule
 id                 bigint            NOT NULL  (identity)
 owner_user_id      bigint            NOT NULL
 enabled            boolean           NOT NULL DEFAULT false
 run_hour           integer           NOT NULL DEFAULT 8
 run_minute         integer           NOT NULL DEFAULT 0
 output_subpath     varchar(255)      NOT NULL DEFAULT 'input'
 range_months       integer           (nullable；NULL＝全部十年)
 last_run_date      date              (nullable)
 last_run_at        timestamp         (nullable)
 last_run_status    varchar(500)      (nullable)
 updated_at         timestamp         (nullable)
 gdrive_enabled     boolean           NOT NULL DEFAULT false
 gdrive_subpath     varchar(512)      (nullable)
 gdrive_last_run_at timestamp         (nullable)
 gdrive_last_status varchar(512)      (nullable)
Indexes: PK(id), UNIQUE uq_commodity_export_schedule_owner(owner_user_id)
Checks: run_hour 0..23／run_minute 0..59／range_months NULL or 1..120

databasechangelog 尾端（ORDER BY orderexecuted DESC）：
 v1.102.0-export-schedule-multi-time  ← 與本 worktree db.changelog-master.yaml 尾端一致，v1.103.0 未被佔用
 v1.101.0-radar-notification-action-policy-version
 v1.100.0-radar-dividend-fetch-attempt
 ...

commodity_export_schedule_time：尚不存在（本任務新建）。
```

**與 Task 326 的差異只有兩點**（其餘全部照抄）：
1. 本頁 parent 多一個 `range_months`（滾動匯出範圍月數）欄位，**維持 parent-only、不下放至 child**——匯出範圍是「整份設定」的屬性，不因執行時段而異；每次某個 child 到點執行時，都以「執行當下」重新計算 `start=end.minusMonths(range_months)`（或 `range_months==null` 時 `minusYears(10)`），與現行單時間版本邏輯一致。
2. `CommodityExportScheduleService` **目前沒有任何專屬測試檔**（`backend/src/test/java/com/steven/assets/service/` 底下找不到以它為對象的同名測試類），與 Task 326 擴充既有測試（`ExportScheduleGdriveTest`／`GdriveBrowseTest`／`SingleTableScheduleServiceDualFormatTest` 等既有檔案內已涵蓋 `ExportScheduleService` 的案例）不同——本任務要對 `CommodityExportScheduleService` **新建一支專屬測試類**，放在同目錄 `backend/src/test/java/com/steven/assets/service/`，涵蓋單時間與多時間兩種情境，不是「更新既有測試」。檔名依專案既有命名慣例——服務類名稱後方加上字尾再接 `.java`，例如 `AssetTransactionExportScheduleService` 對應的測試檔是 `AssetTransactionExportScheduleServiceTest.java`、`TradingRadarExportScheduleService` 對應的是 `TradingRadarExportScheduleServiceTest.java`——比照辦理。

## 要做什麼

- [x] **330.1 DB migration（`v1.103.0-commodity-export-schedule-multi-time.sql`）：**
  - 建立 `commodity_export_schedule_time`：`id BIGSERIAL PK`、`schedule_id BIGINT NOT NULL REFERENCES commodity_export_schedule(id) ON DELETE CASCADE`、`run_hour INT NOT NULL`、`run_minute INT NOT NULL`、`enabled BOOLEAN NOT NULL DEFAULT TRUE`、`last_run_date DATE`、`last_run_at TIMESTAMP`、`last_run_status VARCHAR(500)`、`updated_at TIMESTAMP`。
  - 加 `UNIQUE(schedule_id,run_hour,run_minute)`、`CHECK(run_hour BETWEEN 0 AND 23)`、`CHECK(run_minute BETWEEN 0 AND 59)`，並建 `schedule_id` 的 index（供背景查詢用）。
  - 對每一筆既有 `commodity_export_schedule` parent，以原 `run_hour`/`run_minute` 建一列 child，`enabled=true`，並複製 parent 的 `last_run_date`/`last_run_at`/`last_run_status`/`updated_at`。如此部署當天若原排程已跑過，新 child 仍被 guard，不會重跑。
  - 採 expand/contract：搬移後仍保留 parent 既有的 `run_hour`/`run_minute` NOT NULL、`ck_commodity_export_schedule_hour`/`ck_commodity_export_schedule_minute` CHECK 與 `last_run_date`（nullable）三個 legacy 欄位作 rollback shadow；`range_months`／`output_subpath`／Drive 四欄完全不動。新程式不再以 parent 的 `run_hour`/`run_minute`/`last_run_date` 判斷 due，但每次設定儲存與 due runner 執行時都要 dual-write（見 330.4／330.5）。真正 drop legacy 欄位留待後續獨立 Requirement／changeset。
  - SQL 用 `CREATE TABLE IF NOT EXISTS`／`CREATE INDEX IF NOT EXISTS`／`INSERT ... ON CONFLICT DO NOTHING` 保護重複建立；保留 Liquibase 預設逐 statement 切分，不需要 `DO $$` 或 `splitStatements:false`（`v1.61.0` 建 parent、`v1.76.0` 加 Drive 欄位皆已在 master 中固定先套用，本 migration 可直接讀取既有欄位）。在 `db.changelog-master.yaml` 最尾端 include。**實作當下務必重新以 `docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5;"` 確認 v1.103.0 仍未被其他並行 worktree 佔用**；若已佔用，檔名、changeset id、本任務檔與 `spec/design.md`／`spec/requirements.md` 的 Requirement 72 內文同步改號。
- [x] **330.2 Entity／Repository：**
  - `CommodityExportSchedule`（`backend/src/main/java/com/steven/assets/model/CommodityExportSchedule.java`）保留 `runHour`/`runMinute`/`lastRunDate`，但於 Javadoc 明確標記為 rollback shadow、不得作新 scheduler 的 due 來源；保留 parent `lastRunAt`/`lastRunStatus`（最近一次任一 scheduled/run-now 摘要），並新增 `@OneToMany(mappedBy="schedule", cascade=CascadeType.ALL, orphanRemoval=true)` 的 `List<CommodityExportScheduleTime> times`。排序可用 `@OrderBy("runHour ASC, runMinute ASC, id ASC")` 或 repository 明確排序；提供 `addTime(CommodityExportScheduleTime)` helper 維持雙向關係（同時設定 `time.schedule = this`）。
  - 新增 `CommodityExportScheduleTime`（新檔 `backend/src/main/java/com/steven/assets/model/CommodityExportScheduleTime.java`）：`@ManyToOne` 指回 parent（`schedule_id`）、`runHour`、`runMinute`、`enabled`（預設 `true`）、`lastRunDate`、`lastRunAt`、`lastRunStatus`、`updatedAt`。它**不**另存 `owner_user_id`——owner 一律由 parent 取得；parent 關聯不可讓 HTTP 以任意 schedule id 繞過 owner filter（即 child 沒有獨立的 owner-scoped repository 查詢入口）。**Lombok 註解比照既有 `ExportScheduleTime.java`：用 `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`，不用 `@Data`**——parent 的 `CommodityExportSchedule` 是 `@Data`，若 child 也用 `@Data`，雙向 `@ManyToOne`／`@OneToMany` 關聯會讓兩邊自動產生的 `toString()`/`equals()`/`hashCode()` 互相遞迴呼叫對方，導致 `StackOverflowError`。
  - `CommodityExportScheduleRepository` 的 `findByOwnerUserId(Long)` 與覆寫的 `findAll()` **一律加 `@EntityGraph(attributePaths = "times")`**（比照既有 `ExportScheduleSettingRepository` 的做法），讓 `times` 隨查詢一次 fetch join 進來，不依賴 session 是否還開著。**不要**用「service 方法補 `@Transactional(readOnly=true)`」這種替代方案：背景 due runner（330.5 的 `runDueExports()`）會在同一個掃描迴圈裡持續呼叫 `settingRepo.save(child 所屬的 parent)` 寫回每個到點 child 的 guard／狀態，若這個方法被標成 `readOnly=true`，Hibernate 的 flush mode 常態性不自動 flush，這些寫入可能在 commit 時完全沒有真的落地——guard 寫入靜默失敗，命中時間的排程會整天每分鐘重跑。`runDueExports()` 應是一般（非 readOnly）的 `@Transactional`。HTTP 入口（`getForCurrentUser`/`updateForCurrentUser`）必須先由 `findByOwnerUserId` 限縮本人再讀 children。
- [x] **330.3 DTO 契約（`backend/src/main/java/com/steven/assets/dto/CommodityExportDto.java`）：**
  - `SettingResponse` 拿掉 top-level `runHour`/`runMinute`，改為 `{enabled,outputSubpath,rangeMonths,times,lastRunAt,lastRunStatus,baseDir,gdriveEnabled,gdriveSubpath,gdriveRemote,gdriveLastRunAt,gdriveLastStatus,gdriveSelfCheckWarning}`；`times` 為依 `(runHour,runMinute,id)` 排序的清單，每項 `{id,runHour,runMinute,enabled,lastRunAt,lastRunStatus}`（`lastRunAt` 格式沿用既有 `yyyy-MM-dd HH:mm:ss`）。`rangeMonths`／Drive 相關欄位維持不變、不進 `times[]`。
  - `SettingRequest` 改為 `{enabled,outputSubpath,rangeMonths,times,gdriveEnabled,gdriveSubpath}`；`times` 為 `[{runHour,runMinute,enabled}]`，**不接受 client 傳入 id**，整包依 `(runHour,runMinute)` 取代語意（比對邏輯見 330.4）。
  - `RunNowResponse` 七個既有欄位（`path,sizeBytes,gdrivePath,gdriveStatus,jsonPath,jsonSizeBytes,jsonGdrivePath`）完全不變。
  - BFF（`bff/src/main/java/com/steven/assets/bff/commodityprice/CommodityPriceBffController.java`）四支 passthrough（`schedule` GET/PUT、`run-now` POST、`browse` GET）維持 `Map<String,Object>`／原樣轉發，不新增鏡像 DTO——確保新增的 `times[]` 欄位不會因為 BFF 端寫死欄位清單而被漏轉。
- [x] **330.4 設定讀寫（`CommodityExportScheduleService.getForCurrentUser`/`updateForCurrentUser`）：**
  - `getForCurrentUser()`：DB 無 parent 列時回一個 transient parent（不寫 DB）＋一列 transient child `{runHour:8,runMinute:0,enabled:true,lastRunAt:null,lastRunStatus:null}`，其餘欄位維持現行預設（`rangeMonths` 顯示邏輯不變）。
  - `updateForCurrentUser(SettingRequest req)` 加 `@Transactional`。驗證順序：先驗 `times` 非 null 且非空（否則 400「至少需要一個執行時間」）；逐項驗證 `runHour∈[0,23]`、`runMinute∈[0,59]`；同一 request 內 `(runHour,runMinute)` 不得重複；若 `req.enabled()==true`，`times` 中至少一項 `enabled==true`；`rangeMonths` 驗證維持現行邏輯（`null` 或 `1..120`，否則 400）；`outputSubpath` 正規化與 `resolveDir` 跳脫檢查維持現行邏輯；Drive 兩欄維持現行 `GdriveOutputSupport.resolveUpdate` 語意（`null`＝未送出＝不變更、啟用時必填、僅主要管理者可啟用）。全部驗證通過才可異動 DB。
  - 以 `(runHour,runMinute)` 對映既有 child：相同時分的新列必須沿用既有 entity（保留其 `id`／`lastRunDate`／`lastRunAt`／`lastRunStatus`），只更新 `enabled`；request 中不在既有清單裡的新時分建立新 child（guard 三欄為 null）；既有 child 不在本次 request 內的，透過 `orphanRemoval` 刪除。**不得因為單純修改資料夾、`rangeMonths` 或 Drive 設定而清除任何 child 的當日 guard**。
  - parent 的 `enabled`/`outputSubpath`/`rangeMonths`/Drive 兩欄/`updatedAt` 照更新；儲存後依「最早啟用 child；若全部停用則最早 child（依 runHour/runMinute 排序）」為 rollback representative，呼叫一支共用的 `syncRollbackRepresentative(parent)`（比照 `ExportScheduleService.syncRollbackRepresentative()`），把 parent 的 `runHour`/`runMinute`/`lastRunDate` **三欄一起**同步為該 representative child 目前的值（含 `lastRunDate`，即使它是 `null`）。**這一步在存檔當下就要做，不可延後到 330.5 該 child 實際執行時才做**：若使用者把 representative 從一個「今天已跑過」的 child 換成另一個「今天還沒跑過」的 child，parent 的 `lastRunDate` 必須立刻反映新 representative 目前的真實狀態（通常是 `null`），否則若此時把服務 rollback 回本次改動之前的舊單一時間版本，舊版只看得到 parent 三欄，會誤判「新代表時間今天已經跑過」而整天不再觸發，即使該時間其實從未真正執行——這正是 expand/contract＋rollback representative 這整套機制要避免的情境。330.5 該 representative child 實際到點執行時，同一支 `syncRollbackRepresentative(parent)` 會被再呼叫一次，把 parent `lastRunDate` 更新成該次執行的今天日期；兩個呼叫點共用同一支方法、不得各自重複實作同步邏輯。回傳 `toResponse(saved)`，其 `times[]` 依排序輸出。
- [x] **330.5 背景多時間 due runner（`tick`/`selfHealOnStartup`/`runDueExports`）：**
  - 保留且只保留既有一個 `@Scheduled(cron="0 * * * * *", zone="Asia/Taipei")` 與一個 `@EventListener(ApplicationReadyEvent.class)`；兩者都須先走同一個 `tryRunDueExports()`／`AtomicBoolean.compareAndSet(false,true)` 入口才可呼叫實際 runner 邏輯，防止啟動瞬間與跨分鐘 tick 重入（沿用既有 `ticking` 欄位語意，只是把守門邏輯抽成共用入口）。
  - `runDueExports()` 標一般（非 `readOnly`）的 `@Transactional`——它要在同一個掃描迴圈裡持續呼叫 `settingRepo.save(...)` 寫回每個到點 child 的 guard，`readOnly=true` 會讓這些寫入有靜默不落地的風險（見 330.2）。改為：對每個 `settingRepo.findAll()` 的 parent，先驗 `Boolean.TRUE.equals(parent.getEnabled())`，再逐一遍歷其 `times`（依 runHour/runMinute 排序）；對每個 child，條件為 `child.enabled==true` 且 `LocalDate.now(TW_ZONE)` 不等於 `child.lastRunDate` 且 `now >= LocalTime.of(child.runHour, child.runMinute)`（沿用「`>=` 而非精確相等」的既有語意，避免排程執行緒被卡住跨分鐘導致靜默漏跑）。
  - 每個到點 child 呼叫 330.6 所述的 `export(...)`（每 child 各自查一次 `commodityPricesDoc`，因為執行當下要用「當下」重新計算的滾動區間）。child 執行完成（成功或失敗）**只更新該 child 的 `lastRunDate`/`lastRunAt`/`lastRunStatus`**，並把同一結果同步為 parent 的 `lastRunAt`/`lastRunStatus`（含既有 `applyGdriveStatus`/`syncGdrive` 呼叫，寫入 parent 的 Drive 兩欄）；若該 child 剛好是目前的 rollback representative（即與 parent 目前 `runHour`/`runMinute` 相同的那個 child），額外呼叫 330.4 所述的同一支 `syncRollbackRepresentative(parent)`，把 parent `lastRunDate` 更新成這次執行的今天日期。
  - 單一 child 發生 render／寫檔／Drive／資料錯誤只記錄該 child 的 `lastRunStatus` 並繼續下一個到點 child、下一個 parent；不得中斷同一 owner 其餘到點 child 或其他 owner。開機自癒（`selfHealOnStartup`）與 `tick` 共用同一段 `runDueExports()` 邏輯：若啟動時有兩個時間都已過期且皆未 guard，必須依序（依時分排序）各自嘗試一次，不得只跑第一個就 return，也不得讓「parent 已有 lastRunDate」誤擋第二個 child。
- [x] **330.6 run-now（`runNowForCurrentUser`）：**
  - 忽略 parent 與所有 child 的 `enabled`，沿用目前共用路徑（`outputSubpath`）、`rangeMonths`（執行當下重新計算滾動起訖日）與 Drive 設定立即產出雙格式檔，維持既有 `RunNowResponse` 七欄語意不變。
  - 已有 parent 時，**不得新增、修改或刪除任何既有 child**（不碰 `times` 集合）；只更新 parent 的 `lastRunAt`/`lastRunStatus` 與 Drive 兩欄（沿用既有 `applyGdriveStatus`/`syncGdrive` 呼叫）。
  - 若 DB 尚無 parent（`settingRepo.findByOwnerUserId(ownerId)` 為空），延續現行「run-now 會保存設定／結果」語意：建立一個 `enabled=false` 的 parent，並透過 `addTime` 建立一列 `runHour=8,runMinute=0,enabled=true` 的 child（該 child 的 `lastRunDate`/`lastRunAt`/`lastRunStatus` 皆為 null，本次 run-now **不消耗**它），parent 的 `runHour`/`runMinute` legacy shadow 同步為 `8`/`0`。
- [x] **330.7 前端時間清單（`frontend/src/views/CommodityPriceView.vue`）：**
  - 移除單值響應式 `scheduleTime`（目前 `const scheduleTime = ref('08:00')`），新增帶 client-only key 的 `scheduleTimes`（結構比照 `AssetHistoryView.vue`：`ref([{ key: 1, value: '08:00', enabled: true, lastRunAt: null, lastRunStatus: null }])` + `let nextScheduleTimeKey = 2`）。
  - 樣板：把現有「每日執行時間」`el-form-item` 內的單一 `el-time-picker`（模板第 86–89 行附近）換成 `v-for="item in scheduleTimes"` 的列表，每列含 `el-time-picker`（`v-model="item.value"` `format="HH:mm"` `value-format="HH:mm"`）、`el-switch v-model="item.enabled"`（啟用/停用文字）、`el-button text type="danger" :disabled="scheduleTimes.length===1"` 的移除鈕、以及該列 `lastRunAt`/`lastRunStatus` 的小字提示；下方加「＋ 新增時間」`el-button text type="primary"`。「匯出範圍」「輸出資料夾」「同步 Google Drive」三個 `el-form-item` 與其邏輯完全不動。
  - `loadSchedule()` 改為：讀 `schedule.rangeMonths`/`outputSubpath`/`lastRunAt`/`lastRunStatus`/`baseDir`/Drive 欄位邏輯不變；若 `Array.isArray(s.times) && s.times.length`，依 `(runHour,runMinute)` 排序後映成 `scheduleTimes`（`value` 用 `padStart(2,'0')` 組 `HH:mm`）；若 `!Array.isArray(s.times)`（舊後端 rolling upgrade fallback），用 top-level `s.runHour`/`s.runMinute` 映一列；若 `Array.isArray(s.times)` 但長度為 0，視為異常，`ElMessage.error('排程時間資料異常，請重新載入後再試')`、不靜默補預設值。
  - `saveSchedule()`：Drive 前置檢查（開了同步卻沒選資料夾要擋）維持不變。新增：`if (!scheduleTimes.value.length) { ElMessage.warning('至少需要一個執行時間'); return }`；逐項解析 `item.value` 為 `h`/`m`，非合法整數或超出範圍時 `ElMessage.warning('請輸入有效的執行時間'); return`；用 `Set` 擋同一次儲存內時分重複（`ElMessage.warning('執行時間不可重複'); return`）；`if (schedule.enabled && !times.some(t => t.enabled)) { ElMessage.warning('啟用排程時至少需啟用一個時間'); return }`。全部通過後呼叫 `bffApi.commodityPrice.updateExportSchedule({enabled, gdriveEnabled, gdriveSubpath, rangeMonths, outputSubpath, times})`（**移除**目前送出的 top-level `runHour`/`runMinute`）；成功後呼叫 `loadSchedule()` 重建 `scheduleTimes`（不得 client 端手動 merge），並沿用既有 `showGdriveSelfCheckWarning(s.gdriveSelfCheckWarning)`。
  - 新增 `addScheduleTime()`（push 一列預設 `08:00 enabled:true`）與 `removeScheduleTime(key)`（`scheduleTimes.value.length>1` 才允許移除）兩個函式，簽名與行為比照 `AssetHistoryView.vue` 同名函式。
  - `schedule-hint` 提示文字（模板第 139–144 行附近）中的「每日於指定時間匯出油價金價為…」改為「於下列每個啟用時間更新同日最新檔，較晚時段會覆寫同名檔為較新內容」，其餘關於檔名格式、雙格式、滾動範圍的說明維持不變。
  - `frontend/src/api/index.js` 的 `commodityPrice` 命名空間本身**不需新增/修改端點**（`getExportSchedule`/`updateExportSchedule` 既有兩支即可承載新的 `times[]` payload/response），僅上述 view 內的呼叫參數改變。
- [x] **330.8 排程列表與 spec 文件同步：**
  - `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的 `JOBS` **不增減項目**，只把「油價金價匯出」那一筆的描述文字，從「每分鐘檢查各使用者的油價金價自動匯出設定，命中執行時間即同時產出 JSON 與 Excel 兩份（主檔名相同）到指定目錄（Requirement 41）；輸出含 Google Drive 同步（若已啟用）」改為「每分鐘檢查頁面設定的多個每日時間，命中每個啟用時間即同時產出 JSON 與 Excel 兩份（主檔名相同）到指定目錄（Requirement 72）；輸出含 Google Drive 同步（若已啟用）」；annotation 數與清單總筆數不變。
  - 本任務完成、驗證通過後，回到 `spec/requirements.md` 的 Requirement 72 與 `spec/design.md` 對應章節勾選/更新驗收狀態（若該流程要求勾選 checkbox，見 330 checklist 本身）；**不得**把其他仍是單一時間的匯出頁（Requirement 42／37／39／45／48／49）順便改成多時間點——那些不在本任務範圍內。
- [x] **330.9 測試（新建測試類，非擴充既有）：**
  - 在 `backend/src/test/java/com/steven/assets/service/` 新建對應 `CommodityExportScheduleService` 的測試類（依 330 背景段所述命名慣例；目前不存在任何同名或涵蓋此 service 的測試檔）。至少涵蓋：
    - transient 預設（無 DB 列時 GET 回 `08:00` 啟用 child，且不寫入 DB）；
    - `times[]` 排序、整包取代（新增/移除/保留時分）、重複時分／空清單／非法時分／總啟用但全 child 停用皆丟 400 或等價驗證錯誤；
    - 相同時分再次儲存時保留既有 child 的 `lastRunDate`/`lastRunAt`/`lastRunStatus`（guard 不清）；新時分的 child guard 為 null；
    - `rangeMonths` 驗證（`null`/`1..120` 合法，超出範圍 400）不因多時間點而改變；
    - due runner：兩個到點時間都會各自執行；其中一個已 guard 不阻止另一個；其中一個 render/寫檔失敗不阻止另一個且各自記錄自己的 `lastRunStatus`；開機自癒對兩個過期時間依序補跑；`tick` 與 `selfHealOnStartup` 併發只會有一輪真正執行（CAS 生效）；
    - run-now：已有 parent 時不修改任何既有 child 的 guard/status；無 parent 時建立 disabled parent＋未消耗的 `08:00` child；
    - owner 隔離（不同 owner 的 child 互不影響）、雙格式（xlsx 有值/json 有值各自 render 失敗互不影響既有行為）與 Google Drive best-effort 行為不回歸（沿用既有 `GdriveOutputSupport` mock 慣例）。
  - 若既有 `backend/src/test/java/com/steven/assets/controller/`（或等價）目錄有 `CommodityExportController` 相關測試則一併更新 request/response 契約；若不存在則新建，涵蓋 GET/PUT/`run-now` 三端點的新 `times[]` payload。
  - `bff` 對應 passthrough 測試（若既有）需確認回應 body 不因 BFF 端寫死欄位清單而遺漏新的 `times[]`；若無既有測試檔可新建一支簡單驗證 passthrough 不裁切欄位。
  - Frontend：若專案對 view 有既有靜態測試慣例，補上 add/remove 時間點、`times[]` payload 組裝與重複驗證的測試；若無此類測試慣例，至少確保 `npm run build` 通過（見「驗證」段）。
- [x] **330.10 Docker 實機驗證與清理：**
  - 從本 worktree 對 `business-services`、`bff`、`frontend` 執行 `--no-cache` 重建並 `--force-recreate`，等待三者皆 healthy；用 `docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog WHERE id LIKE 'v1.103%';"` 確認 `v1.103.0` changeset 恰為一筆 `EXECUTED`，並用 `\d commodity_export_schedule_time` 確認 child PK、FK `ON DELETE CASCADE`、`UNIQUE(schedule_id,run_hour,run_minute)`、hour/minute CHECK 均存在；`\d commodity_export_schedule` 確認 parent legacy 三欄與既有 nullability 未被移除或放寬。
  - 使用實際登入 owner（史帝芬／管理者帳號）：**先讀出並記錄目前設定**（`GET /api/bff/commodity-price/export/schedule` 回應原文，供驗證後還原），再儲存至少兩個時間點（例如以目前時間前後一分鐘的受控等價時間，或 `00:00`／`00:01`／既有 `09:20` 三列），GET 讀回確認排序、per-time `lastRunAt`/`lastRunStatus` 與 rollback representative 同步正確。
  - 以受控 DB guard／到點案例（可直接對指定 child 的 `last_run_date` 下 SQL 或等待實際到點）證明同日兩個時間點都會各自執行、產出當日最新兩份格式檔、且第二輪覆寫同名檔。
  - 驗證 run-now 呼叫前後所有既有 child 的 `last_run_date`/`last_run_at`/`last_run_status` 完全相同（逐欄比對，非僅目測）。
  - **完成後務必還原**：把設定改回驗證前記錄的原始值（含原本的單一時間、`rangeMonths`、輸出資料夾、Drive 設定），並確認 DB 中不殘留驗證用的多餘 child 列或測試輸出檔案；不得留下未經使用者要求的新啟用時間點。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
for name in asset-business-services asset-bff; do
  for i in $(seq 1 60); do
    state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || true)
    [ "$state" = healthy ] && break
    sleep 2
  done
  test "$(docker inspect --format '{{.State.Health.Status}}' "$name")" = healthy
done
test "$(docker inspect --format '{{.State.Status}}' asset-frontend)" = running
docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog WHERE id LIKE 'v1.103%';"
docker exec asset-postgres psql -U assets -d assets -c "\d commodity_export_schedule_time"
```

## 完成報告

### 實際變更檔案

**新增**
- `backend/src/main/java/com/steven/assets/model/CommodityExportScheduleTime.java`：child entity，`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`（**刻意不用 `@Data`**，避免與 parent 的 `@Data` 在雙向關聯下 `toString`/`equals`/`hashCode` 互相遞迴）；不存 `owner_user_id`，owner 只由 parent 取得。
- `backend/src/main/resources/db/changelog/changes/v1.103.0-commodity-export-schedule-multi-time.sql`：建 child 表＋搬移既有單一時間為一列 child（複製 guard／status）。
- `backend/src/test/java/com/steven/assets/service/CommodityExportScheduleServiceTest.java`（27 個測試）
- `backend/src/test/java/com/steven/assets/controller/CommodityExportControllerTest.java`（3 個測試）
- `bff/src/test/java/com/steven/assets/bff/commodityprice/CommodityPriceBffScheduleTest.java`（3 個測試，驗 passthrough 不裁切 `times[]`）

**異動**
- `CommodityExportSchedule.java`：加 `@OneToMany(cascade=ALL, orphanRemoval=true)` times ＋ `addTime` helper；legacy 三欄 Javadoc 標記為 rollback shadow。
- `CommodityExportDto.java`：`SettingResponse` 移除 top-level `runHour/runMinute`、加排序後 `times[]`；`SettingRequest` 改收 `times[]`；`RunNowResponse` 七欄不變。
- `CommodityExportScheduleRepository.java`：`findByOwnerUserId`／覆寫的 `findAll()` 皆加 `@EntityGraph(attributePaths = "times")`。
- `CommodityExportScheduleService.java`：整包取代＋依 `(hour,minute)` 保留既有 child guard；`runDueExports` 先驗 parent 再逐 child；新增 `syncRollbackRepresentative()`（三處呼叫：兩處存檔路徑、一處 representative 執行後）。
- `SchedulePublicBffController.java`：「油價金價匯出」描述改為「每分鐘檢查頁面設定的多個每日時間…（Requirement 72）」，項目數不變。
- `CommodityPriceView.vue`：單值 `scheduleTime` → 可增刪的 `scheduleTimes` 列表。
- `db.changelog-master.yaml`：尾端註冊 v1.103.0。

### 自動驗證（2026-08-15）

- **backend：952/952 全數通過**（`Failures: 0, Errors: 0`）。注意須帶 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`；不帶會有 568 個 Mockito/ByteBuddy 環境錯誤（與本次變更無關，且**不可**改用 `-DargLine`，那會覆蓋時區設定）。
- **BFF：76/76 全數通過**。
- **frontend production build：通過**（`✓ built in 4.63s`）。
- `scripts/spec-check.sh`：`BLOCK 0 / CHECK 0`。

### Migration runtime

`databasechangelog` 中 `v1.103.0-commodity-export-schedule-multi-time` 恰為一筆 `EXECUTED`（orderexecuted 136）。實機 `\d commodity_export_schedule_time` 確認：PK、FK `ON DELETE CASCADE`、`uq_commodity_export_schedule_time UNIQUE(schedule_id,run_hour,run_minute)`、hour 0–23／minute 0–59 CHECK、`idx_commodity_export_schedule_time_schedule` index 均存在。`\d commodity_export_schedule` 確認 parent legacy 三欄與既有 nullability 未被移除或放寬。原單一時間 `09:20` 與其 guard（`last_run_date=2026-08-15`、`last_run_at=09:20:06.851659`、status）已無損搬成 child id=1。

### Docker runtime 驗證

三服務 `--no-cache` 重建並 `--force-recreate`（images：business 13:16、bff 13:21、frontend 13:24），business/bff healthy、frontend running。確認非 stale image：business jar 內含 `CommodityExportScheduleTime.class`，frontend bundle 含新 UI 字串（`新增時間`／`執行時間不可重複`）。

以實際 owner（id=1）驗證，**驗證前先備份 parent／child 全欄至 TSV**：

1. **GET 契約**：回應含依時分排序的 `times[]`（`{id,runHour,runMinute,enabled,lastRunAt,lastRunStatus}`），無 top-level `runHour/runMinute`；`rangeMonths`／`outputSubpath`／Drive 四欄／`baseDir` 維持 parent-only。
2. **PUT 整包取代＋guard 保留**：刻意亂序送 `23:30／09:20／00:01(停用)`，回應依時分排序為 `00:01／09:20／23:30`；既有 `09:20`（id=1）的 `last_run_date`／`last_run_at`／`last_run_status` 完整保留，新時分（id=4、5）guard 皆為 null；同時變更了 `outputSubpath`／`rangeMonths`／Drive 也未清除任何 guard。parent rollback shadow 同步為**最早啟用** child `09:20`（正確跳過停用的 `00:01`）。
3. **同日多時段各自執行（核心驗收）**：設 `00:00`／`00:02` 兩列皆啟用、guard 皆 null，等一次每分鐘 tick——`13:33:00.038561`（id=6）與 `13:33:00.058189`（id=7）**兩列各自執行一次**、各自寫入自己的當日 guard，證明第一列的 guard 不會擋住第二列；parent 摘要指向最後完成的一輪（13:33:00.058189）。實際產出 `油價金價_1_20260815.xlsx`（4542 bytes）與 `.json`（4852 bytes），第二輪覆寫同名檔為較新內容（目錄內僅一份）。
4. **run-now 不消耗 child guard**：run-now 前後對兩列 child 的 `id/時分/last_run_date/last_run_at/last_run_status(md5)` 逐欄比對，**完全相同**；run-now 只更新 parent 摘要並回傳既有七欄 response。
5. **rollback representative 換人時 guard 立即同步（Task 330.4 的 critical 行為）**：把 times 整包換成單一個從未執行過的 `23:50` 後，parent 立刻由 `0/0/last_run_date=2026-08-15` 同步為 `23/50/last_run_date=NULL`——確認不會讓 rollback 至舊 image 時誤判「代表時間今天已跑過」而整天不觸發。orphanRemoval 亦正確刪除未列於 request 的舊 child。

### 清理與還原

驗證全程使用測試子目錄 `Project/SRPP/data/t330_verify` 並暫時關閉 Google Drive 同步，**未寫入使用者真實輸出目錄、未上傳任何檔案至雲端硬碟**。驗證後由 TSV 備份完整還原 parent 與 child（`enabled=true`、`09:20`、`Project/SRPP/data/input`、`range_months=60`、Drive 啟用＋`投資理財/資產管理`、原 guard 與 status 全部復原），測試輸出目錄已刪除，`input/` 內既有歷史檔案完好無損。DB 僅保留原 child id=1，未殘留任何測試排程。

### 與原計畫的偏差

無實質偏差。兩點補充說明：
- 本次 `@Scheduled` 維持恰一個（每分鐘 poll）＋一個 `@EventListener`，未新增 annotation，故排程列表僅更新既有一筆描述、總筆數不變，與 330.8 一致。
- 驗證用的第 5 項（representative 換人）是 spec 審查階段標記的 critical 風險點，任務檔原本只要求測 guard 保留與多時段執行；實作後額外做了這項端對端驗證以確認 rollback 安全性，屬加驗而非偏離。
