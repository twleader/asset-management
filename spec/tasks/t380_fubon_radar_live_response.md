# [t380] 富邦 LIVE 僅查交易雷達、完整保存並由 9090 單檔報價回傳

**對應 Requirements:** Requirement 115（富邦十秒 LIVE 只查今日交易雷達；完整標準化回應保存並由既有 9090 單檔 quote 唯讀回傳）
**前置任務:** t370（十秒台股 priority persistence）、t373（富邦五檔快取）、t379（Yahoo 五檔 fallback）
**Liquibase changeset:** v1.117.0-fubon-live-quote-response.sql

## 背景

目前 `TwLiveQuoteDispatcher` 以傳入的所有台股輸入做最多 40 檔富邦 round-robin，並只把通過 actual-price 驗證的欄位寫到 `stock_intraday_quote`，完整有效五檔才另寫 canonical order-book store。富邦 normalized endpoint 的 per-code `status/reason`、`batchId`、`counters`、`closed`、`quoteStatus`，以及不完整但仍有值的 optional `orderBook` 並沒有一個可完整回讀的持久化位置；9090 的 `GET /api/quotes/one` 也只帶 raw 19 欄、chart、complete quoteDetail、ETF 與股利資料。

使用者明確定義「今日交易雷達」涵蓋所有使用者的持股和觀察股票，且要求：

1. ten-second **LIVE** Fubon endpoint 只可查這個集合；
2. endpoint 回傳的每一檔標準化資料要完整寫入 PostgreSQL 與 Redis；
3. 既有 9090 `GET /api/quotes/one` 成功回應要包含這些資料，不限於既有 complete-book projection。

這不是交易、下單、庫存同步或 per-round 無限歷史保存需求。富邦 SDK 的 proprietary raw object、token、帳戶、持股／owner 資訊不得離開 adapter 或寫入新資料層；只保存已受 endpoint 合約限制的 normalized JSON。

## 要做什麼

- [ ] **380.1 將全部台股 LIVE candidate 收窄為交易雷達交集。** 在 production `TwLiveQuoteDispatcher` 中，先無條件以 `StockSourceQuery.collectTwRadarCodes` 取得每 owner 最新台股持股 union 台股 `stock_alert`，令唯一 effective set 為穩定正規化後的 `codes ∩ radarCodes − {0000}`；不得只在 Fubon client 可用時才查，也不得在 `PricePoller` 某個 caller 單獨篩選來假裝完成。scheduled、warmup、refreshAll 和 TwRadarRefresh 都必從 dispatcher 得到相同限制。只有 effective set 可送進 Fubon、TWSE MIS、Yahoo actual-price、Fubon complete-book writer 和 Yahoo book fallback；40-code Fubon cursor 只在 effective set 上工作，未被 Fubon 選取的 effective code 才從 MIS 開始。radar empty、collector exception、僅非 radar code 或僅 `0000` 時 zero Fubon HTTP/SDK quote、zero TWSE MIS/Yahoo actual-price HTTP、zero Fubon/Yahoo book worker。collector failure 不得退回 held-code collector、Redis、9090 或 UI data 猜測 radar；非 radar input 直接終止，不得保留原始 input 進 generic MIS → Yahoo chain。本 task 只收窄台股即時 dispatcher 外呼，不改歷史回補／正式收盤等另行授權路徑。

- [ ] **380.2 鎖定 purpose boundary。** 此變更只作用於 `FubonNormalizedQuoteClient` 的 `purpose=LIVE`。backend `FubonHttpClient.readTwQuotes` 的 `purpose=INVENTORY`、inventory flag/capacity conflict、broker read-only flow、資產資料與所有 order/transfer prohibition 全部維持不變；不得讓 live response store 或 9090 query 呼叫 inventory client。測試須證明這條邊界，而非僅靠類別名稱推論。

- [ ] **380.3 保留完整、已驗證的 endpoint envelope。** 擴充 `FubonNormalizedQuoteClient.BatchResult`（或同等 immutable contract），使它以 strict duplicate-key detection 完成 HTTP 200 root/body/requested-code/duplicate/status/reason/counter/quote-schema/size validation 後保留 `batchId`、完整 `counters` object，以及每個 requested code 的完整 normalized response row JSON。root 只能有 `batchId/counters/quotes`，batchId 符合 `^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$`；row 只能有 `stockCode/status/reason/quote`。`counters` 必精確有 13 個 nonnegative `int64` fields，名稱依序為 `DISABLED`、`MISCONFIGURED`、`CALENDAR_UNKNOWN`、`ACCOUNTING_FAILED`、`RECONCILE_FAILED`、`QUOTE_FAILED`、`NO_OWNER`、`NO_TODAY_SNAPSHOT`、`BROKER_MISSING`、`DRY_RUN`、`SUCCESS`、`EMPTY_CLEARED`、`ROLLED_BACK`；missing/extra/negative/nonintegral/overflow 一律拒絕整包。row 必包含 `stockCode/status/reason/quote`，status 只能 SUCCESS/FAILURE；SUCCESS 必 `reason=null` 且有 object quote，FAILURE 必 `quote=null` 且 reason 符合 `^[A-Z_]{1,64}$`。在 mapper 前新增 lossless success-quote schema gate：exact known fields、row/requested inner code identity、market 台股、source `FUBON_INTRADAY`、quoteStatus `LIVE`、canonical decimal/nonnegative int64/ISO date/RFC3339 instant/boolean/nullability 均須正確；optional orderBook 只能有 known fields、恰有五個 `level=1..5` fixed slots，bid/ask price+lot 必成對 null 或 canonical positive price+nonnegative int64 lot。任何 duplicate/unknown property、wrong inner code/market、wrong type 或 malformed nullable level 都拒絕**整包**，不存 arbitrary JSON。結構合法後 mapper 才決定 actual-price current-date/closed/OHLC eligibility 與 strict complete book；stale、closed 或 partial book 的合法 row 仍完整保存，不能因不能成為 observation/book 而刪欄位。HTTP status/transport/oversize/malformed/duplicate/missing/wrong-code/unknown-status/invalid-reason/invalid-counters/invalid-success-quote body 一律沒有可保存 envelope，且不得給 Fubon price/book path 使用。保留既有 `BatchResult` test constructors 或以明確 test seam 避免不相關 legacy test 靜默失效，但 production client 對合法 200 response 必定帶 envelope。

- [ ] **380.4 新增 DB-canonical latest response store。** 新增並註冊冪等 `backend/src/main/resources/db/changelog/changes/v1.117.0-fubon-live-quote-response.sql`：

  ```sql
  CREATE TABLE fubon_tw_live_quote_response (
      stock_code   VARCHAR(20) NOT NULL,
      market       VARCHAR(20) NOT NULL,
      received_at  TIMESTAMPTZ NOT NULL,
      batch_id     VARCHAR(64) NOT NULL,
      counters     JSONB NOT NULL,
      response_row JSONB NOT NULL,
      PRIMARY KEY (stock_code, market)
  );
  ```

  加入 `market='台股'`、`jsonb_typeof(counters)='object'`、`jsonb_typeof(response_row)='object'` 和 nonblank batch-id 的 constraints；migration 必須 `IF NOT EXISTS`、master YAML 註冊一次，且不可增加 owner/account/secret/SDK-raw column。external-materials-service 建立 transaction-bound store，單一 batch 以一個 microsecond-precision receipt instant 對所有 rows 做 all-or-nothing `INSERT ... ON CONFLICT ... WHERE EXCLUDED.received_at > current.received_at`。回傳每個 DB canonical row（APPLIED 或 stale reread），不可把 raw candidate 當 Redis input；個別 row 不能部分 commit。這是每檔**最新回應狀態**，不是每十秒 append history。

- [ ] **380.5 專用 Redis mirror 與失敗規則。** 新增 dedicated `price:fubon-live-response:台股:{code}` key 和 strict receipt-time Lua/cache writer；value 固定帶 DB canonical `receivedAt`、`batchId`、`counters`、`responseRow`，TTL 24 hours。只有 DB returned canonical row 可寫；equal/older 不覆寫、不刷新 TTL。DB write 失敗時，dispatcher 視本 Fubon batch 為不可用：不得處理該 batch Fubon observations、不得提交 Fubon orderbook／Yahoo-book worker，**僅 effective radar codes** 走 MIS → Yahoo；非雷達 code 保持零外呼。專用 Redis write 失敗時保留 DB canonical，effective generic price chain 可照現有規則處理；後一合格 response 必以 DB canonical retry mirror。此 key 不得變成 `price:{market}:{code}`、`price:quote-detail:*`、index、ticks、day-H/L、SSE 或任何 public list cache。

- [ ] **380.6 保持既有價格／五檔 canonical semantics。** response mirror 只是完整回應記錄。success quote 的 actual-price contract、`stock_intraday_quote` newer-wins、stock name、generic Redis latest quote，及 valid complete book 的 `stock_intraday_order_book`/levels + quote-detail cache 必須完全保留。若 price timestamp equal/older，或 optional book incomplete，generic canonical price/book/tick/day-H/L/SSE 仍要 zero mutation；但經 endpoint envelope validation 的 response row 要依 receipt time 更新 new mirror。不得用 response JSON 對 generic price 反向寫入，不能讓 partial level 偽裝 FUBON_BOOKS，也不得新增 vendor calls。

- [ ] **380.7 增加 external internal pure-read bridge。** 新增唯一 Docker-network internal exact `GET /internal/fubon-live-response?code=&market=`，由 dedicated read service 實作 `Redis candidate → PostgreSQL received_at validation → matching cache or DB canonical → typed unavailable`。reader 不得 cache repair、寫 DB/Redis、觸發 dispatch/price refresh、呼叫 Fubon/Yahoo/broker。它回 typed envelope：`supported/available/message/receivedAt/batchId/status/reason/quote`；`quote` 展開所有標準化 quote properties，`orderBook` 展開 `bookUpdatedAt/averagePrice/turnoverYi/innerVolumeLots/outerVolumeLots/levels`，levels 的 price/lot 可為 null 以忠實表示 stored incomplete fixed slots。完整 fixed 13-key counters 仍存於 DB/Redis envelope，但不得由 bridge 傳送或在任何 public/no-tenant response 投影，因為它是 process-global accounting/portfolio lifecycle telemetry 而非單檔 market data。台股非 `0000` response miss 是 supported-unavailable；non-TW/`0000` 是 unsupported. This bridge must not be host-mapped, gateway-routed, or callable through a wildcard.

- [ ] **380.8 只擴充既有 9090 single quote response，禁止同義欄位重複。** `GET /api/quotes/one` raw cache hit 維持先讀 external raw 19 fields、cache miss 維持 204。`DetailedLatestQuote` 不得新增 nested `quote`／`fubonQuote`／`fubonLiveResponse.quote` 或第二份 `actualPrice`、OHLC、買賣價、成交量、日期、來源、closed／quoteStatus：stored `SUCCESS` Fubon row 只有在 source/tradingDate 相同且其 `updatedAt` 與 raw cache canonical quote 解析為同一 market instant 時，才可填入已存在的 top-level shared quote fields（`actualPrice → price`，變動值只用同筆 price/previousClose 推導）；mismatch、隔日 row、較新 MIS/Yahoo raw、failure／unavailable 都保留 raw cache fields，response mirror 絕不能成為 price authority。`premiumDiscountPct` 永遠保留 raw ETF NAV cache 值，不可由 Fubon 清空、覆寫或反推。所有既有 fields、原 `marketData` four children、direct `quoteDetail/bidLevels/askLevels/dividendHistory` 的欄位名稱、順序和語意皆保留。成功 single quote 在所有既有欄位末端只新增 required top-level `fubonSupported`、`fubonAvailable`、`fubonMessage`、`fubonReceivedAt`、`fubonBatchId`、`fubonResponseStatus`、`fubonFailureReason`、`fubonReturnedOrderBook`；完整 13-key `counters` 仍 DB/Redis 保存但因是 process-global accounting/portfolio lifecycle telemetry，絕不可出現在 bridge、`DetailedLatestQuote`、9090 或其他 public/no-tenant route。最後一項是可包含 null fixed slots 的 returned normalized book，與既有 strict canonical `quoteDetail` 的 source/revision semantics 不同，二者不可互相替換。新增 immutable `ListedLatestQuote`，精確保留舊有 24 fields/order；`PublicQuoteMarketDataController.list`、`PublicQuoteMarketDataService.list` 與其 enrichment/fallback generic、以及 `/api/quotes` OpenAPI items 都必改用它，確保 list JSON keys/order、四個 marketData children、direct projections 與 zero bridge call 完全不變。`DetailedLatestQuote` 僅供 single quote。single quote 的 new fields 以 no-tenant external client call 380.7 bridge，與既有 children parallel、timeout/failure child-local fail-soft；不得由 BFF/business 直查 DB、寫 cache、call vendor、owner bootstrap 或帶/保留 Reactor tenant headers。沒有 stored response 時回 typed unavailable；failure row 則 `fubonAvailable=true,fubonResponseStatus=FAILURE,fubonFailureReason=<typed>`。不新增任何 9090/gateway path or method.

- [ ] **380.9 更新 public contract and rules。** 修改 `docs/openapi/docker-external-api.yaml` 的 `GET /api/quotes/one`、`DetailedLatestQuote` top-level de-duplicated Fubon field required list/example、`GET /api/quotes` items 改為 `ListedLatestQuote`，以及所有 new returned-orderBook/level schema descriptions。文件要逐欄說明 type/format/nullable semantics、available vs failure/unavailable、shared quote 的 source/date/normalized-instant equality gate、`premiumDiscountPct` raw NAV exception、zero same-meaning duplicate、five-level order、source/provenance、台股 support boundary、one-only/no-list expansion、Redis/DB pure-read and no request-time vendor I/O；明確聲明 `counters` 雖完整 DB/Redis 保存，卻因 adapter-wide accounting/portfolio telemetry 永不透過 bridge/9090/no-tenant route 公開。不得用 arbitrary JSON / `additionalProperties` hiding fields。`GET /api/quotes` document remains four children and has identical old keys/order. Run `ruby scripts/render-9090-openapi-docs.rb` to regenerate both checked-in and SRPP Swagger mirrors byte-identically; update `CLAUDE.md` and `spec/steering/structure.md` to name the one-only Fubon response projection and its no-personal/no-vendor boundary. No gateway allowlist change is permitted.

- [ ] **380.10 必要測試與實機驗收。** Extend existing tests and add focused deterministic tests as needed, covering all of the following:

  - Radar selection: an input containing radar and non-radar Taiwan codes uses the radar intersection as the only Fubon/MIS/Yahoo/book candidate set; no radar/collector error sends no live-market-data request; all dispatcher entry paths obey it; Fubon orderbook/Yahoo-book candidates remain radar-only; 41+ radar codes retain fair cursor while effective codes not selected by Fubon can fall back to MIS/Yahoo; inventory request remains unrelated.
  - Client/store: retained success and failure rows preserve every normalized quote/orderBook field, batchId and all 13 counters; structurally valid stale/closed/partial book remains stored even though canonical price/complete-book mapping rejects it; malformed/oversize/missing/duplicate-key/wrong-code/unknown-root-or-row-property/unknown-status/invalid-status-reason/invalid-counter/unknown-success-quote-property/wrong-inner-code-or-market/invalid-orderBook response creates no envelope/store write; batch store is atomic; PostgreSQL and Redis readback prove full values, strict receipt ordering, TTL non-refresh on stale and DB-to-Redis repair, including a latch-controlled A(t1)-delayed-mirror/B(t2)-committed-mirror Lua rejection and a B DB-canonical bridge read when only stale A cache remains.
  - Existing canonical price semantics: response-store DB failure skips Fubon price/book then lets only the effective radar set fall through to MIS/Yahoo; raw response save with stale/equal price does not mutate generic price, tick/day-HL/SSE or complete-book revision; raw Redis failure does not lose DB canonical or trigger a vendor retry.
  - Pure reads / 9090: internal bridge does no write/vendor work and validates cache receipt timestamp against DB; `GET /api/quotes/one` returns the complete typed success/failure/unavailable de-duplicated Fubon projection only after raw quote hit; success maps every shared quote concept exactly once at its existing top-level name only under source/date/normalized-instant equality, mismatch/next-day/newer-other-source/failure leave raw values and raw `premiumDiscountPct` intact, and raw returned book is distinct from strict canonical `quoteDetail`; `GET /api/quotes` uses `ListedLatestQuote`, makes no bridge calls and has byte-for-byte identical old keys/order; bad bridge input/timeout is child-local unavailable; non-Taiwan/`0000` has no bridge call; existing external/gateway exact route tests, OpenAPI parity and generated-doc check stay green.
  - Liquibase and runtime: migration is applied through `business-services`, `db/schema.sql` is regenerated by its header instructions and drift test succeeds. Rebuild/recreate `business-services`, `external-materials-service`, then BFF (external restart requires BFF restart). Verify container image provenance, health, migration/table presence and cache schema. Do not force a real Fubon request outside known-open time; when live is closed, report deterministic PostgreSQL/Redis/BFF tests instead of fabricating API data.

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/tests/schema-sql-drift-test.sh

# 在包含未合併實作的 worktree rebuild；run-stack 驗證實際 Compose service/image provenance。
docker compose build --no-cache business-services external-materials-service bff
docker compose up -d --no-deps --force-recreate business-services external-materials-service bff
docker compose ps
docker exec asset-postgres psql -U assets -d assets -c "SELECT column_name, data_type FROM information_schema.columns WHERE table_name='fubon_tw_live_quote_response' ORDER BY ordinal_position;"
docker exec asset-external-materials-service wget -qO- http://127.0.0.1:8080/actuator/health
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
```

During a naturally open market only, the runtime report may read a persisted non-`0000` Taiwan row and its exact dedicated Redis key, then call `127.0.0.1:9090/api/quotes/one?code={code}&market=%E5%8F%B0%E8%82%A1` to compare the de-duplicated top-level Fubon metadata, shared quote fields and `fubonReturnedOrderBook` with DB/Redis. It must not send a manual Fubon endpoint request, alter time gates or fabricate a row merely to obtain this evidence.

## 完成報告

（實作者完成後回填：actual changed files、test/runtime results、migration/schema/cache readback、9090 field verification、market-state limitation，以及任何與本 task 不同的取捨。）
