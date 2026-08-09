package com.steven.assets.externalmaterials.client;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for a provider that can prove an explicit upcoming dividend date range.
 * Historical feeds must not implement this contract merely because their HTTP
 * request succeeded.
 */
public interface DividendUpcomingScopeClient {

    record UpcomingScope(
            String provider,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            Instant sourceAvailableAt,
            boolean complete,
            List<DividendFetchClient.DividendEvent> events,
            String errorReason,
            List<String> sourceUrls) {

        public UpcomingScope(String provider, LocalDate scopeFrom, LocalDate scopeTo,
                             Instant sourceAvailableAt, boolean complete,
                             List<DividendFetchClient.DividendEvent> events,
                             String errorReason) {
            this(provider, scopeFrom, scopeTo, sourceAvailableAt, complete, events,
                    errorReason, List.of());
        }

        public UpcomingScope {
            events = events == null ? List.of() : List.copyOf(events);
            sourceUrls = sourceUrls == null ? List.of() : sourceUrls.stream()
                    .filter(url -> url != null && !url.isBlank()).distinct().toList();
        }

        public static UpcomingScope unavailable(String reason) {
            return new UpcomingScope(null, null, null, null, false, List.of(), reason, List.of());
        }

        public boolean covers(LocalDate from, LocalDate to) {
            return complete && scopeFrom != null && scopeTo != null
                    && !scopeFrom.isAfter(from) && !scopeTo.isBefore(to);
        }
    }

    UpcomingScope fetch(String stockCode, String market, LocalDate from, LocalDate to);
}
