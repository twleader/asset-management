package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
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

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * Task 356.13：回測的視窗、暖機與 {@code MarketInput} 接線。
 *
 * <p><b>本檔守的是「回測不是空跑」。</b>{@code WINDOW} 與 {@code WARMUP} 是
 * {@code BacktestService} 內的<b>獨立字面常數</b>，不會因為「共用 {@code RadarInputAssembler}」
 * 而自動變大：維持 241／240 的話 {@code completedWeeks} 永遠不足 60，四組週K 因子在回測中恆為
 * 缺值並重分配權重——量到的是一組<b>沒有週K 的規則</b>，卻要拿來當新權重的稽核。</p>
 *
 * <p>另一半是 {@code MarketInput} 的補線：TAIEX 映射原本<b>沒有帶 {@code trade_volume}</b>，
 * 回測的大盤週量比會恆為 null 而 production 算得出值——正是 {@code BacktestService} 內既有註解
 * 記載的舊病「線上生效、回測不生效，且沒有任何測試抓得到」（Task 323 的標題）。</p>
 */
class BacktestWeeklyWiringTest {

    private static final String TW_MARKET = "台股";
    private static final String TAIEX_CODE = "0000";

    private final TwseIndexDailyHistoryRepository twseRepo = mock(TwseIndexDailyHistoryRepository.class);
    private final UsIndexDailyHistoryRepository usIndexRepo = mock(UsIndexDailyHistoryRepository.class);
    private final StockPriceHistoryRepository priceHistoryRepo = mock(StockPriceHistoryRepository.class);
    private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
    private final MarketDataService marketDataService = mock(MarketDataService.class);
    private final TechnicalIndicatorService indicatorService = mock(TechnicalIndicatorService.class);
    private final TaiexDisplayPriceService taiexDisplay = mock(TaiexDisplayPriceService.class);

    /** 真實 context service（底層 repo 才是 mock）：兩側的期望值必須出自同一支實作。 */
    private final TradingRadarMarketContextService contextService = new TradingRadarMarketContextService(
            twseRepo, usIndexRepo, mock(ExchangeRateHistoryRepository.class),
            mock(NewsHeadlineRepository.class), marketDataService);

    private RadarInputAssembler assembler(TradingRadarRuleEngine engine) {
        return new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQueryService, twseRepo, usIndexRepo),
                new DistributionAdjustedPriceService(), engine);
    }

    private BacktestService newBacktestService(TradingRadarRuleEngine engine) {
        DistributionAdjustedPriceService adjust = new DistributionAdjustedPriceService();
        return new BacktestService(
                engine, assembler(engine), new AssetClassifier(),
                priceHistoryRepo, mock(StockDividendHistoryRepository.class), twseRepo,
                usIndexRepo, mock(ExchangeRateHistoryRepository.class),
                mock(EtfNavHistoryRepository.class), mock(StockRepository.class), adjust,
                contextService, mock(FundamentalAnalysisService.class));
    }

    private TradingRadarService newRadarService(TradingRadarRuleEngine engine) {
        return new TradingRadarService(
                engine,
                indicatorService,
                mock(DistributionAdjustedPriceService.class),
                assembler(engine),
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

    // ── fixture ─────────────────────────────────────────────────────────────

    /** {@code BacktestService.WARMUP} ＋1 列 TAIEX（升冪），故回測恰好跑一輪。 */
    private static final int TAIEX_ROWS = BacktestService.WARMUP + 1;

    /**
     * {@code TAIEX_ROWS} 列 TAIEX（升冪），日期<b>只取平日</b>。
     *
     * <p>用連續日曆日會讓每個 ISO 週佔滿 7 列，306 列只有約 43 個完成週 &lt; 60，
     * 整組週K 缺值——本檔要驗的「回測有沒有帶 volume」就會被「根本沒有週K」蓋掉。
     * 真實市場每週約 5 個交易日，平日序列才是等價的形狀（306 列 ≈ 61 週）。</p>
     */
    private static List<TwseIndexDailyHistory> taiexAscending() {
        List<LocalDate> weekdays = new ArrayList<>(TAIEX_ROWS);
        LocalDate cursor = LocalDate.now().minusDays(2);
        while (weekdays.size() < TAIEX_ROWS) {
            if (cursor.getDayOfWeek().getValue() <= 5) weekdays.add(cursor);
            cursor = cursor.minusDays(1);
        }
        Collections.reverse(weekdays);
        List<TwseIndexDailyHistory> asc = new ArrayList<>(TAIEX_ROWS);
        for (int i = 0; i < TAIEX_ROWS; i++) {
            boolean latestRow = i == TAIEX_ROWS - 1;
            // 最新一列刻意放量（volume=400），其餘 100：週量比與日量比因此都算得出來，
            // 「回測沒帶 volume」會直接讓 weekly.volumeRatio() 變 null 而被抓到。
            BigDecimal close = BigDecimal.valueOf(latestRow ? 22100 : 22000).setScale(2);
            TwseIndexDailyHistory row = new TwseIndexDailyHistory();
            row.setTradingDate(weekdays.get(i));
            row.setOpenPoint(close.subtract(new BigDecimal("5.00")));
            row.setHighPoint(close.add(new BigDecimal("10.00")));
            row.setLowPoint(close.subtract(new BigDecimal("10.00")));
            row.setClosePoint(close);
            row.setTradeVolume(latestRow ? 400L : 100L);
            asc.add(row);
        }
        return asc;
    }

    private static List<TwseIndexDailyHistory> descending(List<TwseIndexDailyHistory> asc) {
        List<TwseIndexDailyHistory> desc = new ArrayList<>(asc);
        Collections.reverse(desc);
        return desc;
    }

    @SuppressWarnings("unchecked")
    private static Map<LocalDate, TradingRadarRuleEngine.MarketRegime> invokeBuildMarketRegimes(
            BacktestService service) throws Exception {
        Method method = BacktestService.class.getDeclaredMethod("buildMarketRegimes");
        method.setAccessible(true);
        return (Map<LocalDate, TradingRadarRuleEngine.MarketRegime>) method.invoke(service);
    }

    /** 與回測 assembler 逐位相同的日K 指標：同一段視窗、同一支 {@code computeFromSeries}。 */
    private TechnicalIndicatorService.FullIndicators indicatorsOverSameWindow(
            List<TwseIndexDailyHistory> asc) {
        List<StockPriceHistory> desc = new ArrayList<>();
        for (int i = asc.size() - 1; i >= 0; i--) {
            TwseIndexDailyHistory row = asc.get(i);
            desc.add(StockPriceHistory.builder()
                    .stockCode(TAIEX_CODE).market(TW_MARKET).tradingDate(row.getTradingDate())
                    .openPrice(row.getOpenPoint()).highPrice(row.getHighPoint())
                    .lowPrice(row.getLowPoint()).closePrice(row.getClosePoint())
                    .volume(row.getTradeVolume()).build());
        }
        return new TechnicalIndicatorService(priceHistoryRepo, priceQueryService, twseRepo, usIndexRepo)
                .computeFromSeries(desc.subList(0,
                        Math.min(desc.size(), RadarInputAssembler.FULL_WINDOW)));
    }

    private static TradingRadarRuleEngine.MarketInput captureMarketInput(TradingRadarRuleEngine engine) {
        ArgumentCaptor<TradingRadarRuleEngine.MarketInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.MarketInput.class);
        verify(engine, times(1)).evaluateMarket(captor.capture());
        return captor.getValue();
    }

    // ── (a) 視窗常數（356.13a-2）────────────────────────────────────────────

    @Test
    @DisplayName("356.13a-2：WINDOW 已放大到 500，日K 契約仍為 241，WARMUP 同時滿足兩個下界")
    void windowIsWidenedWhileTheDailyContractStaysAt241() {
        assertThat(BacktestService.WINDOW)
                .as("維持 241 的話 ≈ 48 個 ISO 週 < 60，週K 因子在回測中恆缺值")
                .isEqualTo(500);
        assertThat(BacktestService.DAILY_CONTRACT_ROWS)
                .as("日K 契約必須與 production 共用同一個值，不得跟著 WINDOW 一起變大")
                .isEqualTo(241)
                .isEqualTo(RadarObservationResolver.INDICATOR_SERIES_MAX_ROWS);
        assertThat(BacktestService.WARMUP)
                .as("暖機必須同時滿足「240 根完成日 K」與「60 根完成週」")
                .isGreaterThanOrEqualTo(RadarInputAssembler.FULL_WINDOW)
                .isGreaterThanOrEqualTo(BacktestService.WEEKLY_WARMUP_ROWS);
        assertThat(BacktestService.WEEKLY_WARMUP_ROWS)
                .isEqualTo((RadarInputAssembler.MIN_COMPLETED_WEEKS + 1) * 5);
    }

    @Test
    @DisplayName("356.13a-2：回測那一段可重放序列的 completedWeeks 必須 >= 60（否則回測是空跑）")
    void theBacktestWindowActuallyReachesSixtyCompletedWeeks() {
        // 與 BacktestService 逐字相同的呼叫形狀：長視窗 ＋ 顯式日K 契約列數。
        List<StockPriceHistory> windowDesc = new ArrayList<>();
        LocalDate latest = LocalDate.now().minusDays(2);
        for (int i = 0; i < BacktestService.WINDOW; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i % 7).setScale(4);
            windowDesc.add(StockPriceHistory.builder()
                    .stockCode("2330").market(TW_MARKET).tradingDate(latest.minusDays(i))
                    .openPrice(close).highPrice(close.add(BigDecimal.ONE))
                    .lowPrice(close.subtract(BigDecimal.ONE)).closePrice(close)
                    .volume(1_000L + i).build());
        }

        RadarInputAssembler.Assembled assembled = assembler(new TradingRadarRuleEngine()).assemble(
                windowDesc, List.of(), false,
                Math.min(windowDesc.size(), BacktestService.DAILY_CONTRACT_ROWS),
                BacktestService.DAILY_CONTRACT_ROWS,
                windowDesc.get(0).getClosePrice());

        assertThat(assembled.weekly()).isNotNull();
        assertThat(assembled.weekly().completedWeeks())
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(assembled.weekly().ma10()).as("完成週足夠時週MA10 必須有值").isNotNull();
        assertThat(assembled.completedCloses())
                .as("356.4d-3：completedCloses 仍由日K 契約界定，不得被長視窗撐大")
                .hasSizeLessThanOrEqualTo(BacktestService.DAILY_CONTRACT_ROWS);
    }

    // ── (b) 預設 horizon（356.13a）───────────────────────────────────────────

    @Test
    @DisplayName("356.13a：預設 horizon 由 5/20/60/120 改為 5/10/20/60/120（中間軌才有樣本）")
    void defaultHorizonsCoverTheSwingTrack() {
        List<StockPriceHistory> asc = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(BacktestService.WARMUP + 200L);
        for (int i = 0; i < BacktestService.WARMUP + 200; i++) {
            BigDecimal close = BigDecimal.valueOf(100 + i * 0.1).setScale(4, java.math.RoundingMode.HALF_UP);
            asc.add(StockPriceHistory.builder()
                    .stockCode("2330").market(TW_MARKET).tradingDate(start.plusDays(i))
                    .openPrice(close).highPrice(close.add(BigDecimal.ONE))
                    .lowPrice(close.subtract(BigDecimal.ONE)).closePrice(close)
                    .volume(1_000_000L + i).build());
        }
        when(priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc("2330", TW_MARKET))
                .thenReturn(asc);
        when(twseRepo.findAllByOrderByTradingDateAsc()).thenReturn(List.of());
        when(usIndexRepo.findByIndexCodeOrderByTradingDateAsc(anyString())).thenReturn(List.of());

        var response = newBacktestService(new TradingRadarRuleEngine()).run(
                new com.steven.assets.dto.BacktestDto.Request(
                        List.of("2330"), null, null, null, null, null));

        assertThat(response.results()).extracting(r -> r.horizon())
                .as("10 個交易日這一格是 1周~1月 軌的樣本；缺了它，中間軌沒有任何回測依據")
                .contains(5, 10, 20, 60, 120);
    }

    // ── (c) 回測與 production 對同一日 TAIEX 不分岔（356.13b-2）─────────────────

    @Test
    @DisplayName("356.13b-2：同一日 TAIEX 在回測與 production 產生相同 regime 與相同週量比")
    void backtestAndProductionAgreeOnTaiexRegimeAndWeeklyVolumeRatio() throws Exception {
        List<TwseIndexDailyHistory> asc = taiexAscending();
        when(twseRepo.findAllByOrderByTradingDateAsc()).thenReturn(asc);
        when(twseRepo.findTopNByOrderByTradingDateDesc(anyInt())).thenReturn(descending(asc));
        when(usIndexRepo.findByIndexCodeOrderByTradingDateAsc(anyString())).thenReturn(List.of());
        when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        org.mockito.Mockito.lenient().when(marketDataService.isTwTradingDayKnown(any(LocalDate.class)))
                .thenReturn(Optional.of(true));
        org.mockito.Mockito.lenient().when(marketDataService.isTwTradingDayCachedOnly(any(LocalDate.class)))
                .thenReturn(Optional.of(true));
        // production 的 MA／KD 取自 indicatorService.computeAll(TAIEX)（讀 DB），回測則由 assembler
        // 對同一段視窗算出。這裡把 computeAll stub 成「同一段視窗的 computeFromSeries」，
        // 即 production 讀到同一份資料時的實際結果——否則 production 會因為 EMPTY 指標而落在
        // DATA_INCOMPLETE，regime 比較就變成在比「有沒有 stub 指標」，證明不了兩側是否分岔。
        when(indicatorService.computeAll(anyString(), anyString()))
                .thenReturn(indicatorsOverSameWindow(asc));
        when(priceQueryService.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        when(taiexDisplay.resolve()).thenReturn(new TaiexDisplayPriceService.DisplayQuote(
                new BigDecimal("22100.00"), BigDecimal.ZERO, null, null, null, null,
                asc.get(asc.size() - 1).getTradingDate().toString(), null, true, "VERIFIED_CLOSE"));

        TradingRadarRuleEngine productionEngine = Mockito.spy(new TradingRadarRuleEngine());
        TradingRadarDto.MarketSummary productionSummary =
                newRadarService(productionEngine).buildMarketSnapshot(TW_MARKET).summary();
        TradingRadarRuleEngine.MarketInput production = captureMarketInput(productionEngine);

        TradingRadarRuleEngine backtestEngine = Mockito.spy(new TradingRadarRuleEngine());
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes =
                invokeBuildMarketRegimes(newBacktestService(backtestEngine));
        TradingRadarRuleEngine.MarketInput backtest = captureMarketInput(backtestEngine);

        LocalDate latest = asc.get(asc.size() - 1).getTradingDate();
        assertThat(regimes.keySet()).as("前提：回測必須恰好跑一輪").containsExactly(latest);

        // 前提：週量比真的算得出來，否則兩邊同為 null 也會「通過」。
        assertThat(backtest.weekly()).as("回測的 TAIEX MarketInput 必須帶週K").isNotNull();
        assertThat(production.weekly()).as("production 的 TAIEX MarketInput 必須帶週K").isNotNull();
        assertThat(backtest.weekly().volumeRatio())
                .as("TAIEX 映射沒補 trade_volume 的話，這裡恆為 null 而 production 有值")
                .isNotNull();
        assertThat(production.weekly().volumeRatio()).isNotNull();
        assertThat(backtest.weekly().volumeRatio())
                .isEqualByComparingTo(production.weekly().volumeRatio());
        assertThat(backtest.weekly().completedWeeks())
                .isEqualTo(production.weekly().completedWeeks())
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);

        assertThat(backtest.dailyCandle()).as("日K 棒兩側都必須接上").isNotNull();
        assertThat(production.dailyCandle()).isNotNull();
        assertThat(backtest.dailyCandle().close())
                .isEqualByComparingTo(production.dailyCandle().close());

        assertThat(regimes.get(latest))
                .as("同一日 TAIEX 的 regime 必須一致；不一致即代表兩側又分岔了")
                .isEqualTo(TradingRadarRuleEngine.MarketRegime.valueOf(productionSummary.regime()));
    }

    // ── (d) 回測的個股 StockInput 也必須接上（356.13a-2）────────────────────────

    /**
     * {@code MarketInput} 兩處補了線，<b>個股那兩處也必須補</b>。
     *
     * <p>{@code BacktestService} 對個股有兩個 {@code StockInput} 建構點——{@code evaluate(...)}
     * （legacy 回測）與 {@code v13Input(...)}（V13 candidate 回測）。兩者原本都只傳 25 個引數而
     * 靜默命中 Task 356 之前的相容建構式，因此即使 {@code WINDOW} 已放大到 500、
     * {@code completedWeeks} 確實 &ge; 60（本檔 (a)(b) 兩段已釘住），五個新因子在<b>個股</b>回測中
     * 仍然全部缺值——356.13b 的三軌述詞稽核量到的會是一組沒有週K 也沒有日K 棒的規則。</p>
     *
     * <p>兩處都以反射直呼，理由與 {@code invokeBuildMarketRegimes} 相同：跑完整回測需要一整組
     * 標的／事件／匯率 fixture，那些與本斷言無關，卻會讓失敗訊息指不到真正的原因。</p>
     */
    @Test
    @DisplayName("356.13a-2：回測的兩處個股 StockInput 都必須帶 dailyCandle 與 weekly")
    void backtestStockInputsCarryDailyCandleAndWeekly() throws Exception {
        List<TwseIndexDailyHistory> asc = taiexAscending();
        RadarInputAssembler.Assembled assembled = assembledOverBacktestWindow(asc);

        // 前提：這一份 Assembled 真的算得出兩個新欄位，否則下面兩條 not-null 是恆假的空轉。
        assertThat(assembled.dailyCandle()).as("前提：fixture 必須產生完整日K 棒").isNotNull();
        assertThat(assembled.weekly()).as("前提：fixture 必須產生週K").isNotNull();
        assertThat(assembled.weekly().completedWeeks())
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);

        BigDecimal price = assembled.adjustedRowsDesc().get(0).getClosePrice();

        // (1) V13 路徑：v13Input(...) 直接回傳 StockInput，可直接斷言。
        TradingRadarRuleEngine v13Engine = Mockito.spy(new TradingRadarRuleEngine());
        Method v13Input = BacktestService.class.getDeclaredMethod("v13Input",
                RadarInputAssembler.Assembled.class, boolean.class, BigDecimal.class,
                RadarBacktestExecution.InstrumentKind.class,
                TradingRadarRuleEngine.MarketRegime.class, BigDecimal.class, BigDecimal.class,
                BigDecimal.class, TradingRadarRuleEngine.FundamentalInput.class);
        v13Input.setAccessible(true);
        TradingRadarRuleEngine.StockInput v13 = (TradingRadarRuleEngine.StockInput) v13Input.invoke(
                newBacktestService(v13Engine), assembled, true, price,
                RadarBacktestExecution.InstrumentKind.STOCK,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, null, null, null,
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);

        assertThat(v13.dailyCandle())
                .as("V13 回測路徑漏傳 → promotion 稽核比的是「有週K 的線上」對「沒週K 的回測」")
                .isNotNull();
        assertThat(v13.weekly()).isNotNull();
        assertThat(v13.weekly().completedWeeks())
                .isEqualTo(assembled.weekly().completedWeeks());

        // (2) legacy 路徑：evaluate(...) 只回 StockResult，改由 spy 捕捉真正送進引擎的輸入。
        TradingRadarRuleEngine legacyEngine = Mockito.spy(new TradingRadarRuleEngine());
        Method evaluate = BacktestService.class.getDeclaredMethod("evaluate",
                RadarInputAssembler.Assembled.class, boolean.class, BigDecimal.class,
                TradingRadarRuleEngine.InstrumentType.class,
                TradingRadarRuleEngine.MarketRegime.class, BigDecimal.class, BigDecimal.class,
                BigDecimal.class, TradingRadarRuleEngine.FundamentalInput.class);
        evaluate.setAccessible(true);
        evaluate.invoke(newBacktestService(legacyEngine), assembled, true, price,
                TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL, null, null, null,
                TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE);

        ArgumentCaptor<TradingRadarRuleEngine.StockInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(legacyEngine, times(1)).evaluateStock(captor.capture());
        TradingRadarRuleEngine.StockInput legacy = captor.getValue();

        assertThat(legacy.dailyCandle()).as("legacy 回測路徑同樣不得漏傳").isNotNull();
        assertThat(legacy.weekly()).isNotNull();
        assertThat(legacy.weekly().completedWeeks())
                .isEqualTo(assembled.weekly().completedWeeks());
    }

    /**
     * 以回測自己的視窗契約（{@code WINDOW} 列、{@code DAILY_CONTRACT_ROWS} 為日K 契約）
     * 對同一份 TAIEX 序列組出 {@code Assembled}，形狀與 {@code BacktestService} 內的三處
     * {@code assemble(...)} 呼叫逐項相同。
     */
    private RadarInputAssembler.Assembled assembledOverBacktestWindow(
            List<TwseIndexDailyHistory> asc) {
        List<StockPriceHistory> windowDesc = new ArrayList<>();
        for (int i = asc.size() - 1; i >= 0 && windowDesc.size() < BacktestService.WINDOW; i--) {
            TwseIndexDailyHistory row = asc.get(i);
            windowDesc.add(StockPriceHistory.builder()
                    .stockCode(TAIEX_CODE).market(TW_MARKET).tradingDate(row.getTradingDate())
                    .openPrice(row.getOpenPoint()).highPrice(row.getHighPoint())
                    .lowPrice(row.getLowPoint()).closePrice(row.getClosePoint())
                    .volume(row.getTradeVolume()).build());
        }
        return assembler(new TradingRadarRuleEngine()).assemble(
                windowDesc, List.of(), false,
                Math.min(windowDesc.size(), BacktestService.DAILY_CONTRACT_ROWS),
                BacktestService.DAILY_CONTRACT_ROWS,
                windowDesc.get(0).getClosePrice());
    }
}
