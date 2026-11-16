package com.steven.assets.model;

import static com.steven.assets.model.CanonicalDividend.*;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class VanguardDividendAuthorityTest {
    static final Key KEY = new Key("VOO", "美股");
    static final Instant NOW = Instant.parse("2026-11-16T16:30:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-11-17");
    static Component event(String id, String date, String cash, String pay) {
        return new Component(id, LocalDate.parse(date).getYear(), LocalDate.parse(date), null,
                new BigDecimal(cash), BigDecimal.ZERO, pay == null ? null : LocalDate.parse(pay), null, null);
    }
    static Evidence evidence(long id, String source, String from, String to, Component... events) {
        return new Evidence(id, id, source, 2, "NOMINAL_PER_EX_DATE", true, "PARTIAL", false,
                LocalDate.parse(from), LocalDate.parse(to), NOW.minusSeconds(100-id), null, "hash"+id, List.of(events));
    }
    @Test void issuerPrecisionPaymentAndSameDayComponentsWinWithoutLosingEarlierSourceHistory() {
        var issuer = evidence(3, "VANGUARD_ISSUER", "2024-11-16", "2026-11-16",
                event("VANGUARD:income", "2026-09-28", "1.8226", "2026-09-30"));
        var old = evidence(1, "Yahoo Finance (nominal from verified splits)", "2015-01-01", "2026-11-16",
                event("YAHOO:old", "2015-12-21", "1.092", null), event("YAHOO:recent", "2026-09-28", "1.823", null));
        var competing = evidence(2, "NASDAQ.stocks", "2024-11-16", "2026-11-16",
                event("NASDAQ:recent", "2026-09-28", "1.823", "2026-10-01"));
        var result = CanonicalDividendResolver.resolve(new Facts(KEY, List.of(old, competing, issuer), List.of()), NOW, TODAY, false).days();
        assertThat(result).hasSize(2);
        assertThat(result.getFirst().date()).isEqualTo(LocalDate.parse("2015-12-21"));
        assertThat(result.getFirst().cash()).isEqualByComparingTo("1.092");
        var recent = result.getLast(); assertThat(recent.cash()).isEqualByComparingTo("1.8226");
        assertThat(recent.cashPayment()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(recent.source()).isEqualTo("VANGUARD_ISSUER"); assertThat(recent.resolution()).isEqualTo("RESOLVED");
        assertThat(result).noneMatch(Day::withdrawn);
        var twoParts = evidence(4, "VANGUARD_ISSUER", "2024-11-16", "2026-11-16",
                event("VANGUARD:income", "2026-09-28", "1.8226", "2026-09-30"),
                event("VANGUARD:cgst", "2026-09-28", ".1234", "2026-09-30"));
        var sum = CanonicalDividendResolver.resolve(new Facts(KEY, List.of(twoParts), List.of()), NOW, TODAY, false).days();
        assertThat(sum).singleElement().satisfies(row -> assertThat(row.cash()).isEqualByComparingTo("1.9460"));
    }
    @Test void issuerPartialDoesNotUnlockFutureFinancialCalendarOrPriceBasisGuard() {
        var future = evidence(1, "VANGUARD_ISSUER", "2026-11-17", "2027-01-01",
                event("VANGUARD:future", "2026-12-21", "1.9", "2026-12-24"));
        var facts = new Facts(KEY, List.of(future), List.of());
        assertThat(CanonicalDividendResolver.resolve(facts, NOW, TODAY, false).days()).isEmpty();
        assertThat(CanonicalDividendResolver.resolve(facts, NOW, TODAY, true).days()).singleElement().satisfies(row -> {
            assertThat(row.resolution()).isEqualTo("FUTURE_PARTIAL"); assertThat(row.financial()).isFalse();
        });
        var guarded = CanonicalDividendFinancialGuard.assess(List.of(new CanonicalDividendFinancialGuard.Fact(
                "issuer", "VOO", "美股", LocalDate.parse("2026-09-28"), new BigDecimal("1.8226"), BigDecimal.ZERO,
                "RESOLVED", 1L, null)));
        assertThatThrownBy(guarded::requireComparablePriceBasis).hasMessage("US_DIVIDEND_PRICE_BASIS_UNVERIFIED");
    }
}
