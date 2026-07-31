# [t262] 走勢圖 KD 子圖加上技術指標下拉選單：MACD／RSI／乖離率／威廉指標

**對應 Requirements:** Requirement 13（股票走勢圖延伸資訊）
**前置任務:** Task 261（指標上收後端、`indicatorSeries()` 與 `/api/market-data/indicators/series` 已建立）
**Liquibase changeset:** 無（不動資料庫）

## 背景

Task 261 把五個 KD 系列指標畫進子圖後，使用者反映「線圖很亂」，並指出外部看盤軟體（Yahoo 股市技術分析頁）的作法是**一個下拉選單、一次只畫一組指標**。

> ⚠️ **實作基準**：Task 262 必須在分支 `claude/stock-chart-indicators-1eb75a` 之上實作（僅 `5e1d541a` 尚未併入 main；`c7cdb677` 已隨 `444cd3e6` 併入）。若從 main 開分支，看到的會是舊座標（`grid[1].height: 140`／`grid[0].bottom: 250`／`legend[1].bottom: 206`），本檔引用的 155／283／231 全部對不上。
>
> 註：「亂」的**主因已在 Task 261 修掉**（子圖只有 90px 導致三條線擠成一團 → 加高到 155px、上下 pane 改為約 58:42）。本任務做的是使用者同時提出的另一件事：**指標可切換**。兩者是獨立的改善，不要把本任務當成「修亂」的手段。

Yahoo 的選單共 9 項（成交量／KD,J／MACD／RSI／乖離率／威廉指標／多空指標乖離／CDP／動向指標DMI）。**本任務只做其中四個新指標**（MACD／RSI／乖離率／威廉指標）＋既有的 KD,J，共 5 個選項——這四個都是純線圖（MACD 多一組柱狀），與現有子圖同形態，前端不必改渲染架構。使用者已明確選擇此範圍。

## 參數來源（重要：不採國際慣用值）

**全部對齊 Yahoo 股市台股頁**，2026-07-31 於 `https://tw.stock.yahoo.com/quote/2330.TW/technical-analysis` 逐一切換選單實測取得。使用者的判讀習慣建立在該畫面上，參數不同會導致同一檔股票在兩邊看到不同數字。

| 指標 | Yahoo legend 欄位 | 參數 | 2330 於 2026-07-31 實測值 |
|---|---|---|---|
| KD,J | K9／D9／J9 | 9 | 42.65／32.92／13.45 |
| MACD | EMA12／EMA26／DIF9／MACD | 12、26、DIF 平滑 9 | 2338.96／2360.89／−21.94／−8.23 |
| RSI | RSI5／RSI10 | **5、10**（**不是**國際慣用的 6／12） | 65.78／56.53 |
| 乖離率 | BIAS10／BIAS20／B10−B20 | 10、20 | 3.88／1.83／2.05 |
| 威廉指標 | W%R9 | 9 | 7.55 |

> 順帶佐證 Task 261 的 `J9 = 3D − 2K` 方向正確：`3 × 32.92 − 2 × 42.65 = 13.46 ≈ 13.45` ✓
>
> **上表同時是逐位比對的基準**（不只驗自洽）：以本專案 `stock_price_history` 的 2330 全序列實算，K9／D9／J9、RSI5／RSI10、BIAS10／BIAS20／B10−B20、W%R9 共 9 個值與 Yahoo **逐位相同**，證明我方 OHLC 與 Yahoo 同源。唯 MACD 四值因 DI 價基有 ≤0.12 殘差，採容差 0.15。另有可直接驗的自洽關係：`EMA12 − EMA26 = 2338.96 − 2360.89 = −21.93 ≈ DIF9`、`BIAS10 − BIAS20 = 3.88 − 1.83 = 2.05 = B10−B20`、`W%R9 = 100 − RSV9`（代數恆等式，非實測比對——Yahoo 未顯示 RSV 欄位）。

## 要做什麼

### 後端：`TechnicalIndicatorService`（`backend/src/main/java/com/steven/assets/service/TechnicalIndicatorService.java`）

現況重點（Task 261 建立，改動前確認仍屬實）：

- `IndicatorPoint` record 目前 9 個欄位：`tradingDate, ma20, ma60, ma240, k, d, j9, k3d2, rsv`。
- `indicatorSeries(code, market, start, end)` 取全史 asc（`0000` 走指數日線映射）→ `kdSeriesAsc(asc)` 得逐期 KD → 逐日組 `IndicatorPoint`，最後只回 `>= start` 的部分。
- `kdSeriesAsc()` 是**全站股票 KD 唯一的遞迴**，單點的 `stockKd()` 也走它取尾筆；`taiexKd()`／`AlertChartRenderer.calcKd()` 是另外兩套、本任務同樣**不動**。
- `maAt(asc, i, days)` 逐期算 MA，**累加方向為「新→舊」**，與 `simpleMa()` 一致（Task 262 新增的任何均值計算都必須沿用同方向，否則 double 加法不可結合會讓末位在 `x.xx5` 邊界翻面）。

- [x] 262.1 `IndicatorPoint` 擴充 11 個欄位（全部 `BigDecimal`，`setScale(2, HALF_UP)`，暖機不足為 `null`）：
  `ema12, ema26, dif, macd, osc, rsi5, rsi10, bias10, bias20, b10b20, wr9`。
  **`osc` 也是 2 位小數**（雖為柱狀，值域同 DIF）。

- [x] 262.2 新增 MACD 序列（private，吃 asc 序列回等長 list）：
  ```
  DI[i]   = (high[i] + low[i] + 2 × close[i]) / 4               ← 需求指數，非收盤價！
  EMAn[i] = i < n-1  ? null
          : i == n-1 ? SMA(DI[0..n-1])                           ← seed 用前 n 筆簡單平均
          : DI[i] × k + EMAn[i-1] × (1 − k)                      , k = 2 / (n + 1)
  DIF     = EMA12 − EMA26        ← 任一為 null 則 null（故前 25 筆為 null）
  MACD    = DIF 的 9 日 EMA（同一遞迴、同樣 SMA seed，故前 33 筆為 null）
  OSC     = DIF − MACD
  ```
  - **EMA 以「前 n 筆 DI 的簡單平均」作 seed，之前為 `null`**（`ema12` 前 11 筆、`ema26` 前 25 筆、`macd` 前 33 筆為 null）。
    ⚠️ **不可用 DI[0] 當 seed**：本專案 `stock_price_history` 只保留 10 年、而前端 `start` 也是 10 年前，序列首筆的日期就約等於 `start`，**返回區間內沒有暖機緩衝**。若 seed 取 DI[0]，則 `ema12[0] == ema26[0] == DI[0]` → `dif[0] = macd[0] = osc[0] = 0`，走勢圖最左端會出現「DIF/OSC 從 0 慢慢張開」的假象（EMA26 的 seed 殘留權重 `(1−2/27)^n`，約 60 個交易日後才衰減到 1% 以下）。改用 SMA seed ＋暖機 null 可完全消除，且與 RSI／BIAS 的暖機行為一致。
  - ⚠️ **MACD 的價基是 DI＝(H+L+2C)/4（需求指數），不是收盤價**——這是台股看盤軟體的慣例。以本專案 2330 全序列實算與 Yahoo 比對：

    | 價基 | EMA12 | 與 Yahoo(2338.96) 差 |
    |---|---|---|
    | 收盤價 | 2338.6794 | 0.2806 |
    | **DI=(H+L+2C)/4** | **2338.9583** | **0.0017**（四捨五入即 2338.96） |

    EMA26／DIF 同樣是 DI 基準較近（誤差 0.08／0.07，收盤基準為 0.91／0.64）。`high`／`low` 為 null 時 fallback `close`，沿用 `kdSeriesAsc()` 的既有缺值慣例（`0000` 大盤舊資料正是這種情況）。
    註：DI 基準下 EMA26／DIF／MACD 仍有 ≤0.12 的殘差（來源未查明，可能是 Yahoo 的序列起點或內部精度不同），故 262.7 與驗證段對 MACD 採**容差 0.15**，其餘指標逐位相符。
  - **續存未捨入的 double**，各欄輸出時才 `setScale(2, HALF_UP)`（與 `kdSeriesAsc` 對 j9／k3d2 的處理同一慣例，避免二次捨入）。
  - `dif`／`macd`／`osc` 三者的關係必須在捨入前成立：`osc` 用未捨入的 `dif − macd` 算完才捨入。

- [x] 262.3 新增 RSI 序列，**採 Wilder 平滑**（已用實機資料證實，見下表）：
  ```
  gain[i] = max(0, close[i] − close[i-1]) ; loss[i] = max(0, close[i-1] − close[i])
  avgGain[n] = 前 n 期 gain 的簡單平均（seed）
  avgGain[i] = (avgGain[i-1] × (n − 1) + gain[i]) / n     , i > n      ← Wilder 遞迴
  avgLoss 同理
  RSIn[i]  = i < n ? null : 100 − 100 / (1 + avgGain / avgLoss)
  avgLoss == 0 → 100 ；avgGain 與 avgLoss 同為 0（連續平盤）→ 50
  ```
  ⚠️ **不可用簡單移動平均**。以本專案 `stock_price_history` 的 2330 完整收盤序列實算（2026-07-31 收 2425，與 Yahoo 同值）：

  | | RSI5 | RSI10 |
  |---|---|---|
  | 簡單移動平均 | 60.00 | 61.95 |
  | **Wilder 平滑** | **65.78** | **56.53** |
  | Yahoo 實測 | 65.78 | 56.53 |

  Wilder 版逐位命中；RSI10 兩者相差 5.4 點，**選錯會直接給出錯誤數字**。
  Wilder 是由最舊往最新的單向遞迴，須從序列最早一筆起算——**沒有「視窗」可加總**，不要套用 `maAt` 的滑動視窗寫法。

- [x] 262.4 新增乖離率序列：`BIASn = (close − MAn) / MAn × 100`，n = 10、20。
  - MA10／MA20 用**與 `maAt` 相同的計算**（新→舊累加）；`maAt(asc, i, 20)` 已存在可直接用，MA10 比照呼叫 `maAt(asc, i, 10)`，**不要另寫一份均值函式**。
  - `MAn` 為 null（視窗不足）或為 0 時，該日 BIAS 為 `null`。
  - `b10b20 = bias10 − bias20`，任一為 null 則為 null；**用未捨入值相減後才捨入**。
  - ⚠️ BIAS **刻意接受 `maAt` 已捨入的 MA**（誤差 < 0.001 個百分點），這與 262.2「續存未捨入」的原則不同是有意的取捨——重用 `maAt` 才能保住 Task 261 的「新→舊」累加方向。**不得**為此改動 `maAt` 的輸出捨入，Task 261 的同源判準（尾筆 `ma*` 逐位等於 `computeAll()`）綁在上面。

- [x] 262.5 新增威廉指標：`wr9 = 100 − rsv9`。
  - **直接由 `kdSeriesAsc()` 已算出的 RSV 導出**，不另跑一次 9 日視窗掃描。`W%R9 = 100 − RSV9` 是**代數恆等式**：`(HH−C)/(HH−LL) = [(HH−LL)−(C−LL)]/(HH−LL) = 1 − RSV/100`，且兩者取同一個 9 日視窗、high/low 缺值同樣 fallback close、`HH==LL` 同樣取 50。（Yahoo 未顯示 RSV 欄位，故此式無獨立實測值可比對，但代數上嚴格成立。）
  - RSV 為 null（暖機不足 9 筆）時 `wr9` 亦為 null。
  - ⚠️ 若為此需要 `kdSeriesAsc` 回傳未捨入的 rsv，**不可改動該函式既有的輸出捨入行為**（Task 261 的同源判準綁在上面）；用已捨入的 rsv 反算即可（`100 − rsv` 的捨入誤差不會放大）。

- [x] 262.6 `indicatorSeries()` 把上述新欄位一併組進 `IndicatorPoint`。**既有 9 個欄位的值一個都不能變**——Task 261 的同源判準（尾筆 `k`/`d`/`ma*` 逐位等於 `computeAll()`）仍須成立。

- [x] 262.7 測試：在 `backend/src/test/java/com/steven/assets/service/` **既有的 Task 261 測試類**（`TechnicalIndicatorSeriesAlignmentTest`）內新增方法，或新增一支同目錄測試類（沿用 `@ExtendWith(MockitoExtension.class)` ＋ `@Mock` ＋ AssertJ ＋繁體中文方法名）。須涵蓋：
  - **既有欄位不回歸**：擴充後 `computeAll()` 與 `indicatorSeries()` 尾筆的 `k`/`d`/`ma20`/`ma60`/`ma240` 仍逐位相等（Task 261 的判準）。
  - **MACD 自洽**：對任一非 null 的點，`dif == ema12 − ema26`（容差 0.01，因三者各自捨入）、`osc == dif − macd`（同容差）。
  - **RSI 必須能辨別平滑方式（關鍵）**：以**寫死的收盤序列**斷言 RSI5／RSI10 的具體數值，且該序列要讓 Wilder 與簡單移動平均的結果明顯不同。
    ⚠️ **只測「連漲趨近 100／連跌趨近 0／不足 n 筆為 null」是不夠的**——這三條在兩種平滑下都成立，選錯了也照樣通過。
    可直接用本任務已實證的錨點：2330 的完整收盤序列在 Wilder 下得 `RSI5 = 65.78`、`RSI10 = 56.53`（簡單平均為 60.00／61.95）。測試可取一段足夠長的固定序列並把 Wilder 期望值寫死。
  - **BIAS 自洽**：`b10b20 == bias10 − bias20`（容差 0.01）；MA 不足視窗時為 null。
  - **威廉恆等式**：對每個非 null 的點，`wr9 == 100 − rsv`（容差 0.01）。

### BFF（`bff/src/main/java/com/steven/assets/bff/stockanalysis/`）

- [x] 262.8 `IndicatorPointDto` 補齊 11 個新欄位（record，欄名與 business 回應一致）。
- [x] 262.9 `ChartSeriesDto` 補對應的 11 個等長陣列欄位；`Latest` 補新指標的最新值（MACD **五值（含 `osc`）**、RSI 兩值、BIAS 三值、W%R 一值——`osc` 不進 legend，但 262.15.1 的 intraday 常數填滿要用它畫柱狀，漏了當日模式的 OSC 就畫不出來）與 **prev\*（供 legend 漲跌箭頭）**。
- [x] 262.10 `ChartSeriesAligner.align()` 把新欄位一併對齊。**沿用既有的 `column(dates, byDate, getter)` 機制**，不要另寫一套對齊邏輯；日期聯集與「`latest` 取指標序列本身尾筆」的規則不變。
- [x] 262.11 `ChartSeriesAlignerTest`：**先更新 `indicator(...)` helper**（`bff/src/test/.../ChartSeriesAlignerTest.java:30-37`）——它以**位置參數**建構 `IndicatorPointDto` record，欄位數一變即 canonical constructor 不符、**全部 7 個既有測試會編譯失敗**（新欄位可填 null 或由 k 衍生）。再補一條斷言：新指標欄位同樣參與聯集對齊且長度與 `dates` 相同。

### 前端（`frontend/src/components/StockAnalysisDialog.vue`）

- [x] 262.12 **註冊 `BarChart`**：`import { LineChart } from 'echarts/charts'` 改為 `import { BarChart, LineChart } from 'echarts/charts'` 並加入 `use([...])`。
  ⚠️ 本專案 echarts 為 tree-shaking 版，**漏註冊會靜默不畫柱狀且無任何錯誤訊息**（同檔第 168 行的註解已記載 `MarkPointComponent` 的前例）。實作後必須在畫面上確認 OSC 柱真的有出現。

- [x] 262.13 新增 `selectedIndicator` ref（預設 `'KD,J'`），以 Element Plus `el-select` 呈現，**放進圖表上方既有的「期間：」那一列**（`StockAnalysisDialog.vue:31-43`）。選單只有 5 個選項：`KD,J`／`MACD`／`RSI`／`乖離率`／`威廉指標`。
  - 切換**不重新請求資料**（`chart-series` 已一次回傳全部指標），只重算 `chartOption`。
  - **建議作法（優先採用）**：放進**既有的「期間：」那一列**（`StockAnalysisDialog.vue:31-43`，已是 `display:flex` 的按鈕群），加一個「指標：」下拉。純 DOM、零絕對定位、與既有 UI 慣例一致，也沒有 canvas/DOM 疊層的 z-index 與點擊穿透問題。與 Yahoo 把選單放在子圖左上略有差異，但本專案的期間選擇器本就在圖表上方，放在一起更一致。
  - 若堅持要放在子圖左上（貼近 Yahoo），才走絕對定位，此時注意：`legend[1].bottom: 231` 是 legend **box 底緣**、實際佔 231~267（兩行、`lineHeight: 16`），要對齊中線需用 `bottom: 239px`；`grid[1]` 頂緣在 215，只剩 16px 餘裕。**垂直方向對 `autoresize` 免疫**（圖表高度硬寫 `style="height:580px"`），真正的風險在**水平**：`legend[1]` 未設 `left`、ECharts 預設水平置中，容器變窄時 legend 左緣會往左移撞上 select，須實測窄視窗。
  - **無論哪種作法都不可為了選單而動 `grid`／`legend` 的既有座標**（那是 Task 261 調到使用者滿意的版面）。

- [x] 262.14 依 `selectedIndicator` 切換子圖的 **series／y 軸／參考線／legend 內容**：

  | 選項 | series | Y 軸 | 參考線 |
  |---|---|---|---|
  | KD,J | K9／D9／J9 三線＋K3D2／RSV 空 series（現況，不動） | 自適應（現況邏輯） | 80／20 |
  | MACD | DIF／MACD 兩線 ＋ OSC 柱狀 | 自適應 | 0 |
  | RSI | RSI5／RSI10 兩線 | 固定 0~100 | 70／30 |
  | 乖離率 | BIAS10／BIAS20 兩線（B10−B20 只在 legend 顯示數值） | 自適應 | 0 |
  | 威廉指標 | W%R9 單線 | 固定 0~100 | 20／80 |

  - **自適應 Y 軸**：`interval = (max − min) / 2`（三個等距刻度）沿用 Task 261。KD,J 沿用現況（吃整條陣列、對齊 10 的倍數、強制 `min ≤ 20`／`max ≥ 80`）。
    ⚠️ **MACD 與 BIAS 的值域必須只取「目前可視區間」內的非 null 值，不可照抄 KD,J 那段**。Task 261 的寫法（`StockAnalysisDialog.vue:606` 的 `const vals = [...K, ...D, ...J]`）吃的是**整條 10 年陣列**——K/D/J 有界於 0~100 所以沒問題，但 MACD／BIAS 與價位同階，2330 十年間價位從約 180 漲到 2425，值域差兩個數量級。實算後果：MACD 全序列軸為 `[-70, 110]`，近一個月資料只佔軸高 **42.7%**、2017 年那種低價區間只佔 **1.6%**（實質是一條直線）；BIAS 近一個月佔 **22.7%**。Task 261 已把「只佔一半高度」判為不可接受並回退過一次，這裡更糟。
    作法：用同檔既有的可視區間算法（`ez = effectiveZoom.value` 於 `:624`、`loIdx`/`hiIdx` 於 `:647-648`，markPoint 就是這樣做的）先切出可視片段再取 min/max。
    ⚠️ **`const ez` 宣告在 `:624`，而 KD 的 y 軸 IIFE 在 `:603-621`——在 y 軸區塊內直接寫 `ez` 會落在 TDZ（`ReferenceError: Cannot access 'ez' before initialization`）**。須先把 `const ez` 上移到 y 軸區塊之前，或在該區塊內直接讀 `effectiveZoom.value`。
    intraday 模式無此問題：`effectiveZoom` 在 `isIntraday` 時硬回 `{start:0, end:100}`（`:210-211`），可視區間即整條常數陣列。
    另：**BIAS 不要對齊 10 的倍數**（典型值域僅 ±5，10 的粒度過粗會讓線壓成平的），改沿用同檔價格軸的 pad fallback 鏈（`:663` 的 `(hi-lo)*0.1 || hi*0.001 || 1`）不做倍數對齊。MACD 與 BIAS 皆**強制涵蓋 0**（參考線是 0 軸）。
  - **只在 legend 顯示數值、不畫線的欄位必須掛同名 `data: []` 空 series**——ECharts `LegendView` 找不到同名 series 時整個項目連同數值都不繪製，production build 無提示（Task 261 已踩過）。完整清單：KD,J 的 **K3D2／RSV**、MACD 的 **EMA12／EMA26**、乖離率的 **B10−B20**。
  - **MACD 的 EMA12／EMA26 不畫線**，因為它們與價格同量級（實測 Yahoo 2330：EMA12 2338.96 vs DIF −21.94，差百倍以上），畫上去會把 DIF／MACD／OSC 壓成一條水平線。連帶：**MACD 子圖的自適應 Y 軸值域只取 `dif`／`macd`／`osc`，不可納入 `ema12`／`ema26`**。
  - MACD 的 `legend.data` 為 EMA12／EMA26／DIF9／MACD **四項**（OSC 是柱狀、不列 legend）；但 OSC 的 bar series 仍要有 `name`，只是不放進 `legend.data`。
  - OSC 柱狀：正值 `#dc2626`、負值 `#16a34a`（台股慣例紅漲綠跌），用 `itemStyle.color` 的 callback 依值取色。

- [x] 262.15 子圖 legend（`legend[1]`）的 `data` 與數值 `map` 依 `selectedIndicator` 動態產生；漲跌箭頭沿用既有機制（BFF `latest` 的 `prev*` 比較、漲紅跌綠）。**上圖的 `legend[0]`（股價／均線／成本均價）不受選單影響。**

- [x] 262.15.1 ⚠️ **「當日」（intraday）分支必須一併擴充**（`StockAnalysisDialog.vue:562-569`）。該分支現況只填六個變數：
  ```js
  const fill = v => dates.map(() => v)
  ma20 = fill(num(lt.ma20)); ma60 = fill(num(lt.ma60)); ma240 = fill(num(lt.ma240))
  K = fill(num(lt.k)); D = fill(num(lt.d)); J = fill(num(lt.j9))
  ```
  intraday 的 `dates` 是「開盤→收盤每分鐘」網格（台股 271 格），與日線 `sr.dates`（約 2500 筆）**長度完全不同**。若只照 262.14 改 series／y 軸而沒動這段，切到「當日」＋MACD/RSI 時會把日線長度的陣列貼到分鐘網格上 → 前 271 分鐘畫出十年前的指標值。
  作法：依 `selectedIndicator` 只 `fill()` 當前需要的欄位，值一律取 BFF 的 `latest.*`（不是對齊後陣列的末格）。驗證段第 6 步會檢查這點。

### 明確不做的事

- [x] 262.16 選單**不含**成交量／多空指標乖離／CDP／動向指標DMI。前三者需要 bar＋獨立量級軸或水平點位等不同渲染形態，DMI 需 +DI／−DI／ADX 三線與 Wilder 平滑，皆另案處理。
- 不改警示 email 的 PNG 走勢圖（`AlertChartRenderer`）。
- 不改 `computeAll()` 的對外數值行為（警示觸發門檻、觀察清單、Excel 匯出都吃這些值）。
- 不動 Task 261 調好的 `grid`／`legend` 版面座標。

## 驗證

### 1. 後端測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

（**不要**用 `-DargLine`，會覆蓋 pom 的時區設定導致大量測試 error。）

### 2. BFF 測試

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
```

### 3. 前端建置

本 worktree 沒有 `node_modules`（在主 clone 且被 gitignore），直接建置會 `vite: command not found`。先連結、建完移除：

```bash
ln -s /Users/steven/Project/asset-management/frontend/node_modules frontend/node_modules
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
rm frontend/node_modules
```

### 4. 部署（本專案沒有 dev server，「改好」＝ image rebuild ＋ container recreate）

```bash
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```

⚠️ recreate `business-services` 會換 IP，BFF 握舊 IP 會回 500 且約 3 分鐘不自癒（Docker DNS TTL 600s）——三個服務一起 recreate，或事後 `docker compose -p asset-management restart bff`。

### 5. 端點自測（免 OAuth，在 business 容器內直接打）

```bash
docker exec asset-business-services sh -c 'curl -s "http://localhost:8080/api/market-data/indicators/series?code=2330&market=%E5%8F%B0%E8%82%A1&start=2026-07-01&end=2026-07-31" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE"' | python3 -m json.tool | tail -25
```

⚠️ **用區間查詢並取尾筆，不要用 `start=end=今天`**——當日收盤若尚未進 `stock_price_history` 且 Redis 無今日 live，單日查詢會回**空陣列**而被誤判為失敗。

檢查尾筆含 11 個新欄位且非 null，並驗自洽（容差 0.01，各欄各自捨入）：`dif ≈ ema12 − ema26`、`osc ≈ dif − macd`、`b10b20 ≈ bias10 − bias20`、`wr9 ≈ 100 − rsv`。
**與 Yahoo 逐位比對（本任務最有效的驗收，務必做）**：本專案 OHLC 與 Yahoo 同源（已實測 9 個值逐位相同），故 2330 於 2026-07-31 的尾筆應為：

| 欄位 | 期望值 | 判準 |
|---|---|---|
| k／d／j9 | 42.65／32.92／13.45 | 逐位相符 |
| rsi5／rsi10 | 65.78／56.53 | 逐位相符（對不上＝平滑方式寫錯） |
| bias10／bias20／b10b20 | 3.88／1.83／2.05 | 逐位相符 |
| wr9 | 7.55 | 逐位相符 |
| ema12／ema26／dif／macd | 2338.96／2360.89／−21.94／−8.23 | **容差 0.15**（價基為 DI，殘差來源未查明） |

⚠️ 只驗 `dif ≈ ema12 − ema26` 這類**恆等式是不夠的**——公式寫錯時那些關係照樣成立，抓不到任何東西。上表才是能抓錯的驗收。若其他欄位都對、只有 MACD 系列偏掉，優先檢查價基是否誤用收盤價而非 DI。

> BFF 的 `chart-series` **無法用 curl 自測**：`asset-bff` 映像沒有 curl，且未登入一律回 401（狀態碼無法區分 controller 有沒有接走）。靠 262.11 的單元測試與下面的畫面實測。

### 6. 畫面實測

`http://localhost/` → Dashboard 持股表格雙擊任一台股列，確認：

- 圖表上方的「期間：」那一列出現「指標：」下拉，預設 `KD,J`；**子圖本身的版面與 Task 261 完成時完全相同**（沒有因為加選單而動到 grid／legend 座標）。
- 逐一切換五個選項：子圖的線、Y 軸範圍、參考線、legend 數值都跟著換，且**切換沒有 loading**（不重新請求）。
- **MACD：OSC 柱狀真的有畫出來**（正紅負綠）——這是 `use(BarChart)` 是否漏註冊的唯一可靠檢查，漏了會靜默不畫。
- RSI 與威廉指標的 Y 軸固定 0~100；BIAS 與 MACD 的 0 軸基準線在畫面內。
- 切到「當日」期間：各指標一樣顯示為水平參考線（沿用 Task 261 的 `latest` 常數填滿機制）。
- 開 `0000` 台股大盤，五個選項都不空白。

### 7. stale image 檢查

| 服務 | 判準 |
|---|---|
| business-services | 第 5 步 curl 回 200 且含新欄位 |
| bff | `docker exec asset-bff sh -c 'unzip -p /app/app.jar BOOT-INF/classes/com/steven/assets/bff/stockanalysis/dto/IndicatorPointDto.class | strings | grep -c wr9'` 大於 0。⚠️ **不可用裸 `grep -c wr9 /app/app.jar`**——fat jar 內的 `.class` 是 DEFLATE 壓縮的，只有 ZIP entry 名（類別檔名）是明文，故裸 grep 對 class 內識別字**恆為 0**（實測既有欄位 `k3d2` 也回 0），會把正確的 build 誤判成 stale。本次不新增 class，所以 `unzip -l | grep <ClassName>` 這種 entry 名比對法也不適用，必須解壓該 class 再抓字串。 |
| frontend | `docker exec asset-frontend sh -c "grep -o '威廉指標' -m1 /usr/share/nginx/html/assets/*.js"` 有輸出 |

## 完成報告

### 實際改動

| 檔案 | 內容 |
|---|---|
| `backend/.../service/TechnicalIndicatorService.java` | `IndicatorPoint` 擴充 11 欄；新增 `MacdPoint` record、`diOf()`、`emaWithSmaSeed()`、`macdSeriesAsc()`、`rsiSeriesAsc()`、`rsiOf()`、`biasRaw()`；`indicatorSeries()` 組入新欄位 |
| `backend/src/test/.../TechnicalIndicatorSeriesAlignmentTest.java` | 新增 5 個 `@Test`（RSI 平滑辨別、MACD 價基、MACD 三者關係、BIAS/W%R 恆等式、暖機邊界） |
| `bff/.../dto/IndicatorPointDto.java`／`ChartSeriesDto.java` | 各補 11 個欄位；`Latest` 另補 11 值＋10 個 `prev*` |
| `bff/.../ChartSeriesAligner.java` | 新欄位納入聯集對齊與 `latestOf` |
| `bff/src/test/.../ChartSeriesAlignerTest.java` | fixture 補欄位（record canonical constructor 參數變動）＋新增 1 個 `@Test` |
| `frontend/src/api/index.js` | 無變動（沿用 `getChartSeries`） |
| `frontend/src/components/StockAnalysisDialog.vue` | `use(BarChart)`；`INDICATOR_SPEC` 規格表；`el-select` 指標選單；子圖 series／Y 軸／legend 依選單動態產生；`v-chart` 加 `notMerge` |

### 驗證輸出

- 後端 `mvn test`：**373 passed / 0 failed**（Task 261 的同源判準測試全數維持綠燈）。
- BFF `mvn test`：**18 passed / 0 failed**。
- 前端 `npm run build`：`✓ built in 4.30s`。
- **實機逐位比對（2330 @ 2026-07-31，本任務最有效的驗收）**：

  | 欄位 | 我方 | Yahoo | 判定 |
  |---|---|---|---|
  | k／d／j9 | 42.65／32.92／13.45 | 同 | 逐位 ✓ |
  | rsi5／rsi10 | 65.78／56.53 | 同 | 逐位 ✓ |
  | bias10／bias20／b10b20 | 3.88／1.83／2.05 | 同 | 逐位 ✓ |
  | wr9 | 7.55 | 同 | 逐位 ✓ |
  | ema12／ema26／dif／macd | 2338.96／2360.97／−22.01／−8.35 | 2338.96／2360.89／−21.94／−8.23 | 容差 0.15 內 ✓（ema12 逐位） |

- stale image 檢查三項全過：business 端點回新欄位、`unzip -p … IndicatorPointDto.class \| strings \| grep -c wr9` = 1、frontend bundle 含「威廉指標」。

### 與原計畫的偏差

1. **實作中修掉一個自製 bug**：`macdSeriesAsc()` 原本以「`e12[i] == null || e26[i] == null` 就整筆 `MacdPoint.EMPTY`」組值，導致 `ema12` 在第 11~24 期**明明已算出卻被連坐清成 null**。由 262.7 要求的暖機邊界測試當場抓到，改為五個欄位各自獨立判斷。
2. **新增 `v-chart` 的 `:update-options="{ notMerge: true }"`（原計畫未涵蓋）**：vue-echarts 預設 merge 更新，而切換指標時子圖 series 數量會變（KD,J 五個 vs 威廉指標一個），merge 不會移除多餘的舊 series → 切過去後舊指標的線殘留在畫面上，看起來像「切了沒反應」。此為 ECharts 更新語義問題，純函式測試涵蓋不到，是實機操作才會現形的缺口。`dataZoom` 的 start/end 本就由 option 明確指定（`ez`），故 notMerge 不會丟失縮放狀態。
3. 選單最終放在既有「期間：」那一列（spec 已定案的低風險作法），與 Yahoo 放在子圖左上不同——本專案圖表是單一 canvas，DOM 無法插進兩張圖中間。

### 尚未執行

- **瀏覽器實際切換五個選項的畫面確認**（尤其 MACD 的 OSC 柱狀是否真的畫出來——`use(BarChart)` 漏註冊會靜默不畫）待使用者確認。
- 依共用 stack 規則，merge 進 main 後應從 main 的 worktree 重建。
