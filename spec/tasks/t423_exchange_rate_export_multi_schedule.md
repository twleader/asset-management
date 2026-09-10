# [t423] 台幣兌美元匯出支援多個每日執行時間

**對應 Requirements:** Requirement 145（把台幣兌美元匯出的單一每日時間改為一份共用設定下的多個正規化時間點）
**前置任務:** Task 204（既有匯率匯出與單一排程）、Task 243（Google Drive）、Task 270（雙格式輸出）
**Liquibase changeset:** `v1.126.0-exchange-rate-export-schedule-multi-time.sql`

## 背景

匯率頁目前每位 owner 只有一份 `exchange_rate_export_schedule`，將唯一的
`run_hour`／`run_minute`／`last_run_date` 當成排程時點與當日 guard。使用者需要在同一天早、中、晚
刷新同一份匯率匯出檔，但輸出資料夾、滾動範圍與 Google Drive 設定仍應共用，不是建立多筆可各自指定
資料夾的獨立排程。

`db/schema.sql` 的 parent 表目前有 `owner_user_id UNIQUE`、NOT NULL 的 `run_hour`／
`run_minute`、nullable `last_run_date`／`last_run_at`／`last_run_status`、`range_months`
與四個 Drive 欄位；實機 `databasechangelog` 已到 `v1.125.0`。migration 因此採
expand/contract：保留 parent 舊欄位作 rollback shadow，新增 child 表保存真正的每時間點 guard，
避免部署當日把舊 guard 清掉而重跑。部署時必須先停止並確認舊 business-services 容器已退出，再啟動
會套 migration 的新 image；不可讓舊 image 在 child 搬移後再寫 parent guard，否則新 image 看到未更新的
child guard 會同日重跑。

匯率資料是全域公開 `exchange_rate_history`，背景產檔只需 per-owner 讀設定，讀取資料時不得手動
啟用 owner filter。每輪仍只建一次 USD 文件，再產出 `.xlsx` 與 `.json`；較晚時間覆寫當日同名
`台幣兌美元_{ownerId}_yyyyMMdd`，這是「當日最新」語意，不新增時間戳檔名。

## 要做什麼

- [ ] **423.1 migration 與 ORM：**新增 `exchange_rate_export_schedule_time`，欄位精確為 `id BIGSERIAL PK`、`schedule_id BIGINT NOT NULL REFERENCES exchange_rate_export_schedule(id) ON DELETE CASCADE`、`run_hour INT NOT NULL`、`run_minute INT NOT NULL`、`enabled BOOLEAN NOT NULL DEFAULT TRUE`、nullable `last_run_date DATE`／`last_run_at TIMESTAMP`／`last_run_status VARCHAR(500)`／`updated_at TIMESTAMP`；加 `UNIQUE(schedule_id,run_hour,run_minute)`、hour 0..23／minute 0..59 CHECK 及 `schedule_id` index。DDL 必須冪等。先查運行中 `databasechangelog`；若 `v1.126.0` 被佔用，連同檔名、changeset id、本檔與 design 一次避讓。migration 將每筆既有 parent 的時分、guard、時間、狀態與 `updated_at` 複製為一筆 `enabled=true` child；不得 drop、rename 或改變 parent legacy 三欄的 nullability。master changelog 尾端註冊新檔。migration 套用後必須依 `db/schema.sql` 檔頭的三步程序由 `asset-postgres` 重產 schema，更新表數，將 `db/schema.sql` 納入本任務，並以 `bash scripts/tests/schema-sql-drift-test.sh` 回傳 0 證明逐位元同步。rollout 必須先停止並確認舊 business-services container exited，才啟動會套 migration 的新 image；此順序要納入實機驗收，防止 child 複製後舊 image 仍寫 parent guard 而使新 image 同日重跑。
- [ ] **423.2 parent／child 關聯與讀取：**新增 `ExchangeRateExportScheduleTime` entity；child 必須為 owning `@ManyToOne(fetch=LAZY, optional=false)`／`@JoinColumn(name="schedule_id", nullable=false)`，並用 `@Getter/@Setter/@NoArgsConstructor/@AllArgsConstructor/@Builder`，不可用會令雙向關聯遞迴的 `@Data`。parent 新增 `@OneToMany(mappedBy="schedule", cascade=ALL, orphanRemoval=true)`、`@OrderBy("runHour ASC, runMinute ASC, id ASC")` 與 `@Builder.Default List<ExchangeRateExportScheduleTime> times = new ArrayList<>()`；helper 必須先 `child.setSchedule(this)` 再加入 list，禁止由 unidirectional association 另建 join table。parent legacy `runHour/runMinute/lastRunDate` Javadoc 標為 rollback shadow；new scheduler 不得使用其做 due 判斷。repository 的 owner lookup 及 scheduler `findAll()` 都用 entity graph 完整載入 times；測試 builder 建 parent 的 GET 與 run-now 路徑不會得到 null collection 或未設 FK child。
- [ ] **423.3 DTO、controller 與 BFF：**`ExchangeRateExportDto.SettingResponse` 保留 `enabled/outputSubpath/rangeMonths/lastRunAt/lastRunStatus/baseDir`、Drive 結果欄位與既有時間字串格式，再加排序的 `times`，每項為 immutable `TimeResponse(id,runHour,runMinute,enabled,lastRunAt,lastRunStatus)`；只移除 response top-level `runHour/runMinute`。`SettingRequest` 改收 `List<TimeRequest>`，child id 與 child 狀態不得由 client 寫入。GET／PUT `/api/exchange-rate-export/schedule` 路徑不變；PUT JSON 為 `{enabled,outputSubpath,rangeMonths,times:[{runHour,runMinute,enabled}],gdriveEnabled,gdriveSubpath}`。business run-now 精確為 `POST /api/exchange-rate-export/run-now`，BFF 為 `POST /api/bff/exchange-rate/export/run-now`，雙格式 `RunNowResponse` 不變；BFF 保持 `Map<String,Object>` passthrough，不能漏掉 `times[]`。
- [ ] **423.4 設定保存、預設及 representative：**`updateForCurrentUser` 在 transaction 中先驗：times 非 null／非空、hour 0..23、minute 0..59、沒有相同時分，parent enabled 時至少一 child enabled；既有 `rangeMonths`、本機子路徑、Drive admin 授權、Drive null＝不變更與啟用自檢語意不變。以 `(runHour,runMinute)` 對映 child，保留相同時分所有 guard／時間／狀態，新時分全為 null，未在 request 出現者由 orphan removal 刪除。每次儲存更新 parent timestamp；child 建立與設定修改都寫 Taipei `updatedAt`；選最早 enabled child（全停用時最早 child）為 representative，同步 parent shadow 時分與其 `lastRunDate`。GET 無 parent 回 transient `08:00 enabled=true` child、不寫 DB；parent 有但 times 空時以 legacy 時分回 transient child。
- [ ] **423.5 背景多時間 due runner：**保留且只保留既有一個 `@Scheduled(cron="0 * * * * *", zone="Asia/Taipei")` 與一個 `ApplicationReadyEvent`。兩入口都先經同一 `tryRunDueExports` 的 `AtomicBoolean.compareAndSet`；Compose 維持 business-services 單 replica。每個 parent 先驗總 enabled，再逐 child 判 child enabled、`now >= LocalTime.of(hour,minute)` 與該 child `lastRunDate != today`；成功或失敗都只更新自己的 guard、時間、狀態與 Taipei `updatedAt`，並更新 parent `lastRunAt/lastRunStatus` 摘要。代表 child 執行時同步 parent legacy guard，非代表 child 不可覆蓋 shadow。第一個 child 已 guard 不能阻止第二個；多個過期時間啟動自癒必須全跑；一個 render／本機／Drive／資料錯誤只記錄並繼續其他 child 與 owner。未來多 replica 需另加 DB atomic claim／row lock，JVM 鎖不視為跨 replica 保護。
- [ ] **423.6 文件產生、run-now 與 Drive：**每個 due child 和 run-now 維持只呼叫一次既有 USD 匯率文件建構方法，再用既有 renderer／writer 生成 xlsx/json；背景不得為匯率資料啟用 owner filter。run-now 忽略 parent／child enabled，只更新 parent 最近一次本機與 Drive 摘要，絕不新增、刪除或修改既有 child 的 guard／時間／狀態。無 parent 時建立 `enabled=false` parent 加一列 `08:00 enabled=true`、guard/status 為 null 的 child，並同步 parent shadow。Drive 仍 local-first、best-effort，並只允許主要管理者啟用。
- [ ] **423.7 前端：**`ExchangeRateView.vue` 把單一 `scheduleTime` 改成 `scheduleTimes` 列表；每列有 `el-time-picker`、啟用 switch、上次執行資訊與移除按鈕，最後一列移除鈕 disabled，另有「＋ 新增時間」。load 優先讀 `times[]`；只有 legacy response 未提供 times 才從 top-level `runHour/runMinute` 建一列，新 response 的空 times 是錯誤，不可補預設。save 前阻擋空列、無效或重複時分、總開關已開但全 child 停用；成功後以完整 response 重建，不作 client merge。範圍、local／Drive picker、立即匯出、雙格式通知與 parent 整體上次結果保留，文字改為「於下列每個啟用時間更新同日最新檔」。
- [ ] **423.8 排程列表與測試：**不新增／移除任何 `@Scheduled`，`SchedulePublicBffController.JOBS` 的台幣兌美元既有一筆維持，只更新為「每分鐘檢查頁面設定的多個每日時間」且總數不變。更新 Requirement 42 與 design 的單時間描述。新增／調整 migration／repository、`ExchangeRateExportScheduleServiceTest`、`ExchangeRateExportControllerTest`、`ExchangeRateBffScheduleTest` 與既有 `SingleTableScheduleServiceDualFormatTest`；前端至少以 build 與靜態契約釘住 add/remove、times payload、重複驗證與 legacy fallback。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f bff/pom.xml test
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
docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog WHERE id LIKE 'v1.126%';"
docker exec asset-postgres psql -U assets -d assets -c "\d exchange_rate_export_schedule_time"
# 依 db/schema.sql 檔頭的「重新產生」三步程序重產後：
bash scripts/tests/schema-sql-drift-test.sh
```

自動測試必須精確涵蓋：原 parent 時分、guard 與 `updated_at` 無損搬一 child、FK/cascade/unique/check/index、GET transient default、空／重複／非法／總啟用但全停用的 PUT 拒絕、時分排序、相同時分 guard 保留、新時分 unguarded、representative 立即同步、第一 child guard 不擋第二 child、兩個 overdue 都執行、第一個失敗後繼續、startup/tick CAS、owner isolation、run-now 前後 child 完全不變、Drive 與雙格式不回歸、controller JSON 和 BFF `times[]` passthrough。

Docker 實機驗收前先備份登入 owner 的 parent／child 全欄與輸出設定；以隔離測試目錄、暫關 Drive 或使用者明確指定的測試目標儲存至少兩個時間，讀回排序，並以受控 child guard／到點證明同日各執行一次。驗收後完整還原 parent、child、Drive、guard／狀態與輸出；不得留下測試時段、測試檔或雲端副本。若當時無法安全取得登入 owner 或隔離測試路徑，報告該 live 驗收未執行，不以單元測試冒充。

Docker rollout 的容器交接驗收另須明確留下順序證據：停止 old business-services、確認其 state 為 exited，才啟動新 image 讓 Liquibase 執行；不得把這一步與仍可讓兩 image 同時排程的 rolling／平行部署混用。migration 後確認 child 初始 guard 等於停止前備份 parent guard，並以同日 due probe 確認不會因交接重跑。

## 完成報告

（實作者完成後回填：實際變更檔案、migration id／實機 schema 證據、backend/BFF/frontend/Docker 驗證結果、設定備份與還原證據，以及任何版號避讓或與本計畫不同之處。）
