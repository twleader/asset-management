# [t372] 9090 單檔與快取清單報價 API 補齊股票分析四個頁籤的市場資料

**對應 Requirements:** Requirement 108（兩支既有 9090 quote API 保留原本 19 個市場報價欄位，為每檔增加四頁籤市場投影；不得回傳任何個人資產資料）
**前置任務:** t348、t359 與既有 9090/OpenAPI 契約
**Liquibase changeset:** 無

## 背景

`GET /api/quotes` 現在從 `external-materials-service` 的 `PriceCacheReader` 直接回傳所有 Redis `price:index:{market}` 中的 `LatestQuote`；`GET /api/quotes/one` 回傳同一 DTO 的單筆資料，cache miss 為 HTTP 204。實測每筆只有 19 個欄位：`stockCode`、`stockName`、`market`、`price`、`previousClose`、`priceChange`、`changePercent`、`buyPrice`、`sellPrice`、`openPrice`、`highPrice`、`lowPrice`、`volume`、`tradingDate`、`updatedAt`、`closed`、`source`、`quoteStatus`、`premiumDiscountPct`。

使用者需要這兩支外部 API 的每一檔都能帶出股票分析畫面的完整市場資料：

1. 走勢圖：對齊的日線與日 K／週 K、MA、KD/J、MACD、RSI、乖離率、威廉值、latest 與分時 ticks。
2. 行情五檔：台股富邦十秒 cached snapshot 的摘要、內外盤與五檔委買賣。
3. 持股明細：僅 ETF 發行人公開成分股，不是使用者的持股。
4. 股利歷史：十年資料及四個日期欄位。

`StockAnalysisDialog` 已有登入後的 BFF path 與既有 DTO。Requirement 109／Task 373 與 Requirement 114／Task 379 取代此任務原本 request-time Yahoo 的來源決策：完整 `FUBON_BOOKS` 是 primary，完整 `YAHOO_TW` 是 producer-only fallback；兩者先落 canonical DB/Redis 後供 pure read，絕不在 request-time 外呼或混拼。Requirement 108 仍是使用者明確新增的、受限的公開巢狀投影例外：它不得用於估值、損益、下單、警示、SSE 或個人資產判斷。

## 要做什麼

- [ ] **372.1 建立 BFF 公開市場資料聚合，不改既有路徑。** 在 `bff/src/main/java/com/steven/assets/bff/` 建立清楚命名的 public quote aggregation controller/service/DTO（名稱可等價），精確處理 `GET /api/quotes` 與 `GET /api/quotes/one`；不得用 wildcard。Nginx 的兩個既有 exact location 改 proxy 至 BFF。新增兩顆明確 qualifier 的 container-only WebClient：`publicQuoteRawClient` 只呼叫 `external-materials-service:8080/api/quotes{,/one}`，`publicMarketDataBusinessClient` 只呼叫本任務列出的 market-data read endpoint；兩者必須共用既有 DNS／codec 配置，但**不得**掛 `tenantHeaderFilter`、不得讀 Reactor 身分、不得加 `X-User-*`。後者只由 `PublicQuoteMarketDataService` 作 outbound 呼叫，不能有供 browser 轉送這兩個 internal bridge 的 BFF inbound route。BFF 不直讀 Redis、不直查 DB、不直連 Yahoo，外部 IO 仍只能經 external-materials-service。

- [ ] **372.2 保留原報價 JSON 形狀。** 定義 immutable public response DTO，top-level 逐欄保留上述 19 個 `LatestQuote` 欄位及其 Java/JSON 型別與 nullable 語意，再新增唯一 `marketData` property。不得將舊欄位置入 `quote`／`raw` 巢狀物件、不得改名或移除欄位。list 仍是 array，row 順序與 external raw list 相同；`market` 篩選、raw list 空陣列、single raw miss 204 皆不得改變。raw external 非 2xx／transport 必須回公開安全的既有 502/504，不得原樣暴露內部 body。

- [ ] **372.3 精確定義並驗證日期窗口。** 兩個 endpoint 都新增選填 `start`、`end`（ISO `yyyy-MM-dd`）。兩者皆缺省時以 injected `Clock` 的 Asia/Taipei today 產生 `[today.minusYears(1), today]`；兩者必須同時提供。`start > end`、未來日、早於 today.minusYears(10) 或格式不合法回 400；不得 silently clamp。將完成驗證後的 start/end 回寫在 `marketData.chart.requestedStart/requestedEnd`，list 的所有 row 必須使用同一個範圍。

- [ ] **372.4 `marketData.chart` 與 popup 同源，且 typed fallback 可被凍結。** 從 `StockAnalysisChartBffController` 抽出可注入的共用 service，讓原本 `/api/bff/stock-analysis/chart-series` 與新 public aggregation 共用同一個「history + indicator」日期聯集／對齊演算法，避免 controller-to-controller 呼叫或出現第二套 MA/KD 計算。public API 新增 immutable `ChartMarketData(status,message,requestedStart,requestedEnd,series,intraday)` 與 `IntradayMarketData(status,message,tradingDate,ticks)`；兩者 status 只可為 `AVAILABLE`／`NO_DATA`／`UNAVAILABLE`，message nullable 且只能是一般化安全文案，`series`／`intraday`／`ticks` 永不為 null。chart 兩來源皆成功但無資料是 `NO_DATA`，都失敗才是 `UNAVAILABLE`，一側成功則保留既有 partial alignment 並為 `AVAILABLE`；前兩種都回 canonical empty `ChartSeriesDto`（所有 list 空、值／latest null、daily／weekly empty frame）。

  `chart.intraday` 必須從 raw quote `tradingDate` 解析 ISO date，且該日期在 validated `[start,end]` 時才讀取；缺失／非法／窗口外不呼叫下游，回 `NO_DATA,tradingDate=null,ticks=[]`。有效 target 的 external `readStatus=DATA`（非空且各列合法）為 `AVAILABLE`，`EMPTY` 為 `NO_DATA`，`UNAVAILABLE`／`MALFORMED` 或 BFF→business timeout／transport／schema 為 `UNAVAILABLE`；非 AVAILABLE 統一 `ticks=[]`。新增 external-materials exact `GET /internal/intraday-ticks-readonly?code=&market=&date=`（date required），新增 immutable pure-read `TickReadOutcome`（或等價）而**不改**既有 `tickStore.getTicks` silent-skip compatibility：`DATA`、`EMPTY`、`UNAVAILABLE`（Redis exception）、`MALFORMED`（任一 Redis list value 的 JSON／time／price 不合法）；非 DATA 不帶部分 ticks 或內部錯誤細節。external controller 回 `{tradingDate,readStatus,ticks}`，且 outcome reader 只能讀 tick store，不得用 `MarketClock` 選日期、`stockSource.findMaxTradingDate`、`tickRefresher`、外部 HTTP、Redis／DB write 或 publish。新增 backend exact `GET /internal/public-market-data/intraday-ticks-readonly?code=&market=&date=`（同樣 date required）只 proxy 這條 external bridge；不得重用 `HistoricalDataService.fetchIntradaySession`／既有 `/intraday-ticks`，因 Task 409 的 non-public session route 雖 cache-only ticks 仍可在今日 reference-cache miss bounded 讀 TWSE MIS evidence。backend path 刻意不使用 `/api/market-data/**`，避免 `MarketDataBffRoutes`／frontend 將它 proxy 給已登入瀏覽器。走勢資料無、分時無或單一 downstream failure 時，只對這個 child 回上述 shape，不可清空 top-level quote 或其他 child；不得呼叫 `backfill-stock`／任何寫入 endpoint。

- [ ] **372.5 `marketData.quoteDetail` 是受限的市場 snapshot。** 以 existing business `GET /api/market-data/quote-detail` 取得完整 `QuoteDetailDto.Response`，含 `supported`、`available`、source/sourceTime/fetchedAt、12 格摘要、內外盤、五檔 `levels`、bid/ask 小計。依 Requirement 109／Task 373 與 Requirement 114／Task 379，它是完整 single-source `FUBON_BOOKS` primary／`YAHOO_TW` fallback 的 Redis→DB pure-read snapshot，查詢不得寫 Redis/DB/SSE、不得 request-time 外呼、不得用於估值或個人資產。`market != "台股"` 或 `code == "0000"` 必須在 external boundary 前回 `supported=false, available=false`，測試應證明零上游 HTTP；不可把 generic quote `buyPrice/sellPrice` 偽裝成五檔。

- [ ] **372.6 `marketData.etfConstituents` 與 `marketData.dividends`。** ETF 部分重用 `EtfHoldingsDto` 與 `EtfHoldingsAggregator`，外層資料名稱固定為 `etfConstituents`，保留 supported/source/asOfDate/message/前十加其它的公開成分股；不得新增個人 `holdings` 或帶入使用者股票列。重用 DTO 內既有 `holdings[]` 子陣列名稱不可改動，且每一列只代表 ETF 發行人公開成分股，絕不可映射為個人持股。股利部分新增 backend container-only exact `GET /internal/public-market-data/dividends-readonly-result?code=&market=&years=`，保留既有 code／market validation、限制 `years=1..10`，並**直接回完整** `DividendHistoryService.findFromDbReadOnly` 的 `DividendHistoryResult`（source/message/rows 及 year/cash/stock/ex-dividend/ex-rights/cash-payment/stock-payment/fill-days/previous-close/yield）；BFF 固定傳 `years=10`。backend path 刻意不使用 `/api/market-data/**`，不得有 BFF route/controller 或 frontend proxy，避免已登入瀏覽器可直取。既有 `/api/market-data/dividends-readonly` 必須仍回 bare `List<DividendRow>`，不得改動任何既有 consumer；絕不能走可 cold-sync 的 `/api/market-data/dividends`，DB 無資料只回既有空 envelope，禁止 projection／sync／寫入。

- [ ] **372.7 部分可用、清單並行與 global deadline。** 每一個 child（chart-series、intraday、quoteDetail、etfConstituents、dividends）有明確 timeout 和不洩漏內部細節的 typed fallback；固定四個 `marketData` property 永遠都要出現。每列五個 child 同時開始，無 retry；production timeout 固定為 raw quote 3 秒、chart-series 7 秒、quoteDetail 2 秒（pure read；BFF child 與 business `MarketDataService.getQuoteDetail()` 對 external `/internal/quote-detail` 都以 2 秒界線把卡住的 peer 正規化為這個 child 的 typed unavailable）、ETF 5 秒、dividends 3 秒、intraday 2 秒，列級 deadline 7 秒。list 使用 `flatMapSequential` 或等價做最多 8 檔並行聚合並保持 raw order；不可無限制 fan-out。從 controller 收到 request 起算 50 秒 global deadline，deadline 到時取消未完成 IO、把每筆 detail IO 前預建的 fallback `marketData` 合併進未完成或未開始 row，再輸出所有 raw rows；不得用 timeout 導致只回已完成 prefix。properties 可為測試縮短，但 production default 不得放寬；50 秒保留至少 10 秒給 Nginx `proxy_read_timeout 60s`。raw quote 成功時，child failure 或 deadline 只能局部降級。

- [ ] **372.8 安全與個人資料邊界。** `SecurityConfig` 只新增 `/api/quotes`、`/api/quotes/one` 兩個 exact GET 到既有 public permitAll 清單，POST/PUT/PATCH/DELETE 和 descendants 仍不可匿名。兩條新 backend bridge 只可為 `GET /internal/public-market-data/dividends-readonly-result` 與 `GET /internal/public-market-data/intraday-ticks-readonly` 兩個 exact mapping，不得用 `/internal/public-market-data/**` wildcard；它們刻意不得加入 `AdminGateInterceptor.addPathPatterns`，因只供 `PublicQuoteMarketDataService` 的 no-tenant outbound `publicMarketDataBusinessClient` 在 asset-net 內讀取公開市場資料。它們絕不進 BFF public permitAll、`MarketDataBffRoutes`、BFF controller、Nginx、Tailscale、frontend 或 gateway OpenAPI manifest；以已登入 BFF/frontend fixture 證明無 direct route 可取到 bridge body。API response/DTO/log/test fixture 不得讀或含 configured admin、`AssetSnapshot`、`StockHolding`、user/account/broker、costPrice/investmentCost/currentValue、交易、損益、資產配置或建議。ETF 成分股的公開 `shares` 只可存在於 `marketData.etfConstituents` 子樹，必須在測試中與個人資料作 path-sensitive 區分。

- [ ] **372.9 Gateway、Tailscale、frontend deny、架構文件與 OpenAPI 同步。** `api-gateway/nginx.conf` 兩條 quote location 維持 `GET` guard、`Allow: GET`、exact path 與 `$request_uri`，僅 upstream 改 BFF；Tailscale `SERVE_PATHS`/expected handlers 的九路集合不增不減，preflight 加驗 response 含 `marketData.chart/quoteDetail/etfConstituents/dividends` 且 top-level identity/19 欄仍在。`frontend/nginx.conf` 的兩個 exact/matrix 404 不放寬。同步 `CLAUDE.md`、`spec/steering/structure.md`、`spec/steering/tech.md` 與本 design 的**現況** topology／allowlist：quotes 是 gateway → BFF public aggregation → raw external＋business readonly；Requirement 109 的富邦十秒 cached snapshot 是唯一 nested public exception，明載 no-tenant/no-personal-data／no request-time upstream。不得留下現況「gateway／Tailscale 直送 external-materials quote」或「`/api/quotes*` 一律禁止五檔」的敘述；歷史 Task 328 描述須標明為歷史，不改寫當時事實。更新 `docs/openapi/docker-external-api.yaml`：info version 維持 `1.4.0`，兩個 operation 加 start/end parameters、完整 200 schema/example、400/204/502/504，新增固定 typed component schemas（不得用 `additionalProperties: true` 逃避），尤其釘 `ChartMarketData`／`IntradayMarketData` 的 status enum、message nullability、requested range、target date、canonical empty list；清楚說明 no-personal-data 與富邦 cached pure-read 限制。更新 `scripts/tests/docker-external-api-openapi-test.rb`，仍釘住九個 route/method manifest，另釘 `DetailedLatestQuote` 和四個 children 的 properties/required/nullability。

- [ ] **372.9a Gateway healthcheck 不得探測完整 quote payload。** `api-gateway` 的 Compose healthcheck 必須使用既有匿名、低負載的 `GET /api/public/market-index`；不得用完整 `/api/quotes` response 作 health probe，避免四頁籤市場資料的 payload／deadline 超出 healthcheck timeout。這只是在既有九條 9090 allowlist 中改選 probe，**不是**新增第十條 path。

- [ ] **372.10 自動化與 Docker 驗收。** 新增 BFF／backend／external tests，至少驗：raw top-level 19 欄逐欄 relay（含 raw record property order）、raw one miss 204、list 空陣列與排序、有效 `start/end` 與每種 400、四 children 固定存在、`ChartMarketData`／`IntradayMarketData` 各種 `AVAILABLE`／`NO_DATA`／`UNAVAILABLE`、各 child 單獨失敗仍回其他資料、readonly-result 保留 source/message 且 bare readonly 契約不變、external readonly ticks 的 `DATA`／`EMPTY`／`UNAVAILABLE`／`MALFORMED` outcome 與空／不完整 cache 零 refresh、已登入 frontend/BFF 無 internal bridge route、非台股五檔零外呼、8 檔上限／五 child 平行／global deadline fallback／無 retry、專用 client 零 tenant identity，以及沒有個人資產 path。執行既有 OpenAPI/Tailscale tests、各服務 tests 和 `git diff --check`。依 `/run-stack` 從本 feature worktree rebuild/recreate **external-materials-service**、**business-services**、**bff**、**api-gateway**（不 rebuild 未改服務），等 healthy 後對 9090 實測：由 `market=台股` 找一筆非 `0000` code，驗 `/api/quotes/one` 與 `/api/quotes?market=台股` 的原 19 欄、四 children、ETF 子樹命名與 no-personal-data；驗 non-GET 405 及 `/one/`、unknown/descendant/matrix 404；重新 inspect image SHA 確認驗證中的容器未被其他 worktree 覆蓋。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
git diff --check
docker compose -p asset-management build external-materials-service business-services bff api-gateway
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services bff api-gateway
```

容器 healthy 後，以 Python 只讀呼叫 `http://127.0.0.1:9090/api/quotes?market=台股`，挑選 `stockCode != "0000"` 的 row；再呼叫同 code 的 `/api/quotes/one`。兩份 JSON 都必須保留 19 個既有 top-level key，且每筆都有 `marketData.chart`、`marketData.quoteDetail`、`marketData.etfConstituents`、`marketData.dividends`。驗證 `chart.status` 與 `chart.intraday.status` 屬固定 enum、其 non-AVAILABLE fallback 仍保留 required empty shape，`requestedStart/requestedEnd` 與 query 一致；驗證 `marketData` 不含 personal portfolio path，ETF 成分股只在 `etfConstituents`。另外直接在 container network 對 readonly dividend／ticks bridge 以空 fixture 驗證完整 envelope／零 refresh，確認它們不出現在 9090／Tailscale allowlist。對兩條 path 的 `POST` 應為 405/`Allow: GET`，對 trailing slash、descendant 和 matrix 變體應為 404。

## 完成報告

（實作者完成後回填：實際修改檔案、單元／契約／Docker／9090 驗證結果、容器 image SHA、以及與本任務的任何偏差及原因。）
