# [t264] 交易雷達改為「趨勢品質 × 進場時機」二維決策：均值回歸因子、高檔轉弱出場、極端超賣不殺低、ETF 折溢價

**對應 Requirements:** Requirement 43 修訂（規則版本 `TW_RULES_V9`）——今日交易雷達的評分因子與動作映射結構性修正，使建議方向符合「不追高殺低、高點示警、急跌錯殺可進場、持有期數周至兩年」；Requirement 34（ETF 淨值與折溢價每日入庫留存——本任務新增其消費端）
**前置任務:** **t265（還原序列補齊股票分割）**——本任務新增的 52 週相對位置與既有 MA240 皆使用 240 根視窗，視窗內若有分割會靜默算錯且方向相反。不依賴 t222／t223／t224；本任務只用 `stock_price_history`、`etf_nav_history` 既有欄位與既有還原序列。
**後續任務:** t266（台股基本面資料抓取）→ t267（基本面因子接進 V10）。**個股基本面不在本任務範圍，理由見「本任務不做什麼」。**
**Liquibase changeset:** 無（不動 schema、不新增資料表）

---

## 背景

### 現在的錯誤行為

使用者要求：「建議的方向不該追高殺低」「股市急跌，如果能事先看出隔日將大幅反彈，應建議買入」「當股市在高點，隔日或近幾日下殺風險很高時，應建議賣出」「獲利期間是數周至兩年，不是極短線」。現行 `TW_RULES_V8` 在**結構上**做不到，且方向恰好相反。

**根因：動作映射對分數單調，而分數量的是「順勢強度」。** `TradingRadarRuleEngine.actionFor()`（`:554-585`）以 `score` 單一維度分層，而 `score` 由六項均線因子主導（權重合計 `0.61`／總權重 `0.95`）。後果是兩個方向的封閉：

| 情境 | Σ(w×c) 實算 | score | 動作 |
|---|---|---|---|
| 站上全部均線，`K=90／D=100`（動能 −1、位置 −0.9 的真實極小值）、大盤 `RISK_OFF`、當日 −5% | `0.61−0.08−0.117−0.08−0.05 = 0.283`，σ=0.298 | **65** | `HOLD` |
| 跌破全部均線，KD 均值 10、K<D、大盤 `RISK_OFF`、當日 −6% | `−0.61−0.04+0.104−0.08−0.05 = −0.676`，σ=−0.712 | **14** | `EXIT_CANDIDATE` |

第一列是**數學下限**：只要六項均線因子全為 `+1`，其餘因子即使踩到各自的真實極小值，score 的下限也是 `65`（台股個股無 `fxPercentile`，`ΣW=0.95`；帶匯率因子的標的 `ΣW=1.00`，下限為 `62`），**永遠達不到減碼(<40)／出場(<25) 門檻**。第二列是急跌時的必然結果。合起來就是：**價格在均線之上永遠不會被建議賣出，跌破均線就必然被建議出場**——追高殺低是這個設計的結構性後果，不是參數沒調好。

> **極小值的取法須留意，不可用「KD 都填 95」草率代入。** `kdMomentum` 為 `clampUnit((K−D)/10)`、`kdPosition` 為 `clampUnit(−(avg−50)/50)`，兩者對 K、D 的偏導方向相反：`K=D=95` 會使動能為 `0`（非 `−1`）、位置為 `−0.9`（非 `−1`），且「K=D」與「K<D」自相矛盾。真正同時壓低兩者的輸入是 `K=90／D=100`（動能 `−1`、位置 `−0.9`）。

**逐項證據：**

- **無任何距離型因子。** `positionOf()`（`:358-372`）只回二元 `±1.0`：`int cmp = price.compareTo(ma); if (cmp > 0) { ...; return 1.0; }`。價格高於年線 `0.01%` 與高於 `33%` 的貢獻完全相同。既有測試 fixture `strongStockWithKd()`（`TradingRadarRuleEngineTest.java:491-509`）正是 `price=120／MA20=110／MA60=100／MA240=90`（高於年線 **33.3%**）的極端拉伸標的，測試 `:388-398` 明文釘住它得 **83 分／`ADD_CANDIDATE`**。
- **唯一的追高抑制無效。** `dayMoveContribution()`（`:482-493`）僅在單日 `±5%` 觸發，權重 `W_DAY_MOVE=0.05`，最大效果 `50×0.05/0.95 = 2.63` 分，不足以跨越任何動作分層；且 `+5%` 與漲停 `+10%` 貢獻相同。`StockInput`（`:126-141`）沒有任何跨日累積漲幅欄位，故連續 10 日各漲 `4.9%`（累計 `+61.4%`）**零扣分**。
- **KD 過熱不產生賣出。** `kdHeatOf()`（`:424-431`）判定的 `OVERHEATED` 只用於關閉 `buyGate`（`:573-577`），不扣分、不改分層。既有測試 `:407` 明文斷言 `assertEquals(85, held.score(), "過熱不得扣分")`，動作為 `HOLD`。**全引擎沒有任何在高檔主動建議賣出的路徑。**
- **急跌時買進在結構上不可能。** `qualifiesForTrialBuy()` 條件 6（`:550-551`）`return !equityMarketApplies(input) || (input.marketRegime() != MarketRegime.RISK_OFF && !input.marketStale());`——大盤急跌時 `regime` 必為 `RISK_OFF`，股票一律不試單。這與同一支引擎的 `evaluateCounterTrend()`（`:303-305、319-321`）自相矛盾：後者在 `RISK_OFF` 時仍輸出「只限小額試單，不得視為一般買進或加碼候選」的文案，顯示原設計預期 `RISK_OFF` 下可小額試單，卻被條件 6 封死。
- **大盤因子純順週期。** `evaluateMarket()`（`:213-216`）對大盤單日漲逾 `3%` 給 `score += 3`，`regime` 分層（`:219-221`）上方沒有任何「過熱」區間。
- **時間尺度不符。** 依 264.6 權重表的分組，V8 的短期組（MA20 位置 `0.10` ＋ MA20 確認 `0.06` ＋ KD 動能 `0.08` ＋ KD 位置 `0.13` ＝ `0.37`）壓過中期組（MA60 位置 `0.12` ＋ 確認 `0.08` ＝ `0.20`）與長期組（`0.15 + 0.10 = 0.25`）。沒有任何數月至兩年尺度的因子。

### 推翻了什麼

- **推翻 Requirement 43 的 AC「動作映射」（「分數 ≥ 75……`<25` 時已持有＝`EXIT_CANDIDATE`」）與 Requirement 47 的 AC「匯率因子的實際落地（採扁平加權，非分組）」中的 11 因子權重表**（兩處皆已於 requirements.md 加註被本修訂取代；**刻意以 AC 標題引用而非行號**——本次變更自身即造成 requirements.md 行號位移，行號引用會反覆失效）。本任務改為二維：`score` 維持「趨勢品質」語意不變，**新增正交的「進場時機」維度**（`TimingState`），由後者對前者的動作輸出做雙向修正。這是解決追高殺低的**唯一結構性作法**——單維度下「高分」與「該賣」無法同時表達。
- **推翻 Task 226／Requirement 43 修訂的 `TRIAL_BUY` 條件 6 中的 `RISK_OFF` 封鎖**（`marketStale` 封鎖保留，見 264.5）。原設計理由是「大盤 `RISK_OFF` 時風險過高」；使用者明確要求「股市急跌時應建議買入」，且該封鎖與同引擎 `evaluateCounterTrend()` 的既有文案自相矛盾。
- **推翻 `evaluateMarket()` 對大盤單日漲逾 3% 的 `+3` 加分**（`:213-216`）。獎勵大盤暴漲與「不追高」直接衝突。單日跌逾 3% 的 `−10` **保留不動**（只收緊不放寬的既有原則）。

### 本任務不做什麼（明確劃界）

- **不引入任何新外部資料源。** 需求「債券 ETF 需納入 Fed、台灣央行等公開資訊」中的**利率／央行部分不在本任務範圍**（全庫查證 `grep -ran`：`federal_funds`／`policy_rate`／`treasury`／`TNX`／`yieldCurve`／`FOMC` 六個識別字在 `backend/src/main`、`external-materials-service/src/main`、`db` 三處**零命中**；既有「殖利率」字樣全為股息殖利率。**`央行` 例外——它有 5 筆命中，但全部位於新聞爬蟲的關鍵字清單與註解**（`EditorialNewsFilter.java:66,125,147`、`NewsFetchClient.java:25,50`），**未進入雷達評分鏈**，故「雷達沒有央行資訊」的結論不變，但不得寫成「零命中」）。匯率風險維持既有 `fxPercentile` 五年分位機制不變。**前端不得因本任務而宣稱雷達已納入利率或央行資訊。** ETF 折溢價**在範圍內**（264.7），因其資料已由 Requirement 34／Task 215 落地於 `etf_nav_history`，屬既有資料而非新資料源。
- **不納入美股／英股。** `TradingRadarService.assemble()`（`:150`）的 `.filter(t -> TW_MARKET.equals(t.market()) ...)` 維持不變。
- **個股基本面不在本任務範圍，改由 t266／t267 承接。** 使用者要求「如果是個股，一定要參考基本面」，此需求**成立且必須實作**，但**不能併入本任務**——查證結果：`stock_valuation_daily`／`stock_financial_quarter`／`stock_monthly_revenue` 三張表在 `backend/src`、`external-materials-service/src` **零命中**，t222 從未實作，即**基本面資料在本系統中完全不存在**。而其唯一合規來源（TWSE／TPEx 開放 API）只提供當期單一快照、`?date=` 參數被伺服器忽略，歷史回補的唯一管道 MOPS 全站 `robots.txt` 為 `Disallow: /`。故基本面必須「先建抓取、再等累積、最後接線」三步走，**與本任務的因子重構彼此獨立、不得互相阻塞**：本任務用現有資料即可立即改善追高殺低，若綁在一起等基本面，使用者要兩年後才看得到任何改善。資料可用時程見 t266。
- **不實作 t223 的五組分組正規化框架。** 本任務在既有扁平權重結構上新增因子。t266 的基本面因子亦沿用扁平權重，不改為分組。**四份從未實作的舊任務檔須於檔頭加註取代關係並更新 `spec/tasks.md` 索引：t225 → t265、t223 → t264（僅取其問題診斷，不取五組框架）、t222 → t266、t224 → t267。**
- **不擴大取數視窗。** 維持 241 根。t223 已明文警告「分割還原未完成前不得擴大取數視窗」（0050 於 2025-06-18 有 1:4 分割，跨過會使長窗因子方向相反且不拋例外）。本任務新增的兩個長期因子（52 週相對位置、季線乖離）**刻意都落在 241 根視窗內**，故不觸發「擴大視窗」的風險。**但視窗內本身就可能有分割**——`2327` 於 2025-08-25 有 `546.00 → 143.00`（≈3.82 倍）的真分割，241 個台股交易日回推約至 2025-08-10，**該分割在現行視窗內**。這正是 t265 必須先做的理由，不得以「視窗沒擴大」為由跳過。**不得因本任務新增「年線斜率」（需 300 根）或「3 年年化報酬」（需 750 根）。**

---

## 要做什麼

### 264.1 新增四個輸入值，全部由既有還原序列計算

`TradingRadarService.prepareTechnicalData()`（`:334-376`）已持有 `adjustedRows`（`List<StockPriceHistory>`，日期降冪，還原權息後）。`DistributionAdjustedPriceService`（`:112-113`）**同時還原 `highPrice` 與 `lowPrice`**，故以下四值與 MA／KD 同一價基，**不違反「禁止混用原始／還原價」**。

- [x] **`ma60BiasPercent`**：`(price − ma60) / ma60 × 100`。`ma60` 為 `null` 或 `signum() <= 0` 時回 `null`。
- [x] **`week52Position`**：`(price − low52) / (high52 − low52)`，其中 `high52`／`low52` 取 `adjustedRows` 前 240 筆的 `highPrice` 最大值／`lowPrice` 最小值（**須跳過 null**；含 live 合成列時取前 241 筆以對齊既有 `indicatorRows` 慣例）。**結果必須 `clamp(0, 1)`**——`high52`／`low52` 取自完成日 K 而 `price` 可能是 Redis 即時價，創 52 週新高當日 `pos > 1`，不 clamp 會使貢獻超出 `[-1,+1]` 而破壞 `score ∈ [0,100]` 不變量（此路徑只在創新高當天出現，須有專屬測試）。不足 240 筆、`high52 == low52`、或 `high52/low52` 全為 null 時回 `null`。
- [x] **`kdBandWidthPercent`**：`(hi9 − lo9) / lo9 × 100`，取 `adjustedRows` 前 9 筆的 `highPrice` 最大／`lowPrice` 最小。`lo9 <= 0`、不足 9 筆或高低全 null 時回 `null`。
- [x] **`ma240BiasPercent`**：`(price − ma240) / ma240 × 100`，供 `TRIAL_BUY` 條件 1 改用具名共用計算（取代 `qualifiesForTrialBuy()` 內現行的 inline `premium`），並供風險文案引用。
  **⚠ 單位改變，必須同步改常數**：現行 `TRIAL_BUY_MIN_ANNUAL_PREMIUM = 0.05`（`TradingRadarRuleEngine.java:514`）比對的是**小數**（`:534-535` 的 `premium` 未乘 100）。改用百分比值後**必須同步把該常數由 `0.05` 改為 `5.0`**，否則門檻會由「年線乖離 ≥ 5%」變成「≥ 0.05%」。
  這是本任務**最危險的靜默路徑**：疊加 264.5 移除 `RISK_OFF` 封鎖後，等同在大盤急跌時對幾乎所有站上年線的標的大量誤發 `TRIAL_BUY`；而驗證段的急跌案例用「年線乖離 +8%」，**兩種單位都會通過、測不出來**，故驗證段另立邊界測試（乖離 `+0.5%` ⇒ 不得 `TRIAL_BUY`）。

四值一併加入 `TradingRadarRuleEngine.StockInput`。**大盤 `MarketInput` 不加**——本任務不改大盤的因子組成（僅 264.6 移除一條加分）。

### 264.2 窄幅 KD 失效（本任務的必要前置，不是加分項）

- [x] `kdBandWidthPercent < KD_BAND_MIN_PERCENT`（具名常數 `= 2.0`）時：
  - **KD 位置因子回 `null`**（權重由 `Accumulator` 重分配，不得以 0 充當中性）；
  - **`kdHeatOf()` 一律回 `NORMAL`**（不觸發過熱否決）；
  - **`TimingState` 不得由 KD 判定**，只能由乖離判定（見 264.3）。
  - **`qualifiesForTrialBuy()` 一律回 `false`**、**`evaluateCounterTrend()` 一律回 `NONE`**（見下方說明）；
  - **KD 動能不受此限制**（方向訊號在窄幅下仍有意義，只是幅度小），沿用 t223 既有結論。
- [x] `kdBandWidthPercent` 為 `null`（資料不足）時**視同未觸發**（照常判定），不得因缺值而關閉保護。

**低檔側同樣要防護，不可只防高檔。** `qualifiesForTrialBuy()` 條件 3（`TradingRadarRuleEngine.java:539`，`k >= 20 || d >= 20` 即否決）與 `evaluateCounterTrend()`（`:277`、`:288` 的 `k < 20`、`d < 20`）**直接吃原始 K／D，不經 `kdHeatOf()`**，故上面三條防護對它們無效。窄幅失真對低檔完全對稱成立——00719B 的 `K=90.76` 只代表「比 9 日低點高 0.37 元」，同樣地 `K=5` 也只代表「比 9 日高點低幾毛」。而 264.5 正在移除 `TRIAL_BUY` 的 `RISK_OFF` 封鎖，等於放大這條路徑的觸發面，**不補這一條會讓債券 ETF 在窄幅震盪中反覆誤發試單建議**。

**為什麼這是前置而非選配：** 本任務新增「極端超買 ＋ 高檔轉弱 → 減碼」與「極端超賣 → 阻擋出場」兩條由 KD 驅動的動作覆寫。t223 實測 00719B（元大美債1-3）近 60 個交易日價格區間僅 `3.096%`、**9 日高低帶平均寬度僅 `1.011%`**，其 `K=90.76` 實質只代表「比 9 日低點高 0.37 元」。不先做此防護，債券 ETF 會因雜訊飽和而**大量誤發減碼建議**——那是比現況更糟的迴歸。

### 264.3 新增 `TimingState`（進場時機維度）

- [x] 新增 enum `TimingState { EXTREME_OVERBOUGHT, OVERBOUGHT, NEUTRAL, OVERSOLD, EXTREME_OVERSOLD }`，於 `StockResult` 回傳。全部門檻為具名常數。

判定順序（**由極端往中性依序判斷，先命中先返回；區間互斥且窮盡**）：

| 狀態 | 條件 |
|---|---|
| `EXTREME_OVERBOUGHT` | `kdHeatOf() == OVERHEATED` **且** `ma60BiasPercent >= BIAS_EXTREME_HIGH`（`= 20.0`） |
| `EXTREME_OVERSOLD` | `kdOversold()` **且** `ma60BiasPercent <= BIAS_EXTREME_LOW`（`= −20.0`） |
| `OVERBOUGHT` | `kdHeatOf() == OVERHEATED` **或** `ma60BiasPercent >= BIAS_HIGH`（`= 12.0`） **或** `etfPremiumPct >= ETF_PREMIUM_EXPENSIVE`（`= 3.0`，見 264.7） |
| `OVERSOLD` | `kdOversold()` **或** `ma60BiasPercent <= BIAS_LOW`（`= −12.0`） |
| `NEUTRAL` | 其餘 |

- [x] `kdOversold()` 為**新增的、對稱於 `kdHeatOf()` 的唯一求值處**：`avg(K,D) < KD_OVERSOLD_AVG`（`= 20.0`）**或** `K < KD_OVERSOLD_K`（`= 15.0`）。與 `kdHeatOf()` 同樣受 264.2 窄幅失效約束。**對稱性是本設計的核心論證**：買方既有「超買否決買進」，賣方就必須有「超賣否決賣出」，否則系統只在單一方向上保守。
- [x] `ma60BiasPercent` 為 `null` 時，該狀態只由 KD 判定；KD 亦不可用時回 `NEUTRAL`。

### 264.4 動作映射改為二維（本任務解決使用者問題的主要機制）

`actionFor()` 改為下列順序，**每一步的覆寫都必須寫進 `risks`／`reasons` 使畫面可解釋**：

- [x] **第 1 步（不變）**：`qualifiesForTrialBuy()` 成立 → `TRIAL_BUY`。
- [x] **第 2 步（新增，需求「高點應建議賣出」）**：`TimingState == EXTREME_OVERBOUGHT` **且高檔轉弱已確認** →
  `held ? REDUCE_CANDIDATE : AVOID`。**此覆寫凌駕分數**——高檔時分數必高，不凌駕就永遠觸發不到。

  「高檔轉弱已確認」定義為 **KD 高檔死亡交叉**：`previousK != null && previousD != null && previousK > previousD && k <= d`。
  **必須要求轉弱、不得只憑超買就賣**：只要超買就出場會在主升段初期就砍掉部位，與「考慮風險下讓獲利最大化」直接衝突；要求死叉確認才賣，是「讓利潤奔跑、轉弱才走」。
  `previousK`／`previousD` 已存在於 `StockInput`（`:132-133`），**不需要新資料**。

- [x] **第 3 步（新增，需求「不殺低」）**：分數映射結果為 `REDUCE_CANDIDATE`／`EXIT_CANDIDATE`／`AVOID`，但 `TimingState == EXTREME_OVERSOLD` 時 → **降級為 `HOLD_CAUTION`（已持有）／`WAIT`（未持有）**。

  **例外——長期結構已完全破壞者不受保護**：`ma240Confirmation == BELOW` **且** `week52Position != null && week52Position <= WEEK52_BROKEN`（`= 0.10`）時，**照常輸出原本的減碼／出場**。
  這一條是必要的安全閥：沒有它，一檔持續崩壞的股票會因為 KD 永遠釘在低檔而**永遠拿不到出場訊號**。有了它，「大盤急跌造成的個股錯殺（個股仍在年線附近或之上）」被保護，「跌破年線且位於 52 週最低 10% 區的真實崩壞」仍會出場。判準與 `TRIAL_BUY` 的年線結構要求同源。

- [x] **第 4 步（不變）**：其餘沿用既有 `score` 分層與 `buyGate`。`buyGate` 的既有**五個**條件（大盤允許、MA20 `ABOVE`、MA60 `ABOVE`、KD 未過熱、換匯未過貴）**全部不放寬**；264.7 另加**第六個**（ETF 溢價 ≥ 3.0%）。

- [x] **第 2、3 步的覆寫必須互斥**：`EXTREME_OVERBOUGHT` 與 `EXTREME_OVERSOLD` 依 264.3 判定順序不可能同時成立，此性質須有測試釘住。

### 264.5 `TRIAL_BUY` 移除 `RISK_OFF` 封鎖（需求「急跌時應建議買入」）

- [x] `qualifiesForTrialBuy()` 條件 6 由
  `!equityMarketApplies(input) || (marketRegime != RISK_OFF && !marketStale)`
  改為
  `!equityMarketApplies(input) || !marketStale`。
- [x] **`marketStale` 封鎖保留**：stale 是「資料不新鮮」的技術狀態，不是市場判斷，在看不見大盤真實狀況時不得放行進場。
- [x] 其餘五項條件（年線乖離 `≥ 5%`、MA240 兩日 `ABOVE`、`K<20 且 D<20`、真交叉、完成日 K 止跌）**全部不放寬**。保留年線條件正是本次放寬的安全邊界：**「大盤急跌 ＋ 個股長線結構完好」＝ 錯殺 ＝ 買點；「個股自身跌破年線」＝ 長線結構已壞 ＝ 不主動接刀。**
- [x] `RISK_OFF` 下成立的 `TRIAL_BUY`，其 `risks` **必含**既有的「小額分批」文案與「大盤仍為 RISK_OFF」揭露（`evaluateCounterTrend()` `:303-305` 已有等價文案，須確保主動作路徑也輸出）。

### 264.6 因子權重重配（需求「數周至兩年、非極短線」）

- [x] 新增兩個因子並重配全部權重，總和維持 `1.00`（全部為具名常數）：

  | 尺度 | 因子 | 舊 | 新 | 適用 |
  |---|---|---|---|---|
  | 短期 | MA20 位置 | 0.10 | **0.06** | 全部 |
  | 短期 | MA20 兩日確認 | 0.06 | **0.05** | 全部 |
  | 短期 | KD 動能 | 0.08 | **0.05** | 全部 |
  | 短期 | KD 位置 | 0.13 | **0.07** | 全部（窄幅時 `null`） |
  | 中期 | MA60 位置 | 0.12 | **0.10** | 全部 |
  | 中期 | MA60 兩日確認 | 0.08 | **0.07** | 全部 |
  | 中期 | **季線乖離（新增）** | — | **0.12** | 全部 |
  | 長期 | MA240 位置 | 0.15 | **0.11** | 全部 |
  | 長期 | MA240 兩日確認 | 0.10 | **0.09** | 全部 |
  | 長期 | **52 週相對位置（新增）** | — | **0.07** | 全部 |
  | 環境 | 大盤 regime | 0.08 | **0.06** | 非債券 |
  | 環境 | 單日漲跌幅 | 0.05 | **0.04** | 全部 |
  | 環境 | 匯率分位 | 0.05 | **0.05** | 外幣計價底層 |
  | 估值 | **ETF 折溢價分位（新增）** | — | **0.06** | 僅 ETF |
  | | **合計** | 1.00 | **1.00** | |

  尺度佔比由「短期 0.37／中期 0.20／長期 0.25／環境 0.18」變為「短期 **0.23**／中期 **0.29**／長期 **0.27**／環境 **0.15**／估值 **0.06**」。中期（數周至數月）成為最大組，符合使用者的持有期。

  > **本表與 Requirement 43 修訂（`TW_RULES_V9`）AC 中的權重表為同一份，該 AC 為唯一契約**；兩處若不一致，以 requirements.md 為準並回頭修正本表。

  > **t267 會再次重配全部權重以納入基本面組**（僅適用於個股，ETF 恆 `null`）。屆時「ETF 得折溢價因子、個股得基本面因子」形成互補配對，兩者皆由 `Accumulator` 的缺值重分配處理。**本任務不預留基本面的權重位置**——預留等於在資料不存在時就固定了尚未驗證的權重，t223 的教訓正是如此（權重定義了兩年、因子始終回 `null`）。

- [x] **季線乖離因子（`W_EXTENSION = 0.12`）**：`contribution = clampUnit(−ma60BiasPercent / BIAS_SATURATION)`，`BIAS_SATURATION`（具名常數 `= 25.0`）。即乖離 `+25%` → `−1`（過度延伸），`−25%` → `+1`（過度超跌，均值回歸機會），之間線性。`ma60BiasPercent` 為 `null` 時回 `null`。**這是本任務對「不追高殺低」的評分層修正**——與 264.4 的動作層覆寫互補：評分層讓拉伸標的分數自然下降，動作層在極端時直接改變動作。
- [x] **52 週相對位置因子（`W_52W_POS = 0.07`）**：`contribution = (week52Position − 0.5) × 2`，`null` 時回 `null`。此因子刻意**保持正向**（接近 52 週高 → 正貢獻）：3–12 個月尺度的動能是長期趨勢品質的訊號，與「不追高」不衝突——追高的抑制由季線乖離因子與 264.4 的時機覆寫負責。**兩者正交：52 週位置量的是「長期趨勢好不好」，季線乖離量的是「現在進場貴不貴」。**
- [x] **`evaluateMarket()` 移除大盤單日漲逾 3% 的 `+3` 加分**（`:213-216`），改為僅寫入 `risks` 揭露追價風險、不加分。單日跌逾 3% 的 `−10` 不動。

### 264.7 ETF 折溢價（使用者追加需求）

ETF 的市價可偏離淨值。**溢價買進＝為同一籃資產多付錢**，這與「不追高」是同一件事的另一個面向；折價則是相對划算的進場點。台灣 ETF 有過溢價急速拉高後崩回的實例，故除了評分因子外另設硬否決。

- [x] **資料來源（資料本身皆為既有，但 business 端的讀取管道要新建）**：即時值走**既有**的 `PriceQueryService.getEtfNav(code, market)`（`:112`，Redis `price:etfnav:{market}:{code}`，`EtfNav.premiumDiscountPct()`）。**business-services 不得直連外部行情**（既有鐵則），本任務只讀 Redis 與 PostgreSQL。
- [x] **⚠ 歷史分位需要新增 business 端的讀取管道**：`grep -ran "etf_nav" backend/src/main` 只命中兩支 changelog，**backend 沒有 `EtfNavHistory` entity、沒有 repository、沒有任何查詢**——現有讀寫全在 `external-materials-service`（`StockSourceQuery.java:385-407`、`EtfNavPoller`）。故本任務須新增 `EtfNavHistory` entity ＋ `EtfNavHistoryRepository`（或以 `JdbcTemplate` 查詢，比照既有慣例），供 250 日分位計算使用。該表**無 `owner_user_id`**（全域公開行情），不套 `@Filter`。
- [x] **⚠ 分位因子的可用時程須揭露（不得宣稱上線即完整生效）**：`etf_nav_history` 建表於 **2026-07-19**（`v1.65.0`），且 `v1.82.0` 註解明載「不回填既有 186 列」，故截至本任務規劃時（2026-08-01）僅約 **10 個交易日**歷史。以 `ETF_PREMIUM_MIN_SAMPLES = 60` 計，**分位因子（權重 `0.06`）在上線後約 3 個月內對每一檔 ETF 恆為 `null`**，由權重重分配吸收。
  **絕對硬否決（`>= 3.0%`）不受此限、即刻生效**——它只需要當下的折溢價值，不需要歷史。
  此揭露為必要：本任務自己以「預留等於在資料不存在時就固定了尚未驗證的權重」為由拒絕預留基本面權重（見 264.6 註），若對折溢價不做同樣揭露即為雙重標準。前端文案須能表達「折溢價分位資料累積中」。
- [x] **`etfPremiumPct`**：現行折溢價百分比（`1.2` = 溢價 1.2%）。Redis 有值**且 `navAsOf` 為最近一個交易日**時優先，否則退回 `etf_nav_history` 最新一筆。**非 ETF 或查無 → `null`**。
  **必須驗證 `navAsOf` 新鮮度**：`price:etfnav:{market}:{code}` 的 TTL 為 96 小時（`EtfNavCacheWriter.java:20`），不驗日期會讓最多 4 天前的折溢價觸發下方的硬否決。本專案剛為同類問題做過修正（大盤即時點位的日期驗證，commit `5554ddb1`），不得重蹈。
  **禁止由市價與淨值反推**：t259 已確立此鐵則（台股該值為證交所發布的權威數字，且股票型 ETF 的淨值欄僅四捨五入至小數 2 位，反推誤差達 0.07 個百分點）。`premium_discount_pct` 為 `null` 就是缺值，須回 `null` 而非計算。
- [x] **`etfPremiumPercentile`**：現行折溢價在該 ETF **自身歷史**折溢價分布中的百分位（0–100），回看窗 `ETF_PREMIUM_LOOKBACK_DAYS`（具名常數 `= 250`，約一年）。樣本數不足 `ETF_PREMIUM_MIN_SAMPLES`（具名常數 `= 60`）時回 `null`。
  **必須用自身歷史分位而非絕對值**：不同 ETF 的常態折溢價水準差異極大（台灣債券 ETF 長期存在結構性溢價），用同一組絕對門檻套全部 ETF 會系統性誤判。此設計與既有 `fxPercentile()`（`TradingRadarService.java:496`）同源，實作應共用同一套分位計算。
- [x] **評分因子（`W_ETF_PREMIUM = 0.06`）**：`contribution = clampUnit(−(percentile − 50) / 50)`，與 `fxContribution()`（`TradingRadarRuleEngine.java:499-511`）完全同形。`null` 時回 `null`，由權重重分配吸收（個股與查無歷史的 ETF 皆走此路徑）。
- [x] **硬否決（對稱於既有 `fxExpensive`）**：`etfPremiumPct != null && etfPremiumPct >= ETF_PREMIUM_EXPENSIVE`（具名常數 `= 3.0`，即溢價 3% 以上）時**關閉 `buyGate`**，且 `TRIAL_BUY` 亦不得成立。
  **絕對門檻與分位門檻並存是刻意的**：分位管「相對自己貴不貴」，絕對門檻管「無論歷史如何，溢價 3% 買進就是為同一籃資產多付 3%」。只用分位會讓一檔長期高溢價的 ETF 因為「現在只是它的中位數」而放行。
- [x] **併入 `TimingState`**：`etfPremiumPct >= ETF_PREMIUM_EXPENSIVE` 時，264.3 的 `OVERBOUGHT` 條件增列一個 `或` 分支；`EXTREME_OVERBOUGHT` 條件**不納入折溢價**（避免僅憑溢價就觸發減碼覆寫——溢價會隨市場情緒在一天內收斂，而減碼是不可逆的建議）。
- [x] **文案**：溢價偏高時 `risks` 明示「市價高於淨值 X%，等於為同一籃資產多付溢價」；折價時 `reasons` 明示「市價低於淨值」。**不得把門檻數字寫進句子**（沿用 264.9）。

### 264.8 DTO、前端與版本

- [x] `StockResult` 增 `TimingState timingState`；`TradingRadarDto.StockDecision` 增 `timingState`／`timingLabel`／`ma60BiasPercent`／`week52Position`／`etfPremiumPct`／`etfPremiumPercentile` 六欄，供前端在收合列即可辨識時機狀態（比照 Task 232 `kdHeat` 的既有作法——收合列看不到 `reasons`／`risks`）。
- [x] `TradingRadarView.vue` 於個股表新增「時機」欄：`EXTREME_OVERBOUGHT` danger／`OVERBOUGHT` warning／`NEUTRAL` info(—)／`OVERSOLD` warning／`EXTREME_OVERSOLD` danger；展開列顯示季線乖離、52 週位置與 ETF 折溢價（含分位；非 ETF 該列不顯示，不得顯示為 0）。
- [x] **`RULE_VERSION` 由 `TW_RULES_V8` 升為 `TW_RULES_V9`**。**同步點共五處**（`grep -ran "TW_RULES_V8"`）：`TradingRadarRuleEngine.java:19`（本體）、`frontend/src/views/TradingRadarView.vue:28`（`radar` ref 初始值）、`:586`（顯示 fallback）、`backend/.../dto/TradingRadarDto.java:9`（javadoc）、`backend/src/test/.../TradingRadarRuleEngineTest.java:488`（**方法名 `ruleVersion_isV8` 亦須改為 `ruleVersion_isV9`**）。**本次必須揭露不可比性**：與 Task 228／232／263 三次「因子組成、權重與正規化方式完全相同」的升版不同，本次**新增兩個因子、重配全部權重、並改變動作映射結構**，V9 分數與 V8 分數不可直接比較。
- [x] **Requirement 44 的通知基準須全部重建**：動作映射結構改變會使既有 `trading_radar_notification_state.last_action` 與 V9 動作直接比對而觸發大量假通知。升級後**首輪評估一律只建基準不寄信**（沿用 t223 已定義的同一機制）。
- [x] `TradingRadarExportService` 的 Excel 欄位同步（新增四欄），`TradingRadarSnapshotStore` 的 Redis 快照序列化相容性須確認（舊快照缺新欄位時反序列化不得拋例外）。

### 264.9 文案約束（沿用本專案既有紀律）

- [x] **不得宣稱能預測隔日漲跌。** 使用者需求原文為「事先看出隔日將大幅反彈／隔日下殺風險很高」，但本功能是規則式系統、無回測支撐的預測能力。所有文案只能陳述**當前位置與狀態**（「已達極端超買且 KD 高檔轉弱」「已深度超跌，此位置不建議追殺出場」），**不得出現「預期隔日反彈」「明日將下跌」等預測性語句**。此約束與既有 `KD_OVERHEAT_K` 註解「不得於任何文案宣稱本門檻能降低回檔風險」同源。
- [x] **門檻數字不得寫進句子**（沿用 `describeHeat()` `:433-439` 的既有約束）：條件為嚴格比較而顯示值四捨五入，會產生自我否定的句子。

---

## 驗證

- [x] **不變量**：`score ∈ [0,100]` 恆成立（全部因子 `+1`、全部 `−1`、以及新增兩因子的極值組合）；新權重總和為 `1.00`（以斷言釘住，不靠人工加總）。
- [x] **`week52Position` 創新高路徑**：`price > high52` 時 clamp 後貢獻為 `+1`，不得 > `+1`（專屬測試，平時測不到）。
- [x] **需求對應的四條行為測試（本任務的核心驗收）**：
  1. **高點應賣出**：站上全部均線、`ma60Bias = +25%`、**`K=88／D=90`**、`previousK=92／previousD=90`→ 死叉成立（前期 `K>D`、本期 `k <= d`），`kdHeatOf` 仍為 `OVERHEATED`（`avg=89 > 80`）→ 動作為 `REDUCE_CANDIDATE`（已持有）／`AVOID`（未持有）。
     對照組：同一輸入但 **`previousK=88／previousD=90`**（前期已在 D 之下，無死叉）→ 不得為減碼（維持 `HOLD`／`WATCH`）。
     **⚠ 本 fixture 必須讓 `k <= d` 成立。** 直覺上會寫成「K 高於 D 的高檔」如 `K=90／D=88`，但那使死叉條件 `k <= d` 為 false、覆寫不觸發，且對照組會得到與實驗組相同的結果，測試退化為恆真而無鑑別力。
  2. **不殺低**：三均線位置皆 `−1`、三項確認皆 `BELOW`、`ma60Bias = −25%`、`K=8／D=12`、`week52Position = 0.20`、`marketRegime = RISK_OFF`、`changePercent = −6%`、`ma240Confirmation = BELOW`→ 分數落在減碼／出場區，但因 `EXTREME_OVERSOLD` 且 `week52Position > 0.10` 而降級為 `HOLD_CAUTION`（已持有）／`WAIT`。
  3. **崩壞股仍出場**：同 2 但 **`week52Position = 0.05`**（≤ `WEEK52_BROKEN`）→ **不降級**，維持分數映射的原始輸出。
     **⚠ 這兩例的期望動作不得寫死為 `EXIT_CANDIDATE`，須依實算分數決定。** 本任務新增的季線乖離因子在深度超跌時給 **正**貢獻（`ma60Bias = −25%` → `+1 × 0.12`），會把分數往上推；實算「三均線位置 −1、三確認 −1、乖離 +1、KD 動能 −0.4、KD 位置 +0.8、52 週 −0.9、大盤 −1、單日 −1」得 `Σ ≈ −0.487`／`ΣW = 0.89`／`σ ≈ −0.547` → `score ≈ 23`（`EXIT_CANDIDATE` 區）；若少給 `marketRegime` 或 `changePercent` 其中之一，分數會升到 28 附近而落入 `REDUCE_CANDIDATE` 區。**撰寫測試時須先實算分數、再斷言降級前後的動作**，不得憑「應該很低」臆測區間。
  4. **急跌可買**：`marketRegime = RISK_OFF`、`marketStale = false`、年線乖離 `+8%`、`ma240Confirmation = ABOVE`、`K=15/D=18`、`previousK=14/previousD=19`、完成日 K 漲跌 `+0.5%` → `TRIAL_BUY`。同一輸入但 `marketStale = true` → 不得為 `TRIAL_BUY`。
- [x] **窄幅 KD 失效**：`kdBandWidthPercent = 1.0` 且 `K=90/D=88` → `kdHeat` 為 `NORMAL`、`TimingState` 不得為 `EXTREME_OVERBOUGHT`（只能由乖離決定）、KD 位置因子為 `null` 且權重已重分配（以生效權重總和斷言）。
- [x] **既有行為迴歸**：`TradingRadarRuleEngineTest` 既有的 period+1 邊界、`ABOVE`／`BELOW`／`MIXED`／`UNAVAILABLE`、買進門檻與 `RISK_OFF` veto、held action mapping、incomplete veto 全數仍通過。**既有測試 `:388-398`（00882 得 83 分 `ADD_CANDIDATE`）與 `:405-412`（85 分 `HOLD`）的期望值會因權重重配而改變，須依新權重重算後更新，並在完成報告中列出新舊對照**——不得為了讓舊測試通過而回頭改權重。
- [x] **`TradingRadarServiceOwnerScopeTest`／`TradingRadarMarketFreshnessTest`／`TradingRadarSnapshotStoreTest`／`TradingRadarExportServiceTest`** 全數通過（DTO 增欄的連鎖影響）。
- [x] **完成報告須附**：使用者實際持股與觀察標的在 V8 與 V9 下的分數／動作／`TimingState` 對照表，並標出動作改變的標的與改變原因。

## 完成報告

**實際改了哪些檔**

| 檔案 | 改了什麼 |
|---|---|
| `backend/.../service/TradingRadarRuleEngine.java` | `RULE_VERSION` → `TW_RULES_V9`；11 → 14 個因子並重配全部權重（`WEIGHT_SUM` 常數供斷言）；新增 `TimingState` 五態與 `timingOf()`／`kdOversold()`／`kdDeadCross()`／`narrowKdBand()`／`longTermBroken()`；`extensionOf()`／`week52Of()`／`etfPremiumContribution()` 三個新因子；`actionFor()` 改為二維（高檔死叉→減碼、極端超賣→阻擋出場）；`TRIAL_BUY` 移除 `RISK_OFF` 封鎖、`TRIAL_BUY_MIN_ANNUAL_PREMIUM` 由 `0.05` 改為 `5.0` 並改讀 `ma240BiasPercent`；`evaluateMarket` 移除大盤單日暴漲 `+3` |
| `backend/.../service/TradingRadarService.java` | `TechnicalData` 增 52 週高低與 9 日帶寬；新增 `biasPercent()`／`week52Position()`（含 clamp）／`maxHigh()`／`minLow()`／`bandWidthPercent()`／`etfPremiumPct()`（含 `navAsOf` 新鮮度驗證）／`etfPremiumPercentile()`／`timingLabel()` |
| `backend/.../model/EtfNavHistory.java`、`repository/EtfNavHistoryRepository.java` | **新檔**：business 端的 ETF 折溢價歷史讀取路徑（原本只存在於 external-materials-service） |
| `backend/.../dto/TradingRadarDto.java` | `StockDecision` 增 `timingState`／`timingLabel`／`ma60BiasPercent`／`week52Position`／`weeklyMa`／`etfPremiumPct`／`etfPremiumPercentile` |
| `backend/.../service/TradingRadarExportService.java` | Excel 增「時機」「季線乖離%」「52週位置」「折溢價%」四欄 |
| `backend/.../model/TradingRadarNotificationSetting.java`、`service/TradingRadarNotificationService.java`、`db/changelog/changes/v1.83.0-radar-notification-rule-version.sql` | Requirement 44 通知基準重建：新增 `rule_version` 欄，與現行 `RULE_VERSION` 不符即視同未初始化 |
| `frontend/src/views/TradingRadarView.vue` | 新增「時機」欄（含 tooltip）；展開列顯示季線乖離、52 週位置、ETF 折溢價與分位；版本 fallback → V9 |

**既有測試期望值的新舊對照（權重重配的必然結果，非為了讓測試通過而改權重）**

| 測試 | V8 | V9 | 說明 |
|---|---|---|---|
| `kdHeat_userReportedCase_...`（00882） | 83 | **86** | 權重重配 |
| `kdHeat_kAloneOverheated_...` | 85 | **87** | 權重重配；仍為 `HOLD`，「過熱不扣分」語意不變 |
| `bondDoesNotUseEquityRiskOffPenaltyOrBuyGate`（equity） | 82 | **84** | 權重重配 |
| 同上（bond） | 90 | **91** | 權重重配 |
| `oversoldWatch_keepsOriginal009804ScoreAndAction` | 37 | **41** | 權重重配 ＋ 季線乖離 −7.9% 給正貢獻；動作由 `REDUCE_CANDIDATE` 降為 `HOLD_CAUTION`——正是「不殺低」的方向 |
| `trialCandidate_requiresRealLowGoldenCross...` | `HOLD_CAUTION` | **`TRIAL_BUY`** | `RISK_OFF` 封鎖移除後六項條件全數成立 |
| `trialBuy_blockedByRiskOffAndStale...` | 兩者皆擋 | **改名為 `..._isAllowedUnderRiskOffButStillBlockedWhenMarketDataIsStale`**：`RISK_OFF` 放行、stale 仍擋 | 需求 5 |

**驗證輸出**

- `TradingRadarRuleEngineTest`：46 個測試通過，含 9 條新增的需求驗收測試（高檔死叉→減碼／無死叉→維持、極端超賣→阻擋出場、崩壞股→照常出場、乖離因子抑制追高、窄幅 KD 全面失效、ETF 溢價硬否決、52 週 clamp、權重總和 1.00）。
- backend 全套 **402** 個測試通過；bff **18** 個測試通過。

**與原計畫的偏差**

1. **`WEIGHT_SUM` 以常數相加而非執行期加總**，供 `weightsSumToExactlyOne` 斷言，符合 spec「不靠人工加總」的意圖。
2. **`maxHigh()`／`minLow()` 在高低價為 null 時退回 `closePrice`**，spec 264.1 原文為「須跳過 null」。改為以收盤補位的理由：整列丟掉會讓 52 週區間在資料不全時失真，用收盤補位較保守。**此為對 spec 的偏離，記錄於此。**
3. **`etfPremiumPercentile()` 未與 `fxPercentile()` 抽共用方法**（spec 264.7 建議「實作應共用同一套分位計算」）。兩者的取樣來源與缺值條件不同（一個查匯率表取中價、一個查 ETF 表取折溢價），目前各自 inline。**屬未完成項，留待後續重構。**

**尚未完成（明確列出，不當作已做）**

- **未部署驗證**：本次只到「編譯 ＋ 單元測試通過」。依專案規範「改好」＝ image rebuild ＋ container recreate，尚未執行。
- **使用者實際持股與觀察標的的 V8／V9 分數對照表**：需連線實際 DB 產出，尚未取得。
- **前端未經 build 驗證**：worktree 無 `node_modules`，`StockAnalysisDialog.vue` 與 `TradingRadarView.vue` 的改動僅經人工檢視，須於 Docker build 時確認。
