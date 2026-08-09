package com.steven.assets.externalmaterials.client;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Routes the explicit upcoming-scope contract to the market's official adapter. */
@Component
@Primary
public class MarketDividendUpcomingScopeClient implements DividendUpcomingScopeClient {

    private final NasdaqDividendCalendarClient us;
    private final TaiwanOfficialDividendCalendarClient tw;
    /** Lazy provider avoids a constructor cycle: DividendFetchClient consumes this router. */
    private final ObjectProvider<DividendFetchClient> provider;

    /** Compatibility constructor used by focused adapter tests and non-Spring callers. */
    public MarketDividendUpcomingScopeClient(
            NasdaqDividendCalendarClient us,
            TaiwanOfficialDividendCalendarClient tw) {
        this(us, tw, null);
    }

    @Autowired
    public MarketDividendUpcomingScopeClient(
            NasdaqDividendCalendarClient us,
            TaiwanOfficialDividendCalendarClient tw,
            ObjectProvider<DividendFetchClient> provider) {
        this.us = us;
        this.tw = tw;
        this.provider = provider;
    }

    @Override
    public UpcomingScope fetch(String stockCode, String market, LocalDate from, LocalDate to) {
        UpcomingScope official = switch (market) {
            case "美股" -> us.fetch(stockCode, market, from, to);
            case "台股" -> tw.fetch(stockCode, market, from, to);
            default -> UpcomingScope.unavailable("沒有對應市場的 upcoming dividend adapter");
        };
        // Keep TWSE/TPEx and Nasdaq calendar evidence authoritative when available.  Only when
        // that exact scope is unavailable do we ask the already-configured FinMind/Yahoo fetcher
        // for the same bounded scope; this wires its fallback into the scope path without making
        // a historical partial response look like a verified empty calendar.
        if (official != null && official.complete()) return official;
        if (provider == null) return official;
        DividendFetchClient fallback = provider == null ? null : provider.getIfAvailable();
        UpcomingScope providerScope = fallback == null
                ? UpcomingScope.unavailable("FinMind/Yahoo provider fallback 未設定")
                : fallback.fetchProviderUpcomingScope(stockCode, market, from, to);
        if (providerScope != null && providerScope.complete()) return providerScope;
        if (official != null && !official.events().isEmpty()) return official;
        if (providerScope != null && !providerScope.events().isEmpty()) return providerScope;
        String officialReason = official == null ? "official adapter 未回結果" : official.errorReason();
        String fallbackReason = providerScope == null ? "provider fallback 未回結果" : providerScope.errorReason();
        return UpcomingScope.unavailable(joinReasons(officialReason, fallbackReason));
    }

    private static String joinReasons(String first, String second) {
        if (first == null || first.isBlank()) return second;
        if (second == null || second.isBlank()) return first;
        return first + "; " + second;
    }
}
