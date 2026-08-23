# Structure Steering — 資產管理系統

> **用途：** 本文件由 AWS KIRO SDD 在每次對話中載入，提供「程式碼放在哪、命名規則、模組界線」的長期 context。本文件描述穩定的目錄結構與慣例；新增檔案時請依此放置。

---

## 1. Repository Top-Level

```
asset-management/
├── backend/                       # business-services（領域邏輯 + JPA）
├── bff/                           # BFF（Spring Cloud Gateway + 聚合 controller）
├── external-materials-service/    # 抓價 / NAV / 配息子系統
├── fubon-broker-service/          # 富邦 proprietary SDK 的 Python/Linux 唯讀 adapter
├── api-gateway/                   # Docker 外部唯讀 API 的 Nginx exact allowlist
├── frontend/                      # Vue 3 SPA
├── db/
│   ├── init/                      # 容器初始化 SQL（01_dump.sql 含真實資料，gitignored）
│   └── schema.sql                 # schema-only 鏡像（離線參考用，**不是可信基準線**——靠人工重新
│                                  #   產出，實測已落後：截至 Task 245 仍缺 crawler_export_setting
│                                  #   與 asset_transaction。DB 現況一律查運行中的 DB：
│                                  #   docker exec asset-postgres psql -U assets -d assets -c '\d <table>'）
├── data/                          # H2 本機開發 DB（gitignored）
├── scripts/                       # 維運腳本 + git hooks
│   ├── db-export.sh
│   ├── db-import.sh
│   ├── git-hooks/
│   │   └── commit-msg             # 強制 SDD 同步（只驗 spec/ 有無變更）
│   └── spec-check.sh              # spec 變更的機械前置檢查
├── spec/                          # ⭐ SDD 規格文件（單一真實來源）
│   ├── requirements.md            # User Stories + AC
│   ├── design.md                  # 架構 / ERD / API
│   ├── tasks.md                   # 任務索引 ＋ 尚未歸檔區段
│   ├── tasks/                     # 任務檔（archive/ 歷史、tNNN_*.md 新制）
│   └── steering/                  # ⭐ 長期 context（本檔所在處）
│       ├── product.md
│       ├── tech.md
│       └── structure.md
├── .claude/                       # skills（run-stack / commit-merge-push / spec-review）、worktrees
├── docker-compose.yml
├── start.sh
├── .env.example                   # （.env 本身 gitignored）
├── AGENTS.md
├── INSTALLATION.md                # 搬機安裝手冊
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
    │   │   └── export/                        # 匯出中介模型 ExportDoc ＋ 兩個 renderer ＋ 雙檔落地（Req 55）
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
| `TwseIndexDailyHistory` / `TaiwanGdpPerCapitaHistory` / `KoreaGdpPerCapitaHistory` | 對應 Repository | `MacroHistoryService` |

> `TwseIndexYearEndHistory` entity / repository 已於 **Task 97 移除**（`twse_index_year_end_history` 資料表保留不刪）。

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
2. **前端頁面 BFF 路徑前綴：** `/api/bff/{page-name}/...`。Gateway route 將 `/api/bff/{page}/**` rewrite 為 `/api/{resource}/**`。
   - **具名、限縮例外（Requirements 67／68／70／71／78／79／86；Tasks 317／325／327／329／337／338／347）**：BFF 只對匿名唯讀的 exact `GET /api/public/market-index`、`GET /api/assets/latest`、`GET /api/public/exchange-rate/usd-twd`、`GET /api/public/market-analysis/today`、`GET /api/public/portfolio-advice/latest`、`GET /api/public/trading-radar/today`（後三條依序由 Requirements 79／86 新增；`portfolio-advice/latest` 與 `trading-radar/today` 都是 owner-scoped，固定走 configured-admin bootstrap 並顯式帶 tenant header；交易雷達公開讀取不得保存 export snapshot），以及 Requirement 71／Task 329 新增的 exact `POST /api/public/crawler-data/rescan`（唯一有外部抓取副作用的例外，經 business 端 30 秒全域 Redis 冷卻節流，語意等同既有 ADMIN 端點「立即抓取並匯出」的匿名版本）放行；Docker host 必須經 Requirement 66／Task 328 的 Nginx `api-gateway` `127.0.0.1:9090`，BFF 本身不發布 host port。Quotes 由 gateway 直接送 external-materials，不在 BFF 建 route。禁止任何 wildcard／descendant、同路徑其他 method與前端 view 援引；Controller 仍只委派 service，BFF 不直查 DB 或外部行情。每個 9090 exact path/method 都必須在 `docs/openapi/docker-external-api.yaml` 有同 commit 的完整 OpenAPI 3 operation，gateway 與文件 path/method 集合必須機械相等。USD/TWD 的每 2 秒外部抓取與 live Redis producer只屬於 `external-materials-service`，business/BFF 僅唯讀 cache/DB 與聚合。
   - **具名、限縮的行情五檔資料來源例外（Requirement 87／Task 348）**：登入後共用 `StockAnalysisDialog` 的「行情五檔」可在首次切頁或明確重新整理時，經 exact `/api/bff/stock-analysis/quote-detail` → business proxy → external request-time 取得 Yahoo 台股摘要＋orderbook 的單一展示 snapshot。它不是權威即時價，不能供估值、損益、下單、警示、SSE、`/api/quotes*`、9090 公開 API 或其他頁面／consumer 使用；不得寫 Redis／DB或背景輪詢。business 只能 proxy／fail-soft，外部 IO 與 HTML 解析仍只在 `external-materials-service`；該頁籤以外的即時價仍只走 Redis、收盤價仍只走 `stock_price_history`。
3. **跨頁共用邏輯放 `bff/common/`。** 如 `SnapshotEnricher`（注入歷史收盤價、合併 broker rows）。
4. **同義欄位 → 同一支 business service API。** BFF 不在不同頁重複呼叫不同 endpoint 取同義值。
   - **具名例外第一組（Task 285／286）：台股大盤的均線（MA5/20/60/240）目前有三份實作**——
     (1) 「股市大盤查詢」頁圖表走 `MarketIndexChartService.movingAverage`（BFF，BigDecimal，只用已落地日線收盤）；
     (2) 該頁匯出走 `ExcelExportService.indexMaAt`（backend，BigDecimal，同樣只用已落地日線收盤，
     Task 285 建立 MA5、Task 286 擴為四個視窗）；(3) 交易雷達／走勢圖／觀察清單走
     `TechnicalIndicatorService`（double，**盤中併入 Redis 即時點位**）。(1)(2) 定義相同（皆不含 live），
     彼此以「同定義同精度」保證同值，只是分屬 BFF／backend 兩處各自實作；(3) 因語意不同（含 live）另計。
     **擋住把 (1)(2) 收斂進 (3) 的是語意不是技術**：`TechnicalIndicatorService` 只覆蓋 `0000`＋`台股`
     （本頁另 9 個 code-keyed 指數：TPEX＋8 個海外指數，皆無分支），且會併 live——本頁圖表的另外三條線與匯出檔一律不含 live，
     換過去會讓同一張圖上出現兩種口徑。兩路徑對 `numeric(12,2)` 的台股收盤**算術上逐位相同**
     （和除以 5 恆為第三位小數為偶數的三位小數，碰不到 HALF_UP 邊界；50 萬組樣本實測 0 次不一致），
     故實際差異只出現在盤中、且來自 live 併入。**收斂的正解**是先決定「MA 要不要併 live」
     再統一 `TechnicalIndicatorService` 的算術路徑，屬獨立任務。
     詳見 `spec/design.md` 的 Requirement 45「週線MA5：唯一的計算欄」。
   - **具名例外之二（Task 335 登記，Task 336 收斂，狀態：已收斂）：IXIC（那斯達克綜合指數）的
     均線 MA5/20/60/240 有三份顯示用實作。** (a)「股市大盤查詢」頁圖表走 `MarketIndexChartService
     .movingAverage`（BFF）；(b) 交易雷達美股大盤分頁走
     `TechnicalIndicatorService.computeAllForNasdaq()` → `nasdaqSimpleMa`（backend）；(c) 該頁匯出的
     四條均線欄走 `ExcelExportService.indexMaAt`（backend——`findIndexDaily` 只有 `TWSE` 一條分支，
     其餘一律走 `us_index_daily_history`，`MacroHistoryService.DAILY_INDEX_CODES` 含 IXIC，
     **它確實會產出 IXIC 均線，不要誤記成只有台股**）。三者同讀 `us_index_daily_history` 的
     `index_code='IXIC'` 已落地日線收盤、都不併即時價。
     **免罪理由與 (1)(2) 同類——同定義同精度、分屬三處各自實作；但每一對的理由必須各自成立。**
     (a) 對 {(b),(c)} 是**建置結構**：`bff` 是獨立 Maven artifact `asset-management-bff`、`pom.xml`
     不依賴 backend，全 repo 三個 `pom.xml` 無 aggregator、無共用程式模組，跨不過去。
     **(b) 對 (c) 不適用建置結構這個理由**——兩者同在 `backend` 這一個 module、同一個 Spring
     context，寫成「無共用模組故無法收斂」是錯的（本鐵則最下方那句「引用例外時須逐條核對免罪理由
     是否真的適用」指的就是這種事）。(b)–(c) 真正的理由是**呼叫形狀與型別不同**：(b) 吃
     `List<UsIndexDailyHistory>`（desc）只回最新一期單值、(c) 吃 `List<IndexDailyRow>`（asc）
     逐點回整段序列；抽成共用 `List<BigDecimal>` primitive 技術上可行，**本組明文登記為已知技術債**，
     不是結構限制。（原本擋住 backend 內部合併的兩個理由中，「live 併入語意」那一半在 (b) 收斂後不再適用；「覆蓋率」那一半仍成立（(b) 只覆蓋 IXIC、(c) 現覆蓋 10 個指數），已含在上述呼叫形狀理由內。）
     **本組的等值強度高於 (1)(2)**：
     (a)(c) 本就是 BigDecimal 精確和後 `divide(window, 2, HALF_UP)`，Task 336 把 (b) 也改成同一路徑，
     三者等值**由構造保證**（同一批收盤、同一視窗、同一捨入；BigDecimal 加法可結合，累加方向不影響
     結果），不像 (1)(2) 仰賴「50 萬組樣本實測 0 次不一致」的抽樣論證。
     **另有一份非顯示用的第四處，刻意未收斂：** `BacktestService.buildUsMarketRegimes()` 也以 IXIC
     日線算 MA 餵進同一支 `evaluateMarket()`，但走 `RadarInputAssembler.assemble` →
     `computeFromSeries` → **`simpleMa`（double）**。收斂它必須改 `simpleMa`，而那一支服務全部個股、
     會翻動所有個股的 `action`／`score`，故 Task 336 明文留為範圍外。**殘餘分歧已量化**：兩條路徑
     double 累加方向相同（皆 desc、新→舊），故下段的重放結果直接適用——`price.compareTo(ma)`
     正負號翻動 0 日，2319 個可重放交易日中回測與 live 的美股 `regime` 0 日不同【**Requirement 83／Task 342 起不再成立**：量能兩欄已於該任務接進 production 與回測兩側，跨市場再多一個 applicability component。此結論只涵蓋 MA 路徑、**不再涵蓋量價欄**；後續重放或重跑 V13 校準須以升版後的實際欄位重新界定範圍】，分歧上限為 MA 的 0.01。
     **查這一組時 grep 必須用 `grep -ran "IXIC" backend/src/main/java/ bff/src/main/java/`**：
     只 grep `nasdaqSimpleMa`／`computeAllForNasdaq` 找不到 `BacktestService`（兩個識別字都沒有），
     而不加 `-a` 的 `grep -r` 會把部分 `.java` 判為 data 整檔靜默跳過。
     **不得沿用 (3) 或 (1)(2) 的原理由。** (3) 的「語意不同、含 live」在此不成立——
     `computeAllForNasdaq()` 刻意不併即時價（IXIC 無對應 Redis 即時報價來源，見
     `TechnicalIndicatorNasdaqTest` 的同名斷言）；(1)(2) 的「和除以 5 恆為第三位小數為偶數而碰不到
     HALF_UP 邊界」前提是台股收盤 `numeric(12,2)`，而 `us_index_daily_history.close_point` 是
     **`numeric(14,4)`** 且四位小數不是名目（實測 2559 筆 IXIC 日線中 2248 筆帶滿 4 位）。
     **收斂前的實際分歧（Task 336 實測，留作歷史證據）：** 收斂前 (b) 是 double 累加後
     `BigDecimal.valueOf(sum/days).setScale(2, HALF_UP)`，精確商恰為 `x.xx5` 時會與 (a) 差 **0.01**；
     2016-06-10~2026-08-14 的 2559 筆 IXIC 日線中 MA20 命中 2 日（2018-07-17、2025-12-22），
     MA5／60／240 各 0 日。因收盤價從未落在兩個候選 MA 之間（歷史最小 `|收盤 − MA|` 為 MA20
     `0.1502`／MA60 `0.2901`／MA240 `1.6999`，均為 0.01 的 15 倍以上），2319 個可重放交易日的
     `score` 與 `regime` 皆 0 日翻動，故 Task 336 不升 `RULE_VERSION`——但**援引的不是 Task 249**：
     顯示值 `usMarket.monthlyMa` 那 2 日確實會變，不滿足 Task 249 的「輸出完全相同」，也不滿足
     Task 281 的「不進 `MarketInput`」。Task 336 因此在 `spec/design.md` 的先例清單新增**第三條**
     判準（規則引擎輸出逐位不變、變動的僅是顯示值本身的捨入缺陷修正）。三條互斥、不得混用。
     **不得因此推論翻面「不可能」**：收盤價為 4 位小數而 MA 為 2 位小數，若某日收盤落在兩候選之間，
     `priceVsMa` 差值是 2×權重（MA240 達 30 分）、足以翻動 regime；那是經驗上未發生，不是結構上不可能。
     **台股那組（(1)(2)(3)）仍未收斂**，其正解仍是先決定「MA 要不要併 live」再統一
     `TechnicalIndicatorService` 的算術路徑，屬獨立任務——Task 336 刻意只改 `nasdaqSimpleMa` 一支，
     同類別的 `simpleMa`／`taiexSimpleMa`／`maAt` 三支 double 累加版維持不動。
   - **具名例外之三（Task 342 登記，狀態：併存，已量化確認同值）：IXIC 大盤「完成日量比」
     （`MarketInput.marketVolumeRatio`）有兩份實作。** production 走
     `TradingRadarMarketContextService.ratio()`，回測（`BacktestService.buildUsMarketRegimes()`）
     走 `RadarInputAssembler.volumeRatio()`。**兩支演算法已逐項比對確認相同**：lookback 20、
     最少 10 筆正成交量樣本、取中位數（偶數筆時 `divide(2, 8, HALF_UP)`）、最終
     `divide(median, 4, HALF_UP)`、分母一律排除最新日。IXIC 為指數、不存在股票分割還原問題，
     故無正確性風險。**為何 Task 342 沒有收斂：** 該任務的射程是「把已算出的量比接進評分」，
     其 spec 明文禁止把回測改呼叫 `resolveMarketFromRows`（不在授權範圍），且反向要求回測
     沿用既有的 `a.volumeRatio()`——那是為了同批消除「production 有值、回測傳 null」這個**更嚴重**
     的分岔（回測分支會在線上生效、回測不生效且無測試可抓）。收斂兩支 helper 屬獨立任務。
   - **具名例外之四（Task 356 登記，狀態：併存、刻意不合併）：ISO 週分桶有兩份實作**——
     `bff/.../stockanalysis/ChartSeriesAligner#weekly`（走勢圖週K，Task 355）與
     `backend/.../service/WeeklyBarAggregator#aggregate`（交易雷達週K，Task 356）。兩者的
     **分桶鍵逐字相同**（`WeekFields.ISO` 的 `weekBasedYear` ＋ `weekOfWeekBasedYear`，
     `open` 取該週最早交易日、`high`／`low` 取極值、`close` 與週日期取最晚交易日）。
     **免罪理由：** `bff` 是獨立 Maven artifact、其 `pom.xml` 不依賴 `backend`，兩者之間
     沒有共用程式模組；把 `ChartSeriesAligner` 抽成共用會讓 business-services 依賴 BFF 的
     display DTO（`PricePointDto`），違反本檔 §8 的相依方向——**收斂方向比重複更糟**。
     **五處刻意分歧**（不影響週界本身）：(a) BFF 的「週指標」是該週最後一個交易日的**日K**
     指標值，雷達是在週K 序列上**重算**；(b) 價基不同（走勢圖原始價、雷達還原權息）；
     (c) 進行中週：圖表要畫、評分排除；(d) `requestedStart` 起始週丟棄為 BFF 專有；
     (e) 壞資料週：BFF 整週丟棄，雷達逐欄 null ＋ `close` 缺值才丟整根。
     **同步機制：** 雙邊 Javadoc 互相指名為「必須同步的對應實作」，並由
     `WeeklyBarAggregatorTest` 與 `ChartSeriesAlignerTest` 各自以相同的週界案例
     （一般週、短週、ISO 跨年、單日成週）釘住，壞週案例則各自斷言刻意不同。
     完整脈絡見 `spec/design.md` 的「三軌持有期與真正的週K／日K 棒」小節。
   - **具名例外之五（Task 357 登記，狀態：併存、刻意不合併）：配息事件的「錨定日」
     （`anchorDate ＝ min(除息日, 除權日)`，Requirement 94 / Task 357.2c）有兩份實作**——
     (a) `backend/.../model/DividendDates#anchorDate`（canonical，吃 `LocalDate`；backend 內
     所有進入點——`StockDividendHistory#anchorDate()`、`DividendCurrentStateRepository` 的
     `Event`／`ProjectedEvent`／`ActiveFutureEvent`／`ActiveEventDetail` 四個 record、
     `DividendEventEvidenceResolver.Event`——一律委派它，**該 module 內只有這一份**）；
     (b) `external-materials-service/.../model/DividendDates#anchorDate`（同樣吃 `LocalDate`；
     三條抓取路徑 `client.DividendFetchClient`、`client.TaiwanOfficialDividendCalendarClient`
     與 `service.MarketDataFetchService` 都委派它）。
     SQL 側的 `LEAST(ex_dividend_date, ex_rights_date)`（`JdbcDividendEventEvidenceRepository`、
     `JdbcDividendCurrentStateRepository`、`StockDividendHistoryRepository` 的兩支 JPQL）語意與
     (a) 逐位相同（**是 `LEAST` 不是 `COALESCE`**），視為同一份規則在資料庫端的等價寫法，
     不另計為實作。
     **免罪理由：** (a)–(b) 是**建置結構**——`backend` 與 `external-materials-service` 是兩個
     獨立 Maven artifact，後者的 `pom.xml` 不依賴 `backend`，全 repo 三個 `pom.xml` 無
     aggregator、無共用程式模組，跨不過去（與具名例外之二 (a) 對 {(b),(c)}、具名例外之四
     同一條理由）。
     **不適用建置結構理由的那一組已於 Task 357 收斂，不列為例外：** ext 端原本在
     `client.DividendFetchClient`、`client.TaiwanOfficialDividendCalendarClient` 與
     `service.MarketDataFetchService` 各有一份等價的四行實作，三者同在
     `asset-external-materials-service` 這一個 artifact、同一個 Spring context，寫成
     「無共用模組故無法收斂」是錯的（正是本鐵則最下方那句「引用例外時須逐條核對免罪理由
     是否真的適用」要擋的事，與具名例外之二 (b) 對 (c) 同型）。已抽成 (b) 這一支純日期算術
     helper：放在新的 `model/` package 而不是 `client/` 或 `service/`，兩個 package 都往內
     依賴它，不必為了一個純函式讓 `service` 反過來依賴 `client`。backend 端同理，四個 record
     ＋ entity 的五份逐字相同實作已收斂成 (a)。
     **`min` 不是 `coalesce`，這是語意差異不是寫法偏好：** `coalesce` 取「第一個非 null」，
     在**兩欄皆有值且除權日較早**時會取到較晚的除息日，讓一個實際落在視窗內的事件被區間
     過濾排除——正是 Task 357 要消滅的那種靜默消失換個形狀。兩者只有在「至多一個非 null」
     或「純 null 檢查」時才等價，所以只用純配股（除息日 null）的案例測不出差別。
     （`spec/requirements.md` 的 357.3d-1 字面寫 `COALESCE(...)`「即 anchorDate 的落地形式」，
     與 357.2c 的 `min` 定義互相矛盾；以 357.2c 為準。）
     **同步機制：** 兩份的清單以本條為權威，(b) 的 Javadoc 指名 (a) 為「必須同步的對應實作」。
     兩份各以相同四個案例釘住（只有除息日／只有除權日／兩者皆有取較早／兩者皆 null 視為
     無日期）：`DividendAnchorDateLeastTest`（backend，直接呼叫 (a)，並以捕捉到的 SQL 字串
     釘住 `LEAST`）、`DividendDatesTest`（ext，直接呼叫 (b)，並以反射確認三條抓取路徑都委派它）。
     **任一側改動比較邏輯，兩側必須同 commit 跟上。**
   - **上述五組具名例外之外，不得再新增任何一份同義值的獨立實作**：新的同義值一律回到本鐵則。
     引用例外時須逐條核對免罪理由是否真的適用——Task 335 的 arch 稽核正是發現「(3) 的『含 live』
     理由對 IXIC 不成立」才揭出第二組；Task 342 的稽核則是發現量比欄同時被兩支 helper 餵養；
     Task 357 的稽核則是發現 `external-materials-service` 內部三處各有一份等價的錨定日實作，
     而它們同屬一個 artifact、「無共用模組」在那裡不成立（已收斂，見例外之五）。
5. **前端只 render，BFF 預先聚合 / 排序 / 過濾 / 計算 profit / profitRate 等衍生值。**

---

## 4. External Materials Service `external-materials-service/`

### 4.1 目錄結構

```
external-materials-service/src/main/java/com/steven/assets/externalmaterials/
├── config/             # WebClient、Redis 連線
├── model/              # DividendDates（錨定日 `min(除息日, 除權日)` 純算術，client 與 service 共用；見 §3.2 鐵則 4 具名例外之五）
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

- **僅內網暴露 `/internal/*`，不對前端或 host 開放。** business-services 透過 docker network 呼叫。
  具名唯讀 operation `/api/quotes` 與 `/api/quotes/one` 可由 `api-gateway:9090` 的 exact allowlist
  轉送，但 external-materials-service 本身不發布 host port；後端內部消費者仍走既有 Redis 直讀。
- **抓價邏輯不寄宿在 business-services。** 外部 API 限流／失敗不影響主系統。
- **寫入端：** Redis（live）+ PostgreSQL（歷史 / NAV / 配息）。
- **不持有業務邏輯。** 不知道 snapshot、不知道使用者持倉；只負責 fetch & store。

### 4.3 Redis Key Schema

| Key | 內容 | TTL | 寫入者 |
|-----|------|-----|--------|
| `price:{market}:{code}` | live JSON | 24h | `PriceCacheWriter` |
| `price:index:{market}` | Set，紀錄該市場所有有 cache 的 code | 24h | 同上 |
| `price:dayhl:{market}:{code}:{tradingDate}` | 該日最高/最低聚合 | 36h | `IntradayHighLowTracker` |
| `price:etfnav:{market}:{code}` | ETF 淨值／折溢價 JSON（Task 214） | 96h | `EtfNavCacheWriter`（由 `EtfNavPoller` 排程觸發） |
| `exchange-rate:session:USD:TWD` | eligible session heartbeat（UTC `Instant`、來源集合） | 10s | `ExchangeRateSpotCacheWriter`（Task 327） |
| `exchange-rate:spot:USD:TWD` | USD/TWD 最新買賣價、UTC timestamps 與內部 per-source watermark | 24h | `ExchangeRateSpotCacheWriter`（Task 327） |
| Channel `price-update` | Pub/Sub 推播 | — | `PriceCacheWriter` |

**`price:etfnav:*` 補充說明**

- **讀取端：** business-services 的 `PriceQueryService.getEtfNav(stockCode, market)` **只讀 Redis、刻意不 fallback DB**；查無回 `Optional.empty()` 是正常情形（個股本來就沒有淨值），不記 warn、不補 0、不做 ETF 白名單。其兩個業務消費端為 `ExcelExportService`（資產總覽匯出的淨值／折溢價欄）與 `TradingRadarService`（交易雷達「即時折溢價」欄，Task 320），兩者的美股衍生計算一律經 `EtfLivePremiumCalculator`。Task 349 另增加第三個**外部契約消費端**：external-materials-service 的 `PriceCacheReader` 為 `/api/quotes` 唯讀 join 同一 key，但只原樣轉交來源 `premiumDiscountPct`，不取 `nav`、不做美股反推；這是「官方來源欄位」而非上述 business 畫面衍生語意，兩者不得混用。
- **payload 欄位：** `stockCode` / `market` / `nav` / `premiumDiscountPct` / `navAsOf` / `source` / `updatedAt`。序列化設定為 `NON_NULL`，**null 欄位直接不出現於 JSON**（不是寫成 `null`）。`updatedAt` 為台北牆鐘的寫入時刻，讀取端不解析它。

```
{"stockCode":"0050","market":"台股","nav":105.57,"premiumDiscountPct":-0.35,
 "navAsOf":"20260812 13:31:00","source":"TWSE","updatedAt":"2026-08-12T13:31:12.345"}
```

- **`navAsOf` 有兩種格式，解析前先看 `market`：** 台股為 `yyyyMMdd HH:mm:ss`（證交所 `all_etf.txt` 的 `i`＋`j` 欄），美股為 `yyyy-MM-dd`（Yahoo 報價時點轉紐約當地日期）。`source` 對應為 `TWSE`／`Yahoo Finance`。
- **美股 payload 恆無 `premiumDiscountPct`：** Yahoo 未提供折溢價欄，寫入端刻意留 null 而非用 Yahoo 自己的 `regularMarketPrice` 反推——那與取用端該列顯示的即時價來源／時點不同（實測 VOO 相差 0.11%，使用者自行驗算會兜不攏）。故美股折溢價由取用端以「該列自己的市價」計算，保證列內自洽；台股則直接沿用證交所已算好的 `g` 欄，缺漏時留白**不反推**（Requirement 34／Task 259）。
- **TTL 96h 而非比照即時價的 24h 是刻意的：** 淨值一天只有一組有意義的值，且只在交易時段抓取。若只留 24h，週末與連假後的第一份匯出會整欄空白（週五最後一筆已過期）；96h 讓資料撐過週末＋一天連假。**代價是「Redis 裡有值」不等於「是今天的值」——判斷新舊一律看 `navAsOf`。**
- **排程節拍：** Task 349 起台股 `EtfNavPoller` 為 `0 1/2 9-13 * * MON-FRI`（Asia/Taipei），以奇數分鐘和偶數分鐘股價 producer 錯開；兩者都每 2 分鐘，且各自受 `MarketClock` 守門。公開 quote API 只新增 `premiumDiscountPct`，不輸出 `navAsOf`，所以其 `updatedAt` 仍只能解讀為 price 時點，不能推論折溢價新鮮度。

---

### 4.4 Docker 外部 API Gateway `api-gateway/`

`api-gateway` 是獨立、非 root、deny-by-default 的 Nginx image，host 唯一 mapping 為
`127.0.0.1:9090:9090`。它轉送九條 exact route：八條唯讀 GET（quotes 兩條到 external service，
market-index、assets/latest、USD/TWD、market-analysis/today、portfolio-advice/latest、trading-radar/today 到 BFF；
後三條由 Requirements 79／86、Tasks 338／347 新增；兩支 owner-scoped API 固定解析 configured admin）＋ 一條寫入 POST
（`/api/public/crawler-data/rescan` 到 BFF，Requirement 71／Task 329；唯一有外部抓取副作用的
例外，經 business 端 30 秒全域 Redis 冷卻節流）；其餘回 `404`，同 exact path 非合法 method 回 `405`。
Tailscale Serve 只掛相同九條 exact path，包含公開 USD/TWD 匯率與第六條寫入路由；禁止
root／`/api/` proxy、Funnel、自簽憑證與另加 OAuth。九條 path/method 必須與版本控管的完整
OpenAPI 3 契約機械對齊；Swagger 文件本身不新增 9090 route。

### 4.5 Fubon Broker Service `fubon-broker-service/`

```
fubon-broker-service/
├── Dockerfile                 # Python 3.13 multi-stage；runtime platform linux/amd64
├── .dockerignore              # 排除 SDK 安裝媒介與 secrets
├── requirements.txt           # exact-pinned runtime dependencies
├── src/fubon_broker_service/
│   ├── app.py                 # exact internal routes；關閉 docs/redoc/openapi
│   ├── config.py              # mounted-file config state（lazy、fail closed）
│   ├── security.py            # internal token constant-time verify＋redaction
│   ├── sdk_gateway.py         # login/accounting/init_realtime/session lifecycle
│   └── models.py              # normalized decimal-string wire DTO
└── tests/                     # fake SDK/adapter；永不需要真實憑證
```

- proprietary SDK 只存在這個 Python service；Spring modules 經 `X-Internal-Service-Token` 主動 pull normalized internal API，不直接 import SDK。
- 服務唯讀：只允許 health/config、portfolio dry-read、台股 intraday quote；不提供下單／改單／刪單（全專案鐵則，見 CLAUDE.md〈券商 API 只能查詢，不得交易〉），不連 PostgreSQL／Redis、不反向呼叫 business。
- Compose 固定 `platform: linux/amd64`、無 host port、只接 `asset-net`、non-root/read-only/tmpfs/drop capabilities。官方 zip/wheel 安裝媒介不入 Git/build context/final image；hash 驗證後安裝的 runtime package可存在final image。
- `secrets/fubon/sdk/` 只掛Python；Java services只可掛`secrets/fubon/shared/`。disabled或misconfigured時process/service仍healthy，functional feature回typed failure、零外呼/零寫入。
- business-services 是庫存 persistence與tenant/transaction owner；external-materials-service是Redis live quote唯一writer。Python不得跨越這兩個責任邊界。

### 4.6 Yuanta Broker Service `yuanta-broker-service/`（scaffold，尚待官方帳號啟用）

```
yuanta-broker-service/
├── Dockerfile                 # Python 3.13 multi-stage；runtime platform linux/amd64
├── .dockerignore              # 排除 DLL／憑證等 SDK 安裝媒介與 secrets
├── requirements.txt           # exact-pinned runtime dependencies（含 pythonnet）
├── src/yuanta_broker_service/
│   ├── app.py                 # exact internal routes（14 支）；關閉 docs/redoc/openapi
│   ├── config.py              # mounted-file config state（lazy、fail closed）
│   ├── security.py            # internal token constant-time verify＋redaction
│   ├── sdk_gateway.py         # pythonnet/clr 載入 YuantaSparkAPI.dll；集中 raw→normalized 映射
│   └── models.py              # normalized decimal-string wire DTO
└── tests/                     # FakeYuantaSparkGateway；永不需要真實憑證
```

- 元大官方提供兩套 API：**SPARK API**（`pythonnet` + .NET 8 CoreCLR，官方文件列出 Linux 登入簽章）與**舊版 OneAPI／YuantaOneCom**（COM + .NET Framework 4.5.2，僅限 Windows）。本服務只能用 SPARK API；OneAPI 無 Linux/Docker 路徑，禁止整合。
- 唯讀：只允許查詢類（帳務 6 支、行情 5 支、回報 1 支）與 `Subscribe*`／`Unsubscribe*`；**禁止** import 或呼叫任何下單／改單／刪單／`SendFutureCombined`／`GetFutDepositOptimum`（全專案鐵則，見 CLAUDE.md〈券商 API 只能查詢，不得交易〉）。
- `YuantaSparkAPI.dll` 無公開直鏈可供 build time 雜湊釘選，與 Fubon 官方 wheel 不同；DLL／憑證一律由使用者依官方申請流程取得後掛載 `secrets/yuanta/`，不得進 image/build context。
- Compose 固定 `platform: linux/amd64`、無 host port、只接 `asset-net`、non-root/read-only/tmpfs/drop capabilities。`YUANTA_ENABLED=false`（預設）時 process 仍 healthy、狀態 `NOT_CONFIGURED`，不拖垮既有 stack。
- **本次任務不建立 Java 消費端**：無具體功能要消費這些查詢前，不預先蓋 `integration/yuanta` proxy 層（YAGNI）；日後有功能需要時，比照既有 `integration/fubon` 慣例新增。

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
├── requirements.md       # 102 個 Requirements（最新為 102）
├── design.md             # 架構圖、ERD、Service 職責、Sequence
├── tasks.md              # 索引（Task 1–228、264–267、269–292、297–309、311–342、344–366）＋尚未歸檔的 201 起區段
├── tasks/                # 任務檔
│   ├── README.md         # 自足任務檔規範
│   ├── archive/          # Task 1–200 歷史，已凍結
│   └── tNNN_<slug>.md    # 新制自足任務檔（Task 201 之後）
└── steering/             # 長期 context（每次對話皆載入）
    ├── product.md        # 產品定位 / 使用者 / 範圍
    ├── tech.md           # 技術棧 / 版本 / 慣例
    └── structure.md      # 本檔
```

> **新任務不要追加進 `tasks.md`。** 一任務一檔 `spec/tasks/tNNN_<slug>.md`，內容須自足到
> 「只讀那一支檔就能實作」。規範與範本見 [tasks/README.md](../tasks/README.md)。

### 6.1 何時動 spec？

| 變更類型 | 是否必須更新 spec | 應更新哪些檔 | 需過 `/spec-review` |
|----------|-------------------|--------------|---------------------|
| 新增 Entity / 改資料模型 | ✅ | requirements + design + 任務檔 | ✅ |
| 新增 API endpoint | ✅ | design + 任務檔（若新功能也要 requirements） | ✅ |
| 新增前端頁面 | ✅ | requirements + design（含 route 表）+ 任務檔 | ✅ |
| 修正商業邏輯 bug | ✅ | requirements（補 AC 或更新既有 AC）+ 任務檔 | ✅ |
| 純 CSS / 樣式 / typo / import 整理 | ❌ | commit 訊息加 `[skip-spec]` | ❌ |
| 長期不變的技術選型 / 目錄結構 | ✅ | steering 對應檔（本文件等） | ❌ |

審查產出 findings 清單（critical／major／minor），**不打分數、不設通過門檻**；critical 與 major 修完即可進入實作，minor 可留待後續。連續 3 輪仍在爭同一件事則停下來問人，不要無限迴圈。

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
                          ├──► external-materials (僅內網；Docker 外 quotes 由 api-gateway exact route 直達，見 Requirement 66／§4.2)
                          └──► fubon-broker-service ◄── external-materials-service
                                      │                 （兩者只主動 pull normalized API）
                                      └──► Fubon API
```

**禁止：**
- ❌ frontend 直接打 business-services、external-materials-service 或 fubon-broker-service
- ❌ business-services 直接打外部行情 / NAV / 配息 API（一律經 external-materials）
- ❌ external-materials-service 反向呼叫 business-services
- ❌ **任何 service** 呼叫券商 API／SDK 的下單／改單／刪單或任何具金融副作用的寫入（見 CLAUDE.md〈券商 API 只能查詢，不得交易〉，全專案鐵則）
- ❌ fubon-broker-service 寫 PostgreSQL／Redis或反向呼叫任一Spring service
- ❌ 任何 service 跨層直接讀對方資料庫表（除非由 SDD 明確設計）
