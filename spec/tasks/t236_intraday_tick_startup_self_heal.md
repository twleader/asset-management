# [t236] 服務啟動與讀取時自癒當日分時缺口

**對應 Requirements:** Requirement 13（股票分析當日分時完整性）
**前置任務:** t88 / t119 / t153 / t156
**Liquibase changeset:** 無

## 背景

`PricePoller` 只會從服務正在執行的時刻開始，每兩分鐘把即時成交 append 至 Redis。實機 2026-07-23 美股服務於 11:47 ET 啟動，GOOGL／VOO／VT／NVDA 的今日 bucket 都從 11:47 才開始，缺少 09:30–11:46；前一交易日 GOOGL 則有 79 筆完整涵蓋 09:30–16:00。既有 `IntradayTickRefresher` 已能用 Yahoo `chart?interval=5m&range=1d` 取得盤中截至目前的完整資料，實測 GOOGL 回 44 筆、09:30 起至當下，卻只在盤後 cron 或 LIST 完全為空時觸發；「非空但嚴重短少」不會自癒。

## 要做什麼

- [x] 236.1 `IntradayTickRefresher` 監聽 `ApplicationReadyEvent`，以背景 thread 檢查當下正在交易的市場；只對開盤中的市場收集既有 `StockSourceQuery.collectHeldStockCodes` 標的，並行呼叫既有 `refreshOne(code, market, today)`。盤外／非交易日不抓，避免把前一交易日資料誤寫今日。
- [x] 236.2 `IntradayTickStore` 提供純判定 `isIncompleteForSession(ticks, market, tradingDate)`：傳入的是 `getTicks` 已解析、已排除 malformed JSON 的序列；零筆有效 tick 等同 empty/incomplete。第一筆時間晚於該市場開盤後 5 分鐘，或任兩相鄰有效時間間隔大於 15 分鐘亦為 incomplete。session 開盤固定為台股 09:00、 美股 09:30、英股 08:00，日期與時間均為既有市場 wall time；只有一筆且時間未晚於容許開盤點時，不因筆數單獨判缺。
- [x] 236.3 `IntradayTickRefresher` 新增讀取用 `refreshOneGuarded(code, market, date, wait)`：以 `(market,code,date)` 記憶體 `ConcurrentHashMap` single-flight，同 key 併發共用同一 `CompletableFuture`、只發一次外呼；每次完成後成功／失敗皆冷卻 60 秒，冷卻中不重抓；狀態於冷卻期滿移除，避免無界成長。`wait=true` 最多同步等 8 秒，逾時／例外即返回、背景 future 繼續。`InternalPriceController.intradayTicks` 讀取「該市場今日且今日為交易日」bucket 時，若 236.2 判 incomplete，呼叫 guarded refresh 後重讀；過去日期維持既有「只有 empty 才 cold-start」，避免歷史資料的自然稀疏每次開圖都重抓。
- [x] 236.4 回補仍使用既有 `PriceFetchClient.fetchIntraday5m`：美股／英股 Yahoo 5m，台股 FinMind 5m 優先、Yahoo `.TW → .TWO` fallback；所有寫入走 `IntradayTickStore.replaceTicks` never-shrink。來源空／429／例外時保留既有 LIST，不新增 DB、Redis key、endpoint 或前端欄位。
- [x] 236.5 測試覆蓋：啟動時僅回補開盤市場；解析後空序列判 incomplete；第一筆準時且 gap ≤15 分鐘不回補；第一筆 11:47（美股）回補；內部 gap >15 分鐘回補；過去日期非空稀疏資料不回補；同 key 併發只一次外呼；429／失敗後 60 秒冷卻內不重抓；8 秒逾時回既有資料且 future 可續跑；外部回空時既有 LIST 不被清除。

## 驗證

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
bash scripts/spec-check.sh
git diff --check
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
docker compose -p asset-management restart bff
US_DATE=$(TZ=America/New_York date +%F)
US_TIME=$(TZ=America/New_York date +%H%M)
test "$US_TIME" -ge 1147 -a "$US_TIME" -lt 1600
KEY="price:ticks:美股:GOOGL:$US_DATE"
docker exec asset-redis redis-cli --raw LRANGE "$KEY" 0 -1 > /tmp/googl-ticks-before.jsonl
docker exec asset-redis redis-cli DEL "$KEY"
docker exec asset-redis redis-cli RPUSH "$KEY" "{\"t\":\"${US_DATE}T11:47:00\",\"p\":\"317.95\"}"
docker restart asset-external-materials-service
for i in $(seq 1 30); do
  test "$(docker inspect asset-external-materials-service --format '{{.State.Health.Status}}')" = healthy && break
  sleep 2
done
test "$(docker inspect asset-external-materials-service --format '{{.State.Health.Status}}')" = healthy
for i in $(seq 1 20); do
  test "$(docker exec asset-redis redis-cli LLEN "$KEY")" -gt 1 && break
  sleep 1
done
docker exec asset-redis redis-cli LLEN "$KEY"
docker exec asset-redis redis-cli --raw LINDEX "$KEY" 0
VOO_KEY="price:ticks:美股:VOO:$US_DATE"
docker exec asset-redis redis-cli --raw LRANGE "$VOO_KEY" 0 -1 > /tmp/voo-ticks-before.jsonl
docker exec asset-redis redis-cli DEL "$VOO_KEY"
docker exec asset-redis redis-cli RPUSH "$VOO_KEY" "{\"t\":\"${US_DATE}T11:47:00\",\"p\":\"676.93\"}"
docker exec asset-external-materials-service sh -c "curl -s 'http://localhost:8080/internal/intraday-ticks?code=VOO&market=%E7%BE%8E%E8%82%A1&date=$US_DATE'"
docker exec asset-redis redis-cli --raw LINDEX "$VOO_KEY" 0
docker exec asset-external-materials-service sh -c "curl -s 'http://localhost:8080/internal/health'"
curl -s http://localhost:8080/actuator/health
```

驗證須在美東 11:47–16:00 執行（命令會先守門，避免人工 11:47 成為未來 tick 而觸發 never-shrink）。先備份並刻意建立只含 11:47 的可重現不完整今日 bucket；重啟並 bounded 等待 healthy／回補後，第一筆須回到 09:30、筆數大於 1、末筆不得早於原 11:47。另以另一檔建立相同缺口並呼叫 `/internal/intraday-ticks` 驗證 read-time 自癒；成功時權威回補結果即為正確復原，若任一步失敗，立即以備份 JSONL 逐筆 `RPUSH` 回原 key（先 `DEL`）後再除錯，不得把人工單筆留在實機。自動測試提供 429／空來源不清空、single-flight、冷卻與 8 秒上限證據。服務與 BFF 均須 healthy。

## 完成報告

完成啟動自癒、今日分時完整性判定、read-time single-flight／60 秒冷卻／8 秒等待上限，並新增 8 個自癒回歸案例。Java 21 external-materials-service 全套測試通過。Docker 實機：GOOGL 由原 11:47 起 40 筆修復為 09:30 起 47 筆；另將 VOO 人工縮成 11:47 單筆後呼叫讀取端點，成功自癒為 09:30 起 47 筆。external-materials-service／BFF healthy，BFF actuator UP。
