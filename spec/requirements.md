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
- [ ] 明細表上方提供市場 tab（全部／台股／美股／英股）客戶端篩選（既有行為，此處補記）
- [ ] **新增表單的市場預設跟隨當前市場 tab（Task 250）**：按「新增」開啟表單時，「市場」欄預設值＝當前市場 tab，tab 為「全部」時預設「台股」（維持既有行為），幣別依既有規則連動（美股／英股→USD，其餘→TWD）。**只影響新增**：編輯既有紀錄一律帶入該筆自己的 `market`，不受 tab 影響；表單開啟後使用者自行改選的市場不得被 tab 覆寫。行為與交易紀錄頁（Requirement 49）完全一致。

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
- [ ] **休市 DB→Redis 同步必須保有可計算且日期一致的昨收／漲跌／漲跌幅（Task 235）**：`PriceCacheWriter.syncClosedFromDb` 不得沿用既有 Redis JSON 的 `previousClose`（即使有值也可能屬於舊 `tradingDate`）。必須一律以本次 DB 最新收盤的 `tradingDate` 為界，從 `stock_price_history` 讀取嚴格早於該日的最近一筆收盤作為 `previousClose`，再衍生 `priceChange`／`changePercent`；查無前一交易日才允許三欄為 null。平盤必須回傳數值 `0`／`0%`，不得因 null 而讓 Dashboard 隱藏漲跌。
- [ ] **FinMind 當日收盤校正必須驗證來源交易日期（Task 235）**：`getTwClosingPriceFromFinMind(code, expectedDate)` 必須讀取回傳列的 `date`，且只有 `date == expectedDate` 才能交給 `ClosePersister` upsert；FinMind 尚停在 T-1、日期缺漏或格式錯誤時一律視為 miss，不得把 T-1 OHLC 以今日日期複製寫入 `stock_price_history`。此守門須在寫 DB／Redis 之前完成。
- [ ] **「持股抓價清單」必須涵蓋每一位 owner 的最新快照，不得全庫只取一筆（Task 257）**：`StockSourceQuery.collectHeldStockCodes` 原以 `SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1` 取**全庫單一**最新快照，多位 owner 同日各有快照時 tie-break 由 Postgres 任意決定，落選 owner 的持股（若又不在 `stock_alert` 觀察清單內）就完全不會被抓價、也不會被收盤校正。實測 2026-07-29：owner 1 快照 id 15 有 35 筆台股持股、owner 2 快照 id 18 只有 2 筆，兩者同為 2026-07-30，Postgres 選中 id 18，使 `2885`（元大金，只在 owner 1 持股、不在觀察清單）**不在**每 2 分鐘的 `PricePoller.scheduledTwIntradayUpdate`、也不在 16:00 `ClosePersister.verifyTwCloseWithFinMind` 的 18 檔範圍內；其 Redis `price:台股:2885` 停在前一日的值直到 24h TTL 過期消失，而 13:32 `dumpTwCloseFromRedis` 又把該過期前的舊值以今日日期寫進 `stock_price_history`，最終前端顯示 `$63.50 ▲$0.00 (0.00%)`（07-28 的收盤），實際 07-29 收盤為 62.2。故該方法必須改以 `DISTINCT ON (owner_user_id) ... ORDER BY owner_user_id, snapshot_date DESC, id DESC` 取**每位 owner 各自**的最新快照（同 owner 同日多筆再以 id 決勝，結果具決定性），與 `collectTwRadarCodes`（Task 249）**使用同一個快照選取子查詢**（僅此一段相同，非全等——後者另有 SQL 層 `WHERE h.market = '台股'` 與無條件 `remove("0000")`，前者靠 `classify()` 的 else 分支收攏非美股／非英股為台股且只在 `stock_alert` 側排除 `0000`，故兩支不可互相替換）。此清單為跨租戶共用的**市場資料**抓取範圍，不回傳給任何使用者、不構成租戶洩漏
- [ ] **收盤 dump 只能寫「本次目標交易日的收盤前後所取得」的值（Task 258）**：`ClosePersister.dumpRedisToDb` 原本只要 Redis `price:{market}:{code}` 有 `price` 就寫進 `stock_price_history` 當日列，**不檢查 payload 的 `tradingDate`，也不檢查該值有多舊**。凡是在 Redis SET `price:index:{market}` 內、但當日未被盤中 cron 刷新過的代號（例如已不在任何 owner 最新快照、也不在 `stock_alert`，卻仍在 `stock` 主檔而被開機 `warmCacheOnStartup` 寫入 Redis 的「曾持有／曾觀察」標的），其陳舊值就會被冠上今日日期寫進收盤歷史。更嚴重的是 `PriceCacheWriter.syncClosedFromDb` 會**從 DB 最近收盤寫回 Redis**，於是錯誤值透過 Redis 洗一圈回到 DB、每個交易日自我延續一列，且新寫的錯誤列又成為下一輪 `syncClosedFromDb` 讀到的「最近收盤」。實測 2026 年已累積 **台股 122 列、美股 3 列、英股 3 列**與前一列 OHLCV 全等的假收盤（例 `2002` 中鋼 2026-07-16～07-29 的 10 個交易日中有 **8 列**被記成 `19.1000`、O/H/L 為 null、volume 0，另 `07-22`／`07-23` 兩日原本**完全沒有列**（10 ＝ 腐化 8 ＋ 缺列 2；本 AC 所述的 122／3／3 只計腐化列）；實際權威值除 `07-16`／`07-21` 同為 18.80 外每日不同）。故 dump 必須逐檔守門：payload 的 `tradingDate` 必須等於本次 dump 的目標交易日，且 `updatedAt` 距本次執行時刻不得超過 12 分鐘（＝該值來自收盤前最後幾輪 cron）。守不過就**跳過該檔、不寫任何列**——不得改用昨收／開盤價／中價回填（沿用本 Requirement「禁止回寫充數」原則）；當日該檔無列時下游自然 fallback 至最近一筆收盤，16:00／18:00 的 FinMind 校正仍是當日收盤的最終權威。**不得改以 payload 的 `closed` 欄位當守門條件**：13:32 dump 取的正是 13:28～13:30 那輪盤中 cron 寫入的值，其 `closed` 為 `false`，以它守門會把正常路徑整個擋掉
- [ ] **既寫錯的收盤歷史必須能被權威來源覆寫修復（Task 258）**：既有回補路徑**修不了**已存在的錯誤列——`HistoricalBackfillService.backfillTwStock/backfillUsStock/backfillUkStock` 有兩道各自獨立的阻擋：(1) 起點一律為 `maxDate.plusDays(1)`（除非該檔完全無列、或要往更早擴充歷史），故永遠碰不到區間中段；(2) for-loop 內 `if (store.existsHistory(...)) continue;` 明確 skip-if-exists。故須新增一條**修復用**路徑，對指定市場與日期區間重新抓取權威日線並**覆寫**既有列（`StockSourceQuery.upsertHistory` 本身已是真 upsert，存在即 UPDATE）。修復必須：只在權威來源**真的回傳該日 bar** 時才覆寫（查無則保留原列不動、不刪除、不猜值）；沿用「今日列獨佔」規則不寫該市場當日列；為冪等（重跑不改變結果）。該修復路徑**必須有一個可手動觸發的入口**：內部端點 `POST /internal/repair/history`（`market` 必填且需 URL-encode、`from`／`to` 為 ISO 日期必填、`code` 選填；省略 `code` 時範圍為該市場 `collectAllStockCodes` 全集），回傳須包含處理檔數、覆寫列數、來源查無檔數、失敗檔數四項計數，使維運者能據以判斷修復結果。此入口**刻意不做成使用者可按的按鈕**、不加 business-services／BFF proxy——它會覆寫收盤歷史，屬破壞性維運操作。實際語意為 upsert，故區間內原本缺列的交易日也會被補上
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
- [ ] **KD 子圖同時顯示 K9 / D9 / J9 / K3D2 / RSV 五個指標值，且走勢圖全部技術指標改由後端單一來源供給**（Task 261）：原本子圖只有 K、D 兩條線與兩個 legend 數值，使用者無法在同一畫面判讀 J 值背離與當日原始 RSV。新增三值定義：**`J9 = 3×D9 − 2×K9`**、**`K3D2 = 3×K9 − 2×D9`**、**`RSV` 即該日遞迴中算出的原始 RSV**。K9／D9 沿用既有 KD9 遞迴（period 9、`RSV=(close−LL9)/(HH9−LL9)×100`、`HH9==LL9→50`、`K=prevK×2/3+RSV/3`、`D=prevD×2/3+K/3`、seed `prevK=prevD=50`、high/low 缺值 fallback close、`prevK`/`prevD` 續存未捨入值）。
  - **指標一律由 `TechnicalIndicatorService` 供給，前端不得自行計算**：走勢圖原本在前端 `StockAnalysisDialog.vue` 以 `calcKD()`／`calcMA()` 自算 KD 與三條均線，而觀察清單（Requirement 14）表格顯示的同義 K/D／均線來自後端 `TechnicalIndicatorService.computeAll()` —— 兩者資料源不同（後端併入今日 live 的**真實盤中高低價**且不濾 `source`；前端資料源 `HistoricalDataService.getStockHistory` 只併 `source` 不含括號的實際成交、且合成列**沒有 high/low** 只有收盤，`0000` 台股大盤更是**完全不併 live**），**盤中同一畫面雙擊同一列會看到兩組不同的 K/D**。故本需求將走勢圖指標上收：後端新增回傳**整段歷史序列**的指標端點，前端改為取用該序列畫線，刪除 `calcKD()`／`calcMA()`。此舉使 CLAUDE.md「同義欄位、同一 business service API（例：股票即時 K/D/季線 → 兩個頁面都透過 `TechnicalIndicatorService.computeAll()`）」對走勢圖真正成立。
    ⚠️ **同源範圍僅限「原始價基」的顯示點**：交易雷達（`TradingRadarView`）**個股決策表**的 K/D 走 `computeFromSeries(還原權息序列)`（`DistributionAdjustedPriceService` 先還原配息／除權），與本圖的原始價基**刻意不同**，凡視窗內有配息的個股必然對不上數字，**不納入本次同源範圍、也不得為此改動任一方**（`design.md` 既有規範明訂「禁止混用原始／還原價」）。交易雷達的**大盤卡**才是走 `computeAll()`。
  - **序列端點與單點 `computeAll()` 必須同源同值**：新增的序列計算與 `computeAll()` 共用**同一份** MA／KD 核心與**同一套今日 live 併入規則**（若最新歷史列非該市場今日、且 Redis live 的 `tradingDate` 等於今日，則以 `closePrice=price`、`highPrice=highPrice ?? price`、`lowPrice=lowPrice ?? price` 併為今日列）。驗收條件：**當 `end >= MarketZones.today(market)` 時**，同一 `(code, market)` 下序列最後一筆的 `k`/`d`/`ma20`/`ma60`/`ma240` 必須逐位等於 `computeAll()` 回傳的對應欄位，序列倒數第二筆的 `k`/`d` 必須等於 `computeAll()` 的 `previousK`/`previousD`。（`end` 早於今日時序列不併 live，兩者本就不必相等。）這是「同一畫面兩組數字」被消除的機械判準。
  - **不得因此改動既有 KD／MA 的數值行為**：`computeAll()` 目前取最近 240 筆歷史、`stockKd()` 從序列最早一筆以 seed 50/50 起遞迴、`previousK/previousD` 以 `subList(1,…)` 重跑同一遞迴取得。重構為序列版時這些對外數值**必須完全不變**——到價警示觸發門檻（Requirement 16）、警示 email 技術指標（Requirement 23）、觀察清單 KD 欄（Requirement 14）、MA% 換算觸發價（Task 146）、歷年資產 Excel 匯出的均線／KD 欄（`ExcelExportService`）都吃這些值，偷改等於偷改觸發門檻。須有測試證明重構前後 `computeAll()` 的五個欄位逐位相同。
  - **序列端點涵蓋 0000 台股大盤**：`computeAll()` 對 `0000`＋`台股` 走 `twse_index_daily_history`（含 OHLC、舊資料 high/low 可能為 null 需 fallback close，並比照併入 Redis 今日即時點位），序列版須維持同一特例，否則大盤走勢圖指標會空白。大盤的既有 KD／MA 遞迴（吃 `TwseIndexDailyHistory` 型別的那一套）與其對外數值**不得改動**，序列版改以既有的「指數日線轉 `StockPriceHistory`」映射餵同一份序列核心。
  - **股價線與指標線以 `tradingDate` 的「聯集」對齊，且必須由 BFF 完成**：兩者今日格條件不同（股價線要求 `source` 不含括號、`0000` 更完全不併 live；指標線比照 `computeAll` 併 live），故指標序列的日期集合可能**嚴格大於**股價序列。若以股價序列的日期當 x 軸，今日的指標點會被靜默丟棄、legend 顯示的仍是前一交易日的值——**本需求要消滅的不一致會原封不動留著**（`0000` 大盤為必然觸發，非偶發）。故：x 軸取兩者日期的**聯集**（股價缺該日填 `null`），legend 的五個 KD 值與三條均線一律取**指標序列本身的最後一筆**、箭頭比較指標序列的最後兩筆。此對齊屬跨來源 aggregation，依 CLAUDE.md「BFF 負責跨服務 aggregation、預先計算，前端只負責 render」**必須在 BFF 完成**，前端不得自行 join 兩支 API。
  - **畫線只畫 K9 / D9 / J9 三條**（J9 以虛線與 K9／D9 區隔）；**K3D2 與 RSV 只在 legend 顯示數值、不畫線**——子圖再加兩條會線條過密無法判讀（且 K3D2 = 3K−2D 可由 K9／D9 直接推得、RSV 可由 K9 與前一日 K9 反解 `RSV = 3K − 2·prevK`，資訊不會遺失）。但 legend 項目仍**必須**掛同名 `data: []` 空 series：ECharts `LegendView` 對「`legend.data` 有名字卻找不到同名 series」的處理是**整個項目連同數值都不繪製**（production build 無任何提示），不是 render 成灰色。
  - **legend 的五個 KD 指標值後附漲跌箭頭**（僅 K9／D9／J9／K3D2／RSV 五值；股價／均線／成本均價維持現況無箭頭）：▲（該指標序列最後一筆 > 前一筆）／▼（<）／無箭頭（相等或無前值），配色沿用台股慣例漲紅（`#dc2626`）、跌綠（`#16a34a`），與同圖 markPoint「紅漲綠跌」一致。比較基準恆為**指標序列本身的最後兩筆**（「當日」模式亦同，見下）。
  - **子圖 Y 軸不得再固定 `min:0 / max:100`**：`J9`＝`3D−2K` 在 K、D 背離時可為負值或 >100（例 K9=40.36、D9=32.74 → J9=17.50，但 K、D 快速交叉時 J 常衝出區間），固定軸會把 J9 線裁掉、看似「線斷掉」。改為依 K9／D9／J9 實際值域自適應，且**下限不得高於 20、上限不得低於 80**，確保既有 80／20 超買超賣參考虛線恆在可視範圍內。
  - **「當日」（intraday）模式維持既有語義**：分時 tick 數不足以重算日線級指標，五個 KD 值與三條均線皆取**指標序列的最後一筆**（盤中該筆已含今日即時價，非「前一收盤日」的值）畫成整段分鐘網格的水平參考線，箭頭同樣比較指標序列最後兩筆——確保切回日線期間時看到相同數字。
  - **範圍界定**：警示 email 的 PNG 走勢圖（`AlertChartRenderer`）維持只畫 K/D 兩線、Y 軸 0~100，**不同步變更**；它自有一套走 `getStockHistory` 的 KD 計算，本次不動。
- [ ] 「當日」分時資料採兩階段：(1) **盤中**由 `external-materials-service` 既有 `PricePoller` 每 2 分鐘輪詢時，將真實成交 tick（時間 + 成交價）`RPUSH` 到 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`，TTL 36h。**寫入守門條件**：(a) `source` 不含括號（排除 `(history)` / `(前收)` / `(買賣中價)` 等非實際成交值，與 `HistoricalDataService.getStockHistory` 對今日格條件一致）；(b) `markClosed=false`（排除 cache warming 對盤外時段抓到的「最新可得值」回灌污染分時序列）。前端切到「當日」即時讀此 LIST 看到當下累積結果
- [ ] tick 寫入的 `{tradingDate}` bucket 與 tick 時間戳的時區**必須同市場、同一交易日**：`PriceCacheWriter.resolveTradingDate` 對台／美／英三市場一律以「**該市場自己時區**」判定 live session（開盤中 or 剛收盤窗口）並取 `LocalDate.now(zoneOf(market))`。⚠️ **英股不可誤用台股時段判斷**：倫敦盤中（台北 15:00–23:30）台股已收盤，若沿用台股窗口會判為非 live → `tradingDate` 退回昨天，今日倫敦 tick 被貼進昨日 bucket、與盤後覆寫的昨日資料混桶，前端「當日」讀到跨兩天序列（時間軸倒退、假最高／最低）（Task 154 修正）
- [ ] **盤後**由 `IntradayTickRefresher` cron（**台股 14:00 TW、美股 16:05 ET、英股 17:00 LON**）抓外部完整當日資料覆寫該日 LIST，補回盤中 polling 因 `z='-'` 跳號漏掉的時間點。⚠️ **台股 / 英股排在收盤後 ~30 分（非 5 分）**：Yahoo 對 TWSE(~25min) / LSE(~15min) 的 5m feed 有延遲，收盤後 5 分抓會截斷（台股停在 13:10 而非 13:30、英股停在 ~16:20），後延讓 feed 追上收盤；美股 Yahoo 5m 無延遲，16:05 即完整（Task 156）
- [ ] **服務啟動與讀取時自癒不完整的當日分時資料（Task 236）**：`external-materials-service` 於 `ApplicationReadyEvent` 背景檢查當下正在交易的市場，對全部持股／觀察標的呼叫既有 `IntradayTickRefresher.refreshOne` 回補從開盤至目前的當日 5 分鐘行情（台股 FinMind `TaiwanStockKBar` 優先、空才 Yahoo `.TW→.TWO`；美股／英股直接 Yahoo `chart?interval=5m&range=1d`），避免服務盤中重啟後 Redis LIST 只從重啟時刻開始。另 `/internal/intraday-ticks` 每次讀取今日交易日 bucket 時，若解析後 LIST 為空、第一筆晚於市場開盤後 5 分鐘，或相鄰兩筆間隔超過 15 分鐘，須觸發同一回補後重讀；過去交易日維持既有 empty-only cold start，不因歷史稀疏而反覆對外抓取。讀取時回補須以 `(market,code,date)` 做 single-flight，同 key 併發只准一個外呼；每次嘗試完成後冷卻 60 秒（成功／失敗皆同），冷卻中直接回既有 LIST；同步最多等待 8 秒，逾時即回既有 LIST、背景回補可繼續完成。single-flight／冷卻用服務記憶體 bounded state，不新增 Redis key。寫入沿用 `IntradayTickStore.replaceTicks` never-shrink；外部來源失敗／429 時保留既有 LIST，不得清空。
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
- [ ] 盤中即時 `highPrice` / `lowPrice` 由 `external-materials-service` 自行聚合：每輪 cron 觀察到的成交價與當日已記錄的高/低做 max/min，存於 Redis（key `price:dayhl:{market}:{code}:{tradingDate}`，TTL 36 小時）。寫入 `price:{market}:{code}` 時，若外部 API 有提供 high/low 則取「外部值與聚合值的 max(high)/min(low)」；若外部 API 未提供（如 NASDAQ 對 ETF 的 `keyStats` 為 null），則直接採用聚合值。盤後 `dumpRedisToDb` 沿用同一份 Redis JSON 寫入 `stock_price_history`。**`0000` 台股大盤自 Task 263 起不參與本聚合**（見「Requirement 43 修訂（Task 263）」）：Yahoo `^TWII` 5 分 K 本就提供整日 high／low 陣列，本聚合賴以存在的前提（外部 API 不給 dayrange）對它不成立
- [ ] 美股 `openPrice` 來源：NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`；NASDAQ `/historical`「`fromdate == todate`」會回 400（`Provided date is less than from date`）、給日期區間盤中又不含今日列，兩者皆**無法**取得今日 open（原 `getNasdaqOpenPrice` 因此恆回 null → Redis live 無 `openPrice` → 連帶盤後 dump 進 `stock_price_history` 亦無 open → 觀察清單「開盤」欄恆顯示「—」）。改由 `PriceFetchClient.fetchUsTodayOpenFromYahoo` 打 Yahoo chart `interval=1d&range=1d`，取當日 daily bar `indicators.quote[0].open[0]`（盤中＝今日部分 bar 的 open、盤後＝完整 bar 的 open；只取 open 不碰 close，不違反 Task 84）；為盤中輪詢補強欄位，遇 Yahoo 429 fail-fast 不重試以免拖慢 cron。取不到時 `openPrice = null`，下游 `WatchStockService` 維持 `stock_price_history` fallback 行為。台股維持 TWSE mis API 的 `o` 欄位。
- [ ] 英股 `openPrice` 來源：**同取 Yahoo chart 的 `indicators.quote[0].open[0]`**（Task 194 修正）。原 `getYahooLsePrice` 取 `meta.regularMarketOpen`，但該欄**實測不存在於 Yahoo chart meta**（2026-07-15 以 `interval=1d&range=1d` 實測 CSPX.L / VUSA.L / VWRL.L 三檔皆 missing），故英股 `PriceResult.openPrice` 恆為 null → `PriceCacheWriter` 採 `NON_NULL` 序列化 → Redis `price:英股:{code}` 的 `openPrice` **欄位恆缺**（非寫入 null，而是整個欄位不存在）。**使用者可見症狀為「靜默顯示錯誤的日期」而非空值**：`WatchStockService` 見 `openPrice == null` 會 fallback 至 `stock_price_history.findRecentN` 最近一筆，而倫敦盤中今日列尚未入庫 → 觀察清單「開盤」欄拿到**前一交易日的開盤價**，看起來有值、實則非今日（比顯示「—」更難察覺）。16:32 LON `dumpUkCloseFromRedis` 直接抄 Redis 的缺漏值 → DB 今日列 open 亦為 null；要到 17:00 LON `verifyUkCloseWithYahoo` 走 `fetchUkHistoricalRange`（其 open 取自 `indicators.quote[0].open[i]`，本來就正確）覆寫今日列後才修正。**故 bug 影響窗＝倫敦整個交易時段至 17:00 LON**，收盤校正後自然痊癒，這也是本 bug 長期未被發現的原因。症狀與美股 Task 120 修正前同型，**修法亦同**（Task 193 `fetchKrIntradayQuote` 已用正確欄位落點）。同時 `meta.previousClose` 亦不存在，原「`chartPreviousClose` 取不到才 fallback `previousClose`」的 fallback 為**死碼**，一併移除以免誤導後續維護者以為該欄可用。`meta.regularMarketDayHigh` / `regularMarketDayLow` / `regularMarketVolume` 實測**存在**且與 `indicators.quote[0]` 對應值一致，high / low / volume 續用 meta 不動
- [ ] **觀察清單支援代號 `0000`（市場 = 台股）= 台股大盤（TAIEX）**（含 KD）：
  - 加入觀察 = 設一筆 0000 的 alert（與其他股票流程一致）
  - `lookup-name` 端點看到 `code=0000&market=台股` 直接回 `{"stockName":"台股大盤"}`，不打外部 API、不寫入 stock 主檔
  - `StockAlertService.create` 對 `0000` 不寫入 `stock` 主檔（避免被排程當作真股票抓價）；報價/技術指標一律從 `twse_index_daily_history` 取〔**後半句已分別被推翻**：「報價」自下方「Requirement 14 修訂（Task 263）」起，盤中改讀 Redis `price:台股:0000`；「技術指標」自 Task 228 起已由 `TechnicalIndicatorService.computeAllForTaiex()` 併入今日即時點位。**「不寫入 `stock` 主檔」不變**〕
  - 觀察清單列：`price` / `previousClose` 取 `twse_index_daily_history` 最新與次新；`openPrice` / `highPrice` / `lowPrice` 從同表 OHLC 欄位填；`buyPrice` / `sellPrice` / `volume` 為 null（大盤無買賣盤口、無成交量定義）〔**本條的前兩個子句（`price`／`previousClose` 取最新與次新、OHLC 取自同表）已由下方「Requirement 14 修訂（Task 263）」推翻**，盤中改優先讀 Redis 即時點位、`previousClose` 改為「嚴格早於顯示日的最後一筆」；**第三個子句「`buyPrice`／`sellPrice`／`volume` 為 null」不變**，該修訂明文保留。原文保留作為決策記錄〕
  - 月線（MA20）、季線（MA60）、年線（MA240）、KD 皆從 `twse_index_daily_history` 計算（與一般股票同算法），`TechnicalIndicatorService` 對 `0000` 改讀此表代替 `stock_price_history`
  - 警示彙總（`lastTriggered*`）對 `0000` 比照其他股票顯示

**Requirement 14 修訂（Task 263）—— 觀察清單大盤列在盤中不得停在昨日：**

> **本修訂推翻上方 AC「觀察清單列：`price` / `previousClose` 取 `twse_index_daily_history` 最新與次新；`openPrice` / `highPrice` / `lowPrice` 從同表 OHLC 欄位填」一句。** 該句成文於觀察清單只有完成日 K 可用的年代；Task 228 之後 `price:台股:0000` 已有每 2 分鐘更新的盤中即時點位，且 `TechnicalIndicatorService.computeAllForTaiex()` 已在吃它算 MA／KD，唯獨本頁的報價欄沒跟上。**使用者可見症狀（2026-07-31 13:41 實測）**：觀察清單台股頁的 `0000` 那一列，股價 39,933.30 ／開盤 40,048.94 ／最高 41,155.42 ／最低 39,404.65 ／昨收 40,039.18，逐欄等於 `twse_index_daily_history` 的 **7/30** 那一列（昨收則為 7/29 收盤），而當日 Redis `price:台股:0000` 已是 43,200.11（`updatedAt` 13:28）。成因是完成日 K 的今日列要等 `TwseIndexPoller` 的 14:00 排程才寫入，故 09:00–14:00 整個盤中「最新一筆」恆為昨日。**同一列的月線／季線／年線／KD 卻是含今日即時點位算出來的**，於是畫面同時呈現「今天的指標」與「昨天的股價」，兩者不可能互相印證。
>
> - [ ] **報價欄分盤中／盤後兩層**：`price` / `openPrice` / `highPrice` / `lowPrice` / `tradingDate` 的來源改為——Redis `price:台股:0000` 存在、其 `tradingDate` 等於**台北當日**、且 `twse_index_daily_history` 最新一筆的 `trading_date` **尚未到當日**時，五欄一律取自該即時值且 `closed = false`；不符時（Redis 無值／非今日／完成日 K 已含今日）維持既有行為，取 `twse_index_daily_history` 最新一筆的收盤與 OHLC，`closed = true`。判定條件必須與 `TechnicalIndicatorService.computeAllForTaiex()` 併入今日 live 的條件**語意等價——即對任何輸入，兩者的判定結果必須逐次相同**，否則同一列的股價與指標會再次來自不同日期，那正是本次要消滅的不一致。**「等價」不等於「文字照抄」**：兩邊的區域變數形狀本就不同，且本修訂明訂不得改動 `TechnicalIndicatorService` 的任何一行，稽核時應比對判定結果而非比對字面。`closed` 不得再無條件寫死 `true`。
> - [ ] **`previousClose` 兩層共用同一條規則**：一律取 `twse_index_daily_history` 中 `trading_date` **嚴格早於當列顯示日（`tradingDate`）**的最後一筆 `close_point`。盤中顯示日為今日 → 昨收即完成日 K 最新一筆；盤後顯示日為今日完成日 K → 昨收為次新一筆。**不得改讀 Redis payload 內的 `previousClose`**：「股市大盤查詢」頁的當日卡昨收亦讀同一張日線表（Requirement 18），兩頁的昨收是同義欄位、必須同一事實來源。
> - [ ] **`priceChange` / `changePercent` 一律由 `price` 與 `previousClose` 現算**（`priceChange` scale 4 HALF_UP、`changePercent` 先除 scale 6 再 ×100 取 scale 4 HALF_UP，與既有個股路徑同式），不得改用 Redis payload 內的既算值——那是 `PriceCacheWriter` 依它自己那份昨收算的，與上一條的昨收來源不保證一致。
> - [ ] **`buyPrice` / `sellPrice` / `volume` 仍恆為 null**（大盤無買賣盤口、無成交量定義），不因改讀 Redis 而變化；即時值 payload 內若出現這三欄亦不得採用。
> - [ ] **只動觀察清單這一條讀取路徑**：不得順手改動 `HistoricalDataService.getStockHistory` 對 `0000` 的今日格規則（走勢圖股價側明訂「`0000` 完全不併 live」，Task 261）、不得改動 `TwseIndexPoller` 的盤後批次（完成日 K 仍是唯一權威來源）、不得為此新增任何 `@Scheduled`。
> - [ ] **驗證**：測試須覆蓋「完成日 K 未到今日且 Redis 有今日即時點位 → 五欄取 Redis、`closed=false`、昨收＝完成日 K 最新一筆」「Redis 無值 → 退回完成日 K 最新一筆、`closed=true`、昨收＝次新一筆」「Redis 有值但 `tradingDate` 非今日 → 同樣退回完成日 K（不得把昨日點位當今日報價）」「完成日 K 已含今日 → 不併 live、`closed=true`」「`buyPrice`／`sellPrice`／`volume` 四種情形皆為 null」。

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
- [ ] **複合條件警示（AND 群組；Task 253）**：現行每一列 `stock_alert` 各自獨立評估、獨立 24h cooldown、獨立寄信，語意天然是 OR（任一條件達標即觸發）。新增「複合條件」＝一個 `stock_alert_group` 綁 2～5 條件，**群組內所有條件在同一次評估中同時成立才觸發一次**：
  - 新表 `stock_alert_group`（含 `owner_user_id` 多租戶隔離、`active`、`display_order`、`last_triggered_*` 五欄）；`stock_alert` 新增 nullable `group_id`：**`group_id IS NULL` 為既有的獨立單一條件，行為完全不變**；`group_id` 非空者為群組成員，**不得再自行觸發**
  - 群組**只支援單層 AND**，不支援 OR、不支援巢狀運算式。要 OR 就照現況拆成多筆獨立條件（每筆各自觸發、各自寄信）
  - 「同時成立」定義為**同一次評估、同一份現價**，且每個成員沿用與獨立條件**完全相同的指標計算路徑**（共用的 `matches(alert, currentPrice)`）；不接受「兩條件在某時間窗內先後成立」。注意此處刻意不寫成「共用同一份預先算好的技術指標」——既有 `checkMaDeviation` / `checkKdValue` 各自查 `stock_price_history` 現算，其 MA 口徑與 `TechnicalIndicatorService.computeAll()` 未必逐位一致，改成共用一份等於偷改既有單一條件的觸發門檻
  - 群組成員的 `active` 一律強制 true，啟停一律用群組的 `active`——避免停用單一成員讓 AND 條件數悄悄變少、判定變寬鬆
  - 群組至少 2 個條件、至多 5 個；同群組內不得有兩個完全相同的條件（同 `alertType` + `maPeriod` + `threshold`）；兩個群組的條件集合完全相同時視為重複、拒絕建立（比照既有單一條件的重複守門）
  - **複合條件不做盤中補抓**：既有單一條件在 `lastTriggeredAt IS NULL` 時會抓 Yahoo 5 分 K 回溯最近 3 個交易日、精確定位觸發時點（`findRecentIntradayTrigger`）；群組不走此路徑，只在每 2 分鐘的 live 評估判定。取捨：補抓要對每根 bar 重算全部指標再套 AND，成本遠高於效益，且漏掉的是「盤中短暫同時成立又立刻脫離」的尖峰
  - 收件人以獨立 join 表 `stock_alert_group_recipient` 表達（不在群組成員的 `stock_alert_recipient` 各存一份，避免同一事實重複儲存）
  - 觸發時寫**一筆** `stock_alert_trigger`（`alert_id` 為 NULL、`group_id` 為群組 id），email digest 的條件文案為群組合併 label（各條件 label 以 `" 且 "`（前後各一個半形空白）串接，如「低於季線 10%（92.09） 且 K 值低於 15」）；手動補發沿用同一份 label 組法
  - 警示頁的清單為「獨立條件 + 群組」混合列，群組列顯示合併 label；觀察頁「警示條件」欄把同群組成員合併成一條顯示，不再拆成多條（否則使用者看不出那是 AND）

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
- [ ] **走勢圖須比照畫面：上 pane 股價 + 月線MA20 + 季線MA60 + 年線MA240，下 pane KD（K/D 兩線、0~100、80/20 虛線參考線）**；所有數值寫進圖內 legend（中文名稱 + 最新值，2 位小數含千分位，如「股價 54.35」「季線MA60 45.53」「K 60.15」「D 67.07」）。KD 用與 `TechnicalIndicatorService` 完全相同的算法（period 9、RSV、K=2/3+RSV/3、D=2/3+K/3、seed 50/50、high/low 缺值 fallback close）。⚠️ **Task 261 起「比照畫面」僅限上 pane 與 KD 演算法，不再逐項比照下 pane 外觀**：畫面 KD 子圖已改為 K9/D9/J9 三線＋自適應 Y 軸＋K3D2/RSV 數值，email PNG 圖**刻意維持** K/D 兩線、0~100、80/20 虛線（信件圖幅小，三線加自適應軸會難以判讀）。另 Task 261 已刪除前端 `calcKD`（走勢圖指標改由後端序列端點供給），本圖的 KD 仍為 `AlertChartRenderer` 自有的一份、走 `getStockHistory` 序列
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
- [ ] 收件人持久化於資料庫 `notification_recipient` 表（欄位：`id`、`owner_user_id`、`email`、`active`、`receive_market_analysis`、`add_to_calendar`、`created_at`、`updated_at`；型別與約束以 `design.md` 的 `NotificationRecipient` 表為準），不寫死於程式碼或設定檔；email 欄位需正規化（去除前後空白、轉小寫），並與 `owner_user_id` 複合 unique（同一使用者不可重複，不同使用者可各自使用同一 email）
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

**Gmail 收件人加入 Google 日曆（Task 248 新增）：**

**User Story:** 作為使用者，我希望警示觸發時除了收到 email，還能自動在 Google 日曆上出現一個事件，靠日曆的推播提醒讓我在手機上立刻知道，而不是等到我打開信箱才看到。

- [ ] 通知收件人可**逐一**設定「加入 Google 日曆」開關（`notification_recipient.add_to_calendar`，預設 `false`）。開啟後，寄給該收件人的**警示 digest email** 額外夾帶一份 iCalendar（RFC 5545）邀請，Gmail / Google 日曆收到後即自動建立日曆事件並推播提醒
- [ ] **僅 Gmail 網域可開啟**：email 網域為 `gmail.com` 或 `googlemail.com`（正規化後小寫比對）才允許 `add_to_calendar=true`。非 Gmail 收件人前端不顯示可切換的開關（顯示「—」），後端 `PATCH .../calendar` **在「由 `false` 切為 `true`」時**對非 Gmail 收件人回 400 `IllegalArgumentException`「僅 Gmail 收件人可加入 Google 日曆」（不可只靠前端擋）；**反向（`true` → `false`）一律放行不檢查網域**，否則資料被改壞後（如殘留「非 Gmail 卻已開啟」的列）將永遠無法從畫面關閉。收件人 email 由 Gmail 改成非 Gmail 時，`update` 必須一併把 `add_to_calendar` 歸 `false`（避免留下該殘留狀態）
- [ ] **收件人分組改以收件人 id 為單位**：dispatcher 原本以 email 字串分組（`groupByRecipient`），但 `notification_recipient` 的唯一鍵是複合 `(owner_user_id, email)` —— **不同使用者可各自使用同一 email**，且背景排程無 HTTP request context、Hibernate `ownerFilter` 不啟用。以 email 為 key 有兩個後果：其一，無法安全取回該列的 `id` 與 `add_to_calendar`（同 email 多列會取到別的租戶）；其二，兩個租戶各有同一 email 的收件人時，兩邊的觸發會被**合併進同一封信**寄出。故改以「收件人 id」為分組單位，email 僅作為寄送位址。**行為變更（刻意）**：同一 email 分屬不同租戶時，改為各租戶各寄一封，不再合併——這同時關掉上述跨租戶內容混寄的路徑
- [ ] 日曆邀請**只掛在自動 digest（`flush()`）**，手動「補發」（`resendLastTradingDay`）**不**夾帶——補發是使用者當下主動按的全量重寄歷史觸發，再塞進日曆只會製造重複事件，且使用者人就在畫面前，不需要推播
- [ ] **一封 digest 一個日曆事件**（非一檔股票一個）：同一封信涵蓋的所有觸發合併為單一 VEVENT。理由是 Gmail 對含多個 VEVENT 的 `METHOD:REQUEST` 只會辨識第一個，多事件會靜默丟失
- [ ] 日曆事件內容：
  - `SUMMARY` = 該封信主旨（`[資產管理] 股票警示觸發 N 筆`，N 為該收件人去重後股票檔數）
  - `DESCRIPTION` = 每檔一行「股名 (代號 市場) — 條件文案 觸發價 X」的純文字摘要（與信件正文同一份 digest 資料，不另算），特殊字元依 RFC 5545 escape（`\` → `\\`、`;` → `\;`、`,` → `\,`、換行 → `\n`）
  - `DTSTART` = **寄送當下 + 2 分鐘**（秒歸零、UTC `yyyyMMdd'T'HHmmss'Z'`），`DTEND` = `DTSTART` + 15 分鐘；`VALARM`：`ACTION:DISPLAY` + `TRIGGER:-PT1M`（≈ 寄送後 1 分鐘推播）
  - `TRANSP:TRANSPARENT`（不佔用忙碌時段，不影響使用者 free/busy）、`STATUS:CONFIRMED`、`SEQUENCE:0`
  - `UID` = `alert-{recipientId}-{寄送 epochMillis}@asset-management`，**每封信皆為新 UID**（同 UID 會被 Google 視為既有事件的更新而覆蓋前一次觸發、且不再推播）
  - `ORGANIZER` = 寄件人（`NOTIFICATION_FROM` 或 `MAIL_USERNAME`）；`ATTENDEE` = 該收件人，帶 `PARTSTAT=ACCEPTED;RSVP=FALSE`（預設已接受、不要求回覆，避免 RSVP 回信灌爆寄件信箱）
- [ ] **`DTSTART` 必須落在未來**（採 +2 分鐘）：Google 日曆對「開始時間已過」的事件不發推播；而事件在其提醒窗內被建立時（例如收件人日曆預設「10 分鐘前提醒」、事件 2 分鐘後開始）Google 會於加入當下立即推播。留 2 分鐘緩衝可同時涵蓋「ics 的 `VALARM` 被採用」與「被收件人日曆預設提醒覆蓋」兩種情形
- [ ] MIME 結構：iCalendar 以 `Content-Type: text/calendar; charset=UTF-8; method=REQUEST` 的 body part 掛在 root multipart（`MimeMessageHelper.getRootMimeMultipart()`），與既有 HTML 正文 + inline CID 走勢圖並存，**不得破壞既有 HTML 與內嵌圖**；ics 內容以 CRLF 換行，且長行依 RFC 5545 folding（75 octets，續行以單一空白起始）。**內容必須以明確的 UTF-8 位元組寫入**（`DataHandler` + `ByteArrayDataSource`），**不得用 `MimeBodyPart.setContent(String, type)`** —— JavaMail 對 `text/calendar` 沒有 DataContentHandler（`META-INF/mailcap` 只註冊 text/plain、text/html、text/xml、multipart/\*、message/rfc822），會退回以 `Charset.defaultCharset()` 寫出而忽略宣告的 `charset=UTF-8`，中文摘要在非 UTF-8 預設編碼的 JVM 下會變亂碼
- [ ] 失敗策略比照既有：組 ics 或夾帶失敗一律 `log.warn` 後**照常寄出純 email**，不可讓整封信寄不出去、更不可阻斷警示判斷流程
- [ ] **收件人知情**：本功能會把警示內容寫進**他人**的 Google 日曆，而開關由帳號擁有者操作、收件人未被徵詢（`ATTENDEE` 帶 `PARTSTAT=ACCEPTED;RSVP=FALSE`，Gmail 卡片上不會出現可拒絕的 RSVP）。故**夾帶日曆邀請的那封 digest，其 HTML 正文結尾必須多一行提示**：「本信附有 Google 日曆邀請，如不需要請告知寄件人於『警示通知設定』關閉。」（只在有夾帶時出現，未夾帶的信件內容不變）。`add_to_calendar` 預設 `false`，不對既有收件人自動開啟
- [ ] 通知設定頁（`/notification-settings`）新增「Google 日曆」欄：Gmail 收件人顯示可切換的開關（`el-switch`，開＝已加入日曆），非 Gmail 顯示「—」並以 tooltip 說明「僅 Gmail 收件人支援」；頁面說明文字需標示前提——**收件人的 Google 日曆須維持「自動將邀請加入日曆」設定**（Google 日曆預設為「是」），若其設為「僅在我回覆時」，則需在信中手動點一次接受才會進日曆
- [ ] API：`PATCH /api/notification-recipients/{id}/calendar` 切換開關（比照既有 `/active`、`/market-analysis`，owner-scoped 以 `TenantGuard.assertOwned` 縱深保護），`Response` DTO 增 `addToCalendar` 欄位；前端經既有 BFF `/api/bff/notification-settings/recipients/**` passthrough 取用，不另開 BFF

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

**User Story:** 作為系統部署者、管理者與一般使用者，我希望部署時能以環境變數指定主要管理者的 Gmail，讓同一份安裝包可安全散佈給不同人；登入後每個人只能管理與檢視「自己的」資產，管理者能切換檢視任何使用者的資產並管理使用者帳號，以確保多人共用同一套系統時的資料隱私與權限分離。

**Acceptance Criteria:**

- [ ] **Gmail OAuth2 登入**：系統採 Spring Security OAuth2 Login（authorization code 重導流程），provider 為 Google。**OAuth2 登入端點落在 BFF（Spring Cloud Gateway / WebFlux reactive，唯一對外入口）**，非內網的 business-services。未登入者存取受保護資源一律先導向 Google 登入。
- [ ] **Session 機制**：登入成功後 BFF 以 server-side `WebSession` + `SESSION` cookie（`HttpOnly`、`SameSite=Lax`、prod `Secure`）維持登入態；登入只為辨識身分，不保留 Google access/refresh token。前端為 SPA，登入成功後固定 302 導回前端 `/`，由前端打 `GET /api/me` 決定後續導向。
- [ ] **`GET /api/me`**：回傳目前登入者 `{ email, name, picture, role(ADMIN/USER), status(PENDING/ACTIVE/DISABLED), effectiveUserId, effectiveUserName, isImpersonating, switchableUsers[] }`；`switchableUsers` 僅 ADMIN 才填；`status` 即時查資料庫（核准後不需重登即生效）。未登入時受保護 API 回 **401 JSON**（非 302），由前端攔截後整頁跳轉 `/oauth2/authorization/google`。
- [ ] **主要管理者由環境設定**：business-services 必須讀取必填的 `ADMIN_EMAIL`，啟動時先 `trim`、轉小寫並驗證 email 格式；缺值或格式錯誤須 fail-fast，禁止回退到任何內建帳號。該 email 登入時永遠維持 `role=ADMIN`、`status=ACTIVE`，不得停用或調降角色；其他 Gmail 首次登入規則不變。
- [ ] **前後端共用同一判定結果**：`UserResponse` 回傳 `protectedAdmin:boolean`；使用者管理頁只能依此欄位標示並鎖定主要管理者操作，不得在 BFF 或前端再保存管理者 email。
- [ ] **新使用者待核准**：任何其他 Gmail 首次登入，自動建立 `app_user`（`role=USER`、`status=PENDING`）。PENDING 使用者可完成 Google 登入，但呼叫業務 API 一律被擋（回 `403 {code:"ACCOUNT_PENDING"}`），前端導向「等待核准」頁；管理者於使用者管理頁核准成 `ACTIVE` 後始能使用。`DISABLED` 同樣被擋。
- [ ] **多租戶資料隔離（每人只能看自己）**：下列「資產類」資料以 `owner_user_id` 隔離，每個使用者只能讀寫自己的：`asset_snapshot`（含子表 `bank_deposit`/`stock_holding`/`fund_holding` 經 snapshot 繼承）、`realized_gain`、`payment_account`、`stock_alert`（含 `stock_alert_trigger`、`stock_alert_recipient` join、`watch_stock` 衍生清單）、`notification_recipient`。參考／行情／設定主檔（`bank`、`broker`、`stock`、各 `*_history`、`market_type`、`deposit_type`、`asset_class`、`fund_master`、`fund_nav` 等）維持**全系統共用**，不加 owner。
- [ ] **過濾強制執行**：business-services 以 request-scoped `CurrentUserContext`（讀 BFF 傳來的 `X-User-Id`/`X-User-Role`/`X-User-Status` header）+ Hibernate `@Filter`（`owner_user_id = :ownerId`）統一過濾所有受隔離 entity 的查詢；寫入路徑以 `CurrentUserContext.effectiveUserId()` 設定 owner。**filter 僅在有 HTTP request 時啟用**，背景 cron（警示偵測 / email dispatcher）不啟用、維持掃全體 active alert 的既有行為。
- [ ] **business-services 未識別身分回 401（錯誤語意契約）**：business-services 內需要使用者身分才能執行的 service，在 `CurrentUserContext` 無使用者（請求未帶 `X-User-Id`／`TenantGuard.requireCurrentUserId()` 回 `null`）時，一律拋出專用的 `UnauthenticatedException`，由 `GlobalExceptionHandler` 對映為 **401 Unauthorized**，不得落入 `Exception.class` 的 500 兜底。涵蓋五支排程設定 service（`ExportScheduleService`／`RealizedGainExportScheduleService`／`TradingCalendarExportScheduleService`／`CommodityExportScheduleService`／`ExchangeRateExportScheduleService`，Requirement 34／39／37／41／42）與 `PortfolioAdviceService` 的儲存理財條件、產生資產配置建議兩處（Requirement 33）。與既有對映一致成套：`TenantAccessException`→404（不洩漏他人資源存在）、`AdminRequiredException`→403（已識別但權限不足）、`UnauthenticatedException`→401（未識別身分）。此為錯誤語意修正而非資安邊界變更——business-services 未對主機開埠，公開邊界（nginx:80／bff:8080）對未登入與偽造 header 本就正確回 401，錯誤回應亦不含 stacktrace。
- [ ] **管理者代看（使用者切換）**：管理者頁面右上角提供使用者下拉，選定某使用者後整個系統以該使用者視角呈現（`effectiveUserId` = 選定目標）。切換 `POST /api/impersonate {userId}` 僅 ADMIN session 可呼叫並寫入 BFF session；之後每個下游呼叫帶的 `X-User-Id` 即為該目標，查詢路徑與「看自己」完全相同、零分支。一般使用者 `effectiveUserId` 恆等於自己、無法變更。
- [ ] **使用者管理（ADMIN）**：新增使用者管理頁（`/settings/users`），管理者可列出所有使用者、核准（PENDING→ACTIVE）、停用（→DISABLED）、設定角色。後端 `/internal/users/**` 管理端點限 ADMIN。
- [ ] **備份／還原限管理者**：「系統設定 > 備份/還原 資料」僅管理者可用。非管理者前端選單**不顯示**該項與「使用者管理」項；後端雙層阻擋——BFF `/api/bff/backup-restore/**` 與 business-services `/api/backups/**` 皆限 `ROLE_ADMIN`，非 ADMIN 回 403。備份/還原為全系統操作，不套 owner filter。
- [ ] **唯一性多租戶化**：原本全域唯一的欄位改為「每使用者唯一」——`asset_snapshot.snapshot_date` 改 `(owner_user_id, snapshot_date)` 複合唯一（不同使用者同一天各可有一筆快照）、`notification_recipient.email` 改 `(owner_user_id, email)` 複合唯一。
- [ ] **資料遷移（Liquibase）**：既有 `v1.34.0-multi-tenant.sql` 保留不改以維持已部署資料庫 checksum；另以新 changeset 清理舊版固定 email 的空白 seed，且必須同時確認所有 `owner_user_id` 欄位皆無資料、沒有其他外鍵引用才可刪除。若舊帳號仍擁有資料則保留為一般既有帳號；日後更換 `ADMIN_EMAIL` 只改變受保護的主要管理者身分，不會暗中轉移資料所有權。`ddl-auto:none`，schema 變更一律走 Liquibase。
- [ ] **部署**：Nginx／vite proxy 需把 `/oauth2/**`、`/login/oauth2/**`、`/logout` 導向 BFF 並透傳 `X-Forwarded-Proto`（確保 prod https redirect-uri 正確）；Google client-id/secret 由環境變數注入 BFF，`ADMIN_EMAIL` 只注入 business-services；cookie session 須一致設定 CSRF（`XSRF-TOKEN`/`X-XSRF-TOKEN`）、`withCredentials`、CORS `allowCredentials=true` 且具名 origin。

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
- [ ] **濾除「影響不了全球經濟／股市」的雜訊新聞（Task 221）**：使用者於 2026-07-19 回報兩則實際爬進 `news_headline` 的雜訊，兩者成因不同，故新增兩條規則：
    - **① 社會獵奇誤判為地緣政治**：「防囚犯越獄！以色列修法 鱷魚可部署監獄周邊」命中 `GEO_REGION`(以色列)＋`GEO_TRIGGER`(部署) 被判 `KEEP:market-geopolitics`。新增 `SOCIAL_ODDITY` 否決集（越獄／囚犯／監獄／獄方／獄警／典獄／鱷魚／蟒蛇／動物園／追晉／褒揚令），置於**財經豁免之後、其他所有規則之前**（緊接規則①），才攔得到原本在規則④被留下的標題。**刻意不從 `GEO_TRIGGER` 移除「部署」**——對抗式稽核實測移除會誤殺「南韓同意部署薩德系統」（薩德曾重創韓股）「伊朗在荷姆茲海峽部署新型快艇」（油運咽喉）等真正影響市場者，且語料中「部署」有效樣本僅 1 則，不足以支撐移除。
    - **② 財經軼事／都市傳說**：「68歲退休翁嫌定期定額賺太慢！看到半導體股狂飆就衝了 下場曝光」標題必帶財經詞（半導體股／定期定額），在規則①即被 `KEEP:finance` 攔下，後續任何否決規則都沒有機會發言。故新增 `ANECDOTE` 判定並置於 **`FINANCE` 之前——這是 Task 199 建立 cascade 以來唯一凌駕「財經一律保留」的規則**。觸發需**同時**滿足「`N歲`」與（身分稱謂｜釣魚詞），刻意設計得極窄。此類文章看似與股市有關，實為個人理財軼事／勵志故事，對判斷股市走向沒有資訊量，卻佔用今日股市分析的 prompt 額度。
    - **零誤殺驗證**：以 2005 則真實 ltn／udn 標題全量對照，KEEP→DROP 翻轉 **15 則**（12 則理財軼事、2 則軍人榮典、1 則鱷魚），逐則檢視皆為應濾除者；**零反向翻轉**。含「N歲」者 43 則中命中 34 則，而全語料命中總數亦為 34——代表不含「N歲」的標題完全不受本規則影響，爆炸半徑為零。必保回歸錨點（青安3.0 房貸政策／國泰世華董座人事／巴菲特給投資人忠告／勞退新制65歲請領／退休金試算）全數存活。
    - **刻意排除的候選詞**（經語料實測會誤殺，勿再加入）：判刑／監禁／起訴／收押／交保／法院／檢方／刑事（打到「聯準會前顧問說謊遭判38月監禁」「內線交易張國華1.2億交保」「國際刑事法院」「立法院」）、領照／換發（台灣媒體主流用法是**建照／使照領照量**＝營建股房市領先指標）、車禍（打到「馬斯克自駕計程車連環車禍遭監理機關調查」）、推擠（台媒報導重大法案闖關的標準寫法）、罹難／翻覆／空難、走私（打到「防中國走私輝達晶片」）、裸的「退休」（打到「勞工65歲退休可請領月退金」）。
- [x] **濾除南海小型海上摩擦新聞（Task 229）**：使用者於 2026-07-20 回報一則實際爬進 `news_headline` 的雜訊「中國海警南海持棍傷人 菲律賓海軍1人遭打傷」——南海海警／海軍間的低烈度肢體摩擦（持棍、水砲、對峙、驅離、擦撞、登檢、扣船…），對台股大盤與全球經濟無實質影響，卻因命中 `CHINA`（`南海`）＋`BEIJING_REGIME`（`海警`）在 cascade 規則③被判 `KEEP:china-regime` 而收錄。新增 `SCS_SKIRMISH` 否決集，置於**財經豁免（規則①）與社會獵奇否決（規則①b）之後、生活否決（規則②）與中國判定（規則③）之前**（新規則①c），比照 Task 221 `SOCIAL_ODDITY` 的最小爆炸半徑手法。
    - **三重 AND 守門（缺一不濾）**：(a) 命中**南海地區詞**（`南海`，或 `黃岩島`／`仁愛礁`／`斯卡伯勒` 等中菲衝突熱點礁）；(b) 命中**低烈度海上摩擦詞**（持棍／棍棒／木棍／水砲／水炮／噴水／射水／水柱／對峙／驅離／驅趕／擦撞／碰撞／衝撞／撞船／撞擊／登船／登檢／攔檢／臨檢／扣押／扣船／查扣／雷射／激光／潑漆／鳴笛／傷人／打傷／受傷／打人）；(c) **未**命中**重大升級守門詞**（開戰／宣戰／開火／交火／砲擊／炮擊／擊沉／擊落／擊毀／空襲／轟炸／飛彈／導彈／魚雷／封鎖／禁運／斷航／動員／戰爭）。三者皆成立才回傳 `DROP:scs-skirmish`；升級為真正衝突（開火／飛彈／封鎖…）者由守門詞救回、落回規則③以 `KEEP:china-regime` 收錄。
    - **「中南海」子字串陷阱（必修）**：「中南海」＝中共領導層駐地，與南海（South China Sea）無關（實際語料「揭GDP真相觸怒中南海！…」）。南海地區詞判定須先 `replace("中南海","")` 再比對子字串 `南海`，否則會把中共高層新聞誤判為南海摩擦。（該則另含 `GDP`／`經濟` 本就先被規則①`KEEP:finance` 攔下，剝除為防禦縱深。）
    - **金門摩擦刻意不納入**：語料中「中國公務船夜闖金門海域…強勢驅離」「中國海警船又闖金門限制水域…驅離」屬台海／金門戰區、非南海，且直接涉及台灣安全故維持收錄；本規則只鎖定使用者指定的「南海」，不擴及金門。
    - **刻意排除的觸發詞**（經真實語料實測會誤傷或過廣，勿加入摩擦詞集）：`對抗`／`巡弋`／`侵襲`／`灰色`（打到「美調6艘海防隊…對抗中國在台海、南海灰色侵襲」「美海防隊艦艇加入南海巡弋」等真正的美軍部署／地緣政治，應保留）、`衝突`（`GEO_TRIGGER` 已用於正當地緣政治，且「武裝衝突／利益衝突」過廣）、`軍演`／`軍事`／`海警`／`軍艦`（是事件主角而非「小」摩擦的標記，重大軍演具市場訊號意義，應留給規則③）。
    - **零誤殺驗證（對線上 3501 則真實 `news_headline` 全量對照）**：含南海地區詞者 **8 則**，本規則僅翻轉 **1 則**（使用者回報的持棍傷人案）為 `DROP`，其餘 7 則（南海仲裁 14 國聯署、美海防隊南海巡弋、中國官媒 AI 影片酸菲、南海仲裁十週年學者遇害、觸怒中南海 GDP…）全數維持 `KEEP`；含低烈度摩擦詞者另有 **9 則**（荷姆茲美伊對峙、金門驅離×2、中聯致癌油品扣押、義大利文物免扣押…）因**無**南海地區詞全部不受影響。**零反向翻轉**。單元測試 `EditorialNewsFilterTest` 補一組 `@Nested` 錨點：應 DROP 的南海小摩擦、應 KEEP 的南海重大事件（仲裁／部署／升級至開火）、金門摩擦不誤殺、中南海不誤殺、摩擦詞於非南海情境（荷姆茲對峙／不動產扣押）不誤觸。**此規則同 `EditorialNewsFilter` 只作用於台灣混合型 feed（自由時報財經／政治／國際、經濟日報）於抓取時過濾，不追溯清除已入庫的舊列。**
- [ ] **濾除體育賽事新聞，保留影響國際政治的重大事件（Task 241）**：使用者於 2026-07-27 要求「刪除所有體育賽事，除非體育賽事發生影響國際政治的大新聞，例如恐怖攻擊」。這同時要求兩個方向相反的變更——**更嚴**：純財經來源（wantgoo／moneydj）的體育新聞目前全部漏網（Task 240 的 `traceFinanceFeed()` 刻意不跑 `LIFESTYLE`）；**更寬**：新增一個目前完全不存在的豁免（混合型 feed 的體育新聞由 `LIFESTYLE` 無條件濾除，恐攻類事件同樣被濾）。
    - **實作**：把 `LIFESTYLE` 的 44 個體育詞**原樣搬出**為獨立的 `SPORT` 集合（不增不減，保證混合型 feed 判定 delta 嚴格為 0），另增 5 個零誤中賽事專名（`奧委會`／`國際足總`／`開踢`／`金盃`／`公開賽`，語料各 1 則且皆為目標）；新增規則①d，置於 **①`FINANCE` 之後、②`LIFESTYLE` 之前**，兩個入口（`trace`／`traceFinanceFeed`）共用同一支 `traceSport()` helper。
    - **必須置於 `FINANCE` 之後（硬約束）**：中文財經媒體大量借用體育語彙，語料實證「高股息ETF受益人數 0056奪冠」「元大高股息優質龍頭飆破120％奪冠」「好市多…單店平均營收百億霸氣封王」「台灣大6月EPS 0.52元 蟬聯電信股EPS冠軍」「卓榮泰喊話打造金融世界盃」，且 udn 有整個「隱形冠軍／…」專欄（8 則以上）。`球員`／`冠軍`／`奪冠`／`封王`／`電競`／`教練`／`金牌`／`賽事` 之所以安全**完全靠 `FINANCE` 先判**，任何把 `SPORT` 移到其前的重構會第一天就誤殺上列全部。
    - **必須置於 `LIFESTYLE` 之前，且豁免採正向 `KEEP`**：規則③④⑤ 都在 `LIFESTYLE` 之後、救不回來——實測「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」若只是跳過否決續行 cascade，②③⑤ 皆無命中、④ 的 `GEO_TRIGGER` 落空，最終仍是 `DROP:non-finance-general`；「巴黎奧運遭恐怖攻擊」同理（法國不在 `GEO_REGION`）。
    - **豁免只收人為攻擊與國家層級政治，不收意外災難**（使用者 2026-07-27 追加裁示：「『足球場看台倒塌 逾百人罹難』如果不是人為攻擊，是建築物太爛，不會影響到國際政治、經濟、股市，這類新聞不要」）。故場館事故詞（踩踏／倒塌／坍塌／暴動／騷亂）與傷亡數字守門一律不納入——它們指向工安與管理不善，非國際政治事件。此裁示推翻了設計初稿以希斯堡慘案為由納入場館災難的做法。
    - **豁免必須兩層，且守門詞不可用「有沒有提到某國家／政治角色」**（對抗測試驅動的關鍵設計）：單層 `containsAny` 豁免會被一般體育新聞大量誤觸——`制裁`（「聯盟制裁違規球隊」）、`抵制`（「球迷抵制球隊」）、`杯葛`（「球迷杯葛主辦單位」）、`爆炸`（「電競選手人氣爆炸」）、`暗殺`（「球員遭球團暗殺式冷凍」）**5 個測試案例 5 個全被誤救**。故拆為 **強豁免**（`SPORT_TERROR`：恐攻／恐怖攻擊／恐怖襲擊／恐怖主義／恐怖組織／恐怖分子／恐怖份子／自殺炸彈／炸彈客／槍擊案／爆炸案／爆炸事件／人質／挾持／國際奧會／國際奧委會／奧會模式，語意鎖死、單獨命中即保留）與 **弱豁免**（`SPORT_GEO`：抵制／杯葛／制裁／爆炸／暗殺／驅逐，需與 `SPORT_GEO_GUARD`〔外交／主權／代表權／國旗／國歌／多國／聯合國／反恐／邦交／斷交／正名／國安／戰爭／入侵〕AND 命中）。**守門詞刻意不用 `GEO_REGION`／`CHINA`／`POLITY_STRONG`**——體育本質上就是國際的，巴西／韓國／越南／中國是體育報導的日常詞彙，對抗測試實證用它們當守門時「世界盃巴西隊球迷抵制主辦單位售票制度」「韓國職棒球星遭球團驅逐出隊」「中國羽球選手遭禁藥制裁」等 **8 則一般體育新聞 8 則全部被誤救**；`政府`（「政府制裁禁藥球員」）與 `總統`（子字串誤中「**總統盃**」全國羽球錦標賽，同 `北市`⊂`竹北市`、`陽明`⊂`陽明交大` 的陷阱）亦刻意排除。
    - **規則①d 必須置於 `LIFESTYLE`（規則②）之前**：體育詞已於 241.1 移出 `LIFESTYLE`，故純體育標題不會被②攔下；真正理由是**體育事件常同時命中非體育的 `LIFESTYLE` 詞**——實測「奧運開幕演唱會遭恐怖攻擊 多國元首緊急撤離」（`演唱會`）、「世界盃球迷粉絲見面會爆炸案 主辦國提升反恐」（`粉絲`），①d 在②後會被判 `DROP:lifestyle`。至於 `traceSport()` 內強豁免／弱豁免／財經閘門三步的相對順序，**經實測不影響收錄結果**（全語料 diff＝0，只影響 label）。
    - **體育帶動的產業／營收新聞必須保留**（使用者一貫的「影響經濟」判準）：世足經濟學紡織／製鞋、中鋼燁輝搶奧運基建商機、中華電信MOD收視、華碩電競周邊營收、友達電競顯示器、世足加持日本電視出貨量、Nike／adidas 贊助商、FIFA 狂攬90億美元。純財經來源無規則①，全靠 `traceSport()` 內建的 `FINANCE` 閘門——無閘門實測 1223 則中 12 則命中 `SPORT`、其中 **8 則是誤殺（誤殺率 67%）**。`FINANCE` 白名單的兩處召回缺口以 `SPORT_ECON_EXEMPT`（`贊助商`／`加税`，各 1 則實證樣本、爆炸半徑 1）在體育規則內局部補，**不改動 `FINANCE` 本身**。
    - **刻意排除的候選詞**（逐詞實測會誤殺／誤救）：`運動`（誤中「營運動能」3 則、葉門「青年運動」6 則）、`國家隊`（9 樣本體育用法 0，全是無人機／SMR／機器狗國家隊、中國國家隊護盤）、`決賽`／`FIFA`（樣本多數為必保產業關稅新聞）、`足總`（誤中「資金不足總額」）、`奧會`（誤中「中華奧會」例行新聞）、`助攻`／`終場`／`開打`／`競賽`／`逆轉`／`稱霸`／`霸榜`／`黑馬`／`賽局`（全為財經比喻，語料體育用法合計 0）、`衛冕`／`王者`／`全壘打`／`破紀錄`／`蟬聯`／`爆冷`（語料零樣本；**體育否決是負向規則，零樣本詞誤中即誤刪財經**，不比照豁免集的零樣本例外）；豁免排除 `禁賽`（「球員禁賽3場」是體育紀律報導第一用語，收了等同讓否決集失效）、裸 `恐怖`／`炸彈`／`槍擊`／`槍手`／`刺殺`（恐怖打線／債務炸彈／手槍擊出10環／得分槍手／棒球術語刺殺出局）、`中華台北`（每則我國選手國際賽報導都有）、`引爆`（語料唯一與體育詞共現者正是應濾除的「世足》嘲諷梅西…引爆球迷炎上」）。
    - **零誤殺驗證（對線上 4835 則真實 `news_headline` 台灣新聞全量對照）**：**KEEP→DROP 2 則、DROP→KEEP 0 則（零反向翻轉）**，另 7 則僅 label 變更（6 則 `DROP:lifestyle`→`DROP:sport` 行為不變、1 則「俄羅斯重返奧運」`KEEP:finance-feed`→`KEEP:sport-intl-politics`）。新濾除的 2 則正是目標雜訊（wantgoo「AI眼中的世界盃8強」「巔峰對決！世足賽決賽開踢前夕」）。對抗測試（合成案例，因語料無此類事件）：國際政治重大事件保留 **10/10**（含恐攻／恐怖襲擊／爆炸案／槍擊案／挾持／多國抵制／IOC 制裁／奧會模式／俄羅斯重返奧運，以及「奧運開幕演唱會遭恐怖攻擊」「世界盃球迷粉絲見面會爆炸案」兩則同時命中非體育 `LIFESTYLE` 詞者）、一般體育含豁免詞濾除 **6/6**、國家指涉不得作為守門 **6/6**（巴西隊球迷抵制／韓國職棒驅逐／中國選手禁藥制裁／政府制裁禁藥球員／總統盃／大聯盟驅逐）、場館意外災難濾除 **3/3**（使用者裁示）、財經比喻含體育詞保留 **6/6**。**弱豁免層在現行語料中零樣本**（同時含 `SPORT` 與 `SPORT_GEO` 詞者 0 則、實際行經規則①d 者僅 9 則），故上述豁免行為僅由合成對抗案例驗證，不可將「全語料零反向翻轉」誤讀為整條規則都經真實語料驗證——此為使用者明確指定之功能需求的**刻意例外**，例外只給豁免集（誤中方向是少濾一則體育），否決集 `SPORT` 仍嚴格遵守「零樣本詞不納入」紀律。**已知殘留**：wantgoo「白宮：川普出席世界盃決賽 預計與FIFA主席共同頒發冠軍金盃」仍 KEEP——其摘要含場館名「大都會**人壽**體育場」（MetLife Stadium）誤中 `FINANCE` 的 `人壽`；同事件的 ltn 版本「世界盃決賽在即　白宮預告川普將出席」判 `DROP:sport`。唯一解是把 `金盃`／`開踢` 排在 `FINANCE` 之前，違反硬約束故刻意不做；根因屬 `FINANCE` 既有的場館名誤中，非本規則引入。另兩項殘留：(a) `國際奧會`／`國際奧委會` 置於強豁免，故「國際奧會公布2036奧運主辦城市名單」這類例行行政新聞會被保留——這是為救語料實例「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」（其 `解除處罰`／`重返` 都不在 `SPORT_GEO`，IOC 若降級為守門詞就救不回來）所付的代價，語料中 IOC 相關僅 1 則、爆炸半徑小；(b) `traceFinanceFeed()` 無兜底 DROP，不含 `SPORT` 詞的體育周邊新聞（如「球場踩踏事故釀30死」，`球場` 非 `SPORT` 成員）若來自 wantgoo／moneydj 仍會入庫，屬 Task 240 建立的既有邊界。**本規則僅於抓取時作用，不追溯清除已入庫舊列。**
- [ ] **濾除「影響不了經濟／股市／政局」的社會與軟性新聞（Task 240）**：使用者於 2026-07-27 連續回報 **4 則**實際爬進 `news_headline` 的雜訊，成因分屬**三個互不相同的結構性缺口**，故需三組修正。使用者原話：「這種社會新聞影響不了經濟、股市，爬蟲時要濾掉」「雖然屬於台灣六都的新聞，但是和財經完全無關，也影響不了政局發展」「這個教育界的新聞，沒有大到影響政局發展的話，也應該過濾」。
    - **缺口 A｜純財經來源完全豁免過濾**：「很多家庭急著分遺產 忘了另一位父母還活著」（`source=wantgoo`）。Task 199 起 `EditorialNewsFilter` **只套用於混合型 feed**（ltn／udn），wantgoo／MoneyDJ 因「本就財經專屬」而豁免；但玩股網 `all-headlines-by-category` 實際含理財生活軟文，故此類直接落庫。**修正：對純財經來源新增 `traceFinanceFeed()`／`retainForFinanceFeed()`，只套「三條凌駕 `FINANCE` 的否決集」（`isAnecdote`／`LOTTERY`／`ESTATE`），不套白名單 KEEP 邏輯、不套 `LIFESTYLE`／`SOCIAL_ODDITY`、不套 `DROP:non-finance-general` 兜底。** 此界線非任意：`LIFESTYLE`／`non-finance-general` 的設計前提是「`FINANCE` 白名單先判＝唯一豁免」，該白名單是為「從一般新聞版面挑出財經」而設，套到純財經站會把**其財經用語不在白名單內**的真財經新聞整批誤殺——語料實測若對 wantgoo 套整套 cascade，41 則遭濾者含「Waymo 傳終止合作 Uber 跌逾4%」「〈華德動能訪廠〉樂估日本電巴市占率上看3成」「基本工資調漲至3萬」「比特幣未來十年大預言」「舊制勞工也能自提6％到退休金專戶」等明確財經；若只拿掉 `FINANCE` 豁免而保留 `LIFESTYLE`，另誤殺「Burberry…股價摔」「A股午後大逆轉 中芯國際市值超越茅台」「TISA 開戶數近20萬 金管會」「〈房產〉青安3.0方案拍板」「記憶體價格暴漲300%」等 40+ 則；即使保留 `FINANCE` 豁免，`LIFESTYLE` 仍誤殺「雄獅東北亞賞楓行程銷售破5成」「《DJ在線》旅行社下半年團費趨穩」「諾和諾德怒告禮來」「Nike 大砍中國經銷商」等**消費／旅遊類股**報導，以及「Hugging Face 遭…沙盒『越獄』攻擊」（`SOCIAL_ODDITY` 的「越獄」誤中 AI jailbreak）。三條否決集之所以可跨來源套用，正因其語意即「**即使帶財經詞也無投資資訊量**」，與來源是否財經專屬無關。
    - **缺口 B｜`FINANCE` 白名單誤中，使社會軟文在規則①即被 `KEEP:finance` 攔下、後續所有否決規則失去發言機會**。三個成因與對應修正：
        - **子字串誤中**：「陽明交大教評會爆爭議 教育部長：組成有瑕疵」命中 `FINANCE` 的 `陽明`（＝陽明海運）。**修正：`陽明` → `陽明海運`，並配套把 `運價` 補進 `FINANCE`**。零誤殺已驗證——語料含「陽明」者 **10 則 ＝ 7 則陽明海運 ＋ 2 則陽明交大產學 ＋ 1 則教評會**：7 則陽明海運（美新關稅敲定後運價後市／運價走揚6月營收165.91億／航海節董座蔡豐明航運業升級／與PSA簽永續備忘錄／陽明海運與PSA供應鏈減碳／蔡豐明運價雖跌貨量仍滿後市視美關稅政策而定／陽明、台驊6月營運亮麗）**全部另含並集 `關稅`／`營收`／`航運`／`營運`／`集團`／`陽明海運`／`供應鏈` 之一而維持 `KEEP:finance`**，2 則陽明交大產學新聞（鴻海研究院矽光子／TSIA 半導體設備創新獎）亦另含 `鴻海`／`矽光子`／`半導體` 維持 `KEEP`，僅使用者回報的教評會爭議翻為 `DROP:non-finance-general`。**`運價` 是必要配套**：窄化 `陽明` 後，唯一公司訊號為「陽明」的航運標題會失去保護（形如「陽明7月運價走弱 貨量持平」），而 `運價`／`貨量` 原本都不在 `FINANCE`；語料實測 `運價` 11 則（標題 10 ＋ 僅摘要 1）全部為航運／空運財經新聞（SCFI 運價指數／長榮海營收創高／空運淡季不淡／航空雙雄貨運旺季／赫伯羅德上修全年財測〔`運價` 在摘要內——`trace()` 輸入為標題＋摘要〕），零歧義。此為既有註記「已移除『北市』避免誤中『竹北市』」同類陷阱。
        - **金額詞誤中（發票／彩券）**：「7-11開出千萬中獎發票　花150元買飲品成幸運兒」命中 `千萬`；「大樂透頭獎連17摃　加碼100萬獎只剩7組」命中 `加碼`。**不得以移除 `千萬`／`加碼` 修正**——實測 `千萬` 為唯一財經訊號者 17 則，**僅泰山捐三千萬、HH 草本公益捐助 2 則屬企業新聞**；不移除的理由是**移除解決不了問題**——「大樂透頭獎連17摃　加碼100萬獎」等 4 則移除 `千萬` 後仍靠 `加碼` 判 `KEEP:finance`，而 `加碼` 是「外資加碼／加碼投資」的核心財經語彙、移除風險更高。**修正：新增 `LOTTERY` 否決集，置於 `FINANCE` 之前。**
        - **家事遺產糾紛**：「很多家庭急著分遺產…」摘要含「存款」「房子要不要賣」故併入摘要後亦達 `KEEP:finance`（**標題單獨判為 `DROP:non-finance-general`，是摘要把它救回**——證明本規則必須凌駕 `FINANCE`，僅靠來源接線不足）。**修正：新增 `ESTATE_TOPIC` ∧ ¬`ESTATE_EXEMPT` 否決，置於 `FINANCE` 之前**，以法制（立院／修法／三讀）、稅制（國稅局／申報／遺產稅／贈與稅）、市場主體（台積電／股利／配息／經營權／接班）三類豁免保住「遺產可免分兄弟姊妹？立院朝野拍板 特留分修法」「被繼承人遺有應收股利 遺產稅申報一次看」「台積電配息創新高、繼承股票先別high！國稅局爆…」。
    - **缺口 C｜六都白名單無條件保留（規則⑧）**：「侯友宜、谷立言、片山和之同遊新北　搭船欣賞淡江大橋」不含任何財經／強政治訊號，僅因命中 `TW_WHITELIST_CITY` 的 `新北` 即被 `KEEP:tw-whitelist-city`。**修正：`LIFESTYLE` 增列觀光行程／禮儀性活動詞（同遊／搭船／宮廟／遶境／路跑／授旗／嘉年華／水樂園／合唱團／出家），沿用其既有「旅遊／景點／打卡」語意，不新增規則。** 因 `LIFESTYLE` 位於規則②（財經之後、中國／地緣／政治／城市之前），六都軟文得以在走到規則⑧前被攔下。**只有財經（規則①）豁免——`POLITY_STRONG` 位於規則⑤、在 `LIFESTYLE` 之後，強政治並不豁免**（Task 199 既有設計：「置於中國/地緣/政治/城市之前，使政治人物名／城市名／中國詞皆不能救回軟文」）；故政治人物的純禮儀性行程（授旗／路跑／水樂園）一併濾除，此為刻意取捨，符合使用者「影響不了政局發展」的判準。**`參拜` 刻意不收、只收 `宮廟`**：裸 `參拜` 會在規則②攔下「日相參拜靖國神社 中國外交部強烈抗議」這類真正牽動中日關係與市場的事件（規則③ CHINA／⑤ POLITY_STRONG 都在其後、無從救回），而使用者回報的「李四川參拜宮廟」已由 `宮廟` 命中。
    - **刻意排除的候選詞（經 4807 則真實語料逐詞實測會誤殺，勿加入）**：`家產`（誤中「國**家產**業園區」→ 誤殺「三星…龍仁半導體國家產業園區首座晶圓廠量產提前」）、`分產`／`爭產`（`充分產能`／`部分產品`／`競爭產業` 子字串風險，且語料中該類標題已由 `遺產` 命中，冗餘）、`開獎`（財經媒體借喻財報公布 → 誤殺「AI巨頭財報前瞻一表看！微軟、Meta、蘋果下周**開獎** 聚焦資本支出」）、裸 `發票`（誤殺「政府挺團體訴訟求償！陳時中：食安基金補助打官司 退貨持**發票**憑證僅空瓶也收」；改以 `發票` ∧ `開出` 的 AND 組合承接「7-ELEVEN 開出一張千萬、七張百萬發票」）。`特別獎`（有 `特別獎金` 子字串風險，會誤殺「台積電發放特別獎金 每人平均逾百萬」這類年終／績效獎金標題；且該類標題已由 `統一發票`／`獎號` 命中，冗餘）、`集團`（不列入 `ESTATE_EXEMPT`——語料中與「遺產／繼承」零共現、無證據支撐，且它是高頻泛詞〔詐騙集團／犯罪集團〕又同時存在於 `FINANCE`，收了會讓「詐騙集團騙走老翁遺產」落回 `KEEP:finance`）。**語料零樣本的推測詞一律不納入**（對獎／威力彩／今彩／四星彩／三星彩〔且 `三星彩` 有 Samsung `三星` 疑慮〕／連摃／摃龜／槓龜／遊船／遊河／進香／園遊會／剪綵／揭幕／踩線），比照 Task 229 紀律——最終 `LOTTERY` 8 詞（統一發票／中獎／獎號／兌獎／刮刮樂／幸運兒／頭獎／大樂透）**每一個在語料中都有實際樣本**。
    - **零誤殺驗證（對線上 4807 則真實 `news_headline` 台灣新聞全量對照；baseline＝現況＝ltn／udn 走 `trace()`、wantgoo／moneydj 完全不過濾。語料每日成長，重跑時筆數與則數會略增，結論不變）**：**新增濾除 26 則、零反向翻轉**（無任何現況 `DROP` 被放回 `KEEP`），逐則檢視全為應濾除者——`DROP:lottery` 13 則（大樂透×5〔ltn 連17～20摃＋wantgoo 連槓19期〕、統一發票千萬獎×3、開出發票×3〔7-11／7-ELEVEN／全家〕、刮刮樂×1、尋找中獎人×1）、`DROP:lifestyle` 8 則（同遊新北搭船、參拜宮廟、動畫路跑、水樂園×2、川友會授旗、兒童合唱團、佛光山出家）、`DROP:family-estate` 4 則（分遺產、繼承1500萬丟股市、阿公4千萬遺產、孫女數百萬美元遺產）、`DROP:non-finance-general` 1 則（陽明交大教評會）。純財經來源僅濾 **3 / 1219**（使用者回報的分遺產＋統一發票開獎＋大樂透），其餘 1216 則全數維持收錄。**已知殘留（刻意接受）**：純財經來源的體育賽事軟文仍會入庫（約 4／1219：奧運處罰、世足 AI 押注×2、川普出席世界盃決賽）；正解是日後在 `traceFinanceFeed` 加一條**窄**的體育賽事否決集，而非改套整套 `LIFESTYLE`（後者會誤殺消費／旅遊／運動品牌類股報導）。單元測試 `EditorialNewsFilterTest` 補 `@Nested` 錨點涵蓋 4 則使用者案例判 DROP，以及上述必保回歸錨點判 KEEP（含靖國神社、特別獎金、陽明運價、詐騙集團奪產）。**本規則同 `EditorialNewsFilter` 僅於抓取時作用，不追溯清除已入庫舊列**（30 天保留期自然滾出）。
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
- [x] **收尾 poller 於 virtual thread 組態下的可靠執行（bug fix，Task 246）**：本服務 `spring.threads.virtual.enabled=true`；Boot 為 `@Scheduled` 配置的 virtual-thread `SimpleAsyncTaskScheduler` 下，`fixedDelay` 的批次收尾 poller（`pollBatches`）實測**不會週期執行**——已 `ENDED` 批次因而永遠停在 `PROCESSING`、無法落 `OK`／寄信，連 12h 逾時判 FAILED 都不觸發（clock 執行緒存活、日誌全無 finalize/例外，重啟仍複現）。修法：定義專用平台執行緒 `TaskScheduler` bean（`SchedulingConfig`，`ThreadPoolTaskScheduler` pool=3）使 Boot virtual 排程器退讓，所有 `@Scheduled` 改跑平台執行緒（`fixedDelay` 正確重排 + 避免排程內同步呼叫 Anthropic SDK 時 virtual thread 阻塞 + 多執行緒不互相餓死），Web 層仍維持 virtual threads。並為 Anthropic client 設 90s 請求逾時（`AnthropicOkHttpClient.builder().timeout(...)`）加固，避免單次 retrieve／results 呼叫因連線半開而長時間阻塞排程執行緒。
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
- [ ] **「股票（即時）」增列 ETF 淨值與折溢價欄（Task 214）**：「當前即時資產」分頁的「股票（即時）」區塊，於末尾再增列 3 欄 `淨值／折溢價(%)／淨值時間`（完整欄序 0–20）。折溢價＝(市價 − 淨值) / 淨值，以百分比數值呈現（`1.2` 表示溢價 1.2%，負值為折價）。
  - **只有 ETF 有值，個股留白**：折溢價是「市價 vs 基金淨值」的概念，個股（如 2330、GOOGL）沒有淨值，該列三欄一律留白，不補 0、不補「N/A」。
  - **資料驅動判定，不維護 ETF 白名單**：台股以「代號是否出現在證交所全市場 ETF 名冊」判定、美股以「Yahoo 是否回傳 `navPrice`」判定——兩者都是資料本身即答案。**刻意不複用既有 `isEtf()` 白名單**：該白名單有三份複本且都誤將個股 AVGO 列為 ETF、又都漏掉使用者實際持有的 SGOV，沿用會第一天就錯列。
  - **台股來源**：證交所 MIS 全市場 ETF 彙整檔（`all_etf.txt`），一次 request 涵蓋上市與上櫃（含 3 檔上櫃債券 ETF），取盤中即時預估淨值（iNAV）與**證交所已算好的折溢價欄**。**折溢價一律直接取用該欄，不得自行以 (市價−淨值)/淨值 重算**（淨值欄在股票型被四捨五入至 2 位，重算誤差達 0.07 個百分點）；**亦不得使用「前一交易日淨值」欄計算**（該欄對全部檔位皆為 T-1，實測台股重挫日會把 0050 的 +1.2% 溢價算成 −5.8% 折價）。
  - **美股來源**：Yahoo `quoteSummary` 的 `navPrice`（與 Vanguard／iShares 官方淨值實測完全吻合）。Yahoo 不提供折溢價欄，故**由匯出端以「該列自己顯示的即時價」與淨值計算**，確保列內自洽——若改在抓取端以 Yahoo 自己的市價計算，會與匯出列的即時價（走 Redis，來源與時點皆不同）對不起來（實測 VOO 相差 0.11%，使用者拿本列數字驗算會兜不攏）。**亦不得改用同回應的 `previousClose`**（那是 T-1，會把 VOO 真實 +0.003% 溢價放大成 +1.02%）。
  - **淨值時間欄的必要性**：淨值有其資料時點（台股為盤中 iNAV 的當下時分、美股為前一交易日收盤），與匯出時點不必然同日；若不揭示，使用者無從分辨「今天的折溢價」與「上一個交易日殘留值」。故第三欄輸出資料時點，語意為「最近一次取得的淨值」而非「與匯出同步的即時值」。
  - **抓取失敗不補值**：外部來源失敗時保留 Redis 內上一輪的值、不覆寫也不清空；若連上一輪都沒有則留白。留白與「本來就不是 ETF」在表面上相同，此為刻意取捨（不為區分兩者而在數字欄位填入字串）。
- [ ] **ETF 淨值與折溢價每日入庫留存（Task 215）**：Redis 只保留最新一筆（TTL 96 小時）供匯出即時取用，**每日抓到的淨值與折溢價另須寫入 `etf_nav_history` 長期留存**，以便日後回看折溢價走勢（例如「這檔平常溢價 0.5%，現在 2% 是不是買貴了」）。
  - **每檔每日一列**：`(代號, 市場, 資料日)` UNIQUE；同日多次抓取覆寫同一列，故當日最終值＝當日最後一次抓取的值。台股於收盤後（17:30）再抓一次，確保該日入庫值為**收盤折溢價**而非停在盤中某個瞬間。
  - **資料日以來源自帶時點為準**：台股取彙整檔的資料日期欄、美股取該筆報價的紐約當地日期；解析不出資料日則**不入庫**，不以「今天」代入（跨日或休市時抓取會掛到錯誤日期）。
  - **不存市價**：同一事實已在 `stock_price_history.close_price`，跨資料表重複儲存違反完整正規化。
  - **折溢價原樣保存、不由淨值反推**：台股該值為證交所發布的權威數字，且其淨值欄在股票型 ETF 已四捨五入至小數 2 位，反推誤差達 0.07 個百分點、與證交所公告對不起來。此為刻意保留的來源值（比照歷史快照匯總欄位的 denormalization 例外）。
  - **入庫失敗不影響即時功能**：只記 log，不中斷 Redis 寫入與整輪排程。
- [ ] **折溢價的取得方式必須可從資料本身分辨，且台股不得反推（Task 259）**：前一條「折溢價原樣保存、不由淨值反推」目前**在實作上是破的**——`EtfNavFetchClient` 對「淨值欄 `f` 有值、折溢價欄 `g` 留白」的判斷是 `if (nav == null && pct == null) continue;`（**AND**），故該筆仍會進入下游；`EtfNavPoller.resolvePct()` 隨即以 `stock_price_history` 同日收盤價自行反推，而寫入 `etf_nav_history.source` 的值仍是 `'TWSE'`，**DB 上無從分辨這一列是證交所公告值還是本系統反推值**。美股更是 100% 走反推（Yahoo 不提供折溢價欄），同樣標記為 `'Yahoo Finance'`。故：
  - **`source` 與「折溢價怎麼來的」是兩個獨立軸，不可共用一欄**：`source` 維持既有語意＝提供淨值的站台（`TWSE`／`Yahoo Finance`，未來可能新增），既有 186 列的值一律不改寫；另立 `etf_nav_history.pct_origin` 表達折溢價的取得方式，值域 `OFFICIAL`（來源直接公告）／`RECONSTRUCTED`（本系統以收盤價反推）／`NULL`（該列無折溢價）。**此為資料來源標記、非使用者可自訂的業務分類**，故不入設定表、不做管理端點（比照既有來源標記欄位的慣例）。
  - **台股一律不反推**：`g` 欄留白時 `premium_discount_pct` 與 `pct_origin` **皆寫 `NULL`**，不得以 `(收盤價−淨值)/淨值` 補值。理由同上一條 AC（證交所淨值欄在股票型 ETF 已四捨五入至 2 位，反推誤差達 0.07 個百分點，與公告值對不起來），且「留白」是誠實狀態、優於一個對不起來的數字。
  - **美股保留反推但必須誠實標記**：Yahoo 不提供折溢價欄，反推是該市場唯一的取得方式，若一併關閉會讓 Task 214 的功能整個消失。故美股續行反推，但 `pct_origin` 必須為 `RECONSTRUCTED`。**本項驗收範圍僅止於 `etf_nav_history` 入庫路徑的來源標記**；Excel 匯出檔（Task 214 的「股票（即時）」折溢價欄）目前直接讀 Redis、不經 `etf_nav_history`，欄位是否需要另外揭露重建值屬 UI／匯出契約層面的擴充，不在本任務範圍，留待後續任務評估。
  - **重建值不得覆寫權威值**：`upsertEtfNav` 目前是「先 SELECT id、有就無條件 UPDATE」，沒有任何守門。必須加上：同一 `(代號, 市場, 資料日)` 已存在 `pct_origin = 'OFFICIAL'` 的列時，**不得**以 `RECONSTRUCTED` 的折溢價覆寫該欄。淨值本身仍可更新（同日多次抓取取最後一次，維持上一條 AC 的語意）。
  - **匯出端同一分流**：`ExcelExportService` 的折溢價計算亦不得對台股反推。該處與 `EtfNavPoller.resolvePremium()`（Task 259 由 `resolvePct` 改名）的分子刻意不同（匯出用該列當下的即時價、入庫用與淨值同一交易日的收盤價）——這是「盤中折溢價」與「收盤折溢價」兩個不同事實，**刻意保留兩份計算**，但「台股不反推」這條規則兩處必須一致。

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

---

### Requirement 43: 今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助

**User Story:** 作為台股投資人，我希望在「股市綜合分析」下查看最新大盤風險與我目前持股／觀察股票的買進、續抱、觀望、減碼或出場候選訊號，並清楚看到每個判斷使用的均線、KD、確認條件、資產類別與資料日期；配息型標的不得因除息缺口被誤判為趨勢跌破，債券 ETF 也不得直接套用股票大盤風險；所有判斷都由本地固定規則產生，不呼叫任何 AI API，讓功能沒有模型費用且能重現與回測。

**Acceptance Criteria:**

- [ ] **選單與頁面**：在左側「股市綜合分析」群組新增「今日交易雷達」（route `/trading-radar`），置於「今日股市分析」之後、「股票觀察」之前。頁面上方顯示台股大盤風險卡，下方顯示台股標的決策表；提供重新整理按鈕，但不提供自動下單。使用者在個股決策表任一資料列雙擊時，須以該列的 `stockCode`、`stockName`、`market` 開啟跨頁共用的 `StockAnalysisDialog` 股票分析圖；雙擊展開欄與資料欄皆適用，通知設定按鈕維持原本單擊行為。
- [ ] **零 AI API**：本功能不得注入或呼叫 `MarketAnalysisService`、Anthropic、OpenAI 或其他 LLM client；不得因重新整理而觸發公開資訊爬蟲或外部行情抓取。後端只讀現有 PostgreSQL 與 Redis：大盤讀 `twse_index_daily_history`，標的技術歷史讀 `stock_price_history`、除權息事件讀 `stock_dividend_history`、有效資產類別讀 `stock` 並經 `AssetClassifier` 判定，最新價透過既有 `PriceQueryService`（Redis miss 才退回同一歷史表）。既有外部行情排程仍可獨立更新資料，但不是本頁請求鏈的一部分。
- [ ] **標的範圍與租戶隔離**：個股清單＝當前使用者「最新資產快照仍持有的台股」∪「股票觀察（`stock_alert` 衍生）的台股」，依 `(stockCode, market)` 去重；`0000/台股` 只作大盤卡、不重複列在個股表。美股／英股第一版不評分，回傳略過檔數並於頁面說明。最新快照與觀察清單皆沿用既有 `ownerFilter`，不得跨使用者洩漏標的。
- [ ] **同義指標同一來源與還原權息（`TW_RULES_V3`）**：MA20／MA60／MA240／KD 仍由 `TechnicalIndicatorService` 的同一計算核心產生，不在 BFF 或前端重算；交易雷達先把最近完成日 K（以及尚未入庫的當日 live K）與 `stock_dividend_history` 的有效除權息事件組成單一「還原權息 OHLC」序列，再以該同一序列計算 MA、KD、最近 241 根完成日 K 的兩日確認，以及規則內部的單日漲跌幅（含 ±5% 扣分與逆勢「停止續跌」）。現金配息與股票股利因子依事件日套入、整段縮放至最新一日仍等於原始現價；沒有有效事件時數值須與原始序列完全相同。不得讓 MA／KD 用還原值、兩日確認或規則漲跌幅卻用原始值。API／畫面的 `changePercent` 仍顯示市場報價原始漲跌，規則內部值不另存。完成日不足 `period + 1` 根時確認仍為 `UNAVAILABLE`。
- [ ] **資產類別感知（`TW_RULES_V3`）**：有效資產類別由 `stock.asset_class` override 優先、否則 `AssetClassifier.classifyStockByRule(code, market)`；API 每檔回傳 `assetClass` 與 `distributionAdjusted` 供畫面揭露。`STOCK` 維持 V2 的台股大盤加減分、`RISK_OFF` 買進閘門與資料不足 veto；`BOND` 不以台股大盤 regime 加減分、不受 `RISK_OFF` 買進閘門限制，且大盤 `DATA_INCOMPLETE` 不得單獨 veto 債券標的。未知類別保守視為 `STOCK`。
- [ ] **大盤風險分數（規則版本 `TW_RULES_V3`）**：起始 50 分；最新點位相對 MA20／60／240 分別 `±8／±12／±15`；MA60、MA240 連續兩收盤日在均線上／下各 `±5`；KD `K>D` 加 5、否則減 5；單日跌幅 ≤ -3% 再減 10、單日漲幅 ≥ 3% 加 3；最後 clamp 至 0–100。資料齊全時 `score >= 65`＝`RISK_ON`、`40–64`＝`NEUTRAL`、`<40`＝`RISK_OFF`；必要欄位或歷史不足則 `DATA_INCOMPLETE`，不得把不完整資料假裝成中性。V3 的大盤分數與 V2 完全相同。
- [ ] **標的分數與大盤閘門（規則版本 `TW_RULES_V3`）**：起始 50 分；最新價相對 MA20／60／240 分別 `±8／±12／±15`；MA20／60／240 連續兩收盤日在均線上／下分別 `±5／±8／±10`；KD `K>D` 加 5、否則減 5，且 `K>D` 且 K、D 皆低於 20 再加 3，K、D 皆高於 80 減 3；僅 `STOCK` 套用大盤 `RISK_ON` 加 8、`RISK_OFF` 減 15；`BOND` 此項為 0；標的單日漲幅 ≥ 5%（避免追高）減 3、跌幅 ≤ -5% 減 5；最後 clamp 至 0–100。必要技術資料不足一律 `NO_TRADE`；大盤 `DATA_INCOMPLETE` 只 veto `STOCK`。V3 其餘分數與主建議門檻沿用 V2。
- [ ] **動作映射**：分數 ≥ 75 且 MA20／MA60 皆完成「連續兩收盤日在均線上」時，未持有標的＝`BUY_CANDIDATE`（買進候選）、已持有＝`ADD_CANDIDATE`（加碼候選）；其餘 `score >= 55` 時已持有＝`HOLD`、未持有＝`WATCH`；`40–54` 時已持有＝`HOLD_CAUTION`、未持有＝`WAIT`；`25–39` 時已持有＝`REDUCE_CANDIDATE`、未持有＝`AVOID`；`<25` 時已持有＝`EXIT_CANDIDATE`、未持有＝`AVOID`。`STOCK` 在大盤 `RISK_OFF` 時仍禁止 `BUY_CANDIDATE`／`ADD_CANDIDATE`；`BOND` 不套用此股票大盤閘門。
- [ ] **逆勢抄底狀態（V3，獨立於主建議）**：每檔另回 `NONE`／`OVERSOLD_WATCH`（超跌觀察）／`TRIAL_CANDIDATE`（逆勢試單候選），不得改寫 `score` 或 `action`。基礎 setup 必須同時滿足：(1) 最新價高於 MA240；(2) MA240 已連續兩日站上；(3) MA20、MA60 已連續兩日跌破；(4) K<20。成立即為 `OVERSOLD_WATCH`；只有再同時滿足 D<20、前一期 `K<=D` 而本期 `K>D`（低檔黃金交叉）、且本日漲跌幅 `>=0`（停止續跌），才升級 `TRIAL_CANDIDATE`。前一期 K/D 由還原權息後的同一 KD9 序列、排除本期後計算；不足時不得假裝交叉。`STOCK` 在大盤 `RISK_OFF` 時須明示「只限小額分批、主建議仍優先」風險；`BOND` 不顯示股票大盤風險文案；年線結構失守或資料不足一律 `NONE`。
- [ ] **可解釋與資料新鮮度**：每筆回傳分數、動作、是否持有、`assetClass`、`distributionAdjusted`、現價、漲跌幅、MA20／60／240、K／D、三條均線的兩日確認狀態、支持理由、風險提醒、行情更新時間與完成日 K 的 `asOfDate`。畫面在標的名稱下揭露「債券」與「還原權息」標記，並固定顯示「規則式決策輔助、非獲利保證；送單前請自行確認價格與部位」聲明。
- [ ] **盤中自動更新**：`TradingRadarView` 沿用儀表板的 `/api/market-data/prices/stream` SSE。收到目前清單內的台股 `price-update` 時，先立即更新該列現價、漲跌幅與行情時間；同一批事件以短暫 debounce 合併後，背景重讀 `GET /api/bff/trading-radar`，重新計算 MA、KD、分數、規則建議、理由與風險，不顯示整頁 loading、不得呼叫行情 refresh 或 AI API。離開頁面須關閉 SSE 並清除計時器；一般斷線沿用 `EventSource` 自動重連，永久關閉時延遲重建連線；手動「重新整理」保留。
- [ ] **無持股／無觀察與失敗降級**：清單為空時顯示引導使用者至「股票觀察」新增標的；單一股票資料不足只將該檔標為 `NO_TRADE`，不得使整頁失敗；大盤資料不足仍回頁面結構與個股 `NO_TRADE`，不得回 5xx。
- [ ] **一頁一 BFF**：新增 business `GET /api/trading-radar` 與頁面專屬 BFF `GET /api/bff/trading-radar`；前端只呼叫 BFF。此 GET 無寫入、無排程、無新資料表。
- [ ] **驗證**：規則引擎須有單元測試覆蓋分數 clamp、兩收盤日確認、買進門檻、大盤 `RISK_OFF` 禁買、持有／未持有動作映射與資料不足 `NO_TRADE`；backend、BFF、frontend 均須建置成功，部署後確認頁面可開且請求鏈沒有 AI API 呼叫。

**Requirement 43 修訂（規則版本 `TW_RULES_V4`，Task 217）—— 判斷邏輯強化：**

- [ ] **大盤 regime 新鮮度閘門**：大盤分數與 regime 來源 `twse_index_daily_history` 只有完成日資料，盤中永遠是前一交易日的值（`0000/台股` 被 `StockSourceQuery` 明確排除於即時抓價之外，Redis 無大盤即時價可用——**事實更正，Task 249 逐行查證**：該排除只套在 `stock_alert` 那半段，`stock_holding` 那半段沒有，呼叫端不得倚賴它作為保證）；而個股走 Redis 即時價。兩者混用會在指數盤中重挫當日仍以昨日 `RISK_ON` 放行買進候選。故 `MarketSummary` 增加 `stale` 旗標：**大盤最新完成日 K 的日期不等於「當前台股交易日」時視為 stale**。stale 時：(a) 不給個股 `RISK_ON` 的 +8 加分；(b) 買進閘門一律關閉，不得產生 `BUY_CANDIDATE`／`ADD_CANDIDATE`；(c) `RISK_OFF` 的 −15 扣分與 veto **仍然生效**（保守方向不放寬）；(d) 前端於大盤卡明示「大盤為前一交易日資料，今日買進訊號暫停」。不得以「補一個即時大盤來源」規避——本 Requirement 明訂零外部行情抓取。
- [ ] **交易日判定沿用既有交易日曆**：判斷「當前台股交易日」須使用既有的交易日曆服務（`isTradingDay`），不得自行以星期幾推算，也不得新建第二份假日清單。非交易日（週末／國定假日／颱風假）時，以最近一個交易日為基準日，不因休市而誤判 stale。
- [ ] **逆勢「停止續跌」改用完成日 K**：`TRIAL_CANDIDATE` 的 `stabilized` 條件原為「盤中漲跌幅 >= 0」，盤中在 0% 附近的零交叉會讓 `OVERSOLD_WATCH ↔ TRIAL_CANDIDATE` 逐 tick 翻轉。改為以**最近一根完成日 K 的漲跌幅 >= 0** 判定；盤中不因即時價變動而反覆升級／降級。原「不得只用當期 K>D 冒充黃金交叉」的約束不變。
- [ ] **暫時性失敗不得偽裝成判斷結果**：`buildMarket`／`buildStock` 的 `catch` 目前把 Redis 逾時、DB 查詢中斷等暫時性失敗轉成 `NO_TRADE`／`DATA_INCOMPLETE` 的正常回傳值，與「資料真的不足」無法區分。`StockDecision`／`MarketSummary` 增加 `degraded` 旗標標示「因讀取失敗而降級」（相對於「資料量不足」），供通知鏈判斷是否應跳過（見 Requirement 44 修訂），前端亦須以不同文案區分兩者。
- [ ] **還原權息長窗偏誤的揭露**：`DistributionAdjustedPriceService` 採「以最新價為錨、往回縮小歷史價」的 back-adjustment，數學上等同總報酬序列。對高配息標的（尤其月配息債券 ETF），此序列在市價完全不動時仍呈上升，使最新價恆高於長窗均線、取得全部均線分數。此為**已知且刻意保留**的行為（除息缺口的修正效益大於此偏誤），但必須揭露：`StockDecision` 增加 `distributionAdjustedYieldPct`（該 240 日視窗內還原累積幅度），前端於「還原權息」tag 的 tooltip 說明「長期均線已含配息累積，位置分偏多」。**不得**因此取消還原（會讓除息日回到假跌破）。

**Requirement 43 修訂（規則版本 `TW_RULES_V5`，Task 223）—— 長期因子併入總分與分數飽和修正：**

- [ ] **問題陳述（本次修訂的動機）**：V4 的評分因子 100% 為技術面，且時間尺度權重失衡——短期側（MA20 位置 ±8、MA20 確認 ±5、KD ±5(+3/−3)、大盤 regime +8/−15、單日漲跌 −3/−5）為 `+29 / −41`（正負不對稱，下檔更重），中期側（MA60 ±12＋確認 ±8）±20，長期側（MA240 ±15＋確認 ±10）僅 ±25。結構上短中期永遠壓過長期，導致「長期趨勢完好、短期回檔」的標的被判為減碼／出場候選。實測 2026-07-17：0050（現價 100.15、MA240 73.63）與 006208（現價 227.75、MA240 167.26）年線位置分皆為正，仍雙雙得 25 分並輸出 `REDUCE_CANDIDATE`。且評分完全沒有公司體質資訊，個股的 25 分無法區分「基本面崩壞應出場」與「短期錯殺應加碼」——兩者正確動作相反。
- [ ] **分數飽和必須先修（本修訂的前置條件）**：現行「base 50 ＋ 各因子直接加減 ＋ 硬 clamp 0–100」的理論值域為 `−36 ~ +124`（Task 217.8 實測 `strongStock` 原始分 121，為該值域內的單一觀測值），超出 `[0,100]` 的部分被 clamp 吃掉，使後加因子在強勢標的上完全失效。**在飽和修正完成前不得加入任何新因子**。V5 改為**分組加權正規化**：各子因子先標準化為 `[-1, +1]` 的貢獻值，組分數＝組內子因子的加權平均（同樣落在 `[-1, +1]`），最終 `score = 50 + 50 × Σ(組權重 × 組分數)`。因 `Σ(組權重) = 1` 且每個組分數 ∈ `[-1,+1]`，故 `Σ ∈ [-1,+1]`、`score ∈ [0,100]` **恆成立且無例外**。clamp 保留為防禦性斷言，但**斷言對象是 `Σ` 而非 `score`**：`|Σ| > 1 + 1e-9` 才代表權重或標準化有 bug 而須寫 WARN log。**`score = 0` 與 `score = 100` 是合法極值不得告警**——全部子因子皆 `−1` 且大盤 `RISK_OFF` 時 `Σ = −1.00` → `score = 0`，屬設計預期。（上界不對稱：`RISK_ON` 只給 `+0.5`，`Σ` 上限為 `0.95` → `score = 97.5`。）
- [ ] **五個因子組與權重（大盤 regime 為第五組，不得置於正規化之外）**：短期動能組 `22%`（MA20 位置、MA20 兩日確認、KD、單日漲跌幅）、中期趨勢組 `18%`（MA60 位置、MA60 兩日確認）、長期趨勢組 `30%`（MA240 位置、MA240 兩日確認、年線斜率、52 週相對位置、3 年年化報酬）、基本面組 `20%`（EPS 年增率、月營收 YoY、ROE、PE 自身歷史分位）、大盤環境組 `10%`（單一子因子：regime）。合計 `100%`。**大盤 regime 必須作為因子組納入正規化，不得改為「正規化完成後再加減分」的調整項**——後者會使值域變成 `[-10,105]` 而重現本修訂正要消滅的飽和問題。大盤環境組的組分數：`RISK_ON` → `+0.5`、`NEUTRAL` → `0`、`RISK_OFF` → `−1`（不對稱是刻意的，沿用 V4「保守方向不放寬」原則）；沿用 V4 的 stale 閘門：大盤 stale 時 `RISK_ON` 一律視為 `NEUTRAL`（不給正貢獻），`RISK_OFF` 的負貢獻與買進 veto 仍生效。`BOND` 的大盤環境組為 `null`（沿用 V3「債券不套台股大盤」語意），觸發權重重分配。全部權重為具名常數，不得散落於計算式內。
- [ ] **組內子因子權重（缺此數字則公式不可實作）**：長期趨勢組——MA240 位置 `0.25`、MA240 兩日確認 `0.15`、年線斜率 `0.25`、52 週相對位置 `0.15`、3 年年化報酬 `0.20`。短期動能組——MA20 位置 `0.35`、MA20 兩日確認 `0.25`、KD `0.25`、單日漲跌幅 `0.15`。中期趨勢組——MA60 位置 `0.60`、MA60 兩日確認 `0.40`。基本面組——EPS 年增率 `0.30`、ROE `0.30`、月營收 YoY `0.20`、PE 自身歷史分位 `0.20`。大盤環境組——單一子因子權重 `1.00`。各組組內權重合計皆為 `1`；子因子缺值時於該組內按比例重分配（與組層級的重分配規則相同）。
- [ ] **缺資料時權重重分配，不得以 0 分代替**：任一子因子資料不足時回 `null` 並排除於組內加權平均之外；整組全部子因子皆 `null` 時，**該組權重按比例重新分配給其餘有資料的組**，使實際生效權重總和恆為 `1`。嚴禁以 `0`（＝中性）代替缺值——否則 ETF（無基本面）與新掛牌標的（歷史不足）會被系統性拉向 50 分而低估。實測 `stock_price_history` 現況：主流標的有 2400+ 個交易日（2016-07 起滿 10 年），但 009804 僅 308 筆（2025-04-16 起）、00929 僅 753 筆（門檻 750，為臨界值）故「3 年年化報酬」對新掛牌 ETF 必然為 `null`，此路徑必須實測覆蓋。（00919 有 905 筆，已滿足 750 門檻，可作為臨界值的對照組。）
- [ ] **基本面組的實際生效時程必須揭露（不得宣稱上線即完整生效）**：基本面資料來源只提供當期快照且無合規歷史回補管道（見 Requirement 46），故各子因子自資料抓取上線起的可用時間為——月營收 YoY 需 3 個月、ROE 需 4 季（約 1 年）、PE 自身歷史分位需 250 交易日（約 1 年）、EPS 年增率需 8 季（近四季 vs 前四季，約 **2 年**）。因此：**抓取上線後前 3 個月基本面組四項全為 `null`（整組不生效），所有個股與 ETF 一同走權重重分配路徑；第 1 年僅 2 項生效；滿 2 年才完整。** 此時程須於前端可解釋性文案揭露，不得讓使用者誤以為分數已含完整基本面判斷。
- [ ] **長窗因子的前置條件：還原序列必須先支援股票分割**：`DistributionAdjustedPriceService` 現行只處理現金股利與股票股利（`validEvent()` 僅接受 `cashDividend`／`stockDividend` 為正的事件，事件來源 `stock_dividend_history` 亦只有這兩個欄位），**完全不處理股票分割**。現行 241 根取數視窗恰好落在 0050 的 1:4 分割（實測 2025-06-18，收盤 `188.65 → 47.57`）之後，故此缺陷至今未爆；長窗因子把視窗擴大到 750 根後會跨過分割，使 3 年年化報酬算成 `−7.6%`（正確值 `+46.7%`）——**方向相反且不拋任何例外**。故：**分割還原未完成前不得擴大取數視窗**。分割偵測採序列啟發式：`ratio = prevClose / close`，`≥ 2.0`（跌幅 ≥ 50%）判為正向分割、`≤ 0.5`（漲幅 ≥ 100%）判為反向分割，再以「比例須接近 `{2,3,4,5,10}` 之一、誤差 ≤ 10%」二次驗證，不通過則維持原值並記 WARN。**門檻不得設在 ±15% 之類的小值**——實測全台股序列中 ±15% 以上的跳空共 21 筆，其中僅 2 筆為真分割（`0050 −74.8%`、`2327 −73.8%`），其餘 19 筆幅度在 `−22% ~ +47%`（停牌復牌、資料源缺日等），小門檻會產生大量誤報而把序列改壞。另須排除 `close_price <= 0` 的列（實測台股 176 筆），否則會除以零或使 52 週相對位置恆為 `+1`。
- [ ] **短期動能組與中期趨勢組子因子定義**：(a) **MA20／MA60 位置**：現價高／低於該均線 → `+1`／`−1`，相等 `0`；(b) **MA20／MA60 兩日確認**：`ABOVE`／`BELOW` → `+1`／`−1`，`MIXED` → `0`，`UNAVAILABLE` → `null`；(c) **KD 動能**：`clamp((K − D) / 10, −1, +1)`，表達短線轉強或轉弱；K 或 D 為 `null` 時回 `null`。(d) **KD 位置**：`clamp(−(avg(K, D) − 50) / 50, −1, +1)`，表達超買（負）或超賣（正）；**但 9 日高低帶寬度 `(hi9 − lo9) / lo9 < 2%` 時一律回 `null`**（見下條窄幅失真）。(e) **單日漲跌幅**：`≥ +5%` → `−1`（避免追高）、`≤ −5%` → `−1`（波動風險升高）、其餘 `0`。**此子因子的值域刻意為 `[−1, 0]`、沒有 `+1`**——「單日異常波動是風險訊號、雙向皆負」是 V4 既有設計（V4 為 `−3`／`−5` 的純扣分），本修訂不改變此語意；驗證「全部子因子皆 `+1`」的極端輸入時，此項的最大值為 `0`。
- [x] **KD 必須拆成「動能」與「位置」兩個正交子因子（不得合併為單一數值）**：V4 把兩者混在一組加減分裡（`K>D` +5／否則 −5、`K、D 皆 <20` 再 +3、`K、D 皆 >80` 減 3），語意上是錯的——`K=90.8, D=86.0` 與 `K=26.1, D=20.7` 同樣是 `K>D`，但前者是「漲多了」、後者是「跌深反彈」，方向完全相反。任何把兩者壓進單一數值的設計都會保留這個缺陷：實測若採 `base=(K−D)/10` 再對超買扣固定值 `−0.3`，`K=90.8／D=86.0` 得 `+0.48−0.3=+0.18` **仍為正貢獻**，過熱依然沒被正確表達。拆分後兩檔實際標的的貢獻為——00719B（K 90.76／D 86.01）：動能 `+0.48`、位置 `−0.77`；00679B（K 26.1／D 20.7）：動能 `+0.54`、位置 `+0.53`。前者「轉強但嚴重過熱」、後者「跌深轉強」，正確區分。
- [ ] **窄幅標的的 KD 位置必須失效（指標誤用防護）**：KD 量的是價格在 9 日高低帶中的位置，帶寬過窄時 KD 會在雜訊上飽和。實測 00719B（元大美債1-3）近 60 個交易日價格區間僅 `3.096%`、日報酬標準差 `0.217%`、**9 日高低帶平均寬度僅 `1.011%`**——其 `K=90.76` 實質只代表「比 9 日低點高 0.37 元」。把股票用的 80／20 閾值原封不動套用在此類標的上屬指標誤用。故 `(hi9 − lo9) / lo9 < 2%` 時 KD 位置子因子回 `null`，由組內權重重分配吸收；**KD 動能不受此限制**（方向訊號在窄幅下仍有意義，只是幅度小）。此閾值為具名常數。
- [x] **短期動能組的組內權重（因 KD 拆分而重配）**（實際落地採扁平權重，見 Requirement 47）：MA20 位置 `0.30`、MA20 兩日確認 `0.20`、KD 動能 `0.15`、KD 位置 `0.20`、單日漲跌幅 `0.15`，合計 `1.00`。KD 位置的權重高於動能，因為「現在貴不貴」對進出場決策的影響大於「這兩天有沒有轉強」。
- [ ] **長期趨勢組子因子定義**：(a) **MA240 位置**：現價高／低於 MA240 → `+1`／`−1`，相等 `0`；(b) **MA240 兩日確認**：`ABOVE`／`BELOW` → `+1`／`−1`，`MIXED` → `0`，`UNAVAILABLE` → `null`；(c) **年線斜率**：`(MA240(今日) − MA240(60 交易日前)) / MA240(60 交易日前)`，`≥ +5%` → `+1`、`≤ −5%` → `−1`、之間線性內插，需 300 根完成日 K 否則 `null`；(d) **52 週相對位置**：`(現價 − 52 週最低) / (52 週最高 − 52 週最低)` 映射為 `(pos − 0.5) × 2`，**且必須外加 `clamp(−1, +1)`**——52 週高低取自完成日 K 而現價為 Redis 即時價，創 52 週新高當日 `pos > 1` 會使貢獻 > `+1` 而打破 `[0,100]` 不變量（此路徑只在創新高當天出現，平時測不到，須有專屬測試）；需 240 根否則 `null`，分母為 0 時回 `null`；(e) **3 年年化報酬**：以還原權息序列計 `(現價/750 交易日前價)^(1/3) − 1`，`≥ +15%` → `+1`、`≤ 0%` → `−1`、之間線性內插，需 750 根否則 `null`。全部子因子一律使用還原權息序列（與 MA／KD 同一價基，不得混用）。
- [ ] **基本面組子因子定義**：(a) **EPS 年增率**：最近四季 EPS 合計 vs 前四季合計的增率，`≥ +20%` → `+1`、`≤ −20%` → `−1`、線性內插；不足 8 季 → `null`；前四季合計 `≤ 0` 時無法計算增率 → `null`（不得回 `+1`）；(b) **月營收 YoY**：最近 3 個月 `去年同月增減(%)` 的算術平均，`≥ +15%` → `+1`、`≤ −15%` → `−1`、線性內插，不足 3 個月 → `null`；(c) **ROE**：`最近四季淨利合計 ÷ 最新一期歸屬母公司權益`，`≥ 15%` → `+1`、`≤ 5%` → `−1`、線性內插，不足四季或權益 `≤ 0` → `null`；(d) **PE 自身歷史分位**：現行 PE 在該股自身歷史 PE 分布中的百分位，`≤ 20 百分位` → `+1`（相對便宜）、`≥ 80 百分位` → `−1`、線性內插；歷史 PE 樣本不足 250 個交易日時回 `null`。
- [ ] **PE 子因子的兩種 `null` 語意與優先序**：`stock_valuation_daily` 中「當日有列但 `pe_ratio IS NULL`」代表**公司虧損**（無正 EPS 可計算本益比，實測來源 1079 筆中 247 筆如此），**必須計為 `−1`**；「當日根本沒有列」才是未抓到。兩者須可區分，故 Requirement 46 要求虧損公司仍須寫入一列。**優先序：虧損（有列且 `pe_ratio IS NULL`）一律計 `−1`，與歷史樣本量無關**；只有「非虧損但歷史樣本 < 250 交易日」才回 `null`。此優先序必須明確實作——上線首年歷史樣本恆不足，若無優先序規定則每一家虧損公司的兩個條件同時成立而行為未定義。
- [ ] **PE 低分位方向性的限制條件**：「PE 處於自身歷史低分位 → `+1`」預設「便宜＝機會」，但長期衰退股的 PE 會持續停在自身歷史低位而**永久**取得正分，恰與本修訂要解決的「區分基本面崩壞 vs 短期錯殺」相衝突。故加條件：**`EPS 年增率 ≤ −20%`（即該子因子為 `−1`）時，PE 分位子因子一律回 `null`**，不得給正分。此交互須有專屬測試。
- [ ] **ETF 不套用基本面組，判定採資料驅動**：ETF 在 TWSE／TPEx 基本面資料集是「整筆不存在」而非空值（實測 `BWIBBU_ALL` 1079 筆中 `00` 開頭 0 筆），故 ETF 的基本面組四個子因子恆為 `null`，觸發權重重分配。ETF 判定**不得**沿用 `MarketDataService.isEtf()` 或 `MarketDataFetchService` 的 `US_ETF_WHITELIST` 白名單——該白名單有**兩份複本**（backend 與 external-materials-service 各一），皆把 `AVGO`（Broadcom，個股）誤列為 ETF 且皆漏 `SGOV`，且專案既有決策已三度明文記載「刻意不複用該白名單、改以資料驅動判定」（`MarketDataFetchService.java:641`、`EtfNavPoller.java:29`、`ExcelExportService.java:718`）。本修訂**跟隨既有方向**，判定順序見 Requirement 46。
- [ ] **動作門檻依長期趨勢組分數分層（本修訂解決原始問題的主要機制）**：分數本身誠實反映短期弱勢（不因長期看好而灌水），但**動作門檻**依長期趨勢組分數分三層，使「長期完好、短期回檔」不再被建議減碼，且「長期轉弱、短期反彈」不易被建議買進：

  | 長期趨勢組分數 | 買進／加碼 | 續抱／觀察 | 警戒／觀望 | 減碼／迴避 |
  |---|---|---|---|---|
  | `> +0.5`（長期結構完好） | `75` | `55` | **`30`** | **`15`** |
  | `−0.5 ≤ x ≤ +0.5`（中性，**含兩端點**） | `75` | `55` | `40` | `25` |
  | `< −0.5`（長期轉弱） | **`85`** | `55` | `40` | `25` |

  **三層區間必須互斥且窮盡**，端點歸中性層（不得寫成 `≥ +0.5` 與 `−0.5 ~ +0.5` 這種在 `+0.5` 重疊、使同一輸入可套兩層的形式——兩層的減碼門檻差 10 分，選錯直接改變動作輸出）。長期趨勢組為 `null`（歷史不足）時一律套用中性層，不得套用寬鬆層。門檻分層須為具名常數表，不得寫死於條件式。前端須於動作標籤旁揭露套用了哪一層與原因（例：「長期結構完好，減碼門檻放寬至 30」），否則使用者無法理解為何 43 分是續抱而非減碼。**`< −0.5` 層的買進門檻 85 在基本面組接線前不可達**（該情況下 `score` 上界為 71.9），屬預先定義；驗證該層須直接測門檻選擇函數，不得經由 action 輸出斷言。
- [ ] **買進硬閘門維持不變**：`BUY_CANDIDATE`／`ADD_CANDIDATE` 仍須同時滿足 MA20 與 MA60 皆為 `ABOVE`（連續兩收盤日站上）、大盤非 `RISK_OFF`、大盤非 stale。上表的分層只調整**分數門檻**，不放寬任何硬閘門；長期趨勢組分數再高也不得繞過雙 `ABOVE` 條件。
- [x] **新增「分批試單」動作，解決超賣與買進閘門的結構互斥**：現行「長線佳＋短線超賣轉強」在數學上**不可能**產生任何買進建議——買進閘門要求 MA20／MA60 兩日確認皆 `ABOVE`，而超賣幾乎必然發生在跌破均線時，兩組條件對同一個 enum 互為否定。實測十年全樣本（`stock_price_history` 台股 103,040 個交易日、70 檔標的，以真實 KD9 遞迴與兩日確認重算）：`K<20 且 D<20` 出現 **6,552 次**（6.36%）、買進閘門成立 **41,808 次**（40.57%）、**兩者共存 0 次**、其中低檔黃金交叉且閘門成立 **0 次**。故 V4 的「`K<20` 且 `K>D` 再加 3 分」bonus 十年來從未促成任何一次買進，是死程式碼。**解法不得是放寬既有 buyGate**（放寬會讓那 41,808 次以外的所有下跌趨勢標的一併變成可買，風險失控），而須新增一條**平行的獨立路徑**，輸出與 `BUY_CANDIDATE` 區隔的新動作 `TRIAL_BUY`（分批試單候選）。觸發條件須**同時**滿足：(1) **年線乖離 `(price − MA240)/MA240 ≥ 5%` 且 MA240 兩日確認為 `ABOVE`**（長線結構明確向上——**實作改用乖離率而非長期趨勢組分數**，因後者需 750 根視窗、進而需分割還原；且實測 `price > MA240` 在使用者投組上 100% 成立、毫無篩選力，5% 門檻才能排除貼著年線者與 KD 近乎雜訊的低波動債券 ETF）；(2) `K < 20` 且 `D < 20`（深度超賣）；(3) `K > D`（已轉強）；(4) 前一期 `K ≤ D`（是真的交叉，不是持續強勢；前期 K/D 由排除本期後的同一序列計算，不足時不得假裝交叉）；(5) 最近一根**完成日 K** 的漲跌幅 `≥ 0`（止跌，不可用盤中漲跌幅，避免逐 tick 翻轉）；(6) `EQUITY` 須大盤非 `RISK_OFF` 且非 stale（`BOND` 沿用既有豁免）。任一不滿足即不得輸出 `TRIAL_BUY`。
- [x] **`TRIAL_BUY` 的風險揭露與部位語意**：此動作的本質是接刀，與 `BUY_CANDIDATE`（順勢買進）的風險結構不同，**不得共用標籤或並列排序**。前端須明確標示為「分批試單」並固定顯示「僅適合小額分批、非全額進場；長線判斷失準時虧損可能持續擴大」。API 須回傳觸發此動作的完整條件明細（六項各自的實際值），使使用者能自行判斷。
- [ ] **`TRIAL_BUY` 與既有逆勢抄底軌的關係必須明確**：既有 `evaluateCounterTrend()` 輸出的 `OVERSOLD_WATCH`／`TRIAL_CANDIDATE` 是**純顯示的第二軌**，不影響主 `action`（三年僅觸發 26 次 `TRIAL_CANDIDATE`）。本修訂後，`TRIAL_BUY` 成為主 `action` 的一個值，故同一列不得再出現「主建議＝減碼候選、逆勢狀態＝逆勢試單候選」這種互相矛盾的組合——實測既有單元測試已把該矛盾組合寫死為預期行為。須明訂：`TRIAL_BUY` 觸發時主 `action` 即為 `TRIAL_BUY`，`counterTrendState` 降為診斷欄位；兩者的顯示優先權與矛盾檢查須有測試覆蓋。
- [ ] **分數可比性的界線**：不同標的因缺值而套用不同的實際生效權重（例：ETF 無基本面組、新掛牌標的無 3 年報酬），其分數的組成結構不同，**跨標的比較分數高低不具嚴格意義**。API 須回傳每筆的實際生效權重供判讀，前端須揭露「分數用於同一標的的時序比較與動作判定，跨標的排序僅供參考」。
- [ ] **規則版本與不可比性揭露**：`RULE_VERSION` 升為 `TW_RULES_V5`。V5 分數與 V4 分數**不可直接比較**（因子組成與正規化方式皆改變），API 每筆須回傳 `ruleVersion`，前端於分數欄 tooltip 揭露規則版本。Requirement 44 的通知基準（`last_action`）在規則版本變更後**須全部重建**，不得讓 V4 基準與 V5 動作直接比對而觸發大量假通知——升級後首輪評估一律只建基準不寄信。基本面組由「全 `null`」轉為「有值」時（資料累積達門檻）分數同樣會跳變，須以相同方式處理。
- [ ] **可解釋性須涵蓋新因子**：每筆回傳五個組分數、各組**實際生效權重**（含重分配後的值）、每個子因子的原始值與標準化貢獻，以及套用的動作門檻層級。理由／風險文案須說明長期與基本面的貢獻方向（例：「年線斜率 +8.2%，長期趨勢組正貢獻」「基本面資料累積中，該組權重已重分配至趨勢組」），不得只給總分。
- [ ] **零 AI API 約束不變**：新增的長期與基本面因子一律由本地 DB 的結構化數值計算，不得引入 LLM 判讀財報文字、不得於請求鏈觸發外部抓取。基本面資料由 Requirement 46 的排程預先落地，交易雷達只讀 DB。
- [ ] **驗證**：單元測試至少覆蓋——正規化後分數恆落在 `[0, 100]`（含全部子因子皆 `+1`、皆 `−1`、以及疊加 `RISK_ON`／`RISK_OFF` 的極端輸入，驗證不再飽和且不觸發 clamp WARN）、缺值權重重分配後實際生效權重總和為 `1`、52 週位置在「現價 > 52 週高」時經 clamp 後仍為 `+1`、ETF 路徑基本面組全 `null`、虧損（有列且 `pe_ratio IS NULL`）計為 `−1` 且優先於歷史不足、`EPS 年增率 = −1` 時 PE 分位回 `null`、EPS 前四季合計 `≤ 0` 回 `null`、新掛牌標的（<750 筆）3 年報酬回 `null` 且不影響其餘子因子、三層動作門檻的邊界值（長期組 `+0.5`／`−0.5` 兩個交界各測上下）、買進硬閘門在分數再高時仍不被繞過。**結構性迴歸判準**（取代「特定標的不得為某動作」的循環論證）：固定其他輸入、僅將長期趨勢組分數由 `−1` 掃到 `+1`，總分須單調遞增且變動幅度 ≥ 短期動能組做同樣掃描時的變動幅度；並輸出全部台股標的的 V4／V5 分數與動作對照表附於任務完成報告。

**Requirement 43 修訂（規則版本 `TW_RULES_V6`，Task 228）—— 大盤盤中即時判斷：**

> **本修訂推翻 Task 217 的「零外部行情抓取」決定，與 Task 223（V5，長期因子與分數飽和修正）為互相獨立的兩個修訂——本修訂只動大盤 regime 的資料新鮮度來源，不改動 Task 223 引入的分組加權評分公式、五個因子組權重或動作門檻分層，`evaluateMarket()`／`MarketInput` 簽名維持不變。** Task 217 當時明文「不得以『補一個即時大盤來源』規避——本 Requirement 明訂零外部行情抓取」，理由是彼時「今日交易雷達」的 User Story 強調零 AI API、零外部呼叫的可重現／可回測特性。使用者於 2026-07-20 重新檢視後，明確要求加入大盤即時資料以改善盤中判斷準確度，並確認願意承擔因此增加的外部依賴（Yahoo Finance）。**保留的原則**：本功能仍不得呼叫任何 AI／LLM API；仍不得因使用者「重新整理」頁面而觸發抓取（抓取一律由獨立背景排程驅動，頁面只讀 Redis／PostgreSQL 既有值，與個股即時價的既有模式一致）。**推翻的原則**：不再要求大盤價完全零外部抓取。
>
> - [ ] **大盤盤中即時點位比照個股既有機制**：`0000/台股` 加入 `external-materials-service` 的 Redis 即時價快取（`price:台股:0000`，schema 與個股 `price:{market}:{code}` 相同），不再是 `StockSourceQuery` 明確排除的特例。抓取來源沿用既有的 `MacroDataFetchClient.fetchIndexIntraday("TWSE")`（Yahoo `^TWII` 5 分 K，供「股市大盤查詢」頁「當日走勢」圖表使用的同一支方法，Requirement 18），取該次回傳中最新一筆非 null 收盤點位〔**Task 263 起改呼叫新增的 `fetchIndexIntradayDay("TWSE")`**——同一個 Yahoo URL、同一支 `curlGetWithRetry`，但回傳含所屬日期與當日 open／high／low 供守門與填欄；`fetchIndexIntraday` 本身一行不動，仍服務「當日走勢」圖表〕。排程時段與頻率比照個股台股輪詢（`PricePoller.scheduledTwIntradayUpdate`）：週一～五 09:00–13:30 Asia/Taipei、每 2 分鐘，同樣以 `MarketClock.isTwMarketOpen()` 守門。抓不到有效點位時該輪不寫入，保留 Redis 內上一輪真實值（比照個股 TWSE `z='-'` 的既有慣例，不得以昨收或空值覆寫）。`twse_index_daily_history` 的既有盤後批次寫入（`TwseIndexPoller`）完全不受影響，仍是完成日 K 的唯一權威來源。
> - [ ] **`MarketSummary.stale` 語意改為「真的沒有任何新鮮資料」**：原 Task 217.1 的 stale 定義（完成日 K 日期 ≠ 當前台股交易日）改為僅在**完成日 K 未到今日、且 Redis 亦無今日即時點位**時才視為 stale。任一者成立即非 stale：完成日 K 已入庫（既有邏輯不變）**或**大盤即時價存在且其 `tradingDate` 為當前台股交易日。stale 時的既有效果全部不變（不給個股 `RISK_ON` 加分、買進閘門關閉、`RISK_OFF` 扣分與 veto 仍生效；Task 223 的大盤環境組貢獻值 `+0.5`／`0`／`−1` 與 stale 閘門互動方式不變，只是 stale 判定本身更容易在盤中提早解除）。
> - [ ] **MA／KD 與兩日確認的資料口徑不得混用**：大盤即時點位可併入 MA20／60／240、KD 的計算（比照個股既有的「今日尚未入庫時暫加 live K」機制），但**兩收盤日確認（`confirm(closes, 60/240)`）僅能使用 `twse_index_daily_history` 的完成日 K，不得納入即時點位**——這是既有個股邏輯已驗證過的作法（避免盤中價格在均線附近來回造成確認狀態逐 tick 翻轉），大盤必須採同一原則，不得因為新增即時來源而破例。
> - [ ] **新增 `intraday` 與 `liveUpdatedAt` 欄位**：`MarketSummary` 增加 `intraday`（boolean，true 代表本次 regime 由即時點位計算、而非已入庫完成日 K）與 `liveUpdatedAt`（String，`intraday=true` 時為 Redis 即時價的 `updatedAt`，否則為 null）。既有 `asOfDate` 語意不變，仍為「完成日 K」的日期，不得因為加了即時來源而被即時時間覆蓋。
> - [ ] **前端文案更新**：大盤卡的 stale 警示文字不得再宣稱「大盤指數只有收盤後才入庫，盤中無即時值」（此說法本修訂後不再成立），改為描述「本次未能取得即時大盤點位，已退回前一交易日資料」的暫時性退化語意。`intraday=true` 時另顯示即時點位的更新時間，與「完成日 K」日期並列、不得互相覆蓋或混淆。
> - [ ] **規則版本升級**：`TradingRadarRuleEngine.RULE_VERSION` 由 `TW_RULES_V5`（Task 223）升為 `TW_RULES_V6`（前端 fallback 字串同步），因大盤資料新鮮度的判斷邏輯與使用者可觀察行為（盤中買進訊號的出現頻率）有實質變化，比照 Task 217.6／Task 223 的先例。V6 分數與 V5 分數的因子組成、正規化方式完全相同、**不受本修訂影響**，不可比性揭露沿用 Task 223 已建立的機制，不另訂新規則。
> - [ ] **驗證**：測試須覆蓋「完成日 K 未到今日但 Redis 有今日即時價 → stale=false、intraday=true」「兩者皆無 → stale=true（既有行為不變）」「完成日 K 已到今日 → stale=false、intraday=false（既有行為不變）」「兩日確認只用完成日 K、不受即時點位影響」；`external-materials-service` 須覆蓋「抓不到有效點位時不覆寫 Redis」。

**Requirement 43 修訂（Task 230）—— GET 路徑新增 per-owner Redis 快照寫入（詳見 Requirement 48）：**

- [ ] 原 AC「一頁一 BFF……此 GET 無寫入、無排程、無新資料表」中的「**無寫入**」，因結果快照落地與區間匯出需求（Requirement 48）而修訂：`GET /api/trading-radar` 於成功回應前，在 `CurrentUserContext.hasUser()` 為真時新增一筆 **per-owner Redis 快照寫入**（節流＋去重＋gzip 壓縮，保留窗預設 90 天）。仍維持：**無新資料表**（快照只存 Redis）、**無新增 @Scheduled**（保留窗修剪於寫入路徑 inline 完成）、不呼叫任何外部行情或 AI API。因該 Redis 為 `allkeys-lru` 且與即時股價 `price:*` 共用 256MB，快照量必須受節流／保留窗控制，以免把即時股價快取逐出。前端資訊框「本頁只讀取…」文案同步更新以揭露此寫入。

**Requirement 43 修訂（規則版本 `TW_RULES_V7`，Task 232）—— KD 過熱判定補 K 單獨門檻、文案修正與偏熱揭露：**

> **本修訂修改 Requirement 47 已標記 `[x]` 的「KD 過熱與換匯過貴採『否決買進』」條目中的過熱判定式，該條其餘內容（換匯過貴 `fxPercentile ≥ 90`、「進場時機而非標的品質」的設計理由）不變。** 使用者於 2026-07-21 回報實際畫面：00882（中信中國高股息，K 82.3／D 75.2、分數 83、已持有）被判為「加碼候選」，質疑「K > 80 短線過熱為何建議加碼」。查核結果為兩個原因疊加：(a) 過熱判定式為 `avg(K,D) > 80`，該檔 `avg = 78.75` 未達門檻；(b) `KD 動能`（權重 `0.08`）與 `KD 位置`（權重 `0.13`）在高檔互相抵消——K 剛突破 80 時正是 K 大幅領先 D 之際，動能子因子因此接近飽和地給正分，實測該檔動能 `+0.71×0.08 = +0.057`、位置 `−0.575×0.13 = −0.075`，淨貢獻 `−0.018`，換算後在 100 分制中**僅扣 0.9 分**。
>
> - [ ] **決策的實證背景必須完整記載，包含與本決策相反的部分**：以 `stock_price_history` 台股十年全樣本（106,131 列、50 檔，排除 `0000` 大盤）依 `TechnicalIndicatorService` 同一 KD9 遞迴（period 9、seed 50/50、`hh==ll → 50`）重算，取「買進閘門其他條件成立」（MA20／MA60 皆連續兩完成日在均線上）的 **38,186** 個樣本（其中 **37,720** 個有完整 20 交易日後續，下檔風險指標以此為準）分組回測：
>
>   | 分組 | 樣本（具完整20日後續） | 後20日報酬 | 20日勝率 | MDD20均 | 10日內跌>3% | 20日內跌>10% |
>   |---|---|---|---|---|---|---|
>   | A 放行 `avg≤80, K≤80` | 23,342 | `+1.94%` | `54.5%` | `−5.98%` | `41.8%` | `15.9%` |
>   | B `avg≤80, 80<K≤85` | 2,438 | `+1.70%` | `56.8%` | `−6.03%` | `39.7%` | `13.8%` |
>   | C `avg≤80, K>85` | 239 | `+1.70%` | `62.8%` | `−5.05%` | `32.6%` | `10.0%` |
>   | D 已擋 `avg>80` | 11,701 | `+1.86%` | `58.7%` | `−5.30%` | `34.9%` | `11.1%` |
>
>   **回測結論與本修訂方向相反，此事必須明文留存**：C 組（本修訂新增擋下的區間）在四組中下檔風險最低、勝率最高；現行已擋的 D 組各項風險指標亦優於正常放行的 A 組。跨年度分年檢視同樣穩定，含下跌年份（2018：A `−1.31%` vs D `−0.06%`；2022：A `−2.44%` vs D `−0.94%`）。即「KD 過熱在本標的池與本區間不預示回檔，反而偏向動能延續」。**已知偏誤（皆使結論偏向不利於本修訂）**：標的池為使用者當前 50 檔觀察清單，存在存活偏誤且偏大型 ETF；2016–2026 台股整體為多頭，任何抑制買進的規則於此區間皆吃虧；回測使用原始價而非規則引擎實際採用的還原權息序列，除息缺口會壓低各組報酬（方向一致、幅度未校正）；C 組僅 239 樣本，統計力弱，D 組的 11,701 樣本才是紮實者。
> - [ ] **過熱判定式增加 K 單獨門檻（刻意的風險偏好取捨，非實證結論）**：判定式由 `avg(K,D) > 80` 改為 `avg(K,D) > 80 || K > KD_OVERHEAT_K`，`KD_OVERHEAT_K = 85.0` 為具名常數。此變更**經上述回測證明不會改善期望值、亦不會降低下檔風險**，採納理由為使用者明確的風險偏好：不願在單一指標已達極端超買時收到加碼建議，即使該狀態的歷史後續表現較佳。**不得在任何文件、註解或 UI 文案中宣稱本門檻有回測依據。** 門檻值 `85`（而非使用者原始直覺的 `80`）的選定依據是**受影響樣本量**——在一條無實證支持的規則上盡量縮小影響面：以上表 37,720 個具完整 20 交易日後續的樣本為分母，`85` 影響 C 組 239 個（`0.63%`），`80` 則另把 B 組 2,438 個（`6.46%`）一併降級、合計 2,677 個（`7.10%`）。**下檔風險數據對 `80` 與 `85` 皆不支持**（B 組 MDD `−6.03%` 與正常放行組 `−5.98%` 實質相同；C 組 `−5.05%` 甚至優於放行組），故不得以「85 比 80 更能避開回檔」為由陳述此選擇。
> - [ ] **「降級而非否決」為既有機制的澄清，不得新增動作值**：現行 `kdOverheated` 僅使 `buyGate` 為 false、**不扣分**，分數維持原值後落入既有的 `score >= 55` 分支，已持有得 `HOLD`、未持有得 `WATCH`（Requirement 47 記載的「00719B 由 100 分／買進候選變為 78 分／觀察」即此行為）。本修訂確認此即所需的「降級」語意，**不新增 enum 值、不改動作映射分層、不因過熱而扣分**。過熱標的仍必須保留其原始分數與其餘理由，使畫面能同時呈現「長線結構良好」與「短線過熱故本日不加碼」。
> - [ ] **修正與實證相反的風險文案**：現行 `kdPosition()` 在 `avg > 80` 時輸出「KD 均值 N 已達超買區，短線過熱、回檔機率升高。」，其中「**回檔機率升高**」與上述回測直接矛盾（過熱組 20 日內跌逾 10% 的比例為 `11.1%`，低於正常放行組的 `15.9%`）。須改為不對後續機率作出宣稱的中性描述，例如「KD 均值 N 已達超買區，短線位置偏高；本日不列入買進／加碼候選。」系統不得對使用者陳述本專案自身資料不支持的因果宣稱。
> - [ ] **新增「短線偏熱」揭露，且必須在收合列可見**：K 或 `avg(K,D)` 進入偏高區但未達降級門檻時（`K > 80 || avg(K,D) > 70`，且未達過熱門檻），須輸出資訊性提示。**兩個觸發分支必須有各自的文案，不得共用**：`K > 80` 命中時述 K 值（如「K 值 82.3 偏高，短線偏熱；未達過熱門檻，動作維持。」），僅 `avg(K,D) > 70` 命中時述均值（如「KD 均值 73 偏高，短線偏熱；未達過熱門檻，動作維持。」）——`K=70, D=75` 會使 `avg=72.5` 觸發偏熱而 K 並未高於 80，共用 K 版文案會對使用者輸出「K 值 70 已高於 80」這種假陳述，與本修訂「不得陳述資料不支持的宣稱」的要求自相矛盾。**所有 KD 相關文案一律不得把門檻數字寫進句子**（不可寫「已高於 80」「已高於 85」）：條件為嚴格大於，而 `K = 85.02` 在任何四捨五入下都顯示為 `85.0`，句子即成為自我否定的「K 值 85.0 已高於 85」；既有 avg 分支的「已達超買區」正是不引述門檻的正確寫法。此提示**純為揭露，不影響分數、不影響動作、不得計入扣分**。**關鍵約束**：`reasons`／`risks` 清單目前僅在表格列展開後才顯示，而使用者回報的正是收合狀態下的畫面；故偏熱與過熱狀態**必須在收合列本身即可辨識**（於既有 KD 欄 `K x.x / D x.x` 加註視覺標記），僅加進 `risks` 清單不算滿足本條。過熱（已降級）與偏熱（未降級）兩種狀態的標記須可區分，不得共用同一樣式而讓使用者無法判斷動作是否已受影響。
> - [ ] **規則版本升級**：`TradingRadarRuleEngine.RULE_VERSION` 由 `TW_RULES_V6` 升為 `TW_RULES_V7`，因買進閘門的判定條件與使用者可觀察行為（加碼候選的出現頻率）有實質變化，比照 Task 217.6／223／228 的先例。**前端兩處 hardcode 字串須同步**：`TradingRadarView.vue` 的顯示 fallback（`radar.ruleVersion || 'TW_RULES_V6'`）與 `radar` ref 的初始值。V7 的因子組成、權重與正規化方式與 V6 完全相同，分數本身跨版本可比，不另訂不可比性揭露。
> - [ ] **驗證**：測試須覆蓋——`K=82.3, D=75.2`（`avg=78.75`）在 `score>=75` 且其餘閘門成立時**仍為 `ADD_CANDIDATE`**（本修訂刻意不改變此案例，門檻取 85 的直接後果，須以測試釘住以免日後被誤改）；`K=86, D=70`（`avg=78`）→ 過熱降級為 `HOLD`／`WATCH` 且分數不變；`avg(K,D)=81` 且 `K<85` → 沿用既有過熱降級（迴歸保護）；過熱時 `risks` 不含「回檔機率升高」字樣；`K=82.3` 未達過熱門檻時輸出偏熱提示且 `action` 與分數與未加此揭露前完全相同。前端須驗證收合列即可辨識偏熱／過熱兩種標記。

**Requirement 43 修訂（Task 249）—— 手動「重新整理」改為先回補即時行情再重算：**

> **本修訂推翻三處既有明文：**（a）本 Requirement 原 AC「零 AI API」中的「**不得因重新整理而觸發公開資訊爬蟲或外部行情抓取**」；（b）Task 228（V6）修訂前言中「**仍不得因使用者「重新整理」頁面而觸發抓取（抓取一律由獨立背景排程驅動，頁面只讀 Redis／PostgreSQL 既有值，與個股即時價的既有模式一致）**」一句；（c）**Requirement 48** 的 AC「`TradingRadarView.vue` 既有資訊框……**仍維持不呼叫 AI API、不觸發外部行情回補的陳述**」與 Requirement 48 追加（Task 231）的「Requirement 43 的『不呼叫任何外部行情或 AI API』不變」——資訊框改為揭露「按下『重新整理』會觸發一次台股行情回補」，Requirement 48 其餘部分（per-owner 快照寫入、區間 Excel 匯出、排程匯出）**仍為零外部行情抓取，完全不變**。使用者於 2026-07-29 檢視頁面後明確要求：按下「重新整理」就要抓到最新股價。現行行為是按鈕只重打 `GET /api/bff/trading-radar`，最新價由 `PriceQueryService.getLive` 讀 Redis `price:台股:{code}`，而該值完全由 `PricePoller`／`TaiexIndexPoller` 每 2 分鐘的背景排程決定；因此在兩次排程之間連按十次「重新整理」得到的是同一個價格，按鈕名稱與實際效果不符。**保留不變的原則：** 本功能仍不得注入或呼叫 `MarketAnalysisService`、Anthropic、OpenAI 或任何 LLM client；仍不得因重新整理而觸發公開資訊（新聞）爬蟲；仍不新增資料表。**推翻的只有「外部行情抓取」這一項，且只限使用者明確按下按鈕的那一條路徑。**
>
> **範圍已擴大（Task 260，2026-07-30）：** 上一段「只限使用者明確按下按鈕的那一條路徑」與同段「Requirement 48 其餘部分（per-owner 快照寫入、區間 Excel 匯出、**排程匯出**）仍為零外部行情抓取，完全不變」兩句，其中**「排程匯出」該項已由 Requirement 48 追加（Task 260）推翻**：排程產檔前一律先回補台股即時行情再重算。原文保留為 Task 249 當時的決策記錄。**仍然不變的是**：per-owner 快照寫入與區間 Excel 匯出兩項仍為零外部抓取；LLM／AI client 與新聞爬蟲的禁令對**所有**路徑一律不變。
>
> - [ ] **只有手動按鈕走新路徑，`GET` 維持純讀**：新增 business `POST /api/trading-radar/refresh`；BFF 端**不得新增 gateway route、也不得新增 controller method**——該路徑落在 `TradingRadarBffRoutes` 既有 wildcard `/api/bff/trading-radar/**` 之內，rewrite 後即為 `POST /api/trading-radar/refresh`，自動可用。既有 `GET /api/trading-radar` 的行為**完全不變**：不觸發任何外部抓取，仍是純讀 PostgreSQL／Redis ＋ Task 230 的 per-owner 快照寫入。**SSE 背景重算（本 Requirement「盤中自動更新」AC）必須繼續呼叫 `GET`，不得改呼叫 `POST`**——否則每一次 `price-update` tick 都會觸發一次外部抓取、抓完再寫 Redis 又觸發下一輪 tick，形成自我餵食迴圈並在 2 秒 debounce 下持續打外部 API。此約束須以「前端 `recalculateRadar()` 呼叫的是 GET wrapper」的形式落實，不得只寫在註解裡。
> - [ ] **抓取範圍限於本頁真正用得到的台股，不得順手全抓**：本頁只評台股（美股／英股僅計入 `skippedNonTwStocks`），故按鈕觸發的回補範圍為：(a) **每位 owner 各自最新快照**的台股持股 ∪ 台股觀察清單（`stock_alert`），排除大盤 `0000`（大盤走另一條路徑，且本 Requirement 明訂不打 `mis.twse.com.tw`）。**不得重用既有的 `StockSourceQuery.collectHeldStockCodes()`**：它取的是 `SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1`——全庫只取**一筆**快照，且日期相同時 tie-break 由 Postgres 任意決定。實測 2026-07-29 兩位 owner 同日各有一筆快照（id 15／owner 1，35 筆台股；id 18／owner 2，2 筆台股），它挑中 id 18，於是 owner 1 只在自己持股、不在觀察清單的標的（實測 `2885`）**永遠不會被回補**——使用者按下「重新整理」卻有一列價格沒動，與按鈕的承諾直接矛盾。須改用 `DISTINCT ON (owner_user_id) ... ORDER BY owner_user_id, snapshot_date DESC, id DESC` 取每位 owner 的最新快照（同 owner 同日多筆再以 id 決勝，結果具決定性）。~~**同時不得修改 `collectHeldStockCodes` 本身**：它服務每 2 分鐘的 `PricePoller.scheduledTwIntradayUpdate` 與 `refreshAll()`，放大其範圍會改變背景排程對外部 API 的請求量，屬另一個決定~~ **（此句已由 Task 257 推翻：實測改為 per-owner 後台股僅 18→19 檔、美股 9→9、英股 3→3，請求量幾無變化，兩者現已共用同一個快照選取子查詢。保留原句作為當時的決策記錄。）**；(b) 台股大盤 `0000`，走 `TaiexIndexPoller` 既有的 `MacroDataFetchClient.fetchIndexIntraday("TWSE")` 單次抓取。**不得呼叫既有的 `POST /internal/refresh`**——它會連美股與英股一起抓（`PricePoller.refreshAll()`），對本頁毫無用處，只會把使用者的等待時間拉長到含美股逐檔 Yahoo 查詢的長度。
> - [ ] **休市時不得以 last-tick 覆寫權威收盤（既有不變式，本修訂不放寬）**：台股非交易時段／非交易日時，「抓到最新股價」的正確語意是**以 `stock_price_history` 的最近一筆收盤同步 Redis**（沿用 `PricePoller.syncClosedFromDb`），**不得**改為向外部重抓即時報價。理由是既有已驗證的不變式：盤外抓到的 last-tick 會蓋掉 FinMind 校正過的權威收盤，造成 Redis 與 DB 不一致、Dashboard／歷年資產／快照表單三處數字互相打架（`PricePoller.refreshAll` 與 `warmCacheOnStartup` 兩處註解均明載此為 Task 111 的結論）。休市時大盤 `0000` **不抓**：`twse_index_daily_history` 的盤後批次（`TwseIndexPoller`）是完成日 K 的唯一權威來源，盤外抓 5 分 K 只會取到昨日尾盤點位而無新資訊。開盤與否一律以既有 `MarketClock.isTwMarketOpen()`／`MarketDataService.isMarketOpenNow("台股")` 判定，**不得自行以星期幾或時鐘推算，也不得新建第二份假日清單**。
> - [ ] **13:30–13:32 收盤空窗：休市同步必須逐檔守門，否則會把昨收寫成今日收盤**：`MarketClock.isTwMarketOpen()` 的上界為 `13:30`（`!t.isAfter(LocalTime.of(13,30))`），而今日收盤價要到 `ClosePersister.dumpTwCloseFromRedis()`（`@Scheduled(cron = "0 32 13 * * MON-FRI")`）才由 Redis 落進 `stock_price_history`。在這約兩分鐘的空窗內，`syncClosedFromDb` 走的 `StockSourceQuery.findRecentClose`（`ORDER BY trading_date DESC LIMIT 1`）取回的是**昨日**收盤，`PriceCacheWriter.syncClosedFromDb` 會無條件覆寫 Redis，銷毀 13:30 那輪的真實當日收盤；接著 13:32 的 `dumpRedisToDb` **不檢查 payload 的 `tradingDate`**，會把該昨收以 `tradingDate=今日` upsert 進 `stock_price_history`，污染本專案宣告的收盤價唯一權威來源，直到 16:00 `verifyTwCloseWithFinMind` 才自癒。故休市分支**必須逐檔守門**：`MarketClock.isTradingDay("台股", today)` 為真、**且台股當地時間已過 13:30**、且該檔 `StockSourceQuery.findMaxTradingDate(code, "台股")` **不等於今日**時，該檔**不執行** `syncClosedFromDb`（Redis 現值即當日最後成交，比 DB 新）。非交易日（週末／國定假日／颱風假）不受此守門影響，照常同步。**13:30 這個時間下界不可省**：少了它，交易日 00:00–09:00 的盤前整段也會落進守門（該時段同樣「休市 ＋ 是交易日 ＋ 今日收盤未落 DB」），使按鈕整個上午都回 `SKIPPED_PENDING_CLOSE` 並顯示「已保留最新成交價」——盤前 Redis 裡沒有今日成交價，這是本 Requirement 自己禁止的假陳述；且守門理由在盤前是反過來的（DB 有 16:00 FinMind 校正過的權威收盤，Redis 才是該被同步的一方，正是 `PricePoller.warmCacheOnStartup` 在盤外做的事）。`findMaxTradingDate` 回 `Optional.empty()`（該檔無任何歷史列，如新掛牌或剛加入觀察清單）視為**不納入**同步。`tw` 本身為空時 `outcome` 不得為 `SKIPPED_PENDING_CLOSE`。整批皆被守門擋下且 `tw` 非空時 `outcome` 為 `SKIPPED_PENDING_CLOSE`。**不得改動既有 `PricePoller.syncClosedFromDb`／`PriceCacheWriter.syncClosedFromDb` 的行為**——它們另有 `refreshAll` 與 `warmCacheOnStartup` 兩個既有呼叫端，守門只加在本頁新增的服務內。
> - [ ] **冷卻必須含全域鍵，per-owner 單獨無法達成節流目的**：按鈕會直接打外部行情 API，必須有節流，否則連點就是連續對 Yahoo／TWSE 發請求（本專案已有被 Yahoo WAF 回 429 的實績）。(a) **雙鍵冷卻**：business 端同時檢查 per-owner 的 `radar:refresh:cooldown:{ownerId}` 與**全域**的 `radar:refresh:cooldown:global`，TTL 皆 `30` 秒（同一具名常數），**兩把都取得才進行抓取**，任一未取得即 `outcome=COOLDOWN`；且**任一把未取得時必須釋放本次已取得的那一把**（沒有實際抓取就不得消耗任何一把冷卻，否則對使用者宣告的「30 秒」實際最長會變成約 60 秒）。只有 per-owner 鍵擋不住「A 按完 5 秒後 B 按」——回補清單是全庫的（見下方租戶條），N 個使用者輪流按可把外部請求頻率從背景排程的每 2 分鐘一輪推高數十倍，而 `Semaphore` 只擋併發、不擋速率。全域鍵不造成租戶洩漏：它只透露「近期有人刷新過」，而行情快取本就是跨租戶共用的市場資料。(b) **服務端單一併發**：`external-materials-service` 端以單一 permit 的 `Semaphore.tryAcquire()` 守住本端點，已有一輪在跑時立即回 `busy=true` 而**不排隊等待**，business 收到後 `outcome=BUSY`、照常重算回傳。冷卻中與 busy 皆為「降級但仍回結果」，不得回 4xx——使用者按鈕仍應得到一份最新重算結果。
> - [ ] **逾時預算與失敗降級（全鏈路必須在 nginx 60 秒內收斂，且大盤那一段必須自己有上界）**：既有 `frontend/nginx.conf` 的 `location /api/` 為 `proxy_read_timeout 60s`，超過即 504；BFF 的 Spring Cloud Gateway **未設** `spring.cloud.gateway.httpclient.response-timeout`（預設不逾時），不構成額外上界，日後亦不得為此功能加設。預算由內而外固定為：business → external 的 `WebClient` 設 **30 秒**逾時；前端 axios 對此支呼叫覆寫 **45 秒**。**external 端本身必須收斂在 30 秒內，不得只靠外層逾時**：個股側 `PriceFetchClient` 每檔 `tse`／`otc` 各 10 秒逾時、virtual-thread-per-code 並行，總時間 ≈ 20 秒；但大盤側 `MacroDataFetchClient.fetchIndexIntraday("TWSE")` 走 `curlGetWithRetry(url, 2)`，回應非 JSON（Yahoo WAF 擋）時會 `Thread.sleep(10s)` 再 `sleep(20s)` 重試，**單這一段最壞就是 30 秒純睡眠**，且其 `ProcessBuilder("curl", "-s", ...)` **沒有 `-m`／`--max-time`**、`waitFor()` 亦無逾時，理論上無上界。故：大盤抓取與個股抓取**必須併行**（不得序列），且大盤那一條**必須自帶 12 秒等待上限**，逾時即放棄本輪大盤（Redis 保留上一輪真實點位，符合 `TaiexIndexPoller` 既有「查無有效點位不寫」的慣例），不得讓它把整體推過 30 秒。**該上限不得被 `ExecutorService` 的收尾抵銷**：Java 19+ 的 `ExecutorService.close()` 預設實作是 `shutdown()` 後 `awaitTermination(1 DAY)`，用 try-with-resources 包住併行區塊會在離開區塊時一路等到大盤任務結束，上限形同不存在（本專案 `PricePoller.updatePrices` 的 `// executor.close() 等所有 task 完成` 註解即此語意）；`Future.cancel(true)` 亦救不回來，因 `curlGetWithRetry` 阻塞在子程序 pipe 讀取時對中斷無反應。故 executor 必須是 bean 生命週期的欄位，逾時後直接往下走、不再等待。**不得為此修改既有 `curlGetWithRetry`**——它同時服務「股市大盤查詢」頁與其他總經抓取，改其重試或逾時屬另一個變更的爆炸半徑。business 端**逾時或呼叫失敗一律不得回 5xx**：記 WARN log 後照常執行既有重算並回傳，`outcome=TIMEOUT`／`FAILED`，讓使用者至少拿到以現有 Redis 值重算的結果。
> - [ ] **回應形狀與租戶隔離**：`POST /api/trading-radar/refresh` 回傳 `{ radar, priceRefresh }`——`radar` 為與 `GET` **完全相同**的 `TradingRadarDto.Response`（不得改動該 record 的欄位，以免影響 Task 230 已落地的 Redis 快照序列化與 Requirement 48 的區間匯出），`priceRefresh` 為新增的 `{ outcome, twMarketOpen, elapsedMs }`。`outcome` 為列舉字串 `FETCHED`（開盤中已重抓）／`CLOSED_SYNCED`（休市，已同步 DB 收盤）／`SKIPPED_PENDING_CLOSE`（今日收盤尚未落 DB 的空窗，刻意不同步）／`COOLDOWN`／`BUSY`／`TIMEOUT`／`FAILED`。`priceRefresh.twMarketOpen` 與 `outcome` 的開／休市判斷**必須取自同一個值**（一律用 business 端的 `MarketDataService.isMarketOpenNow("台股")`），external 回傳的同名欄位只寫 log 不入回應——否則跨 13:30 邊界或兩服務假日表不同步時，會出現 `{outcome:"FETCHED", twMarketOpen:false}` 這種自我矛盾的 payload。**`priceRefresh` 不得回傳抓取檔數**：回補清單來自全庫（Redis 行情快取本來就是跨租戶共用的市場資料，既有背景排程亦是全庫抓），回檔數等於把「全庫台股標的數」洩漏給任一使用者；檔數只寫 business／external 的 log。`radar` 本身仍走既有 `ownerFilter`，內容為當前使用者自己的標的，不因本修訂改變。POST 路徑亦須照 Task 230 寫入 per-owner 快照（重算走同一支 `TradingRadarService.get()`，行為自動一致）。
> - [ ] **前端行為與文案**：「重新整理」按鈕改呼叫新的 POST wrapper，`loading` 期間按鈕維持 `:loading="refreshing"`（不得改成整頁遮罩，沿用既有 manual／silent 分流）。回應後依 `outcome` 顯示對應 `ElMessage`，且**文案不得宣稱做了沒做的事**：`FETCHED` →「已重新抓取即時報價並重算」；`CLOSED_SYNCED` →「台股目前休市，已同步至最新收盤價並重算」；`SKIPPED_PENDING_CLOSE` →「今日收盤價尚未落檔，已保留最新成交價並重算」；`COOLDOWN` →「30 秒內剛更新過，已直接重算」；`BUSY` →「行情更新進行中，已以現有報價重算」；`TIMEOUT`／`FAILED` →「行情抓取未完成，已以現有報價重算」。頁面上方既有資訊框文字「不會送出 Claude、OpenAI 或其他 AI API 請求，**也不會觸發外部行情回補**」**必須同步修正**——後半句在本修訂後為假陳述，改為明示「按下『重新整理』會觸發一次台股行情回補；頁面自動更新與其餘操作不會」。
> - [ ] **規則版本不升版**：`TradingRadarRuleEngine.RULE_VERSION` 維持 `TW_RULES_V7`。Task 217.6／223／228／232 升版的理由都是**規則本身或其輸入語意改變**（stale 判定、因子與正規化、買進閘門條件）；本修訂完全不碰規則引擎、不碰 `MarketSummary.stale`／`intraday` 語意、不碰任何因子或門檻，只改變「Redis 內的價格在使用者按鈕當下有多新」。同一份輸入在本修訂前後產生完全相同的輸出，故升版反而會製造假的不可比性訊號並觸發 Requirement 44 的通知基準重建（`last_action` 全部重建、首輪只建基準不寄信），代價實在而效益為零。
> - [ ] **驗證**：測試須覆蓋——(a) 開盤中觸發 → 走個股 `updatePrices` ＋ 大盤 `updateOnce`，`outcome=FETCHED`；(b) 休市觸發 → 走 `syncClosedFromDb`、大盤不抓，`outcome=CLOSED_SYNCED`；(b2) 今日為交易日、休市、但該檔 `findMaxTradingDate` 非今日（13:30–13:32 空窗）→ **不呼叫** `syncClosedFromDb`，`outcome=SKIPPED_PENDING_CLOSE`；(b3) 非交易日（週末）→ 守門不生效、照常 `syncClosedFromDb`；(c) 冷卻中觸發（per-owner 鍵或全域鍵任一命中）→ **完全不呼叫** external（以 mock 驗證零互動），仍回完整 `radar`，`outcome=COOLDOWN`；(d) external 逾時／拋例外 → 不拋到 controller、仍回完整 `radar`，`outcome=TIMEOUT`／`FAILED`；(e) external 端 `Semaphore` 已被佔用 → 立即回 `busy=true` 且不進行任何抓取；(f) 迴歸：`GET /api/trading-radar` 在本修訂後仍**零外部呼叫**（以 mock 驗證 external client 零互動），確保 SSE 背景重算路徑未被汙染。前端須驗證正式建置後的 `TradingRadarView` chunk 含 `bff/trading-radar/refresh`，且 `recalculateRadar` 走的仍是 GET。

**Requirement 43 修訂（Task 263）—— 大盤即時點位必須驗證日期，且當日 OHLC 取自來源而非本地聚合：**

> **本修訂補正上方 Task 228 AC「取該次回傳中最新一筆非 null 收盤點位」一句的缺漏**：該句只約束「非 null」，未約束「屬於今日」。`MacroDataFetchClient.fetchIndexIntraday("TWSE")` 打的是 Yahoo `^TWII` 的 `interval=5m&range=5d`，回傳橫跨最近五個交易日；它內部雖以 `byDate.lastKey()` 取最新交易日，但**當 Yahoo 尚未產生今日第一根 5 分格時，「最新交易日」就是昨日**。cron `0 0/2 9-13` 的第一輪落在 09:00:00 整、而今日 09:00 那格要到 09:05 才收，故此情形每個交易日開盤都會發生一次。**使用者可見症狀（2026-07-31 實測）**：`price:台股:0000` 的 `lowPrice` 為 39933.3008（該值是 7/30 的盤中點位），而 Yahoo 當日真實低點為 41610.41——昨日點位被當成今日 tick 寫入，並經 `IntradayHighLowTracker` 併進 `price:dayhl:台股:0000:2026-07-31` 汙染當日最低，且該汙染會持續整個交易日（聚合只取 min，不會被後續正確值修正）。Task 263 讓觀察清單改讀這份 Redis 值之後，這個錯值會直接出現在使用者畫面上，故必須同批修掉。
>
> - [ ] **只採用屬於台北當日的點位**：`TaiexIndexPoller.updateOnce()` 須依點位自身的時間戳判定其所屬台北日期，只有等於**台北當日**者才可寫入 Redis；當次回傳完全不含今日點位時，本輪不寫入，保留 Redis 內上一輪真實值（與 Task 228 既有「抓不到有效點位時不覆寫」的處置相同，不得以昨日點位或空值覆寫）。既有「取最新一筆非 null 收盤」的行為在通過日期判定後維持不變。
>   **連帶效果（預期行為，不得為此放寬日期守門）**：今日第一根 5 分格產生前（約 09:00–09:05），「今日交易雷達」的 `MarketSummary` 會顯示 `stale=true`／`intraday=false`。修正前那一輪即使抓到昨日點位也會寫入 Redis（`PriceCacheWriter` 的 `tradingDate` 來自 `TradingDateResolver`，盤中恆為今日），使 `TradingRadarService` 的 `liveFreshToday` 判定為真、顯示 `stale=false`／`intraday=true`——那是**用昨日點位假裝今日即時**。修正後短暫顯示「資料過時」才是誠實的語意，`stale` 的既有效果（不給 `RISK_ON` 加分、買進閘門關閉、`RISK_OFF` 扣分與 veto 仍生效）在這幾分鐘內照常適用，屬於保守方向。
> - [ ] **當日 `openPrice` / `highPrice` / `lowPrice` 取自來源當日全部 5 分格**：`open` 為當日第一根格的開盤、`high` 為當日各格最高的最大值、`low` 為當日各格最低的最小值。原本 `openPrice` 寫死 null（註解「MA/KD 判斷不吃這欄，留白不臆造」）——該理由在 Task 263 讓觀察清單顯示這一列 OHLC 之後不再成立，「開盤」欄不得恆為空白。
> - [ ] **大盤不參與 `IntradayHighLowTracker` 的本地聚合**：上一條的 high／low 已是來源給的當日權威值，不得再與 `price:dayhl:{market}:{code}:{tradingDate}` 的本地累計取 max／min。本地聚合的存在理由是「外部 API 不提供 dayrange」（Requirement 14 明訂，起因為 NASDAQ 對 ETF 的 `keyStats` 為 null），對大盤不成立；且一旦寫入過錯誤的極值，聚合的 max／min 語意使其無法被後續正確值修正。個股路徑的聚合行為**完全不變**。
> - [ ] **連帶效果：盤中 TAIEX 的 K／D 值會與修正前不同，且不升規則版本。** 修正前 `TaiexIndexPoller` 傳 `highPrice`／`lowPrice = null`，`PriceCacheWriter` 的 merge 回退為聚合值，故 `price:台股:0000` 的 high／low 實際上是**「5 分格收盤價」的本地 max/min**；修正後改為 Yahoo **「5 分格 high／low 陣列」的 max/min**，區間必然變寬（實測 2026-07-31：low 由 39933.30 變 41610.41）。這兩欄直接進 KD 的 RSV 分母，影響三處：`computeAllForTaiex()` 合成今日列 → 觀察清單 `0000` 的 K／D 欄；`taiexSeriesAsc` 今日點 → 走勢圖 KD 子圖；前者再經 `MarketSummary.kValue`／`dValue` → 本 Requirement 的 regime／score，並可能連動 Requirement 44 的狀態轉換寄信。因此**`TradingRadarRuleEngine.RULE_VERSION` 由 `TW_RULES_V7` 升為 `TW_RULES_V8`**，比照 Task 228（V6）與 Task 232（V7）的先例——那兩次的 AC 都明文寫著「因子組成、權重與正規化方式完全相同」卻照樣升版，理由都是「使用者可觀察行為有實質變化」；唯一的不升版先例 Task 249 之所以成立是「同一份輸入在修訂前後產生完全相同的輸出」，本修訂不符合。**前端兩處 hardcode 字串須同步**：`TradingRadarView.vue` 的顯示 fallback（`radar.ruleVersion || 'TW_RULES_V7'`）與 `radar` ref 的初始值。V8 的因子組成、權重與正規化方式與 V7 完全相同，**不另訂不可比性揭露**（修正前後的差異來自輸入取值變正確，不是 Task 223 那種因子組成改變）。
> - [ ] **`previousClose` 取嚴格早於點位日期的最後一筆完成日 K**：不得直接取 `twse_index_daily_history` 最新一筆——`TwseIndexPoller` 於 14:00 寫入今日完成日 K 後，「最新一筆」即為今日，昨收會變成今日收盤。現行 cron 與 `MarketClock.isTwMarketOpen()` 使 poller 不會在 14:00 之後執行，故此為防禦性修正、當前不影響輸出值。
> - [ ] **不得修改 `MacroDataFetchClient.fetchIndexIntraday`**：該方法同時服務「股市大盤查詢」頁的「當日走勢」圖表（Requirement 18），其回傳型別 `IndexIntradayPoint` 只有 `time` 與 `close` 兩欄、且補滿整個交易時段的 5 分格（未到的時段 `close` 為 null）以固定 x 軸。取 open／high／low 須另闢方法，共用同一個 URL 與既有 `curlGetWithRetry` 重試機制，不得新增第二個外部資料來源、不得新增 `@Scheduled`、不得改動既有重試或逾時參數（`TwRadarRefreshService` 的 12 秒上界依賴其現行行為）。
> - [ ] **驗證**：`external-materials-service` 測試須覆蓋「回傳只含昨日點位 → 不寫入 Redis」「回傳含今日點位 → 寫入且 open／high／low 為當日全部格的第一開盤／最高／最低」「回傳為空 → 不寫入（既有行為不變）」「大盤寫入路徑不呼叫 `IntradayHighLowTracker.observe`」「`previousClose` 為嚴格早於點位日期的最後一筆」。

---

### Requirement 44: 每檔交易雷達狀態 Email 通知

**User Story:** 作為台股投資人，我希望在交易雷達每檔股票後方開啟通知設定，選擇哪些主規則建議或逆勢抄底狀態出現時寄 Email，並指定我自己的通知收件人，讓我不必一直停留在雷達頁面也能得知狀態轉變。

**Acceptance Criteria:**

- [ ] **逐檔入口與對話框**：交易雷達表格最右側新增「通知設定」按鈕；點擊後顯示該股票代號／名稱，分組列出所有主規則狀態（`BUY_CANDIDATE`、`ADD_CANDIDATE`、`HOLD`、`WATCH`、`HOLD_CAUTION`、`WAIT`、`REDUCE_CANDIDATE`、`EXIT_CANDIDATE`、`AVOID`、`NO_TRADE`）與可通知的逆勢狀態（`OVERSOLD_WATCH`、`TRIAL_CANDIDATE`），並可多選收件人及啟用／停用。`NONE` 代表沒有逆勢訊號，不提供訂閱。
- [ ] **收件人同一事實來源**：收件人沿用目前使用者的 `notification_recipient`，不另存 email 字串；對話框顯示 email 與啟用狀態。只寄給設定有勾選且當下 `active=true` 的收件人；未建立收件人時引導至「系統設定 → 警示通知設定」。
- [ ] **正規化與租戶隔離**：每位使用者每個 `(stockCode, market)` 最多一筆 `trading_radar_notification_setting`；選定狀態存 `trading_radar_notification_state`，收件人存 `trading_radar_notification_recipient`，不得以逗號字串或重複 email 儲存。setting 帶 `owner_user_id` 與 `ownerFilter`；HTTP 讀寫須 owner-scoped，recipient ids 寫入前只接受目前使用者擁有者；背景寄送查詢再以「setting 與 recipient owner 相同」縱深防護。
- [ ] **只在進入狀態時通知**：設定建立或修改後的第一次評估只建立基準、不寄信；之後只有主 `action` 或 `counterTrendState` 從前一狀態轉入使用者勾選的狀態才通知，同一狀態持續期間不得因每筆報價或重新整理重複寄信。離開後再進入可再次通知；修改選項、收件人或重新啟用設定時重建基準，避免儲存當下立即誤寄。
- [ ] **背景評估、不依賴頁面開啟**：business 收到台股 `price-update` 後，把同輪代號合併並延遲一次評估；一般個股事件只評估該檔的 active settings，大盤 `0000/台股` 更新則評估全部 active 台股 settings。背景依 setting 的 `owner_user_id` 明確查該 owner 最新快照判斷 held，不得依賴 HTTP request filter；計算重用 `TradingRadarService`／`TradingRadarRuleEngine` 的目前規則版本（現為 `TW_RULES_V3`），不另寫第二套分數。
- [ ] **Email 行為**：同一短批次通知依收件人合併，每位收件人各寄一封以保護 email 隱私；內容至少含股票、觸發狀態、主建議、逆勢狀態、分數、現價／漲跌幅、理由／風險與行情時間。SMTP 未設定、無 active 收件人或寄信例外皆只記錄 log，不阻斷價格 SSE／既有到價警示，且不得呼叫 AI API 或外部行情 refresh。
- [ ] **API／BFF**：business 提供 `GET/PUT /api/trading-radar/notifications/{stockCode}?market=台股`，GET 聚合目前設定、可選狀態與目前使用者收件人；PUT 覆寫 active、所選 action／counter-trend states 與 recipient ids。前端只走同頁 BFF `/api/bff/trading-radar/notifications/**`。
- [ ] **驗證**：測試至少覆蓋首次基準不寄、轉入選定狀態只寄一次、同狀態不重寄、離開再進入重寄、未選狀態不寄、inactive／跨租戶收件人不寄，以及 owner-specific held 映射；backend、BFF、frontend 建置與 runtime migration／health／API／bundle 均成功。

**Requirement 44 修訂（Task 218）—— 通知抖動抑制：**

- [ ] **狀態需持穩才算轉入（去抖）**：現行 `TradingRadarNotificationTransition` 只比對「與 last 不同」，價格貼著均線震盪時（`priceVsMa` 單一門檻即造成 16 分跳動，足以跨越 55／40 等 action 邊界）會逐次評估反覆轉入而等量寄信。改為**同一新狀態需連續 N 次評估維持不變才算真正轉入**（N 預設 3；設定值存於 setting 或全域組態，不寫死於邏輯）。未達 N 次前只記錄候選狀態，不更新 baseline、不寄信。
  - **N 次 ≠ N × 2 秒**：2 秒排程是價格事件到達後的排空節拍，佇列為空時直接 return；實際評估頻率由 `PricePoller` 的台股 cron `0 0/2 9-13`（**每 2 分鐘**）決定。故 N=3 在盤中約等於 **6 分鐘**的持穩要求，而非 6 秒。調整 N 時須以此為準。
- [ ] **同檔同狀態每日寄送上限與冷卻**：同一 `(setting, 狀態)` 每個交易日最多寄 1 封；同一 setting 不論狀態每日最多 `M` 封（M 預設 4）。另設冷卻期：距上次對該 setting 寄信未滿 30 分鐘不再寄。上限與冷卻須持久化（新增欄位或計數表），不得只存記憶體——business 容器重建後不可重置而導致重複寄送。
- [ ] **降級狀態不得觸發通知也不得污染基準**：當 `StockDecision.degraded=true`（因讀取失敗而降級，見 Requirement 43 修訂）時，該輪評估一律跳過：不寄信、**不更新** `last_action`／`last_counter_trend_state`。避免「暫時失敗 → 寫入 NO_TRADE 基準 → 恢復後被判定為轉入 → 寄出假通知」的假訊號對。資料真的不足（非失敗）造成的 `NO_TRADE` 仍為正常狀態，行為不變。
- [ ] **非交易時段不評估**：`queueEvaluation` 須先檢查當下是否為台股交易日的交易時段（沿用既有交易日曆，不自建假日表）。盤後收盤校正回寫、休市日以 DB 收盤同步等情境同樣會 publish `price-update`，不得因此觸發評估與寄信。
- [ ] **單一節拍：評估與派送同輪（Task 220）**：原本評估為 2 秒排程、Email 派送另有獨立 10 秒排程。兩個獨立計時器會競爭——派送可能在評估尚未 enqueue 完成時就 drain 佇列，把同一輪的通知拆成多封信，破壞「每位收件人一輪一封」的合併。改為**由評估排程在同一輪驅動派送**：`TradingRadarNotificationDispatcher.flush()` 移除自身 `@Scheduled`，改由 `TradingRadarNotificationService` 的 2 秒節拍在評估完成後呼叫。派送須在**評估交易 commit 之後**執行，不得置於交易內（SMTP I/O 會撐長交易；且「信已寄出但交易回滾」會造成下一輪重寄）。評估或派送任一方失敗只記 log，不影響另一方與既有價格 SSE。
- [ ] **前端揭露**：通知 dialog 須提示「勾選相鄰狀態（如「續抱」與「續抱但提高警戒」）在盤中震盪時會較常觸發」，並顯示目前的每日上限與冷卻設定值。
- [ ] **驗證**：測試至少覆蓋「未達持穩次數不寄」「達持穩次數寄一次」「同日同狀態第二次不寄」「冷卻期內不寄」「degraded 輪次不寄且不改 baseline」「非交易時段不評估」；容器重建後每日計數不重置。

---

### Requirement 45: 股市大盤指數日線 Excel 匯出（開/高/低/收）與排程自動匯出到指定目錄

**User Story:** 作為使用者，我希望「股市大盤查詢」頁也能像油價金價與匯率頁一樣，指定時間區間把大盤指數的每日行情匯出成 Excel，並可設定每日自動匯出到指定資料夾，讓大盤歷史定期留存到本機目錄。

**Acceptance Criteria:**

- [ ] **匯出內容含每日開盤／最高／最低／收盤**：單一 Excel 檔、單一工作表（表名＝指數中文名，如 `台股大盤`），欄位為 **日期／開盤／最高／最低／收盤**，依日期遞增排列。四個價格欄皆取自資料庫既有的 OHLC 欄位（`twse_index_daily_history` 與 `us_index_daily_history` 的 `open_point`／`high_point`／`low_point`／`close_point`），**不重算、不由收盤價推導**。某欄當日為 `NULL`（早期資料）時該格留空，不補前值、不捏造。
- [ ] **匯出的指數＝頁面目前選取的指數**：本頁可切換 9 個指數（`TWSE`／`DJI`／`SPX`／`IXIC`／`SOX`／`FTSE`／`DAX`／`KOSPI`／`N225`），手動匯出一律匯出**目前選取**的那一個，不是固定台股大盤，也不是九個併成一檔——與畫面所見一致。
- [ ] **手動匯出（指定時間區間、瀏覽器另存）**：日線圖工具列（區間按鈕與「回補日線」旁）新增「匯出 Excel」按鈕，開啟對話框可指定起訖日期，預設帶入目前圖表所選區間。按下匯出優先開啟系統「另存新檔」對話框（`showSaveFilePicker`）供自選資料夾與檔名；瀏覽器不支援時退回一般下載。使用者按取消不顯示成功訊息。檔名 `{指數名}_{起}_{迄}.xlsx`（`YYYYMMDD`）。
- [ ] **「當日」分時模式不提供匯出**：頁面區間選「當日」時走的是 transient 分時資料（非日線表、不入庫），與本需求的日線 OHLC 不同源。此模式下匯出按鈕停用並提示改選日線區間，避免匯出一份與畫面不符的資料。
- [ ] **日期寫為文字**：比照 Requirement 40／42，日期欄以 ISO 文字寫入而非 Excel date cell，避免開啟端依時區重新詮釋而偏移一天。
- [ ] **指數標籤單一來源**：工作表名、手動匯出檔名、排程檔名三處共用同一支 `ExcelExportService.indexLabel(market)`（`TWSE`→`台股大盤`、`DJI`→`道瓊工業`…），不得各處硬編碼中文名而漂移（同 Requirement 42 的 `exchangeRateLabel`）。未知代碼直接以代碼本身為標籤，不臆造名稱。
- [ ] **指數代碼白名單驗證**：`market` 參數以既有的 `MacroHistoryService.OVERSEAS_INDEX_CODES ∪ {TWSE}` 白名單驗證，未知代碼回 400（資安 Requirement 29：不得讓任意字串流入查詢與檔名）。
- [ ] **排程／立即匯出的內容＝手動匯出的同一份活頁簿**：排程與「立即匯出到目錄」產出的檔案，與手動匯出走同一支 `ExcelExportService.exportIndexDaily(market, start, end)`，不因觸發途徑而不同（CLAUDE.md「同義欄位、同一 business service API」）。
- [ ] **每日排程自動匯出（per-user，每日單一時間）**：頁面新增「排程自動匯出」設定卡，可開啟每日排程並設定執行時間（時:分）與輸出資料夾，系統於該時間匯出 `.xlsx` 到指定目錄。每日固定一個時間（比照 Requirement 34／39／41／42）。
- [ ] **排程需可指定匯出的指數**：與 Requirement 42（單一幣別頁、刻意不設 `currency` 欄）不同，本頁**本來就有 9 個指數可選**，故排程表設 `market` 欄（預設 `TWSE`）並於設定卡提供指數下拉。此處加欄不是為不存在的需求預留，而是頁面既有維度。
- [ ] **可設定匯出時間範圍**：排程設定含「匯出範圍」（近 1 個月／3 個月／6 個月／1 年／3 年／5 年／全部十年，預設全部十年），每次執行以「執行當日往前推該範圍」計算起訖日期，讓留存檔案隨時間滾動而非固定區間。範圍以月數存於 `range_months`，「全部十年」＝ `120`；後端另接受 `NULL` 同義為全部十年，以相容從未儲存過設定的列。前端不以 `null` 表示（同 Requirement 42：`el-select` 會把 `null` 當 empty value 而顯示 placeholder）。
- [ ] **輸出路徑（家目錄為根＋相對子路徑）**：沿用 Requirement 34／37／39／41／42 的路徑模型——容器內基底目錄由 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）指定，經 docker volume 對映到 host 家目錄；使用者設定的是相對子路徑。後端一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」驗證，拒絕 `..` 跳脫與絕對路徑；寫檔時 `Files.createDirectories` 自動建立缺少的目錄。
- [ ] **資料夾選擇器沿用同一支 business API**：設定卡提供檔案總管式 `el-tree` 懶載入資料夾選擇器。目錄列舉**不新增 business 端點**，沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`；本頁僅在 BFF 新增自己的路由 `GET /api/bff/gdp-twse/export/browse` passthrough（依「一個前端頁面一個 BFF」）。
- [ ] **可手動立即匯出（驗證用）**：設定卡提供「立即匯出到目錄」按鈕（`POST /api/bff/gdp-twse/export/run-now`），立即產檔到設定目錄並回傳實際落點路徑與檔案大小；此操作**不動當日排程 guard**。
- [ ] **每使用者各自設定（owner-scoped 設定表）**：排程設定存於新表 `index_export_schedule`（每 `owner_user_id` 一列 UNIQUE、`@Filter(ownerFilter)` 隔離），欄位含啟用／時分／指數代碼／輸出子路徑／匯出範圍／上次執行日期（當日 guard）／上次執行時間與結果。
- [ ] **背景產檔不需 `enableFilter`（同 Requirement 41／42）**：指數日線為**全域公開行情**（兩張日線表皆無 `owner_user_id`、無 `@Filter`），故背景 cron 直接呼叫 `exportIndexDaily(market, start, end)` 即可，不需要 `exportXxxForOwner(ownerId)` 變體——**排程設定 per-user，但資料本身全域**。
- [ ] **排程執行機制與自癒（比照 Requirement 34／37／39／41／42）**：每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`），以 `now >= 設定時分` ＋ `last_run_date` 當日 guard 判斷（非「分鐘精確相等」，避免排程執行緒被長工作卡住跨分鐘導致整日靜默漏跑）；服務重啟以 `ApplicationReadyEvent` 補跑當日已到點未執行者；`AtomicBoolean` 防重入；單一使用者失敗只記 `last_run_status`＋log、不影響其他使用者（成功或失敗都設當日 guard）。
- [ ] **寫檔採 tmp ＋ atomic move**：先寫 `.tmp` 再 `ATOMIC_MOVE`（不支援時退 `REPLACE_EXISTING`），避免覆寫既有檔時因中途失敗留下半截殘檔。
- [ ] **檔名**：`{指數名}_{使用者ID}_{YYYYMMDD}.xlsx`。含 owner id 的原因同 Requirement 41／42：資料雖為全域，但各使用者可設不同指數與範圍，同日產出內容不同，不帶 id 會在共用目錄互相覆蓋。
- [ ] **排程列表頁需登錄**：新排程須在「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）補上對應項目，避免該頁與實際排程漂移。

---

### Requirement 46: 台股個股基本面資料每日抓取與歷史落地

**User Story:** 作為台股投資人，我希望系統每日自動抓取並**永久保存**上市櫃個股的估值與財務數據（本益比、股價淨值比、殖利率、EPS、月營收年增率、淨利與股東權益），讓交易雷達的評分能反映公司長期體質而不只是短期價格動能；且因官方開放資料只提供「當期單一快照」、不提供歷史查詢，系統必須從上線第一天起自行累積時間序列，否則永遠無法回測。

**Acceptance Criteria:**

- [ ] **資料來源與合規界線**：只使用 TWSE 開放 API（`https://openapi.twse.com.tw/`，上市）與 TPEx 開放 API（`https://www.tpex.org.tw/openapi/`，上櫃）。兩者皆免 API key、無需註冊。合規依據：TWSE／TPEx 使用條款雖禁止以爬蟲程式擷取本站資料，但明文豁免「已授權『政府資料開放平臺』提供公眾使用之資料」，`/openapi/` 正是該授權通道；政府資料開放授權條款允許程式化取用與衍生著作，義務為標示出處。**嚴禁抓取公開資訊觀測站 MOPS**（`mopsov.twse.com.tw/robots.txt` 為 `User-Agent: * / Disallow: /`，僅開放 bingbot），亦不得抓取 TWSE／TPEx 的 HTML 頁面。TWSE／TPEx openapi 本身即為 MOPS 資料的官方 JSON 轉發（TPEx 端點前綴即為 `mopsfin_`），無需另尋來源。程式碼須於 client 類別 Javadoc 標示資料來源與授權依據。
- [ ] **快照式來源必須自行落地累積（本 Requirement 的核心價值）**：實測確認 openapi 全部端點只回「當期單一快照」，且 `?date=` 參數**會被直接忽略**（傳 `date=11412` 仍回 `11506` 的資料）。故每日抓取的快照必須逐日寫入自有資料表建立時間序列；**每延後一天上線，就永久少一天且無法補回**（歷史月營收的唯一來源在 MOPS，而 MOPS 全站禁止程式化存取）。此為本 Requirement 優先於評分邏輯（Requirement 43 修訂）實作的理由。
- [ ] **整批端點，不逐檔輪詢**：`BWIBBU_ALL` 單次回傳全市場 1079 筆、`t187ap06_L_ci` 1043 筆、`t187ap05_L` 1082 筆、TPEx 對應端點 884–891 筆。抓取一律採整批單次 GET 後於本地比對落地，**不得**沿用既有 FinMind 路徑的「逐檔查詢＋`Thread.sleep(300)` 節流」模式（該模式為逐檔 API 而設，用在整批端點上會產生上千次無謂請求）。實測連續 8 次請求無節流跡象，但仍須設定 connect／read timeout 並於失敗時退避重試。
- [ ] **三張資料表，完整正規化**：新增 (a) `stock_valuation_daily`——`stock_code`／`market`／`trading_date` 為業務唯一鍵，欄位 `pe_ratio`、`pb_ratio`、`dividend_yield_pct`（皆 `numeric(12,4)` 且 **nullable**）；(b) `stock_financial_quarter`——唯一鍵 `stock_code`／`market`／`fiscal_year`／`fiscal_quarter`，欄位 `eps`（`numeric(12,4)`）、`net_income_parent`、`equity_parent`（皆 `bigint`，單位千元，與來源一致）；(c) `stock_monthly_revenue`——唯一鍵 `stock_code`／`market`／`revenue_year`／`revenue_month`，欄位 `revenue`（`bigint`，千元）、`revenue_yoy_pct`（`numeric(12,4)`）。三表皆為**全域公開行情資料**，比照 `stock_price_history` **不帶 `owner_user_id`、不套 `@Filter`**，並各帶 `updated_at TIMESTAMP NOT NULL DEFAULT now()`（稽核用，不參與計算；不另設 `fetched_date`）。寫入採 upsert（同一鍵重複抓取須為冪等）。**`market` 欄位一律填 `'台股'`**，與 `stock_price_history`／`stock` 對齊（該欄實際值域只有 `台股`／`美股`／`英股` 三種）；**不得填 `'上市'`／`'上櫃'`**——這是跨表 join key，填錯會使下游 `WHERE market='台股'` 靜默回零筆、基本面因子永遠為 `null` 而不報錯。上市／上櫃的區別不入此欄，需要時另加 `listing_board`。
- [ ] **禁止儲存衍生值**：**不得**新增 `roe` 欄位——ROE 可由 `net_income_parent ÷ equity_parent` 計算得出，儲存即違反本專案「禁止存入可從其他欄位計算得出的衍生值」的正規化規範。同理不得儲存「近四季 EPS 合計」「營收成長率均值」等聚合結果。ROE 與各項聚合一律於評分時計算。TWSE／TPEx **未提供任何現成 ROE 欄位**（兩站 swagger 搜尋「報酬率」皆無命中），此為已確認事實，不需再尋找來源。
- [ ] **ROE 計算口徑的失真必須揭露**：以 `淨利 ÷ 期末權益` 計算（來源未提供平均權益），且單季數值年化（×4）會放大旺季效應。使用此值的一方（Requirement 43 修訂的基本面組）須於理由文案標示為「近四季合計 ÷ 最新期末權益」的近似值，不得呈現為經審計的正式 ROE。
- [ ] **產業別分表必須完整涵蓋**：TWSE 的損益表與資產負債表依產業切分為 `_ci`（一般業）、`_fh`（金控）、`_ins`（保險）、`_bd`（證券）、`_mim`（其他）、`_basi` 等多支端點，只讀 `_ci` 會整段漏掉金融股（如 2881、2885、2891——皆在現有 `stock_price_history` 中有 2439 筆完整歷史，屬使用者實際持股）。抓取須涵蓋全部產業別端點；各端點欄位結構不同，須個別對應。**上櫃 TPEx 的 key 命名與上市不一致**（`mopsfin_t187ap06_O_ci` 用 `SecuritiesCompanyCode`／`Year`／`Season`，`mopsfin_t187ap07_O_ci` 混用 `SecuritiesCompanyCode` 與中文 `年度`／`季別`，`mopsfin_t187ap05_O` 全中文 `公司代號`，TWSE 對應端點則全中文），須建立明確的 key 映射表，不得假設兩市場欄位同名。
- [ ] **空值與虧損的語意區分**：`BWIBBU_ALL` 實測 1079 筆中 247 筆 `PEratio` 為**空字串**，代表該公司虧損（無正 EPS 可計算本益比），**不是**資料缺漏。落地時空字串一律寫入 `NULL`，**嚴禁以 `0` 代替**——`pe_ratio = 0` 會在下游被解讀為「本益比極低＝極便宜」而把虧損公司評為最優。同理 `eps`、`revenue_yoy_pct` 等欄位的缺值一律 `NULL`。此語意區分（虧損 vs 未抓到）須於資料表欄位註解明載。
- [ ] **虧損公司仍須寫入一列（下游區分兩種 `null` 的前提）**：當來源回傳該股票但 `PEratio` 為空字串時，**仍須寫入 `stock_valuation_daily` 一列**（`pe_ratio` 為 `NULL`，其餘欄位照常），不得因「沒有 PE」而整列略過。理由：Requirement 43 修訂規定「有列且 `pe_ratio IS NULL`＝虧損，計為 `−1`」與「當日無列＝未抓到，回 `null`」是兩種不同語意且行為相反；若虧損公司整列不寫，下游無從區分，虧損會被誤判為資料缺失而免於扣分。
- [ ] **`revenue_yoy_pct` 為刻意保留的 denormalization（例外說明）**：`stock_monthly_revenue` 同時存 `revenue` 與 `revenue_yoy_pct`，後者在自建歷史累積滿 13 個月後可由 `revenue(Y,M) ÷ revenue(Y−1,M) − 1` 重建，形式上屬衍生值。**刻意保留**，理由：本表的歷史自上線起累積，滿 13 個月前**沒有去年同月基期**，屆時 `revenue_yoy_pct` 是來源直給且**無法由本表重建**的獨立事實；捨棄它等同讓月營收 YoY 子因子首年完全不可用。比照 `realized_gain.broker`（記錄成交當下券商名稱）的既有 denormalization 例外慣例。此例外須於欄位註解與 changeset `--comment` 記載，避免日後維護者依「禁止衍生值」規則誤刪。
- [ ] **ETF 判定採資料驅動，跟隨專案既有決策方向**：判定順序為 (1) `stock.security_type` override（`STOCK`／`ETF`，nullable）；(2) **資料驅動**——該標的在 ETF 淨值資料中有紀錄即為 ETF；(3) fallback 規則——台股 `00` 開頭為 ETF。**不得以 `US_ETF_WHITELIST` 白名單作為判定依據。** 理由：該白名單有**兩份完全相同的複本**（`MarketDataService.java:378` 與 `MarketDataFetchService.java:371`，分屬 backend 與 external-materials-service 兩個 deployable），皆把 `AVGO`（Broadcom，個股）誤列為 ETF、皆漏 `SGOV`；且專案既有決策已三度明文記載棄用它——`MarketDataFetchService.java:641`「本方法即為『這檔是不是 ETF』的資料驅動判定，不需要維護 ETF 白名單（既有 `isEtf()` 白名單誤把個股 AVGO 列為 ETF、又漏掉使用者實際持有的 SGOV，刻意不複用）」，`EtfNavPoller.java:29` 與 `ExcelExportService.java:718` 同旨。本 Requirement 跟隨此既有方向，不做方向反轉。既有的兩份白名單**不在本 Requirement 修正範圍**（它們服務於其他既有路徑），但新程式碼一律不得引用。
- [ ] **`security_type` 為技術判定 override，刻意不建設定頁（例外聲明）**：本專案規範「所有業務分類必須存入資料庫並提供 `/api/settings/*` 管理端點與前端設定頁面」（如 `asset_class` 具備設定表＋seed＋`GET/POST/PUT/PATCH /api/settings/asset-classes`＋`PUT /api/settings/securities/asset-class`＋`AssetClassSettingsView.vue`＋專屬 BFF 的完整五件套）。`security_type` **刻意不比照辦理**，理由：它不是使用者可自訂的業務分類（值域固定為 `STOCK`／`ETF` 兩者，不會新增第三種），而是「這檔證券在市場上是不是 ETF」的客觀技術事實，主要由資料驅動判定得出；此欄位僅作為資料驅動誤判時的逃生口，預期使用頻率極低。故本 Requirement 只建欄位、不建設定表、不建管理端點、不建前端頁面。**若日後發現需要頻繁人工修正，即代表資料驅動判定不可靠，屆時應優先修正判定邏輯而非補設定頁。** 修正路徑（DBA 手動 `UPDATE`）須記載於欄位註解。
- [ ] **ETF 無基本面資料為預期行為**：實測 ETF 在全部基本面資料集為「整筆不存在」而非空值（`BWIBBU_ALL`／`t187ap05_L`／`t187ap06_L_ci` 中 `00` 開頭筆數皆為 0）。抓取端不得因 ETF 查無資料而記為失敗或重試，落地後三張表本就不會有 ETF 列。
- [ ] **排程、自癒與手動觸發（沿用既有慣例）**：於 `external-materials-service` 新增抓取，比照 `DividendPersister` 的骨架（低頻資料，**不經 Redis**——Redis 在本專案專用於即時價格路徑，基本面資料直接寫 PostgreSQL）。**執行時點須讀 DB 而非硬編字面時間**：沿用既有的 `crawler_schedule` 機制（`CrawlerScheduleQuery.enabledTimes(crawlerKey)`，`NewsPoller` 為參考實作——`@Scheduled(cron = "0 * * * * *")` 只當每分鐘節拍，實際觸發時點比對 DB 設定），並於 changeset 冪等 seed 一筆 `crawler_key = 'fundamental'`；表缺列或 DB 例外時 fallback 預設時間並記 WARN。**不得以「本專案排程時點一律硬編」為由略過**——該服務除 `crawler_schedule` 外另有 `commodity_export_schedule`、`exchange_rate_export_schedule`、`export_schedule_setting`、`index_export_schedule`、`realized_gain_export_schedule`、`trading_calendar_export_schedule` 共 7 張排程設定表，DB 可設定機制是既有慣例。估值資料每日抓取一次、財報與月營收每日檢查是否有新期別（公布日不固定，每日檢查最單純且不漏）。須提供 `@EventListener(ApplicationReadyEvent.class)` 開機自癒補跑當日錯過的抓取，`AtomicBoolean` 防重入（比照 `NewsPoller`；`DividendPersister` 未做此項），逐項 try/catch 使單一端點失敗不中斷整批並於結束時 log 成功／失敗計數。另須於 `InternalPriceController`（`/internal`）新增手動觸發端點供部署後驗證與失敗補救，比照既有 `/internal/dividend/sync` 慣例。
- [ ] **排程列表頁需登錄**：新增的 `@Scheduled` 須同步登錄至「公開資訊 → 排程列表」（`SchedulePublicBffController` 的靜態 `JOBS` 清單）。該清單為手動維護，漏登即造成該頁與實際排程漂移。
- [ ] **歷史回補為獨立決策，預設不實作**：TWSE 舊版 `rwd/zh/afterTrading/BWIBBU_d?date=YYYYMMDD` 可回溯至 2005-09-02、TPEx 舊 PHP 端點可回溯至民國 100 年，能一次補齊歷史 PE／PB／殖利率（使 Requirement 43 修訂的「PE 自身歷史分位」子因子立即可用，而非等待 250 個交易日累積）。但這兩個端點**並非官方指定的開放資料通道**，不在使用條款的開放資料豁免範圍內，屬合規灰色地帶。故本 Requirement **預設不實作歷史回補**；PE 分位子因子在樣本不足期間回 `null` 並由權重重分配吸收。若日後決定實作，須另立 Requirement 並明確記錄合規評估結論、限制為低頻單次執行、使用短 User-Agent `Mozilla/5.0`（長 Chrome UA 會被 WAF 擋）、並附上顯名聲明。另注意 `BWIBBU_d` 的欄位 schema 在 2017／2018 年間變更過（2015 年僅 5 欄、2018 年後才有收盤價與財報年季），parser 須依日期分支。
- [ ] **不得使用 FinMind 作為主來源**：FinMind 資料經實測與官方完全吻合（2330 於 2026-07-17 的 PER 30.79、EPS 22.08、月營收 442,679,969 千元逐項一致），但其免費層強制逐檔查詢（不帶 `data_id` 回 400），全市場掃一輪需 7 小時以上，且其免責條款明列「不得將即時資料直接呈現於 web、App 等對外介面」「不包含對外再散布」。既有價格路徑已使用 FinMind 屬既成事實，本 Requirement 不擴大其使用範圍。
- [ ] **驗證**：測試至少覆蓋——整批 JSON 解析（含上市／上櫃兩套 key 命名）、`PEratio` 空字串落地為 `NULL` 而非 `0`、同一鍵重複抓取的 upsert 冪等性、金融股產業別端點（`_fh`／`_ins`）確實被涵蓋且 2881／2885／2891 有資料落地、ETF 查無資料不記為失敗、`security_type` override 優先於規則判定、`AVGO` 修正後判定為個股而 `SGOV` 判定為 ETF。部署後須以手動端點觸發一次完整抓取，並實查三張表確認筆數與內容（非僅確認 HTTP 200）。

---

### Requirement 47: 匯率曝險與換匯估值納入交易雷達評分

**User Story:** 作為同時持有台股與台幣計價美元資產（美債 ETF）的投資人，我希望交易雷達在美元處於歷史高位時，不要把「美元升值推高的台幣報價」誤判為標的本身的多頭而建議我加碼；我要能看到某檔標的的近期漲勢有多少來自匯率、以及現在換匯划不划算，讓我不會在美元最貴的時候被系統叫進場。

**Acceptance Criteria:**

- [ ] **問題陳述（本 Requirement 的動機，皆為實測）**：交易雷達的整條請求鏈**完全沒有任何匯率輸入**——`TradingRadarService`（461 行）、`TradingRadarRuleEngine`（374 行）、`TradingRadarDto`、`AssetClassifier`、`DistributionAdjustedPriceService` 五個檔案 grep `exchange|fx|rate|匯率|currency|幣別|usd|twd` 零實質命中；規則引擎的 `StockInput` record 全部 13 個欄位中沒有任何幣別或匯率欄位。而台幣計價的美債 ETF 其台幣報價 ≈ 底層美元價 × USD/TWD，實測 2025-07-19 起近一年的日收盤與 USD/TWD 中價相關係數為 **00719B（1–3 年）0.9737、00697B（7–10 年）0.8449、00679B（20 年）0.5864**，相關性隨存續期單調遞減，符合「短債幾乎無利率風險故只剩匯率」的金融結構；日報酬相關（去除共同趨勢）00719B 為 `+0.6763`（R²=0.457），對照組 0050 為 `−0.5339`。剝除匯率後，**00719B 過去一年台幣價變動 10.34%，底層美元價值僅變動 2.05%**。且 2026-07-17 的 USD/TWD 為 32.23，處於一年期百分位 **99.2**、全歷史百分位 **91.8**，其自身亦為完美多頭排列（32.23 > MA20 32.00 > MA60 31.66 > MA240 31.29）。**結論：系統給 00719B 滿分的理由「站上月／季／年線且連續兩日確認」，與 USD/TWD 自身的均線排列是同一件事——它在美元逼近一年新高時，把「美元多頭排列」翻譯成了「債券 ETF 買進候選」。**
- [ ] **資料基礎已具備，不需新增抓取**：`exchange_rate_history` 現有 4,261 列，其中 USD 2,488 列涵蓋 2016-07-19 至 2026-07-19 整整十年，欄位為 `id / currency / rate_date / buy_rate numeric(10,4) / sell_rate numeric(10,4)`，UNIQUE `(currency, rate_date)`。抓取管線在 `external-materials-service` 的 `ExchangeRatePoller`（盤中每 5 分鐘 `0 0/5 9-15 * * MON-FRI`、收盤後 `0 0 17 * * MON-FRI`，台銀牌告為主、Yahoo 為 USD fallback、隔日由 FinMind 以真實買賣價覆寫）。本 Requirement **只消費既有資料，不新增任何抓取**。中價一律取 `(buy_rate + sell_rate) / 2`。
- [x] **必須新增「底層資產幣別」欄位（現況無法表達匯率曝險）**（已落地：`stock.underlying_currency` + `Stock.underlyingCurrency`，changeset `v1.69.0-stock-underlying-currency.sql`）：`stock` 表原欄位僅 `code / market / name / asset_class / stock_style / bond_term`，**沒有 currency 欄位**；三檔美債 ETF 的 `market` 皆為 `台股`、`asset_class` 皆為空（`BOND` 是 `AssetClassifier` 執行期推導）。故系統無法區分「以台幣交易且持有台幣資產」與「以台幣交易但持有美元資產」。須新增 `stock.underlying_currency`（`VARCHAR(10)`，nullable，值如 `TWD`／`USD`／`GBP`），null 時依規則推斷：`market` 為 `美股` → `USD`、`英股` → `GBP`、`台股` → `TWD`。**台股中持有外幣資產的 ETF 必須以此欄位顯式標記**（如 00679B／00697B／00719B 標為 `USD`），不得以名稱字串比對（如「含『美債』二字」）判斷——該做法在標的更名或新增時會靜默失效。Liquibase changeset 版號須避開已被其他任務預定的 `v1.67.0` 與 `v1.68.0`。
- [x] **匯率因子的實際落地（採扁平加權，非分組）**：`TW_RULES_V5` 的首個實作版本採**扁平的因子權重**而非巢狀分組——分組只在需要「組內先平均、組間再加權」時才有意義，而本次落地的因子彼此獨立，扁平權重的數學結果相同且少一層間接。實際權重（`TradingRadarRuleEngine` 具名常數，合計 `1.00`）：

  | 因子 | 權重 | 標準化 |
  |---|---|---|
  | MA20／MA60／MA240 位置 | `0.10`／`0.12`／`0.15` | 高於 `+1`、低於 `−1`、相等 `0` |
  | MA20／MA60／MA240 兩日確認 | `0.06`／`0.08`／`0.10` | `ABOVE +1`、`BELOW −1`、`MIXED 0`、`UNAVAILABLE null` |
  | KD 動能 | `0.08` | `clamp((K−D)/10, −1, +1)` |
  | **KD 位置** | `0.13` | `clamp(−(avg(K,D) − 50)/50, −1, +1)` |
  | 大盤 regime | `0.08` | `RISK_ON +0.5`、`NEUTRAL 0`、`RISK_OFF −1`；`BOND` 為 `null` |
  | 單日漲跌幅 | `0.05` | `≥+5%` 或 `≤−5%` → `−1`，其餘 `0`（值域 `[−1,0]`） |
  | **匯率分位** | `0.05` | `clamp(−(fxPercentile − 50)/50, −1, +1)`；`TWD` 為 `null` |

  計分：`score = 50 + 50 × Σ(w×c) / Σw`，其中 `Σw` 只累加**非 null** 因子的權重——除以 `Σw` 即為缺值權重重分配。因每個 `c ∈ [−1,+1]`，`score ∈ [0,100]` 恆成立且不需截斷。**`underlying_currency = TWD` 的標的匯率因子回 `null`**，台股標的不因匯率被加減分。長期趨勢組（年線斜率、52 週位置、3 年報酬）與基本面組尚未併入，待 Task 223／224 實作後再擴充權重表。
- [x] **回看期取五年，且此選擇必須記錄理由**：實測同一天（2026-07-17、USD/TWD 32.23）的百分位在不同回看期差異極大——**一年 99.2、三年 73.2、五年 83.6、全歷史 91.8**。取一年會判定「極貴」（貢獻 `−0.98`）、三年「偏貴」（`−0.46`）、五年（`−0.67`）、全歷史「很貴」（`−0.84`）。選定**五年**：一年過短，易被單一波段主導而使因子在趨勢行情中長期釘在極值；三年未涵蓋台幣由強轉弱的完整週期；五年實測 1247 筆、區間 27.53–33.14，涵蓋完整週期且不極端；全歷史涵蓋不同匯率制度時期，代表性存疑。**已實作**為 `TradingRadarService.FX_LOOKBACK_YEARS = 5`，調整時須同步更新此處記載。
- [x] **KD 過熱與換匯過貴採「否決買進」而非僅扣分（實作決策）**：實測證明純扣分不足以改變建議——V4 的 `K,D>80` 扣 3 分因分數飽和而完全無效（00719B 原始分 110、00697B 113，KD 從 `(5,10)` 掃到 `(95,92)` 分數恆為 100、動作恆為買進候選）。即使在 V5 修正飽和後，扣分仍只是把分數推低，一檔各項技術面全綠的標的仍可能維持在買進門檻之上。故於買進閘門增設兩道獨立否決：`avg(K,D) > 80`（短線過熱）、`fxPercentile ≥ 90`（換匯過貴）。兩者是**進場時機**問題而非標的品質問題——一檔長期結構完好的標的不該因短線過熱被判減碼，但也不該在過熱時被建議買進。實測效果：00719B（K 90.76／D 86.01）由「100 分／買進候選」變為「78 分／觀察」。
  > **本條的過熱判定式已由 Task 232（`TW_RULES_V7`）修訂為 `avg(K,D) > 80 || K > 85`**，並補上偏熱揭露與風險文案修正；`fxPercentile ≥ 90` 與本條其餘設計理由不變。**另須留意本條「否決」一詞的實際語意**：`kdOverheated` 只關閉 `buyGate`、不扣分，分數維持原值後落入 `score >= 55` 分支而得 `HOLD`／`WATCH`，即本條所述 00719B「變為觀察」的機制——實為降級而非阻斷。完整修訂內容與十年回測證據見 Requirement 43 修訂（`TW_RULES_V7`，Task 232）。
- [ ] **資料品質防護**：`exchange_rate_history` 中存在 `buy_rate = sell_rate` 的列（台銀被 WAF 擋下時的 Yahoo fallback，實測 2026-07-18／19 兩筆皆為 32.3650），其性質與有真實買賣價差的列不同（對照 2026-07-17 為 31.8950／32.5650）。分位計算須以**完成日**資料為準，並排除或標記 fallback 列；不得讓假日或 fallback 值影響分位。取不到當日匯率時該子因子回 `null`，**不得以最近一筆硬代**（匯率在假日不變動，硬代會使分位在連假期間失真）。
- [ ] **UI 必須揭露匯率貢獻，不能只給一個分數**：畫面須對有匯率曝險的標的顯示 (a) 目前 `fxPercentile` 與其回看期；(b) 該標的近一年台幣報價變動中，**剝除匯率後的底層變動**（`implied = 台幣收盤 / 當日中價`）。實測 00719B 應顯示「台幣 +10.34%／底層 +2.05%」這類對照。這是本 Requirement 對使用者最直接的價值——讓他看得出漲勢來自哪裡。
- [ ] **技術面仍以台幣報價計算（刻意的取捨，須記載）**：MA／KD／兩日確認等趨勢指標**維持使用台幣報價**，不改用剝除匯率後的 implied 序列。理由：使用者的實際部位與損益是台幣計價的，剝離後的訊號雖然更純粹地反映債券本身，卻不對應他真正承受的價格波動。匯率的影響改由本 Requirement 的匯率環境組與 UI 揭露處理。**此取捨須明文記載**：本 Requirement 已知技術面訊號對高匯率相關標的（如 00719B，相關係數 0.97）有相當部分來自匯率，選擇以「揭露＋獨立因子」而非「改變價基」處理。
- [ ] **零 AI API 與零新增抓取約束不變**：匯率分位由本地 `exchange_rate_history` 即時計算，不得寫回資料庫（屬可計算的衍生值）、不得於請求鏈觸發外部抓取、不得引入任何 LLM。
- [ ] **驗證**：測試至少覆蓋——`underlying_currency` 為 `TWD` 時匯率組回 `null` 且權重重分配後六組實際生效權重總和為 `1`；`USD` 標的在分位 99 時貢獻約 `−0.98`、分位 50 時為 `0`、分位 10 時為 `+0.80`；回看期不足三年時的行為；當日無匯率資料時回 `null` 而非沿用前值；`buy_rate = sell_rate` 的 fallback 列被正確排除或標記。部署後須實查 00719B／00697B／00679B 的匯率組分數與 UI 揭露值，並確認 0050 等台股標的完全不受影響。

---

### Requirement 48: 今日交易雷達結果快照落地 Redis 與 Excel 區間匯出

**User Story:** 作為交易雷達的使用者，我希望每次頁面產生的雷達判斷（大盤風險與我的個股決策）都被保存為帶時間戳的快照，並能指定一段時間區間，把該區間內的多個快照匯出成 Excel、於存檔當下自行指定資料夾，讓我能留存並事後比對盤中／每日的規則決策如何隨行情變化；且此保存不得危及系統既有的即時股價快取。

**Acceptance Criteria:**

- [ ] **快照寫入時機（修訂 Requirement 43「GET 無寫入」）**：`GET /api/trading-radar` 成功組出 `TradingRadarDto.Response` 後、回傳前，於同一 HTTP 請求執行緒把該份結果寫成一筆 per-owner 快照到 Redis。**僅在 `CurrentUserContext.hasUser()` 為真時寫入**（無身分／背景執行緒不得寫入，避免寫出 owner 錯亂的髒快照）；owner 取 `CurrentUserContext.getEffectiveUserId()`（admin 代看時為被代看者，與既有 `ownerFilter` 對齊）。仍維持 Requirement 43 的**無新資料表、無新增 @Scheduled、不呼叫任何外部行情或 AI API**。
- [ ] **只存 Redis、不新增資料表、不回補**：快照唯一儲存於 Redis（沿用即時股價路徑同一個 Redis、database 0）；**不新增任何 PostgreSQL 資料表**。功能上線前的歷史不回補——匯出區間早於上線日則該段無資料，屬預期。
- [ ] **保護即時股價快取（本 Requirement 的硬約束）**：此 Redis 為 `--maxmemory 256mb --maxmemory-policy allkeys-lru`，與即時股價 `price:*` 共用；快照量若不受控，記憶體壓力下 LRU 會逐出任意 key（含 `price:*`），危及即時股價功能。故快照寫入**必須**同時具備下列容量控制，缺一不可：(a) **節流**——同一 owner 兩次寫入的最小間隔為具名常數（預設 5 分鐘，可由設定覆寫），間隔內的重讀不再產生新快照（盤中頁面經既有 SSE 每約 2 秒重讀 `get()`，須靠節流收斂）；(b) **去重**——與該 owner 上一筆快照內容（**排除每次都變的 `generatedAt` 後**）相同者不寫入；(c) **壓縮**——JSON 以 gzip 壓縮，因 `StringRedisTemplate` 存字串故壓縮結果以 Base64 編碼存放。
- [ ] **保留窗、筆數硬上限與自動修剪（不新增排程）**：保留窗為可設定值（預設 **90 天**；**刻意短於 12 個月以配合 256MB 上限**）。每筆快照 value key 帶等於保留窗的 TTL；索引結構在**每次寫入時 inline 修剪**（不新增任何 `@Scheduled`），且為**兩道**：(a) 依時間窗移除超過保留窗的舊項；(b) 依 **per-owner 筆數硬上限**（可設定，預設 5000）以 `ZREMRANGEBYRANK` 只保留最新 N 筆。筆數硬上限給每個 owner 確定的 footprint 天花板，是保護共用 Redis 上 `price:*` 的關鍵——僅靠時間窗在多使用者高頻情境下仍可能累積上萬筆。保留窗為 best-effort：LRU 壓力下可能更早被逐出，匯出端須容忍缺漏；活躍使用者增長時應監控 Redis 記憶體並視需要調高 `maxmemory`。
- [ ] **Redis key 結構（per-owner，時間戳為序）**：value＝`trading-radar:snap:{ownerId}:{epochMillis}`（內容為 `Base64(gzip(JSON))`，TTL＝保留窗）；索引＝Sorted Set `trading-radar:snap:idx:{ownerId}`（member＝`{epochMillis}`、score＝`epochMillis`，供區間查詢與「取最後寫入時間做節流判斷」）；去重雜湊＝`trading-radar:snap:hash:{ownerId}`（存上一筆內容雜湊，TTL＝保留窗）。`{epochMillis}` 取自 `Response.generatedAt` 解析出的 epoch 毫秒。節流的「上次寫入時間」由索引 ZSet 的最大 score 取得。
- [ ] **寫入 fail-soft**：整個快照寫入以 try/catch 包住，任何 Redis 例外只記 log、**不得影響 `get()` 的正常回應**——頁面渲染是核心功能，快照是附加。
- [ ] **前端資訊框文案同步**：`TradingRadarView.vue` 既有資訊框「本頁只讀取系統既有 PostgreSQL 與 Redis 資料…」須改為揭露「並將每次結果快照寫入 Redis 供匯出」；仍維持不呼叫 AI API、不觸發外部行情回補的陳述。
- [ ] **匯出端點（business，由 Redis 快照組檔）**：新增 business `GET /api/trading-radar/export?from={ISO datetime}&to={ISO datetime}`，回 `ResponseEntity<ByteArrayResource>`（`Content-Disposition: attachment`、UTF-8 檔名、content-type `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`、`contentLength`，比照既有 `RealizedGainController.exportExcel`）。owner 同樣取 `getEffectiveUserId()`，只讀該 owner 快照。`from`／`to` 為 Asia/Taipei 時區的 ISO local datetime，換算為 epoch 毫秒後以 Sorted Set 區間查詢；`from > to` 或格式錯誤回 400。
- [ ] **匯出讀取須容忍缺漏且在檔內揭露**：以索引區間查得時間點後逐一讀 value；某時間點的 value 已因 TTL／LRU 逐出而讀不到時，**跳過該點、不使整份匯出失敗**。缺漏筆數須於 Excel「快照索引」分頁的彙總列標示「查得／索引預期／缺漏」（**不得只進 server log**——否則使用者看到列數變少會把「資料被逐出」誤判為「那段沒有交易」）。反序列化採 JSON tree（`JsonNode`）逐欄取值、對缺欄位容忍（相容日後 DTO 欄位增修）。
- [ ] **Excel 結構（多快照，時間為欄）**：至少三張工作表——(1)「快照索引」每列一個快照（快照時間、規則版本、大盤 regime／中文標籤／分數／stale、個股檔數、略過非台股檔數）；(2)「大盤總覽」每列一個快照的 `MarketSummary` 全欄；(3)「個股決策」每列一個（快照時間, 個股）的 `StockDecision` 全欄，**含「快照時間」欄以區分不同時刻**。**所有日期／時間欄一律以 ISO 文字寫入**（比照 Requirement 45／40／42，避免開啟端依時區偏移一天）；`reasons`／`risks` 等清單以換行合併為單格字串。POI 產檔沿用既有 `ExcelExportService` 的 Styles／cell helper 慣例。
- [ ] **區間無快照**：查得零快照時仍回一份合法 `.xlsx`（表頭齊全、首列註明「指定區間 {from}～{to} 查無交易雷達快照」），**不得回 5xx**。
- [ ] **一頁一 BFF、二進位 passthrough**：`/api/bff/trading-radar/export` 沿用既有 `TradingRadarBffRoutes`（Spring Cloud Gateway rewrite）自動轉發至 business `/api/trading-radar/export`，二進位 body 與下載 header 原樣穿透；**BFF 不新增程式**。前端只呼叫 BFF。
- [ ] **前端匯出入口與指定目錄**：`TradingRadarView.vue` 頁首新增「匯出 Excel」按鈕，開啟對話框以 datetime 區間選擇器指定起訖（預設帶入當日 00:00 至現在）。匯出優先以 `showSaveFilePicker`（File System Access API）讓使用者**自選資料夾與檔名**，瀏覽器不支援時退回一般下載；使用者按取消不顯示成功訊息（沿用 `ExchangeRateView.vue` 既有的 `canPickDirectory`／`saveBlob`／`downloadBlob` 模式）。檔名 `交易雷達_{起}_{迄}.xlsx`（`YYYYMMDDHHmm`）。`api/index.js` 的 `tradingRadar` 區塊新增 `exportExcel(from, to)`（`responseType: 'blob'`）。
- [ ] **零 AI API 不變**：快照寫入與匯出全程只用本地 Redis 與 POI，不呼叫任何 LLM、不觸發外部行情抓取。
- [ ] **驗證**：單元測試至少覆蓋——節流間隔內不重複寫、內容未變（排除 `generatedAt`）不重複寫、gzip＋Base64 往返可完整還原、保留窗修剪移除逾期索引項、寫入例外不影響 `get()` 回應；匯出多快照產出三分頁且時間欄為文字、value 缺漏被跳過而不失敗、空區間回含表頭的合法檔。部署後以帶 `X-User-*` header 的容器內請求觸發 `get()` 寫入快照、以 `redis-cli` 確認索引與 value（含 TTL），再打 `export` 取回 `.xlsx` 確認三分頁與內容，並確認 `price:*` 即時股價 key 未被大量快照擠出。

**Requirement 48 追加（Task 231）—— 排程自動匯出到指定伺服器目錄（比照公開資訊爬蟲的多時間點設定）：**

- [ ] **本追加修訂上述兩項約束（範圍限縮，不是推翻）**：上述 AC 原寫「**不新增任何 PostgreSQL 資料表**」與「保留窗修剪 inline、**不新增任何 `@Scheduled`**」。本追加修訂為：(a) **快照本身仍只存 Redis、不新增任何快照資料表**；新增的兩張表只存**排程設定**（執行時間點與輸出資料夾），不存任何雷達結果。(b) 新增的 `@Scheduled` **只負責排程觸發寫檔**；**保留窗修剪仍維持在快照寫入路徑 inline 完成**，不改為排程。(c) Requirement 43 的「不呼叫任何外部行情或 AI API」不變。
- [ ] **與既有手動匯出並存**：本追加**不取代**上述「前端按鈕 → 指定時間區間 → 瀏覽器下載（`showSaveFilePicker` 自選資料夾）」。兩種途徑並存，且**共用同一支產檔邏輯**，不得各自實作而使內容漂移。
- [ ] **多個執行時間點（比照「爬蟲執行時間設定」卡）**：使用者可設定**多個**每日執行時間點，每個時間點可獨立啟用／停用、移除，並可新增。**每個時間點各自持有當日 guard（`last_run_date` 在時間點列上），不得只在 owner 層設一個** ——否則同日設多個時間點只會跑第一個。
- [ ] **輸出資料夾（per-owner、伺服器端）**：使用者設定一個輸出資料夾；實際寫入路徑＝容器基底 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`，docker volume 對映主機家目錄 `/Users/steven`）resolve 使用者設定的**相對子路徑**。**只存相對子路徑**；絕對路徑與 `..` 跳脫一律於 service 層擋下（`base.resolve(sub).normalize()` 必須仍 `startsWith(base)`）。目錄不存在時 `Files.createDirectories` 自動建立。
- [ ] **正規化：時間點與資料夾分兩張表**：`trading_radar_export_time` 一列一時間點（`owner_user_id`／`run_hour`／`run_minute`／`enabled`／`last_run_date`，UNIQUE `(owner_user_id, run_hour, run_minute)`）；`trading_radar_export_setting` 一使用者一列（`owner_user_id` UNIQUE、`output_subpath`、`last_run_at`、`last_run_status`）。**不得把資料夾併進時間點表**——併入會讓同一路徑隨時間點列數重複儲存（違反 CLAUDE.md 正規化），且刪一個時間點會連帶弄丟路徑（沿用既有 `crawler_export_setting` 與 `crawler_schedule` 分表的同一理由）。兩表皆帶 `owner_user_id` 與 `@Filter(ownerFilter)`。
- [ ] **寫檔內容＝當日全部快照、一天一檔覆寫**：每個時間點觸發時，讀該 owner **當日 00:00 至觸發當下**的 Redis 快照，產出與手動匯出**相同結構**的三分頁 Excel；檔名 `交易雷達_{使用者ID}_{YYYYMMDD}.xlsx`，**同日多個時間點覆寫同一檔、跨日產生新檔**（比照爬蟲 `public_info_<日期>.json` 的行為）。檔名含使用者 ID 的理由同 Requirement 45：多使用者可能指向同一共用目錄，不帶 ID 會互相覆蓋。
- [ ] ~~**當日零快照時不得寫出空檔**：快照**只在使用者開啟／刷新雷達頁的 HTTP 路徑產生**（背景不產生快照），故使用者當天若在排程時間點前從未開過雷達頁，該 owner 當日區間會**查無快照**。此時**不得寫檔**（避免每天在目錄留下只有表頭的無用檔案，並保留前一版檔案不被覆蓋），改為只把 `last_run_status` 記為「當日尚無快照，未產檔」，且**仍設當日 guard**（避免每 poll 重試整天）。此行為須於前端設定卡明示，讓使用者理解「排程是把你當天看過的雷達倒出來，不是排程自己去算」——與爬蟲「排程自己去抓」的語意不同。~~
  > **已由 Requirement 48 追加（Task 260）推翻。** 保留原文為決策記錄。此 AC 的「背景不產生快照、當天沒開過頁就不產檔」正是 2026-07-30 缺檔事故的直接原因（09:10 排程因當日無快照而略過，當日第一筆快照到 11:43 才由人工開頁產生，檔案遲到 2.5 小時）。新行為見下方 Task 260 追加段：**每次產檔前一律由背景重算**，不再依賴使用者是否開過頁。
- [ ] **排程機制（比照既有匯出排程，不得自創）**：`@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")` 每分鐘 poll；以 **`now >= 設定時分` ＋ 該時間點的 `last_run_date` 當日 guard** 判斷（**非「分鐘精確相等」**——排程執行緒被長工作卡住跨分鐘會造成整日靜默漏跑）；`@EventListener(ApplicationReadyEvent.class)` 開機自癒補跑當日已到點未執行者；`AtomicBoolean` 防重入；**單一使用者失敗只記 `last_run_status` ＋ log、不影響其他使用者**，且成功或失敗都設當日 guard（避免命中分鐘後每 poll 重試整天）。
- [ ] **寫檔採 tmp ＋ atomic move**：先寫 `.tmp` 再 `ATOMIC_MOVE`（不支援時退 `REPLACE_EXISTING`），避免覆寫既有檔時中途失敗留下半截殘檔。
- [ ] **背景無 request context 的 owner 取得**：背景排程沒有 HTTP request，`ownerFilter` 不啟用，`findAll()` 讀全部 owner 的時間點列；**owner 一律取自該列的 `owner_user_id` 並顯式傳入快照讀取**（Redis 快照 key 本就 owner-scoped，不依賴 Hibernate filter）。背景路徑**不得**依賴 `CurrentUserContext`（request-scoped，背景取不到）。
- [ ] **資料夾選擇器沿用既有 business API**：**不新增目錄列舉端點**，沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`；本頁僅在自己的 BFF 增加 passthrough 路由（一頁一 BFF）。
- [ ] **可手動「立即匯出到目錄」**：提供立即觸發鈕，走**同一支**寫檔邏輯產檔到設定目錄，回傳實際落點路徑與檔案大小；此操作**不動當日 guard**。
- [ ] **排程列表頁需登錄**：新增的 `@Scheduled` 須同步登錄至「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`），避免該頁與實際排程漂移。
- [ ] **驗證**：單元測試至少覆蓋——同日多個時間點各自 guard（兩個時間點各跑一次而非只跑第一個）、`now >= 時分` 的補跑、停用的時間點不跑、`..` 路徑跳脫被擋、單一 owner 失敗不影響其他 owner。部署後實際設定兩個時間點與一個資料夾，確認到點在主機家目錄對應路徑產生 `交易雷達_{id}_{日期}.xlsx`、同日第二個時間點覆寫同一檔、內容為當日全部快照。

**Requirement 48 追加（Task 260）—— 排程產檔前一律由背景重算雷達，不再依賴使用者是否開過頁：**

> **事故起因（2026-07-30，使用者報「今早交易雷達檔案沒有匯出至 google drive」）：** 該 owner 設有 09:10 與 11:45 兩個時間點。09:10 那一輪照常執行，但因當日 Redis 區間查無快照而依上述已推翻的 AC 略過——log 為 `交易雷達排程匯出略過（當日無快照）owner=1 9:10`，`syncGdrive(ownerId, null, "當日無交易雷達快照")` 完全不上傳。當日第一筆快照是 **11:43:11**（人工開頁產生），故 11:45 那一輪才成功（本機 ＋ Drive 皆成功，10166 bytes）。Drive 上 `交易雷達_1_20260729.xlsx` 的 createdTime 為台北 **09:10:02**、`交易雷達_1_20260730.xlsx` 為 **11:44:38**，直接佐證前一日 09:10 有上傳、當日沒有。**不是 Drive／rclone 故障**（同一上午其他七支排程匯出全部成功、零失敗）。
>
> **這不是第一次，且不只是「遲到」——10 天內至少三次，其中兩次整日缺檔。** 對 Redis 索引 `trading-radar:snap:idx:1` 與輸出目錄逐日交叉比對（台股交易日以 `twse_index_daily_history` 當日有無日 K 判定）：
>
> | 日期 | 台股 | 當日快照 | `交易雷達_1_*.xlsx` |
> |---|---|---|---|
> | 2026-07-23（四） | 交易日 | **0 筆** | **整日缺檔** |
> | 2026-07-25（六） | 休市 | 1 筆（03:42） | 有檔（新規則下休市日將不再產出） |
> | 2026-07-26（日） | 休市 | 0 筆 | 無檔 |
> | 2026-07-27（一） | 交易日 | 首筆 **13:45** | **整日缺檔**（09:10／11:45 兩輪皆空手） |
> | 2026-07-30（四） | 交易日 | 首筆 11:43 | 遲到 2.5 小時 |
>
> 即這是「排程隱性依賴使用者當天先開過頁」的設計**反覆**失效，而非單次意外；使用者只是這次注意到。**不得**以「往前數日第一筆快照都落在凌晨、故一向有快照可倒」為由把它當成孤例——7/23 與 7/27 已否證該說法。
>
> **證據時效：** 上述 Redis 筆數與「當日第一筆＝11:43:11」為 2026-07-30 調查當下的時點快照；雷達頁開啟時會持續累積新快照，故當日筆數事後無法回溯覆核（載重事實「09:10 當下為 0 筆」已由該時刻的 log 行獨立佐證）。DB 狀態列、Drive createdTime、輸出目錄檔名可隨時重查。

- [ ] **每次產檔前一律重算（本追加的核心）**：排程時間點觸發時，**不論當日 Redis 區間是否已有快照**，一律先由背景重新計算一份該 owner 的雷達結果並寫成一筆快照，再產檔。**不得只在「查無快照」時才補算**——只補空窗的話，早上 09:10 產出的檔仍會是使用者凌晨開頁時的判斷，與 09:10 的實際盤勢不符，等於把「排程匯出」變成「歷史快照轉檔」。使用者於 2026-07-30 明示：「交易雷達應該每日產檔前，根據資料庫資料、搜尋可得資料，客觀判斷出是否該交易，**每次產檔前都要做**」。
- [ ] **重算前先回補台股即時行情（推翻 Requirement 43／48 對排程路徑的「零外部行情抓取」）**：重算前先呼叫**既有**的台股行情回補路徑（Task 249 已落地的 `PriceQueryService.refreshTradingRadarPrices()`，business → external-materials-service → 寫 Redis `price:台股:{code}`），使重算用的是當下最新價而非兩分鐘前的排程值。**本項推翻的範圍限縮說明**：Requirement 43 修訂（Task 249）原文寫「推翻的只有『外部行情抓取』這一項，且**只限使用者明確按下按鈕的那一條路徑**」；本追加把該推翻的適用範圍**擴大到排程產檔路徑**。**仍然完全不變的禁令**：不得注入或呼叫 `MarketAnalysisService`、Anthropic、OpenAI 或任何 LLM／AI client；不得觸發公開資訊（新聞）爬蟲；不新增資料表。使用者選擇的資料來源即「回補台股即時行情後再算」，**明確不含新聞／網路搜尋**。
- [ ] **回補不得成為產檔的單點故障**：回補逾時／失敗／BUSY／冷卻時**仍須以現有 Redis 值繼續重算並產檔**，只記 log。外部服務不可用不得使當日缺檔——那會用一個新的失敗模式換掉舊的。
- [ ] **一輪只回補一次、與使用者按鈕共用冷卻語意但不共用 owner 冷卻鍵**：回補清單本就是全庫台股標的（跨租戶共用的市場資料），故同一輪 tick 中有多位 owner 到點時**只回補一次**，再逐 owner 重算。背景路徑**不得**沿用 `TradingRadarRefreshService.refreshAndGet()`——它的冷卻鍵取自 request-scoped 的 `CurrentUserContext`，背景無 request context 會落到 `anonymous` 且會與使用者按鈕互相燒掉冷卻；背景直接呼叫 `PriceQueryService.refreshTradingRadarPrices()`。
- [ ] **背景重算必須顯式 owner-scoped**：`TradingRadarService.get()` **不可**直接重用於背景——它的 `loadLatestHoldings()` 走 `AssetSnapshotRepository.findLatestWithStocks()`、`loadWatchList()` 走 `StockAlertRepository.findDistinctStockCodeMarket()`，兩者都無 owner 條件、靠 request-scoped 的 `TenantFilterAspect` 啟用 Hibernate `@Filter(ownerFilter)`；背景無 request context → filter 不啟用 → **會撈到全部租戶的持股與觀察清單，產出跨租戶污染的快照**。背景重算須走**新的顯式 owner 參數路徑**：持股用既有的 `AssetSnapshotRepository.findLatestWithStocksByOwnerUserId(ownerId)`，觀察清單新增 owner-scoped 查詢。背景路徑一律不得觸碰 `CurrentUserContext`。
- [ ] **背景重算的快照寫入須略過去重與節流**：既有 `TradingRadarSnapshotStore.save()` 有 (a) 同 owner 5 分鐘節流、(b) 與上一筆內容（排除 `generatedAt`）相同即不寫的去重。背景補產若沿用該路徑，**在行情未變動時會被去重靜默擋掉**（例如休市日或連假，內容與前一筆逐位元相同）→ 當日仍無快照 → 仍不產檔，等於本次修法無效。故須提供**只供背景產檔使用**的寫入入口，略過節流與去重，其餘（gzip＋Base64 壓縮、value TTL＝保留窗、索引 ZSet、兩道 inline 修剪）**完全沿用不得另寫一套**。
- [ ] **重算的快照是 append，不取代當日既有快照**：新快照以自身 `generatedAt` 為 key 加入當日索引；匯出仍是「當日 00:00～觸發當下」的**累積**區間。故同日多個時間點各自 append 一筆，11:45 的檔會同時含 09:10 那一筆——**不得為了「只匯出最新一筆」而改動匯出區間語意**，那會弄丟當日盤中變化，正是 Requirement 48 原本要留存的東西。
- [ ] **台股休市日不產檔**：排程時間點到達時，若當日非台股交易日（`MarketDataService.isTradingDay("台股", today)` 為 false，沿用既有交易日曆、不自建假日表），**不重算、不回補、不產檔、不上傳 Drive**，只把 `last_run_status` 記為「非台股交易日，未產檔」，且**仍設當日 guard**（避免每 poll 重試整天）。使用者於 2026-07-30 明示選擇「休市日不產檔」。**此限制只適用排程路徑**：手動「立即匯出到目錄」（run-now）是使用者明確觸發、其用途就是驗證落點，休市日仍須可用並照樣重算，否則週末無法驗證部署。
- [ ] **重算失敗時的降級**：背景重算或快照寫入擲例外時，**回退為使用當日既有快照產檔**（若有）；當日確實一筆都沒有才維持既有的「當日尚無快照，未產檔」狀態與不寫檔、不上傳。即重算是「盡力讓檔更新」，不是「產檔的新前提」。
- [ ] **不新增 `@Scheduled`，但排程列表頁的說明文字必須改**：重算掛在既有 `tick()`／`selfHealOnStartup()` 流程內，**不新增任何排程方法**，故「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）**不需新增項目**；**但既有「交易雷達匯出」那一筆的 `description` 必須改寫**——它現在明文寫著「命中執行時間即把當日 Redis 快照產出 Excel 到指定目錄；**當日尚無快照則略過不產檔**」，該句在本追加後為假。須改為揭露「命中執行時間即**先回補台股即時行情、由背景重算一次雷達並寫入快照**，再把當日快照產出 Excel 到指定目錄；**台股休市日不產檔**」，並保留原有的「輸出含 Google Drive 同步（若已啟用）」。**理由與 Requirement 51 完全相同**：該 Requirement 同樣不新增 `@Scheduled`，卻明文要求「八個對應排程的 description 須補『輸出含 Google Drive 同步（若已啟用）』，避免該頁與實際行為漂移」。「不新增 `@Scheduled` ⇒ `JOBS` 不必動」這個推論在本專案已被否決過一次，不得重犯。
- [ ] **背景寫入會佔用該 owner 的節流窗與去重基準（刻意取捨，須揭露）**：既有節流是「與該 owner 索引 ZSet 最大 score 的間隔 < 5 分鐘即不寫」，去重基準是 `trading-radar:snap:hash:{ownerId}`。背景重算寫入的快照同樣進索引、同樣更新該雜湊，故**使用者若在排程時間點後 5 分鐘內開頁，該次開頁可能不再產生新快照；內容與背景那筆相同時亦會被去重擋下**。這是 Task 260 之前不會發生的可觀察行為變化。判定為可接受：兩者都代表「這段時間內雷達判斷沒有新資訊」，快照少一筆不影響匯出內容（背景那筆已在當日區間內）。**不得**為此讓背景寫入繞過索引或不更新雜湊——那會使去重基準停在更舊的內容，語意更難推理。
- [ ] **前端文案同步（兩處）**：(a) `TradingRadarView.vue` 排程卡說明現為「匯出內容為『當日已產生的雷達快照』；若當天還沒開過本頁，該次排程會略過不產檔。」，須改為揭露「每次排程產檔前會**先回補台股行情並重新計算**一次雷達，不需先開本頁；台股休市日不產檔」。(b) 頁首資訊框現為「按下『重新整理』會先回補一次台股行情再重算；頁面自動更新與其餘操作只讀取既有 PostgreSQL 與 Redis 資料。」，其中「只讀取既有資料」的陳述在排程路徑已不成立，須補述排程產檔亦會觸發回補。仍須維持「不送出任何 AI API 請求」的陳述。
- [ ] **驗證**：單元測試至少覆蓋——當日零快照時排程仍產檔（推翻舊行為的回歸錨點）、當日已有快照時仍重算並 append（不是只在空窗才算）、背景重算走 owner-scoped 查詢且不觸碰 `CurrentUserContext`、回補失敗仍產檔、休市日不產檔且仍設 guard 且不呼叫回補、重算擲例外時回退用既有快照產檔、背景寫入不被去重擋下。部署後於台股休市日以 run-now 驗證重算產檔可用；下一個交易日確認 09:10 那一輪在使用者未開頁的情況下即產出檔案並上傳 Drive（`gdrive_last_status` 為成功、Drive 檔案 createdTime 落在 09:10）。

### Requirement 49: 資產交易紀錄（手動買賣流水帳）與 Excel 手動／每日排程匯出

**User Story:** 作為使用者，我希望在「資產管理」下有一個「交易紀錄」頁面，能手動逐筆記錄我的股票與基金買進、賣出交易（含日期、數量、單價、成交金額、券商／通路、幣別等），依年度檢視與篩選，並能把交易紀錄手動下載成 Excel、或設定每日自動匯出到指定資料夾定期留存。

> **定位（與既有功能的關係，務必先讀）：** 「交易紀錄」是一份**獨立的買賣流水帳**（flow），記錄每一筆買進／賣出事件；與既有「已實現損益」（Requirement 6，僅記賣出且已結算之損益）、「歷年資產快照」（Requirement 1，某時點的資產存量 snapshot）語意不同。三者**互不自動衍生、不共用資料表**：交易紀錄不會自動產生／修改 `realized_gain` 或 `stock_holding`／`asset_snapshot`，也不由它們反推，避免「同一事實跨表存兩份」造成不一致（CLAUDE.md 資料庫正規化）。本需求範圍即為這份流水帳本身的 CRUD 與匯出，**不涉及**用交易紀錄重算持股或損益。

**Acceptance Criteria:**

- [ ] **手動逐筆 CRUD**：支援手動新增、編輯、刪除單筆交易紀錄；每筆記錄以下欄位——交易類型（買／賣）、資產類型（股票／基金）、資產名稱（必填）、資產代號、市場（股票用；基金可空）、幣別（TWD／USD）、券商／通路、交易日期（必填）、數量（股數／單位數）、成交單價（原幣，支援小數 6 位——輸入、儲存、明細顯示、Excel 匯出一致，Task 239）、成交金額（原幣，含手續費／交易稅後之實際交割金額，必填）、交易當天匯率（USD 計價用；**Task 251 起改為依交易日期自動帶入的唯讀欄，不再由使用者手填**，見下方三條）、備註。
- [ ] **代號優先、輸入代號自動帶出股名**：新增／編輯表單將「代號」欄置於「資產名稱」欄之前（交易標的通常為股票，先填代號較符合輸入習慣）；當資產類型為股票且已選市場時，於代號輸入完成（blur/change）自動查 stock 主檔帶出資產名稱——找不到時不覆寫（由使用者手填）、使用者已手填的名稱亦不覆寫。此查名走與其他頁面**同一支** business API `GET /api/stock-alerts/lookup-name?code=&market=`（CLAUDE.md「同義欄位、同一 business service API」，比照已實現損益頁），本頁僅在自己的 BFF 新增 `GET /api/bff/transaction/lookup-name` passthrough（一頁一 BFF），**不新增** business 端點；資產類型為基金時不查主檔。
- [ ] **交易類型與資產類型為資料驅動、非寫死 enum**：交易類型（買／賣）與資產類型（股票／基金）以字串存入欄位；沿用既有 `MarketType`（市場）與 `BrokerEntity`（券商）主檔提供下拉選項，不新增寫死 enum（CLAUDE.md「禁止 Enum 寫死」）。基金通路若無對應主檔則以自由文字輸入，記錄成交當下名稱字串。
- [ ] **正規化：不存衍生值**：`成交金額(amount)` 為含費用後之實際交割金額、與 `數量 × 單價` 不必然相等（手續費、交易稅、零股撮合價差），故 `數量`／`單價`／`成交金額` 三者為各自獨立輸入、非彼此衍生（比照既有 `realized_gain` 同時存 `shares`／`salePrice`／`proceeds`／`investmentCost` 的慣例）。`年度` 由 `交易日期` 即時衍生（`@Transient`，不入庫）；`台幣成交金額` 由 `幣別＝USD ? amount × exchangeRate : amount` 於 DTO 層即時計算，不入庫（CLAUDE.md「禁止存入可計算得出的衍生值」）。**交易紀錄不計算損益**（損益是已實現損益頁的職責，流水帳只記事實）。
- [ ] **券商／通路為歷史名稱字串（刻意 denormalize）**：`券商／通路` 記錄成交當下的名稱字串（比照 `realized_gain.broker` 的既有例外），即使日後該券商主檔改名或停用，歷史交易仍顯示成交時的名稱。
- [ ] **多租戶隔離**：交易紀錄為 per-user 私人資料，新表 `asset_transaction` 帶 `owner_user_id`（nullable=false）與 `@Filter(ownerFilter)`；HTTP 情境（BFF→business）由 `TenantFilterAspect` 自動 owner-scoped 到本人，各使用者只能存取自己的交易紀錄。
- [ ] **列表與年度篩選**：列表預設依交易日期新到舊排序，可依年度篩選（年度由 `交易日期` 即時衍生）；提供各年度筆數／買賣別統計等彙總資訊供頁面檢視（彙總於伺服端 business service 預先計算、BFF 聚合後回傳，前端只 render，比照既有 BFF 規範）。
- [ ] **市場 tab 篩選**：明細表上方提供市場 tab（全部／台股／美股／英股，比照已實現損益頁的 `el-tabs`），前端客戶端依 `market` 過濾當前（年度篩選後）明細列；「全部」不過濾。與年度篩選為 AND 關係。
- [ ] **匯率不由使用者填寫，依交易日期自動帶出（Task 251）**：USD 計價的交易，「匯率」欄改為**唯讀自動帶入**——使用者選定「交易日期」後系統即依該日期查出匯率填入，欄位不再接受手動輸入。取值走與快照（Requirement 1）、美股持股交易日匯率（Requirement 3）、已實現損益**同一支** business API `GET /api/market-data/exchange-rate/on-date?currency=USD&date=`，取其**中間價 `midRate`**（＝(即期買入＋即期賣出)/2，四捨五入至小數 4 位），底層為 `ExchangeRateHistoryRepository.findClosestRate`＝**該日或之前最近一筆**（closest-on-or-before）。本頁僅在自己的 BFF 新增 `GET /api/bff/transaction/exchange-rate?date=` passthrough（一頁一 BFF），**不新增 business 端點、不新增第二份查詢實作**（CLAUDE.md「同義欄位、同一 business service API」）。口徑刻意採中間價而非即期買入：`asset_snapshot.usd_exchange_rate`／`stock_holding.transaction_exchange_rate`／`realized_gain.exchange_rate` 三處同義欄位皆為中間價，只有基金估值（`FundNavService`）刻意用即期買入，本欄語意屬前者。
- [ ] **匯率回退取值時必須揭露實際採用的日期**：`exchange_rate_history` 有假日缺口——近 365 個日曆日僅約 72%（263 天）有 USD 列，週末一律沒有，農曆年、清明、國慶等國定假日的平日也沒有，最長須往前退 9 天（2026-02-13 → 02-23）。故查得的匯率日期經常不等於交易日期，UI **必須顯示實際採用的匯率日期**（例「2026-02-13 匯率」），不得讓使用者誤以為那是交易當天的牌告價。查無任何列（交易日期早於 USD 匯率主檔起點 2016-07-29，business 端回 404）時欄位留空並提示，**仍允許儲存**（匯率存 null、台幣成交金額退回原幣金額，與現行 null 行為一致），不得因查不到匯率而擋住使用者存檔。
- [ ] **「查無牌告」與「查詢失敗」必須分開處理，且失敗時要留手動逃生口**：兩者後果不同，不得共用同一句提示。**查無**（business 回 404，即該日之前沒有任何 USD 列）＝資料本來就不存在，欄位留空、維持唯讀、可直接儲存。**查詢失敗**（business 5xx、逾時、BFF 與 business 之間連線中斷）＝資料可能存在只是取不到，此時必須(a)顯示與查無不同的訊息、(b)**暫時解除匯率欄的唯讀狀態讓使用者可手動輸入**。理由：本需求把手填欄改為唯讀，等於拿掉了使用者原本唯一的輸入手段；若查詢失敗時仍鎖著，使用者將完全無法記下一筆金額正確的 USD 交易——而 `exchangeRate` 為 null 的 USD 交易，其台幣成交金額會等於美元金額（少算約 32 倍）並直接計入年度彙總 `totalBuyAmountTwd`／`totalSellAmountTwd`。**「不要由我填」的訴求是針對正常情況**，不是要求在系統故障時也不准填。
- [ ] **編輯既有紀錄不得覆寫已存的匯率**：開啟編輯對話框時一律顯示該筆自己已存的 `exchangeRate`，**不重查、不覆寫**；只有在使用者**實際改動了交易日期**、或該筆原本就沒有匯率（null）時才重新查詢。此保護必須以「開啟編輯時另存一份原值 ＋ 交易日期是否被改動的顯式旗標」實作，**不得**僅依賴「匯率欄目前是否為空」這種可被其他操作破壞的狀態——例如在編輯中把市場由美股改成台股（幣別轉 TWD、匯率欄清空）再改回美股，若僅憑空值判斷就會觸發重查並覆寫該筆原本的匯率，而交易日期從頭到尾沒被碰過。理由有二且皆有實據：(a) 既有資料中存在使用者刻意填入、與當日中間價不同的值（`asset_transaction` id=63，MSFT 2026-01-29 存 31.5000，該日中間價為 31.2350、即期賣出為 31.5700），那可能是券商實際扣款匯率；(b) `exchange_rate_history` 的當日列會被隔日 FinMind 的真實買賣價覆寫（`realized_gain` 已有 4 組「存檔值 ≠ 事後重算值」的實例，如 2026-04-21 存 31.4850／現算 31.4200），無條件重查等於用事後修訂過的數字改寫歷史交易。交易紀錄的匯率是**成交當下凍結的事實**，比照同表「券商／通路記錄成交當下名稱字串」的既有 denormalization 例外。
- [ ] **新增表單的市場預設跟隨當前市場 tab（Task 250）**：按「新增交易紀錄」開啟表單時，「市場」欄預設值＝當前市場 tab（台股／美股／英股）；tab 為「全部」時預設「台股」（維持既有行為）。幣別隨之連動（美股／英股→USD，其餘→TWD），沿用既有「市場改變時自動切換預設幣別」的同一套判斷，不另立第二套。**只影響新增**：編輯既有紀錄一律帶入該筆自己的 `market`，不受 tab 影響；表單開啟後使用者自行改選的市場不得被 tab 覆寫。理由：使用者在美股 tab 下按新增，十之八九要記的就是美股交易，預設寫死「台股」使每一筆美股交易都得多改兩個欄位（市場＋幣別）。同一行為同步套用於已實現損益頁（Requirement 6）。
- [ ] **一頁一 BFF**：前端只呼叫 `/api/bff/transaction/*`（新增 `TransactionBffController`），不直接呼叫 business `/api/asset-transactions/*`；下拉選項（市場／券商）由 BFF 聚合取得（比照 `RealizedGainBffController` 以 `Mono.zip` 同時取資料與下拉主檔）。
- [ ] **選單與路由**：於「資產管理」子選單（`frontend/src/App.vue` 的 `mainMenuItems`）新增「交易紀錄」項，並在 `frontend/src/router/index.js` 註冊對應路由（`path`／`title`／`icon` 兩處保持一致，比照既有「已實現損益」）。
- [ ] **手動匯出（瀏覽器下載）**：頁面提供「匯出 Excel」按鈕（`GET /api/bff/transaction/export` → business `GET /api/asset-transactions/export` → `ExcelExportService.exportAssetTransactions()`），瀏覽器直接下載 `.xlsx`，單張「交易紀錄」sheet、涵蓋**所有年度**（含 `年度` 欄），owner-scoped。欄位順序與明細表一致（資產名稱／代號／交易類型／資產類型／交易日期／數量／單價／成交金額／台幣成交金額／市場／幣別／券商通路／匯率／年度／備註）。
- [ ] **排程／立即匯出的內容＝手動匯出的同一份活頁簿**：排程與「立即匯出到目錄」產出的檔案內容，與手動下載完全一致（同一 `writeAssetTransactionsSheet()`、同樣涵蓋全部年度），不因觸發途徑而不同（CLAUDE.md「同義欄位、同一 business service API」）。
- [ ] **每日排程自動匯出（per-user，可設定多筆排程；Task 255 起）**：使用者可在交易紀錄頁「排程自動匯出」卡**新增多筆**每日排程，每筆各自持有：名稱（選填）、啟用開關、每日執行時間（時:分）、輸出資料夾、Google Drive 同步設定，以及各自的上次執行時間／結果。系統於**每筆排程各自的時間**把該使用者的交易紀錄匯出成 `.xlsx` 到該筆設定的目錄。單筆仍是「每日固定一個時間」（不做多時段欄位、不做週期性 cron 字串、不設交易日閘門）——**要一天匯出多次或匯到多個資料夾，就新增多筆排程**，語意單純且每筆的執行狀態可各自檢視。**每人最多 10 筆**（超出回 400）：每分鐘 tick 會逐列處理，無上限等於讓單一使用者無限放大背景工作量。
- [ ] **每筆排程完全獨立、互不影響**：當日 guard（`last_run_date`）、`last_run_at`、`last_run_status` 與 Drive 狀態欄皆為**每筆各自持有**；某筆失敗（目錄不存在／權限不足／Drive 上傳失敗）只寫入該筆自己的狀態欄並記 log，不影響同一使用者的其他排程，也不影響其他使用者（比照既有「單一使用者失敗不影響其他使用者」再往下一層）。
- [ ] **排程 by-id 存取必須驗歸屬（多租戶關鍵）**：改為多列後，`PUT`／`DELETE`／`run-now` 一律帶排程 id。Hibernate `@Filter(ownerFilter)` **不套用於 `findById`／`deleteById`**（Requirement 28 的既知缺口），故 by-id 操作必須以 `findByIdAndOwnerUserId` 或 `TenantGuard.assertOwned` 驗證歸屬，否則可用他人排程 id 讀取／修改／刪除／立即觸發他人的排程（含改成把他人資料寫到自己看得到的目錄）。查無或非本人一律回 404（不區分兩者，避免洩漏他人資源是否存在）。
- [ ] **輸出路徑（家目錄為根＋相對子路徑）**：沿用 Requirement 34／39 的路徑模型——容器內基底目錄由 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）指定，經 docker volume 對映到 host 家目錄；使用者設定的是相對子路徑（例 `input` → host `/Users/steven/input`；空字串＝家目錄根，後端正規化為預設 `input`）。後端一律以「基底 resolve 子路徑後 normalize 必須仍在基底內」驗證，拒絕 `..` 跳脫與絕對路徑；寫檔時 `Files.createDirectories` 自動建立缺少的目錄。
- [ ] **資料夾選擇器沿用同一支 business API**：設定卡提供檔案總管式 `el-tree` 懶載入資料夾選擇器。目錄列舉**不新增 business 端點**，直接沿用 Requirement 34 既有的 `GET /api/export-schedule/browse?subpath=`（語意相同＝列出基底下子目錄，依 CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）；本頁僅在 BFF 新增自己的路由 `GET /api/bff/transaction/export/browse` passthrough 至該端點（依「一個前端頁面一個 BFF」）。
- [ ] **可手動立即匯出（驗證用，逐筆）**：每筆排程各有「立即匯出」按鈕（`POST /api/bff/transaction/export/schedules/{id}/run-now`），以**該筆**的輸出資料夾與檔名產檔並回傳實際落點路徑與檔案大小，供使用者驗證該筆路徑正確；此操作**不動該筆的當日排程 guard**，也不碰其他筆。
- [ ] **每使用者各自設定（owner-scoped 設定表，每人多列）**：排程設定存於 `asset_transaction_export_schedule`（`@Filter(ownerFilter)` 隔離）。**Task 255 起移除 `owner_user_id` UNIQUE 約束、改為每人多列**，並新增 `name`（選填名稱）欄；其餘欄位（啟用／時分／輸出子路徑／上次執行日期（當日 guard）／上次執行時間與結果／Drive 四欄）語意不變，只是改為每筆各持一份。列表／新增／修改／刪除／run-now 走 HTTP（BFF→business）由 `TenantFilterAspect` 自動 scope 到本人；非管理者亦可設定自己的排程（不限 admin）。**升級不做資料遷移**：既有每人 1 列原地成為該使用者的第 1 筆排程（`name` 為 NULL），落點與檔名完全不變。
- [ ] **背景排程必須逐列 `enableFilter`（租戶隔離關鍵）**：`AssetTransaction` entity 帶 `@Filter(ownerFilter)`，而背景 cron 無 request context、`TenantFilterAspect` 不啟用 → `findAll()` 會讀到**全部使用者**的交易紀錄。故排程產檔一律走新增的 `ExcelExportService.exportAssetTransactionsForOwner(ownerId)`，在該 session 手動 `enableFilter("ownerFilter")` 縮到該列 owner，確保各使用者檔案只含自己的資料（比照 Requirement 39 已實現損益）。
- [ ] **排程執行機制與自癒（比照 Requirement 34／39）**：每分鐘 `@Scheduled` poll（`zone=Asia/Taipei`），以 `now >= 設定時分` ＋ `last_run_date` 當日 guard 判斷（非「分鐘精確相等」，避免排程執行緒被長工作卡住跨分鐘導致整日靜默漏跑）；服務重啟以 `ApplicationReadyEvent` 補跑當日已到點未執行者；`AtomicBoolean` 防重入；單一列失敗只記該列 `last_run_status`＋log、不影響其他列（成功或失敗都設當日 guard，避免整天每分鐘重試）。**Task 255 後判斷單位由「每使用者一列」變成「每筆排程一列」，`@Scheduled` 方法數不變**（仍是同一支每分鐘 tick，`findAll()` 逐列判斷），故排程列表頁 `JOBS` 筆數與總數 **46 不變**，只需視情況調整該筆說明文字。
- [ ] **檔名**：預設 `交易紀錄_{使用者ID}_{YYYYMMDD}.xlsx`（檔名含 owner id，避免多使用者共用同一 subpath 時同名互相覆蓋；同一使用者同日覆寫）。**該筆排程有填名稱時**改為 `交易紀錄_{使用者ID}_{名稱}_{YYYYMMDD}.xlsx`——名稱留空即與 Task 238 的檔名**逐字元相同**，既有排程升級後餵給下游程式的輸入檔不會斷（既有唯一一筆的 `name` 為 NULL）。
- [ ] **排程名稱的字元限制與同檔名行為**：名稱長度 ≤ 20，僅允許中文、英數、底線、連字號與空白；含 `/`、`\`、`..`、控制字元等一律回 400（名稱會進檔名，不擋等於讓使用者把檔案寫到設定目錄之外或產生不合法檔名）。同一使用者兩筆排程若**同資料夾且同檔名**（都沒填名稱或名稱相同），當日後執行者覆寫前者——兩份都是**執行當下**的完整交易紀錄（三入口共用同一份涵蓋全部年度的活頁簿，不會缺年度），但**不是同一時點的快照**：兩次執行之間新增／修改的交易只會出現在後面那一份。UI 須明白告知此行為並提示「要各時段各留一份就填不同名稱」。
- [ ] **排程列表頁需登錄**：新排程須在「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）補上對應項目，避免該頁與實際排程漂移（Task 188／195 即為修正此類漂移而生）。

---

### Requirement 50: 爬蟲輸出檔案同步上傳 Google Drive（本機輸出不變，附加雲端副本）

**User Story:** 作為使用者，我希望「爬蟲資訊查詢」頁的輸出資料夾除了本機之外，也能把爬蟲產出的公開資訊 JSON 同步一份到我 Google 雲端硬碟的「投資理財 / 資產管理」目錄，讓我在沒開這台電腦時也能從雲端看到爬回來的資料。

> **定位（務必先讀）：本需求是「附加」而非「替換」。** 現行本機輸出路徑機制（Requirement 38 / Task 212：`crawler_export_setting.output_subpath` → 容器基底 `EXPORT_OUTPUT_DIR` resolve）**行為完全不變、一律照寫**，Google Drive 只是在本機檔案寫成功之後多上傳一份副本。原因是 **SRPP 退休規劃專案正依賴 `Project/SRPP/data/input/public_info_<yyyy-MM-dd>.json` 這份本機檔案**；若把 Drive 做成單選的替代目標，使用者選了 Drive 就會靜默切斷 SRPP 的資料來源。故本需求刻意不提供「只寫 Drive」的選項，Drive 上傳失敗也絕不影響本機檔案與 `news_headline` 入庫。

**背景：** 全庫既有的唯一 Google Drive 整合是 DB 備份／還原（Requirement 21 系列，`BackupService`），走 `rclone` 的 crypt remote `gdrive-crypt:`（＝`GoogleDriver:asset-management-backup` 的加密層），檔名與內容皆加密、使用者無法在 Drive 網頁上直接閱讀，且該 remote 的 OAuth `scope = drive.file`——這種 scope 下 rclone **只看得到自己建立的檔案**，看不到使用者手動建立的「投資理財 / 資產管理」目錄，因此既有 remote 無法用於本需求。反之，「輸出資料夾」選擇器共有九個頁面沿用同一套 `el-tree` 懶載入 UI（Requirement 34／37／38／39／41／42／45／48／49），目前**完全沒有任何儲存後端抽象層**，只認本機檔案系統路徑；且其後端目錄列舉**已經是分裂的**——實測九頁中有八頁的 BFF passthrough 到 `GET /api/export-schedule/browse`，但交易日曆（Requirement 37）走自己的第二份 `GET /api/trading-calendar-export/browse`。本需求先在「爬蟲資訊查詢」一頁打通端到端，同時把 Drive 端的路徑模型與目錄列舉建成與本機對稱、可供其餘八頁日後沿用的形狀；**不修既有的本機列舉分裂（超出範圍），但不得複製它**。

**Acceptance Criteria:**

- [ ] **Drive 上傳為獨立開關，預設關閉（既有部署行為不變）**：「爬蟲輸出檔案設定」卡在既有「輸出資料夾」之下新增「同步上傳 Google Drive」開關與 Drive 目標資料夾欄位。`crawler_export_setting` 新增 `gdrive_enabled`（NOT NULL DEFAULT false）、`gdrive_subpath`（nullable）。**Liquibase seed 不啟用**，既有部署升級後行為與現況完全一致（只寫本機、不碰 Drive、不需要 rclone remote 存在）。
- [ ] **Drive 端路徑模型與本機對稱（remote 為基底＋相對子路徑）**：DB 只存 Drive 上的**相對子路徑**（例 `投資理財/資產管理`），實際目的地 = rclone remote 基底 resolve 該子路徑。remote 名稱由環境變數 `GDRIVE_OUTPUT_REMOTE`（預設 `GDriveOutput`）指定，**不得寫死在程式碼裡**；此對稱性是為了讓其餘頁面日後沿用同一組 DTO 與選擇器，無須再發明第二套路徑語意。
- [ ] **驗證比照本機（擋絕對路徑、`..` 段與 remote 切換）**：`gdrive_subpath` 一律以「去頭尾空白、**只剝結尾**斜線；不得以 `/` 開頭；以 `/` 切開後不得有任何一段等於 `..`；不得含 `:`」驗證，違反回 400。三點理由各不相同，不可混為一談：（a）**開頭 `/` 回 400 而非默默剝掉**——比照既有 `CrawlerExportPathService` 對本機 `outputSubpath` 的處理，讓使用者知道只能填相對子路徑，勝過默默改寫語意；（b）**擋 `..` 不是在防目錄跳脫**——實測 rclone 對 Drive remote 不做路徑正規化，`..` 被當字面目錄名（`rclone lsd "GoogleDriver:x/.."` 回 `directory not found`），擋它是為了避免在使用者 Drive 上建出字面名為 `..` 的怪目錄，並與本機規則一致；（c）**擋 `:` 是防 remote 切換**——rclone 取第一個 `:` 之前為 remote 名，雖然「remote 前綴與子路徑拼在同一個 argv 元素」使注入在結構上已被擋住，但該保證依賴實作細節，日後重構拆開即失效，故多擋一層。`gdrive_enabled=true` 時 `gdrive_subpath` 為必填（空值回 400），避免啟用了卻把檔案倒在 Drive 根目錄。**目錄瀏覽端點的開頭斜線處理刻意相反**（剝除而非 400），沿用本機既有 `browse` 與 `update` 的同一組不對稱：瀏覽是唯讀導覽、對輸入寬容；儲存是寫入設定、要求明確。
- [ ] **專用 remote，不動既有備份 remote**：Drive 上傳使用**獨立的 rclone remote**（`scope = drive`，未加密，指向使用者本人的 Drive），與 DB 備份的 `GoogleDriver:`／`gdrive-crypt:` 完全分離。刻意不改既有 remote 的 scope：重新授權若失敗會連帶弄壞正在運作的 DB 備份／還原，而備份是災難復原的最後一道防線。新 remote 需由使用者本人執行一次 OAuth 授權建立（`rclone config create <name> drive scope=drive`），**不由程式建立、不由程式持有 client secret**。
- [ ] **`scope = drive` 的權衡須記載**：新 remote 取得的是使用者 Drive 的完整讀寫權（非 `drive.file` 的「僅限自己建立的檔案」）。這是為了能寫進使用者**手動建立**的既有目錄所必要的權衡——`drive.file` 下 rclone 連該目錄都列不出來。程式端的自我約束是：只執行 `lsjson --dirs-only`（列目錄）與 `copyto`（寫入指定子路徑）兩種操作，**不實作任何刪除既有 Drive 檔案的程式路徑**，把完整權限的實際使用面縮到最小。
- [ ] **上傳時機與內容＝本機那一份同一個檔案**：`NewsPoller` 每輪維持現行流程寫出本機 `public_info_<yyyy-MM-dd>.json`（tmp ＋ atomic move 不變）；**本機檔案寫成功後**，若 `gdrive_enabled` 為真則以 `rclone copyto <本機檔> <remote>:<gdrive_subpath>/public_info_<yyyy-MM-dd>.json` 上傳同一份檔案。同日多輪覆寫＝當日最新、跨日新檔，與本機一致；**檔名不開放設定**（維持 `public_info_<日期>.json`，SRPP 依此檔名取用）。
- [ ] **設定每輪即時讀取、免重啟**：`gdrive_enabled` 與 `gdrive_subpath` 比照現行 `output_subpath`，於**每一輪寫檔時**由 ext 端讀 DB 現值決定，不快取於欄位、不需重啟容器；頁面改設定後下一輪（含開機 warmup）即生效。
- [ ] **上傳失敗不得影響本機檔案、不得中斷入庫**：Drive 上傳為 best-effort——失敗只記 ERROR log 與 DB 狀態欄，**絕不** rollback 本機檔案、**絕不**讓 `NewsPoller` 本輪的 `news_headline` 入庫失敗、**絕不**擲例外中斷排程。不實作 retry queue：爬蟲每輪都重新產生當日完整檔案並重新上傳，下一輪即為天然重試（Drive 端覆寫同名檔案本身冪等）。**上傳逾時上限 45 秒**，逾時視為該輪上傳失敗——理由不是「避免阻塞排程執行緒」（`NewsPoller` 早已把抓取丟到獨立執行緒），而是它的 `AtomicBoolean running` 旗標（warmup 與排程輪共用）被持有期間，後續輪次會因「上一輪尚未結束」被跳過；該旗標的持有時間主要由抓取決定，Drive 逾時上限的作用是**不再額外拉長**它，故取明確小於 ticker 60 秒週期的 45 秒。使用者把兩個執行時點設在相鄰分鐘時下一輪仍可能被跳過，屬 DB 驅動排程的既有行為，不由本需求解決。互動式的 Drive 目錄瀏覽另設 20 秒上限（使用者點樹節點的懶載入，不得沿用 `BackupService` 為整庫上傳設的 300 秒）。
- [ ] **上傳結果須可在頁面上看見（否則失敗是靜默的）**：`crawler_export_setting` 新增 `gdrive_last_run_at`、`gdrive_last_status`（成功記落點路徑與檔案大小，失敗記錯誤訊息摘要），由設定卡顯示「上次上傳」狀態（比照既有排程設定表的 `last_run_status` 慣例）。理由：上傳目的地不在使用者眼前的檔案系統，若不回報狀態，「檔案沒上去」只會體現為 Drive 上少一個檔案，使用者無從得知。
- [ ] **Drive 資料夾樹狀瀏覽（與本機選擇器同一操作體驗）**：Drive 目標資料夾以與本機相同的檔案總管式 `el-tree` 懶載入選擇器挑選。business 端新增 `GET /api/export-schedule/browse-gdrive?subpath=`，與既有 `GET /api/export-schedule/browse` **並列於同一支 service**（`ExportScheduleService`）——兩者語意不同（一個列本機基底下子目錄、一個列 Drive remote 下子目錄），故為兩支端點而非加參數。**「Drive 目錄列舉」全庫只准有這一支實作**：其餘頁面日後接 Drive 時一律沿用它，**含交易日曆頁**（即使它的本機列舉是自己那份 `trading-calendar-export/browse`，Drive 列舉也不得再開第二份），避免把既有的本機列舉分裂複製到 Drive 這一側（CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）。實作以 `rclone lsjson --dirs-only` 列舉，僅回目錄名與相對路徑、不讀檔案內容、不變更 Drive 內容。
- [ ] **一頁一 BFF**：前端只呼叫自己頁面的 BFF——`CrawlerDataBffController` 新增 `GET /api/bff/crawler-data/export-path/browse-gdrive` passthrough 至上述 business 端點；Drive 設定的讀寫沿用既有 `GET/PUT /api/bff/crawler-data/export-path`（同一張設定表、同一支 business API `GET/PUT /api/crawler-export-path`，只是 DTO 多了 Drive 欄位），**不新增第二支設定端點**。
- [ ] **remote 未設定時必須明確報錯、不得 500**：使用者尚未建立 rclone remote（或 remote 名稱打錯、授權過期）時，Drive 目錄瀏覽須回可讀的錯誤訊息（如「Google Drive remote `GDriveOutput` 尚未設定或授權失效」）供前端提示，而非 500 或空樹——空樹會被誤讀為「Drive 裡沒有資料夾」。同理，`gdrive_enabled=true` 但 remote 不可用時，設定頁仍須能載入與儲存（比照現行 `absolutePathOrNull` 的既有理由：唯一的修正入口不能被自己鎖死）。
- [ ] **單一 rclone config 檔，兩個容器共用（使用者明示的決定，含已知代價）**。`~/.config/rclone/rclone.conf` 同時含 `[GoogleDriver]`（`drive.file`，備份底層）／`[gdrive-crypt]`（crypt 層，含解密密碼）／`[GDriveOutput]`（`scope = drive`，輸出用），**同一份唯讀掛入 business 與 ext**。**代價**：`external-materials-service`（全 stack 唯一對外打第三方者，攻擊面最大）也讀得到備份憑證，即具備解密整庫財務備份的能力。原設計為分離兩份以避免此擴權；改為共用是為省下第二份檔案的維護與搬機成本，且實測兩個 remote 為**同一個 Google 帳號**，分離的實際收益本就有限。復原方式與 dangling mount 陷阱記於 `spec/steering/tech.md` §4。
- [ ] **ext 容器需具備 rclone 與設定檔（部署前提）**：實際執行上傳的是 `external-materials-service`，該容器目前只裝了 `curl`。需於其 Dockerfile 加裝 `rclone`，並於 `docker-compose.yml` 唯讀掛入上述 config 檔。**須沿用 `BackupService` 既有的「啟動時複製到可寫路徑」作法**（唯讀掛載會使 rclone 自動續期 OAuth token 時寫回失敗而 exit non-zero），否則 token 過期後上傳會開始整批失敗；來源檔不存在時只 warn 並跳過 Drive 上傳，不得讓服務啟動失敗。
- [ ] **不得為中文目錄名加 JVM 編碼參數（實測無效）**：目標目錄名為中文，若 `sun.jnu.encoding` 退化成 ASCII，`ProcessBuilder` 傳出的中文路徑會變成 `?` 而靜默寫到錯誤目錄。但**實測 ext 容器現況已是 UTF-8**（base image `eclipse-temurin:21-jre-alpine` 自帶 `LANG=en_US.UTF-8`），且**命令列 `-Dsun.jnu.encoding=UTF-8` 對此屬性無效**（JDK 由 platform locale 決定）。故不加任何 `-D`；實際約束是「不得在 Dockerfile 設 `LANG=C`／清除 base image locale／更換為不帶 UTF-8 locale 的 base image」，並以驗證步驟做回歸守門。
- [ ] **不為兩處 rclone 呼叫新建共用 module**：`backend`／`bff`／`external-materials-service` 為三個獨立 Maven 專案、無父 pom 與共用 module。ext 端的 rclone 呼叫為薄封裝（`ProcessBuilder` ＋ 逾時 ＋ exit code 檢查），刻意與 `BackupService.execProcess` 各自實作而不抽共用 module：為兩處數十行程式碼引入跨服務 module 會使三個服務的建置相互耦合，成本高於重複本身（CLAUDE.md「三行類似的程式碼勝過過早的抽象」）。
- [ ] **`gdrive_enabled` 不建主檔表（「禁止 Enum 寫死」的界線）**：本需求以布林開關（本機必寫 ＋ 可選附加 Drive）表達，而非新增「儲存目標」分類主檔表。理由：CLAUDE.md「禁止 Enum 寫死」針對的是**業務分類**（銀行、券商、存款類型、市場類型——使用者會自行增修的清單）；「要不要多上傳一份到 Drive」對應的是程式中一條具體的 rclone code path，DB 多一列並不會讓程式自動支援一個新的儲存後端，建主檔表只會製造假的擴充性。
- [ ] **權限（比照現行輸出路徑設定）**：讀取設定與瀏覽 Drive 目錄為已登入者皆可（`authenticated`）；**修改 Drive 開關與子路徑的 `PUT` 限 ADMIN**——與本機輸出路徑同理，此設定決定服務往使用者雲端硬碟寫入的位置；非 ADMIN 前端隱藏／停用儲存並提示。
- [ ] **測試**：`CrawlerExportPathService` 的 Drive 欄位驗證須有單元測試涵蓋——`..` 路徑段、絕對路徑（`/` 開頭）、`gdrive_enabled=true` 但子路徑為空三種均回 400；正常值 upsert 後可讀回；既有本機 `output_subpath` 行為不因新欄位而改變（回歸）。rclone 呼叫以介面隔離，測試不實際連網。
- [ ] **排程列表頁不需新增項目**：本需求未新增任何 `@Scheduled` 排程（上傳掛在 `NewsPoller` 既有輪次內），故「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）**不需新增項目**；但該頁 `news-poller` 那筆的說明須補上「輸出含 Google Drive 同步（若已啟用）」，避免頁面與實際行為漂移（Task 188／195 即為修正此類漂移而生）。

---

### Requirement 51: 其餘八個匯出頁的輸出資料夾同步上傳 Google Drive（Requirement 50 的推廣）

**User Story:** 作為使用者，我希望除了爬蟲資訊查詢頁之外，其他會匯出檔案的頁面（歷年資產、已實現損益、匯率、商品價格、交易日曆、交易雷達、交易紀錄、GDP-TWSE）也都能把匯出的檔案同步一份到 Google 雲端硬碟，而不必每一頁都只能存在本機。

> **定位：** 本需求是 Requirement 50 的**推廣**，語意完全相同——**本機一律照寫，Drive 只是附加副本**，不提供「只寫 Drive」的選項。Requirement 50 已在「爬蟲資訊查詢」一頁打通端到端（實測：本機與 Drive 兩份 93159 bytes 完全一致），本需求把同一套模型套到其餘八頁。差異只有兩處：（1）這八頁的匯出設定是 **per-user（owner-scoped）** 而非全域，（2）實際上傳者是 **business-services**（這八頁的排程都在 business，且該容器已有 rclone）而非 `external-materials-service`。

**背景：** 九個有「輸出資料夾」選擇器的頁面中，Requirement 50 只做了爬蟲資訊查詢一頁；其餘八頁（Requirement 34／37／39／41／42／45／48／49）的匯出仍只認本機路徑。這八頁的設定各存於自己的表——`export_schedule_setting`／`trading_calendar_export_schedule`／`index_export_schedule`／`exchange_rate_export_schedule`／`trading_radar_export_setting`／`commodity_export_schedule`／`realized_gain_export_schedule`／`asset_transaction_export_schedule`——**八張全部帶 `owner_user_id` ＋ `@Filter(ownerFilter)`**，與爬蟲那張全域無 owner 的 `crawler_export_setting` 結構不同。Requirement 50 已建好可重用的部分：Drive 目錄列舉端點 `GET /api/export-schedule/browse-gdrive`、`RcloneClient` 介面與 `ProcessRcloneClient` 實作（含可寫副本機制）、以及前端 `el-tree` 選擇器的雙模式（本機／Drive）寫法。

**Acceptance Criteria:**

- [ ] **範圍為八頁，語意完全比照 Requirement 50**：歷年資產（R34）、交易日曆（R37）、已實現損益（R39）、**油價金價（R41）**、**台幣兌美元匯率（R42）**、GDP-TWSE（R45）、交易雷達（R48）、交易紀錄（R49）。每頁的排程設定卡在既有「輸出資料夾」之下新增「同步 Google Drive」開關 ＋ Drive 目標資料夾欄位 ＋「上次上傳」狀態顯示。**本機輸出行為完全不變**，Drive 只在本機檔寫成功之後多上傳一份。
- [ ] **八頁的結構分三類，不可套同一個修改樣板**（實測結果，這是本需求的主要複雜度來源）：（a）**三頁有 `writeToDir()`**——歷年資產、已實現損益、交易紀錄；（b）**四頁是 `export()`／`writeDailyExport()` 外層 ＋ `writeAtomically()` 底層**——油價金價、台幣兌美元匯率、GDP-TWSE、交易雷達，上傳插入點必須在**外層**（`writeAtomically` 沒有設定列情境，拿不到 `gdrive_subpath`）；（c）**交易日曆自己不寫檔**，委派 `TradingCalendarExportService`。
- [ ] **兩頁另有結構例外，必須個別處理**：（1）**交易日曆沒有 run-now**——其手動匯出是 `POST /api/trading-calendar-export/run?year=&format=&subpath=`，走 `TradingCalendarExportService.exportToDir`、不經排程 service，且 **`subpath` 由 HTTP query param 帶入而非讀設定列**，故該頁的手動匯出若要上傳 Drive，必須另外去讀該使用者的設定列取 `gdriveEnabled`／`gdriveSubpath`。（2）**交易雷達的設定 DTO 是裸單欄** `SettingRequest(String outputSubpath)`，controller 與 service 簽章都只傳一個字串，加 Drive 欄位必須同時改 record、controller、service 簽章與前端 helper 的參數形狀（此即 Requirement 50 在爬蟲頁踩過的同一個坑）；且該頁的執行時間點存於另一張表、**一天可能上傳多次**，故其 `gdrive_last_status` 為「最後一次」語意。
- [ ] **Drive 同步只有「主要管理者」本人能啟用（隱私硬約束）**：這八張設定表都是 per-user，但 **rclone remote 全機只有一份**——它綁定的是**某一個特定 Google 帳號**。若允許其他使用者啟用，**B 的財務報表就會被上傳到那個帳號的雲端硬碟**，這是實質的資料外流，而且從 B 的角度完全不可見。故：（a）`PUT` 時若請求要把 `gdrive_enabled` 設為 true 而當前使用者不是主要管理者，回 **403**（不是 400——這是權限問題而非輸入錯誤）；（b）非主要管理者前端不顯示該開關與欄位；（c）本機輸出路徑、排程時間等既有欄位**仍維持所有使用者皆可設定**（不限 ADMIN），此不對稱是刻意的，因為只有 Drive 這一項會把資料送出本機。
- [ ] **判準必須是 `isConfiguredAdmin(email)`，不得用 `role == ADMIN`**：`role` 是 DB 欄位、**可以有多列 ADMIN**（現況實測：`app_user` 有兩名使用者，第二位若被升為 ADMIN，其報表照樣會進到 remote 擁有者的 Drive——外流語意與原問題完全相同，只是母體變小）。故一律走既有的單一判定入口 `UserAdminService.isConfiguredAdmin(email)`（比對 `ADMIN_EMAIL`，全庫唯一一人）。因 `CurrentUserContext` 只帶 `effectiveUserId`／`role`／`status`、**不帶 email**，兩處判定都需先以 userId 查 `AppUserRepository` 取 email：`PUT` 用當前使用者的 id，背景排程用該列的 `owner_user_id`。**兩處必須走同一個判定函式**，避免日後其中一處被改寬。
- [ ] **背景排程必須逐列再驗一次 owner 仍是主要管理者**：`PUT` 時的檢查有 request context，但排程是背景執行緒、逐列跑 `findAll()`，沒有 `CurrentUserContext`。若某列在啟用後其 owner 被改（或 DB 值被 psql 直改繞過 API、或 `ADMIN_EMAIL` 換人），背景仍會照上傳——故排程產檔後、上傳前須以該列的 `owner_user_id` 取 email 再走 `isConfiguredAdmin`，**不通過一律跳過上傳並寫入狀態欄說明原因**（不可靜默跳過，否則使用者會以為還在同步）。這與 Requirement 39／49「背景排程必須逐列 `enableFilter`」是同一類縱深防禦：背景執行緒沒有 request 情境可依賴，任何「HTTP 層已經驗過」的假設都不成立。
- [ ] **抽共用元件，不得複製八份**：Drive 子路徑驗證、上傳呼叫、狀態字串組裝、remote 名稱注入一律走**單一共用元件**（business 端新增，例如 `GdriveOutputSupport`），八個 service 各自只呼叫它。這與 Requirement 50「不為兩處 rclone 呼叫建共用 module」**不衝突**：那裡指的是跨越三個獨立 Maven 專案（backend／bff／external-materials-service，無父 pom）為兩處數十行程式碼建 module；這裡是**同一個 backend 內的八個 service 共用同一段邏輯**，八份複製才是明確的錯誤。
- [ ] **同時收斂 Requirement 50 已留下的兩份重複**：Requirement 50 實作時把 Drive 子路徑驗證寫成 `CrawlerExportPathService` 的 **private static** 方法、remote 名稱則分別注入 `CrawlerExportPathService` 與 `ExportScheduleService` 兩處。private 無法重用，若只在新元件裡「照抄一份」，做完本需求後同一個 module 內會有 **2 份驗證邏輯 ＋ 3 處 remote 注入**——那正是本需求宣告要防的事，只是數量從 8 降到 2。故驗證邏輯與 remote 名稱一併**遷入**共用元件，`CrawlerExportPathService` 與 `ExportScheduleService` 改為注入使用；`ExportScheduleService.browseGdrive` 的 Drive 目錄列舉邏輯亦一併遷入（controller 端點路徑不變，避免動到已完成的 BFF 與前端）。驗收方式：`GDRIVE_OUTPUT_REMOTE` 在 backend 全樹只應命中共用元件一處。
- [ ] **`RcloneClient` 加上傳能力**：Requirement 50 的 `RcloneClient` 只有 `listDirs`（刻意最小化）。本需求為它加 `copyTo`（上傳單一檔案），並維持既有的自我約束——**仍不實作任何刪除既有 Drive 檔案的程式路徑**。上傳沿用 Requirement 50 已定案的**單一** `~/.config/rclone/rclone.conf` 的 `[GDriveOutput]` section ＋ `/tmp` 可寫副本 ＋ per-process `RCLONE_CONFIG` 覆寫；**本需求不變更 config 佈局**。單一檔的已知取捨（掛入該檔的服務都讀得到備份憑證）與復原方式見 `spec/steering/tech.md` §4。
- [ ] **上傳失敗為 best-effort，語意比照 Requirement 50**：本機檔寫成功之後才上傳；失敗只記 ERROR log 與該列的狀態欄，**絕不** rollback 本機檔案、**絕不**讓該使用者的排程被標記為失敗（本機那一份確實成功了）、**絕不**擲例外影響其他使用者的排程列。不實作 retry queue（每日排程本身即為重試）。上傳逾時上限 45 秒。
- [ ] **「立即匯出到目錄」也必須上傳 Drive**：其中**七頁**有 `POST .../run-now`（Requirement 39／49 的「可手動立即匯出（驗證用）」；**交易紀錄頁自 Task 255 起改為逐筆的 `POST /api/asset-transactions/export/schedules/{id}/run-now`**，語意與此條相同，只是作用於指定的那一筆排程）；**交易日曆是例外**，其手動匯出為 `POST /api/trading-calendar-export/run`（見上方結構例外條）。既然 run-now 的用途就是驗證落點正確，Drive 同步啟用時它**必須同樣上傳**並回報落點，否則使用者無法在不等排程的情況下驗證 Drive 設定——這正是 Requirement 50 在爬蟲頁缺 run-now 而造成的實際不便（實測時只能靠重啟容器觸發 warmup 才驗到）。
- [ ] **狀態欄與既有 `last_run_status` 分離**：Drive 上傳結果存於**新的** `gdrive_last_run_at`／`gdrive_last_status` 欄位，不得併入既有的 `last_run_at`／`last_run_status`。理由：本機成功而 Drive 失敗是**正常且必須可分辨**的狀態；若共用一欄，本機明明寫成功卻顯示「失敗」，使用者會誤以為本機檔案沒產生而去做不必要的排查。
- [ ] **檔名沿用各頁既有規則、不因 Drive 而改變**：各頁維持自己既有的檔名（多數含 `{使用者ID}` 與日期，例 `交易紀錄_1_20260727.xlsx`）。Drive 上與本機**同名同內容**，同日覆寫、跨日新檔。檔名含 owner id 這一點在 Drive 上更重要——多個使用者的檔案若共用同一個 Drive 子路徑不會互相覆蓋。**不得以「實務上只有一人會啟用」為理由省略 owner id**：那是設定值決定的偶然狀態，不是機制保證。
- [ ] **Drive 目錄列舉沿用 Requirement 50 既有那一支，不得各自新造**：八頁的 BFF 各新增自己的 `GET /api/bff/{page}/…/browse-gdrive` passthrough（依「一頁一 BFF」），但**全部指向同一支** business `GET /api/export-schedule/browse-gdrive`。**含交易日曆頁**——即使它的本機列舉自成一份 `GET /api/trading-calendar-export/browse`（既有分裂，本需求不修），Drive 側也必須用共用那支，不得在 `TradingCalendarExportService` 再開第二份 Drive 實作（CLAUDE.md「不同頁面顯示同樣意義的值須呼叫同一支 business service API」）。
- [ ] **交易日曆的委派結構需個別處理**：`TradingCalendarExportScheduleService` 本身不寫檔（委派 `TradingCalendarExportService`），與其他七個 service 的「自己 `writeToDir`」結構不同。實作時須確認上傳插入點確實在該頁檔案寫成功之後，不可假設八個 service 結構一致而套同一個修改樣板。
- [ ] **DB 欄位以單一 changeset 為八張表一次加齊**：每張表加 `gdrive_enabled`（NOT NULL DEFAULT false）、`gdrive_subpath`（nullable）、`gdrive_last_run_at`、`gdrive_last_status`（512）。**seed 不啟用任何一列**，既有部署升級後行為與現況完全一致、不要求 rclone remote 存在。changeset 冪等（`ADD COLUMN IF NOT EXISTS`）。
- [ ] **驗證規則與 Requirement 50 完全一致**：子路徑去頭尾空白、只剝結尾斜線；開頭 `/` 回 400；`..` 逐段比對（不可用 `contains("..")`，會誤擋合法目錄名如 `a..b`）；拒收含 `:`（防 rclone 解讀為切換 remote）；`gdrive_enabled=true` 時子路徑必填；關閉開關不清空既有值；未送出該欄位＝不變更。讀取時不因既有不合法值而擲例外（否則設定頁 500，使用者失去唯一的修正入口）。
- [ ] **排程列表頁說明同步**：本需求不新增 `@Scheduled`（上傳掛在既有排程輪次內），故「公開資訊 → 排程列表」（`SchedulePublicBffController` 的 `JOBS`）不需新增項目；但八個對應排程的 description 須補「輸出含 Google Drive 同步（若已啟用）」，避免該頁與實際行為漂移。
- [ ] **測試**：共用元件的驗證規則（含 `a..b` 不被誤擋、`:` 被擋、啟用必填）須有單元測試；**非主要管理者啟用回 403** 與**背景排程遇非主要管理者 owner 跳過上傳並寫狀態欄**兩項為本需求最關鍵的迴歸，必須各有測試（後者同時驗證本機檔仍產生、既有 `last_run_status` 仍為成功）；rclone 呼叫以介面替身注入、不實際連網；既有八頁的本機匯出行為須有回歸測試確認未被改動。

---

### Requirement 52: Google Drive 輸出的可用性自檢（讓「明天早上才發現全掛」變成「啟動當下就看得到」）

**User Story:** 作為使用者，我希望 Drive 同步一旦因為授權或設定問題而不可用，系統在我啟用它的當下、以及每次服務啟動時就明白告訴我哪裡壞了、該怎麼修，而不是等到隔天早上發現九個排程的檔案都沒上雲端才回頭查。

> **定位：** 本需求**不改變任何上傳行為**——Requirement 50／51 的「本機一律照寫、Drive 只是附加副本、上傳失敗 best-effort」語意完全不動。本需求只做兩件事：（1）消除一個會讓修復動作靜默失效的部署層陷阱，（2）在**啟用當下**與**服務啟動時**兩個時機，把三種已知的「Drive 不可用」原因主動探出來寫進 log。

**背景（2026-07-28 的實測事故，三個原因接連發生）：** Requirement 50／51 於 2026-07-27 21:31 上線後的第一個完整排程日，九張設定表**各有至少一列** `gdrive_enabled=true`、`gdrive_subpath=投資理財/資產管理`（`exchange_rate_export_schedule` 另有一列屬第二位使用者、為 false），本機檔案九份全部照常產生，Drive 端一份都沒上去。事後從 `gdrive_last_status` 與容器 log 追出三個**彼此獨立**的原因：

1. **Google Cloud 專案未啟用 Drive API。** rclone 回 `Error 403: Google Drive API has not been used in project 1098468643583 before or it is disabled`——請求被算到某個使用者自有的 GCP 專案而非 rclone 內建公用 client 的專案。**DB 備份不受影響**：`[GoogleDriver]` 同一晚 23:00 的 `Uploaded to gdrive-crypt:backups/daily/` 照常成功。這個不對稱正是誤判的來源：「備份好好的，所以 Drive 沒問題」。**成因**：事故當下 `[GDriveOutput]` 帶有自訂 `client_id`／`client_secret`，自訂 client 會把配額與 API 啟用狀態綁到該 client 所屬的 GCP 專案，而該專案未啟用 Drive API。**中途曾改走 rclone 內建公用 client**（15:53，403 隨之消失），但內建 client 的配額為全球共享——實測 16:03–16:12 補跑八頁時三頁回 `rateLimitExceeded`（`gdrive_last_status` 記為「暫時未上傳：Drive API 達每分鐘查詢上限」），間隔 60–90 秒重試才成功。**最終組態（2026-07-28 19:35 起）：改回自訂 client**，同時把該 GCP 專案的 Drive API 啟用；實測補跑不再撞配額。**改用自訂 client 必須同時滿足四個條件，缺一即以第 1 或第 2 項的形式失敗**：（i）該 GCP 專案**啟用 Drive API**；（ii）OAuth 同意畫面**已發布**（停在「測試」狀態的 refresh token 7 天即失效）——實測 2026-07-28 該專案為 **In production**，已滿足；欄位位置在新版 **Google Auth Platform → Audience → Publishing status**（舊版介面稱「OAuth 同意畫面」，改版後已搬離 Overview 頁）；（iii）授權時取得 **refresh_token**（見第 2 項，這是最難的一關）；（iv）授權時選對 Google 帳號。
2. **`[GDriveOutput]` 的 OAuth token 沒有 `refresh_token`。** 實測該 section 的 token JSON 只有 `access_token`／`expires_in`／`expiry`／`token_type` 四個鍵，對照 `[GoogleDriver]` 多一個 `refresh_token`。後果是 access_token 一過期（實測約 1 小時）就回 `token expired and there's no refresh token`，且**無法自動續期**。這一項最陰險：重新授權後的一小時內，任何探測、任何上傳都會成功，看起來完全正常。**取不到 refresh_token 的成因**：Google 對「該 client 已被此帳號授權過」的重複授權不重發 refresh token——判斷指標是**授權過程有沒有出現同意畫面**：實測 `rclone config reconnect` 在 3 秒內就 `Got code`（瀏覽器一閃而過）時必然拿不到。

**關鍵：Google 的授權記錄綁的是「應用程式」而非 client id。** 同一個 GCP 專案下的所有 OAuth client 共用同一個同意畫面，在 Google 帳號的「第三方應用程式存取權」中就是**同一個應用程式**。故 2026-07-28 當天實測：19:19 換上重新產生的 client id/secret 並 reconnect → 仍無 refresh_token；19:29 再授權一次 → 仍無。**「換一組新 client」對此完全無效。**

**唯一實測有效的取得方式（19:35 一次成功）**——把 `prompt=consent` 塞進授權端點，強制 Google 重新徵求同意：

```bash
rclone config reconnect GDriveOutput: --drive-auth-url "https://accounts.google.com/o/oauth2/auth?prompt=consent"
```

另一條理論上可行但當天走不通的路是先到 https://myaccount.google.com/permissions 撤銷該**應用程式**（非 client）的存取權再重新授權——實測使用者在該頁找不到對應項目。**撤銷是否生效的判斷法**：撤銷會立即 revoke 已發出的 token，故 `rclone lsd "<remote>:<任一存在的目錄>"` 會由成功轉為失敗。**注意此判斷法有時效陷阱**：access_token 自然過期後症狀完全相同（同樣是 lsd 失敗），故只在 token 尚未到 `expiry` 前有效。

**另一個相關線索**：當天該 client 的憑證 JSON 片段顯示類型鍵為 `web`（桌面應用程式為 `installed`）。Web 類型必須在 console 明確登錄重新導向 URI `http://127.0.0.1:53682/`，且重複授權時預設不重發 refresh token；rclone 官方建議建立 client 時選「桌面應用程式」。當天最終是以 `prompt=consent` 在 Web 類型上直接解決，未更換類型。
3. **host 端改過 config 之後，容器內的掛載點成為 dangling inode。** `rclone config` 寫設定是「寫新檔 ＋ rename」原子替換，而 compose 是以**單一檔案**掛入（`~/.config/rclone/rclone.conf:/etc/rclone/rclone.conf:ro`），替換後容器內舊 inode 的 link count 歸零。實測兩個容器皆為 `stat` 看得到（`links=0`）、`cat` 回 `ENOENT`。**這不是「使用者偶爾手動改設定」的罕見情況**——rclone **每次續期 OAuth token 都會重寫 config**，實測 2026-07-28 一天內 15:30／15:53／16:02 三次原子替換，其中 16:02 那次發生在容器 recreate 之後 **1 分鐘內**，掛載當場又 dangling。故這是**常態而非例外**。**後果是「使用者去 host 重新授權」對執行中的容器完全無效**——這條陷阱早已記在 `spec/steering/tech.md` §4（「host 改過 rclone config 之後必須 `--force-recreate` 容器」），但純文件約束擋不住它再次發生，事故當天就是這樣發生的。

**Acceptance Criteria:**

- [ ] **rclone config 改為掛目錄而非單檔（根治第 3 項）**：`docker-compose.yml` 中 business-services 與 external-materials-service 兩處掛載，由 `${HOME}/.config/rclone/rclone.conf:/etc/rclone/rclone.conf:ro` 改為 `${HOME}/.config/rclone:/etc/rclone:ro`。**`RCLONE_CONFIG` 的值不變**（仍為 `/etc/rclone/rclone.conf`），兩支 client 的 `CONFIG_SOURCE` 常數也不變——目錄掛載下該路徑每次都經目錄查找解析，host 端原子替換後容器內即可讀到新檔。**消除的是「單檔替換」造成的 dangling（即 rclone 每次 token 續期都會觸發的那種）；若 `~/.config/rclone` 目錄本身被替換**（`mv` 後重建、還原備份、換機搬設定），掛載仍指向舊目錄 inode，同一失效模式會往上搬一層，屆時仍須 recreate 容器。**已知副作用須記載**：該目錄下的其他檔案（實測有 `rclone-gdrive-output.conf`、`rclone.conf.bak-before-merge`）會一併以唯讀進入容器；這不擴大權限面——單一 config 檔的共用取捨（Requirement 50 末條）已使兩容器都讀得到備份憑證，多掛入的兩檔是同一組憑證的舊副本。
- [ ] **掛目錄不等於免重啟，此限制須明寫**：兩支 client 都是啟動時複製一份到 `/tmp` 可寫副本（token 續期需寫回），**執行期不重讀來源**。故 host 重新授權後仍須 recreate 容器才會生效；本需求**刻意不實作 config 熱重載**——理由是範圍控制：熱重載需要 watch／輪詢與並發保護，而收益只有省下一次 recreate。（**不要以「重載會覆蓋較新的 token」為理由**：token 健康時 rclone 會自己再 refresh 一次、只多一次網路往返；token 缺 `refresh_token` 時從來源重載反而正是想要的行為。）改變的是失效模式：從「`ls` 看得到卻讀不到」這種無從診斷的怪症狀，變成「用的是舊 token」這種自檢會直接指名的狀況。
- [ ] **自檢分三層，缺一層都會漏掉今天這三種原因中的一種**：（L1）**來源 config 可實際讀取**——判準是「讀得到內容」而非 `Files.exists()`，因為 dangling inode 下 `exists()` 仍回 true（走 stat），只有實際讀取才會 `ENOENT`。**L1 的價值被一個既有設計放大**：兩支 client 的 `configReady` 都是**啟動時判定一次的旗標、失敗後永不重試**，一旦啟動當下讀不到 config，該容器**整個生命週期**的 Drive 同步都會被跳過。實測 2026-07-28 16:02 就發生過——16:02:25 ext 啟動、16:02:26 讀 config 得 `NoSuchFileException`、16:02:29 host 檔正好被 `rclone config` 改寫，結果 business 逃過而 ext 靜默失效；**症狀會偽裝成「Drive 好像還在收檔案」**（各匯出頁的 xlsx 由 business 上傳、照常出現），只有爬蟲 JSON 停止更新。故 L1 不只是「診斷訊息比較好看」，它是唯一會把這種一次性失敗講出來的地方；（L2）**解析 `[<remote>]` section 的 token JSON，檢查 `refresh_token` 存在且非空**——這是唯一能在「access_token 尚未過期」期間就抓出第 2 項的辦法，實際探測在那個時間窗內必然通過；（L3）**實跑一次唯讀探測** `rclone lsd <remote>:`，用以涵蓋 Drive API 未啟用（403）、remote 名稱打錯、授權已撤銷等只有連線才知道的狀況。**L2 的判定須涵蓋五種輸入**：含 `refresh_token`（通過）／缺或為空（警告）／token 值非合法 JSON（無法判定，警告）／**config 副本不存在**（無法判定，警告）／**檔內無該 section**（無法判定，警告）——後兩者是 L1 失敗與 remote 名稱錯誤的下游狀態，**一律不得擲例外**。
- [ ] **自檢須在兩個時機執行，只做啟動時等於防不到本次事故**：（a）**服務啟動時**；（b）**使用者把 `gdrive_enabled` 由 false 改為 true 的那一次設定儲存**。理由是實測時序：R50／R51 部署 recreate 當下九張表全為 false（`NOT NULL DEFAULT false`、seed 不啟用），使用者是在**容器已在跑之後**才從 UI 打開開關的（實測 `updated_at`：`crawler_export_setting` 2026-07-27 22:33、`export_schedule_setting` 2026-07-28 12:09、`trading_radar_export_setting` 12:56、`exchange_rate_export_schedule` 12:58，全部晚於 21:31 的上線 recreate）。若只做啟動自檢，「啟用」與「下次啟動」之間的空窗正是本次事故發生的整段時間——自檢會在最需要它的時候完全不執行。
- [ ] **(b) 的涵蓋範圍是九頁，不是八頁**：八個匯出頁的儲存確實共用 `GdriveOutputSupport.resolveUpdate`，但**爬蟲頁不走它**——`CrawlerExportPathService.update()` 雖注入同一個元件，卻只用其零件（`normalizeSubpath`／`validateSubpath`／`remoteName`）自行合成，全樹 `resolveUpdate` 的呼叫點恰為八個匯出 service。而爬蟲頁正是九列中**最早被打開**的一列（`updated_at` 2026-07-27 22:33），漏掉它等於漏掉最該被攔下的那一次。故 (b) 需**兩個掛載點**：八頁共用一處、爬蟲頁另一處。
- [ ] **(b) 只做 L1 ＋ L2，不做 L3**：這兩層是純本地檔案讀取與 JSON 解析（毫秒級、無副作用、不打網路），同步執行即可。**L3 刻意排除在 (b) 之外**，三個理由缺一不可：（i）L3 的逾時是 20 秒，同步做會讓使用者按下儲存後乾等最長 20 秒，而「探測慢」恰恰等於「Drive 有問題」，體感就是儲存卡死；（ii）九支設定 service 中 `TradingRadarExportScheduleService.saveSetting` 與 `CrawlerExportPathService.update` 標了 `@Transactional`，同步 L3 會把 20 秒的外部行程呼叫包進交易、佔住連線；（iii）啟用時正是連續開九個開關的時候，同步探測等於連續九次打 Drive API。（**註**：本條原先的論據是「內建公用 client 配額全球共享，實測撞過三次 `rateLimitExceeded`」，該前提已於 2026-07-28 19:35 改回自訂 client 後失效——專屬配額下實測不再撞；本條因此降為次要理由，**(i)(ii) 兩條足以支撐本決定**。）**這不是缺口**：L3 涵蓋的 403／remote 錯誤會在該頁**第一次實際上傳**時寫進 `gdrive_last_status`（既有機制，本次事故正是這樣被記錄下來的），而 (a) 每次啟動也會做一次；(b) 真正不可取代的價值在 **L2**——它是唯一能在 access_token 尚未過期的時間窗內抓出「token 缺 refresh_token」的辦法，而那正是本次最陰險的一項。
- [ ] **(b) 的結果一律不得寫入 `gdrive_last_run_at`／`gdrive_last_status`**：那兩欄的既有語意是**上傳結果**（Requirement 50 明訂「成功記落點路徑與檔案大小，失敗記錯誤訊息摘要，由設定卡顯示『上次上傳』狀態」），且九個前端 view 全部把它標成「上次上傳」直接顯示。把自檢結果寫進去會產生兩個實害：（i）**永久覆蓋真正的上傳記錄**——使用者關掉再打開開關，昨晚成功上傳的落點與大小就沒了；（ii）**`gdrive_last_run_at` 會被寫成一個沒有發生任何上傳的時刻**，設定卡顯示「上次上傳：今天 12:09 — 自檢失敗：…」，而 12:09 根本沒上傳過。故 (b) 的結果走**當次回應**：九頁的設定儲存 response DTO 各加一個**非持久化**的警告欄位（如 `gdriveSelfCheckWarning`，正常時為 null），前端在既有的「儲存成功」提示旁多顯示這一則警告即可。**不新增 DB 欄位、不新增端點、不新增頁面或元件**；九頁的 BFF passthrough 已存在，DTO 加欄位即隨既有路徑帶到前端。
- [ ] **自檢一律不阻止服務啟動、不阻止設定儲存、不改變任何既有行為**：三層任一失敗只寫 log（(b) 另在當次 response 帶回警告字串），`configReady`、`gdrive_last_run_at`／`gdrive_last_status` 與所有既有判定邏輯完全不受自檢結果影響（自檢是觀測，不是閘門）。**九支設定 service 既有的「刻意不碰 `gdriveLastRunAt`／`gdriveLastStatus`：那是執行結果，不是使用者設定」註解（全樹命中 9 次）維持成立，本需求不為它開例外。****(b) 自檢失敗不得讓儲存回非 2xx**——使用者必須能先把設定存起來再去修授權，否則唯一的修正入口被自己鎖死（沿用 Requirement 50 `absolutePathOrNull` 的既有理由）。理由與 Requirement 50「來源檔不存在時只 warn」相同：Drive 輸出是附加功能，不得讓 business-services（含 DB 備份、所有 API）或 ext（含股價、爬蟲）起不來。
- [ ] **啟動自檢一律走獨立（daemon）執行緒，不得阻塞啟動流程**：不得只靠「設個短逾時」了事。**理由不是 `depends_on: service_healthy`**——自檢掛在 `ApplicationReadyEvent`，該事件發布時 web server 已在 listen、healthcheck 已可回應。真正的理由是：`ApplicationReadyEvent` 的 listener 跑在**主執行緒**上、彼此之間**沒有順序保證**，阻塞 20 秒會延後 `SpringApplication.run()` 收尾與同事件的其他 listener；若自檢排在 availability listener 之前，readiness 轉為 `ACCEPTING_TRAFFIC` 一樣會被拖 20 秒。逾時仍設 20 秒（沿用既有列目錄逾時）作為執行緒內的上限。
- [ ] **只在「確實有人啟用 Drive」時做啟動自檢，否則不得噪音**：Requirement 50／51 明訂「既有部署升級後行為與現況完全一致、不要求 rclone remote 存在」。故啟動自檢前先查 DB：business 端查該八張表（`export_schedule_setting`／`trading_calendar_export_schedule`／`index_export_schedule`／`exchange_rate_export_schedule`／`trading_radar_export_setting`／`commodity_export_schedule`／`realized_gain_export_schedule`／`asset_transaction_export_schedule`）是否**存在任一列 `gdrive_enabled=true`**，ext 端查 `crawler_export_setting`；全部未啟用就整個自檢跳過（連 L1 都不做，維持現行只在實際呼叫時才報錯的行為）。**此查詢須繞過 owner filter**（背景執行緒無 request context，且這裡問的是「全庫有沒有人啟用」而非「我的設定」）。**此前置條件只約束 (a)**——(b) 那一刻的請求本身就是「有人要啟用」的證據。
- [ ] **自檢元件不得與既有元件形成建構子循環**：本專案全樹一律建構子注入且未開啟 `allow-circular-references`（grep 零命中），Spring Boot 2.6+ 預設禁止循環參照，踩到即 `BeanCurrentlyInCreationException`、服務起不來。因 (b) 要求 `GdriveOutputSupport` 呼叫自檢，**自檢元件本身就不得反向注入 `GdriveOutputSupport`**——remote 名稱改為**每次呼叫時傳參**，沿用 `ProcessRcloneClient` 既有模式（其建構子不吃 remote，`listDirs`／`copyTo` 都由呼叫端傳入）。啟動時所需的 remote 由第三個薄元件（掛 `ApplicationReadyEvent` 者）注入 `GdriveOutputSupport` 取得後傳入。**Task 242.1.4「`GDRIVE_OUTPUT_REMOTE` 為全 backend 唯一注入點」不得因本需求而破例。**
- [ ] **前置查詢與三層自檢的例外一律不得逸出**：整段自檢（含 DB 前置查詢）須包在單一 catch-all 內，任何例外只寫 WARN 後 return。這不是防禦性冗餘：查詢的八張表在全新安裝的首次啟動時可能尚未由 Liquibase 建立（`BadSqlGrammarException`），而既有同類元件 `CrawlerExportPathQuery` 的既定契約就是「表缺／DB 例外時由呼叫端 fallback」；自檢若讓例外逸出，會把一個純觀測功能變成啟動失敗。同理 L3 沿用既有 `exec(...)` 時會擲 `RcloneUnavailableException`／`RcloneTimeoutException`／`RcloneRateLimitedException`／裸 `RuntimeException` 四種，**須全部攔下**。
- [ ] **log 訊息必須指名原因與修法，不得只寫「Drive 不可用」**：三層各有專屬訊息，且都要能讓人不必翻程式碼就知道下一步。L1 → 指出 config 路徑並提示 host 改過設定後需 recreate 容器；L2 → 明寫「`[<remote>]` 的 token 缺 refresh_token，access_token 過期後將無法自動續期」，並附**當天唯一實測有效的修法**：`rclone config reconnect <remote>: --drive-auth-url "https://accounts.google.com/o/oauth2/auth?prompt=consent"`。**不得只寫裸的 `rclone config reconnect <remote>:`**——實測那樣會跳過同意畫面而再次拿不到 refresh_token（當天連續失敗兩次），且**換一組新的 client id/secret 也無效**（Google 的授權記錄綁「應用程式」＝同意畫面，非 client id）；L3 → **盡可能原樣附上 rclone 的 stderr**（403 訊息本身就含啟用 Drive API 的 console 連結）——注意既有 `exec(...)` 對「找不到 section」與速率限制兩條分支會把 stderr 換成罐頭訊息，該兩型例外附其可讀訊息即可。**自檢新增的訊息層級一律 WARN**（不是 ERROR）——這是「功能不可用」而非「系統故障」，且本機輸出完全正常；既有 `initConfig` 內的 `log.error` 不在本需求範圍，維持不變。
- [ ] **不得把 token 內容寫進 log**：L2 只解析 token JSON 的**鍵名**判斷 `refresh_token` 是否存在，log 只寫存在與否，**絕不**輸出 `access_token`／`refresh_token` 的值。同理 L1 不得為了診斷而 dump config 檔內容——該檔含備份的 crypt 解密密碼。
- [ ] **ext 端的 L3 需要一個唯讀探測能力，而該介面目前刻意只有上傳**：`GdriveUploader` 只有 `upload`／`isAvailable`／`remoteName` 三個方法，其 javadoc 把「**只實作 `copyto`**、不實作任何刪除既有 Drive 檔案的程式路徑」寫成安全不變量。L3 需要新增一支唯讀探測（`rclone lsd <remote>:`），**這是對該不變量的修改，必須是明示決定**：新增後 javadoc 須同步改為「兩種操作：`copyto`（寫）與 `lsd`（唯讀列目錄）；仍不實作任何刪除路徑」。不得由實作者自行在自檢元件內另寫一份 `ProcessBuilder`——那會複製逾時、stderr 解析與 rate-limit 判定三份邏輯。
- [ ] **自檢不新增 API 端點、不新增頁面或 UI 元件、不新增 DB 欄位**：(b) 的結果走既有儲存 response 的一個新增欄位（非持久化）＋前端既有提示，(a) 的結果只進 `docker logs`。刻意不做管理頁健康卡片：那會是第十個要維護的頁面，而它要顯示的資訊在既有狀態欄與 log 裡已經完整。
- [ ] **順帶修正一句會誘導使用者重演事故的既有訊息**：`ProcessRcloneClient` 速率限制分支寫進 `gdrive_last_status` 的字串目前是「此為 rclone 內建共用憑證的已知限制，**根治方式是為 rclone 設定專屬的 OAuth client_id**」——該建議本身正確（當天最終正是改回自訂 client 才解除配額問題），但**缺了會讓人重演 403 與 refresh_token 兩個事故的前提**。須補上：「設定後**必須同時到該 GCP 專案啟用 Drive API**，且授權時須帶 `prompt=consent` 才會取得 refresh_token」。純字串、無邏輯變更。
- [ ] **排程列表頁不需新增項目**：本需求未新增任何 `@Scheduled`，故「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController` 的 `JOBS`）不需新增或修改任何項目。
- [ ] **測試**：L2 的 token 解析須有單元測試涵蓋上述**五種**輸入；L1 須有「來源檔讀取失敗時只 warn、初始化不擲例外、服務仍可啟動」的測試；「全庫無任何列啟用時整個啟動自檢跳過、不呼叫 rclone」須有測試（以介面替身驗證零呼叫）；「前置 DB 查詢擲例外時自檢靜默結束、不影響啟動」須有測試；「(b) 自檢失敗仍回 2xx、設定已存入、且 `gdrive_last_run_at`／`gdrive_last_status` **未被改動**」須有測試（後半段是 Requirement 50 語意的回歸保護）；「**(b) 完全不呼叫 rclone**」須有測試（以介面替身驗證零呼叫——這是「(b) 不做 L3」的機械保證）；**爬蟲頁與八個匯出頁兩個掛載點各需一個「false→true 觸發、true→true 不觸發」的測試**。**自檢一律不實際連網**——(a) 的 L3 走既有介面替身。

---

### Requirement 53: 系統時區基準統一為台北（讓「現在幾點」在全系統只有一個答案）

**User Story:** 作為使用者，我希望畫面上每一個時間都是台北時間、警示的冷卻期是真的 24 小時，而不是「有些頁面對、有些少 8 小時」，也不必自己心算容器跑在哪個時區。

**背景（已實測，非推論）：** 三個 JVM 容器（business-services／external-materials-service／bff）的 `TZ` 環境變數從未設定，base image `eclipse-temurin:21-jre-alpine` 無 `/etc/timezone`，故 JVM 預設時區為 **UTC**；`docker-compose.yml` 全檔無任何 TZ 設定；PostgreSQL 的 `timezone` 亦為 UTC（且已被 initdb 寫進資料目錄的 `postgresql.conf`，單設容器 `TZ` 環境變數無效）。於是所有**未帶 ZoneId 的** `LocalDateTime.now()` / `LocalDate.now()` 產生的是 UTC 牆鐘，寫進 `timestamp without time zone` 欄位後不帶任何時區資訊，前端再原字串顯示 —— 使用者看到的時間**恆少 8 小時**。而「需要指定時區的地方」（46 個帶 `zone` 的 `@Scheduled`、`MarketZones`、`MarketClock`、7 支匯出排程的 `TW_ZONE`）**早已 100% 顯式化**，因此 JVM 預設時區的唯一作用，就是決定那些沒寫 zone 的呼叫落在哪裡 —— 而 UTC 是全系統唯一沒有任何人想要的時區。

**Acceptance Criteria:**

- [ ] **單一開關**：三個 JVM 服務（`business-services`／`external-materials-service`／`bff`）於 `docker-compose.yml` 設 `TZ: ${APP_TZ:-Asia/Taipei}`；PostgreSQL 因 `postgresql.conf` 已被 initdb 寫死 `timezone = UTC`（實測 `source=configuration file`），若要改須用啟動參數 `command: ["postgres","-c","timezone=Asia/Taipei","-c","log_timezone=Asia/Taipei"]`，設環境變數無效。**但要理解它的作用範圍**：pgjdbc 每條連線的 startup packet 都會以 JVM 預設時區覆寫該連線的 session TimeZone，故 server 端的 `timezone` **管不到任何應用連線**；設它是為了讓手動 `psql` 查詢與 server 日誌跟應用同一個基準。`frontend`(nginx) 設 `TZ` 僅為 access log 可讀性；`redis` 不設（無時間語意）
- [ ] **既有的市場時區邏輯零位移**：46 個帶 `zone` 屬性的 `@Scheduled` 觸發時刻不得改變（Spring 的 `zone` 覆蓋 JVM 預設）；3 個 `fixedDelay`／`fixedDelayString` 排程本質與時區無關。**本需求不新增、不修改任何 `@Scheduled`**，故「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController.JOBS`）不需新增或修改任何項目
- [ ] **警示冷卻期修正（本需求最嚴重的既有 bug）**：`StockAlertService` 的 24 小時冷卻判定，左側 `lastTriggeredAt` 是**市場牆鐘**（`computeTriggeredAt()` 以 `ZonedDateTime.now(市場 zone)` 寫入）、右側卻是 JVM 牆鐘，兩個不同時鐘直接比較，實際冷卻長度為 `24h ± 市場 offset`：**台股 32 小時**、英股 25 小時、美股 20 小時。台股警示於早上觸發後，隔天整個交易日（13:30 收盤前）都仍在冷卻中而**靜默漏發**。修法：右側改用同一市場的牆鐘。**此修正必須與 TZ 切換同批**——只改 TZ 會把台股修好（32h→24h）卻讓美股惡化成 12h、英股 17h（台北牆鐘比紐約快 12h、比倫敦快 7h），變成同一封警示信一天內重複寄兩次
- [ ] **三處「靠 UTC 巧合才正確」的交易日判定必須同批改**：目前三個市場的交易時段在 UTC 日期上恰與其交易日重合，故裸 `LocalDate.now()` 湊巧可用；切換為台北後，**美股在台北 00:00–04:00（＝ET 12:00–16:00，含收盤前最關鍵的 4 小時）會算出 `today = D+1`**，與 Redis／DB 的 `tradingDate = D` 兩側比對同時失敗 → 今日即時價完全不併入序列 → MA20/60/240 與 KD 全部以不含今日價的舊序列計算，警示判定與畫面指標同時失真。三處皆改為以該股市場時區取今日
- [ ] **新增市場時區的單一入口**：`MarketZones` 提供 `today(String market)` 與 `nowLocal(String market)`，作為所有「市場今日／市場現在」的唯一取得方式，避免各處各自寫一遍 `LocalDate.now(resolve(market))`
- [ ] **naive timestamp 欄位的語意收斂為兩種**：`timestamp without time zone` 欄位一律存**台北牆鐘**，唯二例外為 `stock_alert.last_triggered_at` 與 `stock_alert_trigger.triggered_at`（存**市場牆鐘**，是顯示端要的語意——使用者要看到「紐約時間 12:00 觸發」），該例外須於 `design.md` 與欄位註解標明。切換前的四種並存時鐘（台北／UTC／市場／DB）不得留存
- [ ] **一次性歷史資料校正，且必須與 TZ 切換同一次部署**：47 個非 Liquibase 的 naive 欄位中，**只有 11 欄需要 `+ INTERVAL '8 hours'`**；**28 欄已是台北牆鐘（由 service 層 `LocalDateTime.now(TW_ZONE)` 寫入）絕對不可動**，動了會弄壞排程的「今日是否已跑過」判定；2 欄市場牆鐘不可動；2 欄 Liquibase 內部不管；其餘為下一條所述的「不位移」欄位。台北全年固定 UTC+8、無夏令時間，固定 interval 安全
- [ ] **判準是「寫入端怎麼繫結」，不是 Java 型別**（這一條決定上一條的清單，寫錯就是不可逆的資料損毀）：
  - 裸 `LocalDateTime.now()` → **位移** → 納入校正
  - SQL `NOW()` 灌進 naive 欄 → **位移** → 納入校正（pgjdbc 每條連線把 JVM 預設時區當 session TimeZone 送出，`timestamptz→timestamp` 的隱式轉型即依該值）
  - JdbcTemplate 的 `Timestamp.from(instant)`（不帶 Calendar）→ **位移**，但**破口在寫入端**：改歷史值只會把舊列一起弄錯。修法是把寫入端改綁 `LocalDateTime.ofInstant(now, ZoneOffset.UTC)`，歷史資料不動
  - **Hibernate 的 `Instant` 欄位 → 不位移，絕對不可校正**：Hibernate 6 對 `Instant` 走 `TimestampUtcAsJdbcTimestampJdbcType`，bind 與 extract 兩側都帶 UTC Calendar，時區中立。對這些欄位 `+8` 會把目前正確的顯示永久改壞
- [ ] **同一個欄位可能有兩個寫入端**：`crawler_export_setting.gdrive_last_run_at` 由 business 的 Hibernate（`Instant`，時區中立）與 ext 的 JdbcTemplate（`Timestamp.from`，隨 JVM 時區）各寫一次。切換後兩端會分家 8 小時，故必須把 ext 端改成顯式 UTC 繫結；此欄**不納入**歷史校正
- [ ] **changeset 不可重跑**：本專案有前科（changeset 改 id 導致 Liquibase 視為新 migration 重跑 → 整站 crash loop）。校正 changeset 的 id **絕對不可改名**，且須附 `--rollback` 區塊；上線前須確認 `databasechangelog` 中無同 id 紀錄。重跑一次的後果是資料變成 +16 小時。**刻意不設日期 cutoff**：Liquibase 於 Web 層與排程啟動前執行、ext 又以 `depends_on: service_healthy` 等待 business，執行當下全表皆為舊值；寫死 cutoff 反而會在部署延後時漏掉那幾天的新列
- [ ] **測試環境與正式一致**：`backend` 與 `external-materials-service` 的 surefire 加 `-Duser.timezone=Asia/Taipei`，避免「本機測試過、容器行為不同」；並新增測試斷言市場今日入口在台北凌晨時段回傳美東當日而非 D+1
- [ ] **前端：顯示端零改動，但「今天」的產生方式必須改**。
  - **顯示端零改動**：後端改吐台北牆鐘後，既有的字串切割顯示（`s.replace('T',' ').slice(0,16)`）與 `dayjs` 格式化**自動變正確**。已確認前端不存在任何 +8 小時補償 hack（無 `28800`／`addHours(8)`／`utcOffset`），故**不得**為本需求在前端新增任何時區補償——那會造成雙重補償
  - **但前端自己算的「今天」是 UTC，後端換時區救不到**：`new Date().toISOString().slice(0,10)` 取的是 UTC 日期，台北 00:00–08:00 之間會得到「昨天」。這影響三處使用者可見的預設日期（新增交易／已實現損益／新增快照），且會與後端的 `isToday` 判定分家——目前兩邊碰巧都是 UTC 才一致，後端一改台北就會裂開，導致快照表單不刷即時匯率、美英股部位用舊 USD 匯率換算。這批必須改用本地日期（`toLocaleDateString('sv-SE')`），與後端同一個時區基準
- [ ] **可回退**：`TZ` 以 `${APP_TZ:-Asia/Taipei}` 形式注入，回退只需覆寫環境變數並 recreate；但 DB 校正無法由 Liquibase 自動 rollback，changeset 內須附對應的 `- INTERVAL '8 hours'` 反向 SQL 供人工執行

---

### Requirement 54: 警示觸發即時匯出 JSON 到指定目錄（含 Google Drive 同步）

**User Story:** 作為使用者，我希望警示條件一旦觸發，系統就立刻把觸發內容寫成 JSON 檔到我指定的目錄（本機、必要時再同步一份到 Google 雲端硬碟），讓其他程式能在觸發當下就讀到這件事，而不必等我開信箱、也不必去查資料庫。

> **定位（務必先讀）：這是第十個「輸出資料夾」頁面，但它不是排程匯出。** 既有九頁（Requirement 34／37／38／39／41／42／45／48／49；其 Google Drive 同步能力另見 Requirement 50／51）全部是「每天某個時刻跑一次」，本需求是**事件驅動**——觸發發生的那一刻就寫檔。這個差異決定了三件事：（a）設定表**沒有** `run_hour`／`run_minute`，套排程頁的樣板會多出兩個永遠沒人讀的欄位；（b）寫檔發生在 **Redis pub/sub 訂閱者執行緒**上（`PriceStreamService.onPriceUpdate` → `StockAlertService.checkAlertsFor` 是**同步呼叫**，見下方執行緒條），不是排程執行緒，能佔用的時間預算完全不同；（c）同一分鐘內可能連續觸發多次，而排程頁一天只跑一次。
>
> 其餘語意一律**比照 Requirement 51**：本機一律照寫、Drive 只是附加副本、上傳失敗 best-effort、Drive 開關限主要管理者。不提供「只寫 Drive」的選項。

**背景：** 警示觸發目前有三個出口——覆寫 `stock_alert.last_triggered_*`（畫面顯示）、寫一筆 `stock_alert_trigger`（歷史，保留 30 天）、enqueue email（Requirement 23）。三者都是給人看的，**沒有任何機器可讀的即時出口**：下游程式要知道「剛剛觸發了什麼」，只能去 poll 資料庫或解析信件。Requirement 50／51 已建好完整的輸出模型（本機基底 `EXPORT_OUTPUT_DIR` ＋ 相對子路徑、`GdriveOutputSupport` 共用元件、`el-tree` 雙模式資料夾選擇器、`gdrive_last_*` 狀態欄），本需求沿用該模型、只換掉「什麼時候寫」與「寫什麼格式」。

**Acceptance Criteria:**

- [ ] **匯出時機＝觸發當下，涵蓋兩條觸發路徑**：獨立單一條件（`StockAlertService.recordTrigger`）與複合 AND 群組（`recordGroupTrigger`，Task 253）**都要匯出**。只接其中一條會讓「複合條件觸發了但檔案沒動」，而使用者從畫面上看不出差別。插入點在**該路徑寫入 `stock_alert_trigger` 之後**——檔案內容是從該表重查產生的，先寫檔會漏掉當次這一筆。
- [ ] **檔案粒度＝固定單檔 ＋ 3 天滾動視窗，每次觸發全量重寫**：檔名 `alert_triggers_{ownerUserId}.json`（**不含日期**），內容為該 owner **最近 3 個台北日曆日**（今日 ＋ 前 2 日）的全部觸發；每次觸發覆寫同一檔。**不得用 append**：append 是 read-modify-write，同一檔可能被兩條路徑並發寫入而互相截斷，且中途失敗會留下半截 JSON；全量重寫天然冪等。檔名含 `ownerUserId` 的理由同 Requirement 51——多使用者共用同一個子路徑時不互相覆蓋，**不得以「實務上只有一人啟用」為由省略**。
- [ ] **不得用「每日一檔」，那會把同一個美股交易日切成兩個檔案**（本需求最初的設計缺陷，2026-07-29 實測後修正）：美股交易時段（紐約 09:30–16:00）換算台北是 **21:30 → 隔日 04:00**，橫跨午夜。實測同一個美股交易日（紐約 07-28）的四筆觸發被切開——VT/QQQ/VOO 的 `created_at` 是台北 07-28 21:30–21:40、AMZN 是台北 **07-29 00:00**，前三筆進 `_20260728.json`、第四筆進 `_20260729.json`，使用者打開當日檔只看得到一筆。這是**必然且每天發生**的（台股 09:00–13:30、英股 15:00–23:30 換算台北都不跨午夜，只有美股每天中）。滾動視窗讓下游永遠讀同一個檔就拿得到完整的近期觸發，不必猜日期、也不會被市場時區切開。
- [ ] **視窗以台北日曆日界定，不是滾動 72 小時**：起點為 `今日台北日期 − 2 天` 的 00:00，無上界。用「now − 72h」會讓同一筆觸發隨匯出時刻在檔案裡忽隱忽現（下游兩次讀到不同結果卻沒有任何事件發生）；用日曆日則同一天內的每次重寫視窗一致。3 天足以涵蓋週末與連假（週五盤中觸發，週一早上讀仍讀得到）。
- [ ] **JSON 須標明視窗範圍**：檔案層級輸出 `windowDays`（3）與 `since`（視窗起點台北時刻），下游才知道「沒有更早的資料」是視窗造成的，而不是真的沒觸發過。**不再輸出 `date` 欄位**——固定單檔沒有「這是哪一天的檔」這個語意。
- [ ] **系統一律不刪舊的日期檔**：改版前已寫出的 `alert_triggers_{ownerUserId}_{yyyyMMdd}.json` 留在使用者目錄裡不動（既有九頁的匯出檔亦然）。是否清理由使用者自行決定。
- [ ] **視窗篩選一律用 `created_at`，不可用 `triggered_at`**：`stock_alert_trigger` 有兩個時間欄，語意不同——`triggered_at` 存的是**市場牆鐘**（Requirement 53 明列的唯二例外之一：美股存紐約時間、英股存倫敦時間），`created_at` 存的是**台北牆鐘**。視窗起點是台北時刻，只有 `created_at` 與它同一個時鐘；拿市場牆鐘去比台北時刻，美股會整批位移 12 小時、英股 7 小時（視窗邊界附近的觸發時有時無）。JSON 內容中兩個時間欄**都要輸出**並標明時區語意（`triggeredAtZone`），供下游自行判斷。
- [ ] **owner 歸屬走 join，不得在 `stock_alert_trigger` 加 `owner_user_id` 欄位**：該表沒有 owner 欄，owner 由 `alert_id` → `stock_alert.owner_user_id` 或 `group_id` → `stock_alert_group.owner_user_id` 取得（兩者恰好一個非空，DB 以 `ck_sat_alert_xor_group` 保證）。加一個冗餘 owner 欄違反 CLAUDE.md「相同的資料只能存一份」，且該欄會與來源分家（alert 換 owner 時舊 trigger 列不會跟著改）。**匯出查詢必須是 owner-scoped 的 join 查詢**——`StockAlertTrigger` 未掛 `@Filter(ownerFilter)`，直接 `findAll()` 會把所有使用者的觸發寫進每個人的檔案，這是實質的跨租戶資料外流。
- [ ] **本機寫檔在觸發執行緒同步完成；Drive 上傳一律非同步且合併**（不遵守這條會讓整條即時價管線停擺）：`PriceStreamService.onPriceUpdate` 對 `checkAlertsFor` 是**同步呼叫**（該處註解寫「避免阻塞訂閱者執行緒」但實作並未如此，屬既有狀況、本需求不修，但正好說明風險），其上游是 Redis 訂閱者執行緒，同時還負責 SSE 廣播與交易雷達評估。故：
  - **本機寫檔同步做**——目標是真正的「即時」，一次查詢 ＋ 一次本機寫檔為毫秒級，與該路徑既有的 `stock_alert_trigger` INSERT ＋ email enqueue 同一量級。
  - **Drive 上傳絕不可同步做**——rclone 上傳逾時上限為 45 秒（Requirement 51 定案值），一次卡住就是整條價格更新管線停擺 45 秒。改為丟給**單執行緒 executor** 於背景執行。
  - **Drive 上傳須合併去抖，且必須以「延後至間隔到期的單一排定任務」表達**（最小間隔 60 秒，用 JDK 的 `ScheduledExecutorService.schedule`；**不引入 Quartz／`@Scheduled` 等排程框架**）。**不得**寫成「距上次上傳未滿間隔就只標記 pending、由已排入的那個任務完成後補跑」——當天最後一次觸發若發生在上一次上傳**完成之後**，就沒有任何執行中的任務會回頭看 pending，Drive 那一份會永久缺最後一筆，隔天檔名滾到新日期後再也不會被修正。理由：Drive 端每次上傳都有固定成本（Requirement 50／51 實測記載：token 幾乎每次都需 refresh、未設 `root_folder_id` 需逐層查找，加上四個 `RCLONE_LIMITS` 參數後單次仍需約 2.4 秒），開盤時段連續觸發會讓上傳次數遠多於有意義的內容變化；而且**檔案是當日全量重寫的**，晚一點上傳的那一份必然包含先前所有內容，合併不會遺失資料。
  - **合併的代價要寫進 UI 文案**：Drive 那一份最多可能落後 60 秒，設定卡須明示「本機即時、Drive 最多延遲約一分鐘」，否則使用者會把正常的延遲當成故障。
- [ ] **匯出失敗絕不影響觸發本身**：寫檔與上傳整段包 try/catch 只記 log 與狀態欄，**絕不**讓 `stock_alert_trigger` 的 INSERT 回滾、**絕不**中斷 email enqueue、**絕不**讓 `evaluate`／`evaluateGroup` 拋出而中止整輪檢查（比照該處既有的三段獨立 try/catch 寫法）。反向也成立：email 失敗不影響匯出。
- [ ] **觸發路徑在未啟用時完全不做任何事**：`enabled=false`（預設）時，觸發後的匯出直接 return——不查詢、不寫檔、不碰 Drive、不寫狀態欄。既有部署升級後行為與現況完全一致。**唯一例外是使用者主動按下的「立即匯出」**（run-now）：它是驗證工具，**不看 `enabled` 仍須產檔**，且不改變 `enabled` 的值。
- [ ] **JSON 內容須自足，不要求下游再查資料庫**：每筆觸發至少含股票代號、股名、市場、觸發條件文案、觸發時間（市場牆鐘）、寫入時間（台北牆鐘）、觸發價、月線／季線／年線／K／D 五個指標，以及觸發來源（獨立條件或複合群組）與其 id。**條件文案一律取自 `StockAlertService.buildLabel`／`buildGroupLabel`**，不得在匯出端自行串接——那四條既有路徑（警示頁／觀察頁／email digest／補發）已共用同一支，第五份必然分歧（CLAUDE.md「同義欄位、同一 business service API」）。檔案本身另含產生時間與 owner id 供下游驗證。**數值一律輸出為 JSON number 而非字串**，null 就是 null（指標資料不足時），不得以 `0` 或空字串充數。
- [ ] **本機寫檔須為 tmp ＋ atomic move**：下游程式可能正在讀同一個檔案，直接就地覆寫會讓對方讀到半截 JSON。沿用 Requirement 50 在爬蟲頁已用的作法（同目錄暫存檔 ＋ `ATOMIC_MOVE`）。**Drive 端不做 tmp ＋ rename**（沿用 Requirement 51 的既有決定：Drive API 未完成的上傳不會產生可見檔案，且 rename 需要 delete 權限的程式路徑，與「不實作任何刪除路徑」的自我約束衝突）。
- [ ] **同一 owner 的寫檔須序列化**：兩條觸發路徑可能由不同執行緒同時進入（Redis 訂閱者執行緒的 `checkAlertsFor` 與手動 `POST /api/stock-alerts/check` 的 HTTP 執行緒）。同一個 owner 的檔案寫入須以 per-owner 鎖序列化；不同 owner 之間不互相阻塞（全域單鎖會讓一個使用者的慢速磁碟拖累其他人）。
- [ ] **設定為 per-user，新表 `stock_alert_export_setting`**：欄位 `id`／`owner_user_id`（唯一，`@Filter(ownerFilter)`）／`enabled`／`output_subpath`／`last_run_at`／`last_run_status`／`gdrive_enabled`／`gdrive_subpath`／`gdrive_last_run_at`／`gdrive_last_status`／`created_at`／`updated_at`。**刻意沒有 `run_hour`／`run_minute`**（事件驅動，無執行時刻可設）。單一 Liquibase changeset 建表，`ADD`／`CREATE` 皆冪等；**seed 不啟用任何一列**。
- [ ] **`last_run_status` 的語意是「最後一次觸發匯出」而非「今日排程結果」**：一天可能寫入多次，狀態欄為最後一次的結果（同 Requirement 51 對交易雷達那一頁的處理）。Drive 狀態欄與本機狀態欄**分離**，理由同 Requirement 51：「本機成功、Drive 失敗」是正常且必須可分辨的狀態。**「本輪被合併去抖跳過」時，`gdrive_last_run_at` 與 `gdrive_last_status` 兩欄一律不碰**（不只是「不得寫成失敗」）：那兩欄的語意是「上次**上傳**的結果」，寫任何東西進去都會覆蓋掉前一次真正成功的落點與 bytes，並把時間欄寫成一個根本沒發生過上傳的時刻——與 Requirement 52 禁止把自檢警告寫進該欄是同一個理由。使用者要知道「本機即時、Drive 最多延遲約一分鐘」，走 UI 常駐文案，不入庫。
- [ ] **Drive 同步只有主要管理者能啟用，判準為 `isConfiguredAdmin(email)`**：一律走 `GdriveOutputSupport.resolveUpdate`（`PUT` 時 403）與 `syncQuietly`（每次上傳前以該列 `owner_user_id` 複驗），**不得自行查 `AppUserRepository`、不得用 `role == ADMIN`**。理由與 Requirement 51 完全相同（rclone remote 全機只有一份、綁定單一 Google 帳號，允許他人啟用等於把其財務資料送進別人的雲端硬碟）。本機輸出路徑與開關仍維持所有使用者皆可設定。
- [ ] **「立即匯出」按鈕（驗證用）**：提供 `POST /api/stock-alerts/export-setting/run-now`，以當前使用者身分把**視窗內已發生的觸發**重新產檔並（啟用時）上傳 Drive，回報本機落點與 Drive 落點。理由同 Requirement 51：Drive 設定對不對，使用者必須能在不等下一次觸發的情況下驗證。**視窗內尚無任何觸發時**須寫出一個 `triggers` 為空陣列的合法 JSON 並明白回報「近 3 天尚無觸發」，不得回 404 或靜默不產檔——使用者按下按鈕的目的是驗證落點，空檔案同樣達成該目的。
- [ ] **一頁一 BFF，且 Drive／本機目錄列舉沿用既有唯一那支**：新增 `StockAlertBffController`（`@RequestMapping("/api/bff/stock-alert")`）提供 `GET/PUT /export-setting`、`POST /export-setting/run-now`、`GET /export-setting/browse`、`GET /export-setting/browse-gdrive`；後兩者 passthrough 至 business 既有的 `GET /api/export-schedule/browse{,-gdrive}`，**不得在 business 端新開第二份目錄列舉實作**。
- [ ] **既有 `StockAlertBffRoutes` 的萬用 route 與新 controller 並存，順序必須確認**：既有 route 為 `/api/bff/stock-alert/**` → rewrite 成 `/api/stock-alerts${seg}`，會把 `/export-setting/browse-gdrive` 錯誤地轉成 `/api/stock-alerts/export-setting/browse-gdrive`（business 端沒有這個端點）。WebFlux 的 `RequestMappingHandlerMapping`（order 0）先於 Gateway 的 `RoutePredicateHandlerMapping`（order 1），故 controller 會先接走——**`TradingRadarBffController` 與 `TradingRadarBffRoutes` 已是同一模式的既有先例**，照它辦即可，但驗證步驟必須實際打這兩個端點確認沒有落到 route。
- [ ] **設定 UI 放在「警示條件」頁**（`/stocks?tab=alert`，`StockAlertView.vue`）：卡片含啟用開關、輸出資料夾（本機 `el-tree` 選擇器）、Drive 開關與 Drive 資料夾（僅主要管理者可見，沿用 `authStore.isConfiguredAdmin`）、「上次匯出」與「上次上傳」狀態、「立即匯出」按鈕。Drive 區塊的顯示條件與自檢警告提示沿用既有九頁的共用寫法（`showGdriveSelfCheckWarning`），**不另造一套**。
- [ ] **30 天清理不刪已匯出的檔案**：`cleanupOldTriggers` 只刪 DB 列。已寫到使用者目錄／Drive 的 JSON 是使用者的資料，**系統一律不刪**（既有九頁的匯出檔亦然）。這代表 DB 清空後舊檔仍在，屬預期行為，須於設定卡說明。
- [ ] **必須把新設定表加進啟動自檢的「全庫是否有任一列啟用 Drive」查詢**（Requirement 52）：該查詢目前是**八張表**的 UNION，是啟動時 L3 探測（`rclone lsd`）的前置閘門。漏加的話，只在本頁啟用 Drive 的部署遇到 rclone token 失效或 remote 被改名時，重啟會判定「全庫無人啟用」而**整個跳過探測、不噴任何 WARN**——正是 Requirement 52 要消除的那種「明天早上才發現全掛」。此為**假綠燈**，必須有測試守門（斷言該查詢的 SQL 確實含新表名）。
- [ ] **排程列表頁不新增項目**：本需求不新增任何 `@Scheduled`（匯出掛在既有觸發路徑內），故「公開資訊 → 排程列表」（Requirement 36 / `SchedulePublicBffController.JOBS`）不需新增項目。
- [ ] **測試**：（a）**跨午夜的美股交易日不得被切開**（本需求修正的那個缺陷的探針）——同一個美股交易日的兩筆觸發，`created_at` 分別落在台北 D 日 21:30 與 D+1 日 00:00，**必須同時出現在同一個檔案**；另驗視窗起點為 `今日 − 2 天` 的 00:00（落在起點前一秒的觸發不得入檔）；（b）**owner 隔離**——A 的觸發不得出現在 B 的檔案，且 join 兩路徑（`alert_id` 與 `group_id`）都要涵蓋；（c）**群組觸發**的條件文案為 `buildGroupLabel` 的合併結果（`" 且 "` 串接）而非單條；（d）**未啟用**時完全不產檔；（e）**匯出失敗不影響** `stock_alert_trigger` 寫入與 email enqueue；（f）**Drive 合併去抖**三點缺一不可——(f1) 60 秒內連續 N 次觸發只呼叫一次 `copyTo`；(f2) **尾端補跑**：兩次觸發相隔 5 秒、之後不再有任何觸發，間隔到期後**必須**發生第二次 `copyTo`（這條是上面那個資料遺失寫法的唯一探針，缺了它壞實作也會全綠）；(f3) 被合併的那幾次 `gdrive_last_run_at` 與 `gdrive_last_status` **值保持不變**（不只是「不得寫成失敗」）；（g）rclone 一律以 `RcloneClient` 介面替身注入、不實際連網；（h）既有警示觸發行為（`last_triggered_*` 覆寫、email、24h cooldown）須有回歸測試確認未被改動。
