# [t511] 歷年資產及已實現損益改為多筆獨立匯出排程

**對應 Requirements:** Requirements 34／39／69／73（兩頁匯出排程獨立化）
**前置任務:** 無
**Liquibase changeset:** v1.157.0-independent-asset-gain-export-schedules.sql

## 背景

使用者要求比照交易紀錄，兩頁原共用目錄的多時間點改成各自獨立可增刪的排程。原模型每 owner 唯一 parent ＋ children；本次轉成多筆 parent，每列一個時間，不回退舊 image。

## 要做什麼

- [x] 511.1 實作以下完整契約、migration 與 owner-scoped API，更新既有受影響測試而非刪除有效覆蓋。
- [x] 511.2 兩頁 UI 比照交易紀錄提供完整獨立排程操作與草稿隔離。
- [x] 511.3 通過獨立 spec/arch 審查、測試、容器驗收、schema 重產及 no-ff merge/push；main 重建確認。

### 需求契約

Task 511 修訂：歷年資產與已實現損益多筆獨立匯出排程（Requirements 34／39／69／73）

**User Story:** 作為使用者，我希望「歷年資產管理」及「已實現損益」的匯出設定比照「交易紀錄」，可以新增多筆各自獨立的每日排程，分別編輯時間、名稱、輸出目錄與 Google Drive，並個別刪除或立即匯出。

**Acceptance Criteria:**

- [ ] 每頁每位 owner 最多 10 筆獨立排程，每筆只有一個每日時分、enabled、選填名稱（trim 後 1–20 字，只准中英數、底線、連字號、空白）、本機子路徑及 Drive 設定，並各自保存當日 guard 與執行狀態。GET 零筆回空清單且不建立預設。新增預設停用，只有儲存成功才建立，取消不留資料；儲存失敗保留草稿；所有變更以伺服器回傳完整排序清單取代 UI 資料，不合併舊列。
- [ ] 新的每頁清單支援新增／編輯／刪除（須確認）／立即匯出。名稱、啟用狀態、時間、本機目錄、上次執行與 Drive 狀態可辨識；編輯與資料夾選擇使用隔離草稿；操作進行中阻擋重複請求及互相干擾的操作，錯誤顯示而非當成空清單。
- [ ] 新資產 API 為 `/api/export-schedule/schedules`，新損益 API 為 `/api/realized-gains/export/schedules`：GET 清單、POST 新增、PUT `/{id}` 修改、DELETE `/{id}` 刪除、POST `/{id}/run-now`。對應 BFF 分別為 `/api/bff/asset-history/export-schedules` 及 `/api/bff/realized-gain/export/schedules`，沿用各頁自己的 API client。不存在或他人 id 一律 404、無認證 401；所有修改須 server 驗證。舊 singleton 設定與無 id run-now HTTP 路由退役，回 410 且無寫入／產檔／Drive 副作用，提示重新整理；目錄 browse 路由保持。
- [ ] 一個舊 child 轉一筆排程，按時分與 id 排序後第一筆沿用原 parent id；其他筆配置新 id。enabled = 原 parent.enabled AND child.enabled；繼承原輸出目錄／Drive 設定及 child guard/status，不因升級重跑已執行時間。無 child 的原 parent 保留原時分、enabled、guard/status。名稱預設 null，空名沿用既有檔名，因而原多時間同日覆寫模式不變。舊 parent 的共享 Drive 執行結果不可假冒逐筆結果，遷移全部清為 null；設定保留。遷移後超過 10 筆須整體拒絕，不得截斷。移除 owner UNIQUE 及兩頁時間子表，parent 時分／guard 成為唯一事實；不支援回退至依賴舊子表的 image。
- [ ] 無名稱檔名仍為 `資產總覽_{ownerId}_yyyyMMdd`、`已實現損益_{ownerId}_yyyyMMdd`；有名稱則在 ownerId 後加 `_{name}`，同一 baseName 產 `.xlsx` 與 `.json`。同 owner、日期、目錄與名稱相同會覆寫，UI 明示可用不同名稱或目錄區分。同名合法，不額外默默加 id 或改舊下游檔名。
- [ ] 每分鐘與 startup 共用同一 AtomicBoolean 防重入，按時分／id 逐列補跑到點未 guard 的啟用排程；成功或失敗都更新那筆當日 guard，一筆失敗不阻斷下一筆。run-now 指定那筆，忽略 enabled，但不動其當日 guard；不得建立不存在的排程。設定編輯保留 guard，異步結果寫回只准 exact id＋owner、仍相同的 captured 設定；已刪、改時分／目錄／名稱／Drive／enabled 者不寫回舊結果，不 resurrect。DB 短交易捕捉不可變輸入後在交易外產檔，owner 層鎖保護包括第一筆建立在內的上限與 CRUD 併發；Compose 單 replica 前提維持。
- [ ] 資產仍使用 `ExcelExportService.liveAssetsDoc*`，損益仍使用 `realizedGainsDoc*`，背景指定 owner；共用 renderer／DualFormatExportWriter，單次 doc 雙格式、local-first、Drive best-effort、configured-admin 限制、啟用自檢警告不入庫均維持。存檔與產檔皆防路徑跳脫，產檔前重驗實體路徑防 symlink；不涉及券商交易、9090 或 SRPP 規則／檔案。
- [ ] 驗證包含 PostgreSQL migration 多時間／停用／guard／無 child／冪等／超限 rollback、owner CRUD/404/10 筆併發、逐列 due 與 run-now guard、晚到結果不覆蓋編輯與刪除、名稱檔名與路徑防護、雙格式／Drive；BFF 新路由及舊路由 410；兩頁 build 與互動；重建 business-services／bff／frontend 容器實測並重產 db/schema.sql，確認 schema drift 為零後 no-ff merge/push，從 main 重建再讀回。

### 設計契約

Task 511：兩頁獨立匯出排程設計（取代 Requirements 69／73 的 children 模型）

兩個功能各維持自己的 parent table／service／controller 與 BFF，不合併業務表。`export_schedule_setting` 與 `realized_gain_export_schedule` 各新增 nullable `name varchar(20)`、移除 owner unique、加 owner index；既有 run_hour/run_minute/last_run_date 成為唯一時分與 guard，移除兩個時間 child table 及 JPA mapping。DTO 一律 record。使用獨立不可變 capture，保留 exact settings 比對與短交易邊界，不更改 commodity／exchange-rate 共用 execution port 的行為。

### HTTP 契約

| 功能 | business base | 頁面 BFF base |
|---|---|---|
| 最新資產 | `/api/export-schedule/schedules` | `/api/bff/asset-history/export-schedules` |
| 已實現損益 | `/api/realized-gains/export/schedules` | `/api/bff/realized-gain/export/schedules` |

兩組都提供 GET/POST base、PUT/DELETE `/{id}`、POST `/{id}/run-now`。POST/PUT body 為 `{name,enabled,runHour,runMinute,outputSubpath,gdriveEnabled,gdriveSubpath}`；時分必填且整數範圍 0–23/0–59，enabled 未送預設 false。名稱 null／空白正規化 null，其餘只准中英數、底線、連字號與空白且最多20字；路徑空白維持既有 input 預設；Drive 兩欄 null＝保留舊值，create 的原值為 false/null。存檔不改 last-run guard/status。

GET 與三種 CRUD 回 `{baseDir,gdriveRemote,schedules:[{id,name,enabled,runHour,runMinute,outputSubpath,lastRunAt,lastRunStatus,gdriveEnabled,gdriveSubpath,gdriveLastRunAt,gdriveLastStatus}],gdriveSelfCheckWarning}`，按 `(runHour,runMinute,id)` 排序，時間字串沿用台北 `yyyy-MM-dd HH:mm:ss`，無結果 null。run-now 回既有七欄 `{path,sizeBytes,gdrivePath,gdriveStatus,jsonPath,jsonSizeBytes,jsonGdrivePath}`，未成功產出的路徑 null。400 為非法欄位／第11筆、401 無認證、403 未獲 Drive 權限、404 他人或未知 id；舊 singleton GET/PUT 及無 id run-now 為410（提示重新整理、零副作用），共用 browse 仍可用。

owner namespace advisory transaction lock（兩功能不同 namespace）序列化 create/update/delete，含無首筆時仍能鎖；id＋owner row lock 取得 fresh entity，短 REQUIRES_NEW 交易只 capture 或寫回，產 doc/render/local/Drive 在交易外。capture 含 name/enabled/time/path/Drive，complete 比對全組設定及 id/owner，不同就丟棄結果；已刪不重建。手動 capture attemptDate=null、due capture attemptDate=今天；只有 due complete 改 guard。每筆產檔與寫回例外隔離。排程列表維持原兩個 poll，描述改成多筆獨立排程。

### 資料遷移與檔名

Liquibase `changes/v1.157.0-independent-asset-gain-export-schedules.sql` 以單一 PostgreSQL DO transaction、splitStatements:false 註冊 master 末尾。兩張 child table 在同 transaction 中 split：每 parent 按 child 時分/id 排序，第一筆保留 parent id，其他用序列配置；在更動第一筆前保留原 parent 設定與 enabled 供其他列。每筆 enabled 是原 parent 與 child 的 AND，local guard/status 取 child；無 child parent 原樣保留。所有 name=null，複製 output/Drive 設定，Drive last-run 清 null（共享歷史無法歸屬）。先檢查每 owner 每功能 split 後最多10筆，超限 RAISE rollback；只有成功 split 後 drop child tables。以 child table 是否存在作遷移 guard，重執行不再 split、不清新狀態；約束／欄位／index 使用冪等防護。保留既有 hour/minute checks、not null、owner FK 狀態，不新增金融分類。需重產 schema；舊 image 不相容，回退需受控 DB rollback，不能直接跑舊 image。

baseName 無 name 時逐字沿用既有名稱；有 name 以 `_{name}` 插在 ownerId 後，不含 schedule id。同名同目錄同日覆寫是明示契約，split 的舊排程保留 null 名稱以維持下游來源。兩頁草稿對話框採 TransactionView 的操作習慣，新增先開 disabled draft、儲存才 POST；每列只一個 time picker，顯示本機與 Drive 狀態。資料夾 picker 只改當前 draft，不回寫清單；取消丟棄。loading/error/empty 分開，列表 mutation 期間統一防重入。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
cd frontend
npm test
npm run build
```

新增 PostgreSQL migration 測試應在隔離 schema／資料庫驗證雙頁 split、guard、停用、無 child、超限整體 rollback、重執行。service 測試涵蓋 owner 404、CRUD 上限與併發、due 單筆失敗隔離、run-now 不改 guard、stale complete、雙格式/Drive/name/path。BFF 測試新路由與410無副作用。透過實際容器登入兩頁驗證新增／編輯取消／刪除／獨立目錄，驗證用 disabled schedule 且 Drive=false，測後刪除；立即產檔測試使用隔離暫存路徑且清理產物，不觸發原排程／Drive。

```bash
docker compose -p asset-management build business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
bash scripts/tests/schema-sql-drift-test.sh
```

重建前比對 main .env、不輸出 secrets。migration 後依 db/schema.sql 檔頭流程從 asset-postgres pg_dump 重產，更新表數（原117減2為115），schema drift 須0。驗收後 no-ff merge/push 並 remote readback，從乾淨 main 目錄重建三服務再次驗證。

## 完成報告

四頁實作與feature驗收完成，紀錄如下。


### 2026-10-26 驗收紀錄

- 已完成四頁獨立排程；各頁自己的 BFF/API 支援 CRUD、指定 id run-now，草稿與目錄隔離、每頁 owner 上限10筆、Drive 權限與雙格式匯出均保留。匯率／油金每筆 rangeMonths 獨立，合法自訂7個月已驗證。
- spec 兩輪獨立審查均0 findings；arch最終 critical/major/minor 均0，已記錄內容雜湊。機械檢查 BLOCK0/CHECK1，該 CHECK 是排程程式重排觸發的人工確認：原四個每分鐘poll及startup數量不變、registry只更新文案，兩次獨立審查均已核實，無新增排程。
- backend 完整3274 tests，0 failures/0 errors，2個既有具名條件略過：IndexExportScheduleServiceTest Linux檔案系統案例在mac不適用；SrppReportRepositoryPostgresTest size-probe未設啟用property。最後PG增量108 tests通過；BFF完整915 tests通過；frontend143 tests通過且production build成功。
- 完整測試另外修正3個既有test fixture：Fubon結清案例加入同bank足額MANUAL現金，驗到期後總值守恆；DailyDecisionCalculatorTest由embedded manifest canonical bytes與model原始bytes獨立驗hash取代過時硬碼；TradingRadar route fixture補Method predicate bean。沒有修改相關金融production規則、SRPP政策、Swagger或authority資源。
- feature已重建/recreate business-services、bff、frontend；main與feature有效環境、bind mounts皆MATCH。停止舊business後由新image套v1.157/v1.158，原BFF等upstream healthy後safe GET首次即恢復，再因本次BFF有改才正常recreate；三服務健康/HTTP200，公開安全GET正常。
- 遷移8表baseline逐欄比對PASS：資產/損益/匯率/油金各4/4/4/3筆，首id、時分、AND enabled、guard/status、目錄/range/Drive設定保留；無法歸屬逐筆的共享Drive執行歷史依契約清null，4張child移除。db/schema.sql已從正式PostgreSQL完整重產為113表，schema-sql-drift-test逐位元PASS。
- 真實容器API四頁CRUD、401/404/400/410、重新整理提示、有效XLSX workbook與可解析同名JSON、run-now不動guard均PASS；disabled/Drive=false測試列與隔離產物已全清，原15筆設定讀回未改。
- 真實編譯容器UI搭配全API/mock auth fixtures驗證四頁載入錯誤重試、取消零寫入、目錄草稿隔離、失敗保留、busy防重、CRUD/run皆PASS，匯率/油金range7選項和值保留PASS。正式登入UI為NOT_VERIFIED，未取cookie或建立假正式session；不得將mock互動當正式登入證據。
- 日誌與receipt：/tmp/asset-gain-511-backend-full-final.log、/tmp/asset-gain-511-pg-final.log、/tmp/asset-gain-511-bff-full-final2.log、/tmp/asset-gain-512-spec-final.txt、/tmp/asset-gain-512-before.json、/tmp/asset-export-512-ui/results.json。Git收尾採feature中文commit、main no-ff merge、push及remote-ref讀回；合併後由乾淨main重建三服務並重新驗收，最終SHA與部署證據由本session最終回報確認。
