# [t338] 今日股市分析與資產配置建議的 Docker 外部唯讀 API（Nginx 9090 第七、八條路由）

**對應 Requirements:** Requirement 79（今日股市分析與資產配置建議的 Docker 外部唯讀 API，Nginx 9090 第七、八條路由）
**前置任務:** 無（與 t336 可並行；t336 改的是分析怎麼產生，本任務改的是怎麼被外部讀取，兩者不重疊）
**Liquibase changeset:** 無

---

## 背景

本專案的 Docker 外部 HTTP 入口只有一個：non-root Nginx `api-gateway` 綁 `127.0.0.1:9090`。`bff` 與 `external-materials-service` 都**不映射 host port**，`frontend:80` 對這些路徑一律回 404（第二道防線）。遠端只經 Tailscale Serve 的 path-scoped HTTPS `:9090`，禁止 Funnel、禁止 root／`/api/` proxy。

目前 allowlist 有**六條**：

| # | Method | Path | Upstream | Requirement |
|---|---|---|---|---|
| 1 | GET | `/api/quotes` | `external-materials-service:8080` | 66 |
| 2 | GET | `/api/quotes/one` | `external-materials-service:8080` | 66 |
| 3 | GET | `/api/public/market-index` | `bff:8080` | 67 |
| 4 | GET | `/api/assets/latest` | `bff:8080` | 68 |
| 5 | GET | `/api/public/exchange-rate/usd-twd` | `bff:8080` | 70 |
| 6 | **POST** | `/api/public/crawler-data/rescan` | `bff:8080` | 71（唯一有副作用者） |

本任務新增**第七、八條，都是唯讀 GET**，加完為八條（七條唯讀 GET ＋ 一條寫入 POST）。

### 這兩條落在既有邊界之內

與 Requirement 71 的第六條不同（那條明文修訂了「純唯讀」邊界），本任務的兩條**純唯讀、零副作用**。9090 gateway 的所有既有規則原樣適用，本任務不修訂任何邊界。

### ⚠ 風險等同性聲明（明示，不得因有先例就略過）

`/api/public/portfolio-advice/latest` 回傳的是**主要管理者的個人化資產配置建議**（建議配置比例、與現有部位落差的敘述）。其暴露等級與既有第 4 條 `/api/assets/latest`（已將全部資產金額匿名放行）**相同或更低**——後者外洩實際金額，本條外洩配置建議文字。既有先例已接受此等級的暴露。

**實際的保護只有兩層**：9090 只綁 `127.0.0.1`、遠端只經 Tailscale 私網 identity。**路由本身無任何鑑權。**

---

## 要做什麼

### 兩條路由的結構差異（決定實作方式，務必先讀）

| | 第七條 `/api/public/market-analysis/today` | 第八條 `/api/public/portfolio-advice/latest` |
|---|---|---|
| 資料 owner 屬性 | **全域參考**（`daily_market_analysis` 無 `owner_user_id`、不受 `TenantFilterAspect` 過濾） | **owner-scoped** |
| owner 解析 | 不需要 | **必須**走 configured-admin bootstrap |
| 上游 business 端點 | 既有 `GET /api/market-analysis/today`（`MarketAnalysisController:43`） | 既有 `GET /api/portfolio-advice/latest`（`PortfolioAdviceController:46`） |
| 需新增 business 端點 | **無** | **無** |

- [x] **338.1 BFF：第七條 `/api/public/market-analysis/today`**

    新增三個檔案於 `bff/src/main/java/com/steven/assets/bff/todaymarketanalysis/`（**與該頁面既有的 `TodayMarketAnalysisBffController` 同 package**——既有四組 sibling 中有三組採此慣例：`exchangerate/`、`gdptwse/`、`crawlerdata/` 各自都是「頁面 BFF controller ＋ Public controller」同 package；只有 `latestassets/` 因為沒有對應頁面 controller 才獨立。第八條同理放進既有的 `portfolioadvice/`）：

    - `PublicMarketAnalysisController.java` — `@RestController` ＋ `@RequestMapping("/api/public/market-analysis/today")` ＋ `@GetMapping`，**只委派 Service**
    - `PublicMarketAnalysisService.java` — 注入既有 `businessServicesClient`（`WebClient` bean），呼叫 business `GET /api/market-analysis/today`。**取用方式寫死為 `.retrieve().bodyToMono(...)`**（與 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanService.java:30-33` 同型）。**不得用 `exchangeToMono` 的 byte-relay**——本專案這兩種寫法互斥：byte-relay（`LatestAssetsPublicService.java:44-45`）在非 2xx 時**不擲例外**，body 會原樣送到匿名呼叫者手上，導致 338.3 要求的 scoped exception advice 的 `WebClientResponseException` handler 永遠不會被觸發（形同死碼），且與 338.3「非 2xx 一律消毒成固定文案」直接衝突，338.9(c) 的跨 advice 競爭測試也會變成測不到真實路徑的假驗證。「原樣 relay」在第七條僅限縮為 **2xx 時 body 內容不改寫**
    - `PublicMarketAnalysisExceptionAdvice.java` — 見 338.3

    **不得把此方法加進既有 `TodayMarketAnalysisBffController`。** 該類別的 class-level `@RequestMapping("/api/bff/today-market-analysis")` 會與方法級路徑串接，**產生不出 `/api/public/...` 這個頂層路徑**。這是技術原因，不是風格選擇——既有四組 sibling（`PublicUsdTwdController`／`PublicMarketIndexController`／`LatestAssetsPublicController`／`PublicCrawlerRescanController`）全都各自獨立成類別，正是同一個原因。

    **Controller 不得持有 `WebClient` 或發起 HTTP 呼叫**（CLAUDE.md：「Controller 仍只委派 service，BFF 不直查 DB／外部行情」）。既有 `LatestAssetsPublicController` 的完整寫法可直接照抄結構：

    ```java
    @RestController
    @RequestMapping("/api/assets/latest")
    @RequiredArgsConstructor
    public class LatestAssetsPublicController {
        private final LatestAssetsPublicService service;

        @GetMapping
        public Mono<ResponseEntity<byte[]>> getLatest() {
            return service.getLatest();
        }
    }
    ```

    **不做 `onErrorReturn` 降級**——呼叫端需要看到真實失敗，不能被吞成 200 空物件。

- [x] **338.2 BFF：第八條 `/api/public/portfolio-advice/latest`（owner-scoped，務必逐項照做）**

    新增**四個**檔案於既有的 `bff/src/main/java/com/steven/assets/bff/portfolioadvice/`（該 package 已有 `PortfolioAdviceBffController.java`）：`PublicPortfolioAdviceController.java`、`PublicPortfolioAdviceService.java`、`PublicPortfolioAdviceExceptionAdvice.java`，以及**具名例外類** `PublicPortfolioAdviceUnavailableException.java`（比照既有 `bff/.../latestassets/LatestAssetsUnavailableException.java`，用來表達「主要管理者不可用」——Requirement 79 明文要求「以具名 exception 表達」，既有先例也是獨立檔案）。Controller 結構同 338.1。

    **Service 必須逐項沿用 `LatestAssetsPublicService` 的既有模式。** 該檔案完整內容如下（`bff/src/main/java/com/steven/assets/bff/latestassets/LatestAssetsPublicService.java`），四個關鍵步驟都不可省：

    ```java
    public Mono<ResponseEntity<byte[]>> getLatest() {
        return users.configuredAdmin()                                    // ① 解析唯一 configured admin
                .switchIfEmpty(Mono.error(new LatestAssetsUnavailableException("主要管理者尚未建立")))
                .flatMap(admin -> {
                    if (admin == null || admin.id() == null || !admin.configuredAdmin() || !admin.isActive()) {
                        return Mono.error(new LatestAssetsUnavailableException("主要管理者不可用"));  // ② 檢查可用性
                    }
                    return businessServicesClient.get()
                            .uri("/api/assets/latest")
                            .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))     // ③ 顯式帶三個 tenant header
                            .header(AuthConstants.HDR_USER_ROLE, admin.role())
                            .header(AuthConstants.HDR_USER_STATUS, admin.status())
                            .accept(MediaType.APPLICATION_JSON)
                            .exchangeToMono(response -> response.toEntity(byte[].class)
                                    .map(entity -> validateSuccessPayload(entity)))
                            .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));      // ④ 清除 Reactor context 身分
                });
    }
    ```

    **④ 是安全關鍵，遺漏即為缺陷。** 該檔案的既有註解已寫明理由：

    > 這支對外匿名 API 的 owner 僅能是 business 唯一解析出的 configured admin。shared WebClient 的 tenant filter 會讀取 Reactor context；若保留登入者／代看者身分，會覆寫下面明確指定的 header，讓公開資料錯指向呼叫者。只對這個 downstream publisher 清掉 context，bootstrap lookup 仍維持既有行為。

    本任務須有專屬測試模擬「context 中已有另一個登入者身分」，斷言下游收到的仍是 configured admin 的 header（見 338.9(d)）。

    `LatestAssetsPublicService` 另有的 `validateSuccessPayload`（驗證 snapshot identity 一致）是 latest-assets 專屬的 payload 語意，**本任務不需要**，原樣 relay 即可。

    **第八條的取用方式與第七條刻意不同，這不是疏漏：** 第八條的 downstream 呼叫**維持 `exchangeToMono` 的 byte-relay**（沿用 `LatestAssetsPublicService.java:44-45`——既有註解已載明「金融數值不可先 decode 成 Map/Double 再重編碼」）。因此第八條的 business 非 2xx 屬 **relay 範圍、不經 advice 消毒**；它的 advice 只負責 bootstrap lookup（`BusinessUserClient.configuredAdmin()`，該支用 `.retrieve()`，見 `bff/.../security/BusinessUserClient.java:57-66`）擲出的例外與「主要管理者不可用」的具名 exception。**兩條路由的錯誤契約因而不同，測試須分別寫，不得混為一談。**

    **絕不觸發 `POST /api/portfolio-advice/generate`**——那是有 LLM 成本的寫入操作。本任務只讀既有最新一筆；若尚無任何一筆，回傳 business 既有的「無資料」回應形狀，**不代為產生**。

- [x] **338.3 兩支 scoped exception advice，都必須明確宣告 `@Order(Ordered.HIGHEST_PRECEDENCE)`**

    `@RestControllerAdvice(assignableTypes = PublicMarketAnalysisController.class)` / `(... = PublicPortfolioAdviceController.class)`，比照既有四支 sibling advice 的命名。

    **`assignableTypes` 範圍窄不保證蓋過全域 `BusinessErrorAdvice`。** Requirement 71 已逐字記載此教訓：兩者對同一個 `WebClientResponseException` 各自宣告 handler 時，Spring 跨 `@ControllerAdvice` bean 解析同一例外型別依 `@Order`／bean 註冊順序決定，不是依 `assignableTypes` 範圍窄自動優先。既有**四支** sibling 中只有 `PublicCrawlerRescanExceptionAdvice`（`bff/.../crawlerdata/PublicCrawlerRescanExceptionAdvice.java:38`）宣告了 `@Order(Ordered.HIGHEST_PRECEDENCE)`，另三支（`PublicUsdTwd`／`PublicMarketIndex`／`LatestAssetsPublic`）皆未宣告。而 `bff/src/main/java/com/steven/assets/bff/common/BusinessErrorAdvice.java` 會把 business 非 2xx 的 body **原樣轉發**，business 端 `GlobalExceptionHandler` 對未分類例外會把 `ex.getMessage()`（可能含內部主機名、SQL 錯誤文字）塞進 `ProblemDetail.detail` 回 500。對已登入 ADMIN 端點是既有取捨，對**完全匿名**的本任務端點不可沿用。

    故兩支 advice 都要：
    - 明確宣告 `@Order(Ordered.HIGHEST_PRECEDENCE)`
    - 對 `WebClientResponseException`（business 非 2xx）與更廣義的 `WebClientException`（連線失敗、逾時等 transport 失敗）各回**固定文案**的安全 `ProblemDetail`（502／503），**不得帶出 business 原始 body 或例外訊息**

    **例外**：第八條的「主要管理者不可用」是合法的結構化訊號，比照 `LatestAssetsPublicExceptionAdvice.unavailable()`（`bff/.../latestassets/LatestAssetsPublicExceptionAdvice.java:13-18`，既有回 **`503 SERVICE_UNAVAILABLE`**）回**具名的 503**，不在消毒範圍。**不是 404**——該檔的 `downstream()`（`:28-34`）只做上游狀態原樣 relay、不處理此情境，不得引用為本條範本。

- [x] **338.4 `api-gateway/nginx.conf` 新增兩個 `location =`**

    現有檔案（`api-gateway/nginx.conf`）的既有唯讀 GET location 寫法如下，**逐字沿用**：

    ```nginx
    location = /api/public/market-index {
        add_header Allow $api_allow_header always;
        if ($request_method != GET) { return 405; }
        set $bff_upstream bff:8080;
        proxy_pass http://$bff_upstream$request_uri;
    }
    ```

    新增：

    ```nginx
    location = /api/public/market-analysis/today {
        add_header Allow $api_allow_header always;
        if ($request_method != GET) { return 405; }
        set $bff_upstream bff:8080;
        proxy_pass http://$bff_upstream$request_uri;
    }

    location = /api/public/portfolio-advice/latest {
        add_header Allow $api_allow_header always;
        if ($request_method != GET) { return 405; }
        set $bff_upstream bff:8080;
        proxy_pass http://$bff_upstream$request_uri;
    }
    ```

    - **重用既有 `map $request_method $api_allow_header`**（檔案第 29–32 行，`GET ""` / `default "GET"`），**不新增第三個 map**。第六條之所以需要 `$rescan_allow_header` 是因為它是 POST；本次兩條都是 GET。
    - **upstream 必須用變數 ＋ `resolver 127.0.0.11`**（檔案已在 server 區塊設定），**不得**寫死 `proxy_pass http://bff:8080$request_uri`——container recreate 換 IP 後會黏住舊位址。
    - 兩個 location 都放在既有 `location / { return 404; }` catch-all **之前**。
    - 非 GET 回 `405` 帶 `Allow: GET`；descendant（如 `/api/public/market-analysis/today/x`）落 catch-all 回 404。

- [x] **338.5 `bff/.../config/SecurityConfig.java` 兩條 `permitAll` 併入既有 GET 群組**

    現有寫法（第 79–82 行）：

    ```java
    // Requirements 67/68/70：只有三支 BFF 精確 GET 可由 api-gateway 匿名讀取；
    // quotes 由 Nginx 直接轉 external-materials，不在 BFF 放行。
    .pathMatchers(HttpMethod.GET,
            "/api/public/market-index",
            "/api/assets/latest",
            "/api/public/exchange-rate/usd-twd").permitAll()
    ```

    改為在同一個呼叫的參數列加入兩條新路徑（**不另開新的 `pathMatchers` 呼叫**——method 相同時既有寫法本就是多路徑並列），並更新該註解的 Requirements 清單為 `67/68/70/78`、把「三支」改為「五支」：

    ```java
    .pathMatchers(HttpMethod.GET,
            "/api/public/market-index",
            "/api/assets/latest",
            "/api/public/exchange-rate/usd-twd",
            "/api/public/market-analysis/today",
            "/api/public/portfolio-advice/latest").permitAll()
    ```

    同路徑其他 method、descendant，以及相鄰 `/api/bff/today-market-analysis/**`（既有 ADMIN 規則在第 90–99 行）與 `/api/bff/portfolio-advice/**`（第 103 行）**維持不變，不得放寬**。

- [x] **338.6 `frontend/nginx.conf` 二次防線：exact ＋ matrix 雙重**

    現有內容（第 54–65 行）：

    ```nginx
    # Docker 外部唯讀 API 只能從 loopback 9090 api-gateway 進入。
    location = /api/quotes { return 404; }
    location = /api/quotes/one { return 404; }
    location = /api/public/market-index { return 404; }
    location = /api/assets/latest { return 404; }
    location = /api/public/exchange-rate/usd-twd { return 404; }
    location = /api/public/crawler-data/rescan { return 404; }

    # 連每個 segment 帶 matrix parameter 的變體也擋下，避免 WebFlux 解析後命中 controller。
    location ~ "^/api(?:;[^/]*)?/(?:quotes(?:;[^/]*)?(?:/one(?:;[^/]*)?)?|public(?:;[^/]*)?/(?:market-index(?:;[^/]*)?|exchange-rate(?:;[^/]*)?/usd-twd(?:;[^/]*)?|crawler-data(?:;[^/]*)?/rescan(?:;[^/]*)?)|assets(?:;[^/]*)?/latest(?:;[^/]*)?)$" {
        return 404;
    }
    ```

    須：
    1. 新增兩條 exact `location = /api/public/market-analysis/today { return 404; }` 與 `location = /api/public/portfolio-advice/latest { return 404; }`
    2. 在**既有那條單一 anchored regex** 的 `public(?:;[^/]*)?/(...)` 分支內新增兩個替代分支：`market-analysis(?:;[^/]*)?/today(?:;[^/]*)?` 與 `portfolio-advice(?:;[^/]*)?/latest(?:;[^/]*)?`

    **不得另開一條獨立 regex**——維持「單一 anchored regex 涵蓋全部路由」的既有結構。須擋下的變體例如 `/api/public;x=1/market-analysis/today`、`/api/public/market-analysis;x=1/today`、`/api;x=1/public/portfolio-advice/latest`。

- [x] **338.7 `scripts/configure-tailscale-api-gateway.sh` 掛第七、八條**

    現有 `SERVE_PATHS` 陣列（第 5–11 行）：

    ```bash
    readonly -a SERVE_PATHS=(
      '/api/quotes'
      '/api/quotes/one'
      '/api/public/market-index'
      '/api/assets/latest'
      '/api/public/exchange-rate/usd-twd'
      '/api/public/crawler-data/rescan'
    )
    ```

    須：
    1. `SERVE_PATHS` 新增兩筆
    2. `validate_owned_config()` 內 Python 的 `expected` dict（第 143–148 行附近）新增兩筆，格式為 `"<path>": "http://127.0.0.1:9090<path>"`
    3. 兩條都是唯讀 GET 且預期 200，**直接沿用既有 `get_200()`**（第六條因方法是 POST 才需要專屬的 `get_405_post_only()`；本次不需要新函式）
    4. **第八條在「尚無任何一筆建議」時 business 仍回 HTTP 200**：`PortfolioAdviceController.latest()`（`backend/.../controller/PortfolioAdviceController.java:45-50`）在 `adviceService.latest()` 回 null 時回 `PortfolioAdviceDto.none()`（`status="NONE"` 的普通 DTO，HTTP 200 `application/json`），**不拋例外、不回非 200**。故 `get_200()` 天然通過，**不需要任何「無資料」旁路**。若該路徑回非 200，代表 configured-admin bootstrap 或路由本身有問題，應照既有規則整批 fail closed——**不得**為此新增接受非 200 的例外分支，那會侵蝕 Requirement 66 建立的「任一契約不健康即整批 fail closed」不變式
    5. preflight 通過與否比照既有「任一契約不健康即整批 fail closed、不 reset」規則，一併計入 `serve_before`／`serve_recheck` 兩次所有權比對範圍

    **另有三處 operator-facing 訊息寫死路由數**（Requirement 71 由「五條」改來時漏列了第三處），全部更新為「八條／八路」：
    - `:159` — `validate_owned_config()` 內 Python 例外訊息「必須精確只有本任務管理的**六條** path handler」
    - `:264` — 「現有 Serve 設定所有權與本機**六路** API preflight 通過…」（**Requirement 71 當初漏列的一處**）
    - `:292` — 腳本尾端 `die` 訊息「建立後的 Serve config 不是預期**六條** exact handler」

    `scripts/tests/configure-tailscale-api-gateway-test.sh` **兩處**須同步：
    - `:153` — 目前是 `[[ "$(grep -c '^serve ' …)" == 5 ]]`，**Task 329 新增第六條時漏改、與 `SERVE_PATHS` 的 6 筆已經不一致**，本次直接改為 `== 8`
    - `:162` — 「五路」改為「八路」

- [x] **338.8 文件同步（逐檔逐處，不得只改其中一份）**

    - **`CLAUDE.md`**
      - 第 237 行「BFF 與資料來源規範」第 1 節具名例外段落：現載「Requirements 66–68／70／71；Tasks 317、325、327、328、329」須併入 `78`／`79` 與 `337`／`338`；「**六條路由**（五條唯讀 GET ＋ 一條寫入 POST…）」改為「**八條路由**（七條唯讀 GET ＋ 一條寫入 POST…）」；BFF 匿名放行的三條 GET 列舉須補上新增兩條；「Frontend 對**六路** exact／matrix 變體回 404」改為「八路」
      - 第 247 行第 3 節標題「**五條唯讀 GET ＋ 一條寫入 POST**」改為「七條唯讀 GET ＋ 一條寫入 POST」
      - 第 248–256 行內文的路由列舉須補上第七、八條；「`frontend:80` 對上述**六條**回 404」與「Tailscale Serve 只以 path-scoped HTTPS `:9090` 掛相同**六條** exact path」皆改為八條
      - 「Spec 文件位置」表格的 Requirements 總數與 `spec/steering/structure.md:342` 的同一數字，**本次 spec 撰寫階段已同步為 79**（Requirement 78／78／79 一併新增）。實作時只需複驗 `grep -c "^### Requirement " spec/requirements.md` 的輸出與該二處一致，**不需要再改**；`spec/tasks.md` 索引範圍同理已為 `311–338`
    - **`spec/steering/structure.md`**
      - §3.2「BFF 設計鐵則」條目 2 的具名例外段落（現列 Requirements／Tasks 清單與匿名唯讀 exact GET 的列舉）
      - §4.4「Docker 外部 API Gateway」段落，第 248 行「它轉送**六條** exact route：五條唯讀 GET…」與第 252 行「Tailscale Serve 只掛相同**六條** exact path」
    - **`INSTALLATION.md`——四處仍停在「五條」，是 Requirement 71 落地時遺漏的既有漂移，本次一併補正為八條**：
      - `:480`「只有**五條**」
      - `:579`「確認上述**五條**本機 API 都健康後執行」
      - `:587`「驗證 Content-Type fail-closed 與**五路**成功流程」
      - `:589`「腳本只會建立**五條** path-scoped HTTPS `:9090`：quotes、quotes/one、market-index、…」——該處的路由列舉須補上第六、七、八條
    - **`docs/openapi/docker-external-api.yaml`——本專案唯一一份機器可讀的對外 API 契約，目前仍停在五條，連 Task 329 的第六條都沒補**：
      - `:6`（「只描述…開放的**五條**精確 GET 路徑」）、`:9`（「**五條**逐一路徑規則形成網路邊界」）、`:24`（「Tailscale 私有網路的**五條** path-scoped HTTPS 入口」）三處計數改為八條（七條唯讀 GET ＋ 一條寫入 POST）
      - `paths:`（`:26` 起，現有 `/api/quotes`:27、`/api/quotes/one`:76、`/api/public/market-index`:122、`/api/assets/latest`:234、`/api/public/exchange-rate/usd-twd`:284）須補上**三個**缺漏條目與各自 response schema：`POST /api/public/crawler-data/rescan`（Task 329 遺漏）、`GET /api/public/market-analysis/today`、`GET /api/public/portfolio-advice/latest`
      - 漏掉這一份，本節「不得只改程式碼」即為空話
    - **`spec/steering/tech.md` 三處**：`:11`（「SPA；**五條** external-only API 明確回 404」）、`:12`（「Docker 外部**五條** exact GET 唯一入口」）、`:27`（「Tailscale ──► HTTPS :9090（僅**五條** exact path）」）。該檔與 `structure.md` 同屬 `spec/steering/` 長期 context、敘述幾乎重複，只改一份即製造新漂移
    - **`scripts/README.md` 兩處**：`:8`（「**五路**本機 API 的 status／Content-Type／payload preflight…才設定**五條** path-scoped Tailscale HTTPS handler」）、`:9`（「並驗正常路徑只設定**五條** handler」）
    - **任務索引範圍共三處具名位置，缺一不可**：`spec/tasks.md:32`、`spec/tasks/README.md:7`、**`spec/steering/structure.md` 目錄樹裡的 `tasks.md` 那一行**（撰寫當下 `:364`；與同檔含「個 Requirements（User Story + AC）」的計數行相隔兩行，最容易只改一行漏另一行）
    - **Requirement 66／68／70 與 `spec/design.md` 內既有的「實際 handler 集合為六條／精確為六路」callout，在第七、八條落地後全部成為錯誤斷言，須逐一更正為八條／八路**：`spec/requirements.md:2625`、`:2660`、`:2705`、`:2716`，以及 `spec/design.md` 內含「Serve status精確只有五路」與「五路正向皆精確200」的那兩行（撰寫當下為 `:6991`／`:6992`——**行號會隨其他 worktree 併版漂移，實作時請以這兩個字串 grep 定位，不要照行號跳**）

    **不得**只改程式碼、留著任何一份文件描述舊有的六條白名單。

- [x] **338.9 測試**

    既有可照抄的最新範本是 `bff/src/test/java/com/steven/assets/bff/crawlerdata/` 目錄下那四支公開 rescan 端點的測試（controller、service、security、exception advice 各一支，檔名皆以 `PublicCrawlerRescan` 開頭），以及 `bff/src/test/java/com/steven/assets/bff/config/LatestAssetsConfiguredAdminContextTest.java`。至少涵蓋：

    - (a) 兩支 controller **只委派**各自 Service、controller 本身零 `WebClient` 互動（以替身斷言）
    - (b) 匿名（無 session）呼叫兩條路徑回 200；business 端非 2xx 或連線失敗時 BFF **不吞錯誤**（不得回退成 200 空物件）。**兩條的 body 斷言方式不同**：第七條走 `.retrieve().bodyToMono(...)`（會 decode／re-encode，key 順序與數字格式不保證逐位元相同），故只能斷言 **2xx 時 JSON 語意等價（逐欄比對）**，寫成 byte 級斷言會假失敗；第八條走 byte-relay，才用 byte 級斷言
    - (c) **兩支 exception advice 真的蓋過全域 `BusinessErrorAdvice`**——`WebTestClient` 必須**同時**註冊 scoped advice 與 `BusinessErrorAdvice`（例如 `.controllerAdvice(new PublicMarketAnalysisExceptionAdvice(), new BusinessErrorAdvice())`），注入帶特定內部訊息字串的假例外，斷言該字串**不出現**在回應 body 裡。**不得照抄既有 sibling「只註冊自己那支」的組裝方式**——那測不到跨 advice 競爭，會給假陽性（Requirement 71 已載明此教訓）
    - (d) `PublicPortfolioAdviceService` 顯式帶三個 tenant header，且**確實清除 Reactor context 身分**——須模擬 context 中已有另一個登入者身分（比照 `LatestAssetsConfiguredAdminContextTest` 既有手法），斷言下游收到的仍是 configured admin 的 header 而非該登入者
    - (e) configured admin 不存在／非 active 時回**具名的 503**（沿用 `LatestAssetsPublicExceptionAdvice.unavailable()` 的既有狀態碼 `SERVICE_UNAVAILABLE`），**不回 500、也不回 404**
    - (f) `SecurityConfig`：兩條路徑的 POST/PUT/PATCH/DELETE 回 401；descendant 與相鄰 `/api/bff/today-market-analysis/**`、`/api/bff/portfolio-advice/**` 的 ADMIN 規則不受影響
    - (g) Nginx：本機 9090 對兩條路徑 GET 200 命中 `bff:8080`、非 GET 回 405 帶 `Allow: GET`、descendant 回 404
    - (h) frontend port 80 對兩條路徑之 exact 與**至少兩種 matrix 變體**均回 404

- [x] **338.10 不新增排程、不新增資料表、不新增 changeset**

    兩條路由都只讀既有資料。不新增 `@Scheduled`，`SchedulePublicBffController.JOBS` 筆數不變，`SchedulePublicBffControllerTest` 的 `hasSize` 斷言不需更動。

---

## 驗證

```bash
# 1. BFF 測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 重建（JVM service 一律 --no-cache；先把主 repo 的 .env 複製進 worktree，
#    否則 compose 因 env_file 相對路徑解析失敗）
cp /Users/steven/Project/asset-management/.env . 2>/dev/null; \
docker compose -p asset-management build --no-cache bff && \
docker compose -p asset-management build api-gateway frontend
```

```bash
# 3. recreate
docker compose -p asset-management up -d --no-deps --force-recreate bff api-gateway frontend
```

```bash
# 4. 第七條：GET 應 200
curl -s -o /dev/null -w "market-analysis GET=%{http_code}\n" \
  http://127.0.0.1:9090/api/public/market-analysis/today
```

```bash
# 5. 第八條：GET 應 200（或在尚無建議時回 business 既有的無資料形狀）
curl -s -o /dev/null -w "portfolio-advice GET=%{http_code}\n" \
  http://127.0.0.1:9090/api/public/portfolio-advice/latest
```

```bash
# 6. 非 GET 應 405 且帶 Allow: GET
curl -s -X POST -D - -o /dev/null http://127.0.0.1:9090/api/public/market-analysis/today | head -5
```

```bash
# 7. descendant 應 404
curl -s -o /dev/null -w "descendant=%{http_code}\n" \
  http://127.0.0.1:9090/api/public/market-analysis/today/extra
```

```bash
# 8. frontend port 80 二次防線：exact 與 matrix 變體皆應 404
curl -s -o /dev/null -w "fe-exact=%{http_code}\n" http://localhost/api/public/market-analysis/today
curl -s -o /dev/null -w "fe-matrix=%{http_code}\n" "http://localhost/api/public;x=1/portfolio-advice/latest"
```

```bash
# 9. host 8080/8082 依舊無 listener（延續 Requirement 66 既有驗證）
lsof -nP -iTCP:8080 -sTCP:LISTEN; lsof -nP -iTCP:8082 -sTCP:LISTEN; echo "（兩者皆應無輸出）"
```

```bash
# 10. 只有 api-gateway 綁 host port
docker compose -p asset-management ps --format '{{.Service}} {{.Ports}}'
```

---

## 完成報告

### 新增檔案（7 支 main ＋ 7 支 test）

**BFF main**
- `bff/src/main/java/com/steven/assets/bff/todaymarketanalysis/PublicMarketAnalysisController.java`
- `bff/src/main/java/com/steven/assets/bff/todaymarketanalysis/PublicMarketAnalysisService.java`（`.retrieve().bodyToMono(MAP)`）
- `bff/src/main/java/com/steven/assets/bff/todaymarketanalysis/PublicMarketAnalysisExceptionAdvice.java`（`@Order(HIGHEST_PRECEDENCE)`）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceController.java`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceService.java`（configured-admin bootstrap ＋ 三個顯式 tenant header ＋ `exchangeToMono` byte-relay ＋ `contextWrite(ctx -> ctx.delete(CTX_IDENTITY))`）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceExceptionAdvice.java`（`@Order(HIGHEST_PRECEDENCE)`；unavailable→具名 503、bootstrap 非 2xx→502、transport→503）
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/PublicPortfolioAdviceUnavailableException.java`

**BFF test**
- `todaymarketanalysis/PublicMarketAnalysisServiceTest.java`（3）
- `todaymarketanalysis/PublicMarketAnalysisControllerTest.java`（4；2xx 只做逐欄語意等價）
- `todaymarketanalysis/PublicMarketAnalysisExceptionAdviceTest.java`（3；與 `BusinessErrorAdvice` 同場競爭）
- `portfolioadvice/PublicPortfolioAdviceControllerTest.java`（4；byte 級斷言）
- `portfolioadvice/PublicPortfolioAdviceExceptionAdviceTest.java`（4；含具名 503）
- `config/PublicPortfolioAdviceConfiguredAdminContextTest.java`（7；含「context 已有另一登入者／代看者」的 header 隔離）
- `config/PublicMarketAnalysisAndAdviceSecurityTest.java`（3）

### 修改檔案

- `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`：既有 GET `permitAll` 群組加兩條路徑，註解改為 `67/68/70/78`、「三支」→「五支」
- `api-gateway/nginx.conf`：新增兩個 `location =`（重用 `$api_allow_header`、變數 upstream ＋ resolver，置於 catch-all 之前）
- `frontend/nginx.conf`：新增兩條 exact `404`；在既有單一 anchored regex 的 `public/` 分支內加兩個替代分支
- `scripts/configure-tailscale-api-gateway.sh`：`SERVE_PATHS` ＋2、`expected` dict ＋2、兩支新 `get_200()` preflight、三處 operator 訊息改八條／八路
- `scripts/tests/configure-tailscale-api-gateway-test.sh`：`== 5` → `== 8`、「五路」→「八路」，**另修好假 curl／假 tailscale 的 mock**（見偏差 1）
- 文件：`CLAUDE.md`（2 段）、`spec/steering/structure.md`（§3.2、§4.4）、`spec/steering/tech.md`（3 處）、`scripts/README.md`（2 處）、`INSTALLATION.md`（5 處）、`docs/openapi/docker-external-api.yaml`（3 處計數 ＋ 補齊第六、七、八條 path 與 8 個新 schema）、`spec/requirements.md`（`:2625`／`:2660`／`:2705`／`:2716` 四處 callout）、`spec/design.md`（「五路正向皆精確200」「Serve status精確只有五路」兩行）

### 驗證輸出

```
mvn -f bff/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
  baseline（動工前）：Tests run: 92,  Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS
  完成後：            Tests run: 120, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS（+28）

bash scripts/tests/configure-tailscale-api-gateway-test.sh
  → PASS: 八路 preflight Content-Type／reset fail-closed regression（exit 0）
  （動工前以 HEAD 版本重跑同一測試為 exit 1，見偏差 1）

ruby -ryaml：docs/openapi/docker-external-api.yaml 可解析，paths = 8，$ref 全部可解析、無孤兒 schema
```

驗證段第 2–10 步（Docker 重建、9090／port 80 實機 curl、port 綁定盤點）由主 agent 於 stack 重建時統一執行，本任務未跑。

### 與原計畫的偏差

1. **（超出 338.7 所列兩處）`scripts/tests/configure-tailscale-api-gateway-test.sh` 的 mock 一併修好。**
   任務檔只要求改 `:153` 的 `== 5` → `== 8` 與 `:162` 的文案。但實測該測試在**動工前的 HEAD 就已經是 exit 1**：Task 329 新增第六條時沒有同步更新假 curl（收到 `/api/public/crawler-data/rescan` 會走 `unexpected URL` exit 2）與假 tailscale 的 `serve status --json`（只回 5 個 handler，過不了 `exact` 驗證）。只改計數會留下一個「斷言 8 但根本跑不起來」的測試，等同沒有測試。故一併補上三個 curl mock 分支（rescan 回 `405` ＋ `Allow: POST`、market-analysis 與 portfolio-advice 回 `200 application/json`）與 8 handler 的 serve status JSON。
2. **（超出 338.8 所列四處）`INSTALLATION.md` 第五處「為五支本機 API 各自保存 response headers/body」一併改為八支**，並補述第六條只以 GET 驗 `405`／`Allow: POST`、不發 POST。同段落內其餘四處都改了卻留這一處，會直接自相矛盾。
3. **（超出 338.8 明列）`docs/openapi/docker-external-api.yaml` 的 `info.description` 另修了「非 GET 一律 `Allow: GET`」這句。** 第六條是 POST-only、回 `Allow: POST`，該句自 Task 329 起即為錯誤斷言；補齊第六條 path 卻留著這句會前後打架。`info.title` 仍為「Docker 外部唯讀 API」未動（它不含計數，且屬文件識別名）。
4. **第七條未加 `contextWrite(ctx -> ctx.delete(CTX_IDENTITY))`**，與任務檔一致：`daily_market_analysis` 無 owner、不受 `TenantFilterAspect` 過濾，帶不帶 tenant header 都取到同一筆全域資料。已在 `PublicMarketAnalysisServiceTest` 以斷言固定「服務層不自行帶入任何身分 header」這個契約差異。
5. **338.9(g)(h) 無對應的自動化測試檔可擴充**（全樹沒有任何靜態檢查 `api-gateway/nginx.conf`／`frontend/nginx.conf` 內容的測試），故這兩項仍屬 Docker 實機驗證，落在上述第 2–10 步。
