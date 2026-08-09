package com.steven.assets.service;

import com.steven.assets.util.MarketZones;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pure per-instrument Treasury-yield beta resolver for t275.8.
 *
 * <p>Inputs are immutable, already lag-aligned observations.  Every numeric
 * component must carry an observed timestamp, availability basis and provider;
 * date-only history is deliberately rejected because it cannot prove what a
 * historical decision knew.  The resolver never substitutes a missing beta
 * with zero.</p>
 */
public final class BondYieldBetaResolver {

    public enum Status { AVAILABLE, DISCLOSURE_ONLY, MISSING, NOT_APPLICABLE }

    public enum FxControl { NONE, REQUIRED }

    public record Query(
            String code,
            String market,
            String tenor,
            Instant decisionInstant,
            FxControl fxControl,
            int lagSessions,
            int minimumSamples,
            int rollingWindowSessions,
            int minimumStableWindows,
            RateSignalSpec signalSpec) {

        /** Production structure: one-session lag and three disjoint 250-session windows. */
        public Query(
                String code,
                String market,
                String tenor,
                Instant decisionInstant,
                FxControl fxControl) {
            this(code, market, tenor, decisionInstant, fxControl, 1, 250, 250, 3,
                    RateSignalSpec.primaryOnly(tenor));
        }

        /** Compatibility shape used by existing regression callers. */
        public Query(
                String code,
                String market,
                String tenor,
                Instant decisionInstant,
                FxControl fxControl,
                int lagSessions,
                int minimumSamples,
                int rollingWindowSessions,
                int minimumStableWindows) {
            this(code, market, tenor, decisionInstant, fxControl, lagSessions, minimumSamples,
                    rollingWindowSessions, minimumStableWindows, RateSignalSpec.primaryOnly(tenor));
        }
    }

    /** Candidate-selected primary tenor plus optional adjacent curve-shape calibration. */
    public record RateSignalSpec(
            String primaryTenor,
            String shapeShortTenor,
            String shapeLongTenor,
            BigDecimal curveShapeWeight,
            BigDecimal returnPctAtUnit) {

        public static RateSignalSpec primaryOnly(String tenor) {
            return new RateSignalSpec(tenor, null, null, BigDecimal.ZERO, BigDecimal.ONE);
        }

        public BigDecimal effectiveShock(BigDecimal primaryShockPp, BigDecimal shapeShockPp) {
            if (primaryShockPp == null) return null;
            if (curveShapeWeight == null || curveShapeWeight.signum() == 0) return primaryShockPp;
            if (shapeShockPp == null) return null;
            return primaryShockPp.add(curveShapeWeight.multiply(shapeShockPp));
        }
    }

    /** One numeric observation with an explicit known-at boundary. */
    public record NumericObservation(
            BigDecimal value,
            Instant observedAt,
            Instant sourceAvailableAt,
            String availabilityBasis,
            String provider) {

        public Instant knownAt() {
            return max(observedAt, sourceAvailableAt);
        }

        public boolean structurallyComplete() {
            return finite(value) && observedAt != null
                    && nonBlank(availabilityBasis) && nonBlank(provider);
        }

        public boolean availableAsOf(Instant decisionInstant) {
            Instant known = knownAt();
            return structurallyComplete() && decisionInstant != null
                    && known != null && !known.isAfter(decisionInstant);
        }
    }

    /**
     * {@code adjustedReturnPct} is percentage return; {@code yieldChangePp} is
     * percentage-point yield change.  Therefore beta is return percent per 1pp.
     */
    public record Sample(
            LocalDate returnDate,
            LocalDate yieldChangeDate,
            int lagSessionsApplied,
            NumericObservation adjustedReturnPct,
            NumericObservation yieldChangePp,
            NumericObservation fxChangePct) {

        public Instant knownAt(FxControl fxControl) {
            Instant known = max(
                    adjustedReturnPct == null ? null : adjustedReturnPct.knownAt(),
                    yieldChangePp == null ? null : yieldChangePp.knownAt());
            return fxControl == FxControl.REQUIRED
                    ? max(known, fxChangePct == null ? null : fxChangePct.knownAt()) : known;
        }
    }

    /** Latest complete curve move known at the decision instant. */
    public record RateSignal(
            LocalDate curveDate,
            NumericObservation primaryShockPp,
            NumericObservation curveShapeShockPp) {

        public Instant knownAt() {
            return max(primaryShockPp == null ? null : primaryShockPp.knownAt(),
                    curveShapeShockPp == null ? null : curveShapeShockPp.knownAt());
        }
    }

    public record RollingBeta(
            LocalDate fromDate,
            LocalDate toDate,
            int n,
            BigDecimal univariateBeta,
            BigDecimal fxControlledBeta) {}

    public record Stability(
            int nonOverlappingWindows,
            boolean allNegative,
            BigDecimal absoluteMaxMinRatio,
            boolean passed) {}

    public record Result(
            Status status,
            String code,
            String market,
            String tenor,
            FxControl fxControl,
            BigDecimal univariateBeta,
            BigDecimal fxControlledBeta,
            int n,
            List<RollingBeta> rollingBetas,
            Stability stability,
            LocalDate asOfDate,
            Instant knownAt,
            Set<String> providers,
            String missingReason,
            RateSignalSpec signalSpec,
            LocalDate rateSignalDate,
            BigDecimal primaryShockPp,
            BigDecimal curveShapeShockPp,
            BigDecimal effectiveShockPp,
            Instant rateSignalKnownAt) {

        public Result {
            rollingBetas = rollingBetas == null ? List.of() : List.copyOf(rollingBetas);
            providers = providers == null ? Set.of() : Set.copyOf(providers);
        }

        public BigDecimal effectiveBeta() {
            return fxControl == FxControl.REQUIRED ? fxControlledBeta : univariateBeta;
        }

        /** Compatibility constructor for disclosure-only fitted-beta callers. */
        public Result(
                Status status,
                String code,
                String market,
                String tenor,
                FxControl fxControl,
                BigDecimal univariateBeta,
                BigDecimal fxControlledBeta,
                int n,
                List<RollingBeta> rollingBetas,
                Stability stability,
                LocalDate asOfDate,
                Instant knownAt,
                Set<String> providers,
                String missingReason) {
            this(status, code, market, tenor, fxControl, univariateBeta, fxControlledBeta, n,
                    rollingBetas, stability, asOfDate, knownAt, providers, missingReason,
                    RateSignalSpec.primaryOnly(tenor), null, null, null, null, null);
        }

        public static Result missing(Query query, String reason) {
            return new Result(Status.MISSING,
                    query == null ? null : query.code(), query == null ? null : query.market(),
                    query == null ? null : query.tenor(), query == null ? null : query.fxControl(),
                    null, null, 0, List.of(), new Stability(0, false, null, false),
                    null, null, Set.of(), reason,
                    query == null ? null : query.signalSpec(), null, null, null, null, null);
        }

        public static Result notApplicable(Query query) {
            return new Result(Status.NOT_APPLICABLE,
                    query == null ? null : query.code(), query == null ? null : query.market(),
                    query == null ? null : query.tenor(), query == null ? null : query.fxControl(),
                    null, null, 0, List.of(), new Stability(0, false, null, false),
                    null, null, Set.of(), null,
                    query == null ? null : query.signalSpec(), null, null, null, null, null);
        }
    }

    private static final int SCALE = 8;
    private static final double EPSILON = 1e-14;
    private static final Set<String> TENORS = Set.of("M3", "Y5", "Y10", "Y30");

    private BondYieldBetaResolver() {}

    /**
     * Convert only a stable, available beta into the bounded candidate factor.
     * A negative yield beta (bond return rises as yield falls) is a positive
     * defensive contribution; disclosure-only and missing results stay null.
     */
    public static Double normalizedContribution(Result result) {
        return BondYieldBetaContribution.from(result).shortTerm();
    }

    public static Result resolve(Query query, List<Sample> rawSamples) {
        return resolve(query, rawSamples, null);
    }

    public static Result resolve(Query query, List<Sample> rawSamples, RateSignal rateSignal) {
        String invalid = validate(query);
        if (invalid != null) return Result.missing(query, invalid);
        if (rawSamples == null || rawSamples.isEmpty()) {
            return Result.missing(query, "沒有標的／Treasury 對齊 observation");
        }

        LocalDate decisionDate = query.decisionInstant()
                .atZone(MarketZones.resolve(query.market())).toLocalDate();
        List<Sample> candidates = rawSamples.stream()
                .filter(Objects::nonNull)
                .filter(sample -> sample.returnDate() == null
                        || !sample.returnDate().isAfter(decisionDate))
                .toList();
        if (candidates.stream().anyMatch(sample -> !structurallyComplete(sample, query))) {
            return Result.missing(query,
                    "decision scope 內 observation 缺 observed-at／basis／provider 或 lag 對齊不合法");
        }

        // Append-only stores may contain later revisions.  Select the latest
        // observation known by the decision instant for each return session.
        Map<LocalDate, Sample> asOfByDate = candidates.stream()
                .filter(sample -> sample.knownAt(query.fxControl()) != null
                        && !sample.knownAt(query.fxControl()).isAfter(query.decisionInstant()))
                .collect(Collectors.toMap(Sample::returnDate, Function.identity(),
                        (left, right) -> left.knownAt(query.fxControl())
                                .isAfter(right.knownAt(query.fxControl())) ? left : right));
        List<Sample> samples = asOfByDate.values().stream()
                .sorted(Comparator.comparing(Sample::returnDate)).toList();

        int structuralMinimum = Math.max(query.minimumSamples(),
                query.rollingWindowSessions() * query.minimumStableWindows());
        if (samples.size() < structuralMinimum) {
            return Result.missing(query, "有效 as-of 樣本不足：n=" + samples.size()
                    + "，至少需要 " + structuralMinimum);
        }

        Regression overall = regression(samples, query.fxControl());
        if (overall == null || overall.univariateBeta() == null
                || (query.fxControl() == FxControl.REQUIRED && overall.controlledBeta() == null)) {
            return Result.missing(query, "yield／FX 序列無足夠變異，beta 不可識別");
        }

        List<RollingBeta> windows = rolling(samples, query);
        Stability stability = stability(windows, query.fxControl(), query.minimumStableWindows());
        ResolvedRateSignal currentSignal = resolveRateSignal(query, rateSignal);
        Status status = stability.passed() && currentSignal != null
                ? Status.AVAILABLE : Status.DISCLOSURE_ONLY;
        Sample latest = samples.getLast();
        LinkedHashSet<String> providers = new LinkedHashSet<>();
        for (Sample sample : samples) {
            providers.add(sample.adjustedReturnPct().provider());
            providers.add(sample.yieldChangePp().provider());
            if (query.fxControl() == FxControl.REQUIRED) {
                providers.add(sample.fxChangePct().provider());
            }
        }
        String reason = !stability.passed()
                ? "rolling beta 未通過至少三個互不重疊視窗同為負且 |max/min|<=3"
                : currentSignal == null
                ? "decision-time tenor shock／curve shape 不可得或晚於 decision instant"
                : null;
        Instant resultKnownAt = max(latest.knownAt(query.fxControl()),
                currentSignal == null ? null : currentSignal.knownAt());
        return new Result(status, query.code(), query.market(), query.tenor(), query.fxControl(),
                decimal(overall.univariateBeta()), decimal(overall.controlledBeta()), samples.size(),
                windows, stability, latest.returnDate(), resultKnownAt, providers,
                reason, query.signalSpec(),
                currentSignal == null ? null : currentSignal.date(),
                currentSignal == null ? null : currentSignal.primaryShockPp(),
                currentSignal == null ? null : currentSignal.curveShapeShockPp(),
                currentSignal == null ? null : currentSignal.effectiveShockPp(),
                currentSignal == null ? null : currentSignal.knownAt());
    }

    private static ResolvedRateSignal resolveRateSignal(Query query, RateSignal signal) {
        if (query == null || signal == null || signal.curveDate() == null
                || signal.primaryShockPp() == null
                || !signal.primaryShockPp().availableAsOf(query.decisionInstant())) return null;
        RateSignalSpec spec = query.signalSpec();
        if (spec == null) return null;
        BigDecimal shape = null;
        if (spec.curveShapeWeight().signum() != 0) {
            if (signal.curveShapeShockPp() == null
                    || !signal.curveShapeShockPp().availableAsOf(query.decisionInstant())) return null;
            shape = signal.curveShapeShockPp().value();
        }
        BigDecimal effective = spec.effectiveShock(signal.primaryShockPp().value(), shape);
        if (!finite(effective)) return null;
        return new ResolvedRateSignal(signal.curveDate(), signal.primaryShockPp().value(), shape,
                effective, signal.knownAt());
    }

    private static List<RollingBeta> rolling(List<Sample> samples, Query query) {
        int size = query.rollingWindowSessions();
        int completeWindows = samples.size() / size;
        List<RollingBeta> out = new ArrayList<>(completeWindows);
        int first = samples.size() - completeWindows * size;
        for (int start = first; start + size <= samples.size(); start += size) {
            List<Sample> window = samples.subList(start, start + size);
            Regression regression = regression(window, query.fxControl());
            out.add(new RollingBeta(window.getFirst().returnDate(), window.getLast().returnDate(),
                    window.size(), regression == null ? null : decimal(regression.univariateBeta()),
                    regression == null ? null : decimal(regression.controlledBeta())));
        }
        return List.copyOf(out);
    }

    private static Stability stability(
            List<RollingBeta> windows, FxControl fxControl, int minimumWindows) {
        List<BigDecimal> betas = windows.stream()
                .map(window -> fxControl == FxControl.REQUIRED
                        ? window.fxControlledBeta() : window.univariateBeta())
                .toList();
        boolean enough = betas.size() >= minimumWindows;
        boolean allNegative = enough && betas.stream()
                .allMatch(beta -> beta != null && beta.signum() < 0);
        if (!allNegative) return new Stability(betas.size(), false, null, false);
        BigDecimal min = betas.stream().map(BigDecimal::abs).min(Comparator.naturalOrder()).orElse(null);
        BigDecimal max = betas.stream().map(BigDecimal::abs).max(Comparator.naturalOrder()).orElse(null);
        if (min == null || min.signum() == 0 || max == null) {
            return new Stability(betas.size(), true, null, false);
        }
        BigDecimal ratio = max.divide(min, SCALE, RoundingMode.HALF_UP);
        return new Stability(betas.size(), true, ratio,
                ratio.compareTo(BigDecimal.valueOf(3)) <= 0);
    }

    private static Regression regression(List<Sample> samples, FxControl fxControl) {
        int n = samples.size();
        double meanX = samples.stream().mapToDouble(s -> s.yieldChangePp().value().doubleValue())
                .average().orElse(Double.NaN);
        double meanY = samples.stream().mapToDouble(s -> s.adjustedReturnPct().value().doubleValue())
                .average().orElse(Double.NaN);
        double meanZ = fxControl == FxControl.REQUIRED
                ? samples.stream().mapToDouble(s -> s.fxChangePct().value().doubleValue())
                .average().orElse(Double.NaN) : 0;
        double xx = 0, xy = 0, zz = 0, xz = 0, zy = 0;
        for (Sample sample : samples) {
            double x = sample.yieldChangePp().value().doubleValue() - meanX;
            double y = sample.adjustedReturnPct().value().doubleValue() - meanY;
            xx += x * x;
            xy += x * y;
            if (fxControl == FxControl.REQUIRED) {
                double z = sample.fxChangePct().value().doubleValue() - meanZ;
                zz += z * z;
                xz += x * z;
                zy += z * y;
            }
        }
        if (n < 2 || !Double.isFinite(xx) || xx <= EPSILON) return null;
        double univariate = xy / xx;
        if (!Double.isFinite(univariate)) return null;
        if (fxControl != FxControl.REQUIRED) return new Regression(univariate, null);
        double denominator = xx * zz - xz * xz;
        if (!Double.isFinite(denominator) || Math.abs(denominator) <= EPSILON) {
            return new Regression(univariate, null);
        }
        double controlled = (xy * zz - zy * xz) / denominator;
        return Double.isFinite(controlled) ? new Regression(univariate, controlled)
                : new Regression(univariate, null);
    }

    private static boolean structurallyComplete(Sample sample, Query query) {
        if (sample.returnDate() == null || sample.yieldChangeDate() == null
                || !sample.returnDate().isAfter(sample.yieldChangeDate())
                || sample.lagSessionsApplied() != query.lagSessions()
                || sample.adjustedReturnPct() == null
                || !sample.adjustedReturnPct().structurallyComplete()
                || sample.yieldChangePp() == null
                || !sample.yieldChangePp().structurallyComplete()) {
            return false;
        }
        return query.fxControl() != FxControl.REQUIRED
                || (sample.fxChangePct() != null && sample.fxChangePct().structurallyComplete());
    }

    private static String validate(Query query) {
        if (query == null) return "beta query 缺漏";
        if (!nonBlank(query.code()) || !Set.of("台股", "美股").contains(query.market())
                || !TENORS.contains(query.tenor()) || query.decisionInstant() == null
                || query.fxControl() == null) {
            return "標的／市場／tenor／decision instant／FX mode 不完整";
        }
        if (query.lagSessions() < 1 || query.minimumSamples() < 2
                || query.rollingWindowSessions() < 2 || query.minimumStableWindows() < 3) {
            return "lag／sample／rolling window 結構不合法";
        }
        RateSignalSpec spec = query.signalSpec();
        if (spec == null || !query.tenor().equals(spec.primaryTenor())
                || !TENORS.contains(spec.primaryTenor())
                || spec.curveShapeWeight() == null
                || spec.curveShapeWeight().compareTo(BigDecimal.valueOf(-1)) < 0
                || spec.curveShapeWeight().compareTo(BigDecimal.ONE) > 0
                || spec.returnPctAtUnit() == null || spec.returnPctAtUnit().signum() <= 0) {
            return "rate signal spec 不合法";
        }
        boolean shapePairMissing = spec.shapeShortTenor() == null || spec.shapeLongTenor() == null;
        if (spec.curveShapeWeight().signum() != 0 && (shapePairMissing
                || !TENORS.contains(spec.shapeShortTenor())
                || !TENORS.contains(spec.shapeLongTenor())
                || spec.shapeShortTenor().equals(spec.shapeLongTenor()))) {
            return "curve shape tenor pair 不合法";
        }
        return null;
    }

    private static BigDecimal decimal(Double value) {
        return value == null || !Double.isFinite(value) ? null
                : BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static boolean finite(BigDecimal value) {
        if (value == null) return false;
        double number = value.doubleValue();
        return Double.isFinite(number);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant max(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private record Regression(Double univariateBeta, Double controlledBeta) {}

    private record ResolvedRateSignal(
            LocalDate date,
            BigDecimal primaryShockPp,
            BigDecimal curveShapeShockPp,
            BigDecimal effectiveShockPp,
            Instant knownAt) {}
}
