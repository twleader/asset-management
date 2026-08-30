# [t396] 除權息資料查詢排程——開收盤日期批次只處理雷達交集並寫既有證據

**對應 Requirements:** Requirement 131（交易日 09:00／13:30 唯讀查詢富邦股利資料，只處理交易雷達股票，併入既有除權息證據；不新增 SQL 表或欄位）
**前置任務:** 既有 `StockSourceQuery`、`DividendSnapshotStore`、`DividendFetchClient.DividendEvent`；external-materials-service 的富邦 config-state 等價元件由本任務建立一份供 t397/t398 共用
**Liquibase changeset:** 無

## 背景

[富邦官方 dividends 文件](https://www.fbs.com.tw/TradeAPI/docs/market-data/http-api/corporate-actions/dividends.txt) 已核實 `rest_client.stock.corporate_actions.dividends` **只接受 start_date/end_date 日期範圍，不接受 symbol**。來源為市場日期批次，回 data 陣列，包含 symbol、exchange、date、dividendType、cashDividend、stockDividendShares 等；不能沿用先前假設的逐檔 `dividends(symbol)`。使用者的個股限制容許此 provider 只能提供的公開市場日期批次，但 adapter 輸出及後續處理、存入必須先與傳入 radar codes 嚴格取交集，不得逐檔查非雷達股票或宣稱上游做了 symbol 過濾。

本任務只做可映射到既有 dividend 證據的股利，不做 capital_changes／減資。沿用 `DividendSnapshotStore.record()` 的 snapshot/event/observation 與 content_hash/event_key 去重；backend 的 `DividendCurrentStateProjectionService` 仍是唯一 current-state 投影者。`provider="FUBON_SDK"` 落入既有 `JdbcDividendCurrentStateRepository` CASE 的 ELSE 50，不改官方及既有 provider 排序，也不把市場批次 HTTP 200 當未來 45 天完整性證明。

## 要做什麼

- [x] 396.1 **排程與 gate。** 新增 `FUBON_DIVIDEND_SYNC_ENABLED=false` 及 external-materials-service Compose 轉接，不更改既有部署旗標。Asia/Taipei cron 為 `0 0 9 * * MON-FRI`、`0 30 13 * * MON-FRI`。feature flag → global/config READY → 既有交易日曆明確 true → inFlight；false/no-data/unknown 各自記狀態，不借用 fail-open 判斷。external 共用一份等價 config-state 元件，不能複製多個競爭版本。既有每日 17:00 DividendPersister 與 startup warmup 不改。

- [x] 396.2 **只處理雷達交集，日期有界且每轮只作一次市場請求。** `StockSourceQuery.collectTwRadarCodes` 取得各 owner 最新台股持股∪台股 stock_alert，排除 0000；不改其定義，不用 collectAllStockCodes，不查美股／英股。去重排序後 empty 回 NO_SYMBOLS，**不呼叫來源**。本任務固定查 `[queryDate.minusDays(320),queryDate.plusDays(45)]`（含首尾不超過 366 天）；本地 queryDate 只作查詢窗，不是事件日。一次完整日期批次涵蓋本次 codes，不能按每檔重複取全市場。
  Python route `POST /internal/market-data/dividends/read` body 為 `{"symbols":[...],"from":"YYYY-MM-DD","to":"YYYY-MM-DD"}`，只供可信 external consumer；symbols 需唯一合法台股代碼、排除 0000、上限 2000，window 必須符合本次 queryDate 規則。只呼叫 `corporate_actions.dividends(**{"start_date":from,"end_date":to})`。來源必須是含 data list 的成功 response，deadline/大小上限與 marketdata budget 沿用既有窄 wrapper；失敗不能回空成功。adapter 先按 symbol/exchange 取傳入集合交集，再正規化輸出；非交集 row 不快取、不寫證據、不輸出原始內容。

- [x] 396.3 **官方欄位映射，不猜配股單位或事件日期。** adapter 回 `queryDate,observedAt,scopeFrom,scopeTo,provider="FUBON_SDK",rows` 及每檔 usable/reason。row symbol 必須屬傳入集合，exchange 僅 TWSE/TPEx，date 是來源除權息日且在請求範圍；dividendType 必為 息/權/權息。`cashDividend` 非負有限數，`stockDividendShares` 是**每千股配股數**，不能直接塞進既有 stockDividend；`dividend` 是參考價調整量，不能冒充現金股利。拒 bool、非有限數、負股利；未來事件的價格欄可 null，不因此拒收有效股利。
  對應 DividendEvent：year 由已核實事件 anchor date 的西元年產生（沿用既有定義，非盈餘所屬年度）；含息且 cashDividend>0 才映射 cashDividend/exDividendDate；含權則 exRightsDate=date，**不可把除權日 fallback 為除息日**。cashPaymentDate/stockPaymentDate 無來源欄位則 null，不填 queryDate。
  stockDividendShares→既有 stockDividend 僅在既有欄位的金額／配股率單位可核實相容時轉換，需記錄來源和轉換規則；不得假設所有股票面額皆 10 元。未核實為 `STOCK_DIVIDEND_UNIT_UNVERIFIED`，股票股利保持 null；混合事件可保留可信 cash 部分作 PARTIAL，純配股沒有可映射金額不造事件，保留具名不可用觀測。至少一個可信正股利＋其對應日期才是 usable event。
  money 依既有 snapshot_event numeric(18,6) HALF_UP 至 scale 6 後驗 precision<=18；被捨入成 0 的微小正數不能宣稱正股利。Java 以 immutable record/BigDecimal/LocalDate 複驗，再存入。不能讓一檔 malformed row 污染其他雷達檔；同檔無法辨明的重複／矛盾事件，該檔記 FAILED，不 arbitrary pick。

- [x] 396.4 **只 append 既有證據，不升級完整性。** `FubonDividendEvidenceSyncService` 放 external-materials-service；本次成功取得日期批次後，每檔雷達代碼只用已過濾的 rows 建立 `DividendFetchResult`，呼叫現有 `DividendSnapshotStore.record(code,"台股",result,observedAt)`。provider 固定 FUBON_SDK，scopeFrom/To 是實際查詢窗，sourceAvailableAt 未提供則 null（不得用 fetchedAt 宣稱來源發佈時間），sourceUrls 未有穩定資料 URL 則 empty，觀測時間仍單獨保留。
  本輪固定 `status=PARTIAL,complete=false`；即使 scopeTo=queryDate+45 也只是請求終日，**不是**完整 upcoming calendar 證明。無匹配事件亦為 PARTIAL，不轉 EMPTY_COMPLETE；來源失敗用既有 FAILED observation/attempt 路徑，不製造空成功。沿用 snapshot/event hash 與 observation append，重跑相同事件不得重複 snapshot/event，新的 observation 可正常追加。
  不直接 upsert stock_dividend_history、不直接 trigger backend、不新增跨服務 callback；下次既有讀取路徑自動納入該來源。`JdbcDividendCurrentStateRepository` 的 CASE 權威順序、findLatestComplete/歷史分流與取消規則完全不變。

- [x] 396.5 **手動與盤點契約。** external-only `POST /internal/dividend/fubon-sync?dryRun=true|false` 預設 true，獨立 exact-path constant-time token filter；controller 只委派。dryRun 仍查詢／驗證／過濾，但不寫任何證據表或 attempt。回 outcome/dryRun/reason/processedCount/persistedCount/skippedCount/failedCount，不回非雷達原始資料。enum 至少 DISABLED、DIVIDEND_SYNC_DISABLED、MISCONFIGURED、CALENDAR_UNKNOWN、MARKET_CLOSED、NO_SYMBOLS、DIVIDEND_FAILED、DRY_RUN、PARTIAL、SUCCESS；只保存部分有效檔或存在未核實欄位須揭露 PARTIAL，不能全綠。
  JOBS 用 EXTERNAL／股利，時間「交易日 09:00／13:30」，描述「日期批次取得、只處理雷達交集」；數量／分類測試同步。API 盤點 dividends 的 httpEndpoint 為 **`POST /internal/market-data/dividends/read`**，consumer 描述 external 排程及 Java 手動入口；capital_changes 維持未串接。不得新增 BFF、frontend 或 9090 公開路由。

- [x] 396.6 **自動與持久化驗證。** Python fake SDK 斷言官方呼叫只含 start_date/end_date、不送 symbol、非空雷達只呼叫一次、空雷達零外呼；混合 radar/non-radar batch 只回交集。測 date/type、未來 null price、cash/stock 單位、矛盾事件與 token。Java 測兩時段／calendar/config gate、同批不同股票可獨立成功、dry-run 零 DB write、PARTIAL/FAILED 不能使原已知 future event 被取消。PostgreSQL 透過既有 store 寫兩次後讀回 snapshot/event 不重複、observation 完整，核對 sourceAvailableAt null 與真實 scope；原 17:00／權威排序／projection 回歸必須綠燈。

## 驗證

```bash
bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test -f fubon-broker-service/Dockerfile fubon-broker-service --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

實作後依 run-stack rebuild/recreate fubon-broker-service、external-materials-service、bff，維持既有旗標；以隔離去識別 fixtures 驗證寫入、資料讀回及原投影行為。本任務不查真人帳號，不宣稱真實 SDK 權限／樣本已驗證。

## 完成報告

**程式、隔離測試、獨立架構與 feature Docker 驗收已完成；第二個 session 整體仍待394／395來源契約。** External完整700項、Python676項全部通過；真PostgreSQL證明日期批次只保留雷達交集、相同snapshot/event不重複、observation可追加、sourceAvailableAt維持null。FUBON_SDK固定PARTIAL／complete=false；現金單位可驗，未核實配股或減資不在本輪資料保證內，capital_changes保持未串接。既有權威排序、complete gate、future事件投影未更改，PARTIAL／空觀測不得取消已知future事件。沒有真人SDK或正式資料測試。

共用實機驗收見 [Task386接手更新](t386_fubon_api_documentation_view.md)：四個服務從此feature rebuild/recreate且healthy、class/source雜湊與測試產物相符，內部disabled GET及登入後盤點／排程頁已驗。全域FUBON_ENABLED保持false，新flags保持false，正向資料回寫只在隔離PostgreSQL／Redis測試，未啟用真人券商查詢。這不構成第二個session已完成或已push。
