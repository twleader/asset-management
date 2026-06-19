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

- [x] 14.1 `StockPriceService` 即時股價排程（台股 / 美股獨立 cron）
  - `scheduledTwIntradayUpdate()`：cron `0 0/2 9-13 * * MON-FRI` zone `Asia/Taipei`；方法內以 `isTwMarketOpen()` 過濾 09:00–13:30
  - `scheduledUsIntradayUpdate()`：cron `0 0/2 9-16 * * MON-FRI` zone `America/New_York`；方法內以 `isUsMarketOpen()` 過濾 09:30–16:00；EST/EDT 夏令由 JVM `ZoneId` 自動切換
  - 收盤後當日收盤價分別由 `recordTwClosingPrice()`（13:35 Asia/Taipei）/ `recordUsClosingPrice()`（16:05 America/New_York）單次 cron 觸發
  - 移除舊有單支 `scheduledPriceUpdate()` fixedRate（避免兩市場排程混雜、非交易時段空轉）

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
  - 欄位：股名/股號、股價、漲跌、漲跌幅(%)、開盤、昨收、最高、最低、成交量(張)、警示條件（所有 alert label）、警示（最近一次觸發）
  - 支援 sortablejs 拖曳排序（拖拉欄位置於最左邊）、新增/刪除（含確認對話框）、自動帶股名（沿用 `/api/stock-alerts/lookup-name`）

### Task 20: 資料庫備份／還原（UI 介面）

對應 Requirements: 15
前置任務: host 端 `gdrive-crypt` rclone remote 已設定完成（舊 host launchd 排程 `scripts/backup.sh` 已停用並移除，全改由 Spring Boot `BackupService` 排程）

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

- [x] 20.11 保留代數設定 UI（對應 Requirement 15 新增條目）
  - Liquibase `v1.10.0-backup-setting.sql`：單列資料表 `backup_setting`，欄位 `manual_retention`/`daily_retention`/`weekly_retention` (INT NOT NULL)，預設 5/50/5，CHECK `id = 1`
  - 後端：新增 `BackupSetting` 實體 + Repository；`BackupService` 改為從 DB 讀取保留代數（每次輪替前 fetch；fallback 到預設常數）
  - `BackupController` 新增 `GET /api/backups/settings`、`PUT /api/backups/settings`，DTO 含三欄位驗證 1～999
  - 前端：`BackupRestoreView.vue` 新增「保留設定」區塊（三個 number input + 儲存按鈕），`backupApi.getSettings()` / `updateSettings()`

- [x] 20.10 自動排程備份（對應 Requirement 15 新增條目）
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

- [x] 21.5 後端：警示建立／更新加入「代號↔股名」守門
  - `StockAlertService` 新增 `assertNameMatchesCode(code, market, userName)` 私有方法
  - `create` / `update` 在 `stockMasterRepo.upsert` 之前呼叫；以 `historicalDataService.fetchTwStockName / fetchUsStockName` 取得 canonical name
  - canonical 非空且與 user-supplied stockName 不一致 → throw `IllegalArgumentException("代號 X 與股名「Y」不符，外部來源為「Z」")`（GlobalExceptionHandler → 400）
  - canonical 空 → fall through，外部 API 異常時不阻擋
  - `0000` + `台股` 跳過；`0000` + `美股` 直接拒絕
  - 目的：阻止使用者把錯誤代號（如把 2500 標成「台積電」）寫入 `stock` 主檔造成觀察清單顯示「2500 台積電」但所有報價欄位皆為 —

- [ ] 21.4 觸發歷史紀錄（對應 Requirement 16 新增條目）
  - Liquibase `v1.11.0-stock-alert-trigger.sql`：建立 `stock_alert_trigger` 表，欄位 `id` / `alert_id` (FK CASCADE) / `stock_code` / `market` / `triggered_at` / `price` / `monthly_ma` / `quarterly_ma` / `annual_ma` / `k_value` / `d_value` / `created_at`，以 `(alert_id, triggered_at DESC)` 與 `(created_at)` 各一個索引
  - 後端：新增 `StockAlertTrigger` 實體 + Repository
  - `TechnicalIndicatorService` 擴充：新增 `computeAll(code, market)` 一次回 MA20 / MA60 / MA240 / K / D（保留舊 `compute()` 簽名以相容 WatchStockService）
  - `StockAlertService.evaluate` 觸發時除了更新 `last_triggered_*` 外，另計算完整指標並 INSERT 一筆 `stock_alert_trigger`
  - 新增 `@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Taipei")` 每日清理 `created_at < NOW() - 30 days` 的舊紀錄

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

`stock_price`（即時報價表）只由 `StockPriceService.scheduledTwIntradayUpdate` / `scheduledUsIntradayUpdate` 在「市場開盤中或剛收盤」時段寫入。
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

### Task 33: FinMind API token 支援（修正股利歷史 402）

對應 Requirements: Requirement 13

#### 背景

FinMind 自 2025 年起調整匿名呼叫額度，使用者開啟股票分析 → 股利歷史頁籤時前端顯示「FinMind 回應 402」。
後端原本所有 FinMind 呼叫皆未帶 token，匿名額度耗盡即全部失敗（影響股利歷史、ETF 持股、歷史收盤價回補等）。

#### Steps:

- [x] 33.1 後端新增環境變數 `FINMIND_TOKEN`（Spring property `finmind.token`），未設定時保持匿名行為向下相容
- [x] 33.2 `MarketDataService` 注入 token 並新增 `finmindRequest(url, timeoutSec)` 共用 builder，套用至股利率、ETF 持股、股利歷史三個 FinMind 呼叫
- [x] 33.3 `HistoricalDataService.httpGet()` 偵測 URL 為 `api.finmindtrade.com` 時自動加上 `Authorization: Bearer <token>` header（涵蓋歷史價、匯率、股票名稱）
- [x] 33.4 非 200 回應於 log 標記 token 是否有設定，方便除錯

### Task 34: SnapshotForm「台幣存款總計」併入在途款項

對應 Requirements: Requirement 1（資產快照 / 存款管理）

#### 背景

SnapshotForm 下方資訊列原本 `台幣存款總計` 只 sum `currency=TWD` 的存款，不含 TRANSIT_TWD，
使用者看到「在途 −287,047」分列在右邊會誤以為頂端 KPI `存款 / 總資產` 沒把在途扣掉
（實際上後端 `recalcTotals` 已將 TRANSIT_TWD 簽號合計進 `total_deposit`）。

#### Steps:

- [x] 34.1 前端 `SnapshotFormView.vue` 將 `depositTwdTotal` 改為「定存 + 活存 + 在途淨額」，
       使台幣 tab 下方總計 = 頂端 KPI `存款` 邏輯（皆含在途）；
       `depositTwdDemand` 改為直接 sum 非定存 TWD 條目，避免被總計倒推時混進在途

### Task 35: Dashboard 美元存款併入 TRANSIT_USD

對應 Requirements: Requirement 1（資產快照 / 存款管理）

#### 背景

Dashboard `bankSummary` 將 `TRANSIT_TWD` 與 `TRANSIT_USD` 同一條件分支都計入 TWD 的 `demand`，
導致美元在途（TRANSIT_USD）誤併進台幣活存，使 `美元合計` 不含在途、跨幣別分配失真。

#### Steps:

- [x] 35.1 前端 `DashboardView.vue` `bankSummary` 拆分 TRANSIT_TWD / TRANSIT_USD：
       TRANSIT_TWD → `demand`（台幣活存淨額）、TRANSIT_USD → `usdDemand`（美元活存淨額，amount 已是台幣值）

### Task 36: 即時股價排程拆成「開盤抓即時 + 收盤後單次紀錄」

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

`StockPriceService.scheduledPriceUpdate` 原本以 fixedRate 2 分鐘 + `isXxxJustClosed()`（收盤後 20 分鐘窗口）
觸發收盤價更新，導致同一個收盤窗口會被 fixedRate 反覆觸發 ~10 次；當 TWSE mis 連線異常 fallback 到 Yahoo
（已停用、每次 ~25 秒重試）時，請求堆積把整個 backend handler thread 拖滿，前端拿不到資料。

#### Steps:

- [x] 36.1 `StockPriceService.scheduledPriceUpdate` fixedRate 2 分鐘僅在 `isTwMarketOpen()` / `isUsMarketOpen()` 為 true 時抓即時價（closed=false），收盤後直接 return
- [x] 36.2 新增 `recordTwClosingPrice` cron `0 35 13 * * MON-FRI` Asia/Taipei，收盤後 5 分鐘單次寫入收盤價（closed=true）
- [x] 36.3 新增 `recordUsClosingPrice` cron `0 5 16 * * MON-FRI` America/New_York，收盤後 5 分鐘單次寫入收盤價
- [x] 36.4 `isXxxJustClosed()` 保留供 `resolveTradingDate` 判斷剛收盤窗口的 K 棒日期使用，但不再驅動排程

### Task 37: 即時股價移除 Yahoo Finance fallback + Yahoo crumb negative cache

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

`MarketDataService.getStockPrice` 原本在 TWSE mis / NASDAQ 失敗時 fallback 到 Yahoo Finance；但 spec design.md
已標註 Yahoo Finance 在 Docker 環境被擋（無法取得 crumb），每次呼叫會跑 ~25 秒重試後失敗，且 `getYahooCrumb`
為 `synchronized`，所有 thread 序列化排隊，造成排程觸發時整個 backend 卡住。

#### Steps:

- [x] 37.1 `getStockPrice` 移除 Yahoo Finance fallback：台股只用 TWSE mis、美股只用 NASDAQ；查無時直接 throw「查無股價：xxx」
- [x] 37.2 `getYahooCrumb` 加 5 分鐘 negative cache（`yahooCrumbBlockedUntil`），失敗後短時間內 fast-fail，不再讓其他 Yahoo 呼叫者（股利率、ETF 等）也被拖滿

### Task 38: 台股收盤價紀錄改用 FinMind

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

13:35 cron `recordTwClosingPrice` 原本透過 `getStockPrice` → TWSE mis 取當日收盤；TWSE mis 在 Docker 環境
偶爾完全連不到（"HTTP/1.1 header parser received no bytes"）會導致整批漏寫。FinMind TaiwanStockPrice
日結資料穩定且本來就有 token，足以涵蓋收盤價需求（無買賣五檔，但收盤後本就用不到）。

#### Steps:

- [x] 38.1 `MarketDataService` 新增 `getTwClosingPriceFromFinMind(code, startDate)`：呼叫 FinMind TaiwanStockPrice，
       回傳 `PriceResult`（close / open / max / min / spread / 估算 changePct / previousClose=close-spread / volume），
       source = `FinMind`
- [x] 38.2 `StockPriceService.recordTwClosingPrice` 改為迭代呼叫 FinMind 方法 + `persistPrice(...closed=true)`，
       不再走 `getStockPrice` / TWSE mis；log 紀錄成功與缺漏檔數

### Task 39: Dashboard 美股持股 bar 改用股票代號

對應 Requirements: Requirement 8（資產歷史趨勢）

#### 背景

`持股明細 (現值)` bar chart 的 y 軸原本都用 `stockName`，美股名稱常很長（如 "Vanguard S&P 500 ETF"），
被 grid.left=100 截斷。台股名稱短沒問題。

#### Steps:

- [x] 39.1 `DashboardView.vue` `stockBarOption.yAxis.data` 依 `chartMarketTab` 區分：台股用 `stockName`、美股改用 `stockCode`
- [x] 39.2 tooltip 標題保留「代號 + 名稱」完整資訊，避免 y 軸僅用代號時失去名稱可讀性

### Task 40: Dashboard 持股 bar 依損益上色

對應 Requirements: Requirement 8（資產歷史趨勢）

#### 背景

`持股明細 (現值)` bar 原本台股一律藍、美股一律橘，無法一眼看出哪些賺哪些賠。改為依該檔損益正負上色。

#### Steps:

- [x] 40.1 `DashboardView.vue` `stockBarOption` 移除 `barColor`（依市場著色），改為每筆 `itemStyle.color`
       依 `currentValue - investmentCost >= 0 ? 綠 : 紅`，台美股一致

### Task 39: 股價基準日規則改 per-market（修正美股盤中誤顯示前一交易日收盤）

對應 Requirements: Requirement 9（儀表板基準日股價）、Requirement 7（市場資料整合）

#### 背景

`SnapshotEnricher.isCurrentBasedate(basedate)` 原本只比對 `basedate == LocalDate.now(Asia/Taipei)`，
不分市場。實際使用者多在 TW 收盤後建快照（basedate = TW 那天），但美股 session 跨午夜（TW 21:30 → 隔日 05:00），
TW 已過午夜後 `basedate（昨天）== TW 今日（今天）` 必失敗 → Dashboard 走 `closeMapToPriceList(closeMap, basedate)`
鎖死在 basedate 收盤價，即使後端 cache 裡美股已是盤中即時價，畫面仍顯示前一交易日收盤。

#### Steps:

- [x] 39.1 `SnapshotEnricher` 新增 `isCurrentBasedate(LocalDate basedate, String market)` per-market 多載：
        台股比對 `Asia/Taipei`、美股比對 `America/New_York`（EST/EDT 由 JVM `ZoneId` 自動處理）；
        舊單參數版本移除（呼叫端全部改為帶 market 的新版）
- [x] 39.2 `DashboardBffController` 不再對整批 stockPrices 做 all-or-nothing 替換；改為 **per-stock**：
        每筆 price 依其 market 套 `isCurrentBasedate(basedate, market)` 決定保留 live 或換成 basedate 收盤
- [x] 39.3 `SnapshotFormBffController.enrichBatch` 同樣 per-stock：每 row 依 market 各自決定 useLive
- [x] 39.4 `closeMapToPriceList` 改為依需求 build 單筆 / 單市場版本（或在呼叫端按 market 篩選 closeMap）

### Task 40: SnapshotForm BFF 配息率批次抓取避免 30s timeout

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

`SnapshotFormBffController.enrichBatch` 對每檔股票分別呼叫 `/api/market-data/dividend-rate`，
原 concurrency=4、無 per-call timeout。21 檔持股若任一檔 FinMind 偶發 hang，整批會被拖到前端 axios 30s timeout，
畫面顯示 "-" 並 console 報 `批次載入股價失敗: AxiosError: timeout of 30000ms exceeded`。

#### Steps:

- [x] 40.1 `enrichBatch` dividend-rate Flux concurrency 從 4 提高到 16（21 檔內 1–2 批可消化）
- [x] 40.2 每筆 dividend-rate 加 per-call timeout 3 秒（`Mono.timeout(...).onErrorReturn(emptyMap)`），
        單檔 hang 不影響整批回應

### Task 41: dividend-rate 加 backend in-memory cache (TTL 1h)

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

`MarketDataService.getDividendRate` 每次都重打 FinMind / TWSE，cold call 5-13 秒（受 FinMind 後端拉資料 + 計算近 3 年平均影響）。
即使 BFF 把 concurrency 拉到 16 + per-call 3s timeout，21 檔 SnapshotForm 一發只會有 ~5 檔在 3s 內回；
其餘 dividendRate 為 null（畫面 fallback 顯示 snapshot 已存的值，但失去「重新整理拿最新」的功能）。
殖利率本身年級頻率變動，1 小時 cache 完全合理。

#### Steps:

- [x] 41.1 `MarketDataService` 新增 `dividendRateCache: ConcurrentHashMap<String, CachedRate>`，
        key = `code + "_" + market`，value 含 `(DividendRateResult, expiresAt)`，TTL 1 小時
- [x] 41.2 `getDividendRate` 進入時先查 cache：未過期回 cached；過期或 miss 才走原本 fallback chain，
        成功取得後 put cache（失敗的 N/A 也 cache 1 小時，避免 cold storm 反覆打 FinMind）
- [x] 41.3 SnapshotFormBffController 把 dividend-rate per-call timeout 從 3s 放寬到 8s
        （cold call 路徑也能完整回，cache 命中後本就 <5ms）

### Task 42: Dashboard 前端不再重做 basedate 判斷，一律信任 BFF flag

對應 Requirements: Requirement 9（儀表板基準日股價）、CLAUDE.md「同義欄位、同一 business service API」

#### 背景

Task 39 把 BFF `isCurrentBasedate` 改成 per-market，Dashboard BFF 也改成 `mergePerMarketPrices`，
但 `DashboardView.vue` 的 `getRealtimePrice()` 仍自己跑一遍 `isBaselineToday()`（純比 TW 今日）+ `marketStatus.{tw|us}MarketOpen`，
與 BFF 的 per-market 判斷重複且邏輯落後。
結果：BFF 已經把美股 live 價放進 `dto.stockPrices`，但前端 `getRealtimePrice` 第一行就 `if (!isBaselineToday()) return null`，
fallback 到 `row.stockPrice`（基準日收盤）→ Dashboard 顯示前一交易日收盤，與 SnapshotForm 不一致。

#### Steps:

- [x] 42.1 `DashboardView.vue` `getRealtimePrice(row)` 改成只看 `stockPrices[key].priceChange`：
        非 null → live（套漲跌色 / %）；null → 退回 `row.stockPrice` frozen 顯示
- [x] 42.2 移除 / 保留 `isBaselineToday()`：價格判斷不再用，但 styling（淡灰色）若仍需要可保留
- [x] 42.3 spec/design.md 寫清「per-market 判斷一律由 BFF 完成；前端禁止重做」

### Task 43: 到價警示觸發時間改用 LocalDateTime.now()（不再寫成收盤時間）

對應 Requirements: Requirement 14（到價警示）

#### 背景

`StockAlertService.evaluate` live 觸發路徑把 `lastTriggeredAt` 設為 `max(history.tradingDate)@13:30`（台股）或 `@16:00`（美股），
不是真正的觸發當下時間。畫面顯示「04/24 13:30」=「上一交易日的收盤時間」，與使用者期待的「cron 偵測到的當下時間」不符。
背景路徑（findRecentIntradayTrigger 從 Yahoo 5m 補抓）已正確用 bar 時間，無此問題。

#### Steps:

- [x] 43.1 `StockAlertService.evaluate` live 觸發路徑改 `triggeredAt = LocalDateTime.now()`，
        移除 `tradingDate.atTime(closeTime)` 的近似邏輯
- [x] 43.2 `triggeredAt` 一律 clamp 到該市場交易時段內：盤中（市場時區，週一～週五，TW 09:00–13:30 / US 09:30–16:00）用 `now()`；
        盤後或假日退回 `max(history.tradingDate)@close`。理由：cron 通常在收盤後幾分鐘才偵測到當日收盤觸發，
        裸 `now()` 會顯示 13:35 / 17:28 等盤外時間，與「到價」語意不符

### Task 44: 警示頁面 MA / KD 顯示「觸發時」值（不再混入當前值）

對應 Requirements: Requirement 14（到價警示）

#### 背景

`StockAlertDto.Response.{quarterlyMa,kValue,dValue}` 現由 `toResponse()` 用 `indicatorService.compute()` 即時計算，
與 `lastTriggeredAt/Price`（觸發時凍結值）混排在同一欄「觸發時間 / 股價 / 季線 / KD」，造成顯示不一致：
例如 0050「K 值高於 96」alert，觸發當時 K=96.59、D=94.14 都正確 ≥ 門檻，但畫面顯示 K=90.3 D=90.8（當前值），
看起來像「沒滿足條件卻觸發」誤導使用者。

`StockAlert` model 早已有 `lastTriggeredMaValue / lastTriggeredKdValue / lastTriggeredDValue`，
`checkMaDeviation` / `checkKdValue` 觸發時會自動寫入；只需 DTO 改回傳這些欄位、前端改 binding。

#### Steps:

- [x] 44.1 `StockAlertDto.Response` 把 `quarterlyMa/kValue/dValue` 改名為 `lastTriggeredMaValue/lastTriggeredKdValue/lastTriggeredDValue`
- [x] 44.2 `StockAlertService.toResponse()` 從 `alert.getLastTriggered*Value()` 讀取，不再呼叫 `indicatorService.compute()`
        （副作用：每筆 alert 少一次 indicator 計算，列表 query 加快）
- [x] 44.3 `StockAlertView.vue` 把 `row.quarterlyMa / kValue / dValue` 改為 `row.lastTriggeredMaValue / KdValue / DValue`
- [x] 44.4 PRICE_ABOVE/BELOW 等不算 MA/KD 的 alert 觸發後該欄位為 null，前端顯示 `—`（保持現有 fallback）

### Task 45: 管理資產（SnapshotForm 編輯模式）股價/漲跌與新增模式一致

對應 Requirements: Requirement 1（資產快照管理）

#### 背景

原 Task 45 規劃編輯模式（`/snapshots/:id/edit`，標題「管理資產」）股票表格一律顯示基準日歷史收盤、
不顯示漲跌、不輪詢。實際使用後決定改為與新增模式一致（per-market basedate 規則）：
基準日 = 該市場時區的今日 → 即時價＋漲跌＋輪詢；非今日 → 歷史收盤、無漲跌。
理由：使用者多在當日盤後建檔／微調快照，看得到漲跌幫助判讀；非今日的舊快照本來就沒 live cache，
fallback 為歷史收盤即可。

#### Steps:

- [x] 45.1 `SnapshotFormBffController.batchPrices` 移除 `historicalOnly` query 參數，
        `enrichBatch` 直接以 `preferLive`（basedate==市場今日 && live 有價）決定 price / priceChange / changePercent
- [x] 45.2 `frontend/src/api/index.js` `snapshotForm.prices(date, stocks)` 移除第三參數
- [x] 45.3 `SnapshotFormView.vue` 所有 `bffApi.snapshotForm.prices(...)` 呼叫處移除 `isEdit.value` 參數
        （`fetchPriceForRow` / `fetchPrice` / 複製前一版股票補價 / `loadAllPrices`）
- [x] 45.4 `SnapshotFormView.vue` `onMounted` 移除 `if (!isEdit.value)` gate，編輯模式也啟動 `startPriceAutoRefresh()`

### Task 46: 美股漲跌幅錯誤 — `previous_close` 改用歷史表權威值

對應 Requirements: Requirement 7（市場資料整合）、Requirement 9（儀表板基準日股價）

#### 背景

`StockPrice.priceChange / changePercent`（@Transient）= `price - previousClose`，
`previousClose` 寫入時優先取自 Yahoo `regularMarketPreviousClose`。觀察到 Yahoo 在週末 / 美股盤外
回傳異常舊值（NVDA 4/28 收到 4/22 close = 202.50；正確應為 4/27 close = 216.61），
造成 Dashboard 顯示 NVDA ▲5%、GOOGL ▲3.61% 等明顯錯誤的漲跌幅。

歷史表（`stock_price_history`）由我們自家的收盤紀錄 cron 寫入，是權威值；應一律以此為準。

#### Steps:

- [x] 46.1 `StockPriceService.persistPrice` 改用 `historyRepo.findClosestPrice(code, market, tradingDate.minusDays(1))`
        覆寫 `previousClose`；找不到歷史時才退回資料源提供的值；都沒有就維持 null

### Task 47: 抓股價拆出獨立微服務（external-materials-service + Redis live cache）

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

抓價邏輯（外部 API、cron、市場時段）目前內嵌於 `business-services`，外部 API 失敗會拖垮主服務、無法獨立 scale；
且 live 行情寫在 `stock_price` 表、收盤寫 `stock_price_history`，兩處 cache 容易漂移。
拆成獨立微服務 `external-materials-service`，盤中 2 分鐘抓價寫 Redis，盤後寫 DB；`business-services` 只當消費者，
live 行情先讀 Redis，miss fallback 到 `stock_price_history` 最近一筆。

#### Steps:

- [x] 47.1 新增 Maven module `external-materials-service/`：pom（spring-boot-starter-web + data-redis + jpa
        + lettuce-core + 共享 model jar），自己的 `Application.java`
- [x] 47.2 從 `backend/` 搬 `MarketDataService` 抓價相關（TWSE mis、NASDAQ info、FinMind close）、
        `StockPriceService.scheduledTw/UsIntradayUpdate`、`recordTw/UsClosingPrice`、
        `isTwMarketOpen/isUsMarketOpen`、`resolveTradingDate` 至 external-materials-service
- [x] 47.3 新增 `PriceCacheWriter`：序列化 price JSON 寫入 Redis key `price:{market}:{code}`
        TTL 600s；同步寫入 `price:index:{market}` set；`market:status` key TTL 90s
- [x] 47.4 新增 `ClosePersister`：盤後 cron 觸發，對 stock 主檔每筆呼叫 close 來源、寫 `stock_price_history`
- [x] 47.5 新增 `InternalPriceController`：`POST /internal/refresh` 同步抓價並寫 Redis 後回 200
- [x] 47.6 `backend/` 端：加 spring-boot-starter-data-redis 依賴；新增 `PriceQueryService`：
        `getLive(market, code)` 先讀 Redis，miss fallback `stock_price_history` 最近一筆收盤；
        `getAll()` 透過 `price:index:*` 列舉
- [x] 47.7 `MarketDataController` 的 `/api/market-data/prices`、`/live-assets`、`/market-status`、
        `/prices/refresh` 改打 `PriceQueryService`；`/prices/refresh` 走 WebClient 呼叫
        `http://external-materials-service:8080/internal/refresh` 後再從 Redis 回讀
- [x] 47.8 `Dockerfile` for external-materials-service（同 backend multi-stage）；docker-compose 新增
        `external-materials-service` container，depends_on postgres + redis；business-services depends_on redis
- [x] 47.9 Liquibase changelog `1.x.x` drop `stock_price` 表；移除 `StockPrice` Entity、
        `StockPriceRepository`；其他引用點改用 `PriceQueryService` 回傳的 DTO
- [x] 47.10 build 全套 image、docker compose up，smoke test：
        - `redis-cli KEYS 'price:*'` 應有資料
        - `GET /api/market-data/prices` 仍正常回傳
        - `POST /api/market-data/prices/refresh` 觸發後 Redis 內容更新
        - Dashboard / WatchStock / SnapshotForm 顯示股價無回歸

### Task 48: Live 股價即時推送（Redis pub/sub + SSE，取代前端 polling）

對應 Requirements: Requirement 7（市場資料整合）、Requirement 9（儀表板總覽）

#### 背景

前端 2 分鐘 polling + external-materials-service 2 分鐘 cron 的相位差可能讓最壞情況價格 lag 達 ~4 分鐘。
改用 Redis pub/sub：external-materials-service 寫入 Redis 同時 PUBLISH，business-services 訂閱後透過 SSE
推到前端，前端 EventSource 即時收到、零輪詢。

#### Steps:

- [x] 48.1 external-materials-service `PriceCacheWriter.write()` 寫完 Redis SET 後，
        `redis.convertAndSend("price-update", json)` 發布同一份 payload
- [x] 48.2 backend 新增 `PriceStreamService`：`Sinks.Many<String>` fan-out sink；
        新增 `RedisSubscriberConfig` 啟動 `RedisMessageListenerContainer` 訂閱 `price-update`
        channel，把 message body 餵給 sink
- [x] 48.3 backend `MarketDataController` 新增 `GET /api/market-data/prices/stream`
        回 `Flux<ServerSentEvent<String>>`，從 sink 即時串流
- [x] 48.4 bff 新增 passthrough route `/api/bff/market-data/stream` →
        `/api/market-data/prices/stream`
- [x] 48.5 frontend nginx config：`location /api/bff/market-data/stream { proxy_buffering off; ... }`
        避免 SSE 被 buffer 卡住
- [x] 48.6 `DashboardView.vue`：把 setInterval 改成 `new EventSource(...)`；onmessage 解析
        JSON 並更新 stockPrices reactive map；onerror 自動 reconnect。初始載入仍走
        `/api/bff/dashboard/summary`，後續增量更新走 SSE

### Task 49: 股利歷史快取至 DB（每日同步，UI 直讀 DB）

對應 Requirements: Requirement 7（市場資料整合）、Requirement 13（股票走勢圖延伸資訊）

#### 背景

`StockAnalysisDialog` 的股利歷史分頁每次開啟都打 FinMind，慢且耗 quota。
改為每日由 cron 從 FinMind 抓寫進 `stock_dividend_history`，前端只讀 DB。

#### Steps:

- [x] 49.1 Liquibase changelog `v1.13.0`：新增 `stock_dividend_history` 表
       （PK id，唯一鍵 (stockCode, market, year, COALESCE(exDividendDate, epoch))）
- [x] 49.2 新增 `StockDividendHistory` Entity + `StockDividendHistoryRepository`
- [x] 49.3 新增 `DividendHistoryService`：
        - `@Scheduled cron "0 0 17 * * MON-FRI"`：iterate `stock` 主檔，呼叫
          `MarketDataService.getDividendHistory(10)` 後 upsert
        - `findFromDb(code, market, years)`：DB 查詢，DB 空一次性 fallback 抓+寫
        - `@EventListener(ApplicationReadyEvent)`：背景補齊主檔中尚無資料的股票
- [x] 49.4 `MarketDataController /dividends` 改呼叫 `DividendHistoryService.findFromDb`

### Task 50: 盤中 high / low 自行聚合（修 NASDAQ ETF 無 dayrange）

對應 Requirements: Requirement 14（觀察股票清單 — 盤中 high/low）

#### 背景

NASDAQ info API 自 2026/04 起對 ETF 的 `keyStats` 為 null，VOO/VT 等 ETF 的盤中
最高 / 最低永遠抓不到（Redis 寫 null，盤後 dump 進 history 後 `low_price` 也是 null，
顯示為「—」或 `$0.00`）。改由 `external-materials-service` 自行以「每輪 cron 觀察到
的成交價」聚合當日 high / low。

#### Steps:

- [x] 50.1 新增 `IntradayHighLowTracker`（`external-materials-service`）：
        - Redis key `price:dayhl:{market}:{code}:{tradingDate}`，TTL 36h
        - `observe(code, market, tradingDate, price) → (high, low)`：以新價更新 Redis
          中的 high / low（max / min），回傳更新後的值
- [x] 50.2 `PriceCacheWriter.write()` 寫 `price:{market}:{code}` 前先呼叫 tracker：
        - external API 已給 high/low → 取 `max(extHigh, aggHigh)` / `min(extLow, aggLow)`
        - external API 未給 → 直接用聚合值
- [x] 50.3 `StockSourceQuery.upsertHistory` 移除 open / high 上的 `nz(...)` 包裝
        （與 low 一致改為直接寫入，保留 null 表「無資料」），避免 history 假裝有 0；
        close 仍套 nz 作雙保險（上游已用 price 過濾 null）
- [x] 50.4 Liquibase changelog `v1.14.0`：`stock_price_history.open_price` /
        `high_price` 改為可 null（原本 NOT NULL 是 50.3 用 0 偽裝的根因）

### Task 51: 儀表板新增「信託基金」長條圖

對應 Requirements: Requirement 9（儀表板總覽）、Requirement 4（基金資料）

#### 背景

儀表板目前只有銀行存款 bar 與持股 bar，沒有信託基金的明細圖；圓餅圖雖含基金佔比但無法看到單檔基金損益。
基金資料已隨 `/api/bff/dashboard/summary` 的 `latestSnapshotDetail.funds` 一起送到前端，純前端 render 即可。

#### Steps:

- [x] 51.1 `DashboardView.vue` 在第二排（銀行 / 持股）下方新增第三排卡片「信託基金」
        - 橫向 bar 圖，y 軸基金名稱、x 軸現值（升冪排序）
        - 顏色：profit >= 0 → 綠 (#16a34a)，否則紅 (#dc2626)；與 `stockBarOption` 同調
        - tooltip 顯示現值 / 成本 / 損益（含 %）
- [x] 51.2 卡片下方 `chart-summary-bar` 顯示總值 / 成本 / 損益（含 %）小計
- [x] 51.3 資料來源直接讀 `detail.value.funds`（不需新 BFF endpoint，基金不參與盤中輪詢）

### Task 52: 備份開關 + 紀錄改存 DB

對應 Requirements: Requirement 15（資料庫備份/還原）

#### 背景

原本「還原資料」UI 每次開啟都呼叫 rclone 列 Google Drive，慢且耗 quota；且沒有「暫停備份」開關，
排程預設一直跑。改為：備份成功後把 metadata 寫進 DB，UI 直接從 DB 讀；新增 `backup_enabled` 開關
讓使用者可以暫時關閉所有排程／手動備份。

#### Steps:

- [x] 52.1 Liquibase changelog `v1.15.0`：
        - `backup_setting` 加 `backup_enabled BOOLEAN NOT NULL DEFAULT TRUE`
        - 新增 `backup_record` (id PK, folder, filename UNIQUE, size_bytes, modified_at,
          auto_pre_restore, created_at)
- [x] 52.2 `BackupSetting` entity 加 `backupEnabled`；新增 `BackupRecord` entity + repository
- [x] 52.3 `BackupService`：
        - `runBackup` / 三個 `@Scheduled` 進入流程前檢查 `backupEnabled`，false 直接 skip / 拋
          錯（自救點 `autoPreRestore=true` 不受開關影響，避免還原無自救）
        - 每次 `doBackup` 成功 upload → insert `backup_record`
        - `rotateFolder` 刪除舊檔時連帶 `delete by folder+filename`
        - `listBackups()` 改成 `repo.findAllByOrderByModifiedAtDesc()`
        - 新增 `syncFromRemote()`：列 rclone → upsert / 清孤兒列
- [x] 52.4 `BackupController` + DTO 補 `backupEnabled`；新增 `POST /api/backups/sync` 對應
        `syncFromRemote`
- [x] 52.5 BFF passthrough route 補 `/api/bff/backup-restore/sync`
- [x] 52.6 `BackupRestoreView.vue` 「保留設定」加 `el-switch`「啟用備份」；「還原資料」加
        「從 Google Drive 同步」按鈕；「立即備份」按鈕在開關 off 時 disabled

### Task 53: GDP + 台股大盤年度走勢頁

對應 Requirements: Requirement 18（台灣人均 GDP 與台股大盤年度走勢比較）

#### 背景

新增一頁靜態總體經濟資訊，比較近 30 年「台灣人均 GDP（USD）」與「台股大盤 12/31 收盤」。
資料為歷史已知值，直接於 Liquibase changelog seed，不需外部 API。

#### Steps:

- [x] 53.1 Liquibase changelog `v1.16.0`：建立 `taiwan_gdp_per_capita_history` 與
        `twse_index_year_end_history`，並 INSERT 1996–2025 共 30 年資料
- [x] 53.2 後端 entities：`TaiwanGdpPerCapitaHistory`、`TwseIndexYearEndHistory`；對應 repository
- [x] 53.3 後端 controller：`MacroHistoryController` 提供 `/api/taiwan-gdp` 與
        `/api/twse-year-end-index`
- [x] 53.4 BFF：`bff/gdptwse/GdpTwseBffController`，路徑 `/api/bff/gdp-twse`，aggregate 兩支
        business API 回傳 `{ years, gdpPerCapitaUsd, twseYearEndClose }`
- [x] 53.5 前端：`GdpTwseView.vue` 雙 Y 軸折線圖（左 GDP / 右 大盤點位），`router/index.js` 加
        `/gdp-twse` route，`App.vue` 左側選單 push 一項，`api/index.js` 加 `bffApi.gdpTwse`
- [x] 53.6 後端 `MacroHistoryService` + `POST /api/taiwan-gdp/refresh-from-imf`、
        `POST /api/twse-year-end-index/refresh?from=YYYY&to=YYYY`：分別呼叫 IMF DataMapper
        (NGDPDPC/TWN) 與 TWSE FMTQIK 月報 upsert 至 DB
- [x] 53.7 BFF `POST /api/bff/gdp-twse/refresh`（並行觸發兩支 refresh），前端按鈕串接
- [x] 53.8 加入韓國比較：v1.17.0 changelog 建 `korea_gdp_per_capita_history`；後端
        `KoreaGdpPerCapitaHistory` entity/repo + `MacroHistoryService.refreshKoreaGdpFromImf`；
        controller 新增 `GET /api/korea-gdp` 與 `POST /api/korea-gdp/refresh-from-imf`；
        BFF 擴充回傳 `koreaGdpPerCapitaUsd` 與 `taiwanGdpGrowthRate` / `koreaGdpGrowthRate`；
        前端在原圖下加第二張卡（左 Y 折線雙國 GDP，右 Y 柱狀雙國年增率）
- [x] 53.10 觀察清單支援代號 `0000`（= 台股大盤 / TAIEX）：
        - `StockAlertController.lookupName`：`code=0000` + `market=台股` 短路回傳
          `{"stockName":"台股大盤"}`，不打外部、不寫 stock 主檔
        - `WatchStockService.create`：偵測 `0000` 時跳過 `stockMasterRepo.upsert`（避免被
          排程抓價當作真股票），仍可單獨儲存
        - `WatchStockService.toResponse`：`0000` 改讀 `twse_index_daily_history` 最新與次新
          一筆 → 填 `price` / `previousClose`，計算 `priceChange` / `changePercent`；
          季線從近 60 筆收盤平均；KD / buy / sell / open / high / low / volume 為 null
        - `TwseIndexDailyHistoryRepository` 補 `findTop2ByOrderByTradingDateDesc()` /
          `findTopNByOrderByTradingDateDesc(int n)` 取最新 N 筆
- [x] 53.9 加入大盤日線（近 10 年 + MA20/60/240）：
        - Liquibase `v1.21.0-twse-daily-history.sql` 建 `twse_index_daily_history`（trading_date PK,
          close_point NUMERIC(12,2)），不 seed 歷史值
        - 後端 `TwseIndexDailyHistory` entity / `TwseIndexDailyHistoryRepository`
          （`findByTradingDateBetweenOrderByTradingDateAsc`、`findTopByOrderByTradingDateDesc`）
        - `MacroHistoryService.refreshTwseDaily(int years)`：current month 起逐月往前抓 FMTQIK
          月報，把每筆交易日的 `index_close` upsert 至 `twse_index_daily_history`；月份間
          sleep 800ms。共 ~120 個月、~2 分鐘
        - `MacroHistoryController` 新增 `GET /api/twse-daily-index?from=&to=` 與
          `POST /api/twse-daily-index/refresh?years=10`
        - BFF `GdpTwseBffController` 新增 `GET /api/bff/gdp-twse/twse-daily?years=10`：
          載入近 N 年日線並計算 MA20/60/240（移動平均不足視窗時填 null），一次回 dates / closes /
          ma20 / ma60 / ma240
        - BFF 新增 `POST /api/bff/gdp-twse/refresh-twse-daily?years=10` proxy 至 business（timeout 180s）
        - 既有 `POST /api/bff/gdp-twse/refresh` 同步觸發大盤日線回補（與 GDP / 年末三項並行）
        - 前端 `GdpTwseView.vue` 最上方加第三張卡：ECharts line（4 條曲線：close、MA20、MA60、MA240）
          + el-radio-group 區間切換（1m / 3m / 6m / 1y / 2y / 5y）；切區間僅調整 dataZoom
          start/end，不重新打 API
        - `api/index.js` `bffApi.gdpTwse` 增 `getTwseDaily(years=10)` / `refreshTwseDaily(years=10)`
- [x] 53.11 「當年尚未到 12/31」以最後一個交易日大盤收盤代替：
        - business 新增 `GET /api/twse-daily-index/latest`（`MacroHistoryController`，list 長度 0 或 1）
        - BFF `GdpTwseBffController.get()` 並行多打 `/api/twse-daily-index/latest`；若 `twse_index_year_end_history`
          無當年紀錄，且最新一筆 daily 落在當年，則把該 `closePoint` 補進 `twseYearEndClose` 末元素，
          並回傳 `currentYearLastTradingDate`
        - 前端 `GdpTwseView.vue`：當年點以空心圓繪製（`symbol:'emptyCircle', symbolSize:9`），
          tooltip 對「台股大盤年末收盤」series 在當年加註「（截至 YYYY-MM-DD）」
- [x] 53.12 修正第一張圖成長率柱狀（bug fix，對齊 Requirement 18 / requirements.md 既有規範）：
        - 原本第一張「GDP vs 台股大盤」圖的成長率柱狀誤用前端 `gdpYoy`（人均 GDP USD 相減推算），
          會被匯率波動扭曲（如 2022 台幣貶值被算成負成長，實質為 +2.7%）
        - 改用 BFF 既有回傳的 `taiwanGdpGrowthRate`（IMF `NGDP_RPCH` 實質 GDP 成長率，`twGrowth`），
          與第二張「台韓比較」圖同一資料源；移除 `gdpYoy` computed
        - series / legend 名稱由「人均 GDP 成長率」改為「實質 GDP 成長率」以正確反映指標意義
- [x] 53.13 台灣 GDP / 成長率改用主計總處（DGBAS）為主、IMF 為備援：
        - ext-materials-service `MacroDataFetchClient.fetchDgbasNationalIncome()`：HttpClient 抓
          主計總處 NA8101A1A XML（`macro.dgbas.na8101-url` 設定，預設 data.gov.tw 資料集 44218 下載點），
          regex 解析「經濟成長率(%)」與「平均每人GDP(名目值，美元)」逐年原始值（用 contains 比對避全形標點），
          回 `{growth, gdpUsd}`；失敗回空 map
        - ext controller `InternalPriceController` 新增 `GET /internal/macro/dgbas`
        - backend `MacroHistoryService.refreshGdpFromImf()`（TWN）改為合併：DGBAS 逐年優先、缺值 fallback IMF
          （`NGDPDPC`/`NGDP_RPCH`），union 年份 upsert；回傳 `dgbasGdpYears` / `dgbasGrowthYears` 統計
        - 韓國 `refreshKoreaGdpFromImf()` 不變（DGBAS 無韓國資料）
        - DB schema 不變（`taiwan_gdp_per_capita_history` 既有欄位，存合併後單一值）；前端與 BFF 不變
        - 背景：DGBAS 為台灣官方權威來源（成長率精度 2 位小數、人均 GDP 與 IMF 幾近一致），
          且涵蓋 1951 起完整；唯無未來預測年（2026+）與韓國，故保留 IMF 補位

### Task 54: 信託基金最新淨值自動估值

對應 Requirements: Requirement 19（信託基金最新淨值自動估值）

#### 背景

`FundHolding.currentValue` 目前使用者每次建 snapshot 都要去華南銀行網銀抓最新值手填。
基金日結淨值是公開資訊（FundClear 境外基金資訊觀測站），改由系統自動抓淨值 + 匯率，
使用者只要填 `units`（總單位數），系統用 `units × NAV × FX` 算出 `currentValue` 寫入 snapshot。

抓 NAV 邏輯放 `external-materials-service`（與股價、股利同層級），對 backend 暴露
`POST /internal/fund-nav/refresh`。先用純 `java.net.http.HttpClient` 抓 FundClear，
不行再 fallback MoneyDJ；若兩家都因 SPA 抓不到，再回頭討論 Playwright。

僅 seed 6 支華南境外基金（02A8 / 02B9 / 01C2 / 1680 / 24B2 / 1616）；元大日本基金後續再加。

#### Steps:

- [x] 54.1 Liquibase changelog `v1.19.0`：建立 `fund_master`、`fund_nav` 兩表（含 `site` /
        `fundclear_org_code` / `fundclear_fund_code` / `fundclear_class_code` 欄位）；
        alter `fund_holding` 新增 `units NUMERIC(20,4) NULL`；seed `fund_master` 7 筆 —
        6 支華南 offshore（02A8/02B9/01C2 USD、1680/24B2 ZAR、1616 USD）+ 1 支元大
        onshore（93100953A TWD）
- [x] 54.2 backend entities + repos：`FundMaster`（含 site / fundclear 三段代碼）、`FundNav`；
        `FundHolding` 加 `units` 欄位（nullable）；`FundMasterRepository`、
        `FundNavRepository`（後者提供 `findTopByFundCodeOrderByNavDateDesc`）
- [x] 54.3 backend `HistoricalDataService.dailyExchangeRateUpdate` 與 `fetchBotExchangeRate`
        擴充：原本只抓 USD，改為遍歷 `fund_master` 出現的所有非 TWD currency 集合（ZAR 等）
- [x] 54.4 external-materials-service `FundNavFetchClient`：純 `java.net.http.HttpClient` +
        UA / Referer / Origin，依 `fund_master.site` 分流 — offshore 打
        `POST /api/offshore/nav-profit/query-history`（DTO 用 `organizeCode`/`fundCode`/
        `fundClassCode`），onshore 打 `POST /api/onshore/nav-profit/query-history`（DTO 用
        `orgId`/`fundNo`/`fundClassCode`）；日期格式 `YYYY/MM/DD`；回傳取 `tableList[0]` 最新
        `navValue`
- [x] 54.5 external-materials-service `FundNavPoller`：`@Scheduled(cron="0 0 9 * * *",
        zone="Asia/Taipei")` 全抓啟用基金；`FundNavPersister` upsert 至 `fund_nav`
- [x] 54.6 external-materials-service `InternalPriceController` 新增
        `POST /internal/fund-nav/refresh`，同步呼叫 `FundNavPoller.refreshAll()`
- [x] 54.7 backend `FundNavService`：`getLatestNavTwd(fundCode)` 回傳
        `{ nav, navDate, fxRate, fxDate, currency, twdPerUnit }`；TWD 計價基金 fxRate=1；
        找不到 NAV 時回 Optional.empty
- [x] 54.8 backend `AssetService.createSnapshot` / `updateSnapshot`：對每筆 FundHolding，
        若 `units` 非 null 且能查到 NAV+FX → 自動算 `currentValue = units × nav × fxRate`；
        否則保留使用者手填值（向後相容）
- [x] 54.9 backend `FundNavController`：`POST /api/fund-nav/refresh`（proxy 至 external
        `/internal/fund-nav/refresh`）；`GET /api/funds`（fund_master 列表，給前端 dropdown 用）
- [x] 54.10 BFF `SnapshotFormBffController` 預載 fund_master + 最新 NAV，回傳每支基金的
        `latestNav / latestFxRate / latestNavDate / currency`，前端可即時預覽 currentValue
- [x] 54.11 frontend `SnapshotFormView.vue` 信託基金區塊：基金代號改為 dropdown（從
        `/api/funds`）、新增 `units` 欄位、現值欄位改唯讀（顯示
        `units × NAV × FX` 即時計算）、加「刷新最新淨值」按鈕（呼叫
        `/api/fund-nav/refresh` 後重載 BFF）；NAV 日期早於今日 N 天時加警示 badge
- [x] 54.12 既有 6 筆 FundHolding 維持原 `currentValue` 凍結值（units 為 NULL，計算邏輯
        fallback 走手填路徑）；驗證歷史 snapshot 顯示不變
- [x] 54.13 commit + spec 同步

### Task 55: 信託基金主檔設定頁 + SnapshotForm 欄位合併

對應 Requirements: Requirement 19（信託基金最新淨值自動估值）

#### 背景

Task 54 把 fund_master 7 筆寫死在 DataInitializer，使用者沒有 UI 可以新增 / 編輯（例如 1616 多了一個 share class、或新增其他銀行的基金）。同時 SnapshotForm 信託基金區塊「基金代號」「基金名稱」兩欄並列其實重複——dropdown 已含 `「{code}　{name}」` label，名稱欄變多餘。

#### Steps:

- [x] 55.1 backend `FundNavController` 擴充 CRUD：`POST /api/funds`、`PUT /api/funds/{fundCode}`、
        `PATCH /api/funds/{fundCode}/active`；DTO `CreateFundRequest` / `UpdateFundRequest`
        （PK fundCode 僅 create 可填，update 不可改）
- [x] 55.2 BFF `FundBffRoutes` 既有 `/api/funds/**` passthrough 涵蓋新 CRUD，無需改動
- [x] 55.3 frontend `bffApi.fundSettings`：`getAll`、`create`、`update`、`setActive`
- [x] 55.4 frontend `FundSettingsView.vue` + `router/index.js` 加 `/settings/funds` route + `App.vue` 左側選單
        新增「💰 信託基金設定」項；表單欄位：fundCode（新增時可編輯）/ fundName / bank dropdown /
        currency / site / FundClear 三段代碼
- [x] 55.5 frontend `SnapshotFormView.vue` 信託基金區塊：移除「基金名稱」欄，dropdown 加寬；
        onFundCodeChange 仍會把 fundName 寫進 row（送出 payload 用），UI 不再顯示
- [x] 55.6 DataInitializer.seedFundMasters 確認為 idempotent（只插入不存在的 code）
- [x] 55.7 commit + spec 同步

### Task 56: 信託基金預估年配息

對應 Requirements: Requirement 20（信託基金預估年配息）

#### 背景

每支基金近 12 個月「每單位配息」累加 × units × FX = 年配息台幣估算。FundClear `info-dividend/query` 提供完整月配息歷史。模型對齊 `StockHolding.estimatedDividend`：snapshot 凍結值，建立 / 更新時由系統自動算入。

#### Steps:

- [x] 56.1 Liquibase changelog `v1.20.0`：建立 `fund_dividend_history` (id PK, fund_code, base_date,
        amount NUMERIC(20,6), currency, frequency, fetched_at)；唯一鍵 `(fund_code, base_date)`；
        index `(fund_code, base_date DESC)`。`fund_holding` 新增 `estimated_dividend NUMERIC(20,2) NULL`
- [x] 56.2 backend entity `FundDividendHistory` + `FundDividendHistoryRepository`；`FundHolding` 加
        `estimatedDividend` 欄位（nullable）
- [x] 56.3 external-materials-service `FundDividendFetchClient`：offshore POST
        `/api/offshore/fund-info/info-dividend/query`（`queryType:"1"`、`organizeCode`、`fundCode`、
        `fundClassCode`、`baseBeginDate/baseEndDate` YYYY/MM、`asiFreqList:[]`、`_pageNum:1`、
        `_pageSize:50`），onshore POST `/api/onshore/fund-info/info-dividend/query-dividend`；
        回應解析 `list[].asiBaseDate`（YYYY/MM/DD）與 `asiAmt`
- [x] 56.4 external-materials-service `FundDividendSourceQuery`：upsert `fund_dividend_history`
        （`(fund_code, base_date)` 視為覆寫）；讀取現有 fund_master active 清單沿用
        `FundNavSourceQuery.findActiveFunds()`
- [x] 56.5 external-materials-service `FundDividendPoller`：`@Scheduled(cron="0 5 9 * * *",
        zone="Asia/Taipei")`（NAV 排程後 5 分鐘）；每支 active 基金抓近 13 個月
- [x] 56.6 external-materials-service `InternalPriceController` 加
        `POST /internal/fund-dividend/refresh`
- [x] 56.7 backend `FundDividendService.getAnnualEstimateTwd(fundCode)`：
        `fund_dividend_history` 近 12 個月 amount 加總 × FX；找不到資料回 Optional.empty；
        TWD 計價基金 fxRate=1
- [x] 56.8 backend `FundNavController` 加 `POST /api/fund-dividend/refresh`（proxy 至 external）；
        `GET /api/fund-dividend/latest?fundCode=...` 給 BFF 預載用
- [x] 56.9 backend `AssetService.createSnapshot` / `updateSnapshot`：對每筆 FundHolding，
        若 `units` 非 null 且 `FundDividendService` 回傳 estimate，
        `fund.estimatedDividend = units × annualPerUnitTwd`，否則保留前端送進來的值（向後相容）
- [x] 56.10 backend `AssetService.recalcTotals`：`estimatedAnnualDividend` 合計加入 fund.estimatedDividend
        （目前只算 stock）；確認 SnapshotEnricher / Dashboard / AssetHistory 抓的是 snapshot 層級欄位
- [x] 56.11 backend `AssetSnapshotDto.FundResponse` + `FundRequest` 加 `estimatedDividend`、`dividendRate`
- [x] 56.12 BFF `SnapshotFormBffController.listFunds()` 回傳 fund_master 時併回 `annualDividendPerUnitTwd`
        欄位，前端用以即時預覽（同 NAV 預覽路徑）
- [x] 56.13 frontend `SnapshotFormView.vue` 信託基金 row 加「預估年配息」column（read-only），
        units 變更時即時算；底部 sec-summary 加「預估年配息」彙總
- [x] 56.14 commit + spec 同步

### Task 57: 信託基金歷史 NAV / 配息 / FX 回補 + 基準日估值

對應 Requirements: Requirement 21（信託基金歷史 NAV 與基準日估值）

#### 背景

既有 `fund_nav` / `fund_dividend_history` 只抓「最新」/「近 13 月」。要支援編輯舊 snapshot 用基準日 NAV / FX / 配息計算，需 10 年歷史回補 + AssetService 改為 date-aware。決議 A：存檔覆寫使用者手填 currentValue 為基準日值。

#### Steps:

- [x] 57.1 external-materials-service `FundNavBackfillService`：對每支 active 基金分段（每段一年）抓 10 年 NAV，呼叫既有 `FundNavSourceQuery.upsertNav`；`InternalPriceController` 加 `POST /internal/fund-nav/backfill?years=10`
- [x] 57.2 external-materials-service `FundDividendBackfillService`：同 57.1 模式，10 年配息
- [x] 57.3 backend `HistoricalDataService.startupBackfill` 擴充：對 `fund_master` 中所有非 TWD currency 補 10 年（`backfillExchangeRateFrom(currency, 10y)`）
- [x] 57.4 backend `FundNavService.getNavTwdOnDate(fundCode, basedate)`：closest-on-or-before NAV + 該日 closest FX；TWD fxRate=1
- [x] 57.5 backend `FundDividendService.getAnnualEstimateOnDate(fundCode, basedate)`：取 `[basedate-12m, basedate]` 區間 amount 加總 × 該日 FX
- [x] 57.6 backend `AssetService` snapshot create / update 把 basedate 傳進 `resolveFundCurrentValue` / `resolveFundEstimatedDividend`；helper 改 signature
- [x] 57.7 backend `FundNavController` `POST /api/fund-nav/backfill?years=10`、`POST /api/fund-dividend/backfill?years=10` proxy；`FundDto` 加 `?date=YYYY-MM-DD` 支援基準日值
- [x] 57.8 BFF `SnapshotFormBffController.listFunds` 接 `?date=YYYY-MM-DD` 參數透傳到 backend
- [x] 57.9 frontend `bffApi.snapshotForm.getFunds(date)` 加 date 參數；`loadFundMasters` 帶 `form.snapshotDate`；改 snapshotDate 時重抓
- [x] 57.10 commit + spec 同步

### Task 58: 走勢圖「股價」線只取實際成交價

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

走勢圖的「股價」依定義為實際成交價（成交價）。`PriceFetchClient.getTwseRealTimePrice` 在 TWSE `z` 為 `-`（兩 tick 之間）時改用「買賣中價」或「前收」估算現價（`source = "TWSE(買賣中價)"` / `"TWSE(前收)"`），這對 Dashboard 即時資產估值有用，但若 `HistoricalDataService.getStockHistory` 把它拼為今日 closePrice，就會讓走勢圖股價末端出現如台積電 2262.5（違反 5 元 tick）這種「不可能成交」的數字。

#### Steps:

- [x] 58.1 backend `HistoricalDataService.getStockHistory`：補今日 live 價時檢查 `LivePrice.source`，若含括號（估算來源）則略過，僅在實際成交（`source == "TWSE"` / `"NASDAQ"` 等不含括號）時才拼入
- [x] 58.2 external-materials-service `PriceFetchClient` 新增 `snapToTwTick` helper；TWSE 中價估算分支套用，使 Dashboard 即時資產列表也只顯示合法 tick 價位（change/changePct 從對齊後的 estimated 重算，已是現有邏輯）
- [x] 58.3 frontend `SnapshotFormView.vue` summaryTotalAssets 改為「存款 + 基金 + 台股 + 美股」直接加總，不再讀 stored `totalAssets`，避免 bar 上分項與總資產對不起來
- [x] 58.4 frontend `DashboardView.vue` `trendLegendItems` 比照 `trendOption` 套用 liveLatest overlay，使趨勢圖例「總資產」與上方 KPI「資產總計」、趨勢線最後一點同步（之前 KPI 顯示 18,165,410 但圖例仍顯示 stored 17,836,374）
- [x] 58.5 BFF `AssetHistoryBffController` `getHistory` 在最新一筆 snapshotDate == 今日（即 live-assets 指向同一筆 snapshot）時，呼叫 `/api/market-data/live-assets` 並用其結果覆蓋最新列的 totalTwStockValue / totalUsStockValue / totalStockValue / totalAssets / increase / increaseRate / investmentRate，使「歷年資產管理」最新列與 Dashboard live 顯示一致
- [x] 58.6 external-materials-service `PriceFetchClient.snapToTwTick` 加入 stockCode 參數：ETF（代碼以 "00" 開頭）tick = 0.01，個股仍依價格分級。先前 ETF 中價估算被 snap 到 0.5 元 tick，造成 006208 等寫入 219.0000（實為 219.20）。
- [x] 58.7 external-materials-service `InternalPriceController` 新增 `POST /internal/close/verify-tw` / `verify-us`，手動觸發 FinMind 校正當日收盤（同 16:00 / 18:00 排程），用以修復 ETF tick 修正前已寫錯的 row
- [x] 58.8 確立全域規則「股價一律是成交價」：移除 `PriceFetchClient.getTwseRealTimePrice` 的買賣中價估算分支與 `snapToTwTick` helper；`z` 為 `-` 時直接退回 prevClose（real 昨日成交價）。Dashboard / 管理資產 / 走勢圖等所有消費者都不再可能拿到中價估算
- [x] 58.9 commit + spec 同步

### Task 59: Dashboard KPI「資產總計」與「歷年資產管理」對齊

對應 Requirements: Requirement 9（儀表板總覽）

#### 背景

Dashboard 頂部 KPI「資產總計」走前端 `liveLatest` 計算，且加上「市場開盤」閘門 — 收盤後（含週末）整段 live overlay 跳過，回退到快照 stored value。「歷年資產管理」今日列則是 BFF 直接呼叫 `/api/market-data/live-assets`，後端 `getLive()` 在 Redis cache 過期時會 fallback 到 `stock_price_history` 最近一筆收盤，故任何時段都能算出「上一交易日收盤」估值。結果：週末看到 Dashboard 資產總計 ≠ 歷年資產管理今日列資產總計，違反 CLAUDE.md「同義欄位、同一 business service API」。

#### Steps:

- [x] 59.1 BFF `DashboardSummaryDto` 加 `liveAssets` 欄位；`DashboardBffController` `/summary` 與 `/realtime` 並行呼叫 `/api/market-data/live-assets` 並回傳，與「歷年資產管理」共用同一支 API
- [x] 59.2 frontend `DashboardView.vue` 新增 `liveAssets` state（由 summary / realtime 寫入）；`shouldApplyLive` 移除「市場開盤」閘門僅留 `isBaselineToday`；`overlayLivePrice` 優先使用 `liveAssets.stocks[].liveValue` 重算 row 現值；`liveLatest.totalAssets` 優先採用 `liveAssets.liveTotalAssets`
- [x] 59.3 spec 同步 — Requirement 9「市場開盤」閘門條款移除；design.md 加註 KPI 一致性規則與 BFF Aggregation 端點清單
- [x] 59.4 commit + 服務重啟驗證

### Task 60b: 將股票歷史回補 + 啟動 10 年回補 + FX FinMind 回補搬到 external-materials-service

對應 Requirements: Requirement 7（即時股價/快取）— [requirements.md:108-109](spec/requirements.md)

#### 背景

`HistoricalDataService` 仍保有大量「business-services 直接呼叫外部行情 API」的違反：
- `@EventListener(ApplicationReadyEvent)` 啟動時 10 年回補 — FinMind / Yahoo
- `backfillTwStock`、`backfillUsStock` — 對前端 `/api/market-data/history/backfill*` 端點公開
- `backfillExchangeRate` / `backfillExchangeRateFrom` — FinMind TaiwanExchangeRate
- `dailyExchangeRateUpdate`（17:00 排程）— 內部呼叫上述 FinMind 方法

依「即時股價走 Redis、外部行情 API 全部集中 external-materials-service」的規則（spec L108-109），這些對外抓取邏輯都應集中到 external-materials-service。business-services 只該透過內部 endpoint 觸發、再從 DB 讀回結果。

#### Steps:

- [x] 60b.1 external-materials-service 新增 `HistoricalBackfillService`：含 `@EventListener(ApplicationReadyEvent)` 啟動 10 年回補（股票 + USD/fund_master 幣別）、`backfillTwStock`、`backfillUsStock`、`backfillExchangeRate`、`backfillExchangeRateFrom`、`backfillSingleStock`、`backfillAll`
- [x] 60b.2 external-materials-service `PriceFetchClient` 新增 `fetchTwHistoricalRange`（FinMind TaiwanStockPrice 區間 + ETF 後綴 retry）、`fetchUsHistoricalRange`（Yahoo Finance chart API via curl + 429 retry）
- [x] 60b.3 external-materials-service 新增 `ExchangeRateFetchClient`：FinMind TaiwanExchangeRate 區間抓取（cash_buy/cash_sell fallback spot_buy/spot_sell）
- [x] 60b.4 `StockSourceQuery` 新增 JDBC：`findMinTradingDate`、`existsHistory`、`findMaxRateDate` / `findMinRateDate`、`upsertExchangeRate`、`collectTrackedCurrencies`、`collectAllHeldCodes`
- [x] 60b.5 `InternalPriceController` 新增 `/internal/backfill/stock`、`/internal/backfill/all`、`/internal/backfill/exchange-rate`、`/internal/backfill/exchange-rate-from`
- [x] 60b.6 business-services `HistoricalDataService`：刪除 startupBackfill / backfillTw/UsStock / backfillExchangeRate(From) / collectAllHeldCodes / 不再用的 imports；`backfillSingleStock` / `backfillAll` / `backfillExchangeRate` / `backfillExchangeRateFrom` 改 WebClient proxy 至 ext-materials；`dailyExchangeRateUpdate` 仍在本地排程但內部走 proxy
- [x] 60b.7 編譯驗證（business-services + external-materials-service 均通過）
- [x] 60b.8 commit + 服務重啟驗證（觀察 ext-materials-service 啟動時 8s 後執行 startupBackfill；business-services 啟動 log 無 "啟動補齊"；前端按「回補資料」仍可運作）

### Task 60c: 匯率排程（BOT 5 分鐘 + FinMind 17:00）搬到 external-materials-service

對應 Requirements: Requirement 7 — [requirements.md:108-109](spec/requirements.md)

#### 背景

business-services `HistoricalDataService` 仍持有：
- `intradayExchangeRateUpdate` `@Scheduled(cron = "0 0/5 9-15 ...")`：每 5 分鐘呼叫 `fetchBotExchangeRate` 抓 BOT CSV
- `dailyExchangeRateUpdate` `@Scheduled(cron = "0 0 17 ...")`：呼叫 backfillExchangeRate（FinMind 增量）+ purgeOldExchangeRates
- `fetchBotExchangeRate(currency)`：直接 curl 抓 https://rate.bot.com.tw/xrt/flcsv/0/day

依規則「對外抓行情 / 匯率全集中 ext-materials-service」，這三項都該搬走。本地清理 (`purgeOldStockPriceHistory` / `purgeOldExchangeRates`) 因只動 DB 可留；改名為 `purgeOldHistory` 收斂為單一排程於 17:30 跑。

#### Steps:

- [x] 60c.1 ext-materials-service 新增 `BotFxFetchClient`：BOT CSV via curl，回 `SpotQuote(spotBuy, spotSell)`
- [x] 60c.2 ext-materials-service 新增 `ExchangeRatePoller`：`@Scheduled` 盤中 5 分鐘 BOT + 17:00 FinMind FinMind 增量補；提供 `refreshBotNow(currency)` 給手動觸發
- [x] 60c.3 `InternalPriceController` 加 `POST /internal/exchange-rate/refresh-bot`
- [x] 60c.4 business-services `HistoricalDataService`：刪除原 BOT 抓 / 排程 / `currenciesToTrack` / `parseBotDecimal`；`fetchBotExchangeRate` 改為 WebClient proxy；新增 `purgeOldHistory` `@Scheduled(17:30)` 收斂本地清理
- [x] 60c.5 編譯驗證（business-services + ext-materials-service 皆通過）
- [x] 60c.6 commit + 服務重啟驗證（觀察 ext-materials-service 9-15 點 5 分鐘 log、business-services 不再有 BOT log）

### Task 60h: 修台股大盤日線抓取（舊 URL 失效 + 加每日排程）

對應 Requirements: Requirement（GDP+大盤）

#### 背景

台股大盤每日收盤圖表停在 5/5 之後沒更新到 5/6。診斷發現兩個問題：
1. **舊端點 `https://www.twse.com.tw/exchangeReport/FMTQIK?response=json&date=...` 站台維護中** —— 用 curl HTTP/1.1 直打回傳 `<html>網站維護中</html>`，HTTP/2 直接 timeout。`MacroDataFetchClient.fetchTwseMonthlyDaily` 因此只回 `[]`。
2. **無自動排程** —— 之前完全靠前端「回補日線」按鈕手動觸發。即使按鈕能用，使用者也得每天記得按。

替代端點 `https://openapi.twse.com.tw/v1/exchangeReport/FMTQIK?date=YYYYMM01` 仍可用，但 schema 不同：
- 直接是 array of objects（無 `stat`/`data` 包裝）
- `Date` 為民國格式 `"1150504"` 而非 `"115/05/04"`
- 收盤值欄位名為 `TAIEX` 而非陣列 index 4

#### Steps:

- [x] 60h.1 `MacroDataFetchClient` 改用 openapi 端點 + 解析 array-of-objects schema（新 Date 格式 7 字元、TAIEX 鍵）
- [x] 60h.2 ext-materials-service 新增 `TwseIndexPoller`：`@Scheduled` 14:00 / 17:00 / 隔日 08:30 三次抓當月（每月 1~5 日跨月時補上月）`upsert` 至 `twse_index_daily_history`
- [x] 60h.3 `StockSourceQuery` 新增 `upsertTwseIndexDaily(date, closePoint)`
- [x] 60h.4 `fetchTwseDecemberClose` 改 reuse `fetchTwseMonthlyDaily(year, 12).last`，避免重複維護
- [x] 60h.5 編譯驗證
- [x] 60h.6 commit + 服務重啟驗證（隔日 08:30 cron 跑後 5/6 應寫入 DB；openapi 此刻還沒上 5/6）

### Task 60g: 警示頁文案校正（沒有基準日，盤中每 2 分鐘隨股價更新檢查）

對應 Requirements: Requirement 16（警示）

#### 背景

[StockAlertView.vue:16](frontend/src/views/StockAlertView.vue) 文案寫「每 5 分鐘隨股價更新自動檢查」，但實際排程是 ext-materials-service `PricePoller` 每 **2 分鐘** poll 一次（台股 09:00–13:30 / 美股 09:30–16:00 ET），寫 Redis 後 publish 到 `price-update` channel，business-services 立即 `StockAlertService.checkAlertsFor` 評估。Alert 完全沒有基準日 / snapshot 概念 — 盤中只要 Redis 有新報價，警示條件就會被檢查。

#### Steps:

- [x] 60g.1 `StockAlertView.vue` 改為「盤中（台股 09:00–13:30、美股 09:30–16:00 ET）每次股價更新（每 2 分鐘）即時檢查」
- [x] 60g.2 commit + 前端重建驗證

### Task 60d: 警示盤中 5 分鐘 K 線抓取搬到 external-materials-service

對應 Requirements: Requirement 7、Requirement 16（警示）

#### 背景

`HistoricalDataService.fetchIntraday5m(code, market, daysBack)` 直接呼叫 `query2.finance.yahoo.com/v8/finance/chart/{ticker}?interval=5m&range=Nd`（透過 curl + 429 retry），用於 `StockAlertService` 警示觸發補抓精確時點。仍然違反「對外行情 API 集中 ext-materials-service」原則。

#### Steps:

- [x] 60d.1 ext-materials-service `PriceFetchClient.fetchIntraday5m`：搬遷 Yahoo 5m + curl retry 邏輯，回傳 `IntradayBar(time:String, OHLC)`
- [x] 60d.2 `InternalPriceController` 加 `GET /internal/intraday-5m?code=&market=&daysBack=`
- [x] 60d.3 business-services `HistoricalDataService.fetchIntraday5m` 改為 WebClient proxy；`IntradayBar(LocalDateTime, OHLC)` 對外型別保持不變（`StockAlertService` 不需動）；內部用 `IntradayBarDto(String time)` 解 JSON
- [x] 60d.4 編譯驗證（兩個 service 通過）
- [x] 60d.5 commit + 服務重啟驗證

### Task 60e: MarketDataService 配息率 / ETF / 股利歷史 / TWSE 假日 / 股票名稱搬到 external-materials-service

對應 Requirements: Requirement 7、Requirement 13（配息率）

#### 背景

`MarketDataService` 仍持有大量直接呼叫外部行情 API 的方法（TWSE OpenAPI / TWSE BWIBBU per-stock / FinMind dividend datasets / NASDAQ /quote/{code}/dividends / Yahoo quoteSummary + crumb 認證 / FinMind TaiwanETFHoldings），共約 1400 行。`HistoricalDataService.fetchTw/UsStockName` 同樣直連 FinMind / Yahoo。需全部搬到 ext-materials-service。

#### Steps:

- [x] 60e.1 ext-materials-service 新增 `MarketDataFetchService`：含殖利率級聯（TWSE OpenAPI / TWSE BWIBBU 3Y / FinMind dataset / NASDAQ / Known US ETF）、ETF 持股（Yahoo topHoldings + 台股 FinMind fallback）、股利歷史（FinMind / NASDAQ）、TWSE 假日表、股票名稱（FinMind / Yahoo via curl）、Yahoo crumb 認證
- [x] 60e.2 `StockSourceQuery` 新增 `findRecentClose(code, market)` 提供殖利率分母 fallback
- [x] 60e.3 `InternalPriceController` 加 `/internal/dividend-rate`、`/internal/etf-holdings`、`/internal/dividend-history`、`/internal/tw-holidays`、`/internal/stock-name`
- [x] 60e.4 business-services `MarketDataService`：刪除 ~1300 行 HTTP 邏輯；保留公開 record 類型 + 1 小時 dividend rate 快取 + per-year holiday 快取 + NYSE 假日純計算 + ETF 白名單；其餘全改 WebClient proxy
- [x] 60e.5 business-services `HistoricalDataService.fetchTwStockName/fetchUsStockName` 改 proxy 至 `/internal/stock-name`；移除 `curlGetWithRetry` / `httpGet` / 不再用的 imports（JsonNode / ObjectMapper / HttpClient / 等）
- [x] 60e.6 編譯驗證（business-services + ext-materials-service 皆通過）
- [x] 60e.7 commit + 服務重啟驗證（前端配息率 / ETF 持股 / 假日 / 股票名稱查詢仍正常）

### Task 60f: MacroHistoryService IMF / TWSE FMTQIK 搬到 external-materials-service

對應 Requirements: Requirement 7（外部行情 API 集中）

#### 背景

`MacroHistoryService` 直接呼叫 IMF DataMapper（curl shell-out 避 Akamai WAF）與 TWSE FMTQIK 月報。為集中外部抓取於 ext-materials-service，搬遷其 HTTP 部分；JPA 寫入留在 business-services（涉及 4 個 Repo / Entity，DB 邏輯複雜，proxy 後再寫 JPA 較簡潔）。

#### Steps:

- [x] 60f.1 ext-materials-service 新增 `MacroDataFetchClient`：`fetchImf(indicator, country, scale)`、`fetchTwseDecemberClose(year)`、`fetchTwseMonthlyDaily(year, month)` 回傳 `DailyClose(date, close)`
- [x] 60f.2 `InternalPriceController` 加 `/internal/macro/imf`、`/internal/macro/twse-year-end`、`/internal/macro/twse-monthly`
- [x] 60f.3 business-services `MacroHistoryService`：HTTP / curl 全部刪除；refresh* 方法保留 `@Transactional` + JPA 寫入；新增 `fetchImfProxy` / `fetchTwseDecemberCloseProxy` / `fetchTwseMonthlyDailyProxy` 走 WebClient
- [x] 60f.4 全 codebase 確認 backend / bff 無 twse / nasdaq / finmind / yahoo / bot / imf 等對外行情 URL（v1.21.0 changelog 註解一行除外）
- [x] 60f.5 編譯驗證
- [x] 60f.6 commit + 服務重啟驗證

### Task 60a: 移除 business-services 重複的每日股價收盤排程

對應 Requirements: Requirement 7（即時股價/快取）— [requirements.md:108-109](spec/requirements.md)

#### 背景

`HistoricalDataService` 留有兩支 `@Scheduled` — `dailyTwStockUpdate`（14:00 TW，FinMind）與 `dailyUsStockUpdate`（06:00 TW，Yahoo）— 在收盤後重新抓當日收盤寫入 `stock_price_history`。但 external-materials-service 已經有 `ClosePersister` 完整負責這件事（台股 13:32 Redis dump + 16:00 FinMind 校驗；美股 16:02 ET Redis dump + 18:00 ET FinMind 校驗）。business-services 這兩支不只重複還直接違反「不再呼叫外部行情 API」的規定。

`HistoricalDataService` 其餘責任（10 年回補 / FX 排程 / 5 分鐘 K 線等）後續再分階段移轉，暫時保留。

#### Steps:

- [x] 60a.1 刪除 `HistoricalDataService.dailyTwStockUpdate()` 與 `dailyUsStockUpdate()` 兩支 `@Scheduled`，並在原位置加註解說明已交給 ClosePersister
- [x] 60a.2 編譯驗證（`mvn -q -DskipTests compile` 通過）
- [x] 60a.3 commit + 服務重啟驗證（觀察台股 13:32–16:00 / 美股 16:02–18:00 ClosePersister 寫入 `stock_price_history`，business-services 不再有 14:00 / 06:00 抓價 log）

### Task 60: 修復 SSE 推送讓「股價」欄滲入非基準日的 live 價（Task 29 回歸）

對應 Requirements: Requirement 9（[requirements.md:144-147](spec/requirements.md)）

#### 背景

Task 59 移除 `shouldApplyLive` 的「市場開盤」閘門時，`getRealtimePrice(row)` 的「基準日 = 該市場當地今日」閘門（原 Task 29.2）一併被拆掉，只剩 `priceChange != null` 判斷。Task 51 後又改用 SSE (`/api/market-data/prices/stream`) 推播 live 價，連線是無條件開的；外部行情服務開盤期間推送的 `price-update` 一定帶 `priceChange`，會直接覆寫 `stockPrices.value[key]`。

結果：使用者切到非今日的歷史快照時，「股價」欄初次載入正確顯示基準日收盤價，但只要該市場開盤、SSE 一推送，股價就跳成今日 live 價並出現 ▲▼ 漲跌%——和 Requirement 9「其他情形：顯示快照保存的收盤價，不顯示漲跌%、不參與輪詢更新」直接違背。台股、美股都中。同列其他欄位（現值/損益/預估配息）因走 `overlayLivePrice` → `shouldApplyLive`，凍結正確；只有股價欄會跳，視覺上一列基準不一致。

#### Steps:

- [x] 60.1 `DashboardView.vue` `getRealtimePrice(row)` 開頭加 `if (!shouldApplyLive(row.market)) return null`，與 `overlayLivePrice` 共用同一個 per-market 基準日閘門
- [x] 60.2 commit + 服務重啟驗證（切到歷史快照、台股盤中重整，股價欄應穩定顯示快照 stockPrice、無漲跌%、不被 SSE 覆蓋）

### Task 61: 觀察清單由 stock_alert 衍生（廢止 watch_stock 表 + 大盤 0000 KD）

對應 Requirements: Requirement 14（觀察股票清單）、Requirement 16（到價警示）、Requirement 18（台股大盤日線）

#### 背景

目前 `watch_stock` 表與 `stock_alert` 表獨立並存，違反「相同的資料只能存一份」正規化原則：使用者必須同時維護兩份名單，常出現「警示條件設了 0050、但觀察清單忘了加」的不一致。重構為「觀察清單 = `stock_alert` 群組去重衍生」單一資料源。

同時 `twse_index_daily_history` 目前只存 `close_point`，使得觀察清單的 0000（台股大盤）無法計算 KD（需要日內 high/low）。TWSE FMTQIK 月報其實同時提供 `OpeningIndex` / `HighestIndex` / `LowestIndex`，本任務一起補完 OHLC，讓 0000 與一般股票走完全相同的技術指標路徑。

#### Steps:

**A. Schema 變更**

- [x] 61.1 Liquibase changelog `v1.x.x-twse-index-daily-ohlc.sql`：`twse_index_daily_history` 加 `open_point` / `high_point` / `low_point`（皆 NUMERIC(12,2), nullable，舊資料未抓 OHLC 維持 null，由下次 refresh 補完）
- [x] 61.2 Liquibase changelog `v1.x.x-drop-watch-stock.sql`：`DROP TABLE watch_stock`（資料完全由 stock_alert 衍生，無需資料移轉；既有觀察清單若有純觀察、無 alert 的股票，必須在 release notes 中提示使用者重新建立 alert，否則這些股票不再出現於觀察清單）

**B. ext-materials-service**

- [x] 61.3 `MacroDataFetchClient.fetchTwseMonthlyDaily` 回傳結構從 `DailyClose(date, close)` 擴為 `DailyOhlc(date, open, high, low, close)`；對應 internal endpoint 的 JSON
- [x] 61.4 `StockSourceQuery.upsertTwseIndexDaily` 簽名擴 OHLC 四欄

**C. business-services Backend**

- [x] 61.5 `TwseIndexDailyHistory` entity 加 `openPoint` / `highPoint` / `lowPoint` 欄位
- [x] 61.6 `MacroHistoryService.refreshTwseDaily` 解析 FMTQIK `OpeningIndex` / `HighestIndex` / `LowestIndex` / `ClosingIndex` 同步 upsert
- [x] 61.7 `TechnicalIndicatorService` 對 `code=0000 & market=台股` 改讀 `twse_index_daily_history` 計算 MA20 / MA60 / MA240 / KD（與一般股票同算法，high/low 來自 OHLC 欄位）
- [x] 61.8 刪除 `WatchStock` entity / `WatchStockRepository` / `WatchStockController` / `WatchStockService`（service 邏輯移至 BFF/衍生 view）
- [x] 61.9 `StockAlertRepository` 新增：
        - `findDistinctStockCodeMarket()`：回傳所有 (stockCode, market) 去重對 + 該對最小 displayOrder
        - `findByStockCodeAndMarket(code, market)`：取該股票全部 alert（用於拖曳重排與級聯刪除）
- [x] 61.10 `StockAlertService.create` 對 `0000 & 台股` 跳過 `stockMasterRepo.upsert`（避免被排程當真股票抓價）；`lookupName` 對 `0000` 短路回 `台股大盤`
- [x] 61.11 新增 `WatchListService`（取代舊 `WatchStockService`）：
        - `findAll()`：呼叫 `StockAlertRepository.findDistinctStockCodeMarket()` → 對每筆組裝 live 報價（`PriceQueryService`，0000 走 `twse_index_daily_history`）+ 技術指標 + 該股票最近觸發資訊
        - `delete(stockCode, market)`：刪除該 (code, market) 所有 alert（FK 級聯 trigger 歷史）
        - `reorder(orderedKeys)`：依 orderedKeys 順序，把每個股票所有 alert 的 `displayOrder` 整組區段重排（保持條件之間的相對順序）
- [x] 61.12 `WatchStockController` 改為 `WatchListController`，路徑 `/api/watch-list`：`GET`、`DELETE /{stockCode}/{market}`、`PUT /order`（接 `[{stockCode, market}]` 陣列）；不再有 POST（建立由 `/api/stock-alerts` 接手）

**D. BFF**

- [x] 61.13 `WatchStockBffController` 改名 `WatchListBffController`：rewrite `/api/bff/watch-stock/**` → `/api/watch-list/**`（路徑可保留 `watch-stock` 不動以維持前端 URL 穩定，僅內部 rewrite 目標改變）

**E. Frontend**

- [x] 61.14 `WatchStockView.vue` 新增按鈕改為直接開啟「警示條件」新增 dialog（reuse `StockAlertView` 既有 dialog component）；建立成功後回到觀察清單自動 reload
- [x] 61.15 `WatchStockView.vue` 刪除按鈕的二次確認文字改為「將同時刪除 N 筆警示條件，確定？」N 從 BFF 回傳資料計算
- [x] 61.16 `WatchStockView.vue` 拖曳排序 callback 改呼叫 `PUT /api/bff/watch-stock/order`（payload 為 `[{stockCode, market}]` 陣列），不再傳 watch_stock id

**F. 編譯與驗證**

- [x] 61.17 編譯驗證（business-services + bff + ext-materials-service）
- [x] 61.18 commit + 服務重啟驗證：
        - 新增警示條件 → 觀察清單自動出現該股票
        - 同股票多筆警示 → 觀察清單只一列
        - 在「警示條件」頁刪除某股票全部 alert → 觀察清單該列消失（觀察清單頁本身無刪除列入口）
        - 觀察清單拖曳 → 警示條件頁的該股票條件群組整體位置改變、群組內順序不變
        - 0000 加入觀察 → 季線/年線/KD 皆有值（不再是 dash）
        - 0000 可設 KD 警示且觸發後 lastTriggered* 顯示

### Task 62: 觀察清單欄位調整（移除買進/賣出、新增警示條件欄）

對應 Requirements: Requirement 14（觀察股票清單）

#### 背景

觀察清單目前最近一次觸發只有一條訊息，看不出該股票實際設了哪些條件。把「買進／賣出」兩欄拿掉（資訊量低且台股大盤、ETF 多半為 null），改加一欄「警示條件」列出該股票所有條件 label，方便快速確認設了什麼。

#### Steps:

- [x] 62.1 `StockAlertService.buildLabel` 提升為 `public static`（或抽到 util），讓 `WatchStockService` 共用同一份 label 文案
- [x] 62.2 `WatchStockDto.Response` 加 `conditions: List<{label, active}>`（依 displayOrder 升冪）
- [x] 62.3 `WatchStockService.toResponse` / `toIndexResponse` 填入 conditions（`alertRepo.findByStockCodeAndMarket` 排序後 map 成 `{label, active}`）
- [x] 62.4 前端 `WatchStockView.vue` 移除買進、賣出兩個 column；在「警示」欄前面加「警示條件」欄，每條換行顯示，停用條件淺色 + 「(停用)」
- [x] 62.5 commit + 服務重啟驗證
- [x] 62.6 已觸發條件以紅字顯示：`Condition` DTO 加 `triggered` 欄位，由 `WatchStockService` 比對 alert.lastTriggeredAt 與最近 3 個交易日 cutoff（與「警示」欄共用同一份 cutoff）填入；前端依此 flag 套紅色字

### Task 63: 保留設定儲存時立即套用 retention

對應 Requirements: Requirement 15（資料庫備份/還原）

#### 背景

原本 `updateSetting()` 只把新的保留代數寫進 `backup_setting`，輪替只在下一次排程／手動備份完成時才會跑。若使用者把 `dailyRetention` 從 50 下調到 20，daily 資料夾現有 > 20 份檔案要等到下一次台股交易日 15:30 或美股 07:00 排程跑完才會被清掉，不符合使用者「我改設定就是要立刻只留 N 份」的直覺。

#### Steps:

- [x] 63.1 `BackupService.updateSetting()` 在 `settingRepo.save(s)` 之後依新的 retention 值對三個資料夾各跑一次 `rotateFolder`：
        - `rotateFolder("manual", MANUAL_PREFIX, manual)`
        - `rotateFolder("daily", "asset_daily_", daily)`
        - `rotateFolder("weekly", WEEKLY_PREFIX, weekly)`
        每支獨立 try/catch RuntimeException 包住，失敗只 `log.warn`，不讓 PUT `/api/backups/settings` 整支 fail（設定值仍要存進去）
- [x] 63.2 重啟後端，於前端「保留設定」把 `dailyRetention` 從原值下調到一個小於目前 daily 檔案數的值並儲存，確認：
        - PUT 200 OK、UI 顯示「儲存成功」
        - Google Drive `daily/` 內 `asset_daily_*` 只剩新上限份數（最舊的被刪）
        - `backup_record` 表對應 daily folder 的 row 也同步減少
        - manual / weekly 同理

### Task 64: 美股觀察清單「開盤」欄補上今日 open（NASDAQ historical endpoint）

對應 Requirements: Requirement 14（觀察股票清單 — 美股 openPrice 來源）

#### 背景

NASDAQ `/info` endpoint 自 2026/04 起不再回傳 `OpenPrice`，`PriceFetchClient.getNasdaqPrice` 把 `openPrice` 寫死為 null，導致觀察清單頁面美股（VOO/QQQ/VT 等）「開盤」欄一律顯示「—」。下游 `WatchStockService` 雖有 `stock_price_history` fallback，但只會撈到昨日 open，且若無歷史紀錄仍是 null。改打 NASDAQ `/historical` endpoint 補今日真實 open。

#### Steps:

- [x] 64.1 `PriceFetchClient` 新增 `getNasdaqOpenPrice(stockCode, assetClass)`：
        - URL `https://api.nasdaq.com/api/quote/{code}/historical?assetclass={class}&fromdate={今日(ET)}&todate={今日(ET)}&limit=1`
        - 解析 `data.tradesTable.rows[0].open`（格式 `$XXX.XX`），用 `parseDollar` 轉 `BigDecimal`
        - 任何例外或無 row 回 `Optional.empty()`，僅以 `log.debug` 記錄，不噴 warn（盤前無資料屬正常）
- [x] 64.2 `PriceFetchClient.getNasdaqPrice` 把 `BigDecimal openPrice = null` 替換為 `BigDecimal openPrice = getNasdaqOpenPrice(stockCode, assetClass).orElse(null);`，原註解一併更新
- [x] 64.3 編譯驗證（`mvn -q -DskipTests compile`）
- [x] 64.4 服務重啟，於美股盤中（NYSE 09:30–16:00 ET）開「股票觀察 → 美股」頁，VOO / QQQ / VT 「開盤」欄應顯示今日真實開盤價（非昨日、非「—」）；盤前則 fallback 為 `stock_price_history` 最近一筆 open（與台股相同行為）

### Task 65: 代繳帳戶記錄管理（Requirement 22）

對應 Requirements: Requirement 22

#### 背景

使用者長期以 Google Sheet 維護「分類 / 項目 / 帳戶 / 備註」的代繳對照表（範例如：市話 + MOD → momo 信用卡 → 用戶號碼 Y046509）。需在系統內提供獨立「自動代繳」頁面取代手工表格，分類採 DB 維護（不寫死 Enum），帳戶採純字串（不與 `bank` / `broker` 建立 FK），不影響任何資產計算。

#### Steps:

- [x] 65.1 Liquibase changeset `v1.23.0-payment-account.sql` 建立兩張表：
        - `payment_category(id BIGINT IDENTITY PK, code VARCHAR(30) UNIQUE NOT NULL, display_name VARCHAR(50) NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE)`
        - `payment_account(id BIGINT IDENTITY PK, category_id BIGINT NOT NULL REFERENCES payment_category(id), item_name VARCHAR(100) NOT NULL, payment_account VARCHAR(100), note VARCHAR(255), sort_order INTEGER NOT NULL DEFAULT 0)`
        - 加 `INDEX idx_payment_account_category ON payment_account(category_id)`
- [x] 65.2 在 `db.changelog-master.yaml` 末尾 `include` 該 changeset
- [x] 65.3 新增 Entity：`PaymentCategory`（仿 `DepositTypeEntity` 樣式）、`PaymentAccount`（含 `@ManyToOne PaymentCategory category`）
- [x] 65.4 新增 Repository：`PaymentCategoryRepository`（`findByCode`、`findAllByOrderBySortOrderAscDisplayNameAsc`、`findByActiveTrueOrderBySortOrderAscDisplayNameAsc`）、`PaymentAccountRepository`（`findAllByOrderByCategorySortOrderAscSortOrderAscIdAsc` 或 service 端排序）
- [x] 65.5 新增 DTO `PaymentDto`：`CategoryResponse` / `CreateCategoryRequest` / `UpdateCategoryRequest` / `AccountResponse`（含 categoryId + categoryDisplayName） / `CreateAccountRequest`（含 categoryId） / `UpdateAccountRequest`
- [x] 65.6 在 `InstitutionService` 加入 `PaymentCategory` 區段（5 個方法：getAll/getActive/create/update/setActive），與 Bank/Broker 同檔；另新增 `PaymentAccountService`（getAll/create/update/delete）
- [x] 65.7 在 `InstitutionController` 加入 `/api/settings/payment-categories` 路由（GET/POST/PUT/PATCH active）；新增 `PaymentAccountController` 提供 `/api/payment-accounts` GET/POST/PUT/DELETE
- [x] 65.8 `DataInitializer.seedPaymentCategories()` seed 三筆預設分類：`bill / 繳費 / 1`、`tax / 繳稅 / 2`、`service / 服務 / 3`（findByCode 檢查避免重複）；代繳記錄本身不 seed
- [x] 65.9 新增 BFF `PaymentAccountSettingsBffRoutes`（路徑 `/api/bff/payment-account-settings/categories/**` rewrite 至 `/api/settings/payment-categories/**`；`/api/bff/payment-account-settings/accounts/**` rewrite 至 `/api/payment-accounts/**`）
- [x] 65.10 前端 `frontend/src/api/index.js` 新增 `bffApi.paymentAccountSettings`：`getCategories` / `createCategory` / `updateCategory` / `setCategoryActive` / `getAccounts` / `createAccount` / `updateAccount` / `deleteAccount`
- [x] 65.11 前端 `views/PaymentAccountSettingsView.vue`：上半段「分類維護」（小表格 + 新增/編輯/啟用-停用 dialog）、下半段「代繳記錄」主表格（欄位：分類 tag、項目、帳戶、備註、操作（編輯/刪除））；新增 dialog 含分類下拉（僅啟用中）、項目、帳戶、備註、排序
- [x] 65.12 `frontend/src/router/index.js` 新增 route `/settings/payment-accounts` → `PaymentAccountSettings`；`App.vue` 系統設定 submenu 加一個 `el-menu-item index="/settings/payment-accounts"`，icon `Document` 或 `Tickets`
- [x] 65.13 編譯驗證（`mvn -q -DskipTests compile` 對 backend 與 bff 兩個 module）
- [x] 65.14 服務重啟，前端進入主選單「自動代繳」：
        - 預設出現三個分類；新增一筆「市話 + MOD / momo 信用卡 / 2626-2305 (用戶號碼: Y046509)」於「繳費」分類
        - 編輯、刪除、停用分類、按分類過濾皆正常
- [x] 65.15 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 66: SnapshotForm 台股 broker 列修改股數時自動重算持股成本

對應 Requirements: Requirement 1（管理資產 — 持股成本與均價連動）

#### 背景

`SnapshotFormView.vue` 台股 broker row 的「股數」欄 `@blur` 只更新 `br.shares`，未重算 `br.investmentCost`，
導致使用者把股數改小（例如賣出後從 1478 改成 1000）後，持股成本仍維持原本 54,996.38（=1478 × 37.21），
與「股數 × 買入均價」不符。美股欄位本已具備此連動（line 714–723），且 `SnapshotDetailView.syncFromShares`
也採同策略；只是台股欄位漏寫。

#### Steps:

- [x] 66.1 `frontend/src/views/SnapshotFormView.vue` 台股 broker 列「股數」`@blur` 加上：
        `br.investmentCost = numParse(((br.avgCost||0) * (br.shares||0)).toFixed(2), 2); br.investmentCostStr = numFmt(br.investmentCost)`
        保持均價不變、以「均價 × 新股數」重算總成本（與美股欄位、`SnapshotDetailView.syncFromShares` 一致）
- [x] 66.2 服務重啟，於 SnapshotForm 編輯既有台股 broker row（例如 元大證券 1478→1000 股），確認「持股成本」自動更新為 37,210；「均價」維持 37.21；「現值／損益」隨之刷新
- [x] 66.3 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 67: SnapshotForm 編輯模式下使用者改動後 KPI 改用 live 計算

對應 Requirements: Requirement 1（管理資產 — 上方 KPI 即時反映表單編輯）

#### 背景

`SnapshotFormView.vue` 的 `pickStored()`（line 1611–1635）在編輯模式下一律回傳 BFF 的 stored 值
（`totalDeposit` / `totalFundValue` / `totalFundCost`），目的是避免歷史快照 `usdExchangeRate=null`
時 live 重算把 USD 存款再乘一次匯率。但副作用是：使用者在表單裡改動「在途款項 / 一般存款 / 基金」
後，上方 KPI（總資產 / 存款 / 共同基金現值）不會更新，直到存檔 + BFF 重抓才同步。

修法：載入完成（含 `loadExchangeRateForDate` / `onUsTransactionDateChange` / `fetchPriceForRow`
等程式化 mutation）後才掛 watcher；watcher 偵測到任何 user-driven 改動就把 `userEdited=true`，
`pickStored` 改回 live。如此 legacy USD 防呆只在「初始 render 且未編輯」生效，編輯後即時反映。

#### Steps:

- [x] 67.1 `SnapshotFormView.vue` 新增 `userEdited` ref；`pickStored` 改為 `!isEdit.value || userEdited.value → live`
- [x] 67.2 `onMounted` 末段（所有 sortable refresh 之後）`await nextTick()` 後註冊
        `watch(() => [form.deposits, form.funds, form.stocks, form.usdExchangeRate], ..., { deep: true })`
        將 `userEdited` 設為 true
- [x] 67.3 服務重啟，於編輯既有 snapshot：
        - 改在途款項任一筆金額 → blur → 上方 `總資產` / `存款` 即時更新（含 transitNetTwd）
        - 改基金 units / currentValue → 上方 `共同基金現值` 即時更新
        - 初始載入時 KPI 應仍等於 stored 值（沒有抖動）
- [x] 67.4 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 68: MarketDataService.getDividendRate 無配息資料時用 stock 主檔補 stockName

對應 Requirements: Requirement 1（管理資產 — 股票名稱自動帶出）/ Requirement 3（持股自動補名稱）

#### 背景

SnapshotForm `/api/bff/snapshot-form/prices` 取 stockName 只看：
(1) `/api/market-data/prices`（即時 cache）的 stockName
(2) `/api/market-data/dividend-rate` 回應的 stockName

對於沒有配息的標的（例如 SGOV iShares 0-3 Month Treasury Bond ETF）`dividend-rate` 回 `stockName=null`，
若 live cache 又尚未 warm-up（剛啟動或 ext-materials 暫時無回應），BFF 就會回 `stockName=null`，
前端股票名稱欄位留空。但 `stock` 主檔早已存過該名稱（透過先前的 upsert）。

修法：在 `MarketDataService.getDividendRate()` 取得 ext-materials 結果後，若 stockName 為空，
就從 `StockRepository.findByCodeAndMarket()` 補上。所有 caller（SnapshotForm BFF / Dashboard BFF /
StockAnalysis BFF / WatchStock 等）自動受惠，stockName 變成「殖利率來源 OR 主檔」雙重 fallback。

#### Steps:

- [x] 68.1 `MarketDataService` 注入 `StockRepository stockMasterRepo`（手動 constructor，因原有 `@Value`）
- [x] 68.2 `getDividendRate()` 在 result 形成後（含 ext-materials 失敗的 fallback path），
        如果 `result.stockName()` 為 null/blank，從 `stockMasterRepo.findByCodeAndMarket(code, market)`
        取 name，補回 DividendRateResult 再 cache
- [x] 68.3 編譯驗證（`mvn -q -DskipTests compile`）
- [x] 68.4 服務重啟後在 SnapshotForm 編輯模式打開含 SGOV 的快照：「股票名稱」欄位應立即顯示
        「iShares 0-3 Month Treasury Bond ETF」（即使 live cache 尚未 warm-up）
- [x] 68.5 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 69: 美股殖利率新增 Yahoo Finance fallback（覆蓋 NYSE / NYSEARCA）

對應 Requirements: Requirement 5（殖利率 / 預估配息 — 美股 ETF 非 NASDAQ 也要可查）

#### 背景

`MarketDataFetchService.getDividendRate("美股", ...)` 目前只用：
1. NASDAQ `/api/quote/{code}/dividends`：對非 NASDAQ 上市（NYSE / NYSEARCA）一律回
   `"Dividend History for Non-Nasdaq symbols is not available"`，所有欄位 N/A
2. 寫死 `KNOWN_US_ETF_YIELDS` map（VOO/VT/VTI/VXUS/BND/QQQ 六檔）

導致 SGOV（NYSEARCA，iShares 0-3 Month Treasury Bond ETF，月配息 TTM ~4%）、BIL、SCHD、JEPI、JEPQ、TLT
等都查不到殖利率，UI 顯示「查無配息資料 / 預估配息 = -」。

修法：在 NASDAQ 與 KNOWN_US_ETF_YIELDS 之間插入 Yahoo Finance fallback。Yahoo
`/v8/finance/chart/{code}?range=1y&events=div` 不需 crumb、且涵蓋所有 US-listed。
TTM 殖利率 = sum(最近 1 年 dividends) ÷ regularMarketPrice。走 curl 子程序避免
fingerprint 偵測（與既有 `fetchUsStockName` 同一模式）。

#### Steps:

- [x] 69.1 `MarketDataFetchService` 新增 `getYahooDividendRate(stockCode)`：curl 取 Yahoo chart
        events=div，累加 amount ÷ regularMarketPrice 得 TTM 殖利率，組成 `DividendRateResult`
        （source="Yahoo Finance"，description 含「TTM N 筆配息合計 $X.XX，殖利率 X.XX%」）
- [x] 69.2 `getDividendRate("美股", ...)` 鏈調整為 NASDAQ → Yahoo → KNOWN_US_ETF_YIELDS → N/A
- [x] 69.3 編譯驗證（`mvn -q -DskipTests compile`）
- [x] 69.4 重啟 external-materials-service + business-services（清掉 1 小時 dividend cache），
        於 SnapshotForm 編輯模式含 SGOV 的快照：「預估配息」應出現 ~4% 的數字；
        SGOV / BIL / SCHD / JEPI / JEPQ / TLT 也都應有殖利率
- [x] 69.5 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 70: Backup rotation 改以 DB `backup_record` 為單一事實來源

對應 Requirements: Requirement 19（備份保留代數一致性）

#### 背景

`BackupService.rotateFolder(folder, prefix, retention)` 原本透過 `rclone lsjson` 列出 Google Drive
資料夾下 `startsWith(prefix)` 的檔案，挑出最舊的刪掉。問題：歷史檔名前綴有更動（早期 `asset_*`，
現在 `asset_weekly_*`），前綴過濾會放過 legacy 檔，導致 weekly 設定 retention=3 但資料夾實際有 4 份。

修法：rotation 改為 DB-driven。`backup_record` 表已記錄每筆備份的 folder 與 `auto_pre_restore` flag，
是穩定的事實來源。新版 `rotateFolder(folder, retention)`：
1. `findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(folder)` 取出該資料夾下所有非自救點記錄
2. 保留前 retention 筆，其餘對 rclone + DB 兩邊一併刪除
3. rclone 刪除失敗（檔案已不存在或網路異常）只 log warn，DB 記錄仍刪掉避免下次重複嘗試

副作用 / trade-off：
- 自救點（`auto_pre_restore=true`）永遠不輪替（仍維持原設計）
- rclone 上有但 DB 沒記錄的檔案不會被刪（DB 沒記錄就不動）—— 較安全，誤刪風險低
- 廢除 prefix 過濾參數，所有 caller 簡化為 `rotateFolder(folder, retention)`

#### Steps:

- [x] 70.1 `BackupRecordRepository` 新增 `findByFolderAndAutoPreRestoreFalseOrderByModifiedAtDesc(String folder)`
- [x] 70.2 `BackupService.rotateFolder` 改為 DB-driven：取 DB 記錄、按 modifiedAt 排序、保留 retention 筆、
        其餘 rclone delete + DB delete；rclone 刪除失敗只 warn log
- [x] 70.3 全部 caller 改為新簽名（`updateSetting` 內 3 個 rotateQuietly、`runBackup` / `daily` x2 / `weekly` 各 1 個 rotateFolder）
- [x] 70.4 編譯驗證（`mvn -q -DskipTests compile`）
- [x] 70.5 服務重啟，至「保留設定」把 weeklyRetention 從 3 暫調為 2 → 儲存 → 應立即刪除最舊一份；
        再調回 3 不會自動新增（因新檔由排程產生）
- [x] 70.6 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 71: BackupRestoreView 儲存保留設定後 reload 備份列表

對應 Requirements: Requirement 19（備份保留代數一致性 — UI 即時反映）

#### 背景

後端 `BackupService.updateSetting()` 儲存設定時會立即跑 rotate 三個資料夾（Task 63 + Task 70），
但前端 `BackupRestoreView.saveSettings()` 只更新 `settings`，沒重新撈備份列表，使用者看不到
被輪替掉的舊備份消失，必須手動 reload 或按「同步」。

#### Steps:

- [x] 71.1 `BackupRestoreView.vue` `saveSettings()` 成功後 `await loadList()` 重新撈備份列表
- [x] 71.2 服務重啟，把 weeklyRetention 暫調為 2 → 儲存 → 列表應立即少一筆（不需手動 reload）
- [x] 71.3 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 72: 均線警示通用化（新增月線 MA20、改為 `MA_*_PCT` + `ma_period`）

對應 Requirements: Requirement 16（到價警示）

#### 背景

原本 `stock_alert.alertType` 把每個均線寫成獨立字串：`QUARTERLY_MA_ABOVE_PCT` / `QUARTERLY_MA_BELOW_PCT` /
`ANNUAL_MA_ABOVE_PCT` / `ANNUAL_MA_BELOW_PCT`，每加一條均線就要在 enum-like 字串、`evaluate` switch、
`buildLabel`、`matchInIntradayBars`、`matchInDailyOhlc`、`pickMaForAlert`、前端 `parseAlertType` / `buildAlertType`
七個地方各加一組分支，違反 OCP。

改法：把「均線天數」從 alertType 字串拆出來變成獨立欄位 `ma_period` (INT)，alertType 統一為
`MA_ABOVE_PCT` / `MA_BELOW_PCT`，前端下拉只增加 `maPeriod` 選項即可。本次同時新增「月線（MA20）」選項。

#### Steps:

- [x] 72.1 Liquibase `v1.24.0-stock-alert-ma-period.sql`：
  - `ALTER TABLE stock_alert ADD COLUMN ma_period INTEGER`
  - `UPDATE stock_alert SET ma_period = 60, alert_type = REPLACE(alert_type, 'QUARTERLY_MA_', 'MA_') WHERE alert_type LIKE 'QUARTERLY_MA_%'`
  - `UPDATE stock_alert SET ma_period = 240, alert_type = REPLACE(alert_type, 'ANNUAL_MA_', 'MA_') WHERE alert_type LIKE 'ANNUAL_MA_%'`
- [x] 72.2 `StockAlert` model 新增 `maPeriod` 欄位（Integer，nullable）；`StockAlertDto.Request/Response` 同步
- [x] 72.3 `StockAlertService.evaluate` switch 改為通用 `MA_ABOVE_PCT` / `MA_BELOW_PCT` 走 `checkMaDeviation(alert, currentPrice, alert.getMaPeriod(), above)`，刪除 `QUARTERLY_MA_*` / `ANNUAL_MA_*` 分支
- [x] 72.4 `StockAlertService.pickMaForAlert` 改為依 `maPeriod` 從 `FullIndicators` 取對應 MA（20→monthlyMa、60→quarterlyMa、240→annualMa）；`buildLabel` 用 `maPeriod` 動態組「高於月線/季線/年線 X%」；`matchInIntradayBars` / `matchInDailyOhlc` 內 MA 分支用 `alert.getMaPeriod()` 取代寫死的 60/240
- [x] 72.5 前端 `StockAlertView.vue`：下拉條件類型新增「月線偏離（20 日均線）」；form 新增 `maPeriod` 欄位；`buildAlertType` 對 MA 一律回 `MA_${dir}_PCT`，payload 帶 `maPeriod`；`parseAlertType` 依 alertType + maPeriod 還原 conditionGroup
- [x] 72.6 編譯與重啟驗證（`mvn -q -DskipTests compile`、啟動 backend / frontend）
- [x] 72.7 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 73: 觀察清單拿掉「操作」欄（移除觀察改走警示條件頁）

對應 Requirements: Requirement 14（觀察股票清單 — 由警示條件衍生）

#### 背景

觀察清單本來就是 `stock_alert` 群組去重的衍生 view，列上的「操作」欄（紅色刪除鈕）會
連帶把該股票所有 alert 級聯刪除，等同在兩個頁面提供「等價但措辭不同」的刪除入口，
容易誤觸（使用者以為只是把列從觀察清單拿掉，實際上把多筆警示一起刪了）。
正確操作模式是：要把股票從觀察清單移除，就去「警示條件」頁逐筆刪掉該股票的 alert，
最後一筆刪掉時觀察清單該列自然消失，與「新增觀察 = 新增第一筆警示條件」對稱。

#### Steps:

- [x] 73.1 `WatchStockView.vue` 移除「操作」`el-table-column`、`remove()` 函式、`Delete` / `ElMessageBox` import
- [x] 73.2 `frontend/src/api/index.js` 移除 `watchStock.delete`
- [x] 73.3 `WatchStockController` 移除 `@DeleteMapping("/{stockCode}/{market}")`；`WatchStockService.delete()` 連同 `StockAlertRepository.deleteByStockCodeAndMarket()`（已無 caller）一併刪除
- [x] 73.4 spec：`requirements.md` Requirement 14 將「刪除觀察 = 刪除該股票所有 alert」改為「移除觀察的入口 = 警示條件頁刪除」；`design.md` `WatchStockBffController` / `WatchStockService` / Watch Stocks API 段落同步移除 DELETE 端點；`tasks.md` 61.18 驗證項調整
- [x] 73.5 服務重啟驗證：觀察清單列無「操作」欄；在警示條件頁刪除某股票最後一筆 alert 後，觀察清單該列消失；拖曳排序仍可運作
- [x] 73.6 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 74: 新增警示 dialog 支援「股名 → 代號」反向自動帶入

對應 Requirements: Requirement 16（到價警示 — 新增表單 UX）

#### 背景

`StockAlertView` 的新增警示 dialog 目前只支援「打代號→自動帶名稱」（`lookup-name` blur），
但很多使用者習慣打名字而非記得每檔代號（特別是台股金控股、ETF）。打入「富邦金」應該自動填入
「2881」，與正向流程對稱。

採「只查本地 stock 主檔（精確匹配）」策略：FinMind / Yahoo 原本就是 code → name 設計，反向
查 API 不可靠。本地 stock 主檔涵蓋所有曾經持有 / 觀察 / 警示過的股票，命中率對主用例足夠；
查不到時提示使用者改輸入代號。「台股大盤」+ 台股 → 0000 列為特例（與 `lookup-name` 對稱）。

#### Steps:

- [x] 74.1 `StockRepository` 新增 `findFirstByNameAndMarketOrderByCodeAsc(name, market)`（極端撞名取 code 升冪第一筆）
- [x] 74.2 `StockAlertController` 新增 `@GetMapping("/lookup-code")`：`{name, market}` → `{stockCode}`；空白 / 查無回空字串，`「台股大盤」+ 台股 → 0000` 特例。不打外部 API
- [x] 74.3 `frontend/src/api/index.js` `stockAlert.lookupCode = (params) => api.get('/bff/stock-alert/lookup-code', { params })`（沿用 stock-alert wildcard BFF route，無需 BFF 新增配置）
- [x] 74.4 `StockAlertView.vue` 股名 `el-input` 加 `@blur="fetchStockCode"` 與 loading suffix-icon；新增 `fetchStockCode()`：只在 `stockCode` 為空且 `stockName` 有值時觸發，避免覆蓋已填代號；查無時 `ElMessage.warning('本地查無此股名，請改輸入股票代號')`
- [x] 74.5 spec：`requirements.md` Requirement 16 增加 `lookup-code` AC；`design.md` `/api/stock-alerts/lookup-code` 列入端點列表
- [x] 74.6 服務重啟驗證：富邦金（已存在於主檔者）打入名字 → blur → 代號自動填入；隨意打不存在名字 → 顯示警告且不蓋掉代號；打「台股大盤」→ 帶入 0000
- [x] 74.7 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 75: `lookup-name` 對「美股 + 0000」加守門（防 Yahoo fuzzy match 污染主檔）

對應 Requirements: Requirement 16（到價警示 — lookup-name 守門）

#### 背景

`StockAlertController.lookupName` 原本只對「台股 + 0000」做特例攔截（回「台股大盤」），「美股 + 0000」會 fall through 到 `historicalDataService.fetchUsStockName("0000")`，Yahoo Finance 對非標準 ticker 會做 fuzzy match 回傳隨機公司（曾觀察到回 `Shenzhen 7Road Tech Co Ltd`），接著被自動 upsert 進 `stock` 主檔，污染後續 dropdown / 反查。實際 dev DB 已發現一筆此類髒資料並手動刪除（`DELETE FROM stock WHERE code='0000' AND market='美股'`）。

#### Steps:

- [x] 75.1 `StockAlertController.lookupName` 在台股守門之後加「美股 + 0000 → 回空字串」分支；comment 說明歷史 bug 與防護意圖
- [x] 75.2 spec：`requirements.md` Requirement 16 `lookup-name` AC 補上守門描述
- [x] 75.3 服務重啟驗證：警示新增表單市場選「美股」、代號打 `0000` → blur 不再自動帶入名稱（停留空白）；`stock` 主檔不再新增 `(0000, 美股)` 列
- [x] 75.4 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 76: 已實現損益明細列雙擊開啟股票分析圖

對應 Requirements: Requirement 13（股票走勢圖延伸資訊）

#### 背景

`StockAnalysisDialog` 原本已被 Dashboard / SnapshotForm / WatchStock / StockAlert 四個 view 共用，使用者習慣在持股／觀察列雙擊查 K 線與股利歷史。已實現損益（`RealizedGainView`）每筆都是具體股票成交，但原先不支援雙擊，使用者得切回觀察清單再開分析圖，多繞一步。

#### Steps:

- [x] 76.1 `RealizedGainView.vue` 在 `<el-table>` 加 `@row-dblclick="onRowDblClick"`，import `StockAnalysisDialog`，新增 `analysisVisible` / `analysisStock` state 與 `onRowDblClick(row)` 函式：以 `row.assetCode → stockCode`、`row.assetName → stockName`、`row.market` 構造 dialog 入參
- [x] 76.2 spec：`design.md` `StockAnalysisBffRoutes` 段落把 `RealizedGain` 加進使用此對話框的 view 清單（四 → 五）
- [x] 76.3 服務重啟驗證：在已實現損益列上雙擊 2330 / AVGO 等 → 跳出對應股票的走勢圖 popup，標題顯示「{code} {name}　股票分析」
- [x] 76.4 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 77: 存款新增「年利率／預估利息」欄位並併入預估年配息

對應 Requirements: Requirement 2（銀行存款追蹤）

#### 背景

`SnapshotFormView` 存款明細目前只記錄銀行、類型、金額、備註。使用者要求新增「年利率」（可輸入百分比）與「預估利息」（自動 = `amount × rate / 100`），且加總後併入快照層級的「預估年配息」與各頁面 KPI。`預估利息` 是 derived value（同列其他欄位即可算出），依正規化規則不存 DB；只新增 `bank_deposit.annual_interest_rate` 欄位儲存利率本身。

#### Steps:

- [x] 77.1 Liquibase changelog `v1.25.0-deposit-annual-interest-rate.sql`：`ALTER TABLE bank_deposit ADD COLUMN annual_interest_rate NUMERIC(7,4)`，並掛到 `db.changelog-master.yaml`
- [x] 77.2 `BankDeposit.java` 新增 `annualInterestRate` 欄位（`@Column(precision=7, scale=4)`，nullable）
- [x] 77.3 `AssetSnapshotDto.DepositRequest` / `DepositResponse` 加 `annualInterestRate` 欄位
- [x] 77.4 `AssetService.createSnapshot` / `updateSnapshot` 寫入 `annualInterestRate`；`toDetailResponse` 回傳；`recalcTotals` 在算 `estimatedAnnualDividend` 時加上 `Σ(amount × rate / 100)`
- [x] 77.5 `AssetService.autoEnrichDividendRates` / `enrichAllSnapshotDividendRates` / `recalcAllDividends` / `updateSnapshotDividendRates` 統一抽出 `sumDepositInterest(snapshot)` helper，重算 `estimatedAnnualDividend` 時一律包含
- [x] 77.6 `SnapshotFormView.vue`：台幣 + 美元 tab 各加「年利率」「預估利息」兩欄；`mapDepositFromApi` 映射 `annualInterestRate`；`addDeposit` 預設 `null`；submit payload 帶 `annualInterestRate`
- [x] 77.7 `SnapshotFormView.vue`：新增 `depositInterestTwd(d)` helper 與 `depositInterestTotal` computed；`summaryDividend` 加進去；台幣 tab 底部彙總列新增「預估年利息」一欄
- [x] 77.8 服務重啟驗證：在台幣存款列輸入 1.5（年利率），預估利息欄即時顯示金額；底部彙總「預估年利息」與頂部 KPI「預估年配息」皆變動；儲存後重新打開該快照，年利率值仍在
- [x] 77.9 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）
- [x] 77.10 `DashboardView.vue` 殖利率分母同步納入存款：`yieldBase = totalStockValue + totalFundValue + totalDeposit`（原本不含存款）。理由：分子 `estimatedAnnualDividend` 已含存款預估年利息，分母也須含存款本金口徑才一致

### Task 78: 警示存檔守門放寬：本地 stock 主檔也算合法 canonical

對應 Requirements: Requirement 14（觀察清單 / 警示）

#### 背景

`StockAlertService.assertNameMatchesCode` 直接打外部來源（ext-materials-service `/internal/stock-name` → Yahoo `shortName`）取得 canonical name 與使用者送來的 `stockName` 比對。但 lookupName 帶名是「本地 `stock` 主檔優先 → 外部 fallback」，當外部來源在不同時間回不同字串（例如美股 NVDA：Yahoo `shortName` 「NVIDIA Corporation」vs 主檔過去寫入的 「NVIDIA Corporation Common Stock」），UI 自動帶名後 save 時會被守門擋下，跳出「代號 NVDA 與股名「NVIDIA Corporation Common Stock」不符，外部來源為「NVIDIA Corporation」」400。既然 lookupName 帶出的值來自主檔，主檔本身應算合法 canonical。

#### Steps:

- [x] 78.1 `StockAlertService.assertNameMatchesCode`：取得外部 canonical 後若與 user-supplied `stockName` 不一致，再 fallback 對照 `stockMasterRepo.findByCodeAndMarket(code, market).name`；任一相符即通過，兩者皆不相符才 throw。錯誤訊息維持「代號 X 與股名「Y」不符，外部來源為「Z」」（仍以外部 canonical 為錯誤訊息中的對照值，避免訊息誤導）
- [x] 78.2 spec：`requirements.md` 與 `design.md` 對應段落已同步更新（本 task 同 commit）
- [x] 78.3 服務重啟驗證：以原案例重現 — 美股 NVDA + lookupName 自動帶名 → 新增「月線偏離 5%」警示能順利存檔
- [x] 78.4 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 79: 修復盤中股價跳回昨收（TWSE `z='-'` 改採 skip write）

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

Dashboard「持股明細 (現值)」bar chart、KPI「股票現值」、即時資產估算等盤中即時數值，最終都源自 Redis `price:台股:{code}` JSON 的 `price` 欄。`PriceFetchClient.getTwseRealTimePrice` 在 TWSE mis API 回 `z='-'`（這一輪 polling 撞到「兩 tick 之間沒有新成交」的 5 秒視窗）時，**會把 `y`（昨日收盤）當作現價寫進 Redis**，source 標記 `TWSE(前收)`，changePct 強制 0。Task 58.8 把此設計寫進規格（"唯一合理 fallback 是退回前收"），但實務上 TWSE mis 的 `z='-'` 並不代表「整天沒成交」— 同一檔今日通常仍在持續成交（`h`/`l`/`v`/`o`/買賣盤深度都有值），只是這 5 秒視窗剛好沒撮合。盤中每 2 分鐘 polling 命中機率很高，現場 23 檔台股中曾觀察到 11 檔 source 同時為 `TWSE(前收)`，所有現值瞬間跳回昨收，造成「盤中股價與實際明顯偏差」的使用者抱怨。

#### Steps:

- [x] 79.1 `PriceFetchClient.getTwseRealTimePrice`：移除 `z='-'` 時退回 `prevClose` 寫 Redis 的分支；改回 `Optional.empty()`，並 `log.debug` 紀錄「{code} z='-'，本輪 polling 略過寫入」。同檔股票上不再嘗試另一交易所（`tse`/`otc` for-loop 直接 return empty — codeCheck 已對上）
- [x] 79.2 `PriceFetchClient.getStockPrice` 簽名改為 `Optional<PriceResult>`（含台股 / 美股兩條路徑），讓 `getTwseRealTimePrice` 的 empty 與 `getNasdaqPrice` 的 empty 都能語意一致地往上傳遞，不再以 `RuntimeException("查無股價")` 攔截。
- [x] 79.3 `PricePoller.updatePrices` 改判 `client.getStockPrice(...).orElse(null)`：null 或 `r.price() == null` → 不寫 Redis、不更新 stock 主檔名稱、不視為錯誤（move on 至下一檔）。原本的 try-catch 仍保留以接住 HTTP 例外。
- [x] 79.4 spec：`requirements.md` Requirement 7、`design.md`「TWSE `z='-'` 時改採 skip write」段、`steering/tech.md` TWSE 列已同步更新（本 task 同 commit）
- [x] 79.5 重 build external-materials-service image、`docker compose up -d --build external-materials-service` 套用；盤中（09:00–13:30 Asia/Taipei，平日）觀察 `GET /api/market-data/prices`：所有台股 source 應該都是 `TWSE`（含真實成交價）而非 `TWSE(前收)`；若某輪某檔 z='-'，下輪 polling 後該檔 `price` 與 `updatedAt` 都更新但中間維持上輪值不變。Dashboard「持股明細」chart 各 bar 不再出現「整批同步跳回昨收」的瞬間。
- [x] 79.6 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 80: cold-start fallback — `z='-'` 補上「今日開盤價」候選

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

Task 79 把 `z='-'` 改成 skip write 後解決了「整批跳回昨收」的核心 bug，但暴露出 cold-start 弱點：若某檔股票 Redis cache 還沒被填過（首次部署、剛清過 cache、TTL 過期），而當下 polling 又連續撞到 `z='-'`，Redis 永遠寫不進去，`PriceQueryService.getLive` 自動 fallback 到 `stock_price_history` 最近一筆收盤 — 又變回顯示昨收。盤中 verify 觀察到 6 檔（0050、00878、00679B、2891、009804、00882）卡在這個狀態：例如 2891 中信金實際盤中 bid/ask 68.8/68.9，Redis absent → 圖表顯示昨收 64.1，落差 -7%。

TWSE mis 在這些情境通常仍有今日開盤價 `o`（今日第一筆真實成交），可作 cold-start 候選；但若 Redis 已有今日真實 intraday tick，cold-start 必須讓位（避免用較早的 `o` 蓋掉較新的成交價）。

#### Steps:

- [x] 80.1 `PriceFetchClient.getTwseRealTimePrice`：`z='-'` 分支拆兩段 — 有 `o` → `Optional.of(PriceResult(price=o, source="TWSE(開盤)"))`；無 `o` → 維持 `Optional.empty()`。`changePercent` 以 `o − y` 計算（仍為真實漲跌）
- [x] 80.2 `PriceCacheWriter.write`：判斷 incoming `source.contains("(")`（cold-start fallback）→ 先呼叫 `hasFreshRealtimeCache(key)`；若既有 cache `source` 不含 `(` 且 `tradingDate` == 該市場今日，則 skip write 不覆寫。helper `hasFreshRealtimeCache` 私有，封裝 Redis 讀 + JSON parse + 今日對比邏輯
- [x] 80.3 spec：`requirements.md` Requirement 7、`design.md`「TWSE `z='-'` 時改採兩段式 fallback」段、`steering/tech.md` TWSE 列已同步更新（本 task 同 commit）
- [x] 80.4 重 build external-materials-service image、`docker compose up -d --build external-materials-service`；盤中清空指定股票的 Redis cache（`redis-cli DEL price:台股:2891`）並 trigger refresh，驗證該檔 Redis 重新寫入 `source="TWSE(開盤)"`、price 為今日 open（非昨日 close）；接著 trigger 第二次 refresh，若 TWSE 已回真 z 應覆寫成 `TWSE`，若仍 z='-' 則保留 `TWSE(開盤)` 不變
- [x] 80.5 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 81: 回退 Task 80 cold-start — Redis 只能由真實 z tick 推進

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

Task 80 引入「`z='-'` 但今日 `o` 有值 → 寫入 `TWSE(開盤)` cold-start」候選，並由 `PriceCacheWriter` 守門以避免覆蓋今日真實 cache。雖然驗證流程成功（Dashboard 0050、2891 等 cold-started 股票從昨收 → 今日開盤、落差縮小），但規格擁有者指示：**Redis 內的值應該只反映「最近一次真實成交價」，而非「最接近現況的推估」**。理由如下：

- `o`（今日開盤）也是真實成交，但「open 不等於 latest」，把它寫進 Redis 容易讓使用者誤以為這是當下成交價（chart updatedAt 也會跟著跳成 cold-start 寫入時間）。
- 系統內已有更便宜的「沒有 Redis 就回昨收」路徑（`PriceQueryService.fallbackToHistory` 讀 `stock_price_history`），完全足以涵蓋盤前/cold-start 情境，且語意明確（source="history"）。
- Cold-start fallback 寫 Redis 本質上在「為了畫面好看而塞推估值」，違反「股價一律是成交價」的更上層原則 — 這條原則的精神是「盤中圖表不會動 ≠ bug」。

#### 規則最終定案（兩條）

1. **每天開盤抓不到最新值 → 顯示昨日收盤**：`z='-'` 時不寫 Redis；前端透過 `PriceQueryService.fallbackToHistory` 看到昨收。
2. **每次 tick 抓不到值 → 不要更新 Redis**：`PricePoller` skip write，保留上一輪成功 poll 的真實 z。

#### Steps:

- [x] 81.1 `PriceFetchClient.getTwseRealTimePrice`：移除 Task 80 加入的「`o` cold-start」分支；`z='-'` 一律 `Optional.empty()`，註解改寫成「兩條規則」說明
- [x] 81.2 `PriceCacheWriter.write`：移除 Task 80 加入的「source 含 `(` 守門」分支與 `hasFreshRealtimeCache` 私有 helper；回到「拿到 `PriceResult` 一律寫」的簡化形式（client 端已保證不會送推估值）
- [x] 81.3 spec：`requirements.md` Requirement 7 改寫成「規則僅兩條」、`design.md`「TWSE `z='-'` 時 skip write」段重寫覆蓋 Task 80 兩段式 fallback、`steering/tech.md` TWSE 列同步
- [x] 81.4 重 build external-materials-service image、`docker compose up -d --build external-materials-service`；盤中觀察 `/api/market-data/prices` 不再出現 `TWSE(開盤)`；殘留的 `TWSE(開盤)` 會隨 600s TTL 自然過期，或被真實 z 覆寫
- [x] 81.5 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 82: Redis 股價 TTL 拉長至 24h（修「股價退回昨收」的退化）

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

Task 79+81 定案的兩條規則：(1) 每天開盤抓不到最新值 → 顯示昨收；(2) 每次 tick 抓不到值 → 不更新。但實作上規則 (2) 依賴 Redis 內仍有上一輪 cache，而舊版 `PriceCacheWriter.LIVE_TTL = 600s`（10 分鐘）對流動性低的 ETF / 個股（盤中可連續 5+ 輪 polling 皆 z='-'，例如 006208 富邦台50、00919 群益台灣精選高息）撐不夠長 — 一旦 TTL 過期，`PriceQueryService.getLive` 自動 fallback 至 `stock_price_history` 退回昨收，**使用者看到的就是「明明開盤有真實成交、過了 10 分鐘股價突然退回昨收」**。例如盤中觀察 006208：09:00 撈到真實 z=248.7，09:02-09:11 連續 5 輪 z='-' → 09:11 TTL 過期 → 圖表顯示昨收 244.55；09:13 下一輪若有真 z 又跳回 248.x。

#### 修正

`LIVE_TTL` 改成 24 小時：服務正常運作下，TW 9:00-13:30 / US 9:30-16:00 cron 持續刷新 TTL；當日撈到過真實 z 一次後，該值就在 Redis 內持續活著直到被下一個真實 z 覆寫，不會 TTL 過期被退回昨收。盤後到次日開盤之間（最長約 19.5 小時對 TW），24h TTL 仍有餘裕。真的 24h 都沒新成交才會自然 fallback 至 `stock_price_history` — 這是合理的 cold-start 場景。

#### Steps:

- [x] 82.1 `PriceCacheWriter.LIVE_TTL`：`Duration.ofSeconds(600)` → `Duration.ofHours(24)`，註解寫明動機（避免低流動性股票 z='-' 過久 TTL 過期退回昨收）
- [x] 82.2 spec：`requirements.md` Requirement 7、`design.md` Redis key schema 表 + 一致性段落、`steering/tech.md`、`steering/structure.md` 同步「24h」TTL（本 task 同 commit）
- [x] 82.3 重 build external-materials-service image、`docker compose up -d --build external-materials-service`；盤中觸發 refresh 並等待真 z 寫入 006208 / 00919 等之前退回昨收的標的；觀察 `redis-cli ttl price:台股:006208` 約等於 24h 起點往下倒數，且 5-10 分鐘內不再退回昨收
- [x] 82.4 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）

### Task 83: 警示觸發 Email 通知

對應 Requirements: Requirement 23（警示觸發 Email 通知）

#### 背景

股票觀察清單 / 警示條件頁的警示觸發後，使用者必須開頁面才看得到（觀察清單「警示」欄）。需求是觸發當下主動寄 email 通知，使用者不必盯盤。`StockAlertService` 已有 24h cooldown、`StockAlertTrigger` 完整觸發紀錄（時間、股價、MA、K、D），是天然的通知掛勾點。

收件人不寫死，遵循專案「禁止 enum 寫死、設定走 DB」原則，做成 `/notification-settings` 設定頁。SMTP 走 Gmail App Password，帳密從環境變數讀，不入 git。

#### 設計

- in-memory queue + 排程 60s flush 合併 digest（避免每筆觸發一封）；`StockAlertService.recordTrigger()` 末端 enqueue，dispatcher 失敗一律 log.warn 不回拋（觸發判斷與通知解耦）
- 新 Entity `NotificationRecipient`（id, email, active, createdAt, updatedAt），unique email 正規化
- `EmailService` 包 JavaMailSender，`MAIL_USERNAME`/`MAIL_PASSWORD` 未設定時 skip 寄信
- BFF passthrough `/api/bff/notification-settings/recipients/**` → `/api/notification-recipients/**`
- 前端 `NotificationSettingsView` 收件人新增 / 刪除 / 啟停

#### Steps:

- [x] 83.1 Liquibase migration `v1.26.0-notification-recipient.sql`：建 `notification_recipient` 表（id PK, email VARCHAR UNIQUE NOT NULL, active BOOLEAN DEFAULT TRUE, created_at TIMESTAMP, updated_at TIMESTAMP）；註冊到 `db.changelog-master.yaml`
- [x] 83.2 `backend/pom.xml` 加 `spring-boot-starter-mail`；`backend/src/main/resources/application.yml` 加 `spring.mail.*`（host smtp.gmail.com、port 587、`username=${MAIL_USERNAME:}`、`password=${MAIL_PASSWORD:}`、STARTTLS）；`docker-compose.yml` business-services service 加環境變數 placeholder（讀取自 host env）
- [x] 83.3 後端 `NotificationRecipient` Entity / Repo / DTO / Service / Controller：
  - `model/NotificationRecipient.java`（Lombok + JPA + `@PreUpdate` 更新 updatedAt）
  - `repository/NotificationRecipientRepository.java`（含 `findByActiveTrue()`、`findByEmailIgnoreCase()`）
  - `dto/NotificationRecipientDto.java`（Request / Response）
  - `service/NotificationRecipientService.java`（CRUD + email normalize trim+toLowerCase + duplicate 檢查 + toggleActive）
  - `controller/NotificationRecipientController.java`（`/api/notification-recipients` + `PATCH .../{id}/active`）
- [x] 83.4 `service/EmailService.java`：包 JavaMailSender；`isEnabled()` 檢查 `MAIL_USERNAME` 非空；`send(toList, subject, body)` SMTP 失敗 `log.warn` 不拋例外；寄件人優先用 `${NOTIFICATION_FROM:${spring.mail.username}}`
- [x] 83.5 `service/AlertNotificationDispatcher.java`：
  - `ConcurrentLinkedQueue<PendingTrigger>` 收 `enqueue(alert, triggeredAt, price, ind)`
  - `@Scheduled(fixedDelay = 60_000)` `flush()`：drain queue、組 digest subject + body、讀 active recipients、呼叫 EmailService
  - 收件人空 / EmailService disabled → skip 寄信但仍清空 queue（避免 queue 無限長大）
- [x] 83.6 `StockAlertService.recordTrigger()` 末端注入 `dispatcher.enqueue(...)`；包 try/catch 確保通知失敗不影響觸發紀錄落地
- [x] 83.7 BFF `NotificationSettingsBffRoutes`：rewrite `/api/bff/notification-settings/recipients(?<seg>/?.*)` → `/api/notification-recipients${seg}` → `business-services.url`
- [x] 83.8 前端 `views/NotificationSettingsView.vue`：表格顯示收件人 + email 輸入框新增 + 啟停 switch + 刪除按鈕；走 `/api/bff/notification-settings/recipients`；加路由 `/notification-settings`；在主選單「系統設定」群組加入口
- [x] 83.9 部署文件：CLAUDE.md / README 補一段「Gmail App Password 取得步驟」（兩步驗證 → 應用程式密碼 → 16 碼貼到 `MAIL_PASSWORD`），docker-compose env 範例；本機 build 跑通、手動建一筆假觸發或調整 cooldown 驗證 digest 收信
- [x] 83.10 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）


### Task 84: HistoricalBackfillService 禁止寫入「今日列」（避免 Yahoo intraday bar 汙染 DB 收盤）

對應 Requirements: Requirement 7（市場資料整合 — 「今日列」獨佔規則）

#### 背景

VOO 股票走勢圖在 2026-06-05 NY 盤中（15:16，未到 16:00 收盤）顯示「收盤 689.70」，但 Redis live tick 同時為 $677.20，兩者脫鉤。DB 現況：2026-06-05 row 已有完整 `open_price=691.71`、`high=692.18`、`low=687.96`、`close=689.70`，是該檔表中**唯一有 open_price 的近期紀錄**（6/4、6/3 都沒 open，因 FinMind 收盤校正不回 open）。

根因：`HistoricalBackfillService.backfillUsStock` 走 `PriceFetchClient.fetchUsHistoricalRange` → Yahoo Finance `chart?interval=1d`。Yahoo 此 endpoint 在「市場仍開盤」時，會把「今日 partial bar」（open=今日開盤、high/low=至此刻區間、close=此刻 last trade）也包成一筆 `HistoricalBar` 回傳。`backfillUsStock` 的 for-loop 只用 `existsHistory` 去重，沒有「今日盤中不寫」的守門，所以該 partial bar 直接被 upsert 進 `stock_price_history`，前端走勢圖 / SnapshotForm 讀 DB 就看到一個假收盤。

觸發來源任一即可（不必查具體誰）：(1) `startupBackfill` 條件 stale；(2) `SnapshotFormBffController.triggerBackfillThenRefetch`（使用者開今日 SnapshotForm，缺今日歷史價即觸發 `until=today+5d` backfill）；(3) `/api/market-data/history/backfill-stock` / `/internal/backfill/all` 手動觸發。

修正方向：將「今日列」獨佔給 `ClosePersister`（13:32 / 16:02 dump、16:00 / 18:00 verify、`selfHealMissedClose` 過收盤時點），`HistoricalBackfillService` 只負責歷史 row。

#### Steps:

- [x] 84.1 `HistoricalBackfillService.backfillTwStock`：
  - `LocalDate today = LocalDate.now(MarketClock.TW_ZONE)`
  - `LocalDate end = (until != null) ? until : today`（改用 TW_ZONE，不用 JVM 預設 TZ）
  - for-loop 內：`if (bar.tradingDate().equals(today)) continue;` 並於該分支 `log.debug` 記錄
- [x] 84.2 `HistoricalBackfillService.backfillUsStock`：同 84.1，但用 `MarketClock.US_ZONE`
- [x] 84.3 spec：`requirements.md` Requirement 7 加「今日列獨佔」acceptance criterion；`design.md` 在 ClosePersister sequence 後補一段「今日列獨佔規則」說明
- [x] 84.4 清除已被汙染的 6/5 VOO row（DELETE WHERE stock_code='VOO' AND market='美股' AND trading_date='2026-06-05'）；等 16:02 ET ClosePersister 寫入正確的收盤
- [x] 84.5 Docker 重 build external-materials-service image + 容器重建（`docker compose build external-materials-service && docker compose up -d external-materials-service`）
- [x] 84.6 commit + 兩段式 merge（feature 分支 commit + main 用 `--no-ff` merge）



---

### Task 85: 新增「英股」市場類型（CSPX 等 LSE UCITS ETF）

對應 Requirements: Requirement 24（英股市場類型擴充）

#### 背景

使用者要透過複委託投資 LSE 掛牌的 USD-denominated UCITS ETF（CSPX、VWRA、VUSA、EIMI、IWDA 等）。現有系統只有「台股 / 美股」兩個市場類型，需新增第三類「英股」並在所有相關頁面與後端服務支援。

設計決策：
- 計價幣別沿用既有 USD 體系（CSPX.L USD-denominated），`StockHolding.currency='USD'`，市值換算與美股同 path；不引入 GBP 匯率體系
- 即時股價走 Yahoo Finance `.L` suffix（無 NASDAQ / FinMind 對應）
- ETF 成分股外部連結用 iShares 官網

#### Steps:

- [x] 85.1 spec：requirements.md Req 12 seed 改 3 種；新增 Req 24；design.md 加 MarketType seed 註解 + Req 24 設計章節
- [x] 85.2 backend `DataInitializer.seedMarketTypes()` 加 `("英股", "英國股市", 3)`
- [x] 85.3 external-materials `MarketClock`：加 `LON_ZONE` + `isUkMarketOpen` + `isUkMarketJustClosed`
- [x] 85.4 external-materials `PriceFetchClient`：加 `getYahooLsePrice` + `fetchUkHistoricalRange`；`getStockPrice` 加英股分支
- [x] 85.5 external-materials `StockSourceQuery`：三組 collect 方法 signature 改三路（加 ukCodes）
- [x] 85.6 external-materials `PricePoller`：加 `scheduledUkIntradayUpdate` cron、`warmCacheOnStartup` / `refreshAll` 涵蓋英股、`RefreshSummary` 加 ukUpdated/ukMarketOpen
- [x] 85.7 external-materials `ClosePersister`：加 `dumpUkCloseFromRedis` cron 16:32 LON、`verifyUkCloseWithYahoo` cron 17:00 LON、`selfHealMissedClose` 倫敦時區分支
- [x] 85.8 external-materials `HistoricalBackfillService.backfillUkStock`；`startupBackfill` / `backfillAll` / `backfillSingleStock` 加英股分支
- [x] 85.9 external-materials `MarketDataFetchService`：加 `fetchUkStockName`；`getDividendRate` / `getEtfHoldings` / `getDividendHistory` 加英股分支
- [x] 85.10 backend 新增 `com.steven.assets.util.MarketZones.resolve(market)` helper
- [x] 85.11 backend `StockPriceService`：加 LON_ZONE / isUkMarketOpen；`getLiveAssets` 英股套 USD 匯率；`getMarketStatus` / `manualRefresh` / `LiveAssetsResponse` 加 ukMarketOpen
- [x] 85.12 backend `TechnicalIndicatorService` / `WatchStockService` / `StockAlertService` / `HistoricalDataService` 改用 `MarketZones.resolve`
- [x] 85.13 backend `StockAlertController.lookupName`：`0000 + 英股` 拒絕
- [x] 85.14 backend `StockAlertService.assertNameMatchesCode` 加英股分支
- [x] 85.15 backend `MarketDataService.getDividendRate / getEtfHoldings / getDividendHistory / isEtf` 加英股分支
- [x] 85.16 frontend `StockAnalysisDialog.vue` 加英股 ETF 白名單 + iShares 連結
- [x] 85.17 frontend `DashboardView.vue` 頂部彙整列加英股欄、資產配置圓餅 6 區、KPI 計算
- [x] 85.18 frontend `AssetHistoryView.vue` 加英股欄
- [x] 85.19 frontend `SnapshotDetailView.vue` 英股 USD 顯示
- [x] 85.20 frontend `SnapshotFormView.vue` 股票區塊加英股區
- [x] 85.21 frontend `WatchStockView.vue` / `StockAlertView.vue` 加英股 tab + LON 時區後綴
- [x] 85.22 frontend `TradingCalendarView.vue` 顯示英股市場狀態
- [x] 85.23 frontend `RealizedGainView.vue` market 選項加英股
- [x] 85.24 Docker 重 build：`docker compose build backend external-materials-service frontend && docker compose up -d backend external-materials-service frontend`
- [x] 85.25 手動驗證：新增 CSPX/英股 持股、watch、alert，dashboard 顯示英股欄、historical backfill 觸發、倫敦時區排程啟動

---

### Task 86: Dashboard 資產配置面板加「台股個股穿透前 10 大」tab

對應 Requirements: Requirement 9（[requirements.md:162-170](spec/requirements.md)）

#### 背景

「資產配置分佈」目前只顯示 6 大資產類別，看不出台股部位實際集中在哪幾檔個股。使用者多檔台股 ETF（0050、0056、00878…）內含的成分股才是真正的曝險，應穿透拆解後與直接持股合併計算前 10 大個股。FinMind `TaiwanETFHoldings` API 已透過 `MarketDataFetchService.getEtfHoldings` 提供全成分股 + weight%，不需新接外部資料源。

設計決策：
- BFF 新加 lazy endpoint，前端切到 tab 才 fetch，避免拖慢 `/summary` 首屏
- 計算口徑：ETF 穿透 + 直接持股一律依 `stockCode` 加總（單一事實）
- ETF 抓取失敗的 fallback：整筆退回以代號自身計入，並回傳 `degradedEtfs` 讓前端顯示降級註記（避免漏算總金額）

#### Steps:

- [x] 86.1 新增 `bff/.../dashboard/dto/TwStockLookthroughDto.java`（含 `Item` / `Others` / `DegradedEtf` 內部 record）
- [x] 86.2 `DashboardBffController.getTwStockLookthrough(snapshotId)`：抓 snapshot detail + closePrices → mergedStocks → 篩台股 → 並行（concurrency 4，Yahoo quoteSummary 對單 IP 有 rate limit）呼叫 `/api/market-data/etf-holdings?market=台股&code=...` → 拆解 + 加總 → top10 + others
- [x] 86.3 前端 `DashboardView.vue`：「資產配置分佈」card header 加 el-tabs（`category` / `twStock`），新 `twStockPieOption` computed，watch `allocationTab` + `selectedSnapshotId` lazy fetch，前端 Map cache（key = snapshotId）
- [x] 86.4 ETF 抓取失敗的 degradedEtfs 註記顯示（淡灰小字於 panel 底部）
- [x] 86.5 ETF 成分股資料來源（FinMind `TaiwanETFHoldings` 已被移除回 422）：
  - 台股主來源改 **MoneyDJ** `Basic0007a.xdjhtm?etfid={code}.TW`（`getMoneyDjEtfHoldings` + `parseMoneyDjHoldings`，完整成分股、解析「股票名稱/持股(千股)/比例」表）；Yahoo `topHoldings` 前 10 退為 fallback
  - MoneyDJ / Yahoo 一律走 `curl` 子程序（`runCurl`）：站方 WAF 依 TLS 指紋對 Java HttpClient 回 429（同容器 curl 卻 200）；沿用本檔既有 `ProcessBuilder("curl"...)` 先例
  - `getEtfHoldings` 加 12h in-memory cache（成分股每日至多變動一次）
  - MoneyDJ 只給股名無代號 → BFF lookthrough 聚合鍵用**股名**；ext-materials 以記憶體「股名→代號」字典（TWSE `STOCK_DAY_ALL` + TPEX，24h cache，`twNameToCodeMap()`）補代號供 tooltip 顯示，**不**寫入 stock 主檔（避免污染 `StockSourceQuery` 抓價清單）
  - 前端圓餅圖：環標籤/圖例只顯示股名 + 佔比；tooltip 顯示「代號 股名 + 金額 + 佔比」；ETF 依權重正規化完全穿透（不殘留 ETF 自身 slice）
  - 同步更新 Req 9 / Req 13 / design.md 資料來源描述
- [x] 86.6 手動驗證：curl endpoint 檢查 items.length ≤ 10、percent 加總 ≈ 100、ETF 確實穿透（出現 2330/2317 等成分股而非 ETF 代號）；前端切換 tab 與 snapshot 行為正確
- [x] 86.7 「台股個股」tab hover 連動：對齊 tab 1「資產類別」既有 hover 行為，hover 趨勢圖某節點時 tab 2 圓餅圖切到該節點對應 snapshot 的穿透結果。實作要點：
  - 新增 `effectiveSnapshotId` computed：`hoveredHistoryDate` → 從 `store.history` 找對應 row.id；無 hover 時 fallback 到 `selectedSnapshotId`
  - watch source 從 `[allocationTab, selectedSnapshotId]` 改為 `[allocationTab, effectiveSnapshotId]`；cache hit 同步切換、miss 走 `loadTwStockLookthrough` lazy fetch
  - `loadTwStockLookthrough` race 防護判斷由 `selectedSnapshotId.value === snapshotId` 改為 `effectiveSnapshotId.value === snapshotId`（hover 快速移動時不寫入過期 fetch 結果）
  - `selectedSnapshotId` 語義保持不變（仍是下拉選的快照），不影響 KPI 卡 / 持股表等其他面板


### Task 87: 股票走勢圖期間按鈕加「當日」（最後一交易日分時 5m K 線）

對應 Requirements: Requirement 13（[requirements.md:225-226](spec/requirements.md)）

#### 背景

走勢圖期間按鈕目前最短為「1個月」（21 個交易日），看不到當天盤中走勢；使用者想在收盤後快速看「今天股價怎麼走」。external-materials-service 已有 `PriceFetchClient.fetchIntraday5m` + `/internal/intraday-5m` endpoint（原為 `StockAlertService` 警示觸發補抓用），business-services 也已有 `HistoricalDataService.fetchIntraday5m` proxy。直接把這份既有資料源接通到 StockAnalysisDialog 即可，不需另外累計 ticks。

設計決策：
- 「當日」模式抓「最近 1 個交易日」的 5m K 線（Yahoo `chart?interval=5m&range=1d`），盤後即可看當天完整走勢
- 月線 / 季線 / 年線 / KD 仍要在 legend 顯示，但 intraday tick 數無法支撐日線級 MA20/60/240、KD(9) 重算 → 改畫「該檔最近一個交易日收盤後的日線最新值」水平參考線，與其他期間 legend 數值口徑一致（避免使用者切回 1個月時看到不同 MA 值產生誤會）
- 一次抓 10 年的歷史資料保留，「當日」模式只額外抓一支 intraday API；切換期間時前端只重組 chartOption，不再 roundtrip

#### Steps:

- [x] 87.1 backend `MarketDataController.getIntraday5m`：`GET /api/market-data/intraday-5m?code=&market=&daysBack=1`，直接代理 `HistoricalDataService.fetchIntraday5m`，回 `List<IntradayBar>`（time / open / high / low / close）
- [x] 87.2 bff `StockAnalysisBffRoutes`：加 `stock-analysis-intraday-5m` route `GET /api/bff/stock-analysis/intraday-5m` → `/api/market-data/intraday-5m`
- [x] 87.3 frontend `api/index.js`：`bffApi.stockAnalysis.getIntraday5m(code, market, daysBack=1)`
- [x] 87.4 frontend `StockAnalysisDialog.vue`：
  - `rangeOptions` 第一項插入 `{ label: '當日', months: 0 }`，預設仍為 12（1年）
  - `months === 0` 時改抓 `getIntraday5m`，存到 `intradayBars` ref；資料截止顯示為 intradayBars 最後一筆的 date 部分
  - 切到當日模式時 chartOption 改以 `intradayBars` 為 x 軸（time 顯示為 HH:mm），price 序列為 close；月線 / 季線 / 年線 / KD 改畫水平線（值取自日線資料 last MA20/60/240 / last K / last D）
  - tooltip / legend 末值顯示維持與其他期間一致格式
- [x] 87.5 Docker 重 build：`docker compose build backend external-materials-service bff frontend && docker compose up -d backend external-materials-service bff frontend`
- [x] 87.6 手動驗證：開啟任一持股的股票分析 → 切到「當日」→ 確認分時走勢圖出現、月/季/年線為水平線、KD 副圖顯示最新值水平線；切回「1年」回到日線正常顯示


### Task 88: 「當日」走勢兩階段資料源（盤中 polling 累積 + 盤後外部源覆寫）

對應 Requirements: Requirement 13（[requirements.md:226-227](spec/requirements.md)）

#### 背景

Task 87 以 Yahoo `interval=5m` 作為「當日」走勢資料源，但 Yahoo 對台股盤中有延遲、且不是我們最權威的來源。改採兩階段：
- **盤中**：`external-materials-service` 既有 `PricePoller` 每 2 分鐘輪詢真實成交價，原本只覆寫 Redis live key `price:{m}:{c}`。本任務在 `PriceCacheWriter.write` 多寫一筆「tick」到 Redis LIST `price:ticks:{market}:{code}:{tradingDate}`，前端切到「當日」即可看到當下累積的分時走勢（不依賴外部 API、無延遲）。
- **盤後**：抓 FinMind / Yahoo 全天完整資料 `DEL` + `RPUSH` 覆寫該日 LIST。理由：盤中 polling 是 2 分鐘輪詢，遇 z='-'（兩 tick 之間無成交）會 skip，原始 LIST 可能跳號；盤後用權威完整資料覆寫，補回所有時間點，前端看到的就是「正確的曲線圖」。

設計決策：
- **Tick element** 格式：JSON `{"t":"2026-06-05T13:25:00","p":"104.50"}`（time = ISO LocalDateTime，與既有 `IntradayBar.time` 同格式；price = BigDecimal 字串避免精度漂移）
- **覆寫時機**：在 `ClosePersister.dumpXxxCloseFromRedis` 之後再 3 分鐘觸發，避免與 dump 競爭 Redis IO；cron 與 `ClosePersister` 同 zone
- **資料源**：台股 FinMind `TaiwanStockKBar` 5m K（用 close 作為 tick.p；FinMind sponsor token 在 application.yml 既有 `finmind.token`），美/英股 Yahoo `chart?interval=5m`（沿用 `fetchIntraday5m`）
- **舊 endpoint `/intraday-5m` 保留**：StockAlertService 警示觸發補抓仍用該 endpoint（不同用途、不同資料源語意），不動

#### Steps:

- [x] 88.1 ext-materials 新 `IntradayTickStore`（同 `IntradayHighLowTracker` pattern）：`appendTick(code, market, date, time, price)` `RPUSH` + `EXPIRE` 36h；`replaceTicks(code, market, date, ticks)` `DEL` + `RPUSH`；`getTicks(code, market, date)` `LRANGE 0 -1` 解 JSON 回傳 `List<TickPoint>`（time, price）
- [x] 88.2 `PriceCacheWriter.write` 在 `redis.opsForValue().set(key, json, LIVE_TTL)` 之後加 tick append：`source` 不含 `(` 視為真實成交（與 `HistoricalDataService.getStockHistory` 對「今日格」的守門條件一致），呼叫 `tickStore.appendTick(code, market, tradingDate, LocalDateTime.now(marketZone), price)`
- [x] 88.3 ext-materials `PriceFetchClient.fetchTwKBar5m(code, date)`：打 FinMind `dataset=TaiwanStockKBar&data_id={code}&start_date={date}&end_date={date}`，回 `List<TickBar>`，每筆 = (date + "T" + minute, close)。FinMind row 欄位：`date`、`minute`、`close`。FinMind `TaiwanStockKBar` 為 sponsor 付費 dataset，無 token 時回 400 → `IntradayTickRefresher.refreshTwOne` 偵測到空 list 後 fallback 到 Yahoo 5m（與美/英股同源）
- [x] 88.4 ext-materials 新 `IntradayTickRefresher`：
  - `@Scheduled(cron = "0 35 13 * * MON-FRI", zone = "Asia/Taipei") refreshTwTicks()`：對所有持股（`StockSourceQuery.collectHeldStockCodes` 台股部分）並行 fetch FinMind 5m K 線 → `tickStore.replaceTicks`
  - `@Scheduled(cron = "0 5 16 * * MON-FRI", zone = "America/New_York") refreshUsTicks()`：美股用 `PriceFetchClient.fetchIntraday5m(code, "美股", 1)` → tick + close → replaceTicks
  - `@Scheduled(cron = "0 35 16 * * MON-FRI", zone = "Europe/London") refreshUkTicks()`：英股同上但 market="英股"
  - 啟動時若市場已收盤但今日 ticks LIST 空，補一次 cold-start refresh
- [x] 88.5 ext-materials `InternalPriceController.intradayTicks(code, market, date)`：`GET /internal/intraday-ticks`，預設 date = 該市場時區的「最近一個交易日」（用 `MarketClock` / `findMaxTradingDate`），回 `List<TickPoint>`
- [x] 88.6 business-services `HistoricalDataService.fetchIntradayTicks(code, market, date)`：proxy 至 `/internal/intraday-ticks`
- [x] 88.7 business-services `MarketDataController.getIntradayTicks`：`GET /api/market-data/intraday-ticks?code=&market=&date=`（date 可選）
- [x] 88.8 bff `StockAnalysisBffRoutes` 加 `stock-analysis-intraday-ticks` route `/api/bff/stock-analysis/intraday-ticks` → `/api/market-data/intraday-ticks`
- [x] 88.9 frontend `api/index.js` `bffApi.stockAnalysis.getIntradayTicks(code, market, date?)`
- [x] 88.10 frontend `StockAnalysisDialog.vue`：`intradayBars`→`intradayTicks` 結構改 `{time, price}`；prices = ticks.map(t => t.price)；移除 close/open/high/low 解讀
- [x] 88.11 Docker 重 build + recreate：`backend bff external-materials-service frontend`
- [x] 88.12 手動驗證：(a) `docker exec asset-redis redis-cli LRANGE 'price:ticks:台股:0050:2026-06-05' 0 -1` 確認 LIST 存在且有資料；(b) 直接 curl `/api/bff/stock-analysis/intraday-ticks?code=0050&market=台股` 看走勢資料回傳；(c) 等下個 polling 週期，LIST 多一筆 tick；(d) 開啟股票分析切「當日」確認走勢與 Task 87 行為一致

### Task 89: 走勢圖「成本均價」改用 BFF avgCostOriginal（修跨頁買入均價不一致）

對應 Requirements: Requirement 13（股票走勢圖延伸資訊）

#### 問題

Dashboard 美股表格「買入均價(USD)」（如 SGOV 100.6707）與雙擊開啟的走勢圖「成本均價」（100.19）對不上，且走勢圖值偏低。
根因：兩者算法不同口徑 ——
- 表格 `row.avgCostOriginal` = `investmentCostOriginal ÷ shares`，`investmentCostOriginal` 由 `SnapshotEnricher.enrichInvestmentCostOriginal` 用**交易當下匯率 `transactionExchangeRate`** 還原原幣（鎖定買入時刻，買進後固定）。
- 走勢圖 `StockAnalysisDialog.vue` 卻自行 `costTwd = investmentCost ÷ shares`，再 `cost = costTwd ÷ props.usdRate`，**用的是今日即時匯率 `detail.usdExchangeRate`**（每日浮動）。

今日台幣較買入時貶值（匯率變大），固定台幣成本除以較大匯率 → USD 數字偏小，故走勢圖 100.19 < 表格 100.6707。
此違反 `design.md` 已明訂的「前端買入均價直接使用 `avgCostOriginal` 同欄位」與 BFF「同義欄位、同一來源」原則。
（註：走勢圖此線實際只在「從 Dashboard 列雙擊」時繪製 —— 唯一帶有 `shares + investmentCost + avgCostOriginal` 的入口；WatchStock / StockAlert / RealizedGain 只傳 `{stockCode, stockName, market}`，SnapshotForm grouped row 頂層無 `shares/investmentCost`，皆不畫此線。）

#### Steps:

- [x] 89.1 frontend `StockAnalysisDialog.vue` `chartOption`：成本均價改為一律優先取 `s.avgCostOriginal`（其次 `s.investmentCostOriginal ÷ shares`），與表格買入均價同義同源；移除「`investmentCost ÷ shares ÷ 今日 usdRate`」反推為主路徑。僅在完全無原幣成本欄位時 fallback（台股 `investmentCost` 即原幣 TWD；美/英股才用匯率反推），確保未帶 BFF 欄位的呼叫端不致畫出台幣值到 USD 軸
- [x] 89.2 spec：`requirements.md` Requirement 13 加驗收條件；`design.md` BFF Enrichment 註記補「成本均價／買入均價一律用 `avgCostOriginal`，禁止用今日匯率反推」
- [x] 89.3 Docker 重 build + recreate：`frontend`
- [x] 89.4 手動驗證：Dashboard 美股雙擊 SGOV → 走勢圖「成本均價」應顯示與表格「買入均價(USD)」相同的 100.6707（不再是 100.19）；切換不同持股再次確認兩處數字一致


### Task 90: 警示觸發 Email 改列月線／季線／年線三條均線

對應 Requirements: Requirement 23（[requirements.md:475](spec/requirements.md)）

#### 問題

警示觸發 email 目前每筆只印一行「均線：{ma}」，`ma` 取自 `pickMaForAlert(alertType, maPeriod, ind)`，亦即「觸發條件對應的那一條均線」（低於季線→只印 MA60）。使用者想在信中一次看到月線（MA20）、季線（MA60）、年線（MA240）三條值，掌握完整技術面，而非只看觸發的那一條。`TechnicalIndicatorService.FullIndicators` 本就同時算出三條，資料已具備，只是 dispatcher 沒帶過去。

#### Steps:

- [x] 90.1 `AlertNotificationDispatcher`：`enqueue` 與 `PendingTrigger` 的單一 `maValue` 改為 `monthlyMa / quarterlyMa / annualMa` 三欄；`buildDigestBody` 由單行「均線」改為依序印「月線 / 季線 / 年線」三行，各自於非 null 時才印（歷史不足以撐滿某視窗時該行省略）
- [x] 90.2 `StockAlertService.recordTrigger` enqueue 呼叫改傳 `ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa()`（不再用 `pickMaForAlert` 縮成單值）；`pickMaForAlert` 保留供 line 208 凍結 `lastTriggeredMaValue`（UI 顯示對應觸發條件的單一均線）使用
- [x] 90.3 spec：`requirements.md` Requirement 23、`design.md` digest 內容格式與 enqueue 簽名同步更新
- [x] 90.4 Docker 重 build + recreate：`backend`
- [x] 90.5 手動驗證：觸發任一均線警示 → 收到的 email 該筆同時列出「月線 / 季線 / 年線」三行 + KD；歷史不足 240 日者年線行省略


### Task 91: 觀察清單「補發」按鈕（各市場最後交易日觸發事件，單封 email 重寄）

對應 Requirements: Requirement 23（[requirements.md:477](spec/requirements.md)）

#### 背景

警示觸發 email 是自動寄送（dispatcher 60s flush），但使用者可能漏看或想重看當天整批。需求：在觀察清單「新增觀察」左側加「補發」鈕，按一下把**各市場「最後交易日」當天觸發的事件**（盤中則為當日盤中至今）彙整成**單一** digest email 重寄。跨時區關鍵：`stock_alert_trigger.triggered_at` 以各市場 wall time 儲存，所以「當天」需各市場各自以其時區判定「最後交易日」，台北深夜按下時仍能涵蓋 US 當日盤的觸發。重用 `AlertNotificationDispatcher.buildDigestBody` 確保與自動信同格式（月/季/年線 + KD 同源）。

#### Steps:

- [x] 91.1 `StockAlertTriggerRepository`：加 `findDistinctMarkets()` 與「某 market + triggered_at 落在某日窗 [start, end)」查詢（依 triggeredAt asc）
- [x] 91.2 `AlertNotificationDispatcher`：`resolveStockName` 改吃 `(code, market)`（沿用 0000→台股大盤、否則查 stock master）；新增 `resendLastTradingDay()`：對 `findDistinctMarkets` 每個 market 算 `lastTradingDate`（平日已過開盤＝當日、盤前/週末回溯最近平日，不考慮假日；開盤時刻沿用 `StockAlertService.computeTriggeredAt` 同口徑：台 09:00 / 美 09:30 / 英 08:00）→ 取當日觸發列 → 回查 `StockAlert` 用 `buildLabel` 還原條件（孤兒觸發 fallback「警示觸發」）→ 合併用 `buildDigestBody` 組單封 `[資產管理] 股票警示補發 N 筆` 寄 active 收件人；回傳 `ResendResult{status, count}`（SENT/NO_EVENTS/NO_RECIPIENTS/EMAIL_DISABLED）
- [x] 91.3 `WatchStockController`：注入 dispatcher，加 `POST /api/watch-stocks/resend-digest` → 委派 `resendLastTradingDay()`，map 成 `{sent, count, message}` 回傳（四態各給中文訊息）
- [x] 91.4 BFF：watch-stock passthrough（`/api/bff/watch-stock/**`→`/api/watch-stocks/**`）已涵蓋 POST，無需改 route
- [x] 91.5 frontend `api/index.js`：`bffApi.watchStock.resendDigest()` = `api.post('/bff/watch-stock/resend-digest')`
- [x] 91.6 frontend `WatchStockView.vue`：header「新增觀察」左側加「補發」鈕（`Promotion` icon + loading），`resendDigest()` 呼叫後依 `sent` 以 `ElMessage.success` / `.warning` 提示 `message`
- [x] 91.7 Docker 重 build + recreate：`backend frontend`
- [x] 91.8 手動驗證：觀察頁點「補發」→ 收到單封含各市場最後交易日全部觸發的 email；無觸發 / 無收件人時前端顯示對應提示且不寄空信


### Task 92: 警示 / 補發 email 同一股票多條件合併成一筆

對應 Requirements: Requirement 23（[requirements.md:476](spec/requirements.md)）

#### 背景

digest / 補發信原本每個觸發（trigger）印一個區塊，同一股票若有多個條件觸發（如同時「低於季線」「低於年線」），會出現多個重複的股票區塊、技術指標也重複。需求：同一張股票匯成一筆，標題列出該股全部觸發條件，技術快照只顯示一次。改動集中在 `buildDigestBody`，自動 digest 與手動補發共用，故兩者一起生效。

#### Steps:

- [x] 92.1 `AlertNotificationDispatcher.buildDigestBody`：先 `groupByStock`（LinkedHashMap，key=stockCode+market，保留首次出現順序）；每組標題 `{name} ({code} {market}) — {labels 去重串接「、」}`，技術快照（觸發時間/股價/月季年線/KD）取該組 max `triggeredAt` 的那筆；編號 N 改數「組（股票）」
- [x] 92.2 主旨與補發回傳筆數改用 `groupByStock(batch).size()`（去重股票檔數）；`flush()` / `resendLastTradingDay()` 同步；`WatchStockController` 成功訊息改「已補發 N 檔股票的觸發事件」
- [x] 92.3 Docker 重 build + recreate：`backend`
- [x] 92.4 手動驗證：一檔股票同時觸發兩條件 → email 只出現一個區塊，標題含兩條件 label，技術指標一份；主旨 N = 股票檔數


### Task 93: 警示 / 補發 email 內嵌「股票分析走勢圖」PNG

對應 Requirements: Requirement 23（[requirements.md:478](spec/requirements.md)）

#### 背景

使用者希望 email 不只有文字技術快照，還能看到走勢圖。email 無法跑 JS（不能嵌 ECharts），故改由後端 server-side 渲染 PNG 內嵌。為與畫面 `StockAnalysisDialog` 一致，沿用同一資料源與同一 MA 算法。純 Java（XChart）繪圖、無外部服務依賴（符合系統自足原則）。

#### Steps:

- [x] 93.1 `pom.xml` 加 `org.knowm.xchart:xchart:3.8.8`（純 Java 繪圖）
- [x] 93.2 新 `AlertChartRenderer.renderPriceMaPng(code, market)`：走 `HistoricalDataService.getStockHistory(code, market, end-24M, end)`（0000 自動讀指數表、併今日即時價）→ 與前端 `calcMA` 相同的簡單平均四捨五入 2 位算 MA20/60/240 → XChart 畫近 252 日 股價 + 月/季/年線（藍/橘/紫/紅）→ PNG bytes；資料不足 / 失敗回 `Optional.empty()`
- [x] 93.3 `EmailService.sendHtml(recipients, subject, html, inlineImages)`：`MimeMessage` + `MimeMessageHelper(multipart)`，`setText(html,true)` 後 `addInline(cid, ByteArrayResource, image/png)`；失敗一律 log.warn
- [x] 93.4 `AlertNotificationDispatcher`：`buildDigestBody`(純文字) 改 `buildDigest`→`DigestMail{html, inlineImages, stockCount}`，每檔股票文字下內嵌 `<img src="cid:chart{i}">`；`flush()` / `resendLastTradingDay()` 改呼叫 `sendHtml`
- [x] 93.5 backend `Dockerfile`：runtime（alpine slim JRE 無字型）apk 增裝 `fontconfig ttf-dejavu`，ENTRYPOINT 加 `-Djava.awt.headless=true`（否則 AWT 繪文字拋例外）
- [x] 93.6 `WatchStockController` 加 `GET /api/watch-stocks/chart.png?code=&market=` 預覽端點（資料不足回 204），供前端 / 驗證用
- [x] 93.7 Docker 重 build + recreate：`backend`（含字型層；pom 改動會觸發 go-offline 重抓 xchart）— 已完成，容器 healthy
- [x] 93.8 驗證：curl `/api/bff/watch-stock/chart.png?code=2330&market=台股` 回 700×320 PNG，圖與畫面 `StockAnalysisDialog` 數值一致（股價≈2305、季線≈2112、年線≈1589），字型正常渲染（已於 alpine 容器確認）
- [ ] 93.9 端對端：實際觸發 / 按補發 → 收到的 HTML email 每檔股票下顯示走勢圖（需實寄，外向動作待使用者授權或自行點按）


### Task 94: email 走勢圖升級為 Price+MA / KD 雙 pane、數值入圖、文字精簡

對應 Requirements: Requirement 23（[requirements.md:479-482](spec/requirements.md)）

#### 背景

使用者要 email 內的圖完全比照畫面 `StockAnalysisDialog`：加 KD 副圖、所有數值寫進圖內 legend，並把這些數值從 email 文字移除（直接看圖）。以 workflow 平行研究 + 對抗式審查釘死兩大風險：(1) 容器 CJK 字型——`.ttc` face 0 是日文變體，須用 `Font.createFonts` 挑 TC face；(2) KD/MA 與畫面逐位一致——須同 120 月範圍、MA 改逐點重算。

#### Steps:

- [x] 94.1 `AlertChartRenderer` 重寫：上 pane 股價+月/季/年線、下 pane K/D（0~100、80/20 虛線）兩張 XChart 用 Graphics2D 合成；資料抓 120 月；`calcMa` 改逐點重算+HALF_UP；新增 `calcKd`（period 9，與前端/`TechnicalIndicatorService` 同遞迴，high/low 缺值 fallback close）；legend 帶最新值（`%,.2f`、白底）；顏色對齊畫面
- [x] 94.2 CJK 字型：`loadCjkFont` 用 `Font.createFonts` 取 TC face（多候選路徑 + 遞迴掃 `/usr/share/fonts` 後備），失敗 fallback SANS_SERIF
- [x] 94.3 `Dockerfile`：apk 加 `font-noto-cjk freetype`、`RUN fc-cache -f`（`-Djava.awt.headless=true` 已於 Task 93 加）
- [x] 94.4 `AlertNotificationDispatcher.buildDigest`：移除月/季/年線/KD 文字列；保留標題 + 觸發時間 + 觸發股價；`<img>` 寬度 700→900；刪 dead `appendMa`（`formatNumber` 仍給觸發股價用，保留）；每封內嵌圖數設上限、超過僅文字
- [x] 94.5 `EmailService.sendHtml`：log 內嵌圖總位元組（觀察 digest 大小）
- [x] 94.6 Docker 重 build + recreate：`backend`（含 CJK 字型層）— 已完成，容器 healthy；啟動 log 確認字型 `Noto Sans CJK TC`（檔 /usr/share/fonts/noto/NotoSansCJK-Regular.ttc）
- [x] 94.7 容器內視覺驗證：00881 與 2330 PNG 皆 (a) 上 pane 股價+3 均線、下 pane KD + 80/20 ✓ (b) legend 繁中正常（Noto Sans CJK TC、非日系、非方框）且帶數值 ✓ (c) 00881 數值與畫面一致（股價 54.35 / 季線MA60 45.53 / K 60.15 / D 67.07，後續因即時資料更新而微動）✓ (d) 上下 pane 左軸右對齊等寬 gutter → plot 對齊（2330 四位數價也對齊）✓
- [ ] 94.8 端對端：實寄確認 email 文字只剩標題+觸發時間+觸發股價、圖含 KD（需實寄，待授權 / 自行按補發）


### Task 95: GDP+大盤頁日線圖支援「台股 / 美股四大指數」切換

對應 Requirements: Requirement 18（[requirements.md:368-386](spec/requirements.md)）

#### 背景

第三張卡原本固定顯示台股大盤（TAIEX）近 10 年日線 + 三均線。需求改為可在頂部下拉切換「台股大盤 / 道瓊工業 / 標普500 / 那斯達克綜合 / 費城半導體」五者，一次顯示一個指數，沿用同一套四線版型。美股四大指數＝DJI(`^DJI`)、SPX(`^GSPC`)、IXIC(`^IXIC`)、SOX(`^SOX`)。資料來源原評估 Stooq CSV 但實測在部署環境被擋（連 `aapl.us` 都回錯誤頁），改用 **Yahoo Finance v8 chart API**（`range=10y`，curl 子程序，與 `fetchUsHistoricalRange` 同 pattern）。台股流程完全不動，美股另建 `us_index_daily_history` 表，由同一支 BFF 以同一套 MA 計算服務兩市場。

#### Steps:

- [x] 95.1 Liquibase `v1.27.0-us-index-daily.sql` 建 `us_index_daily_history`（複合主鍵 `(index_code, trading_date)`、OHLC NUMERIC(14,4)）+ master include
- [x] 95.2 backend `UsIndexDailyHistory`（`@IdClass`）+ `UsIndexDailyHistoryRepository`（依 `indexCode` + 日期區間查詢）
- [x] 95.3 ext-materials-service `MacroDataFetchClient.fetchUsIndexDaily(code)`：code→Yahoo symbol，curl 取 `range=10y&interval=1d`，解析 timestamp(America/New_York)+OHLC → `List<DailyOhlc>`；`InternalPriceController` 加 `GET /internal/macro/us-index?code=`
- [x] 95.4 backend `MacroHistoryService.refreshUsIndexDaily(code)` 經 proxy upsert；`MacroHistoryController` 加 `GET /api/us-daily-index`、`POST /api/us-daily-index/refresh?code=`（code 白名單守門）
- [x] 95.5 BFF `GdpTwseBffController`：`twse-daily`/`refresh-twse-daily` 一般化為 `index-daily?market=`/`refresh-index-daily?market=`（TWSE→台股、其餘→美股 `code`）；MA 計算共用
- [x] 95.6 frontend `api/index.js` `gdpTwse`：`getTwseDaily`/`refreshTwseDaily` → `getIndexDaily(market, years)`/`refreshIndexDaily(market, years)`
- [x] 95.7 frontend `GdpTwseView.vue`：第三張卡加市場下拉（5 選 1），標題/空狀態/回補訊息隨選取指數動態；切換即重抓 BFF
- [x] 95.8 Docker 重 build + recreate（backend / external-materials-service / bff / frontend）後驗證：台股維持原樣；切到四個美股指數各自顯示近 10 年日線 + 三均線；按「回補日線」對美股指數成功 upsert


### Task 96: 指數日線圖新增「當日」分時走勢（盤中即時 / 盤後最後交易日）

對應 Requirements: Requirement 18（[requirements.md:368-390](spec/requirements.md)）

#### 背景

承 Task 95，第三張卡的指數圖區間鈕最前面再加「當日」：切到當日顯示該指數的分時走勢線。比照股票分析 Task 87/88 版型（x 軸 HH:mm、收盤單線、月/季/年線改水平參考線取日線最新值）。差異：指數（大盤＋美股四大）不在 Redis tick 輪詢名單，故不走 Task 88 的 tick store，改即時向 Yahoo v8 chart（`interval=5m&range=5d`，取最新交易日）抓取、transient 不寫 DB——盤中回今日部分 bar＝即時、盤後回最後完整交易日，自動滿足需求。

#### Steps:

- [x] 96.1 ext-materials `MacroDataFetchClient.fetchIndexIntraday(market)`：market→Yahoo symbol（含 TWSE→^TWII），curl 取 5m/5d，依 exchangeTimezoneName 轉當地時區、group by 當地日期取最新交易日，回 `List<IndexIntradayPoint(time ISO, close)>`；`InternalPriceController` 加 `GET /internal/macro/index-intraday?market=`
- [x] 96.2 backend `MacroHistoryService.fetchIndexIntraday(market)` proxy + 公開 record `IntradayPoint(time, close)`；`MacroHistoryController` 加 `GET /api/index-intraday?market=`
- [x] 96.3 BFF `GdpTwseBffController` 加 `GET /api/bff/gdp-twse/index-intraday?market=`：回 `tradingDate` + `times`(HH:mm) + `closes`
- [x] 96.4 frontend `api/index.js` 加 `gdpTwse.getIndexIntraday(market)`
- [x] 96.5 frontend `GdpTwseView.vue`：區間鈕最前加「當日」；isIntraday 時 x 軸 HH:mm、收盤＝分時 closes、月/季/年線＝水平線（取日線最新 MA）、不用 dataZoom；標題改「{指數} 當日走勢（YYYY-MM-DD）」；切當日 / 切市場時重抓分時
- [x] 96.7 修正當日走勢被壓平：Y 軸鎖定當日價格區間（分時收盤 min/max +10% padding），不用 `scale:true`（否則遠離當日價位的均線水平線把跨距撐成數千點，當日數百點起伏變平線）；均線水平線落區間外由 clip 裁切、數值仍留 legend
- [x] 96.8 X 軸延伸到收盤時間（非現在時間）：ext-materials `fetchIndexIntraday` 補滿交易時段完整 5 分格（美股 09:30–16:00 ET、台股 09:00–13:30），盤中未到時段 close 留 null；最後一筆現價 bar floor 對齊 5 分格；BFF 保留 null close（時間照常輸出）


### Task 97: 移除「台灣人均 GDP vs 台股大盤年末收盤」卡 + 清後端死碼

對應 Requirements: Requirement 18（[requirements.md:347-368](spec/requirements.md)）

#### 背景

頁面改名「股市分析」後，使用者要求移除第②張「台灣人均 GDP vs 台股大盤年末收盤（近 30 年）」雙 Y 軸圖。保留第①指數圖（日線/當日）與第③「台韓人均 GDP 比較」。盤點確認年末相關端點/entity 只被此鏈使用（無 dashboard 等其他消費者），故一併清除死碼；**DB 資料表 `twse_index_year_end_history` 保留不刪**（非破壞性）。第②卡的「回補資料」鈕同時餵第③卡 GDP 資料，故改為「回補 GDP（IMF）」鈕移至第③卡。

#### Steps:

- [x] 97.1 frontend `GdpTwseView.vue`：移除第②卡 template + `chartOption`/`twseSeriesData` computed + `twse`/`currentYearLastTradingDate`/`currentYear` state；`fetchData` 去掉 twse/年末欄位；「回補 GDP（IMF）」鈕移到第③卡、`onRefresh` 簡化為只回補 TWN+KOR GDP
- [x] 97.2 BFF `GdpTwseBffController`：`get` 移除 twse-year-end-index + twse-daily-index/latest 取值與 `twseYearEndClose`/`currentYearLastTradingDate` 欄位（Mono.zip 4→2）；`refresh` 移除大盤年末 + 大盤日線觸發（只留 TWN+KOR GDP）
- [x] 97.3 business `MacroHistoryController`：移除 `GET /api/twse-year-end-index`、`POST /api/twse-year-end-index/refresh`、`GET /api/twse-daily-index/latest` + `twseRepo` 欄位/import
- [x] 97.4 business `MacroHistoryService`：移除 `refreshTwseYearEnd` + `fetchTwseDecemberCloseProxy` + `twseRepo` 欄位/建構子參數/import
- [x] 97.5 ext-materials：移除 `InternalPriceController` `GET /internal/macro/twse-year-end` + `MacroDataFetchClient.fetchTwseDecemberClose`（`fetchTwseMonthlyDaily` 仍供日線回補，保留）
- [x] 97.6 移除 `TwseIndexYearEndHistory` entity + repository（`twse_index_year_end_history` 表保留不刪）
- [x] 97.7 Docker 重 build + recreate（frontend / bff / business-services / external-materials-service）後驗證：股市分析頁只剩指數圖 + 台韓 GDP 比較；第②卡消失；台韓 GDP「回補 GDP（IMF）」可運作；其餘頁面（dashboard 等）不受影響
- [x] 96.6 Docker 重 build + recreate（external-materials-service / backend / bff / frontend）後驗證：台股大盤切「當日」顯示分時（盤後為最後交易日、末點＝當日收盤）；美股指數盤中顯示即時分時；月/季/年線為水平線


### Task 98: 債券 ETF / 收益分配型 ETF 股利歷史 fallback（TaiwanStockDividendResult）

對應 Requirements: Requirement 13（[requirements.md:240](spec/requirements.md)）

#### 背景

使用者開啟 00751B（元大AAA至A公司債）股票分析 → 股利歷史頁籤顯示「查無資料」，但該檔實為季配息債券 ETF（每季約 0.35~0.38 元）。根因：`DividendFetchClient.fetchTw` 只打 FinMind `TaiwanStockDividend`（上市櫃公司盈餘分配表），ETF 的「利息 / 收益分配」不在此表 → 回空陣列 → cold-cache fallback 重抓仍空 → 查無。實測 FinMind：`TaiwanStockDividend` 對 00751B 回 `[]`，但 `TaiwanStockDividendResult`（除權息結果表）每季除息事件齊全。

#### Steps:

- [x] 98.1 ext-materials `DividendFetchClient`：抽出 `finmindData(dataset, code, years)` 共用 HTTP helper（帶 token、UA、identity encoding）；`fetchTw` 改用之
- [x] 98.2 `fetchTw`：`TaiwanStockDividend` 解析後若 `out` 為空 → fallback 呼叫新增 `fetchTwDividendResult(code, years)`：以 `date`=除息日、`stock_and_cache_dividend`=配息金額組 `DividendEvent`；`stock_or_cache_dividend` 含「權」且不含「息」→ 股票股利，其餘 → 現金股利；發放日留 null；金額 0 跳過
- [x] 98.3 Docker 重 build + recreate（external-materials-service）後驗證：開啟 00751B 股利歷史顯示近 10 年季配息列（含除息日昨收價、現金殖利率、年度小計）；既有個股（2330）與股票型 ETF（0056）股利歷史不變
      （驗證：`/internal/dividend/sync` 00751B written=28、2330 written=32、0056 written=19、9999 written=0；BFF `/api/bff/stock-analysis/dividends?code=00751B` 回 28 列、source=FinMind）


### Task 99: 美股非 NASDAQ ETF 股利歷史 fallback（Yahoo chart events=div）

對應 Requirements: Requirement 13（[requirements.md:240](spec/requirements.md)）

#### 背景

VOO（Vanguard S&P 500 ETF，NYSEARCA 上市）股利歷史顯示「查無資料」。根因：`DividendFetchClient.fetchUs` 只走 NASDAQ `/api/quote/{code}/dividends`，該 API 僅服務 NASDAQ 自家上市標的，對 NYSE / NYSEARCA（VOO、SGOV、SCHD、JEPI…）一律回 N/A → 空。Task 69 已為「殖利率」加 Yahoo fallback，但「股利歷史」未補。實測：NASDAQ VOO stocks=Symbol not exists、etf=全 N/A；Yahoo `chart?events=div` VOO 有完整 10 年除息事件。

#### Steps:

- [x] 99.1 `DividendFetchClient`：新增 `DividendFetchResult(source, events)` record，`fetch()` 改回傳之，逐筆 source 由實際資料源決定（FinMind / NASDAQ / Yahoo Finance），移除以市場別硬編的 `source(market)`
- [x] 99.2 `fetchUs`：NASDAQ 兩個 assetclass 都無 rows 時 → fallback 新增 `fetchUsYahoo(code, years)`：curl 取 Yahoo `chart?range={years}y&events=div`，解析 `events.dividends`（amount=現金配息、date=epoch 秒以 America/New_York 轉除息日），組 `DividendEvent`（無發放日）；NASDAQ 命中回 source=NASDAQ、Yahoo 命中回 source=Yahoo Finance
- [x] 99.3 `DividendPersister.syncOne` 改用 `fetched.events()` 與 `fetched.source()`（取代 `client.source(market)`）
- [x] 99.4 Docker 重 build + recreate（external-materials-service）後驗證：VOO 股利歷史顯示近年季配息列（source=Yahoo Finance）；NASDAQ 上市標的（如 QQQ）仍走 NASDAQ；台股不受影響
      （驗證：VOO→Yahoo Finance 37 列、SGOV→Yahoo Finance 71 列、QQQ→NASDAQ 38 列、00751B/2330/0056→FinMind 28/32/19 列。踩雷修正：Yahoo curl 必須用短 UA `Mozilla/5.0`，長 Chrome UA 被 Yahoo WAF 回 429 Too Many Requests）

### Task 100: 警示 email 寄送時段閘門（盤外市場不寄）

對應 Requirements: Requirement 23（[requirements.md:485](spec/requirements.md)）

#### 背景

警示觸發 email 由 `AlertNotificationDispatcher.flush()`（每 60s）寄出，原本只要 queue 非空就寄，不看當下時間。症狀：台股收盤超過 9 小時（深夜）仍可能因盤外 price-update 觸發而寄出台股警示信，與「到價盯盤」語意不符。需求：警示 email 只在各市場「開盤 ~ 收盤後 10 分鐘」寄；多市場混批時逐筆判定，台股盤外時只寄仍盤中的美股 / 英股。手動「補發」為使用者明確動作，不受此閘門限制。

#### Steps:

- [x] 100.1 `MarketZones`：開收盤時刻集中為單一來源，新增 `openTime(market)` / `closeTime(market)`（台 09:00/13:30、美 09:30/16:00、英 08:00/16:30）；`StockAlertService.computeTriggeredAt` / `matchInDailyOhlc`、`AlertNotificationDispatcher.lastTradingDate` 原本各自硬編的開收盤改吃此來源（行為不變，消除三處重複）
- [x] 100.2 `AlertNotificationDispatcher`：新增 `SEND_GRACE_MINUTES=10` 與 `withinSendWindow(market)`（市場時區平日、`open ≤ now ≤ close+10`；不考慮假日，與既有時間判定同口徑）
- [x] 100.3 `flush()`：drain 後以 `withinSendWindow` 逐筆過濾，盤外市場本輪丟棄（queue 不回填、`StockAlertTrigger` 歷史已落地）；全部盤外則 return 不寄；過濾掉幾筆時 `log.info`。`resendLastTradingDay()`（補發）不經此閘門
- [x] 100.4 spec：`requirements.md` Requirement 23 加「寄送時段閘門」AC、`design.md` flush 流程圖與邊界處理同步更新
- [x] 100.5 部署 + 邏輯驗證：Docker 重 build + recreate（business-services，healthy、BFF `/actuator/health` UP、啟動無錯）。以實際市場時鐘驗證閘門判定＝預期：台股 23:20（盤外）丟棄、美股 11:20 ET / 英股 16:20 London（盤中）寄出
- [ ] 100.6 端對端待觀察：實際於台股盤外時段發生台股觸發時，確認 log 出現「盤外時段略過 N 筆警示 email」、且該輪 email 不含台股（強制觸發會實寄信給設定收件人，故待自然觸發或使用者授權後驗證）

### Task 101: StockPriceService 開盤判斷委派 MarketZones（去重）

對應 Requirements: Requirement 23（[requirements.md:485](spec/requirements.md)）

#### 背景

Task 100.1 已把開收盤時刻集中進 `MarketZones`（`openTime/closeTime`），並讓 `StockAlertService` / `AlertNotificationDispatcher` 改吃單一來源；但 `StockPriceService` 仍自帶 `TW_ZONE/US_ZONE/LON_ZONE` 三個 `ZoneId` 常數，且 `isTwMarketOpen/isUsMarketOpen/isUkMarketOpen` 各自硬編開收盤時刻（台 09:00–13:30、美 09:30–16:00、英 08:00–16:30）＋平日判斷 —— 與 `MarketZones` 重複，違反 CLAUDE.md「相同的資料只能存一份」。本任務把這份「市場是否開盤」判斷也收斂進 `MarketZones`。純去重重構，判斷邏輯（`!t.isBefore(open) && !t.isAfter(close)`、週末關閉、含端點、無寬限分鐘）完全不變；注意與 Requirement 23 寄送閘門的 +10 分寬限窗語意不同，未把寬限帶進來。

#### Steps:

- [x] 101.1 `MarketZones` 新增 `isMarketOpen(market)`（市場時區、平日一～五、`!t.isBefore(openTime) && !t.isAfter(closeTime)`，含端點、無寬限分鐘）
- [x] 101.2 `StockPriceService` 三個 `isXxMarketOpen()` 改委派 `MarketZones.isMarketOpen("台股"/"美股"/"英股")`；移除自帶的 `TW_ZONE/US_ZONE/LON_ZONE` 常數；`getMarketStatus()` 的 `twTime/usTime/ukTime` 改引用 `MarketZones.*_ZONE`（`manualRefresh()` / `getLiveAssets()` 沿用這三個方法，無需改）
- [x] 101.3 spec：`design.md` MarketZones helper 說明補 `openTime/closeTime/isMarketOpen` 與 StockPriceService 委派；`tasks.md` 本任務
- [x] 101.4 `mvn compile` 通過（BUILD SUCCESS）
- [x] 101.5 Docker 重 build + recreate（business-services，healthy）後驗證 `/api/market-data/market-status` 三旗標行為不變：當下 TW 00:07（盤前）=false、US 12:07 ET（盤中）=true、UK 17:07 London（盤後）=false，與委派前硬編邏輯一致（開收盤值、平日判斷、含端點比較皆未變）

---

### Task 102: 指數「當日」走勢圖顯示昨日收盤 / 漲跌 / 漲跌%

對應 Requirements: Requirement 18（[requirements.md:347](spec/requirements.md)）

#### 背景

Task 96 指數圖「當日」模式只畫分時走勢與月/季/年線水平參考線，缺最關鍵的「相對昨收的當日漲跌」。需求：切到「當日」時，於卡片標題列顯示**昨日收盤、漲跌、漲跌%**（紅漲綠跌）。

關鍵設計：
- **昨收同一事實來源**：系統別處（觀察清單 0000 報價 `WatchStockService.toIndexResponse`）已從 `twse_index_daily_history` 取「倒數第二個交易日」當昨收。本任務昨收同樣讀日線表（台股 `twse_index_daily_history`、美股 `us_index_daily_history`），確保兩頁同義欄位值一致（CLAUDE.md「同義欄位同一來源」）。但 `WatchStockService` 只支援台股大盤 0000、且「現價」為 DB 日收盤（盤中不動），不適用本圖（5 市場、現價需用 intraday 即時點位），故不直接複用其 endpoint，改在 BFF 以同一張日線表計算。
- **計算放 BFF**（前端只 render）：`index-intraday` BFF endpoint 回傳增加 `previousClose` / `lastClose` / `change` / `changePercent`。
- **昨收對齊 intraday tradingDate**：以分時的 `tradingDate` 為基準，取日線表中「嚴格早於該日」的最後一筆收盤，不受「日線表是否已含當日 row」影響（盤中通常尚無今日、盤後已回補今日皆正確）。漲跌＝分時最新點位（`closes` 末筆非 null）− 昨收。

#### Steps:

- [x] 102.1 BFF `GdpTwseBffController.getIndexIntraday`：改以 `Mono.zip` 並行抓 intraday（原 `/api/index-intraday`）＋近 40 日日線 tail（台股 `/api/twse-daily-index?from=today-40&to=today`、美股 `/api/us-daily-index?code=&from=&to=`）；新增 `previousCloseBefore(daily, tradingDate)`（日線 asc，取 `tradingDate` 嚴格小於當日的最後一筆 `closePoint`）；body 增加 `previousClose` / `lastClose`（分時末筆非 null）/ `change` / `changePercent`（HALF_UP 2 位；昨收為 0 或缺值回 null）
- [x] 102.2 frontend `GdpTwseView.vue`：`fetchIntraday` 接 `previousClose/change/changePercent` 存 state；卡片標題列（`isIntraday` 且昨收非 null 時）顯示「昨收 X｜▲/▼漲跌｜+漲跌%」，紅漲(#dc2626)綠跌(#16a34a) 比照 `WatchStockView.priceColor`；指數點位格式千分位 2 位（不帶 $）
- [x] 102.3 spec：`requirements.md` Requirement 18 加 AC、`design.md` index-intraday 回傳格式 + 昨收計算說明、`tasks.md` 本任務
- [x] 102.4 `mvn -q compile`（bff module）通過
- [x] 102.5 Docker 重 build + recreate（bff / frontend）後驗證：台股大盤切「當日」標題列顯示昨收 + 漲跌 + 漲跌%、紅漲綠跌；切美股四大指數同樣顯示；漲跌 = 走勢圖末點 − 昨收


### Task 103: Dashboard 資產配置面板加「美股個股穿透前 10 大」tab

對應 Requirements: Requirement 9（[requirements.md:172-176](spec/requirements.md)）

#### 背景

承 Task 86「台股個股」穿透，使用者要平行的「美股個股」tab：把美股 ETF（VOO/QQQ/SPY…）穿透成成分股後與直接持股合併計算前 10 大個股。關鍵限制：美股 ETF 成分股只有 Yahoo `topHoldings`（前 10 大，權重總和常 30~50%），不像台股 MoneyDJ 給完整成分股，故穿透演算法必須與台股不同。

設計決策（與 Task 86 台股的差異）：
- **不正規化**：台股 MoneyDJ 給完整成分股，故 `cv × weight/Σweight` 正規化完全穿透；美股 Yahoo 只給前 10 大，若正規化會把未揭露的 50~70% 也按前 10 大比例攤入、嚴重高估 AAPL/NVDA 等權值股，故改依**真實權重** `cv × weight/100` 分配，未揭露尾段 `cv × (1 − Σweight/100)` 全數歸「其它」（使用者拍板：誠實優先，接受「其它」偏大）
- **代號聚合**：Yahoo 成分股有 `symbol`，且英文全名與持股中文名難對齊，故美股以 `stockCode` 聚合（台股 MoneyDJ 無代號故用股名）
- **ETF 判斷不在 BFF 重複維護白名單**：BFF 對每檔美股呼叫 etf-holdings，以「回傳 holdings 是否非空」區分 ETF / 個股；external `isEtf()`（`US_ETF_WHITELIST`）對非 ETF 代號短路回空、不打 Yahoo，個股呼叫成本低，白名單維持單一事實來源
- 不做 `degradedEtfs` 動態清單（白名單 ETF Yahoo 幾乎都有；改以 `lookthroughEtfCount > 0` 觸發固定註記涵蓋語意）

#### Steps:

- [x] 103.1 新增 `bff/.../dashboard/dto/UsStockLookthroughDto.java`（含 `Item` / `Others` 內部 class；欄位 snapshotDate / totalUsStockValue / items[] / others / lookthroughEtfCount）
- [x] 103.2 `DashboardBffController`：`fetchEtfHoldings` 加 `market` 參數（取代硬編「台股」，台股呼叫端傳「台股」零行為變更）；新增 `getUsStockLookthrough(snapshotId)` + `buildUsLookthrough`（篩美股 → 並行 concurrency 4 抓 etf-holdings → ETF 依真實權重分配、未揭露歸其它、個股整筆計入 → 以代號聚合 → top10 + others；`lookthroughEtfCount` 計成功穿透檔數）
- [x] 103.3 前端 `api/index.js`：`bffApi.dashboard.usStockLookthrough(snapshotId)`
- [x] 103.4 前端 `DashboardView.vue`：card header el-tabs 加 `usStock`；新 state `usLookthrough/usLookthroughLoading/usLookthroughCache`、`loadUsStockLookthrough`（race 防護沿用 `effectiveSnapshotId`）、watch 擴充、computed `usLookthroughHasData/usStockPieOption`（色盤沿用 `TW_PIE_COLORS`）；底部 `lookthroughEtfCount > 0` 時顯示固定註記
- [x] 103.5 spec：`requirements.md` Req 9 加 tab 3 AC、`design.md` 新增 `us-stock-lookthrough` endpoint、`tasks.md` 本任務
- [x] 103.6 `mvn -q compile`（bff module）通過
- [x] 103.7 Docker 重 build + recreate（bff / frontend）後驗證：切「美股個股」tab → 圓餅顯示前 10 大個股（VOO/QQQ 拆成 AAPL/MSFT/NVDA… 而非 ETF 代號）+ 「其它」；有 ETF 時底部出現註記；hover 趨勢圖節點連動；無美股部位顯示空狀態
- [x] 103.8 bug fix（external-materials-service）：實作驗證時發現 `MarketDataFetchService` 取 Yahoo crumb / quoteSummary（topHoldings）沿用長 Chrome UA，被 Yahoo 反 bot WAF 回 429（Too Many Requests），導致**所有**美股 ETF 成分股查無、穿透失效（同 IP 短 UA `Mozilla/5.0` 卻回 200）。新增 `YAHOO_UA = "Mozilla/5.0"` 常數，crumb prime（`fc.yahoo.com`）/ `getcrumb` / `yahooApiGet`（quoteSummary）三處 curl 改用之；`v8/chart` 端點維持長 UA（仍正常）。重建 external-materials-service 後 VOO/VT 正常回前 10 大成分股


### Task 104: 股市分析指數下拉新增海外四指數（英國 / 德國 / 韓國 / 日本）

對應 Requirements: Requirement 18（[requirements.md:379-392](spec/requirements.md)）

#### 背景

承 Task 95 / 96，指數日線 + 當日分時的市場下拉原為「台股大盤 + 美股四大指數」五項。使用者要再加入英、德、韓、日四個主要海外指數：英國富時 100（FTSE / `^FTSE`）、德國 DAX（DAX / `^GDAXI`）、韓國 KOSPI（KOSPI / `^KS11`）、日經 225（N225 / `^N225`）。

設計決策：
- **沿用 `us_index_daily_history` 同一條管線**（依 `index_code` 通用化），不另建表、不改 entity/repo/changelog → **零遷移**。表名與 `/api/us-daily-index` endpoint 名沿用（語意一般化為「海外指數」，避免大規模 rename 風險）。
- 日線 timestamp → 交易日改讀 Yahoo meta `exchangeTimezoneName`（取代 `fetchUsIndexDaily` 寫死的 `America/New_York`）：美股 daily bar timestamp 在開盤時刻（09:30 ET），轉 NY 與轉交易所時區同結果；但亞洲/歐洲指數 daily bar timestamp 在 UTC 午夜（＝當地開盤），用 NY 會把日期回退一日（東京 6/15→6/14）。改讀交易所時區後全市場一致正確（intraday 早已這樣處理）。
- 當日分時補格的交易時段改用 `INDEX_TRADING_HOURS` map（取代寫死的「TWSE 09:00–13:30 / else 09:30–16:00」）：FTSE 08:00–16:30、DAX 09:00–17:30、KOSPI 09:00–15:30、N225 09:00–15:30（東京證交所 2024-11-05 起收盤由 15:00 延至 15:30；午休 11:30–12:30 Yahoo 無 bar→留 null，屬預期）。
- BFF / business service / InternalPriceController 對非 TWSE 市場本就以 `code`/`market` 字串通用 passthrough，僅 `MacroHistoryController` 的 refresh 白名單需加新 code；其餘多為註解更新。

#### Steps:

- [x] 104.1 frontend `GdpTwseView.vue`：`MARKETS` 陣列加 4 項（FTSE「英國富時 100」/ DAX「德國 DAX」/ KOSPI「韓國 KOSPI」/ N225「日經 225」）
- [x] 104.2 ext-materials `MacroDataFetchClient`：`US_INDEX_YAHOO` 與 `INDEX_INTRADAY_YAHOO` 各加 4 個 symbol；`fetchUsIndexDaily` 改依 meta `exchangeTimezoneName` 轉交易日（default NY）；當日時段抽成 `INDEX_TRADING_HOURS` map（含 4 市場，未知 fallback 09:30–16:00）
- [x] 104.3 backend `MacroHistoryController`：refresh 白名單 `US_INDEX_CODES` 加 `FTSE/DAX/KOSPI/N225`；相關註解一般化為「海外指數」
- [x] 104.4 註解一般化（BFF `GdpTwseBffController`、`MacroHistoryService`、`UsIndexDailyHistory`、`InternalPriceController`）：將「美股四大指數 / {DJI,SPX,IXIC,SOX}」字樣補上新增的海外指數，避免誤導
- [x] 104.5 `mvn -q compile`（backend + bff + external-materials-service）通過
- [x] 104.6 Docker 重 build + recreate（frontend / bff / backend / external-materials-service）後驗證：下拉出現英德韓日四項；各市場按「回補日線（10 年）」後日線圖正常（日期不偏移）；切「當日」顯示當地時區分時走勢線與昨收/漲跌/漲跌%
- [x] 104.7 bug fix（external-materials-service）：實機驗證 N225「當日」走勢時發現後場最後半小時（15:00–15:30）被截掉、線在 15:00 就停——`INDEX_TRADING_HOURS` 的 N225 收盤誤設 15:00（舊制）；東京證交所 2024-11-05 起收盤延至 15:30（新增收盤競價），Yahoo 5m 確有 15:05–15:30 之 bar。改 N225 close 為 15:30 後線延伸至 15:30、收盤點位/漲跌取到真正收盤值。重建 external-materials-service 驗證


### Task 105: 海外指數日線自動回補排程（修當日走勢「昨收」過時 → 漲跌% 失真）

對應 Requirements: Requirement 18（[requirements.md:379-392](spec/requirements.md)）

#### 背景

實機回報：費城半導體「當日」走勢顯示「昨收 12,330.30 ▲1,711.47 **+13.88%**」，但盤中只在 13,894~14,041（實際約 +5%）。

根因（兩個來源新鮮度不一致）：
- 「當日走勢」的**現在點位**由 BFF 即時抓 Yahoo（`/api/index-intraday`，最新交易日）；**昨收**則由 `GdpTwseBffController.previousCloseBefore()` 從日線表 `us_index_daily_history` 取「嚴格早於當日的最後一筆」（Task 102 設計）。當日線表最新只到數日前，昨收就退回那筆舊收盤，與即時點位相減 → 漲跌% 爆量。
- **真正缺口**：`us_index_daily_history` 原本**只靠前端「回補日線（10 年）」按鈕手動觸發、無排程**。Task 104 新增的 FTSE/DAX/KOSPI/N225 是開發時剛手動 backfill 才最新；原有 DJI/SPX/IXIC/SOX 自上次手動回補日（實機 2026-06-10）後就停滯，缺 06-11/06-12/06-15。台股大盤日線由 external-materials `TwseIndexPoller` 排程顧著故一直最新，海外 8 指數缺對應排程。
- Task 102 原假設「日線未回補導致昨收**缺值**→ 回 null 不顯示」，但實際失效模式是「日線**過時**→ 昨收非 null 但是錯的舊值」，故當時的 null 守門擋不住。
- 觀察清單 0000（`WatchStockService.toIndexResponse`）用「同表相鄰兩筆」算漲跌，過時只會整體偏舊、不會爆 %；故此 bug 為「當日走勢圖」獨有（混即時 + 過時兩來源）。

設計決策：
- **不改昨收的事實來源**（維持 Task 102「昨收讀日線表、與觀察清單同義同源」），改為**讓日線表恆保最新**——新增排程自動回補海外 8 指數日線，比照台股 `TwseIndexPoller` 的角色。
- **重用既有路徑**：排程呼叫 `MacroHistoryService.refreshUsIndexDaily(code)`（＝手動按鈕同一條 Yahoo `range=10y` idempotent upsert），不另寫抓取 / 寫入邏輯（避免重複）。
- 代碼清單收斂為單一來源 `MacroHistoryService.OVERSEAS_INDEX_CODES`，`MacroHistoryController` 的 refresh 守門白名單改引用之（去除重複硬編）。
- 排程放 business-services（`backend`，已 `@EnableScheduling`），因回補與 upsert 邏輯在此服務；每日 07:00 Asia/Taipei（TUE-SAT，美股 16:00 ET 收盤後足夠緩衝，亞/歐/美最新交易日皆已收）+ 開機 self-heal（任一指數最新日期 > 4 日即補，處理服務於排程時點未運行）。

#### Steps:

- [x] 105.0 一次性修復：對停滯的 DJI/IXIC/SOX/SPX 觸發 `POST /api/bff/gdp-twse/refresh-index-daily`，8 指數日線補到最新交易日（畫面數字即時恢復正確）
- [x] 105.1 backend `MacroHistoryService`：新增 `public static final List<String> OVERSEAS_INDEX_CODES`（單一清單）
- [x] 105.2 backend `MacroHistoryController`：refresh 守門白名單改引用 `MacroHistoryService.OVERSEAS_INDEX_CODES`（去重）
- [x] 105.3 backend `UsIndexDailyHistoryRepository`：新增 `findTopByIndexCodeOrderByTradingDateDesc`（self-heal 取各指數最新日期）
- [x] 105.4 backend 新增 `IndexDailyRefreshScheduler`：`@Scheduled(0 0 7 * * TUE-SAT, Asia/Taipei)` 逐一回補 8 指數 + `@EventListener(ApplicationReadyEvent)` self-heal（過時 > 4 日才補；500ms Yahoo 間隔）
- [x] 105.5 spec：`requirements.md` Req 18 加「海外指數日線自動回補」AC + 昨收 AC 補註過時失效、`design.md` 海外指數日線排程說明、`tasks.md` 本任務
- [x] 105.6 `mvn -q compile`（backend）通過
- [x] 105.7 Docker 重 build + recreate（business-services）後驗證：SOX「當日」昨收 13,371.47（取代過時 12,330.30）、漲跌% +4.96%（取代 +13.88%）；business-services 啟動 log 出現 `IndexDailyRefreshScheduler：self-heal：海外指數日線皆為最新，略過`

---

### Task 106: 「警示」顯示窗由近 3 個交易日收斂為「最後交易日＋前一日」

**需求**：觀察頁／警示頁「警示（觸發時間／股價／季線／KD）」欄與「警示條件」紅字，原本顯示最近 **3** 個交易日內的觸發，導致收盤後數日仍殘留舊觸發（實機 6/17 仍顯示 6/12 09:30 NY 的觸發）。改為只保留「**最後一個交易日（或交易當日）及前一日**」，共 **2** 個交易日。

- 純調窗 + 去魔術數字，演算法（`recentTradingDayCutoff` 取最近 N 個 distinct `trading_date` 之較早一天午夜為下界、`triggeredAt >= cutoff` 才顯示）不變，僅 N 由 3 改 2。
- 顯示窗以 `stock_price_history` 的 `trading_date` 為準，故「交易當日」在收盤列寫入後即納入；今日盤中觸發（`lastTriggeredAt` = 今日）一律 ≥ cutoff，必顯示。
- 與 `stock_alert_trigger` 30 天歷史保留（補發／稽核）為**兩個獨立概念**，不更動。
- `StockAlertService` 另有一處「最近 3 個交易日」是 cron 漏抓時的盤中 5 分 K 回補 lookback（`scanIntradayForTrigger`），語意不同，**保留不動**。

#### Steps:

- [x] 106.1 backend `StockAlertService`：新增單一常數 `public static final int FRESHNESS_TRADING_DAYS = 2`（含 javadoc 說明顯示窗語意），`toResponse` 改用之
- [x] 106.2 backend `WatchStockService`：個股與 `0000` 大盤兩分支的 `recentTradingDayCutoff(market, 3)` 改 `(market, StockAlertService.FRESHNESS_TRADING_DAYS)`，兩頁共用單一來源（同義欄位同一來源）
- [x] 106.3 同步註解：`WatchStockDto.Condition.triggered`、`WatchStockView.vue conditionColor`
- [x] 106.4 spec：`requirements.md` Req 16 新增顯示窗 AC、`design.md` `WatchStockService` 補述、`tasks.md` 本任務
- [x] 106.5 `mvn -q compile`（backend）通過
- [x] 106.6 Docker 重 build + recreate（business-services + frontend）後驗證：美股分頁警示僅留最後交易日及前一日，6/12 等更早觸發消失

### Task 107: 國定假日休市仍抓價／觸發警示的全面修正（含 Juneteenth）

**需求**：實機 2026-06-19（美股 Juneteenth，週五）休市，但觀察頁／警示頁仍把 AMZN/MSFT 等條件標成「06/19 09:30 NY」觸發。根因：系統有三套各自獨立的「市場是否開盤」判斷，其中兩套（ext-materials `MarketClock`、business-services `StockAlertService.computeTriggeredAt` / `AlertNotificationDispatcher`）**只判週末、不查國定假日**；而完整含 Juneteenth 的假日表（`MarketDataService.getUsHolidays`）只被備份排程使用。週五 = 平日 → `isUsMarketOpen()` 在假日回 `true` → 照抓價寫 Redis、照觸發並用 `now()` 蓋上「09:30」。

**設計**：把三套判斷全部收斂到「交易日（平日 && 非假日）」單一入口。詳見 `design.md`「國定假日整段休市」與「交易日 / 國定假日判定（單一事實來源）」。台股假日 = TWSE holidaySchedule（唯一來源）；美 / 英假日 = NYSE / LSE 純函式，因兩服務無共用 library 且不可循環依賴而各持一份（交叉註解鎖定）。

#### Steps:

- [x] 107.1 ext-materials 新增 `MarketCalendar`：`isTwTradingDay/isUsTradingDay/isUkTradingDay`；台股委派 `MarketDataFetchService.getTwHolidays`，美 / 英為 NYSE / LSE 純函式（per-year 快取，交叉註解指向 backend `MarketDataService`）
- [x] 107.2 ext-materials `MarketClock`：注入 `MarketCalendar`，`isXxxMarketOpen` / `isXxxMarketJustClosed` 改為「`isXxxTradingDay(當日)` && 時段」，移除只判週末的 `isWeekend`
- [x] 107.3 ext-materials `ClosePersister`：注入 `MarketCalendar`，`selfHealMissedClose` 的 `isWeekday` 改 `isXxxTradingDay`；各 dump / verify 排程方法加假日早退守門
- [x] 107.4 ext-materials `PricePoller.refreshAll`：`markClosed` 由 `false` 改 `!clock.isXxxMarketOpen()`（與 `warmCacheOnStartup` 一致，假日手動刷新不 append 假 tick）
- [x] 107.5 backend `MarketDataService`：新增 `isTradingDay(market, date)` dispatcher 與 `isMarketOpenNow(market)`（`MarketZones` 時段 + `isTradingDay`）
- [x] 107.6 backend `StockAlertService`：注入 `MarketDataService`，`evaluate` live 觸發分支加 `isTradingDay(market, 市場時區今日)` 閘門（假日落到補抓分支）；`computeTriggeredAt` 由 `isWeekday` 改 `isTradingDay`
- [x] 107.7 backend `AlertNotificationDispatcher`：注入 `MarketDataService`，`withinSendWindow` 加假日回 false；`lastTradingDate` 迴圈以 `isTradingDay` 跳週末 + 假日；更新「不考慮假日」註解
- [x] 107.8 backend `StockPriceService`：注入 `MarketDataService`，`isXxxMarketOpen` 改委派 `isMarketOpenNow`，使 `getMarketStatus` / 交易日曆 / Dashboard 假日顯示「休市」
- [x] 107.9 spec：`requirements.md` Req 7 / 16 / 23、`design.md`、`tasks.md` 本任務
- [x] 107.10 `mvn -q compile` 兩服務皆通過
- [x] 107.11 Docker 重 build + recreate（business-services + external-materials-service）後驗證：假日不抓價、警示不觸發、市場狀態顯示休市

### Task 108: Dashboard 台股總值偏差修正（現值與「股價」欄不同源）

**需求**：總覽儀表板台股 tab 底部「目前總值」與各列「股價 × 股數」對不上、總值偏低。實機快照 2026-06-19，每列「股價」欄顯示 `stock_price_history` 收盤（如 0050 = 107.30，基準日查無 06-19 → 回退 06-18），但「現值 / 總值」沿用快照建檔當下凍結的 `currentValue`（隱含 06-17 的 106.00），15 檔台股總值 10,051,967 vs 用收盤重算的 10,176,102，差約 12.4 萬。`liveAssets` 早已用最新收盤算出 10,176,102（KPI 資產總計亦採用），唯獨表格 footer 與配置圖讀凍結值。

**根因**：BFF `SnapshotEnricher.buildMergedStocks` 中 `stockPrice` 取自 `closeMap`（stock_price_history），`currentValue` 卻沿用快照凍結值 —— 同義欄位不同源、不同日。違反 CLAUDE.md「股價一律從 stock_price_history 抓、禁存可計算的衍生值」。

**設計**：`buildMergedStocks` 新增 `revalueFromClose` 旗標；唯讀 Dashboard 傳 `true`，以 `closeMap` 重算 `currentValue = 股數 × 收盤價 ×（美股/英股）匯率`，連動 `estimatedDividend / profit / unitPriceTwd`，使「現值 = 股價 × 股數」自洽。前端 `liveLatest` 的 `sumOf(台股, twLive=false)` 會自動讀到重算後的 row.currentValue，故 KPI 股票現值 / 配置 donut / 市場小計同步對齊（closeMap 台股 == liveAssets 台股）。會存檔的編輯頁（SnapshotForm / SnapshotDetail，以 `unitPriceTwd` 反推 broker `currentValue` 存檔）維持 3-arg 預設 `false`，不被覆寫以免改寫歷史快照。

#### Steps:

- [x] 108.1 `SnapshotEnricher.buildMergedStocks`：新增 4-arg overload（`revalueFromClose`）；3-arg 委派預設 `false`。`revalueFromClose=true` 時依 `closeMap` 重算 `currentValue`（美股/英股乘 `usdExchangeRate`）與 `estimatedDividend`，`profit / profitRate / unitPriceTwd` 自然跟著新值
- [x] 108.2 `DashboardBffController`：4 個 call site（summary / snapshot / tw-stock-lookthrough / us-stock-lookthrough）改傳 `revalueFromClose=true`
- [x] 108.3 `SnapshotDetailBffController` / `SnapshotFormBffController`：維持 3-arg（`false`），編輯頁不重算
- [x] 108.4 spec：`requirements.md` Req 2/9、`design.md` SnapshotEnricher、`tasks.md` 本任務
- [x] 108.5 Docker 重 build + recreate（bff）後驗證：台股 footer 總值 10,051,967 → 10,176,102 == Σ(股價 × 股數) == liveAssets 台股；15 檔每列「現值 = 股價 × 股數」diff 全 0；美股 fx 換算正確；snapshot-detail 編輯頁仍回凍結值（unitPriceTwd 106.00 未被覆寫）

