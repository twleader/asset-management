# [t359] 股票分析彈窗「持股明細」改接既有 ETF 成分股 API、以圓餅圖呈現

**對應 Requirements:** Requirement 13（股票走勢圖延伸資訊：ETF 持股明細）
**前置任務:** 無（純前端變更，消費既有已上線 API）
**Liquibase changeset:** 無（不改資料庫）

> **編號說明**：本任務原編 Task 358，開工過程中 origin/main 已先 merge 落地另一支不相關的 Task 358「今日股市分析頁面改為分類分點呈現」（Requirement 95）。已 landed 者保留原號，本任務改編為 359。

## 背景

使用者在雙擊開啟「0056 元大高股息」的股票分析彈窗、切到「持股明細」頁籤時，看到的是一段寫死的靜態說明文字：

> ETF 成分股資料目前免費資料源都有限制（TWSE 無此 API、FinMind 需付費方案、發行商官網為 SPA）。
> 點擊以下外部連結可查看最新完整成分股：MoneyDJ／玩股網／Yahoo 奇摩股市

使用者指出：Dashboard 的「資產配置分佈 → 台股個股」頁籤（`DashboardView.vue` 的「台股個股穿透」圓餅圖）明明就能正常顯示 ETF 成分股（含 0056），要求「持股明細」頁籤也弄成跟 Dashboard 一樣的圖。

查證後發現：**這不是資料源真的抓不到，而是前端這個頁籤從未呼叫過已經存在、已經正常運作的後端 API。**

- `frontend/src/components/StockAnalysisDialog.vue` 第 106~122 行的「持股明細」`<el-tab-pane>` 完全沒有 API 呼叫、沒有 loading/error state，是純靜態內容。
- 但 `frontend/src/api/index.js` 第 122~123 行早就定義了：
  ```js
  getEtfHoldings: (code, market) =>
    api.get('/bff/stock-analysis/etf-holdings', { params: { code, market } }),
  ```
  這支函式目前在整個 `frontend/src` 裡**零呼叫端**（`grep -rn "getEtfHoldings" frontend/src` 只命中定義本身）。
- 這支 API 背後的路由與後端實作**完整存在且正常運作**：
  - BFF：`bff/src/main/java/com/steven/assets/bff/stockanalysis/StockAnalysisBffRoutes.java` 的 `stock-analysis-etf-holdings` route，`/api/bff/stock-analysis/etf-holdings` → rewrite → `/api/market-data/etf-holdings`（business-services）。
  - business：`backend/src/main/java/com/steven/assets/controller/MarketDataController.java:141-145` 的 `GET /api/market-data/etf-holdings`，委派 `MarketDataService.getEtfHoldings()`（`backend/src/main/java/com/steven/assets/service/MarketDataService.java:159-173`），純 proxy 呼叫 `external-materials-service` 的 `/internal/etf-holdings`，不快取、不加業務邏輯。
  - external-materials-service：`MarketDataFetchService.getEtfHoldings()`（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/MarketDataFetchService.java:441` 起），依序試 MoneyDJ → Yahoo → FinMind，含 12h in-memory cache（`etfHoldingsCache`）。
- **已實測驗證資料真的抓得到**（2026-08-22，容器內直接呼叫）：
  ```
  docker exec asset-external-materials-service sh -c \
    'curl -s "http://localhost:8080/internal/etf-holdings?code=0056&market=%E5%8F%B0%E8%82%A1"'
  ```
  回傳 `"supported":true,"source":"MoneyDJ","asOfDate":"2026/08/14"`，`holdings` 陣列含南亞 5.87%、華南金 4.35%、永豐金 4.27%…完整成分股清單，非只有前 10 大。

結論：**這是前端消費端從未接上既有端點的缺工，不是資料源限制。** 本任務範圍限定在前端 `StockAnalysisDialog.vue`，後端／BFF 三層完全不動、不新增任何 API。

## 要做什麼

### 359.1 呼叫既有 API、渲染圓餅圖

- [ ] **359.1a** `StockAnalysisDialog.vue` 的「持股明細」`<el-tab-pane>`（第 106~122 行）改為：頁籤啟用時（比照股利歷史／行情五檔 tab 的 lazy-fetch 慣例，見 359.2c——**不是**走勢圖 tab，那個 tab 是 `onOpen()` 每次開對話框就 eager fetch，不要照抄成三個 tab 一開就打）呼叫 `bffApi.stockAnalysis.getEtfHoldings(props.stock.stockCode, props.stock.market)`（注意是 `props.stock`，本檔 `<script setup>` 沒有解構出裸 `stock` 這個識別字），顯示 loading 狀態，成功後以圓餅圖呈現 `holdings` 陣列（依 `weight` 分扇形）。
- [ ] **359.1b** 圓餅圖視覺風格比照 `DashboardView.vue` 的「台股個股穿透」圓餅圖（`twStockPieOption` computed，約第 936~975 行）：`radius:['46%','78%']`、`center:['50%','50%']`、`label:{formatter:'{b}\n{d}%',fontSize:11}`、`labelLayout:{hideOverlap:true}`、`itemStyle:{borderRadius:6}`，tooltip 顯示「代號 名稱 / 金額或股數 (比例%)」。**不必**照抄 Dashboard 那組雙環＋內圈 host label 的複雜版本（那是為了同時疊「總覽」與「細分」兩層資料設計的，本頁只有單一 ETF 的成分股，用單環圓餅圖即可）；配色可重用 Dashboard 的 `TW_PIE_COLORS` 常數（或自行定義同色階的一組，只要視覺一致、不要用 ECharts 預設色票）。
  ⚠️ **`StockAnalysisDialog.vue` 目前的 echarts import／`use()` 清單（約第 201、209 行）沒有註冊 `PieChart`**（只有 `BarChart`／`LineChart`／`CandlestickChart`），這是獨立於 `DashboardView.vue` 的 `<script setup>`，不會繼承後者的註冊。本專案 echarts 為 tree-shaking 版，畫圓餅圖**必須顯式** `import { PieChart } from 'echarts/charts'` 並加進 `use([...])` 陣列，比照 `DashboardView.vue:392,407` 的既有寫法——漏註冊會靜默不畫、無任何錯誤訊息，與同檔第 207~208 行既有註解記錄的 `MarkPointComponent` 陷阱同一類（Task 96 已踩過一次）。
- [ ] **359.1c** 圖例／hover 顯示欄位：股票代號、股票名稱、持股比例（`weight`，百分比）、股數（`shares`）。API 回傳的 `holdings[].weight` 已是百分比數值（例如 `5.87` 代表 5.87%），不要再除以 100 或乘 100。**`shares` 只有台股 MoneyDJ 路徑會填值，Yahoo 路徑（美股／英股／台股 `.TWO` fallback）與 FinMind 路徑一律回 `null`**（`MarketDataFetchService.java` 的 `getYahooEtfHoldings()`／`getMoneyDjEtfHoldings()` 兩個建構點逐一核對過）；顯示時比照同檔既有的 `fmtLots`（第 461 行，`v == null ? '—' : ...`）null-safe 慣例處理成「—」，不要直接對 `null` 呼叫數值格式化方法。
- [ ] **359.1d** 資料來源標示：沿用其他頁籤的慣例（例如走勢圖頁籤右上角「資料截止：」、行情五檔頁籤的「Yahoo 股市．時間不明」列），在圖表上方或下方顯示 `asOfDate` 與 `source`（例如「資料來源：MoneyDJ．2026/08/14」），讓使用者知道資料新鮮度與來源，不是憑空生成的圖。

### 359.2 失敗時的既有 fallback 原封不動保留

- [ ] **359.2a** API 回傳 `supported=false`（該市場不支援）或 `holdings` 為空陣列（查詢失敗、暫時抓不到）時，**不要顯示空白圖表或報錯**，改顯示現行這段既有的靜態說明文字＋外部連結（現有的 `etfExternalLinks` computed，第 536~557 行；程式碼與文案原封不動保留，只是從「唯一路徑」降級為「fallback 路徑」）。**連結集合依市場別而異，不是同一組三個**：台股（MoneyDJ／玩股網／Yahoo 奇摩股市，3 條）、美股（ETFdb／Morningstar／Yahoo Finance，3 條）、英股（iShares 官網，僅 1 條）。
- [ ] **359.2b** API 呼叫失敗（網路錯誤、逾時、非 2xx）時的處理方式，比照同一份元件裡「行情五檔」頁籤的既有慣例（`quoteDetail` 那組 try/catch，`getQuoteDetail` 呼叫帶 `skipErrorToast:true`）：不要跳全域 toast 汙染其他頁籤，局部顯示可重試的錯誤狀態（或直接降級到 359.2a 的靜態連結 fallback，兩者擇一，由實作者依現有程式風格決定，但不得讓整個對話框卡住或拋出未捕捉例外）。
- [ ] **359.2c** 依既有 tab 切換模式（`onTabChange`，約第 425~433 行）比照 359.1a 的 lazy-fetch 加一個 `holdingsAttempted` 旗標（同 `quoteDetailAttempted` 的既有模式），避免每次切回這個 tab 都重打 API；但**不要**跨股票沿用快取——切換股票時的 reset 機制**不是** `watch(() => props.stock, ...)`（本檔沒有這個 watcher，唯一的 `watch(...)` 是監看 `months` 的第 391 行）。實際機制是 `el-dialog` 帶 `destroy-on-close`（第 8 行）＋ `@open="onOpen"`（第 10 行）：對話框關閉時整個內容連同所有 `ref()` 一併卸載，重開時 `<script setup>` 重新執行；`onOpen()`（約第 399~411 行）另外做一輪顯式手動 reset（`zoomPct`／`dividendHistory`／`quoteDetail`／`quoteRequestToken` 等）當雙重保險。holdings 相關 state 的 reset 比照 `onOpen()` 裡現有 `quoteDetail.value = null` 那幾行的既有寫法，加進同一個函式。

### 359.3 範圍界定（不做的事）

- [ ] **359.3a** 不新增、不修改任何後端／BFF API；`/api/bff/stock-analysis/etf-holdings`、`/api/market-data/etf-holdings`、`/internal/etf-holdings` 三層契約維持現狀不動。
- [ ] **359.3b** 不改資料庫、不加 Liquibase changeset。
- [ ] **359.3c** 美股 ETF（`isEtf` 的美股白名單分支）同樣改走這條路徑（API 本身已支援 `market=美股`），不必為美股另寫特例；但美股來源目前只有 Yahoo 前 10 大，`supported=false` 時同樣走 359.2a 的 fallback。**英股 ETF 同樣不必另寫特例，但後端其實有專屬抓取分支、`holdings` 不是恆空**：`getYahooEtfHoldings()`（`MarketDataFetchService.java:638`）對英股有 `.L` 後綴專屬邏輯，`isEtf()` 白名單（CSPX／VWRA／VUSA／EIMI／IWDA）命中的代號會實際查 Yahoo `topHoldings`。**已容器內實測**：`curl "http://localhost:8080/internal/etf-holdings?code=CSPX&market=%E8%8B%B1%E8%82%A1"` 回傳 `supported:true, source:"Yahoo Finance (前 10 大)"`，`holdings` 含真實成分股（NVIDIA 7.54%／Apple 7.03%／Microsoft 5.35%…）。前端邏輯仍完全通用（`holdings` 有值就畫圓餅圖，`supported=false` 或空陣列才走 359.2a fallback），不必為英股寫分支判斷；只是「英股必定 fallback」的假設是錯的，驗收時應實際點開一檔英股 ETF 持倉確認圓餅圖正常渲染，不能只驗證 fallback 路徑。

## 驗證

```bash
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

⚠️ **`npm test`（`package.json:7`）是寫死的檔案清單**（`node --test` 後面直接列 5 個 `.js` 路徑），不會自動探索專案內其他 `*.test.js`。新斷言請直接加進清單內既有的 `frontend/src/utils/stockAnalysisDialog.contract.test.js`（沿用其對 `.vue` 原始碼做字串／regex 比對的既有寫法，見同檔 10~33 行）；若堅持另開新測試檔，**必須**同步把該檔路徑加進 `package.json:7` 的 `test` script，否則 `npm test` 會綠燈但新測試從未被執行過。

測試至少涵蓋：

- `holdings` 有資料時渲染圓餅圖（可用既有測試方式斷言呼叫了 `getEtfHoldings` 並把回傳的 `holdings` 餵進 chart option 的 `series[0].data`，不必真的渲染 canvas）。
- `supported=false` 或 `holdings` 為空陣列時，顯示既有的靜態連結 fallback，不是空白或報錯畫面。
- API 呼叫拋出例外時，不污染其他 tab（走勢圖／行情五檔／股利歷史）的既有 state，不觸發全域 toast。
- 切換股票後，舊股票的 holdings 資料不殘留在畫面上（reset 邏輯生效）。
- `weight` 欄位直接當百分比使用，不做多餘的 ×100 或 ÷100。

實際 Docker stack 驗收：

```bash
cd /Users/steven/Project/asset-management-main
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
```

實資料須確認：

- 雙擊「0056 元大高股息」（或任一持有中的台股 ETF）→「持股明細」頁籤顯示圓餅圖，扇形對應成分股權重，hover 顯示代號／名稱／比例／股數，與 Dashboard「台股個股穿透」圖同一視覺風格。
- 資料來源／時間標示存在且非空。
- 雙擊非 ETF 個股（例如 2330 台積電）時，「持股明細」頁籤本就不顯示（既有 `v-if="isEtf"` 行為，本任務不動）。

## 完成報告

（實作者完成後回填：實際修改檔案清單、npm test/build 完整輸出、瀏覽器實測截圖或觀察結果、與本計畫的偏差及原因。）
