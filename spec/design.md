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
│                                       TWSE / Yahoo Finance            │  │
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
- `MarketDataService`: 外部 API 呼叫、Cookie/Crumb 管理、股利率查詢
- `StockPriceService`: 股價快取管理、多市場支援、每 5 分鐘排程更新
- `HistoricalDataService`: 歷史股價與匯率資料管理
- `InstitutionService`: 銀行、券商、存款類型、市場類型的 CRUD、停用管理、關鍵字比對邏輯

**BFF 層**（`bff/` 模組）
- Spring Cloud Gateway：所有 `/api/*` 路由至 Backend
- `DashboardBffController`：`GET /api/bff/dashboard/summary`（並行聚合儀表板資料）

**Repository 層**（Spring Data JPA，共 16 個）
- `AssetSnapshotRepository`
- `StockHoldingRepository`
- `FundHoldingRepository`
- `BankDepositRepository`
- `RealizedGainRepository`
- `StockPriceRepository`
- `StockPriceHistoryRepository`
- `StockRepository`（股票主檔）
- `ExchangeRateHistoryRepository`
- `BankRepository`
- `BrokerRepository`
- `DepositTypeRepository`
- `MarketTypeRepository`
- `TransitFundTypeRepository`
- `WatchStockRepository`
- `StockAlertRepository`

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
- `BackupService`: 透過 ProcessBuilder 呼叫 pg_dump / pg_restore / rclone，支援手動備份、列表（合併 manual/daily/weekly/monthly 四個資料夾）、還原（自動先建自救點）
- `WatchStockService`: 觀察股票 CRUD、拖曳排序、整合 StockPrice 報價與 StockAlert 觸發資訊
- `StockAlertService`: 到價警示 CRUD、條件評估、排序、最近觸發資訊回寫
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
StockPrice            (快取，依 code+market 索引；含買賣/開昨/高低/量)
WatchStock            (觀察股票清單，code+market 唯一)
StockPriceHistory     (歷史股價紀錄)
ExchangeRateHistory   (歷史匯率紀錄)
DepositTypeEntity     (存款類型主檔，code 值存入 BankDeposit.depositType)
MarketType            (市場類型主檔，code 值存入 StockHolding.market)
TransitFundType       (待轉入資金類型主檔)
StockAlert            (到價警示，獨立資料表；WatchStock 列表彙總其最近觸發資訊)
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
| code | String | 識別代碼，同時作為存入 StockHolding.market 的值（如 `台股`） |
| displayName | String | 顯示名稱（如 `台灣股市`） |
| sortOrder | Integer | 顯示排序 |
| active | Boolean | 是否啟用（軟刪除用） |

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
| notes | String | 備註（如「行存」、「綜存」等標記） |

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
| currentValue | BigDecimal | 當前市值 |

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
| year | Integer | 年度（應用層計算，存入 trade_year 欄位） |
| shares | BigDecimal | 交易股數 |
| salePrice | BigDecimal | 賣出均價（原幣） |
| proceeds | BigDecimal | 收帳金額（原幣） |
| investmentCost | BigDecimal | 投資成本（原幣） |
| exchangeRate | BigDecimal | 交易時匯率 |

> 正規化：`profit` 與 `profitRate` 為衍生值（`proceeds - investmentCost` 與其除以 `investmentCost`），不入庫，於 DTO 層即時計算後回傳。v1.9.3 起移除實體欄位。

#### StockPrice（擴充）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| stockCode | String | 股票代號 |
| stockName | String | 股票名稱 |
| market | String | 市場代碼 |
| price | BigDecimal | 最新成交價 |
| priceChange | BigDecimal | 漲跌金額 |
| changePercent | BigDecimal | 漲跌幅(%) |
| buyPrice | BigDecimal | 五檔買進最佳價（新增） |
| sellPrice | BigDecimal | 五檔賣出最佳價（新增） |
| openPrice | BigDecimal | 開盤價（新增） |
| previousClose | BigDecimal | 昨收（新增） |
| highPrice | BigDecimal | 當日最高（新增） |
| lowPrice | BigDecimal | 當日最低（新增） |
| volume | Long | 成交量（台股單位為張，美股為股；新增） |
| tradingDate | LocalDate | 交易日 |
| updatedAt | LocalDateTime | 更新時間 |
| closed | Boolean | 是否為收盤價 |
| source | String | 資料來源 |

#### WatchStock（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| stockCode | String | 股票代號 |
| stockName | String | 股票名稱 |
| market | String | 市場代碼（台股/美股） |
| displayOrder | Integer | 顯示排序（拖曳排序用） |
| createdAt | LocalDateTime | 建立時間 |
| updatedAt | LocalDateTime | 更新時間 |

> Unique constraint：(stockCode, market)。觀察清單中的股票會被併入排程更新；報價直接查 `StockPrice`，警示資訊則彙總自 `StockAlert`。

#### StockAlert（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| stockCode | String | 股票代號 |
| stockName | String | 股票名稱 |
| market | String | 市場代碼（台股/美股） |
| condition | String/JSON | 觸發條件（價格門檻、均線、KD 等） |
| active | Boolean | 是否啟用 |
| displayOrder | Integer | 拖曳排序 |
| lastTriggeredAt | LocalDateTime | 最近一次觸發時間 |
| lastTriggeredPrice | BigDecimal | 觸發時股價 |
| lastTriggeredMa | BigDecimal | 觸發時均線值 |
| lastTriggeredKd | BigDecimal | 觸發時 KD 值 |

#### TransitFundType（新增）
| 欄位 | 型別 | 說明 |
|------|------|------|
| id | Long | PK |
| code | String | 識別代碼（唯一） |
| displayName | String | 顯示名稱 |
| sortOrder | Integer | 顯示排序 |
| active | Boolean | 是否啟用（軟刪除用） |

> **設計決策（零遷移策略）：** `Bank`/`Broker`/`DepositType`/`MarketType` 全部改為資料庫 Entity，不使用任何 Enum。原 `@Enumerated(EnumType.STRING)` 欄位已以 VARCHAR 儲存 Enum 名稱，改為 `String` 欄位時無需資料庫 Migration，現有資料值（如 `"台股"`、`"活存"`）完全相容。`DepositTypeEntity.code` 與 `MarketType.code` 即為寫入欄位的值，與歷史資料對應。

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
GET    /api/market-data/etf-holdings?code=0050&market=台股     # ETF 成分持股（台股 FinMind TaiwanETFHoldings；美股尚未支援）
GET    /api/market-data/dividends?code=0050&market=台股&years=10 # 最近 N 年股利（台股 FinMind；美股 NASDAQ）
```

##### ETF 判斷規則
- 台股：`stockCode` 以 `00` 開頭
- 美股：白名單（VOO、VT、AVGO、VGT、QQQ、SPY … 可於 constant 維護）

#### BFF Aggregation
```
GET    /api/bff/dashboard/summary                  # 並行聚合儀表板所需資料（snapshots + history + prices + market-status）
```

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

#### Watch Stocks（新增）
```
GET    /api/watch-stocks                       # 列出所有觀察股票（含對應的最新報價、警示彙總）
POST   /api/watch-stocks                       # 新增觀察股票（市場 + 代號 + 名稱）
DELETE /api/watch-stocks/{id}                  # 刪除觀察股票
PUT    /api/watch-stocks/reorder               # body: ordered ids 陣列
```

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
```

#### Exchange Rate History（新增）
```
POST   /api/market-data/exchange-rate/backfill-history?currency=USD&since=2021-01-01
                                               # 強制補齊指定日期起的歷史匯率（忽略現有 maxDate）
```

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

### 即時資產估算（Live Assets）

```
1. 取得最新 AssetSnapshot（含 StockHolding 清單）
2. 取得最新 USD/TWD 匯率（ExchangeRateHistory）
3. 對每筆 StockHolding，查詢 StockPrice 快取：
   - 台股：liveValue = shares × price（TWD）
   - 美股：liveValue = shares × price × exchangeRate（USD→TWD）
4. liveStockValue = Σ(liveValue)
5. liveTotalAssets = snapshot.totalDeposit + snapshot.totalFundValue + liveStockValue
6. 回傳：liveStockValue, liveTotalAssets, perStock[{code, market, price, liveValue, closed}],
         twMarketOpen, usMarketOpen, priceUpdatedAt
```

> 存款與基金以快照當時值為準；股票部位反映最新市價。
> `closed=true` 表示該股已使用收盤價，不再盤中更新。

### 股利率查詢（Yahoo Finance）

```
1. 取得 Yahoo Finance session cookie
2. 取得 crumb token（API 認證）
3. 請求 /v10/finance/quoteSummary/{symbol}?modules=summaryDetail
4. 解析 trailingAnnualDividendRate / dividendYield
5. 快取結果至 StockPrice entity
6. 台股先嘗試 {code}.TW，失敗則嘗試 {code}.TWO
```

## Infrastructure

### Docker Compose Services

```yaml
services:
  postgres:
    image: postgres:16-alpine
    ports: ["5432:5432"]
    healthcheck: pg_isready

  backend:
    build: ./backend
    # 不暴露 host port（僅 Docker internal network）
    depends_on: postgres (healthy)
    environment: SPRING_PROFILES_ACTIVE=postgres

  bff:
    build: ./bff
    ports: ["8080:8080"]
    depends_on: [backend]

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

**Backend**: `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine`
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
