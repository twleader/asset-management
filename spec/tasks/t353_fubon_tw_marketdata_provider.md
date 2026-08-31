# [t353] 富邦台股逐檔 intraday LIVE provider 與原子 freshness

**對應 Requirements:** Requirement 91（富邦啟用時以官方逐檔 intraday quote 取代台股 LIVE 網頁來源，維持既有 19 欄與來源時間新鮮度）
**前置任務:** t352
**Liquibase changeset:** 無

## 背景

本 Task 353 完成時，`external-materials-service` 的台股 `PricePoller` 每 2 分鐘抓持股與觀察清單代號，透過 TWSE MIS 網頁來源取得價格，再由唯一 `PriceCacheWriter` 寫 `price:台股:{code}`、index set、24 小時 TTL與 `price-update`。**現行覆寫：**Requirement 106／Task 370 已將已知開盤台股更新為每 10 秒依富邦→MIS→Yahoo；Task 372 在此基礎上另處理最佳五檔的獨立 canonical snapshot。公開 `/api/quotes`、`/api/quotes/one` 已有精確 19 欄：
`stockCode,market,price,previousClose,priceChange,changePercent,buyPrice,sellPrice,openPrice,highPrice,lowPrice,volume,stockName,source,tradingDate,updatedAt,closed,quoteStatus,premiumDiscountPct`。最後一欄由獨立 ETF NAV cache唯讀join，不能由Fubon重算。

富邦官方 `intraday/quote/{symbol}` raw payload 回 `previousClose/openPrice/highPrice/lowPrice/closePrice`、bids/asks、`total.tradeVolume`、`lastTrade.time`、`isTrial`、`lastUpdated`；`open/high/low`不是本API的合法raw aliases。官方範例的時間是16位microsecond epoch。`closePrice`/`lastTrade`代表實際成交，`lastPrice`/lastTrial包含試撮。TSE/OTC snapshot雖可兩call取全市場，卻沒有bid/ask/previousClose且範例未證明trial flag，會讓現有19欄退化；SDK的`query_symbol_snapshot`官方另明示不即時。現況約35 codes、每2分鐘，逐檔仍遠低於官方intraday 300 requests/min。provider dispatcher 對正常四入口每輪先限 100 codes，Fubon adapter 同樣有 100 codes hard cap與240/min operational budget；Task 350 `PriceFetchClient.fetchTwBatch` 的 320 只是 disabled existing provider 直接呼叫時的防禦性 hard cap。

本任務現已與 Task 350 合併落地。Task 350 的一般 writer Lua 是 `write`、`writeVerifiedClose`、`syncClosedFromDb` 的權威；Task 353 另保留一條**只供market-open、具provider timestamp observation**的 scoped `PriceCacheWriter` 原子路徑。兩者共用同一 payload builder，都必須先比較 `tradingDate`、同日再守嚴格時間單調與狀態不降級；scoped Lua 另先驗 `marketOpenAuthorized` 與一次性 provider takeover。任一路徑都不得使另一路徑寫入的新日期或新時間倒退。

## 要做什麼

- [x] **353.1 固定provider且四入口只用known-open授權LIVE。**external建立窄`TwLiveQuoteProvider`與existing/Fubon兩實作；existing必須呼叫 Task 350 `PriceFetchClient.fetchTwBatch(codes)` 並依共用 writer outcome 分類 written/stale/write-failed，禁止退回逐檔 `getStockPrice`；Fubon走normalized client/scoped writer。新增tri-state `MarketClock.isTwMarketOpenKnown()`，以Asia/Taipei today/time與`MarketCalendar.isTwTradingDayKnown(today)`為唯一authority：只有date known true且`09:00<=t<13:30`回true，非交易日／盤外回false，authority empty/throw回empty；禁止用現行fail-open boolean clock授權。`FUBON_ENABLED`只選strategy。四入口(a)`scheduledTwIntradayUpdate`、(b)`warmCacheOnStartup`、(c)`POST /internal/refresh → refreshAll`、(d)`POST /internal/refresh/tw-radar → TwRadarRefreshService.refresh → updatePrices`都先走同一known gate；`updatePrices`台股路徑不得直MIS。known true才call selected provider；false/empty時Fubon/MIS live HTTP與Redis SET/index/TTL/PUBLISH/tick皆0。enabled provider失敗零MIS/Yahoo fallback；disabled known true只走MIS batch、零Fubon。盤外DB/history與official reconcile不變。

- [x] **353.2 Fubon internal client與token。**external只掛shared secrets，token lazy讀；enabled但檔案missing/unreadable/empty時Spring仍healthy，client回MISCONFIGURED且zero HTTP/Redis，不以constructor/`@Value` fail-fast。base URL只呼叫normalized quote endpoint；codes1..100、timeouts bounded。所有HTTP/schema/partial failure轉stable outcome，不lograw body/token。503只保留舊cache、increment固定outcome counter並寫每輪summary，絕不轉call scrape。

- [x] **353.3 normalized adapter契約與session前提。**Python adapter已用FubonSDK 2.2.9；乾淨`FubonSDK()`尚無`marketdata` attribute，因此必須先`apikey_login`成功，再呼叫`sdk.init_realtime()`，成功後才取得`sdk.marketdata.rest_client.stock`並逐code呼叫intraday quote。login/init/refresh失敗回503/no fallback。adapter batch上限100、concurrency≤20、per-call timeout≤5秒、wall≤30秒、30秒success cache＋per-symbol single-flight、global token bucket≤240/min；429尊重retry-after且開60秒circuit。Java不可繞過normalized endpoint直接理解SDK object。

- [x] **353.4 嚴格接受actual trade。**每筆先驗exact requested symbol、非空name、raw `previousClose>0`，且只接受一致pair `(exchange='TWSE',market='TSE')`或`(exchange='TPEx',market='OTC')`；任何錯配拒絕。`isTrial=true`必拒，false或欄位omitted才繼續。價格只接受明示actual pair：(a)`lastTrade.price>0`＋`lastTrade.time`；或(b)`closePrice>0`＋`closeTime`。兩組並存時BigDecimal price與解析後Instant都須相等；衝突整筆拒絕。16位epoch按microseconds解析，不得當milliseconds；轉Instant後不得超前injected Clock 30秒，且Asia/Taipei日期須等於payload/query trading date。禁止使用raw `lastPrice`、lastTrial、previousClose、openPrice/highPrice/lowPrice、bid/ask或中價fallback成actual price。低流動股票數分鐘無新成交是合法，不另發明固定5分鐘stale門檻；older/equal由writer tuple拒絕。

- [x] **353.5 精確decimal wire、OHLC與19欄映射。**Python只從官方raw `previousClose/openPrice/highPrice/lowPrice`（另加actual與非null bids/asks price）取值並先`Decimal(str(value))`；只有`open/high/low` alias時拒絕。映射到normalized/public同名`previousClose/openPrice/highPrice/lowPrice`後用positive canonical decimal string，precision≤20、scale 0..10。Java只從canonical string建BigDecimal並重驗同一limits，拒絕JSON float/scientific/non-finite。`total.tradeVolume`只接受`0..Long.MAX_VALUE` exact integer、原始張數不乘1,000。映射至既有PriceResult/observation與19欄名稱不變；OHLC完整關係必驗，priceChange/changePercent仍由唯一writer以BigDecimal既有公式計算，public numeric JSON不轉double、不為wire預先round。fixture含官方volume 54,538、`"0.1"`、precision20/scale10及Long邊界。

- [x] **353.6 provider time是唯一updatedAt來源。**建立例如 `ProviderTimedPriceObservation(PriceResult result, LocalDate tradingDate, Instant providerUpdatedAt)`；兩個time欄來自同一actual trade event。寫JSON時維持既有`updatedAt`格式：把Instant轉`Asia/Taipei`的ISO `LocalDateTime`字串，不改公開schema為帶Z/offset；禁止用Python receipt、WebClient receipt或Java `now()`。`lastUpdated`只作diagnostic consistency，不能取代actual trade time。provider future/wrong-date/missing timestamp在writer前拒絕。

- [x] **353.7 自足新增scoped atomic writer overload。**唯一`PriceCacheWriter`新增只接`ProviderTimedPriceObservation`與`marketOpenAuthorized`的overload，outcome至少`MARKET_CLOSED/WRITTEN/PROVIDER_TAKEOVER/STALE_OR_EQUAL/CURRENT_MALFORMED/WRITE_FAILED`。authorized只能由同輪`isTwMarketOpenKnown()==Optional.of(true)`產生；legacy boolean、authority empty/throw都不得產生true。Fubon OHLC authoritative且不呼叫dayhl tracker。共用既有payload builder/BigDecimal公式，不複製19欄serializer。

- [x] **353.8 Lua market-open、preflight、strict比較、一次性takeover與原子副作用。**Lua輸入另含`marketOpenAuthorized`及`allowProviderTakeover`。script第一步若market-open不為true即回`MARKET_CLOSED`，且必須位於任何Redis operation（含TYPE/GET）前；不得因current missing而寫，也不得refresh TTL/publish。授權通過後、任何GET/SET前固定驗2 KEYS／9 ARGV、latest none/string、index none/set、正整數TTL、非空code/market/channel、incoming JSON object及其identity/date/time與ARGV一致，並驗`FUBON_INTRADAY/LIVE/closed=false`；任一錯誤回`WRITE_FAILED`且零副作用，禁止SET後才因SADD WRONGTYPE留下partial mutation。通過才讀current並驗identity/tuple；missing可寫，malformed fail closed。同日所有current套`VERIFIED_CLOSE(3)>LIVE(2)>PREVIOUS_CLOSE(1)>unknown(0)`，incoming LIVE不得降級且不論rank提高/相等都只在strictly newer時寫；因此Fubon VERIFIED_CLOSE＋newer LIVE仍拒絕，正常Fubon LIVE strictly newer才寫。takeover同時要求兩flag、Fubon enabled/actual batch由Java gate保證，Lua再要求incoming date較新，或同日current精確TWSE/LIVE/open且 `incomingTuple > currentTuple`；同日 older/equal 即使來自 Fubon 也必須拒絕。WRITTEN/TAKEOVER才同script SET/SADD/EXPIRE/PUBLISH；其他outcome零副作用。禁止JVM GET→compare→SET，NOSCRIPT只可原子EVAL。

- [x] **353.9 tick副作用只在WRITTEN後。**Lua回WRITTEN/PROVIDER_TAKEOVER後才append tick；time用provider Instant。其他outcome不append/publish/refresh TTL。Fubon完整day high/low不更新dayhl key；existing路徑不變。append失敗時主price已成功，只increment固定`TICK_APPEND_FAILED`counter＋structured reason，不回滾；完成報告明示跨key非原子邊界。

- [x] **353.10 PricePoller批次與失敗語意。**existing collection/cron/0000不變，但known gate與provider dispatch須抽成上述四入口共用的台股窄路徑，且位於任何Fubon/MIS live HTTP前；`updatePrices`不得成為tw-radar繞過strategy的後門。known false回`MARKET_CLOSED`，authority empty/throw回`MARKET_UNKNOWN`（名稱可等價），兩者皆零live HTTP/Redis，即使cache missing亦同。dispatcher 對正常四入口每輪先限 100 codes；disabled existing provider 內部 `fetchTwBatch` 保留 320 hard cap 作為直接呼用的防禦層，不表示排程輪可超過 100。partial逐success寫、failure保舊。固定enum counters/summary分開marketClosed與marketUnknown，不引Micrometer/host metrics。

- [x] **353.11 ETF join與其他來源不變。**`PriceCacheReader`仍對`price:etfnav:台股:{code}`做best-effort join，故public第19欄`premiumDiscountPct`語意不變，Fubon/Python不讀寫該key。台股17:30 NAV、official post-close reconcile/FinMind、歷史回補、TPEX指數、ETF iNAV/premium、基本面、新聞、FX、美股/英股provider全不改；不得把`closePrice`欄名誤認成official settled close，也不得取消盤後來源對帳。

- [x] **353.12 enabled-no-scrape、四入口known gate與disabled tests。**對四入口做入口層parameterized matrix，而非只測provider method。每入口在enabled/disabled兩模式各驗known true下selected-provider success/failure、date authority false、盤外、authority `Optional.empty()`與authority throw；逐案assert Fubon/MIS HTTP、writer、tick、pubsub call count。enabled success只Fubon；enabled failure仍MIS/Yahoo=0且失敗code零寫；disabled known true只MIS且Fubon=0；false/empty/throw兩mode的live provider與全部Redis副作用皆0，並分辨`MARKET_CLOSED`／`MARKET_UNKNOWN`，仍可沿用既有盤外DB/official-close讀取。另直接反例證明legacy `isTwMarketOpen()`即使fail-open true也不能產生authorized。missing token context healthy且零HTTP/Redis。

- [x] **353.13 parser/mapper矩陣。**所有成功fixture使用官方raw `previousClose/openPrice/highPrice/lowPrice`並驗normalized/public同名值；另以只有`previousClose/open/high/low`的payload釘住缺官方price-suffixed keys而拒絕。既有市場pair/trial/actual/time/OHLC/cache/budget cases外，精確加入decimal `"9999999999.9999999999"`（precision20/scale10）接受、`"10000000000.0000000000"`（precision21）與`"0.00000000001"`（scale11）拒絕、`"0.1"`原樣BigDecimal、volume 0/9,223,372,036,854,775,807接受及負值/9,223,372,036,854,775,808拒絕；每個拒絕case零Redis/tick/pubsub。

- [x] **353.14 Redis integration不是mock。**真Redis執行production Lua首先驗current missing＋`marketOpenAuthorized=false`回MARKET_CLOSED，且以錯誤arity/wrong-type key反證authorization早於任何Redis operation；value/index不存在、TTL不建立、pubsub/tick=0，existing current盤外亦bytes/TTL全不變。authorized=true才驗missing→written、malformed fail closed、Fubon older/equal no side effect、newer一次；另直接script harness覆蓋wrong-type latest/index、非法TTL、malformed或JSON/ARGV mismatch，以及FUBON來源VERIFIED_CLOSE＋newer LIVE拒絕。每個拒絕案都驗payload bytes、index member/type、value/index TTL、publish count與tick不變，不能只看outcome。同日 TWSE/LIVE/open 只有 Fubon `incomingTuple` 嚴格較新才恰一次 takeover，older/equal、official/unknown/任一flag false均不可接管。保留concurrent max-tuple、NOSCRIPT原子重載測試。

- [x] **353.15 API/排程/結構不回歸。**`PriceCacheReaderTest`與`PublicQuoteControllerTest`精確驗19 properties（含nullable premium）、source/provider timestamp、list/one status；OpenAPI example/schema不改欄數或欄名。此任務不新增`@Scheduled`；main Task351只改交易日曆描述而不加job，`SchedulePublicBffController.JOBS`仍是inventory Task352新增後的56筆=business22+external34。若落地時基線尚無Task352，實作者不得硬寫56，而應先完成前置任務再落本任務。無DB schema/Liquibase。

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh

# Python adapter contract（包含login→init_realtime、trial/microseconds/cache/budget）
docker buildx build --platform linux/amd64 --target test \
  -f fubon-broker-service/Dockerfile fubon-broker-service \
  --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q

# external unit + 真Redis Lua integration；integration test不可被silent skip
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true \
  -Dfubon.redis.integration.required=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 靜態防線：Fubon程式不得引用禁止的snapshot或在Python寫Redis
if rg -n 'query_symbol_snapshot|snapshot/quotes|redis.Redis|RedisTemplate' \
    fubon-broker-service/src; then
  echo 'Forbidden Fubon snapshot/Redis path found' >&2; exit 1
fi

# 無cache正式重建；default disabled 必須保留既有stack相容性
docker compose -p asset-management build --no-cache \
  fubon-broker-service external-materials-service business-services bff
FUBON_ENABLED=false docker compose -p asset-management up -d --no-deps --force-recreate \
  fubon-broker-service external-materials-service business-services bff
docker compose -p asset-management restart bff
docker compose -p asset-management ps
for c in asset-fubon-broker-service asset-external-materials-service asset-business-services asset-bff; do
  test "$(docker inspect "$c" --format '{{.State.Health.Status}}')" = healthy
done
test -z "$(docker port asset-fubon-broker-service)"
fubon_image=$(docker inspect asset-fubon-broker-service --format '{{.Image}}')
test "$(docker image inspect "$fubon_image" --format '{{.Architecture}}')" = amd64
docker exec asset-fubon-broker-service python -c \
  'from fubon_neo.sdk import FubonSDK; print("FUBON_SDK_IMPORT_OK")'

# 公開19欄相容：若現有台股cache非空，每列keys必須精確包含這19欄
quote_tmp=$(mktemp -d)
trap 'rm -rf "$quote_tmp"' EXIT
status=$(curl -sS -o "$quote_tmp/list.json" -w '%{http_code}' --get \
  'http://127.0.0.1:9090/api/quotes' --data-urlencode 'market=台股')
test "$status" = 200
jq -e 'all(.[];
  ([keys[]] | sort) ==
  (["stockCode","market","price","previousClose","priceChange","changePercent",
    "buyPrice","sellPrice","openPrice","highPrice","lowPrice","volume","stockName",
    "source","tradingDate","updatedAt","closed","quoteStatus","premiumDiscountPct"] | sort))' \
  "$quote_tmp/list.json"
# 空 list 會 vacuous pass；19 欄 shape 的主要證據是上方 controller/OpenAPI tests，
# runtime 只驗當下實際存在的 rows，完成報告須記錄 list length。

# enabled但缺secret：明確改掛本輪isolated empty dir，不能假設開發機沒有真secret
# feature fail closed、Python仍alive、external不得restart；no-scrape由spy tests作直接證據
mkdir -p "$quote_tmp/empty-fubon"
FUBON_ENABLED=true FUBON_SECRETS_DIR_HOST="$quote_tmp/empty-fubon" \
  docker compose -p asset-management up -d --no-deps --force-recreate \
  fubon-broker-service external-materials-service
test "$(docker inspect asset-fubon-broker-service --format '{{.State.Health.Status}}')" = healthy
docker exec asset-fubon-broker-service python -c \
  'import json,urllib.request; d=json.load(urllib.request.urlopen("http://127.0.0.1:8080/internal/health")); assert d["configState"]=="MISCONFIGURED"'
test "$(docker inspect asset-external-materials-service --format '{{.RestartCount}}')" = 0

# 驗證結束恢復安全預設，避免共享stack持續處於enabled/misconfigured
FUBON_ENABLED=false docker compose -p asset-management up -d --no-deps --force-recreate \
  fubon-broker-service external-materials-service
```

有安裝真實 secrets 且台股開盤時的 production 驗收：

- [ ] 先確認Python health=`READY`；以容器內讀token的腳本呼叫同一code normalized endpoint兩次，response須`isTrial`不為true且具有`lastTrade.price+time`或`closePrice+closeTime` actual pair，source time為16位microseconds正確解出的provider Instant，且price不取lastPrice。
- [ ] 跨至少兩個2分鐘poll slot讀`price:台股:{code}`與host `/api/quotes/one`：source=`FUBON_INTRADAY`、closed=false、quoteStatus=LIVE、19欄不退化，updatedAt等於provider trade time轉台北ISO而非receipt time；新成交時tuple前進，無新成交時舊payload/TTL不因equal observation刷新。
- [ ] 以可控先後順序送older/equal/newer fixture到production writer internal test seam或integration test，證明older/equal不publish、newer一次publish；不得直接改production Redis造假後忘記還原。
- [ ] 以fixed counters、test spy與structured log correlation證明enabled poll沒有TWSE MIS/Yahoo請求。若休市、無secret或官方權限未啟用，完成報告明列live stage未驗，不得用disabled cache或fixture冒充富邦實盤。

## 完成報告

**完成日期：** 2026-08-22

**變更檔案：** 主要落地已於前一輪（commit `6e3f6bb8`「完成富邦庫存同步與台股行情整合」，merge `1e1cf5cc`）
完成，涵蓋 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/{client/
FubonNormalizedQuoteClient,client/FubonNormalizedQuoteMapper,service/ExistingTwLiveQuoteProvider,
service/FubonTwLiveQuoteProvider,service/MarketClock,service/PriceCacheWriter,service/PricePoller,
service/ProviderTimedPriceObservation,service/TwLiveQuoteBatchResult,service/TwLiveQuoteDispatcher,
service/TwLiveQuoteOutcomeCounters,service/TwLiveQuoteProvider,service/TwRadarRefreshService}.java`、
`external-materials-service/src/main/resources/redis/provider-timed-price-write.lua`、對應完整測試矩陣
（`TwLiveFourEntryMatrixTest`、`FubonNormalizedQuoteClientTest`、`FubonTwLiveQuoteProviderTest`、
`MarketClockTwKnownTest`、`PriceCacheWriterFubonRedisIntegrationTest`、`PricePollerTpexContractTest`、
`PriceCacheReaderTest`、`PublicQuoteControllerTest`、`TwRadarRefreshServiceTest` 等）。本輪只針對稽核
發現的一個真實測試缺口做補強：
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/
  FubonNormalizedQuoteClientTest.java`：新增 `volumeLowerBoundZeroIsAcceptedAsValidObservation()`，
  補上 volume=0（合法下界）在 Java 端重驗 limits 邏輯的接受案例（先前只有 Python adapter 端測過，
  Java 這一層只測了 `Long.MAX_VALUE` 成功案例與負值/overflow 拒絕案例，缺 0 這個下界的正面斷言）。
  未動 `FubonNormalizedQuoteMapper.java` 主程式——核對第 60-65 行既有驗證邏輯（`volume < 0` 才拒絕）
  本就正確接受 0，純屬補測試覆蓋，非 bug fix。

**測試結果：**
- `mvn -f external-materials-service/pom.xml test`：**539 run / 0 failures / 0 errors / 0 skipped**
  （含新增的 `volumeLowerBoundZeroIsAcceptedAsValidObservation`；`FubonNormalizedQuoteClientTest`
  單獨執行為 7 run / 0 failures）。
- `docker run asset-fubon-broker-service:test pytest -q`（`--no-cache` 重build 後）：**92 passed**。
- `mvn -f bff/pom.xml test`：**156 run / 0 failures**。
- `bash scripts/spec-check.sh`：PASS（BLOCK 0 / CHECK 0）。
- 未執行 `-Dfubon.redis.integration.required=true` 這個特定 flag 的獨立跑法，但完整
  `external-materials-service` test suite（含 `PriceCacheWriterFubonRedisIntegrationTest`，使用
  Testcontainers 真實 Redis，非 mock）已在上面 539 run 內全數執行並通過。

**Provider mode證據：** `TwLiveFourEntryMatrixTest`（4 入口 × enabled/disabled × 6 情境＝48 案）逐案
verify Fubon/MIS client 呼叫次數、`writer.write`/`writeProviderTimed` 呼叫次數、`MarketClock.
isTwMarketOpen()`（legacy fail-open 方法）從未被呼叫；`MarketClockTwKnownTest.
legacyFailOpenBooleanCannotAuthorizeKnownGate()` 直接反證 legacy fail-open true 無法產生
`authorized`。tick／pubsub 的實際副作用（隨 outcome 而定）改由 `PriceCacheWriterFubonRedisIntegrationTest`
在真實 Redis 上按 `ProviderWriteOutcome` 全枚舉（`WRITTEN`/`PROVIDER_TAKEOVER`/`MARKET_CLOSED`/
`STALE_OR_EQUAL`/`CURRENT_MALFORMED` 等）逐一驗證——兩份測試合起來才是完整證明鏈（見下方「與規格
偏差」第 2 點）。

**Docker／LIVE證據：** image `amd64` architecture（buildx `--platform linux/amd64`）；容器內
`from fubon_neo.sdk import FubonSDK` import 成功；`docker port asset-fubon-broker-service` 為空（無
host port）；`/internal/health` 在 `FUBON_ENABLED=false`（預設）時仍回 `status=UP`。**台股開盤時的
LIVE 驗收未執行**——本次未安裝真實 secret、未在交易時段觀察 `price:台股:{code}` 的實際 provider
timestamp/tuple 遞增行為，t353 底部「有安裝真實 secrets 且台股開盤時的 production 驗收」四項
checkbox 維持未勾選。

**與規格偏差：**
1. **353.13（本輪修復重點）**：稽核發現 Java 端 `FubonNormalizedQuoteClientTest` 缺 volume=0 的接受
   案例，本輪已補上（見上方變更檔案）。
2. **353.12（tick/pubsub call count 的組織方式）**：規格原文要求「逐案assert Fubon/MIS HTTP、writer、
   tick、pubsub call count」。`TwLiveFourEntryMatrixTest` 對 `PriceCacheWriter` 整支使用 Mockito mock
   （避免 48 案全部起真實 Redis 造成測試緩慢/脆弱），故該檔案本身只能斷言「writer 方法被呼叫幾次、
   帶什麼 outcome-driving 條件」，無法在同一測試內獨立斷言 tick/pubsub（那是 mock 內部從未真正執行
   的邏輯）。tick/pubsub 的正確性改由 `PriceCacheWriterFubonRedisIntegrationTest`
   （`authorizedMissingWritesValueIndexTtlsPublishAndProviderTimedTick` 等測試名稱）在真實 Redis 上
   按 outcome 全枚舉逐一驗證。兩份測試合起來完整覆蓋「四入口正確決定 outcome」＋「outcome 正確決定
   tick/pubsub」，是刻意的測試金字塔分工，未在 entry matrix 內重覆起真實 Redis。
3. 除上述兩點外，稽核逐條核對 353.1–353.11、353.14、353.15（含 19 欄相容性、OHLC/decimal/volume
   range、tri-state known gate、Lua market-open/preflight/takeover、schedule catalog 56=22+34 非
   回歸）均與現有程式碼/測試相符，無其他偏差。
