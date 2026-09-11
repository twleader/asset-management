# [t425] 富邦分價量 Redis 快照與盤後歷史日 K PostgreSQL 落庫

**對應 Requirements:** Requirement 147（當日分價量每分鐘只進 Redis；歷史日 K 在 15:35 寫入 PostgreSQL；交易雷達讀已保存資料）
**前置任務:** t408（富邦市場資料窄 adapter、雷達範圍與 immutable fact 慣例）
**Liquibase changeset:** `v1.128.0-fubon-volume-and-daily-candle.sql`

## 背景

富邦「個股當日分價量」與「個股歷史日 K 線」都只可唯讀查詢。前者是盤中的暫態累計值，使用者決定只保存目前快照到 Redis；後者是盤後完成的日資料，必須完整保存到 PostgreSQL。交易雷達需要使用這些資料時，只能從 Redis 或 PostgreSQL 讀取，禁止由請求路徑直接打 Fubon。

## 要做什麼

- [ ] **425.1 broker exact routes。** 在 Python 的既有 market-data v1 authorization／strict JSON gate 內新增兩條 token-protected POST：`/internal/market-data/intraday-volumes/read` 接受唯一 `{"symbol":"2330"}`，呼叫 ordinary-lot `intraday.volumes(symbol)`；`/internal/market-data/historical-daily-candles/read` 接受唯一 `{"symbol":"2330","from":"YYYY-MM-DD","to":"YYYY-MM-DD"}`，date span 為 inclusive 1..366 日，呼叫 `historical.candles` 的固定日線 arguments `timeframe="D", adjusted=False, fields="open,high,low,close,volume,turnover,change", sort="asc"`。unknown field、duplicate key、query、null／不合法代號、非法日期、range 不合法都必在 SDK 前拒絕；不新增 GET、alias、trailing slash、public route、manual endpoint 或任何 order SDK import/call。
- [ ] **425.2 strict normalized envelopes。** 分價量回應固定帶 schemaVersion、symbol、market=`台股`、provider=`FUBON_SDK`、sourceDate、observedAt、exchange（僅 TWSE／TPEx／ESB）、nullable sourceMarket、status、nullable reason、levels；HTTP 200 的 OK levels 必為依 price 嚴格升序且不重複的非空集合，每列為 positive decimal price、non-negative signed-64 volume、nullable non-negative bid／ask volume；NO_DATA 只能是空 levels。日 K 固定帶 queryFrom/queryTo、同一 identity／observedAt、status／reason／candles，每列為 query range 中嚴格升序不重複的 tradingDate、正 OHLC、non-negative volume／turnover 及 signed change；high >= open/close >= low。INVALID_RESPONSE、RATE_LIMITED、UPSTREAM_ERROR 皆是 typed non-2xx error，不存 raw SDK object 或 Redis snapshot。任一 identity、日期、時間、欄位、number canonicality 或完整性不符，整次拒絕。
- [ ] **425.3 Redis only 的分價量同步。** external client／port 只可呼叫上述第一條 route。新增專用 Redis writer/read cache，不得使用 generic price、tick、latest quote key，也不得建立任何 PostgreSQL 分價量／capture／level table。每個有效雷達代號在已知台股交易日、台北 09:00–13:30、`FUBON_INTRADAY_PRICE_VOLUME_SYNC_ENABLED=true`、Fubon READY 與 shared single-flight 都成立時，由 `@Scheduled(cron="0 * * * * *", zone="Asia/Taipei")` 每分鐘查一次；空／非法／錯誤 radar scope 零外呼。key payload 保留整個 normalized snapshot，具固定有限 TTL，且只可由同一 sourceDate 且 strictly newer observedAt 覆寫；過日或舊值不能覆寫。單檔失敗隔離，只有 typed stop-run 才停止未開始者。
- [ ] **425.4 PostgreSQL only 的歷史日 K 同步。** external client／port 只可呼叫第二條 route。交易日 15:35、`FUBON_HISTORICAL_DAILY_CANDLE_SYNC_ENABLED=true`、Fubon READY、calendar 與 valid radar scope 都成立時，cron 0 35 15 * * MON-FRI、Asia/Taipei 每檔查今天結束的一年日線。新增 migration 的 immutable `fubon_historical_daily_candle`，其 primary key、canonical payload hash、完整 DDL、immutable trigger 與 same-hash/different-hash atomic outcome 一律依固定實作契約；不得保留第二個同 natural key fact。日 K 成功保存後才可投影到 `stock_price_history`；只要 close_source 是 `TWSE_MI_INDEX`／`TPEX_DAILY_CLOSE`／`FINMIND_TW_CLOSE` 就 immutable fact only，否則依固定實作契約的 guarded upsert 投影 OHLC／volume／close 與 `FUBON_SDK` source。turnover/change 絕不寫入 canonical history 或 Redis。
- [ ] **425.4a 固定查詢區間。** 日 K 排程固定使用 queryTo=Taipei 當日、queryFrom=queryTo.minusDays(365)，剛好 inclusive 366 日並可處理 2 月 29 日；任何其他「一年」演算法均不可使用。
- [ ] **425.5 雷達讀取邊界。** business、BFF、交易雷達以及任何其 request path 的 reader，分價量只讀 dedicated Redis same-day/fresh snapshot，日 K 只讀 `stock_price_history` 或 immutable PostgreSQL fact；不得注入／呼叫 Fubon SDK、broker HTTP、external scheduler、refresh writer、subscription 或 dispatcher。miss、stale、unavailable、conflict 都走既有缺值／fail-soft 設計，不作 request-time 外呼或資料寫入。
- [ ] **425.6 catalog、schedule、configuration。** Compose 將兩個 non-secret flags 預設為 false 並只傳給 external-materials-service；不改 .env／secret。Fubon API 目錄的當日分價量與歷史日 K 項目都改 connected，確切描述 internal route、Redis 或 PostgreSQL target、雷達範圍與排程。SchedulePublicBffController 增加兩個 external jobs（一分鐘分價量、15:35 歷史日 K），同步更新其回歸計數與 contract test；不新增 public、9090、Tailscale、frontend 或 manual route。
- [ ] **425.7 tests 與 schema。** Python tests 覆蓋 exact path/method、token/strict-request rejection、fixed SDK arguments、normalization、sanitized reason 和 zero order namespace；external tests 覆蓋 radar-only、Redis same-day/newer-only/TTL、minute/15:35 schedule gate、partial/stop-run、no raw payload。PostgreSQL tests 覆蓋 migration、daily fact insert/unchanged/conflict、trusted source projection block；backend tests 釘 request-time 只讀 cache／DB、零 Fubon client/HTTP。重產 `db/schema.sql` 並以 schema drift test 驗證。Docker 驗收所有兩個 Fubon flags 為 false，僅使用 fixture，不得真人 SDK、帳戶查詢或任何券商寫入。

### 固定實作契約

- 分價量回應根欄位依序固定為 schemaVersion、symbol、market、provider、sourceDate、observedAt、instrumentType、exchange、sourceMarket、status、reason、levels；日 K 根欄位依序固定為 schemaVersion、symbol、market、provider、queryFrom、queryTo、observedAt、instrumentType、exchange、sourceMarket、status、reason、candles。schemaVersion 為 JSON 整數 1，instrumentType 必為 JSON string EQUITY。所有價格、量與金額均為不含指數記法的十進位字串，precision 至多 20、scale 至多 10；volume 必為 0 至 Long.MAX_VALUE 的整數字串；sourceMarket 為 null 或 ASCII 1..20 字元。超界值必以 INVALID_RESPONSE 在持久化前拒絕。observedAt 是完整 broker 回應已收取後的 UTC ISO-8601 時間；sourceDate 只屬分價量、是 broker payload 的 Taipei 行情日，日 K 則以每列 tradingDate 為行情日。
- levels 每項必且僅可為 price、volume、bidVolume、askVolume：price 為正 canonical decimal JSON string；volume 為非負 signed-64 integer JSON string；bidVolume、askVolume 各為 null 或非負 signed-64 integer JSON string。candles 每項必且僅可為 tradingDate、open、high、low、close、volume、turnover、change：tradingDate 為 YYYY-MM-DD JSON string；OHLC 為正 canonical decimal JSON string；volume 為非負 signed-64 integer JSON string；turnover 為非負 canonical decimal JSON string；change 為 null 或 signed canonical decimal JSON string。日 K 的 OK 必為非空 candles 且 reason=null；NO_DATA 必為空 candles 且 reason=NO_DATA。root 與每個 child object 都必須拒絕 missing、unknown、duplicate fields。
- HTTP 200 snapshot 的 status 僅可為 OK 或 NO_DATA：OK 必為非空、依 price 嚴格升序且不重複的 levels，reason 為 null；NO_DATA 必為空 levels，reason 為 NO_DATA。INVALID_RESPONSE、RATE_LIMITED、UPSTREAM_ERROR 僅能以 typed non-2xx error 回應，不能作為 Redis payload，也不得覆寫既有快取。
- 兩個同步工作都必須以 FubonRadarScope.current(30) 取得範圍；多於 30 檔時以 RADAR_LIMIT_EXCEEDED fail closed，零 SDK 呼叫、不得部分或 round-robin。每個入選代碼每輪僅有一次 scheduler 邏輯 route invocation；既有一次 auth-invalid retry 可保留，且同樣計入每分鐘歷史額度與 rolling 60 秒最多 38 次 actual SDK starts。兩者共用既有五個 blocking slot；RATE_LIMITED 或 HTTP 429 停止整輪且 Java 不重試。
- 分價量只可使用 Redis key fubon:intraday-price-volume:台股:{symbol}；TTL 固定 18 小時，Lua 只容許同一 sourceDate 且新 observedAt 嚴格較晚的 OK 或 NO_DATA snapshot 覆寫。Radar 只可在 sourceDate 為 Taipei 今天、status 為 OK，且 observedAt 介於 now-minus-two-minutes 和 now-plus-30-seconds 時採用，否則視為無資料；不允許 Radar 或任何 reader request-time 呼叫 Fubon。
- 日 K 只可使用 PostgreSQL fubon_historical_daily_candle，primary key 為 stock_code、market、trading_date，payload_hash 是 NOT NULL 一般欄位；同 natural key/same hash unchanged，不同 hash 必以 atomic insert conflict 回 typed conflict 且不插入第二筆。完整欄位與型別為 stock_code varchar(20)、market varchar(20) check market='台股'、provider varchar(32) check provider='FUBON_SDK'、trading_date date、exchange varchar(10) check exchange in ('TWSE','TPEx','ESB')、source_market varchar(20) nullable、open/high/low/close numeric(30,10)、volume bigint、turnover numeric(30,10)、price_change numeric(30,10) nullable、observed_at timestamptz、schema_version integer check schema_version=1、canonical_payload jsonb、payload_hash char(64) check lowercase SHA-256、created_at timestamptz；除 source_market/price_change 外均 NOT NULL，OHLC 全正且 high/low 關係正確，volume/turnover 非負。加入阻止 UPDATE、DELETE 的不可變更 trigger。hash bytes 固定為 FUBON_HISTORICAL_DAILY_CANDLE_FACT_V1 加換行加上依鍵排序 canonical JSON，JSON 僅含 schemaVersion、symbol、market、provider、tradingDate、exchange、sourceMarket、open、high、low、close、volume、turnover、change，排除 observedAt/createdAt；FubonCanonicalHash 新增完全相同的方法與 golden fixture。
- 每個可用日 K 先獨立提交 immutable fact，才進行 stock_price_history 投影。migration 必須驗證並復用既有 stock_code、market、trading_date unique constraint，不得進行不必要或破壞性的 duplicate rewrite。FUBON 投影是單一 INSERT ON CONFLICT guarded upsert，不得先 SELECT；只允許新列或目前 close_source 不在精確集合 TWSE_MI_INDEX、TPEX_DAILY_CLOSE、FINMIND_TW_CLOSE 時更新。官方收盤 writer 同樣必須原子 authority upsert，使官方資料並行到達時獲勝。任何投影 skip 或錯誤不得回滾已提交 fact。
- 將這兩條 exact broker route 納入 catalog：stock/intraday/volumes、stock/historical/daily-candles。Fubon catalog 斷言更新為總數 52、已接 23、未接 29；Schedule 斷言更新為 business 28、external 39、BFF 1、總數 68。
- Redis tests 必須證明較新的合法 NO_DATA snapshot 可取代舊 OK 或舊 NO_DATA；過日、錯 identity、非合法空 payload、typed error、相同或較舊 observedAt 均不得覆寫。

## 驗證

```bash
bash scripts/spec-check.sh
pytest -q fubon-broker-service/tests
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f bff/pom.xml test
bash scripts/tests/schema-sql-drift-test.sh
```

Docker 驗收只重建 fubon-broker-service、external-materials-service、business-services 與 bff；等待健康後確認 schema drift 回 0、既有交易雷達讀取 JSON 成功，並以 flags false 的 container logs 證明沒有 Fubon SDK request。不得修改 .env、secrets、帳戶資料或下單。

## 完成報告

（實作者完成後回填：實際變更、Redis／PostgreSQL readback、測試、container health、schema drift 與偏差。）
