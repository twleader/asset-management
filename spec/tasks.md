# Tasks

## Implementation Tasks

以下任務依功能模組分組，每個任務標注所對應的 Requirements，並列出具體實作步驟。任務之間若有依賴關係則以「前置任務」標注。

---

### Task 1: 專案基礎建設

**對應 Requirements:** 所有 Requirements 的基礎

#### Steps:

- [x] 1.1 建立 Spring Boot 專案結構（com.steven.assets）
  - 建立 `AssetManagementApplication.java` 啟動類別
  - 設定 `pom.xml`（Spring Boot 3.4.4, Java 25, JPA, PostgreSQL, H2, Apache POI, Lombok）

- [x] 1.2 設定資料庫連線
  - `application.yml`：H2 記憶體資料庫（開發環境）
  - `application-postgres.yml`：PostgreSQL（生產環境）
  - 設定 JPA `ddl-auto: none`，Schema 由 Liquibase 管理

- [x] 1.3 建立 Vue 3 前端專案
  - 初始化 Vite + Vue 3 專案
  - 安裝套件：Element Plus、ECharts、Axios、Pinia、Vue Router
  - 設定 `vite.config.js`（自動匯入、元件自動註冊）

- [x] 1.4 設定 CORS（`WebConfig.java`）
  - 允許來源：`http://localhost`、`https://localhost`
  - 允許方法：GET, POST, PUT, DELETE, OPTIONS

- [x] 1.5 建立 Docker Compose 部署設定
  - `docker-compose.yml`：postgres + backend + frontend 三個服務
  - Backend `Dockerfile`：Maven 多階段建置 → JRE Alpine
  - Frontend `Dockerfile`：Node 建置 → Nginx Alpine
  - `nginx.conf`：`/api/` 反向代理 + SPA fallback

---

### Task 2: 資料模型與 Repository

**對應 Requirements:** 所有 Requirements 的資料層
**前置任務:** Task 1

#### Steps:

- [x] 2.1 建立 Enum 型別（已全數廢棄，改為資料庫 Entity）
  - ~~`Market.java`~~：已廢棄，改由 `MarketType` Entity 取代（見 Task 13）
  - ~~`DepositType.java`~~：已廢棄，改由 `DepositTypeEntity` Entity 取代（見 Task 13）
  - ~~`BankName.java`~~：已廢棄，改由 `Bank` Entity 取代（見 Task 12）
  - ~~`Broker.java`~~：已廢棄，改由 `BrokerEntity` Entity 取代（見 Task 12）

- [x] 2.2 建立 JPA Entity
  - `AssetSnapshot.java`：快照主表，含 `@OneToMany` 關聯
  - `BankDeposit.java`：銀行存款，`bank` 欄位改為 `@ManyToOne Bank`，`depositType` 改為 `String`
  - `StockHolding.java`：股票持倉，`broker` 改為 `@ManyToOne BrokerEntity`，`market` 改為 `String`
  - `FundHolding.java`：基金持倉，含 `@ManyToOne` 至 AssetSnapshot 與 Bank
  - `RealizedGain.java`：已實現損益（獨立表），`market` 改為 `String`
  - `StockPrice.java`：股價快取，唯一索引 code+market（market 改為 String）
  - `StockPriceHistory.java`：歷史股價（market 改為 String）
  - `ExchangeRateHistory.java`：歷史匯率
  - `Bank.java`：銀行設定 Entity（id、code、displayName、keywords、active）
  - `BrokerEntity.java`：券商設定 Entity（同結構，命名加 Entity 避免與舊 Enum 衝突）
  - `DepositTypeEntity.java`：存款類型設定 Entity（id、code、displayName、sortOrder、active）
  - `MarketType.java`：市場類型設定 Entity（同結構）

- [x] 2.3 建立 Spring Data JPA Repository（共 16 個）
  - 各 Repository 繼承 `JpaRepository`
  - `AssetSnapshotRepository`：`findBySnapshotDate(LocalDate)`
  - `StockPriceRepository`：`findByStockCodeAndMarket(String, String)`
  - `RealizedGainRepository`：`findByYear(Integer)`
  - `BankRepository`：`findByCode(String)`、`findByActiveTrueOrderByDisplayNameAsc()`
  - `BrokerRepository`：同上
  - `DepositTypeRepository`：`findByCode(String)`、`findByActiveTrueOrderBySortOrderAscDisplayNameAsc()`
  - `MarketTypeRepository`：同上

- [x] 2.4 建立 DTO（Java Records）
  - `AssetSnapshotDto.java`：含巢狀 BankDeposit、StockHolding、FundHolding 列表
  - `RealizedGainDto.java`：含年度彙總資訊

---

### Task 3: 資產快照 API

**對應 Requirements:** Requirement 1
**前置任務:** Task 2

#### Steps:

- [x] 3.1 實作 `AssetService` 核心方法
  - `getAllSnapshots()`：回傳所有快照摘要列表
  - `getSnapshotById(Long id)`：回傳快照明細（含三種持倉）
  - `createSnapshot(AssetSnapshotDto)`：建立快照，計算總額
  - `updateSnapshot(Long id, AssetSnapshotDto)`：更新快照
  - `deleteSnapshot(Long id)`：刪除快照及其所有子資料

- [x] 3.2 實作總額計算邏輯（`AssetService`）
  - `totalDeposit = Σ(bankDeposit.twdAmount)`
  - `totalFund = Σ(fundHolding.currentValue)`
  - `totalStock = Σ(台股市值) + Σ(美股市值 × usdToTwd)`
  - `totalCost = Σ(shares × costPerShare)`
  - `totalProfit = totalStock - totalCost`
  - `totalAssets = totalDeposit + totalFund + totalStock`

- [x] 3.3 實作 `AssetSnapshotController`
  - `GET /api/snapshots`
  - `POST /api/snapshots`
  - `GET /api/snapshots/{id}`
  - `PUT /api/snapshots/{id}`
  - `DELETE /api/snapshots/{id}`

- [x] 3.4 實作 `GlobalExceptionHandler`
  - 處理 `EntityNotFoundException`（404）
  - 處理 `ConstraintViolationException`（400）
  - 處理通用 `Exception`（500）

---

### Task 4: Excel 批次匯入（已停用）

**對應 Requirements:** Requirement 5（功能已轉為匯出，見 Task 17）
**前置任務:** Task 3

> ⚠️ Excel 匯入功能已停用：`ExcelImportService` 程式碼保留，但 controller 端點（`POST /api/snapshots/import`、`POST /api/realized-gains/import`）皆已移除。下方步驟標記 `[x]` 表示原始實作已完成；現行系統不再暴露這些端點。

#### Steps:

- [x] 4.1 實作 `ExcelImportService` - 基礎架構
  - 使用 Apache POI 讀取 `.xlsx` 檔案
  - 遍歷工作表，以工作表名稱（YYYYMMDD）解析日期
  - 定義新/舊格式偵測邏輯

- [x] 4.2 實作銀行存款解析
  - ~~依硬編碼關鍵字識別銀行~~ → 改為從資料庫載入 Bank 清單，依 `keywords` 欄位動態比對（見 Task 12）
  - 依欄位名稱識別存款類型
  - 處理台幣/美元金額轉換
  - 處理 Formula Cell（`evaluateFormulaCell`）

- [x] 4.3 實作股票持倉解析
  - 格式 A（新）：股票代號與名稱分開存放於不同欄
  - 格式 B（舊）：代號與名稱合併於同一欄（如 `2330台積電`）
  - 解析持股數、成本均價
  - ~~依硬編碼關鍵字識別券商~~ → 改為從資料庫載入 Broker 清單，依 `keywords` 欄位動態比對（見 Task 12）

- [x] 4.4 實作基金持倉解析
  - 解析基金名稱、投入成本、當前市值

- [x] 4.5 實作匯入衝突處理
  - 同日期已存在時，依 `overwrite` 參數決定行為
  - 回傳匯入結果：成功筆數、跳過筆數、錯誤明細

- [x] 4.6 ~~新增 `POST /api/snapshots/import` endpoint~~（已撤回，端點已從 Controller 移除）
  - 原本接受 `multipart/form-data`（file + overwrite 參數）

---

### Task 5: 已實現損益模組

**對應 Requirements:** Requirement 6
**前置任務:** Task 2

#### Steps:

- [x] 5.1 實作 `RealizedGainController`
  - `GET /api/realized-gains?year={year}`
  - `POST /api/realized-gains`
  - `PUT /api/realized-gains/{id}`
  - `DELETE /api/realized-gains/{id}`
  - ~~`POST /api/realized-gains/import`~~（已撤回，端點已移除）
  - `POST /api/realized-gains/fix-currency-to-twd`（資料修正工具）
  - `GET /api/realized-gains/export`（Excel 匯出，見 Task 17）

- [x] 5.2 在 `ExcelImportService` 新增損益匯入邏輯
  - 讀取損益工作表
  - 解析年度、股票代號、名稱、損益金額
  - 自動偵測市場（台股/美股）

---

### Task 6: 市場資料整合

**對應 Requirements:** Requirement 7
**前置任務:** Task 2

#### Steps:

- [x] 6.1 實作台股即時股價（`MarketDataService`）
  - 呼叫 TWSE API 取得台股即時報價
  - 解析股價、漲跌、漲跌幅

- [x] 6.2 實作美股即時股價（`MarketDataService`）
  - 呼叫 Yahoo Finance API
  - 管理 session cookie 與 crumb token

- [x] 6.3 實作股利率查詢（`MarketDataService`）
  - Yahoo Finance `quoteSummary` API
  - 台股自動 fallback：`.TW` → `.TWO`
  - 結果快取至 `StockPrice` entity

- [x] 6.4 實作股價快取管理（`StockPriceService`）
  - 依 code+market 查詢/更新快取
  - 提供批次更新介面

- [x] 6.5 實作股利率批次更新（`AssetService`）
  - `PATCH /api/snapshots/{id}/dividend-rates`：回寫指定快照配息率
  - `POST /api/snapshots/enrich-all-dividend-rates`：補齊所有快照缺漏配息率
  - `POST /api/snapshots/recalc-dividends`：依現值×配息率重算所有快照預估配息

- [x] 6.6 實作匯率歷史（`HistoricalDataService`）
  - 取得 USD/TWD 歷史匯率並儲存
  - `GET /api/market-data/exchange-rate?currency=USD&start=&end=`
  - `GET /api/market-data/exchange-rate/latest?currency=USD`
  - `POST /api/market-data/exchange-rate/refresh`
  - `POST /api/market-data/exchange-rate/backfill-history`

- [x] 6.7 實作開盤狀態（`MarketDataController`）
  - `GET /api/market-data/market-status`
  - 判斷台股與美股當日是否開盤

- [x] 6.8 實作歷史股價補齊（`HistoricalDataService`）
  - `POST /api/market-data/history/backfill`：補齊所有持股歷史股價
  - `POST /api/market-data/history/backfill-stock?code=&market=&since=&until=`：補齊單支，支援日期區間
  - `GET /api/market-data/history/stock?code=&market=`：查詢歷史股價
  - `POST /api/market-data/history/prices-on-date?date=`：批次查詢指定日期各股收盤價
  - ETF 代號後綴自動嘗試（U/L/R/B），支援 00642U 等 5 碼 ETF

---

### Task 7: 資產歷史趨勢

**對應 Requirements:** Requirement 8
**前置任務:** Task 3

#### Steps:

- [x] 7.1 實作歷史趨勢查詢（`AssetService`）
  - `GET /api/snapshots/history`
  - 依日期排序所有快照
  - 計算相鄰期間變化量與變化率
  - 計算投資比例（投資資產 / 總資產）

---

### Task 8: 前端 API 整合層

**對應 Requirements:** 所有前端需求
**前置任務:** Task 3, 5, 6

#### Steps:

- [x] 8.1 建立 Axios 實例（`api/index.js`）
  - base URL 指向 `/api`
  - 統一錯誤處理 interceptor

- [x] 8.2 實作所有 API 呼叫函式
  - Snapshots CRUD + import
  - Realized Gains CRUD + import
  - Market Data 查詢

- [x] 8.3 建立 Pinia Store（`assetStore.js`）
  - state：snapshots, currentSnapshot, loading
  - actions：fetchSnapshots, fetchSnapshot, createSnapshot, etc.

---

### Task 9: 前端頁面實作

**對應 Requirements:** Requirement 1, 3, 4, 6, 8, 9, 10
**前置任務:** Task 8

#### Steps:

- [x] 9.1 建立 App.vue（主佈局）
  - Sidebar 導覽選單（Element Plus Menu）
  - `<router-view>` 主內容區

- [x] 9.2 實作 DashboardView（Requirement 9）
  - KPI 卡片：總資產、存款、投資、損益
  - 資產類別圓餅圖（ECharts）
  - 與上期比較的變化量顯示

- [x] 9.3 實作 SnapshotListView（Requirement 1）
  - 快照列表表格（Element Plus Table）
  - Excel 匯入對話框（上傳元件 + overwrite 選項）
  - 新增/刪除操作按鈕

- [x] 9.4 實作 SnapshotDetailView（Requirement 1, 2, 3, 4）
  - 分頁標籤：總覽 / 銀行存款 / 股票持倉 / 基金持倉
  - 各類別圓餅圖與長條圖
  - 台灣地圖（TaiwanMap.vue）呈現銀行地理分佈
  - 美國地圖（UsaMap.vue）呈現美股市場

- [x] 9.5 實作 SnapshotFormView（Requirement 1）
  - 快照基本資訊表單（日期、匯率）
  - 銀行存款動態新增/刪除列
  - 股票持倉動態新增/刪除列
  - 基金持倉動態新增/刪除列

- [x] 9.6 實作 AssetHistoryView（Requirement 8）
  - 總資產折線圖（ECharts，時間軸）
  - 變化量與變化率數據表

- [x] 9.7 實作 RealizedGainView（Requirement 6）
  - 年度損益彙總卡片
  - 交易明細表格（可依年度篩選）
  - Excel 匯入功能

- [x] 9.8 實作 ExchangeRateView（Requirement 10）
  - 歷史匯率折線圖（買入/中間/賣出）
  - 時間範圍選擇器

- [x] 9.9 實作 TradingCalendarView（Requirement 7）
  - 台股/美股開盤狀態指示器
  - 當日市場資訊顯示

---

### Task 10: 測試與品質保證

**對應 Requirements:** 所有 Requirements

#### Steps:

- [x] 10.1 後端單元測試（基礎覆蓋）
  - `AssetServiceTest`：快照刪除、配息重算、歷史趨勢增幅計算
  - `ExcelImportServiceTest`：存款類型識別、美股代號判斷

- [x] 10.2 後端整合測試（Controller MockMvc）
  - `AssetSnapshotControllerTest`：GET /snapshots、DELETE、GET /history

- [ ] 10.3 後端進階測試（待補充）
  - `ExcelImportService` 使用測試 Excel 檔的 E2E 匯入流程
  - `MarketDataService` Mock 外部 API（TWSE / Yahoo Finance）

- [ ] 10.4 前端元件測試
  - Pinia Store action 測試（Vitest）
  - API 整合層 Mock 測試

- [ ] 10.5 手動驗收測試
  - 依 `requirements.md` 中每個 Acceptance Criteria 逐一驗證
  - 使用真實 Excel 檔案測試匯入流程
  - 驗證市場 API 整合（TWSE、Yahoo Finance）

---

### Task 12: 銀行與券商設定管理

**對應 Requirements:** Requirement 11
**前置任務:** Task 2, Task 4

#### Steps:

- [x] 12.1 建立 `Bank` 與 `BrokerEntity` Entity
  - `Bank.java`：欄位 id、code（唯一）、displayName、keywords（逗號分隔字串）、active
  - `BrokerEntity.java`：同結構（命名加 Entity 避免與舊 Enum 衝突）

- [x] 12.2 建立 `BankRepository` / `BrokerRepository`

- [x] 12.3 建立 Seed Data（`DataInitializer implements ApplicationRunner`）
  - 預設銀行：富邦、國泰、台新、LINE Bank、永豐、元大、華南
  - 預設券商：富邦證券、國泰證券、元大證券、華南證券
  - 使用 `findByCode().isEmpty()` 確保重啟不重複插入

- [x] 12.4 實作 `InstitutionService`（銀行/券商部分）
  - CRUD + 軟停用 + `matchBankByKeyword()` / `matchBrokerByKeyword()`

- [x] 12.5 實作 `InstitutionController`（銀行/券商端點）

- [x] 12.6 重構 `ExcelImportService`
  - 移除硬編碼對應，改呼叫 `InstitutionService.matchBankByKeyword()` / `matchBrokerByKeyword()`

- [x] 12.7 重構 `BankDeposit`、`FundHolding` Entity：`bank` 改為 `@ManyToOne Bank`

- [x] 12.8 重構 `StockHolding` Entity：`broker` 改為 `@ManyToOne BrokerEntity`，`market` 改為 `String`

- [x] 12.9 實作前端銀行/券商設定頁面（`BankSettingsView.vue`、`BrokerSettingsView.vue`）

- [x] 12.10 更新前端 API 層，表單下拉選單動態從 API 載入

---

### Task 13: 存款類型與市場類型設定管理

**對應 Requirements:** Requirement 12
**前置任務:** Task 12

#### Steps:

- [x] 13.1 建立 `DepositTypeEntity` / `MarketType` Entity
  - 欄位：id、code（唯一，同時作為存入欄位的值）、displayName、sortOrder、active

- [x] 13.2 建立 `DepositTypeRepository` / `MarketTypeRepository`

- [x] 13.3 補充 Seed Data（`DataInitializer`）
  - 存款類型：活存(1)/定存(2)/美元活存(3)/美元定存(4)/證券戶(5)/信用卡待付款(6)
  - 市場類型：台股(1)/美股(2)

- [x] 13.4 擴充 `InstitutionService`（存款類型/市場類型 CRUD + 軟停用）

- [x] 13.5 擴充 `InstitutionController`
  - `GET/POST /api/settings/deposit-types`、`PUT/PATCH /api/settings/deposit-types/{id}/active`
  - `GET/POST /api/settings/market-types`、`PUT/PATCH /api/settings/market-types/{id}/active`

- [x] 13.6 擴充 `InstitutionDto`（新增 DepositTypeResponse/Request、MarketTypeResponse/Request）

- [x] 13.7 實作前端設定頁面
  - `DepositTypeSettingsView.vue`（`/settings/deposit-types`）
  - `MarketTypeSettingsView.vue`（`/settings/market-types`）
  - 側邊欄「系統設定」新增兩個連結

- [x] 13.8 更新 `SnapshotFormView.vue`
  - `depositTypeOptions` 改為從 `GET /api/settings/deposit-types` 動態載入
  - 日期變更時自動查詢對應歷史匯率（`watch(() => form.snapshotDate, ...)`）

- [x] 13.9 新增 `POST /api/market-data/exchange-rate/backfill-history` 端點
  - 強制從指定日期補齊歷史匯率，不受現有 maxDate 限制

---

### Task 14: 即時股價排程與資產估算

**對應 Requirements:** Requirement 7（新增條目）、Requirement 9（新增條目）
**前置任務:** Task 6, Task 9

#### Steps:

- [x] 14.1 `StockPriceService.scheduledPriceUpdate()` — 已實作每 5 分鐘排程
  - 台股 09:00～13:30、美股 09:30～16:00（美東）交易時間內更新
  - 收盤視窗（台股 13:30～13:50、美股 16:00～16:20）以 `markClosed=true` 標記

- [x] 14.2 `HistoricalDataService` 每日收盤價寫入 `StockPriceHistory` — 已實作
  - 台股：14:00 排程（backfillTwStock，FinMind）
  - 美股：06:00 排程（backfillUsStock，Yahoo Finance）
  - 啟動時自動補齊 10 年缺漏資料（startupBackfill）

- [x] 14.3 `StockPriceService.getLiveAssets()` — 新增即時資產估算方法
  - 取得最新快照持倉（snapshotRepo.findLatestWithStocks）
  - 取得最新 USD/TWD 匯率（rateHistRepo.findClosestRate）
  - 對每筆持股查詢 StockPrice 快取，計算 liveValue
  - 回傳 LiveAssetsResponse（含各股明細、匯總、市場狀態）

- [x] 14.4 `MarketDataController` 新增 `GET /api/market-data/live-assets` 端點

- [x] 14.5 前端 `DashboardView.vue` 新增「即時資產估算」區塊
  - 顯示 liveTotalAssets（即時總資產）、liveStockValue
  - 顯示各股即時現值（表格）
  - 交易時間內每 5 分鐘自動刷新（setInterval）
  - 收盤後顯示「收盤估值」標籤，非交易時間不自動更新

- [x] 14.6 前端 `api/index.js` 新增 `getLiveAssets()` API 呼叫

---

### Task 16: 文件與部署

**前置任務:** Task 1–15

#### Steps:

- [x] 16.1 建立 README.md
  - 專案簡介與功能說明
  - 本地開發環境設定步驟（`start.sh` 說明）
  - Docker Compose 部署說明
  - Excel 格式規格說明
  - 完整 API 端點列表

- [x] 16.2 建立 `spec` 文件（本文件集）
  - `requirements.md`：需求與驗收條件
  - `design.md`：系統架構與資料模型（已與實作同步）
  - `tasks.md`：實作任務清單（本文件）

- [x] 16.3 環境變數文件
  - 建立 `.env.example` 說明所有必要環境變數
  - 建立 `.gitignore`，確認 `.env` 已排除版本控制

- [x] 16.4 資料庫遷移策略
  - 已採用 Liquibase，`ddl-auto: none`
  - `db/changelog/` 內含初始 schema 與後續變更（含 Bank / Broker / DepositType / MarketType / WatchStock / StockAlert / TransitFundType Seed Data）

---

### Task 14b: 股票交易類型與日期

**對應 Requirements:** Requirement 3
**前置任務:** Task 2, Task 3

#### Steps:

- [x] 14.1 StockHolding model 新增欄位（已實作）
  - `transactionType` (String)：買 / 賣
  - `transactionDate` (LocalDate)：交易日期
  - `transactionExchangeRate` (BigDecimal, precision 10,4)：交易日當日匯率（美股用）
  - `displayOrder` (Integer)：持倉顯示排序

- [x] 14.2 StockRequest DTO 新增對應欄位（已實作）

- [x] 14.3 AssetService 映射新欄位（createSnapshot / updateSnapshot）（已實作）

- [x] 14.4 新增 API endpoint `GET /api/market-data/exchange-rate/on-date?currency=USD&date=YYYY-MM-DD`（已實作）
  - 使用 ExchangeRateHistoryRepository.findClosestRate() 查詢

- [x] 14.5 前端 newBrokerRow / groupStocks / flattenStocks 支援新欄位

- [x] 14.6 台股 broker row 表格加入「買/賣」與「交易日期」欄位

- [x] 14.7 美股 broker row 表格加入「買/賣」與「交易日期」欄位；日期選定後自動抓取歷史匯率

- [x] 14.8 成本計算改用 transactionExchangeRate（優先）或 form.usdExchangeRate（備援）；美股持股成本依幣別顯示（TWD/USD 各自彙總）

---

### Task 15: 微服務架構重構（MVC → BFF + Business Services）

**對應 Requirements:** 非功能性需求（架構升級）
**前置任務:** Task 1–14

#### 架構決策
- **BFF Container**（`bff/`）：Spring Cloud Gateway + 頁面專屬路由設定 + Dashboard 聚合 Controller
- **Business Services Container**（`backend/`）：現有 Spring Boot MVC 應用，無需改動 API 程式碼
- **通訊**：BFF 透過 Docker internal network HTTP 呼叫 Business Services
- **資料庫**：維持單一 PostgreSQL（不拆分）

#### Steps:

- [x] 15.1 更新 `spec/tasks.md`（本任務）

- [x] 15.2 建立 `bff/` Spring Boot + Gateway 專案
  - `pom.xml`：spring-cloud-starter-gateway + spring-boot-starter-actuator
  - `BffApplication.java`：Spring Boot 啟動類別
  - `application.yml`：port 8080、CORS 設定、business-services URL 設定

- [x] 15.3 建立 per-page BFF 路由設定（`RouteLocator` @Bean，每頁一個 @Configuration）
  - `SnapshotBffRoutes.java`：`/api/snapshots/**` → business-services
  - `RealizedGainBffRoutes.java`：`/api/realized-gains/**` → business-services
  - `MarketDataBffRoutes.java`：`/api/market-data/**` → business-services
  - `SettingsBffRoutes.java`：`/api/settings/**` → business-services

- [x] 15.4 實作 `DashboardBffController`（BFF 聚合端點）
  - `GET /api/bff/dashboard/summary`：並行呼叫 snapshots、history、prices、market-status，一次回傳前端所需全部資料
  - `DashboardSummaryDto`：聚合 DTO（snapshots、history、stockPrices、marketStatus）

- [x] 15.5 建立 `bff/Dockerfile`（Maven 多階段建置 → JRE Alpine）

- [x] 15.6 更新 `docker-compose.yml`
  - 新增 `bff` service（port 8080，depends on business-services）
  - `backend` 移除 host port 暴露（僅 internal Docker network）
  - `frontend` depends_on 改為 `bff`

- [x] 15.7 更新 `frontend/nginx.conf`（`/api/` proxy 改指向 `bff:8080`）

- [x] 15.8 更新前端 `DashboardView.vue`
  - `onMounted` 改呼叫 `GET /api/bff/dashboard/summary`（單一請求取代 5 次並行請求）
  - 新增 `bffApi.getDashboardSummary()` 至 `frontend/src/api/index.js`

---

### Task 17: Excel 批次匯出

**對應 Requirements:** Requirement 5b
**前置任務:** Task 3, Task 5

#### Steps:

- [x] 17.1 實作後端 `ExcelExportService`
  - 使用 Apache POI 產生 `.xlsx`
  - 以工作表名稱（YYYYMMDD）對應快照日期
  - 涵蓋銀行存款、股票持倉、基金持倉工作表

- [x] 17.2 新增 `GET /api/snapshots/export` 與 `GET /api/realized-gains/export` endpoint
  - 回傳 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`

- [x] 17.3 前端「資產快照」、「歷年資產」、「已實現損益」頁面新增「匯出 Excel」按鈕
  - 觸發檔案下載

### Task 18: 股票走勢圖 Popup 延伸（ETF 持股明細 + 股利歷史）

- [x] 18.1 後端：`MarketDataService` 新增 ETF 判斷工具方法
  - 台股：code 以 `00` 開頭
  - 美股：白名單常數（VOO / VT / AVGO / VGT / QQQ / SPY … 可擴充）

- [x] 18.2 後端：新增 `GET /api/market-data/etf-holdings?code=&market=`
  - 台股：呼叫 FinMind `TaiwanETFHoldings`；回傳成分股（代號、名稱、持股比例、股數）
  - 美股：回 `{ supported: false }` 提示尚未支援
  - 失敗時回空清單 + 錯誤訊息，不丟 500

- [x] 18.3 後端：新增 `GET /api/market-data/dividends?code=&market=&years=10`
  - 台股：FinMind `TaiwanStockDividend`，取最近 N 年；欄位含年度、現金股利、股票股利、除息日
  - 美股：NASDAQ `/api/quote/{code}/dividends`
  - 當年殖利率以當前股價概估（optional）

- [x] 18.4 前端：DashboardView Popup 加入 `el-tabs`
  - 頁籤：走勢圖（預設）、持股明細（僅 ETF 顯示）、股利歷史
  - `isEtf(row)` 以代號規則判斷；走勢圖邏輯維持不動

- [x] 18.5 前端：持股明細頁籤呼叫新 API，以 `el-table` 顯示成分股
- [x] 18.6 前端：股利歷史頁籤以 `el-table` 顯示 10 年股利

### Task 19: 觀察股票清單

- [x] 19.1 後端：擴充 `stock_price` 與 `StockPrice` 實體，新增 buy_price / sell_price / open_price / previous_close / high_price / low_price / volume 欄位
  - Liquibase migration `v1.9.0-stock-price-quote-fields.sql`

- [x] 19.2 後端：擴充 `MarketDataService.PriceResult` 與 TWSE / NASDAQ / Yahoo 解析邏輯，補齊買賣/開盤/昨收/最高/最低/成交量
  - 台股 TWSE mis API：a/b（五檔賣/買）、o（開盤）、y（昨收）、h（最高）、l（最低）、v（成交量，張）
  - 美股 NASDAQ info API：openPrice、previousClosePrice、bidPrice、askPrice、dayHighLow、volume
  - Yahoo Finance fallback：summaryDetail.bid / ask / open / previousClose / dayHigh / dayLow / regularMarketVolume

- [x] 19.3 後端：`StockPriceService.updatePrices` 將新欄位一併寫入快取

- [x] 19.4 後端：新增 `WatchStock` 實體 + Repository + Service + Controller
  - Liquibase migration `v1.9.1-watch-stock.sql` 建立 `watch_stock` 資料表（unique: stock_code + market）
  - Endpoints: `GET/POST/DELETE /api/watch-stocks`、`PUT /api/watch-stocks/reorder`
  - 列表回傳整合 StockPrice（報價欄位）與 StockAlert（最近一次觸發時間/股價/均線/KD）
  - 將觀察股票的代號併入 `StockPriceService.collectHeldStockCodes` 的更新範圍

- [x] 19.5 前端：新增 `views/WatchStockView.vue` 與 `views/StockMonitorView.vue`，route `/stocks`（含 `tab` query），舊路徑 `/watch-stocks`、`/stock-alerts` 自動 redirect；左側選單 `股票觀察`
  - 台股 / 美股 兩個頁籤
  - 欄位：股名/股號、股價、漲跌、漲跌幅(%)、買進、賣出、開盤、昨收、最高、最低、成交量(張)、警示
  - 支援 sortablejs 拖曳排序（拖拉欄位置於最左邊）、新增/刪除（含確認對話框）、自動帶股名（沿用 `/api/stock-alerts/lookup-name`）

### Task 20: 資料庫備份／還原（UI 介面）

對應 Requirements: 15
前置任務: `scripts/backup.sh` 已就緒、host 端 `gdrive-crypt` rclone remote 已設定完成

- [x] 20.1 基礎建設：`backend/Dockerfile` 加裝 `postgresql-client` 與 `rclone`
  - 在 runtime stage（alpine）加 `RUN apk add --no-cache postgresql16-client rclone`
  - 確認 `pg_dump --version` 主版號需與 PostgreSQL server 一致（16）
  - rebuild backend image：`docker compose build business-services`

- [x] 20.2 基礎建設：`docker-compose.yml` 在 `business-services` 加掛載
  ```yaml
  volumes:
    - ${HOME}/.config/rclone:/root/.config/rclone:ro
  ```
  驗證：`docker exec asset-business-services rclone lsd gdrive-crypt:` 應正常列出資料夾

- [x] 20.3 後端：新增 `BackupController` (`/api/backups`)，三個 endpoint：
  - `POST /api/backups` 立即備份
  - `GET /api/backups` 列出所有備份（合併四個資料夾、依時間新→舊排序）
  - `POST /api/backups/restore` 還原（body: folder / filename / confirmation）

- [x] 20.4 後端：新增 `BackupService`，封裝 ProcessBuilder 呼叫
  - `runBackup(boolean isAutoPreRestore)`：pg_dump → rclone copy → 輪替（保留 5 份；自救點不計入）
  - `listBackups()`：對 manual/daily/weekly/monthly 各執行 `rclone lsjson --files-only`，合併後依 ModTime 排序
  - `runRestore(folder, filename)`：先呼叫 `runBackup(true)` 建自救點 → rclone copy 下載 → pg_restore --clean --if-exists
  - 所有指令參數白名單化，不接受使用者輸入拼接
  - 失敗時拋 `BackupException`，由 ControllerAdvice 統一格式

- [x] 20.5 後端：新增 `dto.BackupItem` record，欄位：folder / filename / sizeBytes / modifiedAt / isAutoPreRestore

- [x] 20.6 前端：新增 `views/BackupRestoreView.vue`
  - 上半部「立即備份」：按鈕 + 最近一次手動備份結果顯示
  - 下半部「還原資料」：`el-table` 列出所有備份，欄位 folder / filename / 備份時間 / 檔案大小，預設「新→舊」
  - 每列「還原」按鈕 → 開啟 `el-dialog`，需於 input 內輸入「確認還原」字樣，按鈕才 enable
  - 還原期間以 `v-loading` 全螢幕遮罩 + 文案「還原中…請勿關閉視窗」
  - 還原成功後 `ElMessage.success` + `setTimeout(() => location.reload(), 1500)`
  - 失敗時顯示後端回傳的錯誤訊息

- [x] 20.7 前端：`router/index.js` 加 route `/settings/backup-restore`，`App.vue` 系統設定子選單追加項目「備份/還原 資料」

- [x] 20.8 前端：`api/index.js` 新增 `backupApi`：
  - `list()` → `GET /api/backups`
  - `create()` → `POST /api/backups`
  - `restore({folder, filename, confirmation})` → `POST /api/backups/restore`
  - 為 `create` / `restore` 拉長 axios timeout 至 120 秒（pg_dump + pg_restore 可能需時）

- [x] 20.9 整合測試：
  - 手動備份 → 到 Google Drive 確認 `manual/` 多一份加密檔
  - 連續備份 6 次 → 確認最舊一份被刪、保留 5 份
  - 還原 → 確認 `manual/` 多一份 `auto-pre-restore_*` 自救點、目前 DB 資料被覆蓋為所選備份內容
  - 還原進行中前端遮罩生效、完成後自動 reload

- [ ] 20.11 保留代數設定 UI（對應 Requirement 15 新增條目）
  - Liquibase `v1.10.0-backup-setting.sql`：單列資料表 `backup_setting`，欄位 `manual_retention`/`daily_retention`/`weekly_retention` (INT NOT NULL)，預設 5/50/5，CHECK `id = 1`
  - 後端：新增 `BackupSetting` 實體 + Repository；`BackupService` 改為從 DB 讀取保留代數（每次輪替前 fetch；fallback 到預設常數）
  - `BackupController` 新增 `GET /api/backups/settings`、`PUT /api/backups/settings`，DTO 含三欄位驗證 1～999
  - 前端：`BackupRestoreView.vue` 新增「保留設定」區塊（三個 number input + 儲存按鈕），`backupApi.getSettings()` / `updateSettings()`

- [ ] 20.10 自動排程備份（對應 Requirement 15 新增條目）
  - `MarketDataService` 新增 `isTwTradingDay(LocalDate)` / `isUsTradingDay(LocalDate)`：以週末 + `getTwHolidays` / `getUsHolidays` 為依據
  - `BackupService.runBackup` 改為支援 `(folder, prefix)` 參數，預設 `manual/asset_manual_`；新增私有 `rotateFolder(folder, prefix, retention)` 取代 `rotateManual`
  - 新增 `@Scheduled` 方法（時區 `Asia/Taipei`，呼叫前先檢查交易日）：
    - `0 30 15 * * MON-FRI` → 台股交易日 → `daily/asset_daily_tw_*.dump`，輪替 50
    - `0 0 7 * * TUE-SAT` → 前一日為美股交易日 → `daily/asset_daily_us_*.dump`，輪替 50
    - `0 0 5 * * SUN` → `weekly/asset_weekly_*.dump`，輪替 5
  - `Application.java` / `BackupServiceTest`：確認 `@EnableScheduling` 已啟用（既有 `HistoricalDataService` 已使用排程，無需重複啟用）

---

### Task 21: 到價警示（Stock Alerts）

**對應 Requirements:** Requirement 16

#### Steps:

- [x] 21.1 後端：新增 `StockAlert` 實體 + Repository + Service + Controller
  - 欄位：id、stockCode、stockName、market、condition（價格門檻、均線、KD 等）、active、displayOrder、lastTriggeredAt、lastTriggeredPrice、lastTriggeredMa、lastTriggeredKd
  - Liquibase migration 建立 `stock_alert` 資料表

- [x] 21.2 後端：`StockAlertController` 端點
  - `GET /api/stock-alerts`、`POST /api/stock-alerts`、`PUT /api/stock-alerts/{id}`、`DELETE /api/stock-alerts/{id}`
  - `PATCH /api/stock-alerts/{id}/active`、`PUT /api/stock-alerts/reorder`
  - `POST /api/stock-alerts/check`、`GET /api/stock-alerts/lookup-name`

- [x] 21.3 前端：警示頁面整併進 `StockMonitorView.vue`「警示條件」頁籤（路徑 `/stocks?tab=alerts`，舊路徑 `/stock-alerts` 自動 redirect）

---

### Task 22: 待轉入資金類型設定管理（TransitFundType）

**對應 Requirements:** Requirement 17

#### Steps:

- [x] 22.1 後端：新增 `TransitFundType` 實體 + Repository
  - 欄位：id、code（唯一）、displayName、sortOrder、active

- [x] 22.2 後端：`InstitutionService` 擴充 + `InstitutionController` 端點
  - `GET /api/settings/transit-fund-types`、`GET /api/settings/transit-fund-types/active`
  - `POST`、`PUT /{id}`、`PATCH /{id}/active`

- [x] 22.3 後端：Seed Data（`DataInitializer`）建立預設待轉入資金類型

- [x] 22.4 前端：新增 `TransitFundTypeSettingsView.vue`，左側「系統設定」追加項目

---

### Task 23: 資料庫正規化 — 移除 legacy 字串欄位

**對應需求:** 技術債清理（非功能性需求）

#### Steps:

- [x] 23.1 新增 Liquibase migration `v1.9.2-drop-legacy-string-columns.sql`
  - DROP `bank_deposit.bank_name`（已由 `bank_id` FK 取代，Java Entity 未對應）
  - DROP `fund_holding.bank`（字串欄，已由 `bank_id` FK 取代，Java Entity 未對應）
  - DROP `stock_holding.broker`（字串欄，已由 `broker_id` FK 取代，Java Entity 未對應）
  - 保留 `realized_gain.broker`（歷史交易記錄，無 FK，屬刻意設計）

### Task 24: 資料庫正規化 — 移除 RealizedGain 衍生欄位

**對應需求:** 技術債清理（非功能性需求）

#### Steps:

- [x] 24.1 新增 Liquibase migration `v1.9.3-drop-realized-gain-derived-columns.sql`
  - DROP `realized_gain.profit`（= `proceeds - investment_cost`，衍生值）
  - DROP `realized_gain.profit_rate`（= `profit / investment_cost`，衍生值）
- [x] 24.2 `RealizedGain` Entity 移除 `profit` / `profitRate` 欄位
- [x] 24.3 `RealizedGainRepository.sumProfitByYear` 改為 `SUM(r.proceeds - r.investmentCost)`
- [x] 24.4 `AssetService` 寫入時忽略 `req.profit` / `req.profitRate`，讀取時於 DTO 層即時計算
- [x] 24.5 DTO 與前端 API 契約保持不變（`RealizedGainResponse` 仍回傳 `profit` / `profitRate`，由後端計算）

### Task 25: 歷史回補範圍擴大至 stock 主檔

對應 Requirements: 7

- [x] 25.1 `WatchStockService.create` 新增觀察股票時，同步 upsert 到 `stock` 主檔（與 holding / alert 流程一致）
- [x] 25.2 `HistoricalDataService.collectAllHeldCodes` 改以 `stock` 主檔為主來源，並聯集歷史快照中的持股代號（保險用）
  - 影響：`backfillAll`、`dailyTwStockUpdate`、`dailyUsStockUpdate`、`startupBackfill` 皆自動套用新範圍
  - 結果：凡列入主檔的股票（含觀察清單與警示）皆保有 10 年歷史收盤價並納入每日更新

### Task 26: 全面正規化 — 移除冗餘 / 衍生欄位

**對應需求:** 技術債清理（非功能性需求）— 對應 CLAUDE.md「資料庫完整正規化」規範。

#### 背景

CLAUDE.md 規定「相同的資料只能存一份；禁止同一欄位同時以 FK 和字串冗餘儲存；禁止存可由其他欄位計算得出的衍生值」。
全 Entity 稽核後發現以下違規欄位，本 Task 統一清理。

| Entity | 欄位 | 違規類型 | 處理 |
|---|---|---|---|
| StockHolding | stockName | 重複（stock 主檔已有） | DROP，DTO 由 join 填入 |
| StockAlert | stockName | 重複（stock 主檔已有） | DROP，DTO 由 join 填入 |
| WatchStock | stockName | 重複（stock 主檔已有） | DROP，DTO 由 join 填入 |
| StockPrice | stockName | 重複（stock 主檔已有） | DROP，DTO 由 join 填入 |
| StockPrice | priceChange | 衍生（price - previousClose） | DROP，改為 @Transient |
| StockPrice | changePercent | 衍生（priceChange / previousClose） | DROP，改為 @Transient |
| ExchangeRateHistory | midRate | 衍生（(buyRate+sellRate)/2） | DROP，改為 @Transient |
| RealizedGain | year (trade_year) | 衍生（YEAR(tradeDate)） | DROP，改為 @Transient |

#### Steps:

- [x] 26.1 新增 Liquibase migration `v1.9.4-drop-redundant-columns.sql` 一次 DROP 上述所有欄位
- [x] 26.2 Entity 層
  - StockHolding/StockAlert/WatchStock/StockPrice 移除 `stockName` 欄位
  - StockPrice 移除 `priceChange` / `changePercent` 欄位
  - ExchangeRateHistory 移除 `midRate` 欄位，新增 `@Transient getMidRate()`
  - RealizedGain 移除 `year` 欄位，新增 `@Transient getYear()`
  - StockPrice 新增 `@Transient getPriceChange()` / `@Transient getChangePercent()`
- [x] 26.3 Service 層
  - `AssetService` / `StockAlertService` / `WatchStockService` / `StockPriceService` / `ExcelExportService` 在組裝 DTO 時改以 `StockRepository.findByCodeAndMarket()` 取得 stockName
  - 寫入流程（create / update / Excel 匯入 / 行情抓取）若請求包含 stockName，呼叫 `stockMasterRepo.upsert()` 而非寫回 entity
  - `RealizedGainRepository.findByYearOrderByTradeDateAsc` 改用 `tradeDate BETWEEN start AND end` 區間查詢
  - `HistoricalDataService` 移除 `midRate` 計算與 setter，改用 entity 的 @Transient getter
- [x] 26.4 DTO 與前端 API 契約保持不變（`stockName` / `priceChange` / `changePercent` / `midRate` / `year` 仍回傳，由後端計算或 join 取得）

### Task 27: 新增觀察股票時即時抓價

對應 Requirements: 7（觀察清單體驗）

#### 背景

`stock_price`（即時報價表）只由 `StockPriceService.scheduledPriceUpdate` 在「市場開盤中或剛收盤」時段寫入。
若使用者在非交易時段把新股票加入觀察清單，畫面欄位（股價/開盤/昨收/最高/最低/成交量）會空白直到下次開盤，體驗不佳。

#### Steps:

- [x] 27.1 `WatchStockService.create` 在 upsert 完 stock 主檔後，呼叫 `stockPriceService.updatePrices(Set.of(code), market, false)`，即時抓一次行情寫入 `stock_price`
- [x] 27.2 抓價失敗以 warn log 記錄，不阻斷新增動作（觀察記錄仍寫入成功）

### Task 28: 啟動補抓主檔缺報價的股票

對應 Requirements: 7

#### 背景

`stock_price` 表只在「市場開盤中或剛收盤」由排程寫入。
若 stock 主檔（涵蓋持股 / 觀察 / 警示）中有股票尚無 `stock_price` 記錄，
畫面欄位會持續為空直到下次該市場開盤；即使 Task 27 為新增觀察補上了即時抓價，舊資料仍會空白。

#### Steps:

- [x] 28.1 `StockPriceService` 新增 `@EventListener(ApplicationReadyEvent.class)` 啟動補抓
  - 掃描 `stock` 主檔，找出沒有對應 `stock_price` 記錄的 (code, market)
  - 開獨立執行緒呼叫 `updatePrices()`，避免阻塞 Spring 啟動
  - `markClosed` 由 `isTwMarketOpen() / isUsMarketOpen()` 推導

### Task 29: 儀表板「股票持股」股價依基準日顯示

對應 Requirements: Requirement 9

#### 背景

總覽儀表板右上角的快照選擇器（snapshotDate）即「基準日」。原本 `DashboardView.getRealtimePrice()` 不分基準日，
一律以 `summary.stockPrices`（即時快取）覆蓋顯示，導致選擇過去日期的快照時，「股價」欄仍是當下最新價，
與買入均價／投資成本／現值（依快照計算）的時間基準不一致。

#### Steps:

- [x] 29.1 `DashboardView.vue` 新增 `isBaselineToday()` 判斷（`latest.snapshotDate === 今日 yyyy-MM-dd`）
- [x] 29.2 修改 `getRealtimePrice(row)`：
  - 僅當「基準日 = 今日」且該市場（`row.market`）`marketStatus.{tw|us}MarketOpen === true` 時，才回傳 `stockPrices` 即時價＋漲跌%
  - 否則回 `null`，模板自動 fallback 顯示快照中保存的 `row.stockPrice`（不顯示漲跌%）
- [x] 29.3 即時價來源（`StockPriceRepository`）已與「觀察股票」共用，無需後端調整；前端輪詢仍維持 5 分鐘

### Task 30: 修復基準日切換後圖表未跟動

對應 Requirements: Requirement 9

#### 背景

切換基準日後 5 分鐘內，輪詢 `loadDashboardSummary()` 會把 `selectedSnapshotId` 重置為最新快照，
KPI 卡 / 圓餅 / 持股表回跳到最新；另外資產歷史趨勢圖一直畫整段 history，未隨基準日截斷。

#### Steps:

- [x] 30.1 `refreshPricesAndStatus` 只呼叫 `marketDataApi.getAllPrices()` + `getMarketStatus()`，不再重抓 summary
- [x] 30.2 `loadDashboardSummary` 僅在初次（`selectedSnapshotId == null`）時設定 `selectedSnapshotId` 為最新；後續呼叫保留使用者選擇
- [x] 30.3 `trendOption` 與 KPI「較上次」都以 `snapshotDate <= 基準日` 過濾後的 history 計算

### Task 31: 股利歷史新增「除息日昨收價」欄位

對應 Requirements: Requirement 13

#### 背景

股利歷史頁籤需顯示除息日前一個交易日的收盤價，作為填息基準的參考。
原本 `calcFillDays` 內部已抓出該基準價但僅用於計算填息天數，未對外暴露。

#### Steps:

- [x] 31.1 後端 `MarketDataService.DividendRow` 新增 `previousClose` 欄位；提取共用 `DividendBasis` record，
       由 `calcDividendBasis()` 一次回傳前一交易日收盤與填息天數，避免重複查 `StockPriceHistory`
- [x] 31.2 `getTwDividendHistory` / `getUsDividendHistory` 改呼叫 `calcDividendBasis()` 並回傳 `previousClose`
- [x] 31.3 前端 `StockAnalysisDialog.vue` 股利歷史表格在「除息日」欄前新增「除息日昨收價」欄位（金額右對齊，無資料顯示 —）

### Task 32: 股利歷史新增年度小計列與現金殖利率欄

對應 Requirements: Requirement 13

#### 背景

原本股利歷史表只顯示每次除息事件。使用者希望仿 Yahoo 介面，於每年事件之上加上「年度小計列」，
快速看出當年合計現金股利與整體殖利率。

#### Steps:

- [x] 32.1 前端 `StockAnalysisDialog.vue` 新增 `dividendDisplayRows` computed：依 `year` 分組，
       於每年事件之上插入 `isYearSummary=true` 的小計列（合計現金股利、合計股票股利、年度殖利率）
- [x] 32.2 新增「現金殖利率」欄：事件列 = `cashDividend / previousClose × 100%`；年度列 = `年合計現金 / 該年最近一次事件的昨收價 × 100%`
- [x] 32.3 透過 `row-class-name="dividend-year-summary"` 與全域 CSS 將年度小計列以淺灰背景與粗體呈現
