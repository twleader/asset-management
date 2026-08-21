package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void closureOnlyCannotPromoteMissingTwseBaseToKnownAuthority() {
        TwTyphoonClosureService closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(true);
        when(closures.closuresForYear(2027)).thenReturn(Map.of("2027-08-01", "臨時休市"));
        SequencedAuthorityService service = new SequencedAuthorityService(closures, Map.of(), Map.of());

        assertThat(service.getTwHolidaysKnown(2027)).isEmpty();
        assertThat(service.fetchAttempts).isEqualTo(1);
    }

    @Test
    void unknownClosureCalendarFailsBeforeTwseBaseIsTrusted() {
        TwTyphoonClosureService closures = mock(TwTyphoonClosureService.class);
        when(closures.isClosureCalendarKnown()).thenReturn(false);
        SequencedAuthorityService service = new SequencedAuthorityService(
                closures, Map.of("2026-01-01", "元旦"));

        assertThat(service.getTwHolidaysKnown(2026)).isEmpty();
        assertThat(service.fetchAttempts).isZero();
        verify(closures).loadFromDb();
        verify(closures, never()).closuresForYear(2026);
    }

    private static final class SequencedAuthorityService extends MarketDataFetchService {
        private int fetchAttempts;

        private final Map<String, String>[] responses;

        private SequencedAuthorityService(TwTyphoonClosureService closures) {
            this(closures, Map.of(), Map.of("2026-01-01", "holiday"));
        }

        @SafeVarargs
        private SequencedAuthorityService(TwTyphoonClosureService closures,
                                          Map<String, String>... responses) {
            super(mock(StockSourceQuery.class), closures, "");
            this.responses = responses;
        }

        @Override
        Map<String, String> fetchTwHolidaysFromTwse(int year) {
            int index = fetchAttempts++;
            return responses[Math.min(index, responses.length - 1)];
        }
    }
}
