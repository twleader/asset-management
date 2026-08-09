package com.steven.assets.service;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.util.MarketZones;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure evidence-quality resolver for Trading Radar V13.
 *
 * <p>The rule engine owns opportunity direction.  This class only measures whether
 * the observations needed to support that direction are present, fresh and
 * independently sourced.  Missing observations remain missing; they are never
 * replaced with a neutral zero or hidden by optional-score re-weighting.</p>
 */
public final class TradingRadarEvidenceConfidenceResolver {

    public enum Group {
        PRICE_TECHNICAL, MARKET_LIQUIDITY, VALUATION, FINANCIAL_OPERATING,
        ASSET_SPECIFIC, PUBLIC_EVENT
    }

    public enum Applicability {
        AVAILABLE, MISSING, STALE, NOT_APPLICABLE
    }

    public enum Horizon { SHORT, MEDIUM }

    /**
     * Treasury observation selected as-of the decision instant.
     *
     * <p>{@code riskUnit} remains nullable until a stock-specific, stable negative
     * rate beta is promoted.  A complete curve context is still retained here so
     * callers can disclose provenance without turning a raw yield level into a
     * made-up downside score.</p>
     */
    public record RateObservation(
            TreasuryYieldDto.RateContext context,
            BigDecimal riskUnit,
            String missingReason) {

        public static RateObservation notApplicable() {
            return new RateObservation(null, null, null);
        }

        public static RateObservation missing(String reason) {
            return new RateObservation(null, null, reason);
        }

        public static RateObservation contextOnly(
                TreasuryYieldDto.RateContext context, String unpromotedReason) {
            return new RateObservation(context, null, unpromotedReason);
        }

        public boolean hasCompleteContext() {
            return context != null && context.complete() && context.value() != null
                    && context.curveDate() != null && context.provider() != null
                    && context.staleReason() == null;
        }
    }

    /** Context already assembled by the market resolver; no repository access here. */
    public record MarketContext(
            LocalDate asOfDate,
            BigDecimal volumeRatio,
            BigDecimal turnoverRatio,
            String provider,
            /**
             * Whether this market/index has a meaningful liquidity observation.
             * This is a capability decision supplied by the market adapter; it
             * must never be inferred from a nullable ratio.  A meaningful index
             * with missing volume therefore remains MISSING in the evidence
             * denominator, while a genuinely non-traded/non-meaningful index can
             * explicitly opt out with {@code false}.
             */
            boolean liquidityApplicable) {
        /** Compatibility shape; known market contexts are liquidity-applicable. */
        public MarketContext(LocalDate asOfDate, BigDecimal volumeRatio,
                             BigDecimal turnoverRatio, String provider) {
            this(asOfDate, volumeRatio, turnoverRatio, provider, true);
        }

        /** Missing context is still applicable for a known market, hence MISSING not N/A. */
        public static final MarketContext EMPTY = new MarketContext(null, null, null, null, true);

        /** Explicit opt-out for an index with no meaningful liquidity concept. */
        public static final MarketContext NOT_APPLICABLE =
                new MarketContext(null, null, null, null, false);
    }

    public record Inputs(
            String market,
            Instant decisionInstant,
            RadarObservationResolver.AcceptedPrice acceptedPrice,
            RadarInputAssembler.Assembled technical,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale,
            MarketContext marketContext,
            TradingRadarDto.FundamentalSnapshot fundamental,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            BigDecimal premiumPct,
            LocalDate premiumAsOfDate,
            String premiumSource,
            boolean premiumStale,
            BigDecimal fxPercentile,
            LocalDate fxAsOfDate,
            RateObservation rateObservation,
            TradingRadarRuleEngine.TimingState timingState,
            DividendEventEvidenceResolver.Resolution dividendEvent,
            TradingRadarMarketFeatureResolver.Evidence marketFeatures) {

        public Inputs {
            marketContext = marketContext == null ? MarketContext.EMPTY : marketContext;
            technical = technical == null ? RadarInputAssembler.Assembled.EMPTY : technical;
            timingState = timingState == null
                    ? TradingRadarRuleEngine.TimingState.NEUTRAL : timingState;
            rateObservation = rateObservation == null
                    ? RateObservation.notApplicable() : rateObservation;
            dividendEvent = dividendEvent == null
                    ? DividendEventEvidenceResolver.Resolution.MISSING : dividendEvent;
            marketFeatures = marketFeatures == null
                    ? TradingRadarMarketFeatureResolver.Evidence.empty(market, decisionInstant,
                    "market feature resolver 未建立") : marketFeatures;
        }

        /** Compatibility shape before dividend event evidence became explicit. */
        public Inputs(
                String market,
                Instant decisionInstant,
                RadarObservationResolver.AcceptedPrice acceptedPrice,
                RadarInputAssembler.Assembled technical,
                TradingRadarRuleEngine.MarketRegime marketRegime,
                boolean marketStale,
                MarketContext marketContext,
                TradingRadarDto.FundamentalSnapshot fundamental,
                TradingRadarAssetProfileResolver.AssetProfile profile,
                BigDecimal premiumPct,
                LocalDate premiumAsOfDate,
                String premiumSource,
                boolean premiumStale,
                BigDecimal fxPercentile,
                LocalDate fxAsOfDate,
                RateObservation rateObservation,
                TradingRadarRuleEngine.TimingState timingState,
                DividendEventEvidenceResolver.Resolution dividendEvent) {
            this(market, decisionInstant, acceptedPrice, technical, marketRegime, marketStale,
                    marketContext, fundamental, profile, premiumPct, premiumAsOfDate, premiumSource,
                    premiumStale, fxPercentile, fxAsOfDate, rateObservation, timingState,
                    dividendEvent, TradingRadarMarketFeatureResolver.Evidence.empty(
                            market, decisionInstant, "market feature resolver 未建立"));
        }

        /** Compatibility shape before dividend and market candidate evidence became explicit. */
        public Inputs(
                String market,
                Instant decisionInstant,
                RadarObservationResolver.AcceptedPrice acceptedPrice,
                RadarInputAssembler.Assembled technical,
                TradingRadarRuleEngine.MarketRegime marketRegime,
                boolean marketStale,
                MarketContext marketContext,
                TradingRadarDto.FundamentalSnapshot fundamental,
                TradingRadarAssetProfileResolver.AssetProfile profile,
                BigDecimal premiumPct,
                LocalDate premiumAsOfDate,
                String premiumSource,
                boolean premiumStale,
                BigDecimal fxPercentile,
                LocalDate fxAsOfDate,
                RateObservation rateObservation,
                TradingRadarRuleEngine.TimingState timingState) {
            this(market, decisionInstant, acceptedPrice, technical, marketRegime, marketStale,
                    marketContext, fundamental, profile, premiumPct, premiumAsOfDate, premiumSource,
                    premiumStale, fxPercentile, fxAsOfDate, rateObservation, timingState,
                    DividendEventEvidenceResolver.Resolution.MISSING,
                    TradingRadarMarketFeatureResolver.Evidence.empty(
                            market, decisionInstant, "market feature resolver 未建立"));
        }

        /** Small pure-input constructor useful to callers that have no optional context. */
        public Inputs(
                String market,
                Instant decisionInstant,
                RadarObservationResolver.AcceptedPrice acceptedPrice,
                RadarInputAssembler.Assembled technical,
                TradingRadarRuleEngine.MarketRegime marketRegime,
                boolean marketStale,
                TradingRadarDto.FundamentalSnapshot fundamental,
                TradingRadarAssetProfileResolver.AssetProfile profile) {
            this(market, decisionInstant, acceptedPrice, technical, marketRegime, marketStale,
                    MarketContext.EMPTY, fundamental, profile, null, null, null, false,
                    null, null, RateObservation.notApplicable(), TradingRadarRuleEngine.TimingState.NEUTRAL,
                    DividendEventEvidenceResolver.Resolution.MISSING,
                    TradingRadarMarketFeatureResolver.Evidence.empty(
                            market, decisionInstant, "market feature resolver 未建立"));
        }

        public BigDecimal rateRiskUnit() {
            return rateObservation == null ? null : rateObservation.riskUnit();
        }

        public DividendEventEvidenceResolver.Resolution dividendEvent() {
            return dividendEvent;
        }

        public TradingRadarMarketFeatureResolver.Evidence marketFeatures() {
            return marketFeatures;
        }
    }

    public record Component(
            String name,
            Applicability applicability,
            double weight,
            double availableWeight,
            LocalDate asOfDate,
            String provider,
            String missingReason) {
        public Component(String name, Applicability applicability, double weight,
                         LocalDate asOfDate, String provider, String missingReason) {
            this(name, applicability, weight,
                    applicability == Applicability.AVAILABLE ? weight : 0,
                    asOfDate, provider, missingReason);
        }
        public boolean available() { return applicability == Applicability.AVAILABLE; }
        public boolean fresh() { return applicability == Applicability.AVAILABLE; }
    }

    public record GroupEvidence(
            Group group,
            List<Component> components,
            double shortCoverage,
            double mediumCoverage,
            boolean shortAvailable,
            boolean mediumAvailable,
            boolean shortFresh,
            boolean mediumFresh,
            int sourceCount,
            boolean downsideRisk,
            boolean participates) {
        public GroupEvidence {
            components = components == null ? List.of() : List.copyOf(components);
            shortCoverage = clamp01(shortCoverage);
            mediumCoverage = clamp01(mediumCoverage);
        }

        public double coverage(Horizon horizon) {
            return horizon == Horizon.SHORT ? shortCoverage : mediumCoverage;
        }

        public boolean available(Horizon horizon) {
            return horizon == Horizon.SHORT ? shortAvailable : mediumAvailable;
        }

        public boolean fresh(Horizon horizon) {
            return horizon == Horizon.SHORT ? shortFresh : mediumFresh;
        }

        public boolean meets(Horizon horizon, double threshold) {
            return !participates || (available(horizon) && fresh(horizon)
                    && coverage(horizon) >= threshold);
        }
    }

    public record RiskComponent(
            String name,
            Applicability applicability,
            double weight,
            Double unit,
            String missingReason) {}

    public record Risk(
            Integer downsideRisk,
            double riskCoverage,
            List<RiskComponent> components) {
        public Risk {
            components = components == null ? List.of() : List.copyOf(components);
            riskCoverage = clamp01(riskCoverage);
        }
    }

    public record Evidence(
            Map<Group, GroupEvidence> groups,
            int shortConfidence,
            int mediumConfidence,
            Risk shortRisk,
            Risk mediumRisk,
            List<String> reasons,
            DividendEventEvidenceResolver.Resolution dividendEvent,
            TradingRadarMarketFeatureResolver.Evidence marketFeatures) {
        public Evidence {
            groups = groups == null ? Map.of() : Map.copyOf(groups);
            shortConfidence = clampInt(shortConfidence, 0, 100);
            mediumConfidence = clampInt(mediumConfidence, 0, 100);
            shortRisk = shortRisk == null ? new Risk(null, 0, List.of()) : shortRisk;
            mediumRisk = mediumRisk == null ? new Risk(null, 0, List.of()) : mediumRisk;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            dividendEvent = dividendEvent == null
                    ? DividendEventEvidenceResolver.Resolution.MISSING : dividendEvent;
            marketFeatures = marketFeatures == null
                    ? TradingRadarMarketFeatureResolver.Evidence.empty(null, null,
                    "market feature resolver 未建立") : marketFeatures;
        }

        /** Compatibility shape before dividend event evidence became explicit. */
        public Evidence(
                Map<Group, GroupEvidence> groups,
                int shortConfidence,
                int mediumConfidence,
                Risk shortRisk,
                Risk mediumRisk,
                List<String> reasons) {
            this(groups, shortConfidence, mediumConfidence, shortRisk, mediumRisk, reasons,
                    DividendEventEvidenceResolver.Resolution.MISSING,
                    TradingRadarMarketFeatureResolver.Evidence.empty(null, null,
                            "market feature resolver 未建立"));
        }

        public static final Evidence EMPTY = new Evidence(Map.of(), 0, 0,
                new Risk(null, 0, List.of()), new Risk(null, 0, List.of()), List.of());

        public GroupEvidence group(Group group) { return groups.get(group); }

        public boolean gateOpen(Horizon horizon) {
            return gateOpen(horizon, .70);
        }

        /** Candidate-specific confidence threshold; structural group coverage remains 70%. */
        public boolean gateOpen(Horizon horizon, double confidenceThreshold) {
            int confidence = horizon == Horizon.SHORT ? shortConfidence : mediumConfidence;
            GroupEvidence price = group(Group.PRICE_TECHNICAL);
            GroupEvidence market = group(Group.MARKET_LIQUIDITY);
            int threshold = (int) Math.ceil(Math.max(0.0, Math.min(1.0, confidenceThreshold)) * 100.0);
            return confidence >= threshold && price != null && market != null
                    && price.meets(horizon, .70) && market.meets(horizon, .70);
        }

        public double riskCoverage(Horizon horizon) {
            return (horizon == Horizon.SHORT ? shortRisk : mediumRisk).riskCoverage();
        }

        public Integer downsideRisk(Horizon horizon) {
            return (horizon == Horizon.SHORT ? shortRisk : mediumRisk).downsideRisk();
        }

        public Integer shortDownsideRisk() { return shortRisk.downsideRisk(); }

        public Integer mediumDownsideRisk() { return mediumRisk.downsideRisk(); }

        public boolean dividendEventComplete() {
            return dividendEvent != null
                    && (dividendEvent.status() == DividendEventEvidenceResolver.Status.AVAILABLE
                    || dividendEvent.status() == DividendEventEvidenceResolver.Status.EMPTY_COMPLETE);
        }
    }

    private static final double SHORT_PRICE = .50;
    private static final double SHORT_MARKET = .30;
    private static final double SHORT_ASSET = .20;
    private static final double MEDIUM_PRICE = .30;
    private static final double MEDIUM_MARKET = .20;
    private static final double MEDIUM_VALUATION = .20;
    private static final double MEDIUM_FINANCIAL = .20;
    private static final double MEDIUM_ASSET = .10;

    private TradingRadarEvidenceConfidenceResolver() {}

    public static Evidence resolve(Inputs input) {
        // Compatibility/pure-call path: callers that predate the explicit
        // DecisionMarketClock contract retain the bounded accepted-close
        // fallback.  Production and V13 backtest call the overload below with
        // the calendar-selected terminal date (or null to fail closed).
        return resolveInternal(input, null, null, false, false);
    }

    /**
     * Strict evidence path.  {@code expectedCompletedDate} must come from the
     * market calendar/DecisionMarketClock; null means that the terminal session
     * is unknown and mandatory price/market evidence is unavailable.
     */
    public static Evidence resolve(Inputs input, LocalDate expectedCompletedDate) {
        // Compatibility strict overload: older pure callers do not have a separately
        // calendar-resolved market session, so retain the bounded weekday fallback here.
        // Production/backtest call the three-argument overload below and never infer it.
        return resolveInternal(input, expectedCompletedDate, null, true, false);
    }

    /**
     * Strict production path with independently calendar-resolved terminals.  Price/technical
     * evidence is compared with {@code expectedPriceTerminal}; market liquidity is compared with
     * {@code expectedMarketCompletedSession}.  A null market session is unknown—not permission
     * to derive a weekday—so the market-volume component remains MISSING and the gate closes.
     */
    public static Evidence resolve(
            Inputs input,
            LocalDate expectedPriceTerminal,
            LocalDate expectedMarketCompletedSession) {
        return resolveInternal(input, expectedPriceTerminal, expectedMarketCompletedSession,
                true, true);
    }

    private static Evidence resolveInternal(
            Inputs input,
            LocalDate expectedCompletedDate,
            LocalDate expectedMarketCompletedSession,
            boolean strictTerminalDate,
            boolean explicitMarketSession) {
        if (input == null) return Evidence.EMPTY;
        LocalDate targetDate = strictTerminalDate
                ? expectedCompletedDate : terminalTargetDate(input);
        Map<Group, GroupEvidence> groups = new EnumMap<>(Group.class);
        groups.put(Group.PRICE_TECHNICAL, priceGroup(input, targetDate));
        groups.put(Group.MARKET_LIQUIDITY, marketGroup(input, targetDate,
                explicitMarketSession ? expectedMarketCompletedSession
                        : requiredMarketTerminalDate(input)));
        groups.put(Group.VALUATION, valuationGroup(input, targetDate));
        groups.put(Group.FINANCIAL_OPERATING, financialGroup(input, targetDate));
        groups.put(Group.ASSET_SPECIFIC, assetGroup(input, targetDate));
        groups.put(Group.PUBLIC_EVENT, publicEventGroup(input, targetDate));

        int shortConfidence = confidenceFor(groups, Map.of(
                Group.PRICE_TECHNICAL, SHORT_PRICE,
                Group.MARKET_LIQUIDITY, SHORT_MARKET,
                Group.ASSET_SPECIFIC, SHORT_ASSET), Horizon.SHORT);
        int mediumConfidence = confidenceFor(groups, Map.of(
                Group.PRICE_TECHNICAL, MEDIUM_PRICE,
                Group.MARKET_LIQUIDITY, MEDIUM_MARKET,
                Group.VALUATION, MEDIUM_VALUATION,
                Group.FINANCIAL_OPERATING, MEDIUM_FINANCIAL,
                Group.ASSET_SPECIFIC, MEDIUM_ASSET), Horizon.MEDIUM);
        Risk shortRisk = risk(input, targetDate, Horizon.SHORT);
        Risk mediumRisk = risk(input, targetDate, Horizon.MEDIUM);
        List<String> reasons = new ArrayList<>();
        for (Group group : List.of(Group.PRICE_TECHNICAL, Group.MARKET_LIQUIDITY,
                Group.VALUATION, Group.FINANCIAL_OPERATING, Group.ASSET_SPECIFIC)) {
            GroupEvidence evidence = groups.get(group);
            if (evidence != null && evidence.participates()
                    && (!evidence.meets(Horizon.SHORT, .70)
                    || !evidence.meets(Horizon.MEDIUM, .70))) {
                reasons.add(group.name() + " coverage/freshness 未達 70%，不支持買進候選。 ");
            }
        }
        addDividendReason(reasons, input.dividendEvent(), Horizon.SHORT);
        addDividendReason(reasons, input.dividendEvent(), Horizon.MEDIUM);
        return new Evidence(groups, shortConfidence, mediumConfidence, shortRisk, mediumRisk,
                reasons, input.dividendEvent(), input.marketFeatures());
    }

    /** Convenience overload for callers that have only the mandatory observations. */
    public static Evidence resolve(
            RadarObservationResolver.AcceptedPrice acceptedPrice,
            RadarInputAssembler.Assembled technical,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale,
            TradingRadarDto.FundamentalSnapshot fundamental,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String market,
            Instant decisionInstant) {
        return resolve(new Inputs(market, decisionInstant, acceptedPrice, technical,
                marketRegime, marketStale, fundamental, profile));
    }

    private static GroupEvidence priceGroup(Inputs in, LocalDate targetDate) {
        List<Component> shortComponents = new ArrayList<>();
        List<Component> mediumComponents = new ArrayList<>();
        RadarObservationResolver.AcceptedPrice accepted = in.acceptedPrice();
        Applicability priceStatus = accepted != null && accepted.available()
                && sameTerminalDate(accepted.tradingDate(), targetDate)
                ? Applicability.AVAILABLE : Applicability.MISSING;
        shortComponents.add(component("accepted_price", priceStatus, .25, date(accepted),
                accepted == null ? null : accepted.source(),
                priceStatus == Applicability.AVAILABLE ? null : "缺少符合市場日期的 accepted price"));
        mediumComponents.add(component("accepted_price", priceStatus, .20, date(accepted),
                accepted == null ? null : accepted.source(),
                priceStatus == Applicability.AVAILABLE ? null : "缺少符合市場日期的 accepted price"));
        var ind = in.technical().indicators();
        var ext = ind == null ? null : ind.extended();
        LocalDate asOf = accepted == null ? null : accepted.tradingDate();
        shortComponents.add(component("ma5", available(ind == null ? null : ind.weeklyMa()), .15, asOf,
                "TECHNICAL_INDICATORS", "MA5 缺漏"));
        shortComponents.add(component("kd_j_wr", allAvailable(
                ind == null ? null : ind.k(), ind == null ? null : ind.d(),
                ext == null ? null : ext.j9(), ext == null ? null : ext.wr9()), .15, asOf,
                "TECHNICAL_INDICATORS", "KD/J/W%R 任一缺漏"));
        shortComponents.add(component("macd_rsi_bias", allAvailable(
                ext == null ? null : ext.macd(), ext == null ? null : ext.rsi5(),
                ext == null ? null : ext.rsi10(), ext == null ? null : ext.bias10(),
                ext == null ? null : ext.bias20()), .25, asOf,
                "TECHNICAL_INDICATORS", "MACD/RSI/BIAS 任一缺漏"));
        shortComponents.add(component("volume_ratio", available(in.technical().volumeRatio()), .20, asOf,
                "PRICE_HISTORY", "個股量比缺漏"));
        mediumComponents.add(component("ma20_60_240", allAvailable(
                ind == null ? null : ind.monthlyMa(), ind == null ? null : ind.quarterlyMa(),
                ind == null ? null : ind.annualMa()), .35, asOf,
                "TECHNICAL_INDICATORS", "MA20/MA60/MA240 任一缺漏"));
        mediumComponents.add(component("kd_j_wr", allAvailable(
                ind == null ? null : ind.k(), ind == null ? null : ind.d(),
                ext == null ? null : ext.j9(), ext == null ? null : ext.wr9()), .10, asOf,
                "TECHNICAL_INDICATORS", "KD/J/W%R 任一缺漏"));
        mediumComponents.add(component("macd_rsi_bias", allAvailable(
                ext == null ? null : ext.macd(), ext == null ? null : ext.rsi5(),
                ext == null ? null : ext.rsi10(), ext == null ? null : ext.bias10(),
                ext == null ? null : ext.bias20()), .20, asOf,
                "TECHNICAL_INDICATORS", "MACD/RSI/BIAS 任一缺漏"));
        mediumComponents.add(component("volume_ratio", available(in.technical().volumeRatio()), .15, asOf,
                "PRICE_HISTORY", "個股量比缺漏"));
        return horizonGroup(Group.PRICE_TECHNICAL, shortComponents, mediumComponents,
                true, true, true);
    }

    private static GroupEvidence marketGroup(
            Inputs in, LocalDate targetDate, LocalDate expectedMarketCompletedSession) {
        List<Component> components = new ArrayList<>();
        // In the explicit production path a missing calendar-selected terminal session means
        // the market clock itself is unknown.  Do not let a regime label alone keep the 70%
        // mandatory component open (that would silently turn an unknown holiday into a fresh
        // market observation).
        boolean regimeAvailable = expectedMarketCompletedSession != null
                && in.marketRegime() != null
                && in.marketRegime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE
                && !in.marketStale();
        components.add(component("regime", regimeAvailable ? Applicability.AVAILABLE :
                        (in.marketStale() ? Applicability.STALE : Applicability.MISSING),
                .70, targetDate, "MARKET_REGIME", regimeAvailable ? null : "大盤 regime stale／資料不足"));
        MarketContext context = in.marketContext();
        // Applicability is an explicit adapter capability.  Do not use the
        // presence of a nullable ratio as a proxy: IXIC/TAIEX have meaningful
        // liquidity observations even when today's value is unavailable.
        boolean contextApplicable = context != null && context.liquidityApplicable();
        boolean contextValueAvailable = context != null
                && (finite(context.volumeRatio()) || finite(context.turnoverRatio()));
        LocalDate requiredMarketSession = expectedMarketCompletedSession;
        boolean contextExactSession = context != null && context.asOfDate() != null
                && requiredMarketSession != null
                && requiredMarketSession.equals(context.asOfDate());
        Applicability contextStatus = !contextApplicable ? Applicability.NOT_APPLICABLE
                : contextExactSession
                && !in.marketStale() && contextValueAvailable ? Applicability.AVAILABLE
                : (in.marketStale() ? Applicability.STALE : Applicability.MISSING);
        components.add(component("market_volume_turnover", contextStatus, .30,
                context == null ? null : context.asOfDate(), context == null ? null : context.provider(),
                contextStatus == Applicability.AVAILABLE || contextStatus == Applicability.NOT_APPLICABLE
                        ? null : contextValueAvailable ? "大盤量能 context 非要求 completed session"
                        : "大盤量能 context 缺漏（適用指數不得視為 N/A）"));
        boolean mandatory = regimeAvailable;
        return group(Group.MARKET_LIQUIDITY, components, mandatory, true);
    }

    private static GroupEvidence valuationGroup(Inputs in, LocalDate targetDate) {
        TradingRadarDto.FundamentalSnapshot f = in.fundamental();
        TradingRadarAssetProfileResolver.AssetProfile p = in.profile();
        boolean applicable = p != null && p.equity()
                && p.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.STOCK;
        if (!applicable) return notApplicable(Group.VALUATION);
        List<Component> c = new ArrayList<>();
        c.add(valuationComponent("pe", f == null ? null : f.pePercentile(),
                f == null ? null : f.peLossFlag(), f == null ? null : f.peEvidence(), f, targetDate));
        c.add(valuationComponent("pb", f == null ? null : f.pbPercentile(), null,
                f == null ? null : f.pbEvidence(), f, targetDate));
        c.add(valuationComponent("dividend_yield", f == null ? null : f.dividendYieldPercentile(), null,
                f == null ? null : f.dividendYieldEvidence(), f, targetDate));
        return group(Group.VALUATION, c, true, true);
    }

    private static Component valuationComponent(String name, BigDecimal percentile, Boolean loss,
                                                TradingRadarDto.ValuationComponentEvidence evidence,
                                                TradingRadarDto.FundamentalSnapshot f,
                                                LocalDate targetDate) {
        boolean available = loss == Boolean.TRUE || (percentile != null && finite(percentile));
        String asOf = evidence == null ? (f == null ? null : f.valuationAsOf()) : evidence.asOf();
        String provider = evidence == null ? (f == null ? null : f.valuationProvider()) : evidence.provider();
        boolean freshDate = asOf != null && freshDate(parseDate(asOf), targetDate, 10);
        Applicability status = !freshDate ? (asOf == null
                ? Applicability.MISSING : Applicability.STALE)
                : available ? Applicability.AVAILABLE : Applicability.MISSING;
        return component(name, status, 1.0 / 3.0,
                parseDate(asOf), provider,
                status == Applicability.AVAILABLE ? null : "估值欄位／250 筆歷史不足");
    }

    private static GroupEvidence financialGroup(Inputs in, LocalDate targetDate) {
        TradingRadarDto.FundamentalSnapshot f = in.fundamental();
        TradingRadarAssetProfileResolver.AssetProfile p = in.profile();
        boolean applicable = p != null && p.equity()
                && p.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.STOCK;
        if (!applicable) return notApplicable(Group.FINANCIAL_OPERATING);
        boolean tw = "台股".equals(in.market());
        List<Component> c = new ArrayList<>();
        c.add(epsFinancialComponent(f, .35));
        Component roe = financialComponent("roe", f == null ? null : f.approximateRoePct(),
                f == null ? null : f.roeAsOf(), f == null ? null : f.roeProvider(), .35);
        if (f != null && f.roeApproximationFallback() && roe.applicability() == Applicability.AVAILABLE) {
            roe = new Component(roe.name(), roe.applicability(), roe.weight(), roe.weight() * .75,
                    roe.asOfDate(), roe.provider(), "期初權益缺漏，ROE fallback 信心折減 25%");
        }
        c.add(roe);
        if (tw) {
            c.add(financialComponent("revenue", f == null ? null : f.revenueYoy3mPct(),
                    f == null ? null : f.revenueAsOf(), f == null ? null : f.revenueProvider(), .20));
            c.add(financialComponent("industry", f == null ? null : f.industryRevenueYoyPct(),
                    f == null ? null : f.industryAsOf(), f == null ? null : f.industryProvider(), .10));
        } else {
            c.add(component("revenue", Applicability.NOT_APPLICABLE, .20, null, null, null));
            c.add(component("industry", Applicability.NOT_APPLICABLE, .10, null, null, null));
        }
        return group(Group.FINANCIAL_OPERATING, c, true, true);
    }

    private static Component financialComponent(String name, BigDecimal value, String asOf,
                                                String provider, double weight) {
        LocalDate date = parseDate(asOf);
        boolean ok = value != null && finite(value) && date != null;
        Applicability status = ok ? Applicability.AVAILABLE : Applicability.MISSING;
        return component(name, status, weight, date, ok ? provider : null,
                ok ? null : "基本面 observation 缺漏");
    }

    /**
     * EPS may be a valid categorical observation when a percentage cannot be
     * computed (e.g. TURNAROUND, TURNED_LOSS, PERSISTENT_LOSS).  The trend is
     * produced by the same as-of FundamentalAnalysisService snapshot; accepting
     * it here keeps the loss/turnaround contribution and its provenance intact
     * instead of falsely closing the entire FINANCIAL group.
     */
    private static Component epsFinancialComponent(
            TradingRadarDto.FundamentalSnapshot f, double weight) {
        if (f == null) return financialComponent("eps", null, null, null, weight);
        LocalDate date = parseDate(f.epsAsOf());
        boolean numeric = f.epsYoyPct() != null && finite(f.epsYoyPct());
        String trend = f.epsTrendType();
        boolean categorical = validEpsTrendType(trend);
        boolean ok = date != null && (numeric || categorical);
        String reason = !ok ? "基本面 observation 缺漏"
                : numeric ? null
                : "EPS 趨勢 " + trend + " 為有效類別 evidence；年增百分比不可定義，保留轉機／虧損風險語意";
        return component("eps", ok ? Applicability.AVAILABLE : Applicability.MISSING, weight,
                date, ok ? f.epsProvider() : null, reason);
    }

    private static boolean validEpsTrendType(String trend) {
        return switch (trend == null ? "" : trend.trim()) {
            case "POSITIVE_BASE_IMPROVING", "POSITIVE_BASE_DETERIORATING",
                    "TURNAROUND", "TURNED_LOSS", "PERSISTENT_LOSS" -> true;
            default -> false;
        };
    }

    private static GroupEvidence assetGroup(Inputs in, LocalDate targetDate) {
        TradingRadarAssetProfileResolver.AssetProfile p = in.profile();
        if (p == null) return notApplicable(Group.ASSET_SPECIFIC);
        List<Component> c = new ArrayList<>();
        boolean etf = p.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.EQUITY_ETF
                || p.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF;
        if (etf && "台股".equals(in.market())) {
            Applicability status = in.premiumPct() == null ? (in.premiumStale() ? Applicability.STALE : Applicability.MISSING)
                    : in.premiumStale() ? Applicability.STALE
                    : freshDate(in.premiumAsOfDate(), targetDate, 5) ? Applicability.AVAILABLE : Applicability.STALE;
            c.add(component("etf_premium", status, 1, in.premiumAsOfDate(), in.premiumSource(),
                    status == Applicability.AVAILABLE ? null : "ETF 折溢價非決策日／缺漏"));
        }
        boolean fxApplicable = p.underlyingCurrency() != null && p.quoteCurrency() != null
                && !p.underlyingCurrency().equalsIgnoreCase(p.quoteCurrency());
        if (fxApplicable) {
            Applicability status = in.fxPercentile() != null && in.fxAsOfDate() != null
                    && freshDate(in.fxAsOfDate(), targetDate, 5)
                    ? Applicability.AVAILABLE : Applicability.MISSING;
            c.add(component("fx", status, 1, in.fxAsOfDate(), "FX_HISTORY",
                    status == Applicability.AVAILABLE ? null : "底層幣別匯率缺漏"));
        }
        if (p.bond()) {
            RateObservation rate = in.rateObservation();
            boolean completeContext = rate != null && rate.hasCompleteContext();
            // Complete decision-time curve context is evidence availability.
            // Promotion controls only the quantitative downside unit; keeping
            // riskUnit nullable must not relabel the source observation MISSING.
            boolean stale = rate != null && rate.context() != null
                    && rate.context().staleReason() != null;
            Applicability status = completeContext ? Applicability.AVAILABLE
                    : stale ? Applicability.STALE : Applicability.MISSING;
            LocalDate asOf = rate != null && rate.context() != null
                    ? rate.context().curveDate() : null;
            String provider = rate != null && rate.context() != null
                    ? rate.context().provider() : "TREASURY_YIELD";
            String reason = status == Applicability.AVAILABLE ? rate.missingReason()
                    : stale ? rate.context().staleReason()
                    : rate == null || rate.missingReason() == null
                    ? "債券利率風險 observation 缺漏" : rate.missingReason();
            c.add(component("bond_rate", status, 1, asOf, provider, reason));
        }
        if (c.isEmpty()) return notApplicable(Group.ASSET_SPECIFIC);
        double equal = 1.0 / c.size();
        List<Component> normalized = c.stream()
                .map(x -> new Component(x.name(), x.applicability(), equal,
                        x.availableWeight() > 0 ? equal : 0,
                        x.asOfDate(), x.provider(), x.missingReason()))
                .toList();
        return group(Group.ASSET_SPECIFIC, normalized, false, true);
    }

    private static GroupEvidence publicEventGroup(Inputs in, LocalDate targetDate) {
        TradingRadarDto.FundamentalSnapshot f = in.fundamental();
        List<Component> c = new ArrayList<>();
        int company = f == null || f.companyPublicInformation() == null ? 0 : f.companyPublicInformation().size();
        int industry = f == null || f.industryPublicInformation() == null ? 0 : f.industryPublicInformation().size();
        c.add(component("company_public_info", company > 0 ? Applicability.AVAILABLE : Applicability.MISSING,
                .33, null, "PUBLIC_INFO", company > 0 ? null : "無個股公開事件"));
        c.add(component("industry_public_info", industry > 0 ? Applicability.AVAILABLE : Applicability.MISSING,
                .33, null, "PUBLIC_INFO", industry > 0 ? null : "無產業公開事件"));
        DividendEventEvidenceResolver.Resolution dividend = in.dividendEvent();
        Applicability dividendStatus = dividendApplicability(dividend);
        String dividendReason = dividendReason(dividend, null);
        LocalDate asOf = dividend == null || dividend.knownAt() == null ? targetDate
                : dividend.knownAt().atZone(ZoneId.of("UTC")).toLocalDate();
        c.add(component("dividend_event", dividendStatus, .34, asOf,
                dividend == null ? "DIVIDEND_EVIDENCE" : dividend.provider(), dividendReason));
        return group(Group.PUBLIC_EVENT, c, false, false);
    }

    private static Applicability dividendApplicability(
            DividendEventEvidenceResolver.Resolution resolution) {
        if (resolution == null) return Applicability.MISSING;
        return switch (resolution.status()) {
            case AVAILABLE, EMPTY_COMPLETE -> Applicability.AVAILABLE;
            case STALE -> Applicability.STALE;
            case MISSING, PARTIAL -> Applicability.MISSING;
        };
    }

    private static String dividendReason(
            DividendEventEvidenceResolver.Resolution resolution, Horizon horizon) {
        if (resolution == null) return "配息事件 evidence 未建立，禁止猜測無事件";
        int count = horizon == null ? 0 : horizon == Horizon.SHORT
                ? resolution.eventsWithinFiveSessions() : resolution.eventsWithinTwentySessions();
        return switch (resolution.status()) {
            case AVAILABLE -> count > 0
                    ? "決策時點前已知未來 " + count + " 個配息事件（"
                    + (horizon == Horizon.SHORT ? "5" : "20") + " 個交易日內）。" : null;
            case EMPTY_COMPLETE -> "決策時點已查無 scope 內未來配息事件（EMPTY_COMPLETE）。";
            case STALE -> resolution.missingReason() == null
                    ? "配息事件 snapshot 已過期，禁止猜測無事件。" : resolution.missingReason();
            case PARTIAL -> resolution.missingReason() == null
                    ? "配息事件 snapshot 不完整，禁止猜測無事件。" : resolution.missingReason();
            case MISSING -> resolution.missingReason() == null
                    ? "配息事件 snapshot 缺漏，禁止猜測無事件。" : resolution.missingReason();
        };
    }

    private static void addDividendReason(
            List<String> reasons,
            DividendEventEvidenceResolver.Resolution resolution,
            Horizon horizon) {
        String reason = dividendReason(resolution, horizon);
        if (reason != null && !reason.isBlank()) reasons.add(reason);
    }

    private static Risk risk(Inputs in, LocalDate targetDate, Horizon horizon) {
        List<RiskComponent> c = new ArrayList<>();
        double availableWeight = 0;
        double applicableWeight = 0;
        double weightedRisk = 0;
        // timing 25
        double timing = switch (in.timingState()) {
            case EXTREME_OVERBOUGHT -> 1.0;
            case OVERBOUGHT -> .5;
            default -> 0.0;
        };
        c.add(new RiskComponent("timing", Applicability.AVAILABLE, 25, timing, null));
        applicableWeight += 25;
        availableWeight += 25; weightedRisk += 25 * timing;
        // market 20
        applicableWeight += 20;
        Applicability marketStatus = in.marketRegime() == null || in.marketRegime()
                == TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE
                ? Applicability.MISSING : in.marketStale() ? Applicability.STALE : Applicability.AVAILABLE;
        Double marketRisk = in.marketRegime() == null ? null : switch (in.marketRegime()) {
            case RISK_OFF -> 1.0;
            case NEUTRAL -> .25;
            case RISK_ON -> 0.0;
            case DATA_INCOMPLETE -> null;
        };
        c.add(new RiskComponent("market", marketStatus, 20, marketRisk,
                marketStatus == Applicability.AVAILABLE ? null : "大盤資料不足"));
        if (marketRisk != null && marketStatus == Applicability.AVAILABLE) {
            availableWeight += 20; weightedRisk += 20 * marketRisk;
        }
        // move/volatility 20; either available metric is enough, not a zero fill.
        applicableWeight += 20;
        BigDecimal move = in.technical().completedChangePercent();
        BigDecimal sigma = in.technical().returnStdDev60Ratio();
        if (move == null && sigma == null) {
            c.add(new RiskComponent("move_volatility", Applicability.MISSING, 20, null, "波動／完成日漲跌缺漏"));
        } else {
            double moveRisk = move == null ? 0 : clamp01(Math.abs(move.doubleValue()) / 7.0);
            double volRisk = sigma == null ? 0 : clamp01(sigma.doubleValue() / .03);
            double unit = Math.max(moveRisk, volRisk);
            c.add(new RiskComponent("move_volatility", Applicability.AVAILABLE, 20, unit, null));
            availableWeight += 20; weightedRisk += 20 * unit;
        }
        // liquidity 15
        applicableWeight += 15;
        BigDecimal volume = in.technical().volumeRatio();
        if (volume == null) {
            c.add(new RiskComponent("liquidity", Applicability.MISSING, 15, null, "個股量比缺漏"));
        } else {
            double v = volume.doubleValue();
            double unit = v <= .5 ? 1 : v < .8 ? (.8 - v) / .3 : 0;
            c.add(new RiskComponent("liquidity", Applicability.AVAILABLE, 15, clamp01(unit), null));
            availableWeight += 15; weightedRisk += 15 * clamp01(unit);
        }
        // asset 10 is split across independently applicable sub-observations.
        // Missing/stale sub-items never become a zero risk contribution: only
        // the weight of genuinely fresh items enters availableWeight.  This
        // prevents one available ETF premium from silently claiming the full
        // FX/rate risk budget.
        TradingRadarAssetProfileResolver.AssetProfile assetProfile = in.profile();
        // ETF premium is a Taiwan exchange NAV observation.  A US-listed ETF (for
        // example VOO) has no Taiwan premium applicability; marking it missing
        // would incorrectly reduce otherwise complete risk coverage.
        boolean etfApplicable = "台股".equals(in.market()) && assetProfile != null
                && (assetProfile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.EQUITY_ETF
                || assetProfile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF);
        boolean fxApplicable = assetProfile != null && assetProfile.underlyingCurrency() != null
                && assetProfile.quoteCurrency() != null
                && !assetProfile.underlyingCurrency().equalsIgnoreCase(assetProfile.quoteCurrency());
        boolean rateApplicable = assetProfile != null && assetProfile.bond();
        int applicableAssetItems = (etfApplicable ? 1 : 0)
                + (fxApplicable ? 1 : 0)
                + (rateApplicable ? 1 : 0);
        if (applicableAssetItems == 0) {
            c.add(new RiskComponent("asset", Applicability.NOT_APPLICABLE, 0, null,
                    "無適用的 ETF premium／FX／bond rate risk observation"));
        } else {
            double itemWeight = 10.0 / applicableAssetItems;
            applicableWeight += 10;
            double availableAssetWeight = 0;
            double assetRiskMax = 0;
            if (etfApplicable) {
                Applicability status = in.premiumPct() == null
                        ? (in.premiumStale() ? Applicability.STALE : Applicability.MISSING)
                        : in.premiumStale() || !freshDate(in.premiumAsOfDate(), targetDate, 5)
                        ? Applicability.STALE : Applicability.AVAILABLE;
                Double unit = status == Applicability.AVAILABLE
                        ? clamp01(Math.max(in.premiumPct().doubleValue(), 0) / 3.0) : null;
                c.add(new RiskComponent("asset_premium", status, itemWeight, unit,
                        unit == null ? "ETF premium observation 缺漏或過期" : null));
                if (unit != null) {
                    availableWeight += itemWeight;
                    availableAssetWeight += itemWeight;
                    assetRiskMax = Math.max(assetRiskMax, unit);
                }
            }
            if (fxApplicable) {
                Applicability status = in.fxPercentile() == null || in.fxAsOfDate() == null
                        ? Applicability.MISSING
                        : freshDate(in.fxAsOfDate(), targetDate, 5)
                        ? Applicability.AVAILABLE : Applicability.STALE;
                Double unit = status == Applicability.AVAILABLE
                        ? clamp01((in.fxPercentile().doubleValue() - 50) / 40.0) : null;
                c.add(new RiskComponent("asset_fx", status, itemWeight, unit,
                        unit == null ? "FX percentile observation 缺漏或過期" : null));
                if (unit != null) {
                    availableWeight += itemWeight;
                    availableAssetWeight += itemWeight;
                    assetRiskMax = Math.max(assetRiskMax, unit);
                }
            }
            if (rateApplicable) {
                RateObservation rate = in.rateObservation();
                // Freshness is decided once by the Treasury resolver using the
                // authoritative completed-session clock.  Do not apply a second
                // calendar-day cutoff here: a long exchange holiday can be more
                // than five calendar days while still being only one valid
                // completed-session old.  Conversely, a stale/unknown session is
                // carried by staleReason and must remain STALE everywhere.
                boolean fresh = rate != null && rate.hasCompleteContext();
                boolean staleContext = rate != null && rate.context() != null
                        && rate.context().staleReason() != null;
                Applicability status = rate == null || rate.riskUnit() == null
                        ? staleContext ? Applicability.STALE : Applicability.MISSING
                        : fresh ? Applicability.AVAILABLE : Applicability.STALE;
                Double unit = status == Applicability.AVAILABLE
                        ? clamp01(rate.riskUnit().doubleValue()) : null;
                c.add(new RiskComponent("asset_rate", status, itemWeight, unit,
                        unit == null ? staleContext ? rate.context().staleReason()
                                : rate == null || rate.missingReason() == null
                                ? "bond rate risk observation 缺漏或過期"
                                : rate.missingReason() : null));
                if (unit != null) {
                    availableWeight += itemWeight;
                    availableAssetWeight += itemWeight;
                    assetRiskMax = Math.max(assetRiskMax, unit);
                }
            }
            // Asset risk is a max-of-applicable-observations signal, not an
            // average: a fresh extreme FX/rate/premium warning must retain the
            // full applicable asset budget.  Coverage still records only the
            // fresh sub-item weights above.
            weightedRisk += availableAssetWeight * assetRiskMax;
        }
        // Dividend event evidence is horizon-specific risk/disclosure only.  It
        // never changes opportunity score; incomplete evidence is kept missing
        // (not a guessed zero) and is closed by the action gate.
        DividendEventEvidenceResolver.Resolution dividend = in.dividendEvent();
        int eventsWithinFive = dividend == null ? 0 : dividend.eventsWithinFiveSessions();
        int eventsWithinTwenty = dividend == null ? 0 : dividend.eventsWithinTwentySessions();
        Applicability eventStatus = dividendApplicability(dividend);
        // Dividend event scope is applicable to the evaluated security even when
        // the provider returns MISSING/STALE; only an explicit N/A would leave it
        // out of the denominator (the resolver currently has no such status).
        applicableWeight += 10;
        if (eventStatus == Applicability.AVAILABLE) {
            double eventRisk = eventsWithinFive > 0 ? 1.0
                    : eventsWithinTwenty > 0 ? .5 : 0.0;
            c.add(new RiskComponent("dividend_event", Applicability.AVAILABLE, 10, eventRisk,
                    dividendReason(dividend, horizon)));
            availableWeight += 10;
            weightedRisk += 10 * eventRisk;
        } else {
            c.add(new RiskComponent("dividend_event", eventStatus, 10, null,
                    dividendReason(dividend, horizon)));
        }
        Integer score = availableWeight <= 0 ? null : (int) Math.round(100 * weightedRisk / availableWeight);
        double coverage = applicableWeight <= 0 ? 0.0 : availableWeight / applicableWeight;
        return new Risk(score, coverage, c);
    }

    private static GroupEvidence group(Group group, List<Component> components,
                                       boolean mandatory, boolean participates) {
        return horizonGroup(group, components, components, mandatory, mandatory, participates);
    }

    private static GroupEvidence horizonGroup(Group group,
                                              List<Component> shortComponents,
                                              List<Component> mediumComponents,
                                              boolean shortMandatory,
                                              boolean mediumMandatory,
                                              boolean participates) {
        double shortCoverage = weightedCoverage(shortComponents);
        double mediumCoverage = weightedCoverage(mediumComponents);
        boolean shortAvailable = shortComponents.stream().anyMatch(Component::available);
        boolean mediumAvailable = mediumComponents.stream().anyMatch(Component::available);
        boolean shortFresh = shortMandatory && shortCoverage >= .70
                && fresh(shortComponents, mandatoryName(group));
        boolean mediumFresh = mediumMandatory && mediumCoverage >= .70
                && fresh(mediumComponents, mandatoryName(group));
        if (!shortMandatory) shortFresh = shortCoverage >= .70;
        if (!mediumMandatory) mediumFresh = mediumCoverage >= .70;
        List<Component> all = new ArrayList<>(shortComponents);
        for (Component component : mediumComponents) {
            if (all.stream().noneMatch(x -> x.name().equals(component.name()))) all.add(component);
        }
        return new GroupEvidence(group, all, shortCoverage, mediumCoverage,
                shortAvailable, mediumAvailable, shortFresh, mediumFresh,
                sourceCount(all), false, participates);
    }

    private static String mandatoryName(Group group) {
        return switch (group) {
            case PRICE_TECHNICAL -> "accepted_price";
            case MARKET_LIQUIDITY -> "regime";
            default -> "";
        };
    }

    private static GroupEvidence notApplicable(Group group) {
        return new GroupEvidence(group, List.of(), 0, 0, false, false, false, false,
                0, false, false);
    }

    private static int confidenceFor(Map<Group, GroupEvidence> groups, Map<Group, Double> weights,
                                     Horizon horizon) {
        double denominator = 0, numerator = 0;
        for (Map.Entry<Group, Double> e : weights.entrySet()) {
            GroupEvidence g = groups.get(e.getKey());
            if (g == null || !g.participates()) continue;
            denominator += e.getValue();
            numerator += e.getValue() * g.coverage(horizon);
        }
        return denominator <= 0 ? 0 : clampInt((int) Math.round(100 * numerator / denominator), 0, 100);
    }

    private static Component component(String name, Applicability status, double weight,
                                       LocalDate date, String provider, String reason) {
        return new Component(name, status, weight, date, provider, reason);
    }

    private static Component component(String name, boolean available, double weight,
                                       LocalDate date, String provider, String reason) {
        return component(name, available ? Applicability.AVAILABLE : Applicability.MISSING,
                weight, date, provider, reason);
    }

    private static boolean available(Object value) { return value instanceof BigDecimal b && finite(b); }

    private static boolean allAvailable(Object... values) {
        if (values == null || values.length == 0) return false;
        for (Object value : values) if (!available(value)) return false;
        return true;
    }

    private static double weightedCoverage(List<Component> components) {
        // NOT_APPLICABLE is an explicit scope decision, not missing evidence.
        // Excluding it keeps the denominator tied to observations that are
        // actually required for this instrument/market (for example US stocks
        // do not have TW-only revenue/industry fields).
        double denominator = components.stream()
                .filter(component -> component.applicability() != Applicability.NOT_APPLICABLE)
                .mapToDouble(Component::weight).sum();
        double available = components.stream().mapToDouble(Component::availableWeight).sum();
        return denominator <= 0 ? 0 : available / denominator;
    }

    private static boolean fresh(List<Component> components, String name) {
        return components.stream().filter(x -> name.equals(x.name()))
                .allMatch(x -> x.applicability() == Applicability.AVAILABLE);
    }

    private static int sourceCount(List<Component> components) {
        Set<String> providers = new LinkedHashSet<>();
        for (Component component : components) {
            if (component.applicability() == Applicability.AVAILABLE
                    && component.provider() != null && !component.provider().isBlank()) {
                providers.add(component.provider().trim());
            }
        }
        return providers.size();
    }

    private static LocalDate date(RadarObservationResolver.AcceptedPrice accepted) {
        return accepted == null ? null : accepted.tradingDate();
    }

    /**
     * Use the market-context terminal session when it is available.  The
     * production assembler gets this date from the authoritative market
     * calendar/index completion path, so a price from a different session is
     * not allowed to masquerade as current.  Pure compatibility callers without
     * a market context may still use a bounded accepted-close fallback; the
     * resolver itself never infers a weekday or holiday.
     */
    private static LocalDate terminalTargetDate(Inputs input) {
        LocalDate decisionDate = RadarObservationResolver.targetDate(
                input.market(), input.decisionInstant());
        if (input.marketContext() != null && input.marketContext().asOfDate() != null
                && decisionDate != null
                && !input.marketContext().asOfDate().isAfter(decisionDate)) {
            return input.marketContext().asOfDate();
        }
        RadarObservationResolver.AcceptedPrice accepted = input.acceptedPrice();
        if (accepted != null && accepted.tradingDate() != null && decisionDate != null
                && !accepted.tradingDate().isAfter(decisionDate)
                && ChronoUnit.DAYS.between(accepted.tradingDate(), decisionDate) <= 5) {
            return accepted.tradingDate();
        }
        return decisionDate;
    }

    private static boolean sameTerminalDate(LocalDate value, LocalDate target) {
        return value != null && target != null && value.equals(target);
    }

    /**
     * Market-liquidity has its own terminal session. Before close, the required session is the
     * prior weekday; after close it is today's session. It must not borrow a stock's current live
     * date or accept an arbitrary older non-future row as complete market context.
     */
    private static LocalDate requiredMarketTerminalDate(Inputs input) {
        if (input == null || input.decisionInstant() == null || input.market() == null) return null;
        java.time.ZoneId zone = MarketZones.resolve(input.market());
        java.time.ZonedDateTime local = input.decisionInstant().atZone(zone);
        LocalDate current = local.toLocalDate();
        if (isWeekday(current) && !local.toLocalTime().isBefore(MarketZones.closeTime(input.market()))) {
            return current;
        }
        LocalDate previous = current.minusDays(1);
        for (int i = 0; i < 370; i++, previous = previous.minusDays(1)) {
            if (isWeekday(previous)) return previous;
        }
        return null;
    }

    private static boolean isWeekday(LocalDate date) {
        return date != null && date.getDayOfWeek().getValue() <= 5;
    }

    private static boolean freshDate(LocalDate value, LocalDate target, int maxAge) {
        return value != null && target != null && !value.isAfter(target)
                && ChronoUnit.DAYS.between(value, target) <= maxAge;
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.substring(0, Math.min(10, value.length()))); }
        catch (RuntimeException e) { return null; }
    }

    private static boolean finite(BigDecimal value) {
        if (value == null) return false;
        double d = value.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private static Double max(Double... values) {
        Double result = null;
        for (Double value : values) if (value != null) result = result == null ? value : Math.max(result, value);
        return result;
    }

    private static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }

    private static int clampInt(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
