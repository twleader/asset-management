package com.steven.assets.service;

import com.steven.assets.dto.BacktestDto;
import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.EtfNavHistory;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.EtfNavObservationRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.util.MarketZones;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 交易雷達規則回測框架（Task 273／291）。
 *
 * <p>V11 以 production 共用的組裝器、規則引擎、市場 context 與基本面 as-of resolver，
 * 稽核短期與中期訊號；
 * 本服務只量測規則輸出，不在回測端另寫第二套判定。</p>
 *
 * <p><b>做法</b>：把歷史序列逐日切成「截至 t 的 241 筆視窗」，餵給
 * {@link RadarInputAssembler}（production 的同一支組裝）再餵給同一支規則引擎，
 * 量測每條述詞成立後 5／20／60／120 個交易日的前瞻報酬分布，並與同標的同期間的
 * 無條件分布（基準）比較。</p>
 *
 * <p><b>輸出一律是「歷史上此條件成立後的報酬分布」</b>，不是預測（273.8.1）。
 * 刻意不輸出 p 值與信賴區間——樣本嚴重自相關，古典檢定前提不成立（273.6.4）。</p>
 *
 * <h3>前視偏誤的處理</h3>
 * <ul>
 *   <li><b>全期統計量是真正的來源</b>：52 週高低一律只取自截至 t 的 241 筆視窗，
 *       由 {@code RadarInputAssembler} 負責，本服務不另外掃全序列取 max／min。</li>
 *   <li><b>還原權息的切片不構成前視偏誤</b>：back-adjustment 對切片只造成一個與索引無關的
 *       常數倍率，而引擎的全部價格導出輸入皆為尺度不變量（比較、比值、相對位置）。
 *       故不需逐 t 重錨，也不得宣稱重錨修正了前視偏誤（273.3.2）。</li>
 *   <li><b>大盤 regime 逐日重算</b>，不得用最新一日套用到全部歷史。</li>
 *   <li><b>匯率分位以「截至 t 的五年視窗」重算</b>，不得沿用 production 以今日為錨的算法。</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class BacktestService {

    private static final String TW_MARKET = "台股";
    private static final String TAIEX_CODE = "0000";
    private static final String TWD = "TWD";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /** 美股 completed close 後的固定 evidence boundary；不取用下一交易日盤前資料。 */
    private static final LocalTime US_SIGNAL_BOUNDARY = LocalTime.of(18, 0);
    /**
     * 60 根完成週所需的最少完成日 K（Task 356.13a-2）：60 完成週 × 每週約 5 個交易日，
     * 另留一週給「最晚 ISO 週恆被視為進行中週」的排除。
     *
     * <p>這個數字必須跟著 {@link RadarInputAssembler#MIN_COMPLETED_WEEKS} 走，
     * 寫死 300 或 305 會在該常數調整時靜默失準。</p>
     *
     * <p>本組視窗常數一律為 package-private（不是 private）：同 package 的測試必須能直接斷言
     * 「{@code WINDOW} 已放大且 {@code DAILY_CONTRACT_ROWS} 仍為 241」，
     * 用反射或複製一份字面值都會讓斷言與實作各自漂移。</p>
     */
    static final int WEEKLY_WARMUP_ROWS = (RadarInputAssembler.MIN_COMPLETED_WEEKS + 1) * 5;
    /**
     * 需要 240 根完成日 K 的因子在此之前不可得；這些日子 production 會回 NO_TRADE，一律排除。
     *
     * <p><b>Task 356.13a-2 起同時要滿足「60 根完成週」</b>，故取兩者的較大者（目前為
     * {@link #WEEKLY_WARMUP_ROWS} = 305）。維持 240 的話 {@code completedWeeks} 永遠不足 60，
     * 四組週K 因子在回測中恆為缺值並重分配權重，量到的是一組<b>沒有週K 的規則</b>，
     * 卻要拿來當新權重的稽核。</p>
     *
     * <p><b>代價必須如實揭露而不是靜默少掉幾檔</b>（Task 356.13a-3）：交易日數僅 318 的標的
     * 扣掉暖機後只剩約 13 個可用訊號日，低於 {@link #MIN_SAMPLES} 而整檔被標記為樣本不足。</p>
     */
    static final int WARMUP = Math.max(RadarInputAssembler.FULL_WINDOW, WEEKLY_WARMUP_ROWS);
    /**
     * 回測取數視窗，與 production 的 {@code SERIES_FETCH_ROWS} 一致（Task 356.4a／356.13a-2）。
     *
     * <p><b>這不是日K 契約</b>：日K 契約仍固定為 {@link #DAILY_CONTRACT_ROWS}（241），
     * 由 {@code RadarInputAssembler.assemble} 的顯式參數界定。長視窗只供週K 聚合取得
     * 足夠的完成週；維持 241 的話 ≈ 48 個 ISO 週 &lt; 60，回測是空跑。</p>
     */
    static final int WINDOW = 500;
    /**
     * 日K 契約的<b>完成列</b>數，與 production 共用同一個值（Task 356.4d）。
     * 回測無 live K，故實際視窗長度即此值。
     */
    static final int DAILY_CONTRACT_ROWS = RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS;
    /**
     * 五個持有期（Task 356.13a）：{@code 5} 對應一周軌、{@code 10}／{@code 20} 對應 1周~1月 軌、
     * {@code 60}／{@code 120} 對應 1月~6月 軌。
     */
    private static final List<Integer> DEFAULT_HORIZONS = List.of(5, 10, 20, 60, 120);
    /** 低於此樣本數的格子一律標記為樣本不足，其數字不得用於決策（273.6.3）。 */
    private static final int MIN_SAMPLES = 30;
    private static final BigDecimal DOWNSIDE = BigDecimal.valueOf(-10);
    /** Keep the backtest premium percentile identical to production's lookback contract. */
    private static final int ETF_PREMIUM_LOOKBACK_DAYS = 250;
    private static final int ETF_PREMIUM_MIN_SAMPLES = 60;
    private static final int MAX_FOLD_NORMALIZED_BIAS_ROWS = 5_000;

    private final TradingRadarRuleEngine ruleEngine;
    private final RadarInputAssembler assembler;
    private final AssetClassifier assetClassifier;
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final StockDividendHistoryRepository dividendHistoryRepo;
    private final TwseIndexDailyHistoryRepository twseRepo;
    private final UsIndexDailyHistoryRepository usIndexRepo;
    private final ExchangeRateHistoryRepository exchangeRateRepo;
    private final EtfNavHistoryRepository etfNavHistoryRepo;
    private final StockRepository stockRepo;
    private final DistributionAdjustedPriceService adjustedPriceService;
    private final TradingRadarMarketContextService marketContextService;
    private final FundamentalAnalysisService fundamentalAnalysisService;
    private final TreasuryYieldService treasuryYieldService;
    private final DividendEventEvidenceRepository dividendEventEvidenceRepository;
    private final BondYieldBetaEvidencePort bondYieldBetaEvidencePort;
    private final TradingRadarMarketFeaturePort marketFeaturePort;
    /** Append-only NAV observations; the daily current table is never a backtest source. */
    private final EtfNavObservationRepository etfNavObservationRepository;
    private final StockStyleThresholdProvider stockStyleThresholdProvider;

    /** Legacy unit-test constructor: Treasury evidence is explicitly unavailable. */
    BacktestService(
            TradingRadarRuleEngine ruleEngine,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexRepo,
            ExchangeRateHistoryRepository exchangeRateRepo,
            EtfNavHistoryRepository etfNavHistoryRepo,
            StockRepository stockRepo,
            DistributionAdjustedPriceService adjustedPriceService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService) {
        this(ruleEngine, assembler, assetClassifier, priceHistoryRepo, dividendHistoryRepo,
                twseRepo, usIndexRepo, exchangeRateRepo, etfNavHistoryRepo, stockRepo,
                adjustedPriceService, marketContextService, fundamentalAnalysisService,
                null, null, null, null, null, null);
    }

    /** Explicit constructor injection keeps the Treasury as-of port visible to Spring and tests. */
    public BacktestService(
            TradingRadarRuleEngine ruleEngine,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexRepo,
            ExchangeRateHistoryRepository exchangeRateRepo,
            EtfNavHistoryRepository etfNavHistoryRepo,
            StockRepository stockRepo,
            DistributionAdjustedPriceService adjustedPriceService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            TreasuryYieldService treasuryYieldService,
            DividendEventEvidenceRepository dividendEventEvidenceRepository) {
        this(ruleEngine, assembler, assetClassifier, priceHistoryRepo, dividendHistoryRepo,
                twseRepo, usIndexRepo, exchangeRateRepo, etfNavHistoryRepo, stockRepo,
                adjustedPriceService, marketContextService, fundamentalAnalysisService,
                treasuryYieldService, dividendEventEvidenceRepository, null, null, null, null);
    }

    /** Compatibility constructor when bond beta was added before the market source port. */
    public BacktestService(
            TradingRadarRuleEngine ruleEngine,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexRepo,
            ExchangeRateHistoryRepository exchangeRateRepo,
            EtfNavHistoryRepository etfNavHistoryRepo,
            StockRepository stockRepo,
            DistributionAdjustedPriceService adjustedPriceService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            TreasuryYieldService treasuryYieldService,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort) {
        this(ruleEngine, assembler, assetClassifier, priceHistoryRepo, dividendHistoryRepo,
                twseRepo, usIndexRepo, exchangeRateRepo, etfNavHistoryRepo, stockRepo,
                adjustedPriceService, marketContextService, fundamentalAnalysisService,
                treasuryYieldService, dividendEventEvidenceRepository, bondYieldBetaEvidencePort, null, null, null);
    }

    /** Compatibility constructor for callers that already provide market/NAV ports. */
    public BacktestService(
            TradingRadarRuleEngine ruleEngine,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexRepo,
            ExchangeRateHistoryRepository exchangeRateRepo,
            EtfNavHistoryRepository etfNavHistoryRepo,
            StockRepository stockRepo,
            DistributionAdjustedPriceService adjustedPriceService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            TreasuryYieldService treasuryYieldService,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort,
            TradingRadarMarketFeaturePort marketFeaturePort,
            EtfNavObservationRepository etfNavObservationRepository) {
        this(ruleEngine, assembler, assetClassifier, priceHistoryRepo, dividendHistoryRepo,
                twseRepo, usIndexRepo, exchangeRateRepo, etfNavHistoryRepo, stockRepo,
                adjustedPriceService, marketContextService, fundamentalAnalysisService,
                treasuryYieldService, dividendEventEvidenceRepository, bondYieldBetaEvidencePort,
                marketFeaturePort, etfNavObservationRepository, null);
    }

    /** Explicit constructor injection keeps the Treasury and beta evidence ports visible to Spring/tests. */
    @Autowired
    public BacktestService(
            TradingRadarRuleEngine ruleEngine,
            RadarInputAssembler assembler,
            AssetClassifier assetClassifier,
            StockPriceHistoryRepository priceHistoryRepo,
            StockDividendHistoryRepository dividendHistoryRepo,
            TwseIndexDailyHistoryRepository twseRepo,
            UsIndexDailyHistoryRepository usIndexRepo,
            ExchangeRateHistoryRepository exchangeRateRepo,
            EtfNavHistoryRepository etfNavHistoryRepo,
            StockRepository stockRepo,
            DistributionAdjustedPriceService adjustedPriceService,
            TradingRadarMarketContextService marketContextService,
            FundamentalAnalysisService fundamentalAnalysisService,
            TreasuryYieldService treasuryYieldService,
            DividendEventEvidenceRepository dividendEventEvidenceRepository,
            BondYieldBetaEvidencePort bondYieldBetaEvidencePort,
            TradingRadarMarketFeaturePort marketFeaturePort,
            EtfNavObservationRepository etfNavObservationRepository,
            StockStyleThresholdProvider stockStyleThresholdProvider) {
        this.ruleEngine = ruleEngine;
        this.assembler = assembler;
        this.assetClassifier = assetClassifier;
        this.priceHistoryRepo = priceHistoryRepo;
        this.dividendHistoryRepo = dividendHistoryRepo;
        this.twseRepo = twseRepo;
        this.usIndexRepo = usIndexRepo;
        this.exchangeRateRepo = exchangeRateRepo;
        this.etfNavHistoryRepo = etfNavHistoryRepo;
        this.stockRepo = stockRepo;
        this.adjustedPriceService = adjustedPriceService;
        this.marketContextService = marketContextService;
        this.fundamentalAnalysisService = fundamentalAnalysisService;
        this.treasuryYieldService = treasuryYieldService;
        this.dividendEventEvidenceRepository = dividendEventEvidenceRepository;
        this.bondYieldBetaEvidencePort = bondYieldBetaEvidencePort;
        this.marketFeaturePort = marketFeaturePort;
        this.etfNavObservationRepository = etfNavObservationRepository;
        this.stockStyleThresholdProvider = stockStyleThresholdProvider;
    }

    /** 單一交易日的觀察值：述詞判定所需的一切，全部來自截至該日的資料。 */
    private record Obs(
            LocalDate date,
            int index,
            Integer score,
            Integer shortScore,
            /** 1周~1月 軌分數（Task 356.13b）；與 {@code score}／{@code shortScore} 平行，不得互相頂替。 */
            Integer swingScore,
            TradingRadarRuleEngine.Action actionHeld,
            TradingRadarRuleEngine.Action actionNotHeld,
            TradingRadarRuleEngine.Action shortActionHeld,
            TradingRadarRuleEngine.Action shortActionNotHeld,
            TradingRadarRuleEngine.Action swingActionHeld,
            TradingRadarRuleEngine.Action swingActionNotHeld,
            TradingRadarRuleEngine.TimingState timing,
            TradingRadarRuleEngine.KdHeat kdHeat,
            boolean kdDeadCross,
            boolean longTermBroken,
            boolean profitTakingConfirmed,
            BigDecimal ma60BiasPercent,
            BigDecimal k,
            BigDecimal d,
            BigDecimal sigma,
            BigDecimal volumeRatio,
            boolean etfPremiumAvailable,
            /** 該標的是否為 ETF（折溢價否決只對 ETF 生效，非 ETF 不該掛 caveat）。 */
            boolean etfLike,
            TradingRadarRuleEngine.FundamentalInput fundamental
    ) {
        TradingRadarRuleEngine.Action action(boolean held) { return held ? actionHeld : actionNotHeld; }
        TradingRadarRuleEngine.Action shortAction(boolean held) {
            return held ? shortActionHeld : shortActionNotHeld;
        }
        TradingRadarRuleEngine.Action swingAction(boolean held) {
            return held ? swingActionHeld : swingActionNotHeld;
        }
    }

    /** 同幣別的歷史列與逐訊號日解析結果；避免每檔重複查詢與重算。 */
    private record FxSeries(
            List<ExchangeRateHistory> rows,
            Map<LocalDate, TradingRadarMarketContextService.FxContext> resolved
    ) {}

    /** Per-request immutable session calendar cache; never derive sessions from one code's closes. */
    private record MarketCalendarKey(String market, LocalDate from, LocalDate to) {}

    private record CalendarResolution(List<LocalDate> sessions, boolean known) {
        CalendarResolution {
            sessions = sessions == null ? List.of() : List.copyOf(sessions);
        }
    }

    /** 具名述詞。{@code held} 由呼叫端帶入，因為述詞 6／7 的動作依 held 而不同。 */
    private record NamedPredicate(String name, java.util.function.BiPredicate<Obs, Boolean> test) {}

    /** 單一標的的回測產物。 */
    private record CodeRun(
            BacktestDto.CodeCoverage coverage,
            List<Obs> observations,
            /** 全序列的還原收盤價，供前瞻報酬（含 t+h，故取自全序列而非截至 t 的子序列）。 */
            List<BigDecimal> adjustedCloses
    ) {}

    /** V13 report/promotion 的最小分組單位；null profile 仍保留為明確 insufficient group。 */
    private record V13GroupKey(
            String market,
            int horizon,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String productionProfile,
            String assetClass,
            String stockStyle,
            String bondTerm,
            String confidenceDecile) {
        private V13GroupKey(String market, int horizon) {
            this(market, horizon, null, null, null, null, null, null);
        }
    }

    /**
     * Calibration-only realized-volatility profile used by the V13 normalized-BIAS floor.
     * Percentiles are computed from signal dates at or before the global calibration cutoff;
     * holdout observations never participate in candidate construction.
     */
    private record CalibrationSigmaProfile(
            BigDecimal p05,
            BigDecimal p10,
            BigDecimal p25,
            int sampleN,
            LocalDate asOfFrom,
            LocalDate asOfTo,
            LocalDate calibrationCutoff,
            String source,
            BigDecimal saturationMultiple,
            BigDecimal upperMultiple,
            BigDecimal lowerMultiple
    ) {
        static CalibrationSigmaProfile unavailable(String reason) {
            return new CalibrationSigmaProfile(null, null, null, 0, null, null, null,
                    reason == null ? "unavailable" : reason, null, null, null);
        }

        boolean available() {
            return p05 != null && p10 != null && p25 != null && sampleN > 0
                    && saturationMultiple != null && upperMultiple != null && lowerMultiple != null
                    && saturationMultiple.signum() > 0 && upperMultiple.signum() > 0
                    && lowerMultiple.signum() > 0;
        }

        BigDecimal floorFor(int percentile) {
            if (!available()) return BigDecimal.ZERO;
            return switch (percentile) {
                case 5 -> p05;
                case 10 -> p10;
                case 25 -> p25;
                default -> throw new IllegalArgumentException("unsupported sigma percentile: " + percentile);
            };
        }

        String note() {
            return "normalized sigma floor calibration source=" + source
                    + "; cutoff=" + (calibrationCutoff == null ? "" : calibrationCutoff)
                    + "; asOfFrom=" + (asOfFrom == null ? "" : asOfFrom)
                    + "; asOfTo=" + (asOfTo == null ? "" : asOfTo)
                    + "; n=" + sampleN
                    + "; p05=" + (p05 == null ? "" : p05)
                    + "; p10=" + (p10 == null ? "" : p10)
                    + "; p25=" + (p25 == null ? "" : p25)
                    + "; saturation=" + (saturationMultiple == null ? "" : saturationMultiple)
                    + "; upper=" + (upperMultiple == null ? "" : upperMultiple)
                    + "; lower=" + (lowerMultiple == null ? "" : lowerMultiple)
                    + "; candidateFloors=" + (available() ? "P05/P10/P25" : "DISABLED_NO_CALIBRATION_PROFILE");
        }
    }

    private record V13CandidateOutcome(
            TradingRadarRuleEngine.StockResult free,
            TradingRadarRuleEngine.StockResult held
    ) {}

    private record V13Attempt(
            String code,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String productionProfile,
            String assetClass,
            String stockStyle,
            String bondTerm,
            String confidenceDecile,
            RadarBacktestExecution.ExecutionAttempt execution,
            V13CandidateOutcome baseline,
            /** The exact as-of inputs used to build the candidate outcomes. */
            TradingRadarRuleEngine.StockInput freeInput,
            TradingRadarRuleEngine.StockInput heldInput,
            /** Baseline context shared by candidates whose evidence inputs are identical. */
            TradingRadarRuleEngine.CandidateContext baselineContext,
            /** Immutable request-level grid; all attempts share the same list instance. */
            List<RuleParameters> candidateGrid,
            /**
             * Only evidence contexts which differ from the baseline are retained.  Most V13
             * candidates vary thresholds/weights only; retaining a full StockResult and a
             * duplicate CandidateContext for every candidate made a decade-long run retain
             * millions of redundant reason/risk strings.  A missing key deliberately means
             * "reuse baselineContext" and replayCandidate still evaluates the exact engine.
             */
            Map<String, TradingRadarRuleEngine.CandidateContext> candidateContexts
    ) {}

    private record TreasuryObservationKey(java.time.Instant decisionInstant, String tenor) {}

    private record V13TrackStats(
            int candidateN,
            int candidateCodes,
            int baselineN,
            int baselineCodes,
            int intersectionN,
            int intersectionCodes,
            List<BigDecimal> candidateReturns,
            List<BigDecimal> baselineReturns,
            List<BigDecimal> intersectionCandidateReturns,
            List<BigDecimal> intersectionBaselineReturns,
            List<BigDecimal> deltas,
            BigDecimal pooledMeanDelta,
            /** Median of per-code mean deltas; each code contributes exactly one paired value. */
            BigDecimal pairedMedianDelta,
            BigDecimal candidateDownsideRatePct,
            BigDecimal baselineDownsideRatePct,
            BigDecimal intersectionCandidateDownsideRatePct,
            BigDecimal intersectionBaselineDownsideRatePct
    ) {}

    private record V13CandidateStats(
            int candidateN,
            int candidateCodes,
            int baselineN,
            int baselineCodes,
            int intersectionN,
            int intersectionCodes,
            List<BigDecimal> candidateReturns,
            List<BigDecimal> baselineReturns,
            List<BigDecimal> intersectionCandidateReturns,
            List<BigDecimal> intersectionBaselineReturns,
            List<BigDecimal> candidateGrossReturns,
            List<BigDecimal> baselineGrossReturns,
            List<BigDecimal> intersectionCandidateGrossReturns,
            List<BigDecimal> intersectionBaselineGrossReturns,
            List<BigDecimal> deltas,
            BigDecimal pooledMeanDelta,
            /** Median of per-code mean deltas; not a row-weighted percentile. */
            BigDecimal pairedMedianDelta,
            BigDecimal candidateDownsideRatePct,
            BigDecimal baselineDownsideRatePct,
            BigDecimal intersectionCandidateDownsideRatePct,
            BigDecimal intersectionBaselineDownsideRatePct,
            V13TrackStats entry,
            V13TrackStats held,
            /** Paired deltas grouped by code; joint-track selection must pool horizons by code. */
            Map<String, List<BigDecimal>> deltasByCode
    ) {
        private V13CandidateStats {
            deltasByCode = copyDeltasByCode(deltasByCode);
        }

        private static Map<String, List<BigDecimal>> copyDeltasByCode(
                Map<String, List<BigDecimal>> source) {
            if (source == null || source.isEmpty()) return Map.of();
            Map<String, List<BigDecimal>> copy = new LinkedHashMap<>();
            source.forEach((code, values) -> copy.put(
                    code == null ? "" : code,
                    values == null ? List.of() : List.copyOf(values)));
            return Map.copyOf(copy);
        }
    }

    private final class V13TrackAccumulator {
        private final List<BigDecimal> candidateReturns = new ArrayList<>();
        private final List<BigDecimal> baselineReturns = new ArrayList<>();
        private final List<BigDecimal> intersectionCandidateReturns = new ArrayList<>();
        private final List<BigDecimal> intersectionBaselineReturns = new ArrayList<>();
        private final List<BigDecimal> deltas = new ArrayList<>();
        private final Map<String, List<BigDecimal>> deltasByCode = new LinkedHashMap<>();
        private final Set<String> candidateCodes = new LinkedHashSet<>();
        private final Set<String> baselineCodes = new LinkedHashSet<>();
        private final Set<String> intersectionCodes = new LinkedHashSet<>();

        private void add(
                String code,
                boolean candidateHit,
                BigDecimal candidateReturn,
                boolean baselineHit,
                BigDecimal baselineReturn) {
            if (candidateHit) {
                candidateReturns.add(candidateReturn);
                candidateCodes.add(code);
            }
            if (baselineHit) {
                baselineReturns.add(baselineReturn);
                baselineCodes.add(code);
            }
            if (!candidateHit && !baselineHit) return;
            intersectionCandidateReturns.add(candidateReturn);
            intersectionBaselineReturns.add(baselineReturn);
            intersectionCodes.add(code);
            BigDecimal delta = candidateReturn.subtract(baselineReturn).setScale(4, RoundingMode.HALF_UP);
            deltas.add(delta);
            deltasByCode.computeIfAbsent(code == null ? "" : code, ignored -> new ArrayList<>()).add(delta);
        }

        private V13TrackStats finish() {
            return new V13TrackStats(
                    candidateReturns.size(), candidateCodes.size(), baselineReturns.size(), baselineCodes.size(),
                    intersectionCandidateReturns.size(), intersectionCodes.size(),
                    List.copyOf(candidateReturns), List.copyOf(baselineReturns),
                    List.copyOf(intersectionCandidateReturns), List.copyOf(intersectionBaselineReturns),
                    List.copyOf(deltas), diff(mean(intersectionCandidateReturns), mean(intersectionBaselineReturns)),
                    pairedMedianDelta(deltasByCode),
                    ratioAtOrBelow(candidateReturns, DOWNSIDE), ratioAtOrBelow(baselineReturns, DOWNSIDE),
                    ratioAtOrBelow(intersectionCandidateReturns, DOWNSIDE),
                    ratioAtOrBelow(intersectionBaselineReturns, DOWNSIDE));
        }
    }

    private record V13GroupAnalysis(
            V13GroupKey key,
            List<V13Attempt> attempts,
            RadarWalkForwardPlan.ChronologicalSplit split,
            List<RadarWalkForwardPlan.Fold> folds,
            RuleParameters selectedCandidate,
            List<BacktestDto.CandidateCalibration> candidateCalibration,
            String productionProfile,
            RadarBacktestExecution.InstrumentKind instrumentKind,
            String assetClass,
            String stockStyle,
            String bondTerm,
            String confidenceDecile,
            TradingRadarV13PromotionRegistry.Track track,
            Map<Integer, RuleParameters> foldSelectedCandidates,
            Map<Integer, CalibrationSigmaProfile> foldSigmaProfiles,
            boolean foldSigmaProfileUnavailable,
            CalibrationSigmaProfile sigmaProfile
    ) {}

    /** 一個 exact production key/fold 共用的 joint calendar、sigma 與選值。 */
    private record JointFoldSelection(
            RadarWalkForwardPlan.JointTrackFoldResolution resolution,
            CalibrationSigmaProfile sigmaProfile,
            RuleParameters selectedCandidate,
            String status,
            String reason
    ) {
        private JointFoldSelection {
            status = status == null ? "UNAVAILABLE" : status;
            reason = reason == null ? "" : reason;
        }

        boolean available() {
            return resolution != null && resolution.available()
                    && sigmaProfile != null && sigmaProfile.available()
                    && selectedCandidate != null && "AVAILABLE".equals(status);
        }
    }

    /** Registry decision 與其使用的 joint-fold evidence 必須由同一次建構產生。 */
    private record V13RegistryBuild(
            TradingRadarV13PromotionRegistry registry,
            Map<TradingRadarV13PromotionRegistry.ProductionKey,
                    Map<Integer, JointFoldSelection>> jointFoldSelections,
            List<String> failures
    ) {
        private V13RegistryBuild {
            jointFoldSelections = jointFoldSelections == null ? Map.of() : Map.copyOf(jointFoldSelections);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    // ─────────────────────────── 對外入口 ───────────────────────────

    public BacktestDto.Response run(BacktestDto.Request request) {
        BacktestDto.Request req = request == null
                ? new BacktestDto.Request(null, null, null, null, null, null)
                : request;
        List<Integer> horizons = (req.horizons() == null || req.horizons().isEmpty())
                ? DEFAULT_HORIZONS
                : req.horizons().stream().filter(h -> h != null && h > 0).sorted().distinct().toList();
        LocalDate from = parseDate(req.from());
        LocalDate to = parseDate(req.to());

        List<String> codes = resolveCodes(req.codes());
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes = buildMarketRegimes();
        Map<String, FxSeries> fxSeriesByCurrency = new HashMap<>();

        List<CodeRun> runs = new ArrayList<>();
        List<String> failedCodes = new ArrayList<>();
        for (String code : codes) {
            try {
                CodeRun run = runOne(code, from, to, regimes, fxSeriesByCurrency);
                if (run != null) runs.add(run);
            } catch (Exception e) {
                // 失敗標的必須在輸出中可見：否則「68 檔的統計」與「本來就只有 68 檔」無法區分，
                // 結果不可獨立複驗（273.7.1）。
                log.warn("回測 {} 失敗，略過該標的", code, e);
                failedCodes.add(code);
            }
        }

        List<NamedPredicate> predicates = buildPredicates(req.thresholds(), req.predicates());
        List<BacktestDto.PredicateStat> stats = new ArrayList<>();
        List<BacktestDto.PairedStat> paired = new ArrayList<>();
        for (NamedPredicate p : predicates) {
            for (boolean held : new boolean[]{true, false}) {
                for (int h : horizons) {
                    stats.add(pooled(p, held, h, runs));
                    paired.add(pairedByCode(p, held, h, runs));
                }
            }
        }

        String dataFrom = runs.stream().map(r -> r.coverage().dataFrom())
                .filter(java.util.Objects::nonNull).min(String::compareTo).orElse(null);
        String dataTo = runs.stream().map(r -> r.coverage().dataTo())
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);

        BacktestDto.V13Report v13 = req.v13Requested()
                ? buildV13Report(req, horizons)
                : null;

        return new BacktestDto.Response(
                TradingRadarRuleEngine.RULE_VERSION,
                dataFrom,
                dataTo,
                runs.size(),
                horizons,
                runs.stream().map(CodeRun::coverage).toList(),
                stats,
                paired,
                List.copyOf(failedCodes),
                notes(runs, failedCodes),
                v13);
    }

    // ─────────────────────────── 單一標的 ───────────────────────────

    private BigDecimal stockStyleIncomeThreshold() {
        return stockStyleThresholdProvider == null
                ? AssetClassifier.defaultDividendThreshold()
                : stockStyleThresholdProvider.incomeThreshold();
    }

    private CodeRun runOne(
            String code,
            LocalDate from,
            LocalDate to,
            Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes,
            Map<String, FxSeries> fxCache) {

        List<StockPriceHistory> raw =
                priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(code, TW_MARKET);
        if (raw == null || raw.isEmpty()) return null;

        // close_price <= 0 的髒列一律剔除：單列即產生 bias = −100% 的假訊號並污染 MA60。
        // 這是既有的資料品質問題（實測台股 175 列），本框架只剔除與揭露，不修資料。
        List<StockPriceHistory> rows = raw.stream()
                .filter(r -> r.getClosePrice() != null && r.getClosePrice().signum() > 0)
                .toList();
        int nonPositive = raw.size() - rows.size();
        if (rows.size() <= WARMUP) {
            return new CodeRun(coverage(code, rows, nonPositive, rows.size(), 0, 0, "EQUITY"),
                    List.of(), List.of());
        }

        Optional<Stock> stock = stockRepo.findByCodeAndMarket(code, TW_MARKET);
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                stock.orElse(null), code, TW_MARKET,
                stock.map(Stock::getName).orElse(code), null, stockStyleIncomeThreshold());
        TradingRadarRuleEngine.InstrumentType instrumentType = profile.bond()
                ? TradingRadarRuleEngine.InstrumentType.BOND
                : TradingRadarRuleEngine.InstrumentType.EQUITY;

        LocalDate seriesFrom = rows.get(0).getTradingDate();
        LocalDate seriesTo = rows.get(rows.size() - 1).getTradingDate();
        List<StockDividendHistory> allEvents =
                dividendHistoryRepo.findAdjustmentEvents(code, TW_MARKET, seriesFrom, seriesTo);

        // 前瞻報酬用的還原序列：一次算完整段（含 t+h）。切片與全段只差一個常數倍率，
        // 而報酬率是比值，故錨點差異不影響結果（273.3.2 的推導）。
        List<BigDecimal> adjustedCloses = fullAdjustedCloses(rows, allEvents);

        // Backtests must use the append-only observation stream.  The daily
        // etf_nav_history row is a current view without a known-at boundary and
        // is therefore not allowed to leak revisions into historical signals.
        List<EtfNavObservation> premiumObservations = loadPremiumObservations(
                code, TW_MARKET, seriesTo);
        boolean etfLike = isEtf(profile) || !premiumObservations.isEmpty() || code.startsWith("00");
        String currency = profile.underlyingCurrency();
        FxSeries fxSeries = currency == null || TWD.equals(currency) ? null : fxSeries(currency, fxCache);

        // 基本面 observation 每檔只查一次；各訊號日的 as-of/revision collapse 由 resolver 在記憶體完成。
        List<java.time.Instant> fundamentalInstants = rows.stream().skip(WARMUP)
                .map(StockPriceHistory::getTradingDate)
                .filter(date -> from == null || !date.isBefore(from))
                .filter(date -> to == null || !date.isAfter(to))
                .map(this::signalInstant)
                .toList();
        Map<java.time.Instant, TradingRadarRuleEngine.FundamentalInput> fundamentalByInstant =
                fundamentalAnalysisService.resolveInputsForBacktest(
                        code, TW_MARKET, fundamentalInstants, profile);
        if (fundamentalByInstant == null || fundamentalByInstant.isEmpty()) {
            // Compatibility with legacy adapters that predate the strict-profile overload.
            fundamentalByInstant = fundamentalAnalysisService.resolveInputsForBacktest(
                    code, TW_MARKET, fundamentalInstants);
        }

        List<Obs> observations = new ArrayList<>();
        int warmupExcluded = 0;
        int etfPremiumDays = 0;

        for (int t = 0; t < rows.size(); t++) {
            if (t < WARMUP) { warmupExcluded++; continue; }
            LocalDate date = rows.get(t).getTradingDate();
            if (from != null && date.isBefore(from)) continue;
            if (to != null && date.isAfter(to)) continue;

            // 截至 t 的 241 筆視窗，降序（新到舊）——與 production「取 N 筆後截斷為 241」同形狀
            // （Task 319.4 起 production 抓 250 筆當剔除緩衝，序列上限仍是 241）。
            int lo = Math.max(0, t - WINDOW + 1);
            List<StockPriceHistory> windowDesc = new ArrayList<>(rows.subList(lo, t + 1));
            Collections.reverse(windowDesc);

            List<StockDividendHistory> events = eventsWithin(
                    allEvents, windowDesc.get(windowDesc.size() - 1).getTradingDate(), date);

            // 視窗最新一筆的還原收盤價恆等於其原始收盤價（該筆的 scale 必為 1），故直接取原始值。
            BigDecimal price = windowDesc.get(0).getClosePrice();
            // Task 356.4d／356.4d-3：completedRowCount 與 dailyContractRows 一律顯式傳入日K 契約
            // （241），不得因為 WINDOW 放大成 500 就順手傳 windowDesc.size()。
            RadarInputAssembler.Assembled a = assembler.assemble(
                    windowDesc, events, false,
                    Math.min(windowDesc.size(), DAILY_CONTRACT_ROWS), DAILY_CONTRACT_ROWS, price);

            java.time.Instant decisionInstant = signalInstant(date);
            TradingRadarPremiumResolver.DecisionObservation premiumObservation =
                    resolvePremiumObservation(profile, TW_MARKET, date, decisionInstant, premiumObservations);
            BigDecimal premium = premiumObservation.value();
            BigDecimal premiumPercentile = premiumPercentile(
                    TW_MARKET, premiumObservation, decisionInstant, premiumObservations);
            if (premium != null) etfPremiumDays++;

            TradingRadarRuleEngine.MarketRegime regime = regimes.getOrDefault(
                    date, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE);

            BigDecimal fxPct = fxSeries == null ? null : fxAt(currency, fxSeries, date).percentile();
            TradingRadarRuleEngine.FundamentalInput fundamental = fundamentalByInstant.getOrDefault(
                    decisionInstant, FundamentalAnalysisService.Resolved.unavailable(true).input());

            TradingRadarRuleEngine.StockResult held = evaluate(
                    a, true, price, instrumentType, regime, fxPct, premium, premiumPercentile, fundamental);
            TradingRadarRuleEngine.StockResult free = evaluate(
                    a, false, price, instrumentType, regime, fxPct, premium, premiumPercentile, fundamental);

            observations.add(new Obs(
                    date, t,
                    held.score(),
                    held.shortScore(),
                    held.swingScore(),
                    held.action(), free.action(),
                    held.shortAction(), free.shortAction(),
                    held.swingAction(), free.swingAction(),
                    held.timingState(), held.kdHeat(),
                    held.kdDeadCross(), held.longTermBroken(),
                    held.profitTakingConfirmed(),
                    a.ma60BiasPercent(),
                    a.indicators().k(), a.indicators().d(),
                    a.returnStdDev60Ratio(),
                    a.volumeRatio(),
                    premium != null,
                    etfLike,
                    fundamental));
        }

        return new CodeRun(
                coverage(code, rows, nonPositive, warmupExcluded, observations.size(),
                        etfPremiumDays, instrumentType.name()),
                observations,
                adjustedCloses);
    }

    /** 以組裝結果 ＋ 環境欄位呼叫同一支引擎。**述詞判定一律由引擎產生，此處不複製任何判定邏輯。** */
    private TradingRadarRuleEngine.StockResult evaluate(
            RadarInputAssembler.Assembled a,
            boolean held,
            BigDecimal price,
            TradingRadarRuleEngine.InstrumentType instrumentType,
            TradingRadarRuleEngine.MarketRegime regime,
            BigDecimal fxPct,
            BigDecimal premium,
            BigDecimal premiumPercentile,
            TradingRadarRuleEngine.FundamentalInput fundamental) {
        return ruleEngine.evaluateStock(new TradingRadarRuleEngine.StockInput(
                held,
                price,
                a.ruleChangePercent(),
                a.completedChangePercent(),
                assembler.indicators(a.indicators()),
                a.indicators().previousK(),
                a.indicators().previousD(),
                a.ma20Confirmation(),
                a.ma60Confirmation(),
                a.ma240Confirmation(),
                instrumentType,
                regime,
                false,                 // 歷史日一律非 stale：完成日 K 就是當日的最終值
                fxPct,
                a.ma60BiasPercent(),
                a.ma60BiasPercentile(),
                a.ma240BiasPercent(),
                a.week52Position(),
                a.kdBandWidthPercent(),
                premium,
                premiumPercentile,
                a.indicators().weeklyMa(),
                assembler.extendedIndicators(a.indicators().extended()),
                a.volumeRatio(),
                fundamental,
                // Task 356.13a-2：個股回測的 StockInput 同樣必須接上日K 棒與週K。少傳這兩個引數
                // 會靜默命中 Task 356 之前的相容建構式（不會編譯失敗），五個新因子在回測中恆為
                // 缺值並重分配權重 → 356.13b 量到的是一組沒有週K 的規則，回測是空跑。
                a.dailyCandle(),
                a.weekly()));
    }

    // ─────────────────────────── Task 308 可成交報告 ───────────────────────────

    private BacktestDto.V13Report buildV13Report(
            BacktestDto.Request request, List<Integer> legacyNormalizedHorizons) {
        BacktestDto.UniverseMode universeMode = request.codes() == null || request.codes().isEmpty()
                ? BacktestDto.UniverseMode.FULL_MARKET
                : BacktestDto.UniverseMode.BOUNDED_DIAGNOSTIC;
        Set<String> markets = normalizeV13Markets(request.markets());
        List<Integer> horizons = normalizeV13Horizons(request.horizons(), legacyNormalizedHorizons);
        LocalDate from = parseV13Date(request.from(), "from");
        LocalDate to = parseV13Date(request.to(), "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from 不得晚於 to");
        }
        BigDecimal ratio = request.calibrationRatio() == null
                ? new BigDecimal("0.70") : request.calibrationRatio();
        int requestedFolds = request.walkForwardFolds() == null ? 5 : request.walkForwardFolds();
        // 以 helper 的唯一驗證路徑 fail closed；空日期仍會檢查 ratio/folds。
        RadarWalkForwardPlan.chronologicalSplit(List.of(), ratio);
        RadarWalkForwardPlan.expandingWalkForward(List.of(), requestedFolds);
        boolean includeSensitivity = Boolean.TRUE.equals(request.includeCloseFallbackSensitivity());

        // 每次 request 固定建立同一組 baseline/candidate，不讀取或修改 production registry。
        // The first pass only discovers the candidate-independent global calendar; the second
        // pass (when available) rebuilds candidates with a calibration-only sigma profile.
        CalibrationSigmaProfile unavailableSigma = CalibrationSigmaProfile.unavailable(
                "market_calibration_cutoff_pending");
        List<RuleParameters> unavailableGrid = v13CandidateGrid(unavailableSigma);
        Map<String, CalibrationSigmaProfile> sigmaProfilesByMarket = markets.stream()
                .collect(Collectors.toMap(market -> market, ignored -> unavailableSigma,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, List<RuleParameters>> candidateGridsByMarket = markets.stream()
                .collect(Collectors.toMap(market -> market, ignored -> unavailableGrid,
                        (left, right) -> left, LinkedHashMap::new));
        Map<String, Map<LocalDate, TradingRadarRuleEngine.MarketRegime>> regimesByMarket =
                buildV13MarketRegimes(markets);

        Map<RadarBacktestExecution.CostKey, RadarBacktestExecution.CostAssumption> costs =
                resolveV13Costs(markets, request.costs());
        TradingRadarMarketFeatureResolver.Sources marketFeatureSources = loadV13MarketFeatureSources();
        Map<V13GroupKey, List<V13Attempt>> baseAttempts = new LinkedHashMap<>();
        for (String market : markets) {
            for (int horizon : horizons) baseAttempts.put(new V13GroupKey(market, horizon), new ArrayList<>());
        }
        Map<String, List<String>> codesByMarket = new LinkedHashMap<>();
        for (String market : markets) {
            codesByMarket.put(market, resolveV13Codes(request.codes(), market));
        }
        List<RadarWalkForwardPlan.TradableOpportunity> opportunities = new ArrayList<>();
        Set<String> failures = new LinkedHashSet<>();
        Map<MarketCalendarKey, CalendarResolution> marketCalendarCache = new HashMap<>();
        // Market features are market-wide, not security-wide.  Reuse the immutable
        // decision-instant evidence snapshot across every code in this request; a
        // per-code resolveBatch would otherwise repeat the same nine JPA range queries.
        Map<String, Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence>>
                marketFeatureEvidenceCache = new LinkedHashMap<>();

        for (String market : markets) {
            for (String code : codesByMarket.getOrDefault(market, List.of())) {
                try {
                    appendV13Code(code, market, from, to, horizons, includeSensitivity,
                    costs, candidateGridsByMarket.getOrDefault(market, unavailableGrid),
                            regimesByMarket.getOrDefault(market, Map.of()),
                            baseAttempts, opportunities, failures, marketFeatureSources,
                            marketCalendarCache, marketFeatureEvidenceCache);
                } catch (Exception e) {
                    log.warn("V13 可成交回測 {}/{} 失敗，明確排除", market, code, e);
                    failures.add(market + "/" + code + ":EXECUTION_BUILD_FAILED["
                            + e.getClass().getSimpleName() + "]");
                }
            }
        }

        Map<RadarWalkForwardPlan.MarketHorizon, List<LocalDate>> calendars =
                RadarWalkForwardPlan.globalCalendars(opportunities);
        for (String market : markets) {
            Map<RadarWalkForwardPlan.MarketHorizon, List<LocalDate>> marketCalendars = calendars.entrySet()
                    .stream().filter(entry -> market.equals(entry.getKey().market()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                            (left, right) -> left, LinkedHashMap::new));
            CalibrationSigmaProfile marketSigma = calibrateSigmaProfile(
                    Set.of(market), Map.of(market, codesByMarket.getOrDefault(market, List.of())),
                    from, to, marketCalendars, ratio, opportunities);
            sigmaProfilesByMarket.put(market, marketSigma);
            candidateGridsByMarket.put(market, v13CandidateGrid(marketSigma));
        }
        if (!candidateGridsByMarket.isEmpty()) {
            // Execution opportunities are candidate-independent, but candidate outcomes are not;
            // clear the provisional pass before rebuilding with the calibrated floor.
            baseAttempts.values().forEach(List::clear);
            opportunities.clear();
            failures.clear();
            for (String market : markets) {
                for (String code : codesByMarket.getOrDefault(market, List.of())) {
                    try {
                        appendV13Code(code, market, from, to, horizons, includeSensitivity,
                                costs, candidateGridsByMarket.getOrDefault(market, unavailableGrid),
                                regimesByMarket.getOrDefault(market, Map.of()),
                                baseAttempts, opportunities, failures, marketFeatureSources,
                                marketCalendarCache, marketFeatureEvidenceCache);
                    } catch (Exception e) {
                        log.warn("V13 可成交回測/{}/{} 重新建立失敗，明確排除", market, code, e);
                        failures.add(market + "/" + code + ":EXECUTION_BUILD_FAILED["
                                + e.getClass().getSimpleName() + "]");
                    }
                }
            }
            calendars = RadarWalkForwardPlan.globalCalendars(opportunities);
        }
        // 不把混合 instrument/profile 靜默合併成 null：先按每一檔的實際 kind/profile
        // 拆成獨立 report group；只有完全沒有可辨識樣本的 base group 才保留 null，
        // 由 toV13GroupReport 明確標 insufficient。
        Map<V13GroupKey, List<V13Attempt>> attempts = partitionV13Attempts(baseAttempts);
        Map<V13GroupKey, V13GroupAnalysis> analyses = new LinkedHashMap<>();
        for (var group : attempts.entrySet()) {
            V13GroupKey key = group.getKey();
            List<LocalDate> globalDates = calendars.getOrDefault(
                    new RadarWalkForwardPlan.MarketHorizon(key.market(), key.horizon()), List.of());
            RadarWalkForwardPlan.ChronologicalSplit split =
                    RadarWalkForwardPlan.chronologicalSplit(globalDates, ratio);
            List<RadarWalkForwardPlan.Fold> folds =
                    RadarWalkForwardPlan.expandingWalkForward(globalDates, requestedFolds);
            analyses.put(key, analyzeV13Group(key, group.getValue(), split, folds,
                    candidateGridsByMarket.getOrDefault(key.market(), unavailableGrid),
                    sigmaProfilesByMarket.getOrDefault(key.market(), unavailableSigma)));
        }

        // 只將 selected candidate 的 untouched holdout/fold evidence 送進 immutable gate；
        // production fold 的可用性由 exact-key joint calendar/sigma 決定，不能再用單一
        // horizon/diagnostic stratum 的 fold profile 取代。
        V13RegistryBuild registryBuild = buildV13Evidence(
                analyses, candidateGridsByMarket, sigmaProfilesByMarket, universeMode);
        failures.addAll(registryBuild.failures());
        TradingRadarV13PromotionRegistry registry = registryBuild.registry();
        List<BacktestDto.MarketHorizonExecution> reports = analyses.values().stream()
                .map(analysis -> toV13GroupReport(
                        analysis, registry, registryBuild.jointFoldSelections(), universeMode))
                .sorted(Comparator.comparing(BacktestDto.MarketHorizonExecution::market)
                        .thenComparingInt(BacktestDto.MarketHorizonExecution::horizon)
                        .thenComparing(report -> Objects.toString(report.instrumentKind(), ""))
                        .thenComparing(report -> Objects.toString(report.productionProfile(), "")))
                .toList();
        Map<String, String> selectedCandidates = new LinkedHashMap<>();
        Map<String, BacktestDto.RuleParameterSnapshot> selectedParameterSnapshots = new LinkedHashMap<>();
        if (universeMode == BacktestDto.UniverseMode.FULL_MARKET) {
            analyses.values().stream()
                    .filter(analysis -> analysis.selectedCandidate() != null)
                    .forEach(analysis -> selectedCandidates.put(
                            v13GroupLabel(analysis.key()),
                            analysis.selectedCandidate().parameterSetId()));
            analyses.values().stream()
                    .filter(analysis -> analysis.selectedCandidate() != null)
                    .forEach(analysis -> selectedParameterSnapshots.put(
                            v13GroupLabel(analysis.key()),
                            parameterSnapshot(analysis.selectedCandidate(), analysis.sigmaProfile())));
        }
        List<String> gridIds = candidateGridsByMarket.values().stream()
                .flatMap(List::stream).map(RuleParameters::parameterSetId).distinct().toList();

        return new BacktestDto.V13Report(
                TradingRadarRuleEngine.RULE_VERSION,
                false,
                universeMode,
                ratio,
                requestedFolds,
                includeSensitivity,
                resolvedCostDtos(costs),
                List.copyOf(reports),
                List.copyOf(failures),
                List.of(
                        "主要樣本固定使用 adjustedOpen[t+1] 到 adjustedOpen[t+1+h]；h 為完整持有 session 數。",
                        "CLOSE_FALLBACK_SENSITIVITY 是獨立敏感度，未進 calibration、holdout promotion 或主要樣本。",
                        "cutoff 與 walk-forward 由 market/horizon 跨標的全域可成交日期建立，晚上市標的不另切自身 70%。",
                        "candidate 以同一份 as-of StockInput 重跑完整 V13 engine；selector 只看 calibration，再以 untouched holdout/fold gate 判定。",
                        sigmaProfilesByMarket.entrySet().stream()
                                .map(entry -> entry.getKey() + ":" + entry.getValue().note())
                                .toList().toString(),
                        "每個 walk-forward fold 都在 trainTo 重新建立 adjusted completed-close sigma profile/grid；profile 無法由 train-only evidence 建立時，標記 FOLD_SIGMA_PROFILE_UNAVAILABLE 並 fail-closed。",
                        universeMode == BacktestDto.UniverseMode.FULL_MARKET
                                ? "registry promotion 僅為本次 report 的 immutable evidence；production runtime 仍明確維持 "
                                        + TradingRadarRuleEngine.RULE_VERSION + "。"
                                : "BOUNDED_DIAGNOSTIC 僅保留逐列診斷；在建立 production registry 前 fail closed。",
                        "report registry promoted key 數="
                                + (registry == null ? 0 : registry.promotedParameters().size())
                                + "；未通過、bounded 或缺證據的 key 維持 baseline fallback。"),
                List.copyOf(gridIds),
                Map.copyOf(selectedCandidates),
                registry == null ? 0 : registry.promotedParameters().size(),
                Map.copyOf(selectedParameterSnapshots));
    }

    private void appendV13Code(
            String code,
            String market,
            LocalDate from,
            LocalDate to,
            List<Integer> horizons,
            boolean includeSensitivity,
            Map<RadarBacktestExecution.CostKey, RadarBacktestExecution.CostAssumption> costs,
            List<RuleParameters> candidateGrid,
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes,
            Map<V13GroupKey, List<V13Attempt>> attempts,
            List<RadarWalkForwardPlan.TradableOpportunity> opportunities,
            Set<String> failures,
            TradingRadarMarketFeatureResolver.Sources marketFeatureSources,
            Map<MarketCalendarKey, CalendarResolution> marketCalendarCache,
            Map<String, Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence>>
                    marketFeatureEvidenceCache) {
        List<StockPriceHistory> raw = priceHistoryRepo
                .findAllByStockCodeAndMarketOrderByTradingDateAsc(code, market);
        if (raw == null || raw.isEmpty()) {
            failures.add(market + "/" + code + ":NO_PRICE_HISTORY");
            return;
        }
        List<StockPriceHistory> rows = raw.stream()
                .filter(row -> row.getClosePrice() != null && row.getClosePrice().signum() > 0)
                .toList();
        if (rows.size() <= WARMUP) {
            failures.add(market + "/" + code + ":INSUFFICIENT_WARMUP[rows=" + rows.size() + "]");
            return;
        }
        LocalDate seriesFrom = rows.get(0).getTradingDate();
        LocalDate seriesTo = rows.get(rows.size() - 1).getTradingDate();
        List<StockDividendHistory> events = dividendHistoryRepo
                .findAdjustmentEvents(code, market, seriesFrom, seriesTo);
        List<RadarBacktestExecution.AdjustedBar> bars = RadarBacktestExecution.adjustedBars(
                rows, events == null ? List.of() : events, adjustedPriceService);
        CalendarResolution calendar = resolveV13Calendar(
                market, bars, from, to, marketCalendarCache);
        // Market features combine local-market liquidity with US indices and
        // commodities.  Resolve the US calendar once per code/request so each
        // signal can enforce its own exact terminal session without deriving
        // freshness from whichever source happens to be latest.
        CalendarResolution usCalendar = RadarBacktestExecution.US_MARKET.equals(market)
                ? calendar
                : resolveV13Calendar(RadarBacktestExecution.US_MARKET, bars, from, to, marketCalendarCache);

        Optional<Stock> stock = stockRepo.findByCodeAndMarket(code, market);
        TradingRadarAssetProfileResolver.AssetProfile profile = TradingRadarAssetProfileResolver.resolve(
                stock.orElse(null), code, market, stock.map(Stock::getName).orElse(code),
                null, stockStyleIncomeThreshold());
        RadarBacktestExecution.InstrumentKind kind = executionInstrumentKind(profile.instrumentKind());
        if (kind == null) {
            failures.add(market + "/" + code + ":UNKNOWN_INSTRUMENT_KIND");
            return;
        }
        RadarBacktestExecution.CostKey costKey = new RadarBacktestExecution.CostKey(market, kind);
        RadarBacktestExecution.CostAssumption cost = costs.get(costKey);
        if (cost == null) {
            failures.add(market + "/" + code + ":MISSING_COST_ASSUMPTION[" + kind + "]");
            return;
        }

        List<EtfNavObservation> premiumObservations = loadPremiumObservations(
                code, market, rows.get(rows.size() - 1).getTradingDate());
        String currency = profile.underlyingCurrency();
        FxSeries fxSeries = currency == null || TWD.equals(currency)
                ? null : fxSeries(currency, new HashMap<>());
        List<java.time.Instant> fundamentalInstants = rows.stream()
                .skip(WARMUP)
                .map(StockPriceHistory::getTradingDate)
                .filter(date -> from == null || !date.isBefore(from))
                .filter(date -> to == null || !date.isAfter(to))
                .map(date -> signalInstant(date, market))
                .toList();
        // V13 需要 snapshot provenance 給 EvidenceConfidenceResolver；舊 mock／舊呼叫端若
        // 未提供 Resolved map，才退回既有 FundamentalInput batch（不把其偽裝成完整 evidence）。
        Map<java.time.Instant, FundamentalAnalysisService.Resolved> resolvedFundamentalByInstant =
                fundamentalAnalysisService.resolveResolvedInputsForBacktest(
                        code, market, fundamentalInstants, profile);
        if (resolvedFundamentalByInstant == null || resolvedFundamentalByInstant.isEmpty()) {
            resolvedFundamentalByInstant = fundamentalAnalysisService.resolveResolvedInputsForBacktest(
                    code, market, fundamentalInstants);
        }
        Map<java.time.Instant, TradingRadarRuleEngine.FundamentalInput> legacyFundamentalByInstant =
                resolvedFundamentalByInstant == null || resolvedFundamentalByInstant.isEmpty()
                        ? fundamentalAnalysisService.resolveInputsForBacktest(code, market, fundamentalInstants)
                        : Map.of();
        Map<java.time.Instant, DividendEventEvidenceResolver.Resolution> dividendByInstant =
                resolveV13DividendEvidence(code, market, bars, from, to, calendar.sessions());
        List<java.time.Instant> marketDecisionInstants = bars.stream()
                .skip(WARMUP)
                .map(RadarBacktestExecution.AdjustedBar::date)
                .filter(Objects::nonNull)
                .filter(date -> from == null || !date.isBefore(from))
                .filter(date -> to == null || !date.isAfter(to))
                .map(date -> signalInstant(date, market))
                .toList();
        Map<LocalDate, TradingRadarEvidenceConfidenceResolver.MarketContext> marketContextsByDate =
                resolveV13MarketContextsBatch(market, marketDecisionInstants);
        Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence> marketFeaturesByInstant =
                resolveV13MarketFeaturesBatch(market, marketDecisionInstants, marketFeatureSources,
                        marketFeatureEvidenceCache, calendar, usCalendar);
        // Bond beta and Treasury context are immutable as-of observations.  Resolve every
        // candidate query once per code/request instead of rereading full price, dividend,
        // FX and Treasury history inside the signal × candidate loop.
        Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> bondBetaByQuery =
                resolveV13BondYieldBetaBatch(profile, code, market,
                        marketDecisionInstants, candidateGrid);
        Map<TreasuryObservationKey, TradingRadarEvidenceConfidenceResolver.RateObservation>
                treasuryObservationCache = new HashMap<>();
        String productionProfile = productionProfile(profile);

        for (int t = WARMUP; t < bars.size(); t++) {
            LocalDate signalDate = bars.get(t).date();
            if (from != null && signalDate.isBefore(from)) continue;
            if (to != null && signalDate.isAfter(to)) continue;

            // V13 candidate 與 legacy baseline 共用 signal instant 前的完整技術／基本面／FX／折溢價輸入。
            int rawIndex = t;
            int lo = Math.max(0, rawIndex - WINDOW + 1);
            List<StockPriceHistory> windowDesc = new ArrayList<>(rows.subList(lo, rawIndex + 1));
            Collections.reverse(windowDesc);
            List<StockDividendHistory> windowEvents = eventsWithin(
                    events == null ? List.of() : events,
                    windowDesc.get(windowDesc.size() - 1).getTradingDate(), signalDate);
            BigDecimal price = windowDesc.get(0).getClosePrice();
            RadarInputAssembler.Assembled assembled = assembler.assemble(
                    windowDesc, windowEvents, false,
                    Math.min(windowDesc.size(), DAILY_CONTRACT_ROWS), DAILY_CONTRACT_ROWS, price);
            TradingRadarRuleEngine.MarketRegime regime = regimes.getOrDefault(
                    signalDate, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE);
            TradingRadarMarketContextService.FxContext fx = fxSeries == null
                    ? null : fxAt(currency, fxSeries, signalDate, market);
            BigDecimal fxPct = fx == null ? null : fx.percentile();
            java.time.Instant decisionInstant = signalInstant(signalDate, market);
            TradingRadarPremiumResolver.DecisionObservation premiumObservation =
                    resolvePremiumObservation(profile, market, signalDate, decisionInstant, premiumObservations);
            BigDecimal premium = premiumObservation.value();
            BigDecimal premiumPercentile = premiumPercentile(
                    market, premiumObservation, decisionInstant, premiumObservations);
            TradingRadarMarketFeatureResolver.Evidence marketFeatures =
                    marketFeaturesByInstant.getOrDefault(decisionInstant,
                            TradingRadarMarketFeatureResolver.resolve(
                                    market, decisionInstant, marketFeatureSources));
            FundamentalAnalysisService.Resolved resolvedFundamental =
                    resolvedFundamentalByInstant == null ? null
                            : resolvedFundamentalByInstant.get(decisionInstant);
            TradingRadarRuleEngine.FundamentalInput fundamental = resolvedFundamental != null
                    ? resolvedFundamental.input()
                    : legacyFundamentalByInstant == null
                    ? TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE
                    : legacyFundamentalByInstant.getOrDefault(decisionInstant,
                            FundamentalAnalysisService.Resolved.unavailable(true).input());
            TradingRadarRuleEngine.StockInput freeInput = v13Input(
                    assembled, false, price, kind, regime, fxPct, premium, premiumPercentile, fundamental);
            TradingRadarRuleEngine.StockInput heldInput = v13Input(
                    assembled, true, price, kind, regime, fxPct, premium, premiumPercentile, fundamental);
            // Raw V12 is needed only for timing evidence; final baseline actions are
            // evidence-gated through evaluateBaseline, matching candidate semantics
            // without changing production evaluateStock.
            TradingRadarRuleEngine.StockResult rawBaselineFree = ruleEngine.evaluateStock(freeInput);
            TradingRadarRuleEngine.StockResult rawBaselineHeld = ruleEngine.evaluateStock(heldInput);
            TradingRadarAssetProfileResolver.AssetProfile evidenceProfile =
                    TradingRadarAssetProfileResolver.resolve(
                            stock.orElse(null), code, market, stock.map(Stock::getName).orElse(code),
                            resolvedFundamental == null || resolvedFundamental.snapshot() == null
                                    ? null : resolvedFundamental.snapshot().dividendYieldPct(),
                            stockStyleIncomeThreshold());
            TradingRadarEvidenceConfidenceResolver.MarketContext marketContext =
                    marketContextsByDate.getOrDefault(signalDate,
                            TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY);
            // verified 與 indicator 兩份都傳 windowDesc（Task 319.6）：回測視窗本來就是完整還原視窗、
            // 不具 provenance 概念，兩者同值語意正確；漏傳其一會讓 production 與回測分岔。
            RadarObservationResolver.AcceptedPrice acceptedPrice = new RadarObservationResolver.AcceptedPrice(
                    price, signalDate, null, "BACKTEST_COMPLETED_CLOSE",
                    RadarObservationResolver.Quality.COMPLETED_CLOSE, false, null,
                    windowDesc, windowDesc, null);
            TradingRadarDto.FundamentalSnapshot fundamentalSnapshot = resolvedFundamental == null
                    || resolvedFundamental.snapshot() == null
                    ? FundamentalAnalysisService.Resolved.unavailable(
                            fundamental != null && fundamental.applicable()).snapshot()
                    : resolvedFundamental.snapshot();
            RuleParameters baselineParameters = RuleParameters.v12Default();
            BondYieldBetaResolver.Result bondYieldBeta = resolveV13BondYieldBeta(
                    evidenceProfile, code, market, decisionInstant, baselineParameters, bondBetaByQuery);
            TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation =
                    resolveV13TreasuryRateObservation(
                            evidenceProfile, decisionInstant, baselineParameters, bondYieldBeta,
                            treasuryObservationCache);
            DividendEventEvidenceResolver.Resolution dividendEvent = dividendByInstant.getOrDefault(
                    decisionInstant, DividendEventEvidenceResolver.Resolution.MISSING);
            TradingRadarRuleEngine.CandidateContext context = v13Context(
                    assembled, regime, fundamental, price, market, decisionInstant, acceptedPrice,
                    marketContext, fundamentalSnapshot, evidenceProfile, premium, signalDate,
                    premiumObservation.source(), premiumObservation.stale(),
                    fx, rateObservation, rawBaselineFree.timingState(), dividendEvent, marketFeatures,
                    bondYieldBeta, acceptedPrice.tradingDate(), sigmaProfileAvailable(candidateGrid));
            V13CandidateOutcome baseline = new V13CandidateOutcome(
                    ruleEngine.evaluateBaseline(freeInput, context),
                    ruleEngine.evaluateBaseline(heldInput, context));
            Map<String, TradingRadarRuleEngine.CandidateContext> candidateContexts = new LinkedHashMap<>();
            for (RuleParameters candidate : candidateGrid) {
                if (!RuleParameters.V13_VERSION.equals(candidate.ruleVersion())) continue;
                BondYieldBetaResolver.Result candidateBondYieldBeta = resolveV13BondYieldBeta(
                        evidenceProfile, code, market, decisionInstant, candidate, bondBetaByQuery);
                TradingRadarEvidenceConfidenceResolver.RateObservation candidateRateObservation =
                        resolveV13TreasuryRateObservation(
                                evidenceProfile, decisionInstant, candidate, candidateBondYieldBeta,
                                treasuryObservationCache);
                TradingRadarRuleEngine.CandidateContext candidateContext = v13Context(
                        assembled, regime, fundamental, price, market, decisionInstant, acceptedPrice,
                        marketContext, fundamentalSnapshot, evidenceProfile, premium, signalDate,
                        premiumObservation.source(), premiumObservation.stale(),
                        fx, candidateRateObservation, rawBaselineFree.timingState(), dividendEvent,
                        marketFeatures, candidateBondYieldBeta, acceptedPrice.tradingDate(),
                        sigmaProfileAvailable(candidateGrid));
                // Threshold/weight-only candidates share all evidence with baseline.  Keep an
                // override only when the strict bond beta/rate evidence actually differs.
                if (!candidateContext.equals(context)) {
                    candidateContexts.put(candidate.parameterSetId(), candidateContext);
                }
            }
            TradingRadarEvidenceConfidenceResolver.Evidence signalEvidence = context.evidence();
            for (int horizon : horizons) {
                RadarBacktestExecution.ExecutionAttempt execution = RadarBacktestExecution.execute(
                        bars, t, horizon, costKey, cost, includeSensitivity);
                attempts.get(new V13GroupKey(market, horizon)).add(new V13Attempt(
                        code, kind, productionProfile(evidenceProfile),
                        identity(evidenceProfile == null ? null : evidenceProfile.assetClass()),
                        identity(evidenceProfile == null ? null : evidenceProfile.stockStyle()),
                        identity(evidenceProfile == null ? null : evidenceProfile.bondTerm()),
                        confidenceDecile(signalEvidence, horizon),
                        execution, baseline, freeInput, heldInput, context,
                        candidateGrid, Map.copyOf(candidateContexts)));
                boolean enoughForward = !execution.excludedInsufficientForward();
                boolean costEffective = !execution.excludedCostOutsideEffectiveRange();
                opportunities.add(new RadarWalkForwardPlan.TradableOpportunity(
                        market, horizon, code, signalDate,
                        enoughForward && costEffective && !execution.excludedMissingEntryOpen(),
                        enoughForward && costEffective && !execution.excludedMissingExitOpen()));
            }
        }
    }

    private TradingRadarMarketFeatureResolver.Sources loadV13MarketFeatureSources() {
        try {
            List<UsIndexDailyHistory> us = new ArrayList<>();
            for (String index : List.of("IXIC", "SOX", "SPX", "DJI")) {
                List<UsIndexDailyHistory> rows = usIndexRepo.findByIndexCodeOrderByTradingDateAsc(index);
                if (rows != null) us.addAll(rows);
            }
            List<TwseIndexDailyHistory> tw = twseRepo.findAllByOrderByTradingDateAsc();
            return new TradingRadarMarketFeatureResolver.Sources(us, tw, Map.of());
        } catch (RuntimeException e) {
            log.warn("V13 market feature source 讀取失敗，全部標記 MISSING：{}", e.getMessage());
            return TradingRadarMarketFeatureResolver.Sources.empty();
        }
    }

    /**
     * Resolve all V13 market features for one code's signal dates from a single
     * bounded source snapshot.  The port's batch hook prevents one DB load per
     * signal; a pure fallback remains available for legacy mocks and offline
     * tests.  Returned evidence is immutable and keyed by decision instant.
     */
    private Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence>
            resolveV13MarketFeaturesBatch(
                    String market,
                    List<java.time.Instant> decisionInstants,
            TradingRadarMarketFeatureResolver.Sources fallbackSources,
            Map<String, Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence>>
                            requestCache,
                    CalendarResolution marketCalendar,
                    CalendarResolution usCalendar) {
        if (decisionInstants == null || decisionInstants.isEmpty()) return Map.of();
        if (marketFeaturePort != null) {
            try {
                Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence> cached =
                        requestCache == null ? null : requestCache.computeIfAbsent(
                                market, ignored -> new LinkedHashMap<>());
                List<java.time.Instant> missing = decisionInstants.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .filter(instant -> cached == null || !cached.containsKey(instant))
                        .toList();
                if (!missing.isEmpty()) {
                    Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence> resolved =
                            marketFeaturePort.resolveBatch(
                                    market, missing,
                                    decision -> expectedFeatureSessions(
                                            market, decision, marketCalendar, usCalendar));
                    if (resolved != null && !resolved.isEmpty() && cached != null) {
                        resolved.forEach((instant, evidence) -> {
                            if (instant != null && evidence != null) cached.put(instant, evidence);
                        });
                    }
                    if (cached == null && resolved != null && !resolved.isEmpty()) {
                        return Map.copyOf(resolved);
                    }
                }
                if (cached != null && decisionInstants.stream()
                        .allMatch(instant -> instant != null && cached.containsKey(instant))) {
                    return Map.copyOf(decisionInstants.stream().filter(Objects::nonNull).distinct()
                            .collect(java.util.stream.Collectors.toMap(
                                    instant -> instant, cached::get, (left, right) -> left,
                                    LinkedHashMap::new)));
                }
            } catch (RuntimeException e) {
                log.warn("V13 market feature batch 解析失敗（{}，n={}），改用 bounded fallback：{}",
                        market, decisionInstants.size(), e.getMessage());
            }
        }
        Map<java.time.Instant, TradingRadarMarketFeatureResolver.Evidence> fallback = new LinkedHashMap<>();
        TradingRadarMarketFeatureResolver.Sources sources = fallbackSources == null
                ? TradingRadarMarketFeatureResolver.Sources.empty() : fallbackSources;
        for (java.time.Instant decision : decisionInstants) {
            if (decision != null) {
                fallback.put(decision,
                        TradingRadarMarketFeatureResolver.resolve(
                                market, decision, sources,
                                expectedFeatureSessions(market, decision, marketCalendar, usCalendar)));
            }
        }
        if (requestCache != null && !fallback.isEmpty()) {
            requestCache.computeIfAbsent(market, ignored -> new LinkedHashMap<>())
                    .putAll(fallback);
        }
        return Map.copyOf(fallback);
    }

    private TradingRadarMarketFeatureResolver.ExpectedSessions expectedFeatureSessions(
            String market,
            java.time.Instant decisionInstant,
            CalendarResolution marketCalendar,
            CalendarResolution usCalendar) {
        LocalDate marketSession = terminalSessionForFeature(market, decisionInstant, marketCalendar);
        LocalDate usSession = terminalSessionForFeature(
                RadarBacktestExecution.US_MARKET, decisionInstant, usCalendar);
        // Backtest calendar resolution is itself a hard evidence boundary.  A
        // known calendar with no session in range and an unavailable calendar
        // both remain strict-null terminals and therefore produce MISSING.
        return TradingRadarMarketFeatureResolver.ExpectedSessions.strict(
                marketSession, usSession, usSession);
    }

    private LocalDate terminalSessionForFeature(
            String market,
            java.time.Instant decisionInstant,
            CalendarResolution calendar) {
        if (calendar == null || !calendar.known() || decisionInstant == null) return null;
        LocalDate localDate = decisionInstant.atZone(MarketZones.resolve(market)).toLocalDate();
        return calendar.sessions().stream()
                .filter(Objects::nonNull)
                .filter(date -> !date.isAfter(localDate))
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** Resolve all historical dividend evidence in one append-only batch; never project current state. */
    private Map<java.time.Instant, DividendEventEvidenceResolver.Resolution> resolveV13DividendEvidence(
            String code,
            String market,
            List<RadarBacktestExecution.AdjustedBar> bars,
            LocalDate from,
            LocalDate to,
            List<LocalDate> sessions) {
        if (bars == null || bars.isEmpty()) return Map.of();
        List<DividendEventEvidenceBatch.Query> queries = bars.stream()
                .skip(WARMUP)
                .map(RadarBacktestExecution.AdjustedBar::date)
                .filter(date -> from == null || !date.isBefore(from))
                .filter(date -> to == null || !date.isAfter(to))
                .map(date -> new DividendEventEvidenceBatch.Query(
                        code, market, signalInstant(date, market), sessions))
                .toList();
        if (queries.isEmpty()) return Map.of();
        if (dividendEventEvidenceRepository == null) {
            return queries.stream().collect(java.util.stream.Collectors.toMap(
                    DividendEventEvidenceBatch.Query::decisionInstant,
                    ignored -> DividendEventEvidenceResolver.Resolution.MISSING,
                    (left, right) -> left, LinkedHashMap::new));
        }
        try {
            List<DividendEventEvidenceBatch.Result> resolved =
                    dividendEventEvidenceRepository.resolveBatch(queries);
            if (resolved == null) return Map.of();
            return resolved.stream()
                    .filter(Objects::nonNull)
                    .filter(result -> result.query() != null && result.query().decisionInstant() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            result -> result.query().decisionInstant(),
                            result -> result.resolution() == null
                                    ? DividendEventEvidenceResolver.Resolution.MISSING
                                    : result.resolution(),
                            (left, right) -> right, LinkedHashMap::new));
        } catch (RuntimeException e) {
            log.warn("V13 dividend evidence batch 解析失敗（{}/{}）：{}", market, code, e.getMessage());
            return queries.stream().collect(java.util.stream.Collectors.toMap(
                    DividendEventEvidenceBatch.Query::decisionInstant,
                    ignored -> DividendEventEvidenceResolver.Resolution.MISSING,
                    (left, right) -> left, LinkedHashMap::new));
        }
    }

    /**
     * Build the session list from the authoritative market calendar, not from a
     * single instrument's completed-close rows.  A missing close on an otherwise
     * open date must still count toward the 5/20-session dividend window.
     */
    private CalendarResolution resolveV13Calendar(
            String market,
            List<RadarBacktestExecution.AdjustedBar> bars,
            LocalDate from,
            LocalDate to,
            Map<MarketCalendarKey, CalendarResolution> cache) {
        if (bars == null || bars.isEmpty()) return new CalendarResolution(List.of(), false);
        List<LocalDate> dates = bars.stream().map(RadarBacktestExecution.AdjustedBar::date)
                .filter(Objects::nonNull).sorted().toList();
        if (dates.isEmpty()) return new CalendarResolution(List.of(), false);
        LocalDate calendarFrom = dates.getFirst();
        if (from != null && from.isBefore(calendarFrom)) calendarFrom = from;
        LocalDate calendarTo = dates.getLast();
        if (to != null && to.isAfter(calendarTo)) calendarTo = to;
        // Dividend evidence covers decision day through +45 calendar days; include
        // that tail so the final in-range signals do not silently lose 20 sessions.
        calendarTo = calendarTo.plusDays(45);
        final LocalDate resolvedFrom = calendarFrom;
        final LocalDate resolvedTo = calendarTo;
        MarketCalendarKey key = new MarketCalendarKey(market, resolvedFrom, resolvedTo);
        if (cache == null) {
            return resolveV13CalendarUncached(market, dates, resolvedFrom, resolvedTo);
        }
        return cache.computeIfAbsent(key,
                ignored -> resolveV13CalendarUncached(market, dates, resolvedFrom, resolvedTo));
    }

    private CalendarResolution resolveV13CalendarUncached(
            String market,
            List<LocalDate> fallbackDates,
            LocalDate calendarFrom,
            LocalDate calendarTo) {
        // Legacy unit-test constructors predate the calendar dependency.  Their
        // close dates remain a compatibility fallback; a real context service
        // returns known=false on any unresolved calendar date and we fail closed.
        if (marketContextService == null) {
            return new CalendarResolution(fallbackDates, true);
        }
        TradingRadarMarketContextService.TradingSessions resolved =
                marketContextService.resolveTradingSessions(market, calendarFrom, calendarTo);
        if (resolved == null) {
            // Mockito/legacy adapters that do not implement the new hook retain
            // deterministic old-test behavior without affecting production wiring.
            return new CalendarResolution(fallbackDates, true);
        }
        return new CalendarResolution(resolved.dates(), resolved.known());
    }

    private Map<V13GroupKey, List<V13Attempt>> partitionV13Attempts(
            Map<V13GroupKey, List<V13Attempt>> baseAttempts) {
        Map<V13GroupKey, List<V13Attempt>> out = new LinkedHashMap<>();
        if (baseAttempts == null) return out;
        for (var entry : baseAttempts.entrySet()) {
            V13GroupKey base = entry.getKey();
            List<V13Attempt> values = entry.getValue() == null ? List.of() : entry.getValue();
            if (values.isEmpty()) {
                out.put(base, new ArrayList<>());
                continue;
            }
            Map<V13GroupKey, List<V13Attempt>> partitions = new LinkedHashMap<>();
            for (V13Attempt attempt : values) {
                V13GroupKey key = new V13GroupKey(base.market(), base.horizon(),
                        attempt.instrumentKind(), attempt.productionProfile(),
                        attempt.assetClass(), attempt.stockStyle(), attempt.bondTerm(),
                        attempt.confidenceDecile());
                partitions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(attempt);
            }
            partitions.forEach((key, attempts) -> out.put(key, List.copyOf(attempts)));
        }
        return out;
    }

    private String v13GroupLabel(V13GroupKey key) {
        String kind = key.instrumentKind() == null ? "UNKNOWN_INSTRUMENT" : key.instrumentKind().name();
        String profile = key.productionProfile() == null ? "UNKNOWN_PROFILE" : key.productionProfile();
        return key.market() + "/" + kind + "/" + profile
                + "/asset=" + identity(key.assetClass())
                + "/style=" + identity(key.stockStyle())
                + "/term=" + identity(key.bondTerm())
                + "/confidence=" + confidenceIdentity(key.confidenceDecile())
                + "/h=" + key.horizon();
    }

    private V13GroupAnalysis analyzeV13Group(
            V13GroupKey key,
            List<V13Attempt> attempts,
            RadarWalkForwardPlan.ChronologicalSplit split,
            List<RadarWalkForwardPlan.Fold> folds,
            List<RuleParameters> candidateGrid,
            CalibrationSigmaProfile sigmaProfile) {
        List<V13Attempt> primary = attempts.stream()
                .filter(attempt -> attempt.execution().primary().isPresent())
                .toList();
        LocalDate calibrationBoundary = firstDate(split.holdoutDates());
        List<V13Attempt> calibration = split.sufficientForCutoff()
                ? primary.stream().filter(attempt -> split.isCalibration(
                        attempt.execution().signalDate()))
                        .filter(attempt -> eligibleBeforeBoundary(attempt, calibrationBoundary)).toList()
                : List.of();
        List<V13Attempt> holdout = split.sufficientForCutoff()
                ? primary.stream().filter(attempt -> split.isHoldout(
                        attempt.execution().signalDate())).toList()
                : List.of();
        List<RuleParameters> v13Candidates = candidateGrid.stream()
                .filter(candidate -> RuleParameters.V13_VERSION.equals(candidate.ruleVersion()))
                .toList();
        List<BacktestDto.CandidateCalibration> calibrationEvidence = new ArrayList<>();
        List<TradingRadarCalibrationSelector.CalibrationScore> selectorScores = new ArrayList<>();
        Map<String, V13CandidateStats> calibrationStats = new LinkedHashMap<>();
        if (split.sufficientForCutoff()) {
            for (RuleParameters candidate : v13Candidates) {
                V13CandidateStats stats = candidateStats(calibration, candidate.parameterSetId(), key.horizon());
                calibrationStats.put(candidate.parameterSetId(), stats);
                if (stats.intersectionN() > 0) {
                    selectorScores.add(new TradingRadarCalibrationSelector.CalibrationScore(
                            candidate,
                            stats.intersectionCandidateDownsideRatePct(),
                            stats.pairedMedianDelta(),
                            pooledDelta(stats)));
                }
            }
        }
        RuleParameters selected = split.sufficientForCutoff()
                ? TradingRadarCalibrationSelector.select(selectorScores, RuleParameters.v12Default())
                        .orElse(null)
                : null;
        for (RuleParameters candidate : v13Candidates) {
            V13CandidateStats stats = calibrationStats.get(candidate.parameterSetId());
            if (stats == null) stats = candidateStats(List.of(), candidate.parameterSetId(), key.horizon());
            calibrationEvidence.add(new BacktestDto.CandidateCalibration(
                    candidate.parameterSetId(), candidate.ruleVersion(),
                    stats.candidateN(), stats.candidateCodes(), stats.baselineN(), stats.baselineCodes(),
                    stats.intersectionN(), stats.intersectionCodes(),
                    stats.intersectionN() > 0
                            ? stats.intersectionCandidateDownsideRatePct()
                            : stats.candidateDownsideRatePct(),
                    stats.pairedMedianDelta(), pooledDelta(stats),
                    selected != null && selected.parameterSetId().equals(candidate.parameterSetId()),
                    stats.intersectionCandidateDownsideRatePct(),
                    stats.intersectionBaselineDownsideRatePct(),
                    trackCoverage(stats.entry()), trackCoverage(stats.held()),
                    parameterSnapshot(candidate, sigmaProfile)));
        }
        List<String> profiles = attempts.stream().map(V13Attempt::productionProfile).distinct().toList();
        String profile = profiles.size() == 1 ? profiles.get(0) : null;
        List<RadarBacktestExecution.InstrumentKind> kinds = attempts.stream()
                .map(V13Attempt::instrumentKind).distinct().toList();
        RadarBacktestExecution.InstrumentKind kind = kinds.size() == 1 ? kinds.get(0) : null;
        Map<Integer, RuleParameters> foldSelections = new LinkedHashMap<>();
        Map<Integer, CalibrationSigmaProfile> foldSigmaProfiles = new LinkedHashMap<>();
        boolean foldSigmaProfileUnavailable = false;
        for (RadarWalkForwardPlan.Fold fold : folds) {
            List<V13Attempt> train = attempts.stream()
                    .filter(attempt -> fold.trainDates().contains(attempt.execution().signalDate()))
                    .filter(attempt -> attempt.execution().primary().isPresent())
                    .filter(attempt -> eligibleBeforeBoundary(attempt, fold.evaluationFrom()))
                    .toList();
            List<String> foldCodes = train.stream().map(V13Attempt::code)
                    .filter(Objects::nonNull).distinct().sorted().toList();
            CalibrationSigmaProfile foldSigma = calibrateSigmaProfileForFold(
                    key.market(), foldCodes, train, fold.trainDates());
            foldSigmaProfiles.put(fold.index(), foldSigma);
            if (!foldSigma.available()) {
                // A fold with no train-side volatility evidence cannot safely select a
                // normalized candidate.  Keep the failure explicit and do not borrow the
                // request/global profile (which may include later observations).
                foldSigmaProfileUnavailable = true;
                continue;
            }
            List<RuleParameters> foldGrid = v13CandidateGrid(foldSigma).stream()
                    .filter(candidate -> RuleParameters.V13_VERSION.equals(candidate.ruleVersion()))
                    .toList();
            RuleParameters foldSelected = selectV13Candidate(
                    train, key.horizon(), foldGrid, true, foldSigma);
            if (foldSelected != null) foldSelections.put(fold.index(), foldSelected);
        }
        return new V13GroupAnalysis(key, List.copyOf(attempts), split, List.copyOf(folds), selected,
                List.copyOf(calibrationEvidence), profile, kind, key.assetClass(), key.stockStyle(),
                key.bondTerm(), key.confidenceDecile(), trackForHorizon(key.horizon()),
                Map.copyOf(foldSelections), Map.copyOf(foldSigmaProfiles),
                foldSigmaProfileUnavailable, sigmaProfile);
    }

    private BacktestDto.MarketHorizonExecution toV13GroupReport(
            V13GroupAnalysis analysis,
            TradingRadarV13PromotionRegistry registry,
            Map<TradingRadarV13PromotionRegistry.ProductionKey,
                    Map<Integer, JointFoldSelection>> jointFoldSelections,
            BacktestDto.UniverseMode universeMode) {
        V13GroupKey key = analysis.key();
        List<V13Attempt> attempts = analysis.attempts();
        RadarWalkForwardPlan.ChronologicalSplit split = analysis.split();
        List<RadarWalkForwardPlan.Fold> folds = analysis.folds();
        List<V13Attempt> primary = attempts.stream()
                .filter(attempt -> attempt.execution().primary().isPresent()).toList();
        LocalDate calibrationBoundary = firstDate(split.holdoutDates());
        List<V13Attempt> calibration = split.sufficientForCutoff()
                ? primary.stream().filter(attempt -> split.isCalibration(
                        attempt.execution().signalDate()))
                        .filter(attempt -> eligibleBeforeBoundary(attempt, calibrationBoundary)).toList() : List.of();
        List<V13Attempt> holdout = split.sufficientForCutoff()
                ? primary.stream().filter(attempt -> split.isHoldout(
                        attempt.execution().signalDate())).toList() : List.of();
        int missingEntry = (int) attempts.stream()
                .filter(attempt -> attempt.execution().excludedMissingEntryOpen()).count();
        int missingExit = (int) attempts.stream()
                .filter(attempt -> attempt.execution().excludedMissingExitOpen()).count();
        int insufficientForward = (int) attempts.stream()
                .filter(attempt -> attempt.execution().excludedInsufficientForward()).count();
        int costOutsideEffectiveRange = (int) attempts.stream()
                .filter(attempt -> attempt.execution().excludedCostOutsideEffectiveRange()).count();
        int sensitivityN = (int) attempts.stream()
                .filter(attempt -> attempt.execution().closeSensitivity().isPresent()).count();

        TradingRadarV13PromotionRegistry.HorizonDecision horizonDecision = null;
        TradingRadarV13PromotionRegistry.PromotionDecision promotionDecision = null;
        TradingRadarV13PromotionRegistry.ProductionKey productionKey = null;
        if (analysis.track() != null && analysis.instrumentKind() != null
                && analysis.productionProfile() != null) {
            productionKey = new TradingRadarV13PromotionRegistry.ProductionKey(
                    key.market(), analysis.instrumentKind(), analysis.productionProfile(), analysis.track());
        }
        if (universeMode == BacktestDto.UniverseMode.FULL_MARKET
                && registry != null && productionKey != null) {
            promotionDecision = registry.decision(productionKey);
            horizonDecision = promotionDecision.horizons().get(key.horizon());
        }
        Map<Integer, JointFoldSelection> productionJointFolds = productionKey == null
                || jointFoldSelections == null
                ? Map.of() : jointFoldSelections.getOrDefault(productionKey, Map.of());
        boolean productionKeyPromoted = horizonDecision != null && horizonDecision.promoted();
        String productionCandidateId = productionKeyPromoted && productionKey != null
                ? registry.resolve(productionKey).parameterSetId() : null;
        boolean stratumMatchesProduction = productionKeyPromoted
                && analysis.selectedCandidate() != null
                && Objects.equals(productionCandidateId, analysis.selectedCandidate().parameterSetId());
        String status;
        String reason;
        String promotionEvidenceScope;
        if (universeMode == BacktestDto.UniverseMode.BOUNDED_DIAGNOSTIC) {
            status = "INSUFFICIENT_DIAGNOSTIC_ONLY";
            reason = "INSUFFICIENT_DIAGNOSTIC_ONLY";
            promotionEvidenceScope = "NONE";
        } else if (!split.sufficientForCutoff()) {
            status = "INSUFFICIENT_RETAIN_V12";
            reason = "INSUFFICIENT_GLOBAL_TRADABLE_DATES";
            promotionEvidenceScope = "NONE";
        } else if (productionKeyPromoted) {
            // The registry decision is based on the pooled exact-key sample.
            // This stratum's own action distribution is diagnostic only; it
            // must never be labelled as an independent promotion pass.
            status = stratumMatchesProduction
                    ? "PROMOTION_KEY_GATE_PASS_STRATUM_MATCH_DIAGNOSTIC_ONLY"
                    : "PROMOTION_KEY_GATE_PASS_STRATUM_MISMATCH";
            reason = "PRODUCTION_KEY_POOLED:" + horizonDecision.reason()
                    + (stratumMatchesProduction ? "" : ";STRATUM_SELECTED_CANDIDATE_MISMATCH");
            promotionEvidenceScope = "PRODUCTION_KEY_POOLED_STRATUM_DIAGNOSTIC_ONLY";
        } else if (analysis.selectedCandidate() == null) {
            status = "INSUFFICIENT_RETAIN_V12";
            reason = "INSUFFICIENT_CALIBRATION_INTERSECTION";
            promotionEvidenceScope = "NONE";
        } else if (horizonDecision == null) {
            status = "REJECTED_RETAIN_V12";
            reason = promotionDecision == null
                    ? "NO_TRACK_PROMOTION_KEY" : promotionDecision.reason();
            promotionEvidenceScope = promotionDecision == null
                    ? "NONE" : "PRODUCTION_KEY_POOLED";
        } else {
            status = "REJECTED_RETAIN_V12";
            reason = "PRODUCTION_KEY_POOLED:" + horizonDecision.reason();
            promotionEvidenceScope = "PRODUCTION_KEY_POOLED";
        }
        BacktestDto.PromotionHorizonEvidence promotionEvidence = null;
        if (analysis.selectedCandidate() != null) {
            String candidateId = analysis.selectedCandidate().parameterSetId();
            V13CandidateStats holdoutStats = candidateStats(holdout, candidateId, key.horizon());
            List<TradingRadarV13PromotionRegistry.FoldMetrics> foldMetrics = folds.stream()
                    .map(fold -> {
                        JointFoldSelection joint = productionJointFolds.get(fold.index());
                        RuleParameters selected = analysis.track() == null
                                ? analysis.foldSelectedCandidates().get(fold.index())
                                : joint == null ? null : joint.selectedCandidate();
                        CalibrationSigmaProfile sigma = analysis.track() == null
                                ? analysis.foldSigmaProfiles().get(fold.index())
                                : joint == null ? null : joint.sigmaProfile();
                        return toFoldMetrics(fold, primary, selected, key.horizon(), sigma);
                    }).toList();
            BigDecimal practical = firstRoundTripCost(primary);
            if (practical != null) practical = practical.max(new BigDecimal("0.10"));
            int validFolds = (int) foldMetrics.stream().filter(this::validFoldSample).count();
            int passingFolds = horizonDecision == null ? 0 : horizonDecision.passingFolds();
            boolean catastrophic = horizonDecision != null && horizonDecision.catastrophicFold();
            promotionEvidence = new BacktestDto.PromotionHorizonEvidence(
                    key.horizon(), candidateId, holdoutStats.intersectionN(), holdoutStats.intersectionCodes(),
                    holdoutStats.pairedMedianDelta(), pooledDelta(holdoutStats),
                    downsideImprovement(holdoutStats), practical, validFolds, passingFolds,
                    catastrophic, productionKeyPromoted && stratumMatchesProduction, reason,
                    holdoutStats.intersectionN(), holdoutStats.intersectionCodes(),
                    holdoutStats.candidateN(), holdoutStats.baselineN(),
                    trackCoverage(holdoutStats.entry()), trackCoverage(holdoutStats.held()),
                    parameterSnapshot(analysis.selectedCandidate(), analysis.sigmaProfile()));
        }
        return new BacktestDto.MarketHorizonExecution(
                key.market(), key.horizon(), split.cutoff() == null ? null : split.cutoff().toString(),
                split.globalDates().size(), split.calibrationDates().size(), split.holdoutDates().size(),
                folds.stream().map(fold -> {
                    JointFoldSelection joint = productionJointFolds.get(fold.index());
                    RuleParameters selected = analysis.track() == null
                            ? analysis.foldSelectedCandidates().get(fold.index())
                            : joint == null ? null : joint.selectedCandidate();
                    CalibrationSigmaProfile sigma = analysis.track() == null
                            ? analysis.foldSigmaProfiles().get(fold.index())
                            : joint == null ? null : joint.sigmaProfile();
                    return foldDto(fold, selected, attempts, key.horizon(), sigma, joint,
                            analysis.track() != null);
                }).toList(),
                splitStat(calibration,
                        analysis.selectedCandidate() == null ? null
                                : analysis.selectedCandidate().parameterSetId(), key.horizon()),
                splitStat(holdout,
                        analysis.selectedCandidate() == null ? null
                                : analysis.selectedCandidate().parameterSetId(), key.horizon()),
                missingEntry, missingExit, insufficientForward, costOutsideEffectiveRange,
                sensitivityN, firstExecution(primary),
                status, reason, analysis.selectedCandidate() == null ? null
                        : analysis.selectedCandidate().parameterSetId(),
                analysis.candidateCalibration(), promotionEvidence,
                analysis.instrumentKind() == null ? null : analysis.instrumentKind().name(),
                analysis.productionProfile(), analysis.assetClass(), analysis.stockStyle(),
                analysis.bondTerm(), analysis.confidenceDecile(), promotionEvidenceScope,
                parameterSnapshot(analysis.selectedCandidate(), analysis.sigmaProfile()),
                purgeEmbargoEvidence(primary, split, folds));
    }

    private V13RegistryBuild buildV13Evidence(
            Map<V13GroupKey, V13GroupAnalysis> analyses,
            Map<String, List<RuleParameters>> candidateGridsByMarket,
            Map<String, CalibrationSigmaProfile> sigmaProfilesByMarket,
            BacktestDto.UniverseMode universeMode) {
        boolean registryAllowed = universeMode == BacktestDto.UniverseMode.FULL_MARKET;
        // Bounded requests intentionally never allocate a promotion candidate map and never
        // call TradingRadarV13PromotionRegistry.build(). Joint-fold diagnostics remain visible.
        Map<TradingRadarV13PromotionRegistry.ProductionKey,
                TradingRadarV13PromotionRegistry.CandidatePromotion> candidates = registryAllowed
                ? new LinkedHashMap<>() : null;
        Map<TradingRadarV13PromotionRegistry.ProductionKey,
                Map<Integer, JointFoldSelection>> jointFoldSelections = new LinkedHashMap<>();
        List<String> jointFailures = new ArrayList<>();
        Set<String> markets = analyses.keySet().stream().map(V13GroupKey::market)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (String market : markets) {
            for (TradingRadarV13PromotionRegistry.Track track
                    : TradingRadarV13PromotionRegistry.Track.values()) {
                List<Integer> required = track.requiredHorizons();
                // Asset/style/term/confidence are report stratifications, not
                // production dimensions.  Recombine every diagnostic group
                // sharing the exact market × instrument × productionProfile ×
                // track key before selection/promotion; otherwise a sparse
                // profile/decile could silently shrink the promotion sample.
                Map<String, List<V13GroupAnalysis>> byIdentity = analyses.values().stream()
                        .filter(group -> market.equals(group.key().market())
                                && track == group.track()
                                && group.instrumentKind() != null
                                && group.productionProfile() != null)
                        .collect(java.util.stream.Collectors.groupingBy(
                                group -> productionIdentity(group.instrumentKind(), group.productionProfile()),
                                LinkedHashMap::new, java.util.stream.Collectors.toList()));
                for (List<V13GroupAnalysis> identityGroups : byIdentity.values()) {
                    // Pool every diagnostic stratum first, then recalibrate on
                    // the combined exact-key sample.  Stratum-local selectors
                    // are report diagnostics only and must never veto or shrink
                    // the production-key calibration sample.
                    Map<Integer, V13GroupAnalysis> byHorizon = new LinkedHashMap<>();
                    for (int horizon : track.requiredHorizons()) {
                        List<V13GroupAnalysis> sameHorizon = identityGroups.stream()
                                .filter(group -> group.key().horizon() == horizon).toList();
                        if (sameHorizon.isEmpty()) continue;
                        V13GroupAnalysis seed = sameHorizon.get(0);
                        List<V13Attempt> combinedAttempts = sameHorizon.stream()
                                .flatMap(group -> group.attempts().stream()).toList();
                        V13GroupKey combinedKey = new V13GroupKey(
                                seed.key().market(), horizon, seed.instrumentKind(), seed.productionProfile(),
                                seed.assetClass(), seed.stockStyle(), seed.bondTerm(), "ALL");
                        byHorizon.put(horizon, analyzeV13Group(combinedKey, combinedAttempts,
                                seed.split(), seed.folds(),
                                candidateGridsByMarket.getOrDefault(market, List.of()),
                                sigmaProfilesByMarket.getOrDefault(market,
                                        CalibrationSigmaProfile.unavailable("market_profile_missing"))));
                    }
                    List<V13GroupAnalysis> groups = required.stream()
                            .map(byHorizon::get).toList();
                    if (groups.stream().anyMatch(Objects::isNull)) continue;
                    RadarBacktestExecution.InstrumentKind kind = groups.get(0).instrumentKind();
                    String profile = groups.get(0).productionProfile();
                    TradingRadarV13PromotionRegistry.ProductionKey key =
                            new TradingRadarV13PromotionRegistry.ProductionKey(
                                    market, kind, profile, track);
                    Map<Integer, JointFoldSelection> jointFolds = buildJointFoldSelections(
                            groups, required);
                    jointFoldSelections.put(key, jointFolds);
                    jointFolds.forEach((foldIndex, selection) -> {
                        if (selection == null || !selection.available()) {
                            String reason = selection == null || selection.reason().isBlank()
                                    ? "JOINT_FOLD_UNAVAILABLE" : selection.reason();
                            jointFailures.add(market + "/" + kind + "/" + profile + "/"
                                    + track + "/fold=" + foldIndex + ":" + reason);
                        }
                    });
                    if (!registryAllowed) {
                        continue;
                    }
                    /*
                     * Production selection is track-scoped, not horizon-scoped.  The
                     * diagnostic analyses above still select a parameter independently so
                     * each report row can explain its local calibration, but promotion must
                     * choose one immutable candidate after pooling both required horizons.
                     * Requiring the two independently selected ids to happen to match is not
                     * equivalent: it can select a candidate that wins one horizon while losing
                     * the joint tie-break, or reject a candidate that is best on the pooled
                     * evidence.  Missing calibration intersection in either required horizon
                     * keeps the production key V12.
                     */
                    List<RuleParameters> productionGrid = candidateGridsByMarket
                            .getOrDefault(market, List.of());
                    RuleParameters jointSelected = selectJointV13Candidate(
                            groups, required, productionGrid);
                    if (jointSelected == null) continue;
                    RuleParameters parameters = jointSelected;
                    Map<Integer, TradingRadarV13PromotionRegistry.HorizonEvidence> evidence = new LinkedHashMap<>();
                    for (V13GroupAnalysis group : groups) {
                        List<V13Attempt> primary = group.attempts().stream()
                                .filter(attempt -> attempt.execution().primary().isPresent()).toList();
                        List<V13Attempt> holdout = group.split().sufficientForCutoff()
                                ? primary.stream().filter(attempt -> group.split().isHoldout(
                                        attempt.execution().signalDate())).toList() : List.of();
                        V13CandidateStats hs = candidateStats(holdout, parameters, group.key().horizon());
                        TradingRadarV13PromotionRegistry.HoldoutMetrics hm = new TradingRadarV13PromotionRegistry.HoldoutMetrics(
                                hs.intersectionN(), hs.intersectionCodes(), hs.pairedMedianDelta(),
                                pooledDelta(hs), downsideImprovement(hs), firstRoundTripCost(primary));
                        List<TradingRadarV13PromotionRegistry.FoldMetrics> fm = group.folds().stream()
                                .map(fold -> {
                                    JointFoldSelection joint = jointFolds.get(fold.index());
                                    return toFoldMetrics(fold, primary,
                                            joint == null ? null : joint.selectedCandidate(),
                                            group.key().horizon(),
                                            joint == null ? null : joint.sigmaProfile());
                                }).toList();
                        evidence.put(group.key().horizon(),
                                new TradingRadarV13PromotionRegistry.HorizonEvidence(
                                        group.key().horizon(), hm, fm));
                    }
                    candidates.put(key, new TradingRadarV13PromotionRegistry.CandidatePromotion(parameters, evidence));
                }
            }
        }
        return new V13RegistryBuild(
                registryAllowed ? TradingRadarV13PromotionRegistry.build(candidates) : null,
                Map.copyOf(jointFoldSelections), List.copyOf(jointFailures));
    }

    /**
     * Select one candidate for an entire production track by pooling its required horizons.
     *
     * <p>The selector deliberately uses only calibration rows.  Candidate downside is pooled
     * over the same candidate/baseline intersection, pooled mean delta is row-weighted over that
     * intersection, and paired median delta first joins all horizon deltas by code before taking
     * the per-code mean/median.  Thus a code with both required horizons contributes one paired
     * value instead of silently letting its number of rows dominate the tie-break.</p>
     */
    private RuleParameters selectJointV13Candidate(
            List<V13GroupAnalysis> groups,
            List<Integer> requiredHorizons,
            List<RuleParameters> candidateGrid) {
        if (groups == null || requiredHorizons == null || requiredHorizons.isEmpty()
                || candidateGrid == null || candidateGrid.isEmpty()
                || groups.size() != requiredHorizons.size()) return null;

        List<TradingRadarCalibrationSelector.CalibrationScore> scores = new ArrayList<>();
        for (RuleParameters candidate : candidateGrid) {
            if (candidate == null || !RuleParameters.V13_VERSION.equals(candidate.ruleVersion())) continue;
            List<V13CandidateStats> horizonStats = new ArrayList<>();
            boolean complete = true;
            for (int i = 0; i < requiredHorizons.size(); i++) {
                V13GroupAnalysis group = groups.get(i);
                if (group == null || group.split() == null || !group.split().sufficientForCutoff()) {
                    complete = false;
                    break;
                }
                List<V13Attempt> primary = group.attempts().stream()
                        .filter(attempt -> attempt.execution().primary().isPresent()).toList();
                LocalDate calibrationBoundary = firstDate(group.split().holdoutDates());
                List<V13Attempt> calibration = primary.stream()
                        .filter(attempt -> group.split().isCalibration(attempt.execution().signalDate()))
                        .filter(attempt -> eligibleBeforeBoundary(attempt, calibrationBoundary))
                        .toList();
                V13CandidateStats stats = jointCandidateStats(
                        calibration, candidate, requiredHorizons.get(i));
                // A joint selector may not borrow the other horizon when one required
                // horizon has no same-sample calibration intersection.
                if (stats.intersectionN() <= 0) {
                    complete = false;
                    break;
                }
                horizonStats.add(stats);
            }
            if (!complete || horizonStats.size() != requiredHorizons.size()) continue;
            TradingRadarCalibrationSelector.CalibrationScore score = jointCalibrationScore(
                    candidate, horizonStats);
            if (score != null) scores.add(score);
        }
        return TradingRadarCalibrationSelector.select(scores, RuleParameters.v12Default()).orElse(null);
    }

    /**
     * Fold-local counterpart of {@link #selectJointV13Candidate}: one immutable joint calendar,
     * one sigma profile/grid and one candidate per exact production key/fold.  Per-horizon
     * diagnostic sigma profiles are deliberately not accepted as input.
     */
    private Map<Integer, JointFoldSelection> buildJointFoldSelections(
            List<V13GroupAnalysis> groups, List<Integer> requiredHorizons) {
        if (groups == null || groups.isEmpty() || requiredHorizons == null
                || groups.size() != requiredHorizons.size()) return Map.of();
        Map<Integer, V13GroupAnalysis> byHorizon = groups.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(group -> group.key().horizon(), group -> group,
                        (left, right) -> left, LinkedHashMap::new));
        if (requiredHorizons.stream().anyMatch(horizon -> !byHorizon.containsKey(horizon))) {
            return Map.of();
        }
        List<Integer> foldIndexes = groups.stream()
                .flatMap(group -> group.folds().stream())
                .map(RadarWalkForwardPlan.Fold::index).distinct().sorted().toList();
        List<String> exactKeyCodes = groups.stream()
                .flatMap(group -> group.attempts().stream())
                .map(V13Attempt::code).filter(Objects::nonNull).distinct().sorted().toList();
        String market = groups.get(0).key().market();
        Map<Integer, JointFoldSelection> selected = new LinkedHashMap<>();
        for (Integer foldIndex : foldIndexes) {
            Map<Integer, RadarWalkForwardPlan.Fold> foldsByHorizon = new LinkedHashMap<>();
            for (Integer horizon : requiredHorizons) {
                RadarWalkForwardPlan.Fold horizonFold = byHorizon.get(horizon).folds().stream()
                        .filter(fold -> fold.index() == foldIndex).findFirst().orElse(null);
                if (horizonFold != null) foldsByHorizon.put(horizon, horizonFold);
            }
            RadarWalkForwardPlan.JointTrackFoldResolution resolution =
                    RadarWalkForwardPlan.jointTrackFold(requiredHorizons, foldsByHorizon);
            if (!resolution.available()) {
                selected.put(foldIndex, new JointFoldSelection(
                        resolution, CalibrationSigmaProfile.unavailable(resolution.reason()),
                        null, "UNAVAILABLE", resolution.reason()));
                continue;
            }
            RadarWalkForwardPlan.JointTrackFold jointFold = resolution.fold();
            Set<LocalDate> jointDateSet = Set.copyOf(jointFold.jointTrainDates());
            Map<String, Map<Integer, Set<LocalDate>>> primaryDatesByCodeAndHorizon =
                    new LinkedHashMap<>();
            for (Integer horizon : jointFold.requiredHorizons()) {
                for (V13Attempt attempt : byHorizon.get(horizon).attempts()) {
                    if (attempt == null || attempt.code() == null
                            || attempt.execution().primary().isEmpty()
                            || !jointDateSet.contains(attempt.execution().signalDate())) continue;
                    primaryDatesByCodeAndHorizon
                            .computeIfAbsent(attempt.code(), ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(horizon, ignored -> new LinkedHashSet<>())
                            .add(attempt.execution().signalDate());
                }
            }
            Map<String, Set<LocalDate>> jointEligibleDatesByCode = new LinkedHashMap<>();
            for (String code : exactKeyCodes) {
                Map<Integer, Set<LocalDate>> byRequiredHorizon =
                        primaryDatesByCodeAndHorizon.getOrDefault(code, Map.of());
                Set<LocalDate> intersection = null;
                for (Integer horizon : jointFold.requiredHorizons()) {
                    Set<LocalDate> dates = new LinkedHashSet<>(
                            byRequiredHorizon.getOrDefault(horizon, Set.of()));
                    if (intersection == null) intersection = dates;
                    else intersection.retainAll(dates);
                }
                jointEligibleDatesByCode.put(code,
                        intersection == null ? Set.of() : Set.copyOf(intersection));
            }
            CalibrationSigmaProfile sigma = calibrateSigmaProfileForFold(
                    market, exactKeyCodes, jointEligibleDatesByCode, jointFold.jointTrainDates());
            if (!sigma.available() || sigma.asOfTo() == null
                    || sigma.asOfTo().isAfter(jointFold.jointTrainTo())) {
                selected.put(foldIndex, new JointFoldSelection(
                        resolution, sigma, null, "UNAVAILABLE",
                        "JOINT_FOLD_SIGMA_PROFILE_UNAVAILABLE"));
                continue;
            }
            List<RuleParameters> foldGrid = v13CandidateGrid(sigma).stream()
                    .filter(candidate -> RuleParameters.V13_VERSION.equals(candidate.ruleVersion()))
                    .toList();
            List<TradingRadarCalibrationSelector.CalibrationScore> scores = new ArrayList<>();
            Set<LocalDate> jointTrainDates = jointDateSet;
            for (RuleParameters candidate : foldGrid) {
                List<V13CandidateStats> horizonStats = new ArrayList<>();
                boolean complete = true;
                for (Integer horizon : jointFold.requiredHorizons()) {
                    V13GroupAnalysis group = byHorizon.get(horizon);
                    RadarWalkForwardPlan.Fold horizonFold = jointFold.horizonFolds().get(horizon);
                    List<V13Attempt> train = group.attempts().stream()
                            .filter(attempt -> jointTrainDates.contains(
                                    attempt.execution().signalDate()))
                            .filter(attempt -> attempt.execution().primary().isPresent())
                            .filter(attempt -> eligibleBeforeBoundary(
                                    attempt, horizonFold.evaluationFrom())).toList();
                    V13CandidateStats stats = jointCandidateStats(train, candidate,
                            horizon, sigma);
                    if (stats.intersectionN() <= 0) {
                        complete = false;
                        break;
                    }
                    horizonStats.add(stats);
                }
                if (!complete) continue;
                TradingRadarCalibrationSelector.CalibrationScore score = jointCalibrationScore(
                        candidate, horizonStats);
                if (score != null) scores.add(score);
            }
            RuleParameters candidate = TradingRadarCalibrationSelector
                    .select(scores, RuleParameters.v12Default()).orElse(null);
            selected.put(foldIndex, candidate == null
                    ? new JointFoldSelection(resolution, sigma, null, "UNAVAILABLE",
                            "JOINT_FOLD_INTERSECTION_UNAVAILABLE")
                    : new JointFoldSelection(resolution, sigma, candidate, "AVAILABLE", ""));
        }
        return Map.copyOf(selected);
    }

    /** Build the fixed selector tuple from all required horizon statistics. */
    private TradingRadarCalibrationSelector.CalibrationScore jointCalibrationScore(
            RuleParameters candidate, List<V13CandidateStats> horizonStats) {
        if (candidate == null || horizonStats == null || horizonStats.isEmpty()) return null;
        List<BigDecimal> candidateReturns = horizonStats.stream()
                .flatMap(stats -> stats.intersectionCandidateReturns().stream()).toList();
        List<BigDecimal> baselineReturns = horizonStats.stream()
                .flatMap(stats -> stats.intersectionBaselineReturns().stream()).toList();
        Map<String, List<BigDecimal>> deltasByCode = new LinkedHashMap<>();
        for (V13CandidateStats stats : horizonStats) {
            stats.deltasByCode().forEach((code, deltas) ->
                    deltasByCode.computeIfAbsent(code == null ? "" : code,
                            ignored -> new ArrayList<>()).addAll(deltas));
        }
        BigDecimal downside = ratioAtOrBelow(candidateReturns, DOWNSIDE);
        BigDecimal paired = pairedMedianDelta(deltasByCode);
        BigDecimal pooled = diff(mean(candidateReturns), mean(baselineReturns));
        if (downside == null || paired == null || pooled == null) return null;
        return new TradingRadarCalibrationSelector.CalibrationScore(candidate, downside, paired, pooled);
    }

    private V13CandidateStats candidateStats(
            List<V13Attempt> attempts, String candidateId, int horizon) {
        return candidateStats(attempts, horizon,
                attempt -> attempt == null || candidateId == null
                        ? null : replayCandidate(attempt, candidateId));
    }

    /**
     * Fold-local candidate statistics.  A fold may have a different calibrated sigma profile
     * (and therefore different normalized thresholds) than the request-level profile.  Replaying
     * the candidate against the exact signal-date input/context prevents the selector from merely
     * relabelling a globally evaluated outcome under a fold-local parameter id.
     */
    private V13CandidateStats candidateStats(
            List<V13Attempt> attempts, RuleParameters candidate, int horizon) {
        return candidateStats(attempts, horizon,
                attempt -> replayCandidate(attempt, candidate));
    }

    /** Joint-track selector variant; retains per-code deltas only for its paired aggregation. */
    private V13CandidateStats jointCandidateStats(
            List<V13Attempt> attempts, RuleParameters candidate, int horizon) {
        return candidateStats(attempts, horizon,
                attempt -> replayCandidate(attempt, candidate), true);
    }

    /** Fold-local replay must carry profile availability, not only the row's raw sigma. */
    private V13CandidateStats candidateStats(
            List<V13Attempt> attempts, RuleParameters candidate, int horizon,
            CalibrationSigmaProfile sigmaProfile) {
        boolean profileAvailable = sigmaProfile != null && sigmaProfile.available();
        return candidateStats(attempts, horizon,
                attempt -> replayCandidate(attempt, candidate, profileAvailable));
    }

    /** Joint fold selector variant with fold-local sigma availability and paired provenance. */
    private V13CandidateStats jointCandidateStats(
            List<V13Attempt> attempts, RuleParameters candidate, int horizon,
            CalibrationSigmaProfile sigmaProfile) {
        boolean profileAvailable = sigmaProfile != null && sigmaProfile.available();
        return candidateStats(attempts, horizon,
                attempt -> replayCandidate(attempt, candidate, profileAvailable), true);
    }

    private V13CandidateStats candidateStats(
            List<V13Attempt> attempts,
            int horizon,
            java.util.function.Function<V13Attempt, V13CandidateOutcome> candidateResolver) {
        return candidateStats(attempts, horizon, candidateResolver, false);
    }

    private V13CandidateStats candidateStats(
            List<V13Attempt> attempts,
            int horizon,
            java.util.function.Function<V13Attempt, V13CandidateOutcome> candidateResolver,
            boolean retainDeltasByCode) {
        List<BigDecimal> candidateReturns = new ArrayList<>();
        List<BigDecimal> baselineReturns = new ArrayList<>();
        List<BigDecimal> intersectionCandidateReturns = new ArrayList<>();
        List<BigDecimal> intersectionBaselineReturns = new ArrayList<>();
        List<BigDecimal> candidateGrossReturns = new ArrayList<>();
        List<BigDecimal> baselineGrossReturns = new ArrayList<>();
        List<BigDecimal> intersectionCandidateGrossReturns = new ArrayList<>();
        List<BigDecimal> intersectionBaselineGrossReturns = new ArrayList<>();
        List<BigDecimal> deltas = new ArrayList<>();
        Set<String> candidateCodes = new LinkedHashSet<>();
        Set<String> baselineCodes = new LinkedHashSet<>();
        Set<String> intersectionCodes = new LinkedHashSet<>();
        Map<String, List<BigDecimal>> deltasByCode = new LinkedHashMap<>();
        V13TrackAccumulator entry = new V13TrackAccumulator();
        V13TrackAccumulator held = new V13TrackAccumulator();
        for (V13Attempt attempt : attempts == null ? List.<V13Attempt>of() : attempts) {
            Optional<RadarBacktestExecution.ExecutionSample> sample = attempt.execution().primary();
            V13CandidateOutcome candidate = candidateResolver == null ? null : candidateResolver.apply(attempt);
            if (sample.isEmpty() || candidate == null || attempt.baseline() == null) continue;
            RadarBacktestExecution.ExecutionSample execution = sample.get();

            boolean candidateEntryHit = hasEntryCoverage(candidate, horizon);
            boolean baselineEntryHit = hasEntryCoverage(attempt.baseline(), horizon);
            BigDecimal candidateEntryReturn = entryReturn(candidate, execution, horizon);
            BigDecimal baselineEntryReturn = entryReturn(attempt.baseline(), execution, horizon);
            entry.add(attempt.code(), candidateEntryHit, candidateEntryReturn,
                    baselineEntryHit, baselineEntryReturn);

            boolean candidateHeldHit = hasHeldCoverage(candidate, horizon);
            boolean baselineHeldHit = hasHeldCoverage(attempt.baseline(), horizon);
            BigDecimal candidateHeldReturn = heldReturn(candidate, execution, horizon);
            BigDecimal baselineHeldReturn = heldReturn(attempt.baseline(), execution, horizon);
            held.add(attempt.code(), candidateHeldHit, candidateHeldReturn,
                    baselineHeldHit, baselineHeldReturn);

            // One key must contribute at most once to the selector/promotion
            // metrics.  Prefer the entry path whenever either policy emitted a
            // free BUY/ADD/TRIAL action; only when neither entered do we compare
            // the held path.  This keeps free and held returns from being added
            // together while still exposing a one-sided signal against zero.
            boolean useEntry = candidateEntryHit || baselineEntryHit;
            boolean candidateHit = useEntry ? candidateEntryHit : candidateHeldHit;
            boolean baselineHit = useEntry ? baselineEntryHit : baselineHeldHit;
            if (!candidateHit && !baselineHit) continue;
            BigDecimal candidateReturn = useEntry ? candidateEntryReturn : candidateHeldReturn;
            BigDecimal baselineReturn = useEntry ? baselineEntryReturn : baselineHeldReturn;
            BigDecimal candidateGrossReturn = candidateHit
                    ? (useEntry ? execution.grossReturnPct()
                    : heldGrossReturn(candidate, execution, horizon)) : zeroReturn();
            BigDecimal baselineGrossReturn = baselineHit
                    ? (useEntry ? execution.grossReturnPct()
                    : heldGrossReturn(attempt.baseline(), execution, horizon)) : zeroReturn();
            if (candidateHit) {
                candidateReturns.add(candidateReturn);
                candidateCodes.add(attempt.code());
                candidateGrossReturns.add(candidateGrossReturn);
            }
            if (baselineHit) {
                baselineReturns.add(baselineReturn);
                baselineCodes.add(attempt.code());
                baselineGrossReturns.add(baselineGrossReturn);
            }
            intersectionCandidateReturns.add(candidateReturn);
            intersectionBaselineReturns.add(baselineReturn);
            intersectionCandidateGrossReturns.add(candidateGrossReturn);
            intersectionBaselineGrossReturns.add(baselineGrossReturn);
            intersectionCodes.add(attempt.code());
            BigDecimal delta = candidateReturn.subtract(baselineReturn).setScale(4, RoundingMode.HALF_UP);
            deltas.add(delta);
            deltasByCode.computeIfAbsent(attempt.code() == null ? "" : attempt.code(),
                    ignored -> new ArrayList<>()).add(delta);
        }
        return new V13CandidateStats(candidateReturns.size(), candidateCodes.size(), baselineReturns.size(),
                baselineCodes.size(), intersectionCandidateReturns.size(), intersectionCodes.size(),
                List.copyOf(candidateReturns), List.copyOf(baselineReturns),
                List.copyOf(intersectionCandidateReturns), List.copyOf(intersectionBaselineReturns),
                List.copyOf(candidateGrossReturns), List.copyOf(baselineGrossReturns),
                List.copyOf(intersectionCandidateGrossReturns), List.copyOf(intersectionBaselineGrossReturns),
                List.copyOf(deltas), diff(mean(intersectionCandidateReturns), mean(intersectionBaselineReturns)),
                pairedMedianDelta(deltasByCode),
                ratioAtOrBelow(candidateReturns, DOWNSIDE), ratioAtOrBelow(baselineReturns, DOWNSIDE),
                ratioAtOrBelow(intersectionCandidateReturns, DOWNSIDE),
                ratioAtOrBelow(intersectionBaselineReturns, DOWNSIDE), entry.finish(), held.finish(),
                retainDeltasByCode ? deltasByCode : Map.of());
    }

    private V13CandidateOutcome replayCandidate(
            V13Attempt attempt, RuleParameters candidate) {
        return replayCandidate(attempt, candidate, null);
    }

    private V13CandidateOutcome replayCandidate(
            V13Attempt attempt, RuleParameters candidate, Boolean sigmaProfileAvailable) {
        if (attempt == null || candidate == null
                || !RuleParameters.V13_VERSION.equals(candidate.ruleVersion())) return null;
        TradingRadarRuleEngine.CandidateContext context = attempt.candidateContexts().get(
                candidate.parameterSetId());
        if (context == null) context = attempt.baselineContext();
        if (context != null && sigmaProfileAvailable != null) {
            context = context.withSigmaProfileAvailable(sigmaProfileAvailable);
        }
        if (context == null || attempt.freeInput() == null || attempt.heldInput() == null) {
            return null;
        }
        return new V13CandidateOutcome(
                ruleEngine.evaluateCandidate(attempt.freeInput(), candidate, context),
                ruleEngine.evaluateCandidate(attempt.heldInput(), candidate, context));
    }

    private V13CandidateOutcome replayCandidate(V13Attempt attempt, String parameterSetId) {
        if (attempt == null || parameterSetId == null || attempt.candidateGrid() == null) return null;
        RuleParameters candidate = attempt.candidateGrid().stream()
                .filter(Objects::nonNull)
                .filter(value -> parameterSetId.equals(value.parameterSetId()))
                .findFirst().orElse(null);
        return replayCandidate(attempt, candidate);
    }

    /**
     * Entry 軌只計算 free BUY/ADD/TRIAL_BUY；free no-position 一律為零，不能
     * 因為 held 狀態另有 HOLD 而把同一筆 next-open 報酬重複算入。
     */
    private BigDecimal entryReturn(
            V13CandidateOutcome outcome,
            RadarBacktestExecution.ExecutionSample sample,
            int horizon) {
        return outcome != null && sample != null
                && isBuyAction(actionOf(outcome.free(), horizon))
                ? sample.netReturnPct() : zeroReturn();
    }

    /**
     * Held 軌以同一個 t+1 next-open 的既有部位市值為起點，不虛構一筆買進成本：
     * HOLD/HOLD_CAUTION 在 horizon 結束時支付一次賣出成本；REDUCE/EXIT 則視為
     * t+1 以相同市值全數清算（partial REDUCE 無持倉比例時採保守的 full-liquidation
     * 代理）。如此 flat price 下兩條路徑成本相同，不會因 candidate 被標成 REDUCE
     * 而免費得到一筆「零報酬」相對於 baseline 的買進成本優勢。
     */
    private BigDecimal heldReturn(
            V13CandidateOutcome outcome,
            RadarBacktestExecution.ExecutionSample sample,
            int horizon) {
        TradingRadarRuleEngine.Action held = outcome == null ? null
                : actionOf(outcome.held(), horizon);
        if (sample == null) return zeroReturn();
        if (held == TradingRadarRuleEngine.Action.HOLD
                || held == TradingRadarRuleEngine.Action.HOLD_CAUTION) {
            return heldLiquidationReturn(sample, sample.exitPrice());
        }
        if (held == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || held == TradingRadarRuleEngine.Action.EXIT_CANDIDATE) {
            return heldLiquidationReturn(sample, sample.entryPrice());
        }
        return zeroReturn();
    }

    /**
     * Held gross uses the same t+1 mark-to-market basis as held net.  Selling at t+1 has no
     * gross price return; only the typed net path exposes the sell fee/tax/slippage impact.
     */
    private BigDecimal heldGrossReturn(
            V13CandidateOutcome outcome,
            RadarBacktestExecution.ExecutionSample sample,
            int horizon) {
        TradingRadarRuleEngine.Action held = outcome == null ? null
                : actionOf(outcome.held(), horizon);
        if (sample != null && (held == TradingRadarRuleEngine.Action.HOLD
                || held == TradingRadarRuleEngine.Action.HOLD_CAUTION)) {
            return sample.grossReturnPct();
        }
        return zeroReturn();
    }

    /** Existing holding value at t+1, liquidated at the supplied open; no fictional buy fee. */
    private BigDecimal heldLiquidationReturn(
            RadarBacktestExecution.ExecutionSample sample, BigDecimal liquidationPrice) {
        if (sample == null || sample.entryPrice() == null || liquidationPrice == null
                || sample.costAssumption() == null) return zeroReturn();
        BigDecimal proceeds = sample.costAssumption().exitCashReceived(liquidationPrice);
        return proceeds.divide(sample.entryPrice(), 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private boolean hasEntryCoverage(V13CandidateOutcome outcome, int horizon) {
        return outcome != null && isBuyAction(actionOf(outcome.free(), horizon));
    }

    private boolean hasHeldCoverage(V13CandidateOutcome outcome, int horizon) {
        TradingRadarRuleEngine.Action held = outcome == null ? null
                : actionOf(outcome.held(), horizon);
        return held == TradingRadarRuleEngine.Action.HOLD
                || held == TradingRadarRuleEngine.Action.HOLD_CAUTION
                || held == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || held == TradingRadarRuleEngine.Action.EXIT_CANDIDATE;
    }

    private TradingRadarRuleEngine.Action actionOf(
            TradingRadarRuleEngine.StockResult result, int horizon) {
        if (result == null) return null;
        return horizon <= 20 ? result.shortAction() : result.action();
    }

    private BigDecimal zeroReturn() {
        return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isBuyAction(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY;
    }

    private BigDecimal downsideImprovement(V13CandidateStats stats) {
        return diff(stats.intersectionBaselineDownsideRatePct(),
                stats.intersectionCandidateDownsideRatePct());
    }

    private BigDecimal pooledDelta(V13CandidateStats stats) {
        return stats == null ? null : stats.pooledMeanDelta();
    }

    private BacktestDto.ActionTrackCoverage trackCoverage(V13TrackStats stats) {
        if (stats == null) return new BacktestDto.ActionTrackCoverage(0, 0, 0, 0, 0, 0);
        return new BacktestDto.ActionTrackCoverage(
                stats.candidateN(), stats.candidateCodes(), stats.baselineN(), stats.baselineCodes(),
                stats.intersectionN(), stats.intersectionCodes());
    }

    private boolean isOpportunity(TradingRadarRuleEngine.StockResult result, int horizon) {
        if (result == null) return false;
        TradingRadarRuleEngine.Action action = horizon <= 20
                ? result.shortAction() : result.action();
        return action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY;
    }

    private TradingRadarV13PromotionRegistry.FoldMetrics toFoldMetrics(
            RadarWalkForwardPlan.Fold fold, List<V13Attempt> attempts, RuleParameters selected,
            int horizon, CalibrationSigmaProfile sigmaProfile) {
        Set<LocalDate> eval = Set.copyOf(fold.evaluationDates());
        List<V13Attempt> block = attempts.stream()
                .filter(attempt -> eval.contains(attempt.execution().signalDate())).toList();
        V13CandidateStats stats = selected == null
                ? candidateStats(List.of(), "__NO_FOLD_SELECTION__", horizon)
                : candidateStats(block, selected, horizon, sigmaProfile);
        return new TradingRadarV13PromotionRegistry.FoldMetrics(
                fold.index(), stats.intersectionN(), stats.intersectionCodes(),
                stats.pairedMedianDelta(), stats.pooledMeanDelta(), downsideImprovement(stats));
    }

    /** 每個 expanding fold 僅以該 fold train dates 重新校準 candidate；不得沿用全域選值。 */
    private RuleParameters selectV13Candidate(
            List<V13Attempt> train, int horizon, List<RuleParameters> candidateGrid) {
        return selectV13Candidate(train, horizon, candidateGrid, false);
    }

    private RuleParameters selectV13Candidate(
            List<V13Attempt> train, int horizon, List<RuleParameters> candidateGrid,
            boolean replayFoldLocal) {
        return selectV13Candidate(train, horizon, candidateGrid, replayFoldLocal, null);
    }

    private RuleParameters selectV13Candidate(
            List<V13Attempt> train, int horizon, List<RuleParameters> candidateGrid,
            boolean replayFoldLocal, CalibrationSigmaProfile sigmaProfile) {
        List<TradingRadarCalibrationSelector.CalibrationScore> scores = new ArrayList<>();
        for (RuleParameters candidate : candidateGrid) {
            if (!RuleParameters.V13_VERSION.equals(candidate.ruleVersion())) continue;
            V13CandidateStats stats = replayFoldLocal
                    ? candidateStats(train, candidate, horizon, sigmaProfile)
                    : candidateStats(train, candidate.parameterSetId(), horizon);
            if (stats.intersectionN() <= 0) continue;
            scores.add(new TradingRadarCalibrationSelector.CalibrationScore(
                    candidate,
                    stats.intersectionCandidateDownsideRatePct(),
                    stats.pairedMedianDelta(),
                    stats.pooledMeanDelta()));
        }
        return TradingRadarCalibrationSelector.select(scores, RuleParameters.v12Default()).orElse(null);
    }

    private boolean validFoldSample(TradingRadarV13PromotionRegistry.FoldMetrics fold) {
        return fold != null && fold.n() >= 50 && fold.codes() >= 3;
    }

    private BigDecimal firstRoundTripCost(List<V13Attempt> attempts) {
        return attempts.stream().map(attempt -> attempt.execution().primary())
                .flatMap(Optional::stream)
                .map(sample -> sample.costAssumption().roundTripCostPct())
                .findFirst().orElse(null);
    }

    private boolean finite(BigDecimal value) {
        if (value == null) return false;
        double d = value.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    private TradingRadarV13PromotionRegistry.Track trackForHorizon(int horizon) {
        if (TradingRadarV13PromotionRegistry.Track.SHORT.requiredHorizons().contains(horizon)) {
            return TradingRadarV13PromotionRegistry.Track.SHORT;
        }
        if (TradingRadarV13PromotionRegistry.Track.MEDIUM.requiredHorizons().contains(horizon)) {
            return TradingRadarV13PromotionRegistry.Track.MEDIUM;
        }
        return null;
    }

    private List<RuleParameters> v13CandidateGrid(CalibrationSigmaProfile sigmaProfile) {
        RuleParameters.ActionThresholds baseline = new RuleParameters.ActionThresholds(75, 55, 40, 25);
        RuleParameters.ActionThresholds selectiveShort = new RuleParameters.ActionThresholds(78, 58, 42, 25);
        RuleParameters.ActionThresholds selectiveMedium = new RuleParameters.ActionThresholds(80, 60, 42, 25);
        RuleParameters.ActionThresholds normalizedShort = new RuleParameters.ActionThresholds(82, 62, 43, 25);
        RuleParameters.ActionThresholds normalizedMedium = new RuleParameters.ActionThresholds(82, 62, 43, 25);
        RuleParameters.ActionThresholds evidence = new RuleParameters.ActionThresholds(80, 60, 42, 25);
        RuleParameters.ActionThresholds treasury = new RuleParameters.ActionThresholds(80, 60, 42, 25);
        CalibrationSigmaProfile profile = sigmaProfile == null
                ? CalibrationSigmaProfile.unavailable("null_profile") : sigmaProfile;
        List<RuleParameters> grid = new ArrayList<>();
        grid.add(RuleParameters.v12Default());
        // Non-normalized candidates explicitly disable the normalized path; they do not carry
        // guessed sigma floor/multiple constants merely to satisfy a constructor.
        grid.add(RuleParameters.v13DisabledCandidate("V13_BASELINE", baseline, baseline,
                new BigDecimal("0.70"), new BigDecimal("100"), Map.of(),
                RuleParameters.BondRateCandidate.v12Fallback()));
        grid.add(RuleParameters.v13DisabledCandidate("V13_SELECTIVE", selectiveShort,
                        selectiveMedium, new BigDecimal("0.70"), new BigDecimal("100"), Map.of(
                                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.02"),
                                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.02"),
                                RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.02"),
                                RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.02")),
                        RuleParameters.BondRateCandidate.v12Fallback()));
        // Bounded factorial threshold pairs: short and medium tracks are independently
        // calibrated, while the candidate count remains deterministic and finite.
        grid.add(RuleParameters.v13DisabledCandidate("V13_SHORT_BASELINE_MEDIUM_SELECTIVE",
                baseline, selectiveMedium, new BigDecimal("0.70"), new BigDecimal("100"), Map.of(),
                RuleParameters.BondRateCandidate.v12Fallback()));
        grid.add(RuleParameters.v13DisabledCandidate("V13_SHORT_SELECTIVE_MEDIUM_BASELINE",
                selectiveShort, baseline, new BigDecimal("0.70"), new BigDecimal("100"), Map.of(),
                RuleParameters.BondRateCandidate.v12Fallback()));
        if (profile.available()) {
            // Each volatility/timing/weakening dimension is independently bounded.  The base
            // candidate keeps the calibrated profile values; LOW/HIGH variants let the selector
            // choose one dimension without silently coupling upper/lower/saturation to it.
            addNormalizedBiasCandidates(grid, "P05", normalizedShort, normalizedMedium,
                    profile.floorFor(5), profile);
            addNormalizedBiasCandidates(grid, "P10", normalizedShort, normalizedMedium,
                    profile.floorFor(10), profile);
            addNormalizedBiasCandidates(grid, "P25", normalizedShort, normalizedMedium,
                    profile.floorFor(25), profile);
        }
        grid.add(RuleParameters.v13DisabledCandidate("V13_EVIDENCE_DOWNSIDE", evidence, evidence,
                        new BigDecimal("0.70"), new BigDecimal("25"), Map.of(
                                RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.01"),
                                RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.01"),
                                RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.01"),
                                RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.01")),
                        RuleParameters.BondRateCandidate.v12Fallback()));
        grid.add(RuleParameters.v13DisabledCandidate("V13_TREASURY_BOND_M3_Y10_Y30_LEVEL", treasury, treasury,
                        new BigDecimal("0.70"), new BigDecimal("25"), Map.of(
                                RuleParameters.CandidateWeight.SHORT_TREASURY, new BigDecimal("0.05"),
                                RuleParameters.CandidateWeight.MEDIUM_TREASURY, new BigDecimal("0.05"),
                                RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.01"),
                                RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.01")),
                        RuleParameters.BondRateCandidate.v12Fallback()));
        grid.add(RuleParameters.v13DisabledCandidate("V13_TREASURY_BOND_M3_Y5_Y10_SHAPE25", treasury, treasury,
                        new BigDecimal("0.70"), new BigDecimal("25"), Map.of(
                                RuleParameters.CandidateWeight.SHORT_TREASURY, new BigDecimal("0.05"),
                                RuleParameters.CandidateWeight.MEDIUM_TREASURY, new BigDecimal("0.05"),
                                RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.01"),
                        RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.01")),
                        RuleParameters.BondRateCandidate.of(
                                "M3", "Y5", "Y10", new BigDecimal("0.25"), BigDecimal.ONE)));
        return List.copyOf(grid);
    }

    private RuleParameters normalizedBiasCandidate(
            String parameterSetId,
            RuleParameters.ActionThresholds shortThresholds,
            RuleParameters.ActionThresholds mediumThresholds,
            BigDecimal sigmaFloor,
            CalibrationSigmaProfile profile) {
        return normalizedBiasCandidate(parameterSetId, shortThresholds, mediumThresholds,
                sigmaFloor, profile.saturationMultiple(), profile.upperMultiple(),
                profile.lowerMultiple(), RuleParameters.WeakeningCondition.conservativeV13());
    }

    private RuleParameters normalizedBiasCandidate(
            String parameterSetId,
            RuleParameters.ActionThresholds shortThresholds,
            RuleParameters.ActionThresholds mediumThresholds,
            BigDecimal sigmaFloor,
            BigDecimal saturationMultiple,
            BigDecimal upperMultiple,
            BigDecimal lowerMultiple,
            RuleParameters.WeakeningCondition weakeningCondition) {
        return RuleParameters.v13Candidate(parameterSetId, shortThresholds, mediumThresholds,
                new BigDecimal("0.70"), sigmaFloor, saturationMultiple,
                upperMultiple, lowerMultiple, new BigDecimal("100"), Map.of(
                        RuleParameters.CandidateWeight.SHORT_MARKET, new BigDecimal("0.02"),
                        RuleParameters.CandidateWeight.MEDIUM_MARKET, new BigDecimal("0.02"),
                        RuleParameters.CandidateWeight.SHORT_MARKET_FEATURE, new BigDecimal("0.03"),
                        RuleParameters.CandidateWeight.MEDIUM_MARKET_FEATURE, new BigDecimal("0.03")),
                RuleParameters.BondRateCandidate.v12Fallback(), weakeningCondition);
    }

    private void addNormalizedBiasCandidates(
            List<RuleParameters> grid,
            String floorLabel,
            RuleParameters.ActionThresholds shortThresholds,
            RuleParameters.ActionThresholds mediumThresholds,
            BigDecimal sigmaFloor,
            CalibrationSigmaProfile profile) {
        String prefix = "V13_" + floorLabel;
        BigDecimal saturation = profile.saturationMultiple();
        BigDecimal upper = profile.upperMultiple();
        BigDecimal lower = profile.lowerMultiple();
        RuleParameters.WeakeningCondition conservative = RuleParameters.WeakeningCondition.conservativeV13();
        grid.add(normalizedBiasCandidate(prefix + "_BASE", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, upper, lower, conservative));
        grid.add(normalizedBiasCandidate(prefix + "_SAT_LOW", shortThresholds, mediumThresholds,
                sigmaFloor, boundedMultipleVariant(saturation, new BigDecimal("0.75")), upper,
                lower, conservative));
        grid.add(normalizedBiasCandidate(prefix + "_SAT_HIGH", shortThresholds, mediumThresholds,
                sigmaFloor, boundedMultipleVariant(saturation, new BigDecimal("1.25")), upper,
                lower, conservative));
        grid.add(normalizedBiasCandidate(prefix + "_UPPER_LOW", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, boundedMultipleVariant(upper, new BigDecimal("0.75")),
                lower, conservative));
        grid.add(normalizedBiasCandidate(prefix + "_UPPER_HIGH", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, boundedMultipleVariant(upper, new BigDecimal("1.25")),
                lower, conservative));
        grid.add(normalizedBiasCandidate(prefix + "_LOWER_LOW", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, upper, boundedMultipleVariant(lower, new BigDecimal("0.75")),
                conservative));
        grid.add(normalizedBiasCandidate(prefix + "_LOWER_HIGH", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, upper, boundedMultipleVariant(lower, new BigDecimal("1.25")),
                conservative));
        grid.add(normalizedBiasCandidate(prefix + "_WEAKEN_LOW", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, upper, lower,
                RuleParameters.WeakeningCondition.withDownVolumeRatioFloor(new BigDecimal("1.00"))));
        grid.add(normalizedBiasCandidate(prefix + "_WEAKEN_HIGH", shortThresholds, mediumThresholds,
                sigmaFloor, saturation, upper, lower,
                RuleParameters.WeakeningCondition.withDownVolumeRatioFloor(new BigDecimal("2.00"))));
    }

    private BigDecimal boundedMultipleVariant(BigDecimal base, BigDecimal multiplier) {
        if (base == null || base.signum() <= 0 || multiplier == null || multiplier.signum() <= 0) {
            return new BigDecimal("0.10");
        }
        return boundedMultiple(base.multiply(multiplier).setScale(8, RoundingMode.HALF_UP));
    }

    private boolean sameSigmaProfile(CalibrationSigmaProfile left, CalibrationSigmaProfile right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return Objects.equals(left.p05(), right.p05())
                && Objects.equals(left.p10(), right.p10())
                && Objects.equals(left.p25(), right.p25())
                && left.sampleN() == right.sampleN()
                && Objects.equals(left.asOfFrom(), right.asOfFrom())
                && Objects.equals(left.asOfTo(), right.asOfTo())
                && Objects.equals(left.calibrationCutoff(), right.calibrationCutoff())
                && Objects.equals(left.source(), right.source())
                && Objects.equals(left.saturationMultiple(), right.saturationMultiple())
                && Objects.equals(left.upperMultiple(), right.upperMultiple())
                && Objects.equals(left.lowerMultiple(), right.lowerMultiple());
    }

    /** Build a lossless DTO echo; no report consumer should infer values from an id. */
    private BacktestDto.RuleParameterSnapshot parameterSnapshot(
            RuleParameters parameters, CalibrationSigmaProfile sigmaProfile) {
        if (parameters == null) return null;
        CalibrationSigmaProfile profile = sigmaProfile == null
                ? CalibrationSigmaProfile.unavailable("null_profile") : sigmaProfile;
        BacktestDto.SigmaSnapshot sigma = new BacktestDto.SigmaSnapshot(
                profile.available() ? "CALIBRATED_PROFILE" : "DISABLED_NO_CALIBRATION_PROFILE",
                null, null, parameters.sigmaFloorRatio(), profile.p05(), profile.p10(), profile.p25(),
                profile.sampleN(), profile.calibrationCutoff(), profile.asOfFrom(), profile.asOfTo(),
                profile.source());
        return new BacktestDto.RuleParameterSnapshot(
                parameters.parameterSetId(), parameters.ruleVersion(),
                parameters.normalizedBiasEnabled(),
                thresholdSnapshot(parameters.shortThresholds()),
                thresholdSnapshot(parameters.mediumThresholds()), parameters.confidenceThreshold(),
                parameters.normalizedBiasFloor(), parameters.normalizedBiasMultiple(),
                parameters.normalizedBiasUpperMultiple(), parameters.normalizedBiasLowerMultiple(),
                parameters.downsideActionThresholdPct(),
                new BacktestDto.WeakeningSnapshot(
                        parameters.weakeningCondition().requireStructureBelow(),
                        parameters.weakeningCondition().requireKdDeadCross(),
                        parameters.weakeningCondition().downVolumeRatioFloor()),
                parameters.candidateWeightDeltas().entrySet().stream().collect(
                        java.util.stream.Collectors.toUnmodifiableMap(
                                entry -> entry.getKey().name(), Map.Entry::getValue)),
                new BacktestDto.BondRateSnapshot(
                        parameters.bondRateCandidate().tenorByBondTerm(),
                        parameters.bondRateCandidate().curveShapeWeight(),
                        parameters.bondRateCandidate().returnPctAtUnit()),
                sigma);
    }

    private BacktestDto.ThresholdSnapshot thresholdSnapshot(RuleParameters.ActionThresholds thresholds) {
        return new BacktestDto.ThresholdSnapshot(
                thresholds.buy(), thresholds.hold(), thresholds.caution(), thresholds.reduce());
    }

    /**
     * Build the sigma floor strictly from calibration-side signal windows.  The cutoff is the
     * earliest sufficient global market/horizon cutoff, so a single shared candidate floor cannot
     * accidentally consume another group's holdout.  The assembler is deliberately reused so the
     * prepass has the same adjusted-price and 60-return definition as production/backtest input.
     */
    private CalibrationSigmaProfile calibrateSigmaProfile(
            Set<String> markets,
            Map<String, List<String>> codesByMarket,
            LocalDate from,
            LocalDate to,
            Map<RadarWalkForwardPlan.MarketHorizon, List<LocalDate>> calendars,
            BigDecimal calibrationRatio,
            List<RadarWalkForwardPlan.TradableOpportunity> opportunities) {
        if (calendars == null || calendars.isEmpty()) {
            return CalibrationSigmaProfile.unavailable("no_global_calendar");
        }
        List<LocalDate> cutoffs = new ArrayList<>();
        Set<LocalDate> calibrationDates = new LinkedHashSet<>();
        for (List<LocalDate> dates : calendars.values()) {
            RadarWalkForwardPlan.ChronologicalSplit split =
                    RadarWalkForwardPlan.chronologicalSplit(dates, calibrationRatio);
            if (split.sufficientForCutoff() && split.cutoff() != null) {
                cutoffs.add(split.cutoff());
                calibrationDates.addAll(split.calibrationDates());
            }
        }
        if (cutoffs.isEmpty()) {
            return CalibrationSigmaProfile.unavailable("no_sufficient_calibration_cutoff");
        }
        LocalDate cutoff = cutoffs.stream().min(LocalDate::compareTo).orElse(null);
        calibrationDates.removeIf(date -> date == null || date.isAfter(cutoff));
        Map<String, Map<Integer, Set<LocalDate>>> datesByCodeAndHorizon = new LinkedHashMap<>();
        for (RadarWalkForwardPlan.TradableOpportunity opportunity
                : opportunities == null ? List.<RadarWalkForwardPlan.TradableOpportunity>of()
                : opportunities) {
            if (opportunity == null || !markets.contains(opportunity.market())
                    || !opportunity.validEntryOpen() || !opportunity.validExitOpen()
                    || !calibrationDates.contains(opportunity.signalDate())) continue;
            datesByCodeAndHorizon.computeIfAbsent(
                            sigmaEligibilityKey(opportunity.market(), opportunity.code()),
                            ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(opportunity.horizon(), ignored -> new LinkedHashSet<>())
                    .add(opportunity.signalDate());
        }
        Map<String, Set<LocalDate>> eligibleDatesByCode = new LinkedHashMap<>();
        for (String market : markets) {
            List<Integer> requiredHorizons = (opportunities == null
                    ? List.<RadarWalkForwardPlan.TradableOpportunity>of() : opportunities).stream()
                    .filter(Objects::nonNull)
                    .filter(opportunity -> market.equals(opportunity.market()))
                    .map(RadarWalkForwardPlan.TradableOpportunity::horizon)
                    .distinct().sorted().toList();
            if (requiredHorizons.isEmpty()) {
                requiredHorizons = calendars.keySet().stream()
                        .filter(key -> market.equals(key.market()))
                        .map(RadarWalkForwardPlan.MarketHorizon::horizon)
                        .distinct().sorted().toList();
            }
            for (String code : codesByMarket.getOrDefault(market, List.of())) {
                String eligibilityKey = sigmaEligibilityKey(market, code);
                Map<Integer, Set<LocalDate>> byHorizon = datesByCodeAndHorizon
                        .getOrDefault(eligibilityKey, Map.of());
                Set<LocalDate> intersection = null;
                for (Integer horizon : requiredHorizons) {
                    Set<LocalDate> dates = new LinkedHashSet<>(
                            byHorizon.getOrDefault(horizon, Set.of()));
                    if (intersection == null) intersection = dates;
                    else intersection.retainAll(dates);
                }
                eligibleDatesByCode.put(eligibilityKey,
                        intersection == null ? Set.of() : Set.copyOf(intersection));
            }
        }
        return calibrateSigmaProfileAtCutoff(
                markets, codesByMarket, from, to, cutoff, calibrationDates, eligibleDatesByCode);
    }

    /**
     * Fold-local calibration: every observation is bounded by that fold's train end.  This
     * deliberately does not apply a second chronological split; the fold train partition is
     * already the calibration sample for selecting the fold's candidate.
     */
    private CalibrationSigmaProfile calibrateSigmaProfileForFold(
            String market,
            List<String> codes,
            List<V13Attempt> eligibleAttempts,
            List<LocalDate> trainDates) {
        Map<String, Set<LocalDate>> eligibleDatesByCode = new LinkedHashMap<>();
        Set<LocalDate> allowedDates = trainDates == null
                ? Set.of() : new LinkedHashSet<>(trainDates);
        for (V13Attempt attempt : eligibleAttempts == null ? List.<V13Attempt>of() : eligibleAttempts) {
            if (attempt == null || attempt.code() == null
                    || attempt.execution().primary().isEmpty()
                    || !allowedDates.contains(attempt.execution().signalDate())) continue;
            eligibleDatesByCode.computeIfAbsent(attempt.code(), ignored -> new LinkedHashSet<>())
                    .add(attempt.execution().signalDate());
        }
        return calibrateSigmaProfileForFold(market, codes, eligibleDatesByCode, trainDates);
    }

    private CalibrationSigmaProfile calibrateSigmaProfileForFold(
            String market,
            List<String> codes,
            Map<String, Set<LocalDate>> eligibleDatesByCode,
            List<LocalDate> trainDates) {
        if (market == null || trainDates == null || trainDates.isEmpty()) {
            return CalibrationSigmaProfile.unavailable("fold_train_dates_missing");
        }
        LocalDate cutoff = trainDates.stream().filter(Objects::nonNull)
                .max(LocalDate::compareTo).orElse(null);
        if (cutoff == null) return CalibrationSigmaProfile.unavailable("fold_train_cutoff_missing");
        Set<LocalDate> allowedDates = new LinkedHashSet<>(trainDates);
        Map<String, Set<LocalDate>> qualifiedDatesByCode = new LinkedHashMap<>();
        if (eligibleDatesByCode != null) {
            eligibleDatesByCode.forEach((code, dates) -> qualifiedDatesByCode.put(
                    sigmaEligibilityKey(market, code),
                    dates == null ? Set.of() : Set.copyOf(dates)));
        }
        return calibrateSigmaProfileAtCutoff(
                Set.of(market), Map.of(market, codes == null ? List.of() : codes),
                null, null, cutoff, allowedDates, qualifiedDatesByCode);
    }

    private CalibrationSigmaProfile calibrateSigmaProfileAtCutoff(
            Set<String> markets,
            Map<String, List<String>> codesByMarket,
            LocalDate from,
            LocalDate to,
            LocalDate cutoff) {
        return calibrateSigmaProfileAtCutoff(markets, codesByMarket, from, to, cutoff, null);
    }

    /**
     * Exact fold calibration variant.  A fold's train end is only an upper bound;
     * the allowed-date set is the actual train partition.  This prevents a
     * signal date that happens to fall before the end but outside the fold's
     * train calendar from silently entering sigma/normalized-bias calibration.
     */
    private CalibrationSigmaProfile calibrateSigmaProfileAtCutoff(
            Set<String> markets,
            Map<String, List<String>> codesByMarket,
            LocalDate from,
            LocalDate to,
            LocalDate cutoff,
            Set<LocalDate> allowedDates) {
        return calibrateSigmaProfileAtCutoff(
                markets, codesByMarket, from, to, cutoff, allowedDates, null);
    }

    /**
     * Optional per-code date set closes the cost/missing-open boundary: a date made globally
     * tradable by another code or instrument must not re-admit this code's excluded execution
     * into sigma calibration.
     */
    private CalibrationSigmaProfile calibrateSigmaProfileAtCutoff(
            Set<String> markets,
            Map<String, List<String>> codesByMarket,
            LocalDate from,
            LocalDate to,
            LocalDate cutoff,
            Set<LocalDate> allowedDates,
            Map<String, Set<LocalDate>> eligibleDatesByCode) {
        if (cutoff == null) return CalibrationSigmaProfile.unavailable("calibration_cutoff_missing");
        List<BigDecimal> values = new ArrayList<>();
        List<BigDecimal> normalizedAbsValues = new ArrayList<>();
        LocalDate asOfFrom = null;
        LocalDate asOfTo = null;
        List<String> orderedMarkets = (markets == null ? Set.<String>of() : markets).stream()
                .filter(Objects::nonNull).sorted().toList();
        for (String market : orderedMarkets) {
            List<String> orderedCodes = codesByMarket.getOrDefault(market, List.of()).stream()
                    .filter(Objects::nonNull).sorted().toList();
            for (String code : orderedCodes) {
                List<StockPriceHistory> raw = priceHistoryRepo
                        .findAllByStockCodeAndMarketOrderByTradingDateAsc(code, market);
                if (raw == null || raw.isEmpty()) continue;
                List<StockPriceHistory> rows = raw.stream()
                        .filter(row -> row != null && row.getTradingDate() != null
                                && row.getClosePrice() != null && row.getClosePrice().signum() > 0
                                && !row.getTradingDate().isAfter(cutoff))
                        .sorted(Comparator.comparing(StockPriceHistory::getTradingDate))
                        .toList();
                if (rows.size() <= WARMUP) continue;
                LocalDate seriesFrom = rows.get(0).getTradingDate();
                LocalDate seriesTo = rows.get(rows.size() - 1).getTradingDate();
                List<StockDividendHistory> events = dividendHistoryRepo
                        .findAdjustmentEvents(code, market, seriesFrom, seriesTo);
                if (events == null) events = List.of();
                for (int t = WARMUP; t < rows.size(); t++) {
                    LocalDate signalDate = rows.get(t).getTradingDate();
                    if (from != null && signalDate.isBefore(from)) continue;
                    if (to != null && signalDate.isAfter(to)) continue;
                    // rows are already filtered to <= cutoff, but keep the guard as a
                    // per-observation continue rather than an order-dependent early break:
                    // provider rows must not be able to truncate another date/code group.
                    if (signalDate.isAfter(cutoff)) continue;
                    if (allowedDates != null && !allowedDates.contains(signalDate)) continue;
                    if (eligibleDatesByCode != null
                            && !eligibleDatesByCode.getOrDefault(
                                    sigmaEligibilityKey(market, code), Set.of()).contains(signalDate)) continue;
                    List<StockPriceHistory> windowDesc = new ArrayList<>(rows.subList(
                            Math.max(0, t - WINDOW + 1), t + 1));
                    Collections.reverse(windowDesc);
                    List<StockDividendHistory> windowEvents = eventsWithin(
                            events, windowDesc.get(windowDesc.size() - 1).getTradingDate(), signalDate);
                    RadarInputAssembler.Assembled assembled = assembler.assemble(
                            windowDesc, windowEvents, false,
                            Math.min(windowDesc.size(), DAILY_CONTRACT_ROWS), DAILY_CONTRACT_ROWS,
                            windowDesc.get(0).getClosePrice());
                    BigDecimal sigma = assembled.returnStdDev60Ratio();
                    if (sigma == null || sigma.signum() <= 0) continue;
                    values.add(sigma);
                    if (assembled.ma60BiasPercent() != null) {
                        BigDecimal normalizedAbs = assembled.ma60BiasPercent()
                                .movePointLeft(2).abs()
                                .divide(sigma, 12, RoundingMode.HALF_UP);
                        if (normalizedAbs.signum() > 0 && finite(normalizedAbs)) {
                            normalizedAbsValues.add(normalizedAbs);
                        }
                    }
                    asOfFrom = asOfFrom == null || signalDate.isBefore(asOfFrom) ? signalDate : asOfFrom;
                    asOfTo = asOfTo == null || signalDate.isAfter(asOfTo) ? signalDate : asOfTo;
                }
            }
        }
        if (values.isEmpty()) {
            return new CalibrationSigmaProfile(null, null, null, 0, asOfFrom, asOfTo, cutoff,
                    "adjusted_completed_close_sigma60_no_observations", null, null, null);
        }
        BigDecimal saturation = normalizedAbsValues.isEmpty()
                ? null : boundedMultiple(percentile(normalizedAbsValues, 50));
        BigDecimal upper = normalizedAbsValues.isEmpty()
                ? null : boundedMultiple(percentile(normalizedAbsValues, 90));
        BigDecimal lower = normalizedAbsValues.isEmpty()
                ? null : boundedMultiple(percentile(normalizedAbsValues, 90));
        return new CalibrationSigmaProfile(
                percentile(values, 5), percentile(values, 10), percentile(values, 25),
                values.size(), asOfFrom, asOfTo, cutoff,
                "RadarInputAssembler.adjusted_completed_close_return_stddev60_ratio",
                saturation, upper, lower);
    }

    private String sigmaEligibilityKey(String market, String code) {
        return Objects.toString(market, "") + "\u0000" + Objects.toString(code, "");
    }

    private BigDecimal boundedMultiple(BigDecimal value) {
        if (value == null || value.signum() <= 0 || !finite(value)) return null;
        return value.max(new BigDecimal("0.10")).min(BigDecimal.valueOf(100));
    }

    private Map<String, Map<LocalDate, TradingRadarRuleEngine.MarketRegime>> buildV13MarketRegimes(
            Set<String> markets) {
        Map<String, Map<LocalDate, TradingRadarRuleEngine.MarketRegime>> out = new LinkedHashMap<>();
        if (markets.contains(RadarBacktestExecution.TW_MARKET)) {
            out.put(RadarBacktestExecution.TW_MARKET, buildMarketRegimes());
        }
        if (markets.contains(RadarBacktestExecution.US_MARKET)) {
            out.put(RadarBacktestExecution.US_MARKET, buildUsMarketRegimes());
        }
        return out;
    }

    /** 美股回測使用 IXIC 自身的 as-of 技術 regime，不把台股大盤套到美股。 */
    private Map<LocalDate, TradingRadarRuleEngine.MarketRegime> buildUsMarketRegimes() {
        List<UsIndexDailyHistory> source = usIndexRepo.findByIndexCodeOrderByTradingDateAsc("IXIC");
        if (source == null || source.isEmpty()) return Map.of();
        List<StockPriceHistory> rows = source.stream()
                .filter(row -> row.getClosePoint() != null && row.getClosePoint().signum() > 0)
                .map(row -> StockPriceHistory.builder()
                        .stockCode("IXIC").market(RadarBacktestExecution.US_MARKET)
                        .tradingDate(row.getTradingDate()).openPrice(row.getOpenPoint())
                        .highPrice(row.getHighPoint()).lowPrice(row.getLowPoint())
                        .closePrice(row.getClosePoint()).volume(row.getVolume()).build())
                .toList();
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> out = new LinkedHashMap<>();
        for (int t = WARMUP; t < rows.size(); t++) {
            List<StockPriceHistory> windowDesc = new ArrayList<>(
                    rows.subList(Math.max(0, t - WINDOW + 1), t + 1));
            Collections.reverse(windowDesc);
            RadarInputAssembler.Assembled a = assembler.assemble(
                    windowDesc, List.of(), false,
                    Math.min(windowDesc.size(), DAILY_CONTRACT_ROWS), DAILY_CONTRACT_ROWS,
                    windowDesc.get(0).getClosePrice());
            BigDecimal change = percentChange(rows.get(t).getClosePrice(), rows.get(t - 1).getClosePrice());
            TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            windowDesc.get(0).getClosePrice(), change, assembler.indicators(a.indicators()),
                            a.ma60Confirmation(), a.ma240Confirmation(), change,
                            // Task 342.7：量比必須與 production 的 buildUsMarket() 同源。
                            // RadarInputAssembler.volumeRatio 與 TradingRadarMarketContextService.ratio()
                            // 演算法逐項相同（前 20 個正成交量日的中位數為分母、至少 10 筆樣本、scale 4
                            // HALF_UP），且本方法已把 us_index_daily_history 的 volume 映射進轉型後的列。
                            // 這裡若維持 null，production 補上量比後該計分分支會「線上生效、回測不生效」，
                            // 且沒有任何測試抓得到——正是 Task 323 標題所說的「停止線上與回測分岔」。
                            a.volumeRatio(),
                            // marketTurnoverRatio：無成交值來源，與 production 一致維持 null。
                            null,
                            null, null, null, false,
                            // crossMarketApplicable=false：美股大盤即 IXIC，跨市場因子不適用（同 production）。
                            false,
                            // Task 356.13b-2：回測自行組建的 MarketInput 必須接上週K 與日K 棒，
                            // 否則大盤那五項加減分「線上生效、回測不生效」，且沒有任何測試抓得到。
                            a.dailyCandle(),
                            a.weekly()));
            out.put(rows.get(t).getTradingDate(), result.regime());
        }
        return Map.copyOf(out);
    }

    private TradingRadarRuleEngine.StockInput v13Input(
            RadarInputAssembler.Assembled a,
            boolean held,
            BigDecimal price,
            RadarBacktestExecution.InstrumentKind kind,
            TradingRadarRuleEngine.MarketRegime regime,
            BigDecimal fxPct,
            BigDecimal premium,
            BigDecimal premiumPercentile,
            TradingRadarRuleEngine.FundamentalInput fundamental) {
        TradingRadarRuleEngine.InstrumentType instrumentType = kind == RadarBacktestExecution.InstrumentKind.BOND_ETF
                ? TradingRadarRuleEngine.InstrumentType.BOND
                : TradingRadarRuleEngine.InstrumentType.EQUITY;
        return new TradingRadarRuleEngine.StockInput(
                held, price, a.ruleChangePercent(), a.completedChangePercent(),
                assembler.indicators(a.indicators()), a.indicators().previousK(), a.indicators().previousD(),
                a.ma20Confirmation(), a.ma60Confirmation(), a.ma240Confirmation(), instrumentType, regime,
                false, fxPct, a.ma60BiasPercent(), a.ma60BiasPercentile(), a.ma240BiasPercent(),
                a.week52Position(), a.kdBandWidthPercent(), premium, premiumPercentile, a.indicators().weeklyMa(),
                assembler.extendedIndicators(a.indicators().extended()), a.volumeRatio(), fundamental,
                // Task 356.13a-2：V13 candidate 回測路徑同樣必須接上日K 棒與週K，否則 promotion
                // 稽核比較的是「有週K 的 production」與「沒有週K 的回測」，兩者根本不同一組規則。
                a.dailyCandle(), a.weekly());
    }

    private TradingRadarRuleEngine.CandidateContext v13Context(
            RadarInputAssembler.Assembled a,
            TradingRadarRuleEngine.MarketRegime regime,
            TradingRadarRuleEngine.FundamentalInput fundamental,
            BigDecimal price,
            String market,
            java.time.Instant decisionInstant,
            RadarObservationResolver.AcceptedPrice acceptedPrice,
            TradingRadarEvidenceConfidenceResolver.MarketContext marketContext,
            TradingRadarDto.FundamentalSnapshot fundamentalSnapshot,
            TradingRadarAssetProfileResolver.AssetProfile profile,
            BigDecimal premium,
            LocalDate premiumAsOfDate,
            String premiumSource,
            boolean premiumStale,
            TradingRadarMarketContextService.FxContext fx,
            TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation,
            TradingRadarRuleEngine.TimingState timingState,
            DividendEventEvidenceResolver.Resolution dividendEvent,
            TradingRadarMarketFeatureResolver.Evidence marketFeatures,
            BondYieldBetaResolver.Result bondYieldBeta,
            LocalDate expectedPriceTerminal,
            boolean sigmaProfileAvailable) {
        boolean technicalComplete = price != null && a.indicators() != null
                && a.indicators().monthlyMa() != null && a.indicators().quarterlyMa() != null
                && a.indicators().annualMa() != null && a.indicators().k() != null
                && a.indicators().d() != null;
        boolean marketFresh = regime != null && regime != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE;
        boolean sigmaAvailable = a.returnStdDev60Ratio() != null
                && a.returnStdDev60Ratio().signum() > 0;
        TradingRadarEvidenceConfidenceResolver.Evidence evidence =
                TradingRadarEvidenceConfidenceResolver.resolve(
                        new TradingRadarEvidenceConfidenceResolver.Inputs(
                                market, decisionInstant, acceptedPrice, a, regime, !marketFresh,
                                marketContext, fundamentalSnapshot, profile,
                                premium, premiumAsOfDate, premiumSource, premiumStale,
                                fx == null ? null : fx.percentile(),
                                fx == null ? null : fx.asOfDate(), rateObservation, timingState,
                                dividendEvent, marketFeatures),
                        expectedPriceTerminal,
                        marketContext == null ? null : marketContext.asOfDate());
        BigDecimal shortConfidence = BigDecimal.valueOf(evidence.shortConfidence())
                .movePointLeft(2);
        BigDecimal mediumConfidence = BigDecimal.valueOf(evidence.mediumConfidence())
                .movePointLeft(2);
        BigDecimal confidence = shortConfidence.min(mediumConfidence);
        if (!technicalComplete || !marketFresh || !sigmaAvailable) {
            // 保留 sigma 的 candidate normalized-bias data gate；Evidence confidence 才是
            // action gate 的完整來源，這裡只在 legacy 共用欄位揭露最保守值。
            confidence = confidence.min(sigmaAvailable ? BigDecimal.ONE : new BigDecimal("0.50"));
        }
        BigDecimal shortDownside = evidence.shortDownsideRisk() == null ? null
                : BigDecimal.valueOf(evidence.shortDownsideRisk());
        BigDecimal mediumDownside = evidence.mediumDownsideRisk() == null ? null
                : BigDecimal.valueOf(evidence.mediumDownsideRisk());
        BondYieldBetaContribution.Contributions treasury =
                BondYieldBetaContribution.from(bondYieldBeta);
        TradingRadarMarketFeatureResolver.AggregatedContribution marketContribution =
                evidence.marketFeatures().aggregateContribution(profile);
        return new TradingRadarRuleEngine.CandidateContext(
                acceptedPrice != null && acceptedPrice.available(), marketFresh, confidence,
                shortDownside, mediumDownside, treasury.shortTerm(), treasury.mediumTerm(),
                a.returnStdDev60Ratio(),
                shortConfidence, mediumConfidence, evidence, profile, evidence.marketFeatures(), bondYieldBeta,
                marketContribution.shortTerm(), marketContribution.mediumTerm(),
                a.volatility60() == null ? null : a.volatility60().asOfDate(), sigmaProfileAvailable);
    }

    /**
     * Candidate grids are rebuilt from the request/global calibration profile.  Disabled
     * candidates intentionally remain in an unavailable grid for reporting, but they must
     * carry that profile status into the action gate instead of treating a row's raw sigma as
     * proof that calibration succeeded.
     */
    private boolean sigmaProfileAvailable(List<RuleParameters> candidateGrid) {
        return candidateGrid != null && candidateGrid.stream()
                .filter(Objects::nonNull)
                .anyMatch(RuleParameters::normalizedBiasEnabled);
    }

    /**
     * V13 evidence market context 一律由 market-aware instant 截斷；TW legacy
     * buildMarketRegimes 仍走原本的台北 14:00 helper。若跨市場資料沒有同一個
     * signal date，僅保留 regime evidence，不把較晚的量能列冒充當日 context。
     */
    private TradingRadarEvidenceConfidenceResolver.MarketContext resolveV13MarketContext(
            LocalDate signalDate, String market) {
        try {
            List<TwseIndexDailyHistory> twRows = twseRepo.findAllByOrderByTradingDateAsc();
            List<UsIndexDailyHistory> usRows = new ArrayList<>();
            List<UsIndexDailyHistory> ixic = usIndexRepo
                    .findByIndexCodeOrderByTradingDateAsc("IXIC");
            List<UsIndexDailyHistory> sox = usIndexRepo
                    .findByIndexCodeOrderByTradingDateAsc("SOX");
            if (ixic != null) usRows.addAll(ixic);
            if (sox != null) usRows.addAll(sox);
            TradingRadarMarketContextService.MarketContext resolved =
                    marketContextService.resolveMarketFromRows(
                            market,
                            signalInstant(signalDate, market),
                            twRows == null ? List.of() : twRows,
                            usRows);
            if (resolved == null || resolved.marketAsOfDate() == null
                    || resolved.marketAsOfDate().isAfter(signalDate)) {
                return TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY;
            }
            return new TradingRadarEvidenceConfidenceResolver.MarketContext(
                    resolved.marketAsOfDate(), resolved.marketVolumeRatio(),
                    resolved.marketTurnoverRatio(), "MARKET_CONTEXT_AS_OF",
                    resolved.liquidityApplicable());
        } catch (RuntimeException e) {
            log.warn("V13 市場 context 解析失敗（{}/{}）：{}", market, signalDate, e.getMessage());
            return TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY;
        }
    }

    /** Load each market's immutable index snapshot once, then resolve every signal date in memory. */
    private Map<LocalDate, TradingRadarEvidenceConfidenceResolver.MarketContext>
            resolveV13MarketContextsBatch(String market, List<java.time.Instant> decisionInstants) {
        if (market == null || decisionInstants == null || decisionInstants.isEmpty()) return Map.of();
        try {
            List<TwseIndexDailyHistory> twRows = twseRepo.findAllByOrderByTradingDateAsc();
            List<UsIndexDailyHistory> usRows = new ArrayList<>();
            List<UsIndexDailyHistory> ixic = usIndexRepo.findByIndexCodeOrderByTradingDateAsc("IXIC");
            List<UsIndexDailyHistory> sox = usIndexRepo.findByIndexCodeOrderByTradingDateAsc("SOX");
            if (ixic != null) usRows.addAll(ixic);
            if (sox != null) usRows.addAll(sox);
            Map<LocalDate, TradingRadarEvidenceConfidenceResolver.MarketContext> result = new LinkedHashMap<>();
            for (java.time.Instant instant : decisionInstants.stream().filter(Objects::nonNull).distinct().toList()) {
                LocalDate date = instant.atZone(MarketZones.resolve(market)).toLocalDate();
                TradingRadarMarketContextService.MarketContext resolved =
                        marketContextService.resolveMarketFromRows(market, instant,
                                twRows == null ? List.of() : twRows,
                                usRows);
                if (resolved == null || resolved.marketAsOfDate() == null
                        || resolved.marketAsOfDate().isAfter(date)) {
                    result.put(date, TradingRadarEvidenceConfidenceResolver.MarketContext.EMPTY);
                } else {
                    result.put(date, new TradingRadarEvidenceConfidenceResolver.MarketContext(
                            resolved.marketAsOfDate(), resolved.marketVolumeRatio(),
                            resolved.marketTurnoverRatio(), "MARKET_CONTEXT_AS_OF",
                            resolved.liquidityApplicable()));
                }
            }
            return Map.copyOf(result);
        } catch (RuntimeException e) {
            log.warn("V13 市場 context batch 解析失敗（{}，n={}）：{}",
                    market, decisionInstants.size(), e.getMessage());
            return Map.of();
        }
    }

    /** V13 bond evidence 使用與 production 相同的完整 Treasury batch as-of query。 */
    private TradingRadarEvidenceConfidenceResolver.RateObservation rateObservation(
            TreasuryYieldDto.RateContext context, BondYieldBetaResolver.Result beta) {
        BondYieldBetaContribution.Contributions contribution = BondYieldBetaContribution.from(beta);
        Double shortContribution = contribution.shortTerm();
        BigDecimal riskUnit = shortContribution == null ? null
                : BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, -shortContribution)));
        String reason = beta == null
                ? "Treasury batch 已知，但 bond beta evidence 缺漏"
                : "Treasury batch 已知；bond beta status=" + beta.status()
                + " n=" + beta.n() + "，未經 V13 holdout promotion 不納入分數";
        return new TradingRadarEvidenceConfidenceResolver.RateObservation(context, riskUnit, reason);
    }

    private TradingRadarEvidenceConfidenceResolver.RateObservation resolveV13TreasuryRateObservation(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            java.time.Instant decisionInstant,
            RuleParameters parameters,
            BondYieldBetaResolver.Result bondYieldBeta) {
        if (profile == null || !profile.bond()) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.notApplicable();
        }
        if (!profile.profileComplete()) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "strict bond profile 不完整，禁止猜測 Treasury tenor");
        }
        if (treasuryYieldService == null) {
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "V13 backtest Treasury resolver 未注入");
        }
        BondRateQueryResolver.Selection selection = BondRateQueryResolver.select(
                profile.bondTerm(), parameters);
        String tenor = bondYieldBeta != null && bondYieldBeta.tenor() != null
                ? bondYieldBeta.tenor()
                : selection == null ? null : selection.primaryTenor();
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
            log.warn("V13 Treasury context 解析失敗：{}", e.getMessage());
            return TradingRadarEvidenceConfidenceResolver.RateObservation.missing(
                    "Treasury context 解析失敗");
        }
    }

    /**
     * Cached Treasury context lookup for one code/request.  Candidate grid entries commonly
     * share the same tenor; the cache key is the typed decision instant plus tenor, never a
     * mutable current-state snapshot.
     */
    private TradingRadarEvidenceConfidenceResolver.RateObservation resolveV13TreasuryRateObservation(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            java.time.Instant decisionInstant,
            RuleParameters parameters,
            BondYieldBetaResolver.Result bondYieldBeta,
            Map<TreasuryObservationKey, TradingRadarEvidenceConfidenceResolver.RateObservation> cache) {
        if (cache == null) {
            return resolveV13TreasuryRateObservation(profile, decisionInstant, parameters, bondYieldBeta);
        }
        BondRateQueryResolver.Selection selection = BondRateQueryResolver.select(
                profile == null ? null : profile.bondTerm(), parameters);
        String tenor = bondYieldBeta != null && bondYieldBeta.tenor() != null
                ? bondYieldBeta.tenor() : selection == null ? null : selection.primaryTenor();
        TreasuryObservationKey key = new TreasuryObservationKey(decisionInstant, tenor);
        return cache.computeIfAbsent(key, ignored -> resolveV13TreasuryRateObservation(
                profile, decisionInstant, parameters, bondYieldBeta));
    }

    /**
     * Resolve all beta queries for one immutable code snapshot in one port call.  The default
     * port implementation remains compatible with old adapters, while the historical adapter
     * can override the batch hook to share its price/dividend/FX/Treasury source loads.
     */
    private Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result>
            resolveV13BondYieldBetaBatch(
                    TradingRadarAssetProfileResolver.AssetProfile profile,
                    String code,
                    String market,
                    List<java.time.Instant> decisionInstants,
                    List<RuleParameters> candidateGrid) {
        if (profile == null || !profile.bond() || decisionInstants == null
                || decisionInstants.isEmpty() || bondYieldBetaEvidencePort == null) {
            return Map.of();
        }
        List<BondYieldBetaResolver.Query> queries = new ArrayList<>();
        List<RuleParameters> parameters = new ArrayList<>();
        parameters.add(RuleParameters.v12Default());
        if (candidateGrid != null) {
            parameters.addAll(candidateGrid.stream()
                    .filter(Objects::nonNull)
                    .filter(candidate -> RuleParameters.V13_VERSION.equals(candidate.ruleVersion()))
                    .toList());
        }
        for (java.time.Instant instant : decisionInstants.stream().filter(Objects::nonNull).distinct().toList()) {
            for (RuleParameters parameter : parameters) {
                BondYieldBetaResolver.Query query = BondRateQueryResolver.query(
                        code, market, profile, instant, parameter);
                if (query.tenor() != null && !queries.contains(query)) queries.add(query);
            }
        }
        if (queries.isEmpty()) return Map.of();
        try {
            Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> resolved =
                    bondYieldBetaEvidencePort.resolveBatch(List.copyOf(queries));
            return resolved == null ? Map.of() : Map.copyOf(resolved);
        } catch (RuntimeException e) {
            log.warn("V13 bond beta batch 解析失敗（{}/{}，n={}）：{}",
                    market, code, queries.size(), e.getMessage());
            return Map.of();
        }
    }

    /** Load strict, lag-aligned per-instrument beta evidence for the candidate context. */
    private BondYieldBetaResolver.Result resolveV13BondYieldBeta(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String code,
            String market,
            java.time.Instant decisionInstant,
            RuleParameters parameters) {
        return resolveV13BondYieldBeta(profile, code, market, decisionInstant, parameters, null);
    }

    private BondYieldBetaResolver.Result resolveV13BondYieldBeta(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String code,
            String market,
            java.time.Instant decisionInstant,
            RuleParameters parameters,
            Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> batch) {
        if (profile == null || !profile.bond()) {
            return BondYieldBetaResolver.Result.notApplicable(null);
        }
        BondYieldBetaResolver.Query query = BondRateQueryResolver.query(
                code, market, profile, decisionInstant, parameters);
        if (!profile.profileComplete() || query.tenor() == null) {
            return BondYieldBetaResolver.Result.missing(query,
                    "strict bond profile／tenor 不完整，禁止猜測 beta");
        }
        if (batch != null && batch.containsKey(query)) {
            BondYieldBetaResolver.Result result = batch.get(query);
            return result == null
                    ? BondYieldBetaResolver.Result.missing(query, "bond beta batch 回傳空值")
                    : result;
        }
        if (bondYieldBetaEvidencePort == null) {
            return BondYieldBetaResolver.Result.missing(query,
                    "bond beta evidence port 未注入");
        }
        try {
            BondYieldBetaResolver.Result result = bondYieldBetaEvidencePort.resolve(query);
            return result == null
                    ? BondYieldBetaResolver.Result.missing(query, "bond beta resolver 回傳空值")
                    : result;
        } catch (RuntimeException e) {
            log.warn("V13 bond beta 解析失敗（{}/{}）：{}", market, code, e.getMessage());
            return BondYieldBetaResolver.Result.missing(query, "bond beta resolver 解析失敗");
        }
    }

    private String productionProfile(TradingRadarAssetProfileResolver.AssetProfile profile) {
        if (profile == null) return "UNKNOWN";
        if (profile.bond()) return "BOND_" + identity(profile.bondTerm());
        if (profile.equity() && profile.stockStyle() != null) return identity(profile.stockStyle());
        if (profile.assetClass() != null) return identity(profile.assetClass());
        return "UNKNOWN";
    }

    private String identity(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase();
    }

    private String confidenceIdentity(String value) {
        return value == null || value.isBlank() ? "MISSING" : value.trim().toUpperCase();
    }

    private String confidenceDecile(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence, int horizon) {
        if (evidence == null) return "MISSING";
        int confidence = horizon <= 20 ? evidence.shortConfidence() : evidence.mediumConfidence();
        if (confidence < 0 || confidence > 100) return "MISSING";
        return "D" + Math.min(9, confidence / 10);
    }

    private String productionIdentity(
            RadarBacktestExecution.InstrumentKind kind,
            String productionProfile) {
        return identity(kind == null ? null : kind.name()) + "|"
                + identity(productionProfile);
    }

    private BigDecimal percentChange(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() <= 0) return null;
        return current.divide(previous, 8, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
    }

    /**
     * Build split metrics only after calibration has selected a concrete V13 candidate.
     * The two distributions are calculated from candidateStats' same action-track sample
     * (entry first, held fallback), while candidate/baseline coverage remains separate.
     * A missing selection is an explicit insufficient/null report; the whole primary
     * attempts list must never be presented as candidate alpha.
     */
    private BacktestDto.SplitExecutionStat splitStat(
            List<V13Attempt> attempts, String selectedCandidateId, int horizon) {
        return splitStatInternal(attempts, selectedCandidateId, horizon, null);
    }

    private BacktestDto.SplitExecutionStat splitStat(
            List<V13Attempt> attempts, RuleParameters selectedCandidate, int horizon) {
        return splitStatInternal(attempts,
                selectedCandidate == null ? null : selectedCandidate.parameterSetId(),
                horizon, selectedCandidate);
    }

    private BacktestDto.SplitExecutionStat splitStatInternal(
            List<V13Attempt> attempts, String selectedCandidateId, int horizon,
            RuleParameters replayCandidate) {
        List<V13Attempt> safeAttempts = attempts == null ? List.of() : attempts;
        Set<String> attemptCodes = safeAttempts.stream()
                .map(V13Attempt::code).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int attemptN = safeAttempts.size();
        int attemptCodeCount = attemptCodes.size();
        if (selectedCandidateId == null || selectedCandidateId.isBlank()) {
            return new BacktestDto.SplitExecutionStat(
                    attemptN, attemptCodeCount, null, null, null, null, null, null, null, null, null,
                    attemptN, attemptCodeCount, null,
                    "INSUFFICIENT", "NO_SELECTED_CANDIDATE",
                    0, 0, 0, 0, 0, 0, null, null, null);
        }

        V13CandidateStats stats = replayCandidate == null
                ? candidateStats(safeAttempts, selectedCandidateId, horizon)
                : candidateStats(safeAttempts, replayCandidate, horizon);
        if (stats.intersectionN() <= 0) {
            return new BacktestDto.SplitExecutionStat(
                    attemptN, attemptCodeCount, null, null, null, null, null, null, null, null, null,
                    attemptN, attemptCodeCount, selectedCandidateId,
                    "INSUFFICIENT", "NO_CANDIDATE_BASELINE_ACTION_TRACK_INTERSECTION",
                    stats.candidateN(), stats.candidateCodes(), stats.baselineN(), stats.baselineCodes(),
                    0, 0, null, null, null);
        }

        BacktestDto.ExecutionDistribution candidate = executionDistribution(
                stats.intersectionCandidateGrossReturns(), stats.intersectionCandidateReturns(),
                stats.intersectionCodes());
        BacktestDto.ExecutionDistribution baseline = executionDistribution(
                stats.intersectionBaselineGrossReturns(), stats.intersectionBaselineReturns(),
                stats.intersectionCodes());
        BacktestDto.ExecutionDelta delta = executionDelta(candidate, baseline);
        return new BacktestDto.SplitExecutionStat(
                candidate.n(), candidate.codeCount(), candidate.grossMeanPct(), candidate.netMeanPct(),
                candidate.netMedianPct(), candidate.netWinRatePct(), candidate.netDownsideRiskPct(),
                candidate.netP5Pct(), candidate.netP25Pct(), candidate.netP75Pct(), candidate.netP95Pct(),
                attemptN, attemptCodeCount, selectedCandidateId, "AVAILABLE", null,
                stats.candidateN(), stats.candidateCodes(), stats.baselineN(), stats.baselineCodes(),
                stats.intersectionN(), stats.intersectionCodes(), candidate, baseline, delta);
    }

    private BacktestDto.ExecutionDistribution executionDistribution(
            List<BigDecimal> gross, List<BigDecimal> net, int codeCount) {
        if (gross == null || net == null || gross.isEmpty() || net.isEmpty()) return null;
        BigDecimal grossMean = mean(gross);
        BigDecimal netMean = mean(net);
        return new BacktestDto.ExecutionDistribution(
                Math.min(gross.size(), net.size()), codeCount,
                grossMean, percentile(gross, 50), ratioAbove(gross, BigDecimal.ZERO),
                ratioAtOrBelow(gross, DOWNSIDE), percentile(gross, 5), percentile(gross, 25),
                percentile(gross, 75), percentile(gross, 95),
                netMean, percentile(net, 50), ratioAbove(net, BigDecimal.ZERO),
                ratioAtOrBelow(net, DOWNSIDE), percentile(net, 5), percentile(net, 25),
                percentile(net, 75), percentile(net, 95), diff(netMean, grossMean));
    }

    private BacktestDto.ExecutionDelta executionDelta(
            BacktestDto.ExecutionDistribution candidate,
            BacktestDto.ExecutionDistribution baseline) {
        if (candidate == null || baseline == null) return null;
        return new BacktestDto.ExecutionDelta(
                diff(candidate.grossMeanPct(), baseline.grossMeanPct()),
                diff(candidate.grossMedianPct(), baseline.grossMedianPct()),
                diff(candidate.grossWinRatePct(), baseline.grossWinRatePct()),
                diff(candidate.grossDownsideRiskPct(), baseline.grossDownsideRiskPct()),
                diff(candidate.grossP5Pct(), baseline.grossP5Pct()),
                diff(candidate.grossP25Pct(), baseline.grossP25Pct()),
                diff(candidate.grossP75Pct(), baseline.grossP75Pct()),
                diff(candidate.grossP95Pct(), baseline.grossP95Pct()),
                diff(candidate.netMeanPct(), baseline.netMeanPct()),
                diff(candidate.netMedianPct(), baseline.netMedianPct()),
                diff(candidate.netWinRatePct(), baseline.netWinRatePct()),
                diff(candidate.netDownsideRiskPct(), baseline.netDownsideRiskPct()),
                diff(candidate.netP5Pct(), baseline.netP5Pct()),
                diff(candidate.netP25Pct(), baseline.netP25Pct()),
                diff(candidate.netP75Pct(), baseline.netP75Pct()),
                diff(candidate.netP95Pct(), baseline.netP95Pct()),
                diff(candidate.costImpactPct(), baseline.costImpactPct()));
    }

    private BacktestDto.ExecutionDateExample firstExecution(List<V13Attempt> attempts) {
        return attempts.stream()
                .sorted(Comparator.comparing((V13Attempt attempt) -> attempt.execution().signalDate())
                        .thenComparing(V13Attempt::code))
                .findFirst()
                .map(attempt -> {
                    RadarBacktestExecution.ExecutionSample sample =
                            attempt.execution().primary().orElseThrow();
                    return new BacktestDto.ExecutionDateExample(
                            attempt.code(),
                            BacktestDto.InstrumentKind.valueOf(attempt.instrumentKind().name()),
                            sample.signalDate().toString(),
                            sample.entryDate().toString(),
                            sample.exitDate().toString(),
                            sample.entryPrice(),
                            sample.exitPrice());
                })
                .orElse(null);
    }

    private LocalDate firstDate(List<LocalDate> dates) {
        return dates == null || dates.isEmpty() ? null : dates.stream()
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
    }

    /** A train/calibration row is usable only when its realised primary exit is before boundary. */
    private boolean eligibleBeforeBoundary(V13Attempt attempt, LocalDate boundary) {
        if (attempt == null || boundary == null || attempt.execution() == null) return false;
        return attempt.execution().primary().map(sample -> sample.exitDate() != null
                && sample.exitDate().isBefore(boundary)).orElse(false);
    }

    private BacktestDto.PurgeEmbargoEvidence purgeEmbargoEvidence(
            List<V13Attempt> primary,
            RadarWalkForwardPlan.ChronologicalSplit split,
            List<RadarWalkForwardPlan.Fold> folds) {
        List<V13Attempt> safe = primary == null ? List.of() : primary;
        LocalDate calibrationBoundary = firstDate(split == null ? List.of() : split.holdoutDates());
        Set<String> calibrationCodes = new LinkedHashSet<>();
        int calibrationPurged = 0;
        if (split != null && split.sufficientForCutoff()) {
            for (V13Attempt attempt : safe) {
                if (split.isCalibration(attempt.execution().signalDate())
                        && !eligibleBeforeBoundary(attempt, calibrationBoundary)) {
                    calibrationPurged++;
                    if (attempt.code() != null) calibrationCodes.add(attempt.code());
                }
            }
        }
        Set<String> foldCodes = new LinkedHashSet<>();
        int foldPurged = 0;
        if (folds != null) {
            for (RadarWalkForwardPlan.Fold fold : folds) {
                for (V13Attempt attempt : safe) {
                    if (fold.trainDates().contains(attempt.execution().signalDate())
                            && !eligibleBeforeBoundary(attempt, fold.evaluationFrom())) {
                        foldPurged++;
                        if (attempt.code() != null) foldCodes.add(attempt.code());
                    }
                }
            }
        }
        return new BacktestDto.PurgeEmbargoEvidence(
                calibrationBoundary == null ? null : calibrationBoundary.toString(),
                calibrationPurged, calibrationCodes.size(), foldPurged, foldCodes.size());
    }

    private BacktestDto.WalkForwardFold foldDto(
            RadarWalkForwardPlan.Fold fold,
            RuleParameters selectedCandidate,
            List<V13Attempt> attempts,
            int horizon,
            CalibrationSigmaProfile sigmaProfile,
            JointFoldSelection jointSelection,
            boolean jointRequired) {
        RadarWalkForwardPlan.JointTrackFold jointFold = jointSelection == null
                || jointSelection.resolution() == null
                ? null : jointSelection.resolution().fold();
        return new BacktestDto.WalkForwardFold(
                fold.index(),
                fold.trainDates().get(0).toString(),
                fold.trainDates().get(fold.trainDates().size() - 1).toString(),
                fold.trainDates().size(),
                fold.evaluationFrom().toString(),
                fold.evaluationTo().toString(),
                fold.evaluationDates().size(),
                jointFold == null ? 0 : jointFold.jointTrainDates().size(),
                jointFold == null ? null : jointFold.jointTrainFrom(),
                jointFold == null ? null : jointFold.jointTrainTo(),
                jointFold == null ? List.of() : jointFold.requiredHorizons(),
                selectedCandidate == null ? null : selectedCandidate.parameterSetId(),
                foldExecutionEvidence(fold, attempts, selectedCandidate, horizon, sigmaProfile,
                        jointSelection, jointRequired));
    }

    private BacktestDto.FoldExecutionEvidence foldExecutionEvidence(
            RadarWalkForwardPlan.Fold fold,
            List<V13Attempt> attempts,
            RuleParameters selectedCandidate,
            int horizon,
            CalibrationSigmaProfile sigmaProfile,
            JointFoldSelection jointSelection,
            boolean jointRequired) {
        RadarWalkForwardPlan.JointTrackFold jointFold = jointSelection == null
                || jointSelection.resolution() == null
                ? null : jointSelection.resolution().fold();
        String sigmaStatus;
        String sigmaCutoff = null;
        String sigmaSource = null;
        if (sigmaProfile == null || !sigmaProfile.available()) {
            sigmaStatus = jointRequired
                    ? "JOINT_FOLD_SIGMA_PROFILE_UNAVAILABLE"
                    : "FOLD_SIGMA_PROFILE_UNAVAILABLE";
            sigmaSource = sigmaProfile == null ? "null_profile" : sigmaProfile.source();
        } else {
            sigmaCutoff = sigmaProfile.calibrationCutoff() == null
                    ? null : sigmaProfile.calibrationCutoff().toString();
            sigmaSource = sigmaProfile.source();
            LocalDate expectedCutoff = jointFold == null
                    ? fold.trainDates().get(fold.trainDates().size() - 1)
                    : jointFold.jointTrainTo();
            sigmaStatus = sigmaProfile.calibrationCutoff() == null
                    || !sigmaProfile.calibrationCutoff().equals(expectedCutoff)
                    || sigmaProfile.asOfTo() == null
                    || sigmaProfile.asOfTo().isAfter(expectedCutoff)
                    ? (jointRequired ? "JOINT_FOLD_SIGMA_PROFILE_UNAVAILABLE"
                            : "FOLD_SIGMA_PROFILE_UNAVAILABLE")
                    : (jointRequired ? "JOINT_FOLD_SIGMA_PROFILE_ASOF_TRAIN"
                            : "FOLD_SIGMA_PROFILE_ASOF_TRAIN");
        }
        List<V13Attempt> block = (attempts == null ? List.<V13Attempt>of() : attempts).stream()
                .filter(attempt -> fold.evaluationDates().contains(attempt.execution().signalDate()))
                .filter(attempt -> attempt.execution().primary().isPresent())
                .toList();
        Set<LocalDate> eligibleTrainDates = jointRequired
                ? jointFold == null ? Set.of() : Set.copyOf(jointFold.jointTrainDates())
                : Set.copyOf(fold.trainDates());
        List<V13Attempt> purgedTrain = (attempts == null ? List.<V13Attempt>of() : attempts).stream()
                .filter(attempt -> eligibleTrainDates.contains(attempt.execution().signalDate()))
                .filter(attempt -> attempt.execution().primary().isPresent())
                .filter(attempt -> !eligibleBeforeBoundary(attempt, fold.evaluationFrom()))
                .toList();
        Set<String> purgedCodes = purgedTrain.stream().map(V13Attempt::code)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        String selectedId = selectedCandidate == null ? null : selectedCandidate.parameterSetId();
        boolean sigmaUnavailable = sigmaStatus.endsWith("SIGMA_PROFILE_UNAVAILABLE");
        boolean jointUnavailable = jointRequired
                && (jointSelection == null || !jointSelection.available());
        BacktestDto.SplitExecutionStat split = sigmaUnavailable || jointUnavailable
                ? splitStat(block, (String) null, horizon)
                : splitStat(block, selectedCandidate, horizon);
        String purgeReason = purgedTrain.isEmpty() ? null
                : "PURGED_TRAIN_EXIT_OVERLAP=" + purgedTrain.size();
        String reason = sigmaUnavailable ? sigmaStatus
                : jointUnavailable && jointSelection != null && !jointSelection.reason().isBlank()
                ? jointSelection.reason()
                : jointUnavailable ? "JOINT_FOLD_UNAVAILABLE" : split.reason();
        if (purgeReason != null) reason = reason == null || reason.isBlank()
                ? purgeReason : reason + ";" + purgeReason;
        List<BacktestDto.FoldNormalizedBiasProvenance> normalizedBiasRows =
                foldNormalizedBiasRows(fold, block, selectedCandidate, horizon, sigmaProfile);
        return new BacktestDto.FoldExecutionEvidence(
                sigmaStatus, sigmaCutoff, sigmaSource,
                jointUnavailable ? "UNAVAILABLE" : split.status(), reason,
                sigmaUnavailable || jointUnavailable
                        ? null : split.selectedCandidateParameterSetId(),
                split.candidateCoverageN(), split.candidateCoverageCodes(),
                split.baselineCoverageN(), split.baselineCoverageCodes(),
                split.intersectionN(), split.intersectionCodes(),
                split.candidate(), split.baseline(), split.delta(),
                sigmaUnavailable || jointUnavailable
                        ? null : parameterSnapshot(selectedCandidate, sigmaProfile),
                normalizedBiasRows, purgedTrain.size(), purgedCodes.size(),
                fold.evaluationFrom().toString());
    }

    /**
     * Echo the exact per-signal normalized-BIAS observation used by the fold-local replay.  The
     * list is sorted by signal date/code and bounded so a decade-long report cannot become an
     * unbounded JSON/CSV payload; truncation is represented by a final reason row.
     */
    private List<BacktestDto.FoldNormalizedBiasProvenance> foldNormalizedBiasRows(
            RadarWalkForwardPlan.Fold fold,
            List<V13Attempt> block,
            RuleParameters selectedCandidate,
            int horizon,
            CalibrationSigmaProfile sigmaProfile) {
        if (block == null || block.isEmpty()) return List.of();
        List<V13Attempt> ordered = block.stream()
                .sorted(Comparator.comparing((V13Attempt attempt) -> attempt.execution().signalDate())
                        .thenComparing(V13Attempt::code))
                .toList();
        List<BacktestDto.FoldNormalizedBiasProvenance> rows = new ArrayList<>();
        String track = horizon <= 20 ? "SHORT" : "MEDIUM";
        for (V13Attempt attempt : ordered) {
            if (rows.size() >= MAX_FOLD_NORMALIZED_BIAS_ROWS) break;
            V13CandidateOutcome outcome = replayCandidate(attempt, selectedCandidate,
                    sigmaProfile != null && sigmaProfile.available());
            TradingRadarRuleEngine.NormalizedBiasProvenance provenance = null;
            if (outcome != null) {
                TradingRadarRuleEngine.StockResult selected = outcome.free();
                provenance = horizon <= 20 ? selected.shortNormalizedBias() : selected.normalizedBias();
            }
            String signalDate = attempt.execution().signalDate() == null
                    ? null : attempt.execution().signalDate().toString();
            rows.add(new BacktestDto.FoldNormalizedBiasProvenance(
                    attempt.code(), signalDate, horizon, track,
                    provenance != null && provenance.enabled(),
                    provenance == null ? null : provenance.rawBiasRatio(),
                    provenance == null ? null : provenance.rawSigmaRatio(),
                    provenance == null ? null : provenance.sigmaFloorRatio(),
                    provenance == null ? null : provenance.effectiveSigmaRatio(),
                    provenance == null ? null : provenance.normalizedBias(),
                    provenance == null || provenance.asOfDate() == null
                            ? null : provenance.asOfDate().toString(),
                    provenance == null
                            ? (selectedCandidate == null ? "NO_SELECTED_CANDIDATE" : "PROVENANCE_UNAVAILABLE")
                            : provenance.reason()));
        }
        if (ordered.size() > MAX_FOLD_NORMALIZED_BIAS_ROWS) {
            rows.add(new BacktestDto.FoldNormalizedBiasProvenance(
                    null, null, horizon, track, false, null, null, null, null, null, null,
                    "TRUNCATED_MAX_" + MAX_FOLD_NORMALIZED_BIAS_ROWS));
        }
        return List.copyOf(rows);
    }

    private List<BacktestDto.ResolvedCostAssumption> resolvedCostDtos(
            Map<RadarBacktestExecution.CostKey, RadarBacktestExecution.CostAssumption> costs) {
        return costs.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<RadarBacktestExecution.CostKey,
                                RadarBacktestExecution.CostAssumption> entry) -> entry.getKey().market())
                        .thenComparing(entry -> entry.getKey().instrumentKind().name()))
                .map(entry -> new BacktestDto.ResolvedCostAssumption(
                        dtoCostKey(entry.getKey()),
                        dtoCost(entry.getValue()),
                        entry.getValue().roundTripCostPct(),
                        entry.getValue().returnPracticalDeltaPct()))
                .toList();
    }

    private Map<RadarBacktestExecution.CostKey, RadarBacktestExecution.CostAssumption> resolveV13Costs(
            Set<String> markets, Map<BacktestDto.CostKey, BacktestDto.CostAssumption> overrides) {
        Map<RadarBacktestExecution.CostKey, RadarBacktestExecution.CostAssumption> resolved =
                new LinkedHashMap<>();
        RadarBacktestExecution.defaultCosts().forEach((key, assumption) -> {
            if (markets.contains(key.market())) resolved.put(key, assumption);
        });
        if (overrides != null) {
            for (var entry : overrides.entrySet()) {
                BacktestDto.CostKey key = java.util.Objects.requireNonNull(entry.getKey(), "cost key");
                BacktestDto.CostAssumption assumption =
                        java.util.Objects.requireNonNull(entry.getValue(), "cost assumption");
                RadarBacktestExecution.CostKey executionKey = new RadarBacktestExecution.CostKey(
                        key.market(), RadarBacktestExecution.InstrumentKind.valueOf(
                                java.util.Objects.requireNonNull(key.instrumentKind(), "instrumentKind").name()));
                if (!markets.contains(executionKey.market())) continue;
                resolved.put(executionKey, new RadarBacktestExecution.CostAssumption(
                        assumption.buyFeePct(), assumption.sellFeePct(), assumption.sellTaxPct(),
                        assumption.slippageEachSidePct(), assumption.sourceLabel(),
                        assumption.effectiveFrom(), assumption.effectiveTo()));
            }
        }
        return java.util.Collections.unmodifiableMap(resolved);
    }

    private BacktestDto.CostKey dtoCostKey(RadarBacktestExecution.CostKey key) {
        return new BacktestDto.CostKey(key.market(),
                BacktestDto.InstrumentKind.valueOf(key.instrumentKind().name()));
    }

    private BacktestDto.CostAssumption dtoCost(RadarBacktestExecution.CostAssumption assumption) {
        return new BacktestDto.CostAssumption(
                assumption.buyFeePct(), assumption.sellFeePct(), assumption.sellTaxPct(),
                assumption.slippageEachSidePct(), assumption.sourceLabel(),
                assumption.effectiveFrom(), assumption.effectiveTo());
    }

    private Set<String> normalizeV13Markets(Set<String> requested) {
        if (requested == null || requested.isEmpty()) return Set.of(RadarBacktestExecution.TW_MARKET);
        Set<String> normalized = new LinkedHashSet<>();
        for (String market : requested) {
            if (market == null || market.isBlank()) throw new IllegalArgumentException("market 不可空白");
            String trimmed = market.trim();
            // CostKey constructor 是 market 白名單的單一驗證處。
            new RadarBacktestExecution.CostKey(trimmed, RadarBacktestExecution.InstrumentKind.STOCK);
            normalized.add(trimmed);
        }
        LinkedHashSet<String> ordered = normalized.stream().sorted()
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return java.util.Collections.unmodifiableSet(ordered);
    }

    private List<Integer> normalizeV13Horizons(
            List<Integer> requested, List<Integer> legacyNormalized) {
        List<Integer> values = requested == null || requested.isEmpty()
                ? legacyNormalized : requested;
        if (values == null || values.isEmpty()) values = DEFAULT_HORIZONS;
        for (Integer horizon : values) {
            if (horizon == null || horizon < 1 || horizon > 240) {
                throw new IllegalArgumentException("V13 horizon 必須介於 1..240");
            }
        }
        return values.stream().distinct().sorted().toList();
    }

    private List<String> resolveV13Codes(List<String> requested, String market) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().filter(code -> code != null && !code.isBlank())
                    .map(String::trim)
                    .filter(code -> !(TW_MARKET.equals(market) && TAIEX_CODE.equals(code)))
                    .distinct().sorted().toList();
        }
        List<String> all = priceHistoryRepo.findDistinctStockCodesByMarket(market);
        if (all == null) return List.of();
        return all.stream().filter(code -> code != null && !code.isBlank())
                .filter(code -> !(TW_MARKET.equals(market) && TAIEX_CODE.equals(code)))
                .distinct().sorted().toList();
    }

    private RadarBacktestExecution.InstrumentKind executionInstrumentKind(
            TradingRadarAssetProfileResolver.InstrumentKind kind) {
        if (kind == null || kind == TradingRadarAssetProfileResolver.InstrumentKind.UNKNOWN) return null;
        return RadarBacktestExecution.InstrumentKind.valueOf(kind.name());
    }

    private LocalDate parseV13Date(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " 必須為 YYYY-MM-DD", e);
        }
    }

    // ─────────────────────────── 述詞 ───────────────────────────

    private List<NamedPredicate> buildPredicates(
            Map<String, List<BigDecimal>> thresholds, List<String> composed) {
        Map<String, java.util.function.BiPredicate<Obs, Boolean>> base = basePredicates();
        List<NamedPredicate> out = new ArrayList<>();
        base.forEach((name, test) -> out.add(new NamedPredicate(name, test)));

        if (thresholds != null) {
            thresholds.forEach((key, candidates) -> {
                if (candidates == null) return;
                for (BigDecimal c : candidates) {
                    if (c == null) continue;
                    NamedPredicate p = sweepPredicate(key, c);
                    if (p != null) out.add(p);
                }
            });
        }
        if (composed != null) {
            for (String expr : composed) {
                if (expr == null || expr.isBlank()) continue;
                String[] parts = expr.split("\\+");
                List<java.util.function.BiPredicate<Obs, Boolean>> ps = new ArrayList<>();
                boolean ok = true;
                for (String part : parts) {
                    var t = base.get(part.trim());
                    if (t == null) { ok = false; break; }
                    ps.add(t);
                }
                if (!ok) { log.warn("回測：略過無法解析的組合述詞 {}", expr); continue; }
                out.add(new NamedPredicate(expr.trim(),
                        (o, h) -> ps.stream().allMatch(x -> x.test(o, h))));
            }
        }
        return out;
    }

    /** V11 內建述詞。全部由引擎輸出組成，不在回測端複製動作門檻。 */
    private Map<String, java.util.function.BiPredicate<Obs, Boolean>> basePredicates() {
        Map<String, java.util.function.BiPredicate<Obs, Boolean>> m = new LinkedHashMap<>();
        for (TradingRadarRuleEngine.TimingState s : TradingRadarRuleEngine.TimingState.values()) {
            m.put("TIMING_" + s.name(), (o, h) -> o.timing() == s);
        }
        m.put("PROFIT_TAKING_CONFIRMED", (o, h) -> o.profitTakingConfirmed());
        // Task 356.13b：三軌述詞必須對稱且各讀各自的引擎輸出——SWING_* 一律走 swingAction()／
        // swingScore()，**不得**以 medium 的 action()／score() 冒充。三軌的動作分組在多數日子相同，
        // 拿 medium 頂替不會讓任何既有斷言變紅，卻會讓 356.13b 的稽核量到同一軌兩次。
        m.put("SHORT_BUY", (o, h) -> isBuy(o.shortAction(h)));
        m.put("SWING_BUY", (o, h) -> isBuy(o.swingAction(h)));
        m.put("MEDIUM_BUY", (o, h) -> isBuy(o.action(h)));
        m.put("SHORT_PROFIT_TAKING", (o, h) -> o.profitTakingConfirmed() && isSell(o.shortAction(h)));
        m.put("SWING_PROFIT_TAKING", (o, h) -> o.profitTakingConfirmed() && isSell(o.swingAction(h)));
        m.put("MEDIUM_PROFIT_TAKING", (o, h) -> o.profitTakingConfirmed() && isSell(o.action(h)));
        // 極端超賣保護只統計「原分數本會落入賣出組，但實際被改成中性組」的日子。
        m.put("SHORT_EXTREME_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.shortScore() != null && o.shortScore() < 40
                        && isNeutral(o.shortAction(h)));
        m.put("SWING_EXTREME_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.swingScore() != null && o.swingScore() < 40
                        && isNeutral(o.swingAction(h)));
        m.put("MEDIUM_EXTREME_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.score() != null && o.score() < 40
                        && isNeutral(o.action(h)));
        m.put("LONG_TERM_BROKEN_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.longTermBroken() && isNeutral(o.action(h)));
        m.put("KD_OVERHEATED", (o, h) -> o.kdHeat() == TradingRadarRuleEngine.KdHeat.OVERHEATED);
        m.put("BUY_GATE", (o, h) -> isBuy(o.action(h)));
        m.put("TRIAL_BUY", (o, h) -> o.action(h) == TradingRadarRuleEngine.Action.TRIAL_BUY);
        m.put("SCORE_GTE_75", (o, h) -> o.score() != null && o.score() >= 75);
        m.put("SCORE_55_74", (o, h) -> o.score() != null && o.score() >= 55 && o.score() < 75);
        m.put("SCORE_40_54", (o, h) -> o.score() != null && o.score() >= 40 && o.score() < 55);
        m.put("SCORE_25_39", (o, h) -> o.score() != null && o.score() >= 25 && o.score() < 40);
        m.put("SCORE_LT_25", (o, h) -> o.score() != null && o.score() < 25);
        m.put("SCORE_LT_40", (o, h) -> o.score() != null && o.score() < 40);
        return m;
    }

    private boolean isBuy(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY;
    }

    private boolean isSell(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || action == TradingRadarRuleEngine.Action.EXIT_CANDIDATE
                || action == TradingRadarRuleEngine.Action.AVOID;
    }

    private boolean isNeutral(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.HOLD
                || action == TradingRadarRuleEngine.Action.WATCH
                || action == TradingRadarRuleEngine.Action.HOLD_CAUTION
                || action == TradingRadarRuleEngine.Action.WAIT;
    }

    /**
     * 門檻掃描（273.4b.1）。**刻意在述詞層而非引擎層套用候選值**——引擎的門檻是常數，
     * 掃描要回答的是「若門檻改成 X，哪些日子會成立」，用同一組已算好的底層量即可，
     * 不需要（也不應該）為了掃描去改引擎。
     */
    private NamedPredicate sweepPredicate(String key, BigDecimal c) {
        String suffix = "@" + c.stripTrailingZeros().toPlainString();
        return switch (key) {
            case "biasSigma" -> new NamedPredicate("BIAS_SIGMA_OVER" + suffix, (o, h) -> {
                BigDecimal nb = normalizedBias(o);
                return nb != null && nb.abs().compareTo(c) > 0;
            });
            case "sigmaFloor" -> new NamedPredicate("SIGMA_BELOW_FLOOR" + suffix, (o, h) ->
                    o.sigma() != null && o.sigma().compareTo(c) < 0);
            case "kdOverheat" -> new NamedPredicate("KD_AVG_OVER" + suffix, (o, h) -> {
                BigDecimal avg = kdAvg(o);
                return avg != null && avg.compareTo(c) > 0;
            });
            case "kdOversold" -> new NamedPredicate("KD_AVG_UNDER" + suffix, (o, h) -> {
                BigDecimal avg = kdAvg(o);
                return avg != null && avg.compareTo(c) < 0;
            });
            case "volumeRatio" -> new NamedPredicate("VOLUME_RATIO_OVER" + suffix, (o, h) ->
                    o.volumeRatio() != null && o.volumeRatio().compareTo(c) > 0);
            default -> {
                log.warn("回測：不支援的門檻掃描 key {}", key);
                yield null;
            }
        };
    }

    /** `bias ÷ (σ × 100)`：bias 是百分比、σ 是比例，相除前必須把 σ 乘以 100 才對齊量綱。 */
    private BigDecimal normalizedBias(Obs o) {
        if (o.ma60BiasPercent() == null || o.sigma() == null || o.sigma().signum() <= 0) return null;
        return o.ma60BiasPercent().divide(
                o.sigma().multiply(BigDecimal.valueOf(100)), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal kdAvg(Obs o) {
        if (o.k() == null || o.d() == null) return null;
        return o.k().add(o.d()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────── 統計 ───────────────────────────

    /** 全樣本合併口徑。 */
    private BacktestDto.PredicateStat pooled(
            NamedPredicate p, boolean held, int horizon, List<CodeRun> runs) {
        List<BigDecimal> signal = new ArrayList<>();
        List<BigDecimal> baseline = new ArrayList<>();
        int insufficient = 0;
        boolean anyEtfWithoutPremium = false;   // 只在「是 ETF 且該日無折溢價」時才成立

        for (CodeRun run : runs) {
            for (Obs o : run.observations()) {
                BigDecimal fwd = forwardReturn(run.adjustedCloses(), o.index(), horizon);
                boolean hit = p.test().test(o, held);
                if (fwd == null) {
                    if (hit) insufficient++;
                    continue;
                }
                baseline.add(fwd);
                if (hit) {
                    signal.add(fwd);
                    if (o.etfLike() && !o.etfPremiumAvailable()) anyEtfWithoutPremium = true;
                }
            }
        }

        String caveat = null;
        // 受 ETF 折溢價否決影響的述詞：buyGate（硬否決）、OVERBOUGHT／EXTREME_OVERBOUGHT（參與 timingOf）、
        // 以及 TRIAL_BUY（qualifiesForTrialBuy 同樣檢查溢價）。
        // 注意 "TIMING_EXTREME_OVERBOUGHT".startsWith("TIMING_OVERBOUGHT") 為 false，不可用 startsWith 判。
        // Task 356.13b：SWING_BUY 與另外兩軌同樣經過 actionFor() 的 premiumExpensive 硬否決
        //（三軌共用同一支動作映射，見 TradingRadarRuleEngine.evaluateHorizon），故一併納入。
        boolean premiumSensitive = "BUY_GATE".equals(p.name())
                || "SHORT_BUY".equals(p.name())
                || "SWING_BUY".equals(p.name())
                || "MEDIUM_BUY".equals(p.name())
                || "TRIAL_BUY".equals(p.name())
                || p.name().endsWith("OVERBOUGHT");
        if (anyEtfWithoutPremium && premiumSensitive) {
            caveat = "本次量測未含 ETF 折溢價否決（etf_nav_history 在回測期間結構性缺值），"
                    + "不得直接當成 production 行為的證據。";
        }

        return new BacktestDto.PredicateStat(
                p.name(), held, horizon,
                signal.size(), signal.size() < MIN_SAMPLES, insufficient,
                mean(signal), percentile(signal, 50), ratioAbove(signal, BigDecimal.ZERO),
                ratioAtOrBelow(signal, DOWNSIDE),
                percentile(signal, 5), percentile(signal, 25),
                percentile(signal, 75), percentile(signal, 95),
                baseline.size(), mean(baseline), percentile(baseline, 50),
                ratioAbove(baseline, BigDecimal.ZERO), ratioAtOrBelow(baseline, DOWNSIDE),
                diff(mean(signal), mean(baseline)),
                caveat);
    }

    /** 逐標的配對比較口徑：每檔各自算「訊號組 − 基準」，再對各檔的差額取統計量。 */
    private BacktestDto.PairedStat pairedByCode(
            NamedPredicate p, boolean held, int horizon, List<CodeRun> runs) {
        List<BigDecimal> diffs = new ArrayList<>();
        int positive = 0;
        for (CodeRun run : runs) {
            List<BigDecimal> signal = new ArrayList<>();
            List<BigDecimal> baseline = new ArrayList<>();
            for (Obs o : run.observations()) {
                BigDecimal fwd = forwardReturn(run.adjustedCloses(), o.index(), horizon);
                if (fwd == null) continue;
                baseline.add(fwd);
                if (p.test().test(o, held)) signal.add(fwd);
            }
            if (signal.isEmpty() || baseline.isEmpty()) continue;
            BigDecimal d = diff(mean(signal), mean(baseline));
            if (d == null) continue;
            diffs.add(d);
            if (d.signum() > 0) positive++;
        }
        return new BacktestDto.PairedStat(
                p.name(), held, horizon, diffs.size(),
                mean(diffs), percentile(diffs, 50), positive);
    }

    /**
     * 前瞻報酬 ＝ {@code adjClose[t+h] / adjClose[t] − 1}（%）。
     *
     * <p>{@code t+h} 越界時回 {@code null}（該樣本不計入此 horizon，但仍計入較短的 horizon），
     * <b>不得以最後一日充數</b>。</p>
     */
    private BigDecimal forwardReturn(List<BigDecimal> adjustedCloses, int t, int horizon) {
        int target = t + horizon;
        if (target >= adjustedCloses.size()) return null;
        BigDecimal now = adjustedCloses.get(t);
        BigDecimal later = adjustedCloses.get(target);
        if (now == null || later == null || now.signum() <= 0) return null;
        return later.divide(now, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────── 逐日環境 ───────────────────────────

    /** 大盤 regime 逐日重算，並以該台股日 14:00 截斷美股科技與大盤量能資料。 */
    private Map<LocalDate, TradingRadarRuleEngine.MarketRegime> buildMarketRegimes() {
        List<TwseIndexDailyHistory> asc = twseRepo.findAllByOrderByTradingDateAsc();
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> out = new HashMap<>();
        if (asc == null || asc.isEmpty()) return out;
        List<UsIndexDailyHistory> usRows = new ArrayList<>();
        usRows.addAll(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("IXIC"));
        usRows.addAll(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("SOX"));

        List<StockPriceHistory> asRows = asc.stream()
                .filter(r -> r.getClosePoint() != null && r.getClosePoint().signum() > 0)
                .map(r -> StockPriceHistory.builder()
                        .stockCode(TAIEX_CODE).market(TW_MARKET)
                        .tradingDate(r.getTradingDate())
                        .openPrice(r.getOpenPoint()).highPrice(r.getHighPoint())
                        .lowPrice(r.getLowPoint()).closePrice(r.getClosePoint())
                        // Task 356.13b-2：trade_volume 必須帶上。缺了它回測的台股大盤週量比恆為
                        // null，而 production 由同一欄算得出值——正是 BacktestService 既有註解記載的
                        // 舊病「線上生效、回測不生效，且沒有任何測試抓得到」（Task 323 的標題）。
                        .volume(r.getTradeVolume())
                        .build())
                .toList();

        for (int t = WARMUP; t < asRows.size(); t++) {
            List<StockPriceHistory> windowDesc = new ArrayList<>(
                    asRows.subList(Math.max(0, t - WINDOW + 1), t + 1));
            Collections.reverse(windowDesc);
            // 大盤無除權息事件，events 傳空 list；assemble 會原樣回傳序列。
            RadarInputAssembler.Assembled a = assembler.assemble(
                    windowDesc, List.of(), false,
                    Math.min(windowDesc.size(), DAILY_CONTRACT_ROWS), DAILY_CONTRACT_ROWS,
                    windowDesc.get(0).getClosePrice());
            LocalDate signalDate = asRows.get(t).getTradingDate();
            TradingRadarMarketContextService.MarketContext context =
                    marketContextService.resolveMarketFromRows(signalInstant(signalDate), asc, usRows);
            TradingRadarRuleEngine.MarketResult r = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            windowDesc.get(0).getClosePrice(),
                            a.ruleChangePercent(),
                            assembler.indicators(a.indicators()),
                            a.ma60Confirmation(),
                            a.ma240Confirmation(),
                            context.completedMarketChangePercent(),
                            context.marketVolumeRatio(),
                            context.marketTurnoverRatio(),
                            context.nasdaqChangePercent(),
                            context.soxChangePercent(),
                            context.usTechCompositePercent(),
                            context.usTechAvailable(),
                            // 台股跨市場因子適用（同 production 的 buildMarket()），行為逐位不變。
                            true,
                            // Task 356.13b-2：同 IXIC 那處，週K 與日K 棒一律接上同一份 Assembled。
                            a.dailyCandle(),
                            a.weekly()));
            out.put(signalDate, r.regime());
        }
        return out;
    }

    /** legacy 台股回測的 signal boundary；既有 273 路徑保持台北 14:00。 */
    private java.time.Instant signalInstant(LocalDate date) {
        return date.atTime(LocalTime.of(14, 0)).atZone(TAIPEI).toInstant();
    }

    /**
     * Task 308 V13 的 market-aware signal boundary：台股沿用既有台北 14:00，
     * 美股則在 America/New_York completed close 後 18:00 取證據。日期、基本面、FX
     * 與 market context 均必須由同一個 boundary 決定，避免用台北早上 14:00 讀取美股
     * 尚未收盤或把下一日資料誤當成 signal instant 前可得。
     */
    private java.time.Instant signalInstant(LocalDate date, String market) {
        if (RadarBacktestExecution.US_MARKET.equals(market)) {
            return date.atTime(US_SIGNAL_BOUNDARY).atZone(NEW_YORK).toInstant();
        }
        return signalInstant(date);
    }

    // ─────────────────────────── 資料準備 ───────────────────────────

    private List<String> resolveCodes(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().filter(c -> c != null && !c.isBlank())
                    .map(String::trim).filter(c -> !TAIEX_CODE.equals(c)).distinct().toList();
        }
        List<String> all = priceHistoryRepo.findDistinctStockCodesByMarket(TW_MARKET);
        return all == null ? List.of()
                : all.stream().filter(c -> !TAIEX_CODE.equals(c)).sorted().toList();
    }

    /** 全序列一次還原，供前瞻報酬。與逐 t 視窗的還原只差常數倍率，報酬率不受影響。 */
    private List<BigDecimal> fullAdjustedCloses(
            List<StockPriceHistory> rowsAsc, List<StockDividendHistory> events) {
        List<StockPriceHistory> desc = new ArrayList<>(rowsAsc);
        Collections.reverse(desc);
        // 還原失敗一律往外拋——**不得**靜默改用原始收盤價。用原始價會讓除息缺口變成假跌幅、
        // 月配息債券 ETF 的 240 日報酬被系統性低估，而輸出看起來完全正常（273.5 明訂「不得用原始收盤價」）。
        // 拋出後由 run() 的 per-code catch 丟棄該標的並記入 failedCodes，與逐 t 視窗路徑
        //（RadarInputAssembler 內無 try/catch）的行為一致。
        List<StockPriceHistory> out = adjustedPriceService.adjust(desc, events).rowsDesc();
        List<BigDecimal> asc = new ArrayList<>(out.size());
        for (int i = out.size() - 1; i >= 0; i--) asc.add(out.get(i).getClosePrice());
        return asc;
    }

    private List<StockDividendHistory> eventsWithin(
            List<StockDividendHistory> all, LocalDate from, LocalDate to) {
        if (all == null || all.isEmpty()) return List.of();
        // Task 357／357.3d-1b：改用 anchorDate，否則純配股事件（getExDividendDate()
        // 為 null）在這個獨立於 repository SQL 之外的二次過濾會被排除。
        return all.stream()
                .filter(e -> e.anchorDate() != null
                        && !e.anchorDate().isBefore(from)
                        && !e.anchorDate().isAfter(to))
                .toList();
    }

    private boolean isEtf(TradingRadarAssetProfileResolver.AssetProfile profile) {
        return profile != null
                && (profile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.EQUITY_ETF
                || profile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF);
    }

    /**
     * Load only the append-only observations known by the latest signal boundary.
     * The compatibility daily-current repository is intentionally not consulted.
     */
    private List<EtfNavObservation> loadPremiumObservations(
            String code, String market, LocalDate throughDate) {
        if (etfNavObservationRepository == null || !TW_MARKET.equals(market) || throughDate == null) {
            return List.of();
        }
        try {
            List<EtfNavObservation> rows = etfNavObservationRepository.findObservationStreamThrough(
                    code, market, signalInstant(throughDate, market));
            return rows == null ? List.of() : List.copyOf(rows);
        } catch (RuntimeException e) {
            log.warn("ETF premium observation stream 讀取失敗（{}／{}）：{}", code, market, e.getMessage());
            return List.of();
        }
    }

    private TradingRadarPremiumResolver.DecisionObservation resolvePremiumObservation(
            TradingRadarAssetProfileResolver.AssetProfile profile,
            String market,
            LocalDate targetDate,
            java.time.Instant decisionInstant,
            List<EtfNavObservation> observations) {
        if (!isEtf(profile)) {
            return TradingRadarPremiumResolver.DecisionObservation.missing(
                    TradingRadarPremiumResolver.DecisionStatus.NOT_APPLICABLE,
                    "ETF premium 僅適用 ETF；個股不進正式 factor");
        }
        return TradingRadarPremiumResolver.resolveAsOf(market, targetDate, decisionInstant, observations);
    }

    /** Same as production: percentile is computed only from revisions known at the signal instant. */
    private BigDecimal premiumPercentile(
            String market,
            TradingRadarPremiumResolver.DecisionObservation current,
            java.time.Instant decisionInstant,
            List<EtfNavObservation> observations) {
        if (current == null || !current.available()) return null;
        List<BigDecimal> history = TradingRadarPremiumResolver.premiumHistoryAsOf(
                market, current.asOfDate(), decisionInstant, observations, ETF_PREMIUM_LOOKBACK_DAYS);
        if (history.size() < ETF_PREMIUM_MIN_SAMPLES) return null;
        long atOrBelow = history.stream().filter(value -> value != null
                && value.compareTo(current.value()) <= 0).count();
        return BigDecimal.valueOf(100.0 * atOrBelow / history.size())
                .setScale(1, RoundingMode.HALF_UP);
    }

    private FxSeries fxSeries(String currency, Map<String, FxSeries> cache) {
        return cache.computeIfAbsent(currency, key -> {
            List<ExchangeRateHistory> rows = exchangeRateRepo.findByCurrencyOrderByRateDateAsc(key);
            return new FxSeries(rows == null ? List.of() : List.copyOf(rows), new HashMap<>());
        });
    }

    private TradingRadarMarketContextService.FxContext fxAt(
            String currency, FxSeries series, LocalDate signalDate) {
        return series.resolved().computeIfAbsent(signalDate,
                date -> marketContextService.resolveFxFromRows(currency, signalInstant(date), series.rows()));
    }

    private TradingRadarMarketContextService.FxContext fxAt(
            String currency, FxSeries series, LocalDate signalDate, String market) {
        return series.resolved().computeIfAbsent(signalDate,
                date -> marketContextService.resolveFxFromRows(
                        currency, signalInstant(date, market), series.rows()));
    }

    private BacktestDto.CodeCoverage coverage(
            String code, List<StockPriceHistory> rows, int nonPositive,
            int warmupExcluded, int evaluated, int etfPremiumDays, String instrumentType) {
        return new BacktestDto.CodeCoverage(
                code, TW_MARKET, instrumentType,
                rows.isEmpty() ? null : rows.get(0).getTradingDate().toString(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).getTradingDate().toString(),
                rows.size(), nonPositive, warmupExcluded, evaluated, etfPremiumDays);
    }

    private List<String> notes(List<CodeRun> runs, List<String> failedCodes) {
        int etfPremiumMissing = (int) runs.stream()
                .filter(r -> r.coverage().etfPremiumDays() == 0)
                .filter(r -> r.coverage().code().startsWith("00"))
                .count();
        int dirty = runs.stream().mapToInt(r -> r.coverage().nonPositiveRows()).sum();
        List<String> out = new ArrayList<>(List.of(
                "本輸出為「歷史上此條件成立後的報酬分布」，不是預測。",
                "刻意不輸出 p 值與信賴區間：樣本嚴重自相關（連續多日同一訊號成立），古典檢定前提不成立。",
                "已剔除 close_price <= 0 的髒列共 " + dirty + " 列（既有資料品質問題，另立任務處理）。",
                "有 " + etfPremiumMissing + " 檔 ETF 在整段回測期間完全沒有折溢價資料；"
                        + "這些標的的 buyGate 與 OVERBOUGHT 統計未含折溢價否決，不得直接當成 production 行為的證據。",
                "volumeRatio 與 production 共用 20 日中位數，且已按股票股利／分割的股數因子還原成交量。",
                "n < " + MIN_SAMPLES + " 的格子已標記 sampleInsufficient，其數字不得用於決策。"));
        out.addAll(fundamentalCoverageNotes(runs));
        if (!failedCodes.isEmpty()) {
            out.add("有 " + failedCodes.size() + " 檔標的因讀取或還原失敗而未納入統計："
                    + String.join("、", failedCodes) + "。codeCount 已排除它們。");
        }
        return out;
    }

    /** 明示列出各基本面因子的有效訊號日與標的數，零覆蓋不得被誤解為無效。 */
    private List<String> fundamentalCoverageNotes(List<CodeRun> runs) {
        record Metric(String label, java.util.function.Function<TradingRadarRuleEngine.FundamentalInput, Double> value) {}
        List<Metric> metrics = List.of(
                new Metric("EPS 年增", TradingRadarRuleEngine.FundamentalInput::epsContribution),
                new Metric("近似 ROE", TradingRadarRuleEngine.FundamentalInput::roeContribution),
                new Metric("近三月營收年增", TradingRadarRuleEngine.FundamentalInput::revenueContribution),
                new Metric("PE 自身分位／可信虧損", TradingRadarRuleEngine.FundamentalInput::peContribution),
                new Metric("產業營收年增", TradingRadarRuleEngine.FundamentalInput::industryContribution));
        List<String> notes = new ArrayList<>();
        for (Metric metric : metrics) {
            int days = 0;
            int codes = 0;
            for (CodeRun run : runs) {
                int codeDays = 0;
                for (Obs observation : run.observations()) {
                    TradingRadarRuleEngine.FundamentalInput input = observation.fundamental();
                    if (input != null && input.applicable() && metric.value().apply(input) != null) codeDays++;
                }
                days += codeDays;
                if (codeDays > 0) codes++;
            }
            notes.add("基本面回測覆蓋—" + metric.label() + "：" + days + " 標的日／" + codes
                    + " 標的；0 覆蓋只代表當時尚無 as-of observation，不代表因子有效或無效。");
        }
        return notes;
    }

    // ─────────────────────────── CSV ───────────────────────────

    /**
     * 統計結果的 CSV 形式，供離線分析。欄位與 {@link BacktestDto.PredicateStat} 一一對應。
     *
     * <p>格式產生放在 service：本專案既有慣例一律如此（{@code ExcelExportService}、
     * {@code TradingCalendarExportService}、{@code AlertChartRenderer}），
     * controller 只負責包 {@code ResponseEntity}。</p>
     */
    public String toCsv(BacktestDto.Request request) {
        BacktestDto.Response r = run(request);
        StringBuilder sb = new StringBuilder();
        sb.append("predicate,held,horizon,n,sampleInsufficient,insufficientForward,")
          .append("mean,median,winRate,downsideRisk,p5,p25,p75,p95,")
          .append("baselineN,baselineMean,baselineMedian,baselineWinRate,baselineDownsideRisk,diffMean\n");
        for (BacktestDto.PredicateStat s : r.results()) {
            sb.append(csvCell(s.predicate())).append(',').append(s.held()).append(',').append(s.horizon())
              .append(',').append(s.n()).append(',').append(s.sampleInsufficient())
              .append(',').append(s.insufficientForward())
              .append(',').append(csvNum(s.mean())).append(',').append(csvNum(s.median()))
              .append(',').append(csvNum(s.winRate())).append(',').append(csvNum(s.downsideRisk()))
              .append(',').append(csvNum(s.p5())).append(',').append(csvNum(s.p25()))
              .append(',').append(csvNum(s.p75())).append(',').append(csvNum(s.p95()))
              .append(',').append(s.baselineN()).append(',').append(csvNum(s.baselineMean()))
              .append(',').append(csvNum(s.baselineMedian())).append(',').append(csvNum(s.baselineWinRate()))
              .append(',').append(csvNum(s.baselineDownsideRisk())).append(',').append(csvNum(s.diffMean()))
              .append('\n');
        }
        sb.append('\n')
          .append("coverage,code,market,instrumentType,dataFrom,dataTo,rows,nonPositiveRows,warmupExcluded,evaluated,etfPremiumDays\n");
        for (BacktestDto.CodeCoverage coverage : r.perCode()) {
            sb.append("coverage,").append(csvCell(coverage.code())).append(',')
              .append(csvCell(coverage.market())).append(',').append(csvCell(coverage.instrumentType())).append(',')
              .append(csvCell(coverage.dataFrom())).append(',').append(csvCell(coverage.dataTo())).append(',')
              .append(coverage.rows()).append(',').append(coverage.nonPositiveRows()).append(',')
              .append(coverage.warmupExcluded()).append(',').append(coverage.evaluated()).append(',')
              .append(coverage.etfPremiumDays()).append('\n');
        }
        if (r.v13() != null) {
            sb.append('\n')
              .append("v13_metadata,key,value\n")
              .append("v13_metadata,productionRuleVersion,").append(csvCell(r.v13().productionRuleVersion())).append('\n')
              .append("v13_metadata,productionPromoted,").append(r.v13().productionPromoted()).append('\n')
              .append("v13_metadata,universeMode,").append(r.v13().universeMode()).append('\n')
              .append("v13_metadata,calibrationRatio,").append(csvNum(r.v13().calibrationRatio())).append('\n')
              .append("v13_metadata,walkForwardFolds,").append(r.v13().walkForwardFolds()).append('\n')
              .append("v13_metadata,closeFallbackSensitivityIncluded,")
              .append(r.v13().closeFallbackSensitivityIncluded()).append('\n')
              .append("v13_metadata,candidateParameterSetIds,")
              .append(csvCell(String.join(";", r.v13().candidateParameterSetIds()))).append('\n')
              .append("v13_metadata,promotedCandidateCount,")
              .append(r.v13().promotedCandidateCount()).append('\n');
            sb.append('\n')
              .append("v13_market,horizon,cutoff,globalDateCount,calibrationDateCount,holdoutDateCount,")
              .append("calibrationN,holdoutN,missingEntryOpen,missingExitOpen,insufficientForward,")
              .append("excludedCostOutsideEffectiveRange,closeSensitivityN,promotionStatus,rejectionReason,instrumentKind,productionProfile,")
              .append("assetClass,stockStyle,bondTerm,confidenceDecile,selectedCandidateParameterSetId,calibrationCodeCount,calibrationGrossMeanPct,")
              .append("calibrationNetMeanPct,calibrationNetMedianPct,calibrationNetWinRatePct,calibrationNetDownsideRiskPct,")
              .append("calibrationNetP5Pct,calibrationNetP25Pct,calibrationNetP75Pct,calibrationNetP95Pct,")
              .append("holdoutCodeCount,holdoutGrossMeanPct,holdoutNetMeanPct,holdoutNetMedianPct,")
              .append("holdoutNetWinRatePct,holdoutNetDownsideRiskPct,holdoutNetP5Pct,holdoutNetP25Pct,holdoutNetP75Pct,holdoutNetP95Pct,firstExecutionCode,firstExecutionInstrumentKind,")
              .append("firstExecutionSignalDate,firstExecutionEntryDate,firstExecutionExitDate,firstExecutionEntryAdjustedOpen,")
              .append("firstExecutionExitAdjustedOpen,promotionEvidenceScope\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                BacktestDto.SplitExecutionStat calibration = execution.calibration();
                BacktestDto.SplitExecutionStat holdout = execution.holdout();
                BacktestDto.ExecutionDateExample first = execution.firstPrimaryExecution();
                sb.append(csvCell(execution.market())).append(',').append(execution.horizon()).append(',')
                  .append(csvCell(execution.cutoff())).append(',').append(execution.globalDateCount()).append(',')
                  .append(execution.calibrationDateCount()).append(',').append(execution.holdoutDateCount()).append(',')
                  .append(execution.calibration().n()).append(',').append(execution.holdout().n()).append(',')
                  .append(execution.excludedMissingEntryOpen()).append(',')
                  .append(execution.excludedMissingExitOpen()).append(',')
                  .append(execution.excludedInsufficientForward()).append(',')
                  .append(execution.excludedCostOutsideEffectiveRange()).append(',')
                  .append(execution.closeSensitivityN()).append(',')
                  .append(csvCell(execution.promotionStatus())).append(',')
                  .append(csvCell(execution.rejectionReason())).append(',')
                  .append(csvCell(execution.instrumentKind())).append(',').append(csvCell(execution.productionProfile())).append(',')
                  .append(csvCell(execution.assetClass())).append(',').append(csvCell(execution.stockStyle())).append(',')
                  .append(csvCell(execution.bondTerm())).append(',').append(csvCell(execution.confidenceDecile())).append(',')
                  .append(csvCell(execution.selectedCandidateParameterSetId())).append(',')
                  .append(calibration.codeCount()).append(',').append(csvNum(calibration.grossMeanPct())).append(',')
                  .append(csvNum(calibration.netMeanPct())).append(',').append(csvNum(calibration.netMedianPct())).append(',')
                  .append(csvNum(calibration.netWinRatePct())).append(',').append(csvNum(calibration.netDownsideRiskPct())).append(',')
                  .append(csvNum(calibration.netP5Pct())).append(',').append(csvNum(calibration.netP25Pct())).append(',')
                  .append(csvNum(calibration.netP75Pct())).append(',').append(csvNum(calibration.netP95Pct())).append(',')
                  .append(holdout.codeCount()).append(',').append(csvNum(holdout.grossMeanPct())).append(',')
                  .append(csvNum(holdout.netMeanPct())).append(',').append(csvNum(holdout.netMedianPct())).append(',')
                  .append(csvNum(holdout.netWinRatePct())).append(',').append(csvNum(holdout.netDownsideRiskPct())).append(',')
                  .append(csvNum(holdout.netP5Pct())).append(',').append(csvNum(holdout.netP25Pct())).append(',')
                  .append(csvNum(holdout.netP75Pct())).append(',').append(csvNum(holdout.netP95Pct())).append(',')
                  .append(csvCell(first == null ? null : first.code())).append(',')
                  .append(first == null || first.instrumentKind() == null ? "" : first.instrumentKind()).append(',')
                  .append(csvCell(first == null ? null : first.signalDate())).append(',')
                  .append(csvCell(first == null ? null : first.entryDate())).append(',')
                  .append(csvCell(first == null ? null : first.exitDate())).append(',')
                  .append(csvNum(first == null ? null : first.entryAdjustedOpen())).append(',')
                  .append(csvNum(first == null ? null : first.exitAdjustedOpen())).append(',')
                  .append(csvCell(execution.promotionEvidenceScope())).append('\n');
            }
            sb.append('\n')
              .append("v13_split_distribution,market,horizon,split,side,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope,selectedCandidateParameterSetId,status,reason,")
              .append("attemptN,attemptCodeCount,candidateCoverageN,candidateCoverageCodes,baselineCoverageN,baselineCoverageCodes,")
              .append("intersectionN,intersectionCodes,n,codeCount,grossMeanPct,grossMedianPct,grossWinRatePct,grossDownsideRiskPct,")
              .append("grossP5Pct,grossP25Pct,grossP75Pct,grossP95Pct,netMeanPct,netMedianPct,netWinRatePct,netDownsideRiskPct,")
              .append("netP5Pct,netP25Pct,netP75Pct,netP95Pct,costImpactPct\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                appendSplitDistributionCsv(sb, execution, "calibration", "candidate",
                        execution.calibration(), execution.calibration().candidate());
                appendSplitDistributionCsv(sb, execution, "calibration", "baseline",
                        execution.calibration(), execution.calibration().baseline());
                appendSplitDistributionCsv(sb, execution, "holdout", "candidate",
                        execution.holdout(), execution.holdout().candidate());
                appendSplitDistributionCsv(sb, execution, "holdout", "baseline",
                        execution.holdout(), execution.holdout().baseline());
            }
            sb.append('\n')
              .append("v13_split_delta,market,horizon,split,selectedCandidateParameterSetId,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope,status,reason,")
              .append("attemptN,attemptCodeCount,candidateCoverageN,candidateCoverageCodes,baselineCoverageN,baselineCoverageCodes,")
              .append("intersectionN,intersectionCodes,grossMeanDeltaPct,grossMedianDeltaPct,grossWinRateDeltaPp,grossDownsideRiskDeltaPp,")
              .append("grossP5DeltaPct,grossP25DeltaPct,grossP75DeltaPct,grossP95DeltaPct,netMeanDeltaPct,netMedianDeltaPct,")
              .append("netWinRateDeltaPp,netDownsideRiskDeltaPp,netP5DeltaPct,netP25DeltaPct,netP75DeltaPct,netP95DeltaPct,costImpactDeltaPct\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                appendSplitDeltaCsv(sb, execution, "calibration", execution.calibration());
                appendSplitDeltaCsv(sb, execution, "holdout", execution.holdout());
            }
            sb.append('\n')
              .append("v13_fold,market,horizon,fold,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope,trainFrom,trainTo,trainDateCount,evaluationFrom,evaluationTo,")
              .append("evaluationDateCount,jointTrainDateCount,jointTrainFrom,jointTrainTo,jointRequiredHorizons,selectedCandidateParameterSetId,sigmaProfileStatus,sigmaProfileCutoff,sigmaProfileSource,evidenceStatus,evidenceReason,evidenceSelectedCandidateParameterSetId,")
              .append("candidateCoverageN,candidateCoverageCodes,baselineCoverageN,baselineCoverageCodes,intersectionN,intersectionCodes,")
              .append("candidateN,candidateCodeCount,candidateGrossMeanPct,candidateGrossMedianPct,candidateGrossWinRatePct,candidateGrossDownsideRiskPct,candidateGrossP5Pct,candidateGrossP25Pct,candidateGrossP75Pct,candidateGrossP95Pct,candidateNetMeanPct,candidateNetMedianPct,candidateNetWinRatePct,candidateNetDownsideRiskPct,candidateNetP5Pct,candidateNetP25Pct,candidateNetP75Pct,candidateNetP95Pct,candidateCostImpactPct,")
              .append("baselineN,baselineCodeCount,baselineGrossMeanPct,baselineGrossMedianPct,baselineGrossWinRatePct,baselineGrossDownsideRiskPct,baselineGrossP5Pct,baselineGrossP25Pct,baselineGrossP75Pct,baselineGrossP95Pct,baselineNetMeanPct,baselineNetMedianPct,baselineNetWinRatePct,baselineNetDownsideRiskPct,baselineNetP5Pct,baselineNetP25Pct,baselineNetP75Pct,baselineNetP95Pct,baselineCostImpactPct,")
              .append("deltaGrossMeanPct,deltaGrossMedianPct,deltaGrossWinRatePp,deltaGrossDownsideRiskPp,deltaGrossP5Pct,deltaGrossP25Pct,deltaGrossP75Pct,deltaGrossP95Pct,deltaNetMeanPct,deltaNetMedianPct,deltaNetWinRatePp,deltaNetDownsideRiskPp,deltaNetP5Pct,deltaNetP25Pct,deltaNetP75Pct,deltaNetP95Pct,deltaCostImpactPct,purgedTrainN,purgedTrainCodes,purgeBoundary\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                for (BacktestDto.WalkForwardFold fold : execution.folds()) {
                    sb.append("v13_fold,").append(csvCell(execution.market())).append(',').append(execution.horizon()).append(',')
                      .append(fold.fold()).append(',');
                    appendV13IdentityCsv(sb, execution);
                    sb.append(csvCell(fold.trainFrom())).append(',')
                      .append(csvCell(fold.trainTo())).append(',').append(fold.trainDateCount()).append(',')
                      .append(csvCell(fold.evaluationFrom())).append(',').append(csvCell(fold.evaluationTo())).append(',')
                      .append(fold.evaluationDateCount()).append(',')
                      .append(fold.jointTrainDateCount()).append(',')
                      .append(csvCell(fold.jointTrainFrom() == null
                              ? null : fold.jointTrainFrom().toString())).append(',')
                      .append(csvCell(fold.jointTrainTo() == null
                              ? null : fold.jointTrainTo().toString())).append(',')
                      .append(csvCell(fold.jointRequiredHorizons().stream()
                              .map(String::valueOf).collect(Collectors.joining(";")))).append(',')
                      .append(csvCell(fold.selectedCandidateParameterSetId()));
                    appendFoldExecutionEvidenceCsv(sb, fold.executionEvidence());
                }
            }
            sb.append('\n')
              .append("v13_fold_normalized_bias,market,horizon,fold,code,signalDate,track,enabled,rawBiasRatio,rawSigmaRatio,sigmaFloorRatio,effectiveSigmaRatio,normalizedBias,asOfDate,reason\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                for (BacktestDto.WalkForwardFold fold : execution.folds()) {
                    if (fold.executionEvidence() == null) continue;
                    for (BacktestDto.FoldNormalizedBiasProvenance row
                            : fold.executionEvidence().normalizedBiasRows()) {
                        sb.append("v13_fold_normalized_bias,")
                                .append(csvCell(execution.market())).append(',')
                                .append(execution.horizon()).append(',')
                                .append(fold.fold()).append(',')
                                .append(csvCell(row.code())).append(',')
                                .append(csvCell(row.signalDate())).append(',')
                                .append(csvCell(row.track())).append(',')
                                .append(row.enabled()).append(',')
                                .append(csvNum(row.rawBiasRatio())).append(',')
                                .append(csvNum(row.rawSigmaRatio())).append(',')
                                .append(csvNum(row.sigmaFloorRatio())).append(',')
                                .append(csvNum(row.effectiveSigmaRatio())).append(',')
                                .append(csvNum(row.normalizedBias())).append(',')
                                .append(csvCell(row.asOfDate())).append(',')
                                .append(csvCell(row.reason())).append('\n');
                    }
                }
            }
            sb.append('\n')
              .append("v13_candidate_market,horizon,parameterSetId,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope,ruleVersion,candidateCoverageN,"
                      + "candidateCoverageCodes,baselineCoverageN,baselineCoverageCodes,intersectionN,"
                      + "intersectionCodes,downsideRatePct,intersectionCandidateDownsideRatePct,"
                      + "intersectionBaselineDownsideRatePct,pairedMedianDeltaPct,pooledMeanDeltaPct,selected,"
                      + "entryCandidateN,entryCandidateCodes,entryBaselineN,entryBaselineCodes,"
                      + "entryIntersectionN,entryIntersectionCodes,heldCandidateN,heldCandidateCodes,"
                      + "heldBaselineN,heldBaselineCodes,heldIntersectionN,heldIntersectionCodes\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                for (BacktestDto.CandidateCalibration candidate : execution.candidateCalibration()) {
                    sb.append(csvCell(execution.market())).append(',').append(execution.horizon()).append(',')
                      .append(csvCell(candidate.parameterSetId())).append(',')
                      ;
                    appendV13IdentityCsv(sb, execution);
                    sb.append(csvCell(candidate.ruleVersion())).append(',')
                      .append(candidate.candidateCoverageN()).append(',')
                      .append(candidate.candidateCoverageCodes()).append(',')
                      .append(candidate.baselineCoverageN()).append(',')
                      .append(candidate.baselineCoverageCodes()).append(',')
                      .append(candidate.intersectionN()).append(',')
                      .append(candidate.intersectionCodes()).append(',')
                      .append(csvNum(candidate.downsideRatePct())).append(',')
                      .append(csvNum(candidate.intersectionCandidateDownsideRatePct())).append(',')
                      .append(csvNum(candidate.intersectionBaselineDownsideRatePct())).append(',')
                      .append(csvNum(candidate.pairedMedianDeltaPct())).append(',')
                      .append(csvNum(candidate.pooledMeanDeltaPct())).append(',')
                      .append(candidate.selected()).append(',')
                      .append(candidate.entryCoverage().candidateN()).append(',')
                      .append(candidate.entryCoverage().candidateCodes()).append(',')
                      .append(candidate.entryCoverage().baselineN()).append(',')
                      .append(candidate.entryCoverage().baselineCodes()).append(',')
                      .append(candidate.entryCoverage().intersectionN()).append(',')
                      .append(candidate.entryCoverage().intersectionCodes()).append(',')
                      .append(candidate.heldCoverage().candidateN()).append(',')
                      .append(candidate.heldCoverage().candidateCodes()).append(',')
                      .append(candidate.heldCoverage().baselineN()).append(',')
                      .append(candidate.heldCoverage().baselineCodes()).append(',')
                      .append(candidate.heldCoverage().intersectionN()).append(',')
                      .append(candidate.heldCoverage().intersectionCodes()).append('\n');
                }
            }
            sb.append('\n')
              .append("v13_promotion_market,horizon,parameterSetId,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope,holdoutN,holdoutCodes,"
                      + "pairedMedianDeltaPct,pooledMeanDeltaPct,downsideImprovementPp,"
                      + "returnPracticalDeltaPct,validFolds,passingFolds,catastrophicFold,promoted,reason,"
                      + "intersectionN,intersectionCodes,candidateCoverageN,baselineCoverageN,"
                      + "entryCandidateN,entryCandidateCodes,entryBaselineN,entryBaselineCodes,"
                      + "entryIntersectionN,entryIntersectionCodes,heldCandidateN,heldCandidateCodes,"
                      + "heldBaselineN,heldBaselineCodes,heldIntersectionN,heldIntersectionCodes\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                BacktestDto.PromotionHorizonEvidence evidence = execution.promotionEvidence();
                if (evidence == null) continue;
                sb.append(csvCell(execution.market())).append(',').append(execution.horizon()).append(',')
                  .append(csvCell(evidence.parameterSetId())).append(',')
                  ;
                appendV13IdentityCsv(sb, execution);
                sb.append(evidence.holdoutN()).append(',').append(evidence.holdoutCodes()).append(',')
                  .append(csvNum(evidence.pairedMedianDeltaPct())).append(',')
                  .append(csvNum(evidence.pooledMeanDeltaPct())).append(',')
                  .append(csvNum(evidence.downsideImprovementPp())).append(',')
                  .append(csvNum(evidence.returnPracticalDeltaPct())).append(',')
                  .append(evidence.validFolds()).append(',').append(evidence.passingFolds()).append(',')
                  .append(evidence.catastrophicFold()).append(',').append(evidence.promoted()).append(',')
                  .append(csvCell(evidence.reason())).append(',')
                  .append(evidence.intersectionN()).append(',').append(evidence.intersectionCodes()).append(',')
                  .append(evidence.candidateCoverageN()).append(',').append(evidence.baselineCoverageN()).append(',')
                  .append(evidence.entryCoverage().candidateN()).append(',')
                  .append(evidence.entryCoverage().candidateCodes()).append(',')
                  .append(evidence.entryCoverage().baselineN()).append(',')
                  .append(evidence.entryCoverage().baselineCodes()).append(',')
                  .append(evidence.entryCoverage().intersectionN()).append(',')
                  .append(evidence.entryCoverage().intersectionCodes()).append(',')
                  .append(evidence.heldCoverage().candidateN()).append(',')
                  .append(evidence.heldCoverage().candidateCodes()).append(',')
                  .append(evidence.heldCoverage().baselineN()).append(',')
                  .append(evidence.heldCoverage().baselineCodes()).append(',')
                  .append(evidence.heldCoverage().intersectionN()).append(',')
                  .append(evidence.heldCoverage().intersectionCodes()).append('\n');
            }
            sb.append('\n')
              .append("v13_parameter_snapshot,scope,key,parameterSetId,ruleVersion,normalizedBiasEnabled,shortBuy,shortHold,shortCaution,shortReduce,mediumBuy,mediumHold,mediumCaution,mediumReduce,confidenceThreshold,normalizedBiasFloor,normalizedBiasSaturationMultiple,normalizedBiasUpperMultiple,normalizedBiasLowerMultiple,downsideActionThresholdPct,weakeningRequireStructureBelow,weakeningRequireKdDeadCross,weakeningDownVolumeRatioFloor,candidateWeightDeltas,bondTenorByBondTerm,bondCurveShapeWeight,bondReturnPctAtUnit,sigmaStatus,rawSigmaRatio,effectiveSigmaRatio,sigmaFloorRatio,p05SigmaRatio,p10SigmaRatio,p25SigmaRatio,sigmaSampleN,sigmaCalibrationCutoff,sigmaAsOfFrom,sigmaAsOfTo,sigmaSource\n");
            for (BacktestDto.MarketHorizonExecution execution : r.v13().marketHorizons()) {
                for (BacktestDto.CandidateCalibration candidate : execution.candidateCalibration()) {
                    appendParameterSnapshotCsv(sb, "CANDIDATE", execution.market() + "/h="
                            + execution.horizon() + "/" + candidate.parameterSetId(),
                            candidate.parameterSnapshot());
                }
                appendParameterSnapshotCsv(sb, "SELECTED", execution.market() + "/h="
                        + execution.horizon(), execution.selectedParameterSnapshot());
                for (BacktestDto.WalkForwardFold fold : execution.folds()) {
                    appendParameterSnapshotCsv(sb, "FOLD", execution.market() + "/h="
                            + execution.horizon() + "/fold=" + fold.fold(),
                            fold.executionEvidence() == null ? null
                                    : fold.executionEvidence().parameterSnapshot());
                }
            }
            for (var selected : r.v13().selectedParameterSnapshots().entrySet()) {
                appendParameterSnapshotCsv(sb, "SELECTED_KEY", selected.getKey(), selected.getValue());
            }
            sb.append('\n')
              .append("v13_selected_candidate,groupKey,parameterSetId\n");
            for (var selected : r.v13().selectedCandidates().entrySet()) {
                sb.append("v13_selected_candidate,").append(csvCell(selected.getKey())).append(',')
                  .append(csvCell(selected.getValue())).append('\n');
            }
            sb.append('\n')
              .append("v13_failure,reason\n");
            for (String failure : r.v13().failures()) {
                sb.append("v13_failure,").append(csvCell(failure)).append('\n');
            }
            sb.append('\n')
              .append("v13_note,note\n");
            for (String note : r.v13().notes()) {
                sb.append("v13_note,").append(csvCell(note)).append('\n');
            }
            sb.append('\n')
              .append("v13_cost_market,instrumentKind,buyFeePct,sellFeePct,sellTaxPct,")
              .append("slippageEachSidePct,sourceLabel,effectiveFrom,effectiveTo,roundTripCostPct,")
              .append("returnPracticalDeltaPct\n");
            for (BacktestDto.ResolvedCostAssumption resolved : r.v13().assumptions()) {
                BacktestDto.CostAssumption assumption = resolved.assumption();
                sb.append(csvCell(resolved.key().market())).append(',')
                  .append(resolved.key().instrumentKind()).append(',')
                  .append(csvNum(assumption.buyFeePct())).append(',')
                  .append(csvNum(assumption.sellFeePct())).append(',')
                  .append(csvNum(assumption.sellTaxPct())).append(',')
                  .append(csvNum(assumption.slippageEachSidePct())).append(',')
                  .append(csvCell(assumption.sourceLabel())).append(',')
                  .append(assumption.effectiveFrom() == null ? "" : assumption.effectiveFrom()).append(',')
                  .append(assumption.effectiveTo() == null ? "" : assumption.effectiveTo()).append(',')
                  .append(csvNum(resolved.roundTripCostPct())).append(',')
                  .append(csvNum(resolved.returnPracticalDeltaPct())).append('\n');
            }
        }
        return sb.toString();
    }

    private String csvNum(BigDecimal v) { return v == null ? "" : v.toPlainString(); }

    private void appendParameterSnapshotCsv(
            StringBuilder sb,
            String scope,
            String key,
            BacktestDto.RuleParameterSnapshot snapshot) {
        List<String> fields = new ArrayList<>();
        fields.add("v13_parameter_snapshot");
        fields.add(scope);
        fields.add(key);
        if (snapshot == null) {
            fields.addAll(Collections.nCopies(36, null));
        } else {
            fields.add(snapshot.parameterSetId());
            fields.add(snapshot.ruleVersion());
            fields.add(Boolean.toString(snapshot.normalizedBiasEnabled()));
            fields.add(Integer.toString(snapshot.shortThresholds().buy()));
            fields.add(Integer.toString(snapshot.shortThresholds().hold()));
            fields.add(Integer.toString(snapshot.shortThresholds().caution()));
            fields.add(Integer.toString(snapshot.shortThresholds().reduce()));
            fields.add(Integer.toString(snapshot.mediumThresholds().buy()));
            fields.add(Integer.toString(snapshot.mediumThresholds().hold()));
            fields.add(Integer.toString(snapshot.mediumThresholds().caution()));
            fields.add(Integer.toString(snapshot.mediumThresholds().reduce()));
            fields.add(csvNum(snapshot.confidenceThreshold()));
            fields.add(csvNum(snapshot.normalizedBiasFloor()));
            fields.add(csvNum(snapshot.normalizedBiasSaturationMultiple()));
            fields.add(csvNum(snapshot.normalizedBiasUpperMultiple()));
            fields.add(csvNum(snapshot.normalizedBiasLowerMultiple()));
            fields.add(csvNum(snapshot.downsideActionThresholdPct()));
            fields.add(Boolean.toString(snapshot.weakeningCondition().requireStructureBelow()));
            fields.add(Boolean.toString(snapshot.weakeningCondition().requireKdDeadCross()));
            fields.add(csvNum(snapshot.weakeningCondition().downVolumeRatioFloor()));
            fields.add(String.valueOf(snapshot.candidateWeightDeltas()));
            fields.add(String.valueOf(snapshot.bondRate().tenorByBondTerm()));
            fields.add(csvNum(snapshot.bondRate().curveShapeWeight()));
            fields.add(csvNum(snapshot.bondRate().returnPctAtUnit()));
            BacktestDto.SigmaSnapshot sigma = snapshot.sigma();
            fields.add(sigma == null ? null : sigma.status());
            fields.add(csvNum(sigma == null ? null : sigma.rawSigmaRatio()));
            fields.add(csvNum(sigma == null ? null : sigma.effectiveSigmaRatio()));
            fields.add(csvNum(sigma == null ? null : sigma.floorRatio()));
            fields.add(csvNum(sigma == null ? null : sigma.p05SigmaRatio()));
            fields.add(csvNum(sigma == null ? null : sigma.p10SigmaRatio()));
            fields.add(csvNum(sigma == null ? null : sigma.p25SigmaRatio()));
            fields.add(sigma == null ? null : Integer.toString(sigma.sampleN()));
            fields.add(sigma == null || sigma.calibrationCutoff() == null
                    ? null : sigma.calibrationCutoff().toString());
            fields.add(sigma == null || sigma.asOfFrom() == null ? null : sigma.asOfFrom().toString());
            fields.add(sigma == null || sigma.asOfTo() == null ? null : sigma.asOfTo().toString());
            fields.add(sigma == null ? null : sigma.source());
        }
        sb.append(fields.stream().map(this::csvCell)
                .collect(java.util.stream.Collectors.joining(","))).append('\n');
    }

    /** Append the complete V13 report-group identity and pooled promotion scope. */
    private void appendV13IdentityCsv(
            StringBuilder sb, BacktestDto.MarketHorizonExecution execution) {
        sb.append(csvCell(execution.instrumentKind())).append(',')
          .append(csvCell(execution.productionProfile())).append(',')
          .append(csvCell(execution.assetClass())).append(',')
          .append(csvCell(execution.stockStyle())).append(',')
          .append(csvCell(execution.bondTerm())).append(',')
          .append(csvCell(execution.confidenceDecile())).append(',')
          .append(csvCell(execution.promotionEvidenceScope())).append(',');
    }

    private void appendFoldExecutionEvidenceCsv(
            StringBuilder sb, BacktestDto.FoldExecutionEvidence evidence) {
        List<String> fields = new ArrayList<>();
        if (evidence == null) {
            fields.addAll(Collections.nCopies(70, null));
        } else {
            fields.add(evidence.sigmaProfileStatus());
            fields.add(evidence.sigmaProfileCutoff());
            fields.add(evidence.sigmaProfileSource());
            fields.add(evidence.status());
            fields.add(evidence.reason());
            fields.add(evidence.selectedCandidateParameterSetId());
            fields.add(Integer.toString(evidence.candidateCoverageN()));
            fields.add(Integer.toString(evidence.candidateCoverageCodes()));
            fields.add(Integer.toString(evidence.baselineCoverageN()));
            fields.add(Integer.toString(evidence.baselineCoverageCodes()));
            fields.add(Integer.toString(evidence.intersectionN()));
            fields.add(Integer.toString(evidence.intersectionCodes()));
            appendDistributionFields(fields, evidence.candidate());
            appendDistributionFields(fields, evidence.baseline());
            appendDeltaFields(fields, evidence.delta());
            fields.add(Integer.toString(evidence.purgedTrainN()));
            fields.add(Integer.toString(evidence.purgedTrainCodes()));
            fields.add(evidence.purgeBoundary());
        }
        sb.append(',').append(fields.stream().map(this::csvCell).collect(java.util.stream.Collectors.joining(",")))
          .append('\n');
    }

    private void appendDistributionFields(
            List<String> fields, BacktestDto.ExecutionDistribution distribution) {
        if (distribution == null) {
            fields.addAll(Collections.nCopies(19, null));
            return;
        }
        fields.add(Integer.toString(distribution.n()));
        fields.add(Integer.toString(distribution.codeCount()));
        fields.add(csvNum(distribution.grossMeanPct()));
        fields.add(csvNum(distribution.grossMedianPct()));
        fields.add(csvNum(distribution.grossWinRatePct()));
        fields.add(csvNum(distribution.grossDownsideRiskPct()));
        fields.add(csvNum(distribution.grossP5Pct()));
        fields.add(csvNum(distribution.grossP25Pct()));
        fields.add(csvNum(distribution.grossP75Pct()));
        fields.add(csvNum(distribution.grossP95Pct()));
        fields.add(csvNum(distribution.netMeanPct()));
        fields.add(csvNum(distribution.netMedianPct()));
        fields.add(csvNum(distribution.netWinRatePct()));
        fields.add(csvNum(distribution.netDownsideRiskPct()));
        fields.add(csvNum(distribution.netP5Pct()));
        fields.add(csvNum(distribution.netP25Pct()));
        fields.add(csvNum(distribution.netP75Pct()));
        fields.add(csvNum(distribution.netP95Pct()));
        fields.add(csvNum(distribution.costImpactPct()));
    }

    private void appendDeltaFields(List<String> fields, BacktestDto.ExecutionDelta delta) {
        if (delta == null) {
            fields.addAll(Collections.nCopies(17, null));
            return;
        }
        fields.add(csvNum(delta.grossMeanDeltaPct()));
        fields.add(csvNum(delta.grossMedianDeltaPct()));
        fields.add(csvNum(delta.grossWinRateDeltaPp()));
        fields.add(csvNum(delta.grossDownsideRiskDeltaPp()));
        fields.add(csvNum(delta.grossP5DeltaPct()));
        fields.add(csvNum(delta.grossP25DeltaPct()));
        fields.add(csvNum(delta.grossP75DeltaPct()));
        fields.add(csvNum(delta.grossP95DeltaPct()));
        fields.add(csvNum(delta.netMeanDeltaPct()));
        fields.add(csvNum(delta.netMedianDeltaPct()));
        fields.add(csvNum(delta.netWinRateDeltaPp()));
        fields.add(csvNum(delta.netDownsideRiskDeltaPp()));
        fields.add(csvNum(delta.netP5DeltaPct()));
        fields.add(csvNum(delta.netP25DeltaPct()));
        fields.add(csvNum(delta.netP75DeltaPct()));
        fields.add(csvNum(delta.netP95DeltaPct()));
        fields.add(csvNum(delta.costImpactDeltaPct()));
    }

    private void appendSplitDistributionCsv(
            StringBuilder sb,
            BacktestDto.MarketHorizonExecution execution,
            String split,
            String side,
            BacktestDto.SplitExecutionStat stat,
            BacktestDto.ExecutionDistribution distribution) {
        sb.append("v13_split_distribution,")
          .append(csvCell(execution.market())).append(',').append(execution.horizon()).append(',')
          .append(split).append(',').append(side).append(',');
        appendV13IdentityCsv(sb, execution);
        sb.append(csvCell(stat.selectedCandidateParameterSetId())).append(',')
          .append(csvCell(stat.status())).append(',').append(csvCell(stat.reason())).append(',')
          .append(stat.attemptN()).append(',').append(stat.attemptCodeCount()).append(',')
          .append(stat.candidateCoverageN()).append(',').append(stat.candidateCoverageCodes()).append(',')
          .append(stat.baselineCoverageN()).append(',').append(stat.baselineCoverageCodes()).append(',')
          .append(stat.intersectionN()).append(',').append(stat.intersectionCodes()).append(',');
        if (distribution == null) {
            sb.append(",,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,\n");
            return;
        }
        sb.append(distribution.n()).append(',').append(distribution.codeCount()).append(',')
          .append(csvNum(distribution.grossMeanPct())).append(',')
          .append(csvNum(distribution.grossMedianPct())).append(',')
          .append(csvNum(distribution.grossWinRatePct())).append(',')
          .append(csvNum(distribution.grossDownsideRiskPct())).append(',')
          .append(csvNum(distribution.grossP5Pct())).append(',')
          .append(csvNum(distribution.grossP25Pct())).append(',')
          .append(csvNum(distribution.grossP75Pct())).append(',')
          .append(csvNum(distribution.grossP95Pct())).append(',')
          .append(csvNum(distribution.netMeanPct())).append(',')
          .append(csvNum(distribution.netMedianPct())).append(',')
          .append(csvNum(distribution.netWinRatePct())).append(',')
          .append(csvNum(distribution.netDownsideRiskPct())).append(',')
          .append(csvNum(distribution.netP5Pct())).append(',')
          .append(csvNum(distribution.netP25Pct())).append(',')
          .append(csvNum(distribution.netP75Pct())).append(',')
          .append(csvNum(distribution.netP95Pct())).append(',')
          .append(csvNum(distribution.costImpactPct())).append('\n');
    }

    private void appendSplitDeltaCsv(
            StringBuilder sb,
            BacktestDto.MarketHorizonExecution execution,
            String split,
            BacktestDto.SplitExecutionStat stat) {
        BacktestDto.ExecutionDelta delta = stat.delta();
        sb.append("v13_split_delta,")
          .append(csvCell(execution.market())).append(',').append(execution.horizon()).append(',')
          .append(split).append(',').append(csvCell(stat.selectedCandidateParameterSetId())).append(',');
        appendV13IdentityCsv(sb, execution);
        sb.append(csvCell(stat.status())).append(',').append(csvCell(stat.reason())).append(',')
          .append(stat.attemptN()).append(',').append(stat.attemptCodeCount()).append(',')
          .append(stat.candidateCoverageN()).append(',').append(stat.candidateCoverageCodes()).append(',')
          .append(stat.baselineCoverageN()).append(',').append(stat.baselineCoverageCodes()).append(',')
          .append(stat.intersectionN()).append(',').append(stat.intersectionCodes()).append(',');
        if (delta == null) {
            sb.append(",,,,,,,,,,,,,,,,,\n");
            return;
        }
        sb.append(csvNum(delta.grossMeanDeltaPct())).append(',')
          .append(csvNum(delta.grossMedianDeltaPct())).append(',')
          .append(csvNum(delta.grossWinRateDeltaPp())).append(',')
          .append(csvNum(delta.grossDownsideRiskDeltaPp())).append(',')
          .append(csvNum(delta.grossP5DeltaPct())).append(',')
          .append(csvNum(delta.grossP25DeltaPct())).append(',')
          .append(csvNum(delta.grossP75DeltaPct())).append(',')
          .append(csvNum(delta.grossP95DeltaPct())).append(',')
          .append(csvNum(delta.netMeanDeltaPct())).append(',')
          .append(csvNum(delta.netMedianDeltaPct())).append(',')
          .append(csvNum(delta.netWinRateDeltaPp())).append(',')
          .append(csvNum(delta.netDownsideRiskDeltaPp())).append(',')
          .append(csvNum(delta.netP5DeltaPct())).append(',')
          .append(csvNum(delta.netP25DeltaPct())).append(',')
          .append(csvNum(delta.netP75DeltaPct())).append(',')
          .append(csvNum(delta.netP95DeltaPct())).append(',')
          .append(csvNum(delta.costImpactDeltaPct())).append('\n');
    }

    private String csvCell(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    // ─────────────────────────── 統計小工具 ───────────────────────────

    private BigDecimal mean(List<BigDecimal> xs) {
        if (xs == null || xs.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal x : xs) sum = sum.add(x);
        return sum.divide(BigDecimal.valueOf(xs.size()), 4, RoundingMode.HALF_UP);
    }

    /**
     * Paired-by-code statistic required by t308.  A stock with many signal dates must not
     * dominate the median simply because it contributes more rows: first average each code's
     * same-intersection deltas, then take the median across code means.
     */
    private BigDecimal pairedMedianDelta(Map<String, List<BigDecimal>> deltasByCode) {
        if (deltasByCode == null || deltasByCode.isEmpty()) return null;
        List<BigDecimal> codeMeans = deltasByCode.values().stream()
                .map(this::mean)
                .filter(Objects::nonNull)
                .toList();
        return percentile(codeMeans, 50);
    }

    private BigDecimal percentile(List<BigDecimal> xs, int p) {
        if (xs == null || xs.isEmpty()) return null;
        List<BigDecimal> s = new ArrayList<>(xs);
        Collections.sort(s);
        double idx = (p / 100.0) * (s.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return s.get(lo).setScale(4, RoundingMode.HALF_UP);
        BigDecimal w = BigDecimal.valueOf(idx - lo);
        return s.get(lo).add(s.get(hi).subtract(s.get(lo)).multiply(w))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal ratioAbove(List<BigDecimal> xs, BigDecimal bound) {
        if (xs == null || xs.isEmpty()) return null;
        long c = xs.stream().filter(x -> x.compareTo(bound) > 0).count();
        return BigDecimal.valueOf(100.0 * c / xs.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ratioAtOrBelow(List<BigDecimal> xs, BigDecimal bound) {
        if (xs == null || xs.isEmpty()) return null;
        long c = xs.stream().filter(x -> x.compareTo(bound) <= 0).count();
        return BigDecimal.valueOf(100.0 * c / xs.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal diff(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            log.warn("回測：無法解析日期 {}，忽略該界限", s);
            return null;
        }
    }
}
