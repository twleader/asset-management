# [t461] 富邦盤中 1／5 分 KD、MACD、布林及 9090／SRPP 明細

**對應 Requirements:** Requirement 135（富邦技術指標來源與雷達 detail）及 Requirement 147（富邦盤中行情配額與 Redis cache）的 Task461 延伸
**前置任務:** t408、t425、t460
**Liquibase changeset:** 無

## 背景

現有富邦技術指標工作於收盤後執行，僅有 D/W profiles；交易雷達 detail 已有 `technicalResolution`，9090 已有唯讀 `GET /api/public/trading-radar/stock`，SRPP 的文件鏡像由 `docs/openapi/docker-external-api.yaml` 產生。使用者要在盤中提供 1 分、5 分 KD／MACD／布林通道，並將數值從 9090 detail 提供給 SRPP；每分鐘 60 次富邦 SDK market-data starts 足夠。

本任務只供分析／顯示，不把未完成 K 棒的值納入任何 Radar score 或 action。Request-time 不得呼叫富邦，數值由 external-materials-service 背景查詢後寫入 Redis，再由雷達和 9090 純讀。富邦 API 為唯讀用途，禁止下單／改單／撤單。

## 要做什麼

- [x] **461.1 Python exact route。** 新增 token-protected `POST /internal/market-data/intraday-technical-indicators/read`，exact request `{ "symbol": "2330" }`；拒絕額外／重複欄位、非法代碼、query、錯誤 method、未授權 token。method/profile/timeframe 不能由 caller 選擇。固定對 KD(9,3,3)、MACD(12,26,9)、BBANDS(20) 各呼叫 timeframe 字串 `"1"`、`"5"`，共 6 次 SDK reads；minute `from`／`to` 固定為 Taipei today−29 days／today。全部 SDK calls 經既有 `SdkGateway` method whitelist、history budget、blocking slot、auth retry 與 actual-start limiter；實作不得直接 import SDK 或 order namespace。將 market-data actual start rolling 60 秒上限由38更新為60；所有 technical/ticker/candle/volume/historical starts 及 authentication retry 均計入同一 gate，既有 history60/min gate保留。
- [x] **461.2 normalized bundle。** 對每個 timeframe 的三組回應各自驗 symbol、method／parameters、timeframe、source date/time、data list、numeric canonicality；以各 response 最後一列為最新點。官方 technical 文件未證明 minute `date` 必有時間精度：若三個 `date` 是 ISO 8601 含時區時間，必須同一 sourceTimestamp；若為日期字串，輸出 nullable `sourceTimestamp=null`，不得用 observedAt 假造 bar time。三組 sourceDate 必須相同、為台北當日。root 固定 schemaVersion、symbol、market=`台股`、provider=`FUBON_SDK`、observedAt、`oneMinute`、`fiveMinute`。每個 frame 固定 timeframe、sourceDate、nullable sourceTimestamp、observedAt、`kdj:{k,d,j}`、`macd:{macdLine,signalLine}`、`bollinger:{upper,middle,lower}`。只有 decimal 字串、precision≤38／scale≤18；拒絕非法／未來 timestamp、wrong date、parameter mismatch、缺欄或日期不一致。只輸出最新點，不保存或 relay 官方 30 日歷史 arrays，不自行產生 MACD histogram。六次任一失敗就整檔失敗，不能拼舊值或 partial success。
- [x] **461.3 external sync 與 cache。** 新增 `FUBON_INTRADAY_TECHNICAL_INDICATOR_SYNC_ENABLED`，Compose／`.env.example` 預設 false，只傳 external-materials-service。新增 token-client contract、typed DTO／parser、Fubon catalog item description、Redis writer/read/cache schema。錯誤日誌沿用既有 immutable `FUBON_TECHNICAL_INDICATORS_READ`／`技術指標查詢` operation identity，不增加 `api_error_log_operation` seed row 或 Liquibase migration；static `ApiErrorLogOperationCatalog.apiUrl` 同列既有 D/W route 與本次 1m／5m route，避免錯誤日誌介面顯示錯誤路徑。每分鐘 Cron `0 * 9-13 * * MON-FRI` Asia/Taipei；再 gate feature+FUBON enabled、SDK ready、known Taiwan trading day、09:00≤time<13:30。用 `FubonRadarScope.current(30)`；scope 空、超過30、錯誤或不合法時零 SDK calls。專用 Redis key `fubon:technical:intraday:tw:{symbol}:v1`；兩 timeframe 原子 Lua 更新，sourceDate 相同且 timestamp 不回退；若 sourceTimestamp 為 null，改以較新的 observedAt 判斷取代；成功 TTL 固定12分鐘，failure 不刷新 TTL。external-materials-service 對 Redis Testcontainers 的依賴固定為 1.21.4 且僅 test scope，與 backend 對齊以支援目前 Docker Engine API minimum 1.40；不得改 production dependency。
- [x] **461.4 公平排程與 60/min 上限。** 一檔完整技術 bundle 消耗六個基礎 actual SDK starts，auth retry 額外計數。Volume sync 啟用時每輪候選最多5檔，未啟用時最多10檔；這是無 retry 且共享 budget 足額時的候選上限。sorted radar codes 用 Redis cursor round-robin，不得每輪永遠取前段代碼。每 service single-flight；共享 gate 不足以完成一檔 bundle、429／RATE_LIMITED／timeout 時停止該輪剩餘工作，不寫失敗檔的 cache；游標下輪從下一代碼繼續，不永久重試同一首檔。不能由 Java retry。Python shared rolling 60 秒 market-data start gate 是硬上限，volume 和 technical 含 auth retry 的所有實際 starts 都計數；重試會減少技術指標更新量並可能令回應標示 STALE／UNAVAILABLE，全服務每分鐘絕不可超過60 starts。
- [x] **461.5 Radar cache-only read。** backend `RadarTechnicalResolver.preload` 將盤中技術資料納入既有 request-scoped batch，一次 MGET 所有雷達代碼，不逐檔 Redis read-through。Strict validation exact identity、today、TTL、時間與 age；age≤420秒標 AVAILABLE，超過420秒但仍在12分鐘 key TTL內標 STALE，miss／expired／corrupt／未來>30秒標 UNAVAILABLE。`TechnicalResolution` 增加 required nullable `intraday` child，含 root status/observedAt/ageSeconds 和 `oneMinute`／`fiveMinute` frames、三組數值與 nullable source timestamps。數值僅供 detail；D/W 100秒 freshness、V18、short／swing score/action/gates、alert、notification、valuation均不得改動。
- [x] **461.6 9090、UI 與 SRPP Swagger。** 交易雷達 UI 在 detail 顯示 1m／5m KD／MACD／布林數值、provider 提供時顯示 bar time、read time、age 和 AVAILABLE／STALE／UNAVAILABLE；若沒有來源 bar 時間，明確標示不可得；不可補零。既有 9090 `GET /api/public/trading-radar/stock` 僅新增 `stock.technicalResolution.intraday`，不新增 route、query、owner scope 或 request-time 外呼。更新 `docs/openapi/docker-external-api.yaml` schema/example、backend OpenAPI contract test 與 BFF strict public detail decoder contract test；decoder 必須驗證 required nullable `intraday` 及 exact root/frame/KDJ/MACD/Bollinger shape，並核對 root freshness 狀態、age 與兩個 frame 狀態的一致性：root AVAILABLE 時 age≤420 且 frame 皆 AVAILABLE；root STALE 時 age>420 或至少一個 frame STALE，若 age>420 則兩個 frame都須 STALE；各 frame status 也必須與可得的 sourceTimestamp／frame observedAt、root observedAt 與 age 相符；UNAVAILABLE 的可選欄位皆為 null。compact list schema 不變；`info.version` 1.15.0。執行 `ruby scripts/render-9090-openapi-docs.rb` 產生兩份 byte-identical Markdown，包括 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`。Fubon API 與 schedule 清單需反映六次 calls、1-minute Cron 和 session gate。
- [x] **461.7 安全。** 不修改任何 `.env`、secret、帳戶、下單能力或 public allowlist。Docker 實際驗收所有 FUBON flags false，fixture-only；不向真人券商發出 SDK request。

## 驗證

```bash
bash scripts/spec-check.sh
pytest -q fubon-broker-service/tests
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
```

驗收後依 `.agents/skills/run-stack/SKILL.md` 從正確 main／feature source 重建和 recreate 受影響服務，以 flags false 的執行中 stack 確認 health、existing 9090 stock detail response contract、Swagger 兩份 mirror、無 broker SDK starts；不作真人 Fubon query。沒有 SQL migration，無需重產 schema。

## 完成報告

- 實作：新增 Fubon 1m／5m KDJ、MACD、Bollinger cache-only 流程與跨路徑 rolling 60 秒 SDK start gate；9090 detail schema、Trading Radar UI 與 SRPP Swagger mirror 已同步。BFF strict decoder 驗證 required nullable `intraday` 及 root/frame/KDJ/MACD/Bollinger exact shape，並拒絕 root freshness status、age 與 frame 狀態矛盾的 payload，避免合法 detail 被轉成 502。
- 驗證：pytest 817 passed；external-materials-service 24 passed（含 Redis Testcontainers，無 skip）、backend 9 passed、BFF 55 passed（含 7 個新盤中 strict contract tests）；OpenAPI mirror/parity、schema drift、`git diff --check` 通過。`spec-check.sh` BLOCK 0，只有既有排程數提醒 CHECK 1。
- Docker：從 feature worktree 重建 BFF；重建後 healthy／actuator UP，最新映像 `sha256:205419067214353117dd953207dd328e5e2ccdf967b624be9f33541f8393c75c`，9090 雷達清單（38 檔）與個股明細皆回 200。個股回應含 `technicalResolution.intraday`；Task461 專屬同步旗標維持 false，無 cache 時為 `UNAVAILABLE` 且時間框子欄位皆為 null。其他既有富邦旗標保留原狀；這次驗收未呼叫 Fubon SDK、券商 API 或下單路徑。因旗標關閉，Docker 驗收未取得 AVAILABLE／STALE live frame，相關 timestamp/freshness 分支由 BFF contract tests 覆蓋；frontend HTTP 200 且 bundle 含盤中技術指標文字。
- 安全與文件：確認 Fubon flags false，驗收未發出任何 Fubon SDK／order 呼叫；兩份 9090 Markdown mirror byte-identical，SRPP tracked tree 未需額外修改。
