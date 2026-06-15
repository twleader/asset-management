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
- `InstitutionService`: 銀行、券商、存款類型、市場類型的 CRUD、停用管理、關鍵字比對邏輯

**BFF 層**（`bff/` 模組）
- Spring Cloud Gateway：所有 `/api/*` 路由至 Backend
- 設計原則：**一個前端頁面對應一個 BFF controller**；前端只 render，aggregation 與計算（profit / profitRate / 買入均價 / 收盤價對齊等）一律由 BFF 預先處理
- `SnapshotEnricher`（共用工具，`bff/.../common`）：注入 investmentCostOriginal、抓快照基準日歷史收盤價、依 stockCode + market 合併 broker rows 為 mergedStocks（含 stockPrice、unitPriceTwd、profit、profitRate、avgCostOriginal）。各 BFF controller 一律走此工具，確保「同義欄位 = 同一邏輯」
- `DashboardBffController`（DashboardView 專屬）：
  - `GET /api/bff/dashboard/summary`：並行聚合 snapshots / history / prices / marketStatus / latestSnapshotDetail + mergedStocks
  - `GET /api/bff/dashboard/snapshot/{id}`：切換快照時用
  - `GET /api/bff/dashboard/realtime`：2 分鐘輪詢用，回傳 stockPrices + marketStatus
  - `POST /api/bff/dashboard/enrich-dividend-rates`：背景補齊所有快照缺漏的配息率
  - `PATCH /api/bff/dashboard/snapshot/{id}/stock-order`：拖曳排序持股後寫回
  - `GET /api/bff/dashboard/tw-stock-lookthrough/{snapshotId}`：「資產配置分佈」第 2 tab「台股個股」用。沿用 `SnapshotEnricher.buildMergedStocks` 取得快照基準日的台股部位，對每檔 ETF（`stockCode` 以 `00` 開頭）並行（concurrency 4）呼叫既有 `/api/market-data/etf-holdings?market=台股&code=...` 取得成分股權重（MoneyDJ 完整成分股優先、Yahoo `topHoldings` 前 10 fallback），依成分股權重**正規化**分配整筆 ETF 市值（`cv × weight / Σweight`，使 ETF 完全穿透不殘留 ETF 自身 slice）後依**股名**加總（MoneyDJ 只給股名無代號；直接持股不拆，與 ETF 內含部位的同股名合併），排序取前 10 名 + 「其它」聚合。ETF 抓取失敗時該 ETF 整筆退回以代號自身計入並記錄於 `degradedEtfs`。回傳 `TwStockLookthroughDto { snapshotDate, totalTwStockValue, items[], others, degradedEtfs[] }`。lazy fetch — 前端只在使用者切到第 2 tab 才呼叫；前端 hover 趨勢圖節點時改以該節點對應的 `snapshotId` 取 cache，cache miss 則 lazy fetch（race 防護：fetch 回來時若使用者目前的 effective snapshot 已切走則不覆寫畫面）
  - `GET /api/bff/dashboard/us-stock-lookthrough/{snapshotId}`：「資產配置分佈」第 3 tab「美股個股」用。流程鏡像台股版（`buildMergedStocks` → 篩 `market=美股` → 並行 concurrency 4 呼叫既有 `/api/market-data/etf-holdings?market=美股&code=...`），但**穿透演算法與台股不同**：美股成分股來源 Yahoo `topHoldings` 僅揭露前 10 大（權重總和常 30~50%），故依**真實權重**分配 `cv × weight/100`（**不正規化**），未揭露尾段 `cv × (1 − Σweight/100)` 全數計入「其它」（台股則 `cv × weight/Σweight` 正規化完全穿透）。聚合鍵用**代號**（Yahoo 成分股有 `symbol`；台股用股名）。ETF 判斷：BFF 對每檔美股 row 呼叫 etf-holdings，以「回傳 holdings 是否非空」區分 ETF / 個股 —— external `isEtf()`（`US_ETF_WHITELIST`）會對非 ETF 代號短路直接回空、不打 Yahoo，故個股呼叫成本低。注意：external 取 Yahoo crumb / quoteSummary（topHoldings）須用短 UA `Mozilla/5.0`（`YAHOO_UA` 常數），長 Chrome UA 會被 Yahoo WAF 回 429 致 topHoldings 全查無（`v8/chart` 端點不受影響）。回傳 `UsStockLookthroughDto { snapshotDate, totalUsStockValue, items[], others, lookthroughEtfCount }`；`lookthroughEtfCount > 0` 時前端顯示「美股 ETF 僅揭露前 10 大成份股，其餘計入『其它』」固定註記（美股不做 `degradedEtfs` 動態清單）。lazy fetch / hover 趨勢圖連動 / per-snapshot cache 與台股一致
- `SnapshotDetailBffController`（SnapshotDetailView 專屬）：
  - `GET /api/bff/snapshot-detail/{id}`：回傳 enriched detail + mergedStocks（含 brokerRows 子陣列，供編輯頁直接使用）
  - `GET /api/bff/snapshot-detail/brokers`：編輯券商欄位用，回傳 active brokers
  - `PUT /api/bff/snapshot-detail/{id}`：儲存編輯後的快照
- 存款 amount 換算規則（後端 `AssetService.normalizeDepositAmount`）：原幣值（`originalAmount` USD）由前端輸入直接傳入，**台幣 amount 一律由後端用 snapshot 匯率算出**（`amount = originalAmount × usdExchangeRate`），TRANSIT_* 的正負號也由後端依 `TransitFundType.payable` 決定。前端不做這部份計算，避免 snapshot 匯率為 null 時誤把 USD 數字寫進 TWD 欄位。
- `AssetHistoryBffController`（AssetHistoryView 專屬）：`GET /api/bff/asset-history`（history + isLastOfYear flag）、`POST /api/bff/asset-history/recalc-dividends`、`DELETE /api/bff/asset-history/{id}`、`GET /api/bff/asset-history/export`
- `RealizedGainBffController`（RealizedGainView 專屬）：`GET /api/bff/realized-gain`（gains + active brokers 一起回傳）、CRUD、export
- `ExchangeRateBffController`（ExchangeRateView 專屬）：`GET /api/bff/exchange-rate`（先 refresh 再回 5 年歷史）、`POST /api/bff/exchange-rate/backfill`
- `TradingCalendarBffController`（TradingCalendarView 專屬）：`GET /api/bff/trading-calendar?year=Y`（`holidays` 為 `{tw: {date→name}, us: {date→name}}` 物件 + `marketStatus`）、`GET /api/bff/trading-calendar/market-status`
- `SnapshotListBffController`（SnapshotListView 專屬）：`GET /api/bff/snapshot-list`、`DELETE /{id}`、`GET /export`
- 其他 settings/passthrough（每頁一支 Spring Cloud Gateway route 配置，rewrite `/api/bff/{page}/**` → `/api/{resource}/**`）：BankSettings、BrokerSettings、DepositTypeSettings、MarketTypeSettings、TransitFundTypeSettings、StockAlert（`/api/bff/stock-alert/**`）、BackupRestore（`/api/bff/backup-restore/**`）、NotificationSettings（`/api/bff/notification-settings/recipients/**` → `/api/notification-recipients/**`）
- `WatchStockBffController`（WatchStockView 專屬，`/api/bff/watch-stock/**`）：觀察清單已改為「`stock_alert` 衍生 view」，BFF 提供：
  - `GET /api/bff/watch-stock`：呼叫 `business-services` 衍生端點 → 對每筆 (stockCode, market) 補上 live 報價、技術指標、最近觸發資訊
  - `PUT /api/bff/watch-stock/order`：拖曳排序時把該股票所有 alert 的 displayOrder 整組重排
  - 新增觀察由前端直接呼叫 `/api/bff/stock-alert/**` 建立警示條件；移除觀察由前端在「警示條件」頁刪除該股票所有 alert 達成，因此 BFF **不提供** watch-stock 級的 create / delete 端點
- `SnapshotFormBffController`（SnapshotFormView 專屬）：把表單頁的多步協調邏輯（價格批次查 + backfill fallback + 配息率補抓 + 名稱補齊 + 匯率智慧 fallback）集中於此
  - `GET /api/bff/snapshot-form/{id}`：編輯模式 bootstrap，回傳 enriched detail + mergedStocks
  - `POST /api/bff/snapshot-form/prices?date=YYYY-MM-DD`：批次取得每筆股票的歷史收盤價 + 漲跌 + 名稱 + 配息率（DB 缺資料時自動 backfill 重試）
  - `GET /api/bff/snapshot-form/realtime`：2 分鐘輪詢用，先 trigger 後端刷新行情再回傳 stockPrices + marketStatus
  - `GET /api/bff/snapshot-form/exchange-rate?date=YYYY-MM-DD`：取指定日期 USD 匯率（今天會先 refresh，假日往前 fallback）
  - `GET /api/bff/snapshot-form/lookups`：表單下拉一次取齊（banks / brokers / depositTypes / transitFundTypes，皆已過濾 active）
- `StockAnalysisBffRoutes`（StockAnalysisDialog 跨 view 共用元件專屬）：對話框被 Dashboard / SnapshotForm / WatchStock / StockAlert / RealizedGain 五個 view 同時使用（已實現損益明細列雙擊開啟），依「同義欄位、同一 business service API」原則拆為獨立 BFF route，避免在五個父 view 的 BFF 各自重複代理。提供：
  - `GET /api/bff/stock-analysis/history/stock` → `/api/market-data/history/stock`
  - `GET /api/bff/stock-analysis/dividends` → `/api/market-data/dividends`
  - `GET /api/bff/stock-analysis/etf-holdings` → `/api/market-data/etf-holdings`
  - `GET /api/bff/stock-analysis/intraday-ticks` → `/api/market-data/intraday-ticks`（走勢圖「當日」期間用；business-services proxy 至 `external-materials-service /internal/intraday-ticks`，後者讀 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`。盤中由 `PricePoller` 每 2 分鐘累積 / 盤後由 `IntradayTickRefresher` 用台股 FinMind `TaiwanStockKBar` + 美/英股 Yahoo 5m 完整覆寫）

**Repository 層**（Spring Data JPA，共 16 個）
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
│   ├── MarketClock     # isTwMarketOpen / isUsMarketOpen（搬出後集中此處）
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

**美股盤中 `openPrice` 補強（2026/05）：** NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`，`PriceFetchClient.getNasdaqPrice` 在主呼叫之後額外打 `https://api.nasdaq.com/api/quote/{code}/historical?assetclass=...&fromdate=YYYY-MM-DD&todate=YYYY-MM-DD&limit=1`（日期皆為美東今日），由 `data.tradesTable.rows[0].open` 取得今日開盤價。失敗或無今日列時保留 null，由 `WatchStockService` 的 `stock_price_history` fallback 接手（顯示昨日 open；不接受時可在 UI 端忽略）。HTTP 呼叫在 `PricePoller.updatePrices` 的 virtual-thread pool 中與其他 stock 並行執行，不會延長 cron 週期。

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
├── router/index.js      # Route definitions (14 routes)
├── stores/assetStore.js # Pinia global state
├── api/index.js         # Axios instance, API methods
├── components/          # Reusable components (TaiwanMap, UsaMap)
└── views/               # Page-level components (12 views)
```

**Service 層補充**
- `BackupService`: 透過 ProcessBuilder 呼叫 pg_dump / pg_restore / rclone，支援手動備份、列表（合併 manual/daily/weekly/monthly 四個資料夾）、還原（自動先建自救點）；內建 `@Scheduled` 自動排程：
  - `0 30 15 * * MON-FRI` Asia/Taipei：台股交易日 15:30（收盤後 2h）→ 上傳 `daily/asset_daily_tw_*.dump`
  - `0 0 7 * * TUE-SAT` Asia/Taipei：前一日為美股交易日時，台北 07:00（美東 16:00 收盤後 2h，涵蓋夏令／標準時）→ 上傳 `daily/asset_daily_us_*.dump`
  - `0 0 5 * * SUN` Asia/Taipei：每周日 05:00 → 上傳 `weekly/asset_weekly_*.dump`
  - 輪替策略：`daily/` 保留 50 份、`weekly/` 保留 5 份、`manual/` 保留 5 份（自救點不計入）
  - 輪替觸發點：(a) 每次排程／手動備份成功上傳後對該資料夾跑一次；(b) `updateSetting()` 儲存後對三個資料夾各跑一次（讓使用者調降保留代數時立即套用，不需等到下一次排程）。rotate 失敗以 `try/catch` 包住只記 log，不讓設定儲存 API 失敗
  - 交易日判定委派至 `MarketDataService.getTwHolidays(year)` / `getUsHolidays(year)`，並排除週末
- `WatchStockService`: 觀察清單 view 服務（不再對應實體表）。`findAll()` 由 `StockAlertRepository.findDistinctStockCodeMarket()` 取得去重 (stockCode, market) 清單後，整合 Redis live 報價（透過 `PriceQueryService`）、技術指標（`TechnicalIndicatorService`）與該股票最近一次 StockAlert 觸發資訊；`reorder(orderedStockKeys)` 拖曳重排時把每個股票所有 alert 的 `displayOrder` 整組依新順序重新指派；不提供 delete 入口（移除觀察一律由 `StockAlertService.delete` 在「警示條件」頁逐筆刪除）
- `StockAlertService`: 到價警示 CRUD、條件評估、排序、最近觸發資訊回寫；`create` 對 `0000`（台股大盤）跳過 `stockMasterRepo.upsert`。`create` / `update` 在 `stockMasterRepo.upsert` 之前以 `assertNameMatchesCode(code, market, userName)` 守門：以 `historicalDataService.fetchTwStockName / fetchUsStockName` 取得外部 canonical name（權威來源 ext-materials-service `/internal/stock-name`），canonical 非空且與 user-supplied `stockName` 不一致時，**再對照 `stockMasterRepo.findByCodeAndMarket(code, market).name`（本地主檔亦視為合法 canonical）**；兩者皆不相符才 throw `IllegalArgumentException`（由 `GlobalExceptionHandler` 映射為 `400`）。canonical 空則 fall through（外部 API 異常時不阻擋）。`0000` + `台股` 跳過守門；`0000` + `美股` 直接拒絕。設計目的：阻止使用者把錯誤代號（如把 2500 標成「台積電」）寫入 `stock` 主檔導致觀察清單出現名稱對但完全無報價的列；同時避免「外部來源回應與本地主檔不一致」（例 Yahoo `shortName` 「NVIDIA Corporation」vs 主檔過去寫入的 「NVIDIA Corporation Common Stock」）造成 lookupName 自動帶名後 save 被自己擋下
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
| `/settings/banks` | BankSettingsView | 銀行設定管理 |
| `/settings/brokers` | BrokerSettingsView | 券商設定管理 |
| `/settings/deposit-types` | DepositTypeSettingsView | 存款類型設定管理 |
| `/settings/market-types` | MarketTypeSettingsView | 市場類型設定管理 |
| `/settings/transit-fund-types` | TransitFundTypeSettingsView | 待轉入資金類型設定管理 |
| `/settings/backup-restore` | BackupRestoreView | 資料庫備份／還原 |
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
StockPriceHistory     (歷史股價紀錄；live 行情改由 Redis 提供)
ExchangeRateHistory   (歷史匯率紀錄)
DepositTypeEntity     (存款類型主檔，code 值存入 BankDeposit.depositType)
MarketType            (市場類型主檔，code 值存入 StockHolding.market)
TransitFundType       (待轉入資金類型主檔)
StockAlert            (到價警示，獨立資料表；觀察清單由此表 GROUP BY (stockCode, market) 衍生)
StockAlertTrigger     (警示觸發歷史，FK→stock_alert，保留 30 天)
BackupSetting         (備份保留代數設定，單列資料表，id = 1)
```

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
| investmentCost | BigDecimal | 總投資成本（= shares × 成本均價） |
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

#### Live 行情（Redis）

`StockPrice` Entity 與 `stock_price` 表已廢除，盤中即時行情改存 Redis（schema 見上方 External Materials Service Architecture）。`PriceQueryService` 從 Redis 取值並組裝成原 `StockPriceDto` 形狀，對 BFF / 前端介面不變。

#### WatchStock（已廢止）

`watch_stock` 表已廢止（v1.x 重構：「觀察清單由 stock_alert 衍生」）。觀察清單一律由 `StockAlert` 群組去重產生，不再有獨立的觀察 entity。被併入 `external-materials-service` 排程的股票範圍 = 持股 + `stock_alert.stockCode` distinct（含 `0000` 大盤特例不送排程，由 TWSE 日線 cron 寫入 `twse_index_daily_history`）。

#### StockAlert（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| stockCode | String | 股票代號 |
| stockName | String | 股票名稱 |
| market | String | 市場代碼（台股/美股） |
| alertType | String | 條件類型：`PRICE_ABOVE` / `PRICE_BELOW` / `MA_ABOVE_PCT` / `MA_BELOW_PCT` / `KD_ABOVE` / `KD_BELOW` / `KD_D_ABOVE` / `KD_D_BELOW` |
| maPeriod | Integer | 均線天數（僅 `MA_*_PCT` 類型使用，可選 20 / 60 / 240；其他類型為 null） |
| threshold | BigDecimal | 條件門檻：價位類為價格，均線類為百分比偏離，KD 類為 0–100 門檻 |
| active | Boolean | 是否啟用 |
| displayOrder | Integer | 拖曳排序 |
| lastTriggeredAt | LocalDateTime | 最近一次觸發時間 |
| lastTriggeredPrice | BigDecimal | 觸發時股價 |
| lastTriggeredMa | BigDecimal | 觸發時均線值（對應 `maPeriod` 的 MA） |
| lastTriggeredKd | BigDecimal | 觸發時 KD 值 |

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
GET    /api/snapshots/export                       # Excel 批次匯出
PATCH  /api/snapshots/{id}/dividend-rates          # 回寫指定快照配息率
PATCH  /api/snapshots/{id}/stock-order             # 更新持倉顯示排序
POST   /api/snapshots/enrich-all-dividend-rates    # 批次補齊所有快照缺漏配息率
POST   /api/snapshots/recalc-dividends             # 重算所有快照預估配息（依現值×配息率）

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

#### Market Data
```
GET    /api/market-data/dividend-rate?code=0050&market=台股    # 取得股利率
GET    /api/market-data/price?code=2330&market=台股            # 取得即時股價（含漲跌、股名）
GET    /api/market-data/prices                                 # 列出所有快取股價
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
> - 否則（basedate 為過去日期、或該市場非當日）顯示快照保存的當日 `stockPrice`（即 `latestSnapshotDetail.stocks[].stockPrice`），不顯示漲跌%、不參與 polling 切換。
> - **per-market 判斷一律由 BFF 完成**（`SnapshotEnricher.mergePerMarketPrices`），前端禁止重做 basedate / 市場開盤判斷。BFF 把判斷結果編碼在 response：`stockPrices[].priceChange != null` 即代表該檔為 live；`priceChange == null` 即為 frozen 快照價。前端 `getRealtimePrice()` 只能依此 flag 決定 render，避免「同義欄位、不同邏輯」造成 Dashboard 與 SnapshotForm 兩頁顯示不一致。
>
> KPI「資產總計」一致性：Dashboard `liveLatest.totalAssets` 一律優先採用 `liveAssets.liveTotalAssets`（來自 `/api/market-data/live-assets`），與「歷年資產管理」今日列共用同一支 business-service API。即使盤後 Redis cache 最終過期（24 小時 TTL），`PriceQueryService.getLive()` 會 fallback 至 `stock_price_history` 最近一筆收盤價，確保兩頁永遠顯示同一個總資產數字。**禁止前端再加「市場開盤」閘門**；basedate 是否==今日由 BFF 與 `liveAssets.snapshotDate` 比對結果決定即可。
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

#### Payment Accounts / Categories（Requirement 22 新增）
```
GET    /api/settings/payment-categories               # 列出所有分類（含停用）
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
```

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

#### Macro History（Requirement 18：股市分析）
```
GET    /api/taiwan-gdp                         # 全部年度人均 GDP（USD）
GET    /api/taiwan-gdp?since=1996              # 起始年（含）以後
GET    /api/korea-gdp / ?since=1996            # 韓國人均 GDP（USD）+ 實質成長率
GET    /api/twse-daily-index?from=YYYY-MM-DD&to=YYYY-MM-DD
                                               # 台股大盤每日收盤（10 年回補後使用）
POST   /api/twse-daily-index/refresh?years=10  # 逐月呼叫 TWSE MI_5MINS_HIST 抓所有交易日 upsert
# 已移除（Task 97）：/api/twse-year-end-index(GET/refresh)、/api/twse-daily-index/latest（年末走勢圖卡移除）
GET    /api/us-daily-index?code=SPX&from=&to=  # 海外指數每日 OHLC（code ∈ DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225）
POST   /api/us-daily-index/refresh?code=SPX    # Yahoo v8 chart range=10y 抓單一指數 upsert（一次呼叫）
GET    /api/index-intraday?market=TWSE         # 指數「當日」分時（Yahoo 5m，取最新交易日；transient，不寫 DB）

GET    /api/bff/gdp-twse?years=30              # 前端 view 專用，回傳近 N 年彙整資料
GET    /api/bff/gdp-twse/index-daily?market=TWSE&years=10
                                               # 指數日線 + MA20/60/240（market=TWSE 或 DJI/SPX/IXIC/SOX/FTSE/DAX/KOSPI/N225；一次載入，前端 dataZoom 切區間）
POST   /api/bff/gdp-twse/refresh-index-daily?market=TWSE&years=10  # 觸發「當前選取」指數日線回補
GET    /api/bff/gdp-twse/index-intraday?market=TWSE   # 「當日」分時：回 tradingDate + times(HH:mm) + closes + previousClose/lastClose/change/changePercent
```

「當日」分時資料源（Requirement 18，比照股票分析 Task 87/88 版型但不走 tick store）：
- 指數（大盤＋美股四大＋海外四指數）不在 Redis tick 輪詢名單，故 ext-materials `MacroDataFetchClient.fetchIndexIntraday(market)` 即時向 Yahoo v8 chart（`interval=5m&range=5d`）抓取，依 `exchangeTimezoneName` 轉當地時區、group by 當地日期取「最新交易日」回傳；盤中＝今日部分 bar（即時）、盤後＝最後完整交易日 → 自動滿足需求。transient 不寫 DB
- market→Yahoo symbol：TWSE→`^TWII`、DJI→`^DJI`、SPX→`^GSPC`、IXIC→`^IXIC`、SOX→`^SOX`、FTSE→`^FTSE`、DAX→`^GDAXI`、KOSPI→`^KS11`、N225→`^N225`
- 各市場交易時段（補滿 5 分格用，當地時區）：TWSE 09:00–13:30、美股四大 09:30–16:00、FTSE 08:00–16:30、DAX 09:00–17:30、KOSPI 09:00–15:30、N225 09:00–15:00（午休 11:30–12:30 無 bar→留 null）。以 `INDEX_TRADING_HOURS` map 查詢，未知市場 fallback 09:30–16:00
- 前端「當日」模式 x 軸改 HH:mm、收盤單線；月/季/年線改畫水平參考線（取日線最新 MA20/60/240），與其他期間同口徑
- 「當日」卡片標題列另顯示**昨收 / 漲跌 / 漲跌%**，由 BFF 計算（前端只 render，符合「計算放 BFF」）：`previousClose` ＝該指數日線表（`twse_index_daily_history` / `us_index_daily_history`）中 `tradingDate` **之前**最後一筆收盤——與觀察清單 0000 報價 `WatchStockService.toIndexResponse` 讀**同一張日線表**，昨收為同一事實來源、值一致；`lastClose` ＝分時 `closes` 末筆非 null（盤中即時 / 盤後收盤）；`change = lastClose − previousClose`、`changePercent = change / previousClose ×100`（HALF_UP 2 位）。BFF 以 `Mono.zip` 並行抓 intraday 與「近 40 日日線 tail」（台股 `/api/twse-daily-index`、美股 `/api/us-daily-index`，與 `index-daily` 同一支 business API，同義欄位同一來源；40 日涵蓋最長連假確保含前一交易日）；日線 asc，取「`tradingDate` 嚴格小於當日」的最後一筆。日線未回補導致昨收缺值時 `change/changePercent` 回 null，前端整段不顯示

回傳格式（BFF `/api/bff/gdp-twse`，服務「台韓人均 GDP 比較」圖）：
```json
{
  "years": [1996, 1997, ..., 2025, 2026],
  "gdpPerCapitaUsd": [13571, 13888, ...],
  "koreaGdpPerCapitaUsd": [12000, 12500, ...],
  "taiwanGdpGrowthRate": [6.05, 4.2, ...],
  "koreaGdpGrowthRate": [7.1, 5.9, ...]
}
```
（Task 97 起不再回 `twseYearEndClose` / `currentYearLastTradingDate`——「人均 GDP vs 台股大盤年末收盤」卡已移除。`POST /api/bff/gdp-twse/refresh` 亦簡化為只觸發 TWN+KOR GDP 回補）

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
totalCost    = Σ(stock.investmentCost)         # 儲存總投資成本，非每股成本
totalProfit  = totalStock - totalCost

# 預估年配息合計（asset_snapshot.estimated_annual_dividend）
estimatedAnnualDividend
  = Σ(stockHolding.estimatedDividend)
  + Σ(fundHolding.estimatedDividend)
  + Σ(bankDeposit.amount × bankDeposit.annualInterestRate / 100)  # 存款預估年利息（amount 已是台幣等值）
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
isLiveSession = (該市場 isOpen) || (剛收盤 20 分鐘窗口)
                 // 台股：09:00–13:30 + 13:30–13:50
                 // 美股：09:30–16:00 ET + 16:00–16:20 ET

if isLiveSession:
    trading_date = LocalDate.now(market timezone)   // 資料確實來自今天
else:
    trading_date = max(stock_price_history.trading_date for this code+market)
                    fallback today                  // 上一個有真實資料的交易日
```

> 此規則 fix 過去歷史 bug：盤外刷新會把所有 cache 的 `trading_date` 蓋成今天，導致 KD9 把上一交易日的 OHLC 當成今天的 K 棒。

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
                                  ├─ 組 digest body
                                  ├─ 讀 NotificationRecipientRepository.findByActiveTrue
                                  └─ EmailService.send(toList, subject, body)
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
- digest 主旨：`[資產管理] 股票警示觸發 N 筆`，N = **去重後的股票檔數**（非觸發筆數）
- **同一股票（stockCode+market）多條件觸發合併成一筆**（`groupByStock`，保留首次出現順序）：標題 `{stockName} ({stockCode} {market}) — {label1、label2…}`（該股所有觸發條件 label 去重串接），其下依序 `觸發時間`、`股價`、三條均線 `月線 {monthlyMa}`／`季線 {quarterlyMa}`／`年線 {annualMa}`、`KD：K {k} / D {d}`。技術快照（時間/股價/MA/KD）取該股**最近一筆觸發**（max `triggeredAt`）——同股各條件的 MA/KD 本即同源、僅時間略異（某條均線因歷史不足為 null 時該行省略；K/D 皆 null 時整行省略）。三條均線值取自 `recordTrigger` 當下 `TechnicalIndicatorService.computeAll()` 的 `FullIndicators`，與 `stock_alert_trigger` 落地的 `monthly_ma/quarterly_ma/annual_ma` 同源

**手動補發（觀察清單「補發」按鈕）**：
- 前端 `WatchStockView` 「新增觀察」左側「補發」鈕 → `POST /api/bff/watch-stock/resend-digest`（watch-stock BFF passthrough → `/api/watch-stocks/resend-digest`，守「一頁一支 BFF」）
- `WatchStockController.resendDigest()` 委派 `AlertNotificationDispatcher.resendLastTradingDay()`：
  - 對 `stock_alert_trigger` 內出現過的每個 market，依市場時區算「最後交易日」（`lastTradingDate`：平日已過開盤＝當日、盤前 / 週末回溯最近平日；不考慮假日），取該日 `triggered_at ∈ [當地 00:00, 翌日 00:00)` 的所有觸發
  - 把各市場結果合併、用**與自動 digest 相同的** `buildDigestBody()` 組單封信（重用同一格式 → 月線/季線/年線 + KD 同源），主旨 `[資產管理] 股票警示補發 N 筆`，寄給所有 active 收件人
  - 觸發列回查 `StockAlert`（`alertId`）以 `buildLabel` 還原條件文案；alert 已刪除的孤兒觸發以「警示觸發」當 fallback label，不靜默丟棄
  - 回傳 `{sent, count, message}`：`SENT` / `NO_EVENTS`（各市場最後交易日皆無觸發）/ `NO_RECIPIENTS` / `EMAIL_DISABLED` 四態，後三者不寄信，前端據以提示
  - 補發為手動全量重寄，與自動 digest 的 24h cooldown / queue 互不影響（不寫 cooldown、不入 queue）

**HTML 信 + 內嵌走勢圖（Task 93）**：
- digest / 補發信改以 HTML 寄送：`EmailService.sendHtml(recipients, subject, html, inlineImages)` 用 `MimeMessage` + `MimeMessageHelper(multipart)`，`setText(html, true)` 後逐一 `addInline(cid, ByteArrayResource(png), "image/png")`
- `AlertNotificationDispatcher.buildDigest()` 回 `DigestMail{html, inlineImages(cid→png), stockCount}`：每檔股票區塊組好文字後，呼叫 `AlertChartRenderer.renderPriceMaPng(code, market)` 取圖，成功則 `images.put("chart{i}", png)` 並插入 `<img src="cid:chart{i}">`
- `AlertChartRenderer`：純 Java（XChart）server-side 繪圖，**上下雙 pane 合成一張 PNG**（Graphics2D 垂直拼接）：
  - 資料一律走 `HistoricalDataService.getStockHistory(code, market, end-120M, end)`（與畫面 `StockAnalysisDialog` **同 120 個月範圍**、0000 自動讀 `twse_index_daily_history` 含 OHLC、併今日即時價）
  - MA：與前端 `calcMA` 相同——**每點對 window 重新加總**（非滑動扣減，避免長序列累積誤差）+ `BigDecimal HALF_UP` 2 位
  - KD：與前端 `calcKD` / `TechnicalIndicatorService` 同一遞迴（period 9、RSV=(close-ll)/(hh-ll)*100、hh==ll→50、K=prevK*2/3+RSV/3、D=prevD*2/3+K/3、seed 50/50、high/low 缺值 fallback close、續算用未捨入值）。整段歷史算完再切尾 252（≈1年），尾值與畫面逐位一致
  - 上 pane：股價(藍) + 月線MA20(橘) + 季線MA60(紫) + 年線MA240(紅)，隱藏 x 軸（日期只畫在下 pane）；下 pane：K(橘) / D(綠)，Y 0~100，80/20 灰虛線（`setShowInLegend(false)` 不進圖例）
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

## Security Considerations

- CORS 設定於 `WebConfig.java`，限制允許的來源與方法
- 外部 API 金鑰（如有）透過環境變數注入，不寫死於程式碼
- 資料庫憑證透過 `.env` 檔案管理，不提交至版本控制
- 使用 BigDecimal 處理所有金融數值，避免浮點數精度問題
- 財務計算精度：20 位數，2-4 位小數
- 備份／還原 API 僅執行白名單指令，命令參數不接受使用者拼接

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
