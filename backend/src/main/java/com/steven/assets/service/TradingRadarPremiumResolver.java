package com.steven.assets.service;

import com.steven.assets.model.EtfNavHistory;
import com.steven.assets.model.EtfNavObservation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ETF premium dated-observation policy，將 freshness 判斷與 repository I/O 分離。
 * stale 值只回 provenance，不回傳可進規則 veto 的數值。
 */
public final class TradingRadarPremiumResolver {

    private TradingRadarPremiumResolver() {}

    public record Observation(
            BigDecimal value,
            LocalDate asOfDate,
            String source,
            boolean stale
    ) {
        public static Observation unavailable(boolean stale) {
            return new Observation(null, null, null, stale);
        }
    }

    public enum DecisionStatus { AVAILABLE, MISSING, STALE, NOT_APPLICABLE }

    /** Append-only decision-time result; unavailable values never enter the premium veto. */
    public record DecisionObservation(
            DecisionStatus status,
            BigDecimal value,
            LocalDate asOfDate,
            String source,
            Instant observedAt,
            Instant availableAt,
            String availabilityBasis,
            String missingReason) {

        public boolean available() {
            return status == DecisionStatus.AVAILABLE && value != null;
        }

        public boolean stale() {
            return status == DecisionStatus.STALE;
        }

        public static DecisionObservation missing(DecisionStatus status, String reason) {
            return new DecisionObservation(status, null, null, null,
                    null, null, null, reason);
        }
    }

    public static Observation resolve(
            LocalDate targetDate,
            PriceQueryService.EtfNav redisObservation,
            EtfNavHistory exactDbObservation,
            List<EtfNavHistory> priorDbObservations) {
        if (targetDate == null) return Observation.unavailable(true);
        if (redisObservation != null && redisObservation.premiumDiscountPct() != null) {
            LocalDate redisDate = parseNavDate(redisObservation.navAsOf());
            if (targetDate.equals(redisDate)) {
                return new Observation(redisObservation.premiumDiscountPct(), redisDate,
                        nonBlank(redisObservation.source(), "REDIS_ETF_NAV"), false);
            }
        }
        if (exactDbObservation != null
                && targetDate.equals(exactDbObservation.getNavDate())
                && exactDbObservation.getPremiumDiscountPct() != null) {
            return new Observation(exactDbObservation.getPremiumDiscountPct(),
                    exactDbObservation.getNavDate(),
                    nonBlank(exactDbObservation.getSource(), "POSTGRES_ETF_NAV"), false);
        }
        boolean stale = (redisObservation != null && redisObservation.premiumDiscountPct() != null)
                || (priorDbObservations != null && priorDbObservations.stream()
                .anyMatch(row -> row != null && row.getNavDate() != null
                        && row.getPremiumDiscountPct() != null
                        && row.getNavDate().isBefore(targetDate)));
        return Observation.unavailable(stale);
    }

    /**
     * Resolve one Taiwan ETF premium from immutable observations as known at the
     * exact signal instant.  US premium is an explicit N/A contract.
     */
    public static DecisionObservation resolveAsOf(
            String market,
            LocalDate targetDate,
            Instant decisionInstant,
            List<EtfNavObservation> observations) {
        if (!"台股".equals(market)) {
            return DecisionObservation.missing(DecisionStatus.NOT_APPLICABLE,
                    "ETF premium 僅適用台股；其他市場不進正式 factor");
        }
        if (targetDate == null || decisionInstant == null) {
            return DecisionObservation.missing(DecisionStatus.MISSING,
                    "premium target date／decision instant 缺漏");
        }
        List<EtfNavObservation> rows = observations == null ? List.of() : observations.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getNavDate() != null && !row.getNavDate().isAfter(targetDate))
                .toList();
        boolean malformed = rows.stream().anyMatch(row -> row.getPremiumDiscountPct() != null
                && !structurallyDated(row));
        if (malformed) {
            return DecisionObservation.missing(DecisionStatus.MISSING,
                    "premium observation 缺 observed-at／available-at／basis／provider");
        }
        List<EtfNavObservation> eligible = rows.stream()
                .filter(TradingRadarPremiumResolver::structurallyDated)
                .filter(row -> row.getPremiumDiscountPct() != null)
                .filter(row -> !row.getObservedAt().isAfter(decisionInstant)
                        && !row.getAvailableAt().isAfter(decisionInstant))
                .toList();
        Comparator<EtfNavObservation> latest = Comparator
                .comparing(EtfNavObservation::getAvailableAt)
                .thenComparing(EtfNavObservation::getObservedAt)
                .thenComparing(row -> row.getId() == null ? 0L : row.getId());
        EtfNavObservation exact = eligible.stream()
                .filter(row -> targetDate.equals(row.getNavDate()))
                .max(latest).orElse(null);
        if (exact != null) {
            return new DecisionObservation(DecisionStatus.AVAILABLE,
                    exact.getPremiumDiscountPct(), exact.getNavDate(), exact.getSource(),
                    exact.getObservedAt(), exact.getAvailableAt(), exact.getAvailabilityBasis(), null);
        }
        boolean stale = eligible.stream().anyMatch(row -> row.getNavDate().isBefore(targetDate));
        return DecisionObservation.missing(stale ? DecisionStatus.STALE : DecisionStatus.MISSING,
                stale ? "decision instant 前只有較舊 premium observation"
                        : "decision instant 前沒有 target-date premium observation");
    }

    /**
     * Latest-known revision per NAV date, newest date first, for as-of percentile
     * calculation.  Later observations of an earlier date remain invisible.
     */
    public static List<BigDecimal> premiumHistoryAsOf(
            String market,
            LocalDate throughDate,
            Instant decisionInstant,
            List<EtfNavObservation> observations,
            int maximumDates) {
        if (!"台股".equals(market) || throughDate == null || decisionInstant == null
                || observations == null || maximumDates <= 0) return List.of();
        Comparator<EtfNavObservation> latest = Comparator
                .comparing(EtfNavObservation::getAvailableAt)
                .thenComparing(EtfNavObservation::getObservedAt)
                .thenComparing(row -> row.getId() == null ? 0L : row.getId());
        Map<LocalDate, EtfNavObservation> byDate = new LinkedHashMap<>();
        observations.stream()
                .filter(Objects::nonNull)
                .filter(TradingRadarPremiumResolver::structurallyDated)
                .filter(row -> row.getPremiumDiscountPct() != null && row.getNavDate() != null
                        && !row.getNavDate().isAfter(throughDate)
                        && !row.getObservedAt().isAfter(decisionInstant)
                        && !row.getAvailableAt().isAfter(decisionInstant))
                .forEach(row -> byDate.merge(row.getNavDate(), row,
                        (left, right) -> latest.compare(left, right) >= 0 ? left : right));
        return byDate.values().stream()
                .sorted(Comparator.comparing(EtfNavObservation::getNavDate).reversed())
                .limit(maximumDates)
                .map(EtfNavObservation::getPremiumDiscountPct)
                .toList();
    }

    public static LocalDate parseNavDate(String navAsOf) {
        if (navAsOf == null || navAsOf.isBlank()) return null;
        String raw = navAsOf.trim();
        try {
            if (raw.length() >= 8 && raw.substring(0, 8).chars().allMatch(Character::isDigit)) {
                return LocalDate.parse(raw.substring(0, 8),
                        java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            }
            return LocalDate.parse(raw.substring(0, Math.min(raw.length(), 10)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean structurallyDated(EtfNavObservation row) {
        return row != null && row.getNavDate() != null
                && row.getObservedAt() != null && row.getAvailableAt() != null
                && !row.getAvailableAt().isBefore(row.getObservedAt())
                && row.getAvailabilityBasis() != null && !row.getAvailabilityBasis().isBlank()
                && row.getSource() != null && !row.getSource().isBlank()
                && finite(row.getPremiumDiscountPct());
    }

    private static boolean finite(BigDecimal value) {
        return value != null && Double.isFinite(value.doubleValue());
    }
}
