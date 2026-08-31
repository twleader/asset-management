package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertChartRendererIntradaySessionTest {
    @Test
    void usesExRightsSessionAndNeverQueriesSeparateRawHistory() {
        HistoricalDataService historical = mock(HistoricalDataService.class);
        when(historical.fetchIntradaySession("00881", "台股", null)).thenReturn(session(
                HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE, new BigDecimal("50.55"), new BigDecimal("-1.07")));
        var png = new AlertChartRenderer(historical).renderIntradayPng("00881", "台股");
        assertThat(png).isPresent();
        assertThat(png.orElseThrow()).isNotEmpty();
        verify(historical, never()).getStockHistory(anyString(), anyString(), any(), any());
    }

    @Test
    void unavailableSessionStillRendersNeutralTicksAndAllLabelsAreStable() {
        HistoricalDataService historical = mock(HistoricalDataService.class);
        when(historical.fetchIntradaySession("00881", "台股", null)).thenReturn(session(
                HistoricalDataService.ComparisonKind.UNAVAILABLE, null, null));
        assertThat(new AlertChartRenderer(historical).renderIntradayPng("00881", "台股")).isPresent();
        verify(historical, never()).getStockHistory(anyString(), anyString(), any(), any());
        assertThat(HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE.displayLabel()).isEqualTo("昨收");
        assertThat(HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE.displayLabel()).isEqualTo("除權息參考價");
        assertThat(HistoricalDataService.ComparisonKind.SESSION_REFERENCE.displayLabel()).isEqualTo("參考價");
        assertThat(HistoricalDataService.ComparisonKind.UNAVAILABLE.displayLabel()).isEqualTo("比較基準");
    }

    private static HistoricalDataService.IntradaySession session(HistoricalDataService.ComparisonKind kind,
                                                                  BigDecimal comparison, BigDecimal change) {
        return new HistoricalDataService.IntradaySession(LocalDate.of(2026, 8, 18),
                List.of(new HistoricalDataService.IntradayTick("2026-08-18T09:00:00", new BigDecimal("49.80")),
                        new HistoricalDataService.IntradayTick("2026-08-18T13:30:00", new BigDecimal("49.48"))),
                comparison, comparison == null ? null : LocalDate.of(2026, 8, 18),
                comparison == null ? null : "TWSE_MIS_Y",
                comparison, kind, comparison == null ? null : "TWSE_MIS_Y", new BigDecimal("49.48"),
                change, change == null ? null : change.multiply(BigDecimal.valueOf(100))
                        .divide(comparison, 6, java.math.RoundingMode.HALF_UP));
    }
}
