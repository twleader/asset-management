package com.steven.assets.service;

import com.steven.assets.dto.BacktestDto;
import com.steven.assets.model.EtfNavObservation;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.EtfNavObservationRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 273 回測框架的守門測試。
 *
 * <p>本檔的重點不是「統計算得對不對」，而是<b>三件會靜默出錯的事</b>：
 * 前視偏誤、回測與 production 的組裝漂移、以及暖機／越界樣本的處理。
 * 這三者都不會讓程式報錯，只會讓結果變好看。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestServiceTest {

    private static final String TW = "台股";
    private static final String CODE = "TEST1";
    private static final String ETF_CODE = "0050";
    private static final LocalDate START = LocalDate.of(2016, 1, 4);
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @Mock private StockPriceHistoryRepository priceHistoryRepo;
    @Mock private StockDividendHistoryRepository dividendHistoryRepo;
    @Mock private TwseIndexDailyHistoryRepository twseRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexRepo;
    @Mock private ExchangeRateHistoryRepository exchangeRateRepo;
    @Mock private EtfNavHistoryRepository etfNavHistoryRepo;
    @Mock private EtfNavObservationRepository etfNavObservationRepo;
    @Mock private StockRepository stockRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TradingRadarMarketContextService marketContextService;
    @Mock private FundamentalAnalysisService fundamentalAnalysisService;

    private final TradingRadarRuleEngine engine = new TradingRadarRuleEngine();
    private final DistributionAdjustedPriceService adjust = new DistributionAdjustedPriceService();

    private RadarInputAssembler assembler() {
        // 第 4 參數為 Task 294 新增的 usIndexDailyHistoryRepo；重用既有的 usIndexRepo mock
        // （本檔已為 BacktestService 的建構子注入它），本測試不涉及 IXIC 故不額外 stub。
        return new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQuery, twseRepo, usIndexRepo), adjust, engine);
    }

    private BacktestService service() {
        return new BacktestService(
                engine, assembler(), new AssetClassifier(),
                priceHistoryRepo, dividendHistoryRepo, twseRepo,
                usIndexRepo, exchangeRateRepo, etfNavHistoryRepo, stockRepo, adjust, marketContextService,
                fundamentalAnalysisService);
    }

    private TradingRadarRuleEngine.StockResult actionResult(TradingRadarRuleEngine.Action action) {
        TradingRadarRuleEngine.CounterTrendResult counterTrend =
                new TradingRadarRuleEngine.CounterTrendResult(
                        TradingRadarRuleEngine.CounterTrendState.NONE, List.of(), List.of());
        return new TradingRadarRuleEngine.StockResult(
                50, action, counterTrend, List.of(), List.of(),
                TradingRadarRuleEngine.KdHeat.NORMAL,
                TradingRadarRuleEngine.TimingState.NEUTRAL,
                false, false, 50, action, List.of(), List.of(), false, false);
    }

    // ─────────────────────────── 測試資料 ───────────────────────────

    /** 產生 n 筆升序序列；第 i 筆收盤 = base × (1 + drift)^i，另可注入一段大漲。 */
    private List<StockPriceHistory> series(int n, double base, double drift, int rallyFrom, double rallyPct) {
        List<StockPriceHistory> out = new ArrayList<>(n);
        double px = base;
        LocalDate d = START;
        for (int i = 0; i < n; i++) {
            px = i >= rallyFrom ? px * (1 + rallyPct) : px * (1 + drift);
            BigDecimal c = BigDecimal.valueOf(px).setScale(4, java.math.RoundingMode.HALF_UP);
            out.add(StockPriceHistory.builder()
                    .stockCode(CODE).market(TW).tradingDate(d)
                    .openPrice(c).highPrice(c.multiply(BigDecimal.valueOf(1.01)))
                    .lowPrice(c.multiply(BigDecimal.valueOf(0.99))).closePrice(c)
                    .volume(1_000_000L + i)
                    .build());
            d = d.plusDays(1);
        }
        return out;
    }

    private List<StockPriceHistory> desc(List<StockPriceHistory> asc, int fromIdx, int toIdxInclusive) {
        List<StockPriceHistory> w = new ArrayList<>(asc.subList(fromIdx, toIdxInclusive + 1));
        Collections.reverse(w);
        return w;
    }

    private List<StockPriceHistory> withCode(List<StockPriceHistory> rows, String code) {
        return rows.stream().map(row -> StockPriceHistory.builder()
                .stockCode(code).market(TW).tradingDate(row.getTradingDate())
                .openPrice(row.getOpenPrice()).highPrice(row.getHighPrice())
                .lowPrice(row.getLowPrice()).closePrice(row.getClosePrice())
                .volume(row.getVolume()).build()).toList();
    }

    private StockDividendHistory cashDividend(LocalDate exDate, double cash) {
        StockDividendHistory e = new StockDividendHistory();
        e.setStockCode(CODE);
        e.setMarket(TW);
        e.setExDividendDate(exDate);
        e.setCashDividend(BigDecimal.valueOf(cash));
        e.setYear(exDate.getYear());
        return e;
    }

    private void stubRepos(List<StockPriceHistory> asc, List<StockDividendHistory> events) {
        when(priceHistoryRepo.findDistinctStockCodesByMarket(TW)).thenReturn(List.of(CODE));
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(CODE, TW)).thenReturn(asc);
        when(dividendHistoryRepo.findAdjustmentEvents(anyString(), anyString(), any(), any()))
                .thenReturn(events);
        when(twseRepo.findAllByOrderByTradingDateAsc()).thenReturn(List.of());
        when(etfNavHistoryRepo.findByStockCodeAndMarketOrderByNavDateAsc(CODE, TW)).thenReturn(List.of());
        when(stockRepo.findByCodeAndMarket(CODE, TW)).thenReturn(java.util.Optional.empty());
        when(fundamentalAnalysisService.resolveInputsForBacktest(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    List<java.time.Instant> instants = invocation.getArgument(2);
                    Map<java.time.Instant, TradingRadarRuleEngine.FundamentalInput> result =
                            new java.util.LinkedHashMap<>();
                    instants.forEach(instant -> result.put(
                            instant, TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE));
                    return result;
                });
    }

    @Test
    @DisplayName("基本面回測每檔只呼叫一次批次 resolver，不逐日查詢")
    void fundamentalsAreResolvedOncePerCode() {
        List<StockPriceHistory> asc = series(280, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        service().run(new BacktestDto.Request(List.of(CODE), null, null, List.of(5), null, null));

        verify(fundamentalAnalysisService).resolveInputsForBacktest(eq(CODE), eq(TW), any());
        verify(fundamentalAnalysisService, never()).resolve(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("回測 ETF premium 只接受 signal instant 前已知的 append-only NAV；同日 18:30 才可用在 18:00 仍排除")
    void backtestPremiumUsesObservationAvailabilityBoundary() {
        List<StockPriceHistory> rows = withCode(series(270, 100, 0.001, 999, 0.0), ETF_CODE);
        LocalDate signalDate = rows.get(RadarInputAssembler.FULL_WINDOW).getTradingDate();
        Instant observedAt = signalDate.atTime(12, 0).atZone(TAIPEI).toInstant();
        Instant availableAt = signalDate.atTime(18, 30).atZone(TAIPEI).toInstant();
        EtfNavObservation lateSameDay = EtfNavObservation.builder()
                .stockCode(ETF_CODE).market(TW).navDate(signalDate)
                .nav(new BigDecimal("100")).premiumDiscountPct(new BigDecimal("2.5"))
                .source("TWSE").observedAt(observedAt).availableAt(availableAt)
                .availabilityBasis("SOURCE_PUBLISHED_AT").build();

        when(priceHistoryRepo.findDistinctStockCodesByMarket(TW)).thenReturn(List.of(ETF_CODE));
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(ETF_CODE, TW))
                .thenReturn(rows);
        when(dividendHistoryRepo.findAdjustmentEvents(anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        when(twseRepo.findAllByOrderByTradingDateAsc()).thenReturn(List.of());
        when(usIndexRepo.findByIndexCodeOrderByTradingDateAsc(anyString())).thenReturn(List.of());
        when(stockRepo.findByCodeAndMarket(ETF_CODE, TW)).thenReturn(java.util.Optional.empty());
        when(etfNavObservationRepo.findObservationStreamThrough(eq(ETF_CODE), eq(TW), any()))
                .thenReturn(List.of(lateSameDay));
        when(fundamentalAnalysisService.resolveInputsForBacktest(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    List<Instant> instants = invocation.getArgument(2);
                    Map<Instant, TradingRadarRuleEngine.FundamentalInput> result = new java.util.LinkedHashMap<>();
                    instants.forEach(instant -> result.put(instant,
                            TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE));
                    return result;
                });

        TradingRadarRuleEngine candidateEngine = org.mockito.Mockito.spy(new TradingRadarRuleEngine());
        RadarInputAssembler candidateAssembler = new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQuery, twseRepo, usIndexRepo),
                adjust, candidateEngine);
        BacktestService candidateService = new BacktestService(
                candidateEngine, candidateAssembler, new AssetClassifier(),
                priceHistoryRepo, dividendHistoryRepo, twseRepo, usIndexRepo, exchangeRateRepo,
                etfNavHistoryRepo, stockRepo, adjust, marketContextService, fundamentalAnalysisService,
                null, null, null, null, etfNavObservationRepo);

        candidateService.run(new BacktestDto.Request(List.of(ETF_CODE), null, null, List.of(1), null, null));

        org.mockito.ArgumentCaptor<TradingRadarRuleEngine.StockInput> inputs =
                org.mockito.ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(candidateEngine, org.mockito.Mockito.atLeastOnce()).evaluateStock(inputs.capture());
        assertThat(inputs.getAllValues()).allSatisfy(input ->
                assertThat(input.etfPremiumPct()).as("18:30 available observation must not enter earlier signal")
                        .isNull());

        Instant signalAt1800 = signalDate.atTime(18, 0).atZone(TAIPEI).toInstant();
        TradingRadarPremiumResolver.DecisionObservation excluded = TradingRadarPremiumResolver.resolveAsOf(
                TW, signalDate, signalAt1800, List.of(lateSameDay));
        assertThat(excluded.status()).isEqualTo(TradingRadarPremiumResolver.DecisionStatus.MISSING);
        verify(etfNavObservationRepo).findObservationStreamThrough(
                eq(ETF_CODE), eq(TW), any(Instant.class));
        verify(etfNavHistoryRepo, never()).findByStockCodeAndMarketOrderByNavDateAsc(anyString(), anyString());
    }

    @Test
    @DisplayName("V13 request 真的逐訊號呼叫完整 candidate engine，而非只替既有訊號換標籤")
    void v13RequestEvaluatesCandidateGridThroughRuleEngine() {
        List<StockPriceHistory> asc = series(270, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());
        TradingRadarRuleEngine candidateEngine = spy(new TradingRadarRuleEngine());
        RadarInputAssembler candidateAssembler = new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQuery, twseRepo, usIndexRepo),
                adjust, candidateEngine);
        BacktestService candidateService = new BacktestService(
                candidateEngine, candidateAssembler, new AssetClassifier(),
                priceHistoryRepo, dividendHistoryRepo, twseRepo, usIndexRepo, exchangeRateRepo,
                etfNavHistoryRepo, stockRepo, adjust, marketContextService, fundamentalAnalysisService);

        candidateService.run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false));

        verify(candidateEngine, atLeastOnce()).evaluateCandidate(any(), any(), any());
        verify(candidateEngine, atLeastOnce()).evaluateBaseline(any(), any());
    }

    @Test
    @DisplayName("V13 歷史市場 feature 以每訊號 terminal session 呼叫 batch port，且不逐訊號 load")
    void v13HistoricalMarketFeaturesUseStrictPerInstantBatchPort() {
        List<StockPriceHistory> asc = series(270, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());
        AtomicInteger batchCalls = new AtomicInteger();
        AtomicInteger rangeLoads = new AtomicInteger();
        AtomicReference<TradingRadarMarketFeatureResolver.ExpectedSessions> firstExpected =
                new AtomicReference<>();
        TradingRadarMarketFeaturePort marketFeaturePort = new TradingRadarMarketFeaturePort() {
            @Override
            public TradingRadarMarketFeatureResolver.Sources load(
                    String market, Instant decisionInstant) {
                throw new AssertionError("V13 historical path must use resolveBatch/loadRange");
            }

            @Override
            public TradingRadarMarketFeatureResolver.Sources loadRange(
                    String market, Instant earliestDecision, Instant latestDecision) {
                rangeLoads.incrementAndGet();
                return TradingRadarMarketFeatureResolver.Sources.empty();
            }

            @Override
            public Map<Instant, TradingRadarMarketFeatureResolver.Evidence> resolveBatch(
                    String market,
                    List<Instant> decisionInstants,
                    java.util.function.Function<Instant,
                            TradingRadarMarketFeatureResolver.ExpectedSessions> expectedSessions) {
                batchCalls.incrementAndGet();
                if (decisionInstants != null && !decisionInstants.isEmpty()) {
                    firstExpected.set(expectedSessions.apply(decisionInstants.getFirst()));
                }
                return TradingRadarMarketFeaturePort.super.resolveBatch(
                        market, decisionInstants, expectedSessions);
            }
        };
        BacktestService candidateService = new BacktestService(
                engine, assembler(), new AssetClassifier(),
                priceHistoryRepo, dividendHistoryRepo, twseRepo, usIndexRepo, exchangeRateRepo,
                etfNavHistoryRepo, stockRepo, adjust, marketContextService, fundamentalAnalysisService,
                null, null, null, marketFeaturePort, null);

        candidateService.run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false));

        assertThat(batchCalls).hasValue(1);
        assertThat(rangeLoads).hasValue(1);
        assertThat(firstExpected).hasValueSatisfying(expected -> {
            assertThat(expected.strict()).isTrue();
            assertThat(expected.marketSession()).isNotNull();
            assertThat(expected.usSession()).isNotNull();
            assertThat(expected.commoditySession()).isEqualTo(expected.usSession());
        });
    }

    @Test
    @DisplayName("V13 CSV 與 JSON 同步揭露 metadata、coverage、fold、failure、selected candidate 與 assumptions")
    void v13CsvContainsJsonParitySections() {
        List<StockPriceHistory> asc = series(270, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        String csv = service().toCsv(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false));

        assertThat(csv).contains("coverage,code,market,instrumentType,dataFrom,dataTo")
                .contains("v13_metadata,key,value")
                .contains("v13_fold,market,horizon,fold,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope")
                .contains("assetClass,stockStyle,bondTerm,confidenceDecile")
                .contains("promotionEvidenceScope")
                .contains("calibrationNetP5Pct,calibrationNetP25Pct,calibrationNetP75Pct,calibrationNetP95Pct")
                .contains("v13_split_distribution,market,horizon,split,side,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope")
                .contains("candidateCoverageN,candidateCoverageCodes,baselineCoverageN,baselineCoverageCodes")
                .contains("v13_split_delta,market,horizon,split,selectedCandidateParameterSetId,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope")
                .contains("costImpactDeltaPct")
                .contains("v13_candidate_market,horizon,parameterSetId,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope")
                .contains("v13_promotion_market,horizon,parameterSetId,instrumentKind,productionProfile,assetClass,stockStyle,bondTerm,confidenceDecile,promotionEvidenceScope")
                .contains("v13_parameter_snapshot,scope,key,parameterSetId,ruleVersion,normalizedBiasEnabled,shortBuy")
                .contains("normalizedBiasFloor,normalizedBiasSaturationMultiple,normalizedBiasUpperMultiple,normalizedBiasLowerMultiple")
                .contains("p05SigmaRatio,p10SigmaRatio,p25SigmaRatio,sigmaSampleN,sigmaCalibrationCutoff")
                .contains("v13_selected_candidate,groupKey,parameterSetId")
                .contains("v13_failure,reason")
                .contains("v13_cost_market,instrumentKind");
    }

    @Test
    @DisplayName("V13 缺 sigma calibration 時停用 normalized candidates 且不得 promotion")
    void v13WithoutSigmaCalibrationFailsClosed() {
        stubRepos(List.of(), List.of());

        BacktestDto.V13Report report = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false)).v13();

        assertThat(report).isNotNull();
        assertThat(report.candidateParameterSetIds())
                .doesNotContain("V13_P05_BASE", "V13_P10_BASE", "V13_P25_BASE");
        assertThat(report.promotedCandidateCount()).isZero();
        assertThat(report.notes()).anyMatch(note -> note.contains("DISABLED_NO_CALIBRATION_PROFILE"));
    }

    @Test
    @DisplayName("V13 同鍵比較排除雙無 action，單邊 action 與持有部位減碼使用實際 next-open 報酬")
    void v13IntersectionUsesSingleSideActionAndHeldAvoidedLoss() {
        // 下跌序列讓「持有部位 REDUCE」的 avoided-loss 報酬為正；它不能被當成 short P&L。
        List<StockPriceHistory> descending = series(270, 100, -0.001, 999, 0.0);
        stubRepos(descending, List.of());
        TradingRadarRuleEngine candidateEngine = spy(new TradingRadarRuleEngine());
        RadarInputAssembler candidateAssembler = new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQuery, twseRepo, usIndexRepo),
                adjust, candidateEngine);
        TradingRadarRuleEngine.StockResult noAction = actionResult(
                TradingRadarRuleEngine.Action.NO_TRADE);
        TradingRadarRuleEngine.StockResult baselineHeld = actionResult(
                TradingRadarRuleEngine.Action.HOLD);
        doReturn(noAction).when(candidateEngine).evaluateStock(any());
        doAnswer(invocation -> {
            TradingRadarRuleEngine.StockInput input = invocation.getArgument(0);
            return input.held() ? baselineHeld : noAction;
        }).when(candidateEngine).evaluateBaseline(any(), any());
        doAnswer(invocation -> {
            TradingRadarRuleEngine.StockInput input = invocation.getArgument(0);
            RuleParameters parameters = invocation.getArgument(1);
            if ("V13_BASELINE".equals(parameters.parameterSetId())) {
                return actionResult(input.held()
                        ? TradingRadarRuleEngine.Action.NO_TRADE
                        : TradingRadarRuleEngine.Action.BUY_CANDIDATE);
            }
            return actionResult(input.held()
                    ? TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                    : TradingRadarRuleEngine.Action.NO_TRADE);
        }).when(candidateEngine).evaluateCandidate(any(), any(), any());

        BacktestService candidateService = new BacktestService(
                candidateEngine, candidateAssembler, new AssetClassifier(),
                priceHistoryRepo, dividendHistoryRepo, twseRepo, usIndexRepo, exchangeRateRepo,
                etfNavHistoryRepo, stockRepo, adjust, marketContextService, fundamentalAnalysisService);
        BacktestDto.Response response = candidateService.run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false));

        BacktestDto.MarketHorizonExecution report = response.v13().marketHorizons().stream()
                .filter(row -> TW.equals(row.market()) && row.horizon() == 1)
                .findFirst().orElseThrow();
        BacktestDto.CandidateCalibration selective = report.candidateCalibration().stream()
                .filter(row -> row.parameterSetId().startsWith("V13_"))
                .filter(row -> row.parameterSetId().contains("SELECTIVE"))
                .findFirst().orElseThrow();
        assertThat(selective.candidateCoverageN()).isEqualTo(17);
        assertThat(selective.baselineCoverageN()).isEqualTo(17);
        assertThat(selective.intersectionN()).isEqualTo(17);
        assertThat(selective.intersectionCodes()).isEqualTo(1);
        assertThat(selective.entryCoverage().candidateN()).isZero();
        assertThat(selective.entryCoverage().baselineN()).isZero();
        assertThat(selective.entryCoverage().intersectionN()).isZero();
        assertThat(selective.heldCoverage().candidateN()).isEqualTo(17);
        assertThat(selective.heldCoverage().baselineN()).isEqualTo(17);
        assertThat(selective.heldCoverage().intersectionN()).isEqualTo(17);
        assertThat(selective.pooledMeanDeltaPct())
                .as("held REDUCE 在下跌樣本應呈現避免損失，而不是 short P&L")
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(response.v13().selectedCandidates().values())
                .anyMatch(id -> id.contains("SELECTIVE"));
        assertThat(report.calibration().status()).isEqualTo("AVAILABLE");
        assertThat(report.calibration().candidate()).isNotNull();
        assertThat(report.calibration().baseline()).isNotNull();
        assertThat(report.calibration().candidate().n())
                .isEqualTo(report.calibration().baseline().n())
                .isEqualTo(report.calibration().intersectionN());
        assertThat(report.calibration().candidate().grossMeanPct()).isNotNull();
        assertThat(report.calibration().candidate().grossMeanPct())
                .as("REDUCE/EXIT has no held exposure, so gross must be zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.calibration().candidate().grossMedianPct()).isNotNull();
        assertThat(report.calibration().candidate().grossP5Pct()).isNotNull();
        assertThat(report.calibration().candidate().netMeanPct()).isNotNull();
        assertThat(report.calibration().candidate().netP95Pct()).isNotNull();
        assertThat(report.calibration().baseline().grossMeanPct()).isNotNull();
        assertThat(report.calibration().baseline().grossMeanPct()).isNegative();
        assertThat(report.calibration().baseline().netDownsideRiskPct()).isNotNull();
        assertThat(report.calibration().candidate().costImpactPct()).isNotNull();
        assertThat(report.calibration().baseline().costImpactPct()).isNotNull();
        assertThat(report.calibration().delta()).isNotNull();
        assertThat(report.calibration().delta().grossMeanDeltaPct()).isNotNull();
        assertThat(report.calibration().delta().netMeanDeltaPct()).isNotNull();
        assertThat(report.calibration().delta().costImpactDeltaPct()).isNotNull();

        // 同一組 action 改用上漲樣本，entry BUY 對 baseline free WATCH 的差額
        // 必須使用 entry 報酬；baseline held HOLD 不得再被加進同一 key。
        List<StockPriceHistory> ascending = series(270, 100, 0.01, 999, 0.0);
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(CODE, TW))
                .thenReturn(ascending);
        BacktestDto.Response entryResponse = candidateService.run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false));
        BacktestDto.CandidateCalibration baselineCandidate = entryResponse.v13().marketHorizons().stream()
                .filter(row -> TW.equals(row.market()) && row.horizon() == 1)
                .findFirst().orElseThrow().candidateCalibration().stream()
                .filter(row -> "V13_BASELINE".equals(row.parameterSetId()))
                .findFirst().orElseThrow();
        assertThat(baselineCandidate.entryCoverage().candidateN()).isEqualTo(17);
        assertThat(baselineCandidate.entryCoverage().baselineN()).isZero();
        assertThat(baselineCandidate.entryCoverage().intersectionN()).isEqualTo(17);
        assertThat(baselineCandidate.heldCoverage().baselineN()).isEqualTo(17);
        assertThat(baselineCandidate.baselineCoverageN()).isZero();
        assertThat(baselineCandidate.pooledMeanDeltaPct())
                .as("free BUY 對 baseline free WATCH 的差額應為 entry 報酬，不得混入 held HOLD")
                .isGreaterThan(BigDecimal.ZERO);

        // Flat prices are the cost-symmetry invariant: a candidate REDUCE/EXIT must not
        // appear superior merely because the old held path charged baseline a fictional
        // buy fee while assigning the sell action a free zero return.
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(CODE, TW))
                .thenReturn(series(270, 100, 0.0, 999, 0.0));
        BacktestDto.CandidateCalibration flatSelective = candidateService.run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, false))
                .v13().marketHorizons().stream()
                .filter(row -> TW.equals(row.market()) && row.horizon() == 1)
                .findFirst().orElseThrow().candidateCalibration().stream()
                .filter(row -> row.parameterSetId().contains("SELECTIVE"))
                .findFirst().orElseThrow();
        assertThat(flatSelective.pooledMeanDeltaPct())
                .as("flat existing holding: REDUCE must not gain a fictional buy-cost advantage")
                .isCloseTo(BigDecimal.ZERO, within(new BigDecimal("0.0002")));
    }

    // ─────────────────────────── (a) 前視偏誤 ───────────────────────────

    @Test
    @DisplayName("(a) 前視偏誤：t 日的判定與「把 t 之後整段刪除後重算」完全相同")
    void lookAhead_dayTIdenticalWhenFutureRemoved() {
        int t = 260;
        // t 之後連續大漲：若實作誤用全期最高價當 52 週高點，t 日的 week52Position 會被壓低。
        List<StockPriceHistory> full = series(320, 100, 0.001, t + 1, 0.08);
        List<StockPriceHistory> truncated = full.subList(0, t + 1);
        List<StockDividendHistory> events = List.of(cashDividend(full.get(t - 30).getTradingDate(), 2.0));

        RadarInputAssembler a = assembler();
        var fromFull = a.assemble(desc(full, t - 240, t), events, false, 241,
                full.get(t).getClosePrice());
        var fromTruncated = a.assemble(desc(truncated, t - 240, t), events, false, 241,
                truncated.get(t).getClosePrice());

        assertThat(fromFull.week52Position()).isEqualTo(fromTruncated.week52Position());
        assertThat(fromFull.ma60BiasPercent()).isEqualTo(fromTruncated.ma60BiasPercent());
        assertThat(fromFull.ma240BiasPercent()).isEqualTo(fromTruncated.ma240BiasPercent());
        assertThat(fromFull.kdBandWidthPercent()).isEqualTo(fromTruncated.kdBandWidthPercent());
        assertThat(fromFull.indicators().k()).isEqualTo(fromTruncated.indicators().k());
        assertThat(fromFull.indicators().d()).isEqualTo(fromTruncated.indicators().d());
        assertThat(fromFull.ma20Confirmation()).isEqualTo(fromTruncated.ma20Confirmation());
        assertThat(fromFull.ma60Confirmation()).isEqualTo(fromTruncated.ma60Confirmation());
        assertThat(fromFull.ma240Confirmation()).isEqualTo(fromTruncated.ma240Confirmation());
    }

    @Test
    @DisplayName("(a2) 52 週視窗：高低只取自 [t-239, t]，視窗外的暴衝不得影響")
    void week52_usesWindowOnly() {
        int t = 300;
        List<StockPriceHistory> asc = series(320, 100, 0.0, 999, 0.0);
        // 在視窗「之外」（t-260）插入一個極高價：若實作掃全序列，week52High 會被它污染。
        StockPriceHistory spike = asc.get(t - 260);
        asc.set(t - 260, StockPriceHistory.builder()
                .stockCode(CODE).market(TW).tradingDate(spike.getTradingDate())
                .openPrice(spike.getOpenPrice())
                .highPrice(BigDecimal.valueOf(99999))
                .lowPrice(spike.getLowPrice()).closePrice(spike.getClosePrice())
                .volume(spike.getVolume()).build());

        var a = assembler().assemble(desc(asc, t - 240, t), List.of(), false, 241,
                asc.get(t).getClosePrice());

        assertThat(a.week52High()).isNotNull();
        assertThat(a.week52High()).isLessThan(BigDecimal.valueOf(1000));
    }

    // ─────────────────────────── (b)(c) 還原權息 ───────────────────────────

    @Test
    @DisplayName("(b) 無事件時還原序列與原始序列逐筆相同")
    void adjustment_noEventsKeepsSeriesIdentical() {
        List<StockPriceHistory> asc = series(300, 50, 0.0005, 999, 0.0);
        var a = assembler().assemble(desc(asc, 59, 299), List.of(), false, 241,
                asc.get(299).getClosePrice());
        assertThat(a.distributionAdjusted()).isFalse();
        List<StockPriceHistory> expected = desc(asc, 59, 299);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(a.adjustedRowsDesc().get(i).getClosePrice())
                    .isEqualByComparingTo(expected.get(i).getClosePrice());
        }
    }

    @Test
    @DisplayName("(b) 跨越除息日的前瞻報酬不因除息缺口而變負")
    void adjustment_forwardReturnAcrossExDateNotNegative() {
        // 市價完全不動，但除息當日原始價下跌 2 元 → 未還原會算出負報酬。
        List<StockPriceHistory> asc = new ArrayList<>();
        LocalDate d = START;
        for (int i = 0; i < 300; i++) {
            double px = i < 150 ? 100.0 : 98.0;   // 除息缺口
            BigDecimal c = BigDecimal.valueOf(px);
            asc.add(StockPriceHistory.builder().stockCode(CODE).market(TW).tradingDate(d)
                    .openPrice(c).highPrice(c).lowPrice(c).closePrice(c).volume(1000L).build());
            d = d.plusDays(1);
        }
        List<StockDividendHistory> events = List.of(cashDividend(asc.get(150).getTradingDate(), 2.0));

        var adjusted = adjust.adjust(desc(asc, 0, 299), events);
        List<BigDecimal> ascAdj = new ArrayList<>();
        for (int i = adjusted.rowsDesc().size() - 1; i >= 0; i--) {
            ascAdj.add(adjusted.rowsDesc().get(i).getClosePrice());
        }
        BigDecimal before = ascAdj.get(145);
        BigDecimal after = ascAdj.get(155);
        assertThat(after.subtract(before).doubleValue())
                .as("還原後跨除息日的報酬不應為負")
                .isGreaterThanOrEqualTo(-1e-6);
    }

    // ───────────────── (g) 回測與 production 取同一個視窗 ─────────────────

    @Test
    @DisplayName("(g) 回測在 t=最新日切出的視窗，與 production 的 findRecentN(241) 逐筆相同")
    void window_matchesProductionRecentN() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        int t = asc.size() - 1;

        // production：findRecentN(code, market, 241) → 降序最近 241 筆
        List<StockPriceHistory> production = new ArrayList<>(asc.subList(asc.size() - 241, asc.size()));
        Collections.reverse(production);

        // 回測：subList(max(0, t-240), t+1) 反轉
        List<StockPriceHistory> backtest = desc(asc, Math.max(0, t - 240), t);

        assertThat(backtest).hasSize(241);
        assertThat(backtest).containsExactlyElementsOf(production);

        // 同一個視窗 → 同一支 assembler → 逐欄相同（守住 273.2b 的「不得複製組裝」）
        RadarInputAssembler a = assembler();
        var p = a.assemble(production, List.of(), false, 241, production.get(0).getClosePrice());
        var b = a.assemble(backtest, List.of(), false, 241, backtest.get(0).getClosePrice());
        assertThat(b.indicators()).isEqualTo(p.indicators());
        assertThat(b.ma60BiasPercent()).isEqualTo(p.ma60BiasPercent());
        assertThat(b.week52Position()).isEqualTo(p.week52Position());
        assertThat(b.kdBandWidthPercent()).isEqualTo(p.kdBandWidthPercent());
        assertThat(b.ma240Confirmation()).isEqualTo(p.ma240Confirmation());
    }

    // ─────────────────────────── (d)(e)(f) 服務層 ───────────────────────────

    @Test
    @DisplayName("(e) 暖機期：前 240 筆一律排除，且被排除天數有輸出")
    void warmup_excludedAndReported() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5), null, null));

        assertThat(r.perCode()).hasSize(1);
        BacktestDto.CodeCoverage c = r.perCode().get(0);
        assertThat(c.warmupExcluded()).isEqualTo(240);
        assertThat(c.evaluated()).isEqualTo(400 - 240);
        assertThat(c.rows()).isEqualTo(400);
    }

    @Test
    @DisplayName("(d) t+h 越界：長 horizon 的樣本較少，且計入 insufficientForward")
    void forwardReturn_outOfRangeCountedSeparately() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5, 60), null, null));

        var h5 = r.results().stream()
                .filter(s -> s.predicate().equals("SCORE_GTE_75") && s.horizon() == 5 && s.held()).findFirst();
        var h60 = r.results().stream()
                .filter(s -> s.predicate().equals("SCORE_GTE_75") && s.horizon() == 60 && s.held()).findFirst();
        assertThat(h5).isPresent();
        assertThat(h60).isPresent();
        // 越界只影響較長的 horizon：h60 的基準樣本數必然少於 h5。
        assertThat(h60.get().baselineN()).isLessThan(h5.get().baselineN());
    }

    @Test
    @DisplayName("(f) 訊號組是基準組的子集：任一格的 n 不得大於 baselineN")
    void signalIsSubsetOfBaseline() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5, 20), null, null));

        assertThat(r.results()).isNotEmpty();
        for (BacktestDto.PredicateStat s : r.results()) {
            assertThat(s.n())
                    .as("述詞 %s／held=%s／h=%d 的訊號數不得超過基準數", s.predicate(), s.held(), s.horizon())
                    .isLessThanOrEqualTo(s.baselineN());
        }
    }

    @Test
    @DisplayName("(h) held=true 與 held=false 兩組分開統計，且動作述詞確實不同")
    void heldSplitProducesDistinctActionPredicates() {
        List<StockPriceHistory> asc = series(400, 100, 0.002, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5), null, null));

        long heldRows = r.results().stream().filter(BacktestDto.PredicateStat::held).count();
        long freeRows = r.results().stream().filter(s -> !s.held()).count();
        assertThat(heldRows).isPositive();
        assertThat(heldRows).isEqualTo(freeRows);
    }

    @Test
    @DisplayName("門檻掃描：候選值各自產生一條述詞（273.4b.1）")
    void thresholdSweepProducesOnePredicatePerCandidate() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5),
                java.util.Map.of("kdOverheat", List.of(BigDecimal.valueOf(70), BigDecimal.valueOf(80))),
                null));

        assertThat(r.results().stream().map(BacktestDto.PredicateStat::predicate).distinct())
                .contains("KD_AVG_OVER@70", "KD_AVG_OVER@80");
    }

    @Test
    @DisplayName("組合述詞：基礎條件 ∧ 附加條件（273.4b.2）")
    void composedPredicateIsAccepted() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5), null,
                List.of("TIMING_EXTREME_OVERSOLD+SCORE_LT_40")));

        assertThat(r.results().stream().map(BacktestDto.PredicateStat::predicate).distinct())
                .contains("TIMING_EXTREME_OVERSOLD+SCORE_LT_40");
    }

    @Test
    @DisplayName("輸出不得含預測性語句，且必須揭露樣本不足與缺值（273.6.4／273.8.1）")
    void outputDisciplineIsHonoured() {
        List<StockPriceHistory> asc = series(400, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response r = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(5), null, null));

        // 「不是預測」這句本身含「預測」二字，故不能用整串比對；改為禁止**預測性宣稱**的用語。
        String notes = String.join("\n", r.notes());
        assertThat(notes)
                .doesNotContain("將會").doesNotContain("機率為").doesNotContain("能降低風險")
                .contains("不是預測");
        assertThat(r.results().stream().anyMatch(BacktestDto.PredicateStat::sampleInsufficient))
                .as("應有樣本不足的格子被標記")
                .isTrue();
    }

    // ─────────────────────────── (i) 回歸 ───────────────────────────

    @Test
    @DisplayName("Task 292 回測與 production 共用 TW_RULES_V12")
    void ruleVersionIsV12() {
        assertThat(TradingRadarRuleEngine.RULE_VERSION).isEqualTo("TW_RULES_V12");
    }

    @Test
    @DisplayName("Task 308 legacy 六欄 Request 維持原回應且不暗中產生 V13")
    void legacyRequestRemainsDescriptiveOnly() {
        List<StockPriceHistory> asc = series(270, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response response = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null));

        assertThat(response.v13()).isNull();
        assertThat(response.ruleVersion()).isEqualTo("TW_RULES_V12");
        assertThat(response.results()).isNotEmpty();
    }

    @Test
    @DisplayName("Task 308 V13 request 產生真實 next-open 70/30 與 walk-forward 報告但維持 V12")
    void v13RequestBuildsTradableGlobalSplitAndExplicitRejection() {
        List<StockPriceHistory> asc = series(270, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response response = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), 3, null, true));

        assertThat(response.v13()).isNotNull();
        BacktestDto.V13Report v13 = response.v13();
        assertThat(v13.productionRuleVersion()).isEqualTo("TW_RULES_V12");
        assertThat(v13.productionPromoted()).isFalse();
        assertThat(v13.assumptions()).hasSize(3);
        assertThat(v13.closeFallbackSensitivityIncluded()).isTrue();
        assertThat(v13.candidateParameterSetIds())
                .containsExactly("V12_DEFAULT", "V13_BASELINE", "V13_SELECTIVE",
                        "V13_SHORT_BASELINE_MEDIUM_SELECTIVE",
                        "V13_SHORT_SELECTIVE_MEDIUM_BASELINE",
                        "V13_P05_BASE", "V13_P05_SAT_LOW",
                        "V13_P05_SAT_HIGH", "V13_P05_UPPER_LOW",
                        "V13_P05_UPPER_HIGH", "V13_P05_LOWER_LOW",
                        "V13_P05_LOWER_HIGH", "V13_P05_WEAKEN_LOW",
                        "V13_P05_WEAKEN_HIGH", "V13_P10_BASE",
                        "V13_P10_SAT_LOW", "V13_P10_SAT_HIGH",
                        "V13_P10_UPPER_LOW", "V13_P10_UPPER_HIGH",
                        "V13_P10_LOWER_LOW", "V13_P10_LOWER_HIGH",
                        "V13_P10_WEAKEN_LOW", "V13_P10_WEAKEN_HIGH",
                        "V13_P25_BASE", "V13_P25_SAT_LOW",
                        "V13_P25_SAT_HIGH", "V13_P25_UPPER_LOW",
                        "V13_P25_UPPER_HIGH", "V13_P25_LOWER_LOW",
                        "V13_P25_LOWER_HIGH", "V13_P25_WEAKEN_LOW",
                        "V13_P25_WEAKEN_HIGH", "V13_EVIDENCE_DOWNSIDE",
                        "V13_TREASURY_BOND_M3_Y10_Y30_LEVEL",
                        "V13_TREASURY_BOND_Y5_Y5_Y10_SHAPE25");
        BacktestDto.RuleParameterSnapshot shapeCandidate = v13.marketHorizons().stream()
                .flatMap(report -> report.candidateCalibration().stream())
                .filter(candidate -> "V13_TREASURY_BOND_Y5_Y5_Y10_SHAPE25"
                        .equals(candidate.parameterSetId()))
                .map(BacktestDto.CandidateCalibration::parameterSnapshot)
                .findFirst()
                .orElseThrow();
        assertThat(shapeCandidate.bondRate().tenorByBondTerm())
                .containsEntry("SHORT", "M3")
                .containsEntry("MID", "Y5")
                .containsEntry("LONG", "Y10");
        assertThat(shapeCandidate.bondRate().curveShapeWeight())
                .isEqualByComparingTo("0.25");
        assertThat(v13.selectedCandidates()).isEmpty();

        assertThat(v13.marketHorizons()).singleElement().satisfies(report -> {
            assertThat(report.market()).isEqualTo(TW);
            assertThat(report.horizon()).isEqualTo(1);
            // signal-date cutoff 仍為 floor(28*0.70)=19；其中最後兩筆 exit 已跨入
            // holdout block，依 purge/embargo 規則不進 calibration 統計。
            assertThat(report.globalDateCount()).isEqualTo(28);
            assertThat(report.calibrationDateCount()).isEqualTo(19);
            assertThat(report.holdoutDateCount()).isEqualTo(9);
            assertThat(report.calibration().n()).isEqualTo(17);
            assertThat(report.purgeEmbargoEvidence().calibrationPurgedN()).isEqualTo(2);
            assertThat(report.holdout().n()).isEqualTo(9);
            assertThat(report.calibration().status()).isEqualTo("INSUFFICIENT");
            assertThat(report.calibration().reason()).isEqualTo("NO_SELECTED_CANDIDATE");
            assertThat(report.calibration().candidate()).isNull();
            assertThat(report.calibration().baseline()).isNull();
            assertThat(report.calibration().delta()).isNull();
            assertThat(report.holdout().status()).isEqualTo("INSUFFICIENT");
            assertThat(report.holdout().reason()).isEqualTo("NO_SELECTED_CANDIDATE");
            assertThat(report.holdout().candidate()).isNull();
            assertThat(report.holdout().baseline()).isNull();
            assertThat(report.excludedInsufficientForward()).isEqualTo(2);
            assertThat(report.closeSensitivityN()).isEqualTo(28);
            assertThat(report.folds()).hasSize(3);
            assertThat(report.folds()).allSatisfy(fold ->
                    assertThat(LocalDate.parse(fold.trainTo()))
                            .isBefore(LocalDate.parse(fold.evaluationFrom())));
            assertThat(report.folds()).allSatisfy(fold ->
                    assertThat(fold.executionEvidence().sigmaProfileStatus())
                            .isEqualTo("FOLD_SIGMA_PROFILE_ASOF_TRAIN"));
            assertThat(report.firstPrimaryExecution().signalDate())
                    .isEqualTo(asc.get(240).getTradingDate().toString());
            assertThat(report.firstPrimaryExecution().entryDate())
                    .isEqualTo(asc.get(241).getTradingDate().toString());
            assertThat(report.firstPrimaryExecution().exitDate())
                    .isEqualTo(asc.get(242).getTradingDate().toString());
            assertThat(report.promotionStatus()).isEqualTo("INSUFFICIENT_RETAIN_V12");
            assertThat(report.rejectionReason())
                    .isEqualTo("INSUFFICIENT_CALIBRATION_INTERSECTION");
            assertThat(report.candidateCalibration()).hasSize(34)
                    .allSatisfy(candidate -> assertThat(candidate.ruleVersion())
                            .isEqualTo(RuleParameters.V13_VERSION));
            assertThat(report.candidateCalibration()).allSatisfy(candidate -> {
                assertThat(candidate.parameterSnapshot()).isNotNull();
                assertThat(candidate.parameterSnapshot().parameterSetId())
                        .isEqualTo(candidate.parameterSetId());
                assertThat(candidate.parameterSnapshot().shortThresholds()).isNotNull();
                assertThat(candidate.parameterSnapshot().mediumThresholds()).isNotNull();
                assertThat(candidate.parameterSnapshot().sigma().status())
                        .isEqualTo("CALIBRATED_PROFILE");
                assertThat(candidate.parameterSnapshot().sigma().rawSigmaRatio()).isNull();
                assertThat(candidate.parameterSnapshot().sigma().effectiveSigmaRatio()).isNull();
            });
            assertThat(report.candidateCalibration())
                    .allSatisfy(candidate -> assertThat(candidate.intersectionN())
                            .as("雙方都沒有 action 的 key 不應偽造 calibration intersection")
                            .isZero());
        });
    }

    @Test
    @DisplayName("Task 308 default 五 folds 每一折都以 train boundary 建立 sigma profile，不跳過前三折")
    void defaultWalkForwardUsesTrainLocalSigmaForEveryFold() {
        List<StockPriceHistory> asc = series(270, 100, 0.001, 999, 0.0);
        stubRepos(asc, List.of());

        BacktestDto.Response response = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW), new BigDecimal("0.70"), null, null, false));

        BacktestDto.MarketHorizonExecution report = response.v13().marketHorizons().stream()
                .filter(row -> TW.equals(row.market()) && row.horizon() == 1)
                .findFirst().orElseThrow();
        assertThat(report.folds()).hasSize(5);
        assertThat(report.folds()).allSatisfy(fold -> {
            assertThat(fold.executionEvidence().sigmaProfileStatus())
                    .isEqualTo("FOLD_SIGMA_PROFILE_ASOF_TRAIN");
            assertThat(fold.executionEvidence().sigmaProfileCutoff())
                    .isEqualTo(fold.trainTo());
        });
        assertThat(response.v13().failures())
                .noneMatch(reason -> reason.contains("FOLD_SIGMA_PROFILE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("Task 308 台美市場各自建立全域 cutoff，不混用另一市場日曆")
    void v13MarketsHaveIndependentGlobalCalendars() {
        List<StockPriceHistory> tw = series(270, 100, 0.001, 999, 0.0);
        List<StockPriceHistory> us = tw.stream().map(row -> StockPriceHistory.builder()
                .stockCode(row.getStockCode()).market("美股").tradingDate(row.getTradingDate().plusDays(10))
                .openPrice(row.getOpenPrice()).highPrice(row.getHighPrice())
                .lowPrice(row.getLowPrice()).closePrice(row.getClosePrice()).volume(row.getVolume()).build())
                .toList();
        stubRepos(tw, List.of());
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(CODE, "美股"))
                .thenReturn(us);
        when(stockRepo.findByCodeAndMarket(CODE, "美股")).thenReturn(java.util.Optional.empty());

        BacktestDto.Response response = service().run(new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                Set.of(TW, "美股"), new BigDecimal("0.70"), 3, null, false));

        assertThat(response.v13().marketHorizons()).hasSize(2);
        var twReport = response.v13().marketHorizons().stream()
                .filter(report -> TW.equals(report.market())).findFirst().orElseThrow();
        var usReport = response.v13().marketHorizons().stream()
                .filter(report -> "美股".equals(report.market())).findFirst().orElseThrow();
        assertThat(LocalDate.parse(usReport.cutoff()))
                .isEqualTo(LocalDate.parse(twReport.cutoff()).plusDays(10));
        assertThat(twReport.globalDateCount()).isEqualTo(usReport.globalDateCount());
    }

    @Test
    @DisplayName("V13 calibration sigma 會完整涵蓋每一市場/代碼，且 request 市場順序不改變結果")
    void v13CalibrationIsDeterministicAcrossMarketOrderAndPreservesAllGroups() {
        List<StockPriceHistory> tw = series(270, 100, 0.001, 999, 0.0);
        List<StockPriceHistory> us = tw.stream().map(row -> StockPriceHistory.builder()
                .stockCode(row.getStockCode()).market("美股").tradingDate(row.getTradingDate().plusDays(10))
                .openPrice(row.getOpenPrice()).highPrice(row.getHighPrice())
                .lowPrice(row.getLowPrice()).closePrice(row.getClosePrice()).volume(row.getVolume()).build())
                .toList();
        stubRepos(tw, List.of());
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(CODE, "美股"))
                .thenReturn(us);
        when(stockRepo.findByCodeAndMarket(CODE, "美股")).thenReturn(java.util.Optional.empty());

        Set<String> twThenUs = new LinkedHashSet<>(List.of(TW, "美股"));
        Set<String> usThenTw = new LinkedHashSet<>(List.of("美股", TW));
        BacktestDto.Request requestA = new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                twThenUs, new BigDecimal("0.70"), 3, null, false);
        BacktestDto.Request requestB = new BacktestDto.Request(
                List.of(CODE), null, null, List.of(1), null, null,
                usThenTw, new BigDecimal("0.70"), 3, null, false);

        BacktestDto.V13Report reportA = service().run(requestA).v13();
        BacktestDto.V13Report reportB = service().run(requestB).v13();

        assertThat(reportA.marketHorizons()).extracting(
                        BacktestDto.MarketHorizonExecution::market,
                        BacktestDto.MarketHorizonExecution::horizon)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TW, 1),
                        org.assertj.core.groups.Tuple.tuple("美股", 1));
        assertThat(reportB.marketHorizons()).extracting(
                        BacktestDto.MarketHorizonExecution::market,
                        BacktestDto.MarketHorizonExecution::horizon)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TW, 1),
                        org.assertj.core.groups.Tuple.tuple("美股", 1));
        assertThat(reportA.candidateParameterSetIds())
                .containsExactlyElementsOf(reportB.candidateParameterSetIds());
        assertThat(reportA.notes()).containsExactlyElementsOf(reportB.notes());
        assertThat(reportA.marketHorizons()).extracting(
                        execution -> execution.market() + "/" + execution.horizon()
                                + "/" + execution.globalDateCount() + "/" + execution.calibrationDateCount())
                .containsExactlyElementsOf(reportB.marketHorizons().stream()
                        .map(execution -> execution.market() + "/" + execution.horizon()
                                + "/" + execution.globalDateCount() + "/" + execution.calibrationDateCount())
                        .toList());
    }

    @Test
    @DisplayName("NO_TRADE 早退分支的兩個新欄位一律為 false（不得是「偶然的 false」）")
    void noTradeEarlyExitReportsFalseForBothFlags() {
        TradingRadarRuleEngine.StockResult r = engine.evaluateStock(
                new TradingRadarRuleEngine.StockInput(
                        true, null, null, null,
                        new TradingRadarRuleEngine.Indicators(null, null, null, null, null),
                        null, null,
                        TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                        TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                        TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                        TradingRadarRuleEngine.InstrumentType.EQUITY,
                        TradingRadarRuleEngine.MarketRegime.NEUTRAL,
                        false, null, null, null, null, null, null, null));

        assertThat(r.action()).isEqualTo(TradingRadarRuleEngine.Action.NO_TRADE);
        assertThat(r.kdDeadCross()).isFalse();
        assertThat(r.longTermBroken()).isFalse();
    }
}
