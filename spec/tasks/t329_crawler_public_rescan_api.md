# [t329] 「爬蟲資訊查詢」頁公開觸發重新搜尋 API（Nginx 9090 第六條路由）

**對應 Requirements:** Requirement 71（新增 `POST /api/public/crawler-data/rescan`：Requirement 66 五條唯讀 GET 之外的第六條 Nginx 9090 路由，免登入觸發 NewsPoller「立即抓取並匯出」的同一段程式碼，語意等同 Requirement 63 既有 ADMIN 端點 `fetch-and-run-now` 的匿名版本，以 30 秒全域 Redis 冷卻節流防止被連續觸發打爆對外新聞來源）
**前置任務:** t328（建立 Nginx 9090 gateway 骨架、五條 exact allowlist、Tailscale Serve script 基礎）
**Liquibase changeset:** 無（本任務不新增資料表欄位，冷卻鍵只存在 Redis，TTL 到期即消失）

## 背景

「爬蟲資訊查詢」頁（`frontend/src/views/CrawlerDataView.vue`）目前有兩顆 ADMIN 限定的手動觸發按鈕（Requirement 63 / Task 280）：

- 「立即匯出」→ `POST /api/bff/crawler-data/export/run-now` → business `POST /api/crawler-export-path/run-now?crawler=news-poller` → ext `POST /internal/news-poller/export-now`：**只重產檔案**，不抓取。
- 「立即抓取並匯出」→ `POST /api/bff/crawler-data/export/fetch-and-run-now` → business `POST /api/crawler-export-path/fetch-and-run-now?crawler=news-poller` → ext `POST /internal/news-poller/fetch-and-export-now`：**完整跑一輪**——重新抓取全部既有來源（權威新聞、TWSE 三大法人／大盤成交、台幣兌美元與美股快照、韓股盤中、台股均線突破）→ 以 `dedupe_key` upsert 進 `news_headline` → 產出 `public_info_<今日>.json` ＋ `.xlsx` 兩份 → Drive 同步（啟用時）。

兩支都限 ADMIN（BFF `SecurityConfig` `hasAuthority(ADMIN)` ＋ business `CurrentUserContext.isAdmin()` 縱深防禦），且都只能在登入系統網頁後由前端呼叫。

同專案另有 5 條免登入、Docker host／Tailscale 私網可呼叫的唯讀 API，經獨立的 Nginx `api-gateway`（`127.0.0.1:9090`，Requirement 66 / Task 328）精確路由轉送：`GET /api/quotes`、`GET /api/quotes/one`、`GET /api/public/market-index`、`GET /api/assets/latest`、`GET /api/public/exchange-rate/usd-twd`。這五條的設計原則是「純唯讀、不觸發外部抓取、封閉式白名單」（Requirement 66 AC 第一條、`spec/requirements.md:2610`）。

**本任務要新增第六條**：`POST /api/public/crawler-data/rescan`——語意是「立即抓取並匯出」（`fetchAndExportNow()`）的**免登入版本**，讓 Docker host 工具或 Tailscale 私網的排程器能在既有 `crawler_schedule` 時間點之外主動觸發一次完整抓取，不必先登入系統網頁、也不必倚賴容器重啟觸發 warmup。**這是唯一打破「Requirement 66 純唯讀」邊界的例外**，屬於使用者已確認的刻意產品決策（見 Requirement 71 開頭 callout），不是既有唯讀契約的漂移。因為它有真實的外部抓取副作用且完全匿名，須以 30 秒全域 Redis 冷卻節流防止被連續觸發（無驗證機制、任何連得到 9090 的人都能呼叫，比照現有 `TradingRadarRefreshService`（Task 249）的 Redis 冷卻模式，但簡化為單一全域鍵——呼叫者匿名、沒有身分可做 per-owner 區分）。

## 要做什麼

### ext（external-materials-service）

- [x] 329.1 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/NewsPoller.java` 新增 `public ManualRunResult publicRescan()`，行為與現行 `fetchAndExportNow()`（約在第 260–274 行）**完全一致**——同樣先檢查 `enabled`（`news-scraper.enabled=false` 時回 `busyOrDisabled("DISABLED", MODE_FETCH_AND_EXPORT, "爬蟲已停用（news-scraper.enabled=false），未執行")`）、同樣以既有 `AtomicBoolean running`（第 105 行，與排程輪／warmup／兩顆既有 ADMIN 按鈕共用同一把鎖）`compareAndSet(false, true)` 取得互斥鎖（取不到回 `busyOrDisabled("BUSY", MODE_FETCH_AND_EXPORT, "上一輪抓取尚未結束，本次未啟動；請稍候再試")`）、同樣在 `finally` 釋放旗標。**唯一差異**：呼叫 `run("public-rescan")`（而非 `run("manual")`）。`mode` 沿用既有常數 `MODE_FETCH_AND_EXPORT`（代表執行的動作是完整跑一輪，與既有按鈕語意相同；`trigger` 才是區分呼叫來源的欄位）。**不得**複製 `run(String trigger)`／`exportPublicInfoJson(String trigger)` 的第二份實作——這兩個既有 private 方法已接受 `trigger` 參數，直接複用。
- [x] 329.2 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java` 新增映射（緊鄰既有 `/news-poller/export-now`、`/news-poller/fetch-and-export-now` 兩個映射，約第 124、140 行附近）：
  ```java
  @PostMapping("/news-poller/public-rescan")
  public com.steven.assets.externalmaterials.service.NewsPoller.ManualRunResult publicRescanNews() {
      return newsPoller.publicRescan();
  }
  ```
  此端點與既有 `/internal/*` 系列一致，**只能經 docker network 呼叫**，不映射 host port、不經 Nginx 9090 直接暴露（Nginx 只轉送到 BFF／business 這一層公開端點，不得讓 gateway 直連 `external-materials-service` 的 `/internal/*`）。

### business（backend）

- [x] 329.3 `backend/src/main/java/com/steven/assets/service/CrawlerExportPathService.java`：
  - 類別加 `@Slf4j`（Lombok，目前未使用，需新增 import `lombok.extern.slf4j.Slf4j`）。
  - 建構子新增相依 `org.springframework.data.redis.core.StringRedisTemplate redis`（backend 既有 Bean，`TradingRadarRefreshService` 已注入同一顆，不需新增設定）。
  - 新增常數：
    ```java
    private static final long PUBLIC_RESCAN_COOLDOWN_SECONDS = 30;
    private static final String PUBLIC_RESCAN_COOLDOWN_KEY = "crawler:news-poller:public-rescan:cooldown";
    ```
  - 新增方法：
    ```java
    /**
     * 公開觸發「重新搜尋」（Requirement 71）：免登入版「立即抓取並匯出」，供 Nginx 9090 gateway
     * 對外／Tailscale 呼叫。全域 30 秒冷卻（單一鍵，呼叫者匿名無法做 per-owner 區分）；
     * proxy 目標固定為 news-poller，不接受 crawler 參數。
     */
    public CrawlerExportPathDto.RunNowResponse publicRescan() {
        if (!acquirePublicRescanCooldown()) {
            return new CrawlerExportPathDto.RunNowResponse(
                    "COOLDOWN", null, null, null, null, null, null, null, null, null, null, null,
                    "冷卻中，請 " + PUBLIC_RESCAN_COOLDOWN_SECONDS + " 秒後再試");
        }
        CrawlerExportPathDto.RunNowResponse result = proxyManualRun(
                CrawlerSchedule.CRAWLER_NEWS_POLLER, "/internal/news-poller/public-rescan", "重新搜尋");
        String status = result.status();
        if ("BUSY".equals(status) || "DISABLED".equals(status) || "ERROR".equals(status)) {
            // 這三種結果代表本次呼叫沒有真的促成一輪對外抓取（或根本沒連到 ext），
            // 不強迫下一個匿名呼叫端等滿 30 秒——比照 TradingRadarRefreshService「沒真的抓，不燒冷卻」。
            redis.delete(PUBLIC_RESCAN_COOLDOWN_KEY);
        } else {
            // OK／FAILED／RUNNING：proxyManualRun 可阻塞至 runNowTimeoutSeconds（預設 50 秒），
            // 呼叫前設下的舊 30 秒 TTL 可能已在等待期間自然到期——這裡必須用 SET（不是 EXPIRE）
            // 從「呼叫已返回」的當下重新起算一個全新 30 秒窗口，EXPIRE 對已過期、不存在的 key 無效，
            // 無法重建。詳見 Requirement 71 對應 AC 與 design.md 的完整理由（含明確接受的殘餘落差）。
            try {
                redis.opsForValue().set(PUBLIC_RESCAN_COOLDOWN_KEY, "1",
                        java.time.Duration.ofSeconds(PUBLIC_RESCAN_COOLDOWN_SECONDS));
            } catch (Exception e) {
                log.warn("爬蟲公開重新搜尋冷卻鍵重新起算失敗（不影響本次呼叫結果）：{}", e.toString());
            }
        }
        return result;
    }

    /** Redis 例外時 fail-open（視為取得鎖），不因 Redis 抖動就永遠擋住這個公開入口。 */
    private boolean acquirePublicRescanCooldown() {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
                    PUBLIC_RESCAN_COOLDOWN_KEY, "1", java.time.Duration.ofSeconds(PUBLIC_RESCAN_COOLDOWN_SECONDS)));
        } catch (Exception e) {
            log.warn("爬蟲公開重新搜尋冷卻閘門讀寫失敗，本次放行：{}", e.toString());
            return true;
        }
    }
    ```
  - `proxyManualRun` 方法簽章與既有邏輯**不修改**（其對 `crawlerKey` 的既有校驗——只允許 `CrawlerSchedule.CRAWLER_NEWS_POLLER`——由本方法傳入常數值自動滿足，不需改動該方法本身）。
- [x] 329.4 `backend/src/main/java/com/steven/assets/controller/CrawlerExportPathController.java` 新增（緊鄰既有 `runNow`／`fetchAndRunNow` 兩個方法之後）：
  ```java
  /**
   * 公開觸發「重新搜尋」（Requirement 71）：免登入版「立即抓取並匯出」。
   *
   * <p>不接受 {@code crawler} 參數（固定 news-poller）、<b>不檢查 {@link CurrentUserContext#isAdmin()}</b>——
   * 這是本端點相對既有兩支 ADMIN 端點的唯一差異，由 {@link CrawlerExportPathService#publicRescan()}
   * 內建的全域 30 秒 Redis 冷卻取代 ADMIN 驗證作為濫用防護。
   */
  @PostMapping("/public-rescan")
  public CrawlerExportPathDto.RunNowResponse publicRescan() {
      return service.publicRescan();
  }
  ```
  `CrawlerExportPathDto.RunNowResponse` 的 `status` 欄位不需新增列舉型別（既有為 `String`），但其文件（該類別頂端 Javadoc）須補上第七種值 `COOLDOWN`（「全域冷卻中，未呼叫 ext，本次未啟動」），與既有 `OK`／`FAILED`／`BUSY`／`RUNNING`／`DISABLED`／`ERROR` 並列。

### bff

- [x] 329.5 新檔 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanService.java`（Controller **不得**直接持有 `WebClient`——CLAUDE.md「Controller 仍只委派 service，BFF 不直查 DB／外部行情」；三支既有 sibling `PublicUsdTwdController`／`PublicMarketIndexController`／`LatestAssetsPublicController` 皆委派各自 Service、無一在 controller 內直接發 HTTP 呼叫，本端點沿用同一結構）：
  ```java
  package com.steven.assets.bff.crawlerdata;

  import lombok.RequiredArgsConstructor;
  import org.springframework.core.ParameterizedTypeReference;
  import org.springframework.stereotype.Service;
  import org.springframework.web.reactive.function.client.WebClient;
  import reactor.core.publisher.Mono;

  import java.util.Map;

  /**
   * 公開觸發「重新搜尋」（Requirement 71）：Docker host／Tailscale 免登入可呼叫的第六條 Nginx 9090 路由，
   * 語意等同既有 ADMIN 限定「立即抓取並匯出」（{@link CrawlerDataBffController#fetchAndRunExportNow()}）
   * 的匿名版本，由 business 端 30 秒全域 Redis 冷卻節流。
   *
   * <p>刻意不做 {@code onErrorReturn} 降級：呼叫端需要看到 business 端真實失敗，不能被吞成空物件
   * （同 Requirement 63 對兩支既有 ADMIN 端點的既有理由）。business 例外由
   * {@link PublicCrawlerRescanExceptionAdvice} 消毒，不在這裡處理。
   */
  @Service
  @RequiredArgsConstructor
  public class PublicCrawlerRescanService {

      private static final ParameterizedTypeReference<Map<String, Object>> MAP =
              new ParameterizedTypeReference<>() { };

      private final WebClient businessServicesClient;

      public Mono<Map<String, Object>> rescan() {
          return businessServicesClient.post()
                  .uri("/api/crawler-export-path/public-rescan")
                  .retrieve()
                  .bodyToMono(MAP);
      }
  }
  ```
  `businessServicesClient` 與 `bff/src/main/java/com/steven/assets/bff/crawlerdata/CrawlerDataBffController.java` 注入的是同一顆既有 Bean（同一 `@Component` WebClient，不新增設定）。`MAP` 常數**必須**用 `ParameterizedTypeReference`（`CrawlerDataBffController.java:39-40` 現有 `runExportNow()`／`fetchAndRunExportNow()` 共用的 `MAP` 常數正是這個寫法——**不是** unchecked cast，全樹搜尋 `(Class<Map<String, Object>>) (Class<?>)` 完全零命中，不得使用該寫法）。
- [x] 329.5b 新檔 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanController.java`：
  ```java
  package com.steven.assets.bff.crawlerdata;

  import lombok.RequiredArgsConstructor;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.PostMapping;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;
  import reactor.core.publisher.Mono;

  import java.util.Map;

  /**
   * 公開觸發「重新搜尋」（Requirement 71）：只委派 {@link PublicCrawlerRescanService}，
   * 不直接持有 WebClient 或發起 HTTP 呼叫。
   *
   * <p>獨立成類別、不併入 {@link CrawlerDataBffController}：後者 class-level
   * {@code @RequestMapping("/api/bff/crawler-data")} 會與方法級路徑相串接，無法產生
   * {@code /api/public/...} 這個頂層路徑（比照既有 {@code PublicUsdTwdController}／
   * {@code PublicMarketIndexController} 的同一結構性原因，非風格選擇）。
   */
  @RestController
  @RequestMapping("/api/public/crawler-data/rescan")
  @RequiredArgsConstructor
  public class PublicCrawlerRescanController {

      private final PublicCrawlerRescanService service;

      @PostMapping
      public Mono<ResponseEntity<Map<String, Object>>> rescan() {
          return service.rescan().map(ResponseEntity::ok);
      }
  }
  ```
- [x] 329.5c 新檔 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanExceptionAdvice.java`（匿名端點須有專屬錯誤消毒層，不得沿用全域 `BusinessErrorAdvice` 的「原樣轉發」——`bff/src/main/java/com/steven/assets/bff/common/BusinessErrorAdvice.java` 會把 business 任何非 2xx 回應的 body **原樣**轉發，而 business 端 `GlobalExceptionHandler.java:76-79` 對未分類例外的兜底 `@ExceptionHandler(Exception.class)` 會把 `ex.getMessage()`——可能含內部細節的原始例外訊息——放進 `ProblemDetail.detail` 回 500；這套組合對既有已登入 ADMIN 端點是合理取捨，但本端點完全匿名，不得沿用）：
  ```java
  package com.steven.assets.bff.crawlerdata;

  import org.springframework.core.Ordered;
  import org.springframework.core.annotation.Order;
  import org.springframework.http.HttpStatus;
  import org.springframework.http.ProblemDetail;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.ExceptionHandler;
  import org.springframework.web.bind.annotation.RestControllerAdvice;
  import org.springframework.web.reactive.function.client.WebClientException;
  import org.springframework.web.reactive.function.client.WebClientResponseException;

  /**
   * 公開觸發重新搜尋的封閉錯誤契約（Requirement 71）：不回傳 business 原始 body 或例外訊息。
   *
   * <p>比照 {@code PublicUsdTwdExceptionAdvice}／{@code LatestAssetsPublicExceptionAdvice} 既有的
   * scoped-advice 命名模式（{@code assignableTypes} 限定只對 {@link PublicCrawlerRescanController}
   * 生效）。<b>但 {@code assignableTypes} 範圍窄不等於 Spring 保證它蓋過全域
   * {@code BusinessErrorAdvice}</b>——兩者對同一個 {@code WebClientResponseException} 各自宣告
   * handler，Spring 跨 {@code @ControllerAdvice} bean 解析同一例外型別時依 {@code @Order}／bean
   * 註冊順序決定，不會因為某個 advice 的 {@code assignableTypes} 範圍較窄就自動優先；三支既有
   * sibling 皆未宣告 {@code @Order}：{@code PublicUsdTwdExceptionAdvice}／
   * {@code PublicMarketIndexExceptionAdvice} 的既有測試只用
   * {@code WebTestClient.bindToController(...).controllerAdvice(僅自己)} 組裝、從未把
   * {@code BusinessErrorAdvice} 一起放進同一個測試 context；{@code LatestAssetsPublicExceptionAdvice}
   * （三支裡唯一真正會與 {@code BusinessErrorAdvice} 競爭同一個 {@code WebClientResponseException}
   * handler 的一支，另兩支各自只處理專屬 domain exception）則連任何既有測試都沒有。故「scoped
   * advice 會蓋過全域 advice」這件事在本專案<b>從未被任何既有測試證明過</b>。故本類別<b>明確宣告
   * {@code @Order(Ordered.HIGHEST_PRECEDENCE)}</b>，不依賴未定義的 bean 註冊順序去「碰運氣蓋過」
   * 全域 advice——這是唯一能給出決定性保證的做法。
   *
   * <p>與 {@code LatestAssetsPublicExceptionAdvice.downstream()} 刻意不同：後者對 business 404 是
   * 原樣 relay，因為那是「查無可信 owner」這個合法結構化訊號；本端點的 business 端
   * {@code POST /api/crawler-export-path/public-rescan} 設計上一律回 200（結果全部表達在
   * {@code RunNowResponse.status}），故出現非 2xx 本身即代表未預期的失敗，一律消毒。
   */
  @RestControllerAdvice(assignableTypes = PublicCrawlerRescanController.class)
  @Order(Ordered.HIGHEST_PRECEDENCE)
  public class PublicCrawlerRescanExceptionAdvice {

      @ExceptionHandler(WebClientResponseException.class)
      public ResponseEntity<ProblemDetail> handleBusinessError(WebClientResponseException ex) {
          ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                  HttpStatus.BAD_GATEWAY, "重新搜尋觸發暫時失敗，請稍後再試");
          problem.setTitle("Crawler rescan downstream failure");
          return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem);
      }

      @ExceptionHandler(WebClientException.class)
      public ResponseEntity<ProblemDetail> handleTransportFailure(WebClientException ex) {
          ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                  HttpStatus.SERVICE_UNAVAILABLE, "重新搜尋服務暫時無法連線");
          problem.setTitle("Crawler rescan service unavailable");
          return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
      }
  }
  ```
  `WebClientResponseException extends WebClientException`，Spring 依例外型別具體程度挑選最匹配的 handler，故兩個 handler 並存不衝突（前者精確攔非 2xx，後者兜底攔連線失敗／逾時等 transport 層例外）。
- [x] 329.6 `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java` 新增（緊鄰既有三條 `HttpMethod.GET` 的 `pathMatchers(...).permitAll()`，約第 79–82 行附近）：
  ```java
  // Requirement 71：公開觸發重新搜尋，第六條 Nginx 9090 路由，唯一有寫入副作用的匿名端點；
  // 30 秒全域冷卻在 business 端（CrawlerExportPathService.publicRescan()），BFF 層不重複防護。
  .pathMatchers(HttpMethod.POST, "/api/public/crawler-data/rescan").permitAll()
  ```
  與既有三條 `HttpMethod.GET` 的 `permitAll` **分開宣告**（method 不同，Spring Security 的 `pathMatchers(HttpMethod, String...)` 不可用不同 method 合併同一次呼叫）。同路徑其他 method、descendant，以及相鄰 `/api/bff/crawler-data/**`（含既有兩支 ADMIN 手動按鈕、排程與輸出路徑設定端點）**不修改**，維持既有規則。

### nginx（api-gateway）

- [x] 329.7 `api-gateway/nginx.conf` 在既有 `map $request_method $api_allow_header { ... }`（第 29–32 行附近）之後，新增一個對稱的第二個 map（既有那個硬編碼「合法方法是 GET」，語意與本路由相反，不可挪用）：
  ```nginx
  map $request_method $rescan_allow_header {
      POST "";
      default "POST";
  }
  ```
  在既有五個 `location = ...` 區塊之後、`location / { return 404; }` 之前，新增：
  ```nginx
  location = /api/public/crawler-data/rescan {
      add_header Allow $rescan_allow_header always;
      if ($request_method != POST) { return 405; }
      set $bff_upstream bff:8080;
      proxy_pass http://$bff_upstream$request_uri;
  }
  ```
  用 `map` 而非寫死 `add_header Allow POST always;`：既有五條唯讀路由對「合法方法」的成功回應**不帶** `Allow` header（nginx 對 `add_header` 收到空字串值時不加該 header），只在方法不合法的 405 回應才帶——`$rescan_allow_header` 讓本路由沿用同一行為（POST 成功時不帶 `Allow`，非 POST 的 405 才帶 `Allow: POST`），與既有五條的既定行為對稱，而不是無條件在每個回應都帶。upstream 沿用既有 `set $bff_upstream bff:8080;` ＋ `proxy_pass http://$bff_upstream$request_uri;` 兩段式寫法（透過變數觸發 request-time `resolver 127.0.0.11` 動態解析，避免 container recreate 換 IP 後黏住舊位址），**不得**寫死 `proxy_pass http://bff:8080$request_uri;`。

### frontend（第二道匿名入口防線）

- [x] 329.8 `frontend/nginx.conf` 在既有五條 `location = /api/... { return 404; }`（約第 55–59 行）之後新增第六條：
  ```nginx
  location = /api/public/crawler-data/rescan { return 404; }
  ```
- [x] 329.9 同檔既有 matrix-parameter deny regex（約第 62 行）：
  ```
  location ~ "^/api(?:;[^/]*)?/(?:quotes(?:;[^/]*)?(?:/one(?:;[^/]*)?)?|public(?:;[^/]*)?/(?:market-index(?:;[^/]*)?|exchange-rate(?:;[^/]*)?/usd-twd(?:;[^/]*)?)|assets(?:;[^/]*)?/latest(?:;[^/]*)?)$" {
      return 404;
  }
  ```
  改為（只在既有 `public(?:;[^/]*)?/(...)` 群組內新增一個 `|` 分支，其餘既有分支逐字不變）：
  ```
  location ~ "^/api(?:;[^/]*)?/(?:quotes(?:;[^/]*)?(?:/one(?:;[^/]*)?)?|public(?:;[^/]*)?/(?:market-index(?:;[^/]*)?|exchange-rate(?:;[^/]*)?/usd-twd(?:;[^/]*)?|crawler-data(?:;[^/]*)?/rescan(?:;[^/]*)?)|assets(?:;[^/]*)?/latest(?:;[^/]*)?)$" {
      return 404;
  }
  ```

### Tailscale script（第六條 preflight 不得真的觸發抓取）

- [x] 329.10 `scripts/configure-tailscale-api-gateway.sh` 的 `SERVE_PATHS` 陣列（第 5–11 行）新增一筆：`'/api/public/crawler-data/rescan'`。
- [x] 329.11 `validate_owned_config()` 內 Python heredoc 的 `expected` dict（約第 123–129 行）新增一筆：`"/api/public/crawler-data/rescan": "http://127.0.0.1:9090/api/public/crawler-data/rescan"`。
- [x] 329.12 **既有 `get_200()`（第 18–31 行）對本路徑不適用，不得沿用**：它斷言 HTTP 200 且 `Content-Type: application/json`，本路徑對 `GET` 的正確回應是 `405`。若誤用既有寫法對本路徑發送 `POST` 來驗證契約通不通，會讓**每一次執行這支設定腳本**（人工重跑、未來若接 CI）都真的觸發一輪對外抓取——這正是本任務要以 30 秒冷卻防範的同一種濫用，只是這次是自己的維運工具造成的。新增獨立函式（緊鄰 `get_200()` 之後）：
  ```bash
  get_405_post_only() {
    local url=$1
    local output_file=$2
    local label=$3
    local headers_file=$4
    local -a curl_args=(-sS -D "$headers_file" -o "$output_file" -w '%{http_code}')
    local status
    if ! status="$(curl "${curl_args[@]}" "$url")"; then
      die "$label transport 失敗；不會 reset Serve。"
    fi
    [[ "$status" == 405 ]] || die "$label 對 GET 必須回 HTTP 405（避免真的觸發爬蟲），實際為 ${status}；不會 reset Serve。"
    grep -Eiq '^allow:[[:space:]]*POST[[:space:]]*$' "$headers_file" || \
      die "$label 缺少 Allow: POST header；不會 reset Serve。"
  }
  ```
  在既有 USD/TWD preflight 區塊（約第 222–238 行）之後、`printf '現有 Serve 設定所有權與本機五路 API preflight 通過...'`（第 240 行）之前，新增呼叫：
  ```bash
  rescan_json="$work_dir/rescan.json"
  rescan_headers="$work_dir/rescan.headers"
  get_405_post_only "$LOCAL_BASE/api/public/crawler-data/rescan" "$rescan_json" '本機爬蟲重新搜尋' "$rescan_headers"
  ```
  **全程不得對本路徑發送任何 `POST` 請求**（無論在 preflight 或後續 for 迴圈掛載階段——第 262–264 行的 `for path in "${SERVE_PATHS[@]}"` 迴圈是用 `tailscale serve --set-path` 純設定 Serve 掛載，本身不對 `LOCAL_BASE` 發 HTTP request，這段不受影響、不需修改）。第 240 行的 log 文案「本機五路 API」須同步改為「本機六路 API」或等義措辭，避免與實際檢查數量不一致。
- [x] 329.12b 同檔另外兩處寫死「五條」的 operator-facing 錯誤訊息字串（`grep -n "五路\|五條\|五個\|五支" scripts/configure-tailscale-api-gateway.sh` 可定位）：`validate_owned_config()` 內 Python heredoc 的 `raise SystemExit("必須精確只有本任務管理的五條 path handler")`（約第 139 行）、腳本尾端 `die '建立後的 Serve config 不是預期五條 exact handler。'`（約第 268 行）。這兩處是 `validate_owned_config` 在 `exact`／`allow-empty` 模式驗證失敗時才會顯示的訊息；加入第六條後若真的走到這兩條錯誤分支，訊息仍會誤報「五條」與實際上下文（六條）不符，一併改為「六條」。

### 測試（情境列於下方；先在各模組 `src/test/java` 底下對應套件找既有測試檔案，比照其命名慣例掛新測試方法或新建同慣例檔案，不在此指定固定檔名）

- [x] 329.13 涵蓋以下情境：
  - ext：`publicRescan()` 與 `fetchAndExportNow()` 共用 `running` 鎖，鎖被持有時兩者互相排擠，斷言未真正呼叫抓取 client。
  - ext：`publicRescan()` 產出的 JSON／xlsx 檔案 `trigger` 欄位為 `public-rescan`。
  - ext：`InternalPriceController` 新映射 `POST /internal/news-poller/public-rescan` 呼叫 `newsPoller.publicRescan()`。
  - business：冷卻鍵已被持有時，第二次呼叫 `service.publicRescan()` 斷言 ext client 零互動、回傳 `status=COOLDOWN`。
  - business：冷卻可用時正確轉送至 `/internal/news-poller/public-rescan` 且逐欄位透傳。
  - business：`BUSY`／`DISABLED`／`ERROR` 三種結果會立即刪除冷卻鍵（下一次呼叫不必等 TTL 到期即可再次嘗試）。
  - business：`OK`／`FAILED`／`RUNNING` 三種結果會以 `SET` 重新起算全新 30 秒 TTL——**斷言重新整理後的剩餘 TTL 接近 30 秒**，不是「30 秒減去呼叫耗時」（例如注入一個讓 `proxyManualRun` 耗時數秒才回應的替身，驗證返回後冷卻鍵的剩餘存活時間仍接近完整 30 秒）。
  - business：Redis 連線例外時 fail-open（仍會呼叫 ext）——涵蓋兩個獨立分支：(1) `acquirePublicRescanCooldown()` 本身的 `setIfAbsent` 拋例外時放行並呼叫 ext；(2) renewal 的 `redis.opsForValue().set(...)`（`OK`／`FAILED`／`RUNNING` 分支）拋例外時，本次呼叫仍正常回應原本的 `result`（不拋例外、不影響本次結果），僅 log warn——這是與 (1) 性質不同的失敗點（冷卻鍵可能維持在已自然過期的狀態，等同這次呼叫沒有冷卻保護，而非「保護窗口不夠長」），需獨立斷言，不能只測 (1) 就當作涵蓋。
  - bff：`PublicCrawlerRescanController` 只委派 `PublicCrawlerRescanService`，controller 本身零 `WebClient` 互動（以替身斷言）。
  - bff：匿名（無 session）呼叫回 200 並原樣 relay body；business 端非 2xx 或連線失敗時 BFF 不吞錯誤（不得回退成 200 空物件）。
  - bff：**`PublicCrawlerRescanExceptionAdvice` 真的蓋過全域 `BusinessErrorAdvice`，而不是測試環境根本沒有全域 advice 可競爭**——`WebTestClient` 組裝時必須**同時**註冊兩者（例如 `.controllerAdvice(new PublicCrawlerRescanExceptionAdvice(), new BusinessErrorAdvice())`），斷言在兩者都在場、且都對同一個 `WebClientResponseException` 型別宣告 handler 的情況下，回應仍是消毒後的固定文案 502／503、**不含任何來自 business 的原始文字**（例如注入一個帶特定內部訊息字串的假例外，斷言該字串不出現在 BFF 最終回應 body 裡）。**不得**照抄三支既有 sibling 測試的 `bindToController(...).controllerAdvice(僅自己)` 寫法——那種寫法從未把 `BusinessErrorAdvice` 一起放進同一個測試 context，測不到「跨 advice 競爭」這件事，會給假陽性（詳見 `PublicCrawlerRescanExceptionAdvice` 上方 329.5c 的 `@Order` 說明）。
  - bff `SecurityConfig`：本路徑 GET/PUT/PATCH/DELETE 回 401，descendant 與相鄰 `/api/bff/crawler-data/**`（含既有兩支 ADMIN 端點）不受影響。

### 文件同步（「五條 exact GET」在本專案有多個獨立出處，逐一列出，不得只改一處）

- [x] 329.14 `CLAUDE.md` 第 217 行附近（「BFF 與資料來源規範」第 3 節）：「Docker 外部 HTTP 只能從 non-root Nginx `api-gateway` 的 loopback `127.0.0.1:9090` 五條 exact GET 進入」須更正為「...`127.0.0.1:9090` 六條路由（五條唯讀 GET ＋ 一條寫入 POST `/api/public/crawler-data/rescan`，見 Requirement 71）進入」；同段落補一句「`/api/public/crawler-data/rescan` 是唯一有外部抓取副作用的例外，經 business 端 30 秒全域 Redis 冷卻節流，語意等同 `fetchAndExportNow()` 的匿名版本」。Requirement 條號引用（原「Requirements 66–68／70」）視段落語意決定是否併入 71（例如改成「Requirements 66–68／70／71」）。
- [x] 329.15 `spec/steering/structure.md` 兩處：
  - §3.2「BFF 設計鐵則」條目 2 的具名例外段落（`grep -n "具名、限縮例外" spec/steering/structure.md` 定位，約第 164 行）：現列 `Requirements 67／68／70；Tasks 317／325／327`、只列舉三條匿名唯讀 exact GET，須改為併入 `Requirement 71`／`Task 329`，並在列舉裡補上第六條 `POST /api/public/crawler-data/rescan`（連帶說明它是唯一有外部抓取副作用、經冷卻節流的例外）。
  - §4.4「Docker 外部 API Gateway」段落（約第 245–250 行）：「它只轉送五條 exact GET…Tailscale Serve 只掛相同五條 exact path」須改為「六條路由（五條 exact GET ＋ 一條 exact POST）」，並註明第六條非唯讀。
- [x] 329.16 `spec/tasks/README.md` 的 `spec/` 目錄樹註解（`grep -n "311–328" spec/tasks/README.md` 定位，約第 7 行）：任務索引範圍「311–328」須同步改成「311–329」（`spec/tasks.md`／`CLAUDE.md`／`spec/steering/structure.md` 三處已在 spec 撰寫階段先行更正，這一份容易被漏掉，實作時務必一併檢查）。
- [x] 329.17 `spec/requirements.md` 裡 Requirement 66／68／70 自身既有 AC（含可執行的測試斷言，非純敘述性文字）與 `spec/design.md` 敘述性段落裡同義的「五條／五路」殘留提及，須逐一改為六條，或就地插入指向 Requirement 71 的行內 callout（二擇一，比照 Requirement 66 第一條 AC 已示範的做法）。這些描述的是 Requirement 66／68／70 已上線的既有契約，本次 spec 撰寫階段刻意不搶先修正（避免描述一個第六條尚未存在的狀態），統一留到本任務落地時一次處理。已知位置（`grep -n "五條\|五路\|五個\|五支" spec/requirements.md spec/design.md` 可重新定位，行號可能因本任務其他編輯而略有偏移）：
  - `spec/requirements.md`：Requirement 66 的 AC（現分別在第 2623、2624、2625、2626、2629、2631 行附近，除第一條 AC 已插 callout 外，其餘仍逐字寫「五條」「五路」「五支」「五個」，其中「五路 preflight 一律先驗 status/header」與「五條 path 的 POST/PUT/PATCH/DELETE 均為 405」是可執行的測試斷言）；Requirement 68 的 AC（現第 2659、2670 行附近，其中 AC(h) 明文要求「執行 Requirement 66 的五路 preflight／五路遠端驗證」）；Requirement 70 的 AC（現第 2699、2704、2715 行附近，其中「回歸與實機驗證」AC 明文要求「Tailscale HTTPS 五路正向皆須精確 200」「Serve status 精確只有五路」）。
  - `spec/design.md`：描述既有 Tailscale script／gateway 邊界的敘述性段落（現分別在第 2334、2336、2340、2579、6535、6538、6585、6734、6736、6740、6741 行附近，反覆使用「五條」「五路」「五個 exact location」等措辭；**不含**第 5092、5299 行附近——那兩處是股市大盤查詢頁 K/D 均線圖表的「五條線」，與 API gateway 路由數無關，不屬本項範圍）。

## 驗證

```bash
# backend 全模組測試（含爬蟲輸出路徑 service／controller 新增方法的新測試）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test

# external-materials-service 全模組測試（NewsPoller 新方法／InternalPriceController 新映射的新測試）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test

# bff 全模組測試（新 Public 觸發 controller／SecurityConfig 新規則的新測試）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test

# nginx 語法檢查
docker run --rm -v "$(pwd)/api-gateway/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:alpine nginx -t
docker run --rm -v "$(pwd)/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" nginx:alpine nginx -t

# 無快取重建並 recreate 受影響服務（本專案沒有 dev server，改好的定義是 image rebuild + container recreate）
docker compose -p asset-management build --no-cache backend external-materials-service bff api-gateway frontend
docker compose -p asset-management up -d --no-deps --force-recreate backend external-materials-service bff api-gateway frontend

# 實機驗證：本機 9090 六條路由的 method／404 矩陣
curl -sS -o /dev/null -w '%{http_code}\n' -X GET  http://127.0.0.1:9090/api/public/crawler-data/rescan   # 期望 405
curl -sS -D - -o /dev/null http://127.0.0.1:9090/api/public/crawler-data/rescan -X GET | grep -i '^allow:'  # 期望含 POST
curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9090/api/public/crawler-data/rescan/x            # 期望 404
curl -sS -o /dev/null -w '%{http_code}\n' -X POST http://localhost/api/public/crawler-data/rescan            # frontend port 80，期望 404

# 實機驗證：真的觸發一次（會真實對外抓取，寫入 news_headline，請在確認要驗證此功能時才執行）
curl -sS -X POST http://127.0.0.1:9090/api/public/crawler-data/rescan | python3 -m json.tool
# 緊接第二次呼叫應在 30 秒內回 status=COOLDOWN
curl -sS -X POST http://127.0.0.1:9090/api/public/crawler-data/rescan | python3 -m json.tool

# Tailscale script 若已登入且啟用 Serve，可重跑設定腳本驗證 preflight 不觸發抓取
# （執行前先 curl 直連 127.0.0.1:9090 的 rescan 端點確認目前非冷卻中，執行後比對 news_headline 筆數與時間戳未因這次「執行設定腳本」而改變）
bash scripts/configure-tailscale-api-gateway.sh
```

## 完成報告

**實作日期：** 2026-08-15

### 改動的檔案

**ext（external-materials-service）**
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/NewsPoller.java`：新增 `publicRescan()`，與 `fetchAndExportNow()` 共用 `running` 鎖與 `enabled` 檢查，唯一差異是 `trigger` 傳 `"public-rescan"`。
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/InternalPriceController.java`：新增 `POST /internal/news-poller/public-rescan` 映射，委派 `newsPoller.publicRescan()`。
- 新檔 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/NewsPollerPublicRescanTest.java`（5 個測試）。
- 新檔 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/controller/InternalPriceControllerPublicRescanTest.java`（1 個測試）。

**business（backend）**
- `backend/src/main/java/com/steven/assets/service/CrawlerExportPathService.java`：加 `@Slf4j`、新增 `StringRedisTemplate redis` 建構子參數、`PUBLIC_RESCAN_COOLDOWN_SECONDS`／`PUBLIC_RESCAN_COOLDOWN_KEY` 常數、`publicRescan()`／`acquirePublicRescanCooldown()` 方法。
- `backend/src/main/java/com/steven/assets/controller/CrawlerExportPathController.java`：新增 `POST /api/crawler-export-path/public-rescan`，不檢查 `isAdmin()`。
- `backend/src/main/java/com/steven/assets/dto/CrawlerExportPathDto.java`：`RunNowResponse` 的 Javadoc 補上 `COOLDOWN` 狀態說明。
- 既有測試因建構子多一個參數而更新：`backend/src/test/java/com/steven/assets/service/CrawlerGdriveOutputTest.java`、`backend/src/test/java/com/steven/assets/service/CrawlerManualExportProxyTest.java`（後者另加一個 `publicRescan不檢查Admin直接委派service` 測試）。
- 新檔 `backend/src/test/java/com/steven/assets/service/CrawlerExportPathServicePublicRescanTest.java`（10 個測試，ext 以裸 `com.sun.net.httpserver.HttpServer` 替身，冷卻鍵以 Mockito `StringRedisTemplate`／`ValueOperations` 替身）。

**bff**
- 新檔 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanService.java`
- 新檔 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanController.java`
- 新檔 `bff/src/main/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanExceptionAdvice.java`（含 `@Order(Ordered.HIGHEST_PRECEDENCE)`）
- `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`：新增 `pathMatchers(HttpMethod.POST, "/api/public/crawler-data/rescan").permitAll()`。
- 新檔 `bff/src/test/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanServiceTest.java`（3 個測試）
- 新檔 `bff/src/test/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanControllerTest.java`（3 個測試）
- 新檔 `bff/src/test/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanExceptionAdviceTest.java`（3 個測試，`WebTestClient` 同時註冊 `PublicCrawlerRescanExceptionAdvice` 與 `BusinessErrorAdvice`）
- 新檔 `bff/src/test/java/com/steven/assets/bff/crawlerdata/PublicCrawlerRescanSecurityTest.java`（2 個測試）

**nginx**
- `api-gateway/nginx.conf`：新增 `map $request_method $rescan_allow_header` 與 `location = /api/public/crawler-data/rescan`。
- `frontend/nginx.conf`：新增 exact 404 location，並在 matrix-parameter deny regex 的 `public(?:;[^/]*)?/(...)` 分支內加入 `crawler-data(?:;[^/]*)?/rescan(?:;[^/]*)?`。

**Tailscale script**
- `scripts/configure-tailscale-api-gateway.sh`：`SERVE_PATHS` 新增一筆、`validate_owned_config()` 的 `expected` dict 新增一筆、新增 `get_405_post_only()` 函式（緊鄰 `get_200()` 之後）與呼叫、「五條」→「六條」的兩處 operator-facing 錯誤訊息、「本機五路 API」→「本機六路 API」log 文案。

**文件同步**
- `CLAUDE.md`：「BFF 與資料來源規範」第 3 節「五條 exact GET」→「六條路由」，補上 crawler-data/rescan 說明與 Requirement 71 引用。
- `spec/steering/structure.md`：§3.2「BFF 設計鐵則」具名例外段落與 §4.4「Docker 外部 API Gateway」段落皆改為六條並補上 Requirement 71／Task 329。
- `spec/tasks/README.md`：目錄樹註解「311–328」→「311–329」（`spec/tasks.md`／`CLAUDE.md`／`structure.md` 三處在 spec 撰寫階段已先行更正，本檔補齊）。
- `spec/requirements.md`：Requirement 66 的 6 條 AC（原第 2616、2623、2624、2625、2626、2629、2631 行附近，以 grep 重新定位後實際處理了 2616／2623／2624（含涵蓋 2625/2626/2629/2631 的統一 callout）、Requirement 68 的 2 條 AC（2659、2670）、Requirement 70 的 2 條 AC（2704、2715）皆插入指向 Requirement 71 的行內 callout；Requirement 70 開頭 `> 編號說明` 段（原第 2699 行）的「第五條同名 exact path」為描述 USD/TWD 歷史序位（第幾條被加入）的序數用法、非「總數為五」的封閉式宣稱，判斷不屬於本項範圍、未改動。
- `spec/design.md`：`API Design/Base URL` 段落改寫為「六條路由」；其餘 9 處敘述性段落（gateway 拓撲圖、Nginx／Tailscale 設定說明、Requirement 68／70 各自的拓撲快照與驗證矩陣）皆採插入行內 callout 的方式指向 Requirement 71 的「邊界：五條唯讀 GET 之外的第六條」一節，未逐一重寫涉及的 nginx/Tailscale 程式碼區塊或 ASCII 拓撲圖本身（避免與 Requirement 71 自己的設計段落重複維護兩份幾乎相同的六條版本）。第 5092／5299 行附近的 K/D 均線圖表「五條線」與本項無關，未觸碰。

### 測試結果

- **backend**：`mvn -f backend/pom.xml test` → `Tests run: 933, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- **external-materials-service**：`mvn -f external-materials-service/pom.xml test` → `Tests run: 354, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- **bff**：`mvn -f bff/pom.xml test` → `Tests run: 84, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- **nginx -t**：`api-gateway/nginx.conf` 與 `frontend/nginx.conf` 皆 `syntax is ok` / `test is successful`（`nginx:alpine` 容器驗證）。
- 未執行「無快取重建並 recreate」與「實機驗證：本機 9090 六條路由」等段落——按呼叫端指示，這些留待驗收方另行處理。

### 與原計畫的偏差

1. **`bff` 測試不得用 Mockito `mock()` 直接 mock 具體類別**（原計畫 329.13 沒有明講寫法細節）：第一版 `PublicCrawlerRescanControllerTest` 用 `mock(PublicCrawlerRescanService.class)` 驗證委派，在本機 Java 25 環境下觸發 Mockito inline mock maker 的 byte-buddy retransformation 失敗（`Could not modify all classes`）。查證後發現 `bff/pom.xml` 完全沒有 wiring `extraArgLine`/`argLine`（`backend`／`external-materials-service` 的 pom.xml 都有，`bff` 沒有），且 bff 全樹既有測試零處對具體類別呼叫 `mock()`（全部改用替身 `WebClient` exchangeFunction 或真實物件）。改用「子類別覆寫 `rescan()` 的手刻替身＋反射斷言無 `WebClient` 欄位」取代 Mockito mock，符合本專案 bff 測試既有慣例，且比 Mockito 零互動驗證更嚴格（替身 WebClient 的 exchangeFunction 若真被呼叫會直接擲 `AssertionError`）。**未改動 `bff/pom.xml` 補上 argLine wiring**——那屬於既有建置設定的缺口，不在本任務範圍內，如需要應另案處理。
2. **ExceptionAdvice 測試的「連線失敗」情境不能用自訂 `exchangeFunction` 模擬**：原計畫 329.13 沒有明講這個細節。第一版用 `.exchangeFunction(request -> Mono.error(new IOException(...)))` 模擬連線失敗，實測回應是 500（Spring 預設兜底）而非預期的 503——原因是自訂 `exchangeFunction` 會整個取代 `WebClient` 的連線層，此時拋出的例外不會被包裝成 `WebClientRequestException`（`WebClientException` 子類別），兩個 advice 的 handler 都攔不到。改用真的連不上的埠（開一個 `ServerSocket` 再立刻關閉）讓 Reactor Netty 連線層產生真正的 `WebClientRequestException`，藉此驗證 503 分支。
3. 其餘實作與任務檔樣板逐字一致，未發現與既有程式碼衝突之處（`NewsPoller`／`InternalPriceController`／`CrawlerExportPathService`／`CrawlerExportPathController`／`CrawlerExportPathDto`／BFF 三支 sibling／`SecurityConfig`／兩份 nginx.conf／Tailscale script 的既有簽章、行號、程式碼片段皆與任務檔描述相符）。

### 未執行項目（依呼叫端指示保留給驗收方）

- `docker compose ... build --no-cache` ＋ `up -d --force-recreate`（image rebuild／container recreate）
- 實機 curl 驗證本機 9090 六條路由的 method／404 矩陣
- 真實觸發一次 rescan 並驗證 30 秒冷卻（會真的對外抓取、寫入 `news_headline`）
- 重跑 `scripts/configure-tailscale-api-gateway.sh` 驗證 preflight 不觸發抓取（需要已登入 Tailscale 且啟用 Serve 的環境）

### 實機驗證（驗收方補做，2026-08-15）

無快取重建並 recreate `business-services`／`external-materials-service`／`bff`／`api-gateway`／`frontend` 五個服務後：

- **本機 9090 六條路由 method／404 矩陣**：全部符合預期——`GET /api/public/crawler-data/rescan` → 405 帶 `Allow: POST`；descendant → 404；`POST /api/public/crawler-data/rescan`（frontend port 80）與其 exact GET → 404；既有五條（`quotes`、`market-index`、`assets/latest`、`exchange-rate/usd-twd`）皆 200。
- **⚠ 過程中發現並修正一次映像被覆蓋（非本任務程式碼問題）**：初次重建後 `frontend`／`bff` 兩個 container 使用的映像雖 SHA 與剛 build 的一致，但實際內容（`docker exec` 解壓 jar／nginx conf 檢查）沒有本次新增的程式碼——比對後確認是本機同時有多個 worktree／session 並行對同一組共用映像 tag（`asset-management-*:latest`）做 build，最後完成的 build 覆寫了先完成的（本專案已知風險，見 `feedback_shared_stack_tag_clobber` 記憶）。徵狀：BFF 直接回 401（`SecurityConfig` 的新 permitAll 規則不存在於運行中的 jar）。處置：對 `frontend`、`bff` 各自單獨重跑一次 `--no-cache` build＋`--no-deps --force-recreate`，並在 `docker exec` 進容器解壓驗證後立刻 `curl`，把驗證窗口縮到最短；同時確認 `business-services`／`external-materials-service`／`api-gateway` 三者未受影響（`docker exec` 解壓驗證含新程式碼）。修正後兩者 image SHA 在驗證前後保持一致，未再被覆寫。
- **實際觸發一次公開重新搜尋**：`POST http://127.0.0.1:9090/api/public/crawler-data/rescan` → `200`，`status=OK`／`mode=FETCH_AND_EXPORT`／`upserted=356`／`failed=0`／`exported=226`，兩份檔案（`public_info_2026-08-15.json` 76966 bytes、`.xlsx` 29233 bytes）皆產出並成功同步至 Google Drive，耗時約 10 秒（典型案例，遠低於 30 秒冷卻窗口與 50 秒逾時上限）。
- **緊接第二次呼叫（5 秒後）**：回 `200`，`status=COOLDOWN`、`message="冷卻中，請 30 秒後再試"`，其餘欄位皆 `null`——冷卻節流機制運作符合預期，未真的再次觸發對外抓取。
- `docker logs asset-bff` 近期無 error/exception；`docker logs asset-business-services` 有一則與本功能無關的既有 SSE／`ProblemDetail` content-type 警告（時間點早於本次觸發），非本次變更引入。
