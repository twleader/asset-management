# 資產管理系統

個人資產管理系統，支援銀行存款、台股/美股持倉、信託基金、已實現損益的多維度管理與視覺化分析。

## 功能特色

- **資產快照**：以日期為單位記錄各類資產，支援多期比較與趨勢圖表
- **Excel 批次匯入**：支援新舊格式自動偵測，關鍵字比對銀行/券商
- **即時市場資料**：整合 TWSE（台股）、Yahoo Finance（美股）取得即時股價與配息率
- **已實現損益**：多幣別損益記錄，含年度彙總與報酬率分析
- **匯率歷史**：USD/TWD 歷史走勢圖，整合台灣銀行牌告匯率
- **交易日曆**：顯示台股/美股當日開盤狀態
- **設定管理**：銀行、券商、存款類型、市場類型皆可動態管理

## 技術棧

| 層次 | 技術 |
|------|------|
| Backend | Java 21 + Spring Boot 3.4.4 + Spring Data JPA |
| 資料庫 | H2（開發）/ PostgreSQL 16（生產） |
| Excel | Apache POI 5.3.0 |
| Frontend | Vue 3 + Vite 5 + Element Plus + ECharts |
| 狀態管理 | Pinia |
| HTTP 客戶端 | Axios |
| 部署 | Docker Compose（backend + frontend + postgres） |

## 本地開發環境

### 前置需求

- Java 21+
- Maven 3.9+
- Node.js 22+（建議透過 nvm 管理）

### 快速啟動（推薦）

```bash
# 一鍵啟動 Backend + Frontend
./start.sh
```

服務啟動後：
- Frontend：http://localhost:5173
- Backend API：http://localhost:8080/api

### 手動啟動

```bash
# Backend（port 8080）
cd backend
mvn spring-boot:run

# Frontend（port 5173）
cd frontend
npm install
npm run dev
```

### 環境變數

複製 `.env.example` 為 `.env` 並依需求修改（僅 Docker/生產環境需要）：

```bash
cp .env.example .env
```

## Docker Compose 部署

```bash
# 建置並啟動所有服務
docker-compose up -d --build

# 查看狀態
docker-compose ps

# 停止
docker-compose down
```

部署後服務位址：
- Frontend：http://localhost（port 80）
- Backend API：http://localhost:8081/api

## Excel 匯入格式規格

### 快照工作表

工作表名稱格式：`YYYYMMDD`（如 `20240131`）

| 欄 | 說明 |
|----|------|
| A | 銀行名稱（以關鍵字比對，支援模糊匹配） |
| B | 存款類型（活存/定存/美元活存/美元定存/證券戶等） |
| C | 台幣換算金額 |
| D | 基金名稱 |
| E | 基金投入成本 |
| F | 基金現值 |
| G | 股票代號 |
| H（新格式）| 股票名稱（字串） |
| H（舊格式）| 股數（數字，自動偵測） |
| I | 股數（新格式） |
| J | 持股成本 |
| K | 現值 |
| L | 預估配息（新格式） |

### 已實現損益工作表

工作表名稱：`已實現損益`

| 欄 | 說明 |
|----|------|
| A | 券商 |
| B | 交易日期（YYYY-MM-DD） |
| C | 股票名稱 |
| D | 股票代號 |
| E | 市場（台股/美股） |
| F | 幣別（TWD/USD） |
| G | 股數 |
| H | 收帳金額（原幣） |
| I | 投資成本（原幣） |
| J | 損益（原幣） |
| K | 匯率 |

## API 文件

Backend 啟動後可透過以下端點查詢：

### 快照
```
GET    /api/snapshots                        # 列出所有快照
POST   /api/snapshots                        # 建立快照
GET    /api/snapshots/{id}                   # 快照明細
PUT    /api/snapshots/{id}                   # 更新快照
DELETE /api/snapshots/{id}                   # 刪除快照
GET    /api/snapshots/history                # 歷史趨勢
POST   /api/snapshots/import                 # Excel 匯入
PATCH  /api/snapshots/{id}/dividend-rates    # 更新配息率
POST   /api/snapshots/enrich-all-dividend-rates  # 批次補齊配息率
POST   /api/snapshots/recalc-dividends       # 重算所有快照配息
```

### 已實現損益
```
GET    /api/realized-gains                   # 列出（依年度彙總）
POST   /api/realized-gains                   # 新增
PUT    /api/realized-gains/{id}              # 更新
DELETE /api/realized-gains/{id}              # 刪除
POST   /api/realized-gains/import            # Excel 獨立匯入
```

### 市場資料
```
GET    /api/market-data/dividend-rate?code=0050&market=台股   # 配息率
GET    /api/market-data/price?code=2330&market=台股           # 即時股價
GET    /api/market-data/prices                                # 所有快取股價
POST   /api/market-data/prices/refresh                        # 刷新股價
GET    /api/market-data/market-status                         # 開盤狀態
GET    /api/market-data/exchange-rate?currency=USD            # 匯率歷史
GET    /api/market-data/exchange-rate/latest?currency=USD     # 最新匯率
POST   /api/market-data/exchange-rate/refresh                 # 刷新匯率
POST   /api/market-data/history/backfill-stock?code=0050&market=台股  # 補齊單支歷史股價
```

### 設定管理
```
GET/POST       /api/settings/banks
PUT/PATCH      /api/settings/banks/{id}/active
GET/POST       /api/settings/brokers
PUT/PATCH      /api/settings/brokers/{id}/active
GET/POST       /api/settings/deposit-types
PUT/PATCH      /api/settings/deposit-types/{id}/active
GET/POST       /api/settings/market-types
PUT/PATCH      /api/settings/market-types/{id}/active
```

## 專案結構

```
asset-management/
├── backend/                    # Spring Boot 後端
│   └── src/main/java/com/steven/assets/
│       ├── controller/         # REST API 端點
│       ├── service/            # 業務邏輯（AssetService, ExcelImportService, MarketDataService...）
│       ├── repository/         # Spring Data JPA（12 個 Repository）
│       ├── model/              # JPA Entity
│       └── dto/                # Data Transfer Objects
├── frontend/                   # Vue 3 前端
│   └── src/
│       ├── views/              # 頁面元件（12 個 View）
│       ├── stores/             # Pinia 狀態管理
│       ├── api/                # Axios API 層
│       └── components/         # 共用元件（TaiwanMap, UsaMap）
├── spec/                       # 規格文件
│   ├── requirements.md         # User Stories + Acceptance Criteria
│   ├── design.md               # 架構設計與 API 規格
│   └── tasks.md                # 實作任務清單
├── docker-compose.yml          # Docker 部署設定
├── start.sh                    # 本地一鍵啟動腳本
└── .env.example                # 環境變數範本
```
