# [t306] 台幣兌美元匯率新增兆豐銀行備援層（台銀 → 兆豐 → Yahoo → FinMind）

**對應 Requirements:** Requirement 10（匯率歷史查詢——匯率來源鏈與當日 T-0 備援）
**前置任務:** 無
**Liquibase changeset:** 無（`exchange_rate_history` 表結構不變，見下方說明）

## 背景

目前 USD/TWD 匯率抓取邏輯全部在 `external-materials-service`，由 `ExchangeRatePoller`
串接兩層當日來源：`BotFxFetchClient`（台灣銀行牌告 CSV，主來源，真實即期買入/賣出）失敗時，
若幣別為 USD，退到 `YahooFxFetchClient`（Yahoo Finance `TWD=X`，僅中間價，`buy=sell=mid`）。
台銀 2026/06 起被 Akamai WAF 反爬挑戰封鎖，一旦擋下，USD 以外幣別（目前系統只追蹤 USD、ZAR
兩種，見 `StockSourceQuery.collectTrackedCurrencies()`）當日完全沒有備援，只能等 17:00
FinMind 對帳回補（T-1）。

使用者要求新增「抓不到台銀就改抓兆豐銀行」的備援層，插在台銀與 Yahoo 之間。原本也評估納入
中國信託商業銀行作為第三備援層，但實測發現其牌告頁面（`ctbcbank.com/content/twrbo/zh_tw/
dep_index/dep_ratequery/dep_foreign_rates.html`）為純前端渲染，即期買賣價數字不存在於原始
HTML；且全站（含理應是靜態內容的 JSON）皆套用動態簽章 token（`?IIhfvu=...` 查詢參數）反爬蟲
機制，用與 `BotFxFetchClient` 相同的「curl 子程序 + 短 UA」手法實測只拿到不含匯率數字的
18KB 空殼 HTML。要抓取需改用 headless browser 渲染，這會為 `external-materials-service`
引入目前完全沒有的重量級依賴，且盤中 5 分鐘排程每輪都要開瀏覽器渲染，與現有輕量 curl 架構
落差過大——**本任務範圍不含中國信託，僅新增兆豐銀行這一層**。

兆豐銀行的匯率頁面（`https://www.megabank.com.tw/personal/foreign-service/forex`）背後有
乾淨的公開 JSON REST API，已用 curl 實測成功（見「要做什麼」的 URL 與回應格式）：免登入、
免 token、無反爬阻擋，回應格式清楚，涵蓋目前系統追蹤的所有幣別（USD、ZAR 皆有 `spot` 值），
且與台銀一樣是真實即期買入/賣出價，不是中間價——資料品質與台銀同級，不像 Yahoo 只能給
被抹平的中間價。因為兆豐與台銀同屬「真實買賣價」類、沒有新增資料品質分類，`exchange_rate_
history` 現有的「`buy==sell` 隱含標記中間價來源」慣例不受影響，**不需要新增 `source` 欄位、
不需要 Liquibase changeset**（已用 `docker exec asset-postgres psql -U assets -d assets -c
'\d exchange_rate_history'` 確認現況只有 `id / buy_rate / currency / rate_date / sell_rate`
五欄，`(currency, rate_date)` 唯一鍵，DB 現況與 `spec/design.md` 描述一致）。

## 要做什麼

- [x] 306.1 **新增共用型別** `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/FxSpotQuote.java`（全新檔案）：

  ```java
  package com.steven.assets.externalmaterials.client;

  import java.math.BigDecimal;

  /** 銀行即期買入/賣出匯率報價（{@link BotFxFetchClient} / {@link MegaFxFetchClient} 共用）。 */
  public record FxSpotQuote(BigDecimal spotBuy, BigDecimal spotSell) {}
  ```

- [x] 306.2 **修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/BotFxFetchClient.java`**：
  - 刪除內嵌的 `public record SpotQuote(BigDecimal spotBuy, BigDecimal spotSell) {}`（原第 21 行）
  - 方法簽章 `public Optional<SpotQuote> fetchSpot(String currency)` 改為
    `public Optional<FxSpotQuote> fetchSpot(String currency)`
  - 方法內 `return Optional.of(new SpotQuote(spotBuy, spotSell));` 改為
    `return Optional.of(new FxSpotQuote(spotBuy, spotSell));`
  - 原第 39 行註解 `// 明確辨識以免誤報為「找不到 USD」，並交由上游（ExchangeRatePoller）改用 Yahoo 備援。`
    改為 `// 明確辨識以免誤報為「找不到 USD」，並交由上游（ExchangeRatePoller）依序改用兆豐銀行／Yahoo 備援。`
    （新鏈路台銀失敗後下一步是兆豐銀行，不是直接跳 Yahoo，原註解會失真）
  - 其餘邏輯（curl 子程序、CSV 解析、WAF 挑戰頁偵測）完全不動

- [x] 306.3 **新增** `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/MegaFxFetchClient.java`（全新檔案），完整內容：

  ```java
  package com.steven.assets.externalmaterials.client;

  import com.fasterxml.jackson.databind.JsonNode;
  import com.fasterxml.jackson.databind.ObjectMapper;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.stereotype.Component;

  import java.math.BigDecimal;
  import java.math.RoundingMode;
  import java.util.Optional;

  /**
   * 兆豐銀行牌告匯率 API（https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData）。
   *
   * 台銀被 WAF 反爬挑戰封鎖時的第一備援。公開 GET 端點，免登入免 token，回傳 JSON，
   * 涵蓋所有追蹤幣別、含真實即期買入/賣出（非中間價，資料品質同台銀）。用 curl 子程序 + 短 UA，
   * 同 BotFxFetchClient / YahooFxFetchClient 慣例（避開 HTTP client fingerprint 封鎖）。
   */
  @Slf4j
  @Component
  public class MegaFxFetchClient {

      private static final String RATE_URL =
              "https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData?sc_lang=zh-TW&sc_site=bank-zh-tw&dic_lang=zh-TW";

      private final ObjectMapper mapper = new ObjectMapper();

      /** 取指定幣別當日即期買入/賣出；找不到該幣別或抓取失敗回 empty。 */
      public Optional<FxSpotQuote> fetchSpot(String currency) {
          try {
              ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                      "-H", "User-Agent: Mozilla/5.0",
                      RATE_URL);
              pb.redirectErrorStream(true);
              Process proc = pb.start();
              String body = new String(proc.getInputStream().readAllBytes());
              proc.waitFor();

              if (body == null || body.isBlank()) {
                  log.warn("兆豐銀行 API 回傳空白");
                  return Optional.empty();
              }
              JsonNode rates = mapper.readTree(body).path("rates");
              for (JsonNode rate : rates) {
                  String currKey = rate.path("currKey").asText("");
                  int sep = currKey.indexOf('|');
                  String code = sep >= 0 ? currKey.substring(0, sep) : currKey;
                  if (!currency.equalsIgnoreCase(code)) continue;
                  JsonNode spot = rate.path("spot");
                  BigDecimal bid = decimal(spot.path("bid"));
                  BigDecimal ask = decimal(spot.path("ask"));
                  if (bid == null || ask == null) {
                      log.warn("兆豐銀行 {} 即期匯率解析失敗: bid=[{}], ask=[{}]",
                              currency, spot.path("bid"), spot.path("ask"));
                      return Optional.empty();
                  }
                  return Optional.of(new FxSpotQuote(bid, ask));
              }
              log.warn("兆豐銀行 API 找不到 {} 的匯率資料", currency);
              return Optional.empty();
          } catch (Exception e) {
              log.warn("兆豐銀行匯率抓取失敗 ({}): {}", currency, e.getMessage());
              return Optional.empty();
          }
      }

      private static BigDecimal decimal(JsonNode n) {
          if (n == null || n.isMissingNode() || n.isNull()) return null;
          String s = n.asText("");
          if (s.isBlank()) return null;
          try {
              return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
          } catch (NumberFormatException e) {
              return null;
          }
      }
  }
  ```

  參考：實測回應格式（2026/08/09 19:04 抓取，逐欄位皆已核對）——
  `{"updateTime":"2026/08/09 19:04:11","rates":[{"currKey":"USD|01","spot":{"bid":"32.2500","ask":"32.3500","bidOld":"32.2600","askOld":"32.3600","bidStatus":"o-price--fall","askStatus":"o-price--fall"},"cash":{"bid":"31.9100","ask":"32.5800",...},"update":"20260809190411"},{"currKey":"ZAR|27","spot":{"bid":"1.9600","ask":"2.0600",...},"cash":{"bid":"","ask":"",...},"update":"..."},...]}`
  → `spot.bid` = 銀行即期買入（對應 `spotBuy`），`spot.ask` = 銀行即期賣出（對應 `spotSell`），
  與 `BotFxFetchClient` 既有的 buy/sell 語意一致。ZAR 的 `spot` 有值，只有 `cash`（現金匯率）
  是空字串——本任務只用 `spot`，不受影響。`currKey` 格式固定為 `"{ISO幣別}|{兩位數字代碼}"`。

- [x] 306.4 **修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/ExchangeRatePoller.java`**，改為以下完整內容（欄位順序 `botFx, megaFx, yahooFx, backfill, store` 對應 `@RequiredArgsConstructor` 產生的建構子參數順序，306.5 的測試會依此順序呼叫建構子）：

  ```java
  package com.steven.assets.externalmaterials.service;

  import com.steven.assets.externalmaterials.client.BotFxFetchClient;
  import com.steven.assets.externalmaterials.client.FxSpotQuote;
  import com.steven.assets.externalmaterials.client.MegaFxFetchClient;
  import com.steven.assets.externalmaterials.client.YahooFxFetchClient;
  import lombok.RequiredArgsConstructor;
  import lombok.extern.slf4j.Slf4j;
  import org.springframework.scheduling.annotation.Scheduled;
  import org.springframework.stereotype.Service;

  import java.math.BigDecimal;
  import java.time.LocalDate;
  import java.time.ZoneId;
  import java.time.ZonedDateTime;
  import java.util.Optional;

  /**
   * 匯率排程：盤中每 5 分鐘 台銀 → 兆豐銀行 → USD 才退 Yahoo 當日中間價；收盤後 17:00 FinMind（T-1 對帳回補）。
   *
   * 從 business-services HistoricalDataService 搬遷至此。所有對外行情 API 集中在 external-materials-service。
   *
   * 來源鏈（見 design.md「匯率來源鏈」、Task 142、Task 306）：台銀牌告 {@link BotFxFetchClient} 為當日主來源
   * （真實即期買賣）；台銀失敗時改抓兆豐銀行牌告 API {@link MegaFxFetchClient}（同樣真實即期買賣、涵蓋所有
   * 追蹤幣別）；兩者皆失敗時，USD 改用 {@link YahooFxFetchClient} 當日中間價暫定（buy=sell=mid），隔日由
   * 17:00 FinMind 以真實買賣價覆寫同一 (currency, rate_date) 列。
   */
  @Slf4j
  @Service
  @RequiredArgsConstructor
  public class ExchangeRatePoller {

      private final BotFxFetchClient botFx;
      private final MegaFxFetchClient megaFx;
      private final YahooFxFetchClient yahooFx;
      private final HistoricalBackfillService backfill;
      private final StockSourceQuery store;

      /**
       * 盤中匯率：每 5 分鐘從台銀牌告抓即期匯率（失敗改兆豐銀行），寫入今日 exchange_rate_history（同 currency+rate_date 為覆寫）。
       * 台灣外匯交易：週一~五 09:00~16:00 Asia/Taipei；09:00 整點跳過避開市場未開盤。
       */
      @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")
      public void intradayExchangeRateUpdate() {
          ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Taipei"));
          if (now.getHour() == 9 && now.getMinute() < 5) return;
          log.info("排程：盤中更新匯率（台灣銀行 → 兆豐銀行） ({}:{})", now.getHour(),
                  String.format("%02d", now.getMinute()));
          LocalDate today = now.toLocalDate();
          for (String currency : store.collectTrackedCurrencies()) {
              updateOne(currency, today);
          }
      }

      /**
       * 收盤後匯率：17:00 走 FinMind TaiwanExchangeRate 增量補（涵蓋 BOT/兆豐沒抓到 / 開盤前缺漏）。
       */
      @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
      public void dailyExchangeRateUpdate() {
          log.info("排程：收盤後 FinMind 增量補匯率");
          LocalDate since = LocalDate.now().minusDays(5);
          for (String currency : store.collectTrackedCurrencies()) {
              backfill.backfillExchangeRate(currency, since);
          }
      }

      /** 手動觸發（business-services /api/market-data/exchange-rate/refresh proxy）。 */
      public boolean refreshBotNow(String currency) {
          return updateOne(currency, LocalDate.now(ZoneId.of("Asia/Taipei")));
      }

      /**
       * 抓單一幣別當日即期匯率寫入今日列。
       * 優先台銀牌告（含真實即期買入/賣出）；台銀失敗時改抓兆豐銀行牌告（同樣含真實即期買入/賣出，
       * 涵蓋所有追蹤幣別）；兩者皆失敗時，USD 才退到 Yahoo 當日中間價暫定（buy=sell=mid），隔日 17:00
       * FinMind 會以真實買賣價覆寫同一 (currency, rate_date) 列。USD 以外幣別若台銀與兆豐皆缺值，
       * 不另尋備援，接受沿用 FinMind 的 T-1 值。
       *
       * package-private（非 private）供同套件測試直接呼叫、注入固定日期。
       *
       * @return 是否成功寫入今日列（台銀、兆豐、Yahoo 任一）
       */
      boolean updateOne(String currency, LocalDate today) {
          Optional<FxSpotQuote> bot = botFx.fetchSpot(currency);
          if (bot.isPresent()) {
              upsertSpot("台灣銀行", currency, today, bot.get());
              return true;
          }
          Optional<FxSpotQuote> mega = megaFx.fetchSpot(currency);
          if (mega.isPresent()) {
              upsertSpot("兆豐銀行", currency, today, mega.get());
              return true;
          }
          if ("USD".equals(currency)) {
              Optional<BigDecimal> mid = yahooFx.fetchUsdTwdMid();
              if (mid.isPresent()) {
                  store.upsertExchangeRate(currency, today, mid.get(), mid.get());
                  log.info("Yahoo 備援 {} 當日中間價（買=賣=中間價，待 FinMind 隔日覆寫）: {} ({})",
                          currency, mid.get(), today);
                  return true;
              }
          }
          return false;
      }

      private void upsertSpot(String sourceName, String currency, LocalDate today, FxSpotQuote q) {
          store.upsertExchangeRate(currency, today, q.spotBuy(), q.spotSell());
          log.info("{} {} 匯率: buy={}, sell={} ({})", sourceName, currency, q.spotBuy(), q.spotSell(), today);
      }
  }
  ```

- [x] 306.5 **新增測試** `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/ExchangeRatePollerTest.java`（全新檔案，此類別目前完全沒有測試覆蓋，`HistoricalBackfillService`／`StockSourceQuery` 與 `ExchangeRatePoller` 同套件 `com.steven.assets.externalmaterials.service`，無需 import）：

  ```java
  package com.steven.assets.externalmaterials.service;

  import com.steven.assets.externalmaterials.client.BotFxFetchClient;
  import com.steven.assets.externalmaterials.client.FxSpotQuote;
  import com.steven.assets.externalmaterials.client.MegaFxFetchClient;
  import com.steven.assets.externalmaterials.client.YahooFxFetchClient;
  import org.junit.jupiter.api.BeforeEach;
  import org.junit.jupiter.api.DisplayName;
  import org.junit.jupiter.api.Test;

  import java.math.BigDecimal;
  import java.time.LocalDate;
  import java.util.Optional;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.mockito.ArgumentMatchers.any;
  import static org.mockito.ArgumentMatchers.anyString;
  import static org.mockito.Mockito.mock;
  import static org.mockito.Mockito.never;
  import static org.mockito.Mockito.verify;
  import static org.mockito.Mockito.when;

  /**
   * {@link ExchangeRatePoller} 台銀 → 兆豐銀行 → Yahoo 備援鏈順序（Task 306）。
   */
  class ExchangeRatePollerTest {

      private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

      private BotFxFetchClient botFx;
      private MegaFxFetchClient megaFx;
      private YahooFxFetchClient yahooFx;
      private HistoricalBackfillService backfill;
      private StockSourceQuery store;
      private ExchangeRatePoller poller;

      @BeforeEach
      void setUp() {
          botFx = mock(BotFxFetchClient.class);
          megaFx = mock(MegaFxFetchClient.class);
          yahooFx = mock(YahooFxFetchClient.class);
          backfill = mock(HistoricalBackfillService.class);
          store = mock(StockSourceQuery.class);
          poller = new ExchangeRatePoller(botFx, megaFx, yahooFx, backfill, store);
      }

      @Test
      @DisplayName("台銀成功 → 直接採用，不打兆豐與 Yahoo")
      void usesBotWhenAvailable() {
          when(botFx.fetchSpot("USD")).thenReturn(
                  Optional.of(new FxSpotQuote(new BigDecimal("32.25"), new BigDecimal("32.35"))));

          boolean result = poller.updateOne("USD", TODAY);

          assertThat(result).isTrue();
          verify(store).upsertExchangeRate("USD", TODAY, new BigDecimal("32.25"), new BigDecimal("32.35"));
          verify(megaFx, never()).fetchSpot(anyString());
          verify(yahooFx, never()).fetchUsdTwdMid();
      }

      @Test
      @DisplayName("台銀失敗、兆豐成功 → 採用兆豐真實買賣價，不打 Yahoo")
      void fallsBackToMegaWhenBotEmpty() {
          when(botFx.fetchSpot("USD")).thenReturn(Optional.empty());
          when(megaFx.fetchSpot("USD")).thenReturn(
                  Optional.of(new FxSpotQuote(new BigDecimal("32.20"), new BigDecimal("32.40"))));

          boolean result = poller.updateOne("USD", TODAY);

          assertThat(result).isTrue();
          verify(store).upsertExchangeRate("USD", TODAY, new BigDecimal("32.20"), new BigDecimal("32.40"));
          verify(yahooFx, never()).fetchUsdTwdMid();
      }

      @Test
      @DisplayName("台銀、兆豐皆失敗，USD → 退到 Yahoo 中間價（買=賣=中間價）")
      void fallsBackToYahooMidForUsdWhenBotAndMegaEmpty() {
          when(botFx.fetchSpot("USD")).thenReturn(Optional.empty());
          when(megaFx.fetchSpot("USD")).thenReturn(Optional.empty());
          when(yahooFx.fetchUsdTwdMid()).thenReturn(Optional.of(new BigDecimal("32.30")));

          boolean result = poller.updateOne("USD", TODAY);

          assertThat(result).isTrue();
          verify(store).upsertExchangeRate("USD", TODAY, new BigDecimal("32.30"), new BigDecimal("32.30"));
      }

      @Test
      @DisplayName("兆豐涵蓋非 USD 幣別（如 ZAR）：台銀失敗時一樣 fallback 到兆豐")
      void megaFallbackAppliesToNonUsdCurrency() {
          when(botFx.fetchSpot("ZAR")).thenReturn(Optional.empty());
          when(megaFx.fetchSpot("ZAR")).thenReturn(
                  Optional.of(new FxSpotQuote(new BigDecimal("1.96"), new BigDecimal("2.06"))));

          boolean result = poller.updateOne("ZAR", TODAY);

          assertThat(result).isTrue();
          verify(store).upsertExchangeRate("ZAR", TODAY, new BigDecimal("1.96"), new BigDecimal("2.06"));
      }

      @Test
      @DisplayName("非 USD 幣別、台銀與兆豐皆失敗 → 不打 Yahoo（僅 USD 有中間價備援），回傳 false")
      void nonUsdCurrencyDoesNotFallBackToYahoo() {
          when(botFx.fetchSpot("ZAR")).thenReturn(Optional.empty());
          when(megaFx.fetchSpot("ZAR")).thenReturn(Optional.empty());

          boolean result = poller.updateOne("ZAR", TODAY);

          assertThat(result).isFalse();
          verify(yahooFx, never()).fetchUsdTwdMid();
          verify(store, never()).upsertExchangeRate(anyString(), any(), any(), any());
      }

      @Test
      @DisplayName("三層皆失敗（USD）→ 回傳 false，不寫入")
      void allSourcesFailReturnsFalse() {
          when(botFx.fetchSpot("USD")).thenReturn(Optional.empty());
          when(megaFx.fetchSpot("USD")).thenReturn(Optional.empty());
          when(yahooFx.fetchUsdTwdMid()).thenReturn(Optional.empty());

          boolean result = poller.updateOne("USD", TODAY);

          assertThat(result).isFalse();
      }
  }
  ```

- [x] 306.6 **修改 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`**
  第 163-165 行（「匯率」「即期匯率（盤中）」那筆 `ScheduledJobDto`）的說明文字，把：

  ```java
  new ScheduledJobDto(EXTERNAL, "匯率", "即期匯率（盤中）",
          "盤中每 5 分鐘從台銀牌告抓即期匯率",
          "交易日 09:00–15:55 每 5 分鐘", "0 0/5 9-15 * * MON-FRI", TPE),
  ```

  改為：

  ```java
  new ScheduledJobDto(EXTERNAL, "匯率", "即期匯率（盤中）",
          "盤中每 5 分鐘抓即期匯率（台銀優先，失敗改兆豐銀行；兩者皆失敗且為 USD 才退回 Yahoo 中間價）",
          "交易日 09:00–15:55 每 5 分鐘", "0 0/5 9-15 * * MON-FRI", TPE),
  ```

  cron 表達式與觸發時機**不變**（本任務未新增或修改任何 `@Scheduled` 方法，只改既有方法內部的
  fallback 邏輯），只改第二個參數（說明文字），其餘三個參數原樣保留。第 166-168 行「匯率收盤補抓」
  那筆不動。

- [x] 306.7 **不修改**：`backend/src/main/java/com/steven/assets/service/HistoricalDataService.java`
  的 `fetchBotExchangeRate()` 方法名稱維持不變——它只是對 `external-materials-service`
  `POST /internal/exchange-rate/refresh-bot` 的 HTTP proxy 呼叫，方法名稱雖然還留著「Bot」字樣，
  但語意上觸發的是整條 fallback 鏈（本任務未變更這條 proxy 路徑的 URL 或行為），重新命名
  屬於不必要的擴大改動，不在本任務範圍內。

## 驗證

```bash
# 1. 單元測試（含新增的 ExchangeRatePollerTest 五個案例）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test -Dtest=ExchangeRatePollerTest

# 2. 全模組測試不因本次變更破版（BotFxFetchClient 的 SpotQuote → FxSpotQuote 型別搬遷，確認沒有其他遺漏的呼叫端）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test

# 3. image rebuild + container recreate（本專案沒有 dev server，改好的定義見 .claude/skills/run-stack）
docker compose -p asset-management build --no-cache external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service bff
curl -s http://localhost:8080/actuator/health

# 4. 手動觸發一次匯率刷新，確認鏈路真的跑得動（依序覆寫 exchange_rate_history 今日列）
curl -s -X POST http://localhost:8080/api/market-data/exchange-rate/refresh
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT currency, rate_date, buy_rate, sell_rate FROM exchange_rate_history WHERE currency='USD' ORDER BY rate_date DESC LIMIT 1"

# 5. 確認兆豐銀行 API 本身仍可用（外部依賴，非本專案程式碼問題時的排除法）
curl -s -H "User-Agent: Mozilla/5.0" \
  "https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData?sc_lang=zh-TW&sc_site=bank-zh-tw&dic_lang=zh-TW" \
  | head -c 300

# 6. 排程列表頁文案確認同步（肉眼核對，非自動化）
docker compose -p asset-management logs bff --tail 20 | grep -i error || true
```

## 完成報告

**實際改了哪些檔：**

新增：
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/FxSpotQuote.java`（306.1，全新共用 record）
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/MegaFxFetchClient.java`（306.3，兆豐銀行牌告 API client）
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/ExchangeRatePollerTest.java`（306.5，六案例）

修改：
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/BotFxFetchClient.java`（306.2，刪內嵌 `SpotQuote` record、方法簽章與回傳改用 `FxSpotQuote`、第 39 行註解更新為「依序改用兆豐銀行／Yahoo 備援」）
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/ExchangeRatePoller.java`（306.4，整檔改為台銀 → 兆豐銀行 → USD 才退 Yahoo 三層 fallback，`updateOne` 由 `private` 改 package-private）
- `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java`（306.6，僅改「即期匯率（盤中）」那筆的 description 參數，cron 與其餘三個參數原樣保留）

未修改（依 306.7 明確排除）：
- `backend/src/main/java/com/steven/assets/service/HistoricalDataService.java` 的 `fetchBotExchangeRate()`——已用 `git diff --stat` 確認本次改動完全未觸及此檔。

**Maven 驗證輸出（皆加 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`，理由見下方偏差說明）：**

1. `mvn -q -f external-materials-service/pom.xml test -Dtest=ExchangeRatePollerTest`
   → exit code 0；surefire report `ExchangeRatePollerTest.txt`：`Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`（六案例：台銀成功／台銀失敗兆豐成功／台銀兆豐皆敗 USD 退 Yahoo／兆豐涵蓋 ZAR／非 USD 不退 Yahoo／三層皆敗回 false，全數通過）。

2. `mvn -q -f external-materials-service/pom.xml test`（全模組）
   → exit code 0；彙總 39 個測試類、`Tests run: 246, Failures: 0, Errors: 0, Skipped: 0`。確認 `SpotQuote`→`FxSpotQuote` 型別搬遷後，`grep -rn "\bSpotQuote\b"`（排除 `FxSpotQuote`）在整個 worktree 中無殘留裸露引用，無遺漏呼叫端。

3. `mvn -q -f bff/pom.xml test`（全模組）
   → exit code 0；彙總 8 個測試類、`Tests run: 33, Failures: 0, Errors: 0, Skipped: 0`。`SchedulePublicBffControllerTest` 單獨結果：`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`——該測試不斷言「即期匯率（盤中）」的確切文案內容（只驗總筆數 49／19／30、含 Excel／JSON 字樣的說明是否帶新措辭、交易日曆不再宣稱二選一），故本次文案異動不需要同步改測試斷言。

**與原計畫的偏差：**

程式碼本身無偏差，306.1–306.6 全部逐字採用任務檔給的完整程式碼區塊，未自行改寫任何邏輯。

唯一偏差是 **Maven 執行方式**：任務檔「驗證」段落給的指令未帶 JVM 參數，但本機 JDK 25 + Mockito inline mock maker（byte-buddy 1.15.11）組合下，任何一支 mock 了具體類別（非介面）的測試——包括新增的 `ExchangeRatePollerTest`（mock `BotFxFetchClient`／`MegaFxFetchClient` 等具體類別）——會在 `setUp()` 就以
`MockitoException: Could not modify all classes ... Java 25 (69) is not supported by the current version of Byte Buddy`
失敗（六案例全部 ERROR）。這是本機環境既有問題，與本次改動的程式邏輯無關；改用
`-DextraArgLine=-Dnet.bytebuddy.experimental=true` 後六案例全綠。三次 mvn 指令因此均補上此參數（未使用 `-DargLine`，避免覆蓋 surefire 既有的 `-Duser.timezone=Asia/Taipei`）。程式碼與測試檔內容本身與任務檔給定版本逐字相同，未因此更動任何一行 Java 原始碼。
