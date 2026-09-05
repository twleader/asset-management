# [t416] 9090 個人資料公開端點新增 email 參數選擇帳號

**對應 Requirements:** Requirement 140（9090 個人資料公開端點依 email 參數選擇帳號，取代固定 configured-admin）
**前置任務:** 無
**Liquibase changeset:** 無（不動 DB schema）

> 接手狀態：下方既有勾選僅表示前一輪曾完成的實作宣告，不得視為驗收完成。本檔的「接手修正與完成門檻」優先於先前條目與未驗證完成報告；所有待修項、架構審查、Docker 實機驗收與 commit → no-ff merge → push 完成後，才可宣告 Task 416 完成。

## 背景

`GET /api/assets/latest`、`GET /api/public/portfolio-advice/latest`、`GET /api/public/trading-radar/today`、`GET /api/public/trading-radar/stock`、`GET /api/public/transactions` 這五支 9090 對外公開 GET，目前 owner 一律固定為 `BusinessUserClient.configuredAdmin()` 解析出的唯一 configured admin（`ADMIN_EMAIL` 所指定帳號）。使用者要求：這些回傳個人資料的 API 應該依「使用者的 email」查回該帳號的資料，而不是永遠固定回傳主要管理者的資料；查詢別人就傳別人的 email。

**這五支端點今天是完全匿名、無 application 層驗證的公開端點**（經 `127.0.0.1:9090` loopback 與 Tailscale tailnet HTTPS `:9090` 對外，見 `spec/design.md` 既有段落「路由本身無任何鑑權」）。固定回傳單一 configured-admin 是刻意設計：即使網路邊界被突破，曝險範圍也鎖死在 1 個帳號。本任務要新增的「依 email 查任意帳號」功能，會把曝險範圍從「1 人」放大成「系統裡所有 active 帳號」——**因為 email 不是密碼，任何人只要知道一個有效帳號的 email 就能透過這五支端點讀到該帳號完整的資產、投資組合建議、交易雷達個股決策與交易紀錄。**

這個安全取捨已經在對話中完整攤給使用者（系統 owner）：現況本就沒有 application 層驗證，只是波及範圍固定為 1 人；開放 email 參數會把波及範圍放大到全部帳號。使用者在理解這個差異後明確選擇「知情且接受，直接開放 email 參數」，不要求新增驗證層（token/API key 等）。**本任務的正確行為就是「不加驗證、直接依 email 查帳號」——這不是實作疏漏，是使用者的明確決定，不要自作主張加上驗證層,也不要因為看起來不安全就拒絕實作或改為別的設計。** 如果你（實作者）認為這個決定有問題，先停下回報，不要靜默加驗證或靜默不做。

## 要做什麼

- [x] 416.1 五支 controller 各自新增 `@RequestParam(required = false) String email`，原樣傳給對應 service 方法，method 簽章與既有其他參數（`stockCode`／`market`／`year`／`start`／`end`）並列，不改變既有參數的驗證邏輯或順序：
  - `LatestAssetsPublicController.getLatest(String email)` → `LatestAssetsPublicService.getLatest(String email)`
  - `PublicPortfolioAdviceController.latest(String email)` → `PublicPortfolioAdviceService.latest(String email)`
  - `PublicTradingRadarController.today(String email)` → `PublicTradingRadarService.today(String email)`；`PublicTradingRadarController.stock(stockCode, market, String email)` → `PublicTradingRadarService.stock(stockCodes, markets, String email)`（`PublicTradingRadarService` 的 `today()`／`stock()` 共用私有 `relay()`，`email` 作為 `relay()` 的額外參數往下傳，不得複製一份 `relay()`）
  - `PublicTransactionHistoryController.current(year, start, end, String email)` → `PublicTransactionHistoryService.current(years, starts, ends, String email)`

- [x] 416.2 五支 service 在既有 bootstrap 之前插入分歧：`email` trim 後非空白時走新分支，否則走既有 `users.configuredAdmin()` 分支（**逐位元組不變**，包含 `admin.configuredAdmin()==true` 與 `admin.isActive()` 的既有檢查、既有 Unavailable 例外訊息、既有 503 契約）。

- [x] 416.3 新分支的 email 格式驗證：長度 ≤ 254 字元，且匹配 `Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE)`（與 `backend/src/main/java/com/steven/assets/service/UserAdminService.java` 既有 `EMAIL_PATTERN` 語意一致；BFF 與 backend 是各自獨立 module，不共用程式碼，BFF 端另建同語意 regex 常數）。不合法格式在呼叫 `byEmail` 之前就要擋下並回 400（沿用各端點既有 Request/Invalid 例外類別慣例；`LatestAssetsPublicService`／`PublicPortfolioAdviceService` 目前沒有對應的 400 例外類別，需各自新增一個同慣例的類別，例如 `LatestAssetsRequestException`／`PublicPortfolioAdviceRequestException`，並在各自 ExceptionAdvice 加 `@ExceptionHandler` 回 `HttpStatus.BAD_REQUEST`）。

- [x] 416.4 格式合法的 email：呼叫既有 `BusinessUserClient.byEmail(email)`（對應 business 既有 `GET /internal/users/by-email`，已由 `AdminGateInterceptor` 免 ADMIN 放行，**不新增 business controller mapping**）。解析出的帳號只需 `id != null`、`role != null`、`status != null`、`isActive()==true` 即可使用；**明確不得檢查 `configuredAdmin()==true`**——這是與既有 bootstrap 分支的唯一差異，沿用該檢查會讓所有非 admin 帳號永遠落入下一步的「不可用」分支，等於沒做到這個任務要做的事。

- [x] 416.5 email lookup 的空回應、回傳帳號欄位缺失／非 ACTIVE、HTTP 4xx/5xx、連線／逾時與解碼錯誤，必須使用該端點既有的 unavailable exception class 回固定 503，且同一端點所有這些 email lookup failure 的 status、title、detail 與完整 body 必須完全相同。此處「既有 configured-admin unavailable」只指定 exception 類別與 503 status，不要求冒充 configured-admin 分支內本來就不同的「尚未建立／不可用」detail；email branch 的 canonical body 明定為：Latest Assets title `Latest assets unavailable`、detail `指定帳號不可用`；Portfolio Advice title `Portfolio advice unavailable`、detail `指定帳號不可用`；Trading Radar 維持既有 no-arg unavailable body；Transaction History 維持既有 no-arg unavailable body。不得讓 lookup 的 WebClientResponseException 或上游 body 穿透匿名 9090。

- [x] 416.6 byEmail 是 identity bootstrap call：`BusinessUserClient.byEmail()` 本身必須在整個 publisher 加 `.contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY))`，使 lookup request 不帶 `HDR_USER_ID`／`HDR_USER_ROLE`／`HDR_USER_STATUS`。兩個 owner 分支合流後的資料讀取 request 則維持既有顯式三個 header 加上刪除 context 的寫法，header 值只可來自 configured-admin 或 by-email 解出的帳號。

- [x] 416.7 `PublicTransactionHistoryService.current()` 的 year/start/end 驗證與 `PublicTradingRadarService.stock()` 的 singleSelector 驗證必須保留原有合法 email／無 email 路徑的行為。若 email 與 selector/filter 同時不合法，採明確的 email 優先：先執行 email 格式驗證並回 `email 格式不合法`，不得呼叫 byEmail；email 合法或省略後才維持既有 selector/filter 的 400 契約。

- [x] 416.8 Gateway（`api-gateway/nginx.conf`）、Tailscale Serve 設定**不需修改**——五個既有 exact `location`／path-scoped mount 已透傳 `$request_uri`／不解析 query string，`email` 會自動透傳。**不要**因為這個任務去動 `nginx.conf` 或 Tailscale 設定腳本。

- [x] 416.9 `docs/openapi/docker-external-api.yaml`：五個 operation（`getLatestAssets`、`getPublicPortfolioAdviceLatest`、`getPublicTradingRadarTodayList`、`getPublicTradingRadarStockDetail`、`getPublicTransactionHistory`）各自的 `parameters` 新增：

  ```yaml
  - name: email
    in: query
    required: false
    schema:
      type: string
      format: email
      maxLength: 254
    description: >-
      選擇要查詢的帳號 email；省略時預設回傳 configured-admin（主要管理者）的資料。
      帶入時僅需該帳號存在且狀態為 ACTIVE，不需為 configured-admin。
      格式不合法回 400；查無帳號或帳號非 ACTIVE 回 503（與 configured-admin 不可用時相同的錯誤契約，
      刻意不區分「查無」與「停用」以避免此參數成為帳號列舉工具）。
    example: selected@example.invalid
  ```

  **`example` 欄位不可省略**：`scripts/tests/docker-external-api-openapi-test.rb:258` 對每一個 operation 的每一個 parameter 無條件要求存在合成 `example`，五個 operation 的 `email` parameter 都要各自帶上。

  五個 operation 的 `responses` 新增 `400`（格式不合法）說明，既有 `503` 說明擴充為涵蓋「configured-admin 或指定帳號皆可能觸發」；**不得新增或修改任何 `200` response schema**（成功時的回應形狀完全不變）。`info.version` 由 `1.10.0` 改為 `1.11.0`。

  **這五個 operation 現有頂層 `description` 必須同步修訂，這是自足性必要項、不是選配：** 目前分別在 `docker-external-api.yaml:315`（`getLatestAssets`）、`:534`（`getPublicPortfolioAdviceLatest`）、`:605`（`getPublicTradingRadarTodayList`）、`:670`（`getPublicTradingRadarStockDetail`）、`:737`（`getPublicTransactionHistory`）寫死「不接受 `ownerId`／`email`／cookie／`X-User-*` 作為租戶選擇輸入」或「owner 只能由 configured-admin bootstrap 決定」等**無條件**語句，若不改，會與同一個 operation 物件裡剛新增的 `email` parameter 字面矛盾（`description` 說不接受 email，`parameters` 卻定義了 email）。改寫成雙態敘述，例如：「不帶 `email` 時 owner 由 configured-admin bootstrap 決定；帶合法 `email` 時 owner 改由該帳號決定（仍不接受 `ownerId`、cookie、`X-User-*`、Tailscale identity 作為額外的租戶選擇輸入）」。既有 `docker-external-api-openapi-test.rb` 只驗「有沒有具體描述文字」，不驗語意矛盾，這一項不會被既有測試攔下，必須手動核對五處都已修改。

- [x] 416.10 執行 `ruby scripts/render-9090-openapi-docs.rb`（可加 `--check` 驗證）由 `docs/openapi/docker-external-api.yaml` 重新產生本專案 Swagger Markdown 鏡像（`docs/openapi/9090-api-swagger.md`），並以同一 bytes 覆寫 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`。

- [x] 416.10b **`scripts/tests/docker-external-api-openapi-test.rb` 必須同一 commit 更新，這一項漏掉會讓 `scripts/spec-check.sh` 的 B9 在實作完成後直接 BLOCK**（此腳本硬編碼了變更前的契約形狀，不是新規則——`info.version` 從 1.8.0 升到 1.9.0 時就同步改過一次）：
  - `:169` 硬編 `document.dig('info', 'version') == '1.10.0'` → 改為 `'1.11.0'`（含斷言訊息文字）。
  - `:27`／`:31`／`:32` 的 `MANIFEST`（`getLatestAssets`／`getPublicPortfolioAdviceLatest`／`getPublicTradingRadarTodayList` 三個 key）目前 response-status 陣列都不含 `400`，各自補上。
  - `:304-305` 對 `getPublicTradingRadarStockDetail` 的 `parameters` 做精確陣列相等（`== %w[stockCode market]`），`:310-311` 對 `getPublicTransactionHistory` 同理（`== %w[year start end]`）——兩處都要把 `email` 加進期望陣列。
  - 驗證指令需新增 `ruby scripts/tests/docker-external-api-openapi-test.rb`，不能只跑 `render-9090-openapi-docs.rb --check`（兩支腳本驗的維度不同）。

- [x] 416.10c **同步更新架構文件的姊妹副本，避免只改 `CLAUDE.md` 造成不一致**：`spec/steering/structure.md`（已在本次 spec 修訂中同步）、`INSTALLATION.md`（已在本次 spec 修訂中同步）——這兩處與 `CLAUDE.md` 維護同一句「owner 走 configured-admin bootstrap」規則的獨立副本，Requirement 140 落地後三處都必須一致改為「預設走…，可另帶 email…」，不得只改其中一份。

- [x] 416.11 **不擴大範圍：** 不新增使用者專屬驗證機制（token/API key）；不修改 `/api/quotes`、`/api/quotes/one`、`/api/public/market-index`、`/api/public/exchange-rate/usd-twd`、`/api/public/crawler-data/rescan`、`/api/public/market-analysis/today`、`/api/public/trading-calendar`、`/api/public/commodity-prices` 這八支無個人資料語意端點；不新增任何可寫入或觸發券商交易的 API；不修改 `configured-admin` 本身的判定邏輯（`ADMIN_EMAIL` 環境變數、`UserAdminService.configuredAdmin()`、`AdminGateInterceptor` 既有放行清單皆不變）；不變更已登入（Google OAuth）內部頁面既有的租戶隔離機制；不新增 business controller mapping（`byEmail` 對應的 `GET /internal/users/by-email` 已存在且已免 ADMIN gate）。

## 接手修正與完成門檻（本段優先）

- [x] 416.R1 email 格式驗證必須在呼叫 byEmail、亦在交易雷達 selector／交易紀錄 filter 驗證之前執行；五端點的 detail 一律為「email 格式不合法」。Radar／Transactions 必須新增或區分 email 專用 request exception/advice，不能把 email 格式錯誤偽裝成既有 selector 或日期篩選錯誤；email 合法或省略時，既有 selector／日期錯誤文字仍維持原契約。
- [x] 416.R2 僅包住 email lookup publisher：以 `Mono.defer(() -> users.byEmail(trimmed)).timeout(Duration.ofSeconds(5)).onErrorMap(...)` 將 HTTP 4xx/5xx、連線／逾時與解碼錯誤轉成 416.5 的 canonical unavailable exception；其後才處理 empty/inactive。不得把 local email 格式錯誤或資料讀取 downstream error 一併映射，且不得讓 lookup 的 WebClientResponseException 或 upstream body 穿透匿名 9090。
- [x] 416.R3 BusinessUserClient.byEmail 必須刪除 Reactor CTX_IDENTITY 後才發出 lookup；因此 lookup request 不得帶 X-User-Id、X-User-Role、X-User-Status。解析成功後的資料讀取 request 則仍必須帶入被選帳號三個 header，且不受呼叫端殘留 context 影響。
- [x] 416.R4 測試一律使用 synthetic fixture（例如 selected@example.invalid），不得讀取、硬編碼或在任務報告揭露真人帳號 email。查無帳號要模擬 business 實際的 200 空 body（非 204），並覆蓋 inactive、lookup 4xx/5xx、transport、實際 5 秒 timeout、malformed body、五端點精確 400 detail、同端點每種 lookup 失敗的 canonical 503 body，以及帶殘留 context 時 lookup 無 tenant header、下游使用選取帳號 header。
- [x] 416.R5 文件與驗收命令必須可執行：下方完整矩陣取代舊版簡略範例。BFF health 由 compose container 內 localhost:8080 檢查；實機驗收要覆蓋五支端點的 default 與 email 分支（含 trading-radar stock），以環境變數提供已確認的 synthetic fixture 與個股參數。若環境沒有可用 fixture，不得捏造成功結果，需明確回報並保留單元測試證據。
- [x] 416.R6 OpenAPI YAML、產生的專案 Swagger Markdown 與 SRPP 鏡像必須同步新契約：五支 operation 的 400 說明／example 要正確表示 email 格式錯誤的 detail；Radar／Transactions 同時保留其既有 selector/filter 400 情境但明示 email invalid 的優先規則；Portfolio 不得再把 byEmail lookup 失敗描述為 502，所有 email lookup failure 都是該 endpoint canonical 503。
  - 指令的唯一健康檢查為 `docker compose -p asset-management exec -T bff wget -qO- http://localhost:8080/actuator/health`；不得從 host 假設 8080 有對外 port。
  - 先以 `TASK416_ACTIVE_EMAIL`、`TASK416_STOCK_CODE`、`TASK416_STOCK_MARKET` 做非空 preflight；驗收矩陣必須對 `/api/assets/latest`、`/api/public/portfolio-advice/latest`、`/api/public/trading-radar/today`、`/api/public/trading-radar/stock`（帶 stockCode/market）、`/api/public/transactions` 各做一次 default 與一次 urlencoded email query。
  - 查無帳號與 inactive fixture 的 503 要收集完整 body 做逐位元比對，invalid email 要驗證 400 的精確 detail；若環境沒有可用 inactive／active fixture，記為未可做的實機情境，不能以任意真人帳號代替。

## 驗證

**單元測試**（在既有測試檔 `LatestAssetsConfiguredAdminContextTest`／`PublicPortfolioAdviceConfiguredAdminContextTest`／`PublicTradingRadarConfiguredAdminContextTest`／`PublicTransactionHistoryConfiguredAdminContextTest` 各自新增案例，不新建平行測試檔）每支端點至少覆蓋：

1. 不帶 `email`：既有測試全數維持通過（回歸基準，證明「零改變」）。
2. 帶合法 email、對應一個 active 且非 configured-admin 的帳號：成功 200，回應資料可觀察地屬於該帳號（例如不同的資產總額／配置建議／雷達個股欄位／交易紀錄筆數）而非 configured-admin。**測試需準備至少一個非 configured-admin 的 active 測試帳號**，否則無法排除「巧合命中同一份資料」的可能。
3. 帶合法 email、查無對應帳號：503，body 記下供情境 4 比對。
4. 帶合法 email、對應帳號存在但 `status != ACTIVE`：503，**斷言與情境 3 的 status code 與 body 完全相同**。
5. 帶格式不合法 email（缺 `@`、缺網域、超過 254 字元）：400。
6. Reactor context 已有另一個登入者／代看者身分時（既有 `.contextWrite` 測試的既有模式），email 分支下游收到的 header 必須是 email 解析出的帳號，不是 context 裡殘留的身分。

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
```

**實機驗收**（本專案沒有 dev server，改好的定義是 image rebuild + container recreate；本段取代任何較早的簡略 curl 範例）：

```bash
docker compose -p asset-management build --no-cache bff
docker compose -p asset-management up -d --no-deps --force-recreate bff api-gateway
docker compose -p asset-management exec -T bff wget -qO- http://localhost:8080/actuator/health

# 完整驗收必須用已確認的 synthetic / 非正式帳號 fixture；未設定就停止，不得以真人帳號代替。
: "${TASK416_ACTIVE_EMAIL:?set an ACTIVE non-configured-admin fixture}"
: "${TASK416_INACTIVE_EMAIL:?set an INACTIVE fixture}"
: "${TASK416_STOCK_CODE:?set a queryable stock code}"
: "${TASK416_STOCK_MARKET:?set the stock market}"
task416_dir="$(mktemp -d)"
task416_base="http://127.0.0.1:9090"
request() {
  task416_label="$1"; shift
  curl -sS -D "$task416_dir/$task416_label.headers" -o "$task416_dir/$task416_label.body" -w "%{http_code}" "$@" > "$task416_dir/$task416_label.status"
}

# 五支端點都必須走 default 與 email 分支；保留 response 供人工確認 owner-specific 資料。
request assets-default "$task416_base/api/assets/latest"
request assets-email -G --data-urlencode "email=$TASK416_ACTIVE_EMAIL" "$task416_base/api/assets/latest"
request advice-default "$task416_base/api/public/portfolio-advice/latest"
request advice-email -G --data-urlencode "email=$TASK416_ACTIVE_EMAIL" "$task416_base/api/public/portfolio-advice/latest"
request radar-today-default "$task416_base/api/public/trading-radar/today"
request radar-today-email -G --data-urlencode "email=$TASK416_ACTIVE_EMAIL" "$task416_base/api/public/trading-radar/today"
request radar-stock-default -G --data-urlencode "stockCode=$TASK416_STOCK_CODE" --data-urlencode "market=$TASK416_STOCK_MARKET" "$task416_base/api/public/trading-radar/stock"
request radar-stock-email -G --data-urlencode "stockCode=$TASK416_STOCK_CODE" --data-urlencode "market=$TASK416_STOCK_MARKET" --data-urlencode "email=$TASK416_ACTIVE_EMAIL" "$task416_base/api/public/trading-radar/stock"
request transactions-default "$task416_base/api/public/transactions"
request transactions-email -G --data-urlencode "email=$TASK416_ACTIVE_EMAIL" "$task416_base/api/public/transactions"
for task416_label in assets-default assets-email advice-default advice-email radar-today-default radar-today-email radar-stock-default radar-stock-email transactions-default transactions-email; do
  test "$(cat "$task416_dir/$task416_label.status")" = 200
done

# Anti-enumeration: missing 與 inactive 的完整 503 body 必須相同；invalid email 是精確 400 detail。
request assets-missing -G --data-urlencode "email=missing@example.invalid" "$task416_base/api/assets/latest"
request assets-inactive -G --data-urlencode "email=$TASK416_INACTIVE_EMAIL" "$task416_base/api/assets/latest"
test "$(cat "$task416_dir/assets-missing.status")" = 503
test "$(cat "$task416_dir/assets-inactive.status")" = 503
cmp -s "$task416_dir/assets-missing.body" "$task416_dir/assets-inactive.body"
request assets-invalid -G --data-urlencode "email=not-an-email" "$task416_base/api/assets/latest"
test "$(cat "$task416_dir/assets-invalid.status")" = 400
ruby -rjson -e 'abort unless JSON.parse(File.read(ARGV[0])).fetch("detail") == "email 格式不合法"' "$task416_dir/assets-invalid.body"
ruby scripts/render-9090-openapi-docs.rb --check
```

本任務不涉及資料庫 schema 或 migration。

## 前一輪未驗證報告（僅供追溯；下方接手驗證結果優先）

> 下列內容在接手前寫入；其中完成宣告、測試數字與實機驗收均需依本檔「接手修正與完成門檻」重新驗證。完成前不得將它當作已交付結果。

### 實際改動的檔案

**BFF production code（新增/修改）**
- `bff/src/main/java/com/steven/assets/bff/latestassets/LatestAssetsPublicController.java`（改）
- `bff/src/main/java/com/steven/assets/bff/latestassets/LatestAssetsPublicService.java`（改：新增 `resolveOwner()`／`normalize()`，`getLatest()` 前既有 configured-admin 邏輯逐位元組保留）
- `bff/src/main/java/com/steven/assets/bff/latestassets/LatestAssetsRequestException.java`（新增）
- `bff/src/main/java/com/steven/assets/bff/latestassets/LatestAssetsPublicExceptionAdvice.java`（改：新增 400 handler）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceController.java`（改）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceService.java`（改，結構同 LatestAssets）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceRequestException.java`（新增）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceExceptionAdvice.java`（改：新增 400 handler）
- `bff/src/main/java/com/steven/assets/bff/tradingradar/PublicTradingRadarController.java`（改：`today`／`stock` 都加 `email`）
- `bff/src/main/java/com/steven/assets/bff/tradingradar/PublicTradingRadarService.java`（改：`relay()` 加 `email` 參數並共用，新增 `resolveOwner()`，沿用既有 `PublicTradingRadarRequestException`／`PublicTradingRadarUnavailableException`，未改動這兩個例外類別本身）
- `bff/src/main/java/com/steven/assets/bff/publictransaction/PublicTransactionHistoryController.java`（改）
- `bff/src/main/java/com/steven/assets/bff/publictransaction/PublicTransactionHistoryService.java`（改，結構同 TradingRadar，沿用既有 `PublicTransactionHistoryRequestException`／`PublicTransactionHistoryUnavailableException`）

**BFF 測試（既有 4 支 `bff/src/test/java/com/steven/assets/bff/config/` 下的 config context 測試檔：修正既有呼叫端傳 `null` ＋ 各自新增 5 個 email 分支測試方法，覆蓋驗證段情境 2～6；情境 1 由既有測試本身即為回歸基準）**
- `bff/src/test/java/com/steven/assets/bff/config/LatestAssetsConfiguredAdminContextTest.java`
- `bff/src/test/java/com/steven/assets/bff/config/PublicPortfolioAdviceConfiguredAdminContextTest.java`
- `bff/src/test/java/com/steven/assets/bff/config/PublicTradingRadarConfiguredAdminContextTest.java`
- `bff/src/test/java/com/steven/assets/bff/config/PublicTransactionHistoryConfiguredAdminContextTest.java`

**BFF 測試（非任務檔指名，但因 5 支方法簽章新增 `email` 參數而編譯失敗，一併做最小修正，非新建平行測試檔）**
- `bff/src/test/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceControllerTest.java`（`controller.latest()`→`controller.latest(null)`；replica service 的 `@Override latest()`→`latest(String email)`）
- `bff/src/test/java/com/steven/assets/bff/tradingradar/PublicTradingRadarControllerTest.java`（Mockito stub／呼叫／反射 `getDeclaredMethod` 都補上 `String.class`／`null` 參數）
- `bff/src/test/java/com/steven/assets/bff/tradingradar/PublicTradingRadarSelectorTest.java`（`service.stock(...)` 補第三個 `null` 引數）

**文件**
- `docs/openapi/docker-external-api.yaml`（五個 operation 新增 `email` parameter＋400 response＋503 description 擴充＋五處頂層 description 改雙態敘述；`info.version` 1.10.0→1.11.0）
- `docs/openapi/9090-api-swagger.md`（由 `ruby scripts/render-9090-openapi-docs.rb` 重產）
- `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`（同一份 render 覆寫，repo 外、不在本專案版控）
- `scripts/tests/docker-external-api-openapi-test.rb`（`info.version` 斷言→1.11.0；`MANIFEST` 三個 key 補 `400`；`stock`／`transactions` 的 parameters 精確陣列斷言補 `email`）

**驗證結果**：`spec/steering/structure.md`（第 199、416 行）與 `INSTALLATION.md`（第 490–499 行）已在本次 spec 修訂中同步 Requirement 140 的雙態敘述，本次未再變更（僅核對，416.10c 屬查證性質）。

### 驗證指令實際輸出

```
mvn -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
→ Tests run: 265, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS

mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
→ Tests run: 1917, Failures: 0, Errors: 6, Skipped: 3 — BUILD FAILURE
  6 個 error 都是既有 Fubon Testcontainers 整合測試：FubonAccountingPostgresTest、
  FubonConfiguredOwnerSnapshotPostgresTest、FubonEtfHoldingsPostgresTest、
  FubonSnapshotLockPostgresTest、FubonTradeSyncUniqueIndexPostgresTest、
  FubonTradeWriterPostgresTest——見下方「偏差與原因」

ruby scripts/tests/docker-external-api-openapi-test.rb
→ PASS: 9090 gateway/OpenAPI 十三路 parity、response manifest、parameters、examples、strict schemas 與 generated docs 完整

ruby scripts/render-9090-openapi-docs.rb --check
→ PASS: 9090 OpenAPI Markdown mirrors are byte-identical and current
```

（本機 JVM 實際為 Java 25，`-DextraArgLine=-Dnet.bytebuddy.experimental=true` 是既有專案慣例，非本次新增；命令列刻意不用 `-DargLine`，理由見 `feedback_mockito_java25_bytebuddy.md`。）

### 與原計畫的偏差及原因

1. **backend 測試 6 個 error（非本次變更造成，已排查非本次範圍）**：6 個失敗全部是既有 Fubon Testcontainers 整合測試（見上方檔名清單），錯誤是 `IllegalState: Could not find a valid Docker environment`。排查過程：(a) 這 6 個類別測的是既有 Fubon 券商同步/快照鎖定機制，本次完全沒有修改任何 `backend/` 檔案，與 Requirement 140 無關；(b) `docker ps`、`docker context ls` 確認 Docker Desktop 正常執行於 `desktop-linux` context；(c) 明確指定 `DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock` 並關閉 Ryuk（`TESTCONTAINERS_RYUK_DISABLED=true`）單獨重跑 `FubonSnapshotLockPostgresTest` 仍失敗；(d) 但用 `curl --unix-socket` 直接打同一個 socket 可以正常拿到 Docker daemon version，證實 socket 本身可達。結論：這是本 session 的 sandboxed bash 環境限制（Testcontainers 從這裡啟動的 Java 行程偵測不到 Docker，而非 daemon 真的不可用），不是程式碼問題，也不是本次變更造成——其餘 1911 個測試（1917−6）全數通過。此限制留給另一個能存取完整 Docker 的驗收步驟（任務檔本來就把 Docker rebuild/recreate 與 curl 實機驗收另外劃分出去）。
2. **測試 fixture 修正要求**：先前報告曾主張使用實際帳號資料；該做法不納入交付。接手實作必須改用 synthetic fixture，且不得在 task、測試或報告記錄真人帳號 email。
3. **其餘未偏離**：`LatestAssetsPublicService`／`PublicPortfolioAdviceService` 與 `PublicTradingRadarService`／`PublicTransactionHistoryService` 兩組既有變體結構皆保留原樣（前者 `switchIfEmpty().flatMap()` 直接串接，後者獨立 `bootstrap` 變數＋`Mono.defer`／`onErrorMap`），只是把「選 configured-admin 或選 by-email 帳號」包成 `resolveOwner()` 內的 if/else 分支，下游呼叫（headers／`contextWrite`／既有下游例外處理）完全不變；`PublicTradingRadarRequestException`／`PublicTransactionHistoryRequestException` 沿用既有 no-arg固定訊息，未新增建構子；email 格式驗證常數（regex／254 長度）在五支 service 裡各自獨立宣告（不新建跨 service 共用工具類別），呼應 BFF 與 backend 不共用程式碼的既有原則、也避免對現有 5 個 service 的既有邊界做非任務要求的重構。
4. **架構符規查證**：已依 CLAUDE.md 規範派 `arch-auditor` subagent 對本次 diff 做唯讀查證（結果見下方／或本回覆內文最新更新）。

## 接手實作驗證結果（2026-09-06）

本段取代上方歷史報告中任何未經本次驗證的完成宣告；測試 fixture、文件 example 與本任務報告均只使用 synthetic email，沒有讀取或記錄真人帳號。

- 已完成 R1–R4：`byEmail` 在整個 publisher 清除 Reactor identity；五支端點的 email lookup 只在 lookup 範圍內以五秒 timeout／error mapping 封閉；Radar／Transactions 用 email 專用 400 exception/advice，且 email 格式錯誤優先於 selector/filter。
- 四個既有 config context 測試覆蓋 active non-configured owner、business 實際 200 空 body、inactive、lookup 4xx／5xx／transport／decode／五秒 timeout、同端點 canonical 503、精確 email 400 detail，以及殘留 context 下 lookup 無 `X-User-*`、資料讀取使用 selected owner headers。
- 已完成 R6：OpenAPI 的五支 operation、rendered project Markdown 及 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md` 已由同一 renderer 覆寫並 byte-identical；Portfolio 的 by-email lookup failure 已明確文件化為 503，不是 502。
- 已實際通過：`mvn -q -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`、`git diff --check`、`bash scripts/spec-check.sh`、`ruby scripts/tests/docker-external-api-openapi-test.rb`、`ruby scripts/render-9090-openapi-docs.rb --check`。

### R5 Docker／9090 實機驗收

- 以 temporary integration 合併 `ffd8010a` 與 `896215d0`；`-X ours` 成功且只處理文件衝突。temporary worktree 已 abort／移除，HTTP 驗收輸出已移至 Trash。
- BFF 已 rebuild／recreate 且 runtime health 為 `UP`；實際 image digest 為 `sha256:13c110698cd4d3067cb85cdd015e80b456b14c5bf75e81bd32862715e96b5074`，api-gateway image 未變更。原本文件指定的 container 內 `curl` 不存在，故該 health command 不可執行；修正文檔為 container 內 `wget` 後，runtime agent 已重跑成功（exit 0，`{"status":"UP"}`），故 R5 已完成。
- 五支端點的 default 與 active email 分支皆為 200；五支 missing 情境皆為 503；五支 invalid email 情境皆為 400，且 detail 精確為規定文案。
- 執行環境沒有 inactive non-admin fixture，故無法做 missing／inactive runtime body equality 比對；此唯一未可執行的 runtime 情境由已通過的 unit tests 覆蓋，未捏造成功結果。
- recreate 時限內的 BFF log scan，`Connection refused`、`500`、`error`、`exception` 均為 0；未呼叫任何 write 或 broker action。

**任務整體仍不得宣稱完成：** main merge／push 仍被 main worktree 的未知 `.configure-tailscale-api.sh.swp` 阻擋，必須先由其擁有者處理後才能完成既定的 commit → no-ff merge → push。
