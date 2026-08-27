# [t383] 9090 批次唯讀油價／金價 API 與 Swagger 文件

**對應 Requirements:** Requirement 118（新增一支 9090 唯讀 batch API，同時提供 WTI、Brent 與黃金已持久化即時報價）
**前置任務:** t337（commodity Redis live cache）、t372（public no-tenant BFF client）、t378（9090 OpenAPI／Tailscale exact-path parity）
**Liquibase changeset:** 無

## 背景

系統已經有三個 commodity code：WTI、BRENT、GOLD。external-materials 的 producer 將資料寫入 commodity:spot:{code} Redis key；business 的 CommodityLiveQuoteService 以 Redis spot cache 加 PostgreSQL 前收組成既有 GET /api/market-data/commodity/live。既有已登入頁面 GET /api/bff/commodity-price/live 也會轉送它，但該頁面 API 對 upstream error 刻意降級為空 quotes，不能成為批次 consumer 的 availability contract。

使用者要求在 port 9090 新增一支 API，以一次 GET 供批次查詢油價與金價，並要求 API 一增加就同步 Swagger、asset-management Markdown 與 /Users/steven/Project/SRPP/docs 下的 Markdown。這不是 request-time Yahoo 查詢、資料回補、排程、匯出、交易、下單、broker 或 tenant data 功能。讀取必須只使用既有已持久化的資料，並保留「單一 cache key 缺失」和「整個 downstream 不可用」的不同語意。

## 要做什麼

- [ ] **383.1 建立公開 BFF endpoint 與 immutable typed DTO。** 在 bff 建立 publiccommodity package，新增 PublicCommodityPriceController 和 PublicCommodityPriceService。inbound route 只能是 GET /api/public/commodity-prices，沒有 query parameter、request body、ownerId、cookie selector 或 caller identity。回應必為 immutable Java records：

  - CommodityPriceBatchResponse：boolean marketOpen 與 CommodityPriceSlots quotes；
  - CommodityPriceSlots：Jackson JSON property 順序和名稱精確為 WTI、BRENT、GOLD；三個 property 都不可省略，value 可為 null；
  - CommodityPriceQuote：commodityCode、unit、price、change、changePercent、sessionDate、quoteTime、polledAt、status、dayHigh、dayLow、provider。

  non-null quote 的 commodityCode 必和 slot 相同，且只能是 WTI、BRENT、GOLD；WTI 和 BRENT 的 unit 固定 USD_PER_BARREL，GOLD 固定 USD_PER_TROY_OUNCE。price 必為 positive finite decimal；provider 非空；sessionDate、quoteTime、polledAt 不可為 null 且格式正確；status 只能 LIVE、STALE、SETTLED；change/changePercent 只有在沒有前收時才能同時為 null，否則兩者皆為 finite decimal；dayHigh/dayLow 可以各自為 null，但 non-null 必為 positive finite decimal，且不可估算、補零或從歷史資料反推。marketOpen 必是 JSON boolean。不得回傳 Map、arbitrary JSON、raw Redis/vendor payload、外部 URL、帳戶、owner、持股、交易、broker 或 secret。

- [ ] **383.1a 先執行 inbound request gate。** Controller 必先取得完整 query MultiValueMap，只有沒有 named parameter 的 request 可繼續；bare trailing ? 視為空 query。任何 named、empty-value、repeated 或 multi-value query 皆回 400，且 service/downstream invocation count 為零。GET 的 positive Content-Length 或任何 Transfer-Encoding 也視為不合法 body、回同一個 400，不能消耗或轉送 body。這些 direct BFF 行為與經過 gateway/Tailscale 的行為必相同。

- [ ] **383.2 只讀既有 business 聚合，且不帶 tenant。** PublicCommodityPriceService 使用已存在、具 Qualifier publicMarketDataBusinessClient 的 WebClient。該 client 不掛 tenantHeaderFilter，禁止發出 X-User-* header 或從 Reactor context 取 caller identity。每個 valid request 只對 business-services 發一個 GET /api/market-data/commodity/live；不得直接注入或使用 Redis、repository、JPA、external-materials client、Yahoo、Fubon、scheduler、cache writer、export、refresh、broker 或 filesystem client。既有 business CommodityLiveQuoteService 是唯一可讀 Redis commodity spot 和 PostgreSQL 前收的聚合；不得複製或改寫其 calculation。不得呼叫 /api/market-data/commodity/refresh 或 /api/market-data/commodity/live-refresh，也不得重用既有 CommodityPriceBffController 的 onErrorReturn 空 response。

- [ ] **383.3 映射與錯誤語意固定。** business 回的 marketOpen 原樣投影，但必驗為 boolean。business 為每一個 WTI/BRENT/GOLD spot miss 或 malformed cache entry 回 null 時，BFF 必輸出三個固定 slots、保留該 slot null，並回 200；null 表示已持久化 spot 不可用，不是 0、假日、交易停止、refresh 已發動或 business service down。BFF 只接受固定三 keys；missing/extra key、inner commodityCode 不相等、nonpositive/nonfinite price、required date/time/status/provider 缺失、unknown status、nonpositive non-null dayHigh/dayLow、change/changePercent 非同時 null 或同時 finite numeric、wrong numeric/date type、empty body 或無法映射都回 sanitized 502。既有 RedisCommoditySpotCacheAdapter 僅驗 status nonblank／數值可解析，不能當 public gate。business non-2xx 也回 502；connection/transport failure 回 503；bounded timeout 回 504。不得洩漏 downstream URL、HTML、exception、stack trace、headers 或 tenant identity。所有成功和失敗分支都不可造成 DB、Redis、vendor、排程、檔案、郵件、broker、訂單或交易 mutation。

- [ ] **383.3a 固定 failure body 與 timeout。** downstream timeout 必為五秒。PublicCommodityPriceExceptionAdvice 對 BFF 自產的 400、502、503、504 一律輸出 application/problem+json ProblemDetail：type=about:blank、instance=/api/public/commodity-prices、沒有 extension。title/detail 逐一固定為 Invalid commodity price request／不支援 query parameter 或 request body、Commodity prices downstream failure／商品報價暫時無法取得、Commodity prices service unavailable／商品報價服務暫時無法連線、Commodity prices timeout／商品報價服務逾時。Nginx 自己的 BFF-connect/timeout text/html 502/504 是另一個明列於 OpenAPI 的 gateway alternative，不能被 advice 冒充。

- [ ] **383.3b 保證 sanitized advice 優先。** PublicCommodityPriceExceptionAdvice 必為 RestControllerAdvice(assignableTypes = PublicCommodityPriceController.class) 且使用 Order(Ordered.HIGHEST_PRECEDENCE)，使它優先於既有會 raw-relay WebClientResponseException 的全域 BusinessErrorAdvice；或 service 必把每個 non-2xx 轉成專屬例外，確保 WebClientResponseException 永不外溢。無論做法，測試都必同時註冊全域 BusinessErrorAdvice，並以含 sentinel upstream raw body 的 non-2xx 證明只有固定 502 ProblemDetail、完全不含 sentinel/raw content。

- [ ] **383.4 只放寬精確 BFF security／gateway network edge。** BFF SecurityConfig 只新增 HttpMethod.GET 的精確 /api/public/commodity-prices permitAll；不能放寬 /api/public/**、/api/bff/** 或任何 business route。api-gateway/nginx.conf 新增一個 location = /api/public/commodity-prices，method 不是 GET 時 405 並帶 Allow: GET，GET 才 proxy 到 bff:8080。frontend nginx 對同一路徑保持 exact 404 deny。新路徑的 child、trailing slash、matrix variants 與未知路徑必 404。scripts/configure-tailscale-api-gateway.sh 和其 fake CLI regression test 將此 path 加到唯一 exact Serve manifest 和 preflight；preflight 必對本機 GET 驗 200、application/json、marketOpen boolean 與 WTI/BRENT/GOLD 三個 slots 都存在，且絕不 POST /api/public/crawler-data/rescan。不得加 root proxy、Funnel、host port、OAuth/API key 或 wildcard。

- [ ] **383.5 更新 OpenAPI、生成文件與 current-state 說明。** docs/openapi/docker-external-api.yaml 是唯一 source of truth。新增 GET /api/public/commodity-prices，operationId getPublicCommodityPrices，無 parameter/request body，完整列出 200、400、405、502、503、504。新增固定 typed schemas CommodityPriceBatchResponse、CommodityPriceSlots、CommodityPriceQuote，每個 class/property/enum/array（若有）均需具體 description；明載 WTI/BRENT/GOLD slot order、unit mapping、nullable semantics、positive price、status provenance、provider、persisted Redis/DB pure-read、no-tenant and no-side-effect boundary。400、502、503、504 必各自有 application/problem+json ProblemDetail exact title/detail/example，另於 502/504 說明並列 Nginx text/html gateway alternative。OpenAPI version 從主線已合併的 1.9.0 固定升為 1.10.0；若合併時已有並行 contract 升版，採唯一遞增後的版本，絕不倒退。更新 scripts/tests/docker-external-api-openapi-test.rb 的 exact 13-route manifest、operationId、response status/schema and property/type/nullability/description assertions；更新 renderer 所需 contract checks。執行 ruby scripts/render-9090-openapi-docs.rb 生成 docs/openapi/9090-api-swagger.md 與 /Users/steven/Project/SRPP/docs/9090 Port API Swagger.md；兩檔必 byte-identical，不能手編 generated Markdown。同步更新 CLAUDE.md、spec/steering/structure.md 及必要的 current API markdown，使其宣告目前 9090 為 13 條 exact route（12 GET、1 POST）且列出新 path，不能保留 12 條作現行上限。

- [ ] **383.6 寫 focused tests 與執行真 stack 驗證。** BFF tests 覆蓋：single exact downstream URI；no X-User-* header/Reactor identity；valid three quote projection and unit mapping；marketOpen false；each cache-miss/malformed slot remains a 200 null while all three JSON slots exist；query and GET body gate=400 with zero downstream invocation；exact four ProblemDetail bodies；bad/missing/extra downstream payload/non-2xx=502；unknown status、zero/negative dayHigh/dayLow、unpaired change/changePercent、invalid marketOpen/type=502；non-2xx test 與全域 BusinessErrorAdvice 同時註冊且 sentinel raw body 不外洩；transport=503；five-second timeout=504；no direct Redis/DB/vendor/refresh interactions。Security tests 覆蓋 exact GET anonymous but POST/child are not widened. Gateway/frontend/Tailscale tests 覆蓋 13 route manifest、new GET target、query/body 400、405/404 boundaries、frontend deny and content-type/payload preflight. OpenAPI and renderer tests cover operationId、all statuses、fixed slots、type/format/nullability/descriptions、status enum、positive high/low、paired nullable change fields、BFF/Nginx error alternatives and two Markdown mirrors. Rebuild/recreate bff、api-gateway、frontend（business source untouched 時不重建 business）；inspect the actual running Compose source/image provenance. Through 127.0.0.1:9090 call the new GET and gates, assert status/content-type/fixed keys. no-side-effect evidence is the focused BFF mock verification of the single WebClient and zero write dependency, not shared Redis/DB before-after values that race a background producer. Do not call crawler rescan again for this task.

## 驗證

依序執行：

    set -euo pipefail
    bash scripts/spec-check.sh
    /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
    ruby scripts/tests/docker-external-api-openapi-test.rb
    ruby scripts/render-9090-openapi-docs.rb --check
    bash scripts/tests/configure-tailscale-api-gateway-test.sh

    # run-stack must build/recreate the changed services from this worktree, then verify the actual Compose provenance.
    docker compose -p asset-management build --no-cache bff api-gateway frontend
    docker compose -p asset-management up -d --no-deps --force-recreate bff api-gateway frontend
    docker compose -p asset-management ps
    curl -fsS -D /tmp/t383-headers.txt http://127.0.0.1:9090/api/public/commodity-prices

The final runtime check must parse the response and prove marketOpen is boolean, quotes has exactly WTI/BRENT/GOLD in that order, and every slot is either null or a complete typed quote. It must also prove query and GET-body requests receive the fixed 400 ProblemDetail without an upstream call, and frontend receives 404 for the exact API path. It must not call any POST endpoint, trigger Yahoo, modify a producer schedule, or fabricate a quote when the market/cache has no live data.

## 完成報告

（實作者完成後回填：實際修改檔案、BFF/gateway/Tailscale/OpenAPI/Markdown 驗證結果、Docker image provenance、runtime payload 與 no-mutation evidence，以及未能驗證的外部 Tailscale 項目。）
