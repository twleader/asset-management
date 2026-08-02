# [t280] 交易雷達匯出補上週線 MA5 與走勢圖指標選單的 14 個值（大盤總覽 +15 欄、個股決策 +15 欄）

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本次追加「擴充技術指標欄位為純揭露、不參與評分」）＋ Requirement 48（今日交易雷達結果快照落地 Redis 與 Excel 區間匯出；本次追加「匯出檔補齊週線 MA5 與走勢圖指標選單的同一組值」）
**前置任務:** 無（Task 262 已上線提供 `TechnicalIndicatorService` 的 MACD／RSI／BIAS／W%R 序列核心；Task 265 已上線提供 `weeklyMa`；Task 271 已把匯出改走 `ExportDoc`；Task 273 已把技術面組裝抽成 `RadarInputAssembler`）
**Liquibase changeset:** 無（不動資料庫）

> **與 t276 的關係（務必先讀）**：`spec/tasks/t276_unused_indicators_and_volume.md` 的 276.1 已規劃「**擴充 `FullIndicators` 的輸出**，由 `computeFromSeries(series)` 一併算出雷達需要的指標欄位」，並明訂「**一律新增輸出而非修改既有輸出**」。本任務即照該方向落地那一步（但**只到揭露為止，一個值都不進評分**）；t276 之後只需把已在 `FullIndicators` 裡的值接進 `StockInput`。
> **不得另建第二條取值路徑**（例如為匯出單獨寫一支 `latestFromSeries()`）——那會讓 t276 落地時出現兩份同義輸出，正是本專案反覆吃虧的漂移來源。

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

1. **週線 MA5**：`weeklyMa` 自 Task 265 起就同時存在於 `TradingRadarDto.MarketSummary`（第 9 個 component）與 `StockDecision`，也顯示在畫面上（`frontend/src/views/TradingRadarView.vue:62` 大盤卡「週線 MA5」、`:112` 個股展開列「週線 MA5」），**但從未進匯出**。純粹是漏掉——不需新增任何計算，只要多寫一欄。
2. **走勢圖指標選單的 14 個值**：`J9`／`K3D2`／`RSV`／`EMA12`／`EMA26`／`DIF`／`MACD`／`OSC`／`RSI5`／`RSI10`／`BIAS10`／`BIAS20`／`BIAS10-BIAS20`／`W%R9`。這些**目前完全不進雷達的請求鏈**——雷達走 `computeFromSeries(series)` → `FullIndicators`（只有 8 個欄位），而含全部指標的 `IndicatorPoint` 只出現在走勢圖用的 `indicatorSeries()`。故 DTO 沒有、Redis 快照沒有、匯出自然也沒有。

### 匯出的資料流（決定了改動必須落在哪一層）

```
TradingRadarService.get()  →  TradingRadarDto.Response
      ↓（每次頁面計算／排程重算時寫入）
TradingRadarSnapshotStore.save()  →  Base64(gzip(mapper.writeValueAsString(resp)))  →  Redis
      ↓
TradingRadarExportService  →  store.range() 取回 JsonNode  →  ExportDoc  →  xlsx ＋ json
```

匯出端只讀 Redis 快照的 `JsonNode`，**不重新計算任何指標**。因此「要匯出這些值」＝「這些值必須先進 `TradingRadarDto`」。`TradingRadarSnapshotStore` 走 `mapper.writeValueAsString(resp)`／`mapper.valueToTree(resp)`，DTO 新增欄位會自動進快照，**不需改該類**。

### 現況重點（改動前請先確認這些仍屬實）

- `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`
  - `FullIndicators`（public record，`:48-59`）：`monthlyMa, quarterlyMa, annualMa, k, d, previousK, previousD, weeklyMa` — **8 個 component**，另有 `public static final FullIndicators EMPTY`（`:57`）。
  - `IndicatorPoint`（public record，`:69-92`）：`tradingDate, ma5, ma20, ma60, ma240, k, d, j9, k3d2, rsv, ema12, ema26, dif, macd, osc, rsi5, rsi10, bias10, bias20, b10b20, wr9` — 21 個 component，只供走勢圖。
  - `computeFromSeries(List<StockPriceHistory> series)`（`:147`，**package-private**、吃 desc 序列）→ `simpleMa(series, 5/20/60/240)` ＋ `stockKd(series)` ＋ `stockKd(series.subList(1,…))` → `new FullIndicators(...)`（`:159`）。
  - `computeAll(code, market)`（`:110`，`@Transactional(readOnly=true) public`）：`0000`＋`台股` 走 `computeAllForTaiex()`；否則取 `findRecentN(code, market, 240)` ＋ 併今日 live → `computeFromSeries(series)`。
  - `computeAllForTaiex()`（`:466`，**private**）：取 `twseDailyRepo.findTopNByOrderByTradingDateDesc(**240**)` ＋ 併今日 live 合成列 → `taiexSimpleMa()`／`taiexKd()` → `new FullIndicators(...)`（`:496`）。整段包在 `try`，`catch → return FullIndicators.EMPTY`（`:501-504`）。
  - 序列核心（皆 `private static`，吃 **asc** 序列）：`kdSeriesAsc(asc)` → `List<KdPoint>`（`k, d, j9, k3d2, rsv`，暖機不足 9 筆為 `KdPoint.EMPTY`）、`macdSeriesAsc(asc)` → `List<MacdPoint>`（`ema12, ema26, dif, macd, osc`）、`rsiSeriesAsc(asc, n)` → `Double[]`、`biasRaw(asc, i, days)` → `Double`、`maAt(asc, i, days)` → `BigDecimal`、`scale2(double)` → `BigDecimal`。
  - `taiexSeriesAsc(code, market, end)`（private）內已有「指數日線 → `StockPriceHistory`」映射：`closePrice←getClosePoint()`、`highPrice←getHighPoint()`、`lowPrice←getLowPoint()`。
- `backend/src/main/java/com/steven/assets/service/RadarInputAssembler.java`（Task 273 新增，`TradingRadarService` 與 `BacktestService` 共用的唯一組裝點）
  - `assemble(...)` 於 `:107-109` 呼叫 `indicatorService.computeFromSeries(adjustedRows.subList(0, indicatorRows))`，`indicatorRows = min(adjustedRows.size(), liveAdded ? FULL_WINDOW+1 : FULL_WINDOW)`。
  - 回 `Assembled`（`:52`），第一個 component 即 `TechnicalIndicatorService.FullIndicators indicators`；`Assembled.EMPTY`（`:70`）帶 `FullIndicators.EMPTY`。
  - **本任務一行都不改這支檔**：擴充值長在 `FullIndicators` 裡，隨 `indicators()` 免費傳出來。
- `backend/src/main/java/com/steven/assets/service/TradingRadarService.java`
  - `buildMarket()`（`:183`）：`:206` 呼叫 `indicatorService.computeAll(TAIEX_CODE, TW_MARKET)` 得 `ind`；`:221` 建 `MarketSummary`；`:243` 有 `catch (Exception e) → incompleteMarket(...)`。
  - `buildStock()`（`:249`）：`prepareTechnicalData(...)` 回 `RadarInputAssembler.Assembled`，`ind = technical.indicators()`；`:323` 建 `StockDecision`。
  - `incompleteMarket()`（`:600`，`:601` 建 `MarketSummary`）、`incompleteStock()`（`:619`，`:621` 建 `StockDecision`）。
- `backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`
  - `marketSheet()`／`stockSheet()` 各自持有 `headers`／`formats`／`rows` 三份清單，長度必須相等（`ExportDoc.Table` 的 compact constructor 在 runtime 檢查長度與重複欄名）。`marketSheet()` 的 formats 目前是單行 `List.of(...)`（`:141`）；`stockSheet()` 已是逐行 ＋ index 註解。
  - 取值 helper：`txt(JsonNode, field)` 缺值回 `""`、`num(JsonNode, field)` 非數值回 `null`、`boolVal(...)`、`listVal(...)`。
- **`new TechnicalIndicatorService.FullIndicators(...)` 的全部呼叫端（`grep -ran`，共 8 處）**——加 component 會讓這些**編譯失敗**：
  - main：`TechnicalIndicatorService.java:57`（`EMPTY`）／`:159`（`computeFromSeries`）／`:496`（`computeAllForTaiex`）、`StockAlertTriggerExportService.java:450`。
  - test：`StockAlertGroupLabelTest.java:72`／`:89`、`TradingRadarMarketFreshnessTest.java:104`、`export/LiveAssetsDualFormatTest.java:313`。
- 測試：`backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java` 以 `GoldenWorkbooks.assertSame(GoldenWorkbooks.golden("radar"), actual)` 逐列逐格比對 `backend/src/test/resources/golden/radar.xlsx`／`radar_empty.xlsx`。`GoldenWorkbooks.golden(String name)` 只做 `getResourceAsStream("/golden/" + name + ".xlsx")`，**沒有白名單**，新檔名可直接讀。同檔另有**三組寫死的欄索引**：`:154-160`（個股決策 `getCell(33)` 共三處 ＝「逆勢條件」）、`:145`（大盤總覽 `getCell(18)` ＝「支持訊號」）、`:174-175`（大盤總覽 `getCell(14)` 共兩處 ＝「季線確認」）。
- `backend/src/test/java/com/steven/assets/service/TradingRadarSnapshotStoreTest.java:73` 以 positional constructor 建 `MarketSummary`。

### 為什麼不能直接叫走勢圖那支 `indicatorSeries()`

雷達的價基是 `DistributionAdjustedPriceService.adjust()` **還原配息／除權**後的序列；`indicatorSeries()` 是**原始價基**。`design.md` 既有鐵則明訂「禁止混用原始／還原價」。若擴充指標改由 `indicatorSeries()` 取，同一列會同時出現兩種價基的值，`j9 = 3D − 2K` 對該列的 `k`／`d` 不再成立（有配息的個股必然對不上），而且雷達逐檔迴圈會變成 N 次全史掃描。

**連帶結論（驗收時務必記住）**：本任務產出的值與使用者雙擊該列開啟的走勢圖 popup 顯示的值，**凡視窗內有配息／除權的個股必然不同，這是預期行為**，不得為了讓兩邊一致而改動任一方，也不得拿兩者互相比對當驗收。

## 要做什麼

### A. 指標服務（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`）

- [x] 280.1 新增 public 巢狀 record（DTO 一律 `record`）：

  ```java
  /**
   * 走勢圖指標選單（Task 262）同一組值的單點版（Task 280）。
   * 暖機／視窗不足的欄位為 null（不是 0）；全部 setScale(2, HALF_UP)。
   * 價基由 computeFromSeries 的呼叫端決定（交易雷達餵還原權息序列），與同一份 FullIndicators 的 k/d 同源。
   */
  public record ExtendedIndicators(
          BigDecimal j9, BigDecimal k3d2, BigDecimal rsv,
          BigDecimal ema12, BigDecimal ema26, BigDecimal dif, BigDecimal macd, BigDecimal osc,
          BigDecimal rsi5, BigDecimal rsi10,
          BigDecimal bias10, BigDecimal bias20, BigDecimal b10b20,
          BigDecimal wr9) {
      public static final ExtendedIndicators EMPTY = new ExtendedIndicators(
              null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
  ```

- [x] 280.2 `FullIndicators` 新增**一個** component `ExtendedIndicators extended`，**加在最末**；`FullIndicators.EMPTY` 該欄填 `ExtendedIndicators.EMPTY`。
  **刻意不攤平成 14 個 component**：`FullIndicators` 有 8 個 positional 呼叫端（見現況重點），攤平要多寫 112 個引數；巢狀一個欄位讓每個呼叫端只多一個引數。t276 之後要接進 `StockInput` 時，`ind.extended().rsi5()` 一樣拿得到。

- [x] 280.3 新增 private 方法，對 desc 序列算出最新一期的 14 個值：

  ```java
  /** desc 序列 → 最新一期的擴充指標；序列為 null／空時回 ExtendedIndicators.EMPTY。 */
  private static ExtendedIndicators extendedOf(List<StockPriceHistory> desc)
  ```

  - 實作：`asc = new ArrayList<>(desc).reversed()`，`last = asc.size() - 1`，呼叫**既有**的 `kdSeriesAsc(asc)`／`macdSeriesAsc(asc)`／`rsiSeriesAsc(asc, 5)`／`rsiSeriesAsc(asc, 10)`／`biasRaw(asc, last, 10)`／`biasRaw(asc, last, 20)`，取第 `last` 期組值。
  - `wr9` **必須與 `indicatorSeries()` 逐字同式**：`p.rsv() == null ? null : BigDecimal.valueOf(100).subtract(p.rsv()).setScale(2, RoundingMode.HALF_UP)`。
  - `b10b20` **必須用未捨入值相減後才捨入**：`(b10 == null || b20 == null) ? null : scale2(b10 - b20)`。
  - `rsi5`／`rsi10` 為 `Double`，以 `scale2(...)` 轉 `BigDecimal`；null 保持 null。
  - ⚠️ **不得新增任何第四套 KD／MA／MACD／RSI 遞迴**，也不得複製既有遞迴的內容。本方法只做「反轉序列 → 呼叫既有核心 → 取尾筆」。
  - ⚠️ **不得改動 `kdSeriesAsc`／`macdSeriesAsc`／`rsiSeriesAsc`／`biasRaw`／`maAt`／`scale2`／`simpleMa`／`stockKd`／`taiexKd`／`taiexSimpleMa`／`indicatorSeries` 的任何一行**——`computeAll()` 的對外數值（警示觸發門檻、觀察清單 KD 欄、Task 146 的 MA% 換算觸發價、走勢圖同源判準）全綁在上面。

- [x] 280.4 `computeFromSeries(series)`（`:147`）改為**單趟 `kdSeriesAsc`**，同時供 current KD／previous KD／extended 三者取值，再把 `extendedOf` 需要的其餘序列（MACD／RSI×2／BIAS×2）算完，填進 `new FullIndicators(...)` 的新欄。
  - 由此，`RadarInputAssembler.assemble()`（`:107-109`）與 `computeAll()` 的一般個股路徑**自動**帶上擴充值，**兩支檔都不必改**。
  - **價基與 k/d 天然同源**：吃的就是同一個 `series` 參數，不是另一次取數。
  - **為什麼要合併成單趟（不是可有可無的優化）**：現況 `computeFromSeries` 已經跑**兩趟** `kdSeriesAsc`（`:155` 的 `stockKd(series)` 與 `:157` 的 `stockKd(series.subList(1, size))`，兩者各自在內部建整條序列）。若 `extendedOf` 再獨立跑第三趟，而 `kdSeriesAsc` 是本方法最貴的部分（每期一個 `subList` ＋ 兩個 9 元素 stream，`:324-328`），成本會明顯上升——**而 `computeFromSeries` 的最大宗呼叫端是 `BacktestService`**（`:238`／`:254-255` 與 `:547-552`：每檔標的 × 每個交易日各呼叫一次 `assembler.assemble(...)`，10 年史約 2,200 次／檔，再加大盤 regime 迴圈），`design.md` 對該端點的自述已是「分鐘級運算」。合併後由 3 趟降為 1 趟，本任務對該端點**淨持平或更快**。
  - **值逐位不變的證明（必須寫進程式碼註解）**：`kdSeriesAsc` 是對 asc 序列的**前綴相依**前向遞迴——`out[i]` 只依賴 `asc[0..i]`（視窗 `asc[i-8..i]` ＋ 由 index 0 累進的 `k`／`d`）。而 `stockKd(desc.subList(1, n))` 反轉後的 asc **正是完整 asc 的前綴 `asc[0..n-2]`**，故其尾筆恆等於完整序列的 `out[n-2]`。因此 `previousK`／`previousD` 改取單趟結果的**倒數第二筆**，與現況 bit-identical。
  - **長度守門會自然等價，唯一需要的判斷是索引下界**（`n = series.size()`，`out = kdSeriesAsc(asc)`）：
    - **current** ＝ `out[n-1]`，**不需要任何 `n >= 9` 判斷**——`kdSeriesAsc` 對 `i < 8` 一律 `out.add(KdPoint.EMPTY)`（`:323`），其 `k`／`d` 為 `null`，與舊版 `stockKd` 在 `size < 9` 回 `KdValues.EMPTY` 完全相同。
    - **previous** ＝ `n >= 2 ? out[n-2] : (null, null)`。`n == 1` 時 `out[-1]` 會越界，這是**唯一**必要的守門（舊版對應 `series.size() > 1` 的判斷）。`n` 介於 2～9 時 `out[n-2]` 的 index ≤ 7 → `KdPoint.EMPTY` → null，與舊版 `stockKd(size = n-1 < 9)` 回 EMPTY 相同。
    - 逐一代入 `n = 0/1/8/9/10` 皆與現況相同（`n == 0` 由 `:148` 的 `series.isEmpty()` 提前回 `FullIndicators.EMPTY`）。
  - ⚠️ **`kdSeriesAsc` 本體的公式一個字都不能改**；本項只改「呼叫幾次、從哪一格取值」。
  - **`stockKd`（`:305-310`）在合併後成為死碼，一併刪除**：全樹只有 `:155`／`:157` 兩個呼叫端，都在 `computeFromSeries` 內（`grep -rn "stockKd(" backend/src` 僅三筆命中：兩個呼叫點＋定義本身）。刪掉後 `kdSeriesAsc` 成為 Task 261 所宣告的「全站股票 KD 唯一的遞迴」名副其實；留一支無呼叫端的 `private static` 只會讓下一個人以為還有第二條路徑。

- [x] 280.5 `computeAllForTaiex()`（`:466`）把**已含今日 live 合成列的那個 `desc` 變數**（即 `:485` 的 `desc.isEmpty()` guard 之後、`taiexSimpleMa()`／`taiexKd()` 吃的**同一個變數**）映射成 `List<StockPriceHistory>` 後呼叫 `extendedOf(...)`，填進 `:496` 的 `new FullIndicators(...)`。
  - **映射抽成共用 private static helper**：`private static StockPriceHistory toRow(TwseIndexDailyHistory d, String code, String market)`（`closePrice←getClosePoint()`、`highPrice←getHighPoint()`、`lowPrice←getLowPoint()`），並讓 `taiexSeriesAsc()`（`:250-259`，內有同一組對應的 inline lambda）**改呼叫它**。
    理由：t276 的 276.2 要用 `volume` 做量價因子，屆時映射要多一個欄位；留兩份會漏一份而且不報錯。`taiexSeriesAsc` 不在 280.3 的「不得改動」清單內，改它只是把 lambda 換成方法呼叫、輸出完全相同。
  - ⚠️ **`core` 的 8 個既有欄位必須一個位元都不變**：仍由 `taiexSimpleMa()`／`taiexKd()` 對同一份 desc 清單算出。
  - ⚠️ **視窗維持既有的 `findTopNByOrderByTradingDateDesc(240)`，不得為新指標改成 241**。理由不是「改了會算錯」（240 vs 241 的 `k`／`d` 實測 bit-identical、`taiexSimpleMa(desc,240)` 只讀 `desc[0..239]`），而是那條路徑另有三個非雷達消費端（觀察清單 `0000` KD 欄、Requirement 44 通知門檻、走勢圖同源判準），動它超出本任務範圍且數值上無收益。
  - ⚠️ **映射不得只複製 `closePoint`**：`highPoint`／`lowPoint` 直接進 KD 的 RSV 分母與 MACD 的 DI 價基，漏掉會讓 `extended` 的 `rsv` 與 `core.k()` 對不上。舊資料 high/low 為 null 時由核心自行 fallback close（既有慣例，不需在映射層補值）。
  - ⚠️ **新增的計算必須留在既有的 `try` 內**，失敗仍回 `FullIndicators.EMPTY`。讓例外逸出會被 `TradingRadarService:243` 的 `catch` 接住 → `incompleteMarket(...)` → `regime=DATA_INCOMPLETE`、`stale=true`，而 `buildStock()` 收到 `DATA_INCOMPLETE` 後**全部個股買進閘門一律關閉**。把「指標暫時算不出來」放大成「今天整張雷達停發訊號」是絕不可接受的爆炸半徑。

- [x] 280.6 **視窗長度一律沿用各自路徑的既有值，不得延長也不得對齊**：個股 `min(size, liveAdded ? 241 : 240)`（`RadarInputAssembler`）、`computeAll` 一般路徑 240（+live）、大盤 240（+live）。MACD／RSI 雖是由序列最早一筆單向遞迴，既有視窗已足夠收斂——序列長 241 時 EMA26 的 SMA seed 落在 asc index 25、遞迴 215 步，殘留權重 `(1−2/27)^215 ≈ 6.5×10⁻⁸`；Wilder RSI10 的 seed 落在 index 10、遞迴 230 步，殘留 `0.9^230 ≈ 3×10⁻¹¹`；皆遠低於 2 位小數的捨入尺度 0.005，序列長 240 時各少一步、結論不變。**不得為新指標另開全史查詢**：那會讓同一列的 MA／KD 與 MACD／RSI 吃到不同長度的序列，且在雷達逐檔迴圈中放大成 N 次全史掃描。

- [x] 280.7 **成本揭露與必要的實測**：`computeFromSeries` 淨變化為「**KD 遞迴 3 趟 → 1 趟**（280.4）」＋「新增 MACD 一趟、RSI 兩趟、BIAS 兩趟 `maAt`」，n ≤ 241。新增的三類每元素成本遠低於 `kdSeriesAsc`（後者每期一個 `subList` ＋ 兩個 stream），預期淨持平或更快。
  受影響的呼叫端有兩類，**兩類都要在完成報告中交代**：
  - **高頻**：`BacktestService`（`:238`／`:254-255` 與 `:547-552`，每檔標的 × 每個交易日一次，經 `RadarInputAssembler.assemble()` → `computeFromSeries`）。`/internal/backtest/rules` 已是分鐘級運算，**必須實測改動前後的 wall time**（見驗證段第 7 步），倍率 > 1.2 即視為未達 280.4 的設計目標，須回頭查是否真的只跑一趟 KD。
  - **低頻**：`computeAll()` 的呼叫端——觀察清單（`WatchStockService`，僅 controller 觸發、該 view 無 `setInterval`／`EventSource`）、警示觸發落地（`StockAlertService` 的 `recordTrigger` 路徑，在 `triggered && tradingDayNow` 之後才走）、資產 Excel 匯出。**均不在即時價 tick 路徑上**，多算不用的值可忽略。
  - **不得**為此加 `boolean withExtended` 之類的旗標把路徑分岔——那正是「只有一條取值路徑」要避免的東西。

### B. DTO（`backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java`）

- [x] 280.8 新增巢狀 record（**用 record，不得用 `@Data` 可變 class**）：

  ```java
  /**
   * 走勢圖指標選單（Task 262）同一組值的雷達版（Task 280）。
   *
   * <p><b>純揭露：不參與評分</b>——不進 StockInput／MarketInput，不影響
   * action／score／regime／buyGate／kdHeat／timingState，故 RULE_VERSION 不升版。</p>
   *
   * <p><b>價基與同一列的 kValue／dValue 相同</b>：個股為還原權息序列、大盤為指數日線序列，
   * 與雙擊開啟的走勢圖（原始價基）刻意不同，有配息的個股必然對不上。</p>
   *
   * <p>暖機／視窗不足的欄位為 null，不得以 0 充數。全部 2 位小數。</p>
   */
  public record ExtendedIndicators(
          BigDecimal j9, BigDecimal k3d2, BigDecimal rsv,
          BigDecimal ema12, BigDecimal ema26, BigDecimal dif, BigDecimal macd, BigDecimal osc,
          BigDecimal rsi5, BigDecimal rsi10,
          BigDecimal bias10, BigDecimal bias20, BigDecimal b10b20,
          BigDecimal wr9) {}
  ```

- [x] 280.9 `MarketSummary` 與 `StockDecision` 各新增**一個** component `ExtendedIndicators extendedIndicators`，**加在該 record 的最末**。
  ⚠️ `RefreshResponse` 的既有 javadoc 寫「`Response` **不得為此新增欄位**」——那是指「不得為了手動重新整理這個功能而在 `Response` 上加欄位」；本任務加的是 `MarketSummary`／`StockDecision` 內部欄位、`Response` 本身的 5 個 component 不變，`GET` 與 `POST /refresh` 仍完全同形，不牴觸。

### C. 雷達服務（`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`）

- [x] 280.10 新增 private static mapper `TradingRadarDto.ExtendedIndicators toDto(TechnicalIndicatorService.ExtendedIndicators e)`：`e == null` 回 `null`，否則逐欄搬 14 個值。
- [x] 280.11 `buildStock()` 成功路徑（`:323` 的 `new TradingRadarDto.StockDecision(...)`）最末欄填 `toDto(ind.extended())`；`incompleteStock()`（`:621`）該欄傳 `null`。
- [x] 280.12 `buildMarket()` 成功路徑（`:221` 的 `new TradingRadarDto.MarketSummary(...)`）最末欄填 `toDto(ind.extended())`；`incompleteMarket()`（`:601`）該欄傳 `null`。
  **`:206` 的 `indicatorService.computeAll(TAIEX_CODE, TW_MARKET)` 一字不改**——擴充值已在同一個 `FullIndicators` 裡。
- [x] 280.13 **`RadarInputAssembler.java` 與 `prepareTechnicalData()` 一行都不改。**
- [x] 280.14 **不得把擴充值餵進規則引擎**：`TradingRadarRuleEngine.StockInput`／`MarketInput` 的欄位數與內容一律不動；`RULE_VERSION` 維持現值**不升版**。
  理由是「**規則集本身未變**」——不新增／不修改任何因子、權重或動作門檻，同一份輸入產生逐位相同的 `action`／`score`／`regime`／`reasons`／`risks`。
  ⚠️ **刻意不援引 Task 249 那條「同一份輸入前後產生完全相同的輸出」**：本任務的輸出結構確有變化（DTO 多一個巢狀欄位、匯出檔多 15 欄、快照雜湊必然改變），**不滿足**該條件。寫成「符合 Task 249」會把一條治理判準改寫掉，下一個任務會照著引用。

### D. 匯出（`backend/src/main/java/com/steven/assets/service/TradingRadarExportService.java`）

- [x] 280.15 新增巢狀取值 helper（不得改動既有四支 helper 的行為）：

  ```java
  /** 巢狀子節點的數值欄；父節點缺漏／為 null 時回 null（舊快照沒有 extendedIndicators）。 */
  private static BigDecimal num(JsonNode parent, String child, String field) {
      return num(parent.path(child), field);
  }
  ```

  `JsonNode.path()` 對 MissingNode／NullNode 都回 MissingNode，故舊快照自然得到 `null` → Excel 空白格、JSON `null`。**不得補 0、`"-"` 或空字串。**

- [x] 280.16 `marketSheet()`：headers 由 20 → **35**，順序**逐字**如下（`週線MA5` 插在 `MA20` 前、14 欄插在 `D` 後）：

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
  - `formats`：新增的 15 欄全部 `ExportDoc.Format.NUM2`；既有欄的 Format 一個都不改。**現行 formats 是單行的 `List.of(...)`（`:141`），本任務一併改成比照 `stockSheet()` 的逐行 ＋ index 註解寫法**——20 → 35 欄後單行已無法目視對位，而 Task 264 的插欄事故正是三份清單靜默不同步。

- [x] 280.17 `stockSheet()`：headers 由 35 → **50**，順序**逐字**如下：

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

- [x] 280.18 **「快照索引」分頁一欄都不動。**

### E. 測試（與實作同一支任務，不得延後）

- [x] 280.19 `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorSeriesAlignmentTest.java`（既有 Task 261／262 測試類，沿用其 `@ExtendWith(MockitoExtension.class)` ＋ `@Mock` ＋ AssertJ ＋繁體中文方法名）新增：
  - **`extended` 與同一份 `FullIndicators` 的 k／d 自洽**：對固定 fixture（≥ 250 筆、含 high/low 為 null 的列）呼叫 `computeFromSeries(...)`，斷言 `3×d − 2×k` 與 `extended().j9()` 差 ≤ `0.03`、`3×k − 2×d` 與 `k3d2()` 差 ≤ `0.03`、`100 − extended().rsv()` 與 `wr9()` **精確相等**。
    ⚠️ **容差不可寫 0.01**：`kdSeriesAsc` 以**未捨入**的 k／d 算 j9／k3d2、三者各自 `setScale(2, HALF_UP)`，上界為 `0.005 + 3×0.005 + 2×0.005 = 0.03`；寫 0.01 會產生**必然的**假失敗。
  - **`computeFromSeries().extended()` 與 `indicatorSeries()` 尾筆逐位相同（同一份輸入時）**：stub `historyRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(...)` 回一份 asc 序列、`end` 早於今日（`stockSeriesAsc` 於 `end.isBefore(today)` 時直接 return，不併 live），斷言 `indicatorSeries(...)` 尾筆的 14 個欄位 == `computeFromSeries(同一份 desc)` 的 `extended()` 對應欄位。證明沒有長出第二套公式。
  - **既有 8 個欄位零回歸**：同一 fixture 下 `computeFromSeries(...)` 的 `monthlyMa`／`quarterlyMa`／`annualMa`／`k`／`d`／`previousK`／`previousD`／`weeklyMa` 與擴充前的預期值逐位相同（預期值以現行程式碼先跑一次取得後寫死，作為迴歸錨點）。
  - **241 根視窗對 MACD／RSI 足夠收斂（本任務唯一真正有風險的數值判準，必測）**：同一份 ≥ 500 筆的 fixture，比較 `computeFromSeries(全部).extended()` 與 `computeFromSeries(前 241 筆).extended()` 的 `ema12`／`ema26`／`dif`／`macd`／`osc`／`rsi5`／`rsi10`，斷言差值 ≤ `0.01`。
    ⚠️ **不可改成斷言「完全相等」**（2 位小數捨入邊界上可能差 1 個末位）；也**不可只測 KD／BIAS／W%R／MA**——RSV／MA／BIAS 是固定視窗本來就恆等，K／D 雖是遞迴但 seed 衰減率 2/3、232 步後殘留 `≈1.4×10⁻⁴¹`（實測 double 層級即 bit-identical），這四類全部測不到真正的風險（MACD／RSI 的單向遞迴被截斷）。
  - **`0000` 台股大盤：core 與 extended 必須自洽（本任務最容易寫出無效測試的一條）**：stub `twseDailyRepo` 一組 ≥ 250 筆**含 high/low 且與 close 明顯不同**的指數日線，取 `ind = computeAll("0000","台股")` 後斷言：
    1. 8 個既有欄位與擴充前逐位相同（重構等價性）；
    2. **`|ind.extended().j9() − (3×ind.d() − 2×ind.k())| ≤ 0.03`** 且 **`|ind.extended().k3d2() − (3×ind.k() − 2×ind.d())| ≤ 0.03`**——`ind` 是**同一個** `FullIndicators`，其 `k`／`d` 來自 `taiexKd()`、`j9`／`k3d2` 來自映射後的 `kdSeriesAsc()`，這是唯一能證明「映射沒漏 `highPoint`／`lowPoint`、也沒餵錯清單」的斷言；
    3. 再 stub `priceQuery.getLive("0000","台股")` 回**今日**的 live 點位，重跑同一組斷言（證明映射吃的是併入 live 合成列**之後**的 `desc`，不是之前）。
    ⚠️ **不可只斷言 `rsv()` 非 null 與 `100 − rsv() == wr9()`**：前者在 `highest == lowest` 時 `kdSeriesAsc` 一律回 50，恆為非 null；後者是 280.3 的實作定義本身，是**恆等式、恆真**。兩條都偵測不到漏抄 high/low，也偵測不到餵錯清單。
    ⚠️ fixture 的 high/low **必須與 close 不同**，否則漏抄時 RSV 仍會因 fallback close 而算出相同結果。
  - **「擴充值一律不進評分」的機械釘子（決策釘子，非行為驗證）**：比照既有 `backend/src/test/java/com/steven/assets/service/WeeklyMaTest.java:45-53`（`weeklyMaMustNotBeAnInputToTheRuleEngine`，以反射掃 `StockInput.class.getRecordComponents()` 斷言不含 `ma5`／`weekly`），新增一條掃 `TradingRadarRuleEngine.StockInput` **與** `MarketInput` 的 record component 名，斷言**不含** `j9`／`k3d2`／`rsv`／`ema`／`dif`／`macd`／`osc`／`rsi`／`bias`／`b10b20`／`wr9`／`extended` 任一字樣（小寫比對）。失敗訊息須寫明「本任務明訂這些值純揭露；要接進評分請走 t276 的 SDD 循環，不得在此放寬斷言」。
    沒有這條就分不出「t276 有意接線」與「有人不小心接了」——280.14 目前只是一句禁令，全 spec 沒有任何機械檢查點。
  - **大盤 fail-soft**：stub `twseDailyRepo.findTopNByOrderByTradingDateDesc(anyInt())` 擲 `RuntimeException`，斷言 `computeAll("0000","台股")` **不擲例外**且回 `FullIndicators.EMPTY`。沒有這條，例外逸出會讓整張大盤卡變 `DATA_INCOMPLETE`、全部個股停發訊號。
  - **空／不足序列**：`computeFromSeries(List.of())` 回 `FullIndicators.EMPTY`（既有行為）；序列僅 3 筆時 `extended()` 的 14 個欄位全為 `null`、不擲例外。
  - Mockito 在本專案的 Java 版本下需要 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`（見驗證段），**不要用 `-DargLine`**（會覆蓋 pom 的時區設定導致大量測試 error）。

- [x] 280.20 **修補全部 8 個 `new FullIndicators(...)` 呼叫端**（不補就編譯失敗）：main 的 `TechnicalIndicatorService.java:57`／`:159`／`:496`、`StockAlertTriggerExportService.java:450`；test 的 `StockAlertGroupLabelTest.java:72`／`:89`、`TradingRadarMarketFreshnessTest.java:104`、`export/LiveAssetsDualFormatTest.java:313`。除 `:159`／`:496` 要填真值外，其餘一律填 `ExtendedIndicators.EMPTY`（**不是 `null`**——`EMPTY` 讓下游 `ind.extended().rsi5()` 不必判空）。
  掃描指令：`grep -ran "new TechnicalIndicatorService.FullIndicators(\|new FullIndicators(" backend/src`。
- [x] 280.21 `TradingRadarSnapshotStoreTest.java:73` 的 `new TradingRadarDto.MarketSummary(...)` 補最後一個引數（傳 `null`，該測試不驗指標）。掃描：`grep -ran "new TradingRadarDto\." backend/src`。

- [x] 280.22 `backend/src/test/java/com/steven/assets/service/export/TradingRadarDualFormatTest.java`：
  - **三組寫死的欄索引全部依 280.24 的映射改掉**（漏任一組都會驗到別的欄位，且對數值格呼叫 `getStringCellValue()` 會擲例外）：
    - `:154-160` 個股決策的 `getCell(33)` **共三處**（`:157`／`:159`／`:160`）→ `getCell(48)`；`:155` 的註解一併改寫，寫明本次插欄的位移規則。
    - `:145` 大盤總覽的 `getCell(18)`（「支持訊號」）→ `getCell(33)`。
    - `:174-175` 大盤總覽的 `getCell(14)` **共兩處**（「季線確認」）→ `getCell(15)`。
    - （`:128`／`:129` 的 `getCell(4)`／`getCell(5)` 與 `TradingRadarExportServiceTest` 全檔的 `getCell(0)` 都在插入點之前，不必動。）
  - 新增：**兩張分頁的表頭逐字等於 280.16／280.17 的清單**（`containsExactly(...)`，含順序），大盤 35 欄、個股 50 欄。
  - 新增：fixture `snapshotNode()`（`:198-231`）的 `market`／`stocks` 各補 `weeklyMa` 與 `extendedIndicators` 子物件，斷言 (a) Excel 對應格為 `CellType.NUMERIC` 且 `getCellStyle().getDataFormatString()` 等於 **`"#,##0.00"`**；(b) JSON 對應 key 為 JSON number 且值與 fixture 相同。
    ⚠️ **格式字串是 `#,##0.00`，不是 `0.00`**（`ExcelDocRenderer.java:236-237` 的 `num2.setDataFormat(fmt.getFormat("#,##0.00"))`，對應 Excel builtin `numFmtId=4`）。寫成 `"0.00"` 會 100% 紅，而失敗訊息最直覺的「修法」是去改 `ExcelDocRenderer` 的格式字串——**那會同時打壞全部 9 份 golden**。
    ⚠️ **不可拿「既有的 `MA20` 那格」當對照組**：`snapshotNode()` 的欄名**刻意與 DTO 不同名**——它寫 `m.put("ma20", …)`／`m.put("k", …)`／`m.put("latestPoint", …)`／`s1.put("code", …)`，而匯出端讀的是 `monthlyMa`／`kValue`／`price`／`stockCode`。故 golden 裡多數數值欄其實是 `num()` 回 `null` → **存在但無樣式的 BLANK 格**。要比對照組請用大盤 index 3「分數」（fixture `score` 同名有值）或個股新 index 43「季線乖離%」（fixture `ma60BiasPercent` 同名有值）。
  - 新增：**舊快照相容**——另一組完全不含 `weeklyMa` 與 `extendedIndicators` 的**獨立** fixture（不與 golden 比對），斷言匯出不擲例外、新增 15 欄在 Excel 為 `CellType.BLANK`、在 JSON 為 `null`。

- [x] 280.23 **golden 基準重產**：
  - 先把現行 `backend/src/test/resources/golden/radar.xlsx`／`radar_empty.xlsx` **複製**為 `radar_pre_t280.xlsx`／`radar_empty_pre_t280.xlsx`（兩個新名與原名都要存在，不是 `git mv`）。
  - **順序不可顛倒：先做 280.22 的 fixture 補欄，再重產 golden。** `snapshotNode()` 是兩條零回歸測試與其他斷言**共用**的同一份 fixture，補了 `weeklyMa`／`extendedIndicators` 之後，重產出的 `radar.xlsx` 新 15 欄會是 **NUMERIC 有值**（不是空白）；空白格的驗證由 280.22 最後那組獨立 fixture 負責。（t271 已踩過同型的坑：「golden fixture 必須與產生器逐字相同——交易雷達的測試一度多加了 `reasons`／`risks` 兩欄而與 golden 對不起來」。）
  - **重產方式（repo 內沒有 golden 產生器，必須自己來）**：暫時在 `TradingRadarDualFormatTest` 加一支拋棄式測試，**store stub 與 `snapshotNode()` 必須與該檔現行的完全同一份**，把 bytes 寫出後刪掉該測試：
    ```java
    Files.write(Path.of("src/test/resources/golden/radar.xlsx"),       service.exportForOwner(1L, 0L, 1L));
    Files.write(Path.of("src/test/resources/golden/radar_empty.xlsx"), service.exportForOwner(2L, 0L, 1L));
    ```
    （`mvn -f backend/pom.xml` 的 CWD 是 `backend/`，故路徑不含 `backend/` 前綴。`exportForOwner(2L, …)` 對應零快照那條測試既有的 stub。）
  - 重產後既有兩條零回歸測試改對新基準。

- [x] 280.24 **新增一條測試 `插欄前後既有欄逐格未變`**：對「大盤總覽」以 `old → old < 11 ? old : (old < 18 ? old + 1 : old + 15)`、對「個股決策」以 `old → old < 16 ? old : (old < 24 ? old + 1 : old + 15)` 的映射，逐格比對值、`CellType`、`dataFormat`、粗體、字級；「快照索引」以 identity 映射比對。
  **兩份 pre 基準都要比**：`radar_pre_t280` 比全表；`radar_empty_pre_t280` 比各分頁的表頭列——⚠️ **零快照時「快照索引」是 2 列不是 1 列**（`indexSheet()` 恆先寫一列 `ExportDoc.Line` 提示列，表頭在第 1 列），故該分頁比第 0–1 列，「大盤總覽」「個股決策」比第 0 列即可。
  ⚠️ **只重產 golden 不做這條比對是不夠的**——那樣「新增了欄」與「順手把既有欄改壞」在測試上完全無法分辨，而本次要動的正是三份平行清單的索引。

### 明確不做的事

- [x] 280.25 **不動前端**：`TradingRadarView.vue` 不新增欄位、不改展開列（使用者已明確選擇「只進匯出檔」）。雷達表已過寬，且這組值與雙擊開啟的走勢圖 popup 因價基不同必然對不上，同頁並列會造成誤解。
- [x] 280.26 **不動走勢圖與其 BFF**：`indicatorSeries()`／`StockAnalysisChartBffController`／`ChartSeriesAligner`／`StockAnalysisDialog.vue` 一律不改。
- [x] 280.27 **不動 `RadarInputAssembler`／`BacktestService`／`AlertChartRenderer`**，**不改 `computeAll()` 既有 8 個欄位的對外數值**，**不動 `RULE_VERSION`**。
- [x] 280.28 **不新增 `@Scheduled`**，故「公開資訊 → 排程列表」（`SchedulePublicBffController.JOBS`）不新增項目；既有「交易雷達匯出」那一筆的 `description` 只描述觸發時機／產出檔／Drive 同步，本任務只加欄，該段文字改動後仍為真，**不需改寫**。
- [x] 280.29 **不動 Liquibase**（零 DB schema 變更；擴充值不入庫，只進 Redis 快照與匯出檔）。
- [x] 280.30 **不把任何擴充值接進評分**——那是 t276 的範圍，且 t276 明訂「納入與否一律以 t273 回測框架的量測結果為準」。本任務只負責讓值存在並被匯出。

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

⚠️ **不要拿這些值去跟走勢圖 popup 或 Yahoo 比對**：雷達走還原權息價基，有配息的個股必然不同，那不是 bug。**恆等式自洽是本步唯一有效的判準**；逐位比對留給 280.19 的單元測試。

### 4. 匯出檔實測

上一步**通常**已寫入一筆快照。⚠️ **若下面取回的檔新增 15 欄全空，先確認不是節流**——`TradingRadarSnapshotStore.write()` 是「先節流、後去重」，節流間隔為 property `trading-radar.snapshot.min-interval-minutes`（`application.yml`，預設 **5** 分鐘；spec 他處稱之為 `SNAPSHOT_MIN_INTERVAL`，程式碼裡沒有這個識別字）；若使用者或 SSE 在 5 分鐘內已打過 `GET /api/trading-radar`，第 3 步那次**不會**落新快照，匯出到的全是部署前的舊快照。查法：

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

### 6. 不得回歸的既有頁面（因為 `computeFromSeries()` 的回傳內容變了）

`http://localhost/` → **觀察清單**確認 K／D／三條均線數值與改動前相同；**警示條件**頁的 MA% 觸發價換算正常。兩者都吃 `computeAll()`，是「既有 8 個欄位零回歸」的畫面判準。

### 7. 回測端點效能（280.4／280.7 的設計目標判準，必做）

`computeFromSeries` 的最大宗呼叫端是 `BacktestService`（每檔標的 × 每個交易日一次）。對**同一組固定標的與日期區間**，取改動前（`git stash` 或改動前的 image）與改動後的 wall time 對照：

```bash
docker exec asset-business-services sh -c 'time curl -s -X POST "http://localhost:8080/internal/backtest/rules" -H "Content-Type: application/json" -d "{\"codes\":[\"2330\",\"0050\",\"00878\"],\"from\":\"2024-01-01\",\"to\":\"2026-07-31\"}" -o /dev/null'
```

`BacktestDto.Request` 為 `(codes, from, to, horizons, thresholds, predicates)`，全部可省略（body 給 `{}` 代表**全部台股、全部期間**——那是分鐘級，**不要**拿來做前後對照，跑一次太久且雜訊大）。**固定用上面這組小子集比較**，倍率 > 1.2 即視為未達 280.4 的設計目標——回頭確認 `kdSeriesAsc` 真的只跑了一趟（3 趟降 1 趟應足以抵銷新增的 MACD／RSI／BIAS）。

該端點不經 BFF、不帶 `X-User-*`（`InternalBacktestController` 刻意不納入 `AdminGateInterceptor`，靠「business 的 8080 不對外映射」隔離），故容器內裸打即可。

### 8. 快照體積（Requirement 48 的硬約束是「不得擠掉即時股價快取」）

每筆 `StockDecision` 由 38 欄增為 39 欄、其中 `extendedIndicators` 展開為 14 個數值；保留設定為 90 天／per-owner 上限 5000 筆。部署前後各取一次對照：

```bash
docker exec asset-redis redis-cli --bigkeys
```

確認 `price:*` 未被大量快照擠出（gzip 對重複 key 壓縮率高，實際 byte 成長預期遠低於欄位數成長，但仍須實測）。

## 完成報告

### 實際改動

| 檔案 | 內容 |
|---|---|
| `backend/.../service/TechnicalIndicatorService.java` | 新增 public `ExtendedIndicators` record（14 值＋`EMPTY`）；`FullIndicators` 加第 9 個 component `extended`；`computeFromSeries` 改**單趟 `kdSeriesAsc`**（current 取尾筆、previous 取倒數第二筆）並呼叫新的 private `extendedOf(asc, kd)`；`computeAllForTaiex` 把已併 live 的 desc 經新的共用 `toRow()` 映射後算擴充值（留在既有 try 內）；`taiexSeriesAsc` 的 inline lambda 改呼叫 `toRow()`；**刪除死碼 `stockKd()`** |
| `backend/.../dto/TradingRadarDto.java` | 新增 `ExtendedIndicators` record；`MarketSummary`（20→21）與 `StockDecision`（38→39）各加最末欄 `extendedIndicators` |
| `backend/.../service/TradingRadarService.java` | 新增 private `toDto()`；`buildMarket`／`buildStock` 成功路徑填 `toDto(ind.extended())`，`incompleteMarket`／`incompleteStock` 填 `null`。**`RadarInputAssembler` 與 `prepareTechnicalData` 一行未改** |
| `backend/.../service/TradingRadarExportService.java` | 新增 `num(parent, child, field)` overload、`EXT_KEYS`／`EXT_HEADERS`／`extCells()`；大盤 20→35 欄、個股 35→50 欄，三份平行清單同步位移，`marketSheet` 的 formats 由單行改為逐行＋index 註解；`:171`／`:178` 的既有 index 註解同步更新 |
| `backend/.../service/StockAlertTriggerExportService.java` | `new FullIndicators(...)` 補第 9 個引數 `ExtendedIndicators.EMPTY` |
| `backend/src/test/.../TechnicalIndicatorSeriesAlignmentTest.java` | 新增 9 個 `@Test`（KD 自洽、與 `indicatorSeries` 尾筆逐位相同、單趟合併零回歸、241 視窗收斂、大盤自洽×2、fail-soft、不足序列、**反射釘子**） |
| `backend/src/test/.../export/TradingRadarDualFormatTest.java` | 三組寫死欄索引改掉（33→48 三處、18→33、14→15 兩處）；fixture 補 `weeklyMa`／`extendedIndicators`；新增 5 個 `@Test`（表頭逐字、新欄值與格式、舊快照相容、插欄前後逐格未變、**匯出欄名與 DTO 元件名綁定**） |
| `backend/src/test/.../{StockAlertGroupLabelTest,TradingRadarMarketFreshnessTest,TradingRadarSnapshotStoreTest,export/LiveAssetsDualFormatTest}.java` | positional constructor 補引數 |
| `backend/src/test/resources/golden/radar{,_empty}.xlsx` | 重產（新 15 欄為 NUMERIC 有值） |
| `backend/src/test/resources/golden/radar{,_empty}_pre_t280.xlsx` | 新增，改動前基準，供欄索引映射比對 |

### 驗證輸出

- `mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`：**Tests run: 520, Failures: 0, Errors: 0 — BUILD SUCCESS**（改動前 498，新增 22 條）。
- `spec-check.sh`：BLOCK 0 / CHECK 0。
- 對抗式 spec 審查 **5 輪**（2 critical／11 major／16 minor 全數處理；第 4 輪後因 main 推進與 t276 衝突而換設計，重審）。
- `arch-auditor`：critical 0／major 0／minor 1（javadoc 死鏈與孤兒區塊，已修）。另採納其兩項非 finding 建議：`git add` 兩份 pre golden、新增「匯出欄名 ↔ DTO 元件名」反射綁定測試。

### 與原計畫的偏差

1. **編號由 273 避讓為 280**：審查期間 main 被其他 worktree 推進，Task 273–279 已占用。
2. **設計整個換過（第 4 輪後）**：原設計新增 `latestFromSeries()` ＋ `computeTaiexWithExtended()`／`TaiexIndicators`。merge main 後發現 (a) Task 273 把 `prepareTechnicalData` 抽成 `RadarInputAssembler`，原設計依附的 `TechnicalData` record 與 `window` 變數已不存在；(b) main 上的 t276（未實作）的 276.1 已規劃「擴充 `FullIndicators` 的輸出」，原設計會變成兩份同義輸出。改為擴充 `FullIndicators`，反而更小——`RadarInputAssembler` 與 `buildMarket` 的 `computeAll` 呼叫都不必改。
3. **順帶合併 `kdSeriesAsc` 為單趟（原計畫沒有）**：第 4 輪審查指出 `computeFromSeries` 的最大宗呼叫端是 `BacktestService`（每檔每日一次，端點自述分鐘級），而原本已跑兩趟 KD、再加一趟會明顯變慢。因 `kdSeriesAsc` 是前綴相依前向遞迴，previous 取倒數第二筆與舊版 bit-identical，故合併為單趟：3 趟降 1 趟，抵銷新增的 MACD／RSI／BIAS。`stockKd()` 因此成為死碼並刪除。
4. **反射釘子的禁用字串改為精確欄名**：初版用 `bias` 當字根，誤中 Task 264 既有且合法的 `ma60BiasPercent`／`ma240BiasPercent`（那是「現價對季／年線的乖離」，與 BIAS10／BIAS20 是不同的東西），測試當場紅燈。改為 14 個精確欄名＋`extended`。
5. **新增一條原計畫沒有的測試**（arch-auditor 建議）：`EXT_KEYS` 與 `TradingRadarDto.ExtendedIndicators` 的 record 元件名之間原本沒有任何編譯期或測試期綁定——改了 DTO 欄名，匯出會靜默變成 14 個空白格而全測試皆綠。

### 尚未執行

- **部署與實機驗證**（驗證段第 2–8 步）：image rebuild ＋ container recreate、端點自測、匯出檔實測、舊快照相容、觀察清單零回歸、**回測端點 wall time 倍率**、**Redis `--bigkeys` 體積對照**——待使用者指示。依共用 stack 規則，merge 進 main 後應從 main 的 worktree 重建。
