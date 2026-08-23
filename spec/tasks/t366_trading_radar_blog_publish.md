# [t366] 交易雷達結果新增「匯出到 blog」輸出通道，發布到 twleader.blogspot.com

**對應 Requirements:** Requirement 102（交易雷達新增第三個輸出通道：把快照的精簡摘要版發布/更新到使用者的
公開 Blogger 部落格 `https://twleader.blogspot.com/`，含手動立即發布按鈕與排程自動發布，比照既有
Google Drive 同步先例限主要管理者啟用）
**前置任務:** 無
**Liquibase changeset:** `backend/src/main/resources/db/changelog/changes/v1.112.0-trading-radar-blog-publish.sql`

## 背景

交易雷達目前有兩個輸出通道：瀏覽器手動下載 Excel（`TradingRadarExportService`／`POST /api/trading-radar/export`）
與排程自動寫入伺服器目錄／Google Drive（`TradingRadarExportScheduleService`，到點由
`trading_radar_export_time` 觸發）。兩者輸出的都是含 190+ 欄鑑識欄位（PE／PB provenance、利率批次 ID、
evidence group 覆蓋率等）的完整 Excel/JSON，供使用者自己稽核用途。

使用者要新增第三個通道：把同一份快照的**精簡摘要版**（大盤卡、個股三軌動作與分數、公開資訊）發布到
`https://twleader.blogspot.com/`，公開全發（含個股代號、分數、加減碼建議），使用者已被明確告知公開部落格
會被索引、等同公開個人投資組合，仍堅持全發，這是他的決定，不在本任務重新評估。

**兩個此前已定案、後續又被追加/修正的決策，全部以下列為準：**

1. **觸發入口**：交易雷達頁頁首 `.header-actions` 區塊，與既有〔⬇ 匯出 Excel〕〔⟳ 重新整理〕並排新增
   第三顆按鈕〔匯出到 blog〕（`frontend/src/views/TradingRadarView.vue:8-10`）。按下等同「立即發布/更新
   一次」，屬「發布公開內容」，**每次手動觸發都要先跳確認對話框，使用者按確認才送出**。
2. **排程自動發布與手動按鈕同一個任務範圍一起做**（不拆成兩個任務）。啟用後沿用既有
   〔匯出執行時間設定〕卡片的時間點（`trading_radar_export_time`），不新增第二套排程時間 UI。
3. **只有主要管理者（`ADMIN_EMAIL`，`UserAdminService.isConfiguredAdmin`）能看到與啟用本功能**——
   比照既有 Google Drive 同步開關（`GdriveOutputSupport.isDriveAllowedFor`）的既有先例：blog 是
   全機唯一、綁定特定 Google 帳號的目的地，非該帳號擁有者的使用者若也能觸發，會把不屬於自己的資料
   發到與自己無關的公開網址上。
4. **blog 擁有帳號（`shi.chihung@gmail.com`）與本 App 的登入/主要管理者帳號（`tw.leader@gmail.com`）
   是兩個不同的 Google 帳號**——OAuth 授權同意畫面是瀏覽器對 Google 的獨立互動，與「目前登入本 App
   的是誰」無關；以 `tw.leader@gmail.com` 主要管理者身分登入本 App 後點擊〔連接 Blogger 帳號〕，
   在 Google 同意畫面可自行切換/登入 `shi.chihung@gmail.com` 完成同意，取得的 refresh token 綁定
   `shi.chihung@gmail.com`，與觸發連接動作的 App 登入身分各自獨立。
5. **沿用既有登入用的 `GOOGLE_CLIENT_ID`／`GOOGLE_CLIENT_SECRET`**（`docker-compose.yml:340-341`），
   不新建第二組 OAuth Client；但**不借用** BFF 既有的 `oauth2Login`（`bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java:127`）——那條路徑語意是「登入本 App」，scope 只有
   `openid`／`profile`／`email`、不會帶 `access_type=offline`。本任務另外寫一組獨立的 controller
   端點，直接呼叫 Google 的 `/o/oauth2/v2/auth` 與 `/token` 端點（皆用 `java.net.http.HttpClient`，
   本專案 `backend/pom.xml` 未含任何 Google API client library 或 OkHttp 依賴，不得新增這類依賴，
   全部用 JDK 內建 HTTP client 手刻請求/解析 JSON）。

**部署前置準備（使用者需在 Google Cloud Console 手動完成，不在本任務程式碼驗收範圍內，程式碼本身不假設
已完成、也不檢查）：**
1. 於既有 OAuth 2.0 Client（`GOOGLE_CLIENT_ID` 對應的那一個）的「已授權的重新導向 URI」新增
   `<本 App 對外網域>/api/bff/trading-radar/blog-oauth/callback`。
2. 在同一 GCP 專案啟用 Blogger API v3。
3. 若該 OAuth 同意畫面仍是「測試中」狀態，把 `shi.chihung@gmail.com` 加入測試使用者名單。

## 要做什麼

### 366.1 Liquibase changeset（新表 + 既有表新增五欄）

新增 `backend/src/main/resources/db/changelog/changes/v1.112.0-trading-radar-blog-publish.sql`，並於
`backend/src/main/resources/db/changelog/db.changelog-master.yaml` 尾端（緊接
`v1.111.0-dividend-event-uniqueness.sql` 之後）新增一筆 `include`（`relativeToChangelogFile: false`，
比照既有寫法）。SQL 內容：

```sql
--liquibase formatted sql

--changeset steven:v1.112.0-trading-radar-blog-publish
CREATE TABLE blog_publish_credential (
    id                        BIGINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    blog_id                   VARCHAR(64),
    blog_url                  VARCHAR(512) NOT NULL DEFAULT 'https://twleader.blogspot.com/',
    account_label             VARCHAR(255),
    access_token              VARCHAR(2048),
    access_token_expires_at   TIMESTAMP,
    refresh_token             VARCHAR(2048),
    needs_reconnect           BOOLEAN NOT NULL DEFAULT FALSE,
    connected_at              TIMESTAMP,
    updated_at                TIMESTAMP
);

ALTER TABLE trading_radar_export_setting ADD COLUMN blog_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE trading_radar_export_setting ADD COLUMN blog_last_post_id VARCHAR(64);
ALTER TABLE trading_radar_export_setting ADD COLUMN blog_last_post_url VARCHAR(512);
ALTER TABLE trading_radar_export_setting ADD COLUMN blog_last_run_at TIMESTAMP;
ALTER TABLE trading_radar_export_setting ADD COLUMN blog_last_status VARCHAR(512);
```

- [x] 366.1a `blog_publish_credential` **在 DB 層以 `CHECK (id = 1)` 強制全表恆只有一列**——
  **不得**比照既有 `ExportScheduleSetting`／`TradingRadarExportSetting`「純應用層 upsert 保證
  一使用者一列」的說法去正當化本表不加任何 DB 層約束：那兩張既有表其實都有
  `UNIQUE(owner_user_id)` 兜底（見 `ExportScheduleSetting.java:24-25`／
  `TradingRadarExportSetting.java:26-27`），不是純應用層假設。本表連 `owner_user_id` 這種天然
  業務鍵都沒有（全域單例，沒有 owner 概念），若略去對應約束，防護反而比既有先例更弱——並發情境
  （callback 被重放、雙分頁同時完成連接）下可能競速寫出兩列，後續讀取會不確定地挑到其中一列，
  導致中斷連接／續期／發布出現難以重現的錯亂。`CHECK (id = 1)` 讓「只能有一列」在 DB 層即為
  不可違反的事實。
- [x] 366.1b 執行前用 `docker exec asset-postgres psql -U assets -d assets -c '\d trading_radar_export_setting'`
  確認目前運行中的 DB 沒有這五欄（避免與其他 worktree 已推進的 changeset 撞號；若已存在需重新查
  `SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5` 與 main 最新 changeset 版號
  是否需要避讓，比照 CLAUDE.md「編號先查撞號」）。

### 366.2 Entity 與 Repository

- [x] 366.2a 新增 `backend/src/main/java/com/steven/assets/model/BlogPublishCredential.java`：
  `@Entity @Table(name = "blog_publish_credential")`，**不套 `@Filter(ownerFilter)`**（全域單例，
  沒有 owner 概念）。欄位：`id`（`@Id`，**不加 `@GeneratedValue`**——固定寫死 `1L`，對應 DB 層
  `CHECK (id = 1)`，新增第一列時建構子／`builder()` 明確指定 `.id(1L)`）、
  `blogId`（`blog_id`，String，nullable）、`blogUrl`（`blog_url`，String，`@Builder.Default`
  = `"https://twleader.blogspot.com/"`）、`accountLabel`（String，nullable）、
  `accessToken`（String，length 2048，nullable）、`accessTokenExpiresAt`（`LocalDateTime`，nullable）、
  `refreshToken`（String，length 2048，nullable）、`needsReconnect`（boolean，預設 false）、
  `connectedAt`（`LocalDateTime`，nullable）、`updatedAt`（`LocalDateTime`，nullable）。
  用 Lombok `@Data @NoArgsConstructor @AllArgsConstructor @Builder`（比照
  `TradingRadarExportSetting` 既有風格）。
- [x] 366.2b 新增 `backend/src/main/java/com/steven/assets/repository/BlogPublishCredentialRepository.java`：
  `extends JpaRepository<BlogPublishCredential, Long>`。全表只會有 `id=1` 這一列（或完全不存在，
  代表尚未連接），呼叫端一律用 `findById(1L)` 取得（**不用** `findAll().stream().findFirst()`——
  既然 `id` 固定為 `1`，直接用主鍵查詢比掃全表更明確也更快，且不會在誤插入異常資料時退化成
  「取任一列」的不確定行為）。
- [x] 366.2c `backend/src/main/java/com/steven/assets/model/TradingRadarExportSetting.java` 新增五個
  欄位（`@Column` 對應 366.1 五個新欄）：`blogEnabled`（boolean，`blog_enabled`，`nullable = false`）、
  `blogLastPostId`（String，`blog_last_post_id`，length 64）、`blogLastPostUrl`（String，
  `blog_last_post_url`，length 512）、`blogLastRunAt`（`LocalDateTime`，`blog_last_run_at`）、
  `blogLastStatus`（String，`blog_last_status`，length 512）。放在既有 `gdrive_*` 五個欄位群組
  之後，加一段 Javadoc 說明與 `gdrive_*` 語意平行但彼此獨立（三個通道各自成敗）。

### 366.3 `BlogPublishOutputSupport`（權限判定，比照 `GdriveOutputSupport.isDriveAllowedFor`）

- [x] 366.3a 新增 `backend/src/main/java/com/steven/assets/service/BlogPublishOutputSupport.java`
  （`@Component`），建構子注入 `AppUserRepository userRepo` 與 `UserAdminService userAdminService`
  （與 `GdriveOutputSupport` 完全同型別依賴）。方法 `boolean isBlogAllowedFor(Long ownerUserId)`：
  `ownerUserId == null` 回 `false`；`userRepo.findById(ownerUserId)` 查無回 `false`（fail-closed）；
  email 為空回 `false`；否則回 `userAdminService.isConfiguredAdmin(email)`。**與
  `GdriveOutputSupport.isDriveAllowedFor` 邏輯完全平行但獨立存在，不合併成同一支**——理由：兩者
  未來可能各自獨立開放對象，合併會讓其中一方要開放時被迫連動另一方。

### 366.4 `BlogOAuthService`（OAuth2 換取／續期／中斷連接）

新增 `backend/src/main/java/com/steven/assets/service/BlogOAuthService.java`（`@Service`）：

- [x] 366.4a 建構子注入 `BlogPublishCredentialRepository credentialRepo`，以及
  `@Value("${GOOGLE_CLIENT_ID:placeholder-client-id}") String clientId`／
  `@Value("${GOOGLE_CLIENT_SECRET:placeholder-secret}") String clientSecret`
  （**兩者都要有預設值，比照 `bff/src/main/resources/application.yml:24-25` 既有寫法**——
  沒有預設值時，本 bean 一旦被建立，`backend` 在本機開發模式（`cd backend && mvn spring-boot:run`，
  不會先 source `.env`）下 Spring context 啟動時會找不到 property 而整個啟動失敗，波及本功能以外
  的所有既有端點；沿用既有環境變數 `docker-compose.yml:340-341` 已注入到 `bff` 服務，本任務需在
  `docker-compose.yml` 的 `business-services` 區塊**新增同一組**環境變數注入，因為目前只有 `bff`
  容器拿得到這兩個值——寫法為 `GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID:-placeholder-client-id}`／
  `GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET:-placeholder-secret}`，與 `bff` 區塊同樣的
  placeholder 預設值寫法）與 `@Value("${APP_PUBLIC_BASE_URL:}") String publicBaseUrl`（供組
  `redirect_uri`；若為空字串，`buildAuthorizeUrl()` 需擲出清楚的 `IllegalStateException`
  說明必須設定這個環境變數才能使用本功能，**不得**猜測或拼湊出一個網址）。**這個值刻意是需要
  使用者手動維護的明確環境變數，不沿用 BFF `oauth2Login` 既有的 `forward-headers-strategy: framework`
  動態推導機制**——那是 Spring Security reactive `oauth2Login` filter chain 內建的行為，本任務
  刻意不借用 `oauth2Login`（見 366 背景第 5 點），而 business 是傳統 Spring MVC、未設定該項；
  要複製同一套「信任反向代理 forwarded header」機制，需要在 business 新開一道信任邊界（設定
  `server.forward-headers-strategy` 並確認 Gateway 逐跳轉發不被偽造），對一支全機只有一位主要
  管理者、一次性使用的設定流程而言不划算，一個明確的環境變數更簡單、更好稽核。
  `.env.example` 新增一行 `APP_PUBLIC_BASE_URL=` 附註說明「本 App 對外可從瀏覽器存取的網域，供
  Blogger OAuth callback 組 redirect_uri，**必須是 `https://` 開頭**（Google 對非 localhost 的
  redirect_uri 要求 HTTPS）；只需在執行〔連接 Blogger 帳號〕當下能連得到，不需永久公開，例如
  Tailscale 為本機自動核發的 `https://<裝置>.<tailnet>.ts.net`」。
- [x] 366.4b `Map<String, Instant> pendingStates` 為行程內記憶體單例（`ConcurrentHashMap`），
  `state` 為 `UUID.randomUUID().toString()`，10 分鐘後視為過期（每次呼叫 `buildAuthorizeUrl()`
  時順手清掉已過期的舊 entry，避免無限增長）。
- [x] 366.4c `String buildAuthorizeUrl()`：產生並記錄一個新 `state`，回傳完整導向網址：
  ```
  https://accounts.google.com/o/oauth2/v2/auth
    ?client_id={clientId}
    &redirect_uri={URLEncoder.encode(publicBaseUrl + "/api/bff/trading-radar/blog-oauth/callback", UTF_8)}
    &response_type=code
    &scope={URLEncoder.encode("https://www.googleapis.com/auth/blogger", UTF_8)}
    &access_type=offline
    &prompt=consent
    &state={state}
  ```
- [x] 366.4d `record CallbackResult(boolean success, String reason)`；
  `CallbackResult handleCallback(String code, String state)`：
  1. `state` 不在 `pendingStates` 或已過期 → 回 `CallbackResult(false, "state_expired")`。
  2. 消費掉該 `state`（從 map 移除，一次性）。
  3. `POST https://oauth2.googleapis.com/token`（`Content-Type: application/x-www-form-urlencoded`）
     body：`grant_type=authorization_code&code={code}&client_id={clientId}&client_secret={clientSecret}&redirect_uri={同 366.4c 的 redirect_uri}`。
     非 2xx 回應 → 回 `CallbackResult(false, "token_exchange_failed")`，記錄 log（**不得記錄
     `code`／`client_secret`／回應中的 token 明碼**，只記 HTTP 狀態碼）。
  4. 解析回應 JSON 取得 `access_token`／`expires_in`／`refresh_token`（可能缺）。`refresh_token`
     缺漏 → 回 `CallbackResult(false, "missing_refresh_token")`。
  5. `GET https://www.googleapis.com/blogger/v3/blogs/byurl?url=https://twleader.blogspot.com/`
     （header `Authorization: Bearer {access_token}`）→ 解析 `id` 為 `blogId`；非 2xx 或缺
     `id` → 回 `CallbackResult(false, "blog_lookup_failed")`。
  6. `GET https://www.googleapis.com/blogger/v3/users/self`（同一個 Bearer token）→ 解析
     `displayName`（缺漏時容忍為 `null`，不得因此整體失敗——這欄純供 UI 顯示）。
  7. upsert `BlogPublishCredential`（`credentialRepo.findById(1L).orElseGet(() -> BlogPublishCredential.builder().id(1L).build())`）：
     寫入 `blogId`／`accessToken`／`accessTokenExpiresAt`（`LocalDateTime.now(TAIPEI).plusSeconds(expiresIn)`）／
     `refreshToken`／`accountLabel`／`needsReconnect=false`／`connectedAt`（僅在原本為 null 時設定，
     重新連接不覆寫第一次連接時間，但更新 `updatedAt`）。`save()`。
  8. 回 `CallbackResult(true, null)`。
  - **HTTP 呼叫全部用 `java.net.http.HttpClient`**（`HttpClient.newHttpClient()`，逾時
    `Duration.ofSeconds(15)`），JSON 解析用既有的 `com.fasterxml.jackson.databind.ObjectMapper`
    （建構子注入，本專案已在其他 service 廣泛使用同一顆 bean）。
- [x] 366.4e `String ensureAccessToken()`（供發布邏輯呼叫）：讀取 366.2b 的唯一列；不存在或
  `refreshToken` 為空 → 擲 `IllegalStateException("尚未連接 Blogger 帳號")`；`needsReconnect`
  為 true → 擲 `IllegalStateException("Blogger 授權已失效，請於設定頁重新連接")`；
  `accessTokenExpiresAt` 距今 > 60 秒 → 直接回既有 `accessToken`；否則：
  1. `POST https://oauth2.googleapis.com/token`，body
     `grant_type=refresh_token&refresh_token={refreshToken}&client_id={clientId}&client_secret={clientSecret}`。
  2. 回應含 `error=invalid_grant` → 設 `needsReconnect=true`、`save()`，擲
     `IllegalStateException("Blogger 授權已失效，請於設定頁重新連接")`。
  3. 其他非 2xx 或網路例外 → **不修改** `refreshToken`／`needsReconnect`，原樣往外擲例外
     （呼叫端視為本次發布失敗，見 366.7），下次重試即可。
  4. 成功 → 更新 `accessToken`／`accessTokenExpiresAt`；**回應若含 `refresh_token` 才覆寫
     `this.refreshToken`，缺漏時保留原值**（Google 的 refresh grant 回應通常不含此欄，
     不得整包覆寫成 null）。`save()`，回新的 `accessToken`。
- [x] 366.4f `void disconnect()`：找到既有列即整列刪除（`credentialRepo.deleteAll()`）；不存在則
  no-op（不擲例外）。

### 366.5 `BlogContentRenderer`（白名單內容組裝，純函數）

新增 `backend/src/main/java/com/steven/assets/service/export/BlogContentRenderer.java`：

- [x] 366.5a `static String render(JsonNode snapshot)`（純 static 方法或無狀態
  `@Component`，**不注入任何 repository**，方便單元測試傳固定假 `JsonNode`）。輸入為
  `TradingRadarSnapshotStore` 讀出的單筆快照 `JsonNode`（與 `TradingRadarExportService` 讀同一種
  結構）。輸出純 HTML 字串，**全程不得包含 `<script>` 標籤**。
- [x] 366.5b 固定開頭兩行（`<p>` 包裹）：
  `最後更新：{Asia/Taipei 當下時間 yyyy-MM-dd HH:mm}`（用 `LocalDateTime.now(ZoneId.of("Asia/Taipei"))`
  格式化，**不是**快照的 `generatedAt`——這行講的是「這篇文章何時被更新」，不是「快照何時算出」，
  兩者可能有些微落差是預期行為）；
  `本頁內容由系統規則自動產生，僅供個人投資紀錄公開，不構成任何投資建議。`
- [x] 366.5c **大盤卡**（讀 `snapshot.path("market")`——**只讀台股大盤，不得讀
  `snapshot.path("usMarket")`**，比照既有 `TradingRadarExportService.marketSheet()`「大盤總覽」
  分頁本就只讀 `market`、從未輸出 `usMarket` 的既有範圍）：一個 `<div>` 區塊，純文字列出
  `regimeLabel`／`score`／`asOfDate`／`price`／`changePercent`，接著把 `reasons`（陣列）／
  `risks`（陣列）各自轉成 `<ul><li>...</li></ul>`；任一欄缺值以「—」呈現，不得擲例外或整段省略。
- [x] 366.5d **個股決策**（讀 `snapshot.path("stocks")`，陣列）：每一檔一個
  `<details><summary>{stockCode} {stockName} — {actionLabel}</summary>...</details>`（`actionLabel`
  取自 1月~6月 軌的 `actionLabel`，作為 `<summary>` 的主要標籤；缺值時 `<summary>` 顯示
  `{stockCode} {stockName}`，不得留空白 `summary`）。展開內容依序：
  - 現價／漲跌%／是否持有（`held` → `是`／`否`）
  - 一周軌：`shortActionLabel`／`shortScore`／支持訊號 `shortReasons`（`<ul>`）／風險提醒
    `shortRisks`（`<ul>`）
  - 1周~1月軌：**`swingActionLabel`**（既有中文標籤欄位，`TradingRadarDto.java:838`，
    `TradingRadarView.vue:707-709` 既有頁面正在使用；**不得**改讀內部代碼欄位 `swingAction`——
    與另兩軌一致顯示中文標籤，缺值時 fallback「今日不交易」，比照
    `TradingRadarView.vue:709` 既有寫法 `row.swingActionLabel || '今日不交易'`，不得顯示原始
    英文/內部代碼）／`swingScore`／支持訊號 `swingReasons`（`<ul>`）／風險提醒 `swingRisks`
    （`<ul>`）——`swingReasons`／`swingRisks` 是既有欄位（`TradingRadarDto.java:840-841`），
    只是既有 `TradingRadarExportService` 的 Excel 匯出從未輸出這兩欄，本任務**不比照這個既有
    匯出缺口**，一定要接上
  - 1月~6月軌：`actionLabel`／`score`／支持訊號 `reasons`（`<ul>`）／風險提醒 `risks`（`<ul>`）
  - 上述六個 `<ul>`（三軌各自的 reasons／risks）**每一個都缺值各自省略，不留空殼**，不是共用
    同一組「支持訊號／風險提醒」標題
  - **不得輸出**：`evidence`／`fundamental`／`extendedIndicators`／`weeklyIndicators`／
    `dailyCandle`／任何 `*Provider`／`*SourceUrl`／`*AsOf`／利率／treasury 相關欄位、
    `actionGateReasons`、`counterTrend*`。
- [x] 366.5e **台美公開資訊**（讀 `snapshot.path("publicInformation")`，陣列，可能不存在或空）：
  一個 `<ul>`，每項 `<li>{publishedAt} ｜ {source} ｜ {title 或連結} — {summary}</li>`。**`url`
  需要兩層防護，缺一不可**：
  1. **scheme 白名單**：只有 `url` 以 `http://` 或 `https://`（不分大小寫）開頭才輸出
     `<a href="{url}">{title}</a>`；其餘 scheme（含 `javascript:`、空字串、缺漏）一律降級為純
     文字 `{title}`（不輸出 `<a>` 標籤）。
  2. **即使通過第 1 層，`url` 本身在填入 `href="..."` 之前仍要經過 366.5f 的 `escape()`**——
     一個以合法 `https://` 開頭、但內含雙引號的網址（例如
     `https://evil.example/"><script>...`）若不轉義，雙引號會提前結束 `href` 屬性，讓後續字元
     被解讀成新屬性或新標籤，實際產生 `<script>` 標籤，正面違反本頁「全程不得包含 `<script>`
     標籤」的不變式。
  這批網址來自背景爬蟲抓取的第三方來源，不是使用者輸入也不是系統自產，公開發布前必須比照一般
  外部輸入驗證，不得原樣信任。陣列不存在或為空時整個區塊連標題一起省略（不輸出「查無資料」之類
  的空殼標題）。
- [x] 366.5f 所有從快照讀出、將寫進 HTML 的文字欄位——含 366.5e 的 `url`（填入 `href` 屬性前）與
  `stockName`／`title`／`summary`／`source` 等（填入標籤內文前）——一律做 HTML escape
  （`<`／`>`／`&`／`"` 轉義），避免快照內容（例如新聞標題含特殊字元，或第三方網址含雙引號）破壞
  HTML 結構、造成屬性逃逸，或被 Blogger 當成標籤解讀。新增一支 private static helper
  `escape(String s)`，`s == null` 回空字串。

### 366.6 `BlogPublishService`（建立/更新文章）

新增 `backend/src/main/java/com/steven/assets/service/BlogPublishService.java`（`@Service`）：

- [x] 366.6a 建構子注入 `BlogOAuthService blogOAuthService`、`BlogPublishCredentialRepository credentialRepo`、
  `TradingRadarExportSettingRepository settingRepo`（既有類別）、`TradingRadarSnapshotStore snapshotStore`
  （既有類別）、`ObjectMapper objectMapper`。
- [x] 366.6b `record PublishResult(boolean success, String status, String postUrl)`；
  **`PublishResult publishLatest(long ownerId)`——手動發布（`POST /blog-publish`）呼叫這支，
  查快照與「查無快照」的業務判斷封裝在這裡，不得讓 controller 直接呼叫 `TradingRadarSnapshotStore`
  自己判斷**（全庫既有三個 `TradingRadarSnapshotStore` 呼叫端——`TradingRadarExportService`／
  `TradingRadarExportScheduleService`／`TradingRadarService`——皆為 Service 層，沒有 Controller
  直接呼叫的既有先例，本任務不開先例）：
  1. 以 `snapshotStore` 讀該 owner 當日範圍（`today 00:00` 至現在）的最新一筆快照（沿用既有
     `TradingRadarSnapshotStore.range(ownerId, fromEpoch, toEpoch)` 讀法，取回傳清單最後一筆；
     若既有元件已有更直接的「取最新一筆」方法則優先沿用，不得為本任務改變快照讀取的既有時效語意）。
  2. 查無快照 → 回 `PublishResult(false, "當日尚無交易雷達快照，請先重新整理", null)`，**不寫入**
     `blogLastRunAt`／`blogLastStatus`（沒有內容可發，比照既有 Drive 同步在無快照時的既有邏輯，
     不算一次「執行」）。
  3. 查得快照 → 委派 `publish(ownerId, snapshot)`（下方核心方法），回其結果。

  **`PublishResult publish(long ownerId, JsonNode snapshot)`——核心方法，排程路徑（366.9a）直接
  呼叫這支、傳入該輪已查好的快照，不得為此再查一次 Redis**：
  1. `accessToken = blogOAuthService.ensureAccessToken()`——擲例外時 catch 住，回
     `PublishResult(false, "尚未連接 Blogger 帳號或授權已失效：" + e.getMessage(), null)`，
     **不得讓例外往外逸出**（呼叫端 366.9 需要這支方法永不擲出，見該節說明）。
  2. `blogId = credentialRepo.findById(1L).map(BlogPublishCredential::getBlogId).orElse(null)`；
     為 null → 回 `PublishResult(false, "尚未解析出 blogId，請重新連接", null)`。
  3. `html = BlogContentRenderer.render(snapshot)`。
  4. `setting = settingRepo.findByOwnerUserId(ownerId).orElse(null)`；為 null → 回
     `PublishResult(false, "查無交易雷達匯出設定，請先於設定頁儲存一次", null)`
     （理論上不會發生——啟用 `blogEnabled` 前必先有一列設定，此為防禦性分支）。
  5. `postId = setting.getBlogLastPostId()`。
  6. `postId == null`：`POST https://www.googleapis.com/blogger/v3/blogs/{blogId}/posts?isDraft=false`
     （header `Authorization: Bearer {accessToken}`、`Content-Type: application/json`，body
     `{"kind":"blogger#post","title":"交易雷達每日結果","content":"{html 轉義成 JSON 字串}"}`）。
     成功（2xx）→ 解析回應 `id`／`url`，寫回 `setting.blogLastPostId`／`blogLastPostUrl`。
     非 2xx → 回 `PublishResult(false, "建立文章失敗：HTTP " + status, null)`。
  7. `postId != null`：`PUT https://www.googleapis.com/blogger/v3/blogs/{blogId}/posts/{postId}`
     （同 header，body 同上但不含 `isDraft`）。
     - 2xx → 解析回應 `url` 更新 `setting.blogLastPostUrl`（`postId` 不變）。
     - 404 → 視為文章已被手動刪除，**回退成第 6 步的 POST 建立**，成功後覆寫 `blogLastPostId`／
       `blogLastPostUrl`；仍失敗則回 `PublishResult(false, "更新文章找不到既有貼文，重新建立亦失敗：HTTP " + status, null)`。
     - 其他非 2xx → 回 `PublishResult(false, "更新文章失敗：HTTP " + status, null)`。
  8. 成功路徑：`setting.setBlogLastRunAt(LocalDateTime.now(TAIPEI))`；
     `setting.setBlogLastStatus("成功：" + postUrl)`；`settingRepo.save(setting)`；回
     `PublishResult(true, "成功：" + postUrl, postUrl)`。
  9. 失敗路徑（第 1/2/4/6/7 步任一失敗分支）：**仍要**寫入 `blogLastRunAt`／`blogLastStatus`
     （狀態文字取自對應分支的失敗說明）並 `save()`，讓設定頁看得到失敗原因，再回傳
     `PublishResult(false, ...)`。
  10. HTTP 呼叫同樣一律用 `java.net.http.HttpClient`；JSON body 組裝與回應解析用注入的
      `ObjectMapper`（`writeValueAsString`／`readTree`），**不得用手刻字串串接 JSON**
      （366.5f 的 HTML 已轉義，但 HTML 本身可能含雙引號、換行，必須讓 Jackson 負責 JSON 層的
      轉義，兩層轉義各自獨立不可省略任一層）。

### 366.7 業務層 Controller

新增 `backend/src/main/java/com/steven/assets/controller/TradingRadarBlogController.java`
（`@RestController @RequestMapping("/api/trading-radar") @RequiredArgsConstructor`，與既有
`TradingRadarController` 平行的獨立類別，避免既有檔案繼續膨脹）：

- [x] 366.7a `GET /blog-oauth/authorize-url`：**先呼叫
  `BlogPublishOutputSupport.isBlogAllowedFor(CurrentUserContext.getEffectiveUserId())`**，false 則
  擲 `AdminRequiredException`（比照 `PortfolioAdviceController.java:101` 既有寫法，由既有
  `GlobalExceptionHandler` 轉 403）——**不得只靠 BFF 的 `hasAuthority` 單一防線**，business 層必須
  獨立覆核一次（與 366.7c／366.7e／366.7f 的既有防禦深度一致）。通過後回
  `{"url": blogOAuthService.buildAuthorizeUrl()}`。
- [x] 366.7b `GET /blog-oauth/callback?code&state`：**同樣先呼叫
  `BlogPublishOutputSupport.isBlogAllowedFor(CurrentUserContext.getEffectiveUserId())`**，false 則
  直接回 302 導回 `/trading-radar?blogOauth=error&reason=forbidden`（**不呼叫**
  `blogOAuthService.handleCallback(...)`，即不進行任何 token 換取）——這是本 Requirement 唯一真正
  把 Google token 寫入全域憑證（有副作用）的端點，必須與其餘端點防禦深度一致，不得只靠 BFF 單層
  防護。通過覆核後才呼叫 `blogOAuthService.handleCallback(code, state)`，依 `CallbackResult` 回
  `ResponseEntity.status(302).location(URI.create(redirectTarget)).build()`：成功 →
  `/trading-radar?blogOauth=connected`；失敗 → `/trading-radar?blogOauth=error&reason={result.reason()}`。
  `redirectTarget` 為**相對路徑**（不含 domain）——瀏覽器本就在本 App 網域內，相對路徑導向即可，
  不需要再讀一次 `publicBaseUrl` 拼絕對網址。
- [x] 366.7c `POST /blog-oauth/disconnect`：需 owner 為主要管理者（`BlogPublishOutputSupport.isBlogAllowedFor`
  判定 `CurrentUserContext.getEffectiveUserId()`），否則擲 `AdminRequiredException`（既有類別，
  由既有 `GlobalExceptionHandler` 轉 403，比照 `PortfolioAdviceController.java:101` 既有寫法）。
  通過後呼叫 `blogOAuthService.disconnect()`，回 `ResponseEntity.noContent().build()`。
- [x] 366.7d `GET /blog-status`：owner 取 `CurrentUserContext.getEffectiveUserId()`（無使用者時
  回一個「未連接、`blogEnabled=false`」的預設值，不得 500）。組回應（新增
  `backend/src/main/java/com/steven/assets/dto/TradingRadarBlogDto.java` 定義
  `record StatusResponse(boolean connected, String accountLabel, String blogUrl, boolean blogEnabled, String lastRunAt, String lastStatus, String lastPostUrl)`）：
  `connected` 取自 366.2b 是否存在該列且 `refreshToken` 非空且 `needsReconnect` 為 false；
  `blogEnabled`／`lastRunAt`／`lastStatus`／`lastPostUrl` 取自該 owner 的
  `trading_radar_export_setting`（不存在則對應欄位為 `false`／`null`）。**回應絕不包含
  `accessToken`／`refreshToken` 欄位**。
- [x] 366.7e `PUT /blog-enabled`（body `record EnabledRequest(boolean enabled)`，同檔
  `TradingRadarBlogDto`）：owner 取 `requireOwnerId()` 風格（無使用者擲
  `IllegalArgumentException` → 400，比照 `TradingRadarExportScheduleService.requireOwnerId()`
  既有寫法）；`enabled == true` 時先呼叫 `BlogPublishOutputSupport.isBlogAllowedFor(ownerId)`，
  false 則擲 `AdminRequiredException("交易雷達發布到 Blog 僅限主要管理者啟用")`；通過後 upsert
  `trading_radar_export_setting`（沿用既有 `settingRepo.findByOwnerUserId(ownerId).orElseGet(...)`
  既有寫法）寫入 `blogEnabled`，`save()`，回 366.7d 同款 `StatusResponse`。
- [x] 366.7f `POST /blog-publish`：owner 取 `requireOwnerId()` 風格；先呼叫
  `BlogPublishOutputSupport.isBlogAllowedFor(ownerId)`，false 則擲 `AdminRequiredException`；
  通過後呼叫 `blogPublishService.publishLatest(ownerId)`（**controller 不得自行呼叫
  `TradingRadarSnapshotStore` 或做「有沒有快照」的判斷**——查快照與該判斷已封裝在 366.6b 的
  `publishLatest` 內部，controller 只轉譯 `PublishResult` 為 HTTP 狀態碼），依 `PublishResult`
  回應：`status` 為「當日尚無交易雷達快照，請先重新整理」時回 400；其餘 `success()=false` 回
  502（失敗原因是外部服務或授權問題，不是使用者輸入錯誤）；`success()=true` 回 200（含
  `postUrl`）。

### 366.8 BFF 端

- [x] 366.8a `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java` 新增四條規則
  （放在既有 `.pathMatchers("/api/bff/backup-restore/**").hasAuthority(...)` 同一群組附近）：
  ```java
  .pathMatchers("/api/bff/trading-radar/blog-oauth/**").hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
  .pathMatchers("/api/bff/trading-radar/blog-status").hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
  .pathMatchers(HttpMethod.PUT, "/api/bff/trading-radar/blog-enabled").hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
  .pathMatchers(HttpMethod.POST, "/api/bff/trading-radar/blog-publish").hasAuthority(AuthConstants.AUTHORITY_CONFIGURED_ADMIN)
  ```
  **`TradingRadarBffController.java`／`TradingRadarBffRoutes.java` 不需要任何新增方法**——
  六支端點在 business 與 BFF 兩端路徑後綴與 method 完全相同，既有 wildcard rewrite
  （`/api/bff/trading-radar/**` → `/api/trading-radar/**`）已能轉發，包含 302 回應（Gateway
  原樣轉發 status/Location header，不需 BFF 額外處理）。
- [x] 366.8b `docker-compose.yml` 的 `business-services` 服務區塊新增
  `GOOGLE_CLIENT_ID: ${GOOGLE_CLIENT_ID:-placeholder-client-id}`／
  `GOOGLE_CLIENT_SECRET: ${GOOGLE_CLIENT_SECRET:-placeholder-secret}`／
  `APP_PUBLIC_BASE_URL: ${APP_PUBLIC_BASE_URL:-}`（見 366.4a），寫在 `environment:` 區塊並附註
  這是給 Blogger OAuth（Requirement 102）使用、與 `bff` 服務的登入用途分開注入。

### 366.9 排程整合

- [x] 366.9a `backend/src/main/java/com/steven/assets/service/TradingRadarExportScheduleService.java`
  的 `runScheduled(TradingRadarExportTime t, LocalDate today)` 方法（現有 `:423-449`）在既有
  `applyGdriveStatus(ownerId, r)` 呼叫**之後**新增一段：讀該 owner 的
  `TradingRadarExportSetting`，`blogEnabled` 為真時呼叫新注入的 `BlogPublishService.publish(ownerId, 該輪已讀出的快照 JsonNode)`（**沿用同一輪已經取得的快照物件，不得為此再查一次 Redis**——
  `writeDailyExport` 內部已經算出 `ExportDoc`，若該結構取不到原始 `JsonNode`，改為讓
  `runScheduled` 自行以 `TradingRadarSnapshotStore` 取當日最新一筆，**取一次即可，不得為 Excel
  與 blog 各查一次**，具體接線方式由實作者依現有方法簽章判斷最小改動路徑，但「同一輪只查一次
  快照」這個既有原則不可違反）。呼叫包在獨立 `try/catch`：失敗只記 log 與更新
  `blog_last_run_at`／`blog_last_status`，**不得**讓例外往外逸出影響本方法既有的
  `finally` 區塊（`t.setLastRunDate(today)` 等既有收尾邏輯必須照常執行）。
- [x] 366.9b `run-now`（`TradingRadarExportScheduleService.runNow()`）**不觸發** blog 發布——
  該方法維持現狀，不新增任何 blog 相關呼叫。
- [x] 366.9c `blogEnabled` 為真但當輪沒有快照可發布（既有 `writeDailyExport` 回傳 `null` 的分支）
  時，**不呼叫** blog 發布（沒有內容可發，比照既有 Drive 同步在無快照時只記 skip 狀態、不觸發
  上傳的既有邏輯）。

### 366.10 前端

`frontend/src/views/TradingRadarView.vue`：

- [x] 366.10a `.header-actions`（`:8-10`）新增第三顆按鈕，緊接既有〔重新整理〕之後：
  ```html
  <el-button v-if="auth.isConfiguredAdmin" :icon="Promotion" :loading="publishingBlog" @click="onPublishBlog">匯出到 blog</el-button>
  ```
  （`Promotion` 或其他 Element Plus 既有 icon，若專案既有 icon import 清單沒有 `Promotion`，
  改用已匯入的任一語意相近圖示，不得為此新增外部圖示套件）。
- [x] 366.10b `onPublishBlog()`：先 `GET /api/bff/trading-radar/blog-status` 讀連接狀態；
  `connected === false` → `ElMessage.warning('尚未連接 Blogger 帳號，請先於下方設定卡完成連接')`
  並捲動到設定卡（`document.getElementById('blog-publish-card')?.scrollIntoView({behavior:'smooth'})`，
  設定卡需加此 `id`，見 366.10d）；`connected === true` → 呼叫
  `ElMessageBox.confirm('即將把交易雷達目前結果公開發布/更新到 https://twleader.blogspot.com/，任何人皆可瀏覽，內容含個股代號、三軌分數與加減碼建議，確定要發布嗎？', '公開發布確認', {confirmButtonText: '確定發布', cancelButtonText: '取消', type: 'warning'})`，
  確認後 `publishingBlog.value = true`，呼叫 `POST /api/bff/trading-radar/blog-publish`，成功顯示
  `ElMessage.success()` 並附文章網址（`ElMessage` 的 `dangerouslyUseHTMLString` 或另用
  `ElNotification` 附可點擊連結，二擇一，選型時避免引入新依賴），失敗顯示
  `ElMessage.error(該次回應的錯誤訊息)`；`finally` 重置 `publishingBlog.value = false`。
  取消對話框（`catch` 到 `'cancel'`）時不送出任何請求、不顯示錯誤訊息。
- [x] 366.10c 頁面 `onMounted`（或既有初始化流程）新增：讀 `route.query.blogOauth`，
  `'connected'` → `ElMessage.success('已成功連接 Blogger 帳號')`；`'error'` →
  `ElMessage.error('連接 Blogger 帳號失敗：' + (route.query.reason || '未知原因'))`；
  顯示後立即 `router.replace({query: {...route.query, blogOauth: undefined, reason: undefined}})`
  清掉這兩個 query key，避免使用者重新整理頁面時重複跳出訊息。
- [x] 366.10d 新增〔匯出到 Blog 設定〕卡片（`id="blog-publish-card"`，`v-if="auth.isConfiguredAdmin"`），
  緊接既有〔匯出輸出檔案設定〕卡片（`:861-`）之後，樣式比照既有 `sched-card`：
  - 頁面載入時（`auth.isConfiguredAdmin` 為真時）呼叫 `GET /api/bff/trading-radar/blog-status`
    填入本地 reactive 狀態 `blogStatus`。
  - `blogStatus.connected === false`：顯示說明文字＋〔連接 Blogger 帳號〕按鈕，`@click` 呼叫
    `GET /api/bff/trading-radar/blog-oauth/authorize-url` 取得 `{url}`，
    `window.location.href = url`（整頁導向，不是 XHR 後在頁面內處理）。
  - `blogStatus.connected === true`：顯示 `已連接：{blogStatus.accountLabel}`；
    若 `blogStatus.lastPostUrl` 存在，顯示可點擊連結（`target="_blank"`）；
    〔中斷連接〕按鈕（`@click` 呼叫 `ElMessageBox.confirm` 二次確認後 `POST
    /api/bff/trading-radar/blog-oauth/disconnect`，成功後重新讀取 `blog-status`）；
    「同步發布到 blog」`<el-switch>` 綁 `blogStatus.blogEnabled`，`@change` 呼叫
    `PUT /api/bff/trading-radar/blog-enabled` `{enabled}`，旁邊加提示文字「沿用上方〔匯出執行時間
    設定〕的執行時間點」；顯示「上次發布：{lastRunAt} — {lastStatus}」（皆為 null 時顯示
    「尚未發布過」）。
- [x] 366.10e `frontend/src/api/index.js` 的 `tradingRadar` 物件新增：
  `getBlogStatus: () => api.get('/bff/trading-radar/blog-status')`、
  `getBlogAuthorizeUrl: () => api.get('/bff/trading-radar/blog-oauth/authorize-url')`、
  `disconnectBlog: () => api.post('/bff/trading-radar/blog-oauth/disconnect')`、
  `setBlogEnabled: (enabled) => api.put('/bff/trading-radar/blog-enabled', { enabled })`、
  `publishBlog: () => api.post('/bff/trading-radar/blog-publish')`（依既有 `api/index.js` 對
  `tradingRadar` 其他方法的既有寫法與 base path 慣例調整，若既有慣例的 base path 前綴或呼叫方式與
  本行不同，以檔案既有慣例為準，不得自創第二種呼叫風格）。

### 366.11 測試

- [x] 366.11a 新增 `backend/src/test/java/com/steven/assets/service/` 底下、被測類別為
  `BlogOAuthService` 的單元測試（依本專案既有「被測類別＋Test.java」命名慣例命名）：
  - `buildAuthorizeUrl()` 回傳網址含 `scope=`（URL-encoded 的 blogger scope）、
    `access_type=offline`、`prompt=consent`、`state=` 且該 `state` 確實可被同一個
    `handleCallback` 消費一次。
  - `handleCallback` 對「`state` 不存在」「`state` 已過期」兩種輸入皆回
    `CallbackResult(false, "state_expired")` 且不呼叫任何 HTTP client。
  - 用可替換的 HTTP client 抽象（例如把 `HttpClient` 換成介面注入，測試提供假實作回傳固定
    JSON body）驗證：正常換取寫入 `BlogPublishCredential` 四個關鍵欄位
    （`blogId`／`accessToken`／`refreshToken`／`accountLabel`）；`token` 回應缺
    `refresh_token` 時回 `missing_refresh_token` 且**不寫入**任何 credential 列。
  - `ensureAccessToken()`：`accessTokenExpiresAt` 未到期時不觸發任何 HTTP 呼叫；到期時觸發
    refresh 且**保留原 `refreshToken`**（假 refresh 回應不含 `refresh_token` 欄位時）；
    `invalid_grant` 回應觸發 `needsReconnect=true` 並擲例外；一般網路例外時
    `refreshToken`／`needsReconnect` 皆維持原值。
- [x] 366.11b 新增 `backend/src/test/java/com/steven/assets/service/export/` 底下、被測類別為
  `BlogContentRenderer` 的單元測試（同一命名慣例）：以固定假 `JsonNode`（用
  `ObjectMapper().readTree(...)` 讀一段內嵌 JSON 字面）驗證：輸出不含 `<script>`；輸出不含
  〈不得上傳到 blog 的畫面元素〉對應字串（例如不含 `EXPORT_OUTPUT_DIR`／`/home/steven`／
  `gdrive`／`Google Drive` 等關鍵字）；`<details>` 數量與假快照的個股數一致；`reasons`／`risks`
  缺值時不輸出空 `<ul></ul>`；HTML 特殊字元（測資故意放 `<`／`&`／`"` 於 `stockName`／`title`）
  被正確轉義；1周~1月軌讀 `swingActionLabel` 而非 `swingAction`（測資故意讓兩者不同值，斷言輸出
  含前者不含後者）；台股大盤與美股大盤（`market`／`usMarket`）並存的測資中，輸出不含 `usMarket`
  對應數值；`publicInformation[].url` 測資分別放 `https://...`／`javascript:alert(1)`／空字串，
  斷言只有第一種輸出 `<a href>`，後兩種降級為純文字且不含 `<a`；**另補一筆合法 scheme 但含雙引號
  的攻擊測資** `https://evil.example/"><script>alert(1)</script>`，斷言輸出的 `href` 屬性值中
  雙引號已被轉義（`&quot;` 或等價寫法），且整份輸出不含裸露的 `<script>` 標籤——證明 scheme 白名單
  與 escape 兩層防護都有生效，不是只做了第一層就讓第二層留白。
- [x] 366.11c 新增 `backend/src/test/java/com/steven/assets/service/` 底下、被測類別為
  `BlogPublishService` 的單元測試（同一命名慣例）：以可替換的 HTTP client 假物件驗證：
  `blogLastPostId` 為 `null` 時走建立（POST）並存下回應 `postId`／`url`；已有 `blogLastPostId`
  時走更新（PUT）；更新回 404 時回退為建立並覆寫 `postId`；`ensureAccessToken()` 擲例外時
  `publish()` 回 `PublishResult(false, ...)` 而非讓例外往外逸出，且仍寫入
  `blogLastRunAt`／`blogLastStatus`；`publishLatest(ownerId)` 在查無快照時回對應的
  `PublishResult(false, "當日尚無交易雷達快照，請先重新整理", null)` 且**不寫入**
  `blogLastRunAt`／`blogLastStatus`、也不呼叫 `ensureAccessToken()`（沒有內容可發，不該先去
  打 Google）；查得快照時 `publishLatest` 委派給 `publish` 並回傳同一個結果。
- [x] 366.11d 既有 `backend/src/test/java/com/steven/assets/service/TradingRadarExportScheduleServiceTest.java`
  新增案例：`blogEnabled=true` 時 `runScheduled` 完成既有本機／Drive 產出後會呼叫
  `BlogPublishService.publish(...)`；令該呼叫擲出例外，斷言既有 `lastRunAt`／`lastRunStatus`／
  `gdriveLastRunAt`／`gdriveLastStatus`（若該輪也啟用 Drive）與既有測試案例一致地正常寫入，
  不因 blog 發布失敗而回滾；`blogEnabled=false` 時完全不呼叫 `BlogPublishService`。
- [x] 366.11e 新增 `backend/src/test/java/com/steven/assets/service/` 底下、被測類別為
  `BlogPublishOutputSupport` 的單元測試（同一命名慣例）：`ownerUserId == null` 回 `false`；
  查無使用者回 `false`；email 為主要管理者回 `true`；非主要管理者回 `false`。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
curl -s http://localhost:8080/actuator/health
```

以下項目**需要使用者先在 Google Cloud Console 完成背景中列出的三項前置準備**，不在單純的
程式碼跑起來範圍內，由使用者手動驗收：

1. 登入本 App（`tw.leader@gmail.com`，主要管理者）進交易雷達頁，確認頁首出現〔匯出到 blog〕、
  頁尾出現〔匯出到 Blog 設定〕卡片（非主要管理者登入時兩者皆不可見）。
2. 於設定卡按〔連接 Blogger 帳號〕，於 Google 同意畫面切換／登入 `shi.chihung@gmail.com` 完成授權，
  導回後設定卡顯示已連接帳號資訊。
3. 按頁首〔匯出到 blog〕，確認彈出確認對話框，確認後 `https://twleader.blogspot.com/` 出現/更新
  一篇標題為「交易雷達每日結果」的文章，內容不含〈背景〉列出的三類禁止元素、不含絕對路徑、
  不含 Drive／Blogger 連接狀態。
4. 開啟「同步發布到 blog」開關；等待既有〔匯出執行時間設定〕下一個到點時間，確認 Excel／JSON
  落檔（與 Drive 同步，如已啟用）照常完成後，blog 文章同步更新，設定卡的「上次發布」隨之更新。

## 完成報告

**實作方式**：後端＋BFF 與前端分派給兩支獨立 subagent 並行實作於各自隔離 worktree；後端 subagent 中途因
session usage 上限中斷（在「編譯測試原始碼、準備跑完整測試套件」處停止），但其隔離 worktree
（`agent-a85f2b72f5d8d3fcd`）內已完成全部 366.1–366.9、366.11 的程式碼。因該隔離 worktree 是從
`origin/main` 當時最新 commit（`41d1201a`）分支、而非本 session 分支，未帶有本次未提交的
`spec/tasks/t366_trading_radar_blog_publish.md`，故該 subagent 實際上是**依主 agent 派工 prompt 裡的
完整摘要**實作，未能直接讀取任務檔全文；主 agent 事後逐一比對其產出的關鍵檔案（`BlogPublishCredential`／
`BlogContentRenderer`／`BlogOAuthService`／`BlogPublishService`／`TradingRadarBlogController`）與任務檔
字面要求，確認完全一致（含三輪 spec 審查修正的細節：`CHECK(id=1)` 單例、三軌各自 reasons/risks、
url 雙層防護、`publishLatest`/`publish` 分離、business 層 `isBlogAllowedFor` 覆核）後，以 diff 方式套用進
本 session worktree。前端 subagent 正常完成，同樣因隔離 worktree 機制以 diff 方式套用。

**實際新增/修改的檔案**（相對 `origin/main` 41d1201a）：

新增：
- `backend/src/main/java/com/steven/assets/model/BlogPublishCredential.java`
- `backend/src/main/java/com/steven/assets/repository/BlogPublishCredentialRepository.java`
- `backend/src/main/java/com/steven/assets/service/BlogPublishOutputSupport.java`
- `backend/src/main/java/com/steven/assets/service/BlogOAuthService.java`
- `backend/src/main/java/com/steven/assets/service/GoogleHttpClient.java`（介面）／
  `JdkGoogleHttpClient.java`（`java.net.http.HttpClient` 實作）——供測試以假物件替換，任務檔未點名
  這兩支檔名，但落在 366.4／366.6「以可替換的 HTTP client 假物件測試」既有要求範圍內
- `backend/src/main/java/com/steven/assets/service/export/BlogContentRenderer.java`
- `backend/src/main/java/com/steven/assets/service/BlogPublishService.java`
- `backend/src/main/java/com/steven/assets/controller/TradingRadarBlogController.java`
- `backend/src/main/java/com/steven/assets/dto/TradingRadarBlogDto.java`
- `backend/src/main/resources/db/changelog/changes/v1.112.0-trading-radar-blog-publish.sql`
- `backend/src/test/java/com/steven/assets/service/BlogOAuthServiceTest.java`／
  `BlogPublishOutputSupportTest.java`／`BlogPublishServiceTest.java`／
  `export/BlogContentRendererTest.java`

修改：
- `backend/src/main/java/com/steven/assets/model/TradingRadarExportSetting.java`（新增五欄）
- `backend/src/main/java/com/steven/assets/service/TradingRadarExportScheduleService.java`
  （`runScheduled` 追加 blog 發布步驟）
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml`（新增一筆 include）
- `backend/src/test/java/com/steven/assets/service/TradingRadarExportScheduleServiceTest.java`／
  `RadarManualExportDualFormatTest.java`／`TradingExportGdriveTest.java`（配合
  `TradingRadarExportSetting` 新增五欄調整既有建構呼叫）
- `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`（新增四條路徑規則）
- `docker-compose.yml`（`business-services` 新增 `GOOGLE_CLIENT_ID`／`GOOGLE_CLIENT_SECRET`／
  `APP_PUBLIC_BASE_URL` 三個環境變數）
- `.env.example`（新增 `APP_PUBLIC_BASE_URL` 附註）
- `frontend/src/api/index.js`（`tradingRadar` 新增五個方法）
- `frontend/src/views/TradingRadarView.vue`（頁首按鈕、設定卡、確認對話框、OAuth 導回訊息處理）

**驗證輸出**：
- `mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true` → `Tests run: 1444, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- `mvn -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true` → `Tests run: 177, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- `node ./node_modules/.bin/vite build`（frontend）→ exit code 0，無語法錯誤。
- 尚未執行 AC9 的實機 OAuth 全流程驗收（需使用者於 Google Cloud Console 完成三項前置準備，非本次
  程式碼可自行完成）；`/run-stack` 的容器重建與健康檢查另行於收尾步驟執行。

**與原計畫的偏差**：
1. `GoogleHttpClient`／`JdkGoogleHttpClient` 為任務檔未明文點名的新增檔案，理由如上（可替換 HTTP client
   抽象，供測試用假物件替換，屬既有測試要求的自然實作路徑，非範圍外擴充）。
2. 後端 subagent 因隔離 worktree 缺任務檔而改依派工 prompt 摘要實作，主 agent 已逐檔比對任務檔字面
   （含三輪 spec 審查的修正項）確認一致，未發現偏差。
3. `spec-review` 通過後又補了三輪修正的最後一個 minor（`TradingRadarExportSetting.java` 行號引用
   由 `20-21` 訂正為 `26-27`）——此訂正落在 spec 定案階段而非實作階段，不影響本次實作內容。
