package com.steven.assets.bff.publictransaction;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 112: public BFF rejects ambiguous filters before configured-admin bootstrap or outbound I/O. */
class PublicTransactionHistoryFilterTest {

    @Test
    void parsesOnlyAllYearOrCompleteInclusiveDateRange() {
        var all = PublicTransactionHistoryFilter.parse(null, null, null);
        assertThat(all.year()).isNull();
        assertThat(all.start()).isNull();
        assertThat(all.end()).isNull();

        var year = PublicTransactionHistoryFilter.parse(List.of("2026"), null, null);
        assertThat(year.year()).isEqualTo("2026");
        assertThat(year.start()).isNull();

        var range = PublicTransactionHistoryFilter.parse(null, List.of("2026-01-01"), List.of("2026-01-31"));
        assertThat(range.year()).isNull();
        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void rejectsDuplicatesWhitespaceMixedModesIncompleteOrReversedRange() {
        for (Runnable invocation : List.<Runnable>of(
                () -> PublicTransactionHistoryFilter.parse(List.of("2026", "2025"), null, null),
                () -> PublicTransactionHistoryFilter.parse(List.of(" 2026"), null, null),
                () -> PublicTransactionHistoryFilter.parse(List.of("2026"), List.of("2026-01-01"), null),
                () -> PublicTransactionHistoryFilter.parse(null, List.of("2026-01-01"), null),
                () -> PublicTransactionHistoryFilter.parse(null, List.of("2026-02-01"), List.of("2026-01-01")))) {
            assertThatThrownBy(invocation::run).isInstanceOf(PublicTransactionHistoryRequestException.class);
        }
    }
}
