package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataFetchServiceTwAuthorityRetryTest {

    @Test
    void regularPathKeepsLegacyEmptyCacheWhileKnownPathCanRefreshIt() {
        TwTyphoonClosureService closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        when(closures.closuresForYear(2026)).thenReturn(Map.of());
        SequencedAuthorityService service = new SequencedAuthorityService(closures);

        assertThat(service.getTwHolidays(2026)).isEmpty();
        assertThat(service.getTwHolidays(2026)).isEmpty();
        assertThat(service.fetchAttempts).isEqualTo(1);

        assertThat(service.getTwHolidaysKnown(2026))
                .contains(Map.of("2026-01-01", "holiday"));
        assertThat(service.fetchAttempts).isEqualTo(2);
    }

    private static final class SequencedAuthorityService extends MarketDataFetchService {
        private int fetchAttempts;

        private SequencedAuthorityService(TwTyphoonClosureService closures) {
            super(mock(StockSourceQuery.class), closures, "");
        }

        @Override
        Map<String, String> fetchTwHolidaysFromTwse(int year) {
            fetchAttempts++;
            return fetchAttempts == 1 ? Map.of() : Map.of("2026-01-01", "holiday");
        }
    }
}
