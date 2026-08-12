package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）純讀 response。
 *
 * <p>所有分數／建議皆為 {@code TW_RULES_V12} 即時計算的衍生值，不入庫；
 * {@code score=null} 代表必要資料不足，不以 0 分冒充有效判斷。</p>
 */
public final class TradingRadarDto {

    private TradingRadarDto() {}

    public record Response(
            String ruleVersion,
            String actionPolicyVersion,
            String generatedAt,
            MarketSummary market,
            List<StockDecision> stocks,
            int skippedNonTwStocks,
            List<PublicInformationItem> publicInformation
    ) {
        /** t316 前的 response 形狀；舊快照沒有 action policy，不得由 ruleVersion 推導。 */
        public Response(String ruleVersion, String generatedAt, MarketSummary market,
                        List<StockDecision> stocks, int skippedNonTwStocks,
                        List<PublicInformationItem> publicInformation) {
            this(ruleVersion, null, generatedAt, market, stocks, skippedNonTwStocks,
                    publicInformation);
        }

        /** 舊快照／舊測試相容建構式；Task 291 前沒有公開資訊清單。 */
        public Response(String ruleVersion, String generatedAt, MarketSummary market,
                        List<StockDecision> stocks, int skippedNonTwStocks) {
            this(ruleVersion, null, generatedAt, market, stocks, skippedNonTwStocks, List.of());
        }
    }

    /**
     * 公開財經資訊原文；主頁市場清單使用台灣／美國近 72 小時，個股基本面證據使用台灣近 120 日。
     * 兩者都只揭露來源，不做關鍵字情緒評分。
     */
    public record PublicInformationItem(
            String region,
            String title,
            String source,
            String url,
            String publishedAt,
            String summary,
            String knownAt,
            String availabilityBasis
    ) {
        /** Task 292 前的建構式；舊市場資訊不一定帶摘要。 */
        public PublicInformationItem(String region, String title, String source, String url, String publishedAt) {
            this(region, title, source, url, publishedAt, null, null, null);
        }

        /** Compatibility constructor for callers that already provide a summary. */
        public PublicInformationItem(String region, String title, String source, String url,
                                     String publishedAt, String summary) {
            this(region, title, source, url, publishedAt, summary, null, null);
        }
    }

    /**
     * 個股基本面與產業 observation 的 as-of 解析結果（Task 292）。
     *
     * <p>各衍生因子分別揭露 provider／sourceUrls／asOf；不得因為來源順位相同就
     * 把不同 observation 拼成一筆。{@code coverage} 只數 EPS、近似 ROE、營收、PE 四個
     * 基本面子因子；產業發展為另一項因子。ETF 整筆 {@code applicable=false}。</p>
     */
    /** Provenance of one valuation component; components may be from different observations/providers. */
    public record ValuationComponentEvidence(
            BigDecimal value,
            BigDecimal percentile,
            String provider,
            List<String> sourceUrls,
            String availableAt,
            String asOf,
            boolean loss) {
        /** Compatibility constructor for pre-categorical valuation evidence. */
        public ValuationComponentEvidence(
                BigDecimal value, BigDecimal percentile, String provider,
                List<String> sourceUrls, String availableAt, String asOf) {
            this(value, percentile, provider, sourceUrls, availableAt, asOf, false);
        }
    }

    public record FundamentalSnapshot(
            boolean applicable,
            int coverage,
            BigDecimal epsYoyPct,
            BigDecimal approximateRoePct,
            BigDecimal revenueYoy3mPct,
            BigDecimal pePercentile,
            Boolean peLossFlag,
            String epsProvider,
            List<String> epsSourceUrls,
            String epsAsOf,
            String roeProvider,
            List<String> roeSourceUrls,
            String roeAsOf,
            String revenueProvider,
            List<String> revenueSourceUrls,
            String revenueAsOf,
            String valuationProvider,
            List<String> valuationSourceUrls,
            String valuationAsOf,
            String industryName,
            BigDecimal industryRevenueYoyPct,
            Integer industryCompanyCount,
            String industryPeriod,
            String industryProvider,
            List<String> industrySourceUrls,
            String industryAsOf,
            List<PublicInformationItem> companyPublicInformation,
            List<PublicInformationItem> industryPublicInformation,
            /** valuation composite 的三個原始值（百分點／倍數依欄位語意保存）。 */
            BigDecimal peValue,
            BigDecimal pbValue,
            BigDecimal dividendYieldPct,
            BigDecimal pbPercentile,
            BigDecimal dividendYieldPercentile,
            Double valuationContribution,
            int valuationCoverage,
            String epsTrendType,
            boolean roeApproximationFallback,
            ValuationComponentEvidence peEvidence,
            ValuationComponentEvidence pbEvidence,
            ValuationComponentEvidence dividendYieldEvidence
    ) {
        /** t307 前舊快照／測試建構式；新證據欄位以缺值保留，不冒充可用。 */
        public FundamentalSnapshot(
                boolean applicable, int coverage,
                BigDecimal epsYoyPct, BigDecimal approximateRoePct, BigDecimal revenueYoy3mPct,
                BigDecimal pePercentile, Boolean peLossFlag,
                String epsProvider, List<String> epsSourceUrls, String epsAsOf,
                String roeProvider, List<String> roeSourceUrls, String roeAsOf,
                String revenueProvider, List<String> revenueSourceUrls, String revenueAsOf,
                String valuationProvider, List<String> valuationSourceUrls, String valuationAsOf,
                String industryName, BigDecimal industryRevenueYoyPct, Integer industryCompanyCount,
                String industryPeriod, String industryProvider, List<String> industrySourceUrls,
                String industryAsOf, List<PublicInformationItem> companyPublicInformation,
                List<PublicInformationItem> industryPublicInformation) {
            this(applicable, coverage, epsYoyPct, approximateRoePct, revenueYoy3mPct,
                    pePercentile, peLossFlag, epsProvider, epsSourceUrls, epsAsOf,
                    roeProvider, roeSourceUrls, roeAsOf, revenueProvider, revenueSourceUrls,
                    revenueAsOf, valuationProvider, valuationSourceUrls, valuationAsOf,
                    industryName, industryRevenueYoyPct, industryCompanyCount, industryPeriod,
                    industryProvider, industrySourceUrls, industryAsOf,
                    companyPublicInformation, industryPublicInformation,
                    null, null, null, null, null, null, 0, null, false,
                    null, null, null);
        }

        /** Compatibility constructor for callers using the pre-provenance composite fields. */
        public FundamentalSnapshot(
                boolean applicable, int coverage,
                BigDecimal epsYoyPct, BigDecimal approximateRoePct, BigDecimal revenueYoy3mPct,
                BigDecimal pePercentile, Boolean peLossFlag,
                String epsProvider, List<String> epsSourceUrls, String epsAsOf,
                String roeProvider, List<String> roeSourceUrls, String roeAsOf,
                String revenueProvider, List<String> revenueSourceUrls, String revenueAsOf,
                String valuationProvider, List<String> valuationSourceUrls, String valuationAsOf,
                String industryName, BigDecimal industryRevenueYoyPct, Integer industryCompanyCount,
                String industryPeriod, String industryProvider, List<String> industrySourceUrls,
                String industryAsOf, List<PublicInformationItem> companyPublicInformation,
                List<PublicInformationItem> industryPublicInformation,
                BigDecimal peValue, BigDecimal pbValue, BigDecimal dividendYieldPct,
                BigDecimal pbPercentile, BigDecimal dividendYieldPercentile,
                Double valuationContribution, int valuationCoverage,
                String epsTrendType, boolean roeApproximationFallback) {
            this(applicable, coverage, epsYoyPct, approximateRoePct, revenueYoy3mPct,
                    pePercentile, peLossFlag, epsProvider, epsSourceUrls, epsAsOf,
                    roeProvider, roeSourceUrls, roeAsOf, revenueProvider, revenueSourceUrls,
                    revenueAsOf, valuationProvider, valuationSourceUrls, valuationAsOf,
                    industryName, industryRevenueYoyPct, industryCompanyCount, industryPeriod,
                    industryProvider, industrySourceUrls, industryAsOf,
                    companyPublicInformation, industryPublicInformation,
                    peValue, pbValue, dividendYieldPct, pbPercentile, dividendYieldPercentile,
                    valuationContribution, valuationCoverage, epsTrendType,
                    roeApproximationFallback, null, null, null);
        }
    }

    /** t309 第一階段 strict asset profile 的 API projection；前端只呈現，不自行推斷。 */
    public record AssetProfile(
            String assetClass,
            String assetClassSource,
            boolean assetClassComplete,
            String instrumentKind,
            String instrumentKindSource,
            boolean instrumentKindComplete,
            String stockStyle,
            String stockStyleSource,
            boolean stockStyleComplete,
            String bondTerm,
            String bondTermSource,
            boolean bondTermComplete,
            String quoteCurrency,
            String quoteCurrencySource,
            boolean quoteCurrencyComplete,
            String underlyingCurrency,
            String underlyingCurrencySource,
            boolean underlyingCurrencyComplete,
            boolean currencyDataComplete,
            boolean profileComplete,
            List<String> missingReasons
    ) {
        public static AssetProfile from(
                com.steven.assets.service.TradingRadarAssetProfileResolver.AssetProfile profile) {
            if (profile == null) return null;
            return new AssetProfile(
                    profile.assetClass(), profile.assetClassSource().name(),
                    profile.assetClassComplete(), profile.instrumentKind().name(), profile.instrumentKindSource().name(),
                    profile.instrumentKindComplete(), profile.stockStyle(), profile.stockStyleSource().name(),
                    profile.stockStyleComplete(), profile.bondTerm(), profile.bondTermSource().name(),
                    profile.bondTermComplete(), profile.quoteCurrency(), profile.quoteCurrencySource().name(),
                    profile.quoteCurrencyComplete(), profile.underlyingCurrency(),
                    profile.underlyingCurrencySource().name(), profile.underlyingCurrencyComplete(),
                    profile.currencyDataComplete(),
                    profile.profileComplete(), profile.missingReasons());
        }
    }

    /** Immutable API projection of one evidence component/group. */
    public record EvidenceComponent(
            String name,
            String applicability,
            double weight,
            double availableWeight,
            String asOfDate,
            String provider,
            String missingReason) {}

    public record EvidenceGroup(
            String group,
            List<EvidenceComponent> components,
            double shortCoverage,
            double mediumCoverage,
            boolean shortAvailable,
            boolean mediumAvailable,
            boolean shortFresh,
            boolean mediumFresh,
            int sourceCount,
            boolean participates) {}

    /** Immutable API projection of one V13 typed market candidate observation. */
    public record MarketFeatureEvidence(
            String code,
            BigDecimal value,
            String asOfDate,
            String availableAt,
            String availabilityBasis,
            String provider,
            String sourceUrl,
            String profileApplicability,
            String duplicateOf,
            String status,
            String missingReason) {}

    /** API projection of one per-signal normalized-BIAS observation. */
    public record NormalizedBiasEvidence(
            boolean enabled,
            BigDecimal rawBiasRatio,
            BigDecimal rawSigmaRatio,
            BigDecimal sigmaFloorRatio,
            BigDecimal effectiveSigmaRatio,
            BigDecimal normalizedBias,
            String asOfDate,
            boolean volatilityFallback,
            boolean floorApplied,
            String reason
    ) {
        public static NormalizedBiasEvidence from(
                com.steven.assets.service.TradingRadarRuleEngine.NormalizedBiasProvenance provenance) {
            if (provenance == null) return null;
            return new NormalizedBiasEvidence(provenance.enabled(), provenance.rawBiasRatio(),
                    provenance.rawSigmaRatio(), provenance.sigmaFloorRatio(),
                    provenance.effectiveSigmaRatio(), provenance.normalizedBias(),
                    provenance.asOfDate() == null ? null : provenance.asOfDate().toString(),
                    provenance.volatilityFallback(), provenance.floorApplied(), provenance.reason());
        }
    }

    /** t274/t309 provenance plus V13 confidence/risk projection. */
    public record RadarEvidence(
            String acceptedPriceAsOfDate,
            String acceptedPriceSource,
            String acceptedPriceQuality,
            boolean livePriceAccepted,
            BigDecimal returnStdDev60Ratio,
            String returnStdDev60AsOfDate,
            String returnStdDev60Source,
            String premiumAsOfDate,
            String premiumSource,
            boolean premiumStale,
            AssetProfile assetProfile,
            List<String> actionGateReasons,
            java.util.Map<String, EvidenceGroup> evidenceGroups,
            java.util.Map<String, MarketFeatureEvidence> marketFeatures,
            Integer shortEvidenceConfidence,
            Integer mediumEvidenceConfidence,
            Integer shortDownsideRisk,
            Integer mediumDownsideRisk,
            Double shortRiskCoverage,
            Double mediumRiskCoverage,
            String candidateAction,
            String shortCandidateAction,
            String nextDistributionDate,
            String nextDistributionKnownAt,
            String nextDistributionProvider,
            List<String> nextDistributionSourceUrls,
            String nextDistributionStatus,
            String nextDistributionMissingReason,
            Integer distributionsWithinFiveSessions,
            Integer distributionsWithinTwentySessions,
            TreasuryYieldDto.RateContext treasuryRateContext,
            /** Per-signal medium-horizon normalized-BIAS provenance. */
            NormalizedBiasEvidence normalizedBias,
            /** Per-signal short-horizon normalized-BIAS provenance. */
            NormalizedBiasEvidence shortNormalizedBias
    ) {
        public RadarEvidence {
            actionGateReasons = actionGateReasons == null ? List.of() : List.copyOf(actionGateReasons);
            evidenceGroups = evidenceGroups == null ? java.util.Map.of() : java.util.Map.copyOf(evidenceGroups);
            marketFeatures = marketFeatures == null ? java.util.Map.of() : java.util.Map.copyOf(marketFeatures);
            nextDistributionSourceUrls = nextDistributionSourceUrls == null
                    ? List.of() : List.copyOf(nextDistributionSourceUrls);
        }

        public RadarEvidence(
                String acceptedPriceAsOfDate,
                String acceptedPriceSource,
                String acceptedPriceQuality,
                boolean livePriceAccepted,
                BigDecimal returnStdDev60Ratio,
                String returnStdDev60AsOfDate,
                String returnStdDev60Source,
                String premiumAsOfDate,
                String premiumSource,
                boolean premiumStale,
                AssetProfile assetProfile,
                List<String> actionGateReasons) {
            this(acceptedPriceAsOfDate, acceptedPriceSource, acceptedPriceQuality, livePriceAccepted,
                    returnStdDev60Ratio, returnStdDev60AsOfDate, returnStdDev60Source,
                    premiumAsOfDate, premiumSource, premiumStale, assetProfile, actionGateReasons,
                    java.util.Map.of(), java.util.Map.of(), null, null, null, null, null, null, null, null,
                    null, null, null, List.of(), null, null, null, null, null, null, null);
        }

        public static final RadarEvidence EMPTY = new RadarEvidence(
                null, null, "MISSING", false, null, null, null,
                null, null, false, null, List.of(), java.util.Map.of(), java.util.Map.of(),
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(), null, null, null, null, null, null, null);

        public static RadarEvidence withConfidence(
                String acceptedPriceAsOfDate,
                String acceptedPriceSource,
                String acceptedPriceQuality,
                boolean livePriceAccepted,
                BigDecimal returnStdDev60Ratio,
                String returnStdDev60AsOfDate,
                String returnStdDev60Source,
                String premiumAsOfDate,
                String premiumSource,
                boolean premiumStale,
                AssetProfile assetProfile,
                List<String> actionGateReasons,
                com.steven.assets.service.TradingRadarEvidenceConfidenceResolver.Evidence evidence,
                String candidateAction,
                String shortCandidateAction) {
            return withConfidence(acceptedPriceAsOfDate, acceptedPriceSource, acceptedPriceQuality,
                    livePriceAccepted, returnStdDev60Ratio, returnStdDev60AsOfDate, returnStdDev60Source,
                    premiumAsOfDate, premiumSource, premiumStale, assetProfile, actionGateReasons,
                    evidence, candidateAction, shortCandidateAction, null, null, null, null);
        }

        public static RadarEvidence withConfidence(
                String acceptedPriceAsOfDate,
                String acceptedPriceSource,
                String acceptedPriceQuality,
                boolean livePriceAccepted,
                BigDecimal returnStdDev60Ratio,
                String returnStdDev60AsOfDate,
                String returnStdDev60Source,
                String premiumAsOfDate,
                String premiumSource,
                boolean premiumStale,
                AssetProfile assetProfile,
                List<String> actionGateReasons,
                com.steven.assets.service.TradingRadarEvidenceConfidenceResolver.Evidence evidence,
                String candidateAction,
                String shortCandidateAction,
                com.steven.assets.service.DividendEventEvidenceResolver.Resolution distribution) {
            return withConfidence(acceptedPriceAsOfDate, acceptedPriceSource, acceptedPriceQuality,
                    livePriceAccepted, returnStdDev60Ratio, returnStdDev60AsOfDate, returnStdDev60Source,
                    premiumAsOfDate, premiumSource, premiumStale, assetProfile, actionGateReasons,
                    evidence, candidateAction, shortCandidateAction, distribution, null, null, null);
        }

        public static RadarEvidence withConfidence(
                String acceptedPriceAsOfDate,
                String acceptedPriceSource,
                String acceptedPriceQuality,
                boolean livePriceAccepted,
                BigDecimal returnStdDev60Ratio,
                String returnStdDev60AsOfDate,
                String returnStdDev60Source,
                String premiumAsOfDate,
                String premiumSource,
                boolean premiumStale,
                AssetProfile assetProfile,
                List<String> actionGateReasons,
                com.steven.assets.service.TradingRadarEvidenceConfidenceResolver.Evidence evidence,
                String candidateAction,
                String shortCandidateAction,
                com.steven.assets.service.DividendEventEvidenceResolver.Resolution distribution,
                TreasuryYieldDto.RateContext treasuryRateContext,
                NormalizedBiasEvidence normalizedBias,
                NormalizedBiasEvidence shortNormalizedBias) {
            java.util.Map<String, EvidenceGroup> groups = new java.util.LinkedHashMap<>();
            if (evidence != null) {
                for (var entry : evidence.groups().entrySet()) {
                    var group = entry.getValue();
                    List<EvidenceComponent> components = group.components().stream()
                            .map(c -> new EvidenceComponent(c.name(), c.applicability().name(), c.weight(),
                                    c.availableWeight(),
                                    c.asOfDate() == null ? null : c.asOfDate().toString(),
                                    c.provider(), c.missingReason()))
                            .toList();
                    groups.put(entry.getKey().name(), new EvidenceGroup(
                            entry.getKey().name(), components, group.shortCoverage(), group.mediumCoverage(),
                            group.shortAvailable(), group.mediumAvailable(), group.shortFresh(),
                            group.mediumFresh(), group.sourceCount(), group.participates()));
                }
            }
            java.util.Map<String, MarketFeatureEvidence> marketFeatures = new java.util.LinkedHashMap<>();
            if (evidence != null && evidence.marketFeatures() != null) {
                for (var entry : evidence.marketFeatures().features().entrySet()) {
                    var feature = entry.getValue();
                    marketFeatures.put(entry.getKey(), new MarketFeatureEvidence(
                            feature.code(), feature.value(),
                            feature.asOfDate() == null ? null : feature.asOfDate().toString(),
                            feature.availableAt() == null ? null : feature.availableAt().toString(),
                            feature.availabilityBasis(), feature.provider(), feature.sourceUrl(),
                            feature.profileApplicability(), feature.duplicateOf(), feature.status(),
                            feature.missingReason()));
                }
            }
            return new RadarEvidence(acceptedPriceAsOfDate, acceptedPriceSource, acceptedPriceQuality,
                    livePriceAccepted, returnStdDev60Ratio, returnStdDev60AsOfDate, returnStdDev60Source,
                    premiumAsOfDate, premiumSource, premiumStale, assetProfile,
                    actionGateReasons == null ? List.of() : actionGateReasons, groups, marketFeatures,
                    evidence == null ? null : evidence.shortConfidence(),
                    evidence == null ? null : evidence.mediumConfidence(),
                    evidence == null ? null : evidence.shortDownsideRisk(),
                    evidence == null ? null : evidence.mediumDownsideRisk(),
                    evidence == null ? null : evidence.shortRisk().riskCoverage(),
                    evidence == null ? null : evidence.mediumRisk().riskCoverage(),
                    candidateAction, shortCandidateAction,
                    distribution == null || distribution.nextEvent() == null
                            ? null : distribution.nextEvent().exDividendDate().toString(),
                    distribution == null || distribution.knownAt() == null
                            ? null : distribution.knownAt().toString(),
                    distribution == null ? null : distribution.provider(),
                    distribution == null ? List.of() : distribution.sourceUrls(),
                    distribution == null ? null : distribution.status().name(),
                    distribution == null ? null : distribution.missingReason(),
                    distribution == null ? null : distribution.eventsWithinFiveSessions(),
                    distribution == null ? null : distribution.eventsWithinTwentySessions(),
                    treasuryRateContext, normalizedBias, shortNormalizedBias);
        }
    }

    /**
     * 手動「重新整理」的行情回補結果（Task 249）。
     *
     * <p>{@code outcome} ∈ {@code FETCHED}（開盤中已重抓）／{@code CLOSED_SYNCED}（休市，已同步 DB 收盤）／
     * {@code SKIPPED_PENDING_CLOSE}（今日收盤尚未落 DB 的空窗，刻意不同步）／{@code COOLDOWN}／
     * {@code BUSY}／{@code TIMEOUT}／{@code FAILED}。</p>
     *
     * <p><b>刻意不含抓取檔數</b>：回補清單來自全庫（Redis 行情快取本就是跨租戶共用的市場資料），
     * 回檔數等於把「全庫台股標的數」洩漏給任一使用者；檔數只寫 log。</p>
     */
    public record PriceRefresh(String outcome, boolean twMarketOpen, long elapsedMs) {}

    /**
     * 走勢圖指標選單（Task 262）同一組值的雷達版（Task 281）。
     *
     * <p>Task 291 起，J／MACD／RSI／乖離率／威廉指標會以各自所屬因子組進入短期與中期評分；
     * EMA12／EMA26 透過 DIF、DIF／MACD 透過 OSC 同源納入，避免代數相依值重複灌權重。</p>
     *
     * <p><b>價基與同一列的 {@code kValue}／{@code dValue} 相同</b>：個股為還原權息序列、
     * 大盤為指數日線序列，與雙擊該列開啟的走勢圖（原始價基）<b>刻意不同</b>，
     * 凡視窗內有配息／除權的個股必然對不上，這是既有鐵則「禁止混用原始／還原價」的結果。</p>
     *
     * <p>暖機／視窗不足的欄位為 {@code null}，<b>不得以 0 充數</b>。全部 2 位小數。</p>
     */
    public record ExtendedIndicators(
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv,
            BigDecimal ema12,
            BigDecimal ema26,
            BigDecimal dif,
            BigDecimal macd,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal b10b20,
            BigDecimal wr9
    ) {}

    /**
     * {@code POST /api/trading-radar/refresh} 的回應（Task 249）。
     *
     * <p>{@code radar} 與 {@code GET} 完全同形——{@link Response} 不得為此新增欄位，
     * 它會被 {@code TradingRadarSnapshotStore} 序列化進 Redis 快照供 Requirement 48 的區間匯出讀回。</p>
     */
    public record RefreshResponse(Response radar, PriceRefresh priceRefresh) {}

    public record MarketSummary(
            String regime,
            String regimeLabel,
            Integer score,
            boolean dataComplete,
            /** 大盤最新完成日 K 非當前交易日、且 Redis 亦無今日即時價：買進閘門關閉、不採計 RISK_ON 加分（Task 217.1，語意於 Task 228 擴充）。 */
            boolean stale,
            String asOfDate,
            BigDecimal price,
            BigDecimal changePercent,
            String quoteStatus,
            /** 週線 MA5；Task 291 起進入短期與中期的獨立權重。 */
            BigDecimal weeklyMa,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String quarterlyConfirmation,
            String annualConfirmation,
            List<String> reasons,
            List<String> risks,
            /** regime 是否由 Redis 今日即時點位算出（相對於「已入庫完成日 K」）（Task 228）。 */
            boolean intraday,
            /** intraday=true 時為 Redis 即時價的 updatedAt（ISO 字串）；否則為 null（Task 228）。 */
            String liveUpdatedAt,
            /** 走勢圖指標選單同一組值；Task 291 起進入雙軌評分並在展開列顯示。 */
            ExtendedIndicators extendedIndicators,
            BigDecimal marketVolumeRatio,
            BigDecimal marketTurnoverRatio,
            String marketVolumeAsOfDate,
            BigDecimal nasdaqChangePercent,
            BigDecimal soxChangePercent,
            BigDecimal usTechCompositePercent,
            String usTechAsOfDate,
            boolean usTechAvailable
    ) {
        /** Task 291 前的欄位形狀，供既有測試與舊快照相容。 */
        public MarketSummary(
                String regime, String regimeLabel, Integer score, boolean dataComplete, boolean stale,
                String asOfDate, BigDecimal price, BigDecimal changePercent, String quoteStatus,
                BigDecimal weeklyMa, BigDecimal monthlyMa, BigDecimal quarterlyMa, BigDecimal annualMa,
                BigDecimal kValue, BigDecimal dValue, String quarterlyConfirmation, String annualConfirmation,
                List<String> reasons, List<String> risks, boolean intraday, String liveUpdatedAt,
                ExtendedIndicators extendedIndicators) {
            this(regime, regimeLabel, score, dataComplete, stale, asOfDate, price, changePercent, quoteStatus,
                    weeklyMa, monthlyMa, quarterlyMa, annualMa, kValue, dValue,
                    quarterlyConfirmation, annualConfirmation, reasons, risks, intraday, liveUpdatedAt,
                    extendedIndicators, null, null, null, null, null, null, null, false);
        }
    }

    public record StockDecision(
            String stockCode,
            String stockName,
            String market,
            String assetClass,
            boolean distributionAdjusted,
            boolean held,
            String action,
            String actionLabel,
            Integer score,
            String counterTrendState,
            String counterTrendLabel,
            List<String> counterTrendReasons,
            List<String> counterTrendRisks,
            boolean dataComplete,
            BigDecimal price,
            BigDecimal changePercent,
            String quoteStatus,
            String priceUpdatedAt,
            String asOfDate,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String monthlyConfirmation,
            String quarterlyConfirmation,
            String annualConfirmation,
            /**
             * 底層資產幣別對台幣的五年期分位（0–100）；台幣資產為 null（Requirement 47）。
             * 供畫面揭露「現在換匯貴不貴」——台幣計價的美債 ETF 其報價相當部分由匯率驅動
             * （實測 00719B 與 USD/TWD 近一年相關 0.9737），不揭露會讓使用者以為漲勢來自標的本身。
             */
            BigDecimal fxPercentile,
            /** 底層資產幣別（TWD/USD/GBP…），供前端判斷是否顯示匯率相關說明。 */
            String underlyingCurrency,
            List<String> reasons,
            List<String> risks,
            /**
             * KD 短線熱度：{@code OVERHEATED}／{@code ELEVATED}／{@code NORMAL}（Task 232）。
             *
             * <p>供收合列即可辨識——{@code reasons}／{@code risks} 只在展開後顯示，
             * 使用者於收合狀態看不出 K 已偏高。KD/J 因子本身已反映在兩軌分數；
             * {@code OVERHEATED} 另外關閉買進閘門，{@code ELEVATED} 不另加硬閘門。</p>
             */
            String kdHeat,
            /** 進場時機（Task 264）；供收合列辨識，並對動作做雙向覆寫。 */
            String timingState,
            String timingLabel,
            /** 現價對季線的乖離率（%），供進場時機判斷。 */
            BigDecimal ma60BiasPercent,
            /** 52 週相對位置 [0,1]（已 clamp）。 */
            BigDecimal week52Position,
            /** 週線 MA5；Task 291 起進入短期與中期評分。 */
            BigDecimal weeklyMa,
            /** ETF 折溢價（%）；非 ETF 為 null，畫面不得顯示為 0。 */
            BigDecimal etfPremiumPct,
            /** ETF 折溢價的自身歷史分位（0–100）；樣本不足或非 ETF 為 null。 */
            BigDecimal etfPremiumPercentile,
            /** 走勢圖指標選單同一組值；Task 291 起進入雙軌評分並在展開列顯示。 */
            ExtendedIndicators extendedIndicators,
            String shortAction,
            String shortActionLabel,
            Integer shortScore,
            List<String> shortReasons,
            List<String> shortRisks,
            boolean horizonConflict,
            BigDecimal volumeRatio,
            String fxAsOfDate,
            boolean profitTakingConfirmed,
            FundamentalSnapshot fundamental,
            RadarEvidence evidence,
            Integer shortDownsideRisk,
            Integer mediumDownsideRisk,
            Integer shortEvidenceConfidence,
            Integer mediumEvidenceConfidence,
            Double shortRiskCoverage,
            Double mediumRiskCoverage,
            String candidateAction,
            String shortCandidateAction,
            List<String> actionGateReasons,
            /**
             * ETF <b>即時</b>折溢價（%）；1.2 表示溢價 1.2%、負值為折價（Task 320）。純揭露、不進任何規則。
             *
             * <p><b>與上方 {@code etfPremiumPct} 是兩個不同語意的值，不可互相取代</b>：
             * {@code etfPremiumPct} 是 dated observation（只認已完成交易日，進 buyGate 硬否決與 OVERBOUGHT 判定，
             * 美股恆為 null）；本欄是與該列現價同一 tick 的即時值（台股取證交所權威值、美股以該列現價反推），
             * 盤中兩者本來就會不同。把即時值寫回 {@code etfPremiumPct} 會讓未完成 session 的 NAV 進入決策，
             * 且盤中每 5 分鐘讓同一決策日的 veto 結果漂移一次。
             */
            BigDecimal etfPremiumLivePct,
            /** 即時折溢價所用淨值的資料時點原樣字串（台股 {@code yyyyMMdd HH:mm:ss}、美股 {@code yyyy-MM-dd}）；無值為 null。 */
            String etfPremiumLiveNavAsOf
    ) {
        /** Task 291 前的欄位形狀，供既有測試建構資料。 */
        public StockDecision(
                String stockCode, String stockName, String market, String assetClass,
                boolean distributionAdjusted, boolean held, String action, String actionLabel, Integer score,
                String counterTrendState, String counterTrendLabel, List<String> counterTrendReasons,
                List<String> counterTrendRisks, boolean dataComplete, BigDecimal price,
                BigDecimal changePercent, String quoteStatus, String priceUpdatedAt, String asOfDate,
                BigDecimal monthlyMa, BigDecimal quarterlyMa, BigDecimal annualMa,
                BigDecimal kValue, BigDecimal dValue, String monthlyConfirmation,
                String quarterlyConfirmation, String annualConfirmation, BigDecimal fxPercentile,
                String underlyingCurrency, List<String> reasons, List<String> risks, String kdHeat,
                String timingState, String timingLabel, BigDecimal ma60BiasPercent,
                BigDecimal week52Position, BigDecimal weeklyMa, BigDecimal etfPremiumPct,
                BigDecimal etfPremiumPercentile, ExtendedIndicators extendedIndicators) {
            this(stockCode, stockName, market, assetClass, distributionAdjusted, held,
                    action, actionLabel, score, counterTrendState, counterTrendLabel,
                    counterTrendReasons, counterTrendRisks, dataComplete, price, changePercent,
                    quoteStatus, priceUpdatedAt, asOfDate, monthlyMa, quarterlyMa, annualMa,
                    kValue, dValue, monthlyConfirmation, quarterlyConfirmation, annualConfirmation,
                    fxPercentile, underlyingCurrency, reasons, risks, kdHeat, timingState, timingLabel,
                    ma60BiasPercent, week52Position, weeklyMa, etfPremiumPct, etfPremiumPercentile,
                    extendedIndicators, null, null, null, List.of(), List.of(), false, null, null, false,
                    null, RadarEvidence.EMPTY, null, null, null, null, null, null,
                    null, null, List.of(),
                    null, null); // Task 320：etfPremiumLivePct／etfPremiumLiveNavAsOf
        }
    }
}
