# [t376] 9090 交易雷達列表／指定股票明細與完整 OpenAPI 3 標準文件

**對應 Requirements:** Requirement 111（交易雷達外部 API 拆成首頁列表與指定股票展開資料，並讓每次 9090 契約變動同步產生完整標準文件）
**前置任務:** t347、t375
**Liquibase changeset:** 無

## 背景

目前 9090 的 `GET /api/public/trading-radar/today` 直接 relay configured admin 的完整 `TradingRadarDto.Response`。每一檔 `stocks[]` 都有展開面板才會使用的基本面來源、證據、風險、K 棒及指標樹；首頁其實只需要收合列與頁首市場／公開資訊。這使第一個頁面負載過大，也不能在 API 層清楚分辨「所有股票首頁資料」和「指定股票展開後資料」。

同時，t375 已將 `docs/openapi/docker-external-api.yaml` 建為 9090 path/method 的機器契約，並建立 YAML-to-Markdown generator；本 task 必須延續它補齊新增／變更 class attribute。使用者指定 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md` 為 9090 對外標準文件，而且每次修改 9090 API 都要從 OpenAPI 3 source 同步重產／覆寫。

本任務將 `today` 改為列表契約，新增一支 exact detail API。這是公開 `today` response 的刻意 breaking change：consumer 不得再假設列表中含完整 `StockDecision`，展開資料必須呼叫 detail API。兩支 API 都只讀 configured admin 當下組裝結果，絕不觸發交易、更新、匯出或持久化。

## 要做什麼

- [ ] 376.1 **新增雷達明細的精確 gateway route。** 保留 `GET /api/public/trading-radar/today`，但改為首頁列表；新增且只新增 `GET /api/public/trading-radar/stock?stockCode=&market=`。更新 `api-gateway/nginx.conf`、BFF `SecurityConfig`、Tailscale Serve script／mock／測試、frontend exact deny、`CLAUDE.md`、`spec/steering/structure.md`、`spec/steering/tech.md`、`scripts/README.md`、`.agents/skills/run-stack/SKILL.md`、`INSTALLATION.md` 與 OpenAPI path set；本任務獨立驗收時使 manifest 由 9→**10** 條 exact path/method（9 GET、1 POST）。t377、t378 隨後才分別提升為 11、12 條；所有 current-state 文件的最終 12 條描述只在四個 task 都完成後成立。Tailscale preflight 必先驗 today=200：若 `stocks[]` 非空，取第一列 URL-encoded exact selector 驗 stock=200；若空，對空白 `stockCode`／`market` 驗消毒 400 與 JSON Content-Type，以證明 detail route 的 validation 邊界而不捏造 selector 或把合法空清單當故障。不得加 `/api/public/trading-radar/**` wildcard、dynamic path variable、root proxy、Swagger UI route 或 host port。這兩條 exact GET 的其他 method 要 405 `Allow: GET`；未知、child、trailing slash、matrix path 一律 404。

- [ ] 376.2 **讓 business 層以 typed projection，不是 controller shaping。** 新增 dedicated internal controller 的 exact `GET /internal/public-trading-radar/current/list` 及 `GET /internal/public-trading-radar/current/stock?stockCode=&market=`；不得把它們放在 `/api/trading-radar/current/*`，否則既有 `TradingRadarBffRoutes` 的 `/api/bff/trading-radar/**` rewrite 會讓 browser 直接 proxy。各 controller method 只 validation＋委派一個 dedicated projection service；兩個 mapping 不進 9090、Tailscale、frontend、OpenAPI gateway manifest，也不得以 `/internal/public-trading-radar/**` wildcard 放行。service 每個 request 只呼叫一次現有 `TradingRadarService.getCurrent()`，絕不呼叫會寫 export snapshot 的 `get()`，也不得 refresh、export、通知、寫 snapshot／Redis／DB、呼叫券商或任一交易功能。detail 以 trim 後、exact `(stockCode, market)` 選當次 response 的 `stocks[]`；code 要符合既有 `^[A-Za-z0-9.\\-]{1,12}$`，market 要符合既有 `^[\\p{L}0-9]{1,10}$`，缺值／空白／格式錯誤回消毒 `400`，合法但沒有同時匹配的 stock 回消毒 `404`。不查任意股票、不得跨 owner 或把同代號不同市場資料混用。新增 regression test：任一 `/api/bff/trading-radar/current/list`／`stock` 或其他 browser inbound path 均不得到達這兩個 internal mappings。

- [ ] 376.3 **凍結兩個 public DTO shape。** 列表成功回 `TradingRadarListResponse`，必要 top-level property 為 `ruleVersion`、`actionPolicyVersion`、`generatedAt`、`market`、`usMarket`、`stocks`、`skippedNonTwStocks`、`publicInformation`；`market`／`usMarket` 直接使用完整既有 `MarketSummary`，`publicInformation` 直接使用完整既有 `PublicInformationItem[]`。`stocks[]` 每一列用 typed `TradingRadarListStock`，且包含：`stockCode`、`stockName`、`market`、`assetClass`、`distributionAdjusted`、`held`、`fxPercentile`、`underlyingCurrency`、`fundamental`（只含 `applicable`、`coverage`、`industryName`、`industryRevenueYoyPct`）、`shortAction`、`shortActionLabel`、`shortScore`、`swingAction`、`swingActionLabel`、`swingScore`、`action`、`actionLabel`、`score`、`horizonConflict`、`timingState`、`timingLabel`、`counterTrendState`、`counterTrendLabel`、`price`、`changePercent`、`quoteStatus`、`etfPremiumLivePct`、`etfPremiumLiveNavAsOf`、`weeklyMa`、`monthlyMa`、`quarterlyMa`、`annualMa`、`kValue`、`dValue`、`kdHeat`、`weeklyIndicators`、`asOfDate`。null、empty array 與 numeric precision 必維持 full current result 原本語意；`stocks[]` 保持 current result 的順序。列表不得含 `reasons`、`risks`、完整 `fundamental` source tree、`evidence`、`extendedIndicators`、`dailyCandle` 或任一展開-only field。

- [ ] 376.4 **detail 回完整展開列。** 指定股票成功回 `TradingRadarStockDetailResponse`，必要 top-level property 為 `ruleVersion`、`actionPolicyVersion`、`generatedAt`、`market`、`usMarket`、`stock`。`stock` 的 type 是現有完整 `TradingRadarDto.StockDecision`，其 JSON fields、nested nested data、nullable／empty 語意都不可裁切、改名、重算或用 loose `object` 替代。這使 API consumer 能取到 UI 展開列的完整基本面、來源 URL、公開資訊、判斷證據、reasons／risks、risk coverage、candidate／gate、ETF dated/live premium、daily candle、weekly indicators、extended indicators 和三軌資料。list 與 detail 是各自獨立 HTTP current-read；每個 response 內 metadata／market context 必須出自同一次組裝，`generatedAt` 供 consumer 比較兩次呼叫是否跨更新，BFF 不可把兩次不同結果在單一 response 中拼接，也不得加 snapshot cache/token 假裝跨呼叫一致。

- [ ] 376.5 **BFF configured-admin boundary 與錯誤 shape。** public BFF controller 只委派 service；service 先 `configuredAdmin()` bootstrap，驗證 configured／active admin 後才以顯式 `X-User-*` header 呼叫相對應 business current endpoint，且 outbound Reactor context 必須移除 caller identity。caller 的 ownerId、email、cookie、`X-User-*`、Tailscale headers 都不可選 owner。business non-2xx／decode 轉 sanitized 502、bootstrap／transport 轉 503、timeout 轉 504；detail 的 backend 404 必需 safe relay／map 成外部 404，不能被一般 downstream exception advice 改成 502。BFF 不直查 DB、不可只為 detail 另做 background fetch。

- [ ] 376.6 **完整 OpenAPI 3 field documentation。** 在 t375 generator foundation 上更新 `docs/openapi/docker-external-api.yaml`：本 task 的 path/method manifest 精確為 **10** routes（9 GET、1 POST），含新 operationIds、parameters、success/error response、security、examples 和 response refs。每一 operation 要寫具體用途、讀取或副作用、資料邊界及 freshness；每個 parameter／request body／header／response／schema／schema property／array item／enum 都要有非空且語意具體的 `description`，並保留 OpenAPI type、format、required、nullable、items 和 ref。既有 routes 與所有可達 component schemas 同樣補齊，不可只做新雷達 schema，也不可用 generic object、additionalProperties map、欄名重述或「資料」等空泛文案取代 attribute 說明。新增／更新 Ruby OpenAPI contract test，機械驗：gateway path/method parity、10 route count、列表／detail schema 的 required and excluded fields、detail ref 為 full `StockDecision`、所有操作及可達 schema/property description 完整；t377、t378 再依序將同一 assertion 提升至 11、12。

- [ ] 376.7 **從 YAML 重產兩份完整 Markdown。** 重用 t375 已建立的 deterministic generator（Ruby stdlib、`--check`、YAML 是唯一 input）。每次跑產生模式同時寫 `docs/openapi/9090-api-swagger.md` 與精確目標 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`，兩檔 byte-for-byte 相同；`--check` 在任一檔不存在、內容不同、YAML 缺 documentation 或 target 無法存取時非零退出。產出至少包含：9090 endpoint／Tailscale 安全邊界、路由總覽、每個 API 的用途、完整 query/path/header/body input 表、各 HTTP response、所有 class/schema 的 attribute table（名稱、用途、type／format、required、nullable、enum、array item/ref），並清楚標示列表／detail 的 breaking change 與 query selector 用法。將 generator check 納入自動測試或可重複驗證指令，令以後所有 9090 API 改動若不重產兩份文件會失敗。

- [ ] 376.8 **測試與 runtime 驗證。** 新增或更新 backend tests（list 投影完整、detail full response、跨市場同代號、400／404、單次 `getCurrent()`、零 refresh/export/write）、BFF tests（configured admin、identity clear、query encoding、sanitized errors／404）、Nginx／Tailscale script tests（新 path 與 exact 404／405）、OpenAPI／generator tests。先跑受影響 Maven module tests（若完整 suite 因已知 Java 25 Mockito baseline 失敗，記錄 baseline 並使用 `-Dnet.bytebuddy.experimental=true` 重新跑 target tests），再由 run-stack 使用 main 產生 image rebuild + recreate `business-services`、`bff`、`api-gateway`。透過 `http://127.0.0.1:9090` 先 GET `/api/public/trading-radar/today`，從回應選同一 `stockCode` 和 `market` 再 GET `/api/public/trading-radar/stock`；驗列表首頁資料完整而沒有展開樹、detail 完整、metadata 合理、非 GET 與錯誤 path 邊界正確，並確認無 refresh／export／snapshot／Redis／DB 寫入。

## 驗證

```bash
bash scripts/spec-check.sh
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
docker compose -p asset-management build --no-cache business-services bff api-gateway
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff api-gateway
curl -fsS http://127.0.0.1:9090/api/public/trading-radar/today
```

runtime 驗證時不得呼叫 `POST /api/public/crawler-data/rescan`；列表回應至少有一列時，以該列的 URL-encoded `stockCode`、`market` 呼叫 detail 並檢查 `stock`。最後再執行 `ruby scripts/render-9090-openapi-docs.rb --check`，確認本專案與 SRPP target 均為 generator 的同一份輸出。

## 完成報告

（實作者做完後回填：實際改了哪些檔、OpenAPI／Markdown 產生結果、測試與 Docker runtime readback、以及與原計畫的偏差及原因。）
