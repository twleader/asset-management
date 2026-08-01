# [t265] 還原序列補齊股票分割，並新增週線（MA5）

**對應 Requirements:** Requirement 43（今日交易雷達——MA／KD 與兩日確認的還原權息價基）
**前置任務:** 無
**後續任務:** t264（本任務是其前置——t264 新增的 52 週相對位置與 MA240 皆使用 240 根視窗，視窗內若有分割會靜默算錯）
**Liquibase changeset:** 無（不動 schema）

> **本任務取代 `spec/tasks/t225_split_adjusted_series.md`**（該檔從未實作，且其設計綁在 t223 的 750 根長窗需求上；本任務不擴大視窗，只修正既有 241 根視窗內的正確性）。

## 背景

### 使用者要求

「所有股票、ETF 的除權、除息要注意價格變化，在計算周線、月線、季線、年線時，要將它還原，才能正確。」

### 現況查證：一半已符合，一半是真缺陷

**已符合——除權息還原確實在做。** `TradingRadarService.prepareTechnicalData()`（`:350-354`）呼叫 `adjustedPriceService.adjust()`，其回傳的 `adjustedRows` 被用於：MA20／60／240 與 KD（`:362-364` `computeFromSeries`）、兩日確認（`:266-268` 的 `completedCloses`）、規則漲跌幅（`:262`）。且 `DistributionAdjustedPriceService`（`:112-113`）**同時還原 `highPrice` 與 `lowPrice`**，不只 `closePrice`，故 KD 的 RSV 分母亦為還原價基。前端於 `reasons` 揭露「MA／KD、兩日確認與規則漲跌已使用還原權息價」（`:290`）。

**缺陷——股票分割完全不還原。** `validEvent()`（`:84-88`）：

```java
private boolean validEvent(StockDividendHistory event) {
    return event != null
            && event.getExDividendDate() != null
            && (positive(event.getCashDividend()) || positive(event.getStockDividend()));
}
```

只接受現金股利與股票股利為正的事件；事件來源 `stock_dividend_history` 亦只有這兩個欄位，**沒有任何分割欄位**。分割會使價格在單日出現數倍跳空，但那不是趨勢——0050 於 **2025-06-18** 執行 1:4 分割，收盤由 `188.65` 變為 `47.57`。

**影響範圍與嚴重度：** 視窗內若有分割，MA20／60／240 全部混用分割前後兩種價基，均線會被前段的高價拉高，使「現價位於年線之下」恆成立；`confirm()` 的兩日確認同樣失真；t264 新增的 52 週相對位置會因 `high52` 取到分割前價格而恆為接近 `0`。**方向相反且不拋任何例外**（t223 已就 750 根視窗做過同樣的警告，實測 3 年年化報酬會由正確的 `+46.7%` 算成 `−7.6%`）。

**這不是「未來才會發生」的風險——現在就有一筆在視窗內。** t225 實測列出全庫**兩筆**真分割：`0050`（2025-06-18，`188.65 → 47.57`，−74.8%）與 **`2327`（2025-08-25，`546.00 → 143.00`，−73.8%，比例 ≈ 3.82）**。取數視窗為 `findRecentN(..., 241)`（`TradingRadarService.java:250`），241 個台股交易日回推約 356 天 ⇒ 視窗起點約 **2025-08-10**。故 `0050` 的分割已滑出視窗，**但 `2327` 的分割仍在視窗內**：該檔若在使用者的持股或觀察清單中，即為**現行 live 缺陷**，其 MA20／60／240 與兩日確認此刻就是錯的。

**更糟的是 `2327` 走不到還原邏輯**：t225 實測該檔在 `stock_dividend_history` **完全沒有紀錄**（該表只有 0050 的 21 筆與 006208 的 20 筆），而現行 `adjust()` 在無事件時直接原樣返回（見 265.1 的 early-return 分析）。即使實作了分割偵測，若沿用現行結構仍然偵測不到。

**週線（MA5）不存在：** `ma5`／`weeklyMa`／`週線`／`周線` 在 `TechnicalIndicatorService` 與 `TradingRadarRuleEngine` **零命中**。現有僅 `monthlyMa`(MA20)／`quarterlyMa`(MA60)／`annualMa`(MA240)。

## 要做什麼

### 265.1 分割偵測（序列啟發式，不新增資料來源）

台股沒有合規且可程式化存取的分割事件來源（MOPS `robots.txt` 為 `Disallow: /`），故採序列啟發式，於 `DistributionAdjustedPriceService` 內完成。

> ### ⚠ 現行 `adjust()` 的四條 early-return 會讓分割偵測完全不執行（承接 t225 Critical-1）
>
> `DistributionAdjustedPriceService.java` 現行結構：
> - `:29-34` `if (rawEvents == null || rawEvents.isEmpty()) return new Adjustment(List.copyOf(rowsDesc), false);` ——**無除權息事件即原樣返回**；
> - `:48-50` 事件經視窗過濾後為空，同樣原樣返回。
>
> 亦即：**逐列掃描根本不會執行**。而全庫兩筆真分割中的 `2327` 在 `stock_dividend_history` **完全沒有紀錄**（該表只有 0050 的 21 筆與 006208 的 20 筆），正好落在這條 early-return 上。
>
> - [x] **故：非正收盤排除與分割偵測必須置於全部 early-return（`:29`／`:32`／`:48`／`:70`）之前**，不得依賴「有除權息事件」作為進入掃描的前提。此為本任務最容易做錯、且做錯後對主要目標標的（2327）完全無效卻不報錯的一點。

> ### ⚠ 現行累積因子結構無法表達反向分割（承接 t225 Major-1）
>
> `:61` `if (factor.compareTo(BigDecimal.ONE) > 0)` ＋ `:77` `scale = sharesByRow.get(i).divide(finalShares, ...)`：`shares` 單調遞增故 `scale <= 1` **恆成立**，而反向分割需要 `scale > 1`。
>
> - [x] **故：`:61` 的守衛須改為 `!= 0`（或等效寫法），或把分割合成為「記憶體內的虛擬事件」插入事件序列後走同一套疊乘。** 二擇一即可，但必須明確選定並在實作註解記錄理由；沿用現行結構會使 265.1 定義的 `SPLIT_REVERSE_MAX = 0.5` 反向分割偵測**寫了也不會生效**。

- [x] 逐日檢查相鄰兩筆收盤：`ratio = prevClose / close`（`prevClose` 為時間上較早的一筆）。
  - `ratio >= SPLIT_FORWARD_MIN`（具名常數 `= 2.0`，即單日跌幅 ≥ 50%）→ 疑似正向分割；
  - `ratio <= SPLIT_REVERSE_MAX`（具名常數 `= 0.5`，即單日漲幅 ≥ 100%）→ 疑似反向分割。
- [x] **二次驗證**：比例須接近 `{2, 3, 4, 5, 10}`（或其倒數）之一、相對誤差 `≤ SPLIT_RATIO_TOLERANCE`（具名常數 `= 0.10`）。不通過則**維持原值並記 WARN**，不得擅自調整。
- [x] **門檻不得放寬到 ±15% 之類的小值。** t223 實測全台股序列中 ±15% 以上的跳空共 **21 筆**，其中僅 **2 筆**為真分割（`0050 −74.8%`、`2327 −73.8%`），其餘 19 筆幅度在 `−22% ~ +47%`（停牌復牌、資料源缺日等）。小門檻會產生大量誤報而把序列改壞——**改壞序列比不還原更糟**，因為前者無法從畫面察覺。
- [x] **排除 `closePrice <= 0` 的列**（t223 實測台股 176 筆），否則會除以零或使 52 週相對位置恆為 `+1`。
- [x] **已知的誤判風險必須揭露**：本啟發式無法區分「真分割」與「恰好接近整數倍的暴跌」。故偵測到分割時**必須寫 INFO log**（含代號、日期、比例），使異常可事後追查。

### 265.2 分割因子併入既有還原流程

- [x] 分割還原與現行的配息還原**共用同一個累積因子**，依日期升冪一併套用（**注意：現行 `adjust()` 只有配息路徑是此結構，分割須依 265.1 兩個 ⚠ 區塊先改結構才能併入**）。分割因子為 `1 / ratio`：**分割日之前的價格須乘上 `1/ratio`**，以對齊分割後的價基（0050 的 `ratio ≈ 3.966`，較早價格 ×0.252）。
- [x] **`highPrice`／`lowPrice`／`openPrice` 須與 `closePrice` 套用同一因子**（沿用 `:112-113` 既有作法），否則 KD 的 RSV 會混用價基。
- [x] `Adjustment.adjusted()` 旗標在僅因分割而調整時亦須為 `true`。
- [x] **前端揭露文案須涵蓋分割**：`TradingRadarService:290` 的既有文案「已使用還原權息價」改為能涵蓋分割的措辭（例：「已使用還原權息／分割價」），且僅在實際發生調整時顯示。

### 265.3 新增週線（MA5）

使用者明確列出「周線」。台股慣例週線 = MA5（5 個交易日）。

- [x] `TechnicalIndicatorService.FullIndicators` 增 `weeklyMa`（MA5），與既有三條均線走**同一計算路徑**；不足 5 根回 `null`。
  **⚠ 兩條路徑的價基本來就不同，不得宣稱 MA5「走還原序列」**：`computeFromSeries` 由呼叫端（交易雷達）餵入已還原的序列；而 `computeAll()`（`TechnicalIndicatorService.java:98-129`）自己走 `historyRepo.findRecentN(...)` ＋ Redis live 合成列，**全程未還原**——該檔 `:132-133` 的 javadoc 已明載「交易雷達會先還原權息再呼叫（`computeFromSeries`），避免在此服務重複資料存取或混用價基」。故 MA5 在雷達（還原）與觀察清單／警示／匯出（`computeAll`，未還原：`WatchStockService.java:207,325`、`StockAlertService.java:667,812,857,1341,1358,1415`、`ExcelExportService.java:789`、`TradingRadarService.java:194`——最後一支是大盤 `0000`，無除權息故實務影響為零，但 265.3 要求 `MarketSummary` 增 `weeklyMa` 走的正是這一支）會是兩個值。
  **這是既有 MA20／60／240 就存在的同一個限制，本任務不擴大也不修復**，但必須寫明；若要讓兩路徑一致須另立任務（涉及 `computeAll` 的全部消費端）。
- [x] `TradingRadarDto.StockDecision` 與 `MarketSummary` 增 `weeklyMa`，`TradingRadarView.vue` 展開列顯示。
- [x] **MA5 不納入評分因子，也不納入買進閘門。** 理由：使用者同時要求「獲利期間是數周至兩年，不是極短線」，而 MA5 是 5 個交易日的尺度；把它加進評分會與該需求直接衝突，也會與 t264 刻意降低短線權重的方向相反。**本任務只讓它可見、可查證，不讓它影響決策。** 若日後要讓它參與評分，須另走 SDD 循環並明確處理與持有期需求的矛盾。
- [x] 同理**不新增 MA5 的兩日確認**（`confirm(closes, 5)`）——那只會是評分因子的入口。
- [x] **走勢圖也要顯示週線**（使用者於 2026-08-01 追加）：`TechnicalIndicatorService.IndicatorPoint` 增 `ma5`、
  BFF 的 `IndicatorPointDto`／`ChartSeriesDto`（含 `Latest`）與 `ChartSeriesAligner` 同步增欄，
  `StockAnalysisDialog.vue` 於主圖新增「週線MA5」線（色 `#10b981`，與既有月／季／年線同一組 endLabel 慣例）
  並納入 legend。
  **`ma5` 在此路徑同樣是未還原值**——走勢圖刻意走原始價基（`design.md` 既有規範），與雷達的還原價基不同，
  此為既有 MA20／60／240 的同一限制，不因新增 MA5 而改變。
  **不新增指標選單項目**：週線屬主圖均線（與月／季／年線同列），不是子圖指標；`INDICATOR_OPTIONS`
  （`KD,J`／`MACD`／`RSI`／`乖離率`／`威廉指標`）維持不變。

## 驗證

- [x] **分割還原正確性**：以 0050 真實序列（含 2025-06-18 的 1:4 分割）為 fixture，驗證還原後跨分割日的相鄰收盤不再出現數倍跳空，且 MA240 不再被分割前價格拉高。
- [x] **誤報防護**：構造 `−22%`、`+47%`、`−30%` 等非整數倍跳空，驗證**不觸發**分割還原且序列維持原值。
- [x] **二次驗證邊界**：比例 `3.95`（誤差 1.25%，在容差內）觸發 4:1 還原；比例 `3.4`（誤差 15%，超出容差）不觸發並記 WARN。
- [x] **`closePrice <= 0` 列**：含 0 或負收盤的序列不得拋例外、不得產生分割誤判。
- [x] **無除權息事件但有分割（本任務最關鍵的一條）**：以 `2327`（2025-08-25，`546.00 → 143.00`）為 fixture，且 `stock_dividend_history` 對該檔**回傳空清單**，驗證分割仍被偵測並還原。**此案例會直接打到 `:29-34` 的 early-return**；若沿用現行結構，本條必然失敗——這正是它存在的目的。
- [x] **反向分割**：構造 `ratio = 0.25`（1 股併為 4 股的反向，價格漲 4 倍）的序列，驗證還原生效。此案例會打到 `:61` 的 `> ONE` 守衛。
- [x] **配息與分割並存**：同一序列內同時有除息事件與分割事件時，累積因子須依日期升冪正確疊乘（順序錯會使結果偏差但不報錯，須有專屬測試）。
- [x] **OHLC 一致性**：還原後任一列須維持 `low <= min(open, close) <= max(open, close) <= high`（以斷言釘住，可捕捉「只還原 close 沒還原 high/low」這類錯誤）。
- [x] **走勢圖**：主圖出現「週線MA5」線且 legend 可切換；`chart-series` 回應含 `ma5` 陣列與 `latest.ma5`；
  「當日」模式下 MA5 以 `latest.ma5` 畫成水平參考線（與既有三條均線同一機制）。
- [x] **MA5**：不足 5 根回 `null`；`computeFromSeries` 路徑有值時等於**傳入序列**最近 5 根收盤的算術平均（雷達餵的是還原序列、觀察清單餵的是原始序列，斷言須依路徑分別寫，不得跨路徑宣稱同值）；**斷言 MA5 不出現在 `StockInput` 的任何欄位**（釘住「不參與評分」這項決策）。
- [x] 既有 `DistributionAdjustedPriceService` 相關測試全數通過（純配息路徑行為不得改變）。

## 完成報告

**實際改了哪些檔**

| 檔案 | 改了什麼 |
|---|---|
| `backend/.../service/DistributionAdjustedPriceService.java` | 移除「無除權息事件即原樣返回」的 early-return；新增 `detectSplits()` 序列啟發式與 `canonicalSplitRatio()` 二次驗證；分割與配息合流為內部 `FactorEvent` 依日期升冪疊乘；`factor > 1` 守衛改為 `!= 1` 使反向分割可表達；新增 `hasStockDividendOn()` 排除大額股票股利造成的跳空 |
| `backend/.../service/TechnicalIndicatorService.java` | `FullIndicators` 增 `weeklyMa`；`IndicatorPoint` 增 `ma5`；`computeFromSeries`／`computeAllForTaiex`／`series` 三處計算 |
| `backend/.../dto/TradingRadarDto.java` | `StockDecision`／`MarketSummary` 增 `weeklyMa` |
| `backend/.../service/TradingRadarService.java` | 揭露文案改為「還原權息／分割價」 |
| `backend/.../service/StockAlertTriggerExportService.java` | `FullIndicators` 建構補 `weeklyMa=null`（觸發紀錄未留存 MA5） |
| `bff/.../stockanalysis/dto/IndicatorPointDto.java`／`ChartSeriesDto.java`／`ChartSeriesAligner.java` | 走勢圖序列與 `Latest` 增 `ma5` |
| `frontend/src/components/StockAnalysisDialog.vue` | 主圖新增「週線MA5」線（`#10b981`）、legend、當日模式水平參考線 |
| `frontend/src/views/TradingRadarView.vue` | 大盤卡與個股展開列顯示週線；還原標籤改為「還原權息／分割」 |
| `spec/design.md` | `/api/market-data/indicators/series` 契約補 `ma5`、指標欄位計數 19 → 20 |

**驗證輸出**

- `DistributionAdjustedPriceServiceTest`：12 個測試通過（原 3，新增 9）。涵蓋 2327 無配息路徑、反向分割、四組誤報防護、容差邊界、非正收盤、配息＋分割疊乘、OHLC 一致性、大額股票股利不重複計入、0050 年線不被拉高。
- `WeeklyMaTest`：3 個測試通過，含以反射斷言 `StockInput` 無 `ma5`／`weekly` 欄位（釘住「不參與評分」）。
- backend 全套 402 個測試通過；bff 18 個測試通過。

**與原計畫的偏差**

1. **分割比例採「標準值」而非觀察值。** spec 只寫「二次驗證比例須接近 `{2,3,4,5,10}`」，未明訂還原時用哪個。實作採標準值（0050 用 4 而非觀察到的 3.966），理由：觀察值含當日真實漲跌，用它還原會把真實價格變動一併抹掉。已於 `detectSplits()` javadoc 記錄。
2. **新增「大額股票股利不得同時被認定為分割」的防護**（spec 未要求）。配股 10 元＝1:1 會使價格腰斬、`ratio ≈ 2.0`，若不排除會被 `dividendFactor` 與 `detectSplits` 各計一次、`shares` 乘成 4 倍。目前 DB 無此樣本，屬預防性修補，已補測試。
3. **除息因子的分母語意有一處靜默變動，已明示決定**：舊實作在套用當下用「`tradingDate >= exDate` 的第一筆」收盤作分母，新實作預先計算、改用 `closeOn()` 取「`tradingDate <= exDate` 的最後一筆」。除息日本身有價格列時兩者相同（既有 fixture 皆屬此類），僅在**價格序列缺日**時方向相反。**採用新語意**（除息日無價格列時取除息前一日收盤），理由是預先計算讓分割與配息能合流為單一事件序列；此決定為本任務新增，尚無專屬測試釘住缺日情境。
4. **`AlertChartRenderer`（email 警示圖）刻意不加 MA5。** Requirement 的「上 pane 須比照畫面」在 Task 261 已有「僅限上 pane 與 KD 演算法」的分歧先例；信件圖幅小，四條均線會難以判讀。**此為刻意分歧，記錄於此。**
