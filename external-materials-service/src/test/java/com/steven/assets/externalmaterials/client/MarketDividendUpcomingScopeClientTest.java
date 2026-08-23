package com.steven.assets.externalmaterials.client;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketDividendUpcomingScopeClientTest {

    @Test
    void routesTaiwanAndUsToDifferentOfficialAdapters() {
        NasdaqDividendCalendarClient us = mock(NasdaqDividendCalendarClient.class);
        TaiwanOfficialDividendCalendarClient tw = mock(TaiwanOfficialDividendCalendarClient.class);
        LocalDate from = LocalDate.of(2026, 8, 9);
        LocalDate to = from.plusDays(45);
        var twScope = DividendUpcomingScopeClient.UpcomingScope.unavailable("tw marker");
        var usScope = DividendUpcomingScopeClient.UpcomingScope.unavailable("us marker");
        when(tw.fetch("2330", "台股", from, to)).thenReturn(twScope);
        when(us.fetch("AAPL", "美股", from, to)).thenReturn(usScope);
        var router = new MarketDividendUpcomingScopeClient(us, tw);

        assertThat(router.fetch("2330", "台股", from, to)).isSameAs(twScope);
        assertThat(router.fetch("AAPL", "美股", from, to)).isSameAs(usScope);
        verify(tw).fetch("2330", "台股", from, to);
        verify(us).fetch("AAPL", "美股", from, to);
    }

    @Test
    void usesFinMindYahooProviderScopeOnlyWhenOfficialScopeIsUnavailable() {
        NasdaqDividendCalendarClient us = mock(NasdaqDividendCalendarClient.class);
        TaiwanOfficialDividendCalendarClient tw = mock(TaiwanOfficialDividendCalendarClient.class);
        org.springframework.beans.factory.ObjectProvider<DividendFetchClient> provider = mock();
        DividendFetchClient fallback = mock(DividendFetchClient.class);
        LocalDate from = LocalDate.of(2026, 8, 9);
        LocalDate to = from.plusDays(45);
        var officialUnavailable = DividendUpcomingScopeClient.UpcomingScope.unavailable("official down");
        var providerEvent = new DividendFetchClient.DividendEvent(
                2026, new java.math.BigDecimal("0.25"), java.math.BigDecimal.ZERO,
                "2026-08-10", null, null, null);
        var providerScope = new DividendUpcomingScopeClient.UpcomingScope(
                "FinMind", from, to, java.time.Instant.parse("2026-08-09T00:00:00Z"),
                true, List.of(providerEvent), null);
        when(tw.fetch("2330", "台股", from, to)).thenReturn(officialUnavailable);
        when(provider.getIfAvailable()).thenReturn(fallback);
        when(fallback.fetchProviderUpcomingScope("2330", "台股", from, to))
                .thenReturn(providerScope);
        var router = new MarketDividendUpcomingScopeClient(us, tw, provider);

        assertThat(router.fetch("2330", "台股", from, to)).isSameAs(providerScope);
        verify(fallback).fetchProviderUpcomingScope("2330", "台股", from, to);
    }

    @Test
    void doesNotReplaceAuthoritativeOfficialScopeWithProviderFallback() {
        NasdaqDividendCalendarClient us = mock(NasdaqDividendCalendarClient.class);
        TaiwanOfficialDividendCalendarClient tw = mock(TaiwanOfficialDividendCalendarClient.class);
        org.springframework.beans.factory.ObjectProvider<DividendFetchClient> provider = mock();
        DividendFetchClient fallback = mock(DividendFetchClient.class);
        LocalDate from = LocalDate.of(2026, 8, 9);
        LocalDate to = from.plusDays(45);
        var official = new DividendUpcomingScopeClient.UpcomingScope(
                "TWSE", from, to, java.time.Instant.parse("2026-08-09T00:00:00Z"),
                true, List.of(), null);
        when(tw.fetch("2330", "台股", from, to)).thenReturn(official);
        when(provider.getIfAvailable()).thenReturn(fallback);

        var router = new MarketDividendUpcomingScopeClient(us, tw, provider);

        assertThat(router.fetch("2330", "台股", from, to)).isSameAs(official);
        verifyNoInteractions(fallback);
    }

    @Test
    void routesNasdaqFailureToYahooProviderScopeFallback() {
        NasdaqDividendCalendarClient us = mock(NasdaqDividendCalendarClient.class);
        TaiwanOfficialDividendCalendarClient tw = mock(TaiwanOfficialDividendCalendarClient.class);
        org.springframework.beans.factory.ObjectProvider<DividendFetchClient> provider = mock();
        DividendFetchClient fallback = mock(DividendFetchClient.class);
        LocalDate from = LocalDate.of(2026, 8, 9);
        LocalDate to = from.plusDays(45);
        var unavailable = DividendUpcomingScopeClient.UpcomingScope.unavailable("Nasdaq calendar failed");
        var yahooScope = new DividendUpcomingScopeClient.UpcomingScope(
                "Yahoo Finance", from, to, java.time.Instant.parse("2026-08-09T00:00:00Z"),
                true, List.of(), null);
        when(us.fetch("VOO", "美股", from, to)).thenReturn(unavailable);
        when(provider.getIfAvailable()).thenReturn(fallback);
        when(fallback.fetchProviderUpcomingScope("VOO", "美股", from, to))
                .thenReturn(yahooScope);

        var router = new MarketDividendUpcomingScopeClient(us, tw, provider);

        assertThat(router.fetch("VOO", "美股", from, to)).isSameAs(yahooScope);
        verify(fallback).fetchProviderUpcomingScope("VOO", "美股", from, to);
    }
}
