package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingDateResolverObservationTest {

    private final MarketClock marketClock = mock(MarketClock.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final TradingDateResolver resolver = new TradingDateResolver(marketClock, source);

    @Test
    void liveSessionUsesDateFromTheSameObservationInstantWithoutDbFallback() {
        Instant observed = Instant.parse("2026-08-21T01:00:00Z"); // 09:00 Taipei
        LocalDate localDate = LocalDate.of(2026, 8, 21);
        when(marketClock.isTradingDay("台股", localDate)).thenReturn(true);

        assertThat(resolver.resolve("2330", "台股", observed)).isEqualTo(localDate);

        verify(source, never()).findMaxTradingDate("2330", "台股");
    }

    @Test
    void outsideLiveAndJustClosedWindowFallsBackToDbDate() {
        Instant observed = Instant.parse("2026-08-21T08:00:00Z"); // 16:00 Taipei
        LocalDate localDate = LocalDate.of(2026, 8, 21);
        when(marketClock.isTradingDay("台股", localDate)).thenReturn(true);
        when(source.findMaxTradingDate("2330", "台股"))
                .thenReturn(Optional.of(LocalDate.of(2026, 8, 20)));

        assertThat(resolver.resolve("2330", "台股", observed))
                .isEqualTo(LocalDate.of(2026, 8, 20));
    }

    @Test
    void usDateComesFromNewYorkConversionOfTheSameInstant() {
        Instant observed = Instant.parse("2026-08-21T13:30:00Z"); // 09:30 EDT
        LocalDate usDate = LocalDate.of(2026, 8, 21);
        when(marketClock.isTradingDay("美股", usDate)).thenReturn(true);

        assertThat(resolver.resolve("VOO", "美股", observed)).isEqualTo(usDate);
    }
}
