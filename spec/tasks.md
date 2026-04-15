# Tasks

## Implementation Tasks

以下任務依功能模組分組，每個任務標注所對應的 Requirements，並列出具體實作步驟。任務之間若有依賴關係則以「前置任務」標注。

---

### Task 1: 專案基礎建設

**對應 Requirements:** 所有 Requirements 的基礎

#### Steps:

- [x] 1.1 建立 Spring Boot 專案結構（com.steven.assets）
  - 建立 `AssetManagementApplication.java` 啟動類別
  - 設定 `pom.xml`（Spring Boot 3.4.4, Java 21, JPA, PostgreSQL, H2, Apache POI, Lombok）

- [x] 1.2 設定資料庫連線
  - `application.yml`：H2 記憶體資料庫（開發環境）
  - `application-postgres.yml`：PostgreSQL（生產環境）
  - 設定 JPA `ddl-auto=update`

- [x] 1.3 建立 Vue 3 前端專案
  - 初始化 Vite + Vue 3 專案
  - 安裝套件：Element Plus、ECharts、Axios、Pinia、Vue Router
  - 設定 `vite.config.js`（自動匯入、元件自動註冊）

- [x] 1.4 設定 CORS（`WebConfig.java`）
  - 允許來源：`http://localhost:5173`
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

- [x] 2.3 建立 Spring Data JPA Repository（共 12 個）
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

### Task 4: Excel 批次匯入

**對應 Requirements:** Requirement 5
**前置任務:** Task 3

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

- [x] 4.6 新增 `POST /api/snapshots/import` endpoint
  - 接受 `multipart/form-data`（file + overwrite 參數）

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
  - `POST /api/realized-gains/import`

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
  - 刪除 `BankName.java` 與 `Broker.java` Enum

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
  - 刪除 `DepositType.java` 與 `Market.java` Enum

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

### Task 13: 文件與部署

**前置任務:** Task 1–12

#### Steps:

- [x] 13.1 建立 README.md
  - 專案簡介與功能說明
  - 本地開發環境設定步驟（`start.sh` 說明）
  - Docker Compose 部署說明
  - Excel 格式規格說明
  - 完整 API 端點列表

- [x] 13.2 建立 `spec` 文件（本文件集）
  - `requirements.md`：需求與驗收條件
  - `design.md`：系統架構與資料模型（已與實作同步）
  - `tasks.md`：實作任務清單（本文件）

- [x] 13.3 環境變數文件
  - 建立 `.env.example` 說明所有必要環境變數
  - 建立 `.gitignore`，確認 `.env` 已排除版本控制

- [ ] 13.4 資料庫遷移策略
  - 評估從 `ddl-auto=update` 遷移至 Flyway/Liquibase
  - 建立初始 schema migration script（含 Bank / Broker Seed Data）
