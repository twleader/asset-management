# [t385] 富邦台股成交紀錄同步排程——盤中每 30 分鐘唯讀查詢並新增交易紀錄

**對應 Requirements:** Requirement 120（在交易日盤中每 30 分鐘唯讀查詢富邦當日成交紀錄，若查到系統尚未記錄的成交，冪等新增到交易紀錄，不覆寫既有資料）
**前置任務:** 無（重用 Requirement 90／Task 352 已落地的 `fubon-broker-service`、`integration/fubon` package、`MarketDataService.isTwTradingDayKnown`、`UserAdminService.configuredAdmin()`）
**Liquibase changeset:** `v1.119.0-asset-transaction-fubon-source.sql`（建檔前跑 `bash scripts/spec-check.sh` 核對是否已被其他 worktree 佔用最新版號；若已撞號，依既有編號避讓慣例遞增版號，不得覆寫他人 changeset）

## 背景

**2026-08-30 接手修正：** 成交同步依原人類要求改為交易日09:00起每半小時至14:00（11輪，不含14:30），另修復已核實的 SDK 請求／回應日期格式與同次 selected-account 證據。原385.7的provenance schema已落地，不重建migration、不改庫存同步時間；以下原建置步驟保留歷史脈絡，本輪新增要求以385.13為準。

系統既有一個排程 `FubonInventorySyncScheduler`（`backend/src/main/java/com/steven/assets/integration/fubon/FubonInventorySyncScheduler.java`），盤中交易日 09:05–13:35 每 30 分鐘（`@Scheduled(cron="0 5,35 9-13 * * MON-FRI", zone="Asia/Taipei")`）以隔離在 `fubon-broker-service`（Python 3.13、`platform: linux/amd64`）的富邦官方 SDK 2.2.9 唯讀查詢庫存，對帳後只替換最新資產快照中的富邦台股部位。

使用者現在要求：**新增一個同樣每 30 分鐘的排程，改為唯讀查詢富邦「成交紀錄」（已成交的交易，不是庫存），若查到系統尚未記錄的成交，自動新增到既有的交易紀錄表 `asset_transaction`（Requirement 49，手動記帳流水帳）。** 這是純唯讀查詢（歷史成交／對帳單查詢），完全符合 CLAUDE.md 最高優先鐵則「券商 API 只能查詢不得交易」——本任務不下單、不改單、不刪單，也不寫入任何會改變富邦帳戶狀態的資料。

`asset_transaction` 目前完全是手動輸入的流水帳（`AssetTransactionService.createAssetTransaction`／`updateAssetTransaction`，經 `TenantGuard.requireCurrentUserId()` 取得 owner，僅在 HTTP request context 可用），沒有任何欄位可辨識「這筆是不是已經從富邦同步過」，也沒有任何欄位可防止排程每 30 分鐘重複新增同一筆成交。本任務必須新增 provenance 欄位與資料庫層防重複機制。

`fubon-broker-service` 的 `SdkGateway`（`fubon-broker-service/src/fubon_broker_service/sdk_gateway.py`）目前只封裝 `sdk.accounting.*`（`inventories`／`unrealized_gains_and_loses`）與 `sdk.marketdata.*`；`_default_sdk_factory()` 明確註解「Deliberately import only the login/accounting/market-data SDK root. No order API is imported」。官方 SDK 的 `sdk.stock` 命名空間下有 `filled_history(account, start_date, end_date=None)` 可唯讀查詢成交紀錄（回傳 `FilledData` 物件，官方文件列出 `date`、`filled_no`、`filled_avg_price`、`filled_qty`、`filled_price`、`order_type`（範例值 `Stock`）、`filled_time`），但 `sdk.stock` 同時也持有下單／改單／刪單方法（`place_order`／`cancel_order`／`modify_price`／`modify_quantity`／`batch_place_order` 等）。本任務必須新增一個**只存取 `filled_history` 這一個屬性**的窄範圍封裝，不得暴露整個 `stock` client，不擴大下單能力的可觸及面。

**官方契約已核實，真人樣本未執行：** [Python FilledHistory](https://www.fbs.com.tw/TradeAPI/docs/trading/library/python/trade/FilledHistory/) 明列 `stock_no`、`buy_sell`、`account`、`branch_no`、`date`、`filled_no` 與成交量價欄位；請求範例使用 `YYYYMMDD`，回應 date 為 `YYYY/MM/DD`。v2.1.1 起單次最大30日，本系統仍保留既有 `end-start<=7天` 較窄限制。舊文件稱沒有欄位來源、只能依姊妹API猜測的限制已失效。此為文件契約核實，不冒稱已登入真人帳戶驗證。

## 要做什麼

- [ ] **385.1 新增獨立 feature flag 與排程骨架。** `.env`／`.env.example` 新增 `FUBON_TRADE_SYNC_ENABLED=false`（預設關閉，非秘密值）。`docker-compose.yml` 對 `business-services` 的容器環境變數是逐條白名單（`environment:` 區塊內既有 `FUBON_INVENTORY_SYNC_ENABLED: ${FUBON_INVENTORY_SYNC_ENABLED:-false}` 這一行），**必須**在同一區塊緊鄰新增一行 `FUBON_TRADE_SYNC_ENABLED: ${FUBON_TRADE_SYNC_ENABLED:-false}`；漏掉這一行會讓 `.env` 設定不會傳入容器，`@Value("${fubon.trade-sync-enabled:false}")` 永遠讀到預設 `false`，功能靜默失效（`.env` 設 `true` 也不會生效）。新增 `backend/src/main/java/com/steven/assets/integration/fubon/FubonTradeSyncScheduler.java`：`@Component`，`@Scheduled(cron="0 0,30 9-13 * * MON-FRI", zone="Asia/Taipei")` 加 `@Scheduled(cron="0 0 14 * * MON-FRI", zone="Asia/Taipei")`，持有獨立於 `FubonInventorySyncScheduler` 的 `AtomicBoolean inFlight`（process-local single-flight，互不共用、互不阻塞既有 inventory 排程）。執行順序：(1) `syncService.tradeSyncFeatureGate(false)` 非 null 即 return（`FUBON_TRADE_SYNC_ENABLED=false` → `TRADE_SYNC_DISABLED`；`FUBON_TRADE_SYNC_ENABLED=true` 且 `FUBON_TW_LIVE_QUOTES_ENABLED=true` → `TRADE_SYNC_CAPACITY_CONFLICT`——比照既有 `FubonInventorySyncService.inventoryFeatureGate` 對 LIVE quote 的互斥規則，因 LIVE quote provider 持續占用同一 SDK session 的 marketdata lane；`FUBON_TRADE_SYNC_ENABLED` 與 `FUBON_INVENTORY_SYNC_ENABLED` 兩者互不互斥，兩者可同時為 `true`，共用 Python 端同一帳務 lane mutex 依序序列化執行，不互相 disable）；(2) `FubonConfigState.snapshot().state()` 必須為 `READY`，否則 `syncService.localConfigOutcome(false, state)` 並 return；(3) `LocalDate today = LocalDate.now(clock.withZone(TW_ZONE))`，呼叫既有 `MarketDataService.isTwTradingDayKnown(today)`，`RuntimeException` 視為 `Optional.empty()`；只有 `Optional.of(true)` 才呼叫 `syncService.syncScheduledAfterCalendar(today, false)`（`dryRun` 固定 `false`），否則 `syncService.calendarUnknown(false)` 並 return。整體結構、命名風格、`AtomicBoolean` single-flight 與 `finally { inFlight.set(false); }` 慣例比照 `FubonInventorySyncScheduler.java` 現有寫法；除了本輪已獨立修正的成交節拍，另一差異是 `syncScheduledAfterCalendar` 的呼叫簽章——既有 `FubonInventorySyncScheduler` 呼叫的是單參數版本 `syncService.syncScheduledAfterCalendar(today)`，本任務的 `FubonTradeSyncService.syncScheduledAfterCalendar` 刻意改為雙參數 `(LocalDate today, boolean dryRun)`（見 385.5），呼叫處固定傳 `false`；這一點**不是**要複製既有單參數呼叫，其餘結構才逐一比照。

- [ ] **385.2 Python adapter：窄範圍唯讀成交查詢方法。** 在 `fubon-broker-service/src/fubon_broker_service/sdk_gateway.py` 的 `SdkGateway` 新增 `read_filled_trades(self, start_date: str, end_date: str) -> object`：與既有 `read_accounting_pair()` 共用同一 `self._accounting_lock`（序列化執行）、同一 `self._wait_for_accounting_budget()`（既有帳務 5 calls/sec 上限的限流機制，`_accounting_call` 內部第一行即呼叫它；本方法雖不經過 `_accounting_call`，仍必須在每次實際發出 SDK 呼叫前**顯式呼叫這個既有方法**，與 `_accounting_call` 共用同一個 `self._account_starts` 預算池，不得省略），同一 auth-invalid bounded retry（最多一次 `_invalidate_locked()` 後重跑）與同一 `SdkCallError` 例外語意。**不得**透過既有 `_accounting_call(method_name, account)` 呼叫——該 helper 固定解析 `sdk.accounting.<method_name>` 並以單一 `account` 參數呼叫（`lambda: method(account)`），既不支援 `stock` 命名空間也不支援額外的 `start_date`／`end_date` 參數，介面不相容，硬套用會把該 helper 的命名空間解析從 `accounting` 誤混到 `stock`。方法內部只能透過 `raw_field(raw_field(account_session_sdk, "stock"), "filled_history")`（或等價的顯式屬性存取）取得單一 callable 並呼叫一次，**不得**把 `stock` 物件整個回傳、快取為 instance attribute 或用任何形式暴露給呼叫端；新增單元測試以 fake SDK 的 `stock` 屬性包一層 attribute-access spy，斷言整次 `read_filled_trades` 呼叫過程中，`stock` 物件唯一被存取過的屬性名稱是 `"filled_history"`，未曾存取 `place_order`／`cancel_order`／`modify_price`／`modify_quantity`／`batch_place_order`／`batch_cancel_order` 或任何其他屬性（即使 fake SDK 本身有提供這些屬性也不能被存取到）。

- [ ] **385.3 Python adapter：raw row 驗證與 route。** 新增 `TradeReadRequest(BaseModel)`（`model_config = ConfigDict(extra="forbid", strict=True)`），欄位 `startDate: StrictStr`、`endDate: StrictStr`，各自先驗嚴格 `YYYY-MM-DD` 正規格式、再以 `date.fromisoformat()` 驗實際日期（失敗經既有 `RequestValidationError` handler 回消毒 `400`）。新增 `POST /internal/trades/read` route，複用既有 `authorize` dependency（`DISABLED`/`MISCONFIGURED`/token 缺漏或錯誤語意與既有 `/internal/portfolio/read`、`/internal/market-data/tw-quotes` 完全一致）；handler 另驗 `startDate <= endDate` 且 `(date.fromisoformat(endDate) - date.fromisoformat(startDate)).days <= 7`，違反回消毒 `400`（reason `"INVALID_DATE_RANGE"`）。呼叫 `sdk_gateway.read_filled_trades(startDate, endDate)`，對回傳的每一 raw row，**在組成任何 normalized DTO 之前**依序驗證：(a) `order_type` 精確等於 `"Stock"`（非 Stock 一律整批視為 invalid）；(b) 標的代號、買賣別、帳號、分公司代號欄位存在且可解析（依 385 背景段落核實出的確切屬性名稱；核實不到任一欄位＝視為不存在＝整批 invalid）；(c) 帳號／分公司代號與目前 selected account 的 raw 欄位精確相等；(d) raw `date` 先按嚴格 `YYYY/MM/DD` 解析為日期物件，再驗落在 `[startDate, endDate]`（inclusive），輸出 `filledDate` 改為 ISO；不可比較不同格式原字串；(e) 買賣別對應官方 `BSAction` 的 Buy／Sell 兩值之一，其餘值（如零股、當沖特殊 flag）整批 invalid。任一列未通過即整批（本次呼叫全部列）回 `503`（reason `"RECONCILE_FAILED"`），不得只丟棄該列後繼續處理其餘列。全部通過後，組成 response：`{"batchId": <新產生 UUID>, "startDate": ..., "endDate": ..., "accountFingerprint": <既有 HMAC fingerprint 規則>, "emptyConfirmed": <rows 為空時 true>, "trades": [{"stockCode","side","filledQty","filledPrice","filledAvgPrice","filledDate","filledTime","filledNo"}, ...]}`。`filledPrice`／`filledAvgPrice` 走既有 `Decimal(str(value))` → canonical decimal string 規則（`CanonicalFubonDecimal` 對應：`precision<=20`、`0<=scale<=10`、正值）；`filledQty` 為 `1..9,999,999,999` 的 exact integer；`filledNo` 為非空字串、長度 ≤ 50，超界或型別錯誤同樣整批 invalid。`app.py` 新增此 route 時，`outcome_counters` 於成功時 increment 既有 `Outcome.SUCCESS`（沿用既有 `Outcome` enum，不新增 Python 端 enum 值）。

- [ ] **385.4 backend：DTO 與 client。** `FubonDtos.java` 新增 `TradeBatchResponse(String batchId, LocalDate startDate, LocalDate endDate, String accountFingerprint, boolean emptyConfirmed, List<FilledTrade> trades)` 與 `FilledTrade(String stockCode, String side, long filledQty, CanonicalFubonDecimal filledPrice, CanonicalFubonDecimal filledAvgPrice, LocalDate filledDate, String filledTime, String filledNo)`（皆為 immutable record，不得用 `Map`/`JsonNode` 代替）。`FubonBrokerClient` 介面新增 `FubonDtos.CallResult<FubonDtos.TradeBatchResponse> readFilledTrades(LocalDate start, LocalDate end);`；`FubonHttpClient` 新增對應實作，POST 到 `/internal/trades/read`，body `{"startDate": start.toString(), "endDate": end.toString()}`，比照既有 `readPortfolio()`／`readTwQuotes()` 的 timeout／4xx／5xx／invalid JSON 處理（timeout/4xx/5xx/invalid JSON 一律轉 `CallResult.failure(reason)`，不 log raw body）。

- [ ] **385.5 backend：`FubonTradeSyncService` 業務邏輯。** 新增 `FubonTradeSyncService.java`，建構子注入 `FubonConfigState`、`FubonBrokerClient`、`MarketDataService`、`UserAdminService`、`BrokerRepository`、`AssetTransactionRepository`、`StockMasterService`、新增的 `FubonTradeOutcomeCounters`、`Clock`，以及兩個 `@Value` boolean flag（比照 `FubonInventorySyncService` 既有建構子模式：`@Value("${fubon.trade-sync-enabled:false}") boolean tradeSyncEnabled` 為本任務新增設定鍵；`@Value("${fubon.tw-live-quotes-enabled:false}") boolean liveQuotesEnabled` 沿用既有設定鍵，與 `FubonInventorySyncService` 讀同一個值）。**本任務刻意採不同於既有 `FubonInventorySyncService` 的方法回傳型別（裸 `FubonTradeOutcome` enum 而非既有的 `FubonDtos.SyncResponse` 完整回應物件）與呼叫圖（`syncManual` 委派給 `syncScheduledAfterCalendar`；既有 `FubonInventorySyncService.syncManual` 是獨立流程、不呼叫 `syncScheduledAfterCalendar`），下方僅指出精神類比之處，不是逐一比照既有程式碼結構。** 公開方法：
  - `FubonTradeOutcome tradeSyncFeatureGate(boolean dryRun)`：`!tradeSyncEnabled` → `TRADE_SYNC_DISABLED`；`liveQuotesEnabled` → `TRADE_SYNC_CAPACITY_CONFLICT`；否則 `null`。
  - `void localConfigOutcome(boolean dryRun, FubonConfigState.State state)`：記錄 `DISABLED`／`MISCONFIGURED` counter（比照既有寫法）。
  - `void calendarUnknown(boolean dryRun)`：記錄 `CALENDAR_UNKNOWN`。
  - `private FubonTradeOutcome localConfigGate(boolean dryRun)`：先呼叫 `tradeSyncFeatureGate(dryRun)`，非 null 直接回傳；否則檢查 `configState.snapshot().state()`，非 `READY` 記 `MISCONFIGURED` 並回傳該值；否則回 `null`（放行）。此方法同時供下面 `syncManual` 與 `syncScheduledAfterCalendar` 共用（精神上類比既有 `FubonInventorySyncService.localConfigGate` 的 gate-短路做法，但本任務回傳裸 enum 而非 `SyncResponse`）。
  - `TradeSyncResult syncManual(boolean dryRun)`：供 385.8 `FubonTradeSyncController` 呼叫，**不信任呼叫端已驗證日曆**。步驟：(1) `localConfigGate(dryRun)` 非 null 即回傳對應 outcome；(2) `LocalDate today = LocalDate.now(clock.withZone(TW_ZONE))`，呼叫 `marketDataService.isTwTradingDayKnown(today)`（`RuntimeException` 視為 `Optional.empty()`），非明確 `Optional.of(true)` 則 `calendarUnknown(dryRun)` 並回傳 `CALENDAR_UNKNOWN`；(3) 呼叫 `syncScheduledAfterCalendar(today, dryRun)` 並回傳其結果。（既有 `FubonInventorySyncService.syncManual` 是不同的獨立流程、不委派至 `syncScheduledAfterCalendar`，本方法是本任務刻意的新設計，僅精神上類比「manual 端點自行重驗日曆、不信任呼叫端」這一點。）
  - `TradeSyncResult syncScheduledAfterCalendar(LocalDate today, boolean dryRun)`（回傳型別自訂，供 `syncManual` 與 scheduler 共用；scheduler 呼叫時固定 `dryRun=false`）：
    0. **defense-in-depth 二次檢查**：`localConfigGate(dryRun)` 非 null 即回傳對應 outcome 並 return（即使呼叫端 `FubonTradeSyncScheduler`／`syncManual` 已各自驗證過一次，仍在此方法內部重驗一次；精神上類比既有 `FubonInventorySyncService.syncScheduledAfterCalendar` 內部呼叫 `localConfigGate` 的 defense-in-depth 做法）。
    1. `Optional<AppUser> configured = userAdminService.configuredAdmin()`；`configuredAdmin()` 本身不做 active／admin 過濾（只是依 email 查找），故此處必須自行檢查：為空、`!configured.get().isActive()` 或 `!configured.get().isAdmin()` 任一成立即記 `NO_OWNER` 並 return（比照既有 `FubonInventorySyncService.preflightCommit` 對 configured admin 的同一組檢查，此為財務寫入路徑的信任邊界，不可省略）。
    2. `brokerRepository.findByCode("fubon")`：查無、或找到但 `!Boolean.TRUE.equals(broker.getActive())`（已被 `/api/settings/*` 軟停用），皆記 `BROKER_MISSING` 並 return（比照既有 `FubonInventorySyncService.preflightCommit` 對 `broker.active` 的過濾邏輯，不得只驗證「查無」——否則使用者軟停用富邦券商後，本排程仍會繼續唯讀查詢並寫入交易紀錄）。
    3. `brokerClient.readFilledTrades(today, today)`；`!success || body==null` 記 `TRADE_FAILED` 並 return；`body.emptyConfirmed() && body.trades().isEmpty()` 記 `NO_NEW_TRADES` 並 return（合法：今日尚無成交，非失敗）。
    4. 對 `body.trades()` 逐筆：`stockName = stockMasterService.resolveNameLocalOnly(trade.stockCode(), "台股")`；`stockName == null` 則跳過此筆（**不算失敗、不中止整批**，計入本輪 `skippedNameUnresolvedCount`，下一輪 30 分鐘排程自然重試，屆時該標的名稱可能已由既有庫存同步或報價查詢流程補齊）；否則檢查 `assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(ownerId, trade.filledNo())`，存在則跳過（計入 `skippedExistingCount`，不覆寫任何既有欄位），不存在則加入本輪待新增清單。
    5. `dryRun=true`：記 `DRY_RUN` 並 return，**不進入下一步的寫入 transaction**（即便待新增清單非空）。
    6. `dryRun=false` 且待新增清單非空：於單一 `@Transactional` 方法內逐筆 `assetTransactionRepository.save(...)` 建構下方 385.6 欄位映射的新 `AssetTransaction`；捕捉 `DataIntegrityViolationException`（唯一索引競態）並視為該筆已存在、跳過而非拋出至排程外層。`dryRun=false` 且待新增清單為空：直接記 `SUCCESS`（代表本輪查到的成交都已同步過）。全部完成記 `SUCCESS`。
    7. 任何非預期例外導致 transaction rollback，記 `ROLLED_BACK` 並讓例外往外拋（讓排程的 `finally` 正常釋放 in-flight guard，但不吞例外）。

- [ ] **385.6 `AssetTransaction` 欄位映射（新增列的固定規則）。**
  - `ownerUserId` = configured admin 的 `id`。
  - `transactionType` = `"Buy".equals(trade.side())` ? `"買"` : `"賣"`（`side` 只可能是 `"Buy"`／`"Sell"`，Python 層已驗證，Java 層仍需 `else` 分支對非預期值拋 `IllegalStateException` 而非靜默當賣出處理）。
  - `assetType` = `"股票"`；`assetName` = 385.5 步驟 4 解析所得名稱；`assetCode` = `trade.stockCode()`；`market` = `"台股"`；`currency` = `"TWD"`；`channel` = `"富邦證券"`（與 `BrokerSeed("fubon","富邦證券","富邦")` 的 `displayName` 完全一致）。
  - `tradeDate` = `trade.filledDate()`；`shares` = `BigDecimal.valueOf(trade.filledQty())`；`price` = `trade.filledAvgPrice()` 轉出的 `BigDecimal`（統一使用成交均價，不與 `filledPrice` 混用；若同一 `filledNo` 已代表單次分批成交明細、`filledAvgPrice` 與 `filledPrice` 數值相同，此規則仍一致適用不需特判）。
  - `amount` = 未捨入 `price.multiply(shares)`，最後才 `.setScale(2, RoundingMode.HALF_UP)`；結果要求 `precision<=20`（超界視為該列 invalid，計入 `TRADE_FAILED` 整批，因為這代表 raw 數值已超出系統可信範圍，不應該只跳過該列悄悄漏記一筆交易）。
  - `fee`、`transactionTax`、`exchangeRate`、`notes` 皆精確為 `null`（SDK 未提供，不得估算或帶入固定文字）。
  - `source` = `"FUBON_SYNC"`；`brokerFilledNo` = `trade.filledNo()`。
  - 既有 `AssetTransactionService.createAssetTransaction`／`updateAssetTransaction`（builder 不設 `source`）維持不變，其產生的紀錄 `source` 恆為 entity 層 `@Builder.Default` 預設值 `"MANUAL"`；`AssetTransactionDto.CreateAssetTransactionRequest` 不新增 `source`／`brokerFilledNo` 欄位，使用者無法透過既有 API 手動指定或覆寫這兩欄。

- [ ] **385.7 `asset_transaction` schema 變更。** 新增 `backend/src/main/resources/db/changelog/changes/v1.119.0-asset-transaction-fubon-source.sql`（版號建檔前以 `bash scripts/spec-check.sh` 核對現行最新版號，若已非 `v1.118.0` 則遞增至實際下一版，並在 `db.changelog-master.yaml` 加對應 `include`）：
  ```sql
  ALTER TABLE asset_transaction
      ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
      ADD COLUMN broker_filled_no VARCHAR(50);

  ALTER TABLE asset_transaction
      ADD CONSTRAINT asset_transaction_source_check CHECK (source IN ('MANUAL', 'FUBON_SYNC'));

  CREATE UNIQUE INDEX ux_asset_transaction_owner_broker_filled_no
      ON asset_transaction (owner_user_id, broker_filled_no)
      WHERE broker_filled_no IS NOT NULL;
  ```
  `AssetTransaction.java` entity 新增對應欄位：`@Builder.Default @Column(nullable = false, length = 20) private String source = "MANUAL";` 與 `@Column(name = "broker_filled_no", length = 50) private String brokerFilledNo;`。既有資料列（既有測試 fixture／既有 DB 資料）的 `source` 依 DDL `DEFAULT` 回填為 `'MANUAL'`，`broker_filled_no` 維持 `NULL`。完成後**必須**依 `db/schema.sql` 檔頭「重新產生」段重產該檔（本任務有動 `db/changelog/**`），並執行 `bash scripts/tests/schema-sql-drift-test.sh` 確認回 `0`；非 0 不得視為完成。

- [ ] **385.8 手動驗證端點與獨立 token filter。** 既有 `FubonInternalTokenFilter.shouldNotFilter()` 硬編碼 `private static final String PATH = "/internal/brokers/fubon/inventory-sync"`，對其他 path（含新的 `/internal/brokers/fubon/trade-sync`）一律回傳 `true`（即該 filter 直接放行、完全不驗 token）；**不得**讓新端點掛在既有 filter 下卻誤以為有保護，否則會產生一個無認證即可寫入 `asset_transaction` 財務紀錄的 internal 端點。因此新增**獨立**檔案 `FubonTradeInternalTokenFilter.java`：結構逐行比照 `FubonInternalTokenFilter.java`（同一 constant-time header 比較邏輯、同一 `OncePerRequestFilter`／`shouldNotFilter` exact-path 模式），差異僅：`PATH = "/internal/brokers/fubon/trade-sync"`；`writeUnavailable()` 改綁本任務新增的 `FubonTradeOutcome`／`FubonTradeOutcomeCounters` 與 385.8 定義的 response 形狀（不得複用既有 `FubonOutcome`／`FubonDtos.SyncResponse`）。新增 `FubonTradeSyncController.java`，新增 exact internal `POST /internal/brokers/fubon/trade-sync?dryRun=true|false`（預設 `true`），受上述新 filter 保護，controller 方法**只委派**呼叫 `FubonTradeSyncService.syncManual(dryRun)`（385.5 新增的方法，內部自行重驗 feature gate／configState／日曆，不得改呼叫只信任呼叫端已驗證日曆的 `syncScheduledAfterCalendar`），不得在 controller 內另寫任何 gate 判斷邏輯（業務邏輯一律留在 service 層）。response body 僅 `{"outcome": "...", "dryRun": bool, "batchId": "...", "tradeCount": int, "insertedCount": int, "skippedExistingCount": int, "skippedNameUnresolvedCount": int, "reason": "...", "counters": {...}}`，不回 raw identity、帳號或個別交易明細（stockCode／金額等）。不新增 BFF／frontend／Nginx／gateway route。

- [ ] **385.9 固定 enum outcome 與 counters。** 新增 `FubonTradeOutcome.java`（獨立 enum，**不得**修改或擴充既有 `FubonOutcome.java`，避免混淆既有 inventory sync 的既有值語意與既有測試對該 enum 精確值集合的斷言）：
  ```java
  public enum FubonTradeOutcome {
      DISABLED, TRADE_SYNC_DISABLED, TRADE_SYNC_CAPACITY_CONFLICT, MISCONFIGURED,
      CALENDAR_UNKNOWN, TRADE_FAILED, NO_OWNER, BROKER_MISSING, DRY_RUN, SUCCESS,
      NO_NEW_TRADES, ROLLED_BACK
  }
  ```
  新增 `FubonTradeOutcomeCounters.java`（結構比照 `FubonOutcomeCounters.java`：`EnumMap<FubonTradeOutcome, LongAdder>`，process-local，`@Component`）。不新增 Actuator/Micrometer 依賴，不新增 host/public metrics endpoint；counter 只經 385.8 手動端點的 sanitized `counters` 欄位揭露。

- [ ] **385.10 同步排程列表頁。** `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的 `JOBS` 陣列，於既有「富邦台股現股庫存同步」那筆之後（同一 `(BUSINESS, "券商庫存", ...)` 分類）新增一筆：
  ```java
  new ScheduledJobDto(BUSINESS, "券商庫存", "富邦台股成交紀錄同步",
          "以隔離的富邦官方 Linux SDK 唯讀查詢 configured admin 當日成交紀錄，新增系統尚未記錄的交易到交易紀錄，以富邦成交序號防止重複新增，不覆寫既有紀錄",
          "交易日 09:00–14:00 每 30 分鐘（不含14:30）", "0 0,30 9-13 * * MON-FRI / 0 0 14 * * MON-FRI", TPE),
  ```
  `bff/src/test/java/com/steven/assets/bff/schedulelist/SchedulePublicBffControllerTest.java` 的 `項目數正確()` 測試，總數與 `業務服務`／BUSINESS 分類計數各同步加一（以實作當下該測試現行斷言值為基準往上加一，不得假設本任務檔撰寫時的舊數字仍然正確——先跑一次現行測試確認基準值）。

- [ ] **385.11 自動測試矩陣。** Python：`read_filled_trades` 的 auth-invalid retry／`_accounting_lock` 序列化／385.2 的 attribute-access spy（唯一存取 `filled_history`）；route 層的 date 格式錯誤 400、`startDate>endDate` 400、range>7天 400、raw row 驗證各分支（`order_type!=Stock`、帳號不符、分公司代號不符、日期落在範圍外、買賣別非 Buy/Sell 兩值之一）各自整批 invalid、decimal/qty 邊界（沿用既有 `CanonicalFubonDecimal` 測試模式）、`emptyConfirmed=true` 的合法空批次。Java：`FubonTradeSyncScheduler` 的 feature gate／configState／calendar 三層 short-circuit（比照 `FubonInventorySyncSchedulerTest` 既有寫法）；`FubonTradeSyncService` 的 `localConfigGate`／`syncManual`（自行重驗日曆、`CALENDAR_UNKNOWN` 分支）／`syncScheduledAfterCalendar` 的 defense-in-depth 二次 gate 檢查、`NO_OWNER`（含 configured admin 存在但非 ACTIVE、存在但非 admin 兩個子案例）／`BROKER_MISSING`（含 fubon broker 查無、與存在但 `active=false` 兩個子案例）／`TRADE_FAILED`／`NO_NEW_TRADES`／`DRY_RUN`（`dryRun=true` 時待新增清單非空仍不寫入）各分支、名稱無法解析時跳過該列但不中止整批、已存在（`existsByOwnerUserIdAndBrokerFilledNo`）時跳過不覆寫、新增成功時逐欄斷言 385.6 映射規則（含 `fee`/`transactionTax`/`exchangeRate`/`notes` 精確為 `null`、`source="FUBON_SYNC"`、`brokerFilledNo` 正確）、金額 `setScale(2,HALF_UP)` 精確案例（比照既有 `12.345×3→37.04` 風格自訂至少一組非整除小數案例）。`FubonTradeInternalTokenFilter` 新增獨立測試：正確 token 放行、缺漏／錯誤 token 回既有 filter 同款 4xx/5xx 語意、對 `/internal/brokers/fubon/inventory-sync` 這條 path **不生效**（確認兩支 filter 互不干擾），並在既有 `FubonInternalBoundaryTest.java`（目前既有 `FubonInternalTokenFilter` 邊界測試所在檔案）補一個案例，回歸驗證舊 filter 對 `/internal/brokers/fubon/trade-sync` 這條新 path 仍是 `shouldNotFilter=true`（即舊 filter 對新 path 確實不處理，凸顯新 filter 存在的必要性）。使用真實 PostgreSQL（Testcontainers，非 H2／非 mock repository）驗證 partial unique index：同一 `(ownerUserId, brokerFilledNo)` 併發插入兩次，第二次必須因唯一索引衝突失敗且被 service 層捕捉為「視同已存在」而非拋出。既有 `AssetTransactionService`／`AssetTransactionRepository` 相關既有測試須保持綠燈（`source` 新欄位不得破壞既有手動 CRUD 行為）。`bff` 的 `項目數正確()` 更新後綠燈。

**本輪節拍驗證：** `FubonTradeSyncSchedulerTest` 與 `SchedulePublicBffControllerTest` 必驗兩個 cron 一致；以 CronExpression 列舉有效交易日，精確得到09:00/09:30/10:00/10:30/11:00/11:30/12:00/12:30/13:00/13:30/14:00共11次，排除08:30/14:30，非交易日與未知日曆零SDK呼叫。只驗jar字串不足；不修改inventory原09:05/09:35節拍。

- [x] **385.13 本輪官方日期與原子來源證據修復（取代舊日期假設）。** HTTP 邊界只接受嚴格 `YYYY-MM-DD` 的有效日期（拒基本格式 `YYYYMMDD`、week date、datetime、空白、不存在日期），仍驗 `start<=end` 及差值<=7天；外部HTTP契約不擴為30天。gateway 進入窄 `stock.filled_history` 呼叫前，從已解析日期轉為 `YYYYMMDD`，不能把 ISO 原字串送SDK。官方 raw `date` 只收嚴格有效 `YYYY/MM/DD`、round-trip 相等；轉 date 物件檢查範圍後，normalized `filledDate` 輸出 `YYYY-MM-DD`，Java LocalDate 正常反序列化。request/response 各自日期格式互換錯誤均拒絕，不能 substring/字典序比對或把文件例子的範圍外日期視為放行理由。
  在同一 accounting 臨界區取得 response、真正執行呼叫的 selected account、當次 internal token 的不可變結果；欄位逐一非空且相同後才產生既有 HMAC fingerprint。relogin retry 整組重新取得；不得在呼叫後另讀可變 selected account 來判定先前 response 身分。原帳號及token不跨Python邊界。全域 Python counters 仍精確13鍵，不加新 outcome key。
  fake SDK 正常案例必以實際 `YYYYMMDD` 參數断言、回 `YYYY/MM/DD` 成交，整條 route→Java DTO 可接受；再測 leap day、invalid day、邊界日前後、不同格式拒絕、原子 account/token 在重登入切換、批中任一無效列全批拒絕與 sole `filled_history` attribute access。既有手動 ledger CRUD／source label 不改，因此它仍是可編輯的記帳表，不能被Task395當成不可變供應商證據。

## 驗證

```bash
set -euo pipefail

# 規格機械檢查
bash scripts/spec-check.sh

# Python 測試（fake SDK，含 385.2/385.3 的新分支）
docker buildx build --platform linux/amd64 --target test \
  -f fubon-broker-service/Dockerfile fubon-broker-service \
  --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

# 後端與 BFF 測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 本輪不新增 changeset；唯讀確認既有 schema 同步
bash scripts/tests/schema-sql-drift-test.sh   # 必須回 0

# 實際部署遵循 run-stack：先 feature 驗收，再 commit-merge-push，最後 main 重建。
# 保留已核實的部署設定及既有 flags，不複製本文件的預設值覆蓋 .env。
# 憑證權限未核實時維持 fail-closed；不能為了通過測試啟用真人查詢。

# runtime image 內確認新排程與新欄位確實落地，不只是 source 有寫
docker cp asset-bff:/app/app.jar /tmp/asset-bff-t385.jar
unzip -p /tmp/asset-bff-t385.jar \
  BOOT-INF/classes/com/steven/assets/bff/schedulelist/SchedulePublicBffController.class \
  > /tmp/t385-schedule.class
strings /tmp/t385-schedule.class | rg '富邦台股成交紀錄同步|0 0,30 9-13|0 0 14'

docker exec asset-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\\d asset_transaction"' \
  | rg 'source|broker_filled_no|ux_asset_transaction_owner_broker_filled_no'
```

無安裝真實富邦 secrets 時，`dryRun=false` 的實際新增交易紀錄流程**不得**在真實帳戶上驗證；比照 t352 慣例，以 fake adapter／Testcontainers 完成上述測試矩陣即視為 deterministic 驗收完成，並在完成報告明列「真實富邦成交同步：未執行」。

## 完成報告

下方接手修復報告記錄本輪節拍／日期／同次來源證據的實作與驗收，包含 fake SDK 正常與拒絕路徑、Java DTO、11輪 cron 及實際 image 驗證；真人SDK權限與查詢尚未驗證，不宣稱已執行。

## 2026-08-30 接手日期與節拍修復

本輪Python676、backend1,854、BFF242項完整測試全數通過。HTTP嚴格ISO日期→SDK YYYYMMDD→官方raw YYYY/MM/DD→normalized ISO的正常與拒絕路徑已驗證，response／selected account／token在同一accounting臨界區擷取；成交排程與JOBS精確為09:00至14:00共11次，不含14:30。沒有新增schema、改既有ledger CRUD、補估費稅或把可編輯交易紀錄當Task395來源證明。本輪獨立架構與feature Docker驗收已完成，實際頁面顯示上述11輪節拍；共用證據見Task386接手更新。未測真人filled_history權限，不沿用歷史原版測試作本輪通過依據。
