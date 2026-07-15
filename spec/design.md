# Design

## Overview

資產管理系統採用前後端分離的全端架構，以 Spring Boot 提供 RESTful API，Vue 3 SPA 負責使用者介面，PostgreSQL 作為生產資料庫，透過 Docker Compose 進行容器化部署。系統核心能力包含多資產類型管理、Excel 批次匯入、外部市場資料整合，以及豐富的圖表視覺化分析。

```
┌──────────────────────────────────────────────────────────────────────────┐
│                              Docker Compose                               │
│  ┌──────────────┐  ┌────────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │   Frontend   │  │      BFF       │  │   Backend    │  │ PostgreSQL │ │
│  │  Vue 3 SPA   │─▶│ Spring Cloud   │─▶│ Spring Boot  │─▶│     16     │ │
│  │  Nginx :80   │  │ Gateway :8080  │  │ (internal)   │  │   :5432    │ │
│  └──────────────┘  └────────────────┘  └──────────────┘  └────────────┘ │
│                            │ /api/bff/**  ↓ (Dashboard 聚合)              │
│                            └──────────────────────────────────────────┐  │
│                                       External APIs                   │  │
│                                       TWSE / FinMind / NASDAQ          │  │
│                                       （由 Backend 呼叫）              │  │
└──────────────────────────────────────────────────────────────────────────┘
```

> **微服務架構（Task 15 已完成）：** BFF（`bff/`）作為 API Gateway，前端所有 `/api/*` 請求先到 BFF，BFF 透過 Spring Cloud Gateway 路由至 Backend（`backend/`）。BFF 另提供 `GET /api/bff/dashboard/summary` 聚合端點，並行呼叫多個 Backend API 一次回傳儀表板所需資料。

## Architecture

### Backend Architecture (Spring Boot)

採用標準分層架構：Controller → Service → Repository → Entity

```
com.steven.assets/
├── controller/          # REST endpoints, request/response mapping
├── service/             # Business logic, external API integration
├── repository/          # Spring Data JPA repositories
├── model/               # JPA entities + enums
└── dto/                 # Data Transfer Objects (Java records)
```

**Controller 層職責**
- 接收 HTTP 請求，進行基本格式驗證
- 委派 Service 層處理業務邏輯
- 統一回應格式（200/400/404/500）
- `GlobalExceptionHandler` 集中處理異常

**Service 層職責**
- `AssetService`: 快照 CRUD、總額計算、損益運算、歷史分析
- `ExcelImportService`: Excel 解析、格式偵測、資料清洗、批次儲存
- `MarketDataService`: 股利率查詢、ETF 持股查詢等不屬於即時報價的市場資料（外部抓價已搬至 `external-materials-service`）
- `PriceQueryService`: 對 `external-materials-service` 寫入 Redis 的 live 行情做唯讀；live 一律先讀 Redis，miss 則 fallback 至 `stock_price_history` 最近一筆收盤；歷史收盤直接讀 DB。`MarketDataController` 等對外 endpoint 皆透過此 service 取值，介面對 BFF / 前端不變
- `HistoricalDataService`: 歷史股價與匯率資料管理；歷史回補範圍以 `stock` 主檔（含曾持有 / 觀察清單 / 警示）為主，並聯集歷史快照中的持股代號
- `StockMasterService`: `stock` 主檔（被觀察 / 持有 / 警示標的的聯集）的唯一寫入入口。原本各路徑各自呼叫 `StockRepository.upsert(code, market, name)`，現統一改走 `StockMasterService.upsert(...)`：先以 `existsByCodeAndMarket` 判定是否為「新標的」（先前不存在），upsert 後若為新標的（且非台股大盤 0000）即以**單執行緒佇列**（serialize，避免大量匯入時並發打爆 Yahoo）背景呼叫 `HistoricalDataService.backfillSingleStock(code, market, now−10y)` 觸發 10 年歷史回補。動機：`HistoricalBackfillService.startupBackfill` 只在「服務啟動」掃描主檔回補，兩次重啟之間新增的標的（例 2026-06 新增英股 IB01）會落入「即時價有、走勢圖歷史只有今日一格」的空窗。呼叫端：`StockAlertService.create/update`、`AssetService.createSnapshot/updateSnapshot`、`StockAlertController.lookupName`（皆原 upsert 點）。既有標的的名稱更新 → `isNew=false` → 不重複回補；回補本身 idempotent（skip 已存在日期、今日列獨佔給 `ClosePersister`），失敗則由下次重啟 `startupBackfill` 的 stale/missing 條件補救
- `InstitutionService`: 銀行、券商、存款類型、市場類型的 CRUD、停用管理、關鍵字比對邏輯

**BFF 層**（`bff/` 模組）
- Spring Cloud Gateway：所有 `/api/*` 路由至 Backend
- 設計原則：**一個前端頁面對應一個 BFF controller**；前端只 render，aggregation 與計算（profit / profitRate / 買入均價 / 收盤價對齊等）一律由 BFF 預先處理
- `SnapshotEnricher`（共用工具，`bff/.../common`）：注入 investmentCostOriginal、抓快照基準日歷史收盤價、依 stockCode + market 合併 broker rows 為 mergedStocks（含 stockPrice、unitPriceTwd、profit、profitRate、avgCostOriginal）。各 BFF controller 一律走此工具，確保「同義欄位 = 同一邏輯」
  - `buildMergedStocks(..., revalueFromClose)`：`revalueFromClose=true` 時以 `closeMap`（與 `stockPrice` 欄同源的 `stock_price_history` 收盤價）重算 `currentValue = 股數 × 收盤價 ×（美股/英股）匯率` 並連動 `estimatedDividend / profit / profitRate / unitPriceTwd`，使「現值 = 股價 × 股數」自洽。**重算套 per-market 基準日閘門**（`isCurrentBasedate(basedate, market)`，Task 122）：**僅「基準日 == 該市場時區今日」的市場才重算**（建檔當天盤中暫定價需 refresh 成收盤）；**過去日期的快照一律保留 stored `currentValue`**（已是定案收盤值，且 revalue 在缺收盤/匯率時不可靠），與「歷年資產管理」（讀 stored）逐欄同源（Task 121/122）。**唯讀的 Dashboard 一律傳 true**（4 個 call site：summary / snapshot / tw-stock-lookthrough / us-stock-lookthrough）；**會存檔的編輯頁（SnapshotForm / SnapshotDetail）傳 false（3-arg overload 預設）**，維持快照凍結值供 broker row 以 `unitPriceTwd` 反推 `currentValue` 存檔。代價：過去快照的美股列「現值（stored）」與「股價（基準日收盤）× 股數 × 匯率」可能有 ~1% 落差（建檔當下價/匯率與定案收盤的時間差）；**刻意接受**以換取 Dashboard 與歷年頁逐欄同源（規格擁有者決策）
- `LiveAssetsOverlay`（共用工具，`bff/.../common`）：`applyToLatest(history, live)` —— 對資產歷史最新一筆套 **per-market 基準日閘門**（`SnapshotEnricher.isCurrentBasedate`）：台股 / 美股 / 英股各自「最新快照 `snapshotDate` == 該市場時區今日」時，該市場才以 `/api/market-data/live-assets` 的 `liveValue` 覆蓋；非今日市場保留 history 帶來的快照凍結收盤值（`totalTwStockValue / totalUsStockValue / totalUkStockValue`）。覆蓋後重算 `totalStockValue / totalAssets` 並連動 `investmentRate / increase / increaseRate`。**最新一筆為過去日期（三市場皆非今日）→ 完全不覆蓋**，使其顯示基準日收盤。`DashboardBffController.summary`、`AssetHistoryBffController` 兩支 BFF 與前端 `DashboardView.liveLatest` 皆採同一 per-market 規則，確保三處 `history` 最新列「同義欄位 = 同一算法 = 同值」。**為什麼過去日期不能用 live-assets 覆蓋**：`live-assets` 讀 Redis = 最新一筆 tick / 收盤，當最新快照日 < 今日且已有更新交易日的收盤時，覆蓋會把較新交易日的收盤洩漏到過去基準日（見 Task 121）；而快照凍結的 `stock_holding.currentValue` 對已定案的過去日期本來就 == 該日收盤，保留即正解。**今日列（snapshotDate == 今日）仍禁止直接吐 `asset_snapshot.total_*` 聚合**，因建檔當下可能是盤中暫定價、需以 live 收盤 refresh（見 Task 108/109）
- `DashboardBffController`（DashboardView 專屬）：
  - `GET /api/bff/dashboard/summary`：並行聚合 snapshots / history / prices / marketStatus / latestSnapshotDetail + mergedStocks
  - `GET /api/bff/dashboard/snapshot/{id}`：切換快照時用
  - `GET /api/bff/dashboard/realtime`：2 分鐘輪詢用，回傳 stockPrices + marketStatus
  - `POST /api/bff/dashboard/enrich-dividend-rates`：背景補齊所有快照缺漏的配息率
  - `PATCH /api/bff/dashboard/snapshot/{id}/stock-order`：拖曳排序持股後寫回
  - `GET /api/bff/dashboard/holdings-classified/{snapshotId}`：「資產配置分佈」雙層 donut（資產類別 → 個股）用，passthrough 至 `/api/snapshots/{id}/holdings-classified`（Requirement 25）
  - `GET /api/bff/dashboard/tw-stock-lookthrough/{snapshotId}`：「資產配置分佈」第 2 tab「台股個股」用。沿用 `SnapshotEnricher.buildMergedStocks` 取得快照基準日的台股部位，對每檔 ETF（`stockCode` 以 `00` 開頭）並行（concurrency 4）呼叫既有 `/api/market-data/etf-holdings?market=台股&code=...` 取得成分股權重（MoneyDJ 完整成分股優先、Yahoo `topHoldings` 前 10 fallback），依成分股權重**正規化**分配整筆 ETF 市值（`cv × weight / Σweight`，使 ETF 完全穿透不殘留 ETF 自身 slice）後依**股名**加總（MoneyDJ 只給股名無代號；直接持股不拆，與 ETF 內含部位的同股名合併），排序取前 10 名 + 「其它」聚合。ETF 抓取失敗時該 ETF 整筆退回以代號自身計入並記錄於 `degradedEtfs`。回傳 `TwStockLookthroughDto { snapshotDate, totalTwStockValue, items[], others, degradedEtfs[] }`。lazy fetch — 前端只在使用者切到第 2 tab 才呼叫；前端 hover 趨勢圖節點時改以該節點對應的 `snapshotId` 取 cache，cache miss 則 lazy fetch（race 防護：fetch 回來時若使用者目前的 effective snapshot 已切走則不覆寫畫面）
  - `GET /api/bff/dashboard/us-stock-lookthrough/{snapshotId}`：「資產配置分佈」第 3 tab「美股個股」用。流程鏡像台股版（`buildMergedStocks` → 篩 `market=美股` → 並行 concurrency 4 呼叫既有 `/api/market-data/etf-holdings?market=美股&code=...`），但**穿透演算法與台股不同**：美股成分股來源 Yahoo `topHoldings` 僅揭露前 10 大（權重總和常 30~50%），故依**真實權重**分配 `cv × weight/100`（**不正規化**），未揭露尾段 `cv × (1 − Σweight/100)` 全數計入「其它」（台股則 `cv × weight/Σweight` 正規化完全穿透）。聚合鍵用**代號**（Yahoo 成分股有 `symbol`；台股用股名）。ETF 判斷：BFF 對每檔美股 row 呼叫 etf-holdings，以「回傳 holdings 是否非空」區分 ETF / 個股 —— external `isEtf()`（`US_ETF_WHITELIST`）會對非 ETF 代號短路直接回空、不打 Yahoo，故個股呼叫成本低。注意：external 取 Yahoo crumb / quoteSummary（topHoldings）須用短 UA `Mozilla/5.0`（`YAHOO_UA` 常數），長 Chrome UA 會被 Yahoo WAF 回 429 致 topHoldings 全查無（`v8/chart` 端點不受影響）。回傳 `UsStockLookthroughDto { snapshotDate, totalUsStockValue, items[], others, lookthroughEtfCount }`；`lookthroughEtfCount > 0` 時前端顯示「美股 ETF 僅揭露前 10 大成份股，其餘計入『其它』」固定註記（美股不做 `degradedEtfs` 動態清單）。lazy fetch / hover 趨勢圖連動 / per-snapshot cache 與台股一致
- `SnapshotDetailBffController`（SnapshotDetailView 專屬）：
  - `GET /api/bff/snapshot-detail/{id}`：回傳 enriched detail + mergedStocks（含 brokerRows 子陣列，供編輯頁直接使用）
  - `GET /api/bff/snapshot-detail/brokers`：編輯券商欄位用，回傳 active brokers
  - `PUT /api/bff/snapshot-detail/{id}`：儲存編輯後的快照
- 存款 amount 換算規則（後端 `AssetService.normalizeDepositAmount`）：原幣值（`originalAmount` USD）由前端輸入直接傳入，**台幣 amount 一律由後端用 snapshot 匯率算出**（`amount = originalAmount × usdExchangeRate`），TRANSIT_* 的正負號也由後端依 `TransitFundType.payable` 決定。前端不做這部份計算，避免 snapshot 匯率為 null 時誤把 USD 數字寫進 TWD 欄位。
- `AssetHistoryBffController`（AssetHistoryView 專屬）：`GET /api/bff/asset-history`（history + isLastOfYear flag）、`POST /api/bff/asset-history/recalc-dividends`、`DELETE /api/bff/asset-history/{id}`、`GET /api/bff/asset-history/export`
- `RealizedGainBffController`（RealizedGainView 專屬）：`GET /api/bff/realized-gain`（gains + active brokers 一起回傳）、CRUD、export
- `ExchangeRateBffController`（ExchangeRateView 專屬）：`GET /api/bff/exchange-rate`（先 refresh 再回 10 年歷史）、`POST /api/bff/exchange-rate/backfill`
- `TradingCalendarBffController`（TradingCalendarView 專屬）：`GET /api/bff/trading-calendar?year=Y`（`holidays` 為 `{tw: {date→name}, us: {date→name}}` 物件 + `marketStatus`）、`GET /api/bff/trading-calendar/market-status`
- `SnapshotListBffController`（SnapshotListView 專屬）：`GET /api/bff/snapshot-list`、`DELETE /{id}`、`GET /export`
- `GdpTwseBffController`（GdpTwseView 專屬，`@RequestMapping("/api/bff/gdp-twse")`）：股市大盤查詢頁的指數日線／當日＋台韓人均 GDP 聚合（Requirement 18）：
  - `GET /api/bff/gdp-twse`：台／韓人均 GDP + 實質成長率歷史
  - `POST /api/bff/gdp-twse/refresh`：自 IMF 刷新人均 GDP
  - `GET /api/bff/gdp-twse/index-daily`：台股大盤／海外指數每日 OHLC
  - `POST /api/bff/gdp-twse/refresh-index-daily`：刷新指數日線
  - `GET /api/bff/gdp-twse/index-intraday`：指數當日分時
- 其他 settings/passthrough（每頁一支 Spring Cloud Gateway route 配置，rewrite `/api/bff/{page}/**` → `/api/{resource}/**`）：BankSettings、BrokerSettings、DepositTypeSettings、MarketTypeSettings、AssetClassSettings（`/api/bff/asset-class-settings/**`）、TransitFundTypeSettings、StockAlert（`/api/bff/stock-alert/**`）、BackupRestore（`/api/bff/backup-restore/**`）、NotificationSettings（`/api/bff/notification-settings/recipients/**` → `/api/notification-recipients/**`）
- `WatchStockBffRoutes`（WatchStockView 專屬，`/api/bff/watch-stock/**`）：純 Spring Cloud Gateway passthrough route，rewrite `/api/bff/watch-stock(/**)` → `/api/watch-stocks(/**)` 轉至 business-services。觀察清單已改為「`stock_alert` 衍生 view」，**衍生與 enrichment 一律在 business-services（`WatchStockController` / `WatchStockService`）完成，BFF 僅轉發不重算**：
  - `GET /api/bff/watch-stock` → `GET /api/watch-stocks`：business-services 由 `stock_alert` 群組去重衍生清單，對每筆 (stockCode, market) 補上 live 報價、技術指標、最近觸發資訊
  - `PUT /api/bff/watch-stock/order` → `PUT /api/watch-stocks/order`：拖曳排序時把該股票所有 alert 的 displayOrder 整組重排
  - `POST /api/bff/watch-stock/resend-digest?market=` → `POST /api/watch-stocks/resend-digest`：手動補發最後交易日警示 digest 信，`market` query（台股/美股/英股）限定只補發當前市場 tab、原樣帶過（passthrough 也涵蓋 `GET /api/watch-stocks/chart.png`）
  - 新增觀察由前端直接呼叫 `/api/bff/stock-alert/**` 建立警示條件；移除觀察由前端在「警示條件」頁刪除該股票所有 alert 達成，因此不提供 watch-stock 級的 create / delete 端點
- `SnapshotFormBffController`（SnapshotFormView 專屬）：把表單頁的多步協調邏輯（價格批次查 + backfill fallback + 配息率補抓 + 名稱補齊 + 匯率智慧 fallback）集中於此
  - `GET /api/bff/snapshot-form/{id}`：編輯模式 bootstrap，回傳 enriched detail + mergedStocks
  - `POST /api/bff/snapshot-form/prices?date=YYYY-MM-DD`：批次取得每筆股票的歷史收盤價 + 漲跌 + 名稱 + 配息率（DB 缺資料時自動 backfill 重試）。`enrichBatch` per-market 基準日規則：basedate==該市場今日→用 live 即時漲跌；否則（frozen）改用 `hist`（`prices-on-date`）已算好的「當日漲跌」（該收盤日 vs 前一交易日），不再留空——收盤/週末頁也顯示漲跌，與 Dashboard 同源。前端 `SnapshotFormView` `.price-up=#dc2626`（漲紅）/`.price-down=#16a34a`（跌綠），台股慣例
  - `GET /api/bff/snapshot-form/realtime`：2 分鐘輪詢用，先 trigger 後端刷新行情再回傳 stockPrices + marketStatus
  - `GET /api/bff/snapshot-form/exchange-rate?date=YYYY-MM-DD`：取指定日期 USD 匯率（今天會先 refresh，假日往前 fallback）
  - `GET /api/bff/snapshot-form/lookups`：表單下拉一次取齊（banks / brokers / depositTypes / transitFundTypes，皆已過濾 active）
  - `GET /api/bff/snapshot-form/funds`：信託基金主檔（passthrough 至 `/api/funds`），每筆已含 latestNav / latestFxRate / twdPerUnit；`?date=` 時改用該基準日（Requirement 19/21）
  - `POST /api/bff/snapshot-form/fund-nav/refresh`：觸發後端 → external-materials-service 立即刷新所有基金 NAV，回 `{ success, failed, total }`
- `StockAnalysisBffRoutes`（StockAnalysisDialog 跨 view 共用元件專屬）：對話框被 Dashboard / SnapshotForm / WatchStock / StockAlert / RealizedGain 五個 view 同時使用（已實現損益明細列雙擊開啟），依「同義欄位、同一 business service API」原則拆為獨立 BFF route，避免在五個父 view 的 BFF 各自重複代理。Dashboard 開啟此 dialog 的觸發點有三：持股表格列雙擊（`onStockDblClick`）、個股 bar 圖雙擊（`onBarDblClick`）、以及「資產配置分佈」tab 2/3 個股穿透圓餅圖 segment 單擊（`onLookthroughPieClick(params, market)`，「其它」聚合段無代號不開；命中當前快照 `mergedStocks` 同 `stockCode`+`market` 的直接持股則沿用完整列以保留 `avgCostOriginal` 成本欄位、否則僅帶 `{stockCode, stockName, market}`）。提供：
  - `GET /api/bff/stock-analysis/history/stock` → `/api/market-data/history/stock`
  - `GET /api/bff/stock-analysis/dividends` → `/api/market-data/dividends`
  - `GET /api/bff/stock-analysis/etf-holdings` → `/api/market-data/etf-holdings`
  - `POST /api/bff/stock-analysis/backfill-stock` → `/api/market-data/history/backfill-stock`（Task 136 lazy 回補：走勢圖無歷史時即時觸發單檔 10 年回補後重載；只寫 `stock_price_history` 不入主檔、今日列獨佔給 `ClosePersister`）
  - `GET /api/bff/stock-analysis/intraday-ticks` → `/api/market-data/intraday-ticks`（走勢圖「當日」期間用；business-services proxy 至 `external-materials-service /internal/intraday-ticks`，後者讀 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`。**date 省略時的預設 bucket**：今天（該市場時區）若為交易日且今日 tick LIST 已有資料就用今天，否則退回 `findMaxTradingDate`（最近有收盤的交易日）——不可直接用 `findMaxTradingDate`，否則盤中今日收盤價尚未入庫、預設日期會停在前一交易日、讀到空 bucket（見 Task 153）。盤中由 `PricePoller` 每 2 分鐘累積 / 盤後由 `IntradayTickRefresher` 用台股 FinMind `TaiwanStockKBar` + 美/英股 Yahoo 5m 完整覆寫。**盤後 refresh cron：台股 14:00 TW、美股 16:05 ET、英股 17:00 LON**；台股/英股排在收盤後 ~30 分而非 5 分，因 Yahoo 對 TWSE(~25min)/LSE(~15min) 的 5m feed 有延遲，收盤後 5 分抓會截斷（台股停 13:10、英股停 ~16:20），美股 Yahoo 無延遲故 16:05 即完整。另 `IntradayTickStore.replaceTicks` 採 **never-shrink** 防截斷：新抓資料末刻早於既有 LIST 末刻（或為空）就保留既有不覆寫，避免截斷版蓋掉 polling 已到收盤的 tick（Task 156）。台股 Yahoo 5m fallback 的 ticker 後綴依掛牌市場：上市 `.TW`、上櫃（TPEx，含債券 ETF 00xxxB）`.TWO`，`PriceFetchClient.fetchIntraday5m` 先試 `.TW` 空再 fallback `.TWO`，否則上櫃股當日分時恆空，見 Task 119）。**「當日」上方的「昨收 / 今日漲跌」不走此端點、亦不新增欄位**：`StockAnalysisDialog.vue` 於前端 view 從已載入的日線 `history`（同對話框 `/api/bff/stock-analysis/history/stock`）取「該分時交易日之前最後一筆 `stock_price_history` 收盤」為昨收、以 legend 顯示的現價（`lastNonNull(prices)`）自算今日漲跌，維持 tick API 契約（`List<IntradayTick>` 僅 `time`+`price`）不變，且與 Dashboard／管理資產「當日漲跌」同一「vs 前一交易日原始收盤」口徑（Task 158）

- **共享 / 跨頁 passthrough routes**（非單一頁面專屬，由 Spring Cloud Gateway 直接轉發至 business-services，服務跨頁共用的 store CRUD、下拉 lookups、SSE 與基金主檔；皆為刻意的共享資源，不另立 `/api/bff/{page}/**`）：
  - `SnapshotBffRoutes`：`/api/snapshots/**` → business-services（Pinia store 共用快照 CRUD；單頁資料仍走各自的 `/api/bff/{page}/**`）
  - `SettingsBffRoutes`：`/api/settings/**` → business-services（銀行 / 券商 / 存款類型 / 市場類型 / 待轉入資金類型的下拉 lookups，被多個表單頁共用；各設定頁的 CRUD 仍走 `/api/bff/{page}-settings/**`）
  - `FundBffRoutes`：`/api/funds`、`/api/fund-nav/**`、`/api/fund-dividend/**` → business-services（FundSettingsView 基金主檔 CRUD 與 NAV / 配息回補，Requirement 19/20/21）。**慣例例外**：基金主檔頁直接沿用 `/api/funds` 資源 passthrough，未另設 `/api/bff/fund-settings/**`（基金主檔為跨頁共享資源）。SnapshotForm 讀同一份基金主檔，但走自己頁面專屬的 `/api/bff/snapshot-form/funds`（同樣 passthrough 至 `/api/funds`，符合「一頁一 BFF」）
  - `RealizedGainBffRoutes`：`/api/realized-gains/**` → business-services（RealizedGainView 的 Pinia store `gainApi` 共用已實現損益 CRUD；該頁另有 `RealizedGainBffController` 提供 `/api/bff/realized-gain` 聚合端點，passthrough 僅供 store 直接 CRUD 用，與 `SnapshotBffRoutes` 同屬「store 共用」例外）
  - `MarketDataBffRoutes`：`/api/market-data/**` → business-services（SSE 行情串流 `prices/stream` 等直接市場資料取用；`StockAnalysisBffRoutes` 另以 `/api/bff/stock-analysis/**` rewrite 至同一組端點）

**Repository 層**（Spring Data JPA，共 33 個）
- `AssetSnapshotRepository`
- `StockHoldingRepository`
- `FundHoldingRepository`
- `BankDepositRepository`
- `RealizedGainRepository`
- `StockPriceHistoryRepository`
- `StockRepository`（股票主檔）
- `ExchangeRateHistoryRepository`
- `BankRepository`
- `BrokerRepository`
- `DepositTypeRepository`
- `MarketTypeRepository`
- `TransitFundTypeRepository`
- `StockAlertRepository`（含衍生 query：`findDistinctStockCodeMarket()` 提供觀察清單去重結果）
- `StockAlertTriggerRepository`（警示觸發歷史，30 天輪替）
- `BackupSettingRepository`（備份保留代數設定，單列資料表）
- `BackupRecordRepository`（備份紀錄，單一事實來源；Requirement 15）
- `StockDividendHistoryRepository`（股利歷史，Requirement 13）
- `FundMasterRepository` / `FundNavRepository` / `FundDividendHistoryRepository`（信託基金主檔／淨值／配息；Requirement 19–21）
- `PaymentCategoryRepository` / `PaymentAccountRepository`（代繳分類／記錄；Requirement 22）
- `NotificationRecipientRepository`（警示通知收件人；Requirement 23）
- `StockAlertRecipientRepository`（警示 ↔ 收件人多對多 join；每條警示挑選收件人；Requirement 23 / Task 125）
- `TwseIndexDailyHistoryRepository` / `UsIndexDailyHistoryRepository`（台股大盤／海外指數日線；Requirement 18，亦供 Requirement 14 觀察清單 `0000` KD）
- `TaiwanGdpPerCapitaHistoryRepository` / `JapanGdpPerCapitaHistoryRepository` / `KoreaGdpPerCapitaHistoryRepository`（台／日／韓人均 GDP；Requirement 18）
- `AssetClassRepository` / `StockStyleRepository` / `BondTermRepository`（資產類別／股票風格／債券期別分類主檔；Requirement 25–27）
- `FundClassOverrideRepository`（基金分類人工指定，PK = fund_name；Requirement 27）

### External Materials Service Architecture（獨立微服務 / image：`asset-external-materials-service`）

抓取與分發股價的子系統獨立成單一 Spring Boot image，部署為獨立 container。背後動機：抓價邏輯（外部 API、cron、市場時段）與主業務邏輯解耦，外部 API 失敗或限流不會拖垮 `business-services`，並可獨立重啟 / scale。

**模組結構（Maven module `external-materials-service/`）：**
```
com.steven.assets.externalmaterials/
├── config/             # WebClient、Redis 連線設定
├── client/             # TwseClient、NasdaqClient、FinMindClient（從 backend 搬出）
├── service/
│   ├── PricePoller     # 兩段獨立 cron（搬自原 StockPriceService.scheduledTw/UsIntradayUpdate）
│   ├── ClosePersister  # 盤後收盤 cron（搬自 recordTw/UsClosingPrice），寫 stock_price_history
│   ├── MarketClock     # isTwMarketOpen / isUsMarketOpen（平日 + 時段 + 假日；委派 MarketCalendar 判假日）
│   ├── MarketCalendar  # 交易日 / 國定假日判定（台股→TWSE holidaySchedule；美/英→NYSE/LSE 純函式）
│   ├── PriceCacheWriter # 寫入 Redis（封裝 key schema）
│   ├── FundNavPoller   # 信託基金 NAV 每日 cron（Requirement 19）
│   └── FundNavPersister # 寫 fund_nav 表
├── client/
│   ├── PriceFetchClient
│   ├── DividendFetchClient
│   └── FundNavFetchClient # FundClear / MoneyDJ 抓 NAV（Requirement 19）
└── controller/
    └── InternalPriceController # POST /internal/refresh、POST /internal/fund-nav/refresh
```

**匯率來源鏈（`ExchangeRatePoller` + `BotFxFetchClient` / `YahooFxFetchClient` / `ExchangeRateFetchClient`）：**
匯率寫入 `exchange_rate_history`（僅 `buy_rate` / `sell_rate` 兩欄，`mid_rate = (buy+sell)/2` 為 `@Transient` 衍生）。`(currency, rate_date)` 為 upsert 覆寫鍵。三層來源依「當日新鮮度 / 是否含真實買賣價」分工：

| 來源 | client | 角色 | 買賣價 | 觸發 |
|------|--------|------|--------|------|
| 台灣銀行牌告 CSV | `BotFxFetchClient`（curl 子程序 + 短 UA `Mozilla/5.0`） | **當日主來源**，真實即期買入/賣出 | ✅ 即期買/賣 | 盤中每 5 分鐘 cron（`0 0/5 9-15 MON-FRI` Asia/Taipei）+ 手動 `refreshBotNow` |
| Yahoo Finance `TWD=X` | `YahooFxFetchClient`（curl 子程序 + 短 UA） | **當日備援（僅 USD）**：BOT 抓不到時取當日中間價，`buy=sell=mid` 暫定寫入今日列 | ❌ 僅 mid | 同上路徑，BOT `fetchSpot` 回 empty 時 fallback |
| FinMind `TaiwanExchangeRate` | `ExchangeRateFetchClient` | **T-1 對帳回補**：以真實即期買/賣覆寫近期列（含 Yahoo 暫定的當日中間價，隔日升級為正式買賣盤） | ✅ spot 買/賣（ZAR cash 恆 0 須改用 spot） | 收盤後 17:00 cron + 手動 refresh 的 `backfillExchangeRate(近 10 日)` |

> **2026/06 台銀 WAF 失效背景**：`rate.bot.com.tw` 全站套上 Akamai SEC-CPT 主動式 JS PoW 挑戰（HTTP 200 但 body 為 "Challenge Validation" HTML + `set-cookie: sec_cpt`），curl 子程序無 JS runtime 無法解題，所有牌告路徑（flcsv / fltxt / 單一幣別 / HTML）皆抓不到 → `BotFxFetchClient.fetchSpot` 對所有幣別回 empty，盤中即時匯率靜默失效，僅 17:00 FinMind 補到 T-1，導致「當日 6/30 缺、最新停 6/29」。故引入 Yahoo `TWD=X` 當日中間價作為 USD 備援（Task 142）。**口徑代價（刻意接受）**：Yahoo 只給中間價，當日列 `buy=sell=mid` 會抹平買賣價差（買入價較真實值高約半個價差 ~0.03），且下游基金贖回現值（`getFundValuationRate` 採 `buyRate`）當日略為高估；此暫定值隔日即被 FinMind 真實買賣價覆寫，屬 1 日內的近似。`buy == sell` 隱含標記該列為中間價來源（未另加 `source` 欄位）。

**Redis key schema：**
| Key | 內容 | TTL | 寫入者 |
|-----|------|-----|--------|
| `price:{market}:{code}` | JSON `{ price, prevClose, changePercent, open, high, low, volume, tradingDate, source, updatedAt }` | 24 小時（涵蓋整個交易日 + 跨夜，避免低流動性股票長時間 z='-' 後 TTL 過期退回昨收） | `PriceCacheWriter` 每 2 分鐘 |
| `price:index:{market}` | Set，紀錄該市場所有有 cache 的 stockCode | 24 小時 | 同上 |
| `price:dayhl:{market}:{code}:{tradingDate}` | JSON `{ high, low }` 該交易日累積觀察到的最高 / 最低成交價 | 36 小時（跨日 dump 後仍可佐證） | `IntradayHighLowTracker` 每次 cron tick |
| `market:status` | JSON `{ twMarketOpen, usMarketOpen, twTime, usTime }` | 90s（短於輪詢） | `MarketClock` 每分鐘 |

**盤中 high / low 聚合（`IntradayHighLowTracker`）：**
NASDAQ info API 自 2026/04 起對 ETF 的 `keyStats` 為 null（無 dayrange 欄位），對 stocks 也僅剩 dayrange，無法穩定取得「今日最高/最低」。為避免 ETF（VOO/VT 等）的 high/low 為 null，`external-materials-service` 在每輪抓價後自行聚合：以該檔當日已記錄的 high/low 與最新成交價做 max / min，回寫 Redis。`PriceCacheWriter` 在寫 `price:{market}:{code}` 時：
- 若外部 API 已回傳 high/low，最終值取「外部值與聚合值的 max(high) / min(low)」（覆蓋 cron 起點之前已過去的盤中波動）
- 若外部 API 未回傳，直接採用聚合值
- 聚合 key 的 `tradingDate` 與 JSON 中的 `tradingDate` 同源（`PriceCacheWriter.resolveTradingDate`），確保跨日（美股 session 跨 ET 午夜在 TW 看為當日）能正確分桶

> `market:status` TTL 設 90s 短於輪詢間隔，確保 Redis 過期前一定會被覆寫；fail-safe 若 external-materials-service 掛了，`PriceQueryService` 視 Redis miss 為「未開盤」（保守處理）。

**抓價來源同舊**：TWSE mis API（台股 live）、NASDAQ info API（美股 live）、FinMind TaiwanStockPrice（台股盤後收盤）。

**美股盤中 `openPrice` 補強（2026/05 初版用 NASDAQ /historical；2026/06 Task 120 改 Yahoo daily bar）：** NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`。初版改打 `https://api.nasdaq.com/api/quote/{code}/historical?...&fromdate==todate==美東今日`，但 NASDAQ 對「`fromdate == todate`」一律回 **400（`Provided date is less than from date`）**、給日期區間盤中又**不含今日列**，故該法恆失敗 → `openPrice` 永遠 null → Redis live 無 open → 盤後 dump 進 `stock_price_history` 亦無 open → 觀察清單「開盤」欄對所有美股恆顯示「—」。**現行作法**：`PriceFetchClient.fetchUsTodayOpenFromYahoo(code)` 打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}?interval=1d&range=1d`，取 `chart.result[0].indicators.quote[0].open[0]`（盤中＝今日部分 bar 的 open、盤後＝完整 bar 的 open；只取 open 欄、不碰 close，不違反 Task 84「禁寫今日列收盤」）。走 curl 子程序避開 Yahoo HTTP/2 fingerprint 偵測；為補強欄位遇 429 fail-fast 不重試（`curlGetWithRetry(url, 0)`），取不到回 null 由 `WatchStockService` 的 `stock_price_history` fallback 接手。HTTP 呼叫在 `PricePoller.updatePrices` 的 virtual-thread pool 中與其他 stock 並行執行，不會延長 cron 週期。台股 open 走 TWSE mis `o`、英股走 Yahoo `regularMarketOpen`。

**TWSE `z='-'` 時 skip write — Redis 只能由真實 z tick 推進（2026/06，修正盤中股價跳回昨收的 bug）：** TWSE mis API 的 `z`（最近一筆成交價）以每 5 秒 tick 為單位，這一輪 cron polling 撞到「兩 tick 之間沒有新成交」的視窗時會回 `z='-'`。舊邏輯將 `z='-'` 視為「整天無成交」並退回 `y`（昨日收盤）寫 Redis（`source = "TWSE(前收)"`），盤中只要任一輪 polling 命中就會把 Dashboard 持股圖、即時資產估算的所有現值瞬間替換成昨收，造成「盤中股價與實際明顯偏差」。

**最終規則只有兩條（規格擁有者明確指示，覆蓋早期實驗性的 `TWSE(開盤)` cold-start 設計，見 Task 81 回退）：**

1. **每天開盤抓不到最新值 → 顯示昨日收盤**：`getTwseRealTimePrice` 在 `z='-'` 時回 `Optional.empty()`、不寫 Redis；若 Redis 為空，`PriceQueryService.getLive` 自然 fallback 至 `stock_price_history` 最近一筆收盤（=昨日收盤），讓前端始終有值可顯示。
2. **每次 tick 抓不到值 → 不要更新 Redis**：`PricePoller` 收到 `Optional.empty()` 直接 return，保留上一輪成功 poll 寫入的真實 z；不視為錯誤，不丟 exception（同時涵蓋 NASDAQ 查無資料的 fail-soft）。

`PriceCacheWriter.write` 收到任何 `PriceResult` 都無條件寫入（不再有「source 含 `(` 就守門」的特殊路徑），因為 client 端已保證不會送 `o` / `y` 衍生值上來。

合併效果：
- 盤中有成交的 5 秒 tick → 寫真實 `z`（`source="TWSE"`/`"NASDAQ"`）。
- 盤中沒成交的 5 秒 tick + 之前已有 cache → 保留前一筆真實 intraday 價（cache 仍是 `TWSE`，updatedAt 也維持上輪時間，誠實表達「沒新資訊」）。
- 首輪 polling 即 `z='-'`（盤剛開、剛清過 cache、TTL 過期）+ Redis 空 → `PriceQueryService.getLive` fallback 至 `stock_price_history` 顯示昨收，前端有明確值。
- 設計意圖：**Redis 內的值反映「最近一次真實成交」，updatedAt 反映「最近一次成功 poll 到真實成交的時間」**。盤中可能「看起來價格沒變」，但那是真實狀態（無新成交），不是 bug。

`source` 欄不再出現 `"TWSE(前收)"`、也不會出現實驗階段曾用過的 `"TWSE(開盤)"`；走勢圖今日格的 `source.contains("(")` 守門條件仍適用於 `(history)` 等非「今日真實成交」來源（保留，避免未來其他來源被誤拼入今日格）。

**國定假日整段休市（2026/06，修 Juneteenth 仍抓價 / 觸發 bug）：** cron 以 `MON-FRI` 觸發只能濾週末，平日仍可能是國定假日（美股 6/19 Juneteenth 為週五）。原 `MarketClock` 只有 `isWeekend` 判斷 → `isUsMarketOpen()` 在假日回 `true` → `PricePoller` 照抓（NASDAQ 回前一交易日收盤）、`PriceCacheWriter.resolveTradingDate` 把假日當「live session」→ tradingDate = 假日當天 → 污染 intraday tick；`ClosePersister` 同樣於假日把 Redis 最後價 dump 成假日當日收盤寫進 DB。

修正：新增 `MarketCalendar`（ext-materials 內的交易日 / 假日權威）：
- `isTwTradingDay/isUsTradingDay/isUkTradingDay(date)` = `非週末 && 非該市場假日`。
- 台股假日委派既有 `MarketDataFetchService.getTwHolidays(year)`（TWSE holidaySchedule，已是台股唯一來源；抓不到時保守視為交易日，與 business-services 既有退化一致）。
- 美股 / 英股假日為 NYSE / LSE 法定規則純函式（與 `business-services` `MarketDataService.getUsHolidays/getUkHolidays` 同一套；因兩服務無共用 module、且 ext-materials 不可反向依賴 business-services（避免循環），故各持一份並以交叉註解鎖定「修改須同步」，per-year 快取）。

`MarketClock.isXxxMarketOpen / isXxxMarketJustClosed` 全部改為「`MarketCalendar.isXxxTradingDay(當日)` && 時段」。連帶效果：`PricePoller` 各 scheduled 抓價（gated on `isXxxMarketOpen`）假日自動 skip；`PriceCacheWriter.resolveTradingDate`（依 `isXxxMarketOpen/isXxxMarketJustClosed` 判 live session）假日自動退回 DB 最近交易日。`ClosePersister` 各 dump / verify / `selfHealMissedClose` 另加 `MarketCalendar.isXxxTradingDay` 早退守門（`PricePoller` 假日不抓，但 Redis 仍有前一交易日值且 TTL 24h，若不守門 dump 會把它標成假日當日寫 DB）。`PricePoller.refreshAll`（手動 `/internal/refresh`）的 `markClosed` 亦由 `false` 改為 `!isXxxMarketOpen()`，與 `warmCacheOnStartup` 一致，避免手動刷新在假日 append 假 tick。

**對外介面：**
- `POST /internal/refresh`（僅 docker network 內 `business-services` 呼叫）：同步抓所有持股一次、寫 Redis、回 200。供使用者按「刷新」時用
- 不對前端暴露 REST；前端仍打 `business-services` 的 `/api/market-data/*`，由 `PriceQueryService` 從 Redis 取值

**`stock_price` 表廢除**：原本作為 hot cache 與 `stock_price_history` 並存，現由 Redis 取代；移除後即消除「兩處 cache 漂移」的可能。`StockPrice` Entity 與 `StockPriceRepository` 一併移除。

**Sequence（盤中刷新）：**
```
[Frontend] → [BFF] → [business-services /api/market-data/prices]
                              ↓ PriceQueryService
                              ↓
                      [Redis price:*:* GET]  ← (miss) → [stock_price_history latest]
```

**Sequence（盤中 2 分鐘排程）：**
```
[external-materials-service @Scheduled] → TWSE/NASDAQ → PriceCacheWriter → [Redis SET price:*:*]
```

**Sequence（盤後收盤）：**
```
[external-materials-service @Scheduled 13:32/16:02] → Redis last tick
   → ClosePersister.dump*CloseFromRedis → [DB stock_price_history UPSERT]

[external-materials-service @Scheduled 16:00 TW / 18:00 ET] → FinMind
   → ClosePersister.verify*CloseWithFinMind
     ├→ [DB stock_price_history UPSERT]                  // 權威收盤值
     └→ PriceCacheWriter.writeVerifiedClose
         ├→ [Redis SET price:{market}:{code}]            // 覆寫盤中 last tick
         └→ Redis PUBLISH price-update                   // SSE 推送
```

> 為什麼 FinMind 校正後也要覆寫 Redis：盤中最後一輪 cron 通常落在 13:28 / 15:58（集合競價開始前 2 分鐘），
> 抓到的是盤中 last tick 而非 13:30 / 16:00 集合競價產生的官方收盤。若只更新 DB，Dashboard / SnapshotForm
> 在 `basedate == 今日` 時讀 Redis 就會看到 last tick，與「歷年資產管理」（讀 DB）對不起來。

**「今日列」獨佔規則（Task 84）：** `stock_price_history` 中市場時區「當日」row 只能由上述 `ClosePersister` 路徑（13:32 / 16:02 dump，16:00 / 18:00 verify，含 `selfHealMissedClose` 過收盤時點補救）寫入。`HistoricalBackfillService.backfillTwStock` / `backfillUsStock` 即使被任意路徑觸發（`startupBackfill` 條件 stale、`SnapshotFormBffController.triggerBackfillThenRefetch`、`/api/market-data/history/backfill-stock` 手動觸發、`/internal/backfill/all`），在 for-loop 中遇到 `bar.tradingDate().equals(LocalDate.now(marketZone))` 必須 `continue`。原因：外部歷史 API 在盤中也會回一根「今日 partial bar」 — Yahoo Finance `chart?interval=1d` 把今日 open/high/low/「此刻 last trade」打包成一筆 `HistoricalBar`，若直接 upsert 就會落在 `stock_price_history` 充當「收盤」，與 Redis 即時 tick 脫鉤（例 2026-06-05 NY 盤中 VOO：Redis tick 677.20 / DB row close 689.70）。今日列由 ClosePersister 在收盤後（含 self-heal）建立，使 backfill 路徑只負責「歷史」、close 路徑只負責「當日」，職責不重疊。

**Live price push（Redis pub/sub + SSE，取代輪詢）：**

為了消除前端 2 分鐘 polling 與 external-materials-service 2 分鐘 cron 的相位差（最差 ~4 分鐘 lag），
價格寫入後即時推到前端：

- Redis channel：`price-update`
- external-materials-service `PriceCacheWriter.write()` 寫完 Redis SET 後，`PUBLISH price-update <json>`
- business-services `PriceStreamService` 透過 `RedisMessageListenerContainer` 訂閱該 channel，
  fan-out 到 `Sinks.Many<String>`
- business-services 暴露 `GET /api/market-data/prices/stream`（`text/event-stream`），
  從 sink 串流 SSE
- BFF 加 passthrough route `/api/bff/market-data/stream` → backend SSE endpoint
- 前端用 `EventSource('/api/bff/market-data/stream')` 訂閱，每筆訊息更新 stockPrices reactive state

```
[external-materials-service write] → Redis SET price:*:*
                     └→ Redis PUBLISH price-update {json}
                                          ↓
                     [business-services PriceStreamService 訂閱] 
                                          ↓
                     [SSE /api/market-data/prices/stream]
                                          ↓
                     [BFF passthrough] → [Frontend EventSource] → stockPrices state
```

> nginx 對 `/api/bff/market-data/stream` 路徑需設 `proxy_buffering off`，否則 SSE 會被 buffering 卡住。
> 前端仍保留初始 GET `/api/bff/dashboard/realtime` 載入第一份 snapshot；之後增量更新走 SSE，不再用 setInterval polling。

### Frontend Architecture (Vue 3)

```
src/
├── App.vue              # Root layout: sidebar navigation + router-view
├── main.js              # App bootstrap, plugin registration
├── router/index.js      # Route definitions (25 routes，含 4 條 redirect)
├── stores/assetStore.js # Pinia global state
├── api/index.js         # Axios instance, API methods
├── components/          # Reusable components (TaiwanMap, UsaMap)
└── views/               # Page-level components (24 views；含 StockMonitorView 內嵌的 WatchStockView / StockAlertView 兩個未掛路由的子 view)
```

**Service 層補充**
- `BackupService`: 透過 ProcessBuilder 呼叫 pg_dump / pg_restore / rclone，支援手動備份、列表（合併 manual/daily/weekly/monthly 四個資料夾）、還原（自動先建自救點）；內建 `@Scheduled` 自動排程：
  - `0 30 15 * * MON-FRI` Asia/Taipei：台股交易日 15:30（收盤後 2h）→ 上傳 `daily/asset_daily_tw_*.dump`
  - `0 0 7 * * TUE-SAT` Asia/Taipei：前一日為美股交易日時，台北 07:00（美東 16:00 收盤後 2h，涵蓋夏令／標準時）→ 上傳 `daily/asset_daily_us_*.dump`
  - `0 0 5 * * SUN` Asia/Taipei：每周日 05:00 → 上傳 `weekly/asset_weekly_*.dump`
  - 輪替策略：`daily/` 保留 50 份、`weekly/` 保留 5 份、`manual/` 保留 5 份（自救點不計入）
  - 輪替觸發點：(a) 每次排程／手動備份成功上傳後對該資料夾跑一次；(b) `updateSetting()` 儲存後對三個資料夾各跑一次（讓使用者調降保留代數時立即套用，不需等到下一次排程）。rotate 失敗以 `try/catch` 包住只記 log，不讓設定儲存 API 失敗
  - 交易日判定委派至 `MarketDataService.getTwHolidays(year)` / `getUsHolidays(year)`，並排除週末
- `WatchStockService`: 觀察清單 view 服務（不再對應實體表）。`findAll()` 由 `StockAlertRepository.findDistinctStockCodeMarket()` 取得去重 (stockCode, market) 清單後，整合 Redis live 報價（透過 `PriceQueryService`）、技術指標（`TechnicalIndicatorService.computeAll()` 回傳 `FullIndicators`：月線 MA20／季線 MA60／年線 MA240／K／D，填入 `WatchStockDto.Response` 的 `monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue`）與該股票最近一次 StockAlert 觸發資訊；`reorder(orderedStockKeys)` 拖曳重排時把每個股票所有 alert 的 `displayOrder` 整組依新順序重新指派；不提供 delete 入口（移除觀察一律由 `StockAlertService.delete` 在「警示條件」頁逐筆刪除）。**「警示」欄顯示窗**：最近觸發（時間／股價／月線／季線／年線／KD）與「警示條件」紅字只顯示落在 `StockAlertService.FRESHNESS_TRADING_DAYS`（= 2，最後交易日及前一日）內的觸發，cutoff 由 `recentTradingDayCutoff(market, 2)` 取 `stock_price_history` 最近 2 個 distinct `trading_date` 之較早一天午夜；與警示頁 `StockAlertService.toResponse` 共用同一常數確保兩頁口徑一致（同義欄位同一來源）。此 UI 顯示窗與 `stock_alert_trigger` 保留 30 天為兩個獨立概念。**「警示條件」欄的 MA% 觸發價**：`buildConditions()` 把已算好的 `FullIndicators` 一併傳給 `StockAlertService.buildLabel(alert, ind)`，`MA_*_PCT` 且 threshold≠0 的條件於 label 附換算後的觸發價（`對應均線 × (1 ± pct/100)`，如「高於季線 20%（360）」），重用同一份即時均線值、不另查（Task 146，詳 Requirement 16）
- `StockAlertService`: 到價警示 CRUD、條件評估、排序、最近觸發資訊回寫；`create` 對 `0000`（台股大盤）跳過 `stockMasterRepo.upsert`。`create` / `update` 在 `stockMasterRepo.upsert` 之前以 `assertNameMatchesCode(code, market, userName)` 守門：以 `historicalDataService.fetchTwStockName / fetchUsStockName` 取得外部 canonical name（權威來源 ext-materials-service `/internal/stock-name`），canonical 非空且與 user-supplied `stockName` 不一致時，**再對照 `stockMasterRepo.findByCodeAndMarket(code, market).name`（本地主檔亦視為合法 canonical）**；兩者皆不相符才 throw `IllegalArgumentException`（由 `GlobalExceptionHandler` 映射為 `400`）。canonical 空則 fall through（外部 API 異常時不阻擋）。`0000` + `台股` 跳過守門；`0000` + `美股` 直接拒絕。設計目的：阻止使用者把錯誤代號（如把 2500 標成「台積電」）寫入 `stock` 主檔導致觀察清單出現名稱對但完全無報價的列；同時避免「外部來源回應與本地主檔不一致」（例 Yahoo `shortName` 「NVIDIA Corporation」vs 主檔過去寫入的 「NVIDIA Corporation Common Stock」）造成 lookupName 自動帶名後 save 被自己擋下
- **交易日 / 國定假日判定（單一事實來源）**：`business-services` 側以 `MarketDataService.isTradingDay(market, date)`（dispatch 至 `isTwTradingDay/isUsTradingDay/isUkTradingDay`）與 `isMarketOpenNow(market)`（`MarketZones` 時段 + `isTradingDay` 假日）為唯一入口。`StockAlertService.evaluate`（live 觸發閘門）/ `computeTriggeredAt`（交易時段判斷）、`AlertNotificationDispatcher.withinSendWindow / lastTradingDate`、`StockPriceService.getMarketStatus`（→ `isXxxMarketOpen`）全部委派之，不再各自只判週末。`external-materials-service` 側對應為 `MarketCalendar` + `MarketClock`（抓價 / 收盤排程閘門）。台股假日權威 = TWSE holidaySchedule（business-services 經 `/internal/tw-holidays` proxy、ext-materials 直接 fetch，同一份）；美 / 英假日為 NYSE / LSE 法定規則純函式，因兩服務無共用 module 而各持一份（交叉註解鎖定，修改須同步）。Requirement 7 / 16 / 23
- `ExcelExportService`: Apache POI 產生快照與已實現損益的 .xlsx 匯出檔

**State Management (Pinia)**
- `assetStore`: 持有快照列表、當前快照、載入狀態
- 非同步 actions 直接呼叫 `api/index.js`

**Routing**
| Path | View | 說明 |
|------|------|------|
| `/` | → redirect | 導向 `/dashboard` |
| `/dashboard` | DashboardView | 資產總覽儀表板 |
| `/snapshots` | SnapshotListView | 快照列表 |
| `/snapshots/new` | SnapshotFormView | 新增快照（需定義在 `:id` 之前） |
| `/snapshots/:id` | SnapshotDetailView | 快照明細 |
| `/snapshots/:id/edit` | SnapshotFormView | 編輯快照 |
| `/history` | AssetHistoryView | 歷史趨勢 |
| `/realized-gains` | RealizedGainView | 已實現損益 |
| `/exchange-rate` | ExchangeRateView | 匯率走勢 |
| `/trading-calendar` | TradingCalendarView | 交易日曆 |
| `/gdp-twse` | GdpTwseView | 股市大盤查詢（指數日線／當日＋台韓人均 GDP；Requirement 18） |
| `/today-market-analysis` | TodayMarketAnalysisView | 今日股市分析（AI 判斷當日台股走向；Requirement 31） |
| `/asset-allocation-advice` | AssetAllocationAdviceView | 資產配置建議（依個人條件＋持有資產由 AI 給配置建議；Requirement 32） |
| `/settings/banks` | BankSettingsView | 銀行設定管理 |
| `/settings/brokers` | BrokerSettingsView | 券商設定管理 |
| `/settings/deposit-types` | DepositTypeSettingsView | 存款類型設定管理 |
| `/settings/market-types` | MarketTypeSettingsView | 市場類型設定管理 |
| `/settings/asset-classes` | AssetClassSettingsView | 資產類別／風格／債券期別歸類管理（Requirement 25–27） |
| `/settings/transit-fund-types` | TransitFundTypeSettingsView | 待轉入資金類型設定管理 |
| `/settings/funds` | FundSettingsView | 信託基金主檔設定管理（Requirement 19） |
| `/settings/backup-restore` | BackupRestoreView | 資料庫備份／還原 |
| `/settings/notifications` | NotificationSettingsView | 警示通知收件人設定（Requirement 23） |
| `/payment-accounts` | PaymentAccountSettingsView | 代繳帳戶記錄管理（Requirement 22）；舊路徑 `/settings/payment-accounts` 自動 redirect |
| `/stocks` | StockMonitorView | 股票觀察（含「觀察清單」、「警示條件」兩個頁籤；舊路徑 `/watch-stocks`、`/stock-alerts` 自動 redirect 並帶 `tab` query） |

## Data Model

### Entity Relationship Diagram

```
Bank        (1) ──── (N) BankDeposit
BrokerEntity(1) ──── (N) StockHolding

AssetSnapshot (1) ──── (N) BankDeposit
AssetSnapshot (1) ──── (N) StockHolding
AssetSnapshot (1) ──── (N) FundHolding
RealizedGain          (獨立，不關聯快照)

# 多租戶 / 認證（Requirement 28）
AppUser               (使用者主檔，PK = id；email UNIQUE；role ADMIN/USER；status PENDING/ACTIVE/DISABLED）
AppUser (1) ──── (N) AssetSnapshot          (owner_user_id；子表 bank/stock/fund holding 經 snapshot 繼承 owner)
AppUser (1) ──── (N) RealizedGain           (owner_user_id)
AppUser (1) ──── (N) PaymentAccount         (owner_user_id)
AppUser (1) ──── (N) StockAlert             (owner_user_id；trigger/recipient join、watch_stock 衍生皆繼承)
AppUser (1) ──── (N) NotificationRecipient  (owner_user_id)
# 受隔離表唯一性多租戶化：asset_snapshot UNIQUE(owner_user_id, snapshot_date)；
#                          notification_recipient UNIQUE(owner_user_id, email)
# 參考/行情/設定主檔（Bank, BrokerEntity, Stock, *_History, MarketType, AssetClass, FundMaster...）為全系統共用，不加 owner

# 主檔 / 設定類（不寫死 enum，由 DataInitializer seed）
Stock                 (個股主檔，PK = code + market；nullable override 欄 asset_class / stock_style / bond_term)
DepositTypeEntity     (存款類型主檔，code 值存入 BankDeposit.depositType)
MarketType            (市場類型主檔，code 值存入 StockHolding.market)
TransitFundType       (待轉入資金類型主檔)
AssetClass            (現金/債券/股票 三分類主檔，code 值存入 stock.asset_class)
StockStyle            (成長型/收益型 風格主檔，code 值存入 stock.stock_style)
BondTerm              (短/中/長期 債券期別主檔，code 值存入 stock.bond_term)
FundClassOverride     (基金分類人工指定，PK = fund_name；asset_class / stock_style / bond_term 三欄皆 nullable)
PaymentCategory(1) ── (N) PaymentAccount   (代繳記錄分類 + 記錄，Requirement 22)
NotificationRecipient (警示通知收件人，Requirement 23)
StockAlert (N) ──< stock_alert_recipient >── (N) NotificationRecipient  (每條警示挑選收件人；Task 125)

# 基金（Requirement 19–21）
FundMaster            (信託基金主檔，PK = fund_code)
FundNav               (基金 NAV 歷史)
FundDividendHistory   (基金配息歷史)

# 行情 / 歷史
StockPriceHistory     (歷史股價紀錄；live 行情改由 Redis 提供)
StockDividendHistory  (個股配息歷史，殖利率 / 填息天數計算用)
ExchangeRateHistory   (歷史匯率紀錄)

# 警示
StockAlert            (到價警示，獨立資料表；觀察清單由此表 GROUP BY (stockCode, market) 衍生)
StockAlertTrigger     (警示觸發歷史，FK→stock_alert，保留 30 天)

# 總經 / 指數（Requirement 18）
TaiwanGdpPerCapitaHistory / JapanGdpPerCapitaHistory / KoreaGdpPerCapitaHistory   (人均 GDP + 實質成長率)
TwseIndexDailyHistory / UsIndexDailyHistory            (大盤 / 海外指數每日 OHLC)

# 備份（Requirement 15）
BackupSetting         (備份保留代數設定，單列資料表，id = 1)
BackupRecord          (Google Drive 備份檔本地索引，UNIQUE(folder, filename))
```

> **Schema 基準線與 DB 層唯一鍵（重要澄清）：** 本專案的資料表基準線由 `db/init/01_dump.sql`（完整 `pg_dump` 快照，掛載進 `docker-entrypoint-initdb.d`）提供，**非** Liquibase 的 `v1.0.0-initial-schema` changeset；Liquibase 僅在此基準線之上做**增量**變更（dump 已含 `databasechangelog` 歷史，過往 changeset 視為 already-ran）。因此下列 Hibernate 早期建立、已固化進 dump 的 DB 層約束**不會出現在 Liquibase changelog**，但每個環境（運行中＋全新以 dump 初始化）皆已存在，`ddl-auto: none` 亦不會重建：
> - `stock_price_history`：`UNIQUE (stock_code, market, trading_date)`（Hibernate 名 `ukgoyp…`）＋ `INDEX idx_sph_code_date (stock_code, trading_date)`；OHLC 皆 `NUMERIC(20,4)`（`open/high/low` nullable、`close` NOT NULL，見 v1.14.0）、`volume BIGINT NOT NULL`。Entity `@Column` 註解已對齊此位數/nullable（Task 148）。
> - `exchange_rate_history`：`UNIQUE (currency, rate_date)`（Hibernate 名 `uk977p…`）＝ upsert 覆寫鍵；`buy_rate/sell_rate NUMERIC(10,4)`。
>
> 稽核提醒：只讀 Liquibase changelog 會誤判「DB 無唯一鍵、無去重保護」；實際 DB 已有上述約束（重複列數為 0），故**不需**再補 `ADD CONSTRAINT` changeset（會產生重複約束）。

### Core Entities

#### AssetSnapshot
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| snapshotDate | LocalDate | 快照日期（唯一） |
| usdExchangeRate | BigDecimal | 當日美元匯率（程式欄位名，原 spec 為 usdToTwd） |
| totalDeposit | BigDecimal | 總存款（台幣） |
| totalFundValue | BigDecimal | 總基金市值 |
| totalFundCost | BigDecimal | 總基金投入成本 |
| totalStockValue | BigDecimal | 總股票市值（含美股換算） |
| totalStockCost | BigDecimal | 總股票投入成本 |
| totalAssets | BigDecimal | 總資產 |
| estimatedAnnualDividend | BigDecimal | 預估年配息合計 |
| realizedGain | BigDecimal | 已實現損益合計（快取） |
| notes | String | 備註 |

#### Bank（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一，如 `fubon`） |
| displayName | String | 顯示名稱（如 `台北富邦銀行`） |
| keywords | String | Excel 匯入比對關鍵字，逗號分隔（如 `富邦,Fubon`） |
| active | Boolean | 是否啟用（軟刪除用） |

#### Broker（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一，如 `fubon`） |
| displayName | String | 顯示名稱（如 `富邦證券`） |
| keywords | String | Excel 匯入比對關鍵字，逗號分隔 |
| active | Boolean | 是否啟用（軟刪除用） |

#### DepositTypeEntity（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼，同時作為存入 BankDeposit.depositType 的值（如 `活存`） |
| displayName | String | 顯示名稱（如 `台幣活存`） |
| sortOrder | Integer | 顯示排序 |
| active | Boolean | 是否啟用（軟刪除用） |

#### MarketType（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼，同時作為存入 StockHolding.market 的值（如 `台股`、`美股`、`英股`） |
| displayName | String | 顯示名稱（如 `台灣股市`） |
| sortOrder | Integer | 顯示排序 |
| active | Boolean | 是否啟用（軟刪除用） |

> Seed 由 `DataInitializer.seedMarketTypes()` 提供 3 筆：`台股` / `美股` / `英股`（sortOrder 1/2/3）。`英股` 為 Requirement 24 加入，承載透過複委託投資的 LSE 掛牌 UCITS ETF（CSPX、VWRA 等）。

#### AssetClass（Requirement 25 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一，存入 `stock.asset_class` 與分類運算用）：`CASH` / `BOND` / `STOCK` |
| displayName | String | 顯示名稱：`現金` / `債券` / `股票` |
| sortOrder | Integer | 顯示排序（1/2/3） |
| active | Boolean | 是否啟用（軟刪除用） |

> Seed 由 `DataInitializer.seedAssetClasses()` 提供 3 筆。此表是「現金/債券/股票」三分類的單一事實來源（取代寫死 enum），供圓餅圖第 4 tab legend 與「資產類別歸類」設定頁下拉使用。`Stock` 主檔（`code`+`market` PK）新增 nullable 欄位 `asset_class`（值對應本表 `code`）：非空 = 人工指定、覆蓋規則；`null` = 依 `AssetClassifier` 規則自動判定。基金因 `fund_holding.fund_code` 多為 NULL、以「名稱」為穩定識別，故 override 改存 `fund_class_override(fund_name PK, asset_class, stock_style, bond_term — 三欄皆 nullable)` 表（任一欄非空即建列、全清則刪列；某欄有值 = 該維度人工指定、無值 = 依規則），與 `stock` 主檔的三個 nullable override 欄結構平行。標一次即跨所有快照生效，符合正規化（per 標的/名稱存一份）。設定頁的 securities 清單合併 `stock` 主檔（市場 `台股/美股/英股`）與 `fund_holding` 去重名稱（市場標記為 `基金`、`code` 欄即名稱）；`PUT /api/settings/securities/{asset-class|stock-style|bond-term}` 皆依 `market==基金` 分流 upsert/prune `fund_class_override` 對應欄，否則改 `stock` 對應欄。基金無 `dividendRate`，STOCK 基金風格自動為成長型（override 可改收益型）、BOND 基金期別自動依名稱（override 可改）。

#### StockStyle（Requirement 26 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一）：`GROWTH` / `INCOME`，存入 `stock.stock_style` |
| displayName | String | 顯示名稱：`成長型` / `收益型` |
| sortOrder | Integer | 顯示排序（1/2） |
| active | Boolean | 是否啟用 |
| dividendThreshold | BigDecimal | 殖利率門檻（DECIMAL(6,4)，nullable）：僅「收益型(INCOME)」列有值，預設 `0.0400`（= 4%）。`dividendRate >= 此值` → 收益型 |

> 「成長/收益」是套在 `asset_class = STOCK` 之上的**正交第二維度**，與三分類獨立並存（一檔股票同時有 `asset_class=STOCK` 與 `stock_style=GROWTH/INCOME`）。`Stock` 主檔新增 nullable 欄位 `stock_style`（值對應本表 `code`）作為逐檔 override。門檻可調故存於 INCOME 列（不寫死），由 `/api/settings/stock-styles` 管理。
>
#### BondTerm（Requirement 27 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一）：`SHORT` / `MID` / `LONG`，存入 `stock.bond_term` |
| displayName | String | 顯示名稱：`短期` / `中期` / `長期` |
| sortOrder | Integer | 顯示排序（1/2/3） |
| active | Boolean | 是否啟用 |

> 「短/中/長期」是套在 `asset_class = BOND` 之上的子維度。`Stock` 主檔新增 nullable 欄位 `bond_term`（值對應本表 `code`）作為逐檔 override；自動判定（`AssetClassifier.classifyBondTerm`）依標的名稱年期字樣（`20年`→長、`7-10`→中、`1-3`/`0-3 Month`→短、無資訊→中），override 優先。

> **呈現（不另開 tab）**：既有「現金/債券/股票」圓餅圖（第 4 tab）為**雙層 donut**——兩個 pie series 同 `center`、半徑相接（內層 `['36%','53%']`、外層 `['53%','70%']`，各佔一半、白邊 `borderWidth:2`）。
> - 內層（總覽，家族底色）= 現金 / 債券 / 股票。
> - 外層（細分，同色系深淺不同）= 台幣 / 美元 ｜ 短期 / 中期 / 長期 ｜ 成長型 / 收益型：`現金 → 台幣 + 美元`（藍系）、`債券 → 短 + 中 + 長`（teal 系）、`股票 → 成長型 + 收益型`（琥珀系）。
> - 因 `台幣+美元=現金`、`短+中+長=債券`、`成長+收益=股票`，外層與內層逐區角度對齊。資料皆來自 history（`totalTwdDeposit`/`totalUsdDeposit`/`cashValue`/`bondValue`/`stockValue`/`bondShortValue`/`bondMidValue`/`bondLongValue`/`growthValue`/`incomeValue`）。
> - **無 legend**；外圈 hover 列持股：`GET /api/snapshots/{id}/holdings-classified`（business-services，回逐持股 `{code,name,market,currentValue,assetClass,stockStyle,bondTerm}`，與 `getAssetHistory` 同一 classifier/override/門檻）→ BFF `GET /api/bff/dashboard/holdings-classified/{id}` passthrough。前端依 effectiveSnapshotId lazy 載入、群組成桶（成長型/收益型/短/中/長期），tooltip 列出該桶持股名稱與金額。

#### BankDeposit
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| snapshot | AssetSnapshot | FK |
| bank | Bank | FK（取代原 BankName enum） |
| depositType | String | 存款類型代碼（對應 DepositTypeEntity.code，不建立 FK 以保持靈活性） |
| originalAmount | BigDecimal | 原始幣別金額 |
| amount | BigDecimal | 台幣換算金額 |
| currency | String | 幣別（TWD/USD） |
| annualInterestRate | BigDecimal | 年利率（百分比，1.5 表示 1.5%；scale 4，最多 0.0001% 精度）；nullable，僅 TWD / USD 適用，TRANSIT_* 一律 null |
| notes | String | 備註（如「定存到期日」等標記） |

> **預估年利息為衍生值不存 DB**：`estimatedAnnualInterest = amount × annualInterestRate / 100`（amount 已是台幣等值，無論幣別都得到 TWD 結果），由 DTO / 前端即時計算後回傳。各筆加總後併入 `asset_snapshot.estimated_annual_dividend`（與股票、基金的預估配息一起）。

#### StockHolding
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| snapshot | AssetSnapshot | FK |
| stockCode | String | 股票代號 |
| stockName | String | 股票名稱 |
| market | String | 市場代碼（對應 MarketType.code，不建立 FK 以保持靈活性） |
| broker | BrokerEntity | FK（取代原 Broker enum） |
| shares | BigDecimal | 持股數量 |
| investmentCost | BigDecimal | 總投資成本（= shares × 成本均價）。**幣別依 currency**：台股 / 美股 TWD 計價 = 台幣；美股 USD 計價 = 美元。彙總台幣須經 transactionExchangeRate（無則快照匯率）換算 |
| currentValue | BigDecimal | 當前市值 |
| dividendRate | BigDecimal | 現金股利率 |
| estimatedDividend | BigDecimal | 預估年配息 |
| currency | String | 幣別（TWD/USD） |
| originalCurrencyValue | BigDecimal | 原幣現值（美股） |
| transactionType | String | 交易類型（買 / 賣） |
| transactionDate | LocalDate | 交易日期 |
| transactionExchangeRate | BigDecimal | 交易日當日匯率（美股，precision 10,4） |
| displayOrder | Integer | 持倉顯示排序（拖曳排序用） |

#### FundHolding
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| snapshot | AssetSnapshot | FK |
| fundName | String | 基金名稱 |
| fundCode | String | 基金代號（選填） |
| bank | Bank | FK 至銷售銀行（選填） |
| investmentAmount | BigDecimal | 投入成本（程式欄位名，原 spec 為 investedAmount） |
| currentValue | BigDecimal | 當前市值（snapshot 凍結值；若 units 非空，由系統 `units × NAV × FX` 算出寫入；否則使用者手填） |
| units | BigDecimal | 總單位數（Requirement 19 新增；nullable 向後相容；非空時觸發自動計算 currentValue） |
| estimatedDividend | BigDecimal | 預估年配息台幣 (Requirement 20)；snapshot 凍結值；`units × 近 12 月每單位配息加總 × FX` 自動算 |

#### FundDividendHistory（Requirement 20 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| fundCode | String | FK 至 `fund_master.fund_code` |
| baseDate | LocalDate | 配息基準日（FundClear `asiBaseDate`） |
| amount | BigDecimal | 每單位原幣配息金額（scale 6） |
| currency | String | 計價幣別（複用 fund_master.currency） |
| frequency | String | 配息頻率描述（每月 / 每季 / …） |
| fetchedAt | Instant | 抓取時間 |

唯一鍵：`(fund_code, base_date)`。external-materials-service 每日 cron 寫入；`FundDividendService` 取近 12 個月 amount 加總算年估值。

#### FundMaster（Requirement 19 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| fundCode | String | PK，銀行內代號（華南 4 碼如 `02A8`，元大用 FundClear class code 如 `93100953A`） |
| fundName | String | 基金中文名稱 |
| currency | String | 計價幣別（USD / ZAR / TWD / EUR…）；TWD 表示無需 FX 換算 |
| bank | Bank | FK，銷售銀行 |
| site | String | `offshore` 或 `onshore`，決定走 FundClear 哪組 API |
| fundclearOrgCode | String | FundClear 機構代碼（offshore 為 3 碼如 `043`；onshore 為 `Annnn` 如 `A0005`） |
| fundclearFundCode | String | FundClear 基金代碼（offshore 為 10 碼 `A003800030`；onshore 為 8 碼 `93100953`） |
| fundclearClassCode | String | FundClear 級別代碼（offshore 多為 ISIN 如 `LU0937949237`，少數投信內部 code 如 `GSBAMU`、`ABGHYATUSD`；onshore 為 `93100953A`） |
| active | Boolean | 是否啟用（`fund_master` 抓 cron 只抓啟用中） |

> Offshore / onshore DTO 欄位名不同：offshore 用 `organizeCode` / `fundCode` / `fundClassCode`，onshore 用 `orgId` / `fundNo` / `fundClassCode`。`FundNavFetchClient` 需依 `site` 分流。

> `fund_master` 由「信託基金設定」頁面（`/settings/funds`）管理，CRUD 經 `FundNavController` 暴露 `POST/PUT/PATCH /api/funds*`；DataInitializer 僅在 fund_code 不存在時 seed 預設 7 筆，不覆蓋使用者編輯。

> 設計理由：基金本身屬性（幣別、所屬銷售銀行）與「某次 snapshot 的持有狀態」分離，避免 `FundHolding` 跨筆冗餘儲存同一事實。`fund_holding.fund_code` 形成弱 FK 至 `fund_master.fund_code`，但不加 DB 級 FK 以避免破壞既有歷史資料（舊 FundHolding 的 fundCode 可能不在主檔內）。

#### FundNav（Requirement 19 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| fundCode | String | FK 至 `fund_master.fund_code` |
| navDate | LocalDate | 淨值日 |
| nav | BigDecimal | 最新淨值（原幣計價，scale 6） |
| source | String | 抓取來源（`FUNDCLEAR` / `MONEYDJ`） |
| fetchedAt | Instant | 抓取時間 |

唯一鍵：`(fund_code, nav_date)`。external-materials-service 每日 cron 寫入；business-services 讀取最新一筆。

#### RealizedGain
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| assetCode | String | 資產代號（原 spec 為 stockCode） |
| assetName | String | 資產名稱（原 spec 為 stockName） |
| market | String | 市場代碼（對應 MarketType.code） |
| currency | String | 幣別（TWD/USD） |
| broker | String | 券商名稱 |
| tradeDate | LocalDate | 交易日期 |
| year | Integer | 年度（@Transient，由 tradeDate.getYear() 計算；v1.9.4 起移除實體欄位 trade_year） |
| shares | BigDecimal | 交易股數 |
| salePrice | BigDecimal | 賣出均價（原幣） |
| proceeds | BigDecimal | 收帳金額（原幣） |
| investmentCost | BigDecimal | 投資成本（原幣） |
| exchangeRate | BigDecimal | 交易時匯率 |

> 正規化：`profit` 與 `profitRate` 為衍生值（`proceeds - investmentCost` 與其除以 `investmentCost`），不入庫，於 DTO 層即時計算後回傳。v1.9.3 起移除實體欄位。
>
> v1.9.4 進一步移除冗餘 / 衍生欄位：
> - `stock_holding.stock_name` / `stock_alert.stock_name` / `watch_stock.stock_name`：與 `stock` 主檔重複，DROP，DTO 由 `StockRepository.findByCodeAndMarket()` join 補上。
> - `exchange_rate_history.mid_rate`：可由 `(buyRate + sellRate) / 2` 即時計算，改為 `@Transient`。
> - `realized_gain.trade_year`：可由 `YEAR(tradeDate)` 即時計算，改為 `@Transient`，相關 query 改以日期區間替代。

#### StockDividendHistory（個股配息歷史）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| stockCode | String | 股票代號 |
| market | String | 市場代碼 |
| year | Integer | 配息年度 |
| cashDividend | BigDecimal | 現金股利 |
| stockDividend | BigDecimal | 股票股利 |
| exDividendDate | LocalDate | 除息日 |
| yieldPct | BigDecimal | 當年殖利率 |
| cashPaymentDate | LocalDate | 現金股利發放日 |
| stockPaymentDate | LocalDate | 股票股利發放日 |
| fillDays | Integer | 填息天數 |
| previousClose | BigDecimal | 除息前一日收盤（殖利率 / 填息計算基準） |
| source | String | 資料來源（如 FinMind / NASDAQ） |
| updatedAt | LocalDateTime | 最近一次更新時間 |

> 資料來源與抓取流程見 `#### 股利歷史資料來源（stock_dividend_history）`（本文件後段）。供 StockAnalysisDialog 殖利率 / 填息天數圖與配息率計算使用。

#### Live 行情（Redis）

`StockPrice` Entity 與 `stock_price` 表已廢除，盤中即時行情改存 Redis（schema 見上方 External Materials Service Architecture）。`PriceQueryService` 從 Redis 取值並組裝成原 `StockPriceDto` 形狀，對 BFF / 前端介面不變。

#### WatchStock（已廢止）

`watch_stock` 表已廢止（v1.x 重構：「觀察清單由 stock_alert 衍生」）。觀察清單一律由 `StockAlert` 群組去重產生，不再有獨立的觀察 entity。被併入 `external-materials-service` 排程的股票範圍 = 持股 + `stock_alert.stockCode` distinct（含 `0000` 大盤特例不送排程，由 TWSE 日線 cron 寫入 `twse_index_daily_history`）。

#### StockAlert（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| stockCode | String | 股票代號 |
| market | String | 市場代碼（台股/美股；股名由 `stock` 主檔 join 補上，v1.9.4 起不存冗餘 `stock_name`） |
| alertType | String | 條件類型：`PRICE_ABOVE` / `PRICE_BELOW` / `MA_ABOVE_PCT` / `MA_BELOW_PCT` / `KD_ABOVE` / `KD_BELOW` / `KD_D_ABOVE` / `KD_D_BELOW` |
| maPeriod | Integer | 均線天數（僅 `MA_*_PCT` 類型使用，目前前端下拉提供 20 / 60 / 240；其他類型為 null。欄位本身為任意整數，新增天數不需 migration，見下方「均線通用化」） |
| threshold | BigDecimal | 條件門檻：價位類為價格，均線類為百分比偏離，KD 類為 0–100 門檻 |
| active | Boolean | 是否啟用 |
| displayOrder | Integer | 拖曳排序 |
| lastTriggeredAt | LocalDateTime | 最近一次觸發時間 |
| lastTriggeredPrice | BigDecimal | 觸發時股價 |
| lastTriggeredMaValue | BigDecimal | 觸發時均線值（對應 `maPeriod` 的 MA；MA 條件才有） |
| lastTriggeredKdValue | BigDecimal | 觸發時 K 值（KD 條件才有） |
| lastTriggeredDValue | BigDecimal | 觸發時 D 值（KD 條件才有；v1.7.5 新增 `last_triggered_d_value`） |
| createdAt | LocalDateTime | 建立時間（不可更新） |
| updatedAt | LocalDateTime | 最近一次更新時間（`@PreUpdate` 自動維護） |

> **均線通用化**：原本以 `QUARTERLY_MA_*` / `ANNUAL_MA_*` 兩組字串表達兩種均線，改為通用的 `MA_ABOVE_PCT` / `MA_BELOW_PCT` + `ma_period` 數字欄位。未來新增任何天數的均線警示（5、10、20、120…）皆不需新增 enum-like 字串，前端下拉只增加 `maPeriod` 選項即可。Liquibase 遷移 `v1.24.0` 將舊資料一次轉換（QUARTERLY → 60、ANNUAL → 240）。

#### StockAlertTrigger（觸發歷史，新增）

每次警示條件成立時，除了覆寫 `StockAlert.last_triggered_*` 欄位外，另寫一筆 `stock_alert_trigger` 紀錄，保留近 30 天供事後追蹤。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| alertId | Long | FK → `stock_alert.id`（CASCADE on delete） |
| stockCode | String | 觸發當下的股票代號（denorm 快取，避免 JOIN） |
| market | String | 市場 |
| triggeredAt | LocalDateTime | 觸發時間（同 `StockAlert.lastTriggeredAt`） |
| price | BigDecimal | 觸發當下股價 |
| monthlyMa | BigDecimal | 月線 MA20 |
| quarterlyMa | BigDecimal | 季線 MA60 |
| annualMa | BigDecimal | 年線 MA240 |
| kValue | BigDecimal | K 值（KD9） |
| dValue | BigDecimal | D 值（KD9） |
| createdAt | LocalDateTime | row 寫入時間（用於 30 天輪替） |

> **保留策略**：每日排程刪除 `created_at < NOW() - 30 days` 的紀錄（`StockAlertService.cleanupOldTriggers` @Scheduled cron `0 0 4 * * *` Asia/Taipei）。
> **資料填寫**：5 個技術指標皆無條件計算寫入（不論觸發類型是 PRICE / MA / KD），便於事後分析「觸發當下整個技術面狀態」。透過 `TechnicalIndicatorService.computeAll()` 一次取得 MA20 / MA60 / MA240 / K / D。


#### TransitFundType（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一） |
| displayName | String | 顯示名稱 |
| payable | Boolean | 是否為「待付」（true＝待付/金額為負，false＝待收/金額為正）；`NOT NULL`，預設 true。驅動 TRANSIT_* 金額正負號（見上「存款 amount 換算規則」） |
| sortOrder | Integer | 顯示排序 |
| active | Boolean | 是否啟用（軟刪除用） |

> **設計決策（零遷移策略）：** `Bank`/`Broker`/`DepositType`/`MarketType` 全部改為資料庫 Entity，不使用任何 Enum。原 `@Enumerated(EnumType.STRING)` 欄位已以 VARCHAR 儲存 Enum 名稱，改為 `String` 欄位時無需資料庫 Migration，現有資料值（如 `"台股"`、`"活存"`）完全相容。`DepositTypeEntity.code` 與 `MarketType.code` 即為寫入欄位的值，與歷史資料對應。

#### PaymentCategory（Requirement 22 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一，如 `bill` / `tax` / `service`） |
| displayName | String | 顯示名稱（如 `繳費` / `繳稅` / `服務`） |
| sortOrder | Integer | 顯示排序 |
| active | Boolean | 是否啟用（停用後不出現於新增 dialog 下拉選單，但舊記錄仍顯示分類） |

#### PaymentAccount（Requirement 22 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| category | PaymentCategory | FK |
| itemName | String | 項目名稱（自由輸入，如 `市話 + MOD`、`台電電費`） |
| paymentAccount | String | 扣款帳戶（自由輸入字串，可填銀行帳戶或信用卡名稱；**不**與 `bank` / `broker` 建立 FK） |
| note | String | 備註（自由輸入，可放用戶號碼、電號、水號等） |
| sortOrder | Integer | 顯示排序 |

> **設計理由（不關聯資產表）：** 代繳記錄屬於「個人資料記錄簿」，目的是替代手工 Excel/便利貼，不參與任何資產計算。`paymentAccount` 刻意採純字串而非 FK：使用者可能輸入「momo 信用卡」這類不在 `bank` 表中的卡別，硬綁 FK 反而綁手綁腳；停用銀行也不應影響舊代繳紀錄的可讀性。

#### NotificationRecipient（Requirement 23 新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| email | String | 收件人 email；存入前 trim + 轉小寫；unique |
| active | Boolean | 是否啟用（false 不寄信，但保留設定） |
| createdAt | LocalDateTime | 建立時間 |
| updatedAt | LocalDateTime | 最近一次更新時間 |

> **設計理由（獨立表 + 不寫死 enum）：** 收件人屬「使用者可變設定」，未來可能多人接收（家人 / 副信箱），需 DB 持久化並透過設定頁維護，遵循專案「禁止 enum 寫死」原則。目前僅支援 email 通道，未來如要擴充 LINE / Telegram 再新增 `notification_channel` 表，不為假設需求預留欄位。

#### StockAlertRecipient（Requirement 23 / Task 125 新增；警示 ↔ 收件人多對多 join）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| alertId | Long | FK → `stock_alert.id`（不映射 JPA 關聯，沿用本專案「以 Long id 顯式關聯」慣例，同 `StockAlertTrigger.alertId`） |
| recipientId | Long | FK → `notification_recipient.id` |

> **設計理由（join 表而非冗餘欄位）：** 每條警示要能各自挑選「寄給哪幾位收件人」，是典型多對多，遵循正規化原則以 join 表表達，不在 `stock_alert` 存逗號分隔 email（會與 `notification_recipient` 重複儲存同一事實、且 email 改名需多表同步）。`(alert_id, recipient_id)` 組合 unique。dispatcher 寄信時以 `alert_id` 查出 `recipient_id`，再交集 `notification_recipient.active=true` 得實際收件 email。升級時 Liquibase 把現有警示 × 現有收件人全配對回填，維持升級前「全部都收」行為。刪除警示 / 刪除收件人時連帶清除對應 join 列。

#### BackupSetting（Requirement 15）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK，固定為 1（單列資料表） |
| manualRetention | Integer | 手動備份保留份數 |
| dailyRetention | Integer | 每日備份保留份數 |
| weeklyRetention | Integer | 每週備份保留份數 |
| backupEnabled | Boolean | 排程備份總開關 |

#### BackupRecord（Requirement 15）
Google Drive 上每一份備份檔的本地索引；UI 列表 / 還原選單一律從本表讀，避免每次都連 rclone。真正的備份檔仍存於 Google Drive，本表只是 metadata 快取。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| folder | String | `manual` / `daily` / `weekly` / `monthly` |
| filename | String | 備份檔名（與 `folder` 組成 UNIQUE） |
| sizeBytes | Long | 檔案大小 |
| modifiedAt | LocalDateTime | 備份完成時間（= rclone ModTime，轉 Asia/Taipei） |
| autoPreRestore | Boolean | 是否為還原前自動自救點（不計入 5 份輪替） |
| createdAt | LocalDateTime | row 寫入時間 |

## API Design

### Base URL
- Development: `http://localhost:8080/api`
- Production: `http://localhost:8081/api` (via Nginx proxy `/api`)

### Endpoints

#### Asset Snapshots
```
GET    /api/snapshots                              # 列出所有快照（含摘要）
POST   /api/snapshots                              # 建立新快照
GET    /api/snapshots/{id}                         # 取得快照明細
PUT    /api/snapshots/{id}                         # 更新快照
DELETE /api/snapshots/{id}                         # 刪除快照
GET    /api/snapshots/history                      # 資產歷史趨勢
GET    /api/snapshots/{id}/holdings-classified      # 該快照持股依現金/債券/股票三分類（雙層 donut 用，Requirement 25）
GET    /api/snapshots/export                       # Excel 批次匯出
PATCH  /api/snapshots/{id}/dividend-rates          # 回寫指定快照配息率
PATCH  /api/snapshots/{id}/stock-order             # 更新持倉顯示排序
POST   /api/snapshots/enrich-all-dividend-rates    # 批次補齊所有快照缺漏配息率
POST   /api/snapshots/recalc-dividends             # 重算所有快照預估配息（依現值×配息率）
POST   /api/snapshots/recalc-totals                # 重算所有快照彙總（修正美股 USD 成本未換匯造成的 total_stock_cost 偏差）

# Excel 批次匯入端點（POST /api/snapshots/import）已停用
```

#### Realized Gains
```
GET    /api/realized-gains                       # 列出（依年度彙總，含明細）
POST   /api/realized-gains                       # 新增
PUT    /api/realized-gains/{id}                  # 更新
DELETE /api/realized-gains/{id}                  # 刪除
POST   /api/realized-gains/fix-currency-to-twd   # 資料修正工具：將舊紀錄缺漏 currency 欄補為 TWD
GET    /api/realized-gains/export                # Excel 匯出（單獨損益 sheet）

# Excel 批次匯入端點（POST /api/realized-gains/import）已停用
```

#### Funds / Fund NAV / Fund Dividend（信託基金，Requirement 19–21）
```
GET    /api/funds                                # 列出全部 fund_master（含 inactive）；?date=YYYY-MM-DD 時 NAV/FX/配息估算改用該基準日（Req 21）
POST   /api/funds                                # 新增基金主檔
PUT    /api/funds/{fundCode}                     # 更新基金主檔
PATCH  /api/funds/{fundCode}/active              # 啟用/停用基金
GET    /api/fund-nav/latest?fundCode=            # 取單檔最新 NAV
POST   /api/fund-nav/refresh                     # 立即刷新所有基金 NAV（proxy 至 external-materials-service），回 { success, failed, total }
POST   /api/fund-nav/backfill?years=10           # 基金 NAV 歷史回補（Req 21，proxy 至 external-materials-service）
POST   /api/fund-dividend/refresh                # 同步全抓基金配息歷史（Req 20）
POST   /api/fund-dividend/backfill?years=10      # 基金配息歷史回補（Req 21）
```
> 由 `FundNavController`（`@RequestMapping("/api")`）提供。前端：FundSettingsView 透過 `FundBffRoutes` passthrough；SnapshotFormView 另經自己的 `SnapshotFormBffController`（`/api/bff/snapshot-form/funds`、`/api/bff/snapshot-form/fund-nav/refresh`）讀同一份基金主檔。

#### Market Data
```
GET    /api/market-data/dividend-rate?code=0050&market=台股    # 取得股利率
GET    /api/market-data/prices                                 # 列出所有快取股價（live 即時股價統一走此端點，無單數 /price）
GET    /api/market-data/prices/stream                          # SSE 即時推價（text/event-stream，取代輪詢）
GET    /api/market-data/intraday-ticks?code=&market=&date=     # 走勢圖「當日」分時 tick（proxy 至 external-materials Redis LIST；date 省略時預設今天（該市場時區）若為交易日且今日 tick 已有資料，否則退回最近有收盤的交易日 — 見 Task 153）。回傳恆為「真實成交 tick 原始序列（升冪、不含未來 padding）」；前端 `StockAnalysisDialog.vue`「當日」模式再以該市場交易時段（鏡射 MarketZones）建「開盤→收盤」每分鐘網格對齊、未來留 null、股價線 connectNulls，使 X 軸延伸到收盤（與指數當日圖 Task 96 同視覺行為，Task 157）
POST   /api/market-data/prices/refresh                         # 刷新所有持股現價
GET    /api/market-data/market-status                          # 開盤狀態（台股/美股）
GET    /api/market-data/holidays?year=2026                     # 台股與美股假日清單
POST   /api/market-data/history/backfill                       # 補齊所有歷史股價
POST   /api/market-data/history/backfill-stock?code=&market=&since=&until=  # 補齊單支股票歷史股價
GET    /api/market-data/history/stock?code=&market=            # 查詢單支股票歷史股價
POST   /api/market-data/history/prices-on-date?date=           # 批次查詢指定日期各股收盤價
GET    /api/market-data/exchange-rate?currency=USD&start=&end= # 匯率歷史（省略區間則回傳全部）
GET    /api/market-data/exchange-rate/latest?currency=USD      # 最新匯率
GET    /api/market-data/exchange-rate/on-date?currency=USD&date= # 指定日期最近匯率（新增快照自動帶入）
POST   /api/market-data/exchange-rate/refresh?currency=USD     # 刷新最新匯率
POST   /api/market-data/exchange-rate/backfill-history?currency=USD&since= # 補齊指定日期起歷史匯率
GET    /api/market-data/live-assets                            # 以最新快照持倉 × 當前快取股價，即時計算總資產估值
GET    /api/market-data/etf-holdings?code=0050&market=台股     # ETF 成分持股（台股優先 MoneyDJ 完整成分股→Yahoo前10 fallback；FinMind dataset 已移除；含 12h cache；美股走 Yahoo）
GET    /api/market-data/dividends?code=0050&market=台股&years=10 # 最近 N 年股利（台股 FinMind；美股 NASDAQ）
```

##### ETF 判斷規則
- 台股：`stockCode` 以 `00` 開頭
- 美股：白名單（VOO、VT、AVGO、VGT、QQQ、SPY … 可於 constant 維護）

#### BFF Aggregation
```
GET    /api/bff/dashboard/summary                  # 並行聚合儀表板所需資料（snapshots + history + prices + market-status + live-assets）
GET    /api/bff/dashboard/realtime                  # 2 分鐘輪詢用：最新 prices + market-status + live-assets，不重抓 summary
```

> BFF Enrichment：`latestSnapshotDetail.stocks[]` 由 BFF 補上 `investmentCostOriginal`（美股 USD、台股 TWD），legacy 美股 `currency='TWD'` 記錄會用 `transactionExchangeRate` 換回 USD，前端買入均價直接使用此欄位以避免各頁面重複正規化。`SnapshotEnricher.buildMergedStocks` 進一步算出 `avgCostOriginal = investmentCostOriginal ÷ shares`（4 位小數）。**凡顯示「買入均價／成本均價」一律使用此 `avgCostOriginal`**：Dashboard 表格欄位、以及從 Dashboard 列雙擊開啟的 `StockAnalysisDialog` 走勢圖「成本均價」水平參考線都讀同一欄位（同義同源）。禁止前端用「台幣 `investmentCost` ÷ 今日即時匯率 `usdExchangeRate`」反推買入均價 —— 今日匯率每日浮動，會與表格鎖定交易匯率的買入均價對不上。

> 儀表板「股票持股」股價顯示規則（與「觀察股票」共用同一 Redis live cache，由 `external-materials-service` 兩支獨立 cron 各自每 2 分鐘更新）：
> - 規則 **per-market 判斷**：對每一檔股票，依其市場各自決定是否顯示即時價。
>   - **台股**：`basedate == LocalDate.now(Asia/Taipei)` → 顯示 `stockPrices` 即時價＋漲跌%（盤中由 cron 每 2 分鐘更新）。
>   - **美股**：`basedate == LocalDate.now(America/New_York)` → 顯示 `stockPrices` 即時價＋漲跌%。EST/EDT 由 JVM `ZoneId` 自動處理。
>   - 為什麼分市場：使用者通常在 TW 收盤後建快照（`basedate = TW 那天`），但美股 session 跨午夜（TW 21:30 → 隔日 05:00），TW 已過午夜後 `basedate（昨天）== TW 今日（今天）` 會誤判；改用 `basedate == 美東今日` 才能正確涵蓋 TW 凌晨對應的美股盤中。
> - 否則（basedate 為過去日期、或該市場非當日 = frozen）顯示快照保存的當日 `stockPrice`（即 `latestSnapshotDetail.stocks[].stockPrice`），**並一併顯示該收盤日的「當日漲跌」**（該收盤日收盤 vs 前一交易日收盤）；不參與 polling 切換。當日漲跌由 business-services `prices-on-date` 回傳（每檔以 `findClosestPrice(date)` 取收盤 `h`、再以 `findClosestPrice(h.tradingDate − 1)` 取前一交易日收盤算差），BFF `SnapshotEnricher.fetchSnapshotCloseData` 一次 HTTP 取回 `closeMap + changeMap`，`mergePerMarketPrices` 將 changeMap 併入 frozen entry 的 `priceChange`／`changePercent`。無前一交易日資料時留 null（前端只顯示收盤價）。
> - **per-market 判斷一律由 BFF 完成**（`SnapshotEnricher.mergePerMarketPrices`），前端禁止重做 basedate / 市場開盤判斷。live vs frozen 由 `basedate == 該市場當地今日`（前端 `shouldApplyLive` / BFF `isCurrentBasedate`）決定，**不再以 `priceChange` 是否為 null 判定**（frozen entry 現在也帶當日漲跌）。前端 `getPriceCell()`：`getRealtimePrice()`（受 `shouldApplyLive` 閘門）有值 → live；否則取 `row.stockPrice` + `stockPrices[key]` 的 frozen 漲跌，且僅在 `stockPrices.tradingDate == 該列 snapshotDate`（＝所選快照為最新快照，map 才對應）時採用漲跌，選歷史快照則只顯示收盤價。
> - **漲跌配色（台股慣例）**：上漲紅 `#dc2626`、下跌綠 `#16a34a`、平盤/無資料灰 `#94a3b8`（前端 `changeColor`／`changeArrow`），live 與 frozen 兩情形一致，與走勢圖 markPoint「紅漲綠跌」同慣例；與損益 / KPI 卡「綠獲利、紅虧損」為不同語意，刻意不統一。
>
> KPI「資產總計」一致性（**per-market 基準日閘門**，見 Task 121）：Dashboard `liveLatest` 對台股 / 美股 / 英股各自判斷 `basedate == 該市場當地今日`（`isBaselineToday`），今日市場用 `customTableData × overlayLivePrice` 的 live 值、非今日市場維持快照凍結收盤；`totalAssets` **僅在三市場皆為今日時**才採用 `liveAssets.liveTotalAssets`（全 live 口徑，與「歷年資產管理」今日列共用同一支 business-service API、同值），否則一律 per-market 加總（`totalDeposit + totalFundValue + per-market totalStockValue`）。**禁止前端再加「市場開盤」閘門**；是否套 live 純由 `basedate == 該市場今日` 決定。
>
> **「全市場皆非交易日今日」（昨日快照／今日尚未建檔／週末／刪除今日快照後最新退回昨日）時，`liveLatest` 一律維持快照凍結收盤值（= 基準日收盤），不得用 `liveAssets` 覆蓋**。理由：`live-assets` 讀 Redis = 最新一筆 tick / 收盤，當最新快照日 < 今日且已有更新交易日收盤時，覆蓋會把較新交易日收盤洩漏到一個過去基準日上（Task 121 修正前 bug：最新快照 6/23、刪掉 6/24 後 6/23 列被 6/24 收盤蓋掉）。快照凍結的 `stock_holding.currentValue` 對已定案的過去日期本來就 == 該日收盤，保留即正解。`DashboardBffController.summary` 回的 `history` 最新列亦透過共用 `LiveAssetsOverlay.applyToLatest`（同一 per-market 閘門）套 overlay，與 `/api/bff/asset-history` 最新列同值。（前一版設計曾要求此分支改走 `overlayLatestFromLiveAssets(s)` 一律覆蓋，導致過去基準日被較新收盤汙染 → Task 121 反轉為 per-market 閘門，並移除 `overlayLatestFromLiveAssets`）
>
> 儀表板基準日切換行為：
> - 2 分鐘輪詢 `refreshPricesAndStatus()` 只刷新 `stockPrices` / `marketStatus` / `liveAssets`（呼叫 `bffApi.dashboard.realtime()`），**不得重抓 dashboard summary** 以免覆蓋使用者選擇的快照。
> - 初次載入才呼叫 `bffApi.getDashboardSummary()` 並把 `selectedSnapshotId` 設為最新；之後使用者透過快照選擇器切換時，僅 `store.fetchSnapshotDetail(id)` 取得明細。
> - 「資產歷史趨勢」圖與 KPI 卡「較上次」皆以 `snapshotDate <= 基準日` 過濾後計算。

#### Settings - Banks
```
GET    /api/settings/banks                      # 列出所有銀行（含停用）
POST   /api/settings/banks                      # 新增銀行
PUT    /api/settings/banks/{id}                 # 更新銀行資訊
PATCH  /api/settings/banks/{id}/active          # 啟用/停用銀行
```

#### Settings - Brokers
```
GET    /api/settings/brokers                    # 列出所有券商（含停用）
POST   /api/settings/brokers                    # 新增券商
PUT    /api/settings/brokers/{id}               # 更新券商資訊
PATCH  /api/settings/brokers/{id}/active        # 啟用/停用券商
```

#### Settings - Deposit Types（新增）
```
GET    /api/settings/deposit-types              # 列出所有存款類型（含停用）
POST   /api/settings/deposit-types             # 新增存款類型
PUT    /api/settings/deposit-types/{id}        # 更新存款類型
PATCH  /api/settings/deposit-types/{id}/active # 啟用/停用存款類型
```

#### Watch Stocks（v1.22 起為 stock_alert 衍生 view，無實體表）
```
GET    /api/watch-stocks                       # 列出所有觀察股票（去重後含最新報價、警示彙總）
PUT    /api/watch-stocks/order                 # body: [{stockCode, market}] 陣列；把每個股票所有 alert 的 displayOrder 整組重排
POST   /api/watch-stocks/resend-digest?market= # 補發：指定市場（台股/美股/英股）最後交易日觸發事件彙整為單封 digest email 重寄；market 省略則全市場（Requirement 23、Task 128）
GET    /api/watch-stocks/chart.png             # 警示 email 內嵌的股票分析走勢圖 PNG（XChart server-side 渲染）
```
新增 / 移除觀察一律透過 `/api/stock-alerts` 操作對應 alert：建立第一筆 alert 即出現於觀察清單，刪除最後一筆 alert 即從觀察清單消失。

#### Settings - Market Types（新增）
```
GET    /api/settings/market-types               # 列出所有市場類型（含停用）
POST   /api/settings/market-types              # 新增市場類型
PUT    /api/settings/market-types/{id}         # 更新市場類型
PATCH  /api/settings/market-types/{id}/active  # 啟用/停用市場類型
```

#### Settings - Transit Fund Types（新增）
```
GET    /api/settings/transit-fund-types               # 列出所有待轉入資金類型（含停用）
GET    /api/settings/transit-fund-types/active        # 僅列出啟用中
POST   /api/settings/transit-fund-types              # 新增
PUT    /api/settings/transit-fund-types/{id}         # 更新
PATCH  /api/settings/transit-fund-types/{id}/active  # 啟用/停用
```

#### Settings - Asset Classes / Securities（Requirement 25 新增）
```
GET    /api/settings/asset-classes               # 列出三分類（現金/債券/股票，含停用）
POST   /api/settings/asset-classes               # 新增資產類別
PUT    /api/settings/asset-classes/{id}          # 更新資產類別
PATCH  /api/settings/asset-classes/{id}/active   # 啟用/停用

GET    /api/settings/securities                  # 列出 stock 主檔 + fund_holding 去重名稱（市場=基金，code=名稱）每檔的 {code, market, name,
                                                 #   assetClass(override), effectiveAssetClass, source,
                                                 #   stockStyle(override), effectiveStockStyle, styleSource, ...}
PUT    /api/settings/securities/asset-class      # body {code, market, assetClass|null}：market==基金 upsert/刪 fund_class_override（key=名稱），否則改 stock.asset_class
PUT    /api/settings/securities/stock-style      # body {code, market, stockStyle|null}：market==基金 upsert/prune fund_class_override.stock_style，否則改 stock.stock_style
PUT    /api/settings/securities/bond-term        # body {code, market, bondTerm|null}：market==基金 upsert/prune fund_class_override.bond_term，否則改 stock.bond_term

GET    /api/settings/stock-styles                # 列出風格（成長/收益，含 dividendThreshold）
POST   /api/settings/stock-styles                # 新增
PUT    /api/settings/stock-styles/{id}           # 更新（含調整收益型門檻 dividendThreshold）
PATCH  /api/settings/stock-styles/{id}/active    # 啟用/停用

GET    /api/settings/bond-terms                  # 列出債券期別（短/中/長期）
POST   /api/settings/bond-terms                  # 新增
PUT    /api/settings/bond-terms/{id}             # 更新
PATCH  /api/settings/bond-terms/{id}/active      # 啟用/停用
```
> 前端「資產類別歸類」頁透過 BFF `AssetClassSettingsBffRoutes` passthrough：
> `/api/bff/asset-class-settings/categories/**` → `/api/settings/asset-classes/**`、
> `/api/bff/asset-class-settings/stock-styles/**` → `/api/settings/stock-styles/**`、
> `/api/bff/asset-class-settings/bond-terms/**` → `/api/settings/bond-terms/**`、
> `/api/bff/asset-class-settings/securities/**` → `/api/settings/securities/**`。
> `effectiveStockStyle` 以**最新快照**的逐持股 `dividendRate` 套規則算出（僅 `STOCK` 者有值）；`effectiveBondTerm` 以標的名稱年期套規則算出（僅 `BOND` 者有值），override 皆優先。

#### Payment Accounts / Categories（Requirement 22 新增）
```
GET    /api/settings/payment-categories               # 列出所有分類（含停用）
GET    /api/settings/payment-categories/active        # 僅列出啟用中（新增 dialog 下拉用）
POST   /api/settings/payment-categories               # 新增分類
PUT    /api/settings/payment-categories/{id}          # 更新分類
PATCH  /api/settings/payment-categories/{id}/active   # 啟用/停用分類

GET    /api/payment-accounts                          # 列出所有代繳記錄（含 category 展開）
POST   /api/payment-accounts                          # 新增代繳記錄
PUT    /api/payment-accounts/{id}                     # 更新代繳記錄
DELETE /api/payment-accounts/{id}                     # 刪除代繳記錄（硬刪除）

# 前端 view 經 BFF：rewrite /api/bff/payment-account-settings/categories/** → /api/settings/payment-categories/**
#                  rewrite /api/bff/payment-account-settings/accounts/**   → /api/payment-accounts/**
```

#### Stock Alerts（到價警示，新增）
```
GET    /api/stock-alerts                  # 列出所有警示（含最近觸發資訊）
POST   /api/stock-alerts                  # 新增警示
PUT    /api/stock-alerts/{id}             # 更新
DELETE /api/stock-alerts/{id}             # 刪除
PATCH  /api/stock-alerts/{id}/active      # 啟用/停用
PUT    /api/stock-alerts/reorder          # 拖曳排序（body: ordered ids）
POST   /api/stock-alerts/check            # 手動觸發檢查
GET    /api/stock-alerts/lookup-name      # 以股票代號查名稱（前端共用）
GET    /api/stock-alerts/lookup-code      # 反向：以股名查代號（只查本地 stock 主檔，精確匹配）
GET    /api/stock-alerts/recipients       # 列出可挑選收件人 [{id,email,active}]（委派 NotificationRecipientService；供警示對話框「通知對象」多選；Task 125）
```

> **每條警示挑選收件人（Task 125）：** `StockAlertDto.Request` / `Response` 新增 `recipientIds`（`List<Long>`）。`create` / `update` 以 `recipientIds` 覆寫 `stock_alert_recipient`（先 `deleteByAlertId` 再批次 insert）；`Response` 回填該警示目前的 `recipientIds`。對話框「通知對象」多選的可選清單走同頁 BFF `GET /api/bff/stock-alert/recipients`（passthrough → `/api/stock-alerts/recipients`，後端委派 `NotificationRecipientService.findAll()`，與通知設定頁同一份資料源）。新增警示預設全選；選 0 位代表觸發不寄信。

#### Notification Recipients（Requirement 23 新增）
```
GET    /api/notification-recipients                  # 列出所有收件人（含啟停狀態）
POST   /api/notification-recipients                  # 新增收件人
PUT    /api/notification-recipients/{id}             # 更新 email
DELETE /api/notification-recipients/{id}             # 刪除
PATCH  /api/notification-recipients/{id}/active      # 切換啟用/停用

# 前端 view 經 BFF：rewrite /api/bff/notification-settings/recipients/** → /api/notification-recipients/**
```

#### Exchange Rate History（新增）
```
POST   /api/market-data/exchange-rate/backfill-history?currency=USD&since=2021-01-01
                                               # 強制補齊指定日期起的歷史匯率（忽略現有 maxDate）
```

#### Macro History（Requirement 18：股市大盤查詢）
```
GET    /api/taiwan-gdp                         # 全部年度人均 GDP（USD）
GET    /api/taiwan-gdp?since=1996              # 起始年（含）以後
POST   /api/taiwan-gdp/refresh-from-imf        # 回補台灣人均 GDP / 實質成長率（DGBAS 優先、IMF 備援）
GET    /api/japan-gdp / ?since=1996            # 日本人均 GDP（USD）+ 實質成長率
POST   /api/japan-gdp/refresh-from-imf         # 回補日本人均 GDP / 實質成長率（純 IMF）
GET    /api/korea-gdp / ?since=1996            # 韓國人均 GDP（USD）+ 實質成長率
POST   /api/korea-gdp/refresh-from-imf         # 回補韓國人均 GDP / 實質成長率（純 IMF）
GET    /api/twse-daily-index?from=YYYY-MM-DD&to=YYYY-MM-DD
                                               # 台股大盤每日收盤（10 年回補後使用）
POST   /api/twse-daily-index/refresh?years=10  # 逐月呼叫 TWSE MI_5MINS_HIST 抓所有交易日 upsert
# 已移除（Task 97）：/api/twse-year-end-index(GET/refresh)、/api/twse-daily-index/latest（年末走勢圖卡移除）
GET    /api/us-daily-index?code=SPX&from=&to=  # 海外指數每日 OHLC（code ∈ DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225）
POST   /api/us-daily-index/refresh?code=SPX    # Yahoo v8 chart range=10y 抓單一指數 upsert（一次呼叫）
GET    /api/index-intraday?market=TWSE         # 指數「當日」分時（Yahoo 5m，取最新交易日；transient，不寫 DB）

GET    /api/bff/gdp-twse?years=40              # 前端 view 專用，回傳近 N 年彙整資料
POST   /api/bff/gdp-twse/refresh?years=40      # 並行觸發 TWN+JPN+KOR 人均 GDP 回補（見下方說明）
GET    /api/bff/gdp-twse/index-daily?market=TWSE&years=10
                                               # 指數日線 + MA20/60/240（market=TWSE 或 DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225；一次載入，前端 dataZoom 切區間）
POST   /api/bff/gdp-twse/refresh-index-daily?market=TWSE&years=10  # 觸發「當前選取」指數日線回補
GET    /api/bff/gdp-twse/index-intraday?market=TWSE   # 「當日」分時：回 tradingDate + times(HH:mm) + closes + previousClose/lastClose/change/changePercent
```

「當日」分時資料源（Requirement 18，比照股票分析 Task 87/88 版型但不走 tick store）：
- 指數（大盤＋美股四大＋海外四指數）不在 Redis tick 輪詢名單，故 ext-materials `MacroDataFetchClient.fetchIndexIntraday(market)` 即時向 Yahoo v8 chart（`interval=5m&range=5d`）抓取，依 `exchangeTimezoneName` 轉當地時區、group by 當地日期取「最新交易日」回傳；盤中＝今日部分 bar（即時）、盤後＝最後完整交易日 → 自動滿足需求。transient 不寫 DB
- market→Yahoo symbol：TWSE→`^TWII`、DJI→`^DJI`、SPX→`^GSPC`、IXIC→`^IXIC`、SOX→`^SOX`、FTSE→`^FTSE`、DAX→`^GDAXI`、KOSPI→`^KS11`、N225→`^N225`
- 各市場交易時段（補滿 5 分格用，當地時區）：TWSE 09:00–13:30、美股四大 09:30–16:00、FTSE 08:00–16:30、DAX 09:00–17:30、KOSPI 09:00–15:30、N225 09:00–15:30（東京 2024-11-05 收盤由 15:00 延至 15:30；前場 09:00–11:30、後場 12:30–15:30，午休 11:30–12:30 無 bar→留 null）。以 `INDEX_TRADING_HOURS` map 查詢，未知市場 fallback 09:30–16:00
- 前端「當日」模式 x 軸改 HH:mm、收盤單線；月/季/年線改畫水平參考線（取日線最新 MA20/60/240），與其他期間同口徑
- 「當日」卡片標題列另顯示**昨收 / 漲跌 / 漲跌%**，由 BFF 計算（前端只 render，符合「計算放 BFF」）：`previousClose` ＝該指數日線表（`twse_index_daily_history` / `us_index_daily_history`）中 `tradingDate` **之前**最後一筆收盤——與觀察清單 0000 報價 `WatchStockService.toIndexResponse` 讀**同一張日線表**，昨收為同一事實來源、值一致；`lastClose` ＝分時 `closes` 末筆非 null（盤中即時 / 盤後收盤）；`change = lastClose − previousClose`、`changePercent = change / previousClose ×100`（HALF_UP 2 位）。BFF 以 `Mono.zip` 並行抓 intraday 與「近 40 日日線 tail」（台股 `/api/twse-daily-index`、美股 `/api/us-daily-index`，與 `index-daily` 同一支 business API，同義欄位同一來源；40 日涵蓋最長連假確保含前一交易日）；日線 asc，取「`tradingDate` 嚴格小於當日」的最後一筆。日線未回補導致昨收缺值時 `change/changePercent` 回 null，前端整段不顯示
- **昨收新鮮度 — 海外指數日線自動回補（Task 105）**：上述昨收讀日線表，前提是日線表「最新」（含前一交易日）。`us_index_daily_history` 原本只靠前端「回補日線（10 年）」按鈕手動觸發、無排程，久未點擊的指數會停在舊日期；當日走勢點位是即時 Yahoo（最新交易日），昨收卻退回數日前舊收盤 → 漲跌% 失真（實機 SOX 顯示 +13.88%，實際 ~+5%）。注意失效模式是「過時但**非空**→昨收為錯的舊值」，非「缺值→null」，故 BFF 的 null 守門擋不住。修法：business-services 新增 `IndexDailyRefreshScheduler` 讓日線表恆保最新（昨收不變更事實來源），角色比照台股大盤的 ext-materials `TwseIndexPoller`：
  - `@Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")`：美股 16:00 ET 收盤後（≈隔日 04~05:00 台北）足夠緩衝，07:00 對 8 指數逐一呼叫 `MacroHistoryService.refreshUsIndexDaily(code)`（＝手動按鈕同一條 Yahoo `range=10y` idempotent upsert，500ms 間隔）；此時亞/歐/美最新交易日皆已收
  - `@EventListener(ApplicationReadyEvent)` self-heal：開機延遲 30s（待 external-materials 就緒）後，任一指數最新日期過時（> 4 日，容忍週末+1 假日）即補一次，處理「服務於排程時點未運行（restart/crash）」
  - 8 指數代碼收斂為單一來源 `MacroHistoryService.OVERSEAS_INDEX_CODES`，`MacroHistoryController` refresh 守門白名單改引用之（去重）；新增 repo `findTopByIndexCodeOrderByTradingDateDesc` 供 self-heal 取各指數最新日期

回傳格式（BFF `/api/bff/gdp-twse`，服務「台日韓人均 GDP 比較」圖）：
```json
{
  "years": [1996, 1997, ..., 2025, 2026],
  "gdpPerCapitaUsd": [13571, 13888, ...],
  "japanGdpPerCapitaUsd": [37000, 35000, ...],
  "koreaGdpPerCapitaUsd": [12000, 12500, ...],
  "taiwanGdpGrowthRate": [6.05, 4.2, ...],
  "japanGdpGrowthRate": [1.6, -1.0, ...],
  "koreaGdpGrowthRate": [7.1, 5.9, ...]
}
```
（X 軸年份取 TW/JP/KR 三國 GDP 的聯集並過濾 `> 當年`。Task 97 起不再回 `twseYearEndClose` / `currentYearLastTradingDate`——「人均 GDP vs 台股大盤年末收盤」卡已移除。`POST /api/bff/gdp-twse/refresh` 亦簡化為只觸發 TWN+JPN+KOR GDP 回補，回 `{gdp, japan, korea}` 三國回補結果）

回傳格式（BFF `/api/bff/gdp-twse/index-daily`，`market=TWSE` 或 DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225 皆同一格式）：
```json
{
  "dates":  ["2016-05-05", ..., "2026-05-05"],
  "closes": [8295.74, ..., 23000.00],
  "ma20":   [null, ..., 22950.12],
  "ma60":   [null, ..., 22500.45],
  "ma240":  [null, ..., 21800.30]
}
```
（`null` 代表移動平均尚未滿視窗的早期資料點）

台灣人均 GDP / 實質成長率資料來源（**DGBAS 優先、IMF 備援**）：
- ext-materials-service `MacroDataFetchClient.fetchDgbasNationalIncome()` 抓主計總處 NA8101A1A
  XML（表號＝國民所得統計常用資料-年；URL 由 `macro.dgbas.na8101-url` 設定，預設指向 data.gov.tw
  資料集 44218 列出之下載點），解析「經濟成長率(%)」與「平均每人GDP(名目值，美元)」逐年原始值，
  經 `/internal/macro/dgbas` 回 `{growth:{year:val}, gdpUsd:{year:val}}`
- backend `MacroHistoryService.refreshGdpFromImf()`（TWN）合併：以 DGBAS 為主，逐年缺值才用
  IMF `NGDPDPC/NGDP_RPCH` 補（DGBAS 僅至最新實際年度、無 2026+ 預測，預測年由 IMF 提供）；
  DB 存單一最終值，不分來源欄位（符合正規化）。回傳統計 `dgbasGdpYears` / `dgbasGrowthYears`
- 韓國無 DGBAS 對應來源，`refreshKoreaGdpFromImf()` 維持純 IMF
- DGBAS 抓取失敗時 `/internal/macro/dgbas` 回空 map → 全部年份 fallback IMF（安全降級）
- 憑證：ws.dgbas.gov.tw 由 TWCA 簽發但未送中繼憑證，容器 truststore 無法建鏈，故用
  `curl -k` shell-out 取得（與 `fetchImf` 同走 curl 避開 Java TLS）；抓的是公開統計、且有 IMF
  fallback，故 insecure 取捨可接受

對應資料表：
- `taiwan_gdp_per_capita_history` (year PK, gdp_usd, real_gdp_growth_rate) — 值＝DGBAS 優先、IMF 備援
- `japan_gdp_per_capita_history`  (year PK, gdp_usd, real_gdp_growth_rate) — 值＝純 IMF（`refreshJapanGdpFromImf`）
- `korea_gdp_per_capita_history`  (year PK, gdp_usd, real_gdp_growth_rate) — 值＝IMF
- `twse_index_year_end_history`   (year PK, close_point NUMERIC(12,2)) — **Task 97 起已不使用**（年末走勢圖卡移除）；資料表保留不刪，entity/repo/endpoint 已移除
- `twse_index_daily_history`      (trading_date PK, open_point / high_point / low_point / close_point 皆 NUMERIC(12,2))
  - OHLC 同時供 Requirement 18 大盤日線圖、Requirement 14 觀察清單 0000 KD 計算使用
  - `MacroHistoryService.refreshTwseDaily` 從 TWSE FMTQIK 月報抓取四欄（`OpeningIndex` / `HighestIndex` / `LowestIndex` / `ClosingIndex`），同步 upsert
- `us_index_daily_history`        ((index_code, trading_date) PK, open_point / high_point / low_point / close_point 皆 NUMERIC(14,4))
  - 美股四大指數（道瓊 DJI / 標普500 SPX / 那斯達克綜合 IXIC / 費城半導體 SOX）+ 海外主要指數（英國富時 FTSE / 德國 DAX / 韓國 KOSPI / 日經 N225）每日 OHLC，供 Requirement 18 日線圖「市場切換」。表名沿用 `us_index_daily_history`（語意已一般化為「海外指數日線」，欄位本以 `index_code` 通用化，新增市場零遷移）
  - 來源 Yahoo Finance v8 chart API（`^DJI`/`^GSPC`/`^IXIC`/`^SOX`/`^FTSE`/`^GDAXI`/`^KS11`/`^N225`，`range=10y&interval=1d`），ext-materials-service `MacroDataFetchClient.fetchUsIndexDaily(code)` 以 curl 子程序抓取（避 Yahoo Java fingerprint 封鎖）；timestamp→交易日依 Yahoo meta `exchangeTimezoneName` 轉當地時區（非寫死 NY，否則亞洲/歐洲指數日期回退一日）；`MacroHistoryService.refreshUsIndexDaily(code)` 經 `/internal/macro/us-index` proxy 後 upsert
  - **與 `twse_index_daily_history` 分表**的理由：台股大盤為單一指數（無 code 欄）、且已與 Requirement 14 觀察清單 0000 報價/KD 與當年年末回填邏輯耦合，分表可完全不動既有台股流程；兩表由 `GdpTwseBffController` 以**同一套 MA 計算**服務（同義欄位同一來源），確保兩市場版面一致
  - Stooq CSV 為原評估來源但實測在部署環境被擋（連 `aapl.us` 都回通用錯誤頁），故改採 Yahoo；來源封裝於單一 fetch 方法，可一處替換

GDP 兩表 seed data 直接寫入 Liquibase changelog（歷史值不變）。
日線表（10 年 ~2400 筆）改由使用者按「回補日線」觸發 TWSE MI_5MINS_HIST 月報抓取（changelog 僅建表不 seed），原因：資料量大、會隨時間遞增，不適合寫死於 changelog。

### Response Format

**成功回應**
```json
{
  "id": 1,
  "snapshotDate": "2024-01-31",
  "totalAssets": 5000000.00,
  ...
}
```

**錯誤回應**（由 GlobalExceptionHandler 統一處理）
```json
{
  "error": "錯誤訊息",
  "timestamp": "2024-01-31T10:00:00"
}
```

## Key Business Logic

### 資產總額計算

```
totalDeposit = Σ(bankDeposit.amount)           # amount 為台幣換算金額
totalFund    = Σ(fundHolding.currentValue)
totalStock   = Σ(twStock.currentValue)
             + Σ(usStock.originalCurrencyValue × usdToTwd)
totalAssets  = totalDeposit + totalFund + totalStock
# total_stock_cost 一律台幣：美股 USD 計價列須先換匯再加總，禁止把美元與台幣混加（修正前的 bug）
totalCost    = Σ(twStock.investmentCost)                          # 台股：台幣原值
             + Σ(usStock[TWD].investmentCost)                     # 美股 TWD 計價：台幣原值
             + Σ(usStock[USD].investmentCost × (txRate ?: 快照匯率))  # 美股 USD 計價：交易日匯率優先，否則快照匯率
totalProfit  = totalStock - totalCost
# recalcTotals（彙總）與 toDetailResponse（逐筆 investmentCostTwd）共用 AssetService.stockInvestmentCostTwd，
# 確保 total_stock_cost == Σ 各列 investmentCostTwd；逐筆股票損益亦以「台幣現值 − 台幣成本」計算（美股不可拿美元成本直接相減）。
# total_* 為歷史快照的 denormalized 欄位，僅在建立/更新時重算；修正舊資料用 POST /api/snapshots/recalc-totals 一次重算。

# 預估年配息合計（asset_snapshot.estimated_annual_dividend）
estimatedAnnualDividend
  = Σ(stockHolding.estimatedDividend)
  + Σ(fundHolding.estimatedDividend)
  + Σ(bankDeposit.amount × bankDeposit.annualInterestRate / 100)  # 存款預估年利息（amount 已是台幣等值）
```

### 現金／債券／股票 三分類計算（Requirement 25）

由 `AssetService.getAssetHistory()` 在既有逐快照迴圈內一併算出，隨 `/api/snapshots/history` 回傳（`cashValue`/`bondValue`/`stockValue`）。`AssetClassifier` 提供分類規則，個股 override 由 `stock.asset_class` 提供（一次查 `stockRepo.findAll()` 建 `Map<market|code, assetClass>`）、基金 override 由 `fund_class_override` 表提供（以名稱為 key，一次查 `fundClassOverrideRepo.findAll()` 建 `Map<fundName, {assetClass, stockStyle, bondTerm}>`；基金 `fund_code` 多為 NULL 故以名稱識別）。基金 STOCK 風格沿用 `classifyStockStyle(code=null, dividendRate=null, override)`（無殖利率 → override 或成長型）、BOND 期別沿用 `classifyBondTerm(name, override)`。

```
classifyStock(code, market, override):
  override != null            → override                       # 人工指定優先
  market=台股 且 code 00…B     → BOND                           # 櫃買債券 ETF
  code ∈ US_BOND_ETFS         → BOND                           # 美股/英股債券 ETF 清單
  其餘                         → STOCK
classifyFund(name, override):
  override != null                       → override            # 基金人工指定優先（fund_class_override.asset_class，key=fund_name）
  name 含 債/bond/高收益/收益（不分大小寫）  → BOND               # 高收益債、收益型債券基金名稱多不帶「債」字
  其餘（含 入息/股息 高股息股票型基金）       → STOCK

cashValue  = twdDeposit + usdDeposit                            # == 既有「台幣存款 + 美元存款」
bondValue  = Σ(stock.currentValue  where classify=BOND)
           + Σ(fund.currentValue   where classifyFund=BOND)
stockValue = Σ(stock.currentValue  where classify=STOCK)
           + Σ(fund.currentValue   where classifyFund=STOCK)
# 不變式：cashValue + bondValue + stockValue == totalAssets（與六分類同源同總）
```

採快照凍結之逐筆 `currentValue`（不套盤中 live），巨觀資產配置毋須 intraday 精度。

### 成長型／收益型 風格細分（Requirement 26）

套在 `asset_class = STOCK` 之上的正交子維度，與三分類同在 `getAssetHistory()` 迴圈內算（隨 `/api/snapshots/history` 回傳 `growthValue`/`incomeValue`）。`stock_style` override 由 `stock.stock_style` 提供（一次查 `stockRepo.findAll()`）；門檻由 `stock_style` INCOME 列 `dividend_threshold` 提供（預設 0.04）。

```
classifyStockStyle(code, market, styleOverride, dividendRate, threshold):
  styleOverride != null                       → styleOverride          # 人工指定優先
  code ∈ HIGH_DIVIDEND_ETFS                    → INCOME                 # 高股息 ETF 清單（不受殖利率波動影響）
  dividendRate != null 且 dividendRate ≥ threshold → INCOME
  其餘（含 dividendRate null/0）                → GROWTH                 # 缺值 fallback 成長

# 僅對「該股票經 classifyStock 判為 STOCK（非 BOND）」者再細分：
growthValue = Σ(stock.currentValue where assetClass=STOCK 且 style=GROWTH) + Σ(非債券 fund.currentValue)
incomeValue = Σ(stock.currentValue where assetClass=STOCK 且 style=INCOME)
# 不變式：growthValue + incomeValue == stockValue（基金一律歸成長：無 dividendRate 欄、v1 不細分）

# Requirement 27：債券再依名稱年期細分短/中/長期（classifyBondTerm，override 優先；用股票主檔名稱判定）
bondShortValue = Σ(bond.currentValue where term=SHORT)
bondMidValue   = Σ(bond.currentValue where term=MID)
bondLongValue  = Σ(bond.currentValue where term=LONG)
# 不變式：bondShortValue + bondMidValue + bondLongValue == bondValue
```

### Excel 匯入流程

```
1. 讀取 .xlsx 檔案
2. 遍歷工作表（名稱格式：YYYYMMDD）
3. 解析日期 → 建立 AssetSnapshot
4. 偵測格式版本（新/舊）
5. 從資料庫載入所有啟用中的 Bank（含 keywords）
6. 解析銀行存款行（依資料庫 keywords 動態比對 Bank）
7. 從資料庫載入所有啟用中的 Broker（含 keywords）
8. 解析股票持倉（格式：代號+名稱 或 代號/名稱分欄，依資料庫 keywords 動態比對 Broker）
9. 解析基金持倉
10. 計算並寫入總額
11. 若同日期已存在：一律覆蓋（`overwrite` 參數設計存在於 tasks/spec 但實際 Controller 未暴露，目前行為等同 overwrite=true）
```

### Live 行情 tradingDate 語意（盤外刷新防呆）

Redis 中 `price:{market}:{code}` 的 `tradingDate` 欄位代表**這筆價格資料對應的真實交易日**，不是 cache 寫入當下日期。

**為什麼重要**：TWSE mis API 在週日深夜或非交易時段仍會回傳上一個交易日的最後成交資料。若 `external-materials-service` 的 `/internal/refresh` 盤外被觸發時盲目把 `tradingDate` 設為 `LocalDate.now()`，會造成：
- `TechnicalIndicatorService.compute()` 看到 live `tradingDate == today` 就把它當「今天的 K 棒」併入 KD/MA9 序列
- 實際上那筆資料是上週五的收盤 → 等於把上週五重複算了一次，污染技術指標

**規則**（`external-materials-service` 的 `PriceCacheWriter.resolveTradingDate`）：

```
isLiveSession = (該市場 isOpen) || (該市場剛收盤 20 分鐘窗口)   // 一律用「該市場自己時區」判定
                 // 台股：09:00–13:30 + 13:30–13:50 TW
                 // 美股：09:30–16:00 + 16:00–16:20 ET
                 // 英股：08:00–16:30 + 16:30–16:50 LON

if isLiveSession:
    trading_date = LocalDate.now(MarketClock.zoneOf(market))   // 資料確實來自今天（該市場時區）
else:
    trading_date = max(stock_price_history.trading_date for this code+market)
                    fallback LocalDate.now(zoneOf(market))      // 上一個有真實資料的交易日
```

> 此規則 fix 過去歷史 bug：盤外刷新會把所有 cache 的 `trading_date` 蓋成今天，導致 KD9 把上一交易日的 OHLC 當成今天的 K 棒。
>
> **三市場一致（2026/07，Task 154 修英股「當日」分時跨兩天）：** 舊版 `liveSession` 以 `isUs ? US窗口 : TW窗口` 二分，**英股被歸入 else 誤用台股時段**。倫敦盤中（台北 15:00–23:30）台股早已收盤 → `liveSession` 恆 false → `trading_date` 退回 `findMaxTradingDate`＝昨天；於是今日倫敦即時 tick 被 `appendTick` 寫進「昨天」bucket（`price:ticks:英股:{code}:{昨天}`），與盤後 `IntradayTickRefresher` 覆寫的昨日資料混桶，前端「當日」讀到跨兩天序列（07-07 全日 + 07-08 盤中）→ 時間軸倒退、假高低點。改為 `switch(market)` 三分支各用自身時區窗口 + `zoneOf(market)`，英股即正確分桶。美股原走 `isUsMarketOpen` 判斷正確、Redis 一向乾淨，不受此 bug 影響。連帶修正英股 `price:dayhl:*`（`IntradayHighLowTracker` 亦以此 `tradingDate` 為 key）跨日混算的最高／最低。

### 即時資產估算（Live Assets）

```
1. 取得最新 AssetSnapshot（含 StockHolding 清單）
2. 取得最新 USD/TWD 匯率（ExchangeRateHistory）
3. 對每筆 StockHolding，透過 `PriceQueryService` 從 Redis 取 live 報價（miss 則 fallback 至 stock_price_history 最近一筆收盤）：
   - 台股：liveValue = shares × price（TWD）
   - 美股：liveValue = shares × price × exchangeRate（USD→TWD）
4. liveStockValue = Σ(liveValue)
5. liveTotalAssets = snapshot.totalDeposit + snapshot.totalFundValue + liveStockValue
6. 回傳：liveStockValue, liveTotalAssets, perStock[{code, market, price, liveValue, closed}],
         twMarketOpen, usMarketOpen, priceUpdatedAt
```

> 存款與基金以快照當時值為準；股票部位反映最新市價。
> `closed=true` 表示該股已使用收盤價，不再盤中更新。

### 配息率（殖利率）查詢與計算

由 `MarketDataService.getDividendRate(stockCode, market)` 統一提供，所有頁面（Dashboard、SnapshotDetail、SnapshotForm）一律走此函式，避免不同頁面值不一致。

**台股查詢優先序**（依序 fallback，皆「最近 3 年平均」口徑）：
1. **FinMind** 近 3 年平均殖利率（ETF / 個股皆適用，免認證）— 主要來源
2. **TWSE BWIBBU 歷史** 近 3 年平均（FinMind 失敗時備援）
3. **TWSE OpenAPI BWIBBU_ALL** 當日殖利率（最後備援，僅涵蓋上市個股，不含 ETF）

**美股查詢優先序**：
1. **NASDAQ API** 即時殖利率
2. **內建已知 ETF 表**（Vanguard 等 NASDAQ API 查不到的常見 ETF）

> Yahoo Finance 已停用（Docker 環境被擋，且其數據與台灣公開資料口徑不一致）。

#### FinMind 認證

FinMind 自 2025 年起對匿名呼叫額度收緊，超量會回 `402 Payment Required`，造成股利歷史 / ETF 持股 / 歷史收盤價回補等功能失敗。後端統一從環境變數 `FINMIND_TOKEN`（亦可用 `finmind.token` Spring property）讀取 token，所有 `api.finmindtrade.com` 的呼叫於有 token 時加上 `Authorization: Bearer <token>` header；未設定時保持匿名行為向下相容。

實作位置：
- `MarketDataService.finmindRequest()`：股利率、ETF 持股、股利歷史
- `HistoricalDataService.httpGet()`：歷史收盤價、匯率歷史、股票名稱（自動偵測 URL 為 FinMind 時加上 header）

**「最近 3 年平均」演算法**（`getFinMindDividendRate` / `getTwseThreeYearAvgDividendRate`）：

```
分子：依年份彙總每年現金配息金額（CashEarningsDistribution + CashStatutorySurplus）
     ↓ 排除當年度（資料不完整會壓低平均）
     ↓ 取最近 3 個完整年度的算術平均 = avgAnnualDividend

分母：當前股價（current price）
     ├── 優先：即時 API（getTwseRealTimePrice）
     └── Fallback：Redis live cache → stock_price_history 最近一筆

殖利率 = avgAnnualDividend / currentPrice
```

> **設計決策**：分母固定使用「當前股價」而非「3 年平均股價」。早期版本曾改為 3 年均價想與分子時間窗對齊，但與 Yahoo / Goodinfo 等公開資料源差距反而拉大；最終決定回到「現價分母」貼近市場慣用口徑。

#### 股利歷史資料來源（stock_dividend_history）

`external-materials-service` 的 `DividendFetchClient.fetchTw` 抓台股股利歷史，台股採兩段資料源：

```
1. FinMind TaiwanStockDividend（盈餘分配表）
     ├─ 個股 / 股票型 ETF（如 2330、0056）：有資料 → 直接採用
     └─ 債券 ETF / 收益分配型 ETF（如 00751B）：回空陣列 []
                                                  ↓ fallback
2. FinMind TaiwanStockDividendResult（除權息結果表）
     以 date=除息日、stock_and_cache_dividend=配息金額組成 DividendEvent
     stock_or_cache_dividend 含「權」且不含「息」→ 股票股利；其餘 → 現金股利
     此表無發放日 → cashPaymentDate / stockPaymentDate = null
```

> **為何需要 fallback**：`TaiwanStockDividend` 是上市櫃**公司**的盈餘分配政策表（盈餘分配、法定公積、員工股利），ETF 的配息屬「收益分配」性質，債券 ETF 配的是成分債券利息，根本不在此表 → 查無。但 ETF 每季除息事件都會落在 `TaiwanStockDividendResult`，故以此為 fallback。`fetchTw` 先打盈餘分配表，**只有回空時**才打結果表，避免影響既有個股 / 股票型 ETF（兩表欄位語意不同，個股仍以盈餘分配表的現金 / 配股拆分為準）。

美股 `DividendFetchClient.fetchUs` 同採兩段資料源：

```
1. NASDAQ /api/quote/{code}/dividends（assetclass=stocks→etf）
     └─ NASDAQ 上市（如 AAPL、QQQ）：有 rows → 直接採用，source=NASDAQ
     └─ NYSE / NYSEARCA 上市（如 VOO、SGOV、SCHD、JEPI）：rows 全 N/A
                                                  ↓ fallback
2. Yahoo chart?range={years}y&events=div（curl 子程序）
     events.dividends 每筆 amount=現金配息、date=除息日(epoch 秒，以 America/New_York 轉日期)
     Yahoo 僅現金配息、無發放日 → cashPaymentDate = null，source=Yahoo Finance
```

> NASDAQ 的 `/dividends` 只服務 NASDAQ 自家上市標的，對 NYSEARCA ETF 一律回 N/A（與 Task 69 殖利率遇到的限制同源）。Yahoo chart events=div 對全美股 / ETF 都有完整除息歷史，且與 `MarketDataFetchService.getYahooDividendRate`（殖利率）走同一資料源，符合「同義欄位、同一資料來源」原則。Yahoo 一律以 curl 子程序呼叫，避開 Java HttpClient 被 WAF 擋（同 ETF 持股 / 殖利率既有做法）。**curl 的 User-Agent 必須用短字串 `Mozilla/5.0`**：實測 Yahoo WAF 對「長 Chrome UA + curl TLS 指紋」判為 bot 回 `Too Many Requests`(429)，短 UA 才放行（與 `getYahooDividendRateForTicker` 一致）。

下游 `DividendPersister.syncOne` 對所有來源的 `DividendEvent` 一視同仁：用 `calcDividendBasis` 由除息日回查 `stock_price_history` 算昨收價、填息天數、現金殖利率後 upsert。`source` 不再以市場別硬編，改由 `fetch()` 回傳的 `DividendFetchResult.source`（FinMind / NASDAQ / Yahoo Finance）逐筆寫入，確保 `stock_dividend_history.source` 與實際採用的資料源一致。

### 預估配息資料來源（estimatedAnnualDividend）

**單一資料來源原則**：所有 view 顯示的「預估年配息」一律讀自 `asset_snapshot.estimated_annual_dividend` 欄位，**不再於 BFF / 前端即時重算**。

**寫入時機**（由 `AssetService.autoEnrichDividendRates` 與 `AssetService.recalcAllEstimatedDividends` 維護）：

```
對快照中每個 StockHolding：
  if dividendRate > 0 且 currentValue > 0:
    estimatedDividend = currentValue × dividendRate
快照層級：
  asset_snapshot.estimated_annual_dividend = Σ holding.estimatedDividend
```

**讀取面**：
- `AssetService.getAssetHistory()`（行 340）— 直接 `snapshot.getEstimatedAnnualDividend()`，不重算
- `DashboardBffController` / `SnapshotDetailBffController` / `SnapshotFormBffController`：透過 `SnapshotEnricher` 取得已存的值

**觸發補算的端點**：
- `POST /api/snapshots/recalc-dividends`：對所有快照重跑 `currentValue × dividendRate` 並回寫
- `POST /api/snapshots/enrich-all-dividend-rates`：背景補齊缺漏的 `dividendRate`，順帶更新 estimatedDividend
- 新建 / 編輯快照存檔時亦會寫入

> **歷史背景**：早期 BFF 在 response 動態組裝 estimatedAnnualDividend，導致同一張快照在 AssetHistory 與 Dashboard 顯示不同值（一邊用即時股價、一邊用快照當日股價）。改為「snapshot 欄位即真相」後三邊一致，並符合 CLAUDE.md「同義欄位、同一 business service API」原則。

### 警示觸發 Email 通知（Requirement 23）

**目標**：警示條件觸發時自動寄 email，避免使用者盯盤。

**架構**：

```
StockAlertService.evaluate()
  ├─ 24h cooldown 通過 + 條件命中
  ├─ alertRepo.save(凍結 K/D/MA)
  ├─ recordTrigger() → 寫 StockAlertTrigger（必落地，無論寄信成敗）
  └─ alertNotificationDispatcher.enqueue(alert, triggeredAt, price,
                                         ind.monthlyMa, ind.quarterlyMa, ind.annualMa, ind.k, ind.d)
                                  │
                                  ▼
                       in-memory ConcurrentLinkedQueue
                                  │
                                  ▼   @Scheduled fixedDelay = 60s
                       AlertNotificationDispatcher.flush()
                                  │
                                  ├─ queue 為空 → return
                                  ├─ 一次 drain 全部
                                  ├─ 依市場時區過濾「開盤 ~ 收盤+10 分」時段（withinSendWindow）
                                  │     盤外市場本輪丟棄；全部盤外 → return
                                  ├─ 逐筆觸發查其警示選定且 active 的收件人 email（recipientsFor(alertId)）
                                  ├─ 反轉分組 → Map<email, List<觸發>>（每位收件人只含其訂閱的觸發）
                                  └─ 對每位收件人各組一封 digest（走勢圖 PNG 以 stock 為 key 跨收件人快取）
                                       └─ EmailService.sendHtml([email], subject, html, images)
                                                ├─ MAIL_USERNAME 未設 → log.warn skip
                                                └─ SMTP 失敗 → log.warn 不重試
```

**為何走「queue + 60s flush」而非 event-driven 即時寄**：
- 同一輪 PricePoller（每 2 分鐘）可能在 1–2 秒內連續 fire 多支股票的 `checkAlertsFor`，立即寄信會產生多封；queue 自然 batch 成單封 digest
- 60 秒 flush 是「使用者人類感受可接受的延遲」與「合併效益」的折衷
- in-memory queue 不持久化是刻意的：若服務重啟未及寄出，`StockAlertTrigger` 已落地、24h cooldown 也會啟動，下次觀察清單頁仍看得到觸發狀態；遺漏一封通知優於發兩封或卡住觸發流程

**邊界處理**：
- **寄送時段閘門（盤外不寄，`withinSendWindow`）**：flush 對 drain 出的每筆觸發依其市場時區判定是否在 `[開盤, 收盤+10 分]` 平日時段（台 09:00–13:40 / 美 09:30–16:10 / 英 08:00–16:40；開收盤時刻取自 `MarketZones.openTime/closeTime` 單一來源，`SEND_GRACE_MINUTES=10` 容納 60s flush 延遲與 cron 採樣落後；不考慮假日，與 `computeTriggeredAt` / `lastTradingDate` 同口徑——假日本就無 price-update 觸發，放行亦無信可寄）。盤外市場的觸發本輪 **丟棄不寄**（queue 一律 drain 不回填，避免無限長大；`StockAlertTrigger` 歷史已落地，使用者可按「補發」重寄）。多市場混批逐筆判定，僅寄出仍在盤中的市場（例：深夜台股已收盤、美股盤中 → 只寄美股）。動機：避免在該市場盤外時段收到當日早已收盤的警示信。手動 `resendLastTradingDay()` **不經此閘門**（明確的使用者重寄動作）
- 收件人空 / `MAIL_USERNAME` 空 / SMTP 例外：一律 `log.warn` 後返回，**絕不**拋例外回到 `StockAlertService` —— 警示判斷必須與通知解耦
- **每條警示挑選收件人（Task 125）**：flush 不再「全部觸發一封、寄給所有 active 收件人」，而是逐筆觸發以 `alert_id` 經 `stock_alert_recipient` 查出選定收件人、交集 `active=true`，反轉成 `Map<email, List<觸發>>`；每位收件人各組一封僅含「其訂閱觸發」的 digest，`sendHtml(List.of(email), …)` 單一收件人寄出。`recipientsFor(alertId)` 結果於該輪 flush 內以 `Map<Long,List<String>>` 快取（同 alert 多筆觸發不重查）；走勢圖 PNG 以 `stockCode+market` 為 key 跨收件人快取，避免同一檔重複 render。為此 `PendingTrigger` record 增帶 `alertId` 欄位（`enqueue` 時填 `alert.getId()`、補發 `toPending` 時填 `StockAlertTrigger.alertId`），作為 `groupByRecipient` 查收件人與快取的 key。`resendLastTradingDay()` 回傳 `ResendResult{status, count（去重股票檔數）, recipientCount（實際寄達人數）}`，前端補發提示顯示「已補發 N 檔股票給 M 位收件人」
- digest 主旨：`[資產管理] 股票警示觸發 N 筆`，N = **該收件人這封信**去重後的股票檔數（非觸發筆數）；同輪不同收件人各自的 N 可能不同
- **同一股票（stockCode+market）多條件觸發合併成一筆**（`groupByStock`，保留首次出現順序）：標題 `{stockName} ({stockCode} {market}) — {label1、label2…}`（該股所有觸發條件 label 去重串接），其下依序 `觸發時間`、`股價`、三條均線 `月線 {monthlyMa}`／`季線 {quarterlyMa}`／`年線 {annualMa}`、`KD：K {k} / D {d}`。技術快照（時間/股價/MA/KD）取該股**最近一筆觸發**（max `triggeredAt`）——同股各條件的 MA/KD 本即同源、僅時間略異（某條均線因歷史不足為 null 時該行省略；K/D 皆 null 時整行省略）。三條均線值取自 `recordTrigger` 當下 `TechnicalIndicatorService.computeAll()` 的 `FullIndicators`，與 `stock_alert_trigger` 落地的 `monthly_ma/quarterly_ma/annual_ma` 同源

**手動補發（觀察清單「補發」按鈕，限當前市場 tab；Task 128）**：
- 前端 `WatchStockView` 「新增觀察」左側「補發」鈕 → `POST /api/bff/watch-stock/resend-digest?market={marketTab}`（watch-stock BFF passthrough → `/api/watch-stocks/resend-digest`，query 原樣帶過，守「一頁一支 BFF」）。按鈕文字隨 `marketTab` 顯示「補發台股 / 補發美股 / 補發英股」，使「只補發當前市場」對使用者明示
- `WatchStockController.resendDigest(@RequestParam(required=false) market)` 委派 `AlertNotificationDispatcher.resendLastTradingDay(market)`：
  - **市場範圍**：`market` 有值（前端必帶）→ 只處理該單一市場；`market` 為 null / 空 → fallback `triggerRepo.findDistinctMarkets()` 全市場（向後相容、直接打 API 仍可全補）。實作上把原 `for (market : findDistinctMarkets())` 的來源換成 `markets = (market 空) ? findDistinctMarkets() : List.of(market)`
  - 對每個 market，依市場時區算「最後交易日」（`lastTradingDate`：平日已過開盤＝當日、盤前 / 週末回溯最近平日；不考慮假日），取該日 `triggered_at ∈ [當地 00:00, 翌日 00:00)` 的所有觸發
  - 把結果**以收件人為單位**各組一封（重用同一 `buildDigest` → 月線/季線/年線 + KD 同源），主旨 `[資產管理] 股票警示補發 N 筆`，每位收件人只含其所訂閱警示的觸發、單一收件人寄出（同自動 flush 的 per-recipient 分組；Task 125）
  - 觸發列回查 `StockAlert`（`alertId`）以 `buildLabel` 還原條件文案；alert 已刪除的孤兒觸發以「警示觸發」當 fallback label，不靜默丟棄
  - 回傳 `{sent, count, message}`：`SENT` / `NO_EVENTS`（該市場最後交易日無觸發）/ `NO_RECIPIENTS` / `EMAIL_DISABLED` 四態，後三者不寄信，前端據以提示；controller 組訊息時把市場名嵌入（如「已補發 美股 N 檔股票給 M 位收件人」「美股最後交易日無觸發事件，無可補發」）
  - 補發為手動全量重寄，與自動 digest 的 24h cooldown / queue 互不影響（不寫 cooldown、不入 queue）

**HTML 信 + 內嵌走勢圖（Task 93）**：
- digest / 補發信改以 HTML 寄送：`EmailService.sendHtml(recipients, subject, html, inlineImages)` 用 `MimeMessage` + `MimeMessageHelper(multipart)`，`setText(html, true)` 後逐一 `addInline(cid, ByteArrayResource(png), "image/png")`
- `AlertNotificationDispatcher.buildDigest()` 回 `DigestMail{html, inlineImages(cid→png), stockCount}`：每檔股票區塊組好文字後，呼叫 `AlertChartRenderer.renderPriceMaPng(code, market)` 取圖，成功則 `images.put("chart{i}", png)` 並插入 `<img src="cid:chart{i}">`
- `AlertChartRenderer`：純 Java（XChart）server-side 繪圖，**上下雙 pane 合成一張 PNG**（Graphics2D 垂直拼接）：
  - 資料一律走 `HistoricalDataService.getStockHistory(code, market, end-120M, end)`（與畫面 `StockAnalysisDialog` **同 120 個月範圍**、0000 自動讀 `twse_index_daily_history` 含 OHLC、併今日即時價）
  - MA：與前端 `calcMA` 相同——**每點對 window 重新加總**（非滑動扣減，避免長序列累積誤差）+ `BigDecimal HALF_UP` 2 位
  - KD：與前端 `calcKD` / `TechnicalIndicatorService` 同一遞迴（period 9、RSV=(close-ll)/(hh-ll)*100、hh==ll→50、K=prevK*2/3+RSV/3、D=prevD*2/3+K/3、seed 50/50、high/low 缺值 fallback close、續算用未捨入值）。整段歷史算完再切尾 252（≈1年），尾值與畫面逐位一致
  - 上 pane：股價(藍) + 月線MA20(橘) + 季線MA60(紫) + 年線MA240(紅)，隱藏 x 軸（日期只畫在下 pane）；下 pane：K(橘) / D(綠)，Y 0~100，80/20 灰虛線（`setShowInLegend(false)` 不進圖例）
  - **股價線標最高 / 最低點**（比照畫面 `StockAnalysisDialog` 的 markPoint）：`addHiLoMarkers` 找顯示窗（≈252 日）內股價最高 / 最低收盤，各以 `HiLoMarker`（自訂 `Annotation` 子類）畫出——圓點落在線上 + 圓角色塊兩行（第一行「最高/最低 + 價位 `%,.2f`」、第二行日期 `yyyy/MM/dd`），紅最高(#dc2626) / 綠最低(#16a34a)（紅漲綠跌）。色塊位置：最高放點下方、最低放點上方，水平夾在 plot 內避免出界。最高 / 最低同點（區間平盤）只畫最高。座標用 `Annotation.getXAxisScreenValue/getYAxisScreenValue`（paint 時軸範圍已算妥）→ 點精準落線上；不用 `AnnotationText`（其字色由 styler 全域共用、無法逐點分紅綠）
  - legend 文字 = 中文名稱 + 空白 + 最新值（`%,.2f`），白底，與畫面同
  - 失敗回 empty → 略過該圖、文字照寄。每封信內嵌圖數設上限（超過則該檔僅文字），`sendHtml` log 內嵌總位元組
- `AlertNotificationDispatcher.buildDigest`：數值入圖後文字精簡——保留「標題 + 觸發時間 + **觸發股價**」，移除月/季/年線/KD 文字列（圖內已有）。`<img width:900px>`
- **字型依賴**：runtime image 為 `eclipse-temurin:21-jre-alpine`（slim、無 CJK 字型）→ backend `Dockerfile` apk 裝 `fontconfig ttf-dejavu font-noto-cjk freetype` 並 `fc-cache -f`，ENTRYPOINT 加 `-Djava.awt.headless=true`。`AlertChartRenderer.loadCjkFont` 用 **`Font.createFonts`（複數）挑 TC face**（`.ttc` 的 face 0 為 JP 變體，`createFont` 單數會選到日系字形）；找不到則 fallback SANS_SERIF
- 預覽 / 驗證端點：`GET /api/watch-stocks/chart.png?code=&market=`（BFF passthrough `/api/bff/watch-stock/chart.png`）回同一張 PNG，資料不足回 204

**設定面**：
- `application.yml` 內 `spring.mail.host=smtp.gmail.com:587 STARTTLS`，username / password 走 `${MAIL_USERNAME}` / `${MAIL_PASSWORD}` 環境變數（Gmail App Password，非登入密碼）
- 寄件人預設 = `MAIL_USERNAME`；可以 `NOTIFICATION_FROM` 覆寫
- 收件人於 `/notification-settings` 頁面維護，存 `notification_recipient` 表

## Infrastructure

### Docker Compose Services

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    healthcheck: pg_isready

  redis:
    image: redis:7-alpine
    # 不暴露 host port（僅 Docker internal network）
    healthcheck: redis-cli ping

  business-services:
    build: ./backend
    # 不暴露 host port（僅 Docker internal network）
    depends_on: [postgres (healthy), redis (healthy)]
    environment: SPRING_PROFILES_ACTIVE=postgres

  external-materials-service:
    build: ./external-materials-service
    # 不暴露 host port；僅 docker network 可達
    depends_on: [postgres (healthy), redis (healthy)]
    environment:
      - FINMIND_TOKEN
      - SPRING_REDIS_HOST=redis

  bff:
    build: ./bff
    ports: ["8080:8080"]
    depends_on: [business-services]

  frontend:
    build: ./frontend
    ports: ["80:80"]
    depends_on: [bff]
```

### Nginx Configuration (Frontend)

```nginx
location /api/ {
  proxy_pass http://backend:8080/api/;  # 反向代理至後端
}
location / {
  try_files $uri $uri/ /index.html;     # SPA fallback
}
```

### Database Configuration

| Profile | Database | Connection |
|---------|----------|------------|
| default | H2 (記憶體) | jdbc:h2:mem:assetdb |
| postgres | PostgreSQL | jdbc:postgresql://postgres:5432/assetdb |

### Multi-Stage Docker Builds

**Backend / BFF / External Materials Service**: `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`
**Frontend**: `node:18-alpine` (build) → `nginx:alpine` (serve)

## Backup / Restore (Requirement 15)

### 概念

兩條獨立的備份軌道：

| 軌道 | 觸發 | 目的 | 儲存位置 | 保留 |
|------|------|------|---------|------|
| 排程備份（既有） | host 端 launchd 每日 05:00 | 災難復原 | `gdrive-crypt:backups/{daily,weekly,monthly}/` | 15 / 4 / 6 |
| 手動備份（新增） | UI 按鈕觸發 | 排程失敗時補救、還原前自救點 | `gdrive-crypt:backups/manual/` | 5（自救點不計入） |

還原來源涵蓋四個資料夾，使用者可從任一備份點還原。

### 架構

```
[Frontend Vue]
   │  POST /api/backups          (立即備份)
   │  GET  /api/backups          (列出所有備份)
   │  POST /api/backups/restore  (還原)
   ▼
[BFF asset-bff]
   ▼
[asset-business-services container]
   ├─ BackupController
   ├─ BackupService
   │    ├─ ProcessBuilder → pg_dump  -h postgres -U $POSTGRES_USER -d $POSTGRES_DB --format=custom --compress=9
   │    ├─ ProcessBuilder → rclone   rcat / copy / lsjson / delete
   │    └─ ProcessBuilder → pg_restore -h postgres ... --clean --if-exists
   ▼
[postgres container]   [Google Drive via rclone crypt]
```

容器內需安裝 `postgresql-client`（提供 pg_dump / pg_restore）與 `rclone`，rclone 設定以 read-only volume 從 host 掛入：
```yaml
volumes:
  - ${HOME}/.config/rclone:/root/.config/rclone:ro
```

### API 端點

| Method | Path | 說明 | Request | Response |
|--------|------|------|---------|----------|
| `POST` | `/api/backups` | 觸發手動備份 | （無 body） | `{ filename, sizeBytes, uploadedAt }` |
| `GET`  | `/api/backups` | 列出所有遠端備份 | （無） | `BackupItem[]` |
| `POST` | `/api/backups/restore` | 從指定備份還原 | `{ folder, filename, confirmation: "確認還原" }` | `{ status, preRestoreBackup }` |
| `GET`  | `/api/backups/settings` | 取得保留代數設定（含排程開關） | （無） | `{ manualRetention, dailyRetention, weeklyRetention, backupEnabled }` |
| `PUT`  | `/api/backups/settings` | 更新保留代數設定（含排程開關） | `{ manualRetention, dailyRetention, weeklyRetention, backupEnabled }` | 同上 |
| `POST` | `/api/backups/sync` | 從 Google Drive 同步本地索引：upsert 缺漏 / 清孤兒 | （無） | `SyncResponse` |

`BackupItem` 結構：
```json
{
  "folder": "manual" | "daily" | "weekly" | "monthly",
  "filename": "asset_manual_20260425_170000.dump",
  "sizeBytes": 1456789,
  "modifiedAt": "2026-04-25T17:00:00+08:00",
  "isAutoPreRestore": false
}
```

### 後端流程

**Backup (`POST /api/backups`)**
1. 產生時間戳 `YYYYMMDD_HHMMSS`
2. 暫存檔 `/tmp/asset_manual_${ts}.dump`
3. 執行 `pg_dump --format=custom --compress=9 --no-owner --no-acl`，stdout 重導至暫存檔
4. 執行 `rclone copy /tmp/asset_manual_${ts}.dump gdrive-crypt:backups/manual/`
5. 列出 `manual/` 內 `asset_manual_*.dump`（排除 `auto-pre-restore`），保留最新 5 份，刪除其餘
6. 刪除暫存檔
7. 回傳成功訊息

**List (`GET /api/backups`)**
1. 對 `daily/` `weekly/` `monthly/` `manual/` 各執行 `rclone lsjson --files-only`
2. 合併結果，標註 `folder` 欄位，依 `ModTime` 由新到舊排序
3. 解析檔名前綴判斷 `isAutoPreRestore`

**Restore (`POST /api/backups/restore`)**
1. 驗證 `confirmation === "確認還原"`，否則回 400
2. 執行手動備份流程（自救點），檔名 `asset_auto-pre-restore_${ts}.dump`，**不**做 5 份輪替
3. `rclone copy gdrive-crypt:backups/${folder}/${filename} /tmp/`
4. `pg_restore --clean --if-exists --no-owner --no-acl -h postgres -U $user -d $db /tmp/${filename}`
5. 刪除暫存檔
6. 回傳成功（不需重啟 backend，HikariCP 會自動重連）

### 安全

- 還原為破壞性操作，必須二次確認 + 自動先建自救點
- `pg_restore --clean` 期間 backend 對 DB 的請求會短暫失敗，前端 UI 加遮罩防止使用者誤觸
- 備份檔在 Google Drive 上由 rclone crypt 加密，檔名與內容皆不可讀
- 後端僅透過 ProcessBuilder 執行**白名單**指令，不接受使用者輸入拼接命令

### 前端

- 新增 `views/BackupRestoreView.vue`，路由 `/settings/backup-restore`
- `App.vue` 系統設定子選單追加項目
- `api/index.js` 新增 `backupApi.list() / create() / restore()`
- 還原成功後 `setTimeout(() => location.reload(), 1500)`，避免 stale store 殘留

## 認證與多租戶（Requirement 28）

### 拓樸（OAuth 落點在 BFF，非 backend）

```
瀏覽器 → frontend(Nginx) → bff(Spring Cloud Gateway / WebFlux reactive，唯一對外:8080)
                                → business-services(Spring MVC，內網不對外，靠 X-User-* header 取得身分)
                                → postgres / redis / external-materials-service
```

- 因 BFF 是唯一對外入口且 business-services 不接觸瀏覽器，**OAuth2 Login（Google 重導、session cookie）必須放在 BFF 的 reactive Security（`SecurityWebFilterChain` / `ServerHttpSecurity`）**。
- BFF 在呼叫下游時，把目前登入者（或管理者代看的目標）以 header `X-User-Id` / `X-User-Role` / `X-User-Status` 傳給 business-services。

### BFF 安全層

- `spring-boot-starter-security` + `spring-boot-starter-oauth2-client`；`application.yml` 設 `spring.security.oauth2.client.registration.google`（client-id/secret 走環境變數）+ `server.forward-headers-strategy: framework`。
- `SecurityWebFilterChain`：`/oauth2/**`、`/login/**`、health → permitAll；`/api/bff/backup-restore/**`、`/api/bff/user-management/**`、`/api/impersonate` → `hasAuthority("ROLE_ADMIN")`；其餘 `authenticated()`。未登入回 **401**（自訂 `authenticationEntryPoint`）而非 302。
- 自訂 reactive OIDC user service：登入取得 Google email 後呼叫 business-services `POST /internal/users/login-upsert`（upsert 並回 id/role/status），把 `ROLE_ADMIN`/`ROLE_USER` 與 `APP_UID_{id}`、`APP_STATUS_{status}` 一併灌成 authorities。**之後每個請求的身分（id/role/status）直接從登入 principal 還原（`BffUser.fromPrincipal`），不再每請求 round-trip business `by-email`**——避免延遲與「WebClient 完成執行緒接手寫回應」造成的 `setContentLength` on committed-response 問題。代價：role/status 為登入時快照，核准（PENDING→ACTIVE）後使用者需「重新登入」刷新（`/pending` 頁提供按鈕）。
- 登入成功 `ServerAuthenticationSuccessHandler` 固定 302 → 前端 `/`。
- CSRF：`CookieServerCsrfTokenRepository.withHttpOnlyFalse()`，前端從 `XSRF-TOKEN` cookie 取值放進 `X-XSRF-TOKEN`（axios 自動）。
- PENDING/DISABLED 攔截：`WebFilter` 對業務 `/api/**`（除 `/api/me`、`/logout`、`/api/impersonate`）若 `status != ACTIVE` 回 `403 {code:"ACCOUNT_PENDING"}`。
- header 注入：`TenantWebFilter` 從 principal 解析身分後，單次 `exchange.mutate()` 以 `set` 寫入 `X-User-*`（覆蓋 client 偽造值）並寫進 Reactor context；passthrough route 由 gateway 轉發該 request header，aggregation controller 的 `businessServicesClient` 由 `ExchangeFilterFunction` 從 context 取 `TenantIdentity` 補上 header。`MeController` 列使用者清單時則顯式帶管理者 header（不依賴 context 傳遞）。
- **過濾鏈只能訂閱一次（二次訂閱陷阱）**：`chain.filter(...)` 為 `Mono<Void>`，只發 onComplete 不發 onNext。若寫成 `flatMap(me -> chain.filter(...)).switchIfEmpty(chain.filter(...))`，整條 `flatMap` 會被 `switchIfEmpty` 誤判為 empty 而觸發，使同一 exchange 的過濾鏈被**第二次訂閱**——第一趟（已登入）已把回應 commit（200/204），第二趟在 response 已凍結後重跑，result handler 對唯讀 header 呼叫 `setContentLength` 即拋 `UnsupportedOperationException`（`/api/impersonate` 第二趟落到 `ResourceWebHandler` → 404；proxied SSE 則為 `Rejecting additional inbound receiver`）。修法：把「未登入」轉成 `defaultIfEmpty(ANONYMOUS)`（id=null 的哨兵）的 onNext，後續只有**一個** `flatMap` 呼叫 `chain.filter`，保證恰好訂閱一次。注意這與「巢狀 mutate」無關。

### business-services 身分與過濾

- `CurrentUserFilter`（`OncePerRequestFilter`）讀 `X-User-*` 填 request-scoped `CurrentUserContext`。
- 受隔離 entity 加 `@FilterDef(name="ownerFilter")`（定義於 `model/package-info.java`）+ `@Filter(condition="owner_user_id = :ownerId")`。create 流程以 `ctx.effectiveUserId()` set owner。
- **啟用點 `TenantFilterAspect`**：`@Before("execution(* com.steven.assets.repository..*(..))")` 在每次 repository 呼叫前，於目前 Hibernate session `enableFilter("ownerFilter")`。選 repository 層而非請求進入點，是因為此時已位於 service `@Transactional`（或 OSIV）綁定的 session 內，**不依賴 interceptor 與 OSIV 註冊順序**，過濾必定套用到實際執行的查詢（經實機驗證：帶 `X-User-Id` 不同值查 `/api/snapshots` 各自隔離）。
- **filter 僅在有 request context 時啟用**（aspect 以 `RequestContextHolder` 判斷）；背景 cron（`AlertNotificationDispatcher` / `StockAlertService.checkAlerts`）無 request context 故不啟用，照舊掃全體 alert、寄信給各 alert 自己挑的收件人。
- Hibernate `@Filter` 不套用於 `EntityManager.find()`（findById），by-id 存取另以 `TenantGuard.assertOwned(ownerUserId)` 驗證歸屬。
- ADMIN gate：輕量 `HandlerInterceptor` 讀 `X-User-Role`，對 `/api/backups/**`、`/internal/users/**`（管理）限 ADMIN（不引入整套 backend Security）。

### 管理者代看（effectiveUserId）

- effectiveUserId 由 BFF 決定：一般使用者 = 自己；管理者 = `IMPERSONATE_UID` cookie 指定目標（預設自己）。
- `POST /api/impersonate?userId={id}` 僅 ADMIN（SecurityConfig 限 `ROLE_ADMIN`）；**由 `TenantWebFilter` 攔截處理、不進 `@RestController`**：在 WebFilter 內（`chain.filter` 之前、response 尚可寫的視窗，與登入／登出清 cookie 同一視窗）`response.getHeaders().add(Set-Cookie, IMPERSONATE_UID)`+`setStatusCode(204)`+`setComplete()`，stateless 不碰 WebSession；省略 `userId` 或等於自己 → 清除 cookie（回到看自己）。**為何不放在 controller**：本 BFF 是 Spring Cloud Gateway，`@RestController` handler 執行時 response 已 commit、`getHeaders()` 唯讀，任何在 controller 內寫 Set-Cookie 的做法（`addCookie` / `ResponseEntity` / 直接 `getHeaders().add`）都會丟 `UnsupportedOperationException`，且因無 body 可降級 chunked、回應無法 start → **500**（回 body 的 GET controller 如 `MeController` 才能在 commit 後仍把 body 以 chunked 寫出、僅留良性雜訊）。`userId` 改走 query param 以便在 filter 直接讀取（不需在 filter 解析 JSON body）。`TenantWebFilter` 只在 `role=ADMIN` 時採信此 cookie，故非管理者自設無效、無需簽章。business-services 只信任 `X-User-*` header（對外那層已把關）。
- **每次登入／登出都清除 `IMPERSONATE_UID` cookie**：`SecurityConfig` 的登入成功 handler（302→`/` 前）與登出成功 handler 各寫一個 `maxAge=0`、屬性與寫入時一致（`path=/`、`HttpOnly`、`SameSite=Lax`）的 Set-Cookie。否則 cookie 會跨登出／登入殘留，管理者重新登入時誤帶上次代看目標、看到他人資料；清除後每次新登入都從「看自己」開始。

### API 端點（新增）

| 端點 | 提供者 | 權限 | 說明 |
|------|--------|------|------|
| `GET /oauth2/authorization/google` | BFF | 公開 | 觸發 Google 登入 |
| `GET /login/oauth2/code/google` | BFF | 公開 | Google callback |
| `GET /api/me` | BFF | 已登入 | 目前使用者 + 角色 + 狀態 + 可切換清單 |
| `POST /api/impersonate?userId={id}` | BFF（`TenantWebFilter` 攔截，非 controller） | ADMIN | 管理者代看切換（寫/清 `IMPERSONATE_UID` cookie） |
| `POST /logout` | BFF | 已登入 | 清 session |
| `GET /api/bff/user-management` 等 | BFF→business | ADMIN | 使用者管理 passthrough（rewrite → `/internal/users`） |
| `POST /internal/users/login-upsert` | business | 內部 | 登入 upsert + 回 role/status |
| `GET /internal/users` / `PATCH /internal/users/{id}/status` / `PATCH /internal/users/{id}/role` | business | ADMIN | 列出 / 核准·停用 / 設角色 |
| `GET /internal/users/by-email?email={email}` | business | 內部 | 依 email 即時查 id/role/status（登入 principal 為快照，核准後即時查詢備援；見上 L1595 設計說明） |

## Security Considerations

- CORS 設定於 `WebConfig.java`，限制允許的來源與方法
- 外部 API 金鑰（如有）透過環境變數注入，不寫死於程式碼
- 資料庫憑證透過 `.env` 檔案管理，不提交至版本控制
- 使用 BigDecimal 處理所有金融數值，避免浮點數精度問題
- 財務計算精度：20 位數，2-4 位小數
- 備份／還原 API 僅執行白名單指令，命令參數不接受使用者拼接
- 認證在 BFF（Gmail OAuth2），session cookie `HttpOnly`+`SameSite=Lax`+prod `Secure`；CSRF 以 cookie token 防護（Requirement 28）
- business-services 不對外，`X-User-*` header 信任建立於「compose 內網、BFF 為唯一入口」；business-services 暴露於外網即會被繞過（部署層保證）
- 多租戶：受隔離 entity 一律經 `ownerFilter` 過濾，管理者代看僅 ADMIN session 可變更 effectiveUserId，避免越權讀取他人資產

### 資安弱點修補（Requirement 29）

一次全系統資安審查後的修補，四項：

1. **Stored XSS（Dashboard tooltip）**：ECharts tooltip `formatter` 回傳字串以 raw HTML 渲染。新增 `frontend/src/utils/escapeHtml.js`，`DashboardView.vue` **六處** tooltip（資產分類細分 `fmtRow`/`fmtTooltip`、台股穿透、美股穿透、股票橫條 `stockBarOption`、基金橫條 `fundBarOption`、銀行存款橫條 `bankOption`）對持股股名／代號／英文全名／基金名／銀行名一律 `escapeHtml()` 轉義（後三處橫條圖 sink 為對抗式覆驗於同頁補抓）。威脅模型重點：股名／基金名為使用者自由輸入，未轉義時「使用者植入 `<img onerror>` → 管理者代看該使用者 → payload 於 admin session 同源執行 → 可打 admin API」構成提權，故此為 High。`label.formatter`（`{b}` 樣板、canvas 文字非 HTML）與只插數字/硬編碼系列名的 formatter 不受影響。
2. **全域共用設定寫入限 ADMIN**：共用參考資料（`bank`/`broker`/`deposit_type`/`market_type`/`asset_class`/`stock_style`/`bond_term`/`transit_fund_type`/`payment_category`、`stock` override，以及 `fund_master` 信託基金主檔）無 `owner_user_id`，原本任何 ACTIVE 使用者皆可 `POST/PUT/PATCH/DELETE` 竄改，影響全體。改為雙層授權：
   - **BFF `SecurityConfig`**：對前端可觸及的共用設定路徑（`/api/settings/**`、`/api/funds(/**)` + 各 `/api/bff/*-settings/**`）之寫入方法（POST/PUT/PATCH/DELETE）限 `ROLE_ADMIN`；GET 落到 `authenticated()`。`GLOBAL_SETTINGS_PATHS` **刻意排除** per-user 的 `/api/bff/payment-account-settings/accounts/**` 與 `/api/bff/notification-settings/recipients/**`。
   - **backend `AdminGateInterceptor`**（縱深防禦，攔截器掛 `/api/settings/**` 與 `/api/funds(/**)`）：非唯讀方法（非 GET/HEAD/OPTIONS）要求 `X-User-Role=ADMIN`，讀取放行。因 per-user 資料 base path（`/api/payment-accounts`、`/api/notification-recipients`）與操作型 `/api/fund-nav`、`/api/fund-dividend` refresh/backfill 不在受管路徑之下，故此規則精準不誤傷。`fund_master` 授權遺漏為對抗式覆驗補抓（寫入端點在 `/api/funds` 而非 `/api/settings`）。
3. **外部行情參數白名單**：所有「使用者可控且流入外部行情 client」的參數做白名單（見 Requirement 29 AC）——`MarketDataController`（code/market/currency，`@Validated`+`@Pattern`，涵蓋 BFF stock-analysis rewrite 至 `/api/market-data/*`）、`StockAlertController.lookup-name`（code/market 同規則）、`MacroHistoryController.index-intraday`（market 以已知指數集合白名單）。阻擋 `&`/`?`/`#`/路徑穿越注入（污染共用行情表）。違規 → `ConstraintViolationException`／`IllegalArgumentException` → `GlobalExceptionHandler` 回 400。host 皆硬編碼故非任意 SSRF，此為參數注入強化；後兩條路徑為對抗式覆驗補抓。
4. **個資／本機設定不入版控**：`db/init/*.sql`（真實 seed dump）、`*.mv.db`/`*.trace.db`、`/data/`、`backend/data/`、`stock_alert_data.sql`、`.claude.bak/` 移出版控並列入 `.gitignore`（本機檔保留、部署 seed 不受影響）。git 歷史仍含既有資料，屬遺留風險，如曾外流需另清史。

---

### 延後低風險資安項修補（Requirement 30）

Requirement 29 高風險項上線後，處理當時評估為低風險而延後的五項：

1. **通知收件人跨租戶綁定（IDOR）**：`stock_alert_recipient` 為「以 Long id 顯式關聯」的 join entity，無 `owner_user_id`、無 `@Filter`，故 `recipientLinkRepo.save()` 是純 insert、不受 `TenantFilterAspect` 的 `ownerFilter` 覆蓋。`StockAlertService.replaceRecipients()` 原本把前端 `recipientIds` 當純數字直插，繞過多租戶兩道防線（`@Filter` 查詢過濾 + `TenantGuard.assertOwned` by-id 補驗）。修法：在 `replaceRecipients` 寫入前，用 `NotificationRecipientRepository.findByIdIn(ids)`（`NotificationRecipient` 掛 `@Filter`，此派生查詢在 HTTP 請求執行緒下必被 `ownerFilter` 限縮成只回當前租戶）取得合法 id 白名單，只寫入交集；他人 id 自然查不到而被濾除。收斂在 `replaceRecipients` 內、涵蓋 create/update；保留「`null`＝全部自己的收件人、空 list＝不寄」語意。dispatcher 端 `findActiveEmailsByAlertId`（背景寄信 cron 呼叫、無 request context 故 `ownerFilter` 不啟用，且 join entity 無 owner 欄位／`@Filter`）已補上讀取端 defense-in-depth（Task 145）：查詢額外 join `StockAlert a` 並加 `r.ownerUserId = a.ownerUserId` 條件，即使 join 表殘存修補前遺留的跨租戶列也不會寄到他人租戶 email；另以一次性 Liquibase changeset `v1.36.0-stock-alert-recipient-cross-tenant-cleanup` 刪除 `stock_alert_recipient` 中 `notification_recipient.owner_user_id <> stock_alert.owner_user_id` 的殘列（`DELETE … USING`，冪等，無殘列刪 0 列）。寫入端把關（`replaceRecipients` 過濾）與讀取端 owner 條件互補，正當同租戶收件人寄信行為不變。
2. **Session／代看 cookie 可條件化 `Secure`**：BFF 為 WebFlux，cookie 以 `ResponseCookie` 產生。三處建構點——`application.yml` 的 `server.reactive.session.cookie.secure`（承載登入態的 `SESSION` cookie）、`TenantWebFilter` 寫入／清除 `IMPERSONATE_UID`、`SecurityConfig.clearImpersonateCookie()`——統一由環境變數 `SESSION_COOKIE_SECURE`（預設 `false`）控制 `.secure(...)`。設計取捨：專案現況零 Spring profile、`docker-compose` 未傳 `SPRING_PROFILES_ACTIVE`，故用單一 env 開關（改動最小、預設安全）而非新增 profile。目前 prod 的 `frontend/nginx.conf` 仍只 listen 80（純 http），因此 compose 預設 `SESSION_COOKIE_SECURE=false`；待外層上 TLS（`forward-headers-strategy: framework` + `X-Forwarded-Proto: https` 已就緒）後把該 env 設 `true` 即帶 `Secure`。三處務必同源同值，否則 `clearImpersonateCookie` 因屬性不符清不掉殘留代看 cookie（`SecurityConfig` 既有註解已警示）。
3. **PostgreSQL 不對外網暴露**：`docker-compose.yml` postgres port `"5432:5432"`（`0.0.0.0`）收斂為 `"127.0.0.1:5432:5432"`，僅本機 loopback 可連、外部網卡不再暴露。容器間仍以 service name `postgres:5432` 走 `asset-net`（`DB_HOST=postgres` 由 compose 注入），不依賴 host port mapping。命名 volume `asset-postgres-data` 不受 recreate 影響。
4. **DGBAS 抓取恢復 TLS 驗證**：`ws.dgbas.gov.tw` 的 leaf 由 `TWCA Secure SSL Certification Authority` 簽發，但伺服器**漏送該中繼憑證**，容器 truststore 無法建鏈（Java／curl 預設皆 PKIX 失敗）——這是伺服器端設定錯誤，非用戶端缺根。原以 `curl -k` 全域停用驗證（連主機名都不驗）規避。修法：把公開可得（AIA `http://sslserver.twca.com.tw/cacert/secure_sha2_2023G3.crt`、由公信根 `TWCA Global Root CA` 簽發）的中繼憑證打包為 `external-materials-service/src/main/resources/certs/twca-secure-ssl-ca.pem`，以它為信任錨建一個**專屬 `SSLContext`／`HttpClient`**（`TrustManagerFactory` PKIX，leaf → 中繼-anchor 建鏈成立，`openssl verify -partial_chain` 已驗證等效），`fetchDgbasNationalIncome` 改用此 `HttpClient` 抓取、移除 `curl -k`；主機名驗證維持啟用（leaf SAN 含 `ws.dgbas.gov.tw`）。此 `SSLContext` 只用於 DGBAS，不影響 IMF/Yahoo/TWSE 等其他抓取（後者續用 curl 短 UA 規避 WAF，本就無 `-k`）。失敗維持回空 map → IMF fallback。中繼憑證效期至 2030-10，換版需更新此檔。
5. **Excel 匯入清空 owner-scoped**：`ExcelImportService.importRealizedGains()` 匯入前 `gainRepo.deleteAll()` 改為 `gainRepo.deleteByOwnerUserId(tenantGuard.requireCurrentUserId())`（`RealizedGainRepository` 新增衍生刪除方法），只清當前使用者、不依賴 `ownerFilter` 對 `deleteAll()` 的隱性副作用；無身分時跳過清空避免誤刪。此 service 目前無 controller 呼叫（不可達），屬防禦性修補。

---

## Requirement 24 擴充：英股市場類型（LSE UCITS ETF）

### external-materials-service 抓價

- `MarketClock` 加 `LON_ZONE = ZoneId.of("Europe/London")`、`isUkMarketOpen()`（週一～五 08:00–16:30，BST/GMT 由 JVM 處理）、`isUkMarketJustClosed()`（16:30–16:50）
- `PriceFetchClient`：
  - `getStockPrice(code, market)` 加 `英股` 分支 → `getYahooLsePrice(code)`：打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}.L?interval=1d&range=1d`，透過 `curlGetWithRetry` 避開 Yahoo Java HTTP fingerprint 偵測；`meta.regularMarketPrice` 為 live、`meta.chartPreviousClose` 為昨收，回 `PriceResult(market="英股", source="Yahoo")`
  - `fetchUkHistoricalRange(code, start, end)`：對 `{code}.L` 打 Yahoo `chart` API，timezone `Europe/London`
- `PricePoller.scheduledUkIntradayUpdate`：cron `0 0/2 8-16 * * MON-FRI` zone `Europe/London`；`warmCacheOnStartup` / `refreshAll` 加英股一輪；`RefreshSummary` 加 `ukUpdated` / `ukMarketOpen`
- `StockSourceQuery` 的 `collectAllStockCodes` / `collectHeldStockCodes` / `collectAllHeldCodes` signature 由 `(twCodes, usCodes)` 改為 `(twCodes, usCodes, ukCodes)`；分流邏輯改用 if-else if-else（依市場字串）
- `ClosePersister`：
  - `dumpUkCloseFromRedis()` cron `0 32 16 * * MON-FRI` zone `Europe/London`，仿台股 13:32 / 美股 16:02 dump pattern
  - `verifyUkCloseWithYahoo()` cron `0 0 17 * * MON-FRI` zone `Europe/London`，逐檔呼叫 `priceFetch.fetchUkHistoricalRange(code, today, today)` 取單日收盤覆寫 DB 與 Redis（英股無 FinMind 對應，校正改用 Yahoo historical）
  - `selfHealMissedClose()` 加倫敦時區分支
- `HistoricalBackfillService.backfillUkStock(code, since, until)`：仿 `backfillUsStock` 走 Yahoo `chart`；今日 bar 一律 skip（沿用 Task 84「今日列獨佔給 ClosePersister」）；`startupBackfill` / `backfillAll` / `backfillSingleStock` 加英股分支

### business-services

- `DataInitializer.seedMarketTypes()` 加 `("英股", "英國股市", 3)`
- `StockPriceService`：
  - 加 `LON_ZONE`、`isUkMarketOpen()`
  - `getLiveAssets()` 對 `market="英股"` 持倉套用 `usdExchangeRate` 換算台幣（與美股同 path；CSPX.L USD 計價）
  - `getMarketStatus()` 多回 `ukMarketOpen` / `ukTime`；`manualRefresh()` 多回 `ukMarketOpen`；`LiveAssetsResponse` 多 `ukMarketOpen`
- 新增共用 helper `com.steven.assets.util.MarketZones.resolve(market)`：`美股 → America/New_York`、`英股 → Europe/London`、其餘（含 `台股`、`0000`）→ `Asia/Taipei`。`TechnicalIndicatorService` / `WatchStockService` / `StockAlertService` / `HistoricalDataService` 共用。後續再把開收盤時刻與「是否開盤」判斷也收斂進 `MarketZones`：`openTime/closeTime`（供 `StockAlertService.computeTriggeredAt/matchInDailyOhlc`、`AlertNotificationDispatcher` 寄送時段閘門共用）與 `isMarketOpen(market)`（市場時區、平日、`open ≤ now ≤ close`，無寬限分鐘）；`StockPriceService` 三個 `isXxMarketOpen()` 改委派 `MarketZones.isMarketOpen`、`getMarketStatus()` 時區改引用 `MarketZones.*_ZONE`，移除自帶的 `TW_ZONE/US_ZONE/LON_ZONE` 與硬編開收盤（純去重、行為不變）
- `StockAlertController.lookupName`：`0000 + 英股` 直接回空字串（同 `0000 + 美股`）
- `StockAlertService.assertNameMatchesCode`：英股 canonical name 走 `MarketDataFetchService.fetchUkStockName(code)`（Yahoo `chart meta.shortName` for `{code}.L`）
- `MarketDataService` / `MarketDataFetchService`：`getDividendRate("英股", code)` 走 Yahoo `chart?events=div` 對 `{code}.L`；`getEtfHoldings("英股", code)` 呼叫 Yahoo `quoteSummary?modules=topHoldings` 對 `{code}.L`；`isEtf("英股", code)` 採白名單 `[CSPX, VWRA, VUSA, EIMI, IWDA]`

### 前端

- `StockAnalysisDialog.vue`：`isEtf` / `etfExternalLinks` 加英股分支（iShares 官網）
- `DashboardView.vue`：頂部資產彙整列加「英股現值」欄；資產配置圓餅圖 5 區擴為 6 區（加「英股」）；KPI / 即時資產估算對英股套 USD 匯率
- `AssetHistoryView.vue`：歷年資產表格加「英股」欄；今日列以 live-assets 覆寫的邏輯涵蓋英股
- `SnapshotDetailView.vue`：英股小數位 5 位、currency `USD`（與美股同）
- `SnapshotFormView.vue`：股票區塊加英股區，預設 `currency='USD'`
- `WatchStockView.vue` / `StockAlertView.vue`：tab 加「英股」、觸發時間時區後綴 `LON`
- `TradingCalendarView.vue`：英股開收盤狀態（讀 `getMarketStatus()` 的 `ukMarketOpen` / `ukTime`）
- `RealizedGainView.vue`：market 篩選 / 新增表單加「英股」

### 不在本次範圍

- GBP 匯率體系（CSP1.L 等 GBP-denominated UCITS 才需要）
- LSE holiday calendar（用週末 + 時段判斷已足夠）
- Excel 匯入英股欄位
- iShares 官方 ETF 持股 scrape（先依靠 Yahoo `topHoldings`）

---

## Task 129：ETF 透視 top10 成份股歷史回補（Requirement 9 / Requirement 7）

### 問題

資產配置圓餅圖「台股個股 / 美股個股」tab 把持有的 ETF 穿透成個股並開放點擊看走勢（Requirement 9）。但純穿透成份股（如 `2317` 鴻海，使用者只持有 0050 而非直接持有 2317）**同時缺席於全部歷史價格寫入觸發點**：
- 不在 `stock` 主檔（主檔僅由 `StockMasterService.upsert` 寫入：直接持股 / 觀察 / 警示）
- 不在 `stock_holding`（使用者持有的是 ETF，非成份股）
- 多數不在 `stock_alert`

`StockSourceQuery.collectAllStockCodes`（即時抓價）、`collectHeldStockCodes`（盤中）、`collectAllHeldCodes`（10 年歷史回補）三條收集路徑都查不到它 → `stock_price_history` 無資料 → `StockAnalysisDialog` `history.length === 0` → 顯示「無歷史資料，請先執行股價補齊」。

### 設計（全部落在 external-materials-service，不動 BFF / business / `stock` 主檔）

新增第 4 條回補來源「ETF 透視 top10 成份股」，與圓餅圖顯示的 segment 對齊：

- `StockSourceQuery.collectLatestSnapshotHoldingsWithValue()`：讀最新快照 `stock_holding` 的 `(stock_code, market, current_value)`（透視加權用市值）。
- `HistoricalBackfillService.collectLookthroughTopConstituents(twCodes, usCodes)`：以上述持股重算台股 / 美股透視 top10，演算法**鏡像 BFF `buildLookthrough` / `buildUsLookthrough`**（單一事實原則 — 成份股來源同樣是 ext-materials `MarketDataFetchService.getEtfHoldings`，12h cache，與 BFF 經 business 代理打的是同一份資料）：
  - 台股：ETF（`code` 以 `00` 開頭）→ `getEtfHoldings(code,"台股")` → `share = cv × weight / Σweight`（正規化）；直接持股整筆計入；**以代號加總**（成份股代號由 MoneyDJ 名稱經「股名→代號」字典補齊，`twNameToCodeMap()` 首次呼叫同步載入，故啟動時也有代號）；取前 10 → `twCodes`。無代號的成份股略過（無法回補）。
  - 美股：每檔美股 row → `getEtfHoldings(code,"美股")`；回 holdings（ETF）→ `share = cv × weight/100`（不正規化）以代號加總；回空（個股，`isEtf` 白名單短路）→ 整筆計入該代號；取前 10 → `usCodes`。未揭露尾段（「其它」）非可點擊代號，不回補。
  - 英股無透視 tab，略過。
- 併入既有回補：`startupBackfill()` 與 `backfillAll()` 在 `collectAllHeldCodes(...)` 後呼叫 `collectLookthroughTopConstituents(twCodes, usCodes)`（try/catch 包覆，失敗不影響主流程），後續 for-loop 沿用既有 `backfillTwStock` / `backfillUsStock`（`maxDate==null` → 補滿 10 年；今日列仍獨佔給 `ClosePersister`）。已在主檔 / 持股的代號（直接持股、ETF 自身）落入 top10 時被 `existsHistory` / `maxDate` 條件自然 skip，無重複。
- 每日新鮮度：`@Scheduled(cron="0 30 18 * * *", zone="Asia/Taipei") dailyLookthroughBackfill()`（virtual thread）重算 top10 並**增量**補（`maxDate+1 → today`）。成份股**不**納入即時抓價集合 `collectAllStockCodes`（避免 Redis 盤中輪詢爆量 — 使用者點成份股看的是長期走勢非當下 tick），故靠此 cron 跟上每日收盤；新進榜成份股（ETF 成份變動）`maxDate==null` 補滿 10 年。

### 為何不寫入 `stock` 主檔 / 不另建表

- 使用者要求「`stock` 主檔盡可能小，只含系統各功能畫面會看到的股票」。主檔是 `collectAllStockCodes` 的即時抓價清單，灌入幾十～上百檔成份股會造成盤中抓價爆量（沿用 line 178 既有原則）。
- 不另建 `analyzable_stock` 表：top10 透視集合可由「最新快照持股 + getEtfHoldings(12h cache)」即時重算，量小（≤ 約 20 檔）、無需持久化清單；回補結果本就落在 `stock_price_history`，查詢路徑（`/api/market-data/history/stock`）零改動。

### 限制

- 僅收 top10（畫面上可點擊的 segment）；第 11 名以後（圓餅「其它」）不可點擊故不回補。
- 台股成份股若 MoneyDJ 名稱補不到代號 → 不回補也不開放點擊（圓餅圖該段本就無 `code`）。
- 股利 tab（`getDividendHistory`）對成份股維持 best-effort cold fetch，不納入強制回補。

---

## Task 130：警示防重複條件（Requirement 23）

### 問題

警示清單可出現兩筆「完全相同」的警示（如 NVDA「低於年線」），重複寄信、佔版面。使用者要求：存檔時若已存在相同條件，跳通知訊息表示「已經有了」、不再存一份。

### 唯一鍵

「完全相同的警示條件」= `(stockCode, market, alertType, maPeriod, threshold)`。`active` / `recipientIds` 不納入（重複僅以觸發規則判定）。比較細節：`stockCode` 正規化 `trim().toUpperCase()`；`threshold` 以 `BigDecimal.compareTo` 比較（避免 `0` vs `0.0000` scale 差異）；`maPeriod` 以 `Objects.equals` 容許 null（PRICE_/KD_ 類型 maPeriod 為 null）。

### 後端（`StockAlertService`）

- 新增 `assertNoDuplicate(code, market, alertType, maPeriod, threshold, excludeId, stockName)`：以既有 `alertRepo.findByStockCodeAndMarket(code, market)` 取同股同市場警示，逐筆比對唯一鍵（`excludeId` 用於 `update` 排除自身），命中則丟 `IllegalArgumentException`「已存在相同的警示條件（{股名} {buildLabel(既有筆)}），未重複新增」。
- `create()`：在 `assertNameMatchesCode` 後、`save` 前呼叫（`excludeId=null`）。
- `update()`：在 `assertNameMatchesCode` 後呼叫（`excludeId=id`）—— 把某筆改成與另一筆相同也擋。
- 不加 DB unique constraint：現存已有重複列（升級時 Liquibase 加 constraint 會失敗），且 app 層丟出的是友善訊息（400），DB constraint 會是 500 DataIntegrityViolation。app 層檢查即足。

### 前端：錯誤改用 dialog 呈現（使用者要求，非頂部 toast）

使用者反映頂部滑出的 `ElMessage` toast「看起來像系統錯誤」，要求存檔錯誤改用 dialog。但全域 axios 攔截器（`api/index.js`）對**所有**被 reject 的回應一律 `ElMessage.error`，且早於 view 的 catch 執行 → 不抑制就會「toast + dialog」雙重顯示。

- `api/index.js`：攔截器加 `if (!err.config?.skipErrorToast)` 條件 —— 呼叫端在 axios config 帶 `skipErrorToast:true` 即可自行處理錯誤呈現；並匯出 helper `apiErrorMessage(err, fallback)`（取 `ProblemDetail.detail` 優先）。
- `bffApi.stockAlert.create` / `update`：第三參數帶 `{ skipErrorToast: true }`，使存檔錯誤不走全域 toast。
- `StockAlertView.save()` 的 `catch`：改用 `ElMessageBox.alert(apiErrorMessage(e, '儲存失敗'), '無法儲存警示', { type:'warning', confirmButtonText:'我知道了' })` 顯示 dialog（涵蓋重複條件、名稱不符等所有存檔錯誤）；`.catch(()=>{})` 吞掉關閉 reject。`dialogVisible` 維持開啟讓使用者修改。

### 不處理

既有歷史重複列不自動刪除（資料異動需使用者意圖；清單已有刪除鈕）。

---

## Task 136：股票分析對話框無歷史時 lazy 回補（Requirement 9 / Requirement 7）

### 問題

Task 129 把「ETF 透視 top10 成份股」（如 `2383` 台光電，使用者只持有含它的 ETF，未直接持有）納入 10 年歷史回補，但觸發點只有三個：`startupBackfill`（服務啟動）、手動 `backfillAll`、每日 `dailyLookthroughBackfill` cron（18:30 Asia/Taipei）。**缺「開啟分析即補」的即時觸發** —— 當成份股新進 top10（ETF 成份變動 / 使用者新買含它的 ETF / 切換快照），或使用者在兩次 cron 之間就點開該成份股的 `StockAnalysisDialog`，`stock_price_history` 尚無資料 → `history.length === 0` → 走勢圖空白、顯示「無歷史資料，請先執行股價補齊」（即本需求實機情境）。

這與 Task 126 為 `stock` 主檔標的修的「兩次重啟間新增 → 即時價有、歷史空」空窗同型，但主檔走 `StockMasterService.upsert` 的即時背景佇列補；成份股**刻意不入主檔**故不經該路徑，需在 **viewing 時**補。

### 設計（lazy backfill：viewing 時觸發，不入主檔）

使用者決策：**自動背景補齊後重載**（非按鈕）、範圍**只在開啟分析對話框時**（lazy，涵蓋所有「無歷史」情況，非只 top10 成份股）。

- **BFF**（`StockAnalysisBffRoutes`）：新增 passthrough route `stock-analysis-backfill` —— `POST /api/bff/stock-analysis/backfill-stock` rewrite 至 business `/api/market-data/history/backfill-stock`（沿用既有端點，`since` 省略時後端預設 `now−10y`）。RouteLocator 純轉發，保留 method（POST）與 query string。**不**新增 business 端點 —— 與 `SnapshotFormBffController.triggerBackfillThenRefetch` 走同一支 business API（同義同源）。
- **前端**（`StockAnalysisDialog.vue` `fetchHistory()`）：第一次 `getStockHistory` 回空陣列時，設 `backfilling=true`、`await bffApi.stockAnalysis.backfillStock(code, market)`，完成後**重抓一次** `getStockHistory` 填入 `history`。`loading` 全程維持 true，loading 文案於 `backfilling` 時切為「首次載入，補齊 10 年歷史中…（約需數秒）」。補完仍空才落到既有「無歷史資料」空狀態。
  - **守門**：台股大盤 `0000`（`stockCode==='0000' && market==='台股'`）不觸發回補（歷史走 `twse_index_daily_history`，比照 `StockMasterService.isTaiex`）；回補呼叫失敗只 `console.warn`、不阻斷既有空狀態顯示。
- **api**（`api/index.js` `bffApi.stockAnalysis`）：新增 `backfillStock(code, market)` → `api.post('/bff/stock-analysis/backfill-stock', null, { params:{ code, market } })`。

### 不變量（沿用 Task 129 / Task 84）

- **不入主檔**：`/api/market-data/history/backfill-stock` → `HistoricalDataService.backfillSingleStock` → ext `HistoricalBackfillService.backfillSingleStock` → `backfillTw/Us/UkStock` → `StockSourceQuery.upsertHistory`，全程**只寫 `stock_price_history`**，從不呼叫 `StockMasterService.upsert` / `StockRepository.upsert`，故成份股不會被灌進主檔即時抓價清單（`collectAllStockCodes`）。
- **今日列獨佔**：回補 for-loop 仍 skip `bar.tradingDate()==today`（Task 84），今日列由 `ClosePersister` 寫入。
- **idempotent**：`existsHistory` skip 已存在日期，重複開啟對話框安全（已補過則第二次 `getStockHistory` 即有資料、不再觸發回補分支）。

### 不處理

- 不做 eager 觸發（Dashboard 透視圓餅圖算出 top10 時背景補）—— 使用者選 lazy；steady-state 仍由每日 18:30 cron 維護。
- 股利 tab 維持 best-effort cold fetch（Task 129 限制不變）。

## Requirement 31：今日股市分析（每交易日 07:30 AI 判斷台股走向）

### 概觀

每個台股交易日開盤前（07:30 Asia/Taipei），business-services 呼叫 Claude Opus 4.8 一次，綜合「台股大盤＋美股主要指數近一年日線走勢」（本地 DB）與「近期國內外財經新聞」（模型 `web_search` server tool 即時搜尋），對當天台股走向做多空判斷並存入 `daily_market_analysis`。前端「今日股市分析」頁顯示當日判斷＋歷史。此為**全域參考資料**（不分租戶、無 `owner_user_id`，比照指數日線／交易日曆）。

### 架構與模組落點

- **唯一新增的對外 LLM 呼叫在 business-services**：`market-analysis` 模組直接呼叫 `api.anthropic.com`（Anthropic Java SDK `com.anthropic:anthropic-java`）。此非「行情／報價外部 API」，不違反「即時股價走 Redis、收盤價走 DB、business-services 不直連外部行情 API」之規範（該規範針對 price/quote 資料）；business-services 本就有對外 egress（Gmail SMTP、rclone 備份）。
- **新聞不自建抓取管線**：使用者決策採 Claude 內建 `web_search`，故 `external-materials-service` 不變、不新增 `financial_news` 表。走勢量化輸入沿用既有 `twse_index_daily_history` / `us_index_daily_history`（同一事實來源）。
- **BFF 一頁一支**：新增 `TodayMarketAnalysisBffController`（`/api/bff/today-market-analysis/**`），聚合「當日 + 歷史」單次回傳，前端只 render。

```
Scheduler(07:30 MON-FRI, Asia/Taipei) ──isTwTradingDay?──▶ MarketAnalysisService.generate(today)
                                                                │  讀 twse_index_daily_history / us_index_daily_history（近一年）
                                                                │  組 prompt（近期加權）
                                                                ▼
                                                     Anthropic Messages API（Opus 4.8 + adaptive thinking + web_search）
                                                                │  模型上網搜尋近期財經新聞 → 產生 JSON 判斷
                                                                ▼
                                                     解析 JSON → upsert daily_market_analysis
前端 TodayMarketAnalysisView ──▶ /api/bff/today-market-analysis ──▶ business /api/market-analysis/{today,history}
（管理者）重新分析 ──▶ POST /api/bff/today-market-analysis/generate（限 ADMIN）──▶ business POST /api/market-analysis/generate
```

### 資料模型（Liquibase `v1.37.0-daily-market-analysis.sql`）

```sql
CREATE TABLE daily_market_analysis (
    analysis_date    DATE          PRIMARY KEY,      -- 被分析的交易日（= 產生當日）
    bias             VARCHAR(16),                    -- BULLISH / BEARISH / NEUTRAL / UNKNOWN
    confidence       INTEGER,                        -- 0..100，可為 null
    summary          TEXT,                           -- 當日走向總結（繁中一段）
    key_factors      TEXT,                           -- JSON array 字串
    news_highlights  TEXT,                           -- JSON array 字串：[{title,source,url,publishedAt}]
    tw_context       TEXT,                           -- 台股近期走勢摘要
    us_context       TEXT,                           -- 美股近期走勢摘要
    model            VARCHAR(64),                    -- claude-opus-4-8（或設定切換之模型）
    status           VARCHAR(16)   NOT NULL,         -- OK / FAILED / NOT_CONFIGURED / PROCESSING
    error_message    TEXT,                           -- status=FAILED 時的錯誤摘要
    raw_response     TEXT,                           -- 模型原始回覆（除錯用）
    generated_at     TIMESTAMPTZ   NOT NULL          -- PROCESSING 期間＝批次送出時間（供 poller 逾時判斷）
);
-- v1.42.0：改用 Batch API（非同步）新增 batch_id（在製批次 id，收尾後清 null）
ALTER TABLE daily_market_analysis ADD COLUMN batch_id VARCHAR(64);
```

- Entity `DailyMarketAnalysis`（無 owner 欄位、無 `@Filter`，不受 `TenantFilterAspect` owner 過濾）；`key_factors`／`news_highlights` 以 JSON 字串存，Controller 回前端時解析回陣列。
- **schema 基準線**：新表走 Liquibase 增量（比照 `v1.35.0-japan-gdp`）；`db/init/01_dump.sql` 為既有表基準線、不含此新表，由 Liquibase 於 business-services 啟動時建立（`ddl-auto: none` + Liquibase）。

### API 設計

business-services（`MarketAnalysisController`，`/api/market-analysis`）：

| Method | Path | 說明 |
|---|---|---|
| GET | `/api/market-analysis/today` | 最近一筆分析（DTO：含解析後的 keyFactors / newsHighlights 陣列）；無資料回 `{status:"NONE"}`。已登入者皆可讀。 |
| GET | `/api/market-analysis/history?limit=30` | 近 N 筆（`analysis_date` 降序）。 |
| POST | `/api/market-analysis/generate` | 送出當日批次分析並**立即回傳 `PROCESSING` 列**（Batch API 非同步；結果由 poller 收尾）。縱深防禦：`CurrentUserContext.isAdmin()` 否則 `AdminRequiredException`。 |

BFF（`TodayMarketAnalysisBffController`，`/api/bff/today-market-analysis`）：

| Method | Path | 說明 |
|---|---|---|
| GET | `/api/bff/today-market-analysis?historyLimit=30` | `Mono.zip` 聚合 business `today` + `history`，單次回 `{today, history}`。 |
| POST | `/api/bff/today-market-analysis/generate` | 轉發 business `generate`；bff `SecurityConfig` 對此 POST 限 `AUTHORITY_ADMIN`。timeout 180s（web search + thinking 可能耗時）。 |

### 關鍵業務邏輯

- **排程**（`MarketAnalysisScheduler`）：`@Scheduled(cron="0 30 7 * * MON-FRI", zone="Asia/Taipei")` → `today=LocalDate.now(TW_ZONE)`；`marketDataService.isTwTradingDay(today)` 為假則 log skip return。**開機 self-heal**：`@EventListener(ApplicationReadyEvent.class)` 延遲後，若 today 為交易日、現在已過 07:30、且今日尚無 OK 筆 → 補跑一次（背景執行緒，比照 `IndexDailyRefreshScheduler`）。背景 cron 無 HTTP request → `CurrentUserContext`（`@RequestScope`）取不到，不套 owner 過濾（本就全域）。
- **提示詞與近期加權**（`MarketAnalysisService.buildUserPrompt`）：TAIEX 近一年日線（date,close，由舊到新）＋近 20 日另附；美股 `DJI/SPX/IXIC/SOX` 近一年日線同格。system prompt 指派「資深台股策略分析師」角色、要求先（多次、換多組中英文關鍵字積極）`web_search` 最近幾日（≤`newsMaxAgeDays` 天）財經新聞、**越近期的走勢與新聞權重越高**、**新聞來源只採台/美/日/星、嚴禁中港澳**（台/美/星 → 台/美/日/星，Task 149.19 放寬納入日本；嚴禁中港澳沿用 Task 149.18）、並提示可優先參考的可靠來源（台灣證券交易所 `twse.com.tw`／公開資訊觀測站 `mops.twse.com.tw`／日經中文網 `zh.cn.nikkei.com`／自由時報 `ec.ltn.com.tw`／經濟日報 `money.udn.com`／華爾街日報中文網 `cn.wsj.com`／紐約時報中文網 `cn.nytimes.com`，屬引導例示、非硬白名單——刻意不用 `web_search` 的 `allowed_domains` 以免縮小涵蓋）、最後**只輸出 JSON**（schema：`bias/confidence/summary/keyFactors[]/newsHighlights[]/twContext/usContext`）、全程繁體中文。日本來源本就不在 `isRegionBlocked` 封鎖清單（只擋中港澳），且封鎖用 `host.endsWith`／整段網域比對，日經 `zh.cn.nikkei.com` 的中段 `.cn` label 不會被 `.cn` 後綴誤殺——放寬純屬提示詞層、封鎖清單不動。
- **模型呼叫（改用 Batch API，非同步）**：`claude-opus-4-8`（或設定切換之模型）、`ThinkingConfigAdaptive`、`OutputConfig.effort`（思考深度）、`WebSearchTool20250305`（**基本版**，`maxUses` 可調，`0` 則不加此 tool＝關閉新聞搜尋）——後三者皆見「模型頁面可調」、`maxTokens≈8000`。**⚠️ 批次下務必用基本版 `WebSearchTool20250305`，不可用動態過濾版 `WebSearchTool20260209`**：後者底層以 `code_execution` 沙箱做動態過濾，而 code_execution 在 Message Batches API 下 `detection_timeout`（90s，`return_code=1`）→ 搜尋鏈斷 → 模型放棄 → `newsHighlights` 恆空（Task 149.20，實測「動態 vs 基本」雙請求診斷批次證實）。基本版不走 code_execution、結果直接進 context、批次可用。`PortfolioAdviceService` 的背景產生流程不受影響，仍用動態版。請求以 **Message Batches API**（`client.messages().batches()`）送出，省 50% token 成本（同 prompt／同工具，準確度不變）。收結果時收集回覆中所有 `text` 區塊（忽略 `server_tool_use`/`web_search_tool_result`），取首個 `{` 至末個 `}` 以 Jackson 解析（`@JsonIgnoreProperties(ignoreUnknown=true)`）。
- **Batch 非同步流程**（`MarketAnalysisService.submitBatch` / `pollPendingBatches`）：`generateInternal` 於鎖內先查當日列——已 `PROCESSING` 即不重複送出（排程與手動皆然，避免重複花費）；否則 `submitBatch`：組 `BatchCreateParams.Request.Params`（1 request，`customId="ma-"+date`）→ `batches().create` → 落 `status=PROCESSING`＋`batch_id`＋`generated_at=now`（送出時間）→ 立即返回。**背景 poller** `MarketAnalysisScheduler.pollBatches`（`@Scheduled(fixedDelay=90s, initialDelay=60s)`，**不受 `enabled` 限**）→ `pollPendingBatches()` 撈 `findByStatus(PROCESSING)`；逐列 `batches().retrieve`：非 `ENDED` 則等下輪（超過 `BATCH_MAX_AGE=12h` 判 FAILED 逾時保護）；`ENDED` 則 `resultsStreaming` 以 `customId` 取本列結果，`isSucceeded()` → `asSucceeded().message()` 解析落 `OK`、其餘（errored/canceled/expired）落 `FAILED`，並清 `batch_id`。retrieve/results 暫時性例外不改狀態、下輪重試（逾時才判 FAILED）。**ENDED 判定務必以 `processingStatus().value()`（其巢狀 `Value` 才是真 Java `enum`）與 `ProcessingStatus.Value.ENDED` 比較，不可對 `ProcessingStatus` 本體用 `==`/`!=`**——它是 SDK enum-like 值類別（覆寫 `equals`、反序列化每次產生新實例），參考比較恆「不相等」會讓 poller 永遠認不出已完成的批次而空轉到 12h 逾時（Task 149.16 修正）。
- **參考新聞時效驗證**（`MarketAnalysisService.sanitizeNews`，Task 149.17 bug fix）：`web_search` 會撈到主題相關但已數月的舊文，且**模型自報的 `publishedAt` 不可信**（實測把 2025-09-18 的 UDN 文章標成「2026-06」），舊聞若原封顯示即被使用者當「近期重點」誤讀。故 `applyResult` 呼叫 `sanitizeNews(list, analysisDate)` 做三層把關，與既有 http(s) 白名單（XSS）併存：
  1. **格式＋自報時效硬過濾**：`parseIsoDatePrefix()` 要求 `publishedAt` 為精確 `YYYY-MM-DD`（`2026-06`／`null`／雜訊 → `null` → 剔除）；日期須在 `[analysisDate - newsMaxAgeDays, analysisDate + 1d]` 內，否則剔除。`newsMaxAgeDays` 由 `@Value("${market-analysis.news-max-age-days:5}")` 提供（**Task 149.18 由 30 收斂為 5 天**——只要「這幾天」的新聞、越近越重要；`sanitizeNews` 時效區間與 system/user prompt 皆同步套用此值）。
  2. **回抓原文實際發布日**（治本，`@Value("${market-analysis.news-verify-published-date:true}")` 可關）：對通過第 1 層且有 http(s) URL 者，以 lazy 共用 `java.net.http.HttpClient`（follow redirect、短 UA `Mozilla/5.0`、connect/read 逾時各約 6s）GET 原文，`extractPublishedDate()` 依序試 JSON-LD `"datePublished"`／`<meta property=article:published_time>`／`<meta itemprop=datePublished>`／`<meta name=date>`／`<time datetime>`，取首個可解析者。抓到真日期 → 以真日期覆寫 `publishedAt`（頁面顯示正確日）並套同一時效區間（真日期太舊即剔除，擋「模型謊報精確近期日期」）；抓不到／逾時／例外 → 保留第 1 層驗證後的模型日期（不因對方站台 WAF 擋抓而誤刪合法新聞；殘留風險：模型謊報精確近期日期＋原站不可抓，屬可接受殘留）。此為**單次引用驗證抓取**（非行情資料管線，不違反「行情走 external-materials-service」原則）；在 `finalizeIfReady` 背景 poller 執行緒進行，不在使用者 request 路徑。逐則序抓（單筆最多數則、序列可接受）。
  3. **提示詞強化**（`buildSystemPrompt`／`buildUserPrompt`，防禦縱深）：`newsHighlights` 指示改為「只納入經 web_search 實際查到、發布日在最近 N 天內的新聞；`publishedAt` 必須為原文實際發布日（`YYYY-MM-DD` 精確到日）；無法確認精確近期日期就不列入，不得用舊聞／臆測日期充數、不得依賴既有記憶」。
  - 三層後 `newsHighlights` 可為 `[]`（頁面既有空狀態處理）——寧缺勿濫，當日多空判斷仍以走勢量化數據成立。剔除逐則 `log.info` 記原因供稽核。
- **新聞來源地區封鎖：只留台/美/星，剔除中港澳**（`MarketAnalysisService.sanitizeNews`，Task 149.18）：`web_search` 常回傳大量中國大陸／香港來源（實測某日 4 則有 3 則為 `finance.sina.com.cn`、`sfccn.com`），與需求「只看台/美/星、嚴禁中港澳」相悖。故在 `sanitizeNews` 迴圈**最前段**（取得 `url = safeHttpUrl()` 之後、日期解析與昂貴回抓之前）加「地區封鎖」關卡：命中即最早 `continue` 剔除，省下第 2 層 HTTP 回抓成本。
  - **比對規則**（`isRegionBlocked(url, source)`）：以 `java.net.URI` 取 `host`（小寫）＋新聞 `source` 顯示名，比對三類後端 curated 封鎖清單——① **地區 TLD 後綴** `BLOCKED_HOST_SUFFIXES`（`.cn`／`.com.cn`／`.hk`／`.com.hk`／`.mo`／`.com.mo`… `host.endsWith(suffix)`）；② **具名網域** `BLOCKED_DOMAINS`（中港澳媒體但用 `.com`/`.cc`/`.net`：`sfccn.com`、`eastmoney.com`、`caixin.com`／`caixinglobal.com`、`yicai.com`、`wallstreetcn.com`、`cnstock.com`、`stcn.com`、`jrj.com`、`hexun.com`、`jiemian.com`、`cgtn.com`、`21jingji.com`、`gelonghui.com`、`futunn.com`、`cnfin.com`、`yuncaijing.com`、`sina.com.cn`、`qq.com`、`163.com`／香港 `scmp.com`、`hket.com`、`mingpao.com`、`hkej.com`、`on.cc`、`hk01.com`、`stheadline.com`、`wenweipo.com`、`takungpao.com`、`stnn.cc`／澳門 `macaodaily.com`、`aamacau.com`… `host==domain` 或 `host.endsWith("."+domain)`）；③ **來源名關鍵詞** `BLOCKED_SOURCE_TOKENS`（新浪財經／東方財富／南方財經／財新／第一財經／南華早報／香港經濟日報… 繁簡兩式，`source.contains`）。
  - **清單性質**：後端 curated 技術白名單（非使用者管理之業務分類，比照 `AVAILABLE_MODELS` 免入庫、不套「Enum 入庫」規範），以 `static final Set` 保存於 service。以 `@Value("${market-analysis.news-region-block-enabled:true}")` 總開關可整體關閉（快速回退）。
  - **零誤殺（對抗驗證）**：規則只做「完整結尾後綴／整段網域相等或子網域」比對，**嚴禁 `contains("cn")`/`contains("china")` 子字串比對**——否則會誤殺 `cnyes.com`（台灣鉅亨網）、`cna.com.tw`（台灣中央社）、`cnbc.com`、`cnn.com`（美國）。經 workflow 對抗驗證：台美星主流財經網域＋上述 6 個 lookalike 全部零 false positive，並補齊 10 個原漏網的中港澳 `.com`/`.cc` 網域。`source` 名關鍵詞亦逐一比對約 60 個台/美/星來源名無碰撞（如「華視」不含「新華社」、「信傳媒」不含「信報」）。
  - **prompt 積極列出（避免空手）**：實測手動重跑一次時（Sonnet 5／effort low／web_search 3），模型在「5 天內＋只台美星＋嚴禁中港澳」收緊條件下過度保守、原始輸出即 `"newsHighlights": []`（後端過濾器未觸發、非它剔除）。因後端過濾器才是硬關卡，`buildSystemPrompt`／`buildUserPrompt` 改為**積極要求多次 web_search 並列出 3～6 則符合條件（台/美/星、近 `newsMaxAgeDays` 天）的新聞**，個別不合格者才略過，唯有確實找不到才回空——鼓勵列出不致引入舊聞／中港澳（仍由過濾器把關）。
- **本地財經新聞爬蟲 `news_headline`（Task 149.21，producer=external-materials-service／consumer=business-services）**：以「自抓權威來源＋證交所公開資訊、餵入提示詞」取代/補充付費 `web_search`。
  - **資料流**：external-materials-service（**純 producer**，`JdbcTemplate` 直寫共用 postgres、無 Liquibase/JPA，比照 `stock_price_history`／`stock_dividend_history`）抓取後 upsert 至 `news_headline`；backend（**consumer**，擁有 Liquibase schema）以 JPA `NewsHeadlineRepository` 讀近 N 天餵入分析 prompt。Redis 只放高頻即時價、不放新聞。
  - **ERD `news_headline`**（backend Liquibase `v1.45.0-news-headline.sql`；ext 直寫）：`id BIGSERIAL PK`、`title VARCHAR(500)`、`source VARCHAR(100)`（wantgoo/moneydj/ltn/udn/twse）、`url VARCHAR(1024)`、`category VARCHAR(32)`（`news`／`twse-institutional`／`twse-turnover`）、`region VARCHAR(16)`（TW…）、`summary TEXT`、`published_at TIMESTAMPTZ`（原文/資料真實發布時間）、`fetched_at TIMESTAMPTZ DEFAULT now()`、`dedupe_key VARCHAR(64)`（`sha256(source|url|category)`）。索引：`uk_news_headline_dedupe`（UNIQUE，供 ext `ON CONFLICT` upsert）、`idx_news_headline_recent(published_at DESC)`、`idx_news_headline_cat(category, published_at DESC)`。全域參考資料（無 `owner_user_id`，比照 `twse_index_daily_history`）。
  - **ext 抓取（`NewsPoller` cron 06/12/18 Asia/Taipei ＋ `ApplicationReadyEvent` warmup ＋保留 `news-scraper.retention-days` 天）**：`NewsFetchClient`（玩股網 WantGoo JSON API、MoneyDJ 即時新聞 HTML、自由／經濟日報 RSS，讀真實 `time`／列時間／`pubDate`；UA `Mozilla/5.0`；HTML/RSS 以內建 regex/XML 解析、不引第三方）＋`TwseInfoFetchClient`（BFI82U 三大法人買賣金額 RWD JSON、FMTQIK 大盤成交統計 openapi JSON）。逐來源 graceful（單一失敗只 warn）。`StockSourceQuery.upsertNews / deleteNewsOlderThan`。
  - **backend 注入（`MarketAnalysisService`）**：`submitBatch` 前撈 `newsRepo.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(now(TW) - newsMaxAgeDays)`；`buildUserPrompt` 在走勢資料後、web_search 指示前注入「== 近期新聞（本地抓取）==」區塊（`{yyyy-MM-dd} [{source}] {title} — {url}` ＋ summary）。本地新聞**注入時繞過 `sanitizeNews`**（已保證來源＋真實 `published_at`）；模型輸出 `newsHighlights` 仍照舊四層 sanitize。
  - **web_search 三態語意升級**（沿用 `MarketAnalysisSetting.web_search_max_uses` 白名單 `[0,3,4,6]`、零 schema 變更）：`0`＝純本地新聞（注入本地 block、`webSearchOn=false` 不加 tool＝關閉付費 web_search，但不再是「純技術面無新聞」）；`>0`＝本地新聞＋web_search 補今日最新（prompt 註明「已附近期新聞，可再 web_search 補」）。`submitBatch` 加 tool 條件 `if(webSearchOn)` 不變。
- **優雅降級**：`ANTHROPIC_API_KEY` 空 → 不建 client、upsert `status=NOT_CONFIGURED`；送出批次例外 → `status=FAILED` + `error_message`；批次結果 errored/expired/解析失敗 → `status=FAILED`（保留 `raw_response` 供除錯）。皆不拋出中斷排程／poller。`OK` 筆才視為有效分析。
- **設定**：`application.yml` 新增 `anthropic.api-key: ${ANTHROPIC_API_KEY:}`、`anthropic.model: ${ANTHROPIC_MODEL:claude-opus-4-8}`；`docker-compose.yml` business-services 透傳 `ANTHROPIC_API_KEY`；`.env.example` 補金鑰取得說明；金鑰不入版控。新聞政策參數：`market-analysis.news-max-age-days`（預設 **5**，Task 149.18 由 30 收斂）、`market-analysis.news-verify-published-date`（預設 true，Task 149.17）、`market-analysis.news-region-block-enabled`（預設 true，Task 149.18 中港澳地區封鎖總開關）；皆可由環境變數覆寫、有預設值故非必設。
- **前端配色**：偏多（BULLISH）紅、偏空（BEARISH）綠、中性（NEUTRAL）灰——遵循台股「漲紅跌綠」慣例（與 Dashboard Task 147 一致）。
- **前端 PROCESSING 狀態**（Batch 非同步）：`today.status==='PROCESSING'` 顯示 info alert「分析中（批次處理中）」，並 `watch` 狀態每 30 秒自動 `load()` 直到改變（`onUnmounted` 清 timer）；手動「重新分析」送出後提示「已送出分析（批次處理中，完成後自動更新）」。歷史表 `statusLabel` 加 `PROCESSING → （分析中）`。

### 模型頁面可調（成本控管）

分析**模型**、**思考深度（effort）**、**新聞搜尋次數（web search）**皆可由管理者在頁面切換（模型：Opus 4.8 / Sonnet 5 / Haiku 4.5；effort：low / medium / high；web search：0＝關閉 / 3 / 4 / 6），另有**每日自動分析開關（enabled）**可停用 07:30 排程（省整筆花費），皆持久化、下次分析生效、免改環境變數或重啟。

- **資料模型**（Liquibase `v1.38.0-market-analysis-setting.sql` 建表；`v1.39.0` 增 `effort`、`v1.40.0` 增 `web_search_max_uses`、`v1.41.0` 增 `enabled`。單列設定表，比照 `backup_setting`）：
  ```sql
  CREATE TABLE market_analysis_setting (
      id          INTEGER PRIMARY KEY DEFAULT 1,
      model       VARCHAR(64) NOT NULL DEFAULT 'claude-opus-4-8',
      updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
      CONSTRAINT market_analysis_setting_single_row CHECK (id = 1)
  );
  INSERT INTO market_analysis_setting (id) VALUES (1);
  -- v1.39.0：思考深度（成本控管），既有單列以 DEFAULT 補值
  ALTER TABLE market_analysis_setting ADD COLUMN effort VARCHAR(16) NOT NULL DEFAULT 'medium';
  -- v1.40.0：新聞搜尋次數（成本控管），0=關閉；既有單列以 DEFAULT 補值
  ALTER TABLE market_analysis_setting ADD COLUMN web_search_max_uses INTEGER NOT NULL DEFAULT 6;
  -- v1.41.0：每日自動分析開關（成本控管），false=停用 07:30 排程；既有單列以 DEFAULT 補值
  ALTER TABLE market_analysis_setting ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true;
  ```
  Entity `MarketAnalysisSetting`（`@Id Integer id`、`model`、`effort`、`webSearchMaxUses`、`enabled`）＋ `MarketAnalysisSettingRepository`。
- **解析**：`resolveModel()` = 設定表 `model`（非空）→ 否則 `@Value("${anthropic.model:claude-opus-4-8}")` 環境預設；`resolveEffort()` = `effort`（白名單內）→ 否則 `medium`；`resolveWebSearchMaxUses()` = `web_search_max_uses`（白名單內）→ 否則 `6`；`isEnabled()` = `enabled` → 否則 `true`。`doGenerate` 每次呼叫前各取一次（故切換即時生效）；effort 以 `OutputConfig.builder().effort(...)`；web search 以 `WebSearchTool20260209.maxUses(N)`，且 **`N=0` 時不 `addTool`**（純技術面）、並將 system/user prompt 切為「不得杜撰新聞、`newsHighlights` 回空」。`isEnabled()` 則由 **`MarketAnalysisScheduler`** 於 07:30 cron 與開機 self-heal 開頭檢查：`false` 即 return 略過（不呼叫 LLM），手動 `generate()` 不檢查此旗標。
- **可選白名單／開關**：後端 curated 常數 `AVAILABLE_MODELS`（Opus 4.8／Sonnet 5／Haiku 4.5）、`AVAILABLE_EFFORTS`（`low`／`medium`／`high`）、`AVAILABLE_WEB_SEARCHES`（`0` 關閉／`3`／`4`／`6`）皆為「技術白名單」而非使用者可自訂的業務分類，故**不**套用「Enum 必須入庫由 `/api/settings/*` 管理」規範；`enabled` 為布林開關（非白名單）。`effort` 不列 `xhigh`／`max`（更貴、與省錢目的相反）。`updateSettings(model, effort, webSearchMaxUses, enabled)` 對有帶的白名單欄各自驗證（非法 → 400），`enabled` 直接設值，未帶之欄不變；至少須一項；回傳清單時若現值不在白名單則補入（下拉恆含現值）。
  - **成本觀點**：`enabled` 是最粗的槓桿——停用即當天完全不跑、零花費；`effort` 是主要槓桿（thinking 按 output token 計價，Opus $25/1M 最貴，`medium` 較隱含 `high` 省且對方向判斷足夠）；web search 為次要槓桿（搜尋費本身約 $10/1000 次很小，省的是「每輪重複處理灌回 context 的搜尋結果」；因直接影響新聞廣度，預設維持 `6` 不下修）。
- **API**：
  | Method | Path | 說明 |
  |---|---|---|
  | GET | `/api/market-analysis/settings` | `{ model, effort, webSearchMaxUses, enabled, availableModels:[{id,label}], availableEfforts:[{id,label}], availableWebSearches:[{value,label}] }`；已登入者可讀。 |
  | PUT | `/api/market-analysis/settings` `{model?, effort?, webSearchMaxUses?, enabled?}` | 更新模型／思考深度／新聞搜尋次數／每日自動分析開關（至少一項；未帶之欄不變）；`CurrentUserContext.isAdmin()` 否則 `AdminRequiredException`；白名單欄驗證。body 型別 `Map<String,Object>`（`webSearchMaxUses` 收 JSON number、`enabled` 收 JSON boolean）。 |

  BFF：主 `GET /api/bff/today-market-analysis` 聚合回傳 `{ today, history, settings }`；`PUT /api/bff/today-market-analysis/settings` 為**泛型 Map 轉發**（body 原封轉給 business，故新增 `effort`／`web_search`／`enabled` 欄皆無需改 BFF），bff `SecurityConfig` 對該 `PUT` 限 `AUTHORITY_ADMIN`。
- **前端**：頁首（限管理者）並列「每日自動分析」`el-switch`（v-model 布林）＋三個 `el-select`——模型（`availableModels`）、思考深度（`availableEfforts`）、新聞搜尋（`availableWebSearches`，值為整數）；各自 `change` → `updateSettings({model})` / `{effort}` / `{webSearchMaxUses}` / `{enabled}` 持久化，提示訊息，失敗還原；`busy` computed（任一儲存中或分析中）停用全部控制項避免併發覆蓋；`.header-actions` 加 `flex-wrap` 讓開關＋三下拉＋按鈕在窄寬度優雅換行；停用時「尚無資料」`el-empty` 說明改為「已停用、由管理者手動產生」；一般使用者不顯示這些控制項。卡片 `foot-meta` 的「由 {model}」仍顯示**產生該筆分析所用**的模型（`daily_market_analysis.model`），與「下次要用的設定」語意區分（effort／web search／enabled 屬設定層、不逐筆記錄於 `daily_market_analysis`）。

### 擴充：分析結果每日 Email 寄送 + 收件人訂閱選擇（Task 151）

**目標**：分析成功後自動 email 給「訂閱股市分析」的收件人；收件人沿用既有 `notification_recipient`（Requirement 23），並可 per-recipient 選擇是否接收。因 main 已改 **Batch API 非同步流程**（送出即 `PROCESSING`、由背景 poller `finalizeIfReady` 落 OK），寄送掛勾在收尾成功處；**一交易日恰一封**——以 `daily_market_analysis.email_sent_at` 冪等記號確保只寄一次（手動「重新分析」重跑收尾不重寄）。

- **資料模型**（Liquibase `v1.43.0-market-analysis-email.sql`，兩個 changeset）：
  ```sql
  -- 收件人訂閱旗標
  ALTER TABLE notification_recipient
      ADD COLUMN receive_market_analysis BOOLEAN NOT NULL DEFAULT TRUE;
  -- 每日寄送冪等記號（對齊 generated_at 為 timestamptz）
  ALTER TABLE daily_market_analysis
      ADD COLUMN email_sent_at TIMESTAMP WITH TIME ZONE;
  ```
  `receive_market_analysis DEFAULT TRUE`——沿用既有收件人故預設訂閱、可自行取消；與「是否接收警示」`active` 各自獨立（一個收件人可只收警示、只收股市分析、或兩者皆收）。Entity `NotificationRecipient` 加 `receiveMarketAnalysis`（`@Builder.Default = true`）、`NotificationRecipientDto.Response` 加同名欄位、`create` 依 entity 預設即 `true`；Entity `DailyMarketAnalysis` 加 `emailSentAt`（`Instant`，nullable）。

- **寄送對象查詢**：`NotificationRecipientRepository.findByActiveTrueAndReceiveMarketAnalysisTrueOrderByCreatedAtAsc()`。批次收尾 poller 在**背景執行緒（無 HTTP request）**呼叫 → `TenantFilterAspect` 因 `RequestContextHolder` 無 attributes 而不啟用 `ownerFilter` → 掃全體，寄給**所有租戶**已訂閱收件人（全域分析語意，比照排程／poller 本身不套 owner 過濾）。

- **寄送掛勾點**（`MarketAnalysisService.finalizeIfReady`）：批次收尾成功分支落 `status = OK` 後、`save` 前，若 `row.getEmailSentAt() == null` → 以 `MarketAnalysisDto.from(row, objectMapper)`（帶已解析 keyFactors / newsHighlights）呼叫 `emailDispatcher.dispatchDaily(dto)`；**回傳 `true`（實際寄出）才 `row.setEmailSentAt(now)`**（`save` 一併持久化記號）。故：非 OK（`PROCESSING`／`FAILED`／`NOT_CONFIGURED`）不寄；同一交易日收尾一次即寄一次，手動「重新分析」再次收尾時 `email_sent_at` 已非空 → 不重寄；未設 SMTP／無收件人時回 `false`、不戳記，留待（下次收尾或使用者補設後重跑）重試。dispatch 以 try/catch 包覆，失敗 `log.warn` 不拋（不中斷收尾契約）。

- **`MarketAnalysisEmailDispatcher`**（business-services `service/`）：`isEnabled()`（`EmailService`）為否或無訂閱收件人 → log 略過。組 subject `[今日股市分析] {date} 台股{偏多/偏空/中性}（信心 N）` 與 HTML 本文（方向色塊：偏多紅 `#c0392b`／偏空綠 `#27ae60`／中性灰、信心、總結、關鍵因素、台美走勢摘要、參考新聞連結）。**逐一收件人各寄一封**（`emailService.sendHtml(List.of(email), ...)`，保護彼此隱私，比照 `AlertNotificationDispatcher`）。新聞連結寄送前再過濾 http(s)（縱深）。

- **BFF（一頁一支 passthrough）**：新增 `TodayMarketAnalysisRecipientsBffRoutes`（比照 `NotificationSettingsBffRoutes`）：rewrite `/api/bff/today-market-analysis/recipients(?<seg>/?.*)` → `/api/notification-recipients${seg}` → `business-services.url`。前端只透過此頁自己的 BFF 讀 / 切換收件人訂閱，與通知設定頁**共用同一 business API**（`/api/notification-recipients`）確保同一事實來源。business 端新增 `PATCH /api/notification-recipients/{id}/market-analysis`（`toggleMarketAnalysis`，`TenantGuard.assertOwned` 縱深）。

- **授權**：此 passthrough 不在 `GLOBAL_SETTINGS_PATHS`、也不匹配 `today-market-analysis/generate|settings` 兩條 ADMIN 規則 → 落 `anyExchange().authenticated()`，為 per-user（owner-scoped）自管，與 `notification-settings/recipients/**` 一致（刻意不 admin-gate）。

- **前端**（`TodayMarketAnalysisView.vue`）：新增「分析結果寄送對象」`el-card`（已登入者可見），`el-table` 列出自己的收件人（email / 是否啟用 / `接收每日股市分析` 開關），開關 `change` → `bffApi.todayMarketAnalysis.toggleMarketAnalysis(id)`；空清單提示「請至 系統設定 → 通知設定 新增收件人」。`api/index.js` 的 `todayMarketAnalysis` 加 `getRecipients` / `toggleMarketAnalysis`。SMTP 重用既有設定，無新增環境變數。

### 不處理

- 不自建新聞抓取／`financial_news` 表（使用者選 Claude web_search）。
- 不做盤中即時重評（僅每日開盤前一次 + 管理者手動重跑）。
- 不對非台股市場（美股／英股）產生獨立判斷頁（美股走勢僅作為台股判斷的輸入）。

## Requirement 32：資產配置建議（依個人條件＋持有資產由 AI 給個人化配置建議）

**目標**：使用者先設定理財條件（年齡／投資年限／每月可投入／理財目標（複選）／可忍受風險／獲利預期），系統結合其**最新 `asset_snapshot`** 的現況配置與持有明細，由 Claude 產出個人化資產配置建議（整體評析／現況風險評估／建議目標配置／具體調整動作／風險提醒／參考來源），並保存歷次供回顧。以 Requirement 31 為藍本，差異：**互動式即時 → request 立即回覆、背景執行緒呼叫 Messages API**（非 Batch）；owner-scoped 查詢與 prompt 組裝在 request 執行緒完成，`@Filter`／`TenantGuard` 正常運作。

- **資料模型**（Liquibase `v1.44.0-portfolio-advice.sql`，三個 changeset）：
  ```sql
  -- 理財條件（一使用者一列，記住免重填；owner-scoped）
  CREATE TABLE investment_profile (
      id BIGSERIAL PRIMARY KEY, owner_user_id BIGINT NOT NULL,
      age INTEGER, investment_horizon_years INTEGER, monthly_investment NUMERIC(20,2),
      retirement_date DATE, -- 預計退休年月（v1.44.1 addColumn，存該月一號；退休後每月投入歸零）
      goals VARCHAR(300), risk_tolerance VARCHAR(20), expected_annual_return VARCHAR(20),
      updated_at TIMESTAMPTZ NOT NULL, CONSTRAINT uq_investment_profile_owner UNIQUE (owner_user_id));
  -- 歷次建議（owner-scoped；條件快照 + based_on_* 歷史快照 + result_json 解析建議）
  CREATE TABLE portfolio_advice (
      id BIGSERIAL PRIMARY KEY, owner_user_id BIGINT NOT NULL, status VARCHAR(20) NOT NULL,
      model VARCHAR(64), created_at TIMESTAMPTZ NOT NULL, completed_at TIMESTAMPTZ, error_message VARCHAR(1000),
      age INTEGER, investment_horizon_years INTEGER, monthly_investment NUMERIC(20,2),
      goals VARCHAR(300), risk_tolerance VARCHAR(20), expected_annual_return VARCHAR(20),
      based_on_snapshot_id BIGINT, based_on_snapshot_date DATE, based_on_total_assets NUMERIC(20,2),
      raw_response TEXT, result_json TEXT);
  CREATE INDEX idx_portfolio_advice_owner_created ON portfolio_advice (owner_user_id, created_at DESC);
  -- 成本控管設定（單列 id=1，全域，比照 market_analysis_setting）
  CREATE TABLE portfolio_advice_setting (
      id INTEGER PRIMARY KEY, model VARCHAR(64) NOT NULL, effort VARCHAR(16) NOT NULL,
      web_search_max_uses INTEGER NOT NULL, updated_at TIMESTAMPTZ NOT NULL);
  INSERT INTO portfolio_advice_setting VALUES (1, 'claude-opus-4-8', 'medium', 4, now());
  ```
  Entity `InvestmentProfile`／`PortfolioAdvice`（皆 `@Filter(name="ownerFilter")`，多租戶 Requirement 28）／`PortfolioAdviceSetting`。`goals` 為理財目標複選 code 逗號分隔；`based_on_snapshot_id` 正規化參照 asset_snapshot，`based_on_snapshot_date`／`based_on_total_assets` 為歷史快照（回顧不失真，比照 `realized_gain` 名稱字串例外）。

- **正規化說明**：`investment_profile` 不存衍生值（退休日期以 `retirement_date` 存原始年月；累積年數／退休後年數由 service `retirementSpan()` 依退休日期與今天現算、不入庫，每月投入僅計入累積期、退休後歸零）。`portfolio_advice` 的條件快照與 `based_on_*` 為**刻意 denormalize 的歷史快照**（產生當下的條件與資產依據），符合「歷史交易記錄名稱字串」例外；`result_json` 為 LLM 解析結果的凍結內容（比照 `daily_market_analysis`）。

- **Service `PortfolioAdviceService`**：
  - profile：`getProfile()`／`saveProfile(...)`（`TenantGuard.requireCurrentUserId()` 綁 owner；risk/return/goals 白名單驗證，goals 過濾去重逗號串接）。
  - 現況：`getCurrentAllocation()` 讀最新快照 `totalDeposit/totalFundValue/totalStockValue/totalAssets` 算占比（存款／基金／股票）。
  - `generate(...)`（**非同步**）：upsert 條件 → 讀最新快照＋子表明細（`bankDeposit/fundHolding/stockHolding` `findBySnapshotId`，皆由 owner-filtered 快照 id 帶出，安全）→ **在 request 執行緒組好 system/user prompt**（條件＋現況＋明細；`web_search` 開則要求納入當前市場、references 附來源，關則要求不得杜撰、references 回空）→ 落一筆 `PortfolioAdvice`（`status=PROCESSING`＋條件快照＋based_on）並**立即回傳** → 提交背景執行緒 `runGeneration`。`runGeneration`（daemon 固定小池）：依 setting `resolveModel/resolveEffort/resolveWebSearchMaxUses` 呼叫 `client.messages().create(MessageCreateParams...)`（adaptive thinking + `OutputConfig.effort` + 可選 `WebSearchTool20260209.maxUses`）→ 取首個 `{` 至末個 `}` 解析為 `PortfolioAdviceResult` → sanitize references（http(s) 白名單）→ **以 `adviceId` by-id 更新該列** OK／FAILED（全程 try/catch 不拋）。金鑰未設時 request 執行緒直接落 `NOT_CONFIGURED`、不提交背景。`latest()` 對 PROCESSING 逾 10 分鐘（背景中斷／重啟）自癒判 FAILED。
  - 設定：`getSettings()`／`updateSettings(model, effort, webSearchMaxUses)` 白名單驗證（比照 `MarketAnalysisService`）。
  - 免 enum 寫死：`GOAL_OPTIONS`／`RISK_OPTIONS`／`RETURN_OPTIONS`／`AVAILABLE_MODELS`／`AVAILABLE_EFFORTS`／`AVAILABLE_WEB_SEARCHES` 皆服務層白名單常數。

- **非同步（in-process 背景執行緒）而非同步阻塞或 Batch**：單次 Claude 呼叫含 thinking + web_search 常達數十秒（實測 ~90s），**超過 nginx `location /api/` 的 `proxy_read_timeout 60s`**——若同步阻塞，長連線會被 proxy 在 60s 切斷（前端 504、且同步版「跑完才落庫」導致完全無結果、無歷史）。故改：`POST /generate` 立即落 `PROCESSING` 並回、由 business-services **背景執行緒池**（`Executors.newFixedThreadPool(3)`，daemon）跑 Claude、完成 by-id 更新，前端輪詢。與 Requirement 31 差異：R31 用 **Batch API**（每日排程、可等、省 50%）；本功能用 **in-process 背景執行緒**（互動式、要快回饋、單筆即時）。**多租戶正確性關鍵**：owner-scoped 的快照／明細讀取與 prompt 組裝**全在 request 執行緒**（`TenantFilterAspect` 啟用 ownerFilter）；背景執行緒只 Claude 呼叫 + `adviceRepo.findById(adviceId)`（`@Filter` 本就不套 by-id）+ `save`，**不做任何 owner-scoped 查詢**，故不受背景執行緒無 request context、ownerFilter 不啟用之影響（見 `feedback_hibernate_filter_aspect`）。狀態：PROCESSING → OK／FAILED；NOT_CONFIGURED 於 request 執行緒直接落。

- **API**（business `/api/portfolio-advice`）：
  | Method | Path | 說明 |
  |---|---|---|
  | GET | `/latest` | 最新一筆建議（`PortfolioAdviceDto`）；無則 `{status:"NONE"}` |
  | GET | `/history?limit=20` | 近 N 筆（owner-scoped，created_at 降序） |
  | GET | `/profile` | 理財條件（含 goal/risk/return 可選清單） |
  | PUT | `/profile` | 儲存理財條件 |
  | GET | `/current-allocation` | 最新快照現況配置（`CurrentAllocationDto`） |
  | POST | `/generate` | 建立 `PROCESSING` 建議並立即回傳（body 帶條件、一併儲存 profile；背景產生結果） |
  | GET | `/settings` | 成本設定 + 可選清單 |
  | PUT | `/settings` `{model?, effort?, webSearchMaxUses?}` | 更新成本設定（`isAdmin()` 縱深，白名單驗證） |

- **BFF 一頁一支**：`PortfolioAdviceBffController`（`/api/bff/portfolio-advice`）：`GET` `Mono.zip` 聚合 `{ latest, history, profile, settings, currentAllocation }`；`POST /generate`（timeout 180s）、`PUT /profile`、`PUT /settings` 泛型 Map 轉發。WebClient 帶 `X-User-*` 租戶身分，business 端 owner-scoped。`SecurityConfig` 加 `PUT /api/bff/portfolio-advice/settings` 限 `AUTHORITY_ADMIN`；`/generate`／`/profile` 為 per-user → 落 `authenticated()`。

  ```text
  前端 AssetAllocationAdviceView ──▶ GET /api/bff/portfolio-advice ──▶ business /api/portfolio-advice/{latest,history,profile,settings,current-allocation}
  產生建議 ──▶ POST /api/bff/portfolio-advice/generate ──▶ business 落 PROCESSING 即回、背景執行緒呼叫 Claude；前端每 5s 輪詢 GET 聚合至非 PROCESSING
  （管理者）成本設定 ──▶ PUT /api/bff/portfolio-advice/settings（限 ADMIN）──▶ business PUT /api/portfolio-advice/settings
  ```

- **前端**（`views/AssetAllocationAdviceView.vue`）：條件表單（年齡／年限／月投入 `el-input-number`、理財目標 `el-select multiple`、風險 `el-radio-group`、獲利預期 `el-select`）＋「儲存條件」「產生建議」；現況配置與建議目標配置以純 CSS bar 呈現（避免 echarts tree-shaking 漏註冊風險）；建議卡片顯示 summary／風險評估／目標配置（比例＋理由）／調整動作（優先度 tag）／風險提醒／參考來源（`safeUrl` 擋非 http(s)）；免責聲明；歷次建議 `el-table` 可展開回顧當時條件與建議；管理者頁首成本設定（模型／思考深度／web 搜尋 `el-select`，`change` 即持久化）。`api/index.js` 加 `bffApi.portfolioAdvice`（generate 覆寫 200s timeout）。左選單 icon `Compass`。

### 不處理

- 不做定期／排程自動產生（互動式即時，使用者按鈕觸發；不像 Requirement 31 有每日 cron）。
- 不接第三方投顧／下單（純建議，不執行任何交易）。
- 不做多份 profile／情境比較（一使用者一份條件；要比較可各自產生後於歷次建議回顧）。
