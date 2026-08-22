package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 356.10b／356.10c／356.10d：台股大盤與美股大盤<b>各自</b>聚合自己的週K 並餵進
 * {@code MarketInput}，且各自在 {@code MarketSummary} 揭露自己那一份 {@code weeklyIndicators}。
 *
 * <p><b>本檔的核心守門是「長清單有沒有真的被拿去聚合」。</b>兩條路徑各自把序列釘回 241 列的
 * 日K 契約（{@code buildMarket} 甚至有<b>兩處</b> {@code limit}），241 列 ≈ 48 個 ISO 週 &lt;
 * {@link RadarInputAssembler#MIN_COMPLETED_WEEKS}(60)。若把契約列（而不是 500 列長清單）餵給
 * {@code marketWeekly}，四組大盤週K 因子會<b>永遠缺值</b>——畫面只多一則「不採計」風險句、
 * 沒有任何錯誤訊息，也不會有任何既有測試變紅。</p>
 *
 * <p>{@link RadarInputAssembler} 刻意用<b>真實物件</b>（底層 repository 才是 mock）：本檔要證明的
 * 是週K 真的算得出來，用 {@code @Mock} 的 assembler 只會回 null，斷言恆真。</p>
 */
class TradingRadarMarketWeeklyWiringTest {

    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";

    private final TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
    private final UsIndexDailyHistoryRepository usIndexRepo = mock(UsIndexDailyHistoryRepository.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final TechnicalIndicatorService indicatorService = mock(TechnicalIndicatorService.class);
    private final TaiexDisplayPriceService taiexDisplay = mock(TaiexDisplayPriceService.class);
    private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
    private final StockPriceHistoryRepository priceHistoryRepo = mock(StockPriceHistoryRepository.class);

    private final TradingRadarMarketContextService contextService = new TradingRadarMarketContextService(
            twseRepo, usIndexRepo, mock(ExchangeRateHistoryRepository.class),
            mock(NewsHeadlineRepository.class), marketDataService);

    private TradingRadarRuleEngine ruleEngine;

    /** 真實 assembler（含真實 TechnicalIndicatorService）：週K 指標必須真的被算出來。 */
    private RadarInputAssembler realAssembler(TradingRadarRuleEngine engine) {
        return new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQueryService, twseRepo, usIndexRepo),
                new DistributionAdjustedPriceService(),
                engine);
    }

    private TradingRadarService newService() {
        ruleEngine = Mockito.spy(new TradingRadarRuleEngine());
        return new TradingRadarService(
                ruleEngine,
                indicatorService,
                mock(DistributionAdjustedPriceService.class),
                realAssembler(ruleEngine),
                mock(AssetClassifier.class),
                twseRepo,
                usIndexRepo,
                priceHistoryRepo,
                mock(StockDividendHistoryRepository.class),
                priceQueryService,
                taiexDisplay,
                mock(AssetSnapshotRepository.class),
                mock(StockAlertRepository.class),
                mock(StockRepository.class),
                marketDataService,
                contextService,
                mock(FundamentalAnalysisService.class),
                mock(EtfNavHistoryRepository.class),
                mock(TradingRadarSnapshotStore.class),
                mock(CurrentUserContext.class),
                mock(DividendEventEvidenceRepository.class),
                mock(TreasuryYieldService.class));
    }

    private void stubBaseline() {
        when(indicatorService.computeAll(anyString(), anyString()))
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        when(indicatorService.computeAllForNasdaq())
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        when(marketDataService.mostRecentCompletedUsTradingDay(any(Instant.class)))
                .thenAnswer(inv -> ((Instant) inv.getArgument(0))
                        .atZone(java.time.ZoneId.of("America/New_York")).toLocalDate().minusDays(1));
        when(priceQueryService.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        when(taiexDisplay.resolve()).thenReturn(new TaiexDisplayPriceService.DisplayQuote(
                new BigDecimal("22000.00"), BigDecimal.ZERO, null, null, null, null,
                LocalDate.now().minusDays(1).toString(), null, true, "VERIFIED_CLOSE"));
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /**
     * {@code n} 個「連續日曆日」的大盤列（新到舊）。連續日曆日包含週末，
     * 每個 ISO 週固定 7 天，故 {@code n} 天 ≈ {@code n/7} 個 ISO 週——
     * 500 天 ≈ 71 週（&gt; 60），241 天 ≈ 34 週（&lt; 60），正是本檔要區分的兩側。
     */
    private static List<TwseIndexDailyHistory> twRowsDesc(int n) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>(n);
        LocalDate latest = LocalDate.now().minusDays(1);
        for (int i = 0; i < n; i++) {
            BigDecimal close = BigDecimal.valueOf(22000 - i).setScale(2);
            TwseIndexDailyHistory row = new TwseIndexDailyHistory();
            row.setTradingDate(latest.minusDays(i));
            row.setOpenPoint(close.subtract(new BigDecimal("5.00")));
            row.setHighPoint(close.add(new BigDecimal("10.00")));
            row.setLowPoint(close.subtract(new BigDecimal("10.00")));
            row.setClosePoint(close);
            row.setTradeVolume(1_000_000L + i);
            rows.add(row);
        }
        return rows;
    }

    private static List<UsIndexDailyHistory> usRowsDesc(int n) {
        List<UsIndexDailyHistory> rows = new ArrayList<>(n);
        LocalDate latest = LocalDate.now().minusDays(1);
        for (int i = 0; i < n; i++) {
            BigDecimal close = BigDecimal.valueOf(18000 + i).setScale(2);
            rows.add(new UsIndexDailyHistory("IXIC", latest.minusDays(i),
                    close.subtract(new BigDecimal("5.00")),
                    close.add(new BigDecimal("10.00")),
                    close.subtract(new BigDecimal("10.00")),
                    close,
                    2_000_000L + i));
        }
        return rows;
    }

    /** 餵滿 {@code complete(MarketInput)} 需要的五個欄位；週K 之外的完整性與本檔無關。 */
    private static TechnicalIndicatorService.FullIndicators fullIndicators() {
        return new TechnicalIndicatorService.FullIndicators(
                new BigDecimal("21900.00"), new BigDecimal("21500.00"), new BigDecimal("20000.00"),
                new BigDecimal("55.00"), new BigDecimal("50.00"),
                new BigDecimal("54.00"), new BigDecimal("49.00"),
                new BigDecimal("21950.00"), TechnicalIndicatorService.ExtendedIndicators.EMPTY, null);
    }

    private TradingRadarRuleEngine.MarketInput captureMarketInput() {
        ArgumentCaptor<TradingRadarRuleEngine.MarketInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.MarketInput.class);
        verify(ruleEngine, times(1)).evaluateMarket(captor.capture());
        return captor.getValue();
    }

    // ── (a) 台股大盤：長清單真的被聚合（覆蓋 buildMarket 的第二個 limit）─────────────

    @Test
    @DisplayName("356.10c：正常資料下台股大盤 weeklyIndicators 非 null（第二個 limit 也放大了）")
    void taiwanMarketWeeklyIsActuallyProduced() {
        stubBaseline();
        when(twseRepo.findTopNByOrderByTradingDateDesc(anyInt())).thenReturn(twRowsDesc(500));

        TradingRadarDto.MarketSummary summary =
                newService().buildMarketSnapshot(TW_MARKET).summary();

        TradingRadarDto.WeeklyIndicators weekly = summary.weeklyIndicators();
        assertThat(weekly).as("台股大盤必須有自己的週K").isNotNull();
        assertThat(weekly.completedWeeks())
                .as("500 列日曆日 ≈ 71 個 ISO 週，必須跨過 60 根門檻")
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(weekly.ma10()).as("完成週足夠時週MA10 必須算得出來").isNotNull();
        assertThat(weekly.k()).isNotNull();
        assertThat(weekly.d()).isNotNull();
        assertThat(weekly.volumeRatio())
                .as("trade_volume 有帶上，週量比必須算得出來").isNotNull();
        assertThat(weekly.weekEndDate()).isNotNull();

        TradingRadarRuleEngine.MarketInput input = captureMarketInput();
        assertThat(input.weekly()).as("週K 必須真的餵進引擎，而不是只揭露在 summary").isNotNull();
        assertThat(input.dailyCandle()).as("大盤日K 棒同樣要餵進引擎").isNotNull();
        assertThat(input.weekly().completedWeeks()).isEqualTo(weekly.completedWeeks());
    }

    @Test
    @DisplayName("356.4a：只有日K 契約那 241 列時，完成週不足 60 → 整組週K 缺值但如實回報根數")
    void contractWindowAloneCannotReachSixtyCompletedWeeks() {
        stubBaseline();
        when(twseRepo.findTopNByOrderByTradingDateDesc(anyInt())).thenReturn(twRowsDesc(241));

        TradingRadarDto.MarketSummary summary =
                newService().buildMarketSnapshot(TW_MARKET).summary();

        TradingRadarDto.WeeklyIndicators weekly = summary.weeklyIndicators();
        assertThat(weekly).isNotNull();
        assertThat(weekly.completedWeeks())
                .as("241 列 ≈ 34 個 ISO 週，這正是「只改查詢那個 limit 等於沒改」的後果")
                .isLessThan(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(weekly.ma10()).isNull();
        assertThat(weekly.k()).isNull();
        assertThat(weekly.osc()).isNull();
        assertThat(weekly.completedWeeks()).as("根數仍如實回報，供揭露文案寫出「目前 N 根」").isNotNull();
    }

    // ── (b) 大盤 dataComplete 不得被週K 影響（356.10a-2）────────────────────────

    @Test
    @DisplayName("356.10a-2：完成週不足 60 的大盤仍 dataComplete=true，個股買進閘門不因此被關掉")
    void insufficientWeeklyDataMustNotTurnTheWholeMarketIncomplete() {
        stubBaseline();
        // complete(MarketInput) 只看 price／changePercent／MA20-60-240／KD／兩個 confirmation；
        // 這裡把那些餵滿，才能證明「dataComplete 為 false」只可能來自週K（而它不該影響）。
        when(indicatorService.computeAll(anyString(), anyString())).thenReturn(fullIndicators());
        when(twseRepo.findTopNByOrderByTradingDateDesc(anyInt())).thenReturn(twRowsDesc(241));

        TradingRadarDto.MarketSummary summary =
                newService().buildMarketSnapshot(TW_MARKET).summary();

        assertThat(summary.dataComplete()).isTrue();
        assertThat(summary.regime())
                .isNotEqualTo(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE.name());
        assertThat(summary.score()).as("regime 分數仍算得出來，週K 只影響加減分與揭露").isNotNull();
        assertThat(summary.risks())
                .as("缺值必須揭露不採計，不得沉默、也不得寫成「週線中性」")
                .anySatisfy(risk -> assertThat(risk).contains("週"));
    }

    // ── (c) 美股大盤：自己的序列、自己的值（356.10b／356.10d）────────────────────

    @Test
    @DisplayName("356.10b／356.10c：美股大盤有自己的 weeklyIndicators，且與台股那份不同")
    void usMarketHasItsOwnWeeklyIndicatorsDistinctFromTaiwan() {
        stubBaseline();
        when(twseRepo.findTopNByOrderByTradingDateDesc(anyInt())).thenReturn(twRowsDesc(500));
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(usRowsDesc(500));

        TradingRadarService twService = newService();
        TradingRadarDto.MarketSummary tw = twService.buildMarketSnapshot(TW_MARKET).summary();
        TradingRadarService usService = newService();
        TradingRadarDto.MarketSummary us = usService.buildMarketSnapshot(US_MARKET).summary();

        assertThat(us.weeklyIndicators()).as("美股大盤必須有自己的週K").isNotNull();
        assertThat(us.weeklyIndicators().completedWeeks())
                .as("美股大盤同樣必須餵長清單，餵 241 列契約會永遠缺值")
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(us.weeklyIndicators().close())
                .as("兩張大盤卡的數值必須不同（各自的序列）")
                .isNotEqualByComparingTo(tw.weeklyIndicators().close());
        assertThat(us.weeklyIndicators().ma10())
                .isNotEqualByComparingTo(tw.weeklyIndicators().ma10());
    }

    @Test
    @DisplayName("356.10b：美股大盤的週K 也真的進了 MarketInput，而不是只揭露在 summary")
    void usMarketWeeklyReachesTheEngine() {
        stubBaseline();
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(usRowsDesc(500));

        newService().buildMarketSnapshot(US_MARKET);

        TradingRadarRuleEngine.MarketInput input = captureMarketInput();
        assertThat(input.weekly()).isNotNull();
        assertThat(input.weekly().completedWeeks())
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(input.dailyCandle()).isNotNull();
        assertThat(input.dailyCandle().close()).isNotNull();
    }

    // ── (d) 排序 key：三軌取最大（356.1d）──────────────────────────────────────

    @Test
    @DisplayName("356.1d：排序 key 為 max(shortScore, swingScore, score)，缺值排最後")
    void sortKeyTakesTheMaximumOfAllThreeTracks() {
        assertThat(TradingRadarService.bestScore(decisionWithScores(10, 90, 20)))
                .as("只有 swing 軌高分時，排序 key 必須是它——V15 之前這一檔會被誤判成 20 分")
                .isEqualTo(90);
        assertThat(TradingRadarService.bestScore(decisionWithScores(70, null, 20))).isEqualTo(70);
        assertThat(TradingRadarService.bestScore(decisionWithScores(null, 55, null))).isEqualTo(55);
        assertThat(TradingRadarService.bestScore(decisionWithScores(null, null, null)))
                .as("三軌全缺才回 null（缺值排最後）").isNull();
    }

    private static TradingRadarDto.StockDecision decisionWithScores(
            Integer shortScore, Integer swingScore, Integer mediumScore) {
        return new TradingRadarDto.StockDecision(
                "2330", "台積電", TW_MARKET, "股票", false, false,
                "HOLD", "續抱", mediumScore, "NONE", "無", List.of(), List.of(), true,
                null, null, "VERIFIED_CLOSE", null, null,
                null, null, null, null, null, "ABOVE", "ABOVE", "ABOVE",
                null, "TWD", List.of(), List.of(), "NORMAL", "NEUTRAL", "中性",
                null, null, null, null, null, null,
                "HOLD", "續抱", shortScore, List.of(), List.of(), false,
                null, null, false, null, TradingRadarDto.RadarEvidence.EMPTY,
                null, null, null, null, null, null, null, null, List.of(),
                null, null,
                "HOLD", "續抱", swingScore, List.of(), List.of(),
                null, null, null, null, null, null);
    }
}
