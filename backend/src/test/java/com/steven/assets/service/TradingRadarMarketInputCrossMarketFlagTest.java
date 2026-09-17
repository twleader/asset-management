package com.steven.assets.service;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 342（Requirement 82）：{@code MarketInput.crossMarketApplicable} 的兩個方向，
 * 以及「production 與回測的美股 {@code MarketInput} 在本次接線的四欄同源」。
 *
 * <p><b>為什麼非測回測那一側不可：</b>回測的 {@code buildUsMarketRegimes} 現在就已經與
 * production 不一致（第 6 個引數 {@code completedChangePercent} 早就填了值，production 卻傳
 * {@code null}），只是被引擎 {@code marketActivity == null} 的短路吃掉整個區塊而沒有可觀察後果。
 * production 一補上量比，那個分支就會「線上生效、回測不生效」，而且不會有任何既有測試抓到——
 * 正是 Task 323 標題所說的「停止線上與回測分岔」。</p>
 *
 * <p><b>斷言範圍刻意只限縮在四欄</b>（{@code completedChangePercent}／{@code marketVolumeRatio}／
 * {@code marketTurnoverRatio}／{@code crossMarketApplicable}）。{@code price}／{@code changePercent}／
 * {@code indicators}／兩個 {@code Confirmation} 這五欄 production 與回測<b>本來就不同</b>，
 * 且是 Requirement 77／Task 336 明文列為範圍外、刻意保留的狀態（live 走精確路徑、回測美股 regime
 * 仍走 double {@code simpleMa}；production 的 price 取自未經完成日過濾的序列）。寫成「逐欄同源」
 * 會必然失敗，或誘使日後有人去收斂一個已被明確排除的項目。</p>
 *
 * <p><b>{@code completedChangePercent} 只能比 as-of 日與 {@code signum()}，不得比值。</b>
 * 兩側走的是捨入不同的兩支 helper：production 為
 * {@code subtract().divide(previous, 10, HALF_UP).multiply(100).setScale(4, HALF_UP)}，
 * 回測為 {@code divide(previous, 8, HALF_UP).subtract(ONE).multiply(100)}（無 {@code setScale}）。
 * 收盤價非整除時兩者連 {@code compareTo} 都不相等，而引擎的四個計分分支只取 {@code signum()}。</p>
 */
class TradingRadarMarketInputCrossMarketFlagTest {

    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    /**
     * {@code BacktestService.WARMUP} ＋1，恰好跑一輪。
     *
     * <p>Task 356.13a-2 起 {@code WARMUP} 由 {@code FULL_WINDOW}(240) 放大為
     * 「240 根完成日 K」與「60 根完成週」的較大者，故此處必須直接引用該常數；
     * 沿用 {@code FULL_WINDOW + 1} 會讓回測迴圈一輪都跑不到，
     * {@code evaluateMarket} 從未被呼叫而測試以「Wanted but not invoked」失敗。</p>
     */
    private static final int IXIC_ROWS = BacktestService.WARMUP + 1;

    private final TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
    private final UsIndexDailyHistoryRepository usIndexRepo = mock(UsIndexDailyHistoryRepository.class);
    private final StockPriceHistoryRepository priceHistoryRepo = mock(StockPriceHistoryRepository.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final TechnicalIndicatorService indicatorService = mock(TechnicalIndicatorService.class);
    private final PriceQueryService priceQueryService = mock(PriceQueryService.class);

    /** 真實的 context service（底層 repo 才是 mock）——production 端的期望值就從這支算出來。 */
    private final TradingRadarMarketContextService realContextService = new TradingRadarMarketContextService(
            twseRepo, usIndexRepo, mock(ExchangeRateHistoryRepository.class),
            mock(NewsHeadlineRepository.class), marketDataService);

    private TradingRadarRuleEngine productionEngine;

    // ─────────── fixture ───────────

    /**
     * {@link #IXIC_ROWS} 列 IXIC，日期為連續的過去日（確保全部早於美東 16:00 完成邊界）。
     * 最新一列 volume=400、其餘 100 → 量能比 4.0000；最新收盤 18100、前一日 18000 → 完成日上漲。
     */
    private static List<UsIndexDailyHistory> ixicAscending() {
        LocalDate latest = LocalDate.now(NEW_YORK).minusDays(5);
        List<UsIndexDailyHistory> asc = new ArrayList<>();
        for (int i = IXIC_ROWS - 1; i >= 1; i--) {
            BigDecimal close = new BigDecimal("18000");
            asc.add(new UsIndexDailyHistory("IXIC", latest.minusDays(i), close, close, close, close, 100L));
        }
        BigDecimal last = new BigDecimal("18100");
        asc.add(new UsIndexDailyHistory("IXIC", latest, last, last, last, last, 400L));
        return asc;
    }

    private static List<UsIndexDailyHistory> descending(List<UsIndexDailyHistory> asc) {
        List<UsIndexDailyHistory> desc = new ArrayList<>(asc);
        java.util.Collections.reverse(desc);
        return desc;
    }

    /** 「每日皆為交易日」前提下的最近一個已完成美股交易日（比照既有 wiring 測試）。 */
    private static LocalDate everyDayTradingUsCompletedDay(Instant instant) {
        ZonedDateTime nowNy = instant.atZone(NEW_YORK);
        return nowNy.toLocalTime().isBefore(LocalTime.of(16, 0))
                ? nowNy.toLocalDate().minusDays(1)
                : nowNy.toLocalDate();
    }

    private TradingRadarService newRadarService() {
        productionEngine = Mockito.spy(new TradingRadarRuleEngine());
        return new TradingRadarService(
                productionEngine,
                indicatorService,
                mock(DistributionAdjustedPriceService.class),
                mock(RadarInputAssembler.class),
                mock(AssetClassifier.class),
                twseRepo,
                usIndexRepo,
                priceHistoryRepo,
                mock(StockDividendHistoryRepository.class),
                priceQueryService,
                mock(TaiexDisplayPriceService.class),
                mock(AssetSnapshotRepository.class),
                mock(StockAlertRepository.class),
                mock(StockRepository.class),
                marketDataService,
                Mockito.spy(realContextService),
                mock(FundamentalAnalysisService.class),
                mock(EtfNavHistoryRepository.class),
                mock(TradingRadarSnapshotStore.class),
                mock(CurrentUserContext.class),
                mock(DividendEventEvidenceRepository.class),
                mock(TreasuryYieldService.class));
    }

    private BacktestService newBacktestService(TradingRadarRuleEngine engine) {
        DistributionAdjustedPriceService adjust = new DistributionAdjustedPriceService();
        RadarInputAssembler assembler = new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQueryService, twseRepo, usIndexRepo),
                adjust, engine);
        return new BacktestService(
                engine, assembler, new AssetClassifier(),
                priceHistoryRepo, mock(StockDividendHistoryRepository.class), twseRepo,
                usIndexRepo, mock(ExchangeRateHistoryRepository.class), mock(EtfNavHistoryRepository.class),
                mock(StockRepository.class), adjust, mock(TradingRadarMarketContextService.class),
                mock(FundamentalAnalysisService.class));
    }

    /**
     * {@code buildUsMarketRegimes()} 是 private 且唯一的公開入口是整套 V13 {@code run()}——
     * 為此鋪一整份 stock/dividend/fundamental fixture 只會讓這條守門測試依賴一堆與本任務無關的
     * 路徑。改以反射直接打那一支（本專案既有作法，見 {@code ProcessRcloneClientRateLimitTest}／
     * {@code ExcelImportServiceTest}）：方法被改名時會以 {@code NoSuchMethodException} 大聲失敗。
     */
    @SuppressWarnings("unchecked")
    private static java.util.Map<LocalDate, TradingRadarRuleEngine.MarketRegime> invokeBuildUsMarketRegimes(
            BacktestService service) throws Exception {
        Method method = BacktestService.class.getDeclaredMethod("buildUsMarketRegimes");
        method.setAccessible(true);
        return (java.util.Map<LocalDate, TradingRadarRuleEngine.MarketRegime>) method.invoke(service);
    }

    // ─────────── (a) 342.10.5 四欄同源 ───────────

    @Test
    void production與回測的美股MarketInput在本次接線的四欄同源() throws Exception {
        List<UsIndexDailyHistory> asc = ixicAscending();
        when(usIndexRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 500))
                .thenReturn(descending(asc));
        when(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("IXIC")).thenReturn(asc);
        when(indicatorService.computeAllForNasdaq())
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        when(marketDataService.mostRecentCompletedUsTradingDay(any(Instant.class)))
                .thenAnswer(inv -> everyDayTradingUsCompletedDay(inv.getArgument(0)));

        TradingRadarService radarService = newRadarService();
        String productionAsOfDate = radarService.buildMarketSnapshot(US_MARKET)
                .summary().marketVolumeAsOfDate();
        TradingRadarRuleEngine.MarketInput production = captureMarketInput(productionEngine);

        TradingRadarRuleEngine backtestEngine = Mockito.spy(new TradingRadarRuleEngine());
        java.util.Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes =
                invokeBuildUsMarketRegimes(newBacktestService(backtestEngine));
        TradingRadarRuleEngine.MarketInput backtest = captureMarketInput(backtestEngine);

        // 前提：fixture 真的算得出量比，否則兩邊同為 null 也會「通過」。
        assertNotNull(production.marketVolumeRatio(), "前提：production 端必須算得出量比");
        assertNotNull(backtest.marketVolumeRatio(), "前提：回測端必須算得出量比");
        assertEquals(production.marketVolumeRatio(), backtest.marketVolumeRatio(),
                "量比兩側演算法逐項相同（20 個正量日中位數為分母、至少 10 筆、scale 4 HALF_UP），"
                        + "值必須完全相等；不相等即代表回測又自己算了一份");

        assertNull(production.marketTurnoverRatio(), "無成交值來源，production 必須維持 null");
        assertNull(backtest.marketTurnoverRatio(), "無成交值來源，回測必須維持 null");

        assertFalse(production.crossMarketApplicable());
        assertFalse(backtest.crossMarketApplicable());

        // completedChangePercent：只比 as-of 日與 signum，理由見本類別 javadoc。
        assertNotNull(production.completedChangePercent());
        assertNotNull(backtest.completedChangePercent());
        assertEquals(production.completedChangePercent().signum(),
                backtest.completedChangePercent().signum(),
                "引擎的四個量價計分分支只取 signum()，兩側必須同號");

        // 同一個 as-of 日：production 的量能 as-of（＝usContext.marketAsOfDate）必須等於
        // 回測那一輪 regime 的 key。fixture 恰為 WARMUP+1 列，回測只跑一輪。
        LocalDate latest = asc.get(asc.size() - 1).getTradingDate();
        assertEquals(List.of(latest), List.copyOf(regimes.keySet()),
                "前提：回測必須恰好跑一輪且 as-of 為序列最後一列");
        assertEquals(latest.toString(), productionAsOfDate,
                "兩側必須落在同一個 as-of 日，否則漲跌方向與量比會跨日拼接");
    }

    // ─────────── (b) 342.10.3 台股的跨市場旗標必須為 true ───────────

    @Test
    void 台股組的跨市場旗標必須為適用() {
        when(marketDataService.isTwTradingDayKnown(any(LocalDate.class)))
                .thenReturn(java.util.Optional.of(true));
        when(indicatorService.computeAll(anyString(), anyString()))
                .thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);

        TradingRadarService radarService = newRadarService();
        radarService.buildMarketSnapshot(TW_MARKET);

        TradingRadarRuleEngine.MarketInput twInput = captureMarketInput(productionEngine);
        assertTrue(twInput.crossMarketApplicable(),
                "台股的跨市場因子確實適用；旗標若被順手改成 false，"
                        + "「美股科技資料真的抓不到」那則正當提醒會被靜默吞掉");
    }

    private static TradingRadarRuleEngine.MarketInput captureMarketInput(TradingRadarRuleEngine engine) {
        ArgumentCaptor<TradingRadarRuleEngine.MarketInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.MarketInput.class);
        verify(engine, times(1)).evaluateMarket(captor.capture());
        return captor.getValue();
    }
}
