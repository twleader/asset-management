# [t334] 美股歷史估值序列由已入庫官方財報推導落地（`SEC_DERIVED` provider）

**對應 Requirements:** Requirement 74（美股歷史估值序列由已入庫官方財報推導落地——讓交易雷達的 VALUATION
因子不必再等 250 個交易日累積，七檔美股個股的 `pe`／`pb`／`dividend_yield` 不再整組 MISSING）
**前置任務:** 無（`stock_valuation_daily`／`stock_financial_quarter` 由 t266 建立、美股列由 t293 打通，兩者皆已 landed）
**Liquibase changeset:** 無（不動 schema；`provider` 與 `availability_basis` 皆已是 `varchar(20)`，
`SEC_DERIVED`（11 字元）／`RECONSTRUCTED`（13 字元）長度足夠）

## 背景

### 現在的錯誤行為

2026-08-15 13:53 Asia/Taipei，對運行中 stack 的 business-services 容器唯讀取得
`GET /api/trading-radar`（`X-User-Id: 1`）共 32 檔（台股 21、美股 11），
`ruleVersion=TW_RULES_V12`、`actionPolicyVersion=EVIDENCE_GATE_V1`。逐一統計
`evidence.evidenceGroups.VALUATION.components[*].applicability`：

| component | AVAILABLE | NOT_APPLICABLE | MISSING |
|---|---|---|---|
| `pe` | 4 | 21 | **7** |
| `pb` | 4 | 21 | **7** |
| `dividend_yield` | 4 | 21 | **7** |

- AVAILABLE 的 4 檔是台股個股 2330／2881／2885／2891。
- NOT_APPLICABLE 的 21 檔是 ETF（台股 17 ＋ 美股 4：QQQ／VOO／VT／SGOV）。
- **MISSING 的 7 檔全部是美股個股：AMZN／NVDA／AVGO／MSFT／TSM／GOOGL／COIN**，
  `missingReason` 一律為「估值欄位／250 筆歷史不足」。

受影響的 `mediumEvidenceConfidence`（同次實測）：AMZN 78、MSFT 78、GOOGL 78、COIN 78、
AVGO 67、NVDA 56、TSM 56（台股個股與 ETF 多為 100）。VALUATION 整組 `availableWeight=0`。

### 成因（已查證，不是抓取失敗）

`backend/src/main/java/com/steven/assets/service/FundamentalAnalysisService.java`：

```java
private static final int PE_MIN_SAMPLES = 250;                                    // 第 50 行
private static final List<String> PROVIDERS =
        List.of("EXCHANGE", "SEC_EDGAR", "YAHOO", "WANTGOO", "FINMIND");          // 第 54 行
...
if (history.size() < PE_MIN_SAMPLES) return null;                                 // 第 452 行
if (history.size() < PE_MIN_SAMPLES) continue;                                    // 第 527 行
```

`valuationComponent(...)` 對**每一個 provider** 各自要求至少 250 筆有效歷史，不足即 `continue`；
五個 provider 全數落空後回 `null`，`valuationComposite(...)` 隨即
`if (available.isEmpty() && rawYield == null) return null;`，呼叫端 `analyze(...)` 再以
`valuation == null ? null : valuation.asFactor()` 帶出——**值與分位一起消失**。

DB 實測（`docker compose -p asset-management exec -T postgres`）：

```
stock_valuation_daily  台股｜4 檔｜34007 列｜2021-08-09 ~ 2026-08-14
stock_valuation_daily  美股｜7 檔｜   56 列｜2026-08-09 ~ 2026-08-15
```

美股那 56 列全部來自 Yahoo `quoteSummary`（provider `YAHOO`），**只有當期快照、沒有歷史**。

台股那 34,007 列的逐 provider 分佈為：

```
台股|FINMIND |4 檔|33979 列|2021-08-09 ~ 2026-08-14
台股|EXCHANGE|4 檔|   24 列|2026-08-07 ~ 2026-08-14
台股|YAHOO   |4 檔|    4 列|2026-08-08
```

且雷達四檔 AVAILABLE component 的 `provider` 皆顯示 `FINMIND`——**深度來自 FinMind 的 `TaiwanStockPER`
dataset**（`StockFundamentalFetchClient` 的 `finMindUrl("TaiwanStockPER", code, since)`，帶 `since` 可一次取多年），
**不是** Requirement 61／Task 278 的 TWSE `BWIBBU_d` 歷史回補——**那支任務至今未實作**
（`spec/design.md` 對 `/internal/valuation/backfill` 逐字仍記「規劃中、尚未實作／尚未受 AdminGate 保護」，
`spec/tasks.md` 標「尚未送審」）。本任務不依賴、也不推進 Task 278。差別純粹是歷史深度。

### 正確行為

美股個股的 `pe`／`pb`／`dividend_yield` 應該有一條夠長（≥250 筆）的自身歷史序列可以計算分位，
且該序列必須誠實標記為「由官方財報推導而來」而非觀測到的公告值。

### 本任務推翻的既有決定

`spec/design.md` 的 Requirement 64／Task 293 設計段，逐字把「**美股基本面歷史回補**（比照台股
「自上線起累積」，即使 SEC EDGAR 理論上可一次回補多年歷史）」列為「明確不在本次範圍」。
Requirement 74 推翻的是**這個決定**（當初的前提是「先讓每日累積跑起來就好」），
不是推翻任何資料來源或合規判斷。推翻的形態與 Requirement 61 推翻 Requirement 46 的
「歷史回補預設不實作」完全同型。

### 本任務刻意不碰的東西

- **不得調整 `PE_MIN_SAMPLES=250`**，也不得調整 Requirement 65 evidence gate 的
  「group coverage ≥70%、confidence ≥70」。本專案已有「無量測依據門檻清單」的既有體例
  （`spec/requirements.md` 列出 OSC 全幅 `0.5%`／文案門檻 `0.1%`、乖離自身分位 `98`／`2`
  與最少樣本 `120`、ROE 斜率 `10`、通知冷卻 `60` 分鐘，並註明「**皆無回測量測依據，為判斷性取值**」）。
  `PE_MIN_SAMPLES` 同屬未經回測量測的取值，要動必須先以 Requirement 56 的回測工具量測後另案處理。
  **任何註解或 UI 文案不得宣稱本次改動能提升報酬或降低風險。**
- 不改 `TradingRadarRuleEngine.RULE_VERSION`（維持 `TW_RULES_V12`）、不改任何規則參數與權重。
- 不改 Yahoo 當期估值抓取路徑的行為（見 334.6，反而要主動保護它不被關掉）。
- 不改 `etf_premium`、`regime`、`market_volume_turnover`、`PUBLIC_EVENT` 任何一組證據。
- 不動 schema、不新增 Liquibase changeset。

## 要做什麼

### 共同約束（每一項子任務都適用）

- **推導與落地一律在 `external-materials-service`。** 理由有兩個，**要引對**：
  (i) 落地必須走**只存在於該服務**的 `FundamentalObservationStore` append-on-change 去重路徑，
  另寫一條寫入路徑等於讓同一張表有兩套去重語意；
  (ii) 334.4 步驟一的 EDGAR 重抓本來就是對外 HTTP，受 `spec/steering/structure.md` 架構鐵則
  「❌ business-services 直接打外部行情／NAV／配息 API（**一律經 external-materials**）」約束。
  **推導計算本身不發任何 HTTP**（只讀三張表），故該鐵則管不到推導計算，**不得**把它當成推導計算的
  放置理由——真正的理由是 (i)。代價是單季還原邏輯會在兩個 module 各有一份，須以相同輸入的斷言把兩者
  釘在一起（見 334.7；本專案已有 `expectedFinancialPeriodIndex` 兩份複本走樣的前例）。
  本任務對 `backend/`（business-services）的改動**只有 334.5 的一行 provider 白名單**，
  除此之外 backend 不得新增任何對外 HTTP。
- **寫入一律走既有 append-only 去重路徑。** `FundamentalObservationStore.append(Bundle)` →
  `appendValuation(row)`：先以
  `WHERE stock_code=? AND market=? AND trading_date=? AND provider=? ORDER BY observed_at DESC LIMIT 1`
  取最新一列，`latest.same(row, urls)` 為真就回 `0` 不寫。**不得**自己另寫一條 INSERT 路徑，
  也不得 `DELETE` 既有列——這三張表是 append-only 觀測表，實測**只有 PK(id)、沒有任何 unique index**。
- **`market` 欄一律寫 `"美股"`**（跨表 join key，填錯會讓下游 `WHERE market='美股'` 靜默回零筆）。
- **嚴禁以 `0` 代替 `NULL`。** `pe_ratio = 0` 在下游會被讀成「本益比極低＝極便宜」，把虧損公司評為最優。

### 子任務

- [ ] **334.1 spec**（本檔 ＋ `requirements.md` Requirement 74 ＋ `design.md`「美股歷史估值序列推導落地」段
      ＋ `tasks.md` 索引）。已隨本檔一併完成，實作者不需重寫，但**動工前必須讀完本檔**。

- [ ] **334.2 拉長 SEC EDGAR 的歷史抓取視窗。**
      `external-materials-service/.../client/StockFundamentalFetchClient.java` 第 70 行
      `private static final int SEC_LOOKBACK_YEARS = 3;` → 改為 **11**，並更新第 227 行 Javadoc
      「只保留 `end` 落在最近 {@value #SEC_LOOKBACK_YEARS} 年內的列」那段的說明理由
      （原理由「避免一次抓出過量歷史」須改寫為：歷史深度是 Requirement 74 推導序列的必要輸入，
      11 年對齊 `stock_price_history` 美股的實際覆蓋 2016-08-15～2026-08-14）。
  - `parseCompanyFacts(...)` 的 `LocalDate cutoff = LocalDate.now(TAIPEI).minusYears(SEC_LOOKBACK_YEARS);`
    是唯一使用點，改常數即生效。`extractFacts(...)` 仍只收 `form ∈ {10-Q, 10-K}`，不放寬。
  - **不得**因此放寬 `buildPeriodMap`／`selectCumulative` 既有的防污染規則（比較年度標籤陷阱與
    累計口徑陷阱，見該類別 Javadoc）——舊年度的申報文件同樣有這些問題，放寬會讓錯誤數字靜默入庫。
  - 實測基準：改動前 `stock_financial_quarter` 的 `market='美股'` 只有 **6 檔、共 61 列**、
    每檔 9–12 季、最早 2024Q1（AMZN／AVGO／COIN／GOOGL 各 10 季、MSFT 12 季、NVDA 9 季；
    **TSM 完全無列**，故不在這 6 檔之內）。
    改動後預期最早季別明顯前推；**若某檔仍只有 2024Q1 起的資料，那是來源端就沒有，不是本項失敗**，
    須在完成報告據實記載該檔實際取得的季數與最早季別。

- [ ] **334.3 新增推導服務 `UsValuationDerivationService`**（`external-materials-service/.../service/`）。
      唯讀三張既有表、純本地計算、輸出 `List<StockFundamentalFetchClient.Valuation>` 交給
      `FundamentalObservationStore.append(...)` 落地。**類別 Javadoc 必須載明**：
      (i) 資料來源與推導算式；(ii) 為何這是刻意的 denormalization 及其豁免理由
      （**分位計算需要一條固定且可重現的觀測序列**——逐次即時重算會讓同一個歷史日期的分位隨財報
      事後重述與價格還原權息調整而漂移，使回測與 production 的同一個因子失去可比性；比照
      `asset_snapshot.total_*` 的既有加註體例）；(iii) `SEC_DERIVED` 是推導值、不得被顯示成官方公告值。
  - **標的範圍：** `stock_financial_quarter` 中 `market='美股'` 且 `provider='SEC_EDGAR'` 的相異
    `stock_code`。**不含** TSM（該表 0 列，外國發行人走 20-F、不在 `companyfacts` 範圍）與美股 ETF
    （無個股財報；`VALUATION` 對 ETF 本就是 `NOT_APPLICABLE`）。範圍以資料驅動判定，
    **不得硬編標的清單**。
  - **逐交易日推導。** 交易日集合取 `stock_price_history` 中該 `stock_code`、`market='美股'`、
    `close_price > 0` 的 `trading_date`（實測美股共 40,593 列／17 檔、2016-08-15～2026-08-14）。
  - **point-in-time（本項最容易做錯、做錯不會有任何錯誤訊息）：** 交易日 D 只能使用**當日已公開**的季度列。
    **嚴禁**用「最新已知財報」回頭套用到它公布之前的日期——那會讓歷史分位含有未來資訊，
    使 Requirement 65 的 walk-forward／holdout 樣本外驗證失去意義。
  - **但 `source_available_at` 不是「首次公布時點」，直接拿來過濾會做出鋸齒序列（做錯同樣不會報錯）：**
    - 該欄的真實語意是「**最後一次提及該期別的申報時點**」。寫入端 `StockFundamentalFetchClient` 的
      `selectCumulative`／`selectInstant` 對同一期間「取 `filed` 最新的一筆」，而 SEC 申報文件會夾帶
      前期比較數字，故舊期別會被後來的申報反覆覆蓋成更晚的日期。
    - 實測（`market='美股'`，`source_available_at::date`）：

      ```
      MSFT|2024|4|2026-07-29   ┐
      MSFT|2025|4|2026-07-29   ├ 三個不同會計年度的 Q4 共用同一個 filed（同一份含三年比較欄的 10-K）
      MSFT|2026|4|2026-07-29   ┘
      AMZN|2025|2|2026-07-31   ← 非單調：更新的 2025Q3 反而是 2025-10-31
      AMZN|2025|3|2025-10-31
      ```
    - 直接以「`source_available_at` ≤ D」過濾的兩種後果：(i) 舊期別被推遲到一年後才「可見」，
      歷史區段被迫用 12–15 個月前的 EPS、最近區段用 1.5 個月前的 EPS，**同一條序列的 TTM 分母會在
      某一天跨季跳階**（依 AMZN 現有十季推算：D≥2026-07-31 時 TTM EPS 由 `6.14` 跳到 `12.43`，PE 對半砍），
      成長股的「今天」必然落在自身歷史 PE 的極低分位、`contribution` 逼近 `+1`、輸出
      **「現在最便宜」的錯誤訊號**；(ii) 非單調的日期會讓「4 個連續季度」時有時無。
    - **規則：先單調化再過濾**——`effective_available_at(Q) = min{ source_available_at(Q') | Q' ≥ Q }`
      （較新期別既已公開，較舊期別必然早已公開，故此式是真實首次公布時點的**上界**、不引入未來資訊），
      再以它套「≤ D 當日美股收盤時刻」與「4 連續季」。此規則須在類別 Javadoc 載明。
  - **TTM 口徑。** `stock_financial_quarter.eps`／`net_income_parent` 存的是**會計年度累計值**。
    單季值＝`cumulative(Q) − cumulative(Q−1)`，Q1 直接取累計——**必須沿用 backend
    `FundamentalAnalysisService.standaloneValue(int quarter, BigDecimal cumulative,
    BigDecimal previousCumulative)`（第 639 行）的同一套語意**，不得另寫一份平行實作而讓兩處
    日後靜默分岔（external-materials-service 與 backend 是不同 module，無法直接呼叫；
    須以相同語意實作並在 Javadoc 指名它是哪一支的鏡像，且測試以相同輸入斷言結果一致）。
    TTM＝最近 **4 個連續**季度單季值加總；任一季缺漏或季別不連續即該日**不落列**。
  - **三個欄位的算式與空值語意：**

    | 欄位 | 算式 | 不可得時 |
    |---|---|---|
    | `pe_ratio` | 收盤 ÷ TTM EPS | TTM EPS ≤ 0（虧損）→ `NULL` ＋ `pe_loss_flag=true`，**仍須寫入該列**（否則下游無法區分「虧損」與「未推導出」這兩種行為相反的情形） |
    | `pb_ratio` | 收盤 ÷ 每股淨值；每股淨值＝母公司權益 ÷ 推導股數；推導股數＝TTM 母公司淨利 ÷ TTM EPS | 每股淨值 ≤ 0、TTM EPS ＝ 0、或該季 `equity_parent` 為 null → `NULL` |
    | `dividend_yield_pct` | 近 365 日現金股利加總 ÷ 收盤 × 100 | D 當日之前查無任何除息紀錄 → `NULL`（**不得寫 0 冒充「不配息」**） |

    - 推導股數的分子分母必須來自**同一組 TTM 季度**，避免混用不同時點的股數。
    - `net_income_parent`／`equity_parent` 為 `bigint`，**美股 SEC_EDGAR 的單位是 USD 元**
      （台股才是千元）；同一列的 `eps` 與 `net_income_parent` 必為同來源，故「淨利 ÷ EPS ＝ 股數」
      在美股成立。**不得**對美股列做任何千元換算。
    - 母公司權益取「D 之前已公布的最新一季」的 `equity_parent`（時點值，非累計）。
    - 股利來源為 `stock_dividend_history`（`market='美股'`，實測 11 檔 488 列、起 2017-02-09），
      條件 `ex_dividend_date > D − 365 天 AND ex_dividend_date <= D`，加總 `cash_dividend`。
      **加總前必須先去重**（重複列數實測 AVGO 30、NVDA 29、QQQ 36，其餘美股標的 0），
      且重複有**兩種不同型態**，處理方式不同：

      ```
      型態一（同一筆、兩個精度；QQQ 從未分割）
      QQQ |2023-03-20|0.472000 / 0.472200          比值 1.0004

      型態二（同一筆、兩個每股基準）
      AVGO|2023-06-21|0.460000 (previous_close 為 null)
      AVGO|2023-06-21|4.600000 (previous_close 86.8030 為還原價 ← 列內兩欄基準不一致)
      NVDA|2023-06-07|0.004000 / 0.040000          比值 10
      ```

      直接加總會**同時 double count 與混基準**，使分割前區間的殖利率偏高約一個數量級。
      **規則：** 先以 `(stock_code, market, ex_dividend_date)` 分組，對同組的相異 `cash_dividend` 依序判斷：
      0. **先試既有的確定性判別欄位再退回比值啟發式。** 該表已有 `event_key VARCHAR(64)`
         （changeset `v1.99.0-radar-dividend-event-identity.sql` 新增並納入 `uk_dividend_event`）
         與 `source VARCHAR(128)`（`v1.97.0-radar-dividend-provider-width.sql`，存 deterministic
         endpoint-set label）。動工時**必須先實查**這兩欄能否確定性地區分那 30／29／36 筆重複列；
         能區分就用它，比值規則降為 fallback。（本檔撰寫時的抽樣顯示同一除息日的兩列 `event_key`
         **不同**、`source` 皆為 `NASDAQ+Yahoo Finance`，故很可能區分不了——但仍須自行複驗，不得跳過。）
      1. 最大／最小比值 ≤ `1.01` → **型態一**，視為同一筆，取有效位數較多者、**只計一次**，
         **不得** fail closed（單純的來源精度差不該讓整日殖利率消失）。
      2. 比值 ≥ `1.8` → 可能是**型態二**。判定方式須可執行、不得留給實作者自行發揮：
         取該 `stock_code` 全部「可解出比值」的除息日，以其比值**眾數 m** 為該標的的還原倍數基準；
         `|ratio − m| / m ≤ 0.10`（即所引體例的 `SPLIT_RATIO_TOLERANCE`）才判為型態二、取較小值並以
         DEBUG 記錄；不符即落到第 3 條。該標的**沒有任何可解出比值的除息日**時，一律落到第 3 條。
      3. 其餘一律 `NULL`（fail closed），並以 DEBUG 記錄該日與其相異值。**不得**任選一列、
         不得直接加總、不得用 `MAX`／`MIN` 之類未說明理由的啟發式。
      > **第 3 條會誤殺一種合法情形，這是刻意的取捨、已登記為後續任務：** changeset
      > `v1.98.0-radar-dividend-same-day-amounts.sql` 的註解逐字寫著同一除息日可以承載
      > 「multiple distribution components/revisions」，即同日兩筆**不同金額的合法分配**確實存在；
      > 本規則會把這種情形一併判成不可解而寫 `NULL`（少算殖利率、偏保守），而不是冒險相加。
      > 在有可查證的分割事件來源之前，寧可缺值也不要混基準——理由同上面的每股基準閘門。

      > **「正向分割下較小者必為已還原值」單獨作為判準會出錯，實測已被推翻，故才需要第 2 條的鄰近一致檢查：**
      > AVGO 僅有的一次分割是 2024 年 10:1（還原倍數必為 10），但
      > `2018-03-21`／`2018-06-19` 各為 `0.35` 與 `1.75`（比值 **5**），而相鄰的 `2017-12-18`／`2018-09-18`
      > 為 `0.175` 與 `1.75`（比值 10）——只看「取最小值」會採 `0.35`，是正確值 `0.175` 的**兩倍**且不會報錯。
      **只有在該標的於 D 當日之前已有至少一筆除息紀錄時才計算**（可以算出 0，代表已停配滿一年）；
      查無任何除息紀錄者一律 `NULL`。實測受影響者為 **AMZN 與 COIN**（`stock_dividend_history` 零列），
      這兩檔的 `dividend_yield` 會維持 MISSING——這是誠實揭露，本專案既有結論是
      「查無配息不是 bug，也不得捏造入庫」。
  - **每股基準閘門（本任務最容易做錯、做錯完全不會報錯的一項，必須先讀完再動手）：**
    - `stock_price_history` 的美股收盤**已還原股票分割**。實測：AMZN 20:1 分割
      `2022-06-03|122.35` → `2022-06-06|124.79`；NVDA 10:1 分割
      `2024-06-07|120.888` → `2024-06-10|121.79`，**皆無倍數跳空**；全表 `market='美股'` 40,593 列中
      相鄰交易日 `prev/close` 落在 `>1.8` 或 `<0.55` 的列數為 **0**（同一支查詢對台股會命中
      `0050|2025-06-18|188.65|47.57` 與 `2327|2025-08-25|546.00|143.00`，證明台股是原始價、美股是還原價）。
    - SEC `companyfacts` 的每股值則是**申報當下的基準**：`extractFacts(...)` 只收 10-Q／10-K 的原始事實，
      `buildPeriodMap` 對同一 `(start,end)` 只取 `filed` 最新一筆，故只有落在比較年度視窗（約 1–3 年）內、
      被分割後的申報重報過的期別才是還原值；更舊的期別維持申報當下基準。
    - 兩者相除，**跨越分割點的區段會整段錯一個分割倍數**，且 PB 同步錯（EPS 偏大 → 推導股數偏小 →
      每股淨值偏大 → PB 偏小同一倍數）。分位是在整條被污染的序列上算的
      （`FundamentalAnalysisService.valuationComponent`），被壓低數倍的舊 PE 會把「今天」推到接近
      100 分位、`contribution` 夾到 `-1`，等於長期輸出「現在最貴」的錯誤訊號，**沒有任何日誌或例外**——
      正好打在本任務宣稱要提升的判斷準度上。
    - **閘門規則：** 計算每季的**單季推導股數**＝單季 `net_income_parent` ÷ 單季 EPS（同一份申報的
      分子分母，兩者都先以 `standaloneValue(...)` 由累計還原），由新到舊掃描；相鄰季度比值 ≥ `1.8`
      即視為**基準變動點**。**只有比最新一個變動點更新的季度可用**。
    - **下列情形一律視為「基準不可驗證」＝等同變動點（fail closed），缺一條閘門就會 fail open：**
      (i) `net_income_parent` 為 null；(ii) 單季 EPS 為 0（除以零）；
      (iii) **單季 EPS 或單季 `net_income_parent` 不為正**（虧損季）——推導股數會變成負數，
      而「比值 ≥ 1.8」對負數**永遠為 false**，等於對整個虧損區段靜默放行，與 fail-closed 的意圖相反；
      (iv) 相鄰兩季有任一季的推導股數非正或不可得。比值一律**只在兩季推導股數皆為正**時才計算。
    - **口徑必須寫死為單季，不得用 TTM——這是會讓閘門靜默失效的分岔：** `net_income_parent` 不隨分割
      改變而 EPS 會，TTM 口徑下一次分割會被攤平成四個小台階，相鄰比值為：

      | 分割倍數 k | 四步相鄰比值（TTM 口徑） | 是否觸發 ≥1.8 |
      |---|---|---|
      | 2 | 1.14 / 1.17 / 1.20 / 1.25 | **全部否** |
      | 4 | 1.23 / 1.30 / 1.43 / 1.75 | **全部否** |
      | 10 | 1.29 / 1.41 / 1.69 / 3.25 | 僅最後一步 |

      單季口徑則比值恰為 `k`（2.0／4.0／10.0），一次命中。334.2 把視窗拉到 11 年後，NVDA 2021-07-20 的
      **4:1** 分割正好落進視窗，TTM 口徑會完全偵測不到。
    - **門檻取值須誠實登記：** `1.8`（本項）與股利去重用的 `1.8`／容差 `0.10`，**皆無回測量測依據、為判斷性
      取值**，比照 `spec/requirements.md` 既有的「無量測依據門檻清單」體例。取 `1.8` 而非所比照的
      `DistributionAdjustedPriceService.SPLIT_FORWARD_MIN = 2.0`（該類別 `:33`，容差
      `SPLIT_RATIO_TOLERANCE = 0.10` 在 `:44`），理由是留一點餘裕給 EPS 只有兩位小數所造成的推導股數雜訊。
      **該餘裕的量級隨 EPS 絕對值變動，不是固定的**：單季 EPS 量級 ≥ 1 時捨入誤差約 ±1%，
      但 EPS 落在 ±0.0x（虧損或接近損益兩平，AMZN 2022 年多季即屬此類）時可達 ±25%，
      足以自行製造或掩蓋一個 1.8 倍跳階——這正是上面 (iii) 要把非正 EPS 直接判為變動點的原因之一。
      **不得**在任何註解或文案宣稱這個取值有實證支持。TTM 視窗跨越變動點的交易日**不落列**。
    - 實測參考（以現有 3 年視窗計，全部季度都已是今日基準，故現況無污染；風險是 334.2 拉長視窗後才出現）：
      AMZN 推導股數約 10,644–10,891 百萬股、GOOGL 約 12,227–12,520、NVDA 約 24,402–24,897，
      三檔在現有區間內皆平滑無跳階；**AVGO 10 季中 `net_income_parent` 只有 2024Q4 一季非 null**，
      故其推導股數幾乎無法計算 → 依 fail-closed 規則多數季度不可用。
    - **此閘門會使部分標的的可用歷史短於資料實際深度，因而可能仍湊不滿 250 筆而維持 MISSING——
      那是正確結果，不得為了湊滿而放寬基準檢查。** 以 rebase 把舊期別還原到今日基準以延長覆蓋，
      屬後續獨立任務（已登記於 `t333_radar_gap_backlog_2026_08_15.md`）。
  - **落地欄位：** `provider='SEC_DERIVED'`、`availability_basis='RECONSTRUCTED'`、`market='美股'`、
    `trading_date=D`；`source_available_at` 取「所用四季中**最新那一季的 `effective_available_at`**
    （單調化後的值；因 `effective_available_at` 對期別單調不減，四季的最大值必為最新那一季）」與
    「D 當日美股收盤時刻對應的 Instant」**兩者的較晚者**（收盤價本身也要到收盤才可得）；
    `source_urls` 至少含所用的 `companyfacts` URL，並加一個標明推導性質的字串。
  - **這裡絕對不可以改用 raw `filed`。** MSFT 三個 Q4 的 raw `filed` 都是 `2026-07-29`，用它會讓
    2024–2026 整段推導列的 `source_available_at` 塌成同一天。production 路徑（decisionInstant＝現在）
    看不出異常，但 backend `FundamentalAnalysisService.loadPreparedData`（`:320-327`）以 SQL
    `AND source_available_at<=?` 過濾、`latestAsOf`（`:351-353`）再以
    `row.availableAt().isAfter(decisionInstant)` 剔除，故**任何過去決策時點的 walk-forward／holdout
    會整組取不到 `SEC_DERIVED` 列**——正好毀掉這段 point-in-time 規則要保護的東西。
  - **`pe_loss_flag` 的語意沿用既有欄位**：有正 TTM EPS 時為 `FALSE`，虧損時為 `TRUE`，
    連 TTM 都算不出來時該日不落列（故不會出現 `null` flag 搭配 `null` PE 的模稜狀態）。
    **但這個旗標在 backend 有一條不受 250 筆門檻約束的捷徑，必須一併堵住——見 334.5。**

- [ ] **334.4 新增排程與開機自癒 `UsValuationDerivationScheduler`**（同服務 `service/` 下）。
  - 每日排程：`@Scheduled(cron = "0 30 7 * * TUE-SAT", zone = "Asia/Taipei")`
    **07:30 這個時點的硬約束是「必須晚於 `ClosePersister` 在 18:00 ET 的 FinMind 收盤校正」**
    （該類別 `:316-318`：「18:00 ET：用 FinMind USStockPrice 校正美股當日收盤。FinMind 有回值即覆寫
    16:02 dump 值」；換算為夏令時＝台北 06:00、**冬令時＝台北 07:00**），否則會把未校正的收盤寫進
    推導序列——在「只補缺口」的預設下，超出 30 日重算窗的錯值會永久留存。**冬令時只剩 30 分鐘餘裕，
    不得再往前調。** 次要考量是排在既有 `IndexDailyRefreshScheduler` 的 07:00 之後
    （該排程在 business-services、經 `priceServiceClient` proxy 打 external 的
    `/internal/macro/us-index`，與本排程不同服務、不同外部主機，故只是次要考量、不是理由本身）。
  - 開機自癒：`@EventListener(ApplicationReadyEvent.class)`，於背景執行緒延遲啟動
    （比照既有 `IndexDailyRefreshScheduler.selfHealStaleOnStartup()` 的 `Thread.sleep(30_000)` 慣例，
    等依賴就緒），與每日排程**共用同一支方法**。
  - 該方法的兩個步驟，順序不可顛倒：
    1. **確保 EDGAR 季報歷史足夠**：對範圍內每個標的呼叫
       `StockFundamentalFetchClient.fetchSecEdgarFacts(code)` 並 `append`。
       **必須繞過既有 coverage 短路**——`StockFundamentalPoller` 只在
       `FundamentalObservationStore.coverageNeed(...)` 判定「還缺」時才呼叫 EDGAR，
       已覆蓋的標的不會自己去補更早的歷史，不主動觸發就永遠拿不到 334.2 放寬後的舊季別。
       SEC 要求具名 User-Agent（既有 `SEC_USER_AGENT` 常數已符合）；每個標的之間須有節流間隔
       （比照既有 `Thread.sleep(500)` 慣例）。
    2. **逐日補齊缺口，但保留兩個必要的重算觸發**：預設只處理「該標的的交易日中，尚無 `SEC_DERIVED`
       列」的日期；**首次啟動即完成整段歷史回補**，之後每日只增補新的一天；中斷後重跑只處理缺口，
       不得每次無條件從頭重算整段。**但「只補缺口、永不重算」單獨成立會鎖死兩種可修復的漂移，
       故必須加上這兩個觸發：**
       - **(a) 收盤價會被事後校正。** `ClosePersister`（同服務）在 **18:00 ET** 用 FinMind
         `USStockPrice` 校正美股當日收盤、**有回值即覆寫 16:02 dump 值**（見該類別 `:316-318`），
         另有歷史修補路徑（t258）。故每輪**一律重算最近 30 個交易日**（值相同時
         `appendValuation` 本來就不會寫，故成本只有計算、不會膨脹表）。
       - **(b) 未來的股票分割會讓已寫入的列變成舊基準。** 價格序列是還原到**今日**基準的，
         下一次分割發生後，已寫入的 `SEC_DERIVED` 列若不重算，整條序列就變成混基準、分位失真。
         偵測方式不需存額外狀態：每輪算出的「可用區段起點」若**晚於**該標的現有 `SEC_DERIVED` 列的
         `min(trading_date)`，代表基準變動點往後移了，**必須刪除並重寫該標的的整段 `SEC_DERIVED` 列**
         （這是本任務唯一允許 `DELETE` 的情形，須以 WARN 記錄標的、舊起點、新起點與刪除列數）。
       - **殘留盲點（無法在本任務解決，已登記於 t333.11）**：閘門是**季對季**比較，
         對「發生在最新一份 10-Q 之後」的分割**無季可比**，最長約三個月偵測不到；
         而那正是分位計算裡最具決策權重的一端，症狀恰好是本任務要防的「PE 看起來便宜 k 倍」。
  - 全程 fail-soft：單一標的失敗只記 WARN 並繼續下一檔，不得讓整輪中止，也不得擲例外到排程執行緒外。
    收尾以 INFO 記錄「處理 N 檔、新增 M 列、略過 K 檔（含原因）」。
  - **排程列表頁需同步，且筆數要連同 t332 一起算（只加自己的 +1 會漂移）**：本項新增了真正的
    `@Scheduled` annotation（在 **external-materials-service**），而同一分支的
    `t332_index_daily_freshness_alignment.md` 的 332.5 也新增一個（在 **business-services**）。
    現況為 **51 筆 ＝ business 20 ＋ external 31**，兩者都落地後應為
    **business 21 ／ external 32 ／總計 53**。須同步更新的位置（實測共 4 個檔案 9 處）：
    - `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`
      的 `JOBS` 清單本體，以及 `:13`（類別 Javadoc「business-services（20 個）與
      external-materials-service（31 個）」）、`:53` 與 `:217`（皆為「全系統排程清單（51 筆）」字樣）、
      `:55`（`// ===== business-services（20）=====` 分隔註解）、
      `:117`（`// ===== external-materials-service（31）=====` 分隔註解）
    - `bff/src/test/java/com/steven/assets/bff/schedulelist/SchedulePublicBffControllerTest.java`
      的三個斷言：`hasSize(51)`、`"業務服務"` `hasSize(20)`、`"外部行情服務"` `hasSize(31)`
      → 改為 53／21／32。**不改這裡會直接 test failing。**
    - `spec/design.md` 的排程清單段（已於本次 spec 變更改為 53 ＝ 21 ＋ 32）
      > **若最後只落地本任務、另一支沒進同一次 commit，本任務只 +1 自己這一邊**
      > （t334 單獨落地 → 52 ＝ business 20 ＋ external 32；t332 單獨落地 → 52 ＝ business 21 ＋ external 31）。
      > 兩支任務由不同 subagent 執行，各自無條件寫 53 會讓先落地的那一支直接 test failing。
      > **落地前先實查 `SchedulePublicBffController` 當下的實際筆數，以它為準 +1，不要照抄本檔的 51。**

    **這是本任務唯一會動到 `bff/` 的地方。** 動工前先讀該類別確認實際欄位格式
    （`ScheduledJobDto` 為不可變 record：service／category／name／description／schedule 白話／cron／zone）。

- [ ] **334.5 backend：把 `SEC_DERIVED` 加進 provider 白名單。**
      `FundamentalAnalysisService.PROVIDERS`（第 54 行）改為
      `List.of("EXCHANGE", "SEC_EDGAR", "YAHOO", "WANTGOO", "FINMIND", "SEC_DERIVED")`——
      **必須置於清單最末**（優先序最低，推導值只在所有實際觀測來源都湊不到 250 筆時才被採用），
      並在該常數既有註解下方加一行說明理由。
  - **漏掉這一項會讓資料入庫但永不生效且無任何日誌**：該清單被以 `provider.equals(r.provider())`
    過濾後迴圈使用，清單外的 provider 會被靜默丟棄。
  - **但加進白名單會同時打開六個使用點，其中一個沒有 250 筆門檻，必須一併堵住。** 實測
    `grep -n "PROVIDERS" backend/.../FundamentalAnalysisService.java` 共 **6 個迴圈點**：
    `:364 industryFactor`、`:435 peFactor`、`:516 valuationComponent`、`:550 latestLoss`、
    `:567 latestRevenueBasis`、`:608 firstProviderValue`。
    其中 **`latestLoss`（`:549-558`）沒有任何筆數門檻**——它只要求該 provider 最新一列 `fresh(...)`
    且 `loss()` 非 null，命中即回傳；而 `valuationComposite`（`:481-486`）拿到 loss 決策就直接
    `return ValuationComposite.loss(...)`，PE component 被 `lossComponent`（`:539-542`，
    **contribution 寫死 `-1.0`**）取代。
  - 由於 334.3 規定 `SEC_DERIVED` 每一列都會寫 `pe_loss_flag`（TRUE／FALSE 二選一），
    只要 YAHOO 當期列的旗標為 null 或超過 `VALUATION_MAX_AGE_DAYS = 10`（`:49`）而過期，
    **推導出的虧損旗標就會繞過 250 筆門檻直接把整組 VALUATION 打成 `-1.0`**，
    與 Requirement 74 反覆宣告的「推導值只在所有實際觀測來源都湊不到 250 筆時才被採用」直接抵觸。
  - **故 `latestLoss(...)` 必須明確排除 `SEC_DERIVED`**（在該方法內以具名常數過濾並加註原因：
    虧損與否是一手觀測事實，不接受由自家推導值認定），並補一個測試：
    推導列 `loss=true`、YAHOO 列無旗標時，VALUATION **不得**變成 `-1.0`。
  - `peFactor`（`:435`）經查在 production 無呼叫端（只有 `FundamentalAnalysisServiceTest` 用到），
    雷達走的是 `valuationComponent`（`:516`，`:527` 用 `continue` 會落到清單最末順位），
    故不需為它加排除；但**動工時要再確認一次呼叫端**，若已被接線則同樣須評估。
  - `industryFactor`／`latestRevenueBasis`／`firstProviderValue` 讀的是財報／營收表，
    本任務不寫那些表的新 provider，不受影響。
  - Requirement 65 已明訂「每個 component 都只能在自己的 provider 歷史內計分」「不得跨 provider
    拼出單一 component」，故 `SEC_DERIVED` 的分位一律只在自己的序列內計算，
    **嚴禁**與 `YAHOO` 當期快照併成同一條序列——現行實作已依 provider 分組，本項只是加白名單，
    **不得**為了「湊滿 250 筆」而修改分組邏輯。

- [ ] **334.6 保護既有 Yahoo 抓取路徑不被自己關掉。**
      `FundamentalObservationStore.coverageNeed(...)`（方法宣告在 `:129`，valuation 判斷區塊為
      `:152-167`）判斷 valuation 是否仍需 fallback 時，**須排除 `SEC_DERIVED` 這個 provider**，
      並在該處加註原因。
  - 不處理的後果：`SEC_DERIVED` 每日寫入會讓 valuation coverage 恆為「已滿足」，
    `StockFundamentalPoller`（`:181-196`，只在 `need.valuation()` 為真時才 `fetchYahoo`）
    就此停止向 Yahoo 抓美股當期估值快照——等於用自己推導的值把唯一的一手觀測來源關掉。
    有**兩條**都會造成「已滿足」的路徑：(i) `fresh(...)` 通過且正 PE 筆數 ≥ `PE_MIN_SAMPLES`；
    (ii) `:161` 的 `if (Boolean.TRUE.equals(latest.loss())) return true;`——**虧損旗標不需要任何筆數**
    就會讓判定為已滿足。整個 provider 排除可同時堵住兩條。
  - 註解要寫清楚判準：`coverageNeed` 的用途是「**還需不需要再向外抓一手觀測值**」，
    而推導值在定義上無法回答這個問題。
  - **EPS／ROE／營收三項的 coverage 判斷不得受影響**（本任務不寫 `stock_financial_quarter` 的
    新 provider，那三項的 provider 分組維持原狀）。

- [ ] **334.7 測試（與實作同一支任務，不得延後）。**
  - `external-materials-service`（單元測試，不啟 Spring context、不連 DB）：
    - (a) 累計→單季還原與 TTM 加總：含跨會計年度、Q1 直接取累計、缺季不落列、季別不連續不落列；
      並以與 backend `standaloneValue(...)` 相同的輸入斷言結果一致。
    - (b) 虧損季度：TTM EPS ≤ 0 → `pe_ratio` 為 `null`、`pe_loss_flag` 為 `true`、**該列仍產出**。
    - (c) point-in-time：某季 `filed` 之後的交易日才用得到該季；`filed` 之前的交易日只用更舊的季報
      （以「新季報存在但 filed 較晚」的 fixture 斷言舊日期的 PE 未被新季報影響）。
    - (d) 殖利率：無任何除息紀錄 → `dividend_yield_pct` 為 `null`（**不是 0**）；
      有紀錄但近 365 日無除息 → `0`；跨 365 日邊界的除息列正確納入／排除。
    - (d2) 殖利率去重三型態各一案：①`QQQ|2023-03-20` 的 `0.472000`／`0.472200`（比值 1.0004）
      → 只計一次、取 `0.472200`，**不得**回 `null`；②`AVGO|2023-06-21` 的 `0.46`／`4.60`（比值 10、
      與鄰近除息日一致）→ 只計 `0.46` 一次；③`AVGO|2018-03-21` 的 `0.35`／`1.75`（比值 5，與鄰近
      `2017-12-18` 解出的 10 不一致）→ 該日 `dividend_yield_pct` 為 `null`（**這一案是反例錨點，
      缺它等於沒測到重點**）。
    - (b2) 每股基準閘門：單季推導股數序列出現 ≥1.8 倍跳階時，**跨越該點的 TTM 視窗不落列**，
      且落地序列的最早日期晚於該變動點；另構造「跨分割日的相鄰兩個交易日」斷言其 PE **連續、無倍數跳階**。
    - (b2b) 口徑錨點：以 **4:1** 分割的 fixture 斷言其被判為變動點（若實作誤用 TTM 口徑，
      四步比值最大只有 1.75、這一案必然失敗——**缺這一案，單季／TTM 的歧義會活過測試**，
      因為 10:1 fixture 在兩種口徑下都會通過）。
    - (b3) `net_income_parent` 為 null 的季度被視為基準不可驗證，會終止可用區段（fail closed），
      而不是被略過後繼續往更舊的季度延伸。
    - (c2) `effective_available_at` 單調化：以 `MSFT` 三個 Q4 共用同一 filed、以及 `AMZN` 2025Q2／2025Q3
      日期倒置的實測值構造 fixture，斷言 (i) 單調化後較舊期別的有效可見時點不晚於較新期別，
      (ii) 產出的 TTM EPS 序列**不出現單日跨 4 季的跳階**。
    - (e) PB：`equity_parent` 為 null、TTM EPS 為 0、每股淨值 ≤ 0 三種情形皆為 `null`；
      正常情形以手算值斷言（含美股 USD 單位不做千元換算）。
    - (f) 落地欄位：`provider='SEC_DERIVED'`、`availability_basis='RECONSTRUCTED'`、`market='美股'`；
      `source_available_at` 為「最新那一季的 **`effective_available_at`**（單調化後）」與「D 收盤時刻」
      的較晚者——以 MSFT 三個 Q4 共用 raw filed 的 fixture 斷言落地值**不會**塌成同一天。
    - (g) 重跑冪等：同一區間跑兩次，第二次 `append` 回傳新增列數為 0。
    - (h) `coverageNeed(...)`：valuation 列全部為 `SEC_DERIVED` 時仍判定為「需要 fallback」（`true`）；
      同一組資料換成 `YAHOO` 且筆數 ≥250 時判定為不需要（`false`）。
  - `backend`：`FundamentalAnalysisService` 既有測試須新增一案——provider 為 `SEC_DERIVED`
    且有 ≥250 筆有效歷史時，`valuationComponent(...)` 能算出分位且回傳的 `provider` 為 `SEC_DERIVED`。
  - **Mockito 相關**：本專案在 Java 25 下須以 `-DextraArgLine=-Dnet.bytebuddy.experimental=true` 執行，
    **不得**使用 `-DargLine`（會覆蓋掉時區設定）。`external-materials-service/pom.xml` 與
    `backend/pom.xml` 皆已 wiring `extraArgLine`。

- [ ] **334.8 建置與部署驗證**（見下方「驗證」段的完整指令）。

## 驗證

### 建置與測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
```

### 部署（本專案沒有 dev server；「改好」＝ image rebuild ＋ container recreate）

> **JVM service 一律 `--no-cache`**：cached build 可能產出不含本次變更的 stale jar（前端卻會更新），
> 症狀是「程式碼已改、行為沒變」。build 前先確認 worktree 有 `.env`（`env_file` 相對 compose 檔解析）。

```bash
docker compose -p asset-management build --no-cache external-materials-service business-services bff
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services bff
```

> recreate `business-services` 會換 IP，**必須連同 `bff` 一起 recreate／restart**，
> 否則 BFF 握著舊 IP 回 500 且 ≥3 分鐘不自癒（Docker DNS TTL 600s），business log 乾淨、錯只在 bff log。

### 實機查證（唯讀）

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT provider, count(DISTINCT stock_code), count(*), min(trading_date), max(trading_date) FROM stock_valuation_daily WHERE market='美股' GROUP BY 1;\""
```

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT stock_code, count(*), min(fiscal_year||'Q'||fiscal_quarter), max(fiscal_year||'Q'||fiscal_quarter) FROM stock_financial_quarter WHERE market='美股' GROUP BY 1 ORDER BY 1;\""
```

```bash
docker compose -p asset-management exec -T business-services curl -fsS -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' http://localhost:8080/api/trading-radar
```

```bash
docker compose -p asset-management exec -T postgres sh -c "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -At -F'|' -c \"SELECT stock_code, min(trading_date), max(trading_date), count(*) FROM stock_valuation_daily WHERE market='美股' AND provider='SEC_DERIVED' GROUP BY 1 ORDER BY 1;\""
```

**通過判準（逐項可獨立判斷，數字須據實回報而非照抄本檔）：**

1. `stock_valuation_daily` 出現 `provider='SEC_DERIVED'` 的美股列。**實際涵蓋哪幾檔以實測為準**：
   預期上限為 6 檔（AMZN／NVDA／AVGO／MSFT／GOOGL／COIN，**不含 TSM**），但受每股基準閘門與
   250 筆門檻限制，**AVGO 很可能不在其中**（`net_income_parent` 10 季只有 1 季非 null）。
   未覆蓋者維持 MISSING 是正確結果，**不得為了達成本判準而放寬基準檢查或門檻**。
2. 對**實際覆蓋到的**標的，`GET /api/trading-radar` 的 `VALUATION.components` 中 `pe`（以及
   `equity_parent` 可得者的 `pb`）由 MISSING 轉 **AVAILABLE**，且該 component 的 `provider` 顯示為
   `SEC_DERIVED`。`dividend_yield` 只對**實際落地且有除息紀錄**的標的（預期為 MSFT／NVDA／GOOGL）
   可轉 AVAILABLE；**AMZN／COIN 因無除息紀錄維持 MISSING**；**AVGO 依判準 1 預期整檔無列**
   （最新季 2026Q2 的 `net_income_parent` 即為 null，由新到舊掃描第一季就 fail closed），
   故其三個 component 全數維持 MISSING。
3. **TSM 三個 component 維持 MISSING**，`missingReason` 不變。
3b. **基準閘門確實生效**：抽驗至少一檔曾分割的標的（AMZN 2022-06-06 20:1／GOOGL 2022-07-18 20:1／
   NVDA 2024-06-10 10:1 擇一），其 `SEC_DERIVED` 序列的 `min(trading_date)` **晚於**該次分割日；
   若早於，代表閘門沒生效、序列已被污染，**必須修掉才算通過**。
4. 台股 4 檔（2330／2881／2885／2891）三個 component 維持 AVAILABLE 且 `provider` **不是** `SEC_DERIVED`
   （不得因本次改動而讓台股改吃推導值）。
5. 21 檔 ETF 的 `VALUATION` 維持 `NOT_APPLICABLE`。
6. `ruleVersion` 維持 `TW_RULES_V12`、`actionPolicyVersion` 維持 `EVIDENCE_GATE_V1`。
7. 重跑一次排程（或重啟服務觸發自癒）後，`stock_valuation_daily` 的 `SEC_DERIVED` 列數
   **不再增加**（冪等）。
8. `external-materials-service` 的日誌可見本輪推導的 INFO 收尾行，且無 `BadSqlGrammar`／
   `Connection refused`／HTTP 500。

## 完成報告

**2026-08-15 完成，已實機部署並驗證。**

### 落地內容

- **external-materials-service**：新增 `UsValuationDerivationService`（推導本體）、
  `UsValuationDerivationScheduler`（`0 30 7 * * TUE-SAT` ＋ 開機自癒，共用同一支 `runGuarded`）、
  `DividendHistoryQuery`（唯讀、強制 `event_status='ACTIVE'`）；
  `StockFundamentalFetchClient` 的 `SEC_LOOKBACK_YEARS` 3 → 11、新增 `SEC_DERIVED` 常數；
  `FundamentalObservationStore` 新增五支查詢／刪除方法並在 `coverageNeed` 排除 `SEC_DERIVED`；
  `StockSourceQuery` 新增 `loadAllCloses`。
- **backend**：`FundamentalAnalysisService` 的 `PROVIDERS` 末端加 `SEC_DERIVED`，
  `latestLoss(...)` 明確排除 `SEC_DERIVED`。**未新增任何對外 HTTP。**
- **bff**：`SchedulePublicBffController` 新增一筆，總計 53 ＝ business 21 ＋ external 32。
- **frontend**：`valuationEvidence.js` 拆出 `sourceLinks`／`sourceNotes`，`TradingRadarView.vue`
  只把 http/https 當連結、`derived://` 標記改以「來源標記：…」純文字揭露（不丟掉推導性質的揭露）。

### 三輪對抗式查證後才定案的兩個修正（原規劃是錯的，已一併改進 design.md）

1. **單調化消不掉 SEC 比較期別的系統性位移。** 實測 GOOGL 各季 `source_available_at` 距期末達
   388–401 天（僅最新四季 23–36 天），該位移對期別保序、`min` 取不掉。改在**寫入端**追蹤
   **最早** `filed` 當 `availableAt`（值仍取最新 `filed` 那一筆以保留重述後的正確數字）。
2. **`filedInstant` 12:00 UTC ＝ 08:00 ET 是開盤前**，造成每季一天的 look-ahead。改為 16:30 ET。

另修正：分割後約四季內「刪除重寫」不可能觸發（序列為空就提前 return）、
分割倍數眾數可被單一除息日自我驗證（改要求 ≥2 個除息日支持）。

### 測試

- `external-materials-service` **381 通過／0 失敗**（新增 `UsValuationDerivationServiceTest` 22 案、
  `UsValuationDerivationSchedulerTest` 2 案、`StockFundamentalFetchClientTest` +2、
  `FundamentalObservationStoreTest` +4）
- `backend` **979 通過／0 失敗**（`FundamentalAnalysisServiceTest` +2）
- `bff` **89 通過／0 失敗**
- frontend `node --test` 9 通過

### 實機驗證（2026-08-15 19:14 Asia/Taipei，四個 image `--no-cache` 重建 ＋ recreate）

開機自癒實際輸出：

```
SEC EDGAR 季報重抓完成（startup）：6 檔、新增 181 列、失敗 0 檔
美股估值推導完成：處理 6 檔、新增 4347 列、刪除重寫 0 列、略過 2 檔
  （AVGO：無可用推導區段；COIN：無可用推導區段）
```

`stock_financial_quarter` 美股由「6 檔各 9–12 季、皆起 2024Q1」加深為
GOOGL 52 季（起 **2016Q1**）／MSFT 44／NVDA 38／AMZN 36／COIN 36／AVGO 36。

`stock_valuation_daily` 的 `SEC_DERIVED` 實際落地：

| 標的 | 列數 | 起訖 | pe 非空 | pb 非空 | 殖利率非空 |
|---|---|---|---|---|---|
| MSFT | 2078 | 2017-08-03 ~ 2026-08-14 | 2078 | 2078 | 2078 |
| GOOGL | 1017 | 2022-07-28 ~ 2026-08-14 | 1017 | 1017 | 548 |
| AMZN | 760 | 2023-08-07 ~ 2026-08-14 | 760 | 760 | **0** |
| NVDA | 492 | 2024-08-29 ~ 2026-08-14 | 492 | 492 | 365 |

**每股基準閘門確實生效（判準 3b 通過）**：三檔曾分割標的的序列最早日**都晚於**其最近一次分割——
GOOGL 2022-07-28 晚於 2022-07-18 的 20:1、AMZN 2023-08-07 晚於 2022-06-06 的 20:1、
NVDA 2024-08-29 晚於 2024-06-10 的 10:1。

`GET /api/trading-radar`（容器內、帶 `X-User-Id: 1`）前後對照：

| component | 前 | 後 |
|---|---|---|
| `pe` | AVAILABLE 4／N/A 21／MISSING 7 | **AVAILABLE 8**／N/A 21／MISSING 3 |
| `pb` | AVAILABLE 4／N/A 21／MISSING 7 | **AVAILABLE 8**／N/A 21／MISSING 3 |
| `dividend_yield` | AVAILABLE 4／N/A 21／MISSING 7 | **AVAILABLE 7**／N/A 21／MISSING 4 |

`mediumEvidenceConfidence`：**MSFT 78→100、GOOGL 78→100、AMZN 78→93、NVDA 56→78**；
TSM 56、AVGO 67、COIN 78 維持不變。台股 4 檔的 provider 仍為 `FINMIND`（未被推導值取代），
新 AVAILABLE 的 4 檔 provider 顯示為 `SEC_DERIVED`。`ruleVersion` 維持 `TW_RULES_V12`、
`actionPolicyVersion` 維持 `EVIDENCE_GATE_V1`。business／external／bff 日誌零
`BadSqlGrammar`／`Connection refused`／500。

### 實際未覆蓋的標的與理由（判準 1 要求據實回報）

- **TSM**：`companyfacts` 完全無列（外國發行人走 20-F）——如規劃所預期。
- **AVGO**：`net_income_parent` 36 季中僅 4 季非 null、`equity_parent` 全 null → 推導股數幾乎算不出
  → 基準不可驗證 → fail closed——如規劃所預期。
- **COIN**：**規劃時預期可覆蓋，實際落空。** 36 季資料完整，但區間內有單季 EPS／淨利為負的虧損季，
  依閘門 fail-closed 條款 (iii) 截斷可用區段，剩餘不足 250 筆。**這是規則正確運作的結果，不是缺陷**；
  已登記於 `t333_radar_gap_backlog_2026_08_15.md`。
- **AMZN 的 `dividend_yield` 維持 MISSING**：`stock_dividend_history` 零列（從未配息），
  依規定寫 `NULL`（未知）而非 0，故 component 為 MISSING——誠實揭露，如規劃所預期。

### 不宣稱的事

本任務的交付是**補資料與補接線**，不是規則變更：`PE_MIN_SAMPLES=250`、evidence gate 的 70%／70 分、
所有規則參數與 `RULE_VERSION` 一律未動。**本次改動不宣稱能提升報酬或降低風險**——它讓原本整組空白的
估值證據變成有值且來源可追溯，證據覆蓋率與信心度的提升是可量測的事實，投資結果則不是本任務的主張。
