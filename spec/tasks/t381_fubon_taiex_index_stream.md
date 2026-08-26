# [t381] 富邦 `indices` WebSocket 即時持久化台股大盤點數

**對應 Requirements:** Requirement 116（富邦官方 `indices` WebSocket 的台股大盤點數以來源微秒時間嚴格更新 PostgreSQL 與既有 Redis latest cache）
**前置任務:** t370（台股 latest Redis strict-newer）、t380（富邦 Linux adapter 的 token／read-only boundary）
**Liquibase changeset:** v1.118.0-fubon-taiex-index-stream.sql

## 背景

現行 `TaiexIndexPoller` 每兩分鐘從 Yahoo `^TWII` 五分鐘 K 線把盤中台股大盤寫入 `price:台股:0000`；它不寫 event-state DB，且不應污染盤後唯一權威 `twse_index_daily_history`。富邦官方 realtime API 有 `indices` channel，事件帶來源微秒 timestamp 和 index point，可更即時更新同一個 latest cache。

此交付不是交易、下單、持股、成交、庫存或 9090 API 擴充。富邦 proprietary SDK 只能存在 `fubon-broker-service`，而該 adapter 被明確禁止寫 PostgreSQL／Redis 或反向呼叫 Spring。因此不可讓 Python 收到 callback 後 POST 到 external-materials。正確方向是：Python adapter 維持官方 WebSocket，對已建立的、受 token 保護 internal SSE GET 回應資料；`external-materials-service` 主動連上這條 SSE，並獨自完成 DB-first persistence 和 Redis projection。

設定的台灣加權指數 symbol 不是可安全硬編碼的常數。部署者須先用富邦 HTTP `intraday/tickers?type=INDEX&exchange=TWSE` 驗證 symbol 後填進環境；官方文件中的 example 不能當成預設 TAIEX symbol。

## 要做什麼

- [ ] **381.1 新增明確且 fail-closed 的設定。** 在 `.env.example`、`docker-compose.yml`、external 的 `application.yml` 和 Python config／startup path 加入 `FUBON_TAIEX_INDEX_STREAM_ENABLED`（預設 `false`）和 `FUBON_TAIEX_INDEX_SYMBOL`（預設空），兩個 service 都須得到相同值。唯一有效條件是 `FUBON_ENABLED=true && FUBON_TAIEX_INDEX_STREAM_ENABLED=true && valid nonblank symbol && ready shared-token/config`；`FUBON_ENABLED=false`、stream flag=false 或缺／非法 symbol 時 health 維持 UP，但 Python zero SDK WebSocket、Java zero token-file read／SSE GET／retry、zero PostgreSQL／Redis mutation。只有這些 non-secret gates 通過後，Java 才可 lazy、non-logging 讀取 token file 判斷 ready；token missing／empty 要 typed sanitized misconfiguration，仍 zero SDK／SSE GET/retry／DB／Redis。true 時 symbol 必為 nonblank、ASCII、最長 64 字元的 bounded market identity。說明部署者先以 `intraday/tickers?type=INDEX&exchange=TWSE` 確認真正台灣加權指數 symbol；不得以 vendor 文件示例 fallback。

- [ ] **381.2 Python adapter 只持有一組 readonly official `indices` subscription。** 在 `fubon-broker-service` 新增小而獨立的 index-stream component（可命名 `taiex_index_stream.py`），透過既有 `SdkGateway` 同一個 SDK realtime session 取得 `sdk.marketdata.websocket_client.stock`；只使用官方 realtime callback／connect／subscribe `{'channel': 'indices', 'symbol': configuredSymbol}`，不可 import、包裝或呼叫任何 order API。每個 process 最多一組 configured-symbol subscription，首次成功授權的 SSE client 建立時才啟動或重用。處理 vendor `connect`、`disconnect`、`error` 和 `message`：disconnect/error 後使用有上限的 backoff reconnect，且每次 reconnect 都要重新 subscribe；shutdown 停止 loop、解除 callback／disconnect 並保留既有 SDK cleanup。SDK callback 不可做 DB、Redis、HTTP POST、Spring call、accounting 或慢 consumer I/O。

- [ ] **381.3 exact internal SSE contract、授權與無 raw-data 泄漏。** 在 `app.py` 加入唯一 exact `GET /internal/market-data/taiex-index/stream`，沿用現有 `X-Internal-Service-Token` constant-time authorization；缺 token 回 sanitized 401、錯 token 回 sanitized 403、disabled/misconfigured 回 sanitized 503。回應 headers 必有 `Content-Type: text/event-stream; charset=utf-8`、`Cache-Control: no-store` 和禁用 proxy buffering；不增加 docs/redoc/openapi、host port、gateway route 或 wildcard。只輸出：

  ```text
  event: taiex-index
  id: <source epoch microseconds>
  data: {"symbol":"<configured>","exchange":"TWSE","type":"INDEX","index":"<canonical positive decimal>","time":<positive integer>}

  ```

  message data 必有且僅有 `symbol/exchange/type/index/time`。adapter 在 callback 先 reject duplicate／unknown／missing fields、wrong configured symbol、非 `TWSE`／`INDEX`、bool/null/non-finite/scientific/non-canonical／非正 index、非正整數 microsecond time；vendor number 必不經 float round-trip，SSE `index` 一律轉 canonical decimal string，precision ≤20、scale 0–10、無 leading `+`／noncanonical leading zero。只把已驗證 immutable normalized payload fan-out 給已授權 subscribers。新 subscriber 可收到 process-memory 的單一 latest valid event 作快速恢復，但不可保留或宣稱 durable history。不得回傳 SDK raw object、credential、帳戶、portfolio、使用者或任何 order functionality。

- [ ] **381.4 external-materials 建立唯一 pull SSE consumer。** 新增 `FubonTaiexIndexStreamClient`（或同等 `SmartLifecycle` component），只在 381.1 的完整 effective condition 成立時，主動對 `http://fubon-broker-service:8080/internal/market-data/taiex-index/stream` 開一條帶既有 shared internal token 的 GET；main Fubon flag=false 時不得連 token file、不得發／retry GET。可將 `FubonNormalizedQuoteClient` 的 private token-file reader 抽成共用的小型 internal client utility，但 secret 不能在 log／exception／DTO 出現。只接受 exact `taiex-index` SSE event 和 exact JSON schema，duplicate／unknown／missing property、id 與 `time` 不一致、型別錯誤或 non-2xx body 都不可進 ingestion；`index` string 必與 381.3 相同 canonical precision/scale contract。transport EOF/error 或 sanitized 503 使用 bounded exponential backoff reconnect，不得 busy loop。此 client 不得呼叫 SDK、不能新增 public/internal business route、不能自行 DB／Redis write，且停止時取消持續 HTTP connection。

- [ ] **381.5 source-time validation 和 typed ingestion gate。** 新增 immutable typed index event／observation與 `FubonTaiexIndexIngestionService`。以 `time`（epoch microseconds）精確轉 `Instant`：`floorDiv(time, 1_000_000)` seconds 加 `floorMod(time, 1_000_000) * 1_000` nanos；不可用 HTTP receipt time 當 freshness。只接受 configured exact symbol、`TWSE`、`INDEX`、canonical positive string-to-`BigDecimal`（precision ≤20、scale 0–10；不接受 float／scientific／bool／null／leading-plus／noncanonical leading zero），並要求 provider instant 在 `Asia/Taipei` 的 LocalDate 等於 `Clock` 的今日且不大於 `Clock.instant()+30 seconds`。任何 identity/date/future/number/time error 都 zero DB／Redis write。`tradingDate` 必由同一 provider instant 推導。

- [ ] **381.6 DB canonical latest state 必須單 SQL strict-newer。** 新增 `backend/src/main/resources/db/changelog/changes/v1.118.0-fubon-taiex-index-stream.sql` 並在 master changelog 註冊一次。建立下列唯一 current-state table；不可寫入 owner/account/secret/raw SDK payload，不可增加 append history 或修改 `twse_index_daily_history`：

  ```sql
  CREATE TABLE fubon_taiex_index_latest (
      index_code          VARCHAR(20) PRIMARY KEY,
      provider_symbol     VARCHAR(64) NOT NULL,
      exchange            VARCHAR(20) NOT NULL,
      trading_date        DATE NOT NULL,
      provider_updated_at TIMESTAMPTZ NOT NULL,
      index_point         NUMERIC(30,10) NOT NULL,
      source              VARCHAR(32) NOT NULL,
      CONSTRAINT ck_fubon_taiex_index_code CHECK (index_code = '0000'),
      CONSTRAINT ck_fubon_taiex_index_exchange CHECK (exchange = 'TWSE'),
      CONSTRAINT ck_fubon_taiex_index_point CHECK (index_point > 0),
      CONSTRAINT ck_fubon_taiex_index_source CHECK (source = 'FUBON_INDICES'),
      CONSTRAINT ck_fubon_taiex_index_provider_date
          CHECK ((provider_updated_at AT TIME ZONE 'Asia/Taipei')::date = trading_date)
  );
  ```

  In external-materials 實作 `FubonTaiexIndexStore`。對 `index_code='0000'` 使用 `INSERT ... ON CONFLICT (index_code) DO UPDATE ... WHERE EXCLUDED.provider_updated_at > fubon_taiex_index_latest.provider_updated_at RETURNING ...`；禁止 Java read-compare-write。DB `NUMERIC(30,10)` 可容納 20 位整數＋10 位小數，且 adapter/Java source gate 仍限制 total precision ≤20／scale≤10，故不得由 DB rounding／放寬 wire value。此 store 必在獨立 transaction（`REQUIRES_NEW` 或等效 `TransactionTemplate`）**完成 commit 後**才回傳 `APPLIED` canonical row；ingestion wrapper 不得以 ambient outer transaction 包住 store＋Redis，且 Redis writer 不得在此 transaction 或 commit 前執行。結果型別要清楚區分 `APPLIED`、`STALE_OR_EQUAL`（回目前 canonical DB row）和 `FAILED`。較舊或相同來源時間不許改變 point、symbol、date 或 source；DB write／commit failure 必 zero Redis interaction。

- [ ] **381.7 DB committed first 對既有 latest Redis 的無 tick／無 day-HL projection。** 在 `PriceCacheWriter` 新增限定 `writeTaiwanIndexLive(PriceResult)`，驗證 `0000/台股/FUBON_INDICES`、positive timed source observation，呼叫既有 `price-cache-monotonic-write.lua` 的 `strict_newer=true`。其 payload 必為 `stockCode='0000'`、`market='台股'`、`source='FUBON_INDICES'`、`quoteStatus='LIVE'`、`closed=false`，`updatedAt` 是同一 provider instant；沒有 bid/ask、volume、tick、local day-HL merge，`highPrice/lowPrice/volume` 都 null。不得改 Lua 成較寬鬆的 comparator。現有同日 `VERIFIED_CLOSE` status rank 不得被 LIVE 降級；Redis 有較新 live timestamp 時不得倒灌。只在 381.6 successful-commit `APPLIED` 後、transaction 外寫 Redis；DB write／commit failure 時 zero Redis。原始 old/equal event 不得觸 Redis/TTL/publish/tick/day-HL；唯一 recovery 是 prior committed APPLIED row 的 Redis write 曾失敗且現在收到 exact-equal provider time，則只可 reread canonical DB row（不使用 raw input）並由 Lua 在 Redis missing／older 時前進。Lua 對 equal／newer 仍不覆寫、不 refresh TTL、不 publish。

- [ ] **381.8 保留 Yahoo fallback，Fubon DB state 優先。** 修改 `TaiexIndexPoller.updateOnce()`：在任何 `MacroDataFetchClient.fetchIndexIntradayDay("TWSE")` 之前，先 read-only 尋找今日 `fubon_taiex_index_latest`。若存在，從 DB canonical row 建立同樣 index `PriceResult`（previousClose 僅找嚴格早於交易日的 completed `twse_index_daily_history` row），呼叫 381.7 writer 做 cache repair 後 return，整輪 zero Yahoo call。若不存在，保留原本 Yahoo positive/time/today gate、previous completed close、`TWSE指數(5m)` result、無 tick／無 local day-HL 寫入的邏輯。stream disabled、SSE disconnected、事件被拒或 DB empty 時 Yahoo fallback 必繼續；不得清除 cache、造假數值或改盤後 completed daily history。

- [ ] **381.9 Compose/schema/documentation。** `docker-compose.yml` 只把兩個 new flags 傳給 `fubon-broker-service` 和 `external-materials-service`；adapter 依舊無 host port、readonly/non-root、只在 `asset-net`。更新 `spec/steering/structure.md` 以記錄 exact adapter SSE pull flow，並明載 adapter 仍不寫 DB/Redis、不 reverse call Spring。migration 由 `business-services` 套用後，依 `db/schema.sql` 檔頭的 schema-only pg_dump instructions 重產 `db/schema.sql`，更新 generated table count，並讓 drift check 完全比對通過。

- [ ] **381.10 必要測試。** 新增／擴充 deterministic tests，不以真富邦市場事件當測試資料：

  - Python fake SDK：`FUBON_ENABLED=false`／stream flag false／空 symbol zero SDK access；token 401/403；official callback wrong identity/type/exchange/invalid decimal/time（含 precision>20、scale>10、scientific/float）不產生 SSE；valid event 的 exact SSE headers/event/id/data；new subscriber latest replay；disconnect/error reconnect and re-subscribe；shutdown；無 order import/call、無 DB/Redis/Spring outbound。
  - Java stream parser/lifecycle：main flag false 時 zero token-file read／GET/retry；兩 flag true＋valid symbol 而 token missing/empty 時 lazy sanitized misconfiguration、zero SDK/GET/retry/write；only exact `taiex-index` accepted，duplicate/unknown/missing JSON、id mismatch、bad decimal limits、bad non-2xx/EOF/timeout 有 bounded reconnect且無 write；token reader never logs secret。
  - Ingestion/store：microsecond conversion（包含非整秒）、today/future+30s/date guard、positive canonical point/precision/scale gate（20-digit integer must persist unchanged）；real PostgreSQL/latch two-writer race proves newer wins; raw stale/equal does not mutate canonical; DB write/commit failure never invokes cache writer。
  - Redis: committed DB APPLIED writes exact FUBON_INDICES live payload; raw old/equal causes no DB/Redis/TTL/publish/tick/day-HL; equal canonical cache-repair works only after simulated prior Redis failure and only from reread DB row; a newer Redis row and a same-date verified close both reject Fubon live and do not get downgraded; index writer never invokes tick/day-HL collaborators。
  - Fallback: today Fubon row causes zero Yahoo request and cache repair from DB; no today row preserves Yahoo call; existing date gate and daily-history authority remain unchanged。

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh

/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q \
  -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

python -m pytest -q fubon-broker-service/tests

# Compose runtime must use the actual changed images; business-services applies Liquibase.
docker compose build --no-cache business-services fubon-broker-service external-materials-service
docker compose up -d --no-deps --force-recreate business-services fubon-broker-service external-materials-service
docker compose ps
docker exec asset-postgres psql -U assets -d assets -c "SELECT index_code, provider_symbol, exchange, trading_date, provider_updated_at, index_point, source FROM fubon_taiex_index_latest;"
docker exec asset-fubon-broker-service python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8080/internal/health').read().decode())"
docker exec asset-external-materials-service wget -qO- http://127.0.0.1:8080/actuator/health

# After migration has actually run, regenerate db/schema.sql using its checked-in header instructions.
bash scripts/tests/schema-sql-drift-test.sh
```

When the market is not naturally open, or when a real verified index symbol has not been configured, do not manually connect to the live Fubon feed and do not fabricate a production row. Record health, migration/schema readback, and deterministic fake-SDK/PostgreSQL/Redis evidence instead. A natural-market runtime observation, if one occurs, must verify only that DB and `price:台股:0000` have the same provider timestamp and that a later event advances it; it must never issue an order or force a broker request.

## 完成報告

（實作者完成後回填：actual changed files、feature configuration used, migration/schema/cache readback、tests/runtime output、market-state limitation，以及與本任務不同的取捨。）
