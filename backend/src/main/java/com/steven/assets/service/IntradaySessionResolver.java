package com.steven.assets.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Pure comparison semantics shared by the popup and alert chart; it has no HTTP, Redis, or JPA
 * dependency. Taiwan individual stocks accept only an exact-date {@code TWSE_MIS_Y} reference.
 * Fubon {@code previousClose} and Yahoo values are not semantically verified same-session MIS
 * {@code d/y} facts, so they fail closed rather than being substituted by source priority.
 */
public final class IntradaySessionResolver {
    private IntradaySessionResolver() {
    }

    public static HistoricalDataService.IntradaySession resolve(
            String code, String market, LocalDate tradingDate,
            List<HistoricalDataService.IntradayTick> ticks,
            BigDecimal sourceReference, LocalDate sourceReferenceDate, String sourceReferenceName,
            BigDecimal rawPreviousClose, String rawSource) {
        List<HistoricalDataService.IntradayTick> safeTicks = ticks == null ? List.of() : List.copyOf(ticks);
        BigDecimal last = lastPositive(safeTicks);
        boolean twIndividual = "台股".equals(market) && !"0000".equals(code);
        BigDecimal comparison = null;
        HistoricalDataService.ComparisonKind kind = HistoricalDataService.ComparisonKind.UNAVAILABLE;
        String source = null;
        boolean validReference = sourceReference != null && sourceReference.signum() > 0
                && tradingDate != null && tradingDate.equals(sourceReferenceDate)
                && "TWSE_MIS_Y".equals(sourceReferenceName);
        boolean qualifiedTwReference = twIndividual && validReference;
        if (twIndividual) {
            if (qualifiedTwReference) {
                comparison = sourceReference;
                source = "TWSE_MIS_Y";
                kind = rawPreviousClose == null ? HistoricalDataService.ComparisonKind.SESSION_REFERENCE
                        : rawPreviousClose.compareTo(sourceReference) == 0
                        ? HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE
                        : HistoricalDataService.ComparisonKind.EX_RIGHTS_REFERENCE;
            }
        } else if (rawPreviousClose != null && rawPreviousClose.signum() > 0) {
            comparison = rawPreviousClose;
            source = rawSource;
            kind = HistoricalDataService.ComparisonKind.PREVIOUS_CLOSE;
        }
        BigDecimal change = last != null && comparison != null ? last.subtract(comparison) : null;
        BigDecimal percent = change == null ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(comparison, 6, RoundingMode.HALF_UP);
        return new HistoricalDataService.IntradaySession(tradingDate, safeTicks,
                qualifiedTwReference ? sourceReference : null,
                qualifiedTwReference ? sourceReferenceDate : null,
                qualifiedTwReference ? sourceReferenceName : null,
                comparison, kind, source, last, change, percent);
    }

    private static BigDecimal lastPositive(List<HistoricalDataService.IntradayTick> ticks) {
        for (int i = ticks.size() - 1; i >= 0; i--) {
            BigDecimal value = ticks.get(i).price();
            if (value != null && value.signum() > 0) return value;
        }
        return null;
    }
}
