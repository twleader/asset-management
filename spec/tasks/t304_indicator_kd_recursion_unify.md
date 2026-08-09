# [t304] 指數 KD 遞迴整併：taiexKd／nasdaqKd 刪除、統一走 kdSeriesAsc 單趟

**對應 Requirements:** Requirement 43 之 `TW_RULES_V12` 波修訂第 11 條（純重構、值 bit-identical、不升版）
**前置任務:** 無（不與 t297–t303 衝突；`TechnicalIndicatorService` 僅本任務觸碰）
**Liquibase changeset:** 無

## 背景

`TechnicalIndicatorService` 目前有**三份幾乎相同的 KD 遞迴**：

1. `kdSeriesAsc(List<StockPriceHistory>)` —— 序列核心，個股與擴充指標唯一來源（Task 261／281）；
2. `taiexKd(List<TwseIndexDailyHistory>)` —— TAIEX 專用，`computeAllForTaiex` 用它算當期 KD，且 previous KD **再跑一趟** `taiexKd(desc.subList(1, size))`（Task 281 已把個股路徑優化成單趟取尾兩筆，指數路徑沒跟上）；
3. `nasdaqKd(List<UsIndexDailyHistory>)` —— IXIC 專用（Task 294 複製了兩趟舊寫法）。

而 `computeAllForTaiex`／`computeAllForNasdaq` 的擴充指標段**已經**把同一份 desc 映射成 `StockPriceHistory`（`toRow`）餵 `kdSeriesAsc`——即兩個方法內同一序列的 KD 被算了三趟（current 一趟、previous 一趟、extended 內部一趟）。

**Bit-identical 論證**（`computeFromSeries` 註解已載，此處複寫）：`kdSeriesAsc` 是對 asc 序列的**前綴相依前向遞迴**——`out[i]` 只依賴 `asc[0..i]`（9 筆視窗＋自 index 0 累進的 k/d，seed 50/50）。舊版 previous 走 `taiexKd(desc.subList(1, n))`，其反轉後的 asc 正是完整 asc 的前綴 `asc[0..n-2]`，故尾筆恆等於完整序列的 `out[n-2]`。暖機守門亦等價：`kdSeriesAsc` 對 `i < 8` 填 EMPTY（k/d null），與 `taiexKd`／`nasdaqKd` 在 `size < 9` 回 `KdValues.EMPTY` 相同；唯一需要的是 `n >= 2` 的索引下界。`toRow` 映射把 `highPoint`／`lowPoint`／`closePoint` 對位到 `highPrice`／`lowPrice`／`closePrice`，null fallback close 的慣例兩邊同式。

## 要做什麼

- [x] 304.1 `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java` 的 `computeAllForTaiex()`：把 `ascRows` 的建構（`desc.reversed().stream().map(d -> toRow(d, "0000", "台股")).toList()`）移到 KD 計算之前；改為

  ```java
  List<KdPoint> kd = kdSeriesAsc(ascRows);
  int last = ascRows.size() - 1;
  KdPoint currentKd = kd.get(last);
  KdPoint previousKd = last >= 1 ? kd.get(last - 1) : KdPoint.EMPTY;
  ```

  `FullIndicators` 的 `k/d/previousK/previousD` 取自 `currentKd`／`previousKd`，`extendedOf(ascRows, kd)` 沿用同一份 `kd`（不再重算）。刪除 `taiexKd` 方法。`taiexSimpleMa` 與今日 live 合成列邏輯**不動**。
- [x] 304.2 `computeAllForNasdaq()` 同樣改法（`toRow(d, "IXIC", "美股")`），刪除 `nasdaqKd`。`nasdaqSimpleMa` 不動。
- [x] 304.3 `KdValues` record 若因此無其他呼叫端（`grep -n "KdValues" TechnicalIndicatorService.java` 確認），一併刪除；`computeAllForTaiex`／`computeAllForNasdaq` 上的「不呼叫 computeFromSeries（型別不同）」javadoc 段更新為描述新的單趟寫法與 bit-identical 論證出處。
- [x] 304.4 **不動**個股路徑（`computeFromSeries`／`computeAll`）、`indicatorSeries`、`kdSeriesAsc` 本體、`macdSeriesAsc`／`rsiSeriesAsc`／`biasRaw`。
- [x] 304.5 測試：**bit-identical 的機械驗證是以下兩個「用真實 service 打指數路徑」的既有測試，必須不改期望值而通過**——`TechnicalIndicatorNasdaqTest`（new 真 service、斷言 IXIC 的 `k/d/previousK/previousD` 值）與 `TechnicalIndicatorTaiexLiveBlendTest`（`computeAll("0000","台股")` 逐值 `assertEquals`）。（`TradingRadarMarketFreshnessTest`／`TradingRadarUsStockEngineTest` 把 `TechnicalIndicatorService` 整支 mock、`WeeklyMaTest` 只走個股路徑 `computeFromSeries`，三者不受本任務影響、也不構成驗證。）另新增一個直接對照測試：構造 ≥ 12 筆 `TwseIndexDailyHistory` fixture，斷言 `computeAllForTaiex()` 的 `k/d/previousK/previousD` 與「同 fixture 映射 `StockPriceHistory` 後 `computeFromSeries`」的對應值逐位相等（兩路徑同源的機械判準；IXIC 同法）。若既有測試無法直接呼叫 private 方法，以 `computeAll("0000","台股")` 層級斷言。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TechnicalIndicatorNasdaqTest,TechnicalIndicatorTaiexLiveBlendTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署驗證併入 t305 完成後的整波驗證（見 t305）。

## 完成報告

## t304 指數 KD 遞迴整併：完成報告

### 實際改動檔案

**1. `backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`**

- 刪除 `taiexKd(List<TwseIndexDailyHistory>)`、`nasdaqKd(List<UsIndexDailyHistory>)` 兩支方法，以及私有 record `KdValues`（`grep -n "KdValues" TechnicalIndicatorService.java` 確認刪除後僅剩一處歷史註解提及，見下方說明）。
- `computeAllForTaiex()`：把 `ascRows`（`desc.reversed().stream().map(d -> toRow(d,"0000","台股")).toList()`）的建構移到 KD 計算之前；改為單趟 `List<KdPoint> kd = kdSeriesAsc(ascRows)`，`currentKd = kd.get(last)`、`previousKd = last >= 1 ? kd.get(last - 1) : KdPoint.EMPTY`（`last = ascRows.size() - 1`），與任務檔給的虛擬碼逐字一致。`FullIndicators` 的 k/d/previousK/previousD 取自 `currentKd`／`previousKd`，`extendedOf(ascRows, kd)` 沿用同一份 `kd`，不再重算。`taiexSimpleMa` 與今日 live 合成列邏輯（`if (desc.isEmpty() || !today.equals(...))` 整段）完全不動。
- `computeAllForNasdaq()` 同法改寫，`nasdaqSimpleMa` 不動。
- 更新了兩處原本提及 `taiexKd`／`nasdaqKd` 的 javadoc：`taiexSeriesAsc()` 上方（原文字宣稱 taiexKd「不動」，因已被刪除而更新為指向 `kdSeriesAsc`）、`computeAllForNasdaq()` 上方原「不呼叫 computeFromSeries（型別不同）」整段（改為描述 KD 改走單趟 `kdSeriesAsc`、指向 bit-identical 論證出處）。
- **未觸碰**：個股路徑 `computeFromSeries`／`computeAll`、`indicatorSeries`、`kdSeriesAsc` 本體、`macdSeriesAsc`／`rsiSeriesAsc`／`biasRaw`——已用 `git diff` 逐一確認這些方法在 diff 中零改動。

**2. `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorSeriesAlignmentTest.java`**

- 僅改動 `assertTaiexExtendedConsistent` 上方一段 javadoc 註解：原文字面提及已刪除的 `taiexKd`，改寫為「Task 304 起兩者同源自同一份 kdSeriesAsc 單趟結果」。此檔案不在 304 的任務清單內，但該段落是純文字提及（非 `{@link}` tag，不影響編譯），若不修會留下指向不存在方法的失真文件；未變動任何測試邏輯或斷言，該檔案 21 個既有測試全數以原斷言值通過。

**3. `backend/src/test/java/com/steven/assets/service/TechnicalIndicatorIndexKdCrossCheckTest.java`（新增，對應 304.5 的「另新增一個直接對照測試」要求）**

- 兩個測試方法：`computeAllForTaiex的KD與同fixture映射後computeFromSeries逐位相等`、`computeAllForNasdaq的KD與同fixture映射後computeFromSeries逐位相等`。
- 各自建構 20 筆（滿足「≥ 12 筆」要求，取 20 是為了讓 current／previous 兩期都遠離 9 日暖機邊界，斷言更有意義）鋸齒收盤（避免 RSV 貼在 0/100）的 `TwseIndexDailyHistory`／`UsIndexDailyHistory` fixture，同步映射成欄位對應的 `StockPriceHistory`。
- 一邊透過 `computeAll("0000","台股")`／`computeAllForNasdaq()` 走 service 既有入口（`computeAllForTaiex()` 為 private，依任務指示的 fallback 改走 `computeAll` 層級斷言），一邊直接呼叫套件內可見的 `computeFromSeries()`；用 `assertEquals`（`BigDecimal.equals`，scale 也必須一致，符合「逐位相等」的字面要求）比對 `k`／`d`／`previousK`／`previousD`。
- TAIEX 版本把 fixture 最新一筆日期設為 `MarketZones.TW_ZONE` 的今日，讓 `computeAllForTaiex()` 的今日 live 併入分支不觸發，確保兩條路徑吃到完全相同的 20 筆輸入（IXIC 路徑本就不併入即時價，日期可任意固定）。

### 驗證輸出摘要

- 任務指定驗證指令 1（`-Dtest='TechnicalIndicatorNasdaqTest,TechnicalIndicatorTaiexLiveBlendTest'`）：BUILD SUCCESS，exit code 0。
- 任務指定驗證指令 2（整套 backend 測試，無 `-Dtest` 篩選）：BUILD SUCCESS，exit code 0。彙總 75 個 surefire 報告：**Tests run: 625, Failures: 0, Errors: 0, Skipped: 0**。
- 個別確認（surefire 報告逐檔核對）：`TechnicalIndicatorNasdaqTest` 5/5、`TechnicalIndicatorTaiexLiveBlendTest` 4/4（皆用真實 service 打指數路徑，期望值未改一字）、`TechnicalIndicatorSeriesAlignmentTest` 21/21（含大盤 core/extended 自洽、序列尾筆對齊、大盤取數失敗回 EMPTY 等既有測試）、新增 `TechnicalIndicatorIndexKdCrossCheckTest` 2/2。四者皆全綠，且都被整套測試涵蓋。
- 全 repo `grep -ran "taiexKd\|nasdaqKd"` 掃描：僅剩本次新寫的「已刪除」說明性註解，零實際呼叫端殘留；`grep -n "KdValues" TechnicalIndicatorService.java` 僅剩 `computeFromSeries` 內一段描述已於 Task 281 刪除的歷史方法 `stockKd` 的舊註解（該方法依規則 304.4 不得觸碰，予以保留）。

### 與原計畫的偏差

1. 額外修正 `TechnicalIndicatorSeriesAlignmentTest.java` 一處 javadoc（見上）。任務清單未列出，屬刪除 `taiexKd` 的直接副作用，為保文件正確性順手修正，零邏輯風險。
2. 新增對照測試的檔名（`TechnicalIndicatorIndexKdCrossCheckTest.java`）與 fixture 筆數（20，非任務指定的最低 12）由實作者決定，任務檔未指定具體值。
3. 其餘 304.1–304.5 逐項落實，無其他偏差。

### 更新過期望值的測試清單

無。本任務為 bit-identical 純重構（不升版），`TechnicalIndicatorNasdaqTest`／`TechnicalIndicatorTaiexLiveBlendTest`／`TechnicalIndicatorSeriesAlignmentTest` 既有全部斷言值皆未調整，原樣通過。
