# Requirements

## Introduction

資產管理系統（Asset Management System）是一套個人財務資產追蹤與分析平台，協助使用者整合銀行存款、股票、基金等多元資產，提供即時市場資訊、歷史趨勢分析與損益報告，讓個人財務狀況一目了然。

## Requirements

### Requirement 1: 資產快照管理

**User Story:** 作為使用者，我希望能建立與管理特定日期的資產快照，以便追蹤我在不同時間點的淨資產狀況。

**Acceptance Criteria:**

- [ ] 使用者可以建立新的資產快照，並指定快照日期
- [ ] 系統自動計算該快照的總資產（存款 + 基金 + 股票）
- [ ] 使用者可以查看所有快照列表，依日期降序排列
- [ ] 使用者可以查看特定快照的明細，包含銀行存款、股票持倉、基金持倉
- [ ] 使用者可以編輯或刪除現有快照
- [ ] 同一日期僅允許一筆快照，若已存在則提示衝突
- [ ] 快照建立時可記錄當日美元匯率，用於美股換算
- [ ] 新增快照時，選擇日期後系統自動查詢並填入該日歷史匯率；日期改變時匯率隨之更新
- [ ] 編輯模式（管理資產）與新增模式行為一致：股票表格「股價/漲跌(%)」欄套用 per-market 基準日規則 —— 基準日 = 該市場時區的今日 → 顯示即時價＋漲跌，每 2 分鐘輪詢更新；其他日期 → 顯示基準日歷史收盤，**並一併顯示該收盤日的「當日漲跌」**（＝該收盤日收盤 vs 前一交易日收盤，來自 `prices-on-date` 的 `priceChange`／`changePercent`；`SnapshotFormBffController.enrichBatch` frozen 分支改用 `hist` 的漲跌而非留空），與 Dashboard 同一規則同一來源。配色台股慣例：漲紅（`#dc2626`）、跌綠（`#16a34a`）。設計理念：使用者多在當日盤後建檔／微調快照，看得到當日漲跌幫助判讀；非今日的舊快照雖無 live cache，仍可由歷史收盤算出當日漲跌
- [ ] 管理資產頁上方資產彙整列（總資產 / 存款 / 台股現值 / 美股現值 / 共同基金現值 / 預估年配息）必須**內部自洽**：「總資產」一律 = 存款 + 台股 + 美股 + 基金（顯示值的直接加總），不得讀 stored `totalAssets` 後與分項並列；否則 stored 與分項來源不同步時 bar 上四項加起來會不等於總資產
- [ ] 進編輯頁（既有快照）時，KPI「總資產」**首次 paint 即須為收盤價口徑**，不得先顯示快照凍結的暫定價（舊值）、待 `loadAllPrices` 回來才跳成正解。`onMounted` 載入 detail 後須立即以 `get` 回應已附帶的 `mergedStocks[].stockPrice`（與 `loadAllPrices` 同一 `closeMap` 來源）填 `latestPrice`，使股票現值在 `loadAllPrices` 之前就以「收盤價 × 股數」計算 —— 兩段 await 之間不得出現舊值閃動
- [ ] 「歷年資產管理」表格最新一筆套 **per-market 基準日閘門**：台股 / 美股 / 英股各自「snapshotDate == 該市場時區今日」時，該市場的現值才以 live-assets 計算結果覆蓋 stored 值（連動資產總計 / 增加金額 / 增幅 / 投資比例），與 Dashboard 即時資產顯示同一個數字；**最新一筆為過去日期（三市場皆非今日，如昨日快照、今日尚未建檔、刪除今日快照後最新退回昨日）時，整列保留快照凍結收盤值（= 該基準日收盤），不得用 live-assets（讀 Redis = 最新收盤）覆蓋**，否則會把較新交易日的收盤洩漏到一個過去基準日上（例：最新快照 6/23、今日 6/24 已有收盤，6/23 列卻顯示 6/24 收盤）。理由：快照建檔當下凍結的 `stock_holding.currentValue` 對「已收盤定案的過去日期」本來就 == 該日收盤，過去日期保留凍結值即為正解

### Requirement 2: 銀行存款追蹤

**User Story:** 作為使用者，我希望能記錄多個銀行帳戶的存款資訊，以便掌握各銀行的資金分配。

**Acceptance Criteria:**

- [ ] 銀行清單由系統設定動態管理，不寫死於程式碼（詳見 Requirement 11）
- [ ] 每筆存款需記錄：所屬銀行、帳戶類型、金額（台幣或美元原始金額）、台幣換算金額
- [ ] 存款類型由系統設定動態管理，不寫死於程式碼（詳見 Requirement 12）
- [ ] 系統彙總各銀行存款並計算占總資產比例
- [ ] 可視化呈現各銀行存款分佈（圓餅圖、長條圖）
- [ ] 每筆台幣／美元存款可記錄「年利率」（百分比，1.5 表示 1.5%）；在途款項不適用此欄位
- [ ] 系統依「金額 × 年利率 / 100」自動算出該筆「預估年利息」（USD 透過 `amount`（已換算 TWD）一致以台幣等值呈現），不另存於 DB
- [ ] 每筆存款的預估年利息加總後，併入快照層級的 `estimatedAnnualDividend`，與股票／基金的預估配息一起彙總
- [ ] 存款明細頁台幣 tab 底部彙總列新增「預估年利息」一欄
- [ ] Dashboard「預估年配息」卡片的殖利率分母同步納入存款本金：`殖利率 = estimatedAnnualDividend / (totalStockValue + totalFundValue + totalDeposit)`。理由：分子已含存款預估年利息，分母也須含存款本金口徑才一致（否則殖利率被高估）

### Requirement 3: 股票持倉管理

**User Story:** 作為使用者，我希望能記錄台股與美股的持倉資訊，並取得即時市價計算損益。

**Acceptance Criteria:**

- [ ] 市場類型由系統設定動態管理，不寫死於程式碼（詳見 Requirement 12）
- [ ] 每筆持倉記錄：股票代號、股票名稱、股數、成本均價、股利率
- [ ] 系統從市場 API 取得即時股價，計算當前市值
- [ ] 系統計算每筆持倉的未實現損益（現值 - 成本）
- [ ] 美股持倉以台幣換算顯示（依快照匯率）
- [ ] 券商清單由系統設定動態管理，不寫死於程式碼（詳見 Requirement 11）
- [ ] 顯示股票所屬券商
- [ ] 自動查詢並快取每支股票的現金股利率
- [ ] 每筆持倉可記錄交易類型（買/賣）與交易日期
- [ ] 美股持倉若有記錄交易日期，系統自動查詢並套用該日歷史匯率計算台幣成本；無交易日期則沿用快照匯率
- [ ] 快照彙總的股票總成本（`total_stock_cost`）與由其衍生的股票損益，必須為各持股「台幣成本」之和：美股 USD 計價列須先依交易日匯率（無則快照匯率）換算台幣再加總，**不得將美元金額與台幣金額混加**（混加會使總成本嚴重低估、損益虛高）；逐筆持股損益亦同。提供 `POST /api/snapshots/recalc-totals` 重算既有快照以修正歷史偏差

### Requirement 4: 基金持倉管理

**User Story:** 作為使用者，我希望能記錄信託基金的投資狀況，以掌握基金的獲利情形。

**Acceptance Criteria:**

- [ ] 每筆基金記錄：基金名稱、投入成本、當前市值
- [ ] 系統計算基金的未實現損益
- [ ] 基金資料可在快照中新增、編輯、刪除

### Requirement 5: Excel 批次匯出

**User Story:** 作為使用者，我希望能將資料匯出成 Excel，作為備份與後續離線分析之用。

**Acceptance Criteria:**

- [x] 匯出成 `.xlsx`
- [x] 每個快照產生一張 sheet，名稱格式 YYYYMMDD
- [x] 每個 sheet 內依序顯示：頂部摘要（日期/匯率/總資產）、銀行存款區塊、基金區塊、股票區塊
- [x] 已實現損益單獨一張 sheet（資產名稱／代號／交易日期／股數／賣出均價／收帳金額／投資成本／損益／報酬率／市場／幣別／券商／匯率／年度）
- [x] 提供 `GET /api/snapshots/export`（完整匯出）與 `GET /api/realized-gains/export`（僅損益）
- [x] 前端在「資產快照」、「歷年資產」、「已實現損益」頁面提供「匯出 Excel」按鈕

> ⚠️ Excel 批次匯入功能已停用（按鈕改為匯出），原 ExcelImportService 保留但 controller 端點移除。

### Requirement 6: 已實現損益追蹤

**User Story:** 作為使用者，我希望能記錄每筆股票買賣的已實現損益，以分析年度投資績效。

**Acceptance Criteria:**

- [ ] 記錄每筆交易：股票代號、名稱、市場、損益金額、年度
- [ ] 依年度彙總損益並計算獲利率
- [ ] 列表可依年度篩選
- [ ] 支援手動新增、編輯、刪除交易記錄
- [ ] 支援匯出 Excel（見 Requirement 5；批次匯入功能已停用）

### Requirement 7: 市場資料整合

**User Story:** 作為使用者，我希望系統能自動取得市場資料，以提供即時的資產估值。

**Acceptance Criteria:**

- [ ] **股價一律是成交價、且每筆 tick 只能由真實成交推進**：系統內所有顯示的股價（盤中、收盤、走勢圖、Dashboard、管理資產、即時資產估算等所有功能）必須是真實成交過的價格，不得使用買賣中價、買價、賣價、開盤推估或任何衍生估算值。**規則僅兩條**：(1) **每天開盤，如果抓不到最新值 → 顯示昨日收盤價**（Redis 為空時 `PriceQueryService.getLive` 自然 fallback 至 `stock_price_history` 最近一筆收盤）；(2) **每次 cron tick，如果抓不到值（TWSE `z='-'` 或外部 API 查無）→ 不要更新 Redis**（`getStockPrice` 回 `Optional.empty()`、`PricePoller` skip write，保留上一輪成功 poll 寫入的真實 z）。**禁止** 用 `y`（昨日收盤）或 `o`（今日開盤）回寫 Redis — 不誠實地讓使用者誤以為這是「最新成交價」。走勢圖今日格 `getStockHistory` 仍在 `LivePrice.source` 含括號（如 `(history)`）時略過，確保拼入今日格的一定是該日真實成交價
- [ ] 從 NASDAQ API 取得美股即時股價；台股股利率改由 FinMind / TWSE BWIBBU 取得（Yahoo Finance 已停用）
- [ ] 自動偵測股票市場（先嘗試 .TW，失敗則嘗試 .TWO）
- [ ] 股價資料快取以減少外部 API 呼叫次數
- [ ] 從央行或第三方 API 取得美元/台幣歷史匯率
- [ ] 交易日曆顯示台股與美股的開收盤狀態
- [ ] 台股（週一～五 09:00～13:30 Asia/Taipei）與美股（週一～五 09:30～16:00 America/New_York，含 EST/EDT 夏令切換）採**獨立 cron 排程**，各自每 2 分鐘更新一次股價快取；非該市場交易時段不空轉、不混用單一 fixedRate
- [ ] **國定假日整段休市：抓價排程與收盤持久化在假日完全 skip**。各市場 cron 雖以 `MON-FRI` 觸發，平日仍可能是國定假日（美股 Juneteenth 6/19、Memorial Day、Thanksgiving…；台股農曆春節等；英股 Bank Holiday），不可只判週末。`MarketClock.isXxxMarketOpen` / `isXxxMarketJustClosed` 除平日 + 時段外，必須再判「當日非該市場國定假日」；假日時 `PricePoller` 不呼叫外部行情 API、不寫 Redis、不 PUBLISH `price-update`，`ClosePersister`（dump / FinMind・Yahoo verify / `selfHealMissedClose`）亦整段 skip，避免把假日「前一交易日最後可得價」誤標成假日當日 tick / 收盤，污染 Redis 與 `stock_price_history`。假日判定權威來源：台股 = TWSE OpenAPI holidaySchedule（`MarketDataFetchService.getTwHolidays`）；美股 / 英股 = NYSE / LSE 法定規則純函式（ext-materials `MarketCalendar`，與 `business-services` `MarketDataService.getUsHolidays/getUkHolidays` 為同一套規則 —— 兩服務無共用 library 且不可循環依賴，故各持一份，修改須同步）
- [ ] 收盤時（台股 13:30、美股 16:00）將當日收盤價寫入 `stock_price_history`
- [ ] **`stock_price_history` 的「今日列」（市場時區當日）只能由 `ClosePersister` 在收盤後路徑寫入**（13:32 TW Redis dump / 16:02 ET Redis dump / 16:00 TW FinMind verify / 18:00 ET FinMind verify / `selfHealMissedClose` 已守門過收盤時點）。`HistoricalBackfillService` 任何路徑（`startupBackfill`、`/internal/backfill/stock`、`/internal/backfill/all`、`SnapshotFormBffController.triggerBackfillThenRefetch` 觸發等）**禁止寫入該市場當日 row**：即使外部 API（Yahoo Finance `interval=1d`）在盤中也會回傳一根「今日 partial bar」（open/high/low + 此刻 last trade 當 close），會被前端誤當作收盤、與 Redis 即時 tick 脫鉤。實作上由 `HistoricalBackfillService` 在 for-loop 跳過 `bar.tradingDate() == LocalDate.now(market TZ)` 的 bar
- [ ] FinMind 盤後校正（台股 16:00、美股 18:00）覆寫 `stock_price_history` 時，必須同步覆寫 Redis `price:{market}:{code}` cache，並 PUBLISH `price-update` 給 SSE 訂閱者。動機：盤中最後一輪 cron 通常落在收盤前 2 分鐘（13:28 / 15:58），抓到的是 last tick 而非集合競價收盤；若只更新 DB，Dashboard / SnapshotForm 在 `basedate == 今日` 時讀 Redis 仍看到 last tick，與「歷年資產管理」（讀 DB）顯示的收盤對不起來
- [ ] **FinMind 校正後的 Redis 收盤值不得被「盤外 / 國定假日的對外重抓」蓋回 last-tick**。`PricePoller.refreshAll`（前端 `/realtime` 輪詢觸發 `/internal/refresh`）與啟動 `warmCacheOnStartup` 在**市場休市時禁止對外抓價覆寫 Redis**；改以 `stock_price_history` 最近收盤同步 Redis（`PriceCacheWriter.syncClosedFromDb`），確保「Redis 收盤 == DB 收盤」。否則休市日刷新會把 FinMind 權威收盤（如美股 689.20）蓋成盤前 last-tick（688.11），導致走 Redis 的頁（Dashboard KPI / 歷年，經 `liveAssets`）與走 DB 的頁（SnapshotForm / Dashboard 表格，經 `closeMap`）對同一快照顯示不同總資產。權威值一律以 DB（FinMind 官方收盤）為準
- [ ] 歷史收盤價保存至少 10 年（透過 FinMind / TWSE 回補；Yahoo Finance 已停用）
- [ ] 抓價子系統獨立為 `external-materials-service` 微服務（獨立 image / container），盤中 2 分鐘 cron 將 live 價寫入 Redis（key `price:{market}:{code}`，TTL 24 小時 — 確保流動性低的 ETF / 個股 z='-' 連續多輪後該值仍持續活著，不會 TTL 過期被退回昨收），盤後將收盤價寫入 `stock_price_history`
- [ ] `business-services` 不再直接呼叫外部行情 API；live 股價一律先讀 Redis、miss 則 fallback 至 `stock_price_history` 最近一筆收盤；歷史收盤價直接讀 DB
- [ ] 市場開收盤狀態由 `external-materials-service` 維護並寫入 Redis（key `market:status`），各 BFF 透過 `business-services` 統一讀取
- [ ] `POST /api/market-data/prices/refresh` 改由 `business-services` 內部呼叫 `external-materials-service` 的觸發端點，價格刷新後再從 Redis 回讀
- [ ] 股利歷史（FinMind TaiwanStockDividend）每日由 cron 同步寫入 `stock_dividend_history`；`/api/market-data/dividends` 端點直接讀 DB，不再每次開啟對話框都打 FinMind
- [ ] 凡列入 `stock` 主檔的股票（含曾持有、觀察清單、設有警示）皆自動納入 10 年歷史收盤價回補與每日排程更新範圍；新增觀察股票時即同步寫入 `stock` 主檔
- [ ] **新標的首次寫入 `stock` 主檔時，即時於背景觸發一次 10 年歷史回補**，不必等到下次服務重啟的 `HistoricalBackfillService.startupBackfill` 才掃描補齊（否則兩次重啟之間新增的標的會落入空窗：即時價有、走勢圖歷史卻只有今日一格，例 2026-06 新增英股 IB01）。所有寫入主檔的路徑（`StockAlertService.create/update`、`AssetService.createSnapshot/updateSnapshot`、`StockAlertController.lookupName`）統一改走 `StockMasterService.upsert`，由它判斷「先前不存在＝新標的」後以單執行緒佇列序列化呼叫 `HistoricalDataService.backfillSingleStock`（proxy 至 ext-materials `/internal/backfill/stock`，今日列仍獨佔給 `ClosePersister`；既有標的名稱更新不重複回補）；台股大盤 0000 不在此列（歷史走 `twse_index_daily_history`）
- [ ] **回補清單除 `stock` 主檔 ∪ `stock_holding` 外，另含「ETF 透視圓餅圖 top10 成份股」**（Task 129，詳見 Requirement 9）：使用者持有 ETF（如 0050）時，資產配置圓餅圖會把 ETF 穿透成個股（如 2317 鴻海）並開放點擊看走勢，但這些純穿透成份股不在主檔／持股／警示中，故須額外納入回補才有歷史可查。此清單**僅含 top10、且不寫入 `stock` 主檔**（僅補歷史日線、不納入即時抓價集合），以兼顧「成份股可分析」與「主檔／即時抓價清單保持精簡」
- [ ] 提供 `GET /api/market-data/live-assets` 端點：以最新快照持倉 × 當前快取股價，即時計算總資產估值

### Requirement 8: 資產歷史趨勢分析

**User Story:** 作為使用者，我希望能查看資產隨時間的變化趨勢，以評估整體財務成長。

**Acceptance Criteria:**

- [ ] 以折線圖呈現總資產歷史變化
- [ ] 計算相鄰期間的資產變化量與變化率
- [ ] 顯示投資資產占總資產的比例趨勢
- [ ] 支援依時間範圍篩選歷史資料

### Requirement 9: 儀表板總覽

**User Story:** 作為使用者，我希望在進入系統後能立即看到最新的資產總覽，以快速掌握財務狀況。

**Acceptance Criteria:**

- [ ] 顯示最新快照的總資產、存款、投資金額等 KPI 卡片
- [ ] 圓餅圖呈現資產類別分配，分 5 區：台幣存款、美元存款、台股、美股、信託基金。圓餅圖跟著選中快照（右上下拉或點擊趨勢圖選取）顯示該快照分配。卡片副標題同步顯示對應日期。值為 0 的區段自動隱藏。資料來源：
  - 最新／選中快照：存款拆分依前端 `bankSummary`（`BankDeposit.currency` USD vs TWD/null，TRANSIT_TWD/TRANSIT_USD 各歸對應幣別），股票拆分依 `liveLatest.totalTwStockValue / totalUsStockValue`
  - 歷史時間點：`AssetHistoryResponse` 預先帶 `totalTwdDeposit / totalUsdDeposit / totalTwStockValue / totalUsStockValue / totalFundValue`，由後端 `getAssetHistory()` 聚合（amount 已是台幣等值）
- [ ] **點擊趨勢圖某點 = 選取該快照，整個 dashboard 一次切換**：點趨勢圖任一時間點，走 `onSnapshotChange`（與右上下拉**同一支 BFF**：`bffApi.dashboard.snapshot(id)`），把 `selectedSnapshotId` 切到該快照，使 KPI 五卡、資產配置圓餅、各銀行存款圖、持股明細表、趨勢圖選取標記**一次全部**切到該日。前端以 `@updateAxisPointer` 追蹤游標所在點 index、`@click` 提交。不採「滑過即時預覽」（左下明細需載入無法逐次跟，避免半套不一致）
- [ ] **趨勢圖一律顯示完整歷史、不以基準日收合**：趨勢線圖資料源為完整 `store.history`（非以選中快照為界的 `filteredHistory`），點選某日只切換 dashboard、不改變此圖範圍，可連續點不同日期回看；以一條虛線 `markLine` 標示目前選中日。（KPI「較上次」仍以 `filteredHistory` 取選中日前一筆比較）
- [ ] 顯示台股與美股的持倉市值
- [ ] 「歷年資產管理」(`/asset-history`) 表格與兩張圖（總資產趨勢、堆疊長條）的「存款」欄位拆成「台幣存款」、「美元存款」兩欄／兩條線／兩堆疊段；資料來源 `AssetHistoryResponse.totalTwdDeposit / totalUsdDeposit`
- [ ] 資產歷史趨勢線圖簡化為 3 條線：總資產、存款、投資（投資 = `totalFundValue + totalStockValue`）；不再分顯示基金 / 台股 / 美股（細分留給資產配置圓餅圖）
- [ ] 點擊指定股票時，能顯示該股價走勢，包含月線、季線、年線
- [ ] 顯示預估年化股利收入
- [ ] 每個 KPI 卡片顯示相對上一期的變化量
- [ ] 顯示「即時資產估算」區塊：以最新股價計算當前總資產，交易時間內每 2 分鐘自動刷新，收盤後顯示收盤估值
- [ ] 股票持股表格的「股價/漲跌(%)」欄需依右上角基準日（所選快照的 snapshotDate）**per-market** 判斷：
  - 台股：`基準日 == LocalDate.now(Asia/Taipei)` → 顯示即時股價＋漲跌金額＋漲跌%（格式：`▲$X.XX (Y.YY%)`），每 2 分鐘自動更新，資料來源與「觀察股票」一致
  - 美股：`基準日 == LocalDate.now(America/New_York)` → 顯示即時股價＋漲跌金額＋漲跌%（EST/EDT 由 JVM `ZoneId` 自動處理）。設計目的：使用者多在 TW 收盤後建快照，TW 過午夜後若以 TW 今日比對會把美股盤中（TW 凌晨）誤判為非交易時間
  - 其他情形（收盤 / 週末 / 非該市場當日，即最新快照為過去日期）：顯示該快照保存的收盤價（`snapshotStockHolding.stockPrice`），並**一併顯示該收盤日的「當日漲跌」**（＝該收盤日收盤 vs 前一交易日收盤，格式同 live：`▲$X.XX (Y.YY%)`），不參與輪詢更新。當日漲跌由 BFF 於 `stockPrices` 內預先算好（`prices-on-date` 回傳 `priceChange`／`changePercent`，`SnapshotEnricher.mergePerMarketPrices` 併入 frozen entry）；前端僅在「所選快照 == 最新快照」（`stockPrices.tradingDate == 該列 snapshotDate`）時採用，選歷史快照時該 map 不對應則只顯示收盤價。無前一交易日資料時漲跌留空、僅顯示收盤價（優雅降級）
  - **漲跌配色採台股慣例：上漲紅（`#dc2626`）、下跌綠（`#16a34a`）、平盤灰（`#94a3b8`）**，與股票走勢圖 markPoint「紅漲綠跌」一致；與損益 / KPI 卡的「綠獲利、紅虧損」為不同語意、刻意不統一。此規則同時套用於 live 與 frozen 兩情形
- [ ] 持股表「現值 / 損益 / 預估配息」與下方市場小計的收盤價重算（`buildMergedStocks(revalueFromClose=true)`，`currentValue = 股數 × 收盤價 ×（美股/英股）匯率`）**套 per-market 基準日閘門**（Task 122）：**僅「基準日 == 該市場時區今日」的市場才重算**（建檔當天盤中暫定價 refresh 成當日收盤，避免「現值沿用暫定價、股價欄讀較新收盤」造成台股總值偏差 —— 即 Task 108 修的今日列情境）；**過去日期的快照一律保留 stored `currentValue`**，與「歷年資產管理」（讀 stored）逐欄同源。理由：過去快照的 stored `currentValue` 對台股已 == 該基準日收盤（實測 diff 0），revalue 對舊資料（缺收盤 / 快照匯率為 null）反而不可靠；統一以 stored 為過去日期的單一事實來源。**已知並接受的代價**：過去快照美股列「現值（stored）」與「股價（基準日收盤）× 股數 × 匯率」可能差 ~1%（建檔當下價/匯率與定案收盤的時間差）。會存檔的編輯頁（SnapshotForm / SnapshotDetail）維持 `revalueFromClose=false`，不被覆寫
- [ ] 切換基準日後，KPI 卡片、資產配置圓餅圖、銀行存款圖、持股圖與股票持股表格都需跟著基準日（所選快照）變動；不可被 2 分鐘的價格輪詢覆蓋回「最新快照」
- [ ] 資產歷史趨勢圖一律顯示**完整歷史**（不以基準日收合），以虛線 `markLine` 標示目前基準日；點擊任一點即選取該快照切換整個 dashboard。KPI「較上次」變化率仍以 `filteredHistory`（`snapshotDate <= 基準日`）的前一筆比較
- [ ] 股票持股表格下方顯示該市場小計：目前總值、投資成本、損益（含 %）、預估配息、持股數，與管理資產（SnapshotForm）的市場小計欄位一致
- [ ] 基準日 == 今日（市場時區）時，2 分鐘輪詢回傳的 live price 需即時驅動持股表的「現值 / 損益 / 預估配息」、KPI「資產總計／股票現值／預估年配息」、以及下方市場小計重算（依 `shares × livePrice ×（美股）匯率`），不可只更新股價欄位而值欄位停留在快照儲存值。**不得加上「市場開盤」額外閘門**：該市場收盤後 Redis 仍保留當日最後一筆成交價（前一交易日收盤），KPI 必須使用該值，才能與「歷年資產管理」今日列（透過 `/api/market-data/live-assets`）顯示相同總資產數字
- [ ] 基準日 == 今日（市場時區）時，「持股明細」橫條圖 tooltip 的「股價」欄必須與同一 tooltip 的「現值」**同源** —— 一律覆寫為即時原幣成交價（與持股表「股價」欄同一來源 `getRealtimePrice`），**不得**殘留快照儲存的收盤價（`previousClose`）。否則同一 tooltip 會出現「現值 = 股數 × 即時價」、但「股價」仍顯示舊收盤的矛盾（如台積電現值以 2,510 計算、股價卻顯示 2,410）。實作：前端 `overlayLivePrice` 在套用即時估值時一併覆寫 row 的 `stockPrice`；即時價抓不到（`getRealtimePrice` 回 null）時保留快照收盤價
- [ ] **全市場皆非交易日今日（最新一筆為過去日期：昨日快照／今日尚未建檔／刪除今日快照後最新退回昨日）時，KPI「資產總計／股票現值」與資產趨勢圖最後一點必須顯示該基準日的收盤值，且與「歷年資產管理」同一筆完全同值**（per-market 基準日閘門，見 Task 121）。**禁止用 `/api/market-data/live-assets` 覆蓋**：`live-assets` 讀 Redis = 最新一筆 tick / 收盤，當最新快照日 < 今日且已有更新交易日收盤時，覆蓋會把較新交易日的收盤洩漏到過去基準日（如最新快照 6/23、今日 6/24 已收盤，6/23 列卻顯示 6/24 收盤）。前端 `liveLatest` 對三市場各自 `basedate == 該市場當地今日` 判斷，三市場皆非今日 → 一律保留快照凍結收盤值（per-market `sumOf`，不再走已移除的 `overlayLatestFromLiveAssets`）；BFF `dashboard/summary` 的 `history` 與 `asset-history` 共用 `LiveAssetsOverlay.applyToLatest` 套同一 per-market 閘門。**僅當最新一筆 snapshotDate == 今日時**才用 live 覆蓋（建檔當下可能是盤中暫定價，需以 live 收盤 refresh，見 Task 108/109）
- [ ] Dashboard 的 `/api/bff/dashboard/realtime` 每次輪詢需先 trigger 後端 `/api/market-data/prices/refresh` 主動向 Yahoo 拉最新行情，再回傳；不可僅讀取 cache（與 SnapshotForm `/realtime` 一致，避免後端 cron 漏跑時前端看到舊值）
- [ ] 顯示「信託基金」橫向長條圖（與「持股明細」並列），y 軸基金名稱、x 軸現值，依現值升冪排序，bar 顏色依損益正負（賺綠、賠紅），下方顯示總值 / 成本 / 損益（含 %）小計；資料來源 `latestSnapshotDetail.funds`，無 BFF 額外彙總（基金值已存於快照，不隨輪詢跳動）；無基金資料時卡片仍顯示，內容為「尚無基金資料」
- [ ] KPI 卡片列加入「信託基金」一張（共 5 張：資產總計 / 存款總計 / 信託基金 / 股票現值 / 預估年配息），改用 flex 平均分配寬度。`sub` 顯示「損益 ${...}」並依正負上色（綠/紅）
- [ ] 「資產配置分佈」面板提供 tab 切換：
  - tab 1「資產類別」：維持原 6 區圓餅圖（台幣/美元存款、台股、美股、英股、信託基金）
  - tab 2「台股個股」：顯示台股部位穿透 ETF 後的前 10 大個股 + 1 個「其它」segment 圓餅圖
  - 穿透規則：台股 ETF（`stockCode` 以 `00` 開頭）依成分股權重**正規化**分配整筆 `currentValue`（`cv × weight / Σweight`），使每檔 ETF 完全穿透成個股、不殘留「ETF 自身」slice（成分股來源常只揭露前 N 大未滿 100%，正規化後未揭露尾段按已揭露分布等比攤入）；直接持股不拆解；同檔個股一律合併（不同 ETF 持有的同檔 + 直接持有的同檔）。成分股來源 MoneyDJ 只揭露股名無代號，故以**股名**為聚合鍵（圓餅圖亦以股名顯示）
  - ETF 成分股查詢**完全失敗**（查無任何成分股）時，該 ETF 才退回以自身名稱合併計算（避免漏算總金額），並在面板底部顯示降級註記
  - 圓餅圖環標籤/圖例只顯示股名 + 佔比（`{b}\n{d}%`）；hover tooltip 顯示「股票代號 股名」+ 金額 + 佔比
  - 成分股代號補齊：MoneyDJ 只給股名，ext-materials 以記憶體「股名→代號」字典（TWSE `STOCK_DAY_ALL` 上市 + TPEX 上櫃，24h cache）補上代號；**不**寫入 `stock` 主檔（該表是 `StockSourceQuery.collectAllStockCodes` 抓價排程清單，灌入全市場會造成排程爆量）
  - 切到 tab 2 才向 BFF lazy fetch（避免拖慢 `/summary` 首屏）；同一 snapshot 切回再切回時用前端 cache，不重打
  - 切換基準日（snapshot）時 tab 2 重新 fetch 對應 snapshot 的穿透結果
  - hover 右側趨勢圖某時間點時，tab 2 圓餅圖亦同步切換顯示該時間點對應 snapshot 的穿透結果（與 tab 1「資產類別」一致的 hover 連動行為）；卡片副標題日期同步切換；未 hover 時回到所選快照。hover 對應的 snapshot 命中前端 cache 時即時切換、未命中則 lazy fetch（race 防護：fetch 回來時若目前的 effective snapshot 已不是該筆，不覆寫畫面）
  - tab 3「美股個股」：顯示美股部位穿透 ETF 後的前 10 大個股 + 1 個「其它」segment 圓餅圖；操作行為（lazy fetch、切快照重抓、hover 趨勢圖連動、前端 per-snapshot cache）與 tab 2 完全一致；無美股部位的快照顯示「此快照無美股部位」
  - 穿透規則（與台股**不同**）：美股 ETF（external `US_ETF_WHITELIST`：VOO/QQQ/SPY/VTI/SCHD…）成分股來源 Yahoo `topHoldings` 僅揭露**前 10 大**（權重總和常僅 30~50%），故依**真實權重**分配 `currentValue × weight/100`（**不正規化**）；未揭露尾段（`currentValue × (1 − Σweight/100)`）不臆測其分布、全數計入「其它」（這是與台股「正規化完全穿透」的關鍵差異，因台股 MoneyDJ 給完整成分股、美股 Yahoo 只給前 10 大）。直接持股不拆解；同檔個股一律合併（不同 ETF 持有的同檔 + 直接持有的同檔）
  - 聚合鍵用**代號**（Yahoo 成分股有 `symbol`，且成分股英文全名與持股中文名難對齊；與 tab 2 台股以股名聚合不同）；圓餅圖環標籤/圖例顯示代號 + 佔比，hover tooltip 顯示「代號 名稱」+ 金額 + 佔比
  - 有 ETF 被成功穿透（`lookthroughEtfCount > 0`）時，面板底部固定顯示註記「美股 ETF 僅揭露前 10 大成份股，其餘已計入『其它』」，避免使用者對偏大的「其它」佔比困惑；美股部位若全為直接持股（無 ETF）則不顯示此註記
  - tab 2 / tab 3 圓餅圖的個股 segment **可點選**：點某檔個股（非「其它」聚合段）即開啟 `StockAnalysisDialog`（與表格列雙擊、bar 圖雙擊同一元件、同一行為）。台股段以股名顯示、`code` 帶代號；美股段以代號顯示、`fullName` 帶名稱；dialog 入參 `{ stockCode, stockName, market }` 由該 segment 還原。若該檔為使用者直接持有的個股（命中當前快照 `mergedStocks` 同 `stockCode`+`market`），沿用完整持股列以保留 `avgCostOriginal` 等成本欄位（走勢圖可畫「成本均價」參考線）；若為純 ETF 穿透成分股（未直接持有）則無成本欄位、不畫成本線。「其它」聚合段無代號，點擊不開 dialog
  - **ETF 透視圓餅圖 top10 成份股的歷史收盤價納入回補**（Task 129；解決點純穿透成份股如 `2317` 鴻海時走勢圖空白、顯示「無歷史資料，請先執行股價補齊」）：純穿透成份股不在 `stock` 主檔、不在 `stock_holding`、（多數）不在 `stock_alert`，原本不被任何收集路徑涵蓋故 `stock_price_history` 無資料。由 `external-materials` `HistoricalBackfillService.collectLookthroughTopConstituents` 以**最新快照持股**重算台股 / 美股透視 top10（演算與 BFF `buildLookthrough` / `buildUsLookthrough` 一致：台股 ETF `cv×w/Σw` 正規化、美股 ETF `cv×w/100` 不正規化、直接持股整筆計入、同代號加總後取前 10），將這些代號併入既有回補清單，補 10 年歷史寫入 `stock_price_history`（`(code, market)` 與穿透 segment 點擊帶入的 `{stockCode, market}` 一致 —— 台股純數字代號 + `台股`、美股大寫 symbol + `美股`，零格式轉換）。觸發時機：(1) `startupBackfill` 啟動掃描、(2) `backfillAll` 手動全補、(3) 每日 cron `dailyLookthroughBackfill`（18:30 Asia/Taipei）增量補（成份股**不在**即時抓價集合，靠此 cron 跟上每日收盤；既有列走 `maxDate+1` 增量、全新列補滿 10 年；今日列仍獨佔給 `ClosePersister`）。**僅收 top10**（畫面上可點擊的 segment）以控制資料量；**不寫入 `stock` 主檔**（沿用上一條原則：主檔是 `collectAllStockCodes` 抓價排程清單，灌入會造成即時抓價爆量；穿透成份股只需歷史日線、不需即時 tick）。台股成份股若 MoneyDJ 名稱補不到代號則無法回補也不開放點擊（degrade）
- [ ] **點擊個股分析、走勢圖無歷史時即時觸發單檔 10 年回補後重載（lazy backfill，Task 136；Requirement 7 回補涵蓋的即時補強）**：`StockAnalysisDialog` 開啟後若 `/api/bff/stock-analysis/history/stock` 回傳空陣列（該 `(code, market)` 尚未被 Task 129 的 `startupBackfill` / 每日 18:30 cron 補到 —— 例新進 top10 的 ETF 透視成份股如 `2383` 台光電，或在兩次 cron 之間被點開），對話框**自動於前景**呼叫 `/api/bff/stock-analysis/backfill-stock`（passthrough 至 business `/api/market-data/history/backfill-stock`，`since` 預設 `now−10y`）觸發一次回補，完成後**重新載入一次**走勢圖；補齊期間 loading 文案顯示「補齊 10 年歷史中…」。此路徑與 Task 129 同樣**只寫 `stock_price_history`、不寫 `stock` 主檔**（`backfill-stock` 端點本就不碰主檔），維持「主檔精簡」不變量；今日列仍獨佔給 `ClosePersister`（Req 7）。台股大盤 `0000` 不觸發（歷史走 `twse_index_daily_history`）。補完仍為空（成份股無代號、或外部源查無）才退回顯示「無歷史資料」。動機：Task 129 的成份股回補僅有 startup / 手動 `backfillAll` / 每日 cron 三個觸發點，缺「開啟即補」的即時觸發，使用者在 cron 之前點到新成份股會看到走勢圖空白（即本需求的實機情境）；本 AC 補上此即時 lazy 觸發 —— 對映 Task 126 為主檔標的所做的「即時回補消空窗」，但作用在 **viewing 時**且 **不入主檔**

### Requirement 10: 匯率歷史查詢

**User Story:** 作為使用者，我希望能查看美元/台幣的歷史匯率走勢，以了解匯率對資產的影響。

**Acceptance Criteria:**

- [ ] 以折線圖呈現 USD/TWD 歷史匯率（含買入、中間、賣出價）
- [ ] 支援時間範圍選擇：1月 / 3月 / 6月 / 1年 / 2年 / 3年 / 5年 / 全部；「全部」顯示近 10 年歷史
- [ ] 系統定期或按需取得最新匯率資料並儲存歷史紀錄
- [ ] **匯率來源鏈與當日（T-0）備援**：當日匯率以台灣銀行牌告（即期買入/賣出，含真實買賣價差）為主來源；當台銀牌告被 WAF 反爬挑戰封鎖而抓不到時，USD 改以 Yahoo Finance 當日中間價（`TWD=X`）暫定寫入今日列（`buy_rate = sell_rate = 中間價`），隔日由 FinMind 以真實即期買入/賣出價覆寫同一 `(currency, rate_date)` 列。抓不到任何來源時沿用上一筆，**不以假值充數**。USD 以外幣別（如 ZAR）當日缺值時接受沿用 FinMind 的 T-1 值（無當日中間價備援）
- [ ] 支援手動補齊指定日期起的歷史匯率（`POST /api/market-data/exchange-rate/backfill-history`）
- [ ] 歷史匯率至少保存 10 年

---

### Requirement 11: 銀行與券商設定管理

**User Story:** 作為使用者，我希望能在系統介面中自由新增、編輯、停用銀行與券商，而不需要修改程式碼或重新部署，以因應未來金融機構的異動。

**Acceptance Criteria:**

- [x] 銀行與券商清單儲存於資料庫，不以 Enum 寫死
- [x] 使用者可新增銀行，欄位包含：名稱（顯示用）、識別代碼（唯一）、Excel 匯入關鍵字（可多組）
- [x] 使用者可新增券商，欄位包含：名稱（顯示用）、識別代碼（唯一）、Excel 匯入關鍵字（可多組）
- [x] 使用者可編輯現有銀行與券商的名稱、關鍵字
- [x] 使用者可停用銀行或券商（軟刪除），停用後不出現於新增存款/股票的下拉選單，但歷史資料仍正常顯示
- [x] Excel 匯入時依資料庫中設定的關鍵字動態比對銀行與券商，無需修改程式碼即可支援新機構
- [x] 系統提供預設初始資料（Seed Data）：富邦、國泰、台新、LINE Bank、永豐、元大、華南等常見銀行，以及富邦、國泰、元大、華南等常見券商

---

### Requirement 12: 存款類型與市場類型設定管理

**User Story:** 作為使用者，我希望存款類型（活存、定存等）與市場類型（台股、美股等）也能由資料庫管理，不寫死於程式碼，以便未來靈活調整分類。

**Acceptance Criteria:**

- [x] 存款類型與市場類型各自有獨立的資料表，不以 Enum 寫死
- [x] 每個類型有識別代碼（code，同時作為存入資料庫的值）、顯示名稱、顯示排序、啟用狀態
- [x] 使用者可新增、編輯、停用存款類型與市場類型
- [x] 停用後不出現於新增表單的下拉選單，但歷史資料仍正常顯示
- [x] 系統提供預設 Seed Data：6 種存款類型（活存/定存/美元活存/美元定存/證券戶/信用卡待付款）、3 種市場類型（台股/美股/英股）
- [x] 前端新增快照的存款類型下拉選單動態從 API 載入，不寫死


---

### Requirement 13: 股票走勢圖延伸資訊（ETF 持股明細與股利歷史）

**User Story:** 作為使用者，我希望在雙擊開啟的股票走勢圖 popup 裡，除了看到 K 線與技術指標，還能快速檢視「ETF 成分持股」以及「最近 10 年的股利發放」，以便做配置與殖利率評估。

**Acceptance Criteria:**

- [ ] Popup 以頁籤（Tabs）呈現：走勢圖（預設）、持股明細（僅 ETF）、股利歷史
- [ ] 走勢圖期間按鈕第一個項目為「當日」，顯示最後一個交易日的盤中分時走勢。月線 MA20 / 季線 MA60 / 年線 MA240 與 K / D 仍要顯示，但 intraday tick 數無法支撐日線級指標重新計算 → 改以「該股最近一個交易日收盤後算出的日線最新值」畫成水平參考線（與其他期間同口徑，避免使用者在切回日線期間時看到不同 MA 值）
- [ ] 走勢圖「股價」線標出**目前可視區間內**的最高 / 最低點（紅最高、綠最低＝台股紅漲綠跌）：點上以小圓點標記，旁附**色塊標籤（紅/綠底白字）兩行**——第一行「最高／最低 + 股價（2 位小數、千分位）」、**第二行日期；「當日」分時模式第二行改為時間（HH:mm）**（標籤第二行直接取該點 x 軸類別字串 `labels[i]`，日線即日期、當日即時間，不必分支）。色塊位置依該點在可視窗的水平位置自適應避邊（靠右→放左、靠左→放右、其餘最高在下/最低在上，避免撞到頂部 legend 與底部縮放軸、或溢出左右邊界）。因日線資料為完整 10 年一次載入、區間鈕僅調 dataZoom 縮放窗，**不可用 ECharts 原生 `markPoint type:'max'/'min'`**——它會掃整段 10 年的極值，縮到短區間時最低點會落在畫面外、看似壞掉。改依目前可視窗（區間鈕預設 `defaultZoomRange` 或使用者手動拖曳後的 `zoomPct`，合成 `effectiveZoom`）換算可視索引範圍後自行找極值，並透過 `@datazoom` 事件讀回圖表當前 start/end% 即時重算。markPoint `coord` 以該點**類別字串**定位（非絕對索引），避免 dataZoom 過濾視窗外資料後絕對索引對不準。切換股票 / 區間時清掉手動縮放（回該區間預設窗）；「當日」分時模式同口徑標出當日最高 / 最低（與指數圖 Requirement 18 同設計）
- [ ] 走勢圖「成本均價」水平參考線即「買入均價」的同義值，從 Dashboard 持股列雙擊開啟時，必須與該列表格「買入均價(USD)」顯示**同一數字**：一律取 BFF 已算好的 `avgCostOriginal`（以交易當下匯率 `transactionExchangeRate` 鎖定的原幣買入成本 ÷ 股數），**不得**用前端「台幣投資成本 ÷ 今日即時匯率」反推 —— 今日匯率每日浮動，會讓走勢圖成本均價與表格買入均價對不上，且不是真實買入成本
- [ ] 「當日」分時資料採兩階段：(1) **盤中**由 `external-materials-service` 既有 `PricePoller` 每 2 分鐘輪詢時，將真實成交 tick（時間 + 成交價）`RPUSH` 到 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`，TTL 36h。**寫入守門條件**：(a) `source` 不含括號（排除 `(history)` / `(前收)` / `(買賣中價)` 等非實際成交值，與 `HistoricalDataService.getStockHistory` 對今日格條件一致）；(b) `markClosed=false`（排除 cache warming 對盤外時段抓到的「最新可得值」回灌污染分時序列）。前端切到「當日」即時讀此 LIST 看到當下累積結果
- [ ] **盤後**由 `IntradayTickRefresher` cron（台股 13:35 TW、美股 16:05 ET、英股 16:35 LON，較 `ClosePersister` dump 晚 3 分鐘）抓外部完整當日資料 `DEL` + `RPUSH` 覆寫該日 LIST，補回盤中 polling 因 `z='-'` 跳號漏掉的時間點。資料源優先序：
  - 台股：FinMind `TaiwanStockKBar` 5 分鐘 K 線（需 sponsor token；`finmind.token` 未設定時 FinMind 回 400 → fallback）
  - 台股 fallback / 美股 / 英股：Yahoo `chart?interval=5m&range=1d`（既有 `PriceFetchClient.fetchIntraday5m`）。**台股 Yahoo ticker 須依掛牌市場決定後綴**：上市（TSE）為 `{code}.TW`、上櫃（TPEx，含債券 ETF 00xxxB 等）為 `{code}.TWO`。`fetchIntraday5m` 對台股先試 `.TW`、無資料再 fallback `.TWO`（同 Requirement 7 殖利率 / ETF 持股查詢既有 `.TW → .TWO` 慣例）。否則上櫃股的「當日」分時恆為空、顯示「無當日分時資料」（即時價因 TWSE mis 同時試 `tse_`/`otc_` 而正常，唯分時走 Yahoo 漏掉後綴）
- [ ] **Cold start**：使用者切到「當日」時，BFF endpoint 看 Redis LIST 為空，同步觸發一次 `IntradayTickRefresher.refreshOne` 抓外部資料寫入後再回傳；之後切換即從 Redis 命中
- [ ] 非 ETF 個股不顯示「持股明細」頁籤（台股 00 開頭者視為 ETF；美股採白名單：VOO / VT / AVGO 等已有持倉的 ETF）
- [ ] 持股明細頁籤顯示 ETF 成分股清單（名稱 / 持股比例 / 股數）。台股 ETF 資料來源優先順序：(1) **MoneyDJ** 持股明細頁 `Basic0007a.xdjhtm?etfid={code}.TW`（完整成分股，非僅前 10；只揭露股名無代號）；(2) Yahoo `quoteSummary?modules=topHoldings`（前 10 大）為 fallback；(3) FinMind `TaiwanETFHoldings` dataset 已於 2026 年被 FinMind 移除（回 422），僅向下相容。美股 ETF 走 Yahoo；不支援則顯示「尚未支援該市場」。結果含 12h in-memory cache（成分股每日至多變動一次，避免重複外呼）
- [ ] MoneyDJ / Yahoo 一律以 `curl` 子程序抓取（非 Java HttpClient）：站方 WAF 會依 TLS/HTTP 指紋辨識 Java HttpClient 並回 `429 Too Many Requests`（同容器、同 IP 的 curl 卻正常）。Yahoo crumb 流程：`curl -c <cookie> https://fc.yahoo.com`（回 404 但 Set-Cookie: A1/A3）→ `curl -b <cookie> /v1/test/getcrumb` 取 crumb → `curl -b <cookie> quoteSummary?...&crumb=`
- [ ] 股利歷史頁籤顯示最近 10 年股利：年度 / 每股現金股利 / 每股股票股利 / 現金殖利率 / 除息日昨收價 / 除息日 / 發放日 / 填息天數
- [ ] 「除息日昨收價」=該檔股票在除息日前一個交易日的收盤價（取自 `StockPriceHistory`），無資料時顯示「—」
- [ ] 「現金殖利率」每筆事件 = 現金股利 / 除息日昨收價 × 100%（無昨收價時顯示「—」）
- [ ] 表格除了顯示每次除息事件外，還需在每年事件之上插入該年度小計列（年度／合計現金股利／合計股票股利／年度殖利率），年度小計列以較深背景與粗體區隔；年度殖利率以「該年合計現金股利 / 該年最近一次除息事件的昨收價」計算
- [ ] 台股股利資料來源 FinMind `TaiwanStockDividend`（上市櫃公司**盈餘分配表**，欄位為 `CashEarningsDistribution` 等公司治理概念）；**該表查無資料時 fallback 至 FinMind `TaiwanStockDividendResult`（除權息結果表）**。理由：債券 ETF / 收益分配型 ETF（如 00751B 元大AAA至A公司債）配的是「利息收益分配」而非公司盈餘，不在 `TaiwanStockDividend` 內（回空陣列），但每季除息事件都在 `TaiwanStockDividendResult`。fallback 解析規則：以 `date` 為除息日、`stock_and_cache_dividend` 為配息金額；`stock_or_cache_dividend` 含「權」且不含「息」者計入股票股利，其餘（除息 / 除權息）計入現金股利；該表不提供發放日故 `cashPaymentDate` / `stockPaymentDate` 留空。美股股利資料來源 NASDAQ `/api/quote/{code}/dividends`；**該 API 僅服務 NASDAQ 上市標的，對 NYSE / NYSEARCA 標的（如 VOO、SGOV、SCHD）回 N/A → fallback 至 Yahoo `chart?range={years}y&events=div`**（以 curl 子程序呼叫，避開 Java HttpClient 被 WAF 擋；解析 `events.dividends` 每筆 `amount`=現金配息、`date`=除息日 epoch 秒，Yahoo 僅有現金配息、無發放日）。資料源（NASDAQ / Yahoo Finance / FinMind）逐筆寫入 `stock_dividend_history.source`，由 `DividendFetchClient.fetch()` 回傳實際採用值，不再以市場別硬編
- [ ] 後端呼叫 FinMind 時若環境變數 `FINMIND_TOKEN` 有值，需在 HTTP request header 帶 `Authorization: Bearer <token>`，避免匿名額度耗盡時被回 402 Payment Required；未設定時保持匿名呼叫向下相容
- [ ] 資料查無或來源失敗時顯示友善提示，不拋例外

---

### Requirement 14: 觀察股票清單（由警示條件衍生）

**User Story:** 作為使用者，我希望「觀察清單」即「警示條件」中出現過的去重股票清單，這樣在警示條件設了多筆 0050 條件時，觀察清單就會自動有一筆 0050；不必同時維護兩份名單。

**Acceptance Criteria:**

- [ ] 左側導覽選單「股票觀察」項目，路徑 `/stocks`（內含「觀察清單」、「警示條件」兩個頁籤；舊路徑 `/watch-stocks`、`/stock-alerts` 自動 redirect 並帶 `tab` query）
- [ ] 頁面提供「台股」、「美股」兩個頁籤，依市場分流顯示
- [ ] **觀察清單不另存資料表**：`watch_stock` 表廢止；觀察清單一律由 `stock_alert` 群組去重衍生 = `SELECT stockCode, market, MIN(displayOrder) FROM stock_alert GROUP BY stockCode, market`。同一檔股票即使有多筆條件也僅顯示一列
- [ ] 每列顯示欄位：股名/股號、股價、漲跌、漲跌幅(%)、開盤、昨收、最高、最低、成交量(張)、警示條件（該股票所有 alert 條件 label 清單）、警示（最近一次觸發資訊：觸發時間/股價/均線/KD）
- [ ] 「警示條件」欄列出該 (stockCode, market) 在 `stock_alert` 中所有條件（依 displayOrder 升冪），每條顯示其 condition label（如「高於季線 5%（315）」「K 值低於 20」——MA 百分比條件附換算後的觸發價，詳見 Requirement 16）；停用條件以淺色 + 「(停用)」標註；**已觸發**（該條 alert 的 `lastTriggeredAt` 在最近 3 個交易日內）的條件以紅字顯示，與「警示」欄的觸發判定共用同一份 cutoff
- [ ] 「警示」欄取自該股票所有 alert 中最近一筆 `lastTriggeredAt`，呈現觸發時間、觸發價、**月線（MA20）／季線（MA60）／年線（MA240）三條均線值**、KD 值（最近 3 個交易日內才顯示，過期不顯示）。三條均線與 KD 皆為 `TechnicalIndicatorService.computeAll()` 算出的當前即時值（同義欄位同一來源，與股票分析走勢圖、警示 email 同口徑）；某條均線因歷史資料不足無法計算時，該行顯示「—」
- [ ] 報價來源 `StockPrice` 擴充欄位：buyPrice（買進）、sellPrice（賣出）、openPrice（開盤）、previousClose（昨收）、highPrice（最高）、lowPrice（最低）、volume（成交量，台股為張）
- [ ] 觀察清單中的股票同樣納入排程的股價更新（與持股一併更新）
- [ ] **新增觀察的入口 = 新增警示條件**：觀察清單頁的「新增」按鈕直接開啟警示條件新增表單；至少必須設一筆條件，建立成功後該股票自動出現在觀察清單
- [ ] **移除觀察的入口 = 警示條件頁刪除**：觀察清單頁**不提供**列級的刪除操作（無「操作」欄）；要把某股票從觀察清單移除，只能在「警示條件」頁刪除該 (stockCode, market) 所有 alert，列才會消失。此設計反映「觀察清單即 stock_alert 衍生 view」單一資料源語意，避免在兩個頁面提供等價但措辭不同的刪除入口
- [ ] **觀察清單拖曳排序 = 移動該股票所有 alert**：拖曳一列時將該股票所有 alert 的 `displayOrder` 整組重排到新位置（保持條件之間的相對順序）；警示條件頁的拖曳維持單筆 alert 級行為
- [ ] 盤中即時 `highPrice` / `lowPrice` 由 `external-materials-service` 自行聚合：每輪 cron 觀察到的成交價與當日已記錄的高/低做 max/min，存於 Redis（key `price:dayhl:{market}:{code}:{tradingDate}`，TTL 36 小時）。寫入 `price:{market}:{code}` 時，若外部 API 有提供 high/low 則取「外部值與聚合值的 max(high)/min(low)」；若外部 API 未提供（如 NASDAQ 對 ETF 的 `keyStats` 為 null），則直接採用聚合值。盤後 `dumpRedisToDb` 沿用同一份 Redis JSON 寫入 `stock_price_history`
- [ ] 美股 `openPrice` 來源：NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`；NASDAQ `/historical`「`fromdate == todate`」會回 400（`Provided date is less than from date`）、給日期區間盤中又不含今日列，兩者皆**無法**取得今日 open（原 `getNasdaqOpenPrice` 因此恆回 null → Redis live 無 `openPrice` → 連帶盤後 dump 進 `stock_price_history` 亦無 open → 觀察清單「開盤」欄恆顯示「—」）。改由 `PriceFetchClient.fetchUsTodayOpenFromYahoo` 打 Yahoo chart `interval=1d&range=1d`，取當日 daily bar `indicators.quote[0].open[0]`（盤中＝今日部分 bar 的 open、盤後＝完整 bar 的 open；只取 open 不碰 close，不違反 Task 84）；為盤中輪詢補強欄位，遇 Yahoo 429 fail-fast 不重試以免拖慢 cron。取不到時 `openPrice = null`，下游 `WatchStockService` 維持 `stock_price_history` fallback 行為。台股維持 TWSE mis API 的 `o` 欄位、英股維持 Yahoo `regularMarketOpen`
- [ ] **觀察清單支援代號 `0000`（市場 = 台股）= 台股大盤（TAIEX）**（含 KD）：
  - 加入觀察 = 設一筆 0000 的 alert（與其他股票流程一致）
  - `lookup-name` 端點看到 `code=0000&market=台股` 直接回 `{"stockName":"台股大盤"}`，不打外部 API、不寫入 stock 主檔
  - `StockAlertService.create` 對 `0000` 不寫入 `stock` 主檔（避免被排程當作真股票抓價）；報價/技術指標一律從 `twse_index_daily_history` 取
  - 觀察清單列：`price` / `previousClose` 取 `twse_index_daily_history` 最新與次新；`openPrice` / `highPrice` / `lowPrice` 從同表 OHLC 欄位填；`buyPrice` / `sellPrice` / `volume` 為 null（大盤無買賣盤口、無成交量定義）
  - 月線（MA20）、季線（MA60）、年線（MA240）、KD 皆從 `twse_index_daily_history` 計算（與一般股票同算法），`TechnicalIndicatorService` 對 `0000` 改讀此表代替 `stock_price_history`
  - 警示彙總（`lastTriggered*`）對 `0000` 比照其他股票顯示

---

### Requirement 15: 資料庫備份／還原（UI 介面 + 自動排程）

**User Story:** 作為使用者，我希望系統能依交易日曆自動於每個交易日收盤後與每周日定時備份資料庫到 Google Drive，並能在「系統設定」選單下手動觸發備份／挑選歷史備份還原，以確保資料連續性與災難復原能力。

**Acceptance Criteria:**

- [ ] 左側導覽選單「系統設定」子選單下新增「備份/還原 資料」項目，路徑 `/settings/backup-restore`
- [ ] 頁面分成兩個區塊：「立即備份」與「還原資料」
- [ ] 「立即備份」按鈕觸發 `POST /api/backups`，後端執行 `pg_dump` 並上傳加密檔到 `gdrive-crypt:backups/manual/`
- [ ] **自動備份排程（後端 `@Scheduled`，時區 `Asia/Taipei`）：**
  - **台股交易日備份**：每個台股交易日收盤（13:30）後 2 小時，於 15:30 啟動，檔案上傳至 `daily/`，檔名 `asset_daily_tw_YYYYMMDD_HHmmss.dump`。台股是否為交易日依 TWSE Open API 假日表（`MarketDataService.getTwHolidays`）為準。
  - **美股交易日備份**：每個美股交易日收盤（美東 16:00）後 2 小時，對應台北時間 06:00～07:00（依夏令／標準時切換），統一於台北時間 07:00 啟動（隔日 TUE–SAT），檔案上傳至 `daily/`，檔名 `asset_daily_us_YYYYMMDD_HHmmss.dump`。美股是否為交易日依 NYSE 假日規則（`MarketDataService.getUsHolidays`）為準。
  - **每周備份**：每周日台北時間 05:00 啟動，檔案上傳至 `weekly/`，檔名 `asset_weekly_YYYYMMDD_HHmmss.dump`，不檢查交易日。
- [ ] **保留代數（自動輪替，依資料夾分別計算；可由使用者於 UI 調整，預設值如下）：**
  - `daily/`：預設 **50** 份（`asset_daily_*` 才計入；超過自動刪除最舊）
  - `weekly/`：預設 **5** 份
  - `manual/`：預設 **5** 份手動備份（`asset_manual_*`，自救點 `asset_auto-pre-restore_*` 不計入）
  - 設定儲存於 `backup_setting` 單列資料表，欄位 `manual_retention` / `daily_retention` / `weekly_retention`（INT，1～999）
  - 端點：`GET /api/backups/settings`、`PUT /api/backups/settings`
  - 前端「備份/還原 資料」頁新增「保留設定」區塊，三個 number input + 儲存按鈕；儲存成功顯示 `ElMessage.success`
  - **儲存設定當下立即套用 retention**：使用者下調某類型保留代數時，`PUT /api/backups/settings` 在更新 `backup_setting` 後立即對 `manual/`、`daily/`、`weekly/` 三個資料夾各跑一次 `rotateFolder`，把超出新上限的最舊備份刪除（含 `backup_record` row 與 Google Drive 上加密檔），不需等到下一次排程備份。輪替過程中任何單一資料夾失敗只記 log、不影響設定儲存成功與其他資料夾繼續輪替
- [ ] 手動備份檔案名稱格式 `asset_manual_YYYYMMDD_HHMMSS.dump`
- [ ] 「還原資料」區塊以 `el-table` 列出 Google Drive 上**所有**備份（含 manual / daily / weekly / monthly 四個資料夾），欄位：來源資料夾、檔名、備份時間、檔案大小
- [ ] 列表預設依備份時間「新→舊」排序
- [ ] 每列提供「還原」按鈕，點擊後跳出二次確認對話框，需在輸入框輸入「確認還原」字樣才能執行
- [ ] 還原前自動建立「自救點」備份（`asset_auto-pre-restore_YYYYMMDD_HHMMSS.dump`，存於 `manual/`，不計入 5 份保留上限）
- [ ] 還原期間 UI 顯示遮罩「還原中…請勿關閉視窗」，禁止其他操作
- [ ] 還原完成後自動重新載入頁面（HikariCP 會自動重連 PostgreSQL）
- [ ] 備份／還原失敗時顯示錯誤訊息（含後端 stderr 訊息摘要），不直接拋 500
- [ ] 後端需透過 `ProcessBuilder` 呼叫 `pg_dump` / `pg_restore` / `rclone`，相關工具透過 Dockerfile 安裝、rclone 設定檔以 read-only volume 從 host 掛入
- [ ] 「保留設定」新增「啟用備份」開關（`backup_setting.backup_enabled`，預設 true）。關閉時：手動備份按鈕停用，所有 `@Scheduled` 排程在進入備份流程前先檢查並 skip。還原流程不受開關影響（自救點仍會建立）
- [ ] 備份紀錄改存於 DB（`backup_record` 表，欄位：folder, filename, size_bytes, modified_at, auto_pre_restore, created_at），每次備份成功後寫入一筆；rotateFolder 刪除舊檔時連帶刪除對應 row。還原資料列表 (`GET /api/backups`) 一律從 DB 讀取，不再每次連 Google Drive，UI 開啟即時顯示。新增「從 Google Drive 同步」按鈕呼叫 `POST /api/backups/sync`：列出 rclone 並 upsert / 清除 DB 與 GDrive 不一致的紀錄，供首次部署或外部刪檔後手動對齊

---

### Requirement 16: 到價警示（Stock Alerts）

**User Story:** 作為使用者，我希望能設定股票的到價警示（價格門檻、均線、KD 等），系統定期檢查並記錄最近一次觸發資訊，於股票觀察頁面彙總顯示。

**Acceptance Criteria:**

- [x] 警示資料儲存於獨立資料表 `stock_alert`，欄位含股票代號、名稱、市場、條件、啟用狀態、顯示排序、最近觸發時間/股價/均線/KD
- [x] 警示頁面整併進股票觀察頁（路徑 `/stocks?tab=alerts`，舊路徑 `/stock-alerts` 自動 redirect）
- [x] 提供新增、編輯、刪除、啟用/停用、拖曳排序、手動觸發檢查
- [x] 觀察股票列表（Requirement 14）顯示對應股票最近一次觸發資訊
- [x] 提供 `GET /api/stock-alerts/lookup-name` 由代號自動帶名稱（觀察股票與警示新增表單共用）；對 `code=0000` 有市場守門：`台股` → 直接回「台股大盤」（TAIEX 特例），`美股` → 直接回空字串（美股無此代號，避免 Yahoo fuzzy match 回隨機公司並被自動寫入 `stock` 主檔）
- [x] **建立／更新警示時，後端守門：代號↔股名必須一致**。`StockAlertService.create` / `update` 在 `stockMasterRepo.upsert` 之前，呼叫 `historicalDataService.fetchTwStockName / fetchUsStockName(code)` 取得 canonical name（權威來源為 ext-materials-service `/internal/stock-name` → FinMind / Yahoo），若 canonical 非空且與使用者送來的 `stockName` 不一致（trim + 大小寫不敏感），**再對照本地 `stock` 主檔的名稱（亦視為合法 canonical）**；兩者皆不相符才回 `400`，並提示「代號 X 與股名「Y」不符，外部來源為「Z」」，阻止錯誤資料寫入 `stock` 主檔（過去症狀：使用者誤把 `2500` 標為「台積電」，於觀察清單顯示「2500 台積電」但所有報價欄位皆為 `—`，因 2500 非台積電也無 live／history 資料）。「本地主檔也算 canonical」的設計目的：lookupName 是「本地主檔優先 → 外部 fallback」，若外部來源在不同時間回不同字串（例如 Yahoo `shortName` 「NVIDIA Corporation」vs 過去寫入主檔的 「NVIDIA Corporation Common Stock」），會造成 UI 自動帶名後再送出時被守門擋下；既然 UI 帶出的值來自主檔，主檔本身就應算合法 canonical。canonical 為空（外部 API 失敗或查無）則信任使用者，避免外部服務異常時阻擋合法建立。`0000` + `台股` 跳過守門（大盤特例）；`0000` + `美股` 直接拒絕（美股無此代號）
- [ ] 提供 `GET /api/stock-alerts/lookup-code?name=&market=` 反向查找：由股名查代號（只查本地 `stock` 主檔，精確匹配；`「台股大盤」+ 台股 → 0000` 特例），查無時前端提示「請改輸入股票代號」。警示新增表單股名欄位 blur 時自動觸發，方便使用者直接打股名（如「富邦金」）建立警示
- [ ] **`stock_alert` 是觀察清單的唯一資料來源**：新增、編輯、刪除任一 alert 後，觀察清單需同步反映（出現新股票、移除最後一筆條件被刪的股票、displayOrder 變更後排序更新）
- [ ] 支援 `stockCode = 0000`（台股大盤）的 alert：可設 PRICE / MA / KD 各類型；報價與技術指標來源改為 `twse_index_daily_history`（含 OHLC）；不寫入 `stock` 主檔
- [ ] **均線警示通用化**：`stock_alert` 新增 `ma_period` 欄位（INT，可空），均線類警示 `alertType` 統一為 `MA_ABOVE_PCT` / `MA_BELOW_PCT` 並以 `ma_period` 表示天數，新增警示時可選 20（月線）/ 60（季線）/ 240（年線）；舊資料 `QUARTERLY_MA_*` → `MA_*_PCT` + `ma_period=60`，`ANNUAL_MA_*` → `MA_*_PCT` + `ma_period=240`（同 commit liquibase 遷移），未來再加任何均線週期不需改 enum-like 字串
- [ ] **MA 百分比條件於「警示條件」欄附換算觸發價**（Task 146）：`MA_ABOVE_PCT` / `MA_BELOW_PCT` 且 threshold≠0 的條件，label 於百分比後附上換算後的觸發價，格式「高於季線 20%（360）」；觸發價 = 當前對應均線（依 `ma_period` 取月線 MA20／季線 MA60／年線 MA240）× (1 ± pct/100)，四捨五入至小數 2 位並去除尾零。均線即時值一律取自 `TechnicalIndicatorService.computeAll()`（與觀察清單 MA 欄、走勢圖、警示 email 同口徑）；該均線因歷史資料不足無法計算時，退回不附價格的純文字。此換算由 `StockAlertService.buildLabel(alert, indicators)` 單一來源實作：觀察頁（`WatchStockService` 為每檔已算好 indicators、直接帶入）與警示頁（`StockAlertService.toResponse` 僅對 MA% 條件計算 indicators、其餘類型回 null 省查詢）共用，兩頁口徑一致。threshold=0（純「高於／低於季線」）與 PRICE／KD 類型不受影響
- [ ] 每次警示觸發都記錄一筆歷史於 `stock_alert_trigger`，欄位含 `alert_id`、`triggered_at`、`price`、`monthly_ma` (MA20)、`quarterly_ma` (MA60)、`annual_ma` (MA240)、`k_value`、`d_value`、`created_at`
- [ ] 觸發紀錄只保留 30 天：每日排程刪除 `created_at < NOW() - 30 days` 的舊紀錄
- [ ] 觸發紀錄表透過 `alert_id` FK 級聯刪除（警示本體被刪時，歷史一併清除）
- [ ] 觸發時間（`lastTriggeredAt` / `stock_alert_trigger.triggered_at`）一律以「該股市場的本地 wall time」儲存（台股 = Asia/Taipei、美股 = America/New_York）。`computeTriggeredAt` 在交易時段內 fallback 不再用 JVM 預設時區的 `LocalDateTime.now()`，改用 `ZonedDateTime.now(marketZone).toLocalDateTime()`，與 Yahoo intraday bar、`tradingDate.atTime(close)` 對齊。前端 `WatchStockView` / `StockAlertView` 顯示時加市場時區後綴（`TW` / `NY`），避免使用者把美股的「13:30」誤讀為台北時間
- [ ] **國定假日不觸發任何警示**：市場休市日（依該市場國定假日表，週末已隱含）即使 Redis 仍持有前一交易日的即時快取價，`StockAlertService.evaluate` 也不得產生「當日」觸發 —— live 觸發分支以 `MarketDataService.isTradingDay(market, 該市場時區今日)` 為閘門，假日時略過（仍落到「補抓最近交易日盤中觸發」分支，該分支只掃 `stock_price_history` 真實交易日，假日無資料故不會誤觸發）。`computeTriggeredAt` 的「交易時段內用 `now()`」判斷亦由純平日改為 `isTradingDay`（假日 → 退回最近交易日收盤時刻）。解決症狀：美股 Juneteenth（6/19，週五）休市卻仍把條件標成「06/19 09:30 NY」觸發
- [x] **「警示」欄顯示窗 = 最後一個交易日（或交易當日）及前一交易日，共 2 個交易日**：`lastTriggeredAt` 早於此窗的觸發視為過期，不顯示「觸發時間／股價／月線／季線／年線／KD」，「警示條件」也不套紅字（解決收盤後數日仍殘留舊觸發，例：6/17 仍顯示 6/12 NY 觸發）。窗以 `stock_price_history` 該市場最近 2 個 distinct `trading_date` 之較早一天午夜為下界（`recentTradingDayCutoff(market, 2)`）；觀察頁（`WatchStockService`，含 `0000` 大盤分支）與警示頁（`StockAlertService.toResponse`）共用單一常數 `StockAlertService.FRESHNESS_TRADING_DAYS`，兩頁口徑一致。此 UI 顯示窗與 `stock_alert_trigger` 歷史保留 30 天（供補發／稽核）為兩個獨立概念

---

### Requirement 17: 待轉入資金類型設定管理

**User Story:** 作為使用者，我希望「待轉入資金類型」也能由資料庫管理，能在系統介面中新增、編輯、停用，不寫死於程式碼。

**Acceptance Criteria:**

- [x] 待轉入資金類型有獨立資料表，不以 Enum 寫死
- [x] 每個類型有識別代碼（code，唯一）、顯示名稱、顯示排序、啟用狀態
- [x] 使用者可新增、編輯、停用，停用後不出現於下拉選單但歷史資料仍正常顯示
- [x] 系統提供預設 Seed Data（`DataInitializer`）
- [x] 前端新增 `/settings/transit-fund-types` 設定頁面

---

### Requirement 18: 股市大盤查詢（指數日線/當日 + 台日韓人均 GDP 比較）

**User Story:** 作為使用者，我希望在「股市大盤查詢」頁查看台股大盤、美股四大指數與主要海外指數（英、德、韓、日）的近 10 年日線（含當日分時），並比較近 40 年台、日、韓人均 GDP 與其實質成長率，以理解股市表現與總體經濟。

> 註：原「台灣人均 GDP vs 台股大盤年末收盤」雙 Y 軸圖已於 Task 97 移除（見下方刪除線條目）。

**Acceptance Criteria:**

- [ ] 左側選單新增「股市大盤查詢」項目，路徑 `/gdp-twse`
- [x] ~~頁面以雙 Y 軸折線圖呈現「台灣人均 GDP vs 台股大盤年末收盤」~~ **（Task 97 移除）**：此卡連同 `/api/twse-year-end-index`(GET/refresh)、`/api/twse-daily-index/latest`、`/internal/macro/twse-year-end`、`MacroHistoryService.refreshTwseYearEnd`、`MacroDataFetchClient.fetchTwseDecemberClose`、`TwseIndexYearEndHistory` entity/repo 一併移除；BFF `get` 不再回 `twseYearEndClose`/`currentYearLastTradingDate`、`refresh` 不再觸發大盤年末/日線。`twse_index_year_end_history` 資料表保留（不刪資料）
- [ ] 預設顯示近 40 年（含當年度若已有資料）
- [ ] 台/日/韓年度 GDP 各自存於 `taiwan_gdp_per_capita_history`、`japan_gdp_per_capita_history`、`korea_gdp_per_capita_history`，由 Liquibase changelog 建立（日、韓不 seed 歷史值，靠回補按鈕從 IMF 取得）
- [ ] 後端提供獨立資源端點 `/api/taiwan-gdp`、`/api/japan-gdp`、`/api/korea-gdp`；BFF 端點 `/api/bff/gdp-twse`（get 回 TW/JP/KR 人均 GDP + 實質成長率），前端只呼叫 BFF
- [ ] 「回補 GDP（IMF）」按鈕（位於「台日韓人均 GDP 比較」卡）回補 TWN + JPN + KOR 人均 GDP 與實質成長率，台灣採**主計總處（DGBAS）優先、IMF 備援**（DGBAS NA8101A1A 為主、缺年份用 IMF `NGDPDPC/NGDP_RPCH`），日本、韓國純 IMF；合併後 upsert（DB 存單一最終值）
- [ ] BFF 在組裝 X 軸年份時過濾 `> 當年`（IMF 含未來預測，不顯示）
- [ ] 「台日韓人均 GDP 比較」圖：左 Y 軸為 TW/JP/KR 人均 GDP（折線），右 Y 軸為各自年增率（柱狀）
- [ ] 日本、韓國資料同樣由 IMF DataMapper API（`NGDPDPC/JPN`、`NGDPDPC/KOR`）回補，分別存於 `japan_gdp_per_capita_history`、`korea_gdp_per_capita_history`（DGBAS 無日、韓資料，故日、韓維持純 IMF）
- [ ] 「回補資料」按鈕同步觸發 TWN + JPN + KOR 三國 GDP 回補
- [ ] 經濟成長率取**實質 GDP 成長率**（台灣優先 DGBAS NA8101A1A「經濟成長率(%)」、缺則 IMF `NGDP_RPCH`；日本、韓國 IMF `NGDP_RPCH`），不再由前後端用人均 GDP（USD）相減推算（因含匯率波動會失真）；存於 `*_gdp_per_capita_history.real_gdp_growth_rate`。圖的成長率柱狀皆讀此欄位（同一資料源）
- [ ] 同頁「最上方」加第三張卡：台股大盤（TAIEX）每日收盤近 10 年走勢圖
  - 顯示每日收盤點位（`close_point`）+ 月線（MA20）+ 季線（MA60）+ 年線（MA240）四條曲線
  - 區間切換按鈕：1 個月 / 3 個月 / 半年 / 1 年 / 2 年 / 5 年（透過 dataZoom 對齊 X 軸末端，前段 240 個交易日仍保留以利 MA240 完整顯示）
  - 後端日線資料表 `twse_index_daily_history`（`trading_date` PK, `open_point`, `high_point`, `low_point`, `close_point` 各 NUMERIC(12,2)），由 Liquibase changelog 建立（不 seed 歷史值）
  - business service 新增 `GET /api/twse-daily-index?from=YYYY-MM-DD&to=YYYY-MM-DD` 與 `POST /api/twse-daily-index/refresh?years=10`，後者逐月呼叫 TWSE FMTQIK 月報抓全部交易日 OHLC（`OpeningIndex` / `HighestIndex` / `LowestIndex` / `ClosingIndex`）upsert 至 DB
  - 大盤 OHLC 同時供 Requirement 14（觀察清單 0000 KD 計算）使用，不另建表
  - BFF 新增 `GET /api/bff/gdp-twse/twse-daily?years=10`：載入近 N 年日線並計算 MA20/60/240 後一次回傳；前端切換區間僅用 dataZoom 不再打 API
  - BFF 新增 `POST /api/bff/gdp-twse/refresh-twse-daily?years=10`：proxy 至 business `/api/twse-daily-index/refresh`，回補可能要 1~2 分鐘
  - 「回補資料」按鈕同步觸發 TWN GDP + KOR GDP + 大盤年末 + 大盤日線四項回補
- [ ] 第三張卡（每日收盤日線圖）支援**市場切換**（一次顯示一個指數，沿用同一套「收盤＋月線 MA20／季線 MA60／年線 MA240」四線版型與區間切換鈕）：頂部下拉選單可在「台股大盤 / 道瓊工業 / 標普 500 / 那斯達克綜合 / 費城半導體 / 英國富時 100 / 德國 DAX / 韓國 KOSPI / 日經 225」九者間切換；卡片標題、空狀態提示文字隨選取指數動態變化
  - 「美國四大指數」＝道瓊工業 (DJI / `^DJI`)、標普 500 (SPX / `^GSPC`)、那斯達克綜合 (IXIC / `^IXIC`)、費城半導體 (SOX / `^SOX`)
  - 「海外主要指數」＝英國富時 100 (FTSE / `^FTSE`)、德國 DAX (DAX / `^GDAXI`)、韓國 KOSPI (KOSPI / `^KS11`)、日經 225 (N225 / `^N225`)；與美股四大指數共用同一條 `us_index_daily_history` 日線管線（依 `index_code` 區分），不另建表
  - 美股 + 海外指數每日 OHLC 存於同一資料表 `us_index_daily_history`（複合主鍵 `(index_code, trading_date)`，`index_code ∈ {DJI, SPX, IXIC, SOX, FTSE, DAX, KOSPI, N225}`，OHLC 四欄 NUMERIC(14,4)），由 Liquibase changelog 建立（不 seed 歷史值）。表名沿用 `us_index_daily_history`（語意已一般化為「海外指數日線」，欄位本就以 `index_code` 通用化，**零遷移**）
  - 資料來源為 **Yahoo Finance v8 chart API**（`range=10y&interval=1d`），於 ext-materials-service 以 curl 子程序抓取（避開 Yahoo 對 Java HTTP fingerprint 的封鎖，與既有美股歷史抓取 `fetchUsHistoricalRange` 同 pattern）。原評估的 Stooq CSV 經實測在部署環境被擋（連 `aapl.us` 等通用標的都回錯誤頁），故改採 Yahoo；抓取邏輯封裝於 `MacroDataFetchClient.fetchUsIndexDaily(code)` 單一方法，日後可一處替換來源
  - 日線 timestamp → 交易日改依 Yahoo meta 之 `exchangeTimezoneName` 轉當地時區（非寫死 `America/New_York`）：美股 daily bar timestamp 在開盤時刻（09:30 ET）轉 NY 與轉交易所時區結果相同，但亞洲/歐洲指數 daily bar timestamp 在 UTC 午夜（＝當地開盤），若仍用 NY 時區會把日期回退一日（如東京 6/15 變 6/14）。改讀交易所時區後對所有市場一致正確
  - business service 新增 `GET /api/us-daily-index?code=&from=&to=` 與 `POST /api/us-daily-index/refresh?code=`（單一指數；Yahoo `range=10y` 一次呼叫即取得近 10 年，不需逐月迴圈）
  - BFF 將原 `.../twse-daily`、`.../refresh-twse-daily` **一般化**為 `GET /api/bff/gdp-twse/index-daily?market=&years=10` 與 `POST /api/bff/gdp-twse/refresh-index-daily?market=&years=10`（`market=TWSE` 走台股大盤、其餘走對應美股指數）；回傳格式（dates/closes/ma20/ma60/ma240）與 MA 計算對兩市場完全相同，由同一支 BFF 服務（同義欄位同一來源），確保版面一致
  - 「回補日線（10 年）」按鈕回補「當前選取」的指數
- [ ] 區間切換鈕新增「**當日**」（置於最前）：切到當日顯示該指數「盤中即時 / 盤後最後交易日」的分時走勢線（5 分 K 收盤連線，x 軸為該市場當地時區 HH:mm）；月線/季線/年線改畫成**水平參考線**（取日線最新 MA20/60/240 值，與其他期間同口徑，比照股票分析「當日」Task 87）
  - 當日資料即時向 **Yahoo v8 chart**（`interval=5m&range=5d`，取最新交易日的 bar）抓取、不寫 DB（指數不在 Redis tick 輪詢名單，故採即時抓取而非 Task 88 兩階段 tick store）。盤中回最新交易日當天部分 bar＝即時、盤後回最後完整交易日，自動滿足「盤中即時／盤後最後交易日」
  - market→Yahoo symbol：TWSE→`^TWII`、DJI→`^DJI`、SPX→`^GSPC`、IXIC→`^IXIC`、SOX→`^SOX`、FTSE→`^FTSE`、DAX→`^GDAXI`、KOSPI→`^KS11`、N225→`^N225`
  - 當日模式 Y 軸**鎖定當日價格區間**（min/max 取分時收盤上下界 +10% padding），不可用 `scale:true` 全 series 自動範圍——否則遠離當日價位的均線水平線（如強趨勢指數的年線在下、月線在上）會把 Y 軸跨距撐到數千點，當日數百點的起伏被壓成平線。均線水平線落在區間外時由 series clip 裁切，數值仍保留在 legend
  - X 軸固定延伸到**收盤時間**而非「現在時間」：ext-materials 補滿整個交易時段的 5 分格（各市場交易時段以當地時區為準：台股 09:00–13:30、美股 09:30–16:00、英國富時 08:00–16:30、德國 DAX 09:00–17:30、韓國 KOSPI 09:00–15:30、日經 225 09:00–15:30——東京證交所 2024-11-05 起收盤由 15:00 延至 15:30），盤中尚未到的時段 `close` 留 null（前端畫成空白、線只到最新一筆）。Yahoo 最後一筆「現價」bar（非整 5 分，如 14:16）floor 對齊到 5 分格。日經有午休（前場 09:00–11:30、後場 12:30–15:30），午休 11:30–12:30 Yahoo 無 5 分 bar，對應時段 `close` 留 null（線中斷），屬預期
  - business 新增 `GET /api/index-intraday?market=`、BFF 新增 `GET /api/bff/gdp-twse/index-intraday?market=`（回 `tradingDate` + `times`(HH:mm) + `closes`）；ext-materials `MacroDataFetchClient.fetchIndexIntraday(market)` 經 `/internal/macro/index-intraday` 提供
  - 「當日」模式於卡片標題列顯示**昨日收盤 / 漲跌 / 漲跌%**：昨收＝該指數日線表（`twse_index_daily_history` / `us_index_daily_history`）中「當日 `tradingDate` 之前最後一個交易日」的收盤——與觀察清單 0000 報價 `WatchStockService` 讀**同一張日線表**（同義欄位同一事實來源，值一致）；漲跌＝分時最新點位（`closes` 末筆非 null＝盤中即時 / 盤後收盤）− 昨收，漲跌%＝漲跌 / 昨收 ×100。**計算一律於 BFF**（前端只 render），`GET /api/bff/gdp-twse/index-intraday` 回傳增加 `previousClose` / `lastClose` / `change` / `changePercent`。配色比照觀察清單 `priceColor` 紅漲綠跌（台股慣例）；昨收缺值（日線尚未回補）時該段不顯示，不影響走勢圖本身
  - **昨收須為「真正的前一交易日」**（Task 105）：當日走勢的現在點位是即時抓 Yahoo（最新交易日），昨收若取自過時的日線表會退回數日前舊收盤、使漲跌% 失真（實機 SOX 曾顯示 +13.88%）。除「缺值→null」外，日線表「過時但非空→昨收為錯的舊值」亦屬失效，故海外指數日線須由排程恆保最新（見下條）
  - 海外指數日線（`us_index_daily_history`）由 business-services `IndexDailyRefreshScheduler` **自動回補**：每日 07:00（Asia/Taipei，TUE-SAT，美股收盤後）對 8 指數（DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225）逐一呼叫 `refreshUsIndexDaily`（Yahoo `range=10y` idempotent upsert）；開機時若任一指數最新日期過時（> 4 日）亦觸發 self-heal 回補。角色比照台股大盤日線之 external-materials `TwseIndexPoller`，確保日線圖 / MA / 當日走勢昨收恆為最新（不再靠手動「回補日線（10 年）」按鈕維持新鮮度）
- [ ] 日線圖「收盤」線標出**目前可視區間內**的最高 / 最低點（紅最高、綠最低＝台股紅漲綠跌）：點上以小圓點標記，旁附**色塊標籤（紅/綠底白字）兩行**——第一行「最高／最低 + 點位（四捨五入、千分位）」、**第二行日期；「當日」分時模式第二行改為時間（HH:mm）**（標籤第二行直接取該點 x 軸類別字串 `labels[i]`，日線即日期、當日即時間，不必分支）。色塊位置依該點在可視窗的水平位置自適應避邊（靠右→放左、靠左→放右、其餘最高在下/最低在上，避免撞到頂部 legend 與底部縮放軸、或溢出左右邊界）。因日線資料為完整 10 年一次載入、區間鈕僅調 dataZoom 縮放窗，**不可用 ECharts 原生 `markPoint type:'max'/'min'`**——它會掃整段 10 年的極值，縮到短區間時最低點（如 10 年前低點）會落在畫面外、看似壞掉。改依目前可視窗（區間鈕預設 `dailyZoomRange` 或使用者手動拖曳後的 `dailyZoomPct`，合成 `effectiveDailyZoom`）換算可視索引範圍後自行找極值，並透過 `@datazoom` 事件讀回圖表當前 start/end% 即時重算。markPoint `coord` 以該點**日期字串**定位（非絕對索引），避免 dataZoom `filterMode:'filter'` 過濾視窗外資料後絕對索引對不準。切換市場 / 區間時清掉手動縮放（回該區間預設窗）；「當日」分時模式同口徑標出當日最高 / 最低

---

### Requirement 19: 信託基金最新淨值自動估值

**User Story:** 作為使用者，我希望系統能自動抓取信託基金的最新淨值與匯率，自動算出台幣現值，免去每次建快照都要手動填現值的麻煩。

**Acceptance Criteria:**

- [ ] 新增「信託基金主檔」資料表（`fund_master`），記錄基金代號、名稱、計價幣別、銷售銀行、是否啟用
- [ ] 主檔由 Liquibase changelog seed 7 支基金：6 支華南境外基金（`02A8`、`02B9`、`01C2`、`1680`、`24B2`、`1616`）+ 1 支元大境內基金（`93100953A` 元大日本龍頭企業台幣 A 類）
- [ ] `fund_master` 含 `site`（`offshore` / `onshore`）+ FundClear 三段代碼（`fundclear_org_code` / `fundclear_fund_code` / `fundclear_class_code`），用於 NAV 抓取
- [ ] 抓取分流：`site=offshore` 打 `/api/offshore/nav-profit/query-history`（DTO `organizeCode`/`fundCode`/`fundClassCode`），`site=onshore` 打 `/api/onshore/nav-profit/query-history`（DTO `orgId`/`fundNo`/`fundClassCode`）
- [ ] TWD 計價基金（如元大日本龍頭台幣類型）跳過 FX 換算，直接 `units × nav` 即為台幣現值
- [ ] **外幣基金（USD/EUR/ZAR…）台幣現值與預估年配息一律以「即期買入匯率」(`ExchangeRateHistory.buyRate`，銀行買入外幣價) 換算，不可用中間價**。理由：銀行對帳單「參考現值」採贖回口徑（贖回 / 領息時銀行向你買回外幣，套買入價）；中間價會高估。實作集中於 `ExchangeRateHistory.getFundValuationRate()`，由 `FundNavService`（現值）與 `FundDividendService`（年配息）共用，確保兩者同口徑且與銀行一致。NAV 採基準日 closest-on-or-before；境外基金 FUNDCLEAR 本有 2-3 天延遲，取「可得最新」即可
- [ ] 新增「基金淨值快取」資料表（`fund_nav`），存放最新淨值與淨值日，按 `fund_code + nav_date` 唯一
- [ ] `FundHolding` 新增 `units`（總單位數）欄位，**保留** `currentValue` 欄位作為 snapshot 凍結值
- [ ] `units` 為 nullable：填了 → 系統用 `units × NAV × FX` 自動算出 `currentValue` 寫入 snapshot；未填 → 維持手填 `currentValue`（向後相容舊資料）
- [ ] `currency` 由 `fund_master.currency` 提供，FundHolding 不重複儲存
- [ ] external-materials-service 每日 cron（09:00 Asia/Taipei）抓取 `fund_master` 全部啟用基金的最新淨值，寫入 `fund_nav`
- [ ] 抓取來源：FundClear（境外基金資訊觀測站）為主、MoneyDJ 為 fallback；先用 `java.net.http.HttpClient` 純 API 抓
- [ ] 抓取失敗時保留上次 NAV，前端顯示「資料過時 X 天」警示
- [ ] 匯率服務擴充支援 ZAR/TWD（基金 `1680`、`24B2` 計價為南非幣），沿用既有 `ExchangeRateHistory` 表 + FinMind/台銀來源
- [ ] SnapshotForm 信託基金區塊：`units` 欄位手填、現值欄位改為唯讀（顯示自動計算結果），提供「刷新最新淨值」按鈕觸發 external-materials-service 立即抓取
- [ ] 對外端點：`POST /api/fund-nav/refresh`（business-services proxy 至 external `POST /internal/fund-nav/refresh`）
- [ ] 既有 FundHolding 資料（`units` NULL）保留原 `currentValue`，不破壞歷史 snapshot 一致性
- [ ] 新增「信託基金主檔設定」頁面 `/settings/funds`，提供 fund_master CRUD（同 bank / broker 設定頁風格）；可編輯 fundName / bank / currency / site / FundClear 三段代碼 / active；fundCode 為 PK 只在新增時可填，編輯不可改
- [ ] SnapshotForm 信託基金區塊「基金代號」「基金名稱」兩欄合併為單一 dropdown，顯示 `「{code}　{name}」`；選擇後 fundName 寫入隱藏欄位以保留現有 schema 與舊 snapshot 顯示相容性
- [ ] DataInitializer.seedFundMasters 為 idempotent — 只在 fund_code 不存在時插入，避免覆蓋使用者於設定頁修改的資料

---

### Requirement 20: 信託基金預估年配息

**User Story:** 作為使用者，我希望系統能自動估算每支信託基金的預估年配息（含於 snapshot 預估年配息合計），以便了解整體資產的被動收入水準。

**Acceptance Criteria:**

- [ ] 新增 `fund_dividend_history` 資料表，欄位 `(id, fund_code, base_date, amount, currency, frequency, fetched_at)`，唯一鍵 `(fund_code, base_date)`；amount 為「每單位原幣配息」，base_date 為配息基準日 (`asiBaseDate`)
- [ ] external-materials-service 新增 `FundDividendFetchClient`：offshore 打 `POST /api/offshore/fund-info/info-dividend/query`（DTO `queryType:"1"`、`organizeCode/fundCode/fundClassCode`、`baseBeginDate/baseEndDate` 為 YYYY/MM、`asiFreqList:[]`、`_pageNum/_pageSize`），onshore 對應 `/api/onshore/fund-info/info-dividend/query-dividend`；回應 `list[].asiBaseDate / asiAmt / asiFreq` 寫入 `fund_dividend_history`
- [ ] `FundDividendPoller` 每日 cron（接 `FundNavPoller` 之後）跑一次：對 `fund_master.active=TRUE` 的基金抓近 13 個月（避免月份切換遺漏）寫入；inactive 基金跳過
- [ ] `InternalPriceController` 暴露 `POST /internal/fund-dividend/refresh` 同步觸發；business-services 加 `POST /api/fund-dividend/refresh` proxy
- [ ] backend `FundDividendService.getAnnualEstimateTwd(fundCode)`：取 `fund_dividend_history` 近 12 個月 `amount` 加總 → 「每單位年配息（原幣）」 × FX → 台幣；回 Optional.empty 表示無資料
- [ ] `FundHolding` 新增 `estimatedDividend NUMERIC(20,2)` 欄位（snapshot 凍結值；同 `StockHolding.estimatedDividend` 風格）
- [ ] `AssetService.createSnapshot` / `updateSnapshot`：若 `units` 非 null 且有 dividend 估算，自動算 `fund.estimatedDividend = units × annualPerUnitTwd`；既有 row 不動
- [ ] `AssetSnapshot.estimatedAnnualDividend` 合計 = stock dividends + fund dividends（SnapshotEnricher / recalcTotals 一併調整）
- [ ] `AssetSnapshotDto.FundResponse` 新增 `estimatedDividend`、`dividendRate`（rate = annualPerUnitTwd × units / currentValue）
- [ ] SnapshotForm 信託基金 row 增加「預估年配息」欄（read-only，hover 顯示計算來源）
- [ ] Dashboard / SnapshotDetail 既有「預估年配息」自動含基金部分（無需額外改動，靠 snapshot 層級合計）

---

### Requirement 21: 信託基金歷史 NAV 與基準日估值

**User Story:** 作為使用者，我希望系統儲存每支基金 10 年的歷史淨值，且在編輯任一 snapshot 時用該 snapshot 基準日 (snapshotDate) 對應的 NAV / FX / 配息來計算 currentValue 與 estimatedDividend，而非永遠用「最新」值。

**Acceptance Criteria:**

- [ ] external-materials-service 新增 `FundNavBackfillService`：呼叫 FundClear `nav-profit/query-history` 分段抓取 10 年歷史 NAV 寫入 `fund_nav`（每段最多一年避免單次 response 過大）；針對 `fund_master.active=TRUE` 的所有基金
- [ ] 對外端點：`POST /internal/fund-nav/backfill?years=10` 一次性觸發；business-services 暴露 `POST /api/fund-nav/backfill?years=10` proxy
- [ ] 配息歷史 (`fund_dividend_history`) 同樣回補近 10 年；新增 `FundDividendBackfillService` + 端點
- [ ] 匯率歷史擴充：`HistoricalDataService.startupBackfill` / 啟動時對 `fund_master` 出現的所有非 TWD 幣別回補 10 年（既有 `backfillExchangeRateFrom` 已支援多幣別，僅需擴充呼叫範圍至 ZAR 等）
- [ ] backend `FundNavService.getNavTwdOnDate(fundCode, basedate)`：取 `fund_nav` 該日（含之前最近一筆，closest-on-or-before）+ 該日 FX；找不到回 Optional.empty
- [ ] backend `FundDividendService.getAnnualEstimateOnDate(fundCode, basedate)`：取 `fund_dividend_history` 中 base_date 落在 `[basedate - 12 months, basedate]` 的紀錄加總 × 該日 FX
- [ ] `AssetService.createSnapshot` / `updateSnapshot`：`resolveFundCurrentValue` / `resolveFundEstimatedDividend` 改用 `basedate` （= `snapshotDate`），不再用「最新」NAV/FX；對既有 snapshot 重存等於用基準日值覆寫手填 `currentValue`（決議 A）
- [ ] BFF `/api/bff/snapshot-form/funds?date=YYYY-MM-DD`：date 參數帶入時，每筆基金 DTO 的 `latestNav` / `latestFxRate` / `twdPerUnit` / `annualDividendPerUnitTwd` 改為「基準日值」；不帶 date 時保持「最新」
- [ ] SnapshotForm `loadFundMasters()` 帶 `form.snapshotDate`；當使用者改 snapshotDate（編輯既有 snapshot 切換不會發生但新建可能改）時重新載入 fund_master 預覽
- [ ] Dashboard / SnapshotDetail 顯示 snapshot 凍結 `currentValue` / `estimatedDividend`，**無需改動**（既已是基準日值）

---

### Requirement 22: 代繳帳戶記錄管理

**User Story:** 作為使用者，我希望系統能集中記錄每筆「定期代繳項目」對應的扣款帳戶與備註（如：市話 + MOD → momo 信用卡 → 用戶號碼 Y046509、台電電費 → momo 信用卡 → 電號 16680357783），以便日後查詢扣款歸屬、核對帳單時不必每次翻找紙本資料。

**Acceptance Criteria:**

- [ ] 新增「自動代繳」主選單（與其他主功能同層，置於「系統設定」之前），路徑 `/payment-accounts`，對應 `PaymentAccountSettingsView`；舊路徑 `/settings/payment-accounts` 自動 redirect 至新路徑以維持書籤相容
- [ ] 代繳記錄欄位包含：**分類**（FK 至 `payment_category`）、**項目**（自由輸入字串，如「市話 + MOD」）、**帳戶**（自由輸入字串，可填銀行帳戶或信用卡名稱，**不**與既有 `bank` / `broker` entity 關聯）、**備註**（自由輸入字串，可放用戶號碼、電號、水號等）、顯示排序
- [ ] 分類儲存於資料庫 `payment_category` 表，不寫死 Enum；提供 `code` / `displayName` / `sortOrder` / `active` 欄位
- [ ] 同頁面上半段提供分類維護區（新增 / 編輯 / 啟用-停用），下半段為代繳記錄主表格，可依分類過濾並依 `payment_category.sortOrder` → `payment_account.sortOrder` 排序顯示
- [ ] 代繳記錄支援新增、編輯、刪除（硬刪除，不做軟停用）
- [ ] 系統提供預設 Seed Data：三個分類 `繳費` / `繳稅` / `服務`（與圖示一致）；代繳記錄本身不 seed，由使用者依實際扣款項目自行新增
- [ ] 後端 API：`/api/settings/payment-categories`（分類 CRUD + active toggle）與 `/api/payment-accounts`（記錄 CRUD）；前端透過單一 BFF `/api/bff/payment-account-settings/**` 取得
- [ ] 分類若被任一代繳記錄引用，停用後仍允許舊紀錄顯示其分類，但停用中的分類不出現於新增 / 編輯 dialog 的下拉選單
- [ ] 不與既有資產 / 銀行 / 券商表產生 FK 關聯；本功能屬獨立的「資料記錄簿」，不影響資產計算

---

### Requirement 23: 警示觸發 Email 通知

**User Story:** 作為使用者，我希望股票觀察清單的警示條件觸發時，系統能自動寄 email 到我的信箱，避免我必須長時間盯盤才能察覺到價。

**Acceptance Criteria:**

- [ ] 警示條件（`StockAlert`）於 `StockAlertService.evaluate()` 判定為觸發、且通過既有 24 小時冷卻機制，寫入 `StockAlertTrigger` 後，必須對**該警示所選定且 `active=true`** 的收件人寄送通知 email（每條警示可獨立挑選收件人子集，見下方「每條警示挑選收件人」）
- [ ] 同一輪輪詢（盤中每 2 分鐘）或同一次手動「立即檢查」內若同時觸發多筆警示，合併成 digest email 寄出；**以收件人為單位分組**：每位收件人只收到一封、僅含「其所訂閱之警示」觸發的 digest（同一收件人同輪多筆仍合併成單封），避免同時段收到多封信。不同收件人訂閱集合不同時各自收到不同內容的信；單封信只有一個收件人（`To` 不再放多人，順帶保護彼此 email 隱私）
- [ ] Digest 寄送節流：以 in-memory 觸發 queue + 排程 flush（每 60 秒一次）實作；queue 內為空時 skip 寄信
- [ ] **寄送時段閘門：警示 email 僅於各市場「開盤 ~ 收盤後 10 分鐘」寄出**。flush 時對 drain 出的每筆觸發，依其市場時區判定當下是否落在 `[開盤, 收盤+10 分]` 且為交易日（台股 09:00~13:40 Asia/Taipei、美股 09:30~16:10 America/New_York、英股 08:00~16:40 Europe/London；**含國定假日判定** —— 假日即使落在平日時段亦回 `false`，與抓價 / 觸發判定共用同一假日表 `MarketDataService.isTradingDay`）；不在時段者本輪丟棄不寄（觸發歷史 `StockAlertTrigger` 已落地，可由「補發」重寄）。多市場混批時逐筆判定 —— 例：台股已收盤 9 小時、美股 / 英股仍盤中時，同一輪只寄出美股 / 英股的觸發，台股的丟棄。設計目的：避免使用者在該市場盤外時段（如深夜）收到當日早已收盤的台股警示信。**手動「補發」不受此閘門限制**（使用者明確要求重寄）。開收盤時刻取自 `MarketZones.openTime/closeTime`，與觸發時間計算（`computeTriggeredAt`）、最後交易日（`lastTradingDate`）共用單一來源
- [ ] Email 主旨格式：`[資產管理] 股票警示觸發 N 筆`（N = **該收件人這封信**去重後的股票檔數）
- [ ] Email 內容必須包含：股票名稱與代號、市場、警示條件（同前端「警示條件」欄位顯示文案）、觸發時間（市場時區 wall time）、觸發股價、技術指標。技術指標一律列出三條均線值：月線（MA20）/ 季線（MA60）/ 年線（MA240）與 K / D；某條均線因歷史資料不足無法計算時，該欄位省略（不再只顯示觸發條件對應的單一均線）
- [ ] **同一股票多條件觸發在 digest / 補發信內合併成一筆**：以 (stockCode, market) 分組，標題列出該股所有觸發條件 label（去重串接），技術快照（觸發時間 / 股價 / 月季年線 / KD）取該股最近一筆觸發；不可同一股票重複出現多個區塊。主旨「N 筆」之 N 改以去重後股票檔數計
- [ ] **email 內嵌「股票分析走勢圖」PNG**：每檔股票區塊下附一張走勢圖，與畫面 `StockAnalysisDialog` 同資料源（`HistoricalDataService.getStockHistory`，抓 120 個月與畫面同範圍、0000 大盤自動改讀指數表）、同 MA / KD 算法，確保 email 圖與畫面數值一致。圖由後端純 Java（XChart）server-side 渲染，無外部服務依賴；email 改以 HTML（MimeMessage + inline CID 圖片）寄送；任一檔圖渲染失敗則略過該圖、文字內容照寄
- [ ] **走勢圖須比照畫面：上 pane 股價 + 月線MA20 + 季線MA60 + 年線MA240，下 pane KD（K/D 兩線、0~100、80/20 虛線參考線）**；所有數值寫進圖內 legend（中文名稱 + 最新值，2 位小數含千分位，如「股價 54.35」「季線MA60 45.53」「K 60.15」「D 67.07」）。KD 用與前端 `calcKD` / `TechnicalIndicatorService` 完全相同的算法（period 9、RSV、K=2/3+RSV/3、D=2/3+K/3、seed 50/50、high/low 缺值 fallback close）
- [ ] **email 走勢圖股價線須標出顯示窗（≈1 年）內的最高 / 最低點**，比照畫面 `StockAnalysisDialog` 的最高 / 最低 markPoint：在最高 / 最低收盤點畫圓點（落在線上）+ 色塊標籤，色塊含**價位（2 位小數含千分位）與日期（`yyyy/MM/dd`）**；紅最高、綠最低（紅漲綠跌）；最高色塊放點下方、最低放點上方並水平夾在圖內避免出界；最高 / 最低同點（區間平盤）只標最高
- [ ] **數值入圖後，email 文字精簡**：移除月線/季線/年線/KD 數值文字（已在圖內 legend）；**保留標題（股名/代號/市場 + 觸發條件）、觸發時間、觸發股價**（觸發當下價，與圖內「最新收盤」語意不同，不可省略）
- [ ] **CJK 字型**：legend 含繁中，runtime image（alpine slim JRE）須裝 `font-noto-cjk`，且須用 `Font.createFonts` 挑出 **TC face**（`.ttc` 的 face 0 為日文變體，直接 `createFont` 會顯示日系字形）
- [ ] 提供「通知收件人」設定頁（`/notification-settings`，主選單「系統設定」群組內），使用者可新增 / 刪除 / 啟停 email 收件人，至少支援 0 ~ N 筆收件人
- [ ] 股票觀察頁（觀察清單 tab）「新增觀察」左側提供「補發」按鈕：**補發範圍限於當前所在的市場子 tab**（台股 / 美股 / 英股），按鈕文字隨 tab 顯示「補發台股」/「補發美股」/「補發英股」，只把**該市場「最後交易日」當天觸發的事件**（盤中則為當日盤中至今的觸發）彙整後**以收件人為單位**各寄一封 digest email（每位收件人只含其所訂閱警示的觸發），與自動 digest 同格式（含月線/季線/年線、KD）。前端把當前 `marketTab` 以 query param `market` 帶給後端；後端 `resendLastTradingDay(market)` 指定時只處理該單一市場、未指定（null / 空）時 fallback 回全市場（向後相容）。「最後交易日」依市場時區判定：交易日且已過開盤＝當日、盤前 / 週末 / 國定假日則回溯至最近交易日（`lastTradingDate` 迴圈以 `MarketDataService.isTradingDay` 跳過週末與假日）。補發為手動全量重寄，不論該事件先前是否已自動寄出。回傳結果含寄出筆數，前端以訊息提示（提示文案標明市場，如「已補發 美股 N 檔股票給 M 位收件人」；無事件 / 無收件人 / Email 服務未啟用時各給對應提示，不寄空信）
- [ ] 收件人持久化於資料庫 `notification_recipient` 表（欄位：`id`、`email`、`active`、`created_at`、`updated_at`），不寫死於程式碼或設定檔；email 欄位需正規化（去除前後空白、轉小寫）並 unique 不可重複
- [ ] **新增 / 編輯警示時防止重複條件**（Task 130）：同一 `(stockCode, market, alertType, maPeriod, threshold)` 視為「完全相同的警示條件」（如 NVDA「低於年線」= `MA_BELOW_PCT` + `maPeriod=240` + `threshold=0`）。`StockAlertService.create` / `update` 存檔前檢查是否已存在相同條件的**另一筆**警示（`update` 以 id 排除自身、`stockCode` 正規化大寫比對；`threshold` 以 `compareTo` 比較避免 scale 差異、`maPeriod` 以 `Objects.equals` 容許 null），若有則**不寫入**並丟 `IllegalArgumentException`「已存在相同的警示條件（{股名} {條件文案}），未重複新增」（→ 400 ProblemDetail）。`active` 與 `recipientIds` **不**納入唯一鍵（重複僅以觸發規則判定，不因啟停 / 收件人不同而視為不同條件）。**前端以 dialog（`ElMessageBox.alert`）呈現後端訊息，而非頂部滑出的 toast**（使用者反映 toast 看起來像系統錯誤）：`stockAlert.create` / `update` 帶 `skipErrorToast` 旗標，使全域 axios 攔截器（`api/index.js`）對這兩支請求**不**跳 `ElMessage`；`StockAlertView.save()` 的 catch 改以 `ElMessageBox.alert(apiErrorMessage(e), '無法儲存警示', { type:'warning' })` 顯示（含重複條件、名稱不符等所有存檔錯誤），對話框關閉後維持表單開啟讓使用者修改。`apiErrorMessage(err)` 為 `api/index.js` 匯出之 helper，取 `ProblemDetail.detail` 優先。**既有的歷史重複列不自動清除**（使用者可於清單以刪除鈕自行移除其中一筆）

**每條警示挑選收件人（Task 125 新增）：**

- [ ] 警示與收件人為**多對多**關係，存於 join 表 `stock_alert_recipient`（欄位：`alert_id`、`recipient_id`，組合 unique）；每條 `StockAlert` 可獨立挑選 0 ~ N 位收件人，觸發時只寄給「該警示選定 ∩ `active=true`」的收件人
- [ ] 警示新增 / 編輯對話框提供「通知對象」多選（列出 `notification_recipient` 全部收件人，以 email 呈現、可全選 / 全不選）；**新增警示預設全部勾選**（沿用既有「全部都收」的直覺）；選 0 位代表此警示觸發時不寄信給任何人（觸發歷史 `StockAlertTrigger` 仍照常落地）
- [ ] 警示 `Request` / `Response` DTO 新增 `recipientIds`（`List<Long>`）；`create` / `update` 以該清單覆寫 join 列（先刪後插），`Response` 回填該警示目前選定的 `recipientIds`
- [ ] 既有警示的相容性：升級時 Liquibase 把**現有每條警示 × 現有每位收件人**全配對回填至 `stock_alert_recipient`（`stock_alert CROSS JOIN notification_recipient`），確保升級後現行「全部都收」的行為不變、不造成寄信回歸
- [ ] 收件人挑選清單供警示頁使用：`GET /api/stock-alerts/recipients` 回 `[{id, email, active}]`（委派 `NotificationRecipientService`，與通知設定頁同一份資料來源），經既有 `/api/bff/stock-alert/**` passthrough 取得，守「一頁一支 BFF」
- [ ] 刪除警示時連帶刪除其 `stock_alert_recipient` 列；刪除收件人時連帶刪除其在 join 表的列（避免孤兒列）
- [ ] 後端 API：`/api/notification-recipients`（CRUD + `PATCH /api/notification-recipients/{id}/active` 切換啟停）
- [ ] BFF passthrough：`/api/bff/notification-settings/recipients/**` → `/api/notification-recipients/**`
- [ ] SMTP 走 Gmail：`smtp.gmail.com:587` STARTTLS；帳密透過環境變數 `MAIL_USERNAME` / `MAIL_PASSWORD`（Gmail App Password，**非**一般登入密碼）注入，**絕不**寫入 yaml / git；寄件人預設 = `MAIL_USERNAME`，可選擇性以 `NOTIFICATION_FROM` 覆寫
- [ ] 收件人為空、或環境變數 `MAIL_USERNAME` / `MAIL_PASSWORD` 未設定時，dispatcher 直接 skip 寄信（僅 log 警告），不可導致警示判斷流程拋例外、阻擋 `StockAlertService.evaluate()`
- [ ] 寄信失敗（SMTP 錯誤、authentication 失敗、network 異常）僅 `log.warn`，**不**重試、**不**讓觸發判斷 transaction rollback；觸發紀錄 `StockAlertTrigger` 必須無論寄信成敗都已落地


---

### Requirement 24: 英股市場類型擴充（LSE UCITS ETF）

**User Story:** 作為使用者，我希望系統能除了台股、美股之外，再支援「英股」這個市場類型，讓我能透過複委託投資 LSE 掛牌的 UCITS ETF（如 CSPX、VWRA、VUSA、EIMI、IWDA），並在所有相關頁面正確呈現。

**Acceptance Criteria:**

- [ ] `market_type` seed 包含「英股」（displayName「英國股市」、sortOrder=3），由 `DataInitializer.seedMarketTypes()` 提供
- [ ] **計價幣別沿用既有 USD 體系**：使用者購買的標的（如 CSPX.L）為 USD-denominated UCITS ETF，`StockHolding.currency` 存 `USD`，市值換算與美股同 path（`shares × price × usdExchangeRate`），不引入 GBP 匯率體系。若未來購買 GBP-denominated UCITS（如 CSP1.L），再另外擴充 currency=GBP 體系
- [ ] **即時股價走 Yahoo Finance `.L` suffix**：`external-materials-service` 的 `PriceFetchClient.getStockPrice(code, "英股")` 打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}.L`，透過 `curlGetWithRetry` 子程序避開 Yahoo Java HTTP fingerprint 偵測，從 `meta.regularMarketPrice` 取 live 價、`previousClose` 算漲跌；source 標 `Yahoo`
- [ ] 倫敦時區（`Europe/London`，由 JVM `ZoneId` 自動處理 BST/GMT 切換）寫入 `MarketClock`：`isUkMarketOpen()` 為週一～五 08:00–16:30、`isUkMarketJustClosed()` 為 16:30–16:50；`PricePoller.scheduledUkIntradayUpdate` cron `0 0/2 8-16 * * MON-FRI` zone `Europe/London`
- [ ] 收盤寫 `stock_price_history`：`ClosePersister.dumpUkCloseFromRedis` 於倫敦 16:32 dump Redis 收盤值至 DB（與台股 13:32、美股 16:02 同 pattern）；`verifyUkCloseWithYahoo` 於倫敦 17:00 用 Yahoo `chart` 單日查當日 OHLC 覆寫 DB 與 Redis（英股無 FinMind 對應 dataset，校正改走 Yahoo historical）
- [ ] 歷史回補：`HistoricalBackfillService.backfillUkStock` 仿 `backfillUsStock`，呼叫 `priceFetch.fetchUkHistoricalRange(code, start, end)` 走 Yahoo `chart` API（timezone `Europe/London`）；今日 bar 一律 skip（沿用 Requirement 7「今日列獨佔給 ClosePersister」）
- [ ] `business-services` 的 `StockPriceService.getLiveAssets` 對 `market="英股"` 持倉套用 `usdExchangeRate` 換算台幣（與美股同 path）；`getMarketStatus()` 回傳新增 `ukMarketOpen` / `ukTime`；`LiveAssetsResponse` 新增 `ukMarketOpen`
- [ ] 服務層 timezone 路由共用 helper（`MarketZones.resolve(market)`）：`美股 → America/New_York`、`英股 → Europe/London`、其餘（含 `台股`、`0000`）→ `Asia/Taipei`。`TechnicalIndicatorService` / `WatchStockService` / `StockAlertService` / `HistoricalDataService` 一律改用此 helper
- [ ] `StockAlertController` 的 `lookup-name` 守門：`0000 + 英股` 直接回空字串（英股無此代號，避免 Yahoo fuzzy match 寫入 stock 主檔），與 `0000 + 美股` 同邏輯
- [ ] `StockAlertService.assertNameMatchesCode`：英股 canonical name 走 `MarketDataFetchService.fetchUkStockName(code)`（Yahoo `chart meta.shortName` for `{code}.L`），其餘規則同既有
- [ ] `MarketDataService.getDividendRate("英股", code)`：走 Yahoo `chart?events=div` 對 `{code}.L` 取 TTM 配息；`getEtfHoldings("英股", code)`：呼叫 Yahoo `quoteSummary?modules=topHoldings` 對 `{code}.L`，失敗則回「未支援」訊息；`isEtf("英股", code)` 採白名單 `[CSPX, VWRA, VUSA, EIMI, IWDA]`
- [ ] 前端各頁面同步支援英股：
  - `StockAnalysisDialog.vue`：`isEtf` 加英股白名單；`etfExternalLinks` 加 iShares 官網 (`https://www.ishares.com/uk/individual/en/products/search?keyword={code}`)
  - `DashboardView.vue`：頂部資產彙整列加「英股現值」一欄；資產配置圓餅圖加第 6 區「英股」（Requirement 9 圓餅圖規格從 5 區擴為 6 區）；KPI / 即時資產估算演算法對英股套 USD 匯率
  - `AssetHistoryView.vue`：歷年資產表格加「英股」欄；今日列以 live-assets 覆寫的邏輯涵蓋英股
  - `SnapshotDetailView.vue`：英股持股小數位顯示 5 位、currency `USD`（與美股同）
  - `SnapshotFormView.vue`：股票區塊加英股區、預設 `currency='USD'`，匯率欄位沿用美元匯率
  - `WatchStockView.vue` / `StockAlertView.vue`：tab 加「英股」、觸發時間時區後綴顯示 `LON`
  - `TradingCalendarView.vue`：顯示英股開收盤狀態（讀 `getMarketStatus()` 的 `ukMarketOpen` / `ukTime`）；LSE 銀行假日由 `MarketDataService.getUkHolidays(year)` 計算（New Year's Day / Good Friday / Easter Monday / Early May / Spring / Summer Bank Holiday / Christmas / Boxing Day，含 weekend forward observed）
  - `RealizedGainView.vue`：已實現損益的 market 篩選 / 新增表單加「英股」選項
- [ ] **不在本次範圍**：GBP 匯率體系、Excel 匯入英股欄位、iShares ETF 持股 scrape（先依靠 Yahoo `topHoldings`）

### Requirement 25: 現金／債券／股票 資產類別三分類

**User Story:** 作為使用者，我希望在「資產配置分佈」圓餅圖中，除了既有的「資產類別（存款／台股／美股／信託基金）」維度外，再多一個以經濟性質分類的「現金／債券／股票」維度，讓持有的債券 ETF（如台股 `00679B`、美股 `TLT`/`BND`）能正確歸入「債券」，而不是和股票混在一起。

**Acceptance Criteria:**

- [ ] **新增維度、不取代既有圖**：「資產配置分佈」卡片新增第 4 個 tab「現金/債券/股票」，與既有「資產類別／台股個股／美股個股」並存；既有三個 tab 行為完全不變
- [ ] **三分類定義**：
  - 現金 = 台幣存款 + 美元存款（含在途款項 `TRANSIT_*`，與既有「台幣/美元存款」口徑一致）
  - 債券 = 規則判定或人工指定為「債券」之股票/ETF 現值 + 債券型基金現值
  - 股票 = 其餘股票/ETF（含英股）現值 + 非債券型基金現值
  - 三類加總 == 既有六分類加總 == `totalAssets`（同一快照同源、口徑一致）
- [ ] **債券自動判定規則（`AssetClassifier`）**：
  - 台股：代號 `00` 開頭且結尾為 `B`（櫃買債券 ETF 命名慣例，如 `00679B`、`00687B`、`00772B`）→ 債券
  - 美股/英股：代號屬內建債券 ETF 清單（`TLT`、`IEF`、`SHY`、`GOVT`、`SGOV`、`BND`、`AGG`、`BNDX`、`LQD`、`VCIT`、`VGIT`、`TIP`、`MUB`、`HYG`、`JNK` 等）→ 債券
  - 信託基金：基金名稱含「債」、`bond`、「高收益」或「收益」（不分大小寫）→ 債券（高收益債／收益型債券基金名稱多不帶「債」字，如「富達亞洲高收益」「聯博美國收益基金」）。注意「入息」「股息」屬高股息股票型基金，不列為債券關鍵字
  - 其餘 → 股票
- [ ] **可人工微調（override 優先於規則）**：標的主檔 `stock` 新增 nullable 欄位 `asset_class`；基金則因 `fund_holding.fund_code` 多為 NULL、以「名稱」為穩定識別，故以 `fund_class_override(fund_name PK, asset_class/stock_style/bond_term 三欄皆 nullable)` 表存逐檔 override。兩者皆：非空/有列時覆蓋規則、否則回退規則；標一次即跨所有快照生效（符合正規化，per 標的/名稱存一份）。基金 override 由 `AssetService.getAssetHistory()`／`getHoldingsClassified()` 一次查 `fundClassOverrideRepo.findAll()` 建 `Map<fundName, FundClassOverride>` 後傳入 `classifyFund`／`classifyStockStyle`／`classifyBondTerm`
- [ ] **禁止 Enum 寫死**：三個資產類別（現金/債券/股票）以 `asset_class` seed 表存入資料庫（`DataInitializer.seedAssetClasses()` 提供 `CASH`/`BOND`/`STOCK`），提供 `/api/settings/asset-classes` 管理端點與前端設定頁下拉來源
- [ ] **設定頁「資產類別歸類」**：列出所有持有過的標的（`stock` 主檔）與基金（`fund_holding` 去重名稱，市場欄顯示「基金」、`code` 欄即名稱），顯示每檔目前生效之資產類別與來源（規則／人工），可用下拉將個別標的/基金指定為「債券／股票」或還原為「自動（依規則）」；基金列「指定類別」與「指定細分」（成長/收益型、短/中/長期）皆可編，與個股一致；前端走自己頁面的 BFF（`/api/bff/asset-class-settings/**` passthrough）。`PUT /api/settings/securities/{asset-class|stock-style|bond-term}` 皆以 `market` 區分：`基金` → upsert/刪 `fund_class_override` 對應欄（key=名稱），其餘 → 改 `stock` 對應欄
- [ ] **同一 business service API**：三分類值由 `business-services` 的 `/api/snapshots/history` 一併回傳（新增 `cashValue`/`bondValue`/`stockValue` 三欄，由 `AssetService.getAssetHistory()` 計算），與既有六分類同一資料源；圓餅圖新 tab 直接 render，hover／換快照即時反應（與既有圖一致），不另開 lazy endpoint
- [ ] **基金逐檔細分 override（成長/收益型、短/中/長期）**：基金的 `asset_class`／`stock_style`／`bond_term` 皆可逐檔 override，存於 `fund_class_override`（fund_name PK，三欄皆 nullable；任一欄非空即建列、全清則刪列），設定頁基金列「指定類別」「指定細分」與個股一致可編。基金無 `dividendRate`，故 STOCK 基金風格自動為成長型、override 可改收益型；BOND 基金期別自動依名稱、override 可改。
- [ ] **不在本次範圍**：新 tab 套用盤中 live 行情（採快照凍結之逐筆 `currentValue`，巨觀資產配置毋須 intraday 精度）

### Requirement 26: 股票「成長型／收益型」風格細分

**User Story:** 作為使用者，我希望把「股票」這一類再依投資目的細分為「成長型」（追求資本利得、低配息）與「收益型」（追求現金流、高股息），讓我看出投組裡有多少比例是為了領息而持有的高股息 ETF。

**Acceptance Criteria:**

- [ ] **第二維度、與資產類別正交**：「成長/收益」是套在「股票（STOCK）」之上的子維度，與 `asset_class`（現金/債券/股票）獨立並存；只對有效 `asset_class = STOCK` 者細分，債券／現金一律不分（不顯示、不參與加總）
- [ ] **在「現金/債券/股票」圖以雙環細分、不另開 tab**：將既有第 4 tab「現金/債券/股票」圓餅圖改為雙環（drill-down donut）——內圈維持現金/債券/股票總覽，外圈把「股票」再細分成成長型／收益型（現金/債券外圈沿用同色、與內圈角度對齊）；不新增 tab。`growthValue + incomeValue == stockValue`
- [ ] **自動判定規則（`AssetClassifier.classifyStockStyle`），優先序由高到低**：
  1. 逐檔 override（`stock.stock_style` 非空）→ 直接採用
  2. 高股息 ETF 內建清單（台股 `0056`/`00878`/`00919`/`00713`/`00882`/`00929`/`00940` 等、美股 `SCHD`/`VYM`/`HDV`/`DVY`/`SPYD`/`JEPI` 等）→ 收益型（命中清單不受殖利率波動影響）
  3. 殖利率門檻：該快照該持股 `dividendRate >= 門檻`（預設 4%）→ 收益型，否則成長型
  4. 殖利率缺值（null／0，尚未 enrich）→ fallback 成長型
- [ ] **殖利率來源為逐快照逐持股**：判定用 `StockHolding.dividendRate`（非標的主檔），故自動判定在 `AssetService.getAssetHistory()` 逐快照迴圈內、以該快照的殖利率計算
- [ ] **門檻可由設定頁調整、不寫死**：殖利率門檻存於 `stock_style` seed 表「收益型(INCOME)」列的 `dividend_threshold` 欄（預設 `0.0400`），由 `/api/settings/stock-styles` 管理端點與設定頁可改；classifier 讀此值（缺則 fallback 4%）
- [ ] **禁止 Enum 寫死**：兩個風格（成長型/收益型）以 `stock_style` seed 表存入資料庫（`DataInitializer.seedStockStyles()` 提供 `GROWTH`/`INCOME`），提供 `/api/settings/stock-styles` 端點
- [ ] **可人工微調**：標的主檔 `stock` 新增 nullable 欄位 `stock_style`、基金則於 `fund_class_override.stock_style`（key=名稱）；非空覆蓋規則、`null`／無列 依規則。標一次跨所有快照生效
- [ ] **同一設定頁**：擴充既有「資產類別歸類」頁——同一列除「資產類別」外多一欄「股票風格」下拉（自動／成長型／收益型），僅在該列有效 `asset_class = STOCK` 時可編輯（債券/現金該欄 disabled）；基金列同樣可編（基金「自動」= 成長型，無殖利率自動判定）；並提供「收益型殖利率門檻」可調控制項
- [ ] **同一 business service API**：`growthValue`/`incomeValue` 由 `/api/snapshots/history` 一併回傳，與既有分類同源；雙環外圈直接 render、hover／換快照即時反應
- [ ] **基金風格**：基金無 `dividendRate`，自動一律成長型；逐檔 override 可改收益型（存 `fund_class_override.stock_style`）。**不在本次範圍**：以本益比／成長率等進階指標判定（先只用殖利率）

### Requirement 27: 債券「短／中／長期」細分與雙層圓餅同色系配色

**User Story:** 作為使用者，我希望「現金/債券/股票」雙層圓餅的外層子分類採「與母分類同色系、深淺略不同」的配色，並把「債券」再依存續期間細分為短期／中期／長期，看出債券部位的天期結構。

**Acceptance Criteria:**

- [ ] **債券細分短/中/長期**：套在 `asset_class = BOND` 之上的子維度。雙層圓餅外層把「債券」拆成 短期／中期／長期；`bondShortValue + bondMidValue + bondLongValue == bondValue`
- [ ] **自動判定規則（`AssetClassifier.classifyBondTerm`），override 優先**：依標的名稱所帶年期判定——短期：名稱含 `1-3`/`0-3`/`0-1`/`1-5`/`短`/`month`/`貨幣`；長期：含 `20`/`25`/`30年`/`長`/`10年以上`/`10-20`；中期：含 `7-10`/`5-10`/`3-7`/`3-10`/`中`；無年期資訊（如一般公司債）預設中期。例：`元大美債20年`→長、`富邦美債7-10`→中、`元大美債1-3`→短、`SGOV(0-3 Month)`→短、`元大AAA至A公司債`→中（可 override）
- [ ] **可人工微調**：標的主檔 `stock` 新增 nullable 欄位 `bond_term`（對應 `BondTerm.code`）、基金則於 `fund_class_override.bond_term`（key=名稱）；非空覆蓋規則。設定頁「資產類別歸類」同一列「指定細分」欄，債券列顯示短/中/長期下拉（股票列則顯示成長/收益型），股票/基金一致可編
- [ ] **禁止 Enum 寫死**：短/中/長期以 `bond_term` seed 表存入資料庫（`DataInitializer.seedBondTerms()` 提供 `SHORT`/`MID`/`LONG`），提供 `/api/settings/bond-terms` 端點
- [ ] **同色系配色（雙層圓餅）**：外層子分類與內層母分類同色系、深淺略不同——現金/台幣/美元為藍系（`#3b82f6`/`#2563eb`/`#93c5fd`）、債券/短/中/長期為 teal 系（`#14b8a6`/`#5eead4`/`#2dd4bf`/`#0f766e`）、股票/成長型/收益型為琥珀系（`#f59e0b`/`#fcd34d`/`#d97706`）；成長型不再用靛藍
- [ ] **同一 business service API**：`bondShortValue`/`bondMidValue`/`bondLongValue` 由 `/api/snapshots/history` 一併回傳，雙層圓餅外層直接 render
- [ ] **外圈 hover 列出持股、移除圖例**：雙層圓餅移除下方 legend；滑鼠移到外圈「股票」子分類（成長型/收益型）或「債券」子分類（短/中/長期）時，tooltip 列出該分類底下的個別持股名稱與金額。逐持股分類由 `business-services` `GET /api/snapshots/{id}/holdings-classified`（同一套 classifier/override/門檻，與圓餅加總一致）提供，前端走 BFF `GET /api/bff/dashboard/holdings-classified/{id}`（lazy，切到該 tab 才抓、隨選中/hover 快照切換）
- [ ] **基金期別**：基金 BOND 期別可逐檔 override（存 `fund_class_override.bond_term`），未設則依名稱年期規則。**不在本次範圍**：以實際存續期間（duration）數值判定（先用名稱年期字樣）

---

### Requirement 28: Gmail OAuth2 登入與多租戶資料隔離

**User Story:** 作為系統管理者與一般使用者，我希望用各自的 Gmail 帳號登入系統，每個人只能管理與檢視「自己的」資產，管理者（`tw.leader@gmail.com`）能切換檢視任何使用者的資產並管理使用者帳號，以確保多人共用同一套系統時的資料隱私與權限分離。

**Acceptance Criteria:**

- [ ] **Gmail OAuth2 登入**：系統採 Spring Security OAuth2 Login（authorization code 重導流程），provider 為 Google。**OAuth2 登入端點落在 BFF（Spring Cloud Gateway / WebFlux reactive，唯一對外入口）**，非內網的 business-services。未登入者存取受保護資源一律先導向 Google 登入。
- [ ] **Session 機制**：登入成功後 BFF 以 server-side `WebSession` + `SESSION` cookie（`HttpOnly`、`SameSite=Lax`、prod `Secure`）維持登入態；登入只為辨識身分，不保留 Google access/refresh token。前端為 SPA，登入成功後固定 302 導回前端 `/`，由前端打 `GET /api/me` 決定後續導向。
- [ ] **`GET /api/me`**：回傳目前登入者 `{ email, name, picture, role(ADMIN/USER), status(PENDING/ACTIVE/DISABLED), effectiveUserId, effectiveUserName, isImpersonating, switchableUsers[] }`；`switchableUsers` 僅 ADMIN 才填；`status` 即時查資料庫（核准後不需重登即生效）。未登入時受保護 API 回 **401 JSON**（非 302），由前端攔截後整頁跳轉 `/oauth2/authorization/google`。
- [ ] **管理者固定身分**：`tw.leader@gmail.com` 永遠為 `role=ADMIN`、`status=ACTIVE`；系統現有的全部資料（快照、損益、代繳、警示、通知收件人）於資料遷移時一律歸屬給管理者。
- [ ] **新使用者待核准**：任何其他 Gmail 首次登入，自動建立 `app_user`（`role=USER`、`status=PENDING`）。PENDING 使用者可完成 Google 登入，但呼叫業務 API 一律被擋（回 `403 {code:"ACCOUNT_PENDING"}`），前端導向「等待核准」頁；管理者於使用者管理頁核准成 `ACTIVE` 後始能使用。`DISABLED` 同樣被擋。
- [ ] **多租戶資料隔離（每人只能看自己）**：下列「資產類」資料以 `owner_user_id` 隔離，每個使用者只能讀寫自己的：`asset_snapshot`（含子表 `bank_deposit`/`stock_holding`/`fund_holding` 經 snapshot 繼承）、`realized_gain`、`payment_account`、`stock_alert`（含 `stock_alert_trigger`、`stock_alert_recipient` join、`watch_stock` 衍生清單）、`notification_recipient`。參考／行情／設定主檔（`bank`、`broker`、`stock`、各 `*_history`、`market_type`、`deposit_type`、`asset_class`、`fund_master`、`fund_nav` 等）維持**全系統共用**，不加 owner。
- [ ] **過濾強制執行**：business-services 以 request-scoped `CurrentUserContext`（讀 BFF 傳來的 `X-User-Id`/`X-User-Role`/`X-User-Status` header）+ Hibernate `@Filter`（`owner_user_id = :ownerId`）統一過濾所有受隔離 entity 的查詢；寫入路徑以 `CurrentUserContext.effectiveUserId()` 設定 owner。**filter 僅在有 HTTP request 時啟用**，背景 cron（警示偵測 / email dispatcher）不啟用、維持掃全體 active alert 的既有行為。
- [ ] **管理者代看（使用者切換）**：管理者頁面右上角提供使用者下拉，選定某使用者後整個系統以該使用者視角呈現（`effectiveUserId` = 選定目標）。切換 `POST /api/impersonate {userId}` 僅 ADMIN session 可呼叫並寫入 BFF session；之後每個下游呼叫帶的 `X-User-Id` 即為該目標，查詢路徑與「看自己」完全相同、零分支。一般使用者 `effectiveUserId` 恆等於自己、無法變更。
- [ ] **使用者管理（ADMIN）**：新增使用者管理頁（`/settings/users`），管理者可列出所有使用者、核准（PENDING→ACTIVE）、停用（→DISABLED）、設定角色。後端 `/internal/users/**` 管理端點限 ADMIN。
- [ ] **備份／還原限管理者**：「系統設定 > 備份/還原 資料」僅管理者可用。非管理者前端選單**不顯示**該項與「使用者管理」項；後端雙層阻擋——BFF `/api/bff/backup-restore/**` 與 business-services `/api/backups/**` 皆限 `ROLE_ADMIN`，非 ADMIN 回 403。備份/還原為全系統操作，不套 owner filter。
- [ ] **唯一性多租戶化**：原本全域唯一的欄位改為「每使用者唯一」——`asset_snapshot.snapshot_date` 改 `(owner_user_id, snapshot_date)` 複合唯一（不同使用者同一天各可有一筆快照）、`notification_recipient.email` 改 `(owner_user_id, email)` 複合唯一。
- [ ] **資料遷移（Liquibase）**：以 changeset 建立 `app_user` 表、先 seed 管理者列、為各受隔離表加 `owner_user_id` 並把現有資料 backfill 給管理者、再加 `NOT NULL`+FK+index 與複合唯一；`ddl-auto:none`，schema 變更一律走 Liquibase。
- [ ] **部署**：Nginx／vite proxy 需把 `/oauth2/**`、`/login/oauth2/**`、`/logout` 導向 BFF 並透傳 `X-Forwarded-Proto`（確保 prod https redirect-uri 正確）；Google client-id/secret 由環境變數注入 BFF；cookie session 須一致設定 CSRF（`XSRF-TOKEN`/`X-XSRF-TOKEN`）、`withCredentials`、CORS `allowCredentials=true` 且具名 origin。

### Requirement 29: 資安弱點修補（Stored XSS／設定授權／行情參數注入／個資入版控）

**User Story:** 作為系統擁有者，我希望修補一次全系統資安審查所發現的弱點，讓「一般使用者無法藉由持股股名注入 script 於管理者代看時提權」「一般使用者無法竄改全體共用的參考設定」「外部行情抓取的參數無法被注入」「真實個人財務資料不再進入版本控制」，以在多人共用時維持隔離與資料機密。

**Acceptance Criteria:**

- [ ] **Stored XSS 防護（Dashboard tooltip）**：ECharts tooltip 的 `formatter` 回傳字串會被當 raw HTML 渲染。所有把「使用者/外部可控字串（持股股名、股票代號、英文全名、基金名、銀行名）」拼入 tooltip HTML 的地方，一律先經 `frontend/src/utils/escapeHtml.js` 的 `escapeHtml()` 轉義（`& < > " '`）。涵蓋 `DashboardView.vue` **全部六處**會插入外部字串的 tooltip：資產分類細分圓餅（`fmtRow`/`fmtTooltip`）、台股穿透圓餅、美股穿透圓餅、股票持股橫條圖（`stockBarOption`）、信託基金橫條圖（`fundBarOption`）、各銀行存款橫條圖（`bankOption`）。ECharts `label.formatter`（`{b}` 樣板、canvas 文字非 HTML）與只插入數字/日期/硬編碼系列名（`p.seriesName`）的 formatter 不受影響。防護要能擋住「股名/基金名存成 `<img src=x onerror=...>` → 管理者代看該使用者時於 admin session 執行」的提權鏈。
- [ ] **全域共用設定寫入限 ADMIN**：`bank`/`broker`/`deposit_type`/`market_type`/`asset_class`/`stock_style`/`bond_term`/`transit_fund_type`/`payment_category`、`stock` 主檔的 asset-class/style/term override，以及 `fund_master`（信託基金主檔）皆為**全體租戶共用**參考資料。其寫入（`POST`/`PUT`/`PATCH`/`DELETE`，落在 backend `/api/settings/**` 與 `/api/funds(/**)`）**限 `ROLE_ADMIN`**；讀取（`GET`）維持開放給任何已登入者（下拉選單需讀取）。雙層強制：BFF `SecurityConfig` 對前端可觸及的共用設定路徑（`/api/settings/**`、`/api/funds(/**)` 與各 `/api/bff/*-settings/**`，但**排除** per-user 的 `payment-account-settings/accounts` 與 `notification-settings/recipients`）的寫入方法限 ADMIN；backend `AdminGateInterceptor` 對 `/api/settings/**` 與 `/api/funds(/**)` 非 GET 再擋一次。per-user 資料（`/api/payment-accounts`、`/api/notification-recipients`）與操作型 refresh/backfill（`/api/fund-nav`、`/api/fund-dividend`、`/api/market-data/*/refresh`）不受影響。
- [ ] **外部行情參數白名單**：使用者可控且會被外部行情 client 串進外部 API URL query/path 的參數一律白名單驗證：
  - `MarketDataController` 的 `code`／`market`／`currency`：`@Validated`+`@Pattern`（`code`=`^[A-Za-z0-9.\-]{1,12}$`、`market`=`^[\p{L}0-9]{1,10}$`、`currency`=`^[A-Za-z]{3,4}$`），涵蓋所有 rewrite 至 `/api/market-data/*` 的 BFF stock-analysis 路徑。
  - `StockAlertController` 的 `lookup-name`（`code`/`market` 打 `/internal/stock-name`）：同規則 `@Validated`+`@Pattern`。
  - `MacroHistoryController` 的 `index-intraday`（`market` 打 Yahoo）：以已知指數白名單 `{TWSE} ∪ OVERSEAS_INDEX_CODES` 檢查，未知回 400。
  - 驗證違規回 `400`（`GlobalExceptionHandler` 處理 `ConstraintViolationException`／`IllegalArgumentException`）。host 皆硬編碼故非任意 SSRF，此為參數注入強化。
- [ ] **真實個人財務資料 / 本機設定不入版控**：`db/init/*.sql`（PostgreSQL seed dump，含真實 `stock_holding`/`bank_deposit`/`realized_gain`）、H2 `*.mv.db`/`*.trace.db`、`/data/`、`backend/data/`、`stock_alert_data.sql`、以及 `.claude.bak/`（本機 Claude 設定備份，含本機路徑與允許查詢清單）一律移出版本控制並列入 `.gitignore`；本機檔案保留（現行部署 seed 不受影響），未來需保留「不含真實資料」的種子檔時以 `git add -f` 明確加入。（註：git 歷史仍含既有資料，若 repo 曾外流應另行以 filter-repo 清史並視同外洩處理。）

### Requirement 30: 延後低風險資安項修補（跨租戶收件人綁定／Cookie Secure 條件化／DB port 綁定／DGBAS TLS 驗證／匯入 owner-scoped 刪除）

**User Story:** 作為系統擁有者，我希望修補一次全系統資安審查中「當時評估為低風險而延後」的五個項目，讓「使用者無法把他人 email 掛成自己警示的收件人濫發／洩漏」「登入 cookie 在正式環境可帶 `Secure` 而不會壞掉 dev 純 http 登入」「資料庫不對外網暴露」「政府站抓取恢復 TLS 憑證驗證而非全域停用」「Excel 匯入清空已實現損益時只清當前使用者」，在多人共用與部署面維持隔離、機密與傳輸安全。

**Acceptance Criteria:**

- [ ] **通知收件人跨租戶綁定防護（IDOR）**：`StockAlertService.replaceRecipients()` 於寫入 `stock_alert_recipient` join 前，必須以 owner-filtered 查詢（`NotificationRecipientRepository` 受 `ownerFilter` 的派生查詢）過濾傳入的 `recipientIds`，只保留屬於當前租戶（`tenantGuard.requireCurrentUserId()`）的 `notification_recipient`，非本人擁有的 id 一律不寫入。過濾收斂在 `replaceRecipients` 內，同時涵蓋 `create()` 與 `update()` 兩條寫入路徑。修補前：join 寫入端把 `recipientId` 當純數字直插，繞過 `@Filter` 與 `assertOwned` 兩道防線，攻擊者可在 request body 帶入他人 `NotificationRecipient` id 綁進自己的警示，觸發時把通知寄到他人 email（跨租戶個資洩漏／濫發）。`create()` 的「`recipientIds==null` → 預設全部（自己的）收件人」與「空 list → 不寄」語意維持不變。
- [ ] **收件人寄信讀取端 defense-in-depth（Task 145）**：寄信讀取端 `StockAlertRecipientRepository.findActiveEmailsByAlertId()` 由背景寄信 cron（`AlertNotificationDispatcher`，無 HTTP request context → Hibernate `ownerFilter` 不啟用）呼叫，且 `stock_alert_recipient` join entity 無 `owner_user_id`／無 `@Filter`。即使寫入端（`replaceRecipients`，見上一條）已把關，若 join 表存在修補上線前遺留的跨租戶殘列（攻擊者曾以 IDOR 綁入他人 `recipientId`），寄信時仍會把觸發通知寄到他人租戶的 email。故此查詢必須額外 join `StockAlert` 並加「收件人須與警示同一擁有者」條件（`r.ownerUserId = a.ownerUserId`），從讀取端保證只寄給與警示同租戶的收件人。同時提供一次性 Liquibase changeset，刪除 `stock_alert_recipient` 中「`notification_recipient.owner_user_id <> stock_alert.owner_user_id`」的既存殘列（冪等；無殘列時刪 0 列，仍保留作一次性資料清潔）。此為與上一條寫入端把關互補的縱深防禦，不改變正當收件人（同租戶）之寄信行為（不因多 join 條件誤殺自己的收件人）。
- [ ] **Session／代看 cookie 可條件化 `Secure`**：BFF 的 Spring Session `SESSION` cookie 與 `IMPERSONATE_UID` 代看 cookie 支援 `Secure` 屬性，由單一環境變數開關 `SESSION_COOKIE_SECURE`（預設 `false`）統一控制。dev 與「目前仍為純 http」的部署維持不帶 `Secure`（盲目 `Secure=true` 會讓瀏覽器在 http 下不回送登入 cookie → 壞掉登入）；正式環境於 Nginx 終止 TLS（https）後把 `SESSION_COOKIE_SECURE=true` 帶起即生效。三處 cookie 建構點——`application.yml` 的 `server.reactive.session.cookie.secure`、`TenantWebFilter` 寫入／清除代看 cookie、`SecurityConfig.clearImpersonateCookie()`——必須用同一開關取值，維持屬性一致（否則正式環境「登入／登出清代看」會因屬性不符而清不掉殘留）。`SameSite=Lax`、`HttpOnly` 維持不變。
- [ ] **PostgreSQL 不對外網暴露**：`docker-compose.yml` 的 postgres port 由 `"5432:5432"`（綁 `0.0.0.0`、含外部網卡）收斂為 `"127.0.0.1:5432:5432"`，僅本機 loopback 可連（本機 DB 工具如 DBeaver/psql 仍可用），外部網卡不再對外開放、不再只靠 `.env` 密碼保護。容器間仍走 `asset-net` 內部網路以 service name `postgres:5432` 連線，不受影響；變更僅需 recreate postgres 容器（命名 volume 資料不動）。
- [ ] **DGBAS 抓取恢復 TLS 憑證驗證**：`MacroDataFetchClient.fetchDgbasNationalIncome()` 移除 `curl -k`（`--insecure` 同時關掉憑證鏈與主機名驗證），改用 Java `HttpClient` 抓取。因 `ws.dgbas.gov.tw` 伺服器只送 leaf、未送中繼憑證，將公開可得且由公信根 `TWCA Global Root CA` 簽發的中繼憑證 `TWCA Secure SSL Certification Authority` 打包進 `external-materials-service` 專屬 truststore 作為信任錨，使 leaf → 中繼建鏈成立並完整驗證主機名（leaf SAN 含 `ws.dgbas.gov.tw`）。此專屬 `SSLContext` 只用於 DGBAS 抓取，不影響其他連線；抓取失敗維持回空 map → IMF fallback。
- [ ] **Excel 匯入清空已實現損益 owner-scoped**：`ExcelImportService.importRealizedGains()` 匯入前的清空由 `gainRepo.deleteAll()` 改為 owner-scoped 刪除——注入 `TenantGuard`、以 `gainRepo.deleteByOwnerUserId(tenantGuard.requireCurrentUserId())` 只清當前使用者的 `realized_gain`，不再依賴 `ownerFilter` 對 `deleteAll()` 的隱性副作用；無身分（背景無 request）時不執行清空以免誤刪。此匯入路徑目前無 controller 進入點（不可達），屬防禦性修補，避免日後接上入口時退化成刪全體使用者資料。

### Requirement 31: 今日股市分析（每交易日 07:30 由 AI 判斷當天台股走向）

**User Story:** 作為投資人，我希望每個台股交易日開盤前，系統能根據過去一年的台股與美股走勢、以及近期國內外財經新聞，自動判斷「今天台股可能的走向」並給我一段有理由的分析，讓我在開盤前就掌握多空氛圍與關鍵變數；越近期的走勢與新聞應占越高的權重。

**Acceptance Criteria:**

- [x] **排程時點與交易日閘門**：每個台股交易日（週一～五且非台股國定假日）07:30（`Asia/Taipei`）自動執行一次「今日股市分析」；非交易日（週末／台股休市日）跳過不執行、不呼叫 LLM。交易日判定重用 business-services 既有 `MarketDataService.isTwTradingDay(LocalDate)`（週末 + TWSE Open API 假日表）。cron 以 `MON-FRI` 觸發後再判假日。
- [x] **開機自我修復（self-heal）**：服務於 07:30 排程時點未運行（重啟／crash／部署）時，啟動後若「今天為台股交易日且現在時間已過 07:30 且 `daily_market_analysis` 尚無今日該筆」，補跑一次，避免當日分析漏產。比照 `IndexDailyRefreshScheduler` 的 self-heal 慣例。
- [x] **分析輸入（走勢一律讀本地 DB、不即時抓外部行情）**：以「台股大盤（TAIEX）近一年日線收盤」（`twse_index_daily_history`）＋「美股主要指數近一年日線收盤」（`us_index_daily_history` 之 `DJI`／`SPX`／`IXIC`／`SOX`）為量化輸入，餵給模型時明確標註「越近期越重要」（近 20 日另附細節、資料由舊到新排列並在提示詞要求對近期加權）。此為既有指數日線的唯一來源，符合「同義欄位、同一 business service API／同一事實來源」原則，不另建行情抓取。
- [x] **財經新聞由 Claude 內建網路搜尋取得**：近期國內外財經新聞由模型的 `web_search` server tool 於分析當下即時搜尋（不自建新聞抓取管線與資料表）；提示詞要求優先採用近 1～2 週、且越近期權重越高的財經新聞（台股、美股、Fed／利率、匯率、地緣、法人動向等）。
- [x] **模型與輸出**：使用 Claude Opus 4.8（`claude-opus-4-8`，adaptive thinking + `web_search_20260209` server tool）產生結構化判斷：方向（偏多 `BULLISH`／偏空 `BEARISH`／中性 `NEUTRAL`）、信心度（0–100）、當日走向總結（繁體中文一段）、關鍵因素清單、參考新聞摘要（標題／來源／連結）、台股與美股近期走勢摘要。輸出以 JSON 交還後端解析入庫；解析採「取首個 `{` 至末個 `}`」容錯，並忽略未知欄位。全程使用台灣繁體中文。
- [x] **歷史保存**：結果存入 `daily_market_analysis`，每個交易日一筆（`analysis_date` 主鍵）；同日重跑覆蓋當日該筆（upsert）。此為全域參考資料（不分租戶、無 `owner_user_id` 欄位，比照 `twse_index_daily_history`／`us_index_daily_history`／交易日曆假日）。
- [x] **前端頁面「今日股市分析」**：左側選單新增「今日股市分析」項；頁面顯示當日（或最近一筆）判斷——方向以配色標示（**台股漲紅跌綠**：偏多紅、偏空綠、中性灰）、信心度、走向總結、關鍵因素、參考新聞（可點連結）、台股／美股走勢摘要、產生時間與所用模型；下方可回看過去每日的判斷歷史。多 panel 資料以單一 BFF 聚合回傳。
- [x] **手動重新分析（限管理者）**：管理者可於頁面按「重新分析」立即重跑當日分析（bff 對 `POST /generate` 限 `ROLE_ADMIN`，backend 端以 `CurrentUserContext.isAdmin()` 縱深防禦）；一般使用者唯讀、不顯示該按鈕。
- [x] **金鑰未設定與失敗的優雅降級**：`ANTHROPIC_API_KEY` 未設定時不報錯，排程與手動觸發皆安全跳過並記 `status = NOT_CONFIGURED`，頁面顯示「尚未設定 Anthropic API 金鑰」；LLM 呼叫或 JSON 解析失敗時記 `status = FAILED` ＋錯誤訊息、保留 `raw_response` 供除錯，不影響其他排程，頁面顯示失敗狀態且管理者可重試。金鑰經環境變數注入 business-services，不入版控。
- [x] **模型可於頁面調整（成本控管，限管理者）**：管理者可在「今日股市分析」頁的下拉選單切換分析模型（`claude-opus-4-8`／`claude-sonnet-5`／`claude-haiku-4-5`——品質對成本），選擇持久化於單列設定表 `market_analysis_setting`（`id=1`），**下次分析（排程或手動）即生效、無需改環境變數或重啟**。後端於每次產生時以 `resolveModel()` 取「設定值 → 否則 `ANTHROPIC_MODEL` 環境預設」。可選模型清單為後端 curated 技術白名單（僅有效 Claude model id，非使用者可自訂之業務分類，故不套用「Enum 必須入庫管理」規範）；`PUT` 僅接受白名單內 id，防注入無效／任意 model。設定寫入限管理者（bff `PUT` 限 `ROLE_ADMIN` + backend `CurrentUserContext.isAdmin()` 縱深防禦）；一般使用者唯讀、不顯示選單。`ANTHROPIC_MODEL` 環境變數保留為初始預設／後備。
