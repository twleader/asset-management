# 9090 Port API Swagger

本文件由 `docs/openapi/docker-external-api.yaml` 自動產生；請勿手動修改。兩個 Markdown 位置必須位元組一致。

## 概覽

| 項目 | 值 |
| --- | --- |
| OpenAPI | `3.1.0` |
| 契約版本 | `1.10.0` |
| 對外路徑 | 13 條：12 個 `GET`、1 個 `POST` |
| Servers | `http://127.0.0.1:9090`、`https://mac-mini-2.tailccc7be.ts.net:9090` |
| 應用層 security | `[]`；實際邊界為 loopback 或獲准 Tailscale identity，非公網服務。 |

本文件只描述 `api-gateway` 對 Docker host 與 Tailscale 私有網路開放的十三條精確路徑：
十二條 GET（其中 transactions／trading-radar 是 configured-admin owner 範圍，calendar 與 commodity-prices 是 global no-tenant）
＋ 一條寫入 POST（`/api/public/crawler-data/rescan`，唯一有外部抓取副作用者，
經 business 端 30 秒全域 Redis 冷卻節流）。

本機入口只綁定 loopback `127.0.0.1:9090`；遠端入口由 Tailscale Serve 的私有網路身分與
十三條逐一路徑規則形成網路邊界。HTTP 層本身沒有 OAuth、API key 或其他應用層認證，因此
全域 `security` 為空陣列。不得把任一 server URL 解讀為公開網際網路服務。

Gateway 只接受下列精確路徑：同一路徑的非合法 method 回 `405 Method Not Allowed`，
十二條唯讀路徑帶 `Allow: GET`、`/api/public/crawler-data/rescan` 帶 `Allow: POST`；
所有其他路徑、子路徑、尾斜線與 matrix 變體回 `404 Not Found`。
Swagger UI 或原始 OpenAPI 文件沒有對外路由；本 YAML 是版本控管文件，不代表 gateway
暴露文件端點。

回應只代表來源或快取當下狀態，排程不可只看 HTTP 200；應另檢查報價的
`quoteStatus`／`tradingDate`／`updatedAt`、ETF 的 `premiumDiscountPct`、資產的 `targetPriceComplete`／`valuationSource`，
匯率的 `liveUpdateStatus`／`spot.quoteStatus`，以及交易雷達的 `generatedAt` 與各項證據。
交易雷達會揭露 configured admin 的持倉／觀察清單與決策證據；只能透過本機 loopback
或獲准 Tailscale identity 讀取，不可開成公網。本 API 不構成交易執行依據。

## 路由總覽

| # | Method | Path | operationId | 摘要 | 成功回應 |
| ---: | --- | --- | --- | --- | --- |
| 1 | `GET` | `/api/quotes` | `listLatestQuotes` | 列出快取報價與四頁籤公開市場資料 | 200 application/json: array of ListedLatestQuote |
| 2 | `GET` | `/api/quotes/one` | `getLatestQuote` | 查詢單一標的的快取報價與四頁籤公開市場資料 | 200 application/json: DetailedLatestQuote<br>204  |
| 3 | `GET` | `/api/public/market-index` | `getPublicMarketIndex` | 取得大盤日線或當日分時圖表 | 200 application/json: MarketIndexResponse |
| 4 | `GET` | `/api/assets/latest` | `getLatestAssets` | 取得主要管理者的最新完整資產 | 200 application/json: LatestAssetsResponse |
| 5 | `GET` | `/api/public/exchange-rate/usd-twd` | `getPublicUsdTwd` | 取得 USD/TWD 即期與近一年歷史 | 200 application/json: UsdTwdResponse |
| 6 | `POST` | `/api/public/crawler-data/rescan` | `triggerPublicCrawlerRescan` | 免登入觸發爬蟲重新搜尋 | 200 application/json: CrawlerRescanResponse |
| 7 | `GET` | `/api/public/market-analysis/today` | `getPublicMarketAnalysisToday` | 取得最近一筆今日股市分析 | 200 application/json: MarketAnalysisResponse |
| 8 | `GET` | `/api/public/portfolio-advice/latest` | `getPublicPortfolioAdviceLatest` | 取得主要管理者的最新資產配置建議 | 200 application/json: PortfolioAdviceResponse |
| 9 | `GET` | `/api/public/trading-radar/today` | `getPublicTradingRadarTodayList` | 取得今日交易雷達第一屏收合列 | 200 application/json: TradingRadarListResponse |
| 10 | `GET` | `/api/public/trading-radar/stock` | `getPublicTradingRadarStockDetail` | 取得今日交易雷達指定股票的展開資料 | 200 application/json: TradingRadarStockDetailResponse |
| 11 | `GET` | `/api/public/transactions` | `getPublicTransactionHistory` | 取得 configured-admin 的唯讀交易紀錄 | 200 application/json: PublicTransactionHistoryResponse |
| 12 | `GET` | `/api/public/trading-calendar` | `getPublicTradingCalendar` | 取得指定年度台、美、英交易日曆 | 200 application/json: PublicTradingCalendarResponse |
| 13 | `GET` | `/api/public/commodity-prices` | `getPublicCommodityPrices` | 一次取得 WTI、Brent 與黃金的已持久化報價 | 200 application/json: CommodityPriceBatchResponse |

## 路由詳情

### 1. `GET /api/quotes`

BFF 只 relay external-materials Redis raw `LatestQuote` 的既有 19 欄，並在其後固定加入
`marketData.chart`、`quoteDetail`、`etfConstituents`、`dividends` 四個公開市場資料 child，
並在 marketData 後依序直接提供同一份 normalized immutable `quoteDetail`、`bidLevels`、
`askLevels`、`dividendHistory` 供批次程式使用。
market 省略時掃描台股、美股、英股；raw index 的順序、空快取 `[]` 與 raw 讀取語意不變。
`marketData` 不讀、也不含 configured admin、使用者、帳戶、券商、個人持股／成本／交易／快照／配置。
list 固定使用 `ListedLatestQuote` 的既有 24 欄，零 Fubon full-response bridge calls；不新增 Fubon
metadata、failure、raw returned order-book 或 process-global counters。
台股 `quoteDetail`／direct 五檔來自 producer 已保存的同一 immutable canonical snapshot：富邦是每十秒
round 的 primary，只有該 round 的 selected code 缺有效完整五檔時，獨立 Yahoo worker 才會非同步逐檔
fallback。available snapshot 的 source 僅可能為 `FUBON_BOOKS` 或 `YAHOO_TW`，兩者都必須是完整五檔，
不會以 generic buy/sell 或跨來源欄位拼湊。查詢只把 Redis 當 revision-verified candidate 並讀 PostgreSQL
canonical；絕不在 request-time 向 Yahoo、富邦、dispatcher 或 worker 外呼／寫入，也不用於估值、損益、
下單、警示或任何個人資產判斷。

#### Query 參數

| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |
| --- | --- | --- | --- | --- |
| `market` | 否 | `string` | enum: `台股`, `美股`, `英股`<br>example: `台股` | 選填的單一市場；可用值為台股、美股、英股，省略時回三個市場。 |
| `start` | 否 | `string (date)` | example: `2025-01-15` | 與 end 同時提供的 ISO 日期；控制每筆 chart 的共同起日，最早為台北今天往前十年。 |
| `end` | 否 | `string (date)` | example: `2026-01-15` | 與 start 同時提供的 ISO 日期；不得晚於台北今天。兩者皆省略時為近一年。 |

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: array of ListedLatestQuote | 最新報價陣列；可能為空。 |
| `400` | application/problem+json: ProblemDetail | start/end 未同時提供、格式不合法、順序錯誤、未來日或超過十年窗口。 |
| `502` | text/html: NginxErrorHtml | HTTP 502 Bad Gateway response class；Nginx 無法連接或取得 upstream 回應時回傳。body 是 `text/html` 的 `NginxErrorHtml` item，不是 JSON ProblemDetail；gateway 不攔截、不改寫成 JSON 或空 200。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 2. `GET /api/quotes/one`

只讀目前 external-materials Redis raw cache；price key cache miss 維持 204，不查資料庫且不觸發 quote producer。
raw 19 欄的名稱、型別、值與宣告順序不變，成功才加固定 `marketData` 四 child，後接同一份
normalized direct quoteDetail／bidLevels／askLevels／dividendHistory。single response 只在最後追加去重的
Fubon metadata、failure state 與 raw returned order-book，沒有 nested Fubon quote 或 global counters。台股五檔只讀 producer 已保存的
`FUBON_BOOKS` primary 或受限 `YAHOO_TW` fallback canonical snapshot；回應不會在 request-time 外呼、
寫入或修復快取。stored Fubon SUCCESS 只有 source、tradingDate、normalized updatedAt 都等於 raw cache
canonical quote 時才可填入同一份既有 top-level quote fields；不符合時 raw fields 與獨立 ETF NAV
`premiumDiscountPct` 完全保留。所有 child fail-soft，不會遮蔽有效 raw quote；本 API 不會回傳目前使用者的持股、成本、
交易或資產快照。

#### Query 參數

| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |
| --- | --- | --- | --- | --- |
| `code` | 是 | `string` | example: `EXM-TW-001` | 標的代號；controller 僅要求參數存在，不另做格式驗證。 |
| `market` | 是 | `string` | enum: `台股`, `美股`, `英股`<br>example: `台股` | 市場；可用值為台股、美股、英股。 |
| `start` | 否 | `string (date)` | example: `2025-01-15` | 與 end 同時提供的 ISO chart 起日；兩者皆省略時為台北今天往前一年。 |
| `end` | 否 | `string (date)` | example: `2026-01-15` | 與 start 同時提供的 ISO chart 迄日；最長十年且不得晚於台北今天。 |

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: DetailedLatestQuote | Redis 命中的最新報價。 |
| `204` |  | Price cache miss、price Redis 讀取失敗或 price payload 無法解析；沒有 response body。NAV enrichment 失敗不會產生 204。 |
| `400` | application/problem+json: ProblemDetail | code／market 缺少，或 start/end 未同時提供、格式不合法、順序錯誤、未來日或超過十年窗口。 |
| `502` | text/html: NginxErrorHtml | HTTP 502 Bad Gateway response class；Nginx 無法連接或取得 upstream 回應時回傳。body 是 `text/html` 的 `NginxErrorHtml` item，不是 JSON ProblemDetail；gateway 不攔截、不改寫成 JSON 或空 200。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 3. `GET /api/public/market-index`

參數會去除前後空白並忽略大小寫。range=d 為 INTRADAY，其餘為 DAILY；下游 transport、HTTP 或 decode 失敗採完整空資料 200，typed malformed tradeValue 則回 502。

#### Query 參數

| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |
| --- | --- | --- | --- | --- |
| `market` | 否 | `string` | enum: `TWSE`, `TPEX`, `DJI`, `SPX`, `IXIC`, `SOX`, `FTSE`, `DAX`, `KOSPI`, `N225`<br>example: `TPEX` | 指數 catalog code；可用值為 TWSE、TPEX、DJI、SPX、IXIC、SOX、FTSE、DAX、KOSPI、N225，空白或省略時預設 TWSE。 |
| `range` | 否 | `string` | enum: `d`, `1m`, `3m`, `6m`, `1y`, `2y`, `5y`, `10y`<br>example: `1m` | 圖表區間；可用值為 d、1m、3m、6m、1y、2y、5y、10y；d 表示當日分時，其餘為日線回看。 |

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: MarketIndexResponse | 完整、陣列彼此對齊的圖表 response；無資料時陣列為空且 nullable 摘要欄位為 null。 |
| `400` | application/problem+json: ProblemDetail | market 或 range 不在合法集合。 |
| `500` | application/json: SpringWebFluxBasicError | closePoint 為非法數字或發生其他未被 typed 502 捕捉的 shaping error 時，由 Spring WebFlux 基本錯誤處理回傳；不是 ProblemDetail。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF 判定下游 payload 格式錯誤，或 Nginx 無法連上 upstream。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 4. `GET /api/assets/latest`

唯讀聚合完整快照、同源即時估值與三市場狀態。snapshot.id 必須等於 liveAssets.snapshotId；不接受 ownerId、email、cookie 或 X-User-* 作為租戶選擇輸入。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: LatestAssetsResponse | 自洽且保留原始 BigDecimal JSON 精度的最新資產。 |
| `404` | application/problem+json: ProblemDetail | 主要管理者尚無資產快照；business 的 ProblemDetail 原樣 relay。 |
| `500` | application/problem+json: ProblemDetail | Business 聚合發生未預期錯誤，例如 snapshot identity 不一致。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF 驗證到空、malformed 或 identity 不一致的成功 payload，或 Nginx 無法連上 upstream。 |
| `503` | application/problem+json: ProblemDetail | Configured admin 不存在、未啟用或不可用。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 5. `GET /api/public/exchange-rate/usd-twd`

以 Asia/Taipei 當日計算 inclusive 的 today.minusYears(1)..today；history 日期升冪且不重複，count 必須等於 history 長度，中間價以 HALF_UP 固定計算至小數 4 位。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: UsdTwdResponse | 固定 USD/TWD metadata、即期狀態與非空歷史。 |
| `404` | application/problem+json: ProblemDetail | History 為零列，或 live/spot 完全不存在。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | 下游 transport、HTTP、decode、null 或資料契約驗證失敗，或 Nginx 無法連上 upstream。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 6. `POST /api/public/crawler-data/rescan`

十三條路由中唯一有外部抓取副作用者（Requirement 71／Task 329），語意等同既有 ADMIN 端點
「立即抓取並匯出」的匿名版本。由 business 端 30 秒全域 Redis 冷卻節流：冷卻窗口內回
HTTP 200 但 `status=COOLDOWN`，不會真的觸發抓取。呼叫端必須看 `status` 而不是只看 200。
本路徑只接受 POST；GET 等其他 method 回 `405` 並帶 `Allow: POST`。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: CrawlerRescanResponse | 觸發結果；所有情形（含冷卻中與失敗）都以 status 表達，不改用非 200。 |
| `405` | text/html: NginxErrorHtml | 非 POST method；帶 `Allow: POST`。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF 的 scoped advice 把 business 非 2xx 消毒成固定文案，或 Nginx 無法連上 upstream。 |
| `503` | application/problem+json: ProblemDetail | BFF 連不上 business（transport 失敗）；固定文案，不帶出下游訊息。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 7. `GET /api/public/market-analysis/today`

純唯讀、零副作用（Requirement 79／Task 338），**不會**觸發任何 LLM 產生流程。
`daily_market_analysis` 是全域參考資料（無 owner），故 BFF 不做 configured-admin bootstrap、
也不帶 `X-User-*`。尚無任何一筆分析時回 HTTP 200 的 `{"status":"NONE"}` 占位物件。
BFF 走 `.retrieve().bodyToMono(...)`，故 business 的非 2xx 一律由 scoped advice
消毒成固定文案（502／503），**不會**把 business 原始 body 交給匿名呼叫端。
另注意 `status` 可能是 `PROCESSING`／`FAILED`，排程不可只看 HTTP 200。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: MarketAnalysisResponse | 最近一筆分析；尚無資料時為 status=NONE 的占位物件。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF 的 scoped advice 把 business 非 2xx 消毒成固定文案，或 Nginx 無法連上 upstream。 |
| `503` | application/problem+json: ProblemDetail | BFF 連不上 business（transport 失敗）；固定文案，不帶出下游訊息。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 8. `GET /api/public/portfolio-advice/latest`

純唯讀、零副作用（Requirement 79／Task 338），**絕不**觸發 `POST /api/portfolio-advice/generate`
那條有 LLM 成本的寫入流程。資料是 owner-scoped，owner 只能是 business 唯一解析出的
configured admin：BFF 走 configured-admin bootstrap 並顯式帶 `X-User-*`，同時清除 Reactor
context 身分，故不接受 ownerId、email、cookie 或 `X-User-*` 作為租戶選擇輸入。
成功與下游非 2xx 都採 byte-relay（金融數值不經 Map/Double round-trip，狀態碼與 body 原樣傳回）；
只有 bootstrap 階段的失敗才由 scoped advice 消毒。尚無任何一筆建議時回 HTTP 200 的
`{"status":"NONE"}` 占位物件，`status` 亦可能是 `PROCESSING`／`FAILED`。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: PortfolioAdviceResponse | 最新一筆建議；尚無資料時為 status=NONE 的占位物件。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | bootstrap lookup 的 business 非 2xx 被 scoped advice 消毒成固定文案，或 Nginx 無法連上 upstream。 |
| `503` | application/problem+json: ProblemDetail | Configured admin 不存在、未啟用或不可用（具名 503，不是 404），或 BFF 連不上 business。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 9. `GET /api/public/trading-radar/today`

純唯讀、零副作用。owner 只能是 business 唯一解析的
configured admin；不接受 owner selector、email、cookie、匿名 caller 的 `X-User-*`
或 Tailscale header 作為租戶輸入。BFF 顯式傳遞 configured-admin tenant headers，
並清除 Reactor 內可能存在的登入者／代看身分。

本路徑每次只呼叫一次 business 的 current read，再投影第一屏的全域欄位與每檔收合列；
不回傳 reasons、evidence、完整 fundamental 或 K 棒展開樹。它不會 refresh、export、
發通知，也不會寫入交易雷達 snapshot、Redis 或 DB。要展開某一列時，改呼叫精確的
`/api/public/trading-radar/stock?stockCode=&market=`；detail 只會從本日 current result
中尋找該精確 market/code，絕不重新整理或讀取舊快照。

HTTP 層本身沒有應用層認證；只能經 `127.0.0.1:9090` loopback 或獲准的
Tailscale 私網 identity 讀取，禁止 Funnel 或公網 listener。Swagger UI 與本 YAML
都沒有掛在 9090。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: TradingRadarListResponse | 第一屏全域資料與每檔收合列；stocks 可為空。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | Business 的任何非 2xx 被 scoped advice 消毒，或 Nginx 無法連上 upstream。 |
| `503` | application/problem+json: ProblemDetail | Configured admin 不存在、不合法或 bootstrap／downstream transport 失敗。 |
| `504` | text/html: NginxErrorHtml | HTTP 504 Gateway Time-out response class；Nginx upstream timeout 時回傳，gateway 的 connect/send/read timeout 分別為 5 秒／30 秒／60 秒。body 是 `text/html` 的 `NginxErrorHtml` item，不是 RFC 7807 JSON。 |

### 10. `GET /api/public/trading-radar/stock`

只接受一個已 URL-encoded 的 `stockCode` 與一個 `market`，兩者共同構成精確 selector，
用以消除跨市場同代號歧義。BFF 先在本地驗證參數，通過後才以 configured-admin 身分
呼叫 business 一次 current read；不快取、不重新整理、不寫 snapshot，且只回本日結果
已存在的標的。不存在時固定回已消毒的 404，不洩漏其他標的或 owner 資訊。

#### Query 參數

| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |
| --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string` | pattern: `^[A-Za-z0-9.\\-]{1,12}$`<br>example: `2330` | 1 至 12 個英數、`.` 或 `-` 的股票代號；前後空白會移除。 |
| `market` | 是 | `string` | pattern: `^[\\p{L}0-9]{1,10}$`<br>example: `台股` | 1 至 10 個 Unicode 字母或數字的市場字串；前後空白會移除。 |

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: TradingRadarStockDetailResponse | 與 list 同一次 current-result 投影語意的指定標的完整展開樹。 |
| `400` | application/problem+json: ProblemDetail | 缺少、重複或格式不合法的 stockCode／market；不會呼叫 configured-admin 或 business。 |
| `404` | application/problem+json: ProblemDetail | 本日 current result 沒有精確 code/market 標的。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | Business 非 2xx 或 Nginx upstream failure；回應已消毒。 |
| `503` | application/problem+json: ProblemDetail | configured-admin 不可用，或 BFF transport failure。 |
| `504` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF current-read 五秒 timeout 或 Nginx gateway timeout。 |

### 11. `GET /api/public/transactions`

owner 只能由 BFF configured-admin bootstrap 決定，request 不可提供 owner、帳戶、券商或
身分 header 作選擇。無 query 時回全部紀錄；可精確擇一提供 `year`，或成對提供 `start`
與 `end`。篩選會在 BFF 本地嚴格驗證，錯誤不會觸發 bootstrap。此 API 不同步、匯出、寫入
或計算個別成交推論；records 固定按 `tradeDate DESC, id DESC`。

#### Query 參數

| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |
| --- | --- | --- | --- | --- |
| `year` | 否 | `string` | pattern: `^[0-9]{4}$`<br>example: `2026` | 與 start/end 互斥的四位西元年。 |
| `start` | 否 | `string (date)` | example: `2026-01-01` | 與 end 成對提供的 inclusive ISO 起日；不得和 year 同時提供。 |
| `end` | 否 | `string (date)` | example: `2026-12-31` | 與 start 成對提供的 inclusive ISO 迄日，且不得早於 start。 |

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: PublicTransactionHistoryResponse | 選定範圍的 immutable 交易帳本、全期與選定範圍摘要。 |
| `400` | application/problem+json: ProblemDetail | year 重複、year 與 date range 混用、缺少 range 一端、日期格式或順序不合法。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | Business 非 2xx 或 Nginx upstream failure；回應已消毒。 |
| `503` | application/problem+json: ProblemDetail | configured-admin 或 BFF transport 不可用。 |
| `504` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF 五秒 timeout 或 Nginx gateway timeout。 |

### 12. `GET /api/public/trading-calendar`

全域 no-tenant 唯讀路徑；不做 configured-admin bootstrap，也不接受任何 owner、帳戶或券商
selector。year 必須是 Asia/Taipei 當年加減一年內的一個四位年份。每個 request 只讀選定
年度的 TWSE/DGPA、NYSE、LSE authority 各一次並 defensive-copy；不 refresh、不寫快照、
不讀 broker。authority 部分不可用仍回 typed 200：其旗標與日數為 null，而其他 authority
保留可用結果；marketStatus 是獨立的當前 session read。

#### Query 參數

| 名稱 | 必填 | 型別 | 限制／範例 | 說明 |
| --- | --- | --- | --- | --- |
| `year` | 是 | `string` | pattern: `^[0-9]{4}$`<br>example: `2026` | Asia/Taipei 當年減一、當年或加一的四位西元年。 |

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: PublicTradingCalendarResponse | 指定年份完整 365 或 366 日的 typed 日曆，以及 authority availability。 |
| `400` | application/problem+json: ProblemDetail | 缺少、重複、非四位或不在 Asia/Taipei current±1 的 year；不會讀 business。 |
| `502` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | Business 非 2xx 或 Nginx upstream failure；回應已消毒。 |
| `503` | application/problem+json: ProblemDetail | BFF 無法連線 business。 |
| `504` | application/problem+json: ProblemDetail; text/html: NginxErrorHtml | BFF 五秒 timeout 或 Nginx gateway timeout。 |

### 13. `GET /api/public/commodity-prices`

Global no-tenant 的純唯讀 batch 路徑；每個合法 request 只透過 BFF 的 no-tenant container client
讀一次既有 business `GET /api/market-data/commodity/live`，由該聚合既有的 Redis spot cache 與
PostgreSQL 前收投影固定三個 slot。它不讀 caller identity、owner、帳戶、持股、交易或 broker，
不直接讀 Redis/DB，且絕不 request-time 呼叫 Yahoo、Fubon、refresh、排程或任何寫入。
不接受 named query parameter 或 GET body；bare trailing `?` 視同沒有 query。個別 slot 的 null 是
該 persisted spot 不可用，仍是合法 200，不能解讀成零價、停盤、refresh 或整個 downstream 失敗。

#### Responses

| Status | Content／schema | 說明 |
| --- | --- | --- |
| `200` | application/json: CommodityPriceBatchResponse | 固定順序 WTI、BRENT、GOLD 的 immutable persisted quote slots；任一 slot 可為 null。 |
| `400` | application/problem+json: PublicCommodityPriceProblemDetail | 有任何 named、空值、重複或多值 query parameter，或 GET 帶 positive Content-Length／Transfer-Encoding body；BFF local gate 零 downstream request。 |
| `405` | text/html: NginxErrorHtml | Gateway 對這條 known exact path 的非 GET method 回應；帶 `Allow: GET`，不會轉送 BFF。 |
| `502` | application/problem+json: PublicCommodityPriceProblemDetail; text/html: NginxErrorHtml | BFF 將 business non-2xx、空／HTML／malformed 或違反固定 slot invariants 的成功 body 消毒為 ProblemDetail；若 Nginx 本身無法連上 BFF，則是並列的 text/html gateway alternative。 |
| `503` | application/problem+json: PublicCommodityPriceProblemDetail | BFF 的唯一 no-tenant business connection/transport 無法建立；回應不含 downstream URL、exception 或 tenant 資訊。 |
| `504` | application/problem+json: PublicCommodityPriceProblemDetail; text/html: NginxErrorHtml | BFF 對唯一 business GET 的五秒 timeout 會回 ProblemDetail；Nginx upstream timeout 則是並列 text/html gateway alternative。 |

## Schema 欄位

### `ProblemDetail`

Spring `ProblemDetail` 的 RFC 7807/9457 JSON；可帶 RFC extension members。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `type` | 是 | `string` | 否 |  | 問題類型 URI reference。 |
| `title` | 是 | `string` | 否 |  | 穩定、可讀的錯誤標題。 |
| `status` | 是 | `integer (int32)` | 否 | minimum: 400<br>maximum: 599 | 此次問題回應的 HTTP 狀態碼。 |
| `detail` | 是 | `string | null` | 是 |  | 此次錯誤的說明。 |
| `instance` | 是 | `string | null` | 是 |  | 發生問題的 request path/URI reference。 |

### `PublicCommodityPriceProblemDetail`

Commodity batch BFF 自產且不含 extension 的固定 RFC 7807 問題物件；502/504 的 Nginx text/html 另列於 operation response，不可混同。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `type` | 是 | `string` | 否 | enum: `about:blank` | 固定 RFC 問題類型 URI `about:blank`。 |
| `title` | 是 | `string` | 否 | enum: `Invalid commodity price request`, `Commodity prices downstream failure`, `Commodity prices service unavailable`, `Commodity prices timeout` | 固定錯誤標題；可用值為 `Invalid commodity price request`、`Commodity prices downstream failure`、`Commodity prices service unavailable`、`Commodity prices timeout`。 |
| `status` | 是 | `integer (int32)` | 否 | enum: `400`, `502`, `503`, `504` | 對應固定 title/detail 的 HTTP 狀態碼；可用值為 `400`、`502`、`503`、`504`。 |
| `detail` | 是 | `string` | 否 | enum: `不支援 query parameter 或 request body`, `商品報價暫時無法取得`, `商品報價服務暫時無法連線`, `商品報價服務逾時` | 固定、已消毒的繁體中文說明；可用值為 `不支援 query parameter 或 request body`、`商品報價暫時無法取得`、`商品報價服務暫時無法連線`、`商品報價服務逾時`。 |
| `instance` | 是 | `string` | 否 | enum: `/api/public/commodity-prices` | 固定發生問題的 public commodity batch request path `/api/public/commodity-prices`。 |

### `CommodityPriceBatchResponse`

Global no-tenant 的 immutable commodity batch response；只投影既有 Redis spot 與 PostgreSQL 前收聚合，不做 request-time vendor I/O、refresh 或任何寫入。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `marketOpen` | 是 | `boolean` | 否 |  | 既有 commodity session key 是否存在；不代表三個 spot 都有值或資料必然新鮮。 |
| `quotes` | 是 | `CommodityPriceSlots` | 否 |  | 固定 WTI、BRENT、GOLD 順序的三個 persisted quote slots；每個 slot 必存在但可為 null。 |

### `CommodityPriceSlots`

固定三個 commodity slot 的封閉物件；JSON property 順序永遠為 WTI、BRENT、GOLD，null 只表示該一個 persisted spot 無法安全讀取。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `WTI` | 是 | `CommodityPriceQuote | null` | 是 |  | WTI 原油已持久化報價；null 表示 WTI spot cache miss 或其 stored payload 未通過既有嚴格 gate。 |
| `BRENT` | 是 | `CommodityPriceQuote | null` | 是 |  | Brent 原油已持久化報價；null 表示 Brent spot cache miss 或其 stored payload 未通過既有嚴格 gate。 |
| `GOLD` | 是 | `CommodityPriceQuote | null` | 是 |  | 黃金已持久化報價；null 表示 GOLD spot cache miss 或其 stored payload 未通過既有嚴格 gate。 |

### `CommodityPriceQuote`

單一 code 的 immutable persisted commodity live quote；BFF 對 business payload 做 strict enum、正數、時間與 nullable-pair validation 後才輸出。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `commodityCode` | 是 | `string` | 否 | enum: `WTI`, `BRENT`, `GOLD` | 此 quote 所屬的 slot code；可用值為 `WTI`、`BRENT`、`GOLD`，且必與外層 property 相同。 |
| `unit` | 是 | `string` | 否 | enum: `USD_PER_BARREL`, `USD_PER_TROY_OUNCE` | 固定計價單位；WTI/BRENT 為 `USD_PER_BARREL`，GOLD 為 `USD_PER_TROY_OUNCE`。 |
| `price` | 是 | `number` | 否 | exclusiveMinimum: 0 | 必為正數的最新已持久化 spot price；不會以歷史收盤或零值補造。 |
| `change` | 是 | `number | null` | 是 |  | 相對既有前收的絕對變動；與 changePercent 必同時為 finite number 或同時為 null。 |
| `changePercent` | 是 | `number | null` | 是 |  | 相對既有前收的百分比變動；與 change 必同時為 finite number 或同時為 null。 |
| `sessionDate` | 是 | `string (date)` | 否 |  | 此已持久化 commodity session 所屬的 ISO 8601 日期。 |
| `quoteTime` | 是 | `string (date-time)` | 否 |  | provider 報價時間的 RFC 3339 instant，不是 BFF request time。 |
| `polledAt` | 是 | `string (date-time)` | 否 |  | 既有 producer 成功輪詢／寫入 spot 的 RFC 3339 instant。 |
| `status` | 是 | `string` | 否 | enum: `LIVE`, `STALE`, `SETTLED` | 已持久化 spot 狀態；可用值為 `LIVE`、`STALE`、`SETTLED`，其來源是既有 producer 而非 BFF 推測。 |
| `dayHigh` | 是 | `number | null` | 是 | exclusiveMinimum: 0 | producer 提供時必為正數的 session 日內高點；null 表示未提供，不得由 API 估算。 |
| `dayLow` | 是 | `number | null` | 是 | exclusiveMinimum: 0 | producer 提供時必為正數的 session 日內低點；null 表示未提供，不得由 API 估算。 |
| `provider` | 是 | `string` | 否 |  | 非空白的已持久化行情 provider provenance；只揭露來源識別，不觸發該 provider request-time 查詢。 |

### `SpringWebFluxBasicError`

Spring WebFlux 預設錯誤 JSON；requestId、message 與其他診斷欄位是否出現取決於部署設定，因此保持彈性。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `timestamp` | 是 | `string (date-time)` | 否 |  | HTTP 錯誤或事件產生的時間戳。 |
| `status` | 是 | `integer (int32)` | 否 | minimum: 400<br>maximum: 599 | Spring WebFlux 回傳的 HTTP 狀態碼。 |
| `error` | 是 | `string` | 否 |  | HTTP 錯誤的穩定文字名稱。 |
| `path` | 是 | `string` | 否 |  | 產出檔案或錯誤所對應的路徑。 |
| `requestId` | 否 | `string | null` | 是 |  | 伺服器用於追蹤此次 HTTP 請求的識別碼。 |
| `message` | 否 | `string | null` | 是 |  | 可安全顯示給呼叫端的可用性或失敗說明。 |

### `NginxErrorHtml`

Nginx 自行產生的標準 HTML 錯誤頁；不是 JSON，也不保證空白或換行逐字固定。

型別：`string`。

### `LatestQuoteShared`

供 `ListedLatestQuote` 與 `DetailedLatestQuote` 共用的既有 24 個 top-level quote fields。前 19 個 raw LatestQuote attribute 依原始 record 順序固定保留，後接 required 的 marketData 與同一 immutable canonical snapshot 投影的 direct quoteDetail、bidLevels、askLevels、dividendHistory。每個 required attribute 都固定出現；其型別含 null 時表示來源／child unavailable，並非省略。只含公開市場資料，絕不含個人持股、帳戶、 成本、交易、快照、配置或建議。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | raw LatestQuote 的標的代號；required nullable string，來源無法提供代號時為 null，不能由名稱推測。 |
| `stockName` | 是 | `string | null` | 是 |  | raw LatestQuote 的顯示名稱；required nullable string，缺來源名稱時為 null，不是使用者持股名稱。 |
| `market` | 是 | `string | null` | 是 | enum: `台股`, `美股`, `英股`, null | raw LatestQuote 的市場；required nullable enum，只能是 `台股`、`美股`、`英股` 或 null，null 表示 raw source 未分類。 |
| `price` | 是 | `number | null` | 是 |  | raw LatestQuote 的最新成交價；required nullable number，null 表示 raw price cache 沒有可驗證值，非零價或估算值。 |
| `previousClose` | 是 | `number | null` | 是 |  | raw LatestQuote 的前一交易日收盤價；required nullable number，null 表示來源未提供，不能以今日價格回推。 |
| `priceChange` | 是 | `number | null` | 是 |  | raw LatestQuote 相對前收的絕對價格變動；required nullable number，正負方向依來源，null 表示未計算。 |
| `changePercent` | 是 | `number | null` | 是 |  | raw LatestQuote 相對前收的價格變動百分點；required nullable number，例如 0.75 代表上漲 0.75%，null 表示未計算。 |
| `buyPrice` | 是 | `number | null` | 是 |  | raw LatestQuote 的單一最佳買入價；required nullable number，絕不補成五檔或作為 Yahoo fallback 輸入。 |
| `sellPrice` | 是 | `number | null` | 是 |  | raw LatestQuote 的單一最佳賣出價；required nullable number，絕不補成五檔或作為 Yahoo fallback 輸入。 |
| `openPrice` | 是 | `number | null` | 是 |  | raw LatestQuote 的當日開盤價；required nullable number，來源未提供時為 null。 |
| `highPrice` | 是 | `number | null` | 是 |  | raw LatestQuote 的當日最高價；required nullable number，來源未提供時為 null。 |
| `lowPrice` | 是 | `number | null` | 是 |  | raw LatestQuote 的當日最低價；required nullable number，來源未提供時為 null。 |
| `volume` | 是 | `integer | null (int64)` | 是 |  | raw LatestQuote 的成交量；required nullable int64，單位與來源相同，null 表示未提供。 |
| `tradingDate` | 是 | `string | null` | 是 |  | raw LatestQuote 的交易日字串；required nullable string，格式由 raw source 保留，null 表示來源沒有交易日。 |
| `updatedAt` | 是 | `string | null` | 是 | pattern: ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$ | raw LatestQuote 的來源更新時點；required nullable string，pattern 為無 UTC offset 的 ISO local date-time，來源無時點時為 null。 |
| `closed` | 是 | `boolean | null` | 是 |  | raw LatestQuote 指示市場是否已收盤；required nullable boolean，null 表示來源未表態而非 false。 |
| `source` | 是 | `string | null` | 是 |  | raw LatestQuote 的來源識別；required nullable string，非固定 enum，null 表示 raw cache 沒有來源值。 |
| `quoteStatus` | 是 | `string | null` | 是 |  | raw LatestQuote 的行情狀態；required nullable string，非固定 enum，null 表示 raw source 未提供狀態。 |
| `premiumDiscountPct` | 是 | `number | null` | 是 |  | raw LatestQuote best-effort ETF 溢折價百分點；required nullable number，正值為溢價、負值為折價，null 不可由 price 與 NAV 回推。 |
| `marketData` | 是 | `PublicQuoteMarketData` | 否 |  | required `PublicQuoteMarketData` nested class；固定四個公開市場 child，child 個別 unavailable 仍保留 typed fallback。 |
| `quoteDetail` | 是 | `PublicQuoteDetail` | 否 |  | required `PublicQuoteDetail` nested class；與 marketData.quoteDetail 是同一 immutable canonical snapshot，不得與 bidLevels／askLevels 混用不同 source 或 revision。 |
| `bidLevels` | 是 | `array of BookSideLevel` | 否 | maxItems: 5<br>items: BookSideLevel<br>items 說明: BookSideLevel item；買方一側的正價格與正 int64 張數，沿陣列維持買價由高到低。 | required non-null array，僅由 available `FUBON_BOOKS` 或 `YAHOO_TW` 完整 canonical snapshot 直接投影的買方最佳五檔；至多五筆，按價格嚴格遞減，任一不完整／未核准 snapshot 時為空陣列。 |
| `askLevels` | 是 | `array of BookSideLevel` | 否 | maxItems: 5<br>items: BookSideLevel<br>items 說明: BookSideLevel item；賣方一側的正價格與正 int64 張數，沿陣列維持賣價由低到高。 | required non-null array，僅由 available `FUBON_BOOKS` 或 `YAHOO_TW` 完整 canonical snapshot 直接投影的賣方最佳五檔；至多五筆，按價格嚴格遞增，任一不完整／未核准 snapshot 時為空陣列。 |
| `dividendHistory` | 是 | `PublicDividendHistory` | 否 |  | required `PublicDividendHistory` nested class；與 marketData.dividends 相同的 immutable readonly 公開股利資料，非個人股利紀錄。 |

### `ListedLatestQuote`

GET `/api/quotes` 專用既有 list response class；只投影 `LatestQuoteShared` 的固定 24 欄，零 Fubon full-response bridge calls。最外層 `unevaluatedProperties=false` 使 list wire response 不可加入額外欄位。

型別：`schema`。

### `DetailedLatestQuote`

GET `/api/quotes/one` 專用 response class。它保留完全相同的一份 `LatestQuoteShared` quote fields，並只在最後追加不同意義的 Fubon metadata、failure state 與 returned raw order-book。沒有 nested quote、fubonQuote、第二份價格／OHLC／買賣價／成交量／日期／來源／closed／quoteStatus，也絕不公開 adapter-wide counters。internal bridge 與本 API 都是 Redis candidate 加 PostgreSQL receipt-time validation 的 pure read，request-time 不會呼叫 Fubon、Yahoo 或 dispatcher。最外層 `unevaluatedProperties=false` 在 shared fields 與 additions 合併後才關閉額外欄位，避免 `allOf` 錯誤拒絕合法 Fubon 欄位。

型別：`schema`。

### `FubonReturnedOrderBook`

已驗證並保存的 normalized Fubon optional orderBook。它忠實保留五個 fixed slots 的 null pair，故不同於只接受完整正值五檔的 `PublicQuoteDetail`／direct quoteDetail；既不回填 generic price，也不改寫 canonical book revision。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `bookUpdatedAt` | 是 | `string (date-time)` | 否 |  | required UTC normalized book update instant；此 book 自己的來源時點，可能與 shared quote updatedAt 不同。 |
| `averagePrice` | 是 | `number | null` | 是 |  | required nullable normalized average price；adapter 未提供時為 null，不從成交量或價格反推。 |
| `turnoverYi` | 是 | `number | null` | 是 |  | required nullable normalized turnover in 億元；adapter 未提供時為 null，不由 volume 推算。 |
| `innerVolumeLots` | 是 | `integer | null (int64)` | 是 | minimum: 0 | required nullable normalized inner-volume lots；null 表示 adapter 未提供，0 是合法實際值。 |
| `outerVolumeLots` | 是 | `integer | null (int64)` | 是 | minimum: 0 | required nullable normalized outer-volume lots；null 表示 adapter 未提供，0 是合法實際值。 |
| `levels` | 是 | `array of FubonReturnedOrderBookLevel` | 否 | minItems: 5<br>maxItems: 5<br>items: FubonReturnedOrderBookLevel<br>items 說明: Fubon normalized returned order-book 的一個固定順位 slot。 | required exactly five fixed level slots in ascending level 1 through 5；bid/ask price and lots each are paired null or a normalized nonnegative lot with positive price. |

### `FubonReturnedOrderBookLevel`

FubonReturnedOrderBook 的單一 fixed slot；每一側的 price 與 volume 必同時為 null 或同時有值，null 不表示零價或可由其他來源補齊。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `level` | 是 | `integer` | 否 | minimum: 1<br>maximum: 5 | required fixed integer level；levels 陣列依序恰為 1、2、3、4、5。 |
| `bidPrice` | 是 | `number | null` | 是 | exclusiveMinimum: 0 | required nullable normalized bid price；與 bidVolumeLots 成對為 null 或正價格。 |
| `bidVolumeLots` | 是 | `integer | null (int64)` | 是 | minimum: 0 | required nullable normalized bid lots；與 bidPrice 成對為 null 或非負整數。 |
| `askPrice` | 是 | `number | null` | 是 | exclusiveMinimum: 0 | required nullable normalized ask price；與 askVolumeLots 成對為 null 或正價格。 |
| `askVolumeLots` | 是 | `integer | null (int64)` | 是 | minimum: 0 | required nullable normalized ask lots；與 askPrice 成對為 null 或非負整數。 |

### `PublicQuoteMarketData`

DetailedLatestQuote.marketData 的 required nested class，固定四個畫面頁籤公開市場投影。每個 required child 固定出現；child 的 nullable attribute／空陣列表達各自資料 unavailable，並不影響 raw quote。 此 class 不含 configured-admin、使用者、帳戶、持股、成本或交易資料。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `chart` | 是 | `ChartMarketData` | 否 |  | required `ChartMarketData` nested class；價格圖表與技術指標公開市場 child，自己的 status/message 表達可用性。 |
| `quoteDetail` | 是 | `PublicQuoteDetail` | 否 |  | required `PublicQuoteDetail` nested class；完整來源驗證後的行情細節與 paired 五檔，與 root quoteDetail 是同一 immutable snapshot。 |
| `etfConstituents` | 是 | `PublicEtfConstituents` | 否 |  | required `PublicEtfConstituents` nested class；ETF 發行人公開成分標的 child，不是使用者持股。 |
| `dividends` | 是 | `PublicDividendHistory` | 否 |  | required `PublicDividendHistory` nested class；公開股利歷史與年度加總 child，不是個人入帳紀錄。 |

### `ChartMarketData`

history/indicator 共用 aggregation；status 不描述 intraday。NO_DATA 與 UNAVAILABLE 都回 canonical empty series。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `status` | 是 | `string` | 否 | enum: `AVAILABLE`, `NO_DATA`, `UNAVAILABLE` | 來源取得、計算或市場狀態。 可用值固定為 `AVAILABLE`、`NO_DATA`、`UNAVAILABLE`。 |
| `message` | 是 | `string | null` | 是 |  | 一般化 client-safe 訊息，不含上游 URL、HTML 或例外。 |
| `requestedStart` | 是 | `string (date)` | 否 |  | 呼叫端要求的圖表起始日期。 |
| `requestedEnd` | 是 | `string (date)` | 否 |  | 呼叫端要求的圖表結束日期。 |
| `series` | 是 | `ChartSeries` | 否 |  | 日線與週線共用的技術指標時間序列。 |
| `intraday` | 是 | `IntradayMarketData` | 否 |  | 當日分時價格與技術狀態。 |

### `IntradayMarketData`

指定 raw tradingDate 的 pure-read 分時 outcome；非 AVAILABLE 的 ticks 固定為空陣列。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `status` | 是 | `string` | 否 | enum: `AVAILABLE`, `NO_DATA`, `UNAVAILABLE` | 來源取得、計算或市場狀態。 可用值固定為 `AVAILABLE`、`NO_DATA`、`UNAVAILABLE`。 |
| `message` | 是 | `string | null` | 是 |  | 可安全顯示給呼叫端的可用性或失敗說明。 |
| `tradingDate` | 是 | `string | null (date)` | 是 |  | 市場或報價所屬的交易日。 |
| `ticks` | 是 | `array of IntradayTick` | 否 | items: IntradayTick<br>items 說明: 陣列中的單一元素：依市場當地時間排列的分時成交點。 | 依市場當地時間排列的分時成交點。 |

### `IntradayTick`

市場當地時間的一筆分時成交價格。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `time` | 是 | `string` | 否 |  | ISO local date-time；市場當地時間。 |
| `price` | 是 | `number` | 否 |  | 該市場當地時間的成交價格。 |

### `ChartSeries`

StockAnalysisDialog 共用的對齊日線／日K／週K、MA、KD/J、MACD、RSI、乖離率、威廉值與 latest。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `dates` | 是 | `DateStringSeries` | 否 |  | 依時間升冪排列並與同列數值對齊的日期序列。 |
| `prices` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的成交價格序列。 |
| `ma5` | 是 | `NullableDecimalSeries` | 否 |  | 近 5 期的簡單移動平均。 |
| `ma20` | 是 | `NullableDecimalSeries` | 否 |  | 近 20 期的簡單移動平均。 |
| `ma60` | 是 | `NullableDecimalSeries` | 否 |  | 近 60 期的簡單移動平均。 |
| `ma240` | 是 | `NullableDecimalSeries` | 否 |  | 近 240 期的簡單移動平均。 |
| `k` | 是 | `NullableDecimalSeries` | 否 |  | KD 指標的 K 值。 |
| `d` | 是 | `NullableDecimalSeries` | 否 |  | KD 指標的 D 值。 |
| `j9` | 是 | `NullableDecimalSeries` | 否 |  | 九期 KD J 值。 |
| `k3d2` | 是 | `NullableDecimalSeries` | 否 |  | 三期 K、二期 D 組合指標。 |
| `rsv` | 是 | `NullableDecimalSeries` | 否 |  | KD 計算使用的未成熟隨機值。 |
| `ema12` | 是 | `NullableDecimalSeries` | 否 |  | 近 12 期的指數移動平均。 |
| `ema26` | 是 | `NullableDecimalSeries` | 否 |  | 近 26 期的指數移動平均。 |
| `dif` | 是 | `NullableDecimalSeries` | 否 |  | MACD 的快慢均線差值。 |
| `macd` | 是 | `NullableDecimalSeries` | 否 |  | MACD 訊號線值。 |
| `osc` | 是 | `NullableDecimalSeries` | 否 |  | MACD 柱狀震盪值。 |
| `rsi5` | 是 | `NullableDecimalSeries` | 否 |  | 近 5 期的相對強弱指標。 |
| `rsi10` | 是 | `NullableDecimalSeries` | 否 |  | 近 10 期的相對強弱指標。 |
| `bias10` | 是 | `NullableDecimalSeries` | 否 |  | 價格相對 10 期移動平均的乖離率。 |
| `bias20` | 是 | `NullableDecimalSeries` | 否 |  | 價格相對 20 期移動平均的乖離率。 |
| `b10b20` | 是 | `NullableDecimalSeries` | 否 |  | 十期與二十期乖離率的交叉指標。 |
| `wr9` | 是 | `NullableDecimalSeries` | 否 |  | 九期威廉指標值。 |
| `latest` | 是 | `NullableChartLatest` | 否 |  | 目前時間點的技術指標快照；可能為 null。 |
| `daily` | 是 | `ChartFrame` | 否 |  | 日線時間框架的價格與技術指標。 |
| `weekly` | 是 | `ChartFrame` | 否 |  | 週線時間框架的價格與技術指標。 |

### `DateStringSeries`

依時間升冪排列、可與同一圖表數值序列逐項對齊的 ISO 日期陣列。

陣列項目：`string (date)`。陣列中的單一元素：的業務屬性。

### `NullableChartLatest`

最新技術指標快照；沒有可用時間點時為 null。

型別：`ChartLatest | null`。

### `ChartLatest`

目前時間點與前一個時間點的技術指標快照。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `ma5` | 是 | `number | null` | 是 |  | 近 5 期的簡單移動平均。 |
| `ma20` | 是 | `number | null` | 是 |  | 近 20 期的簡單移動平均。 |
| `ma60` | 是 | `number | null` | 是 |  | 近 60 期的簡單移動平均。 |
| `ma240` | 是 | `number | null` | 是 |  | 近 240 期的簡單移動平均。 |
| `k` | 是 | `number | null` | 是 |  | KD 指標的 K 值。 |
| `d` | 是 | `number | null` | 是 |  | KD 指標的 D 值。 |
| `j9` | 是 | `number | null` | 是 |  | 九期 KD J 值。 |
| `k3d2` | 是 | `number | null` | 是 |  | 三期 K、二期 D 組合指標。 |
| `rsv` | 是 | `number | null` | 是 |  | KD 計算使用的未成熟隨機值。 |
| `prevK` | 是 | `number | null` | 是 |  | 前一個對齊時間點的KD 指標的 K 值。 |
| `prevD` | 是 | `number | null` | 是 |  | 前一個對齊時間點的KD 指標的 D 值。 |
| `prevJ9` | 是 | `number | null` | 是 |  | 前一個對齊時間點的九期 KD J 值。 |
| `prevK3d2` | 是 | `number | null` | 是 |  | 前一個對齊時間點的三期 K、二期 D 組合指標。 |
| `prevRsv` | 是 | `number | null` | 是 |  | 前一個對齊時間點的KD 計算使用的未成熟隨機值。 |
| `ema12` | 是 | `number | null` | 是 |  | 近 12 期的指數移動平均。 |
| `ema26` | 是 | `number | null` | 是 |  | 近 26 期的指數移動平均。 |
| `dif` | 是 | `number | null` | 是 |  | MACD 的快慢均線差值。 |
| `macd` | 是 | `number | null` | 是 |  | MACD 訊號線值。 |
| `osc` | 是 | `number | null` | 是 |  | MACD 柱狀震盪值。 |
| `rsi5` | 是 | `number | null` | 是 |  | 近 5 期的相對強弱指標。 |
| `rsi10` | 是 | `number | null` | 是 |  | 近 10 期的相對強弱指標。 |
| `bias10` | 是 | `number | null` | 是 |  | 價格相對 10 期移動平均的乖離率。 |
| `bias20` | 是 | `number | null` | 是 |  | 價格相對 20 期移動平均的乖離率。 |
| `b10b20` | 是 | `number | null` | 是 |  | 十期與二十期乖離率的交叉指標。 |
| `wr9` | 是 | `number | null` | 是 |  | 九期威廉指標值。 |
| `prevEma12` | 是 | `number | null` | 是 |  | 前一個對齊時間點的近 12 期的指數移動平均。 |
| `prevEma26` | 是 | `number | null` | 是 |  | 前一個對齊時間點的近 26 期的指數移動平均。 |
| `prevDif` | 是 | `number | null` | 是 |  | 前一個對齊時間點的MACD 的快慢均線差值。 |
| `prevMacd` | 是 | `number | null` | 是 |  | 前一個對齊時間點的MACD 訊號線值。 |
| `prevRsi5` | 是 | `number | null` | 是 |  | 前一個對齊時間點的近 5 期的相對強弱指標。 |
| `prevRsi10` | 是 | `number | null` | 是 |  | 前一個對齊時間點的近 10 期的相對強弱指標。 |
| `prevBias10` | 是 | `number | null` | 是 |  | 前一個對齊時間點的價格相對 10 期移動平均的乖離率。 |
| `prevBias20` | 是 | `number | null` | 是 |  | 前一個對齊時間點的價格相對 20 期移動平均的乖離率。 |
| `prevB10b20` | 是 | `number | null` | 是 |  | 前一個對齊時間點的十期與二十期乖離率的交叉指標。 |
| `prevWr9` | 是 | `number | null` | 是 |  | 前一個對齊時間點的九期威廉指標值。 |

### `ChartFrame`

單一日線或週線時間框架的價格、技術指標與最新摘要。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `dates` | 是 | `DateStringSeries` | 否 |  | 依時間升冪排列並與同列數值對齊的日期序列。 |
| `opens` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的開盤價序列。 |
| `highs` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的最高價序列。 |
| `lows` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的最低價序列。 |
| `closes` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的收盤價序列。 |
| `ma5` | 是 | `NullableDecimalSeries` | 否 |  | 近 5 期的簡單移動平均。 |
| `ma20` | 是 | `NullableDecimalSeries` | 否 |  | 近 20 期的簡單移動平均。 |
| `ma60` | 是 | `NullableDecimalSeries` | 否 |  | 近 60 期的簡單移動平均。 |
| `ma240` | 是 | `NullableDecimalSeries` | 否 |  | 近 240 期的簡單移動平均。 |
| `k` | 是 | `NullableDecimalSeries` | 否 |  | KD 指標的 K 值。 |
| `d` | 是 | `NullableDecimalSeries` | 否 |  | KD 指標的 D 值。 |
| `j9` | 是 | `NullableDecimalSeries` | 否 |  | 九期 KD J 值。 |
| `k3d2` | 是 | `NullableDecimalSeries` | 否 |  | 三期 K、二期 D 組合指標。 |
| `rsv` | 是 | `NullableDecimalSeries` | 否 |  | KD 計算使用的未成熟隨機值。 |
| `ema12` | 是 | `NullableDecimalSeries` | 否 |  | 近 12 期的指數移動平均。 |
| `ema26` | 是 | `NullableDecimalSeries` | 否 |  | 近 26 期的指數移動平均。 |
| `dif` | 是 | `NullableDecimalSeries` | 否 |  | MACD 的快慢均線差值。 |
| `macd` | 是 | `NullableDecimalSeries` | 否 |  | MACD 訊號線值。 |
| `osc` | 是 | `NullableDecimalSeries` | 否 |  | MACD 柱狀震盪值。 |
| `rsi5` | 是 | `NullableDecimalSeries` | 否 |  | 近 5 期的相對強弱指標。 |
| `rsi10` | 是 | `NullableDecimalSeries` | 否 |  | 近 10 期的相對強弱指標。 |
| `bias10` | 是 | `NullableDecimalSeries` | 否 |  | 價格相對 10 期移動平均的乖離率。 |
| `bias20` | 是 | `NullableDecimalSeries` | 否 |  | 價格相對 20 期移動平均的乖離率。 |
| `b10b20` | 是 | `NullableDecimalSeries` | 否 |  | 十期與二十期乖離率的交叉指標。 |
| `wr9` | 是 | `NullableDecimalSeries` | 否 |  | 九期威廉指標值。 |
| `currentClose` | 是 | `number | null` | 是 |  | 目前時間框架最後一筆收盤價。 |
| `previousClose` | 是 | `number | null` | 是 |  | 前一交易日或前一時間框架的收盤價。 |
| `latest` | 是 | `NullableChartLatest` | 否 |  | 目前時間點的技術指標快照；可能為 null。 |

### `PublicQuoteDetail`

台股完整 paired 五檔與同來源摘要的 required nested class。富邦是每十秒 LIVE producer primary； 只有同輪 selected code 缺有效完整五檔時，獨立 Yahoo worker 才以 `YAHOO_TW` 非同步 fallback。available=true 時 source 僅能為 `FUBON_BOOKS` 或 `YAHOO_TW`，且五個 level 皆正價、正量、bid 遞減／ask 遞增； available=false 時 source 與行情 attribute 為 null、levels 為空。非台股或 0000 是 typed unsupported，零 上游 request。read path 只以 Redis 作 candidate 並以 PostgreSQL revision 驗證，絕不 request-time vendor I/O 或 cache repair。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | required nullable string；交易所或來源使用的標的代號，unsupported／unavailable identity 無法安全確認時為 null。 |
| `stockName` | 是 | `string | null` | 是 |  | required nullable string；供畫面與批次辨識的來源標的名稱，available snapshot 必為非空，否則為 null。 |
| `market` | 是 | `string | null` | 是 |  | required nullable string；標的所屬市場範圍，available 台股 snapshot 為 `台股`，unsupported identity 時可為 null。 |
| `supported` | 是 | `boolean` | 否 |  | required boolean；true 代表請求是可處理的台股非 0000 code，false 是 typed unsupported，不代表有五檔。 |
| `available` | 是 | `boolean` | 否 |  | required boolean；true 僅代表完整且已核准 source snapshot 可用，false 時不可使用任何行情或五檔 attribute。 |
| `source` | 是 | `string | null` | 是 | enum: `FUBON_BOOKS`, `YAHOO_TW`, null | required nullable enum；available=true 時僅 `FUBON_BOOKS`（富邦十秒 primary）或 `YAHOO_TW`（同輪缺完整 Fubon book 的 producer fallback），null 表示 unsupported 或 unavailable，絕不接受其他來源。 |
| `message` | 是 | `string | null` | 是 |  | required nullable string；available=false／unsupported 時可安全顯示的原因，available=true 通常為 null；不含 vendor response 或個人資料。 |
| `sourceTime` | 是 | `string | null (date-time)` | 是 |  | required nullable RFC 3339 date-time；available snapshot 的公開來源行情時點，必為台灣當日且非未來；unavailable 時為 null。 |
| `fetchedAt` | 是 | `string | null (date-time)` | 是 |  | required nullable RFC 3339 date-time；服務取得並保存 canonical snapshot 的時點，非呼叫此 API 的時間；unavailable 時為 null。 |
| `marketStatus` | 是 | `string | null` | 是 | enum: `OPEN`, `CLOSED`, `UNKNOWN`, null | required nullable enum；`OPEN` 是可用完整 canonical snapshot 的 producer market status，`CLOSED` 保留為來源相容值，`UNKNOWN` 代表 unavailable，null 是 unsupported identity 未知。 |
| `price` | 是 | `number | null` | 是 |  | required nullable number；available snapshot 的正成交價，null 表示不可用；不是估值、建議價格或 generic quote 補值。 |
| `previousClose` | 是 | `number | null` | 是 |  | required nullable number；available snapshot 的正前一交易日收盤價，null 表示不可用，不能由 price 推算。 |
| `openPrice` | 是 | `number | null` | 是 |  | required nullable number；同來源當日開盤成交價，來源未提供或 unavailable 時為 null。 |
| `highPrice` | 是 | `number | null` | 是 |  | required nullable number；同來源當日最高成交價，來源未提供或 unavailable 時為 null。 |
| `lowPrice` | 是 | `number | null` | 是 |  | required nullable number；同來源當日最低成交價，來源未提供或 unavailable 時為 null。 |
| `averagePrice` | 是 | `number | null` | 是 |  | required nullable number；同來源當日成交均價，來源未提供或 unavailable 時為 null。 |
| `change` | 是 | `number | null` | 是 |  | required nullable number；同來源相對前收的絕對變動，正負方向依來源，無法驗證時為 null。 |
| `changePercent` | 是 | `number | null` | 是 |  | required nullable number；同來源相對前收的變動百分點，例如 0.55 表示 0.55%，無法驗證時為 null。 |
| `turnoverYi` | 是 | `number | null` | 是 |  | required nullable number；同來源當日成交金額，以億元為單位，無來源值時為 null。 |
| `volumeLots` | 是 | `integer | null (int64)` | 是 |  | required nullable int64；同來源以張為單位的成交量，無來源值時為 null。 |
| `previousVolumeLots` | 是 | `integer | null (int64)` | 是 |  | required nullable int64；同來源前一交易日以張為單位的成交量，無來源值時為 null。 |
| `amplitudePercent` | 是 | `number | null` | 是 |  | required nullable number；由同來源高低價與前收得出的當日振幅百分點，必要輸入缺失時為 null。 |
| `innerVolumeLots` | 是 | `integer | null (int64)` | 是 |  | required nullable int64；同來源內盤成交量（張），無來源值時為 null，不是委託簿買量。 |
| `outerVolumeLots` | 是 | `integer | null (int64)` | 是 |  | required nullable int64；同來源外盤成交量（張），無來源值時為 null，不是委託簿賣量。 |
| `innerPercent` | 是 | `number | null` | 是 |  | required nullable number；內盤占內外盤成交量合計的百分比，分母無效時為 null。 |
| `outerPercent` | 是 | `number | null` | 是 |  | required nullable number；外盤占內外盤成交量合計的百分比，分母無效時為 null。 |
| `bidTotalLots` | 是 | `integer | null (int64)` | 是 |  | required nullable int64；available snapshot 五個 bid level 的正張數合計，unavailable 時為 null。 |
| `askTotalLots` | 是 | `integer | null (int64)` | 是 |  | required nullable int64；available snapshot 五個 ask level 的正張數合計，unavailable 時為 null。 |
| `levels` | 是 | `array of OrderBookLevel` | 否 | maxItems: 5<br>items: OrderBookLevel<br>items 說明: OrderBookLevel item；同一 source／revision 的 paired bid/ask level，level 由 1 到 5 遞進，不能與 root direct side array 交叉拼接。 | required non-null array；available=true 時恰好五筆 `OrderBookLevel`，level 1..5、bid price 嚴格遞減、ask price 嚴格遞增且全部正；available=false／unsupported 時為空，最多五筆。 |

### `OrderBookLevel`

PublicQuoteDetail.levels 的 paired five-book item class。只存在於 available=true 的完整同來源 snapshot； required attribute 均為非 null 正值，level 依 1..5 排序，bid 與 ask 不可由不同 source、不同 revision 或 generic quote 拼合。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `level` | 是 | `integer` | 否 | minimum: 1<br>maximum: 5 | required integer 1..5；由最佳價向外遞進的 paired 五檔順位，levels array 依此嚴格遞增。 |
| `bidPrice` | 是 | `number` | 否 | exclusiveMinimum: 0 | required non-null positive number；該順位買方掛單價格，level 增加時價格嚴格遞減。 |
| `bidVolumeLots` | 是 | `integer (int64)` | 否 | minimum: 1 | required non-null positive int64；該順位買方掛單張數，以張為單位。 |
| `askPrice` | 是 | `number` | 否 | exclusiveMinimum: 0 | required non-null positive number；該順位賣方掛單價格，level 增加時價格嚴格遞增。 |
| `askVolumeLots` | 是 | `integer (int64)` | 否 | minimum: 1 | required non-null positive int64；該順位賣方掛單張數，以張為單位。 |

### `BookSideLevel`

root `bidLevels` 或 `askLevels` 的單側 item class；只由同一 available `FUBON_BOOKS`／`YAHOO_TW` complete canonical snapshot 投影。兩個 required attribute 都是非 null 正值；bid array 由高到低、ask array 由低到高。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `price` | 是 | `number` | 否 | exclusiveMinimum: 0 | required non-null positive number；該側一個五檔順位的掛單價格。 |
| `size` | 是 | `integer (int64)` | 否 | minimum: 1 | required non-null positive int64；該側一個五檔順位的掛單張數，以張為單位。 |

### `PublicEtfConstituents`

ETF 發行人公開成分股。外層刻意不用 holdings；內層既有 holdings[] 僅代表發行人公開成分，不是使用者持股。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | 交易所或來源使用的標的代號。 |
| `market` | 是 | `string | null` | 是 |  | 標的所屬市場或交易所範圍。 |
| `supported` | 是 | `boolean` | 否 |  | 此標的或來源是否屬於服務支援範圍。 |
| `source` | 是 | `string | null` | 是 |  | ETF 成分標的公開來源識別。 |
| `asOfDate` | 是 | `string | null` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |
| `message` | 是 | `string | null` | 是 |  | 可安全顯示給呼叫端的可用性或失敗說明。 |
| `holdings` | 是 | `array of EtfConstituent` | 否 | items: EtfConstituent<br>items 說明: 陣列中的單一元素：ETF 發行人公開揭露的成分標的清單。 | ETF 發行人公開揭露的成分標的清單。 |

### `EtfConstituent`

ETF 發行人公開揭露的一筆成分標的與權重；不是個人持倉。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | 交易所或來源使用的標的代號。 |
| `stockName` | 是 | `string | null` | 是 |  | 供畫面與批次辨識的標的名稱。 |
| `weight` | 是 | `number | null` | 是 |  | 發行人公開揭露的 ETF 成分權重。 |
| `shares` | 是 | `number | null` | 是 |  | 發行人公開成分股股數；不是個人持有股數。 |

### `PublicDividendHistory`

DB-only readonly 十年股利完整 envelope；不觸發 sync、projection 或任何寫入，並含由同一 rows 計算的年度摘要。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | 股利歷史的標的代號。 |
| `market` | 是 | `string | null` | 是 |  | 股利歷史的市場字串。 |
| `source` | 是 | `string | null` | 是 |  | DB-only 股利資料的來源字串。 |
| `message` | 是 | `string | null` | 是 |  | 資料不可用時的一般化訊息。 |
| `rows` | 是 | `array of PublicDividendRow` | 否 | items: PublicDividendRow<br>items 說明: 一筆原始正規化股利事件。 | 股利明細 rows，保持資料來源排序。 |
| `annualSummaries` | 是 | `array of AnnualDividendSummary` | 否 | items: AnnualDividendSummary<br>items 說明: 一個年度的 typed 股利摘要。 | 按年度新到舊的股利加總與 anchor previousClose 現金殖利率摘要。 |

### `PublicDividendRow`

DB-only 正規化的單一股利事件；cashYieldPct 由此 row 的現金股利與 previousClose 決定。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `year` | 是 | `integer | null (int32)` | 是 |  | 原始股利事件年度。 |
| `cashDividend` | 是 | `number | null` | 是 |  | 原始現金股利；年度計算時 null 以零處理。 |
| `stockDividend` | 是 | `number | null` | 是 |  | 原始股票股利；年度計算時 null 以零處理。 |
| `exDividendDate` | 是 | `string | null (date)` | 是 |  | 除息日，也是年度 anchor date 的優先日期。 |
| `yieldPct` | 是 | `number | null` | 是 |  | 資料來源原有殖利率欄位。 |
| `cashPaymentDate` | 是 | `string | null (date)` | 是 |  | 現金股利發放日。 |
| `stockPaymentDate` | 是 | `string | null (date)` | 是 |  | 股票股利發放日。 |
| `fillDays` | 是 | `integer | null (int32)` | 是 |  | 資料來源原有填息天數。 |
| `previousClose` | 是 | `number | null` | 是 |  | 除權息前收盤價；cashYieldPct 的分母。 |
| `exRightsDate` | 是 | `string | null (date)` | 是 |  | 除權日，無除息日時做年度 anchor date。 |
| `cashYieldPct` | 是 | `number | null` | 是 |  | 現金股利除以本 row previousClose 再乘 100；previousClose 非正或缺值時為 null，scale 6 HALF_UP。 |

### `AnnualDividendSummary`

同一年度 rows 的現金與股票股利加總；現金殖利率只採 anchor date 新到舊第一筆有 previousClose 的事件。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `year` | 是 | `integer (int32)` | 否 |  | 股利事件所屬年度，annualSummaries 按此值遞減排列。 |
| `cashDividend` | 是 | `number` | 否 |  | 同年度 cashDividend 加總，來源 null 以零加入。 |
| `stockDividend` | 是 | `number` | 否 |  | 同年度 stockDividend 加總，來源 null 以零加入。 |
| `cashYieldPct` | 是 | `number | null` | 是 |  | 年度現金股利除以唯一 anchor previousClose 再乘 100；候選為非正時為 null 且不得改用較舊正值。 |

### `MarketIndexResponse`

`MarketIndexChartDto.Response` 的 21 個 record properties；八組序列等長，序列點允許 null。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `market` | 是 | `string` | 否 | enum: `TWSE`, `TPEX`, `DJI`, `SPX`, `IXIC`, `SOX`, `FTSE`, `DAX`, `KOSPI`, `N225` | 本次大盤圖表使用的市場代碼。 可用值固定為 `TWSE`、`TPEX`、`DJI`、`SPX`、`IXIC`、`SOX`、`FTSE`、`DAX`、`KOSPI`、`N225`。 |
| `marketLabel` | 是 | `string` | 否 | enum: `台股集中市場`, `台股櫃買市場`, `道瓊工業`, `標普 500`, `那斯達克綜合`, `費城半導體`, `英國富時 100`, `德國 DAX`, `韓國 KOSPI`, `日經 225` | 市場代碼對應的顯示名稱。 可用值固定為 `台股集中市場`、`台股櫃買市場`、`道瓊工業`、`標普 500`、`那斯達克綜合`、`費城半導體`、`英國富時 100`、`德國 DAX`、`韓國 KOSPI`、`日經 225`。 |
| `range` | 是 | `string` | 否 | enum: `d`, `1m`, `3m`, `6m`, `1y`, `2y`, `5y`, `10y` | 本次大盤圖表使用的時間窗代碼。 可用值固定為 `d`、`1m`、`3m`、`6m`、`1y`、`2y`、`5y`、`10y`。 |
| `rangeLabel` | 是 | `string` | 否 | enum: `當日`, `1 個月`, `3 個月`, `半年`, `1 年`, `2 年`, `5 年`, `10 年` | 時間窗代碼對應的顯示名稱。 可用值固定為 `當日`、`1 個月`、`3 個月`、`半年`、`1 年`、`2 年`、`5 年`、`10 年`。 |
| `mode` | 是 | `string` | 否 | enum: `DAILY`, `INTRADAY` | 回應為日線或當日分時圖表的模式。 可用值固定為 `DAILY`、`INTRADAY`。 |
| `tradingDate` | 是 | `string | null (date)` | 是 |  | INTRADAY 的交易日；DAILY 固定為 null。 |
| `labels` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：圖表時間軸的顯示標籤。 | DAILY 為 yyyy-MM-dd；INTRADAY 為 HH:mm。 |
| `closes` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的收盤價序列。 |
| `ma5` | 是 | `NullableDecimalSeries` | 否 |  | 近 5 期的簡單移動平均。 |
| `ma20` | 是 | `NullableDecimalSeries` | 否 |  | 近 20 期的簡單移動平均。 |
| `ma60` | 是 | `NullableDecimalSeries` | 否 |  | 近 60 期的簡單移動平均。 |
| `ma240` | 是 | `NullableDecimalSeries` | 否 |  | 近 240 期的簡單移動平均。 |
| `volumes` | 是 | `array of integer | null (int64)` | 否 | items: integer \| null (int64)<br>items 說明: 陣列中的單一元素：與日期序列對齊的成交量序列。 | 與日期序列對齊的成交量序列。 |
| `turnovers` | 是 | `NullableDecimalSeries` | 否 |  | 與日期序列對齊的成交金額序列。 |
| `hasVolume` | 是 | `boolean` | 否 |  | 目前大盤圖表是否提供成交量序列。 |
| `previousClose` | 是 | `number | null` | 是 |  | 僅 INTRADAY 可能有值。 |
| `lastClose` | 是 | `number | null` | 是 |  | 僅 INTRADAY 可能有值。 |
| `change` | 是 | `number | null` | 是 |  | 僅 INTRADAY 且可比較昨收時有值。 |
| `changePercent` | 是 | `number | null` | 是 |  | 百分比；僅 INTRADAY 且可比較昨收時有值。 |
| `supportedMarkets` | 是 | `array of MarketOption` | 否 | minItems: 10<br>maxItems: 10<br>items: MarketOption<br>items 說明: 陣列中的單一元素：可查詢的大盤市場選項，順序固定。 | 可查詢的大盤市場選項，順序固定。 |
| `supportedRanges` | 是 | `array of RangeOption` | 否 | minItems: 8<br>maxItems: 8<br>items: RangeOption<br>items 說明: 陣列中的單一元素：可查詢的圖表時間窗選項，順序固定。 | 可查詢的圖表時間窗選項，順序固定。 |

### `NullableDecimalSeries`

與 labels 等長；無法計算或不適用的點為 null。

陣列項目：`number | null`。陣列中的單一元素：的量化值。

### `MarketOption`

可查詢大盤市場代碼及其顯示名稱。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `value` | 是 | `string` | 否 | enum: `TWSE`, `TPEX`, `DJI`, `SPX`, `IXIC`, `SOX`, `FTSE`, `DAX`, `KOSPI`, `N225` | 可查詢大盤市場的固定代碼。 可用值固定為 `TWSE`、`TPEX`、`DJI`、`SPX`、`IXIC`、`SOX`、`FTSE`、`DAX`、`KOSPI`、`N225`。 |
| `label` | 是 | `string` | 否 | enum: `台股集中市場`, `台股櫃買市場`, `道瓊工業`, `標普 500`, `那斯達克綜合`, `費城半導體`, `英國富時 100`, `德國 DAX`, `韓國 KOSPI`, `日經 225` | 大盤市場代碼對應的顯示名稱。 可用值固定為 `台股集中市場`、`台股櫃買市場`、`道瓊工業`、`標普 500`、`那斯達克綜合`、`費城半導體`、`英國富時 100`、`德國 DAX`、`韓國 KOSPI`、`日經 225`。 |

### `RangeOption`

可查詢圖表時間範圍代碼及其顯示名稱。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `value` | 是 | `string` | 否 | enum: `d`, `1m`, `3m`, `6m`, `1y`, `2y`, `5y`, `10y` | 可查詢圖表時間窗的固定代碼。 可用值固定為 `d`、`1m`、`3m`、`6m`、`1y`、`2y`、`5y`、`10y`。 |
| `label` | 是 | `string` | 否 | enum: `當日`, `1 個月`, `3 個月`, `半年`, `1 年`, `2 年`, `5 年`, `10 年` | 圖表時間窗代碼對應的顯示名稱。 可用值固定為 `當日`、`1 個月`、`3 個月`、`半年`、`1 年`、`2 年`、`5 年`、`10 年`。 |

### `LatestAssetsResponse`

`LatestAssetsDto.Response`。snapshot.id 與 liveAssets.snapshotId 必須相等；所有股票 valuationSource 都是 TARGET_SESSION_PRICE 時 targetPriceComplete 才為 true，空股票集合的 conjunction 亦為 true。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `generatedAt` | 是 | `string (date-time)` | 否 |  | Java Instant，UTC offset/Z 的 ISO-8601 時刻。 |
| `valuationPolicy` | 是 | `string` | 否 | enum: `TARGET_SESSION_WITH_EXPLICIT_FALLBACK` | 資產估值採用的規則版本或政策。 可用值固定為 `TARGET_SESSION_WITH_EXPLICIT_FALLBACK`。 |
| `targetPriceComplete` | 是 | `boolean` | 否 |  | 每一筆 liveAssets.stocks 的 valuationSource 都等於 TARGET_SESSION_PRICE 時為 true。 |
| `marketStatus` | 是 | `MarketStatus` | 否 |  | 三個市場目前的開收盤狀態。 |
| `snapshot` | 是 | `SnapshotDetail` | 否 |  | 快照的業務屬性。 |
| `liveAssets` | 是 | `LiveAssets` | 否 |  | 即時資產的業務屬性。 |

### `MarketStatus`

`StockPriceService.getMarketStatus()` 的固定九鍵物件。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `twMarketOpen` | 是 | `boolean` | 否 |  | 台股市場目前是否開盤。 |
| `usMarketOpen` | 是 | `boolean` | 否 |  | 美股市場目前是否開盤。 |
| `ukMarketOpen` | 是 | `boolean` | 否 |  | 英股市場目前是否開盤。 |
| `twTime` | 是 | `LocalDateTime` | 否 |  | 台股市場時區中的目前時間。 |
| `usTime` | 是 | `LocalDateTime` | 否 |  | 美股市場時區中的目前時間。 |
| `ukTime` | 是 | `LocalDateTime` | 否 |  | 英股市場時區中的目前時間。 |
| `twTradingDate` | 是 | `string (date)` | 否 |  | 台股市場時區中的交易日。 |
| `usTradingDate` | 是 | `string (date)` | 否 |  | 美股市場時區中的交易日。 |
| `ukTradingDate` | 是 | `string (date)` | 否 |  | 英股市場時區中的交易日。 |

### `LocalDateTime`

Java LocalDateTime 的 ISO 字串，沒有 UTC offset；刻意不使用 date-time format。

型別：`string`。

### `SnapshotDetail`

資產快照總額與存款、基金、股票部位明細。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `id` | 是 | `integer (int64)` | 否 |  | 該回應內實體的穩定識別碼。 |
| `snapshotDate` | 是 | `string (date)` | 否 |  | 資產快照所屬的日期。 |
| `usdExchangeRate` | 是 | `number | null` | 是 |  | 資產快照採用的 USD/TWD 匯率。 |
| `totalDeposit` | 是 | `number | null` | 是 |  | 快照中的存款金額合計。 |
| `totalFundValue` | 是 | `number | null` | 是 |  | 快照中基金目前估值合計。 |
| `totalFundCost` | 是 | `number | null` | 是 |  | 快照中基金投入成本合計。 |
| `totalStockValue` | 是 | `number | null` | 是 |  | 快照中股票目前估值合計。 |
| `totalStockCost` | 是 | `number | null` | 是 |  | 快照中股票投入成本合計。 |
| `totalAssets` | 是 | `number | null` | 是 |  | 快照中的資產總額。 |
| `estimatedAnnualDividend` | 是 | `number | null` | 是 |  | 快照估算的一年股利金額。 |
| `realizedGain` | 是 | `number | null` | 是 |  | 已實現損益金額。 |
| `notes` | 是 | `string | null` | 是 |  | 建立快照或帳本時保留的附註。 |
| `deposits` | 是 | `array of DepositSnapshot` | 否 | items: DepositSnapshot<br>items 說明: 陣列中的單一元素：資產快照中的存款部位清單。 | 資產快照中的存款部位清單。 |
| `funds` | 是 | `array of FundSnapshot` | 否 | items: FundSnapshot<br>items 說明: 陣列中的單一元素：資產快照中的基金部位清單。 | 資產快照中的基金部位清單。 |
| `stocks` | 是 | `array of StockSnapshot` | 否 | items: StockSnapshot<br>items 說明: 陣列中的單一元素：股票部位或收合交易雷達列的清單；語意由所屬 schema 限定。 | 股票部位或收合交易雷達列的清單；語意由所屬 schema 限定。 |

### `DepositSnapshot`

單筆存款部位的快照明細。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `id` | 是 | `integer (int64)` | 否 |  | 該回應內實體的穩定識別碼。 |
| `bankId` | 是 | `integer | null (int64)` | 是 |  | 金融機構的穩定識別碼。 |
| `bankDisplayName` | 是 | `string | null` | 是 |  | 供呈現的金融機構名稱。 |
| `depositType` | 是 | `string` | 否 |  | 存款商品的類型。 |
| `depositDisplayName` | 是 | `string` | 否 |  | 供呈現的存款商品名稱。 |
| `amount` | 是 | `number` | 否 |  | 台幣換算金額。 |
| `originalAmount` | 是 | `number | null` | 是 |  | 存款部位建立時的原始金額。 |
| `currency` | 是 | `string | null` | 是 |  | 部位原始金額的幣別。 |
| `annualInterestRate` | 是 | `number | null` | 是 |  | 百分比，例如 1.5 表示 1.5%。 |
| `estimatedAnnualInterest` | 是 | `number | null` | 是 |  | 台幣；rate 為 null 或計算結果非正數時為 null。 |
| `notes` | 是 | `string | null` | 是 |  | 建立快照或帳本時保留的附註。 |

### `FundSnapshot`

單筆基金部位的快照明細與估算損益。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `id` | 是 | `integer (int64)` | 否 |  | 該回應內實體的穩定識別碼。 |
| `fundName` | 是 | `string` | 否 |  | 基金的顯示名稱。 |
| `fundCode` | 是 | `string | null` | 是 |  | 基金的公開代碼。 |
| `bankId` | 是 | `integer | null (int64)` | 是 |  | 金融機構的穩定識別碼。 |
| `bankDisplayName` | 是 | `string | null` | 是 |  | 供呈現的金融機構名稱。 |
| `investmentAmount` | 是 | `number` | 否 |  | 基金或部位的投入金額。 |
| `currentValue` | 是 | `number` | 否 |  | 基金部位目前估值。 |
| `units` | 是 | `number | null` | 是 |  | 基金部位持有的單位數。 |
| `estimatedDividend` | 是 | `number | null` | 是 |  | 部位估算可取得的股利金額。 |
| `dividendRate` | 是 | `number | null` | 是 |  | estimatedDividend/currentValue 的比例值。 |
| `profit` | 是 | `number` | 否 |  | 部位或快照目前的損益金額。 |
| `profitRate` | 是 | `number` | 否 |  | 部位或快照目前的損益率。 |

### `StockSnapshot`

單筆股票部位的快照成本、收益、股利與原始交易參考。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `id` | 是 | `integer (int64)` | 否 |  | 該回應內實體的穩定識別碼。 |
| `stockCode` | 是 | `string` | 否 |  | 交易所或來源使用的標的代號。 |
| `stockName` | 是 | `string` | 否 |  | 供畫面與批次辨識的標的名稱。 |
| `market` | 是 | `string` | 否 |  | DB 驅動的市場字串，不在 HTTP schema 寫死 enum。 |
| `brokerId` | 是 | `integer | null (int64)` | 是 |  | 券商或帳務來源的穩定識別碼。 |
| `brokerDisplayName` | 是 | `string | null` | 是 |  | 供呈現的券商或帳務來源名稱。 |
| `shares` | 是 | `number` | 否 |  | 快照中該股票部位的持有股數。 |
| `investmentCost` | 是 | `number` | 否 |  | 原始幣別成本。 |
| `investmentCostTwd` | 是 | `number` | 否 |  | 股票部位以新台幣計的投入成本。 |
| `currentValue` | 是 | `number` | 否 |  | 台幣現值。 |
| `profit` | 是 | `number` | 否 |  | 部位或快照目前的損益金額。 |
| `profitRate` | 是 | `number` | 否 |  | 部位或快照目前的損益率。 |
| `estimatedDividend` | 是 | `number | null` | 是 |  | 部位估算可取得的股利金額。 |
| `dividendRate` | 是 | `number | null` | 是 |  | 部位使用的股利率。 |
| `currency` | 是 | `string | null` | 是 |  | 部位原始金額的幣別。 |
| `originalCurrencyValue` | 是 | `number | null` | 是 |  | 以原始幣別計的部位金額。 |
| `transactionType` | 是 | `string | null` | 是 |  | 建立此部位時記錄的原始交易類型。 |
| `transactionDate` | 是 | `string | null (date)` | 是 |  | 建立此部位時記錄的原始交易日期。 |
| `transactionExchangeRate` | 是 | `number | null` | 是 |  | 建立此部位時記錄的原始換匯匯率。 |
| `displayOrder` | 是 | `integer | null (int32)` | 是 |  | 畫面呈現此部位的穩定排序值。 |

### `LiveAssets`

與快照同一識別來源的即時估值、資產合計與市場開盤旗標。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `snapshotId` | 是 | `integer (int64)` | 否 |  | 必須等於 outer snapshot.id。 |
| `snapshotDate` | 是 | `string (date)` | 否 |  | 資產快照所屬的日期。 |
| `exchangeRate` | 是 | `number` | 否 |  | 估值換算採用的匯率。 |
| `totalDeposit` | 是 | `number` | 否 |  | 快照中的存款金額合計。 |
| `totalFundValue` | 是 | `number` | 否 |  | 快照中基金目前估值合計。 |
| `liveStockValue` | 是 | `number` | 否 |  | 即時股票估值合計。 |
| `liveTotalAssets` | 是 | `number` | 否 |  | 即時估值的資產總額。 |
| `stocks` | 是 | `array of LiveStock` | 否 | items: LiveStock<br>items 說明: 陣列中的單一元素：股票部位或收合交易雷達列的清單；語意由所屬 schema 限定。 | 股票部位或收合交易雷達列的清單；語意由所屬 schema 限定。 |
| `twMarketOpen` | 是 | `boolean` | 否 |  | 台股市場目前是否開盤。 |
| `usMarketOpen` | 是 | `boolean` | 否 |  | 美股市場目前是否開盤。 |
| `ukMarketOpen` | 是 | `boolean` | 否 |  | 英股市場目前是否開盤。 |
| `priceUpdatedAt` | 是 | `string | null` | 是 | pattern: ^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d{1,9})?)?$ | 最新可解析 quote 更新 LocalDateTime；沒有時為 null，無 offset，故不用 date-time format。 |

### `LiveStock`

即時估值中一筆股票部位的報價與估值來源。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string` | 否 |  | 交易所或來源使用的標的代號。 |
| `stockName` | 是 | `string` | 否 |  | 供畫面與批次辨識的標的名稱。 |
| `market` | 是 | `string` | 否 |  | 標的所屬市場或交易所範圍。 |
| `shares` | 是 | `number` | 否 |  | configured-admin 範圍內該股票部位的持有股數。 |
| `currentPrice` | 是 | `number | null` | 是 |  | 即時估值採用的目前報價。 |
| `liveValue` | 是 | `number | null` | 是 |  | 台幣估值；無 quote 時可沿用 snapshot currentValue。 |
| `closed` | 是 | `boolean | null` | 是 |  | 所屬市場是否已收盤。 |
| `tradingDate` | 是 | `string | null` | 是 |  | Quote 原始交易日字串；缺失、malformed 或晚於 target 時照實保留並以 valuationSource 標示。 |
| `previousClose` | 是 | `number | null` | 是 |  | 前一交易日或前一時間框架的收盤價。 |
| `priceChange` | 是 | `number | null` | 是 |  | 相對前一收盤價的價格變動。 |
| `changePercent` | 是 | `number | null` | 是 |  | 相對比較基準的變動百分比。 |
| `quoteStatus` | 是 | `string | null` | 是 |  | Quote 原始自由字串。 |
| `holdingId` | 是 | `integer (int64)` | 否 |  | 內部部位的穩定識別碼；僅 configured-admin 範圍。 |
| `source` | 是 | `string | null` | 是 |  | Quote 原始自由字串。 |
| `targetTradingDate` | 是 | `string (date)` | 否 |  | 估值或建議採用的目標交易日。 |
| `valuationSource` | 是 | `string` | 否 | enum: `TARGET_SESSION_PRICE`, `PREVIOUS_SESSION_PRICE`, `UNVERIFIED_SESSION_PRICE`, `SNAPSHOT_VALUE` | 即時估值採用的公開報價來源。 可用值固定為 `TARGET_SESSION_PRICE`、`PREVIOUS_SESSION_PRICE`、`UNVERIFIED_SESSION_PRICE`、`SNAPSHOT_VALUE`。 |

### `UsdTwdResponse`

`UsdTwdPublicDto.Response`。history 日期嚴格升冪、不可重複且介於 requestedStartDate..requestedEndDate（含首尾）；count == history.length。狀態組合：INACTIVE 僅 LAST_AVAILABLE；UNAVAILABLE 僅 HISTORY+STALE；ACTIVE 不得 LAST_AVAILABLE，LIVE 僅銀行來源、INDICATIVE 僅 YAHOO。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `pair` | 是 | `string` | 否 | enum: `USD/TWD` | 匯率貨幣對。 可用值固定為 `USD/TWD`。 |
| `baseCurrency` | 是 | `string` | 否 | enum: `USD` | 匯率貨幣對的基礎幣別。 可用值固定為 `USD`。 |
| `quoteCurrency` | 是 | `string` | 否 | enum: `TWD` | 匯率或標的報價的計價幣別。 可用值固定為 `TWD`。 |
| `requestedStartDate` | 是 | `string (date)` | 否 |  | 匯率歷史查詢採用的起始日期。 |
| `requestedEndDate` | 是 | `string (date)` | 否 |  | 匯率歷史查詢採用的結束日期。 |
| `refreshIntervalSeconds` | 是 | `integer (int32)` | 否 | enum: `2` | 輪詢或更新設定的秒數間隔。 可用值固定為 `2`。 |
| `timezone` | 是 | `string` | 否 | enum: `Asia/Taipei` | 日期與時間字串採用的 IANA 時區。 可用值固定為 `Asia/Taipei`。 |
| `liveUpdateStatus` | 是 | `string` | 否 | enum: `ACTIVE`, `INACTIVE`, `UNAVAILABLE` | 即期匯率更新的可用性狀態。 可用值固定為 `ACTIVE`、`INACTIVE`、`UNAVAILABLE`。 |
| `spot` | 是 | `UsdTwdSpot` | 否 |  | 目前 USD/TWD 即期匯率與來源時間。 |
| `count` | 是 | `integer (int32)` | 否 | minimum: 1 | 必須等於 history 的項目數。 |
| `history` | 是 | `array of UsdTwdHistoryPoint` | 否 | minItems: 1<br>items: UsdTwdHistoryPoint<br>items 說明: 陣列中的單一元素：歷史點的業務屬性。 | 日期升冪、無重複且落在 requested range 內。 |

### `UsdTwdSpot`

Timestamp 組合固定：HISTORY 的兩個 timestamp 都為 null；BANK_OF_TAIWAN 的 polledAt 必填而 sourceUpdatedAt 為 null；MEGA_BANK/YAHOO 兩者都必填且 sourceUpdatedAt 不得晚於 polledAt 120 秒。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `date` | 是 | `string (date)` | 否 |  | 此筆歷史或市場觀測的日期。 |
| `buyRate` | 是 | `number` | 否 | exclusiveMinimum: 0 | 銀行或來源公告的買入匯率。 |
| `sellRate` | 是 | `number` | 否 | exclusiveMinimum: 0 | 必須大於等於 buyRate。 |
| `midRate` | 是 | `number` | 否 | exclusiveMinimum: 0 | (buyRate+sellRate)/2，HALF_UP scale 4。 |
| `source` | 是 | `string` | 否 | enum: `BANK_OF_TAIWAN`, `MEGA_BANK`, `YAHOO`, `HISTORY` | 產生此值的公開來源或系統識別。 可用值固定為 `BANK_OF_TAIWAN`、`MEGA_BANK`、`YAHOO`、`HISTORY`。 |
| `polledAt` | 是 | `string | null (date-time)` | 是 |  | 輪詢來源的時間點。 |
| `sourceUpdatedAt` | 是 | `string | null (date-time)` | 是 |  | 來源聲明的最後更新時間點。 |
| `quoteStatus` | 是 | `string` | 否 | enum: `LIVE`, `INDICATIVE`, `STALE`, `LAST_AVAILABLE` | 原始報價可用性或盤中狀態。 可用值固定為 `LIVE`、`INDICATIVE`、`STALE`、`LAST_AVAILABLE`。 |

### `UsdTwdHistoryPoint`

一個日期的 USD/TWD 銀行買入匯率歷史點。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `date` | 是 | `string (date)` | 否 |  | 此筆歷史或市場觀測的日期。 |
| `buyRate` | 是 | `number` | 否 | exclusiveMinimum: 0 | 銀行或來源公告的買入匯率。 |
| `sellRate` | 是 | `number` | 否 | exclusiveMinimum: 0 | 必須大於等於 buyRate。 |
| `midRate` | 是 | `number` | 否 | exclusiveMinimum: 0 | (buyRate+sellRate)/2，HALF_UP scale 4。 |

### `CrawlerRescanResponse`

`CrawlerExportPathDto.RunNowResponse` 的 13 個 record properties；所有結果（含冷卻中與失敗）都以 status 表達，HTTP 一律 200。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `status` | 是 | `string | null` | 是 |  | OK／FAILED／RUNNING／BUSY／DISABLED／ERROR／COOLDOWN；COOLDOWN 代表 30 秒全域冷卻窗口內、未真的觸發抓取。 |
| `mode` | 是 | `string | null` | 是 |  | 自證是哪一顆按鈕；公開重新搜尋為 FETCH_AND_EXPORT，未跑成時為 null。 |
| `jsonPath` | 是 | `string | null` | 是 |  | JSON 匯出檔在本機工作目錄的路徑。 |
| `jsonSizeBytes` | 是 | `integer | null (int64)` | 是 |  | JSON 匯出檔的位元組大小。 |
| `xlsxPath` | 是 | `string | null` | 是 |  | XLSX 匯出檔在本機工作目錄的路徑。 |
| `xlsxSizeBytes` | 是 | `integer | null (int64)` | 是 |  | XLSX 匯出檔的位元組大小。 |
| `upserted` | 是 | `integer | null (int32)` | 是 |  | 只有 mode=FETCH_AND_EXPORT 有值。 |
| `failed` | 是 | `integer | null (int32)` | 是 |  | 同 upserted。 |
| `exported` | 是 | `integer | null (int32)` | 是 |  | 本次 crawler 結果是否已完成匯出。 |
| `jsonGdrivePath` | 是 | `string | null` | 是 |  | JSON 匯出檔在 Google Drive 的儲存路徑。 |
| `xlsxGdrivePath` | 是 | `string | null` | 是 |  | XLSX 匯出檔在 Google Drive 的儲存路徑。 |
| `gdriveStatus` | 是 | `string | null` | 是 |  | 未啟用 Drive 同步時為 null。 |
| `message` | 是 | `string | null` | 是 |  | crawler 重整完成或被節流時的可讀說明。 |

### `MarketAnalysisResponse`

`MarketAnalysisDto` 的 12 個 record properties。status=NONE 的占位物件所有值皆為 null，兩個陣列為空。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `analysisDate` | 是 | `string | null (date)` | 是 |  | 市場分析結論所屬日期。 |
| `bias` | 是 | `string | null` | 是 |  | 自由字串；不可假設固定 enum。 |
| `confidence` | 是 | `integer | null (int32)` | 是 |  | 市場分析結論的信心程度。 |
| `summary` | 是 | `string | null` | 是 |  | 本回應或分析結論的精簡摘要。 |
| `keyFactors` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：關鍵因素的業務屬性。 | 由 DB 的 JSON 字串欄位解析而來；解析失敗或空字串時為空陣列，不會是 null。 |
| `newsHighlights` | 是 | `array of NewsHighlight` | 否 | items: NewsHighlight<br>items 說明: 陣列中的單一元素：新聞重點的業務屬性。 | 同 keyFactors，解析失敗時為空陣列。 |
| `twContext` | 是 | `string | null` | 是 |  | 台股市場的決策上下文。 |
| `usContext` | 是 | `string | null` | 是 |  | 美股市場的決策上下文。 |
| `model` | 是 | `string | null` | 是 |  | 產生分析或建議的模型／規則版本。 |
| `status` | 是 | `string` | 否 |  | NONE（尚無資料的占位物件）／PROCESSING／OK／FAILED 等；不可只看 HTTP 200。 |
| `errorMessage` | 是 | `string | null` | 是 |  | 本次處理失敗時可安全顯示的錯誤說明。 |
| `generatedAt` | 是 | `string | null (date-time)` | 是 |  | Java Instant，UTC offset/Z 的 ISO-8601 時刻。 |

### `NewsHighlight`

`MarketAnalysisResult.NewsHighlight`；四個欄位皆為自由字串，publishedAt 照來源保留、不保證 ISO 格式。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `title` | 是 | `string | null` | 是 |  | 供使用者閱讀的項目標題。 |
| `source` | 是 | `string | null` | 是 |  | 新聞或公開資訊的發布來源名稱。 |
| `url` | 是 | `string | null` | 是 |  | 可公開存取的引用網址。 |
| `publishedAt` | 是 | `string | null` | 是 |  | 公開資訊發布的時間點。 |

### `PortfolioAdviceResponse`

`PortfolioAdviceDto` 的 22 個 record properties。status=NONE 的占位物件只有 status 有值，五個陣列為空。金額欄位為 BigDecimal，byte-relay 保留原始 JSON 精度。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `id` | 是 | `integer | null (int64)` | 是 |  | 該回應內實體的穩定識別碼。 |
| `status` | 是 | `string` | 否 |  | NONE（尚無資料的占位物件）／PROCESSING／OK／FAILED 等；不可只看 HTTP 200。 |
| `model` | 是 | `string | null` | 是 |  | 產生分析或建議的模型／規則版本。 |
| `createdAt` | 是 | `string | null (date-time)` | 是 |  | 建議或快照建立的時間點。 |
| `completedAt` | 是 | `string | null (date-time)` | 是 |  | 建議計算完成的時間點。 |
| `errorMessage` | 是 | `string | null` | 是 |  | 本次處理失敗時可安全顯示的錯誤說明。 |
| `age` | 是 | `integer | null (int32)` | 是 |  | 建議或快照自建立起的經過時間。 |
| `investmentHorizonYears` | 是 | `integer | null (int32)` | 是 |  | 建議假設的投資期間，以年為單位。 |
| `monthlyInvestment` | 是 | `number | null` | 是 |  | 建議假設的每月投入金額。 |
| `goals` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：目標的業務屬性。 | DB 的逗號分隔字串切開並去除空白；無值時為空陣列。 |
| `riskTolerance` | 是 | `string | null` | 是 |  | 自由字串；不可假設固定 enum。 |
| `expectedAnnualReturn` | 是 | `string | null` | 是 |  | 字串而非數值（可能是 "6%" 之類的區間描述）。 |
| `basedOnSnapshotId` | 是 | `integer | null (int64)` | 是 |  | 產生建議時採用的資產快照識別碼。 |
| `basedOnSnapshotDate` | 是 | `string | null (date)` | 是 |  | 產生建議時所採用資產快照日期。 |
| `basedOnTotalAssets` | 是 | `number | null` | 是 |  | 產生建議時採用的資產總額。 |
| `summary` | 是 | `string | null` | 是 |  | result_json 無法解析時為 null。 |
| `riskAssessment` | 是 | `string | null` | 是 |  | 建議使用的風險承受度或風險評估。 |
| `targetAllocation` | 是 | `array of TargetAllocation` | 否 | items: TargetAllocation<br>items 說明: 陣列中的單一元素：建議的目標資產配置集合。 | 建議的目標資產配置集合。 |
| `rebalancePlan` | 是 | `array of RebalanceItem` | 否 | items: RebalanceItem<br>items 說明: 陣列中的單一元素：使目前配置靠近目標配置的調整項目。 | 使目前配置靠近目標配置的調整項目。 |
| `actions` | 是 | `array of AdviceAction` | 否 | items: AdviceAction<br>items 說明: 陣列中的單一元素：資產配置建議中的行動項目清單。 | 資產配置建議中的行動項目清單。 |
| `warnings` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：資產配置建議中的限制與風險提示。 | 資產配置建議中的限制與風險提示。 |
| `references` | 是 | `array of AdviceReference` | 否 | items: AdviceReference<br>items 說明: 陣列中的單一元素：建議依據的公開引用清單。 | 建議依據的公開引用清單。 |

### `TargetAllocation`

`PortfolioAdviceResult.TargetAllocation`。targetAmount ＝ 資產總額 × targetPct、deltaAmount ＝ targetAmount − currentValue，兩者由後端決定性回填。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `assetClass` | 是 | `string | null` | 是 |  | 資產類別；DB／LLM 驅動的自由字串。 |
| `targetPct` | 是 | `number | null` | 是 |  | 建議目標比例，百分比數值（40.00 表示 40%）。 |
| `currentValue` | 是 | `number | null` | 是 |  | 依目前估值計算的部位金額。 |
| `targetAmount` | 是 | `number | null` | 是 |  | 調整後建議持有的目標金額。 |
| `deltaAmount` | 是 | `number | null` | 是 |  | 正為增碼、負為減碼。 |
| `rationale` | 是 | `string | null` | 是 |  | 配置或調整建議的理由。 |

### `RebalanceItem`

`PortfolioAdviceResult.Rebalance`。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `assetClass` | 是 | `string | null` | 是 |  | 資產分類，例如股票、基金、存款或 ETF。 |
| `holding` | 是 | `string | null` | 是 |  | 標的（個股代號／基金名稱／存款；或「整體」）。 |
| `action` | 是 | `string | null` | 是 |  | BUY／SELL／HOLD；LLM 產出的自由字串，不宣告 enum。 |
| `estimatedAmount` | 是 | `number | null` | 是 |  | 估計操作金額（新台幣，正數）。 |
| `rationale` | 是 | `string | null` | 是 |  | 配置或調整建議的理由。 |

### `AdviceAction`

`PortfolioAdviceResult.Action`。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `title` | 是 | `string | null` | 是 |  | 建議動作的短標題。 |
| `detail` | 是 | `string | null` | 是 |  | 可讀的具體處理或建議說明。 |
| `priority` | 是 | `string | null` | 是 |  | HIGH／MEDIUM／LOW；LLM 產出的自由字串，不宣告 enum。 |

### `AdviceReference`

`PortfolioAdviceResult.Reference`。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `title` | 是 | `string | null` | 是 |  | 公開參考來源的標題。 |
| `url` | 是 | `string | null` | 是 |  | 公開參考來源的網址。 |

### `TradingRadarListResponse`

今日交易雷達第一屏 projection；共用 current-read 的市場全域資料，但 stocks 只保留收合列欄位。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `ruleVersion` | 是 | `string` | 否 |  | 本次 current result 的規則版本。 |
| `actionPolicyVersion` | 是 | `string` | 否 |  | 本次 current result 的動作閘門版本。 |
| `generatedAt` | 是 | `string (date-time)` | 否 |  | business current read 組裝完成時刻。 |
| `market` | 是 | `MarketSummary` | 否 |  | 台股市場全域摘要。 |
| `usMarket` | 是 | `MarketSummary` | 否 |  | 美股市場全域摘要。 |
| `stocks` | 是 | `array of TradingRadarListStock` | 否 | items: TradingRadarListStock<br>items 說明: 一筆收合交易雷達列。 | 首頁收合股票列；每筆由同一次 current result 投影，順序保持 business 結果。 |
| `skippedNonTwStocks` | 是 | `integer (int32)` | 否 | minimum: 0 | 未納入台股雷達計算的非台股標的數。 |
| `publicInformation` | 是 | `array of PublicInformationItem` | 否 | items: PublicInformationItem<br>items 說明: 一筆公開資訊。 | 與 current result 同源的公開資訊清單。 |

### `TradingRadarListStock`

交易雷達第一屏的收合列；刻意不含 reasons、evidence、完整 basic/fundamental tree 或 K 棒。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | 標的代號；與 detail selector 的 stockCode 配對。 |
| `stockName` | 是 | `string | null` | 是 |  | 標的名稱。 |
| `market` | 是 | `string | null` | 是 |  | 市場字串；與 detail selector 的 market 配對。 |
| `assetClass` | 是 | `string | null` | 是 |  | 資產類別。 |
| `distributionAdjusted` | 是 | `boolean` | 否 |  | 是否已套用配息調整。 |
| `held` | 是 | `boolean` | 否 |  | 該 configured-admin current scope 是否持有；不帶帳戶或成本明細。 |
| `fxPercentile` | 是 | `number | null` | 是 |  | 匯率百分位；不適用或無資料時為 null。 |
| `underlyingCurrency` | 是 | `string | null` | 是 |  | 標的底層幣別。 |
| `fundamental` | 是 | `TradingRadarListFundamental | null` | 是 |  | 只含首頁需要的基本面可用性與產業摘要；不是完整 evidence tree。 |
| `shortAction` | 是 | `string | null` | 是 |  | 短期動作字串。 |
| `shortActionLabel` | 是 | `string | null` | 是 |  | 短期動作顯示文字。 |
| `shortScore` | 是 | `integer | null (int32)` | 是 |  | 短期分數。 |
| `swingAction` | 是 | `string | null` | 是 |  | 波段動作字串。 |
| `swingActionLabel` | 是 | `string | null` | 是 |  | 波段動作顯示文字。 |
| `swingScore` | 是 | `integer | null (int32)` | 是 |  | 波段分數。 |
| `action` | 是 | `string | null` | 是 |  | 中期／主要動作字串。 |
| `actionLabel` | 是 | `string | null` | 是 |  | 中期／主要動作顯示文字。 |
| `score` | 是 | `integer | null (int32)` | 是 |  | 中期／主要分數。 |
| `horizonConflict` | 是 | `boolean` | 否 |  | 不同 horizon 的動作是否衝突。 |
| `timingState` | 是 | `string | null` | 是 |  | 時機狀態字串。 |
| `timingLabel` | 是 | `string | null` | 是 |  | 時機狀態顯示文字。 |
| `counterTrendState` | 是 | `string | null` | 是 |  | 逆勢狀態字串。 |
| `counterTrendLabel` | 是 | `string | null` | 是 |  | 逆勢狀態顯示文字。 |
| `price` | 是 | `number | null` | 是 |  | current result 中的市場價格。 |
| `changePercent` | 是 | `number | null` | 是 |  | 市場價格相對前收的百分點變動。 |
| `quoteStatus` | 是 | `string | null` | 是 |  | 行情來源狀態字串。 |
| `etfPremiumLivePct` | 是 | `number | null` | 是 |  | 即時 ETF 溢折價百分點；非 ETF 或不可用時為 null。 |
| `etfPremiumLiveNavAsOf` | 是 | `string | null` | 是 |  | 即時 ETF NAV 對應日期字串。 |
| `weeklyMa` | 是 | `number | null` | 是 |  | 週線均線。 |
| `monthlyMa` | 是 | `number | null` | 是 |  | 月線均線。 |
| `quarterlyMa` | 是 | `number | null` | 是 |  | 季線均線。 |
| `annualMa` | 是 | `number | null` | 是 |  | 年線均線。 |
| `kValue` | 是 | `number | null` | 是 |  | KD K 值。 |
| `dValue` | 是 | `number | null` | 是 |  | KD D 值。 |
| `kdHeat` | 是 | `string | null` | 是 |  | KD 熱度狀態字串。 |
| `weeklyIndicators` | 是 | `WeeklyIndicators | null` | 是 |  | 已計算的週線指標；不會為第一屏補抓資料。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 指標資料日期。 |

### `TradingRadarListFundamental`

第一屏最小基本面 projection；不含來源、證據、明細評分或個人資料。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `applicable` | 是 | `boolean` | 否 |  | 基本面是否適用於該標的。 |
| `coverage` | 是 | `integer (int32)` | 否 | minimum: 0 | 已覆蓋的基本面指標數。 |
| `industryName` | 是 | `string | null` | 是 |  | 產業名稱。 |
| `industryRevenueYoyPct` | 是 | `number | null` | 是 |  | 產業營收年增百分點。 |

### `TradingRadarStockDetailResponse`

由本次 today current result 精確選出的單一股票完整展開 projection。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `ruleVersion` | 是 | `string` | 否 |  | 與指定 stock 同一 current result 的規則版本。 |
| `actionPolicyVersion` | 是 | `string` | 否 |  | 與指定 stock 同一 current result 的動作閘門版本。 |
| `generatedAt` | 是 | `string (date-time)` | 否 |  | 與指定 stock 同一 current result 的產生時刻。 |
| `market` | 是 | `MarketSummary` | 否 |  | 台股市場全域摘要。 |
| `usMarket` | 是 | `MarketSummary` | 否 |  | 美股市場全域摘要。 |
| `stock` | 是 | `StockDecision` | 否 |  | 指定 code/market 的完整既有展開列。 |

### `PublicTransactionHistoryResponse`

configured-admin owner scope 的唯讀交易帳本；不含帳戶、券商憑證、個別成交或同步狀態。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `selection` | 是 | `TransactionHistorySelection` | 否 |  | 本次嚴格驗證後採用的篩選條件。 |
| `allTimeSummary` | 是 | `TransactionPeriodSummary` | 否 |  | 未套用 selection 的全期摘要。 |
| `summary` | 是 | `TransactionPeriodSummary` | 否 |  | 已套用 selection 的摘要。 |
| `yearSummaries` | 是 | `array of TransactionYearSummary` | 否 | items: TransactionYearSummary<br>items 說明: 一個年度的交易總計。 | 已套用 selection 的年度摘要，year 由新到舊排列。 |
| `records` | 是 | `array of PublicTransactionRecord` | 否 | items: PublicTransactionRecord<br>items 說明: 一筆交易帳本列。 | 已套用 selection 的 frozen 18 欄交易列，固定 tradeDate DESC、id DESC。 |

### `TransactionHistorySelection`

交易查詢的互斥選擇模式；ALL 沒有年月日限制，YEAR 僅有 year，DATE_RANGE 僅有 start/end。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `mode` | 是 | `string` | 否 | enum: `ALL`, `YEAR`, `DATE_RANGE` | 已解析的篩選模式。 可用值固定為 `ALL`、`YEAR`、`DATE_RANGE`。 |
| `year` | 是 | `integer | null (int32)` | 是 |  | YEAR 模式的西元年；其他模式為 null。 |
| `start` | 是 | `string | null (date)` | 是 |  | DATE_RANGE 或 YEAR 展開後的 inclusive 起日；ALL 為 null。 |
| `end` | 是 | `string | null (date)` | 是 |  | DATE_RANGE 或 YEAR 展開後的 inclusive 迄日；ALL 為 null。 |

### `TransactionPeriodSummary`

交易列的買賣筆數與台幣金額總計；USD amount 以 row exchangeRate 換算，fee 與 transactionTax 不納入。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `buyCount` | 是 | `integer (int32)` | 否 | minimum: 0 | transactionType 不是賣的買方（含既有資料其他類型）筆數。 |
| `sellCount` | 是 | `integer (int32)` | 否 | minimum: 0 | transactionType 為賣的筆數。 |
| `totalBuyAmountTwd` | 是 | `number` | 否 |  | 買方 amountTwd 加總；fee/tax 排除。 |
| `totalSellAmountTwd` | 是 | `number` | 否 |  | 賣方 amountTwd 加總；fee/tax 排除。 |

### `TransactionYearSummary`

選定範圍內單一年度的交易摘要。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `year` | 是 | `integer | null (int32)` | 是 |  | 原始交易列的年度欄位。 |
| `buyCount` | 是 | `integer (int32)` | 否 | minimum: 0 | 該年買方筆數。 |
| `sellCount` | 是 | `integer (int32)` | 否 | minimum: 0 | 該年賣方筆數。 |
| `totalBuyAmountTwd` | 是 | `number` | 否 |  | 該年買方 amountTwd 加總。 |
| `totalSellAmountTwd` | 是 | `number` | 否 |  | 該年賣方 amountTwd 加總。 |

### `PublicTransactionRecord`

Frozen 18 欄交易帳本列；原始 fee/tax 僅供紀錄，amountTwd 計算不加入 fee/tax。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `id` | 是 | `integer | null (int64)` | 是 |  | 交易列穩定識別碼；同日排序的次鍵。 |
| `transactionType` | 是 | `string | null` | 是 |  | 原始交易類型，例如買或賣。 |
| `assetType` | 是 | `string | null` | 是 |  | 原始資產類別。 |
| `assetName` | 是 | `string | null` | 是 |  | 原始資產名稱。 |
| `assetCode` | 是 | `string | null` | 是 |  | 原始資產代碼。 |
| `market` | 是 | `string | null` | 是 |  | 原始市場字串。 |
| `currency` | 是 | `string | null` | 是 |  | 原始交易幣別。 |
| `channel` | 是 | `string | null` | 是 |  | 原始紀錄通路字串；不是券商登入或帳戶資訊。 |
| `tradeDate` | 是 | `string | null (date)` | 是 |  | 交易日期；主排序鍵為降冪。 |
| `shares` | 是 | `number | null` | 是 |  | 原始數量。 |
| `price` | 是 | `number | null` | 是 |  | 原始單價。 |
| `amount` | 是 | `number | null` | 是 |  | 原始交易金額，不含 fee/tax 的額外加減。 |
| `fee` | 是 | `number | null` | 是 |  | 原始手續費紀錄；不計入 amountTwd。 |
| `transactionTax` | 是 | `number | null` | 是 |  | 原始交易稅紀錄；不計入 amountTwd。 |
| `exchangeRate` | 是 | `number | null` | 是 |  | USD amount 換算台幣時使用的原始匯率。 |
| `notes` | 是 | `string | null` | 是 |  | 原始備註文字。 |
| `amountTwd` | 是 | `number | null` | 是 |  | TWD 時等於 amount；USD 且有 amount/exchangeRate 時等於相乘；不納入 fee/tax。 |
| `year` | 是 | `integer | null (int32)` | 是 |  | 原始交易年度欄位。 |

### `PublicTradingCalendarResponse`

全域 no-tenant 指定年台、美、英交易日曆；days 一律為完整年度的 365 或 366 筆。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `year` | 是 | `integer (int32)` | 否 |  | 本次選定的西元年。 |
| `generatedAt` | 是 | `string (date-time)` | 否 |  | Asia/Taipei 產生 response 的 offset date-time。 |
| `timezone` | 是 | `string` | 否 | enum: `Asia/Taipei` | 年度驗證與 generatedAt 使用的時區。 可用值固定為 `Asia/Taipei`。 |
| `availableYears` | 是 | `array of integer (int32)` | 否 | minItems: 3<br>maxItems: 3<br>items: integer (int32)<br>items 說明: 可查詢的西元年。 | 依序為 Asia/Taipei currentYear-1、currentYear、currentYear+1。 |
| `minYear` | 是 | `integer (int32)` | 否 |  | local validation 接受的最小年度。 |
| `maxYear` | 是 | `integer (int32)` | 否 |  | local validation 接受的最大年度。 |
| `markets` | 是 | `array of CalendarMarketDefinition` | 否 | minItems: 3<br>maxItems: 3<br>items: CalendarMarketDefinition<br>items 說明: 一個市場的固定交易時段定義。 | 固定 TW、US、UK 的交易時段定義。 |
| `availability` | 是 | `CalendarAvailability` | 否 |  | 三個 holiday authority 的本次可用性。 |
| `tradingDayCount` | 是 | `CalendarTradingDayCount` | 否 |  | 三市場年度交易日數；authority 不可用時為 null。 |
| `holidays` | 是 | `CalendarHolidaySet` | 否 |  | 三市場依日期升冪的假日清單。 |
| `days` | 是 | `array of TradingCalendarDay` | 否 | minItems: 365<br>maxItems: 366<br>items: TradingCalendarDay<br>items 說明: 一個公曆日的三市場交易／假日旗標。 | 指定年從 1 月 1 日至 12 月 31 日、不缺日的逐日旗標；閏年為 366，否則 365。 |
| `marketStatus` | 是 | `CalendarMarketStatus` | 否 |  | 與年度日曆獨立讀取的目前三市場 session 狀態。 |

### `CalendarMarketDefinition`

三個市場的靜態常規交易時段；不因特定 holiday 或早收盤動態改寫。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `code` | 是 | `string` | 否 | enum: `TW`, `US`, `UK` | 市場短碼。 可用值固定為 `TW`、`US`、`UK`。 |
| `displayName` | 是 | `string` | 否 | enum: `台股`, `美股`, `英股` | 市場中文顯示名稱。 可用值固定為 `台股`、`美股`、`英股`。 |
| `exchange` | 是 | `string` | 否 | enum: `TWSE`, `NYSE`, `LSE` | 對應交易所。 可用值固定為 `TWSE`、`NYSE`、`LSE`。 |
| `timezone` | 是 | `string` | 否 | enum: `Asia/Taipei`, `America/New_York`, `Europe/London` | 市場本地 IANA 時區。 可用值固定為 `Asia/Taipei`、`America/New_York`、`Europe/London`。 |
| `regularTradingHours` | 是 | `string` | 否 | enum: `09:00-13:30`, `09:30-16:00`, `08:00-16:30` | 正常盤常規時段，非逐日實際開市結論。 可用值固定為 `09:00-13:30`、`09:30-16:00`、`08:00-16:30`。 |
| `daylightSavingSupported` | 是 | `boolean` | 否 |  | 市場時區是否有日光節約時間。 |

### `CalendarAvailability`

每個年度 holiday authority 的獨立可用性；部分不可用是有效 200。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `tw` | 是 | `CalendarAuthorityAvailability` | 否 |  | TWSE/DGPA authority 狀態。 |
| `us` | 是 | `CalendarAuthorityAvailability` | 否 |  | NYSE authority 狀態。 |
| `uk` | 是 | `CalendarAuthorityAvailability` | 否 |  | LSE authority 狀態。 |

### `CalendarAuthorityAvailability`

單一 holiday authority 的可用性與不含上游例外細節的訊息。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `status` | 是 | `string` | 否 | enum: `AVAILABLE`, `UNAVAILABLE` | 本年度該 authority 是否可用。 可用值固定為 `AVAILABLE`、`UNAVAILABLE`。 |
| `source` | 是 | `string` | 否 | enum: `TWSE/DGPA`, `NYSE`, `LSE` | authority 身分。 可用值固定為 `TWSE/DGPA`、`NYSE`、`LSE`。 |
| `message` | 是 | `string | null` | 是 |  | UNAVAILABLE 時的一般化訊息；AVAILABLE 時為 null。 |

### `CalendarTradingDayCount`

三市場的年度交易日總數；該市場 authority unavailable 時為 null，不能以零代替。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `tw` | 是 | `integer | null (int32)` | 是 | minimum: 0 | 台股年度交易日數或 null。 |
| `us` | 是 | `integer | null (int32)` | 是 | minimum: 0 | 美股年度交易日數或 null。 |
| `uk` | 是 | `integer | null (int32)` | 是 | minimum: 0 | 英股年度交易日數或 null。 |

### `CalendarHolidaySet`

三市場的已解析 holiday rows；authority unavailable 時該市場陣列為空，availability 表示原因。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `tw` | 是 | `array of CalendarHoliday` | 否 | items: CalendarHoliday<br>items 說明: 一筆台股假日。 | 台股假日，日期升冪。 |
| `us` | 是 | `array of CalendarHoliday` | 否 | items: CalendarHoliday<br>items 說明: 一筆美股假日。 | 美股假日，日期升冪。 |
| `uk` | 是 | `array of CalendarHoliday` | 否 | items: CalendarHoliday<br>items 說明: 一筆英股假日。 | 英股假日，日期升冪。 |

### `CalendarHoliday`

holiday authority 回傳並正規化的單日假日。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `date` | 是 | `string (date)` | 否 |  | 假日日期。 |
| `name` | 是 | `string | null` | 是 |  | authority 提供的假日名稱。 |

### `TradingCalendarDay`

指定年一個公曆日的週末、交易與假日旗標；authority unavailable 時對應交易／假日旗標皆為 null。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `date` | 是 | `string (date)` | 否 |  | 公曆日期。 |
| `weekday` | 是 | `string` | 否 | enum: `一`, `二`, `三`, `四`, `五`, `六`, `日` | 中文單字星期。 可用值固定為 `一`、`二`、`三`、`四`、`五`、`六`、`日`。 |
| `isWeekend` | 是 | `boolean` | 否 |  | 是否為星期六或星期日。 |
| `twTrading` | 是 | `boolean | null` | 是 |  | 台股是否交易；TW authority unavailable 時為 null。 |
| `usTrading` | 是 | `boolean | null` | 是 |  | 美股是否交易；US authority unavailable 時為 null。 |
| `ukTrading` | 是 | `boolean | null` | 是 |  | 英股是否交易；UK authority unavailable 時為 null。 |
| `twHoliday` | 是 | `boolean | null` | 是 |  | 台股是否為 authority 假日；TW authority unavailable 時為 null。 |
| `usHoliday` | 是 | `boolean | null` | 是 |  | 美股是否為 authority 假日；US authority unavailable 時為 null。 |
| `ukHoliday` | 是 | `boolean | null` | 是 |  | 英股是否為 authority 假日；UK authority unavailable 時為 null。 |

### `CalendarMarketStatus`

獨立於選定年 holiday authority 的目前 session status。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `tw` | 是 | `MarketSessionStatus` | 否 |  | 台股目前 session。 |
| `us` | 是 | `MarketSessionStatus` | 否 |  | 美股目前 session。 |
| `uk` | 是 | `MarketSessionStatus` | 否 |  | 英股目前 session。 |

### `MarketSessionStatus`

現有市場狀態讀取正規化出的單市場 session；缺資料時時間與日期為 null，但 marketOpen 固定存在。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `marketOpen` | 是 | `boolean` | 否 |  | 當前市場是否開市。 |
| `localTime` | 是 | `string | null` | 是 |  | 現有市場狀態提供的市場本地時間字串。 |
| `displayTradingDate` | 是 | `string | null` | 是 |  | 現有市場狀態提供的市場交易日字串。 |
| `timezone` | 是 | `string` | 否 | enum: `Asia/Taipei`, `America/New_York`, `Europe/London` | 該 session 的 IANA 時區。 可用值固定為 `Asia/Taipei`、`America/New_York`、`Europe/London`。 |

### `MarketSummary`

交易雷達使用的大盤趨勢、均線、風險與美股科技情境摘要。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `regime` | 是 | `string | null` | 是 |  | 市場趨勢型態代碼。 |
| `regimeLabel` | 是 | `string | null` | 是 |  | 市場趨勢型態的顯示名稱。 |
| `score` | 是 | `integer | null (int32)` | 是 |  | 策略或市場判斷使用的量化分數。 |
| `dataComplete` | 是 | `boolean` | 否 |  | 決策需要的必要輸入是否齊全。 |
| `stale` | 是 | `boolean` | 否 |  | 內容是否超過對應的新鮮度門檻。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |
| `price` | 是 | `number | null` | 是 |  | 大盤摘要採用的最新價格。 |
| `changePercent` | 是 | `number | null` | 是 |  | 相對比較基準的變動百分比。 |
| `quoteStatus` | 是 | `string | null` | 是 |  | 行情來源狀態自由字串。 |
| `weeklyMa` | 是 | `number | null` | 是 |  | 週線移動平均的量化值。 |
| `monthlyMa` | 是 | `number | null` | 是 |  | 月線時間框架的移動平均。 |
| `quarterlyMa` | 是 | `number | null` | 是 |  | 季線時間框架的移動平均。 |
| `annualMa` | 是 | `number | null` | 是 |  | 年線時間框架的移動平均。 |
| `kValue` | 是 | `number | null` | 是 |  | 策略採用的 KD K 值。 |
| `dValue` | 是 | `number | null` | 是 |  | 策略採用的 KD D 值。 |
| `quarterlyConfirmation` | 是 | `string | null` | 是 |  | 季線訊號是否確認策略判斷。 |
| `annualConfirmation` | 是 | `string | null` | 是 |  | 年線訊號是否確認策略判斷。 |
| `reasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持該判斷的可讀理由清單。 | 支持該判斷的可讀理由清單。 |
| `risks` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：可能改變或削弱判斷的風險清單。 | 可能改變或削弱判斷的風險清單。 |
| `intraday` | 是 | `boolean` | 否 |  | 當日分時價格與技術狀態。 |
| `liveUpdatedAt` | 是 | `string | null` | 是 |  | PriceCacheWriter 產生的 Asia/Taipei local wall-clock ISO LocalDateTime 字串， 不含 UTC offset，因此刻意不宣告 OpenAPI date-time；null 表示無盤中時間。 |
| `extendedIndicators` | 是 | `ExtendedIndicators | null` | 是 |  | 延伸技術指標集合。 |
| `marketVolumeRatio` | 是 | `number | null` | 是 |  | 大盤成交量相對基準期的比率。 |
| `marketTurnoverRatio` | 是 | `number | null` | 是 |  | 大盤成交金額相對基準期的比率。 |
| `marketVolumeAsOfDate` | 是 | `string | null (date)` | 是 |  | 大盤成交量比率所屬日期。 |
| `nasdaqChangePercent` | 是 | `number | null` | 是 |  | 那斯達克指數的變動百分比。 |
| `soxChangePercent` | 是 | `number | null` | 是 |  | 費城半導體指數的變動百分比。 |
| `usTechCompositePercent` | 是 | `number | null` | 是 |  | 美股科技情境的合成百分比分數。 |
| `usTechAsOfDate` | 是 | `string | null (date)` | 是 |  | 美股科技情境所屬日期。 |
| `usTechAvailable` | 是 | `boolean` | 否 |  | 美股科技情境是否可用。 |
| `weeklyIndicators` | 是 | `WeeklyIndicators | null` | 是 |  | 大盤週線技術指標快照。 |

### `ExtendedIndicators`

KD、MACD、RSI、乖離與威廉指標的延伸技術指標快照。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `j9` | 是 | `number | null` | 是 |  | 九期 KD J 值。 |
| `k3d2` | 是 | `number | null` | 是 |  | 三期 K、二期 D 組合指標。 |
| `rsv` | 是 | `number | null` | 是 |  | KD 計算使用的未成熟隨機值。 |
| `ema12` | 是 | `number | null` | 是 |  | 近 12 期的指數移動平均。 |
| `ema26` | 是 | `number | null` | 是 |  | 近 26 期的指數移動平均。 |
| `dif` | 是 | `number | null` | 是 |  | MACD 的快慢均線差值。 |
| `macd` | 是 | `number | null` | 是 |  | MACD 訊號線值。 |
| `osc` | 是 | `number | null` | 是 |  | MACD 柱狀震盪值。 |
| `rsi5` | 是 | `number | null` | 是 |  | 近 5 期的相對強弱指標。 |
| `rsi10` | 是 | `number | null` | 是 |  | 近 10 期的相對強弱指標。 |
| `bias10` | 是 | `number | null` | 是 |  | 價格相對 10 期移動平均的乖離率。 |
| `bias20` | 是 | `number | null` | 是 |  | 價格相對 20 期移動平均的乖離率。 |
| `b10b20` | 是 | `number | null` | 是 |  | 十期與二十期乖離率的交叉指標。 |
| `wr9` | 是 | `number | null` | 是 |  | 九期威廉指標值。 |

### `StockDecision`

`TradingRadarDto.StockDecision` 的固定 record projection；分數、價格、指標與證據不足時以 null 表示，不以 0 冒充。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `stockCode` | 是 | `string | null` | 是 |  | 交易所或來源使用的標的代號。 |
| `stockName` | 是 | `string | null` | 是 |  | 供畫面與批次辨識的標的名稱。 |
| `market` | 是 | `string | null` | 是 |  | DB 驅動的市場字串。 |
| `assetClass` | 是 | `string | null` | 是 |  | 資產分類，例如股票、基金、存款或 ETF。 |
| `distributionAdjusted` | 是 | `boolean` | 否 |  | 分配／除權息因素是否已調整到策略判斷。 |
| `held` | 是 | `boolean` | 否 |  | configured-admin 範圍內是否持有該標的。 |
| `action` | 是 | `string | null` | 是 |  | 規則引擎動作自由字串。 |
| `actionLabel` | 是 | `string | null` | 是 |  | 策略動作的顯示名稱。 |
| `score` | 是 | `integer | null (int32)` | 是 |  | 策略或市場判斷使用的量化分數。 |
| `counterTrendState` | 是 | `string | null` | 是 |  | 逆勢判斷的狀態代碼。 |
| `counterTrendLabel` | 是 | `string | null` | 是 |  | 逆勢判斷的顯示名稱。 |
| `counterTrendReasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持逆勢判斷的理由清單。 | 支持逆勢判斷的理由清單。 |
| `counterTrendRisks` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：逆勢判斷的風險清單。 | 逆勢判斷的風險清單。 |
| `dataComplete` | 是 | `boolean` | 否 |  | 決策需要的必要輸入是否齊全。 |
| `price` | 是 | `number | null` | 是 |  | 目前或該時間點的成交／估值價格。 |
| `changePercent` | 是 | `number | null` | 是 |  | 相對比較基準的變動百分比。 |
| `quoteStatus` | 是 | `string | null` | 是 |  | 原始報價可用性或盤中狀態。 |
| `priceUpdatedAt` | 是 | `string | null` | 是 |  | 行情原始時點字串，不保證有 UTC offset。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |
| `monthlyMa` | 是 | `number | null` | 是 |  | 月線時間框架的移動平均。 |
| `quarterlyMa` | 是 | `number | null` | 是 |  | 季線時間框架的移動平均。 |
| `annualMa` | 是 | `number | null` | 是 |  | 年線時間框架的移動平均。 |
| `kValue` | 是 | `number | null` | 是 |  | 策略採用的 KD K 值。 |
| `dValue` | 是 | `number | null` | 是 |  | 策略採用的 KD D 值。 |
| `monthlyConfirmation` | 是 | `string | null` | 是 |  | 月線訊號是否確認策略判斷。 |
| `quarterlyConfirmation` | 是 | `string | null` | 是 |  | 季線訊號是否確認策略判斷。 |
| `annualConfirmation` | 是 | `string | null` | 是 |  | 年線訊號是否確認策略判斷。 |
| `fxPercentile` | 是 | `number | null` | 是 |  | 匯率在計算窗口中的百分位。 |
| `underlyingCurrency` | 是 | `string | null` | 是 |  | 標的底層資產的幣別。 |
| `reasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持該判斷的可讀理由清單。 | 支持該判斷的可讀理由清單。 |
| `risks` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：可能改變或削弱判斷的風險清單。 | 可能改變或削弱判斷的風險清單。 |
| `kdHeat` | 是 | `string | null` | 是 |  | KD 指標反映的過熱或過冷程度。 |
| `timingState` | 是 | `string | null` | 是 |  | 進出場時點判斷的狀態代碼。 |
| `timingLabel` | 是 | `string | null` | 是 |  | 進出場時點判斷的顯示名稱。 |
| `ma60BiasPercent` | 是 | `number | null` | 是 |  | 價格相對 60 期均線的乖離百分比。 |
| `week52Position` | 是 | `number | null` | 是 |  | 目前價格在 52 週高低區間中的位置。 |
| `weeklyMa` | 是 | `number | null` | 是 |  | 週線移動平均的量化值。 |
| `etfPremiumPct` | 是 | `number | null` | 是 |  | ETF 溢折價百分比。 |
| `etfPremiumPercentile` | 是 | `number | null` | 是 |  | ETF 溢折價在計算窗口中的百分位。 |
| `extendedIndicators` | 是 | `ExtendedIndicators | null` | 是 |  | 延伸技術指標集合。 |
| `shortAction` | 是 | `string | null` | 是 |  | 短期時間框架的策略動作代碼。 |
| `shortActionLabel` | 是 | `string | null` | 是 |  | 短期時間框架策略動作的顯示名稱。 |
| `shortScore` | 是 | `integer | null (int32)` | 是 |  | 短期分數的量化值。 |
| `shortReasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：短期理由的業務屬性。 | 依序列出的短期理由項目。 |
| `shortRisks` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：短期風險的業務屬性。 | 依序列出的短期風險項目。 |
| `horizonConflict` | 是 | `boolean` | 否 |  | 不同投資期間的判斷是否彼此衝突。 |
| `volumeRatio` | 是 | `number | null` | 是 |  | 成交量相對基準期的比率。 |
| `fxAsOfDate` | 是 | `string | null (date)` | 是 |  | 匯率判斷所採用的日期。 |
| `profitTakingConfirmed` | 是 | `boolean` | 否 |  | 停利條件是否已被所需證據確認。 |
| `fundamental` | 是 | `FundamentalSnapshot | null` | 是 |  | 基礎面、估值與產業公開指標。 |
| `evidence` | 是 | `RadarEvidence | null` | 是 |  | 完整展開判斷使用的證據上下文。 |
| `shortDownsideRisk` | 是 | `integer | null (int32)` | 是 |  | 短期策略判定的下行風險。 |
| `mediumDownsideRisk` | 是 | `integer | null (int32)` | 是 |  | 中期策略判定的下行風險。 |
| `shortEvidenceConfidence` | 是 | `integer | null (int32)` | 是 |  | 短期證據的信心分數。 |
| `mediumEvidenceConfidence` | 是 | `integer | null (int32)` | 是 |  | 中期證據的信心分數。 |
| `shortRiskCoverage` | 是 | `number | null (double)` | 是 |  | 短期風險證據的覆蓋比例。 |
| `mediumRiskCoverage` | 是 | `number | null (double)` | 是 |  | 中期風險證據的覆蓋比例。 |
| `candidateAction` | 是 | `string | null` | 是 |  | 主要時間框架建議採用的候選動作。 |
| `shortCandidateAction` | 是 | `string | null` | 是 |  | 短期時間框架建議採用的候選動作。 |
| `actionGateReasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：三軌動作閘門／風險稽核彙總的一項不利診斷；不是任一軌 support source（支持訊號來源）或允許動作依據。 | 三軌動作閘門／風險稽核彙總（medium→short→swing 的 stable-distinct union）；不是任一軌 support source（支持訊號來源）或允許動作依據。 |
| `etfPremiumLivePct` | 是 | `number | null` | 是 |  | 即時 ETF 溢折價百分比。 |
| `etfPremiumLiveNavAsOf` | 是 | `string | null` | 是 |  | 台股與美股來源格式不同，不宣告 date-time format。 |
| `swingAction` | 是 | `string | null` | 是 |  | 1周~1月 軌動作自由字串；未供給該軌時為 null。 |
| `swingActionLabel` | 是 | `string | null` | 是 |  | 波段時間框架策略動作的顯示名稱。 |
| `swingScore` | 是 | `integer | null (int32)` | 是 |  | 波段分數的量化值。 |
| `swingReasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：波段理由的業務屬性。 | 依序列出的波段理由項目。 |
| `swingRisks` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：波段風險的業務屬性。 | 依序列出的波段風險項目。 |
| `swingDownsideRisk` | 是 | `integer | null (int32)` | 是 |  | 波段策略判定的下行風險。 |
| `swingEvidenceConfidence` | 是 | `integer | null (int32)` | 是 |  | 波段證據的信心分數。 |
| `swingRiskCoverage` | 是 | `number | null (double)` | 是 |  | 波段風險證據的覆蓋比例。 |
| `swingCandidateAction` | 是 | `string | null` | 是 |  | 波段時間框架建議採用的候選動作。 |
| `dailyCandle` | 是 | `DailyCandle | null` | 是 |  | 交易雷達採用的最近一日 OHLC K 棒。 |
| `weeklyIndicators` | 是 | `WeeklyIndicators | null` | 是 |  | 交易雷達採用的週線技術指標。 |
| `technicalResolution` | 是 | `TechnicalResolution | null` | 是 |  | Task408 技術指標來源決策。null 僅表示舊快照的 LEGACY_LOCAL_V0，不能當成現在的富邦資料或零值。 |

### `TechnicalResolution`

固定 17-profile 技術 bundle 的來源、100 秒 freshness、context binding 與欄位級採用稽核。PostgreSQL 是 FUBON_SDK 歷史權威；Redis 僅為 bounded 即時 overlay。LOCAL_CALCULATED 只會覆寫 Redis，不會覆寫富邦 PostgreSQL facts/members。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `decisionInputVersion` | 是 | `string | null` | 是 |  | 固定為 TW_RULES_V18\|FUBON_OVERLAY_V1；舊 snapshot 缺整個 technicalResolution，不以此欄猜測版本。 |
| `source` | 是 | `string | null` | 是 | enum: `FUBON_SDK`, `LOCAL_CALCULATED` | 實際提供本次 technical boundary 的來源。FUBON_SDK 表示已通過 context／freshness／exact-17 驗證的富邦值（Redis BOUND 命中或 PostgreSQL historical capture 重新投影）；LOCAL_CALCULATED 表示富邦值不適用時的本地完整計算，僅覆寫 Redis、絕不覆寫 PostgreSQL 富邦 facts/members。兩者都不代表每一個 V18 欄位必然採用富邦值，逐欄以 fieldProvenance 為準。 |
| `binding` | 是 | `string | null` | 是 | enum: `BOUND_CONTEXT`, `UNBOUND_FUBON_SOURCE` | Redis 文件的 context binding。BOUND_CONTEXT 是已綁定本次 decision fingerprint、雷達可採用的文件；UNBOUND_FUBON_SOURCE 是 scheduler 寫入但尚未綁定 decision context 的原始富邦投影，雷達不得直接採用。 |
| `contextFingerprint` | 是 | `string | null` | 是 |  | 同一 decision input context 的 SHA-256 指紋；UNBOUND_FUBON_SOURCE 時為 null。 |
| `captureId` | 是 | `string | null` | 是 |  | 富邦 complete capture 或 local bundle generation 的 UUID。 |
| `oldestObservedAt` | 是 | `string | null (date-time)` | 是 |  | bundle 17 profiles 中最早的 immutable observedAt；100 秒 freshness 的唯一起點。 |
| `freshUntil` | 是 | `string | null (date-time)` | 是 |  | oldestObservedAt 加 100 秒的絕對 deadline；DB re-project 不得延長它。 |
| `ageSeconds` | 是 | `integer | null (int64)` | 是 |  | 回應生成時相對 oldestObservedAt 的秒數；99/100 為可讀邊界，101 必回 local fallback。 |
| `profiles` | 是 | `array of TechnicalProfileResolution` | 否 | items: TechnicalProfileResolution<br>items 說明: 一個固定 timeframe/profile 的候選結果。 | manifest-order fixed profiles 的候選與是否可被 V18 採用。完整正常 bundle 為 17 項。 |
| `fieldProvenance` | 是 | `array of TechnicalFieldProvenance` | 否 | items: TechnicalFieldProvenance<br>items 說明: 一個技術欄位的來源稽核。 | 每個 direct overlay、local derivation 或 detail-only 值的來源與未採用原因。 |

### `TechnicalProfileResolution`

一個 immutable profile 的最大 valid source-date candidate，沒有 history array；完整歷史由 PostgreSQL fubon technical fact/member 保留。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `profileId` | 是 | `string | null` | 是 |  | immutable 17-profile manifest ID，例如 sma_d_20、rsi_w_10、kdj_d_9_3_3。 |
| `status` | 是 | `string | null` | 是 |  | provider candidate 的 AVAILABLE／NO_DATA／SCHEMA_INVALID／UNAVAILABLE 狀態。 |
| `reason` | 是 | `string | null` | 是 |  | 非 AVAILABLE 的 strict failure reason，或 V18 未採用的原因。 |
| `parameters` | 是 | `object | null` | 是 |  | immutable provider parameters。每個 profile 的精確 key/value 在 strict resolver 驗證。 |
| `payload` | 是 | `object | null` | 是 |  | candidate 的 strict canonical decimal-string payload；非 AVAILABLE 可為 null。 |
| `sourceDate` | 是 | `string | null (date)` | 是 |  | candidate 的最大 valid source date；weekly profile 為最近完成週末交易日。 |
| `observedAt` | 是 | `string | null (date-time)` | 是 |  | 該 profile SDK response 返回時固定的 immutable observedAt。 |
| `eligibility` | 是 | `string | null` | 是 |  | APPLIED、DETAIL_ONLY、AVAILABLE_NOT_APPLIED 或未可用原因；前端只呈現，絕不自行補算。 |

### `TechnicalFieldProvenance`

一個送入或揭露於交易雷達的技術欄位來源。DETAIL_ONLY 永遠不改 V18 score；LOCAL 也可能是 DERIVED_FROM_FUBON 的局部衍生值。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `field` | 是 | `string | null` | 是 |  | DTO／detail 內的確切欄位路徑。 |
| `origin` | 是 | `string | null` | 是 | enum: `FUBON_SDK`, `LOCAL`, `DERIVED_FROM_FUBON`, `DETAIL_ONLY`, `AVAILABLE_NOT_APPLIED` | 欄位實際來源或可用但未採用的狀態：FUBON_SDK 是已套用的富邦 profile，LOCAL 是本地計算，DERIVED_FROM_FUBON 是從富邦值衍生，DETAIL_ONLY 僅供明細，AVAILABLE_NOT_APPLIED 是可用但被 basis/date gate 擋下。 |
| `profileId` | 是 | `string | null` | 是 |  | 若有對應富邦 profile，為其 immutable profile ID；純 local 值可為 null。 |
| `reason` | 是 | `string | null` | 是 |  | direct overlay、detail-only 或 fail-closed 未採用的具體理由。 |

### `DailyCandle`

最新完成日的還原 K 棒與其三個分量（closePosition／bodyDirection／lowerShadowRatio 為未 clamp 的原值）。 全幅非正（漲跌停鎖死、整日單一成交價或倒置髒列）時三個分量皆為 null，不得以 0 或 0.5 冒充； bodyDirection 的 0 是十字線（high > low 且 close == open），與「沒有價格區間」是不同狀態。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `open` | 是 | `number | null` | 是 |  | 該時間框架的開盤價。 |
| `high` | 是 | `number | null` | 是 |  | 該時間框架的最高價。 |
| `low` | 是 | `number | null` | 是 |  | 該時間框架的最低價。 |
| `close` | 是 | `number | null` | 是 |  | 該時間框架的收盤價。 |
| `closePosition` | 是 | `number | null` | 是 |  | (close − low) / (high − low)，值域 [0,1]。 |
| `bodyDirection` | 是 | `number | null` | 是 |  | sign(close − open) ∈ {-1, 0, 1}。 |
| `lowerShadowRatio` | 是 | `number | null` | 是 |  | (min(open, close) − low) / (high − low)，值域 [0,1]。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |

### `WeeklyIndicators`

最新完成週的週 OHLC 聚合與在週K 序列上重算的指標。與同一物件的 weeklyMa 是兩個不同的量 （後者為日K 收盤序列的 5 日 SMA，命名沿革）。weekEndDate 為上一個完成週的最後交易日，不是本週任何一天； completedWeeks 不足 60 根時整組指標為 null 而 completedWeeks 仍如實回報。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `weekEndDate` | 是 | `string | null (date)` | 是 |  | 週線指標所屬週期的結束日期。 |
| `completedWeeks` | 是 | `integer | null (int32)` | 是 |  | 完成週的量化值。 |
| `open` | 是 | `number | null` | 是 |  | 該時間框架的開盤價。 |
| `high` | 是 | `number | null` | 是 |  | 該時間框架的最高價。 |
| `low` | 是 | `number | null` | 是 |  | 該時間框架的最低價。 |
| `close` | 是 | `number | null` | 是 |  | 該時間框架的收盤價。 |
| `volume` | 是 | `integer | null (int64)` | 是 |  | 該時間框架的成交量。 |
| `ma5` | 是 | `number | null` | 是 |  | 近 5 期的簡單移動平均。 |
| `ma10` | 是 | `number | null` | 是 |  | 近 10 期的簡單移動平均。 |
| `ma20` | 是 | `number | null` | 是 |  | 近 20 期的簡單移動平均。 |
| `k` | 是 | `number | null` | 是 |  | KD 指標的 K 值。 |
| `d` | 是 | `number | null` | 是 |  | KD 指標的 D 值。 |
| `j9` | 是 | `number | null` | 是 |  | 九期 KD J 值。 |
| `dif` | 是 | `number | null` | 是 |  | MACD 的快慢均線差值。 |
| `macd` | 是 | `number | null` | 是 |  | MACD 訊號線值。 |
| `osc` | 是 | `number | null` | 是 |  | MACD 柱狀震盪值。 |
| `rsi5` | 是 | `number | null` | 是 |  | 近 5 期的相對強弱指標。 |
| `rsi10` | 是 | `number | null` | 是 |  | 近 10 期的相對強弱指標。 |
| `bias10` | 是 | `number | null` | 是 |  | 價格相對 10 期移動平均的乖離率。 |
| `bias20` | 是 | `number | null` | 是 |  | 價格相對 20 期移動平均的乖離率。 |
| `volumeRatio` | 是 | `number | null` | 是 |  | 成交量相對基準期的比率。 |
| `changePercent` | 是 | `number | null` | 是 |  | 相對比較基準的變動百分比。 |
| `closePosition` | 是 | `number | null` | 是 |  | 收盤位置的量化值。 |
| `bodyDirection` | 是 | `number | null` | 是 |  | 實體方向的量化值。 |

### `FundamentalSnapshot`

每股盈餘、ROE、營收、估值與產業公開指標的基礎面快照。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `applicable` | 是 | `boolean` | 否 |  | 此項基礎面或證據是否適用於該標的。 |
| `coverage` | 是 | `integer (int32)` | 否 |  | 必要證據或輸入已涵蓋的比例。 |
| `epsYoyPct` | 是 | `number | null` | 是 |  | 每股盈餘的年增百分比。 |
| `approximateRoePct` | 是 | `number | null` | 是 |  | 以公開數字近似計算的 ROE 百分比。 |
| `revenueYoy3mPct` | 是 | `number | null` | 是 |  | 近三個月營收的年增百分比。 |
| `pePercentile` | 是 | `number | null` | 是 |  | 本益比在計算窗口中的百分位。 |
| `peLossFlag` | 是 | `boolean | null` | 是 |  | 本益比是否因虧損而不適用。 |
| `epsProvider` | 是 | `string | null` | 是 |  | 每股盈餘數字的公開供應者。 |
| `epsSourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持每股盈餘數字的公開來源網址。 | 支持每股盈餘數字的公開來源網址。 |
| `epsAsOf` | 是 | `string | null (date)` | 是 |  | 每股盈餘數字所屬日期或期間。 |
| `roeProvider` | 是 | `string | null` | 是 |  | ROE 數字的公開供應者。 |
| `roeSourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持 ROE 數字的公開來源網址。 | 支持 ROE 數字的公開來源網址。 |
| `roeAsOf` | 是 | `string | null (date)` | 是 |  | ROE 數字所屬日期或期間。 |
| `revenueProvider` | 是 | `string | null` | 是 |  | 營收數字的公開供應者。 |
| `revenueSourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持營收數字的公開來源網址。 | 支持營收數字的公開來源網址。 |
| `revenueAsOf` | 是 | `string | null (date)` | 是 |  | 營收數字所屬日期或期間。 |
| `valuationProvider` | 是 | `string | null` | 是 |  | 估值數字的公開供應者。 |
| `valuationSourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持估值數字的公開來源網址。 | 支持估值數字的公開來源網址。 |
| `valuationAsOf` | 是 | `string | null (date)` | 是 |  | 估值數字所屬日期或期間。 |
| `industryName` | 是 | `string | null` | 是 |  | 標的所屬產業名稱。 |
| `industryRevenueYoyPct` | 是 | `number | null` | 是 |  | 產業營收的年增百分比。 |
| `industryCompanyCount` | 是 | `integer | null (int32)` | 是 |  | 產業彙總涵蓋的公司數量。 |
| `industryPeriod` | 是 | `string | null` | 是 |  | 產業營收指標所屬期間。 |
| `industryProvider` | 是 | `string | null` | 是 |  | 產業指標的公開供應者。 |
| `industrySourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持產業指標的公開來源網址。 | 支持產業指標的公開來源網址。 |
| `industryAsOf` | 是 | `string | null (date)` | 是 |  | 產業指標所屬日期或期間。 |
| `companyPublicInformation` | 是 | `array of PublicInformationItem` | 否 | items: PublicInformationItem<br>items 說明: 陣列中的單一元素：與標的公司直接相關的公開資訊清單。 | 與標的公司直接相關的公開資訊清單。 |
| `industryPublicInformation` | 是 | `array of PublicInformationItem` | 否 | items: PublicInformationItem<br>items 說明: 陣列中的單一元素：與所屬產業相關的公開資訊清單。 | 與所屬產業相關的公開資訊清單。 |
| `peValue` | 是 | `number | null` | 是 |  | 本益比數值。 |
| `pbValue` | 是 | `number | null` | 是 |  | 股價淨值比數值。 |
| `dividendYieldPct` | 是 | `number | null` | 是 |  | 現金股利殖利率百分比。 |
| `pbPercentile` | 是 | `number | null` | 是 |  | 股價淨值比在計算窗口中的百分位。 |
| `dividendYieldPercentile` | 是 | `number | null` | 是 |  | 股利殖利率在計算窗口中的百分位。 |
| `valuationContribution` | 是 | `number | null (double)` | 是 |  | 估值面向對總體判斷的加權貢獻。 |
| `valuationCoverage` | 是 | `integer (int32)` | 否 |  | 估值面向必要指標的涵蓋比例。 |
| `epsTrendType` | 是 | `string | null` | 是 |  | 每股盈餘趨勢的分類。 |
| `roeApproximationFallback` | 是 | `boolean` | 否 |  | ROE 是否採用近似替代計算。 |
| `peEvidence` | 是 | `ValuationComponentEvidence | null` | 是 |  | 本益比數值、百分位與來源證據。 |
| `pbEvidence` | 是 | `ValuationComponentEvidence | null` | 是 |  | 股價淨值比數值、百分位與來源證據。 |
| `dividendYieldEvidence` | 是 | `ValuationComponentEvidence | null` | 是 |  | 股利殖利率數值、百分位與來源證據。 |

### `ValuationComponentEvidence`

單一估值元件的值、百分位、來源、可用時點與虧損旗標。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `value` | 是 | `number | null` | 是 |  | 估值元件的原始數值。 |
| `percentile` | 是 | `number | null` | 是 |  | 指標在定義計算窗口中的百分位。 |
| `provider` | 是 | `string | null` | 是 |  | 提供數值或公開內容的供應者識別。 |
| `sourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支援此項指標的一組公開來源網址。 | 支援此項指標的一組公開來源網址。 |
| `availableAt` | 是 | `string | null (date-time)` | 是 |  | 該內容可被系統使用或首次觀測的時間點。 |
| `asOf` | 是 | `string | null (date)` | 是 |  | 該數值所屬日期或期間。 |
| `loss` | 是 | `boolean` | 否 |  | 該估值元件是否因虧損而不適用。 |

### `RadarEvidence`

交易雷達完整展開列使用的價格採用、風險、股利與證據上下文。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `acceptedPriceAsOfDate` | 是 | `string | null (date)` | 是 |  | 決策實際採用報價的日期。 |
| `acceptedPriceSource` | 是 | `string | null` | 是 |  | 決策實際採用報價的來源。 |
| `acceptedPriceQuality` | 是 | `string | null` | 是 |  | 決策實際採用報價的品質狀態。 |
| `livePriceAccepted` | 是 | `boolean` | 否 |  | 即時價格是否通過決策採用檢核。 |
| `returnStdDev60Ratio` | 是 | `number | null` | 是 |  | 60 期報酬標準差相對基準的比率。 |
| `returnStdDev60AsOfDate` | 是 | `string | null (date)` | 是 |  | 60 期報酬標準差所屬的日期。 |
| `returnStdDev60Source` | 是 | `string | null` | 是 |  | 60 期報酬標準差的公開來源。 |
| `premiumAsOfDate` | 是 | `string | null (date)` | 是 |  | ETF 溢折價所屬的日期。 |
| `premiumSource` | 是 | `string | null` | 是 |  | ETF 溢折價的公開來源。 |
| `premiumStale` | 是 | `boolean` | 否 |  | ETF 溢折價是否超過新鮮度門檻。 |
| `assetProfile` | 是 | `AssetProfile | null` | 是 |  | 標的分類、商品型態與幣別辨識上下文。 |
| `settingsClassification` | 是 | `SettingsClassification | null` | 是 |  | 資產類別設定頁等價的有效類別／細分與 RULE／OVERRIDE 來源；不等同 strict AssetProfile。台股 0000 或舊 snapshot 為 null。 |
| `actionGateReasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：三軌動作閘門／風險稽核彙總的一項不利診斷；不是任一軌 support source（支持訊號來源）或允許動作依據。 | 三軌動作閘門／風險稽核彙總（medium→short→swing 的 stable-distinct union）；不是任一軌 support source（支持訊號來源）或允許動作依據。 |
| `evidenceGroups` | 是 | `object` | 否 |  | 按名稱索引的證據群組。 |
| `marketFeatures` | 是 | `object` | 否 |  | 按市場代碼索引的大盤特徵證據。 |
| `shortEvidenceConfidence` | 是 | `integer | null (int32)` | 是 |  | 短期證據的信心分數。 |
| `mediumEvidenceConfidence` | 是 | `integer | null (int32)` | 是 |  | 中期證據的信心分數。 |
| `shortDownsideRisk` | 是 | `integer | null (int32)` | 是 |  | 短期策略判定的下行風險。 |
| `mediumDownsideRisk` | 是 | `integer | null (int32)` | 是 |  | 中期策略判定的下行風險。 |
| `shortRiskCoverage` | 是 | `number | null (double)` | 是 |  | 短期風險證據的覆蓋比例。 |
| `mediumRiskCoverage` | 是 | `number | null (double)` | 是 |  | 中期風險證據的覆蓋比例。 |
| `candidateAction` | 是 | `string | null` | 是 |  | 主要時間框架建議採用的候選動作。 |
| `shortCandidateAction` | 是 | `string | null` | 是 |  | 短期時間框架建議採用的候選動作。 |
| `nextDistributionDate` | 是 | `string | null (date)` | 是 |  | 下一次預估配息的除權息或公布日期。 |
| `nextDistributionKnownAt` | 是 | `string | null (date-time)` | 是 |  | 服務確認下一次配息資訊的時間點。 |
| `nextDistributionProvider` | 是 | `string | null` | 是 |  | 下一次配息資訊的公開供應者。 |
| `nextDistributionSourceUrls` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：支持下一次配息資訊的公開來源網址。 | 支持下一次配息資訊的公開來源網址。 |
| `nextDistributionStatus` | 是 | `string | null` | 是 |  | 下一次配息資訊的可用性狀態。 |
| `nextDistributionMissingReason` | 是 | `string | null` | 是 |  | 下一次配息資訊缺漏的具體原因。 |
| `distributionsWithinFiveSessions` | 是 | `integer | null (int32)` | 是 |  | 五個交易日內是否有配息或除權息事件。 |
| `distributionsWithinTwentySessions` | 是 | `integer | null (int32)` | 是 |  | 二十個交易日內是否有配息或除權息事件。 |
| `treasuryRateContext` | 是 | `TreasuryRateContext | null` | 是 |  | 公債利率曲線的完整性、來源與新鮮度上下文。 |
| `normalizedBias` | 是 | `NormalizedBiasEvidence | null` | 是 |  | 套用波動度調整後的價格乖離值。 |
| `shortNormalizedBias` | 是 | `NormalizedBiasEvidence | null` | 是 |  | 短期時間框架的波動度正規化乖離證據。 |
| `swingDownsideRisk` | 是 | `integer | null (int32)` | 是 |  | 波段策略判定的下行風險。 |
| `swingEvidenceConfidence` | 是 | `integer | null (int32)` | 是 |  | 波段證據的信心分數。 |
| `swingRiskCoverage` | 是 | `number | null (double)` | 是 |  | 波段風險證據的覆蓋比例。 |
| `swingCandidateAction` | 是 | `string | null` | 是 |  | 波段時間框架建議採用的候選動作。 |
| `nextExDividendDate` | 是 | `string | null (date)` | 是 |  | 下一次除息日期。 |
| `nextExRightsDate` | 是 | `string | null (date)` | 是 |  | 下一次除權日期。 |
| `nextCashPaymentDate` | 是 | `string | null (date)` | 是 |  | 下一次現金股利預計發放日期。 |
| `nextStockPaymentDate` | 是 | `string | null (date)` | 是 |  | 下一次股票股利預計發放日期。 |

### `AssetProfile`

標的分類、商品型態與幣別辨識結果，以及其可用性與缺漏原因。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `assetClass` | 是 | `string | null` | 是 |  | 資產分類，例如股票、基金、存款或 ETF。 |
| `assetClassSource` | 是 | `string | null` | 是 |  | 資產分類判定使用的公開來源。 |
| `assetClassComplete` | 是 | `boolean` | 否 |  | 資產分類是否有足夠公開依據。 |
| `instrumentKind` | 是 | `string | null` | 是 |  | 商品型態，例如 ETF、股票或債券。 |
| `instrumentKindSource` | 是 | `string | null` | 是 |  | 商品型態判定使用的公開來源。 |
| `instrumentKindComplete` | 是 | `boolean` | 否 |  | 商品型態是否有足夠公開依據。 |
| `stockStyle` | 是 | `string | null` | 是 |  | 股票風格分類。 |
| `stockStyleSource` | 是 | `string | null` | 是 |  | 股票風格判定使用的公開來源。 |
| `stockStyleComplete` | 是 | `boolean` | 否 |  | 股票風格是否有足夠公開依據。 |
| `bondTerm` | 是 | `string | null` | 是 |  | 債券期限分類。 |
| `bondTermSource` | 是 | `string | null` | 是 |  | 債券期限判定使用的公開來源。 |
| `bondTermComplete` | 是 | `boolean` | 否 |  | 債券期限分類是否有足夠公開依據。 |
| `quoteCurrency` | 是 | `string | null` | 是 |  | 匯率或標的報價的計價幣別。 |
| `quoteCurrencySource` | 是 | `string | null` | 是 |  | 報價幣別判定使用的公開來源。 |
| `quoteCurrencyComplete` | 是 | `boolean` | 否 |  | 報價幣別是否有足夠公開依據。 |
| `underlyingCurrency` | 是 | `string | null` | 是 |  | 標的底層資產的幣別。 |
| `underlyingCurrencySource` | 是 | `string | null` | 是 |  | 底層幣別判定使用的公開來源。 |
| `underlyingCurrencyComplete` | 是 | `boolean` | 否 |  | 底層資產幣別是否有足夠公開依據。 |
| `currencyDataComplete` | 是 | `boolean` | 否 |  | 所有必要幣別辨識是否已完成。 |
| `profileComplete` | 是 | `boolean` | 否 |  | 標的分類與幣別設定是否已完整辨識。 |
| `missingReasons` | 是 | `array of string` | 否 | items: string<br>items 說明: 陣列中的單一元素：多個必要內容無法取得的原因清單。 | 多個必要內容無法取得的原因清單。 |

### `SettingsClassification`

資產類別設定頁的有效分類投影。它刻意保留最新快照殖利率與 MID fallback 語意，不能被 strict AssetProfile 取代。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `effectiveAssetClass` | 是 | `string` | 否 |  | 與資產類別設定頁相同規則算出的有效資產類別。 |
| `assetClassSource` | 是 | `string` | 否 | enum: `RULE`, `OVERRIDE` | 有效資產類別的來源；RULE 是設定頁規則計算，OVERRIDE 是使用者指定覆寫。 |
| `effectiveStockStyle` | 是 | `string | null` | 是 |  | effectiveAssetClass=STOCK 時，與設定頁相同的有效股票風格。 |
| `stockStyleSource` | 是 | `string | null` | 是 | enum: `RULE`, `OVERRIDE`, null | 有效股票風格的來源；RULE 是設定頁規則計算，OVERRIDE 是使用者指定覆寫，不適用時為 null。 |
| `effectiveBondTerm` | 是 | `string | null` | 是 |  | effectiveAssetClass=BOND 時，與設定頁相同的有效債券期別。 |
| `bondTermSource` | 是 | `string | null` | 是 | enum: `RULE`, `OVERRIDE`, null | 有效債券期別的來源；RULE 是設定頁規則計算，OVERRIDE 是使用者指定覆寫，不適用時為 null。 |
| `assetClassOverride` | 是 | `string | null` | 是 |  | 使用者保存的資產類別覆寫；沒有覆寫時為 null。 |
| `stockStyleOverride` | 是 | `string | null` | 是 |  | 使用者保存的股票風格覆寫；沒有覆寫時為 null。 |
| `bondTermOverride` | 是 | `string | null` | 是 |  | 使用者保存的債券期別覆寫；沒有覆寫時為 null。 |

### `EvidenceGroup`

同一決策面向的證據元件集合及各投資期間覆蓋度。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `group` | 是 | `string | null` | 是 |  | 證據元件所屬的決策群組名稱。 |
| `components` | 是 | `array of EvidenceComponent` | 否 | items: EvidenceComponent<br>items 說明: 陣列中的單一元素：證據群組中可逐項檢查的元件清單。 | 證據群組中可逐項檢查的元件清單。 |
| `shortCoverage` | 是 | `number (double)` | 否 |  | 短期判斷所需證據的涵蓋比例。 |
| `swingCoverage` | 是 | `number (double)` | 否 |  | 波段判斷所需證據的涵蓋比例。 |
| `mediumCoverage` | 是 | `number (double)` | 否 |  | 中期判斷所需證據的涵蓋比例。 |
| `shortAvailable` | 是 | `boolean` | 否 |  | 短期判斷的必要證據是否可用。 |
| `swingAvailable` | 是 | `boolean` | 否 |  | 波段判斷的必要證據是否可用。 |
| `mediumAvailable` | 是 | `boolean` | 否 |  | 中期判斷的必要證據是否可用。 |
| `shortFresh` | 是 | `boolean` | 否 |  | 短期判斷的必要證據是否仍新鮮。 |
| `swingFresh` | 是 | `boolean` | 否 |  | 波段判斷的必要證據是否仍新鮮。 |
| `mediumFresh` | 是 | `boolean` | 否 |  | 中期判斷的必要證據是否仍新鮮。 |
| `sourceCount` | 是 | `integer (int32)` | 否 |  | 此判斷採用的公開來源數量。 |
| `participates` | 是 | `boolean` | 否 |  | 此證據群組是否參與對應投資期間的判斷。 |

### `EvidenceComponent`

決策證據群組中的一項可用性、權重與來源元件。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `name` | 是 | `string | null` | 是 |  | 供顯示的名稱。 |
| `applicability` | 是 | `string | null` | 是 |  | 證據對該標的的適用性說明。 |
| `weight` | 是 | `number (double)` | 否 |  | ETF 公開成分標的的權重比例。 |
| `availableWeight` | 是 | `number (double)` | 否 |  | 可用證據累積的策略權重。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |
| `provider` | 是 | `string | null` | 是 |  | 提供數值或公開內容的供應者識別。 |
| `missingReason` | 是 | `string | null` | 是 |  | 單一內容無法取得的具體原因。 |

### `MarketFeatureEvidence`

九項 typed 市場數值特徵（IXIC_RET5／SOX_RET5／SPX_RET5／DJI_RET5／
INDEX_VOLUME_RATIO20／TW_INSTITUTIONAL_NET_TURNOVER／WTI_RET5／BRENT_RET5／GOLD_RET5）
的逐項揭露值。

**這些數值在 production 不進評分**：交易雷達走的是規則引擎的 baseline 路徑
（candidate 參數集未 promote），typed market feature 區塊整段不執行，故本 schema 的
任何欄位都不影響 `score`、`shortScore`、`swingScore` 與各軌 `action`。欄位保留是為了
provenance 可稽核（provider／asOfDate／availableAt／availabilityBasis／missingReason／
sourceUrl），不代表已被採計。

注意：這不等於「大盤數據不影響評分」——大盤趨勢另由盤勢 regime 因子以非零權重計入分數，
此處不進評分的是**本 schema 的這些數值**。

`status` 的 `DISCLOSURE_ONLY` 是 candidate／回測路徑的**去重**標記（該碼資訊已由盤勢因子
代表，`duplicateOf` 指出被去重到哪個因子），**不是** production 有效性的區別；同一個 code
在來源缺值時會是 `MISSING`，可得時是 `AVAILABLE`，三者在 production 一律同樣不計分。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `code` | 是 | `string | null` | 是 |  | 來源目錄或證據項目的代碼。 |
| `value` | 是 | `number | null` | 是 |  | 該市場特徵的觀測值。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |
| `availableAt` | 是 | `string | null (date-time)` | 是 |  | 該內容可被系統使用或首次觀測的時間點。 |
| `availabilityBasis` | 是 | `string | null` | 是 |  | 判斷內容可用性的來源依據。 |
| `provider` | 是 | `string | null` | 是 |  | 提供數值或公開內容的供應者識別。 |
| `sourceUrl` | 是 | `string | null` | 是 |  | 此筆證據的單一公開來源網址。 |
| `profileApplicability` | 是 | `string | null` | 是 |  | 標的分類對該市場特徵的適用性說明。 |
| `duplicateOf` | 是 | `string | null` | 是 |  | 若為重複項目，所對應的原始證據代碼。 |
| `status` | 是 | `string | null` | 是 |  | 來源取得、計算或市場狀態。 |
| `missingReason` | 是 | `string | null` | 是 |  | 單一內容無法取得的具體原因。 |

### `NormalizedBiasEvidence`

以波動度下限正規化的乖離證據與計算來源。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `enabled` | 是 | `boolean` | 否 |  | 此計算或證據機制是否已啟用。 |
| `rawBiasRatio` | 是 | `number | null` | 是 |  | 未正規化的價格乖離比例。 |
| `rawSigmaRatio` | 是 | `number | null` | 是 |  | 未套用下限的波動度比例。 |
| `sigmaFloorRatio` | 是 | `number | null` | 是 |  | 正規化計算使用的最小波動度比例。 |
| `effectiveSigmaRatio` | 是 | `number | null` | 是 |  | 套用波動度下限後實際使用的比例。 |
| `normalizedBias` | 是 | `number | null` | 是 |  | 套用波動度調整後的價格乖離值。 |
| `asOfDate` | 是 | `string | null (date)` | 是 |  | 此數值、來源或市場觀測所對應的日期。 |
| `volatilityFallback` | 是 | `boolean` | 否 |  | 波動度不足時是否使用替代處理。 |
| `floorApplied` | 是 | `boolean` | 否 |  | 波動度下限是否已套用。 |
| `reason` | 是 | `string | null` | 是 |  | 計算、退回或缺漏的具體原因。 |

### `TreasuryRateContext`

決策時點可得的美國公債殖利率曲線批次（含 tenor、值、曲線日與完整 provenance）。
三段語意不同，不得合併理解：

1. **殖利率數值不計入評分**：candidate 權重未 promote，baseline 為字面 `0.0`，
   加權累加器連分母都不動，故對 `score` 與 `action` 的影響嚴格為零，不是「權重很小」。
2. **曲線資料是否齊備會影響證據閘門**：債券標的的 `ASSET_SPECIFIC` 群一律建立
   `bond_rate` component，其 applicability 隨本批次的可得性在 AVAILABLE／STALE／MISSING
   之間變動；該群覆蓋率未達門檻時，債券的買進動作會被降級為候選揭露。
3. **殖利率風險單位尚未 promote**：production 只提供 context，不產出量化 riskUnit，
   故債券的減碼／出場動作一律降級為候選揭露。

因此不得把本 schema 標成「不影響決策」或「僅供參考」。非債券標的與曲線批次不完整的
債券標的，`RadarEvidence.treasuryRateContext` 皆為 `null`；後者仍會在
`ASSET_SPECIFIC` 留下 `bond_rate` 的缺漏原因。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `batchId` | 是 | `integer (int64)` | 否 |  | 公債曲線批次的穩定識別碼。 |
| `complete` | 是 | `boolean` | 否 |  | 本次曲線、快照或處理結果是否完整。 |
| `tenor` | 是 | `string` | 否 |  | 公債利率曲線的到期期限。 |
| `value` | 是 | `number` | 否 |  | 指定期限的公債殖利率。 |
| `curveDate` | 是 | `string (date)` | 否 |  | 利率曲線所屬的日期。 |
| `provider` | 是 | `string` | 否 |  | 提供數值或公開內容的供應者識別。 |
| `sourceManifest` | 是 | `object` | 否 |  | 公債曲線使用的公開來源清單或版本。 |
| `availableAt` | 是 | `string (date-time)` | 否 |  | 該內容可被系統使用或首次觀測的時間點。 |
| `availabilityBasis` | 是 | `string` | 否 |  | 判斷內容可用性的來源依據。 |
| `fetchedAt` | 是 | `string (date-time)` | 否 |  | 服務擷取或寫入此內容的時間點。 |
| `lagDays` | 是 | `integer (int64)` | 否 |  | 來源日期與目前日期相差的天數。 |
| `staleReason` | 是 | `string | null` | 是 |  | 內容被判定為過期的具體原因。 |

### `PublicInformationItem`

可公開引用的市場、公司或產業資訊項目。

| 欄位 | 必填 | 型別 | Nullable | Enum／限制 | 說明 |
| --- | --- | --- | --- | --- | --- |
| `region` | 是 | `string | null` | 是 |  | 地區的業務屬性。 |
| `title` | 是 | `string | null` | 是 |  | 供使用者閱讀的項目標題。 |
| `source` | 是 | `string | null` | 是 |  | 公開資訊的發布來源名稱。 |
| `url` | 是 | `string | null` | 是 |  | 可公開存取的引用網址。 |
| `publishedAt` | 是 | `string | null` | 是 |  | 來源原始時間字串，不強制 ISO format。 |
| `summary` | 是 | `string | null` | 是 |  | 公開資訊內容的精簡摘要。 |
| `knownAt` | 是 | `string | null (date-time)` | 是 |  | 服務首次確認此公開資訊的時間點。 |
| `availabilityBasis` | 是 | `string | null` | 是 |  | 判斷內容可用性的來源依據。 |
