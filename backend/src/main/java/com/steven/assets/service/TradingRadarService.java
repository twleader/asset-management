package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.model.Stock;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.EtfNavObservationRepository;
import com.steven.assets.model.StockHolding;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
                dividendEventEvidenceRepository, treasuryYieldService, null, null);
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
                bondYieldBetaEvidencePort, null);
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
                bondYieldBetaEvidencePort, stockStyleThresholdProvider, null);
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
            EtfNavObservationRepository etfNavObservationRepository) {
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

        // Task 303：同一次 assemble() 內同幣別的 FxContext 只解析一次（20 檔美股原本各自重查 5 年
        // 匯率）。stream 目前循序執行，但用 ConcurrentHashMap 防未來並行化踩雷。
        Map<String, TradingRadarMarketContextService.FxContext> fxCache = new java.util.concurrent.ConcurrentHashMap<>();
        List<TradingRadarDto.StockDecision> decisions = targets.values().stream()
                .filter(t -> (TW_MARKET.equals(t.market()) || US_MARKET.equals(t.market()))
                        && !TAIEX_CODE.equals(t.code()))
                .map(t -> buildStock(t, regimeFor(t.market(), twMarket, usMarket),
                        staleFor(t.market(), twMarket, usMarket),
                        marketSummaryFor(t.market(), twMarket, usMarket), decisionInstant, fxCache,
                        decisionClocks.getOrDefault(t.market(), new DecisionClock(null, false)),
                        decisionClocks.getOrDefault(US_MARKET, new DecisionClock(null, false))))
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
        return buildStock(new Target(stockCode, market, held), snapshot.regime(), snapshot.stale(),
                snapshot.summary(), snapshot.decisionInstant(), new java.util.HashMap<>(),
                new DecisionClock(snapshot.decisionSessions(), snapshot.authoritativeCalendar()),
                new DecisionClock(snapshot.usDecisionSessions(), snapshot.usAuthoritativeCalendar()));
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
        try {
            List<TwseIndexDailyHistory> rows = twseRepo.findTopNByOrderByTradingDateDesc(241);
            if (context.marketAsOfDate() != null) {
                rows = rows.stream()
                        .filter(r -> !r.getTradingDate().isAfter(context.marketAsOfDate()))
                        .limit(241)
                        .toList();
            }
            List<BigDecimal> closes = rows.stream().map(TwseIndexDailyHistory::getClosePoint).toList();
            LocalDate currentTradingDay = currentTwTradingDay(decisionInstant);
            LocalDate latestEodDate = rows.isEmpty() ? null : rows.get(0).getTradingDate();
            boolean todayEodPresent = latestEodDate != null && latestEodDate.equals(currentTradingDay);

            Optional<PriceQueryService.LivePrice> liveOpt = priceQueryService.getLive(TAIEX_CODE, TW_MARKET);
            boolean liveFreshToday = !todayEodPresent && liveOpt.isPresent()
                    && liveOpt.get().tradingDate() != null
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
                            context.usTechAvailable()));

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
                    context.usTechAvailable());
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
     * <p>{@code MarketInput} 的後六個參數（跨市場量能／美股科技日報酬）全部傳 {@code null}／{@code false}：
     * 那三欄（nasdaqChangePercent／soxChangePercent／usTechCompositePercent）的語意是「台股股票的跨市場
     * 領先訊號」，本身即為 IXIC 走勢的一部分，若原封不動餵給「大盤即是 IXIC」的美股組會重複計分。</p>
     *
     * <p>Task 323：{@code MarketSummary} 的大盤量能三欄改由既有的
     * {@link TradingRadarMarketContextService#resolveMarketFromRows} 供給（同一支 API 也是
     * {@code BacktestService} 走的那支），線上雷達與回測因此不會分岔出第二份美股量能計算。
     * <b>但 {@code MarketInput} 的量能兩欄與 {@code completedChangePercent} 仍維持 {@code null}</b>：
     * 那三欄在 {@link TradingRadarRuleEngine} 內是<b>進 regime 分數</b>的，本任務只補
     * evidence／信心度所讀的 {@code MarketSummary}，不動美股的 regime 分數與買進閘門
     * （台股端 {@link #buildMarket} 是把同一份 context 一併灌進 {@code MarketInput}，
     * 此處刻意不鏡像照抄）。</p>
     */
    private MarketState buildUsMarket(Instant decisionInstant) {
        try {
            List<UsIndexDailyHistory> rows =
                    usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(IXIC_CODE, 241);
            // Task 323：用同一批已讀進來的 IXIC 列走既有 V13 解析，不在本類別另寫一份 ratio 計算。
            // 回傳型別是 TradingRadarMarketContextService.MarketContext（accessor 為 marketVolumeRatio()／
            // marketAsOfDate()），與 buildStock 內組 evidence 用的 TradingRadarEvidenceConfidenceResolver
            // .MarketContext 同名不同型，不得混用。
            TradingRadarMarketContextService.MarketContext usContext =
                    marketContextService.resolveMarketFromRows(US_MARKET, decisionInstant, List.of(), rows);
            List<BigDecimal> closes = rows.stream().map(UsIndexDailyHistory::getClosePoint).toList();
            BigDecimal price = closes.isEmpty() ? null : closes.get(0);
            BigDecimal changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;

            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAllForNasdaq();
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
            TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            price,
                            changePercent,
                            indicators(ind),
                            c60,
                            c240,
                            // completedChangePercent／marketVolumeRatio／marketTurnoverRatio
                            // 一律維持 null（Task 323.2）：這三欄在 TradingRadarRuleEngine 內會進 regime
                            // 分數（averageAvailable(...) → score ±8／±10／±3），把上面解出的 usContext
                            // 灌進來會直接翻動美股個股的 regime 與買進閘門。本任務只補 MarketSummary。
                            null,
                            null,
                            null,
                            // 跨市場領先訊號四欄維持 null／false（Task 294 既有理由，見本方法 javadoc）。
                            null,
                            null,
                            null,
                            false));

            LocalDate latestEodDate = rows.isEmpty() ? null : rows.get(0).getTradingDate();
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
                    false);
            return new MarketState(summary, result.regime(), stale);
        } catch (Exception e) {
            log.warn("今日交易雷達：美股（IXIC）大盤資料組裝失敗", e);
            return incompleteMarket("讀取美股大盤資料失敗，美股個股暫停產生交易訊號。");
        }
    }

    private TradingRadarDto.StockDecision buildStock(
            Target target,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale,
            TradingRadarDto.MarketSummary marketSummary,
            Instant decisionInstant,
            Map<String, TradingRadarMarketContextService.FxContext> fxCache,
            DecisionClock decisionClock,
            DecisionClock usDecisionClock) {
        Optional<Stock> stock = stockRepo.findByCodeAndMarket(target.code(), target.market());
        String name = stock.map(Stock::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(target.code());
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
            DividendEventEvidenceResolver.Resolution distribution = dividendEventEvidenceRepository.resolve(
                    target.code(), target.market(), decisionInstant,
                    futureSessions(target.market(), decisionInstant));
            // 抓 250 是取數緩衝，不是精算出來的餘裕（Task 319.4）：序列的契約上限固定為 241
            // （confirm(closes, 240) 需要 241 根完成收盤），由 RadarObservationResolver 截斷。
            // 這裡多抓 9 列是讓兩種剔除有名額可遞補——(1) findRecentN 沒有日期上界，盤中
            // completedSession 為前一交易日時 DB 的當日列會先佔掉一個名額再被剔除；
            // (2) completedSession 當日列存在但 close_source 未命中白名單。任一發生就只剩 240 根，
            // ma240Confirmation 回 UNAVAILABLE 而仍輸出 NO_TRADE，且五個指標欄位全非 null、偽裝成修好。
            List<StockPriceHistory> rawRows = priceHistoryRepo.findRecentN(target.code(), target.market(), 250);
            Optional<PriceQueryService.LivePrice> rawLive =
                    priceQueryService.getLive(target.code(), target.market());
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
            RadarInputAssembler.Assembled technical = prepareTechnicalData(
                    target, acceptedPrice);
            BigDecimal displayPrice = acceptedPrice.value();
            // ── Task 320：即時折溢價（純揭露欄，不進任何規則） ────────────────────────────
            // 「同一 tick」在這裡的正確意思是「<b>價只取一次</b>」：現價與淨值本來就是兩個 Redis key
            // （price:{market}:{code} vs price:etfnav:{market}:{code}），LivePrice 裡沒有 nav 欄位，
            // 不存在「一次讀到價又讀到淨值」的路徑。讀淨值必然是第二次 Redis 讀取，那不在禁止之列；
            // 禁止的是為折溢價<b>另打一次取價</b>——那會讓「現價」與「折溢價」落在不同 tick，
            // 使用者拿畫面數字驗算 (price−nav)/nav 就會兜不攏。故此處一律沿用同一列已組好的 displayPrice。
            // getEtfNav 查無回 Optional.empty() 是正常情形（個股本來就沒有淨值），不記 warn 洗版。
            PriceQueryService.EtfNav livePremiumNav =
                    priceQueryService.getEtfNav(target.code(), target.market()).orElse(null);
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

            TechnicalIndicatorService.FullIndicators ind = technical.indicators();
            TradingRadarRuleEngine.Confirmation c20 = technical.ma20Confirmation();
            TradingRadarRuleEngine.Confirmation c60 = technical.ma60Confirmation();
            TradingRadarRuleEngine.Confirmation c240 = technical.ma240Confirmation();
            String currency = profile.underlyingCurrency();
            TradingRadarMarketContextService.FxContext fx = currency == null || TWD.equals(currency)
                    ? TradingRadarMarketContextService.FxContext.EMPTY
                    : fxCache.computeIfAbsent(currency, c -> marketContextService.resolveFx(c, decisionInstant));
            BigDecimal fxPct = fx.percentile();
            BigDecimal ma60Bias = technical.ma60BiasPercent();
            BigDecimal ma240Bias = technical.ma240BiasPercent();
            BigDecimal week52Pos = technical.week52Position();
            PremiumObservation premium = etfPremiumObservation(
                    target.code(), target.market(), decisionInstant,
                    decisionClock == null ? null : decisionClock.sessions(),
                    decisionClock != null && decisionClock.authoritative());
            BigDecimal etfPremiumPct = premium.value();
            BigDecimal etfPremiumPercentile = etfPremiumPercentile(
                    target.code(), target.market(), etfPremiumPct, premium.asOfDate(), premium.history());
            FundamentalAnalysisService.Resolved fundamental = fundamentalAnalysisService.resolve(
                    target.code(), name, target.market(), decisionInstant, profile);
            // Keep compatibility with older test/adapters that only implement the four-field
            // resolver; a null adapter response is never treated as complete evidence.
            if (fundamental == null) {
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
                            fundamental.input()));
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
            TradingRadarMarketFeatureResolver.Evidence marketFeatures =
                    resolveMarketFeatureEvidence(target.market(), decisionInstant,
                            decisionClock, usDecisionClock);
            BondYieldBetaResolver.Result bondYieldBeta = resolveBondYieldBeta(
                    target.code(), target.market(), profile, decisionInstant,
                    RuleParameters.v12Default());
            TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation =
                    resolveTreasuryRateObservation(profile, decisionInstant, bondYieldBeta);
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
            TradingRadarEvidenceGate.GatedActions gated = TradingRadarEvidenceGate.apply(
                    result.action(), result.shortAction(), target.held(), profile, evidence);

            List<String> reasons = new ArrayList<>();
            if (technical.distributionAdjusted()) {
                reasons.add("MA／KD、兩日確認與規則漲跌已使用還原權息／分割價，避免把配息缺口或分割跳空誤判為趨勢跌破。 ");
            }
            reasons.addAll(result.reasons());
            reasons.addAll(gated.reasons());
            List<String> risks = new ArrayList<>(result.risks());
            List<String> shortReasons = new ArrayList<>();
            if (technical.distributionAdjusted()) {
                shortReasons.add("MA／KD、擴充指標與相對量已使用同一份還原權息／分割序列。 ");
            }
            shortReasons.addAll(result.shortReasons());
            List<String> shortRisks = new ArrayList<>(result.shortRisks());
            shortRisks.addAll(gated.reasons());
            if (!TWD.equals(currency) && fx.asOfDate() == null) {
                String missingFx = "精確完成日匯率不可得，外幣債券 ETF 的匯率因子本日缺值。 ";
                risks.add(missingFx);
                shortRisks.add(missingFx);
            }

            String updatedAt = acceptedPrice.updatedAt();
            String asOf = acceptedPrice.tradingDate() == null
                    ? null : acceptedPrice.tradingDate().toString();
            String quoteStatus = acceptedPrice.quoteStatus();
            return new TradingRadarDto.StockDecision(
                    target.code(),
                    name,
                    target.market(),
                    assetClass,
                    technical.distributionAdjusted(),
                    target.held(),
                    gated.mediumAction().name(),
                    actionLabel(gated.mediumAction()),
                    result.score(),
                    result.counterTrend().state().name(),
                    counterTrendLabel(result.counterTrend().state()),
                    result.counterTrend().reasons(),
                    result.counterTrend().risks(),
                    result.action() != TradingRadarRuleEngine.Action.NO_TRADE,
                    displayPrice,
                    displayChangePercent,
                    quoteStatus,
                    updatedAt,
                    asOf,
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c20.name(),
                    c60.name(),
                    c240.name(),
                    fxPct,
                    currency,
                    List.copyOf(reasons),
                    List.copyOf(risks),
                    result.kdHeat().name(),
                    result.timingState().name(),
                    timingLabel(result.timingState()),
                    ma60Bias,
                    week52Pos,
                    ind.weeklyMa(),
                    etfPremiumPct,
                    etfPremiumPercentile,
                    toDto(ind.extended()),
                    gated.shortAction().name(),
                    actionLabel(gated.shortAction()),
                    result.shortScore(),
                    List.copyOf(shortReasons),
                    List.copyOf(shortRisks),
                    result.horizonConflict(),
                    technical.volumeRatio(),
                    fx.asOfDate() == null ? null : fx.asOfDate().toString(),
                    result.profitTakingConfirmed(),
                    fundamental.snapshot(),
                    TradingRadarDto.RadarEvidence.withConfidence(
                            asOf,
                            acceptedPrice.source(),
                            acceptedPrice.quality().name(),
                            acceptedPrice.liveAccepted(),
                            technical.returnStdDev60Ratio(),
                            technical.volatility60().asOfDate() == null
                                    ? null : technical.volatility60().asOfDate().toString(),
                            technical.volatility60().source(),
                            premium.asOfDate() == null ? null : premium.asOfDate().toString(),
                            premium.source(),
                            premium.stale(),
                            TradingRadarDto.AssetProfile.from(profile),
                            gated.reasons(), evidence,
                            gated.candidateMediumAction().name(), gated.candidateShortAction().name(),
                            distribution, rateObservation.context(),
                            TradingRadarDto.NormalizedBiasEvidence.from(result.normalizedBias()),
                            TradingRadarDto.NormalizedBiasEvidence.from(result.shortNormalizedBias())),
                    evidence.shortDownsideRisk(),
                    evidence.mediumDownsideRisk(),
                    evidence.shortConfidence(),
                    evidence.mediumConfidence(),
                    evidence.shortRisk().riskCoverage(),
                    evidence.mediumRisk().riskCoverage(),
                    gated.candidateMediumAction().name(),
                    gated.candidateShortAction().name(),
                    gated.reasons(),
                    etfPremiumLivePct,
                    etfPremiumLiveNavAsOf);
        } catch (Exception e) {
            log.warn("今日交易雷達：{} {} 組裝失敗", target.market(), target.code(), e);
            return incompleteStock(target, name, assetClass, "讀取個股資料失敗，該檔今日不交易。");
        }
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
    private RadarInputAssembler.Assembled prepareTechnicalData(
            Target target,
            RadarObservationResolver.AcceptedPrice acceptedPrice) {
        if (acceptedPrice == null) return RadarInputAssembler.Assembled.EMPTY;
        // 技術序列一律取 indicatorSeriesRows（Task 319.3）：verifiedCompletedRows 帶著台股
        // provenance 白名單，那是 accepted price／quoteStatus 的判準，拿來當 MA／KD 的歷史序列
        // 會讓 close_source 為 null 的歷史列（Task 290 明定不回填）整批消失。
        List<StockPriceHistory> completedRows = acceptedPrice.indicatorSeriesRows();
        Optional<PriceQueryService.LivePrice> liveOpt = acceptedPrice.liveAccepted()
                ? Optional.ofNullable(acceptedPrice.live()) : Optional.empty();
        List<StockPriceHistory> combined = new ArrayList<>(completedRows);
        // AcceptedPrice is the immutable decision snapshot.  Do not call the calendar/live
        // predicate again here: a mutable holiday cache or quote state must not make the
        // accepted display price and technical sequence disagree within one decision.
        boolean liveAdded = acceptedPrice.liveAccepted() && liveOpt.isPresent();
        if (liveAdded) {
            combined.add(0, liveRow(target, liveOpt.orElseThrow()));
        }
        if (combined.isEmpty()) return RadarInputAssembler.Assembled.EMPTY;

        LocalDate fromDate = combined.get(combined.size() - 1).getTradingDate();
        LocalDate toDate = combined.get(0).getTradingDate();
        return assembler.assemble(
                combined,
                dividendHistoryRepo.findAdjustmentEvents(target.code(), target.market(), fromDate, toDate),
                liveAdded,
                completedRows.size(),
                acceptedPrice.value());
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
        if (TAIEX_CODE.equals(code)) return;
        Target existing = targets.get(key);
        targets.put(key, new Target(code, market, held || (existing != null && existing.held())));
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
            List<BigDecimal> strictHistory) {
        if (current == null || asOfDate == null) return null;
        try {
            List<BigDecimal> history = strictHistory == null || strictHistory.isEmpty()
                    ? etfNavHistoryRepo.findRecentPremiumPctAsOf(
                            code, market, asOfDate,
                            org.springframework.data.domain.PageRequest.of(0, ETF_PREMIUM_LOOKBACK_DAYS))
                    : strictHistory;
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

    private static Integer bestScore(TradingRadarDto.StockDecision decision) {
        Integer shortScore = decision.shortScore();
        Integer mediumScore = decision.score();
        if (shortScore == null) return mediumScore;
        if (mediumScore == null) return shortScore;
        return Math.max(shortScore, mediumScore);
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

    private TradingRadarDto.StockDecision incompleteStock(
            Target target, String name, String assetClass, String message) {
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
                TradingRadarDto.RadarEvidence.EMPTY,
                null, null, null, null, null, null, null, null, List.of(),
                null, // etfPremiumLivePct（Task 320）
                null); // etfPremiumLiveNavAsOf（Task 320）
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
