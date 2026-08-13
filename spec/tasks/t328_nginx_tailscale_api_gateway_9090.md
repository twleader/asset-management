# [t328] Nginx 9090 統一外部 API 與 Tailscale 私網 HTTPS

**對應 Requirements:** Requirement 66（Nginx 9090 統一 Docker 外部唯讀 API 與 Tailscale 私網入口）、Requirement 67（股市大盤查詢的單一唯讀圖表 API）
**前置任務:** t317（`GET /api/public/market-index` 已存在）、Requirement 66 既有 `PublicQuoteController` 兩支 GET 已存在
**Liquibase changeset:** 無

## 背景

目前 Docker 外部 API 分散在三個入口：external-materials-service 以
`127.0.0.1:8082` 直接映射整個 Spring Boot process、BFF 以 `0.0.0.0:8080` 映射、frontend
Nginx 的 generic `/api/` 又可代理 BFF public route。第一種做法雖只綁 loopback，仍讓同 process
中具寫入副作用的 `/internal/refresh`、`/internal/backfill/*`、`/internal/repair/history` 等一起可由 host
連入；後兩種則讓同一個匿名 API 有多個入口，難以維護一致的網路邊界。

本任務把**目前正式核准供 Docker 外工具使用的全部 API**收斂為以下五個唯讀 GET operation；
「全部」不包含整個 `/api/**`、登入後 BFF API 或任何 `/internal/*`：

| Method | External path | Upstream |
|---|---|---|
| GET | `/api/quotes?market=` | `external-materials-service:8080` |
| GET | `/api/quotes/one?code=&market=` | `external-materials-service:8080` |
| GET | `/api/public/market-index?market=&range=` | `bff:8080` |
| GET | `/api/assets/latest` | `bff:8080` |
| GET | `/api/public/exchange-rate/usd-twd` | `bff:8080` |

`/api/assets/latest` 與 USD/TWD 的 controller、資料聚合與 payload 契約由各自平行任務實作；本任務只提供
exact gateway route 與 network boundary，不得複製或猜測該業務邏輯。若 endpoint 尚未落在本 checkout，
須用 stub upstream 測透明 proxy，再由實作該 endpoint 的整合任務補真實 payload runtime 驗證。

Docker host 的唯一入口固定為 `http://127.0.0.1:9090`。遠端不把 9090 暴露到公網，而由 macOS
主機上的 Tailscale Serve 提供 tailnet HTTPS `:9090`，再反向代理 loopback Nginx：

```text
本機工具 ───────────────────────────────► http://127.0.0.1:9090
tailnet client ─► HTTPS :9090 ─► Tailscale Serve（只掛五個 exact path）
                                      │
                                      ▼
                         api-gateway (Nginx :9090)
                         ├─ quote exact routes ─► external-materials-service:8080
                         └─ index/assets/USD-TWD exact routes ─► bff:8080
```

因此不新增 Google OAuth2、API key、自簽憑證或憑證輪替程式。Tailscale 負責 tailnet identity 與
TLS；Nginx 負責五條本機 exact allowlist；Tailscale 掛載相同五條 exact path，包含公開 USD/TWD 匯率。禁止 Tailscale Funnel、公開 Internet listener、路由器 port-forward
與 wildcard proxy。能直接呼叫 `127.0.0.1` 或操作 Docker 的本機管理者仍屬既有 host-admin 信任邊界。

## 要做什麼

- [ ] **328.1 建立最小權限的獨立 Nginx image。** 新增 `api-gateway/Dockerfile` 與
      `api-gateway/nginx.conf`。image 使用 Alpine Nginx、刪除預設網站、以非 root `nginx` user
      執行並 listen 非特權 port `9090`；PID、client/proxy temporary files 全放 `/tmp`，讓 Compose
      可以 `read_only: true`、只給 `/tmp` tmpfs、`cap_drop: [ALL]`、`no-new-privileges:true`。
      access/error log 寫 stdout/stderr，關閉 `server_tokens`。不得把 TLS certificate、private key、
      Tailscale auth key 或 OAuth secret 放入 image／repo。

- [ ] **328.2 Nginx 必須 deny by default，且本機只 proxy 五個 exact GET。** 完整行為固定如下：
  - `location = /api/quotes` 與 `location = /api/quotes/one` 將原始 `$request_uri` proxy 至變數
    `external-materials-service:8080`；`location = /api/public/market-index` 與
    `location = /api/assets/latest` 與 `location = /api/public/exchange-rate/usd-twd` 各自 exact proxy 至變數 `bff:8080`。
  - `resolver 127.0.0.11 valid=10s ipv6=off` 必須存在；upstream service name 必須放在變數中才會於
    request time 使用 Docker DNS，container recreate 換 IP 後不得黏住舊 IP。
  - 五個 exact path 只有字面上的 `GET` 可用；`HEAD`、`OPTIONS`、POST、PUT、PATCH、DELETE 一律
    `405`。所有其他 path 一律 `404`，包含 trailing slash、descendant、`/internal/*`、`/actuator/*`、
    `/api/bff/*`、OAuth path 與未知 path；不得出現 `/api/`、`/internal/` 或 `/` 的 proxy wildcard。
  - proxy 保留 query string、upstream response body/status/content type；設定 `Host`、`X-Real-IP`、
    `X-Forwarded-For`，但不做 response shaping、cache、retry、fallback 或 error interception。
    connect/send/read timeout 分別採有限值（5s/30s/60s），並明設 `proxy_next_upstream off`；上游故障
    保持標準 `502/504`，不得偽裝成空 `200`。非 GET 的 405 必須帶 `Allow: GET`，可用 http-context
    `map` 讓 GET 不產生空白 Allow、其他 method 產生 `GET`。`Tailscale-*` header 只可寫 log，不得作 app authorization，因本機 direct loopback
    呼叫可以自行偽造該 header。

- [ ] **328.3 Compose 收斂成唯一 loopback host API port。** 修改 `docker-compose.yml`：
  - 完全移除 `external-materials-service.ports` 與 `bff.ports`；不要改成 `expose`，container port 在
    同一 network 本來就可達。原 host `8082` 與 `8080` 完成後都不得有 listener。
  - 新增 service `api-gateway`、container name `asset-api-gateway`、build context `./api-gateway`、
    `restart: unless-stopped`、唯一 mapping `127.0.0.1:9090:9090`、network `asset-net`；depends_on
    `external-materials-service` 與 `bff` 的 `service_healthy`。
  - 套用 328.1 的 read-only/non-root hardening。healthcheck 從 container 內對
    `http://127.0.0.1:9090/api/quotes` 做真正的純讀 GET（禁止 `curl -I`／`wget --spider` 的 HEAD），驗證 Nginx listener 與 quote upstream；實機 smoke
    test 另驗證 BFF upstream，不把單一 healthcheck 誤稱為兩個 upstream 都正常。
  - `frontend:80` 保留，因它仍是瀏覽器 UI；PostgreSQL 的 loopback 5432 不在本任務範圍。

- [ ] **328.4 阻止 frontend 形成第二個匿名 API 入口。** 在 `frontend/nginx.conf` 的 SSE／generic
      `/api/` location 之前加入五個 exact location，對 `/api/quotes`、`/api/quotes/one`、
      `/api/public/market-index`、`/api/assets/latest`、`/api/public/exchange-rate/usd-twd` 全部直接回 `404`；Nginx exact matching 會連帶讓這五條 path 的任何
      method 都被封鎖。另以 anchored regex 封鎖受保護路徑每一個 segment 帶 matrix parameter 的變體（例如
      `/api/public/market-index;x=1`、`/api/public;x=1/market-index`、`/api/assets;x=1/latest`），避免它落入 generic `/api/` 後被 Spring WebFlux 去除 matrix parameter
      而命中 controller。regex 不得擴張到其他正常登入後 API。不得改動 SPA、OAuth2、SSE、長時間
      index refresh 或其他登入後 API proxy。

- [ ] **328.5 提供不保存秘密、path-scoped 的 Tailscale Serve 設定腳本。** 新增 executable
      `scripts/configure-tailscale-api-gateway.sh`，行為依序為：(1) 尋找 PATH 中 `tailscale`，必要時也
      支援 macOS app bundle 的 CLI；找不到時顯示 `brew install --cask tailscale-app` 與停止，腳本不得
      自行要求或處理管理員密碼；(2) `tailscale status` 必須成功，未登入時提示開啟 Tailscale 完成
      browser login 後停止；(3) 先讀 `tailscale serve status --json`，只有空設定或精確等於本任務五條
      handler 才可繼續，若有其他 handler 就列出後停止；(4) 不改動 Serve，先做五路 endpoint-aware
      preflight：`/api/quotes` 必須固定為 200 application/json 非空 array，並以首筆合法 `stockCode/market` 驗
      `/api/quotes/one` 固定為 200 application/json；market-index 代表查詢須固定為 200 application/json，
      且 `labels` 非空、六圖表陣列等長；assets/latest 須通過
      200 application/json、固定 valuation policy 與 snapshot/live id 相同；USD/TWD 須通過其 200
      application/json、固定 pair/currency/interval/timezone、非空 spot/history 與 count 契約。quote list
      無 sentinel 或任一支未健康都須明列後停止，不得 reset/configure；(5) reset 緊前再次讀 machine-readable
      status、重驗所有權，並比較兩次解析後的 canonical JSON，任何變動都停止；只有兩次狀態相同才執行
      `tailscale serve reset`；(6) 對 HTTPS 9090 逐一執行
      `tailscale serve --bg --https=9090 --set-path="$path" "http://127.0.0.1:9090$path"`，其中 `$path`
      依序為 `/api/quotes`、`/api/quotes/one`、`/api/public/market-index`、`/api/assets/latest`、
      `/api/public/exchange-rate/usd-twd`；每條都必須
      `--bg`，target 必須保留同名 backend path；若 CLI 不支援 `--set-path`，須 fail closed 停止且不建立 remote Serve，
      不得另猜 listener、改用 root proxy 或另開不同遠端 port；(7) 顯示 `tailscale serve status --json`，fail closed 驗證 handler
      精確等於上述五條，且無 `/`、`/api/` 或額外 handler。設定中途失敗時，cleanup 必須先重讀現況；只有空設定或本輪
      五條 handler 的安全子集合／精確集合且 target 完全相符時才可 reset，任何陌生或無法確認的狀態都不得
      自動刪除。腳本須可重複執行，不接受／產生／保存 reusable auth key，
      不改 grants/ACL，任何分支都不得呼叫 `tailscale funnel`。多人 tailnet 的 grants/ACL 必須由管理者
      在 tailnet policy 另行限縮 identity。

- [ ] **328.6 同步長期架構與操作文件。** 更新 `CLAUDE.md`、`spec/steering/structure.md`、
      `spec/steering/tech.md`、`INSTALLATION.md`、`.agents/skills/run-stack/SKILL.md` 與
      `.claude/skills/run-stack/SKILL.md`：service 數量加入 api-gateway；BFF/external-materials 的 host port
      改為 none；Docker 外 API entry 改為 9090；列出五個 local exact GET；說明 frontend 80 只供 UI，
      Tailscale Serve 只把相同五個 exact path 提供遠端 HTTPS，包含公開 USD/TWD 匯率，且不使用 root／`/api/` proxy 或 Funnel。兩份 run-stack skill 的內容須同步，但各自既有的
      model/effort frontmatter 不得互相覆蓋。刪除「`/internal/*` 隨 external-materials host port 一併
      可達」「BFF 8080 是 host 入口」「frontend 可代理 public market-index」等已被本任務推翻的敘述。

- [ ] **328.7 不改五支 API 的業務契約。** 不修改 `PublicQuoteController`、`PriceCacheReader`、
      `PublicMarketIndexController`、`MarketIndexChartService`、`SecurityConfig`、Redis key、DB schema、
      poller、寫入流程或 OAuth session。Quote list 仍為 200 array，single hit 仍為 200、cache miss 仍為
      204、缺參數仍為 400；market-index 合法查詢仍為 200，非法 market/range 仍為 400，typed malformed
      payload 仍為 502。`/api/assets/latest` 與 USD/TWD 的 payload/錯誤契約由各平行任務擁有，本任務只透明 proxy。
      BFF 的五個精確 GET permitAll 必須由各 endpoint 實作保留，因 api-gateway 本身沒有 OAuth session。

- [ ] **328.8 新增可重複執行的 gateway smoke verification。** 以 shell script 或逐項可複製命令
      覆蓋：(a) 五支本機 GET 的合法回應（assets/USD-TWD endpoint 未落地時先用 stub upstream 驗 proxy，不能把真 BFF
      的 401/404 當成功）；(b) exact path 的 HEAD/OPTIONS/POST/PUT/PATCH/DELETE 全為 405 且 `Allow: GET`；
      (c) trailing slash、descendant、`/internal/health`、`/internal/refresh`、`/actuator/health`、
      `/api/bff/gdp-twse/index-daily`、OAuth path 與未知 path 全為 404；(d) frontend port 80 對五支
      exact path 及其 matrix-parameter 變體全為 404；(e) host 8080／8082 無 listener、9090 只在 `127.0.0.1`；(f) container
      network 內仍可直接呼叫 `bff:8080/api/public/market-index` 與
      `external-materials-service:8080/api/quotes`，但這不是 host contract。檢查遇到 curl transport error
      必須與預期 HTTP status 分開判讀，不能把「連不到 gateway」當作成功的 404。

## 驗證

先確認工作目錄與執行中 Compose project，所有後續 Compose command 都使用同一 project name：

```bash
pwd
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
docker compose -p asset-management config --quiet
docker compose -p asset-management build --no-cache api-gateway frontend
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate api-gateway frontend
```

使用 bounded healthy wait；四個受影響 container 必須 healthy/running，且 gateway 以非 root、read-only
root filesystem 執行：

```bash
for name in asset-external-materials-service asset-bff asset-api-gateway; do
  for i in $(seq 1 60); do
    state=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$name" 2>/dev/null || true)
    [ "$state" = healthy ] && break
    sleep 2
  done
  test "$(docker inspect --format '{{.State.Health.Status}}' "$name")" = healthy
done
test "$(docker inspect --format '{{.State.Status}}' asset-frontend)" = running
test "$(docker inspect --format '{{.Config.User}}' asset-api-gateway)" = nginx
test "$(docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' asset-api-gateway)" = true
docker exec asset-api-gateway nginx -t
```

正向資料驗證不能只看 HTTP 200。Quote list 必須是 JSON array；market-index 的 DAILY／INTRADAY
`labels` 必須非空，六個圖表陣列等長。單筆 quote 以當時 quote list 中第一筆的 code/market 呼叫，避免
硬編碼一個已過 TTL 的標的；若 list 為空，誠實回報無 runtime quote sentinel，不得把 204 當完整成功：

```bash
curl -fsS 'http://127.0.0.1:9090/api/quotes' > /tmp/t328-quotes.json
jq -e 'type == "array"' /tmp/t328-quotes.json
curl -fsS 'http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m' > /tmp/t328-daily.json
curl -fsS 'http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=d' > /tmp/t328-intraday.json
jq -e '.labels | length > 0' /tmp/t328-daily.json
jq -e '.labels | length > 0' /tmp/t328-intraday.json
jq -e '(.labels|length) == (.closes|length) and (.labels|length) == (.ma5|length) and (.labels|length) == (.ma20|length) and (.labels|length) == (.ma60|length) and (.labels|length) == (.ma240|length)' /tmp/t328-daily.json
jq -e '(.labels|length) == (.closes|length) and (.labels|length) == (.ma5|length) and (.labels|length) == (.ma20|length) and (.labels|length) == (.ma60|length) and (.labels|length) == (.ma240|length)' /tmp/t328-intraday.json
```

負向矩陣與 host exposure：

```bash
for path in /api/quotes /api/quotes/one /api/public/market-index /api/assets/latest /api/public/exchange-rate/usd-twd; do
  for method in HEAD OPTIONS POST PUT PATCH DELETE; do
    test "$(curl -sS -o /dev/null -w '%{http_code}' -X "$method" "http://127.0.0.1:9090$path")" = 405
    test "$(curl -sSI -X "$method" "http://127.0.0.1:9090$path" | tr -d '\r' | awk -F': ' 'tolower($1)=="allow"{print $2}')" = GET
  done
done
for path in /api/quotes/ /api/quotes/child /api/public/market-index/child /internal/health /internal/refresh /actuator/health /api/bff/gdp-twse/index-daily /oauth2/authorization/google /unknown; do
  test "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:9090$path")" = 404
done
for path in /api/quotes /api/quotes/one /api/public/market-index /api/assets/latest /api/public/exchange-rate/usd-twd /api/quotes%3Bx=1 /api/quotes%3Bx=1/one /api/public/market-index%3Bx=1 /api/public%3Bx=1/market-index /api/assets%3Bx=1/latest /api/assets/latest%3Bx=1 /api/public/exchange-rate%3Bx=1/usd-twd /api/public/exchange-rate/usd-twd%3Bx=1; do
  test "$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1$path")" = 404
done
test "$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 2 http://127.0.0.1:8080/ || true)" = 000
test "$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 2 http://127.0.0.1:8082/ || true)" = 000
docker port asset-api-gateway 9090/tcp | grep -Fx '127.0.0.1:9090'
test -z "$(docker port asset-bff 8080/tcp)"
test -z "$(docker port asset-external-materials-service 8080/tcp)"
```

從 Docker network 驗證 upstream 仍可用，並核對 gateway/frontend image 是本輪 build 後實際 recreate
的 image。不得只看 Compose config：

```bash
docker exec asset-api-gateway wget -qO- 'http://external-materials-service:8080/api/quotes' >/dev/null
docker exec asset-api-gateway wget -qO- 'http://bff:8080/api/public/market-index?market=TWSE&range=1m' >/dev/null
docker inspect asset-api-gateway asset-frontend asset-bff asset-external-materials-service --format '{{.Name}} {{.Image}} {{index .Config.Labels "com.docker.compose.project.working_dir"}}'
```

Tailscale 已安裝且使用者登入後才執行；2026-08-14 本機前置查證為 BackendState `Running`、Self online、
DNSName `mac-mini-2.tailccc7be.ts.net.`，且 `tailscale serve status --json` 精確為 `{}`，故本輪首次 reset
不會刪除其他 handler。執行時仍須重查，不能把這個快照當永久保證。若 `tailscale status` 尚未成功，此段狀態是「待使用者登入」，
不得改綁 `0.0.0.0` 或宣稱遠端 HTTPS 已驗證：

```bash
scripts/configure-tailscale-api-gateway.sh
tailscale serve status --json
tailscale status
tail_host=$(tailscale status --json | jq -r '.Self.DNSName | rtrimstr(".")')
tail_base="https://${tail_host}:9090"

# 1–2/5 quotes：分離驗固定 status、content-type 與 body；非空才可由首筆建立可複製的 single-quote sentinel。
quotes_status=$(curl -sS -D /tmp/t328-tail-quotes.headers -o /tmp/t328-tail-quotes.json \
  -w '%{http_code}' "$tail_base/api/quotes")
test "$quotes_status" = 200
grep -Eiq '^content-type: application/json([;[:space:]]|$)' /tmp/t328-tail-quotes.headers
jq -e 'type == "array"' /tmp/t328-tail-quotes.json
if jq -e 'length > 0' /tmp/t328-tail-quotes.json >/dev/null; then
  quote_code=$(jq -r '.[0].stockCode' /tmp/t328-tail-quotes.json)
  quote_market=$(jq -r '.[0].market' /tmp/t328-tail-quotes.json)
  quote_one_status=$(curl -sS -D /tmp/t328-tail-quote-one.headers -o /tmp/t328-tail-quote-one.json \
    -w '%{http_code}' --get --data-urlencode "code=$quote_code" --data-urlencode "market=$quote_market" \
    "$tail_base/api/quotes/one")
  test "$quote_one_status" = 200
  grep -Eiq '^content-type: application/json([;[:space:]]|$)' /tmp/t328-tail-quote-one.headers
  jq -e --arg code "$quote_code" --arg market "$quote_market" \
    '.stockCode == $code and .market == $market' /tmp/t328-tail-quote-one.json
else
  echo 'NO_QUOTE_SENTINEL: /api/quotes 為空，/api/quotes/one 遠端 payload 尚無可驗證標的' >&2
fi

# 3/5 market-index：分離驗固定 status、content-type 與 body，不可用其他 2xx 或 fail-soft 空 schema 冒充成功。
market_index_status=$(curl -sS -D /tmp/t328-tail-index.headers -o /tmp/t328-tail-index.json \
  -w '%{http_code}' "$tail_base/api/public/market-index?market=TWSE&range=1m")
test "$market_index_status" = 200
grep -Eiq '^content-type: application/json([;[:space:]]|$)' /tmp/t328-tail-index.headers
jq -e '(.labels|length) > 0 and
       (.labels|length) == (.closes|length) and
       (.labels|length) == (.ma5|length) and
       (.labels|length) == (.ma20|length) and
       (.labels|length) == (.ma60|length) and
       (.labels|length) == (.ma240|length)' /tmp/t328-tail-index.json

# 4/5 latest assets：驗固定 200、content-type 與已落地契約，不只看 transport 成功。
assets_status=$(curl -sS -D /tmp/t328-tail-assets.headers -o /tmp/t328-tail-assets.json \
  -w '%{http_code}' "$tail_base/api/assets/latest")
test "$assets_status" = 200
grep -Eiq '^content-type: application/json([;[:space:]]|$)' /tmp/t328-tail-assets.headers
jq -e '.valuationPolicy == "TARGET_SESSION_WITH_EXPLICIT_FALLBACK" and
       (.snapshot.id | type == "number") and
       (.liveAssets.snapshotId | type == "number") and
       .snapshot.id == .liveAssets.snapshotId' /tmp/t328-tail-assets.json

# 5/5 USD/TWD：公開匯率與另外四路同樣由 tailnet HTTPS 提供，驗完整固定契約。
usd_twd_status=$(curl -sS -D /tmp/t328-tail-usd-twd.headers -o /tmp/t328-tail-usd-twd.json \
  -w '%{http_code}' "$tail_base/api/public/exchange-rate/usd-twd")
test "$usd_twd_status" = 200
grep -Eiq '^content-type: application/json([;[:space:]]|$)' /tmp/t328-tail-usd-twd.headers
jq -e '.pair == "USD/TWD" and
       .baseCurrency == "USD" and
       .quoteCurrency == "TWD" and
       .refreshIntervalSeconds == 2 and
       .timezone == "Asia/Taipei" and
       (.spot | type == "object") and
       (.spot | length) > 0 and
       (.history | type == "array") and
       (.history | length) > 0 and
       .count == (.history | length)' /tmp/t328-tail-usd-twd.json

test "$(curl -sS -o /dev/null -w '%{http_code}' "https://${tail_host}:9090/")" = 404
test "$(curl -sS -o /dev/null -w '%{http_code}' "https://${tail_host}:9090/api/")" = 404
test "$(curl -sS -o /dev/null -w '%{http_code}' "https://${tail_host}:9090/unknown")" = 404
```

最後跑專案既有機械規格檢查；本任務不改 Java/Vue 業務碼，gateway/frontend runtime smoke 取代無關
的全庫測試，但若實作超出上述檔案範圍，必須加跑對應模組測試／build：

```bash
bash scripts/spec-check.sh
git diff --check
git status --short
```

## 完成報告

- 2026-08-14 已完成 `api-gateway/Dockerfile`／`nginx.conf`、Compose 9090 loopback mapping 與
  non-root/read-only/cap-drop hardening、frontend 五路 exact/matrix deny、fail-closed Tailscale 設定腳本，
  並同步 `CLAUDE.md`、steering、安裝手冊、`.env.example` 與兩份 run-stack skill。未修改 Java/Vue
  業務碼、SecurityConfig、Redis/DB schema 或任何寫入路徑。
- `run-stack` 從本 feature worktree build/recreate；為排除共享 tag 被平行 assets 分支污染，BFF 與
  external-materials-service 另以 `--no-cache` 從本 worktree 重建。執行中 image：gateway
  `d76be063…`、frontend `26c747be…`、BFF `563172ad…`、external `1f06fa6b…`；四者 Compose
  `working_dir` 均為本 worktree。gateway、BFF、external healthy，frontend running；gateway 實測
  `User=nginx`、read-only rootfs、`CapDrop=ALL`、`no-new-privileges`、`/tmp` tmpfs，`nginx -t` 通過。
- 真實資料 sentinel：quotes `200 application/json`、53 筆；以首筆 `0000/台股` 驗 quotes/one 200；
  market-index `TWSE/1m` DAILY 21 點、`IXIC/d` INTRADAY 79 點，六個圖表陣列皆非空且等長。
  assets/latest 與 USD/TWD 在此 gateway 基線 checkout 尚未含平行業務實作，gateway 與 direct BFF
  均回 401，只作透明 status 路由證據，不宣稱業務成功。
- isolated 無 volume mock network 以同一 gateway config 驗五路：quotes 兩路保留 upstream
  `201 application/vnd.task328-external+json`，BFF 三路保留 `202 application/vnd.task328-bff+json`；
  query、status、body、content type 全透明，測完已清理。正式負向矩陣為五路 × 六 method 共 30/30
  `405 + Allow: GET`、gateway deny 11/11 為 404、frontend exact/matrix 25/25 為 404；既有 frontend
  BFF API 仍到 upstream 回 401，未被 deny regex 誤擋。
- Host 8080／8082 無 listener，9090 僅 `127.0.0.1:9090`；BFF/external 無 published port。
  原四路契約當時的 `spec-check` 為 `BLOCK: 0 / CHECK: 0`，兩份 Nginx `-t`、Compose config、shell syntax 與
  `git diff --check` 全通過，spec cross-audit 為 critical 0／major 0／minor 0；這些歷史結果不代表本次五路契約修訂已完成重新審查或 runtime 驗證。
- Tailscale 已安裝、登入且為 `Running/Online`，DNS `mac-mini-2.tailccc7be.ts.net.`；Serve status 仍為
  `{}`。原 2026-08-14 四路契約下刻意不執行設定腳本：腳本依安全規格會先要求 assets/latest 與 USD/TWD 兩支本機 API
  都達正式契約，平行任務尚未合併時必須在任何 reset 前 fail closed。待兩支 API 落到 main 後再執行
  腳本；這段只保留當時未修改 Serve 的歷史事實，不是現行遠端四路契約。
- 2026-08-14 依使用者最新決策修訂遠端契約：USD/TWD 是公開匯率資訊，Tailscale Serve 白名單由
  四路改為五路。實作須先把設定腳本、長期／操作文件與驗證矩陣同步成五路，再重跑五支本機 preflight、
  reset 前 TOCTOU／所有權檢查、安全 cleanup 與五路 tailnet HTTPS payload 驗證；完成前不得宣稱新的
  五路 Serve 已套用。root、`/api/`、unknown、額外 handler、Funnel、公網 listener、額外 OAuth 與自簽憑證仍禁止。
