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

- [ ] **359.3a** 不新增、不修改 business／external-materials-service 兩層：`/api/market-data/etf-holdings`、`/internal/etf-holdings` 契約與行為維持現狀不動。**BFF 這一層例外**（見 359.4）：`/api/bff/stock-analysis/etf-holdings` 的對外路徑、query 參數、回應 JSON 的欄位形狀（`supported`／`source`／`asOfDate`／`message`／`holdings[].{stockCode,stockName,weight,shares}`）皆不變，唯一改變的是 `holdings` 陣列本身從「business 回傳的完整清單」變成「BFF 排序＋截斷後的前 10 大＋其它」。
- [ ] **359.3b** 不改資料庫、不加 Liquibase changeset。
- [ ] **359.3c** 美股 ETF（`isEtf` 的美股白名單分支）同樣改走這條路徑（API 本身已支援 `market=美股`），不必為美股另寫特例；但美股來源目前只有 Yahoo 前 10 大，`supported=false` 時同樣走 359.2a 的 fallback。**英股 ETF 同樣不必另寫特例，但後端其實有專屬抓取分支、`holdings` 不是恆空**：`getYahooEtfHoldings()`（`MarketDataFetchService.java:638`）對英股有 `.L` 後綴專屬邏輯，`isEtf()` 白名單（CSPX／VWRA／VUSA／EIMI／IWDA）命中的代號會實際查 Yahoo `topHoldings`。**已容器內實測**：`curl "http://localhost:8080/internal/etf-holdings?code=CSPX&market=%E8%8B%B1%E8%82%A1"` 回傳 `supported:true, source:"Yahoo Finance (前 10 大)"`，`holdings` 含真實成分股（NVIDIA 7.54%／Apple 7.03%／Microsoft 5.35%…）。前端邏輯仍完全通用（`holdings` 有值就畫圓餅圖，`supported=false` 或空陣列才走 359.2a fallback），不必為英股寫分支判斷；只是「英股必定 fallback」的假設是錯的，驗收時應實際點開一檔英股 ETF 持倉確認圓餅圖正常渲染，不能只驗證 fallback 路徑。

### 359.4 前 10 大 + 其它聚合（追加，359.1~359.3 已實作部署後由使用者實測發現；本節改動 BFF）

> 使用者實際點開「00919 群益台灣精選高息」測試，該檔 MoneyDJ 成分股超過 20 檔，全部個別繪成扇形導致標籤密集交錯、幾乎無法閱讀（`ETF_HOLDINGS_PIE_COLORS` 只有 10 色，超過 10 檔還會重複配色，加劇混淆）。要求「只顯示前 10 大持股，11 名之後就列在其它」，視覺效果比照 Dashboard「台股個股穿透」圖的「排序取前 10 名 + 其它聚合」。
>
> **第一版草稿曾打算讓前端自己排序＋截斷，經 spec-review 挑戰後修正為 BFF 端實作**：Dashboard 那個既有慣例其實是在 `DashboardBffController.buildLookthrough()`（BFF，Java）完成的，不是前端；`spec/steering/structure.md` §3.2 第 5 條明文「前端只 render，BFF 預先聚合/排序/過濾」。若把排序＋截斷留在前端，等於未登記就違反這條鐵則。改到 BFF 做，理由不是單純「合規」，是這個 dialog 本來就有現成、同一模式的既有先例可以直接套用（見 359.4a），不需要發明新架構。

- [ ] **359.4a** 把 `/api/bff/stock-analysis/etf-holdings` 從 `StockAnalysisBffRoutes.java` 目前的純 Gateway 轉發 route，改成 `StockAnalysisChartBffController.java` 的一支真實 `@GetMapping("/etf-holdings")`（`code`／`market` 兩個 `@RequestParam`，回傳 `Mono<...>`）。**照抄同檔既有 `/chart-series` 方法的既有寫法**：用同一個注入的 `WebClient businessServicesClient` 呼叫 business 既有的 `GET /api/market-data/etf-holdings?code=&market=`，`.retrieve().bodyToMono(...)`，並比照 `/chart-series` 加 `.onErrorResume(...)`（上游失敗時回傳 `supported=false` 的降級結果，不得讓例外直接炸給前端）。**同時要把 `StockAnalysisBffRoutes.java` 裡原本的 `stock-analysis-etf-holdings` route 整條移除**——同一路徑不能既是 Gateway route 又是 `@RestController` method，該檔的既有類註解已經解釋過兩者共存的前提是「路徑不重疊」（WebFlux annotated controller 的 order 0 优先於 Gateway 的 order 1，但前提是這個路徑不再出現在 Gateway route 表裡，否則是兩份定義同一路徑、其中一份變成死碼）。
- [ ] **359.4b** 新增一個 BFF 側 DTO（例如 `EtfHoldingsDto`／`EtfHoldingDto`，放在 `bff/.../stockanalysis/dto/`，與同資料夾既有的 `ChartSeriesDto`／`PricePointDto`／`IndicatorPointDto` 同一慣例），欄位形狀對齊 business 既有的 `MarketDataService.EtfHoldingsResult`／`EtfHolding`（`stockCode`／`market`／`supported`／`source`／`asOfDate`／`message`／`holdings[].{stockCode,stockName,weight,shares}`）以便 WebClient 反序列化。取得 business 回應後：`holdings` 依 `weight` 由大到小排序，取前 10 筆個別保留，第 11 筆起（若存在）加總 `weight` 為一筆「其它」附加在陣列尾端；其餘欄位（`supported`／`source`／`asOfDate`／`message`）原封不動透傳。**回應的頂層 JSON 形狀（欄位名稱與巢狀結構）必須與 business 現況完全一致**，前端 `getEtfHoldings()` 呼叫端與既有 `EtfHoldingsResult` 型別不需要跟著改。
- [ ] **359.4c** 「其它」這一筆的識別：`stockCode: null`（前端 tooltip 目前用 `p.data?.code` 判斷要不要顯示代號，`null` 會自然跳過該段，不需要前端另寫特例）、`stockName: "其它"`、`shares: null`（不同個股股數不能直接相加，`null` 會被前端既有的 `shares == null ? '—' : ...` null-safe 邏輯正確處理成「—」，同樣不需要前端另寫特例）、`weight` 為第 11 名起的加總值。
- [ ] **359.4d** 少於等於 10 檔的 ETF（例如產業型窄基 ETF）不產生「其它」項——只有第 11 名以後**真的存在**時才附加這筆聚合項，不得無條件多附加一筆 0% 的「其它」。
- [ ] **359.4e** **前端配合調整（回頭修 359.1）**：`holdingsPieOption`（`StockAnalysisDialog.vue:607` 起）不需要、也不應該再自己排序或截斷——`holdings` 陣列現在由 BFF 保證已經是「前 10 大 + 其它」，前端只單純把陣列映射進 pie series data（沿用 359.1c 既有的欄位映射與 null-safe 處理即可）。「其它」的配色：從既有 `ETF_HOLDINGS_PIE_COLORS`（10 色迴圈）跳出來，用固定灰階（比照 Dashboard `TW_PIE_COLORS` 陣列最後一色 `'#94a3b8'`）——最簡單的判斷方式是 `stockCode == null` 時套用這個固定色，其餘沿用迴圈色票。
- [ ] **359.4f** `StockAnalysisDialog.vue:603-605`（`ETF_HOLDINGS_PIE_COLORS` 常數上方）現有的既有註解寫著「本頁 holdings 是 API 回傳的明確持股清單，不合成『其它』分類」——這句話在 359.4 之後不再成立，**必須同步改寫或移除**，不要留下與新行為矛盾的既有註解。
- [ ] **359.4g** 既有 `stockAnalysisDialog.contract.test.js` 對 `holdingsSection` 的既有逐字斷言（`value: Number(h.weight)`／`code: h.stockCode || ''`／`name: h.stockName || h.stockCode || ''`／`shares: h.shares`，約 104~109 行）在調整 359.4e 的映射寫法後，需重新確認是否仍然綠燈；若寫法變動導致這些斷言失效，同步更新，不得留下假綠或假紅。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml test
```

```bash
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

⚠️ **`npm test`（`package.json:7`）是寫死的檔案清單**（`node --test` 後面直接列 5 個 `.js` 路徑），不會自動探索專案內其他 `*.test.js`。新斷言請直接加進清單內既有的 `frontend/src/utils/stockAnalysisDialog.contract.test.js`（沿用其對 `.vue` 原始碼做字串／regex 比對的既有寫法，見同檔 10~33 行）；若堅持另開新測試檔，**必須**同步把該檔路徑加進 `package.json:7` 的 `test` script，否則 `npm test` 會綠燈但新測試從未被執行過。

BFF 端測試至少涵蓋（比照同檔既有 `ChartSeriesAlignerTest` 之類的既有測試風格）：

- 業者回傳超過 10 檔時，`/etf-holdings` 回傳的 `holdings` 恰好 11 筆（前 10 大依 `weight` 降冪排列＋1 筆「其它」在陣列尾端），「其它」的 `weight` 等於第 11 筆起的加總、`stockCode`／`shares` 為 `null`、`stockName` 為「其它」。
- 業者回傳剛好 10 筆或更少時，`holdings` 原樣透傳（不產生「其它」項，長度不變）。
- 業者上游失敗／逾時時，回傳 `supported=false` 的降級結果，不拋出例外給呼叫端。
- `supported`／`source`／`asOfDate`／`message` 等頂層欄位原封不動透傳，不因排序截斷而遺失。
- `StockAnalysisBffRoutes.java` 已移除 `stock-analysis-etf-holdings` 這條 route（避免與新 controller method 重複定義同一路徑）。

前端測試至少涵蓋：

- `holdings` 有資料時渲染圓餅圖（可用既有測試方式斷言呼叫了 `getEtfHoldings` 並把回傳的 `holdings` 直接餵進 chart option 的 `series[0].data`，不必真的渲染 canvas；不應再斷言前端自己做排序／截斷，那已經下放到 BFF）。
- `supported=false` 或 `holdings` 為空陣列時，顯示既有的靜態連結 fallback，不是空白或報錯畫面。
- API 呼叫拋出例外時，不污染其他 tab（走勢圖／行情五檔／股利歷史）的既有 state，不觸發全域 toast。
- 切換股票後，舊股票的 holdings 資料不殘留在畫面上（reset 邏輯生效）。
- `weight` 欄位直接當百分比使用，不做多餘的 ×100 或 ÷100。
- `stockCode` 為 `null`（「其它」項）時走灰階固定色，不進入 `ETF_HOLDINGS_PIE_COLORS` 迴圈色票。

實際 Docker stack 驗收：

```bash
cd /Users/steven/Project/asset-management-main
docker compose -p asset-management build --no-cache bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
```

⚠️ 重建並重新建立 `bff` 後，比照本專案既有慣例確認沒有殘留連線問題（`docker logs asset-bff` 不應出現 `Connection refused`；本次未重建 business-services／external-materials-service，理論上不會觸發那個 IP 快取問題，但仍建議看一眼 log 確認乾淨）。

實資料須確認：

- 雙擊「0056 元大高股息」（或任一持有中的台股 ETF）→「持股明細」頁籤顯示圓餅圖，扇形對應成分股權重，hover 顯示代號／名稱／比例／股數，與 Dashboard「台股個股穿透」圖同一視覺風格。
- 雙擊成分股超過 10 檔的 ETF（例如「00919 群益台灣精選高息」）→ 圓餅圖只呈現前 10 大＋「其它」一筆，不再是密集交錯的 20+ 扇形。
- 資料來源／時間標示存在且非空。
- 雙擊非 ETF 個股（例如 2330 台積電）時，「持股明細」頁籤本就不顯示（既有 `v-if="isEtf"` 行為，本任務不動）。

## 完成報告

（實作者完成後回填：實際修改檔案清單、npm test/build 完整輸出、瀏覽器實測截圖或觀察結果、與本計畫的偏差及原因。）
