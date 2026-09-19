# [t446] 交易雷達買進閘門「單日漲幅」與「中檔乖離」否決條件可校準化，並跑一次有界診斷

**對應 Requirements:** Requirement 161（交易雷達買進閘門「單日漲幅」與「中檔乖離」否決條件可校準化——讓維護者能用一次有界、成本後、walk-forward 的診斷實驗客觀檢視這兩個否決門檻是否偏保守，同時不改變任何使用者今天看到的正式判斷）
**前置任務:** 無
**Liquibase changeset:** 無（本任務不動任何資料庫欄位、表格或 DB migration）

## 背景

`TradingRadarRuleEngine.actionFor()`（`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java:3021`）決定每檔股票的交易雷達動作（買進候選／加碼候選／續抱／觀察／續抱但提高警戒…共 11 種）。一檔股票即使分數達到買進門檻（≥75），仍可能因為 `buyGate`（`TradingRadarRuleEngine.java:3062` 一帶）裡一連串 AND 條件中的任一個不成立而落回續抱／觀察。實際觀察到的案例：美股 AMD 分數達到 75 以上，但因「當日完成收盤漲幅 ≥5%」（`chasedDailyMove`）觸發否決，最終動作只是續抱／觀察，而非買進候選——這是刻意的保守設計（避免追高），但**目前完全無法用既有回測系統驗證這個 5% 門檻，以及另一個中檔乖離否決門檻（隱含在 `TimingState.OVERBOUGHT` 裡的 `BIAS_HIGH=12.0`），在統計上是否偏緊或恰當**。

`backend/src/main/java/com/steven/assets/service/BacktestService.java`（約 4931 行）已有成熟的 walk-forward／holdout／成本後回測系統（V13 report），重用正式的 `TradingRadarRuleEngine`，已能對 `RuleParameters.ActionThresholds`（買/續抱/警戒/減碼門檻，現行 75/55/40/25）做多候選比較，但**完全沒有把 `chasedDailyMove` 的 5% 門檻，或中檔乖離否決的 12.0 門檻，做成可校準維度**——它們在 `actionFor()` 內部是寫死的 `BigDecimal`／`double` 常數，任何候選都拿到一模一樣的值，無法比較。

`spec/requirements.md` 的 Requirement 117 acceptance criteria 已明文規定：「不以未驗證調參冒充獲利最佳化……日後如要調整任何權重或動作門檻，必先以既有 `BacktestService` 的成本後、時間分割 walk-forward／holdout 證據提出獨立 Requirement／Task 並通過規格審查，不能在本任務順手調參」。**本任務就是那個「先取得證據」的獨立任務本身**：範圍明確止於「讓這兩個否決門檻可測量＋跑一次有界（非全市場）診斷＋誠實記錄結果」，**不包含**「因為診斷結果看起來有利就順手調整 production 門檻」——即使診斷顯示某個方向數字好看，實際調整 `actionFor()` 的正式行為仍須另開 Requirement／Task 並通過規格審查，本任務完成後不得自行決定生效。

**明確排除：V13 逐股回測迴圈的平行化不在本任務範圍內。** 規劃階段曾評估把 `buildV13Report()` 的逐 code 迴圈做有界平行化（動機：過去唯一一次全市場 69 檔驗證嘗試，歷史任務 t316，曾在 10 分鐘執行視窗內逾時未完成），但對抗式審查過程中發現 `appendV13Code()` 的 per-day 迴圈裡藏有多個非顯而易見的資料庫／外部服務呼叫（`stockStyleIncomeThreshold()` 每日觸發 JPA 查詢；`resolveV13TreasuryRateObservation()` 對債券類標的幾乎每日觸發真實 SQL；`fxAt()` 對非本年度歷史交易日、任何外幣標的會觸發跨容器的阻塞式 HTTP 呼叫查詢台股假日行事曆——這最後一項極可能就是 t316 逾時的真正成因），要安全地把這段邏輯平行化，需要先對 `appendV13Code()` 的完整 I/O 依賴做一次獨立盤點，工程範圍明顯超出本任務。本任務的診斷跑批（12 檔小籃子）本身不需要平行化也能在安全時間內完成，故決定將平行化整塊移出本任務，改留待未來視需要另開專屬 Task 處理。

## 要做什麼

### RuleParameters 新增可校準欄位

- [ ] 446.1 於 `backend/src/main/java/com/steven/assets/service/RuleParameters.java` 現有 record（`bondRateCandidate` 是目前最後一個欄位）之後新增兩個欄位：`BigDecimal chasedDailyMoveThresholdPct`（語意：買進閘門否決用的「當日完成收盤漲幅」上限百分比；V12 正式預設必須是 `5`，與 `actionFor()` 目前硬編的 `BigDecimal.valueOf(5)` 逐位元相同）與 `BigDecimal buyGateOverboughtBiasPct`（語意：買進閘門否決用的「中檔乖離」上限百分比；V12 正式預設必須是 `12`，與現行 `BIAS_HIGH = 12.0` 常數逐位元相同語意）。compact constructor（現有 `public RuleParameters { ... }` 驗證區塊）對這兩個新欄位各加一個 `requireRange(value, BigDecimal.ZERO, BigDecimal.valueOf(100), "欄位名")` 檢查，比照現有 `downsideActionThresholdPct` 的檢查風格。
- [ ] 446.2 **不得破壞現有呼叫端。** 執行前先 `grep -rn "new RuleParameters(" backend/src` 確認現況（本任務撰寫時查證結果：僅 7 處，全部在 `RuleParameters.java` 本檔內；若實作時發現不只 7 處或不只在本檔內，必須逐一處理，不得假設）。這 7 處呼叫端目前的實際引數個數並不一致，逐一核對過的現況是：`v12Default()`（14 引數）與另外 4 個 `v13Candidate(...)`／`v13DisabledCandidate(...)` overload（各自也是 14 引數，含顯式傳入 `normalizedBiasEnabled` 的 `true`／`false`）**直接呼叫現行 14 引數的正式（canonical）建構子**；只有前 2 個 `v13Candidate(...)` overload 是 11 引數、經由既有的 11 引數相容建構子間接呼叫 14 引數版本；現行還有一個 13 引數的相容建構子，目前**零呼叫端**（死碼）。做法：把**現行的 14 引數建構子**改為一個內部呼叫新 16 引數建構子（多兩個新欄位）的相容建構子，相容建構子內部固定補上 `BigDecimal.valueOf(5)`／`BigDecimal.valueOf(12)`；新的 16 引數建構子才是攜帶驗證邏輯的正式建構子。這樣一來，凡是傳入 14 個引數的既有呼叫（含 `v12Default()` 與那 4 個 candidate 工廠）都會依 Java 多載解析規則自動改綁到這個新的 14 引數相容建構子，其餘 2 個經由 11 引數相容建構子間接呼叫的 overload 也不受影響——**全部 6 個非 `v12Default()` 的呼叫端不需要任何修改即可編譯並保持既有行為**。
- [ ] 446.3 `v12Default()` 例外：**不得**依賴上一條的相容建構子隱式補值，必須直接呼叫新的 16 引數建構子，並在呼叫中顯式寫出 `BigDecimal.valueOf(5)`／`BigDecimal.valueOf(12)`，以明確表達「這是刻意鎖定的正式預設值，不是相容性殘留」。
- [ ] 446.4 新增靜態工廠 `public static RuleParameters v13BuyGateCandidate(String parameterSetId, ActionThresholds shortThresholds, ActionThresholds mediumThresholds, BigDecimal chasedDailyMoveThresholdPct, BigDecimal buyGateOverboughtBiasPct)`：`ruleVersion` 用 `V13_VERSION`、`confidenceThreshold` 用 `0.70`、`normalizedBias` 全部欄位停用（floor/multiple/upperMultiple/lowerMultiple 皆 `null`，`normalizedBiasEnabled=false`）、`downsideActionThresholdPct` 用 `100`（等同停用）、`weakeningCondition` 用 `WeakeningCondition.conservativeV13()`、`candidateWeightDeltas` 用 `Map.of()`、`bondRateCandidate` 用 `BondRateCandidate.v12Fallback()`——即除了本任務新增的兩個欄位與 `shortThresholds`/`mediumThresholds`/`parameterSetId` 外，其餘全部固定在中性/停用值，確保候選網格比較時只有這兩個新維度在變動。
- [ ] 446.5 `distanceFrom(...)`（tie-break 比較函式，供 `TradingRadarCalibrationSelector` 使用）必須把這兩個新欄位的絕對差值加進既有的累加距離公式（比照現有 `downsideActionThresholdPct` 那一行的寫法：`.add(a.subtract(b).abs())`），否則兩個候選僅這兩個新維度不同時會被誤判距離為零。

### actionFor() 接入新參數，且不得讓 BIAS_HIGH 本身可校準

- [ ] 446.6 `TradingRadarRuleEngine.actionFor(...)`（現行簽章在 `TradingRadarRuleEngine.java:3021`，簽章為 `(StockInput input, int score, TimingState timing, boolean profitTaking, List<String> risks, List<String> reasons, RuleParameters.ActionThresholds thresholds)`）新增兩個參數 `BigDecimal chasedDailyMoveThresholdPct` 與 `BigDecimal buyGateOverboughtBiasPct`，成為簽章最後兩個參數。方法內現有 `chasedDailyMove` 布林運算式的 `BigDecimal.valueOf(5)` 改為傳入的 `chasedDailyMoveThresholdPct`。
- [ ] 446.7 **禁止**修改 `timingOf()`（有兩個 overload，計算 `TimingState.OVERBOUGHT` 時皆讀取 `TradingRadarRuleEngine.java:249` 的 `private static final double BIAS_HIGH = 12.0`，讀取點在 `TradingRadarRuleEngine.java:2810` 與 `:2848`）本身、禁止讓 `BIAS_HIGH` 常數變成可依候選調整。原因：`TimingState.OVERBOUGHT`／`EXTREME_OVERBOUGHT` 會被 `TradingRadarEvidenceConfidenceResolver.risk()`（`TradingRadarEvidenceConfidenceResolver.java:942`一帶的 `switch (in.timingState())`）讀取作為 V13 證據信心分數的 timing 分量輸入，也會被 `TradingRadarService.java` 讀取用於正式頁面「偏貴」風險文案——這兩個消費點是正式與回測共用的路徑，若讓 `BIAS_HIGH` 隨候選變動，會讓每一次候選評估時這兩處被本任務不打算承擔的方式連帶改變。
- [ ] 446.8 改為在 `actionFor()` 方法內部，於既有 `kdOverheated`／`premiumExpensive` 兩個布林值已算出之後（它們在現行程式碼中位於 `overbought` 計算之前），另外算一個**只供本次買進閘門否決使用**的新布林值，命名建議 `midTierOverboughtForBuyGate`：
  ```java
  boolean midTierOverboughtForBuyGate = kdOverheated
          || premiumExpensive
          || (input.ma60BiasPercent() != null
              && input.ma60BiasPercent().doubleValue() >= buyGateOverboughtBiasPct.doubleValue());
  boolean overbought = timing == TimingState.EXTREME_OVERBOUGHT
          || (timing != TimingState.EXTREME_OVERSOLD && midTierOverboughtForBuyGate);
  ```
  **`timing != TimingState.EXTREME_OVERSOLD` 這個排除條件是本條的正確性關鍵，絕對不可省略、不可寫成單純 `timing == TimingState.EXTREME_OVERBOUGHT || midTierOverboughtForBuyGate`。** 原因：`timingOf()`（兩個 overload，`TradingRadarRuleEngine.java:2795-2853` 一帶）是「先中先返回」的優先權階梯：`EXTREME_OVERBOUGHT → EXTREME_OVERSOLD → OVERBOUGHT → OVERSOLD → NEUTRAL`，任一狀態成立即短路、不再檢查後續狀態。若省略排除條件，考慮這種完全自洽的輸入：一檔股票因分位路徑觸發 `timing==EXTREME_OVERSOLD`（`ma60BiasPercentile() <= BIAS_EXTREME_PCT_LOW`，KD 短線超賣），但其原始 `ma60BiasPercent()` 恰好仍 `>= buyGateOverboughtBiasPct`（例如高動能股短線拉回：自身歷史乖離分位極低、但當前乖離絕對值仍不小，與本任務動機案例 AMD 同一類型）——現行系統因 `timing` 已被鎖定為 `EXTREME_OVERSOLD` 而 `overbought=false`；省略排除條件的錯誤版本會不理會這個優先權鎖定、僅因 `midTierOverboughtForBuyGate` 為真就令 `overbought=true`，在 `buyGateOverboughtBiasPct` 等於預設值 `12` 時把現行會放行的買進機會（若 `score>=75` 且其餘 `buyGate` 條件成立）錯誤否決為續抱／觀察，直接違反本任務「候選值等於預設值時與現行 `overbought` 完全等價」與 446.15「production 逐位元不變」兩項核心承諾。加上排除條件後可證明恆等：設 A＝`timing==EXTREME_OVERBOUGHT`、B＝`timing==EXTREME_OVERSOLD`、C＝`midTierOverboughtForBuyGate`，現行 `overbought`（即 `timing==OVERBOUGHT||timing==EXTREME_OVERBOUGHT`）展開為 `A||(¬A&&¬B&&C)`；本條公式 `A||(¬B&&C)`——A 為真時兩式皆真，A 為假時兩式皆化簡為 `¬B&&C`——兩式恆等。這個新 `overbought` 取代現行 `boolean overbought = timing == TimingState.OVERBOUGHT || timing == TimingState.EXTREME_OVERBOUGHT;` 這一行，作為後續 `buyGate` 布林運算式中 `!overbought` 的輸入。`kdOverheated`／`premiumExpensive` 在 `buyGate` 中本就已被獨立否決（`!kdOverheated`、`!premiumExpensive` 各自出現在 `buyGate` 運算式中），此處在 `midTierOverboughtForBuyGate` 裡重複納入不會增加額外否決力，只是為了讓這個新布林值的公式結構與 `timingOf()` 既有的 `OVERBOUGHT` 判斷式一致，方便日後審閱比對。
- [ ] 446.9 更新 `actionFor()` 唯一呼叫點（`TradingRadarRuleEngine.java:2129` 一帶，在既有 `RuleParameters.ActionThresholds thresholds = candidate == null ? V12_ACTION_THRESHOLDS : ...` 那段邏輯附近）：比照 `thresholds` 現有的 `candidate == null` 三元判斷樣式，新增
  ```java
  BigDecimal chasedDailyMoveThresholdPct = candidate == null
          ? BigDecimal.valueOf(5) : candidate.chasedDailyMoveThresholdPct();
  BigDecimal buyGateOverboughtBiasPct = candidate == null
          ? BigDecimal.valueOf(BIAS_HIGH) : candidate.buyGateOverboughtBiasPct();
  ```
  並將這兩個變數傳入 `actionFor(...)` 呼叫。執行前先 `grep -n "actionFor(" backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` 確認呼叫點數量（本任務撰寫時查證結果：僅一個呼叫點、一個方法宣告）；若不只一處，須逐一更新。**此處 `BigDecimal.valueOf(BIAS_HIGH)`（`BIAS_HIGH` 是 `double`，結果 scale=1，字串化為 `"12.0"`）與 446.3 `v12Default()` 裡的 `BigDecimal.valueOf(12)`（`int`，結果 scale=0，字串化為 `"12"`）scale 不同但數值相等；本任務所有下游用法（`.doubleValue()` 比較、`distanceFrom()` 的 `.subtract(...).abs()`）皆對 scale 不敏感，此處刻意引用具名常數 `BIAS_HIGH` 而非重複寫一次 magic number `12`，兩處寫法不需要、也不必統一。**

### BacktestService／BacktestDto 候選網格與快照回填

- [ ] 446.10 `Obs`（`BacktestService.java:294` 一帶的 private record，供 legacy 逐股描述性統計報表使用）新增欄位 `BigDecimal completedChangePercent`，插入位置建議在既有 `ma60BiasPercent` 欄位之後。唯一建構點（`grep -n "new Obs(" backend/src/main/java/com/steven/assets/service/BacktestService.java` 確認，本任務撰寫時查證僅一處，在 `runOne()` 內部）補上對應引數，值來自既有 `RadarInputAssembler.Assembled` 物件（該物件在建構 `Obs` 之前已存在於方法內，變數名可能是 `a`）的 `completedChangePercent()`（此 accessor 已存在，目前只是未被 `Obs` 讀取而已，不需新增）。
- [ ] 446.11 `basePredicates()`（`BacktestService.java:3864` 一帶，回傳 `Map<String, BiPredicate<...>>` 或類似型別的既有 predicate 註冊表）新增一筆：鍵為 `"CHASED_DAILY_MOVE"`，判定式為 `o.completedChangePercent() != null && o.completedChangePercent().compareTo(BigDecimal.valueOf(5)) >= 0`（固定用 5，代表現行 production 門檻，不是可調參數——這是描述性統計，不是候選比較）。
- [ ] 446.12 `sweepPredicate()`（`BacktestService.java:3931` 一帶）新增一個 `case "chasedDailyMove" ->` 分支，比照既有 `"kdOverheat"`／`"kdOversold"` 等 case 的寫法，回傳一個依傳入門檻值 `c`（既有 `sweepPredicate` 的門檻參數變數名）動態判定 `o.completedChangePercent() != null && o.completedChangePercent().compareTo(c) >= 0` 的 `NamedPredicate`，讓既有 `request.thresholds()` 契約可以掃描任意單日漲幅門檻。
- [ ] 446.13 `v13CandidateGrid()`（`BacktestService.java:2499` 一帶）新增三個候選，緊接在既有候選（例如 `V13_SHORT_SELECTIVE_MEDIUM_BASELINE`）之後，全部使用該方法既有的本地 `baseline`（即現行 `ActionThresholds(75,55,40,25)`）作為 `shortThresholds`／`mediumThresholds`，呼叫 446.4 新增的 `v13BuyGateCandidate(...)`：
  - `"V13_BUYGATE_CHASE8"`：`chasedDailyMoveThresholdPct=new BigDecimal("8.0")`、`buyGateOverboughtBiasPct=new BigDecimal("12.0")`
  - `"V13_BUYGATE_BIAS15"`：`chasedDailyMoveThresholdPct=new BigDecimal("5.0")`、`buyGateOverboughtBiasPct=new BigDecimal("15.0")`
  - `"V13_BUYGATE_CHASE8_BIAS15"`：`chasedDailyMoveThresholdPct=new BigDecimal("8.0")`、`buyGateOverboughtBiasPct=new BigDecimal("15.0")`

  這三個候選不受既有「僅在 sigma profile 可用時才加入」的條件限制，必須在每次呼叫 `v13CandidateGrid(...)` 時都無條件加入（即與現有無條件加入的固定候選同一批次），因為它們與 sigma/normalized-bias 維度正交、不依賴 sigma 是否已校準。

  **這會讓 `backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java` 兩處既有精確斷言變紅，必須同步更新，這是允許的既有測試更新，不是不該碰的既有行為：**
  - `:849-868` 逐一列舉全部候選 `parameterSetId` 的 `containsExactly(...)`（本任務撰寫時查證現況為 35 個字串）：須在既有 `"V13_SHORT_SELECTIVE_MEDIUM_BASELINE"` 之後插入 `"V13_BUYGATE_CHASE8"`、`"V13_BUYGATE_BIAS15"`、`"V13_BUYGATE_CHASE8_BIAS15"` 三個新字串。
  - `:923` 的 `candidateCalibration()).hasSize(34)`：改為 `hasSize(37)`。
  修改前務必先執行 `grep -n "containsExactly\|hasSize(34)" backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java` 確認實際行號與現況數字是否與本任務撰寫時查證的結果一致（可能因其他並行任務而有變動），若不一致以實際現況為準、按同樣邏輯（在正確位置插入三個新字串、候選總數加 3）調整。
- [ ] 446.14 `parameterSnapshot(...)`（`BacktestService.java:2661` 一帶）與 `BacktestDto.RuleParameterSnapshot`（`BacktestDto.java:272` 一帶 record；執行前 `grep -rn "new BacktestDto.RuleParameterSnapshot(" backend/src` 確認唯一建構點就是 `parameterSnapshot()` 本身，本任務撰寫時查證確實只有一處、且無任何測試直接建構此 record）在既有 `downsideActionThresholdPct` 欄位之後，各自新增 `chasedDailyMoveThresholdPct`／`buyGateOverboughtBiasPct` 兩個 `BigDecimal` 欄位並正確回填，維持這個 record「完整不可變參數回顯」的既有設計慣例（即：任何 `RuleParameters` 上的欄位都不得在這個 snapshot record 裡被省略）。

### 明確排除事項

- [ ] 446.15 本任務**不得**修改 `TradingRadarRuleEngine` 任何影響 production 唯一路徑（`candidate == null`）輸出的邏輯——production 在本任務完成前後，同一組輸入的 `score`／`action`／`shortAction`／`swingAction`／`timingState`／`reasons`／`risks` 必須逐位元相同，`RULE_VERSION`（現行 `TW_RULES_V20`）不升版，`ACTION_POLICY_VERSION`（現行 `EVIDENCE_GATE_V1`）不變。
- [ ] 446.16 本任務**不得**做任何形式的「promotion」。診斷跑批必為 `codes` 非空（依既有 `BacktestService.buildV13Report()` 邏輯，`request.codes()` 非空即觸發 `BacktestDto.UniverseMode.BOUNDED_DIAGNOSTIC`），此模式下 `productionPromoted` 結構上恆為 false、`selectedCandidates`／`selectedParameterSnapshots` 恆為空——這是既有設計，不是本任務要修改或繞過的限制。跑批得到的數值只能寫進本任務完成報告作為診斷證據，**不得**在本任務內據此調整 `actionFor()` 的任何門檻或權重；若診斷結果支持進一步調整正式行為，必須另開獨立 Requirement／Task 並通過規格審查，比照 `spec/requirements.md` Requirement 117 acceptance criteria 的既有規定。
- [ ] 446.17 不新增任何 REST endpoint、BFF route、前端頁面或按鈕、排程（`@Scheduled`）、DB migration 或 Liquibase changeset。不擴大 `/internal/backtest/*` 現有的存取邊界（現況：僅限容器內部呼叫可達，不對外映射 host port，不受 `AdminGateInterceptor` 管轄）。不修改 `BacktestService.buildV13Report()`／`appendV13Code()` 現有的迴圈結構或執行方式（不做平行化，見上方「明確排除：V13 逐股回測迴圈的平行化不在本任務範圍內」）。
- [ ] 446.18 不涉及、不新增、不包裝任何券商下單／改單／撤單／轉帳類 API；本任務全程只讀既有 `stock_price_history`／`stock_dividend_history` 等歷史資料表，不觸發任何外部行情或券商呼叫。

### 有界診斷跑批

- [ ] 446.19 執行下列唯讀 SQL（透過 `docker compose -p asset-management exec -T postgres` 或等價方式）確認代表性一籃子 12 檔的歷史涵蓋度：
  ```sql
  SELECT market, stock_code, min(trading_date), max(trading_date), count(*)
  FROM stock_price_history
  WHERE stock_code IN ('2330','0050','00878','2317','2454','2603','00679B','AMD','NVDA','TSLA','AAPL','MSFT')
  GROUP BY market, stock_code ORDER BY market, stock_code;
  ```
  固定基本籃子：台股 7 檔 `2330`／`0050`／`00878`／`2317`／`2454`／`2603`／`00679B`，美股 5 檔 `AMD`（必含，本 Requirement 的實際觀察案例）／`NVDA`／`TSLA`／`AAPL`／`MSFT`。任一檔查無資料或涵蓋年數明顯過短（例如少於 3 年），依序以候補替換：台股候補 `2412`／`1301`／`2882`，美股候補 `GOOGL`／`META`／`AVGO`；最終實際使用的 12 檔清單與替換原因（若有）記入完成報告。
- [ ] 446.20 **`horizons` 必須精確是 `[5,20,60,120]`，不得改成 `[5,20,60,240]`。** `[5,20,60,120]` 是 `TradingRadarV13PromotionRegistry` 現行 `Track.SHORT`（`List.of(5,20)`）／`Track.MEDIUM`（`List.of(60,120)`）兩軌 `requiredHorizons()` 的聯集，是這次 V13 診斷唯一該用的一組。**這與 `spec/requirements.md` 別處（Task 56 一帶）描述 legacy 四持有期框架時所用的 `+5/+20/+60/+240` 是兩組不同的數字，兩者字面上都可稱作「四個持有期」但數值不同**——`BacktestService.normalizeV13Horizons()` 只檢查範圍 `1..240`，`240` 本身不會被拒絕，若誤代入 `240`，程式不會報錯，只會安靜跑出一份 `Track.MEDIUM`（`60`／`120`）對應資料缺漏的診斷報表，直接損及本任務要交付的診斷證據的可信度。**由於本任務未做任何平行化，這次診斷跑批的執行時間預期遠低於 t316 那次 69 檔全市場逾時的規模（12 檔、遠小的樣本），若實測發現單次請求耗時異常長（例如超過數分鐘沒有回應），優先懷疑是 446 節提到的每日資料庫／外部服務呼叫（尤其 `00679B` 這檔債券 ETF、以及 5 檔美股外幣標的的歷史匯率行事曆查詢）疊加造成，如實記錄耗時，不需要為此另外做任何程式碼修改。** 重建並 recreate `business-services`（`docker compose -p asset-management build --no-cache business-services` → `up -d --no-deps --force-recreate business-services`），確認新程式碼生效後，對執行中容器送出：
  ```bash
  docker exec asset-business-services curl -s -X POST "http://localhost:8080/internal/backtest/rules" \
    -H 'Content-Type: application/json' \
    -d '{"codes":[<446.19 最終確認的 12 檔>],"markets":["台股","美股"],"horizons":[5,20,60,120],"calibrationRatio":0.70,"walkForwardFolds":5,"includeCloseFallbackSensitivity":false}' \
    -o /tmp/t446-diagnostic-report.json
  ```
  （若 `curl` 在該映像內不可用，改用 `wget -qO-` 搭配等價參數。）
- [ ] 446.21 **已知且屬正常現象、不得誤判為錯誤或程式缺陷**：`resolveV13Codes(...)` 現有實作會把整份 `codes` 清單套用到每一個 `markets` 值，因此回傳報表的 `failures` 欄位會合理出現「美股/2330:NO_PRICE_HISTORY」「台股/AMD:NO_PRICE_HISTORY」這類「某檔股票在它不屬於的市場查無資料」的項目——這是台股代碼被拿去查美股資料表（反之亦然）的預期結果，不需要修正、不代表本次跑批失敗。
- [ ] 446.22 讀取 446.20 取得的報表 JSON，針對每一個 `MarketHorizonExecution`（依 market × horizon 分組），比較 `candidateCalibration` 陣列中 `V13_BASELINE`（現行 baseline 候選）與三個新增候選（`V13_BUYGATE_CHASE8`／`V13_BUYGATE_BIAS15`／`V13_BUYGATE_CHASE8_BIAS15`）各自的 `pairedMedianDeltaPct`／`pooledMeanDeltaPct`／`downsideRatePct`／樣本覆蓋數；同時從同一次呼叫產生的 legacy `Response.results`／`pairedByCode`（描述性統計報表，與 V13 report 同一次 `evaluate()` 呼叫自動產生）讀取 446.11 新增的 `CHASED_DAILY_MOVE` predicate 統計（樣本數、平均／中位數遠期報酬、勝率、下檔比率）。

## 驗證

**單元／整合測試（必須全綠）：**

- [ ] `TradingRadarRuleEngineTest.java` 新增一個邊界回歸案例：在 `completedChangePercent=5`、`ma60BiasPercent=12`（即現行硬編門檻的邊界值）、其餘 `buyGate` 條件皆滿足、`score>=75` 的樣本上，斷言無候選路徑（`evaluateStock`，production 唯一路徑）的動作仍是 `HOLD`／`WATCH`（即仍被否決），證明本任務完全沒有動到 production 行為。
- [ ] `TradingRadarV13ActionPolicyTest.java` 新增四個案例：(1) 帶預設值（`chasedDailyMoveThresholdPct=5`、`buyGateOverboughtBiasPct=12`）的候選在上述邊界樣本上，動作與無候選路徑逐位元相同；(2) 帶 `chasedDailyMoveThresholdPct=8` 的候選在 `completedChangePercent=6`（其餘條件不變、`score>=75`）的樣本上允許 `BUY_CANDIDATE`／`ADD_CANDIDATE`（在 V12 預設 5% 門檻下這個樣本會被否決）；(3) 帶 `buyGateOverboughtBiasPct=15` 的候選在 `ma60BiasPercent=13`（其餘條件不變、`score>=75`）的樣本上允許買進，且同一樣本呼叫 `result.timingState()` 仍回報 `TimingState.OVERBOUGHT`——證明 446.7/446.8 的「不外溢」隔離設計成立；(4) **`EXTREME_OVERSOLD` 優先權排除的專屬回歸（446.8 的核心正確性條件）**：建構一個經分位路徑觸發 `timing==TimingState.EXTREME_OVERSOLD`（`ma60BiasPercentile() <= BIAS_EXTREME_PCT_LOW`）、但 `ma60BiasPercent()>=12`（等於預設 `buyGateOverboughtBiasPct`）的樣本，斷言帶預設值候選（`buyGateOverboughtBiasPct=12`）與無候選路徑，兩者在此樣本上算出的 `overbought` 皆為 `false`（若其餘 `buyGate` 條件與 `score>=75` 皆滿足，動作應仍可為 `BUY_CANDIDATE`／`ADD_CANDIDATE`，不得被 446.8 的新公式誤否決）。
- [ ] `TradingRadarCalibrationSelectorTest.java` 新增一個案例：兩個候選除了 `chasedDailyMoveThresholdPct`／`buyGateOverboughtBiasPct` 外其餘完全相同，斷言 `distanceFrom(baseline)` 為非零且排序正確。
- [ ] `BacktestServiceTest.java` 新增：(1) `v13CandidateGrid(...)` 回傳的候選集合包含 `V13_BUYGATE_CHASE8`／`V13_BUYGATE_BIAS15`／`V13_BUYGATE_CHASE8_BIAS15` 三個 `parameterSetId`；(2) 一段合成價格序列中已知某一日的完成收盤漲幅 ≥5%，斷言該日 `Obs.completedChangePercent()` 數值正確、且 `CHASED_DAILY_MOVE` predicate 只在該日命中、其餘日不命中；(3) `parameterSnapshot(...)` 對非預設的 `chasedDailyMoveThresholdPct`／`buyGateOverboughtBiasPct` 值能無損回顯進 `RuleParameterSnapshot`。**另外，446.13 已指出既有 `:849-868` 的 `containsExactly(...)` 與 `:923` 的 `hasSize(34)` 兩處斷言會因新增候選而變紅——這是預期中必須更新的既有斷言，看到這兩處失敗不代表本任務做錯，依 446.13 的方式更新即可。**
- [ ] `RuleParameters` 相關既有測試（若有獨立測試檔）與涉及 `v12Default()`／`v13Candidate(...)` 系列工廠方法的既有測試全數維持綠燈，不得修改既有斷言的期望值（除非該斷言本來就是在斷言欄位數量／建構子引數數量，此時允許同步更新引數數量但不得改變斷言的既有語意）。

**指令：**

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

**Docker 實機驗收（本專案沒有 dev server，改好的定義是 image rebuild + container recreate）：**

```bash
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
curl -s http://localhost:8080/actuator/health
```

而後依「要做什麼」446.19-446.22 執行實際診斷跑批，並把取得的關鍵數值與結論寫入下方完成報告——不得以本任務撰寫時的假設值或未執行過的推論替代實測結果。

本任務不動 `backend/src/main/resources/db/changelog/**`，不需要重產 `db/schema.sql`。

## 完成報告

**實際修改的檔案：**
- `backend/src/main/java/com/steven/assets/service/RuleParameters.java`（446.1-446.5：新增兩欄位、14→16 引數相容改造、`v12Default()` 顯式呼叫 16 引數建構子、新增 `v13BuyGateCandidate(...)` 工廠、`distanceFrom(...)` 納入兩個新維度）
- `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`（446.6-446.9：`actionFor(...)` 新增兩參數、`midTierOverboughtForBuyGate` 與 `EXTREME_OVERSOLD` 排除的 `overbought` 公式、呼叫點更新）
- `backend/src/main/java/com/steven/assets/service/BacktestService.java`（446.10-446.13：`Obs.completedChangePercent`、`CHASED_DAILY_MOVE` predicate、`sweepPredicate` 的 `"chasedDailyMove"` case、三個新候選、`parameterSnapshot(...)` 回填）
- `backend/src/main/java/com/steven/assets/dto/BacktestDto.java`（446.14：`RuleParameterSnapshot` 新增兩欄位）
- `backend/src/test/java/com/steven/assets/service/{TradingRadarRuleEngineTest,TradingRadarV13ActionPolicyTest,TradingRadarCalibrationSelectorTest,BacktestServiceTest}.java`（對應新測試，含 446.13 指定的兩處既有斷言更新）

**`mvn test` 結果（獨立重跑複驗，非僅信任實作者回報）：** `/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test`，退出碼 0；彙總 `backend/target/surefire-reports/*.txt` 得 **Tests run: 2247, Failures: 0, Errors: 0, Skipped: 0**。

**任務檔未明文提及、但實作時發現並一併修正的兩處衍生影響（皆已人工核對合理性）：**
1. `BacktestService.appendParameterSnapshotCsv` 的 CSV 匯出（含 header 字串與 `null` snapshot 的 `Collections.nCopies(36, null)` 補位）寫死既有欄位順序／數量，新增兩欄位後若不同步更新，會讓同一種列在 snapshot 為 null／非 null 時欄位數不一致（真實 bug）。已補上 2 個新欄位到 header 與 null 補位（`36`→`38`），並確認無測試對此列釘死欄位數。
2. `BacktestServiceTest.v13IntersectionUsesSingleSideActionAndHeldAvoidedLoss` 的 mock 讓除 `V13_BASELINE` 外的候選（含新增三個）績效完全同分，tie-break 改依 `distanceFrom(V12 baseline)` 決定；新候選只偏離兩個新維度、距離比原本贏家 `V13_SELECTIVE`（門檻＋權重皆偏離）更近，導致 tie-break 贏家改變。已將斷言由 `id.contains("SELECTIVE")` 放寬為 `id.contains("SELECTIVE") || id.contains("BUYGATE")`，測試真正驗證的「held REDUCE 在下跌樣本應呈現避免損失」（`selective.pooledMeanDeltaPct() > 0`，仍取用固定的 `SELECTIVE` 列單獨斷言）不受影響。

**Docker 實機驗證：** 從本 worktree（尚未 merge，程式碼變更只存在這裡）執行 `--no-cache` 重建並 `--force-recreate` business-services、`restart bff`；以容器內 jar 的 bytecode 反查（非僅信任 healthy 狀態）確認新符號（`chasedDailyMoveThresholdPct`／`buyGateOverboughtBiasPct`／`midTierOverboughtForBuyGate`／三個新候選 ID）確實存在於執行中容器的 jar。過程中發現本 worktree 的 `.env` 已過期（缺 TLS 變數、`FUBON_SYNC_OWNER_EMAIL`、多個 `FUBON_*_SYNC_ENABLED`、`API_ERROR_LOG_INGEST_TOKEN`，且 `GOOGLE_CLIENT_ID/SECRET` 與 main 不同），已依 main 版本補齊並確認兩份 `.env` 逐位元相同。另發現任務檔沿用的健康檢查端點 `/actuator/health` 在本服務不存在（compose 實際掛的是 `/api/snapshots`），已改用實際端點與 `docker inspect` 健康狀態驗證，兩者皆正常。

**446.19 最終使用的 12 檔清單與替換：** `2603`（長榮海運）在 `stock_price_history` 查無任何資料，依候補順序改用 `2412`（中華電信，2016-09-19～2026-09-16，2437 筆）。最終清單：台股 `2330、0050、00878、2317、2454、2412、00679B`，美股 `AMD、NVDA、TSLA、AAPL、MSFT`。

**446.22 診斷結果與誠實結論：** 診斷請求耗時約 17 分 49 秒（HTTP 200，非逾時未完成；本任務未做任何平行化，此耗時純屬既有 `appendV13Code` 逐股序列執行的觀察值，供未來參考）。`failures` 共 23 筆，全部是既有實作把整份 `codes` 套用到每個 `market` 所產生的跨市場查無資料項目（12 筆）與美股 GROWTH 風格族群樣本不足以建立 sigma profile 的既有情形（11 筆），皆屬 446.21 明文預期的正常現象。

**核心發現：全部 104 個 market×horizon×instrument 分組中，`V13_BASELINE` 與三個新候選（`V13_BUYGATE_CHASE8`／`V13_BUYGATE_BIAS15`／`V13_BUYGATE_CHASE8_BIAS15`）的 `pairedMedianDeltaPct`／`pooledMeanDeltaPct`／`downsideRatePct`／`intersectionN` 逐位元完全相同，無一例外**（已獨立以 Python 讀取原始 JSON 抽查多筆，數字與此結論一致）。追查機制原因：這個 12 檔、10 年歷史的有界籃子裡，整段期間 `score≥75` 且其餘 `buyGate` 條件同時成立的 BUY/ADD/TRIAL 進場事件總共只發生過 27 次（美股集中在 horizon=5/20，台股僅零星 1-3 筆於 3 個極小分組），沒有任何一次恰好落在「單日漲幅 5%~8%」或「中檔乖離 12%~15%」這個新候選才會產生差異的邊界區間內——不是機制沒接上（合成樣本的單元測試已證明機制本身正確），純粹是這個小籃子的真實歷史裡沒出現過這種邊界情境，樣本量不足以評估。

Legacy `CHASED_DAILY_MOVE` descriptive 統計（僅涵蓋 legacy report 既有處理的 7 檔台股，**不含 AMD 或任何美股**——見下方「與規劃的偏差」）顯示：單日漲幅≥5%後的 4 個 horizon（5/20/60/120）遠期報酬均值與中位數皆高於全樣本 baseline，短天期勝率也較高，但 downsideRisk 同時全面高於 baseline。這組數字的母體是「任何單日漲幅≥5%的交易日」，比實際否決情境（`score≥75` 且其餘 `buyGate` 條件同時成立）的母體寬鬆得多，且無成本調整、無 walk-forward 切分、程式本身明文不做顯著性檢定——**只能當背景參考，不構成「否決門檻偏保守」的證據**。

**誠實結論：本次有界診斷因樣本量不足，沒有產生任何可用來判斷 5%／12% 這兩個否決門檻是否偏保守的有效證據**——既不支持放寬，也不支持維持現狀不變，純粹是「證據不足以支持任何方向的調整」。這與 446.16 的既有立場一致：本任務範圍止於取得證據，不得依診斷結果順手調整正式門檻；若日後要以更大樣本重新驗證，需另開獨立 Requirement／Task。

**與本任務規劃的偏差：**
1. 上述兩處衍生修正（CSV 匯出欄位數、tie-break 測試斷言）不在任務檔原始清單內，但確認是新增欄位／候選的必然結果，已如實記錄理由，不影響任務核心設計。
2. 意外發現：legacy（非 V13）`Response.results`／`perCode` 統計路徑目前只處理送入的 12 檔中屬於**台股**的 7 檔（`codeCount:7`），請求中的 `markets:["台股","美股"]` 似乎只影響 V13 report 路徑、未讓 legacy 路徑一併涵蓋 5 檔美股——這代表 446.11／446.22 要求的 `CHASED_DAILY_MOVE` legacy 統計實際上**不含本 Requirement 的動機案例 AMD**。這是既有系統行為限制，本任務未修改任何程式碼去讓它涵蓋美股，僅如實記錄；是否需要另開任務調整，留待日後評估。
3. V13 逐股回測平行化整塊移出本任務範圍（見「背景」段落與 spec-review 過程），過程中意外發現的三個隱藏 I/O 依賴（`stockStyleIncomeThreshold()`、`resolveV13TreasuryRateObservation()`、`fxAt()` 的跨容器阻塞式 HTTP 呼叫）已另開一個獨立 follow-up 建議記錄，不在本任務完成範圍內。
