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
  - `GET /api/bff/dashboard/realtime`：2 分鐘輪詢用，回傳 stockPrices + marketStatus + liveAssets
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
- `AssetHistoryBffController`（AssetHistoryView 專屬）：`GET /api/bff/asset-history`（history + isLastOfYear flag）、`POST /api/bff/asset-history/recalc-dividends`、`DELETE /api/bff/asset-history/{id}`、`GET /api/bff/asset-history/export`；另有排程匯出端點（`export-schedule` GET/PUT、`export-schedule/run-now`、`export-schedule/browse`），詳見後段 Requirement 34 章節
- `RealizedGainBffController`（RealizedGainView 專屬）：`GET /api/bff/realized-gain`（gains + active brokers 一起回傳）、CRUD、export、`GET /api/bff/realized-gain/lookup-name?code=&market=`（輸入股號自動帶出股名；轉呼**同一支** business `/api/stock-alerts/lookup-name`，與 stock-alert 頁同源，符合「同義欄位、同一 business service API」——前端不再跨頁呼叫 stock-alert 的 BFF）；另有排程匯出端點（`export/schedule` GET/PUT、`export/run-now`、`export/browse`），詳見後段 Requirement 39 章節
- `ExchangeRateBffController`（ExchangeRateView 專屬）：`GET /api/bff/exchange-rate`（先 refresh 再回 10 年歷史）、`POST /api/bff/exchange-rate/backfill`
- `TradingCalendarBffController`（TradingCalendarView 專屬）：`GET /api/bff/trading-calendar?year=Y`（`holidays` 為 `{tw: {date→name}, us: {date→name}}` 物件 + `marketStatus`）、`GET /api/bff/trading-calendar/market-status`；另有匯出與排程匯出端點（`POST export`、`export/browse`、`export/schedule` GET/PUT），詳見後段 Requirement 37 章節
- `SnapshotListBffController`（SnapshotListView 專屬）：`GET /api/bff/snapshot-list`、`DELETE /{id}`、`GET /export`
- `GdpTwseBffController`（GdpTwseView 專屬，`@RequestMapping("/api/bff/gdp-twse")`）：股市大盤查詢頁的指數日線／當日＋台韓人均 GDP 聚合（Requirement 18）：
  - `GET /api/bff/gdp-twse`：台／韓人均 GDP + 實質成長率歷史
  - `POST /api/bff/gdp-twse/refresh`：自 IMF 刷新人均 GDP
  - `GET /api/bff/gdp-twse/index-daily`：台股大盤／海外指數每日 OHLC
  - `POST /api/bff/gdp-twse/refresh-index-daily`：刷新指數日線
  - `GET /api/bff/gdp-twse/index-intraday`：指數當日分時
- 其他 settings/passthrough（每頁一支 Spring Cloud Gateway route 配置，rewrite `/api/bff/{page}/**` → `/api/{resource}/**`）：BankSettings、BrokerSettings、DepositTypeSettings、MarketTypeSettings、AssetClassSettings（`/api/bff/asset-class-settings/**`）、TransitFundTypeSettings、StockAlert（`/api/bff/stock-alert/**`；**Requirement 54 起另有 `StockAlertBffController` 與此 route 並存**——`GET/PUT /api/bff/stock-alert/export-setting`、`POST .../run-now`、`GET .../browse`、`GET .../browse-gdrive`，後兩者 passthrough 至既有唯一那支 `/api/export-schedule/browse{,-gdrive}`。萬用 route 會把這幾條錯誤 rewrite 成 `/api/stock-alerts/export-setting/...`，靠 WebFlux `RequestMappingHandlerMapping`（order 0）先於 Gateway `RoutePredicateHandlerMapping`（order 1）由 controller 接走；同一模式的既有先例為 `TradingRadarBffController` ＋ `TradingRadarBffRoutes`）、BackupRestore（`/api/bff/backup-restore/**`）、NotificationSettings（`/api/bff/notification-settings/recipients/**` → `/api/notification-recipients/**`）、PaymentAccountSettings（見「Payment Accounts / Categories」段落，Requirement 22）、UserManagement（`/api/bff/user-management/**`，見 Requirement 28「API 端點（新增）」）、TodayMarketAnalysisRecipients（`/api/bff/today-market-analysis/recipients/**` → `/api/notification-recipients/{id}/market-analysis`，見 Task 151）
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
  - `GET /api/bff/stock-analysis/intraday-ticks` → `/api/market-data/intraday-ticks`（走勢圖「當日」期間用；business-services proxy 至 `external-materials-service /internal/intraday-ticks`，後者讀 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`。**date 省略時的預設 bucket**：今天（該市場時區）若為交易日且今日 tick LIST 已有資料就用今天，否則退回 `findMaxTradingDate`（最近有收盤的交易日）——不可直接用 `findMaxTradingDate`，否則盤中今日收盤價尚未入庫、預設日期會停在前一交易日、讀到空 bucket（見 Task 153）。盤中由 `PricePoller` 每 2 分鐘累積 / 盤後由 `IntradayTickRefresher` 用台股 FinMind `TaiwanStockKBar` + 美/英股 Yahoo 5m 完整覆寫。**Task 236 盤中自癒**：`IntradayTickRefresher` 於 `ApplicationReadyEvent` 背景回補當下開盤市場的全部納管標的；啟動／讀取皆呼叫既有 `refreshOne`，故台股仍為 FinMind 5m 優先、空才 Yahoo `.TW→.TWO`，美／英股為 Yahoo 5m。`/internal/intraday-ticks` 讀今日 bucket 時檢查解析後 empty／首筆晚於開盤後 5 分鐘／內部 gap > 15 分鐘；命中後透過 `(market,code,date)` 記憶體 single-flight 觸發，同 key 一次外呼、完成後成功或失敗均冷卻 60 秒，請求最多同步等待 8 秒，逾時回既有 LIST且背景繼續。狀態 bounded 並於冷卻到期清除；過去交易日只保留 empty cold-start。來源失敗不清空，寫入仍走 `replaceTicks` never-shrink。**盤後 refresh cron：台股 14:00 TW、美股 16:05 ET、英股 17:00 LON**；台股/英股排在收盤後 ~30 分而非 5 分，因 Yahoo 對 TWSE(~25min)/LSE(~15min) 的 5m feed 有延遲，收盤後 5 分抓會截斷（台股停 13:10、英股停 ~16:20），美股 Yahoo 無延遲故 16:05 即完整。另 `IntradayTickStore.replaceTicks` 採 **never-shrink** 防截斷：新抓資料末刻早於既有 LIST 末刻（或為空）就保留既有不覆寫，避免截斷版蓋掉 polling 已到收盤的 tick（Task 156）。台股 Yahoo 5m fallback 的 ticker 後綴依掛牌市場：上市 `.TW`、上櫃（TPEx，含債券 ETF 00xxxB）`.TWO`，`PriceFetchClient.fetchIntraday5m` 先試 `.TW` 空再 fallback `.TWO`，否則上櫃股當日分時恆空，見 Task 119）。**「當日」上方的「昨收 / 今日漲跌」不走此端點、亦不新增欄位**：`StockAnalysisDialog.vue` 於前端 view 從已載入的日線 `history`（同對話框 `/api/bff/stock-analysis/history/stock`）取「該分時交易日之前最後一筆 `stock_price_history` 收盤」為昨收、以 legend 顯示的現價（`lastNonNull(prices)`）自算今日漲跌，維持 tick API 契約（`List<IntradayTick>` 僅 `time`+`price`）不變，且與 Dashboard／管理資產「當日漲跌」同一「vs 前一交易日原始收盤」口徑（Task 158）

- **共享 / 跨頁 passthrough routes**（非單一頁面專屬，由 Spring Cloud Gateway 直接轉發至 business-services，服務跨頁共用的 store CRUD、下拉 lookups、SSE 與基金主檔；皆為刻意的共享資源，不另立 `/api/bff/{page}/**`）：
  - `MeController`（BFF 自有 controller，非 passthrough）：`GET /api/me`、`POST /api/impersonate` → 認證端點例外，服務 `authStore` / `PendingApprovalView` / App layout 等跨頁情境，故不掛 `/api/bff/{page}/**`（Requirement 28）
  - `SnapshotBffRoutes`：`/api/snapshots/**` → business-services（Pinia store 共用快照 CRUD；單頁資料仍走各自的 `/api/bff/{page}/**`）。**已知落差**：實際只有 SnapshotFormView 的建立／更新與 App.vue 取最新快照 ID 在用，等於以「store 共用」之名掩蓋單頁未合規；修正方向為 `SnapshotFormBffController` 補 `POST`／`PUT`／list 端點，屬功能變更須另走 SDD 循環
  - `SettingsBffRoutes`：`/api/settings/**` → business-services。**目前無前端消費者**：原「被多個表單頁共用的下拉 lookups」說法已不成立——lookups 已分別由 `/api/bff/snapshot-form/lookups` 與 `/api/bff/fund-settings/bank-options`（Task 175）取代，前端 `institutionApi` wrapper 已於 Task 197 移除。route 本身暫留（移除需重建 BFF 服務），**屬待清理項，勿據此段落認定它是現行的共享例外**
  - `FundBffRoutes`：`/api/funds`、`/api/fund-nav/**`、`/api/fund-dividend/**` → business-services（Requirement 19/20/21）。`/api/funds` 供 FundSettingsView 基金主檔 CRUD 使用；**慣例例外**：基金主檔頁直接沿用 `/api/funds` 資源 passthrough（基金主檔為跨頁共享資源）。SnapshotForm 讀同一份基金主檔，但走自己頁面專屬的 `/api/bff/snapshot-form/funds`（同樣 passthrough 至 `/api/funds`，符合「一頁一 BFF」）。**`/api/fund-nav/**` 與 `/api/fund-dividend/**` 目前無前端消費者**：原「FundSettingsView 的 NAV / 配息回補」說法已不成立——`FundSettingsView.vue` 全頁無任何 NAV／配息呼叫，前端唯一的 NAV 刷新是 `api/index.js` 的 `/bff/snapshot-form/fund-nav/refresh`（走 SnapshotForm 自己的 BFF，不經本 route）。兩條 route 暫留（移除需重建 BFF 服務），**屬待清理項，勿據此段落認定它們是現行的共享例外**
  - `FundSettingsBffController`：`GET /api/bff/fund-settings/bank-options` → 過濾 active 後的銷售銀行下拉；與 SnapshotForm 的 lookups **同讀 business `/api/settings/banks`**（同義欄位同一來源），fund-settings 頁不再跨頁呼叫 `/api/bff/snapshot-form/lookups`（Task 175：一頁一 BFF 合規化）
  - `RealizedGainBffRoutes`：`/api/realized-gains/**` → business-services。**目前無前端消費者**：原「RealizedGainView 的 Pinia store `gainApi` 共用 CRUD」說法已不成立——該頁已全面走 `RealizedGainBffController` 的 `/api/bff/realized-gain` 聚合端點，前端 `gainApi` wrapper 與 `assetStore` 的三個已實現損益 action 已於 Task 197 移除。route 本身暫留（移除需重建 BFF 服務），**屬待清理項**
  - `MarketDataBffRoutes`：`/api/market-data/**` → business-services。消費者是 DashboardView 與 TradingRadarView 兩頁的 SSE 行情串流（皆為 `new EventSource('/api/market-data/prices/stream')`，見下方 SSE 段落之已知落差）；`marketDataApi` wrapper（歷史/配息/ETF 成分股）無呼叫端，已於 Task 197 移除，該類查詢皆走 `StockAnalysisBffRoutes` 的 `/api/bff/stock-analysis/**`
  - `SchedulePublicBffController`（ScheduleListView 專屬，「公開資訊」分組，Requirement 36）：`GET /api/bff/schedule-list` → 回傳系統所有自動排程的**人工維護靜態清單**（`ScheduledJobDto` 不可變 record：service / category / name / description / schedule 白話 / cron / zone），共 **46 筆** ＝ `business-services` 17 ＋ `external-materials-service` 29（**以 `@Scheduled` 方法計**；external 實際 30 個標註，`TwClosurePoller` 一法兩標併為一筆；business 17 含 Requirement 49「交易紀錄匯出」）。此頁為唯讀資訊展示故不做跨服務反射探索、不入 DB、不設管理端點；**新增／調整任何 `@Scheduled` 須同步更新此清單以免漂移**（Task 195 修正 Task 190／191 漏同步之兩處漂移；Task 196.12 新增一筆 JOBS 後僅更新 javadoc 表頭、漏同步總數與本段，於 Task 197 一併修正為 36；本段之後歷經多個任務累積新增排程未同步更新此總數，Task 228 新增大盤盤中即時點位排程時以 `grep '@Scheduled'` 逐檔核對重新校正為 44——12／24 兩數字皆已是 Task 228 之前即存在的計數漂移，非本次新增所致）。**動態排程**（每分鐘 tick 比對 DB 可設定時點：`NewsPoller`→`crawler_schedule`、`MarketAnalysisScheduler`→`market_analysis_send_time`）於清單標「動態：依『X』頁設定（預設 …）」／「動態（表名）」，**不寫死時間**；每分鐘 tick 但時點為 per-user 私人設定者（`ExportScheduleService`／`TradingCalendarExportScheduleService`）則照列 `每分鐘`／`0 * * * * *` 實際 cron。前端 `ScheduleListView` 之服務別／分類計數由 payload 動態算出，故加減筆數無須改前端。無下游呼叫（不需 WebClient），落 BFF `anyExchange().authenticated()`（已登入者皆可讀）。
  - `CrawlerDataBffController`（CrawlerDataView 專屬，「公開資訊」分組，Requirement 38）：爬蟲資訊查詢頁，一頁一 BFF、WebClient 轉呼 business：
    - `GET /api/bff/crawler-data?date=YYYY-MM-DD&dateField=fetched|published&category=` → business `GET /api/news-headlines`：查指定日期爬回的 `news_headline`（與今日股市分析同讀一份表，符合「同義欄位、同一 business API」）。
    - `GET /api/bff/crawler-data/schedule` → business `GET /api/crawler-schedule?crawler=news-poller`：讀 NewsPoller 已設定的執行時間清單。
    - `PUT /api/bff/crawler-data/schedule` → business `PUT /api/crawler-schedule?crawler=news-poller`：整批覆寫執行時間清單。
    - `GET /api/bff/crawler-data/export-path` → business `GET /api/crawler-export-path?crawler=news-poller`：讀公開資訊 JSON 輸出子路徑與基底（Task 212），Task 245 起同時回 Drive 設定與上次上傳狀態。
    - `PUT /api/bff/crawler-data/export-path` → business `PUT /api/crawler-export-path?crawler=news-poller`：更新輸出子路徑（Task 212）與 Drive 開關／子路徑（Task 245）。**不新增第二支設定端點**——同一張設定表、同一支 business API，只是 DTO 多了 Drive 欄位。
    - `GET /api/bff/crawler-data/export-path/browse?subpath=` → business **既有** `GET /api/export-schedule/browse`：資料夾樹懶載入。**刻意不新增第五份目錄列舉實作**（同 Requirement 39／41／42 的沿用決定，CLAUDE.md「同義欄位、同一 business service API」）；BFF 端另立自己的路由則是「一頁一 BFF」要求。
    - `GET /api/bff/crawler-data/export-path/browse-gdrive?subpath=` → business `GET /api/export-schedule/browse-gdrive`：Google Drive 資料夾樹懶載入（Requirement 50 / Task 245）。與本機 `browse` 並列為兩支而非加參數（語意不同：本機基底 vs. Drive remote）；**「Drive 目錄列舉」全庫只准這一支**，其餘頁面日後接 Drive 一律沿用它（含交易日曆頁——其本機列舉自成一份 `trading-calendar-export/browse`，Drive 側不得再開第二份）。
    - 權限：`GET` 落 `authenticated()`；兩支 `PUT` 均限 ADMIN（`hasRole('ADMIN')`，屬系統設定變更——Drive 子路徑決定服務往使用者雲端硬碟寫入的位置，同理限 ADMIN）。

**Repository 層**（Spring Data JPA，共 47 個）
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
- `StockAlertGroupRepository`（複合 AND 條件群組；Task 253）
- `StockAlertGroupRecipientRepository`（群組 ↔ 收件人多對多 join；Task 253）
- `StockAlertTriggerRepository`（警示觸發歷史，30 天輪替；含 Requirement 54 的 owner-scoped 滾動視窗觸發 join 查詢——本表無 `owner_user_id` 亦未掛 `@Filter`，owner 一律由 `alert_id` / `group_id` join 取得）
- `StockAlertExportSettingRepository`（警示觸發即時匯出設定，per-user；Requirement 54）
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
│   ├── TaiexIndexPoller # 大盤盤中即時點位 → Redis（Task 228，見 Requirement 43 修訂 V6）
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
| `price:{market}:{code}` | JSON `{ price, prevClose, changePercent, open, high, low, volume, tradingDate, source, updatedAt }` | 24 小時（涵蓋整個交易日 + 跨夜，避免低流動性股票長時間 z='-' 後 TTL 過期退回昨收） | `PriceCacheWriter` 每 2 分鐘；`price:台股:0000`（大盤）自 Task 228 起亦納入同一 key schema，寫入者為 `TaiexIndexPoller`，`source` 標記含括號（如 `TWSE指數(5m)`）故不進 `IntradayTickStore`（那條 tick 序列供個股「今日走勢」用，與大盤既有的 transient `fetchIndexIntraday` 圖表資料源分離，不混用） |
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

**美股盤中 `openPrice` 補強（2026/05 初版用 NASDAQ /historical；2026/06 Task 120 改 Yahoo daily bar）：** NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`。初版改打 `https://api.nasdaq.com/api/quote/{code}/historical?...&fromdate==todate==美東今日`，但 NASDAQ 對「`fromdate == todate`」一律回 **400（`Provided date is less than from date`）**、給日期區間盤中又**不含今日列**，故該法恆失敗 → `openPrice` 永遠 null → Redis live 無 open → 盤後 dump 進 `stock_price_history` 亦無 open → 觀察清單「開盤」欄對所有美股恆顯示「—」。**現行作法**：`PriceFetchClient.fetchUsTodayOpenFromYahoo(code)` 打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}?interval=1d&range=1d`，取 `chart.result[0].indicators.quote[0].open[0]`（盤中＝今日部分 bar 的 open、盤後＝完整 bar 的 open；只取 open 欄、不碰 close，不違反 Task 84「禁寫今日列收盤」）。走 curl 子程序避開 Yahoo HTTP/2 fingerprint 偵測；為補強欄位遇 429 fail-fast 不重試（`curlGetWithRetry(url, 0)`），取不到回 null 由 `WatchStockService` 的 `stock_price_history` fallback 接手。HTTP 呼叫在 `PricePoller.updatePrices` 的 virtual-thread pool 中與其他 stock 並行執行，不會延長 cron 週期。台股 open 走 TWSE mis `o`。

**英股盤中 `openPrice` 修正（Task 194）：** `getYahooLsePrice` 原取 `meta.regularMarketOpen`，但該欄**實測不存在於 Yahoo chart meta**（2026-07-15 實測 CSPX.L / VUSA.L / VWRL.L 三檔皆 missing），英股 `openPrice` 因而恆為 null：`PriceCacheWriter` 以 `NON_NULL` 序列化 → Redis `price:英股:{code}` 的 `openPrice` **整個欄位缺席**（非 `"openPrice":null`）。**與美股 Task 120 修正前的差異在使用者可見症狀**：美股當時顯示「—」，英股則因 `WatchStockService:122-127` 對 `openPrice == null` fallback 至 `stock_price_history.findRecentN` 最近一筆、而倫敦盤中今日列尚未入庫，**觀察清單「開盤」欄靜默顯示「前一交易日的開盤價」**——有值但非今日，比空值更難察覺。16:32 LON `dumpUkCloseFromRedis` 抄 Redis 缺漏值 → DB 今日列 open 亦 null；17:00 LON `verifyUkCloseWithYahoo` 走 `fetchUkHistoricalRange`（open 取自 `indicators.quote[0].open[i]`，本來就正確）覆寫今日列後才修正 → **影響窗＝倫敦交易時段至 17:00 LON，收盤後自癒**，故長期未被發現。**現行作法**：與美股 / 韓股同一欄位落點，取 `chart.result[0].indicators.quote[0].open[0]`。另 `meta.previousClose` 亦不存在（昨收只有 `chartPreviousClose` 有值），原 `chartPreviousClose → previousClose` 的 fallback 為死碼，已移除。`meta.regularMarketDayHigh` / `regularMarketDayLow` / `regularMarketVolume` 實測存在且與 `indicators.quote[0]` 對應值一致，high / low / volume 續用 meta。

**TWSE `z='-'` 時 skip write — Redis 只能由真實 z tick 推進（2026/06，修正盤中股價跳回昨收的 bug）：** TWSE mis API 的 `z`（最近一筆成交價）以每 5 秒 tick 為單位，這一輪 cron polling 撞到「兩 tick 之間沒有新成交」的視窗時會回 `z='-'`。舊邏輯將 `z='-'` 視為「整天無成交」並退回 `y`（昨日收盤）寫 Redis（`source = "TWSE(前收)"`），盤中只要任一輪 polling 命中就會把 Dashboard 持股圖、即時資產估算的所有現值瞬間替換成昨收，造成「盤中股價與實際明顯偏差」。

**最終規則只有兩條（規格擁有者明確指示，覆蓋早期實驗性的 `TWSE(開盤)` cold-start 設計，見 Task 81 回退）：**

1. **每天開盤抓不到最新值 → 顯示昨日收盤**：`getTwseRealTimePrice` 在 `z='-'` 時回 `Optional.empty()`、不寫 Redis；若 Redis 為空，`PriceQueryService.getLive` 自然 fallback 至 `stock_price_history` 最近一筆收盤（=昨日收盤），讓前端始終有值可顯示。
2. **每次 tick 抓不到值 → 不要更新 Redis**：`PricePoller` 收到 `Optional.empty()` 直接 return，保留上一輪成功 poll 寫入的真實 z；不視為錯誤，不丟 exception（同時涵蓋 NASDAQ 查無資料的 fail-soft）。

`PriceCacheWriter.write` 收到任何 `PriceResult` 都無條件寫入（不再有「source 含 `(` 就守門」的特殊路徑），因為 client 端已保證不會送 `o` / `y` 衍生值上來。

**休市收盤快取完整性與 FinMind 日期守門（Task 235）：** `PriceCacheWriter.syncClosedFromDb` 先決定 DB 最新收盤的 `tradingDate`，再一律由 `StockSourceQuery.findPreviousCloseBefore(code, market, tradingDate)` 查 `stock_price_history` 中嚴格早於該日的最近收盤；不得沿用 Redis 既有 `previousClose`，因該值可能屬於舊 `tradingDate`。最後以 `dbClose - previousClose` 衍生 `priceChange`／`changePercent`。因此平盤仍輸出數值 0，不會被 Dashboard 的 null 顯示閘門隱藏。台股 16:00 FinMind 校正則在 `PriceFetchClient.getTwClosingPriceFromFinMind` 解析最後一列時同步解析來源 `date`，日期必須等於呼叫端要求的 `expectedDate`；T-1、缺日期或無法解析皆回 empty，使 `ClosePersister` 不寫 DB／Redis，避免把前一交易日 OHLC 複製成今日列。

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

**對外介面（`InternalPriceController`，class-level `@RequestMapping("/internal")`，共 28 支）：**

> **不對前端暴露 REST**；全部僅在 docker network 內由 `business-services` 呼叫（或維運手動觸發）。前端仍打 `business-services` 的 `/api/market-data/*`，由 `PriceQueryService` 從 Redis 取值。
> **本表為 `/internal/*` 契約的唯一出處**——本文件他處提及個別 `/internal/*` 端點時一律引用此表，勿另寫一份（同「同義欄位、同一來源」之文件版精神）。

| Method | Path | 說明 | 呼叫端 |
|--------|------|------|--------|
| POST | `/internal/refresh` | 同步抓所有持股一次、寫 Redis。供使用者按「刷新」時用；`markClosed` 依 `!isXxxMarketOpen()` 以免假日 append 假 tick | `MarketDataService` |
| POST | `/internal/dividend/sync` | 單檔股利同步入庫（`code` / `market`） | `MarketDataService` |
| POST | `/internal/fund-nav/refresh` | 基金淨值即時刷新 | `FundNavController` |
| POST | `/internal/fund-dividend/refresh` | 基金配息即時刷新 | `FundNavController` |
| POST | `/internal/fund-nav/backfill` | 基金淨值歷史回補 | `FundNavController` |
| POST | `/internal/fund-dividend/backfill` | 基金配息歷史回補 | `FundNavController`（proxyBackfill） |
| POST | `/internal/close/verify-tw` | 台股收盤資料驗證 | **無程式呼叫端**（手動維運） |
| POST | `/internal/close/verify-us` | 美股收盤資料驗證 | **無程式呼叫端**（手動維運） |
| POST | `/internal/backfill/stock` | 單檔股價歷史回補 | `HistoricalDataService` |
| POST | `/internal/backfill/all` | 全持股歷史回補 | `HistoricalDataService` |
| POST | `/internal/backfill/exchange-rate` | 匯率歷史回補 | `HistoricalDataService` |
| POST | `/internal/backfill/exchange-rate-from` | 指定起日之匯率回補 | `HistoricalDataService` |
| POST | `/internal/exchange-rate/refresh-bot` | 台銀（BOT）匯率即時刷新 | `HistoricalDataService` |
| POST | `/internal/tw-closure/detect` | 觸發台股臨時休市（颱風假）偵測 | `MarketDataService`（Task 160） |
| GET | `/internal/macro/imf` | IMF 總經資料（GDP 等） | `MacroHistoryService` |
| GET | `/internal/macro/dgbas` | 主計總處（DGBAS）總經資料 | `MacroHistoryService` |
| GET | `/internal/macro/twse-monthly` | 台股大盤月資料 | `MacroHistoryService` |
| GET | `/internal/macro/us-index` | 美股指數資料 | `MacroHistoryService` |
| GET | `/internal/macro/twse-return-index` | 台股報酬指數 | `MacroHistoryService` |
| GET | `/internal/macro/index-intraday` | 指數當日走勢 | `MacroHistoryService` |
| GET | `/internal/dividend-rate` | 單檔殖利率 | `MarketDataService` |
| GET | `/internal/etf-holdings` | ETF 成分股（台股優先 MoneyDJ 完整成分股 → Yahoo 前 10 fallback） | `MarketDataService` |
| GET | `/internal/dividend-history` | 單檔股利歷史 | `MarketDataService` |
| GET | `/internal/tw-holidays` | 台股假日表（TWSE holidaySchedule ∪ `tw_market_closure`，read-time union） | `MarketDataService` |
| GET | `/internal/stock-name` | 股票名稱查詢 | `MarketDataService` |
| GET | `/internal/intraday-5m` | 5 分 K 當日走勢 | `HistoricalDataService` |
| GET | `/internal/intraday-ticks` | 當日 tick 序列（含 cold-start；非交易日由 `clock.isTradingDay` 閘門擋下，Task 161） | `MarketDataService` |
| GET | `/internal/health` | 健康檢查 | `docker-compose.yml` healthcheck |

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
- BFF 以 `MarketDataBffRoutes` 的 passthrough route `/api/market-data/**` 轉發至 backend SSE endpoint
  （**不** rewrite 成 `/api/bff/` 前綴，此為「共享 / 跨頁 passthrough routes」列舉的共享例外）
- 前端用 `EventSource('/api/market-data/prices/stream')` 訂閱，每筆訊息更新 stockPrices reactive state

```
[external-materials-service write] → Redis SET price:*:*
                     └→ Redis PUBLISH price-update {json}
                                          ↓
                     [business-services PriceStreamService 訂閱] 
                                          ↓
                     [SSE /api/market-data/prices/stream]
                                          ↓
                     [BFF passthrough /api/market-data/**] → [Frontend EventSource] → stockPrices state
```

> ⚠ **已知落差（一頁一 BFF 未合規，待後續任務處理）：** 本段原記載前端訂閱 `/api/bff/market-data/stream`、BFF 另設專屬 rewrite route，但該 route **從未實作**。實際鏈路為前端 `DashboardView.vue` 與 `TradingRadarView.vue`（交易雷達雙擊看盤上線後成為第二個消費者，見 Task 234）→ nginx `location = /api/market-data/prices/stream` → BFF `MarketDataBffRoutes` 的 `/api/market-data/**` passthrough → business-services，**未經 `/api/bff/{page}/**`**，是全前端**僅有的兩處**繞過 axios wrapper 的裸 URL，違反「一頁一支 BFF」規範。修正方向為新增 `/api/bff/dashboard/prices/stream`（或共用 `/api/bff/market-data/stream`）route、**兩頁**皆改訂該路徑並同步改 nginx location（SSE route 需保留 `proxy_buffering off`）；屬功能變更，須另走 SDD 循環並重建 BFF 與前端映像。
> nginx 對 `/api/market-data/prices/stream` 路徑需設 `proxy_buffering off`，否則 SSE 會被 buffering 卡住（實作見 `frontend/nginx.conf` 的 `location = /api/market-data/prices/stream`）。
> 前端仍保留初始 GET `/api/bff/dashboard/realtime` 載入第一份 snapshot；之後增量更新走 SSE，不再用 setInterval polling。

#### external-materials-service Internal API（`InternalPriceController`，28 支）

**這些是 service 間內部端點，不是公開 API**：不經 BFF、不對前端暴露，僅在 docker network 內由 `business-services` 以 WebClient 呼叫（class 層 `@RequestMapping("/internal")` + method 層路徑；**全部參數皆為 query string，無 request body**）。此表為 28 支端點的**完整清單**（單一事實來源）；設計理由 / 退化行為凡 design.md 已有段落記載者，「說明」欄一律以**段落標題或 Task / Requirement 編號**交叉引用（不用行號，避免引用隨增刪行漂移），不在此重述（避免同一事實兩處記載各自漂移）。

> 例外：`close/verify-tw` / `close/verify-us` / `health` 三支目前**無 business-services 呼叫端**，為維運手動觸發（container 內 curl）與健康檢查用；其餘 25 支皆有 backend caller。

**行情 / 收盤（`PricePoller` / `ClosePersister` / `MarketClock`）：**

| Method | Path | 說明 |
|--------|------|------|
| POST | `/internal/refresh` | 同步抓所有持股報價、寫 Redis，回 `PricePoller.RefreshSummary`。供使用者按「刷新」（`PriceQueryService`）。見「對外介面」、「國定假日整段休市」（假日 `markClosed`）、「Live 行情 tradingDate 語意（盤外刷新防呆）」（`resolveTradingDate` 盤外守則） |
| POST | `/internal/close/verify-tw` | 手動觸發 FinMind 校正**當日台股**收盤價（同 16:00 排程），覆寫 `stock_price_history` 並回寫 Redis，回 `{verified:n}`。見「Sequence（盤後收盤）」 |
| POST | `/internal/close/verify-us` | 手動觸發 FinMind 校正**當日美股**收盤價（同 18:00 ET 排程），回 `{verified:n}`。見「Sequence（盤後收盤）」 |
| GET | `/internal/health` | 回 `{status:"UP", twMarketOpen, usMarketOpen}`（`MarketClock`）；健康檢查用 |

**歷史回補 / 匯率（`HistoricalBackfillService` / `ExchangeRatePoller`）：**

| Method | Path | 說明 |
|--------|------|------|
| POST | `/internal/backfill/stock?code=&market=&since=&until=` | 回補單支股票歷史收盤價（台股 FinMind、美股 / 英股 Yahoo）。`since` 必填、`until` 選填（皆 ISO date），回 `Map` 統計。**今日列不寫入**，見 Task 84「今日列」獨佔規則 |
| POST | `/internal/backfill/all` | 全持股 + USD 匯率 10 年回補（耗時操作），回 `Map` 統計。見 Task 84「今日列」獨佔規則 |
| POST | `/internal/backfill/exchange-rate?currency=&since=` | 增量補匯率至今日，回 `{currency, records}`。起點取 `max(rate_date)+1`，**`since` 僅在該幣別尚無任何列時作為起點** |
| POST | `/internal/backfill/exchange-rate-from?currency=&since=` | 強制自 `since` 補匯率至今日（補中間缺漏），回 `{currency, records}`。用於 FinMind T-1 對帳回補，見「匯率來源鏈」的 FinMind `TaiwanExchangeRate` 列 |
| POST | `/internal/exchange-rate/refresh-bot?currency=` | 手動觸發 BOT 即期匯率抓取（同盤中 5 分鐘 cron 的 `refreshBotNow`），回 `{currency, refreshed}`。見「匯率來源鏈」的台灣銀行牌告 CSV 列 |

**信託基金（Requirement 19–21）：**

| Method | Path | 說明 |
|--------|------|------|
| POST | `/internal/fund-nav/refresh` | 同步全抓基金 NAV 寫 `fund_nav`，回 `FundNavPoller.RefreshSummary`。business 端 `POST /api/fund-nav/refresh` proxy 至此，見「Funds / Fund NAV / Fund Dividend」段落 |
| POST | `/internal/fund-dividend/refresh` | 同步全抓基金配息歷史（Req 20），回 `FundDividendPoller.RefreshSummary`。business 端 `POST /api/fund-dividend/refresh` proxy 至此 |
| POST | `/internal/fund-nav/backfill?years=10` | 基金 NAV 歷史回補（Req 21，`years` 預設 10），回 `BackfillSummary`。見「Funds / Fund NAV / Fund Dividend」段落 |
| POST | `/internal/fund-dividend/backfill?years=10` | 基金配息歷史回補（Req 21，`years` 預設 10），回 `BackfillSummary` |

**個股資料 / 分時（`MarketDataFetchService` / `PriceFetchClient` / `IntradayTickStore`）：**

| Method | Path | 說明 |
|--------|------|------|
| POST | `/internal/dividend/sync?code=&market=` | Backend cold-cache fallback：抓單檔股利寫 `stock_dividend_history`，回 `{written:n}`。資料源見「股利歷史資料來源（stock_dividend_history）」段落；純讀路徑刻意不觸發此寫副作用，見 Task 170 的 `/api/market-data/dividends-readonly` |
| GET | `/internal/dividend-rate?code=&market=` | 殖利率（TWSE / FinMind / NASDAQ 級聯），回 `DividendRateResult`。business `/api/market-data/dividend-rate` 之來源，見「Market Data」段落 |
| GET | `/internal/dividend-history?code=&market=&years=10` | 股利歷史（`years` 預設 10），回 `DividendHistoryResult`。見「股利歷史資料來源（stock_dividend_history）」段落 |
| GET | `/internal/etf-holdings?code=&market=` | ETF 持股（Yahoo `topHoldings` + 台股 FinMind fallback），回 `EtfHoldingsResult`。business `/api/market-data/etf-holdings` 之來源，見「Market Data」段落 |
| GET | `/internal/stock-name?code=&market=` | 股票名稱查詢（台股 FinMind / 美股 Yahoo / 英股 Yahoo `.L`），回 `{name}`（查無回空字串）。為警示建立時的 canonical name 權威來源，見 Service 層 `StockAlertService` 的 `assertNameMatchesCode` 守門 |
| GET | `/internal/intraday-5m?code=&market=&daysBack=5` | 盤中 5 分鐘 K 線（`daysBack` 預設 5），回 `List<IntradayBar>`。供 `StockAlertService` 警示觸發補抓 |
| GET | `/internal/intraday-ticks?code=&market=&date=` | 「當日」走勢圖分時 tick 序列，回 `List<TickPoint>`。`date` 選填；省略時的預設 bucket 規則、cold-start refresh 與非交易日不 cold-start 的守則，見「StockAnalysisBffRoutes」的 `/api/bff/stock-analysis/intraday-ticks`（Task 153） |

**交易日 / 休市：**

| Method | Path | 說明 |
|--------|------|------|
| GET | `/internal/tw-holidays?year=` | TWSE 假日表（依年份快取，**已 union 颱風假 / 臨時休市**），回 `{date: reason}`。台股假日唯一來源，見「交易日 / 國定假日判定（單一事實來源）」與 Requirement 7 颱風假偵測的「單一注入點」 |
| POST | `/internal/tw-closure/detect` | 手動 / 驗證即時偵測台股颱風假（DGPA 停班公告），命中即寫 `tw_market_closure`，回 `{closedToday}`。見 Requirement 7「颱風假 / 台股臨時休市偵測」 |

**總經 / 指數（`MacroDataFetchClient`，供 `MacroHistoryService` proxy 後 upsert）：**

| Method | Path | 說明 |
|--------|------|------|
| GET | `/internal/macro/imf?indicator=&country=&scale=2` | IMF DataMapper 指標（`NGDPDPC` 人均 GDP / `NGDP_RPCH` GDP 成長率；`scale` 預設 2），回 `{year: value}`。作為 DGBAS 之備援，見「台灣人均 GDP / 實質成長率資料來源（DGBAS 優先、IMF 備援）」段落 |
| GET | `/internal/macro/dgbas` | 主計總處 DGBAS 國民所得常用資料（台灣官方，優先於 IMF），回 `{growth:{year:val}, gdpUsd:{year:val}}`；失敗回空 map 降級至 IMF。見「台灣人均 GDP / 實質成長率資料來源（DGBAS 優先、IMF 備援）」段落 |
| GET | `/internal/macro/twse-monthly?year=&month=` | TWSE 加權指數月線 OHLC（FMTQIK 月報整月），回 `List<DailyOhlc>`。供 `refreshTwseDaily` upsert `twse_index_daily_history`，見「Macro History」對應資料表的 `twse_index_daily_history` |
| GET | `/internal/macro/us-index?code=` | 海外指數近 10 年每日 OHLC（Yahoo v8 chart，`range=10y`）。`code ∈ {DJI, SPX, SP500TR, IXIC, SOX, FTSE, DAX, KOSPI, N225}`，回 `List<DailyOhlc>`。見「Macro History」對應資料表的 `us_index_daily_history` |
| GET | `/internal/macro/twse-return-index?date=` | TWSE 發行量加權股價報酬指數（含息）單日收盤，回 `TwseReturnIndexPoint`；**非交易日 / 查無回 204 No Content**。見 Task 170「含息（total return）演算法與資料管線」 |
| GET | `/internal/macro/index-intraday?market=` | 指數「當日」分時（Yahoo 5m，最新交易日；transient 不寫 DB）。`market ∈ {TWSE, DJI, SPX, IXIC, SOX, FTSE, DAX, KOSPI, N225}`，回 `List<IndexIntradayPoint>`。見「Macro History」的「當日」分時資料源段落 |

### Frontend Architecture (Vue 3)

```
src/
├── App.vue              # Root layout: sidebar navigation + router-view（含「公開資訊」sub-menu：交易日曆 / 台幣兌美元 / 排程列表，Requirement 36）
├── main.js              # App bootstrap, plugin registration
├── router/index.js      # Route definitions (32 routes，含 4 條 redirect)
├── stores/assetStore.js # Pinia global state
├── api/index.js         # Axios instance, API methods
├── components/          # Reusable components (TaiwanMap, UsFlag, StockAnalysisDialog)
└── views/               # Page-level components (29 views；含 StockMonitorView 內嵌的 WatchStockView / StockAlertView 兩個未掛路由的子 view)
```

**Service 層補充**
- `BackupService`: 透過 ProcessBuilder 呼叫 pg_dump / pg_restore / rclone，支援手動備份、列表（合併 manual/daily/weekly/monthly 四個資料夾）、還原（自動先建自救點）；內建 `@Scheduled` 自動排程：
  - `0 30 15 * * MON-FRI` Asia/Taipei：台股交易日 15:30（收盤後 2h）→ 上傳 `daily/asset_daily_tw_*.dump`
  - `0 0 7 * * TUE-SAT` Asia/Taipei：前一日為美股交易日時，台北 07:00（美東 16:00 收盤後 2h，涵蓋夏令／標準時）→ 上傳 `daily/asset_daily_us_*.dump`
  - `0 0 5 * * SUN` Asia/Taipei：每周日 05:00 → 上傳 `weekly/asset_weekly_*.dump`
  - 輪替策略：`daily/` 保留 50 份、`weekly/` 保留 5 份、`manual/` 保留 5 份（自救點不計入）
  - 輪替觸發點：(a) 每次排程／手動備份成功上傳後對該資料夾跑一次；(b) `updateSetting()` 儲存後對三個資料夾各跑一次（讓使用者調降保留代數時立即套用，不需等到下一次排程）。rotate 失敗以 `try/catch` 包住只記 log，不讓設定儲存 API 失敗
  - 交易日判定委派至 `MarketDataService.getTwHolidays(year)` / `getUsHolidays(year)`，並排除週末
- `WatchStockService`: 觀察清單 view 服務（不再對應實體表）。`findAll()` 由 `StockAlertRepository.findDistinctStockCodeMarket()` 取得去重 (stockCode, market) 清單後，整合 Redis live 報價（透過 `PriceQueryService`）、技術指標（`TechnicalIndicatorService.computeAll()` 回傳 `FullIndicators`：月線 MA20／季線 MA60／年線 MA240／K／D，填入 `WatchStockDto.Response` 的 `monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue`）與該股票最近一次 StockAlert 觸發資訊；`reorder(orderedStockKeys)` 拖曳重排時把每個股票所有 alert 的 `displayOrder` 整組依新順序重新指派；不提供 delete 入口（移除觀察一律由 `StockAlertService.delete` 在「警示條件」頁逐筆刪除）。**「警示」欄顯示窗**：最近觸發（時間／股價／月線／季線／年線／KD）與「警示條件」紅字只顯示落在 `StockAlertService.FRESHNESS_TRADING_DAYS`（= 2，最後交易日及前一日）內的觸發，cutoff 由 `recentTradingDayCutoff(market, 2)` 取 `stock_price_history` 最近 2 個 distinct `trading_date` 之較早一天午夜；與警示頁 `StockAlertService.toResponse` 共用同一常數確保兩頁口徑一致（同義欄位同一來源）。此 UI 顯示窗與 `stock_alert_trigger` 保留 30 天為兩個獨立概念。**「警示條件」欄的 MA% 觸發價**：`buildConditions()` 把已算好的 `FullIndicators` 一併傳給 `StockAlertService.buildLabel(alert, ind)`，`MA_*_PCT` 且 threshold≠0 的條件於 label 附換算後的觸發價（`對應均線 × (1 ± pct/100)`，如「高於季線 20%（360）」），重用同一份即時均線值、不另查（Task 146，詳 Requirement 16）。**複合 AND 群組（Task 253）**：`buildConditions()` 把 `group_id` 相同的成員合併成**一條** `Condition`（label 以 `" 且 "`（前後各一個半形空白）串接、`triggered` 取該群組的 `lastTriggeredAt` 而非成員的），不再逐條列出——否則使用者在觀察頁看到的是散開的多條，看不出那是「同時成立才觸發」；「警示」欄的最近觸發彙總（`lastAlert`）亦需納入該股票所有群組的 `lastTriggeredAt` 一併取 max
- `StockAlertService`: 到價警示 CRUD、條件評估、排序、最近觸發資訊回寫；`create` 對 `0000`（台股大盤）跳過 `stockMasterRepo.upsert`。**複合 AND 群組（Task 253）**：群組 CRUD 與評估同住本 service（群組與獨立條件共用 `matches(alert, currentPrice)`、`buildLabel`、`computeTriggeredAt`、freshness cutoff，拆成獨立 service 會複製這五處而必然分歧）；`checkAlerts` / `checkAlertsFor` 掃描獨立條件時查詢改為 `findByActiveTrueAndGroupIdIsNull()`，再另掃 `stockAlertGroupRepo.findByActiveTrue()` 逐一 `evaluateGroup`。群組成員的 `active` 恆為 true、`last_triggered_*` 不寫，觸發狀態一律記在群組上。`create` / `update` 在 `stockMasterRepo.upsert` 之前以 `assertNameMatchesCode(code, market, userName)` 守門：以 `historicalDataService.fetchTwStockName / fetchUsStockName` 取得外部 canonical name（權威來源 ext-materials-service `/internal/stock-name`），canonical 非空且與 user-supplied `stockName` 不一致時，**再對照 `stockMasterRepo.findByCodeAndMarket(code, market).name`（本地主檔亦視為合法 canonical）**；兩者皆不相符才 throw `IllegalArgumentException`（由 `GlobalExceptionHandler` 映射為 `400`）。canonical 空則 fall through（外部 API 異常時不阻擋）。`0000` + `台股` 跳過守門；`0000` + `美股` 直接拒絕。設計目的：阻止使用者把錯誤代號（如把 2500 標成「台積電」）寫入 `stock` 主檔導致觀察清單出現名稱對但完全無報價的列；同時避免「外部來源回應與本地主檔不一致」（例 Yahoo `shortName` 「NVIDIA Corporation」vs 主檔過去寫入的 「NVIDIA Corporation Common Stock」）造成 lookupName 自動帶名後 save 被自己擋下
- `StockAlertTriggerExportService`（Requirement 54）: 警示觸發的即時 JSON 匯出。由 `StockAlertService.recordTrigger` / `recordGroupTrigger` 在寫入 `stock_alert_trigger` 之後呼叫，依該觸發的 owner（`alert_id` / `group_id` join 取得）重查其**最近 3 個台北日曆日**（`created_at` ≥ 今日−2 天的 00:00）全部觸發並全量重寫 `alert_triggers_{ownerUserId}.json`（**固定檔名、不含日期**）。**三個入口、語意各異**：`writeExport(ownerId)`（真正產檔，取 per-owner 鎖）／`exportForTrigger(ownerId)`（看 `enabled` 閘門 ＋ 套 60 秒去抖）／`runNow(ownerId)`（**不看 `enabled`**——它是驗證工具；**不套去抖**，且**不更新去抖的 `lastUploadAt`**，否則按一次按鈕就讓觸發路徑靜默停 60 秒）。**本機同步寫、Drive 非同步合併**：本機以 tmp（**檔名帶 `UUID` 唯一後綴**——三條路徑寫同一個檔名，固定 `.tmp` 會讓後到者 `Files.move` 撞 `NoSuchFileException`）＋ `ATOMIC_MOVE` 在觸發執行緒完成（毫秒級），並以 per-owner 鎖序列化三條寫檔路徑（Redis 訂閱者執行緒的觸發、`POST /api/stock-alerts/check` 的 HTTP 執行緒、run-now 的 HTTP 執行緒）；Drive 上傳丟單執行緒 `ScheduledExecutorService`，以「**已排定任務旗標 ＋ 最近一次實際上傳完成時間**」延後合併（最小間隔 60 秒，見下方禁令），一律走 `GdriveOutputSupport.syncQuietly`（含每次上傳前的 owner 複驗），**不自行呼叫 rclone、不自行判權限**。股名走 `StockMasterService.resolveNameLocalOnly`（本地限定；**不是**既有的 `resolveName`——那一支查無主檔時會打外部行情 API 並寫回主檔、排 10 年回補，在 Redis 訂閱者執行緒上做這些正是本設計要防的事）。整段包 try/catch，任何失敗只記 log 與狀態欄
- **交易日 / 國定假日判定（單一事實來源）**：`business-services` 側以 `MarketDataService.isTradingDay(market, date)`（dispatch 至 `isTwTradingDay/isUsTradingDay/isUkTradingDay`）與 `isMarketOpenNow(market)`（`MarketZones` 時段 + `isTradingDay` 假日）為唯一入口。`StockAlertService.evaluate`（live 觸發閘門）/ `computeTriggeredAt`（交易時段判斷）、`AlertNotificationDispatcher.withinSendWindow / lastTradingDate`、`StockPriceService.getMarketStatus`（→ `isXxxMarketOpen`）全部委派之，不再各自只判週末。`external-materials-service` 側對應為 `MarketCalendar` + `MarketClock`（抓價 / 收盤排程閘門）。台股假日權威 = TWSE holidaySchedule（business-services 經 `/internal/tw-holidays` proxy、ext-materials 直接 fetch，同一份）；美 / 英假日為 NYSE / LSE 法定規則純函式，因兩服務無共用 module 而各持一份（交叉註解鎖定，修改須同步）。Requirement 7 / 16 / 23
- **颱風假 / 台股臨時休市偵測（Requirement 7）**：颱風等臨時停班停課由地方政府當日 / 前一晚公布，**不在** TWSE 年度 holidaySchedule 中；證交所休市與否，法規上取決於「臺北市政府是否宣布停止上班」。故在既有國定假日機制外，另建一條「當日偵測 → 台股假日 override」資料流：
  - **權威來源**：行政院人事行政總處（DGPA）「天然災害停止上班及上課情形」`https://www.dgpa.gov.tw/typh/daily/nds.html`（HTML）。解析臺北市列的「今天」狀態字（`<FONT>` 內文，`TwTyphoonClosureService.parseTaipeiTodayClause`）；`closedForTrading` 判定：含「停止上班」且非「晚上 / 傍晚 / 夜間」限定、且無「HH:MM 起」HH:MM ≥ 13:30 → **休市**（全日 / 上午 / 下午覆蓋 09:00–13:30）；含「照常上班」/ 僅晚間或收盤後起停班 → 交易日。
  - **資料表 `tw_market_closure`**（全域參考、無 owner）：`closure_date DATE PK`、`reason`、`source`（'DGPA'）、`raw_status`（DGPA 原文供稽核）、`detected_at`。ext-materials 以 JdbcTemplate 直寫（`TwMarketClosureQuery`）；backend 不直讀，經 `/internal/tw-holidays` proxy 取已 union 結果。
  - **單一注入點**：`MarketDataFetchService.getTwHolidays(year)` 於回傳 TWSE 年度假日前，read-time union `TwTyphoonClosureService.closuresForYear(year)`（DB → in-memory 快取，不污染 TWSE per-year 快取）。因 `MarketCalendar.isTwHoliday` 與 `/internal/tw-holidays` 皆匯集於此方法，颱風休市對台股所有下游（market-status、`MarketClock`/`PricePoller`/`ClosePersister` 抓價收盤閘門、警示、備份、08:45 分析、交易日曆）與國定假日同一 cascade 自動一體生效，**前端零改動**（`TradingCalendarView` 既有 `day.twHoliday` 渲染 + market-status 開盤中→休市）。
  - **排程**：`TwClosurePoller`（ext-materials）`0 0/15 5-6 * * MON-FRI；0 0 7 * * MON-FRI`（＝05:00–07:00 Asia/Taipei，Task 187 由 5-8＝05:00–08:45 縮短；含 07:00 需兩條 cron）開盤前每 15 分鐘爬 DGPA + `ApplicationReadyEvent` self-heal 補跑一次（部署後立即修正當日、含收盤後補進日曆供回溯）。
  - **傳播（business 端快取設計）**：`business-services` `MarketDataService.getTwHolidays` 對**當年度**改採短 TTL（10 分鐘）快取 `twHolidayCurrentYearCache`（過去 / 未來年度仍 `twHolidayCache` 永久快取）。理由：颱風假可能於**任何時刻**（早盤排程、開機 self-heal、手動 `detect`）由 ext 寫入，若沿用永久 per-year 快取＋僅早晨時窗 evict，則 07:00 後（＝颱風 poller 最後一 tick 之後；含盤中部署 self-heal / 手動觸發 / 07:00 tick race。此 07:00 為 poller 時窗尾、與 08:45 分析時刻語意不同勿混）才偵測到的休市無法當日傳播、整個交易日誤判為交易日。短 TTL 保證同日任何偵測於 ≤10 分鐘內傳播至 market-status / 分析 / 警示 / 備份 / 交易日曆，不依賴固定時窗。另 `fetchTwHolidaysFromExt` 逾時 / 失敗回空表時**不寫入快取**（沿用前一次成功值），避免一次瞬斷把整年假日毒化為零筆。退化：DGPA 失敗 / 查無臺北市狀態 → 保守維持交易日；只新增、不覆寫 TWSE 固定假日。內部觸發 `POST /internal/tw-closure/detect` 供手動 / 驗證。
  - **資料層一體休市（偵測落後補清；Task 161）**：偵測有時序落差——颱風假可能盤中甚至收盤後才公告 / 偵測到（如部署晚於當日盤中）。在偵測寫入 `tw_market_closure` 前 `isTwTradingDay` 仍回 true，`ClosePersister.dumpTwCloseFromRedis`（13:32）把 Redis 昨收平盤 dump 進 `stock_price_history` 當日列、盤中 polling 把昨收 tick 塞進 Redis `price:ticks:台股:*:date` bucket → 「當日」走勢圖顯示昨收平盤線、`InternalPriceController.intraday-ticks` 預設日期經 `findMaxTradingDate` 落回該休市日、且假日列污染日線 / 均線。故 `TwTyphoonClosureService` 於**偵測命中**（`detectAndPersistToday` upsert 後）與 **`ApplicationReadyEvent` 開機 self-heal**（`selfHealClosureMarketData` 逐一掃本年度 `closuresForYear`）兩路徑呼叫 `purgeClosureMarketData(date)`：`StockSourceQuery.deleteTwHistoryOn(date)` 刪台股當日 `stock_price_history` + `IntradayTickStore.purgeTwTicksOn(date)` 刪台股當日 Redis 分時 bucket。**嚴格限市場字串 `台股`**——英股 / 美股同日照常交易，其 bucket 與日線不得清；**不動即時價 live cache** `price:{market}:{code}`（休市日 last price = 昨收，本就是正確現價，且比照 price cache 跨夜耐久性不回寫充數）。清除後 `findMaxTradingDate` 回休市前一交易日，「當日」分時端點自然回退（如 7/10 颱風假 → 顯示 7/9），與交易日曆一體休市一致。持久性：真休市日外部日線源本無該日 bar、`backfillTwStock` 亦 skip 今日列，故清除後不被回補重灌。redeploy（image rebuild + container recreate）即觸發開機 self-heal，無需手動 SQL / redis-cli。
- `ExcelExportService`: Apache POI 產生快照與已實現損益的 .xlsx 匯出檔
- `SnapshotDateRollScheduler`（Requirement 35 / Task 174）：每日 `0 5 0 * * *` Asia/Taipei 把**每個 owner 各自最新一筆** `asset_snapshot` 的 `snapshot_date` 釘成當日，並以 `AssetService.recalcTotals` 重算該筆匯總。動機：BFF `LiveAssetsOverlay.applyToLatest` 的 per-market 基準日閘門僅覆蓋「最新快照日 == 該市場今日」的市場即時價，最新快照停在過去日期時三市場皆不覆蓋 → 資產顯示過去凍結收盤；釘成當日即打開閘門。背景無 request context → `ownerFilter` 不啟用，以 `AssetSnapshotRepository.findDistinctOwnerUserIds()` + 帶 owner 的 `findFirstByOwnerUserIdOrderBySnapshotDateDesc` 逐 owner 隔離（比照 `ExportScheduleService`）。`snapshotDate < 今日才 roll`（== 今日／未來日期 no-op）兼作 `(owner_user_id, snapshot_date)` 唯一鍵防護。**只重算被 roll 的最新一筆、不動歷史快照**（且避免 `recalcAllDividends` 每日對全 owner 全歷史重抓 NAV／配息的外部副作用）。迴圈置於 scheduler bean、逐 owner 呼叫 `AssetService` public `@Transactional rollLatestSnapshotToTodayForOwner`（每 owner 獨立交易，免 self-invocation 繞過 proxy 使整批同一交易連坐 rollback）；`@EventListener(ApplicationReadyEvent)` 另起 thread sleep 30s 後 self-heal 補跑當日、`volatile LocalDate` 當日 guard（roll 冪等，多跑無害）。無 DB schema 變更（僅 UPDATE 既有列）。

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
| `/schedule-list` | ScheduleListView | 排程列表（後端各定時任務／爬蟲時間一覽） |
| `/crawler-data` | CrawlerDataView | 爬蟲資訊查詢（依日期查 `news_headline` 爬回資料 ＋ 設定 NewsPoller 多個執行時間；Requirement 38） |
| `/gdp-twse` | GdpTwseView | 股市大盤查詢（指數日線／當日＋台韓人均 GDP；Requirement 18） |
| `/performance-comparison` | PerformanceComparisonView | 績效比較（個股 vs benchmark 報酬率；Requirement 33） |
| `/today-market-analysis` | TodayMarketAnalysisView | 今日股市分析（AI 判斷當日台股走向；Requirement 31） |
| `/asset-allocation-advice` | AssetAllocationAdviceView | 資產配置建議（依個人條件＋持有資產由 AI 給配置建議；Requirement 32） |
| `/settings/banks` | BankSettingsView | 銀行設定管理 |
| `/settings/brokers` | BrokerSettingsView | 券商設定管理 |
| `/settings/deposit-types` | DepositTypeSettingsView | 存款類型設定管理 |
| `/settings/market-types` | MarketTypeSettingsView | 市場類型設定管理 |
| `/settings/asset-classes` | AssetClassSettingsView | 資產類別／風格／債券期別歸類管理（Requirement 25–27） |
| `/settings/transit-fund-types` | TransitFundTypeSettingsView | 待轉入資金類型設定管理 |
| `/settings/funds` | FundSettingsView | 信託基金主檔設定管理（Requirement 19） |
| `/settings/backup-restore` | BackupRestoreView | 資料庫備份／還原（`requiresAdmin`） |
| `/settings/users` | UserManagementView | 使用者管理（`requiresAdmin`；核准待審帳號、角色管理） |
| `/settings/notifications` | NotificationSettingsView | 警示通知收件人設定（Requirement 23） |
| `/payment-accounts` | PaymentAccountSettingsView | 代繳帳戶記錄管理（Requirement 22）；舊路徑 `/settings/payment-accounts` 自動 redirect |
| `/stocks` | StockMonitorView | 股票觀察（含「觀察清單」、「警示條件」兩個頁籤；舊路徑 `/watch-stocks`、`/stock-alerts` 自動 redirect 並帶 `tab` query） |
| `/pending` | PendingApprovalView | 帳號等待核准頁（`hidden`；未核准使用者登入後導向） |

## Data Model

### Entity Relationship Diagram

```
Bank        (1) ──── (N) BankDeposit
BrokerEntity(1) ──── (N) StockHolding

AssetSnapshot (1) ──── (N) BankDeposit
AssetSnapshot (1) ──── (N) StockHolding
AssetSnapshot (1) ──── (N) FundHolding
RealizedGain          (獨立，不關聯快照)
AssetTransaction      (獨立買賣流水帳，不關聯快照；不自動衍生 realized_gain／stock_holding；Requirement 49)

# 多租戶 / 認證（Requirement 28）
AppUser               (使用者主檔，PK = id；email UNIQUE；name / picture〔Google 帳號顯示名稱與頭像，皆 nullable〕；role ADMIN/USER；status PENDING/ACTIVE/DISABLED；created_at / updated_at 皆 NOT NULL；主要管理者由 ADMIN_EMAIL 即時判定，不另存重複欄位）
AppUser (1) ──── (N) AssetSnapshot          (owner_user_id；子表 bank/stock/fund holding 經 snapshot 繼承 owner)
AppUser (1) ──── (N) RealizedGain           (owner_user_id)
AppUser (1) ──── (N) AssetTransaction        (owner_user_id；手動買賣流水帳；Requirement 49)
AppUser (1) ──── (N) PaymentAccount         (owner_user_id)
AppUser (1) ──── (N) StockAlert             (owner_user_id；trigger/recipient join、watch_stock 衍生皆繼承)
AppUser (1) ──── (N) NotificationRecipient  (owner_user_id)
# 受隔離表唯一性多租戶化：asset_snapshot UNIQUE(owner_user_id, snapshot_date)；
#                          notification_recipient UNIQUE(owner_user_id, email)
# 參考/行情/設定主檔（Bank, BrokerEntity, Stock, *_History, MarketType, AssetClass, FundMaster...）為全系統共用，不加 owner

# 主檔 / 設定類（不寫死 enum，由 DataInitializer seed）
Stock                 (個股主檔，PK = code + market；name NOT NULL〔股名，v1.9.4 起各表不再存冗餘 stock_name、一律由本檔 join 補上〕；nullable override 欄 asset_class / stock_style / bond_term)
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
StockAlertGroup (1) ──── (N) StockAlert     (group_id nullable；非空＝AND 群組成員，不自行觸發；Task 253)
StockAlertGroup (N) ──< stock_alert_group_recipient >── (N) NotificationRecipient  (群組挑選收件人；Task 253)
StockAlertGroup (1) ──── (N) StockAlertTrigger  (trigger.group_id；與 trigger.alert_id 恰好一個非空；Task 253)

# 基金（Requirement 19–21）
FundMaster            (信託基金主檔，PK = fund_code)
FundNav               (基金 NAV 歷史)
FundDividendHistory   (基金配息歷史)

# 行情 / 歷史
StockPriceHistory     (歷史股價紀錄；live 行情改由 Redis 提供)
StockDividendHistory  (個股配息歷史，殖利率 / 填息天數計算用)
ExchangeRateHistory   (歷史匯率紀錄)
ForeignStockDailyHistory (→ foreign_stock_daily_history，海外參考個股每日收盤〔韓股 三星電子 005930 / SK 海力士 000660〕，PK = (stock_code, trading_date)、close_point NUMERIC(18,4) NOT NULL；全域參考、無 owner、無 backend entity——ext-materials 的 KrStockPoller 以 JdbcTemplate 直寫，供公開資訊韓股快照〔category=kr-market〕算漲跌%；Task 185)

# 警示
StockAlert            (到價警示，獨立資料表；觀察清單由此表 GROUP BY (stockCode, market) 衍生)
StockAlertGroup       (複合條件群組，群組內條件全部同時成立才觸發；成員為 group_id 非空的 StockAlert)
StockAlertTrigger     (警示觸發歷史，FK→stock_alert / stock_alert_group 恰一，保留 30 天)
StockAlertExportSetting (觸發即時匯出 JSON 的 per-user 設定；事件驅動、無執行時刻欄位；Requirement 54)

# 總經 / 指數（Requirement 18）
TaiwanGdpPerCapitaHistory / JapanGdpPerCapitaHistory / KoreaGdpPerCapitaHistory   (人均 GDP + 實質成長率)
TwseIndexDailyHistory / UsIndexDailyHistory            (大盤 / 海外指數每日 OHLC)
twse_index_year_end_history                            (台股大盤年末收盤，PK = year、close_point NUMERIC(12,2)；v1.16.0 建表並 seed 1996–2025。**保留但已停用**——Task 97 移除「年末走勢圖卡」後 entity / repository / endpoint 皆已刪除，惟資料表未 DROP、仍存在於 DB)

# 備份（Requirement 15）
BackupSetting         (備份保留代數設定，單列資料表，id = 1)
BackupRecord          (Google Drive 備份檔本地索引，UNIQUE(folder, filename))

# AI 市場分析 / 新聞（Requirement 31、36）
DailyMarketAnalysis      (今日股市分析結果，PK = analysis_date；bias/confidence/summary/key_factors/news_highlights/tw_context/us_context/model/status)
MarketAnalysisSetting    (市場分析設定，單列 id = 1；model / effort / enabled；web_search 相關欄於 Task 179 移除)
News                     (→ news_headline，爬蟲新聞標題，全域參考、無 owner；供今日分析與公開資訊 SRPP JSON)
CrawlerSchedule          (→ crawler_schedule，公開資訊爬蟲執行時間設定，全域參考、無 owner；一列一時間點〔crawler_key + run_hour + run_minute + enabled〕；由「爬蟲資訊查詢」頁維護、NewsPoller 每分鐘讀取；Requirement 38)
CrawlerExportSetting     (→ crawler_export_setting，公開資訊爬蟲輸出檔案路徑設定，全域參考、無 owner；一爬蟲一列〔crawler_key UNIQUE + output_subpath〕；由「爬蟲資訊查詢」頁維護、NewsPoller 每輪寫檔前讀取；Requirement 38 / Task 212。Requirement 50 / Task 245 加 gdrive_enabled + gdrive_subpath + gdrive_last_run_at + gdrive_last_status：本機照寫不變，Drive 為附加副本；**後兩欄由 ext 的 NewsPoller 寫入**——刻意的所有權例外，上傳結果只有 ext 知道，但 ext 只碰這兩欄、UPDATE 命中 0 列不得 upsert，列的所有權仍在 backend)
MarketAnalysisSendTime   (→ market_analysis_send_time，分析寄送時間，全域參考、無 owner；一列一時點〔send_time UNIQUE + active〕，seed 08:45；由「今日股市分析」頁維護、MarketAnalysisScheduler 每分鐘比對；Requirement 31 / Task 191)

# 資產配置建議（Requirement 32）
AppUser (1) ──── (1) InvestmentProfile           (owner_user_id UNIQUE；理財條件，記住免重填)
AppUser (1) ──── (N) InvestmentPlannedExpense     (owner_user_id；特定日期大筆花費，一使用者多筆)
AppUser (1) ──── (N) PortfolioAdvice              (owner_user_id；歷次建議，條件快照刻意 denormalize)
PortfolioAdviceSetting   (配置建議設定，單列 id = 1；model / effort / web_search_max_uses)

# 排程匯出（Requirement 34 / 37 / 39 / 49）
# 「一功能一張排程表」——刻意不合併，理由見本文件 Requirement 39 之關鍵設計決策
AppUser (1) ──── (1) ExportScheduleSetting            (owner_user_id UNIQUE；歷年資產每日排程自動匯出設定；Requirement 34)
AppUser (1) ──── (1) TradingCalendarExportSchedule    (owner_user_id UNIQUE；交易日曆每日排程匯出設定；比另兩張多一個 format 欄〔json/excel〕；Requirement 37)
AppUser (1) ──── (1) RealizedGainExportSchedule       (owner_user_id UNIQUE；已實現損益每日排程匯出設定；Requirement 39)
AppUser (1) ──── (1) AssetTransactionExportSchedule   (owner_user_id UNIQUE；交易紀錄每日排程匯出設定；Requirement 49)

# 台股臨時休市（颱風假；Requirement 7）
TwMarketClosure       (台股臨時休市，PK = closure_date；全域參考、無 owner、無 backend entity——ext-materials 直寫、backend 經 /internal/tw-holidays proxy 讀 union)

# 無 JPA entity 之資料表（ext-materials 以 JdbcTemplate 直寫；列此以免稽核誤判缺漏）
foreign_stock_daily_history  (海外參考個股每日收盤；韓股三星電子 005930 / SK 海力士 000660，僅存 close_point，供公開資訊爬蟲組韓股快照 category=kr-market。與 us_index_daily_history 分表——那張語意為「指數」；與 stock_price_history 分表——那張為投組個股。見 Task 185。刻意不建 entity，由 KrStockPoller 以 JdbcTemplate 直寫，changelog v1.55.0 已註明)
twse_index_year_end_history  (TWSE 指數年末值；Task 97 起已不使用——年末走勢圖卡移除、entity/repo/endpoint 已移除，資料表保留不刪)
```

> **Schema 基準線與 DB 層唯一鍵（重要澄清）：** 本專案的資料表基準線由 `db/init/01_dump.sql`（完整 `pg_dump` 快照，掛載進 `docker-entrypoint-initdb.d`）提供，**非** Liquibase 的 `v1.0.0-initial-schema` changeset；Liquibase 僅在此基準線之上做**增量**變更（dump 已含 `databasechangelog` 歷史，過往 changeset 視為 already-ran）。因此下列 Hibernate 早期建立、已固化進 dump 的 DB 層約束**不會出現在 Liquibase changelog**，但每個環境（運行中＋全新以 dump 初始化）皆已存在，`ddl-auto: none` 亦不會重建：
>
> 📌 **查證來源：運行中的 DB。** `docker exec asset-postgres psql -U assets -d assets -c '\d <table>'`——欄位型別／位數／nullable 一律以它為準。
>
> ⚠ **`db/schema.sql` 不是可信基準線，只能當離線參考。** 它是 `db/init/01_dump.sql`（含真實個人財務資料，被 `.gitignore` 排除）「去除全部資料」後的可版控鏡像，但**靠人工重新產出、實測已落後**：截至 Task 245 它只有 55 張 `CREATE TABLE`，缺 `crawler_export_setting`（v1.64.0）／`asset_transaction`（v1.72.0）／`index_export_schedule`／`trading_radar_export_setting`／`asset_transaction_export_schedule`／`trading_radar_export_time`。在裡面查不到某張表時，先確認是「真的沒有」還是「鏡像沒跟上」。**同理不要引用 `db/changelog/**` 描述現況**——那裡有永不執行的 changeset（下方 `v1.0.0` 的 `NUMERIC(20,4)` 即為前例）。
> - `stock_price_history`：`UNIQUE (stock_code, market, trading_date)`（Hibernate 名 `ukgoyp…`）＋ `INDEX idx_sph_code_date (stock_code, trading_date)`；**以 `db/init/01_dump.sql` 為準**：OHLC 皆 `NUMERIC(15,4)`（`open/high/low` nullable、`close` NOT NULL，見 v1.14.0）、`volume BIGINT`（nullable）。
>   ⚠ `v1.0.0-initial-schema.sql` 寫的是 `NUMERIC(20,4)` ＋ `volume NOT NULL`，但該 changeset 在 dump 中已標記 already-ran、**永不執行**，故 20,4 從未套用到任何環境——查證位數/nullable 一律以 dump 為準，勿照抄 v1.0.0。
>   ✅ **已對齊（Task 201）**：Entity `StockPriceHistory` 曾長期宣告 `precision = 20` 與 `volume nullable = false`（Task 148 照著永不執行的 `v1.0.0` changelog 改，反而改成與 DB 不一致），現已改為四個 OHLC 皆 `precision = 15` 且 `volume` 移除 `nullable = false`，與 DB 相符。依據可自 repo 直接查證：見 `db/schema.sql` 的 `stock_price_history`（`close_price numeric(15,4) NOT NULL`、`open/high/low_price numeric(15,4)` 可空、`volume bigint` 可空）。
> - `exchange_rate_history`：`UNIQUE (currency, rate_date)`（Hibernate 名 `uk977p…`）＝ upsert 覆寫鍵；`buy_rate/sell_rate NUMERIC(10,4)`，**兩者皆 nullable**（`db/schema.sql` 實測；Entity 未標 `nullable=false` 屬正確，反倒是 `v1.0.0-initial-schema.sql` 寫的 `NOT NULL` 與 DB 不符——同樣因該 changeset 永不執行而未套用）。
>
> 稽核提醒：只讀 Liquibase changelog 會誤判「DB 無唯一鍵、無去重保護」；實際 DB 已有上述約束（重複列數為 0），故**不需**再補 `ADD CONSTRAINT` changeset（會產生重複約束）。

### Core Entities

#### AssetSnapshot
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| ownerUserId | Long | 擁有者（Requirement 28 多租戶；`@Filter(name="ownerFilter")`） |
| snapshotDate | LocalDate | 快照日期（與 `ownerUserId` 組成複合唯一鍵 `uq_snapshot_owner_date`，見 ERD 的「受隔離表唯一性多租戶化」註記） |
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
| stockName | String | 股票名稱（由 `stock` 主檔 join 補上，v1.9.4 起不存冗餘 `stock_name`，實體無此欄位；DTO 經 `StockRepository.findByCodeAndMarket()` 補值） |
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
| ownerUserId | Long | 所屬使用者；多租戶隔離欄，nullable=false |
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

#### AssetTransaction（交易紀錄／手動買賣流水帳，Requirement 49）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| ownerUserId | Long | 所屬使用者；多租戶隔離欄，nullable=false，`@Filter(ownerFilter)` |
| transactionType | String(10) | 交易類型：買 / 賣（字串，非寫死 enum），nullable=false |
| assetType | String(10) | 資產類型：股票 / 基金（字串，非寫死 enum），nullable=false |
| assetName | String(50) | 資產名稱，nullable=false |
| assetCode | String(20) | 資產代號（股票代號／基金代碼），可空 |
| market | String(20) | 市場（股票用，對應 MarketType.code；基金可空） |
| currency | String(10) | 幣別（TWD/USD） |
| channel | String(30) | 券商／通路（成交當下名稱字串，刻意 denormalize，比照 realized_gain.broker） |
| tradeDate | LocalDate | 交易日期，nullable=false |
| shares | BigDecimal(15,5) | 數量（股數／單位數） |
| price | BigDecimal(17,6) | 成交單價（原幣，小數 6 位；Task 239 由 (15,4) 加寬） |
| amount | BigDecimal(20,2) | 成交金額（原幣，含手續費／交易稅後之實際交割金額），nullable=false |
| exchangeRate | BigDecimal(10,4) | 交易當天匯率（USD 計價時使用） |
| notes | String(500) | 備註，可空 |
| year | Integer | 年度（@Transient，由 tradeDate.getYear() 計算，不入庫） |

> **定位：** 獨立的買賣流水帳（flow），與 `RealizedGain`（僅賣出且已結算之損益）、`AssetSnapshot`（時點存量 snapshot）語意不同。三者**互不自動衍生、不共用資料表**——交易紀錄不自動產生／修改 `realized_gain`／`stock_holding`／`asset_snapshot`，亦不由它們反推，避免同一事實跨表存兩份（見 Requirement 49 設計章節）。
>
> **正規化：**
> - `amount`（成交金額）為含費用後之實際交割金額，與 `shares × price` 不必然相等（手續費、交易稅、零股撮合價差），故 `shares`／`price`／`amount` 為各自獨立輸入、非彼此衍生（比照 `realized_gain` 同時存 `shares`／`salePrice`／`proceeds`／`investmentCost`）。
> - `year` 由 `tradeDate` 即時衍生（`@Transient`），不入庫。
> - `amountTwd`（台幣成交金額 ＝ `currency==USD ? amount × exchangeRate : amount`）於 DTO 層即時計算，不入庫。
> - 交易紀錄**不計算損益**（損益為已實現損益頁職責），流水帳只記事實。
> - `channel` 記錄成交當下名稱字串（刻意 denormalize，比照 `realized_gain.broker`），券商主檔日後改名／停用不影響歷史交易顯示。

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
| ownerUserId | Long | 所屬使用者；多租戶隔離欄，nullable=false |
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

> **複合條件成員（Task 253 新增 `group_id`）**：`stock_alert` 新增 nullable `group_id`（FK → `stock_alert_group.id`，`ON DELETE CASCADE`）。`group_id IS NULL` ＝ 既有的獨立單一條件，評估、cooldown、寄信行為完全不變；`group_id` 非空 ＝ 該列是某個 AND 群組的成員，**不得再自行觸發**，其 `active` 一律為 true、`last_triggered_*` 五欄一律不寫（觸發狀態記在群組上）。因此 `StockAlertService.checkAlerts` / `checkAlertsFor` 的「取 active 警示」查詢必須由 `findByActiveTrue()` 改為 `findByActiveTrueAndGroupIdIsNull()` —— 漏改的話成員會各自獨立觸發，AND 靜默退化成 OR。

#### StockAlertGroup（複合條件群組，Task 253 新增）

一個群組綁 2～5 條 `stock_alert` 條件，**群組內所有條件在同一次評估中同時成立才觸發一次**。只支援單層 AND：不支援 OR，也不支援巢狀運算式；要 OR 就照現況拆成多筆獨立條件。因為只有一種運算子，**刻意不設 `logic_op` 欄位**（存一個恆定值違反「不存可計算得出的衍生值」原則）；日後真要支援 OR 再加 nullable 欄位即可。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| ownerUserId | Long | 所屬使用者；多租戶隔離欄，nullable=false，掛 `@Filter(ownerFilter)` |
| stockCode | String | 股票代號（群組內所有條件必為同一檔） |
| market | String | 市場代碼 |
| active | Boolean | 是否啟用；**群組的啟停唯一開關**（成員的 `active` 恆為 true，不另具意義） |
| displayOrder | Integer | 警示頁拖曳排序，與獨立條件共用同一個排序空間 |
| lastTriggeredAt | LocalDateTime | 最近一次觸發時間（24h cooldown 以此為準） |
| lastTriggeredPrice | BigDecimal | 觸發時股價 |
| lastTriggeredMaValue | BigDecimal | 觸發時均線值（取群組內第一個 MA 條件對應的 `maPeriod`；無 MA 條件則 null） |
| lastTriggeredKdValue | BigDecimal | 觸發時 K 值 |
| lastTriggeredDValue | BigDecimal | 觸發時 D 值 |
| createdAt / updatedAt | LocalDateTime | 建立 / 更新時間（`@PreUpdate` 維護） |

> **「同時成立」的定義**：同一次 `evaluateGroup` 呼叫中，用**同一份現價**（`PriceQueryService.getLive`）判定所有成員條件，且每個成員沿用與獨立條件**完全相同的指標計算路徑** —— 一律走 `StockAlertService.matches(alert, currentPrice)`，該方法就是原本 `evaluate` 內那段 switch 原封不動抽出，獨立條件與群組成員共用，確保兩條路徑口徑零分歧。不接受「兩條件在某時間窗內先後成立」的寬鬆語意。
> **刻意不寫成「共用同一份預先算好的 `FullIndicators`」**：`matches` 內的 `checkMaDeviation` / `checkKdValue` 各自查 `historyRepo.findRecentN` 現算，其 MA 口徑（`withTodayIfMissing` 後取簡單平均）與 `TechnicalIndicatorService.computeAll()` 未必逐位一致。把成員改成吃一份預算好的指標，等於偷偷改動既有單一條件的觸發門檻——**這是明文禁止的重構方向**，別被「同一份指標」的直覺說法誘導。
> **成員 entity 必須 detach**：`checkMaDeviation` / `checkKdValue` 命中時會把 MA / K / D **回寫進傳入的 `StockAlert` 物件**（既有副作用，`evaluate` 靠它凍結指標值）。群組成員由 repository 取出時是 managed 狀態，而 `POST /api/stock-alerts/check` 走 HTTP 路徑、OSIV 預設開啟，隨後的 `groupRepo.save(group)` 會 flush 整個 persistence context，把髒掉的成員一併寫進 DB，違反「成員 `last_triggered_*` 一律不寫」。取出成員後即 detach。
>
> **不做盤中補抓**：獨立條件在 `lastTriggeredAt IS NULL` 時會走 `findRecentIntradayTrigger` 抓 Yahoo 5 分 K 回溯最近 3 個交易日精確定位觸發時點；**群組不走此路徑**，只在每 2 分鐘的 live 評估判定。要支援得對每根 bar 重算 price/MA20/60/240/K/D 再套 AND，成本遠高於效益，漏掉的僅是「盤中短暫同時成立又立刻脫離」的尖峰。
>
> **空群組防呆**：群組成員數 < 2 時不觸發（`allMatch` 對空集合恆真，直接放行等於無條件觸發）。

#### StockAlertGroupRecipient（複合條件群組 ↔ 收件人 join，Task 253 新增）

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| groupId | Long | FK → `stock_alert_group.id`（`ON DELETE CASCADE`） |
| recipientId | Long | FK → `notification_recipient.id`（`ON DELETE CASCADE`） |

`(group_id, recipient_id)` 組合 unique。**不以「把同一份收件人寫進群組內每個成員的 `stock_alert_recipient`」代替** —— 那會讓同一事實在 N 個成員上各存一份，違反正規化原則，且成員間清單可能不同步而語意矛盾。`AlertNotificationDispatcher` 因此需要第二支投影查詢 `findActiveTargetsByGroupId`（與既有 `findActiveTargetsByAlertId` 同樣保留 `r.ownerUserId = 群組.ownerUserId` 與 `r.active = true` 兩個條件，理由見 Task 145 的背景排程無 `ownerFilter` 說明）。

#### StockAlertTrigger（觸發歷史，新增）

每次警示條件成立時，除了覆寫 `StockAlert.last_triggered_*` 欄位外，另寫一筆 `stock_alert_trigger` 紀錄，保留近 30 天供事後追蹤。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| alertId | Long | FK → `stock_alert.id`（CASCADE on delete）。**Task 253 起改為 nullable**：群組觸發時為 NULL |
| groupId | Long | FK → `stock_alert_group.id`（CASCADE on delete），**Task 253 新增，nullable**：獨立條件觸發時為 NULL |
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
> **`alert_id` / `group_id` 恰好一個非空（Task 253）**：DB 以 CHECK 約束 `(alert_id IS NOT NULL) <> (group_id IS NOT NULL)` 保證。群組觸發**只寫一筆**（不是每個成員各一筆）——寫 N 筆會讓補發路徑把同一次 AND 觸發還原成 N 條獨立條件、文案與 live 寄出的合併 label 分歧。補發時 `group_id` 非空的列以群組合併 label（各成員 label 以 `" 且 "`（前後各一個半形空白）串接）還原條件文案，與 live 路徑共用同一支 `buildGroupLabel`。

> **刻意沒有 `owner_user_id`（Requirement 54）**：owner 一律由 `alert_id` → `stock_alert.owner_user_id` 或 `group_id` → `stock_alert_group.owner_user_id` join 取得。加冗餘 owner 欄違反正規化，且會與來源分家（alert 換 owner 後舊 trigger 列不會跟著改）。**本表未掛 `@Filter(ownerFilter)`**，任何跨使用者的讀取（如觸發匯出）都必須自行以 join 縮 owner，直接 `findAll()` 是跨租戶外流。

> **兩個時間欄的時區語意不同，取用前務必分清（Requirement 53／54）**：`triggeredAt` 是**市場牆鐘**（美股存紐約時間、英股存倫敦時間），`createdAt` 是**台北牆鐘**。凡是「哪一天發生的」這類以台北為基準的判定（例如觸發匯出的當日檔案歸屬），一律用 `createdAt`；用 `triggeredAt` 會讓台北凌晨觸發的美股警示被歸到前一天。

#### StockAlertExportSetting（觸發匯出設定，Requirement 54 新增）

警示觸發時即時把當日觸發寫成 JSON 到指定目錄的 per-user 設定。**與其他九個匯出頁的設定表結構相近但刻意少兩欄**——本頁是事件驅動而非排程，沒有執行時刻可設。

| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| ownerUserId | Long | 擁有者（唯一，`@Filter(ownerFilter)`；Requirement 28） |
| enabled | Boolean | 是否啟用觸發匯出（NOT NULL DEFAULT false） |
| outputSubpath | String | 本機相對子路徑（實際落點 = `EXPORT_OUTPUT_DIR` resolve 之） |
| lastRunAt | LocalDateTime | 最後一次匯出時間（台北牆鐘） |
| lastRunStatus | String | 最後一次匯出結果（**「最後一次」而非「今日」語意**：一天可能寫入多次） |
| gdriveEnabled | Boolean | 是否同步 Drive（NOT NULL DEFAULT false；限主要管理者啟用） |
| gdriveSubpath | String(512) | Drive 相對子路徑 |
| gdriveLastRunAt | LocalDateTime | 最後一次 Drive 上傳時間 |
| gdriveLastStatus | String(512) | 最後一次 Drive 上傳結果（與 `lastRunStatus` **分離**：本機成功而 Drive 失敗是正常且必須可分辨的狀態） |
| createdAt / updatedAt | LocalDateTime | 建立／更新時間（台北牆鐘） |

> **無 `run_hour` / `run_minute`**：匯出掛在 `StockAlertService.recordTrigger` / `recordGroupTrigger` 之後，由觸發事件驅動。套排程頁樣板會多出兩個永遠沒人讀的欄位。
> **固定單檔 ＋ 3 天滾動視窗，不是每日一檔**（Task 256 修正）：`alert_triggers_{ownerUserId}.json`，內容為最近 3 個台北日曆日（今日 ＋ 前 2 日）的觸發。**每日一檔會把同一個美股交易日切成兩個檔案**——美股 09:30–16:00 ET ＝ 台北 21:30 → 隔日 04:00，橫跨午夜；實測紐約 07-28 的四筆觸發被切成台北 07-28 三筆（VT/QQQ/VOO）＋ 07-29 一筆（AMZN），使用者打開當日檔只看得到一筆。台股、英股換算台北都不跨午夜，只有美股每天中。視窗以**台北日曆日**界定而非滾動 72 小時：後者會讓同一筆觸發隨匯出時刻在檔案裡忽隱忽現。
> **本機即時、Drive 合併去抖**：本機寫檔在觸發執行緒同步完成（毫秒級）；Drive 上傳丟單執行緒 `ScheduledExecutorService`，以「已排定任務旗標 ＋ 最近一次實際上傳完成時間」**延後至間隔到期**合併（最小間隔 60 秒）。理由：`PriceStreamService.onPriceUpdate` 對 `checkAlertsFor` 是同步呼叫、其上游為 Redis 訂閱者執行緒（同時負責 SSE 廣播與交易雷達評估），而 rclone 上傳逾時上限 45 秒——同步上傳一次卡住就是整條即時價管線停擺。檔案為視窗全量重寫，晚一點上傳的那份必然含先前所有內容，合併不遺失資料。
> **被合併跳過時兩個 Drive 狀態欄一律不碰**（`gdrive_last_run_at`／`gdrive_last_status` 值保持不變）：那兩欄的語意是「上次**上傳**的結果」，寫任何東西進去都會覆蓋掉前一次真正成功的落點與 bytes——與 Requirement 52 禁止把自檢警告寫進該欄同一理由。使用者要知道「本機即時、Drive 最多延遲約一分鐘」走 UI 常駐文案，不入庫。
> **去抖以「延後至間隔到期的單一任務」表達**（`ScheduledExecutorService.schedule`），**不可**寫成「距上次上傳未滿間隔就只標記 pending、由已排入的任務完成後補跑」——後者在「最後一次觸發發生於上一次上傳完成之後、且當天不再有觸發」時沒有任何執行中的任務會回頭看 pending，Drive 那份會永久缺最後一筆。

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
| ownerUserId | Long | 所屬使用者；多租戶隔離欄，nullable=false |
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
| ownerUserId | Long | 所屬使用者；多租戶隔離欄，nullable=false |
| email | String | 收件人 email；存入前 trim + 轉小寫；與 ownerUserId 複合唯一（同一使用者不可重複，**不同使用者可各自使用同一 email**） |
| active | Boolean | 是否啟用（false 不寄**股價警示**信，但保留設定） |
| receiveMarketAnalysis | Boolean | 是否接收每日股市分析（預設 true；與 `active`〔接收警示〕**各自獨立**，見 Requirement 31 / Task 151） |
| addToCalendar | Boolean | 警示 digest 是否夾帶 Google 日曆邀請（預設 false；**僅 `gmail.com` / `googlemail.com` 網域可設為 true**，見 Requirement 23 / Task 248） |
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
| id | Integer | PK，固定為 1（單列資料表；同 `PortfolioAdviceSetting`／`MarketAnalysisSetting`，三張單列設定表型別一致） |
| manualRetention | Integer | 手動備份保留份數 |
| dailyRetention | Integer | 每日備份保留份數 |
| weeklyRetention | Integer | 每週備份保留份數 |
| backupEnabled | Boolean | 排程備份總開關 |
| updatedAt | LocalDateTime | 最近一次更新時間 |

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
GET    /api/market-data/holidays?year=2026                     # 台股 / 美股 / 英股假日清單
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
GET    /api/market-data/dividends-readonly?code=0050&market=台股&years=10 # 純讀最近 N 年股利（績效比較頁 Requirement 33）：只讀 stock_dividend_history、回「裸陣列」，DB 空即回 []，**絕不觸發 cold-cache 抓取寫入**（與 /dividends 的差別）
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
> KPI 五卡（資產總計 / 存款總計 / **股票現值** / **債券現值** / 預估年配息）：第 3、4 張與「現金/債券/股票」圓餅圖 tab 同邏輯，顯示資產類別歸類值——股票現值 = `stockValue`（股票型：一般股票/ETF ＋ 股票型基金，不含債券 ETF）、債券現值 = `bondValue`（債券型：債券 ETF ＋ 債券型基金），與圓餅圖 **同一 business-service 來源**（`kpiCards` 依 `liveLatest.id` 於 `store.history` 找對應 row 取 `stockValue`／`bondValue`），採快照凍結逐筆 `currentValue`、不套盤中 live（同 Requirement 25，與圓餅圖同值，兩卡佔比 == 圓餅圖股票／債券）；`sub` 皆顯示「佔比 X.X%」。**回復嚴格加總**：存款 + 股票現值 + 債券現值 == `totalAssets`（債券 ETF 只計入債券現值）。原 `liveLatest` 的 live 股票加總仍保留、併入「資產總計」live 口徑。
>
> **「全市場皆非交易日今日」（昨日快照／今日尚未建檔／週末／刪除今日快照後最新退回昨日）時，`liveLatest` 一律維持快照凍結收盤值（= 基準日收盤），不得用 `liveAssets` 覆蓋**。理由：`live-assets` 讀 Redis = 最新一筆 tick / 收盤，當最新快照日 < 今日且已有更新交易日收盤時，覆蓋會把較新交易日收盤洩漏到一個過去基準日上（Task 121 修正前 bug：最新快照 6/23、刪掉 6/24 後 6/23 列被 6/24 收盤蓋掉）。快照凍結的 `stock_holding.currentValue` 對已定案的過去日期本來就 == 該日收盤，保留即正解。`DashboardBffController.summary` 回的 `history` 最新列亦透過共用 `LiveAssetsOverlay.applyToLatest`（同一 per-market 閘門）套 overlay，與 `/api/bff/asset-history` 最新列同值。（前一版設計曾要求此分支改走 `overlayLatestFromLiveAssets(s)` 一律覆蓋，導致過去基準日被較新收盤汙染 → Task 121 反轉為 per-market 閘門，並移除 `overlayLatestFromLiveAssets`）
>
> 儀表板基準日切換行為：
> - 2 分鐘輪詢 `refreshPricesAndStatus()` 只刷新 `stockPrices` / `marketStatus` / `liveAssets`（呼叫 `bffApi.dashboard.realtime()`），**不得重抓 dashboard summary** 以免覆蓋使用者選擇的快照。
> - 初次載入才呼叫 `bffApi.dashboard.summary()` 並把 `selectedSnapshotId` 設為最新；之後使用者透過快照選擇器切換時，僅 `bffApi.dashboard.snapshot(id)` 取得明細。
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
GET    /api/watch-stocks/chart.png             # 警示 email 內嵌的股票分析「近一年」走勢圖 PNG（XChart server-side 渲染）
GET    /api/watch-stocks/intraday.png          # 警示 email 內嵌的「當日分時」走勢圖 PNG（分時價格線 + 昨收基準線 + 漲跌色、X 軸開盤→收盤；Task 159）
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
GET    /api/stock-alerts/export-setting          # 觸發即時匯出設定（owner-scoped；Requirement 54）
PUT    /api/stock-alerts/export-setting          # 更新設定（Drive 開關限主要管理者，否則 403）
POST   /api/stock-alerts/export-setting/run-now  # 立即以視窗內已發生的觸發產檔（驗證落點用；視窗內無觸發亦寫出 triggers: [] 的合法 JSON）
```

> **觸發即時匯出（Requirement 54）：** 匯出掛在 `StockAlertService.recordTrigger`（獨立條件）與 `recordGroupTrigger`（複合群組）**寫入 `stock_alert_trigger` 之後**——先寫檔會漏掉當次那一筆。檔名 `alert_triggers_{ownerUserId}.json`（**固定、不含日期**），內容為該 owner **最近 3 個台北日曆日**（以 `created_at` 台北牆鐘界定，非 `triggered_at`）全部觸發的全量重寫，本機以 tmp ＋ `ATOMIC_MOVE` 落檔（下游可能正在讀）。條件文案一律取自 `StockAlertService.buildLabel` / `buildGroupLabel`，不在匯出端另行串接（同義欄位同一來源）。匯出整段包 try/catch，失敗不回滾 `stock_alert_trigger`、不中斷 email enqueue、不拋出中止整輪檢查。**目錄瀏覽不在此服務**：沿用既有唯一那支 `GET /api/export-schedule/browse{,-gdrive}`。



> **每條警示挑選收件人（Task 125）：** `StockAlertDto.Request` / `Response` 新增 `recipientIds`（`List<Long>`）。`create` / `update` 以 `recipientIds` 覆寫 `stock_alert_recipient`（先 `deleteByAlertId` 再批次 insert）；`Response` 回填該警示目前的 `recipientIds`。對話框「通知對象」多選的可選清單走同頁 BFF `GET /api/bff/stock-alert/recipients`（passthrough → `/api/stock-alerts/recipients`，後端委派 `NotificationRecipientService.findAll()`，與通知設定頁同一份資料源）。新增警示預設全選；選 0 位代表觸發不寄信。

#### Stock Alert Groups（複合 AND 條件，Task 253 新增）

```
POST   /api/stock-alerts/groups                # 建立群組（body 含 stockCode/market/conditions[]/recipientIds/active）
PUT    /api/stock-alerts/groups/{id}           # 更新群組（conditions 整組覆寫）
DELETE /api/stock-alerts/groups/{id}           # 刪除群組（成員 alert 與 join 列由 FK CASCADE 連帶清除）
PATCH  /api/stock-alerts/groups/{id}/active    # 啟用/停用群組

# 前端 view 經既有 BFF：/api/bff/stock-alert/groups/** → /api/stock-alerts/groups/**
# StockAlertBffRoutes 已是 /api/bff/stock-alert/** 萬用 passthrough，新端點不需改 BFF
```

> **混合清單（Task 253）：** `GET /api/stock-alerts` 改回「獨立條件 + 群組」的混合列表。`StockAlertDto.Response` 新增兩欄：`kind`（`"SINGLE"` / `"GROUP"`）與 `conditions`（`List<ConditionItem>`，僅 `GROUP` 有值；`SINGLE` 為 null）。**群組列的 `id` 是 `stock_alert_group.id`，與獨立條件的 `stock_alert.id` 分屬不同表、值會相撞**，故前端 `row-key` 必須用 `` `${kind}-${id}` ``、所有列操作（編輯 / 刪除 / 啟停）依 `kind` 分派到不同端點。`conditionLabel` 對群組為合併 label（各成員 label 以 `" 且 "`（前後各一個半形空白）串接），供表格單欄顯示與觀察頁共用。
>
> `PUT /api/stock-alerts/reorder` 的 body 由 `List<Long>` 改為 `List<{kind, id}>`（獨立條件與群組共用同一個 `display_order` 排序空間，不能只送 id）。
>
> 群組的 `conditions` 條目結構為 `{alertType, maPeriod, threshold}`（與 `StockAlertDto.Request` 的單一條件三欄同名同義），`create` / `update` 以整組覆寫成員 `stock_alert` 列，成員的 `active` 一律寫 true、`display_order` 依陣列順序遞增（決定 label 串接順序）。

#### Notification Recipients（Requirement 23 新增）
```
GET    /api/notification-recipients                  # 列出所有收件人（含啟停狀態）
POST   /api/notification-recipients                  # 新增收件人
PUT    /api/notification-recipients/{id}             # 更新 email
DELETE /api/notification-recipients/{id}             # 刪除
PATCH  /api/notification-recipients/{id}/active      # 切換啟用/停用（＝是否接收股價警示信）
PATCH  /api/notification-recipients/{id}/market-analysis # 切換「是否接收今日股市分析每日 Email」訂閱（Requirement 31 / Task 151；與 active 各自獨立）
PATCH  /api/notification-recipients/{id}/calendar     # 切換「警示 digest 夾帶 Google 日曆邀請」（Task 248）；非 Gmail 網域「開啟」時回 400，關閉一律放行

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
POST   /api/twse-daily-index/refresh-tr?years=10 # 背景回補台股「含息報酬指數」close_point_tr（Task 170），立即回 {started, pending}
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
- `foreign_stock_daily_history`   ((stock_code, trading_date) PK, close_point NUMERIC(18,4)) — **Task 185**（backend Liquibase `v1.55.0-foreign-stock-daily.sql`；ext-materials 以 JdbcTemplate 直寫，無 JPA entity）
  - 海外參考個股每日收盤：韓股三星電子 005930 / SK 海力士 000660，供公開資訊爬蟲組韓股快照（`kr-market`）算漲跌%（只需最新＋前一交易日收盤，故僅存 `close_point`；KRW 幣別可達百萬級，精度放寬 NUMERIC(18,4)）
  - 來源 Yahoo Finance v8 chart API（`005930.KS`/`000660.KS`，時區 Asia/Seoul），ext-materials `PriceFetchClient.fetchKrHistoricalRange` 以 curl 子程序（短 UA `Mozilla/5.0`）抓、`KrStockPoller`（每日 16:00 Asia/Taipei ＋開機 warmup）upsert
  - **與 `us_index_daily_history` 分表**（那張語意為「指數」）、**與 `stock_price_history` 分表**（那張為投組個股、market 分類驅動、涉持股/觀察）：本表僅存固定的海外參考個股收盤，語意獨立、不污染既有兩條線

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
# 不變式：growthValue + incomeValue == stockValue（基金無 dividendRate 欄故不依殖利率細分，預設歸成長型，但可由 fund_class_override.stock_style 指定為收益型而歸入 incomeValue）

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

**規則**（`external-materials-service` 的 `TradingDateResolver.resolve`，供 `PriceCacheWriter` 決定寫 tick LIST 的 bucket）：

> **讀取側刻意不共用這一支。** `InternalPriceController.intradayTicks` 另有更完整的策略：先試「今天」
> （含 `todayTicksWithSelfHeal` 的 tick 不完整自癒，Task 236），為空才退回最近交易日，且**非交易日不做
> cold-start**（颱風假一體休市，refresh 只會抓到昨收平盤幻影，Task 161）。該策略已涵蓋「盤中讀到上一交易日
> key 而 tick LIST 為空」這個問題，且多處理了「盤後仍要看得到今日 tick」的情形，故不改為共用。

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
StockAlertService.evaluate()          # 獨立條件（group_id IS NULL）
  ├─ 24h cooldown 通過 + matches(alert, price) 命中
  ├─ alertRepo.save(凍結 K/D/MA)
  ├─ recordTrigger() → 寫 StockAlertTrigger（必落地，無論寄信成敗）
  └─ alertNotificationDispatcher.enqueue(alert, triggeredAt, price,
                                         ind.monthlyMa, ind.quarterlyMa, ind.annualMa, ind.k, ind.d)

StockAlertService.evaluateGroup()     # 複合 AND 群組（Task 253）
  ├─ 群組 24h cooldown 通過（看 group.lastTriggeredAt，成員不各自 cooldown）
  ├─ 成員數 ≥ 2 且「每一個成員」matches(member, 同一份 price) 皆為 true   ← AND
  ├─ groupRepo.save(凍結 K/D/MA 到群組)
  ├─ recordGroupTrigger() → 寫「一筆」StockAlertTrigger（alert_id=NULL, group_id=群組 id）
  └─ enqueueGroup(group, 合併 label, …)  → 與獨立條件共用同一條 queue / flush 路徑
  ※ 不走 findRecentIntradayTrigger（複合條件不做 5 分 K 盤中補抓）
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
                                  └─ 對每位收件人各組一封 digest（每檔年圖＋當日分時圖 2 張 PNG，以 stock 為 key 跨收件人快取）
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
- **複合 AND 群組的收件人（Task 253）**：`PendingTrigger` 增帶 nullable `groupId`；`groupByRecipient` 依 `groupId` 是否非空，分別走 `findActiveTargetsByGroupId(groupId)`（查 `stock_alert_group_recipient`）或既有的 `findActiveTargetsByAlertId(alertId)`，兩支查詢皆保留 `r.ownerUserId = 擁有者.ownerUserId` 與 `r.active = true` 條件（Task 145 的背景排程無 `ownerFilter` 縱深防護，不得從新入口重新打開）。快取 key 因此需能區分兩者（例：以 `"A"+alertId` / `"G"+groupId` 字串為 key），**沿用純 `Long` 當 key 會讓 alert 5 與 group 5 互相污染收件人清單**。digest 內文與 `calendarLines` 走既有 `groupByStock` 合併——同一檔的獨立條件觸發與群組觸發本就該併在同一區塊
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
- `AlertNotificationDispatcher.buildDigest()` 回 `DigestMail{html, inlineImages(cid→png), stockCount}`：每檔股票區塊組好文字後，**依序取兩張圖各以獨立 CID 內嵌**——(1) 年圖 `renderPriceMaPng(code, market)` → `images.put("chart{i}", png)` + `<img src="cid:chart{i}">`；(2) 當日分時圖 `renderIntradayPng(code, market)` → `images.put("intraday{i}", png)` + `<img src="cid:intraday{i}">`。兩者各自 `Optional`：任一 empty 只略過該圖、另一圖與文字照寄。`chartCache` 以 `"price "` / `"intraday "` 前綴命名空間 + `stockCode+market` 為 key 跨收件人共用，同檔兩圖各只 render 一次（單檔觸發之 digest 內嵌 2 張圖）
- `AlertChartRenderer`：純 Java（XChart）server-side 繪圖，**上下雙 pane 合成一張 PNG**（Graphics2D 垂直拼接）：
  - 資料一律走 `HistoricalDataService.getStockHistory(code, market, end-120M, end)`（與畫面 `StockAnalysisDialog` **同 120 個月範圍**、0000 自動讀 `twse_index_daily_history` 含 OHLC、併今日即時價）
  - MA：與前端 `calcMA` 相同——**每點對 window 重新加總**（非滑動扣減，避免長序列累積誤差）+ `BigDecimal HALF_UP` 2 位
  - KD：與前端 `calcKD` / `TechnicalIndicatorService` 同一遞迴（period 9、RSV=(close-ll)/(hh-ll)*100、hh==ll→50、K=prevK*2/3+RSV/3、D=prevD*2/3+K/3、seed 50/50、high/low 缺值 fallback close、續算用未捨入值）。整段歷史算完再切尾 252（≈1年），尾值與畫面逐位一致
  - 上 pane：股價(藍) + 月線MA20(橘) + 季線MA60(紫) + 年線MA240(紅)，隱藏 x 軸（日期只畫在下 pane）；下 pane：K(橘) / D(綠)，Y 0~100，80/20 灰虛線（`setShowInLegend(false)` 不進圖例）
  - **股價線標最高 / 最低點**（比照畫面 `StockAnalysisDialog` 的 markPoint）：`addHiLoMarkers` 找顯示窗（≈252 日）內股價最高 / 最低收盤，各以 `HiLoMarker`（自訂 `Annotation` 子類）畫出——圓點落在線上 + 圓角色塊兩行（第一行「最高/最低 + 價位 `%,.2f`」、第二行日期 `yyyy/MM/dd`），紅最高(#dc2626) / 綠最低(#16a34a)（紅漲綠跌）。色塊位置：最高放點下方、最低放點上方，水平夾在 plot 內避免出界。最高 / 最低同點（區間平盤）只畫最高。座標用 `Annotation.getXAxisScreenValue/getYAxisScreenValue`（paint 時軸範圍已算妥）→ 點精準落線上；不用 `AnnotationText`（其字色由 styler 全域共用、無法逐點分紅綠）
  - legend 文字 = 中文名稱 + 空白 + 最新值（`%,.2f`），白底，與畫面同
  - 失敗回 empty → 略過該圖、文字照寄。每封信內嵌圖數設上限（超過則該檔僅文字），`sendHtml` log 內嵌總位元組
- `AlertChartRenderer.renderIntradayPng(code, market)`（Task 159）：警示 email 第二張圖「當日分時走勢」，**單一 pane**（無 KD 副圖），與畫面 `StockAnalysisDialog`「當日」同資料源、同口徑：
  - 分時 tick 走 `HistoricalDataService.fetchIntradayTicks(code, market, null)`（date 省略 → ext-materials 取最近有資料交易日、讀 Redis tick LIST，與畫面「當日」同一支 business API）；分時交易日 = 首筆 tick `time` 前 10 碼
  - **昨收基準線**：`previousDailyClose` 讀 `getStockHistory(code, market, 分時日-2M, 分時日)`（升冪），取 `tradingDate` **嚴格早於**分時交易日的最後一筆收盤（併入的今日即時價其 `tradingDate==分時日`，因嚴格早於自動排除）；與畫面「當日漲跌」同「vs 前一交易日原始收盤」口徑，**不採 Redis previousClose**。灰虛線 + legend「昨收 X.XX」；查無則不畫、線用中性藍
  - **漲跌色**：最新分時價 ≥ 昨收 → 紅(#dc2626 漲) / 否則綠(#16a34a 跌)（紅漲綠跌，對齊 `quoteColor`）；legend 股價值附「▲/▼ 金額（±%）」
  - **X 軸開盤→收盤**：以 `MarketZones.openTime/closeTime`（單一事實來源）之當日分鐘數為**數值 X**、`setXAxisMin/Max` 鎖定開收盤，`setCustomXAxisTickLabelsFormatter` 轉 `HH:mm`；故軸固定延伸到收盤而非最後一筆 tick。稀疏 tick 以 `TreeMap<分鐘,價>`「每分鐘最後成交」落點連續線（XChart 無 `connectNulls`，故只畫真實 tick 點、不鋪 null 網格）。Y 軸涵蓋分時區間 + 昨收（各 +10% padding），基準線不落框外
  - 失敗（無 tick / 繪圖例外）回 empty → 僅略過此圖。CJK 字型與年圖共用同一 `CJK_FONT`。另有預覽端點 `GET /api/watch-stocks/intraday.png`（比照 `chart.png`，無 tick 回 204）
- `AlertNotificationDispatcher.buildDigest`：數值入圖後文字精簡——保留「標題 + 觸發時間 + **觸發股價**」，移除月/季/年線/KD 文字列（圖內已有）。`<img width:900px>`
- **字型依賴**：runtime image 為 `eclipse-temurin:21-jre-alpine`（slim、無 CJK 字型）→ backend `Dockerfile` apk 裝 `fontconfig ttf-dejavu font-noto-cjk freetype` 並 `fc-cache -f`，ENTRYPOINT 加 `-Djava.awt.headless=true`。`AlertChartRenderer.loadCjkFont` 用 **`Font.createFonts`（複數）挑 TC face**（`.ttc` 的 face 0 為 JP 變體，`createFont` 單數會選到日系字形）；找不到則 fallback SANS_SERIF
- 預覽 / 驗證端點：`GET /api/watch-stocks/chart.png?code=&market=`（BFF passthrough `/api/bff/watch-stock/chart.png`）回同一張 PNG，資料不足回 204

**設定面**：
- `application.yml` 內 `spring.mail.host=smtp.gmail.com:587 STARTTLS`，username / password 走 `${MAIL_USERNAME}` / `${MAIL_PASSWORD}` 環境變數（Gmail App Password，非登入密碼）
- 寄件人預設 = `MAIL_USERNAME`；可以 `NOTIFICATION_FROM` 覆寫
- 收件人於 `/notification-settings` 頁面維護，存 `notification_recipient` 表

### Gmail 收件人日曆邀請（Requirement 23 / Task 248）

**目標**：警示觸發的即時性不再受「使用者何時打開信箱」限制——digest email 夾帶 iCalendar 邀請，Google 日曆自動建立事件並推播到手機。

**為什麼是 ics 邀請、不是 Google Calendar API**：收件人是**別人的信箱**（家人 / 副信箱），本系統拿不到他們的 OAuth 授權，Calendar API 無從代其建立事件；服務帳戶亦無自身日曆、個人 Gmail 無法做 domain-wide delegation。夾帶 `METHOD:REQUEST` 的 ics 是唯一「不需收件人授權、又能自動落進日曆」的路徑，且完全走既有 SMTP，不新增任何憑證或對外相依。既有 rclone 的 Google OAuth token（`[GoogleDriver]` 為 `drive.file`、`[GDriveOutput]` 為 `drive`）都只授予雲端硬碟權限，與 calendar scope 無關，**不可**挪用。

**資料模型**：`notification_recipient` 增 `add_to_calendar BOOLEAN NOT NULL DEFAULT FALSE`（Liquibase `v1.77.0-notification-recipient-calendar.sql`）。與 `active`（收警示）、`receive_market_analysis`（收股市分析）三者各自獨立；`add_to_calendar` 是「收警示信時**額外**夾帶日曆邀請」的修飾旗標，`active=false` 時本就不寄信，日曆自然也不會有事件。

**Gmail 網域限制**：只有 `gmail.com` / `googlemail.com` 能開啟（email 已在寫入前 trim + 轉小寫，直接取 `@` 之後比對）。後端 `PATCH .../calendar` 與 `update`（改 email）兩處都要把關：改成非 Gmail 時強制 `addToCalendar=false`，不留殘留狀態。**網域檢查只擋「開啟」方向**（`false` → `true`）；關閉一律放行，否則殘留列將無法從畫面關掉。前端非 Gmail 列顯示「—」。

**掛載點與分組單位變更**：`AlertNotificationDispatcher.flush()` 的 per-recipient 迴圈。既有 `groupByRecipient` 以 **email 字串**為 key（來源 `StockAlertRecipientRepository.findActiveEmailsByAlertId`，只投影 `r.email`），要夾帶 ics 就得知道該列的 `id`（寫進 UID）與 `addToCalendar`——**不可用 `findByEmail(email)` 反查**：`notification_recipient` 的唯一鍵是複合 `(owner_user_id, email)`，不同使用者可各自使用同一 email，而背景排程無 HTTP request context、`TenantFilterAspect` 明文放行不套 `ownerFilter`（見 `TenantFilterAspect`「背景執行緒：不啟用，維持掃全體」），故 `Optional<NotificationRecipient> findByEmail` 在同 email 多列時會丟 `IncorrectResultSizeDataAccessException`，單列時也可能取到**別的租戶**那一列。這正是 Task 145 已為同一支 dispatcher 修過的洞（`findActiveEmailsByAlertId` 額外 join `StockAlert a` 並加 `r.ownerUserId = a.ownerUserId`），不可從另一個入口重新打開。

作法：把該查詢改為投影出收件人身分（`r.id`、`r.email`、`r.addToCalendar`，保留既有的 `r.ownerUserId = a.ownerUserId` 與 `r.active = true` 條件），`groupByRecipient` 的 key 改為 **`recipientId`**，email 僅作寄送位址。**這帶來一個刻意的行為變更**：同一 email 分屬不同租戶時，原本會被合併成一封信寄出（等於跨租戶內容混寄），改 key 後各租戶各寄一封。**手動補發 `resendLastTradingDay` 不夾帶 ics**（使用者主動重寄歷史觸發，再進日曆只會製造重複事件），但同樣改用 id 分組。

**ics 產生**（`AlertCalendarInviteBuilder`，純字串組裝、無第三方 iCal 函式庫）：

```
BEGIN:VCALENDAR / VERSION:2.0 / PRODID:-//asset-management//alert//ZH-TW / CALSCALE:GREGORIAN / METHOD:REQUEST
BEGIN:VEVENT
UID:alert-{recipientId}-{epochMillis}@asset-management      ← 每封新 UID；重複 UID 會被 Google 當成既有事件的更新而覆蓋、且不再推播
DTSTAMP / DTSTART:{now+2min，秒歸零 UTC} / DTEND:{DTSTART+15min}
ORGANIZER;CN=資產管理系統:mailto:{from}
ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=FALSE;CN={email}:mailto:{email}
SUMMARY:{= 信件主旨} / DESCRIPTION:{每檔一行摘要，RFC 5545 escape} / STATUS:CONFIRMED / SEQUENCE:0 / TRANSP:TRANSPARENT
BEGIN:VALARM / ACTION:DISPLAY / DESCRIPTION:股票警示觸發 / TRIGGER:-PT1M / END:VALARM
END:VEVENT / END:VCALENDAR
```

- **CRLF 換行 + 75 octet folding**（續行以單一空白起始，依 UTF-8 位元組數而非字元數斷行——`DESCRIPTION` 內是中文，按字元斷會超規）
- **`DTSTART` 必須在未來**：Google 對「已開始」的事件不推播；而事件於其提醒窗內被建立時（收件人日曆預設多為「10 分鐘前」、事件 2 分鐘後開始）Google 會在加入當下立即推播。+2 分鐘同時涵蓋「ics 的 `VALARM` 生效」與「被收件人日曆預設提醒覆蓋」兩種情形，實際延遲 ≈ 1~2 分鐘
- `TRANSP:TRANSPARENT` 讓事件不佔 free/busy，不干擾收件人的行程可用性

**`DESCRIPTION` 的來源**：日曆事件的逐檔摘要與信件正文**必須同源**，否則日後有人改了 `buildDigest` 的 label 串接或 latest 判準，兩者就會靜默分歧。故 `DigestMail` record 增第四個成分 `List<String> calendarLines`，於 `buildDigest` 既有的逐檔迴圈內順手 append（同一份 `groupByStock` 分組、同順序、同 `formatNumber`），dispatcher 直接取用，**不得在 dispatcher 內重跑一次分組或複製 label 去重邏輯**。

**MIME 結構**：`EmailService.sendHtml` 增一個可選 `icsContent` 參數（多載，既有四參數版委派新版傳 `null`，`MarketAnalysisEmailDispatcher` / `TradingRadarNotificationDispatcher` 的行為不變）。`MimeMessageHelper(msg, true, "UTF-8")` 為 `MULTIPART_MODE_MIXED_RELATED`：root 是 `multipart/mixed`、其內含 `multipart/related`（HTML + inline CID 圖）；ics 以 `MimeBodyPart` 掛到 `helper.getRootMimeMultipart()`（該方法為 `public final`），與 HTML 正文並存。內容以 `DataHandler` + `ByteArrayDataSource` 寫入**明確的 UTF-8 位元組**，**不可用 `setContent(String, type)`**——JavaMail 的 `META-INF/mailcap` 未註冊 `text/calendar`（只有 text/plain、text/html、text/xml、multipart/\*、message/rfc822），會落到 `ObjectDataContentHandler` 的 String 分支以 `Charset.defaultCharset()` 寫出、忽略宣告的 charset。`Content-Transfer-Encoding: 8bit` 顯式設定後 `MimeBodyPart.updateHeaders` 不會覆寫。組 ics / 夾帶失敗一律 `log.warn` 後**照常寄純 email**（比照既有「寄信失敗不阻斷警示判斷」的失敗策略）。

**ORGANIZER 的來源**：必須與實際 SMTP 寄件人一致（Google 靠這點才自動接受 `METHOD:REQUEST`），故 `EmailService.resolveFrom()`（現為 private）開放為 public 供 dispatcher 取用，**不得在別處重寫一份 `NOTIFICATION_FROM` → `MAIL_USERNAME` 的 fallback 判斷**——兩份判斷一旦不同步，ORGANIZER 與寄件人不符會讓功能靜默失效且無 log。

**收件人知情**：開關由帳號擁有者操作、收件人未被徵詢，且 `PARTSTAT=ACCEPTED;RSVP=FALSE` 讓 Gmail 卡片不出現可拒絕的 RSVP（此設定是為了避免 RSVP 回信灌爆寄件信箱）。故**夾帶 ics 的那封 digest，HTML 正文結尾多一行**「本信附有 Google 日曆邀請，如不需要請告知寄件人於『警示通知設定』關閉。」——只在有夾帶時出現。

**已知前提（寫在設定頁提示，不是程式能控制的）**：收件人的 Google 日曆「自動將邀請加入日曆」需維持預設「是」；若設為「僅在我回覆時」，需在信中手動接受一次事件才會進日曆。

## 系統時區基準（Requirement 53 / Task 252）

### 三條規則

1. **JVM 預設時區 = `Asia/Taipei`，由 compose 的 `TZ` 環境變數設定，是唯一開關。** 它的語意是「沒有顯式指定 ZoneId 的呼叫落在哪裡」。切換前為 UTC——那是全系統唯一沒有任何人想要的時區（使用者在台灣，三個市場時區則早已 100% 顯式化）。
2. **`timestamp without time zone` 欄位一律存台北牆鐘**，唯二例外：`stock_alert.last_triggered_at` 與 `stock_alert_trigger.triggered_at` 存**市場牆鐘**（`computeTriggeredAt()` 的 `ZonedDateTime.now(市場 zone)`），因為顯示端要的就是「紐約時間 12:00 觸發」。這兩欄的 entity 需有註解標明。
3. **涉及「交易日／今日」的判定一律走 `MarketZones.today(market)` / `nowLocal(market)`，禁用裸 `LocalDate.now()`。** 沒有市場語境的顯示用時間戳可用裸 `now()`（此時它就是台北）。

### 為什麼是容器 TZ 而不是別的做法

- **`spring.jackson.time-zone` 無效**：它只對本身帶時區的型別（`Date`／`Calendar`／`ZonedDateTime`）生效。entity 全是 `LocalDateTime`，按定義沒有時區資訊，Jackson 只能照字面輸出（實測 JSON 為 `"createdAt":"2026-06-05T18:53:29.153553"`，無 `Z` 無 offset），沒有 offset 可寫也就沒有東西可轉。
- **`TimeZone.setDefault()` 在 `@PostConstruct` 不可靠**：`@Scheduled` 的 cron 在 `ScheduledAnnotationBeanPostProcessor` 註冊時就把時區綁進 trigger，與哪個 `@PostConstruct` 先跑沒有保證。要改就在 JVM 啟動前決定。
- **PostgreSQL 不能只設 `TZ` 環境變數**：實測資料目錄的 `postgresql.conf` 已被 initdb 寫死 `timezone = UTC`（`pg_settings.source = configuration file`），環境變數贏不過它，要改須用 `command: ["postgres","-c","timezone=Asia/Taipei",...]`。**但它管不到應用連線**：pgjdbc 的 startup packet 一律以 JVM 預設時區覆寫該連線的 session TimeZone，故 server 端的值只影響手動 `psql` 查詢與 server 日誌。設它是為了讓這兩者與應用同基準，不是為了改變應用行為。

### 部署約束（本任務最關鍵的一條）

**三段——容器 TZ、程式碼修正、歷史校正——必須同一個 commit、同一次 build、同一次 `up -d`。** 拆開部署會產生半套狀態：只改 TZ 不改冷卻判定 → 美股警示 12 小時就重複寄信；只跑 migration 不改 TZ → 所有時間顯示變成**超前** 8 小時；只改 TZ 不改 ext 寫入端 → `gdrive_last_run_at` 的兩個寫入端分家。

### 49 個 naive 欄位的分類

**判準是「寫入端怎麼繫結」，不是 Java 型別。** 這一點決定了 migration 清單，寫錯就是不可逆的資料損毀。

| 分類 | 欄位數 | 寫入端 | 會隨 JVM 時區位移？ | 處置 |
|---|---|---|---|---|
| 裸 `LocalDateTime.now()` | 10 | 5 個 entity 的 `@PrePersist`/`@PreUpdate`（9 欄）＋ `MarketAnalysisService`（1 欄） | 會 | **`+ INTERVAL '8 hours'`** |
| SQL `NOW()` 灌進 naive 欄 | 1 | ext `StockSourceQuery` | 會（pgjdbc 以 JVM 時區當 session TimeZone） | **`+ INTERVAL '8 hours'`** |
| JdbcTemplate `Timestamp.from(instant)` | 3 | ext `CrawlerExportPathQuery` / `FundNavSourceQuery` | 會（`setTimestamp` 不帶 Calendar） | **改寫入端**綁 `LocalDateTime.ofInstant(now, UTC)`；**歷史資料不動** |
| Hibernate 的 `Instant` 欄位 | 3 | business JPA | **不會** | **絕對不可動** |
| 台北牆鐘 | 28 | service 層 `LocalDateTime.now(TW_ZONE)`（7 張匯出排程表 ×3、交易雷達匯出 ×4、備份 ×3） | 不會 | **不可動**——動了會弄壞排程的「今日是否已跑過」判定 |
| 市場牆鐘 | 2 | `computeTriggeredAt()` | 不會 | **不可動**（規則 2 的例外） |
| DB 內部 | 2 | Liquibase | — | 不管 |

合計 10+1+3+3+28+2+2 = 49。**需要歷史校正的只有前兩列共 11 欄。**

**為什麼 Hibernate 的 `Instant` 不位移**：Hibernate 6 對 `Instant` 走 `TimestampUtcAsJdbcTimestampJdbcType`，bind 是 `setTimestamp(i, ts, UTC_CALENDAR)`、extract 是 `getTimestamp(i, UTC_CALENDAR)`，兩側都釘在 UTC，與 JVM 預設時區無關。對這類欄位 `+8` 會把目前正確的顯示永久改壞——這是本任務最容易犯、且最難回復的錯。

**同一欄可能有兩個寫入端**：`crawler_export_setting.gdrive_last_run_at` 由 business 的 Hibernate（`Instant`，UTC 中立）與 ext 的 JdbcTemplate（`Timestamp.from`，隨 JVM 時區）各寫一次。切換前兩者碰巧都產生 UTC 牆鐘所以一致；切換後 ext 端會變台北而 Hibernate 端仍是 UTC → 分家 8 小時。修法是把 ext 端改為顯式 UTC 繫結，**不是**校正歷史值。

分類判準以**寫入端程式碼**為準，並以運行中 DB 的實值交叉驗證（把每個欄位的 `max()` 與 `now() AT TIME ZONE 'UTC'` 相減：台北牆鐘那組落在 +5～+8h，UTC 那組落在 0～−1h）。

### 校正後仍存在的殘留

`LocalDateTime` 欄位是時區中立的（牆鐘原樣往返），所以切換本身**不會改變任何舊列的數字**——不校正的話，畫面顯示也不會變（仍舊錯 8 小時），只有新列開始正確，形成「同一張表兩種基準、看不出哪個是哪個」的混合態。校正的意義正是消除這個混合態，而不是修復切換造成的破壞。

### 不受影響（切換前後行為完全相同）

46 個帶 `zone` 的 `@Scheduled`（Spring 的 `zone` 覆蓋 JVM 預設，已用運行中日誌實證：英股 `0 32 16` zone=Europe/London 於 `15:32:00Z` 觸發＝倫敦 16:32 BST）、3 個 `fixedDelay` 排程、`MarketZones`／`MarketClock`、`AlertNotificationDispatcher.withinSendWindow()`、`BackupService` 的 `DISPLAY_ZONE`、所有 `Instant.now()`（絕對時間軸）、10 個 `timestamptz` 欄位、以及**全部前端顯示程式碼**（後端改吐台北牆鐘後自動正確；前端不存在任何 +8 補償 hack，不得為此新增，否則雙重補償）。

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

### BFF 上游 DNS 解析策略（Task 208）

**問題**：`docker compose up -d --force-recreate business-services` 會讓該容器換 IP（實測 `172.19.0.4` → `172.19.0.7`），
BFF 卻持續對舊 IP 連線得 `Connection refused`，前端每個 `/api/**` 回 **500**，且不會在數十秒內自癒（實測 3.5 分鐘後仍在報錯）。

**根因（已由 bytecode ＋ 封包實測確認）**：reactor-netty 的 `HttpClient` 預設**不走 JDK `InetAddress`**，而是 netty 的非同步
DNS resolver（`DnsAddressResolverGroup`）。`NameResolverProvider` 的 `DEFAULT_CACHE_MAX_TIME_TO_LIVE` 為
`Integer.MAX_VALUE` 秒＝完全照抄 DNS 回應自帶 TTL；而 Docker 內建 DNS（`127.0.0.11`）對 container name 回的 A record
TTL 實測為 **600 秒**（原始封包 TTL 欄位 `0x00000258`），故舊 IP 最久被記住 10 分鐘。容器 OS 的 `getent hosts` 正常，
是因為那走 glibc/NSS，與 netty 自己的快取是兩套獨立機制——**不能以 `getent` 正常就排除 DNS 問題**。

**對策**：`bff/config/DnsCacheConfig` 統一把 netty resolver 的正向快取上限壓到 **30 秒**（`MAX_TTL`）。
- **JVM 旗標無效**：`-Dnetworkaddress.cache.ttl` 只作用於 JDK resolver，reactor-netty 根本不經過它；且它本質是
  **security property** 而非 system property，直接 `-D` 讀不到（雙重無效，且是靜默無效）。
- **negative TTL 刻意不設**：netty 預設即 0 秒＝不快取解析失敗；若設成 1 秒反而把容器重建瞬間的查無主機黏住，
  正好黏在要加速的時間窗。
- **30 秒而非秒級**：越短自癒越快，但也越頻繁依賴 embedded DNS 可用性（netty 查詢逾時 5 秒，DNS 抖動會變成使用者可見延遲）。
  相對修補前的 10 分鐘已是量級改善。
- **兩條上游路徑都要套**（各自持有獨立 `HttpClient` 實例，只修一條會漏）：
  | 路徑 | 客製點 |
  |---|---|
  | gateway route（各 `*BffRoutes` 的 `.uri(businessServicesUrl)`） | `DnsCacheConfig` 的 `HttpClientCustomizer` bean（`HttpClientFactory.createInstance()` 最後一步才套 customizer，必定蓋過預設） |
  | aggregation 的 `businessServicesClient` WebClient | `WebClientConfig` 明確 `.clientConnector(...)` 套 `applyDnsCacheLimit()` |
  本次事故兩條同時中（gateway 的 `/api/snapshots` 與 WebClient 的 `/api/bff/dashboard/enrich-dividend-rates` 同時報錯），
  即為「兩條路徑各自獨立」的實證。
- **共用資源**：WebClient 側以 Boot 的 `ReactorResourceFactory`（`org.springframework.http.client`，非 deprecated 的
  `...client.reactive` 那支）建 client，與 gateway 共用同一組連線池／event loop；該 bean 缺席時退回 `HttpClient.create()`，
  不讓 BFF 因此起不來。**自建 connector ＝已脫離 Boot 的 connector 組裝管線**：日後若導入 `spring.http.client.ssl` bundle
  或 `ReactorNettyHttpClientMapper` bean，對這支 WebClient 不會生效，必須同步補在 `WebClientConfig`。
- **啟動日誌痕跡**：兩條路徑各印一行 `[dns-cache] {gateway|webclient} 上游 DNS 正向快取上限 maxTtl=30s`。
  注意此 log 只證明「設定程式碼有執行」，真正的驗收是「重建 business-services 後 BFF 是否於 30 秒內跟上新 IP」的實測。

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
- `application.yml` 的 `app.admin-email` 綁定必填環境變數 `ADMIN_EMAIL`；`UserAdminService` 建構時完成 trim、小寫與格式驗證，缺值／格式錯誤立即中止啟動，禁止以內建 email 作 fallback。登入強制 ADMIN/ACTIVE、停用保護、角色保護與 DTO 標記一律呼叫同一個 `isConfiguredAdmin(email)`，避免多份判定漂移。
- `ADMIN_EMAIL` 只注入 business-services；BFF 只信任 business-services 回傳的 role/status，前端使用者列表只依 `UserResponse.protectedAdmin` 鎖定「主要管理者」，BFF／前端不得 hard code email。
- `v1.34.0-multi-tenant.sql` 為歷史 migration，不修改 checksum；`v1.71.0-configurable-admin-email.sql` 先由 catalog 動態掃描所有 `owner_user_id` 欄位（涵蓋歷史上未建 FK 的 owner 表），再以 foreign-key violation 兜底，只有完全無參照的舊固定管理者 seed 才刪除。日後更換 `ADMIN_EMAIL` 不會轉移 owner，舊帳號仍保有原資料，新主要管理者可用既有代看機制管理。
- 受隔離 entity 加 `@FilterDef(name="ownerFilter")`（定義於 `model/package-info.java`）+ `@Filter(condition="owner_user_id = :ownerId")`。create 流程以 `ctx.effectiveUserId()` set owner。
- **啟用點 `TenantFilterAspect`**：`@Before("execution(* com.steven.assets.repository..*(..))")` 在每次 repository 呼叫前，於目前 Hibernate session `enableFilter("ownerFilter")`。選 repository 層而非請求進入點，是因為此時已位於 service `@Transactional`（或 OSIV）綁定的 session 內，**不依賴 interceptor 與 OSIV 註冊順序**，過濾必定套用到實際執行的查詢（經實機驗證：帶 `X-User-Id` 不同值查 `/api/snapshots` 各自隔離）。
- **filter 僅在有 request context 時啟用**（aspect 以 `RequestContextHolder` 判斷）；背景 cron（`AlertNotificationDispatcher` / `StockAlertService.checkAlerts`）無 request context 故不啟用，照舊掃全體 alert、寄信給各 alert 自己挑的收件人。
- Hibernate `@Filter` 不套用於 `EntityManager.find()`（findById），by-id 存取另以 `TenantGuard.assertOwned(ownerUserId)` 驗證歸屬。
- ADMIN gate：輕量 `HandlerInterceptor` 讀 `X-User-Role`，對 `/api/backups/**`、`/internal/users/**`（管理）限 ADMIN（不引入整套 backend Security）。
- **身分相關例外 → HTTP 狀態對映**（`GlobalExceptionHandler`，三者成套、語意互斥）：

  | 例外 | 狀態 | 語意 |
  |------|------|------|
  | `UnauthenticatedException` | **401** | 未識別身分（請求未帶 `X-User-Id`／`CurrentUserContext.hasUser()` 為 false／`TenantGuard.requireCurrentUserId()` 回 `null`） |
  | `AdminRequiredException` | 403 | 已識別身分但權限不足 |
  | `TenantAccessException` | 404 | 已識別身分，資源存在但非本人所有（回 404 不洩漏他人資源是否存在） |

  `UnauthenticatedException`（`security/UnauthenticatedException.java`，`RuntimeException` 子類，預設訊息「未識別使用者」，另有帶訊息建構子供各呼叫端保留原本的情境描述）取代原本各 service 直接拋 `IllegalStateException` 的寫法——`IllegalStateException` 無對應 handler，會落入 `@ExceptionHandler(Exception.class)` 的 500 兜底，使「未帶身分」被回報成伺服器內部錯誤。呼叫端涵蓋五支排程設定 service 的 `requireOwnerId()`（`ExportScheduleService`／`RealizedGainExportScheduleService`／`TradingCalendarExportScheduleService`／`CommodityExportScheduleService`／`ExchangeRateExportScheduleService`）與 `PortfolioAdviceService.saveProfile()`／`generate()`。**此為錯誤語意修正，非資安邊界變更**：business-services 未對主機開埠，公開邊界（nginx:80／bff:8080）對未登入與偽造 header 本就正確回 401（`SecurityWebFilterChain` 自訂 `authenticationEntryPoint`），且 `ProblemDetail` 回應不含 stacktrace。

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
| `POST /internal/users/login-upsert` | business | 內部 | 登入 upsert + 回 role/status；`UserResponse.protectedAdmin:boolean` 由 business 判定 |
| `GET /internal/users` / `PATCH /internal/users/{id}/status` / `PATCH /internal/users/{id}/role` | business | ADMIN | 列出 / 核准·停用 / 設角色；每筆 `UserResponse` 含 `protectedAdmin` |
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
  - `getStockPrice(code, market)` 加 `英股` 分支 → `getYahooLsePrice(code)`：打 `https://query2.finance.yahoo.com/v8/finance/chart/{code}.L?interval=1d&range=1d`，透過 `curlGetWithRetry` 避開 Yahoo Java HTTP fingerprint 偵測；`meta.regularMarketPrice` 為 live、`meta.chartPreviousClose` 為昨收、**`indicators.quote[0].open[0]` 為當日開盤**（⚠️ 不可用 `meta.regularMarketOpen`／`meta.previousClose`——實測皆不存在於 chart meta，Task 194 修正）、`meta.regularMarketDayHigh`／`regularMarketDayLow`／`regularMarketVolume` 為 high／low／volume（實測存在），回 `PriceResult(market="英股", source="Yahoo")`
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

## Requirement 31：今日股市分析（每交易日 08:45 AI 判斷台股走向）

### 概觀

每個台股交易日開盤前（08:45 Asia/Taipei），business-services 呼叫 Claude Opus 4.8 一次，綜合「台股大盤＋美股主要指數近一年日線走勢」（本地 DB）與「近期國內外財經新聞」（由 `external-materials-service` 爬蟲寫入的本地 `news_headline`，分析時讀近 `news-max-age-days` 天注入 prompt；**Task 179 起已移除 `web_search`，不再上網搜尋**），對當天台股走向做多空判斷並存入 `daily_market_analysis`。前端「今日股市分析」頁顯示當日判斷＋歷史。此為**全域參考資料**（不分租戶、無 `owner_user_id`，比照指數日線／交易日曆）。

### 架構與模組落點

- **唯一新增的對外 LLM 呼叫在 business-services**：`market-analysis` 模組直接呼叫 `api.anthropic.com`（Anthropic Java SDK `com.anthropic:anthropic-java`）。此非「行情／報價外部 API」，不違反「即時股價走 Redis、收盤價走 DB、business-services 不直連外部行情 API」之規範（該規範針對 price/quote 資料）；business-services 本就有對外 egress（Gmail SMTP、rclone 備份）。
- **新聞來源＝本地爬蟲 `news_headline`（Task 179 起，`web_search` 已移除）**：財經新聞由 `external-materials-service` 的 `NewsPoller`（每交易日 08:20／11:30／18:00 Asia/Taipei）爬權威來源＋TWSE 公開資訊、寫入 `news_headline`（見 Requirement 31 Task 149.21／177／178）；`MarketAnalysisService` 分析時讀近 `news-max-age-days` 天注入 prompt，模型僅據此清單挑選 `newsHighlights`，**不再掛 `web_search` server tool、不上網**。走勢量化輸入沿用既有 `twse_index_daily_history` / `us_index_daily_history`（同一事實來源）。
- **BFF 一頁一支**：新增 `TodayMarketAnalysisBffController`（`/api/bff/today-market-analysis/**`），以 `Mono.zip` 聚合「當日 + 歷史 + 設定（含寄送時間 `sendTimes`）」單次回傳 `{today, history, settings}`，前端只 render。

```
Scheduler(每分鐘 tick, MON-FRI, Asia/Taipei) ──命中 market_analysis_send_time 啟用時點?──isTwTradingDay?──▶ MarketAnalysisService.generateForSend(today)  ── Task 191：預設 seed 08:45（Task 186 之時點），可設多個時段，各重跑＋各寄一封（送批次時重置 email_sent_at）
                                                                │  讀 twse_index_daily_history / us_index_daily_history（近一年）
                                                                │  讀 news_headline（近 N 天本地爬蟲新聞）
                                                                │  組 prompt（近期加權 + 注入本地新聞清單）
                                                                ▼
                                                     Anthropic Batch API（Opus 4.8 + adaptive thinking，Task 179 起不掛 web_search）
                                                                │  依走勢量化 + 本地新聞清單 → 產生 JSON 判斷
                                                                ▼
                                                     解析 JSON → upsert daily_market_analysis
前端 TodayMarketAnalysisView ──▶ /api/bff/today-market-analysis ──▶ business /api/market-analysis/{today,history,settings}
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
-- v1.43.0：每日 Email 寄送冪等記號（批次收尾首次落 OK 且寄出後戳記；Task 191 起每個寄送時段送批次時重置為 null → 重新寄一封）
ALTER TABLE daily_market_analysis ADD COLUMN email_sent_at TIMESTAMPTZ;
```

**可設定的分析寄送時間（Liquibase `v1.57.0-market-analysis-send-time.sql`，Task 191）**

```sql
CREATE TABLE market_analysis_send_time (
    id          BIGINT    GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    send_time   TIME      NOT NULL UNIQUE,   -- HH:mm（Asia/Taipei）分析寄送時點；每分鐘 tick 比對命中即觸發
    active      BOOLEAN   NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO market_analysis_send_time (send_time, active) VALUES ('08:45', TRUE);  -- Seed：Task 186 之原固定時點，保留現行行為
```

- Entity `MarketAnalysisSendTime`（無 owner 欄位、全域單一排程設定，比照 `MarketAnalysisSetting`）；每個 `active=true` 列＝一個每交易日觸發時點，各自跑一次全新分析並各寄一封（`email_sent_at` 於送批次時重置）。

- Entity `DailyMarketAnalysis`（無 owner 欄位、無 `@Filter`，不受 `TenantFilterAspect` owner 過濾）；`key_factors`／`news_highlights` 以 JSON 字串存，Controller 回前端時解析回陣列。
- **schema 基準線**：新表走 Liquibase 增量（比照 `v1.35.0-japan-gdp`）；`db/init/01_dump.sql` 為既有表基準線、不含此新表，由 Liquibase 於 business-services 啟動時建立（`ddl-auto: none` + Liquibase）。

### API 設計

business-services（`MarketAnalysisController`，`/api/market-analysis`）：

| Method | Path | 說明 |
|---|---|---|
| GET | `/api/market-analysis/today` | 最近一筆分析（DTO：含解析後的 keyFactors / newsHighlights 陣列）；無資料回 `{status:"NONE"}`。已登入者皆可讀。 |
| GET | `/api/market-analysis/history?limit=30` | 近 N 筆（`analysis_date` 降序）。 |
| POST | `/api/market-analysis/generate` | 送出當日批次分析並**立即回傳 `PROCESSING` 列**（Batch API 非同步；結果由 poller 收尾）。縱深防禦：`CurrentUserContext.isAdmin()` 否則 `AdminRequiredException`。 |
| GET | `/api/market-analysis/settings` | 分析設定（model / effort / enabled ＋白名單清單，另併帶 `sendTimes`）。已登入者可讀。詳見下方「模型頁面可調（成本控管）」之 API 表。 |
| PUT | `/api/market-analysis/settings` | 更新模型／思考深度／每日自動分析開關（至少一項；未帶之欄不變）。限 ADMIN（`isAdmin()`）。詳見下方「模型頁面可調（成本控管）」之 API 表。 |
| GET | `/api/market-analysis/send-times` | 分析寄送時間清單（`[{id,time,active}]`，`send_time` 升序）。已登入者可讀（亦併入 `/settings` 回應之 `sendTimes` 供聚合）。 |
| POST | `/api/market-analysis/send-times` | 新增寄送時間（body `{time:"HH:mm"}`；格式／唯一性驗證於 service，非法回 400）。限 ADMIN（`isAdmin()`）。回更新後清單。 |
| DELETE | `/api/market-analysis/send-times/{id}` | 刪除寄送時間。限 ADMIN。回更新後清單。 |
| PATCH | `/api/market-analysis/send-times/{id}/active` | 切換啟用／停用。限 ADMIN。回更新後清單。 |

BFF（`TodayMarketAnalysisBffController`，`/api/bff/today-market-analysis`）：

| Method | Path | 說明 |
|---|---|---|
| GET | `/api/bff/today-market-analysis?historyLimit=30` | `Mono.zip` 聚合 business `today` + `history` + `settings`（`settings.sendTimes` 內含寄送時間清單），單次回 `{today, history, settings}`。 |
| POST | `/api/bff/today-market-analysis/generate` | 轉發 business `generate`；bff `SecurityConfig` 對此 POST 限 `AUTHORITY_ADMIN`。timeout 180s（thinking 可能耗時；Batch API 非同步）。 |
| PUT | `/api/bff/today-market-analysis/settings` | 轉發 business `PUT /api/market-analysis/settings` 更新模型／思考深度；限 `AUTHORITY_ADMIN`。 |
| POST | `/api/bff/today-market-analysis/send-times` | 轉發 business 新增寄送時間；bff `SecurityConfig` 限 `AUTHORITY_ADMIN`。回更新後清單。 |
| DELETE | `/api/bff/today-market-analysis/send-times/{id}` | 轉發 business 刪除寄送時間；限 `AUTHORITY_ADMIN`。 |
| PATCH | `/api/bff/today-market-analysis/send-times/{id}/active` | 轉發 business 切換啟用；限 `AUTHORITY_ADMIN`。 |

另有 `TodayMarketAnalysisRecipientsBffRoutes`（同頁的 Gateway route 配置，非上表 controller）：`/api/bff/today-market-analysis/recipients/**` → rewrite 至 business `/api/notification-recipients/**`，供本頁維護分析寄送對象（見 Task 151「擴充：分析結果每日 Email 寄送 + 收件人訂閱選擇」）。

### 關鍵業務邏輯

- **排程**（`MarketAnalysisScheduler`，Task 191 起改每分鐘 tick 比對可設定的多個寄送時間）：`@Scheduled(cron="0 * * * * MON-FRI", zone="Asia/Taipei")` → 取 `nowHm = LocalTime.now(TW_ZONE)` 截到分，比對 `sendTimeService.activeTimes()`（`market_analysis_send_time` 之 `active=true`、截到分）是否含 `nowHm`；**不含即零成本 return**（不查 `enabled`、不做颱風假偵測、不呼叫 LLM）。命中才 `analysisService.isEnabled()` 為真時繼續 → 交易日閘門（`refreshTwClosureToday()` 權威即時颱風假偵測；`closedToday || !isTwTradingDay(today)` 為真則 log skip return）→ `analysisService.generateForSend(today, "scheduled@"+nowHm)`。**`generateForSend`＝強制重跑（不 skip-if-OK）＋送批次時重置 `email_sent_at=null`**，使該時段批次於 `finalizeIfReady` 收尾時重新寄一封（收尾仍先再驗一次交易日，Task 162 縱深守門不變）；同日已 `PROCESSING` 之守門保留（時段過近時不堆疊批次）。**開機 self-heal**：`@EventListener(ApplicationReadyEvent.class)` 延遲後，取 `activeTimes()` 之**最早時點**，若 today 為交易日、現在已過該最早時點、且今日尚無 OK 筆 → `generateIfAbsent` 補跑一次（背景執行緒，比照 `IndexDailyRefreshScheduler`；**不逐時段補寄**，避免重啟洗版；無任何啟用時間則不補）。背景 cron 無 HTTP request → `CurrentUserContext`（`@RequestScope`）取不到，不套 owner 過濾（本就全域）。
- **分析寄送時間 CRUD**（`MarketAnalysisSendTimeService`／`MarketAnalysisController`，Task 191）：`list()`／`add(time)`（`LocalTime.parse` 解析 `HH:mm`、截到分、唯一性檢查，重複或非法格式拋 `IllegalArgumentException` → 400）／`delete(id)`／`toggleActive(id)`，皆回更新後 `List<SendTime>{id,time,active}`。`getSettings()` 併帶 `sendTimes` 供頁面聚合。寫入端限管理者（BFF `SecurityConfig` 對 `POST/DELETE/PATCH /api/bff/today-market-analysis/send-times/**` 限 `AUTHORITY_ADMIN` + backend `CurrentUserContext.isAdmin()` 縱深）。全域設定、無 owner 過濾。
- **提示詞與近期加權**（`MarketAnalysisService.buildUserPrompt`／`buildSystemPrompt`，Task 179 起兩態）：TAIEX 近一年日線（date,close，由舊到新）＋近 20 日另附；美股 `DJI/SPX/IXIC/SOX` 近一年日線同格。system prompt 指派「資深台股策略分析師」角色、**越近期的走勢與新聞權重越高**、最後**只輸出 JSON**（schema：`bias/confidence/summary/keyFactors[]/newsHighlights[]/twContext/usContext`）、全程繁體中文。新聞面依「本地新聞是否存在」**二態**切換（不再有 `web_search` 開關）：**有本地新聞**＝注入 `news_headline` 近 N 天清單，指示模型只從此清單挑 3～6 則對今日走向最相關者列入 `newsHighlights`（`title`／`source`／`url`／`publishedAt` 照清單原樣、不得杜撰清單外新聞）；**無本地新聞**＝純技術面，`newsHighlights` 回空陣列、不得杜撰。本地新聞由爬蟲端（`NewsPoller`）已做地區／個股過濾且發布日精準，故毋須提示詞再引導 `web_search` 來源清單。
- **模型呼叫（Batch API，非同步；Task 179 起不掛 `web_search`）**：`claude-opus-4-8`（或設定切換之模型）、`ThinkingConfigAdaptive`、`OutputConfig.effort`（思考深度）——皆見「模型頁面可調」、`maxTokens≈16000`。**新聞來源改為本地 `news_headline`，`submitBatch` 不再 `addTool(WebSearchTool…)`**（`WebSearchTool20250305`／`ToolUnion` import 一併移除）；歷史上批次曾因動態版 `WebSearchTool20260209` 的 `code_execution` 在 Batch API `detection_timeout` 而改基本版（Task 149.20），現整條 `web_search` 已退場。請求以 **Message Batches API**（`client.messages().batches()`）送出，省 50% token 成本。收結果時收集回覆中所有 `text` 區塊（無 `web_search_tool_result` 需忽略），取首個 `{` 至末個 `}` 以 Jackson 解析（`@JsonIgnoreProperties(ignoreUnknown=true)`）。`PortfolioAdviceService` 的背景產生流程仍保有自己的 `web_search`，不受本次影響。
- **Batch 非同步流程**（`MarketAnalysisService.submitBatch` / `pollPendingBatches`）：`generateInternal` 於鎖內先查當日列——已 `PROCESSING` 即不重複送出（排程與手動皆然，避免重複花費）；否則 `submitBatch`：組 `BatchCreateParams.Request.Params`（1 request，`customId="ma-"+date`）→ `batches().create` → 落 `status=PROCESSING`＋`batch_id`＋`generated_at=now`（送出時間）→ 立即返回。**背景 poller** `MarketAnalysisScheduler.pollBatches`（`@Scheduled(fixedDelay=90s, initialDelay=60s)`，**不受 `enabled` 限**）→ `pollPendingBatches()` 撈 `findByStatus(PROCESSING)`；逐列 `batches().retrieve`：非 `ENDED` 則等下輪（超過 `BATCH_MAX_AGE=12h` 判 FAILED 逾時保護）；`ENDED` 則 `resultsStreaming` 以 `customId` 取本列結果，`isSucceeded()` → `asSucceeded().message()` 解析落 `OK`、其餘（errored/canceled/expired）落 `FAILED`，並清 `batch_id`。retrieve/results 暫時性例外不改狀態、下輪重試（逾時才判 FAILED）。**ENDED 判定務必以 `processingStatus().value()`（其巢狀 `Value` 才是真 Java `enum`）與 `ProcessingStatus.Value.ENDED` 比較，不可對 `ProcessingStatus` 本體用 `==`/`!=`**——它是 SDK enum-like 值類別（覆寫 `equals`、反序列化每次產生新實例），參考比較恆「不相等」會讓 poller 永遠認不出已完成的批次而空轉到 12h 逾時（Task 149.16 修正）。
- **參考新聞時效驗證**（`MarketAnalysisService.sanitizeNews`，Task 149.17 bug fix）：`web_search` 會撈到主題相關但已數月的舊文，且**模型自報的 `publishedAt` 不可信**（實測把 2025-09-18 的 UDN 文章標成「2026-06」），舊聞若原封顯示即被使用者當「近期重點」誤讀。故 `applyResult` 呼叫 `sanitizeNews(list, analysisDate)` 做三層把關，與既有 http(s) 白名單（XSS）併存：
  1. **格式＋自報時效硬過濾**：`parseIsoDatePrefix()` 要求 `publishedAt` 為精確 `YYYY-MM-DD`（`2026-06`／`null`／雜訊 → `null` → 剔除）；日期須在 `[analysisDate - newsMaxAgeDays, analysisDate + 1d]` 內，否則剔除。`newsMaxAgeDays` 由 `@Value("${market-analysis.news-max-age-days:5}")` 提供（**Task 149.18 由 30 收斂為 5 天**——只要「這幾天」的新聞、越近越重要；`sanitizeNews` 時效區間與 system/user prompt 皆同步套用此值）。
  2. **回抓原文實際發布日**（治本，`@Value("${market-analysis.news-verify-published-date:true}")` 可關）：對通過第 1 層且有 http(s) URL 者，以 lazy 共用 `java.net.http.HttpClient`（follow redirect、短 UA `Mozilla/5.0`、connect/read 逾時各約 6s）GET 原文，`extractPublishedDate()` 依序試 JSON-LD `"datePublished"`／`<meta property=article:published_time>`／`<meta itemprop=datePublished>`／`<meta name=date>`／`<time datetime>`，取首個可解析者。抓到真日期 → 以真日期覆寫 `publishedAt`（頁面顯示正確日）並套同一時效區間（真日期太舊即剔除，擋「模型謊報精確近期日期」）；抓不到／逾時／例外 → 保留第 1 層驗證後的模型日期（不因對方站台 WAF 擋抓而誤刪合法新聞；殘留風險：模型謊報精確近期日期＋原站不可抓，屬可接受殘留）。此為**單次引用驗證抓取**（非行情資料管線，不違反「行情走 external-materials-service」原則）；在 `finalizeIfReady` 背景 poller 執行緒進行，不在使用者 request 路徑。逐則序抓（單筆最多數則、序列可接受）。
  3. **提示詞把關**（`buildSystemPrompt`／`buildUserPrompt`，防禦縱深）：**Task 179 起提示詞已收斂為二態、全文無 `web_search` 字樣**——有本地新聞則指示「只從【近期新聞（本地抓取）】清單挑選、`title/source/url/publishedAt` 照清單原樣、不得杜撰清單外新聞」，無本地新聞則「不得杜撰、`newsHighlights` 回空」。惟 `sanitizeNews` 上述三層仍照舊套用於模型輸出（模型仍可能引用清單外內容），格式／時效／地區把關不變。（歷史：Task 149.17 時此處為「只納入經 web_search 實際查到、發布日在最近 N 天內的新聞」。）
  - 三層後 `newsHighlights` 可為 `[]`（頁面既有空狀態處理）——寧缺勿濫，當日多空判斷仍以走勢量化數據成立。剔除逐則 `log.info` 記原因供稽核。
- **新聞來源地區封鎖：只留台/美/星，剔除中港澳**（`MarketAnalysisService.sanitizeNews`，Task 149.18）：`web_search` 常回傳大量中國大陸／香港來源（實測某日 4 則有 3 則為 `finance.sina.com.cn`、`sfccn.com`），與需求「只看台/美/星、嚴禁中港澳」相悖。故在 `sanitizeNews` 迴圈**最前段**（取得 `url = safeHttpUrl()` 之後、日期解析與昂貴回抓之前）加「地區封鎖」關卡：命中即最早 `continue` 剔除，省下第 2 層 HTTP 回抓成本。
  - **比對規則**（`isRegionBlocked(url, source)`）：以 `java.net.URI` 取 `host`（小寫）＋新聞 `source` 顯示名，比對三類後端 curated 封鎖清單——① **地區 TLD 後綴** `BLOCKED_HOST_SUFFIXES`（`.cn`／`.com.cn`／`.hk`／`.com.hk`／`.mo`／`.com.mo`… `host.endsWith(suffix)`）；② **具名網域** `BLOCKED_DOMAINS`（中港澳媒體但用 `.com`/`.cc`/`.net`：`sfccn.com`、`eastmoney.com`、`caixin.com`／`caixinglobal.com`、`yicai.com`、`wallstreetcn.com`、`cnstock.com`、`stcn.com`、`jrj.com`、`hexun.com`、`jiemian.com`、`cgtn.com`、`21jingji.com`、`gelonghui.com`、`futunn.com`、`cnfin.com`、`yuncaijing.com`、`sina.com.cn`、`qq.com`、`163.com`／香港 `scmp.com`、`hket.com`、`mingpao.com`、`hkej.com`、`on.cc`、`hk01.com`、`stheadline.com`、`wenweipo.com`、`takungpao.com`、`stnn.cc`／澳門 `macaodaily.com`、`aamacau.com`… `host==domain` 或 `host.endsWith("."+domain)`）；③ **來源名關鍵詞** `BLOCKED_SOURCE_TOKENS`（新浪財經／東方財富／南方財經／財新／第一財經／南華早報／香港經濟日報… 繁簡兩式，`source.contains`）。
  - **清單性質**：後端 curated 技術白名單（非使用者管理之業務分類，比照 `AVAILABLE_MODELS` 免入庫、不套「Enum 入庫」規範），以 `static final Set` 保存於 service。以 `@Value("${market-analysis.news-region-block-enabled:true}")` 總開關可整體關閉（快速回退）。
  - **零誤殺（對抗驗證）**：規則只做「完整結尾後綴／整段網域相等或子網域」比對，**嚴禁 `contains("cn")`/`contains("china")` 子字串比對**——否則會誤殺 `cnyes.com`（台灣鉅亨網）、`cna.com.tw`（台灣中央社）、`cnbc.com`、`cnn.com`（美國）。經 workflow 對抗驗證：台美星主流財經網域＋上述 6 個 lookalike 全部零 false positive，並補齊 10 個原漏網的中港澳 `.com`/`.cc` 網域。`source` 名關鍵詞亦逐一比對約 60 個台/美/星來源名無碰撞（如「華視」不含「新華社」、「信傳媒」不含「信報」）。
  - ~~**prompt 積極列出（避免空手）**~~（**Task 149.19 史實；Task 179 起 prompt 已無 web_search**）：（歷史）實測手動重跑一次時（Sonnet 5／effort low／web_search 3），模型在「5 天內＋只台美星＋嚴禁中港澳」收緊條件下過度保守、原始輸出即 `"newsHighlights": []`，故當時 `buildSystemPrompt`／`buildUserPrompt` 曾改為積極要求多次 web_search 並列出符合條件的新聞。**Task 179 移除 web_search 後**，prompt 改為「有本地新聞→從清單挑 3～6 則、無→回空」，此「積極 web_search 搜尋」指示已不再適用（新聞廣度改由爬蟲端 `NewsPoller` 決定）。
- **本地財經新聞爬蟲 `news_headline`（Task 149.21，producer=external-materials-service／consumer=business-services）**：以「自抓權威來源＋證交所公開資訊、餵入提示詞」取代/補充付費 `web_search`。
  - **資料流**：external-materials-service（**純 producer**，`JdbcTemplate` 直寫共用 postgres、無 Liquibase/JPA，比照 `stock_price_history`／`stock_dividend_history`）抓取後 upsert 至 `news_headline`；backend（**consumer**，擁有 Liquibase schema）以 JPA `NewsHeadlineRepository` 讀近 N 天餵入分析 prompt。Redis 只放高頻即時價、不放新聞。
  - **ERD `news_headline`**（backend Liquibase `v1.45.0-news-headline.sql`；ext 直寫）：`id BIGSERIAL PK`、`title VARCHAR(500)`、`source VARCHAR(100)`（wantgoo/moneydj/ltn/udn/**cnbc/nasdaq**〔美國財經新聞，Task 198〕/twse/bot-fx/us-index/kr-index〔Task 185〕/kr-intraday〔Task 193〕/ma-cross〔Task 207〕）、`url VARCHAR(1024)`、`category VARCHAR(32)`（`news`／`twse-institutional`／`twse-turnover`／`fx`／`us-market`〔Task 180〕／`kr-market`〔Task 185〕／`kr-intraday`〔Task 193〕／`ma-cross`〔Task 207〕）、`region VARCHAR(16)`（TW／US／KR…）、`summary TEXT`、`published_at TIMESTAMPTZ`（原文/資料真實發布時間）、`fetched_at TIMESTAMPTZ DEFAULT now()`、`dedupe_key VARCHAR(64)`（`sha256(source|url|category)`）。索引：`uk_news_headline_dedupe`（UNIQUE，供 ext `ON CONFLICT` upsert）、`idx_news_headline_recent(published_at DESC)`、`idx_news_headline_cat(category, published_at DESC)`。全域參考資料（無 `owner_user_id`，比照 `twse_index_daily_history`）。
  - **爬蟲資訊查詢頁 ＋ 動態執行時間（Requirement 38）**：新增「公開資訊」子頁 `/crawler-data`（`CrawlerDataView`）。
    - **依日期查詢**：`CrawlerDataBffController` → business `NewsHeadlineController`（`GET /api/news-headlines?date=&dateField=fetched|published&category=`）讀 `news_headline`。`dateField` 決定用 `fetched_at`（爬取入庫日）或 `published_at`（資料日）當篩選欄位，區間皆為「Asia/Taipei 該日 00:00（含）～翌日 00:00（不含）」轉 `Instant`；`category` 選填。repository 加 `findByFetchedAtBetweenOrderByFetchedAtDesc`／`findByPublishedAtBetweenOrderByPublishedAtDesc`，`category` 於 service 層過濾（單日資料量小）。與 `MarketAnalysisService` 讀同一份 `news_headline`（同義欄位、同一表）。
    - **動態執行時間（多時間點）**：`NewsPoller` 執行時間改由新表 `crawler_schedule`（`crawler_key='news-poller'`，一列一時間點）決定，可於頁面增減。`NewsPoller` **移除寫死的三個 cron（`0 20 8`／`0 30 11`／`0 0 18`，Task 184／188）**，改為**每分鐘 ticker** `@Scheduled(cron="0 * * * * *", zone="Asia/Taipei")`：讀 `crawler_schedule` 已啟用時間點（ext 端新增 `CrawlerScheduleQuery` JdbcTemplate 讀取），命中當前 `HH:mm` 即 `run("scheduled")`。cron 每分鐘僅觸發一次故天然去重、無需額外 slot guard；`run` 以 `dedupe_key` upsert 本就冪等，重跑亦無害。**DB 讀取例外**（表缺／連線失敗）**fallback 至預設 08:20 / 11:30 / 18:00**，避免爬蟲靜默停擺；讀到「空清單」＝使用者刻意清空＝該分鐘不跑。開機 warmup 與保留期清理不變。設定變更免重啟、下一分鐘生效。business 端 `CrawlerScheduleController`（`GET/PUT /api/crawler-schedule?crawler=news-poller`）讀／整批覆寫（delete+insert，驗 0–23／0–59、去重）；`PUT` 限 ADMIN。
    - **動態輸出檔案路徑（Task 212）**：`NewsPoller` 每輪產出的公開資訊 JSON（Task 177）輸出目錄，由寫死的 `news-scraper.export-dir`（容器 `/srpp-input`，只能改 docker volume 換目的地）改為新表 `crawler_export_setting`（`crawler_key='news-poller'` UNIQUE，一爬蟲一列）決定，可於同頁設定。
    - **同步上傳 Google Drive（Requirement 50 / Task 245）**：同頁可另外開啟「同步上傳 Google Drive」並指定 Drive 目標資料夾（樹狀選擇器挑選）。**本機那一份照寫不變**——SRPP 依賴本機 `public_info_<日期>.json`，故 Drive 是附加副本而非替代目標，刻意不提供「只寫 Drive」選項。本機檔寫成功後才以 `rclone copyto` 上傳同一份檔案；上傳為 best-effort（失敗只記 ERROR log ＋ `gdrive_last_status`，不 rollback 本機檔、不中斷 `news_headline` 入庫、不擲例外中斷排程），無 retry queue（每輪重新產檔重新上傳＝天然重試）。設定同樣每輪即時讀取、免重啟。
      - **路徑模型＝Requirement 34／39／41／42 那一套**：DB 只存**相對子路徑** `output_subpath`，實際目錄 = 容器內基底 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`）resolve 之。**關鍵前提：`external-materials-service` 也掛上 `${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}:/home/steven`**（原本只有 business-services 有），使「前端資料夾樹（由 business 的 `browse` 列舉）看得到的目錄」＝「ext 爬蟲寫得到的目錄」；兩服務基底路徑同為 `/home/steven`、同一 host 掛載，否則同義的「輸出資料夾」會在兩服務指向不同實體目錄。原 `/srpp-input` 掛載與 `news-scraper.export-dir` 一併移除（其預設目的地 `/Users/steven/Project/SRPP/data/input` 已落在家目錄內，改以 seed 子路徑 `Project/SRPP/data/input` 表達，host 落點完全相同）。
      - **每輪即時讀取**：`exportPublicInfoJson()` 於寫檔前呼叫 ext 端新增的 `CrawlerExportPathQuery`（`JdbcTemplate` 純讀，比照 `CrawlerScheduleQuery`）取現值，**不快取於欄位**，故設定變更下一輪即生效、免重啟。DB 例外或值為空 → fallback 至常數 `Project/SRPP/data/input`（＝改動前行為），避免靜默改寫落點。
      - **路徑驗證兩道（縱深防禦）**：business 於 `PUT` 時驗（`..`／絕對路徑跳脫基底 → 400，複用與 `ExportScheduleService.resolveDir` 相同規則）；ext 寫檔前**再驗一次**，DB 值異常則退回預設並 warn——ext 是實際持有檔案系統寫入權的一方，不能只信上游驗過。
      - **檔名不開放設定**：維持 `public_info_<yyyy-MM-dd>.json`（SRPP 依此檔名取用），本次只開放目錄；暫存檔＋原子 rename 的寫出方式不變。
    - **排程清單同步**：`SchedulePublicBffController` 靜態清單中 NewsPoller 該筆 cron 由 `0 20 8`／`0 30 11`／`0 0 18` 改標「動態：依『爬蟲資訊查詢』頁設定（預設 08:20 / 11:30 / 18:00）」，避免與實際排程漂移。
  - **ext 抓取（`NewsPoller` cron 08:20/11:30/18 Asia/Taipei ＋ `ApplicationReadyEvent` warmup ＋保留 `news-scraper.retention-days` 天）**：`NewsFetchClient`（玩股網 WantGoo JSON API＋MoneyDJ HTML＋自由時報 財經・**政治・國際**〔Task 180〕/經濟日報 RSS〔`region=TW`〕＋**美國財經新聞 CNBC〔Economy／Finance／Markets 三分類〕＋Nasdaq Markets RSS〔`region=US`、`source=cnbc`／`nasdaq`，Task 198〕**，讀真實 `time`/`publishAt`/`pubDate`；UA `Mozilla/5.0`；RSS 用內建 XML/regex 解析、不引第三方；`fetchRss(url, source, region)` 帶 `region` 參數〔台灣來源傳 `TW`、美國來源傳 `US`〕；**台灣混合型一般新聞 feed（自由時報 財經／政治／國際、經濟日報）套 `EditorialNewsFilter.retain`〔Task 199 編輯收錄政策，取代舊 `relevantOnly`／`RELEVANCE_KEYWORDS`〕，逐 feed 過濾——財經一律留、中國新聞只留財經/北京政權、政治只留美日台歐盟＋影響市場地緣、台灣地方只留北北高、其餘濾除；純財經來源 wantgoo／MoneyDJ 與美國 CNBC／Nasdaq 財經專屬 feed 豁免不過濾**；**鉅亨網 cnyes 已於 Task 149.22 移除**）＋`TwseInfoFetchClient`（BFI82U 三大法人買賣金額 RWD JSON、FMTQIK 大盤成交統計 openapi JSON）＋`MarketSnapshotFetchClient`（Task 180／185，由 DB 既有資料組**匯率**＋**美股指數**＋**韓國股市**快照，見下）＋`KrIntradayFetchClient`（Task 193，**即時抓 Yahoo** 組**韓股盤中**快照，見下；為本輪唯一會打外部行情 API 的快照來源）。逐來源 graceful（單一失敗只 warn）。`StockSourceQuery.upsertNews / deleteNewsOlderThan`。**cron 早上那次由 06:00→08:00（Task 177）→08:20（Task 184）、中午 12:00→11:30（Task 188），仍早於 08:45 今日股市分析（拆三 cron `0 20 8`＋`0 30 11`＋`0 0 18`）。來源皆台/美權威網站，不抓中港澳。**
  - **公開資訊輸出 JSON 供 SRPP（Task 177，DB 為單一來源）**：`NewsPoller` 每輪（cron 08:20/11:30/18 ＋ warmup）**先 upsert `news_headline`，再由 DB 查詢產生 JSON**（不再用記憶體 `rows`；JSON＝DB 當日快照，自然含去重＋個股過濾）。查詢＝`StockSourceQuery.loadTodayPublicInfoForExport(today, cutoff)`：`WHERE (fetched_at AT TIME ZONE 'Asia/Taipei')::date = today AND (published_at AT TIME ZONE 'Asia/Taipei')::date >= cutoff ORDER BY published_at DESC`。`cutoff`＝上一交易日＝`StockSourceQuery.lastTwseTradingDate()`＝`MAX((published_at AT TIME ZONE 'Asia/Taipei')::date) WHERE category LIKE 'twse-%'`（twse 資料自帶日期即 TWSE 權威上一交易日；BFI82U 遇假日回最近交易日）；無 twse 時 fallback `MarketCalendar.isTwTradingDay` 往回找。**刻意不用日曆算 cutoff 為主**——原型：日曆得 7/10、twse 實際 7/09，用 7/10 反把 twse 濾掉；取 twse 自身日期保證總體資料保留、且排除更舊過期新聞。`fetched_at` 於 upsert ON CONFLICT 刷新為 NOW()，故「今天抓到」涵蓋今天各輪碰到的列。輸出目錄 = 容器內基底 `EXPORT_OUTPUT_DIR`（預設 `/home/steven`，`docker-compose.yml` 掛 host `${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}`）resolve `crawler_export_setting.output_subpath`（**Task 212 起可於「爬蟲資訊查詢」頁設定**，seed `Project/SRPP/data/input`；原寫死的 `news-scraper.export-dir`＋`/srpp-input` 掛載已移除，host 落點不變）。檔名 `public_info_<yyyy-MM-dd>.json`（同日覆寫、跨日新檔）；結構 `{ generatedAt, trigger, tradingDayCutoff, count, items:[{title,source,url,category,region,summary,publishedAt}] }`。`news_headline` 保留期（30 天）不受當日範圍影響（供分析讀近 N 天）。`publishedAt` 以 `@JsonFormat` 於 Asia/Taipei（+08:00）序列化（日期與交易日／`tradingDayCutoff` 一致，不因 UTC 倒退一天）。寫出採**暫存檔＋原子 rename**（`Files.createTempFile`＋`Files.move(ATOMIC_MOVE)`）——warmup 執行緒與 cron 可能併發寫同一檔，原子 rename 避免截斷毀損、SRPP 不讀半寫檔。`export-enabled` 可關；寫檔失敗 graceful。
  - **量化快照：匯率＋美股指數＋韓國股市（`MarketSnapshotFetchClient`，Task 180／185）**：使用者要求公開資訊「一定要有」台幣兌美元匯率、美股與韓股重要資訊；此三者原本各自落在 `exchange_rate_history`／`us_index_daily_history`／`foreign_stock_daily_history`、**不在 `news_headline`**，故不進 SRPP 公開資訊 JSON、也不在今日股市分析本地新聞區塊。故由 `MarketSnapshotFetchClient.fetchAll()` 讀**已抓好**的 DB 資料組三則 `NewsRow`，交 `NewsPoller` 與其他來源一併 upsert（DB 為單一來源，SRPP JSON 與今日股市分析都吃得到）：
    1. **匯率**（`category=fx`／`source=bot-fx`／`region=TW`）：`StockSourceQuery.loadLatestUsdRate()` 取 `exchange_rate_history` 最新 USD 列，標題 `台幣兌美元(USD/TWD)匯率（資料日 {rate_date}）：即期買入/賣出/中間價`（中間價 = (買+賣)/2）。
    2. **美股指數**（`category=us-market`／`source=us-index`／`region=US`）：`StockSourceQuery.loadLatestUsIndexClose(code)` 取 `us_index_daily_history` 各碼最近兩筆算漲跌%，一則合併標題 `美股主要指數（截至 {session} 收盤）：道瓊/標普500/那斯達克/費半 {收盤}（{漲跌%}）`。指數資料由 Yahoo Finance（美國站，非中港澳）抓、`IndexDailyRefreshScheduler` 寫入。
    3. **韓國股市**（`category=kr-market`／`source=kr-index`／`region=KR`，Task 185）：一則合併標題 `韓國股市（截至 {session} 收盤）：KOSPI {點數}（{漲跌%}）、三星電子 {價}（{漲跌%}）、SK海力士 {價}（{漲跌%}）`（KRW）。KOSPI 讀既有 `us_index_daily_history` 之 `index_code=KOSPI`（既有海外指數管線抓取、**不重抓**）；三星電子 005930／SK 海力士 000660 由 `StockSourceQuery.loadLatestForeignStockClose(code)` 取 `foreign_stock_daily_history` 最近兩筆算漲跌%，資料由 `KrStockPoller`（每日 16:00 Asia/Taipei ＋ warmup，Yahoo `005930.KS`／`000660.KS`、時區 Asia/Seoul）寫入。三項逐一 graceful，任一缺料只略過該項。
    - `publishedAt` 皆取**抓取當下時間**（`Instant.now()`）以保證恆通過 `loadTodayPublicInfoForExport` 的「當日 fetched ＋ published≥cutoff」範圍、恆出現在當日公開資訊；真實資料日期改明列於標題（快照型資料非逐日新聞，此取捨換取「一定有」）。`url` 固定（台銀牌告頁／Yahoo world-indices／Yahoo `^KS11` 報價頁）→ `dedupe_key` 每輪相同 → 就地覆寫為當日最新一列。皆讀 DB、不另打外部 API；逐項 try/catch graceful。`fx`／`us-market`／`kr-market` 於下述個股過濾一律保留（非 `news` category）。
  - **韓股盤中快照（`KrIntradayFetchClient`，Task 193）**：上述 `kr-market`（Task 185）讀 DB，而 KOSPI 由 `IndexDailyRefreshScheduler`（07:00 Asia/Taipei＝08:00 KST，**韓股未開盤**）、三星／海力士由 `KrStockPoller`（16:00 Asia/Taipei＝17:00 KST，**已收盤**）寫入，故 08:20 那輪的 `kr-market` **恆為前一交易日收盤**。但韓股 09:00–15:30 KST **＝台北 08:00–14:30**，08:20 爬蟲跑時已開盤 20 分鐘 → 當日開盤動向（對台股 09:00 開盤具領先參考）從未進分析。故新增**第四則快照**（`category=kr-intraday`／`source=kr-intraday`／`region=KR`），一則合併標題 `韓國股市盤中（{yyyy-MM-dd} {HH:mm} KST）：KOSPI {價}（開盤 {開}，較昨收 {±%}）、三星電子 …、SK海力士 …`（KRW）。
    - **落點刻意獨立於 `MarketSnapshotFetchClient`**：後者契約為「皆讀已抓好的 DB 資料、不另打外部 API」，本 client **會即時打 Yahoo**，故另立 `@Component` 以維持該契約不被破壞。`NewsPoller.run()` 以第四個 `rows.addAll(...)` 接線，與既有三個來源同 pattern。
    - **不新增任何 `@Scheduled`**：掛 `NewsPoller` 既有輪次即可，故 `SchedulePublicBffController` 靜態排程清單無須更動（該清單只列 `@Scheduled`）。**產出與否只由時段閘門決定，與 `NewsPoller` 執行時點無耦合**——現行 08:20／11:30 落在韓股盤中故產出、18:00 在收盤後故不產出（當日收盤已由 `kr-market` 涵蓋）；日後調整爬蟲時點無須改動本功能。
    - **雙閘門（免自建韓國假日曆）**：repo 內無韓國假日資料，且設날／추석 屬農曆，`MarketCalendar` 之 `nthWeekday`／`goodFriday` 純函式算不出；`KrStockPoller` 靠「Yahoo 對非交易日不回 bar」繞過，盤中無法沿用。故 (1) **時段閘門**＝台北 MON–FRI 08:00–14:30（本地時鐘，`Clock` 建構子可注入供測試；「是否盤中」一律由此判定）；(2) **資料閘門**＝`meta.regularMarketTime` 換 `Asia/Seoul` 之**日期** ≠ 今日 KST → 判韓國休市不產出（休市時 Yahoo 回前一交易日 bar，日期天然對不上）。**`regularMarketTime` 只可取日期**——實測 KOSPI 回 18:05 KST（落在收盤後），拿它判「是否盤中」會誤判。
    - **Yahoo 欄位落點（`PriceFetchClient.fetchKrIntradayQuote`，`interval=1d&range=1d`）**：現價＝`meta.regularMarketPrice`；昨收＝`meta.chartPreviousClose`（`meta.previousClose` 實測不存在）；**開盤價＝`indicators.quote[0].open[0]`**。⚠️ **不可用 `meta.regularMarketOpen`——實測不存在於 chart meta**，誤用會使開盤價恆為 `null`、功能靜默半殘（開盤價正是本任務核心）；`getYahooLsePrice` 原即誤用該欄而踩中此坑（英股 `openPrice` 恆 null），已由 Task 194 修正為同一落點。symbol 吃**完整 Yahoo symbol**（KOSPI＝`^KS11` 無 `.KS` 後綴，不可沿用 `fetchKrHistoricalRange` 的字串串接）。沿用 `curlGetWithRetry`（短 UA `Mozilla/5.0`）——**不可用 `httpGet`**（長 Chrome UA 會被 Yahoo WAF 擋 429）。亦**不走 `getStockPrice`**（其 else 分支落到 NASDAQ，傳 `"韓股"` 會靜默回空）。
    - **與 `kr-market` 的消歧**：08:20 的 prompt 會**同時**出現 `kr-market`（前一交易日收盤）與 `kr-intraday`（今日盤中）兩則。標題各自載明日期與「收盤／盤中」，`summary` 再交代兩者關係，避免 LLM 混淆。`url` 固定 → `dedupe_key` 恆定 → 永遠只有一列、每輪就地覆寫為最新盤中值。三檔逐一 graceful，全空則不產列。沿用 Task 185「只餵資料給分析」，無專屬輸出欄位／前端區塊。
  - **台股均線突破快照（`MaCrossSnapshotClient`，Task 207）**：由 DB 日收盤自算季線 MA60／年線 MA240（SMA，與 business-services 之 `TechnicalIndicatorService` **同定義**；該 service 在 business-services、爬蟲不可跨呼叫，故讀相同來源自行重算以維持「同一事實、同一定義」），偵測**台股大盤（`twse_index_daily_history.close_point`）／0050／00881（`stock_price_history.close_price`）於前一交易日**收盤對均線的漲破／跌破，命中才產列（平常輪次自然為空，不塞雜訊）。**不新增 `@Scheduled`**——掛 `NewsPoller` 既有輪次，排程列表頁筆數不變。
    - **穿越判定（逐日對齊自身均線）**：漲破＝`cPrev2 ≤ maT1` 且 `cPrev > maT`；跌破反之。各日收盤比各日**自己**的均線，避免均線本身移動造成假穿越。
    - **分割／反分割防呆（兩層）**：來源為**未還原權值**原始收盤（0050 於 2025-06 分割使收盤驟降 ~4 倍）。(1) **單日層**：前一交易日對前二日變動 > 15% → 該標的整檔跳過（擋分割**當日**）；(2) **視窗層**：該均線取樣視窗 `[n-1-period, n-1]` 內任一相鄰兩日變動 > 15% → 該均線本輪略過，直到舊權值滾出視窗（擋分割**之後**）。缺第二層時，受污染的 MA 會單調下滑並**自己穿過靜止股價**，在分割後約第 60／240 個交易日噴出「股價沒動卻漲破年線」的假訊號（`MaCrossSnapshotClientTest` 以 30 筆 190＋60 筆 47.50 起微升的序列釘住此回歸）。15% 門檻不誤殺真突破（台股 ±10% 漲跌幅限制、真突破多在 ±1~3%）。
    - **`publishedAt` 取事件「資料日」（非 `now()`）**：使突破依真實發生日隨新聞時效窗自然老化；否則每輪重抓都把 `published_at` 推到今天，舊突破被當「今天的新聞」重複餵入分析數日。因資料日可能早於匯出 cutoff（大盤指數表落後屬常態——`TwseIndexPoller` 08:30 補抓晚於 08:20 盤前輪），`loadTodayPublicInfoForExport` 對 `category='ma-cross'` **開 cutoff 例外**（只受 `fetched_at` 當日管），避免該列被 SRPP JSON 靜默濾掉、或同日不同輪次時有時無。
    - **`url` 帶事件識別 fragment**（`#代號-ma期數-資料日`）：`dedupe_key = sha256(source|url|category)`，三者皆固定則 `ma-cross` 永遠只有一列、後來事件整列覆寫先前的，使分析視窗內既有事件被靜默吞掉且無法重建（`detect()` 只讀最後兩根、不補偵測）。帶 fragment 後每個 (標的, 均線, 資料日) 各一列，同輪重跑仍冪等覆寫同一列。
  - **個股過濾（只留 stock 主檔個股＋總體，Task 178；Task 180 擴保留 `fx`／`us-market`；Task 185 含 `kr-market`；Task 193 含 `kr-intraday`；Task 207 含 `ma-cross`）**：`NewsPoller` 抓取後、upsert 與輸出 JSON **前**，經 `PublicInfoStockFilter.retain(rows)` 過濾。**保留條件由「`category` 以 `twse` 開頭」放寬為「`category != "news"`」**（Task 180），使 `twse-*`／`fx`／`us-market`／`kr-market` 等量化/總體公開資訊一律保留、只有一般新聞（`news`）才進個股過濾判定。每則一般新聞的「提到台股個股代號集合 `mentioned`」＝ wantgoo `newsTags`（`name→code` 命中全市場名冊）∪ 明確代號格式（**須帶 `-TW`**，如 `(6967-TW)`/`6967-TW`；title+summary，∩ 全市場 codes；**不認裸數字或括號內年份如 `(2023)`**——2020～2031 年份＝真實鋼鐵股代號會誤傷，Task 178 review 修正）。`mentioned` 非空且全不在 `stock` 主檔 → 濾除；否則保留。全市場 `name→code` ＝ `MarketDataFetchService.twMarketNameToCode()`；主檔代號 ＝ `StockSourceQuery.allStockCodes()`。名冊空（載入失敗）→ 全留 graceful。wantgoo 原始 tags 經 `NewsRow.tags`（`@JsonIgnore`、不入 DB／JSON）攜帶至 filter。
  - **台灣中文新聞編輯收錄政策（`EditorialNewsFilter`，Task 199）**：對台灣「混合型」一般新聞 feed（自由時報 財經／政治／國際、經濟日報）於 `NewsFetchClient` **各 feed 抓取後逐 feed 套** `EditorialNewsFilter.retain(rows)`，收斂收錄範圍；美國 CNBC／Nasdaq（Task 198）**豁免**（英文財經專屬 feed，本過濾之關鍵詞為中文）。**純財經來源（wantgoo／MoneyDJ）自 Task 240 起改套 `retainForFinanceFeed(rows)`／`traceFinanceFeed(text)`＝僅「三條凌駕 `FINANCE` 的否決集」（⓪`isAnecdote`／⓪b `LOTTERY`／⓪c `ESTATE`），其餘一律 `KEEP:finance-feed` 兜底（**方法體內不含 `FINANCE` 判定**——兜底本就是 KEEP，加了多餘；更不可比照 `trace()` 把 `FINANCE` 排在三條否決之前，那會讓「7-11開出千萬中獎發票」在純財經來源退回 `KEEP:finance`、本任務對 wantgoo／moneydj 失效）**——不套 `LIFESTYLE`／`SOCIAL_ODDITY`，不套規則③～⑧的白名單 KEEP 邏輯，不套 `DROP:non-finance-general` 兜底。**此界線是正確性關卡而非風格取捨**：`FINANCE` 是為「從一般新聞版面挑出財經」而設的白名單，純財經站的財經用語大量不在其中，套整套 cascade 會誤殺「Waymo 傳終止合作 Uber 跌逾4%」「〈華德動能訪廠〉市占率上看3成」「基本工資調漲至3萬」等（語料實測 41 則）；即使保留 `FINANCE` 豁免，`LIFESTYLE` 仍會誤殺「雄獅賞楓行程銷售破5成」「《DJ在線》旅行社團費趨穩」「諾和諾德怒告禮來」等**消費／旅遊類股**報導，`SOCIAL_ODDITY` 的「越獄」則誤中 AI jailbreak（「Hugging Face 遭沙盒『越獄』攻擊」）。三條否決集可跨來源套用，正因其語意即「**即使帶財經詞也無投資資訊量**」，與來源是否財經專屬無關。**取代舊 `relevantOnly`／`RELEVANCE_KEYWORDS`**（僅政治／國際、只做財經・政策・地緣白名單）。判定＝純關鍵詞優先序 cascade（標題為主）。**Task 221 於 cascade 前後各插入一條否決規則**：⓪**財經軼事／都市傳說否決** `isAnecdote`（`N歲` ＋〔身分稱謂｜釣魚詞〕皆命中；**唯一凌駕 FINANCE 的規則**——這類標題必帶財經詞，不先於財經判定就永遠攔不到。爆炸半徑為零：全語料命中數＝含「N歲」者之命中數）→ ⓪b **發票／彩券中獎否決** `LOTTERY`（Task 240；統一發票／中獎／獎號／兌獎／刮刮樂／幸運兒／頭獎／大樂透 共 8 詞〔每詞皆有語料樣本〕，另加 `發票` ∧ `開出` 的 AND 組合。**必須凌駕 `FINANCE`**——此類標題必帶金額詞〔`千萬`／`加碼`／`億`〕故在規則①即被 `KEEP:finance` 攔下；而 `千萬`／`加碼` **不得移除**——理由不是移除會大量誤殺（實測 `千萬` 為唯一財經訊號者 17 則，僅泰山捐三千萬、HH 草本公益捐助 2 則屬企業新聞），而是**移除解決不了問題**：「大樂透頭獎連17摃　加碼100萬獎」等 4 則移除 `千萬` 後仍靠 `加碼` 判 `KEEP:finance`，而 `加碼` 是「外資加碼／加碼投資」的核心財經語彙、移除風險更高。`開獎` 刻意排除——財經媒體借喻財報公布，會誤殺「AI巨頭財報前瞻…微軟、Meta、蘋果下周開獎」；裸 `發票` 亦排除，會誤殺 wantgoo「陳時中：食安基金補助打官司 退貨持發票憑證僅空瓶也收」（該則走 `traceFinanceFeed`）；`特別獎` 亦排除，有 `特別獎金` 子字串風險〔「台積電發放特別獎金 每人平均逾百萬」〕且已由 統一發票／獎號 覆蓋；零樣本推測詞 對獎／威力彩／今彩／四星彩／三星彩／連摃／摃龜／槓龜 一律不納入）→ ⓪c **家事遺產糾紛否決** `ESTATE`（Task 240；`ESTATE_TOPIC`〔遺產／遺囑／繼承人／特留分〕∧ ¬`ESTATE_EXEMPT`〔法制：立院／立法院／修法／法案／三讀／朝野／釋憲／大法官；稅制：國稅局／財政部／申報／稅制／課稅／遺產稅／贈與稅／節稅；市場主體：台積電／股利／配息／除息／董事長／董座／經營權／接班——**刻意不收 `集團`**，語料中與「遺產／繼承」零共現、且它是高頻泛詞〔詐騙集團〕又同時在 `FINANCE`，收了會讓「詐騙集團騙走老翁遺產」落回 `KEEP:finance`〕。**必須凌駕 `FINANCE`**——使用者回報的「很多家庭急著分遺產 忘了另一位父母還活著」標題單獨判為 `DROP:non-finance-general`，是其**摘要**含「存款」「房子要不要賣」把它救回 `KEEP:finance`，僅靠來源接線不足。豁免集使「遺產可免分兄弟姊妹？立院朝野拍板 特留分修法」「被繼承人遺有應收股利 遺產稅申報一次看」「台積電配息創新高、繼承股票先別high！國稅局爆…」**不被本規則否決**（能否收錄仍由後續 cascade 決定，非保證 KEEP）。`家產` 刻意排除——誤中「國**家產**業園區」→ 誤殺三星龍仁晶圓廠量產新聞；`分產`／`爭產` 亦排除〔`充分產能`／`部分產品`／`競爭產業` 子字串風險，且該類標題已由 `遺產` 命中〕）→ ①**財經一律留**（最先判、關鍵詞群 `FINANCE` 涵蓋股市／上市櫃公司／半導體／金融／房市／能源／匯率／IPO／財報，確保財經即使提到非白名單縣市也不誤刪；亦為生活軟文體育的唯一豁免。**Task 240 將公司名 `陽明` 改為 `陽明海運`，並配套補入 `運價`**——裸 `陽明` 誤中「陽明交大」使教育新聞被判 `KEEP:finance`；語料含「陽明」10 則＝7 則真陽明海運（皆另含並集 `關稅`／`營收`／`航運`／`營運`／`集團`／`陽明海運`／`供應鏈` 之一）＋2 則陽明交大產學（另含 `鴻海`／`矽光子`／`半導體`）＋1 則教評會，前九者維持 KEEP、僅教評會翻 DROP，零誤殺。`運價` 為必要配套：窄化後唯一公司訊號為「陽明」的航運標題〔「陽明7月運價走弱 貨量持平」〕會失去保護，而語料 `運價` 11 則（標題 10＋僅摘要 1）全為航運／空運財經、零歧義。同「已移除『北市』避免誤中『竹北市』」之陷阱類型）→ ①b **社會獵奇／犯罪獄政／榮典否決** `SOCIAL_ODDITY`（越獄／囚犯／監獄／鱷魚／動物園／追晉／褒揚令；置於財經豁免之後、其他所有規則之前，才攔得到原本在規則④被誤判為地緣政治的「以色列…鱷魚可部署監獄周邊」。**刻意不從 `GEO_TRIGGER` 移除「部署」**，移除會誤殺薩德／荷姆茲快艇等真地緣政治，Task 221）→ ①c **南海小型海上摩擦否決** `SCS_SKIRMISH`（Task 229；三重 AND 守門皆成立才 `DROP:scs-skirmish`：南海地區詞〔`南海`；「中南海」＝中共領導層駐地，須先 `replace("中南海","")` 再比對；或 `黃岩島`／`仁愛礁`／`斯卡伯勒` 熱點礁〕＋低烈度摩擦詞〔持棍／水砲／噴水／對峙／驅離／擦撞／登檢／扣船／雷射／傷人…〕＋**未**命中重大升級詞〔開火／交火／飛彈／導彈／封鎖／斷航／戰爭…〕。攔下原在規則③被 `KEEP:china-regime` 收錄的「中國海警南海持棍傷人 菲律賓海軍1人遭打傷」類雜訊；升級為真正衝突者由守門詞救回落回規則③。**金門摩擦屬台海戰區、直接涉台灣安全，刻意不納入**；`對抗`／`巡弋`／`侵襲`／`灰色`／`衝突`／`軍演`／`軍事`／`海警`／`軍艦` 刻意排除，以免誤傷美軍南海部署與具市場訊號的重大軍演。對線上 3501 則語料含南海地區詞者 8 則僅翻轉 1 則、含摩擦詞但非南海者 9 則全不受影響、零反向翻轉）→ ①d **體育賽事否決** `SPORT`（Task 241；44 詞自 `LIFESTYLE` **原樣搬出**〔不增不減，保證混合型 feed delta＝0〕＋5 個零誤中賽事專名〔奧委會／國際足總／開踢／金盃／公開賽〕。**必須在①`FINANCE` 之後**——`球員`／`冠軍`／`奪冠`／`封王`／`電競`／`教練`／`金牌`／`賽事` 在財經媒體大量作為比喻〔0056奪冠、EPS冠軍、好市多霸氣封王、udn「隱形冠軍」專欄 8 則、金融世界盃、ASML 全球員工〕，靠 FINANCE 先判才安全；**必須在②`LIFESTYLE` 之前**——體育詞雖已移出 LIFESTYLE，但體育事件常同時命中**非體育**的 LIFESTYLE 詞（實測「奧運開幕演唱會遭恐怖攻擊」〔演唱會〕、「世界盃球迷粉絲見面會爆炸案」〔粉絲〕，①d 在②後會被判 `DROP:lifestyle`）。豁免採**正向 KEEP**（實測「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」若續行 cascade 仍落 `DROP:non-finance-general`），且**分兩層**：強豁免 `SPORT_TERROR`〔恐攻／恐怖攻擊／恐怖襲擊／恐怖主義／恐怖組織／自殺炸彈／炸彈客／槍擊案／爆炸案／人質／挾持／國際奧會／奧會模式，語意鎖死、單獨命中即保留〕；弱豁免 `SPORT_GEO`〔抵制／杯葛／制裁／爆炸／暗殺／驅逐〕**需 AND** `SPORT_GEO_GUARD`〔外交／主權／代表權／國旗／國歌／多國／聯合國／反恐／邦交／斷交／正名／國安／戰爭／入侵〕——單層豁免經對抗測試 5/5 被「聯盟制裁違規球隊」「球迷抵制球隊」「電競選手人氣爆炸」等誤救、否決集等同失效。**守門刻意不用 `GEO_REGION`／`CHINA`／`POLITY_STRONG`**（體育本質即國際，實測 8 則一般體育新聞 8 則全被誤救）、不用 `政府`／`總統`（後者子字串誤中「總統盃」）。**意外災難不豁免**（使用者 2026-07-27 裁示：看台倒塌若非人為攻擊而是建築物太爛，影響不了國際政治／經濟／股市），故場館事故詞與傷亡數字守門一律不納入。`FINANCE` 閘門**內建於 `traceSport()`** 使兩個入口語意不漂移（純財經來源無規則①，無閘門實測 12 則命中 SPORT 中 8 則誤殺）；`SPORT_ECON_EXEMPT`〔贊助商／加税，各 1 則實證〕補 FINANCE 在體育產業報導的召回缺口，**不動 FINANCE 本身**。刻意排除：運動〔誤中「營運動能」〕／國家隊〔無人機・機器狗國家隊〕／決賽／FIFA／足總／奧會／助攻／終場／稱霸／霸榜等財經比喻詞，及 禁賽／裸恐怖・炸彈・槍擊／中華台北／引爆 等會架空否決集的豁免候選）→ ②**全世界生活/軟文/體育否決** `LIFESTYLE`（**體育詞已於 Task 241 移出至 `SPORT`**；除①外一律濾；**置於中國/地緣/政治/城市之前**，使政治人物名／城市名／中國詞皆不能救回軟文——體育全項〔世足/奧運/職棒/NBA/選手/金牌…〕＋演藝影劇〔明星/演唱會/票房/緋聞…〕＋餐飲旅宿時尚寵物消費開箱促銷。使用者 2026-07-16 收緊：唯一豁免＝影響股市大盤。**Task 240 增列觀光行程／禮儀性活動詞**〔同遊／搭船／宮廟／遶境／路跑／授旗／嘉年華／水樂園／合唱團／出家〕，沿用既有「旅遊／景點／打卡」語意、不新增規則——使用者回報「侯友宜、谷立言、片山和之同遊新北　搭船欣賞淡江大橋」不含任何財經訊號，僅因命中 `TW_WHITELIST_CITY` 的 `新北` 而在規則⑧被 `KEEP:tw-whitelist-city`；置於規則②即可在走到⑧前攔下，**唯一豁免仍是財經〔規則①先判〕——`POLITY_STRONG` 在規則⑤、於本規則之後，強政治不豁免**，故政治人物的純禮儀性行程〔授旗／路跑／水樂園〕一併濾除，符合使用者「影響不了政局發展」判準；而「台積電嘉義二期動土前7座宮廟遶境祈福」仍由規則①`KEEP:finance`。**`參拜` 刻意不收、只收 `宮廟`**：裸 `參拜` 會攔下「日相參拜靖國神社 中國外交部強烈抗議」這類牽動中日關係與市場的事件，而規則③⑤都在其後、無從救回）→ ③**中國**（`CHINA` 命中時，含 `BEIJING_REGIME`〔政權／軍事／兩岸／外交／人權審查／跨境鎮壓／制裁／五年規劃…〕或 `POLITY_STRONG`〔美日台歐盟〕或 `TW_POLITICS_GENERIC` 或 `GEO_REGION`〔與他國雙邊外交〕才留，否則濾中國純社會獵奇）→ ④**影響市場地緣政治**（`GEO_REGION`＋`GEO_TRIGGER` 皆命中）→ ⑤**美日台歐盟強政治** `POLITY_STRONG`（覆蓋地方地理判定）→ ⑥**台灣他縣市地方新聞或地方公職**（`TW_OTHER_COUNTY` 或 `TW_LOCAL_OFFICE`〔鎮長/市議員…〕且無 `TW_WHITELIST_CITY`〔台北／新北／高雄／雙北；刻意不用「北市」避免誤中「竹北市」〕）→濾 → ⑦**台灣政黨／選舉一般政治** `TW_POLITICS_GENERIC`→留 → ⑧台北／新北／高雄地方→留 → ⑨其餘→濾（**白名單模型**）。與 `sanitizeNews` 的「依來源網域封鎖中港澳媒體」為兩件不同的事（前者主題、後者來源網站），並存。關鍵詞集依 **1561 則真實爬取標題＋12 路對抗式稽核（Workflow）校準**，偏向不誤刪財經、其餘從嚴；子字串誤中須警惕（已移除「出國」「北市」等）。純關鍵詞啟發式邊界必有少量誤判、屬刻意取捨。單元測試 `EditorialNewsFilterTest`。
  - **backend 注入（`MarketAnalysisService`）**：`submitBatch` 前撈 `newsRepo.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(now(TW) - newsMaxAgeDays)`；`buildUserPrompt` 在走勢資料後注入「== 近期新聞（本地抓取）==」區塊（`{yyyy-MM-dd} [{source}] {title} — {url}` ＋ summary；`fx`／`us-market`／`kr-market`／`kr-intraday`〔Task 193〕／`ma-cross`〔Task 207〕因 `category != news` 歸入「TWSE 量化資訊」群一律列出〔該群**無筆數上限**，`LOCAL_NEWS_MAX=40` 只約束 `news`〕。韓股採「只餵資料給分析」，無 `krContext` 專屬輸出欄位；**新增 `kr-intraday` 無須 backend 改動**——`fetchRecentLocalNews` 無 category 過濾、`buildLocalNewsBlock` 以 `category != news` 分群，新 category 天然流入）。本地新聞**注入時繞過 `sanitizeNews`**（已保證來源＋真實 `published_at`）；模型輸出 `newsHighlights` 仍照舊 sanitize（含中港澳地區封鎖為防禦縱深）。
  - ~~**web_search 三態語意升級**~~（**Task 179 起改為二態、`web_search` 已移除**）：新聞面只依「本地新聞是否存在」切換——**有**＝注入本地清單、指示只從清單挑 `newsHighlights`；**無**＝純技術面、`newsHighlights` 回空。`submitBatch` 不再依 `web_search_max_uses` 加任何 tool（該欄與 `AVAILABLE_WEB_SEARCHES` 白名單皆移除）。
- **排程執行緒（`SchedulingConfig`，Task 246 bug fix）**：本服務 `spring.threads.virtual.enabled=true`，Boot 預設為 `@Scheduled` 配置以 virtual thread 執行的 `SimpleAsyncTaskScheduler`；實測 `fixedDelay` 的 `pollBatches` 在此組態下**不週期執行**（收尾 poller 靜默、批次卡 `PROCESSING`，重啟仍複現；其餘 7 個 `@Scheduled` 皆 cron、症狀不明顯）。故定義名為 `taskScheduler` 的 `ThreadPoolTaskScheduler` bean（pool=3）使 Boot virtual 排程器 auto-config 退讓（`@ConditionalOnMissingBean(TaskScheduler.class)`），全部 `@Scheduled` 改於平台執行緒固定池執行（一舉解 fixedDelay 不重排、SDK 呼叫在 VT 阻塞、8 個排程共用單執行緒互相餓死三種可能）。加固：`client()` 建 `AnthropicOkHttpClient` 時設 `timeout(Duration.ofSeconds(90))`，避免單次收尾呼叫長時間阻塞排程執行緒。
- **優雅降級**：`ANTHROPIC_API_KEY` 空 → 不建 client、upsert `status=NOT_CONFIGURED`；送出批次例外 → `status=FAILED` + `error_message`；批次結果 errored/expired/解析失敗 → `status=FAILED`（保留 `raw_response` 供除錯）。皆不拋出中斷排程／poller。`OK` 筆才視為有效分析。
- **設定**：`application.yml` 新增 `anthropic.api-key: ${ANTHROPIC_API_KEY:}`、`anthropic.model: ${ANTHROPIC_MODEL:claude-opus-4-8}`；`docker-compose.yml` business-services 透傳 `ANTHROPIC_API_KEY`；`.env.example` 補金鑰取得說明；金鑰不入版控。新聞政策參數：`market-analysis.news-max-age-days`（預設 **5**，Task 149.18 由 30 收斂）、`market-analysis.news-verify-published-date`（預設 true，Task 149.17）、`market-analysis.news-region-block-enabled`（預設 true，Task 149.18 中港澳地區封鎖總開關）；皆可由環境變數覆寫、有預設值故非必設。
- **前端配色**：偏多（BULLISH）紅、偏空（BEARISH）綠、中性（NEUTRAL）灰——遵循台股「漲紅跌綠」慣例（與 Dashboard Task 147 一致）。
- **前端 PROCESSING 狀態**（Batch 非同步）：`today.status==='PROCESSING'` 顯示 info alert「分析中（批次處理中）」，並 `watch` 狀態每 30 秒自動 `load()` 直到改變（`onUnmounted` 清 timer）；手動「重新分析」送出後提示「已送出分析（批次處理中，完成後自動更新）」。歷史表 `statusLabel` 加 `PROCESSING → （分析中）`。

### 模型頁面可調（成本控管）

分析**模型**與**思考深度（effort）**可由管理者在頁面切換（模型：Opus 4.8 / Sonnet 5 / Haiku 4.5；effort：low / medium / high），另有**每日自動分析開關（enabled）**可停用各啟用中寄送時點的自動分析（停用時所有時段皆不觸發，省整筆花費），皆持久化、下次分析生效、免改環境變數或重啟。（**Task 179 起「新聞搜尋次數（web search）」下拉已移除**，新聞固定讀本地 `news_headline`。）

- **資料模型**（Liquibase `v1.38.0-market-analysis-setting.sql` 建表；`v1.39.0` 增 `effort`、`v1.40.0` 增 `web_search_max_uses`、`v1.41.0` 增 `enabled`；**`v1.54.0` `DROP` `web_search_max_uses`（Task 179）**。單列設定表，比照 `backup_setting`）：
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
  -- v1.41.0：每日自動分析開關（成本控管），false=停用 08:45 排程；既有單列以 DEFAULT 補值
  ALTER TABLE market_analysis_setting ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT true;
  -- v1.54.0（Task 179）：新聞改純本地 news_headline、web_search 退場，移除此欄
  ALTER TABLE market_analysis_setting DROP COLUMN web_search_max_uses;
  ```
  Entity `MarketAnalysisSetting`（`@Id Integer id`、`model`、`effort`、`enabled`；**`webSearchMaxUses` 於 Task 179 移除**）＋ `MarketAnalysisSettingRepository`。
- **解析**：`resolveModel()` = 設定表 `model`（非空）→ 否則 `@Value("${anthropic.model:claude-opus-4-8}")` 環境預設；`resolveEffort()` = `effort`（白名單內）→ 否則 `medium`；`isEnabled()` = `enabled` → 否則 `true`。`submitBatch` 每次呼叫前各取一次（故切換即時生效）；effort 以 `OutputConfig.builder().effort(...)`。**新聞來源固定本地 `news_headline`：不再有 `resolveWebSearchMaxUses()`、不再 `addTool(WebSearchTool…)`**；有本地新聞則 prompt 指示據清單列 `newsHighlights`、無則切為「不得杜撰新聞、`newsHighlights` 回空」。`isEnabled()` 則由 **`MarketAnalysisScheduler`** 於**每分鐘 tick 命中啟用寄送時點之後**檢查（未命中即零成本 return、根本不查 `enabled`，見 Requirement 31「關鍵業務邏輯」的排程段落），以及開機 self-heal 開頭檢查：`false` 即 return 略過（不呼叫 LLM），手動 `generate()` 不檢查此旗標。
- **可選白名單／開關**：後端 curated 常數 `AVAILABLE_MODELS`（Opus 4.8／Sonnet 5／Haiku 4.5）、`AVAILABLE_EFFORTS`（`low`／`medium`／`high`）皆為「技術白名單」而非使用者可自訂的業務分類，故**不**套用「Enum 必須入庫由 `/api/settings/*` 管理」規範；`enabled` 為布林開關（非白名單）。`effort` 不列 `xhigh`／`max`（更貴、與省錢目的相反）。（`AVAILABLE_WEB_SEARCHES` 白名單於 Task 179 移除。）`updateSettings(model, effort, enabled)` 對有帶的白名單欄各自驗證（非法 → 400），`enabled` 直接設值，未帶之欄不變；至少須一項；回傳清單時若現值不在白名單則補入（下拉恆含現值）。
  - **成本觀點**：`enabled` 是最粗的槓桿——停用即當天完全不跑、零花費；`effort` 是主要槓桿（thinking 按 output token 計價，Opus $25/1M 最貴，`medium` 較隱含 `high` 省且對方向判斷足夠）。（新聞改讀本地 `news_headline`，已無付費 `web_search` 成本。）
- **API**：
  | Method | Path | 說明 |
  |---|---|---|
  | GET | `/api/market-analysis/settings` | `{ model, effort, enabled, availableModels:[{id,label}], availableEfforts:[{id,label}], sendTimes:[{id,time,active}] }`；已登入者可讀。（`sendTimes` 於 Task 191 併入本回應供頁面聚合，清單來源見 Requirement 31「API 設計」的 `/api/market-analysis/send-times`。`webSearchMaxUses`／`availableWebSearches` 於 Task 179 隨 `web_search` 一併移除。） |
  | PUT | `/api/market-analysis/settings` `{model?, effort?, enabled?}` | 更新模型／思考深度／每日自動分析開關（至少一項；未帶之欄不變）；`CurrentUserContext.isAdmin()` 否則 `AdminRequiredException`；白名單欄驗證。body 型別 `Map<String,Object>`（`enabled` 收 JSON boolean）。 |

  BFF：主 `GET /api/bff/today-market-analysis` 聚合回傳 `{ today, history, settings }`；`PUT /api/bff/today-market-analysis/settings` 為**泛型 Map 轉發**（body 原封轉給 business，故新增 `effort`／`web_search`／`enabled` 欄皆無需改 BFF），bff `SecurityConfig` 對該 `PUT` 限 `AUTHORITY_ADMIN`。
- **前端**：頁首（限管理者）並列「每日自動分析」`el-switch`（v-model 布林）＋兩個 `el-select`——模型（`availableModels`）、思考深度（`availableEfforts`）；各自 `change` → `updateSettings({model})` / `{effort}` / `{enabled}` 持久化，提示訊息，失敗還原；`busy` computed（任一儲存中或分析中）停用全部控制項避免併發覆蓋；`.header-actions` 加 `flex-wrap` 讓開關＋下拉＋按鈕在窄寬度優雅換行；停用時「尚無資料」`el-empty` 說明改為「已停用、由管理者手動產生」；一般使用者不顯示這些控制項。（「新聞搜尋次數」下拉於 Task 179 隨 `web_search` 移除。）卡片 `foot-meta` 的「由 {model}」仍顯示**產生該筆分析所用**的模型（`daily_market_analysis.model`），與「下次要用的設定」語意區分（effort／enabled 屬設定層、不逐筆記錄於 `daily_market_analysis`）。

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

**目標**：使用者先設定理財條件（生日／退休日期／退休前年薪與年支出／退休後現金流與試算假設／理財目標（複選）／可忍受風險／獲利預期），系統結合其**最新 `asset_snapshot`** 的現況配置與持有明細，由 Claude 產出個人化資產配置建議（整體評析／現況風險評估／建議目標配置／具體調整動作／風險提醒／參考來源），並保存歷次供回顧。以 Requirement 31 為藍本，差異：**互動式即時 → request 立即回覆、背景執行緒呼叫 Messages API**（非 Batch）；owner-scoped 查詢與 prompt 組裝在 request 執行緒完成，`@Filter`／`TenantGuard` 正常運作。

- **資料模型**（Liquibase `v1.44.0-portfolio-advice.sql` 三 changeset ＋ `v1.44.1-retirement-date.sql` ＋ `v1.47.0-financial-planning-fields.sql` 生日/勞保勞退/通膨/大筆花費 ＋ `v1.48.0-retirement-projection-fields.sql` 退休試算三欄 ＋ `v1.49.0-drop-investment-horizon.sql` 移除投資年限 ＋ `v1.50.0-long-term-care-stages.sql` 移除 `retirement_monthly_expense`、加長照三欄 ＋ `v1.51.0-pre-retirement-salary.sql` 移除 `monthly_investment`、加退休前年薪/年支出）：
  ```sql
  -- 理財條件（一使用者一列，記住免重填；owner-scoped）
  CREATE TABLE investment_profile (
      id BIGSERIAL PRIMARY KEY, owner_user_id BIGINT NOT NULL,
      -- v1.49.0 移除 investment_horizon_years（改由生日＋退休日衍生累積/守成年數）；v1.51.0 移除 monthly_investment、改退休前年薪−年支出
      pre_retirement_annual_salary  NUMERIC(20,2), -- v1.51.0：退休前年薪（今日幣值/年）
      pre_retirement_annual_expense NUMERIC(20,2), -- v1.51.0：退休前年生活費（今日幣值/年）；退休前每年淨投入＝年薪−年支出
      retirement_date DATE, -- 預計退休日期（v1.47.0 起存整日；退休後薪水停止、淨投入歸零。原 v1.44.1 存該月一號、YearMonth）
      birth_date DATE,      -- v1.47.0：生日（取代 age；年齡由生日衍生，不入庫）
      labor_insurance_monthly NUMERIC(20,2), labor_insurance_start_date DATE,  -- v1.47.0：勞保年金月領＋起領年月
      labor_pension_lump_sum  NUMERIC(20,2), labor_pension_claim_date  DATE,  -- v1.47.0：勞退一次領＋領取年月
      assumed_annual_inflation_rate NUMERIC(5,2),  -- v1.47.0：假設年通膨率%（預設 2，供大筆花費換算）
      -- v1.48.0 曾加 retirement_monthly_expense；v1.50.0 移除、改年支出兩階段（見下三欄）
      retirement_annual_expense NUMERIC(20,2),      -- v1.50.0：長照前年生活費（今日幣值／年；退休後第一階段提領）
      long_term_care_annual_expense NUMERIC(20,2),  -- v1.50.0：長照後年生活費（今日幣值／年，通常較高；null=不分長照階段）
      long_term_care_start_age INTEGER,             -- v1.50.0：長照起始年齡（null 且有填長照後年費時 service 預設 80）
      accumulation_annual_return_rate NUMERIC(5,2), -- v1.48.0：累積期試算用年報酬率%（試算假設，null→依獲利預期帶入）
      retirement_annual_return_rate   NUMERIC(5,2), -- v1.48.0：退休後試算用年報酬率%（試算假設，退休後預設較保守）
      goals VARCHAR(300), risk_tolerance VARCHAR(20), expected_annual_return VARCHAR(20),
      updated_at TIMESTAMPTZ NOT NULL, CONSTRAINT uq_investment_profile_owner UNIQUE (owner_user_id));
  -- v1.47.0：特定日期大筆花費（一使用者多筆；金額為今日幣值，未來名目值由 service 依通膨衍生、不入庫）
  CREATE TABLE investment_planned_expense (
      id BIGSERIAL PRIMARY KEY, owner_user_id BIGINT NOT NULL,
      expense_date DATE NOT NULL, name VARCHAR(100), amount NUMERIC(20,2) NOT NULL,
      updated_at TIMESTAMPTZ NOT NULL);
  CREATE INDEX idx_planned_expense_owner ON investment_planned_expense (owner_user_id, expense_date);
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

- **正規化說明**：`investment_profile` 不存衍生值——**年齡由 `birth_date` 與今天衍生**（不冗存 `age`，v1.47.0 移除 age 欄）；退休日期以 `retirement_date` 存原始整日；累積年數／退休後年數由 service `retirementSpan(birthDate, retirementDate)` 現算、不入庫（退休前每年淨投入＝年薪−年支出僅計入累積期、退休後歸零）；**大筆花費以「今日幣值」`amount` 存，未來名目金額 `amount × (1+r)^年數` 由 service 依 `assumed_annual_inflation_rate` 現算、不入庫**。勞保／勞退金額為使用者填入的**未來實際給付**（不做通膨換算）。`portfolio_advice` 的條件快照（含產生當下由生日衍生的 `age`）與 `based_on_*` 為**刻意 denormalize 的歷史快照**（產生當下的條件與資產依據），符合「歷史交易記錄名稱字串」例外；`result_json` 為 LLM 解析結果的凍結內容（比照 `daily_market_analysis`）。

- **Service `PortfolioAdviceService`**：
  - profile：`getProfile()`／`saveProfile(InvestmentProfileInput)`（`TenantGuard.requireCurrentUserId()` 綁 owner；risk/return/goals 白名單驗證，goals 過濾去重逗號串接）。輸入含生日／退休日（`LocalDate`）／勞保勞退現金流／通膨率／大筆花費清單；大筆花費以「先刪 owner 全部再插入提交清單」replace（清單小、簡單穩健）。年齡不入庫，由生日衍生。
  - 現況：`getCurrentAllocation()` 讀最新快照 `totalDeposit/totalFundValue/totalStockValue/totalAssets` 算占比（存款／基金／股票）。
  - `generate(...)`（**非同步**）：upsert 條件 → 讀最新快照＋子表明細（`bankDeposit/fundHolding/stockHolding` `findBySnapshotId`，皆由 owner-filtered 快照 id 帶出，安全）→ **在 request 執行緒組好 system/user prompt**（條件＋現況＋明細＋**退休後現金流／未來支出段**：勞保月領（自起領年月，照填）、勞退一次領（於領取年月，照填）、大筆花費（每筆今日金額＋依通膨率換算之未來名目值）；`web_search` 開則要求納入當前市場、references 附來源，關則要求不得杜撰、references 回空）→ 落一筆 `PortfolioAdvice`（`status=PROCESSING`＋條件快照＋based_on）並**立即回傳** → 提交背景執行緒 `runGeneration`。`runGeneration`（daemon 固定小池）：依 setting `resolveModel/resolveEffort/resolveWebSearchMaxUses` 呼叫 `client.messages().create(MessageCreateParams...)`（adaptive thinking + `OutputConfig.effort` + 可選 `WebSearchTool20260209.maxUses`）→ 取首個 `{` 至末個 `}` 解析為 `PortfolioAdviceResult` → sanitize references（http(s) 白名單）→ **以 `adviceId` by-id 更新該列** OK／FAILED（全程 try/catch 不拋）。金鑰未設時 request 執行緒直接落 `NOT_CONFIGURED`、不提交背景。`latest()` 對 PROCESSING 逾 10 分鐘（背景中斷／重啟）自癒判 FAILED。
  - 設定：`getSettings()`／`updateSettings(model, effort, webSearchMaxUses)` 白名單驗證（比照 `MarketAnalysisService`）。
  - 免 enum 寫死：`GOAL_OPTIONS`／`RISK_OPTIONS`／`RETURN_OPTIONS`／`AVAILABLE_MODELS`／`AVAILABLE_EFFORTS`／`AVAILABLE_WEB_SEARCHES` 皆服務層白名單常數。
  - **退休現金流試算（Task 165，退休後兩階段 Task 167，`RetirementProjectionService`）**：`project(profile, startAssets, expenses)` 對最新快照資產總額做**決定性逐年試算**（非預測）。每年順序：期初餘額 × (1+當期報酬率) → 累積期加「年薪 − 退休前年生活費」淨投入（皆依通膨逐年膨脹，Task 168）、退休後扣年生活費 ×(1+通膨)^距今年數（**退休後兩階段**：`age < 長照起始年齡` 用 `retirement_annual_expense`＝長照前、否則用 `long_term_care_annual_expense`＝長照後；`phase` 為 `RETIRE`／`CARE`）→ 加勞保年金（自起領年起，每年，**依 CPI 累計±5% 調整**——見 `laborAnnuityMultiplier`：勞保條例 §65-4，自起領年累計通膨達 ±5% 之年才依實際累計漲幅階梯式調升並重設基準，非逐年隨通膨；`LABOR_ANNUITY_CPI_STEP=0.05`；勞退不適用）＋勞退一次領（領取當年）→ 扣當年到期大筆花費（`inflate` 名目值），推到 `END_AGE=100` 或餘額 ≤ 0（缺口）。長照階段僅在有填長照後年生活費（>0）時啟用；長照起始年齡預設 `DEFAULT_LTC_START_AGE=80`、夾在 [退休年齡, 100]。試算報酬率：`accumulation_annual_return_rate`／`retirement_annual_return_rate` 有值用之，否則由 `expected_annual_return` 區間 `returnDefault(band)` 帶入（退休後較保守）；`Assumptions.*FromBand` 標記是否為帶入預設。回 `RetirementProjectionDto`（逐年 `points`、`retirementStartBalance`、`depletionAge`／`lastsToEndAge`／`endBalance`）；缺生日／無快照／有退休日但未填長照前年生活費 → `available=false` ＋ `unavailableReason`。內部以 `double` 運算、輸出四捨五入 `BigDecimal`（元）。`getProjection()` owner-scoped 讀 profile／快照／花費後委派；`GET /api/portfolio-advice/projection` 曝露；BFF 聚合多帶 `projection`。組 prompt 時 `appendProjection()` 把同一份試算摘要餵給 AI（單一真實來源）。**試算輸出為衍生、on-demand，不入庫**（`investment_profile` 只存試算輸入假設）。
  - **建議輸出金額化（Task 165）**：`buildSystemPrompt` 要求 AI 於 `targetAllocation` 估 `currentValue`（分類歸屬金額）、另輸出 `rebalancePlan`（逐標的 `BUY`／`SELL`／`HOLD` ＋ `estimatedAmount`）；`runGeneration` 解析後 `enrich(result, totalAssets)` 以「資產總額 × targetPct」**決定性回填** `targetAmount`／`deltaAmount`（金額算術不交 LLM）。`appendHoldings` 補股票股數／成本／損益、基金代號／成本／損益、存款銀行名稱（`findWithBankBySnapshotId` join fetch 避免 lazy／N+1）。`PortfolioAdviceResult`／`PortfolioAdviceDto` 新增 `rebalancePlan`、`TargetAllocation` 加 `currentValue`／`targetAmount`／`deltaAmount`。

- **非同步（in-process 背景執行緒）而非同步阻塞或 Batch**：單次 Claude 呼叫含 thinking + web_search 常達數十秒（實測 ~90s），**超過 nginx `location /api/` 的 `proxy_read_timeout 60s`**——若同步阻塞，長連線會被 proxy 在 60s 切斷（前端 504、且同步版「跑完才落庫」導致完全無結果、無歷史）。故改：`POST /generate` 立即落 `PROCESSING` 並回、由 business-services **背景執行緒池**（`Executors.newFixedThreadPool(3)`，daemon）跑 Claude、完成 by-id 更新，前端輪詢。與 Requirement 31 差異：R31 用 **Batch API**（每日排程、可等、省 50%）；本功能用 **in-process 背景執行緒**（互動式、要快回饋、單筆即時）。**多租戶正確性關鍵**：owner-scoped 的快照／明細讀取與 prompt 組裝**全在 request 執行緒**（`TenantFilterAspect` 啟用 ownerFilter）；背景執行緒只 Claude 呼叫 + `adviceRepo.findById(adviceId)`（`@Filter` 本就不套 by-id）+ `save`，**不做任何 owner-scoped 查詢**，故不受背景執行緒無 request context、ownerFilter 不啟用之影響（見 `feedback_hibernate_filter_aspect`）。狀態：PROCESSING → OK／FAILED；NOT_CONFIGURED 於 request 執行緒直接落。

- **API**（business `/api/portfolio-advice`）：
  | Method | Path | 說明 |
  |---|---|---|
  | GET | `/latest` | 最新一筆建議（`PortfolioAdviceDto`）；無則 `{status:"NONE"}` |
  | GET | `/history?limit=20` | 近 N 筆（owner-scoped，created_at 降序） |
  | GET | `/profile` | 理財條件（含 goal/risk/return 可選清單） |
  | PUT | `/profile` | 儲存理財條件 |
  | GET | `/current-allocation` | 最新快照現況配置（`CurrentAllocationDto`） |
  | GET | `/projection` | 退休現金流逐年試算（`RetirementProjectionDto`；決定性、非預測） |
  | POST | `/generate` | **非同步**產生建議：body 帶條件（一併儲存 profile）→ 送 `generationExecutor` 背景執行緒池後**立即回傳 `PROCESSING` 列**，前端輪詢收尾（見上方「非同步（in-process 背景執行緒）而非同步阻塞或 Batch」） |
  | GET | `/settings` | 成本設定 + 可選清單 |
  | PUT | `/settings` `{model?, effort?, webSearchMaxUses?}` | 更新成本設定（`isAdmin()` 縱深，白名單驗證） |

- **BFF 一頁一支**：`PortfolioAdviceBffController`（`/api/bff/portfolio-advice`）：`GET` `Mono.zip` 聚合 `{ latest, history, profile, settings, currentAllocation, projection }`（Task 165 多帶退休試算）；`POST /generate`（timeout 180s）、`PUT /profile`、`PUT /settings` 泛型 Map 轉發。WebClient 帶 `X-User-*` 租戶身分，business 端 owner-scoped。`SecurityConfig` 加 `PUT /api/bff/portfolio-advice/settings` 限 `AUTHORITY_ADMIN`；`/generate`／`/profile` 為 per-user → 落 `authenticated()`。

  ```text
  前端 AssetAllocationAdviceView ──▶ GET /api/bff/portfolio-advice ──▶ business /api/portfolio-advice/{latest,history,profile,settings,current-allocation}
  產生建議 ──▶ POST /api/bff/portfolio-advice/generate ──▶ business 落 PROCESSING 即回、背景執行緒呼叫 Claude；前端每 5s 輪詢 GET 聚合至非 PROCESSING
  （管理者）成本設定 ──▶ PUT /api/bff/portfolio-advice/settings（限 ADMIN）──▶ business PUT /api/portfolio-advice/settings
  ```

- **前端**（`views/AssetAllocationAdviceView.vue`）：條件表單（生日／退休日期 `el-date-picker`、退休前年薪／年支出 `el-input-number`、理財目標 `el-select multiple`、風險 `el-radio-group`、獲利預期 `el-select`）＋「儲存條件」「產生建議」；現況配置與建議目標配置以純 CSS bar 呈現（避免 echarts tree-shaking 漏註冊風險）；建議卡片顯示 summary／風險評估／目標配置（比例＋理由，Task 165 加「目前→目標→增減碼金額」）／再平衡操作明細 `rebalancePlan`（紅減碼綠增碼＋估計金額）／調整動作（優先度 tag）／風險提醒／參考來源（`safeUrl` 擋非 http(s)）；免責聲明；歷次建議 `el-table` 可展開回顧當時條件與建議；管理者頁首成本設定（模型／思考深度／web 搜尋 `el-select`，`change` 即持久化）。**退休現金流試算卡（Task 165、退休後兩階段 Task 167）**：白話結論（撐到 100 歲剩餘／缺口年齡）＋假設列＋**ECharts 逐年資產餘額折線圖**（`use([CanvasRenderer, LineChart, Title/Tooltip/Legend/Grid/MarkLine/MarkPointComponent])` per-view 註冊，退休 markLine／長照起始 markLine／缺口 markPoint／0 軸 dashed）；退休試算假設欄（長照前年生活費、長照後年生活費、長照起始年齡、累積期／退休後年報酬率，報酬率留空 placeholder 顯示帶入預設）。現況與目標配置比例仍以純 CSS bar 呈現。`api/index.js` 加 `bffApi.portfolioAdvice`（generate 覆寫 60s timeout；`/generate` 為非同步立即回 `PROCESSING`，故毋須長 timeout，實際等待由 `PROCESSING` 輪詢承擔）。左選單 icon `Compass`。

### 不處理

- 不做定期／排程自動產生（互動式即時，使用者按鈕觸發；不像 Requirement 31 有每日 cron）。
- 不接第三方投顧／下單（純建議，不執行任何交易）。
- 不做多份 profile／情境比較（一使用者一份條件；要比較可各自產生後於歷次建議回顧）。

## Requirement 33：股票與大盤績效比較（Task 169）

「績效比較」頁讓使用者最多選 3 檔自己的股票，並可勾選 5 個大盤指數（TWSE／DJI／SPX／IXIC／SOX），在同一張圖上以「同起點正規化報酬率(%)」疊圖比較。骨架與資料流比照「股市大盤查詢」頁（`GdpTwseView.vue` + `GdpTwseBffController`）。

### 資料模型

- **價格報酬（原始版）零新增**，重用：
  - `stock_price_history`（個股每日收盤，欄位 `stockCode/market/tradingDate/closePrice`；全域非隔離表）。
  - `twse_index_daily_history`（台股大盤，PK `tradingDate`，欄位 `closePoint`）。
  - `us_index_daily_history`（海外指數，複合 PK `(indexCode, tradingDate)`，`indexCode ∈ {DJI,SPX,IXIC,SOX,...}`，欄位 `closePoint`）。
  - `stock_dividend_history`（既有股利事件表，`stockCode/market/year/cashDividend/stockDividend/exDividendDate/previousClose/...`；全域非隔離）。
- **含息（total return）新增（Task 170，Liquibase `v1.52.0`）**：
  - `twse_index_daily_history` 新增 nullable 欄位 `close_point_tr`（發行量加權股價報酬指數收盤）。與同日 `close_point`（價格指數）同列共存——兩者為「同一交易日、不同指數」的獨立量測事實（非由彼此計算得出的衍生值），由 TWSE poller 同次抓寫，屬刻意 co-location（比照 open/close 同列）。
  - `us_index_daily_history` **不改結構**，以新增 `index_code='SP500TR'` 資料列承載 S&P 500 Total Return（Yahoo `^SP500TR`），沿用既有實體／refresh／`/api/us-daily-index` 端點。
- 「我的股票清單」不落任何新資料，即時由既有 owner-scoped 表衍生：`asset_snapshot`→`stock_holding`（持股）∪ `stock_alert`（觀察清單衍生），股名由 `stock` 主檔補。

### API 設計

**business-services**（只負責 owner-scoped 清單，不算報酬率）

| Method | Path | 說明 |
| --- | --- | --- |
| GET | `/api/performance-comparison/my-stocks` | 回該登入使用者的股票清單 `List<{code, market, name}>`（持股 ∪ 觀察，去重 `(code,market)`，排除 `0000/台股`，過濾無 price history 者，股名由 `stock` 主檔補，穩定排序）。 |

- 新增 `AssetSnapshotRepository.findDistinctOwnedStocks()`：JPQL `SELECT DISTINCT sh.stockCode, sh.market FROM AssetSnapshot s JOIN s.stocks sh`——**查詢 root 是帶 `@Filter(ownerFilter)` 的 `AssetSnapshot`，owner 隔離自動生效**；不可直查無 `@Filter` 的 `StockHolding`（會跨租戶洩漏）。
- 觀察清單重用 `StockAlertRepository.findDistinctStockCodeMarket()`（root=`StockAlert`@Filter，比照 `WatchStockService.findAll()`）。
- 過濾用 `StockPriceHistoryRepository.countByStockCodeAndMarket(code, market) > 0`。

**BFF（一頁一支聚合器 `PerformanceComparisonBffController`，`/api/bff/performance-comparison`）**

| Method | Path | 說明 |
| --- | --- | --- |
| GET | `/my-stocks` | 代理 business `/api/performance-comparison/my-stocks`（WebClient 自動帶 `X-User-Id` → owner 生效）。 |
| GET | `/compare?stocks=&benchmarks=&range=&dividend=` | 回 `{ dates:[union 交易日], series:[{key,type,code,market,returns[],totalReturn,asOfDate,priceOnly}] }`。 |

- `stocks`＝逗號分隔 `code:market`（`split(",")`→trim→去空→distinct→`limit(3)`；各項 `split(":",2)`，code pattern 不含 `:`、market 中文也不含 `:`，安全）。
- `benchmarks`＝逗號分隔代碼（trim→**白名單過濾 `{TWSE,DJI,SPX,IXIC,SOX}`**→`limit(5)`）。
- `range ∈ {1m,3m,6m,1y,2y,5y,10y}`（`1m/3m/6m`→`minusMonths`、其餘→`minusYears`，預設 `1y`）。
- `dividend`＝`true|false`（預設 `true`＝含息）。`true` 時股票走股利再投入、指數改讀報酬指數（見「含息演算法」）；無法含息者以價格報酬降級並回 `priceOnly:true`。`false` 時全部走原始價格／價格指數（`priceOnly` 一律 false）。
- 每個 series 多回 `priceOnly`（boolean）：含息模式下該標的實際以價格報酬呈現（DJI/IXIC/SOX 無報酬指數、或台股/美股個股查無股利資料），供前端標「價格報酬」。英股累積型 ETF 以原始價視為已含息，`priceOnly:false`。**已查證累積型台股 ETF**（BFF `ACCUMULATING_TW_ETFS` 白名單：`00646`/`006205`/`00642`(00642U)/`00865B`）雖含息模式查無股利，但收益累積於淨值故價=含息，同樣 `priceOnly:false`（不標「價格報酬」）；FinMind 兩表＋Yahoo 已確認四檔本就不配息，無股利可補、不捏造入庫。
- 全空選取直接回 `{dates:[],series:[]}`，不打下游。
- 前端 join、後端 split：避開 axios 陣列序列化成 `stocks[]=` 讓 Spring `@RequestParam String` 收不到的坑。

```text
前端 PerformanceComparisonView
  ├─ GET /api/bff/performance-comparison/my-stocks ─▶ business /api/performance-comparison/my-stocks（owner-scoped）
  └─ GET /api/bff/performance-comparison/compare?dividend= ─▶ 並行 business /api/market-data/history/stock（closePrice）
                                                            + /api/market-data/dividends-readonly（含息時：股票除息事件）
                                                            + /api/twse-daily-index（TWSE，closePoint／含息 closePointTr）
                                                            + /api/us-daily-index?code=（其餘；SPX 含息改 code=SP500TR）
                                                   ──▶ BFF union 軸 + forward-fill +（含息時股利再投入）+ 正規化(%)，回 render-ready（含 priceOnly 旗標）
```

### 關鍵業務邏輯

- **報酬率正規化（BFF）**：`from = today.minus(range)`、`to = today`。各標的抓 `{date, close}`（股票 `closePrice`／指數 `closePoint`）；建立所有標的區間內交易日的 union 排序軸（ISO 日期字串字典序＝時間序，比照 `GdpTwseBffController.previousCloseBefore`）；各 series `base = 區間內第一筆非空 close`，某日值 `= (該日或之前最近一筆 close / base − 1) × 100`（forward-fill），首資料日前留 null；`totalReturn = 最後一個非空報酬`、`asOfDate = 該最後非空日`。`base == null || base.signum()==0` → 整條 null（除零防呆）。
- **reactive fan-out（動態 N 標的）**：`Flux.fromIterable(targets).flatMap(t -> fetchCloses(t).timeout(8s).onErrorReturn(empty)...).collectList()`，再依輸入索引重排（flatMap 亂序），確保 series 順序穩定；單一標的失敗只讓該線消失。
- **不呼叫 `/api/stock-alerts/lookup-name`**：該端點對未知 code 會打外部 API 並 upsert 寫 `stock` 主檔，屬寫副作用，不放進 GET 聚合；label 由前端用 my-stocks 清單（股票）＋固定中文常數（基準）自行組合。
- **compare 不做 owner 過濾**：`stock_price_history` 與指數表為全域公開行情（非個資），任意 code 讀取不洩漏任何人持倉；下拉限「我的股票」僅為 UX。

### 含息（total return）演算法與資料管線（Task 170）

**個股含息（BFF 計算，股利再投入還原）**

- BFF 對 `type=stock` 標的，除既有 `/api/market-data/history/stock` 收盤序列外，並行加抓 **`GET /api/market-data/dividends-readonly?code=&market=&years=`**（純讀變體，見下），取 `{exDividendDate, cashDividend, stockDividend}` 逐筆除息事件。
- 演算法（等效 total-return index）：以除息日排序，維持每股份數 `shares`（起始 1）；每逢區間內除息日 `d`（`exDividendDate` 非 null 且 ≤ 今日，落在或 forward 對齊到交易日軸）：
  - 台股配股：`shares *= (1 + stockDividend / 10)`（面額 10 元換算股數乘數；美股 `stockDividend=0`）。
  - 現金再投入：`shares += shares * cashDividend / close(d)`（以除息日收盤價再投入）。
  - 合併：`shares *= (1 + stockDividend/10) + cashDividend/close(d)`。
  - `close(d)` 取該股在 `d`（或之前最近交易日）之原始收盤。
- 含息值序列 `tr(t) = shares(t) * close(t)`（`shares(t)` 為到 `t` 為止累積份數，階梯狀）；再套既有 `pct(tr(t), tr(base))`＝`(tr/base − 1)×100`，正規化到區間起點 0%。`base` 為 tr 序列第一筆非空。
- **降級**：英股（無股利來源，且無法辨別累積/配息型）→ 用原始 close 序列、`priceOnly=true`（不臆測為已含息，避免配息型 `VUSA.L` 被低估誤標）；台股/美股查無任何股利列 → `shares≡1`＝價格報酬、`priceOnly=true`。`exDividendDate=null` 的年度彙總列、除息日 > 今日者略過。

**指數含息**

- 含息模式下 `fetchCloses` 對指數的取數改為：
  - `TWSE` → **只取** `twse_index_daily_history.close_point_tr`（`/api/twse-daily-index` 的 `closePointTr` 欄）；null 列略過、由前值 forward-fill（floorEntry），**禁止退回 `closePoint`**（價格/報酬指數量級差 ~1.6 倍，混填會在回補前緣與缺口造成台階跳空）。TR 覆蓋率（有值列 ÷ 交易日數）< 90% → 整段退回價格指數並標 `priceOnly=true`；否則 `priceOnly=false`。
  - `SPX` → 改抓 `/api/us-daily-index?code=SP500TR` 的 `closePoint`，`priceOnly=false`。
  - `DJI/IXIC/SOX` → 仍讀價格指數 `closePoint`，`priceOnly=true`。
- 純價格模式：全部讀 `closePoint`（現行行為），`priceOnly=false`。

**資料管線（business 抓寫，一次性回補＋每日增量）**

- **TWSE 報酬指數**：ext-materials `MacroDataFetchClient.fetchTwseReturnIndexDaily(date)` 打 RWD `https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=YYYYMMDD&type=IND&response=json`，於 `fields[0]=="報酬指數"` 的表中找 `發行量加權股價報酬指數` 列取「收盤指數」（千分位字串→`BigDecimal`）。經 business `/internal/macro/twse-return-index?date=` proxy；`MacroHistoryService.refreshTwseReturnIndex(years)` **背景執行緒**逐一對既有 `twse_index_daily_history` 交易日回補 `close_point_tr`（350ms rate-limit、逐筆 `findById`＋`save` 只動該欄、resumable；`AtomicBoolean` 重入防護，連點回 `{started:false,reason:"in-progress"}`）。寫入前經 `isPlausibleTr`（報酬指數/價格指數比值須落在 [1.3, 3.5]，歷史約 1.5–2.1）過濾——TWSE 單次 transient 異常值（實測 2021-12-23 曾回 104241／比值 5.8）拒存、留 null 待重試，避免整條含息線爆單日尖刺。每日增量 `fillRecentTwseReturnIndexGaps(14)`（排程 07:00 呼叫）補近 14 交易日仍為 null 者——**不可只補 `now()`**（07:00 時今日列尚未由 poller 寫入，findById 必落空、增量無效）。價格指數回補 `refreshTwseDaily` 寫入前先 `findById` 保留既有 `close_point_tr`，避免整列 merge 覆寫成 null。
- **SP500TR**：`MacroDataFetchClient` 的 `US_INDEX_YAHOO` 加映射 `SP500TR → ^SP500TR`；`refreshUsIndexDaily("SP500TR")` 沿用既有 Yahoo `range=10y&interval=1d` 一次抓，upsert 至 `us_index_daily_history(index_code='SP500TR')`。`IndexDailyRefreshScheduler` 的每日回補名單加入 `SP500TR`。
- **`/api/market-data/dividends-readonly`**（新，business）：純讀 `stock_dividend_history`（`StockDividendHistoryRepository.findByStockSinceYear`），**不觸發 cold-cache `/internal/dividend/sync` 寫副作用**，供本頁 BFF 的 GET 聚合使用（既有 `/api/market-data/dividends` 為 `@Transactional`＋冷快取寫，違反「BFF GET 聚合不放寫副作用」原則，故另立純讀端點）。

### 前端（`views/PerformanceComparisonView.vue`）

- 控制列：股票 `el-select multiple filterable :multiple-limit="3"`（options 來自 my-stocks，`value="code:market"`）、基準 `el-checkbox-button` ×5、區間 `el-radio-group`（預設 1y）、**報酬口徑 `el-radio-group`（`含息報酬`／`純價格報酬`，預設含息）**。含息選擇連動 `bffApi...compare(keys, codes, range, dividend)`，watch 一併監看。
- 含息模式：卡片標題／說明改「含息報酬（股利再投入）」；`priceOnly:true` 的 series 於圖例／摘要表標「價格報酬」小標籤（`el-tag` info），提示口徑差異。
- 單一 `<v-chart>`：y 軸「報酬率(%)」，各標的 `type:'line' + connectNulls:true`，`markLine` 一條 0% 基準水平線；下方報酬率摘要表（標的｜期間報酬率｜截至日，紅漲綠跌）。
- **ECharts tree-shaking 註冊**：`use([CanvasRenderer, LineChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, DataZoomComponent, MarkLineComponent])`——0% 線用 **`MarkLineComponent`**（非 GdpTwseView 的 `MarkPointComponent`），漏註冊會靜默不畫。
- `api/index.js` 加 `bffApi.performanceComparison`（`myStocks`／`compare`）；`router` 加 `/performance-comparison`、`App.vue` `mainMenuItems` 加「績效比較」（icon `Histogram`），置於「股市大盤查詢」之後。

### 不處理

- 不支援「當日」分時比較（僅日線區間）。
- 不在 compare 呼叫 lookup-name（避免外部 API 副作用與寫主檔）。
- **含息不處理**：DJI/IXIC/SOX 免費來源無報酬指數 → 含息時降級為價格報酬（標示，不另尋付費源）；個股原始價之股票分割（split）不還原（沿用既有價格序列既有行為，含息與純價格同樣受影響，屬既有限制）；股利再投入以除息日收盤價近似（非實際發放日），為 total-return index 標準作法。
- compare 不對股價／指數做 owner 過濾（全域公開行情）。

## Requirement 34（Task 171）：歷年資產 Excel 匯出增強與每日排程自動匯出

「歷年資產」頁既有「匯出 Excel」按鈕（`/api/bff/asset-history/export` → `/api/snapshots/export` → `ExcelExportService.exportFull()`）增強為「完整匯出 ＋ 第一張『當前彙總』總表」；並新增每個使用者可各自設定的每日排程，於指定時間把完整匯出寫檔到指定目錄（容器基底目錄 ＋ 使用者相對子路徑，經 docker volume 對映到 host）。

### 架構與資料流

```
[歷年資產頁 AssetHistoryView.vue]
  ├─ 手動匯出鈕 → bffApi.assetHistory.exportExcel() (blob)
  │     → BFF GET /api/bff/asset-history/export → business GET /api/snapshots/export
  │       → ExcelExportService.exportFull()  (HTTP：TenantFilterAspect 自動 owner-scoped)
  └─ 排程設定卡 → bffApi.assetHistory.{getExportSchedule,updateExportSchedule,runExportNow}
        → BFF GET/PUT /api/bff/asset-history/export-schedule、POST .../run-now
          → business GET/PUT /api/export-schedule/settings、POST /api/export-schedule/run-now
            → ExportScheduleService（owner-scoped：HTTP 帶 X-User-* → ownerFilter）

[背景排程] business ExportScheduleService
  @Scheduled(cron="0 * * * * *", zone=Asia/Taipei)  每分鐘 poll
    for each export_schedule_setting（背景無 request → 讀全部列）:
      if enabled && last_run_date != today && now >= (run_hour:run_minute):  // >= 到點，非分鐘精確相等
        byte[] = ExcelExportService.exportLiveAssetsForOwner(ownerUserId)  // 當前即時資產；手動 enableFilter 縮到該 owner
        Files.write( resolveDir(EXPORT_OUTPUT_DIR=/home/steven, output_subpath) / 資產總覽_{ownerUserId}_YYYYMMDD.xlsx )
        update last_run_date/last_run_at/last_run_status
  @EventListener(ApplicationReadyEvent) 開機自癒：補跑「今日已到點但 last_run_date != today」者
```

### 資料模型

`export_schedule_setting`（Liquibase `v1.53.0-export-schedule-setting.sql`；每 owner 一列、`@Filter(ownerFilter)` 隔離）：

```
id              BIGSERIAL PK
owner_user_id   BIGINT      NOT NULL UNIQUE   -- 每使用者一列；@Filter(ownerFilter)
enabled         BOOLEAN     NOT NULL DEFAULT FALSE
run_hour        INT         NOT NULL DEFAULT 8    -- 0..23
run_minute      INT         NOT NULL DEFAULT 0    -- 0..59
output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input'  -- 相對基底目錄的子路徑
last_run_date   DATE                            -- 當日 guard（成功或失敗都設，避免每分鐘重試）
last_run_at     TIMESTAMP
last_run_status VARCHAR(500)                    -- 「成功：/path」或「失敗：訊息」
updated_at      TIMESTAMP
```

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

- `output_subpath` 只存相對子路徑；實際寫入目錄 = `EXPORT_OUTPUT_DIR`(容器內基底) resolve 子路徑。
- 背景 cron 無 request context → `ownerFilter` 不自動生效，`ExportScheduleSettingRepository.findAll()` 讀全部列（跨所有 owner）即為所需；產檔時才對「該列 owner」手動 `enableFilter`。

`crawler_schedule`（Liquibase `v1.58.0-crawler-schedule.sql`；公開資訊爬蟲執行時間設定，Requirement 38）—— **一列一時間點**（不是每 owner 一列，全域設定無 `owner_user_id`；`NewsPoller` 每分鐘讀取比對）：

```
id            BIGSERIAL PK
crawler_key   VARCHAR(64)  NOT NULL          -- 目前僅 'news-poller'（保留擴充其他爬蟲）
run_hour      INT          NOT NULL          -- 0..23（CHECK）
run_minute    INT          NOT NULL          -- 0..59（CHECK）
enabled       BOOLEAN      NOT NULL DEFAULT TRUE
updated_at    TIMESTAMP
UNIQUE (crawler_key, run_hour, run_minute)   -- 同爬蟲同時間點不重覆
```

- Seed 預設 `('news-poller',8,20)`／`('news-poller',11,30)`／`('news-poller',18,0)`，等同改為 DB 驅動前寫死的三個 cron（Task 184／188）；全新部署行為不變。
- **changeset 刻意冪等**（`CREATE TABLE IF NOT EXISTS`＋`INSERT ... ON CONFLICT DO NOTHING`＋清舊 seed 的 `DELETE`）：本功能開發期間曾以 changeset id `v1.55.0-crawler-schedule` 在既有開發 DB 建過同一張表並 seed 舊時點 `08:00/12:00/18:00`；為避讓 main 已佔用的 `v1.55.0`，該檔改名為 `v1.58.0-crawler-schedule`，**changeset id 隨檔名改變 → Liquibase 視為新 changeset 會重跑**，遇既有表即 `relation already exists` 而中止啟動。故建表容忍已存在、seed 走 `ON CONFLICT DO NOTHING`，並以 `DELETE` 把殘留的舊 seed（08:00／12:00）對齊為現行時點；`DELETE` 只命中與舊 seed 完全相同的列，不動使用者自行新增的時間點，於全新資料庫為 no-op。
- 設定變更走「整批覆寫」（`PUT` 先 `deleteByCrawlerKey` 再 batch insert），非逐列 CRUD；ext `NewsPoller` 每分鐘讀已啟用列，DB 讀取例外時 fallback 至 08/12/18。

`crawler_export_setting`（Liquibase `v1.64.0-crawler-export-path.sql`；公開資訊爬蟲輸出檔案路徑設定，Requirement 38 / Task 212）—— **一爬蟲一列**（與 `crawler_schedule` 的「一列一時間點」不同；同為全域設定無 `owner_user_id`）：

```
id                BIGSERIAL PK
crawler_key       VARCHAR(64)  NOT NULL UNIQUE   -- 目前僅 'news-poller'
output_subpath    VARCHAR(512) NOT NULL          -- 本機相對子路徑（相對容器基底 EXPORT_OUTPUT_DIR）
gdrive_enabled    BOOLEAN      NOT NULL DEFAULT false  -- 是否額外上傳一份到 Google Drive（Requirement 50 / Task 245）
gdrive_subpath    VARCHAR(512)                   -- Drive 上相對子路徑（相對 rclone remote 根），gdrive_enabled 時必填
gdrive_last_run_at  TIMESTAMP                    -- 上次 Drive 上傳時間（供設定頁顯示，上傳目的地不在使用者眼前）
gdrive_last_status  VARCHAR(512)                 -- 上次上傳結果：成功記落點與大小、失敗記錯誤摘要
updated_at        TIMESTAMP
```

- **為何不併進 `crawler_schedule`**：該表語意是「一列一執行時間點」，輸出路徑是「每爬蟲一個值」；併入會讓路徑隨時間點列數重複儲存同一事實（CLAUDE.md 資料庫完整正規化），且刪一個時間點就會連帶弄丟路徑。故另立一表、以 `crawler_key` 關聯。
- **只存相對子路徑、不存絕對路徑**：絕對路徑等於把容器內檔案系統位置寫進 DB，跨環境不可攜且繞過基底防護；沿用 Requirement 34／39／41／42 既有模型。實際寫入 = `EXPORT_OUTPUT_DIR` resolve 之，`business`（PUT 時）與 `ext`（寫檔前）各驗一次跳脫。Drive 端同構：`gdrive_subpath` 亦只存相對子路徑，基底為 rclone remote（名稱由 `GDRIVE_OUTPUT_REMOTE` 給定，預設 `GDriveOutput`，**不寫死於程式**）。
- Seed `('news-poller','Project/SRPP/data/input')` ＝ 改為 DB 驅動前 `/srpp-input` volume 的同一個 host 目錄（`/Users/steven/Project/SRPP/data/input`），行為不變。changeset 比照本專案慣例寫成冪等（`CREATE TABLE IF NOT EXISTS`＋`ON CONFLICT DO NOTHING`）。
- **Google Drive 為「附加」而非「替換」（Requirement 50 / Task 245，changeset `v1.75.0-crawler-gdrive-output.sql`）**：`gdrive_*` 欄位新增後，本機 `output_subpath` 的寫入行為**完全不變、一律照寫**；`gdrive_enabled` 為真時才在本機檔寫成功後多上傳一份副本。刻意不做成「儲存目標」單選——**SRPP 退休規劃專案依賴本機 `Project/SRPP/data/input/public_info_<日期>.json`**，單選會讓使用者選了 Drive 就靜默切斷 SRPP 的資料來源。新欄位 seed 不啟用（`gdrive_enabled=false`），既有部署升級後行為與現況一致，且不要求 rclone remote 存在。
- **`gdrive_enabled` 為布林而非分類主檔表**：CLAUDE.md「禁止 Enum 寫死」針對的是使用者會自行增修的**業務分類**（銀行、券商、存款類型、市場類型）；「要不要多上傳一份到 Drive」對應程式中一條具體的 rclone code path，DB 多一列並不會讓程式自動支援新的儲存後端，建主檔表只是假的擴充性。

爬蟲資訊查詢頁 API 端點（Requirement 38）：

```
# business-services（新）
GET  /api/news-headlines?date=YYYY-MM-DD&dateField=fetched|published&category=   # 查該日 news_headline（越新在前），dateField 缺省 fetched
GET  /api/crawler-schedule?crawler=news-poller                                   # 取 NewsPoller 執行時間清單 [{hour,minute,enabled}]
PUT  /api/crawler-schedule?crawler=news-poller                                   # 整批覆寫清單（限 ADMIN；驗 0..23/0..59、去重）
GET  /api/crawler-export-path?crawler=news-poller                                # 取輸出路徑設定 {crawlerKey,outputSubpath,baseDir,absolutePath,updatedAt,
                                                                                 #   gdriveEnabled,gdriveSubpath,gdriveRemote,gdriveLastRunAt,gdriveLastStatus}
PUT  /api/crawler-export-path?crawler=news-poller                                # 更新輸出子路徑＋Drive 設定（限 ADMIN；驗跳脫 → 400）

# BFF（CrawlerDataBffController，WebClient 帶 X-User-*）
GET  /api/bff/crawler-data?date=&dateField=&category=   → GET /api/news-headlines
GET  /api/bff/crawler-data/schedule                     → GET /api/crawler-schedule?crawler=news-poller
PUT  /api/bff/crawler-data/schedule                     → PUT /api/crawler-schedule?crawler=news-poller（限 ADMIN）
GET  /api/bff/crawler-data/export-path                  → GET /api/crawler-export-path?crawler=news-poller
PUT  /api/bff/crawler-data/export-path                  → PUT /api/crawler-export-path?crawler=news-poller（限 ADMIN）
GET  /api/bff/crawler-data/export-path/browse?subpath=  → GET /api/export-schedule/browse（沿用既有目錄列舉，不新增實作）
GET  /api/bff/crawler-data/export-path/browse-gdrive?subpath=
                                                        → GET /api/export-schedule/browse-gdrive（Requirement 50 / Task 245；Drive 資料夾樹懶載入）
```

**Google Drive 輸出（Requirement 50 / Task 245）** —— 本機輸出不變、Drive 為附加副本：

```
[ext: NewsPoller 每輪]
   ├─ 1. upsert news_headline（不變）
   ├─ 2. 寫本機 public_info_<date>.json（tmp + ATOMIC_MOVE，不變）── SRPP 讀這一份
   └─ 3. if gdrive_enabled: ProcessBuilder → rclone copyto <本機檔> <remote>:<gdrive_subpath>/public_info_<date>.json
         └─ best-effort：失敗只記 ERROR log + gdrive_last_status，不 rollback 本機檔、不中斷入庫、不擲例外
            不設 retry queue（每輪重新產檔重新上傳＝天然重試，Drive 覆寫同名檔冪等）；rclone 呼叫設逾時上限
```

- **專用 remote，與 DB 備份完全分離**：備份用的 `gdrive-crypt:`（＝`GoogleDriver:asset-management-backup` 的 crypt 層）檔名與內容皆加密、使用者無法在 Drive 網頁閱讀，且 `GoogleDriver:` 的 OAuth `scope = drive.file`——該 scope 下 rclone **只看得到自己建立的檔案**，列不出使用者手動建立的目錄，故無法用於本需求。本功能改用獨立 remote（`scope = drive`、未加密），**由使用者本人執行一次 `rclone config create GDriveOutput drive scope=drive` 授權建立，程式不建立 remote、不持有 client secret**。該 section 與備份用的兩個 section 共存於同一份 `~/.config/rclone/rclone.conf`（見下方單一 config 檔的取捨說明）。刻意不改既有 remote 的 scope：重新授權失敗會連帶弄壞正在運作的備份／還原（災難復原的最後一道防線）。
- **單一 config 檔，兩個容器共用（使用者明示的決定，含已知代價）**。`~/.config/rclone/rclone.conf` 同時含三個 section：`[GoogleDriver]`（`drive.file`，備份底層）／`[gdrive-crypt]`（crypt 層，含解密密碼）／`[GDriveOutput]`（`scope=drive`，輸出用），**同一份唯讀掛入 business 與 ext**：

  | 用途 | 唯讀掛入 | 可寫副本 | 使用者 | ext | business |
  |---|---|---|---|---|---|
  | DB 備份／還原 | `/etc/rclone/rclone.conf` | `/tmp/rclone.conf` | `BackupService` | 掛（但不使用） | 掛 |
  | Drive 輸出／目錄列舉 | `/etc/rclone/rclone.conf` | `/tmp/rclone-output.conf` | `RcloneClient`／`ProcessGdriveUploader` | 掛 | 掛 |

  **代價**：`external-materials-service`（全 stack 唯一對外打第三方者：TWSE／NASDAQ／FinMind／新聞爬蟲，攻擊面最大）也讀得到備份的 OAuth refresh token 與 crypt 解密密碼——即具備**解密整庫財務備份**的能力。原設計為分離兩份以避免此擴權；改為共用是為省下第二份檔案的維護與搬機成本，且實測兩個 remote 為**同一個 Google 帳號**（`rclone about` 的 Total／Used 一致），分離的實際收益本就有限。**若要復原隔離**：host 上仍保留只含 `[GDriveOutput]` 的 `~/.config/rclone/rclone-gdrive-output.conf`，把 compose 兩處掛載與 `RCLONE_CONFIG` 改回 `/etc/rclone/rclone-output.conf`、兩支 client 的 `CONFIG_SOURCE` 一併改回即可（**Task 247 後掛載粒度已是目錄，復原時要一併改回單檔掛載**——但那會重新引入下方所述的 dangling inode）。
- **兩份可寫副本刻意分開**（`/tmp/rclone.conf` vs `/tmp/rclone-output.conf`）：來源同一份，但各自續期自己的 access token、互不覆寫。
- **掛目錄而非單檔（Requirement 52 / Task 247）**：compose 兩處掛的是 `${HOME}/.config/rclone:/etc/rclone:ro`（目錄），**不是** `…/rclone.conf:/etc/rclone/rclone.conf:ro`（單檔）。`RCLONE_CONFIG` 與兩支 client 的 `CONFIG_SOURCE` 仍為 `/etc/rclone/rclone.conf`、值不變。原因：`rclone config` 寫設定是「寫新檔＋rename」原子替換，**單檔掛載下替換後容器內舊 inode 的 link count 歸零**——2026-07-28 實測兩容器皆 `stat` 看得到（`links=0`）而 `cat` 回 `ENOENT`，使得「使用者去 host 重新授權」對執行中的容器完全無效。**這是常態而非例外**：rclone 每次續期 OAuth token 都會重寫 config，實測當天 15:30／15:53／16:02 三次原子替換，其中 16:02 那次發生在容器 recreate 後 1 分鐘內、掛載當場又 dangling。掛目錄後該路徑每次經目錄查找解析，新檔即時可見。**限縮**：消除的是「單檔替換」造成的 dangling；若 `~/.config/rclone` **目錄本身**被替換（`mv` 後重建、還原備份、換機搬設定），掛載仍指向舊目錄 inode，同一失效模式往上搬一層，屆時仍須 recreate。副作用：該目錄下其他檔（`rclone-gdrive-output.conf`、`rclone.conf.bak-before-merge`）一併唯讀進入容器；不擴大權限面，因為單一 config 檔的共用取捨已使兩容器都讀得到同一組憑證。
- **掛目錄不等於免重啟**：兩支 client 都在啟動時複製一份到 `/tmp` 後**執行期不再重讀來源**，故 host 重新授權後仍須 recreate 容器才生效。**刻意不做 config 熱重載**——理由是範圍控制（需要 watch／輪詢與並發保護，收益只有省一次 recreate）；**不要以「重載會覆蓋較新的 token」為理由**，那個理由撐不住：token 健康時 rclone 會自己再 refresh、只多一次網路往返，token 缺 `refresh_token` 時從來源重載反而正是想要的行為。改變的只是失效模式：從「`ls` 看得到卻讀不到」的怪症狀，變成「用的是舊 token」這種自檢會直接指名的狀況。驗證掛載時仍不可只用 `ls`，要實際讀取（`head -c 1 <file> >/dev/null && grep -o '^\[.*\]' <file>`）。
- **可用性自檢三層 ＋ 兩個時機（Requirement 52 / Task 247）**：**全部只寫 WARN log、不阻止啟動、不阻止設定儲存、不影響 `configReady` 與任何既有判定**。三層各自對應一種 2026-07-28 實際發生過的失敗，缺一層就會漏掉其中一種：

  | 層 | 檢查 | 擋掉的失敗 | 為何不能省 |
  |---|---|---|---|
  | L1 | 來源 config **實際讀得到內容** | dangling inode | `Files.exists()` 走 stat，dangling 下仍回 true；只有實際讀取才 `ENOENT` |
  | L2 | `[<remote>]` 的 token JSON **含非空 `refresh_token`** | token 無法自動續期 | access_token 未過期的那一小時內，L3 必然通過、看起來完全正常 |
  | L3 | 實跑 `rclone lsd <remote>:` | Drive API 未啟用（403）、remote 名稱錯、授權已撤銷 | 只有真的連線才知道 |

  **兩個時機，只做啟動時等於防不到本次事故**：（a）服務啟動時；（b）**使用者把 `gdrive_enabled` 由 false 改為 true 的那一次設定儲存**。實測時序證明只做 (a) 沒有用——R50／R51 部署 recreate 當下九張表全為 false（`NOT NULL DEFAULT false`、seed 不啟用），使用者是在容器已在跑之後才從 UI 打開開關的（`updated_at` 實測：`crawler_export_setting` 07-27 22:33、`export_schedule_setting` 07-28 12:09、`trading_radar_export_setting` 12:56、`exchange_rate_export_schedule` 12:58，全部晚於 21:31 的上線 recreate），「啟用」到「下次啟動」之間的空窗正是事故發生的整段時間。

  **(b) 需要兩個掛載點，不是一個**：八個匯出頁的儲存共用 `GdriveOutputSupport.resolveUpdate`（全樹該方法的呼叫點恰為八支匯出 service），但**爬蟲頁不走它**——`CrawlerExportPathService.update()` 雖注入同一個元件，卻只用其零件（`normalizeSubpath` `:86`／`validateSubpath` `:88`／`remoteName` `:138`）自行合成。而爬蟲頁正是九列中最早被打開的一列（`updated_at` 07-27 22:33），漏掉它等於漏掉最該被攔下的那一次。

  **(b) 只做 L1 ＋ L2，不做 L3**：這兩層純本地（毫秒級、不打網路）。**L3 刻意排除**，三個理由：(i) L3 逾時 20 秒，同步做會讓使用者按下儲存後乾等最長 20 秒，而「探測慢」恰恰等於「Drive 有問題」；(ii) `TradingRadarExportScheduleService.saveSetting`（`@Transactional` 於 `:162`）與 `CrawlerExportPathService.update`（`:74`）在交易內，同步 L3 會把外部行程呼叫包進交易、佔住連線；(iii) 啟用時正是連續開九個開關的時候，同步探測等於連續九次打 Drive API（**註**：本條原論據「內建 client 配額全球共享、實測撞過三次 `rateLimitExceeded`」已因 19:35 改回自訂 client 而失效，專屬配額下實測不再撞；本條降為次要理由，(i)(ii) 足以支撐）。**這不是缺口**：L3 涵蓋的 403／remote 錯誤會在該頁第一次實際上傳時寫進 `gdrive_last_status`（既有機制），(a) 每次啟動也會做；(b) 不可取代的價值在 **L2**——唯一能在 access_token 尚未過期的時間窗內抓出「token 缺 refresh_token」的辦法。

  **(b) 的結果一律不寫 `gdrive_last_run_at`／`gdrive_last_status`**：那兩欄的既有語意是**上傳結果**（R50 明訂「成功記落點路徑與檔案大小…由設定卡顯示『上次上傳』」），九個前端 view 全部標成「上次上傳」直接顯示。寫進去會（i）永久覆蓋真正的上傳記錄，（ii）讓 `gdrive_last_run_at` 指向一個沒有發生任何上傳的時刻（設定卡顯示「上次上傳：12:09 — 自檢失敗」而 12:09 沒上傳過）。故走**當次回應**：九頁的儲存 response DTO 各加一個**非持久化**警告欄位（如 `gdriveSelfCheckWarning`，正常為 null），前端在既有「儲存成功」提示旁多顯示一則警告。**不新增 DB 欄位、不新增端點、不新增頁面或元件**；九頁 BFF passthrough 已存在，DTO 加欄位即隨既有路徑帶到前端。九支 service 既有的「刻意不碰 `gdriveLastRunAt`／`gdriveLastStatus`」註解（全樹命中 9 次）維持成立、不開例外。**失敗不得讓儲存回非 2xx**（沿用 `absolutePathOrNull` 的既有理由：唯一的修正入口不能被自己鎖死）。

  **啟動自檢的前置條件**：先查 DB 是否**存在任一列 `gdrive_enabled=true`**（business 查九張表——Requirement 51 的八張 ＋ Requirement 54 的 `stock_alert_export_setting`；ext 查 `crawler_export_setting`，走 `JdbcTemplate` 繞過 owner filter——問的是「全庫有沒有人啟用」而非「我的設定」），全未啟用就整個跳過，維持 Requirement 50／51「既有部署不要求 rclone remote 存在」的承諾。**每新增一個支援 Drive 的設定表都必須同步加進這個 UNION**，少查一張就會給假綠燈：只在該頁啟用 Drive 的部署，遇到 token 失效或 remote 改名時會整個跳過 L3 探測、不噴任何 WARN。**此前置條件只約束 (a)**（(b) 那一刻的請求本身就是啟用的證據）。

  **相依方向必須單向，否則起不來**：因 (b) 要求 `GdriveOutputSupport` 呼叫自檢，**自檢元件就不得反向注入 `GdriveOutputSupport`**——本專案全樹建構子注入且未開 `allow-circular-references`，踩到即 `BeanCurrentlyInCreationException`。remote 名稱改為**每次呼叫傳參**（沿用 `ProcessRcloneClient` 既有模式：建構子不吃 remote，`listDirs`／`copyTo` 由呼叫端傳入）；啟動時所需的 remote 由第三個薄元件（掛 `ApplicationReadyEvent` 者）注入 `GdriveOutputSupport` 取得後傳入。Task 242.1.4「`GDRIVE_OUTPUT_REMOTE` 為全 backend 唯一注入點」不因本需求破例。

  **啟動自檢一律走獨立 daemon 執行緒**：**理由不是 `depends_on: service_healthy`**——`ApplicationReadyEvent` 發布時 web server 已在 listen、healthcheck 已可回應。真正的理由是該事件的 listener 跑在**主執行緒**、彼此**無順序保證**，阻塞 20 秒會延後 `SpringApplication.run()` 收尾與同事件其他 listener；若排在 availability listener 之前，readiness 轉 `ACCEPTING_TRAFFIC` 一樣被拖 20 秒。逾時仍設 20 秒作為執行緒內上限。

  **ext 端的 L3 需要新增唯讀探測**：`GdriveUploader` 只有 `upload`／`isAvailable`／`remoteName`，其 javadoc 把「只實作 `copyto`、不實作任何刪除路徑」寫成安全不變量。L3 要新增 `rclone lsd <remote>:`，**這是對該不變量的明示修改**，javadoc 須同步改為「兩種操作：`copyto`（寫）與 `lsd`（唯讀列目錄）；仍不實作任何刪除路徑」。不得在自檢元件內另寫一份 `ProcessBuilder`——那會複製逾時、stderr 解析與 rate-limit 判定三份邏輯。

  **例外一律不得逸出**：整段（含 DB 前置查詢）包在單一 catch-all 內。查詢的八張表在全新安裝首次啟動時可能尚未由 Liquibase 建立（`BadSqlGrammarException`），而既有同類元件 `CrawlerExportPathQuery` 的既定契約就是「表缺／DB 例外時由呼叫端 fallback」；自檢若讓例外逸出，會把純觀測功能變成啟動失敗。L3 沿用既有 `exec(...)` 時會擲 `RcloneUnavailableException`／`RcloneTimeoutException`／`RcloneRateLimitedException`／裸 `RuntimeException` 四種，須全部攔下。

  **log 不得輸出 token 值或 config 內容**（該檔含 crypt 解密密碼），L2 只判斷鍵是否存在。L3 的 stderr **盡可能原樣附上**，但既有 `exec(...)` 對「找不到 section」與速率限制兩條分支會換成罐頭訊息，該兩型附其可讀訊息即可。
- **為何不做健康檢查頁／端點**：啟用時的自檢結果只走當次回應的 `gdriveSelfCheckWarning`（不入庫、不碰 `gdrive_last_run_at`／`gdrive_last_status`）、啟動時的只進 `docker logs`；做成第十個管理頁只會多一處要維護，而資訊在當次提示與 log 裡已完整。
- **`scope = drive` 的權衡**：新 remote 取得使用者 Drive 的完整讀寫權，這是能寫進**使用者手動建立**的既有目錄所必要的。程式端自我約束為只用 `lsjson --dirs-only`（列目錄）與 `copyto`（寫指定子路徑）兩種操作，**不實作任何刪除既有 Drive 檔案的程式路徑**，把完整權限的實際使用面縮到最小。
- **ext 容器部署前提**：實際上傳者是 `external-materials-service`（目前僅裝 `curl`），需於其 Dockerfile 加裝 `rclone`，並唯讀掛入 `~/.config/rclone` **目錄**（單一共用 config，三個 section 共存；掛目錄而非單檔的理由見上方 Task 247 條）＋設 `RCLONE_CONFIG`；**須沿用 `BackupService` 既有的「啟動時複製到可寫路徑」作法**（唯讀掛載會使 rclone 自動續期 OAuth token 時寫回失敗而 exit non-zero），否則 token 過期後上傳會開始整批失敗。ext 以非 root `appuser` 執行，副本須落在 `/tmp`。
- **中文目錄名編碼：現況已正確，不加 JVM 參數**。若 `sun.jnu.encoding` 退化成 ASCII，`ProcessBuilder` 傳出的中文路徑會變 `?` 並靜默寫錯目錄；但實測 ext 容器現為 UTF-8（base image `eclipse-temurin:21-jre-alpine` 自帶 `LANG=en_US.UTF-8`），且命令列 `-Dsun.jnu.encoding=UTF-8` 對該屬性**無效**（JDK 由 platform locale 決定）。約束改為「不得設 `LANG=C`／清除 base image locale／換成不帶 UTF-8 locale 的 base image」，並以驗證步驟回歸守門。
- **不為兩處 rclone 呼叫建共用 module**：`backend`／`bff`／`external-materials-service` 為三個獨立 Maven 專案、無父 pom。ext 端 rclone 呼叫為薄封裝（`ProcessBuilder`＋逾時＋exit code 檢查），刻意與 `BackupService.execProcess` 各自實作——為兩處數十行程式碼引入跨服務 module 會使三個服務的建置相互耦合，成本高於重複本身。**注意這條只適用於跨服務**：Requirement 51 把 Drive 輸出推廣到 backend 內的八個匯出 service 時，必須抽**同一個 backend 內的共用元件**（`GdriveOutputSupport`），八份複製是明確的錯誤。

**推廣至其餘八個匯出頁（Requirement 51 / Task 242–244）** —— 同一套「本機照寫＋Drive 附加副本」模型，套用於歷年資產（R34）／交易日曆（R37）／已實現損益（R39）／**油價金價（R41）**／**台幣兌美元匯率（R42）**／GDP-TWSE（R45）／交易雷達（R48）／交易紀錄（R49）。

因八頁 × 六層（DB／entity／DTO／service／BFF／前端）遠超「一支任務檔＝一個可獨立驗收的交付」的界線，**刻意拆成三支任務檔**（`spec/tasks/README.md` 鐵則 1）：

| 任務 | 交付 | 可獨立驗收的判準 |
|---|---|---|
| **t242** | 地基：共用元件 `GdriveOutputSupport`（含收斂 R50 留下的兩份重複）＋ `RcloneClient.copyTo` ＋ changeset `v1.76.0`（8 表 × 4 欄）＋ 8 個 entity ＋ 單元測試 | 八張表欄位到位、單元測試綠、既有功能無回歸（尚無 UI，功能未啟用） |
| **t243** | 六個結構相近的頁面：歷年資產／已實現損益／交易紀錄（`writeToDir`）＋ 油價金價／台幣兌美元／GDP-TWSE（`export()` ＋ `writeAtomically`） | 這六頁端到端可用（含 run-now 上傳） |
| **t244** | 兩個結構例外：交易雷達（`writeDailyExport` ＋ 裸 `String` DTO ＋ 多時間點）＋ 交易日曆（委派寫檔 ＋ 無 run-now ＋ UI 在匯出對話框內） | 這兩頁端到端可用 |

八頁的結構分三類（實測），**不可套同一個修改樣板**：（a）三頁有 `writeToDir()`；（b）四頁是 `export()`／`writeDailyExport()` 外層 ＋ `writeAtomically()` 底層——上傳插入點必須在**外層**（`writeAtomically` 沒有設定列情境，拿不到 `gdrive_subpath`）；（c）交易日曆自己不寫檔，委派 `TradingCalendarExportService`。

**八張表統一新增的四個欄位（changeset `v1.76.0-gdrive-output-all-export-pages.sql`）** —— 這是本節的權威定義，下方各頁的資料模型區塊只標註「＋ gdrive 四欄（見此）」而不重複型別，避免八份定義各自漂移：

```
gdrive_enabled       BOOLEAN      NOT NULL DEFAULT false   -- 是否在本機檔寫成功後額外上傳一份到 Drive
gdrive_subpath       VARCHAR(512)                          -- Drive 相對子路徑（基底為 rclone remote），啟用時必填
gdrive_last_run_at   TIMESTAMP                             -- 上次「判斷」時間（含成功／失敗／跳過），非僅成功
gdrive_last_status   VARCHAR(512)                          -- 上次結果，截斷至 512 內
```

適用的八張表：`export_schedule_setting`（歷年資產 R34）／`realized_gain_export_schedule`（已實現損益 R39）／`asset_transaction_export_schedule`（交易紀錄 R49）／`commodity_export_schedule`（**油價金價 R41**）／`exchange_rate_export_schedule`（**台幣兌美元匯率 R42**）／`index_export_schedule`（GDP-TWSE R45）／`trading_radar_export_setting`（交易雷達 R48）／`trading_calendar_export_schedule`（交易日曆 R37）。

- entity 欄位型別：`gdriveLastRunAt` 為 **`LocalDateTime`**——八張表既有的 `lastRunAt` 實測一致為 `LocalDateTime`（`Instant` 命中 0），對齊即可。
- **狀態欄與既有 `last_run_status` 分離，不得併入**：「本機成功、Drive 失敗」是正常且必須可分辨的狀態；共用一欄會讓本機明明寫成功卻顯示失敗，使用者去做不必要的排查。
- **seed 不啟用任何一列**，既有部署升級後行為與現況完全一致、不要求 rclone remote 存在。

與爬蟲頁的兩處結構差異：

| | 爬蟲資訊查詢（R50） | 其餘八頁（R51） |
|---|---|---|
| 設定表 | `crawler_export_setting`（**全域**，無 owner） | 八張各自的表（**per-user**，`owner_user_id` ＋ `@Filter(ownerFilter)`） |
| 上傳者 | `external-materials-service`（`NewsPoller` 每輪） | `business-services`（各頁排程 ＋ run-now） |
| 觸發 | 爬蟲輪次（DB 驅動時間點） | 每日排程 ＋ 使用者按「立即匯出到目錄」 |

- **Drive 同步只有「主要管理者」本人能啟用（隱私硬約束）**：八張表雖為 per-user，但 **rclone remote 全機只有一份**，綁定某一個特定 Google 帳號。若允許其他使用者啟用，B 的財務報表會被上傳到那個帳號的雲端硬碟，且從 B 的角度不可見。故 `PUT` 要求把 `gdrive_enabled` 設 true 而當前使用者不是主要管理者 → **403**（權限問題，非輸入錯誤）；前端亦不顯示該開關。**本機路徑與排程時間仍為所有使用者皆可設定**——此不對稱是刻意的，只有 Drive 這一項會把資料送出本機。
- **判準是 `isConfiguredAdmin(email)`，不是 `role == ADMIN`**：`role` 可有多列 ADMIN（實測 `app_user` 現有兩名使用者），第二位若被升為 ADMIN，其報表仍會進到 remote 擁有者的 Drive——外流語意不變、只是母體變小。故走既有單一判定入口 `UserAdminService.isConfiguredAdmin(email)`（比對 `ADMIN_EMAIL`，全庫唯一一人）。`CurrentUserContext` 只帶 `effectiveUserId`／`role`／`status`、**不帶 email**，故兩處判定都需先以 userId 查 `AppUserRepository` 取 email。
- **背景排程須逐列再驗 owner 仍是主要管理者**：排程是背景執行緒、逐列跑 `findAll()`，沒有 `CurrentUserContext`，`PUT` 時的檢查在此完全不適用。若某列在啟用後 owner 被改、DB 被 psql 直改、或 `ADMIN_EMAIL` 換人，背景仍會照上傳——故產檔後、上傳前須以該列 `owner_user_id` 取 email 再走**同一個** `isConfiguredAdmin`，不通過則跳過上傳並寫狀態欄說明原因（不可靜默跳過）。此為與 Requirement 39／49「背景排程必須逐列 `enableFilter`」同一類的縱深防禦。可行性已確認：`AppUser` 未套 `@Filter`，且 `TenantFilterAspect` 在無 request 情境時直接 return，故背景執行緒 `findById(ownerId)` 不會被 fail-closed 成空。
- **狀態欄與既有 `last_run_status` 分離**：Drive 結果存新的 `gdrive_last_run_at`／`gdrive_last_status`，不併入既有欄位——「本機成功、Drive 失敗」是正常且必須可分辨的狀態；共用一欄會讓本機明明成功卻顯示失敗，使用者去做不必要的排查。
- **`RcloneClient` 加 `copyTo`，仍不實作刪除**：Requirement 50 的介面只有 `listDirs`；本需求加上傳，但維持「只 `lsjson` 與 `copyto`」的自我約束不變，且沿用既有的 `CONFIG_SOURCE`（`/etc/rclone/rclone.conf`）→ `/tmp/rclone-output.conf` 可寫副本與 per-process 覆寫，**本需求不變更 config 佈局**。
- **`copyTo` 必須帶 `RCLONE_LIMITS`，且 `exec()` 需參數化**（t245 實測教訓）：rclone 預設 `--low-level-retries 10` ＋ `--timeout 5m`，遇到任何 Drive API 延遲就會把單次上傳放大到數十秒至數分鐘，而外層 process timeout 砍掉它只會讓上傳失敗、不會讓它變快（實測：無參數手動測試 >180 秒未結束、20:30 那輪排程以「逾時（45 秒）」失敗；帶上 `--retries 1 --low-level-retries 3 --contimeout 10s --timeout 30s` 後同一上傳只需 2.4 秒）。既有 `exec()` 把 20 秒與「目錄列舉」字樣硬編，重用前須參數化（timeout ＋ 操作名稱）——否則上傳失敗會把「目錄列舉逾時」原樣寫進使用者可見的 `gdrive_last_status`。
- **逾時 ≠ 失敗，措辭必須分開**（已實測發生的假失敗）：`exec()` 的判準是「行程未在時限內 exit」而非「檔案沒上去」。實測 `crawler_export_setting.gdrive_last_status` 記「失敗：rclone 上傳逾時（45 秒）」，同一輪 Drive 端卻有 122053 bytes 的完整檔案、與本機逐 byte 同大小。故逾時寫「逾時（N 秒）：Drive 端可能已完成，請於下一輪確認」，只有 rclone 非零退出才寫「失敗：<rclone 錯誤>」。
- **Drive 目錄列舉仍只有一支**：八頁 BFF 各加自己的 `browse-gdrive` passthrough（一頁一 BFF），但全部指向同一支 business `GET /api/export-schedule/browse-gdrive`——**含交易日曆頁**，即使其本機列舉自成一份 `trading-calendar-export/browse`（既有分裂，不在本需求範圍），Drive 側不得再開第二份。
- **兩個結構例外（t244）**：（1）**交易日曆**——`TradingCalendarExportScheduleService` 自己不寫檔（委派 `TradingCalendarExportService`），且**沒有 run-now**：其手動匯出是 `POST /api/trading-calendar-export/run?year=&format=&subpath=`，不經排程 service，`subpath` 由 HTTP query param 帶入而非讀設定列，故該路徑若要上傳必須另外讀該使用者的設定列；其 UI 的輸出資料夾也不在頁面卡片上，而在匯出對話框內、與手動匯出共用同一欄位。（2）**交易雷達**——設定 DTO 是裸單欄 `SettingRequest(String outputSubpath)`，controller 與 service 簽章都只傳一個字串，加 Drive 欄位須同時改 record／controller／service 簽章／前端 helper 形狀（＝Requirement 50 在爬蟲頁踩過的同一個坑）；且其執行時間點存於另一張表、一天可能上傳多次，`gdrive_last_status` 為「最後一次」語意。
- **`RcloneClient` 的類別 Javadoc 須同步改寫**：Requirement 50 留下的註解明文寫「刻意只有列目錄一個方法…backend 端只實作列目錄…實際上傳在 `external-materials-service` 端」。加 `copyTo` 後這三句全部失效，須改為「backend 端實作列目錄與上傳兩種操作，仍不實作任何刪除路徑」。

**實作落地（t243／t244 完成後的實際形狀）**

共用元件 `GdriveOutputSupport` 對外收斂為五類操作，八個 service 只呼叫它、不自行實作第二份規則：

| 方法 | 用途 | 關鍵語意 |
|---|---|---|
| `resolveUpdate(ownerId, reqEnabled, reqSubpath, curEnabled, curSubpath)` | `PUT` 時解析 Drive 兩欄 | null＝不變更；啟用時必填；**明確要求啟用**才查權限（403 早於 400） |
| `syncQuietly(ownerId, subpath, localFile)` | 本機檔寫成功後上傳 | **絕不擲例外**；每輪重驗 owner；`localFile == null`＝寫「跳過」而非上傳舊檔 |
| `isDriveAllowedFor(ownerId)` | 權限唯一入口 | fail-closed；判準 `isConfiguredAdmin(email)` |
| `listDirsSorted(subpath)` / `normalizeBrowseSubpath` | Drive 目錄列舉 | 全庫唯一一份實作 |
| `remoteName()` | 顯示用 remote 名 | backend 唯一的 `GDRIVE_OUTPUT_REMOTE` 注入點 |

- **`resolveUpdate` 的權限檢查只在「明確送 `gdriveEnabled=true`」時觸發**：既有值已是 true 而本次請求沒送該欄時刻意不檢查，否則 `ADMIN_EMAIL` 換人後該使用者連本機輸出路徑與排程時間都會被 403 鎖死——而那兩項本來就開放給所有使用者（R39／R49）。這不是漏洞：真正決定「會不會上傳」的是每輪產檔前 `syncQuietly` 內的 `isDriveAllowedFor` 複驗。
- **B 組（油價金價／台幣兌美元／GDP-TWSE）的上傳插在 `export()` 的兩個呼叫點之後**，而非 `export()` 內部：`writeAtomically()` 一如設計所述完全不動，而 run-now 那一側必須拿得到 `SyncResult` 才能回報 `gdrivePath`／`gdriveStatus`（AC「run-now 也必須上傳並回報落點」）。`export()` 回傳處即「`writeAtomically` 之後」，兩者不衝突。
- **八支 BFF passthrough（一頁一 BFF，全部指向同一支 business 端點）**：

```
GET /api/bff/asset-history/export-schedule/browse-gdrive   ┐
GET /api/bff/realized-gain/export/browse-gdrive            │
GET /api/bff/transaction/export/browse-gdrive              │
GET /api/bff/commodity-price/export/browse-gdrive          ├─→ GET /api/export-schedule/browse-gdrive
GET /api/bff/exchange-rate/export/browse-gdrive            │   （Drive 目錄列舉全庫唯一一份實作）
GET /api/bff/gdp-twse/export/browse-gdrive                 │
GET /api/bff/trading-radar/export/browse-gdrive            │   註：asset-history 的前綴與其餘七支不同
GET /api/bff/trading-calendar/export/browse-gdrive         ┘
```

- **交易日曆的 `POST /run` 改為委派排程 service**：`TradingCalendarExportController.run` → `TradingCalendarExportScheduleService.runManualForCurrentUser(year, format, subpath)`，controller 維持純委派（`structure.md` 2.2：controller 不讀 repository）。該方法內部先委派 `TradingCalendarExportService.exportToDir(...)` 寫本機，再讀**當前使用者的排程設定列**取 Drive 目的地上傳。**兩個 subpath 的來源刻意不同**：本機來自 query param（「這次匯出到哪」）、Drive 來自設定列（「Drive 同步的固定目的地」）；若讓 Drive 也吃 query param，使用者每次手動匯出都可能把檔案倒進 Drive 的不同位置。`RunResponse` 因此加 `gdrivePath`／`gdriveStatus` 兩欄。
- **BFF 新增橫切錯誤轉譯 `bff/common/BusinessErrorAdvice`（`@RestControllerAdvice`）**：BFF 原本**完全沒有錯誤轉譯**，business 回的 4xx／5xx 到使用者眼前會變成沒有訊息的 500。它把 `WebClientResponseException` 的狀態碼與原始 `ProblemDetail` body 原樣回傳，前端 `api/index.js` 讀 `data.detail` 才拿得到「Google Drive 同步僅限主要管理者啟用」（403）與 rclone remote 不可用（503）這兩條訊息。對 Spring Cloud Gateway 的 route（如 `TradingRadarBffRoutes`）不生效也不需要——那是直接 proxy，狀態碼與 body 本來就原樣傳回。
- **前端一律以 `auth.isConfiguredAdmin` 控制顯示**（八個 view 皆新增 `useAuthStore`），**不得用 `auth.isAdmin`**（`role === 'ADMIN'` 判準與後端 403 不一致，會出現「畫面顯示得了、按儲存卻 403」）。真正的閘門在後端，前端只是不顯示。

### API 端點

```
# business-services（新）
GET  /api/export-schedule/settings     # 取當前使用者排程設定（無則回預設，不寫入）
PUT  /api/export-schedule/settings     # upsert 當前使用者設定（enabled/runHour/runMinute/outputSubpath）
POST /api/export-schedule/run-now      # 立即以當前使用者身分產「當前即時資產」檔寫入其設定目錄（回 path/sizeBytes）
GET  /api/export-schedule/browse       # 唯讀：列基底（家目錄）下 ?subpath= 的子目錄清單（樹狀選擇器懶載入）
GET  /api/export-schedule/browse-gdrive # 唯讀：列 Google Drive remote 下 ?subpath= 的子目錄清單（Requirement 50 / Task 245）
                                        #   與上一支並列而非加參數：語意不同（本機基底 vs. Drive remote）
                                        #   「Drive 目錄列舉」全庫只准這一支：其餘頁面日後接 Drive 一律沿用，含交易日曆頁
                                        #   （其本機列舉自成一份 trading-calendar-export/browse，但 Drive 側不得再開第二份）
                                        #   實作 rclone lsjson --dirs-only；remote 未設定/授權失效須回可讀錯誤訊息，不得 500 或回空樹
                                        #   （空樹會被誤讀為「Drive 裡沒有資料夾」）
GET  /api/snapshots/export             # 既有；exportFull() 多分頁歷次匯出（含「當前彙總」總表，owner-scoped）

# BFF（AssetHistoryBffController，沿用 businessServicesClient 自動帶 X-User-*）
GET  /api/bff/asset-history/export-schedule          → GET  /api/export-schedule/settings
PUT  /api/bff/asset-history/export-schedule          → PUT  /api/export-schedule/settings
POST /api/bff/asset-history/export-schedule/run-now  → POST /api/export-schedule/run-now
GET  /api/bff/asset-history/export-schedule/browse   → GET  /api/export-schedule/browse（唯讀列子目錄）
GET  /api/bff/asset-history/export                    → GET  /api/snapshots/export（既有，多分頁歷次匯出）
```

> 排程設定為 per-user（owner-scoped），非 admin-only：路徑落 BFF `SecurityConfig` 的 `.anyExchange().authenticated()`，一般登入者可設定自己的排程；後端 `ownerFilter` 縮到本人。

### 關鍵業務邏輯

- **匯出內容單一來源**：`ExcelExportService.buildWorkbook()` 產出活頁簿＝`writeCurrentSummarySheet()`（第一張，讀最新快照彙總）＋每快照一張 `writeSnapshotSheet()`＋`writeRealizedGainsSheet()`。
  - `exportFull()`：`@Transactional(readOnly=true)`，HTTP 情境靠 `TenantFilterAspect` 自動 owner-scoped，呼叫 `buildWorkbook()`。
  - `exportFullForOwner(Long ownerId)`：`@Transactional(readOnly=true)`，於 session 手動 `entityManager.unwrap(Session.class).enableFilter("ownerFilter").setParameter("ownerId", ownerId)` 後呼叫同一 `buildWorkbook()`（背景排程用；aspect 於背景不啟用、不覆寫）。
- **當前彙總總表**：讀最新一筆 `asset_snapshot`，欄位：匯出時間、最新快照日期、美元匯率、資產總計、存款總計、股票現值／成本／未實現損益、基金現值／成本／未實現損益、預估年配息、當年度已實現損益。無快照時寫「尚無快照」提示列。
- **路徑安全**：`resolveDir(sub)` = `base = Path.of(EXPORT_OUTPUT_DIR).toAbsolutePath().normalize()`；`target = base.resolve(sub).normalize()`；若 `!target.startsWith(base)` 則拒（防 `..`／絕對路徑跳脫）。`PUT /settings` 亦驗 `run_hour∈[0,23]`、`run_minute∈[0,59]`、子路徑非空且不含跳脫。
- **觸發判斷（>= 到點，非精確相等）**：tick 與開機自癒共用同一判斷 `enabled && last_run_date != today && now >= 排程時間`。用 `>=` 而非「分鐘精確相等」，因 Spring 預設排程池僅 1 條執行緒且與其他 `@Scheduled` 共用，長工作可能把某分鐘的 tick 延後跨越目標分鐘；`>=` ＋ `last_run_date` guard 讓任何被延後／跳過的分鐘都能在後續 tick 自動補跑，直到當日成功為止（避免整日靜默漏跑）。
- **當日 guard 與自癒**：成功或失敗都設 `last_run_date=today`，避免到點後每分鐘重試；重啟以 `ApplicationReadyEvent` 補跑「今日排程時間已到但 `last_run_date != today`」者。`run-now` 不動 `last_run_date`（不影響排程 guard），只更新 `last_run_at/last_run_status`。
- **失敗隔離**：單一使用者產檔／寫檔失敗記 `last_run_status` ＋ `log.warn`，不影響其他使用者、不中斷 poll。

### Infrastructure

- `docker-compose.yml` business-services volume `${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}:/home/steven` 與環境變數 `EXPORT_OUTPUT_DIR=/home/steven`；`.env.example` 的 `EXPORT_OUTPUT_DIR_HOST` 預設 `/Users/steven`。使用者設定 `output_subpath=input` 時，檔案落於 host `/Users/steven/input`；子路徑空＝家目錄根。

### 本次增修（排程改匯出「當前即時資產」＋家目錄為根＋檔案總管式資料夾選擇）

- **排程／run-now 改匯出「當前即時資產」**：`ExcelExportService` 新增 `exportLiveAssets()`（HTTP，aspect owner-scoped）與 `exportLiveAssetsForOwner(Long ownerId)`（背景，手動 `enableFilter`），皆走 `buildLiveWorkbook()`。內容單一來源＝`StockPriceService.getLiveAssets()`（最新快照持股 × Redis 即時股價，與 Dashboard 首頁「當前資產」同一數字；存款／基金沿用最新快照凍結值），股票逐檔以 `(code, market)` 對映即時價與即時現值；deposits／funds 明細讀最新快照（`AssetSnapshotRepository.findLatest()` 後於同交易 lazy load）。排版比照 `writeSnapshotSheet`（銀行存款／基金／股票分區，股票加「即時價」欄、現值用即時值，投資成本共用抽出的 `stockCostTwd()` 換匯），末段列即時彙總（存款總計／基金現值／即時股票現值／即時總資產）。`ExportScheduleService.runNowForCurrentUser()` 改呼叫 `exportLiveAssets()`、`runScheduled()` 改呼叫 `exportLiveAssetsForOwner()`。手動「匯出 Excel」（`/api/snapshots/export` → `exportFull()` 多分頁歷次）維持不變。
- **家目錄為根**：`EXPORT_OUTPUT_DIR` 預設由 `/data/export-output` 改為 `/home/steven`；volume host 端由 `/Users/steven/Project/SRPP/data` 改為 `/Users/steven`。`output_subpath` 仍為相對子路徑（相對家目錄根），路徑安全驗證邏輯不變。
- **檔案總管式資料夾選擇（唯讀 browse）**：`ExportScheduleService.browse(subpath)` 以 `startsWith(base)` 驗證後 `Files.list` 僅取子目錄（隱藏 dotfiles、依名稱排序），回 `BrowseResponse{baseDir, subpath, absolutePath, directories:[{name, path}]}`；`ExportScheduleController` 加 `GET /browse`，BFF passthrough。前端 `AssetHistoryView.vue` 標題改「排程自動匯出最新資產」，輸出資料夾改 `el-tree` 懶載入樹狀選擇對話框（`load` 呼叫 browse 逐層展開，點選節點取相對子路徑）＋可選填「新增子資料夾名稱」（寫檔時 `Files.createDirectories` 自動建立，故 browse 保持唯讀、無需新增變更檔案系統的端點）。

### 「股票（即時）」分頁增列即時報價與技術指標欄（Task 200）

- **欄位增列**：`ExcelExportService.writeLiveAssetsSheet` 的股票分頁在「即時價」後插入 `昨收／漲跌／漲跌幅(%)`，並於分頁末增列 `月線價／季線價／年線價／KD值`。完整欄序（0–17）：`券商／市場／代號／名稱／股數／投資成本／即時價／昨收／漲跌／漲跌幅(%)／即時現值／預估配息／交易類型／交易日期／月線價／季線價／年線價／KD值`。
- **昨收／漲跌／漲跌幅單一來源**：`StockPriceService.LiveStockItem` record 於末尾擴增 `previousClose／priceChange／changePercent` 三欄，於 `getLiveAssets()` 由**同一筆** `PriceQueryService.LivePrice` 帶出（該筆本已在迴圈內讀取，零額外 Redis 讀取），與「即時價」同一 tick，確保「即時價 − 昨收 = 漲跌」一致。此為向後相容的末尾擴欄——`GET /api/market-data/live-assets`（Dashboard）JSON 多回三欄、既有前端忽略；建構點僅 `StockPriceService` 一處、無測試以位置參數建構此 record。
- **月／季／年線與 KD**：`ExcelExportService` 注入既有共用權威 `TechnicalIndicatorService`，逐 `(code, market)` 呼叫 `computeAll()` 取 `FullIndicators{monthlyMa, quarterlyMa, annualMa, k, d}`（資料源 `stock_price_history` 近 240 筆；與觀察清單／警示同一計算，符合「同義欄位同一 business service」）。以 `Map<code|market, FullIndicators>` 於單次匯出內快取，同股多券商列僅計算一次。KD 併為單一「KD值」欄字串 `K {k} / D {d}`（k/d 皆為 `computeAll` 已 scale 2 位之 BigDecimal，任一為 null 以 `—` 佔位）。
- **儲存格樣式**：昨收沿用即時價 `num4`；漲跌／漲跌幅／月線／季線／年線用新增 `num2`（`#,##0.00`）；KD值為純字串。查無即時報價或歷史不足者相應欄留白（`cell()` 遇 null 不寫值）。此增列同時作用於 run-now（`exportLiveAssets`）與排程（`exportLiveAssetsForOwner`），皆共用 `writeLiveAssetsSheet`。

### ETF 淨值與折溢價欄（Task 214）

- **資料流**：`external-materials-service` 抓取 → Redis → `business-services` 讀取 → Excel 欄位。
  business 不直連外部行情 API（既有規範），故淨值比照即時股價走 Redis 中介。

  | 階段 | 元件 | 說明 |
  |---|---|---|
  | 抓取（台股） | `client/EtfNavFetchClient.fetchTwAll()` | 一次 GET 證交所 `all_etf.txt`（全市場約 350 檔，含上市＋上櫃），Java HttpClient ＋ `Referer`，比照 `PriceFetchClient` |
  | 抓取（美股） | `MarketDataFetchService.getUsEtfNav(symbol)` | Yahoo `quoteSummary?modules=summaryDetail,price`；**沿用既有 `getYahooCrumb()` 單一入口**，不另取 crumb（各處自取會互相打成 429） |
  | 寫入 | `service/EtfNavCacheWriter` | `price:etfnav:{market}:{code}`，String JSON，**TTL 96h** |
  | 排程 | `service/EtfNavPoller` | 台股交易時段每 5 分鐘（`0 2/5 9-13 * * MON-FRI` TPE）；美股 `0 30 18 * * MON-FRI` NYC；＋開機 warmup ＋ `POST /internal/etf-nav/refresh` |
  | 讀取 | `PriceQueryService.getEtfNav()` | 回 `Optional<EtfNav>`；**刻意不 fallback DB**（淨值不在 `stock_price_history`，且「查無」是個股的正常狀態） |
  | 呈現 | `ExcelExportService.writeLiveAssetsSheet` | 欄 18 淨值(`num4`)／19 折溢價(%)(`num2`)／20 淨值時間(字串)；`autoSizeColumn` 上界 18→**21** |

- **獨立 Redis key 而非擴充 `price:{market}:{code}`**：後者有三個寫入者（`write`／`writeVerifiedClose`／`syncClosedFromDb`），
  多帶欄位會被彼此覆蓋，且會波及 `ClosePersister` 的收盤回填掃描與 `price-update` pub/sub 的 SSE 契約。
- **TTL 96h（不同於即時價的 24h）**：淨值一天只有一組有意義的值且只在交易時段抓取；24h 會讓週末／連假後第一份匯出整欄空白。
  payload 內帶 `navAsOf`，判斷新舊看該欄位，不可由「Redis 有值」推論「是今天的值」——這正是第三欄「淨值時間」存在的理由。
- **ETF 判定＝資料存在性，不用白名單**：台股看代號是否在證交所 ETF 名冊、美股看 Yahoo 是否回 `navPrice`（個股如 GOOGL 沒有此欄位）。
  既有 `isEtf()` 有三份複本（backend 為死碼、ext、frontend），皆誤含個股 `AVGO`、皆漏 `SGOV`，本功能刻意不複用；
  日後若要收斂那三份白名單，本處的資料驅動判定可作為替代方案。
- **折溢價的兩條計算路徑（刻意不統一）**：台股沿用證交所已算好的權威值（其市價與匯出列的「即時價」同源，實測逐檔吻合）；
  美股則因 Yahoo 未提供折溢價欄，改由 `ExcelExportService.premiumDiscountPct()` 以**該列自己的即時價**與淨值計算。
  抓取端刻意**不**為美股預先算好——那會用 Yahoo 自己的市價，與列上顯示的即時價不同源（實測 VOO 差 0.11%），
  造成使用者拿本列數字驗算兜不攏的列內矛盾。
- **折溢價計算的兩條紅線**（違反會產生「平盤日正常、大跌日離譜」的靜默錯誤，程式碼註解已標明）：
  1. 台股**直接採用證交所已算好的折溢價欄**，不得由淨值自行重算（淨值欄在股票型四捨五入至 2 位，重算誤差達 0.07 個百分點）。
  2. **不得用「前一交易日淨值」欄**（該欄對全部檔位皆 T-1）；美股同理**不得用 `previousClose`** 配 `navPrice`。
- **語意差異（design 明載，避免日後誤解）**：台股寫入的是盤中即時預估淨值，同日不同時間匯出數字會不同；
  美股則是前一交易日收盤淨值配同時點市價。兩者不是同一時點的概念，欄位語意統一理解為「最近一次取得的淨值」。
- **失敗處置**：抓取失敗不寫入（保留上一輪值），比照 `PriceCacheWriter` 對 `z='-'` 的處置；`etf-nav.enabled=false` 可整體停用，
  停用後匯出三欄留白、不影響其他欄位。

#### 每日入庫留存（Task 215）

- **表**：`etf_nav_history(stock_code, market, nav_date, nav, premium_discount_pct, source)`，
  `(stock_code, market, nav_date)` UNIQUE，Liquibase `v1.63.0-etf-nav-history.sql`（冪等寫法）。全域公開行情，無 `owner_user_id`。
- **雙寫分工**：Redis（TTL 96h）＝匯出當下要用的「最新一筆」；`etf_nav_history` ＝長期歷史。
  每次抓取兩邊都寫，DB 端為 upsert，故盤中反覆覆寫同一列，**當日最終值＝最後一次抓取值**。
- **收盤後補抓（`0 30 17 * * MON-FRI` TPE）**：投信約 17:00 更新當日淨值；這一輪的作用是讓當日最後一次寫入落在收盤後，
  使 DB 內該日值具「收盤折溢價」語意，而非停在 13:30 前的盤中瞬間。台股交易日 guard 以 `MarketClock.isTradingDay` 判定。
- **資料日來源**：台股取彙整檔自帶的資料日期欄、美股取報價時點轉紐約當地日期。
  **解析不出來就不入庫**——不以 `LocalDate.now()` 代入，否則跨日抓取或休市補抓會把資料掛到錯誤日期，且該錯誤在 UNIQUE 約束下會固化成一筆假資料。
- **不存市價（正規化）**：市價同一事實已在 `stock_price_history.close_price`。折溢價則**不是**衍生值——見上方紅線，
  台股為證交所權威值且無法由已四捨五入的淨值反推，屬刻意保留的來源事實。
- **入庫折溢價的兩條路徑**：台股沿用證交所權威值；美股 Yahoo 不提供該欄，改以 `stock_price_history` 中
  **與淨值同一交易日**的收盤價計算（`EtfNavPoller.resolvePct()` ＋ `StockSourceQuery.findCloseOn()`），查無同日收盤價則留 null、
  下一輪再補。**與 Excel 欄位語意刻意不同**：匯出欄用「該列當下的即時價」（答『現在買貴了沒』），
  本表用「該交易日收盤價」（答『當日收盤折溢價』，供日後比較常態區間）。兩者本就不是同一個問題，不應強求一致。
- **尚無讀取端**：本階段只做累積留存，未新增 entity／repository／API／頁面。日後要畫折溢價走勢圖時再補讀取路徑，
  屆時歷史資料已經在表內（這正是先行留存的目的）。

### 資產總覽活頁簿改為「總表 ＋ 每檔持股一張過去一年股價分頁」（Task 206）

- **活頁簿結構**：`buildLiveWorkbook()` 在既有 `writeLiveAssetsSheet`（第一張「當前即時資產」）之後追加 `writeStockPriceHistorySheets(wb, st, latest)`，逐檔產生一張股價分頁。run-now 與每日排程共用同一 `buildLiveWorkbook()`，故兩條路徑內容一致；`exportFull()`（歷年多快照活頁簿）不受影響。
- **持股清單與去重**：來源為第一張分頁**同一個** `AssetSnapshot latest`（同一交易內 lazy load，不另查）；以 `LinkedHashSet<code|market>` 去重並保留總表順序，同檔多券商只出一張。`latest == null`（尚無快照）時不產生任何股價分頁。
- **資料查詢**：`StockPriceHistoryRepository.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code, market, today.minusYears(1), today)`（`today` 取 `Asia/Taipei`）——沿用既有派生查詢，不新增 repository 方法。`stock_price_history` 為收盤價唯一權威來源（`HistoricalDataService.purgeOldHistory` 保留 10 年），與 `TechnicalIndicatorService` 的 MA/KD 同源，故匯出的收盤價與總表末四欄的技術指標必然自洽；匯出過程零外部行情呼叫。
- **分頁內容**：第 0 列表頭 `日期／開盤價／最高價／最低價／收盤價／成交量`，其後每交易日一列。日期以 `ISO` 格式寫成**文字**（同油價金價／匯率分頁的既有理由：避免時區位移）；OHLC 用 `num4`、成交量為整數不套小數樣式。開高低與成交量在 DB 可空（`StockPriceHistory` 僅 `close_price NOT NULL`），該格留白不補值。**查無資料仍建立只有表頭的空分頁**，讓「持有但無資料」與「未持有」可區分。
- **分頁命名（`uniqueStockSheetName`）**：首選 `WorkbookUtil.createSafeSheetName(code)`（收斂 Excel 的 31 字元上限與 `[]:*?/\` 禁用字元）；若已存在同名分頁（不同市場同代號，例如台美同號）則改 `代號_市場`；仍衝突再加 `_2`、`_3`… 數字後綴，比照 `writeSnapshotSheet` 的既有處理。第一張「當前即時資產」為中文名，與代號不可能撞名。
- **規模**：以目前約 43 筆持股（去重後約 35 檔）× 一年約 250 個交易日估算，約 9 千列、單檔數百 KB，遠低於 xlsx 上限；查詢為每檔一次帶 `(stock_code, trading_date)` 索引的區間掃描。

---

## Requirement 37（Task 189）：交易日曆匯出到指定路徑（JSON／Excel）

「交易日曆」頁新增「匯出」動作：把某一年度整年交易日曆（台／美／英三市每日交易日旗標＋各市場國定假日）以 JSON 或 Excel 寫檔到使用者指定目錄。輸出路徑沿用 Requirement 34 的家目錄為根＋相對子路徑安全模型；為手動一次性匯出（非排程、不落 DB）。

### 架構與資料流

```
[交易日曆頁 TradingCalendarView.vue]
  ├─ 「匯出」對話框（年度 / 格式 json|excel / 輸出資料夾樹狀選擇器）
  │     → bffApi.tradingCalendar.exportToDir(year, format, subpath)
  │       → BFF POST /api/bff/trading-calendar/export?year=&format=&subpath=
  │         → business POST /api/trading-calendar-export/run?year=&format=&subpath=
  │           → TradingCalendarExportService.exportToDir(year, format, subpath)
  │             → MarketDataService.getTw/Us/UkHolidays(year) + isTw/Us/UkTradingDay(date)  逐日建表
  │             → JSON: ObjectMapper pretty →  或  Excel: Apache POI XSSFWorkbook →  byte[]
  │             → 寫 {EXPORT_OUTPUT_DIR resolve subpath}/交易日曆_{year}.{json|xlsx}（tmp + ATOMIC_MOVE）
  └─ 資料夾選擇器 → bffApi.tradingCalendar.browseExportDir(subpath)
        → BFF GET /api/bff/trading-calendar/export/browse?subpath=
          → business GET /api/trading-calendar-export/browse?subpath=（唯讀列子目錄）
```

### 資料模型

**R37 初版（Task 189，即時匯出 `/run`）無新增 DB 資料表／欄位**——純檔案輸出、不持久化任何設定；交易日曆資料每次即時由 `MarketDataService` 產出。**後由 Task 190 新增 `trading_calendar_export_schedule`**（每 owner 一列的每日排程匯出設定，比另兩張排程表多一個 `format` 欄〔json/excel〕）持久化排程設定，詳見下方「每日排程自動匯出（Task 190）」；即時匯出本身仍不落 DB。

**JSON 輸出結構（`交易日曆_{year}.json`，UTF-8 pretty-print）：**

```json
{
  "year": 2026,
  "generatedAt": "2026-07-14 23:30:00",
  "timezone": "Asia/Taipei",
  "tradingDayCount": { "tw": 240, "us": 250, "uk": 253 },
  "holidays": {
    "tw": { "2026-01-01": "元旦", "...": "..." },
    "us": { "2026-01-01": "New Year's Day", "...": "..." },
    "uk": { "2026-01-01": "New Year's Day", "...": "..." }
  },
  "days": [
    { "date": "2026-01-01", "weekday": "四",
      "tw": false, "us": false, "uk": false,
      "twHoliday": "元旦", "usHoliday": "New Year's Day", "ukHoliday": "New Year's Day" }
  ]
}
```

- `days` 逐日一筆（整年 365／366 筆）；`tw/us/uk` 為布林交易日旗標，`twHoliday/usHoliday/ukHoliday` 無假日時為 `null`。鍵名（`tw/us/uk` + `*Holiday`）與 `TradingCalendarView` 前端日格模型一致。

**Excel 輸出（`交易日曆_{year}.xlsx`，Apache POI）：** 單一工作表「交易日曆 {year}」；第一列標題（含產生時間），第二列表頭（粗體），其後逐日一列：

```
日期 | 星期 | 台股交易日 | 美股交易日 | 英股交易日 | 台股假日 | 美股假日 | 英股假日
```

- 交易日欄以「○」（交易）／「休」（非交易）表示；假日欄填該市場國定假日名稱（無則空）。欄寬 autosize。

### API 端點

```
# business-services（新）— TradingCalendarExportController，全域公開資料操作、無 owner 過濾
POST /api/trading-calendar-export/run     # ?year=&format=json|excel&subpath=  產檔寫入指定目錄，回 {path,sizeBytes,format,year,totalDays}
GET  /api/trading-calendar-export/browse  # ?subpath=  唯讀列基底（家目錄）下子目錄清單（樹狀選擇器懶載入）

# BFF（TradingCalendarBffController，沿用 businessServicesClient）
POST /api/bff/trading-calendar/export         → POST /api/trading-calendar-export/run
GET  /api/bff/trading-calendar/export/browse  → GET  /api/trading-calendar-export/browse
GET  /api/bff/trading-calendar                 # 既有（休市日 + 市場狀態），不變
GET  /api/bff/trading-calendar/market-status   # 既有，不變
```

> 本頁為已登入者皆可讀寫的公開資訊操作，落 BFF `.anyExchange().authenticated()`，不需 ADMIN。

### 關鍵業務邏輯

- **資料單一來源**：交易日曆判斷全走 `MarketDataService`——`getTwHolidays/getUsHolidays/getUkHolidays(year)` 取假日對照表、`isTwTradingDay/isUsTradingDay/isUkTradingDay(date)`（平日且非該市場國定假日）判交易日；與頁面日曆格、Dashboard、市場狀態同一權威來源，前端只 render 不重算。
- **格式驗證**：`format` 僅接受 `json`／`excel`（大小寫不敏感），其餘丟 `IllegalArgumentException` → `GlobalExceptionHandler` 對映 400。
- **路徑安全（沿用 Requirement 34）**：`resolveDir(sub)` = `base = Path.of(EXPORT_OUTPUT_DIR).toAbsolutePath().normalize()`；`target = base.resolve(sub).normalize()`；`!target.startsWith(base)` 則拒（防 `..`／絕對路徑跳脫）。`browse` 同一驗證、僅列子目錄（隱藏 dotfiles、依名稱排序）、不讀檔內容。子路徑不存在時寫檔前 `Files.createDirectories` 建立。
- **原子寫檔**：先寫 `交易日曆_{year}.{ext}.tmp` 再 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`，避免同年度覆寫時出現部分寫入殘檔（比照 external-materials `NewsPoller.exportPublicInfoJson` 範式）。
- **非 owner-scoped**：交易日曆為市場公開資料、輸出為檔案系統操作，`TradingCalendarExportService` 不注入 `CurrentUserContext`、不做 owner 過濾（與 `MarketDataController` 假日端點一致）；存取控制僅靠 BFF 的登入驗證。

### 本次增修

- **business-services**：新增 `service/TradingCalendarExportService`（注入 `MarketDataService` ＋ `ObjectMapper` ＋ `@Value EXPORT_OUTPUT_DIR`）、`controller/TradingCalendarExportController`（`/run`、`/browse`）、`dto/TradingCalendarExportDto`（`RunResponse`／`BrowseResponse`／`DirEntry`）。沿用既有 Apache POI 依賴，無新增依賴、無 DB migration。
- **BFF**：`TradingCalendarBffController` 新增 `POST /export`、`GET /export/browse` 兩個 passthrough。
- **frontend**：`api/index.js` `tradingCalendar` 加 `exportToDir`／`browseExportDir`；`TradingCalendarView.vue` 日曆卡標題加「匯出」按鈕＋匯出對話框（年度／格式／資料夾樹狀選擇器，選擇器邏輯比照 `AssetHistoryView.vue`）。

### 每日排程自動匯出（Task 190）

即時匯出（`/run`）之外，新增「每日指定時間自動匯出當前年度交易日曆」的 per-user 排程，比照 Requirement 34 的排程機制，但因交易日曆為**全域資料**，背景 tick 產檔時無需 owner 資料過濾（僅設定表 owner-scoped）。

```
[匯出對話框排程區塊] 啟用開關 + 每日時間 + 儲存排程
  → bffApi.tradingCalendar.{getExportSchedule,updateExportSchedule}
    → BFF GET/PUT /api/bff/trading-calendar/export/schedule
      → business GET/PUT /api/trading-calendar-export/schedule
        → TradingCalendarExportScheduleService（owner-scoped：HTTP 帶 X-User-* → ownerFilter）

[背景排程] business TradingCalendarExportScheduleService
  @Scheduled(cron="0 * * * * *", zone=Asia/Taipei) 每分鐘 poll
    for each trading_calendar_export_schedule（背景無 request → 讀全部列）:
      if enabled && last_run_date != today && now >= (run_hour:run_minute):
        TradingCalendarExportService.exportToDir(當前西元年, format, output_subpath)  // 全域資料，無需 enableFilter
        update last_run_date/last_run_at/last_run_status
  @EventListener(ApplicationReadyEvent) 開機自癒：補跑「今日已到點但未執行」者
```

**資料模型** `trading_calendar_export_schedule`（Liquibase `v1.56.0-trading-calendar-export-schedule.sql`（logicalFilePath 維持 v1.55.0）；每 owner 一列、`@Filter(ownerFilter)`）：

```
id              BIGSERIAL PK
owner_user_id   BIGINT       NOT NULL UNIQUE
enabled         BOOLEAN      NOT NULL DEFAULT FALSE
run_hour        INT          NOT NULL DEFAULT 8       -- 0..23
run_minute      INT          NOT NULL DEFAULT 0       -- 0..59
format          VARCHAR(10)  NOT NULL DEFAULT 'json'  -- json / excel（CHECK 約束）
output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input'
last_run_date   DATE                                  -- 當日 guard
last_run_at     TIMESTAMP
last_run_status VARCHAR(500)                          -- 「成功：/path」或「失敗：訊息」
updated_at      TIMESTAMP
```

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

**API 端點（新增）：**

```
# business-services
GET  /api/trading-calendar-export/schedule   # 取當前使用者排程設定（無則回預設，不寫入）
PUT  /api/trading-calendar-export/schedule   # upsert（enabled/runHour/runMinute/format/outputSubpath）

# BFF
GET  /api/bff/trading-calendar/export/schedule  → GET /api/trading-calendar-export/schedule
PUT  /api/bff/trading-calendar/export/schedule  → PUT /api/trading-calendar-export/schedule
```

**新增檔案**：`model/TradingCalendarExportSchedule`、`repository/TradingCalendarExportScheduleRepository`、`service/TradingCalendarExportScheduleService`（CRUD＋`@Scheduled` tick＋selfHeal）、`TradingCalendarExportDto` 加 `ScheduleSettingRequest`／`ScheduleSettingResponse`、`TradingCalendarExportController` 加 `GET/PUT /schedule`；BFF 加 2 passthrough；前端匯出對話框加排程區塊（`getExportSchedule`／`updateExportSchedule`）。

---

## Requirement 39（Task 196）：已實現損益 Excel 匯出到指定目錄與每日排程自動匯出

### 架構與資料流

沿用 Requirement 34（歷年資產排程匯出）的整體形狀，差異只在「匯出哪份活頁簿」與「目錄瀏覽改為複用」。

```
【手動下載（既有，不變）】
RealizedGainView「匯出 Excel」
  → GET /api/bff/realized-gain/export (blob)
    → business GET /api/realized-gains/export
      → ExcelExportService.exportRealizedGains()        ← HTTP：TenantFilterAspect 自動 owner-scoped

【立即匯出到目錄（新增）】
RealizedGainView「立即匯出到目錄」
  → POST /api/bff/realized-gain/export/run-now
    → business POST /api/realized-gains/export/run-now
      → RealizedGainExportScheduleService.runNowForCurrentUser()
        → ExcelExportService.exportRealizedGains()      ← HTTP 情境，同上自動 owner-scoped
        → writeToDir(ownerId, subpath, data)

【每日排程（新增）】
@Scheduled(cron="0 * * * * *", zone="Asia/Taipei") tick()
  → runDueExports(): settingRepo.findAll()             ← 背景無 request context，讀全部 owner 列
    → 對每個 enabled 且今日未跑且已到點的列：
      → ExcelExportService.exportRealizedGainsForOwner(ownerId)   ← 手動 enableFilter 縮到該 owner
      → writeToDir(ownerId, subpath, data)

【資料夾瀏覽（複用既有 business 端點）】
RealizedGainView el-tree 懶載入
  → GET /api/bff/realized-gain/export/browse?subpath=  ← 本頁自己的 BFF 路由（一頁一 BFF）
    → business GET /api/export-schedule/browse         ← 複用 Requirement 34 既有端點，不新增
```

### 關鍵設計決策

1. **一功能一張排程表**：新增 `realized_gain_export_schedule`，比照 `trading_calendar_export_schedule`（Requirement 37）與 `export_schedule_setting`（Requirement 34）的既有慣例，不把三者合併成帶 `export_type` 判別欄的共用表。理由：各排程的欄位語意與產出內容不同（交易日曆多一個 `format`），且合併需改動既有兩個需求的 UNIQUE 約束與既有列，風險大於收益。

2. **目錄瀏覽複用、不複製**：`GET /api/export-schedule/browse` 的語意是「列出基底家目錄下某子路徑的子目錄」——與頁面無關的通用能力。依 CLAUDE.md「不同頁面顯示同樣意義的值，BFF 必須呼叫同一支 business service API」，本頁 BFF 直接 passthrough 至該既有端點，**不在 business 端新增第二支 browse**。（Requirement 37 交易日曆當初自建了一份 browse，屬既有重複；本需求不再擴大該重複。）

3. **背景排程的租戶隔離（與 Requirement 37 的關鍵差異）**：`RealizedGain` 帶 `@Filter(ownerFilter)`。交易日曆是全域市場資料，背景產檔不需資料過濾；已實現損益是 per-user 資料，背景 `findAll()` 若不 `enableFilter` 會把**所有使用者的損益寫進每個人的檔案**。故新增 `exportRealizedGainsForOwner(Long ownerId)`，比照既有 `exportFullForOwner` / `exportLiveAssetsForOwner` 在 session 手動啟用 filter。

4. **排程與手動產出同一份**：`exportRealizedGains()` 與 `exportRealizedGainsForOwner()` 共用同一個 `buildRealizedGainsWorkbook()`，兩者只差 filter 啟用方式，確保三個入口（下載／run-now／排程）內容一致。

### 資料模型

新表 `realized_gain_export_schedule`（Liquibase `v1.59.0-realized-gain-export-schedule.sql`）：

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `owner_user_id` | BIGINT NOT NULL | 擁有者；UNIQUE `uq_rg_export_schedule_owner`（每人一列） |
| `enabled` | BOOLEAN NOT NULL DEFAULT FALSE | 是否啟用每日排程 |
| `run_hour` | INT NOT NULL DEFAULT 8 | 每日執行時，CHECK 0..23 |
| `run_minute` | INT NOT NULL DEFAULT 0 | 每日執行分，CHECK 0..59 |
| `output_subpath` | VARCHAR(255) NOT NULL DEFAULT 'input' | 相對家目錄基底的輸出子路徑 |
| `last_run_date` | DATE | 當日已執行 guard（成功／失敗都設） |
| `last_run_at` | TIMESTAMP | 上次執行時間 |
| `last_run_status` | VARCHAR(500) | 「成功：/path」或「失敗：訊息」 |
| `updated_at` | TIMESTAMP | |

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

### API 端點

| 層 | 方法 路徑 | 說明 |
|----|-----------|------|
| business | `GET /api/realized-gains/export` | （既有）下載 xlsx |
| business | `GET /api/realized-gains/export/schedule` | 取當前使用者排程設定（無則回預設，不寫 DB） |
| business | `PUT /api/realized-gains/export/schedule` | upsert 當前使用者排程設定（驗證時分範圍與子路徑不跳脫） |
| business | `POST /api/realized-gains/export/run-now` | 立即產檔到設定目錄，回 `{path, sizeBytes}`；不動當日 guard |
| business | `GET /api/export-schedule/browse?subpath=` | （既有，複用）列出基底下子目錄 |
| BFF | `GET /api/bff/realized-gain/export` | （既有）passthrough 下載 |
| BFF | `GET /api/bff/realized-gain/export/schedule` | passthrough |
| BFF | `PUT /api/bff/realized-gain/export/schedule` | passthrough |
| BFF | `POST /api/bff/realized-gain/export/run-now` | passthrough |
| BFF | `GET /api/bff/realized-gain/export/browse` | passthrough 至 business `/api/export-schedule/browse`（URI template 展開，subpath 需 URL-encode） |

### 新增／異動檔案

**新增**
- `backend/.../model/RealizedGainExportSchedule.java`
- `backend/.../repository/RealizedGainExportScheduleRepository.java`
- `backend/.../service/RealizedGainExportScheduleService.java`（tick／self-heal／run-now／設定 CRUD／路徑驗證）
- `backend/.../controller/RealizedGainExportController.java`（`@RequestMapping("/api/realized-gains/export")`）
- `backend/.../dto/RealizedGainExportDto.java`
- `backend/src/main/resources/db/changelog/changes/v1.59.0-realized-gain-export-schedule.sql`

**異動**
- `ExcelExportService.java`：抽出 `buildRealizedGainsWorkbook()`，新增 `exportRealizedGainsForOwner(Long)`
- `db.changelog-master.yaml`：註冊 v1.59.0
- `RealizedGainBffController.java`：新增 schedule／run-now／browse 四支 passthrough
- `frontend/src/api/index.js`：`realizedGain` 命名空間新增 4 支
- `frontend/src/views/RealizedGainView.vue`：新增「排程自動匯出」設定卡（開關／時間／資料夾樹／立即匯出／上次結果）
- `SchedulePublicBffController.java`：`JOBS` 補「已實現損益匯出 每日匯出排程檢查」項目

### 端點路徑共存說明

`RealizedGainController` 既有 `@GetMapping("/export")`（在 `@RequestMapping("/api/realized-gains")` 下）＝ `/api/realized-gains/export`；新 `RealizedGainExportController` 掛 `/api/realized-gains/export` 並以 `/schedule`、`/run-now` 為子路徑 ＝ `/api/realized-gains/export/schedule`。兩者路徑不同、無 ambiguous mapping。

## Requirement 40（Task 202）：公開資訊「油價金價」十年歷史曲線與 Excel 匯出

### 資料來源與標的

| commodity_code | 名稱 | Yahoo symbol | 單位 |
|---|---|---|---|
| `WTI` | 西德州原油 | `CL=F` | USD / 桶 |
| `BRENT` | 布蘭特原油 | `BZ=F` | USD / 桶 |
| `GOLD` | COMEX 黃金 | `GC=F` | USD / 盎司 |

Yahoo chart API：`https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?period1=&period2=&interval=1d`，
以 `America/New_York` 時區換算 epoch 秒；`indicators.quote[0].close[i]` 為當日收盤，`null` 者略過（未成交日）。
實測 `range=10y&interval=1d` 三個 symbol 皆可一次取得約 2,500 筆日線，足以一次補滿十年。

**呼叫方式**：`ProcessBuilder("curl", "-s", "-H", "User-Agent: Mozilla/5.0", url)` 子程序。
Yahoo 對 Java `HttpClient` 的 HTTP/2 TLS fingerprint 會回 RST_STREAM／429，且**長 Chrome UA 反而被 WAF 擋**，
必須用短 UA（同 `YahooFxFetchClient` / `BotFxFetchClient` 慣例）。

### 資料模型

```sql
CREATE TABLE commodity_price_history (
    id             BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    commodity_code VARCHAR(20)   NOT NULL,   -- WTI / BRENT / GOLD
    price_date     DATE          NOT NULL,
    close_price    NUMERIC(12,4) NOT NULL,   -- 僅存原始收盤價
    CONSTRAINT uq_commodity_price_code_date UNIQUE (commodity_code, price_date)
);
CREATE INDEX idx_commodity_price_code_date ON commodity_price_history (commodity_code, price_date);
```

無 `owner_user_id`（全域公開行情，比照 `exchange_rate_history` / `stock_price_history`）。
不存 open/high/low/volume——本需求只畫收盤曲線與匯出收盤價，存了即為未使用欄位；
亦不存漲跌／漲跌幅（衍生值，CLAUDE.md 禁止）。

### 分層與資料流

```

CommodityFetchClient（curl + 短 UA，純抓取，失敗回空 List）
    ↓
CommodityPricePoller（@Scheduled 06:30 Asia/Taipei，每日增量）
    ↓
StockSourceQuery.upsertCommodityPrice / findMaxCommodityDate / findMinCommodityDate（JdbcTemplate）
    ↓
[commodity_price_history]
    ↑
CommodityPriceHistory（JPA entity）→ CommodityPriceHistoryRepository
    ↑
MarketDataController /api/market-data/commodity*  ← HistoricalDataService（proxy 至 ext /internal/*）
    ↑
CommodityPriceBffController /api/bff/commodity-price*
    ↑
CommodityPriceView.vue
```

`HistoricalBackfillService.startupBackfill()` 追加：三標的各查 `findMinCommodityDate`，
若無資料或最早日晚於 `now-10y`，則 `backfillCommodityFrom(code, now-10y)` 補滿十年。
`HistoricalDataService.purgeOldHistory()`（每交易日 17:30）追加刪除 10 年前的 `commodity_price_history`。

### 匯出設計

匯出走 **business 產檔 → BFF passthrough → 前端另存**：

- `ExcelExportService.exportCommodityPrices(LocalDate start, LocalDate end)` 產 `byte[]`：
  單張 sheet「油價金價」，表頭 `日期／WTI原油(USD/桶)／布蘭特原油(USD/桶)／黃金(USD/盎司)`。
  以 `TreeMap<LocalDate, BigDecimal[]>` 對三序列做 **outer join**（三個市場假日不完全重疊，
  inner join 會漏掉單一市場有報價的日子），缺值留空白格。日期用 `yyyy-MM-dd` 文字格式避免
  Excel 時區偏移，價格用既有 `num4`（`#,##0.0000`）樣式。
- 端點回 `byte[]` + `Content-Disposition: attachment`（同既有匯出慣例）。
- 前端拿到 blob 後優先 `showSaveFilePicker({ suggestedName, types })` 讓使用者選目錄與檔名，
  寫入用 `createWritable()`；`AbortError`（使用者取消）靜默結束，其餘錯誤或 API 不存在則
  退回 anchor-click 下載。**這是本專案第一處使用 File System Access API**——既有匯出（R34/37/39）
  是「後端寫進容器目錄」模型，適用排程自動留存；本頁為一次性手動匯出，瀏覽器對話框才對應
  使用者真正的本機目錄。

### 圖表設計（雙 Y 軸）

油價量級約 20–130、金價約 1,000–5,600，共用單一 Y 軸會使油價貼底成直線。故：

- `yAxis[0]`（左）：油價 USD/桶，`scale: true`；`yAxis[1]`（右）：金價 USD/盎司，`scale: true`
- series：WTI `#16a34a`（綠）／Brent `#78716c`（暖灰）→ `yAxisIndex: 0`；GOLD `#eab308`（金）→ `yAxisIndex: 1`
  （WTI 原為琥珀 `#f59e0b`，與金價的 `#eab308` 在圖例上過於接近難以分辨，依使用者要求改綠）
- `symbol: 'none'`、`lineWidth: 1.5`（十年 2,500 點，畫 symbol 會嚴重掉幀）、`dataZoom` inside + slider
- tooltip `trigger: 'axis'`，自訂 formatter 標註各序列單位

區間快切（1M/3M/6M/1Y/3Y/5Y/10Y）為 `computed` 記憶體切片（日期字串直接比較），不重打 API，
同 `ExchangeRateView` 慣例。

### API 端點

| 層 | 端點 | 說明 |
|---|---|---|
| ext | `POST /internal/backfill/commodity?code=&since=` | 增量回補（自 `max(price_date)+1`） |
| ext | `POST /internal/backfill/commodity-from?code=&since=` | 強制自 `since` 回補（補中間缺漏／首次補十年） |
| business | `GET /api/market-data/commodity?start=&end=` | 三標的區間歷史，回 `{WTI:[...], BRENT:[...], GOLD:[...]}` |
| business | `POST /api/market-data/commodity/refresh` | 手動刷新：三標的增量回補 ＋ 清理十年前 |
| business | `GET /api/market-data/commodity/export?start=&end=` | 產 `.xlsx`（byte[] + Content-Disposition） |
| BFF | `GET /api/bff/commodity-price` | 開頁載入：先 refresh（失敗不擋）再回近十年三序列 |
| BFF | `POST /api/bff/commodity-price/refresh` | 手動刷新 passthrough |
| BFF | `GET /api/bff/commodity-price/export?start=&end=` | passthrough 下載 |

### 新增／異動檔案

**新增**
- `external-materials-service/.../client/CommodityFetchClient.java`
- `external-materials-service/.../service/CommodityPricePoller.java`
- `backend/.../model/CommodityPriceHistory.java`
- `backend/.../repository/CommodityPriceHistoryRepository.java`
- `backend/src/main/resources/db/changelog/changes/v1.60.0-commodity-price-history.sql`
- `bff/.../commodityprice/CommodityPriceBffController.java`
- `frontend/src/views/CommodityPriceView.vue`

**異動**
- `StockSourceQuery.java`：`upsertCommodityPrice` / `findMaxCommodityDate` / `findMinCommodityDate`
- `HistoricalBackfillService.java`：`backfillCommodity` / `backfillCommodityFrom`，`startupBackfill` 追加三標的
- `InternalPriceController.java`：兩支 `/internal/backfill/commodity*`
- `HistoricalDataService.java`：proxy 兩支 ＋ `purgeOldHistory` 追加清理
- `MarketDataController.java`：三支 `/api/market-data/commodity*`
- `ExcelExportService.java`：`exportCommodityPrices(start, end)` ＋ `writeCommoditySheet`
- `db.changelog-master.yaml`：註冊 v1.60.0
- `frontend/src/api/index.js`：`commodityPrice` 命名空間
- `frontend/src/router/index.js`、`frontend/src/App.vue`：路由與「公開資訊」選單項
- `SchedulePublicBffController.java`：`JOBS` 補「油價金價 每日回補」

## Requirement 41（Task 203）：油價金價 Excel 排程自動匯出到指定目錄

### 與既有三套匯出排程的定位

| 需求 | 資料範圍 | 背景產檔是否需 owner 過濾 | 產檔方法 |
|---|---|---|---|
| R34 歷年資產 | per-user | 需要 | `exportLiveAssetsForOwner(ownerId)` |
| R39 已實現損益 | per-user | 需要 | `exportRealizedGainsForOwner(ownerId)` |
| R37 交易日曆 | 全域 | 不需要 | `exportTradingCalendar(year)` |
| **R41 油價金價** | **全域** | **不需要** | `exportCommodityPrices(start, end)` |

`commodity_price_history` 無 `owner_user_id`、未套 `@Filter(ownerFilter)`，背景 cron 無 request context 也讀得到完整資料，
故**不需要**新增 `ForOwner` 變體——排程設定 per-user，但資料本身全域（同 R37）。
這是本需求與 R39 最容易抄錯的一點：R39 若漏了 `enableFilter` 會外洩他人損益，R41 若照抄反而是多餘的。

### 資料模型

```sql
CREATE TABLE commodity_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,      -- @Filter(ownerFilter)，每人一列
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    range_months    INT,                        -- NULL ＝ 全部十年
    last_run_date   DATE,                       -- 當日 guard
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_commodity_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_commodity_export_schedule_hour   CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_commodity_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59),
    CONSTRAINT ck_commodity_export_schedule_range  CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120)
);
```

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

### 滾動時間範圍

`range_months` 讓排程產出隨時間滾動，而非固定區間：

```
end   = LocalDate.now(Asia/Taipei)
start = range_months == null ? end.minusYears(10) : end.minusMonths(range_months)
```

前端選項對應 `1／3／6／12／36／60／120（全部十年）`，預設 `120`。

**「全部十年」在前端以 `120` 而非 `null` 表示**：Element Plus 的 `el-select` 預設把 `null` 視為
empty value（`DEFAULT_EMPTY_VALUES` 含 `null`），綁 `null` 時 `hasModelValue` 為 false，
欄位會渲染灰色 placeholder 而非選項標籤——使用者無法分辨「已選全部十年」與「尚未選擇」（值本身仍正確，
純顯示層失真）。因 `end.minusMonths(120)` 與 `end.minusYears(10)` 等價、且 CHECK 允許 `1..120`，
改用 `120` 語意零變動且不必依賴 `:empty-values` 這類版本相依的 prop。
後端仍保留 `range_months IS NULL` 分支，以相容從未儲存過設定的列（entity 預設即 null）。
手動匯出（R40）仍為使用者自選絕對起訖日期——兩者語意不同：手動取的是「某段歷史」，排程留的是「最近 N 個月」。

### 寫檔與路徑安全

沿用 R34／37／39 的路徑模型與驗證，**不新增第四份 `browse` 實作**：

- 基底：`@Value("${EXPORT_OUTPUT_DIR:/home/steven}")`，docker volume 對映 host 家目錄
- `resolveDir(subpath)`：`base.resolve(subpath).normalize()` 後必須 `startsWith(base)`，否則 `IllegalArgumentException` → 400
- 寫檔比照 R37 `writeAtomically`：先寫 `filename + ".tmp"`，再 `Files.move(..., ATOMIC_MOVE)`，
  不支援時退 `REPLACE_EXISTING`。避免覆寫既有檔時中途失敗留下半截殘檔
- 目錄列舉沿用 business 既有 `GET /api/export-schedule/browse?subpath=`（R34），BFF 僅新增自己的 passthrough 路由

### 排程執行機制

比照 R34／37／39：

- `@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")` 每分鐘 poll
- 判斷式為 `now >= 設定時分` ＋ `last_run_date != today`，**非分鐘精確相等**——
  排程執行緒被長工作卡住跨分鐘時，精確相等會整日靜默漏跑
- `AtomicBoolean ticking` 防重入
- `@EventListener(ApplicationReadyEvent.class)` 重啟自癒，補跑當日已到點未執行者
- 單一使用者失敗只記 `last_run_status` ＋ log，不中斷其他使用者；成功或失敗**都**設當日 guard，
  避免失敗後整天每分鐘重試
- run-now 不動當日 guard（驗證路徑用，不應吃掉當日排程）

### API 端點

| 層 | 端點 | 說明 |
|---|---|---|
| business | `GET /api/commodity-export/schedule` | 讀當前使用者排程設定（無則回預設值） |
| business | `PUT /api/commodity-export/schedule` | upsert（驗證時分範圍、range_months、子路徑不跳脫） |
| business | `POST /api/commodity-export/run-now` | 立即產檔到設定目錄，回 `{path, sizeBytes}`；不動當日 guard |
| business | `GET /api/export-schedule/browse?subpath=` | （既有，複用）列出基底下子目錄 |
| BFF | `GET /api/bff/commodity-price/export/schedule` | passthrough |
| BFF | `PUT /api/bff/commodity-price/export/schedule` | passthrough |
| BFF | `POST /api/bff/commodity-price/export/run-now` | passthrough |
| BFF | `GET /api/bff/commodity-price/export/browse` | passthrough 至 business `/api/export-schedule/browse` |

### 新增／異動檔案

**新增**
- `backend/.../model/CommodityExportSchedule.java`
- `backend/.../repository/CommodityExportScheduleRepository.java`
- `backend/.../service/CommodityExportScheduleService.java`（tick／self-heal／run-now／設定 CRUD／路徑驗證／atomic write）
- `backend/.../controller/CommodityExportController.java`（`@RequestMapping("/api/commodity-export")`）
- `backend/.../dto/CommodityExportDto.java`
- `backend/src/main/resources/db/changelog/changes/v1.61.0-commodity-export-schedule.sql`

**異動**
- `db.changelog-master.yaml`：註冊 v1.61.0
- `CommodityPriceBffController.java`：新增 schedule／run-now／browse 四支 passthrough
- `frontend/src/api/index.js`：`commodityPrice` 命名空間新增 4 支
- `frontend/src/views/CommodityPriceView.vue`：新增「排程自動匯出」設定卡（開關／時間／範圍／資料夾樹／立即匯出／上次結果）
- `SchedulePublicBffController.java`：`JOBS` 補「油價金價匯出 每日匯出排程檢查」項目

---

## Requirement 42（Task 204）：台幣兌美元匯率 Excel 匯出與排程自動匯出到指定目錄

結構完全比照 R41（油價金價），因兩者性質相同：**全域公開行情 ＋ per-user 排程設定**。
以下只記與 R41 的差異；未提及處即與 R41 一致。

### 與既有四套匯出排程的定位

| 需求 | 資料範圍 | 背景產檔是否需 owner 過濾 | 產檔方法 |
|---|---|---|---|
| R34 歷年資產 | per-user | 需要 | `exportLiveAssetsForOwner(ownerId)` |
| R39 已實現損益 | per-user | 需要 | `exportRealizedGainsForOwner(ownerId)` |
| R37 交易日曆 | 全域 | 不需要 | `exportTradingCalendar(year)` |
| R41 油價金價 | 全域 | 不需要 | `exportCommodityPrices(start, end)` |
| **R42 台幣兌美元** | **全域** | **不需要** | `exportExchangeRates(currency, start, end)` |

`exchange_rate_history` 無 `owner_user_id`、未套 `@Filter(ownerFilter)`，故同 R41 不需 `ForOwner` 變體。

### 匯出內容與 midRate 的取得方式

工作表「台幣兌美元」四欄：**日期／即期買入／即期賣出／中間價**，單一序列依日期遞增，
不需要 R41 那種跨標的 outer join（該處三個標的分屬 NYMEX／COMEX、交易日不重疊才需要）。

**中間價必須由 entity 計算，不可回填成 DB 欄位**：`mid_rate` 原為實體欄位，已於
`v1.9.4-drop-redundant-columns.sql` 移除（完整正規化：`(buy+sell)/2` 可由其他欄位算出）。
`ExchangeRateHistory#getMidRate()` 是 `@Transient` getter（scale 4、HALF_UP，單邊 null 時取另一邊），
匯出走該 getter。**不可**在 JPQL/SQL 選取或 `ORDER BY mid_rate`——欄位不存在，會在 runtime 才炸。

買入／賣出皆 `numeric(10,4)` 且 nullable，沿用 `Styles.num4`；null 該格留空（同 R41 不補前值）。
日期以 ISO 文字寫入（非 date cell），避免開啟端時區偏移一天。

### 幣別維度的取捨

排程表**不設 `currency` 欄**，`ExchangeRateExportScheduleService` 以常數 `USD` 產檔。理由：
本頁是「台幣兌美元」單一幣別頁，多幣別頁面目前不存在，加欄等於為不存在的需求預留未使用欄位。
business 匯出端點仍保留 `currency` 參數（預設 `USD`、`CURRENCY_PATTERN` 驗證），與同檔其他匯率端點一致；
差別在於「查詢端點對外可帶幣別」vs「排程設定不暴露該維度」。日後真要多幣別再加欄補 migration。

**但保留參數就必須讓標籤跟著幣別走。** 初版把工作表名與檔名寫死「台幣兌美元」，端到端驗證實測
`currency=ZAR` 會匯出正確的 ZAR 資料（22 列，與 DB 相符）卻標示為美元——資料對、標籤錯的靜默誤標。
修正為單一來源 `ExcelExportService.exchangeRateLabel(currency)`（`USD` → `台幣兌美元`，其餘 → `台幣兌{幣別}`），
由工作表名、手動匯出檔名、排程檔名三處共用（CLAUDE.md「同義欄位、同一來源」）。
審查時「前端恆送 USD 所以不影響」的辯護不成立：那描述的是當下呼叫者，不是 API 契約。

### 資料模型

```sql
CREATE TABLE exchange_rate_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,      -- @Filter(ownerFilter)，每人一列
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    range_months    INT,                        -- NULL ＝ 全部十年
    last_run_date   DATE,                       -- 當日 guard
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_exchange_rate_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_exchange_rate_export_schedule_hour   CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_exchange_rate_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59),
    CONSTRAINT ck_exchange_rate_export_schedule_range  CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120)
);
```

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

滾動範圍、路徑安全（`resolveDir` + `startsWith(base)`）、`writeAtomically`、
每分鐘 poll ＋ 當日 guard ＋ `ApplicationReadyEvent` 自癒 ＋ `AtomicBoolean` 防重入、
run-now 不動當日 guard——全部同 R41，不重述。
檔名 `台幣兌美元_{使用者ID}_{YYYYMMDD}.xlsx`。

### API 端點

| 層 | 端點 | 說明 |
|---|---|---|
| business | `GET /api/market-data/exchange-rate/export?currency=&start=&end=` | 產出 .xlsx（UTF-8 檔名、`ByteArrayResource`） |
| business | `GET /api/exchange-rate-export/schedule` | 讀當前使用者排程設定（無則回預設值） |
| business | `PUT /api/exchange-rate-export/schedule` | upsert（驗證時分、range_months、子路徑不跳脫） |
| business | `POST /api/exchange-rate-export/run-now` | 立即產檔到設定目錄，回 `{path, sizeBytes}`；不動當日 guard |
| business | `GET /api/export-schedule/browse?subpath=` | （既有，複用）列出基底下子目錄 |
| BFF | `GET /api/bff/exchange-rate/export?start=&end=` | passthrough 下載（原樣轉出 Content-Disposition） |
| BFF | `GET`／`PUT /api/bff/exchange-rate/export/schedule` | passthrough |
| BFF | `POST /api/bff/exchange-rate/export/run-now` | passthrough |
| BFF | `GET /api/bff/exchange-rate/export/browse` | passthrough 至 business `/api/export-schedule/browse` |

排程設定端點掛獨立前綴 `/api/exchange-rate-export`（同 R41 的 `/api/commodity-export`），
不掛在 `/api/market-data/exchange-rate` 之下——後者是全域行情查詢，前者是 per-user 設定，語意不同。

### 新增／異動檔案

**新增**
- `backend/.../model/ExchangeRateExportSchedule.java`
- `backend/.../repository/ExchangeRateExportScheduleRepository.java`
- `backend/.../service/ExchangeRateExportScheduleService.java`
- `backend/.../controller/ExchangeRateExportController.java`（`@RequestMapping("/api/exchange-rate-export")`）
- `backend/.../dto/ExchangeRateExportDto.java`
- `backend/src/main/resources/db/changelog/changes/v1.62.0-exchange-rate-export-schedule.sql`

**異動**
- `db.changelog-master.yaml`：註冊 v1.62.0
- `ExcelExportService.java`：新增 `exportExchangeRates` ＋ `writeExchangeRateSheet`
- `MarketDataController.java`：新增 `GET /exchange-rate/export`
- `ExchangeRateBffController.java`：新增 export／schedule／run-now／browse 五支 passthrough
- `frontend/src/api/index.js`：`exchangeRate` 命名空間新增 5 支
- `frontend/src/views/ExchangeRateView.vue`：新增「匯出 Excel」按鈕＋匯出對話框＋「排程自動匯出」設定卡＋資料夾選擇器
- `SchedulePublicBffController.java`：`JOBS` 補「台幣兌美元匯出 每日匯出排程檢查」項目

---

## Requirement 43：今日交易雷達（純本地規則、零 AI API）

### 架構與請求鏈

```text
TradingRadarView
  → GET /api/bff/trading-radar
  → TradingRadarBffRoutes（純 rewrite）
  → GET /api/trading-radar
  → TradingRadarService
       ├─ TwseIndexDailyHistoryRepository（大盤完成日 K）
       ├─ StockRepository + AssetClassifier（有效 STOCK／BOND 類別）
       ├─ StockDividendHistoryRepository（完成日 K 區間內除權息事件）
       ├─ DistributionAdjustedPriceService（還原權息 OHLC 純計算）
       ├─ TechnicalIndicatorService（以同一序列計算 MA20／60／240、KD；大盤走 twse_index_daily_history + Redis 即時價）
       ├─ AssetSnapshotRepository.findLatestWithStocks（當前持股，owner-scoped）
       ├─ StockAlertRepository.findDistinctStockCodeMarket（觀察，owner-scoped）
       ├─ PriceQueryService（只讀 Redis；miss → stock_price_history；`0000/台股` 自 Task 228 起亦可命中）
       └─ TradingRadarRuleEngine（TW_RULES_V3 分數規則不變，RULE_VERSION 現為 TW_RULES_V7）
```

`GET /api/trading-radar` 這條請求鏈**刻意不注入** `MarketAnalysisService`、LLM SDK、新聞爬蟲或任何 refresh endpoint，只重讀既有資料，不對外抓行情、不送出 Batch、不產生 AI 費用；SSE 盤中自動更新走的也是這條。**Task 249 起，使用者手動按下「重新整理」改走 `POST /api/trading-radar/refresh`，會同步觸發一次台股行情回補後才重算**（見下方「手動重新整理觸發行情回補」小節）；該路徑仍不注入任何 LLM client、不觸發新聞爬蟲、不送出 Batch、不產生 AI 費用。大盤即時點位在 `GET` 路徑上同樣只由獨立背景排程（`TaiexIndexPoller`，見「大盤新鮮度與盤中即時判斷」小節）寫入 Redis。現有行情／大盤排程若在背景更新 PostgreSQL 或 Redis，雷達下次讀取自然看見新值；兩者生命週期分離。

### 標的選取與 owner 隔離

`TradingRadarService.loadTargets()` 取最新快照 `findLatestWithStocks()` 中 `shares > 0` 的台股，再 union `stock_alert` 衍生觀察清單中的台股，key 為 `code + '\0' + market`，保留穩定順序並排除 `0000/台股`。最新快照 root `AssetSnapshot` 與觀察 root `StockAlert` 均帶 `@Filter(ownerFilter)`，由既有 `TenantFilterAspect` 依 BFF 傳入的 `X-User-Id` 啟用；不可改為直接由無 filter 的 `StockHoldingRepository` 全表查詢。美／英股不進規則引擎，只累加 `skippedNonTwStocks` 供前端說明。

### 資料模型（不入庫）

新增 `TradingRadarDto` 純 response records：

- `Response`：`ruleVersion`、`generatedAt`、`market`、`stocks`、`skippedNonTwStocks`。
- `MarketSummary`：`regime`、`regimeLabel`、`score`、`dataComplete`、`stale`（Task 217，見下方「大盤新鮮度與盤中即時判斷」）、`intraday`／`liveUpdatedAt`（Task 228，同小節）、`asOfDate`、點位／漲跌幅、MA20／60／240、K／D、MA60／240 兩日確認、`reasons`、`risks`。
- `StockDecision`：code／name／market、`assetClass`、`distributionAdjusted`、`held`、`action`／`actionLabel`、`score`、`counterTrendState`／`counterTrendLabel`、`counterTrendReasons`／`counterTrendRisks`、`dataComplete`、報價／漲跌幅／更新時間／`asOfDate`、MA20／60／240、K／D、MA20／60／240 兩日確認、`reasons`、`risks`。

無新 entity／table／migration；分數與建議皆為可重算的衍生值，不持久化，符合正規化原則。

### 還原權息技術序列（TW_RULES_V3）

交易雷達以 `stock_price_history` 最近 241 根完成日 K 為基礎；若 `PriceQueryService` 有「今日且尚未寫入完成日 K」的 live OHLC，再暫加於序列最前。接著查同一日期區間內 `stock_dividend_history.ex_dividend_date IS NOT NULL` 且現金配息或股票股利為正的事件，由 `DistributionAdjustedPriceService` 以日期升冪套用累積持股因子：

```text
eventFactor = 1 + stockDividend / 10 + cashDividend / eventDayClose
sharesAfterEvent = sharesBeforeEvent * eventFactor
adjustedOHLC(date) = rawOHLC(date) * sharesAtDate / finalShares
```

`eventDayClose` 取除息日（若該日缺 K，則下一根可用 K）的原始收盤，必須大於 0；無效／未來／區間外事件略過。最後除以 `finalShares`，保證最新一根 OHLC 與原始行情相同，歷史價格則消除現金與股票配息造成的機械缺口。沒有有效事件時直接回原序列且 `distributionAdjusted=false`，不製造浮點漂移。調整後的同一份 OHLC 同時供：

1. `TechnicalIndicatorService.computeFromSeries()` 計算 MA20／60／240、當期與前一期 KD；
2. `TradingRadarRuleEngine.confirm()` 計算 MA20／60／240 兩收盤日確認；
3. 規則引擎的單日漲跌（±5% 扣分、逆勢「停止續跌」）＝原始現價相對還原後前一根可比收盤；DTO `changePercent` 仍保留市場報價原始漲跌，兩者語意分離。

禁止只調 MA 不調 KD／確認／規則漲跌，否則會在同一筆決策中混用兩種價基。此調整只發生在交易雷達請求鏈，不覆寫 `stock_price_history`／`stock_dividend_history`，也不改其他頁面既有價格圖與行情漲跌的原始價口徑。

### 兩收盤日確認

`TradingRadarRuleEngine.confirm(closesDesc, period)` 至少需要 `period + 1` 根完成收盤：

- 最新日 SMA＝`close[0..period-1]` 平均；前一日 SMA＝`close[1..period]` 平均。
- 最新、前一收盤皆嚴格大於各自 SMA → `ABOVE`。
- 最新、前一收盤皆嚴格小於各自 SMA → `BELOW`。
- 一上一下或等於均線 → `MIXED`；資料不足 → `UNAVAILABLE`。

最新價相對均線的分數使用 `TechnicalIndicatorService.computeFromSeries()` 對還原權息序列算出的當前指標；若 Redis 有尚未入庫的今日盤中價，該 live K 只合入當前 MA／KD，不進兩日確認。兩日確認只讀完成日 K，避免盤中假突破被當成正式確認。

### 大盤新鮮度與盤中即時判斷（Task 217 stale gate → Task 228 即時化，`TW_RULES_V4`→`V6`）

**Task 217（V4，既有行為，先前未寫入本文件）：** `twse_index_daily_history` 只有完成日 OHLC，盤後批次（`TwseIndexPoller`）才寫入當日列。`TradingRadarService.buildMarket()` 以既有交易日曆（`MarketDataService.isTradingDay`）解析「當前台股交易日」`currentTwTradingDay()`：非交易日往回找最近一個交易日。若最新完成日 K 的 `tradingDate` 不等於這個值，`MarketSummary.stale=true`。stale 時：不給個股 `RISK_ON` 的 `+8` 加分、買進閘門一律關閉（不得產生 `BUY_CANDIDATE`／`ADD_CANDIDATE`）、但 `RISK_OFF` 的 `-15` 扣分與 veto 仍生效（只收緊不放寬，經 `TradingRadarRuleEngine.StockInput.marketStale` 傳遞）。

**Task 228（V6，本次修訂）：** 大盤加入 Redis 即時價（`price:台股:0000`），寫入者為 `external-materials-service` 新增的 `TaiexIndexPoller`：

```text
TaiexIndexPoller（週一～五 09:00–13:30 Asia/Taipei，每 2 分鐘，MarketClock.isTwMarketOpen() 守門）
  → MacroDataFetchClient.fetchIndexIntraday("TWSE")   ← 既有方法，Yahoo ^TWII 5 分 K（Requirement 18 同一來源）
  → 取最新一筆非 null 收盤點位；查無則整輪不寫（保留 Redis 上一輪真實值，比照個股 z='-' 慣例）
  → previousClose = StockSourceQuery.loadRecentTaiexCloses(1) 的最近一筆完成日收盤
  → PriceCacheWriter.write(PriceResult{code=0000, market=台股, source 含括號如 "TWSE指數(5m)", ...}, markClosed=false)
       → price:台股:0000（TTL 24h，schema 與個股相同）；source 含 "(" 故不進 IntradayTickStore
```

`TwseIndexPoller`（既有的盤後 TWSE FMTQIK 月報批次寫入 `twse_index_daily_history`）完全不受影響，仍是完成日 K 的唯一權威來源；`TaiexIndexPoller` 只餵 Redis 的盤中即時層。

`TechnicalIndicatorService.computeAllForTaiex()` 比照既有 `computeAll()` 對一般個股的既有作法：完成日序列最新一筆非今日時，查 `PriceQueryService.getLive("0000","台股")`，若其 `tradingDate` 為今日則暫加一筆合成列（`close/high/low` 取自 live price，缺值以 close 補）到序列最前，MA20／60／240 與當期 KD 皆含這筆；`taiexKd` 算前一期時排除這筆，維持既有「當期 vs 前一期」語意。

`TradingRadarService.buildMarket()` 的 `stale` 判定改為：

```java
boolean todayEodPresent = latestEodDate != null && latestEodDate.equals(currentTwTradingDay());
boolean liveFreshToday = !todayEodPresent && liveOpt.isPresent()
        && currentTwTradingDay().toString().equals(liveOpt.get().tradingDate());
boolean stale = !todayEodPresent && !liveFreshToday;
```

即「完成日 K 已到今日」或「Redis 有今日即時價」任一成立即非 stale。`price`／`changePercent` 在 `liveFreshToday` 時取即時價（相對最近一根**完成日**收盤算漲跌幅，避免跟自己比較）；`ruleEngine.confirm(closes, 60/240)` 用的 `closes` 序列**維持只含完成日 K**，不因新增即時來源而破例——這是 Task 217.3 已驗證過的原則（避免盤中價格在均線附近來回造成確認狀態逐 tick 翻轉），大盤與個股適用同一原則。`MarketSummary` 新增 `intraday`（regime 是否由即時點位算出）與 `liveUpdatedAt`（Redis 即時價的 `updatedAt`，非 intraday 時為 null）；既有 `asOfDate` 語意不變，仍指完成日 K 的日期。

前端 `TradingRadarView.vue` 的 stale 警示文案不再宣稱「盤中無即時值」（V6 起不再成立），改為「本次未能取得即時大盤點位，已退回前一交易日資料」的暫時性退化語意；`intraday=true` 時另外顯示即時點位更新時間，與「完成日 K」並列。`TradingRadarRuleEngine.RULE_VERSION` 由 `TW_RULES_V4` 升為 `TW_RULES_V6`（分數公式本身不變，只有輸入資料的新鮮度與 stale 語意變化，比照 Task 217.6 先例仍需升版以利前端與使用者辨識行為變化）。

### 規則引擎 `TW_RULES_V3`

大盤與標的的權重、clamp、regime 門檻及主動作映射以 Requirement 43 Acceptance Criteria 為唯一契約。V3 保留 V2 的大盤分數與 `STOCK` 行為，新增 `InstrumentType.EQUITY/BOND`：有效類別由 `stock.asset_class` override 優先、否則 `AssetClassifier` 規則判定，無法辨識時保守用 `EQUITY`。`EQUITY` 繼續套用大盤 `RISK_ON +8`／`RISK_OFF -15`、`RISK_OFF` 買進閘門與 `DATA_INCOMPLETE` veto；`BOND` 對台股大盤 regime 計 0 分、不受股票大盤買進閘門影響，大盤資料不足也不單獨 veto，但個別 MA／KD／完成日 K 不足仍一律 `NO_TRADE`。

`TradingRadarRuleEngine` 不碰 repository／網路／時間，輸入皆為數值、資產型別與確認狀態，輸出 score/action/counterTrend/reasons/risks，確保單元測試可重現。債券結果的 reasons 明示「不套用台股大盤加減分與閘門」，避免使用者誤以為漏算；股票 `RISK_OFF` 仍不抹掉獨立的逆勢觀察狀態。

### 逆勢抄底狀態（獨立第二軌）

`CounterTrendState` 為 `NONE`／`OVERSOLD_WATCH`／`TRIAL_CANDIDATE`。`evaluateCounterTrend(StockInput)` 只在個股完整資料通過後執行，不對原分數加減分，也不覆寫主 `Action`：

1. 長期結構仍在：`price > MA240` 且 `ma240Confirmation=ABOVE`。
2. 短中期已明確回檔：`ma20Confirmation=BELOW` 且 `ma60Confirmation=BELOW`。
3. `K<20`：符合 1–3 即 `OVERSOLD_WATCH`，代表跌深但尚非買點。
4. 升級 `TRIAL_CANDIDATE`：另須 `D<20`、`previousK<=previousD && K>D`，以及 `changePercent>=0`。因此只用當期 `K>D` 不足以冒充黃金交叉，仍下跌也不升級試單。

上述 MA／KD 一律來自還原權息序列。`RISK_OFF` 的逆勢風險文案只適用 `EQUITY`；`BOND` 不顯示股票大盤風險文案。

`TechnicalIndicatorService.FullIndicators` 增加 `previousK`／`previousD`：與當期 KD 使用完全相同的 KD9 遞迴；當期序列計算後，再排除最新一根（盤中 live 或最新完成日 K）計算前一期，資料不足回 null。`TradingRadarService` 將兩值只傳入純規則 `StockInput`，不新增 DB 欄位。`RISK_OFF` 時 counter-trend state 仍可產生，但 `counterTrendRisks` 必含小額分批、主建議優先；這是資訊狀態，不是自動下單授權。

### 手動重新整理觸發行情回補（Task 249）

Task 217 訂下、Task 228 保留的「頁面請求鏈零外部行情抓取」在**使用者手動按鈕**這一條路徑上被推翻：原本「重新整理」只是重打 `GET`，最新價仍取自 `PriceQueryService.getLive` 讀 Redis，而 Redis 的內容由 `PricePoller`（每 2 分鐘）與 `TaiexIndexPoller`（每 2 分鐘）的背景排程決定，兩次排程之間連按不會有任何變化。新增一條**只有手動按鈕會走**的路徑：

```text
TradingRadarView「重新整理」→ POST /api/bff/trading-radar/refresh
  → TradingRadarBffRoutes 既有 wildcard rewrite（不新增 route、不新增 controller method）
  → POST /api/trading-radar/refresh（business, TradingRadarController）
      → TradingRadarRefreshService
          1. 雙鍵冷卻閘門：Redis SETNX radar:refresh:cooldown:{ownerId} 與 :global，TTL 皆 30s
             ├─ 任一已存在 → 跳過步驟 2，outcome=COOLDOWN
             └─ 兩把都取得 → 步驟 2
          2. PriceQueryService.refreshTradingRadarPrices()（WebClient，30s timeout，同步等待）
             → POST /internal/refresh/tw-radar（external-materials-service）
                 ├─ Semaphore(1).tryAcquire() 失敗 → 立即回 {busy:true}，不抓
                 ├─ 開盤中（MarketClock.isTwMarketOpen()）→ 兩條【併行】後 join
                 │    ├─ PricePoller.updatePrices(twCodes, "台股", false)  ← virtual thread/檔，≈20s 上界
                 │    └─ TaiexIndexPoller.updateOnce()                     ← 大盤 0000，Future.get(12s) 上界
                 └─ 休市 → 逐檔守門後 PricePoller.syncClosedFromDb(可同步的 twCodes, "台股")；大盤不抓
                      守門：isTradingDay(今日) 且 findMaxTradingDate(code) != 今日 → 該檔跳過
                      （13:30–13:32 空窗期，Redis 現值才是當日收盤）；全數跳過 → SKIPPED_PENDING_CLOSE
          3. TradingRadarService.get()   ← 既有純讀重算，含 Task 230 per-owner 快照寫入
      → { radar, priceRefresh:{ outcome, twMarketOpen, elapsedMs } }
           twMarketOpen 一律取 business 的 MarketDataService.isMarketOpenNow("台股")；
           external 回傳的同名欄位只寫 log（否則跨 13:30 邊界會產生自相矛盾的 payload）
```

`GET /api/trading-radar` **一個位元組都不改**，SSE 背景重算（`recalculateRadar()` → `load(false, true)`）仍走 GET。這不是風格偏好而是必要條件：POST 若被 SSE 路徑呼叫，抓取寫 Redis → `price-update` 事件 → 2 秒 debounce → 再抓取，會形成自我餵食迴圈並持續打外部 API。

`twCodes` 來自**新增的** `StockSourceQuery.collectTwRadarCodes(Set<String>)`：每位 owner 各自最新快照的台股持股 ∪ 台股 `stock_alert`，排除 `0000`。

**不重用既有的 `collectHeldStockCodes()`**，因為它取 `SELECT id FROM asset_snapshot ORDER BY snapshot_date DESC LIMIT 1`——全庫只取一筆快照，同日期時 tie-break 任意。實測（2026-07-29 部署後）owner 1 的快照 id 15 有 35 筆台股持股、owner 2 的 id 18 只有 2 筆，兩者同日，Postgres 挑中 id 18；結果雷達顯示 19 檔而回補只涵蓋 18 檔，`2885`（只在 owner 1 持股、不在觀察清單）永遠不會被更新——按鈕說「已抓取最新報價」卻有一列沒動。改用 `DISTINCT ON (owner_user_id) ... ORDER BY owner_user_id, snapshot_date DESC, id DESC` 後實測 19/19 全覆蓋。**`collectHeldStockCodes` 本身不得修改**：它服務每 2 分鐘的 `scheduledTwIntradayUpdate` 與 `refreshAll()`，放大範圍會改變背景排程的外部請求量。`TwRadarRefreshService` 仍保留一次防禦性 `tw.remove("0000")`，不倚賴收集器的排除。**不重用既有的 `POST /internal/refresh`**——後者是 `PricePoller.refreshAll()`，會連美股／英股一併抓，而本頁只評台股，多抓只是把使用者的等待時間拉長。

休市分支沿用 `syncClosedFromDb` 而非重抓，是 Task 111 已驗證的不變式：盤外抓到的 last-tick 會覆蓋 FinMind 校正過的權威收盤，使 Redis 與 `stock_price_history` 不一致，Dashboard／歷年資產／快照表單三處數字互相打架。大盤 `0000` 在休市時不抓，因為 `twse_index_daily_history`（`TwseIndexPoller` 盤後批次）是完成日 K 的唯一權威來源，盤外抓 5 分 K 只會取到昨日尾盤點位。

**但休市分支本身有一個必須守門的空窗**：`MarketClock.isTwMarketOpen()` 上界是 `13:30`，而今日收盤價要到 `ClosePersister.dumpTwCloseFromRedis()`（`cron = "0 32 13 * * MON-FRI"`）才由 Redis 落進 `stock_price_history`。這約兩分鐘內 `syncClosedFromDb` 走的 `findRecentClose`（`ORDER BY trading_date DESC LIMIT 1`）取回的是**昨日**收盤，`PriceCacheWriter.syncClosedFromDb` 會無條件覆寫 Redis 銷毀當日真實收盤；接著 13:32 的 `dumpRedisToDb` **不檢查 payload 的 `tradingDate`**，把該昨收以 `tradingDate=今日` upsert 進 `stock_price_history`，污染收盤價的唯一權威來源，直到 16:00 `verifyTwCloseWithFinMind` 才自癒。既有 `refreshAll` 有同樣的缺口，但它沒有被任何前端按鈕呼叫；Task 249 是第一次把這條路徑接到顯眼按鈕，而 13:30–13:32 恰是使用者最想按的時刻。故本路徑**逐檔守門**：`isTradingDay(今日)` 為真、**且台股當地時間已過 13:30**、且該檔 `findMaxTradingDate(code, "台股")` 不等於今日時跳過該檔，全數跳過即 `outcome=SKIPPED_PENDING_CLOSE`。**13:30 這個時間下界不可省**——少了它，交易日 00:00–09:00 的盤前整段也會落進守門（該時段同樣「休市 ＋ 是交易日 ＋ 今日收盤未落 DB」），回出「已保留最新成交價」這種盤前根本不成立的假陳述；而且守門的理由在盤前是反過來的：DB 有 16:00 FinMind 校正過的權威收盤，Redis 才是該被同步的一方（`PricePoller.warmCacheOnStartup` 在盤外做的正是這件事）。`findMaxTradingDate` 回 `Optional.empty()`（該檔無任何歷史列）視為不納入同步。守門只加在新服務內，**不改動 `PricePoller.syncClosedFromDb`／`PriceCacheWriter.syncClosedFromDb`**（另有 `refreshAll`／`warmCacheOnStartup` 兩個既有呼叫端）。

**逾時預算由內而外收斂在 nginx 的 60 秒之內**（`frontend/nginx.conf` 的 `location /api/` 為 `proxy_read_timeout 60s`，超過即 504；BFF 的 Spring Cloud Gateway 未設 `spring.cloud.gateway.httpclient.response-timeout`，預設不逾時，不構成額外上界，日後亦不得為此功能加設）。**external 端必須自己收斂，不得只靠外層逾時**：個股側 `PriceFetchClient` 每檔 `tse`／`otc` 各 10 秒逾時、virtual-thread-per-code 並行，總時間 ≈ 20 秒；大盤側 `MacroDataFetchClient.fetchIndexIntraday("TWSE")` 走 `curlGetWithRetry(url, 2)`，回應非 JSON（Yahoo WAF 擋）時 `Thread.sleep(10s)` 再 `sleep(20s)`，**單這段最壞 30 秒純睡眠**，且其 `ProcessBuilder("curl", "-s", ...)` 沒有 `-m`、`waitFor()` 亦無逾時，理論上無上界。故大盤與個股**併行**、大盤那條**自帶 `Future.get(12, SECONDS)` 上限**（先等大盤再等個股，順序顛倒會讓 12 秒疊在個股的 20 秒之後），逾時即放棄本輪大盤（Redis 保留上一輪真實點位，符合 `TaiexIndexPoller` 既有「查無有效點位不寫」慣例）。**`ExecutorService` 必須是 bean 生命週期的欄位，絕不可用 try-with-resources 或 `awaitTermination` 收尾**——Java 19+ 的 `ExecutorService.close()` 預設是 `shutdown()` 後 `awaitTermination(1 DAY)`，離開 try 區塊會一路等到大盤任務結束，把 12 秒上限整個作廢（`PricePoller.java:147` 的 `// executor.close() 等所有 task 完成` 正是這個語意）；`cancel(true)` 也救不回來，`curlGetWithRetry` 阻塞在 pipe 讀取時對中斷無反應。**不修改既有 `curlGetWithRetry`**——它同時服務「股市大盤查詢」頁與其他總經抓取，改其重試或逾時是另一個變更的爆炸半徑。business → external 的 `WebClient` 設 30 秒逾時；前端 axios 對此支覆寫 45 秒。business 端逾時或例外**一律不上拋**：記 WARN 後照常重算並以 `outcome=TIMEOUT`／`FAILED` 回傳。

節流有三道，皆為「降級但仍回結果」，不得回 4xx：per-owner Redis 冷卻 30 秒、**全域** Redis 冷卻 30 秒、以及 external 端單一 permit 的 `Semaphore`。兩把 Redis 鍵**不得用 `&&` 短路取得**：短路後 owner 鍵已寫入卻沒有實際抓取，該使用者的冷卻會被無故燒掉、最長要等約 60 秒才解除；任一把未取得時必須把本次已取得的那一把刪掉。全域鍵不可省：`Semaphore` 只擋併發不擋速率，而 per-owner 鍵擋不住「A 按完 5 秒後 B 按」——回補清單是全庫的，N 個使用者輪流按可把外部請求頻率從背景排程的每 2 分鐘一輪推高數十倍（本專案已有被 Yahoo WAF 回 429 的實績）。全域鍵只透露「近期有人刷新過」，而行情快取本就是跨租戶共用的市場資料，不構成租戶洩漏。

使用者按鈕觸發的 `updatePrices` 可能與每 2 分鐘的 `scheduledTwIntradayUpdate` 同時執行——`Semaphore` 只守 `/internal/refresh/tw-radar`，擋不到 `@Scheduled` 那條。兩者對同一批代號併發寫 Redis，最壞是同一檔被兩次 tick 覆寫；值同源且 `PriceCacheWriter` 對 `Optional.empty()` 已有「本輪不更新」保護，實質無害，刻意不加跨路徑鎖（加了反而會讓背景排程被使用者按鈕餓死）。

`priceRefresh` **刻意不回傳抓取檔數**：回補清單是全庫的（Redis 行情快取本就是跨租戶共用的市場資料），回檔數等於把「全庫台股標的數」洩漏給任一使用者；檔數只進 business／external 的 log。`radar` 部分與 GET 完全同形（`TradingRadarDto.Response` 不新增欄位，避免動到 Task 230 已落地的 Redis 快照序列化與 Requirement 48 的區間匯出）。

`RULE_VERSION` 維持 `TW_RULES_V7`：本修訂不碰規則引擎、不碰 `stale`／`intraday` 語意、不碰任何因子或門檻，同一份輸入前後輸出完全相同；升版只會製造假的不可比性訊號並觸發 Requirement 44 的通知基準全面重建。

### API 與前端

| 層 | 端點 | 說明 |
|---|---|---|
| business | `GET /api/trading-radar` | 組裝大盤＋當前使用者台股決策；純讀、graceful per-stock；回應前 fail-soft 寫一筆 per-owner Redis 快照（Requirement 48）。**零外部行情抓取，Task 249 後仍然不變** |
| business | `POST /api/trading-radar/refresh` | 先同步回補台股即時行情（開盤中抓外部／休市同步 DB 收盤）再走同一支 `get()` 重算；回 `{radar, priceRefresh}`；**per-owner ＋ 全域雙鍵** 30 秒冷卻、逾時／失敗降級不回 5xx；Task 249 |
| external | `POST /internal/refresh/tw-radar` | 只抓台股個股（`collectHeldStockCodes` 的 tw set，另自行 `remove("0000")`）＋大盤 `0000`，兩者併行、大盤 12 秒上限；`Semaphore(1)` 單一併發，忙碌回 `busy=true`；休市走逐檔守門後的 `syncClosedFromDb`；Task 249 |
| business | `GET /api/trading-radar/export?from&to` | 由 Redis 快照組區間 Excel（`ResponseEntity<ByteArrayResource>`）；Requirement 48 |
| business | `GET/PUT /api/trading-radar/export-schedule/times` | 排程執行時間點清單／整批覆寫（per-owner 多時間點）；Requirement 48 追加 |
| business | `GET/PUT /api/trading-radar/export-schedule/setting` | 輸出資料夾（相對子路徑）讀取／儲存；Requirement 48 追加 |
| business | `POST /api/trading-radar/export-schedule/run-now` | 立即匯出到設定目錄，回落點路徑與檔案大小；不動當日 guard |
| business | `GET /api/export-schedule/browse?subpath=` | **沿用 Requirement 34 既有端點**列舉子資料夾，不新增 |
| BFF | `GET /api/bff/trading-radar` | `TradingRadarBffRoutes` rewrite 至 business；一頁一 BFF |
| BFF | `POST /api/bff/trading-radar/refresh` | 同一 wildcard rewrite 自動涵蓋（route 無 method predicate）；**不得新增 route 或 controller method**，理由同下方 browse 那列的反面——本路徑在 business 端**存在**對應位置，wildcard rewrite 正確；Task 249 |
| BFF | `GET /api/bff/trading-radar/export` | `TradingRadarBffRoutes` rewrite 至 business，二進位下載 passthrough |
| BFF | `/api/bff/trading-radar/export-schedule/**` | 同一 rewrite 自動涵蓋（路徑落在 business 對應位置） |
| BFF | `GET /api/bff/trading-radar/export/browse` | **須用 `TradingRadarBffController`（`@RestController` + WebClient）轉呼 business `/api/export-schedule/browse`**；不可加 gateway route——該路徑落在既有 wildcard `/api/bff/trading-radar/**` 內會被 rewrite 成不存在的 `/api/trading-radar/export/browse` 而 404。WebFlux 的 `RequestMappingHandlerMapping`(order 0) 先於 Gateway 的 `RoutePredicateHandlerMapping`(order 1)，controller 自動勝出；此寫法亦與其餘 7 頁一致 |

前端新增 `TradingRadarView.vue`：上方大盤 regime 卡（RISK_ON 紅、RISK_OFF 綠、NEUTRAL 灰、DATA_INCOMPLETE 黃；台股配色）、規則版本／資料日／重新整理；下方卡片表格依主動作顯示 tag，另有「逆勢抄底」獨立欄位（`NONE` 顯示 `—`、超跌觀察為 warning、逆勢試單候選為 danger）。標的名稱下依 DTO 顯示「債券」與「還原權息」小標籤；展開列呈現同一還原價基的三條均線確認、主規則理由／風險及逆勢狀態自己的理由／風險。個股決策表透過 Element Plus `row-dblclick` 將該列 `{ stockCode, stockName, market }` 傳入跨頁共用的 `StockAnalysisDialog`，使雙擊資料列可直接開啟股票分析圖，不新增雷達專屬圖表或 API。`api/index.js` 新增 `bffApi.tradingRadar.get()`；router 新增 `/trading-radar`；`App.vue` 於「股市綜合分析」加入「今日交易雷達」。

盤中更新沿用儀表板既有 SSE `/api/market-data/prices/stream`，不另建 endpoint。`price-update` 只處理 `market=台股` 且已存在於目前雷達清單的代號：事件抵達時以 immutable row replacement 立即覆蓋 `price`／`changePercent`／`priceUpdatedAt`；同一批事件以 2 秒 trailing debounce 合併，再以不顯示 loading 的 `bffApi.tradingRadar.get()` 重讀完整 response，讓 MA、KD、score、action、reasons、risks 與最新 Redis 價格一致。背景重算若仍在執行，新事件只標記 pending，完成後再合併補算，避免重疊請求。此流程只讀既有 Redis／PostgreSQL，**不呼叫任何行情 refresh**——Task 249 新增的 `POST /api/bff/trading-radar/refresh` 專供手動按鈕，SSE 路徑不得改呼叫它（否則抓取→寫 Redis→`price-update`→再抓取，形成自我餵食迴圈）。

生命週期與儀表板一致：mount 初始載入完成後建立 `EventSource`；一般網路中斷交由瀏覽器自動 reconnect，若連線進入 `CLOSED` 則 5 秒後重建；unmount 設定 disposed 並關閉 stream、清除 reconnect／recalculate timers，避免離頁後重開連線或更新已卸載狀態。手動重新整理仍可隨時重讀完整雷達，Task 249 起改為先回補台股行情再重算（見上方「手動重新整理觸發行情回補」）。

### 驗證重點

- `TradingRadarRuleEngineTest`：確認 period+1 邊界、ABOVE／BELOW／MIXED／UNAVAILABLE、分數上下界、買進門檻與 `RISK_OFF` veto、held action mapping、incomplete veto。
- `TradingRadarRuleEngineTest` V2：以 009804 型輸入確認主分數／出場候選不變但 counter-trend=`OVERSOLD_WATCH`；確認只有真實低檔黃金交叉＋停止續跌才是 `TRIAL_CANDIDATE`，未交叉、仍下跌、年線失守、資料不足皆不可誤判。
- `DistributionAdjustedPriceServiceTest`／`TradingRadarRuleEngineTest` V3：以 00751B 型除息序列證明原始季／年線跌破在還原後不再誤判；最新價保持原值、無事件完全不改值、現金／股票配息因子正確；`BOND` 不吃台股 `RISK_OFF` 扣分／閘門／veto，`EQUITY` 行為維持 V2。
- `TradingRadarRuleEngineTest` V4：stale 時不給 `RISK_ON` 加分且關閉買進閘門、stale 時 `RISK_OFF` 仍 veto、逆勢 `stabilized` 改讀完成日漲跌幅後盤中不翻轉。
- 新增測試（`TradingRadarService`／`TechnicalIndicatorService` 大盤即時融合邏輯，Task 228，V6）：完成日 K 未到今日但 Redis 有今日即時價 → `stale=false`、`intraday=true`；兩者皆無 → `stale=true`（既有行為不變）；完成日 K 已到今日 → `stale=false`、`intraday=false`（既有行為不變）；兩日確認（`c60`／`c240`）只用完成日 K、不受即時點位影響。`external-materials-service` 大盤盤中輪詢新增測試：抓不到有效點位時不覆寫 Redis（保留上一輪真實值）。
- 前端正式建置後確認 `TradingRadarView` chunk 含 `/api/market-data/prices/stream` 與 `price-update`；執行環境確認 SSE endpoint 可建立 `text/event-stream` 回應，且離頁清理與背景重算不觸發 refresh endpoint。
- 新增測試（手動重新整理回補，Task 249）：開盤中 → 個股 `updatePrices` ＋ 大盤 `updateOnce` 皆被呼叫、`outcome=FETCHED`；休市 → 走 `syncClosedFromDb`、大盤不抓、`outcome=CLOSED_SYNCED`；交易日休市但該檔 `findMaxTradingDate` 非今日（13:30–13:32 空窗）→ **不呼叫** `syncClosedFromDb`、`outcome=SKIPPED_PENDING_CLOSE`；非交易日 → 守門不生效、照常同步；冷卻中（per-owner 或全域任一命中）→ external client **零互動**且仍回完整 `radar`、`outcome=COOLDOWN`；external 逾時／例外 → 不上拋、仍回完整 `radar`、`outcome=TIMEOUT`／`FAILED`；`Semaphore` 已被佔用 → 立即 `busy=true` 且不抓。**迴歸**：`GET /api/trading-radar` 對 external client 仍為零互動（守住 SSE 路徑未被汙染）。前端建置後確認 chunk 含 `bff/trading-radar/refresh`，且 `recalculateRadar` 走的仍是 GET wrapper。
- 建置：backend test/package、BFF package、frontend build。
- 執行環境：重建 business／BFF／frontend 後確認 health；以已登入頁面或帶有效 user header 的容器內診斷確認 payload owner-scoped。檢查 business log 與程式依賴，證明 `/api/trading-radar` request 不進 `MarketAnalysisService`、不產生 Anthropic batch。

---

## Requirement 44：每檔交易雷達狀態 Email 通知

### 使用者流程與 API

`TradingRadarView` 表格最右側的「通知設定」按鈕開啟逐檔 dialog。GET
`/api/bff/trading-radar/notifications/{stockCode}?market=台股` 一次聚合設定、主動作／逆勢狀態 options，以及與通知設定頁同源的 `notification_recipient`；PUT 同一路徑覆寫 `active`、`actionStates`、`counterTrendStates`、`recipientIds`。BFF 沿用 `TradingRadarBffRoutes` rewrite 到 business，不在 BFF 儲存或計算。

主動作選項涵蓋 `TradingRadarRuleEngine.Action` 全部 10 態；逆勢只提供 `OVERSOLD_WATCH`、`TRIAL_CANDIDATE`，不提供無訊號的 `NONE`。選項 label 由 backend 與雷達主 response 共用 label mapper 回傳，避免 dialog 與 Email 文案各自漂移。active 設定至少需一個狀態與一位收件人；inactive 可保存空選項。

### 正規化資料模型

```text
AppUser (1) ──< TradingRadarNotificationSetting >── Stock(code, market)
                       │
                       ├──< TradingRadarNotificationState
                       │      UNIQUE(setting_id, state_type, state_code)
                       └──< TradingRadarNotificationRecipient >── NotificationRecipient
                              UNIQUE(setting_id, recipient_id)
```

- `trading_radar_notification_setting`：`id`、`owner_user_id`、`stock_code`、`market`、`active`、`initialized`、`last_action`、`last_counter_trend_state`、timestamps；`UNIQUE(owner_user_id, stock_code, market)`，entity 套 `ownerFilter`。
- `trading_radar_notification_state`：`setting_id`、`state_type`（`ACTION`／`COUNTER_TREND`）、`state_code`；狀態是規則版本契約，不冗存 label。
- `trading_radar_notification_recipient`：`setting_id`、`recipient_id`，兩端 FK cascade；不冗存 email。

設定 PUT 先以 owner-filtered `NotificationRecipientRepository.findByIdIn()` 取得合法收件人白名單，再以 bulk delete + insert 覆寫兩個 join；setting by-id／by-stock 仍以 `TenantGuard` 與 owner filter 雙重保護。背景取 email 時 join setting 與 recipient 並強制兩者 `owner_user_id` 相同、recipient `active=true`，避免 HTTP filter 不存在時的跨租戶殘列風險。

### 狀態轉入偵測與寄信

```text
Redis price-update
  → PriceStreamService（原 SSE + StockAlert 不變）
  → TradingRadarNotificationService.queueEvaluation(code, market)
  → 2 秒合併同輪股票
     ├─ 個股：只取該 code/market active settings
     └─ 0000/台股：取全部 active 台股 settings
  → explicit owner latest snapshot 判斷 held
  → TradingRadarService.evaluateForNotification() 共用目前規則版本（現為 TW_RULES_V3）
  → 比對 persisted last_action / last_counter_trend_state
  → TradingRadarNotificationDispatcher（短批次 per-recipient digest）
  → EmailService.sendHtml([single email], ...)
```

setting `initialized=false` 時，第一次評估只寫入目前 action／counter-trend 作 baseline，不 enqueue。其後，只有「目前值與 last 不同」且新值存在 selected state join 時才 enqueue；評估後無論是否選中都更新 last，使「離開 → 再進入」可重新觸發、持續同態不重寄。PUT（含收件人、狀態、active 修改）一律把 `initialized=false`，避免儲存當下狀態立即寄信。

背景沒有 request context，held 必須以 `AssetSnapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(ownerId)` 顯式 owner 條件取得，並在 transaction 內檢查最新快照 `stocks`；不可用無 owner 的 `findLatestWithStocks()`。`TradingRadarService.evaluateForNotification(code, market, held)` 只抽取既有 buildMarket/buildStock 組裝，不查 owner 資料、不改分數。dispatcher 按 active recipient 反轉分組，每位收件人各一封；SMTP disabled／無收件人／例外皆 fail-soft。此鏈不依賴頁面 EventSource 是否存在，也不呼叫外部 refresh 或 AI。

### 前端

操作欄固定在最右側，按鈕開 dialog 後才讀設定，避免雷達初始 GET 為每列增加 N+1。dialog 用兩組 checkbox 顯示主規則狀態與逆勢狀態，另用 recipient checkbox 顯示 email／停用註記；無收件人時提供前往 `/settings/notifications` 的入口。文案明示「第一次只建立基準；只在之後進入所選狀態時寄一次，同狀態持續不重寄」。儲存成功關閉 dialog，不觸發行情 refresh。

---

## Requirement 43／44 修訂（Task 217／218）：判斷邏輯強化與通知抖動抑制（`TW_RULES_V4`）

### 問題根因

三個獨立缺陷，共同根因是「即時值與完成日值混用時缺少新鮮度與穩定性的把關」：

1. **大盤 regime 盤中恆為前一交易日**。`buildMarket()` 只讀 `twse_index_daily_history`（收盤後才入庫），而 `buildStock()` 走 Redis 即時價。`0000/台股` 在 `StockSourceQuery` 被明確排除於即時抓價之外，Redis 內 `price:index:台股` 只是「有哪些代號有 cache」的 SET，**不存在大盤即時價**。故此缺陷（Task 217 當時）無法以「改讀即時來源」修復，只能以新鮮度閘門處理。**此結論已被 Task 228 推翻**：新增 `TaiexIndexPoller` 把大盤盤中點位寫入 `price:台股:0000`，詳見下方「大盤新鮮度與盤中即時判斷」小節；本段落保留作為 Task 217 當時的歷史脈絡，不代表現況。
2. **逆勢升級用盤中漲跌幅判定止跌**。`stabilized = changePercent >= 0` 是逆勢軌唯一會隨盤中變動的判定項（`lowKd`／`goldenCross` 皆來自不含 live 的完成日序列），零交叉造成逐 tick 翻轉。
3. **通知轉入判斷無去抖、無上限、無冷卻**，且暫時性失敗被寫入 baseline。

### 大盤新鮮度閘門

`MarketState` 增加 `stale`：以既有交易日曆解析出「當前台股交易日」（非交易日則取最近一個交易日），與 `rows.get(0).getTradingDate()` 比對，不等即為 stale。傳入 `StockInput` 後由規則引擎處理：

| 情境 | RISK_ON +8 | 買進閘門 | RISK_OFF −15 與 veto |
|---|---|---|---|
| 大盤新鮮 | 給 | 依原規則 | 生效 |
| 大盤 stale | **不給** | **一律關閉** | **仍生效** |

不對稱是刻意的：新鮮度不足時只收緊、不放寬。`DATA_INCOMPLETE` 的既有全域 veto 不變；債券（`InstrumentType.BOND`）本就不套用大盤加減分與買進閘門，stale 對其無影響。

### 逆勢「停止續跌」改基準

`StockInput` 增加 `completedChangePercent`（最近一根完成日 K 相對前一根的漲跌幅，取自還原後序列以與其他逆勢條件同價基）。`evaluateCounterTrend` 的 `stabilized` 改讀此值。盤中即時漲跌幅仍供顯示與「單日漲跌幅扣分」使用，不影響逆勢升級。此後逆勢狀態在盤中為常數，只在完成日 K 變動時改變。

### 降級旗標

`MarketSummary`／`StockDecision` 增加 `degraded`：僅在 `catch` 路徑為 true，代表「讀取失敗」；「資料量不足」（歷史不足 241 根等）走既有 `dataComplete=false`，兩者語意分離。通知鏈只跳過 `degraded`，不跳過正常的資料不足。

### 通知去抖、上限與冷卻

`TradingRadarNotificationTransition` 由「單次比對」改為「持穩計數」：

```text
（下列每「輪」＝一次實際評估，盤中約 2 分鐘一輪，非 2 秒）
候選狀態 == 上次候選狀態  → pendingCount++
候選狀態 != 上次候選狀態  → pendingCount = 1，改記新候選
pendingCount >= N(=3) 且 候選 != baseline 且 候選 ∈ 已選狀態 → 轉入成立
轉入成立 → 檢查每日上限與冷卻 → 通過才 enqueue；baseline 一律更新
```

新增欄位持久化於 `trading_radar_notification_setting`（changeset `v1.67.0`）：`pending_action`／`pending_action_count`／`pending_counter_trend`／`pending_counter_trend_count`、`last_notified_at`、`daily_notify_date`／`daily_notify_count`。持久化而非記憶體，確保 business 容器重建後不重置（否則重建即可繞過上限）。每日計數以台北時區交易日為界。

門檻值（N=3、每日每狀態 1 封、每 setting 每日 4 封、冷卻 30 分）集中為具名常數，供未來移入設定頁。

### 單一節拍：評估與派送同輪（Task 220）

```text
@Scheduled(fixedDelay = 2s)  TradingRadarNotificationService.runCycle()   ← 唯一計時器
   ├─ self.getObject().flushEvaluations()   @Transactional（經 proxy，自呼叫會繞過 AOP）
   │     └─ 逐 setting 評估 → transition → 持久化 baseline → dispatcher.enqueue()
   └─ dispatcher.flush()                    交易外：drain → 依收件人分組 → 各寄一封
```

`Dispatcher.flush()` 移除 `@Scheduled(10s)`。理由是**競爭**而非效能：兩個獨立計時器下，派送可能在評估尚未 enqueue 完成時 drain，使同一輪通知被拆成多封信。改為同輪串接後，一輪評估產生的全部通知必然在同一個 batch 內分組。

派送刻意置於交易之外：`flushEvaluations` 為 `@Transactional`，若把 SMTP I/O 納入會撐長交易，且交易回滾時信已寄出、下一輪會重寄。`runCycle` 本身不標 `@Transactional`，並以 `ObjectProvider<TradingRadarNotificationService>` 取得自身 proxy 呼叫交易方法（同類自呼叫不會套用 `@Transactional`）；ObjectProvider 為延遲解析，不造成建構期循環依賴。兩段各自 try/catch，任一失敗不影響另一段與既有價格 SSE。

**評估頻率的正確理解**：2 秒是「價格事件抵達後的排空節拍」，佇列為空即直接 return；真正的評估頻率由 `PricePoller` 台股 cron `0 0/2 9-13`（每 2 分鐘）決定。設計去抖次數 N 時須以 2 分鐘為單位換算。

### 交易時段閘門

`queueEvaluation` 先問既有交易日曆：非台股交易日、或不在交易時段內，直接 return。盤後收盤校正（`writeVerifiedClose`）與休市同步（`syncClosedFromDb`）同樣會 publish `price-update`，此閘門一併擋掉。既有到價警示與 SSE 行為不受影響（本閘門只加在雷達通知入口）。

### 還原權息長窗偏誤：刻意保留並揭露

back-adjustment 等同總報酬序列，對高配息標的在市價不動時仍呈上升，使長窗均線位置分偏多。取消還原會讓除息日回到假跌破（V3 修正的原始問題），故**保留還原**，改以揭露處理：`StockDecision` 增加 `distributionAdjustedYieldPct`＝`(1 − 最舊列 scale) × 100`，即該視窗內的累積還原幅度，前端於「還原權息」tag tooltip 呈現。此欄為純衍生值、不入庫。

### 驗證重點

- `TradingRadarRuleEngineTest`：stale 時不給 RISK_ON 加分且關閉買進閘門、stale 時 RISK_OFF 仍 veto、逆勢 `stabilized` 改讀完成日漲跌幅後盤中不翻轉。
- `TradingRadarNotificationTransitionTest`：未達 N 次不寄且不改 baseline、達 N 次寄一次、候選中途改變則計數重來、degraded 輪次完全跳過。
- 每日上限／冷卻：以注入的固定時鐘測試跨日重置與冷卻期內不寄。
- 交易時段閘門：非交易日與盤後時間 `queueEvaluation` 不入列。

---

## Requirement 45（Task 216）：股市大盤指數日線 Excel 匯出（開/高/低/收）與排程自動匯出

結構比照 R41／R42（**全域公開行情 ＋ per-user 排程設定**），以下只記差異。

### 與既有五套匯出排程的定位

| 需求 | 資料範圍 | 背景產檔是否需 owner 過濾 | 產檔方法 |
|---|---|---|---|
| R34 歷年資產 | per-user | 需要 | `exportLiveAssetsForOwner(ownerId)` |
| R39 已實現損益 | per-user | 需要 | `exportRealizedGainsForOwner(ownerId)` |
| R37 交易日曆 | 全域 | 不需要 | `exportTradingCalendar(year)` |
| R41 油價金價 | 全域 | 不需要 | `exportCommodityPrices(start, end)` |
| R42 台幣兌美元 | 全域 | 不需要 | `exportExchangeRates(currency, start, end)` |
| **R45 大盤指數日線** | **全域** | **不需要** | `exportIndexDaily(market, start, end)` |

`twse_index_daily_history` 與 `us_index_daily_history` 皆無 `owner_user_id`、未套 `@Filter(ownerFilter)`。

### 匯出內容：直接取 DB 既有 OHLC 欄位

工作表五欄：**日期／開盤／最高／最低／收盤**，單一序列依日期遞增。

四個價格欄**直接讀 entity 既有欄位**（`openPoint`／`highPoint`／`lowPoint`／`closePoint`），
不重算也不由收盤價推導——這與 R42 的中間價相反（那是 `@Transient` 衍生值，必須由 entity 算）。
兩張日線表的 OHLC 皆為既存實體欄位（`twse` 為 `v1.22.0-twse-daily-ohlc.sql` 補齊、`us` 建表即有），
故**不需要任何 DDL 變更**即可匯出開高低。

`open/high/low` 為 nullable（TWSE 早期由 `v1.21.0` 只抓 `ClosingIndex` 的殘留列），null 該格留空、
不補前值（同 R41／R42）。實測目前 10 張表 24,588 列 OHLC 全滿，但仍須保留 null 分支——
清空重建或未來新增指數會再度出現只有收盤的中繼狀態。
`close_point` NOT NULL。TWSE 精度 `numeric(12,2)`、海外 `numeric(14,4)`，沿用 `Styles.num4`。
日期以 ISO 文字寫入（非 date cell），避免開啟端時區偏移一天（同 R40／R42）。

### 兩張來源表的分派

`exportIndexDaily(market, …)` 以 `market` 分派：`TWSE` → `TwseIndexDailyHistoryRepository`
`.findByTradingDateBetweenOrderByTradingDateAsc`；其餘 → `UsIndexDailyHistoryRepository`
`.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc`。兩者欄位語意相同，
在 service 內正規化成同一組 `(date, o, h, l, c)` 再寫表，工作表格式與畫面所見一致
（同一支 API 供手動與排程共用，見 CLAUDE.md「同義欄位、同一 business service API」）。

**`SP500TR` 不列入白名單**：該代碼雖存在於 `us_index_daily_history`，但屬績效比較頁（R33）的
含息報酬指數，不在本頁 9 個可選指數內。白名單取 `OVERSEAS_INDEX_CODES ∪ {TWSE}`，
與頁面下拉一致；`MacroHistoryController` 另有 `US_INDEX_REFRESH_CODES`（含 `SP500TR`）是回補守門用，語意不同，不可誤用。

### 指數維度：本頁與 R42 的關鍵差異

R42 刻意不設 `currency` 欄（單一幣別頁，加欄＝為不存在的需求預留）。
**R45 相反**：本頁下拉本來就有 9 個指數，「匯出哪一個」是使用者當下的實際選擇，
故排程表**設 `market` 欄**（`VARCHAR(16) NOT NULL DEFAULT 'TWSE'`），設定卡提供指數下拉。

標籤同 R42 走單一來源 `ExcelExportService.indexLabel(market)`（`TWSE`→`台股大盤`、`DJI`→`道瓊工業`…），
工作表名／手動匯出檔名／排程檔名三處共用。未知代碼回傳代碼本身，不臆造名稱。
（前端 `GdpTwseView.MARKETS` 的 label 是 render 用，後端不可依賴前端字串。）

### 「當日」分時模式不提供匯出

頁面區間選「當日」走 `GET /api/bff/gdp-twse/index-intraday`，是 Yahoo 5 分 K 的 transient 資料
（不入庫、無 OHLC 欄位語意）。此模式匯出按鈕停用，避免匯出一份與畫面不符的日線資料。

### 資料模型

```sql
CREATE TABLE index_export_schedule (
    id              BIGSERIAL PRIMARY KEY,
    owner_user_id   BIGINT       NOT NULL,      -- @Filter(ownerFilter)，每人一列
    enabled         BOOLEAN      NOT NULL DEFAULT FALSE,
    run_hour        INT          NOT NULL DEFAULT 8,
    run_minute      INT          NOT NULL DEFAULT 0,
    market          VARCHAR(16)  NOT NULL DEFAULT 'TWSE',  -- 與 R42 的差異：本頁有 9 個指數可選
    output_subpath  VARCHAR(255) NOT NULL DEFAULT 'input',
    range_months    INT,                        -- NULL ＝ 全部十年
    last_run_date   DATE,                       -- 當日 guard
    last_run_at     TIMESTAMP,
    last_run_status VARCHAR(500),
    updated_at      TIMESTAMP,
    CONSTRAINT uq_index_export_schedule_owner UNIQUE (owner_user_id),
    CONSTRAINT ck_index_export_schedule_hour   CHECK (run_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_index_export_schedule_minute CHECK (run_minute BETWEEN 0 AND 59),
    CONSTRAINT ck_index_export_schedule_range  CHECK (range_months IS NULL OR range_months BETWEEN 1 AND 120)
);
```

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

`market` 不設 CHECK 約束：合法代碼清單在 `MacroHistoryService.OVERSEAS_INDEX_CODES`（Java 端單一來源），
寫進 DDL 會變成第二份清單，日後新增指數要改兩處且漏改只在 runtime 才炸。改由 service 層白名單驗證。

滾動範圍、路徑安全（`resolveDir` + `startsWith(base)`）、`writeAtomically`、
每分鐘 poll ＋ 當日 guard ＋ `ApplicationReadyEvent` 自癒 ＋ `AtomicBoolean` 防重入、
run-now 不動當日 guard——全部同 R41／R42，不重述。
檔名 `{指數名}_{使用者ID}_{YYYYMMDD}.xlsx`。

### API 端點

| 層 | 端點 | 說明 |
|---|---|---|
| business | `GET /api/index-daily/export?market=&start=&end=` | 產出 .xlsx（UTF-8 檔名、`ByteArrayResource`） |
| business | `GET /api/index-export/schedule` | 讀當前使用者排程設定（無則回預設值） |
| business | `PUT /api/index-export/schedule` | upsert（驗證時分、market 白名單、range_months、子路徑不跳脫） |
| business | `POST /api/index-export/run-now` | 立即產檔到設定目錄，回 `{path, sizeBytes}`；不動當日 guard |
| business | `GET /api/export-schedule/browse?subpath=` | （既有，複用）列出基底下子目錄 |
| BFF | `GET /api/bff/gdp-twse/export?market=&start=&end=` | passthrough 下載（原樣轉出 Content-Disposition） |
| BFF | `GET`／`PUT /api/bff/gdp-twse/export/schedule` | passthrough |
| BFF | `POST /api/bff/gdp-twse/export/run-now` | passthrough |
| BFF | `GET /api/bff/gdp-twse/export/browse` | passthrough 至 business `/api/export-schedule/browse` |

匯出端點掛 `/api/index-daily/export`（`MacroHistoryController`，與 `/api/twse-daily-index`、
`/api/us-daily-index`、`/api/index-intraday` 同一支 controller），排程設定端點掛獨立前綴
`/api/index-export`（同 R41 `/api/commodity-export`、R42 `/api/exchange-rate-export`）——
前者是全域行情查詢，後者是 per-user 設定，語意不同。

### 新增／異動檔案

**新增**
- `backend/.../model/IndexExportSchedule.java`
- `backend/.../repository/IndexExportScheduleRepository.java`
- `backend/.../service/IndexExportScheduleService.java`
- `backend/.../controller/IndexExportController.java`（`@RequestMapping("/api/index-export")`）
- `backend/.../dto/IndexExportDto.java`
- `backend/src/main/resources/db/changelog/changes/v1.66.0-index-export-schedule.sql`

**異動**
- `db.changelog-master.yaml`：註冊 v1.63.0
- `ExcelExportService.java`：新增 `indexLabel`／`exportIndexDaily`／`writeIndexDailySheet`
- `MacroHistoryController.java`：新增 `GET /api/index-daily/export`
- `GdpTwseBffController.java`：新增 export／schedule／run-now／browse 五支 passthrough
- `frontend/src/api/index.js`：`gdpTwse` 命名空間新增 5 支
- `frontend/src/views/GdpTwseView.vue`：新增「匯出 Excel」按鈕＋匯出對話框＋「排程自動匯出」設定卡＋資料夾選擇器
- `SchedulePublicBffController.java`：`JOBS` 補「大盤指數匯出 每日匯出排程檢查」項目

---

## Requirement 46（Task 222）：台股基本面資料抓取與歷史落地

### 為什麼這件事必須先做，且愈早愈好

TWSE／TPEx 開放 API 只提供「當期單一快照」，**且 `?date=` 參數被伺服器忽略**（實測傳 `date=11412` 仍回 `11506`）。歷史月營收的唯一來源在 MOPS，而 `mopsov.twse.com.tw/robots.txt` 為 `User-Agent: * / Disallow: /`（僅開放 bingbot），沒有合規回補管道。

推論：**時間序列只能從上線第一天起自行累積，每延後一天就永久缺一天。** 這是本任務優先於評分邏輯（Task 223）的唯一理由——爬蟲可先上線默默存資料，評分公式之後再迭代。

### 抓取架構

沿用 `external-materials-service` 的既有分層，以 `DividendPersister` 為骨架（低頻資料，**不經 Redis**——Redis 在本專案專用於即時價格路徑與盤中→收盤中繼，基本面資料直接寫 PostgreSQL）：

```text
FundamentalFetchClient   整批 GET → 解析 JSON → 回傳 record list（純抓取，無 DB）
        ↓
FundamentalPersister     @Scheduled(zone=Asia/Taipei) + ApplicationReadyEvent 自癒
        ↓                AtomicBoolean 防重入，逐端點 try/catch，結束 log 成功/失敗計數
StockSourceQuery         新增 upsert 方法（JdbcTemplate，比照既有 upsertHistory）
        ↓
PostgreSQL               stock_valuation_daily / stock_financial_quarter / stock_monthly_revenue
```

**與既有價格路徑的關鍵差異：整批而非逐檔。** `BWIBBU_ALL` 單次回傳全市場 1079 筆，`t187ap06_L_ci` 1043 筆，`t187ap05_L` 1082 筆。既有 FinMind 路徑的「逐檔查詢＋`Thread.sleep(300)`」節流模式是為逐檔 API 而設，套用在整批端點上會產生上千次無謂請求。此處採單次 GET＋本地比對落地，僅需 connect／read timeout 與失敗退避。

### 端點對應

| 目標欄位 | 上市（TWSE） | 上櫃（TPEx） |
|---|---|---|
| PE／PB／殖利率 | `/v1/exchangeReport/BWIBBU_ALL` | `tpex_mainboard_peratio_analysis` |
| EPS、淨利 | `/v1/opendata/t187ap06_L_<產業>` | `mopsfin_t187ap06_O_<產業>` |
| 股東權益 | `/v1/opendata/t187ap07_L_<產業>` | `mopsfin_t187ap07_O_<產業>` |
| 月營收＋YoY | `/v1/opendata/t187ap05_L` | `mopsfin_t187ap05_O` |

兩個實作陷阱，皆已實測確認：

1. **產業別分表**：損益表／資產負債表切成 `_ci`（一般業）／`_fh`（金控）／`_ins`（保險）／`_bd`（證券）／`_mim`／`_basi`。只讀 `_ci` 會整段漏掉金融股——而 2881、2885、2891 皆為使用者實際持股且有 2439 筆完整價格歷史。
2. **上市與上櫃 key 命名不一致**：`mopsfin_t187ap06_O_ci` 用 `SecuritiesCompanyCode`／`Year`／`Season`，`mopsfin_t187ap07_O_ci` 混用 `SecuritiesCompanyCode` 與中文 `年度`／`季別`，`mopsfin_t187ap05_O` 全中文 `公司代號`，TWSE 對應端點則全中文。須建 key 映射表，不得假設同名。

### 資料模型

三張表皆為**全域公開行情**，比照 `stock_price_history` 不帶 `owner_user_id`、不套 `@Filter`。changeset `v1.68.0-stock-fundamentals`（**刻意避開 `v1.67.0`——該版號已由 Task 218 的通知去抖欄位預定，雖檔案尚未建立**）。

```text
stock_valuation_daily     UK(stock_code, market, trading_date)
                          pe_ratio / pb_ratio / dividend_yield_pct   numeric(12,4) NULL

stock_financial_quarter   UK(stock_code, market, fiscal_year, fiscal_quarter)
                          eps numeric(12,4) NULL
                          net_income_parent / equity_parent  bigint NULL（千元，同來源單位）

stock_monthly_revenue     UK(stock_code, market, revenue_year, revenue_month)
                          revenue bigint NULL（千元） / revenue_yoy_pct numeric(12,4) NULL
```

**不存 ROE**：`ROE = net_income_parent ÷ equity_parent` 為可從其他欄位算出的衍生值，儲存即違反本專案正規化規範（同 `realized_gain` 不存 profit 的既有決策）。近四季 EPS 合計、營收成長均值等聚合同理不存，一律於評分時計算。TWSE／TPEx 亦**未提供任何現成 ROE 欄位**（兩站 swagger 搜尋「報酬率」皆無命中），此為已確認事實。

**NULL 的語意**：`BWIBBU_ALL` 實測 1079 筆中 247 筆 `PEratio` 為空字串，代表**該公司虧損**（無正 EPS），不是抓取失敗。落地一律寫 `NULL`，嚴禁以 `0` 代替——`pe_ratio = 0` 在下游會被讀成「本益比極低＝極便宜」，把虧損公司評為最優。欄位註解須明載此區分。

### ETF 判定：跟隨既有的資料驅動方向

現況的 ETF 相關邏輯散在四處，且白名單那兩份已被專案自己判定為不可用：

| 位置 | 內容 | 狀態 |
|---|---|---|
| `MarketDataService.java:378` | `US_ETF_WHITELIST`（含 AVGO、無 SGOV） | 已知有誤 |
| `MarketDataFetchService.java:371` | 同上，**完全相同的第二份複本**（另一個 deployable） | 已知有誤 |
| `AssetClassifier` | `HIGH_DIVIDEND_ETFS`(22+15 檔)、`US_BOND_ETFS`(60+ 檔，**已含 SGOV**)、`isTwBondEtf()` | 正確，但輸出軸是 CASH/BOND/STOCK，不含 STOCK-vs-ETF 這一軸 |
| `MarketDataFetchService.fetchEtfHoldingsUncached()` | 資料驅動判定（有 ETF 淨值資料即為 ETF） | **專案既有的正確方向** |

關鍵事實：程式碼已**三度明文記載**棄用白名單的決策——

> `MarketDataFetchService.java:641`：「本方法即為『這檔是不是 ETF』的資料驅動判定，不需要維護 ETF 白名單（既有 `isEtf()` 白名單誤把個股 AVGO 列為 ETF、又漏掉使用者實際持有的 SGOV，**刻意不複用**）」

`EtfNavPoller.java:29` 與 `ExcelExportService.java:718` 同旨。故本設計**跟隨既有方向，不做方向反轉**：

```text
判定順序：
  1. stock.security_type override（STOCK / ETF，nullable）  ← 逃生口，預期極少使用
  2. 資料驅動：該標的在 ETF 淨值資料中有紀錄 → ETF
  3. fallback：台股 00 開頭 → ETF
```

兩份既有白名單**不在修正範圍**（它們服務於其他既有路徑，動它們是獨立議題），但新程式碼一律不得引用。

`security_type` 刻意**不建設定頁**，與 `asset_class` 的完整五件套（設定表＋seed＋`/api/settings/asset-classes`＋`PUT /api/settings/securities/asset-class`＋`AssetClassSettingsView.vue`＋BFF）不同。理由：它不是使用者可自訂的業務分類（值域固定兩者，不會新增第三種），而是客觀技術事實，主要由資料驅動得出；此欄位僅為誤判時的逃生口。**若日後需要頻繁人工修正，代表資料驅動判定不可靠，屆時應修判定邏輯而非補設定頁。**

### 合規界線

TWSE／TPEx 使用條款禁止以爬蟲擷取本站資料，但明文豁免「已授權『政府資料開放平臺』提供公眾使用之資料」——`/openapi/` 正是該通道，政府資料開放授權條款允許程式化取用與衍生著作，義務為標示出處。故：**走 `/openapi/` 合規，爬 HTML 頁面不合規，MOPS 全面禁止。**

TWSE 舊版 `rwd/BWIBBU_d`（可回溯 2005-09-02）能一次補齊歷史 PE，使 Task 223 的「PE 自身歷史分位」子因子立即可用，但該端點不在開放資料豁免範圍內，屬灰色地帶。**本設計預設不實作歷史回補**，PE 分位在樣本不足期間回 `null` 並由權重重分配吸收；若日後要做須另立 Requirement 並記錄合規評估。

---

## Requirement 43 修訂（Task 223）：長期因子併入總分與飽和修正（`TW_RULES_V5`）

### 飽和問題是前置條件，不是附帶修正

V4 的結構是 `base 50 + Σ 因子加減 → clamp(0, 100)`。逐項加總 `evaluateStock()` 的極值：

```text
最大 = 50 +8 +12 +15 +5 +8 +10 +5(K>D) +3(KD<20) +8(RISK_ON)            = 124
最小 = 50 −8 −12 −15 −5 −8 −10 −5(K≤D) −3(KD>80) −15(RISK_OFF) −5(單日) = −36
```

理論值域 `−36 ~ +124`（Task 217.8 實測的 `strongStock` 原始分 121 是此值域內的單一觀測值，不是端點）。超出 `[0,100]` 的部分被 clamp 吃掉，任何新增因子在強勢標的上等同沒有實作。

故 V5 必須先改結構才能加因子。改為**分組加權正規化**：

```text
子因子 → 標準化為 [-1, +1] 的貢獻值（缺值回 null，排除於平均之外）
組分數 = 組內有效子因子的加權平均            ∈ [-1, +1]
score  = 50 + 50 × Σ(組權重 × 組分數)        ∈ [0, 100]
```

因 `Σ(組權重) = 1` 且每個組分數 ∈ `[-1,+1]`，故 `Σ ∈ [-1,+1]`、`score ∈ [0,100]` **恆成立且無例外**。`clamp` 保留為**防禦性斷言**：正常輸入不應觸及邊界，觸及即代表權重或標準化有 bug，須寫 WARN log 而非靜默截斷。

**大盤 regime 必須是第五個因子組，不能是「正規化後再加減分」的調整項。** 這一點容易寫錯且後果嚴重：若在正規化完成後才套 `RISK_ON +5 / RISK_OFF −10`，值域立刻變成 `[-10, 105]`，強勢股遇 `RISK_ON` 得 105 被 clamp 成 100——本次修訂正要消滅的飽和問題原地重現，且會讓「極端輸入不得觸發 clamp WARN」的驗收測試與公式互斥。改為第五組後，regime 的不對稱需求（下檔比上檔重）由**組分數不對稱**表達：`RISK_ON` → `+0.5`、`NEUTRAL` → `0`、`RISK_OFF` → `−1`，而非由權重表達。

五組的權重、組內子因子權重與各子因子門檻，以 Requirement 43 修訂的 Acceptance Criteria 為唯一契約——比照 V3 的既有慣例，設計文件不複寫權重數字，避免兩處數字漂移。

### 缺值處理：權重重分配，不得補 0

這是本次設計最容易做錯的一點。子因子缺值時**必須回 `null` 並排除於加權平均之外**；整組皆 `null` 時該組權重按比例重分配給其餘有資料的組，實際生效權重總和恆為 1。

以 `0` 代替缺值等同「給中性分」，會把資料不足的標的系統性拉向 50 分。實際受害者是可枚舉的：

| 情境 | 缺什麼 | 若補 0 的後果 |
|---|---|---|
| 全部 ETF | 基本面組四項全缺（ETF 在來源是整筆不存在，非空值） | 20% 權重恆拉向中性 |
| **抓取上線後前 3 個月的全部個股** | 基本面組四項全缺（見下方時程） | 同上 |
| 009804（308 筆，2025-04 上市） | 3 年年化報酬（需 750 筆） | 長期組被稀釋 |
| 00929（753 筆，門檻 750） | 3 年報酬臨界 | 同上 |
| `BOND` 標的 | 大盤環境組（沿用 V3「債券不套台股大盤」語意） | 10% 權重失真 |

基本面組全缺時，短期／中期／長期／大盤由 `22/18/30/10` 依比例放大為 `27.5/22.5/37.5/12.5`。

（00919 有 905 筆，已滿足 750 門檻，是臨界值的對照組而非受害者。）

### 基本面組的實際生效時程——不是上線即完整

因來源只給當期快照且無合規歷史回補（見 Requirement 46），各子因子須等自建歷史累積到門檻才生效：

| 子因子 | 需要 | 抓取上線後可用 |
|---|---|---|
| 月營收 YoY | 3 個月（來源直給 YoY，不需去年基期） | 3 個月 |
| ROE | 4 季 | 約 1 年 |
| PE 自身歷史分位 | 250 交易日 | 約 1 年 |
| EPS 年增率 | 8 季（近四季 vs 前四季） | 約 **2 年** |

即：**前 3 個月基本面組整組不生效、第 1 年 2 項、滿 2 年才完整。** 這直接影響任務排程決策——故 Task 223 只實作長期趨勢組（用現有 10 年價格歷史，立即生效），基本面組的接線與啟用另立 Task 224。此時程須於前端揭露，不得讓使用者誤以為分數已含完整基本面判斷。

### 動作門檻分層：分數誠實，建議不誤導

本次修訂**不靠灌高分數**解決原始問題。0050 的短期確實在跌（跌破月線季線、KD 弱、單日 −5.87%），分數理應偏低——用權重把它推高會讓分數失去短期預警的意義。

改為讓**動作門檻**依長期趨勢組分數分層：

| 長期趨勢組分數 | 買進／加碼 | 續抱／觀察 | 警戒／觀望 | 減碼／迴避 |
|---|---|---|---|---|
| `≥ +0.5`（長期完好） | 75 | 55 | **30** | **15** |
| `−0.5 ~ +0.5`（中性） | 75 | 55 | 40 | 25 |
| `≤ −0.5`（長期轉弱） | **85** | 55 | 40 | 25 |

雙向處理，完整回應原始訴求：長期完好時不因短期回檔被叫減碼；長期轉弱時不因短期反彈被叫買進。長期趨勢組為 `null`（歷史不足）時套中性層，不得套寬鬆層。

三層區間**必須互斥且窮盡**，端點歸中性層。寫成 `≥ +0.5` 與 `−0.5 ~ +0.5` 會在 `+0.5` 重疊而使同一輸入可套兩層，兩層減碼門檻差 10 分，選錯直接改變動作輸出。

以 **006208** 於 2026-07-17 的實測輸入驗算（ETF，基本面組 null，權重 `27.5/22.5/37.5/12.5`）。**刻意不用 0050**：它於 2025-06-18 有 1:4 分割，長期因子的正確性取決於分割還原（見下節），拿它當示例會把兩個議題混在一起。

```text
短期組 = 0.35(−1) + 0.25(−1) + 0.25(−0.95) + 0.15(−1) = −0.9875
中期組 = −1
長期組 ≈ +0.936    （年線斜率 +22.2%、3 年年化報酬 +44%、52 週位置 +0.575）
大盤組 = 0（NEUTRAL）

Σ     = 0.275(−0.9875) + 0.225(−1) + 0.375(0.936) ≈ −0.146
score = 50 + 50 × (−0.146) ≈ 43
```

**43 分這個案例證明不了分層有效**：43 在寬鬆層（減碼門檻 30）與中性層（門檻 40）下都是 `HOLD_CAUTION`。把它從 V4 的 25 分（`REDUCE_CANDIDATE`）救起來的是**正規化**，不是分層。分層真正做功的是大盤 `RISK_OFF` 的情境：

```text
Σ = −0.146 + 0.125×(−1) ≈ −0.271  →  score ≈ 37

寬鬆層（門檻 30）→ 37 ≥ 30 → HOLD_CAUTION
中性層（門檻 40）→ 37 < 40  → REDUCE_CANDIDATE      ← 兩層輸出不同
```

驗收分層是否生效須以這類「兩層輸出不同」的輸入為準，不能拿 43 分的案例充數。

`< −0.5` 層的買進門檻 85 在基本面組接線前**不可達**（該情況下 `score` 上界為 71.9），屬預先定義，須以門檻選擇函數的單元測試驗證。

前端**必須揭露套用了哪一層與原因**（例：「長期結構完好，減碼門檻放寬至 30」），否則使用者看到 43 分卻是續抱會無法理解。

### 長窗因子的前置條件：分割還原

`DistributionAdjustedPriceService` 只處理現金股利與股票股利——`validEvent()` 僅接受 `cashDividend`／`stockDividend` 為正的事件，來源 `stock_dividend_history` 也只有這兩個欄位。`grep -ni "split|分割|拆股"` 在該檔零命中。

現行 241 根視窗恰好落在 0050 的分割之後，所以這個缺陷至今沒爆：

```
0050  2025-06-18   188.65 → 47.57   (−74.8%，1:4 分割)
      rn=240 為 2025-07-23（分割後）  ← V4 視窗到此為止
      rn=750 為 2023-06-08（分割前）  ← 長窗因子的新視窗端點
```

視窗擴大到 750 根後，0050 的 3 年年化報酬會算成 `−7.6%`（正確值 `+46.7%`）——方向相反且不拋例外。故**分割還原是長窗因子的前置任務（Task 225），不是可選項**。

偵測採序列啟發式，門檻由實測資料決定：

| | 判定 |
|---|---|
| `ratio = prevClose / close ≥ 2.0`（跌幅 ≥ 50%） | 正向分割 |
| `ratio ≤ 0.5`（漲幅 ≥ 100%） | 反向分割 |
| 二次驗證：比例須接近 `{2,3,4,5,10}` 之一，誤差 ≤ 10% | 否則維持原值並記 WARN |

**門檻不能設小。** 實測全台股序列中 ±15% 以上的跳空共 21 筆，僅 2 筆為真分割（`0050 −74.8%`、`2327 −73.8%`），其餘 19 筆分布在 `−22% ~ +47%`（停牌復牌、興櫃期間、資料源缺日）。真分割與雜訊之間有巨大安全間隙，50% 門檻在現有 10 年資料上零誤報零漏抓。

另須排除 `close_price <= 0` 的列（實測台股 176 筆），否則分割偵測會除以零、52 週相對位置會恆為 `+1`。

買進硬閘門（MA20＋MA60 雙 `ABOVE`、大盤非 `RISK_OFF`、非 stale）**完全不受分層影響**：分層只調分數門檻，長期組再高也不得繞過雙 `ABOVE`。

### 資料流

```text
TradingRadarService.buildStock()
  ├─ 既有：241 根完成日 K → DistributionAdjustedPriceService.adjust() → MA/KD/兩日確認
  ├─ Task 225：同一 adjust() 額外處理分割與非正收盤（長窗因子的前置條件）
  ├─ Task 223：750 根還原序列 → 年線斜率、52 週相對位置、3 年年化報酬
  └─ Task 224：stock_valuation_daily / _financial_quarter / _monthly_revenue
                → EPS 年增率、月營收 YoY、ROE、PE 分位
                 ↓
        TradingRadarRuleEngine.evaluateStock()
            五組標準化 → 組內加權 → 缺值重分配 → 組間加權 → 依長期組分數選門檻層
```

任務順序：**Task 225 → Task 223 → Task 224**；Task 222（爬蟲）與前三者無相依，可並行。

長期組子因子一律使用**還原權息序列**（與 MA／KD 同一價基）。V3 的既有硬約束不變：不得讓部分指標用還原值、部分用原始值。

取數視窗由 241 根擴大為 750 根（3 年年化報酬所需）。實測 `stock_price_history` 現有 154,706 列／70 檔／2016-07 起滿 10 年，主流標的 2400+ 筆，容量無虞；新掛牌 ETF 的不足由前述權重重分配吸收。

**Task 223 只實作長期趨勢組**（資料現成，立即生效）。基本面組的權重位置在 V5 就定義好，但四個子因子在 Task 224 接線前恆為 `null`，由權重重分配吸收——這樣 Task 224 上線時不需要再改一次權重定義，避免二次規則版本變更。

### 三個必須連帶處理的下游影響

1. **通知基準須全部重建**：V5 分數與 V4 不可直接比較。若讓 V4 的 `last_action` baseline 與 V5 動作直接比對，升級後首輪會判定為大量「轉入」而觸發假通知潮。升級後首輪一律只建基準不寄信（與 Requirement 44「設定變更後首次評估只建基準」的既有語意一致）。**Task 224 讓基本面組由 `null` 轉為有值時，分數同樣會跳變，須以相同方式處理。**
2. **跨標的分數不可比**：不同標的因缺值而套用不同的實際生效權重（ETF 無基本面組、新掛牌無 3 年報酬、`BOND` 無大盤組），分數的組成結構不同。API 須回傳實際生效權重供判讀，前端須揭露「分數用於同一標的的時序比較與動作判定，跨標的排序僅供參考」。這是 V5 內部的可比性界線，與 V4↔V5 的不可比性是兩件事，都要處理。
3. **門檻分層是設計常數，不是事後校準參數**：三層門檻表由本設計直接給定（見上），不是留給實作者依分布反推——後者會使「特定標的不再是減碼候選」的驗收變成循環論證（門檻是自由參數，調到過為止）。實作仍須輸出全部台股標的的 V4／V5 分數與動作對照表附於完成報告，但那是**觀測記錄**，不是門檻的決定依據。

### 驗證重點

- **不再飽和**：全部子因子皆 `+1`、皆 `−1`、以及疊加 `RISK_ON`／`RISK_OFF` 的極端輸入，分數落在 `[0, 100]` 且未觸發 clamp WARN。（疊加 regime 這一項是關鍵：它會抓出「把 regime 寫成正規化後調整項」的錯誤實作。）
- **52 週位置的開放區間**：現價 > 52 週高（創新高當日）時，經 clamp 後貢獻仍為 `+1`。此路徑平時測不到。
- **權重重分配**：任一組全 `null` 後，實際生效權重總和為 1；ETF 路徑基本面組全 `null` 且四組放大為 `27.5/22.5/37.5/12.5`。
- **虧損語意與優先序**：有列且 `pe_ratio IS NULL`（虧損）計為 `−1`，且**優先於**「歷史樣本不足回 `null`」——上線首年兩條件必然同時成立，須有交集測試。
- **PE 與 EPS 的交互**：`EPS 年增率 = −1` 時 PE 分位回 `null`（防止長期衰退股靠低 PE 永久取得正分）。
- **除以零與負基期**：EPS 前四季合計 `≤ 0`、52 週高低相等、權益 `≤ 0` 皆回 `null`，不得回 `+1` 或拋例外。
- **門檻分層邊界**：長期組 `+0.5` 與 `−0.5` 兩個交界各測上下；長期組為 `null` 時套中性層。
- **硬閘門未被繞過**：分數再高但 MA20 確認為 `MIXED` 時仍不得產生買進／加碼；`RISK_OFF` 與 stale 時同樣禁買。
- **結構性迴歸判準**（取代釘死特定標的動作的循環論證）：固定其他輸入、僅將長期趨勢組分數由 `−1` 掃到 `+1`，總分須單調遞增，且變動幅度 ≥ 短期動能組做同樣掃描時的變動幅度。

---

## Requirement 47（Task 227）：匯率曝險與換匯估值

### 為什麼技術面訊號會被匯率污染

台幣計價、持有美元資產的 ETF，其台幣報價 ≈ 底層美元價 × USD/TWD。實測近一年日收盤與 USD/TWD 中價的相關性：

| 標的 | 存續期 | 水準相關 | 日報酬相關 |
|---|---|---|---|
| 00719B | 1–3 年 | **0.9737** | **+0.6763** |
| 00697B | 7–10 年 | 0.8449 | +0.3057 |
| 00679B | 20 年 | 0.5864 | +0.0845 |
| 0050（對照） | — | 0.7175 | **−0.5339** |

相關性隨存續期單調遞減——短債幾乎沒有利率風險，剩下的幾乎全是匯率。這個模式交叉驗證了它不是統計巧合。0050 的水準相關 0.72 是共同趨勢造成的偽相關，日報酬即為負。

剝除匯率後（`implied = 台幣收盤 / 中價`），00719B 過去一年台幣價變動 `10.34%`、底層僅 `2.05%`。而 2026-07-17 的 USD/TWD 為 32.23，一年分位 `99.2`、全歷史 `91.8`，自身亦為多頭排列（32.23 > 32.00 > 31.66 > 31.29）。

**即：系統給 00719B 滿分的「站上月／季／年線」，跟 USD/TWD 自己的均線排列是同一件事——它在美元逼近一年新高時，把「美元多頭排列」翻譯成「債券 ETF 買進候選」。**

### 設計取捨：揭露＋獨立因子，而非改變價基

有兩條路可走：

| 取向 | 優點 | 缺點 |
|---|---|---|
| 剝離匯率後算技術面（`implied` 序列） | 訊號純粹反映債券本身 | **不對應使用者實際承受的價格波動**——他的部位與損益是台幣計價的 |
| **維持台幣價基＋獨立匯率因子＋UI 揭露**（採用） | 貼合實際損益，且換匯貴賤獨立可見 | 趨勢訊號本身仍含匯率成分，靠揭露補足 |

採後者。此取捨須寫入 `TradingRadarService` 註解，日後若要改價基須另立 Requirement 並評估歷史分數可比性。

### 匯率環境組

評分因子之一。**實際落地採扁平因子權重，非巢狀分組**——匯率因子與其餘技術面因子並列於同一層加權，權重 `0.05`（`TradingRadarRuleEngine.W_FX`），完整扁平權重表見 Requirement 47 落地說明與 `requirements.md`（`TW_RULES_V7`）。單一子因子：

```text
組分數 = clamp(−(fxPercentile − 50) / 50, −1, +1)
```

`underlying_currency = TWD` 時該組回 `null`，走既有權重重分配——**台股標的不因匯率被加減分**。

回看期取**五年**（`TradingRadarService.FX_LOOKBACK_YEARS = 5`）。同一天在不同回看期的分位差異極大（一年 99.2／三年 73.2／五年 83.6／全歷史 91.8）：一年過短會使因子在趨勢行情中長期釘在極值而失去區辨力，三年未涵蓋台幣由強轉弱的完整週期，全歷史涵蓋不同匯率制度；五年（實測 1247 筆、區間 27.53–33.14）涵蓋完整週期且不極端。權重與回看期皆為具名常數。

幣別判定：`stock.underlying_currency` 顯式欄位優先，null 時依 `market` 推斷（美股→USD、英股→GBP、台股→TWD）。**嚴禁以名稱字串比對**（如含「美債」），該做法在更名時會靜默失效。

---

## Requirement 43 修訂補充（Task 226）：KD 拆分與分批試單

### KD 為什麼必須拆成兩個子因子

V4 把「動能」與「絕對位置」混在一組加減分裡。`K=90.8, D=86.0` 與 `K=26.1, D=20.7` 同樣是 `K>D`，但前者是漲多了、後者是跌深反彈——方向完全相反。

實測證實混合設計無法修補：若採 `base=(K−D)/10` 再對超買扣固定 `−0.3`，`K=90.8／D=86.0` 得 `+0.18` **仍為正貢獻**。故拆為：

```text
KD 動能 = clamp((K − D) / 10, −1, +1)
KD 位置 = clamp(−(avg(K, D) − 50) / 50, −1, +1)
```

| 標的 | K / D | 動能 | 位置 |
|---|---|---|---|
| 00719B | 90.76 / 86.01 | `+0.48` | **`−0.77`** |
| 00679B | 26.10 / 20.70 | `+0.54` | **`+0.53`** |

**窄幅防護**：`(hi9 − lo9) / lo9 < 2%` 時 KD 位置回 `null`。00719B 近 60 日 9 日高低帶平均寬度僅 `1.011%`，其 `K=90.76` 實質只代表「比 9 日低點高 0.37 元」——把股票的 80／20 閾值套在這種標的上屬指標誤用。KD 動能不受此限。

### 過熱判定與偏熱揭露（V7，Task 232）

**兩個 KD 子因子在高檔會互相抵消。** K 剛突破 80 時正是 K 大幅領先 D 之際，動能子因子因此接近飽和地給出正分，抵掉位置子因子的扣分。實測 00882（K 82.3／D 75.2）：

```text
動能 +0.71 × 0.08 = +0.057
位置 −0.575 × 0.13 = −0.075     淨 −0.018 → 100 分制僅扣 0.9 分
```

這使「短線過熱」在分數層面幾乎不可見——與 V4 因 clamp 飽和而失效的成因不同（見下節），但結果相同。故過熱一律走**閘門**而非扣分：

```java
kdOverheated = avg(K, D) > KD_OVERHEAT_AVG(80.0) || K > KD_OVERHEAT_K(85.0)
buyGate = marketAllowsBuy && MA20 ABOVE && MA60 ABOVE && !kdOverheated && !fxExpensive
```

**「否決」的實際語意是降級，不是阻斷。** `kdOverheated` 只令 `buyGate` 為 false 且**不扣分**；分數維持原值後落入既有的 `score >= 55` 分支，已持有得 `HOLD`、未持有得 `WATCH`。過熱標的因此仍保有原始分數與其餘理由，畫面能同時呈現「長線結構良好」與「短線過熱故本日不加碼」。不為此新增 enum 值。

**`K > 85` 這道門檻沒有回測依據，是刻意的風險偏好取捨。** 十年台股 38,186 個「買進閘門其他條件成立」樣本中，37,720 個具完整 20 交易日後續者分組回測顯示，過熱組的後續下檔風險反而**低於**正常放行組（20 日內跌逾 10% 的比例：`avg>80` 組 `11.1%`、`K>85` 組 `10.0%`、正常放行組 `15.9%`），分年檢視含 2018／2022 下跌年皆一致。採納理由是使用者不願在單一指標極端超買時收到加碼建議。**`85` 相對 `80` 的選擇依據是受影響樣本量**（`0.63%` vs `7.10%`），即在一條無實證支持的規則上盡量縮小影響面——下檔風險數據對 `80` 與 `85` 皆不支持，不構成選定理由。**不得在註解或 UI 文案中宣稱此門檻有實證支持**；完整數據與已知偏誤見 Requirement 43 修訂（`TW_RULES_V7`）。同理，過熱風險文案不得再宣稱「回檔機率升高」——該說法與本專案自身資料矛盾。

**過熱閘門與偏熱揭露不套用窄幅防護。** 上一段的窄幅防護（`(hi9 − lo9)/lo9 < 2%` 時 KD 位置回 `null`）**只作用於 KD 位置子因子的計分**，不作用於過熱閘門與 `kdHeat` 三態。兩者目的不同：窄幅防護是避免讓雜訊等級的 KD 去**加減分數**；而過熱閘門與揭露的作用是「在使用者可能追高時不主動建議買進、並如實顯示指標讀數」——即使 00719B 這類窄幅標的的 `K=90.76` 實質意義薄弱，對它顯示「K 已達 90.8」仍是正確的陳述，且不主動建議加碼是保守方向。故窄幅標的照常適用 `kdOverheated` 與 `kdHeat`，不設豁免分支。

**偏熱揭露必須在收合列可見。** `reasons`／`risks` 僅在表格列展開後顯示，而使用者回報的正是收合狀態下看不出 K 已達 82.3。故 `K > 80 || avg(K,D) > 70` 但未達過熱門檻時，除輸出資訊性提示外，須於收合列既有 KD 欄加註視覺標記，且**過熱（已降級）與偏熱（未降級）的標記須可區分**。此揭露不影響分數與動作。**兩個觸發分支的文案不得共用同一句**：`K > 80` 命中時述 K 值，僅 `avg > 70` 命中時述均值（`K=70, D=75` 會使 `avg=72.5` 觸發偏熱而 K 並未高於 80，共用 K 版文案會輸出假陳述）。

### 為什麼 V4 的過熱扣分等於不存在

均線六項全滿 `= +58`，`base 50 + 58 = 108` 已超過 clamp 上限。KD 的整個影響區間（`−5 ~ +8`）都在天花板之上。實測 00719B 原始分 `110`、00697B `113`，掃描 K/D 從 `(5,10)` 到 `(95,92)` **分數恆為 100、動作恆為買進候選**——KD 對這類標的是完全無資訊的維度，那條 `−3` 是數學上的無效程式碼。要靠加重扣分修正需 `−35` 以上。

這證明：**任何新增因子若不先解決飽和，在極端標的上都會完全失效**。這是 Task 223 分組加權正規化的必要性證據。

### 分批試單路徑

買進閘門要求 MA20／MA60 雙 `ABOVE`，超賣幾乎必然發生在跌破均線時，兩者對同一組 enum 互為否定。十年全樣本實測：

```
總交易日 103,040 ／ K<20 且 D<20 6,552 次 ／ 買進閘門成立 41,808 次 ／ 兩者共存 0 次
```

**一次都沒發生過**，故 V4 的「KD<20 且 K>D 加 3 分」十年來從未促成任何買進。

解法**不是放寬 buyGate**（會讓所有下跌趨勢標的一併可買），而是新增平行路徑輸出 `TRIAL_BUY`（分批試單），六項條件全滿足才觸發：長期趨勢組 `> +0.5`、KD 位置 `> +0.5`、KD 動能 `> 0`、前期 `K ≤ D`、完成日漲跌幅 `≥ 0`、`EQUITY` 須大盤非 RISK_OFF 非 stale。

`TRIAL_BUY` 觸發時主 `action` 即為 `TRIAL_BUY`，既有 `counterTrendState` 降為診斷欄位——不得再出現「主建議＝減碼候選、逆勢狀態＝逆勢試單候選」的矛盾組合（既有單元測試已把該矛盾寫死，須一併修正）。

---

## Requirement 48：交易雷達結果快照與 Excel 區間匯出

交易雷達本體為純即時運算、結果不落地（`TradingRadarService.get()` 標 `@Transactional(readOnly=true)`，算完組 DTO 即丟）。本 Requirement 讓每次頁面計算的結果保存為帶時間戳的 **per-owner Redis 快照**，並提供區間 Excel 匯出。**快照刻意不新增 PostgreSQL 資料表**（結果只存 Redis；Task 231 新增的兩張表僅存**排程設定**，不存任何雷達結果）、**不回補上線前歷史**。

### 快照寫入（修訂 Requirement 43 的「GET 無寫入」）

`TradingRadarService.get()` 算完 `TradingRadarDto.Response` 後，若當前為 HTTP 請求且 `CurrentUserContext.hasUser()`，呼叫 `TradingRadarSnapshotStore.save(effectiveUserId, response)` 寫一筆快照，再回傳。整段 **fail-soft**，Redis 例外只記 log 不影響回應。

```
GET /api/bff/trading-radar → (Gateway rewrite) → GET /api/trading-radar
  → TradingRadarService.get()
      → 組 TradingRadarDto.Response（既有）
      → TradingRadarSnapshotStore.save(effectiveUserId, response)   ← 新增，fail-soft
  → 回傳 response
```

`TradingRadarSnapshotStore` 集中掌管 key 結構與序列化，寫入與匯出讀取共用同一支（避免 key 格式在兩處漂移）。owner 取 `CurrentUserContext.getEffectiveUserId()`（admin 代看為被代看者，與 `ownerFilter` 對齊）。大盤 `MarketSummary` 雖全域相同，仍整份隨 per-owner 快照存放，讓每份快照自足、匯出免跨 key 拼裝（快照屬歷史回溯用途，比照 `asset_snapshot` 的匯總欄位例外）。

### Redis key 結構與容量控制

單一 Redis（database 0，與 `price:*` 即時價共用；`--maxmemory 256mb --maxmemory-policy allkeys-lru`）。因 LRU 會逐出任意 key，快照必須受控以免擠掉即時股價：

| 用途 | key | 型別／值 |
|---|---|---|
| 快照內容 | `trading-radar:snap:{ownerId}:{epochMillis}` | String＝`Base64(gzip(JSON))`，TTL＝保留窗 |
| 快照索引 | `trading-radar:snap:idx:{ownerId}` | Sorted Set，member＝`epochMillis`、score＝`epochMillis` |
| 去重雜湊 | `trading-radar:snap:hash:{ownerId}` | String＝上一筆內容雜湊，TTL＝保留窗 |

`epochMillis` 取自 `Response.generatedAt`。四道控制（皆可由設定覆寫）：

- **節流** `SNAPSHOT_MIN_INTERVAL`（預設 5 分鐘）：以索引 ZSet 最大 score 為上次寫入時間，未達間隔不寫。盤中頁面經既有 SSE（`/api/market-data/prices/stream`）每約 2 秒重讀 `get()`，節流把實際寫入收斂到每 5 分鐘一筆。
- **去重**：與上一筆快照內容（移除 `generatedAt` 後的 JSON）雜湊相同者不寫（盤後頁面開著但內容未變時不重複累積）。
- **壓縮**：gzip＋Base64（`StringRedisTemplate` 存字串）。
- **保留窗** `SNAPSHOT_RETENTION`（預設 90 天，短於 12 個月以配合 256MB）＋ **per-owner 筆數硬上限**（`maxPerOwner` 預設 5000）：value key TTL＝保留窗；每次寫入 inline 兩道修剪——`ZREMRANGEBYSCORE idx 0 (now−保留窗)`（時間窗）與 `ZREMRANGEBYRANK idx 0 -(maxPerOwner+1)`（只保留最新 N 筆），索引 ZSet 亦 refresh 一個略長於保留窗的 EXPIRE。**保留窗修剪一律 inline、不設排程**——Task 231 新增的 `@Scheduled` 只負責排程觸發寫檔，不參與快照修剪。

配合 gzip、90 天保留窗與 per-owner 筆數硬上限，單一使用者 footprint 有確定天花板（約數十 MB），對 256MB 為可控且以即時股價快取為優先——僅靠時間窗在多使用者高頻情境下仍可能累積上萬筆而逼近上限，筆數硬上限即為此而設。保留窗為 best-effort：LRU 壓力下可能更早被逐出，活躍使用者增長時應監控記憶體並視需要調高 `maxmemory`。

### 匯出

business `GET /api/trading-radar/export?from&to`（`ResponseEntity<ByteArrayResource>`，比照 `RealizedGainController.exportExcel`）。owner 取 `getEffectiveUserId()`；`from`／`to` 為 Asia/Taipei ISO local datetime → epoch 毫秒（`from > to` 或格式錯誤回 400）。`TradingRadarExportService`：

```
members = ZRANGEBYSCORE snap:idx:{ownerId} fromEpoch toEpoch
for m in members: raw = GET snap:{ownerId}:{m}
  if raw == null: 跳過（TTL/LRU 逐出）並計缺漏數
  node = ObjectMapper.readTree(gunzip(Base64.decode(raw)))   // JsonNode，容忍缺欄位
→ POI 產三分頁：快照索引 / 大盤總覽 / 個股決策
   （每列一快照，或一(快照,個股)；含快照時間欄；所有日期時間欄以 ISO 文字寫入）
   「快照索引」分頁首列放缺漏彙總「查得 X／索引預期 Y／缺漏 Z」，讓部分被逐出對使用者可見（不只進 log）
```

零快照時仍回含表頭的合法 `.xlsx`（缺漏彙總列註明查無快照），不回 5xx。BFF 走既有 `TradingRadarBffRoutes` passthrough，二進位與下載 header 原樣穿透，不新增程式。前端 `TradingRadarView.vue` 新增「匯出 Excel」按鈕與 datetime 區間對話框，沿用 `ExchangeRateView.vue` 的 `saveBlob`（`showSaveFilePicker` 指定目錄，fallback 一般下載）；`api/index.js` 的 `tradingRadar` 新增 `exportExcel(from, to)`。

### 測試與驗證

節流／去重／gzip 往返／保留窗修剪／寫入 fail-soft；匯出多快照三分頁、時間欄文字、value 缺漏跳過、空區間回含表頭合法檔。部署後以 `X-User-*` header 觸發 `get()` 寫入、`redis-cli` 驗索引與 TTL、`export` 取回 `.xlsx`，並確認 `price:*` 未被大量快照擠出。

### 排程自動匯出到指定伺服器目錄（Task 231）

與手動瀏覽器下載**並存**，兩者共用同一支產檔邏輯（`TradingRadarExportService`），不得各自實作。比照「爬蟲執行時間設定」提供**多個**每日執行時間點；到點由背景排程把當日快照寫成 Excel 到使用者指定的伺服器資料夾。

**資料模型（兩張表，per-owner）** — 時間點與資料夾**刻意分表**，沿用 `crawler_schedule`／`crawler_export_setting` 的既有理由：併表會使同一路徑隨時間點列數重複儲存（違反正規化），且刪一個時間點會連帶弄丟路徑。

| 表 | 欄位 | 說明 |
|---|---|---|
| `trading_radar_export_time` | `id / owner_user_id / run_hour / run_minute / enabled / last_run_date / updated_at`，UNIQUE `(owner_user_id, run_hour, run_minute)` | 一列一時間點。**`last_run_date` 在時間點列上**——當日 guard 必須 per 時間點，否則同日多時間點只跑第一個 |
| `trading_radar_export_setting` | `id / owner_user_id`(UNIQUE)`/ output_subpath / last_run_at / last_run_status / updated_at`＋`gdrive_enabled / gdrive_subpath / gdrive_last_run_at / gdrive_last_status`（Requirement 51 / Task 245，`v1.76.0`；型別見「推廣至其餘八個匯出頁」段的統一定義） | 一使用者一列，只存**相對子路徑**。**注意執行時間點在另一張 `trading_radar_export_time`，故本頁一天可能上傳多次**，`gdrive_last_status` 為「最後一次」語意 |

兩表皆 `@Filter(ownerFilter)`。背景排程無 request context → filter 不啟用，`findAll()` 讀全部 owner 列；**owner 取自列上的 `owner_user_id` 並顯式傳入快照讀取**（Redis key 本就 owner-scoped，不依賴 Hibernate filter），背景路徑不得用 request-scoped 的 `CurrentUserContext`。

**排程機制**（照抄 `IndexExportScheduleService`，不自創）：

```
@Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei") tick()   ← AtomicBoolean 防重入
@EventListener(ApplicationReadyEvent.class) selfHealOnStartup() ← 開機補跑當日已到點未執行者
runDueExports():
  for 每列 enabled 的 trading_radar_export_time（findAll，跨 owner）:
      if today == last_run_date: continue                    ← 當日 guard（per 時間點）
      if now >= LocalTime(run_hour, run_minute):             ← 非「分鐘精確相等」，避免卡分鐘整日漏跑
          export(ownerId)；成功/失敗都設 last_run_date=today ← 單一 owner 失敗不影響他人
```

**寫檔**：讀該 owner 當日 00:00～當下的 Redis 快照 → 同一支三分頁 Excel → 檔名 `交易雷達_{ownerId}_{yyyyMMdd}.xlsx`（同日覆寫、跨日新檔，比照爬蟲 `public_info_<日期>.json`；含 ownerId 因多使用者可能共用同一目錄）。

**當日零快照時不寫檔**：快照只在使用者開啟／刷新雷達頁的 HTTP 路徑產生（背景不產生），故排程時間點前若使用者當天沒開過頁面即查無快照。此時**不寫檔**（避免每天留下只有表頭的無用檔、並保留前一版不被覆蓋），只把 `last_run_status` 記為「當日尚無快照，未產檔」並**仍設當日 guard**。這是本功能與爬蟲的語意差異：爬蟲是排程自己去抓，本功能是把使用者當天看過的雷達倒出來，須於前端設定卡明示。路徑＝`EXPORT_OUTPUT_DIR`（`/home/steven`，volume 對映 host 家目錄）resolve 相對子路徑，`normalize()` 後須仍 `startsWith(base)`（拒 `..`／絕對路徑），`Files.createDirectories` 自動建目錄，先寫 `.tmp` 再 `ATOMIC_MOVE`（不支援退 `REPLACE_EXISTING`）。

**business-services 可寫主機家目錄**（`docker-compose.yml`：`EXPORT_OUTPUT_DIR: /home/steven` ＋ `${EXPORT_OUTPUT_DIR_HOST:-/Users/steven}:/home/steven`），故排程放 business（同時握有 Redis 快照與 POI）。目錄列舉沿用 Requirement 34 既有 `GET /api/export-schedule/browse?subpath=`，不新增端點。新增的 `@Scheduled` 須同步登錄 `SchedulePublicBffController.JOBS`。

## Requirement 49（Task 237／238）：資產交易紀錄（手動買賣流水帳）與 Excel 手動／每日排程匯出

拆為兩支任務落地：**t237** 為核心（entity／CRUD／手動下載／前端頁與選單），**t238** 為每日排程自動匯出（比照 Requirement 39 的整套排程形狀）。

### 定位（與既有功能的邊界）

「交易紀錄」是一份**獨立的買賣流水帳**（flow event ledger），記錄每一筆買進／賣出。刻意與既有三者切分：

| 功能 | 語意 | 資料表 |
|------|------|--------|
| 交易紀錄（本需求） | 買賣事件流水帳（flow） | `asset_transaction`（新） |
| 已實現損益（Requirement 6） | 賣出且已結算之損益 | `realized_gain` |
| 歷年資產快照（Requirement 1） | 某時點資產存量（snapshot） | `asset_snapshot` + 子表 |

三者**互不自動衍生、不共用資料表**。交易紀錄不會自動產生／修改 `realized_gain`／`stock_holding`／`asset_snapshot`，也不由它們反推——避免「同一事實跨表存兩份」的一致性問題（CLAUDE.md 資料庫正規化）。本需求範圍即這份流水帳的 CRUD 與匯出，**不涉及**以交易紀錄重算持股或損益（若日後要做，屬另一需求，須另立設計）。

### 架構與資料流

沿用 Requirement 39（已實現損益）的整體形狀——手動下載、立即匯出到目錄、每日排程、目錄瀏覽四條路徑，差異只在「匯出哪份活頁簿」與「新增一張獨立流水帳表」。

```
【手動 CRUD（t237）】
TransactionView 新增/編輯/刪除
  → /api/bff/transaction/* (list/create/update/delete)
    → business /api/asset-transactions/*
      → AssetTransactionService（HTTP：TenantFilterAspect 自動 owner-scoped）
      → AssetTransactionRepository（@Filter(ownerFilter)）

【手動下載（t237）】
TransactionView「匯出 Excel」
  → GET /api/bff/transaction/export (blob)
    → business GET /api/asset-transactions/export
      → ExcelExportService.exportAssetTransactions()        ← HTTP：TenantFilterAspect 自動 owner-scoped

【立即匯出到目錄（t238）】
TransactionView「立即匯出到目錄」
  → POST /api/bff/transaction/export/run-now
    → business POST /api/asset-transactions/export/run-now
      → AssetTransactionExportScheduleService.runNowForCurrentUser()
        → ExcelExportService.exportAssetTransactions()      ← HTTP 情境，同上自動 owner-scoped
        → writeToDir(ownerId, subpath, data)

【每日排程（t238）】
@Scheduled(cron="0 * * * * *", zone="Asia/Taipei") tick()
  → runDueExports(): settingRepo.findAll()                 ← 背景無 request context，讀全部 owner 列
    → 對每個 enabled 且今日未跑且已到點的列：
      → ExcelExportService.exportAssetTransactionsForOwner(ownerId)   ← 手動 enableFilter 縮到該 owner
      → writeToDir(ownerId, subpath, data)

【資料夾瀏覽（複用既有 business 端點）】
TransactionView el-tree 懶載入
  → GET /api/bff/transaction/export/browse?subpath=  ← 本頁自己的 BFF 路由（一頁一 BFF）
    → business GET /api/export-schedule/browse        ← 複用 Requirement 34 既有端點，不新增
```

### 關鍵設計決策

1. **獨立流水帳表、不與 realized_gain／snapshot 合併或互相衍生**：交易紀錄是 flow event，realized_gain 是賣出結算、snapshot 是時點存量，三者語意不同。合併或讓交易紀錄自動生成持股／損益，會把同一事實存兩份並引入跨表一致性維護成本（CLAUDE.md 正規化）。故新增獨立 `asset_transaction`，比照 `RealizedGain` 的「獨立、不關聯快照」定位。

2. **不存衍生值**：`amount`（成交金額，含費用後實際交割金額）與 `shares × price` 不必然相等（手續費／交易稅／零股價差），三者為各自獨立輸入、非彼此衍生（比照 `realized_gain` 同存 `shares`／`salePrice`／`proceeds`／`investmentCost`）；`year` 以 `@Transient` 由 `tradeDate` 衍生；`amountTwd` 於 DTO 層即時算（`currency==USD ? amount×exchangeRate : amount`）。交易紀錄**不計算損益**。

3. **交易類型／資產類型為字串、非寫死 enum**：`買/賣`、`股票/基金` 以字串存欄位；市場／券商下拉沿用既有 `MarketType`／`BrokerEntity` 主檔（CLAUDE.md「禁止 Enum 寫死」）。`channel`（券商／通路）存成交當下名稱字串（刻意 denormalize，比照 `realized_gain.broker`）。

4. **排程比照 Requirement 39、一功能一張排程表**：新增 `asset_transaction_export_schedule`，不與既有排程表合併（各表欄位語意與產出內容不同，合併需動既有 UNIQUE 約束與既有列，風險大於收益）。目錄瀏覽複用 `GET /api/export-schedule/browse`、不複製。

5. **背景排程的租戶隔離（關鍵）**：`AssetTransaction` 帶 `@Filter(ownerFilter)`；背景 `findAll()` 若不 `enableFilter` 會把**所有使用者的交易紀錄寫進每個人的檔案**。故新增 `exportAssetTransactionsForOwner(Long ownerId)`，比照 `exportRealizedGainsForOwner` 在 session 手動啟用 filter。

6. **排程與手動產出同一份**：`exportAssetTransactions()` 與 `exportAssetTransactionsForOwner()` 共用同一個 `buildAssetTransactionsWorkbook()`，三個入口（下載／run-now／排程）內容一致。

### 資料模型

**t237 — 新表 `asset_transaction`**（Liquibase `v1.72.0-asset-transaction.sql`）：

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `owner_user_id` | BIGINT NOT NULL | 擁有者；`@Filter(ownerFilter)` 隔離 |
| `transaction_type` | VARCHAR(10) NOT NULL | 買 / 賣 |
| `asset_type` | VARCHAR(10) NOT NULL | 股票 / 基金 |
| `asset_name` | VARCHAR(50) NOT NULL | 資產名稱 |
| `asset_code` | VARCHAR(20) | 資產代號 |
| `market` | VARCHAR(20) | 市場（對應 MarketType.code） |
| `currency` | VARCHAR(10) | 幣別 TWD/USD |
| `channel` | VARCHAR(30) | 券商／通路（成交當下名稱字串） |
| `trade_date` | DATE NOT NULL | 交易日期 |
| `shares` | NUMERIC(15,5) | 數量（股數／單位數） |
| `price` | NUMERIC(17,6) | 成交單價（原幣，小數 6 位；Task 239 由 (15,4) 以 v1.74.0 ALTER 加寬） |
| `amount` | NUMERIC(20,2) NOT NULL | 成交金額（原幣，含費用後實際交割金額） |
| `exchange_rate` | NUMERIC(10,4) | 交易當天匯率（USD 用） |
| `notes` | VARCHAR(500) | 備註 |

> 無 seed（使用者自建資料）。`year` 不建欄位（`@Transient` 衍生）。

**t238 — 新表 `asset_transaction_export_schedule`**（Liquibase `v1.73.0-asset-transaction-export-schedule.sql`）：

| 欄位 | 型別 | 說明 |
|------|------|------|
| `id` | BIGSERIAL PK | |
| `owner_user_id` | BIGINT NOT NULL | 擁有者；UNIQUE `uq_at_export_schedule_owner`（每人一列） |
| `enabled` | BOOLEAN NOT NULL DEFAULT FALSE | 是否啟用每日排程 |
| `run_hour` | INT NOT NULL DEFAULT 8 | 每日執行時，CHECK 0..23 |
| `run_minute` | INT NOT NULL DEFAULT 0 | 每日執行分，CHECK 0..59 |
| `output_subpath` | VARCHAR(255) NOT NULL DEFAULT 'input' | 相對家目錄基底的輸出子路徑 |
| `last_run_date` | DATE | 當日已執行 guard（成功／失敗都設） |
| `last_run_at` | TIMESTAMP | 上次執行時間 |
| `last_run_status` | VARCHAR(500) | 「成功：/path」或「失敗：訊息」 |
| `updated_at` | TIMESTAMP | |

> ＋ **`gdrive_enabled` / `gdrive_subpath` / `gdrive_last_run_at` / `gdrive_last_status`**（Requirement 51 / Task 242，changeset `v1.76.0`）——型別與語意見「推廣至其餘八個匯出頁」段的統一定義。

### API 端點

| 層 | 方法 路徑 | 任務 | 說明 |
|----|-----------|------|------|
| business | `GET /api/asset-transactions` | t237 | 回各年度彙總（含各年度 records，依 tradeDate 新到舊）；年度篩選由前端客戶端切換（比照已實現損益頁） |
| business | `POST /api/asset-transactions` | t237 | 新增一筆 |
| business | `PUT /api/asset-transactions/{id}` | t237 | 編輯一筆 |
| business | `DELETE /api/asset-transactions/{id}` | t237 | 刪除一筆 |
| business | `GET /api/asset-transactions/export` | t237 | 下載 xlsx（涵蓋所有年度） |
| business | `GET /api/asset-transactions/export/schedule` | t238 | 取當前使用者排程設定（無則回預設，不寫 DB） |
| business | `PUT /api/asset-transactions/export/schedule` | t238 | upsert 排程設定（驗證時分範圍與子路徑不跳脫） |
| business | `POST /api/asset-transactions/export/run-now` | t238 | 立即產檔到設定目錄，回 `{path, sizeBytes}`；不動當日 guard |
| business | `GET /api/export-schedule/browse?subpath=` | 既有複用 | 列出基底下子目錄 |
| business | `GET /api/stock-alerts/lookup-name?code=&market=` | 既有複用 | 依 code+market 查 stock 主檔回股名（`{stockName}`）；輸入代號自動帶名用 |
| BFF | `GET/POST/PUT/DELETE /api/bff/transaction[/{id}]` | t237 | 列表聚合（含市場／券商下拉）＋ passthrough CRUD |
| BFF | `GET /api/bff/transaction/lookup-name?code=&market=` | t237 | passthrough 至 business `GET /api/stock-alerts/lookup-name`（輸入代號自動帶股名，複用同一支 business API，比照 `RealizedGainBffController.lookupName`） |
| BFF | `GET /api/bff/transaction/export` | t237 | passthrough 下載 |
| BFF | `GET /api/bff/transaction/export/schedule` | t238 | passthrough |
| BFF | `PUT /api/bff/transaction/export/schedule` | t238 | passthrough |
| BFF | `POST /api/bff/transaction/export/run-now` | t238 | passthrough |
| BFF | `GET /api/bff/transaction/export/browse` | t238 | passthrough 至 business `/api/export-schedule/browse`（subpath 需 URL-encode） |

### 新增／異動檔案

**t237 新增**
- `backend/.../model/AssetTransaction.java`
- `backend/.../repository/AssetTransactionRepository.java`
- `backend/.../service/AssetTransactionService.java`（CRUD＋列表／年度篩選）
- `backend/.../controller/AssetTransactionController.java`（`@RequestMapping("/api/asset-transactions")`，含 `GET /export`）
- `backend/.../dto/AssetTransactionDto.java`（Create/Response/YearSummary）
- `backend/src/main/resources/db/changelog/changes/v1.72.0-asset-transaction.sql`
- `bff/.../transaction/TransactionBffController.java`＋`TransactionBffRoutes.java`
- `frontend/src/views/TransactionView.vue`

**t237 異動**
- `ExcelExportService.java`：新增 `buildAssetTransactionsWorkbook()`／`writeAssetTransactionsSheet()`／`exportAssetTransactions()`
- `db.changelog-master.yaml`：註冊 v1.72.0
- `frontend/src/api/index.js`：新增 `transaction` 命名空間
- `frontend/src/App.vue`：`mainMenuItems`「資產管理」子選單新增「交易紀錄」
- `frontend/src/router/index.js`：註冊 `/transactions` 路由

**t238 新增**
- `backend/.../model/AssetTransactionExportSchedule.java`
- `backend/.../repository/AssetTransactionExportScheduleRepository.java`
- `backend/.../service/AssetTransactionExportScheduleService.java`（tick／self-heal／run-now／設定 CRUD／路徑驗證）
- `backend/.../controller/AssetTransactionExportController.java`（`@RequestMapping("/api/asset-transactions/export")`）
- `backend/.../dto/AssetTransactionExportDto.java`
- `backend/src/main/resources/db/changelog/changes/v1.73.0-asset-transaction-export-schedule.sql`

**t238 異動**
- `ExcelExportService.java`：新增 `exportAssetTransactionsForOwner(Long)`（手動 enableFilter）
- `db.changelog-master.yaml`：註冊 v1.73.0
- `TransactionBffController.java`：新增 schedule／run-now／browse passthrough
- `frontend/src/api/index.js`：`transaction` 命名空間新增排程 4 支
- `frontend/src/views/TransactionView.vue`：新增「排程自動匯出」設定卡
- `SchedulePublicBffController.java`：`JOBS` 補「交易紀錄匯出 每日匯出排程檢查」項目

### 端點路徑共存說明

`AssetTransactionController` 既有 `@GetMapping("/export")`（在 `@RequestMapping("/api/asset-transactions")` 下）＝ `/api/asset-transactions/export`；新 `AssetTransactionExportController`（t238）掛 `/api/asset-transactions/export` 並以 `/schedule`、`/run-now` 為子路徑 ＝ `/api/asset-transactions/export/schedule`。兩者路徑不同、無 ambiguous mapping（比照 `RealizedGainController` 與 `RealizedGainExportController` 的既有共存）。

---

## Task 250：新增表單的「市場」預設跟隨當前市場 tab（交易紀錄頁 ＋ 已實現損益頁）

對應 Requirements: 49（交易紀錄）／6（已實現損益）。**純前端變更**：不動任何 controller／service／DTO／DB／BFF route，無 Liquibase changeset。

### 現況與問題

`TransactionView.vue` 與 `RealizedGainView.vue` 是同構的兩頁——都有 `marketFilter` 市場 tab（`el-tabs`，`name` 依序為 `''`／`台股`／`美股`／`英股`）做客戶端篩選，也都有一支 `resetForm()` 把表單初值寫死為 `market: '台股'`。結果是使用者切到「美股」tab 後按新增，市場仍預設台股，每筆美股交易都得多改市場與幣別兩欄。

### 設計

1. **預設值只有一處真相**：兩頁各新增一支 `defaultMarket()`＝`marketFilter.value || '台股'`，由 `resetForm()` 取用。**這樣就夠**——兩頁的 `openCreateDialog()` 第一行本來就是 `resetForm()`（`TransactionView.vue:527`、`RealizedGainView.vue:654`），故預設值必定在**開啟當下**重新求值，切了 tab 再按新增一定拿到新值；dialog 上的 `@closed="resetForm"`（`TransactionView.vue:226`、`RealizedGainView.vue:256`）只是關閉時清場，不是預設值的來源。**不在 `openCreateDialog()` 另寫一份覆寫**：預設值若散在兩處，兩處會各自演化成不同答案。
2. **幣別必須在 `resetForm()` 內一併算好，不能倚賴 watch**：兩頁既有 `watch(() => form.market, m => form.currency = (m === '美股' || m === '英股') ? 'USD' : 'TWD')`，但 watch 只在 `market` 的值**真的改變**時觸發。tab 停在「美股」時 `resetForm()` 把同一個 `美股` 寫回去＝值沒變＝watch 不觸發，若 `currency` 仍寫死 `'TWD'`，表單會停在「美股＋TWD」且**不會自我修正**——這不是短暫的中間狀態，是永久錯值。重現路徑：tab 點「美股」→ 按新增（`market` 台股→美股，watch 觸發，`currency=USD`）→ **不改任何欄位**直接關閉（`@closed` → `resetForm`，美股→美股，watch 不觸發，`currency` 被寫回 `TWD`）→ 再按新增 → 美股＋TWD。故 `resetForm()` 內直接以同一條判斷式算出 `currency`；watch 保留不動（使用者在表單內手動改市場時仍需要它），兩處共用同一組字面值，不另立第二套市場→幣別對照。
3. **只影響新增**：`openEditDialog()` 一律帶入該列自己的 `market`（現行行為），不受 tab 影響。
4. **tab 切換不回頭改動已開啟的表單**：預設值只在 `resetForm()` 求值一次。使用者在表單內改過市場後，即使背景 tab 變動也不得覆寫其輸入。
5. **已知限制（不處理）**：市場 tab 是寫死的四個 pane，而 `TransactionView` 的 `marketOptions` 只含 `active=true` 的市場（`InstitutionService` → `findByActiveTrueOrderBy...`）。某市場在設定頁被停用時，tab 仍在，此時表單的 `el-select` 會退回顯示原始 code（例「英股」而非「英國股市」），送出的值仍是合法的 `MarketType.code`。此風險在改動前就存在（原本寫死的 `'台股'` 被停用時同理），本任務只是多一條觸發路徑，根因是 tab 寫死，屬既有債。
6. **兩頁的市場下拉來源不同，本任務刻意不統一**：`TransactionView` 的 `el-select` 由 `marketOptions`（BFF 聚合的 `MarketType` 主檔，label 為 `displayName`，例「美國股市」）驅動；`RealizedGainView` 則是寫死的三個 `el-option`（`台股`／`美股`／`英股`）。後者是 CLAUDE.md「禁止 Enum 寫死」的既有債，修它要動該頁 BFF payload 與前端載入流程，爆炸半徑與本次「一行預設值」不同量級，另案處理。兩頁的 tab `name` 與 `MarketType.code` 同為 `台股`／`美股`／`英股` 三個字面值，故 `marketFilter` 的值可直接作為 `market` 使用，無須對照表。

### 異動檔案

- `frontend/src/views/TransactionView.vue`：新增 `defaultMarket()`，`resetForm()` 改用它並同步算 `currency`
- `frontend/src/views/RealizedGainView.vue`：同上（`gainForm`）

---

## Task 251：交易紀錄的匯率改為依交易日期自動帶出（唯讀）

對應 Requirements: 49。**前端 ＋ BFF 變更，business 與 DB 零變更**（沿用既有端點與既有欄位），無 Liquibase changeset。

### 現況

`asset_transaction.exchange_rate` 是**全庫唯一**靠使用者手打的匯率：`TransactionView.vue` 以 `el-input`（`txForm.exchangeRateStr`）接受輸入，`AssetTransactionService.create/update` 原封寫入 `req.exchangeRate()`，後端零查詢。同語意的其他三處早已自動化——`asset_snapshot.usd_exchange_rate` 與 `stock_holding.transaction_exchange_rate` 由前端依日期查（`bffApi.snapshotForm.exchangeRate(date)`），`realized_gain.exchange_rate` 由 business 端 `AssetService.lookupExchangeRate(tradeDate)` 自動查，該頁表單根本沒有匯率輸入欄。

### 資料流（新增的只有最上面兩層）

```
TransactionView 交易日期變更 / 幣別切到 USD
  → bffApi.transaction.exchangeRate(date)            ← 前端新 wrapper
  → GET /api/bff/transaction/exchange-rate?date=     ← BFF 新增 passthrough（本頁自己的 BFF）
      → GET /api/market-data/exchange-rate/on-date?currency=USD&date=   ← 既有 business 端點，不動
          → HistoricalDataService.getExchangeRateOnDate
              → ExchangeRateHistoryRepository.findClosestRate（該日或之前最近一筆）
  ← { rateDate, midRate, buyRate, sellRate, fundValuationRate }（查無 → BFF 回 200 {}）
  → 前端取 midRate 填入唯讀欄，並顯示實際 rateDate
```

### 關鍵設計決策

1. **不新增 business 端點、不新增第二份查詢實作**。`GET /api/market-data/exchange-rate/on-date` 已存在且語意完全相同（`findClosestRate` 為全庫唯一的 closest-on-or-before 匯率查詢，已有 6 個呼叫端）。依「一頁一 BFF」，交易頁不得直接呼叫別頁的 `GET /api/bff/snapshot-form/exchange-rate`，也不走 `/api/market-data/**` 的 gateway passthrough，而是在 `TransactionBffController` 新增自己的 passthrough（寫法比照同檔既有的 `/lookup-name`）。
2. **取中間價 `midRate`**。同義欄位的三處（快照、持股交易日匯率、已實現損益）都用中間價；只有基金估值刻意用即期買入（`getFundValuationRate()`，註解明寫「不可用中間價（會高估）」）。本欄語意屬前者。
3. **BFF 不做「今天先 refresh」**。`SnapshotFormBffController` 在 `date == LocalDate.now()` 時會先 `POST /api/market-data/exchange-rate/refresh` 再查，本頁**刻意不照做**，兩個理由：(a) 該 refresh 一次做三件事——FinMind 回補近 10 天、台銀/Yahoo 抓今日、**`purgeOldExchangeRates(currency, 10)` 刪除十年前的資料**，對一個「填表時順手查匯率」的動作而言副作用過大；(b) 盤中匯率排程本來就每 5 分鐘更新（`ExchangeRatePoller` 的 `@Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")`，寫死於程式碼、非 DB 可設定），今日值通常已在。代價是清晨或排程尚未跑到時會回退到前一個有資料日，而該日期會顯示在 UI 上（見決策 5），不是靜默的。
   > 註：**不要拿「容器時區」當作不做 refresh 的理由**。Task 252 起全 stack 以啟動參數統一為 `Asia/Taipei`（實測 host／business／bff 三者同秒同時區），`LocalDate.now()` 與 `date == today` 的判斷是可靠的；此處不照做純粹是因為副作用與收益不成比例。
4. **不在 business 端補值**。`AssetTransactionService` 維持原封寫入，不比照 `AssetService.createRealizedGain` 加 `lookupExchangeRate`。理由：已實現損益那套「寫入時查一次、讀取時若為 null 再查一次」已被實測證明會產生**存檔值與事後重算值不一致**（4 組實例），而交易紀錄的定位是「流水帳只記事實」；匯率一律由前端在使用者眼前查得、看得到、連同表單一起送出，寫入什麼就是使用者當下看到的那個數字。
5. **回退取值必須揭露 `rateDate`**。近 365 天只有 72.1% 的日曆日有 USD 列（週末全缺、國定假日的平日也缺，最長退 9 天），所以「查到的匯率不是交易當天的」是常態而非例外。UI 顯示實際採用日期，使用者才能判斷是否要沿用。
6. **編輯既有紀錄不重查**（本任務最容易做錯的地方）。只有「使用者改動交易日期」或「該筆原本無匯率」才觸發查詢。無條件重查會用事後被 FinMind 覆寫過的值改寫歷史交易，也會抹掉使用者刻意填入的券商實際扣款匯率（`asset_transaction` id=63 即為實例：存 31.5000，該日中間價 31.2350）。
   **實作上必須用「原值備份 ＋ 日期已異動旗標」，不得只憑「匯率欄目前是否為空」判斷。** 只憑空值的版本有一條實際會走到的破口：編輯一筆美股交易時把「市場」誤改為台股（`market` watch 把 `currency` 轉 TWD → 匯率欄被清空），再改回美股（`currency` 轉回 USD → 看到空值 → 觸發查詢），交易日期一次都沒被碰過，該筆的歷史匯率就被當日中間價覆寫了，而欄位是唯讀的、使用者沒有手段改回來。
7. **競態以請求序號解**。使用者連續改日期會送出多個非同步請求，後送出的可能先回。以單調遞增的 `seq` 標記每次查詢，回應時比對 `seq !== latestSeq` 即丟棄，避免舊日期的匯率蓋掉新日期的。**每一條會使前次查詢作廢的路徑都必須遞增 `seq`**——包含「幣別切離 USD 而清空匯率」這種不發出新請求、只做清空的路徑；否則 in-flight 的舊回應仍會把值寫回一個已經不該有匯率的表單。
8. **「查無牌告」與「查詢失敗」分開處理，失敗時解除唯讀**。兩者後果不同：查無（business 404）是資料本來就沒有，欄位留空、維持唯讀、可直接存檔；查詢失敗（5xx／逾時／連線中斷）是取不到而非沒有，此時**暫時解除唯讀讓使用者可手動輸入**。這是本需求的必要配套——把手填欄改成唯讀等於拿掉使用者唯一的輸入手段，若失敗時仍鎖著，使用者就完全無法記下一筆金額正確的 USD 交易，而 `exchangeRate=null` 的 USD 交易其台幣金額會等於美元金額（少算約 32 倍）且直接進年度彙總。故 BFF 的降級**只針對 4xx**，5xx 與連線錯誤要讓前端分辨得出來。
9. **不論查無或失敗都不擋存檔**。`exchangeRate` 送 null 時 `amountTwd` 退回原幣金額——`AssetTransactionService.toResponse` 與 `ExcelExportService.assetTxAmountTwd` 兩處既有公式本來就處理 null，不需修改。

### 異動檔案

- `bff/.../transaction/TransactionBffController.java`：新增 `GET /exchange-rate?date=` passthrough（404／錯誤降級為 `200 {}`）
- `frontend/src/api/index.js`：`transaction` 命名空間新增 `exchangeRate(date)`
- `frontend/src/views/TransactionView.vue`：匯率欄改唯讀、新增自動查詢與 `rateDate` 揭露、競態序號
