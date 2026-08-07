# [t290] 台股官方收盤逐檔對帳與盤後顯示防呆

**對應 Requirements:** Requirement 7（市場資料整合：台股正式收盤取得、逐檔完整性、自我修復、公開顯示防呆）
**前置任務:** Task 235（來源日期守門與 DB→Redis 漲跌）、Task 257（per-owner 持股抓價集合）、Task 258（Redis dump 日期／時效守門）、Task 279（非正收盤五道防線）
**Liquibase changeset:** `v1.89.0-stock-price-close-source.sql`（`stock_price_history.close_source VARCHAR(64)` nullable）

## 背景

2026-08-07 盤後實機查證，最新快照／觀察集合共有 20 檔台股，但 `stock_price_history` 當日只有 10 檔；13:32 日誌為「寫入 10、守門跳過 11」，16:00 FinMind 又只成功 4 檔。缺少 `0056、006208、00850、00865B、00881、00882、009804、2330、2881、2885`。Redis 仍保留 11:46–13:18 的 `closed=false` 盤中 payload，而 `/api/market-data/prices` 與 `/api/market-data/live-assets` 無條件優先讀 Redis，導致 Dashboard 把盤中尾值顯示成「今日股價」。逐檔對照 2026-08-07 官方收盤，現有 10 檔中 9 檔不相等；`00850` 只是盤中值恰巧等於收盤，不能視為來源正確。

根因有四個，必須一起修：

1. 台股盤中來源 TWSE MIS 當日發生 60 次 TLS handshake failure，13:32 的 Redis dump 因 Task 258 守門而誠實地只寫部分代號；放寬 12 分鐘只會把更舊的盤中價當收盤。
2. 16:00 FinMind 為逐檔、發佈時點與覆蓋率不穩，本次只補到 4 檔；它不能是唯一的台股正式收盤來源。
3. `selfHealMissedClose` 用 `hasAnyHistoryFor(today,"台股")` 判斷；只要任一檔存在就跳過，無法修復「部分完成」。
4. `PriceQueryService.getLive/getAll` Redis first 不看市場階段、`tradingDate` 或 `closed`；即使 DB 已有正確收盤，前端仍可能被舊 Redis 覆蓋。

已實測的官方全市場來源：

- 上市：`https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=20260807&type=ALLBUT0999&response=json`，根節點 `date=20260807`；「每日收盤行情」table 以欄名提供代號、名稱、成交股數、開高低收。
- 上櫃：`https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes`，每列含 `Date=1150807`、`SecuritiesCompanyCode`、`CompanyName`、`Close/Open/High/Low/TradingShares`。
- `https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL` 在 2026-08-07 晚間仍回 `Date=1150806`，故**不得**用它作當日收盤；本任務只採可指定日期且能驗證根日期的 `MI_INDEX`。

## 要做什麼

- [ ] **290.1 新增 `TwOfficialCloseClient`（external-materials-service）**：
  - 建立 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/TwOfficialCloseClient.java`，以不可變 record `OfficialClose(code, name, tradingDate, open, high, low, close, volume, source)` 與 `OfficialCloseBatch(rows, sourceFailures)` 回傳。
  - 每輪只做兩個 bounded curl：`curl --fail --silent --show-error --max-time 25 -A Mozilla/5.0 URL`；不得逐檔打官方 API。Process 非 0、逾時、JSON 格式錯誤皆記 source failure 並讓另一來源繼續。
  - TWSE URL 必含呼叫端傳入的 `date=YYYYMMDD`；根 `stat` 必須為 `OK`、根 `date` 必須精確等於目標日。於 `tables[]` 以欄名而非固定 table index 找到同時含「證券代號」「收盤價」者，再以欄名 index 解析。數字去千分位；`--`／空白／非數字／`close <= 0` 略過。
  - TPEx 每列 `Date` 由民國 `yyyMMdd` 轉 ISO；只保留精確等於目標日的列。欄位固定讀 `SecuritiesCompanyCode/CompanyName/Open/High/Low/Close/TradingShares`；非正 close 略過。
  - 兩來源代號合併成 `LinkedHashMap`；若異常重碼，記 warning 並以後讀來源覆寫，但不得把日期不符資料放入 map。

- [ ] **290.2 台股正式收盤改為官方逐檔對帳**（`ClosePersister`）：
  - 注入 `TwOfficialCloseClient`，新增不可變 `TwCloseReconciliation(expected, verified, missingCodes)` 與 package-private／public 可測的 `reconcileTwOfficialClose(LocalDate targetDate)`；無參數 scheduled wrapper 只負責以台北 today 守交易日後委派，不把日期藏進核心方法。
  - 每輪以 `StockSourceQuery.collectHeldStockCodes` 取得台股集合後**顯式移除 `0000`**才成為個股 expected set；抓官方 batch 後只處理 expected 交集。大盤永遠不算 expected／missing，也不查 FinMind 個股端點。每筆呼叫 `source.upsertVerifiedHistory(code,"台股",targetDate,...,closeSource)`；成功才呼叫 `cacheWriter.writeVerifiedClose(priceResult, targetDate)`。`closeSource` 與 `PriceResult.source` 統一使用穩定常數 `TWSE_MI_INDEX`／`TPEX_DAILY_CLOSE`。
  - 不採 skip-if-exists：官方有值就 upsert，以便覆寫同日錯價；相同唯一鍵重跑必須冪等。完成後逐檔用 `hasTrustedTwClose(code,targetDate)` 算 coverage，log `targetDate/expected/verified/missing` 與完整缺檔代號；不得用 `existsHistory`。
  - 新增同一方法的兩組 `@Scheduled`：`0 5 14 * * MON-FRI` 與 `0 35 15,17 * * MON-FRI`，zone `Asia/Taipei`，即 14:05／15:35／17:35；非台股交易日整輪 skip。
  - `dumpTwCloseFromRedis` 不再帶 `@Scheduled`、不再由台股 self-heal 呼叫，或改成明確 no-op；13:32 Redis payload 保持 `closed=false` 暫定行情且**不得寫 DB**。美股／英股 `dumpRedisToDb` 與 Task 258 守門逐行不變。

- [ ] **290.3 FinMind 改為缺檔 fallback，self-heal 改逐檔**：
  - `verifyTwCloseWithFinMind(LocalDate targetDate)` 先取 expected set，再以 `source.hasTrustedTwClose(code,targetDate)` 排除官方已完成代號，只查 missing；既有 `getTwClosingPriceFromFinMind(code,targetDate)` 日期相等守門、正價守門、300ms 節流保留，成功列以 `FINMIND_TW_CLOSE` 寫入 provenance。16:00 scheduled wrapper 只在 today 為交易日時委派 today。
  - 台股 `selfHealMissedClose`：若 today 是交易日且 now 尚未到 14:05，candidate=`today-1`；否則 candidate=today，再用 `MarketCalendar.isTwTradingDay` 向前有界解析 target。若 now 已晚於 `targetDate 14:05`，必跑 `reconcileTwOfficialClose(targetDate)`，即使今天是週末／假日或 DB 已有部分列；若已晚於 `targetDate 16:00`，再跑 `verifyTwCloseWithFinMind(targetDate)` missing-only。移除台股 `hasAnyHistoryFor` 布林短路、today 非交易日整段 skip 與 Redis dump fallback。美／英股既有邏輯不在本任務改寫。
  - 跨日、週末或下一交易日盤前部署重啟時，官方 reconcile 必須能覆寫舊程式在最近完成交易日已寫入的錯誤列，不能只補缺列；新增「週六啟動補週五」與「週一 08:00 啟動補週五、不選未完成週一」測試，且絕不建立非交易日 row。

- [ ] **290.4 `writeVerifiedClose` 日期與來源顯式化**（`PriceCacheWriter`）：
  - Liquibase 以 `v1.89.0-stock-price-close-source.sql` 新增 `stock_price_history.close_source VARCHAR(64)` nullable，同步 `db/schema.sql`、`StockPriceHistory` entity 與 master changelog；既有列保持 null，migration 禁止猜測回填。
  - `StockSourceQuery` 新增 `upsertVerifiedHistory(..., closeSource)` 與 `hasTrustedTwClose(code,date)`；台股信任清單只能是 `TWSE_MI_INDEX`、`TPEX_DAILY_CLOSE`、`FINMIND_TW_CLOSE`。舊 `upsertHistory` INSERT 不得填來源，UPDATE 覆寫 OHLCV 時必須同時 `close_source=NULL`，否則新價格會錯誤繼承舊 verified provenance。
  - 美／英既有收盤 dump 與 verify 路徑在叫用上述新方法時必須傳入非空穩定來源；僅做機械適配，不變更抓價策略。
  - 方法簽章改為 `writeVerifiedClose(PriceResult result, LocalDate tradingDate)`；所有台／美／英呼叫點傳入各自已驗證的目標日。
  - payload `source` 取 `result.source()`，不得硬編 `FinMind`；`tradingDate` 取參數，不得由 now 猜；`closed=true` 並加 `quoteStatus="VERIFIED_CLOSE"`。
  - 一般盤中 `write(..., markClosed=false)` 加 `quoteStatus="LIVE"`；`syncClosedFromDb` 加 `quoteStatus="PREVIOUS_CLOSE"`。Task 279 的非正價早退、previousClose／stockName 保留與 SSE publish 行為不變。

- [ ] **290.5 新增 display quote，保留 raw live 行為**（business-services）：
  - `PriceQueryService.getLive/getAll` 保持 Redis-first 原始語意；既有警示／技術指標與交易雷達規則引擎輸入不改，但交易雷達 response 的使用者可見價格不得直接沿用 raw live。
  - `LivePrice` 增加 `quoteStatus`；舊 Redis payload 無該欄時可在 **raw DTO 解析層**由 `closed` 推導 `VERIFIED_CLOSE` 或 `LIVE`，只供 rolling deploy 相容。display gate 與 SSE 今日 overlay 不得把這個推導值當成持久化 provenance；台股盤後仍只認 exact-date trusted DB row。
  - 新增 `getDisplayPrice(code,market)`／`getAllDisplayPrices(requiredKeys)`，依 `MarketZones` 與 `MarketDataService.isTradingDay` 解析不可變 `DisplaySession(phase,targetTradingDate)`：交易日開盤／盤後 target 為 today，盤前 target 為前一交易日，非交易日 target 為最近交易日；向前解析須有界且可測。
    - 交易日開盤中：回當日 raw Redis（status `LIVE`）；Redis miss 可回最近完成日，但 status 必為 `PREVIOUS_CLOSE`。
    - 交易日收盤後：只查 `historyRepo.findByStockCodeAndMarketAndTradingDate(code,market,today)`；且該列 `closeSource` 非空（台股必須命中三個信任常數）才轉成 `VERIFIED_CLOSE`。單純「當日 row 存在」不足以驗證；缺列或來源不信任都合成同代號 placeholder（`price/previousClose/change/OHLCV=null`、`tradingDate=today`、`closed=false`、`quoteStatus=CLOSE_PENDING`），不得回 raw Redis 或最近歷史。
    - 交易日盤前／非交易日：台股只接受 exact `targetTradingDate` 且 trusted source 的 DB row 並標 `PREVIOUS_CLOSE`；缺列／untrusted 則回 `tradingDate=targetTradingDate` 的 `CLOSE_PENDING`，不得跳過目標日拿更舊收盤。其他市場維持既有最近完成日語意。
  - 時間分支抽成 package-private 純函數或可注入 now provider，使開盤／盤後／非交易日測試不依賴牆鐘。
  - `StockPriceService.getAllPrices` 將當前 owner 最新快照持股 `(code,market)` 傳成 `requiredKeys`；`getAllDisplayPrices` 以它和 Redis index 取聯集，但排除 `0000`（TAIEX 走專用路徑），缺 Redis key 的持股也必須出現成 placeholder。`getPrice/getLiveAssets` 同樣改走 display。`StockPriceDto`、`LiveStockItem` 增加 `quoteStatus`；pending holding 的 `currentPrice=null`，`liveValue` 沿用 `StockHolding.currentValue`，不得拿盤中 Redis 或昨收重算。
  - `HistoricalDataService.getPricesOnDate(stocks,date)` 對 requested date 落於 `DisplaySession.targetTradingDate <= date <= marketToday` 的每個**呼叫端指定 key**改走 `getDisplayPrice`，不受 latest snapshot／Redis universe 限制；`SnapshotPriceDto` 增加 `quoteStatus/source`，即使 pending 也回同 key 且 `price=null`。早於 target 才可沿用 `findClosestPrice`。這是 SnapshotForm 新輸入、Dashboard summary／切換快照與 `SnapshotEnricher.fetchSnapshotPriceRows` 的共同最新完成日守門，並涵蓋 `SnapshotDateRollScheduler` 把週末 latest snapshot 釘為週六／週日及週一盤前 requested=today 的情況。
  - `HistoricalDataService.getStockHistory` 對台股最新 display target date 套 provenance gate：開盤中才合成當日 raw live；盤後／盤前／非交易日若 exact target row 不 trusted，從結果移除該 row 且不以 Redis 或更舊價合成。只守最新 target，不因 migration 將舊歷史列全部刪除。
  - `WatchStockService` 使用者可見個股報價改用 display，`WatchStockDto.Response` 加 `quoteStatus`；技術指標內部計算可繼續用 raw live。`SnapshotFormBffController` 今日分支必須優先採用上述 display row，即使 `price=null` 也不得 fallback `hist`，並把 null/status 傳給前端。
  - `TradingRadarService` 評分／action 維持 raw live 輸入，但 `TradingRadarDto.StockDecision` 的可見 `price/changePercent` 改由 display quote 填入並新增 `quoteStatus`；`TradingRadarView.vue` 的初始表格與 SSE 都以 status 守門，pending 顯示「收盤價待補」，不得整晚顯示 last tick。
  - `0000` 不走個股 display universe：抽出共用 TAIEX display resolver，供 `WatchStockService.toIndexResponse` 與 Trading Radar `MarketSummary` 可見點位使用；雷達 regime／score 仍可用 raw live。盤中可用當日 Redis並標 `LIVE`；盤後 exact same-day `twse_index_daily_history` row 標 `VERIFIED_CLOSE`；盤後缺當日 row 回 `CLOSE_PENDING`；盤前／非交易日只接受 exact target 完成日 K並標 `PREVIOUS_CLOSE`，缺 target row 亦 pending。`MarketSummary` 新增 nullable `quoteStatus`；`closed` 只是相容欄位，不可代替 status。
  - `TradingRadarExportService` 的手動欄序同步：大盤總覽與個股決策都新增 TEXT「行情狀態」欄並讀各自 `quoteStatus`；新快照有值，舊 JsonNode 缺欄時 Excel 空白／JSON null。headers／formats／rows 必須同步增為 36／51 欄，不能以「忽略新欄」冒充 legacy 相容。

- [ ] **290.6 Dashboard、WatchStock、SnapshotForm、Trading Radar 明確顯示待補，SSE 不得繞過守門**：
  - `DashboardView.vue` 的 `getRealtimePrice` 對今日基準日只接受：(a) `quoteStatus=LIVE` 且當日；或 (b) **顯式** `quoteStatus=VERIFIED_CLOSE` 且當日。`closed=true` 本身、`PREVIOUS_CLOSE`、`CLOSE_PENDING` 都不得進今日 overlay。
  - 新增 `isClosePending(row)`；股價欄 pending 時顯示灰色「收盤價待補」，不得 fallback 顯示 `row.stockPrice`。
  - SSE `price-update` 的盤後狀態轉移必須單調：現有 entry 為 `CLOSE_PENDING` **或** `VERIFIED_CLOSE` 時，都只有新 payload **已顯式帶** `quoteStatus=VERIFIED_CLOSE` 且日期為該市場今日才可覆寫；`closed=true`、缺 `quoteStatus` 或 `quoteStatus=PREVIOUS_CLOSE/LIVE` 都不得升級或降級。`syncClosedFromDb` 發出的 `PREVIOUS_CLOSE + closed=true` 必須被拒絕，避免昨收繞過 provenance或蓋掉已驗證收盤。
  - `overlayLivePrice` pending 時維持 backend 傳回的快照 fallback value，不用 `stockPrices` 盤中價重算；既有歷史快照凍結規則不變。
  - `refreshMarketStatus` 每分鐘 realtime 回應必須調用同一個 prices/status 套用函式，同時更新 `stockPrices`、`marketStatus`、`liveAssets`；不能僅更新後兩者，否則早已開啟的頁面不會進入 pending。
  - `WatchStockView.vue` 根據 `quoteStatus` 顯示「收盤價待補」，不顯示昨收；`SnapshotFormView.vue` 在批次或 realtime 收到 `CLOSE_PENDING` 時必須明確清空該列 `latestPrice/changePercent`、保留已存表單估值，並顯示待補文案；不得因 `price == null` 就保留前一輪值。
  - `TradingRadarView.vue` 的個股表格與大盤「最新點位」根據 `quoteStatus` 顯示；SSE 只可依與 Dashboard 相同的顯式 status／日期單調規則更新可見價，pending 不顯示 raw live。規則分數、action 與 notification evaluation 的 raw live 輸入不受影響。
  - `SnapshotEnricher` 的 summary、切換快照、look-through 與 SnapshotForm bootstrap 都會讀 `prices-on-date`；今日 display row 為 pending 時不得再由 closest-history 補值，`closeMap` 不含該價、既存 `currentValue` 保留。business live-assets 失敗時亦不得讓 BFF 以歷史價冒充今日。

- [ ] **290.7 排程清單同步**：
  - `SchedulePublicBffController.JOBS` 新增一筆 external job「台股官方收盤對帳」，時間文字 `交易日 14:05、15:35、17:35`、cron 文字 `0 5 14 * * MON-FRI；0 35 15,17 * * MON-FRI`；說明列明 TWSE／TPEx、指定日與逐檔補齊。
  - 舊「台股收盤價落庫」13:32 job 移除；「台股收盤價校正」16:00 改名／說明為 FinMind 缺檔 fallback。external methods/job 數仍 29（`dumpTwCloseFromRedis` 退場、`reconcileTwOfficialClose` 上線），實際 annotation 數為 31（`TwClosurePoller` 與官方對帳各一法兩標）。
  - 同步修正原就漏列的兩個 business fixed-delay job：`AlertNotificationDispatcher` 每 60 秒、`TradingRadarNotificationService` 每 2 秒。最終清單為 **48 筆 = business 19 + external 29**；這是根據實際 annotation 方法核對，不是一增一減後延用錯誤的 46。
  - `SchedulePublicBffControllerTest` 斷言總數 48、business 19、external 29，並新增對三個官方時點、FinMind fallback 與兩個 fixed-delay job 的正向斷言；不得只 grep 舊字串消失。

- [ ] **290.8 測試**：
  - `TwOfficialCloseClient` 的新單元測試：TWSE 欄名定位＋逗號數字、TPEx 民國日期、日期不符 fail-closed、`--`／0 close 略過、單一來源失敗不連坐。
  - `ClosePersister` 的新官方對帳單元測試：expected 3／官方 2 時只 upsert＋cache 2、missing 完整為 1；輸入含 `0000` 時須從 expected 移除且不查官方／FinMind 個股；已有同日列仍由官方覆寫；FinMind 只查 missing；scheduled wrapper 非交易日零外部互動；self-heal 不因任一列存在而短路；週六啟動與週一 08:00 啟動都以週五為 target 跑官方＋FinMind且不建立週末／週一列（可直接測抽出的逐檔／日期 helper，避免等待 thread）。
  - provenance 測試：新 migration 為 nullable 且不回填；舊 null-source 同日列不算 trusted；官方／FinMind 覆寫後 `hasTrustedTwClose=true`；先寫 verified row 再用普通 `upsertHistory` 覆寫時 source 必須清為 null、`hasTrustedTwClose=false`。
  - `PriceCacheWriter` 的新權威收盤測試：顯式 date/source、`closed=true`、`quoteStatus=VERIFIED_CLOSE`；既有非正價測試改呼叫新簽章。
  - `PriceQueryService` 的新 display price 測試：盤中當日 Redis、盤後 exact DB + trusted source、盤後 null/untrusted source 與缺列皆 `CLOSE_PENDING`、週六 exact 週五 trusted 為 `PREVIOUS_CLOSE`、週五 untrusted 為 target=週五 的 pending、批次排除 `0000`；另斷言 `getLive` raw 行為不變。
  - `HistoricalDataService`／BFF 測試：display target date 的任意 requested key（含 SnapshotForm 新輸入且不在 latest snapshot/Redis index）、週六 requested=週六／target=週五 trusted 與 untrusted、週一盤前 requested=週一／target=週五都必回 display row；pending 不走 closest-history；stock history 盤後／週末排除 latest untrusted row、不合成 Redis。覆蓋 Dashboard summary、切換快照、look-through、SnapshotForm bootstrap 與 live-assets 失敗降級，皆保留既存估值。
  - TAIEX display 測試同時覆蓋 WatchStock 與 Trading Radar MarketSummary：盤中 `LIVE`、13:30–完成日 K 寫入前 `CLOSE_PENDING`、盤後 exact index row `VERIFIED_CLOSE`、盤前／非交易日 exact target `PREVIOUS_CLOSE`，並斷言 `closed=true` 不會自行升級 status；雷達規則輸入仍可使用 raw live。
  - `StockPriceService`／既有匯出 fixtures：補新 record 欄位，驗證 pending 不以 raw price 重算，且一檔不在 Redis index 的最新持股仍會出現 placeholder。
  - `TradingRadarService`／匯出測試：raw live 仍進規則計算，但盤後個股與大盤 response 的 visible price/status 取 display pending／verified；`TradingRadarView` 的後到 raw SSE 不得覆寫 pending／verified；新 schema 的兩張匯出表都含「行情狀態」與實值，舊 Redis radar snapshot 缺 `quoteStatus` 時同欄為空白／null 且仍可匯出；鎖定 headers／formats／rows 各為 36／51 欄並逐列一致。
  - frontend 抽出純函式至 `frontend/src/utils/displayQuote.js`，以 Node 內建 `node:test`（`displayQuote.test.js`，不新增 dependency）至少覆蓋：Dashboard 已開頁收到 realtime 後切 pending、pending 收到 `PREVIOUS_CLOSE + closed=true` 無法解除、顯式 `VERIFIED_CLOSE` 可解除、已 verified 收到 `PREVIOUS_CLOSE + closed=true` 不得降級、WatchStock／TradingRadar pending 文案判斷、SnapshotForm pending 會清除舊價。
  - `SchedulePublicBffControllerTest`、frontend build、三個 Maven module 全測試。

- [ ] **290.9 不得做的事**：
  - 不得放寬 Task 258 的 12 分鐘守門，亦不得把 bid／ask midpoint、open、previous close、成交金額÷成交量當收盤。
  - 不得使用沒有 requested-date 契約且實測仍停 T-1 的 TWSE `STOCK_DAY_ALL` 作今日來源。
  - 不得讓 business-services 直接呼叫 TWSE／TPEx；外部 HTTP 仍只在 external-materials-service。
  - 不得因某一檔或某一來源成功就宣告整體完成；coverage 必須逐檔。
  - 不得修改美股／英股收盤取得策略；本任務只為共用 `writeVerifiedClose` 簽章做機械調整。
  - 不得以 nullable `close_source` 的「欄位存在」猜驗證狀態；必須查該列的非空實值，台股更必須命中信任清單。今日正式列的產生者收斂在 `ClosePersister`，display status 由市場階段＋精確日期＋持久化出處決定。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm test)
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
```

依 `run-stack` 從本 feature worktree 重建並 recreate 受影響的四個服務；external／business recreate 後再重啟 BFF：

```bash
docker compose -p asset-management build --no-cache external-materials-service business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services bff frontend
docker compose -p asset-management restart bff
docker compose -p asset-management ps
curl -fsS http://localhost:8080/actuator/health
```

實資料驗收（執行時以 `MarketCalendar` 解析的最近台股交易日為 target；若是週末／假日，啟動 self-heal 必須自動對帳最近交易日）：

```bash
docker logs asset-external-materials-service --since 10m 2>&1 | rg '官方收盤對帳|expected|missing|FinMind'
docker exec asset-postgres psql -U assets -d assets -c "SELECT stock_code, close_price, close_source FROM stock_price_history WHERE market='台股' AND trading_date='<targetDate>' ORDER BY stock_code;"
curl -fsS http://localhost:8080/api/market-data/prices | jq '[.[] | select(.market=="台股") | {stockCode,price,tradingDate,closed,source,quoteStatus}]'
```

驗收標準：以執行當下 `collectHeldStockCodes - 0000` 為 expected（2026-08-07 診斷當時為 20 檔，但不得硬編為永久集合）；逐檔皆須有 targetDate 正價列且 `close_source` 命中信任清單，官方可得者與 TWSE／TPEx 該日檔逐位相同。若 targetDate 是今天盤後，public prices 為 `VERIFIED_CLOSE`；若是週末／假日的最近交易日，則為 `PREVIOUS_CLOSE`，兩者皆須來源正確。若官方／fallback 當下仍缺檔，API 必須回 targetDate 的 `CLOSE_PENDING`／null price 而非盤中價或更舊價，log 必須列出完整 missing codes。

## 完成報告

（實作完成後填寫：修改檔案、測試輸出、容器健康、expected／verified／missing、20 檔官方逐檔比對結果與任何來源降級。）
