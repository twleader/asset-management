package com.steven.assets.service;

import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.model.TwseInstitutionalDaily;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.util.MarketZones;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure as-of adapter for t307.7 numeric market candidates.  It never parses
 * news text.  Its aggregate is consumed only by the offline V13 candidate
 * path; production V12 and unpromoted runtime keys remain unchanged.
 */
public final class TradingRadarMarketFeatureResolver {

    public static final List<String> CODES = List.of(
            "IXIC_RET5", "SOX_RET5", "SPX_RET5", "DJI_RET5", "INDEX_VOLUME_RATIO20",
            "TW_INSTITUTIONAL_NET_TURNOVER", "WTI_RET5", "BRENT_RET5", "GOLD_RET5");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int SCALE = 8;
    private static final double CONTRIBUTION_MIN = -1.0;
    private static final double CONTRIBUTION_MAX = 1.0;
    /** Five-session return unit: +/-2.5 percentage points saturates one contribution unit. */
    private static final double RETURN_SCALE_PERCENT = 2.5;
    /** Volume ratio is centred at its neutral value 1.0; +/-0.5 ratio saturates. */
    private static final double VOLUME_RATIO_SCALE = 0.5;
    /** Institutional net turnover is a signed ratio; 5% of turnover is a full unit. */
    private static final double INSTITUTIONAL_TURNOVER_SCALE = 0.05;

    private TradingRadarMarketFeatureResolver() {}

    public record Sources(
            List<UsIndexDailyHistory> usIndices,
            List<TwseIndexDailyHistory> twIndices,
            Map<String, List<CommodityPriceHistory>> commodities,
            List<TwseInstitutionalDaily> institutional) {
        public Sources {
            usIndices = usIndices == null ? List.of() : List.copyOf(usIndices);
            twIndices = twIndices == null ? List.of() : List.copyOf(twIndices);
            commodities = commodities == null ? Map.of() : Map.copyOf(commodities);
            institutional = institutional == null ? List.of() : List.copyOf(institutional);
        }

        public Sources(
                List<UsIndexDailyHistory> usIndices,
                List<TwseIndexDailyHistory> twIndices,
                Map<String, List<CommodityPriceHistory>> commodities) {
            this(usIndices, twIndices, commodities, List.of());
        }

        public static Sources empty() {
            return new Sources(List.of(), List.of(), Map.of(), List.of());
        }
    }

    /** Calendar-selected terminal sessions for strict production evidence. */
    public record ExpectedSessions(
            LocalDate marketSession,
            LocalDate usSession,
            LocalDate commoditySession,
            boolean strict) {
        public static ExpectedSessions none() {
            return new ExpectedSessions(null, null, null, false);
        }

        public static ExpectedSessions strict(
                LocalDate marketSession, LocalDate usSession, LocalDate commoditySession) {
            return new ExpectedSessions(marketSession, usSession, commoditySession, true);
        }
    }

    public record Evidence(
            String market,
            Instant decisionInstant,
            Map<String, CandidateMarketFeature> features) {
        public Evidence {
            features = features == null ? Map.of() : Map.copyOf(features);
        }

        public CandidateMarketFeature feature(String code) {
            return features.getOrDefault(code,
                    CandidateMarketFeature.unavailable(code, "market feature 未建立",
                            applicabilityForCode(code, market)));
        }

        public boolean hasAvailable(String code) {
            return feature(code).availableAt(decisionInstant);
        }

        /**
         * Aggregate the typed numeric candidates that are actually known at the
         * decision instant.  This is deliberately a candidate-only observation:
         * the legacy V12 engine never calls it.  A raw adapter value is kept
         * signed and bounded to {@code [-1, 1]} before taking the arithmetic
         * mean, so a single large percentage return cannot dominate the other
         * observations.  A feature which declares {@code duplicateOf} is
         * disclosure-only and is excluded from both the numerator and coverage
         * denominator; it is already represented by the primary factor (for
         * example IXIC/SOX versus MARKET_REGIME).
         */
        public AggregatedContribution aggregateContribution() {
            return aggregate(features, decisionInstant, null, market);
        }

        /**
         * Aggregate only candidates applicable to the strict asset profile and
         * evidence market.  BOND ETF profiles therefore do not let an
         * {@code *_EQUITY} observation (including TW institutional flow) enter
         * the candidate score or its coverage denominator.
         */
        public AggregatedContribution aggregateContribution(
                TradingRadarAssetProfileResolver.AssetProfile profile) {
            return aggregate(features, decisionInstant, profile, market);
        }

        /** Short-horizon alias kept explicit for CandidateContext wiring. */
        public AggregatedContribution shortContribution() {
            return aggregateContribution();
        }

        public AggregatedContribution shortContribution(
                TradingRadarAssetProfileResolver.AssetProfile profile) {
            return aggregateContribution(profile);
        }

        /** Medium-horizon alias kept explicit for CandidateContext wiring. */
        public AggregatedContribution mediumContribution() {
            return aggregateContribution();
        }

        public AggregatedContribution mediumContribution(
                TradingRadarAssetProfileResolver.AssetProfile profile) {
            return aggregateContribution(profile);
        }

        public static Evidence empty(String market, Instant decisionInstant, String reason) {
            Map<String, CandidateMarketFeature> missing = new LinkedHashMap<>();
            for (String code : CODES) {
                missing.put(code, CandidateMarketFeature.unavailable(
                        code, reason, applicabilityForCode(code, market)));
            }
            return new Evidence(market, decisionInstant, missing);
        }
    }

    /**
     * Candidate-only market feature summary.  {@code value} is null when no
     * non-duplicate AVAILABLE feature was known at the decision instant;
     * coverage remains explicit so a missing/disclosure-only source cannot be
     * mistaken for a neutral zero.
     */
    public record AggregatedContribution(
            Double value,
            int availableCount,
            int eligibleCount,
            List<String> unavailableReasons) {
        public AggregatedContribution {
            if (availableCount < 0 || eligibleCount < 0 || availableCount > eligibleCount) {
                throw new IllegalArgumentException("market feature contribution count 不合法");
            }
            unavailableReasons = unavailableReasons == null
                    ? List.of() : List.copyOf(unavailableReasons);
            if (value != null && (!Double.isFinite(value)
                    || value < CONTRIBUTION_MIN || value > CONTRIBUTION_MAX)) {
                throw new IllegalArgumentException("market feature contribution 必須介於 -1..1");
            }
        }

        public double coverage() {
            return eligibleCount <= 0 ? 0.0 : (double) availableCount / eligibleCount;
        }

        public boolean available() {
            return value != null && availableCount > 0;
        }

        /** Explicit horizon accessors keep CandidateContext wiring self-documenting. */
        public Double shortTerm() { return value; }

        public Double mediumTerm() { return value; }
    }

    private static AggregatedContribution aggregate(
            Map<String, CandidateMarketFeature> rawFeatures,
            Instant decisionInstant,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String market) {
        Map<String, CandidateMarketFeature> source = rawFeatures == null
                ? Map.of() : rawFeatures;
        double sum = 0.0;
        int available = 0;
        int eligible = 0;
        List<String> reasons = new ArrayList<>();
        for (String code : CODES) {
            CandidateMarketFeature feature = source.get(code);
            if (feature == null) {
                String applicability = applicabilityForCode(code, market);
                if (!applicableToProfile(applicability, profile, market)) {
                    reasons.add("MARKET_FEATURE_NOT_APPLICABLE[" + code
                            + "]: profileApplicability=" + applicability);
                    continue;
                }
                reasons.add("MARKET_FEATURE_MISSING[" + code + "]: feature 未建立");
                eligible++;
                continue;
            }
            // An explicit duplicate is still useful in the UI, but cannot be
            // promoted into a second score contribution or reduce coverage.
            if (feature.duplicateOf() != null && !feature.duplicateOf().isBlank()) {
                reasons.add("MARKET_FEATURE_DISCLOSURE_ONLY[" + code + "]: duplicateOf="
                        + feature.duplicateOf());
                continue;
            }
            if (!applicableToProfile(feature, profile, market)) {
                reasons.add("MARKET_FEATURE_NOT_APPLICABLE[" + code + "]: profileApplicability="
                        + feature.profileApplicability());
                continue;
            }
            if (CandidateMarketFeature.NOT_APPLICABLE.equals(feature.status())) {
                reasons.add("MARKET_FEATURE_NOT_APPLICABLE[" + code + "]: explicit status=N/A");
                continue;
            }
            eligible++;
            if (feature.availableAt(decisionInstant) && finite(feature.value())) {
                double contribution = contributionFor(feature.code(), feature.value());
                sum += contribution;
                available++;
                continue;
            }
            String status = feature.status() == null ? "UNKNOWN" : feature.status();
            String reason = feature.missingReason() == null || feature.missingReason().isBlank()
                    ? "不可得或尚未通過 available-at 邊界" : feature.missingReason();
            reasons.add("MARKET_FEATURE_" + status + "[" + code + "]: " + reason);
        }
        if (available == 0) {
            reasons.add("MARKET_FEATURE_NO_AVAILABLE：沒有可供 candidate 計分的非重複 numeric feature");
        }
        // Never re-scale a tiny observed subset to a full-strength signal.  The
        // eligible denominator keeps one available feature at 1/9 of its signed
        // effect (rather than amplifying it to 1.0) while preserving the explicit
        // coverage/reason disclosure for calibration and promotion.
        Double value = available == 0 || eligible == 0 ? null : clampUnit(sum / eligible);
        return new AggregatedContribution(value, available, eligible, reasons);
    }

    /** Convert each raw feature into its own dimensionless, centred candidate unit. */
    private static double contributionFor(String code, BigDecimal raw) {
        if (raw == null) return 0.0;
        double value = raw.doubleValue();
        if (!Double.isFinite(value)) return 0.0;
        if (code != null && code.endsWith("_RET5")) {
            return clampUnit(value / RETURN_SCALE_PERCENT);
        }
        if ("INDEX_VOLUME_RATIO20".equals(code)) {
            return clampUnit((value - 1.0) / VOLUME_RATIO_SCALE);
        }
        if ("TW_INSTITUTIONAL_NET_TURNOVER".equals(code)) {
            return clampUnit(value / INSTITUTIONAL_TURNOVER_SCALE);
        }
        return clampUnit(value);
    }

    private static boolean applicableToProfile(
            CandidateMarketFeature feature,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String market) {
        return applicableToProfile(feature.profileApplicability(), profile, market);
    }

    private static boolean applicableToProfile(
            String applicability,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String market) {
        if (profile == null) return true;
        if (applicability == null || applicability.isBlank()) {
            return true;
        }
        String normalized = applicability.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.equals(CandidateMarketFeature.NOT_APPLICABLE)
                || normalized.equals("N/A")) return false;
        if (normalized.contains("_")) {
            String featureMarket = normalized.substring(0, normalized.indexOf('_'));
            String normalizedMarket = market == null ? "" : market.trim().toUpperCase(java.util.Locale.ROOT);
            if (!featureMarket.equals("GLOBAL")
                    && !normalizedMarket.isBlank() && !featureMarket.equals(normalizedMarket)) return false;
        }
        boolean equityFeature = normalized.contains("EQUITY");
        boolean bondFeature = normalized.contains("BOND");
        if (profile.bond()) return bondFeature || !equityFeature;
        return !bondFeature;
    }

    /** Fixed candidate capability map; null values are never used as scope. */
    private static String applicabilityForCode(String code, String market) {
        if (code == null) return CandidateMarketFeature.NOT_APPLICABLE;
        if (code.endsWith("_RET5") && (code.startsWith("IXIC") || code.startsWith("SOX")
                || code.startsWith("SPX") || code.startsWith("DJI"))) {
            return "GLOBAL_EQUITY";
        }
        if (code.startsWith("WTI_") || code.startsWith("BRENT_") || code.startsWith("GOLD_")) {
            return "GLOBAL_ASSET_AGNOSTIC";
        }
        if ("INDEX_VOLUME_RATIO20".equals(code)) {
            return "台股".equals(market) || "美股".equals(market)
                    ? market + "_EQUITY" : CandidateMarketFeature.NOT_APPLICABLE;
        }
        if ("TW_INSTITUTIONAL_NET_TURNOVER".equals(code)) return "台股_EQUITY";
        return CandidateMarketFeature.NOT_APPLICABLE;
    }

    public static Evidence resolve(String market, Instant decisionInstant, Sources sources) {
        return resolve(market, decisionInstant, sources, ExpectedSessions.none());
    }

    /** Strict terminal-session overload used by the production V13 evidence path. */
    public static Evidence resolve(
            String market,
            Instant decisionInstant,
            Sources sources,
            ExpectedSessions expectedSessions) {
        Sources input = sources == null ? Sources.empty() : sources;
        if (market == null || decisionInstant == null) {
            return Evidence.empty(market, decisionInstant, "market／decision instant 缺漏");
        }
        ExpectedSessions expected = expectedSessions == null
                ? ExpectedSessions.none() : expectedSessions;
        LocalDate target = RadarObservationResolver.targetDate(market, decisionInstant);
        Map<String, CandidateMarketFeature> out = new LinkedHashMap<>();
        for (String index : List.of("IXIC", "SOX", "SPX", "DJI")) {
            String code = index + "_RET5";
            out.put(code, indexReturn(code, index, market, target, decisionInstant,
                    input.usIndices(), expected));
        }
        out.put("INDEX_VOLUME_RATIO20", volumeRatio(market, target, decisionInstant, input, expected));
        out.put("TW_INSTITUTIONAL_NET_TURNOVER",
                institutionalNetTurnover(market, target, decisionInstant, input, expected));
        for (String commodity : List.of("WTI", "BRENT", "GOLD")) {
            out.put(commodity + "_RET5", commodityReturn(
                    commodity, market, target, decisionInstant, input.commodities().get(commodity), expected));
        }
        return new Evidence(market, decisionInstant, out);
    }

    private static CandidateMarketFeature indexReturn(
            String code,
            String index,
            String market,
            LocalDate target,
            Instant decisionInstant,
            List<UsIndexDailyHistory> rows,
            ExpectedSessions expected) {
        if (expected.strict() && expected.usSession() == null) {
            return CandidateMarketFeature.unavailable(code,
                    "美股指數 completed terminal session 未由日曆確認",
                    applicabilityForCode(code, market));
        }
        LocalDate terminal = expected.strict() ? expected.usSession() : null;
        List<UsIndexDailyHistory> sorted = rows == null ? List.of() : rows.stream()
                .filter(row -> row != null && index.equals(row.getIndexCode())
                        && row.getTradingDate() != null && row.getClosePoint() != null
                        && row.getClosePoint().signum() > 0 && !row.getTradingDate().isAfter(target)
                        && (terminal == null || !row.getTradingDate().isAfter(terminal))
                        && !boundary(row.getTradingDate(), "美股").isAfter(decisionInstant))
                .sorted(Comparator.comparing(UsIndexDailyHistory::getTradingDate))
                .toList();
        if (sorted.size() < 6) return CandidateMarketFeature.unavailable(
                code, "美股指數不足 6 個完成日", applicabilityForCode(code, market));
        UsIndexDailyHistory latest = sorted.get(sorted.size() - 1);
        if (terminal != null && !terminal.equals(latest.getTradingDate())) {
            return CandidateMarketFeature.unavailable(code,
                    "美股指數缺少指定 completed terminal session " + terminal,
                    applicabilityForCode(code, market));
        }
        Instant availableAt = boundary(latest.getTradingDate(), "美股");
        BigDecimal previous = sorted.get(sorted.size() - 6).getClosePoint();
        BigDecimal value = latest.getClosePoint().divide(previous, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(SCALE, RoundingMode.HALF_UP);
        // Both TW and US market-regime builders consume the Nasdaq/SOX 40/60
        // composite.  Keep the individual legs visible for disclosure, but do
        // not let them enter the candidate aggregate a second time in either
        // market (the previous US-only flag double-counted TW V12/V13 runs).
        boolean regimeDuplicate = "IXIC".equals(index) || "SOX".equals(index);
        return new CandidateMarketFeature(code, value, latest.getTradingDate(), availableAt,
                "COMPLETED_CLOSE_18:00_ET", "US_INDEX_DAILY_HISTORY", null,
                applicabilityForCode(code, market), regimeDuplicate ? "MARKET_REGIME" : null,
                regimeDuplicate
                        ? CandidateMarketFeature.DISCLOSURE_ONLY : CandidateMarketFeature.AVAILABLE,
                null);
    }

    private static CandidateMarketFeature volumeRatio(
            String market, LocalDate target, Instant decisionInstant, Sources sources,
            ExpectedSessions expected) {
        if (expected.strict() && ("台股".equals(market)
                ? expected.marketSession() == null : expected.usSession() == null)) {
            return CandidateMarketFeature.unavailable("INDEX_VOLUME_RATIO20",
                    "市場量能 completed terminal session 未由日曆確認",
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market));
        }
        LocalDate terminal = expected.strict()
                ? ("台股".equals(market) ? expected.marketSession() : expected.usSession()) : null;
        if ("台股".equals(market)) {
            List<TwseIndexDailyHistory> rows = sources.twIndices().stream()
                    .filter(row -> row != null && row.getTradingDate() != null
                            && !row.getTradingDate().isAfter(target)
                            && (terminal == null || !row.getTradingDate().isAfter(terminal))
                            && !boundary(row.getTradingDate(), market).isAfter(decisionInstant)
                            && row.getTradeVolume() != null && row.getTradeVolume() > 0)
                    .sorted(Comparator.comparing(TwseIndexDailyHistory::getTradingDate)).toList();
            if (rows.size() < 21) return CandidateMarketFeature.unavailable(
                    "INDEX_VOLUME_RATIO20", "台股 TAIEX 正成交量不足 21 個完成日",
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market));
            TwseIndexDailyHistory latest = rows.get(rows.size() - 1);
            if (terminal != null && !terminal.equals(latest.getTradingDate())) {
                return CandidateMarketFeature.unavailable("INDEX_VOLUME_RATIO20",
                        "台股量能缺少指定 completed terminal session " + terminal,
                        applicabilityForCode("INDEX_VOLUME_RATIO20", market));
            }
            Instant availableAt = boundary(latest.getTradingDate(), market);
            List<BigDecimal> previous = rows.subList(rows.size() - 21, rows.size() - 1).stream()
                    .map(row -> BigDecimal.valueOf(row.getTradeVolume())).toList();
            BigDecimal median = median(previous);
            if (median == null || median.signum() <= 0) return CandidateMarketFeature.unavailable(
                    "INDEX_VOLUME_RATIO20", "台股 TAIEX 前 20 日量能 median 無效",
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market));
            BigDecimal value = BigDecimal.valueOf(latest.getTradeVolume())
                    .divide(median, 12, RoundingMode.HALF_UP).setScale(SCALE, RoundingMode.HALF_UP);
            return new CandidateMarketFeature("INDEX_VOLUME_RATIO20", value, latest.getTradingDate(),
                    availableAt, "COMPLETED_CLOSE_16:00_TW", "TWSE_INDEX_DAILY_HISTORY", null,
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market),
                    null, CandidateMarketFeature.AVAILABLE, null);
        }
        if ("美股".equals(market)) {
            List<UsIndexDailyHistory> rows = sources.usIndices().stream()
                    .filter(row -> row != null && "IXIC".equals(row.getIndexCode())
                            && row.getTradingDate() != null && !row.getTradingDate().isAfter(target)
                            && (terminal == null || !row.getTradingDate().isAfter(terminal))
                            && !boundary(row.getTradingDate(), market).isAfter(decisionInstant)
                            && row.getVolume() != null && row.getVolume() > 0)
                    .sorted(Comparator.comparing(UsIndexDailyHistory::getTradingDate)).toList();
            if (rows.size() < 21) return CandidateMarketFeature.unavailable(
                    "INDEX_VOLUME_RATIO20", "美股 IXIC 正成交量不足 21 個完成日",
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market));
            UsIndexDailyHistory latest = rows.get(rows.size() - 1);
            if (terminal != null && !terminal.equals(latest.getTradingDate())) {
                return CandidateMarketFeature.unavailable("INDEX_VOLUME_RATIO20",
                        "美股量能缺少指定 completed terminal session " + terminal,
                        applicabilityForCode("INDEX_VOLUME_RATIO20", market));
            }
            Instant availableAt = boundary(latest.getTradingDate(), market);
            BigDecimal median = median(rows.subList(rows.size() - 21, rows.size() - 1).stream()
                    .map(row -> BigDecimal.valueOf(row.getVolume())).toList());
            if (median == null || median.signum() <= 0) return CandidateMarketFeature.unavailable(
                    "INDEX_VOLUME_RATIO20", "美股 IXIC 前 20 日量能 median 無效",
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market));
            BigDecimal value = BigDecimal.valueOf(latest.getVolume())
                    .divide(median, 12, RoundingMode.HALF_UP).setScale(SCALE, RoundingMode.HALF_UP);
            return new CandidateMarketFeature("INDEX_VOLUME_RATIO20", value, latest.getTradingDate(),
                    availableAt, "COMPLETED_CLOSE_18:00_ET", "US_INDEX_DAILY_HISTORY", null,
                    applicabilityForCode("INDEX_VOLUME_RATIO20", market),
                    null, CandidateMarketFeature.AVAILABLE, null);
        }
        return CandidateMarketFeature.unavailable("INDEX_VOLUME_RATIO20", "市場不在 feature 白名單",
                applicabilityForCode("INDEX_VOLUME_RATIO20", market));
    }

    private static CandidateMarketFeature commodityReturn(
            String commodity, String market, LocalDate target, Instant decisionInstant,
            List<CommodityPriceHistory> rows, ExpectedSessions expected) {
        if (expected.strict() && expected.commoditySession() == null) {
            return CandidateMarketFeature.unavailable(commodity + "_RET5",
                    "原物料 completed terminal session 未由日曆確認",
                    applicabilityForCode(commodity + "_RET5", market));
        }
        LocalDate terminal = expected.strict() ? expected.commoditySession() : null;
        if (rows == null || rows.isEmpty()) return CandidateMarketFeature.unavailable(
                commodity + "_RET5", "原物料來源缺漏", applicabilityForCode(commodity + "_RET5", market));
        List<CommodityPriceHistory> sorted = rows.stream()
                .filter(row -> row != null && row.getPriceDate() != null && row.getClosePrice() != null
                        && row.getClosePrice().signum() > 0 && !row.getPriceDate().isAfter(target)
                        && (terminal == null || !row.getPriceDate().isAfter(terminal))
                        && !commodityAvailableAt(row).isAfter(decisionInstant))
                .sorted(Comparator.comparing(CommodityPriceHistory::getPriceDate)).toList();
        if (sorted.size() < 6) return CandidateMarketFeature.unavailable(
                commodity + "_RET5", "原物料不足 6 個完成日",
                applicabilityForCode(commodity + "_RET5", market));
        CommodityPriceHistory latest = sorted.get(sorted.size() - 1);
        if (terminal != null && !terminal.equals(latest.getPriceDate())) {
            return CandidateMarketFeature.unavailable(commodity + "_RET5",
                    "原物料缺少指定 completed terminal session " + terminal,
                    applicabilityForCode(commodity + "_RET5", market));
        }
        Instant availableAt = commodityAvailableAt(latest);
        BigDecimal previous = sorted.get(sorted.size() - 6).getClosePrice();
        BigDecimal value = latest.getClosePrice().divide(previous, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(HUNDRED).setScale(SCALE, RoundingMode.HALF_UP);
        return new CandidateMarketFeature(commodity + "_RET5", value, latest.getPriceDate(), availableAt,
                latest.getSourceAvailableAt() == null
                        ? "RECONSTRUCTED_CONSERVATIVE_NEXT_MIDNIGHT_ET"
                        : "SOURCE_AVAILABLE_AT",
                latest.getProvider() == null ? "COMMODITY_PRICE_HISTORY" : latest.getProvider(),
                latest.getSourceUrl(),
                applicabilityForCode(commodity + "_RET5", market),
                null, CandidateMarketFeature.AVAILABLE, null);
    }

    private static CandidateMarketFeature institutionalNetTurnover(
            String market,
            LocalDate target,
            Instant decisionInstant,
            Sources sources,
            ExpectedSessions expected) {
        String code = "TW_INSTITUTIONAL_NET_TURNOVER";
        if (!"台股".equals(market)) {
            return CandidateMarketFeature.unavailable(code, "三大法人 feature 只適用台股 EQUITY",
                    applicabilityForCode(code, market));
        }
        if (expected.strict() && expected.marketSession() == null) {
            return CandidateMarketFeature.unavailable(code,
                    "法人 completed terminal session 未由日曆確認", applicabilityForCode(code, market));
        }
        LocalDate terminal = expected.strict() ? expected.marketSession() : null;
        Map<LocalDate, TwseIndexDailyHistory> turnoverByDate = new LinkedHashMap<>();
        for (TwseIndexDailyHistory row : sources.twIndices()) {
            if (row == null || row.getTradingDate() == null || row.getTradeValue() == null
                    || row.getTradeValue().signum() <= 0 || row.getTradingDate().isAfter(target)
                    || (terminal != null && row.getTradingDate().isAfter(terminal))
                    || boundary(row.getTradingDate(), "台股").isAfter(decisionInstant)) {
                continue;
            }
            turnoverByDate.put(row.getTradingDate(), row);
        }
        List<TwseInstitutionalDaily> visible = sources.institutional().stream()
                .filter(row -> completeInstitutional(row, target))
                .filter(row -> !institutionalAvailableAt(row).isAfter(decisionInstant))
                .filter(row -> turnoverByDate.containsKey(row.getTradingDate()))
                .sorted(Comparator.comparing(TwseInstitutionalDaily::getTradingDate)
                        .thenComparing(TradingRadarMarketFeatureResolver::institutionalAvailableAt))
                .toList();
        if (visible.isEmpty()) {
            return CandidateMarketFeature.unavailable(code,
                    "法人 typed numeric observation 或同日 TAIEX 成交金額不可得；禁止從新聞字串反解析",
                    applicabilityForCode(code, market));
        }
        TwseInstitutionalDaily latest = visible.get(visible.size() - 1);
        if (terminal != null && !terminal.equals(latest.getTradingDate())) {
            return CandidateMarketFeature.unavailable(code,
                    "法人 typed observation 缺少指定 completed terminal session " + terminal,
                    applicabilityForCode(code, market));
        }
        BigDecimal turnover = turnoverByDate.get(latest.getTradingDate()).getTradeValue();
        BigDecimal value = latest.getTotalNet()
                .divide(turnover, 12, RoundingMode.HALF_UP)
                .setScale(SCALE, RoundingMode.HALF_UP);
        return new CandidateMarketFeature(
                code,
                value,
                latest.getTradingDate(),
                institutionalAvailableAt(latest),
                latest.getAvailabilityBasis() + "+COMPLETED_CLOSE_16:00_TW",
                latest.getProvider(),
                latest.getSourceUrl(),
                applicabilityForCode(code, market),
                null,
                CandidateMarketFeature.AVAILABLE,
                null);
    }

    private static boolean completeInstitutional(TwseInstitutionalDaily row, LocalDate target) {
        return row != null
                && CandidateMarketFeature.AVAILABLE.equals(row.getStatus())
                && row.getTradingDate() != null
                && !row.getTradingDate().isAfter(target)
                && row.getObservedAt() != null
                && row.getForeignNet() != null
                && row.getTrustNet() != null
                && row.getDealerNet() != null
                && row.getTotalNet() != null
                && row.getProvider() != null
                && row.getSourceUrl() != null;
    }

    private static Instant institutionalAvailableAt(TwseInstitutionalDaily row) {
        Instant availableAt = boundary(row.getTradingDate(), "台股");
        if (row.getObservedAt() != null && row.getObservedAt().isAfter(availableAt)) {
            availableAt = row.getObservedAt();
        }
        if (row.getSourceAvailableAt() != null && row.getSourceAvailableAt().isAfter(availableAt)) {
            availableAt = row.getSourceAvailableAt();
        }
        return availableAt;
    }

    private static Instant boundary(LocalDate date, String market) {
        LocalTime time = "美股".equals(market) ? LocalTime.of(18, 0) : LocalTime.of(16, 0);
        return date.atTime(time).atZone(MarketZones.resolve(market)).toInstant();
    }

    /** Legacy commodity rows have no timestamp; t307.7 prescribes next ET midnight. */
    private static Instant commodityBoundary(LocalDate date) {
        return date.plusDays(1).atStartOfDay(MarketZones.resolve("美股")).toInstant();
    }

    private static Instant commodityAvailableAt(CommodityPriceHistory row) {
        return row.getSourceAvailableAt() == null
                ? commodityBoundary(row.getPriceDate())
                : row.getSourceAvailableAt();
    }

    private static BigDecimal median(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) return null;
        List<BigDecimal> sorted = new ArrayList<>(values);
        sorted.removeIf(Objects::isNull);
        sorted.sort(Comparator.naturalOrder());
        if (sorted.isEmpty()) return null;
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 12,
                RoundingMode.HALF_UP);
    }

    private static boolean finite(BigDecimal value) {
        if (value == null) return false;
        double d = value.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static double clampUnit(double value) {
        return Math.max(CONTRIBUTION_MIN, Math.min(CONTRIBUTION_MAX, value));
    }
}
