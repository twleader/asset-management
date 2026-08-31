package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）純讀 response。
 *
 * <p>所有分數／建議皆為 {@code TW_RULES_V18} 即時計算的衍生值，不入庫；
 * {@code score=null} 代表必要資料不足，不以 0 分冒充有效判斷。</p>
 */
public final class TradingRadarDto {

    private TradingRadarDto() {}

    /**
     * @param market   台股（TAIEX）組大盤 summary。<b>欄位名一律不得改為 {@code twMarket}</b>——
     *                 該名稱已被 Redis 快照 JSON、{@code TradingRadarExportService} 與前端多處讀取。
     * @param usMarket 美股（IXIC）組大盤 summary（t335），與 {@code market} <b>同型別</b>。
     *                 其值即 {@code TradingRadarService.buildUsMarket()} 餵給美股個股評分的
     *                 <b>同一份</b> summary，非另算——兩次計算之間 Redis／DB 狀態可能改變，
     *                 會讓「個股評分依據的美股 regime」與「畫面顯示的美股 regime」對不上。
     *                 舊快照／相容建構式建立的 response 此欄為 {@code null}。
     */
    public record Response(
            String ruleVersion,
            String actionPolicyVersion,
            String generatedAt,
            MarketSummary market,
            MarketSummary usMarket,
            List<StockDecision> stocks,
            int skippedNonTwStocks,
            List<PublicInformationItem> publicInformation
    ) {
        /** t335 前的 canonical 形狀；舊快照沒有美股大盤組，不得以台股那份冒充。 */
        public Response(String ruleVersion, String actionPolicyVersion, String generatedAt,
                        MarketSummary market, List<StockDecision> stocks, int skippedNonTwStocks,
                        List<PublicInformationItem> publicInformation) {
            this(ruleVersion, actionPolicyVersion, generatedAt, market, null, stocks,
                    skippedNonTwStocks, publicInformation);
        }

        /** t316 前的 response 形狀；舊快照沒有 action policy，不得由 ruleVersion 推導。 */
        public Response(String ruleVersion, String generatedAt, MarketSummary market,
                        List<StockDecision> stocks, int skippedNonTwStocks,
                        List<PublicInformationItem> publicInformation) {
            this(ruleVersion, null, generatedAt, market, null, stocks, skippedNonTwStocks,
                    publicInformation);
        }

        /** 舊快照／舊測試相容建構式；Task 291 前沒有公開資訊清單。 */
        public Response(String ruleVersion, String generatedAt, MarketSummary market,
                        List<StockDecision> stocks, int skippedNonTwStocks) {
            this(ruleVersion, null, generatedAt, market, null, stocks, skippedNonTwStocks, List.of());
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

    /**
     * Settings-page-equivalent classification for the radar detail display.
     *
     * <p>This is not {@link AssetProfile}: it deliberately retains the Asset
     * Class Settings page's latest-snapshot dividend and permissive bond-term
     * fallback semantics.  It is null only for the Taiwan market index or a
     * legacy snapshot that predates this additive projection.</p>
     */
    public record SettingsClassification(
            String effectiveAssetClass,
            String assetClassSource,
            String effectiveStockStyle,
            String stockStyleSource,
            String effectiveBondTerm,
            String bondTermSource,
            String assetClassOverride,
            String stockStyleOverride,
            String bondTermOverride
    ) {
        public static SettingsClassification from(
                com.steven.assets.service.TradingRadarSettingsClassificationResolver.Resolution resolution) {
            if (resolution == null) return null;
            return new SettingsClassification(
                    resolution.effectiveAssetClass(), resolution.assetClassSource(),
                    resolution.effectiveStockStyle(), resolution.stockStyleSource(),
                    resolution.effectiveBondTerm(), resolution.bondTermSource(),
                    resolution.assetClassOverride(), resolution.stockStyleOverride(), resolution.bondTermOverride());
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

    /**
     * 一組證據的 API projection（Task 356.11c 起為<b>三軌</b>）。
     *
     * <p>{@code swingCoverage}／{@code swingAvailable}／{@code swingFresh} 來自
     * {@code TradingRadarEvidenceConfidenceResolver.GroupEvidence} 的第三份獨立欄位，
     * <b>不是 medium 的複本</b>——各 group 為 SWING 另組一份 {@code swingComponents} 與其權重。</p>
     */
    public record EvidenceGroup(
            String group,
            List<EvidenceComponent> components,
            double shortCoverage,
            double swingCoverage,
            double mediumCoverage,
            boolean shortAvailable,
            boolean swingAvailable,
            boolean mediumAvailable,
            boolean shortFresh,
            boolean swingFresh,
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
            /**
             * anchorDate = {@code LEAST(nextExDividendDate, nextExRightsDate)}（取較早者，非 COALESCE）
             * （Task 357／Requirement 94）。357 之前只代表除息日；純配股事件此欄現在
             * 落的是除權日。既有消費端若只需要「下一次事件哪天發生」可繼續用本欄；
             * 需要區分除息／除權／發放股息／發放股權，改用下方四個新欄位。
             */
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
            NormalizedBiasEvidence shortNormalizedBias,
            /**
             * 1周~1月 軌的證據投影（Task 356.11c）。
             *
             * <p>四欄一律追加在既有欄位<b>之後</b>，既有兩軌的 component 順序與名稱不動。
             * 值來自 {@code TradingRadarEvidenceConfidenceResolver} 的 SWING horizon，
             * <b>不得</b>由 medium 複製——那正是 Task 356.9a-2 要消滅的假證據鏈。</p>
             */
            Integer swingDownsideRisk,
            Integer swingEvidenceConfidence,
            Double swingRiskCoverage,
            String swingCandidateAction,
            /**
             * 「下一配息」同一次事件的四個日期（Task 357／Requirement 94），一律
             * {@code yyyy-MM-dd} 或 null（缺值不得以 0／空字串／往年推估頂替）。追加在
             * 既有欄位之後而非插入 next* 群組之間，以保留既有相容建構式與
             * {@code EMPTY} 的呼叫端不動。
             */
            String nextExDividendDate,
            String nextExRightsDate,
            String nextCashPaymentDate,
            String nextStockPaymentDate,
            /** Settings-page-equivalent classification; strict radar profile remains {@code assetProfile}. */
            SettingsClassification settingsClassification
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
                    null, null, null, List.of(), null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);
        }

        public static final RadarEvidence EMPTY = new RadarEvidence(
                null, null, "MISSING", false, null, null, null,
                null, null, false, null, List.of(), java.util.Map.of(), java.util.Map.of(),
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

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
                    evidence, candidateAction, shortCandidateAction, null, null, null, null, null);
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
                    evidence, candidateAction, shortCandidateAction, distribution, null, null, null, null);
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
                NormalizedBiasEvidence shortNormalizedBias,
                String swingCandidateAction) {
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
                            entry.getKey().name(), components,
                            group.shortCoverage(), group.swingCoverage(), group.mediumCoverage(),
                            group.shortAvailable(), group.swingAvailable(), group.mediumAvailable(),
                            group.shortFresh(), group.swingFresh(), group.mediumFresh(),
                            group.sourceCount(), group.participates()));
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
            // Task 357／Requirement 94：nextDistributionDate 重新定義為 anchorDate =
            // min(exDividendDate, exRightsDate)——純配股的下一事件 exDividendDate()
            // 為 null，裸呼叫 .toString() 會 NPE；四個新欄位各自揭露原始值，缺值為 null。
            com.steven.assets.service.DividendEventEvidenceResolver.Event nextEvent =
                    distribution == null ? null : distribution.nextEvent();
            java.time.LocalDate nextAnchor = nextEvent == null ? null : nextEvent.anchorDate();
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
                    nextAnchor == null ? null : nextAnchor.toString(),
                    distribution == null || distribution.knownAt() == null
                            ? null : distribution.knownAt().toString(),
                    distribution == null ? null : distribution.provider(),
                    distribution == null ? List.of() : distribution.sourceUrls(),
                    distribution == null ? null : distribution.status().name(),
                    distribution == null ? null : distribution.missingReason(),
                    distribution == null ? null : distribution.eventsWithinFiveSessions(),
                    distribution == null ? null : distribution.eventsWithinTwentySessions(),
                    treasuryRateContext, normalizedBias, shortNormalizedBias,
                    evidence == null ? null : evidence.swingDownsideRisk(),
                    evidence == null ? null : evidence.swingConfidence(),
                    evidence == null ? null : evidence.swingRisk().riskCoverage(),
                    swingCandidateAction,
                    nextEvent == null || nextEvent.exDividendDate() == null
                            ? null : nextEvent.exDividendDate().toString(),
                    nextEvent == null || nextEvent.exRightsDate() == null
                            ? null : nextEvent.exRightsDate().toString(),
                    nextEvent == null || nextEvent.cashPaymentDate() == null
                            ? null : nextEvent.cashPaymentDate().toString(),
                    nextEvent == null || nextEvent.stockPaymentDate() == null
                            ? null : nextEvent.stockPaymentDate().toString(),
                    null);
        }

        /**
         * Adds the settings-equivalent display projection without changing the
         * strict asset profile or any evidence/rule inputs.  This is used for
         * both successful and incomplete current radar rows; old persisted
         * snapshots naturally deserialize with this additive field as null.
         */
        public RadarEvidence withSettingsClassification(SettingsClassification classification) {
            return new RadarEvidence(
                    acceptedPriceAsOfDate, acceptedPriceSource, acceptedPriceQuality, livePriceAccepted,
                    returnStdDev60Ratio, returnStdDev60AsOfDate, returnStdDev60Source,
                    premiumAsOfDate, premiumSource, premiumStale, assetProfile, actionGateReasons,
                    evidenceGroups, marketFeatures, shortEvidenceConfidence, mediumEvidenceConfidence,
                    shortDownsideRisk, mediumDownsideRisk, shortRiskCoverage, mediumRiskCoverage,
                    candidateAction, shortCandidateAction, nextDistributionDate, nextDistributionKnownAt,
                    nextDistributionProvider, nextDistributionSourceUrls, nextDistributionStatus,
                    nextDistributionMissingReason, distributionsWithinFiveSessions,
                    distributionsWithinTwentySessions, treasuryRateContext, normalizedBias,
                    shortNormalizedBias, swingDownsideRisk, swingEvidenceConfidence, swingRiskCoverage,
                    swingCandidateAction, nextExDividendDate, nextExRightsDate, nextCashPaymentDate,
                    nextStockPaymentDate, classification);
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
     * <p>Task 291 起，J／MACD／RSI／乖離率／威廉指標會以各自所屬因子組進入評分
     * （Task 356 起為一周／1周~1月／1月~6月<b>三軌</b>各自加權）；
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
     * 最新<b>完成日</b>的還原 K 棒與其三個分量（Task 356.11a）。
     *
     * <p>{@code closePosition}／{@code bodyDirection}／{@code lowerShadowRatio} 一律是
     * {@code TradingRadarRuleEngine} 三支 public static 純函數的<b>原值</b>（未 clamp、未線性轉換），
     * 與引擎計分共用同一支實作。全幅非正（漲跌停鎖死、整日單一成交價或倒置髒列）時三者皆為
     * {@code null}，畫面顯示 {@code —}，<b>不得顯示 0</b>——{@code 0} 是十字線（{@code high > low}
     * 且 {@code close == open}），與「沒有價格區間」是完全不同的狀態。</p>
     *
     * <p>{@code asOfDate} 即該列最新完成日的交易日，與
     * {@code RadarEvidence.returnStdDev60AsOfDate} 同源。</p>
     */
    public record DailyCandle(
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal closePosition,
            BigDecimal bodyDirection,
            BigDecimal lowerShadowRatio,
            String asOfDate
    ) {}

    /**
     * 最新<b>完成週</b>的週K 棒與週K 指標（Task 356.11a）。
     *
     * <p><b>與同一列的 {@code weeklyMa} 是兩個不同的量</b>：{@code weeklyMa} 是<b>日K 收盤序列的
     * 5 日簡單移動平均</b>（命名沿革，Task 356 刻意不改名以免既有 Redis 快照、Excel 匯出與
     * OpenAPI {@code required} 清單同時靜默改變語意）；本 record 才是真正由週 OHLC 聚合、
     * 在週K 序列上重算的指標。</p>
     *
     * <p><b>與股票分析走勢圖的週K 也是兩個不同的量</b>（{@code ChartSeriesAligner.weekly}）：
     * 週界分桶規則逐字相同，但 (1) 本 record 的價基是<b>還原權息／分割後</b>的日K 聚合、
     * (2) 一律排除進行中週、(3) 壞資料週的處理不同。畫面與匯出文案不得讓使用者以為是同一個值。</p>
     *
     * <p>{@code weekEndDate} 為<b>上一個完成週</b>的最後交易日（不是本週任何一天）；
     * {@code completedWeeks} 不足 {@code RadarInputAssembler.MIN_COMPLETED_WEEKS} 時整組指標為
     * {@code null} 而 {@code completedWeeks} 仍如實回報，供揭露文案寫出「目前 N 根」。
     * 全部 2 位小數，缺值為 {@code null}，<b>不得顯示 0</b>。</p>
     */
    public record WeeklyIndicators(
            String weekEndDate,
            Integer completedWeeks,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume,
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal k,
            BigDecimal d,
            BigDecimal j9,
            BigDecimal dif,
            BigDecimal macd,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal volumeRatio,
            BigDecimal changePercent,
            BigDecimal closePosition,
            BigDecimal bodyDirection
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
            /** 週線 MA5（日K 收盤序列的 5 日 SMA，不是週K）；Task 356 起進入三軌各自的獨立權重。 */
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
            /** 走勢圖指標選單同一組值；Task 356 起進入三軌評分並在展開列顯示。 */
            ExtendedIndicators extendedIndicators,
            BigDecimal marketVolumeRatio,
            BigDecimal marketTurnoverRatio,
            String marketVolumeAsOfDate,
            BigDecimal nasdaqChangePercent,
            BigDecimal soxChangePercent,
            BigDecimal usTechCompositePercent,
            String usTechAsOfDate,
            boolean usTechAvailable,
            /**
             * 大盤自己那一份週K（Task 356.10c）：台股組由 {@code twse_index_daily_history}、
             * 美股組由 {@code us_index_daily_history} <b>各自</b>聚合，兩者數值不相同。
             *
             * <p>週K 缺值<b>不影響</b> {@code dataComplete}（Task 356.10a-2）——若把 weekly 併進
             * 完整性判定，完成週不足 60 的大盤會整組變 {@code DATA_INCOMPLETE}，
             * 連帶關掉<b>全部個股</b>的買進閘門。舊快照此欄為 {@code null}。</p>
             */
            WeeklyIndicators weeklyIndicators
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
                    extendedIndicators, null, null, null, null, null, null, null, false, null);
        }
    }

    /**
     * Task408 field-level technical source audit.  Values in the ordinary
     * indicator columns remain nullable business values; this record is the
     * companion evidence explaining exactly which provider facts, if any,
     * were allowed to affect a V18 input.
     */
    public record TechnicalProfileResolution(
            String profileId,
            String status,
            String reason,
            java.util.Map<String, Object> parameters,
            java.util.Map<String, String> payload,
            String sourceDate,
            String observedAt,
            String eligibility
    ) {}

    public record TechnicalFieldProvenance(
            String field,
            String origin,
            String profileId,
            String reason
    ) {}

    public record TechnicalResolution(
            String decisionInputVersion,
            String source,
            String binding,
            String contextFingerprint,
            String captureId,
            String oldestObservedAt,
            String freshUntil,
            Long ageSeconds,
            List<TechnicalProfileResolution> profiles,
            List<TechnicalFieldProvenance> fieldProvenance
    ) {}

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
             * 使用者於收合狀態看不出 K 已偏高。KD/J 因子本身已反映在三軌分數；
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
            /** 週線 MA5（日K 收盤序列的 5 日 SMA，不是週K；真正的週K 見 {@code weeklyIndicators}）。 */
            BigDecimal weeklyMa,
            /** ETF 折溢價（%）；非 ETF 為 null，畫面不得顯示為 0。 */
            BigDecimal etfPremiumPct,
            /** ETF 折溢價的自身歷史分位（0–100）；樣本不足或非 ETF 為 null。 */
            BigDecimal etfPremiumPercentile,
            /** 走勢圖指標選單同一組值；Task 356 起進入三軌評分並在展開列顯示。 */
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
            String etfPremiumLiveNavAsOf,
            // ─── Task 356.11b：1周~1月 軌與兩組新指標，一律追加在既有 62 個 component 之後 ───
            //
            // 既有欄位名一律不動：shortXxx 固定為「一周」軌、無前綴的 score／action／reasons／risks
            // 固定為「1月~6月」軌。四個既有消費端直接讀那兩組名稱
            // （trading_radar_notification_setting.last_action、Redis 快照 JSON、
            // TradingRadarExportService 的既有欄、docs/openapi 的 required 清單）。
            //
            // ⚠ 舊快照缺這 11 欄時 Jackson 一律給 null——本 record <b>沒有</b> compact constructor
            // 做正規化，故缺值的 List 欄位是 {@code null} 而<b>不是</b> {@code List.of()}。
            // 前端、匯出與 TradingRadarExportService 必須自行容忍 null，不得讀檔失敗（Task 356.11e）。
            /** 1周~1月 軌的最終動作（已過 evidence gate）。 */
            String swingAction,
            String swingActionLabel,
            Integer swingScore,
            List<String> swingReasons,
            List<String> swingRisks,
            Integer swingDownsideRisk,
            Integer swingEvidenceConfidence,
            Double swingRiskCoverage,
            String swingCandidateAction,
            /** 最新完成日的還原 K 棒與三分量（Task 356.5）。 */
            DailyCandle dailyCandle,
            /** 最新完成週的週K 棒與週K 指標（Task 356.6）；與 {@code weeklyMa} 是兩個不同的量。 */
            WeeklyIndicators weeklyIndicators,
            /** Task408 immutable source/field provenance; old snapshots are null (=LEGACY_LOCAL_V0). */
            TechnicalResolution technicalResolution
    ) {
        /**
         * Task408 compatibility shape.  Historical snapshots and established
         * unit fixtures predate the final technicalResolution component; their
         * missing value is intentionally preserved as null (= LEGACY_LOCAL_V0),
         * never synthesized as an apparent current Fubon decision.
         */
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
                BigDecimal etfPremiumPercentile, ExtendedIndicators extendedIndicators,
                String shortAction, String shortActionLabel, Integer shortScore,
                List<String> shortReasons, List<String> shortRisks, boolean horizonConflict,
                BigDecimal volumeRatio, String fxAsOfDate, boolean profitTakingConfirmed,
                FundamentalSnapshot fundamental, RadarEvidence evidence, Integer shortDownsideRisk,
                Integer mediumDownsideRisk, Integer shortEvidenceConfidence, Integer mediumEvidenceConfidence,
                Double shortRiskCoverage, Double mediumRiskCoverage, String candidateAction,
                String shortCandidateAction, List<String> actionGateReasons, BigDecimal etfPremiumLivePct,
                String etfPremiumLiveNavAsOf, String swingAction, String swingActionLabel, Integer swingScore,
                List<String> swingReasons, List<String> swingRisks, Integer swingDownsideRisk,
                Integer swingEvidenceConfidence, Double swingRiskCoverage, String swingCandidateAction,
                DailyCandle dailyCandle, WeeklyIndicators weeklyIndicators) {
            this(stockCode, stockName, market, assetClass, distributionAdjusted, held,
                    action, actionLabel, score, counterTrendState, counterTrendLabel,
                    counterTrendReasons, counterTrendRisks, dataComplete, price, changePercent,
                    quoteStatus, priceUpdatedAt, asOfDate, monthlyMa, quarterlyMa, annualMa,
                    kValue, dValue, monthlyConfirmation, quarterlyConfirmation, annualConfirmation,
                    fxPercentile, underlyingCurrency, reasons, risks, kdHeat, timingState, timingLabel,
                    ma60BiasPercent, week52Position, weeklyMa, etfPremiumPct, etfPremiumPercentile,
                    extendedIndicators, shortAction, shortActionLabel, shortScore, shortReasons, shortRisks,
                    horizonConflict, volumeRatio, fxAsOfDate, profitTakingConfirmed, fundamental, evidence,
                    shortDownsideRisk, mediumDownsideRisk, shortEvidenceConfidence, mediumEvidenceConfidence,
                    shortRiskCoverage, mediumRiskCoverage, candidateAction, shortCandidateAction,
                    actionGateReasons, etfPremiumLivePct, etfPremiumLiveNavAsOf, swingAction, swingActionLabel,
                    swingScore, swingReasons, swingRisks, swingDownsideRisk, swingEvidenceConfidence,
                    swingRiskCoverage, swingCandidateAction, dailyCandle, weeklyIndicators, null);
        }

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
                    null, null, // Task 320：etfPremiumLivePct／etfPremiumLiveNavAsOf
                    // Task 356.11b：既有相容建構式一律補新欄位的預設值，不得刪除該建構式。
                    null, null, null, List.of(), List.of(), null, null, null, null, null, null, null);
        }
    }
}
