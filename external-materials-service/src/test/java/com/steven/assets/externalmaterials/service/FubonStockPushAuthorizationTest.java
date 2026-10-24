package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FubonStockPushAuthorizationTest {
    final MarketClock clock = mock(MarketClock.class);
    final FubonRadarScope radar = mock(FubonRadarScope.class);
    final FubonStockPushStream stream = mock(FubonStockPushStream.class);
    final FubonMarketDataPort port = mock(FubonMarketDataPort.class);
    final FubonStockPushConsumer consumer = mock(FubonStockPushConsumer.class);
    final Instant now = Instant.parse("2026-08-28T02:15:00Z");
    final FubonStockPushSubscriptionManager manager = new FubonStockPushSubscriptionManager(
            "true", () -> null, clock, radar, port, stream, consumer);
    FubonStockPushAuthorizationTest() {
        when(clock.instant()).thenReturn(now);
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(radar.current(300)).thenReturn(List.of("2330"));
        when(stream.isRunning()).thenReturn(true);
        when(stream.isReadyFor(Set.of("2330"))).thenReturn(true);
    }
    @Test void workerAloneDoesNotAuthorizePriceSuppressionAndRenewalTriggersCommittedRepair() {
        assertThat(manager.isPricePushHealthy("2330")).isFalse();
        manager.refreshSubscriptions();
        assertThat(manager.isPricePushHealthy("2330")).isTrue();
        assertThat(manager.isPricePushHealthy("0050")).isFalse();
        var order = inOrder(consumer);
        order.verify(consumer).authorize(List.of("2330"),now.plusSeconds(45));
        order.verify(consumer).repairPending();
        when(stream.isReadyFor(Set.of("2330"))).thenReturn(false);
        assertThat(manager.isPricePushHealthy("2330")).isFalse();
    }
    @Test void expiredAuthorizationUnknownCalendarAndScopeReplacementFailClosed() {
        manager.refreshSubscriptions();
        when(clock.instant()).thenReturn(now.plusSeconds(45)); assertThat(manager.isPricePushHealthy("2330")).isFalse();
        when(clock.instant()).thenReturn(now);
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.empty()); assertThat(manager.isPricePushHealthy("2330")).isFalse();
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(true));
        when(radar.current(300)).thenReturn(List.of("0050")); manager.refreshSubscriptions();
        assertThat(manager.isPricePushHealthy("2330")).isFalse();
        assertThat(manager.isPricePushHealthy("0050")).isFalse();
        verify(stream,atLeastOnce()).isReadyFor(Set.of("0050"));
    }
    @Test void marketCloseRevokesConsumerAndStopsStream() {
        manager.refreshSubscriptions();
        when(clock.isTwMarketOpenKnown()).thenReturn(Optional.of(false)); manager.refreshSubscriptions();
        verify(consumer).revoke(); verify(stream).stop(); verify(port).subscriptions(List.of());
        assertThat(manager.isPricePushHealthy("2330")).isFalse();
    }
}
