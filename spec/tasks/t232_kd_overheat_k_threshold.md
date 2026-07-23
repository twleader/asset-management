# [t232] 交易雷達 KD 過熱判定補 K 單獨門檻、修正與實證相反的文案、收合列偏熱揭露

**對應 Requirements:** Requirement 43（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助），修訂版本 `TW_RULES_V7`；並修訂 Requirement 47（匯率曝險與換匯估值納入交易雷達評分）中「KD 過熱與換匯過貴採否決買進」條目的過熱判定式
**前置任務:** 無（`TW_RULES_V6` 已上線）
**Liquibase changeset:** 無（本任務不動 DB schema）

## 背景

使用者於 2026-07-21 回報實際畫面：**00882（中信中國高股息）K 82.3／D 75.2、分數 83、已持有，被判為「加碼候選」**，質疑「K > 80 短線過熱為何建議加碼」。

### 現在的行為（兩個原因疊加）

**(a) 過熱判定用的是 K 與 D 的平均，不是 K 單獨。** `TradingRadarRuleEngine` 現行：

```java
private static final double KD_OVERHEAT_AVG = 80.0;

boolean kdOverheated = k != null && d != null
        && k.add(d).doubleValue() / 2.0 > KD_OVERHEAT_AVG;
```

00882 的 `avg = (82.3 + 75.2) / 2 = 78.75`，未達 80，故 `kdOverheated = false`、買進閘門照開。

**(b) 兩個 KD 子因子在高檔互相抵消，使過熱在分數上幾乎不可見。** K 剛突破 80 時正是 K 大幅領先 D 之際，動能子因子因此接近飽和地給正分：

```text
KD 動能 = clamp((K − D)/10, −1, +1) = +0.71，權重 0.08 → +0.057
KD 位置 = clamp(−(avg − 50)/50, −1, +1) = −0.575，權重 0.13 → −0.075
淨貢獻 −0.018 → 在 100 分制中僅扣 0.9 分
```

分數 83 ≥ 75 且閘門成立 → 已持有 → `ADD_CANDIDATE`。

### 正確行為

1. `K` 單獨達極端超買（`> 85`）時也須視為過熱、關閉買進閘門。
2. **`K = 82.3` 這個案例刻意維持 `ADD_CANDIDATE` 不變**（門檻取 85 的直接後果，見下方「門檻值的取捨」），但畫面必須讓使用者在**收合列**就看得出 K 已偏熱。
3. 過熱的風險文案不得再宣稱「回檔機率升高」——該說法與本專案自身回測資料矛盾。

### 門檻值的取捨（實作者必讀，不得自行調整）

以 `stock_price_history` 台股十年全樣本（106,131 列、50 檔，排除 `0000`）依 `TechnicalIndicatorService` 同一 KD9 遞迴重算，取「買進閘門其他條件成立」（MA20／MA60 皆連續兩完成日在均線上）的 38,186 個樣本（其中 37,720 個有完整 20 交易日後續）分組回測：

| 分組 | 樣本（具完整20日後續） | 後20日報酬 | 20日勝率 | MDD20均 | 10日內跌>3% | 20日內跌>10% |
|---|---|---|---|---|---|---|
| A 放行 `avg≤80, K≤80` | 23,342 | `+1.94%` | `54.5%` | `−5.98%` | `41.8%` | `15.9%` |
| B `avg≤80, 80<K≤85` | 2,438 | `+1.70%` | `56.8%` | `−6.03%` | `39.7%` | `13.8%` |
| C `avg≤80, K>85` | 239 | `+1.70%` | `62.8%` | `−5.05%` | `32.6%` | `10.0%` |
| D 已擋 `avg>80` | 11,701 | `+1.86%` | `58.7%` | `−5.30%` | `34.9%` | `11.1%` |

**回測結論與本任務方向相反：** 過熱組的後續下檔風險反而低於正常放行組，分年檢視含 2018／2022 下跌年皆一致。本門檻採納的理由是**使用者明確的風險偏好**（不願在單一指標極端超買時收到加碼建議），不是實證優勢。

因此：
- **`85` 是定案值，不得改為 `80`。** 選定依據是**受影響樣本量**——在一條無實證支持的規則上盡量縮小影響面：以上表 37,720 個具完整 20 交易日後續的樣本為分母，`85` 影響 C 組 239 個（`0.63%`），`80` 則另把 B 組 2,438 個（`6.46%`）一併降級、合計 2,677 個（`7.10%`）。**下檔風險數據對兩者皆不支持**（B 組 MDD `−6.03%` 與正常放行組 `−5.98%` 實質相同；C 組 `−5.05%` 甚至優於放行組），不得以「85 比 80 更能避開回檔」為由陳述此選擇。
- **不得在程式註解、風險文案或 UI 任何位置宣稱此門檻有回測依據或能降低回檔風險。**

## 要做什麼

### 232.1 後端：過熱判定增加 K 單獨門檻

檔案：`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`

- [x] 232.1.1 於既有 `KD_OVERHEAT_AVG = 80.0` 旁新增具名常數 `private static final double KD_OVERHEAT_K = 85.0;`，註解須說明「K 單獨達此值視為過熱；為使用者風險偏好取捨，非回測結論」。
- [x] 232.1.2 **門檻判定必須收斂成單一 private helper，全類只有這一處求值**：

  ```java
  /** KD 熱度三態。門檻的唯一求值處——閘門、風險文案、DTO 皆由此取得，不得各自比大小。 */
  private KdHeat kdHeatOf(BigDecimal k, BigDecimal d) {
      if (k == null || d == null) return KdHeat.NORMAL;
      double avg = k.add(d).doubleValue() / 2.0;
      double kv = k.doubleValue();
      if (avg > KD_OVERHEAT_AVG || kv > KD_OVERHEAT_K) return KdHeat.OVERHEATED;
      if (kv > KD_ELEVATED_K || avg > KD_ELEVATED_AVG) return KdHeat.ELEVATED;
      return KdHeat.NORMAL;
  }
  ```

  另新增兩個具名常數 `KD_ELEVATED_K = 80.0`、`KD_ELEVATED_AVG = 70.0`（偏熱揭露門檻，見 232.3）。
  `actionFor()` 中現行的 `kdOverheated` 區域變數改為 `boolean kdOverheated = kdHeatOf(k, d) == KdHeat.OVERHEATED;`，**不得保留原地的 `avg > 80` 算式**。`k` 或 `d` 為 null 時 helper 回 `NORMAL`，故 `kdOverheated` 為 false，維持現行行為。
  **為什麼強制收斂**：門檻若在閘門、文案、DTO 三處各判一次，日後任一處被改就會出現「畫面紅字標『過熱、動作已降級』但動作仍是加碼候選」的矛盾——正是本任務要消滅的那類使用者可見不一致。232.5.3 已以同一理由禁止前端寫死門檻，後端不得自我豁免。
- [x] 232.1.3 **不得因過熱而扣分、不得新增 `Action` enum 值、不得改動作映射分層。** 現行機制已是所需的「降級」：`kdOverheated` 只令 `buyGate` 為 false，分數維持原值後落入既有 `score >= 55` 分支，已持有得 `HOLD`、未持有得 `WATCH`。過熱標的必須保留原始分數與其餘 `reasons`，使畫面能同時呈現「長線結構良好」與「短線過熱故本日不加碼」。

### 232.2 後端：修正與實證相反的風險文案

同一檔 `kdPosition()` 方法現行輸出：

```java
if (avg > KD_OVERHEAT_AVG) {
    risks.add("KD 均值 " + Math.round(avg) + " 已達超買區，短線過熱、回檔機率升高。 ");
}
```

- [x] 232.2.1 移除「回檔機率升高」的宣稱（本專案十年回測顯示過熱組 20 日內跌逾 10% 的比例為 `11.1%`，低於正常放行組的 `15.9%`，此文案與自身資料矛盾）。改為不對後續機率作宣稱的中性描述，例如：
  `"KD 均值 " + Math.round(avg) + " 已達超買區，短線位置偏高；本日不列入買進／加碼候選。 "`
- [x] 232.2.2 `K` 單獨過熱（`K > 85` 但 `avg ≤ 80`）時亦須有對應風險文字：
  `"K 值 " + fmt1(k) + " 已達過熱區，短線位置偏高；本日不列入買進／加碼候選。 "`
  其中 `fmt1(BigDecimal)` 為一位小數格式（與畫面 `fmtNumber(row.kValue, 1)` 同精度，如 `82.3`）；**不得直接串接 `BigDecimal`**（會印出 `86.2340` 這種未格式化的值）。
  **文案不得把門檻數字寫進句子**（不可寫成「已高於 85」）——條件為嚴格大於，而 `K = 85.02` 在任何四捨五入下都會顯示成 `85.0`，句子就變成自我否定的「K 值 85.0 已高於 85」。既有 avg 分支的「已達超買區」正是不引述門檻的寫法，K 分支比照辦理。兩種過熱情形同時成立時**不得重複輸出兩條**（`avg > 80` 優先）。
- [x] 232.2.3 既有 `avg < 25.0` 的超賣 `reasons` 文案不變。

### 232.3 後端：新增 `kdHeat` 狀態欄位（供收合列揭露）

- [x] 232.3.1 於 `TradingRadarRuleEngine` **新增 enum**（比照同檔既有的 `Action`／`CounterTrendState`／`MarketRegime` 皆為 enum 的慣例，**不得回傳裸 String**）：

  ```java
  public enum KdHeat { OVERHEATED, ELEVATED, NORMAL }
  ```

  | 值 | 條件（依序判斷，先命中者優先） | 對動作的影響 |
  |---|---|---|
  | `OVERHEATED` | `avg(K,D) > 80 \|\| K > 85` | 關閉買進閘門（降級為 HOLD／WATCH） |
  | `ELEVATED` | `K > 80 \|\| avg(K,D) > 70` | **無**（純揭露） |
  | `NORMAL` | 其餘（含 `k` 或 `d` 為 null） | 無 |

  此表即 232.1.2 的 `kdHeatOf()`，**三態與過熱閘門共用該單一求值處**。**`ELEVATED` 不得影響分數、動作或任何既有計算**——它只是顯示用的狀態。
  **窄幅標的不設豁免**：`spec/design.md` 另記載「9 日高低帶寬度 `(hi9 − lo9)/lo9 < 2%` 時 KD 位置子因子回 null」的窄幅防護。**該防護目前只存在於設計文件、尚未實作**（`kdPosition()` 現行只判 k/d 是否為 null，`Indicators` record 也無 hi9／lo9 欄位，全樹 grep `hi9|lo9|bandWidth` 零命中）——**本任務不實作它，也不要去找它**。此處僅預先劃定作用域：該防護一旦實作，只作用於 KD 位置子因子的**計分**，不作用於本三態與過熱閘門。理由是兩者目的不同——防護是避免雜訊等級的 KD 去加減分數，而三態的作用是如實顯示指標讀數並在可能追高時不主動建議買進，對窄幅標的顯示其真實 K 值仍是正確陳述。
- [x] 232.3.2 `TradingRadarRuleEngine.StockResult` record **末尾**新增 `KdHeat kdHeat` 欄位。現行定義為：

  ```java
  public record StockResult(
          Integer score, Action action, CounterTrendResult counterTrend,
          List<String> reasons, List<String> risks) {}
  ```

  **`evaluateStock()` 開頭的資料不足分支**（`if (!complete(input))`，現行回傳 `new StockResult(null, Action.NO_TRADE, new CounterTrendResult(...), List.of(), List.of("個股必要的…今日不交易。"))`）**須帶 `KdHeat.NORMAL`，不得帶 null**。
- [x] 232.3.3 `ELEVATED` 時於 `risks` 加入資訊性提示，且須明示動作未受影響。**兩個觸發分支必須各有文案，不得共用**：
  - `K > 80` 命中 → `"K 值 " + fmt1(k) + " 偏高，短線偏熱；未達過熱門檻，動作維持。 "`
  - 僅 `avg(K,D) > 70` 命中（K 未過 80）→ `"KD 均值 " + Math.round(avg) + " 偏高，短線偏熱；未達過熱門檻，動作維持。 "`

  **原因**：`K=70, D=75` 會使 `avg=72.5` 觸發 `ELEVATED`，但 K 並未高於 80——共用 K 版文案會輸出「K 值 70 已高於 80」這句假話，與 232.2.1「不得陳述資料不支持的宣稱」自相矛盾。同理兩條文案皆**不得把門檻數字寫進句子**（理由見 232.2.2）。兩分支同時成立時只輸出 K 版一條。
- [x] 232.3.4 `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` 的 `StockDecision` record **末尾**新增 `String kdHeat`（值為 `OVERHEATED`／`ELEVATED`／`NORMAL` 三者之一）。**必須加在 record 參數列最末**，避免既有位置參數建構呼叫錯位。加 javadoc 說明三態語意與「`ELEVATED` 不影響動作」。
- [x] 232.3.5 `TradingRadarService` 有**兩處**位置參數建構 `StockDecision`，兩處都要帶值：
  - `buildStock(...)`：以 `result.kdHeat().name()` 帶入，比照同一建構呼叫中既有的 `result.action().name()`／`result.counterTrend().state().name()` 寫法。
  - `incompleteStock(Target, String, String, String)`（資料不足時的簡化建構，現行帶 `Action.NO_TRADE.name()`／`null` 分數）：帶 `TradingRadarRuleEngine.KdHeat.NORMAL.name()`，**不得帶 null**——前端 232.5.3 的前提是「只讀後端給的三態字串」，給 null 會讓前端被迫做防呆判斷。
- [x] 232.3.6 **`TradingRadarExportService` 本次不新增 Excel 欄位**，欄序完全不變。（該類以 JsonNode 具名取值且已容忍缺欄位，故新增 DTO 欄位不影響既有匯出，亦不影響 Redis 內既有舊快照的反序列化——舊快照無 `kdHeat` 欄位屬正常，不得為此做資料遷移或回填。）

### 232.4 後端：規則版本升級

- [x] 232.4.1 `TradingRadarRuleEngine.RULE_VERSION` 由 `"TW_RULES_V6"` 改為 `"TW_RULES_V7"`。
- [x] 232.4.2 `TradingRadarDto` 類別 javadoc 中的 `TW_RULES_V6` 字樣同步更新。
- [x] 232.4.3 V7 的因子組成、權重與正規化方式與 V6 完全相同，分數跨版本可比，**不需要**新增不可比性揭露機制。
- [x] 232.4.4 **以下位置的 `TW_RULES_V6`／`TW_RULES_V5` 字樣不得更動**，勿以全樹 grep 取代方式處理版號：`TradingRadarExportService`／`TradingRadarSnapshotStoreTest` 中形如「為 Task 228（TW_RULES_V6）新增的欄位」的**歷史註解**（改了會失真）；`TradingRadarExportServiceTest`／`TradingRadarSnapshotStoreTest` 中刻意使用 `TW_RULES_V5` 的**測試 fixture**（其用意正是驗證舊版號快照仍能被讀回，改動會破壞測試意圖）；`spec/tasks/t228_*.md` 等**歷史任務檔**。`spec/design.md` 的架構圖註解已於本任務的 spec 階段同步為 V7，實作階段不需再動 spec。

### 232.5 前端：收合列即可辨識偏熱／過熱

檔案：`frontend/src/views/TradingRadarView.vue`

- [x] 232.5.1 **兩處 hardcode 的 `'TW_RULES_V6'` 字串同步改為 `'TW_RULES_V7'`**：約第 28 行的顯示 fallback（`{{ radar.ruleVersion || 'TW_RULES_V6' }}`）與約第 503 行 `radar` ref 的初始值（`ruleVersion: 'TW_RULES_V6'`）。
- [x] 232.5.2 收合列的 KD 欄（約第 242 行，`K {{ fmtNumber(row.kValue, 1) }} / D {{ fmtNumber(row.dValue, 1) }}`）依 `row.kdHeat` 加註視覺標記：
  - `OVERHEATED`：明顯的警示樣式（如紅字＋「過熱」小標），語意為「動作已因此降級」。
  - `ELEVATED`：較弱的提示樣式（如橘字＋「偏熱」小標），語意為「僅提醒，動作未受影響」。
  - `NORMAL`：維持現狀，不加任何標記。
  **兩種標記必須在視覺上可區分**，不得共用同一樣式——否則使用者無法判斷動作是否已受影響，這正是本任務要解決的問題。
- [x] 232.5.3 **門檻值（80／85／70）一律不得寫死在前端**，前端只讀後端給的 `row.kdHeat` 三態字串。（後端已是門檻的唯一權威，前端重複判斷會在日後調整門檻時靜默不同步。）
- [x] 232.5.4 展開列既有的 `risks` 清單渲染不需改動（新文字會自動出現）。

### 232.6 測試

檔案：`backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java`（既有檔，追加測試）

**共用 fixture（下列案例除 K／D 外皆相同，用以得出確定的分數）**：`EQUITY`、已持有、現價高於 MA20／MA60／MA240 三線、三條均線兩日確認皆 `ABOVE`、大盤 `RISK_ON` 且非 stale、當日漲跌幅 `0%`、`fxPercentile = null`（台幣資產）。此時六項均線因子皆 `+1`（權重合計 `0.61`）、大盤 `+0.5×0.08`、單日漲跌 `0×0.05`、匯率為 null 不計入分母，故 `Σw = 0.95`，`score = (int) Math.round(50 + 50 × Σ(w×c) / 0.95)`。

- [x] 232.6.1 **釘住使用者回報的案例**：`K=82.3, D=75.2`（`avg=78.75`）→ 動能 `+0.71`、位置 `−0.575` → `Σ(w×c) = 0.63205` → **`score` 恰為 `83`**（與使用者回報畫面一致），`action` 為 **`ADD_CANDIDATE`**，`kdHeat = ELEVATED`。此測試刻意鎖定「本任務不改變此案例的動作」，避免日後有人把門檻誤調為 80。
- [x] 232.6.2 **驗證過熱不扣分**：`K=86, D=70`（`avg=78`，僅 K 過熱）→ 動能 clamp 為 `+1.0`、位置 `−0.56` → `Σ(w×c) = 0.6572` → **`score` 恰為 `85`**（≥75，證明分數未被過熱扣減，是閘門而非扣分讓它降級），`action` 為 `HOLD`（已持有）／`WATCH`（未持有），`kdHeat = OVERHEATED`。
- [x] 232.6.3 `K=82, D=80`（`avg=81`，僅 avg 過熱、K 未過 85）→ 沿用既有過熱降級行為（迴歸保護，確保本次改動未破壞 V6 行為）。既有測試 `TradingRadarRuleEngineTest.kdOverheat_vetoesBuyEvenWhenAllTrendSignalsAreGreen()` 已覆蓋同類情境，須確認其仍通過。
- [x] 232.6.4 過熱時 `risks` **不含**「回檔機率升高」字樣。
- [x] 232.6.5 `avg > 80` 且 `K > 85` 同時成立時（如 `K=90, D=86`），過熱風險文字只出現一條。
- [x] 232.6.6 `k` 或 `d` 為 null 時，因既有 `complete(Indicators)` 要求 K／D 非 null，該檔會落入 `evaluateStock()` 開頭的資料不足分支得 `NO_TRADE`；此分支的 `kdHeat` 須為 **`NORMAL` 而非 null**（前端只讀三態字串，不做防呆）。
- [x] 232.6.7 **驗證偏熱揭露不影響計算**：`K=82.3, D=75.2` 的 `score`（`83`）與 `action`（`ADD_CANDIDATE`）**與 232.6.1 完全相同**，即偏熱提示只增加一條 `risks` 文字，不改變任何數值輸出。斷言方式為直接比對上述兩個確定值，不得寫成「與移除揭露後相同」這種測試內無法構造的反事實。
- [x] 232.6.8 **偏熱的 avg 分支不得輸出假陳述**：`K=70, D=75`（`avg=72.5 > 70` 但 `K < 80`）→ `kdHeat = ELEVATED`，且 `risks` 中該條提示須為均值版文案（含「KD 均值 73」）、**不含「K 值」字樣**。
- [x] 232.6.9 **文案不得引述門檻數字**：對 `K=85.02, D=60`（`K` 剛過過熱門檻）與 `K=80.02, D=60`（剛過偏熱門檻）兩案，斷言其 KD 相關 `risks` 文字**不含「高於 80」「高於 85」等字樣**——這類寫法在四捨五入後會產生「K 值 85.0 已高於 85」的自我否定句。
- [x] 232.6.10 `RULE_VERSION` 為 `TW_RULES_V7`。

## 驗證

```bash
# 1. 後端測試（Mockito 在本專案 JDK 需要 byte-buddy experimental，直接 -D 無效，須走 argLine）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -Dtest=TradingRadarRuleEngineTest \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 全量後端測試（確認未破壞既有 V6 行為與快照／匯出測試）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 3. 從 worktree 建置前，先把主 repo 的 .env 複製進來
#    （compose 的 env_file 相對 compose 檔解析，--env-file 救不了）
cp /Users/steven/Project/asset-management/.env .

# 4. JVM service 一律 --no-cache 重建（cached build 會產出不含本次變更的 stale jar，
#    症狀是前端有新版、後端行為卻是舊的）
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services

# 5. 重建 business 會換容器 IP，BFF 會握著舊 IP 回 500 且不會自癒（Docker DNS TTL 600s）
#    → 必須跟著重啟 bff，否則畫面整頁 500 而 business log 乾淨
docker compose -p asset-management restart bff

# 6. 前端變更後同樣要 --no-cache（普通 build 會命中 layer cache 而沒重跑 vite）
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend

# 7. 確認運行中的 jar 真的含本次變更（避免 stale image 誤判）
docker exec asset-business-services sh -c \
  'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/service/TradingRadarRuleEngine.class | strings | grep -c TW_RULES_V7'
# 預期輸出 >= 1

# 8. 端到端：容器內帶 X-User-Id 模擬租戶打 business 端點，確認回傳含 kdHeat 與新版號
docker exec asset-business-services sh -c \
  'curl -s -H "X-User-Id: 1" -H "X-User-Role: USER" -H "X-User-Status: ACTIVE" \
   http://localhost:8080/api/trading-radar' | head -c 600
# 預期：ruleVersion 為 TW_RULES_V7，個股物件含 kdHeat 欄位

# 9. 健康檢查（只有 asset-bff 對 host 發佈 8080 且啟用 actuator；business 與 external
#    未啟用 actuator，直接打會回 500／404。此步驗的是步驟 5 的 bff 是否正常回來，不驗 business）
curl -s http://localhost:8080/actuator/health
```

**畫面驗收**：開啟交易雷達頁，確認 (a) 右上規則版本標籤顯示 `TW_RULES_V7`；(b) 00882 這類 `K > 80` 但未達過熱門檻者，**在收合狀態下**其 KD 欄即可見「偏熱」標記，且動作仍為「加碼候選」；(c) `K > 85` 或 `avg > 80` 者顯示可區分的「過熱」標記且動作為「持有」／「觀察」；(d) 展開列的風險清單不再出現「回檔機率升高」。

## 完成報告

**實際改動（5 檔）**

| 檔案 | 變更 |
|---|---|
| `TradingRadarRuleEngine.java` | `RULE_VERSION` → `TW_RULES_V7`；新增 `KD_OVERHEAT_K=85.0`／`KD_ELEVATED_K=80.0`／`KD_ELEVATED_AVG=70.0` 三個具名常數、`KdHeat` enum、`kdHeatOf()`（門檻唯一求值處）、`describeHeat()`、`fmt1()`；`StockResult` 末尾加 `KdHeat kdHeat`；`actionFor()` 的 `kdOverheated` 改讀 `kdHeatOf()`；`kdPosition()` 移除超買文案與不再需要的 `risks` 參數（改由 `describeHeat()` 統一輸出） |
| `TradingRadarDto.java` | `StockDecision` 末尾加 `String kdHeat`；類別 javadoc `TW_RULES_V6` → `V7` |
| `TradingRadarService.java` | `buildStock` 帶 `result.kdHeat().name()`；`incompleteStock` 帶 `KdHeat.NORMAL.name()` |
| `TradingRadarView.vue` | 兩處 hardcode 版號 → `TW_RULES_V7`；KD 欄依 `row.kdHeat` 加紅色「過熱」／橘色「偏熱」標記（欄寬 120→168）；新增 `kdHeatClass()` 與三條 CSS |
| `TradingRadarRuleEngineTest.java` | 新增 8 個測試與 `strongStockWithKd()` fixture |

**驗證輸出**

- 單元測試：`TradingRadarRuleEngineTest` 37 個全過；全量後端 **110 個測試 0 failures 0 errors**（含 `TradingRadarSnapshotStoreTest`／`TradingRadarExportServiceTest`，證實 DTO 加欄位未波及快照與匯出）。
- **釘住的兩個確定值皆通過**：`K=82.3/D=75.2` → `score == 83`（複現使用者回報畫面）且 `ADD_CANDIDATE`；`K=86/D=70` → `score == 85` 且降級為 `HOLD`（分數 ≥75 未被扣減，證明降級來自閘門而非扣分）。
- 部署：`business-services` 與 `frontend` 皆 `--no-cache` 重建並 `--force-recreate`，business recreate 後已 `restart bff`。
- 線上 API（容器內帶 `X-User-Id` header）：`ruleVersion = TW_RULES_V7`，19 檔全含 `kdHeat`，分佈 16 `NORMAL`／2 `OVERHEATED`／1 `ELEVATED`。實際輸出：
  - 00719B（K 88.14／D 87.95、76 分）→ `OVERHEATED`，「KD 均值 88 已達超買區，短線位置偏高；本日不列入買進／加碼候選。」
  - 00882（K 82.25／D 75.23、81 分）→ `ELEVATED`，「K 值 82.3 偏高，短線偏熱；未達過熱門檻，動作維持。」**動作未受影響**，符合設計。
- 前端 bundle（`TradingRadarView-CynuoI57.js`）：`TW_RULES_V7` 1 處、`TW_RULES_V6` 殘留 0、「過熱」「偏熱」標記與 `kd-overheated` class 均在。

**與原計畫的偏差**

1. **232.6.6 措辭於實作中修正**：原寫「`k`／`d` 為 null 時不因此關閉買進閘門」，但既有 `complete(Indicators)` 要求 K／D 非 null，該檔會先落入資料不足分支得 `NO_TRADE`，根本不走到閘門。已改為驗證「該分支的 `kdHeat` 為 `NORMAL` 而非 null」。
2. **驗證步驟 7 的 jar 檢查方式不適用於中文**：`strings` 抓不到 class 常數池中的 UTF-8 中文（對照組「回檔機率升高」同樣回 0，非真的被移除），故新文案改以線上 API 實際回傳驗證；`TW_RULES_V7` 為 ASCII，該步驟本身仍有效。
3. **`kdPosition()` 的 `risks` 參數一併移除**（原任務檔未明列）：超買文案移至 `describeHeat()` 後該參數已無用途，留著會是死參數。
4. **畫面驗收未由實作者完成**：站台走 Google OAuth，需使用者本人登入，故 (b)(c)(d) 三項視覺確認留待使用者於畫面確認；API 與 bundle 兩層已實證。
5. **線上 00882 當下顯示「續抱」而非「加碼候選」**，原因為大盤 `stale=true`（驗證時為台北時間深夜，台股無今日完成日 K 亦無即時點位），觸發 Task 217.1 既有的「stale 時關閉買進閘門」規則，**與本任務無關**；單元測試已在非 stale 條件下釘住該檔仍為 `ADD_CANDIDATE`。
