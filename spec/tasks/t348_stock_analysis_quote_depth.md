# [t348] 股票分析 popup 新增台股行情五檔頁籤（歷史任務紀錄；資料管線已由 t373 取代）

**對應 Requirements:** Requirement 87（歷史 UI／Yahoo 方案；現行資料管線為 Requirement 109／t373）
**前置任務:** 無（沿用既有跨頁共用 `StockAnalysisDialog` 與 `/api/bff/stock-analysis/*`）
**Liquibase changeset:** 無

> **歷史邊界：**本檔完整保留 Task 348 當時的 Yahoo request-time 實作計畫，供追溯而非派工。以下所有 Yahoo、`YAHOO_TW`、HTML parser、request-time、12 秒 timeout、以及「不得寫 PostgreSQL／Redis」的內容，**均非現行 acceptance criteria，禁止據此實作或測試**。現行五檔的完整 wire、DB、Redis、pure-read、timeout、服務重建與驗收一律以 `t373_redis_order_book_snapshot.md` 為準；只有既有 popup 的 layout／局部錯誤 state 可作歷史 UI 參考。

## 背景（歷史）

跨頁共用的股票分析 popup 現有「走勢圖」、ETF「持股明細」與「股利歷史」頁籤，但沒有參考畫面中的成交摘要、內外盤與最佳五檔。現有 Redis `price:{market}:{code}` 只有一檔 `buyPrice/sellPrice`，沒有五檔量、內外盤、均價、昨量或成交金額；不能把它與另一來源的 orderbook 混合，否則畫面各區塊會是不同時間點。

本任務改用 Yahoo 台股 quote 頁面的單一 server-rendered `quote.data` snapshot，第一次切入新頁籤時 request-time 取得，畫面重整才再抓；資料不寫 Redis／DB、不新增排程。這是已登記在 `CLAUDE.md` 與 `spec/steering/structure.md` 的具名、限縮例外：只供登入後「行情五檔」展示，不是權威即時價，不得供估值、損益、下單、警示、SSE、`/api/quotes*`、9090 公開 API 或其他 consumer 使用；其他即時價仍只走 Redis、收盤價仍只走 `stock_price_history`。Requirement 86／Task 347 已被另一個在途 worktree 佔用，故本任務使用 348。

## 要做什麼（歷史紀錄，非現行待辦）

### external-materials-service：抓取與安全解析

- [ ] 348.1 新增 `TwQuoteDetailFetchClient`（或同義具名 client）與 DTO：
  - `QuoteDetailResult(String stockCode, String stockName, String market, boolean supported, boolean available, String source, String message, String sourceTime, String fetchedAt, String marketStatus, BigDecimal price, BigDecimal previousClose, BigDecimal openPrice, BigDecimal highPrice, BigDecimal lowPrice, BigDecimal averagePrice, BigDecimal change, BigDecimal changePercent, BigDecimal turnoverYi, Long volumeLots, Long previousVolumeLots, BigDecimal amplitudePercent, Long innerVolumeLots, Long outerVolumeLots, BigDecimal innerPercent, BigDecimal outerPercent, Long bidTotalLots, Long askTotalLots, List<OrderBookLevel> levels)`。
  - `OrderBookLevel(int level, BigDecimal bidPrice, Long bidVolumeLots, BigDecimal askPrice, Long askVolumeLots)`。
  - `market!="台股"`、`code="0000"` 或 code 不符 `^[A-Za-z0-9.\-]{1,12}$` 時，建立 HTTP request 前即回 `supported=false, available=false, levels=[]`。
- [ ] 348.2 對支援標的 GET `https://tw.stock.yahoo.com/quote/{code}.TW`。HTTP client 使用 HTTP/1.1、follow redirects、connect timeout 5 秒；request timeout 12 秒；固定 `User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 AssetManagementQuoteDetail/1.0`，並帶 `Accept-Encoding: identity`。建構方式須可注入 fixture client，測試釘住 timeout 與 headers。body 上限 2 MiB，超過即 unavailable。
- [ ] 348.3 不 eval JavaScript、不解析整個 `root.App.main`。HTML 中定位 `"quote":{"data":` 後，從 data object 的 `{` 開始以 `depth/inString/escaped` balanced scanner 擷取完整 object，只把該 object 交 Jackson。marker 缺失、括號未閉合或 JSON 壞掉都 unavailable；不得用 regex 逐欄猜值。
- [ ] 348.4 `available=true` 唯一升格規則：先驗 `systexId==requested code`、`currency="TWD"`、`exchange∈{TAI,TWO}`；再驗 `price` 與 `regularMarketPreviousClose` 都是 object 且各自 `raw` 為正數，`orderbook` 存在且為 array。任一身分或核心 container/key/type/value 不符皆整份 unavailable。核心結構完整後，其他摘要欄可 null、orderbook 可空／不足五列 padding、單 cell 非法只 null 該 cell。
- [ ] 348.5 mapping 與算術：
  - 價：`price.raw`、`regularMarketPreviousClose.raw`、`regularMarketOpen.raw`、`regularMarketDayHigh.raw`、`regularMarketDayLow.raw`、`avgPrice`，僅正值有效。
  - 漲跌：`change.raw` 與去除 `%` 的 `changePercent`，保留正負與 0。
  - `turnoverYi = turnoverM / 100`（來源單位百萬元，100 百萬元＝1 億元）。
  - `volumeK/previousVolumeK/inMarket/outMarket` 皆以張回 Long，只接受非負。
  - `symbolName` 映為 `stockName`，空白為 null；`regularMarketTime` 原值映為 `sourceTime`，缺失為 null；`fetchedAt` 記 external 完成解析的台北牆鐘 ISO-8601，不能代替 sourceTime。
  - `marketStatus="open"` 映為 `OPEN`；Yahoo 正式收盤 token `marketStatus="close"` 映為 `CLOSED`，並可相容接受 `closed`；其他 token 映為 `UNKNOWN`。
  - `amplitudePercent=(high-low)/previousClose*100`，三值合法且 high≥low 時才算，scale 2／HALF_UP。
  - 內外盤分母為 `inner+outer`；分母正數時 inner scale 2／HALF_UP、outer 用 `100.00-inner` 收斂，否則兩者 null。不得以總量補分母，中性成交可以不屬任一盤。
  - `orderbook[0..4]` 映為 level 1..5；bid/ask 價僅正值，bidVolK/askVolK 僅非負。來源不足五列時 pad 到五列；單 cell 非法只令該 cell null，不造 0。
  - 固定五列完成後由 external mapper 計算 `bidTotalLots/askTotalLots`：該側五格全 null 回 null；否則加總非 null 值，null cell 視為 0。business 與 exact BFF 原樣轉交，frontend 不做 reduce。
- [ ] 348.6 `GET /internal/quote-detail?code=...&market=...` 只委派 client。在 external 可服務時，任何支援標的 Yahoo／解析查詢失敗均回 HTTP 200、`supported=true, available=false, source="YAHOO_TW", message="暫時無法取得行情五檔", levels=[]`；browser response 不含 raw HTML、stack trace、完整外部 URL 或 exception message。

### backend／BFF：分層 proxy

- [ ] 348.7 `MarketDataService` 新增與 external JSON 同形的 records 與 `getQuoteDetail(code,market)`，GET external `/internal/quote-detail`。成功原樣回傳；external 連線／解碼／5xx 失敗時回 typed unavailable，不 throw 到 controller。這保證 BFF 與 business 可服務時 upstream 失敗仍回 HTTP 200；純 Gateway route 不承諾 business 或 BFF 本身失聯時仍能製造 200，這兩種故障維持標準 5xx／網路失敗。business 不直接連 Yahoo、不解析 HTML、不快取或落地資料。
- [ ] 348.8 `MarketDataController` 新增 `GET /api/market-data/quote-detail`，`code` 套既有 `CODE_PATTERN`、`market` 套既有 `MARKET_PATTERN`，Controller 只委派 service。
- [ ] 348.9 `StockAnalysisBffRoutes` 新增唯一 exact route `/api/bff/stock-analysis/quote-detail`，rewrite 至 `/api/market-data/quote-detail`；不得新增 wildcard、9090 route、匿名 SecurityConfig 放行或新的直連 external WebClient。同步更新 `StockAnalysisBffRoutes` 與 `StockAnalysisChartBffController` 兩處硬編「五條 route」的 Javadoc 為六條或改成不硬編數量。

### frontend：共用 popup 的行情五檔

- [ ] 348.10 `frontend/src/api/index.js` 的 `bffApi.stockAnalysis` 新增 `getQuoteDetail(code,market)`，只呼叫 `/bff/stock-analysis/quote-detail`，axios config 必須包含 `skipErrorToast: true`；所有 HTTP／decode 錯誤由行情五檔 tab 的局部 state 顯示可重試錯誤，不得觸發全域 `ElMessage.error`。
- [ ] 348.11 `StockAnalysisDialog.vue` 在「走勢圖」後新增 `v-if="stock?.market === '台股' && stock?.stockCode !== '0000'"` 的「行情五檔」tab。所有父 view 因共用元件自然生效；不得另做 popup。`onOpen()` 仍預設 chart，並重設新的 `quoteDetail` state，不碰既有 `series/intraday/dividend` 語意。
- [ ] 348.12 新增獨立 `quoteDetail`、`quoteDetailLoading` state。第一次 `onTabChange('quote-detail')` 且尚無成功資料時抓一次；成功後切走再切回不重抓；頁籤內「重新整理」按鈕明確重抓。失敗只顯示新 tab 空狀態與按鈕，不清空走勢圖、intraday 或股利資料。
- [ ] 348.13 摘要 CSS grid 固定兩欄六列，窄螢幕可降一欄；順序精確為：
  ```text
  成交          昨收
  開盤          漲跌幅
  最高          漲跌
  最低          總量
  均價          昨量
  成交金額(億)  振幅
  ```
  `price/open/high/low` 各自與昨收比較：高紅、低綠、同值灰黑；昨收／均價中性。漲跌與漲跌幅正值紅 `▲`、負值綠 `▼`、零灰黑。所有 formatter 用 `value == null` 判缺；0 要顯示 0，null 才顯示 `—`。
- [ ] 348.14 摘要下方顯示內盤綠、外盤紅的張數與百分比及雙色比例條。兩者百分比皆 null 時顯示無分類資料。不得附加買賣建議解讀。
- [ ] 348.15 五檔固定五列，header 為「量／委買價／委賣價／量」；bid 與 ask 各以自身五列最大量算淡藍 bar width，denominator 0 時 width 0。數字文字不可被 bar 遮住。左右小計直接顯示 response 的 `bidTotalLots/askTotalLots`；null 顯示 `—`，frontend 不得從 `levels[]` 加總。
- [ ] 348.16 頁籤右上顯示「Yahoo 股市」、`sourceTime` 轉 Asia/Taipei 的日期時間、狀態徽章「盤中／收盤／狀態未知」與重新整理。休市 snapshot 必須標「收盤」，不可叫即時；sourceTime null 顯示「時間不明」，不得以 fetchedAt／現在時間替代。

### 自動化測試與不回歸

- [ ] 348.17 external tests 以完整 fixture 驗 TAI/TWO、12 格、五檔、算術、padding、`bidTotalLots/askTotalLots`（含該側全 null→null、局部 null→其餘加總與合法 0）、`symbolName/regularMarketTime`、`marketStatus="open"→OPEN`、正式 `marketStatus="close"→CLOSED`、相容 `marketStatus="closed"→CLOSED`、未知 token→`UNKNOWN`、marker 外 undefined、字串 braces/escape；client 注入固定 `Clock`，斷言 `fetchedAt` 的台北 ISO-8601 值，並驗 `regularMarketTime` 缺失時 `sourceTime=null` 且不以 `fetchedAt` 代替。identity 正確但 price／previousClose 核心 key/container 缺失、核心價無效或 orderbook 非 array都 unavailable；核心完整但 optional 摘要缺失／單 cell 非法仍 available 且只該欄 null。另驗 mismatch、oversize、missing marker、unbalanced/malformed、非台股／0000 零外呼。
- [ ] 348.18 backend tests 覆蓋 service 成功 proxy（fixture 帶不同 `bidTotalLots/askTotalLots` 並斷言原值不變）、external 錯誤 typed unavailable、controller code／market constraint；BFF route test 精確斷言 path 與 rewrite。新增 `frontend/src/utils/stockAnalysisDialog.contract.test.js` 並加入 `frontend/package.json` 的 `npm test`，以 Node source assertion 釘住 tab 條件、12 個 label、五列、`—`、`CLOSED` 顯示「收盤」、重新整理入口、`skipErrorToast: true`、小計直接讀 response `bidTotalLots/askTotalLots`，並限縮斷言 quote-detail 小計分支不得對 `levels[]` 做 `reduce`；另釘住禁止把 quote-detail 接到估值／SSE／Requirement 108 以外的 `/api/quotes*` consumer 的邊界。不為單一元件引進另一套 runner，production build 亦須通過。
- [ ] 348.19 不改既有 `/api/quotes` 的 raw 19 欄 Redis 契約、PriceResult／Redis schema、PricePoller、排程清單、SecurityConfig、DB／Liquibase、SSE 或其他三個市場資料流；Requirement 108／Task 372 是唯一對外巢狀 `marketData.quoteDetail` 市場投影例外，不改 raw 值或把 Yahoo 用於其他 consumer；不做逐筆、券商分點、零股五檔、WebSocket、自動下單或背景輪詢。

## 驗證

```bash
# module tests
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# frontend production build
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build

# 回到 repo root，實機依 upstream → downstream 重建；每次 upstream recreate 後 restart BFF
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
docker compose -p asset-management restart bff
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management restart bff
docker compose -p asset-management build --no-cache bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend

# health（功能 endpoint 需登入，實際資料由瀏覽器驗）
curl -sI http://localhost/ | head -1
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
```

瀏覽器驗收：

- [ ] 以 `00697B 元大美債7-10` 開 popup，切「行情五檔」；12 個摘要值、內外盤、五列價量與雙方小計皆有合理資料，sourceTime／市場狀態可見。兩者有值時內外盤百分比加總 100.00%。
- [ ] 另以一檔集中市場股票驗 `TAI`、一檔上櫃股票驗 `TWO`。按重新整理後 sourceTime 不倒退；若來源同秒，資料仍可合法相同。
- [ ] 美股、英股與 `0000` 不顯示 tab；走勢圖期間／指標、ETF 持股明細、股利歷史不回歸。
- [ ] 四個容器 healthy，frontend root 200、BFF actuator UP，logs 無新增持續 ERROR。驗收前後記錄 image SHA，結論前確認未被另一個 worktree 的重建覆蓋。

## 完成報告

**完成日期：** 待實作後填寫
**變更檔案：** 待實作後逐檔列出
**測試結果：** 待填寫（各 module tests/failures/errors/skipped 與 frontend build）
**Docker／瀏覽器驗收：** 待填寫（TAI、TWO、00697B 摘要／內外盤／五檔、容器與 image SHA）
**與原規格偏差：** 待填寫；無則寫「無」
