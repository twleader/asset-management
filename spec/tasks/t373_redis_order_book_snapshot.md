# [t373] 台股最佳五檔由十秒富邦行情持久化後唯讀提供

**對應 Requirements:** Requirement 109（同一富邦十秒 LIVE round 的完整五檔先存 PostgreSQL、再寫專用 Redis；所有查詢純讀）
**前置任務:** t370、t372；既有 `TwLiveQuoteDispatcher`、`FubonNormalizedQuoteClient`、`QuoteDetailDto.Response`
**Liquibase changeset:** `v1.114.0-stock-intraday-order-book.sql`

> **Current-state override — Requirement 114／Task 379：**本檔中任何 FUBON-only source、禁止 Yahoo producer fallback、null／zero paired level 可作可用 book、available source 固定 FUBON_BOOKS、header source constraint 固定 FUBON、或 `sourceUpdatedEpochMicros` 為 Redis ordering token 的規則，均已被取代。Task 379 是 source／complete-book validity／atomic canonical DB comparator／cache ordering 的唯一現行契約：selected code 只有缺 valid complete FUBON_BOOKS 時才由受限 fair-cursor Yahoo worker 補抓完整 YAHOO_TW snapshot；PostgreSQL 是唯一 source-aware comparator 並回傳 strictly increasing `canonicalRevision`，dedicated Redis payload/Lua 只以該 DB revision strict-newer fencing；缺 revision 的 legacy cache payload 一律 cache miss。consumer 先取 Redis candidate、再純讀 PostgreSQL revision，revision 不合則回完整 DB canonical snapshot，絕不在 request time 外呼或 repair cache。raw price、route、個資、估值／下單／警示與 generic-price side-effect 的限制仍不變。

## 背景

舊的行情五檔曾在 request time 解析 Yahoo。盤中這讓 `/api/quotes/one` 即使同一輪富邦行情已取得五檔，仍受另一個上游、timeout 與 HTML schema 影響。本任務只接受既有台股十秒富邦 LIVE batch 同一 raw quote response 的完整五檔：先寫可稽核的 PostgreSQL canonical snapshot，再寫獨立 Redis key。popup、business 與公開 9090 aggregation 只讀這兩個 sink；不得因讀取者請求重新抓 Yahoo、富邦或啟動任何行情 round。

「同時間 snapshot」的精確定義是：五個 level 與五檔摘要均取同一個 raw quote response，且五檔唯一 `sourceTime`／排序依據為該 response 的 `lastUpdated`（wire 名 `bookUpdatedAt`）。generic actual-price observation 仍以 `lastTrade.time`／`closeTime` 保持既有 freshness；兩個事件時間可以不同，**不得要求相等，也不得用其中之一覆寫或拼接另一條資料流**。

## 要做什麼

- [ ] **373.1 富邦 adapter wire。** `fubon-broker-service` 既有 token-protected `POST /internal/market-data/tw-quotes` 保留 actual-price contract，不連 DB／Redis。每個 SUCCESS item 可附 optional `orderBook`：

  ```text
  { bookUpdatedAt:string, averagePrice:string|null, turnoverYi:string|null,
    innerVolumeLots:integer|null, outerVolumeLots:integer|null,
    levels:[{level:1..5, bidPrice:string|null, bidVolumeLots:integer|null,
             askPrice:string|null, askVolumeLots:integer|null}] }
  ```

  `bookUpdatedAt` 是 raw `lastUpdated` 的 16 位 epoch-microseconds，轉 UTC ISO-8601 後不得未來、轉 Asia/Taipei 後必須等於 parent actual-price `tradingDate`。`bids[]`、`asks[]` 都至少五個 slot；每一個非空 side 必須是正 canonical decimal price（precision≤20、scale 0..10）加 0..Long.MAX_VALUE exact lots，空 slot 只能兩欄皆 null，五個非空 price 在同側不得重複。short array、半邊、非法 timestamp/數值、重複價格使整個 `orderBook` 缺省，但不得使合法 actual price 失敗。`averagePrice` 為 optional 正 decimal；`turnoverYi=total.tradeValue/100000000`（非負、scale 2 HALF_UP）；內／外盤量由 `total.tradeVolumeAtBid`／`total.tradeVolumeAtAsk` 取非負 exact lots、不乘 1,000；這些 optional summary 欄無效時各自 null。

- [ ] **373.2 Java mapping。** `FubonNormalizedQuoteClient` 對既有最多 40 檔 round-robin 的唯一富邦 call 同時回既有 `ProviderTimedPriceObservation` 與 `Map<code, QuoteDetailResult>`；book mapping 失敗不能改寫 actual-price outcome，絕不可送第二個富邦 request。只有完整 wire 產生 immutable `FUBON_BOOKS`：`stockCode/name/market="台股"` 來自 parent、`supported/available=true`、`source="FUBON_BOOKS"`、`message=null`、`sourceTime=bookUpdatedAt`、`fetchedAt=Java Clock.instant()`、`marketStatus="OPEN"`。`price/previousClose/open/high/low/volumeLots` 取同一 raw response 的 parent actual-price fields，`average/turnover/inner/outer/levels` 取 `orderBook`，`previousVolumeLots=null`；server 用 `BigDecimal` 算 `change`、`changePercent`、合法時 `amplitudePercent`、inner/outer 百分比（scale 2 HALF_UP，outer=100.00-inner）及兩側五格 totals（全 null→null）。actual trade timestamp 與 `bookUpdatedAt` 不等時仍接受，唯一 public `sourceTime` 與 DB source comparator timestamp 仍是 `bookUpdatedAt`；Task 379 的 Redis fence 則一律使用 DB-assigned `canonicalRevision`，不是 `bookUpdatedAt`。不得從 generic Redis、MIS、Yahoo、前一 snapshot 或稍後 request 補任何欄位。

- [ ] **373.3 隔離 writer。** Add `TwFubonOrderBookRoundWriter`（或同義具名元件）：一個 single-flight worker、無 waiting queue（例如一 worker + `SynchronousQueue`），只接收 valid Fubon snapshot map。`TwLiveQuoteDispatcher` 在既有 actual-price handling 後嘗試 submit；busy/rejected/DB/Redis exception 只丟棄這次 book。worker 不得共用 dispatcher `inFlight` guard、阻塞其完成、另起 poller、使用 Yahoo fallback、發第二個網路 request，或為非台股／`0000` 外呼；慢 DB／Redis 絕不可令下一輪 generic FUBON→MIS→Yahoo price refresh 成為 `IN_FLIGHT_SKIPPED`。

- [ ] **373.4 PostgreSQL canonical schema/transaction。** Register `v1.114.0-stock-intraday-order-book.sql` in master changelog. Create `stock_intraday_order_book` with `stock_code VARCHAR(20) NOT NULL`, `market VARCHAR(20) NOT NULL`, `trading_date DATE NOT NULL`, `source_updated_at TIMESTAMPTZ NOT NULL`, `fetched_at TIMESTAMPTZ NOT NULL`, `source VARCHAR(32) NOT NULL`, `market_status VARCHAR(16) NOT NULL`, required `actual_price/previous_close NUMERIC(20,10) NOT NULL`, optional `open_price/high_price/low_price/average_price/turnover_yi NUMERIC(20,10)`, optional `volume_lots/previous_volume_lots/inner_volume_lots/outer_volume_lots BIGINT`, and PK `(stock_code, market)`. **The source constraint, complete five-level validity, canonical revision, and DB comparator described below are superseded by Task 379; do not implement this older paragraph as a FUBON-only contract.** Create `stock_intraday_order_book_level` with composite FK to header (`ON DELETE CASCADE`), PK `(stock_code,market,level)`, `level SMALLINT NOT NULL CHECK 1..5`.

  The original FUBON-only persistence details in this paragraph are archived; current source eligibility, complete positive five-level validation, source comparator, canonical revision and cache fence must follow Task 379. The unchanged mechanics are one DB transaction for header/five levels/name, zero Redis mutation on DB failure, read-only/isolation-safe canonical reread against torn header/levels, schema regeneration after migration, and drift verification.

- [ ] **373.5 Redis。** Add `QuoteDetailCache` with only key `price:quote-detail:{market}:{code}`, TTL 24 hours, no membership index. **The payload ordering field and Lua comparator in this paragraph are superseded by Task 379: payload includes DB `canonicalRevision`; Lua accepts only strict-newer DB revision, and an envelope without one is a cache miss.** Only a DB APPLIED snapshot or canonical row read after stale conflict may enter cache; DB success plus Redis failure preserves DB and a later writer may repair from that canonical row. `find()` validates key identity and never repairs; malformed Redis is a miss. This feature must never write generic `price:{market}:{code}`, `price:index:*`, ticks, day-H/L or publish `price-update`.

- [ ] **373.6 pure-read endpoint/proxies。** Replace external `GET /internal/quote-detail?code=&market=` with `QuoteDetailCache.find → canonical PostgreSQL find → typed unavailable`. It makes no Yahoo/Fubon HTTP, dispatcher/worker/cache-repair call or DB/Redis write. At external boundary `market!="台股"` or `code=="0000"` returns `supported=false, available=false, levels=[]` before cache/DB/HTTP. Every supported-but-unavailable outcome—including external cache+DB miss and backend proxy transport/decode/5xx failure—must be `source=null`, `marketStatus="UNKNOWN"`, `levels=[]`, and generic message `暫時無法取得行情五檔`; it must never emit `YAHOO_TW`. Under Task 379 an available result has exactly `source="FUBON_BOOKS"` or `source="YAHOO_TW"`. `MarketDataService`, `MarketDataController`, `StockAnalysisBffRoutes` and `PublicQuoteMarketDataService` proxy the existing full `QuoteDetailDto.Response`; public quote-detail timeout is 2 seconds. Do not add/change a 9090 path: raw 19 fields, raw-one 204, list order, start/end validation, four `marketData` children, no-tenant client and no-personal-data boundary remain exact.

- [ ] **373.7 current documentation/tests/live evidence。** Update `CLAUDE.md`, design/structure, OpenAPI and Javadoc to state the persisted FUBON_BOOKS primary／YAHOO_TW producer-fallback snapshot and no request-time upstream; mark Task 348/Yahoo request-time behavior as history rather than a simultaneous acceptance contract. Task 379 supplies the current source/completeness/revision tests; retain the original tests for (a) Python wire incl. invalid book preserving price and different actual/book timestamps producing `sourceTime=bookUpdatedAt`; (b) Java mapping/client preserving price outcomes; (c) non-queued writer isolation; (f) pure read cache+DB miss and unsupported zero external calls; (g) external/backend/BFF/9090 unavailable outcomes always `source=null`, `marketStatus=UNKNOWN`, `levels=[]` and never `YAHOO_TW`, plus 2-second timeout; (h) zero generic key/index/tick/day-HL/SSE side effects. `/run-stack` must rebuild/recreate fubon, external, business and BFF (restart BFF after upstream; api-gateway only if source changed). A real available snapshot must have matching PostgreSQL header+five ordered levels, Redis envelope and `http://127.0.0.1:9090/api/quotes/one?code={code}&market=台股` source/time/levels. HTTP 200 alone is not proof. If market/config/secrets/no complete book prevent live evidence, report that limitation and keep schema/DB/Redis/pure-read evidence; do not fabricate one.

## 驗證

```bash
PYTHONPATH=fubon-broker-service/src python3 -m pytest -q fubon-broker-service/tests
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/schema-sql-drift-test.sh
git diff --check
```

## 完成報告

（實作者完成後回填實際修改檔案、單元／整合／Docker／DB+Redis+9090 readback 結果、image SHA，以及偏差與原因。）
