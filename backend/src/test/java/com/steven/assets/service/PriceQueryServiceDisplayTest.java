package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceQueryServiceDisplayTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private StockPriceHistoryRepository history;
    private MarketDataService calendar;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        history = mock(StockPriceHistoryRepository.class);
        calendar = mock(MarketDataService.class);
        when(calendar.isTradingDay(any(), any())).thenAnswer(invocation -> {
            LocalDate date = invocation.getArgument(1);
            return date.getDayOfWeek().getValue() <= 5;
        });
    }

    @Test
    void afterCloseUsesExactTrustedDbCloseInsteadOfRedisLastTick() {
        PriceQueryService service = serviceAt("2026-08-07T06:30:00Z"); // 台北 14:30
        when(values.get("price:台股:2330")).thenReturn(redisRow("1198", "LIVE"));
        when(history.findByStockCodeAndMarketAndTradingDate("2330", "台股", LocalDate.of(2026, 8, 7)))
                .thenReturn(Optional.of(historyRow("1205", "TWSE_MI_INDEX")));

        PriceQueryService.LivePrice result = service.getDisplayPrice("2330", "台股").orElseThrow();

        assertThat(result.price()).isEqualByComparingTo("1205");
        assertThat(result.quoteStatus()).isEqualTo("VERIFIED_CLOSE");
        assertThat(result.source()).isEqualTo("TWSE_MI_INDEX");
    }

    @Test
    void afterCloseReturnsPendingWhenExactTrustedCloseIsMissing() {
        PriceQueryService service = serviceAt("2026-08-07T06:30:00Z");
        when(history.findByStockCodeAndMarketAndTradingDate("2330", "台股", LocalDate.of(2026, 8, 7)))
                .thenReturn(Optional.empty());

        PriceQueryService.LivePrice result = service.getDisplayPrice("2330", "台股").orElseThrow();

        assertThat(result.price()).isNull();
        assertThat(result.tradingDate()).isEqualTo("2026-08-07");
        assertThat(result.closed()).isFalse();
        assertThat(result.quoteStatus()).isEqualTo("CLOSE_PENDING");
    }

    @Test
    void afterCloseRejectsExactRowWithoutTrustedSource() {
        PriceQueryService service = serviceAt("2026-08-07T06:30:00Z");
        when(history.findByStockCodeAndMarketAndTradingDate("2330", "台股", LocalDate.of(2026, 8, 7)))
                .thenReturn(Optional.of(historyRow("1198", null)));

        PriceQueryService.LivePrice result = service.getDisplayPrice("2330", "台股").orElseThrow();

        assertThat(result.price()).isNull();
        assertThat(result.quoteStatus()).isEqualTo("CLOSE_PENDING");
    }

    @Test
    void saturdayUsesExactTrustedFridayCloseAsPreviousClose() {
        PriceQueryService service = serviceAt("2026-08-08T02:00:00Z");
        when(history.findByStockCodeAndMarketAndTradingDate("2330", "台股", LocalDate.of(2026, 8, 7)))
                .thenReturn(Optional.of(historyRow("1205", "TWSE_MI_INDEX")));

        PriceQueryService.LivePrice result = service.getDisplayPrice("2330", "台股").orElseThrow();

        assertThat(result.price()).isEqualByComparingTo("1205");
        assertThat(result.tradingDate()).isEqualTo("2026-08-07");
        assertThat(result.quoteStatus()).isEqualTo("PREVIOUS_CLOSE");
    }

    @Test
    void mondayPreOpenAndSaturdayBothTargetFriday() {
        PriceQueryService monday = serviceAt("2026-08-10T00:00:00Z"); // 台北一 08:00
        PriceQueryService saturday = serviceAt("2026-08-08T02:00:00Z"); // 台北六 10:00

        assertThat(monday.displaySession("台股").targetTradingDate()).isEqualTo(LocalDate.of(2026, 8, 7));
        assertThat(saturday.displaySession("台股").targetTradingDate()).isEqualTo(LocalDate.of(2026, 8, 7));
    }

    @Test
    void openSessionAcceptsOnlyRedisRowForMarketToday() {
        PriceQueryService service = serviceAt("2026-08-07T02:00:00Z"); // 台北 10:00
        when(values.get("price:台股:2330")).thenReturn(redisRow("1198", "LIVE"));

        PriceQueryService.LivePrice result = service.getDisplayPrice("2330", "台股").orElseThrow();

        assertThat(result.price()).isEqualByComparingTo("1198");
        assertThat(result.quoteStatus()).isEqualTo("LIVE");
    }

    @Test
    void nonTwMarketsKeepExistingRedisFirstDisplaySemantics() {
        PriceQueryService service = serviceAt("2026-08-08T02:00:00Z");
        when(values.get("price:美股:NVDA")).thenReturn("""
                {"stockCode":"NVDA","market":"美股","price":182.40,
                 "tradingDate":"2026-08-07","updatedAt":"2026-08-07T16:00:00",
                 "closed":false,"source":"NASDAQ","quoteStatus":"LIVE"}
                """);

        PriceQueryService.LivePrice result = service.getDisplayPrice("NVDA", "美股").orElseThrow();

        assertThat(result.price()).isEqualByComparingTo("182.40");
        assertThat(result.tradingDate()).isEqualTo("2026-08-07");
        assertThat(result.quoteStatus()).isEqualTo("LIVE");
    }

    @Test
    void suppliedDisplayKeysNeverReadOrUnionGlobalRedisIndexes() {
        PriceQueryService service = serviceAt("2026-08-08T02:00:00Z");
        when(values.get("price:美股:NVDA")).thenReturn("""
                {"stockCode":"NVDA","market":"美股","price":182.40,
                 "tradingDate":"2026-08-07","updatedAt":"2026-08-07T16:00:00",
                 "closed":false,"source":"NASDAQ","quoteStatus":"LIVE"}
                """);

        var result = service.getAllDisplayPrices(Set.of(new PriceQueryService.PriceKey("NVDA", "美股")));

        assertThat(result).singleElement().extracting(PriceQueryService.LivePrice::stockCode).isEqualTo("NVDA");
        verify(redis, never()).opsForSet();
    }

    private PriceQueryService serviceAt(String instant) {
        return new PriceQueryService(redis, history, "http://localhost", calendar,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static StockPriceHistory historyRow(String close, String source) {
        return StockPriceHistory.builder()
                .stockCode("2330").market("台股").tradingDate(LocalDate.of(2026, 8, 7))
                .closePrice(new BigDecimal(close)).closeSource(source).build();
    }

    private static String redisRow(String price, String status) {
        return """
                {"stockCode":"2330","market":"台股","price":%s,
                 "tradingDate":"2026-08-07","updatedAt":"2026-08-07T10:00:00",
                 "closed":false,"source":"TWSE","quoteStatus":"%s"}
                """.formatted(price, status);
    }
}
