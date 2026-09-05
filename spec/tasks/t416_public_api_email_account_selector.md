# [t416] 9090 個人資料公開端點新增 email 參數選擇帳號

**對應 Requirements:** Requirement 140（9090 個人資料公開端點依 email 參數選擇帳號，取代固定 configured-admin）
**前置任務:** 無
**Liquibase changeset:** 無（不動 DB schema）

## 背景

`GET /api/assets/latest`、`GET /api/public/portfolio-advice/latest`、`GET /api/public/trading-radar/today`、`GET /api/public/trading-radar/stock`、`GET /api/public/transactions` 這五支 9090 對外公開 GET，目前 owner 一律固定為 `BusinessUserClient.configuredAdmin()` 解析出的唯一 configured admin（`ADMIN_EMAIL=tw.leader@gmail.com`）。使用者要求：這些回傳個人資料的 API 應該依「使用者的 email」查回該帳號的資料，而不是永遠固定回傳主要管理者的資料；查詢別人就傳別人的 email。

**這五支端點今天是完全匿名、無 application 層驗證的公開端點**（經 `127.0.0.1:9090` loopback 與 Tailscale tailnet HTTPS `:9090` 對外，見 `spec/design.md` 既有段落「路由本身無任何鑑權」）。固定回傳單一 configured-admin 是刻意設計：即使網路邊界被突破，曝險範圍也鎖死在 1 個帳號。本任務要新增的「依 email 查任意帳號」功能，會把曝險範圍從「1 人」放大成「系統裡所有 active 帳號」——**因為 email 不是密碼，任何人只要知道一個有效帳號的 email 就能透過這五支端點讀到該帳號完整的資產、投資組合建議、交易雷達個股決策與交易紀錄。**

這個安全取捨已經在對話中完整攤給使用者（tw.leader@gmail.com）：現況本就沒有 application 層驗證，只是波及範圍固定為 1 人；開放 email 參數會把波及範圍放大到全部帳號。使用者在理解這個差異後明確選擇「知情且接受，直接開放 email 參數」，不要求新增驗證層（token/API key 等）。**本任務的正確行為就是「不加驗證、直接依 email 查帳號」——這不是實作疏漏，是使用者的明確決定，不要自作主張加上驗證層,也不要因為看起來不安全就拒絕實作或改為別的設計。** 如果你（實作者）認為這個決定有問題，先停下回報，不要靜默加驗證或靜默不做。

## 要做什麼

- [ ] 416.1 五支 controller 各自新增 `@RequestParam(required = false) String email`，原樣傳給對應 service 方法，method 簽章與既有其他參數（`stockCode`／`market`／`year`／`start`／`end`）並列，不改變既有參數的驗證邏輯或順序：
  - `LatestAssetsPublicController.getLatest(String email)` → `LatestAssetsPublicService.getLatest(String email)`
  - `PublicPortfolioAdviceController.latest(String email)` → `PublicPortfolioAdviceService.latest(String email)`
  - `PublicTradingRadarController.today(String email)` → `PublicTradingRadarService.today(String email)`；`PublicTradingRadarController.stock(stockCode, market, String email)` → `PublicTradingRadarService.stock(stockCodes, markets, String email)`（`PublicTradingRadarService` 的 `today()`／`stock()` 共用私有 `relay()`，`email` 作為 `relay()` 的額外參數往下傳，不得複製一份 `relay()`）
  - `PublicTransactionHistoryController.current(year, start, end, String email)` → `PublicTransactionHistoryService.current(years, starts, ends, String email)`

- [ ] 416.2 五支 service 在既有 bootstrap 之前插入分歧：`email` trim 後非空白時走新分支，否則走既有 `users.configuredAdmin()` 分支（**逐位元組不變**，包含 `admin.configuredAdmin()==true` 與 `admin.isActive()` 的既有檢查、既有 Unavailable 例外訊息、既有 503 契約）。

- [ ] 416.3 新分支的 email 格式驗證：長度 ≤ 254 字元，且匹配 `Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE)`（與 `backend/src/main/java/com/steven/assets/service/UserAdminService.java` 既有 `EMAIL_PATTERN` 語意一致；BFF 與 backend 是各自獨立 module，不共用程式碼，BFF 端另建同語意 regex 常數）。不合法格式在呼叫 `byEmail` 之前就要擋下並回 400（沿用各端點既有 Request/Invalid 例外類別慣例；`LatestAssetsPublicService`／`PublicPortfolioAdviceService` 目前沒有對應的 400 例外類別，需各自新增一個同慣例的類別，例如 `LatestAssetsRequestException`／`PublicPortfolioAdviceRequestException`，並在各自 ExceptionAdvice 加 `@ExceptionHandler` 回 `HttpStatus.BAD_REQUEST`）。

- [ ] 416.4 格式合法的 email：呼叫既有 `BusinessUserClient.byEmail(email)`（對應 business 既有 `GET /internal/users/by-email`，已由 `AdminGateInterceptor` 免 ADMIN 放行，**不新增 business controller mapping**）。解析出的帳號只需 `id != null`、`role != null`、`status != null`、`isActive()==true` 即可使用；**明確不得檢查 `configuredAdmin()==true`**——這是與既有 bootstrap 分支的唯一差異，沿用該檢查會讓所有非 admin 帳號永遠落入下一步的「不可用」分支，等於沒做到這個任務要做的事。

- [ ] 416.5 帳號解析失敗（`byEmail` 回 null、或回傳的帳號 `isActive()==false`）**必須映射到與該端點今天『configured-admin 不可用』完全相同的例外類別與 503 契約**：`LatestAssetsUnavailableException`／`PublicPortfolioAdviceUnavailableException`／`PublicTradingRadarUnavailableException`／`PublicTransactionHistoryUnavailableException`。**查無帳號、帳號存在但非 ACTIVE，兩者的 HTTP status 與 response body 必須完全相同**（同一段訊息文字，不得依兩種情境輸出不同內容）——這是刻意的反列舉設計，不是偷懶；`PublicTradingRadarUnavailableException`／`PublicTransactionHistoryUnavailableException`目前是 no-arg、訊息寫死在各自 ExceptionAdvice，維持不變即可；`LatestAssetsUnavailableException`／`PublicPortfolioAdviceUnavailableException` 目前接受自訂訊息字串，email 分支可傳入泛化訊息（不必是「主要管理者不可用」字樣，但同樣不得依查無/停用分岔）。

- [ ] 416.6 兩個分支（configured-admin／email）合流後，既有「顯式帶 `HDR_USER_ID`／`HDR_USER_ROLE`／`HDR_USER_STATUS` 三個 header ＋ `.contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY))`」寫法完全不變，只是三個 header 的值來源不同（configured-admin 或 by-email 解析出的帳號）。下游 business 呼叫 URI、payload relay、既有下游例外處理（`WebClientResponseException` 等）不變。

- [ ] 416.7 `PublicTransactionHistoryService.current()` 既有的 `year`／`start`／`end` 三種互斥模式驗證，與 `email` 驗證彼此獨立：無論哪個先執行，最終結果必須一致（互不能讓對方的錯誤蓋掉自己該回的錯誤）；`PublicTradingRadarService.stock()` 既有的 `singleSelector`（`stockCode`／`market`）格式驗證同理獨立於 `email` 驗證。

- [ ] 416.8 Gateway（`api-gateway/nginx.conf`）、Tailscale Serve 設定**不需修改**——五個既有 exact `location`／path-scoped mount 已透傳 `$request_uri`／不解析 query string，`email` 會自動透傳。**不要**因為這個任務去動 `nginx.conf` 或 Tailscale 設定腳本。

- [ ] 416.9 `docs/openapi/docker-external-api.yaml`：五個 operation（`getLatestAssets`、`getPublicPortfolioAdviceLatest`、`getPublicTradingRadarTodayList`、`getPublicTradingRadarStockDetail`、`getPublicTransactionHistory`）各自的 `parameters` 新增：

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
    example: user@example.com
  ```

  **`example` 欄位不可省略**：`scripts/tests/docker-external-api-openapi-test.rb:258` 對每一個 operation 的每一個 parameter 無條件要求存在合成 `example`，五個 operation 的 `email` parameter 都要各自帶上。

  五個 operation 的 `responses` 新增 `400`（格式不合法）說明，既有 `503` 說明擴充為涵蓋「configured-admin 或指定帳號皆可能觸發」；**不得新增或修改任何 `200` response schema**（成功時的回應形狀完全不變）。`info.version` 由 `1.10.0` 改為 `1.11.0`。

  **這五個 operation 現有頂層 `description` 必須同步修訂，這是自足性必要項、不是選配：** 目前分別在 `docker-external-api.yaml:315`（`getLatestAssets`）、`:534`（`getPublicPortfolioAdviceLatest`）、`:605`（`getPublicTradingRadarTodayList`）、`:670`（`getPublicTradingRadarStockDetail`）、`:737`（`getPublicTransactionHistory`）寫死「不接受 `ownerId`／`email`／cookie／`X-User-*` 作為租戶選擇輸入」或「owner 只能由 configured-admin bootstrap 決定」等**無條件**語句，若不改，會與同一個 operation 物件裡剛新增的 `email` parameter 字面矛盾（`description` 說不接受 email，`parameters` 卻定義了 email）。改寫成雙態敘述，例如：「不帶 `email` 時 owner 由 configured-admin bootstrap 決定；帶合法 `email` 時 owner 改由該帳號決定（仍不接受 `ownerId`、cookie、`X-User-*`、Tailscale identity 作為額外的租戶選擇輸入）」。既有 `docker-external-api-openapi-test.rb` 只驗「有沒有具體描述文字」，不驗語意矛盾，這一項不會被既有測試攔下，必須手動核對五處都已修改。

- [ ] 416.10 執行 `ruby scripts/render-9090-openapi-docs.rb`（可加 `--check` 驗證）由 `docs/openapi/docker-external-api.yaml` 重新產生本專案 Swagger Markdown 鏡像（`docs/openapi/9090-api-swagger.md`），並以同一 bytes 覆寫 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`。

- [ ] 416.10b **`scripts/tests/docker-external-api-openapi-test.rb` 必須同一 commit 更新，這一項漏掉會讓 `scripts/spec-check.sh` 的 B9 在實作完成後直接 BLOCK**（此腳本硬編碼了變更前的契約形狀，不是新規則——`info.version` 從 1.8.0 升到 1.9.0 時就同步改過一次）：
  - `:169` 硬編 `document.dig('info', 'version') == '1.10.0'` → 改為 `'1.11.0'`（含斷言訊息文字）。
  - `:27`／`:31`／`:32` 的 `MANIFEST`（`getLatestAssets`／`getPublicPortfolioAdviceLatest`／`getPublicTradingRadarTodayList` 三個 key）目前 response-status 陣列都不含 `400`，各自補上。
  - `:304-305` 對 `getPublicTradingRadarStockDetail` 的 `parameters` 做精確陣列相等（`== %w[stockCode market]`），`:310-311` 對 `getPublicTransactionHistory` 同理（`== %w[year start end]`）——兩處都要把 `email` 加進期望陣列。
  - 驗證指令需新增 `ruby scripts/tests/docker-external-api-openapi-test.rb`，不能只跑 `render-9090-openapi-docs.rb --check`（兩支腳本驗的維度不同）。

- [ ] 416.10c **同步更新架構文件的姊妹副本，避免只改 `CLAUDE.md` 造成不一致**：`spec/steering/structure.md`（已在本次 spec 修訂中同步）、`INSTALLATION.md`（已在本次 spec 修訂中同步）——這兩處與 `CLAUDE.md` 維護同一句「owner 走 configured-admin bootstrap」規則的獨立副本，Requirement 140 落地後三處都必須一致改為「預設走…，可另帶 email…」，不得只改其中一份。

- [ ] 416.11 **不擴大範圍：** 不新增使用者專屬驗證機制（token/API key）；不修改 `/api/quotes`、`/api/quotes/one`、`/api/public/market-index`、`/api/public/exchange-rate/usd-twd`、`/api/public/crawler-data/rescan`、`/api/public/market-analysis/today`、`/api/public/trading-calendar`、`/api/public/commodity-prices` 這八支無個人資料語意端點；不新增任何可寫入或觸發券商交易的 API；不修改 `configured-admin` 本身的判定邏輯（`ADMIN_EMAIL` 環境變數、`UserAdminService.configuredAdmin()`、`AdminGateInterceptor` 既有放行清單皆不變）；不變更已登入（Google OAuth）內部頁面既有的租戶隔離機制；不新增 business controller mapping（`byEmail` 對應的 `GET /internal/users/by-email` 已存在且已免 ADMIN gate）。

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

**實機驗收**（本專案沒有 dev server，改好的定義是 image rebuild + container recreate）：

```bash
docker compose -p asset-management build --no-cache bff
docker compose -p asset-management up -d --no-deps --force-recreate bff api-gateway
curl -s http://localhost:8080/actuator/health

# 不帶 email：確認與變更前完全一致
curl -s "http://127.0.0.1:9090/api/assets/latest" | head -c 300
curl -s "http://127.0.0.1:9090/api/public/portfolio-advice/latest" | head -c 300
curl -s "http://127.0.0.1:9090/api/public/trading-radar/today" | head -c 300
curl -s "http://127.0.0.1:9090/api/public/transactions" | head -c 300

# 帶 email（需已存在一個非 configured-admin 的 active 帳號）：確認回應內容分屬不同帳號
curl -s "http://127.0.0.1:9090/api/assets/latest?email=<測試帳號 email>" | head -c 300
curl -s "http://127.0.0.1:9090/api/public/transactions?email=<測試帳號 email>" | head -c 300

# 查無帳號與不合法格式
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:9090/api/assets/latest?email=not-a-real-account@example.com"
curl -s -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:9090/api/assets/latest?email=not-an-email"
```

不涉及 `backend/src/main/resources/db/changelog/**`，不需重產 `db/schema.sql`。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
