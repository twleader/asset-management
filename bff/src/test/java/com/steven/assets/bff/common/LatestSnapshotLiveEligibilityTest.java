package com.steven.assets.bff.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LatestSnapshotLiveEligibilityTest {
    private static final Clock SUMMER = clock("2026-10-29T16:16:00Z");

    @ParameterizedTest
    @CsvSource({
            "2026-10-29T16:16:00Z,2026-10-30,美股,true",
            "2026-10-29T16:16:00Z,2026-10-29,美股,true",
            "2026-12-15T16:16:00Z,2026-12-16,美股,true",
            "2026-12-15T16:16:00Z,2026-12-15,美股,true",
            "2026-10-30T02:00:00Z,2026-10-30,台股,true",
            "2026-10-29T16:16:00Z,2026-10-29,台股,false",
            "2026-10-29T16:16:00Z,2026-10-28,美股,false",
            "2026-10-29T16:16:00Z,2026-10-31,美股,false"
    })
    void identityBoundDateWindowRespectsBothTimezones(String instant, String date, String market, boolean eligible) {
        assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot(date), live(date), market, clock(instant)))
                .isEqualTo(eligible);
    }

    @Test
    void missingMismatchedIdsAndDatesFailClosed() {
        for (String key : List.of("id", "snapshotDate")) {
            Map<String, Object> snapshot = snapshot("2026-10-30");
            snapshot.remove(key);
            assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot, live("2026-10-30"), "美股", SUMMER)).isFalse();
        }
        for (String key : List.of("snapshotId", "snapshotDate")) {
            Map<String, Object> live = live("2026-10-30");
            live.remove(key);
            assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot("2026-10-30"), live, "美股", SUMMER)).isFalse();
        }
        Map<String, Object> live = live("2026-10-30");
        live.put("snapshotId", 16);
        assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot("2026-10-30"), live, "美股", SUMMER)).isFalse();
        assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot("2026-10-30"), live("2026-10-29"), "美股", SUMMER)).isFalse();
        assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot("bad"), live("bad"), "美股", SUMMER)).isFalse();
        assertThat(LatestSnapshotLiveEligibility.isEligible(snapshot("2026-10-30"), live("2026-10-30"), "unknown", SUMMER)).isFalse();
    }

    @Test
    void latestHistoryRowUsesSameIdentityWindowWhileOlderRowsStayFrozen() {
        Map<String, Object> older = row("2026-10-29");
        older.put("id", 14);
        Map<String, Object> latest = row("2026-10-30");
        List<Map<String, Object>> history = new ArrayList<>(List.of(older, latest));
        Map<String, Object> live = live("2026-10-30");
        live.put("stocks", List.of(Map.of("market", "美股", "liveValue", 250),
                Map.of("market", "台股", "liveValue", 200)));
        LiveAssetsOverlay.applyToLatest(history, live, SUMMER);
        assertThat(older.get("totalAssets")).isEqualTo(300);
        assertThat((BigDecimal) latest.get("totalAssets")).isEqualByComparingTo("450");
        assertThat((BigDecimal) latest.get("totalUsStockValue")).isEqualByComparingTo("250");
        assertThat((BigDecimal) latest.get("increase")).isEqualByComparingTo("150");

        for (String key : List.of("snapshotId", "snapshotDate")) {
            Map<String, Object> invalidLive = new HashMap<>(live);
            invalidLive.remove(key);
            Map<String, Object> frozen = row("2026-10-30");
            LiveAssetsOverlay.applyToLatest(List.of(frozen), invalidLive, SUMMER);
            assertThat(frozen.get("totalAssets")).isEqualTo(300);
        }
    }

    private static Map<String, Object> row(String date) {
        Map<String, Object> row = snapshot(date);
        row.putAll(Map.of("totalTwStockValue", 100, "totalUsStockValue", 200,
                "totalAssets", 300, "totalDeposit", 0, "totalFundValue", 0));
        return row;
    }

    private static Map<String, Object> snapshot(String date) {
        return new HashMap<>(Map.of("id", 15, "snapshotDate", date));
    }

    private static Map<String, Object> live(String date) {
        return new HashMap<>(Map.of("snapshotId", 15, "snapshotDate", date));
    }

    private static Clock clock(String instant) { return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC); }
}
