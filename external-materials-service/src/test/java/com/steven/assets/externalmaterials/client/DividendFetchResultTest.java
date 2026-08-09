package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.DividendSnapshotStore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DividendFetchResultTest {

    @Test
    void typedStatusDistinguishesSuccessfulEmptyFromFailedFetch() {
        DividendFetchClient.DividendFetchResult empty = new DividendFetchClient.DividendFetchResult(
                "FinMind", List.of(), DividendFetchClient.FetchStatus.EMPTY_COMPLETE,
                LocalDate.of(2016, 1, 1), LocalDate.of(2026, 1, 1), null, null);
        DividendFetchClient.DividendFetchResult failed = new DividendFetchClient.DividendFetchResult(
                "FinMind", List.of(), DividendFetchClient.FetchStatus.FAILED,
                LocalDate.of(2016, 1, 1), LocalDate.of(2026, 1, 1), null, "timeout");

        assertTrue(empty.complete());
        assertFalse(failed.complete());
        assertTrue(failed.errorReason().contains("timeout"));
    }

    @Test
    void legacyConstructorIsConservativeWithoutUpcomingScopeProof() {
        DividendFetchClient.DividendFetchResult result = new DividendFetchClient.DividendFetchResult(
                "NASDAQ", List.of(new DividendFetchClient.DividendEvent(
                        2026, new BigDecimal("1.00"), BigDecimal.ZERO,
                        "2026-01-10", "2026-02-01", null)));

        assertFalse(result.complete());
        assertTrue(result.status() == DividendFetchClient.FetchStatus.PARTIAL);
        assertFalse(result.scopeTo().isAfter(LocalDate.now()));
        assertTrue(result.errorReason().contains("未提供可驗證"));
    }

    @Test
    void partialAndFailedNeverCountAsComplete() {
        var partial = new DividendFetchClient.DividendFetchResult(
                "NASDAQ", List.of(), DividendFetchClient.FetchStatus.PARTIAL,
                LocalDate.now().minusYears(1), LocalDate.now(), null, "one provider failed");
        var failed = new DividendFetchClient.DividendFetchResult(
                "NASDAQ", List.of(), DividendFetchClient.FetchStatus.FAILED,
                LocalDate.now().minusYears(1), LocalDate.now(), null, "all providers failed");

        assertFalse(partial.complete());
        assertFalse(failed.complete());
    }

    @Test
    void canonicalSnapshotHashIsOrderIndependentAndEventKeyIsStable() {
        var a = new DividendFetchClient.DividendEvent(
                2026, new BigDecimal("1.0000"), BigDecimal.ZERO,
                "2026-01-10", null, null);
        var b = new DividendFetchClient.DividendEvent(
                2025, new BigDecimal("0.5000"), BigDecimal.ZERO,
                "2025-01-10", null, null);
        assertTrue(DividendSnapshotStore.canonicalContentHash(List.of(a, b))
                .equals(DividendSnapshotStore.canonicalContentHash(List.of(b, a))));
        assertTrue(DividendSnapshotStore.canonicalEventHash(a)
                .equals(DividendSnapshotStore.canonicalEventHash(a)));
    }
}
