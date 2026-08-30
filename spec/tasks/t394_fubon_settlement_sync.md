# [t394] 應收付交割金額查詢排程——依來源日期核實在途款並更新既有快照

**對應 Requirements:** Requirement 129（每天 08:00／13:45／19:30／22:00 唯讀查詢富邦交割款；只有可核實仍在途的台幣金額才能覆寫最新快照的買股待付款／賣股待收款）
**前置任務:** t393；沿用 `FubonConfigState`、configured admin、`AssetSnapshotMutationLock`、`SnapshotAggregateCalculator`
**Liquibase changeset:** 無；不新增或修改 SQL 表、欄位、索引，不修改 `db/schema.sql`

## 目前核實狀態與實作切片

**原目標保留、財務同步尚未完成。** 2026-08-30第三輪修復已查Python與[Go交割款官方文件](https://www.fbs.com.tw/TradeAPI/docs/trading/library/go/accountManagement/QuerySettlement/)；`3d`最多只被說明為三日，未說明起訖、交易日/曆日、是否含尚未產製資料及完整未交割範圍。沒有可由生產程式核對的coverage充分条件，故不能以條件式文字假裝完整可寫路徑已存在。

本階段可實作394.1/394.2/394.4/394.7的安全adapter、token、解析、排程及no-write預檢修復；所有已解析但缺完整性證據的batch回 `coverageStatus=UNVERIFIED`、`reason=MISSING_SETTLEMENT_RANGE_CONTRACT`，service回 `SETTLEMENT_SCOPE_UNVERIFIED`、零writer呼叫。這是現有不安全半成品的防錯修復，**不是本任務完成或可寫驗收**。394.5/394.6保留為待核实後的設計，現階段不得實作、啟用或以fake成功案例放行財務writer。API inventory仍未串接（connected=false），描述可另註「唯讀解析已驗、財務同步待來源契約」。

解除阻礙前必須取得並納入spec獨立審查：

1. 券商對Python `query_settlement(...,"3d")`的範圍、交易種類與產製時間契約，能讓程式從當次輸入與結果驗出所有應納入未來交割日均在；需涵蓋週末/連假及多交易日案例。T+2市場規則不能單獨證明API回應完整。
2. 原始date與settlement_date、各方向應收付/費稅/net的明確定義；去識別完整同次回應與官方對帳單可核對正常買／賣及零值，不能截取「看起來合理」單列推廣。
3. 全None/空details到底是確定無款項、未產製或範圍外的可判定依據；未來未交割已全部結清的可信零值條件。若要包含同日款項，還需可核實已入帳/未入帳狀態；目前同日非零仍拒絕。

証據不得包含實帳號、憑證或token；不得用`coverage=true`設定、某日的時鐘、人工勾選或自造fixture代替來源契約。未補齐前第二個session保持未完成，不合併或刪除原Claude branches。

## 背景

既有 `transit_fund_type` 的 `買股待付款` 為 payable、`賣股待收款` 為 receivable；`AssetService.normalizeDepositAmount()` 的既有語意是 `currency="TRANSIT_TWD"`、應付 amount 為負、應收 amount 為正。`SnapshotAggregateCalculator` 直接加總 deposit amount，因此把應付寫正數或把在途幣別寫成一般 `TWD` 都會錯報資產。

[富邦官方交割款文件](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/accountManagement/QuerySettlement.txt) 已核實 `query_settlement(account,"3d")` 回 `Result{is_success,data: SettlementData,message}`；`data.account` 含 `branch_no/account`，`details[].date` 稱「查詢日」，`settlement_date` 為交割日。金額為 optional integer；無資料的列可為 date 存在而交割日、幣別、所有金額皆 None。官方例子的 `buy_settlement` 是負數、`sell_settlement` 可為零。不得再以未登入為理由忽略信封或拒絕零值。

官方只列 range 可用 `0d`／`3d`，未保證 `3d` 等於所有未交割交易或三個交易日；也未說明銀行何時已扣入／匯入同日交割款。故不能把所有 details 加總、把查詢日當交易日、臆測日內扣款時刻，或用 T+2 常識自行推算 `settlement_date`。本任務的可寫條件與不可用狀態必須明示，缺來源證據時保留既有資產值。

## 要做什麼

- [ ] 394.1 **每天固定四次，唯讀且有界。** 新增 `FUBON_SETTLEMENT_SYNC_ENABLED=false` 設定與 business-services Compose 轉接，不改既有部署旗標值。`FubonSettlementSyncScheduler` 使用 Asia/Taipei 的 `0 0 8 * * *`、`0 45 13 * * *`、`0 30 19 * * *`、`0 0 22 * * *`，不受交易日曆限制。feature flag → global/config READY → inFlight；flag 關閉時不查 owner、不打 HTTP。SDK 共用既有 accounting lock／每秒 5 次限制／5 秒 timeout，只對 auth-invalid 重登入重試一次；僅呼叫 `query_settlement(selected.raw,"3d")`，不接受外部 account/range 或交易方法。

- [ ] 394.2 **Python 在單一 accounting 臨界區保存來源身分並驗證官方信封。** 同一次呼叫取得 selected account、SDK response、當次 token，回應必須 `is_success is True` 且 data/details 形狀正確。`data.account.branch_no/account` 及 selected 對應欄位皆須非空字串且逐字相等；缺欄不可視為相等，不得拿 selected 欄位補 response。驗證後才生成沿用 trades 的 HMAC-SHA256 fingerprint（token 作 key、branch:account 作 message、前 24 hex）。raw 身分不離開 adapter、不進 Java、SSE、日誌或 metrics。紀錄本地 `queryDate`／`observedAt` 是觀測時間，不是供應商事件日；跨台北午夜的請求整批拒絕。

- [ ] 394.3 **日期、空值與符號採確定規則；不猜未交割。** 每列保留官方 `date` 為 `sourceQueryDate`，嚴格解析 YYYY/MM/DD，再轉 ISO；它可早於本次 queryDate，不得晚於本次 queryDate。非 no-data 列須有 settlementDate、TWD，以及所有官方金額欄位的 exact signed integer（拒 bool、NaN/Infinity、指數字串、部分 None）；`settlementDate >= sourceQueryDate`。買進應付 `buySettlement <= 0`、賣出應收 `sellSettlement >= 0`，`buySettlement + sellSettlement == totalSettlementAmount`；不再另加費稅，不接受與既有現股應付／應收語意相反的符號。其他金額只驗格式，不從官方示意數字推導新公式。
  - 同一 `(sourceQueryDate,settlementDate)` 重複、同一 settlementDate 多列但無可信唯一明細識別，皆 `AMBIGUOUS_SETTLEMENT`，整批 no-write；不得去重或加總猜測。
  - 僅 `settlementDate > queryDate` 可被判定為來源明示的未來交割候選；過去交割列不得計入。`settlementDate == queryDate` 且任一應收付非零時無法知道是否已入銀行餘額，整批 `AMBIGUOUS_SETTLEMENT`，四個固定時段都不可繞過此 gate。
  - 全 None 的官方佔位列可解析成 `NO_DATA_OBSERVED`，不當零元交易；部分 None 為 schema failure。裸空 details 合法表示沒觀測到列，但本身不證明應付應收皆零。
  - **覆寫完整兩列的必要條件**：除日期／符號通過外，須有官方契約或可重現去識別證據，證明這次查詢涵蓋待覆寫的完整未來交割區間，且金額已是各方向應收付款；本輪只有`coverageStatus=UNVERIFIED`、`MISSING_SETTLEMENT_RANGE_CONTRACT`具備可追溯依據，沒有已獲准的VERIFIED產生規則；service整批`SETTLEMENT_SCOPE_UNVERIFIED`、no-write。待補上述證據並另經spec審查，才可定義可由程式判定的正常規則。不得硬編 `coverage=true`、以 feature flag 代替此判定，或將測試 fixture 宣稱真人查證。
  - 通過完整性核實後，`payableAmount=Σ future.buySettlement`（非正）、`receivableAmount=Σ future.sellSettlement`（非負）。有效零值可寫 0；只有完整、明確無未來未交割款的來源證據才可把既有金額歸零，裸空陣列／單一佔位列／失敗都不能清空。

- [ ] 394.4 **窄 DTO、adapter 路徑與預檢。** token-protected `POST /internal/settlement/read` 無 body selector；輸出 `queryDate, observedAt, accountFingerprint, coverageStatus, reason, details`，details 僅上述已驗證 dates、signed canonical decimal 字串、幣別與 no-data 狀態。無原帳號或分行。Java `record SettlementBatch/SettlementDay` 強型別 LocalDate/Instant；settlement 金額使用 component 專用 signed decimal deserializer（允許正／負／零、precision<=20、scale<=10），保留既有 `CanonicalFubonDecimal` 的行情正值預設，非負欄位重用已存在的 `NonNegativeDeserializer`。Python/Java 皆複驗 queryDate 與 observedAt 台北日期一致、observedAt 不在未來、fingerprint 為 24 hex。HTTP／來源／日期／計算驗證皆在非交易式 service 預檢完成。`dryRun=true` 同樣檢查可寫條件並揭露不可用原因，不能僅因收到 HTTP 200 就回可寫；不開 writer transaction、不取快照鎖。

- [ ] 394.5 **HTTP 在 transaction 外，獨立 writer 的第一個 DB 動作就是共用快照鎖。** `FubonSettlementSyncService` 透過公開 Spring proxy 的 `Propagation.NOT_SUPPORTED` 預檢 configured active admin、global/config、唯讀 HTTP；不用 self-invocation 假裝切開交易。把不可變預檢結果交給獨立 `FubonSettlementWriter` bean 的 `REQUIRES_NEW` 方法。該方法任何 repository/SQL 之前先呼叫 `AssetSnapshotMutationLock.lockLatestForOwner(ownerId)`；不是「第一個 snapshot-related 動作」。無快照回 `NO_SNAPSHOT`，不新增快照、不更改 snapshotDate。鎖後重新檢查 owner 有效、`broker.code=fubon` active、台北富邦銀行與在途類型設定。commit 時 queryDate 必須仍為台北當日且 observedAt 距今不超過 60 秒，否則 `STALE_QUERY`、不寫。

- [ ] 394.6 **兩列、managed collection 與 aggregate 同一交易提交。** 在鎖住的最新快照 `snapshot.getDeposits()` 中按台北富邦銀行 id＋`買股待付款`／`賣股待收款` 精確比對，各 0 列才新增、1 列才更新、任一多於 1 列整批 `AMBIGUOUS_TARGET` rollback；不得 findFirst。既有 currency 非 TRANSIT_TWD 的目標列不能默默改造一般存款，回 `AMBIGUOUS_TARGET`。
  新增列須設定 snapshot、加入 managed deposits collection、currency=TRANSIT_TWD、對應 depositType、originalAmount=null、annualInterestRate=null、notes=null；更新只更新本任務的 amount，保留既有 notes，並確保無與台幣在途矛盾的 originalAmount／利率資料。兩個 amount 在加總後才 HALF_UP 至 scale 2，再驗證 `numeric(20,2)`（最多 18 位整數）；包括 0 的合法值仍 update／insert，不刪列。其他銀行／其他存款／持股完全不動。
  呼叫 `SnapshotAggregateCalculator.recalculate(snapshot)`，檢查所有變動總額同樣不超過 numeric(20,2)，`saveAndFlush` 與兩子列一次 commit；任一步溢位／失敗整批 rollback。SUCCESS/counters 僅 commit 後增加。使用者手動修改目標兩列會在下一次**通過來源核實的**同步被覆寫，需明示；不得省略 aggregate 重算或等待其他排程補總額。

- [ ] 394.7 **手動入口、狀態與 API 盤點。** business-only `POST /internal/brokers/fubon/settlement-sync?dryRun=true|false` 預設 true，獨立 exact-path constant-time token filter；controller 只委派。回 `outcome,dryRun,reason,payableAmount,receivableAmount`，金額僅成功提交後呈現，不含帳號、fingerprint 或明細。outcome 至少區分 DISABLED、SETTLEMENT_SYNC_DISABLED、MISCONFIGURED、NO_OWNER、BROKER_MISSING、BANK_MISSING、NO_SNAPSHOT、SETTLEMENT_FAILED、SETTLEMENT_SCOPE_UNVERIFIED、AMBIGUOUS_SETTLEMENT、AMBIGUOUS_TARGET、STALE_QUERY、DRY_RUN、SUCCESS；operational enum/counter 不落 SQL。排程 JOBS 的 BUSINESS／券商庫存項目據實描述「核實未來交割款後更新」，四個 cron 與數量測試同步。富邦 API 盤點的 httpEndpoint 為 **`POST /internal/settlement/read`**，consumer 才記 Java 手動入口；本任務以財務同步為要求，未具可驗收正常寫入路徑前`connected=false`，不得只因parser/預檢可執行就升為已串接。

- [ ] 394.8 **驗證安全與成功路徑，不能只測 disabled。** Python 用官方信封／None 形狀去識別 fixtures，測 selected identity 缺漏／不符、負應付／零應收、正應付拒絕、日期錯誤、未來與過去／同日、重複交割日、完整性缺失與 token 邊界。Java 實際 Spring proxy 驗證 HTTP 無交易、writer 首 SQL 是共用鎖，PostgreSQL 整合測 managed collection 新增/更新、負 payable 和零值、clear persistence context 後讀回 aggregate、numeric 邊界、全量 PUT 併發與整批 rollback。本階段實測目前未核實資料不呼writer、不取得快照寫鎖、不寫任何一列。writer成功/歸零/真DB寫入測試留待來源規則獲獨立審查後，受控fake僅驗實作，不可替代官方完整性證據。

## 驗證

```bash
bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test -f fubon-broker-service/Dockerfile fubon-broker-service --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

實作後依 run-stack rebuild/recreate fubon-broker-service、business-services、bff，維持現有 feature flags。本階段用隔離fake adapter驗no-write與無writer/無寫鎖；財務成功/aggregate讀回待阻礙解除後另測。真人帳戶不在本任務驗證範圍。未核實 3d coverage／同日入帳情況必須在完成報告列為不可用邊界，不把「保持 flag=false」或全數拒絕視為財務同步成功。

## 完成報告

本輪已明確拆出安全修復與未放行財務寫入；coverage來源阻礙尚未解除，**Task394未完成**，尚不能產出整體規格通過或財務同步完成結論。原接手報告曾記載只解析裸 data、拒絕零／負值、未驗證帳號、不重算 aggregate、把鎖順序放寬為第一個 snapshot-related 操作；這些是本輪必須修復的歷史缺口，均不是允許保留的例外。先前測試綠燈不構成本規格驗收。完成時列出官方來源核實範圍、仍無法核實的 coverage、實際成功／no-write 證據及獨立審查結果，不預填成功。

### 2026-08-30 安全切片驗證

安全修復已通過Python676、backend1,854、BFF242項完整測試。strict Result／身分／日期／signed金額解析與手動參數邊界已實作；缺來源完整性時固定SETTLEMENT_SCOPE_UNVERIFIED，沒有writer、寫鎖或在途款變動。此測試證據不解除本檔來源契約要求；Task394及第二個session仍未完成。
