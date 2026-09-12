package com.steven.assets.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Persistence-only observations for FundamentalAnalysisService.  The repository does not choose
 * provider priority, revisions, scores, or applicability; those are service-layer as-of rules.
 */
public interface FundamentalAnalysisBatchRepository {

    record Key(String stockCode, String market) {}

    record FinancialObservation(
            int year, int quarter, BigDecimal eps, BigDecimal income, BigDecimal equity,
            String provider, String sourceUrls, Instant availableAt, Instant observedAt) {}

    record RevenueObservation(
            int year, int month, String industryName, BigDecimal yoy,
            String provider, String sourceUrls, Instant availableAt, Instant observedAt) {}

    record ValuationObservation(
            LocalDate date, BigDecimal pe, BigDecimal pb, BigDecimal dividendYieldPct, Boolean loss,
            String provider, String sourceUrls, Instant availableAt, Instant observedAt) {}

    record IndustryObservation(
            String industryName, int year, int month, BigDecimal yoy, int companyCount,
            String provider, String sourceUrls, Instant availableAt, Instant observedAt) {}

    record Snapshot(
            List<FinancialObservation> financials,
            List<RevenueObservation> revenues,
            List<ValuationObservation> valuations,
            List<IndustryObservation> industries) {
        public Snapshot {
            financials = financials == null ? List.of() : List.copyOf(financials);
            revenues = revenues == null ? List.of() : List.copyOf(revenues);
            valuations = valuations == null ? List.of() : List.copyOf(valuations);
            industries = industries == null ? List.of() : List.copyOf(industries);
        }

        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of(), List.of());
        }
    }

    boolean hasEtfNav(String stockCode, String market);

    Snapshot findSnapshot(Key key, Instant latestInstant);

    Map<Key, Snapshot> findSnapshots(Collection<Key> keys, Instant latestInstant);
}
