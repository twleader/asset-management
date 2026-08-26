# [t379] 富邦十秒報價缺最佳五檔時以 Yahoo 背景補抓

**對應 Requirements:** Requirement 114（富邦每十秒報價缺有效五檔時，以受限 Yahoo producer fallback 保存並由 9090 唯讀提供）
**前置任務:** t373（台股最佳五檔 canonical snapshot）、t375（9090 direct quote fields）
**Liquibase changeset:** v1.116.0-yahoo-order-book-fallback.sql

## 背景

目前 TwLiveQuoteDispatcher 每十秒取得一批最多 40 檔富邦標準化報價。它能把有完整 order book 的標的保存成 FUBON_BOOKS，但富邦該輪沒有回完整五檔時不會建立新的 current book；若沒有既有 canonical snapshot，讀者才會得到 unavailable，若已有 canonical snapshot 則仍純讀該舊 snapshot。已存在的 9090 GET /api/quotes 與 GET /api/quotes/one 都是 pure read，不能也不應在 API request 裡直接抓 Yahoo。

正確行為是 producer-side fallback：每一個富邦十秒 round 中，對富邦選取且缺「有效完整富邦五檔」的代號才背景向 Yahoo 補抓。Yahoo 成功後成為同一 canonical order-book snapshot 的 YAHOO_TW source，讓既有 9090 response 自然回傳五檔；Yahoo 失敗則保留之前快照，絕不影響富邦 actual-price chain、下一輪、generic price cache 或 9090 latency。

## 要做什麼

- [ ] 379.1 在 external-materials-service 的 TwLiveQuoteDispatcher 同一個十秒富邦 batch 中，精確找出「富邦本輪選取、但 FubonNormalizedQuoteClient.BatchResult.orderBooks() 沒有 valid complete FUBON_BOOKS」的代號；不得依 9090 當下可用性或既有 DB/Redis snapshot 決定候選。valid complete FUBON_BOOKS 是 requested valid Taiwan code 的 five level 1..5，每 level 均有正 bid/ask price 與正 bid/ask lots、bid 嚴格遞減、ask 嚴格遞增、兩側都無重複，並有 current-Taiwan-date nonfuture source time、OPEN、正 price/previousClose 與非空名稱。Fubon adapter/mapper 必把 null、zero、partial、duplicate、out-of-order 或 invalid-time book 排除在 BatchResult.orderBooks() 之外，使其觸發 Yahoo；這不得拒絕同一 quote 的 actual-price observation。保留原本最多 40 檔 round-robin、Fubon request 數量、actual-price persistence 順序與 FUBON→MIS→Yahoo actual-price fallback。不得因這項工作另發富邦 request、修改 generic PriceFetchClient 價格規則，或讓 Yahoo 工作阻塞 dispatcher 的 in-flight guard。


- [ ] 379.2 新增獨立 single-flight、無 queue 的 Yahoo order-book fallback writer（可命名 TwYahooOrderBookFallbackRoundWriter）。每輪先將最多 40 個 candidate code 穩定排序，依 bounded round-robin cursor 選**至多 8 個 code／16 個 probe**；cursor 在已接受 worker round 開始時前進一個 8-code window，未選 code 僅記 `DEFERRED_BY_ROUND_BUDGET` 並由下一輪先後輪替，不建立等待 queue。同一 stable 40-code set 因此每一 code 最遲在 5 個成功 fallback rounds 內取得一次 probe，不得永久飢餓。最多 4 個 outbound Yahoo probe、每個 probe 最多 2 秒、整輪最多 9 秒；每個 code 最多兩次 probe，故 8 個 code 的 worst-case 16 probes 在 4-concurrency 下上限為 8 秒。每個 probe 回 immutable `YahooProbeOutcome`：`FOUND(snapshot)` 只限最多 **2 MiB** 2xx body 經 strict parser 成功；`STRUCTURAL_MISS` **只限 HTTP 404**；`TRANSIENT_OR_INVALID` 包含 timeout、transport、429／5xx／其他 non-2xx、body 超過 2 MiB、HTML/JSON/schema error、wrong symbol/market 及 strict validation failure。每個 code 先序列 `https://tw.stock.yahoo.com/quote/{code}.TW`；只有 `STRUCTURAL_MISS` 才可在第一 probe 完成後序列嘗試 `.TWO`。`FOUND` 和 `TRANSIENT_OR_INVALID` 均不得啟動第二 probe；`.TW` URL 若回 requested code 且 exchange=`TWO` 的合法頁面，仍是 `FOUND`，suffix 不能當 market 判斷依據。兩個 suffix 都計入同一 4-concurrency／2-second／9-second budget，deadline 時取消未開始或未完成的 probe。rejected/busy、任何非 FOUND outcome 都不得 throw 回 dispatcher、不得 retry 本輪、不得延遲下一個十秒 round，下一輪可重新嘗試。只讓 external-materials-service 做 Yahoo HTTP；business-services/BFF/9090 不得新增 Yahoo client、route 或 request-time I/O。


- [ ] 379.3 將既有 TwQuoteDetailFetchClient（或等價 immutable client）收斂成 fallback 用的嚴格 parser：輸入必為 market=台股 和合法非 0000 代號；Yahoo 2xx response body 最大 2 MiB，超過即停止讀取並為 `TRANSIENT_OR_INVALID`。Yahoo 資料必須同時驗證 requested code、currency=TWD、exchange 是 TAI 或 TWO、nonblank stock name、marketStatus=OPEN、正的 price／previousClose、有效且為台灣當日且非未來的 source time，以及恰好五個雙邊完整 level。每個 level 都要有正 bid/ask price、正 bid/ask lots；bid 嚴格由高到低、ask 嚴格由低到高、兩邊均不得重複。HTTP 404 不做 HTML parse，直接為 `STRUCTURAL_MISS`；2xx body 若 marker/JSON/schema/strict validation 任一失敗，或含錯商品／market、缺失、半邊、亂序、重複、錯日期，必為 `TRANSIENT_OR_INVALID`，絕不 padding null、絕不拼 generic buy/sell 或富邦摘要，也絕不將 2xx invalid 誤判 structural miss。成功 snapshot source 固定 YAHOO_TW。


- [ ] 379.4 擴充 canonical header 的 source 約束，只接受 FUBON_BOOKS 或 YAHOO_TW，新增 v1.116.0-yahoo-order-book-fallback.sql，並登錄 master changelog。migration 新增 `canonical_revision BIGINT NOT NULL CHECK (canonical_revision > 0)`，existing rows 回填 `1`；new insert 固定 revision 1，accepted canonical update 在同一 DB transaction 將 revision 嚴格加一。兩個 source 的 header/五個 levels 必是同一 DB transaction；所有 required header 欄位與 paired positive level 不變。DB 是 source comparator 的唯一 authority，禁止 JVM read-compare-write：使用 single conditional `INSERT … ON CONFLICT (stock_code,market) DO UPDATE … WHERE <full comparator> RETURNING canonical_revision` 或等價 row-lock transaction，原子套用同 source 嚴格較新的 source_updated_at、Fubon primary 可取代 Yahoo、Yahoo 只有 source time 嚴格晚於既有 Fubon 時才可取代 Fubon；equal/older 值不改 header、levels、name、fetched_at 或 canonical_revision。stale conflict 的 canonical reread 必在同一 locking/transaction sequence 取得已提交 row。`PersistResult`／canonical reread 必回完整 canonical snapshot **與 DB-assigned canonicalRevision**，不能回傳 raw candidate。QuoteDetailCache envelope 必含 exact decimal-string `canonicalRevision`、sourceUpdatedEpochMicros 和 snapshot；quote-detail-cache-write.lua 只比較 canonicalRevision（strict newer，不覆寫／不刷新 equal or older），不再以 source time 決定 source priority。Redis 只能寫 DB 已 applied 或 stale-conflict 後讀回的 canonical row，且永遠帶該 DB revision；缺 canonicalRevision 的 legacy cache payload 是 miss。不得讓 Yahoo 覆寫 generic quote、index、ticks、day H/L 或 SSE。

- [ ] 379.5 維持 QuoteDetailReadService、backend MarketDataService、BFF PublicQuoteMarketDataService 的 strict pure-read / proxy shape：QuoteDetailReadService 先 decode dedicated Redis candidate，再純讀 PostgreSQL current header revision；只有 envelope canonicalRevision 精確等於 DB revision 才回 cache，否則讀完整 DB canonical snapshot。DB revision lookup 或 full snapshot read 失敗時不得回 freshness-unknown cache，只回 typed unavailable；reader 不得修復／寫 cache。台股可用 snapshot 回 source FUBON_BOOKS 或 YAHOO_TW，unsupported 或 unavailable 一律 source=null、empty levels 和 sanitized message。GET /api/quotes 與 GET /api/quotes/one 不得新增路徑、query parameter、write I/O 或 raw 19 欄變更；quoteDetail、bidLevels、askLevels 必從同一 immutable available approved-source snapshot 投影，仍各最多 5 筆且維持 bid descending / ask ascending。不能讓任意未知 source 透過。


- [ ] 379.6 更新 docs/openapi/docker-external-api.yaml 的**全部 9090 operation**、quote operations、PublicQuoteDetail.source、bidLevels、askLevels 和所有 response／nested schema attribute descriptions。文件要清楚說明 API 用途，以及每個 response class 和每個 attribute 的資料語意、type/format、required、nullable、enum/item `$ref`、array ordering/max count；不得以泛稱取代 attribute 說明或遺漏 nested class。quote 文案必明說：Fubon is the ten-second primary producer; Yahoo is only an asynchronous per-code fallback after that Fubon round lacks a valid five-level book; source on an available snapshot is exactly FUBON_BOOKS or YAHOO_TW; response remains pure read and never calls either vendor。執行 ruby scripts/render-9090-openapi-docs.rb，覆寫版本庫 docs/openapi/9090-api-swagger.md 與 /Users/steven/Project/SRPP/docs/9090 Port API Swagger.md；兩者 bytes 必一致，禁止手改 generated Markdown。

- [ ] 379.7 同步更新 CLAUDE.md 與 spec/steering/structure.md 的 named order-book source exception，使它只允許 FUBON_BOOKS primary 和受限 YAHOO_TW fallback，且明定 query pure read、無個人資料與禁止估值／下單／警示。不得放寬 business direct-vendor 禁令或任何 9090 network / authorization path。


- [ ] 379.8 新增或更新 unit/integration tests，至少涵蓋：每輪缺 Fubon book 才 dispatch Yahoo（有 valid Fubon book、非選取 code、foreign market、0000 均不 dispatch）；stable 40-code set 的 8-code fair cursor（五個 accepted rounds 內每 code 都嘗試一次）、16-probe worst case、4-concurrency / 2-second call / 9-second round bounds 和 single-flight no-queue；`FOUND`／HTTP-404 `STRUCTURAL_MISS`／`TRANSIENT_OR_INVALID` 三類 probe 及只有前者 404 結果才觸發 `.TWO`；2 MiB-1 accept / 2 MiB+1 reject；Yahoo valid/invalid parser cases；兩個真 transaction/latch 的 DB source-comparator race；Redis canonicalRevision strict fence；Redis failure leaves DB canonical; read service / backend / BFF accept only the two approved sources; direct bidLevels/askLevels project Yahoo with correct order; and HTTP request to 9090 does not call Yahoo or Fubon。必有兩種 deterministic interleaving：(a) Fubon revision r 已持久化後，Yahoo accepted snapshot 寫為 r+1 並成功進 Redis；延遲的 Fubon r cache write 必被 Lua 拒絕，DB 與 Redis 最終同為 Yahoo r+1；(b) Yahoo r+1 已 commit 但 Redis write fails/empty 時，延遲 Fubon r cache payload 即使暫存，reader 必因 DB revision mismatch 回 DB 的 Yahoo r+1，不回 stale cache。Tests must never submit an order or access broker write paths.

- [ ] 379.9 因變更 Liquibase schema，依 db/schema.sql 檔頭指令在 Docker PostgreSQL migration 後重產 db/schema.sql，將 generated schema 納入同一交付，並使 bash scripts/tests/schema-sql-drift-test.sh 回 0。

## 驗證

~~~bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
cmp -s docs/openapi/9090-api-swagger.md '/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md'
bash scripts/tests/schema-sql-drift-test.sh
docker compose -p asset-management build --no-cache external-materials-service business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff
docker compose -p asset-management ps
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
docker exec asset-external-materials-service wget -qO- http://127.0.0.1:8080/internal/health
docker ps --filter name=asset-business-services --format '{{.Names}} {{.Status}}'
docker exec asset-postgres psql -U assets -d assets -c "SELECT id, dateexecuted FROM databasechangelog WHERE id = 'v1.116.0-yahoo-order-book-fallback';"
docker exec asset-postgres psql -U assets -d assets -c "SELECT stock_code, market, source, source_updated_at, canonical_revision FROM stock_intraday_order_book ORDER BY fetched_at DESC LIMIT 5;"
curl -fsS 'http://127.0.0.1:9090/api/quotes/one?code=00919&market=%E5%8F%B0%E8%82%A1'
~~~

Runtime verification must prove the rebuilt containers, applied migration, health, canonical DB/Redis source-plus-sourceTime-plus-canonicalRevision parity, and 9090 nested/direct five-book response. After the DB query selects a non-0000 Taiwan row, read its exact dedicated cache key with `docker exec asset-redis redis-cli --raw GET 'price:quote-detail:台股:{code}'` (substitute that row's code) and compare source/sourceTime/canonicalRevision to PostgreSQL; cache JSON must carry the same revision, not a raw candidate. The run-stack report must name the rebuilt image / recreated service and show the health result. Deterministic fixture tests must read back the applied YAHOO_TW snapshot through the DB store and dedicated Redis cache, then prove quoteDetail/bidLevels/askLevels are the same immutable snapshot. If the live market is closed or Yahoo / Fubon does not omit a book, record that live fallback cannot be forced without changing producer inputs. Do not fabricate a live fallback result.

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、實機證據、以及與原計畫的偏差及原因。）
