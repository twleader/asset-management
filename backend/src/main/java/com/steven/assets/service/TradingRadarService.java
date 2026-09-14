package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.model.Stock;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.EtfNavObservationRepository;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.util.MarketZones;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 今日交易雷達（Requirement 43）資料組裝。
 *
 * <p>純讀 PostgreSQL／Redis；刻意不注入 MarketAnalysisService、新聞爬蟲或任何 AI client。</p>
 */
@Service
@Slf4j
public class TradingRadarService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";
    private static final String TAIEX_CODE = "0000";
    private static final String IXIC_CODE = "IXIC";

    private static final String TWD = "TWD";
    private static final Pattern STOCK_CODE_SELECTOR = Pattern.compile("^[A-Za-z0-9.\\-]{1,12}$");
    private static final Pattern MARKET_SELECTOR = Pattern.compile("^[\\p{L}0-9]{1,10}$");

    /**
     * ETF 折溢價分位的回看筆數與最小樣本數（Task 264）。
     *
     * <p><b>必須用自身歷史分位而非絕對值評分</b>：不同 ETF 的常態折溢價水準差異極大（台灣債券 ETF
     * 長期存在結構性溢價），用同一組絕對門檻套全部 ETF 會系統性誤判。絕對門檻另由規則引擎的硬否決處理。</p>
     *
     * <p><b>可用時程</b>：{@code etf_nav_history} 建表於 2026-07-19 且不回填，故本因子在上線後
     * 約 3 個月內對每一檔 ETF 恆為 {@code null}（由權重重分配吸收）；絕對硬否決不受此限、即刻生效。</p>
     */
    private static final int ETF_PREMIUM_LOOKBACK_DAYS = 250;
    /**
     * 個股與大盤日 K 的取數視窗（Task 356.4a）：約 100 個 ISO 週，扣掉 provenance／未來列剔除後
     * 仍足以取得 {@link RadarInputAssembler#MIN_COMPLETED_WEEKS} 根完成週。
     *
     * <p><b>擴窗只服務週K 聚合，日K 路徑一律仍由 {@link RadarObservationResolver#INDICATOR_SERIES_MAX_ROWS}
     * （241 完成列）界定</b>——凡是「沒有自帶列數上限」的既有計算都必須釘回那個視窗，
     * 否則會被長視窗靜默改值（見 {@code RadarInputAssembler.assemble} 的 {@code dailyContractRows}）。</p>
     */
    private static final int SERIES_FETCH_ROWS = 500;
    private static final int ETF_PREMIUM_MIN_SAMPLES = 60;

    private final TradingRadarRuleEngine ruleEngine;
    private final TechnicalIndicatorService indicatorService;
    private final DistributionAdjustedPriceService adjustedPriceService;
    /**
     * 把 OHLC 序列組成 {@code StockInput} 技術面欄位的<b>唯一擁有者</b>（Task 273 的 273.2b）。
     * 本服務與 {@code BacktestService} 共用它——回測若另寫一份，兩份會隨演進而分歧，
     * 屆時回測量到的是與線上不同的規則，且不會有任何報錯。
     */
    private final RadarInputAssembler assembler;
    private final AssetClassifier assetClassifier;
    private final TwseIndexDailyHistoryRepository twseRepo;
    /** IXIC（那斯達克綜合指數）美股大盤情境的資料來源（Task 294）；台股大盤沿用 twseRepo，兩者分表。 */
    private final UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final StockDividendHistoryRepository dividendHistoryRepo;
    private final PriceQueryService priceQueryService;
    private final TaiexDisplayPriceService taiexDisplayPriceService;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockAlertRepository alertRepo;
    private final StockRepository stockRepo;
    private final MarketDataService marketDataService;
    private final TradingRadarMarketContextService marketContextService;
    private final FundamentalAnalysisService fundamentalAnalysisService;
    private final EtfNavHistoryRepository etfNavHistoryRepo;
    private final EtfNavObservationRepository etfNavObservationRepository;
    private final TradingRadarSnapshotStore snapshotStore;
    private final CurrentUserContext currentUserContext;
    private final DividendEventEvidenceRepository dividendEventEvidenceRepository;
    private final TreasuryYieldService treasuryYieldService;
    private final TradingRadarMarketFeaturePort marketFeaturePort;
    private final BondYieldBetaEvidencePort bondYieldBetaEvidencePort;
    private final StockStyleThresholdProvider stockStyleThresholdProvider;
    /** Task408 source resolver; nullable only in legacy test constructors. */
    private final RadarTechnicalResolver radarTechnicalResolver;
    /** Settings-page-equivalent classification; nullable only in legacy test constructors. */
    private final TradingRadarSettingsClassificationResolver settingsClassificationResolver;
    /** Injected after compatibility constructors so legacy unit fixtures retain their old full path. */
    private TradingRadarListBatchPreloader listBatchPreloader;
    /** Compatibility constructors are used by legacy unit adapters; Spring production wiring is strict. */
    private final boolean strictCalendarMode;

    /** Compatibility constructor for existing unit tests/callers before typed V13 ports. */
    public TradingRadarService(
            TradingRadarRuleEngine ruleEngine,
            TechnicalIndicatorService indicatorService,
            DistributionAdjustedPriceService adjustedPriceService,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexDailyHistoryRepo,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            PriceQueryService priceQueryService,
            TaiexDisplayPriceService taiexDisplayPriceService,
            AssetSnapshotRepository snapshotRepo,
            StockAlertRepository alertRepo,
            StockRepository stockRepo,
            MarketDataService marketDataService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            EtfNavHistoryRepository etfNavHistoryRepo,
            TradingRadarSnapshotStore snapshotStore,
            CurrentUserContext currentUserContext,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            TreasuryYieldService treasuryYieldService) {
        this(ruleEngine, indicatorService, adjustedPriceService, assembler, assetClassifier,
                twseRepo, usIndexDailyHistoryRepo, priceHistoryRepo, dividendHistoryRepo,
                priceQueryService, taiexDisplayPriceService, snapshotRepo, alertRepo, stockRepo,
                marketDataService, marketContextService, fundamentalAnalysisService,
                etfNavHistoryRepo, snapshotStore, currentUserContext,
                dividendEventEvidenceRepository, treasuryYieldService, null, null, null);
    }

    public TradingRadarService(
            TradingRadarRuleEngine ruleEngine,
            TechnicalIndicatorService indicatorService,
            DistributionAdjustedPriceService adjustedPriceService,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexDailyHistoryRepo,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            PriceQueryService priceQueryService,
            TaiexDisplayPriceService taiexDisplayPriceService,
            AssetSnapshotRepository snapshotRepo,
            StockAlertRepository alertRepo,
            StockRepository stockRepo,
            MarketDataService marketDataService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            EtfNavHistoryRepository etfNavHistoryRepo,
            TradingRadarSnapshotStore snapshotStore,
            CurrentUserContext currentUserContext,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            TreasuryYieldService treasuryYieldService,
            TradingRadarMarketFeaturePort marketFeaturePort,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort) {
        this(ruleEngine, indicatorService, adjustedPriceService, assembler, assetClassifier,
                twseRepo, usIndexDailyHistoryRepo, priceHistoryRepo, dividendHistoryRepo,
                priceQueryService, taiexDisplayPriceService, snapshotRepo, alertRepo, stockRepo,
                marketDataService, marketContextService, fundamentalAnalysisService,
                etfNavHistoryRepo, snapshotStore, currentUserContext,
                dividendEventEvidenceRepository, treasuryYieldService, marketFeaturePort,
                bondYieldBetaEvidencePort, null, null);
    }

    public TradingRadarService(
            TradingRadarRuleEngine ruleEngine,
            TechnicalIndicatorService indicatorService,
            DistributionAdjustedPriceService adjustedPriceService,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexDailyHistoryRepo,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            PriceQueryService priceQueryService,
            TaiexDisplayPriceService taiexDisplayPriceService,
            AssetSnapshotRepository snapshotRepo,
            StockAlertRepository alertRepo,
            StockRepository stockRepo,
            MarketDataService marketDataService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            EtfNavHistoryRepository etfNavHistoryRepo,
            TradingRadarSnapshotStore snapshotStore,
            CurrentUserContext currentUserContext,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            TreasuryYieldService treasuryYieldService,
            TradingRadarMarketFeaturePort marketFeaturePort,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort,
            StockStyleThresholdProvider stockStyleThresholdProvider) {
        this(ruleEngine, indicatorService, adjustedPriceService, assembler, assetClassifier,
                twseRepo, usIndexDailyHistoryRepo, priceHistoryRepo, dividendHistoryRepo,
                priceQueryService, taiexDisplayPriceService, snapshotRepo, alertRepo, stockRepo,
                marketDataService, marketContextService, fundamentalAnalysisService,
                etfNavHistoryRepo, snapshotStore, currentUserContext,
                dividendEventEvidenceRepository, treasuryYieldService, marketFeaturePort,
                bondYieldBetaEvidencePort, stockStyleThresholdProvider, null, null, null);
    }

    /** Compatibility overload retained for tests/adapters that predate Task408. */
    public TradingRadarService(
            TradingRadarRuleEngine ruleEngine,
            TechnicalIndicatorService indicatorService,
            DistributionAdjustedPriceService adjustedPriceService,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexDailyHistoryRepo,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            PriceQueryService priceQueryService,
            TaiexDisplayPriceService taiexDisplayPriceService,
            AssetSnapshotRepository snapshotRepo,
            StockAlertRepository alertRepo,
            StockRepository stockRepo,
            MarketDataService marketDataService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            EtfNavHistoryRepository etfNavHistoryRepo,
            TradingRadarSnapshotStore snapshotStore,
            CurrentUserContext currentUserContext,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            TreasuryYieldService treasuryYieldService,
            TradingRadarMarketFeaturePort marketFeaturePort,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort,
            StockStyleThresholdProvider stockStyleThresholdProvider,
            EtfNavObservationRepository etfNavObservationRepository) {
        this(ruleEngine, indicatorService, adjustedPriceService, assembler, assetClassifier,
                twseRepo, usIndexDailyHistoryRepo, priceHistoryRepo, dividendHistoryRepo,
                priceQueryService, taiexDisplayPriceService, snapshotRepo, alertRepo, stockRepo,
                marketDataService, marketContextService, fundamentalAnalysisService, etfNavHistoryRepo,
                snapshotStore, currentUserContext, dividendEventEvidenceRepository, treasuryYieldService,
                marketFeaturePort, bondYieldBetaEvidencePort, stockStyleThresholdProvider,
                etfNavObservationRepository, null, null);
    }

    @Autowired
    public TradingRadarService(
            TradingRadarRuleEngine ruleEngine,
            TechnicalIndicatorService indicatorService,
            DistributionAdjustedPriceService adjustedPriceService,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexDailyHistoryRepo,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            PriceQueryService priceQueryService,
            TaiexDisplayPriceService taiexDisplayPriceService,
            AssetSnapshotRepository snapshotRepo,
            StockAlertRepository alertRepo,
            StockRepository stockRepo,
            MarketDataService marketDataService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            EtfNavHistoryRepository etfNavHistoryRepo,
            TradingRadarSnapshotStore snapshotStore,
            CurrentUserContext currentUserContext,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            TreasuryYieldService treasuryYieldService,
            TradingRadarMarketFeaturePort marketFeaturePort,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort,
            StockStyleThresholdProvider stockStyleThresholdProvider,
            EtfNavObservationRepository etfNavObservationRepository,
            RadarTechnicalResolver radarTechnicalResolver,
            TradingRadarSettingsClassificationResolver settingsClassificationResolver) {
        this.ruleEngine = ruleEngine;
        this.indicatorService = indicatorService;
        this.adjustedPriceService = adjustedPriceService;
        this.assembler = assembler;
        this.assetClassifier = assetClassifier;
        this.twseRepo = twseRepo;
        this.usIndexDailyHistoryRepo = usIndexDailyHistoryRepo;
        this.priceHistoryRepo = priceHistoryRepo;
        this.dividendHistoryRepo = dividendHistoryRepo;
        this.priceQueryService = priceQueryService;
        this.taiexDisplayPriceService = taiexDisplayPriceService;
        this.snapshotRepo = snapshotRepo;
        this.alertRepo = alertRepo;
        this.stockRepo = stockRepo;
        this.marketDataService = marketDataService;
        this.marketContextService = marketContextService;
        this.fundamentalAnalysisService = fundamentalAnalysisService;
        this.etfNavHistoryRepo = etfNavHistoryRepo;
        this.snapshotStore = snapshotStore;
        this.currentUserContext = currentUserContext;
        this.dividendEventEvidenceRepository = dividendEventEvidenceRepository;
        this.treasuryYieldService = treasuryYieldService;
        this.marketFeaturePort = marketFeaturePort;
        this.bondYieldBetaEvidencePort = bondYieldBetaEvidencePort;
        this.stockStyleThresholdProvider = stockStyleThresholdProvider;
        this.etfNavObservationRepository = etfNavObservationRepository;
        this.radarTechnicalResolver = radarTechnicalResolver;
        this.settingsClassificationResolver = settingsClassificationResolver;
        this.strictCalendarMode = etfNavObservationRepository != null
                || marketFeaturePort != null || bondYieldBetaEvidencePort != null
                || stockStyleThresholdProvider != null;
    }

    /** @param stale 大盤最新完成日 K 不是當前台股交易日（Task 217.1）。 */
    private record MarketState(
            TradingRadarDto.MarketSummary summary,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale
    ) {}

    /** ETF 折溢價的日期／來源 provenance；stale observation 不進規則因子。 */
    private record PremiumObservation(
            BigDecimal value,
            LocalDate asOfDate,
            String source,
            boolean stale,
            List<BigDecimal> history
    ) {
        static PremiumObservation unavailable(boolean stale) {
            return new PremiumObservation(null, null, null, stale, List.of());
        }
    }

    private BigDecimal stockStyleIncomeThreshold() {
        return stockStyleThresholdProvider == null
                ? AssetClassifier.defaultDividendThreshold()
                : stockStyleThresholdProvider.incomeThreshold();
    }

    /**
     * 通知路徑用的大盤快照（Task 302）：{@link #buildMarketSnapshot} 每輪每個市場只組一次，
     * 供 {@code TradingRadarNotificationService#flushEvaluations} 批次共用，取代逐檔各自
     * 重建大盤。除了 regime/stale/decision time，也保留同一輪完成日的完整 summary；通知個股的
     * evidence terminal gate 必須使用這份 summary，不能因批次快照而退回 {@code null}。
     */
    public record MarketSnapshot(
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale,
            Instant decisionInstant,
            TradingRadarDto.MarketSummary summary,
            RadarObservationResolver.DecisionSessions decisionSessions,
            boolean authoritativeCalendar,
            RadarObservationResolver.DecisionSessions usDecisionSessions,
            boolean usAuthoritativeCalendar
    ) {
        /** Compatibility constructor for callers that only supplied regime/staleness. */
        public MarketSnapshot(
                TradingRadarRuleEngine.MarketRegime regime,
                boolean stale,
                Instant decisionInstant) {
            this(regime, stale, decisionInstant, null, null, false, null, false);
        }

        /** Compatibility constructor for callers that supplied a summary only. */
        public MarketSnapshot(
                TradingRadarRuleEngine.MarketRegime regime,
                boolean stale,
                Instant decisionInstant,
                TradingRadarDto.MarketSummary summary) {
            this(regime, stale, decisionInstant, summary, null, false, null, false);
        }
    }

    /** One immutable session-clock result shared by accepted price/live-row/premium paths. */
    private record DecisionClock(
            RadarObservationResolver.DecisionSessions sessions,
            boolean authoritative) {}

    private record Target(String code, String market, boolean held) {}

    @Autowired
    void setListBatchPreloader(TradingRadarListBatchPreloader listBatchPreloader) {
        this.listBatchPreloader = listBatchPreloader;
    }

    /**
     * Shared rule-evaluation result.  It is deliberately an internal calculation object, not an
     * HTTP detail DTO: list callers project only table scalars while full/detail callers create a
     * {@link TradingRadarDto.StockDecision} at the final boundary.
     */
    record DecisionCore(
            Target target,
            String name,
            String assetClass,
            TradingRadarDto.SettingsClassification settingsClassification,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            RadarInputAssembler.Assembled technical,
            ResolvedTechnicalInputs resolvedTechnical,
            RadarObservationResolver.AcceptedPrice acceptedPrice,
            TradingRadarRuleEngine.StockResult result,
            TradingRadarEvidenceGate.GatedActions gated,
            TradingRadarEvidenceConfidenceResolver.Evidence evidence,
            FundamentalAnalysisService.Resolved fundamental,
            DividendEventEvidenceResolver.Resolution distribution,
            TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation,
            PremiumObservation premium,
            BigDecimal displayPrice,
            BigDecimal displayChangePercent,
            String quoteStatus,
            String updatedAt,
            String asOf,
            TechnicalIndicatorService.FullIndicators indicators,
            TradingRadarRuleEngine.Confirmation c20,
            TradingRadarRuleEngine.Confirmation c60,
            TradingRadarRuleEngine.Confirmation c240,
            BigDecimal fxPercentile,
            String currency,
            String fxAsOfDate,
            BigDecimal ma60Bias,
            BigDecimal week52Position,
            BigDecimal etfPremiumPct,
            BigDecimal etfPremiumPercentile,
            BigDecimal etfPremiumLivePct,
            String etfPremiumLiveNavAsOf,
            List<String> reasons,
            List<String> risks,
            List<String> shortReasons,
            List<String> shortRisks,
            List<String> swingReasons,
            List<String> swingRisks,
            String failureMessage) {
        static DecisionCore failed(Target target, String name, String assetClass,
                                   TradingRadarDto.SettingsClassification settingsClassification,
                                   String message) {
            return new DecisionCore(target, name, assetClass, settingsClassification,
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, "CLOSE_PENDING", null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null,
                    List.of(), List.of(message), List.of(), List.of(message), List.of(), List.of(message), message);
        }
    }

    /**
     * 當前台股交易日：今天是交易日就取今天，否則往回找最近一個交易日。
     * 用於判斷大盤資料是否停在更早的交易日；沿用既有交易日曆，不自建假日表。
     */
    private LocalDate currentTwTradingDay(Instant decisionInstant) {
        LocalDate day = decisionInstant.atZone(TAIPEI).toLocalDate();
        for (int i = 0; i < 14; i++) {
            if (marketDataService.isTradingDay(TW_MARKET, day)) return day;
            day = day.minusDays(1);
        }
        return day;
    }

    /** Returns null rather than refreshing the holiday proxy when the cache cannot prove a day. */
    private LocalDate currentTwTradingDayCachedOnly(Instant decisionInstant) {
        if (decisionInstant == null || marketDataService == null) return null;
        LocalDate day = decisionInstant.atZone(TAIPEI).toLocalDate();
        for (int i = 0; i < 14; i++) {
            Optional<Boolean> known = marketDataService.isTwTradingDayCachedOnly(day);
            if (known == null || known.isEmpty()) return null;
            if (known.get()) return day;
            day = day.minusDays(1);
        }
        return null;
    }

    /**
     * 已完成（收盤時刻已過）的最近一個美股交易日，供 {@link #buildUsMarket} 判斷 IXIC 資料是否 stale
     * （Task 294.2）。IXIC 大盤不像台股組有 Redis 即時價可退回判斷（刻意不併入即時價，見 294.1 背景），
     * 故改直接用美東收盤時刻界定「已完成」：收盤前，今天尚不能算數，須往前一個交易日找。
     *
     * <p>Task 332：實作已提升為 {@link MarketDataService#mostRecentCompletedUsTradingDay(Instant)}，
     * 與 {@link IndexDailyRefreshScheduler} 的回補判準同源；本方法只做委派、行為不變。</p>
     */
    private LocalDate mostRecentCompletedUsTradingDay(Instant decisionInstant) {
        return marketDataService.mostRecentCompletedUsTradingDay(decisionInstant);
    }

    @Transactional(readOnly = true)
    public TradingRadarDto.Response get() {
        TradingRadarDto.Response response = assemble(null);

        // Requirement 48：每次頁面計算把結果存為 per-owner Redis 快照供匯出（fail-soft、僅登入的 HTTP 請求）。
        try {
            if (RequestContextHolder.getRequestAttributes() != null && currentUserContext.hasUser()) {
                snapshotStore.save(currentUserContext.getEffectiveUserId(), response);
            }
        } catch (Exception e) {
            log.warn("交易雷達快照寫入失敗（不影響頁面）：{}", e.toString());
        }
        return response;
    }

    /**
     * 公開「今日交易雷達」的純 current read（Requirement 86 / Task 347）。
     *
     * <p>與頁面用 {@link #get()} 共用完全相同的 request-scoped owner 查詢與 DTO 組裝，
     * 但外部輪詢不得推進匯出快照時間軸，因此這個入口刻意只呼叫 {@code assemble(null)}，
     * 不保存 Redis snapshot，也不觸發 refresh、通知或匯出。</p>
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.Response getCurrent() {
        return assemble(null);
    }

    /**
     * Authenticated browser first-screen read.  This path deliberately owns its compact
     * projection and never delegates to get(), getCurrent(), or the full response assembler.
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.ListResponse getList() {
        Instant decisionInstant = Instant.now();
        TradingRadarMarketContextService.Resolved context = marketContextService.resolve(decisionInstant);
        // First-screen rendering uses only a pre-existing calendar snapshot.
        // A miss remains incomplete/stale and must never refresh the holiday proxy.
        MarketState twMarket = buildMarket(context.market(), decisionInstant, true);
        MarketState usMarket = buildUsMarket(decisionInstant);
        Map<String, DecisionClock> decisionClocks = resolveListDecisionClocks(decisionInstant);
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> skippedNonTw = new HashSet<>();
        loadLatestHoldings(targets, skippedNonTw, null);
        loadWatchList(targets, skippedNonTw, null);
        List<Target> eligibleTargets = targets.values().stream().filter(this::isSupportedTarget).toList();
        RadarTechnicalResolver.Batch technicalBatch = preloadListTechnical(eligibleTargets, decisionInstant);
        Map<String, List<LocalDate>> futureSessionsByMarket = new LinkedHashMap<>();
        for (String market : eligibleTargets.stream().map(Target::market)
                .distinct().toList()) {
            futureSessionsByMarket.put(market, futureSessionsCachedOnly(market, decisionInstant));
        }
        Map<String, TradingRadarMarketFeatureResolver.ExpectedSessions> expectedMarketSessions =
                listExpectedMarketSessions(decisionClocks);
        Map<String, LocalDate> premiumTargetDates = listPremiumTargetDatesCachedOnly(eligibleTargets, decisionInstant,
                decisionClocks);
        TradingRadarListBatchPreloader.Context listInputs = preloadListInputs(eligibleTargets, decisionInstant,
                futureSessionsByMarket, expectedMarketSessions, premiumTargetDates);
        Map<String, TradingRadarMarketContextService.FxContext> fxCache = new java.util.concurrent.ConcurrentHashMap<>();
        List<TradingRadarDto.ListStock> stocks = eligibleTargets.stream()
                .map(target -> safeListStock(target, () -> buildDecisionCore(target,
                        regimeFor(target.market(), twMarket, usMarket),
                        staleFor(target.market(), twMarket, usMarket),
                        marketSummaryFor(target.market(), twMarket, usMarket), decisionInstant, fxCache,
                        decisionClocks.getOrDefault(target.market(), new DecisionClock(null, false)),
                        decisionClocks.getOrDefault(US_MARKET, new DecisionClock(null, false)), technicalBatch,
                        false, listInputs.entry(target.code(), target.market()))))
                .sorted(Comparator.comparing(TradingRadarService::bestListScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TradingRadarDto.ListStock::stockCode))
                .toList();
        return new TradingRadarDto.ListResponse(TradingRadarRuleEngine.RULE_VERSION,
                TradingRadarEvidenceGate.ACTION_POLICY_VERSION,
                decisionInstant.atZone(TAIPEI).toOffsetDateTime().toString(), twMarket.summary(),
                usMarket.summary(), stocks, skippedNonTw.size(), context.publicInformation());
    }

    /** Lazy detail read for exactly one current-user target; it never assembles every target first. */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDetailResponse getStockDetail(String rawStockCode, String rawMarket) {
        String stockCode = normalizeSelector(rawStockCode, STOCK_CODE_SELECTOR, "股票代號格式不合法");
        String market = normalizeSelector(rawMarket, MARKET_SELECTOR, "市場別格式不合法");
        Instant decisionInstant = Instant.now();
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> skipped = new HashSet<>();
        loadLatestHoldings(targets, skipped, null);
        loadWatchList(targets, skipped, null);
        Target target = targets.get(stockCode + '\0' + market);
        if (target == null || !isSupportedTarget(target)) {
            throw new IllegalArgumentException("找不到可分析標的");
        }

        TradingRadarMarketContextService.Resolved context = marketContextService.resolve(decisionInstant);
        MarketState twMarket = TW_MARKET.equals(target.market())
                ? buildMarket(context.market(), decisionInstant) : incompleteMarket("台股大盤未讀取");
        MarketState usMarket = US_MARKET.equals(target.market())
                ? buildUsMarket(decisionInstant) : incompleteMarket("美股大盤未讀取");
        Map<String, DecisionClock> clocks = resolveDecisionClocks(decisionInstant);
        RadarTechnicalResolver.Batch technicalBatch = preloadTaiwanTechnical(List.of(target), decisionInstant);
        DecisionCore core = buildDecisionCore(target, regimeFor(target.market(), twMarket, usMarket),
                staleFor(target.market(), twMarket, usMarket), marketSummaryFor(target.market(), twMarket, usMarket),
                decisionInstant, new java.util.HashMap<>(),
                clocks.getOrDefault(target.market(), new DecisionClock(null, false)),
                clocks.getOrDefault(US_MARKET, new DecisionClock(null, false)), technicalBatch, true);
        return new TradingRadarDto.StockDetailResponse(TradingRadarRuleEngine.RULE_VERSION,
                TradingRadarEvidenceGate.ACTION_POLICY_VERSION,
                decisionInstant.atZone(TAIPEI).toOffsetDateTime().toString(), toFullDecision(core));
    }

    /** List-only preload includes the non-TW local cache through one market/code MGET. */
    private RadarTechnicalResolver.Batch preloadListTechnical(
            java.util.Collection<Target> targets, Instant decisionInstant) {
        if (radarTechnicalResolver == null) return null;
        java.util.Collection<Target> safeTargets = targets == null ? List.of() : targets;
        Set<String> taiwanCodes = safeTargets.stream()
                .filter(target -> target != null && TW_MARKET.equals(target.market()))
                .map(Target::code).collect(java.util.stream.Collectors.toSet());
        List<RadarTechnicalCachePort.MarketLocalKey> marketLocalKeys = safeTargets.stream()
                .filter(target -> target != null && isSupportedMarket(target.market())
                        && !TW_MARKET.equals(target.market()))
                .map(target -> new RadarTechnicalCachePort.MarketLocalKey(target.market(), target.code()))
                .toList();
        try {
            RadarTechnicalResolver.Batch batch = radarTechnicalResolver.preload(taiwanCodes, marketLocalKeys, decisionInstant);
            if (batch != null) return batch;
            log.warn("交易雷達清單批次不可得：phase=technical-preload, result=null");
        } catch (RuntimeException unavailable) {
            logListBatchUnavailable("technical-preload", unavailable);
        }
        return RadarTechnicalResolver.Batch.unavailable(!taiwanCodes.isEmpty(), !marketLocalKeys.isEmpty());
    }

    /**
     * The compact route may not translate a failed batch into a null context: doing so would
     * reopen per-target readers or let a single missing map entry collapse the owner list.
     */
    private TradingRadarListBatchPreloader.Context preloadListInputs(
            Collection<Target> targets, Instant decisionInstant,
            Map<String, List<LocalDate>> futureSessionsByMarket,
            Map<String, TradingRadarMarketFeatureResolver.ExpectedSessions> expectedMarketSessions,
            Map<String, LocalDate> premiumTargetDates) {
        List<TradingRadarListBatchPreloader.Target> batchTargets = (targets == null ? List.<Target>of() : targets).stream()
                .filter(this::isSupportedTarget)
                .map(target -> new TradingRadarListBatchPreloader.Target(
                        target.code(), target.market(), target.held())).toList();
        if (batchTargets.isEmpty()) return new TradingRadarListBatchPreloader.Context(Map.of());
        TradingRadarListBatchPreloader.Context source = null;
        if (listBatchPreloader == null) {
            log.warn("交易雷達清單批次不可得：phase=list-input-preload, result=not-injected");
        } else {
            try {
                source = listBatchPreloader.preload(batchTargets, decisionInstant, futureSessionsByMarket,
                        expectedMarketSessions, premiumTargetDates, SERIES_FETCH_ROWS);
                if (source == null) {
                    log.warn("交易雷達清單批次不可得：phase=list-input-preload, result=null");
                }
            } catch (RuntimeException unavailable) {
                logListBatchUnavailable("list-input-preload", unavailable);
            }
        }
        return TradingRadarListBatchPreloader.Context.complete(batchTargets, source, decisionInstant);
    }

    /** Detail remains a one-target path; only its pre-existing Taiwan pair preload applies. */
    private RadarTechnicalResolver.Batch preloadTaiwanTechnical(
            java.util.Collection<Target> targets, Instant decisionInstant) {
        if (radarTechnicalResolver == null) return null;
        Set<String> taiwanCodes = (targets == null ? java.util.stream.Stream.<Target>empty() : targets.stream())
                .filter(target -> target != null && TW_MARKET.equals(target.market()))
                .map(Target::code).collect(java.util.stream.Collectors.toSet());
        return radarTechnicalResolver.preload(taiwanCodes, decisionInstant);
    }

    private boolean isSupportedTarget(Target target) {
        return target != null && (TW_MARKET.equals(target.market()) || US_MARKET.equals(target.market()))
                && !isTaiwanMarketIndex(target.code(), target.market());
    }

    private boolean isSupportedMarket(String market) {
        return TW_MARKET.equals(market) || US_MARKET.equals(market);
    }

    private static String normalizeSelector(String raw, Pattern pattern, String message) {
        if (raw == null) throw new IllegalArgumentException(message);
        String normalized = raw.trim();
        if (normalized.isEmpty() || !pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    /**
     * 背景產檔前的重算（Task 260）：顯式 owner、不觸碰 request-scoped 的 CurrentUserContext，
     * 重算後 append 一筆快照供當日匯出。
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.Response recomputeAndStoreForOwner(long ownerId) {
        TradingRadarDto.Response response = assemble(ownerId);
        snapshotStore.saveRecomputed(ownerId, response);
        return response;
    }

    /**
     * 今日交易雷達的資料組裝主體（Task 260 從 {@code get()} 抽出）。
     *
     * @param ownerId {@code null} 代表走 request-scoped {@code ownerFilter}（HTTP 路徑，
     *                無 owner 查詢方法）；非 null 代表顯式 owner 查詢（背景路徑，owner-scoped
     *                查詢方法），不得依賴 {@link CurrentUserContext}。
     */
    private TradingRadarDto.Response assemble(Long ownerId) {
        return assemble(ownerId, Instant.now());
    }

    /** Explicit-time assembly hook for deterministic replays and unit tests. */
    TradingRadarDto.Response assembleAt(Instant decisionInstant) {
        if (decisionInstant == null) throw new IllegalArgumentException("decisionInstant 不得為空");
        return assemble(null, decisionInstant);
    }

    private TradingRadarDto.Response assemble(Long ownerId, Instant decisionInstant) {
        TradingRadarMarketContextService.Resolved context = marketContextService.resolve(decisionInstant);
        MarketState twMarket = buildMarket(context.market(), decisionInstant);
        // 不論本輪有沒有美股標的都計算（比照台股組現行行為），維持「大盤資料與個股清單解耦」的既有設計。
        MarketState usMarket = buildUsMarket(decisionInstant);
        Map<String, DecisionClock> decisionClocks = resolveDecisionClocks(decisionInstant);
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> skippedNonTw = new HashSet<>();
        loadLatestHoldings(targets, skippedNonTw, ownerId);
        loadWatchList(targets, skippedNonTw, ownerId);

        // Task408: one request-bounded cache/DB read for the whole Taiwan
        // target set.  `buildStock` receives the immutable batch so it cannot
        // degenerate into one capture query per stock.
        RadarTechnicalResolver.Batch technicalBatch = radarTechnicalResolver == null ? null
                : radarTechnicalResolver.preload(targets.values().stream()
                        .filter(target -> TW_MARKET.equals(target.market()))
                        .map(Target::code).collect(java.util.stream.Collectors.toSet()), decisionInstant);

        // Task 303：同一次 assemble() 內同幣別的 FxContext 只解析一次（20 檔美股原本各自重查 5 年
        // 匯率）。stream 目前循序執行，但用 ConcurrentHashMap 防未來並行化踩雷。
        Map<String, TradingRadarMarketContextService.FxContext> fxCache = new java.util.concurrent.ConcurrentHashMap<>();
        List<TradingRadarDto.StockDecision> decisions = targets.values().stream()
                .filter(t -> (TW_MARKET.equals(t.market()) || US_MARKET.equals(t.market()))
                        && !isTaiwanMarketIndex(t.code(), t.market()))
                .map(t -> toFullDecision(buildDecisionCore(t, regimeFor(t.market(), twMarket, usMarket),
                        staleFor(t.market(), twMarket, usMarket),
                        marketSummaryFor(t.market(), twMarket, usMarket), decisionInstant, fxCache,
                        decisionClocks.getOrDefault(t.market(), new DecisionClock(null, false)),
                        decisionClocks.getOrDefault(US_MARKET, new DecisionClock(null, false)), technicalBatch,
                        true)))
                .sorted(Comparator
                        .comparing(TradingRadarService::bestScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TradingRadarDto.StockDecision::stockCode))
                .toList();

        return new TradingRadarDto.Response(
                TradingRadarRuleEngine.RULE_VERSION,
                TradingRadarEvidenceGate.ACTION_POLICY_VERSION,
                decisionInstant.atZone(TAIPEI).toOffsetDateTime().toString(),
                twMarket.summary(),
                // Task 335：直接帶上面那個已算出的 usMarket，嚴禁在此重呼叫 buildUsMarket() 或
                // 另做任何 repository／indicator／evaluateMarket() 查詢——兩次計算之間 Redis／DB
                // 狀態可能改變，會讓「美股個股評分依據的 regime」與「畫面顯示的 regime」對不上。
                usMarket.summary(),
                decisions,
                skippedNonTw.size(),
                context.publicInformation());
    }

    /** 依標的市場選對應的大盤組別（Task 294.3／294.6）；不得用同一個變數餵給兩種市場的股票。 */
    private TradingRadarRuleEngine.MarketRegime regimeFor(
            String market, MarketState twMarket, MarketState usMarket) {
        return US_MARKET.equals(market) ? usMarket.regime() : twMarket.regime();
    }

    private TradingRadarDto.MarketSummary marketSummaryFor(
            String market, MarketState twMarket, MarketState usMarket) {
        return US_MARKET.equals(market) ? usMarket.summary() : twMarket.summary();
    }

    private boolean staleFor(String market, MarketState twMarket, MarketState usMarket) {
        return US_MARKET.equals(market) ? usMarket.stale() : twMarket.stale();
    }

    private Map<String, DecisionClock> resolveDecisionClocks(Instant decisionInstant) {
        Map<String, DecisionClock> clocks = new LinkedHashMap<>();
        if (!strictCalendarMode) {
            clocks.put(TW_MARKET, new DecisionClock(null, false));
            clocks.put(US_MARKET, new DecisionClock(null, false));
            return clocks;
        }
        for (String market : List.of(TW_MARKET, US_MARKET)) {
            try {
                Optional<RadarObservationResolver.DecisionSessions> resolved =
                        marketContextService.resolveDecisionSessions(market, decisionInstant);
                // null is reserved for old test/adapters that do not implement
                // the strict API.  A real Optional.empty is an authoritative
                // calendar outage and remains fail-closed.
                clocks.put(market, resolved == null
                        ? new DecisionClock(null, false)
                        : new DecisionClock(resolved.orElse(null), true));
            } catch (RuntimeException unavailable) {
                log.warn("交易雷達：{} session clock 解析失敗：{}", market, unavailable.getMessage());
                clocks.put(market, new DecisionClock(null, true));
            }
        }
        return clocks;
    }

    /**
     * A list request always uses a strict locally-cached clock.  Calendar
     * unknown is terminal so the evaluator cannot fall back to the legacy
     * external-refreshing trading-day predicate.
     */
    private Map<String, DecisionClock> resolveListDecisionClocks(Instant decisionInstant) {
        Map<String, DecisionClock> clocks = new LinkedHashMap<>();
        for (String market : List.of(TW_MARKET, US_MARKET)) {
            try {
                Optional<RadarObservationResolver.DecisionSessions> resolved =
                        marketContextService.resolveDecisionSessionsCachedOnly(market, decisionInstant);
                clocks.put(market, new DecisionClock(resolved == null ? null : resolved.orElse(null), true));
            } catch (RuntimeException unavailable) {
                log.warn("交易雷達清單：{} 唯讀 session clock 解析失敗：{}", market, unavailable.getMessage());
                clocks.put(market, new DecisionClock(null, true));
            }
        }
        return Map.copyOf(clocks);
    }

    /** Shared strict terminal sessions for one list request; no evaluator may reload them. */
    private Map<String, TradingRadarMarketFeatureResolver.ExpectedSessions> listExpectedMarketSessions(
            Map<String, DecisionClock> clocks) {
        DecisionClock us = clocks == null ? null : clocks.get(US_MARKET);
        Map<String, TradingRadarMarketFeatureResolver.ExpectedSessions> out = new LinkedHashMap<>();
        for (String market : List.of(TW_MARKET, US_MARKET)) {
            DecisionClock own = clocks == null ? null : clocks.get(market);
            boolean strict = own != null && own.authoritative() || us != null && us.authoritative();
            out.put(market, strict ? TradingRadarMarketFeatureResolver.ExpectedSessions.strict(
                    own == null || !own.authoritative() ? null : sessionDate(own),
                    us == null || !us.authoritative() ? null : sessionDate(us),
                    us == null || !us.authoritative() ? null : sessionDate(us))
                    : TradingRadarMarketFeatureResolver.ExpectedSessions.none());
        }
        return Map.copyOf(out);
    }

    /** List counterpart that never refreshes a Taiwan calendar after a cache miss. */
    private Map<String, LocalDate> listPremiumTargetDatesCachedOnly(
            Collection<Target> targets, Instant decisionInstant, Map<String, DecisionClock> clocks) {
        if (targets == null || targets.stream().noneMatch(target -> TW_MARKET.equals(target.market()))) return Map.of();
        DecisionClock tw = clocks == null ? null : clocks.get(TW_MARKET);
        LocalDate date = tw != null && tw.authoritative()
                ? sessionDate(tw) : currentTwTradingDayCachedOnly(decisionInstant);
        return date == null ? Map.of() : Map.of(TW_MARKET, date);
    }

    /**
     * 背景通知評估共用同一份 V11 組裝；通知狀態仍只追蹤中期 action。
     *
     * <p>依傳入的 {@code market} 只組裝對應的那一組大盤（Task 294.6），確保背景通知評估與前景頁面
     * 對同一檔美股股票算出一致的結果——不像 {@link #assemble} 兩組都要（供其餘標的使用），
     * 這裡只評估單一標的，另一組大盤用不到。</p>
     *
     * <p>委派 {@link #buildMarketSnapshot} ＋ 四參數 overload（Task 302）：本方法每次呼叫仍各自
     * 重建一次大盤，供單檔呼叫端（如手動觸發、單檔測試）使用；批次省重算靠呼叫端（見
     * {@code TradingRadarNotificationService#flushEvaluations}）自行對每個市場只組一次
     * {@link MarketSnapshot} 後改呼叫四參數版本。</p>
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDecision evaluateForNotification(
            String stockCode, String market, boolean held) {
        return evaluateForNotification(stockCode, market, held, buildMarketSnapshot(market));
    }

    /**
     * 批次版（Task 302）：呼叫端已組好 {@link MarketSnapshot}（每輪每市場一次），本方法只負責
     * 組單一標的的個股決策，不再重建大盤。
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDecision evaluateForNotification(
            String stockCode, String market, boolean held, MarketSnapshot snapshot) {
        RadarTechnicalResolver.Batch technicalBatch = radarTechnicalResolver == null || !TW_MARKET.equals(market)
                ? null : radarTechnicalResolver.preload(Set.of(stockCode), snapshot.decisionInstant());
        return toFullDecision(buildDecisionCore(new Target(stockCode, market, held), snapshot.regime(), snapshot.stale(),
                snapshot.summary(), snapshot.decisionInstant(), new java.util.HashMap<>(),
                new DecisionClock(snapshot.decisionSessions(), snapshot.authoritativeCalendar()),
                new DecisionClock(snapshot.usDecisionSessions(), snapshot.usAuthoritativeCalendar()), technicalBatch,
                true));
    }

    /**
     * 通知路徑的大盤快照組裝入口（Task 302）：依 {@code market} 組對應的那一組大盤，供呼叫端
     * 對每輪出現的每個市場只呼叫一次，取代逐檔 {@code evaluateForNotification} 各自重建大盤的
     * 既有浪費。台股改走 bounded 的 {@link TradingRadarMarketContextService#resolveMarket}
     * （不再抓新聞，見該方法）；美股維持既有 IXIC 組裝。{@link #buildMarket}／{@link #buildUsMarket}
     * 既有 catch 保證失敗回 {@code DATA_INCOMPLETE + stale=true} 而非拋出，故本方法本身不需要
     * 額外 try/catch——呼叫端（{@code flushEvaluations} 的 {@code computeIfAbsent}）位於逐檔
     * catch 之外，不容許例外逸出。
     */
    @Transactional(readOnly = true)
    public MarketSnapshot buildMarketSnapshot(String market) {
        Instant now = Instant.now();
        MarketState marketState;
        if (US_MARKET.equals(market)) {
            marketState = buildUsMarket(now);
        } else {
            TradingRadarMarketContextService.MarketContext context = marketContextService.resolveMarket(now);
            marketState = buildMarket(context, now);
        }
        java.util.Optional<RadarObservationResolver.DecisionSessions> sessions =
                strictCalendarMode ? marketContextService.resolveDecisionSessions(market, now) : null;
        // A null Optional means a legacy compatibility adapter that predates the
        // strict session API; production implementations return Optional.empty
        // for an unavailable calendar and must fail closed.
        boolean authoritative = sessions != null;
        java.util.Optional<RadarObservationResolver.DecisionSessions> usSessions =
                strictCalendarMode ? marketContextService.resolveDecisionSessions(US_MARKET, now) : null;
        boolean usAuthoritative = usSessions != null;
        return new MarketSnapshot(marketState.regime(), marketState.stale(), now, marketState.summary(),
                sessions == null ? null : sessions.orElse(null), authoritative,
                usSessions == null ? null : usSessions.orElse(null), usAuthoritative);
    }

    private MarketState buildMarket(
            TradingRadarMarketContextService.MarketContext context,
            Instant decisionInstant) {
        return buildMarket(context, decisionInstant, false);
    }

    /** Builds the Taiwan market summary; list callers pass cacheOnlyCalendar=true. */
    private MarketState buildMarket(
            TradingRadarMarketContextService.MarketContext context,
            Instant decisionInstant,
            boolean cacheOnlyCalendar) {
        try {
            // Task 356.4a：查詢與 as-of 截斷<b>兩處都</b>放大到 500——只改查詢那個等於沒改，
            // 序列仍會被第二個 limit 截回 241 列 ≈ 48 個 ISO 週 < 60 根完成週，
            // 台股大盤的週K 因子會永遠缺值，畫面只多一則「不採計」風險句、沒有任何錯誤訊息。
            List<TwseIndexDailyHistory> rows = twseRepo.findTopNByOrderByTradingDateDesc(SERIES_FETCH_ROWS);
            if (context.marketAsOfDate() != null) {
                rows = rows.stream()
                        .filter(r -> !r.getTradingDate().isAfter(context.marketAsOfDate()))
                        .limit(SERIES_FETCH_ROWS)
                        .toList();
            }
            // 既有日K 路徑一律只吃最新 241 列，長清單只供週K 聚合（Task 356.4b）。
            List<TwseIndexDailyHistory> contractRows = rows.size() <= RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS
                    ? rows
                    : rows.subList(0, RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS);
            List<BigDecimal> closes = contractRows.stream().map(TwseIndexDailyHistory::getClosePoint).toList();
            LocalDate currentTradingDay = cacheOnlyCalendar
                    ? currentTwTradingDayCachedOnly(decisionInstant) : currentTwTradingDay(decisionInstant);
            LocalDate latestEodDate = contractRows.isEmpty() ? null : contractRows.get(0).getTradingDate();
            boolean todayEodPresent = latestEodDate != null && latestEodDate.equals(currentTradingDay);

            Optional<PriceQueryService.LivePrice> liveOpt = priceQueryService.getLive(TAIEX_CODE, TW_MARKET);
            boolean liveFreshToday = !todayEodPresent && liveOpt.isPresent()
                    && liveOpt.get().tradingDate() != null
                    && currentTradingDay != null
                    && currentTradingDay.toString().equals(liveOpt.get().tradingDate());

            BigDecimal price;
            BigDecimal changePercent;
            if (liveFreshToday) {
                price = liveOpt.get().price();
                changePercent = closes.isEmpty() ? null : changePercent(price, closes.get(0));
            } else {
                price = closes.isEmpty() ? null : closes.get(0);
                changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;
            }

            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(TAIEX_CODE, TW_MARKET);
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
            // Task 356.10b：台股大盤週K 由 open_point／high_point／low_point／close_point／
            // trade_volume 映射成中性 OHLCV 後聚合，與美股大盤共用同一支 WeeklyBarAggregator。
            // 必須餵**長清單**（500 列）而非上面那份 241 列的 contractRows——241 列 ≈ 48 個 ISO 週
            // < 60 根完成週，餵契約列等於讓台股大盤四組週K 因子永遠缺值。
            List<StockPriceHistory> twIndexRowsDesc = twIndexOhlcvDesc(rows);
            RadarInputAssembler.MarketWeekly weekly =
                    nonNullWeekly(assembler.marketWeekly(twIndexRowsDesc, price));
            TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            price,
                            changePercent,
                            indicators(ind),
                            c60,
                            c240,
                            context.completedMarketChangePercent(),
                            context.marketVolumeRatio(),
                            context.marketTurnoverRatio(),
                            context.nasdaqChangePercent(),
                            context.soxChangePercent(),
                            context.usTechCompositePercent(),
                            context.usTechAvailable(),
                            // 台股的跨市場因子確實適用（前一美股科技交易日是台股的領先訊號），
                            // 故 crossMarketApplicable=true，行為逐位不變（Task 342.6）。
                            true,
                            // Task 356.10a：最新完成日的大盤 K 棒取序列第 0 筆（完成日 K），
                            // 不取 live 點位——live 沒有當日 OHLC，只有一個成交點。
                            marketCandle(twIndexRowsDesc),
                            weekly.weekly()));

            // stale＝「完成日 K 未到今日」且「Redis 也無今日即時價」時才成立；任一者成立即非 stale（Task 228）。
            boolean stale = !todayEodPresent && !liveFreshToday;
            TaiexDisplayPriceService.DisplayQuote display = taiexDisplayPriceService.resolve();
            String asOf = display.tradingDate();
            String liveUpdatedAt = liveFreshToday ? liveOpt.get().updatedAt() : null;
            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                    stale,
                    asOf,
                    display.price(),
                    display.changePercent(),
                    display.quoteStatus(),
                    ind.weeklyMa(),
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c60.name(),
                    c240.name(),
                    result.reasons(),
                    result.risks(),
                    liveFreshToday,
                    liveUpdatedAt,
                    toDto(ind.extended()),
                    context.marketVolumeRatio(),
                    context.marketTurnoverRatio(),
                    context.marketAsOfDate() == null ? null : context.marketAsOfDate().toString(),
                    context.nasdaqChangePercent(),
                    context.soxChangePercent(),
                    context.usTechCompositePercent(),
                    context.usTechAsOfDate() == null ? null : context.usTechAsOfDate().toString(),
                    context.usTechAvailable(),
                    // Task 356.10c：台股組的第 31 個 component，與美股組各算各的。
                    toWeeklyDto(weekly.weekly(), weekly.barsDesc(), weekly.indicators()));
            return new MarketState(summary, result.regime(), stale);
        } catch (Exception e) {
            log.warn("今日交易雷達：大盤資料組裝失敗", e);
            return incompleteMarket("讀取大盤資料失敗，所有個股暫停產生交易訊號。");
        }
    }

    /**
     * 美股個股的大盤情境（Task 294）：用那斯達克綜合指數（IXIC）自身技術面，而非台股加權指數——
     * 美股開盤時間與台股加權指數無直接對應關係，用 TAIEX 當美股的大盤沒有意義。
     *
     * <p>與 {@link #buildMarket} 各自獨立 try/catch，互不影響（294.2／(e) 的隔離要求）：IXIC 讀取失敗
     * 只讓美股個股停止產生訊號，不得拖垮台股組。</p>
     *
     * <p>{@code MarketInput} 的跨市場三欄（nasdaqChangePercent／soxChangePercent／
     * usTechCompositePercent）與 {@code usTechAvailable} 全部傳 {@code null}／{@code false}：
     * 那三欄的語意是「台股股票的跨市場領先訊號」，本身即為 IXIC 走勢的一部分，若原封不動餵給
     * 「大盤即是 IXIC」的美股組會重複計分（Requirement 64 的排除，Task 342 未推翻）。</p>
     *
     * <p>Task 323：{@code MarketSummary} 的大盤量能三欄改由既有的
     * {@link TradingRadarMarketContextService#resolveMarketFromRows} 供給（同一支 API 也是
     * {@code BacktestService} 走的那支），線上雷達與回測因此不會分岔出第二份美股量能計算。</p>
     *
     * <p>Task 342（Requirement 82）：{@code MarketInput} 的 {@code completedChangePercent} 與
     * {@code marketVolumeRatio} <b>自本版起接上同一份 {@code usContext}</b>，真正參與 regime 計分——
     * 原本「算了卻不用」的中間狀態在 Task 335 把 usMarket 送上畫面後，變成使用者直接看得到的矛盾
     * （卡片顯示量比 0.79、風險提醒卻說「資料不足」）。{@code marketTurnoverRatio} 仍為 {@code null}
     * （{@code us_index_daily_history} 無成交值欄，不得偽造）。新增的 {@code crossMarketApplicable}
     * 傳 {@code false}，使跨市場那則「資料不足」提醒改為沉默——那個因子對美股是<b>不適用</b>，
     * 不是資料缺失。</p>
     */
    private MarketState buildUsMarket(Instant decisionInstant) {
        try {
            List<UsIndexDailyHistory> rows =
                    usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(
                            IXIC_CODE, SERIES_FETCH_ROWS);
            // 既有日K 路徑一律只吃最新 241 列，長清單只供週K 聚合（Task 356.4a／356.4b）。
            List<UsIndexDailyHistory> contractRows = rows.size() <= RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS
                    ? rows
                    : rows.subList(0, RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS);
            // Task 323：用同一批已讀進來的 IXIC 列走既有 V13 解析，不在本類別另寫一份 ratio 計算。
            // 回傳型別是 TradingRadarMarketContextService.MarketContext（accessor 為 marketVolumeRatio()／
            // marketAsOfDate()），與 buildStock 內組 evidence 用的 TradingRadarEvidenceConfidenceResolver
            // .MarketContext 同名不同型，不得混用。
            TradingRadarMarketContextService.MarketContext usContext =
                    marketContextService.resolveMarketFromRows(
                            US_MARKET, decisionInstant, List.of(), contractRows);
            List<BigDecimal> closes = contractRows.stream().map(UsIndexDailyHistory::getClosePoint).toList();
            BigDecimal price = closes.isEmpty() ? null : closes.get(0);
            BigDecimal changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;

            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAllForNasdaq();
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
            // Task 356.10b：美股大盤週K 由 us_index_daily_history 的 OHLC＋volume 映射後聚合，
            // 與台股大盤共用同一支 WeeklyBarAggregator。
            //
            // ⚠ 必須餵**長清單** rows（500 列）而不是上面釘回 241 列的 contractRows：
            // 241 列 ≈ 48 個 ISO 週 < RadarInputAssembler.MIN_COMPLETED_WEEKS(60)，
            // 餵契約列會讓美股大盤四組週K 因子永遠缺值，且不會有任何錯誤訊息。
            // 既有日K 路徑（resolveMarketFromRows／closes／confirm）仍只吃 contractRows，
            // 故擴窗對既有輸出逐位無影響。
            List<StockPriceHistory> usIndexRowsDesc = usIndexOhlcvDesc(rows);
            RadarInputAssembler.MarketWeekly weekly =
                    nonNullWeekly(assembler.marketWeekly(usIndexRowsDesc, price));
            TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            price,
                            changePercent,
                            indicators(ind),
                            c60,
                            c240,
                            // Task 342（推翻 Task 323.2 的刻意留白）：完成日漲跌幅與量能比真正接進
                            // regime 分數（averageAvailable(...) → score ±8／±10／±3）。使用者已知情
                            // 並接受「美股個股 regime 與買進閘門會因此變動」的代價，RULE_VERSION 於該次
                            // 同步升版；現行 production 版號為 TW_RULES_V18（Task 382，gate 診斷風險分類修正）。
                            //
                            // ⚠ completedChangePercent 必須取 usContext 這一份，不得改用本方法上面的區域
                            // 變數 changePercent：後者算自 findTopN...(IXIC_CODE, 241)，該查詢沒有任何完成日
                            // 過濾（Yahoo range=10y&interval=1d 盤中會回傳當日的部分 bar），而 usContext 在
                            // resolveMarketFromRows 內套了美東 16:00 的完成日邊界。盤中兩者會落在不同 as-of 日，
                            // 讓「漲跌方向」與「量比」跨日拼接，直接把引擎四個計分分支的正負號判錯。
                            //
                            // ⚠ usContext == null 的三元防護不可省：單元測試把 marketContextService 宣告為
                            // @Mock，未 stub 的方法回 null，直接解參考產生的 NPE 會被本方法的 catch 吞成
                            // incompleteMarket()——不報錯卻讓美股整組變 DATA_INCOMPLETE。
                            usContext == null ? null : usContext.completedMarketChangePercent(),
                            usContext == null ? null : usContext.marketVolumeRatio(),
                            // marketTurnoverRatio：us_index_daily_history 無成交值／週轉率欄，
                            // 不得以成交量除以任何數字偽造週轉率。
                            null,
                            // 跨市場領先訊號三欄維持 null（Task 294 既有理由，見本方法 javadoc），
                            // usTechAvailable 維持 false；crossMarketApplicable=false（Task 342.2）讓引擎
                            // 沉默，不再把「不適用」謊報成「資料不足」。
                            null,
                            null,
                            null,
                            false,
                            false,
                            // Task 356.10a：最新完成日的大盤 K 棒（美股無 live 列，序列第 0 筆即最新）。
                            marketCandle(usIndexRowsDesc),
                            weekly.weekly()));

            LocalDate latestEodDate = contractRows.isEmpty() ? null : contractRows.get(0).getTradingDate();
            LocalDate mostRecentCompleted = mostRecentCompletedUsTradingDay(decisionInstant);
            boolean stale = latestEodDate == null || latestEodDate.isBefore(mostRecentCompleted);

            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                    stale,
                    latestEodDate == null ? null : latestEodDate.toString(),
                    price,
                    changePercent,
                    // Task 335：原本寫死 "CLOSE_PENDING"，該欄從未回傳前端故不可見；usMarket 上畫面後
                    // 會讓「最新點位」永遠顯示橘字「收盤價待補」（前端 isClosePending() 只認這個值）。
                    // 判準必須含完成日邊界，不得簡化成「price != null 就 VERIFIED_CLOSE」——兩側都會實際發生：
                    //  (i) latestEodDate < mostRecentCompleted（日線回補落後一盤）時 stale=true 而 price 仍非
                    //      null，那是「更舊的昨收」，標成已驗證即違反 Requirement 7 的「不得拿更舊昨收冒充」。
                    //  (ii) findTopN...(IXIC_CODE, 241) 沒有任何完成日過濾，Yahoo range=10y&interval=1d 在盤中
                    //      會回傳當日的部分 bar，此時 latestEodDate 會「晚於」mostRecentCompleted。
                    // 用 equals 而非 !isBefore 正是為了同時擋掉 (ii) 這一側。
                    // PREVIOUS_CLOSE 是本專案既有語彙（TaiexDisplayPriceService／PriceQueryService 同一組）。
                    price == null ? "CLOSE_PENDING"
                            : latestEodDate.equals(mostRecentCompleted) ? "VERIFIED_CLOSE"
                            : "PREVIOUS_CLOSE",
                    ind.weeklyMa(),
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c60.name(),
                    c240.name(),
                    result.reasons(),
                    result.risks(),
                    false,
                    null,
                    toDto(ind.extended()),
                    // Task 323：大盤量能比來自既有 IXIC context。null 防護是硬性要求——
                    // 單元測試把 marketContextService 宣告為 @Mock，未 stub 的方法回 null，
                    // 直接解參考產生的 NPE 會被下方 catch 吞成 incompleteMarket()，
                    // 不報錯卻讓美股整組變 DATA_INCOMPLETE。
                    usContext == null ? null : usContext.marketVolumeRatio(),
                    // marketTurnoverRatio 恆為 null：us_index_daily_history 沒有成交值／週轉率欄位，
                    // resolveUsV13 也明確傳 null，不得以成交量除以任何數字偽造週轉率。
                    // TradingRadarEvidenceConfidenceResolver 的 contextValueAvailable 是
                    // finite(volumeRatio) || finite(turnoverRatio) 的 OR，只有量能比即可成立。
                    null,
                    usContext == null || usContext.marketAsOfDate() == null
                            ? null : usContext.marketAsOfDate().toString(),
                    // 以下五欄（nasdaq／sox／usTechComposite／usTechAsOf／usTechAvailable）本次刻意不填：
                    // 「美股科技共同完成日」因子的設計語意是**台股列的領先訊號**（台股 14:00 決策時看
                    // 前一個已完成的美股 session），填進美股列自己使用的 MarketSummary 是自我指涉，
                    // 屬於另一個需要獨立驗收的決定（Task 323.2），不在本次射程。
                    null,
                    null,
                    null,
                    null,
                    false,
                    // Task 356.10c：美股組自己的週K，與台股組各算各的（兩張卡的數值必然不同）。
                    toWeeklyDto(weekly.weekly(), weekly.barsDesc(), weekly.indicators()));
            return new MarketState(summary, result.regime(), stale);
        } catch (Exception e) {
            log.warn("今日交易雷達：美股（IXIC）大盤資料組裝失敗", e);
            return incompleteMarket("讀取美股大盤資料失敗，美股個股暫停產生交易訊號。");
        }
    }

    private DecisionCore buildDecisionCore(
            Target target,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale,
            TradingRadarDto.MarketSummary marketSummary,
            Instant decisionInstant,
            Map<String, TradingRadarMarketContextService.FxContext> fxCache,
            DecisionClock decisionClock,
            DecisionClock usDecisionClock,
            RadarTechnicalResolver.Batch technicalBatch,
            boolean includeFullDetail) {
        return buildDecisionCore(target, marketRegime, marketStale, marketSummary, decisionInstant, fxCache,
                decisionClock, usDecisionClock, technicalBatch, includeFullDetail, null);
    }

    /** List-only overload consumes the immutable request context and never falls back to per-target I/O. */
    private DecisionCore buildDecisionCore(
            Target target,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale,
            TradingRadarDto.MarketSummary marketSummary,
            Instant decisionInstant,
            Map<String, TradingRadarMarketContextService.FxContext> fxCache,
            DecisionClock decisionClock,
            DecisionClock usDecisionClock,
            RadarTechnicalResolver.Batch technicalBatch,
            boolean includeFullDetail,
            TradingRadarListBatchPreloader.Entry listEntry) {
        // The compact route has no legal single-target fallback.  A failed or
        // incomplete batch context is represented as one fail-soft row rather
        // than reopening repositories/Redis while evaluating this target.
        if (!includeFullDetail && listEntry == null) {
            return DecisionCore.failed(target, target.code(), "UNKNOWN", null,
                    "清單批次資料不可得，該檔今日不交易。");
        }
        Optional<Stock> stock = listEntry == null
                ? stockRepo.findByCodeAndMarket(target.code(), target.market()) : listEntry.stock();
        String name = stock.map(Stock::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(target.code());
        TradingRadarDto.SettingsClassification settingsClassification = includeFullDetail
                ? resolveSettingsClassification(stock.orElse(null), target.code(), target.market(), name)
                : null;
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                stock.orElse(null), target.code(), target.market(), name,
                null, stockStyleIncomeThreshold());
        String assetClass = profile.assetClass();
        TradingRadarRuleEngine.InstrumentType instrumentType = profile.bond()
                ? TradingRadarRuleEngine.InstrumentType.BOND
                : TradingRadarRuleEngine.InstrumentType.EQUITY;
        try {
            // 必須先跑完整 snapshot 的 backend projection，再讀 adjustment history；否則新抓到的
            // ACTIVE／CANCELLED 會晚一輪才反映在還原權息序列。pipeline 對缺完整證據為 no-op。
            DividendEventEvidenceResolver.Resolution distribution = listEntry == null
                    ? dividendEventEvidenceRepository.resolve(target.code(), target.market(), decisionInstant,
                    futureSessions(target.market(), decisionInstant)) : listEntry.dividendEvidence();
            // Task 356.4a 起改抓 500（≈100 個 ISO 週）供週K 聚合；緩衝的理由不變（Task 319.4）：
            // 日 K 序列的契約上限仍固定為 241
            // （confirm(closes, 240) 需要 241 根完成收盤），由 RadarObservationResolver 截斷。
            // 這裡多抓 9 列是讓兩種剔除有名額可遞補——(1) findRecentN 沒有日期上界，盤中
            // completedSession 為前一交易日時 DB 的當日列會先佔掉一個名額再被剔除；
            // (2) completedSession 當日列存在但 close_source 未命中白名單。任一發生就只剩 240 根，
            // ma240Confirmation 回 UNAVAILABLE 而仍輸出 NO_TRADE，且五個指標欄位全非 null、偽裝成修好。
            List<StockPriceHistory> rawRows = listEntry == null
                    ? priceHistoryRepo.findRecentN(target.code(), target.market(), SERIES_FETCH_ROWS)
                    : listEntry.prices();
            Optional<PriceQueryService.LivePrice> rawLive = listEntry == null
                    ? priceQueryService.getLive(target.code(), target.market()) : listEntry.live();
            RadarObservationResolver.AcceptedPrice acceptedPrice =
                    decisionClock != null && decisionClock.authoritative()
                            ? RadarObservationResolver.resolveAcceptedPrice(
                                    rawRows, rawLive.orElse(null), target.market(), decisionInstant,
                                    decisionClock.sessions())
                            : RadarObservationResolver.resolveAcceptedPrice(
                                    rawRows, rawLive.orElse(null), target.market(), decisionInstant,
                                    (java.util.function.Predicate<LocalDate>)
                                            day -> marketDataService.isTradingDay(target.market(), day));
            // accepted price 與技術序列出自同一份 AcceptedPrice 快照，但判準各自明確（Task 319）：
            // 兩者都拒絕 future 列與 completedSession 當日的不可信 closeSource 列（避免把盤中誤寫值
            // 當成完成收盤 K 餵進 MA／BIAS）；更早的歷史列則<b>不看 closeSource</b>，
            // 白名單只界定 verified 收盤，不得拿來裁掉技術序列。
            BigDecimal price = acceptedPrice.value();
            RadarInputAssembler.Prepared technicalBasis = prepareTechnicalBasis(target, acceptedPrice,
                    listEntry == null ? null : listEntry.adjustmentEvents());
            String technicalFingerprint = technicalContextFingerprint(target, acceptedPrice, technicalBasis);
            // A fresh local snapshot is selected before `calculate`, so both
            // daily and weekly computeFromSeries calls are skipped on a valid
            // BOUND LOCAL cache hit.  Its non-formula derivatives are still
            // rebuilt from the current prepared price basis.
            ResolvedTechnicalInputs freshLocal = radarTechnicalResolver == null
                    ? null
                    : radarTechnicalResolver.freshLocal(target.code(), target.market(), technicalFingerprint,
                            decisionInstant, technicalBatch);
            RadarInputAssembler.Assembled technical = freshLocal == null
                    ? assembler.calculate(technicalBasis)
                    : assembler.calculate(technicalBasis, freshLocal.indicators(), freshLocal.weekly(),
                            freshLocal.weeklyIndicators());
            BigDecimal displayPrice = acceptedPrice.value();
            // ── Task 320：即時折溢價（純揭露欄，不進任何規則） ────────────────────────────
            // 「同一 tick」在這裡的正確意思是「<b>價只取一次</b>」：現價與淨值本來就是兩個 Redis key
            // （price:{market}:{code} vs price:etfnav:{market}:{code}），LivePrice 裡沒有 nav 欄位，
            // 不存在「一次讀到價又讀到淨值」的路徑。讀淨值必然是第二次 Redis 讀取，那不在禁止之列；
            // 禁止的是為折溢價<b>另打一次取價</b>——那會讓「現價」與「折溢價」落在不同 tick，
            // 使用者拿畫面數字驗算 (price−nav)/nav 就會兜不攏。故此處一律沿用同一列已組好的 displayPrice。
            // getEtfNav 查無回 Optional.empty() 是正常情形（個股本來就沒有淨值），不記 warn 洗版。
            PriceQueryService.EtfNav livePremiumNav = listEntry == null
                    ? priceQueryService.getEtfNav(target.code(), target.market()).orElse(null)
                    : listEntry.liveNav().orElse(null);
            BigDecimal etfPremiumLivePct =
                    EtfLivePremiumCalculator.premiumDiscountPct(livePremiumNav, displayPrice);
            String etfPremiumLiveNavAsOf = livePremiumNav == null ? null : livePremiumNav.navAsOf();
            BigDecimal displayChangePercent = acceptedPrice.liveAccepted() && acceptedPrice.live() != null
                    ? acceptedPrice.live().changePercent() : null;
            // 組裝結果一律取自 RadarInputAssembler，與回測共用同一份（Task 273 的 273.2b）。
            BigDecimal ruleChangePercent = technical.ruleChangePercent();
            if (ruleChangePercent == null) ruleChangePercent = displayChangePercent;
            // 完成收盤 fallback 沒有第二個 quote source；同源的 adjusted close 漲跌仍可揭露，
            // 避免 accepted price 已可用卻把 DTO 的 changePercent 無謂留白。
            if (displayChangePercent == null) displayChangePercent = ruleChangePercent;

            ResolvedTechnicalInputs resolvedTechnical = freshLocal != null ? freshLocal : radarTechnicalResolver == null
                    ? new ResolvedTechnicalInputs(technical.indicators(), technical.weekly(), technical.weeklyIndicators(), null)
                    : listEntry == null ? radarTechnicalResolver.resolve(
                            target.code(), target.market(), technical.indicators(), technical.weekly(), technical.weeklyIndicators(),
                            technical.distributionAdjusted(), technical.weeklyDistributionAdjusted(),
                            acceptedPrice.liveAccepted(),
                            technical.volatility60().asOfDate(),
                            technical.weekly() == null ? null : technical.weekly().weekEndDate(),
                            technicalFingerprint, decisionInstant, technicalBatch)
                    : radarTechnicalResolver.resolveReadOnly(target.code(), target.market(), technical.indicators(),
                            technical.weekly(), technical.weeklyIndicators(), technical.distributionAdjusted(),
                            technical.weeklyDistributionAdjusted(), acceptedPrice.liveAccepted(),
                            technical.volatility60().asOfDate(), technical.weekly() == null ? null
                                    : technical.weekly().weekEndDate(), technicalFingerprint, decisionInstant, technicalBatch);
            // ResolvedTechnicalInputs is the single overlay boundary.  Do not
            // mutate `technical`: confirmations, candles, BIAS and all other
            // derived fields intentionally remain local.
            TechnicalIndicatorService.FullIndicators ind = resolvedTechnical.indicators();
            TradingRadarRuleEngine.WeeklyInput resolvedWeekly = resolvedTechnical.weekly();
            TradingRadarRuleEngine.Confirmation c20 = technical.ma20Confirmation();
            TradingRadarRuleEngine.Confirmation c60 = technical.ma60Confirmation();
            TradingRadarRuleEngine.Confirmation c240 = technical.ma240Confirmation();
            String currency = profile.underlyingCurrency();
            TradingRadarMarketContextService.FxContext fx = currency == null || TWD.equals(currency)
                    ? TradingRadarMarketContextService.FxContext.EMPTY
                    : listEntry == null
                    ? fxCache.computeIfAbsent(currency, c -> marketContextService.resolveFx(c, decisionInstant))
                    : listEntry.fx();
            BigDecimal fxPct = fx.percentile();
            BigDecimal ma60Bias = technical.ma60BiasPercent();
            BigDecimal ma240Bias = technical.ma240BiasPercent();
            BigDecimal week52Pos = technical.week52Position();
            PremiumObservation premium = listEntry == null
                    ? etfPremiumObservation(target.code(), target.market(), decisionInstant,
                    decisionClock == null ? null : decisionClock.sessions(),
                    decisionClock != null && decisionClock.authoritative())
                    : etfPremiumObservationFromBatch(target.market(), decisionInstant,
                    listEntry.premiumTargetDate(), listEntry.premiumObservations());
            BigDecimal etfPremiumPct = premium.value();
            BigDecimal etfPremiumPercentile = etfPremiumPercentile(
                    target.code(), target.market(), etfPremiumPct, premium.asOfDate(), premium.history(), listEntry != null);
            FundamentalAnalysisService.Resolved fundamental = listEntry == null ? fundamentalAnalysisService.resolve(
                    target.code(), name, target.market(), decisionInstant, profile) : listEntry.fundamental();
            // Keep compatibility with older test/adapters that only implement the four-field
            // resolver; a null adapter response is never treated as complete evidence.
            if (fundamental == null && listEntry == null) {
                fundamental = fundamentalAnalysisService.resolve(
                        target.code(), name, target.market(), decisionInstant);
            }
            if (fundamental == null) {
                fundamental = FundamentalAnalysisService.Resolved.unavailable(profile.equity());
            }
            // Style profile may use only decision-time public valuation yield; never use owner snapshot
            // cashflow/cost/allocation fields. Re-resolve after fundamental as-of selection so the
            // provenance remains explicit in the evidence payload.
            profile = TradingRadarAssetProfileResolver.resolve(
                    stock.orElse(null), target.code(), target.market(), name,
                    fundamental.snapshot().dividendYieldPct(), stockStyleIncomeThreshold());
            assetClass = profile.assetClass();
            instrumentType = profile.bond()
                    ? TradingRadarRuleEngine.InstrumentType.BOND
                    : TradingRadarRuleEngine.InstrumentType.EQUITY;
            // The strict profile is re-resolved after as-of valuation selection;
            // use its currency/provenance for both the evidence gate and output.
            currency = profile.underlyingCurrency();
            TradingRadarRuleEngine.StockResult result = ruleEngine.evaluateStock(
                    new TradingRadarRuleEngine.StockInput(
                            target.held(),
                            price,
                            ruleChangePercent,
                            technical.completedChangePercent(),
                            indicators(ind),
                            ind.previousK(),
                            ind.previousD(),
                            c20,
                            c60,
                            c240,
                            instrumentType,
                            marketRegime,
                            marketStale,
                            fxPct,
                            ma60Bias,
                            technical.ma60BiasPercentile(),
                            ma240Bias,
                            week52Pos,
                            technical.kdBandWidthPercent(),
                            etfPremiumPct,
                            etfPremiumPercentile,
                            ind.weeklyMa(),
                            assembler.extendedIndicators(ind.extended()),
                            technical.volumeRatio(),
                            fundamental.input(),
                            // Task 356.5a／356.6：日K 棒與週K 必須顯式傳入，否則會命中 Task 356
                            // 之前的相容建構式（少傳引數不會編譯失敗），兩欄被填成 null →
                            // 五個新因子在 production 恆為缺值、權重被重分配掉，而 BacktestService
                            // 有正確傳入 → 線上與回測分岔且完全靜默。
                            // 兩者與 DTO 揭露欄（toDailyCandleDto／toWeeklyDto）同源，皆取自這一份
                            // Assembled，不得為了接線再算第二次。
                            technical.dailyCandle(),
                            resolvedWeekly));
            TradingRadarEvidenceConfidenceResolver.MarketContext marketContext = marketSummary == null
                    ? TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY
                    : new TradingRadarEvidenceConfidenceResolver.MarketContext(
                    parseLocalDate(marketSummary.marketVolumeAsOfDate()),
                    marketSummary.marketVolumeRatio(), marketSummary.marketTurnoverRatio(),
                    "MARKET_CONTEXT",
                    // 台股 TAIEX 與美股 IXIC 都有意義的成交量概念；即使
                    // 今日 ratio 缺值，也必須以 MISSING 留在 evidence 分母，
                    // 不可由 nullable ratio 推成 N/A。
                    true);
            TradingRadarMarketFeatureResolver.Evidence marketFeatures = listEntry == null
                    ? resolveMarketFeatureEvidence(target.market(), decisionInstant, decisionClock, usDecisionClock)
                    : listEntry.marketFeatures();
            BondYieldBetaResolver.Result bondYieldBeta = listEntry == null
                    ? resolveBondYieldBeta(target.code(), target.market(), profile, decisionInstant,
                    RuleParameters.v12Default()) : listEntry.bondYieldBeta();
            TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation = listEntry == null
                    ? resolveTreasuryRateObservation(profile, decisionInstant, bondYieldBeta)
                    : resolveTreasuryRateObservation(profile, listEntry.rateContext(), bondYieldBeta);
            // PRICE/TECHNICAL terminal is owned by the accepted quote.  A live quote can be
            // today's session while the market summary/volume context is still yesterday;
            // using marketSummary.asOfDate here incorrectly marked the accepted price MISSING.
            // Market context validates its own as-of date inside EvidenceConfidenceResolver.
            LocalDate expectedTerminal = acceptedPrice.available()
                    ? acceptedPrice.tradingDate() : null;
            TradingRadarEvidenceConfidenceResolver.Evidence evidence =
                    TradingRadarEvidenceConfidenceResolver.resolve(
                            new TradingRadarEvidenceConfidenceResolver.Inputs(
                                    target.market(), decisionInstant, acceptedPrice, technical,
                                    marketRegime, marketStale, marketContext, fundamental.snapshot(), profile,
                                    etfPremiumPct, premium.asOfDate(), premium.source(), premium.stale(),
                                    fxPct, fx.asOfDate(), rateObservation, result.timingState(), distribution,
                                    marketFeatures),
                            // Strict terminal-date gate must use the market's completed
                            // price session, not the optional volume/turnover context.
                            // US IXIC often has no same-day volume context; using that
                            // nullable field here would close PRICE/MARKET for every
                            // otherwise fresh US decision.
                            expectedTerminal,
                            decisionClock != null && decisionClock.authoritative()
                                    ? sessionDate(decisionClock)
                                    : marketSummary == null ? null : parseLocalDate(marketSummary.asOfDate()));
            // V12 score/candidate/parameters and RULE_VERSION stay bit-identical.  Only the final
            // action passes through the deterministic safety policy, at this single construction
            // point shared by page, snapshot/export and evaluateForNotification.
            // Task 356.9b：gate 擴為三軌；既有兩軌的 gate 行為逐位不變，swing 軌套用同一組
            // buy 降級與 risk-evidence 判定（不得只讓其中兩軌通過閘門）。
            TradingRadarEvidenceGate.GatedActions gated = TradingRadarEvidenceGate.apply(
                    result.action(), result.shortAction(), result.swingAction(),
                    target.held(), profile, evidence);

            List<String> reasons = List.of();
            List<String> risks = List.of();
            List<String> shortReasons = List.of();
            List<String> shortRisks = List.of();
            // Task 356.1b-2：swing 軌必須比照既有兩軌加入還原權息揭露句，否則同一頁三軌
            // 中兩軌有揭露、一軌沒有。
            // 出處就是這裡——**引擎不產生任何還原字串**：evaluateStock 的三軌 reasons 只寫
            // 均線位置／指標方向，「已使用還原權息序列」這件事只有本方法知道（判準是
            // technical.distributionAdjusted()，由 RadarInputAssembler 依事件日與日K 契約視窗
            // 決定）。三軌的揭露句因此一律在此加上，不得指望 result.swingReasons() 自帶。
            List<String> swingReasons = List.of();
            List<String> swingRisks = List.of();
            if (includeFullDetail) {
                reasons = new ArrayList<>();
                if (technical.distributionAdjusted()) {
                    reasons.add("MA／KD、兩日確認與規則漲跌已使用還原權息／分割價，避免把配息缺口或分割跳空誤判為趨勢跌破。 ");
                }
                reasons.addAll(result.reasons());
                risks = new ArrayList<>(result.risks());
                risks.addAll(gated.mediumDiagnostics());
                shortReasons = new ArrayList<>();
                if (technical.distributionAdjusted()) {
                    shortReasons.add("MA／KD、擴充指標與相對量已使用同一份還原權息／分割序列。 ");
                }
                shortReasons.addAll(result.shortReasons());
                shortRisks = new ArrayList<>(result.shortRisks());
                shortRisks.addAll(gated.shortDiagnostics());
                swingReasons = new ArrayList<>();
                if (technical.distributionAdjusted()) {
                    swingReasons.add("MA／KD、週K 聚合與相對量已使用同一份還原權息／分割序列，"
                            + "1周~1月 的波段位置不受配息缺口或分割跳空影響。 ");
                }
                swingReasons.addAll(nullSafe(result.swingReasons()));
                swingRisks = new ArrayList<>(nullSafe(result.swingRisks()));
                swingRisks.addAll(gated.swingDiagnostics());
                if (!TWD.equals(currency) && fx.asOfDate() == null) {
                    String missingFx = "精確完成日匯率不可得，外幣債券 ETF 的匯率因子本日缺值。 ";
                    risks.add(missingFx);
                    shortRisks.add(missingFx);
                    swingRisks.add(missingFx);
                }
            }

            String updatedAt = acceptedPrice.updatedAt();
            String asOf = acceptedPrice.tradingDate() == null
                    ? null : acceptedPrice.tradingDate().toString();
            String quoteStatus = acceptedPrice.quoteStatus();
            return new DecisionCore(
                    target, name, assetClass, settingsClassification, profile, technical, resolvedTechnical,
                    acceptedPrice, result, gated, evidence, fundamental, distribution, rateObservation, premium,
                    displayPrice, displayChangePercent, quoteStatus, updatedAt, asOf, ind, c20, c60, c240,
                    fxPct, currency, fx.asOfDate() == null ? null : fx.asOfDate().toString(),
                    ma60Bias, week52Pos, etfPremiumPct, etfPremiumPercentile,
                    etfPremiumLivePct, etfPremiumLiveNavAsOf, List.copyOf(reasons), List.copyOf(risks),
                    List.copyOf(shortReasons), List.copyOf(shortRisks), List.copyOf(swingReasons),
                    List.copyOf(swingRisks), null);
        } catch (Exception e) {
            if (includeFullDetail) {
                log.warn("今日交易雷達：{} {} 組裝失敗", target.market(), target.code(), e);
            } else {
                logListBatchUnavailable("compact-decision-core", e);
            }
            return DecisionCore.failed(target, name, assetClass, settingsClassification,
                    "讀取個股資料失敗，該檔今日不交易。");
        }
    }

    /** Full/snapshot/export projection is intentionally kept behind this late boundary. */
    private TradingRadarDto.StockDecision toFullDecision(DecisionCore core) {
        if (core.failureMessage() != null) {
            return incompleteStock(core.target(), core.name(), core.assetClass(),
                    core.settingsClassification(), core.failureMessage());
        }
        return new TradingRadarDto.StockDecision(
                core.target().code(), core.name(), core.target().market(), core.assetClass(),
                core.technical().distributionAdjusted(), core.target().held(),
                core.gated().mediumAction().name(), actionLabel(core.gated().mediumAction()), core.result().score(),
                core.result().counterTrend().state().name(), counterTrendLabel(core.result().counterTrend().state()),
                core.result().counterTrend().reasons(), core.result().counterTrend().risks(),
                core.result().action() != TradingRadarRuleEngine.Action.NO_TRADE,
                core.displayPrice(), core.displayChangePercent(), core.quoteStatus(), core.updatedAt(), core.asOf(),
                core.indicators().monthlyMa(), core.indicators().quarterlyMa(), core.indicators().annualMa(),
                core.indicators().k(), core.indicators().d(), core.c20().name(), core.c60().name(), core.c240().name(),
                core.fxPercentile(), core.currency(), core.reasons(), core.risks(), core.result().kdHeat().name(),
                core.result().timingState().name(), timingLabel(core.result().timingState()), core.ma60Bias(),
                core.week52Position(), core.indicators().weeklyMa(), core.etfPremiumPct(),
                core.etfPremiumPercentile(), toDto(core.indicators().extended()), core.gated().shortAction().name(),
                actionLabel(core.gated().shortAction()), core.result().shortScore(), core.shortReasons(),
                core.shortRisks(), core.result().horizonConflict(), core.technical().volumeRatio(), core.fxAsOfDate(),
                core.result().profitTakingConfirmed(), core.fundamental().snapshot(),
                TradingRadarDto.RadarEvidence.withConfidence(
                        core.asOf(), core.acceptedPrice().source(), core.acceptedPrice().quality().name(),
                        core.acceptedPrice().liveAccepted(), core.technical().returnStdDev60Ratio(),
                        core.technical().volatility60().asOfDate() == null ? null
                                : core.technical().volatility60().asOfDate().toString(),
                        core.technical().volatility60().source(),
                        core.premium().asOfDate() == null ? null : core.premium().asOfDate().toString(),
                        core.premium().source(), core.premium().stale(),
                        TradingRadarDto.AssetProfile.from(core.profile()), core.gated().reasons(), core.evidence(),
                        core.gated().candidateMediumAction().name(), core.gated().candidateShortAction().name(),
                        core.distribution(), core.rateObservation().context(),
                        TradingRadarDto.NormalizedBiasEvidence.from(core.result().normalizedBias()),
                        TradingRadarDto.NormalizedBiasEvidence.from(core.result().shortNormalizedBias()),
                        actionName(core.gated().candidateSwingAction()))
                        .withSettingsClassification(core.settingsClassification()),
                core.evidence().shortDownsideRisk(), core.evidence().mediumDownsideRisk(),
                core.evidence().shortConfidence(), core.evidence().mediumConfidence(),
                core.evidence().shortRisk().riskCoverage(), core.evidence().mediumRisk().riskCoverage(),
                core.gated().candidateMediumAction().name(), core.gated().candidateShortAction().name(),
                core.gated().reasons(), core.etfPremiumLivePct(), core.etfPremiumLiveNavAsOf(),
                actionName(core.gated().swingAction()),
                core.gated().swingAction() == null ? null : actionLabel(core.gated().swingAction()),
                core.result().swingScore(), core.swingReasons(), core.swingRisks(),
                core.evidence().swingDownsideRisk(), core.evidence().swingConfidence(),
                core.evidence().swingRisk().riskCoverage(), actionName(core.gated().candidateSwingAction()),
                toDailyCandleDto(core.technical().dailyCandle(), core.technical().volatility60().asOfDate()),
                toWeeklyDto(core.resolvedTechnical().weekly(), core.technical().weeklyBarsDesc(),
                        core.resolvedTechnical().weeklyIndicators()), core.resolvedTechnical().resolution());
    }

    /**
     * Terminal compact-row boundary: a bad evaluator or projection is one unavailable row, never
     * a failed stream that clears the owner's whole list.
     */
    private TradingRadarDto.ListStock safeListStock(Target target, Supplier<DecisionCore> coreBuilder) {
        DecisionCore core;
        try {
            core = coreBuilder.get();
        } catch (RuntimeException unavailable) {
            logListBatchUnavailable("compact-decision-core", unavailable);
            return unavailableListStock(target, target.code(), "UNKNOWN");
        }
        try {
            return toListStock(core);
        } catch (RuntimeException unavailable) {
            logListBatchUnavailable("compact-projection", unavailable);
            return unavailableListStock(target, target.code(), "UNKNOWN");
        }
    }

    /** List projection never creates a StockDecision, RadarEvidence, or nested full-detail DTO. */
    /* package */ TradingRadarDto.ListStock toListStock(DecisionCore core) {
        if (core.failureMessage() != null) {
            return unavailableListStock(core.target(), core.name(), core.assetClass());
        }
        TradingRadarDto.FundamentalSnapshot fundamental = core.fundamental().snapshot();
        TradingRadarDto.ListFundamental listFundamental = fundamental == null ? null
                : new TradingRadarDto.ListFundamental(fundamental.applicable(), fundamental.coverage(),
                        fundamental.industryName(), fundamental.industryRevenueYoyPct());
        TradingRadarRuleEngine.WeeklyInput weekly = core.resolvedTechnical().weekly();
        TradingRadarDto.ListWeeklyIndicators listWeekly = weekly == null ? null
                : new TradingRadarDto.ListWeeklyIndicators(weekly.k(), weekly.d(), weekly.changePercent());
        String dailyCandleAsOfDate = core.technical().dailyCandle() == null
                || core.technical().volatility60().asOfDate() == null ? null
                : core.technical().volatility60().asOfDate().toString();
        return new TradingRadarDto.ListStock(
                core.target().code(), core.name(), core.target().market(), core.assetClass(),
                core.technical().distributionAdjusted(), core.target().held(), core.fxPercentile(), core.currency(),
                listFundamental, core.gated().shortAction().name(), actionLabel(core.gated().shortAction()),
                core.result().shortScore(), actionName(core.gated().swingAction()),
                core.gated().swingAction() == null ? null : actionLabel(core.gated().swingAction()),
                core.result().swingScore(), core.gated().mediumAction().name(),
                actionLabel(core.gated().mediumAction()), core.result().score(), core.result().horizonConflict(),
                core.result().timingState().name(), timingLabel(core.result().timingState()),
                core.result().counterTrend().state().name(), counterTrendLabel(core.result().counterTrend().state()),
                core.displayPrice(), core.displayChangePercent(), core.quoteStatus(), core.updatedAt(),
                core.etfPremiumLivePct(), core.etfPremiumLiveNavAsOf(), core.indicators().weeklyMa(),
                core.indicators().monthlyMa(), core.indicators().quarterlyMa(), core.indicators().annualMa(),
                core.indicators().k(), core.indicators().d(), core.result().kdHeat().name(), listWeekly,
                dailyCandleAsOfDate);
    }

    private static TradingRadarDto.ListStock unavailableListStock(Target target, String name, String assetClass) {
        String safeName = name == null || name.isBlank() ? target.code() : name;
        String safeAssetClass = assetClass == null || assetClass.isBlank() ? "UNKNOWN" : assetClass;
        return new TradingRadarDto.ListStock(
                target.code(), safeName, target.market(), safeAssetClass, false,
                target.held(), null, null, null, null, null, null, null, null, null,
                TradingRadarRuleEngine.Action.NO_TRADE.name(),
                actionLabel(TradingRadarRuleEngine.Action.NO_TRADE), null, false,
                TradingRadarRuleEngine.TimingState.NEUTRAL.name(),
                timingLabel(TradingRadarRuleEngine.TimingState.NEUTRAL),
                TradingRadarRuleEngine.CounterTrendState.NONE.name(),
                counterTrendLabel(TradingRadarRuleEngine.CounterTrendState.NONE), null, null,
                "CLOSE_PENDING", null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void logListBatchUnavailable(String phase, Exception unavailable) {
        log.warn("交易雷達清單批次不可得：phase={}, error={}", phase,
                unavailable.getClass().getSimpleName());
    }

    /**
     * Resolves the single complete Treasury batch used by this decision.
     * Raw yield levels are disclosure evidence only until the per-instrument beta
     * and holdout promotion gates pass; they are deliberately not coerced to zero
     * or normalized into an invented downside score.
     */
    TradingRadarEvidenceConfidenceResolver.RateObservation resolveTreasuryRateObservation(
            TradingRadarAssetProfileResolver.AssetProfile profile, Instant decisionInstant) {
        return resolveTreasuryRateObservation(profile, decisionInstant,
                BondYieldBetaResolver.Result.notApplicable(null));
    }

    private TradingRadarEvidenceConfidenceResolver.RateObservation resolveTreasuryRateObservation(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            Instant decisionInstant,
            BondYieldBetaResolver.Result bondYieldBeta) {
        if (profile == null || !profile.bond()) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable();
        }
        if (!profile.profileComplete()) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "strict bond profile 不完整，Treasury 利率風險不納入");
        }
        BondRateQueryResolver.Selection fallback = BondRateQueryResolver.select(
                profile.bondTerm(), RuleParameters.v12Default());
        // Production is still V12: even a stable unpublished beta cannot select a
        // different Treasury observation and indirectly change the final-action gate.
        String tenor = fallback == null ? null : fallback.primaryTenor();
        if (tenor == null) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "strict bond term 缺漏，禁止猜測 Treasury tenor");
        }
        try {
            return treasuryYieldService.resolveRateContext(decisionInstant, tenor)
                    .map(context -> rateObservation(context, bondYieldBeta))
                    .orElseGet(() -> TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                            "決策時點前無完整 Treasury curve batch"));
        } catch (RuntimeException e) {
            log.warn("今日交易雷達：Treasury context 不可得：{}", e.getMessage());
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "Treasury context 解析失敗");
        }
    }

    /** List evaluator variant: the Treasury curve was already loaded once in the request context. */
    private TradingRadarEvidenceConfidenceResolver.RateObservation resolveTreasuryRateObservation(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            TreasuryYieldDto.RateContext context,
            BondYieldBetaResolver.Result bondYieldBeta) {
        if (profile == null || !profile.bond()) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable();
        }
        if (!profile.profileComplete()) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "strict bond profile 不完整，Treasury 利率風險不納入");
        }
        BondRateQueryResolver.Selection fallback = BondRateQueryResolver.select(
                profile.bondTerm(), RuleParameters.v12Default());
        if (fallback == null || fallback.primaryTenor() == null) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "strict bond term 缺漏，禁止猜測 Treasury tenor");
        }
        return context == null
                ? TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                "決策時點前無完整 Treasury curve batch")
                : rateObservation(context, bondYieldBeta);
    }

    private String betaDisclosureReason(BondYieldBetaResolver.Result beta) {
        if (beta == null) return "Treasury batch 已知，但 bond beta evidence 缺漏";
        return "Treasury batch 已知；bond beta status=" + beta.status()
                + " n=" + beta.n() + "，未經 V13 holdout promotion 不納入分數";
    }

    /**
     * Production remains V12 until an exact-key holdout is separately approved and published.
     * A stable fitted beta may therefore enrich disclosure, but it must not become an actionable
     * downside unit on this path.  V13 candidate evaluation consumes beta through its own offline
     * candidate context and is deliberately not wired through this mapper.
     */
    TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation(
            TreasuryYieldDto.RateContext context, BondYieldBetaResolver.Result beta) {
        return TradingRadarEvidenceConfidenceResolver.RateObservation.contextOnly(
                context, betaDisclosureReason(beta));
    }

    BondYieldBetaResolver.Result resolveBondYieldBeta(
            String code,
            String market,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            Instant decisionInstant,
            RuleParameters parameters) {
        if (profile == null || !profile.bond()) {
            return BondYieldBetaResolver.Result.notApplicable(null);
        }
        BondYieldBetaResolver.Query query = BondRateQueryResolver.query(
                code, market, profile, decisionInstant, parameters);
        if (!profile.profileComplete() || query.tenor() == null) {
            return BondYieldBetaResolver.Result.missing(query,
                    "strict bond profile／tenor 不完整，禁止猜測 beta");
        }
        if (bondYieldBetaEvidencePort == null) {
            return BondYieldBetaResolver.Result.missing(query,
                    "bond beta evidence port 未注入");
        }
        try {
            BondYieldBetaResolver.Result resolved = bondYieldBetaEvidencePort.resolve(query);
            return resolved == null
                    ? BondYieldBetaResolver.Result.missing(query, "bond beta resolver 回傳空值")
                    : resolved;
        } catch (RuntimeException e) {
            log.warn("交易雷達 bond beta 解析失敗（{}/{}）：{}", market, code, e.getMessage());
            return BondYieldBetaResolver.Result.missing(query, "bond beta resolver 解析失敗");
        }
    }

    /**
     * 解析決策時點可見的大盤／原物料數值證據。
     *
     * <p>正式路徑由 market feature port 載入指數、typed 法人 observation 與帶 provenance 的
     * 原物料資料，再依完成日與 conservative availability boundary 做 as-of 截斷；只有測試／
     * 手動建構時未注入 port 才退回指數-only bounded fallback。任何路徑皆不得由新聞文字猜測數值。</p>
     */
    private TradingRadarMarketFeatureResolver.Evidence resolveMarketFeatureEvidence(
            String market,
            Instant decisionInstant,
            DecisionClock marketDecisionClock,
            DecisionClock usDecisionClock) {
        try {
            boolean strict = (marketDecisionClock != null && marketDecisionClock.authoritative())
                    || (usDecisionClock != null && usDecisionClock.authoritative());
            TradingRadarMarketFeatureResolver.ExpectedSessions expected = strict
                    ? TradingRadarMarketFeatureResolver.ExpectedSessions.strict(
                            marketDecisionClock == null || !marketDecisionClock.authoritative()
                                    ? null : sessionDate(marketDecisionClock),
                            usDecisionClock == null || !usDecisionClock.authoritative()
                                    ? null : sessionDate(usDecisionClock),
                            usDecisionClock == null || !usDecisionClock.authoritative()
                                    ? null : sessionDate(usDecisionClock))
                    : TradingRadarMarketFeatureResolver.ExpectedSessions.none();
            if (marketFeaturePort != null) {
                TradingRadarMarketFeatureResolver.Evidence resolved =
                        marketFeaturePort.resolve(market, decisionInstant, expected);
                return resolved == null
                        ? TradingRadarMarketFeatureResolver.Evidence.empty(
                                market, decisionInstant, "市場 feature port 回傳空值")
                        : resolved;
            }
            List<UsIndexDailyHistory> usRows = new ArrayList<>();
            for (String indexCode : List.of("IXIC", "SOX", "SPX", "DJI")) {
                List<UsIndexDailyHistory> rows = usIndexDailyHistoryRepo
                        .findByIndexCodeOrderByTradingDateAsc(indexCode);
                if (rows != null) usRows.addAll(rows);
            }
            List<TwseIndexDailyHistory> twRows = twseRepo.findAllByOrderByTradingDateAsc();
            return TradingRadarMarketFeatureResolver.resolve(
                    market,
                    decisionInstant,
                    new TradingRadarMarketFeatureResolver.Sources(
                            usRows,
                            twRows == null ? List.of() : twRows,
                            Map.of()),
                    expected);
        } catch (RuntimeException e) {
            log.warn("今日交易雷達：市場 feature evidence 解析失敗（{}）：{}", market, e.getMessage());
            return TradingRadarMarketFeatureResolver.Evidence.empty(
                    market, decisionInstant, "市場數值來源讀取失敗");
        }
    }

    private static LocalDate sessionDate(DecisionClock clock) {
        return clock == null || clock.sessions() == null
                ? null : clock.sessions().targetCompletedSession();
    }

    /**
     * 組裝技術面欄位。**實際邏輯全在 {@link RadarInputAssembler}**，本方法只負責 production 專屬的
     * 「當日 live K 併入」與事件查詢；回測走同一支 assembler，故兩者不可能漂移（Task 273 的 273.2b）。
     */
    private RadarInputAssembler.Prepared prepareTechnicalBasis(
            Target target,
            RadarObservationResolver.AcceptedPrice acceptedPrice) {
        return prepareTechnicalBasis(target, acceptedPrice, null);
    }

    /** List context already owns the exact-pair adjustment window; never reread it in evaluation. */
    private RadarInputAssembler.Prepared prepareTechnicalBasis(
            Target target,
            RadarObservationResolver.AcceptedPrice acceptedPrice,
            List<StockDividendHistory> batchAdjustmentEvents) {
        if (acceptedPrice == null) return RadarInputAssembler.Prepared.EMPTY;
        // 技術序列一律取 indicatorSeriesRows（Task 319.3）：verifiedCompletedRows 帶著台股
        // provenance 白名單，那是 accepted price／quoteStatus 的判準，拿來當 MA／KD 的歷史序列
        // 會讓 close_source 為 null 的歷史列（Task 290 明定不回填）整批消失。
        List<StockPriceHistory> completedRows = acceptedPrice.indicatorSeriesRows();
        // Task 356.4b／356.4c：長序列（未做 241 截斷）供週K 聚合，日K 契約由下方顯式列數界定；
        // 只查一次、只還原一次，日K 與週K 共用同一份還原結果，不得分成兩次查詢或兩次還原。
        List<StockPriceHistory> seriesRows = acceptedPrice.weeklySeriesRows();
        Optional<PriceQueryService.LivePrice> liveOpt = acceptedPrice.liveAccepted()
                ? Optional.ofNullable(acceptedPrice.live()) : Optional.empty();
        List<StockPriceHistory> combined = new ArrayList<>(seriesRows);
        // AcceptedPrice is the immutable decision snapshot.  Do not call the calendar/live
        // predicate again here: a mutable holiday cache or quote state must not make the
        // accepted display price and technical sequence disagree within one decision.
        boolean liveAdded = acceptedPrice.liveAccepted() && liveOpt.isPresent();
        if (liveAdded) {
            combined.add(0, liveRow(target, liveOpt.orElseThrow()));
        }
        if (combined.isEmpty()) return RadarInputAssembler.Prepared.EMPTY;

        LocalDate fromDate = combined.get(combined.size() - 1).getTradingDate();
        LocalDate toDate = combined.get(0).getTradingDate();
        return assembler.prepare(
                combined,
                batchAdjustmentEvents == null
                        ? dividendHistoryRepo.findAdjustmentEvents(target.code(), target.market(), fromDate, toDate)
                        : batchAdjustmentEvents,
                liveAdded,
                // Task 356.4d-3：completedRowCount 仍是「日K 契約的完成列數」（上限 241），
                // 不得因為 combined 改成長序列就順手改傳長序列長度——那會讓 completedCloses
                // 由 ≤241 變成 ~499，撞上擴窗逐位不變的回歸斷言。
                completedRows.size(),
                RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS,
                acceptedPrice.value());
    }

    /**
     * Context binding for a v2 Redis technical pair.  It deliberately hashes
     * the actual adjusted daily and completed-week input basis rather than a
     * stock code/date shortcut: a dividend revision, an accepted live quote,
     * or a different weekly aggregation must invalidate a prior LOCAL result.
     */
    private static String technicalContextFingerprint(
            Target target,
            RadarObservationResolver.AcceptedPrice acceptedPrice,
            RadarInputAssembler.Prepared technical) {
        StringBuilder input = new StringBuilder("FUBON_RADAR_CONTEXT_V1\n");
        appendFingerprint(input, FubonRadarCompatibilityManifest.DECISION_INPUT_VERSION);
        appendFingerprint(input, target == null ? null : target.code());
        appendFingerprint(input, target == null ? null : target.market());
        appendFingerprint(input, acceptedPrice == null || acceptedPrice.tradingDate() == null
                ? null : acceptedPrice.tradingDate().toString());
        appendFingerprint(input, acceptedPrice == null ? null : acceptedPrice.updatedAt());
        appendFingerprint(input, acceptedPrice == null ? null : decimalFingerprint(acceptedPrice.value()));
        appendFingerprint(input, technical == null || technical.adjustedRowsDesc().size() <= technical.firstCompletedIndex()
                || technical.adjustedRowsDesc().get(technical.firstCompletedIndex()).getTradingDate() == null ? null
                : technical.adjustedRowsDesc().get(technical.firstCompletedIndex()).getTradingDate().toString());
        appendFingerprint(input, technical == null || technical.weeklyBarsDesc().isEmpty()
                || technical.weeklyBarsDesc().get(0).weekEndDate() == null ? null
                : technical.weeklyBarsDesc().get(0).weekEndDate().toString());
        appendFingerprint(input, technical != null && technical.distributionAdjusted() ? "1" : "0");
        appendFingerprint(input, technical != null && technical.weeklyDistributionAdjusted() ? "1" : "0");
        appendFingerprint(input, acceptedPrice != null && acceptedPrice.liveAccepted() ? "1" : "0");
        if (technical != null) {
            for (StockPriceHistory row : technical.adjustedRowsDesc()) {
                appendFingerprint(input, row == null || row.getTradingDate() == null ? null : row.getTradingDate().toString());
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.getOpenPrice()));
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.getHighPrice()));
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.getLowPrice()));
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.getClosePrice()));
                appendFingerprint(input, row == null || row.getVolume() == null ? null : row.getVolume().toString());
            }
            for (WeeklyBarAggregator.WeeklyBar row : technical.weeklyBarsDesc()) {
                appendFingerprint(input, row == null || row.weekEndDate() == null ? null : row.weekEndDate().toString());
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.open()));
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.high()));
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.low()));
                appendFingerprint(input, row == null ? null : decimalFingerprint(row.close()));
                appendFingerprint(input, row == null || row.volume() == null ? null : row.volume().toString());
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private static void appendFingerprint(StringBuilder target, String value) {
        String safe = value == null ? "<null>" : value;
        target.append(safe.length()).append(':').append(safe).append('\n');
    }

    private static String decimalFingerprint(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    /**
     * live K 是否併入序列：tradingDate 須等於「{@code market} 對應時區的今日」，且尚未等於完成列最新一筆。
     *
     * <p>「今日」必須以標的市場時區解讀 {@code decisionInstant}，不得用固定台北時區——美股在台北
     * 00:00–05:00 正是美東前一交易日下半場至收盤，此時美東日期＝台北日期−1，用台北時區比對
     * 恆失敗（Task 297，同類修法見 {@link TechnicalIndicatorService#computeAll}／Task 252）。
     * 刻意不用 {@link MarketZones#today}（內部讀系統時鐘）：本方法所有時間判斷須全部來自顯式傳入的
     * {@code decisionInstant}，維持背景重算（{@code recomputeAndStoreForOwner}）與 HTTP 路徑
     * 共用同一 {@code decisionInstant} 語意的純度契約。</p>
     *
     * <p>package-private 供同 package 測試以寫死的 {@code Instant} 直接呼叫——{@code assemble}／
     * {@code evaluateForNotification} 的 {@code decisionInstant = Instant.now()} 無 Clock 注入，
     * 服務層級測試無法控制時刻。</p>
     */
    boolean shouldAddLiveRow(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live,
            Instant decisionInstant,
            String market) {
        return RadarObservationResolver.shouldAddLiveRow(completedRows, live, decisionInstant, market,
                (java.util.function.Predicate<LocalDate>) day -> marketDataService.isTradingDay(market, day));
    }

    private StockPriceHistory liveRow(Target target, PriceQueryService.LivePrice live) {
        BigDecimal price = live.price();
        return StockPriceHistory.builder()
                .stockCode(target.code())
                .market(target.market())
                .tradingDate(LocalDate.parse(live.tradingDate()))
                .openPrice(live.openPrice() != null ? live.openPrice() : price)
                .highPrice(live.highPrice() != null ? live.highPrice() : price)
                .lowPrice(live.lowPrice() != null ? live.lowPrice() : price)
                .closePrice(price)
                .volume(live.volume())
                .build();
    }

    private void loadLatestHoldings(Map<String, Target> targets, Set<String> skippedNonTw, Long ownerId) {
        Optional<AssetSnapshot> snapshot = ownerId == null
                ? snapshotRepo.findLatestWithStocks()
                : snapshotRepo.findLatestWithStocksByOwnerUserId(ownerId);
        if (snapshot.isEmpty()) return;
        for (StockHolding holding : snapshot.get().getStocks()) {
            if (holding.getStockCode() == null || holding.getMarket() == null) continue;
            if (holding.getShares() == null || holding.getShares().signum() <= 0) continue;
            addTarget(targets, skippedNonTw, holding.getStockCode(), holding.getMarket(), true);
        }
    }

    private void loadWatchList(Map<String, Target> targets, Set<String> skippedNonTw, Long ownerId) {
        List<Object[]> rows = ownerId == null
                ? alertRepo.findDistinctStockCodeMarket()
                : alertRepo.findDistinctStockCodeMarketByOwnerUserId(ownerId);
        for (Object[] row : rows) {
            addTarget(targets, skippedNonTw, (String) row[0], (String) row[1], false);
        }
    }

    private void addTarget(Map<String, Target> targets, Set<String> skippedNonTw,
                           String rawCode, String rawMarket, boolean held) {
        if (rawCode == null || rawMarket == null) return;
        String code = rawCode.trim().toUpperCase();
        String market = rawMarket.trim();
        if (code.isEmpty() || market.isEmpty()) return;
        String key = code + '\0' + market;
        if (!TW_MARKET.equals(market) && !US_MARKET.equals(market)) {
            skippedNonTw.add(key);
            return;
        }
        // Only the Taiwan market index is infrastructure/regime data.  A
        // same-code instrument in another supported market remains a normal
        // Radar target and must retain its settings classification detail.
        if (isTaiwanMarketIndex(code, market)) return;
        Target existing = targets.get(key);
        targets.put(key, new Target(code, market, held || (existing != null && existing.held())));
    }

    private static boolean isTaiwanMarketIndex(String code, String market) {
        return TW_MARKET.equals(market) && TAIEX_CODE.equals(code);
    }

    /** 委派 {@link RadarInputAssembler}，避免同一段換算在兩處各自漂移（Task 273）。 */
    private TradingRadarRuleEngine.Indicators indicators(TechnicalIndicatorService.FullIndicators ind) {
        return assembler.indicators(ind);
    }

    /** 委派 {@link RadarInputAssembler}，避免同一段換算在兩處各自漂移（Task 273）。 */
    private BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        return assembler.changePercent(current, previous);
    }

    /**
     * 現行 ETF 折溢價（%）的 dated resolver。Redis 與 PostgreSQL 都必須對上決策日；
     * stale observation 只保留 provenance，不進規則因子，避免舊 NAV 觸發 3% veto。
     */
    private PremiumObservation etfPremiumObservation(
            String code,
            String market,
            Instant decisionInstant,
            RadarObservationResolver.DecisionSessions decisionSessions,
            boolean authoritativeCalendar) {
        // 美股 ETF 折溢價因子明確排除（Task 294.5）。
        if (US_MARKET.equals(market)) return PremiumObservation.unavailable(false);
        // The append-only observation path is strict: before the Taiwan close, today's
        // NAV is not a completed-session observation and must not enter the premium veto;
        // an unavailable authoritative calendar is also not permission to guess a weekday.
        LocalDate targetDate = etfNavObservationRepository != null
                ? authoritativeCalendar
                ? decisionSessions == null ? null : decisionSessions.targetCompletedSession()
                : strictCompletedTaiwanSession(decisionInstant)
                : currentTwTradingDay(decisionInstant);
        if (targetDate == null) return PremiumObservation.unavailable(true);
        try {
            if (etfNavObservationRepository != null) {
                List<EtfNavObservation> observations = etfNavObservationRepository
                        .findObservationStreamThrough(code, market, decisionInstant);
                TradingRadarPremiumResolver.DecisionObservation resolved =
                        TradingRadarPremiumResolver.resolveAsOf(
                                market, targetDate, decisionInstant, observations);
                List<BigDecimal> history = TradingRadarPremiumResolver.premiumHistoryAsOf(
                        market, targetDate, decisionInstant, observations, ETF_PREMIUM_LOOKBACK_DAYS);
                return new PremiumObservation(
                        resolved.value(), resolved.asOfDate(), resolved.source(), resolved.stale(), history);
            }
            // Compatibility fallback for tests/older deployments without the append-only
            // observation repository.  New production wiring always uses the strict branch.
            var live = priceQueryService.getEtfNav(code, market);
            var exact = etfNavHistoryRepo.findPremiumObservationOnDate(code, market, targetDate);
            List<com.steven.assets.model.EtfNavHistory> prior =
                    exact.isPresent() ? List.of() : etfNavHistoryRepo.findRecentPremiumObservationsAsOf(
                            code, market, targetDate,
                            org.springframework.data.domain.PageRequest.of(0, 1));
            TradingRadarPremiumResolver.Observation resolved = TradingRadarPremiumResolver.resolve(
                    targetDate, live.orElse(null), exact.orElse(null), prior);
            return new PremiumObservation(
                    resolved.value(), resolved.asOfDate(), resolved.source(), resolved.stale(), List.of());
        } catch (Exception e) {
            log.warn("ETF 折溢價取得失敗（{}／{}）：{}", code, market, e.getMessage());
            return PremiumObservation.unavailable(true);
        }
    }

    /** Pure list-context projection; the observation stream was fetched by the exact-pair reader. */
    private PremiumObservation etfPremiumObservationFromBatch(
            String market, Instant decisionInstant, LocalDate targetDate, List<EtfNavObservation> observations) {
        if (US_MARKET.equals(market)) return PremiumObservation.unavailable(false);
        if (targetDate == null) return PremiumObservation.unavailable(true);
        try {
            List<EtfNavObservation> rows = observations == null ? List.of() : observations;
            TradingRadarPremiumResolver.DecisionObservation resolved = TradingRadarPremiumResolver.resolveAsOf(
                    market, targetDate, decisionInstant, rows);
            return new PremiumObservation(resolved.value(), resolved.asOfDate(), resolved.source(), resolved.stale(),
                    TradingRadarPremiumResolver.premiumHistoryAsOf(
                            market, targetDate, decisionInstant, rows, ETF_PREMIUM_LOOKBACK_DAYS));
        } catch (RuntimeException unavailable) {
            return PremiumObservation.unavailable(true);
        }
    }

    /**
     * Resolve the completed Taiwan session for the dated NAV path.  The calendar is tri-state:
     * {@code empty} means UNKNOWN and closes the premium evidence gate.  A session that is still
     * intraday is deliberately excluded, even when Redis/DB already contains a same-date NAV.
     */
    /* package */ LocalDate strictCompletedTaiwanSession(Instant decisionInstant) {
        if (decisionInstant == null || marketDataService == null) return null;
        LocalDate current = decisionInstant.atZone(MarketZones.TW_ZONE).toLocalDate();
        java.util.Optional<Boolean> today = marketDataService.isTwTradingDayKnown(current);
        if (today == null || today.isEmpty()) return null;
        if (today.get() && !decisionInstant.atZone(MarketZones.TW_ZONE).toLocalTime()
                .isBefore(MarketZones.closeTime(TW_MARKET))) return current;
        for (int i = 1; i <= 14; i++) {
            LocalDate prior = current.minusDays(i);
            java.util.Optional<Boolean> known = marketDataService.isTwTradingDayKnown(prior);
            if (known == null || known.isEmpty()) return null;
            if (known.get()) return prior;
        }
        return null;
    }

    /** 台股 Redis navAsOf 可能是 yyyyMMdd HH:mm:ss，美股為 ISO date；兩者都轉成 LocalDate。 */
    /** 現行折溢價在自身歷史分布中的百分位；歷史樣本不得晚於 premium observation date。 */
    private BigDecimal etfPremiumPercentile(
            String code, String market, BigDecimal current, LocalDate asOfDate,
            List<BigDecimal> strictHistory, boolean batchOnly) {
        if (current == null || asOfDate == null) return null;
        try {
            List<BigDecimal> history = !batchOnly && (strictHistory == null || strictHistory.isEmpty())
                    ? etfNavHistoryRepo.findRecentPremiumPctAsOf(
                            code, market, asOfDate,
                            org.springframework.data.domain.PageRequest.of(0, ETF_PREMIUM_LOOKBACK_DAYS))
                    : strictHistory == null ? List.of() : strictHistory;
            if (history == null || history.size() < ETF_PREMIUM_MIN_SAMPLES) return null;
            long atOrBelow = history.stream().filter(v -> v != null && v.compareTo(current) <= 0).count();
            return BigDecimal.valueOf(100.0 * atOrBelow / history.size())
                    .setScale(1, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("ETF 折溢價分位計算失敗（{}／{}）：{}", code, market, e.getMessage());
            return null;
        }
    }

    /**
     * {@code marketWeekly(...)} 的 null 防護——與既有 {@code usContext == null} 三元防護同一理由，
     * 不可省：單元測試把 {@code RadarInputAssembler} 宣告為 {@code mock(...)}，未 stub 的方法回
     * {@code null}，直接解參考產生的 NPE 會被 {@code buildMarket}／{@code buildUsMarket} 的 catch
     * 吞成 {@code incompleteMarket()}——<b>不報錯卻讓整組大盤變 DATA_INCOMPLETE</b>，
     * 連帶關掉全部個股的買進閘門。
     */
    private static RadarInputAssembler.MarketWeekly nonNullWeekly(
            RadarInputAssembler.MarketWeekly weekly) {
        return weekly == null ? RadarInputAssembler.MarketWeekly.EMPTY : weekly;
    }

    /** {@code null} action 代表該軌未供給；不得以 {@code NO_TRADE} 冒充「已判定不交易」。 */
    private static String actionName(TradingRadarRuleEngine.Action action) {
        return action == null ? null : action.name();
    }

    /**
     * 引擎回傳的 List 欄位防護：舊相容建構式建立的 {@code StockResult} 其 swing 兩個 List 為
     * {@code null}（record 無 compact constructor 正規化），直接 {@code new ArrayList<>(null)} 會 NPE。
     */
    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    // ─── Task 356.10b／356.10c／356.11a：大盤週K 與日K 棒的中性映射與 DTO 投影 ───────────

    /**
     * 台股大盤列 → 中性 OHLCV（Task 356.10b）。
     *
     * <p>{@code trade_volume} 必須帶上，否則大盤週量比恆為 null，而個股路徑算得出值——
     * 那正是 Task 323 修過的「線上生效、回測不生效」同型缺陷。</p>
     */
    private static List<StockPriceHistory> twIndexOhlcvDesc(List<TwseIndexDailyHistory> rowsDesc) {
        if (rowsDesc == null) return List.of();
        return rowsDesc.stream()
                .filter(r -> r != null && r.getTradingDate() != null)
                .map(r -> StockPriceHistory.builder()
                        .stockCode(TAIEX_CODE).market(TW_MARKET)
                        .tradingDate(r.getTradingDate())
                        .openPrice(r.getOpenPoint()).highPrice(r.getHighPoint())
                        .lowPrice(r.getLowPoint()).closePrice(r.getClosePoint())
                        .volume(r.getTradeVolume())
                        .build())
                .toList();
    }

    /** 美股大盤列 → 中性 OHLCV（Task 356.10b）；與台股共用同一支週K 聚合。 */
    private static List<StockPriceHistory> usIndexOhlcvDesc(List<UsIndexDailyHistory> rowsDesc) {
        if (rowsDesc == null) return List.of();
        return rowsDesc.stream()
                .filter(r -> r != null && r.getTradingDate() != null)
                .map(r -> StockPriceHistory.builder()
                        .stockCode(IXIC_CODE).market(US_MARKET)
                        .tradingDate(r.getTradingDate())
                        .openPrice(r.getOpenPoint()).highPrice(r.getHighPoint())
                        .lowPrice(r.getLowPoint()).closePrice(r.getClosePoint())
                        .volume(r.getVolume())
                        .build())
                .toList();
    }

    /** 序列第 0 筆（最新完成日）的大盤 K 棒；序列為空時回 null（引擎會揭露不採計）。 */
    private static TradingRadarRuleEngine.CandleInput marketCandle(List<StockPriceHistory> rowsDesc) {
        if (rowsDesc == null || rowsDesc.isEmpty()) return null;
        StockPriceHistory row = rowsDesc.get(0);
        return new TradingRadarRuleEngine.CandleInput(
                row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice());
    }

    /**
     * 最新完成日 K 棒 → DTO（Task 356.11a）。
     *
     * <p>三個分量一律呼叫 {@code TradingRadarRuleEngine} 的 public static 純函數，
     * <b>與引擎計分共用同一支實作</b>，不得在此另寫一份公式。回傳的是<b>原值</b>
     * （未 clamp、未線性轉換）；全幅非正時三者皆為 null，畫面顯示 {@code —}。</p>
     */
    private static TradingRadarDto.DailyCandle toDailyCandleDto(
            TradingRadarRuleEngine.CandleInput candle, LocalDate asOfDate) {
        if (candle == null) return null;
        return new TradingRadarDto.DailyCandle(
                scale2(candle.open()), scale2(candle.high()),
                scale2(candle.low()), scale2(candle.close()),
                TradingRadarRuleEngine.closePosition(candle.high(), candle.low(), candle.close()),
                TradingRadarRuleEngine.bodyDirection(
                        candle.open(), candle.high(), candle.low(), candle.close()),
                TradingRadarRuleEngine.lowerShadowRatio(
                        candle.open(), candle.high(), candle.low(), candle.close()),
                asOfDate == null ? null : asOfDate.toString());
    }

    /**
     * 週K → DTO（Task 356.11a）；個股與兩組大盤共用同一支，不得各寫一份。
     *
     * <p>{@code dif}／{@code macd} 是<b>純揭露欄</b>，唯一來源為同一次
     * {@code computeFromSeries(週K 序列)} 產出的 {@code FullIndicators}（Task 356.4h）——
     * {@code WeeklyInput} 刻意只帶 {@code osc}（進評分的只有它），不得為了揭露再算第二次。</p>
     *
     * <p>完成週不足 {@code MIN_COMPLETED_WEEKS} 時 {@code weekly.candle()} 為 null：
     * 此時整組指標與週K 棒欄位一律留白，只保留 {@code weekEndDate} 與 {@code completedWeeks}，
     * 供畫面與匯出寫出「目前 N 根」。<b>不得以 0 冒充缺值。</b></p>
     */
    private static TradingRadarDto.WeeklyIndicators toWeeklyDto(
            TradingRadarRuleEngine.WeeklyInput weekly,
            List<WeeklyBarAggregator.WeeklyBar> barsDesc,
            TechnicalIndicatorService.FullIndicators weeklyIndicators) {
        if (weekly == null) return null;
        TradingRadarRuleEngine.CandleInput candle = weekly.candle();
        TechnicalIndicatorService.ExtendedIndicators extended =
                weeklyIndicators == null ? null : weeklyIndicators.extended();
        Long volume = candle == null || barsDesc == null || barsDesc.isEmpty()
                ? null : barsDesc.get(0).volume();
        return new TradingRadarDto.WeeklyIndicators(
                weekly.weekEndDate() == null ? null : weekly.weekEndDate().toString(),
                weekly.completedWeeks(),
                candle == null ? null : scale2(candle.open()),
                candle == null ? null : scale2(candle.high()),
                candle == null ? null : scale2(candle.low()),
                candle == null ? null : scale2(candle.close()),
                volume,
                weekly.ma5(), weekly.ma10(), weekly.ma20(),
                weekly.k(), weekly.d(), weekly.j9(),
                candle == null || extended == null ? null : scale2(extended.dif()),
                candle == null || extended == null ? null : scale2(extended.macd()),
                weekly.osc(),
                weekly.rsi5(), weekly.rsi10(),
                weekly.bias10(), weekly.bias20(),
                weekly.volumeRatio(), weekly.changePercent(),
                candle == null ? null
                        : TradingRadarRuleEngine.closePosition(
                                candle.high(), candle.low(), candle.close()),
                candle == null ? null
                        : TradingRadarRuleEngine.bodyDirection(
                                candle.open(), candle.high(), candle.low(), candle.close()));
    }

    /** 顯示／匯出一律 2 位小數；缺值為 null，不得顯示 0（Task 356.11d）。 */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 指標服務的擴充指標 → DTO（Task 281）。
     *
     * <p><b>只搬這 14 個值。</b>{@code FullIndicators} 的 MA／K／D 一律維持既有取法
     *（{@code ind.monthlyMa()} 等），不得改由這裡供給——同一列出現兩個 MA 來源就是漂移的開端。</p>
     */
    private static TradingRadarDto.ExtendedIndicators toDto(
            TechnicalIndicatorService.ExtendedIndicators e) {
        if (e == null) return null;
        return new TradingRadarDto.ExtendedIndicators(
                e.j9(), e.k3d2(), e.rsv(),
                e.ema12(), e.ema26(), e.dif(), e.macd(), e.osc(),
                e.rsi5(), e.rsi10(),
                e.bias10(), e.bias20(), e.b10b20(),
                e.wr9());
    }

    /**
     * 清單排序 key（Task 356.1d）：三軌分數取最大值——V15 起由
     * {@code max(shortScore, score)} 改為 {@code max(shortScore, swingScore, score)}。
     *
     * <p>缺值排最後（呼叫端以 {@code Comparator.nullsLast(reverseOrder())} 套用），
     * 再以股票代碼穩定排序；「缺值排最後」的既有語意不變。三軌<b>全部</b>缺值才回 null，
     * 只要任一軌有分數就以該分數參與排序——否則新增中間軌反而會讓
     * 「只有 swing 軌算得出分數」的標的整批沉到清單最後。</p>
     *
     * <p>package-private 供同 package 測試直接呼叫（比照 {@code shouldAddLiveRow}）：
     * 走整條 {@code assemble()} 才能觀測排序，得先鋪一整份個股 fixture，
     * 與本方法要守的不變式無關。</p>
     */
    /* package */ static Integer bestScore(TradingRadarDto.StockDecision decision) {
        // 不得寫成 List.of(...)：三軌分數皆可為 null，List.of 對 null 元素直接擲 NPE。
        Integer best = maxScore(decision.shortScore(), decision.swingScore());
        return maxScore(best, decision.score());
    }

    private static Integer bestListScore(TradingRadarDto.ListStock decision) {
        Integer best = maxScore(decision.shortScore(), decision.swingScore());
        return maxScore(best, decision.score());
    }

    /** 兩個可為 null 的分數取大者；兩者皆 null 時回 null（缺值不得以 0 冒充）。 */
    private static Integer maxScore(Integer left, Integer right) {
        if (left == null) return right;
        if (right == null) return left;
        return Math.max(left, right);
    }

    private static LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value.substring(0, Math.min(10, value.length()))); }
        catch (RuntimeException e) { return null; }
    }

    /* package */ List<LocalDate> futureSessions(String market, Instant decisionInstant) {
        LocalDate start = decisionInstant.atZone(MarketZones.resolve(market)).toLocalDate();
        List<LocalDate> sessions = new ArrayList<>();
        // Resolver counts sessions strictly after decisionDate; do not spend one
        // slot on the current session or silently turn the 20th session into a
        // +20 calendar-day fallback.
        for (int i = 1; i <= 90 && sessions.size() < 20; i++) {
            LocalDate date = start.plusDays(i);
            try {
                // TW's external holiday calendar has a typed-known API.  An empty
                // calendar is unavailable, not proof that every weekday is a
                // session; stop so the downstream resolver returns PARTIAL/MISSING.
                java.util.Optional<Boolean> known = TW_MARKET.equals(market)
                        ? marketDataService.isTwTradingDayKnown(date)
                        : java.util.Optional.of(marketDataService.isTradingDay(market, date));
                if (known.isEmpty()) return List.of();
                if (known.get()) sessions.add(date);
            } catch (Exception unavailable) {
                // Do not infer calendar-day sessions after a calendar failure.
                // A short/empty result is deliberately typed as incomplete by
                // DividendEventEvidenceResolver instead of being a hidden fallback.
                return List.of();
            }
        }
        return List.copyOf(sessions);
    }

    /** List-only future sessions: cache miss is unavailable, not a proxy refresh or weekday guess. */
    /* package */ List<LocalDate> futureSessionsCachedOnly(String market, Instant decisionInstant) {
        if (market == null || decisionInstant == null || marketDataService == null) return List.of();
        LocalDate start = decisionInstant.atZone(MarketZones.resolve(market)).toLocalDate();
        try {
            return marketDataService.futureTradingSessionsCachedOnly(market, start, 20, 90)
                    .orElse(List.of());
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private MarketState incompleteMarket(String message) {
        TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE.name(),
                regimeLabel(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE),
                null,
                false,
                true,
                null,
                null, null, "CLOSE_PENDING",
                null, null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                List.of(),
                List.of(message),
                false,
                null,
                null);
        // 讀不到大盤時保守視為 stale：買進閘門一律關閉。
        return new MarketState(summary, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE, true);
    }

    private TradingRadarDto.SettingsClassification resolveSettingsClassification(
            Stock stock, String code, String market, String name) {
        if (settingsClassificationResolver == null) return null;
        return TradingRadarDto.SettingsClassification.from(
                settingsClassificationResolver.resolve(stock, code, market, name));
    }

    private TradingRadarDto.StockDecision incompleteStock(
            Target target,
            String name,
            String assetClass,
            TradingRadarDto.SettingsClassification settingsClassification,
            String message) {
        return new TradingRadarDto.StockDecision(
                target.code(), name, target.market(), assetClass, false, target.held(),
                TradingRadarRuleEngine.Action.NO_TRADE.name(),
                actionLabel(TradingRadarRuleEngine.Action.NO_TRADE),
                null,
                TradingRadarRuleEngine.CounterTrendState.NONE.name(),
                counterTrendLabel(TradingRadarRuleEngine.CounterTrendState.NONE),
                List.of(), List.of(),
                false,
                null, null, "CLOSE_PENDING", null, null,
                null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                null,
                null,
                List.of(), List.of(message),
                TradingRadarRuleEngine.KdHeat.NORMAL.name(),
                TradingRadarRuleEngine.TimingState.NEUTRAL.name(),
                timingLabel(TradingRadarRuleEngine.TimingState.NEUTRAL),
                null, // ma60BiasPercent
                null, // week52Position
                null, // weeklyMa
                null, // etfPremiumPct
                null, // etfPremiumPercentile
                null, // extendedIndicators
                null, // shortAction
                null, // shortActionLabel
                null, // shortScore
                List.of(), // shortReasons
                List.of(message), // shortRisks
                false, // horizonConflict
                null, // volumeRatio
                null, // fxAsOfDate
                false, // profitTakingConfirmed
                null, // fundamental
                TradingRadarDto.RadarEvidence.EMPTY.withSettingsClassification(settingsClassification),
                null, null, null, null, null, null, null, null, List.of(),
                null, // etfPremiumLivePct（Task 320）
                null, // etfPremiumLiveNavAsOf（Task 320）
                // Task 356.11b：早退分支的 swing 四欄與兩組新指標一律缺值——三軌都不交易。
                null, // swingAction
                null, // swingActionLabel
                null, // swingScore
                List.of(), // swingReasons
                List.of(message), // swingRisks
                null, // swingDownsideRisk
                null, // swingEvidenceConfidence
                null, // swingRiskCoverage
                null, // swingCandidateAction
                null, // dailyCandle
                null, // weeklyIndicators
                null); // technicalResolution
    }

    private String regimeLabel(TradingRadarRuleEngine.MarketRegime regime) {
        return switch (regime) {
            case RISK_ON -> "偏多／可尋找機會";
            case NEUTRAL -> "中性／等待確認";
            case RISK_OFF -> "偏空／降低風險";
            case DATA_INCOMPLETE -> "資料不足／今日不交易";
        };
    }

    /** 進場時機的顯示文案（Task 264）。 */
    public static String timingLabel(TradingRadarRuleEngine.TimingState state) {
        if (state == null) return "—";
        return switch (state) {
            case EXTREME_OVERBOUGHT -> "極端超買";
            case OVERBOUGHT -> "偏貴";
            case NEUTRAL -> "中性";
            case OVERSOLD -> "偏便宜";
            case EXTREME_OVERSOLD -> "極端超賣";
        };
    }

    public static String actionLabel(TradingRadarRuleEngine.Action action) {
        return switch (action) {
            case BUY_CANDIDATE -> "買進候選";
            case ADD_CANDIDATE -> "加碼候選";
            case TRIAL_BUY -> "分批試單";
            case HOLD -> "續抱";
            case WATCH -> "觀察";
            case HOLD_CAUTION -> "續抱但提高警戒";
            case WAIT -> "觀望";
            case REDUCE_CANDIDATE -> "減碼候選";
            case EXIT_CANDIDATE -> "出場候選";
            case AVOID -> "暫不買進";
            case NO_TRADE -> "今日不交易";
        };
    }

    public static String counterTrendLabel(TradingRadarRuleEngine.CounterTrendState state) {
        return switch (state) {
            case NONE -> "—";
            case OVERSOLD_WATCH -> "超跌觀察";
            case TRIAL_CANDIDATE -> "逆勢試單候選";
        };
    }
}
