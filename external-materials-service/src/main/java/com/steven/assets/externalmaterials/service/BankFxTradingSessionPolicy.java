package com.steven.assets.externalmaterials.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;

/** 台銀與兆豐 USD/TWD 牌告交易時段的單一、可測 policy。 */
@Component
public class BankFxTradingSessionPolicy {

    static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime REGULAR_OPEN = LocalTime.of(9, 0);
    private static final LocalTime REGULAR_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime NIGHT_OPEN = LocalTime.of(16, 30);
    private static final LocalTime BOT_NIGHT_CLOSE = LocalTime.of(23, 0);
    private static final LocalTime MEGA_NIGHT_CLOSE = LocalTime.of(8, 0);
    private static final int SEARCH_LIMIT_DAYS = 21;

    private final MarketCalendar calendar;
    private final Clock clock;

    @Autowired
    public BankFxTradingSessionPolicy(MarketCalendar calendar) {
        this(calendar, Clock.system(TAIPEI));
    }

    BankFxTradingSessionPolicy(MarketCalendar calendar, Clock clock) {
        this.calendar = calendar;
        this.clock = clock.withZone(TAIPEI);
    }

    public Set<UsdTwdSource> eligibleSources() {
        return eligibleSources(ZonedDateTime.now(clock));
    }

    Set<UsdTwdSource> eligibleSources(ZonedDateTime rawNow) {
        ZonedDateTime now = rawNow.withZoneSameInstant(TAIPEI);
        LinkedHashSet<UsdTwdSource> eligible = new LinkedHashSet<>();
        Optional<Boolean> todayKnown = calendar.isTwTradingDayKnown(now.toLocalDate());
        if (todayKnown.isEmpty()) return Set.of();

        boolean businessDay = todayKnown.get();
        LocalTime time = now.toLocalTime();
        if (businessDay && inRange(time, REGULAR_OPEN, REGULAR_CLOSE)) {
            eligible.add(UsdTwdSource.BANK_OF_TAIWAN);
            eligible.add(UsdTwdSource.MEGA_BANK);
        }
        if (businessDay && inRange(time, NIGHT_OPEN, BOT_NIGHT_CLOSE)) {
            eligible.add(UsdTwdSource.BANK_OF_TAIWAN);
        }
        if (isMegaNightSession(now)) {
            eligible.add(UsdTwdSource.MEGA_BANK);
        }
        return Collections.unmodifiableSet(eligible);
    }

    private boolean isMegaNightSession(ZonedDateTime now) {
        LocalDate searchFrom = now.toLocalTime().compareTo(NIGHT_OPEN) >= 0
                ? now.toLocalDate()
                : now.toLocalDate().minusDays(1);
        Optional<LocalDate> startDay = previousKnownBusinessDay(searchFrom);
        if (startDay.isEmpty()) return false;
        Optional<LocalDate> nextDay = nextKnownBusinessDay(startDay.get().plusDays(1));
        if (nextDay.isEmpty()) return false;

        ZonedDateTime startsAt = startDay.get().atTime(NIGHT_OPEN).atZone(TAIPEI);
        ZonedDateTime endsAt = nextDay.get().atTime(MEGA_NIGHT_CLOSE).atZone(TAIPEI);
        return !now.isBefore(startsAt) && !now.isAfter(endsAt);
    }

    private Optional<LocalDate> previousKnownBusinessDay(LocalDate from) {
        LocalDate candidate = from;
        for (int i = 0; i < SEARCH_LIMIT_DAYS; i++, candidate = candidate.minusDays(1)) {
            Optional<Boolean> known = calendar.isTwTradingDayKnown(candidate);
            if (known.isEmpty()) return Optional.empty();
            if (known.get()) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private Optional<LocalDate> nextKnownBusinessDay(LocalDate from) {
        LocalDate candidate = from;
        for (int i = 0; i < SEARCH_LIMIT_DAYS; i++, candidate = candidate.plusDays(1)) {
            Optional<Boolean> known = calendar.isTwTradingDayKnown(candidate);
            if (known.isEmpty()) return Optional.empty();
            if (known.get()) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static boolean inRange(LocalTime value, LocalTime start, LocalTime end) {
        return value.compareTo(start) >= 0 && value.compareTo(end) <= 0;
    }
}
