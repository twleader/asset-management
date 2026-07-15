package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.KrIntradayQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link KrIntradayFetchClient} 雙閘門與 graceful 行為（Task 193）。
 *
 * <p>時點以固定 {@link Clock} 注入。2026-07-15 為週三；台北 08:20 ＝ {@code 2026-07-15T00:20:00Z}
 * （＝09:20 KST，韓股開盤後 20 分鐘，正是 NewsPoller 早上那輪的時點）。
 */
class KrIntradayFetchClientTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");

    /** 台北 2026-07-15（週三）08:20 ＝ 09:20 KST，韓股盤中。 */
    private static final Instant WED_0820_TPE = Instant.parse("2026-07-15T00:20:00Z");
    /** 台北 2026-07-15（週三）18:00 ＝ 19:00 KST，韓股已收盤（NewsPoller 晚上那輪）。 */
    private static final Instant WED_1800_TPE = Instant.parse("2026-07-15T10:00:00Z");
    /** 台北 2026-07-18（週六）08:20，韓股休市。 */
    private static final Instant SAT_0820_TPE = Instant.parse("2026-07-18T00:20:00Z");

    private static final LocalDate TODAY_KST = LocalDate.of(2026, 7, 15);

    private PriceFetchClient priceFetch;

    @BeforeEach
    void setUp() {
        priceFetch = mock(PriceFetchClient.class);
    }

    private KrIntradayFetchClient clientAt(Instant now) {
        return new KrIntradayFetchClient(priceFetch, Clock.fixed(now, TW));
    }

    private static KrIntradayQuote quote(String symbol, String name, String price, String open,
                                         String prevClose, String changePct, LocalDate sessionDate) {
        return new KrIntradayQuote(symbol, name, new BigDecimal(price), new BigDecimal(open),
                new BigDecimal(prevClose), new BigDecimal(changePct), sessionDate);
    }

    private void stubAllThreeForToday() {
        when(priceFetch.fetchKrIntradayQuote("^KS11")).thenReturn(Optional.of(
                quote("^KS11", "KOSPI Composite Index", "7284.41", "7082.91", "6856.83", "6.2378", TODAY_KST)));
        when(priceFetch.fetchKrIntradayQuote("005930.KS")).thenReturn(Optional.of(
                quote("005930.KS", "SamsungElec", "279500", "283500", "263000", "6.2738", TODAY_KST)));
        when(priceFetch.fetchKrIntradayQuote("000660.KS")).thenReturn(Optional.of(
                quote("000660.KS", "SK hynix", "195000", "196000", "196500", "-0.7634", TODAY_KST)));
    }

    @Test
    @DisplayName("盤中且資料為今日 KST → 產 1 則，標題含三檔的開盤價與漲跌%")
    void producesSnapshotDuringKrSession() {
        stubAllThreeForToday();

        List<NewsRow> rows = clientAt(WED_0820_TPE).fetchAll();

        assertThat(rows).hasSize(1);
        NewsRow row = rows.get(0);

        assertThat(row.category()).isEqualTo("kr-intraday");
        assertThat(row.source()).isEqualTo("kr-intraday");
        assertThat(row.region()).isEqualTo("KR");

        // 標題時戳為快照當下的 KST（08:20 台北 = 09:20 KST），非 Yahoo 的 regularMarketTime
        assertThat(row.title()).startsWith("韓國股市盤中（2026-07-15 09:20 KST）：");
        // 開盤價是本任務的核心價值：三檔都必須有（取自 indicators.quote[0].open[0]，非 meta.regularMarketOpen）
        assertThat(row.title())
                .contains("KOSPI 7,284.41（開盤 7,082.91，較昨收 +6.24%）")
                .contains("三星電子 279,500.00（開盤 283,500.00，較昨收 +6.27%）")
                .contains("SK海力士 195,000.00（開盤 196,000.00，較昨收 -0.76%）");

        // summary 須與同批 kr-market（前一交易日收盤）消歧，兩則會同時進 prompt
        assertThat(row.summary())
                .contains("盤中即時快照")
                .contains("非當日收盤值")
                .contains("為前一交易日收盤快照");
    }

    @Test
    @DisplayName("時段外（18:00，韓股已收盤）→ 不產出，且完全不打 Yahoo")
    void skipsOutsideKrSession() {
        List<NewsRow> rows = clientAt(WED_1800_TPE).fetchAll();

        assertThat(rows).isEmpty();
        // 時段閘門應在抓取前擋下：18:00 那輪的當日收盤已由既有 kr-market 快照涵蓋，不該重複打 Yahoo
        org.mockito.Mockito.verify(priceFetch, org.mockito.Mockito.never()).fetchKrIntradayQuote(anyString());
    }

    @Test
    @DisplayName("週六 → 不產出（時段閘門先擋週末）")
    void skipsOnWeekend() {
        assertThat(clientAt(SAT_0820_TPE).fetchAll()).isEmpty();
        org.mockito.Mockito.verify(priceFetch, org.mockito.Mockito.never()).fetchKrIntradayQuote(anyString());
    }

    @Test
    @DisplayName("盤中但資料日為昨日（韓國農曆假日）→ 資料閘門擋下，不產出")
    void skipsWhenYahooReturnsStaleSessionDate() {
        LocalDate yesterday = TODAY_KST.minusDays(1);
        when(priceFetch.fetchKrIntradayQuote("^KS11")).thenReturn(Optional.of(
                quote("^KS11", "KOSPI Composite Index", "7284.41", "7082.91", "6856.83", "6.2378", yesterday)));
        when(priceFetch.fetchKrIntradayQuote("005930.KS")).thenReturn(Optional.of(
                quote("005930.KS", "SamsungElec", "279500", "283500", "263000", "6.2738", yesterday)));
        when(priceFetch.fetchKrIntradayQuote("000660.KS")).thenReturn(Optional.of(
                quote("000660.KS", "SK hynix", "195000", "196000", "196500", "-0.7634", yesterday)));

        assertThat(clientAt(WED_0820_TPE).fetchAll()).isEmpty();
    }

    @Test
    @DisplayName("三檔僅 1 檔有今日資料 → 仍產 1 則，只含該檔（逐項 graceful）")
    void gracefulWhenSomeSymbolsMissing() {
        when(priceFetch.fetchKrIntradayQuote("^KS11")).thenReturn(Optional.empty());
        when(priceFetch.fetchKrIntradayQuote("005930.KS")).thenReturn(Optional.of(
                quote("005930.KS", "SamsungElec", "279500", "283500", "263000", "6.2738", TODAY_KST)));
        when(priceFetch.fetchKrIntradayQuote("000660.KS")).thenThrow(new RuntimeException("Yahoo 429"));

        List<NewsRow> rows = clientAt(WED_0820_TPE).fetchAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title())
                .contains("三星電子 279,500.00")
                .doesNotContain("KOSPI")
                .doesNotContain("SK海力士");
    }

    @Test
    @DisplayName("三檔全失敗 → 不產出（不產空殼標題）")
    void producesNothingWhenAllSymbolsFail() {
        when(priceFetch.fetchKrIntradayQuote(anyString())).thenReturn(Optional.empty());

        assertThat(clientAt(WED_0820_TPE).fetchAll()).isEmpty();
    }

    @Test
    @DisplayName("缺昨收（漲跌% 為 null）→ 仍產出，漲跌% 顯示破折號")
    void rendersDashWhenChangePctMissing() {
        when(priceFetch.fetchKrIntradayQuote("^KS11")).thenReturn(Optional.of(
                new KrIntradayQuote("^KS11", "KOSPI Composite Index",
                        new BigDecimal("7284.41"), new BigDecimal("7082.91"), null, null, TODAY_KST)));
        when(priceFetch.fetchKrIntradayQuote("005930.KS")).thenReturn(Optional.empty());
        when(priceFetch.fetchKrIntradayQuote("000660.KS")).thenReturn(Optional.empty());

        List<NewsRow> rows = clientAt(WED_0820_TPE).fetchAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title()).contains("KOSPI 7,284.41（開盤 7,082.91，較昨收 —）");
    }
}
