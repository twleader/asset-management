# [t276] 把已落地但未使用的技術指標與成交量納入交易雷達評分

> **⛔ 本任務已由 t291 取代，不得依本檔實作。** t291 依 2026-08-08 使用者最新需求，把本任務與雙持有期、台股大盤量能、前一美股科技交易日、台美公開資訊、債券 ETF 匯率及不追高殺低一起定案；本檔只保留歷史問題診斷。

**對應 Requirements:** Requirement 59（把已落地但未使用的技術指標與成交量納入交易雷達評分——MACD／RSI／W%R／J9／BIAS 差值與量價述詞，納入與否一律以量測結果為準）
**前置任務:** t273（規則回測框架——各指標納入與否、量價述詞是否有效，必須引用其量測輸出）
**Liquibase changeset:** 無（本任務使用的全部資料皆已落地：各技術指標由 `TechnicalIndicatorService` 計算、`volume` 為 `stock_price_history` 既有欄位）

## 背景

### 缺口是「已有資料未使用」，不是「缺資料」

`TechnicalIndicatorService`（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`，Task 261／262）的 `IndicatorPoint` record 已對每個交易日算出並輸出下列全部欄位：

```
ma5, ma20, ma60, ma240, k, d, j9, k3d2, rsv,
ema12, ema26, dif, macd, osc, rsi5, rsi10, bias10, bias20, b10b20, wr9
```

而 `TradingRadarRuleEngine.StockInput` 接收的技術欄位只有：`indicators`（內含 `ma20`／`ma60`／`ma240`／`k`／`d`）、`previousK`、`previousD`、三條均線的 `Confirmation`、`ma60BiasPercent`、`ma240BiasPercent`、`week52Position`、`kdBandWidthPercent`。

**`MACD` 一族、`RSI5`／`RSI10`、`W%R9`、`J9`、`K3D2`、`BIAS10`／`BIAS20`／`B10−B20` 一個都沒有進入評分鏈。**

> **注意兩條路徑的差異**：`IndicatorPoint`（逐日序列，供走勢圖）含全部指標，但交易雷達走的是另一支 `computeFromSeries(series)` → `FullIndicators`，後者只有 8 個欄位（`monthlyMa`／`quarterlyMa`／`annualMa`／`k`／`d`／`previousK`／`previousD`／`weeklyMa`）。要讓雷達拿到 MACD／RSI／W%R，須**擴充 `FullIndicators` 的輸出**（新增欄位），而非改動既有欄位的計算。

`stock_price_history.volume` 欄位有完整資料——實測（2026-08-01）154,834 筆中 **null 為 0 筆**，僅 **229 筆為 0**。而 14 個因子中**沒有任何一個用到成交量**。

### 為什麼優先評估 RSI 與 W%R

`RSI` 與 `W%R` 的值域本就是 `0–100` 且由該標的**自身**的漲跌幅結構決定，不像季線乖離採固定百分比。t274 的背景段已實測（還原序列、暖機 240）：`|bias|>12%` 在 00697B 為 0.00%、在 2327 為 40.89%。`RSI`／`W%R` 天生免疫這個尺度問題，對債券 ETF 這類低波動標的仍能產生有意義的相對位置判定。

## 要做什麼

### 276.1 擴充 `FullIndicators` 的輸出（新增，不改既有）

- [ ] 276.1 `TechnicalIndicatorService.FullIndicators` 新增雷達需要的指標欄位，由 `computeFromSeries(series)` 一併算出。

  - [ ] 276.1.1 **不得改動 `TechnicalIndicatorService` 既有的計算結果。** Task 262 已把 `K9`／`D9`／`J9`／`RSI5`／`RSI10`／`BIAS10`／`BIAS20`／`B10−B20`／`W%R9` 共 9 個值與 Yahoo 股市台股頁**逐位比對**（2330 於 2026-07-31），該同源判準綁在既有輸出上。若雷達需要目前未輸出的中間值，一律**新增輸出**而非修改既有輸出。
  - [ ] 276.1.2 沿用既有的計算慣例，不得另造第二套：
    - **RSI 採 Wilder 平滑**，不是簡單移動平均。Task 262 已實證：2330 於 2026-07-31，Wilder 得 `RSI5 = 65.78`／`RSI10 = 56.53`（與 Yahoo 逐位相同），簡單移動平均得 `60.00`／`61.95`。**兩者 RSI10 差 5.4 點，選錯會直接給出錯誤數字。**
    - **MACD 的價基是 DI ＝ `(H + L + 2C) / 4`（需求指數），不是收盤價**——這是台股看盤軟體的慣例。
    - **EMA 以「前 n 筆 DI 的簡單平均」作 seed，之前為 `null`**（`ema12` 前 11 筆、`ema26` 前 25 筆、`macd` 前 33 筆為 null）。**不可用 `DI[0]` 當 seed**：本專案序列首筆日期就約等於視窗起點、沒有暖機緩衝，用 `DI[0]` 會讓 `dif[0] = macd[0] = osc[0] = 0` 而產生假的「從 0 張開」序列。
    - **參數對齊 Yahoo 股市台股頁**：`RSI` 為 **5／10**（**不是**國際慣用的 6／12）。
  - [ ] 276.1.3 雷達路徑餵入的是**還原權息且還原分割後**的序列（`TradingRadarService` 既有的 `adjustedRows`），新增指標一律以同一序列計算，**不得混用原始序列**。

### 276.2 成交量因子

- [ ] 276.2.1 **不得直接把 `volume` 當因子。** 成交量的量級跨標的差異極大（實測 0050 分割後 30 日中位數 78,953,424，而多數個股在百萬級），絕對值不可比較。一律以相對量表示：

  ```
  volumeRatio = volume ÷ (近 N 個交易日的成交量中位數)
  ```

- [ ] 276.2.2 **必須用中位數，不得用平均數。** 除權息日、法人調節日的爆量會把平均數整個拉高，使其後數月的「爆量」都判不出來。

- [ ] 276.2.3 **⚠ 股票分割會讓 `volumeRatio` 產生持續數月的假爆量——這是本任務最容易靜默出錯的地方。**

  `DistributionAdjustedPriceService.adjust()` 對 OHLC 四個價格欄位做縮放，但第 237 行為 `.volume(source.getVolume())`——**成交量原樣帶過，完全不調整**。分割後股數變多，成交量的量級隨之跳增。

  **實測證據（0050，2025-06-18 的 1:4 分割）**：

  | 區間 | 交易日數 | 成交量中位數 | 平均收盤價 |
  |---|---|---|---|
  | 分割前 30 日（2025-05-05 ~ 06-17） | 26 | **12,956,054** | 179.27 |
  | 分割後 30 日（2025-06-18 ~ 07-30） | 31 | **78,953,424** | 49.28 |

  中位數比值為 **6.09 倍**。若 `volumeRatio` 的視窗跨越 2025-06-18，分割後的每一天都會被算出 `ratio ≈ 6` 而被判為「爆量」，**持續約 N 個交易日**，且不會拋任何例外。

  **⚠ 修法 (a) 不是「改一行」——現有的 `scale` 是分割與配息合流後的因子，直接拿來縮放 `volume`會把配息也套進成交量。** 逐行證據（`DistributionAdjustedPriceService`）：

  ```java
  :51   private record FactorEvent(LocalDate date, BigDecimal factor, boolean split) {}
  :66   List<FactorEvent> events = new ArrayList<>(detectSplits(rowsAsc, rawEvents));   // split = true
  :75-76 rawEvents…forEach(e -> events.add(new FactorEvent(…, dividendFactor(…), false))); // split = false
  :97-98 if (factor != null && …) { shares = shares.multiply(factor); }                 // 兩種因子連乘
  :113  BigDecimal scale = sharesByRow.get(i).divide(finalShares, SCALE, HALF_UP);
  :114  adjustedAsc.add(adjust(rowsAsc.get(i), scale));
  :237  .volume(source.getVolume())                                                     // 原樣帶過
  ```

  `dividendFactor()`（`:212-221`）＝ `1 + 股票股利/10 + 現金股利/當日收盤`。若在 `:237` 以 `scale` 的倒數縮放 `volume`，**配息因子會一併套用**——這與本節「現金配息不影響 `volume`」直接抵觸，且十年累積配息因子在月配息債券 ETF 上可達 1.3 倍以上，會把久遠年份的 `volume` 系統性放大而**不拋任何例外**，正是本任務想防的那類靜默錯誤。

  **兩種修法，實作者擇一並在註解說明**：
  - **(a)（建議）把分割還原擴及 `volume`，但必須另建一條「只含分割」的累積因子**：
    - 在既有的 `sharesByRow` 迴圈旁，另累積一條**只在 `event.split() == true` 時相乘**的 `splitSharesByRow`（`FactorEvent` 已有 `split()` 判別子，見上方 `:51`，不需新增欄位）。
    - `adjust()` 增加一個 `splitScale` 參數；`volume` 以 `1 / splitScale` 縮放（價格 ÷4 則成交量 ×4），四個價格欄位仍用原本的混合 `scale`。
    - **嚴禁沿用既有的混合 `scale` 縮放 `volume`。**
    - **消費端風險已查證（2026-08-01）**：`adjust()` 的唯一 production 呼叫端是 `TradingRadarService.java:392`（`grep -ran "adjustedPriceService" backend/src/main`），其餘 `getVolume()` 的呼叫端（`PriceQueryService:249`、`WatchStockService:176-179`、`ExcelExportService:866`）都是直接讀 `StockPriceHistory` entity、**不經本服務**，故改動不會外溢。實作時仍須重跑此 grep 確認現況未變。
  - **(b)** 在 `volumeRatio` 的視窗內偵測分割，跨越分割日時該因子回 `null`。較保守、改動面最小，但會讓分割後數月完全拿不到量能因子。

  > 現金配息**不影響** `volume`，故只有分割是問題。分割偵測沿用 Task 265 既有的序列啟發式（`ratio ≥ 2.0`／`≤ 0.5` ＋「接近 `{2,3,4,5,10}` 之一、誤差 ≤ 10%」二次驗證），**門檻不得放寬**——±15% 以上的跳空實測 21 筆中僅 2 筆為真分割，小門檻會把序列改壞，而**改壞序列比不還原更糟**（前者無法從畫面察覺）。

- [ ] 276.2.4 **缺值處理**：`volume` 為 0 或 null 時（實測 229 筆為 0，多為停牌或無成交）該因子回 `null`，權重由 `Accumulator` 重分配。**不得**視為「量縮」——`Accumulator.add(weight, contribution)` 在 `contribution == null` 時直接 return 不累加 `sumW`，傳 `0` 則會被當成中性值計入。近 N 日成交量樣本不足時同樣回 `null`。

- [ ] 276.2.5 **`0000`（大盤）不引入量能因子**：實測 `0000` 在 `stock_price_history` 的 `volume` 全部為 0（大盤指數本無成交量）。`evaluateMarket()` 不得加入量能因子。（`0000` 本就被 `TradingRadarService.assemble()` 的 filter 排除於個股表之外。）

### 276.3 量價述詞須先經 t273 驗證才納入

- [ ] 276.3 下列三個量價述詞，**各自作為 t273 的量測述詞先行驗證，通過者才納入評分**：

  | 述詞 | 定義 |
  |---|---|
  | **突破帶量 vs 無量假突破** | 價格站上均線（`Confirmation` 由非 `ABOVE` 轉 `ABOVE`）當日的 `volumeRatio` 高低 |
  | **下跌縮量 vs 爆量長黑** | 單日跌幅達門檻時的 `volumeRatio` 高低 |
  | **量價背離** | 價格創近期新高但 `volumeRatio` 未同步放大 |

  > 使用者明示量價背離「是比 KD 死叉更早的頂部訊號」。這是待驗證的假設，不是既定事實——須由 t273 的 `+5`／`+20`／`+60`／`+240` 四個 horizon 量測後決定是否納入，以及納入哪一軌。

### 276.4 指標的納入與否一律以量測為準

- [ ] 276.4 至少須評估下列各項，並依 t273 的量測結果決定是否納入：`RSI5`／`RSI10`、`MACD` 一族（`DIF`／`MACD`／`OSC` 及其交叉）、`W%R9`、`J9`、`BIAS10 − BIAS20`。

  - **量測顯示無效者不得納入**——因子數量增加會稀釋既有因子的權重，代價是實質的。
  - **未納入者須在完成報告中列出其量測數字與不納入的理由**，避免日後有人重複評估同一件事。

### 276.5 權重重配與升版

- [ ] 276.5.1 納入新因子必然要重配全部權重，合計須維持 `1.00`（由 `WEIGHT_SUM` 常數供測試斷言釘住，**不得靠人工加總**）。

- [ ] 276.5.2 既有測試的期望分數會隨之改變——**須依新權重重算後更新期望值，並在完成報告列出新舊對照表。嚴禁為了讓舊測試通過而回頭改權重。**

- [ ] 276.5.3 `RULE_VERSION` 升版一級。**同步點共五處**，以 `grep -ran "<現行版本字串>"` 取得（`-a` 不可省略：本專案有 `.java` 檔被 `file(1)` 誤判為 data，普通 `grep -r` 會整檔跳過）：

  | # | 檔案 | 位置 |
  |---|---|---|
  | 1 | `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` | `RULE_VERSION` 常數本體 |
  | 2 | `frontend/src/views/TradingRadarView.vue` | `radar` ref 的初始值 |
  | 3 | `frontend/src/views/TradingRadarView.vue` | 顯示 fallback |
  | 4 | `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` | class Javadoc |
  | 5 | `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java` | 版本斷言，**連同其測試方法名一併改** |

  > **⚠ grep 的命中數多於同步點數。** 以 `TW_RULES_V9` 實測為例，`grep -ran` 回 **7 個命中、分布在 5 個檔案**，但只有上表 5 處該改。另外兩個是 `TradingRadarRuleEngine.java` 權重表上方的說明註解（歷史敘述，改不改皆可），以及 **`backend/src/main/resources/db/changelog/changes/v1.83.0-radar-notification-rule-version.sql` 的 `--comment`——絕對不可改**：Liquibase 的 checksum **包含註解**，改了會 `ValidationFailed`、讓 business-services 進入 crash loop。**做批次取代的 `sed` 必須排除 `db/changelog/`。**

  > **實作前必須先 `grep -ran "RULE_VERSION"` 讀取當下的實際值**：t274 與 t275 也各會升版一級，本任務的起始版本取決於落地順序，不得依本檔假設。

- [ ] 276.5.4 **通知基準會自動重建**：`trading_radar_notification_setting.rule_version` 與現行 `RULE_VERSION` 不符時視同未初始化（機制由 Task 264 的 `v1.83.0-radar-notification-rule-version.sql` 建立），升級後首輪評估一律只建基準不寄信。須實際確認生效，否則升級首輪會對每筆訂閱狂發假通知。

### 276.6 不得做的事

- [ ] 276.6 **不得新增 Liquibase changeset**（無新資料表、無新欄位）、**不得新增外部抓取路徑**、**不得改動 `TechnicalIndicatorService` 既有輸出的計算結果**（見 276.1.1）。

## 驗證

### 單元測試

- [ ] **(a) `WEIGHT_SUM` 為 `1.00`**（由常數加總斷言，不得寫死數字）。
- [ ] **(b) `volume` 為 0／null 時量能因子回 `null` 且權重被重分配**——斷言分數與「該因子不存在」的結果相同（守住不得傳 0）。
- [ ] **(c) `volumeRatio` 用中位數而非平均數**：以一段含單日極端爆量的構造序列斷言，兩種算法的結果須明顯不同（若實作誤用平均數，此測試必失敗）。
- [ ] **(d) 分割不造成假爆量（本任務最重要的探針）**：以含 1:4 分割的構造序列，斷言分割後各日的 `volumeRatio` **不會**被判為爆量。可直接用實測錨點驗證——0050 分割前 30 日成交量中位數 `12,956,054`、分割後 30 日 `78,953,424`（比值 6.09），修正後跨越分割日的 `volumeRatio` 須回到接近 1 的量級（採 276.2.3 的 (b) 方案則為 `null`）。
- [ ] **(e) RSI 必須能辨別平滑方式**：以**寫死的收盤序列**斷言 `RSI5`／`RSI10` 的具體數值，且該序列要讓 Wilder 與簡單移動平均的結果明顯不同。可用 Task 262 已實證的錨點：2330 的完整收盤序列在 Wilder 下得 `RSI5 = 65.78`、`RSI10 = 56.53`（簡單平均為 `60.00`／`61.95`）。
- [ ] **(f) MACD 自洽**：對任一非 null 的點，`dif == ema12 − ema26`（容差 0.01）、`osc == dif − macd`（同容差）。
- [ ] **(g) 威廉恆等式**：對每個非 null 的點，`wr9 == 100 − rsv`（容差 0.01）。
- [ ] **(h) 新納入的每個指標各有一組直接斷言其貢獻方向的測試**（例如 RSI 高檔時該因子貢獻為負）。
- [ ] **(i) `TechnicalIndicatorService` 既有輸出未被改動**（回歸）：含 Task 262 的 Yahoo 同源錨點值（2330 於 2026-07-31 的 `K9`／`D9`／`J9` 42.65／32.92／13.45、`RSI5`／`RSI10` 65.78／56.53、`BIAS10`／`BIAS20`／`B10−B20` 3.88／1.83／2.05、`W%R9` 7.55；MACD 四值採容差 0.15）。
- [ ] **(j) 若採 276.2.3 的 (a) 方案，兩條探針缺一不可**：
  - (j1) 對**無任何事件**的序列，`volume` 逐筆與原始相同；
  - (j2) **對「有現金配息但無分割」的序列，`volume` 逐筆與原始相同**——這條是「誤用混合 `scale`」的唯一探針。若實作沿用了含配息的 `scale`，(j1) 仍會通過（無事件時 `scale` 恆為 1），只有 (j2) 會失敗。
  - (j3) 對含 1:4 分割的序列，分割前的 `volume` 恰為原始的 4 倍、分割後不變。
- [ ] **(k) `RULE_VERSION` 已升版**，測試方法名已改。

### 建置與部署

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
```

```bash
docker compose -p asset-management restart bff
```

### 實資料驗收

分割假爆量的實資料複驗（0050 的視窗跨越 2025-06-18）：

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT CASE WHEN trading_date < '2025-06-18' THEN 'pre' ELSE 'post' END seg, count(*), percentile_cont(0.5) WITHIN GROUP (ORDER BY volume)::bigint median_vol FROM stock_price_history WHERE stock_code='0050' AND market='台股' AND trading_date BETWEEN '2025-05-05' AND '2025-07-30' GROUP BY 1;"
```

- [ ] 以 t273 的回測端點對 0050 重跑，確認 2025-06-18 之後的數十個交易日**沒有**被量價述詞判為連續爆量。

```bash
docker exec asset-business-services curl -s -X POST "http://localhost:8080/internal/backtest/rules" -H 'Content-Type: application/json' -d '{"codes":["0050"]}' | head -c 3000
```

### 通知基準重建驗收

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT rule_version, count(*) FROM trading_radar_notification_setting GROUP BY rule_version;"
```

- [ ] 升版後首輪評估只建基準、未寄信（須實查 log 確認無該輪寄信紀錄）。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。**必須包含**：(1) 每個候選指標的 t273 量測數字與納入／不納入的決定及理由；(2) 三個量價述詞的量測結果；(3) 既有測試期望值的新舊對照表；(4) 分割假爆量的修法選擇 (a)／(b) 與其理由。）
