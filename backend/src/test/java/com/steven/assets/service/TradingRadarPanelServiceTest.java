package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real rule/indicator/assembler projections over fixed persisted fixtures, never a live provider. */
class TradingRadarPanelServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");
    private static final LocalDate TW_DATE = LocalDate.of(2026, 9, 23);
    private static final LocalDate US_DATE = TW_DATE.minusDays(1);

    @Test void fivePanelsMatchCompactBaselineCompleteRowsOrderingAndMarketAtOneInstant() {
        Fixture f = new Fixture();
        var baseline = f.service.getList(NOW);
        var tw = f.service.getStocksPanel("台股", NOW);
        var us = f.service.getStocksPanel("美股", NOW);
        assertThat(tw.data().stocks()).isEqualTo(baseline.stocks().stream().filter(s -> "台股".equals(s.market())).toList());
        assertThat(us.data().stocks()).isEqualTo(baseline.stocks().stream().filter(s -> "美股".equals(s.market())).toList());
        assertThat(tw.data().market()).isEqualTo(baseline.market());
        assertThat(us.data().market()).isEqualTo(baseline.usMarket());
        assertThat(f.service.getMarketPanel("台股", NOW).data().market()).isEqualTo(baseline.market());
        assertThat(f.service.getMarketPanel("美股", NOW).data().market()).isEqualTo(baseline.usMarket());
        assertThat(f.service.getPublicInformationPanel(NOW).data().publicInformation()).isEqualTo(baseline.publicInformation());
        assertThat(tw.data().stocks()).hasSize(2).allSatisfy(stock -> {
            assertThat(stock.score()).isNotNull();
            assertThat(stock.shortScore()).isNotNull();
            assertThat(stock.swingScore()).isNotNull();
        });
        assertThat(tw.data().skippedNonTwStocks()).isEqualTo(1);
        assertThat(us.data().skippedNonTwStocks()).isEqualTo(1);
        assertThat(tw.ruleVersion()).isEqualTo("TW_RULES_V20");
        assertThat(tw.generatedAt()).isEqualTo("2026-09-23T16:00+08:00");
        assertThat(tw.actionPolicyVersion()).isEqualTo(baseline.actionPolicyVersion());
    }

    @Test void stockPanelScopesOwnerAndMarketBeforeEveryBatchAndReadsThresholdOnce() {
        Fixture f = new Fixture();
        var response = f.service.getStocksPanel("台股", NOW);
        assertThat(response.data().stocks()).extracting(TradingRadarDto.ListStock::market).containsOnly("台股");
        assertThat(response.data().stocks()).filteredOn(row -> row.stockCode().equals("SAME"))
                .singleElement().satisfies(row -> assertThat(row.held()).isTrue());
        verify(f.snapshots).findLatestWithStocksByOwnerUserId(7L);
        verify(f.alerts).findDistinctStockCodeMarketByOwnerUserId(7L);
        verify(f.snapshots, never()).findLatestWithStocks();
        verify(f.alerts, never()).findDistinctStockCodeMarket();
        verify(f.threshold).incomeThreshold();
        assertBatchPairs(f, Set.of(new TradingRadarListBatchRepository.Key("2330", "台股"),
                new TradingRadarListBatchRepository.Key("SAME", "台股")));
        verifyNoInteractions(f.news, f.snapshotStore, f.stockRepo, f.history);
        verify(f.technicalCache, never()).readMarketLocals(anyList());
        verifyNoExternalOrWrites(f);
    }

    @Test void usPanelDoesNotBuildTaiwanMarketAndMarketCardsNeverLoadOwnerTargets() {
        Fixture us = new Fixture();
        us.service.getStocksPanel("美股", NOW);
        verifyNoInteractions(us.tw, us.news);
        assertBatchPairs(us, Set.of(new TradingRadarListBatchRepository.Key("SAME", "美股"),
                new TradingRadarListBatchRepository.Key("AAPL", "美股")));
        verify(us.technicalCache, never()).readPairs(anyList());
        Fixture markets = new Fixture();
        markets.service.getMarketPanel("台股", NOW);
        markets.service.getMarketPanel("美股", NOW);
        verifyNoInteractions(markets.snapshots, markets.alerts, markets.batch, markets.threshold,
                markets.news, markets.fundamental, markets.technicalCache, markets.facts);
    }

    @Test void marketReuseReadsOneRawWindowAndNeverCallsRefreshingDisplayCalendar() {
        Fixture f = new Fixture();
        var response = f.service.getMarketPanel("台股", NOW);
        assertThat(response.data().market().score()).isNotNull();
        verify(f.tw).findTopNByOrderByTradingDateDesc(500);
        verify(f.tw, never()).findTopNByOrderByTradingDateDesc(240);
        verify(f.tw, never()).findTopNByOrderByTradingDateDesc(60);
        verify(f.tw, never()).findTop60ByOrderByTradingDateDesc();
        verify(f.prices).getLive("0000", "台股");
        verify(f.prices, never()).displaySession(anyString());
        verifyNoExternalOrWrites(f);
    }

    @Test void publicInformationDistinguishesEmptyNullFailureAndKeepsOtherPanelsIndependent() {
        Fixture f = new Fixture();
        assertThat(f.service.getPublicInformationPanel(NOW).data().publicInformation()).isEmpty();
        verifyNoInteractions(f.snapshots, f.alerts, f.batch, f.tw, f.us);
        when(f.news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any())).thenReturn(null);
        assertThatThrownBy(() -> f.service.getPublicInformationPanel(NOW)).isInstanceOf(IllegalStateException.class);
        when(f.news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> f.service.getPublicInformationPanel(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(f.service.getStocksPanel("台股", NOW).data().stocks()).hasSize(2);
        assertThat(f.context.resolve(NOW).publicInformation()).isEmpty();
    }

    @Test void batchFailureKeepsExactOwnerRowsWithoutScalarFallbackAndUnknownCalendarFailsClosed() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("history unavailable")).when(f.batch).findRecentPrices(any(), anyInt());
        doReturn(Optional.empty()).when(f.calendar).isTwTradingDayCachedOnly(any());
        doReturn(Optional.empty()).when(f.calendar).isTradingDayCachedOnly(eq("台股"), any());
        var panel = f.service.getStocksPanel("台股", NOW);
        assertThat(panel.data().stocks()).extracting(TradingRadarDto.ListStock::stockCode)
                .containsExactlyInAnyOrder("2330", "SAME");
        assertThat(panel.data().stocks()).allSatisfy(stock -> assertThat(stock.action()).isEqualTo("NO_TRADE"));
        assertThat(panel.data().market().stale()).isTrue();
        verifyNoInteractions(f.history, f.stockRepo);
        verify(f.prices, never()).getLive("2330", "台股");
        verify(f.prices, never()).getLive("SAME", "台股");
        verify(f.threshold).incomeThreshold();
        verifyNoExternalOrWrites(f);
    }

    @Test void oneEvaluationRunsCoreOnceAndAllCompactScalarsMatchTheSameFullDecision() throws Exception {
        Fixture f = new Fixture();
        var result = f.service.getStockEvaluation("2330", "台股", NOW);
        assertThat(result.market()).isEqualTo(f.service.getMarketPanel("台股", NOW).data().market());
        assertThat(result.summary().score()).isNotNull();
        assertThat(result.summary().shortScore()).isNotNull();
        assertThat(result.summary().swingScore()).isNotNull();
        for (var component : TradingRadarDto.ListStock.class.getRecordComponents()) {
            String name = component.getName();
            if (Set.of("fundamental", "weeklyIndicators", "dailyCandleAsOfDate").contains(name)) continue;
            assertThat(component.getAccessor().invoke(result.summary())).as(name)
                    .isEqualTo(TradingRadarDto.StockDecision.class.getMethod(name).invoke(result.stock()));
        }
        assertThat(result.summary().dailyCandleAsOfDate()).isEqualTo(result.stock().dailyCandle().asOfDate());
        assertThat(result.summary().weeklyIndicators()).isEqualTo(new TradingRadarDto.ListWeeklyIndicators(
                result.stock().weeklyIndicators().k(), result.stock().weeklyIndicators().d(),
                result.stock().weeklyIndicators().changePercent()));
        assertThat(result.summary().fundamental()).isEqualTo(new TradingRadarDto.ListFundamental(
                result.stock().fundamental().applicable(), result.stock().fundamental().coverage(),
                result.stock().fundamental().industryName(), result.stock().fundamental().industryRevenueYoyPct()));
        verify(f.engine).evaluateStock(any());
        verify(f.threshold).incomeThreshold();
        assertBatchPairs(f, Set.of(new TradingRadarListBatchRepository.Key("2330", "台股")));
        verifyNoInteractions(f.news, f.snapshotStore, f.stockRepo, f.history);
        verifyNoExternalOrWrites(f);
    }

    @Test void singleEvaluationRejectsUnauthenticatedInvalidOrUnownedTargetsBeforeAnalysis() {
        Fixture f = new Fixture();
        assertThatThrownBy(() -> f.service.getStockEvaluation("FOREIGN", "台股", NOW))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("找不到可分析標的");
        assertThatThrownBy(() -> f.service.getStockEvaluation("../bad", "台股", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        when(f.user.hasUser()).thenReturn(false);
        assertThatThrownBy(() -> f.service.getStockEvaluation("2330", "台股", NOW)).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> f.service.getStocksPanel("台股", NOW)).isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(f.engine, f.batch, f.tw, f.us, f.threshold, f.technicalCache);
    }

    @Test void singleEvaluationFailSoftRetainsOneCoherentPairAndThreeHorizons() {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("one evaluator failure")).when(f.engine).evaluateStock(any());
        var result = f.service.getStockEvaluation("SAME", "美股", NOW);
        assertThat(result.summary().market()).isEqualTo("美股");
        assertThat(result.stock().market()).isEqualTo("美股");
        assertThat(result.summary().action()).isEqualTo(result.stock().action()).isEqualTo("NO_TRADE");
        assertThat(result.summary().shortAction()).isEqualTo(result.stock().shortAction()).isNull();
        assertThat(result.summary().swingAction()).isEqualTo(result.stock().swingAction()).isNull();
        assertThat(result.summary().score()).isEqualTo(result.stock().score()).isNull();
        verify(f.engine).evaluateStock(any());
        verifyNoExternalOrWrites(f);
    }

    private static void assertBatchPairs(Fixture f, Set<TradingRadarListBatchRepository.Key> expected) {
        @SuppressWarnings("unchecked") ArgumentCaptor<Collection<TradingRadarListBatchRepository.Key>> keys =
                ArgumentCaptor.forClass(Collection.class);
        verify(f.batch).findRecentPrices(keys.capture(), eq(500));
        assertThat(keys.getValue()).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static void verifyNoExternalOrWrites(Fixture f) {
        verify(f.calendar, never()).isTwTradingDayKnown(any());
        verify(f.calendar, never()).isTradingDayKnown(anyString(), any());
        verify(f.calendar, never()).isTradingDay(anyString(), any());
        verify(f.prices, never()).refreshTradingRadarPrices();
        verify(f.technicalCache, never()).writePair(any());
        verify(f.technicalCache, never()).writeMarketLocal(any());
        verify(f.technicalCache, never()).readMarketLocal(anyString(), anyString());
    }

    private static final class Fixture {
        final StockPriceHistoryRepository history = mock(StockPriceHistoryRepository.class);
        final PriceQueryService prices = mock(PriceQueryService.class);
        final TwseIndexDailyHistoryRepository tw = mock(TwseIndexDailyHistoryRepository.class);
        final UsIndexDailyHistoryRepository us = mock(UsIndexDailyHistoryRepository.class);
        final AssetSnapshotRepository snapshots = mock(AssetSnapshotRepository.class);
        final StockAlertRepository alerts = mock(StockAlertRepository.class);
        final StockRepository stockRepo = mock(StockRepository.class);
        final MarketDataService calendar = mock(MarketDataService.class);
        final NewsHeadlineRepository news = mock(NewsHeadlineRepository.class);
        final TradingRadarSnapshotStore snapshotStore = mock(TradingRadarSnapshotStore.class);
        final CurrentUserContext user = mock(CurrentUserContext.class);
        final StockStyleThresholdProvider threshold = mock(StockStyleThresholdProvider.class);
        final TradingRadarListBatchRepository batch = mock(TradingRadarListBatchRepository.class);
        final FundamentalAnalysisService fundamental = mock(FundamentalAnalysisService.class);
        final TradingRadarRuleEngine engine = spy(new TradingRadarRuleEngine());
        final RadarTechnicalCachePort technicalCache = mock(RadarTechnicalCachePort.class);
        final RadarTechnicalFactPort facts = mock(RadarTechnicalFactPort.class);
        final TradingRadarMarketContextService context;
        final TradingRadarService service;
        Fixture() {
            when(user.hasUser()).thenReturn(true);
            when(user.getEffectiveUserId()).thenReturn(7L);
            when(threshold.incomeThreshold()).thenReturn(new BigDecimal("0.07"));
            when(calendar.isTwTradingDayCachedOnly(any())).thenAnswer(c -> Optional.of(weekday(c.getArgument(0))));
            when(calendar.isTradingDayCachedOnly(anyString(), any())).thenAnswer(c -> Optional.of(weekday(c.getArgument(1))));
            when(calendar.mostRecentCompletedUsTradingDay(any())).thenReturn(US_DATE);
            when(calendar.futureTradingSessionsCachedOnly(anyString(), any(), anyInt(), anyInt()))
                    .thenAnswer(c -> Optional.of(dates(((LocalDate)c.getArgument(1)).plusDays(45), 30).reversed()));
            var twRows = new ArrayList<TwseIndexDailyHistory>();
            var usRows = new ArrayList<UsIndexDailyHistory>();
            for (int i = 0; i < 500; i++) {
                BigDecimal close = BigDecimal.valueOf(1000 - i * 0.7);
                TwseIndexDailyHistory row = new TwseIndexDailyHistory();
                row.setTradingDate(dates(TW_DATE, 500).get(i));
                row.setClosePoint(close); row.setOpenPoint(close); row.setHighPoint(close.add(BigDecimal.ONE));
                row.setLowPoint(close.subtract(BigDecimal.ONE)); row.setTradeVolume(1000L + i);
                row.setTradeValue(BigDecimal.valueOf(100000 + i)); twRows.add(row);
                UsIndexDailyHistory ur = new UsIndexDailyHistory();
                ur.setIndexCode("IXIC"); ur.setTradingDate(dates(US_DATE, 500).get(i));
                ur.setClosePoint(close); ur.setOpenPoint(close); ur.setHighPoint(close.add(BigDecimal.ONE));
                ur.setLowPoint(close.subtract(BigDecimal.ONE)); ur.setVolume(1000L + i); usRows.add(ur);
            }
            when(tw.findTopNByOrderByTradingDateDesc(anyInt())).thenAnswer(c -> twRows.subList(0, c.getArgument(0)));
            when(tw.findTop60ByOrderByTradingDateDesc()).thenReturn(twRows.subList(0, 60));
            when(tw.findById(TW_DATE)).thenReturn(Optional.of(twRows.getFirst()));
            when(us.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt())).thenAnswer(c ->
                    "IXIC".equals(c.getArgument(0)) ? usRows.subList(0, c.getArgument(1)) : List.of());
            when(prices.getLive(anyString(), anyString())).thenReturn(Optional.empty());
            when(prices.displaySession("台股")).thenReturn(new PriceQueryService.DisplaySession(
                    PriceQueryService.DisplayPhase.AFTER_CLOSE, TW_DATE, TW_DATE));
            var held = List.of(holding("2330", "台股"), holding("SAME", "台股"), holding("SAME", "美股"));
            var snapshot = Optional.of(AssetSnapshot.builder().stocks(held).build());
            when(snapshots.findLatestWithStocks()).thenReturn(snapshot);
            when(snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(snapshot);
            List<Object[]> watched = List.of(new Object[]{"SAME", "台股"}, new Object[]{"AAPL", "美股"},
                    new Object[]{"VOD", "英股"}, new Object[]{"0000", "台股"});
            when(alerts.findDistinctStockCodeMarket()).thenReturn(watched);
            when(alerts.findDistinctStockCodeMarketByOwnerUserId(7L)).thenReturn(watched);
            when(batch.findStocks(any())).thenAnswer(c -> {
                Map<TradingRadarListBatchRepository.Key, Stock> out = new LinkedHashMap<>();
                for (TradingRadarListBatchRepository.Key key : c.<Collection<TradingRadarListBatchRepository.Key>>getArgument(0))
                    out.put(key, Stock.builder().code(key.code()).market(key.market()).name(key.market() + key.code())
                            .assetClass("STOCK").stockStyle("GROWTH").build());
                return out;
            });
            when(batch.findRecentPrices(any(), anyInt())).thenAnswer(c -> {
                Map<TradingRadarListBatchRepository.Key, List<StockPriceHistory>> out = new LinkedHashMap<>();
                for (TradingRadarListBatchRepository.Key key : c.<Collection<TradingRadarListBatchRepository.Key>>getArgument(0)) {
                    List<StockPriceHistory> rows = new ArrayList<>();
                    List<LocalDate> days = dates("台股".equals(key.market()) ? TW_DATE : US_DATE, 500);
                    for (int i = 0; i < 500; i++) {
                        BigDecimal close = BigDecimal.valueOf(300 - i * 0.3);
                        rows.add(StockPriceHistory.builder().stockCode(key.code()).market(key.market()).tradingDate(days.get(i))
                                .closePrice(close).openPrice(close).highPrice(close.add(BigDecimal.ONE))
                                .lowPrice(close.subtract(BigDecimal.ONE)).volume(1000L + i).closeSource("TWSE_MI_INDEX").build());
                    }
                    out.put(key, rows);
                }
                return out;
            });
            context = new TradingRadarMarketContextService(tw, us, mock(ExchangeRateHistoryRepository.class), news, calendar);
            var indicators = new TechnicalIndicatorService(history, prices, tw, us);
            var adjustments = new DistributionAdjustedPriceService();
            var dividends = mock(DividendEventEvidenceRepository.class);
            var treasury = mock(TreasuryYieldService.class);
            var features = mock(TradingRadarMarketFeaturePort.class);
            var beta = mock(BondYieldBetaEvidencePort.class);
            service = new TradingRadarService(engine, indicators, adjustments,
                    new RadarInputAssembler(indicators, adjustments, engine), mock(AssetClassifier.class), tw, us,
                    history, mock(StockDividendHistoryRepository.class), prices, new TaiexDisplayPriceService(prices, tw),
                    snapshots, alerts, stockRepo, calendar, context, fundamental, mock(EtfNavHistoryRepository.class),
                    snapshotStore, user, dividends, treasury, features, beta, threshold,
                    mock(EtfNavObservationRepository.class), new RadarTechnicalResolver(facts, technicalCache, new ObjectMapper()), null);
            service.setListBatchPreloader(new TradingRadarListBatchPreloader(batch, prices, dividends, fundamental,
                    threshold, beta, treasury, context, features));
        }
    }

    private static StockHolding holding(String code, String market) {
        return StockHolding.builder().stockCode(code).market(market).shares(BigDecimal.ONE).build();
    }
    private static boolean weekday(LocalDate date) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
    private static List<LocalDate> dates(LocalDate latest, int count) {
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = latest; dates.size() < count; d = d.minusDays(1)) if (weekday(d)) dates.add(d);
        return dates;
    }
}
