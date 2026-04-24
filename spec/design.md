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

**Repository 層**（Spring Data JPA，共 13 個）
- `AssetSnapshotRepository`
- `StockHoldingRepository`
- `FundHoldingRepository`
- `BankDepositRepository`
- `RealizedGainRepository`
- `StockPriceRepository`
- `StockPriceHistoryRepository`
- `ExchangeRateHistoryRepository`
- `BankRepository`
- `BrokerRepository`
- `DepositTypeRepository`
- `MarketTypeRepository`
- `HolidayRepository`（假日快取）

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

## Data Model

### Entity Relationship Diagram

```
Bank        (1) ──── (N) BankDeposit
BrokerEntity(1) ──── (N) StockHolding

AssetSnapshot (1) ──── (N) BankDeposit
AssetSnapshot (1) ──── (N) StockHolding
AssetSnapshot (1) ──── (N) FundHolding
RealizedGain          (獨立，不關聯快照)
StockPrice            (快取，依 code+market 索引)
StockPriceHistory     (歷史股價紀錄)
ExchangeRateHistory   (歷史匯率紀錄)
DepositTypeEntity     (存款類型主檔，code 值存入 BankDeposit.depositType)
MarketType            (市場類型主檔，code 值存入 StockHolding.market)
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
| profit | BigDecimal | 損益金額（原幣） |
| exchangeRate | BigDecimal | 交易時匯率 |
| profitRate | BigDecimal | 報酬率（profit / investmentCost） |

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
POST   /api/snapshots/import                       # Excel 批次匯入（含同日期自動覆蓋）
PATCH  /api/snapshots/{id}/dividend-rates          # 回寫指定快照配息率
PATCH  /api/snapshots/{id}/stock-order             # 更新持倉顯示排序
POST   /api/snapshots/enrich-all-dividend-rates    # 批次補齊所有快照缺漏配息率
POST   /api/snapshots/recalc-dividends             # 重算所有快照預估配息（依現值×配息率）
```

#### Realized Gains
```
GET    /api/realized-gains               # 列出（依年度彙總，含明細）
POST   /api/realized-gains               # 新增
PUT    /api/realized-gains/{id}          # 更新
DELETE /api/realized-gains/{id}          # 刪除
POST   /api/realized-gains/import        # 從獨立 Excel 匯入已實現損益
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

#### Settings - Market Types（新增）
```
GET    /api/settings/market-types               # 列出所有市場類型（含停用）
POST   /api/settings/market-types              # 新增市場類型
PUT    /api/settings/market-types/{id}         # 更新市場類型
PATCH  /api/settings/market-types/{id}/active  # 啟用/停用市場類型
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

## Security Considerations

- CORS 設定於 `WebConfig.java`，限制允許的來源與方法
- 外部 API 金鑰（如有）透過環境變數注入，不寫死於程式碼
- 資料庫憑證透過 `.env` 檔案管理，不提交至版本控制
- 使用 BigDecimal 處理所有金融數值，避免浮點數精度問題
- 財務計算精度：20 位數，2-4 位小數
