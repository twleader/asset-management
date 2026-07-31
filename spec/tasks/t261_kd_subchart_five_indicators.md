# [t261] 走勢圖指標上收後端：新增 K9/D9/J9/K3D2/RSV 五值並消除與觀察清單表格的不一致

**對應 Requirements:** Requirement 13（股票走勢圖延伸資訊：雙擊個股開啟的 popup 內含走勢圖、ETF 持股明細、股利歷史三個頁籤；走勢圖上 pane 為股價＋MA20/MA60/MA240＋成本均價，下 pane 為 KD 技術指標子圖）
**前置任務:** 無
**Liquibase changeset:** 無（不動資料庫）

## 背景

`frontend/src/components/StockAnalysisDialog.vue` 的走勢圖下 pane（KD 子圖）目前只畫 K、D 兩條線，legend 也只有這兩個數值。使用者要求比照外部看盤軟體，在同一列 legend 同時顯示 **K9、D9、J9、K3D2、RSV** 五個值，並在圖上畫出 K9／D9／J9 三條線。

**公式由使用者提供的畫面數值反推驗證**：K9=40.36、D9=32.74 時，畫面顯示 J9=17.50、K3D2=55.60。
`3×32.74 − 2×40.36 = 17.50` ✓（**J9 = 3D − 2K**）、`3×40.36 − 2×32.74 = 55.60` ✓（**K3D2 = 3K − 2D**）。
註：此 J 的方向與坊間常見的 `J = 3K − 2D` 相反，但兩個方向本任務都要提供，不需二擇一。

**同時要修掉一個既有的資料源分裂**。走勢圖的 KD／均線目前是**前端自算**（`calcKD()`／`calcMA()`，就 `/api/bff/stock-analysis/history/stock` 的序列），而觀察清單（`WatchStockView`，`WatchStockService` 呼叫 `computeAll()`）表格顯示的同義 K/D／均線來自後端。兩者**今日那一格的資料源不同**：

| | `computeAll()`（表格顯示） | `HistoricalDataService.getStockHistory()`（走勢圖舊路徑） |
|---|---|---|
| 今日格併入條件 | 只要 Redis live 的 `tradingDate == today` | 還必須 `source` 不含括號（排除 `(history)`／`(前收)`／`(買賣中價)`）；**`0000` 台股大盤完全不併 live**（直接 return `twseDailyRepo` 查詢結果） |
| 今日格高低價 | `highPrice ?? price`、`lowPrice ?? price`（真實盤中高低） | 合成列**只有 closePrice**，KD 遞迴 fallback 成收盤價 |

**現在的錯誤行為**：盤中在觀察清單雙擊某一列開走勢圖，表格的 K/D 與圖上的 K/D 可能是兩個不同的數字（HH9／LL9 不同 → RSV 不同；或一邊有今日格一邊沒有 → 整段遞迴差一期）。收盤後兩者才會一致。

**正確行為**：走勢圖的所有技術指標改由 business-services 的 `TechnicalIndicatorService` 供給，前端零計算，三處顯示同源。

**這是使用者明確拍板的方向**（另一個選項是「維持前端自算、在 spec 記為具名例外」，未被採用）。CLAUDE.md 的 BFF 規範本就把這個情境列為具名例子：「同義欄位、同一 business service API（例：股票即時 K/D/季線 → 兩個頁面都透過 `TechnicalIndicatorService.computeAll()`）」。

> ⚠️ **同源範圍只到「原始價基」為止，交易雷達個股表不在內。** `TradingRadarService` 的個股決策表 K/D 走的是 `DistributionAdjustedPriceService.adjust()` **還原配息／除權**後的序列再 `computeFromSeries(...)`，與走勢圖的原始價基**刻意不同**——凡視窗內有配息的個股必然對不上數字。`spec/design.md` 既有規範明訂「禁止混用原始／還原價」，故**不得為了讓數字一致而改動任一方**，驗收也不得拿交易雷達個股列來比對（該頁的**大盤卡**才是走 `computeAll()`）。

## 要做什麼

### 後端：`TechnicalIndicatorService` 序列版（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`）

現況重點（改動前請先確認這些仍屬實）：

- `computeAll(stockCode, market)`：`0000`＋`台股` 走 `computeAllForTaiex()`；否則 `historyRepo.findRecentN(code, market, 240)` 取降序 240 筆，若首筆非 `MarketZones.today(market)` 則以 `priceQuery.getLive()` 併一筆今日列到 index 0（`closePrice=sp.price()`、`highPrice=sp.highPrice() != null ? sp.highPrice() : sp.price()`、`lowPrice` 同理），再交給 `computeFromSeries(series)`。
- `computeFromSeries(desc)`：`simpleMa(series, 20/60/240)`（不足視窗回 null）＋ `stockKd(series)` 取當期、`stockKd(series.subList(1, size))` 取前期。**此方法另有一個呼叫端**：交易雷達會先還原權息再呼叫它，不可改變其簽章語義。
- `stockKd(desc)`：`desc.size() < 9` 回 EMPTY；`reversed()` 成 asc 後從 index 8 起遞迴，`k=50, d=50` 起算，`highest/lowest` 取 9 筆視窗（`getHighPrice()` 為 null 時 fallback `getClosePrice()`），`rsv = (highest==lowest) ? 50 : (close-lowest)/(highest-lowest)*100`，`k = k*2/3 + rsv/3`、`d = d*2/3 + k/3`，最後 `BigDecimal.valueOf(...).setScale(2, RoundingMode.HALF_UP)`。

- [x] 261.1 新增公開 record（DTO 一律用 record）：
  `public record IndicatorPoint(LocalDate tradingDate, BigDecimal ma20, BigDecimal ma60, BigDecimal ma240, BigDecimal k, BigDecimal d, BigDecimal j9, BigDecimal k3d2, BigDecimal rsv)`。
  所有 BigDecimal 欄位 `setScale(2, RoundingMode.HALF_UP)`；視窗／暖機不足者該欄為 `null`（不是 0）。

- [x] 261.2 把 `stockKd()` 重構成**序列核心**：新增 private 方法逐期算出 `(k, d, j9, k3d2, rsv)` 並回傳整段 list，`stockKd()` 改為呼叫它取最後一筆。**遞迴內容一個字都不能改**（period 9、seed 50/50、`highest==lowest→50`、`k=k*2/3+rsv/3`、`d=d*2/3+k/3`、high/low fallback close、`k`/`d` 續存未捨入 double）。
  ⚠️ **現況全樹有三套 KD 遞迴，本次只碰第一套**：`TechnicalIndicatorService.stockKd()`（吃 `StockPriceHistory`）、同類的 `taiexKd()`（吃 `TwseIndexDailyHistory`，服務交易雷達大盤卡）、`AlertChartRenderer.calcKd()`（警示 email PNG 圖自有）。**`taiexKd()`／`taiexSimpleMa()`／`computeAllForTaiex()` 與 `AlertChartRenderer` 一律不動**——動 `taiexKd()` 會改到大盤卡的對外數值。**不得為了大盤而新增第四套遞迴**（作法見 261.3）。
  `j9 = 3*d - 2*k`、`k3d2 = 3*k - 2*d`，**用該圈未捨入的 `k`、`d`** 計算後才 `setScale(2, HALF_UP)`（與遞迴內部精度一致，避免二次捨入；與拿已捨入值再算相比末位可能差 0.01，本專案統一取未捨入路徑）。
  暖機期（asc index < 8）五值皆 `null`。

- [x] 261.3 新增 `@Transactional(readOnly = true) public List<IndicatorPoint> indicatorSeries(String stockCode, String market, LocalDate start, LocalDate end)`：
  - 取 `tradingDate <= end` 的**全部**歷史（asc），**不可**只取 240 筆——MA240 與 KD 需要 `start` 之前的暖機資料，只取 240 筆會讓 10 年線圖的前段全是 null。**用既有的 `historyRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(code, market, LocalDate.of(1970,1,1), end)`，不要新增語意重複的 query method**。註：`stock_price_history` 的保留期本就只有 10 年（`HistoricalDataService.purgeOldHistory`），而前端 `start` 也是 10 年前，故實際上取不到 `start` 之前的暖機資料、線圖前段的 MA240 仍會是 null——這與舊 `calcMA` 行為相同，**不是回歸，也不需要為此延長保留期**。
  - **`0000`＋`台股` 特例**：改讀 `twse_index_daily_history`（`twseDailyRepo`），並**把指數日線映射成 `StockPriceHistory` 後餵同一份序列核心**（`closePrice←getClosePoint()`、`highPrice←getHighPoint()`、`lowPrice←getLowPoint()`，舊資料 high/low 為 null 時由核心自行 fallback close）。既有的私有映射 `HistoricalDataService.taiexToStockHistory(d, code, market)` 已做同一件事，可提為共用或在本類複製一份等價映射——**但不得因此新增第四套 KD／MA 遞迴**。取數用既有的 `twseDailyRepo.findByTradingDateBetweenOrderByTradingDateAsc(LocalDate.of(1970,1,1), end)`（`HistoricalDataService.getStockHistory` 已在用同一支），**不要新增語意重複的 query method**。今日即時點位的併入比照 `computeAllForTaiex()` 既有作法。
  - 今日 live 併入規則**必須與 `computeAll()` 完全相同**（最新歷史列非 `MarketZones.today(market)`、且 live 的 `tradingDate` 等於今日 → 併一筆今日列，`highPrice ?? price`、`lowPrice ?? price`）。**`end` 早於 `MarketZones.today(market)` 時不併**。
  - 逐日算出 MA20／MA60／MA240（該日往前 n 筆收盤均價，不足 n 筆為 null）與 261.2 的五個 KD 值，最後**只回傳 `tradingDate >= start` 的部分**（暖機段不回）。
  - 失敗時比照 `computeAll()` 的 `catch` 寫法記 `log.warn` 並回**空 list**，不得拋例外中斷走勢圖。
  - **同源保證**：**當 `end >= MarketZones.today(market)` 時**，同一 `(code, market)` 下序列最後一筆的 `k`/`d`/`ma20`/`ma60`/`ma240` 必須逐位等於 `computeAll()` 的對應欄位，倒數第二筆的 `k`/`d` 必須等於 `previousK`/`previousD`。（`end` 早於今日時兩者本就不必相等。）

- [x] 261.4 `MarketDataController`（`backend/src/main/java/com/steven/assets/controller/MarketDataController.java`）新增端點，**參數驗證比照同檔既有 `/history/stock`**（`@Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法")`、`@Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法")`、`@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`）：

  ```java
  @GetMapping("/indicators/series")
  public List<TechnicalIndicatorService.IndicatorPoint> getIndicatorSeries(
          @RequestParam @Pattern(...) String code,
          @RequestParam @Pattern(...) String market,
          @RequestParam @DateTimeFormat(...) LocalDate start,
          @RequestParam @DateTimeFormat(...) LocalDate end) { ... }
  ```

  Controller **只做參數驗證與委派**，不得注入 Repository、不得在內部做計算或 HTTP 呼叫（架構鐵則）。

- [x] 261.5 測試（與實作同一支任務，不得延後）：在 `backend/src/test/java/com/steven/assets/service/` 新增一支測試類。**建構方式**沿用同目錄的 `TechnicalIndicatorTaiexLiveBlendTest`：`@ExtendWith(MockitoExtension.class)` ＋ `@Mock` 欄位（`StockPriceHistoryRepository` / `PriceQueryService` / `TwseIndexDailyHistoryRepository`）＋ `new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo)`（該錨點檔用的是 `@Mock` 註解而非 `mock(...)` 呼叫）。**斷言與方法命名**沿用本目錄多數檔的慣例：AssertJ `assertThat` ＋**繁體中文測試方法名**（錨點檔本身用的是 JUnit `assertEquals` 與英文方法名，此處不照抄）。須涵蓋：
  - **重構等價性**：給一組固定的 `StockPriceHistory` 序列（≥ 250 筆、含 high/low 為 null 的列），斷言 `computeFromSeries()` 回傳的 `k`/`d`/`previousK`/`previousD`/`ma20`/`ma60`/`ma240` 與**重構前的預期值**逐位相同（預期值以現行程式碼先跑一次取得後寫死進測試，作為迴歸錨點）。
  - **序列尾值等於 `computeAll()`（本任務唯一的機械判準，必測）**：同一批 fixture **同時** stub 兩支取數方法——`historyRepo.findRecentN(code, market, 240)` 回傳降序最近 240 筆、`historyRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(...)` 回傳升序全史（≥ 250 筆，使兩者**真的不同**）——斷言 `computeAll(code, market)` 的 `k`/`d`/`ma20`/`ma60`/`ma240` == `indicatorSeries(...)` 尾筆的對應欄位、`previousK`/`previousD` == 倒數第二筆的 `k`/`d`。
    ⚠️ **不可拿 `computeFromSeries()` 當比對對象**：它與序列版吃同一份輸入時恆等，測不到真正的風險面（240 筆視窗 vs 全史所導致的 seed 起算點差異）。
  - **`0000` 台股大盤兩套實作等值（必測）**：stub `twseDailyRepo` 一組 ≥ 250 筆指數日線，斷言 `computeAll("0000", "台股")`（走 `computeAllForTaiex()` → `taiexKd`／`taiexSimpleMa`）的五個欄位 == `indicatorSeries("0000", "台股", …)` 尾筆的對應欄位。261.2 明訂 `taiexKd()` 不動，等於允許兩套實作長期並存——沒有這條測試就沒有任何機制保證它們不漂移。
  - **J9／K3D2 公式**：構造使 K=40.36、D=32.74 的情境（或直接對序列核心的輸出斷言），驗 `j9 == 17.50`、`k3d2 == 55.60`。
  - **暖機**：前 8 筆的五個 KD 欄位為 null、第 9 筆起非 null；不足 20/60/240 筆時對應 MA 欄為 null。
  - Mockito 在本專案的 Java 版本下需要 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`（見驗證段），**不要用 `-DargLine`**（會覆蓋掉時區設定導致大量測試 error）。

### BFF：新增 aggregation controller（`bff/src/main/java/com/steven/assets/bff/stockanalysis/`）

走勢圖需要「股價 + 指標」兩支 business API 的資料，且**兩者日期集合可能不等**（見背景表格）。此跨來源 join 屬 aggregation，依 CLAUDE.md「BFF 負責跨服務 aggregation、預先計算 / 排序 / 過濾，前端只負責 render」**必須在 BFF 做，不得放前端**。該套件現況只有純 Gateway `rewritePath` route（`StockAnalysisBffRoutes.java`），需新增 controller；同 repo 已有前例可照抄結構：`bff/src/main/java/com/steven/assets/bff/dashboard/DashboardBffController.java`。
⚠️ **照抄的是 controller 本身的骨架**（注入 `WebClient businessServicesClient`、以 `Mono.zip` 並行、用 `ParameterizedTypeReference` 解析回應）。**`dashboard/dto/` 的 `@Data` 可變 class 寫法一律不照抄**——那是既有技術債，違反「DTO 用不可變 `record`」的鐵則；本次 DTO 一律 `record`，放在 `bff/.../stockanalysis/dto/`。
身分傳遞**不必自己做**：`bff/.../config/WebClientConfig.java` 的 `tenantHeaderFilter()` 已掛在 `businessServicesClient` 上，會自動從 Reactor context 補 `X-User-Id/Role/Status`。股價與指標皆為全域公開行情（`StockPriceHistory`／`TwseIndexDailyHistory` 都沒有 `@Filter`／owner 欄位），無須 owner 過濾。
路徑不會被既有 route 攔截：`StockAnalysisBffRoutes` 的五條 route 全是 **exact path**（無 `/**` 萬用），且 WebFlux `RequestMappingHandlerMapping`（order 0）本就先於 Gateway `RoutePredicateHandlerMapping`（order 1）——同一模式的既有先例是 `StockAlertBffController` ＋ `StockAlertBffRoutes`。

- [x] 261.6 新增 `StockAnalysisChartBffController`，`GET /api/bff/stock-analysis/chart-series?code=&market=&start=&end=`：
  - **並行**呼叫 business 的 `/api/market-data/history/stock` 與 `/api/market-data/indicators/series`（同一組 `code`／`market`／`start`／`end`），不得序列等待。
  - **以 `tradingDate` 取聯集對齊**（升冪）後回傳等長陣列的 DTO（record）：
    `{ dates: List<String>, prices, ma20, ma60, ma240, k, d, j9, k3d2, rsv: List<BigDecimal> }`，某日在某一側缺值即填 `null`。
  - ⚠️ **必須取聯集，不可拿股價側日期當基準**：指標側的日期集合可能**嚴格較大**（股價側要求 `source` 不含括號、`0000` 大盤完全不併 live；指標側比照 `computeAll` 併 live）。拿股價側當基準會把**今日**的指標點靜默丟掉 → legend 顯示前一交易日的 K9/D9 → **本任務要消滅的不一致原封不動**（`0000` 大盤為必然觸發，非偶發邊角）。
  - 另回傳 `latest` 區塊供 legend 直接使用：**指標序列本身最後一筆**的八個值與**倒數第二筆**（供漲跌箭頭比較）。legend 不得改用對齊後陣列的最後一筆（同上，今日格可能不在股價側日期內）。
  - 指標上游失敗時仍須回傳股價陣列（指標欄留空、`latest` 為 null），走勢圖不得整張消失；股價上游失敗才回空。
  - **對齊邏輯必須抽成可單獨測試的純函式**（比照既有 `bff/src/main/java/com/steven/assets/bff/common/` 下的共用邏輯與其測試 `bff/src/test/.../common/LiveAssetsOverlayTest.java`），並在 `bff/src/test/.../stockanalysis/` 補單元測試（`bff/pom.xml` 已含 `spring-boot-starter-test`，沿用 AssertJ）。至少涵蓋三案：
    1. **指標側多一天（`0000` 情境）** → `dates` 必須含該日、該格 `prices` 為 `null`、`latest` 取**指標序列**最後一筆而非對齊後陣列最後一筆；
    2. **股價側多一天**（指標暖機不足）→ `dates` 含該日、指標欄為 `null`；
    3. **指標上游失敗** → 仍回股價陣列、指標欄全 `null`、`latest` 為 `null`。
    這三案是本任務風險最高的邏輯，而 BFF 端**無法用 curl 驗證**（見驗證段），只能靠這組測試釘住。
- [x] 261.7 `StockAnalysisBffRoutes.java` 的 class javadoc 把「此元件被 Dashboard / SnapshotForm / WatchStock / StockAlert **四個** view 同時使用」更新為**六個**（實際為 Dashboard / SnapshotForm / WatchStock / StockAlert / RealizedGain / TradingRadar，後兩者分別為損益明細列雙擊與 Task 234 交易雷達列雙擊）。**不新增 passthrough route**——本次走的是上面的 aggregation controller。
- [x] 261.8 `frontend/src/api/index.js` 的 `stockAnalysis` 區塊新增：
  ```js
  getChartSeries: (code, market, start, end) =>
    api.get('/bff/stock-analysis/chart-series', { params: { code, market, start, end } }),
  ```
  既有的 `getStockHistory`（`api/index.js:112`）定義**留著不動**（BFF route 仍在），但 261.9 之後 `StockAnalysisDialog.vue` 不再呼叫它——**注意它目前是全前端唯一的呼叫端**（`grep -rn "getStockHistory" frontend/src/` 只命中 `api/index.js` 定義與 `StockAnalysisDialog.vue:284,293`）。

### 前端（`frontend/src/components/StockAnalysisDialog.vue`）

- [x] 261.9 **刪除 `calcMA()`（第 447–453 行）與 `calcKD()`（第 455–473 行）**；走勢圖改讀 261.6 的 `chart-series`，前端**零指標計算、零跨來源 join**（對齊已由 BFF 完成）。`fetchHistory()`（第 271 行附近）改為呼叫 `getChartSeries`，**走勢圖頁籤只留這一個股價來源、不再另抓 `/history/stock`**——同一份股價抓兩次會在盤中拿到兩個不同的即時價，正是本任務要消滅的那類不一致。既有的 lazy backfill（回空 → 觸發 `backfillStock` → 重載一次，Task 136）改以 `chart-series` 的 `dates` 為空判定。

- [x] 261.9.1 ⚠️ **`history.value` 目前有三個使用者可見的消費端，全部要一起改由 `chart-series` 推導，漏掉任一個都是靜默功能損壞**（此三處與股利頁籤無關——股利頁籤的「除息日昨收價」來自股利 API 回應列的 `previousClose`，本就不走 `/history/stock`）：
  1. `defaultZoomRange`（第 197 行附近）用 `history.value.length` 當 total 決定期間按鈕（1個月／3個月／1年…）的預設縮放窗 → 改用 `dates.length`。**漏改的症狀**：total=0，每個期間按鈕都顯示全部 10 年。
  2. `latestTradingDate`（第 216–224 行）→ 標題列「資料截止：」（template 第 15–16 行）→ 改取 `dates` 最後一筆。
  3. `intradayQuote`（第 232–254 行）的**昨收**（Task 158；第 244–250 行從 `history` 反找嚴格早於分時交易日的最後一筆收盤）→ 改用 `dates`／`prices` 反找。**因聯集後尾格可能是「有指標、無股價」的 `null`，反找時必須跳過 `null`**。

- [x] 261.10 `chartOption` 內原本的 `dailyDates`／`dailyPrices`／`dailyMa20`／`dailyMa60`／`dailyMa240`／`dailyKD` 一律改為直接取 `chart-series` 回傳的對應陣列（**不再自行計算或對齊**）；`let dates, prices, ma20, ma60, ma240, K, D, xLabelFormatter`（第 542 行）擴充為含 `J, K3D2, RSV`。
  - **「當日」intraday 分支**（第 566–572 行）：現況以 `const fill = v => dates.map(() => v)` 把日線最新值填成整段分鐘網格常數。八個指標（MA20/60/240 + K9/D9/J9/K3D2/RSV）**全部沿用同一 `fill(...)` 手法**，但填入的值取自 BFF 的 `latest`（**指標序列本身的最後一筆**），不是對齊後陣列的最後一筆。註：該值在盤中已含今日即時價，**不是**「前一收盤日」的值。
  - 箭頭比較基準恆為 BFF `latest` 的**最後兩筆指標值**（不可用 intraday 常數網格，那會恆為持平、箭頭永不出現）。

- [x] 261.11 legend（寫死在 `chartOption` 的 IIFE，第 641–691 行）：名稱由 `K`／`D` 改為 **`K9`／`D9`**，新增 **`J9`**、**`K3D2`**、**`RSV`**（series `name` 同步改名，legend 靠 name 對應 series）。
  - 數值格式沿用現有 `fmt`：`toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })`。
  - **漲跌箭頭**：五個 KD 指標的數值後接 `▲`（BFF `latest` 的最後一筆 > 前一筆）／`▼`（<）／不顯示（相等或無前值）。配色台股慣例漲紅 `#dc2626`、跌綠 `#16a34a`。**股價／均線／成本均價維持現況無箭頭**。
  - 箭頭需與數值異色 → 另佔一組 rich style key。rich key 沿用既有「由 `colorMap` 的 index 產生（`v0`/`v1`/…）」機制（第 666–675 行）：該 map 含「股價」「月線MA20」等**中文**鍵，而 zrender 的 `STYLE_REG = /\{([a-zA-Z0-9_]+)\|/` 只接受英數底線，故不可直接拿名稱當 key。
  - 顏色：`K9` = `#f59e0b`（現況）、`D9` = `#15803d`（現況）、`J9` = `#0ea5e9`、`K3D2` = `#334155`、`RSV` = `#334155`。
  - legend 項目由 7 → 10（有成本均價時），全擠在頂端一列會過密。**改為兩組 legend**（ECharts `legend` 支援陣列）：股價／均線／成本均價留在上圖頂端（`top: 8`），**五個 KD 指標移到兩張圖中間、即 KD 子圖正上方**——它們與股價線無關聯，貼著自己的 pane 才讀得順。KD 那組用 `bottom` 定位（不依賴容器總高）：`grid[1]` 頂端距底部 = `bottom 60 + height 90 = 150`，legend 兩行約 36px，故 `bottom: 156`，並把 `grid[0].bottom` 由 190 加大為 200 讓出這一列。**不可讓任一組 legend 蓋住線圖**。

- [x] 261.12 series（第 752–764 行，皆 `xAxisIndex: 1, yAxisIndex: 1`）：
  - `K9`／`D9`：沿用現有兩個 series 定義只改 `name`；掛在 K 上的 80/20 `markLine`（`silent: true`、灰色虛線）維持不動。
  - `J9`：新增 line series，`lineStyle: { width: 1.5, color: '#0ea5e9', type: 'dashed' }`、`showSymbol: false`、`endLabel` 比照 K9/D9。
  - `K3D2`／`RSV`：**不畫線**（90px 高的子圖再加兩條會過密；K3D2 = 3K−2D 可由 K9／D9 直接推得、RSV 可由 K9 與前一日 K9 反解 `RSV = 3K − 2·prevK`，資訊不會遺失），**但必須各掛一個 `data: []` 的同名 line series**（`itemStyle: { color: '#334155' }`、`showSymbol: false`）。理由：ECharts `LegendView` 對「`legend.data` 有名字卻找不到同名 series」的處理是**整個項目連同數值都不繪製**（production build 無任何提示），不是 render 成灰色圖示——省略空 series 會讓這兩個數值直接消失且無錯誤訊息。
  - 這兩項不會進 tooltip（`trigger: 'axis'` 逐 series 列出），此為預期行為。

- [x] 261.13 子圖 Y 軸（第 710 行 `{ gridIndex: 1, type: 'value', min: 0, max: 100, splitNumber: 2, ... }`）改為自適應：
  - 取 K9／D9／J9 三序列的非 null 值域（K3D2／RSV 不畫線故不參與）。
  - `pad = (hi - lo) * 0.1 || 5`（比照同檔股價軸第 622 行 `(hi - lo) * 0.1 || hi * 0.001 || 1` 的 fallback 鏈；值域退化成一點時 pad=0 會讓線與 `endLabel` 貼軸邊被切）。
  - 強制 `min ≤ 20`、`max ≥ 80`（保住 80／20 `markLine`）。
  - `min`／`max` 各向外取整到 10 的倍數（`splitNumber: 2` 在非整數邊界會產生 `-37.5` 這類刻度）。
  - 全序列皆 null（歷史不足 9 筆）時退回 `min: 0, max: 100`。

### 明確不做的事

- [x] 261.14 不改警示 email 的 PNG 走勢圖（`AlertChartRenderer`）：它自有一份走 `getStockHistory` 的 KD 計算與繪製，維持只畫 K/D 兩線、Y 軸 0~100（信件圖幅小，三線加自適應軸難判讀）。**允許的改動僅限該類的 javadoc 文字，共四處**：`:56`（「MA20/60/240：與前端 `calcMA` 相同」）、`:57`（「K/D：與前端 `calcKD` / `TechnicalIndicatorService` 同一遞迴」）、`:501`（「與前端 `calcMA` 一致」）、`:514`（「與前端 `calcKD` / `TechnicalIndicatorService` 完全一致」），一律改指本類自有的 `calcMa`／`calcKd`——因為前端 `calcMA`／`calcKD` 於 261.9 刪除，留著會是指向不存在符號的引用。**演算法與繪製一律不動。**
- 不新增指標下拉選單（使用者要的是五個值都顯示，不是可切換的指標集）。
- 不改 `computeAll()` 的對外數值行為（警示觸發門檻、觀察清單 KD 欄、Task 146 的 MA% 換算觸發價都吃這些值）。

## 驗證

### 1. 後端測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

### 2. 前端建置

本 worktree 沒有 `node_modules`（在主 clone 且被 gitignore），直接 `npm run build` 會 `vite: command not found`。先連結、建完移除：

```bash
ln -s /Users/steven/Project/asset-management/frontend/node_modules frontend/node_modules
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
rm frontend/node_modules
```

（或省略本步，讓 `docker compose build --no-cache frontend` 內的 `npm run build` 驗語法。）

### 3. 部署到實際運行的 stack（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）

從 worktree 跑 compose 前要先把主 repo 的 `.env` 複製進來（`env_file` 相對 compose 檔解析，`--env-file` 救不了）：

```bash
cp /Users/steven/Project/asset-management/.env .
```

JVM 服務改動後 cached build 可能產出不含本次變更的 jar，一律 `--no-cache`；frontend 普通 build 會命中 layer cache 沒重跑 vite，同樣要 `--no-cache`：

```bash
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```

⚠️ 確認跑起來的 image 真的含本次變更：cached build 產出 stale jar／stale bundle 是本專案踩過的坑，症狀是「程式碼明明有、功能卻不見」。三個服務各有各的判準：

| 服務 | stale 的症狀 | 判準 |
|---|---|---|
| business-services | 端點回 404 | 下面第 4 步的 curl 回 200 且有資料＝新 jar 生效 |
| bff | 走勢圖整張空白，瀏覽器 Network 對 `chart-series` 回 **404**（不是 401） | `docker exec asset-bff sh -c 'grep -c StockAnalysisChartBffController /app/app.jar'` 大於 0 |
| frontend | legend 仍只有 K／D 兩項 | `docker exec asset-frontend sh -c "grep -o 'K3D2' -m1 /usr/share/nginx/html/assets/*.js"` 有輸出 |

任一項不符就重跑該服務的 `--no-cache` build 並 recreate。

⚠️ recreate `business-services` 會換 IP，BFF 握著舊 IP 會回 500 且約 3 分鐘不自癒（Docker DNS TTL 600s）—— 故上面三個服務一起 recreate。

### 4. 端點自測（免 OAuth，在 business 容器內直接打）

```bash
docker exec asset-business-services sh -c 'curl -s "http://localhost:8080/api/market-data/indicators/series?code=0050&market=台股&start=2026-06-01&end=2026-07-31" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE"' | head -c 600
```

> BFF 的 `/api/bff/stock-analysis/chart-series` **無法用 curl 自測**，理由有二：(a) `asset-bff` 的 runtime 映像**根本沒有 curl**（`bff/Dockerfile` 無任何 `apk add`，compose healthcheck 用的是 `wget`）；(b) 未登入一律回 **401**（`bff/.../config/SecurityConfig.java` 的 `json401EntryPoint`，非 302 導向），狀態碼無法區分「controller 有沒有接走」。這兩點 repo 內已記載於 `bff/src/test/.../stockalert/StockAlertBffRouteOrderTest.java` 的 class javadoc。故 BFF 的對齊邏輯改靠 **261.6 的單元測試**釘住，端到端行為靠下面第 5 步的畫面實測（瀏覽器帶登入 session）。

同源判準（序列尾值 == 單點）——比對 `latest` 的 `k`/`d` 與**觀察清單**表格顯示的 K/D 是否同值。
⚠️ **不要拿交易雷達的個股決策表來比對**：該表走還原權息價基（`computeFromSeries(DistributionAdjustedPriceService.adjust(...))`），與本端點的原始價基**刻意不同**，有配息的個股必然對不上，不是 bug。該頁只有**大盤卡**走 `computeAll()`。

### 5. 畫面實測

`http://localhost/` → 分別從 **Dashboard**（持股表格列雙擊）與**觀察清單**（該頁表格同時顯示後端 K/D，最能看出同源與否）各開一次走勢圖，確認：

- legend 一列顯示 K9 / D9 / J9 / K3D2 / RSV 五個名稱＋數值＋箭頭（漲紅跌綠），且未換行壓到股價線。
- 子圖畫出三條線（K9 橘實線、D9 綠實線、J9 藍虛線），J9 越出 0–100 的區段**沒有被裁掉**；80／20 灰色虛線仍在。
- **走勢圖 legend 的 K9／D9 與觀察清單表格顯示的 K／D 為同一數字**（本次上收的核心驗收點，盤中尤其要看）。交易雷達個股列**不在此驗收範圍**（還原權息價基，刻意不同）。
- 三條均線（月線／季線／年線）數值與改動前一致（未因改由後端供給而漂移）。
- 切到「當日」期間：八個指標變成水平參考線且數字與日線期間**完全相同**。
- 切換期間按鈕（1 個月 / 3 個月 / 1 年…）數值與線條正常重繪。
- 開 `0000` 台股大盤：指標非空白，且**盤中 legend 的 K9 是含今日即時點位的值、不是昨天的值**（舊路徑 `getStockHistory` 對 `0000` 完全不併 live，若對齊取了股價側日期就會退回昨值——這是本次必查的迴歸點）。

## 完成報告

### 實際改動

| 檔案 | 內容 |
|---|---|
| `backend/.../service/TechnicalIndicatorService.java` | 新增 `IndicatorPoint` record、private `KdPoint` record、序列核心 `kdSeriesAsc()`、`indicatorSeries()`、`stockSeriesAsc()`／`taiexSeriesAsc()`／`maAt()`／`scale2()`；`stockKd()` 改為呼叫序列核心取最後一筆 |
| `backend/.../controller/MarketDataController.java` | 新增 `GET /indicators/series`（驗證比照同檔 `/history/stock`，只驗證＋委派） |
| `backend/.../service/AlertChartRenderer.java` | **僅 javadoc 文字**四處，把「與前端 `calcMA`／`calcKD`」改指本類自有的 `calcMa`／`calcKd`（前端該兩函式已刪除） |
| `backend/src/test/.../TechnicalIndicatorSeriesAlignmentTest.java` | 新增，6 個 `@Test` |
| `bff/.../stockanalysis/StockAnalysisChartBffController.java` | 新增，`GET /api/bff/stock-analysis/chart-series`，`Mono.zip` 並行兩支上游 |
| `bff/.../stockanalysis/ChartSeriesAligner.java` | 新增，日期聯集對齊的純函式 |
| `bff/.../stockanalysis/dto/{ChartSeriesDto,IndicatorPointDto,PricePointDto}.java` | 新增，皆為不可變 `record` |
| `bff/.../stockanalysis/StockAnalysisBffRoutes.java` | **僅 javadoc 文字**：四個 view → 六個，並註明走勢圖資料改由 controller 提供 |
| `bff/src/test/.../ChartSeriesAlignerTest.java` | 新增，7 個 `@Test` |
| `frontend/src/api/index.js` | 新增 `getChartSeries` |
| `frontend/src/components/StockAnalysisDialog.vue` | 刪除 `calcMA()`／`calcKD()`；`history` ref → `series`；legend 五值＋箭頭；K9/D9/J9 三線＋K3D2/RSV 空 series；KD 子圖 Y 軸自適應 |

### 驗證輸出

- 後端 `mvn test`：**Tests run: 367, Failures: 0, Errors: 0** — BUILD SUCCESS。既有吃 `computeAll()` 的測試（警示觸發、觀察清單、Excel 匯出、大盤 live blend）全綠，證明序列核心重構未改動對外數值。
- BFF `mvn test`：**Tests run: 17, Failures: 0, Errors: 0** — BUILD SUCCESS（含新增的 7 個對齊測試）。
- 前端 `npm run build`：`✓ built in 4.23s`。
- 全樹 `grep "前端 calcKD|前端 calcMA"`：零命中（無指向已刪除符號的懸空引用）。

### 與原計畫的偏差

1. **測試檔名**：任務檔刻意不預先綁死檔名（避免 `spec-check.sh` 的 B5 誤判），實際命名為 `TechnicalIndicatorSeriesAlignmentTest`。
2. **J9／K3D2 公式測試的容差**：原本設 `0.011`，實測失敗——實作以**未捨入** k/d 計算，而測試只能拿已捨入的 k/d 反算，理論誤差上限為 `3×0.005 + 2×0.005 + 0.005 = 0.03`，實測差 0.02。改為 `0.031`。這反而**實證了**「不走二次捨入」這條規則的必要性（兩種路徑末位確實會差）。
3. **前端 `history` ref 完全移除**（原 261.9 寫「若仍需…」）：改為單一資料來源，`defaultZoomRange`／`latestTradingDate`／`intradayQuote` 昨收三處全部改由 `chart-series` 的 `dates`／`prices` 推導，昨收反找時跳過 `null`（聯集後尾格可能是「有指標、無股價」）。`api/index.js` 的 `getStockHistory` 定義保留但已無呼叫端。
4. **legend `itemGap`**：由 36 收窄為 18（項目數 7 → 10），未動 `grid[0].top`。
5. **legend 改為兩組（使用者回饋後調整）**：原本十項全放頂端一列，實機過密。改為 ECharts `legend` 陣列——上組（股價／月線／季線／年線／成本均價，`top: 8`）與下組（K9／D9／J9／K3D2／RSV，`bottom: 156`＝KD 子圖正上方、兩張圖中間），`grid[0].bottom` 190 → 200 讓出該列，`itemGap` 回到 30。
6. **MA 累加方向（架構查證後修正）**：新增的 `maAt()` 原本以「舊→新」累加，而既有 `simpleMa()`／`taiexSimpleMa()` 是「新→舊」。double 加法不可結合，末位差經 `setScale(2, HALF_UP)` 會在 `x.xx5` 邊界翻面——實測 MA20 約 **1.7%** 的日子會與 `computeAll()` 差 0.01，走勢圖 legend 的「月線MA20」就會跟觀察清單表格對不上，正好牴觸本任務的核心宣稱。已把 `maAt()` 迴圈方向改為新→舊（**不動 `simpleMa`**，動它會改到 `computeAll()` 的對外數值），並新增「40 組隨機漫步價格掃邊界」的迴歸測試釘住此順序。

### 部署與實機驗證（已完成）

變更尚未 merge，依規則從本 worktree build（`cp .env` 後 `--no-cache` 重建 business-services / bff / frontend，再 `--force-recreate`；因 recreate 了 business-services，連帶 `restart bff` 避免 JVM 握舊 IP）。

- 容器狀態：六個服務全 `(healthy)`，`http://localhost/` → 200、`/actuator/health` → `{"status":"UP"}`。
- 產物非 stale：frontend bundle `StockAnalysisDialog-*.js` 含 `K3D2`；bff jar 含 `StockAnalysisChartBffController`；image SHA 與本次建置一致（未被其他 worktree 覆蓋）。
- **新端點實機回應**（`0050`，2026-07-31）：`ma20=102.87, ma60=102.42, ma240=76.10, k=41.39, d=33.09, j9=16.48, k3d2=58.00, rsv=83.08`。公式自洽：`3×33.09−2×41.39=16.49≈16.48`、`3×41.39−2×33.09=57.99≈58.00`。
- **同源判準實機成立**：觀察清單表格同一檔顯示 `K=41.39 D=33.09 MA20=102.87 MA60=102.42 MA240=76.1`，與序列尾筆**五個欄位逐位相同**。
- **`0000` 台股大盤**（兩套實作等值）：序列尾筆 `K=32.37 D=30.58 MA20=44101.14`，與觀察清單表格值完全相同。
- 效能：10 年區間（`2330`，2016-08→2026-07）端點回應 `real 0m0.02s`；business 容器近 3 分鐘 error/exception 計數 0。
- BFF 連線錯誤：`restart bff` 後最近 40 秒為 0（先前 3 筆為重啟前舊 IP `172.19.0.4` 的殘留）。

### 尚未執行

- **瀏覽器畫面實測**：應用走 Google OAuth，需使用者自行登入確認 legend 五值＋箭頭、三條線、J9 未被裁切、切「當日」數值一致。
- 依共用 stack 規則，**merge 進 main 後應從 main 的 worktree 重建**，以免洗掉其他 worktree 已 merge 的工作。
