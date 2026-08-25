# 9090 Port API Swagger 清單

本文件列出 `api-gateway` 在 9090 port 對 Docker host 與允許的 Tailscale 私網開放的全部 API。完整、機器可讀的 OpenAPI 3.1 契約請見 [docker-external-api.yaml](./docker-external-api.yaml)。

| 項目 | 值 |
| --- | --- |
| OpenAPI 版本 | `3.1.0` |
| 本機入口 | `http://127.0.0.1:9090` |
| Tailscale 私網入口 | `https://mac-mini-2.tailccc7be.ts.net:9090` |
| 對外路徑數 | 9 條精確路徑：8 個 `GET`、1 個 `POST` |
| Swagger UI／OpenAPI HTTP endpoint | 未掛載在 9090；請使用本 Markdown 或同目錄 YAML |

## 存取與安全邊界

- OpenAPI 的應用層全域 `security` 為空，但服務並非公網 API：本機只綁定 loopback，遠端須通過允許的 Tailscale identity；禁止 Funnel 與公網 listener。
- Gateway 僅接受下表的精確 path。未知 path、子 path、尾斜線與 matrix 變體回 `404 Not Found`。
- 8 個 `GET` path 的其他 method 回 `405` 並附 `Allow: GET`；重新搜尋 path 僅接受 `POST`，其他 method 回 `405` 並附 `Allow: POST`。
- 除各 route 另有說明外，upstream 無法連線可能回 `502`，逾時可能回 `504`。

## API 總覽

| # | Method | Path | operationId | 用途 | 成功回應 |
| ---: | --- | --- | --- | --- | --- |
| 1 | `GET` | `/api/quotes` | `listLatestQuotes` | 列出最新快取報價與公開市場資料 | `200 LatestQuote[]` |
| 2 | `GET` | `/api/quotes/one` | `getLatestQuote` | 查詢單一標的的最新快取報價與公開市場資料 | `200 LatestQuote`、`204` |
| 3 | `GET` | `/api/public/market-index` | `getPublicMarketIndex` | 取得大盤日線或當日分時圖表 | `200 MarketIndexResponse` |
| 4 | `GET` | `/api/assets/latest` | `getLatestAssets` | 取得 configured admin 的最新完整資產 | `200 LatestAssetsResponse` |
| 5 | `GET` | `/api/public/exchange-rate/usd-twd` | `getPublicUsdTwd` | 取得 USD/TWD 即期與近一年歷史 | `200 UsdTwdResponse` |
| 6 | `POST` | `/api/public/crawler-data/rescan` | `triggerPublicCrawlerRescan` | 觸發爬蟲重新搜尋 | `200 CrawlerRescanResponse` |
| 7 | `GET` | `/api/public/market-analysis/today` | `getPublicMarketAnalysisToday` | 取得最近一筆今日股市分析 | `200 MarketAnalysisResponse` |
| 8 | `GET` | `/api/public/portfolio-advice/latest` | `getPublicPortfolioAdviceLatest` | 取得 configured admin 的最新資產配置建議 | `200 PortfolioAdviceResponse` |
| 9 | `GET` | `/api/public/trading-radar/today` | `getPublicTradingRadarToday` | 取得 configured admin 的今日交易雷達 | `200 TradingRadarResponse` |

## 路由說明

### 1. `GET /api/quotes`

可選 `market=台股|美股|英股`；省略時回三個市場。raw 最新報價只讀 Redis，不會觸發抓價或資料庫 fallback。每筆成功 raw quote 固定附上 `marketData` 的 chart、quoteDetail、ETF constituents、dividends 四個公開 child，任一 child 不可用時採 typed fallback，不能遮蔽有效 raw quote。

台股 `marketData.quoteDetail` 為富邦十秒 LIVE round 預先寫入的五檔 snapshot：先讀 dedicated Redis、miss 時讀 canonical PostgreSQL，沒有 request-time Yahoo、富邦或行情 worker 外呼。

範例：`GET http://127.0.0.1:9090/api/quotes?market=台股`

### 2. `GET /api/quotes/one`

必填 query：`code`、`market=台股|美股|英股`。price raw Redis key miss、讀取失敗或 payload 無法解析時回 `204`，不查資料庫也不觸發 quote producer。raw quote 命中時，回應固定含 `marketData`。

其中台股 `marketData.quoteDetail` 是純讀五檔：依序讀 dedicated Redis 與 PostgreSQL canonical snapshot，絕不在 request-time 呼叫 Yahoo、富邦或行情 worker；可用資料的 `source` 固定為 `FUBON_BOOKS`。支援但暫無資料或下游失敗時，固定為 `available=false`、`source=null`、`marketStatus=UNKNOWN`、`levels=[]`，訊息為「暫時無法取得行情五檔」。非台股或 `0000` 為 typed unsupported。

範例：`GET http://127.0.0.1:9090/api/quotes/one?code=00850&market=台股`

### 3. `GET /api/public/market-index`

可選 `market=TWSE|TPEX|DJI|SPX|IXIC|SOX|FTSE|DAX|KOSPI|N225`（預設 `TWSE`）與 `range=d|1m|3m|6m|1y|2y|5y|10y`（預設 `1y`）。`range=d` 為當日分時，其他為日線。非法 market/range 回 `400`。

範例：`GET http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m`

### 4. `GET /api/assets/latest`

無 query。回 configured admin 的最新完整資產；不接受 `ownerId`、email、cookie 或 `X-User-*` 作為 tenant selector。成功時 `snapshot.id` 必須等於 `liveAssets.snapshotId`；尚無快照回 `404`。

範例：`GET http://127.0.0.1:9090/api/assets/latest`

### 5. `GET /api/public/exchange-rate/usd-twd`

無 query。回 USD/TWD 即期與以 Asia/Taipei 當日往回一年（含首尾日）的遞增、不重複歷史資料。沒有歷史或即期資料時回 `404`。

範例：`GET http://127.0.0.1:9090/api/public/exchange-rate/usd-twd`

### 6. `POST /api/public/crawler-data/rescan`

無 query、無 body。唯一具有外部抓取副作用的 route；Business 層使用 30 秒全域 Redis cooldown。cooldown 期間仍回 `200`，但 body 的 `status=COOLDOWN`，不會真的重新抓取，因此呼叫端不可只看 HTTP status。

### 7. `GET /api/public/market-analysis/today`

無 query。只讀最近一筆今日分析，不觸發 LLM 產生；尚無資料時仍回 `200`，body 的 `status=NONE`，也可能為 `PROCESSING` 或 `FAILED`。

範例：`GET http://127.0.0.1:9090/api/public/market-analysis/today`

### 8. `GET /api/public/portfolio-advice/latest`

無 query。只讀 configured admin 的最新資產配置建議，不觸發產生流程；不接受任何 tenant selector。尚無建議時 `200` 加 `status=NONE`，也可能為 `PROCESSING` 或 `FAILED`。

範例：`GET http://127.0.0.1:9090/api/public/portfolio-advice/latest`

### 9. `GET /api/public/trading-radar/today`

無 query。只讀 configured admin 的當日交易雷達組裝結果；不會 refresh、export、發通知，也不寫入 snapshot、Redis 或資料庫。因可揭露 configured admin 的持倉、觀察清單與決策證據，只限 loopback 或允許的 Tailscale 私網。

範例：`GET http://127.0.0.1:9090/api/public/trading-radar/today`

## 使用資料時的狀態判斷

HTTP `200` 只代表路由和當次 response 成功，不代表資料必然新鮮或可作為交易依據。至少請再檢查下列欄位：

| API | 必查欄位 |
| --- | --- |
| 報價 | `quoteStatus`、`tradingDate`、`updatedAt`；台股五檔另查 `marketData.quoteDetail.available`、`source`、`sourceTime`、`marketStatus` 與 `levels`。 |
| 最新資產 | `targetPriceComplete`、`valuationSource`。 |
| USD/TWD | `liveUpdateStatus`、`spot.quoteStatus`。 |
| 市場分析／資產配置建議 | body 的 `status`，不可只看 HTTP `200`。 |
| 交易雷達 | `generatedAt` 與每一項決策證據。 |
