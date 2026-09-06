# [t420] API logs 篩選下拉與 canonical API URL 顯示

**對應 Requirements:** Requirement 141（管理者以來源、時間與單一 API 查詢既有 API error log，並維持只讀、管理者專屬與 lazy stacktrace detail）
**前置任務:** 無（本檔已完整列出本項既有功能擴充所需的資料契約與邊界）
**Liquibase changeset:** 無

## 背景

現有「API logs 查詢」頁以三個 radio button 呈現來源「全部／開放 API／富邦證 API」，時間排序與單一 API
兩個 select 等寬，且單一 API option 只顯示中文名稱。既有 operations read model 只回傳
source、operationKey、apiName、displayOrder，因此前端沒有安全、固定的 canonical method/path 可以顯示。

本任務只改善管理者已登入頁面的查詢控制項與其唯讀 catalog metadata：來源改為一個下拉選單、時間排序與
單一 API 在其可用篩選寬度按 1:2 排列，且單一 API 的 option 和選取後文字都必須顯示中文名稱與
HTTP method/canonical path。本任務不是新 API、不是 Fubon SDK 呼叫、不是 error log 寫入或 retention 變更，
也不能讓顯示 catalog 的動作觸發 catalog 中任何一條 API。

## 要做什麼

- [ ] 420.1 **在固定 technical catalog 增加唯讀 apiUrl metadata，並維持既有 read endpoint。**
  ApiErrorLogOperationCatalog.Operation 的每一筆固定 catalog entry 必有以下非 null 欄位：
  source、operationKey、apiName、apiUrl、displayOrder。其中 apiUrl 是精確的
  HTTP method + canonical path 字串，例如 GET /api/quotes；它不是可變設定、不是 client input、
  不是完整 remote/vendor/container host URL、不是 query，也不含 token、certificate、帳號或任何 credential。
  它只存在於程式內 immutable catalog/read response，不新增 DB 欄位、不寫入
  api_error_log_operation 或 api_error_log，也不改既有 api_name 歷史 snapshot 語意。

  既有下列兩個 endpoint 必維持原 path、method、query、ADMIN 雙重守門與排序規則，僅讓 operations
  response 多出 apiUrl：

  ~~~text
  GET /api/api-error-logs/operations?source=ALL|OPEN_API|FUBON_API
  GET /api/bff/api-error-logs/operations?source=ALL|OPEN_API|FUBON_API
  ~~~

  business operations response 的每個 JSON item schema 固定如下；BFF 必作 opaque proxy，不能重新組裝、
  猜測、刪除或改寫 apiUrl：

  ~~~json
  {
    "source": "OPEN_API",
    "operationKey": "OPEN_QUOTES_LIST",
    "apiName": "即時報價清單",
    "apiUrl": "GET /api/quotes",
    "displayOrder": 10
  }
  ~~~

  source=ALL 的順序仍是 OPEN_API、FUBON_API、displayOrder、operationKey；單一 source 仍是
  displayOrder、operationKey。list 和 detail response 不增加 apiUrl，stacktrace 的 list/detail 分離不變。

- [ ] 420.2 **逐筆固定 canonical mapping。** 下表是本任務唯一允許的 catalog
  (source, operationKey, apiName, apiUrl, displayOrder) 資料；每個 apiUrl 必逐字使用表內
  HTTP method/path。兩個台股報價 operation 可共用相同 path，但不得合併 key。FUBON_API 的 path 是系統內
  canonical internal method/path，絕不補上 vendor host、credentials 或未驗證 placeholder。

  | source | order | operationKey | apiName | apiUrl |
  |---|---:|---|---|---|
  | OPEN_API | 10 | OPEN_QUOTES_LIST | 即時報價清單 | GET /api/quotes |
  | OPEN_API | 20 | OPEN_QUOTES_ONE | 單一即時報價 | GET /api/quotes/one |
  | OPEN_API | 30 | OPEN_MARKET_INDEX | 大盤指數 | GET /api/public/market-index |
  | OPEN_API | 40 | OPEN_LATEST_ASSETS | 最新資產 | GET /api/assets/latest |
  | OPEN_API | 50 | OPEN_USD_TWD | 美元兌台幣 | GET /api/public/exchange-rate/usd-twd |
  | OPEN_API | 60 | OPEN_MARKET_ANALYSIS_TODAY | 今日市場分析 | GET /api/public/market-analysis/today |
  | OPEN_API | 70 | OPEN_PORTFOLIO_ADVICE_LATEST | 最新資產配置建議 | GET /api/public/portfolio-advice/latest |
  | OPEN_API | 80 | OPEN_TRADING_RADAR_TODAY | 今日交易雷達 | GET /api/public/trading-radar/today |
  | OPEN_API | 90 | OPEN_TRADING_RADAR_STOCK | 單一標的交易雷達 | GET /api/public/trading-radar/stock |
  | OPEN_API | 100 | OPEN_TRANSACTIONS | 公開交易紀錄 | GET /api/public/transactions |
  | OPEN_API | 110 | OPEN_TRADING_CALENDAR | 交易日曆 | GET /api/public/trading-calendar |
  | OPEN_API | 120 | OPEN_COMMODITY_PRICES | 油價金價 | GET /api/public/commodity-prices |
  | OPEN_API | 130 | OPEN_CRAWLER_RESCAN | 公開爬蟲重新掃描 | POST /api/public/crawler-data/rescan |
  | FUBON_API | 10 | FUBON_PORTFOLIO_READ | 庫存與未實現損益 | POST /internal/portfolio/read |
  | FUBON_API | 20 | FUBON_TW_QUOTES_INVENTORY | 庫存同步台股報價 | POST /internal/market-data/tw-quotes |
  | FUBON_API | 30 | FUBON_FILLED_TRADES_READ | 已成交交易查詢 | POST /internal/trades/read |
  | FUBON_API | 40 | FUBON_ETF_HOLDINGS_READ | ETF 成分股查詢 | POST /internal/market-data/etf-holdings |
  | FUBON_API | 50 | FUBON_BANK_BALANCE_READ | 交割銀行餘額 | POST /internal/bank-balance/read |
  | FUBON_API | 60 | FUBON_SETTLEMENT_READ | 交割款查詢 | POST /internal/settlement/read |
  | FUBON_API | 70 | FUBON_REALIZED_GAINS_READ | 已實現損益 | POST /internal/realized-gains/read |
  | FUBON_API | 80 | FUBON_TW_QUOTES_LIVE | 盤中台股即時報價 | POST /internal/market-data/tw-quotes |
  | FUBON_API | 90 | FUBON_TAIEX_INDEX_STREAM | 加權指數串流 | GET /internal/market-data/taiex-index/stream |
  | FUBON_API | 100 | FUBON_DIVIDENDS_READ | 股利資料查詢 | POST /internal/market-data/dividends/read |
  | FUBON_API | 110 | FUBON_TECHNICAL_INDICATORS_READ | 技術指標查詢 | POST /internal/market-data/technical-indicators/read |
  | FUBON_API | 120 | FUBON_STOCK_BASIC_READ | 個股基本資料查詢 | POST /internal/market-data/stock-basic/read |
  | FUBON_API | 130 | FUBON_INTRADAY_CANDLES_READ | 分鐘 K 線查詢 | POST /internal/market-data/intraday-candles/read |
  | FUBON_API | 140 | FUBON_STOCK_PUSH_SUBSCRIPTIONS | 個股推播訂閱 | POST /internal/market-data/stock-push/subscriptions |
  | FUBON_API | 150 | FUBON_STOCK_PUSH_STREAM | 個股推播串流 | GET /internal/market-data/stock-push/stream |

- [ ] 420.3 **將 API logs 查詢頁改為三個下拉選單，並鎖定 layout 和顯示文字。**
  在 frontend/src/views/ApiErrorLogsView.vue，來源控制項必是單一 el-select（aria-label="來源"），
  option value/text 精確為 ALL／「全部」、OPEN_API／「開放 API」、FUBON_API／「富邦證 API」；
  初始 source 是 ALL。移除來源用的 el-radio-group 與所有 el-radio-button，不能只是把 radio 藏起來。

  篩選列固定為來源、時間排序、單一 API 三欄；桌面寬度的 CSS grid 必為：

  ~~~css
  grid-template-columns: 160px minmax(0, 1fr) minmax(0, 2fr);
  gap: 12px;
  ~~~

  三個 select 各自填滿其 grid column。第一欄來源固定 160px；扣除它和 gap 後，第二欄時間排序正好占
  剩餘篩選寬度 1/3，第三欄單一 API 正好占 2/3，不得再讓後兩欄等寬。時間排序保留
  NEWEST／「由近而遠」與 OLDEST／「由遠而近」；單一 API 保留可清除和「單一 API」placeholder。

  單一 API 的 value 仍是 operationKey。每一個 el-option 的 label，及 Element Plus 在選取後顯示的
  selection label，都必精確使用 apiName + " — " + apiUrl。例如 option 與已選文字必可讀為
  「即時報價清單 — GET /api/quotes」和
  「庫存與未實現損益 — POST /internal/portfolio/read」；只顯示中文名稱、只顯示 path 或只顯示 key 都不合格。

  來源變動必先把 operationKey 清為 null，再依新 source 讀取既有 operations endpoint，最後重抓 list；
  時間排序或單一 API 變動只重抓 list。table 仍只顯示時間、來源、保存的 apiName、messageHeader，且保留
  既有第一次展開才取得 detail、成功 stacktrace 以 id cache、Vue interpolation/textContent 顯示、禁止
  v-html 的行為。

- [ ] 420.4 **保持資料、路由、安全與金融 I/O 邊界。** 前端繼續只經既有
  bffApi.apiErrorLogs 呼叫 BFF，不得直接呼叫 business 或任一 catalog 顯示的 API path。不得新增或修改
  9090 gateway/Tailscale/OpenAPI route、BFF/business route、HTTP method、query、SecurityConfig、ADMIN 語意、
  DB schema、Liquibase、db/schema.sql、error log 寫入／retention、catalog seed 內容或 stacktrace sanitization。
  不得因顯示 apiUrl 呼叫 POST /api/public/crawler-data/rescan、任何 /internal/... path、Fubon SDK、
  broker、manual sync、refresh 或其他外部 I/O；不新增任何下單、改單、撤單、轉帳、圈存或資金副作用。

- [ ] 420.5 **新增或調整可重現的測試。**
  - backend unit/controller tests 必逐筆驗證 28 項 fixed catalog 的 source、key、中文 apiName、apiUrl 與 order，
    並驗 source=ALL/OPEN_API/FUBON_API 的既有 deterministic order；apiUrl 必非 null/空白，且 JSON operations
    response 恰有五個欄位並含正確 method/path。list/detail 不得因本變更冒出 apiUrl。
  - BFF WebFlux test 必以含 apiUrl 的 fake business operations response 驗證既有
    /api/bff/api-error-logs/operations 逐字 relay 該欄位與既有 source query，沒有 BFF catalog fork 或轉換；
    既有未登入／非 ADMIN 拒絕行為不變。
  - frontend/src/utils/apiErrorLogsView.contract.test.js 必改為驗證：存在一個來源 select 和三個固定 source
    option value/text、沒有 radio group/radio button、grid columns 為 160px/1fr/2fr、option/selection label 同時引用
    apiName 和 apiUrl、source change 先清 operationKey、lazy detail/id cache 及無 v-html 仍成立。
  - 全部測試只能用 catalog/fake response，不可呼叫 public crawler rescan、internal Fubon path、真實 Fubon SDK 或
    寫入任何 broker/金融資料。

## 驗證

先執行下列可重現的本地測試與靜態檢查：

~~~bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
bash scripts/spec-check.sh
~~~

實際 Docker 驗收必重建並依上游到下游順序 recreate 已變更服務；business-services 重建後一定要再 recreate
BFF，避免 JVM 保留舊 upstream IP：

~~~bash
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management up -d --no-deps --force-recreate bff
docker compose -p asset-management up -d --no-deps --force-recreate frontend
docker compose -p asset-management ps business-services bff frontend
~~~

三個 container 均為 healthy 後，以既有 ADMIN 帳號登入前端並開啟 /api-error-logs。在桌面寬度至少
1280px 的畫面驗證以下全部結果：

1. 來源是單一 select，沒有可見或隱藏的來源 radio controls；預設「全部」，可切換「開放 API」及「富邦證 API」。
2. 來源欄為 160px；在它和 12px gap 之外，時間排序與單一 API 的 rendered width 為 1:2，非等寬。
3. operations response 及下拉 option 都有 apiUrl。選擇「開放 API」時可看見並選取
   「即時報價清單 — GET /api/quotes」；選擇「富邦證 API」時可看見並選取
   「庫存與未實現損益 — POST /internal/portfolio/read」；兩者被選取後的 select 文字仍同時含中文名稱和
   method/path。
4. 切換來源會清掉先前單一 API 選擇並重抓 catalog/list；改時間排序或單一 API 不重抓 catalog。table、lazy
   stacktrace detail、純文字輸出與 ADMIN 限制維持原狀。
5. Browser network 只使用既有的 BFF operations/list/detail read requests；不直接呼叫任何表內 public/internal
   API，尤其不得呼叫有外部抓取副作用的 POST /api/public/crawler-data/rescan，也不得連線 Fubon SDK/broker。

## 完成報告

（實作者完成後回填：實際改動檔案、上述測試與 Docker 驗收輸出、視覺驗收證據，以及任何與本計畫不符的差異與原因。本次僅完成規格撰寫，尚未實作。）
