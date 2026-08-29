# [t387] Google 登入固定顯示帳號選擇器（`prompt=select_account`）

**對應 Requirements:** Requirement 122／Task 387（Google 登入固定顯示帳號選擇器；每次觸發登入都帶 `prompt=select_account`，不因 scheme／既有 session／帳號數量而不同，不改變登入後續行為）
**前置任務:** 無（僅修改既有 Requirement 28 落地的 `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`，不新增資料表、不新增端點）
**Liquibase changeset:** 無

## 背景

本系統的 Google OAuth2 登入（`SecurityConfig.springSecurityFilterChain()` 的 `.oauth2Login(o -> o.authenticationSuccessHandler(spaSuccessHandler()))`）目前完全沒有自訂 `authorizationRequestResolver`，走 Spring Security 預設的 `DefaultServerOAuth2AuthorizationRequestResolver`，產生的 Authorization Request 不帶 `prompt` 參數。

**現在的行為（錯誤／不符期望）：** 使用者點擊「使用 Google 登入」時，若瀏覽器目前有某個 Google session（例如同時登入多個 Gmail 帳號、其中一個是最近使用的），Google 可能略過帳號選擇畫面直接沿用該帳號完成授權，使用者無法在當下明確選擇要用哪一個帳號登入本系統。這在瀏覽器同時登入多個 Gmail 帳號時特別容易造成困惑：使用者以為自己用了帳號 A，實際上 Google 悄悄用了帳號 B；若帳號 B 不在本系統核准名單或 OAuth consent screen 的 Test users 名單內，Google 會擋下授權，使用者會誤以為是系統登入功能故障，而非自己選錯了帳號。

**正確行為：** 每次觸發 `/oauth2/authorization/google` 產生的 Authorization Request URL，都必須帶有 `prompt=select_account` 查詢參數（最終出現在導去 `https://accounts.google.com/o/oauth2/v2/auth?...&prompt=select_account&...`），強制 Google 每次都顯示帳號選擇器，不論瀏覽器當下的 Google session 狀態。

**驗證此問題存在的方式（本任務撰寫時已用 curl 直接對本機 stack 驗證過現況，供 implementer 對照修復前後差異）：**

```bash
curl -s -D - -o /dev/null "http://localhost/oauth2/authorization/google" | grep -i '^location:'
```

修復前，`location` 標頭的查詢字串裡只有 `response_type`、`client_id`、`scope`、`state`、`redirect_uri`、`nonce`，沒有 `prompt`。修復後必須看到 `prompt=select_account`（URL-encode 後仍是這個字面值，`=` 不會被編碼）。

## 要做什麼

- [ ] **387.1 新增自訂 `ServerOAuth2AuthorizationRequestResolver` bean。** 在 `SecurityConfig.java` 新增一個 `@Bean public ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver(ReactiveClientRegistrationRepository clientRegistrationRepository)`，內部建構 `DefaultServerOAuth2AuthorizationRequestResolver`（建構子吃 `clientRegistrationRepository`，沿用預設的 authorization-request-uri pattern `/oauth2/authorization/{registrationId}`，不自訂 pattern），呼叫其 `setAuthorizationRequestCustomizer(customizer)`，其中 `customizer` 為 `Consumer<OAuth2AuthorizationRequest.Builder>`，實作為 `builder -> builder.additionalParameters(params -> params.put("prompt", "select_account"))`。回傳這個包好 customizer 的 resolver 實例。**只加這一個 additional parameter，不覆寫 `authorizationRequestUri`、`redirectUri`、`scopes`、`clientId` 等既有由 Spring Security 依 `ClientRegistration` 與 `X-Forwarded-*` 動態算出的欄位**——這些欄位的既有動態行為（Requirement 28 的 `forward-headers-strategy: framework`）必須維持不變，改壞會讓 https 場景下的 `redirect_uri` 計算跟著壞掉。
- [ ] **387.2 把 resolver 接進 `oauth2Login`。** 把 `.oauth2Login(o -> o.authenticationSuccessHandler(spaSuccessHandler()))` 改為 `.oauth2Login(o -> o.authenticationSuccessHandler(spaSuccessHandler()).authorizationRequestResolver(authorizationRequestResolver(clientRegistrationRepository)))`——`springSecurityFilterChain(ServerHttpSecurity http)` 方法簽章需新增參數 `ReactiveClientRegistrationRepository clientRegistrationRepository`（Spring 容器自動注入既有的 `ReactiveClientRegistrationRepository` bean，這是 Spring Boot OAuth2 client autoconfiguration 既有提供的 bean，不需要另外定義）。若採用「直接呼叫 387.1 的 `@Bean` 方法」在同一個 `@Configuration` class 內會被 Spring 代理成單例，等同注入同一個 bean 實例，允許這樣直接呼叫；若嫌不直觀，也可以改成在 `springSecurityFilterChain` 方法參數直接宣告 `ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver` 讓 Spring 注入 387.1 定義的 bean，兩種寫法擇一，功能等價。
- [ ] **387.3 需要新增的 import。** `org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository`、`org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver`、`org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver`、`org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest`。
- [ ] **387.4 新增自動化測試（實作與測試同一支任務檔，不得省略）。** 比照既有 `bff/src/test/java/com/steven/assets/bff/config/PublicMarketAnalysisAndAdviceSecurityTest.java` 的既有樣式（`@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `@TestPropertySource(properties = {"business-services.url=http://localhost:1", "ADMIN_EMAIL=test@example.com"})` + `WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()`；不需要真的連 Google，`/oauth2/authorization/google` 本身只回 302 重導、不會對外發任何請求），在同一個 `bff/src/test/java/com/steven/assets/bff/config/` 目錄下新增一支新測試類（檔名比照目錄下既有測試類的命名慣例自訂，內容涵蓋下列三個情境）：
  - 測試一：`client.get().uri("/oauth2/authorization/google").exchange()` 斷言 `expectStatus().is3xxRedirection()`，並用 `expectHeader().value(org.springframework.http.HttpHeaders.LOCATION, location -> org.assertj.core.api.Assertions.assertThat(location).contains("prompt=select_account"))` 確認 `Location` header 的查詢字串含 `prompt=select_account`。
  - 測試二（既有參數不受影響）：同一次 `exchange()` 的 `Location` 值，額外斷言仍包含 `response_type=code`、`client_id=`、`scope=`、`redirect_uri=`（`redirect_uri` 在無 `X-Forwarded-*` header 的預設情況下應為 `redirect_uri=http` 開頭），確認自訂 resolver 沒有覆寫掉這些既有欄位。
  - 測試三（https 場景下 `redirect_uri` 仍正確且同樣帶 `prompt`）：`client.get().uri("/oauth2/authorization/google").header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "localhost").exchange()`，斷言 `Location` 同時包含 `redirect_uri=https%3A%2F%2Flocalhost%2Flogin%2Foauth2%2Fcode%2Fgoogle`（或未編碼的 `redirect_uri=https://localhost/login/oauth2/code/google`，依實際輸出擇一斷言，二者選其一即可，不必兩者都斷言）與 `prompt=select_account`。

- [ ] **387.5 不在本次範圍。** 不新增／修改 `redirect_uri`、`scope`、`client_id`、`state`、`nonce` 既有邏輯；不改變 Google Console 端「已授權的重新導向 URI」設定（那是使用者在 Google Cloud Console 自行維護的外部設定，不受此變更影響，`https://localhost/login/oauth2/code/google` 已由使用者自行登記，本任務不涉及）；不改變登入成功後的 upsert／PENDING／ADMIN／302／session cookie／代看 cookie 清除等既有行為；不新增前端程式碼（前端「使用 Google 登入」按鈕本來就是導向既有的 `/oauth2/authorization/google`，不需要改動）；不修改 `SESSION_COOKIE_SECURE`、HSTS 或憑證相關設定（這些與本任務的帳號選擇器需求無關，屬於另一個獨立問題）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

跑起來真的有這個功能（本專案沒有 dev server，改好的定義是 image rebuild + container recreate）：

```bash
docker compose -p asset-management build --no-cache bff
docker compose -p asset-management up -d --no-deps --force-recreate bff
sleep 5
curl -s -D - -o /dev/null "http://localhost/oauth2/authorization/google" | grep -i '^location:'
```

`location` 標頭的查詢字串必須包含 `prompt=select_account`；同時確認 `redirect_uri`、`client_id`、`scope` 等既有參數與修復前逐字相同（沒有被自訂 resolver 意外覆寫掉）。再對 `https://localhost/oauth2/authorization/google`（`curl -sk`）重跑一次同樣的驗證，確認 https 場景下 `redirect_uri` 仍正確產生 `https://localhost/login/oauth2/code/google` 且同樣帶有 `prompt=select_account`。

## 完成報告

**實際改了哪些檔：**

- `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`——新增
  `authorizationRequestResolver(ReactiveClientRegistrationRepository)` bean（387.1）、
  接進 `oauth2Login`（387.2）、新增四個 import（387.3）。
- `bff/src/test/java/com/steven/assets/bff/config/OAuth2LoginAccountChooserSecurityTest.java`
  （新增檔）——387.4 的三個測試情境。
- 本檔（`spec/tasks/t387_google_login_account_chooser.md`）——回填完成報告。

**與原計畫的偏差及原因：**

- 387.2 的兩種等價寫法中，前一位 implementer 實際採用的是「讓 Spring 注入」那一種：
  `springSecurityFilterChain(ServerHttpSecurity http, ServerOAuth2AuthorizationRequestResolver authorizationRequestResolver)`
  方法簽章直接宣告 `ServerOAuth2AuthorizationRequestResolver` 參數，由 Spring 容器注入
  387.1 定義的 bean，而非在 `.oauth2Login(...)` 內直接呼叫
  `authorizationRequestResolver(clientRegistrationRepository)` 方法。任務檔原文兩種寫法
  「功能等價、擇一即可」，故不視為偏差，僅記錄實際選用哪一種，供之後對照 diff。
- 其餘（387.1／387.3／387.5）均照任務檔原文實作，無偏差。
- 本次僅執行 `mvn test`（387.4 要求的自動化測試），未執行「驗證」段落的
  `docker compose build/up` + `curl` 人工驗證步驟——該段落超出本次分派範圍
  （387.4 與收尾），由後續收尾流程（`/run-stack`／`/commit-merge-push`）處理。

**mvn test 實際輸出摘要：**

```
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
EXIT_CODE=0
```

`target/surefire-reports/` 彙總（全模組）：225 個測試，Failures: 0，Errors: 0，Skipped: 0。

新測試類單獨結果：

```
Test set: com.steven.assets.bff.config.OAuth2LoginAccountChooserSecurityTest
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s
```

三個測試（`觸發Google登入時Location帶promptSelectAccount`、
`既有AuthorizationRequest參數不受影響`、`https場景下redirectUri正確且仍帶promptSelectAccount`）
與既有全部測試同批全綠，無需重試。
