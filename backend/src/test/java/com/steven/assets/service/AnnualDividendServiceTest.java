package com.steven.assets.service;

import com.steven.assets.model.AnnualDividendData.*;
import com.steven.assets.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
import com.steven.assets.model.AnnualDividendCalculator;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnualDividendServiceTest {
    @Test void missingIdentityFailsBeforePrivateRepositoryAccess() {
        TenantGuard guard = mock(TenantGuard.class);
        AnnualDividendReadRepository repository = mock(AnnualDividendReadRepository.class);
        assertThatThrownBy(() -> new AnnualDividendService(guard, repository).summary(null, null))
                .isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(repository);
    }
    @Test void taipeiDefaultYearAndEffectiveOwnerArePassedExplicitlyAndSelectedYearRemainsAvailable() {
        TenantGuard guard = mock(TenantGuard.class);
        when(guard.requireCurrentUserId()).thenReturn(42L);
        AnnualDividendReadRepository repository = mock(AnnualDividendReadRepository.class);
        when(repository.load(42, LocalDate.of(2027, 1, 1))).thenReturn(
                new Facts(List.of(), List.of(), List.of(), List.of(), List.of(new Market("台股", "台灣股市"))));
        AnnualDividendService service = new AnnualDividendService(guard, repository,
                Clock.fixed(Instant.parse("2026-12-31T16:01:00Z"), ZoneOffset.UTC));
        assertThat(service.summary(null, null).selectedYear()).isEqualTo(2027);
        assertThat(service.summary("2025", "").availableYears()).containsExactly(2027, 2025);
        assertThatThrownBy(() -> service.summary("2026", "不存在")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidYearFailsWithoutReadingPrivateData() {
        TenantGuard guard = mock(TenantGuard.class);
        when(guard.requireCurrentUserId()).thenReturn(42L);
        AnnualDividendReadRepository repository = mock(AnnualDividendReadRepository.class);
        var service = new AnnualDividendService(guard, repository);
        for (String year : List.of("", "1899", "2101", " 2026", "2026x"))
            assertThatThrownBy(() -> service.summary(year, "")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void multipleStocksDeduplicateAndRecomputeWholeSummaryWithoutShrinkingOptionsOrRereadingFacts() {
        var repository = mock(AnnualDividendReadRepository.class);
        var service = stockService(repository);
        String tw = AnnualDividendCalculator.stockKey("台股", "ABC");
        String us = AnnualDividendCalculator.stockKey("美股", "ABC");
        var result = service.summary("2026", "", List.of(us, tw, tw));
        assertThat(result.selectedStockKeys()).containsExactlyElementsOf(List.of(tw, us).stream().sorted().toList());
        assertThat(result.stockOptions()).extracting(StockOption::code).containsExactly("ABC", "MISSING", "ABC");
        assertThat(result.rows()).hasSize(3);
        assertThat(result.gaps()).isEmpty();
        assertThat(result.unavailableCount()).isZero();
        assertThat(result.currencyTotals()).extracting(CurrencyTotal::currency).containsExactly("TWD", "USD");
        assertThat(result.currencyTotals().getFirst().historicalEstimate()).isEqualByComparingTo("20.00");
        assertThat(result.currencyTotals().getFirst().futureEstimate()).isEqualByComparingTo("30.00");
        assertThat(result.currencyTotals().getLast().total()).isEqualByComparingTo("0.15");
        verify(repository, times(1)).load(42, LocalDate.of(2026, 11, 5));
    }

    @Test void selectingGapOnlyKeepsItSelectableAndDoesNotInventCurrencyZeroWhileClearingRestoresAll() {
        var repository = mock(AnnualDividendReadRepository.class);
        var service = stockService(repository);
        var full = service.summary("2026", "");
        var selected = service.summary("2026", "", List.of(AnnualDividendCalculator.stockKey("台股", "MISSING")));
        assertThat(selected.rows()).isEmpty();
        assertThat(selected.gaps()).hasSize(1);
        assertThat(selected.unavailableCount()).isEqualTo(1);
        assertThat(selected.currencyTotals()).isEmpty();
        assertThat(selected.stockOptions()).isEqualTo(full.stockOptions());
        var clear = service.summary("2026", "", List.of());
        assertThat(clear).isEqualTo(full);
        assertThat(clear.selectedStockKeys()).isEmpty();
    }

    @Test void stockScopeIntersectsYearAndMarketAndRejectsBlankOrUnknownKeysWithoutChangingIdentity() {
        var service = stockService(mock(AnnualDividendReadRepository.class));
        String tw = AnnualDividendCalculator.stockKey("台股", "ABC");
        String us = AnnualDividendCalculator.stockKey("美股", "ABC");
        assertThat(tw).isNotEqualTo(us);
        var taiwan = service.summary("2026", "台股", List.of(tw));
        assertThat(taiwan.rows()).allMatch(row -> row.market().equals("台股"));
        assertThat(taiwan.currencyTotals()).extracting(CurrencyTotal::currency).containsExactly("TWD");
        assertThat(taiwan.stockOptions()).extracting(StockOption::market).containsOnly("台股");
        assertThatThrownBy(() -> service.summary("2026", "台股", List.of(us))).isInstanceOf(IllegalArgumentException.class);
        String onlyOlderOption = service.summary("2025", "").stockOptions().stream()
                .filter(option -> option.code().equals("PURE")).findFirst().orElseThrow().key();
        assertThatThrownBy(() -> service.summary("2026", "", List.of(onlyOlderOption))).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : List.of("", " ", "not-a-key", AnnualDividendCalculator.stockKey("台股", "OTHER_OWNER")))
            assertThatThrownBy(() -> service.summary("2026", "", List.of(invalid))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.summary("2026", "", java.util.Collections.singletonList(null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AnnualDividendService stockService(AnnualDividendReadRepository repository) {
        TenantGuard guard = mock(TenantGuard.class);
        when(guard.requireCurrentUserId()).thenReturn(42L);
        var securities = List.of(new Security("ABC", "台股同代號", "台股"), new Security("ABC", "US same ticker", "美股"),
                new Security("MISSING", "缺少配息資料", "台股"), new Security("PURE", "純配股", "台股"));
        var snapshot = new Snapshot(LocalDate.of(2026, 1, 1), List.of(new Position("ABC", "台股", new BigDecimal("10")),
                new Position("ABC", "美股", new BigDecimal("0.125")), new Position("MISSING", "台股", new BigDecimal("5")),
                new Position("PURE", "台股", new BigDecimal("10"))));
        var events = List.of(new Event(1, "ABC", "台股", 2025, LocalDate.of(2026, 3, 1), null, null, new BigDecimal("2"), null, "official"),
                new Event(2, "ABC", "台股", 2025, LocalDate.of(2026, 12, 1), null, null, new BigDecimal("3"), null, "official"),
                new Event(3, "ABC", "美股", 2025, LocalDate.of(2026, 6, 1), null, null, new BigDecimal("1.23456"), null, "official"),
                new Event(4, "PURE", "台股", 2026, null, LocalDate.of(2026, 9, 1), null, BigDecimal.ZERO, BigDecimal.ONE, "official"));
        when(repository.load(42, LocalDate.of(2026, 11, 5))).thenReturn(new Facts(securities, List.of(snapshot), List.of(), events,
                List.of(new Market("台股", "台灣股市"), new Market("美股", "美國股市"))));
        return new AnnualDividendService(guard, repository, Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC));
    }
}
