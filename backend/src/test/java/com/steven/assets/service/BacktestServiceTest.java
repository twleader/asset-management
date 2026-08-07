package com.steven.assets.service;

import com.steven.assets.dto.BacktestDto;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.EtfNavHistoryRepository;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
    private static final LocalDate START = LocalDate.of(2016, 1, 4);

    @Mock private StockPriceHistoryRepository priceHistoryRepo;
    @Mock private StockDividendHistoryRepository dividendHistoryRepo;
    @Mock private TwseIndexDailyHistoryRepository twseRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexRepo;
    @Mock private ExchangeRateHistoryRepository exchangeRateRepo;
    @Mock private EtfNavHistoryRepository etfNavHistoryRepo;
    @Mock private StockRepository stockRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TradingRadarMarketContextService marketContextService;
    @Mock private FundamentalAnalysisService fundamentalAnalysisService;

    private final TradingRadarRuleEngine engine = new TradingRadarRuleEngine();
    private final DistributionAdjustedPriceService adjust = new DistributionAdjustedPriceService();

    private RadarInputAssembler assembler() {
        return new RadarInputAssembler(
                new TechnicalIndicatorService(priceHistoryRepo, priceQuery, twseRepo), adjust, engine);
    }

    private BacktestService service() {
        return new BacktestService(
                engine, assembler(), new AssetClassifier(),
                priceHistoryRepo, dividendHistoryRepo, twseRepo,
                usIndexRepo, exchangeRateRepo, etfNavHistoryRepo, stockRepo, adjust, marketContextService,
                fundamentalAnalysisService);
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
    @DisplayName("Task 292 回測與 production 共用 TW_RULES_V11")
    void ruleVersionIsV11() {
        assertThat(TradingRadarRuleEngine.RULE_VERSION).isEqualTo("TW_RULES_V11");
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
