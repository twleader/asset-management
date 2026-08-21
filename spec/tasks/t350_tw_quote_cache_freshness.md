# [t350] 台股 Redis 最新價日期時間單調守門、MIS 批次重試與每輪可觀測統計

**對應 Requirements:** Requirement 89（台股 Redis 最新價不得被舊日期或同日舊時間倒退覆蓋，MIS 兩分鐘 producer 改為批次重試並輸出可稽核統計）
**前置任務:** t346、t349
**Liquibase changeset:** 無

## 背景

Docker 外部 `GET /api/quotes` 與 `GET /api/quotes/one` 只讀 Redis `price:{market}:{code}`，本身不抓行情。2026-08-21 盤中嚴格報價檢查出現兩種失敗：一部分 code 今日已有真實 tick，卻被前一交易日 `VERIFIED_CLOSE` 覆蓋；另一部分 code 因 TWSE MIS 連續回 `z='-'`，成功寫入間隔超過五分鐘。

第一種的觸發點是啟動 self-heal：交易日 14:05 前會把「最近完成交易日」解析為 T-1，官方 reconciliation upsert DB 後又無條件呼叫 `PriceCacheWriter.writeVerifiedClose` 寫相同 latest key。它與 `PricePoller.warmCacheOnStartup` 平行，形成舊日 close 與當日 LIVE 的競速。第二種則來自逐檔請求：每兩分鐘對約 21 檔各開 virtual thread，每檔先試 `tse` 再試 `otc`；非 200、空陣列或 `z='-'` 大多沒有一行能概括整輪結果，無法分辨來源失敗與真無新成交。

本任務保留「Redis 只能由真實成交推進」及 9090 純讀契約，不用昨日收盤、今日開盤、bid、ask 或中價冒充 LIVE。低流動性標的若五分鐘內確實沒有成交，嚴格交易流程仍應 fail-closed；本任務只移除舊日覆蓋、逐檔 burst 與不可觀測性造成的假失敗。

## 要做什麼

**350.1 metadata 精確補充：**VERIFIED_CLOSE 沿用同日 previousClose 後，固定覆寫 `priceChange=incoming.price-previousClose` 與 `changePercent=priceChange*100/previousClose`，後者為 6 位 `HALF_UP`（負 midpoint 遠離 0）。可沿用的 previousClose/OHLC 只能是 finite JSON number 且 `>0`；volume 只能是 finite JSON integer 且 `0..9007199254740991`。numeric string、其他 JSON 型別、0/負 OHLC、負/fractional/越界 volume 均不沿用。正式 Lua contract 必驗正負 midpoint、每欄 wrong type/value/domain 與 DB incoming previousClose 不被取代。

- [x] 350.1 **所有 latest-key 寫入共用 production Lua。**`write`、`writeVerifiedClose`、`syncClosedFromDb` 把具唯一date/Instant的完整payload交給classpath `redis/price-cache-monotonic-write.lua`；KEYS[1]=latest、KEYS[2]=index，ARGV[1]=payload JSON（tuple只從它解析）、ARGV[2]=member、ARGV[3]=TTL seconds、ARGV[4]=channel，回`{1|0,existingDate,existingUpdatedAt,existingQuoteStatus}`，既有status壞時第四格為`""`。mutation前驗key type與incoming JSON/date/time/status；incoming status missing/null/非字串/trim空白以Redis error→Java FAILED且零mutation，unknown非空為rank0。existing malformed JSON/壞date可repair；existing date/time合法但status missing/null/非字串/空白視legacy rank0且仍比較time。date先分層，再於同日守`VERIFIED_CLOSE=3 > LIVE=2 > PREVIOUS_CLOSE=1 > 其他/legacy=0`，低順位永不覆寫高順位；順位不低才比較time，舊time永不覆寫新time。legacy `HH:mm`／`HH:mm:ss`／fraction1–9正規化；壞time只允許不降級的同日repair，壞date才允許跨日repair。status1才同script SET EX/SADD/EXPIRE/PUBLISH=WRITTEN，status0=REJECTED_STALE；Java禁GET比較或為log另GET。tuple接受後才可在同script補close metadata：stockName缺/空可跨日沿用existing非空字串；其餘只限同日，VERIFIED_CLOSE缺previousClose時沿用existing正數並重算漲跌，PREVIOUS_CLOSE的DB previousClose不被覆寫但缺OHLC/volume可逐欄沿用existing；LIVE不沿用，malformed/壞date/跨日不搬數值，不增ARGV/內部或公開欄。拒絕對latest/index/TTL/publish/tick零mutation；唯一例外是有效live actual-trade可依Requirement14更新**incoming自己的**`price:dayhl:*:{date}`，即使latest stale亦不得改其他date/latest/tick。非正價在day-H/L/Lua前SKIPPED零Redis。tick sole owner是writer且只有live `write(...,false)`、source actual-trade gate、price正、WRITTEN才用同一Instant轉market zone恰一次；close/sync/non-WRITTEN零tick。
- [x] 350.2 **`ClosePersister` historical repair 加第一道 cache publication 守門。**抽出 package-visible pure helper，輸入 `LocalDate targetDate`、`ZonedDateTime nowTw` 與既有 `MarketCalendar` 判定：target 不早於 today、today 非台股交易日、或台北 09:00 前才允許 publish；交易日 09:00 後若 target < today，仍執行官方 fetch 與 `stock_price_history` upsert，但略過 `writeVerifiedClose`。`selfHealTwClose(nowTw)` 必須只算一次 decision，並把同一個 now／decision 同時傳入 official reconciliation 與其 coverage 不足後的 FinMind fallback，不可在任一分支重讀牆鐘或只攔官方路徑。排程 14:05／15:35／17:35 的當日 target、盤前與週末／假日 repair，以及同日 FinMind fallback 均維持可更新 latest。
- [x] 350.3 **新增 immutable timed observation，日期／時間只有一份權威。**MIS只接requested唯一row；positive z須strict `d=yyyyMMdd`與`t=HH:mm:ss`，以`d+t`在Asia/Taipei組成唯一date/Instant；`tlong`實證是來源更新時間（可對應`ot`）而非z成交時間，選填且只驗strict epoch millis與calendar date=d，異常累積`sourceTimeAnomalyCodes/schemaAnomalies`但仍RESOLVED，禁止比對`t`或推進freshness。其餘producer：(a)NASDAQ live response接受Clock一次＋resolver(sameInstant)；(b)Yahoo LSE用positive regularMarketTime epoch seconds/London date；(c)TAIEX 0000用Yahoo最後5分K timestamp/date且等於DayQuote date；(d)TWSE/TPEx official與FinMind TW用已驗證row/target date＋Clock一次；(e)美股FinMind parser必驗最後row date=expected target，不等則DB/Redis零寫，再以row date＋Clock一次；英股Yahoo bar同理；(f)DB sync用DatedClose.date＋row返回Clock一次。verified writer只收observation；相容overload date不等即FAILED。writer不讀clock。MIS mapping沿用name/y/o/h/l/v/b/a。
- [x] 350.4 **兩個有界global waves。**常數`CHUNK_SIZE=40`、`MAX_CONCURRENT_CHUNKS=8`、`MAX_REQUESTED_CODES=320`；>320整批零HTTP、全MISSING、`capacityRejectedCodes`=全requested並WARN，不截斷。合法輸入最多8 chunks，以最多8個固定worker（可用fixed virtual-thread factory）同時送；coordinator global deadline與單request timeout各5秒。deadline時已進send者計httpRequests+requestFailures，未進send/executor reject者只進capacityRejectedCodes，codes皆provisional MISSING。第一wave非RESOLVED共同等一次3秒後按原序第二且最後wave；不得第三輪、無界executor/queue或close/await抵銷deadline，網路硬上界13秒。HTTP200亦須root 0000/OK/msgArray array，否則request failure；WARN含wave/chunk/status/exception及error envelope fields。健康Redis/DB另驗manual `<30s` SLO，無全方法硬deadline/detached工作。
- [x] 350.5 **互斥完整summary。**唯一positive z＋合法d/t為RESOLVED；z無成交為NO_TRADE；duplicate/非法z/壞d/t為INVALID；無row為MISSING；request failure provisional MISSING。第一輪只有RESOLVED terminal，其餘第二輪覆蓋。immutable summary含四類、foreignCodes、schemaAnomalies、sourceTimeAnomalyCodes、capacityRejectedCodes、HTTP計數；tlong anomaly可與RESOLVED並存且不改partition。HTTP計數只算actual send，四類總和=requested。
- [x] 350.6 **`PricePoller.updatePrices(codes,"台股",false)` 改 batch 並分層統計。**台股不建per-code threads；美/英逐檔但帶timed observation。resolved再依writer outcome分written/staleRejected/writeFailures且總和=resolved；一行INFO列client/cache計數與所有evidence sets，含sourceTimeAnomalyCodes，tlong anomaly彙總可見但不逐檔WARN；writer拒絕log帶existing/incoming tuple。Poller WRITTEN後只name upsert、零tick；writer依350.1 eligibility。cron/zone與0000排除不變。
- [x] 350.7 **DB same-row與公開時間契約。**`syncClosedFromDb`以單一query回immutable DatedClose(date,close)，不得另查max date；previousClose依其date，row返回Clock.instant一次並固定status=`PREVIOUS_CLOSE`。該Instant只代表row取得時間，不得據此降級同日既有`LIVE`／`VERIFIED_CLOSE`；由350.1 Lua原子順位守門。`GET /api/quotes`與`GET /api/quotes/one?code=&market=`仍只讀Redis，route/method/19欄shape、one成功200/不存在204均不變且不觸發fetch/write/publish。updatedAt改為source Instant或本機取得Instant轉台北固定9位奈秒字串，不是SET時間；OpenAPI更新description與example，pattern仍相容既有 `HH:mm`／`HH:mm:ss`／fraction 1–9位。無新response欄、Redis key、DB schema、business/frontend。
- [x] 350.8 **測試。**除既定batch/Lua/day-HL/tick/DB/close/cron矩陣，同日較晚`PREVIOUS_CLOSE`不得覆寫`VERIFIED_CLOSE`或`LIVE`，同日較舊time即使incoming狀態較高也不得覆寫較新time；incoming status missing/null/number/empty各FAILED零mutation，existing同四類各按rank0且舊time拒絕、相等/新time修復；各拒絕案例驗payload/index/TTL/publish/tick零mutation。accepted close另驗同日metadata逐欄補齊、incoming值優先、derived漲跌重算、跨日只保留stockName而不搬previousClose/OHLC/volume，以及malformed existing不搬欄仍可repair。MIS fixture必含`d=20260821,t=13:30:00,ot=14:30:00,tlong=1787293800000,positive z`仍RESOLVED且freshness=`2026-08-21T05:30:00Z`；壞d/t才INVALID，壞/缺tlong只加anomaly。美股FinMind最後row date不等target時DB/Redis零寫。不得真sleep/MIS/牆鐘。
- [x] 350.9 **架構與 runtime 驗收。**全測後arch-auditor；重建external/restart BFF，驗health/9090真實200/19欄。manual refresh只接受phase成功且`<30s`。production Lua synthetic使用每次唯一、與公開reader完全隔離的`t350:test:latest:<run-id>`／`t350:test:index:<run-id>`，payload標`closed=true,quoteStatus=SYNTHETIC_TEST`，做repair/stale/payload不變與精確cleanup；不得寫`price:*`/`price:index:*`、造LIVE或平行互撞。
- [x] 350.10 **完成報告與收尾。**回填實際檔案、測試數、Docker image/container provenance、9090 與 synthetic-key 證據，以及與原計畫偏差。驗收通過後 feature 分支使用單行短中文 commit，main 以 `--no-ff` merge commit 收斂並 push；不得納入既存 `.codex/` 或 `NEXT-TASK-transactional-selfinvocation.md`。

## 驗證

```bash
set -euo pipefail
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
bash scripts/tests/price-cache-monotonic-write-redis-contract-test.sh
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
docker compose -p asset-management build external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
docker compose -p asset-management restart bff
docker ps --filter name=asset-external-materials-service --format '{{.Names}}\t{{.Status}}'
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health

quotes_body="$(mktemp)"
one_body="$(mktemp)"
refresh_body="$(mktemp)"
refresh_timing="$(mktemp)"
run_id="$(date +%s)-$$"
test_key="t350:test:latest:${run_id}"
index_key="t350:test:index:${run_id}"
test_member="${run_id}"
lua_resource='external-materials-service/src/main/resources/redis/price-cache-monotonic-write.lua'
lua_container="/tmp/t350-price-cache-monotonic-write-${run_id}.lua"
cleanup_t350() {
  rm -f "$quotes_body" "$one_body" "$refresh_body" "$refresh_timing"
  docker exec asset-redis redis-cli DEL "$test_key" >/dev/null 2>&1 || true
  docker exec asset-redis redis-cli SREM "$index_key" "$test_member" >/dev/null 2>&1 || true
  docker exec asset-redis rm -f "$lua_container" >/dev/null 2>&1 || true
}
trap cleanup_t350 EXIT
quotes_status="$(curl -sS -o "$quotes_body" -w '%{http_code}' http://127.0.0.1:9090/api/quotes)"
test "$quotes_status" = 200
quote_code="$(jq -er '[.[] | select(.market == "台股" and (.stockCode? | type) == "string" and (.stockCode | length) > 0)][0].stockCode' "$quotes_body")"
one_status="$(curl -sS -o "$one_body" -w '%{http_code}' -G http://127.0.0.1:9090/api/quotes/one --data-urlencode "code=${quote_code}" --data-urlencode 'market=台股')"
test "$one_status" = 200
jq -e --arg code "$quote_code" '
  type == "object" and .stockCode == $code and .market == "台股" and
  ((keys | sort) == (["stockCode","stockName","market","price","previousClose","priceChange","changePercent","buyPrice","sellPrice","openPrice","highPrice","lowPrice","volume","tradingDate","updatedAt","closed","source","quoteStatus","premiumDiscountPct"] | sort))
' "$one_body"
test "$(curl -sS -o /dev/null -w '%{http_code}' -G http://127.0.0.1:9090/api/quotes/one --data-urlencode 'code=TEST-NOT-FOUND' --data-urlencode 'market=台股')" = 204

{ /usr/bin/time -p docker exec asset-business-services wget -qO- \
    --header='X-User-Id: 1' --header='X-User-Role: ADMIN' --header='X-User-Status: ACTIVE' \
    --post-data='' http://127.0.0.1:8080/api/trading-radar/refresh > "$refresh_body"; } 2> "$refresh_timing"
refresh_real="$(awk '$1 == "real" { print $2 }' "$refresh_timing")"
test -n "$refresh_real"
awk -v seconds="$refresh_real" 'BEGIN { exit !(seconds < 30) }'
jq -e '.radar | type == "object"' "$refresh_body"
jq -e '
  .priceRefresh.elapsedMs < 30000 and
  (if .priceRefresh.twMarketOpen
   then .priceRefresh.outcome == "FETCHED"
   else (.priceRefresh.outcome | IN("CLOSED_SYNCED","SKIPPED_PENDING_CLOSE"))
   end)
' "$refresh_body"

test -f "$lua_resource"
docker cp "$lua_resource" "asset-redis:${lua_container}"
lua_status() {
  docker exec asset-redis redis-cli --raw --eval "$lua_container" "$test_key" "$index_key" , "$1" "$test_member" 86400 t350-test-channel | sed -n '1p'
}
docker exec asset-redis redis-cli SET "$test_key" '{malformed-json'
new_payload='{"stockCode":"TEST-T350","market":"台股","price":100.00,"previousClose":99.00,"priceChange":1.00,"changePercent":1.010101,"buyPrice":null,"sellPrice":null,"openPrice":100.00,"highPrice":100.00,"lowPrice":100.00,"volume":1,"stockName":"T350","source":"SYNTHETIC_TEST","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:05:00.000000000","closed":true,"quoteStatus":"SYNTHETIC_TEST"}'
test "$(lua_status "$new_payload")" = 1
test "$(docker exec asset-redis redis-cli SISMEMBER "$index_key" "$test_member")" = 1
stored_new="$(docker exec asset-redis redis-cli --raw GET "$test_key")"
printf '%s' "$stored_new" | jq -e '.tradingDate == "2026-08-21" and .updatedAt == "2026-08-21T12:05:00.000000000"'

old_time_payload='{"stockCode":"TEST-T350","market":"台股","price":98.00,"previousClose":99.00,"priceChange":-1.00,"changePercent":-1.010101,"buyPrice":null,"sellPrice":null,"openPrice":98.00,"highPrice":98.00,"lowPrice":98.00,"volume":1,"stockName":"T350","source":"SYNTHETIC_TEST","tradingDate":"2026-08-21","updatedAt":"2026-08-21T12:04:59.999999999","closed":true,"quoteStatus":"SYNTHETIC_TEST"}'
test "$(lua_status "$old_time_payload")" = 0
test "$(docker exec asset-redis redis-cli --raw GET "$test_key")" = "$stored_new"

old_date_payload='{"stockCode":"TEST-T350","market":"台股","price":97.00,"previousClose":99.00,"priceChange":-2.00,"changePercent":-2.020202,"buyPrice":null,"sellPrice":null,"openPrice":97.00,"highPrice":97.00,"lowPrice":97.00,"volume":1,"stockName":"T350","source":"SYNTHETIC_TEST","tradingDate":"2026-08-20","updatedAt":"2026-08-20T23:59:59.999999999","closed":true,"quoteStatus":"SYNTHETIC_TEST"}'
test "$(lua_status "$old_date_payload")" = 0
test "$(docker exec asset-redis redis-cli --raw GET "$test_key")" = "$stored_new"

cleanup_t350
test "$(docker exec asset-redis redis-cli EXISTS "$test_key")" = 0
test "$(docker exec asset-redis redis-cli SISMEMBER "$index_key" "$test_member")" = 0
docker exec asset-redis sh -c 'test ! -e "$1"' sh "$lua_container"
```

若第一次 manual refresh 回 `COOLDOWN`／`BUSY`，須等待該狀態解除後重新執行整段計時；不得把等待時間或快速 skip 當 SLO 樣本。`TIMEOUT`／`FAILED` 直接判驗收失敗。synthetic 命令只可操作本次唯一的 `t350:test:*:<run-id>` keys與容器暫存Lua；不得使用任何 `price:*`／`price:index:*`、不得 flush DB或清真實行情 key。

## 完成報告

### 實際修改

- 規格／契約：`CLAUDE.md`、`spec/requirements.md`、`spec/design.md`、`spec/steering/structure.md`、`spec/tasks.md`、`spec/tasks/README.md`、本任務檔與 `docs/openapi/docker-external-api.yaml`。
- production：`MacroDataFetchClient`、`PriceFetchClient`、`ClosePersister`、`PriceCacheWriter`、`PricePoller`、`StockSourceQuery`、`TaiexIndexPoller`、`TradingDateResolver`，以及唯一 classpath Lua `redis/price-cache-monotonic-write.lua`。
- tests：既有 closing/MIS/cache/poller/TAIEX tests；新增 `PriceFetchClientTwBatchTest`、`PriceProducerTimestampTest`、`StockSourceQueryDatedCloseTest`、`TradingDateResolverObservationTest` 與可執行 `scripts/tests/price-cache-monotonic-write-redis-contract-test.sh`。

### 自動與獨立驗證

- Java 21 完整 external module：79 suites、438 tests、0 failures、0 errors、0 skipped。
- `scripts/spec-check.sh`：BLOCK 0／CHECK 0；spec-auditor 與修正後 arch-auditor 最終皆 critical 0／major 0／minor 0；兩種 diff check 皆通過。
- OpenAPI/gateway 九路 contract PASS。
- 正式 Lua Redis contract PASS：日期／狀態／時間單調、legacy repair、正負六位 HALF_UP、metadata 型別和值域、最大 safe-integer volume、key type、TTL/index、accepted 恰一次 SET/SADD/PUBLISH、stale/error 零 mutation/publish 均通過；結束後 `t350:test:*` keys 與 `/tmp/t350-price-cache-contract-*` 均為 0。

### Docker 與 9090 provenance

- Compose project `asset-management`；由尚未 merge 的 feature worktree build，只 rebuild/recreate `external-materials-service` 並 restart BFF。
- external image 由舊 `sha256:c466…ec2a` 更新為 `sha256:12252d126dfdc03f1bd220c00e5cc8728440735dfc5bf6706c02a658518dfd5d`；新 container `68ab7e2b22b60888e7fd937cc0895e261da36eb12434994cbfa49be3efdd3df3`，created `2026-08-21T12:51:33.735745634Z`、healthy、restart count 0。最終 running image 與 latest tag 相同，未被其他 session 覆蓋；BFF restart 後 healthy，`Connection refused|500 Server Error` 為 0。
- `GET /api/quotes` 為 200；`GET /api/quotes/one?code=00850&market=台股` 為 200 且精確 19 欄，樣本為 `00850 / 元大臺灣ESG永續 / tradingDate=2026-08-21 / updatedAt=2026-08-21T20:51:38.498602095 / VERIFIED_CLOSE`；不存在 code 為 204。
- manual radar refresh 首次 COOLDOWN 後依規範等待重跑；成功 phase 為 `CLOSED_SYNCED`，完整 wall time 1.677 秒、`priceRefresh.elapsedMs=20`。log 實證較晚取得的同日 `PREVIOUS_CLOSE` 對既有 `VERIFIED_CLOSE` 全數 REJECTED_STALE，沒有降級覆寫。

### 時段限制與計畫偏差

- 驗收時為台股盤後，未繞過 `MarketClock` 製造 LIVE，因此無法實觀察兩分鐘 MIS live cadence；改以 cron/fixture、兩波 batch 測試與盤後真實 writer guard 為證。
- 第一次 arch audit 額外找出 submission-order deadline 誤判與 close metadata 清空回歸；本任務因此增加 worker completion timestamp、accepted Lua metadata 安全補齊與正式 Redis contract 腳本。這些修正不新增公開欄位、Redis key、DB schema或路由。
- 本報告寫入後依專案自動收尾流程執行 feature commit、main `--no-ff` merge 與 push；commit hash 由最終使用者回覆列出，避免在同一 commit 內自我參照。
