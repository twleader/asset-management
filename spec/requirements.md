# Requirements

## Introduction

資產管理系統（Asset Management System）是一套個人財務資產追蹤與分析平台，協助使用者整合銀行存款、股票、基金等多元資產，提供即時市場資訊、歷史趨勢分析與損益報告，讓個人財務狀況一目了然。

## 文件慣例（checkbox 語意）

> **本文件的 `- [ ]` / `- [x]` 僅是 Acceptance Criteria 的條列標記，不代表實作完成狀態。**
>
> - 實作完成狀態**一律以 `spec/tasks.md` 為準**，請勿以本文件是否勾選來推斷功能已否實作。
> - 現存 `[x]` 與 `[ ]` 混用（部分 Requirement 甚至同一節內並存）屬歷史遺留寫法，不具任何語意，也不需要逐條回頭補勾。
> - 新增 AC 時一律沿用 `- [ ]`。

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
- [ ] **颱風假 / 臨時休市（比照國定假日一體整段休市）**：台股遇颱風等臨時停班停課時是否休市，證交所依「**臺北市政府是否宣布停止上班**」認定；此類臨時休市**不在 TWSE 年度 holidaySchedule**（年初即公告的固定假期）中，須另行偵測，否則實際休市當日 market-status 仍誤顯示「開盤中」、抓價 / 收盤排程照跑。權威來源＝行政院人事行政總處（DGPA）「天然災害停止上班及上課情形」公告（`https://www.dgpa.gov.tw/typh/daily/nds.html`），判讀**臺北市**當日是否「停止上班」——僅晚間 / 傍晚起（或收盤 13:30 後才起）停班、未涵蓋 09:00–13:30 交易時段者視為照常交易。偵測到即 upsert 至 `tw_market_closure`（全域參考資料、無 owner，比照 `news_headline` / `twse_index_daily_history`），並 **union 進 `MarketDataFetchService.getTwHolidays`（台股假日唯一入口）**，使 `isTwTradingDay` / `isMarketOpenNow` / market-status（開盤中→休市）/ `PricePoller`（不抓價、不寫 Redis）/ `ClosePersister`（不 dump 收盤）/ 警示觸發 / 每日備份 / 08:45 今日股市分析 / 交易日曆全部與國定假日同一條 cascade 一體 skip（前端零改動）。偵測排程：`external-materials-service` 於台股開盤前（`0 0/15 5-6 * * MON-FRI；0 0 7 * * MON-FRI`＝05:00–07:00 Asia/Taipei，Task 187 由 5-8＝05:00–08:45 縮短）每 15 分鐘爬 DGPA，開機 self-heal 補跑一次（部署 / 重啟後立即修正當日）。傳播：`business-services` 端 TWSE 假日改採「**當年度短 TTL（10 分鐘）**」快取（過去 / 未來年度仍永久快取），確保同日**任何時刻**（早盤排程、開機 self-heal、手動 detect）新偵測到的休市於 TTL 內傳播至 business 側（market-status / 08:45 分析 / 警示 / 備份 / 交易日曆），不受固定時窗限制、亦不會漏接盤中或部署後才偵測到的休市；ext 抓取失敗不以空表毒化快取（沿用前一次成功值）。DGPA 抓取失敗 / 查無臺北市狀態時保守維持交易日（與 TWSE 假日抓取失敗退化一致）；只新增臨時休市、不覆寫既有 TWSE 固定假日
- [ ] **颱風假資料層一體休市（偵測落後補清 + 「當日」分時回退最近交易日）**：颱風假可能在盤中（甚至收盤後）才由 DGPA 公告 / 偵測到（例：部署晚於當日盤中）；在偵測寫入 `tw_market_closure` 之前 `isTwTradingDay` 仍回 true，13:32 `ClosePersister` 收盤 dump 與盤中 polling 會把「昨收平盤」誤塞進 `stock_price_history` 當日列與 Redis `price:ticks:台股:*:date` 分時 bucket，導致「當日」走勢圖顯示一條昨收平盤線、且該假日列污染日線 / 均線。故偵測到台股休市時（`TwTyphoonClosureService.detectAndPersistToday` 命中、及 `external-materials-service` 開機 self-heal 逐一掃本年度已持久化休市日）須**清除該休市日誤寫的台股市場資料**：`stock_price_history` 當日列（`deleteTwHistoryOn`）+ Redis 台股分時 bucket（`purgeTwTicksOn`）。**嚴格限「台股」**——颱風假僅台股休市，英股 / 美股同日照常交易（其 `price:ticks:英股/美股:...` bucket 與日線不得清）；**亦不動即時價 live cache** `price:{market}:{code}`（休市日 last price = 昨收，本就是正確現價）。清除後 `stock_price_history` 最近交易日回到休市前一日，「當日」分時端點（`InternalPriceController.intraday-ticks` 預設日期，Task 153）自然退回最近有資料交易日（如颱風假 7/10 顯示 7/9），與交易日曆「一體休市」一致。因真休市日外部日線源本就無該日 bar（`backfillTwStock` 亦 skip 今日列），清除具持久性、不被回補重灌
- [ ] **颱風假不寄「今日股市分析」每日 email——寄信前重驗交易日（縱深守門）**：08:45 `MarketAnalysisScheduler` 已以 `isTwTradingDay` 守門（非交易日不送批次）；但分析走 Anthropic Batch API 非同步，批次於 08:45 送出後由 `pollBatches → finalizeIfReady` 收尾時才寄 email。若颱風假於 08:45 送出批次**之後**才被偵測到（DGPA 晚公告 / business 假日快取 10 分 TTL 尚未刷新 / 部署晚於當日盤中），收尾寄信會漏掉 08:45 那道守門。email 為**不可逆的對外動作**，故 `MarketAnalysisService.finalizeIfReady` 在呼叫 `emailDispatcher.dispatchDaily` 之前，須對 `analysisDate` **再驗一次** `marketDataService.isTwTradingDay`：非台股交易日（含 `tw_market_closure` union）則**不寄**（分析仍落 `STATUS_OK` 供頁面查閱，`email_sent_at` 保持 null＝未寄；OK 列不再被 poller 撈、不重試）。手動 `/api/market-analysis/generate`（admin）同走此收尾路徑，故颱風假手動重跑亦不會自動群發 email（僅供查閱）。此為對「唯一 email 送出點」的縱深守門，把「偵測落後於批次送出」的殘留時序窗口收成硬 gate
- [ ] **花錢前先做權威即時颱風假偵測（省 LLM 費用）**：「今日股市分析」08:45 送出 Anthropic Batch API（花費 LLM token）前的交易日守門，不得只讀可能 stale 的 business 假日快取（10 分 TTL，依賴 05:00–07:00 poller 已偵測+傳播）。因 DGPA 停班公告**爬取免費、LLM 分析昂貴**，`MarketAnalysisScheduler`（08:45 cron `scheduledAnalysis` 與開機 self-heal）在守門時須先呼叫 `MarketDataService.refreshTwClosureToday()`：主動觸發 ext `POST /internal/tw-closure/detect` 立即爬 DGPA + upsert `tw_market_closure`，並**直接採其回傳的權威 `closedToday` 短路**——`if (closedToday || !isTwTradingDay(today)) 略過`，颱風假**不送批次、不花錢**。**刻意不動假日快取**：偵測結果以回傳值直接判斷，不 evict `twHolidayCurrentYearCache`，以免「evict 後重抓瞬斷 → `getTwHolidays` 抗毒化 fallback（沿用前一次成功值）失效 → 整年假日含國定假日丟空 → 反把假日誤判交易日、白花錢」。best-effort：ext / DGPA 失敗 → 回 `false`（不主張休市），退回 `isTwTradingDay`（既有快取，含 poller 先前偵測到的休市）判斷，保守維持交易日；手動 `/generate`（admin）不觸發此前置（人為明示、可接受）。此把「花錢點的交易日判斷」從『賭快取夠新』升級為『花錢前主動權威確認』
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
- [ ] 基準日 == 今日（市場時區）時，2 分鐘輪詢回傳的 live price 需即時驅動持股表的「現值 / 損益 / 預估配息」、KPI「資產總計／預估年配息」（股票即時值仍併入資產總計；股票現值/債券現值兩卡為快照凍結分類值、與圓餅圖同源、不隨輪詢跳動）、以及下方市場小計重算（依 `shares × livePrice ×（美股）匯率`），不可只更新股價欄位而值欄位停留在快照儲存值。**不得加上「市場開盤」額外閘門**：該市場收盤後 Redis 仍保留當日最後一筆成交價（前一交易日收盤），KPI 必須使用該值，才能與「歷年資產管理」今日列（透過 `/api/market-data/live-assets`）顯示相同總資產數字
- [ ] 基準日 == 今日（市場時區）時，「持股明細」橫條圖 tooltip 的「股價」欄必須與同一 tooltip 的「現值」**同源** —— 一律覆寫為即時原幣成交價（與持股表「股價」欄同一來源 `getRealtimePrice`），**不得**殘留快照儲存的收盤價（`previousClose`）。否則同一 tooltip 會出現「現值 = 股數 × 即時價」、但「股價」仍顯示舊收盤的矛盾（如台積電現值以 2,510 計算、股價卻顯示 2,410）。實作：前端 `overlayLivePrice` 在套用即時估值時一併覆寫 row 的 `stockPrice`；即時價抓不到（`getRealtimePrice` 回 null）時保留快照收盤價
- [ ] **全市場皆非交易日今日（最新一筆為過去日期：昨日快照／今日尚未建檔／刪除今日快照後最新退回昨日）時，KPI「資產總計」與資產趨勢圖最後一點必須顯示該基準日的收盤值，且與「歷年資產管理」同一筆完全同值**（per-market 基準日閘門，見 Task 121）。**禁止用 `/api/market-data/live-assets` 覆蓋**：`live-assets` 讀 Redis = 最新一筆 tick / 收盤，當最新快照日 < 今日且已有更新交易日收盤時，覆蓋會把較新交易日的收盤洩漏到過去基準日（如最新快照 6/23、今日 6/24 已收盤，6/23 列卻顯示 6/24 收盤）。前端 `liveLatest` 對三市場各自 `basedate == 該市場當地今日` 判斷，三市場皆非今日 → 一律保留快照凍結收盤值（per-market `sumOf`，不再走已移除的 `overlayLatestFromLiveAssets`）；BFF `dashboard/summary` 的 `history` 與 `asset-history` 共用 `LiveAssetsOverlay.applyToLatest` 套同一 per-market 閘門。**僅當最新一筆 snapshotDate == 今日時**才用 live 覆蓋（建檔當下可能是盤中暫定價，需以 live 收盤 refresh，見 Task 108/109）
- [ ] Dashboard 的 `/api/bff/dashboard/realtime` 每次輪詢需先 trigger 後端 `/api/market-data/prices/refresh` 主動向 Yahoo 拉最新行情，再回傳；不可僅讀取 cache（與 SnapshotForm `/realtime` 一致，避免後端 cron 漏跑時前端看到舊值）
- [ ] 顯示「信託基金」橫向長條圖（與「持股明細」並列），y 軸基金名稱、x 軸現值，依現值升冪排序，bar 顏色依損益正負（賺綠、賠紅），下方顯示總值 / 成本 / 損益（含 %）小計；資料來源 `latestSnapshotDetail.funds`，無 BFF 額外彙總（基金值已存於快照，不隨輪詢跳動）；無基金資料時卡片仍顯示，內容為「尚無基金資料」
- [ ] KPI 卡片列共 5 張（資產總計 / 存款總計 / **股票現值** / **債券現值** / 預估年配息），改用 flex 平均分配寬度。**第 3、4 張改為與「現金/債券/股票」圓餅圖 tab 同邏輯的資產類別歸類值（Requirement 25）**：股票現值 = `stockValue`（股票型：一般股票/ETF ＋ 股票型基金，**不含債券 ETF**），債券現值 = `bondValue`（債券型：債券 ETF ＋ 債券型基金）；兩者與圓餅圖 **同一 business-service 來源**（`GET /api/snapshots/history`，前端依 `id` 對應選中快照的 history row），採快照凍結逐筆 `currentValue`、不套盤中 live（同 Requirement 25「巨觀資產配置毋須 intraday 精度」，故兩卡佔比 == 圓餅圖股票／債券佔比）。`sub` 皆顯示「佔比 X.X%」（分類值無逐筆成本口徑，不顯示損益）。**回復嚴格加總**：現金（存款）+ 股票現值 + 債券現值 == `totalAssets`（債券 ETF 只計入債券現值、不再重複計入股票現值）
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
- [ ] 走勢圖期間按鈕第一個項目為「當日」，顯示當日盤中分時走勢。**「當日」= 今天（該市場時區）若為交易日且今日 tick LIST 已有資料，即用今天**（盤中即時累積 / 盤後完整覆寫皆寫在「今天」bucket）；**今天非交易日（週末 / 假日）或盤前尚無資料時，才退回「最近一個有收盤的交易日」**。⚠️ 不可直接用 `stock_price_history` 的 `findMaxTradingDate` 當預設日期：今日收盤價要收盤後才進 `stock_price_history`，盤中它會停在前一交易日，導致預設日期落在空的 tick bucket、前端誤顯示「無當日分時資料」（Task 153 修正）。月線 MA20 / 季線 MA60 / 年線 MA240 與 K / D 仍要顯示，但 intraday tick 數無法支撐日線級指標重新計算 → 改以「該股最近一個交易日收盤後算出的日線最新值」畫成水平參考線（與其他期間同口徑，避免使用者在切回日線期間時看到不同 MA 值）
- [ ] **「當日」X 軸固定延伸到「收盤時間」而非「現在時間」**：以該市場交易時段（市場當地時區、DST 不變：**美股 09:30–16:00、英股 08:00–16:30、台股 09:00–13:30**，鏡射後端 `MarketZones` 單一事實來源）建整段「開盤→收盤」**每分鐘 category 網格**當 X 軸；真實分時 tick 依其 `HH:mm` 落格（同一分鐘後到者覆蓋＝取該分鐘最後成交價、落在開盤前 / 剛收盤寬限窗等邊界外的 tick 夾到端點以免遺漏最新一筆），**盤中尚未到達的時段留 `null`**（畫空白、股價線只到最新一筆；股價 series 設 `connectNulls`，讓 2 分鐘輪詢 / 5 分 K 等稀疏 tick 之間連成連續線）。月線 MA20 / 季線 MA60 / 年線 MA240 / 成本均價 / K / D 的水平參考線亦以整段網格常數填滿、**同步畫到收盤**。此與指數當日圖（Requirement 18「X 軸固定延伸到收盤時間而非現在時間」）**同一設計、同一視覺行為**——差異僅在資料源（股票分時為 Redis 真實成交 tick、指數為 Yahoo 5 分 K，故各自實作、無法共用後端 padding；本圖於前端 view 依原始 tick + 交易時段建網格 render，不改後端 tick API 契約）。legend「股價」值與成本線漲跌配色須取「**最後一筆非 `null` 分時價**」（網格末格恆為未來 `null`，不可直接取末格，否則盤中 legend 股價與成本漲跌色空白 / 反轉）（Task 157）
- [ ] **「當日」模式 Y 軸鎖定當日股價區間**（分時股價 min/max ±10% padding），**不用 `scale:true`**：否則多頭時遠低於現價的年線 MA240（甚至季線 MA60、成本均價）會把 Y 軸下限撐開，當日日內波動被壓成一條貼頂平線、看似「資料錯誤」。落在鎖定區間外的均線 / 成本水平線由 series clip 自動裁切、數值仍保留在 legend。日線 / 月線等其他期間維持 `scale:true`。此為股票分析走勢圖與指數圖（Requirement 18 / Task 96.7）同一設計，適用台／美／英三市場（Task 155）（紅最高、綠最低＝台股紅漲綠跌）：點上以小圓點標記，旁附**色塊標籤（紅/綠底白字）兩行**——第一行「最高／最低 + 股價（2 位小數、千分位）」、**第二行日期；「當日」分時模式第二行改為時間（HH:mm）**（標籤第二行直接取該點 x 軸類別字串 `labels[i]`，日線即日期、當日即時間，不必分支）。色塊位置依該點在可視窗的水平位置自適應避邊（靠右→放左、靠左→放右、其餘最高在下/最低在上，避免撞到頂部 legend 與底部縮放軸、或溢出左右邊界）。因日線資料為完整 10 年一次載入、區間鈕僅調 dataZoom 縮放窗，**不可用 ECharts 原生 `markPoint type:'max'/'min'`**——它會掃整段 10 年的極值，縮到短區間時最低點會落在畫面外、看似壞掉。改依目前可視窗（區間鈕預設 `defaultZoomRange` 或使用者手動拖曳後的 `zoomPct`，合成 `effectiveZoom`）換算可視索引範圍後自行找極值，並透過 `@datazoom` 事件讀回圖表當前 start/end% 即時重算。markPoint `coord` 以該點**類別字串**定位（非絕對索引），避免 dataZoom 過濾視窗外資料後絕對索引對不準。切換股票 / 區間時清掉手動縮放（回該區間預設窗）；「當日」分時模式同口徑標出當日最高 / 最低（與指數圖 Requirement 18 同設計）
- [ ] **「當日」模式在走勢圖上方顯示「昨收」與「今日漲跌」**：昨收＝該分時交易日的**前一交易日收盤價**，取自對話框已載入的日線 `history`（`/api/bff/stock-analysis/history/stock` → `stock_price_history` 收盤，與 Dashboard／管理資產「當日漲跌」同一 business API、同一「vs 前一交易日**原始**收盤」口徑）——**不採 Redis `LivePrice.previousClose`**（TWSE `y` 於除息日為除息參考價，會與全站「vs 前一交易日原始收盤」慣例不一致）。現價取 legend「股價」的**同一值**（`lastNonNull(prices)`＝最後一筆非 `null` 分時價），今日漲跌 =（現價 − 昨收）、漲跌% =（現價 − 昨收）÷ 昨收 × 100，**由前端以畫面顯示的現價自算**，確保「股價 − 昨收 = 今日漲跌」三值一致（不另抓 live 免與現價對不上）。當日交易日以分時序列所屬日期（首筆 tick `time` 前 10 碼）判定，故非交易日退回最近交易日時，昨收＝**該交易日**的前一交易日收盤（非今日的前一日）。配色台股慣例：漲紅（`#dc2626`）、跌綠（`#16a34a`）、平灰（`#94a3b8`），與 markPoint／Dashboard 同義同色。無前一交易日收盤（如新標的僅今日一格）時昨收顯示「—」、今日漲跌留空（優雅降級）。僅「當日」期間顯示，切回日線期間隱藏；純前端 view 變更、不改 tick API 契約（Task 158）
- [ ] 走勢圖「成本均價」水平參考線即「買入均價」的同義值，從 Dashboard 持股列雙擊開啟時，必須與該列表格「買入均價(USD)」顯示**同一數字**：一律取 BFF 已算好的 `avgCostOriginal`（以交易當下匯率 `transactionExchangeRate` 鎖定的原幣買入成本 ÷ 股數），**不得**用前端「台幣投資成本 ÷ 今日即時匯率」反推 —— 今日匯率每日浮動，會讓走勢圖成本均價與表格買入均價對不上，且不是真實買入成本
- [ ] 「當日」分時資料採兩階段：(1) **盤中**由 `external-materials-service` 既有 `PricePoller` 每 2 分鐘輪詢時，將真實成交 tick（時間 + 成交價）`RPUSH` 到 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`，TTL 36h。**寫入守門條件**：(a) `source` 不含括號（排除 `(history)` / `(前收)` / `(買賣中價)` 等非實際成交值，與 `HistoricalDataService.getStockHistory` 對今日格條件一致）；(b) `markClosed=false`（排除 cache warming 對盤外時段抓到的「最新可得值」回灌污染分時序列）。前端切到「當日」即時讀此 LIST 看到當下累積結果
- [ ] tick 寫入的 `{tradingDate}` bucket 與 tick 時間戳的時區**必須同市場、同一交易日**：`PriceCacheWriter.resolveTradingDate` 對台／美／英三市場一律以「**該市場自己時區**」判定 live session（開盤中 or 剛收盤窗口）並取 `LocalDate.now(zoneOf(market))`。⚠️ **英股不可誤用台股時段判斷**：倫敦盤中（台北 15:00–23:30）台股已收盤，若沿用台股窗口會判為非 live → `tradingDate` 退回昨天，今日倫敦 tick 被貼進昨日 bucket、與盤後覆寫的昨日資料混桶，前端「當日」讀到跨兩天序列（時間軸倒退、假最高／最低）（Task 154 修正）
- [ ] **盤後**由 `IntradayTickRefresher` cron（**台股 14:00 TW、美股 16:05 ET、英股 17:00 LON**）抓外部完整當日資料覆寫該日 LIST，補回盤中 polling 因 `z='-'` 跳號漏掉的時間點。⚠️ **台股 / 英股排在收盤後 ~30 分（非 5 分）**：Yahoo 對 TWSE(~25min) / LSE(~15min) 的 5m feed 有延遲，收盤後 5 分抓會截斷（台股停在 13:10 而非 13:30、英股停在 ~16:20），後延讓 feed 追上收盤；美股 Yahoo 5m 無延遲，16:05 即完整（Task 156）
- [ ] **`replaceTicks` 採「防截斷（never-shrink）」語義**：新抓資料為空、或其末刻（分鐘級）**早於**既有 LIST 末刻時，**保留既有、不覆寫**（原為無條件 `DEL` + `RPUSH` 全覆寫）。避免外部源尚未追上收盤時，把盤中 polling 已累積到收盤的 tick 蓋成截斷版且永不還原。空資料檢查須在 `DEL` 之前（原本先 `DEL` 再 return 會把既有清空）。與 cron 後延雙保險，涵蓋 Yahoo 延遲變動（Task 156）
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
- [ ] 美股 `openPrice` 來源：NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`；NASDAQ `/historical`「`fromdate == todate`」會回 400（`Provided date is less than from date`）、給日期區間盤中又不含今日列，兩者皆**無法**取得今日 open（原 `getNasdaqOpenPrice` 因此恆回 null → Redis live 無 `openPrice` → 連帶盤後 dump 進 `stock_price_history` 亦無 open → 觀察清單「開盤」欄恆顯示「—」）。改由 `PriceFetchClient.fetchUsTodayOpenFromYahoo` 打 Yahoo chart `interval=1d&range=1d`，取當日 daily bar `indicators.quote[0].open[0]`（盤中＝今日部分 bar 的 open、盤後＝完整 bar 的 open；只取 open 不碰 close，不違反 Task 84）；為盤中輪詢補強欄位，遇 Yahoo 429 fail-fast 不重試以免拖慢 cron。取不到時 `openPrice = null`，下游 `WatchStockService` 維持 `stock_price_history` fallback 行為。台股維持 TWSE mis API 的 `o` 欄位。
- [ ] 英股 `openPrice` 來源：**同取 Yahoo chart 的 `indicators.quote[0].open[0]`**（Task 194 修正）。原 `getYahooLsePrice` 取 `meta.regularMarketOpen`，但該欄**實測不存在於 Yahoo chart meta**（2026-07-15 以 `interval=1d&range=1d` 實測 CSPX.L / VUSA.L / VWRL.L 三檔皆 missing），故英股 `PriceResult.openPrice` 恆為 null → `PriceCacheWriter` 採 `NON_NULL` 序列化 → Redis `price:英股:{code}` 的 `openPrice` **欄位恆缺**（非寫入 null，而是整個欄位不存在）。**使用者可見症狀為「靜默顯示錯誤的日期」而非空值**：`WatchStockService` 見 `openPrice == null` 會 fallback 至 `stock_price_history.findRecentN` 最近一筆，而倫敦盤中今日列尚未入庫 → 觀察清單「開盤」欄拿到**前一交易日的開盤價**，看起來有值、實則非今日（比顯示「—」更難察覺）。16:32 LON `dumpUkCloseFromRedis` 直接抄 Redis 的缺漏值 → DB 今日列 open 亦為 null；要到 17:00 LON `verifyUkCloseWithYahoo` 走 `fetchUkHistoricalRange`（其 open 取自 `indicators.quote[0].open[i]`，本來就正確）覆寫今日列後才修正。**故 bug 影響窗＝倫敦整個交易時段至 17:00 LON**，收盤校正後自然痊癒，這也是本 bug 長期未被發現的原因。症狀與美股 Task 120 修正前同型，**修法亦同**（Task 193 `fetchKrIntradayQuote` 已用正確欄位落點）。同時 `meta.previousClose` 亦不存在，原「`chartPreviousClose` 取不到才 fallback `previousClose`」的 fallback 為**死碼**，一併移除以免誤導後續維護者以為該欄可用。`meta.regularMarketDayHigh` / `regularMarketDayLow` / `regularMarketVolume` 實測**存在**且與 `indicators.quote[0]` 對應值一致，high / low / volume 續用 meta 不動
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
- [ ] **email 每檔股票再多嵌一張「當日分時走勢圖」PNG**（Task 159）：年圖下方並列第二張圖，與畫面 `StockAnalysisDialog`「當日」走勢同資料源、同口徑，由 `AlertChartRenderer.renderIntradayPng(code, market)` server-side 渲染（同樣純 Java / XChart，無外部服務依賴）；以第二個 CID inline 內嵌（`cid:intradayN`），故單檔觸發之 digest 應內嵌 **2 張圖**（年圖 + 當日圖）。無當日 tick / 繪圖失敗時**僅略過此圖**，年圖與文字照寄。內容：
  - **分時價格線**：分時 tick 走 `HistoricalDataService.fetchIntradayTicks(code, market, null)`（date 省略 → ext-materials 取最近有資料的交易日；讀 Redis tick LIST，與畫面「當日」同一支 business API、非直連外部行情）。稀疏 tick（2 分輪詢 / 5 分 K）以「每分鐘最後成交」落點連成連續線
  - **昨收基準線**：灰虛線水平參考線 + legend「昨收 X.XX」；昨收＝該分時交易日「**前一交易日**」的日線收盤（`stock_price_history`，`tradingDate` 嚴格早於分時交易日的最後一筆），與畫面「當日漲跌」同「vs 前一交易日**原始**收盤」口徑，**不採 Redis `previousClose`**。查無（如新標的僅今日一格）則不畫基準線、線用中性藍
  - **漲跌色**：最新分時價 ≥ 昨收 → 紅（漲）、否則綠（跌），對齊 `quoteColor` / markPoint「紅漲綠跌」（漲 `#dc2626`、跌 `#16a34a`）；legend 股價值附「▲/▼ 金額（±%）」今日漲跌
  - **X 軸開盤延伸到收盤**：以該市場交易時段（`MarketZones.openTime/closeTime` 單一事實來源，鏡射畫面 `sessionHours`：台 09:00–13:30 / 美 09:30–16:00 / 英 08:00–16:30）之當日分鐘數為數值 X、min/max 鎖定開收盤，故軸固定延伸到收盤時間而非最後一筆 tick（與畫面 Requirement 13「X 軸延伸到收盤」同視覺行為）。Y 軸鎖定分時區間 + 昨收（各 +10% padding），避免基準線落框外
  - 另提供 `GET /api/watch-stocks/intraday.png?code=&market=` 預覽端點（比照既有 `chart.png`），供前端預覽 / 驗證；無 tick / 繪圖失敗回 204
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
- [ ] **即時股價走 Yahoo Finance `.L` suffix**：`external-materials-service` 的 `PriceFetchClient.getStockPrice(code, "英股")` 打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}.L`，透過 `curlGetWithRetry` 子程序避開 Yahoo Java HTTP fingerprint 偵測，從 `meta.regularMarketPrice` 取 live 價、`meta.chartPreviousClose` 算漲跌；source 標 `Yahoo`。**`openPrice` 取 `indicators.quote[0].open[0]`，不可用 `meta.regularMarketOpen`（實測不存在於 Yahoo chart meta）**；`meta.previousClose` 同樣不存在，昨收只有 `chartPreviousClose` 有值。`meta` 的 `regularMarketDayHigh` / `regularMarketDayLow` / `regularMarketVolume` 實測存在，high / low / volume 續用 meta（Task 194 修正，見 Requirement 7 `openPrice` 來源條目）
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
- [ ] **business-services 未識別身分回 401（錯誤語意契約）**：business-services 內需要使用者身分才能執行的 service，在 `CurrentUserContext` 無使用者（請求未帶 `X-User-Id`／`TenantGuard.requireCurrentUserId()` 回 `null`）時，一律拋出專用的 `UnauthenticatedException`，由 `GlobalExceptionHandler` 對映為 **401 Unauthorized**，不得落入 `Exception.class` 的 500 兜底。涵蓋五支排程設定 service（`ExportScheduleService`／`RealizedGainExportScheduleService`／`TradingCalendarExportScheduleService`／`CommodityExportScheduleService`／`ExchangeRateExportScheduleService`，Requirement 34／39／37／41／42）與 `PortfolioAdviceService` 的儲存理財條件、產生資產配置建議兩處（Requirement 33）。與既有對映一致成套：`TenantAccessException`→404（不洩漏他人資源存在）、`AdminRequiredException`→403（已識別但權限不足）、`UnauthenticatedException`→401（未識別身分）。此為錯誤語意修正而非資安邊界變更——business-services 未對主機開埠，公開邊界（nginx:80／bff:8080）對未登入與偽造 header 本就正確回 401，錯誤回應亦不含 stacktrace。
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

### Requirement 31: 今日股市分析（每交易日於可設定時點由 AI 判斷當天台股走向）

**User Story:** 作為投資人，我希望每個台股交易日開盤前，系統能根據過去一年的台股與美股走勢、以及近期國內外財經新聞，自動判斷「今天台股可能的走向」並給我一段有理由的分析，讓我在開盤前就掌握多空氛圍與關鍵變數；越近期的走勢與新聞應占越高的權重。

**Acceptance Criteria:**

- [x] **排程時點與交易日閘門**：每個台股交易日（週一～五且非台股國定假日）08:45（`Asia/Taipei`）自動執行一次「今日股市分析」；非交易日（週末／台股休市日）跳過不執行、不呼叫 LLM。交易日判定重用 business-services 既有 `MarketDataService.isTwTradingDay(LocalDate)`（週末 + TWSE Open API 假日表）。cron 以 `MON-FRI` 觸發後再判假日。**Task 191 起**：08:45（Task 186 之時點）成為預設 seed，排程改為**每分鐘 tick**（`0 * * * * MON-FRI`）比對可於頁面設定的**多個寄送時間**，命中才做交易日閘門並觸發分析（交易日閘門與此處相同，見下方「可設定多個分析寄送時間」）。
- [x] **開機自我修復（self-heal）**：服務於排程時點未運行（重啟／crash／部署）時，啟動後若「今天為台股交易日且現在時間已過**最早的啟用寄送時間**（Task 191 前為固定 08:45）且 `daily_market_analysis` 尚無今日該筆」，補跑一次，避免當日分析漏產。比照 `IndexDailyRefreshScheduler` 的 self-heal 慣例。
- [x] **分析輸入（走勢一律讀本地 DB、不即時抓外部行情）**：以「台股大盤（TAIEX）近一年日線收盤」（`twse_index_daily_history`）＋「美股主要指數近一年日線收盤」（`us_index_daily_history` 之 `DJI`／`SPX`／`IXIC`／`SOX`）為量化輸入，餵給模型時明確標註「越近期越重要」（近 20 日另附細節、資料由舊到新排列並在提示詞要求對近期加權）。此為既有指數日線的唯一來源，符合「同義欄位、同一 business service API／同一事實來源」原則，不另建行情抓取。
- [x] **財經新聞改由本地爬蟲 `news_headline` 供給、移除 `web_search`（Task 179）**：近期國內外財經新聞**不再**由模型的 `web_search` server tool 即時搜尋，改為**一律讀取 `external-materials-service` 爬蟲寫入的本地 `news_headline`**（每交易日 08:20／11:30／18:00 抓取、已過濾為投資組合主檔個股＋總體新聞，見 Task 149.21／177／178／184）。`MarketAnalysisService.submitBatch` 於送批次前撈近 `news-max-age-days` 天的本地新聞注入 prompt，模型只從此清單挑選 `newsHighlights`（`title`／`source`／`url`／`publishedAt` 照清單原樣填），**不再上網、不掛任何 `web_search` tool**；撈不到本地新聞時退回純技術面（`newsHighlights` 回空陣列、prompt 切換為「不得杜撰新聞」，僅依走勢量化數據研判）。下方 Task 149.13／149.20～149.22 的 `web_search` 相關為歷史沿革；其頁面「新聞搜尋次數」下拉與 `web_search_max_uses` 欄位已由本項移除（見「模型與思考深度可於頁面調整」段落與 Liquibase `v1.54.0-market-analysis-drop-web-search`）。原提示詞（優先採近 1～2 週、越近期權重越高的台股／美股／Fed／利率／匯率／地緣／法人動向新聞）沿用於本地新聞清單的挑選指引。
- [ ] **公開資訊必含台幣兌美元匯率與美股主要指數，並擴增政策/國際新聞來源（Task 180）**：每輪爬蟲（08:20／11:30／18:00 ＋ warmup）除既有本地財經新聞＋TWSE 公開資訊外，另由 DB 既有資料組兩則「量化快照」併入 `news_headline`（同進 SRPP 公開資訊 JSON 與今日股市分析本地新聞區塊）：(1) **台幣兌美元匯率**（`exchange_rate_history` 最新 USD 即期買/賣/中間價，`category=fx`）；(2) **美股主要指數收盤**（`us_index_daily_history` 道瓊/標普500/那斯達克/費半最新收盤＋對前一交易日漲跌%，`category=us-market`）。兩則資料真實日期明列於標題、`publishedAt` 取抓取當下以保證恆落在「當日公開資訊」範圍；`fx`／`us-market` 與 `twse-*` 同屬總體/量化資訊，於 `PublicInfoStockFilter` 一律保留、不做個股過濾。新聞來源另擴增**自由時報 政治 RSS**（央行 / 金管會政策、兩岸・國安等政策面）與**自由時報 國際 RSS**（地緣政治、川普言論、Fed 政策），涵蓋使用者要求之政策與地緣重點；因此二為「整個版面」的一般新聞，**只保留標題含財經・政策・地緣中性主題詞者**（`RELEVANCE_KEYWORDS`），濾掉地方 / 社會 / 娛樂 / 體育瑣聞，避免高頻一般新聞把財經頭條擠出今日股市分析的近 N 天新聞上限（40 則）。
- [x] **早上那輪爬蟲由 08:00 改 08:20（Task 184）**：`NewsPoller` 排程早上那次由 08:00 調整為 **08:20**（中午 12:00、晚上 18:00 不變），仍早於 08:45 今日股市分析。因早/午晚的「分」不同（:20 vs :00），`scheduled()` 拆為兩個 cron（`0 20 8 * * *` ＋ `0 0 12,18 * * *`，`@Scheduled` 可重複標註）。排程沿革：06:00（Task 149.21）→08:00（Task 177）→08:20（本項）。**（中午 12:00 與拆 cron 結構已於 Task 188 再調整為 11:30、三條 cron `0 20 8`＋`0 30 11`＋`0 0 18`。）**
- [x] **公開資訊必含韓國股市（KOSPI 大盤＋三星電子＋SK 海力士，Task 185）**：每輪爬蟲於既有匯率、美股快照外，另由 DB 既有資料組**第三則量化快照**併入 `news_headline`（`category=kr-market`／`source=kr-index`／`region=KR`；同進 SRPP 公開資訊 JSON 與今日股市分析本地新聞區塊）：**KOSPI 大盤**（讀既有 `us_index_daily_history` 之 `index_code=KOSPI`，由既有海外指數管線抓取、**不重抓**）＋**三星電子（005930）／SK 海力士（000660）**收盤（KRW）＋對前一交易日漲跌%。三星／海力士屬海外個股，新增 `foreign_stock_daily_history` 表由 `KrStockPoller`（每日 16:00 Asia/Taipei ＋開機 warmup，Yahoo Finance `005930.KS`／`000660.KS`，時區 Asia/Seoul）抓取；快照標題明列各項資料日、`publishedAt` 取抓取當下以恆落在當日範圍；`kr-market` 與 `fx`／`us-market` 同屬非 `news` 量化資訊，於 `PublicInfoStockFilter` 一律保留。**採「只餵資料給分析」**：韓股快照自動進今日股市分析 prompt 的量化桶，**不**新增 `krContext` 輸出欄位／prompt 敘事／前端專屬區塊。資料源為 Yahoo Finance（美國站，非中港澳）。
- [x] **公開資訊必含韓國股市「盤中」快照（當日開盤動向，Task 193）**：Task 185 之 `kr-market` 快照純讀 DB，而其資料由 `IndexDailyRefreshScheduler`（07:00 `Asia/Taipei`＝08:00 KST，韓股尚未開盤）與 `KrStockPoller`（16:00 `Asia/Taipei`＝17:00 KST，韓股已收盤）寫入，故 08:20 那輪爬蟲組出的韓股快照**恆為前一交易日收盤**。但韓股 09:00–15:30 KST **＝台北 08:00–14:30**，08:20 爬蟲執行時韓股已開盤 20 分鐘，三星／SK 海力士（記憶體權值，對台股開盤具領先參考）的**當日開盤動向完全未進分析**。故新增**第四則量化快照**（`category=kr-intraday`／`source=kr-intraday`／`region=KR`；同進 SRPP 公開資訊 JSON 與今日股市分析本地新聞區塊）：由 `KrIntradayFetchClient` 即時抓 Yahoo Finance（`^KS11`／`005930.KS`／`000660.KS`）之**開盤價＋現價＋對前一交易日收盤漲跌%**併入 `news_headline`。**不新增任何 `@Scheduled`**——掛 `NewsPoller` 既有輪次即可，排程列表頁因而無須更動。**產出與否只由韓股盤中時段閘門決定，與 `NewsPoller` 的執行時點無耦合**：現行 08:20／11:30 兩輪落在韓股盤中故產出，18:00 那輪在韓股收盤後故不產出（當日收盤已由既有 `kr-market` 快照涵蓋，不重複），日後調整爬蟲執行時點亦無須改動本功能。**雙閘門（免自建韓國假日曆）**：(1) **時段閘門**＝台北 MON–FRI 08:00–14:30（本地時鐘，`Clock` 可注入供測試）；(2) **資料閘門**＝Yahoo `meta.regularMarketTime` 換算 `Asia/Seoul` 之**日期**須等於今日 KST，否則判定韓國休市而不產出（設날／추석 為農曆，現有 `MarketCalendar` 之 `nthWeekday`／`goodFriday` 純函式算不出韓國假日，改由資料自身判定；韓國休市時 Yahoo 回前一交易日 bar，日期天然對不上）。**開盤價須取 `indicators.quote[0].open[0]`，不可用 `meta.regularMarketOpen`**（後者實測不存在於 Yahoo chart meta，誤用會使開盤價恆為 `null`、功能靜默半殘）。標題明載快照時點（KST）與「盤中」字樣、`summary` 標明為即時值而非收盤並點出同批 `kr-market` 係前一交易日收盤，供 LLM 消歧（兩則會同時出現在同一輪 prompt）。沿用 Task 185 之**「只餵資料給分析」**：自動進今日股市分析 prompt 的量化桶，**不**新增輸出欄位／prompt 敘事／前端專屬區塊。`kr-intraday` 屬非 `news` category，於 `PublicInfoStockFilter` 一律保留。資料源為 Yahoo Finance（美國站，非中港澳）。
- [x] **公開資訊必含台股均線突破偵測（大盤／0050／00881 對季線・年線，Task 207）**：每輪爬蟲於既有匯率、美股、韓股快照外，另由 DB 既有日收盤自算**季線 MA60／年線 MA240**（SMA，與今日股市分析／股票分析頁之 `TechnicalIndicatorService` **同定義同來源**），偵測**台股大盤（0000）／0050／00881 於前一交易日**收盤對均線的**漲破／跌破**，**命中才**組列併入 `news_headline`（`category=ma-cross`／`source=ma-cross`／`region=TW`；同進 SRPP 公開資訊 JSON 與今日股市分析本地新聞區塊），三檔皆無突破則本輪不寫入（不塞雜訊）。穿越判定為「逐日對齊自身均線」（漲破＝`cPrev2 ≤ maT1` 且 `cPrev > maT`），避免均線移動造成假穿越。**不新增任何 `@Scheduled`**——掛 `NewsPoller` 既有輪次，排程列表頁筆數不變。**分割／反分割防呆須為兩層**：來源 `stock_price_history` 存未還原權值原始收盤，只擋「分割當日」不足——受污染的均線會單調下滑並自己穿過靜止股價，在分割後約第 60／240 個交易日噴出「股價沒動卻漲破年線」的假訊號並餵進 LLM 分析；故除單日變動 >15% 跳過該標的外，**均線取樣視窗內任一相鄰兩日變動 >15% 亦須略過該均線**，直到舊權值滾出視窗。**`publishedAt` 取事件「資料日」而非抓取當下**（使突破依真實發生日隨新聞時效窗老化，不被當成每天的新新聞重複餵入）；因大盤指數表落後屬常態，匯出端 `loadTodayPublicInfoForExport` 對本 category **開 cutoff 例外**（只受 `fetched_at` 當日管），避免該列被 SRPP JSON 靜默濾掉。**`url` 須帶 `#代號-ma期數-資料日` fragment** 使去重鍵具事件識別度——否則 `dedupe_key` 恆定、`ma-cross` 永遠只有一列，後來事件會整列覆寫先前的，使分析視窗內既有突破被靜默吞掉且無法重建。`ma-cross` 屬非 `news` category，於 `PublicInfoStockFilter` 一律保留、下游歸「量化資訊」全列。沿用「只餵資料給分析」：不新增輸出欄位／prompt 敘事／前端專屬區塊。
- [ ] **爬蟲新聞來源不得取用中港澳網站（Task 180 明確化）**：本地爬蟲來源固定為台灣權威媒體（玩股網 / MoneyDJ / 自由時報財經・政治・國際 / 經濟日報）＋美國權威財經媒體（CNBC / Nasdaq，Task 198）＋證交所公開資訊＋台銀匯率＋ Yahoo Finance（美國站）美股指數，**不含任何中國大陸 / 香港 / 澳門網站**（鉅亨網 cnyes 亦因言論偏頗於 Task 149.22 移除）。模型輸出端另有 `sanitizeNews` 中港澳地區封鎖（`BLOCKED_HOST_SUFFIXES` / `BLOCKED_DOMAINS` / `BLOCKED_SOURCE_TOKENS`）作防禦縱深。
- [x] **爬蟲新聞來源增列美國財經網站（CNBC / Nasdaq，Task 198）**：原本地爬蟲**新聞文字報導**來源皆為台灣媒體（玩股網／MoneyDJ／自由時報財經・政治・國際／經濟日報），美國資訊僅有 `MarketSnapshotFetchClient` 由 DB 組出的「美股指數收盤」量化快照（`category=us-market`），**無美國財經新聞報導**。因今日股市分析須綜合美股走向與國內外財經新聞（美股走勢對台股具領先性、Fed／關稅／通膨等美國政策為關鍵變數），`NewsFetchClient` 於既有台灣來源外，另增美國權威財經媒體 RSS（`category=news`、`region=US`）：**CNBC**（財經專屬三分類 Economy `id/20910258`／Finance `id/10000664`／Markets(Investing) `id/15839069`，`source=cnbc`）與 **Nasdaq**（Markets `feed/rssoutbound?category=Markets`，`source=nasdaq`）。皆為標準 RSS `<item>`，帶真實 `pubDate`（RFC-1123；CNBC 為「少秒＋GMT」、Nasdaq 為數字時區，皆可由既有 `parsePubDate` 之 `RFC_1123_DATE_TIME` 解析），沿用既有 `fetchRss`（新增 `region` 參數；台灣既有來源續傳 `TW`）、UA `Mozilla/5.0`、逐來源獨立 try/catch graceful（單一來源失敗只 log warn、不影響其他）。**皆為財經專屬分類 feed，不套台灣中文新聞的編輯政策過濾（`EditorialNewsFilter`，Task 199；原 `relevantOnly`）**（該過濾之關鍵詞為中文、僅用於自由時報／經濟日報等台灣混合型一般新聞 feed）。`PublicInfoStockFilter` 對美國新聞**天然全留**（其個股判定只認台股 `-TW` 代號格式與 wantgoo `newsTags`，美股代號不命中 → `mentioned` 空 → 保留）。美國新聞 `category=news` 故計入今日股市分析的一般新聞上限（`LOCAL_NEWS_MAX=40`、依 `published_at` 越新越前排序），與台灣新聞共用額度（刻意如此——美股新聞本就是分析的重要輸入）；同步進 SRPP 公開資訊 JSON 與「爬蟲資訊查詢」頁（`地區` 欄顯示 `US`、`類別` 顯示「新聞」，前端無須改）。**仍嚴禁中港澳來源**（Task 180／149.18 之地區封鎖不變；CNBC／Nasdaq 均為美國網域，模型輸出端 `sanitizeNews` 亦不誤殺 `cnbc.com`）。MarketWatch（feed 已 301 轉址、目的地未穩定）與 Federal Reserve press RSS（Atom `<entry>`／`<updated>` 格式，與既有 RSS `<item>`／`<pubDate>` 解析器不相容）本期不納入、列後續。
- [x] **台灣中文新聞編輯收錄政策（`EditorialNewsFilter`，Task 199）**：使用者要求對台灣中文一般新聞收斂收錄範圍，濾掉與財經分析無關的雜訊。**只套用於台灣「混合型」一般新聞 feed（自由時報 財經／政治／國際、經濟日報）**；純財經來源（玩股網 wantgoo／MoneyDJ，結構化／股票專屬）與美國財經 feed（CNBC／Nasdaq，Task 198）**豁免不過濾**。**取代舊 `relevantOnly`／`RELEVANCE_KEYWORDS`**（原僅政治／國際 feed、只做財經・政策・地緣白名單），涵蓋更完整。收錄政策（使用者 2026-07-16 決定）：
    1. **財經一律保留**：任何財經／股市／上市櫃公司／產業／半導體／科技／總經／金融／房市／能源／匯率／併購／IPO／財報訊號一律收錄，**即使提到非白名單縣市**（如「台積電台中廠」「李長榮中科廠擴線」）；個人理財包裝的生活情感故事（如「6旬夫妻存款顧孫被掏空」）不算財經。
    2. **與中國有關的新聞**：僅保留「財經相關」或「與北京政權／中國中央政治（官員肅清貪腐、軍事軍演解放軍、外交、對台／兩岸／台海、國安、人權／言論審查、跨境鎮壓、制裁、統戰、一帶一路、五年規劃…）」或「美／日／台／歐盟對中雙邊政治」；其餘中國新聞（純社會、意外車禍、天災、醫療獵奇、抄襲學術、娛樂體育、市井趣聞）不收。**此為主題過濾，與既有「依來源網域封鎖中港澳媒體」（`sanitizeNews`，擋 sina.com.cn 等中國來源網站）為兩件不同的事、並存**。
    3. **政治新聞**：收錄與美國／日本／台灣／歐盟相關之政治；其他地區政治僅保留「明顯影響金融市場的地緣政治」（俄烏戰爭、中東／伊朗／以色列衝突與油價、OPEC、韓國半導體、核談判、制裁＝地區詞＋衝突／能源／半導體／談判等觸發詞）；其餘地區純政治／內政人事不收。
    4. **台灣地方新聞**：僅保留台北市／新北市／高雄市（含「北市」）；其他縣市地方新聞（含地方選舉／議員／縣市長／拜票／地方災情停電／地方活動開幕）不收。但**全國性政治**（總統／立法院／行政院／內閣部長／國防外交）即使在某縣市發生仍保留。
    5. **全世界生活／軟文／體育一律不收**（生活／娛樂／體育／消費開箱／汽車售價／美食旅遊／演藝影劇／社會人情趣味／食安個案），**唯一豁免＝真的影響股市大盤漲跌（即命中財經訊號）**；政治／中國／地緣／城市白名單皆<b>不能</b>救回生活軟文體育（使用者 2026-07-16 追加收緊）。**白名單模型**。
    - **實作**：`EditorialNewsFilter.retain(rows)` 為純關鍵詞優先序 cascade（第一個命中者決定；標題為主，有摘要併入），與 `PublicInfoStockFilter` 同為爬取後的過濾層，但置於 `NewsFetchClient` 各混合型 feed 抓取後（逐 feed 套用）。cascade 順序：①財經（最先、涵蓋最廣，確保財經不誤刪＝生活軟文體育的唯一豁免）→ ②**全世界生活／軟文／體育否決**（除非①已判財經，否則一律濾；置於中國/地緣/政治/城市之前，使政治人物名/城市名/中國詞皆不能救回軟文，如「碧姬馬克宏帶動品牌」「世足梅西」「網紅五星旗」皆濾）→ ③中國（政權／雙邊政治／與他國外交／台灣政黨對中→留，其餘→濾）→ ④影響市場地緣政治（地區＋觸發詞）→ ⑤美日台歐盟強政治（覆蓋地方地理判定）→ ⑥台灣他縣市地方新聞或地方公職（鎮長/市議員，無白名單縣市）→濾 → ⑦台灣政黨／選舉一般政治→留 → ⑧台北新北高雄地方→留 → ⑨其餘→濾。關鍵詞集依 **1561 則真實爬取標題 ＋ 12 路對抗式稽核（Task 199）校準**，設計上**偏向不誤刪財經**（財經訊號最先判、涵蓋最廣），其餘從嚴。體育涵蓋全項（世足/奧運/職棒/NBA/選手/教練/金牌/賽事…）、演藝涵蓋影劇娛樂（明星/藝人/演唱會/票房/緋聞…）。純關鍵詞啟發式，語意邊界必有少量誤判（如公司特稿/航空訂位增數/中國娛樂圈整治等軟性框架會被濾），為刻意取捨；子字串誤中須警惕（如已移除「出國」避免誤中「退出國民黨」、「北市」避免誤中「竹北市」）。可隨語料回饋增修。單元測試 `EditorialNewsFilterTest`。美國 CNBC／Nasdaq 為 `region=US`、不經本過濾（英文財經專屬 feed，無生活/體育版）。
- [x] **參考新聞時效性驗證（不得顯示過時新聞，Task 149.17 bug fix）**：模型自報的 `publishedAt` 不可信（實測曾把 2025-09 的舊聞標成「2026-06」的近期日期），故 `newsHighlights` 入庫前於 `MarketAnalysisService.sanitizeNews` 做**三層時效把關**，避免頁面把舊聞當「近期重點」呈現：
    1. **格式＋自報時效硬過濾**：`publishedAt` 必須是精確到日的 `YYYY-MM-DD`（`2026-06`／`null`／無法解析一律剔除）；且須落在「分析日往前 `news-max-age-days`（預設 5 天，見 Task 149.18）內、且不晚於分析日+1 天」的區間，否則剔除。
    2. **回抓原文實際發布日驗證（治本，可關）**：對通過第 1 層且有 http(s) 連結者，後端以短逾時（短 UA `Mozilla/5.0`）抓該連結原始頁面，擷取其真實發布日（JSON-LD `datePublished`／`article:published_time`／`meta[name=date]`／`<time datetime>`）；抓到真日期則以真日期覆寫 `publishedAt`（頁面顯示正確日期），且真日期超出時效區間即剔除（可擋「模型謊報精確近期日期」）；抓不到或抓取失敗則保留第 1 層驗證後的模型日期（不因對方站台擋抓而誤刪合法新聞）。以 `market-analysis.news-verify-published-date`（預設 `true`）可關閉此層。
    3. **提示詞強化（防禦縱深）**：system/user prompt 明確要求「只納入你經 web_search 實際查到、且發布日在最近 N 天內的新聞；`publishedAt` 必須為原文實際發布日（`YYYY-MM-DD` 精確到日）；無法確認精確近期日期就不要列入，不得用舊聞或臆測日期充數、不得依賴既有記憶」。
    - 全部剔除後 `newsHighlights` 得為空陣列（頁面既有空狀態處理）——寧可不顯示新聞，也不顯示過時／不可信新聞；當日多空判斷仍以走勢量化數據成立。與既有 http(s) 白名單（XSS 縱深）併存於 `sanitizeNews`。
- [x] **新聞時效收斂為 5 天＋來源地區限制（只限台美星、嚴禁中港澳，Task 149.18）**：因原本 30 天窗仍會納入偏舊新聞、且 `web_search` 常回傳大量中國大陸／香港來源（實測某日 4 則有 3 則為 `finance.sina.com.cn`、`sfccn.com` 南方財經等中國來源），收斂新聞政策：
    1. **時效改 5 天、越近越重要**：`news-max-age-days` 預設由 30 改為 **5**（`sanitizeNews` 時效區間、system/user prompt 皆同步套用此值）；prompt 強化「越近期權重越高、越舊越不重要」。
    2. **來源地區硬過濾（只留台/美/星，剔除中港澳）**：`sanitizeNews` 新增「地區封鎖」關卡（置於日期解析與昂貴回抓之前，命中即最早剔除、省 HTTP 成本），依新聞 `url` 的 host 與 `source` 顯示名比對後端 curated 封鎖清單，命中中國大陸／香港／澳門來源即剔除。清單分三類：**地區 TLD 後綴**（`.cn`／`.hk`／`.mo` 系，`host.endsWith`）、**具名 `.com`/`.cc` 網域**（如 `sfccn.com`、`eastmoney.com`、`caixin.com`／`caixinglobal.com`、`yicai.com`、`wallstreetcn.com`、`21jingji.com`、`futunn.com`、`cnfin.com`、`scmp.com`、`hket.com`、`wenweipo.com`、`takungpao.com`、`stnn.cc`、`aamacau.com` 等，`host==domain` 或 `host.endsWith("."+domain)`）、**來源名關鍵詞**（新浪財經／東方財富／南方財經／財新／南華早報／香港經濟日報… 繁簡兩式，`source.contains`）。清單為後端技術白名單（非使用者管理之業務分類，比照 `AVAILABLE_MODELS` 免入庫），經對抗驗證：台美星主流財經網域與 `cnyes.com`（台灣鉅亨網）／`cna.com.tw`（台灣中央社）／`cnbc.com`／`cnn.com` 等「含 cn 但非中國」網域**零誤殺**（規則只做完整結尾／整段網域比對，嚴禁 `contains("cn")` 子字串比對）。
    3. **提示詞地區限制＋積極列出（防禦縱深）**：system/user prompt 明確要求「只採用台灣、美國、新加坡的新聞；嚴禁納入中國大陸、香港、澳門的媒體或報導（即使內容與台美股相關）」，從源頭減少中港澳新聞被選入。另因實測一次重跑時模型在收緊條件下過度保守、直接回空 `newsHighlights`（過濾器未觸發、是模型自身不列），故 prompt 改為**積極要求多次搜尋並列出 3～6 則符合條件（台/美/星、近 N 天）的新聞**，個別不合格者才略過，唯有確實找不到才回空——硬性地區／時效仍由後端過濾器把關，故 prompt 可放心鼓勵列出而不致引入舊聞或中港澳。
    - 以 `market-analysis.news-region-block-enabled`（預設 `true`）可整體關閉地區封鎖以快速回退。全部剔除後 `newsHighlights` 得為空（既有空狀態），多空判斷仍以走勢數據成立。
- [x] **允許來源新增日本＋提示可靠來源清單（Task 149.19）**：實測某日分析 `newsHighlights` 仍回空、頁面顯示「近期未能取得可驗證且來源合乎規範的近期重大財經新聞」，動因為模型在「只限台/美/星」的窄來源集下搜不到合格新聞。放寬並引導：
    1. **允許地區新增日本（台/美/星 → 台/美/日/星）**：`buildSystemPrompt`／`buildUserPrompt` 的地區限制由「只採用台灣、美國、新加坡」改為「只採用台灣、美國、日本、新加坡」；嚴禁中港澳不變。**後端 `isRegionBlocked` 地區封鎖清單不需改**——日本來源本就不在封鎖清單內（只擋中港澳），放寬純屬提示詞層；且封鎖規則用 `host.endsWith`／整段網域比對，日經中文網 `zh.cn.nikkei.com`（host 以 `.com` 結尾、`.cn` 僅為中段子網域 label）不會被 `.cn` 後綴誤殺，維持「零誤殺」設計。
    2. **提示可靠來源清單（引導、非硬白名單）**：prompt 加入可優先參考的來源例示——台灣證券交易所（`twse.com.tw`，三大法人買賣超、大盤成交統計）、公開資訊觀測站（`mops.twse.com.tw`，上市櫃重大訊息與財報）、日經中文網（`zh.cn.nikkei.com`）、自由時報財經（`ec.ltn.com.tw`）、經濟日報（`money.udn.com`）、華爾街日報中文網（`cn.wsj.com`）、紐約時報中文網（`cn.nytimes.com`），並註明「不限於此、含 Reuters／Bloomberg／鉅亨網等其他台/美/日/星主流財經媒體」。**刻意不使用 `web_search` 的 `allowed_domains`（硬白名單）**——那會把搜尋限死在少數網域、反而縮小涵蓋、加劇搜不到的問題；來源例示只做提示，實際涵蓋仍開放。
    3. **強化積極搜尋**：prompt 要求「多次、換多組中英文關鍵字」搜尋，不要只搜一次就放棄。硬性地區（含日本放行、中港澳封鎖）與時效仍由後端 `sanitizeNews` 過濾器把關，故 prompt 可放心鼓勵列出。Controller／BFF／DTO／前端與封鎖清單皆不變。
- [x] **批次 web_search 修復：改用基本版 `web_search_20250305`（Task 149.20，重大 bug fix）**：實測 `newsHighlights` 持續回空、頁面「參考新聞」永遠空白，**根因非提示詞、非後端過濾器，而是 `web_search` 工具本身在 Batch API 下呼叫失敗**：
    1. **證據**：DB 該日 `raw_response` 模型原文自述「本次工具配額已用盡、多次嘗試皆無法成功呼叫新聞搜尋…newsHighlights 回傳為空陣列」；後端 `sanitizeNews` 日誌**零筆過濾**（不是被濾掉，是模型沒交出）；以同模型（Sonnet 5）、同 `web_search` 設定做**非批次**直呼則正常回 10 則真實新聞。
    2. **機制**：原用的 `web_search_20260209`（動態過濾版）底層以 **`code_execution`** 沙箱過濾搜尋結果，而 code_execution 在 **Message Batches API** 下會 `detection_timeout`（實測回 `{"status":"detection_timeout","error":"Detection timed out after 90.0s"}`、`return_code=1`），整條搜尋鏈斷掉。以「動態 vs 基本」雙請求診斷批次對抗驗證：動態版 code_execution timeout、基本版 `web_search_20250305` `end_turn` 乾淨完成、穩定回 20 則真實新聞。
    3. **修法**：`MarketAnalysisService.submitBatch` 的 web_search tool 由 `WebSearchTool20260209` 改為 `WebSearchTool20250305`（基本版不走 code_execution、結果直接進 context、批次可用；保留 Batch API 50% 成本優勢）。回覆解析（只收 text 區塊、忽略 `web_search_tool_result`）不變。**`PortfolioAdviceService` 為同步呼叫、動態版正常，不同動。**
- [x] **本地財經新聞爬蟲供市場分析（Task 149.21，降低/可關閉付費 web_search）**：付費 `web_search` 每分析日只跑一次、成本雖小（<$1/月），但（1）批次下曾整條壞掉、（2）新聞來源不可控、發布日期不可信。故新增「自抓權威來源＋證交所公開資訊、餵入提示詞」的本地新聞管線，讓使用者可把 web_search 關掉改用穩定、日期精準、零幻覺的本地新聞：
    1. **生產端（external-materials-service）**：比照既有 producer 模式（`JdbcTemplate` 直寫共用 postgres、無 Liquibase/JPA），新增 `NewsPoller`（cron 每日 08:20/11:30/18:00 Asia/Taipei ＋開機 warmup ＋保留 N 天；早上 08:00→08:20 見 Task 184、中午 12:00→11:30 見 Task 188），逐來源抓取後 upsert 至新表 `news_headline`：
        - **權威新聞**（來源集見 Task 149.22）：玩股網 WantGoo（JSON API `wantgoo.com/news/all-headlines-by-category`）、MoneyDJ 理財網（即時新聞 HTML `moneydj.com/kmdj/news/newsreallist.aspx`）、自由時報財經（RSS `news.ltn.com.tw/rss/business.xml`）、經濟日報 udn（RSS `money.udn.com/rssfeed/news/1001/5591`）——皆帶**真實發布時間**（WantGoo `time` epoch／MoneyDJ 列時間／RSS `pubDate`），category=`news`、region=`TW`。
        - **證交所公開資訊**：三大法人買賣金額（`www.twse.com.tw/rwd/zh/fund/BFI82U?response=json`，外資/投信/自營/合計買賣差額，category=`twse-institutional`）、大盤成交統計（`openapi.twse.com.tw/v1/exchangeReport/FMTQIK`，加權指數/成交值/漲跌，category=`twse-turnover`），published_at=交易日、source=`twse`。
        - 逐來源 graceful degrade（單一來源失敗只 log warn、不影響其他）；UA 一律 `Mozilla/5.0`（比照既有抓取慣例）。MOPS 重大訊息因無穩定公開 API（POST+HTML、易遇 WAF）**本期不做，列後續**。
    2. **消費端（business-services / MarketAnalysisService）**：`submitBatch` 前撈近 `news-max-age-days` 天的 `news_headline`，於 `buildUserPrompt` 在走勢資料後注入「近期新聞（本地抓取）」結構化區塊（日期/來源/標題/連結/摘要），像餵走勢數據一樣。本地新聞**注入時繞過 `sanitizeNews`**（已保證來源與真實發布日）；模型輸出的 `newsHighlights` 仍照舊全程 sanitize（模型可能引用清單外內容）。
    3. **web_search 三態語意升級（沿用既有 `web_search_max_uses` 設定、零 schema 變更）**：`0`＝**純本地新聞模式**（注入本地新聞、不加 web_search tool＝省錢且不再空白）；`3/4/6`＝**本地新聞＋web_search 補今日最新**。使用者於現有設定 UI 一鍵切換。撈不到本地新聞時 graceful（`>0` 退回純 web_search、`==0` 退回純技術面，維持現行行為）。
- [x] **爬蟲公開資訊每次輸出 JSON 檔供 SRPP 退休規劃專案（Task 177，以資料庫為單一來源）**：`NewsPoller` 每次抓取（每日 08:20／11:30／18:00 Asia/Taipei ＋開機 warmup；早上 08:00→08:20 見 Task 184）**先把公開資訊 upsert 進 `news_headline`，再由 `news_headline` 查詢產生 JSON**（DB 為單一事實來源，JSON＝DB 的當日快照，自然反映去重與個股過濾）。輸出至 SRPP 退休規劃專案輸入目錄（host `/Users/steven/Project/SRPP/data/input`），供其量化分析取用；**每次抓取都輸出**（三次各更新一次）。**當日範圍＝「今天這批爬蟲抓進來的」且「資料日期不早於上一交易日」**：撈 `news_headline` 中 `fetched_at`（Asia/Taipei）為今天、且 `published_at`（Asia/Taipei）不早於 `cutoff` 的列；`cutoff`＝**上一交易日**＝`news_headline` 中 twse 總體資料（`category twse-*`）的最新資料日（TWSE 權威——遇假日 BFI82U 回最近交易日，故此日期即上一交易日；無 twse 時 fallback 以 `MarketCalendar` 最近交易日）。**刻意不用日曆算 cutoff**：實測日曆得 7/10、而 twse 實際資料為 7/09，用 7/10 反把三大法人／大盤成交（7/09）濾掉；取 twse 自身日期才保證總體資料保留、且今天抓到但發布日更舊的過期新聞排除。檔名 `public_info_<yyyy-MM-dd>.json`（Asia/Taipei 當日；同日多輪覆寫＝當日最新一份、跨日新檔）；內容含 metadata（generatedAt／trigger／tradingDayCutoff／count）與逐則明細（title／source／url／category／region／summary／publishedAt；`publishedAt` 以 Asia/Taipei +08:00 序列化，日期與交易日／`tradingDayCutoff` 一致，不因 UTC 使 TW 凌晨/整點資料日期倒退一天）。以 docker volume 將 host 目錄掛入 `external-materials-service` 容器（比照 Requirement 34 慣例）。**輸出目錄於 Task 212 起改為 DB 驅動**（`crawler_export_setting` 的相對子路徑 ＋ 容器基底 `EXPORT_OUTPUT_DIR`，可於「爬蟲資訊查詢」頁設定，見 Requirement 38；原 `news-scraper.export-dir`＋`/srpp-input` 掛載已移除，host 落點不變）；輸出開關仍為 `news-scraper.export-enabled`。`news_headline` 本身維持保留期（30 天，供今日股市分析讀近 N 天），不受此當日範圍影響。寫出採**暫存檔＋原子 rename**（避免 warmup 執行緒與 cron 併發截斷、SRPP 不讀到寫一半的檔）；寫檔失敗一律 graceful（只 log warn、不影響落庫與其他排程）。
- [x] **公開資訊只保留 stock 主檔個股＋總體新聞，其餘個股濾除（Task 178）**：公開資訊（尤其 wantgoo 新聞）含大量非投資組合個股的專題新聞，對本系統為雜訊。故 `NewsPoller` 抓取後、upsert `news_headline` 與輸出 SRPP JSON **之前**，先以「全市場名冊精準判定」過濾：識別每則明確指向的台股個股代號，若**全部不在 `stock` 主檔**則濾除；命中 `stock` 主檔任一個股、或屬總體/國際新聞（未指向任何可辨識個股）、或 TWSE 三大法人/大盤成交（category `twse-*`）一律保留。**判定只採高可信訊號以免誤傷總經新聞**（實測裸 4 位數字會把年份 2024/2030 誤當代號、短公司名子字串會誤命中）：(1) wantgoo 來源的結構化 `newsTags`（來源標註的相關實體，對應全市場名冊 `name→code`）；(2) 所有來源的**明確代號格式（須帶 `-TW` 後綴）** `(6967-TW)`／`6967-TW`（刻意不認裸數字或括號內年份如 `(2023)`——2020～2031 等年份正好是真實上市鋼鐵股代號，採信會誤濾含年份的總經新聞，Task 178 review 修正）。全市場 `name→code` 取自 `MarketDataFetchService` 既有台股全市場字典（TWSE STOCK_DAY_ALL 上市＋TPEX 上櫃，24h cache）；`stock` 主檔代號由 ext 直讀。名冊載入失敗時 graceful 全留（不誤濾）。過濾同時套用於 DB 落庫與 SRPP JSON 輸出（兩者一致）。實測今日 232 則濾除約 5–6 則、零誤傷。
- [x] **模型與輸出**：使用 Claude Opus 4.8（`claude-opus-4-8`，adaptive thinking + `web_search_20250305` 基本版 server tool——批次下不可用動態過濾版 `web_search_20260209`，見 Task 149.20）產生結構化判斷：方向（偏多 `BULLISH`／偏空 `BEARISH`／中性 `NEUTRAL`）、信心度（0–100）、當日走向總結（繁體中文一段）、關鍵因素清單、參考新聞摘要（標題／來源／連結）、台股與美股近期走勢摘要。輸出以 JSON 交還後端解析入庫；解析採「取首個 `{` 至末個 `}`」容錯，並忽略未知欄位。全程使用台灣繁體中文。
- [x] **歷史保存**：結果存入 `daily_market_analysis`，每個交易日一筆（`analysis_date` 主鍵）；同日重跑覆蓋當日該筆（upsert）。此為全域參考資料（不分租戶、無 `owner_user_id` 欄位，比照 `twse_index_daily_history`／`us_index_daily_history`／交易日曆假日）。
- [x] **前端頁面「今日股市分析」**：左側選單新增「今日股市分析」項；頁面顯示當日（或最近一筆）判斷——方向以配色標示（**台股漲紅跌綠**：偏多紅、偏空綠、中性灰）、信心度、走向總結、關鍵因素、參考新聞（可點連結）、台股／美股走勢摘要、產生時間與所用模型；下方可回看過去每日的判斷歷史。多 panel 資料以單一 BFF 聚合回傳。
- [x] **手動重新分析（限管理者）**：管理者可於頁面按「重新分析」立即重跑當日分析（bff 對 `POST /generate` 限 `ROLE_ADMIN`，backend 端以 `CurrentUserContext.isAdmin()` 縱深防禦）；一般使用者唯讀、不顯示該按鈕。
- [x] **金鑰未設定與失敗的優雅降級**：`ANTHROPIC_API_KEY` 未設定時不報錯，排程與手動觸發皆安全跳過並記 `status = NOT_CONFIGURED`，頁面顯示「尚未設定 Anthropic API 金鑰」；LLM 呼叫或 JSON 解析失敗時記 `status = FAILED` ＋錯誤訊息、保留 `raw_response` 供除錯，不影響其他排程，頁面顯示失敗狀態且管理者可重試。金鑰經環境變數注入 business-services，不入版控。
- [x] **改用 Batch API（非同步、省 50% token 成本）**：分析的 LLM 呼叫改走 Anthropic **Message Batches API**（同模型／同 prompt／同 `web_search`＋thinking，準確度不變，token 計價打 5 折）。因 batch 本質非同步，流程改為：送出批次（1 request）後該交易日 `daily_market_analysis` 落 `status=PROCESSING`＋暫存 `batch_id`、立即返回；**背景 poller**（每 90 秒）撈 `PROCESSING` 列、待批次 `ENDED` 後取結果解析落庫（`OK`／`FAILED`）並清 `batch_id`；送出超過 12 小時仍未完成則判 `FAILED`（逾時保護）。**行為改變**：手動「重新分析」不再即時回結果——送出後頁面顯示「分析中（批次處理中）」並每 30 秒自動更新，完成後顯示卡片。同一交易日已在 `PROCESSING` 時，排程與手動皆不重複送出（避免重複花費）。poller 不受 `enabled` 影響（只收尾已送出批次）。
- [x] **模型與思考深度可於頁面調整（成本控管，限管理者）**：管理者可在「今日股市分析」頁的下拉選單切換分析模型（`claude-opus-4-8`／`claude-sonnet-5`／`claude-haiku-4-5`——品質對成本），選擇持久化於單列設定表 `market_analysis_setting`（`id=1`），**下次分析（排程或手動）即生效、無需改環境變數或重啟**。後端於每次產生時以 `resolveModel()` 取「設定值 → 否則 `ANTHROPIC_MODEL` 環境預設」。可選模型清單為後端 curated 技術白名單（僅有效 Claude model id，非使用者可自訂之業務分類，故不套用「Enum 必須入庫管理」規範）；`PUT` 僅接受白名單內 id，防注入無效／任意 model。設定寫入限管理者（bff `PUT` 限 `ROLE_ADMIN` + backend `CurrentUserContext.isAdmin()` 縱深防禦）；一般使用者唯讀、不顯示選單。`ANTHROPIC_MODEL` 環境變數保留為初始預設／後備。
    - **另可切換思考深度 `effort`**：`low`／`medium`／`high`（對應 Anthropic `output_config.effort`）。thinking 推理輸出按 output token 計價（最貴那條），`effort` 越低思考 token 越少、單次分析越省，故為主要成本槓桿之一。同樣持久化於 `market_analysis_setting`（新增 `effort` 欄），`resolveEffort()`（設定值 → 否則預設 `medium`）於每次產生時套用 `output_config.effort`，下次分析生效、限管理者、白名單驗證。預設 `medium`＝成本／品質平衡（較先前未指定 `effort` 時 API 隱含的 `high` 省）；`xhigh`／`max` 更貴、與本頁省錢目的相反，故不列入白名單。
    - ~~**另可切換新聞搜尋次數 `web_search_max_uses`**~~（**Task 179 已移除**）：原提供 `0`（關閉純技術面）／`3`／`4`／`6` 之 `web_search` 次數下拉。因新聞來源改為純本地爬蟲 `news_headline`（見上「財經新聞改由本地爬蟲…移除 `web_search`」），此設定失去意義：頁面下拉、`MarketAnalysisSettingsDto.webSearchMaxUses`／`WebSearchOption`、`MarketAnalysisSetting.web_search_max_uses` 欄（Liquibase `v1.54.0` `DROP COLUMN`）、`resolveWebSearchMaxUses()` 與 `submitBatch` 的 `web_search` tool 掛載一併移除。分析改為固定「注入本地新聞 → 有則據以列 `newsHighlights`、無則純技術面」，不再有次數選項。
    - **每日自動分析開關 `enabled`（成本控管，限管理者）**：因每次分析要花錢、不一定每天都想跑，管理者可在頁面用開關**停用每日自動分析**。停用（`enabled=false`）時 08:45 cron 與開機 self-heal **直接跳過、不呼叫 LLM、零花費**；但**手動「重新分析」不受此限**（管理者明確選擇花錢跑單次）。持久化於 `market_analysis_setting`（新增 `enabled` 欄，`isEnabled()` 設定值 → 否則預設 `true`），預設 `true`＝維持既有每日自動行為。停用時頁面「尚無資料」說明改為「已停用、由管理者手動產生」。`PUT /settings` 可帶 `model`、`effort`、`enabled` 之任意組合（未帶之欄不變；`webSearchMaxUses` 已於 Task 179 移除）。
- [ ] **分析結果每日自動 Email 寄送（沿用通知收件人，Task 151）**：每個台股交易日的分析**批次收尾成功（`status = OK`）**時，自動將結果以 Email 寄給「訂閱股市分析」的收件人。收件人**沿用既有通知收件人**（`notification_recipient`，Requirement 23），不另建名單；SMTP 重用既有設定（`MAIL_USERNAME`／`MAIL_PASSWORD`，未設定則安全略過不寄、不報錯）。**只寄一次／不重複打擾**：以 `daily_market_analysis.email_sent_at` 為冪等記號——批次收尾（`finalizeIfReady`）首次落 OK 且尚未寄過才寄，寄達後戳記；同一交易日之後的手動「重新分析」重跑收尾不再重寄（滿足「僅每日自動寄」精神：一交易日恰一封）。非 OK（`PROCESSING`／`FAILED`／`NOT_CONFIGURED`）不寄。寄送任一階段失敗一律 log 不拋、不影響批次收尾與落庫。信件內容含方向（**台股漲紅跌綠**配色）、信心度、走向總結、關鍵因素、台美走勢摘要與參考新聞連結（連結沿用入庫時已過濾之 http(s) 白名單，寄送時再驗一層）；逐一收件人各寄一封（保護彼此 email 隱私，比照 Requirement 23 警示通知）。
- [ ] **可選擇哪些收件人接收（per-recipient 訂閱，Task 151）**：`notification_recipient` 新增「是否接收股市分析」旗標（`receive_market_analysis`，預設 `TRUE`——沿用既有收件人故預設訂閱、可自行取消，與「是否接收警示」`active` 各自獨立）；每日寄送對象為「`active = true` 且 `receive_market_analysis = true`」的收件人。「今日股市分析」頁新增「分析結果寄送對象」區塊，列出使用者自己的收件人並以開關切換其「接收每日股市分析」訂閱（走該頁專屬 BFF passthrough `/api/bff/today-market-analysis/recipients/**` → business `/api/notification-recipients/**`，與通知設定頁**同一 business API／同一事實來源**）；收件人之新增／刪除仍於「系統設定 → 通知設定」進行。此旗標為 per-user（owner-scoped）：使用者僅能檢視／切換自己名下的收件人（讀寫端 `TenantFilterAspect` + `TenantGuard` 保障）；每日排程於背景執行緒（無 request context）讀取時不套 owner 過濾，寄給所有已訂閱收件人（全域分析，寄給所有租戶已訂閱者）。
- [ ] **可設定多個「分析寄送時間」，每個時段各重跑一次分析並各寄一封（限台股交易日，Task 191）**：原本每交易日固定 08:45（Task 186）觸發一次分析並寄送；改為管理者可在「今日股市分析」頁自訂**多個寄送時間**（新表 `market_analysis_send_time`：`send_time`（`TIME`，精確到分）＋`active` 啟用旗標，全域**單一排程設定、不分租戶**，比照 `market_analysis_setting`；Seed 一列 `08:45 active` 保留現行行為）。
    - **排程改為每分鐘 tick**：`MarketAnalysisScheduler` 由單一 `0 45 8 * * MON-FRI` 改為 `@Scheduled(cron="0 * * * * MON-FRI", zone="Asia/Taipei")`——每分鐘取現在 `HH:mm`（截到分），比對是否命中任一**啟用中**寄送時間；**未命中即零成本 return**（不查 LLM、不做颱風假偵測）。命中才做交易日閘門（`refreshTwClosureToday()` 權威即時颱風假偵測 + `isTwTradingDay`，週末／颱風假／臨時休市一律不觸發、不花錢，比照既有 08:45 閘門），通過才觸發一次**全新分析**。
    - **每個時段各跑一次 LLM 批次、各寄一封**：命中時走 `MarketAnalysisService.generateForSend`＝強制重跑（不 skip-if-OK）＋**送出批次時重置 `email_sent_at = null`**，使該批次於 `finalizeIfReady` 收尾時（仍先再驗一次交易日，Task 162 縱深守門不變）重新寄一封。既有「同日已 `PROCESSING` 不重複送出」守門保留——若兩時段過近、前一批次尚未收尾，後一時段略過不堆疊批次（時段應拉開間距）。手動「重新分析」維持現狀（`email_sent_at` 不重置、不自動重寄，避免手滑群發）。
    - **成本提示**：等於把每日 1 次分析放大為「啟用時段數 × 每交易日」；頁面明確提示「每多一個時段即多一次 AI 費用（各時段各跑一次）」。
    - **儲存策略（覆蓋、不留當日各時段歷史）**：`daily_market_analysis` 維持**一天一列**（`analysis_date` 主鍵），後一時段覆蓋當日同列；「今日／歷史」顯示最新那次。**刻意不改成一天多列**——會牽動主鍵與約 10 處查詢面（`findById(date)`／`latest`／`hasOkFor`／`customId` 等）＋BFF＋前端歷史（每日一列）UI，成本過高且與現行歷史表一致性相悖。
    - **管理限管理者、一般使用者唯讀**：新增／刪除／啟用切換走該頁專屬 BFF（`GET` 併入 `/api/bff/today-market-analysis` 聚合、`POST/DELETE/PATCH /api/bff/today-market-analysis/send-times/**` 限 `ROLE_ADMIN`）→ business `/api/market-analysis/send-times/**`（backend `CurrentUserContext.isAdmin()` 縱深防禦、時間格式與唯一性驗證於 service）；一般使用者於頁面唯讀顯示「啟用中的寄送時間＋限台股交易日」提示。`enabled` 每日自動分析總開關仍優先——停用時所有時段皆不觸發（零花費）。
    - **開機 self-heal 改依「最早的啟用寄送時間」**：服務於首個時點未運行（重啟／部署）且今日尚無 OK 時補跑一次（`generateIfAbsent`，寄一封）；**不逐時段補寄**（避免重啟時對已過的多個時段一次洗版）。無任何啟用時間則不補跑。

### Requirement 32: 資產配置建議——依個人條件與持有資產由 AI 給個人化配置建議

**User Story:** 作為投資人，我希望先設定我的理財條件（生日、退休日期、退休前年薪與年支出、退休後現金流、理財目標、獲利預期、可忍受風險），系統再結合我目前實際持有的資產，用 AI 給我一份個人化的資產配置建議（該調整成怎樣的配置、具體怎麼做、要注意什麼風險），並且能回顧我過去產生過的建議。

**Acceptance Criteria:**

- [ ] **理財條件表單（記住免重填）**：左側選單新增「資產配置建議」項；頁面提供條件表單——**生日**（整日，`el-date-picker type="date"`、`value-format="YYYY-MM-DD"`；年齡由生日與今天衍生顯示、不入庫，正規化）、**退休前年薪**與**退休前年生活費**（今日幣值／年；退休前每年淨投入＝年薪−年支出，取代原「每月可投入」，Task 168）、**預計退休日期**（整日，`el-date-picker type="date"`、`value-format="YYYY-MM-DD"`）、**假設年通膨率**（%，預設 2，供未來大筆花費換算，可自行調整）、理財目標（複選：退休準備／資產增值／被動收入（存股領息）／子女教育／購屋置產／短期資金週轉／財富保值傳承）、可忍受風險（保守／穩健／積極）、獲利預期（年化報酬區間 < 3% / 3–6% / 6–10% / > 10%）。條件持久化於 `investment_profile`（一使用者一列，`owner_user_id` 唯一），下次進頁面自動帶回免重填。理財目標／風險／獲利區間為本頁表單詞彙，比照分析模型／思考深度以「服務層白名單」提供選項（非跨域業務分類，不入 `/api/settings`）。退休日期須晚於今天（前端驗證＋提示）。
- [ ] **退休兩階段建模（每月投入退休後歸零；不需「投資年限」，Task 166）**：由生日與退休日期推導「累積年數＝退休日期與今天相距的整年數（整月數 ÷ 12 無條件捨去）」與「退休後守成年數＝100 − 退休年齡（退休年齡＝生日到退休日的整年）」。**不再需要獨立的「投資年限」欄位**——有生日（→年齡）＋退休日期（→退休時點）＋退休現金流試算固定推到 100 歲後，累積期＝今天到退休日、退休後＝退休到 100 歲，皆可衍生；`investment_profile.investment_horizon_years` 欄移除（`portfolio_advice` 的歷史條件快照欄保留、新紀錄不再寫入）。估算未來可投入資金時，退休前每年淨投入（年薪−年支出）**只計入累積期**（退休前），退休後淨投入視為 0（薪水停止，Task 168）。兩衍生年數**不入庫**（正規化），由 service `retirementSpan(birthDate, retirementDate)` 現算。組 prompt 時明確切成「累積期（有淨投入）／退休後守成期（淨投入 0）」兩段，並要求 AI 越接近／進入退休越保守、退休後不得假設仍有定期投入攤平風險。
- [ ] **退休後現金流與未來大筆支出（餵入 AI）**：條件表單另可填——**勞保年金**（月領金額 ＋ 起領年月）、**勞退**（一次領金額 ＋ 領取年月）、**特定日期大筆花費**（可多筆：日期＋用途（如「買車」）＋今日幣值金額；存於子表 `investment_planned_expense`，owner-scoped）。**幣值處理**：勞保／勞退金額照填、**不做通膨調整**（視為勞保局試算之未來實際給付）；大筆花費金額為**今日幣值**，由 service 依「假設年通膨率」以 `今日金額 × (1+r)^(距花費日之年數)` 換算為**未來名目金額**（衍生值，不入庫）。三者於 request 執行緒組 prompt 時整理成「退休後現金流／未來支出」段餵給 Claude：勞保月領與勞退一次領為退休後收入來源（可降低對投資組合提領的依賴），大筆花費為未來一次性支出（需預留流動性、越接近支出日越保守）。AI 於 `targetAllocation`／`rebalancePlan`／`actions`／`warnings` 中一併考量。
- [ ] **退休現金流試算（決定性逐年，非預測；Task 165／退休後兩階段年支出 Task 167）**：條件表單填**長照前年生活費**（今日幣值／年，必填才試算）、**長照後年生活費**（今日幣值／年，選填，通常較高）、**長照起始年齡**（選填，留空預設 80，夾在 [退休年齡, 100]）、**累積期年報酬率**與**退休後年報酬率**（%，試算假設，留空則依「獲利預期」區間帶入預設、退休後較保守；使用者可覆寫，均持久化於 `investment_profile`）。系統以 `RetirementProjectionService` 對最新快照的資產總額做**決定性逐年試算**（非投資報酬預測）：期初餘額每年以當期報酬率複利成長 → 累積期（退休前）加「年薪 − 退休前年生活費」淨投入（皆今日幣值、依通膨逐年膨脹，Task 168）、退休後扣年生活費（**兩階段**：長照前用長照前年生活費、自長照起始年齡起用長照後年生活費，皆 ×(1+通膨)^距今年數）→ 加勞保年金（自起領年起，每年；依《勞工保險條例》第65條之4，年金給付於 CPI 累計成長率達 ±5% 之年，才依**實際累計漲幅**階梯式調整並重設基準——非逐年隨通膨、非固定 5%；2% 通膨下約每 3 年跳一階。勞退為確定提撥制、不適用此調整）與勞退一次領（領取當年）→ 扣當年到期之大筆花費（依通膨換算名目值），逐年推到 **100 歲**或資金耗盡，回答「退休後能撐到幾歲／哪一年出現缺口／退休首年結餘」。走 `GET /api/portfolio-advice/projection`（business，owner-scoped）→ 頁面專屬 BFF 聚合 → 前端以 **ECharts 逐年資產餘額折線圖 ＋ 白話結論**呈現（退休年齡與長照起始 markLine、缺口 markPoint）。缺生日／無快照／（有退休日但）未填長照前年生活費 → 回 `available=false` ＋原因，前端提示補資料。**同一份試算摘要**於 request 執行緒組 prompt 時一併餵給 AI（單一真實來源、與前端圖一致），要求 AI 於 `riskAssessment`／`warnings` 具體評估「資產是否足以支應退休提領（含長照期較高支出）」。
- [ ] **建議輸出具體到新台幣金額（可執行操作；Task 165）**：`targetAllocation` 每類除目標比例 `targetPct` 外，AI 估算 `currentValue`（把持有明細分類歸屬到該類的金額），後端以「資產總額 × targetPct」**決定性回填** `targetAmount` 與差額 `deltaAmount`（金額算術由後端做、不交給 LLM），前端呈現「目前約 X 元 → 目標約 Y 元（增碼／減碼 Z 元）」。另新增 `rebalancePlan`（逐標的再平衡操作）：AI 針對使用者實際持有標的（代號／名稱）與需新增類別，輸出 `BUY`／`SELL`／`HOLD` 與估計 `estimatedAmount`（新台幣），前端以紅（減碼）綠（增碼）操作表呈現。`actions` 保留「非金額類做法步驟」（再平衡節奏／緊急預備金／定期檢視）。
- [ ] **持有明細餵入更完整（Task 165）**：組 prompt 的持有明細，股票補**股數／成本／損益**、基金補**代號／成本／損益**、存款補**銀行名稱**（`bank` 以 join fetch 一併取得，避免 lazy／N+1），供 AI 分辨套牢 vs 獲利可調節部位、判斷更精準。
- [ ] **年齡衍生自生日（正規化）**：`investment_profile` 改存 `birth_date`、不再冗存 `age`；年齡＝生日與今天相距整年數，由前端／service 現算。產生建議時把當下衍生年齡寫入 `portfolio_advice.age`（歷史條件快照，denormalize 例外），歷次回顧顯示不受影響。
- [ ] **依持有資產產生個人化建議（非同步、產生中輪詢）**：使用者按「產生建議」時，後端讀該使用者**最新 `asset_snapshot`** 的現況配置（存款／基金／股票占比）與持有明細（存款類型／基金名稱／股票代號等），結合上述條件組提示詞，呼叫 Claude（adaptive thinking + 可選 `web_search`）產生結構化建議：整體評析（`summary`）、現況與風險評估（`riskAssessment`）、建議目標配置（`targetAllocation`：各資產類別目標比例%＋理由，加總約 100）、具體調整動作（`actions`：標題／說明／優先度）、風險提醒（`warnings`）、參考來源（`references`）。輸出以 JSON 交還後端解析（取首個 `{` 至末個 `}` 容錯、忽略未知欄位）。全程台灣繁體中文、金額為新台幣。**非同步流程**：因單次 Claude 呼叫含 thinking + web_search 常達數十秒（超過 nginx／proxy 60s 逾時），`POST /generate` **立即落一筆 `status = PROCESSING` 並回傳**，實際呼叫由 business-services 背景執行緒進行，完成後把該列更新為 `OK`／`FAILED`；前端顯示「產生中」並每 5 秒輪詢，完成後自動更新（比照 Requirement 31 的 PROCESSING 體驗，但為 in-process 背景執行緒、非 Batch API）。**多租戶正確性**：在 request 執行緒內（owner filter 生效）先組好 prompt（取到正確的自己快照與明細），背景執行緒僅做 Claude 呼叫並以 `adviceId` by-id 更新（不觸及 owner-scoped 查詢），避開背景執行緒 owner 過濾不啟用的坑。PROCESSING 卡逾 10 分鐘（背景中斷／服務重啟）→ 讀取時自癒判 `FAILED`。尚無任何快照時，仍可依條件給「一般性起始配置」建議並提示先建立快照。
- [ ] **現況配置概覽（同一事實來源）**：頁面顯示使用者目前的資產配置（存款／基金／股票占比與金額），資料由後端依最新 `asset_snapshot` 計算（`GET /api/portfolio-advice/current-allocation`），與儀表板同一快照來源、前端不各自重算（比照「同義欄位、同一 business service API」原則）。
- [ ] **歷次建議保存與回顧**：每次產生的建議存入 `portfolio_advice`（owner-scoped，`@Filter ownerFilter` 隔離），保存產生當下的**條件快照**（年齡／年限／月投入／目標／風險／獲利預期）與**資產依據**（`based_on_snapshot_id` 正規化參照 ＋ `based_on_snapshot_date`／`based_on_total_assets` 歷史快照），供頁面「歷次建議」展開回顧「當時的條件與依據」，即使之後 profile 改動或快照刪除亦不失真（比照 `realized_gain` 記名稱字串之歷史快照例外）。`result_json` 存解析後結構化建議（比照 `daily_market_analysis` 存 parsed JSON）。
- [ ] **成本控管設定（限管理者）**：管理者可在頁面調整分析模型（`claude-opus-4-8`／`claude-sonnet-5`／`claude-haiku-4-5`）、思考深度 `effort`（`low`／`medium`／`high`）、`web_search` 次數（`0`＝僅依個人資產與條件／`3`／`4`／`6`），持久化於單列設定表 `portfolio_advice_setting`（`id=1`），下次產生即生效（比照 `market_analysis_setting`）。可選清單為技術白名單；`PUT` 僅接受白名單值。設定寫入限管理者（bff `PUT /settings` 限 `ROLE_ADMIN` + backend `CurrentUserContext.isAdmin()` 縱深防禦）；產生建議 `/generate` 與儲存條件 `/profile` 為 per-user（owner-scoped）開放已登入者（各自產自己的建議、承擔自己的成本）。預設 `opus-4-8` / `medium` / `4`。
- [ ] **多租戶隔離**：`investment_profile` 與 `portfolio_advice` 皆以 `owner_user_id` 隔離，使用者僅能存取自己的條件與建議（`TenantFilterAspect` + `TenantGuard`）；建議依據的資產快照亦為 owner-scoped，不會拿到他人資產。
- [ ] **金鑰未設定與失敗的優雅降級**：`ANTHROPIC_API_KEY` 未設定時安全跳過並落 `status = NOT_CONFIGURED`，頁面提示未設定金鑰；LLM 呼叫或 JSON 解析失敗落 `status = FAILED` ＋錯誤訊息、保留 `raw_response` 供除錯，不拋出，頁面顯示失敗且可重試。`references` 連結僅保留 http(s)（web_search 為不可信來源，後端過濾 + 前端 `safeUrl()` 擋 `javascript:`／`data:`，縱深防禦 XSS）。
- [ ] **免責聲明**：頁面明顯標示「本建議由 AI 依你提供的條件與資產產生，僅供參考，不構成投資建議；投資有風險，請自行評估」。

### Requirement 33: 股票與大盤績效比較——最多三檔股票與五大指數同圖比報酬率

**User Story:** 作為投資人，我希望能把自己關心的股票（最多三檔）放在同一張圖上互相比較，也能把它們和台股大盤、美國道瓊、標普500、那斯達克、費半等大盤指數一起比，用「同一起點正規化後的累積報酬率」看誰在這段期間漲得多、誰抗跌，並可切換不同觀察區間。

**Acceptance Criteria:**

- [ ] **新增「績效比較」頁**：左側主選單新增「績效比較」項（`/performance-comparison`）；頁面提供三組控制項——股票下拉（最多 3 檔）、大盤基準勾選（5 個）、觀察區間切換（1 個月／3 個月／半年／1 年／2 年／5 年／10 年，預設 1 年）；主體為單一疊圖折線圖 ＋ 下方報酬率摘要表。**不支援「當日」分時比較**（跨市場、跨時區的當日報酬無比較意義，僅提供日線區間）。
- [ ] **股票只能選「我的股票」（owner-scoped 下拉）**：股票下拉選項由後端提供該登入使用者自己的股票清單——來源為「歷年資產快照的持股（`stock_holding`，經 `asset_snapshot` owner 過濾）」∪「觀察清單（`stock_alert` 衍生，owner 過濾）」去重 `(code, market)`，並**排除台股大盤特殊代號 `0000/台股`**（與 TWSE 基準重複、且無個股歷史）、**過濾掉 `stock_price_history` 尚無資料而無法比較者**，股名一律由 `stock` 主檔（`(code, market)`）補齊（明細與觀察表皆不冗存股名，正規化）。清單為 per-user：使用者只會看到自己名下的股票（`TenantFilterAspect` 於 repository 層以帶 `@Filter(ownerFilter)` 的 `AssetSnapshot`／`StockAlert` 為查詢 root 自動隔離；持股 distinct 查詢**絕不直查無 `@Filter` 的 `StockHolding`**，避免跨租戶洩漏）。`el-select` 以 `multiple filterable :multiple-limit="3"` 限制最多 3 檔。
- [ ] **大盤基準（5 個，資料庫既有）**：可勾選的大盤/指數固定為台股大盤（TWSE）、道瓊工業（DJI）、標普500（SPX）、那斯達克綜合（IXIC）、費城半導體（SOX）；資料重用既有 `twse_index_daily_history`（TWSE）與 `us_index_daily_history`（其餘四者，`indexCode`）兩張表，與「股市大盤查詢」頁**同一事實來源**。BFF 端以白名單限定這 5 個代碼（`us-daily-index` GET 不驗 code，由 BFF 守門）。
- [ ] **報酬率正規化疊圖（同起點 = 0%）**：因個股數百元與指數數萬點無法直接同軸比較，圖表 y 軸為「累積報酬率(%)」，各標的一律**正規化到觀察區間起點 = 0%**：`base = 該標的區間內第一筆非空收盤`，某交易日值 `= (該日或之前最近一筆收盤 / base − 1) × 100`。個股取 `stock_price_history.closePrice`、指數取日線表 `closePoint`（同義計算、同一口徑）。此正規化計算集中於**該頁專屬 BFF**（`/api/bff/performance-comparison/compare`），前端只 render（比照「聚合／計算放 BFF」原則）。圖上另畫一條 0% 水平基準線。
- [ ] **跨市場交易日對齊與缺日處理**：台股與美股交易日／時區不同，BFF 以「所有選取標的在區間內交易日的 union 排序軸」為 x 軸，各標的用「該日或之前最近一筆收盤」forward-fill（前端 `connectNulls` 讓缺日不斷線）；某標的第一筆資料日之前留 null（如新上市股，線從中段開始）。今日這格可能個股已有即時成交價、指數尚未回補而不對齊——各標的以「自己最後一個非空報酬」計期間報酬並標示「截至日」（`asOfDate`）。
- [ ] **報酬率摘要表**：圖下方列出每個已選標的的「期間報酬率(%)」與「截至日」，報酬率以紅漲綠跌上色（沿用 `priceColor` 慣例）。含息模式下，凡以「價格報酬」降級的標的（見下）於摘要與圖例標示「價格報酬」小標籤。
- [ ] **優雅降級**：某標的在區間內無資料（指數表尚未回補、或個股剛加入）→ 該線不畫、圖例仍列出、摘要顯示「無資料」，不回 500；`base` 為 0 或 null 時該標的整條 null（除零防呆）。單一標的抓取逾時／失敗只讓該線消失，不影響其餘（BFF 逐標的 `timeout` + 降級）。全空選取（未選任何股票與基準）則清空圖表、不呼叫下游。

**含息（total return）比較（Task 170）：**

- [ ] **「含息／純價格」切換（預設含息）**：頁面新增報酬口徑切換 `el-radio-group`（`含息報酬` / `純價格報酬`，預設含息）；選擇連動送至 BFF `GET /compare?...&dividend=true|false`，watch 變動重抓。含息模式圖表標題與說明文字改為「含息報酬（股利再投入）」。此為使用者要求「績效比較應含息」的核心：股票高配息者（如金融股、債券 ETF）純看價格會被低估，含息才是公平的累積報酬比較。
- [ ] **個股含息＝股利再投入還原（BFF 計算）**：以既有 `stock_price_history.closePrice`（原始收盤）為價格序列，套 `stock_dividend_history`（既有表，逐筆 `exDividendDate`＋`cashDividend`＋台股 `stockDividend` 配股）做「除息日再投入」重建總報酬序列：維持每股份數 `shares`（起始 1），每逢區間內除息日 `d`，`shares ×= (1 + stockDividend/10)` 後 `shares += shares × cashDividend / close(d)`（台股配股以面額 10 元換算股數乘數；美股僅現金股利）；某日含息值＝`shares(t) × close(t)`，再依既有「同起點=0%」正規化 `(值/base − 1)×100`。股利一律經 business `GET /api/market-data/dividends`（純讀變體，見 design）取得，符合「同義資料走同一 business API、計算集中於 BFF、禁存衍生值」。`exDividendDate` 為 null 的年度彙總列、或除息日晚於今日者略過。
- [ ] **個股含息的資料缺口與降級**：
  - **英股**：`stock_dividend_history` 無英股資料（抓取端未實作），且無法自動辨別累積型（`CSPX.L`，價已內含息）與配息型（`VUSA.L`，價不含息）→ 不臆測，含息模式一律採原始價序列並標 `priceOnly`（顯示「價格報酬」），避免把配息型英股當含息而低估、誤標。
  - **台股／美股查無股利資料者**（資料缺口或本就不配息，如 `AMZN`）：含息模式退化為價格報酬，該 series 標 `priceOnly`，摘要／圖例顯示「價格報酬」小標籤，不誤植 0 息。
  - **例外——已查證累積型台股 ETF**（`PerformanceComparisonBffController.ACCUMULATING_TW_ETFS` 白名單，現含 `00646` 元大S&P500、`006205` 富邦上證180、`00642`/`00642U` 期元大S&P石油、`00865B` 國泰US短期公債）：這些 ETF 收益不發現金、直接累積於淨值（收盤價），故**價格報酬 ≡ 含息報酬**。已查證 FinMind `TaiwanStockDividend`／`TaiwanStockDividendResult` 兩表與 Yahoo `events=div` 皆確認其「本就不配息」（非資料缺口，故無股利可補、亦不得捏造寫入 `stock_dividend_history`）。含息模式下雖查無股利，仍以原始收盤價視為已含息、標 `priceOnly=false`（比照英股累積型 UCITS ETF），**不顯示「價格報酬」小標籤**，避免使用者誤以為報酬被低估。此白名單僅供標籤判斷、由代碼層維護（比照英股 ETF 白名單、`ALLOWED_BENCHMARKS`），新增累積型標的時擴充；非白名單者維持保守標示。
- [ ] **指數含息（能含息的就含息）**：大盤基準含息模式的口徑：
  - **TWSE（台股大盤）**：改讀「發行量加權股價報酬指數」。資料來源 TWSE RWD `afterTrading/MI_INDEX?date=YYYYMMDD&type=IND` 之「報酬指數(臺灣證券交易所)」表列，落庫至 `twse_index_daily_history.close_point_tr`（新欄位），涵蓋約 10 年逐交易日（一次性回補＋每日增量補近 14 日缺口）。含息模式**只用 `closePointTr`**（null 列略過、由前值 forward-fill，**禁止退回價格指數 `closePoint` 混量級**——兩者差約 1.6 倍會造成台階跳空）；TR 覆蓋率 < 90%（尚未回補完成）→ 整段退回價格指數並標 `priceOnly`。價格指數回補（`refreshTwseDaily`）須保留既有 `close_point_tr`，不得覆寫成 null。
  - **SPX（標普500）**：改讀 Yahoo `^SP500TR`（S&P 500 Total Return），比照既有 `fetchUsIndexDaily`（`range=10y` 一次抓）落庫至 `us_index_daily_history`（`indexCode='SP500TR'`）。含息模式讀該序列。
  - **DJI／IXIC／SOX（道瓊／那斯達克綜合／費半）**：免費來源無穩定報酬指數 → 含息模式仍用價格指數 `closePoint`，該 series 標 `priceOnly`，UI 標示「價格報酬」。
  - **純價格模式**：所有指數與個股一律用原始價格／價格指數（即現行行為）。
- [ ] **資料模型變更（需 Liquibase changeset `v1.52.0`）**：`twse_index_daily_history` 新增 nullable 欄位 `close_point_tr`（發行量加權股價報酬指數收盤，與同日價格指數 `close_point` 同列共存、由 TWSE poller 一併抓寫，屬同交易日不同指數之量測事實、非衍生值）；`us_index_daily_history` 不改結構，以新增 `index_code='SP500TR'` 資料列承載 S&P500 報酬指數。個股與 DJI/IXIC/SOX 不需結構變更。

### Requirement 34: 歷年資產 Excel 匯出增強（當前彙總表）與每日排程自動匯出

**User Story:** 作為使用者，我希望在「歷年資產」頁一鍵把我的所有資產匯出成一份 Excel（含一張「當前全資產彙總」總表），並且能設定每日自動匯出到指定目錄，讓我不必每次手動下載也能定期留存資產快照。

**Acceptance Criteria:**

- [ ] **匯出內容含「當前彙總」總表**：既有「匯出 Excel」按鈕（`GET /api/snapshots/export` → `ExcelExportService.exportFull()`）產出的活頁簿，第一張 sheet 改為「當前彙總」——讀該使用者最新一筆 `asset_snapshot`，列出匯出時間、最新快照日期、美元匯率、資產總計、存款總計、股票現值／成本／未實現損益、基金現值／成本／未實現損益、預估年配息、當年度已實現損益；其後沿用既有「每快照一張 sheet（YYYYMMDD）＋已實現損益」。
- [ ] **手動匯出（瀏覽器下載）**：歷年資產頁「匯出 Excel」按鈕維持瀏覽器直接下載 `.xlsx`；owner-scoped（只含自己的資產，經 BFF 帶 `X-User-*` → `ownerFilter`）。
- [ ] **每日排程自動匯出最新資產（per-user）**：每個使用者可在歷年資產頁「排程自動匯出最新資產」設定卡開啟每日排程，設定每日執行時間（時:分）與輸出資料夾，系統於該時間把該使用者的**當前即時資產**匯出成 `.xlsx` 到指定目錄。匯出內容＝最新一筆快照的持股／存款／基金，但**股票以 Redis 即時股價重估**（與 Dashboard 首頁「當前資產」同一權威來源 `StockPriceService.getLiveAssets()`；存款／基金沿用最新快照凍結值），排版比照歷次快照分頁（銀行存款／基金／股票，股票含即時價與美股 USD→TWD 換算、預估配息，末列即時總資產彙總）。`run-now`「立即匯出到目錄」同此內容。手動「匯出 Excel」按鈕（多分頁歷次匯出）維持不變。
- [ ] **輸出路徑（家目錄為根＋相對子路徑）**：容器內基底目錄由環境變數 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）指定，經 docker volume 對映到 host 家目錄（預設 `/Users/steven`）。使用者設定的是「相對子路徑」（相對家目錄根，例如 `input` → host `/Users/steven/input`；空字串＝家目錄根）。後端一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」驗證，拒絕 `..` 跳脫與絕對路徑。
- [ ] **可調時間、檔案總管式資料夾選擇、可手動立即匯出**：設定卡標題「排程自動匯出最新資產」，提供啟用開關、每日時間（`el-time-picker` 時:分）、**輸出資料夾選擇器**（檔案總管式 `el-tree` 懶載入樹狀瀏覽，自家目錄根逐層展開後點選；可另填「新增子資料夾名稱」，寫檔時 `Files.createDirectories` 自動建立）、「立即匯出到目錄」按鈕（`POST run-now` 立即產檔到設定目錄，供驗證），並顯示上次執行時間與結果。
- [ ] **每使用者各自設定（owner-scoped 設定表）**：排程設定存於 `export_schedule_setting`（每 `owner_user_id` 一列、`@Filter(ownerFilter)` 隔離）；GET/PUT/run-now 走 HTTP（BFF→business）自動 scope 到本人；非管理者亦可設定自己的排程（不限 admin）。
- [ ] **排程執行機制與租戶隔離**：以每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`）比對各設定列的時:分與「當日是否已執行」旗標；命中則對該列 owner 手動 `enableFilter("ownerFilter")` 產出只含該 owner 資產的活頁簿再寫檔（背景 cron 無 request context、`ownerFilter` 不自動生效，故明確逐列指定 owner）。服務重啟以 `ApplicationReadyEvent` 補跑當日已到點但未執行者。單一使用者失敗只記 `last_run_status` 與 log、不影響其他使用者。
- [ ] **檔名**：`資產總覽_{使用者ID}_{YYYYMMDD}.xlsx`（檔名含 owner id，避免多使用者共用同一 subpath 時同名互相覆蓋；同一使用者同日覆寫）。
- [ ] **資料夾瀏覽端點（唯讀）**：新增 `GET /api/export-schedule/browse?subpath=` 列出基底（家目錄）下指定子路徑的「子目錄」清單（僅目錄、隱藏 dotfiles、依名稱排序），供前端樹狀選擇器逐層懶載入。同樣以 normalize `startsWith(base)` 驗證防跳脫；此端點僅列目錄名稱、不讀檔案內容、不變更檔案系統，需登入。BFF 對應 `GET /api/bff/asset-history/export-schedule/browse`。
- [ ] **「股票（即時）」分頁增列即時報價與技術指標欄（Task 200）**：「當前即時資產」匯出的「股票（即時）」分頁，於既有欄位（券商／市場／代號／名稱／股數／投資成本／即時價／即時現值／預估配息／交易類型／交易日期）外增列 7 欄，欄序為：`券商／市場／代號／名稱／股數／投資成本／即時價／`**`昨收／漲跌／漲跌幅(%)／`**`即時現值／預估配息／交易類型／交易日期／`**`月線價／季線價／年線價／KD值`**。
  - **昨收／漲跌／漲跌幅(%)**（緊接「即時價」後）：與即時價**同一 Redis 即時報價來源**——取自 `PriceQueryService.LivePrice.previousClose/priceChange/changePercent`，經 `StockPriceService.getLiveAssets()` 由**同一筆** `LivePrice` 帶進 `LiveStockItem`（零額外 Redis 讀取），確保「即時價 − 昨收 = 漲跌」三值同一 tick 一致；漲跌幅為已計算之百分比數值（例 1.23 = 1.23%）。查無即時報價時三欄留白。
  - **月線價／季線價／年線價／KD值**（置於分頁末欄）：統一取自共用權威 `TechnicalIndicatorService.computeAll(code, market)` 的 `FullIndicators{monthlyMa(MA20)／quarterlyMa(MA60)／annualMa(MA240)／k／d}`，與觀察清單／警示同一計算；KD 以單一「KD值」欄呈現為 `K x.xx / D x.xx`。歷史資料不足以撐滿某視窗時該欄留白、不影響其他欄。同一 `(code, market)` 於同分頁多筆持股（不同券商）僅計算一次並以 `code|market` 於單次匯出內快取共用。
  - 此增列同時套用於「立即匯出到目錄」（run-now）與每日排程產檔（皆走 `ExcelExportService.writeLiveAssetsSheet`）。
- [ ] **每檔持股各一張「過去一年股價」分頁（Task 206）**：`資產總覽_{使用者ID}_{YYYYMMDD}.xlsx`（run-now 與每日排程共用的同一份活頁簿）第一張分頁維持「當前即時資產」全資產總表，**第二張起每一檔持股一張分頁、分頁名稱＝股票代號**，內容為該檔過去一年（匯出日回推一年至匯出日）的每日股價，欄序 `日期／開盤價／最高價／最低價／收盤價／成交量`，依日期遞增；日期寫成 `yyyy-MM-dd` 文字（比照油價金價／匯率匯出，避免 Excel 依開啟端時區重新詮釋 date cell 偏移一天）。
  - **資料來源**：一律讀 `stock_price_history`（收盤價權威來源，與技術指標／個股頁曲線同一張表），匯出過程不呼叫外部行情 API；該表保留 10 年歷史，過去一年區間必然涵蓋。
  - **持股清單**：取自與第一張分頁**同一批**最新快照持股（`asset_snapshot.stocks`），以 `(代號, 市場)` 去重——同一檔股票分散多家券商只出一張分頁——順序同總表由上而下。owner 隔離沿用外層（HTTP 走 `TenantFilterAspect`、背景排程走手動 `enableFilter`），故分頁只含該使用者自己持有的股票。
  - **查無資料不靜默略過**：區間內無任何收盤價的個股（如剛買進、或該市場尚未回補）仍產生**只有表頭的空分頁**，明示「有這檔持股、但區間內無資料」，與「這檔不存在」可區分。
  - **分頁名稱衝突**：不同市場出現同一代號時，第二張起以 `代號_市場` 命名；仍衝突則加數字後綴。名稱長度與非法字元以 POI `WorkbookUtil.createSafeSheetName` 收斂（Excel 上限 31 字元、禁 `[]:*?/\`）。

---

### Requirement 35: 每日自動釘定「最新快照」日期為當日並重算資產（讓即時價覆蓋生效）

**User Story:** 作為資產擁有者，即使我一段時間沒有手動新增快照，我也希望「歷年資產」與 Dashboard 的「最新一筆」持續反映今日最新即時股價，而不是停在數日前的凍結收盤。

**背景：** BFF 的 `LiveAssetsOverlay.applyToLatest` 以 Redis 即時價覆蓋歷史「最新一筆」的股票現值，但有 **per-market 基準日閘門**——僅當「最新快照 `snapshotDate` == 該市場時區今日」的市場才覆蓋。若最新快照停在過去日期（久未建檔），三市場皆非今日 → 完全不覆蓋 → 顯示過去日期的凍結收盤、資產失真。故需每日把最新快照日期釘成當日，打開閘門。

**Acceptance Criteria:**

- [ ] **每日排程釘定當日**：每天 00:05（`Asia/Taipei`）系統對**每個使用者（owner）各自的最新一筆 `asset_snapshot`**，若其 `snapshotDate` < 當日，則更新為當日並重算該筆匯總欄位（`total_*`、`estimated_annual_dividend`）。
- [ ] **冪等與唯一鍵防護合一**：最新快照 `snapshotDate` 已 == 當日或為未來日期者一律不動（no-op）；「`snapshotDate` < 當日才更新」同時作為 `(owner_user_id, snapshot_date)` 唯一鍵防護——最新快照為該 owner 日期最大值，改為當日必不與既有列相撞，且不把未來快照往回搬。
- [ ] **只重算被釘定的最新一筆**：僅對被推進日期的最新快照呼叫 `recalcTotals`（用快照凍結的 `currentValue`，與手動 `POST /api/snapshots/recalc-totals` 對同一筆結果逐欄一致），**不重算歷史快照**（歷史為 point-in-time 凍結紀錄；且 `recalcAllDividends` 會重抓 NAV／配息屬外部副作用，不適合每日排程）。
- [ ] **多租戶隔離**：背景排程無 request context、`ownerFilter` 不自動生效，故以帶 `owner_user_id` 條件的 query 逐 owner 取其最新快照處理；單一 owner 失敗只記 log、不影響其他 owner（比照 Requirement 34 排程自動匯出）。
- [ ] **重啟自癒**：服務重啟以 `ApplicationReadyEvent` 補跑當日（roll 冪等，多跑無害）；純後端排程，前端／BFF／DB schema 皆不需變更（僅 UPDATE 既有列）。

### Requirement 36: 左側選單新增「公開資訊」分組 ＋ 排程列表頁

**User Story:** 作為使用者，我希望把與個人資產無關、屬於系統共通／市場公開性質的資訊集中在一個「公開資訊」選單分組下，方便查找；並希望能在一個「排程列表」頁面總覽系統所有自動排程（何時執行、做什麼、屬哪個服務），了解資料是如何被自動更新的。

**背景：** 「交易日曆」與「台幣兌美元」原本平鋪在左側選單頂層，兩者皆為市場公開資料（非個人化）。新增一個「公開資訊」`el-sub-menu` 分組把它們收納，並新增第三個子項「排程列表」。排程遍佈 `business-services`（12 個 `@Scheduled` 方法）與 `external-materials-service`（24 個 `@Scheduled` 方法；實際 25 個標註，`TwClosurePoller` 一法兩標併為一筆）兩個服務；此頁為唯讀資訊展示，資料由該頁專屬 BFF 提供一份**人工維護的排程清單**（含中文名稱、說明、白話執行時機、cron、時區、所屬服務），不做跨服務反射探索。**原「crons 皆為編譯期常數」之前提已不成立**：部分排程改為「每分鐘 tick ＋ 比對 DB 可設定時點」（`NewsPoller` 依 `crawler_schedule`、`MarketAnalysisScheduler` 依 `market_analysis_send_time`），此類於清單以「動態：依『X』頁設定」表示，不寫死時間。

**Acceptance Criteria:**

- [ ] **選單分組**：左側選單新增「公開資訊」`el-sub-menu`（icon `InfoFilled`），內含三個子項：「交易日曆」、「台幣兌美元」、「排程列表」；「交易日曆」「台幣兌美元」原本的頂層項目移入此分組（路由路徑 `/trading-calendar`、`/exchange-rate` 不變，既有連結不失效）。
- [ ] **排程列表頁**：新增 `/schedule-list` 路由與 `ScheduleListView`，以表格總覽所有排程；每筆顯示所屬服務、分類、名稱、說明、執行時機（白話）、cron 表達式、時區；依服務／分類分組呈現，前端只 render、不計算。
- [ ] **一頁一 BFF**：新增該頁專屬 `SchedulePublicBffController`（`GET /api/bff/schedule-list`），回傳排程清單（不可變 record DTO）。此清單為系統基礎設施資訊、非使用者可管理的業務分類，故以程式碼內建靜態清單提供（不入 DB、不設管理端點）；**新增／調整任何 `@Scheduled` 時須同步更新此清單**（避免與實際 cron 漂移）。
- [ ] **清單涵蓋率（防漏列）**：`JOBS` 須涵蓋兩服務**全部** `@Scheduled` 方法，筆數與實際方法數一致（`TwClosurePoller` 一法兩標併為一筆）；清單與 javadoc 所載之服務別數量須與實際清點結果相符。
- [ ] **動態排程標示（防寫死漂移）**：cron 為編譯期常數者照列其 cron；**已改為 DB 驅動、由使用者於頁面增減時點的動態排程，一律標「動態：依『X』頁設定（預設 …）」與「動態（表名）」，不得寫死單一時間**（寫死即漂移，如 Task 191 把分析改為每分鐘 tick 後清單仍顯示 08:45）。每分鐘 tick 但時點屬 per-user 私人設定者（`ExportScheduleService`／`TradingCalendarExportScheduleService`），照列其 `每分鐘`／`0 * * * * *` 實際 cron，並於說明點出係比對各使用者設定。
- [ ] **權限**：本頁為已登入者皆可讀的公開資訊（`GET` 落 BFF `anyExchange().authenticated()`），不需 ADMIN；不因分組改動任何既有頁面的授權。
- [ ] **契約穩定**：`/trading-calendar`、`/exchange-rate` 及其 BFF 端點完全不變，僅選單層級位置調整。

---

### Requirement 37: 交易日曆匯出到指定路徑（JSON／Excel）

**User Story:** 作為使用者，我希望在「交易日曆」頁把某一年度的整年交易日曆（台／美／英三市每日是否為交易日、各市場國定假日）匯出成一份檔案，並能自行指定輸出資料夾與檔案格式（JSON 或 Excel），方便留存或提供給其他系統使用。

**背景：** 「交易日曆」頁的資料（休市日、交易日）為市場公開資料，權威來源是 business-services 的 `MarketDataService`（台股假日經 external-materials-service proxy、美股／英股純計算）。現有「輸出到指定路徑」的安全模型已在 Requirement 34 建立（容器基底目錄 `EXPORT_OUTPUT_DIR` ＋ 使用者相對子路徑、normalize `startsWith(base)` 防跳脫、檔案總管式資料夾選擇器）；本需求沿用同一輸出路徑模型，但為**手動一次性匯出**（非排程、不落 DB 設定），且**新增格式選項**。

**Acceptance Criteria:**

- [ ] **匯出內容（整年交易日曆）**：使用者指定年度後，系統以 `MarketDataService` 為單一權威來源產出該年度 1/1～12/31 每一天的：日期、星期、台股／美股／英股是否為交易日（平日且非該市場國定假日，經 `isTwTradingDay`／`isUsTradingDay`／`isUkTradingDay`）、及該日台股／美股／英股國定假日名稱（無則空）；並附三市各自的假日對照表與交易日天數統計。日曆判斷邏輯與頁面日曆格（`TradingCalendarView` 的日格模型 `tw/us/uk` + `twHoliday/usHoliday/ukHoliday`）一致，不在前端重算。
- [ ] **格式可選 JSON／Excel**：`format=json` 產出結構化 JSON（UTF-8、pretty-print），`format=excel` 產出 `.xlsx`（Apache POI，單一工作表：日期／星期／台股交易日／美股交易日／英股交易日／台股假日／美股假日／英股假日，逐日一列，標題列粗體）。格式非 `json`／`excel` 一律回 400。
- [ ] **輸出路徑（沿用 Requirement 34 家目錄為根＋相對子路徑安全模型）**：輸出目錄 = 容器基底 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`，docker volume 對映 host `/Users/steven`）resolve 使用者所選相對子路徑；後端一律以 normalize 後 `startsWith(base)` 驗證仍在基底內，拒絕 `..` 與絕對路徑跳脫。子資料夾不存在時 `Files.createDirectories` 自動建立。
- [ ] **檔案總管式資料夾選擇器**：交易日曆頁「匯出」對話框提供年度輸入、格式選擇（JSON／Excel）、及**輸出資料夾選擇器**（`el-tree` 懶載入樹狀瀏覽，自家目錄根逐層展開後點選；可另填「新增子資料夾名稱」，寫檔時自動建立），行為比照歷年資產頁的選擇器。此資料夾瀏覽與寫檔屬檔案系統基礎設施操作，非個人化資料，不做 owner 過濾。
- [ ] **檔名與冪等**：`交易日曆_{年度}.{json|xlsx}`；同年度重複匯出以原子 rename（先寫 `*.tmp` 再 `ATOMIC_MOVE`）就地覆寫，避免部分寫入的殘檔。回應含實際落點絕對路徑、檔案位元組數、格式、年度、天數，供前端顯示。
- [ ] **一頁一 BFF**：交易日曆頁走自己的 BFF——`POST /api/bff/trading-calendar/export?year=&format=&subpath=`（觸發匯出）與 `GET /api/bff/trading-calendar/export/browse?subpath=`（唯讀列子目錄），分別 passthrough 至 business `POST /api/trading-calendar-export/run`、`GET /api/trading-calendar-export/browse`；不直接呼叫其他頁面的 BFF。
- [ ] **權限**：本頁為已登入者皆可讀寫的公開資訊操作（落 BFF `anyExchange().authenticated()`），不需 ADMIN；不變更既有交易日曆查詢端點（`GET /api/bff/trading-calendar`、`/market-status`）契約。

**排程自動匯出（每日指定時間，per-user；Task 190）：**

- [ ] **即時產生＋每日排程並存**：既有「匯出到目錄」為即時一次性產生（保留不變）；另新增「每日排程自動匯出」——使用者可設定啟用開關與每日執行時間（時:分），系統於該時間自動以指定格式（json／excel）與資料夾匯出**當前年度**整年交易日曆，不必每次手動點按。排程與即時共用同一份格式／輸出資料夾設定（對話框上方選定值）。
- [ ] **當前年度自動滾動**：排程每日匯出「執行當下的西元年」交易日曆（`交易日曆_{當前年}.{ext}`），使檔案隨年度更迭與台股臨時休市（颱風假）更新自動保持最新；不釘死於某固定年。
- [ ] **每使用者各自設定（owner-scoped 設定表）**：排程設定存於 `trading_calendar_export_schedule`（每 `owner_user_id` 一列、`@Filter(ownerFilter)` 隔離），欄位含啟用／時分／格式／輸出子路徑／上次執行時間與結果；GET/PUT 走 HTTP（BFF→business）由 `TenantFilterAspect` 自動 scope 到本人；非管理者亦可設定自己的排程。
- [ ] **排程執行機制與自癒（比照 Requirement 34）**：每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`）比對各列時:分與「當日已執行」旗標，命中則產檔；服務重啟以 `ApplicationReadyEvent` 補跑當日已到點未執行者；單一使用者失敗只記 `last_run_status`＋log、不影響他人。因交易日曆為**全域資料**，背景 tick 產檔時**無需 owner 資料過濾**（與 Requirement 34 排程需 `enableFilter` 縮資產不同），僅設定表為 owner-scoped。
- [ ] **排程設定端點與 BFF**：business `GET/PUT /api/trading-calendar-export/schedule`（取／upsert 當前使用者設定）；BFF `GET/PUT /api/bff/trading-calendar/export/schedule` passthrough。前端匯出對話框加「啟用每日排程＋每日執行時間＋儲存排程」區塊並顯示上次執行狀態。
- [ ] **無跳脫、格式白名單**：排程設定的 `output_subpath` 與 `format` 沿用即時匯出的同一驗證（normalize `startsWith(base)` 防跳脫、format 僅 json／excel）。

---

### Requirement 38: 「公開資訊」新增「爬蟲資訊查詢」頁（依日期查爬回資料 ＋ 頁面可設定多個爬蟲執行時間與輸出檔案路徑）

**User Story:** 作為使用者，我希望在「公開資訊」分組下有一個「爬蟲資訊查詢」頁，能指定日期查看公開資訊爬蟲（`NewsPoller`）那天爬回來的資料（新聞、三大法人、大盤成交、台幣兌美元快照、美股主要指數快照、韓股快照），以核對爬蟲是否正常運作、當天抓了哪些內容；同時我希望能直接在這個頁面上設定爬蟲的執行時間，而且可以設定多個時間點；**我也希望能在同一頁指定爬蟲產生的公開資訊 JSON 檔要放到哪個資料夾**（三者都不必改程式或改 docker volume 重新部署）。

**背景：** 公開資訊爬蟲的統一輸出表是 `news_headline`（由 `external-materials-service` 的 `NewsPoller` 以 `JdbcTemplate` 直寫，全域參考、無 owner），每列同時有「資料日期」`published_at`（新聞發布日／交易日）與「爬取入庫時間」`fetched_at`。原本 `NewsPoller` 的執行時間以編譯期常數寫死（三個 cron `0 20 8`／`0 30 11`／`0 0 18`，Asia/Taipei，見 Task 184／188），每次調時間都要改程式重新部署。本需求（1）新增查詢頁讀 `news_headline`，（2）把 `NewsPoller` 的執行時間改為 DB 驅動、可於頁面增減多個時間點，（3）**把 `NewsPoller` 每輪產出的公開資訊 JSON（Task 177）輸出目錄也改為 DB 驅動、可於同頁設定**——該路徑原本同樣寫死（`news-scraper.export-dir` 預設容器內 `/srpp-input`，只能靠改 `docker-compose.yml` 的 volume 換目的地）。

**Acceptance Criteria:**

- [ ] **選單與路由**：「公開資訊」`el-sub-menu` 新增第四個子項「爬蟲資訊查詢」；新增 `/crawler-data` 路由與 `CrawlerDataView`；不影響既有三個子項與其路由／BFF 契約。
- [ ] **依日期查詢**：可選一個日期查該日爬回的 `news_headline` 資料；日期語意可切換「依爬取時間 `fetched_at`」與「依資料日期 `published_at`」兩種（皆以 Asia/Taipei 當日 00:00–翌日 00:00 為區間）；可再依 `category`（news／twse-institutional／twse-turnover／fx／us-market／kr-market）過濾；結果表格顯示資料日期、爬取時間、類別、來源、地區、標題（連結）、摘要，越新在前。前端只 render、不計算。
- [ ] **一頁一 BFF**：新增該頁專屬 `CrawlerDataBffController`；查詢走 `GET /api/bff/crawler-data?date=&dateField=&category=` → business `GET /api/news-headlines`（同義欄位、同一 business API，讀同一份 `news_headline`，與今日股市分析同源）。
- [ ] **爬蟲執行時間設定（多時間點）**：頁面提供 `NewsPoller` 執行時間清單（HH:mm ＋ 啟用開關），可新增／刪除多個時間點並儲存；設定存入新表 `crawler_schedule`（`crawler_key='news-poller'`，一列一時間點，全域設定無 owner）。`GET /api/bff/crawler-data/schedule`、`PUT /api/bff/crawler-data/schedule` → business `GET/PUT /api/crawler-schedule?crawler=news-poller`。
- [ ] **動態排程生效**：`NewsPoller` 移除寫死 cron，改為每分鐘 ticker（`@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")`）讀 `crawler_schedule` 中該爬蟲已啟用的時間點，命中當前時分即執行一次；poll 本身以 dedupe_key upsert 具冪等，重跑無害；保留開機 warmup。DB 讀取例外時 fallback 至預設 08:20／11:30／18:00，避免爬蟲靜默停擺。設定變更免重啟即生效（下一分鐘 ticker 讀到新值）。
- [ ] **預設值不變行為**：`crawler_schedule` 由 Liquibase seed 預設 `08:20 / 11:30 / 18:00`（＝改為 DB 驅動前寫死的 cron，Task 184／188），全新部署行為與現況一致；**早上 08:20 那次仍早於 08:45 今日股市分析**，餵料時序不變。
- [ ] **輸出檔案路徑設定（Task 212）**：頁面提供「爬蟲輸出檔案設定」卡，可設定 `NewsPoller` 每輪產出的公開資訊 JSON（`public_info_<yyyy-MM-dd>.json`，Task 177）要寫入哪個資料夾並儲存；設定存入新表 `crawler_export_setting`（`crawler_key='news-poller'` UNIQUE、一爬蟲一列，全域設定無 owner，比照 `crawler_schedule`）。`GET /api/bff/crawler-data/export-path`、`PUT /api/bff/crawler-data/export-path` → business `GET/PUT /api/crawler-export-path?crawler=news-poller`。**檔名格式不可設定**（維持 `public_info_<日期>.json`，SRPP 依此檔名取用），本需求只開放目錄。
- [ ] **路徑模型沿用 Requirement 34／39／41（家目錄為根＋相對子路徑）**：使用者設定的是**相對子路徑**，實際寫入 = 容器內基底 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）resolve 該子路徑；基底經 docker volume 對映 host 家目錄（`${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}`）。**`external-materials-service` 需與 `business-services` 掛同一個 host 目錄到同一個容器路徑**，否則前端選得到的資料夾與爬蟲實際寫得到的資料夾會不一致。後端（business 寫入時驗證＋ext 寫檔前再驗一次，縱深防禦）一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」擋 `..` 跳脫與絕對路徑（回 400）；寫檔時 `Files.createDirectories` 自動建立缺少的目錄。
- [ ] **預設值不變行為（輸出路徑）**：`crawler_export_setting` 由 Liquibase seed 預設子路徑 `Project/SRPP/data/input` → host `/Users/steven/Project/SRPP/data/input`，**與改為 DB 驅動前 `/srpp-input` volume 所對映的目的地完全相同**；DB 讀取例外或值為空時 ext 亦 fallback 至同一預設，避免爬蟲改動後靜默把檔案寫到別處或不寫。
- [ ] **資料夾選擇器沿用同一支 business API**：設定卡提供與其他匯出頁相同的檔案總管式 `el-tree` 懶載入資料夾選擇器。目錄列舉**不新增 business 端點**，沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`（語意相同＝列出基底下子目錄，依 CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）；本頁僅於 BFF 新增自己的 `GET /api/bff/crawler-data/export-path/browse` passthrough（依「一個前端頁面一個 BFF」）。
- [ ] **動態生效（免重啟）**：`NewsPoller` 於**每一輪抓取寫檔時**讀 `crawler_export_setting` 現值決定目錄，不快取於欄位、不需重啟容器；設定變更後的下一輪（含 warmup）即寫入新目錄。
- [ ] **權限**：查詢與讀取排程／輸出路徑為已登入者皆可（`authenticated`）；**修改排程與輸出路徑的 `PUT` 均限 ADMIN**（屬系統設定變更，且輸出路徑會決定服務往主機檔案系統寫入的位置）；非 ADMIN 前端隱藏／停用儲存並提示。
- [ ] **排程清單同步**：`NewsPoller` 由固定 cron 改動態後，同步更新 `SchedulePublicBffController` 靜態清單中該筆（cron 標示為「動態：依『爬蟲資訊查詢』頁設定，預設 08:20 / 11:30 / 18:00」），避免與實際排程漂移。

---

### Requirement 39: 已實現損益 Excel 匯出到指定目錄與每日排程自動匯出

**User Story:** 作為使用者，我希望在「已實現損益」頁除了手動下載 Excel 之外，還能指定輸出資料夾並設定每日自動匯出時間，讓我的已實現損益明細定期留存到本機目錄，不必每次手動點按下載。

**Acceptance Criteria:**

- [ ] **手動匯出（瀏覽器下載）維持不變**：既有「匯出 Excel」按鈕（`GET /api/bff/realized-gain/export` → business `GET /api/realized-gains/export` → `ExcelExportService.exportRealizedGains()`）仍為瀏覽器直接下載 `.xlsx`，單張「已實現損益」sheet、14 欄、涵蓋**所有年度**（含 `年度` 欄），owner-scoped。本需求為新增排程能力，不改動手動下載行為與產出內容。
- [ ] **排程／立即匯出的內容＝手動匯出的同一份活頁簿**：排程與「立即匯出到目錄」產出的檔案內容，與手動下載完全一致（同一 `writeRealizedGainsSheet()`、同樣涵蓋全部年度），不因觸發途徑而不同；避免同義資料在不同入口產生不一致（CLAUDE.md「同義欄位、同一 business service API」）。
- [ ] **每日排程自動匯出（per-user，每日單一時間）**：使用者可在已實現損益頁「排程自動匯出」設定卡開啟每日排程，設定每日執行時間（時:分）與輸出資料夾，系統於該時間把該使用者的已實現損益匯出成 `.xlsx` 到指定目錄。每日固定一個時間（比照 Requirement 34，非多時段、不設交易日閘門——已實現損益僅於有成交時異動，每日留存即可）。
- [ ] **輸出路徑（家目錄為根＋相對子路徑）**：沿用 Requirement 34 的路徑模型——容器內基底目錄由 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）指定，經 docker volume 對映到 host 家目錄；使用者設定的是相對子路徑（例 `input` → host `/Users/steven/input`；空字串＝家目錄根，後端正規化為預設 `input`）。後端一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」驗證，拒絕 `..` 跳脫與絕對路徑；寫檔時 `Files.createDirectories` 自動建立缺少的目錄。
- [ ] **資料夾選擇器沿用同一支 business API**：設定卡提供檔案總管式 `el-tree` 懶載入資料夾選擇器，逐層瀏覽家目錄下的子目錄。目錄列舉**不新增 business 端點**，直接沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`（語意相同＝列出基底下子目錄，依 CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）；本頁僅在 BFF 新增自己的路由 `GET /api/bff/realized-gain/export/browse` passthrough 至該端點（依「一個前端頁面一個 BFF」）。
- [ ] **可手動立即匯出（驗證用）**：設定卡提供「立即匯出到目錄」按鈕（`POST /api/bff/realized-gain/export/run-now`），立即產檔到設定目錄並回傳實際落點路徑與檔案大小，供使用者驗證路徑正確；此操作**不動當日排程 guard**（不影響當日排程仍會於設定時間執行）。
- [ ] **每使用者各自設定（owner-scoped 設定表）**：排程設定存於新表 `realized_gain_export_schedule`（每 `owner_user_id` 一列 UNIQUE、`@Filter(ownerFilter)` 隔離），欄位含啟用／時分／輸出子路徑／上次執行日期（當日 guard）／上次執行時間與結果；GET/PUT/run-now 走 HTTP（BFF→business）由 `TenantFilterAspect` 自動 scope 到本人；非管理者亦可設定自己的排程（不限 admin）。
- [ ] **背景排程必須逐列 `enableFilter`（租戶隔離關鍵）**：`RealizedGain` entity 帶 `@Filter(ownerFilter)`，而背景 cron 無 request context、`TenantFilterAspect` 不啟用 → `findAll()` 會讀到**全部使用者**的損益。故排程產檔一律走新增的 `ExcelExportService.exportRealizedGainsForOwner(ownerId)`，在該 session 手動 `enableFilter("ownerFilter")` 縮到該列 owner，確保各使用者檔案只含自己的資料。（此點與 Requirement 37 交易日曆不同——交易日曆為全域資料、背景產檔無需資料過濾。）
- [ ] **排程執行機制與自癒（比照 Requirement 34／37）**：每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`），以 `now >= 設定時分` ＋ `last_run_date` 當日 guard 判斷（非「分鐘精確相等」，避免排程執行緒被長工作卡住跨分鐘導致整日靜默漏跑）；服務重啟以 `ApplicationReadyEvent` 補跑當日已到點未執行者；`AtomicBoolean` 防重入；單一使用者失敗只記 `last_run_status`＋log、不影響其他使用者（成功或失敗都設當日 guard，避免整天每分鐘重試）。
- [ ] **檔名**：`已實現損益_{使用者ID}_{YYYYMMDD}.xlsx`（檔名含 owner id，避免多使用者共用同一 subpath 時同名互相覆蓋；同一使用者同日覆寫）。
- [ ] **排程列表頁需登錄**：新排程須在「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）補上對應項目，避免該頁與實際排程漂移（Task 188 即為修正此類漂移而生）。

### Requirement 40: 公開資訊「油價金價」十年歷史曲線與 Excel 匯出

**User Story:** 作為使用者，我希望在「公開資訊」下有一個「油價金價」頁面，看到最近十年每日的國際油價與金價走勢曲線，並能指定時間區間把油價、金價一起匯出成一個 Excel 檔、自行選擇存檔位置，讓我在評估原物料與抗通膨資產時有長期價格參照。

**Acceptance Criteria:**

- [ ] **標的（國際盤、美元計價）**：收集三條每日序列——`WTI` 西德州原油（Yahoo `CL=F`）、`BRENT` 布蘭特原油（`BZ=F`）、`GOLD` COMEX 黃金（`GC=F`），皆為美元計價（原油 USD/桶、黃金 USD/盎司）。選國際盤而非中油零售油價／台銀黃金存摺，因後兩者公開歷史多半不足十年，無法滿足「最近 10 年每日」。
- [ ] **十年每日歷史入庫**：每個標的保存最近 10 年的每日收盤價於新表 `commodity_price_history`（`commodity_code` + `price_date` UNIQUE）。首次啟動時自動回補滿 10 年（比照 Requirement 7 的 `HistoricalBackfillService.startupBackfill`），其後每日增量補；超過 10 年的資料由既有 `purgeOldHistory` 一併清理，維持固定十年視窗。
- [ ] **只存收盤價（不存衍生值）**：本表僅存 `close_price` 原始收盤價。漲跌、漲跌幅、區間最高／最低／平均等一律由前端或匯出時即時計算，不入庫（CLAUDE.md「禁止存入可計算得出的衍生值」）。
- [ ] **抓取走 external-materials-service**：價格抓取一律由 `external-materials-service` 負責（新增 `CommodityFetchClient` + `CommodityPricePoller`），business-services 不直連外部行情 API，僅透過 `/internal/*` 觸發回補並以 JPA 讀取（CLAUDE.md 資料來源規範）。Yahoo chart API 以 curl 子程序＋短 UA（`Mozilla/5.0`）呼叫，避開 Yahoo 對 Java HttpClient HTTP/2 fingerprint 的封鎖（同 `YahooFxFetchClient` 慣例）。
- [ ] **每日更新排程**：`@Scheduled` 於台北時間每交易日 06:30 增量補前一日收盤（紐約收盤 = 台北隔日凌晨），失敗只記 log 不中斷其他標的；新排程須登錄「公開資訊 → 排程列表」（Requirement 36 `SchedulePublicBffController.JOBS`），避免該頁與實際排程漂移。
- [ ] **曲線圖（雙 Y 軸）**：頁面以 ECharts 折線圖同時呈現三條序列。油價（數量級約 20–130）與金價（約 1000–5600）量級差距過大，故採**雙 Y 軸**——左軸油價（WTI／Brent）、右軸金價，否則油價曲線會被壓成貼底直線。提供區間快切（1M／3M／6M／1Y／3Y／5Y／10Y）與 dataZoom 縮放，區間切換為前端記憶體切片、不重打 API。
- [ ] **KPI 與序列開關**：頁面上方顯示三個標的的最新價、對前一交易日漲跌與漲跌幅（即時由序列末兩點計算）；圖例可個別開關序列，單看油或單看金。
- [ ] **匯出：可指定時間區間**：匯出對話框可選起訖日期（預設＝目前圖表所選區間），後端只輸出該區間內的資料列。日期參數在後端驗證（`start` 不得晚於 `end`，格式不合或跳脫則回 400）。
- [ ] **匯出：油價金價同一個檔**：產出單一 `.xlsx`，單張工作表，欄位為 `日期／WTI原油(USD/桶)／布蘭特原油(USD/桶)／黃金(USD/盎司)`——以日期為軸做三序列 **outer join**，某標的當日無報價時該格留空（不補前值、不捏造），確保三個市場交易日不完全重疊時仍逐日對齊。
- [ ] **匯出：可指定存檔目錄**：前端以 File System Access API（`showSaveFilePicker`）開啟系統「另存新檔」對話框，讓使用者自選資料夾與檔名；不支援該 API 的瀏覽器自動退回一般 blob 下載（既有 anchor-click 慣例），功能不中斷。此處刻意**不沿用** Requirement 34／37／39 的「後端寫入容器目錄＋`browse` 目錄樹」模型——本頁為一次性手動匯出、無排程需求，且瀏覽器端對話框才是使用者實際的本機目錄，後端路徑是容器內路徑。
- [ ] **檔名預設**：`油價金價_{起日}_{訖日}.xlsx`（例 `油價金價_20160718_20260717.xlsx`），使用者可在另存對話框中改名。
- [ ] **全域公開資料、不做 owner 過濾**：油金價為全域公開行情（同交易日曆），`commodity_price_history` 不帶 `owner_user_id`、不套 `@Filter(ownerFilter)`，所有登入使用者看到同一份資料。
- [ ] **一頁一 BFF**：前端只呼叫 `/api/bff/commodity-price/*`（新增 `CommodityPriceBffController`），不直接呼叫 business `/api/market-data/*`（CLAUDE.md BFF 規範）。外部來源失敗時 BFF 降級回空序列而非 5xx，頁面顯示「查無資料」而不是整頁錯誤。

### Requirement 41: 油價金價 Excel 排程自動匯出到指定目錄

**User Story:** 作為使用者，我希望「油價金價」頁除了手動另存之外，還能指定輸出資料夾並設定每日自動匯出時間，讓油金價歷史定期留存到本機目錄，不必每次手動點按匯出。

**Acceptance Criteria:**

- [ ] **手動匯出（瀏覽器另存）維持不變**：Requirement 40 既有的「匯出 Excel」按鈕（`showSaveFilePicker` 另存、不支援則退回一般下載、可選時間區間）行為與產出完全不變。本需求為新增排程能力，兩種匯出並存——手動另存適合臨時取檔到任意位置，排程適合固定留存。
- [ ] **排程／立即匯出的內容＝手動匯出的同一份活頁簿**：排程與「立即匯出到目錄」產出的檔案，與手動匯出走同一支 `ExcelExportService.exportCommodityPrices(start, end)`（同樣的 outer join、同樣四欄），不因觸發途徑而不同（CLAUDE.md「同義欄位、同一 business service API」）。
- [ ] **每日排程自動匯出（per-user，每日單一時間）**：使用者可在油價金價頁「排程自動匯出」設定卡開啟每日排程，設定每日執行時間（時:分）與輸出資料夾，系統於該時間匯出 `.xlsx` 到指定目錄。每日固定一個時間（比照 Requirement 34／39）。
- [ ] **可設定匯出時間範圍**：排程設定含「匯出範圍」（近 1 個月／3 個月／6 個月／1 年／3 年／5 年／全部十年，預設全部十年），每次執行以「執行當日往前推該範圍」計算起訖日期，讓留存檔案隨時間滾動而非固定區間。範圍以月數存於 `range_months`，「全部十年」＝ `120`（`end.minusMonths(120)` 與 `minusYears(10)` 等價）；後端另接受 `NULL` 同義為全部十年，以相容從未儲存過設定的列。前端不以 `null` 表示，因 Element Plus `el-select` 會把 `null` 當 empty value 而顯示 placeholder，使用者無法分辨「已選全部十年」與「尚未選擇」。
- [ ] **輸出路徑（家目錄為根＋相對子路徑）**：沿用 Requirement 34／37／39 的路徑模型——容器內基底目錄由 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）指定，經 docker volume 對映到 host 家目錄；使用者設定的是相對子路徑。後端一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」驗證，拒絕 `..` 跳脫與絕對路徑；寫檔時 `Files.createDirectories` 自動建立缺少的目錄。
- [ ] **資料夾選擇器沿用同一支 business API**：設定卡提供檔案總管式 `el-tree` 懶載入資料夾選擇器。目錄列舉**不新增 business 端點**，沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`（語意相同＝列出基底下子目錄）；本頁僅在 BFF 新增自己的路由 `GET /api/bff/commodity-price/export/browse` passthrough（依「一個前端頁面一個 BFF」）。
- [ ] **可手動立即匯出（驗證用）**：設定卡提供「立即匯出到目錄」按鈕（`POST /api/bff/commodity-price/export/run-now`），立即產檔到設定目錄並回傳實際落點路徑與檔案大小，供使用者驗證路徑正確；此操作**不動當日排程 guard**。
- [ ] **每使用者各自設定（owner-scoped 設定表）**：排程設定存於新表 `commodity_export_schedule`（每 `owner_user_id` 一列 UNIQUE、`@Filter(ownerFilter)` 隔離），欄位含啟用／時分／輸出子路徑／匯出範圍／上次執行日期（當日 guard）／上次執行時間與結果。
- [ ] **背景產檔不需 `enableFilter`（與 Requirement 39 的關鍵差異）**：油金價為**全域公開行情**（`commodity_price_history` 無 `owner_user_id`、無 `@Filter`），故背景 cron 直接呼叫 `exportCommodityPrices(start, end)` 即可，不需要 `exportXxxForOwner(ownerId)` 變體。此點與 Requirement 39（已實現損益為 per-user 資料、不過濾會外洩他人資料）相反，與 Requirement 37（交易日曆全域資料）相同——**排程設定 per-user，但資料本身全域**。
- [ ] **排程執行機制與自癒（比照 Requirement 34／37／39）**：每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`），以 `now >= 設定時分` ＋ `last_run_date` 當日 guard 判斷（非「分鐘精確相等」，避免排程執行緒被長工作卡住跨分鐘導致整日靜默漏跑）；服務重啟以 `ApplicationReadyEvent` 補跑當日已到點未執行者；`AtomicBoolean` 防重入；單一使用者失敗只記 `last_run_status`＋log、不影響其他使用者（成功或失敗都設當日 guard，避免整天每分鐘重試）。
- [ ] **寫檔採 tmp ＋ atomic move**：比照 Requirement 37 `TradingCalendarExportService.writeAtomically`，先寫 `.tmp` 再 `ATOMIC_MOVE`（不支援時退 `REPLACE_EXISTING`），避免覆寫既有檔時因中途失敗留下半截殘檔。
- [ ] **檔名**：`油價金價_{使用者ID}_{YYYYMMDD}.xlsx`。檔名含 owner id 的原因：資料雖為全域，但各使用者可設不同「匯出範圍」，同日產出內容不同；若多使用者設定同一 subpath，不帶 id 會互相覆蓋成非預期區間。
- [ ] **排程列表頁需登錄**：新排程須在「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）補上對應項目，避免該頁與實際排程漂移。

---

### Requirement 42: 台幣兌美元匯率 Excel 匯出與排程自動匯出到指定目錄

**User Story:** 作為使用者，我希望「台幣兌美元」頁也能像油價金價一樣，指定時間區間把匯率歷史匯出成 Excel，並可設定每日自動匯出到指定資料夾，讓匯率歷史定期留存到本機目錄。

**Acceptance Criteria:**

- [ ] **手動匯出（指定時間區間、瀏覽器另存）**：匯率頁圖表工具列（區間按鈕與「回補資料」旁）新增「匯出 Excel」按鈕，開啟對話框可指定起訖日期，預設帶入目前圖表所選區間。按下匯出優先開啟系統「另存新檔」對話框（`showSaveFilePicker`）供自選資料夾與檔名；瀏覽器不支援時退回一般下載。使用者按取消不顯示成功訊息。檔名 `台幣兌美元_{起}_{迄}.xlsx`（`YYYYMMDD`）。
- [ ] **匯出內容**：單一 Excel 檔、單一工作表「台幣兌美元」，欄位為 **日期／即期買入／即期賣出／中間價**，依日期遞增排列。買入或賣出當日無牌告時該格留空，不補前值、不捏造。中間價為 `ExchangeRateHistory.getMidRate()` 的計算值（`(買入+賣出)/2`，四捨五入至小數 4 位；單邊為 null 時取另一邊）——`mid_rate` 已於 v1.9.4 移除實體欄位（完整正規化：不存可由其他欄位算出的衍生值），故匯出時由 entity 的 `@Transient` getter 計算，**不可**改回 SQL 選取或排序該欄。
- [ ] **日期寫為文字**：比照 Requirement 40，日期欄以 ISO 文字寫入而非 Excel date cell，避免開啟端依時區重新詮釋而偏移一天。
- [ ] **排程／立即匯出的內容＝手動匯出的同一份活頁簿**：排程與「立即匯出到目錄」產出的檔案，與手動匯出走同一支 `ExcelExportService.exportExchangeRates(currency, start, end)`，不因觸發途徑而不同（CLAUDE.md「同義欄位、同一 business service API」）。
- [ ] **每日排程自動匯出（per-user，每日單一時間）**：匯率頁新增「排程自動匯出」設定卡，可開啟每日排程並設定執行時間（時:分）與輸出資料夾，系統於該時間匯出 `.xlsx` 到指定目錄。每日固定一個時間（比照 Requirement 34／39／41）。
- [ ] **可設定匯出時間範圍**：排程設定含「匯出範圍」（近 1 個月／3 個月／6 個月／1 年／3 年／5 年／全部十年，預設全部十年），每次執行以「執行當日往前推該範圍」計算起訖日期，讓留存檔案隨時間滾動而非固定區間。範圍以月數存於 `range_months`，「全部十年」＝ `120`；後端另接受 `NULL` 同義為全部十年，以相容從未儲存過設定的列。前端不以 `null` 表示（Element Plus `el-select` 會把 `null` 當 empty value 而顯示 placeholder，使用者無法分辨「已選全部十年」與「尚未選擇」）。
- [ ] **輸出路徑（家目錄為根＋相對子路徑）**：沿用 Requirement 34／37／39／41 的路徑模型——容器內基底目錄由 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）指定，經 docker volume 對映到 host 家目錄；使用者設定的是相對子路徑。後端一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」驗證，拒絕 `..` 跳脫與絕對路徑；寫檔時 `Files.createDirectories` 自動建立缺少的目錄。
- [ ] **資料夾選擇器沿用同一支 business API**：設定卡提供檔案總管式 `el-tree` 懶載入資料夾選擇器。目錄列舉**不新增 business 端點**，沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`；本頁僅在 BFF 新增自己的路由 `GET /api/bff/exchange-rate/export/browse` passthrough（依「一個前端頁面一個 BFF」）。
- [ ] **可手動立即匯出（驗證用）**：設定卡提供「立即匯出到目錄」按鈕（`POST /api/bff/exchange-rate/export/run-now`），立即產檔到設定目錄並回傳實際落點路徑與檔案大小；此操作**不動當日排程 guard**。
- [ ] **每使用者各自設定（owner-scoped 設定表）**：排程設定存於新表 `exchange_rate_export_schedule`（每 `owner_user_id` 一列 UNIQUE、`@Filter(ownerFilter)` 隔離），欄位含啟用／時分／輸出子路徑／匯出範圍／上次執行日期（當日 guard）／上次執行時間與結果。
- [ ] **背景產檔不需 `enableFilter`（同 Requirement 41）**：匯率為**全域公開資料**（`exchange_rate_history` 無 `owner_user_id`、無 `@Filter`），故背景 cron 直接呼叫 `exportExchangeRates(currency, start, end)` 即可，不需要 `exportXxxForOwner(ownerId)` 變體。此點與 Requirement 39（per-user 損益，不過濾會外洩他人資料）相反，與 Requirement 37／41 相同——**排程設定 per-user，但資料本身全域**。
- [ ] **幣別固定 USD（不新增設定欄）**：本頁為「台幣兌美元」單一幣別頁，排程表**不設 `currency` 欄**，服務層以常數 `USD` 產檔。business 匯出端點沿用其他匯率端點的慣例保留 `currency` 參數（預設 `USD`、`CURRENCY_PATTERN` 驗證），但排程不暴露該維度——避免為尚不存在的多幣別頁面預留未使用欄位。日後真的要多幣別，屆時再加欄並補 migration。
- [ ] **顯示標籤必須跟著幣別走（不可寫死「台幣兌美元」）**：既然端點對外接受 `currency`，工作表名與檔名就必須由幣別推導，否則帶 `currency=ZAR` 會匯出 ZAR 資料卻標示為美元——實測可重現的靜默誤標。標籤由單一來源 `ExcelExportService.exchangeRateLabel(currency)` 產生（USD → `台幣兌美元`，其餘 → `台幣兌{幣別}`），工作表名、手動匯出檔名、排程檔名三處共用，避免各自硬編碼而漂移。「前端恆送 USD」是現在呼叫者的性質，不是這支 API 的契約，不足以作為寫死的理由。
- [ ] **排程執行機制與自癒（比照 Requirement 34／37／39／41）**：每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`），以 `now >= 設定時分` ＋ `last_run_date` 當日 guard 判斷（非「分鐘精確相等」，避免排程執行緒被長工作卡住跨分鐘導致整日靜默漏跑）；服務重啟以 `ApplicationReadyEvent` 補跑當日已到點未執行者；`AtomicBoolean` 防重入；單一使用者失敗只記 `last_run_status`＋log、不影響其他使用者（成功或失敗都設當日 guard）。
- [ ] **寫檔採 tmp ＋ atomic move**：先寫 `.tmp` 再 `ATOMIC_MOVE`（不支援時退 `REPLACE_EXISTING`），避免覆寫既有檔時因中途失敗留下半截殘檔。
- [ ] **檔名**：`台幣兌美元_{使用者ID}_{YYYYMMDD}.xlsx`。含 owner id 的原因同 Requirement 41：資料雖為全域，但各使用者可設不同匯出範圍，同日產出內容不同，不帶 id 會在共用目錄互相覆蓋。
- [ ] **排程列表頁需登錄**：新排程須在「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）補上對應項目，避免該頁與實際排程漂移。
