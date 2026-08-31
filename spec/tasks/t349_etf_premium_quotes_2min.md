# [t349] 台股 ETF 官方折溢價兩分鐘排程與 quote API 欄位

**對應 Requirements:** Requirement 88（台股 ETF 官方折溢價每 2 分鐘更新，並以 nullable `premiumDiscountPct` 併入 Docker 外部報價 API）  
**前置任務:** t214、t259、t312、t320、t328  
**Liquibase changeset:** 無

## 背景

本 Task 349 完成時，股價 producer 每 2 分鐘更新 `price:{market}:{code}`，但台股 ETF 淨值／折溢價 producer 仍使用 `0 2/5 9-13 * * MON-FRI`，每 5 分鐘才從證交所 MIS `all_etf.txt` 取一次資料。**現行覆寫：**Requirement 106／Task 370 已將台股個股改為已知開盤時每 10 秒依富邦→MIS→Yahoo 更新；美股／英股仍為每 2 分鐘。Docker 外部 `GET /api/quotes` 與 `GET /api/quotes/one` 目前只讀 price key，共 18 個欄位，沒有折溢價。

既有 `price:etfnav:{market}:{code}` 已保存台股 `all_etf.txt` 的官方 `g` 欄為 `premiumDiscountPct`，TTL 96 小時；這個 key 與 price key 刻意分開，避免三個 price writer 互相覆蓋 NAV 欄位。本任務只調整台股排程頻率，並在公開 quote reader 做 fail-soft 唯讀 join。不得修改 price writer、不得把 NAV 欄位塞回 price payload，也不得用四捨五入後的 iNAV 自行反推折溢價。

## 要做什麼

- [ ] **349.1 台股排程改為每 2 分鐘。**修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/EtfNavPoller.java`：`scheduledTwUpdate` 精確使用 `@Scheduled(cron = "0 1/2 9-13 * * MON-FRI", zone = "Asia/Taipei")`。奇數分鐘會在 09:01、09:03……13:29 通過交易時段守門；台股股價現為每 10 秒，兩者不依賴奇偶分鐘錯開，且不得改股價 cron。保留 `MarketClock.isTwMarketOpen()`、一輪只呼叫一次 `fetchTwAll()`、持股／觀察清單過濾、台股 17:30、美股 18:30、warmup 與手動 refresh。同步更新該類別 Javadoc 與 `external-materials-service/src/main/resources/application.yml` 註解，不再保留「每 5 分鐘」現況文字。

- [ ] **349.2 加排程反射測試。**在 external module 新增或擴充測試，以 reflection 取得 `scheduledTwUpdate` 的 `@Scheduled`，斷言 cron 與 zone 精確等於上一項。`EtfNavPoller` 仍精確維持 3 個 `@Scheduled` 方法：台股盤中 `scheduledTwUpdate`、台股 17:30 `scheduledTwCloseUpdate`、美股 18:30 `scheduledUsUpdate`；僅第一支是台股盤中 scheduler，且不得新增獨立 TPEX 盤中 scheduler。測試分別反射三個既有方法，不得以只搜尋 source 字串取代 annotation reflection。

- [ ] **349.3 `LatestQuote` 固定新增 nullable 欄位。**修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/PriceCacheReader.java`，在既有 18 欄後加入 `BigDecimal premiumDiscountPct`。`findOne` 先依原契約讀、解析 `price:{market}:{code}`；只有這個 key missing／malformed 時才回 `Optional.empty()`。價格有效後，best-effort 讀 `price:etfnav:{market}:{code}`，只解析同名 `premiumDiscountPct` 數字並帶入 record。

- [ ] **349.4 NAV join 必須局部 fail-soft 且零衍生。**NAV key missing、null、malformed JSON、缺少 `premiumDiscountPct`、欄位非數字或該次 `redis.opsForValue().get(navKey)` 拋出 runtime exception，都只讓新欄為 `null`；不得讓有效價格變 204 或從 bulk list 消失。NAV 解析使用獨立窄錯誤邊界，不把 price 與 NAV 包在同一個 catch。即使 NAV payload 同時有正數 `nav` 與 quote `price`，只要來源沒有 `premiumDiscountPct` 就必須回 `null`，不得呼叫 calculator 或套 `(price-nav)/nav`。一般股票與目前不帶官方折溢價的 Yahoo／美股 ETF 因此都為 `null`。

- [ ] **349.5 list 與 one 共用 reader，不複製 controller 邏輯。**`PublicQuoteController` 的兩支 operation 繼續透過 `PriceCacheReader`；一筆官方值例 `0.07` 必須序列化為 JSON number `0.07`，代表溢價 0.07%，負值代表折價。record member 即使為 null 也要固定輸出 `"premiumDiscountPct": null`。價格 miss 的 one 仍為 204；NAV miss 不得改 status。不得新增 endpoint、查 DB、對外 fetch、Redis write 或 pub/sub。

- [ ] **349.6 external 測試覆蓋完整錯誤矩陣。**擴充 `PriceCacheReaderTest`：matching NAV key 原值輸出；missing key；malformed JSON；missing field；non-numeric field；NAV Redis get exception；price 與 nav 都有但 premium 缺失時不反推；bulk list 仍保留上述有效 price。保留原有 price miss／malformed 行為與零寫入驗證。擴充 `PublicQuoteControllerTest`：list 與 one 都驗 property 存在、數值 case 正確、null case 仍存在，single price miss 仍 204。

- [ ] **349.7 排程 catalog 同步但筆數不變。**修改 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的既有「台股 ETF 淨值折溢價」項目，名稱維持不變：描述改成每 2 分鐘、一個證交所全市場 request 後寫 Redis；friendly schedule 精確為「交易日 09:01–13:29 每 2 分鐘」；cron 精確為 `0 1/2 9-13 * * MON-FRI`，時區仍 Asia/Taipei。不得新增 job。擴充 `SchedulePublicBffControllerTest`，同時斷言名稱、描述、friendly schedule、cron、zone 與既有總數／分類數。

- [ ] **349.8 OpenAPI 3.1 契約改成 19 欄。**修改 `docs/openapi/docker-external-api.yaml`：`LatestQuote.required` 與 properties 都加入 `premiumDiscountPct`，型別 `[number, 'null']`；required 只表示 property 固定出現，不代表非 null。說明寫明百分點、正溢價／負折價、只轉交 `price:etfnav:*` 來源欄位、缺值不反推、股票及目前美股 ETF 為 null。list example、list operation description、one operation description 與 one 的 204 response description 都要同步：price／index 讀取失敗維持原空陣列或 204，NAV enrichment missing／malformed／讀取失敗只讓 `premiumDiscountPct=null`，不得寫成任何 Redis 失敗都丟棄 quote。明記 price `updatedAt`／`tradingDate` 不是 NAV 時點，NAV key TTL 96 小時且與 price producer 非同 tick；本任務不得另增 `nav`／`navAsOf`。

- [ ] **349.9 不變邊界。**不修改 `PricePoller`、`PriceCacheWriter`、`EtfNavCacheWriter` payload schema、`etf_nav_history`／`etf_nav_observation` schema、交易雷達評分／veto、business-services、frontend、Nginx、Tailscale 或 gateway route；不新增 Redis key、Liquibase、第二支 producer 或 2 秒排程。

## 驗證

```bash
set -euo pipefail

# 規格與兩個受影響 JVM module
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# OpenAPI 必須可解析；另以搜尋確認 19 欄與 nullable required property
ruby -e 'require "yaml"; YAML.load_file("docs/openapi/docker-external-api.yaml"); puts "OPENAPI_YAML_OK"'

# 本專案沒有 dev server：無快取重建並 recreate 實際變更的服務
docker compose -p asset-management build --no-cache external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service bff
docker compose -p asset-management restart bff
docker compose -p asset-management ps
docker inspect asset-external-materials-service --format '{{.State.Health.Status}} {{.Image}}'
docker inspect asset-bff --format '{{.State.Health.Status}} {{.Image}}'

# host 唯一正式入口：分離 status/body，204 不得被 curl -f 冒充成功
quote_tmp=$(mktemp -d)
trap 'rm -rf "$quote_tmp"' EXIT
list_status=$(curl -sS -o "$quote_tmp/list.json" -w '%{http_code}' --get \
  'http://127.0.0.1:9090/api/quotes' --data-urlencode 'market=台股')
test "$list_status" = 200
jq -e 'type == "array" and length > 0 and all(.[]; has("premiumDiscountPct"))' "$quote_tmp/list.json"

# 動態選一檔有官方值的台股 ETF，one 的值須與既有 Redis key 數值相等
etf_code=$(jq -r '[.[] | select(.premiumDiscountPct != null)][0].stockCode // empty' "$quote_tmp/list.json")
test -n "$etf_code"
one_status=$(curl -sS -o "$quote_tmp/one.json" -w '%{http_code}' --get \
  'http://127.0.0.1:9090/api/quotes/one' \
  --data-urlencode "code=$etf_code" --data-urlencode 'market=台股')
test "$one_status" = 200
redis_pct=$(docker exec asset-redis redis-cli --raw GET "price:etfnav:台股:$etf_code" | jq -r '.premiumDiscountPct // empty')
test -n "$redis_pct"
jq -e --argjson expected "$redis_pct" \
  'has("premiumDiscountPct") and .premiumDiscountPct == $expected' "$quote_tmp/one.json"

# 一般股票固定存在 property 且為 null
stock_status=$(curl -sS -o "$quote_tmp/stock.json" -w '%{http_code}' --get \
  'http://127.0.0.1:9090/api/quotes/one' \
  --data-urlencode 'code=2330' --data-urlencode 'market=台股')
test "$stock_status" = 200
jq -e 'has("premiumDiscountPct") and .premiumDiscountPct == null' "$quote_tmp/stock.json"

# 直接問本輪 external 的同一個 MarketClock；只在確認開盤時驗 live cadence
tw_open=$(docker exec asset-external-materials-service \
  wget -qO- http://localhost:8080/internal/health | jq -r '.twMarketOpen')
external_started_at=$(docker inspect asset-external-materials-service \
  --format '{{.State.StartedAt}}')
if [ "$tw_open" = true ]; then
  # 不手動 refresh；等待跨過至少兩個 cron slot，且 DB 證據必須晚於本輪 container StartedAt
  cadence_deadline=$(( $(date +%s) + 360 ))
  cadence_ok=f
  while [ "$(date +%s)" -lt "$cadence_deadline" ]; do
    sleep 15
    cadence_ok=$(docker exec asset-postgres psql -U assets -d assets -Atc \
      "WITH ranked AS (
           SELECT observed_at,
                  row_number() OVER (ORDER BY observed_at) AS sequence_in_deployment
             FROM etf_nav_observation
            WHERE stock_code = '$etf_code' AND market = '台股'
              AND observed_at >= '$external_started_at'::timestamptz
       ), scheduled_only AS (
           -- recreate 後第一筆必為 ApplicationReady warmup，不可冒充 scheduler tick
           SELECT observed_at FROM ranked
            WHERE sequence_in_deployment > 1
       ), gaps AS (
           SELECT observed_at - lag(observed_at) OVER (ORDER BY observed_at) AS gap
             FROM scheduled_only
       )
       SELECT (SELECT count(*) FROM scheduled_only) >= 2
          AND EXISTS (
              SELECT 1 FROM gaps
               WHERE gap BETWEEN interval '90 seconds' AND interval '150 seconds'
          );")
    if [ "$cadence_ok" = t ]; then break; fi
    tw_open=$(docker exec asset-external-materials-service \
      wget -qO- http://localhost:8080/internal/health | jq -r '.twMarketOpen')
    if [ "$tw_open" != true ]; then break; fi
  done
  if [ "$tw_open" = true ]; then
    test "$cadence_ok" = t
    # docker logs 的 --since 在目前 daemon 對 RFC3339／相對值皆實測不可靠；
    # recreate 後 current container 本身就是本輪邊界，DB StartedAt 下界仍是主要證據
    etf_log_count=$(docker logs --timestamps asset-external-materials-service 2>&1 \
      | rg -c 'ETF 淨值更新（台股）')
    test "$etf_log_count" -ge 3
  else
    echo 'TW_MARKET_CLOSED_DURING_CADENCE_WINDOW：不以舊資料或手動 refresh 冒充本輪節拍'
  fi
else
  echo 'TW_MARKET_CLOSED：跳過 live cadence；只採 reflection、catalog、official cache 與 API shape 證據'
fi

# 重建後不得有持續錯誤或 restart loop
if docker logs asset-external-materials-service 2>&1 \
    | rg 'ERROR|Connection refused'; then exit 1; fi
if docker logs asset-bff 2>&1 \
    | rg 'ERROR|Connection refused'; then exit 1; fi
```

實機驗收：

- [ ] 開盤中跨至少兩輪讀 `price:etfnav:台股:0050` 的 `updatedAt` 或等價 scheduler／observation 證據，兩次成功更新約差 2 分鐘；每輪仍只有一個 `all_etf.txt` request。不得以手動 refresh 兩次冒充 scheduler cadence。
- [ ] `/api/quotes` 與 `/api/quotes/one` 的同一台股 ETF 均固定含 `premiumDiscountPct` 且數值等於 Redis 官方欄；一般股票固定含該 property 且值為 null。不能只驗 HTTP 200。
- [ ] 若當時休市，`MarketClock` 應阻止 live fetch；以 reflection test、schedule catalog、既有 official cache 與 API shape 驗收，完成報告明列「未跨兩輪觀察」而非繞過守門。
- [ ] external／BFF healthy、實際 container image 對應本輪 build，重建後 logs 無持續 ERROR、連線拒絕或 restart loop；BFF 已在 external recreate 後 restart。

## 完成報告

**完成日期：** 待實作後填寫  
**變更檔案：** 待實作後逐檔列出  
**測試結果：** 待填寫（external 與 BFF tests/failures/errors/skipped、OpenAPI YAML parse）  
**Docker／排程／API 驗證：** 待填寫（container provenance、健康狀態、兩分鐘 cadence 或休市限制、ETF 官方值與股票 null case）
