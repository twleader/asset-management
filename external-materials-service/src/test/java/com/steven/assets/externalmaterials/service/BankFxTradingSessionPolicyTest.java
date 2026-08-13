package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BankFxTradingSessionPolicyTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Test
    void botAndMegaRegularAndNightBoundariesAreInclusive() {
        BankFxTradingSessionPolicy policy = policy(new KnownCalendar(Set.of(), Set.of()));

        assertThat(at(policy, "2026-08-13T09:00:00+08:00"))
                .containsExactly(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-13T15:30:00+08:00"))
                .containsExactly(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-13T15:30:00.000000001+08:00")).isEmpty();
        assertThat(at(policy, "2026-08-13T16:30:00+08:00"))
                .containsExactly(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-13T23:00:00+08:00"))
                .containsExactly(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-13T23:00:00.000000001+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
    }

    @Test
    void megaNightSessionCrossesMidnightWeekendAndEndsMondayAtEightInclusive() {
        BankFxTradingSessionPolicy policy = policy(new KnownCalendar(Set.of(), Set.of()));

        assertThat(at(policy, "2026-08-14T16:30:00+08:00"))
                .containsExactly(UsdTwdSource.BANK_OF_TAIWAN, UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-15T12:00:00+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-16T23:59:59+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-17T08:00:00+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-17T08:00:00.000000001+08:00")).isEmpty();
        assertThat(at(policy, "2026-08-17T08:59:59+08:00")).isEmpty();
    }

    @Test
    void megaNightSessionIncludesIntermediateHolidayUntilNextKnownBusinessDay() {
        LocalDate mondayHoliday = LocalDate.of(2026, 8, 17);
        BankFxTradingSessionPolicy policy = policy(
                new KnownCalendar(Set.of(mondayHoliday), Set.of()));

        assertThat(at(policy, "2026-08-17T15:00:00+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-18T08:00:00+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
        assertThat(at(policy, "2026-08-18T08:00:01+08:00")).isEmpty();
    }

    @Test
    void calendarUnknownFailsClosedAndProxyClosedNeverOpensBotRegularSession() {
        LocalDate thursday = LocalDate.of(2026, 8, 13);
        BankFxTradingSessionPolicy unknown = policy(
                new KnownCalendar(Set.of(), Set.of(thursday)));
        BankFxTradingSessionPolicy closed = policy(
                new KnownCalendar(Set.of(thursday), Set.of()));

        assertThat(at(unknown, "2026-08-13T10:00:00+08:00")).isEmpty();
        // 兆豐的前一營業日夜間 session 依法涵蓋中間假日；closed 只不得誤開台銀／當日一般段。
        assertThat(at(closed, "2026-08-13T10:00:00+08:00"))
                .containsExactly(UsdTwdSource.MEGA_BANK);
    }

    private static BankFxTradingSessionPolicy policy(MarketCalendar calendar) {
        return new BankFxTradingSessionPolicy(
                calendar, Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), TAIPEI));
    }

    private static Set<UsdTwdSource> at(BankFxTradingSessionPolicy policy, String value) {
        return policy.eligibleSources(ZonedDateTime.parse(value));
    }

    private static final class KnownCalendar extends MarketCalendar {
        private final Set<LocalDate> closed;
        private final Set<LocalDate> unknown;

        private KnownCalendar(Set<LocalDate> closed, Set<LocalDate> unknown) {
            super(null);
            this.closed = new HashSet<>(closed);
            this.unknown = new HashSet<>(unknown);
        }

        @Override
        public Optional<Boolean> isTwTradingDayKnown(LocalDate date) {
            if (unknown.contains(date)) return Optional.empty();
            return switch (date.getDayOfWeek()) {
                case SATURDAY, SUNDAY -> Optional.of(false);
                default -> Optional.of(!closed.contains(date));
            };
        }
    }
}
