# [t299] 極端時機門檻增加「季線乖離自身分位」替代路徑

**對應 Requirements:** Requirement 43（今日交易雷達）之 `TW_RULES_V12` 修訂第 5 條
**前置任務:** t298（`StockInput` 於本任務加欄位，且升版說明已含本項；實作順序須在 t298 之後、t305 之前）
**Liquibase changeset:** 無

## 背景

`TradingRadarRuleEngine.timingOf()` 的極端時機判定要求季線乖離**絕對值** ≥ ±20%（`BIAS_EXTREME_HIGH/LOW`）。對低波動標的（債券 ETF 如 00679B、大盤型 ETF 如 0050/006208），季線乖離 ±20% 幾乎終生不可達，導致 V11 兩大保護對它們**形同不存在**：

- `EXTREME_OVERBOUGHT` 是 `profitTakingConfirmed`（高檔多證據獲利了結 → REDUCE/AVOID）的前置條件——低波動標的永遠不會被建議高檔獲利了結；
- `EXTREME_OVERSOLD` 是「不得殺低」保護（把 REDUCE/EXIT 降級為 HOLD_CAUTION/WAIT）的前置條件——低波動標的崩跌時保護不啟動。

系統對 ETF 折溢價、PE、匯率已採「自身歷史分位」手法（絕對門檻管硬否決、分位管相對貴賤），本任務把同一模式套到極端乖離判定：**絕對 ±20% 或 自身分位 ≥98／≤2 二擇一**。中層 `OVERBOUGHT/OVERSOLD`（±12%）**刻意不分位化**——中層直接參與買進閘門否決（`overbought` 條件），分位化會讓低波動標的每年固定約 10% 的日子被擋買，與「讓保護可達」的目的相反。

超低波動標的不受本修訂影響：其 9 日帶寬 < 2% 時 `narrowKdBand` 已停用 KD → `kdHeatOf()` 回 NORMAL、`kdOversold()` 回 false，而極端判定的 KD 前置條件（overheated／oversold）不成立，分位路徑根本到不了。

分位門檻 `98`／`2` 與最少樣本 `120` **無回測量測依據，為判斷性取值**（240 樣本下 98 分位≈一年中最極端的前 5 個交易日）。

**與 Requirement 57／t274 的關係**：R57 已診斷同一缺陷（絕對門檻對低波動標的失效）並開出「季線乖離門檻一律改 σ 正規化、倍數由回測決定、不得憑直覺設定」的處方，至今未實作（引擎仍為固定 ±20／±12）。本任務是 R57 落地前的**過渡措施**：只動 EXTREME 側（讓保護可達）、明示判斷性取值；t274 實作 σ 正規化時，須明訂與本分位路徑的整併方式（擇一或疊加），不得兩份處方並立。

## 要做什麼

- [x] 299.1 `backend/src/main/java/com/steven/assets/service/RadarInputAssembler.java`：
  - `Assembled` record 新增欄位 `BigDecimal ma60BiasPercentile`（`EMPTY` 補 null）。
  - `assemble(...)` 計算：對**還原後序列的完成日子序列**（`adjustedRows` 自 `firstCompleted` 起，`firstCompleted = liveAdded ? 1 : 0`，與 `completedChangePercent` 同一起點）逐日計算「當日收盤對當日 MA60 的乖離（%）」——第 i 日（降序索引）的 MA60 = `adjustedRows[i..i+59]` 共 60 筆收盤均值，不足 60 筆的日子不產生觀測值。乖離式與既有 `biasPercent` 同式：`(close − ma) / ma × 100`，`ma` 非正時跳過該日。
  - 有效觀測 < `120` 筆 → `ma60BiasPercentile = null`。否則 `percentile = 100 × count(觀測乖離 ≤ 現行乖離) / 觀測數`，`setScale(1, HALF_UP)`；**現行乖離**＝同一 `assemble` 已算出的 `ma60BiasPercent`（live 價基），其為 null 時分位也為 null。
  - 常數 `BIAS_PCT_MIN_SAMPLES = 120` 具名並註明判斷性取值。計算 O(n) 即可（rolling sum），不得為此再查 DB。
  - **計算本體抽成 public 純方法**（比照同檔 `volumeRatio`／`bandWidthPercent`／`biasPercent` 的既有公開純方法慣例）：`public BigDecimal ma60BiasPercentile(List<StockPriceHistory> adjustedRowsDesc, int firstCompleted, BigDecimal currentBiasPercent)`，`assemble()` 呼叫它——使測試可直接呼叫純方法，不必為走 `assemble()` 而 stub `DistributionAdjustedPriceService.adjust`／`computeFromSeries`（`RadarInputAssemblerVolumeTest` 的 mock 未 stub 這些方法，走 `assemble()` 會 NPE）。
- [x] 299.2 `TradingRadarRuleEngine.StockInput` 新增欄位 `BigDecimal ma60BiasPercentile`（加在 `ma60BiasPercent` 之後、`ma240BiasPercent` 之前，javadoc 註明值域 0–100、樣本不足為 null）。兩個既有相容建構式（V10 完整技術面版、Task 291 前版）補傳 `null`。
- [x] 299.3 `TradingRadarRuleEngine`：
  - 新常數 `BIAS_EXTREME_PCT_HIGH = 98.0`、`BIAS_EXTREME_PCT_LOW = 2.0`（javadoc 註明無回測依據、判斷性取值；不得於文案宣稱能降低風險）。
  - `timingOf`：極端判定改為
    ```java
    Double pct = input.ma60BiasPercentile() == null ? null : input.ma60BiasPercentile().doubleValue();
    boolean extremeHigh = bias != null && (bias >= BIAS_EXTREME_HIGH || (pct != null && pct >= BIAS_EXTREME_PCT_HIGH));
    boolean extremeLow  = bias != null && (bias <= BIAS_EXTREME_LOW  || (pct != null && pct <= BIAS_EXTREME_PCT_LOW));
    if (overheated && extremeHigh) return TimingState.EXTREME_OVERBOUGHT;
    if (oversold && extremeLow)  return TimingState.EXTREME_OVERSOLD;
    ```
    其餘（OVERBOUGHT／OVERSOLD／NEUTRAL 判定、折溢價不入極端的既有註解）不變。**乖離本身（`bias`）為 null 時维持不判極端**（分位不可能有值，`extremeHigh/Low` 自然 false）。
  - 揭露文案：`evaluateHorizon` 內（`describeHeat` 之後）新增一段——當 timing 為 EXTREME_OVERBOUGHT 且**絕對門檻未達**（`bias < 20`）時 risks 加「季線乖離 X% 已位於自身近一年分布的第 N 百分位，極端超買以自身分布判定。 」；timing 為 EXTREME_OVERSOLD 且 `bias > −20` 時 reasons 加「季線乖離 X% 已位於自身近一年分布的第 N 百分位，極端超賣以自身分布判定。 」（X 一位小數用既有 `fmt1`、N 用 `Math.round`）。絕對門檻已達時不加此句（既有句子已足）。
- [x] 299.4 `TradingRadarService.buildStock`：`StockInput` 建構處在 `ma60Bias` 之後傳入 `technical.ma60BiasPercentile()`。
- [x] 299.5 `BacktestService`：唯一一處 `StockInput` 建構（`evaluate(...)` 內，約 337 行）同樣改傳 `a.ma60BiasPercentile()`——回測與 production 共用同一 assembler 輸出，**不得**在 BacktestService 另算一份。（約 620 行區塊建構的是 `MarketInput`（大盤 regime 回測），無個股乖離欄位，不在本任務範圍。）
- [x] 299.6 測試：
  - 加入既有 `RadarInputAssemblerVolumeTest`（assembler 既有測試檔），**直接呼叫 299.1 的 public 純方法 `ma60BiasPercentile(...)`**（不走 `assemble()`——該檔的 mock 未 stub `adjust`／`computeFromSeries`，走 `assemble()` 會 NPE）：(a) 構造 240 筆遞增收盤序列、`firstCompleted=0` → 現行乖離為正、分位接近 100；(b) 觀測數 < 120（序列僅 150 筆 → 觀測 91 筆）→ null；(c) `firstCompleted=1` 時首列（模擬 live）不進分布（與 `firstCompleted=0` 的分位可區分）。
  - `TradingRadarRuleEngineTest`：(d) `bias=+8%`（未達 20）＋分位 99＋KD 過熱 → EXTREME_OVERBOUGHT，且 risks 含「自身分布判定」句；(e) `bias=−8%`＋分位 1＋KD 超賣 → EXTREME_OVERSOLD，低分時動作被降級為 HOLD_CAUTION/WAIT；(f) 分位 null＋`bias=+8%` → 維持 OVERBOUGHT 不升級（回歸）；(g) `bias=+25%`＋分位 null＋KD 過熱 → EXTREME_OVERBOUGHT（絕對路徑回歸）。
  - `BacktestServiceTest`：既有測試維持通過（新欄位經 assembler 自動供給）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarRuleEngineTest,RadarInputAssembler*Test,BacktestServiceTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

部署驗證併入 t305 完成後的整波驗證（見 t305）。

## 完成報告

## t299：極端時機門檻增加「季線乖離自身分位」替代路徑

### 實際改動

**`RadarInputAssembler.java`**
- `Assembled` record 新增 `BigDecimal ma60BiasPercentile`，插在 `ma60BiasPercent` 之後、`ma240BiasPercent` 之前；`EMPTY` 常數補一個 null。
- 新增私有常數 `MA60_WINDOW = 60`、`BIAS_PCT_MIN_SAMPLES = 120`（javadoc 註明「判斷性取值、無回測依據」）。
- 新增 public 純方法 `ma60BiasPercentile(List<StockPriceHistory> adjustedRowsDesc, int firstCompleted, BigDecimal currentBiasPercent)`：對 `adjustedRowsDesc` 從 `firstCompleted` 起逐日以 rolling sum 回算 60 日均線與乖離（`(close-ma)/ma×100`，與既有 `biasPercent` 同式），觀測數 < 120 或現行乖離為 null 時回 null，否則回傳「觀測乖離 ≤ 現行乖離」的百分比（`setScale(1, HALF_UP)`）。全程 O(n)、不查 DB。
- `assemble()`：先算出 `ma60Bias`（原本內嵌算式抽成區域變數），再呼叫上述方法算 `ma60BiasPct`，一併塞入回傳的 `Assembled`。

**`TradingRadarRuleEngine.java`**
- `StockInput` record 新增 `BigDecimal ma60BiasPercentile`，位置同上；兩個既有相容建構式（23 參數的 V10 版、20 參數的 Task-291-前版）內部委派呼叫改為多傳一個 `null`，兩者對外簽章不變。
- 新增常數 `BIAS_EXTREME_PCT_HIGH = 98.0`、`BIAS_EXTREME_PCT_LOW = 2.0`，javadoc 註明無回測依據、判斷性取值、不得宣稱能降低風險。
- `timingOf`：改為算出 `extremeHigh`／`extremeLow`（絕對門檻或分位門檻二擇一），邏輯與任務檔給的虛擬碼逐字一致；`bias` 為 null 時 `extremeHigh/Low` 自然為 false。
- 新增私有方法 `describeBiasPercentileExtreme`，在 `evaluateHorizon` 內 `describeHeat` 呼叫之後接著呼叫：僅當「以分位路徑觸發極端、且絕對門檻未達」時才加一句揭露（超買加 risks、超賣加 reasons），絕對門檻已達時不重複加句。

**`TradingRadarService.java` / `BacktestService.java`**
- 各自唯一一處 `StockInput` 建構，均在 `ma60Bias` 之後插入一行分別傳 `technical.ma60BiasPercentile()` / `a.ma60BiasPercentile()`。`BacktestService` 約 620 行的 `MarketInput` 建構（大盤 regime 回測）依任務檔指示未動。

**測試**
- `RadarInputAssemblerVolumeTest.java`：新增 3 個測試直接呼叫 `ma60BiasPercentile(...)` 純方法（不走 `assemble()`，避免既有 mock 未 stub `adjust`／`computeFromSeries` 造成 NPE）：
  - (a) 240 筆 `close[i]=339−i` 線性遞增序列、`firstCompleted=0`，現行乖離傳入 30%（高於整個分布上界 22.78%）→ 分位 100.0。
  - (b) 同序列僅取 150 筆 → 觀測數 91（=150−60+1）< 120 → null。
  - (c) 同一 240 筆序列，現行乖離取在 i=0（9.53150242%）與 i=1（9.56239870%）觀測值之間的 9.55%：`firstCompleted=0` 分位 0.6，`firstCompleted=1` 分位 0.0，兩者不同，證明 live 首列確實被排除。
  - 註：任務檔原描述「遞增序列」隱含現行乖離即為當日（i=0）自算值、分位應「接近100」，但實際驗算發現：純線性遞增序列中，乖離會隨時間推進而**縮小**（絕對差距被越來越大的均線基期稀釋），i=0（最新）反而是分布中最小值而非最大值，若比照 (c) 的方式現行乖離自算，(a) 只能得到極低分位、與任務描述矛盾。改採「現行乖離獨立傳入一個高於整個分布上界的值」——這與生產路徑語意一致（`currentBiasPercent` 本就是獨立於歷史分布之外的 live 價基乖離），也確實達成任務要求的「分位接近100」（本例中為精確 100.0）。已用 Python 逐步重算過 rolling sum／兩階段 HALF_UP 捨入，確認與 Java 實作邏輯一致後才寫斷言，三個測試皆一次跑過。
- `TradingRadarRuleEngineTest.java`：
  - 修正 4 處既有 fixture（`biasIsolationStock`／`kdJIsolationStock`／`oscIsolationStock`／`withFundamental`）以配合 `StockInput` 全參數建構式新增一個欄位（在 `ma60BiasPercent` 後插一個 `null` 或 `base.ma60BiasPercentile()`）；其餘 15 處直接建構呼叫皆走兩個相容建構式（20／23 參數），未受影響、未動。
  - 新增 4 個測試對應任務檔 (d)–(g)：
    - `extremeOverbought_firesViaPercentilePathWhenAbsoluteThresholdNotReached`：bias=+8%＋分位99＋KD過熱 → EXTREME_OVERBOUGHT，risks 含「自身分布判定」。
    - `extremeOversold_firesViaPercentilePathAndDowngradesLowScoreAction`：bias=−8%＋分位1＋KD深度超賣（沿用 `v9CrashStock` 弱勢幾何使分數<40）→ EXTREME_OVERSOLD，動作降級為 HOLD_CAUTION/WAIT，reasons 含「自身分布判定」。
    - `extremeOverbought_doesNotUpgradeWhenPercentileMissing`：分位 null＋bias=+8% → 維持 OVERBOUGHT（回歸）。
    - `extremeOverbought_stillFiresViaAbsoluteThresholdWhenPercentileMissing`：bias=+25%＋分位 null → EXTREME_OVERBOUGHT（絕對路徑回歸）。
  - 新增 3 個 fixture 私有方法（`v9StockWithBiasPercentile`／`crashStockWithBiasPercentile`／`withBiasAndPercentile`），置於檔案末尾、`closesDescending` 之前，比照既有「Task NNN 測試用 fixture」段落慣例。

### 驗證輸出

指定測試：
```
TradingRadarRuleEngineTest: Tests run: 61, Failures: 0, Errors: 0
RadarInputAssemblerVolumeTest: Tests run: 5, Failures: 0, Errors: 0
BacktestServiceTest: Tests run: 15, Failures: 0, Errors: 0
```

整套 backend 測試（`mvn test`，全模組）：72 個測試類、**611 個測試，Failures: 0, Errors: 0, Skipped: 0**，Maven exit code 0。跑滿全套後另外用 `awk` 彙總 72 份 surefire 報告逐一核對，確認無任何 class 有非零失敗數（過程中肉眼看到 `TradingCalendarDualFormatTest`／`GdriveOutputSupportTest` 的 stack trace 屬於該二測試**刻意觸發並斷言的錯誤處理路徑**噪音記錄，非真正失敗，已用其 surefire 報告個別核實 0 failures/0 errors）。

### 與原計畫的偏差及原因
1. 299.6(a) 的資料構造方式與任務檔字面描述有出入，理由與具體處理見上方「測試」段落，已於測試程式碼內加註解說明推導過程，非隨意調整。
2. 除此之外，實作完全依任務檔逐條落實，`timingOf` 的判定邏輯與任務檔給的虛擬碼逐字一致，未更動任何既有商業邏輯（`kdJContribution`／`macdContribution`／`biasContribution`／`RULE_VERSION` 相關內容皆非本任務所改，是前置任務 t298 已完成的 V12 因子整併，工作區現況本就如此）。

### 更新過期望值的測試清單
無。本任務未修改任何既有測試的期望值／斷言；4 處既有 fixture 建構式呼叫的調整純屬配合新增欄位的機械性插入（傳 null 或轉呼原欄位存取器），不影響任何既有斷言的判定結果。

### 其他
`ma60BiasPercentile` 目前僅供引擎內部判定與文案使用，未串進 `TradingRadarDto`／`TradingRadarExportService`／前端——任務檔的「要做什麼」清單未列此項，故未擴大範圍；文案揭露（299.3）已能讓使用者在極端狀態成立時看到分位數字。若後續任務需要在 API／前端原始曝露此欄位，需另開任務處理。
