# [t317] 股市大盤查詢 Docker 可呼叫單一唯讀圖表 API

**對應 Requirements:** Requirement 67（股市大盤查詢——Docker 可呼叫的單一唯讀圖表 API）
**前置任務:** 無（沿用已存在的股市大盤日線／分時聚合）
**Liquibase changeset:** 無（不動資料庫）

## 背景

目前「股市大盤查詢」畫面已能切換 9 個指數與 8 個期間，但資料入口分成兩支需要 Google OAuth session 的頁面 BFF：

- `GET /api/bff/gdp-twse/index-daily?market=&years=10`：完整 10 年日線、MA5/20/60/240、成交量／成交金額；前端再用交易日筆數縮放成 1m/3m/6m/1y/2y/5y/10y。
- `GET /api/bff/gdp-twse/index-intraday?market=`：最新交易日分時、昨收、漲跌與漲跌幅；前端另從完整日線陣列取最新四條 MA 當水平參考線。

Docker host 或同一 Compose network 的其他服務沒有 OAuth 瀏覽器 session，無法直接取得「畫面目前所見」的完整資料。此任務新增一支 `GET /api/public/market-index`，以 `market`＋`range` 覆蓋所有 72 組選擇；不得新增第二份歷史查詢、均線或分時算法。

BFF 已在 Compose 以 host `8080:8080` 暴露，且 service name `bff` 已位於 `asset-net`。因此本任務不修改 `docker-compose.yml`：host 呼叫 `http://localhost:8080/api/public/market-index`，同 network 容器呼叫 `http://bff:8080/api/public/market-index`，經 frontend nginx 則是 `http://localhost/api/public/market-index`。

## 要做什麼

- [ ] 317.1 在 `bff/src/main/java/com/steven/assets/bff/gdptwse/` 新增共用 `MarketIndexChartService`，把現有 `GdpTwseBffController` 內下列邏輯搬入並由原 controller 委派，確保既有頁面與新公開 API 只剩一份實作：
  - 日線下游選擇：`TWSE` → `/api/twse-daily-index?from=&to=`；其餘白名單指數 → `/api/us-daily-index?code=&from=&to=`。
  - 完整日線組裝：`dates/closes/ma5/ma20/ma60/ma240/volumes/turnovers/hasVolume`。
  - `movingAverage`：完整 `BigDecimal` 加總、視窗不足填 null、`divide(window, 2, HALF_UP)`。
  - 台股量欄語意：`volumes=tradeVolume`、`turnovers=tradeValue`、`hasVolume` 只看非零 `tradeValue`；`tradeValue` 在 service 邊界精確轉成 `BigDecimal`，null 保留，Integer／Long／BigDecimal／浮點 Number／numeric String 都不得經 `new BigDecimal(double)`。非法值拋 typed `MalformedMarketIndexPayloadException`：public advice 固定回 HTTP 502 `ProblemDetail`，不得回 200 空 public schema；既有 authenticated `getIndexDaily` 邊界只捕捉此 exception、記錄後回 HTTP 200 完整空 legacy body，維持 fail-soft。兩條邊界都不得補 0、轉 double、靜默丟棄或廣域吞掉其他程式錯誤。海外：`volumes=volume`、`turnovers` 等長全 null、`hasVolume` 只看非零 `volume`。
  - 分時下游 `/api/index-intraday?market=`、近 40 日日線昨收查詢、`previousCloseBefore` 與 `change/changePercent` 計算。
  - `GdpTwseBffController#getIndexDaily` 與 `#getIndexIntraday` 改為薄委派，HTTP 路徑、query、回應欄位與原本 fail-soft 空資料行為不變；其中 `getIndexDaily` 對 `MalformedMarketIndexPayloadException` 回完整空 body（`dates/closes/ma*/volumes/turnovers=[]`、`hasVolume=false`）與 HTTP 200。GDP、refresh、Excel、排程等其他方法不動。
  - 搬移既有純函式測試或調整呼叫點，使 `GdpTwseBffControllerMaTest` 與 `GdpTwseBffControllerIndexDailyVolumeTest` 繼續釘住同一份函式，不得刪除或弱化斷言。

- [ ] 317.2 在同 package 建立 immutable DTO records（可用 `MarketIndexChartDto` 外層 class 包 nested records，不得用 mutable `@Data`／setter）：
  - `Option(String value, String label)`。
  - `Response(String market, String marketLabel, String range, String rangeLabel, String mode, LocalDate tradingDate, List<String> labels, List<BigDecimal> closes, List<BigDecimal> ma5, List<BigDecimal> ma20, List<BigDecimal> ma60, List<BigDecimal> ma240, List<Object> volumes, List<BigDecimal> turnovers, boolean hasVolume, BigDecimal previousClose, BigDecimal lastClose, BigDecimal change, BigDecimal changePercent, List<Option> supportedMarkets, List<Option> supportedRanges)`；`tradingDate` 由 Jackson 序列化成 ISO `yyyy-MM-dd`，`turnovers` JSON 每點為精確 number 或 null。
  - 對外前以 defensive unmodifiable copies 固定所有 list；因 MA／成交量陣列合法包含 null，不得使用會拒絕 null 的 `List.copyOf` 直接複製這些陣列，可用 `Collections.unmodifiableList(new ArrayList<>(source))`。
  - `mode` 唯一值為字串 `DAILY` 或 `INTRADAY`；不用新增 DB enum／business enum。

- [ ] 317.3 在 `MarketIndexChartService` 建立單一 catalog（公開 API validation、label 與回應 metadata 共用，禁止三份 switch/map）：
  - 市場依序為 `TWSE=台股大盤`、`DJI=道瓊工業`、`SPX=標普 500`、`IXIC=那斯達克綜合`、`SOX=費城半導體`、`FTSE=英國富時 100`、`DAX=德國 DAX`、`KOSPI=韓國 KOSPI`、`N225=日經 225`。
  - 期間依序為 `d=當日`、`1m=1 個月`、`3m=3 個月`、`6m=半年`、`1y=1 年`、`2y=2 年`、`5y=5 年`、`10y=10 年`。
  - `market` 省略預設 `TWSE`，先 trim 再 uppercase；`range` 省略預設 `1y`，先 trim 再 lowercase。空白視同省略。未知值丟可映射為 HTTP 400 的例外，detail 至少包含收到的值與合法 values；不得讓未知 market 落到 `/api/us-daily-index` 後回 200 空資料。
  - 每份 `Response` 都帶完整、固定順序的 `supportedMarkets` 與 `supportedRanges`。

- [ ] 317.4 實作日線 range 組裝，所有選項與 `GdpTwseView.vue` 現行 `RANGE_TRADING_DAYS` 完全一致：
  - `1m=21`、`3m=63`、`6m=125`、`1y=250`、`2y=500`、`5y=1250`、`10y=2500` 個交易日；資料不足回現有全部。
  - 必須先取完整 10 年資料、在完整 closes 上算完 MA5/20/60/240，再算 `fromIndex=max(0,size-wanted)`，以同一 `fromIndex` 裁切 `labels/dates`、`closes`、四條 MA、`volumes`、`turnovers`。禁止先裁短資料才算 MA。
  - 回 `mode=DAILY`、`tradingDate=null`，分時專屬 `previousClose/lastClose/change/changePercent` 皆 null；`turnovers` 型別為 `List<BigDecimal>`。六個價格陣列與兩個量陣列長度都等於 labels，空資料時皆為空陣列、`hasVolume=false`，不得填 0。
  - 本 task 不改既有 frontend，因此 `GdpTwseView.vue` 的 `RANGE_TRADING_DAYS` 與分時水平線展開保留為相容殘留，不宣稱與 service shaping 已收斂成單一實作；不得新增第三份。BFF 契約測試須用獨立期望 map 固定上述 7 組筆數，以防兩端漂移。

- [ ] 317.5 實作 `range=d` 組裝：
  - 並行取得既有完整 10 年日線聚合與既有分時聚合，不自行呼叫 Yahoo/TWSE、不改 business/external-materials API。
  - 回 `mode=INTRADAY`、`LocalDate tradingDate`（公開 JSON 為 ISO date）、`labels=times`、分時 `closes`、`previousClose/lastClose/change/changePercent`；既有 authenticated legacy Map 僅在 `toLegacyBody()` 相容輸出邊界轉回同格式 String，key／JSON shape 不變。
  - 對完整日線 `ma5/ma20/ma60/ma240` 各取最後一個非 null 值，展開為與 labels 等長的水平陣列；該 MA 不存在則該條等長全 null。
  - `volumes` 與 `turnovers` 都是與 labels 等長的全 null 陣列，`hasVolume=false`。所有陣列在分時無資料時皆為空，metadata 保留、數值欄 null，不捏造交易日期或 0。

- [ ] 317.6 新增薄 `PublicMarketIndexController`：
  - `@RestController`＋`@RequestMapping("/api/public/market-index")`。
  - 唯一操作 `@GetMapping`，query `market`／`range` 選填，回 `Mono<ResponseEntity<MarketIndexChartDto.Response>>`（或等價 reactive record response）。Controller 只收參數、委派 service、轉 HTTP response，不放 range 裁切／均線／WebClient 邏輯。
  - 參數錯誤回 HTTP 400 `ProblemDetail`；malformed downstream `tradeValue` 的 typed exception 回 HTTP 502 `ProblemDetail`；兩者皆不得回 200 空 public schema。可新增 BFF scoped exception advice，但不得改變 business error passthrough 的既有 `BusinessErrorAdvice` 行為。

- [ ] 317.7 更新 `bff/.../config/SecurityConfig.java`，在 `.anyExchange().authenticated()` 之前精確加入：

  ```java
  .pathMatchers(HttpMethod.GET, "/api/public/market-index").permitAll()
  ```

  不得寫 `/api/public/**` 或 `/api/public/market-index/**` wildcard；POST/PUT/PATCH/DELETE 同路徑不得放行。這是 CLAUDE.md／structure.md 唯一具名、限縮的 Docker／自動化非前端頁面例外，前端 view 不得援引；未登入請求經 `TenantWebFilter` 時維持既有 anonymous 分支（剝除偽造 `X-User-*` headers），下游全球行情 read API 不需要 tenant headers。

- [ ] 317.8 新增／更新 BFF 測試，至少釘住：
  - catalog 9 個 market、8 個 range 與 72 組合法笛卡兒積；大小寫／前後空白正規化；未知 market/range 為 400 `application/problem+json`，`detail` 必須分別包含收到的非法值與完整合法 values。
  - 每個日線 range 的裁切筆數；所有陣列長度一致；以至少 260 筆 fixture 證明 1m 回應第一筆已有成熟 MA240（先全序列算再裁切），不是因短窗重算而 null；另以獨立期望 map 釘住與既有 frontend 相同的 7 組交易日數。
  - 台股／海外 volume 與 `hasVolume` 既有語意不漂移；`tradeValue` 的 Integer／Long／BigDecimal／浮點 Number／numeric String／null 均精確轉換，並釘住不產生 double 二進位尾差。非法值須有 public 502 ProblemDetail 與 authenticated legacy 200 完整空 body 兩條入口測試。
  - 錯誤邊界測試不得只在 controller 外層 mock 整支 service。必須以真實 `MarketIndexChartService` 搭配可控 WebClient fixture，分別模擬 transport、HTTP 與 decode 失敗，證明 public 與 legacy 入口仍回各自完整空 schema 的 HTTP 200；另以已成功 decode、但 `closePoint` 非法的 row 實際穿過 mapping／shaping，證明兩入口都傳播為 5xx，且不會被誤轉成 200、400 或 502。可另加注入的 `IllegalStateException`，但不得取代前述真實 shaping 反例。
  - `d` 的 labels、分時 closes、`LocalDate tradingDate`、ISO date JSON、legacy Map String shape、昨收／漲跌、四條水平 MA、兩條等長全 null volume 陣列。
  - controller 回 immutable record schema；查無資料仍 200 同 schema 空陣列；公開 DTO 的 `turnovers` 型別為 `List<BigDecimal>`。
  - security slice／integration 測試證明 anonymous `GET /api/public/market-index` 可到 controller，而 anonymous `GET /api/bff/gdp-twse/index-daily`、POST/PUT/PATCH/DELETE `/api/public/market-index` 與 GET `/api/public/market-index/child` 均為 401；若 BFF OAuth test slice 組態無法穩定載入，至少以精確 `SecurityConfig` 規則單元證據加下方 Docker 未登入 curl 驗證補足，不得把相鄰與同 namespace 負向驗證刪掉。

- [ ] 317.9 文件與變更邊界：
  - 同步 `spec/requirements.md` Requirement 67、`spec/design.md` BFF/API 設計、`spec/tasks.md` 索引與本任務完成報告。
  - 不修改 `frontend/src/views/GdpTwseView.vue` 的畫面或既有 API 呼叫；backend 只允許訂正 `ExcelExportService.java` 兩處 Javadoc 與 `WatchStockTaiexIntradayTest.java` 一處測試註解，使其改指 `MarketIndexChartService`，不得修改 backend 行為；不修改 external-materials-service、Redis schema、DB schema、docker-compose port；不新增回補／匯出／排程公開能力。兩支 BFF legacy 測試的 Javadoc 同步把誤植 Task 313 改為 317，並把不存在的 `ExcelExportService.ma5At` 改為 `ExcelExportService.indexMaAt`。

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests

# 以下 Docker 段僅在 feature 已 merge 後執行，且一律從乾淨、已追平 origin/main 的 main worktree build。
main_wt="$(git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch refs\/heads\/main$/{print p}')"
test -n "$main_wt"
test "$(git -C "$main_wt" branch --show-current)" = main
test -z "$(git -C "$main_wt" status --porcelain)"
git -C "$main_wt" fetch origin
git -C "$main_wt" merge --ff-only origin/main
cd "$main_wt"
test -f .env

stack_project="$(docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}')"
test "$stack_project" = asset-management
docker compose -p "$stack_project" build bff
bff_image_ref="${stack_project}-bff:latest"
built_bff_image="$(docker image inspect "$bff_image_ref" --format '{{.Id}}')"
test -n "$built_bff_image"
docker compose -p "$stack_project" up -d --no-deps --force-recreate --wait --wait-timeout 120 bff
test "$(docker inspect asset-bff --format '{{.Image}}')" = "$built_bff_image"
docker ps --filter name=asset-bff --format '{{.Names}}\t{{.Status}}'
curl -fsS http://localhost:8080/actuator/health

# Readiness sentinel：這個部署已有大盤歷史與最新分時；任一代表回應為空即停止，
# 回報 runtime 無法證實，不得把 fail-soft 的 200 空 schema 當成功。
curl -fsS 'http://localhost:8080/api/public/market-index?market=TWSE&range=1y' | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['market']=='TWSE' and d['range']=='1y' and d['mode']=='DAILY'; n=len(d['labels']); assert n>0 and all(len(d[k])==n for k in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers'))"
curl -fsS 'http://localhost:8080/api/public/market-index?market=IXIC&range=d' | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['market']=='IXIC' and d['range']=='d' and d['mode']=='INTRADAY'; n=len(d['labels']); assert n>0 and all(len(d[k])==n for k in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers'))"
curl -fsS 'http://localhost/api/public/market-index?market=TWSE&range=1m' | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['market']=='TWSE' and d['range']=='1m' and d['mode']=='DAILY'; n=len(d['labels']); assert n>0 and all(len(d[k])==n for k in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers'))"
curl -fsS 'http://localhost/api/public/market-index?market=IXIC&range=d' | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['market']=='IXIC' and d['range']=='d' and d['mode']=='INTRADAY'; n=len(d['labels']); assert n>0 and all(len(d[k])==n for k in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers'))"
docker exec asset-frontend wget -qO- 'http://bff:8080/api/public/market-index?market=N225&range=3m' | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['market']=='N225' and d['range']=='3m' and d['mode']=='DAILY'; n=len(d['labels']); assert n>0 and all(len(d[k])==n for k in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers'))"
docker exec asset-frontend wget -qO- 'http://bff:8080/api/public/market-index?market=IXIC&range=d' | python3 -c "import json,sys; d=json.load(sys.stdin); assert d['market']=='IXIC' and d['range']=='d' and d['mode']=='INTRADAY'; n=len(d['labels']); assert n>0 and all(len(d[k])==n for k in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers')); print('docker-network intraday points:', n)"

test "$(curl -sS -o /dev/null -w '%{http_code}' 'http://localhost:8080/api/public/market-index?market=BAD&range=1y')" = 400
test "$(curl -sS -o /dev/null -w '%{http_code}' 'http://localhost:8080/api/public/market-index?market=TWSE&range=bad')" = 400
test "$(curl -sS -o /dev/null -w '%{http_code}' 'http://localhost:8080/api/bff/gdp-twse/index-daily?market=TWSE&years=10')" = 401
for method in POST PUT PATCH DELETE; do
  status="$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" 'http://localhost:8080/api/public/market-index')"
  if [ "$status" != 401 ]; then
    echo "$method expected 401, got $status" >&2
    exit 1
  fi
done
test "$(curl -sS -o /dev/null -w '%{http_code}' 'http://localhost:8080/api/public/market-index/child')" = 401

python3 - <<'PY'
import json, subprocess
markets = ['TWSE','DJI','SPX','IXIC','SOX','FTSE','DAX','KOSPI','N225']
ranges = ['d','1m','3m','6m','1y','2y','5y','10y']
for market in markets:
    for period in ranges:
        url = f'http://localhost:8080/api/public/market-index?market={market}&range={period}'
        raw = subprocess.check_output(['curl', '-fsS', url], text=True)
        body = json.loads(raw)
        assert body['market'] == market, (market, period, body.get('market'))
        assert body['range'] == period, (market, period, body.get('range'))
        n = len(body['labels'])
        if period != 'd':
            assert n > 0, (market, period, 'daily readiness sentinel is empty')
        for key in ('closes','ma5','ma20','ma60','ma240','volumes','turnovers'):
            assert len(body[key]) == n, (market, period, key, n, len(body[key]))
print('verified combinations:', len(markets) * len(ranges))
PY

bff_logs="$(docker logs asset-bff --since 10m 2>&1)" || exit 1
bff_error_count="$(printf '%s\n' "$bff_logs" | grep -cE 'Connection refused|500 Server Error' || true)"
test "$bff_error_count" = 0
test "$(docker image inspect "$bff_image_ref" --format '{{.Id}}')" = "$built_bff_image"
test "$(docker inspect asset-bff --format '{{.Image}}')" = "$built_bff_image"
```

最後三支斷言預期成功；它們同時證明 BFF log 無指定錯誤，且驗證結束時 Compose tag 與執行中 container 仍指向本輪 build 的同一個 image，未被其他 worktree 覆蓋。

## 完成報告

- 實際修改：已完成 `MarketIndexChartService` 單一 catalog、9 市場 × 8 期間 normalization、完整十年序列先算 MA 再裁切，以及 `range=d` 分時／水平 MA 組裝；新增 immutable `MarketIndexChartDto`（`LocalDate tradingDate`、`List<BigDecimal> turnovers` 與可含 null 的 defensive copies）、公開 controller、request／malformed typed exception advice。既有 `GdpTwseBffController` 已改為薄委派；transport／HTTP／decode fail-soft 僅留在 WebClient fetch stage，非法 `tradeValue` 分別由 public 502 與 legacy 完整空 body 200 處理，其他 shaping 錯誤維持 5xx。`SecurityConfig` 只精確放行匿名 GET，測試涵蓋同路徑其他 method、descendant 與相鄰 legacy endpoint 的 401。backend 僅訂正 `ExcelExportService` 兩處 Javadoc、`WatchStockTaiexIntradayTest` 一處註解，兩支 BFF legacy test 的 Javadoc 亦已訂正；未修改 frontend、external-materials-service、DB、Redis schema 或 Compose 設定。
- 實際驗證：`git diff --check` 通過；`bash scripts/spec-check.sh` 為 `BLOCK: 0 / CHECK: 0`；BFF 完整測試 54/54 通過；`mvn -q -f bff/pom.xml package -DskipTests` 通過。架構對抗審查為 critical 0／major 0／minor 0，內容雜湊 `638a63ba7cc4`。
- Docker 實機驗證：尚未執行，也未宣稱已部署。依整體工作順序，須待所有實作合併後再由 `/run-stack` 從最終 main 重建／recreate BFF，並完成 image provenance、三條網路入口、非空 readiness sentinel、72 組矩陣、匿名負向路徑與 BFF log 檢查。
- 與規格偏差：目前程式與自動化測試未發現 Task 317 契約偏差；尚待的只有上述合併後 Docker/runtime 證據。

### Docker runtime 驗證（2026-08-12）

- 由 feature image rebuild／force-recreate BFF 後，direct host `:8080`、frontend nginx `:80`、同 Compose
  network 的 `bff:8080` 均各自驗到 representative DAILY 與 INTRADAY response：labels 均非空、
  `closes/ma5/ma20/ma60/ma240/volumes/turnovers` 皆與 labels 等長。
- catalog 9 markets × 8 ranges 的 72 組全數驗過：所有 DAILY 非空、market/range echo 正確、所有陣列對齊。
  `market=BAD`、`range=bad` 均回 400 `application/problem+json`，detail 同時含收到值與完整合法 values。
  未登入精確 GET 為 200；相鄰 authenticated legacy GET、同一路徑 POST/PUT/PATCH/DELETE 與 descendant GET
  均為 401。未安全誘發 malformed downstream payload，故 502 live mapping 不列為 runtime 證據（由既有契約測試覆蓋）。
