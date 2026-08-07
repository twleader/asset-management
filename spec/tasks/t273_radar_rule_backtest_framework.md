# [t273] 交易雷達規則回測框架——讓門檻有實證依據而不是憑直覺

**對應 Requirements:** Requirement 56（交易雷達規則回測框架——量測每條規則述詞在 5／20／60／240 交易日後的前瞻報酬分布並與同標的同期間基準比較，只建工具不改行為）
**前置任務:** 無
**後續任務:** t291（取代 t276／t277：完整指標、量能與短／中期雙軌）、t274（波動度正規化門檻）、t275（債券 ETF 的利率因子）——三者的門檻、因子形式與權重須引用本任務的輸出
**Liquibase changeset:** 無（不新增資料表。只讀既有 `stock_price_history`、`stock_dividend_history`、`twse_index_daily_history`、`exchange_rate_history`、`etf_nav_history`——完整清單與各自的可得性見 273.1）

## 背景

### 問題

`TradingRadarRuleEngine`（`backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java`）現行 `RULE_VERSION = "TW_RULES_V9"`，其全部門檻常數皆無量測依據：

| 常數 | 現值 | 用途 |
|---|---|---|
| `BIAS_EXTREME_HIGH` / `BIAS_EXTREME_LOW` | `+20.0` / `−20.0` | 極端超買／超賣的季線乖離門檻（%） |
| `BIAS_HIGH` / `BIAS_LOW` | `+12.0` / `−12.0` | 一般超買／超賣的季線乖離門檻（%） |
| `BIAS_SATURATION` | `25.0` | 季線乖離貢獻的飽和點（%） |
| `KD_OVERHEAT_AVG` | `80.0` | KD 均值過熱，否決買進 |
| `KD_OVERHEAT_K` | `85.0` | K 單獨過熱，否決買進 |
| `KD_ELEVATED_K` / `KD_ELEVATED_AVG` | `80.0` / `70.0` | 偏熱（純揭露） |
| `KD_OVERSOLD_AVG` / `KD_OVERSOLD_K` | `20.0` / `15.0` | 極端超賣 |
| `WEEK52_BROKEN` | `0.10` | 長期結構破壞（不受「超跌不出場」保護） |
| `KD_BAND_MIN_PERCENT` | `2.0` | 9 日高低帶過窄，KD 失效 |
| `FX_EXPENSIVE_PCT` | `90.0` | 換匯過貴，否決買進 |
| `ETF_PREMIUM_EXPENSIVE` | `3.0` | ETF 溢價過高，否決買進 |
| `TRIAL_BUY_MIN_ANNUAL_PREMIUM` | `5.0` | 試單的年線乖離下限（%） |
| `TRIAL_BUY_KD_OVERSOLD` | `20.0` | 試單的 KD 超賣門檻 |

其中只有 `KD_OVERHEAT_K` 有一次一次性量測，且其 Javadoc 明載：

> **此門檻沒有回測依據，是刻意的風險偏好取捨。** 十年台股 37,720 個買進閘門成立樣本顯示，過熱組的後續下檔風險反而低於正常放行組（20 日內跌逾 10% 的比例：K>85 組 10.0%、正常放行組 15.9%）。採納理由是使用者不願在單一指標極端超買時收到加碼建議。取 85 而非 80 的依據是受影響樣本量（0.63% vs 7.10%），即盡量縮小影響面。**不得於任何文案宣稱本門檻能降低回檔風險。**

該次量測是一次性的、沒有留下可重跑的工具，其餘十餘個門檻至今全憑直覺。

### 為什麼現在做得到

兩個條件已經成立，只差有人把它們接起來：

1. **`TradingRadarRuleEngine` 是純函數。** 該檔的 `import` 只有 `org.springframework.stereotype.Component`、`java.math.BigDecimal`、`java.math.RoundingMode`、`java.util.ArrayList`、`java.util.List`——不碰 repository、不碰網路、不讀系統時間。同一組輸入恆得同一組輸出。
2. **歷史資料足夠。** `stock_price_history` 實測（2026-08-01）共 **154,834 筆**、**71 檔**標的、涵蓋 **2016-08-01 ~ 2026-07-31**。**各標的的交易日數自 318 至 2439 不等**，49 檔中有 12 檔不足 2400 筆——最少者 009804 318、00929 763、00919 915、00882 1330、00881 1369、00878 1469、7556 1629、00850 1686、00713 2153，債券 ETF 為 1624–2323（00679B 2323、00695B 2230、00697B 2220、00719B 2064、00751B 1903、00865B 1624）。**扣掉 240 筆暖機後，最短者只剩約 78 個可用交易日**，故輸出必須逐標的揭露樣本數，且 `n < 30` 的格子須標記為樣本不足。完整分布見驗收查詢。`volume` 欄位 **null 為 0 筆**（僅 229 筆為 0）。

故回測只需「把歷史序列逐日切片餵給同一支引擎」，不需要模擬器，也**不需要第二份規則實作**。

### 本任務的邊界

**只建工具，不改行為。** 不得修改 `TradingRadarRuleEngine` 的任何門檻常數、權重、因子組成、動作映射或 `RULE_VERSION`；不得改動 `TradingRadarService` 的組裝結果、BFF 回傳欄位或前端畫面。使用者在「今日交易雷達」頁看到的內容必須逐字不變。

## 要做什麼

### 273.1 建立 `BacktestService`（business-services，`backend/src/main/java/com/steven/assets/service/`）

- [ ] 273.1 新增 `BacktestService`，`@Service`、`@Transactional(readOnly = true)`。輸入為「標的清單（可選，預設全部台股）」與「日期區間（可選，預設全部）」，輸出為結構化統計結果。

  **資料來源**（只讀，不寫）：
  - `StockPriceHistoryRepository`：逐標的取全序列。**現有的 `findRecentN(code, market, n)` 回傳的是降序（新到舊）的最近 N 筆**，回測需要全序列且要能逐日切片，須確認既有 repository 是否已有合適方法，沒有則新增一支 `findAllByStockCodeAndMarketOrderByTradingDateAsc`。
  - `StockDividendHistoryRepository` ＋ `DistributionAdjustedPriceService`：還原權息與還原分割（見 273.3 的前視偏誤約束）。
  - `ExchangeRateHistoryRepository`：`fxPercentile` 的來源。**十年完整**（實測 USD 2489 筆、ZAR 1774 筆，皆涵蓋 2016-08-01 ~ 2026-07-31），可重建；但**須以「截至 `t` 的五年視窗」重算分位**，不得沿用 production 以 `LocalDate.now(TAIPEI)` 為錨的算法——那是前視偏誤。
  - `EtfNavHistoryRepository`：`etfPremiumPct`／`etfPremiumPercentile` 的來源。**結構性缺值，見下方警告。**

  > **⚠ ETF 折溢價在回測期間幾乎不存在，必須揭露而不是靜默以 `null` 帶過。** `etf_nav_history` 實測僅 **209 筆／11 個交易日／19 檔**（2026-07-17 ~ 2026-07-31）——十年 2400+ 個交易日中約 **0.5%** 有值。而該欄在 production 是**硬否決**（溢價 `>= 3%` 直接關閉 `buyGate`，並參與 `OVERBOUGHT` 判定）。回測等於量到「折溢價否決永不成立」的**另一套規則**。
  >
  > 故本任務必須：(a) 輸出**逐標的、逐 horizon 標記「ETF 折溢價欄位可得的天數」**；(b) 述詞 1 的 `OVERBOUGHT` 與述詞 6 的 `buyGate` 對 **ETF 標的**額外標記「本次量測未含折溢價否決」；(c) **禁止**把 ETF 的 `buyGate` 統計直接當成 production 行為的證據供 t291／t274／**t275** 引用。**t275 尤其要注意**——它的評分對象 100% 是 ETF。
  - `TwseIndexDailyHistoryRepository`：大盤 regime 的逐日輸入（`0000` 的資料在 `stock_price_history` 只有 9 筆，實測範圍 2026-07-21 ~ 2026-07-31，那是 Task 263 的盤中 watchlist 用途，**不足以支撐十年回測；大盤歷史一律讀 `twse_index_daily_history`**）。

  **標的範圍**：預設 `stock_price_history` 中 `market = '台股'` 且 `stock_code <> '0000'` 的全部標的（實測 49 檔）。`0000` 只作為大盤 regime 的輸入來源，不作為被評分的標的——這與 `TradingRadarService.assemble()` 既有的 `.filter(t -> TW_MARKET.equals(t.market()) && !TAIEX_CODE.equals(t.code()))` 一致。

### 273.2 述詞判定必須複用引擎，不得複製一份

- [ ] 273.2 回測的述詞判定**一律透過 `TradingRadarRuleEngine` 本身**取得，不得在 `BacktestService` 內複製一份 `timingOf()`／`actionFor()`／`kdHeatOf()` 的邏輯。

  引擎目前的 `evaluateStock(StockInput)` 回傳 `StockResult`，其中已含 `score`、`action`、`kdHeat`、`timingState`、`counterTrend` — **這四項已足以判定本任務要求的全部述詞**，不需要新增引擎的 public API 來暴露內部條件。

  唯一需要額外資訊的是述詞 (2)(3)(4)（見 273.4），它們需要 `kdDeadCross()` 與 `longTermBroken()` 的結果。**採取的作法**：把這兩個判定的結果加進 `StockResult`（新增兩個 `boolean` 欄位），由引擎輸出。

  > **建構點實測（2026-08-01）**：`grep -ran "new StockResult" backend/src` 只有**引擎內兩處**——`TradingRadarRuleEngine.java:317`（`NO_TRADE` 早退分支）與 `:367`（正常路徑）。`TradingRadarService` 與全部既有測試**零建構點**（只用 accessor），故新增欄位不外溢。
  >
  > **`:317` 早退分支的兩個新欄位一律填 `false`**，並須有測試斷言之。資料不完整時不得宣稱任何條件成立——若實作者改為在該處呼叫 `kdDeadCross(input)`，在 `indicators` 為 `null` 時會取到「偶然的 `false`」，表面相同但語意不同（「無法判定」被寫成「判定為否」）。

### 273.2b `StockInput` 的組裝同樣不得複製（比 273.2 更重要）

- [ ] 273.2b 引擎是純函數、不會漂移；**真正會漂移的是 `TradingRadarService` 把 OHLC 序列變成 `StockInput` 的那一段**。該路徑帶了大量會改變判定的細節：241 筆取數視窗、先還原再算指標的順序、`completedCloses` 的 `subList` 位移、`indicatorRows` 的 240／241 分歧、52 週高低的缺值語意（`window.size() >= 240` 才給值）、`kdBandWidthPercent` 只取還原序列前 9 筆、`ruleChangePercent` 用還原前收。

  **須把該組裝抽為可重用的純函數**（輸入＝降序 OHLC 子序列 ＋ 除權息事件，輸出＝`StockInput` 的技術面欄位），**production 與回測共用同一支**，不得在 `BacktestService` 另寫一份。

  > **為什麼不能複製一份**：複製出來的第二份會隨 production 演進而漂移，屆時回測量的是一組**與線上不同的規則**，結論全部作廢且不會有任何報錯。此為本任務最重要的架構約束，並由驗證段 (g) 的端到端比對守門。

### 273.3 嚴禁前視偏誤（本任務正確性的核心）

- [ ] 273.3 第 `t` 日的述詞判定，其輸入**只能來自 `t` 日（含）以前的資料**。

  - [ ] 273.3.1 **均線、KD、乖離、52 週位置、確認狀態一律以「截至 `t` 日的子序列」重算**。不得取用整段序列的統計量（如全期最高價）。52 週相對位置的視窗為 `t` 往回 240 個交易日。

  - [ ] 273.3.2 **還原權息的錨點：經分析確認「不構成前視偏誤」，不需逐 `t` 重錨。**

    > **⚠ 本條的初版寫錯了，在此更正並保留推導，避免日後有人再照錯的版本實作。**
    >
    > `DistributionAdjustedPriceService` 的 back-adjustment 為 `adj[i] = raw[i] × shares[i] / shares_final`。若改為對每個 `t` 重新錨定，得 `adj_t[i] = raw[i] × shares[i] / shares[t]`。兩者在視窗內只差一個**與 `i` 無關的常數倍率** `shares_final / shares[t]`。
    >
    > 而引擎的**全部**價格導出輸入都是**尺度不變量**：`confirm()`（比較 price 與 MA）、`ma60BiasPercent` ＝ `(close−MA60)/MA60`、`week52Position` ＝ `(price−low)/(high−low)`、`kdBandWidthPercent` ＝ `(hi−lo)/lo`、KD 的 `rsv` ＝ `(close−LL)/(HH−LL)`、`changePercent` ＝ `close/prevClose−1`。乘上任何正的常數倍率，這些值**全部不變**。
    >
    > **結論**：直接切片整段還原序列**不會改變任何述詞判定**。**不得宣稱逐 `t` 重錨修正了前視偏誤**，也**不需要**為此付出 O(n²) 的重算成本。

    仍**建議**採「以固定基期為錨的正向還原」（把 `t=0` 當基準往後放大，只需掃一次），理由是**語意清楚**（`t` 日的值不依賴未來事件）而非正確性。若採此法，須有測試斷言兩種還原法算出的前瞻報酬相同（同一段含配息的序列，容差 `1e-9`）。

  - [ ] 273.3.3 **大盤 regime 同樣逐 `t` 重算**，不得用最新一日的 regime 套用到全部歷史日。`evaluateMarket(MarketInput)` 的輸入亦須以截至 `t` 的大盤序列產生。

  - [ ] 273.3.4 **此條必須有專門的測試守門**（見驗證段 (a)）。前視偏誤不會讓程式報錯，只會讓結果變好看——沒有探針就等於沒防護。

### 273.4 述詞清單（每條各自獨立統計）

- [ ] 273.4 至少涵蓋下列述詞，每條輸出其「成立日集合」的樣本數：

  | # | 述詞 | 判定來源 |
  |---|---|---|
  | 1 | `TimingState` 五態各自成立（五條） | `StockResult.timingState()` |
  | 2 | `EXTREME_OVERBOUGHT` 且 `kdDeadCross()`——現行「減碼覆寫」的完整觸發條件 | `timingState` ＋ 273.2 新增輸出 |
  | 3 | `EXTREME_OVERSOLD` 且非 `longTermBroken()` **且 `score < 40` 且非 `TRIAL_BUY`**——「阻擋出場」**實際改變動作**的那一組日子 | 同上 |
  | 4 | `EXTREME_OVERSOLD` 且 `longTermBroken()` **且 `score < 40`**——被排除保護的崩壞股 | 同上 |
  | 5 | `KdHeat.OVERHEATED` | `StockResult.kdHeat()` |
  | 6 | 買進閘門成立且 `score >= 75`（即 `action ∈ {BUY_CANDIDATE, ADD_CANDIDATE}`） | `StockResult.action()` |
  | 7 | `qualifiesForTrialBuy()`（即 `action == TRIAL_BUY`） | `StockResult.action()` |
  | 8 | 分數落在 `>=75`／`55–74`／`40–54`／`25–39`／`<25` 五個分層各自（五條） | `StockResult.score()` |

  > 述詞 2、3、4 是本任務最重要的三條——它們是使用者明確要求驗證的對象：「極端超買＋KD 死叉 → 減碼是否真的避開了下跌，還是砍在起漲點」「極端超賣 → 阻擋出場是否真的避免了殺低，還是抱著崩壞股不放」。

  **`held` 的處理**：`StockInput.held` 會改變 `action` 的值（持有／未持有映射到不同動作）。回測時**一律以 `held = true` 評估一次、`held = false` 評估一次**，兩組分開統計——否則述詞 6 的定義會不完整（`BUY_CANDIDATE` 與 `ADD_CANDIDATE` 分屬兩種 `held`）。

### 273.4b 述詞必須可參數化掃描（本任務的契約缺口，不補會讓四份下游全部卡住）

- [ ] 273.4b t291／t274／t275 都需要以 t273 量測門檻或規則，但 273.4 的 8 條述詞是**寫死的**，只能回答「現行門檻表現如何」，**回答不了「改成 X 會如何」**。本任務須額外提供：

  - [ ] **273.4b.1 門檻掃描**：對下列可調量接受**候選值清單**，每個候選值各跑一次完整統計並輸出可比較的表：
    - 季線乖離的 `σ` 倍數（t274 需要，先行量測顯示搜尋範圍在 9σ–15σ）
    - `σ` 的絕對下限（t274 需要）
    - KD 過熱／超賣門檻（現行 `80`／`85`／`20`／`15`）
    - `volumeRatio` 的爆量界線（t291 需要）
    - 利率敏感度的啟用門檻（t275 需要：係數符號與相關性下限）
  - [ ] **273.4b.2 述詞可組合**：允許以「基礎條件 ∧ 附加條件」臨時組出述詞（例如 `EXTREME_OVERSOLD ∧ score < 40`、`站上均線 ∧ volumeRatio > k`），不必為每個新問題改程式。
  - [ ] **273.4b.3 輸入／輸出契約須寫死在本檔**：`POST /internal/backtest/rules` 的 request body schema（`codes`／`from`／`to`／`horizons`／`thresholds`／`predicates`）與 CSV 欄位清單須明文定義。**下游 t291／t274／t275 要據此撰寫自己的驗收指令**；t291 使用 `{"horizons":[5,20,60,120]}`，不得靜默忽略 request。

  > **不做這一項的後果**：t273 做完後四份下游同時卡住，屆時得回頭改 t273，等於重做。**這必須在 t273 動工前定案。**

### 273.5 四個 horizon 與前瞻報酬

- [ ] 273.5 每條述詞在 `+5`／`+20`／`+60`／`+240` 個**交易日**（非日曆日，即序列的索引位移）後各計算一次前瞻報酬。

  - 訊號日 `t` 的 `h` 日前瞻報酬 ＝ `adjClose[t+h] / adjClose[t] − 1`，`adjClose` 為**還原權息且還原分割後**的收盤價。**前瞻報酬一律取自全序列的還原價**（含 `t+h`）——若 273.3.2 採了逐 `t` 子序列的作法，那份序列在結構上止於 `t`、不可能含 `adjClose[t+h]`，兩者刻意分開。錨點差異不影響報酬率（它是比值）。
  - **不得用原始收盤價**：現金配息與股票股利會讓除息日產生假跌幅；月配息債券 ETF 在 240 日視窗內尤其嚴重，用原始價會系統性低估其報酬。
  - `t+h` 超出序列尾端時，該樣本在該 horizon **不計入**（另計入 `insufficientForward` 計數並輸出），**不得以最後一日充數**，也**不得因此把整個訊號日丟棄**——它在較短的 horizon 仍然有效。
  - 四個 horizon **一律全部輸出**，不得只輸出其中一部分。既有完成報告保留 `5／20／60／240` 歷史基準；t291 另以 `5／20／60／120` 重跑，對應短期約一週與中期最長約六個月。

### 273.6 基準與統計量

- [ ] 273.6.1 **基準 ＝ 同標的、同期間、所有具備完整前瞻報酬的交易日**的報酬分布。規則的效果一律以「訊號組 vs 該基準」的差額表示。**不得只報訊號組的絕對報酬**——「20 日平均 +2%」在多頭十年裡毫無資訊量。

- [ ] 273.6.2 跨標的彙總時，須**同時**輸出兩種口徑並揭露兩者不一致的情形：
  - **逐標的配對比較**：每檔各自算「訊號組 − 基準」，再對各檔的差額取統計量；
  - **全樣本合併**：所有標的的訊號日合併成一組。

  合併口徑會被樣本數多的高波動標的主導（實測 2327 有 40.89% 的交易日 `|bias|>12%`，而 00697B 為 0.00%），兩者不一致是預期的，必須讓讀者看見。

- [ ] 273.6.3 每個 `(述詞, horizon)` 至少輸出：樣本數 `n`、平均值、中位數、勝率（報酬 `>0` 的比例）、**下檔風險**（報酬 `<= −10%` 的比例）、5／25／75／95 百分位，以及對應的基準值與差額。`n < 30` 時該格須明確標記為樣本不足。

- [ ] 273.6.4 **不得輸出 p 值、信賴區間或任何統計顯著性宣稱。** 本框架是單一市場、單一十年期、標的高度重疊的觀察性資料，且連續多日同一訊號成立造成嚴重自相關，古典檢定的前提不成立；報顯著性會給出虛假的確定感。

- [ ] 273.6.5 **暖機期的樣本一律排除。** 需要 240 根完成日 K 的因子（年線、年線確認、52 週位置）在序列前 240 筆不可得，這些日子 production 會回 `NO_TRADE`。一律排除於訊號組與基準之外，並輸出被排除的天數。**不得以 `null` 當中性值放行**——那會讓上市未滿一年的標的以殘缺條件進入統計。

### 273.7 執行方式

- [ ] 273.7 於 business-services **新建** controller 提供 `POST /internal/backtest/rules` 手動觸發端點，回傳 JSON 結果，並支援另存 CSV 供離線分析。

  > **⚠ 服務歸屬（初版寫錯，已更正）**：`InternalPriceController`（`/internal/dividend/sync` 所在）位於 **external-materials-service**，**不是** business 端的既有慣例。實測 `grep -ran 'RequestMapping("/internal' backend/src external-materials-service/src`：business 只有 `UserAdminController` 的 `/internal/users`。本端點是 business **第一支**這類 `/internal`，路由風格可比照 `InternalPriceController`，但**該類別不在本服務**。
  >
  > **授權立場必須二選一寫死**：(a) 納入 `AdminGateInterceptor` 的 `addPathPatterns`；或 (b) 刻意只靠「容器不對外映射 8080」（實測 `asset-business-services` 只有內部 `8080/tcp`，host 的 8080 是 `asset-bff`）。**這件事必須定案**，因為下方驗收指令是在容器內裸打、不帶任何 `X-User-*` header——若選 (a) 而未在指令補 header，驗收會失敗。

  **明確不得做的事**：
  - **不得**新增 `@Scheduled`。故「公開資訊 → 排程列表」（`SchedulePublicBffController` 的靜態 `JOBS` 清單）**不需新增項目**。
  - **不得**新增前端頁面或 BFF 端點，**不得**進入 `/api/bff/trading-radar` 的請求鏈。全市場十年逐日重算是分鐘級運算，掛進任何使用者請求路徑都會拖垮該頁。
  - 消費者是「調門檻時的維護者」，不是每日使用者。

- [ ] 273.7.1 輸出須含**資料期間、標的數、每標的樣本數**，使結果可被獨立複驗。同一輸入必須得到同一輸出（引擎為純函數、資料為歷史表，故可重現性成立）。

### 273.8 文案與後續使用的紀律

- [ ] 273.8.1 本框架的輸出一律表述為「歷史上此條件成立後的報酬分布」。**不得**於程式註解、API 回應、匯出檔或畫面文案出現「預測」「將會」「機率為」「能降低風險」等語句。此約束沿用 `KD_OVERHEAT_K` Javadoc 已建立的紀律並擴及本框架全部輸出。

- [ ] 273.8.2 **不得因回測結果自動改動任何門檻。** 本任務只產生數據。門檻的調整留給 t291／t274，且須引用本框架的輸出作為依據。**當回測結果與使用者已表明的方向衝突時，一律先呈現數據供使用者裁決，不得逕行改動**——`KD_OVERHEAT_K` 即為既有先例。

## 驗證

### 單元測試

> 本任務**新建** `BacktestService` 的單元測試，檔案置於 `backend/src/test/java/com/steven/assets/service/` 下，命名沿用本專案既有慣例（服務類名 ＋ `Test` 後綴）。下列各項為該測試檔須涵蓋的案例。

- [ ] **(a) 前視偏誤的探針（最重要的一條）**：構造一段人工序列，其中 `t` 日之後有一次大漲（例如 `t+1` 起連續數日 +8%）。斷言 `t` 日的述詞判定結果，與「把 `t` 之後的資料整段刪除後重算」**完全相同**（`timingState`、`action`、`kdHeat`、`score` 四項皆須相同）。序列中須包含至少一次現金配息事件，使還原路徑被涵蓋。

  > **斷言句的正確理由**：「若實作**取用了全期統計量**（例如拿全序列最高價當 52 週高點），此測試必失敗」。**不是**「誤用整段還原」——還原是尺度不變的（見 273.3.2 的推導），誤用它不會讓這條測試變紅。

- [ ] **(a2) 52 週視窗的直接探針**：斷言 `week52Position` 使用的 high／low 只取自 `[t−239, t]`，不是全序列的 max／min。這條才是 (a) 真正要守的東西。
- [ ] **(b) 還原權息的探針**：構造含一次現金配息的序列，斷言跨越除息日的前瞻報酬**不因除息缺口而變負**；另斷言無配息事件時還原序列與原始序列**逐筆相同**。
- [ ] **(c) 若 273.3.2 選了正向還原**：斷言正向還原與 back-adjustment 兩種算法在同一段含配息序列上算出的**前瞻報酬相同**（容差 1e-9）。
- [ ] **(d) `t+h` 越界**：斷言該樣本不計入該 horizon、計入 `insufficientForward`，但**仍計入較短的 horizon**。
- [ ] **(e) 暖機期**：不足 240 筆的日子不進入任何統計（訊號組與基準皆然），且被排除天數有被輸出。
- [ ] **(f) 基準與訊號組同源**：斷言某標的的訊號日集合是其基準日集合的**子集**。
- [ ] **(g) 端到端一致性（不得寫成同義反覆）**：**不可**讓引擎與回測吃**同一個**手工建構的 `StockInput` 再斷言結果相同——那恆成立、抓不到任何漂移。

  正確作法：取 `t` ＝ 最新交易日，讓回測路徑**從 `stock_price_history` 走完整組裝**產生 `StockInput`，斷言其與 `TradingRadarService` 當日實際送進引擎的 `StockInput` **逐欄相同**（至少 `ma20`／`ma60`／`ma240`／`k`／`d`／`previousK`／`previousD`／三個 `Confirmation`／`ma60BiasPercent`／`ma240BiasPercent`／`week52Position`／`kdBandWidthPercent`）。這條守的是 273.2b，不是 273.2。
- [ ] **(h) `held` 兩組分開**：同一天同一標的在 `held=true` 與 `held=false` 下的 `action` 不同（例如 `ADD_CANDIDATE` vs `BUY_CANDIDATE`），且兩組統計分別存在。
- [ ] **(i) 回歸——引擎未被改動**：`TradingRadarRuleEngineTest` 全數通過且 `RULE_VERSION` 仍為 `TW_RULES_V9`（本任務不升版）。

### 建置與部署

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

```bash
docker compose -p asset-management restart bff
```

> **`restart bff` 不可省略**：recreate `business-services` 會換 IP，BFF 握著舊 IP 會讓每個 `/api/**` 回 500，且因 Docker DNS TTL 600s 而 **3 分鐘以上不會自癒**；`business-services` 的 log 會是乾淨的，錯誤只出現在 bff log 的 Connection refused。

### 實跑驗收

```bash
curl -s http://localhost:8080/actuator/health
```

> **這行檢查的是 bff，不是 business。** `docker ps` 顯示 `asset-bff` 對外 `0.0.0.0:8080->8080`，而 `asset-business-services` 只有內部 `8080/tcp`。實測**只有 bff 有 actuator**——在 business 容器內打 `/actuator/health` 回 **500** `No static resource actuator/health.`。business 是否活著改以下面那條實際端點呼叫是否有回應來判斷。

```bash
docker exec asset-business-services curl -s -X POST "http://localhost:8080/internal/backtest/rules" -H 'Content-Type: application/json' -d '{}' | head -c 4000
```

實跑後須確認：

- [ ] 標的數為 **49**（`stock_price_history` 中 `market='台股'` 且 `stock_code<>'0000'` 的檔數；若與此數不符，先確認 DB 現況而非直接改期望值）；
- [ ] 資料期間為 **2016-08-01 ~ 2026-07-31**；
- [ ] 述詞 2（`EXTREME_OVERBOUGHT` 且 KD 死叉）與述詞 3（`EXTREME_OVERSOLD` 且非崩壞）在**債券 ETF（00679B／00697B／00719B／00751B／00865B）上的樣本數**須被明確輸出——依 t274 背景段落的十年實算，這幾檔的 `|bias|>20%` 觸發率為 0.00%~0.38%，故預期樣本數極少甚至為 0。**這個「幾乎為 0」本身就是 t274 要修的問題的證據，必須看得到，不得因為樣本不足而把該格從輸出中省略。**

驗收用的 DB 現況查詢（憑證取自專案根目錄 `.env` 的 `POSTGRES_USER`／`POSTGRES_PASSWORD`／`POSTGRES_DB`，實測值為 `assets`／`assets`）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(DISTINCT stock_code) FROM stock_price_history WHERE market='台股' AND stock_code<>'0000';"
```

## 完成報告

**實作日期：** 2026-08-02　**RULE_VERSION：** `TW_RULES_V9`（未升版，符合本任務邊界）

### 改了哪些檔

| 檔案 | 變更 |
|---|---|
| `backend/.../service/RadarInputAssembler.java` | **新增**。組裝的唯一擁有者（273.2b），production 與回測共用 |
| `backend/.../service/BacktestService.java` | **新增**。逐日切片 → 同一支 assembler → 同一支引擎 |
| `backend/.../dto/BacktestDto.java` | **新增**。273.4b.3 的輸入／輸出契約 |
| `backend/.../controller/InternalBacktestController.java` | **新增**。`POST /internal/backtest/rules`＋`/rules.csv` |
| `backend/.../service/TradingRadarRuleEngine.java` | `StockResult` 新增 `kdDeadCross`／`longTermBroken` 兩個 boolean（純輸出）；早退分支填 `false` |
| `backend/.../service/TradingRadarService.java` | 組裝改為委派 assembler；`price` 求值前移；移除已搬走的 5 個 helper |
| `backend/.../repository/StockPriceHistoryRepository.java` | 新增 `findAllByStockCodeAndMarketOrderByTradingDateAsc` |
| `backend/.../repository/EtfNavHistoryRepository.java` | 新增 `findByStockCodeAndMarketOrderByNavDateAsc` |
| `backend/src/test/.../BacktestServiceTest.java` | **新增** 14 個測試 |
| `TradingRadarServiceOwnerScopeTest`／`TradingRadarMarketFreshnessTest` | 建構子新增 assembler（用**真的** assembler 包同一組 mock，行為與抽取前相同） |

**測試：423 個全綠**（409 既有 ＋ 14 新增），`mvn -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test` → BUILD SUCCESS。

### 實跑驗收（2026-08-02，全市場）

| 項目 | 期望 | 實測 |
|---|---|---|
| 標的數 | 49 | **49** ✅ |
| 資料期間 | 2016-08-01 ~ 2026-07-31 | **相同** ✅ |
| 失敗標的 | — | **0 檔** |
| 執行時間 | 分鐘級 | **34 秒** |
| 髒列剔除 | 175 列 | **175 列**（006208 87、7556 67、00865B 6、3036 5、2327 4、其餘 6 檔各 1）✅ |
| 債券 ETF 折溢價可得天數 | 極少 | 五檔皆 **11 天**（十年 1378–2083 個評估日中） |

### 述詞 2／3／4 的完整統計（`held=true`，全樣本合併口徑，報酬單位 %）

**述詞 2　`REDUCE_OVERRIDE`（極端超買 ∧ KD 高檔死叉 → 減碼）**

| horizon | n | 平均 | 中位 | 勝率 | 跌逾 10% | 基準 n | 基準平均 | **差額** |
|---|---|---|---|---|---|---|---|---|
| 5 | 194 | 1.31 | 0.51 | 53.61% | 6.19% | 94060 | 0.44 | **+0.87** |
| 20 | 192 | 7.95 | 4.58 | 65.10% | 10.42% | 93325 | 1.83 | **+6.11** |
| 60 | 160 | 18.10 | 8.48 | 65.00% | 18.13% | 91365 | 5.71 | **+12.38** |
| 240 | 123 | 41.45 | 16.67 | 73.17% | 17.07% | 82707 | 22.89 | **+18.56** |

**述詞 3　`EXIT_BLOCK_EFFECTIVE`（極端超賣 ∧ 非崩壞 ∧ `score < 40` ∧ 非 TRIAL_BUY——保護實際改變動作的日子）**

| horizon | n | 平均 | 中位 | 勝率 | 跌逾 10% | 基準平均 | **差額** |
|---|---|---|---|---|---|---|---|
| 5 | 144 | 4.25 | 4.60 | 66.67% | 6.25% | 0.44 | **+3.81** |
| 20 | 144 | 11.45 | 11.94 | 72.92% | 13.89% | 1.83 | **+9.61** |
| 60 | 144 | 21.73 | 24.10 | 72.92% | 19.44% | 5.71 | **+16.02** |
| 240 | 144 | 71.23 | 49.76 | 70.83% | 19.44% | 22.89 | **+48.34** |

逐標的配對口徑（避免被高波動標的主導）：29 檔有訊號，h=240 的差額平均 **+50.50**、中位 **+33.14**，**24／29 檔為正**——與合併口徑同向。

**述詞 4　`EXIT_BLOCK_EXCLUDED`（極端超賣 ∧ 崩壞 ∧ `score < 40`——被排除保護、照常出場的日子）**

| horizon | n | 平均 | 中位 | 勝率 | 跌逾 10% | 基準平均 | **差額** |
|---|---|---|---|---|---|---|---|
| 5 | 249 | 4.04 | 3.74 | 64.66% | 3.21% | 0.44 | **+3.60** |
| 20 | 249 | 10.15 | 9.46 | 78.71% | 7.23% | 1.83 | **+8.31** |
| 60 | 249 | 16.64 | 14.00 | 73.90% | 8.43% | 5.71 | **+10.93** |
| 240 | 249 | 72.90 | 48.08 | **89.56%** | 5.22% | 22.89 | **+50.02** |

### 三個必須交給使用者裁決的觀察（本任務不得自行改門檻，273.8.2）

1. **「極端超買＋KD 死叉 → 減碼」在十年資料上是砍在起漲點。** 四個 horizon 的前瞻報酬**全部高於基準**，且差額隨 horizon 擴大（+0.87 → +18.56）。下檔風險確實同步升高（h=60 為 18.13% vs 基準 4.75%——高波動期的特徵），故不是「純粹有害」，但「避開下跌」這個目的**沒有在資料上得到支持**。
2. **「極端超賣 → 阻擋出場」在十年資料上有效。** 差額 +3.81 ~ +48.34，且逐標的配對口徑 24/29 檔為正——**這條保護值得保留**。
3. **⚠ 但「崩壞股不受保護」這個安全閥的方向可能相反。** 述詞 4 的表現**不比述詞 3 差**（h=240 勝率 89.56%、差額 +50.02，下檔風險反而更低 5.22% vs 19.44%）。也就是說：被判定為「長期結構破壞、照常出場」的那些日子，事後同樣大幅反彈。`longTermBroken()` 這個 carve-out **可能正在砍掉最好的買點**。

> **這三項一律只呈現數據，不自動改門檻。** 樣本為單一市場、單一十年（且是多頭十年）、連續多日同一訊號成立造成嚴重自相關，故本框架刻意不輸出 p 值與信賴區間。基準比較已部分吸收多頭偏誤，但無法完全消除。

### 與原計畫的偏差

| 項目 | 偏差 | 原因 |
|---|---|---|
| 273.3.2 的正向還原 | **未採用**，維持 back-adjustment | 該條已論證切片不構成前視偏誤（尺度不變量），正向還原只是語意偏好。驗證 (c) 隨之不適用，改以 (a) 的「截斷後重算結果相同」直接守門 |
| 授權立場 | 定案為**不納入 `AdminGateInterceptor`**，僅靠容器不對外映射 8080 | 端點唯讀、不寫入、不對外請求；驗收指令為容器內裸打不帶 header。**失效條件已寫進 controller Javadoc**：若日後 business 的 8080 對外映射即須改 |
| 門檻掃描的實作層 | 在**述詞層**套候選值，不改引擎 | 引擎門檻是常數且本任務禁止修改；掃描要回答的是「若門檻為 X 哪些日子成立」，用已算好的底層量即可 |

### 架構符規查證

`arch-auditor` 回報 **0 critical／1 major／2 minor**，全數已修：

- **[major]** `fullAdjustedCloses()` 原本在還原失敗時靜默改用原始收盤價（違反 273.5「不得用原始收盤價」，會讓月配息債券 ETF 的 240 日報酬被系統性低估而輸出看起來正常）→ **移除 fallback**，改為往外拋、由 per-code catch 丟棄並記入 `failedCodes`。
- **[minor]** CSV 組裝原在 controller → **搬進 `BacktestService.toCsv()`**（與 `ExcelExportService` 等既有慣例一致）。
- **[minor]** 失敗標的原本靜默消失、`codeCount` 縮水無從察覺 → **新增 `Response.failedCodes`** 與對應 note。
- **另修一處自檢發現的 bug**：ETF 折溢價 caveat 原以 `startsWith("TIMING_OVERBOUGHT")` 判定，漏掉 `TIMING_EXTREME_OVERBOUGHT`（`startsWith` 為 false）且誤掛在非 ETF 上（2330 也被標記）。改為資料驅動的 ETF 判定＋`endsWith("OVERBOUGHT")`，實測 caveat 現在只出現在 `BUY_GATE`／`TIMING_OVERBOUGHT`／`TIMING_EXTREME_OVERBOUGHT`／`TRIAL_BUY` 四條。

記錄：`bash .claude/hooks/arch-review-pass.sh` → `92e2521d8da3`。
