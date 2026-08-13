package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsdTwdLiveRateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-13T15:10:14Z");

    private UsdTwdLiveRateCachePort cache;
    private HistoricalDataService history;
    private UsdTwdLiveRateService service;

    @BeforeEach
    void setUp() {
        cache = mock(UsdTwdLiveRateCachePort.class);
        history = mock(HistoricalDataService.class);
        service = new UsdTwdLiveRateService(
                cache, history, Clock.fixed(NOW, ZoneId.of("Asia/Taipei")));
    }

    @Test
    void activeFreshMegaIsLiveAtSixSecondBoundary() {
        when(cache.readSnapshot()).thenReturn(snapshot(
                heartbeat(NOW.minusSeconds(6), "MEGA_BANK"),
                megaSpot(NOW.minusSeconds(6), NOW.minusSeconds(7))));

        var response = service.getLiveRate();

        assertThat(response.liveUpdateStatus()).isEqualTo("ACTIVE");
        assertThat(response.quoteStatus()).isEqualTo("LIVE");
        assertThat(response.source()).isEqualTo("MEGA_BANK");
        verify(history, never()).getLatestExchangeRate("USD");
    }

    @Test
    void activeFreshBotIsLiveAndActiveFreshYahooIsIndicative() {
        when(cache.readSnapshot()).thenReturn(snapshot(
                heartbeat(NOW, "BANK_OF_TAIWAN"),
                botSpot(NOW.minusSeconds(1))));
        assertThat(service.getLiveRate().quoteStatus()).isEqualTo("LIVE");

        when(cache.readSnapshot()).thenReturn(snapshot(
                heartbeat(NOW, "MEGA_BANK"),
                yahooSpot(NOW.minusSeconds(1), NOW.minusSeconds(2))));
        assertThat(service.getLiveRate().quoteStatus()).isEqualTo("INDICATIVE");
    }

    @Test
    void activeStaleQuoteIsStaleAndInactiveKeepsLastAvailableCache() {
        when(cache.readSnapshot()).thenReturn(snapshot(
                heartbeat(NOW, "MEGA_BANK"), megaSpot(NOW.minusSeconds(7), NOW.minusSeconds(8))));
        assertThat(service.getLiveRate().quoteStatus()).isEqualTo("STALE");

        when(cache.readSnapshot()).thenReturn(snapshot(null, megaSpot(NOW.minusSeconds(7), NOW.minusSeconds(8))));
        assertThat(service.getLiveRate().quoteStatus()).isEqualTo("LAST_AVAILABLE");
    }

    @Test
    void spotMissingFallsBackToHistory() {
        when(cache.readSnapshot()).thenReturn(snapshot(null, null));
        when(history.getLatestExchangeRate("USD")).thenReturn(Optional.of(historyRow()));

        var fallback = service.getLiveRate();

        assertThat(fallback.liveUpdateStatus()).isEqualTo("INACTIVE");
        assertThat(fallback.quoteStatus()).isEqualTo("LAST_AVAILABLE");
        assertThat(fallback.source()).isEqualTo("HISTORY");
        assertThat(fallback.polledAt()).isNull();
        assertThat(fallback.sourceUpdatedAt()).isNull();
    }

    @Test
    void cacheUnavailableFallsBackToHistoryAsUnavailableStale() {
        when(cache.readSnapshot()).thenThrow(new IllegalStateException("redis down"));
        when(history.getLatestExchangeRate("USD")).thenReturn(Optional.of(historyRow()));

        var response = service.getLiveRate();

        assertThat(response.liveUpdateStatus()).isEqualTo("UNAVAILABLE");
        assertThat(response.quoteStatus()).isEqualTo("STALE");
        assertThat(response.source()).isEqualTo("HISTORY");
    }

    @Test
    void noCacheAndNoHistoryIsNotFound() {
        when(cache.readSnapshot()).thenReturn(snapshot(null, null));
        when(history.getLatestExchangeRate("USD")).thenReturn(Optional.empty());

        assertThatThrownBy(service::getLiveRate)
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void malformedCacheNeverFallsBackAndWatermarkMustMatchCurrentSource() {
        var malformed = new UsdTwdLiveRateCachePort.Spot(
                LocalDate.of(2026, 8, 13), new BigDecimal("32.1000"), new BigDecimal("32.2000"),
                "MEGA_BANK", NOW, NOW.minusSeconds(1), Map.of("MEGA_BANK", NOW.minusSeconds(2)));
        when(cache.readSnapshot()).thenReturn(snapshot(heartbeat(NOW, "MEGA_BANK"), malformed));

        assertThatThrownBy(service::getLiveRate)
                .isInstanceOf(MalformedUsdTwdRateException.class);
        verify(history, never()).getLatestExchangeRate("USD");
    }

    @Test
    void futureBoundaryInclusiveAndOneNanosecondOverRejected() {
        when(cache.readSnapshot()).thenReturn(snapshot(
                null, megaSpot(NOW, NOW.plusSeconds(120))));
        assertThat(service.getLiveRate().source()).isEqualTo("MEGA_BANK");

        when(cache.readSnapshot()).thenReturn(snapshot(
                null, megaSpot(NOW, NOW.plusSeconds(120).plusNanos(1))));
        assertThatThrownBy(service::getLiveRate)
                .isInstanceOf(MalformedUsdTwdRateException.class);
    }

    @Test
    void invalidEnumRatesTimestampNullabilityAndMapFailClosed() {
        var valid = megaSpot(NOW, NOW.minusSeconds(1));
        var invalid = java.util.List.of(
                new UsdTwdLiveRateCachePort.Spot(valid.rateDate(), valid.buyRate(), valid.sellRate(),
                        "BAD", valid.polledAt(), valid.sourceUpdatedAt(), valid.sourceUpdatedAtHighWatermarks()),
                new UsdTwdLiveRateCachePort.Spot(valid.rateDate(), BigDecimal.ZERO, valid.sellRate(),
                        valid.source(), valid.polledAt(), valid.sourceUpdatedAt(), valid.sourceUpdatedAtHighWatermarks()),
                new UsdTwdLiveRateCachePort.Spot(valid.rateDate(), new BigDecimal("33"), new BigDecimal("32"),
                        valid.source(), valid.polledAt(), valid.sourceUpdatedAt(), valid.sourceUpdatedAtHighWatermarks()),
                new UsdTwdLiveRateCachePort.Spot(valid.rateDate(), valid.buyRate(), valid.sellRate(),
                        valid.source(), valid.polledAt(), null, Map.of()),
                new UsdTwdLiveRateCachePort.Spot(valid.rateDate(), valid.buyRate(), valid.sellRate(),
                        valid.source(), valid.polledAt(), valid.sourceUpdatedAt(), null));

        for (var spot : invalid) {
            when(cache.readSnapshot()).thenReturn(snapshot(heartbeat(NOW, "MEGA_BANK"), spot));
            assertThatThrownBy(service::getLiveRate)
                    .isInstanceOf(MalformedUsdTwdRateException.class);
        }
    }

    @Test
    void serviceDependencyBoundaryContainsOnlyPortsHistoryAndClock() {
        assertThat(java.util.Arrays.stream(UsdTwdLiveRateService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType))
                .doesNotContain(
                        org.springframework.data.redis.core.StringRedisTemplate.class,
                        com.fasterxml.jackson.databind.ObjectMapper.class,
                        org.springframework.web.reactive.function.client.WebClient.class,
                        MarketDataService.class);
    }

    private static UsdTwdLiveRateCachePort.Snapshot snapshot(
            UsdTwdLiveRateCachePort.Heartbeat heartbeat,
            UsdTwdLiveRateCachePort.Spot spot) {
        return new UsdTwdLiveRateCachePort.Snapshot(
                Optional.ofNullable(heartbeat), Optional.ofNullable(spot));
    }

    private static UsdTwdLiveRateCachePort.Heartbeat heartbeat(Instant at, String source) {
        return new UsdTwdLiveRateCachePort.Heartbeat(at, Set.of(source));
    }

    private static UsdTwdLiveRateCachePort.Spot megaSpot(Instant polledAt, Instant sourceUpdatedAt) {
        return timestampedSpot("MEGA_BANK", polledAt, sourceUpdatedAt);
    }

    private static UsdTwdLiveRateCachePort.Spot yahooSpot(Instant polledAt, Instant sourceUpdatedAt) {
        return timestampedSpot("YAHOO", polledAt, sourceUpdatedAt);
    }

    private static UsdTwdLiveRateCachePort.Spot timestampedSpot(
            String source,
            Instant polledAt,
            Instant sourceUpdatedAt) {
        return new UsdTwdLiveRateCachePort.Spot(
                sourceUpdatedAt.atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),
                new BigDecimal("32.1000"), new BigDecimal("32.2000"), source,
                polledAt, sourceUpdatedAt, Map.of(source, sourceUpdatedAt));
    }

    private static UsdTwdLiveRateCachePort.Spot botSpot(Instant polledAt) {
        return new UsdTwdLiveRateCachePort.Spot(
                polledAt.atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),
                new BigDecimal("32.1000"), new BigDecimal("32.2000"), "BANK_OF_TAIWAN",
                polledAt, null, Map.of("MEGA_BANK", NOW.minusSeconds(10)));
    }

    private static ExchangeRateHistory historyRow() {
        return ExchangeRateHistory.builder()
                .currency("USD")
                .rateDate(LocalDate.of(2026, 8, 13))
                .buyRate(new BigDecimal("32.1000"))
                .sellRate(new BigDecimal("32.2000"))
                .build();
    }
}
