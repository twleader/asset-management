# [t325] 最新全資產唯讀 API 接入 Nginx 9090

**對應 Requirements:** Requirement 68（最新全資產唯讀 API）
**前置任務:** Task 313（market status／display session）、Task 328（Nginx 9090／Tailscale exact gateway）
**Liquibase changeset:** 無（本任務不改 DB schema）

## 背景

Docker 外部 API 已由獨立 non-root Nginx `api-gateway` 收斂：host 唯一映射是
`127.0.0.1:9090:9090`；BFF 與 external-materials-service 都沒有 host port。Gateway 已預留
exact `GET /api/assets/latest` → `bff:8080`，frontend 對五條 external-only path 及 matrix-parameter
變體回 404；Tailscale Serve 只用五條 path-scoped mount，不開 root、`/api/` wildcard、Funnel 或公網 listener。
本任務不得重做或繞過這個 gateway，也不得在 BFF 代理 quotes。

本任務實作 `/api/assets/latest` 的業務內容。它固定查部署設定的主要管理者，不接受租戶選擇參數；
資產 detail 取最新 snapshot，股票現值與 Dashboard／最新資產匯出共用
`StockPriceService.getLiveAssets()`。各市場日期沿用
`PriceQueryService.displaySession(...).targetTradingDate()`：開盤中是當日，收盤後也是當日，盤前／週末／休市日
是最近前一交易日。任何前收、日期不可信或 snapshot fallback 都須明示，不能冒充 target-session 價格。

## 要做什麼

- [x] **325.1 Gateway 整合邊界：**
  - 只接上既有 Nginx exact `/api/assets/latest` route；不修改 gateway upstream/path allowlist、Compose host ports、Tailscale ownership 或 frontend deny 規則。
  - Host 正式入口是 `GET http://127.0.0.1:9090/api/assets/latest`，container network 入口是 `GET http://bff:8080/api/assets/latest`。BFF/business 不新增 `ports:`。
  - Nginx exact path 的非 GET 由 gateway 回 405 且 `Allow: GET`；descendant、`/internal/*`、`/actuator/*` 與未知 path 回 404。Frontend port 80 對 exact 與 matrix variants 回 404。

- [x] **325.2 Business 最新資產聚合：**
  - 新增 immutable `LatestAssetsDto.Response(generatedAt, valuationPolicy, targetPriceComplete, marketStatus, snapshot, liveAssets)`；`generatedAt` 為 `Instant`，`valuationPolicy` 固定 `TARGET_SESSION_WITH_EXPLICIT_FALLBACK`。
  - 新增 `LatestAssetsService`，單一 `@Transactional(readOnly=true)` 內取得 owner-filtered 最新 snapshot、既有完整 `AssetSnapshotDto.SnapshotDetailResponse`、`StockPriceService.getLiveAssets()` 與 market status；驗證 `snapshot.id == liveAssets.snapshotId`。無 snapshot 回 typed 404；identity 不一致不得回 200。
  - 新增薄 `LatestAssetsController` 的 exact `GET /api/assets/latest`。Controller 不注入 repository、不組業務資料；business service 仍只在 Docker network。
  - Snapshot detail 保留 deposits（含 `TRANSIT_*` 在途款）、funds、stocks 成本／券商欄位；outer response 不加入 owner id/email。

- [x] **325.3 日期與估值 provenance：**
  - `StockPriceService.getMarketStatus()` 在既有布林與三地時間後加入 `twTradingDate/usTradingDate/ukTradingDate`，三值各由現有 `PriceQueryService.displaySession(market).targetTradingDate()` 取得，不複製日曆或開收盤算法。
  - `StockPriceService.LiveStockItem` 末端加入 `holdingId`、`source`、`targetTradingDate`、`valuationSource`。`holdingId` 取 snapshot stock row id；實際 `tradingDate`／`quoteStatus`／`source` 照實保留。
  - 只有可解析且 quote `tradingDate == targetTradingDate` 才標 `TARGET_SESSION_PRICE`；可解析且早於 target 才標 `PREVIOUS_SESSION_PRICE`。有價格但日期缺失、無法解析或晚於 target 時維持既有估值金額但標 `UNVERIFIED_SESSION_PRICE`；無可用 quote 而沿用 snapshot current value 才標 `SNAPSHOT_VALUE`。
  - 只有所有股票都是 `TARGET_SESSION_PRICE` 時 outer `targetPriceComplete=true`；任何其他分支都為 false。既有 Dashboard、live-assets 與匯出繼續消費同一 response，不新增另一套金額算法。

- [x] **325.4 Configured-admin bootstrap 與 BFF relay：**
  - `ADMIN_EMAIL` 只注入 business-services；BFF 不讀取、保存或自行比對 email。Business 在既有 `/internal/users/**` 命名空間新增無參數、network-only 的 exact `GET /internal/users/configured-admin`，沿用 `UserAdminService.isConfiguredAdmin`／部署設定回既有 user projection。
  - `AdminGateInterceptor` 只以 path equality＋GET method 對該 bootstrap call 無 header 放行；同 path POST、descendant 與其他 `/internal/users/**` 仍要求 ADMIN。
  - `LatestAssetsPublicService` 只接受 lookup 回來的 `id != null`、`configuredAdmin=true`、`status=ACTIVE`；否則回 typed 503。後續 business request 顯式設定該 user 的 `X-User-Id/Role/Status`，並刪除 shared WebClient Reactor identity context，避免匿名呼叫被既有登入／代看 context 覆寫。
  - Endpoint 不接受 `ownerId`、email、cookie 或 `X-User-*` 作租戶選擇；inbound 偽造 header 仍由 `TenantWebFilter` 移除。
  - Business 2xx 以原始 JSON bytes relay，不得先 decode 成 `Map`／`Double`；relay 前仍以 `JsonNode` 驗 body 非空、JSON 可解析，且 `snapshot.id`／`liveAssets.snapshotId` 為相同整數。空 body、malformed JSON 或 mismatch 回 502 ProblemDetail；downstream 非 2xx 的 status/body/content type 原樣保留。
  - 新增薄 `LatestAssetsPublicController` 的 exact GET，只委派 service；無快照 404、transport／decode／identity 錯誤都不得降級成空 200。

- [x] **325.5 精確匿名規則：**
  - BFF `SecurityConfig` 在既有 market-index 例外旁只新增 `HttpMethod.GET, "/api/assets/latest"`。Quotes 由 Nginx 直接送 external-materials，BFF 不新增 quote route／URL 設定／permit。
  - Latest 同 path POST/PUT/PATCH/DELETE、`/api/assets/latest/**` 與相鄰 private BFF route 匿名時維持 401；不得新增 `/api/**`、`/api/public/**` 或其他 wildcard permit。

- [x] **325.6 測試：**
  - Backend：完整 snapshot/live/status aggregation、無 snapshot 404、ID mismatch、三市場 target trading date，以及 target／previous／unverified／snapshot 四類 provenance 與 `targetPriceComplete`。
  - Bootstrap/BFF：configured-admin 存在／不存在／非 ACTIVE／旗標不符、顯式 tenant headers、Reactor context 清除、偽造 inbound header 不生效、raw-byte 金融精度、malformed/empty/mismatch 502、非 2xx 原樣 relay，並證明 BFF 無 `ADMIN_EMAIL` 與 quote proxy 依賴。
  - Security：latest exact GET 可進 controller；其他 method、descendant 與 private route 仍 401。既有 market-index、backend、BFF 全量測試與 frontend build 必須通過。

- [x] **325.7 Docker 實機驗證：**
  - 先確認 Compose project/container/image provenance，再從本 worktree無快取重建 `business-services bff frontend api-gateway`，以 `--force-recreate` 部署；business recreate 後 BFF 必須使用同輪新 image recreate，bounded wait 到 healthy。
  - `docker compose config`、port inspection 與 host listener 證明只有 api-gateway 綁 `127.0.0.1:9090->9090`，BFF/external 無 host mapping，8080/8082 不 listen。
  - Host 9090 與 container network 都取得非空 latest JSON；驗 `snapshot.id == liveAssets.snapshotId`、金融數字未改寫、每列 provenance 與 outer `targetPriceComplete` 自洽。
  - 驗 gateway methods/descendants/internal deny、frontend exact/matrix deny，並在結論前重查 workdir/image ID 未被 sibling worktree 覆蓋。
  - 若 Tailscale Serve 的一次性核准已完成，執行五路本機 preflight 後驗 tailnet HTTPS 五條 exact path，assets/latest 必須是真實 200 payload；若尚未核准，明列外部互動待辦，不得以本機或 stub 冒充遠端通過。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
docker compose -p asset-management config
docker compose -p asset-management build --no-cache business-services bff frontend api-gateway
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend api-gateway
curl -fsS http://127.0.0.1:9090/api/assets/latest
```

## 完成報告

- 實作：business 新增 configured-admin bootstrap、最新快照／live-assets／market-status 的同 transaction 聚合，並在既有 `StockPriceService` 補上三市場 target trading date 與逐持股 `holdingId/source/targetTradingDate/valuationSource`。BFF 只精確放行 latest GET，以 configured admin 顯式 tenant headers 呼叫 business，清除匿名呼叫可能繼承的 Reactor 身分 context，2xx 原始 JSON bytes 驗 identity 後 relay；非 2xx 保留 status/body/content type。沒有新增 BFF quote proxy、owner selector、host port或第二套估值算法。
- 自動驗證（2026-08-14）：`spec-check` 為 `BLOCK 0 / CHECK 0`；backend 907/907、BFF 63/63 全量測試通過，frontend production build 通過。涵蓋 target／previous／unverified／snapshot provenance、configured-admin 與 ACTIVE 守門、偽造身分隔離、raw-byte 精度、malformed／mismatch fail-closed，以及 BFF exact security matrix。
- Docker runtime（feature worktree 無快取 rebuild/recreate）：`business-services=a547d4334a8d`、`bff=b6dc3ec5c4c3`、`frontend=fb98a5e44698`、`api-gateway=efd32859aebf`，四者 Compose workdir 均為本 feature worktree。Host 只有 `127.0.0.1:9090->9090`；BFF／business／external 只有 container port，host 8080/8082 無 listener。Host 9090 與 container-network BFF 均取得真實 200 JSON；snapshot/live id 均為 15，含 deposits 15、funds 1、stocks 44，三市場 target date 均為 `2026-08-13`，44 列皆為 `TARGET_SESSION_PRICE`，故 `targetPriceComplete=true`。
- 邊界矩陣：gateway 五路 × POST/PUT/PATCH/DELETE 共 20 項全為 405 且 `Allow: GET`；11 個 descendant/internal/actuator/未知 path 全為 404。Frontend 五路 exact 加逐 segment matrix 變體共 20 項全為 404；trailing slash／descendant 刻意回落既有 authenticated BFF，匿名 401，不是第二個公開入口。
- Tailscale：裝置為 Running/Online，但 Serve 目前刻意維持 `{}`。五路設定腳本已修正兩處 shell 變數緊鄰全形分號造成的 unbound-variable 問題；實際 preflight 在尚待 Task 327 落地的 USD/TWD 回 401 時正確於 reset 前 fail closed，Serve 前後皆 `{}`。因此本任務只證實 assets/latest 的本機與 container 真實 payload；五路 tailnet HTTPS 正向驗證由 USD/TWD 落地後統一完成，未以 stub 或本機結果冒充遠端通過。
