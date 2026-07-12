# Structure Steering — 資產管理系統

> **用途：** 本文件由 AWS KIRO SDD 在每次對話中載入，提供「程式碼放在哪、命名規則、模組界線」的長期 context。本文件描述穩定的目錄結構與慣例；新增檔案時請依此放置。

---

## 1. Repository Top-Level

```
asset-management/
├── backend/                       # business-services（領域邏輯 + JPA）
├── bff/                           # BFF（Spring Cloud Gateway + 聚合 controller）
├── external-materials-service/    # 抓價 / NAV / 配息子系統
├── frontend/                      # Vue 3 SPA
├── db/init/                       # PostgreSQL 容器初始化 SQL
├── data/                          # H2 本機開發 DB（gitignored）
├── scripts/                       # 維運腳本 + git hooks
│   ├── db-export.sh
│   ├── db-import.sh
│   └── git-hooks/
│       └── pre-commit             # 強制 SDD 同步
├── spec/                          # ⭐ SDD 規格文件（單一真實來源）
│   ├── requirements.md            # User Stories + AC
│   ├── design.md                  # 架構 / ERD / API
│   ├── tasks.md                   # 實作任務清單
│   └── steering/                  # ⭐ 長期 context（本檔所在處）
│       ├── product.md
│       ├── tech.md
│       └── structure.md
├── docker-compose.yml
├── start.sh
├── .env / .env.example
└── CLAUDE.md                      # 開發規範摘要（指向本 steering）
```

---

## 2. Backend（business-services）`backend/`

### 2.1 目錄結構

```
backend/
├── pom.xml
├── Dockerfile
├── data/                                      # 開發用 H2
└── src/main/
    ├── java/com/steven/assets/
    │   ├── AssetManagementApplication.java    # @SpringBootApplication entry
    │   ├── config/
    │   │   ├── DataInitializer.java           # Seed Data（依賴清單 / fund_master / ...）
    │   │   ├── RedisSubscriberConfig.java     # Redis Pub/Sub 訂閱
    │   │   └── WebConfig.java
    │   ├── controller/                        # REST endpoints（/api/{resource}）
    │   ├── service/                           # 領域邏輯
    │   ├── repository/                        # Spring Data JPA repo
    │   ├── model/                             # JPA Entity + Embedded ID
    │   └── dto/                               # Java records（request/response）
    └── resources/
        ├── application.yml
        └── db/                                # Liquibase changelogs
```

### 2.2 分層職責（嚴格遵守）

| 層 | 職責 | 不該做 |
|----|------|--------|
| **Controller** | HTTP 接收 / 格式驗證 / 委派 service / 統一回應碼 | 不寫業務邏輯、不直接讀 repository |
| **Service** | 領域邏輯、外部 API（透過 client）、事務邊界 | 不直接組 HTTP response |
| **Repository** | Spring Data JPA 查詢 | 不寫業務邏輯 |
| **Model（Entity）** | 資料表映射、JPA 註解、`@Enumerated(EnumType.STRING)` | 不寫業務邏輯（可放 helper getter） |
| **DTO** | Java `record`（不可變），request/response transport | 不放 JPA 註解、不持有 entity 參照 |

### 2.3 命名慣例

| 類型 | 慣例 | 範例 |
|------|------|------|
| Entity | 領域名詞（單數） | `AssetSnapshot`、`StockHolding`、`FundMaster` |
| Repository | `{Entity}Repository` | `AssetSnapshotRepository` |
| Service | `{Domain}Service` | `AssetService`、`PriceQueryService` |
| Controller | `{Resource}Controller` | `AssetSnapshotController` |
| DTO | `{Entity}Dto.{Request|Response}`（巢狀 record） | `AssetSnapshotDto.FundResponse` |
| Config | `{Purpose}Config` 或 `{Purpose}Initializer` | `DataInitializer` |
| 設定主檔 Entity | `{Type}Entity` 或 `{Type}`（避免與業務 enum 衝突） | `DepositTypeEntity`、`MarketType`、`BrokerEntity` |

### 2.4 主要 Entity → Repository → Service 對應（節錄）

| Entity | Repository | Owning Service |
|--------|-----------|----------------|
| `AssetSnapshot` | `AssetSnapshotRepository` | `AssetService` |
| `BankDeposit` / `StockHolding` / `FundHolding` | 各自 Repository | `AssetService`（透過 cascade） |
| `Stock`（主檔） | `StockRepository` | `AssetService` / `WatchStockService` |
| `StockPriceHistory` | `StockPriceHistoryRepository` | `PriceQueryService` / `HistoricalDataService` |
| `StockAlert` / `StockAlertTrigger` | 對應 Repository | `StockAlertService` |
| `FundMaster` / `FundNav` / `FundDividendHistory` | 對應 Repository | `FundNavService` / `FundDividendService` |
| `Bank` / `BrokerEntity` / `DepositTypeEntity` / `MarketType` / `TransitFundType` | 對應 Repository | `InstitutionService` |
| `BackupRecord` / `BackupSetting` | 對應 Repository | `BackupService` |
| `TwseIndexDailyHistory` / `TaiwanGdpPerCapitaHistory` / `KoreaGdpPerCapitaHistory` / `TwseIndexYearEndHistory` | 對應 Repository | `MacroHistoryService` |

### 2.5 API 命名規則

| 模式 | 範例 |
|------|------|
| 資源 CRUD | `GET/POST/PUT/DELETE /api/{resource}` |
| 軟停用切換 | `PATCH /api/{resource}/{id}/active` |
| 設定管理 | `/api/settings/{resource}`（少數仍用 `/api/{resource}`） |
| 市場資料 | `/api/market-data/{action}` |
| SSE | `/api/market-data/prices/stream`（text/event-stream） |
| 異常 | 統一由 `GlobalExceptionHandler` 處理 |

---

## 3. BFF `bff/`

### 3.1 目錄結構（一頁面一資料夾）

```
bff/src/main/java/com/steven/assets/bff/
├── config/                       # WebClient、Gateway route
├── common/                       # 跨頁共用工具（如 SnapshotEnricher）
├── dashboard/                    # → DashboardView
│   └── dto/
├── snapshotform/                 # → SnapshotFormView
├── snapshotdetail/               # → SnapshotDetailView
├── snapshotlist/                 # → SnapshotListView
├── assethistory/                 # → AssetHistoryView
├── realizedgain/                 # → RealizedGainView
├── exchangerate/                 # → ExchangeRateView
├── tradingcalendar/              # → TradingCalendarView
├── stockalert/                   # → StockAlertView
├── watchstock/                   # → WatchStockView
├── stockanalysis/                # → StockAnalysisDialog（跨 view 共用元件）
├── gdptwse/                      # → GdpTwseView
├── fund/                         # → FundSettingsView
├── marketdata/                   # /api/bff/market-data/* passthrough + SSE
├── settings/                     # 共用 settings utility
├── banksettings/                 # → BankSettingsView（純 passthrough）
├── brokersettings/               # → BrokerSettingsView
├── deposittypesettings/          # → DepositTypeSettingsView
├── markettypesettings/           # → MarketTypeSettingsView
├── transitfundtypesettings/      # → TransitFundTypeSettingsView
├── backuprestore/                # → BackupRestoreView
└── backup/                       # 備份相關共用
```

### 3.2 BFF 設計鐵則

1. **一個前端頁面 → 一個資料夾 + 一支 Controller（或 Gateway route）。** 即使純 passthrough 也要建立。
2. **路徑前綴：** `/api/bff/{page-name}/...`。Gateway route 將 `/api/bff/{page}/**` rewrite 為 `/api/{resource}/**`。
3. **跨頁共用邏輯放 `bff/common/`。** 如 `SnapshotEnricher`（注入歷史收盤價、合併 broker rows）。
4. **同義欄位 → 同一支 business service API。** BFF 不在不同頁重複呼叫不同 endpoint 取同義值。
5. **前端只 render，BFF 預先聚合 / 排序 / 過濾 / 計算 profit / profitRate 等衍生值。**

---

## 4. External Materials Service `external-materials-service/`

### 4.1 目錄結構

```
external-materials-service/src/main/java/com/steven/assets/externalmaterials/
├── config/             # WebClient、Redis 連線
├── client/             # PriceFetchClient、TwseInfoFetchClient、DividendFetchClient、ExchangeRateFetchClient、MacroDataFetchClient、NewsFetchClient、BotFxFetchClient、YahooFxFetchClient、FundNavFetchClient、FundDividendFetchClient
├── service/
│   ├── PricePoller            # 2 分鐘 cron：抓 live 寫 Redis
│   ├── ClosePersister         # 收盤 cron：寫 stock_price_history
│   ├── MarketClock            # isTwMarketOpen / isUsMarketOpen
│   ├── PriceCacheWriter       # 寫 Redis（封裝 key schema）
│   ├── IntradayHighLowTracker # 盤中 high/low 聚合
│   ├── FundNavPoller          # 每日 NAV cron
│   ├── FundNavSourceQuery     # fund_master 讀取 + fund_nav upsert（由 FundNavPoller / FundNavBackfillService 呼叫）
│   ├── FundDividendPoller     # 每日配息 cron
│   ├── FundNavBackfillService # 10 年回補
│   └── FundDividendBackfillService
└── controller/
    └── InternalPriceController # POST /internal/refresh、/internal/fund-nav/refresh、...
```

### 4.2 設計原則

- **僅內網暴露 `/internal/*`，不對前端開放。** business-services 透過 docker network 呼叫。
- **抓價邏輯不寄宿在 business-services。** 外部 API 限流／失敗不影響主系統。
- **寫入端：** Redis（live）+ PostgreSQL（歷史 / NAV / 配息）。
- **不持有業務邏輯。** 不知道 snapshot、不知道使用者持倉；只負責 fetch & store。

### 4.3 Redis Key Schema

| Key | 內容 | TTL | 寫入者 |
|-----|------|-----|--------|
| `price:{market}:{code}` | live JSON | 24h | `PriceCacheWriter` |
| `price:index:{market}` | Set，紀錄該市場所有有 cache 的 code | 24h | 同上 |
| `price:dayhl:{market}:{code}:{tradingDate}` | 該日最高/最低聚合 | 36h | `IntradayHighLowTracker` |
| `market:status` | 市場開收盤 JSON | 90s | `MarketClock` |
| Channel `price-update` | Pub/Sub 推播 | — | `PriceCacheWriter` |

---

## 5. Frontend `frontend/`

### 5.1 目錄結構

```
frontend/
├── index.html
├── vite.config.js
├── nginx.conf
├── package.json
└── src/
    ├── App.vue                   # Sidebar 導覽 + router-view
    ├── main.js                   # bootstrap + Element Plus + Pinia
    ├── router/index.js           # 全部 route 定義
    ├── stores/assetStore.js      # Pinia 全域狀態
    ├── api/index.js              # Axios instance + 所有 API 方法
    ├── components/               # 跨 view 共用元件
    │   ├── StockAnalysisDialog.vue
    │   ├── TaiwanMap.vue
    │   └── UsFlag.vue
    └── views/                    # 頁面元件（一頁一檔）
        ├── DashboardView.vue
        ├── SnapshotListView.vue
        ├── SnapshotFormView.vue
        ├── SnapshotDetailView.vue
        ├── AssetHistoryView.vue
        ├── RealizedGainView.vue
        ├── ExchangeRateView.vue
        ├── TradingCalendarView.vue
        ├── StockMonitorView.vue   # 含「觀察清單」「警示條件」兩個 tab
        ├── WatchStockView.vue
        ├── StockAlertView.vue
        ├── GdpTwseView.vue
        ├── BankSettingsView.vue
        ├── BrokerSettingsView.vue
        ├── DepositTypeSettingsView.vue
        ├── MarketTypeSettingsView.vue
        ├── TransitFundTypeSettingsView.vue
        ├── FundSettingsView.vue
        └── BackupRestoreView.vue
```

### 5.2 前端慣例

| 項目 | 慣例 |
|------|------|
| View 命名 | `{Domain}View.vue`（PascalCase） |
| 元件命名 | PascalCase（透過 unplugin-vue-components 自動匯入 Element Plus） |
| Composition API | 全面使用 `<script setup>` |
| Auto-import | `ref` / `computed` / `watch` / `onMounted` 等不需手動 `import`（unplugin-auto-import） |
| API 呼叫 | 一律經 `api/index.js` 的 axios instance；不允許 view 內 `fetch()` |
| API 路徑 | 一律 `/api/bff/{page-name}/...`；**不允許**直接打 `/api/{resource}` |
| 狀態 | 跨頁共享 → Pinia `assetStore`；頁面內局部 → `ref` / `reactive` |
| 圖表 | ECharts via `vue-echarts`；不引入其他圖表庫 |
| 日期格式化 | `dayjs`；數字格式化 `numeral` |
| 拖曳排序 | `sortablejs` |

### 5.3 Router 規則

| Path | View | 備註 |
|------|------|------|
| `/` | redirect → `/dashboard` | |
| `/dashboard` | DashboardView | |
| `/snapshots` | SnapshotListView | |
| `/snapshots/new` | SnapshotFormView | 必須定義在 `:id` 之前 |
| `/snapshots/:id` | SnapshotDetailView | |
| `/snapshots/:id/edit` | SnapshotFormView | |
| `/history` | AssetHistoryView | |
| `/realized-gains` | RealizedGainView | |
| `/exchange-rate` | ExchangeRateView | |
| `/trading-calendar` | TradingCalendarView | |
| `/stocks` | StockMonitorView | 含 `?tab=watch` / `?tab=alerts`；舊路徑 `/watch-stocks`、`/stock-alerts` 自動 redirect |
| `/gdp-twse` | GdpTwseView | |
| `/settings/banks` | BankSettingsView | |
| `/settings/brokers` | BrokerSettingsView | |
| `/settings/deposit-types` | DepositTypeSettingsView | |
| `/settings/market-types` | MarketTypeSettingsView | |
| `/settings/transit-fund-types` | TransitFundTypeSettingsView | |
| `/settings/funds` | FundSettingsView | |
| `/settings/backup-restore` | BackupRestoreView | |

---

## 6. 規範文件 `spec/`

```
spec/
├── requirements.md       # 35 個 Requirements（User Story + AC）
├── design.md             # 架構圖、ERD、Service 職責、Sequence
├── tasks.md              # 22+ 個 Tasks（含完成狀態 checkbox）
└── steering/             # 長期 context（每次對話皆載入）
    ├── product.md        # 產品定位 / 使用者 / 範圍
    ├── tech.md           # 技術棧 / 版本 / 慣例
    └── structure.md      # 本檔
```

### 6.1 何時動 spec？

| 變更類型 | 是否必須更新 spec | 應更新哪些檔 |
|----------|-------------------|--------------|
| 新增 Entity / 改資料模型 | ✅ | requirements + design + tasks |
| 新增 API endpoint | ✅ | design + tasks（若新功能也要 requirements） |
| 新增前端頁面 | ✅ | requirements + design（含 route 表）+ tasks |
| 修正商業邏輯 bug | ✅ | requirements（補 AC 或更新既有 AC）+ tasks |
| 純 CSS / 樣式 / typo / import 整理 | ❌ | commit 訊息加 `[skip-spec]` |
| 長期不變的技術選型 / 目錄結構 | ✅ | steering 對應檔（本文件等） |

---

## 7. 命名 / 規範速查

| 範疇 | 慣例 |
|------|------|
| Package 根 | `com.steven.assets`（business-services 與 BFF 共用） |
| Package 根（external-materials） | `com.steven.assets.externalmaterials` |
| 資料庫表名 | `snake_case` 單數（`asset_snapshot`、`stock_holding`、`fund_master`） |
| 欄位名 | DB `snake_case`、Java `camelCase`（透過 `@Column` 對應或預設 mapping） |
| Liquibase changelog 路徑 | `backend/src/main/resources/db/changelog/...`（含主檔 `db.changelog-master.yaml`） |
| API URL 大小寫 | `kebab-case`（`/api/market-data/...`、`/api/bff/snapshot-form/...`） |
| Java enum 值 | `UPPER_SNAKE_CASE`；DB 存 enum 一律 `EnumType.STRING` |
| 金額單位 | `BigDecimal`（不用 `double` / `float`） |
| 時間欄位 | `LocalDate`（日期）/ `Instant`（時間戳）；wall-time 帶市場時區由 service 層處理 |

---

## 8. 模組界線與相依方向（不可違反）

```
frontend ──► bff ──► business-services ──► postgres
                          │
                          ├──► redis ◄── external-materials-service ──► (external APIs)
                          │              └────────────► postgres (fund_nav / stock_price_history / ...)
                          │
                          └──► external-materials (僅內網 /internal/*)
```

**禁止：**
- ❌ frontend 直接打 business-services 或 external-materials-service
- ❌ business-services 直接打外部行情 / NAV / 配息 API（一律經 external-materials）
- ❌ external-materials-service 反向呼叫 business-services
- ❌ 任何 service 跨層直接讀對方資料庫表（除非由 SDD 明確設計）
