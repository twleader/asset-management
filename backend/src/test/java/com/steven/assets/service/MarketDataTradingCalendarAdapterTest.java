package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataTradingCalendarAdapterTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);

    @Test
    void mapsTrueAndFalseToTypedOpenAndClosed() {
        MarketDataService delegate = mock(MarketDataService.class);
        MarketDataTradingCalendarAdapter adapter = new MarketDataTradingCalendarAdapter(delegate);
        when(delegate.isTradingDayKnown("美股", DATE))
                .thenReturn(Optional.of(true), Optional.of(false));

        var open = adapter.resolve("美股", DATE);
        var closed = adapter.resolve("美股", DATE);

        assertThat(open.status()).isEqualTo(TradingRadarSessionCalendarPort.Status.OPEN);
        assertThat(closed.status()).isEqualTo(TradingRadarSessionCalendarPort.Status.CLOSED);
        assertThat(open.provider()).isEqualTo(MarketDataTradingCalendarAdapter.PROVIDER);
        assertThat(open.reason()).isNull();
    }

    @Test
    void mapsEmptyNullAndDelegateExceptionToStableUnknown() {
        MarketDataService delegate = mock(MarketDataService.class);
        MarketDataTradingCalendarAdapter adapter = new MarketDataTradingCalendarAdapter(delegate);
        when(delegate.isTradingDayKnown("美股", DATE))
                .thenReturn(Optional.empty(), null)
                .thenThrow(new IllegalStateException("authority down"));

        for (int i = 0; i < 3; i++) {
            var result = adapter.resolve("美股", DATE);
            assertThat(result.status()).isEqualTo(TradingRadarSessionCalendarPort.Status.UNKNOWN);
            assertThat(result.provider()).isEqualTo(MarketDataTradingCalendarAdapter.PROVIDER);
            assertThat(result.reason()).isEqualTo(
                    TradingRadarSessionCalendarPort.MARKET_CALENDAR_UNAVAILABLE);
            assertThat(result.date()).isEqualTo(DATE);
        }
    }
}
