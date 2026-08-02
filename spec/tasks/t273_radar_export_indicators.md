# [t273] 交易雷達匯出補上週線 MA5 與走勢圖指標選單的 14 個值（大盤總覽＋個股決策各 +15 欄）

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本次追加「擴充技術指標欄位為純揭露、不參與評分」）＋ Requirement 48（今日交易雷達結果快照落地 Redis 與 Excel 區間匯出；本次追加「匯出檔補齊週線 MA5 與走勢圖指標選單的同一組值」）
**前置任務:** 無（Task 262 已上線提供 `TechnicalIndicatorService` 的 MACD／RSI／BIAS／W%R 序列核心；Task 265 已上線提供 `weeklyMa`；Task 271 已把匯出改走 `ExportDoc`）
**Liquibase changeset:** 無（不動資料庫）

## 背景

### 使用者要求

使用者截圖了走勢圖上方的「指標：」下拉選單（`KD,J`／`MACD`／`RSI`／`乖離率`／`威廉指標`，Task 262 建立），要求「今日交易雷達匯出時，這些值也要匯出」，隨後補充「周線價也要匯出」。經確認範圍為：**「大盤總覽」與「個股決策」兩張分頁都要加**，且**畫面不新增欄位**（雷達表已過寬），這組值只出現在匯出檔。

### 現在的行為

`TradingRadarExportService` 產的三分頁中：

| 分頁 | 現有欄數 | 現有的技術指標欄 |
|---|---|---|
| 快照索引 | 8 | 無 |
| 大盤總覽 | 20 | `MA20`／`MA60`／`MA240`／`K`／`D` |
| 個股決策 | 35 | `MA20`／`MA60`／`MA240`／`K`／`D` |

缺兩類值：

1. **週線 MA5**：`weeklyMa` 自 Task 265 起就同時存在於 `TradingRadarDto.MarketSummary`（第 9 個 component）與 `StockDecision`（`weeklyMa`），也顯示在畫面上（`frontend/src/views/TradingRadarView.vue:62` 大盤卡「週線 MA5」、`:112` 個股展開列「週線 MA5」），**但從未進匯出**。純粹是漏掉——不需新增任何計算，只要多寫一欄。
2. **走勢圖指標選單的 14 個值**：`J9`／`K3D2`／`RSV`／`EMA12`／`EMA26`／`DIF`／`MACD`／`OSC`／`RSI5`／`RSI10`／`BIAS10`／`BIAS20`／`BIAS10-BIAS20`／`W%R9`。這些**目前完全不存在於雷達的請求鏈**——`TradingRadarService` 只呼叫 `TechnicalIndicatorService.computeFromSeries()`（回 `FullIndicators`：MA20／60／240／K／D／previousK／previousD／MA5），不含上述任何一個。故 DTO 沒有、Redis 快照沒有、匯出自然也沒有。

### 匯出的資料流（決定了改動必須落在哪一層）

```
TradingRadarService.get()  →  TradingRadarDto.Response
      ↓（每次頁面計算／排程重算時寫入）
TradingRadarSnapshotStore.save()  →  Base64(gzip(mapper.writeValueAsString(resp)))  →  Redis
      ↓
TradingRadarExportService  →  store.range() 取回 JsonNode  →  ExportDoc  →  xlsx ＋ json
```

匯出端只讀 Redis 快照的 `JsonNode`，**不重新計算任何指標**。因此「要匯出這些值」＝「這些值必須先進 `TradingRadarDto`」。`TradingRadarSnapshotStore` 走 `mapper.writeValueAsString(resp)`／`mapper.valueToTree(resp)`，DTO 新增欄位會自動進快照，不需改該類。

### 現況重點（改動前請先確認這些仍屬實）

- `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`
  - `FullIndicators`（public record）：`monthlyMa, quarterlyMa, annualMa, k, d, previousK, previousD, weeklyMa`。
  - `IndicatorPoint`（public record，Task 261／262）：`tradingDate, ma5, ma20, ma60, ma240, k, d, j9, k3d2, rsv, ema12, ema26, dif, macd, osc, rsi5, rsi10, bias10, bias20, b10b20, wr9`（21 個 component）。
  - `computeFromSeries(List<StockPriceHistory> series)`：**package-private**、吃 desc 序列、回 `FullIndicators`。交易雷達是它的呼叫端之一。
  - `indicatorSeries(code, market, start, end)`：`@Transactional(readOnly = true) public`，走勢圖用，取**全史**、**原始價基**，回 `List<IndicatorPoint>`。
  - 序列核心（皆 `private static`，吃 **asc** 序列）：`kdSeriesAsc(asc)` → `List<KdPoint>`（`k, d, j9, k3d2, rsv`，暖機不足 9 筆為 `KdPoint.EMPTY`）、`macdSeriesAsc(asc)` → `List<MacdPoint>`（`ema12, ema26, dif, macd, osc`）、`rsiSeriesAsc(asc, n)` → `Double[]`、`biasRaw(asc, i, days)` → `Double`、`maAt(asc, i, days)` → `BigDecimal`、`scale2(double)` → `BigDecimal`。
  - `computeAllForTaiex()`：**private**，取 `twseDailyRepo.findTopNByOrderByTradingDateDesc(240)` 的 desc 清單，若首筆非今日且 Redis 有今日 live 則於 index 0 插一筆合成 `TwseIndexDailyHistory`（`close/high/low` 取自 live，high/low 缺值 fallback close），再以 `taiexSimpleMa()`／`taiexKd()` 產 `FullIndicators`。
  - `taiexSeriesAsc(stockCode, market, end)`：**private**，已有「指數日線 → `StockPriceHistory`」的映射（`closePrice←getClosePoint()`、`highPrice←getHighPoint()`、`lowPrice←getLowPoint()`）。
- `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`
  - `buildMarket()`：`twseRepo.findTopNByOrderByTradingDateDesc(241)` ＋ `indicatorService.computeAll(TAIEX_CODE, TW_MARKET)`。
  - `buildStock()` → `prepareTechnicalData()`：`priceHistoryRepo.findRecentN(code, market, 241)` → `adjustedPriceService.adjust(...)` → `adjustedRows` → `indicatorRows = min(adjustedRows.size(), liveAdded ? 241 : 240)` → `indicatorService.computeFromSeries(adjustedRows.subList(0, indicatorRows))`。
  - private record `TechnicalData(FullIndicators indicators, List<BigDecimal> completedCloses, BigDecimal previousAdjustedClose, BigDecimal completedChangePercent, boolean distributionAdjusted, BigDecimal week52High, BigDecimal week52Low, BigDecimal kdBandWidthPercent)` — 8 個 component（第 5 個叫 `distributionAdjusted`，不是 `adjusted`；呼叫端為 `technical.distributionAdjusted()`）。
  - `StockDecision` 的 positional constructor 有**兩個**呼叫點：`buildStock()` 成功路徑與 `incompleteStock()`；`MarketSummary` 有 `buildMarket()` 與 `incompleteMarket()`。
- `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`
  - `marketSheet()`／`stockSheet()` 各自持有 `headers`／`formats`／`rows` 三份清單，長度必須相等（`ExportDoc.Table` 的 compact constructor 在 runtime 檢查）。
  - 取值 helper：`txt(JsonNode, field)` 缺值回 `""`、`num(JsonNode, field)` 非數值回 `null`、`boolVal(...)`、`listVal(...)`。
- 測試：`backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java` 以 `GoldenWorkbooks.assertSame(GoldenWorkbooks.golden("radar"), actual)` 逐列逐格比對 `backend/src/test/resources/golden/radar.xlsx`／`radar_empty.xlsx`。`GoldenWorkbooks.golden(String name)` 只做 `getResourceAsStream("/golden/" + name + ".xlsx")`，**沒有白名單**，故新增檔名即可直接讀。同檔另有**三組寫死的欄索引**：`:154-160`（個股決策 `getCell(33)` 共三處，index 33 ＝「逆勢條件」）、`:145`（大盤總覽 `getCell(18)` ＝「支持訊號」）、`:174-175`（大盤總覽 `getCell(14)` 共兩處 ＝「季線確認」）。
- `backend/src/test/java/com/steven/assets/service/TradingRadarSnapshotStoreTest.java:73` 以 positional constructor 建 `MarketSummary`，DTO 一改欄位就編譯失敗。
- **另有兩支測試 stub `indicatorService.computeAll(...)` 來驅動 `buildMarket()`**：`TradingRadarMarketFreshnessTest.java:100`（`lenient().when(indicatorService.computeAll(anyString(), anyString()))`）與 `TradingRadarServiceOwnerScopeTest.java:87`。這兩支**沒有** `new TradingRadarDto.`，用 DTO constructor 當關鍵字掃不到。

### 為什麼不能直接叫走勢圖那支 `indicatorSeries()`

雷達個股表的價基是 `DistributionAdjustedPriceService.adjust()` **還原配息／除權**後的序列；走勢圖的 `indicatorSeries()` 是**原始價基**。`design.md` 既有鐵則明訂「禁止混用原始／還原價」。若擴充指標改由 `indicatorSeries()` 取，同一列會同時出現兩種價基的值，`j9 = 3D − 2K` 對該列的 `k`／`d` 不再成立（有配息的個股必然對不上），而且雷達逐檔迴圈會變成 N 次全史掃描。

**連帶結論（驗收時務必記住）**：本任務產出的值與使用者雙擊該列開啟的走勢圖 popup 顯示的值，**凡視窗內有配息／除權的個股必然不同，這是預期行為**，不得為了讓兩邊一致而改動任一方，也不得拿兩者互相比對當驗收。

## 要做什麼

### A. DTO（`backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`）

- [ ] 273.1 新增巢狀 record（**用 record，不得用 `@Data` 可變 class**）：

  ```java
  /**
   * 走勢圖指標選單（Task 262）同一組值的雷達版（Task 273）。
   *
   * <p><b>純揭露：不參與評分</b>——不進 {@code StockInput}／{@code MarketInput}，
   * 不影響 action／score／regime／buyGate／kdHeat／timingState，故 RULE_VERSION 不升版。</p>
   *
   * <p><b>價基與同一列的 kValue／dValue 相同</b>：個股為還原權息序列、大盤為指數日線序列，
   * 與雙擊開啟的走勢圖（原始價基）刻意不同，有配息的個股必然對不上。</p>
   *
   * <p>暖機／視窗不足的欄位為 {@code null}，不得以 0 充數。全部 2 位小數。</p>
   */
  public record ExtendedIndicators(
          BigDecimal j9, BigDecimal k3d2, BigDecimal rsv,
          BigDecimal ema12, BigDecimal ema26, BigDecimal dif, BigDecimal macd, BigDecimal osc,
          BigDecimal rsi5, BigDecimal rsi10,
          BigDecimal bias10, BigDecimal bias20, BigDecimal b10b20,
          BigDecimal wr9) {}
  ```

- [ ] 273.2 `MarketSummary` 與 `StockDecision` 各新增**一個** component `ExtendedIndicators extendedIndicators`，**加在該 record 的最末**（兩者皆然）。
  **刻意不攤平成 14 個欄位**：兩個 record 的 positional constructor 各有兩個呼叫點，攤平要多寫 56 個 `null`／值，且 `incompleteStock()` 那一串連續 `null` 已經難以對位。巢狀一個欄位讓 JSON 快照長成 `"extendedIndicators": { "j9": …, … }`，匯出端以 `d.path("extendedIndicators")` 取子節點即可。
  ⚠️ `RefreshResponse` 的既有 javadoc 寫「`Response` **不得為此新增欄位**」——那是指「不得為了手動重新整理這個功能而在 `Response` 上加欄位」，本任務加的是 `MarketSummary`／`StockDecision` 內部欄位、`Response` 本身的 5 個 component 不變，`GET` 與 `POST /refresh` 仍完全同形，不牴觸。

### B. 指標服務（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`）

- [ ] 273.3 新增 **package-private** 方法（與既有 `computeFromSeries` 同可見性，`TradingRadarService` 在同一 package）：

  ```java
  /**
   * 對已備妥的 <b>desc</b> OHLC 序列算出「最新一期」的完整指標點（Task 273）。
   * 呼叫端必須餵入與 {@link #computeFromSeries} <b>同一份序列</b>（同一個變數，或同一個 backing list
   * 的同一段 subList），兩者的 k／d 才保證逐位相同。序列為 null／空時回 null。
   */
  IndicatorPoint latestFromSeries(List<StockPriceHistory> desc)
  ```

  - 實作：`asc = new ArrayList<>(desc).reversed()` → 呼叫**既有**的 `kdSeriesAsc(asc)`／`macdSeriesAsc(asc)`／`rsiSeriesAsc(asc, 5)`／`rsiSeriesAsc(asc, 10)`／`biasRaw(asc, last, 10)`／`biasRaw(asc, last, 20)`／`maAt(asc, last, 5/20/60/240)`，取 `last = asc.size() - 1` 的那一期組成 `IndicatorPoint`。
  - **`wr9` 的算法必須與 `indicatorSeries()` 完全相同**：`p.rsv() == null ? null : BigDecimal.valueOf(100).subtract(p.rsv()).setScale(2, RoundingMode.HALF_UP)`。
  - **`b10b20` 必須用未捨入值相減後才捨入**：`(b10 == null || b20 == null) ? null : scale2(b10 - b20)`。
  - ⚠️ **不得新增任何第四套 KD／MA／MACD／RSI 遞迴**，也不得複製既有遞迴的內容。本方法只做「反轉序列 → 呼叫既有核心 → 取尾筆」。
  - ⚠️ **不得改動 `kdSeriesAsc`／`macdSeriesAsc`／`rsiSeriesAsc`／`biasRaw`／`maAt`／`scale2`／`simpleMa`／`stockKd`／`taiexKd`／`taiexSimpleMa` 的任何一行**——`computeAll()` 的對外數值（警示觸發門檻、觀察清單 KD 欄、Task 146 的 MA% 換算觸發價、走勢圖同源判準）全綁在上面。
  - **`indicatorSeries()` 內既有的逐日組裝邏輯不得為此重構**：本方法與它是兩條獨立路徑（一條吃全史 asc、一條吃 desc 尾筆），硬要抽共用會把 `indicatorSeries` 的暖機切段（`date.isBefore(start) continue`）攪進來。**允許**且**應該**的共用只到序列核心那一層（`kdSeriesAsc` 等），那已經是共用的。

- [ ] 273.4 大盤路徑：新增 public record 與 public 方法，**確保一次取數同時產出兩組值**：

  ```java
  /** 交易雷達大盤用（Task 273）：一次取數同時回既有 FullIndicators 與擴充指標點。 */
  public record TaiexIndicators(FullIndicators core, IndicatorPoint extended) {}

  @Transactional(readOnly = true)
  public TaiexIndicators computeTaiexWithExtended()
  ```

  作法：把現行 `computeAllForTaiex()` 的**取數段**（`findTopNByOrderByTradingDateDesc(240)` ＋ 今日 live 合成列插入 index 0）抽成 private helper 回 `List<TwseIndexDailyHistory>`；`computeAllForTaiex()` 與新方法都吃同一份 helper 輸出。新方法把該 desc 清單以**既有 `taiexSeriesAsc()` 內同一組欄位對應**（`closePrice←getClosePoint()`、`highPrice←getHighPoint()`、`lowPrice←getLowPoint()`）映射成 `List<StockPriceHistory>` 後呼叫 273.3 的 `latestFromSeries()`。

  - ⚠️ **新方法必須沿用 `computeAllForTaiex()` 既有的 fail-soft `try/catch`**，失敗時回 `new TaiexIndicators(FullIndicators.EMPTY, null)` 並記 `log.warn`。**不得讓例外逸出到 `buildMarket()`**——那會被 `TradingRadarService.java:253` 既有的 `catch (Exception e)` 接住 → `incompleteMarket(...)` → `regime=DATA_INCOMPLETE`、`stale=true`、三個 confirmation 全 `UNAVAILABLE`，而 `buildStock()` 收到 `DATA_INCOMPLETE` 後**全部個股買進閘門一律關閉**。本任務把「指標暫時算不出來」放大成「今天整張雷達停發訊號」是絕不可接受的爆炸半徑，而新方法比舊方法多做了「指數日線 → `StockPriceHistory` 映射 ＋ `latestFromSeries`」（`diOf`／`maAt` 都直接 `.getClosePrice().doubleValue()`），例外面積只增不減。既有測試全用 mock，永遠走不到真實例外，**這條只能靠 273.14 的專屬測試釘住**。

  - ⚠️ **`computeAll("0000","台股")` 的回傳值必須一個位元都不變**：`core` 仍由既有 `taiexSimpleMa()`／`taiexKd()` 對同一份 desc 清單算出。抽取取數段是純重構。
  - ⚠️ **不得為此新增第四套 KD 遞迴**，也不得改走 `taiexSeriesAsc()`（那是全史查詢、給走勢圖用的，用在雷達會變成每次請求掃全史）。
  - ⚠️ **不得讓 `TradingRadarService` 自己去映射指數日線**——映射與 live 合成規則必須留在本服務內，否則同一件事會有兩份實作而漂移。
  - **映射不得只複製 `closePoint`**：`highPoint`／`lowPoint` 直接進 KD 的 RSV 分母與 MACD 的 DI 價基，漏掉會讓 `k` 與 `core.k()` 對不上（舊資料 high/low 為 null 時由核心自行 fallback close，這是既有慣例，不需在映射層補值）。

### C. 雷達服務（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`）

- [ ] 273.5 private record `TechnicalData` 新增第 9 個 component `TechnicalIndicatorService.IndicatorPoint extended`。`prepareTechnicalData()`：
  - 空序列的早退分支 → `extended` 傳 `null`。
  - 正常路徑 → `indicatorService.latestFromSeries(...)` 吃**與 `computeFromSeries(...)` 完全同一份序列**，不得另算一個視窗。
    **具體作法**：把現行 `:419` 的 `List<StockPriceHistory> window = adjustedRows.subList(0, indicatorRows);` **上移到 `:405` 的 `computeFromSeries(...)` 呼叫之前**，改成 `computeFromSeries(window)` 與 `latestFromSeries(window)` 共用同一個變數——讓「同一份」在程式碼上目視可見，而不是靠兩處各寫一次相同的 `subList` 運算式（那是兩個不同的 List 物件，內容相同但看不出約束）。`window` 在 `:420-421`（`maxHigh`／`minLow`）與 `:428-429`（`window.size() >= 240` 的守門）的既有用途不變；`:422-423` 的 `bandWidthPercent` 吃的是另一個 9 筆 subList，不受影響。
- [ ] 273.6 `buildStock()` 成功路徑把 `technical.extended()` 經 273.8 的 mapper 轉成 `TradingRadarDto.ExtendedIndicators` 後放進 `StockDecision` 最末欄；`incompleteStock()` 該欄傳 `null`。
- [ ] 273.7 `buildMarket()` 把 `indicatorService.computeAll(TAIEX_CODE, TW_MARKET)` 改為 `indicatorService.computeTaiexWithExtended()`，`ind` 取 `.core()`（**其餘用到 `ind` 的地方一行都不改**），擴充值取 `.extended()` 經 mapper 放進 `MarketSummary` 最末欄；`incompleteMarket()` 該欄傳 `null`。
  ⚠️ **`.extended()` 可能為 `null`**（序列為空時 `latestFromSeries` 回 `null`），mapper 必須容忍——但**不得**為此在 `buildMarket()` 補「整個回傳值為 null 就吞掉」的防護：那會把 273.17 要修的測試 stub 缺漏靜默化成「大盤資料不足」。
- [ ] 273.8 新增 private static mapper `TradingRadarDto.ExtendedIndicators toExtended(TechnicalIndicatorService.IndicatorPoint p)`：`p == null` 回 `null`，否則逐欄搬 14 個值。
  ⚠️ **只准讀那 14 個欄位**。`IndicatorPoint` 另有 `tradingDate`／`ma5`／`ma20`／`ma60`／`ma240`／`k`／`d` 共 7 個欄位，**一律不得用來填 DTO 的 `weeklyMa`／`monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue`**——那些必須維持取自 `FullIndicators`，否則同一列會出現兩個 MA 來源（`maAt` 與 `simpleMa` 雖已對齊累加方向，仍是兩份程式碼）。
- [ ] 273.9 **不得把擴充值餵進規則引擎**：`TradingRadarRuleEngine.StockInput`／`MarketInput` 的欄位數與內容一律不動；`RULE_VERSION` 維持 `TW_RULES_V9` **不升版**。
  理由是「**規則集本身未變**」——不新增／不修改任何因子、權重或動作門檻，同一份輸入產生逐位相同的 `action`／`score`／`regime`／`reasons`／`risks`。
  ⚠️ **刻意不援引 Task 249 那條「同一份輸入前後產生完全相同的輸出」**：本任務的輸出結構確有變化（DTO 多一個巢狀欄位、匯出檔多 15 欄、快照雜湊必然改變），**不滿足**該條件。寫成「符合 Task 249」會把一條治理判準改寫掉，下一個任務會照著引用。

### D. 匯出（`backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`）

- [ ] 273.10 新增巢狀取值 helper（不得改動既有四支 helper 的行為）：

  ```java
  /** 巢狀子節點的數值欄；父節點缺漏／為 null 時回 null（舊快照沒有 extendedIndicators）。 */
  private static BigDecimal num(JsonNode parent, String child, String field) {
      return num(parent.path(child), field);
  }
  ```

  `JsonNode.path()` 對 MissingNode／NullNode 都回 MissingNode，故舊快照自然得到 `null` → Excel 空白格、JSON `null`。**不得補 0、`"-"` 或空字串。**

- [ ] 273.11 `marketSheet()`：headers 由 20 → **35**，順序**逐字**如下（`週線MA5` 插在 `MA20` 前、14 欄插在 `D` 後）：

  ```
  0  快照時間        1  regime        2  中文          3  分數
  4  資料完整        5  stale         6  盤中即時      7  即時更新時間
  8  完成日K         9  最新點位      10 漲跌%         11 週線MA5      ← 新
  12 MA20            13 MA60          14 MA240         15 季線確認
  16 年線確認        17 K             18 D
  19 J9              20 K3D2          21 RSV           22 EMA12        ┐
  23 EMA26           24 DIF           25 MACD          26 OSC          │ 新 14 欄
  27 RSI5            28 RSI10         29 BIAS10        30 BIAS20       │
  31 BIAS10-BIAS20   32 W%R9                                           ┘
  33 支持訊號        34 風險提醒
  ```

  - `rows` 對應：index 11 → `num(m, "weeklyMa")`；index 19–32 → `num(m, "extendedIndicators", "j9"/"k3d2"/"rsv"/"ema12"/"ema26"/"dif"/"macd"/"osc"/"rsi5"/"rsi10"/"bias10"/"bias20"/"b10b20"/"wr9")`。
  - `formats`：新增的 15 欄全部 `ExportDoc.Format.NUM2`；既有欄的 Format 一個都不改。**現行 formats 是單行的 `List.of(...)`，本任務一併改成比照 `stockSheet()` 的逐行 ＋ index 註解寫法**——20 → 35 欄後單行已無法目視對位，而 Task 264 的插欄事故正是三份清單靜默不同步。

- [ ] 273.12 `stockSheet()`：headers 由 35 → **50**，順序**逐字**如下：

  ```
  0  快照時間   1  代碼      2  名稱      3  市場      4  資產類別
  5  持有       6  還原權息  7  動作      8  動作中文  9  分數
  10 逆勢狀態   11 逆勢中文  12 現價      13 漲跌%     14 行情更新
  15 完成日K    16 週線MA5   ← 新
  17 MA20       18 MA60      19 MA240     20 月線確認  21 季線確認
  22 年線確認   23 K         24 D
  25 J9         26 K3D2      27 RSV       28 EMA12     ┐
  29 EMA26      30 DIF       31 MACD      32 OSC       │ 新 14 欄
  33 RSI5       34 RSI10     35 BIAS10    36 BIAS20    │
  37 BIAS10-BIAS20            38 W%R9                  ┘
  39 匯率分位   40 底層幣別  41 資料完整
  42 時機       43 季線乖離% 44 52週位置  45 折溢價%
  46 支持訊號   47 風險提醒  48 逆勢條件  49 逆勢風險
  ```

  - `rows` 對應：index 16 → `num(d, "weeklyMa")`；index 25–38 → `num(d, "extendedIndicators", …)` 同上 14 個 key。
  - `formats` 逐行清單的 index 註解**全部重新編號**（既有 27–30 的「Task 264 四欄」註記保留，index 改為 42–45）。
  - **同檔另有兩處會失真的既有註解，必須一併更新**（它們是下一次插欄時唯一的目視對位依據）：`:171` 的「`// Task 264 的四欄（index 27–30）…`」改為 `index 42–45`；`:178` 的「`// 逐列對齊上面的 headers（35 欄）。`」改為 `50 欄`。
  - ⚠️ 三份清單（`headers`／`formats`／`rows` 的 `Arrays.asList(...)`）必須同步位移；`ExportDoc.Table` 只在 runtime 才擲長度不符。

- [ ] 273.13 **「快照索引」分頁一欄都不動。**

### E. 測試（與實作同一支任務，不得延後）

- [ ] 273.14 `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorSeriesAlignmentTest.java`（既有 Task 261／262 測試類，沿用其 `@ExtendWith(MockitoExtension.class)` ＋ `@Mock` ＋ AssertJ ＋繁體中文方法名）新增：
  - **`latestFromSeries` 與 `computeFromSeries` 的 K／D 逐位相同**：同一份 desc fixture（≥ 250 筆、含 high/low 為 null 的列）餵兩者，斷言 `latestFromSeries(...).k()／d()` == `computeFromSeries(...).k()／d()`。這是「同一列的 `j9 = 3D − 2K` 成立」的機械前提。
  - **`latestFromSeries` 與 `indicatorSeries` 尾筆逐位相同（同一份輸入時）**：stub `historyRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(...)` 回同一份 asc 序列、`end` 早於今日（避免併 live），斷言 `indicatorSeries(...)` 尾筆的 14 個擴充欄 == `latestFromSeries(descOfSameData)` 的對應欄。證明沒有長出第二套公式。
  - **241 根視窗對 MACD／RSI 足夠收斂（本任務唯一真正有風險的數值判準，必測）**：同一份 ≥ 500 筆的 fixture，比較 `latestFromSeries(全部)` 與 `latestFromSeries(前 241 筆)` 的 `ema12`／`ema26`／`dif`／`macd`／`osc`／`rsi5`／`rsi10`，斷言差值 ≤ `0.01`。
    ⚠️ **不可改成斷言「完全相等」**：2 位小數捨入邊界上可能差 1 個末位。也**不可只測 KD／BIAS／W%R／MA**——RSV／MA／BIAS 是固定視窗，本來就恆等；K／D 雖是遞迴但 seed 衰減率 2/3、232 步後殘留 `≈1.4×10⁻⁴¹`，實測 double 層級即 bit-identical。這四類全部測不到本任務真正的風險（MACD／RSI 的單向遞迴被截斷）。
  - **`0000` 台股大盤兩套實作等值**：stub `twseDailyRepo` 一組 ≥ 250 筆指數日線，斷言 `computeTaiexWithExtended().core()` 與 `computeAll("0000","台股")` 的**全部 8 個 component 逐一相等**——`record` 有 value equality，直接 `assertThat(both.core()).isEqualTo(single)` 即可。
    ⚠️ **不可只比 `monthlyMa`／`quarterlyMa`／`annualMa`／`k`／`d` 五個**：273.4 的硬約束是「回傳值一個位元都不變」，而 `previousK`／`previousD` 由 `taiexKd(desc.subList(1, desc.size()))` 算出、對取數段抽取後的清單邊界最敏感，`weeklyMa`（Task 265 才加）則是抽取重構中最容易被漏掉的那個。
    另斷言 `.extended().k()／d()` == `.core().k()／d()`（證明指數日線 → `StockPriceHistory` 的映射沒漏 `highPoint`／`lowPoint`）。
  - **null／空序列**：`latestFromSeries(null)` 與 `latestFromSeries(List.of())` 皆回 `null`，不擲例外。
  - **大盤 fail-soft（273.4 的爆炸半徑保護，必測）**：stub `twseDailyRepo.findTopNByOrderByTradingDateDesc(anyInt())` 擲 `RuntimeException`，斷言 `computeTaiexWithExtended()` **不擲例外**、`.core()` 為 `FullIndicators.EMPTY`、`.extended()` 為 `null`。沒有這條，例外逸出會讓整張大盤卡變 `DATA_INCOMPLETE`、全部個股停發訊號，而其他測試全用 mock 走不到真實例外。
  - Mockito 在本專案的 Java 版本下需要 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`（見驗證段），**不要用 `-DargLine`**（會覆蓋 pom 的時區設定導致大量測試 error）。

- [ ] 273.15 `backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java`：
  - **三組寫死的欄索引全部依 273.16 的映射改掉**（漏任一組都會驗到別的欄位，且對數值格呼叫 `getStringCellValue()` 會擲例外）：
    - `:154-160` 個股決策的 `getCell(33)` **共三處**（`:157`／`:159`／`:160`）→ `getCell(48)`；`:155` 的註解一併改寫，寫明本次插欄的位移規則。
    - `:145` 大盤總覽的 `getCell(18)`（「支持訊號」）→ `getCell(33)`。
    - `:174-175` 大盤總覽的 `getCell(14)` **共兩處**（「季線確認」）→ `getCell(15)`。
    - （`:128`／`:129` 的 `getCell(4)`／`getCell(5)` 與 `TradingRadarExportServiceTest` 全檔的 `getCell(0)` 都在插入點之前，不必動。）
  - 新增：**兩張分頁的表頭逐字等於 273.11／273.12 的清單**（用 `assertThat(headerRow).containsExactly(...)`，含順序），大盤 35 欄、個股 50 欄。
  - 新增：fixture `snapshotNode()` 的 `market`／`stocks` 各補 `weeklyMa` 與 `extendedIndicators` 子物件，斷言 (a) Excel 對應格為 `CellType.NUMERIC` 且 `getCellStyle().getDataFormatString()` 等於 **`"#,##0.00"`**；(b) JSON 對應 key 為 JSON number 且值與 fixture 相同。
    ⚠️ **格式字串是 `#,##0.00`，不是 `0.00`**（`ExcelDocRenderer.java:236-237` 的 `num2.setDataFormat(fmt.getFormat("#,##0.00"))`，對應 Excel builtin `numFmtId=4`）。寫成 `"0.00"` 會 100% 紅，而失敗訊息 `expected "0.00" but was "#,##0.00"` 最直覺的「修法」是去改 `ExcelDocRenderer` 的格式字串——**那會同時打壞全部 9 份 golden**（`GoldenWorkbooks` 逐格比 `dataFormat`）。
    ⚠️ **不可拿「既有的 `MA20` 那格」當 `dataFormat` 對照組**：`snapshotNode()`（`:198-231`）的欄名**刻意與 DTO 不同名**——它寫 `m.put("ma20", 22800.0)`／`m.put("k", 68.1)`／`m.put("latestPoint", …)`／`s1.put("code", …)`，而匯出端讀的是 `monthlyMa`／`kValue`／`price`／`stockCode`。故 golden 裡多數數值欄其實是 `num()` 回 `null` → `ExcelDocRenderer.cell()` 先 `createCell` 才 `if (value == null) return`（`omitNullCells=false`）→ **存在但無樣式的 BLANK 格、`dataFormat` 為 General**，拿去當對照必紅。若真要比對照組，市場那張用 index 3「分數」（fixture `score` 同名、NUM2 有值）、個股那張用新 index 43「季線乖離%」（fixture `ma60BiasPercent` 同名）。
  - 新增：**舊快照相容**——另一組完全不含 `weeklyMa` 與 `extendedIndicators` 的 fixture，斷言匯出不擲例外、新增 15 欄在 Excel 為空白格（`CellType.BLANK`）、在 JSON 為 `null`。
- [ ] 273.16 **golden 基準重產＋插欄比對**：
  - 先把現行 `backend/src/test/resources/golden/radar.xlsx`／`radar_empty.xlsx` **複製**為 `radar_pre_t273.xlsx`／`radar_empty_pre_t273.xlsx`（`git mv` 不行，兩個新名與原名都要存在）。
  - **順序不可顛倒：先做 273.15 的 fixture 補欄，再重產 golden。** `snapshotNode()` 是兩條零回歸測試與其他斷言**共用**的同一份 fixture，補了 `weeklyMa`／`extendedIndicators` 之後，重產出的 `radar.xlsx` 新 15 欄會是 **NUMERIC 有值**（不是空白）。空白格的驗證由 273.15 最後那組「舊快照相容」的**獨立** fixture 負責，兩者不得混為一談。（t271 已踩過同型的坑：「golden fixture 必須與產生器逐字相同——交易雷達的測試一度多加了 `reasons`／`risks` 兩欄而與 golden 對不起來」。）
  - **重產方式（repo 內沒有 golden 產生器，必須自己來）**：暫時在 `TradingRadarDualFormatTest` 加一支拋棄式測試，**store stub 與 `snapshotNode()` 必須與該檔現行的完全同一份**，把 bytes 寫出後刪掉該測試：
    ```java
    Files.write(Path.of("src/test/resources/golden/radar.xlsx"),       service.exportForOwner(1L, 0L, 1L));
    Files.write(Path.of("src/test/resources/golden/radar_empty.xlsx"), service.exportForOwner(2L, 0L, 1L));
    ```
    （`mvn -f backend/pom.xml` 的 CWD 是 `backend/`，故路徑不含 `backend/` 前綴。`exportForOwner(2L, …)` 對應零快照那條測試既有的 stub。）
  - 重產後既有兩條零回歸測試改對新基準。
  - **新增一條測試 `插欄前後既有欄逐格未變`**：對「大盤總覽」以 `old → old < 11 ? old : (old < 18 ? old + 1 : old + 15)`、對「個股決策」以 `old → old < 16 ? old : (old < 24 ? old + 1 : old + 15)` 的映射，逐格比對值、`CellType`、`dataFormat`、粗體、字級；「快照索引」以 identity 映射比對。
    **兩份 pre 基準都要比**：`radar_pre_t273` 比全表；`radar_empty_pre_t273` 比各分頁的表頭列——⚠️ **零快照時「快照索引」是 2 列不是 1 列**（`indexSheet()` 恆先寫一列 `ExportDoc.Line` 提示列，表頭在第 1 列），故該分頁比第 0–1 列，「大盤總覽」「個股決策」比第 0 列即可。用途是證明 35／50 欄的既有**表頭字串**沒被改壞。`GoldenWorkbooks.golden(String name)` 只做 `getResourceAsStream("/golden/" + name + ".xlsx")`、沒有白名單，新檔名可直接用。
    ⚠️ **只重產 golden 不做這條比對是不夠的**——那樣「新增了欄」與「順手把既有欄改壞」在測試上完全無法分辨，而本次要動的正是三份平行清單的索引。
- [ ] 273.17 修既有測試的三處呼叫端。**掃描用 `grep -ran "TradingRadarDto\.\|indicatorService\.\|computeAll(" backend/src/test`**——只掃 `new TradingRadarDto.` 抓不到下面第 2、3 項（它們是 Mockito stub，不建 DTO）：
  1. `TradingRadarSnapshotStoreTest.java:73` 的 `new TradingRadarDto.MarketSummary(...)` 補最後一個引數（傳 `null`，該測試不驗指標）。
  2. `TradingRadarMarketFreshnessTest.java:100` 的 `lenient().when(indicatorService.computeAll(anyString(), anyString())).thenReturn(new FullIndicators(...))` 改 stub `computeTaiexWithExtended()`，回 `new TechnicalIndicatorService.TaiexIndicators(原本那份 FullIndicators, null)`。
  3. `TradingRadarServiceOwnerScopeTest.java:87` 同理，改回 `new TaiexIndicators(FullIndicators.EMPTY, null)`。

  ⚠️ **第 2、3 項不改的後果會偽裝成無關的迴歸**：`computeTaiexWithExtended()` 未 stub → Mockito 回 `null` → `result.core()` NPE → 被 `TradingRadarService.java:253` 既有的 `catch (Exception e)` 吞掉 → 走 `incompleteMarket(...)`（`stale=true`／`intraday=false`／`liveUpdatedAt=null`／confirmation 全 `UNAVAILABLE`）。`TradingRadarMarketFreshnessTest` 的四個測試方法（`:115`／`:129`／`:143`／`:158`）中會**紅三條**（斷言在 `:123-125`／`:152-154`／`:168-171`），訊息指向「大盤新鮮度判定壞了」。
  ⚠️ **`:129` 那條（`completedKNotToday_liveMissing_stale_true_intraday_false`）會假性通過**——它的三個斷言（`:137-139`）恰好與 `incompleteMarket()` 的 `stale=true`／`intraday=false`／`liveUpdatedAt=null` 相符。所以「只剩它是綠的」不代表它沒受影響，補 stub 時不得跳過。
  ⚠️ **正確的修法是補 stub，不是放寬斷言、也不是在 `buildMarket()` 加 null 防護**——後兩者會把既有的迴歸保護拆掉。

### 明確不做的事

- [ ] 273.18 **不動前端**：`TradingRadarView.vue` 不新增欄位、不改展開列（使用者已明確選擇「只進匯出檔」）。雷達表已過寬，且這組值與雙擊開啟的走勢圖 popup 因價基不同必然對不上，同頁並列會造成誤解。
- [ ] 273.19 **不動走勢圖與其 BFF**：`indicatorSeries()`／`StockAnalysisChartBffController`／`ChartSeriesAligner`／`StockAnalysisDialog.vue` 一律不改。
- [ ] 273.20 **不動 `AlertChartRenderer`**（警示 email 的 PNG 圖自有一套 KD）、**不動 `computeAll()` 的對外數值行為**、**不動 `RULE_VERSION`**。
- [ ] 273.21 **不新增 `@Scheduled`**，故「公開資訊 → 排程列表」（`SchedulePublicBffController.JOBS`）不新增項目；**既有「交易雷達匯出」那一筆的 `description` 也不需改寫**——它描述的是「何時觸發、產出什麼檔、是否同步 Drive」，本任務只加欄不改觸發時機與檔名，該段文字改動後仍為真。
- [ ] 273.22 **不動 Liquibase**（本任務零 DB schema 變更；擴充值不入庫，只進 Redis 快照與匯出檔）。

## 驗證

### 1. 後端測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

（**不要**用 `-DargLine`，會覆蓋 pom 的時區設定導致大量測試 error。）

### 2. 部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）

從 worktree 跑 compose 前要先把主 repo 的 `.env` 複製進來（`env_file` 相對 compose 檔解析，`--env-file` 救不了）：

```bash
cp /Users/steven/Project/asset-management/.env .
```

JVM 服務改動後 cached build 可能產出不含本次變更的 jar，一律 `--no-cache`：

```bash
docker compose -p asset-management build --no-cache business-services
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services && docker compose -p asset-management restart bff
```

⚠️ recreate `business-services` 會換 IP，BFF 握舊 IP 會回 500 且約 3 分鐘不自癒（Docker DNS TTL 600s），故一併 `restart bff`。

⚠️ 確認跑起來的 jar 真的含本次變更（**不可用裸 `grep` 抓 jar**——fat jar 內的 `.class` 是 DEFLATE 壓縮的，只有 ZIP entry 名是明文，對 class 內識別字恆為 0）：

```bash
docker exec asset-business-services sh -c 'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/dto/TradingRadarDto\$ExtendedIndicators.class | strings | grep -c wr9'
```

大於 0 即為新 jar。

### 3. 端點自測（免 OAuth，在 business 容器內直接打）

```bash
docker exec asset-business-services sh -c 'curl -s "http://localhost:8080/api/trading-radar" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE"' | python3 -m json.tool | head -80
```

檢查：`market.weeklyMa` 有值；`market.extendedIndicators` 與 `stocks[0].extendedIndicators` 各有 14 個欄位；自洽關係成立（其中 `k`／`d` 取**同一列的 `kValue`／`dValue`**）：

| 關係 | 容差 | 為什麼是這個數 |
|---|---|---|
| `j9 ≈ 3d − 2k`、`k3d2 ≈ 3k − 2d` | **0.03** | `kdSeriesAsc` 以**未捨入**的 k／d 算 j9／k3d2，三者各自 `setScale(2, HALF_UP)` → 上界 `3×0.005 + 2×0.005 + 0.005` |
| `dif ≈ ema12 − ema26`、`osc ≈ dif − macd`、`b10b20 ≈ bias10 − bias20` | **0.015** | 三個值各自捨入 → `2×0.005 + 0.005` |
| `wr9 = 100 − rsv` | **精確相等** | 實作直接拿已捨入的 `rsv` 相減，不引入第二次捨入 |

⚠️ **不可用 0.01 當通用容差**：`j9` 那條在數學上必然超出，會產生**必然的**假失敗，且失敗方向正好指向「兩邊價基不同源」這個最容易誤診的地方。

⚠️ **不要拿這些值去跟走勢圖 popup 或 Yahoo 比對**：雷達走還原權息價基，有配息的個股必然不同，那不是 bug。**恆等式自洽是本步唯一有效的判準**；逐位比對留給 273.14 的單元測試。

### 4. 匯出檔實測

上一步**通常**已寫入一筆快照。⚠️ **若下面取回的檔新增 15 欄全空，先確認不是節流**——`TradingRadarSnapshotStore.write()` 是「先節流、後去重」，`SNAPSHOT_MIN_INTERVAL` 預設 5 分鐘；若使用者或 SSE 在 5 分鐘內已打過 `GET /api/trading-radar`，第 3 步那次**不會**落新快照，匯出到的全是部署前的舊快照。Requirement 48 新 AC 只論證了「雜湊必然改變 → 不被去重擋下」，對節流沒有豁免。查法：

```bash
docker exec asset-redis redis-cli ZREVRANGE trading-radar:snap:idx:1 0 0 WITHSCORES
```

時間戳距上次寫入未滿 5 分鐘就等滿再打一次第 3 步，不是 bug。

確認有新快照後，取回當日區間的檔：

```bash
docker exec asset-business-services sh -c 'curl -s "http://localhost:8080/api/trading-radar/export?from=2026-08-02T00:00:00&to=2026-08-02T23:59:59" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" -o /tmp/radar.xlsx && ls -l /tmp/radar.xlsx'
```

（`from`／`to` 改成當日日期。）再以 `docker cp` 取出開檔，確認：

- 「大盤總覽」共 35 欄、「個股決策」共 50 欄，「快照索引」仍 8 欄。
- `週線MA5` 在 `MA20` 左邊、14 個指標欄在 `D` 右邊，數值欄**沒有**落在「支持訊號／風險提醒／逆勢條件／逆勢風險」右側。
- 數值與第 3 步 API 回應逐位相同。
- 排程那一份 `.json`（Requirement 55 雙格式）內對應 key 為 JSON number、缺值為 `null`。

### 5. 舊快照相容（實機）

匯出區間**涵蓋本次部署之前**的舊快照（例如 `from` 取前一日），確認舊那幾列的 15 個新欄為空白、不是 0，且整份檔仍正常開啟。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
