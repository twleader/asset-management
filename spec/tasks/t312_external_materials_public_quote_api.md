# [t312] external-materials-service 最新報價 host 對外唯讀查詢 API

**對應 Requirements:** Requirement 66（external-materials-service 最新報價——Docker host 對外唯讀查詢 API）
**前置任務:** 無
**Liquibase changeset:** 無（不動資料庫，純讀 Redis 既有 key）

## 背景

`external-materials-service` 盤中每 2 分鐘（`PricePoller` 的三段獨立 `@Scheduled` cron，台股 09:00–13:30、美股 09:30–16:00 ET、英股 08:00–16:00 LON）輪詢一次外部行情，寫入 Redis（`PriceCacheWriter`，key schema `price:{market}:{code}` JSON、`price:index:{market}` SET）。這份資料目前只能透過兩條路徑取得：(a) 容器內部 `business-services` 經 docker network 呼叫既有 `/internal/*` 端點或直讀 Redis；(b) 使用者登入前端網頁。**docker-compose.yml 從未替 `external-materials-service` 開過 host port**（現行 `external-materials-service:` 服務區塊完全沒有 `ports:` 欄位），使用者無法在 host（Docker 外）用 `curl` 之類工具直接查詢。

使用者明確要求新增一支 API，讓 Docker 外可以取得這個微服務抓到的最新報價——用途是不進容器、不透過前端登入即可確認抓價是否正常運作或臨時查某檔股票的快取價格（維運/監看用途）。

**這是刻意的架構例外**：`spec/design.md` 398 行起「對外介面（`InternalPriceController`）」明訂既有 `/internal/*` 端點群「不對前端暴露 REST；全部僅在 docker network 內由 `business-services` 呼叫」（確切支數見該表頭當下計數，本任務不重複宣告數字以免與該表未來的計數校正各自漂移）。本任務不修改這個既有契約——新端點掛在**獨立命名空間** `/api/quotes`（不在 `/internal` 前綴下），既有 `/internal/*` 端點的程式碼、路徑、行為完全不變。

**安全取捨（必須被使用者理解，非本任務可自行迴避的技術債）**：`external-materials-service` 是單一 Spring Boot process，只監聽一個 8080 port（見 `external-materials-service/src/main/resources/application.yml` 第 21–22 行 `server: port: 8080`）。docker-compose 一旦替這個 port 開 `ports:` 映射，同一個 port 上的其餘既有 `/internal/*` 端點（含 `/internal/refresh`、`/internal/backfill/*`、`/internal/repair/history` 等有寫入/覆寫副作用的維運端點）也會一併可從 host 連線到——這是「同 process 只有一個 port」的必然結果，不是本任務新增的獨立漏洞，也無法只靠新增一支 controller 解決（Spring MVC 同一個內嵌 Tomcat 無法對不同 path 套不同的 port 綁定）。緩解方式是**只綁 `127.0.0.1`**（不綁 `0.0.0.0`），比照現有 `postgres` 服務 `docker-compose.yml`（`ports:` 底下）`127.0.0.1:5432:5432` 那行的既有慣例——信任水位等同「已能存取本機的操作者」（能讀 `.env`、能 `docker exec` 進任一容器），不對區域網路或公網開放。若日後要收斂到只放行 `/api/quotes`、擋掉 `/internal/*`，需另立需求評估專屬 filter 或反向代理，**不在本任務範圍**。

**既有 docker-compose.yml 第 138 行的服務說明註解會因本任務變成錯誤陳述，須一併修正（見下方 312.3）**：`external-materials-service:` 服務鍵正上方（第 135–138 行）目前是：
```yaml
  # ── External Materials Service ─────────────────────────────
  # 集中所有「向外部抓資料」的邏輯：盤中 2 分鐘抓股價寫 Redis、盤後寫
  # stock_price_history、每日抓股利寫 stock_dividend_history。
  # 不對外暴露；business-services 透過 docker network 內部呼叫 /internal/*。
```
第 138 行「不對外暴露」在開了 `ports:` 之後即與新設定直接矛盾，312.3 已一併給出修正後文字，312.6 的「不做任何其他改動」邊界明確排除這一行。

## 要做什麼

- [ ] 312.1 新增唯讀 Redis reader service `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/PriceCacheReader.java`（新檔案，與既有 `PriceCacheWriter.java` 同目錄、職責相反——只讀不寫，不觸發外部抓取、不寫入 Redis、不寫入資料庫、不呼叫 `redis.convertAndSend`／PUBLISH）：

  ```java
  package com.steven.assets.externalmaterials.service;

  import com.fasterxml.jackson.databind.JsonNode;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.data.redis.core.StringRedisTemplate;
  import org.springframework.stereotype.Component;

  import java.math.BigDecimal;
  import java.util.ArrayList;
  import java.util.LinkedHashSet;
  import java.util.List;
  import java.util.Optional;
  import java.util.Set;

  /**
   * 唯讀 Redis 即時行情 cache，供 host 對外查詢 API
   * ({@link com.steven.assets.externalmaterials.controller.PublicQuoteController}) 使用。
   *
   * 與 {@link PriceCacheWriter} 職責相反：只讀不寫，不觸發外部抓取、不寫入 Redis、不 PUBLISH。
   * Key schema 與寫入者見 {@link PriceCacheWriter} class Javadoc（Requirement 66）。
   */
  @Slf4j
  @Component
  @RequiredArgsConstructor
  public class PriceCacheReader {

      /** 目前納管的三個市場；與 {@link PricePoller}／{@link MarketClock} 既有硬編碼字面量一致。 */
      private static final List<String> MARKETS = List.of("台股", "美股", "英股");

      private final StringRedisTemplate redis;
      private static final ObjectMapper MAPPER = new ObjectMapper();

      /**
       * Redis JSON payload 的唯讀 DTO；欄位集合、型別與宣告順序逐一比照 backend 端
       * {@code PriceQueryService.LivePrice}（同一份 Redis JSON 的另一個消費端）。
       */
      public record LatestQuote(
              String stockCode,
              String stockName,
              String market,
              BigDecimal price,
              BigDecimal previousClose,
              BigDecimal priceChange,
              BigDecimal changePercent,
              BigDecimal buyPrice,
              BigDecimal sellPrice,
              BigDecimal openPrice,
              BigDecimal highPrice,
              BigDecimal lowPrice,
              Long volume,
              String tradingDate,
              String updatedAt,
              Boolean closed,
              String source,
              String quoteStatus
      ) {}

      /**
       * 列出目前 Redis 快取的所有最新報價。
       * @param market 選填市場篩選（"台股"／"美股"／"英股"其一）；null 或空白回三市場全部。
       */
      public List<LatestQuote> listAll(String market) {
          List<String> markets = (market == null || market.isBlank()) ? MARKETS : List.of(market);
          List<LatestQuote> result = new ArrayList<>();
          for (String m : markets) {
              for (String code : indexCodes(m)) {
                  findOne(code, m).ifPresent(result::add);
              }
          }
          return result;
      }

      /** 查詢單一標的最新報價；Redis cache miss 回 empty（不 fallback 查 DB、不觸發抓取）。 */
      public Optional<LatestQuote> findOne(String code, String market) {
          String key = "price:" + market + ":" + code;
          try {
              String json = redis.opsForValue().get(key);
              if (json == null) return Optional.empty();
              return Optional.of(parse(json));
          } catch (Exception e) {
              log.warn("Redis 讀取或解析失敗 {}: {}", key, e.getMessage());
              return Optional.empty();
          }
      }

      private Set<String> indexCodes(String market) {
          try {
              Set<String> codes = redis.opsForSet().members("price:index:" + market);
              return codes == null ? Set.of() : new LinkedHashSet<>(codes);
          } catch (Exception e) {
              log.warn("Redis 讀取 price:index:{} 失敗: {}", market, e.getMessage());
              return Set.of();
          }
      }

      private LatestQuote parse(String json) throws Exception {
          JsonNode n = MAPPER.readTree(json);
          return new LatestQuote(
                  text(n, "stockCode"),
                  text(n, "stockName"),
                  text(n, "market"),
                  bd(n, "price"),
                  bd(n, "previousClose"),
                  bd(n, "priceChange"),
                  bd(n, "changePercent"),
                  bd(n, "buyPrice"),
                  bd(n, "sellPrice"),
                  bd(n, "openPrice"),
                  bd(n, "highPrice"),
                  bd(n, "lowPrice"),
                  n.hasNonNull("volume") ? n.get("volume").asLong() : null,
                  text(n, "tradingDate"),
                  text(n, "updatedAt"),
                  n.hasNonNull("closed") ? n.get("closed").asBoolean() : null,
                  text(n, "source"),
                  text(n, "quoteStatus")
          );
      }

      private static String text(JsonNode n, String f) {
          JsonNode v = n.get(f);
          return v == null || v.isNull() ? null : v.asText();
      }

      private static BigDecimal bd(JsonNode n, String f) {
          JsonNode v = n.get(f);
          if (v == null || v.isNull()) return null;
          try { return new BigDecimal(v.asText()); } catch (Exception e) { return null; }
      }
  }
  ```

  解析刻意採手動 `JsonNode` 逐欄讀取（`text()`/`bd()` helper），不用 `mapper.readValue(json, LatestQuote.class)` 直接反序列化成 record——`external-materials-service/pom.xml` 的 `maven-compiler-plugin` 未開 `-parameters`，Jackson 在缺 `ParameterNamesModule` 時無法還原 record constructor 的參數名，會直接丟例外；全庫既有的 Redis JSON 解析（`PriceCacheWriter.writeVerifiedClose`／`syncClosedFromDb`、`backend` 端 `PriceQueryService.parse`）也一律採此手動模式，本檔沿用同一慣例。

- [ ] 312.2 新增對外 controller `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/PublicQuoteController.java`（新檔案）：

  ```java
  package com.steven.assets.externalmaterials.controller;

  import com.steven.assets.externalmaterials.service.PriceCacheReader;
  import com.steven.assets.externalmaterials.service.PriceCacheReader.LatestQuote;
  import lombok.RequiredArgsConstructor;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RequestParam;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;

  /**
   * 對外（docker host）唯讀查詢最新報價（Requirement 66）。
   *
   * <p>與全庫其餘既有 {@code /internal/*} 端點不同，本 controller 刻意掛在獨立命名空間，
   * 供 docker host 直接呼叫（見 {@code docker-compose.yml} 的 {@code external-materials-service}
   * 服務新增的 {@code ports} 映射，僅綁 {@code 127.0.0.1}）。純讀 Redis 既有快取，
   * 不觸發外部抓取、不寫入任何資料、不需要身份驗證（唯讀公開市場報價 ＋ 僅 loopback，
   * 理由見 {@code spec/requirements.md} Requirement 66）。
   */
  @RestController
  @RequestMapping("/api/quotes")
  @RequiredArgsConstructor
  public class PublicQuoteController {

      private final PriceCacheReader reader;

      /** 列出目前 Redis 快取的所有最新報價；market 選填篩選單一市場。查無回空陣列。 */
      @GetMapping
      public List<LatestQuote> list(@RequestParam(required = false) String market) {
          return reader.listAll(market);
      }

      /** 查詢單一標的最新報價；cache miss 回 204 No Content。 */
      @GetMapping("/one")
      public ResponseEntity<LatestQuote> one(
              @RequestParam String code, @RequestParam String market) {
          return reader.findOne(code, market)
                  .map(ResponseEntity::ok)
                  .orElseGet(() -> ResponseEntity.noContent().build());
      }
  }
  ```

- [ ] 312.3 `docker-compose.yml` 第 135–145 行現行內容：
  ```yaml
    # ── External Materials Service ─────────────────────────────
    # 集中所有「向外部抓資料」的邏輯：盤中 2 分鐘抓股價寫 Redis、盤後寫
    # stock_price_history、每日抓股利寫 stock_dividend_history。
    # 不對外暴露；business-services 透過 docker network 內部呼叫 /internal/*。
    external-materials-service:
      build:
        context: ./external-materials-service
        dockerfile: Dockerfile
      container_name: asset-external-materials-service
      restart: unless-stopped
      environment:
  ```
  **兩處改動：**

  1. 第 138 行 `# 不對外暴露；business-services 透過 docker network 內部呼叫 /internal/*。` 改為：
     ```yaml
     # /api/quotes 對外唯讀開放（Requirement 66，僅 loopback）；其餘 /internal/* 仍僅 docker network 可達，
     # business-services 透過 docker network 內部呼叫。
     ```
     （只改這一行文字，其餘註解第 135–137 行與 `external-materials-service:` 以下不動。）

  2. 在 `environment:` 區塊結束、`volumes:` 開始之前（現行第 174–175 行之間，`RCLONE_CONFIG: /etc/rclone/rclone.conf` 之後、`volumes:` 之前）插入一行 `ports:`：
     ```yaml
           RCLONE_CONFIG: /etc/rclone/rclone.conf
         ports:
           # Requirement 66：/api/quotes 唯讀對外查詢；僅綁 127.0.0.1（比照上方 postgres 既有慣例），
           # 不對外部網卡暴露。同 port 上既有 /internal/* 端點亦隨之可從 host loopback 連線，
           # 安全取捨全文見 spec/requirements.md Requirement 66。
           - "127.0.0.1:${EXTERNAL_MATERIALS_HOST_PORT:-8082}:8080"
         volumes:
     ```
     （即：緊接在既有 `environment:` 區塊最後一行 `RCLONE_CONFIG: /etc/rclone/rclone.conf` 之後、既有 `volumes:` 那一行之前，新增上述 `ports:` 四行；`environment:` 區塊本身與 `volumes:` 區塊內容一字不動。）

  除上述兩處（138 行文字、`ports:` 四行）外，`docker-compose.yml` 不做任何其他改動。

  **預設埠選 `8082` 而非 `8081`**：`bff/src/main/resources/application.yml` 第 54 行 `BUSINESS_SERVICES_URL` 的本機 fallback 預設值恰為 `http://localhost:8081`（純 docker-compose 部署下這條 fallback 不生效，`docker-compose.yml` 已明確設定 `BUSINESS_SERVICES_URL: http://business-services:8080` 蓋過它；但為避免與本庫既有組態語意混淆，選一個庫內完全未被引用的埠號）；已 `grep` 確認 `8082` 未被 `bff`／`backend`／`docker-compose.yml`／`.env.example` 任何一處引用，且已用 `lsof -nP -iTCP:8082 -sTCP:LISTEN` 確認本機當下未被佔用。

  `EXTERNAL_MATERIALS_HOST_PORT` 刻意**不**加入 `.env.example`：它有安全預設值（`8082`）、不是需要人工填入的密鑰或帳號，比照 `.env.example` 現行只收錄「需要真實填值」變數（`POSTGRES_PASSWORD`／`ADMIN_EMAIL`／`MAIL_*` 等）的既有慣例（`APP_TZ`／`MAIL_HOST`／`MAIL_PORT`／`NEWS_SCRAPER_ENABLED` 等同樣有安全預設值的既有變數也都未收錄於 `.env.example`）。

- [ ] 312.4 新增測試 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/PriceCacheReaderTest.java`（新檔案，比照同目錄既有 `PriceCacheWriterTest.java` 的 mock 風格——手動 `mock()`、不用 `@ExtendWith(MockitoExtension.class)`）：

  ```java
  package com.steven.assets.externalmaterials.service;

  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.data.redis.core.SetOperations;
  import org.springframework.data.redis.core.StringRedisTemplate;
  import org.springframework.data.redis.core.ValueOperations;

  import java.math.BigDecimal;
  import java.util.List;
  import java.util.Optional;
  import java.util.Set;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.never;
  import static org.mockito.Mockito.verify;
  import static org.mockito.Mockito.when;

  class PriceCacheReaderTest {

      private StringRedisTemplate redis;
      private ValueOperations<String, String> values;
      private SetOperations<String, String> sets;
      private PriceCacheReader reader;

      @BeforeEach
      @SuppressWarnings("unchecked")
      void setUp() {
          redis = mock(StringRedisTemplate.class);
          values = mock(ValueOperations.class);
          sets = mock(SetOperations.class);
          when(redis.opsForValue()).thenReturn(values);
          when(redis.opsForSet()).thenReturn(sets);
          reader = new PriceCacheReader(redis);
      }

      @Test
      void findOne_cacheHit_parsesAllFields() {
          when(values.get("price:台股:2330")).thenReturn(
                  "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":1000.00,"
                  + "\"previousClose\":990.00,\"priceChange\":10.00,\"changePercent\":1.01,"
                  + "\"openPrice\":995.00,\"highPrice\":1005.00,\"lowPrice\":993.00,"
                  + "\"volume\":12345,\"stockName\":\"台積電\",\"source\":\"TWSE\","
                  + "\"tradingDate\":\"2026-08-10\",\"updatedAt\":\"2026-08-10T10:30:00\","
                  + "\"closed\":false,\"quoteStatus\":\"LIVE\"}");

          Optional<PriceCacheReader.LatestQuote> result = reader.findOne("2330", "台股");

          assertThat(result).isPresent();
          PriceCacheReader.LatestQuote q = result.get();
          assertThat(q.stockCode()).isEqualTo("2330");
          assertThat(q.price()).isEqualByComparingTo("1000.00");
          assertThat(q.volume()).isEqualTo(12345L);
          assertThat(q.closed()).isFalse();
          assertThat(q.quoteStatus()).isEqualTo("LIVE");
      }

      @Test
      void findOne_cacheMiss_returnsEmpty() {
          when(values.get("price:台股:9999")).thenReturn(null);

          assertThat(reader.findOne("9999", "台股")).isEmpty();
      }

      @Test
      void findOne_malformedJson_returnsEmptyNotException() {
          when(values.get("price:台股:BAD")).thenReturn("not-json");

          assertThat(reader.findOne("BAD", "台股")).isEmpty();
      }

      @Test
      void listAll_noMarketFilter_expandsAllThreeMarkets() {
          when(sets.members("price:index:台股")).thenReturn(Set.of("2330"));
          when(sets.members("price:index:美股")).thenReturn(Set.of("AAPL"));
          when(sets.members("price:index:英股")).thenReturn(Set.of());
          when(values.get("price:台股:2330")).thenReturn(
                  "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":1000.00}");
          when(values.get("price:美股:AAPL")).thenReturn(
                  "{\"stockCode\":\"AAPL\",\"market\":\"美股\",\"price\":200.00}");

          List<PriceCacheReader.LatestQuote> result = reader.listAll(null);

          assertThat(result).extracting(PriceCacheReader.LatestQuote::stockCode)
                  .containsExactlyInAnyOrder("2330", "AAPL");
      }

      @Test
      void listAll_withMarketFilter_onlyQueriesThatMarket() {
          when(sets.members("price:index:台股")).thenReturn(Set.of("2330"));
          when(values.get("price:台股:2330")).thenReturn(
                  "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":1000.00}");

          List<PriceCacheReader.LatestQuote> result = reader.listAll("台股");

          assertThat(result).hasSize(1);
          verify(sets, never()).members("price:index:美股");
          verify(sets, never()).members("price:index:英股");
      }

      @Test
      void listAll_emptyIndex_returnsEmptyListNotError() {
          when(sets.members("price:index:台股")).thenReturn(Set.of());
          when(sets.members("price:index:美股")).thenReturn(Set.of());
          when(sets.members("price:index:英股")).thenReturn(Set.of());

          assertThat(reader.listAll(null)).isEmpty();
      }

      @Test
      void reader_neverWritesToRedis() {
          when(sets.members("price:index:台股")).thenReturn(Set.of());
          when(sets.members("price:index:美股")).thenReturn(Set.of());
          when(sets.members("price:index:英股")).thenReturn(Set.of());

          reader.listAll(null);
          reader.findOne("2330", "台股");

          verify(values, never()).set(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
          verify(redis, never()).convertAndSend(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
      }
  }
  ```

- [ ] 312.5 新增測試 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/controller/PublicQuoteControllerTest.java`（新檔案，比照同目錄既有 `TreasuryYieldControllerSecurityTest.java` 的 standalone `MockMvc` 風格，本端點不需認證故不掛任何 filter）：

  ```java
  package com.steven.assets.externalmaterials.controller;

  import com.steven.assets.externalmaterials.service.PriceCacheReader;
  import com.steven.assets.externalmaterials.service.PriceCacheReader.LatestQuote;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.Test;
  import org.springframework.test.web.servlet.MockMvc;
  import org.springframework.test.web.servlet.setup.MockMvcBuilders;

  import java.math.BigDecimal;
  import java.util.List;
  import java.util.Optional;

  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.verify;
  import static org.mockito.Mockito.when;
  import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
  import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

  class PublicQuoteControllerTest {

      private PriceCacheReader reader;
      private MockMvc mvc;

      @BeforeEach
      void setUp() {
          reader = mock(PriceCacheReader.class);
          mvc = MockMvcBuilders.standaloneSetup(new PublicQuoteController(reader)).build();
      }

      private static LatestQuote quote(String code, String market) {
          return new LatestQuote(code, "測試股", market, new BigDecimal("100.00"), new BigDecimal("99.00"),
                  new BigDecimal("1.00"), new BigDecimal("1.01"), null, null,
                  new BigDecimal("99.50"), new BigDecimal("101.00"), new BigDecimal("98.50"),
                  12345L, "2026-08-10", "2026-08-10T10:00:00", false, "TWSE", "LIVE");
      }

      @Test
      void list_noMarketFilter_passesNullToReader() throws Exception {
          when(reader.listAll(null)).thenReturn(List.of(quote("2330", "台股")));

          mvc.perform(get("/api/quotes"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$[0].stockCode").value("2330"));

          verify(reader).listAll(null);
      }

      @Test
      void list_withMarketFilter_passesThrough() throws Exception {
          when(reader.listAll("美股")).thenReturn(List.of());

          mvc.perform(get("/api/quotes").param("market", "美股"))
                  .andExpect(status().isOk())
                  .andExpect(content().json("[]"));

          verify(reader).listAll("美股");
      }

      @Test
      void one_cacheHit_returns200WithBody() throws Exception {
          when(reader.findOne("2330", "台股")).thenReturn(Optional.of(quote("2330", "台股")));

          mvc.perform(get("/api/quotes/one").param("code", "2330").param("market", "台股"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.stockCode").value("2330"))
                  .andExpect(jsonPath("$.quoteStatus").value("LIVE"));
      }

      @Test
      void one_cacheMiss_returns204() throws Exception {
          when(reader.findOne("9999", "台股")).thenReturn(Optional.empty());

          mvc.perform(get("/api/quotes/one").param("code", "9999").param("market", "台股"))
                  .andExpect(status().isNoContent());
      }

      @Test
      void one_missingRequiredParam_returns400() throws Exception {
          mvc.perform(get("/api/quotes/one").param("code", "2330"))
                  .andExpect(status().isBadRequest());
      }
  }
  ```

  **`PriceCacheReaderTest`／`PublicQuoteControllerTest` 為本任務新建立的測試檔，實作前尚不存在於樹上——若 `scripts/spec-check.sh` 的 B5（宣稱的測試類是否存在）對這兩個類名回報 BLOCK，屬預期的誤判（新建檔案 vs Task 160 前例的「宣稱既有但查無」不同性質），`/spec-review` 時請 spec-auditor 依此判定，不需要為了迴避 B5 而改寫成含糊敘述；`spec/tasks/t306_exchange_rate_mega_bank_fallback.md` 的 `ExchangeRatePollerTest` 為同類先例。**

- [ ] 312.6 不得變動的邊界：
  - 不修改 `InternalPriceController.java` 既有端點的任何一行（路徑、參數、回應形狀、Javadoc 皆不變）。
  - 不修改 `PriceCacheWriter.java` 的任何寫入邏輯；`PriceCacheReader` 為全新檔案，不與 `PriceCacheWriter` 共用任何寫入方法。
  - 不新增 Redis key、不修改既有 Redis key 的 TTL 或 payload 欄位。
  - 不新增資料庫欄位或資料表，不新增 Liquibase changeset。
  - `docker-compose.yml` 除 312.3 指定的兩處（第 138 行註解文字、新增的 `ports:` 四行）外不做任何其他改動；`business-services`／`bff`／`frontend`／`postgres`／`redis` 服務區塊不變。
  - 不替新端點加身份驗證 filter（比照 Requirement 66 AC「不要求身份驗證」的明確決定，不得自行加碼比照 `TreasuryYieldAdminFilter`）。
  - business-services／BFF／前端程式碼一律不觸碰——此端點只服務 host 端直接查詢，不接線進既有前端頁面或 BFF route。

## 驗證

```bash
# 1. 單元測試（新增的 PriceCacheReaderTest ＋ PublicQuoteControllerTest）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -Dtest=PriceCacheReaderTest,PublicQuoteControllerTest

# 2. 全模組既有測試不得因本次改動而壞掉（confirm 沒有動到共用程式碼）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test

# 3. spec 機械檢查（B5 對兩支新測試類的說明見 312.4 附註）
bash scripts/spec-check.sh

# 4. compose 語法檢查（確認 312.3 的 YAML 縮排與既有服務區塊沒有破壞整份檔案）
docker compose -p asset-management config -q
```

（1）（2）（4）皆須 exit code 0；（1）的 surefire 輸出需顯示新增測試全數 `Failures: 0, Errors: 0`。若本機 JDK 25 + Mockito inline mock maker 觸發 byte-buddy 相容性問題（`PriceCacheReaderTest` mock 了具體類別 `StringRedisTemplate`），依既有解法（`external-materials-service/pom.xml` surefire 設定的 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`，**不要用 `-DargLine`**——那會覆蓋既有時區設定）；若該 flag 已在 pom.xml 的 surefire 設定中預設帶入則無需額外傳參，先跑一次確認。

**本專案沒有 dev server，改好的定義是 image rebuild + container recreate**（見 `.claude/skills/run-stack`）：

```bash
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
sleep 5
docker compose -p asset-management ps external-materials-service
```

容器 healthy 後，從 **host**（不進容器）驗證新端點確實可從 Docker 外連線：

```bash
curl -sS "http://127.0.0.1:${EXTERNAL_MATERIALS_HOST_PORT:-8082}/api/quotes" | head -c 500
echo
curl -sS -o /dev/null -w "%{http_code}\n" "http://127.0.0.1:${EXTERNAL_MATERIALS_HOST_PORT:-8082}/api/quotes/one?code=__NOT_EXIST__&market=台股"
```

第一個 `curl` 應回 200 與 JSON 陣列（盤中應非空；盤外／假日視 Redis TTL 24h 內是否仍有殘留快取而定，空陣列也是合法回應，不代表失敗）。第二個 `curl` 對不存在的代號應回 **204**。另外確認既有 `/internal/health` 仍只在 docker network 內部可達的既有行為不受影響（此端點本就未變動，僅供交叉確認 container 內部功能正常）：

```bash
docker exec asset-external-materials-service wget -qO- http://localhost:8080/internal/health
```

## 完成報告

**實際改了哪些檔（`git status --short`）：**

新增：
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/PriceCacheReader.java`（312.1）
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/controller/PublicQuoteController.java`（312.2）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/PriceCacheReaderTest.java`（312.4）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/controller/PublicQuoteControllerTest.java`（312.5）

修改：
- `docker-compose.yml`（312.3，兩處：第 138 行服務說明註解文字＋`environment:`/`volumes:` 之間新增 `ports:` 四行）——`git diff` 核對僅此兩處變動，`business-services`／`bff`／`frontend`／`postgres`／`redis` 服務區塊與 `environment:`／`volumes:` 區塊本身內容均未變動。

`CLAUDE.md`、`spec/design.md`、`spec/requirements.md`、`spec/steering/structure.md`、`spec/tasks.md` 為先前 spec 撰寫階段（本任務檔建立與 `/spec-review` 通過）已完成的既有變更，非本次實作步驟所產生，未被本次動作觸碰；本檔（`t312_...md`）本身僅本節（完成報告）為本次新增內容，312.1–312.6 的「要做什麼」原文逐字未動。

**312.1–312.5 程式碼與測試逐字採用任務檔給定版本，未做任何調整或「優化」。**312.6 邊界逐項確認：`InternalPriceController.java`／`PriceCacheWriter.java` 用 `git diff --stat` 核對為空（完全未觸碰）；未新增 Redis key／未改既有 TTL 或 payload 欄位；未新增 DB 欄位／資料表／Liquibase changeset；未加身份驗證 filter；未觸碰 `backend/**`／`bff/**`／`frontend/**`。

**驗證指令實際輸出：**

1. `mvn -q -f external-materials-service/pom.xml test -Dtest=PriceCacheReaderTest,PublicQuoteControllerTest`
   → 首次不帶額外參數執行即失敗（`PriceCacheReaderTest` 12 案例全部 `MockitoException: Mockito cannot mock this class: class org.springframework.data.redis.core.StringRedisTemplate`，訊息含 `Java 25 (69) is not supported by the current version of Byte Buddy`）。改用既知解法 `-DextraArgLine=-Dnet.bytebuddy.experimental=true` 後 exit code 0；surefire 報告：`PriceCacheReaderTest` `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`、`PublicQuoteControllerTest` `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`（合計 12 案例全數通過）。確認 `external-materials-service/pom.xml` 的 `extraArgLine` 屬性預設為空字串（第 23 行 `<extraArgLine></extraArgLine>`），該 flag **並未**預設帶入，每次執行都需顯式傳入。

2. `mvn -q -f external-materials-service/pom.xml test`（全模組）
   → 同樣不帶參數先跑一次會失敗（同一 byte-buddy 相容性問題，非本次新增邏輯的缺陷，凡 mock 具體類別的既有測試如 `PriceCacheWriterTest` 也一併受影響）；加上 `-DextraArgLine=-Dnet.bytebuddy.experimental=true` 後 exit code 0。彙總 54 個測試類、`Tests run: 311, Failures: 0, Errors: 0, Skipped: 0`（`grep -l FAILURE target/surefire-reports/*.txt` 無命中）。

3. `bash scripts/spec-check.sh`
   → `BLOCK: 0   CHECK: 0`（變更檔數 11，含未追蹤新檔）。312.4 附註預期的 B5 誤判（新測試類建立前查無）未出現——實作完成後兩個類別已存在於樹上，B5 未再誤報。

4. `docker compose -p asset-management config -q`
   → 本 worktree 原無 `.env`（既知 worktree 缺口，比照既有慣例從 `/Users/steven/Project/asset-management-main/.env` 複製一份進來，`.env` 屬 `.gitignore` 排除項不影響 git 狀態）；`INTERNAL_TREASURY_TOKEN` 為本機 secret environment 變數、本 shell session 未設定，以 `INTERNAL_TREASURY_TOKEN=local-validation-only-placeholder` 臨時帶入（僅供本次語法驗證，未寫入任何檔案、非真實密鑰）後 exit code 0。另外用不帶 `-q` 的 `docker compose config` 印出 `external-materials-service` 完整解析結果核對 `ports` 區塊，確認解析為 `host_ip: 127.0.0.1, target: 8080, published: "8082", protocol: tcp`，與 312.3 預期一致。

**與原計畫的偏差：**

程式碼與 YAML 本身無偏差，312.1–312.5 全部逐字採用任務檔給的完整片段／指令位置，未自行改寫任何邏輯或欄位順序。

唯一偏差是 **Maven 執行方式與 docker compose 驗證的前置環境準備**：
- 任務檔「驗證」段落給的指令未帶 JVM 參數，但本機 JDK 25 + Mockito inline mock maker（byte-buddy 1.15.11）組合下，`PriceCacheReaderTest` mock 了具體類別 `StringRedisTemplate`，會在 `setUp()` 就以 byte-buddy 版本不相容失敗；改用 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`（未使用 `-DargLine`，避免覆蓋 surefire 既有的 `-Duser.timezone=Asia/Taipei`）後全綠。此為本機環境既知問題，與本次程式邏輯無關，任務檔「驗證」段落已預告此解法。
- `docker compose config -q` 需要 `.env`（本 worktree 原缺，已從主 repo 複製）與 `INTERNAL_TREASURY_TOKEN` 這個必填 secret env var（本 shell 未設定，以臨時 placeholder 值帶入僅供語法驗證）才能完整 interpolate；這兩者皆為 docker-compose.yml 既有、非本任務新增的必填項（`INTERNAL_TREASURY_TOKEN` 的 `:?` 必填語法在 312.3 改動前即存在於第 70、157 行），不影響本次改動本身的正確性判定。

**未執行（依上游指示屬 `/run-stack` 後續階段工作範圍）：**
image rebuild（`docker compose build --no-cache external-materials-service`）、container recreate、host `curl http://127.0.0.1:8082/api/quotes` 等實際連線驗證、`docker exec ... /internal/health` 交叉確認，本次未執行。
