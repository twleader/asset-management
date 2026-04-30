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
  - 欄位：股名/股號、股價、漲跌、漲跌幅(%)、買進、賣出、開盤、昨收、最高、最低、成交量(張)、警示
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

### Task 39: 股價基準日規則改 per-market（修正美股盤中誤顯示前一交易日收盤）

對應 Requirements: Requirement 9（儀表板基準日股價）、Requirement 7（市場資料整合）

#### 背景

`SnapshotEnricher.isCurrentBasedate(basedate)` 原本只比對 `basedate == LocalDate.now(Asia/Taipei)`，
不分市場。實際使用者多在 TW 收盤後建快照（basedate = TW 那天），但美股 session 跨午夜（TW 21:30 → 隔日 05:00），
TW 已過午夜後 `basedate（昨天）== TW 今日（今天）` 必失敗 → Dashboard 走 `closeMapToPriceList(closeMap, basedate)`
鎖死在 basedate 收盤價，即使後端 cache 裡美股已是盤中即時價，畫面仍顯示前一交易日收盤。

#### Steps:

- [ ] 39.1 `SnapshotEnricher` 新增 `isCurrentBasedate(LocalDate basedate, String market)` per-market 多載：
        台股比對 `Asia/Taipei`、美股比對 `America/New_York`（EST/EDT 由 JVM `ZoneId` 自動處理）；
        舊單參數版本移除（呼叫端全部改為帶 market 的新版）
- [ ] 39.2 `DashboardBffController` 不再對整批 stockPrices 做 all-or-nothing 替換；改為 **per-stock**：
        每筆 price 依其 market 套 `isCurrentBasedate(basedate, market)` 決定保留 live 或換成 basedate 收盤
- [ ] 39.3 `SnapshotFormBffController.enrichBatch` 同樣 per-stock：每 row 依 market 各自決定 useLive
- [ ] 39.4 `closeMapToPriceList` 改為依需求 build 單筆 / 單市場版本（或在呼叫端按 market 篩選 closeMap）

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

### Task 45: 管理資產（SnapshotForm 編輯模式）股價一律基準日收盤

對應 Requirements: Requirement 1（資產快照管理）

#### 背景

SnapshotFormView 編輯模式（`/snapshots/:id/edit`，標題「管理資產」）股票表格的「股價」欄
目前共用 `/api/bff/snapshot-form/prices` 並套 per-market `isCurrentBasedate` 規則：
若所選快照的 basedate == 今日（市場時區），會回傳即時 price + priceChange。
但管理資產為純歷史檢視/編輯，價格隨盤中跳動會干擾使用者；應一律以基準日收盤為準（與 SnapshotDetail 一致）。

新增模式（`/snapshots/new`，標題「新增快照」，basedate=今日）保留即時價，使用者建檔時看活價。

#### Steps:

- [ ] 45.1 `SnapshotFormBffController.batchPrices` 加可選 query `historicalOnly`（預設 false），
        傳入 `enrichBatch`；true 時強制 `useLive=false`、`priceChange/changePercent=null`
- [ ] 45.2 `frontend/src/api/index.js` `snapshotForm.prices(date, stocks, historicalOnly=false)`
        新增第三參數，true 時帶 `historicalOnly=true` query
- [ ] 45.3 `SnapshotFormView.vue` 所有 `bffApi.snapshotForm.prices(...)` 呼叫處改帶 `isEdit.value`
        （`fetchPriceForRow` / `fetchPrice` / `loadHistoricalPrices` / 複製前一版股票補價 / `loadPricesForExistingStocks`）
- [ ] 45.4 `enrichBatch` 當 basedate==今日（市場時區）時改優先用 live cache 的價（盤後 = 當日收盤），
        不再依賴 `stock_price_history` 是否已匯入當日。修正「basedate 4/28 但顯示 4/27 收盤」的問題：
        live cache 在盤後即有 4/28 收盤，歷史表通常要再過幾小時才匯入。`historicalOnly=true` 時仍不回傳漲跌

### Task 46: 美股漲跌幅錯誤 — `previous_close` 改用歷史表權威值

對應 Requirements: Requirement 7（市場資料整合）、Requirement 9（儀表板基準日股價）

#### 背景

`StockPrice.priceChange / changePercent`（@Transient）= `price - previousClose`，
`previousClose` 寫入時優先取自 Yahoo `regularMarketPreviousClose`。觀察到 Yahoo 在週末 / 美股盤外
回傳異常舊值（NVDA 4/28 收到 4/22 close = 202.50；正確應為 4/27 close = 216.61），
造成 Dashboard 顯示 NVDA ▲5%、GOOGL ▲3.61% 等明顯錯誤的漲跌幅。

歷史表（`stock_price_history`）由我們自家的收盤紀錄 cron 寫入，是權威值；應一律以此為準。

#### Steps:

- [ ] 46.1 `StockPriceService.persistPrice` 改用 `historyRepo.findClosestPrice(code, market, tradingDate.minusDays(1))`
        覆寫 `previousClose`；找不到歷史時才退回資料源提供的值；都沒有就維持 null

### Task 47: 抓股價拆出獨立微服務（price-service + Redis live cache）

對應 Requirements: Requirement 7（市場資料整合）

#### 背景

抓價邏輯（外部 API、cron、市場時段）目前內嵌於 `business-services`，外部 API 失敗會拖垮主服務、無法獨立 scale；
且 live 行情寫在 `stock_price` 表、收盤寫 `stock_price_history`，兩處 cache 容易漂移。
拆成獨立微服務 `price-service`，盤中 2 分鐘抓價寫 Redis，盤後寫 DB；`business-services` 只當消費者，
live 行情先讀 Redis，miss fallback 到 `stock_price_history` 最近一筆。

#### Steps:

- [ ] 47.1 新增 Maven module `price-service/`：pom（spring-boot-starter-web + data-redis + jpa
        + lettuce-core + 共享 model jar），自己的 `Application.java`
- [ ] 47.2 從 `backend/` 搬 `MarketDataService` 抓價相關（TWSE mis、NASDAQ info、FinMind close）、
        `StockPriceService.scheduledTw/UsIntradayUpdate`、`recordTw/UsClosingPrice`、
        `isTwMarketOpen/isUsMarketOpen`、`resolveTradingDate` 至 price-service
- [ ] 47.3 新增 `PriceCacheWriter`：序列化 price JSON 寫入 Redis key `price:{market}:{code}`
        TTL 600s；同步寫入 `price:index:{market}` set；`market:status` key TTL 90s
- [ ] 47.4 新增 `ClosePersister`：盤後 cron 觸發，對 stock 主檔每筆呼叫 close 來源、寫 `stock_price_history`
- [ ] 47.5 新增 `InternalPriceController`：`POST /internal/refresh` 同步抓價並寫 Redis 後回 200
- [ ] 47.6 `backend/` 端：加 spring-boot-starter-data-redis 依賴；新增 `PriceQueryService`：
        `getLive(market, code)` 先讀 Redis，miss fallback `stock_price_history` 最近一筆收盤；
        `getAll()` 透過 `price:index:*` 列舉
- [ ] 47.7 `MarketDataController` 的 `/api/market-data/prices`、`/live-assets`、`/market-status`、
        `/prices/refresh` 改打 `PriceQueryService`；`/prices/refresh` 走 WebClient 呼叫
        `http://price-service:8080/internal/refresh` 後再從 Redis 回讀
- [ ] 47.8 `Dockerfile` for price-service（同 backend multi-stage）；docker-compose 新增
        `price-service` container，depends_on postgres + redis；business-services depends_on redis
- [ ] 47.9 Liquibase changelog `1.x.x` drop `stock_price` 表；移除 `StockPrice` Entity、
        `StockPriceRepository`；其他引用點改用 `PriceQueryService` 回傳的 DTO
- [ ] 47.10 build 全套 image、docker compose up，smoke test：
        - `redis-cli KEYS 'price:*'` 應有資料
        - `GET /api/market-data/prices` 仍正常回傳
        - `POST /api/market-data/prices/refresh` 觸發後 Redis 內容更新
        - Dashboard / WatchStock / SnapshotForm 顯示股價無回歸

### Task 48: Live 股價即時推送（Redis pub/sub + SSE，取代前端 polling）

對應 Requirements: Requirement 7（市場資料整合）、Requirement 9（儀表板總覽）

#### 背景

前端 2 分鐘 polling + price-service 2 分鐘 cron 的相位差可能讓最壞情況價格 lag 達 ~4 分鐘。
改用 Redis pub/sub：price-service 寫入 Redis 同時 PUBLISH，business-services 訂閱後透過 SSE
推到前端，前端 EventSource 即時收到、零輪詢。

#### Steps:

- [ ] 48.1 price-service `PriceCacheWriter.write()` 寫完 Redis SET 後，
        `redis.convertAndSend("price-update", json)` 發布同一份 payload
- [ ] 48.2 backend 新增 `PriceStreamService`：`Sinks.Many<String>` fan-out sink；
        新增 `RedisSubscriberConfig` 啟動 `RedisMessageListenerContainer` 訂閱 `price-update`
        channel，把 message body 餵給 sink
- [ ] 48.3 backend `MarketDataController` 新增 `GET /api/market-data/prices/stream`
        回 `Flux<ServerSentEvent<String>>`，從 sink 即時串流
- [ ] 48.4 bff 新增 passthrough route `/api/bff/market-data/stream` →
        `/api/market-data/prices/stream`
- [ ] 48.5 frontend nginx config：`location /api/bff/market-data/stream { proxy_buffering off; ... }`
        避免 SSE 被 buffer 卡住
- [ ] 48.6 `DashboardView.vue`：把 setInterval 改成 `new EventSource(...)`；onmessage 解析
        JSON 並更新 stockPrices reactive map；onerror 自動 reconnect。初始載入仍走
        `/api/bff/dashboard/summary`，後續增量更新走 SSE

### Task 49: 股利歷史快取至 DB（每日同步，UI 直讀 DB）

對應 Requirements: Requirement 7（市場資料整合）、Requirement 13（股票走勢圖延伸資訊）

#### 背景

`StockAnalysisDialog` 的股利歷史分頁每次開啟都打 FinMind，慢且耗 quota。
改為每日由 cron 從 FinMind 抓寫進 `stock_dividend_history`，前端只讀 DB。

#### Steps:

- [ ] 49.1 Liquibase changelog `v1.13.0`：新增 `stock_dividend_history` 表
       （PK id，唯一鍵 (stockCode, market, year, COALESCE(exDividendDate, epoch))）
- [ ] 49.2 新增 `StockDividendHistory` Entity + `StockDividendHistoryRepository`
- [ ] 49.3 新增 `DividendHistoryService`：
        - `@Scheduled cron "0 0 17 * * MON-FRI"`：iterate `stock` 主檔，呼叫
          `MarketDataService.getDividendHistory(10)` 後 upsert
        - `findFromDb(code, market, years)`：DB 查詢，DB 空一次性 fallback 抓+寫
        - `@EventListener(ApplicationReadyEvent)`：背景補齊主檔中尚無資料的股票
- [ ] 49.4 `MarketDataController /dividends` 改呼叫 `DividendHistoryService.findFromDb`
