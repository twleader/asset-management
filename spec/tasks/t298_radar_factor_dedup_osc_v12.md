# [t298] 規則引擎因子同源修正（BIAS／W%R／OSC）與 `TW_RULES_V12` 升版

**對應 Requirements:** Requirement 43（今日交易雷達）之 `TW_RULES_V12` 修訂第 2–4 條與升版條
**前置任務:** t297（同波部署；程式上無相依，先後皆可，但升版由本任務執行）
**Liquibase changeset:** 無

## 背景

`TradingRadarRuleEngine`（V11）的三個因子有同源／抖動缺陷：

1. **BIAS 因子重複計分**：`biasContribution` 平均 `bias10`／`bias20`／`b10b20` 三分量，但 `b10b20 ≡ bias10 − bias20` 是代數相依值——引擎自己在 MACD 因子註解明文「只取 OSC，避免 DIF、MACD、OSC 的代數相依值重複灌權重」，BIAS 因子卻違反同一原則，實質權重偏向 bias10。
2. **W%R 與 KD 同源重複計分**：`TechnicalIndicatorService` 註解已自證 `W%R9 = 100 − RSV9` 為代數恆等式，而 K 是 RSV 的平滑。獨立的 `SW_WR`(0.04)／`MW_WR`(0.03) 權重＝同一個 9 日高低帶訊號計兩次分；窄幅 KD 帶防護把 KD/J 與 W%R 一起停用，正說明系統已認知同源。
3. **OSC 硬翻轉抖動**：`macdContribution` 回 `signum(osc)` ∈ {−1,0,+1}。OSC 在零軸附近逐日（盤中逐 tick）翻面時，中期分數擺動約 ±5 分（權重 0.05×貢獻差 2×50）、短期約 ±8 分，直接造成 75／55／40／25 動作門檻邊界抖動，並經 Requirement 44 的狀態轉入機制觸發重複通知（配套的通知冷卻見 t301）。

三項都改變因子組成 → 連同 t297／t299／t300 的輸入取值變更，`RULE_VERSION` 由 `TW_RULES_V11` 升為 `TW_RULES_V12`（整波單次升版，由本任務執行）。V12 與 V11 分數**不可直接比較**。

## 要做什麼

以下全部改 `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`（測試另列）。

- [x] 298.1 **BIAS 因子**：`biasContribution` 改為只平均兩分量——`bias10 == null ? null : clampUnit(-bias10/10.0)` 與 `bias20 == null ? null : clampUnit(-bias20/20.0)`；刪除 `b10b20` 分量。`b10b20` 在 `ExtendedIndicators`／DTO／畫面／匯出維持純揭露，不動。文案門檻（`value > 0.4` 加 reasons、`< -0.4` 加 risks）與句子不變。
- [x] 298.2 **W%R 併入 KD/J**：
  - `kdJContribution` 增加第四個可用值分量 `wrPosition`：`wr9 == null ? null : clampUnit((wr9.doubleValue() - 50.0) / 50.0)`（本系統 W%R 值域 0=高檔、100=低檔，故低檔為正貢獻，與被刪除的 `wrContribution` 同式）；回傳改為 `averageAvailable(direction, position, jPosition, wrPosition)`。
  - W%R 文案移入 `kdJContribution`：`wr9 >= 80` 加 reasons「威廉指標位於低檔，具跌深承接條件。 」、`wr9 <= 20` 加 risks「威廉指標位於高檔，避免追價。 」（沿用原句）。
  - 刪除 `wrContribution` 方法、刪除 `evaluateHorizon` 中的 `acc.add(shortTerm ? SW_WR : MW_WR, narrowBand ? null : wrContribution(...))` 一行、刪除常數 `SW_WR`／`MW_WR`，並自 `SHORT_WEIGHT_SUM`／`MEDIUM_WEIGHT_SUM` 的算式移除。
  - 權重合併：`SW_KD_J` `0.10` → `0.14`、`MW_KD_J` `0.04` → `0.07`。
  - 窄幅防護語意不變：`narrowBand` 時整個 KD/J 因子（含新併入的 W%R 分量）為 null，風險文案「近 9 個交易日高低帶過窄，KD／J／W%R 在雜訊上飽和，本日不採計。 」沿用。
- [x] 298.3 **V12 完整權重表（18 因子；本表為唯一契約，全部須為具名常數，兩軌總和 `1.00` 由既有 `SHORT_WEIGHT_SUM`／`MEDIUM_WEIGHT_SUM` 測試斷言釘住）**：

  | 因子 | 短期 | 中期 | 與 V11 差異 |
  |---|---|---|---|
  | MA5 位置 | 0.05 | 0.02 | 不變 |
  | MA20 位置＋確認 | 0.06 | 0.05 | 不變 |
  | MA60 位置＋確認 | 0.04 | 0.08 | 不變 |
  | MA240 位置＋確認 | 0.02 | 0.07 | 不變 |
  | KD/J（含 W%R 分量） | **0.14** | **0.07** | 併入 W%R 權重 |
  | MACD（OSC 幅度） | 0.08 | 0.05 | 正規化方式改（298.4） |
  | RSI | 0.06 | 0.04 | 不變 |
  | ~~W%R~~ | — | — | **刪除（併入 KD/J）** |
  | BIAS（兩分量） | 0.07 | 0.08 | 分量組成改（298.1） |
  | 量能 | 0.08 | 0.05 | 不變 |
  | 大盤 regime | 0.09 | 0.06 | 不變 |
  | 完成日漲跌 | 0.04 | 0.02 | 不變 |
  | 匯率分位 | 0.04 | 0.03 | 不變 |
  | ETF 折溢價分位 | 0.03 | 0.02 | 不變 |
  | EPS 年增 | 0.03 | 0.07 | 不變 |
  | 近似 ROE | 0.03 | 0.07 | 不變 |
  | 近三月營收年增 | 0.04 | 0.05 | 不變 |
  | PE 自身分位 | 0.02 | 0.05 | 不變 |
  | 產業營收年增 | 0.08 | 0.12 | 不變 |

- [x] 298.4 **OSC 幅度正規化**：`macdContribution(ExtendedIndicators indicators, List<String> reasons, List<String> risks)` 增加 `BigDecimal price` 參數（呼叫端傳 `input.price()`）。實作：`osc == null` 回 null；`price == null || price.signum() <= 0` 回 null（防禦；`complete()` 已保證 price 非 null）；`double oscPct = osc.doubleValue() / price.doubleValue() * 100.0;`；貢獻 `clampUnit(oscPct / 0.5)`（全幅 `0.5%`：|OSC| ≥ 現價 0.5% 才飽和 ±1）。文案：`oscPct >= 0.1` 加 reasons「MACD 柱狀體 OSC 為正，動能偏多。 」、`oscPct <= -0.1` 加 risks「MACD 柱狀體 OSC 為負，動能偏弱。 」（句子沿用，觸發門檻由「非零」改為 ±0.1%——避免噪音級 OSC 也輸出方向文案）。常數 `0.5`／`0.1` 取具名常數（如 `OSC_FULL_SCALE_PCT`／`OSC_NARRATIVE_PCT`），javadoc 註明**無回測依據、判斷性取值**（比照 `KD_OVERHEAT_K` 的既有揭露寫法），不得於任何文案宣稱能降低風險。
- [x] 298.5 **升版**：`RULE_VERSION` → `"TW_RULES_V12"`；**`RULE_VERSION` 常數的 javadoc**（現行 V11 說明段）改述 V12：雙軌權重、W%R 併入 KD/J、BIAS 兩分量、OSC 幅度正規化、極端時機自身分位（t299）。同步點（`grep -ran "TW_RULES_V11"` 全數處理，實測 5 檔（7 處））：
  - `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`（常數＋其 javadoc）
  - `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` 第 9 行 javadoc
  - `frontend/src/views/TradingRadarView.vue`：顯示 fallback（`radar.ruleVersion || 'TW_RULES_V11'`）與 `radar` ref 初始值兩處
  - `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java`：版本斷言（含測試方法名 `ruleVersion_isV11` → `ruleVersion_isV12`）
  - `backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java`：第 389 行 `@DisplayName("Task 292 回測與 production 共用 TW_RULES_V11")`、第 390 行方法名 `ruleVersionIsV11` → `ruleVersionIsV12`（方法名不含版本字串，grep 抓不到，勿漏）、第 391 行 `assertThat(...).isEqualTo("TW_RULES_V11")` 一併改 V12
  - **絕不可改**：`db/changelog/**` 任何 `--comment`（Liquibase checksum 含註解，改了 ValidationFailed → crash loop）；權重表歷史註解（spec 歸檔）不動。
  - 通知基準重建**無需新增程式**：`TradingRadarNotificationService.evaluateSettingSafely` 既有 `baselineValid = initialized && RULE_VERSION.equals(setting.getRuleVersion())` 判斷會使升版後首輪只建基準不寄信。
- [x] 298.6 **測試**（`TradingRadarRuleEngineTest`，必要時同檔新增方法）：
  - (a) 既有 `SHORT_WEIGHT_SUM`／`MEDIUM_WEIGHT_SUM` == 1.0 斷言維持通過（權重表 298.3 的機械檢查）。
  - (b) BIAS：給定 `bias10=+5, bias20=+5, b10b20` 任意值 → 貢獻等於只平均前兩者（−0.5 與 −0.25 的均值 −0.375），`b10b20` 不影響結果。
  - (c) KD/J：`wr9=90`（低檔）在其餘分量固定下把貢獻往正向拉、`wr9=10` 往負向拉；`wr9=null` 時貢獻等於三分量版本；窄幅（`kdBandWidthPercent < 2`）時整因子 null。
  - (d) OSC：`osc=+0.01, price=100`（0.01%）→ 貢獻 0.02（趨近 0，非 +1）；`osc=+1, price=100`（1%）→ 貢獻 +1（飽和）；`osc=−0.6, price=100` → −1；`osc=+0.05, price=100`（0.05% < 0.1%）→ 不輸出動能文案。
  - (e) 版本斷言改 `TW_RULES_V12`。
  - (f) 既有其他測試因分數變動而失敗者，逐一依 V12 語意更新期望值（不得為過測試而回改引擎；完成報告列出每個被更新的測試與理由）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest='TradingRadarRuleEngineTest' -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
grep -ran "TW_RULES_V11" backend/src bff/src frontend/src | grep -v changelog ; echo "（上行應無輸出）"
```

部署驗證併入 t305 完成後的整波驗證（見 t305）。

## 完成報告

## t298 完成報告

### 實際改了哪些檔案

**`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`**（核心邏輯，5 項）
1. **298.1 BIAS 因子**：`biasContribution` 改為只平均 `bias10`／`bias20` 兩分量，刪除 `b10b20` 分量與其除以 8 的正規化式；`b10b20` 在 `ExtendedIndicators` 仍原樣保留（純揭露，DTO／畫面／匯出不受影響）。
2. **298.2 W%R 併入 KD/J**：`kdJContribution` 新增第四個可用值分量 `wrPosition = clampUnit((wr9-50)/50)`，回傳改 `averageAvailable(direction, position, jPosition, wrPosition)`；W%R 兩則文案（低檔跌深承接／高檔避免追價）搬進 `kdJContribution`。刪除 `wrContribution` 方法、刪除 `evaluateHorizon` 中掛 `SW_WR`／`MW_WR` 的那一行、刪除 `SW_WR`／`MW_WR` 常數並自 `SHORT_WEIGHT_SUM`／`MEDIUM_WEIGHT_SUM` 算式移除。`SW_KD_J` 0.10→0.14、`MW_KD_J` 0.04→0.07。窄幅防護文案與語意原樣沿用。
3. **298.3 權重表**：逐項核對 18 因子權重與任務表格完全相符；`SHORT_WEIGHT_SUM`／`MEDIUM_WEIGHT_SUM` 手算與 `weightsSumToExactlyOne` 測試皆確認仍為 1.00。
4. **298.4 OSC 幅度正規化**：`macdContribution` 新增 `BigDecimal price` 參數（呼叫端 `evaluateHorizon` 已有 `input.price()` 可傳，未新增欄位）；`price==null||price.signum()<=0` 防禦性回 null；`oscPct = osc/price*100`；貢獻 `clampUnit(oscPct/OSC_FULL_SCALE_PCT)`（`OSC_FULL_SCALE_PCT=0.5`）；文案觸發門檻改為 `oscPct` 絕對值 ≥ `OSC_NARRATIVE_PCT`（0.1）。兩個新具名常數均加了「無回測依據、判斷性取值、不得宣稱降低風險」的 javadoc，比照 `KD_OVERHEAT_K` 既有寫法。
5. **298.5 升版**：`RULE_VERSION` → `"TW_RULES_V12"`，javadoc 改述 V12 內容（雙軌權重延續、W%R 併入 KD/J、BIAS 兩分量、OSC 幅度正規化、極端時機自身分位提及 Task 299）。

**同步點（`grep -ran "TW_RULES_V11"` 原 5 檔 7 處，全數處理，另補 2 處 grep 抓不到的方法名）**
- `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` 第 9 行 javadoc → V12。
- `frontend/src/views/TradingRadarView.vue`：顯示 fallback（28 行）與 `radar` ref 初始值（794 行）→ V12。
- `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java`：版本斷言＋方法名 `ruleVersion_isV11`→`ruleVersion_isV12`。
- `backend/src/test/java/com/steven/assets/service/BacktestServiceTest.java`：`@DisplayName`、方法名 `ruleVersionIsV11`→`ruleVersionIsV12`、斷言值，三處皆改。
- 收尾用 `grep -rn "TW_RULES_V11"` 掃過整個 repo（含 backend/frontend/bff，排除 target／node_modules／dist）確認零殘留。
- `db/changelog/**` 未動任何內容或註解；`TradingRadarNotificationService.evaluateSettingSafely` 既有版本比對邏輯不需改碼（如任務所述）。

**298.6 測試**：
- (a) `weightsSumToExactlyOne` 維持通過，另在其 javadoc 註記對應 298.3。
- (b)(c)(d) 在 `TradingRadarRuleEngineTest.java` 新增 5 個測試方法＋3 個共用 fixture helper（`biasIsolationStock`／`kdJIsolationStock`／`oscIsolationStock`）：這組 fixture 把除受測因子外的所有計分項中性化為貢獻恰 0（但仍佔權重分母，中期 Σw=0.52 固定），讓分數差異可被精確手算並釘住：
  - `biasContribution_averagesOnlyBias10AndBias20_ignoringB10b20`：驗證 bias10=+5/bias20=+5 之貢獻等於兩分量均值 −0.375（分數 47），且 b10b20 換成天差地遠的值分數不變。
  - `kdJContribution_wr9PullsPositionAndFallsBackToThreeComponentAverageWhenNull`：wr9=90 把分數從 53 拉高到 54、wr9=10 拉低到 50、wr9=null 時退回三分量平均（分數 52，j9=50 情境）。
  - `kdJContribution_isNullEntirelyWhenKdBandIsNarrow_regardlessOfWr9`：窄幅時 K/D/W%R 換成兩組互相對調的極端值，分數皆為 50（完全不受影響）。
  - `oscContribution_normalizesByPriceAmplitudeInsteadOfHardSign`：osc=0.01%（貢獻 0.02）在整數分數精度下與「無 OSC」無法區分（皆 50 分），且明確不等於飽和版（55 分）；osc=1%／5% 皆飽和至 55 分（貢獻 +1）；osc=−0.6% 飽和至 45 分（貢獻 −1）。
  - `oscContribution_belowNarrativeThreshold_omitsDirectionalText`：osc=0.05%（<0.1% 門檻）貢獻仍計入分數，但 reasons／risks 皆不含 OSC 動能文案。
- (e) 版本斷言兩檔皆已改 `TW_RULES_V12`（含方法名）。
- (f) 見下方「更新過期望值的測試清單」。

### 驗證輸出摘要

```
mvn test -Dtest='TradingRadarRuleEngineTest' -DextraArgLine=...
→ Tests run: 57, Failures: 0, Errors: 0, Skipped: 0（52 既有 + 5 新增）

mvn test -DextraArgLine=...（整套 backend）
→ 72 個測試類、604 個測試，Failures: 0, Errors: 0, Skipped: 0（surefire-reports 逐檔核對，exit code 0）

grep -ran "TW_RULES_V11" backend/src bff/src frontend/src → 無輸出（含全 repo 掃描亦無殘留）
```

### 與原計畫的偏差及原因

無實作邏輯偏差，V12 因子公式、權重、常數、文案觸發門檻皆與任務檔逐條相符。額外多做、但屬任務框架內的延伸：

1. **修正 2 處測試中既有的錯誤手算註解**（`kdHeat_userReportedCase_staysAddCandidateAndIsOnlyMarkedElevated` 與 `kdHeat_kAloneOverheated_closesBuyGateWithoutDeductingScore`）。這兩處註解在改動前就已經與實際斷言值不一致（註解寫 83／85，斷言卻是 87），是既有的文件級小瑕疵、與本次 V12 改動無關；因為我本來就要更新緊鄰的斷言數字，順手一併訂正為與 V12 相符的正確算式，避免在同一行留下兩個互相矛盾的錯誤註解。
2. 為 `kdJContribution`／`biasContribution`／`macdContribution` 補了較完整的 javadoc（說明「為什麼」而非變更歷史），並在 `RULE_VERSION` 常數上方權重區塊加了兩則簡短說明 W%R 併入緣由的行內註解——皆為既有房風格延伸，未改變任何行為。
3. 讀了 `spec/design.md` 第 4505–4509 行確認：此段已在 SDD 設計階段寫好，逐字描述了本任務要實作的三項因子修正，本次實作與該設計文字完全吻合，未發現需要提醒主 agent 回頭修 spec 的落差。

### 更新過期望值的測試清單（因 V12 語意變更）

以下 4 個既有測試因 `MW_KD_J` 由 0.04（併入 W%R 前）升為 0.07（併入後）而分數改變，皆已用手算加權公式核對（`sigma = Σ(w×c)/Σw`, `score = round(50+50σ)`）與實際引擎輸出完全一致，非湊數：

| 測試 | 舊值→新值 | 原因 |
|---|---|---|
| `bondDoesNotUseEquityRiskOffPenaltyOrBuyGate`（`bond.score()`） | 92→90 | `strongStock` fixture 的 KD/J 貢獻（0.5，K=60/D=40）低於已飽和的均線分量（1.0），KD/J 加權比重上修後把加權平均拉低 |
| `incompleteEquityMarketVetoesButBondCanStillBeEvaluated`（`bond.score()`） | 92→90 | 同上，同一 fixture 族群 |
| `kdHeat_userReportedCase_staysAddCandidateAndIsOnlyMarkedElevated`（`held.score()`） | 87→84 | `strongStockWithKd(K=82.3,D=75.2)` 的 KD/J 貢獻 0.2125 同樣低於均線飽和值，權重上修後拉低加權平均；動作（ADD_CANDIDATE）與 KdHeat（ELEVATED）不受影響 |
| `kdHeat_kAloneOverheated_closesBuyGateWithoutDeductingScore`（`held.score()`） | 87→84 | 同上（K=86,D=70），分數仍 ≥75，證明降級仍來自過熱閘門而非扣分的測試主張未變 |

以上 4 處皆屬「同一批 fixture 沒有設定 `bias10`／`bias20`／`osc`／`wr9`（`extendedIndicators` 為 null 或未設），故分數變化純粹來自 `SW_KD_J`／`MW_KD_J` 權重數值上修，不涉及 BIAS／OSC 公式改變」——已用 Accumulator 手算逐一驗證新舊值皆與程式實際輸出吻合，未回改任何實作邏輯。

### 給下一棒的注意事項

1. 工作區內另有 `spec/design.md`、`spec/requirements.md`、`spec/tasks.md`、`CLAUDE.md`、`backend/src/main/java/com/steven/assets/service/TradingRadarService.java`、`backend/src/test/java/com/steven/assets/service/TradingRadarUsStockEngineTest.java` 的未提交變更——這些是 t297（同波前置任務）留下的，本任務全程未觸碰這些檔案，僅供辨識、不需我處理。
2. `RULE_VERSION` 的 javadoc 已提及「極端時機自身分位（Task 299）」，t299 落地後若敘述與實作有出入，煩請 t299 或主 agent 一併核對這段 javadoc 是否仍準確。
3. 新增的 3 個 fixture helper（`biasIsolationStock`／`kdJIsolationStock`／`oscIsolationStock`，`TradingRadarRuleEngineTest.java` 內「Task 298 測試用 fixture」區塊）採「中性化除受測因子外所有計分項、中期 Σw 固定為 0.52」的手算友善設計，t299／t305 若需要對其他因子做同類型精確驗證，可直接沿用或仿造。
4. 部署驗證依任務檔指示併入 t305（整波驗證），本任務未跑 `run-stack`／未動 Docker。
5. 未對 `TechnicalIndicatorService` 或 `RadarInputAssembler` 做任何修改；`RadarInputAssembler.extendedIndicators()` 是單純透傳，`wr9`／`bias10`／`bias20`／`osc` 皆沿用既有欄位，無需同步。
