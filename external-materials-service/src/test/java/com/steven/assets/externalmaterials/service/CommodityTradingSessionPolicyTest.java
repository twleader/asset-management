package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-14 = 週五、08-15 = 週六、08-16 = 週日、08-19 = 週三、08-21 = 週五（同任務檔背景段
 * 「2026-08-15 週六、Globex 休市中取得」的日期基準）。
 */
class CommodityTradingSessionPolicyTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Test
    void sundayOpensAtEighteenHundredNotBefore() {
        CommodityTradingSessionPolicy policy = policy();
        assertThat(at(policy, "2026-08-16T17:59:00")).isFalse();
        assertThat(at(policy, "2026-08-16T18:00:00")).isTrue();
    }

    @Test
    void weekdayMaintenanceWindowIsSeventeenInclusiveToEighteenExclusive() {
        CommodityTradingSessionPolicy policy = policy();
        assertThat(at(policy, "2026-08-19T16:59:00")).isTrue();
        assertThat(at(policy, "2026-08-19T17:00:00")).isFalse();
        assertThat(at(policy, "2026-08-19T17:59:00")).isFalse();
        assertThat(at(policy, "2026-08-19T18:00:00")).isTrue();
    }

    @Test
    void fridayClosesAtSeventeenHundredAndDoesNotReopen() {
        CommodityTradingSessionPolicy policy = policy();
        assertThat(at(policy, "2026-08-21T16:59:00")).isTrue();
        assertThat(at(policy, "2026-08-21T17:00:00")).isFalse();
    }

    @Test
    void saturdayNeverInSession() {
        CommodityTradingSessionPolicy policy = policy();
        assertThat(at(policy, "2026-08-15T00:00:00")).isFalse();
        assertThat(at(policy, "2026-08-15T12:00:00")).isFalse();
        assertThat(at(policy, "2026-08-15T23:59:59")).isFalse();
    }

    @Test
    void clockIsInjectableForBoundaryTesting() {
        CommodityTradingSessionPolicy policy = new CommodityTradingSessionPolicy(
                Clock.fixed(
                        ZonedDateTime.of(LocalDateTime.parse("2026-08-19T12:00:00"), NY).toInstant(),
                        ZoneId.of("Asia/Taipei")));
        assertThat(policy.inSession()).isTrue();
    }

    private static CommodityTradingSessionPolicy policy() {
        return new CommodityTradingSessionPolicy(Clock.fixed(Instant.now(), NY));
    }

    private static boolean at(CommodityTradingSessionPolicy policy, String localDateTime) {
        return policy.inSession(ZonedDateTime.of(LocalDateTime.parse(localDateTime), NY));
    }
}
