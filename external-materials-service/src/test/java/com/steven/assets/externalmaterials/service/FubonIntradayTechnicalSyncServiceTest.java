package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.mockito.InOrder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FubonIntradayTechnicalSyncServiceTest {
    final FubonMarketRunGate gate = mock(FubonMarketRunGate.class);
    final FubonRadarScope radar = mock(FubonRadarScope.class);
    final FubonMarketDataPort client = mock(FubonMarketDataPort.class);
    final FubonTechnicalCachePort cache = mock(FubonTechnicalCachePort.class);
    final MarketClock clock = mock(MarketClock.class);

    @Test
    void disabledFlagStopsBeforeScopeRedisOrMarketData() {
        when(gate.reason("false", "INTRADAY_TECHNICAL_SYNC_DISABLED", false))
                .thenReturn("INTRADAY_TECHNICAL_SYNC_DISABLED");
        var service = new FubonIntradayTechnicalSyncService("false", "false", gate, radar, client, cache, clock);

        assertThat(service.sync().outcome()).isEqualTo("INTRADAY_TECHNICAL_SYNC_DISABLED");
        verifyNoInteractions(radar, client, cache);
    }

    @Test
    void cursorAdvancesAfterTheLastCodeAndWrapsForSortedRadarScope() {
        List<String> symbols = List.of("2301", "2302", "2330", "2603");
        assertThat(FubonIntradayTechnicalSyncService.nextIndex(symbols, null)).isZero();
        assertThat(FubonIntradayTechnicalSyncService.nextIndex(symbols, "2302")).isEqualTo(2);
        assertThat(FubonIntradayTechnicalSyncService.nextIndex(symbols, "2603")).isZero();
        assertThat(FubonIntradayTechnicalSyncService.nextIndex(symbols, "2310")).isEqualTo(2);
    }

    @Test
    void scheduleUsesCachePortForCursorAndBundleWrite() {
        Instant now = Instant.parse("2026-09-25T02:00:00Z");
        LocalDate today = now.atZone(MarketClock.TW_ZONE).toLocalDate();
        when(gate.reason("true", "INTRADAY_TECHNICAL_SYNC_DISABLED", false)).thenReturn(null);
        when(gate.today()).thenReturn(today);
        when(gate.sameDay(today)).thenReturn(true);
        when(clock.instant()).thenReturn(now);
        when(radar.current(30)).thenReturn(List.of("2330"));
        when(cache.readIntradayCursor()).thenReturn(null);

        var frame = new FubonIntradayTechnical.Frame("1", today, null, now,
                new FubonIntradayTechnical.Kdj(new BigDecimal("51.2"), new BigDecimal("48.9"), new BigDecimal("55.8")),
                new FubonIntradayTechnical.Macd(new BigDecimal("0.4"), new BigDecimal("0.3")),
                new FubonIntradayTechnical.Bollinger(new BigDecimal("1015"), new BigDecimal("1004.5"), new BigDecimal("994")));
        var bundle = new FubonIntradayTechnical.Bundle(1, "2330", FubonMarketData.MARKET,
                FubonMarketData.PROVIDER, now, frame, new FubonIntradayTechnical.Frame("5", today, null, now,
                frame.kdj(), frame.macd(), frame.bollinger()));
        when(client.intradayTechnical("2330", today)).thenReturn(bundle);
        when(cache.writeIntraday(bundle)).thenReturn(FubonTechnicalCachePort.IntradayWrite.WRITTEN);

        var service = new FubonIntradayTechnicalSyncService("true", "false", gate, radar, client, cache, clock);

        assertThat(service.sync().outcome()).isEqualTo("SUCCESS");
        InOrder order = inOrder(cache, client);
        order.verify(cache).readIntradayCursor();
        order.verify(cache).advanceIntradayCursor("2330");
        order.verify(client).intradayTechnical("2330", today);
        order.verify(cache).writeIntraday(bundle);
    }
}
