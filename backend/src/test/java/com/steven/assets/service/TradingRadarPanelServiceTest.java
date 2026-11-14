package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.steven.assets.service.TradingRadarRuleEngine.Action.*;

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
        assertThat(tw.ruleVersion()).isEqualTo("TW_RULES_V28");
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
        assertThat(result.summary().horizonConflict()).isFalse();
        assertThat(result.stock().horizonConflict()).isFalse();
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

    @Test void oneFrozenMinuteBatchFeedsCompactAndFullReceiptWithoutPerStockReload() {
        Fixture f = new Fixture();
        Instant now = Instant.parse("2026-09-23T01:14:00Z");
        Instant open = Instant.parse("2026-09-23T01:00:00Z");
        List<RadarIntradayCandlePort.Candle> bars = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            BigDecimal value = BigDecimal.valueOf(100 + i);
            bars.add(new RadarIntradayCandlePort.Candle(open.plusSeconds(60L*i), value,
                    value.add(new BigDecimal("2")), value.subtract(BigDecimal.ONE), value.add(BigDecimal.ONE), 100));
        }
        var cap = new RadarIntradayCandlePort.Capture("2330", "台股", "FUBON_SDK", "AVAILABLE", null,
                TW_DATE, now.minusSeconds(10), now.minusSeconds(1), open.plusSeconds(720), bars);
        RadarIntradayCandlePort port = mock(RadarIntradayCandlePort.class);
        when(port.read(anyList(), eq(TW_DATE), eq(now))).thenReturn(Map.of("2330", cap));
        f.service.setIntradayCandles(port);
        var result = f.service.getStockEvaluation("2330", "台股", now);
        assertThat(result.summary().intradayCandleConfirmation()).isNotNull();
        assertThat(result.summary().intradayCandleConfirmation()).isEqualTo(result.stock().intradayCandleConfirmation());
        assertThat(result.stock().intradayCandleConfirmation().status()).isEqualTo("CONFIRMED");
        verify(port).read(List.of("2330"), TW_DATE, now);
        verifyNoMoreInteractions(port);
        verify(f.engine).evaluateStock(any());
        assertThat(result.stock().shortReasons()).anyMatch(reason -> reason.contains("盤中價量已確認"));
        assertThat(result.stock().reasons()).noneMatch(reason -> reason.contains("盤中價量"));
    }

    @Test void compactPreloadsOneBatchAndUnknownCalendarNeverReadsMinutesOrPretendsNotApplicable() {
        Fixture f = new Fixture();
        Instant now = Instant.parse("2026-09-23T01:14:00Z");
        RadarIntradayCandlePort port = mock(RadarIntradayCandlePort.class);
        when(port.read(anyList(), eq(TW_DATE), eq(now))).thenReturn(Map.of());
        f.service.setIntradayCandles(port);
        var panel = f.service.getStocksPanel("台股", now);
        assertThat(panel.data().stocks()).allSatisfy(row ->
                assertThat(row.intradayCandleConfirmation().status()).isEqualTo("UNAVAILABLE"));
        verify(port).read(List.of("2330", "SAME"), TW_DATE, now);
        verifyNoMoreInteractions(port);
        clearInvocations(port);
        doReturn(Optional.empty()).when(f.calendar).isTwTradingDayCachedOnly(any());
        doReturn(Optional.empty()).when(f.calendar).isTradingDayCachedOnly(eq("台股"), any());
        var unknown = f.service.getStockEvaluation("2330", "台股", now);
        assertThat(unknown.stock().intradayCandleConfirmation().status()).isEqualTo("UNAVAILABLE");
        assertThat(unknown.stock().intradayCandleConfirmation().reason()).isEqualTo("MARKET_CALENDAR_UNAVAILABLE");
        verifyNoInteractions(port);
    }

    @Test void missingShortMinutesCreatesFinalConflictOnSingleEvaluationListAndPanel() {
        Fixture f = new Fixture();
        var raw = forceRawActions(f, TRIAL_BUY, TRIAL_BUY, TRIAL_BUY);
        var evidence = completeEvidence();
        Instant now = Instant.parse("2026-09-23T01:14:00Z");
        RadarIntradayCandlePort minutes = mock(RadarIntradayCandlePort.class);
        when(minutes.read(anyList(), eq(TW_DATE), eq(now))).thenReturn(Map.of());
        f.service.setIntradayCandles(minutes);
        try (var ignored = fixedEvidence(evidence)) {
            var evaluation = f.service.getStockEvaluation("2330", "台股", now);
            assertThat(raw.get().horizonConflict()).isFalse();
            assertFinalProjection(evaluation, raw.get(), "TRIAL_BUY", "HOLD", "TRIAL_BUY", true);
            assertThat(evaluation.stock().intradayCandleConfirmation().reason()).isEqualTo("CAPTURE_MISSING");
            assertThat(evaluation.stock().shortRisks()).anyMatch(r -> r.contains("暫緩短線買進"));
            assertThat(evaluation.stock().risks()).noneMatch(r -> r.contains("暫緩短線買進"));
            assertThat(evaluation.stock().swingRisks()).noneMatch(r -> r.contains("暫緩短線買進"));
            assertThat(evaluation.stock().actionGateReasons()).containsAll(
                    TradingRadarEvidenceGate.apply(TRIAL_BUY, TRIAL_BUY, TRIAL_BUY,
                            true, null, evidence).reasons());
            assertCompactProjection(f.service.getList(now).stocks(), "TRIAL_BUY", "HOLD", "TRIAL_BUY", true);
            assertCompactProjection(f.service.getStocksPanel("台股", now).data().stocks(),
                    "TRIAL_BUY", "HOLD", "TRIAL_BUY", true);
        }
        verifyNoExternalOrWrites(f);
    }

    @Test void evidenceDowngradeRemovesRawConflictWithoutChangingCandidatesOrScores() {
        Fixture f = new Fixture();
        var raw = forceRawActions(f, TRIAL_BUY, HOLD, TRIAL_BUY);
        try (var ignored = fixedEvidence(TradingRadarEvidenceConfidenceResolver.Evidence.EMPTY)) {
            var evaluation = f.service.getStockEvaluation("2330", "台股", NOW);
            assertThat(raw.get().horizonConflict()).isTrue();
            assertFinalProjection(evaluation, raw.get(), "HOLD", "HOLD", "HOLD", false);
            assertThat(evaluation.stock().risks()).anyMatch(r -> r.contains("中期 evidence gate 未達"));
            assertThat(evaluation.stock().swingRisks()).anyMatch(r -> r.contains("1周~1月 evidence gate 未達"));
            assertThat(evaluation.stock().actionGateReasons()).anyMatch(r -> r.contains("中期 evidence gate 未達"));
            assertCompactProjection(f.service.getList(NOW).stocks(), "HOLD", "HOLD", "HOLD", false);
        }
        verifyNoExternalOrWrites(f);
    }

    @Test void officialSma20IsAppliedBeforeFinalConflictOnBothProjections() {
        Fixture f = new Fixture();
        var raw = forceRawActions(f, TRIAL_BUY, HOLD, TRIAL_BUY);
        f.service.setOfficialSma20Facts(f.facts);
        when(f.facts.findOfficialSma20(anyMap())).thenReturn(Map.of("2330",
                new RadarTechnicalFactPort.OfficialSma20(TW_DATE, new BigDecimal("999"), NOW)));
        try (var ignored = fixedEvidence(completeEvidence())) {
            var evaluation = f.service.getStockEvaluation("2330", "台股", NOW);
            assertThat(raw.get().horizonConflict()).isTrue();
            assertThat(evaluation.stock().officialSma20Verification().status()).isEqualTo("CONFLICT");
            assertThat(evaluation.stock().officialSma20Verification().gateApplied()).isTrue();
            assertFinalProjection(evaluation, raw.get(), "HOLD", "HOLD", "HOLD", false);
            assertThat(evaluation.stock().risks()).anyMatch(r -> r.contains("富邦原始 SMA20"));
            assertThat(evaluation.stock().swingRisks()).anyMatch(r -> r.contains("富邦原始 SMA20"));
            assertThat(evaluation.stock().shortRisks()).noneMatch(r -> r.contains("富邦原始 SMA20"));
            assertCompactProjection(f.service.getList(NOW).stocks(), "HOLD", "HOLD", "HOLD", false);
        }
        verifyNoExternalOrWrites(f);
    }

    @Test void differentNeutralActionsStayInOneGroupAndMissingSwingIsNeverInferred() {
        Fixture neutral = new Fixture();
        var rawNeutral = forceRawActions(neutral, HOLD_CAUTION, WAIT, WATCH);
        try (var ignored = fixedEvidence(completeEvidence())) {
            assertFinalProjection(neutral.service.getStockEvaluation("2330", "台股", NOW), rawNeutral.get(),
                    "HOLD_CAUTION", "WAIT", "WATCH", false);
            assertCompactProjection(neutral.service.getList(NOW).stocks(), "HOLD_CAUTION", "WAIT", "WATCH", false);
        }
        Fixture missing = new Fixture();
        var rawMissing = forceRawActions(missing, HOLD, HOLD, null);
        try (var ignored = fixedEvidence(completeEvidence())) {
            assertFinalProjection(missing.service.getStockEvaluation("2330", "台股", NOW), rawMissing.get(),
                    "HOLD", "HOLD", null, true);
            assertCompactProjection(missing.service.getList(NOW).stocks(), "HOLD", "HOLD", null, true);
        }
        verifyNoExternalOrWrites(neutral);
        verifyNoExternalOrWrites(missing);
    }

    @Test void unchangedBuyDirectionsKeepFalseAndPreserveAllThreeDistinctCandidates() {
        Fixture f = new Fixture();
        var raw = forceRawActions(f, BUY_CANDIDATE, ADD_CANDIDATE, TRIAL_BUY);
        try (var ignored = fixedEvidence(completeEvidence())) {
            assertFinalProjection(f.service.getStockEvaluation("2330", "台股", NOW), raw.get(),
                    "BUY_CANDIDATE", "ADD_CANDIDATE", "TRIAL_BUY", false);
            assertCompactProjection(f.service.getList(NOW).stocks(),
                    "BUY_CANDIDATE", "ADD_CANDIDATE", "TRIAL_BUY", false);
        }
        verifyNoExternalOrWrites(f);
    }

    /** Fixed candidate inputs isolate projection wiring; all three safety gates remain real. */
    private static AtomicReference<TradingRadarRuleEngine.StockResult> forceRawActions(Fixture f,
            TradingRadarRuleEngine.Action medium, TradingRadarRuleEngine.Action shortTerm,
            TradingRadarRuleEngine.Action swing) {
        var captured = new AtomicReference<TradingRadarRuleEngine.StockResult>();
        doAnswer(call -> {
            TradingRadarRuleEngine.StockResult actual = (TradingRadarRuleEngine.StockResult) call.callRealMethod();
            var fixed = new TradingRadarRuleEngine.StockResult(actual.score(), medium, actual.counterTrend(),
                    actual.reasons(), actual.risks(), actual.kdHeat(), actual.timingState(), actual.kdDeadCross(),
                    actual.longTermBroken(), actual.shortScore(), shortTerm, actual.shortReasons(), actual.shortRisks(),
                    TradingRadarRuleEngine.horizonConflict(medium, swing, shortTerm), actual.profitTakingConfirmed(),
                    actual.normalizedBias(), actual.shortNormalizedBias(), actual.swingScore(), swing,
                    actual.swingReasons(), actual.swingRisks());
            captured.set(fixed);
            return fixed;
        }).when(f.engine).evaluateStock(any());
        return captured;
    }

    /** Only observation quality is controlled; gate logic and emitted diagnostics are not mocked. */
    private static MockedStatic<TradingRadarEvidenceConfidenceResolver> fixedEvidence(
            TradingRadarEvidenceConfidenceResolver.Evidence evidence) {
        var resolver = mockStatic(TradingRadarEvidenceConfidenceResolver.class, CALLS_REAL_METHODS);
        resolver.when(() -> TradingRadarEvidenceConfidenceResolver.resolve(
                any(TradingRadarEvidenceConfidenceResolver.Inputs.class), nullable(LocalDate.class),
                nullable(LocalDate.class))).thenReturn(evidence);
        return resolver;
    }

    private static TradingRadarEvidenceConfidenceResolver.Evidence completeEvidence() {
        var groups = new EnumMap<TradingRadarEvidenceConfidenceResolver.Group,
                TradingRadarEvidenceConfidenceResolver.GroupEvidence>(TradingRadarEvidenceConfidenceResolver.Group.class);
        for (var group : TradingRadarEvidenceConfidenceResolver.Group.values()) {
            groups.put(group, new TradingRadarEvidenceConfidenceResolver.GroupEvidence(group, List.of(),
                    1, 1, 1, true, true, true, true, true, true, 1, false, true));
        }
        var risk = new TradingRadarEvidenceConfidenceResolver.Risk(10, 1, List.of());
        return new TradingRadarEvidenceConfidenceResolver.Evidence(groups, 100, 100, 100,
                risk, risk, risk, List.of());
    }

    private static void assertFinalProjection(com.steven.assets.dto.TradingRadarPanelDto.StockEvaluation evaluation,
            TradingRadarRuleEngine.StockResult raw, String medium, String shortTerm, String swing, boolean conflict) {
        var stock = evaluation.stock();
        var summary = evaluation.summary();
        assertThat(stock.action()).isEqualTo(medium);
        assertThat(stock.shortAction()).isEqualTo(shortTerm);
        assertThat(stock.swingAction()).isEqualTo(swing);
        assertThat(stock.horizonConflict()).isEqualTo(conflict);
        assertThat(summary.action()).isEqualTo(medium);
        assertThat(summary.shortAction()).isEqualTo(shortTerm);
        assertThat(summary.swingAction()).isEqualTo(swing);
        assertThat(summary.horizonConflict()).isEqualTo(conflict);
        assertThat(stock.score()).isEqualTo(raw.score());
        assertThat(stock.shortScore()).isEqualTo(raw.shortScore());
        assertThat(stock.swingScore()).isEqualTo(raw.swingScore());
        assertThat(summary.score()).isEqualTo(raw.score());
        assertThat(summary.shortScore()).isEqualTo(raw.shortScore());
        assertThat(summary.swingScore()).isEqualTo(raw.swingScore());
        assertThat(stock.candidateAction()).isEqualTo(raw.action().name());
        assertThat(stock.shortCandidateAction()).isEqualTo(raw.shortAction().name());
        assertThat(stock.swingCandidateAction()).isEqualTo(raw.swingAction() == null ? null : raw.swingAction().name());
        assertThat(stock.reasons()).containsAll(raw.reasons());
        assertThat(stock.shortReasons()).containsAll(raw.shortReasons());
        assertThat(stock.swingReasons()).containsAll(raw.swingReasons());
        assertThat(stock.risks()).containsAll(raw.risks());
        assertThat(stock.shortRisks()).containsAll(raw.shortRisks());
        assertThat(stock.swingRisks()).containsAll(raw.swingRisks());
    }

    private static void assertCompactProjection(List<TradingRadarDto.ListStock> stocks,
            String medium, String shortTerm, String swing, boolean conflict) {
        var stock = stocks.stream().filter(row -> "2330".equals(row.stockCode()) && "台股".equals(row.market()))
                .findFirst().orElseThrow();
        assertThat(stock.action()).isEqualTo(medium);
        assertThat(stock.shortAction()).isEqualTo(shortTerm);
        assertThat(stock.swingAction()).isEqualTo(swing);
        assertThat(stock.horizonConflict()).isEqualTo(conflict);
    }

    @Test void v27RealSelectedServiceUsesCanonicalPeakAndShowsStrongLabelOnlyAfterAllGates() {
        Fixture f = accumulationFixture("00881", "台股", new BigDecimal("80"), false);
        try (var ignored = fixedEvidence(completeEvidence())) {
            var evaluation = f.service.getStockEvaluation("00881", "台股", NOW);
            assertThat(evaluation.stock().action()).isEqualTo("ADD_CANDIDATE");
            assertThat(evaluation.stock().actionLabel()).isEqualTo("積極分批加碼候選");
            assertThat(evaluation.summary().actionLabel()).isEqualTo(evaluation.stock().actionLabel());
            assertThat(evaluation.stock().reasons()).anyMatch(r -> r.contains("52週完成窗口") && r.contains("00881"));
            assertThat(evaluation.stock().reasons()).noneMatch(r -> r.contains("仍須等待止跌才可買進"));
            assertThat(evaluation.stock().reasons()).anyMatch(r -> r.contains("不等待短線止穩"));
            var list = f.service.getList(NOW).stocks().getFirst();
            assertThat(list.actionLabel()).isEqualTo("積極分批加碼候選");
            assertThat(list.horizonConflict()).isEqualTo(TradingRadarRuleEngine.horizonConflict(
                    TradingRadarRuleEngine.Action.valueOf(list.action()),
                    TradingRadarRuleEngine.Action.valueOf(list.swingAction()),
                    TradingRadarRuleEngine.Action.valueOf(list.shortAction())));
            f.service.setOfficialSma20Facts(f.facts);
            when(f.facts.findOfficialSma20(anyMap())).thenReturn(Map.of("00881",
                    new RadarTechnicalFactPort.OfficialSma20(TW_DATE, new BigDecimal("999"), NOW)));
            var blocked = f.service.getStockEvaluation("00881", "台股", NOW);
            assertThat(blocked.stock().officialSma20Verification().status()).isEqualTo("CONFLICT");
            assertThat(blocked.stock().action()).isEqualTo("HOLD");
            assertThat(blocked.stock().candidateAction()).isEqualTo("ADD_CANDIDATE");
            assertThat(blocked.stock().actionLabel()).doesNotContain("積極");
            assertThat(blocked.summary().actionLabel()).isEqualTo(blocked.stock().actionLabel());
        }
        try (var ignored = fixedEvidence(TradingRadarEvidenceConfidenceResolver.Evidence.EMPTY)) {
            var blocked = f.service.getStockEvaluation("00881", "台股", NOW);
            assertThat(blocked.stock().action()).isEqualTo("HOLD");
            assertThat(blocked.stock().actionLabel()).doesNotContain("積極");
        }
        verifyNoExternalOrWrites(f);
    }

    @Test void v27UsPriceOnlyFallbackHasReachableRealServiceOpportunityAndExactIdentity() {
        Fixture f = accumulationFixture("NVDA", "美股", new BigDecimal("70"), true);
        try (var ignored = fixedEvidence(completeEvidence())) {
            var evaluation = f.service.getStockEvaluation("NVDA", "美股", NOW);
            assertThat(evaluation.stock().action()).isEqualTo("ADD_CANDIDATE");
            assertThat(evaluation.stock().actionLabel()).isEqualTo("積極分批加碼候選");
            assertThat(evaluation.stock().reasons()).anyMatch(r -> r.contains("NVDA") && r.contains("STORED_PRICE_ONLY"));
            assertThat(evaluation.stock().risks()).anyMatch(r -> r.contains("未做配息還原"));
            ArgumentCaptor<TradingRadarRuleEngine.StockInput> capture = ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
            verify(f.engine).evaluateStock(capture.capture());
            assertThat(capture.getValue().usdFxPurchase().data().quality()).isEqualTo("LIVE");
            assertThat(capture.getValue().usdFxPurchase().data().history().available()).isTrue();
            var fact = capture.getValue().accumulation();
            assertThat(capture.getValue().completedChangePercent()).isNegative();
            assertThat(capture.getValue().ma20Confirmation()).isEqualTo(TradingRadarRuleEngine.Confirmation.BELOW);
            assertThat(capture.getValue().structure().medium().stabilized()).isFalse();
            assertThat(fact.market()).isEqualTo("美股");
            assertThat(fact.stockCode()).isEqualTo("NVDA");
            assertThat(fact.completedAsOfDate()).isEqualTo(US_DATE);
            assertThat(fact.high()).isEqualByComparingTo("100");
            assertThat(fact.eligible(capture.getValue().price())).isTrue();
        }
        verifyNoExternalOrWrites(f);
    }

    private static Fixture accumulationFixture(String code, String market, BigDecimal price, boolean usCashEvent) {
        Fixture f = new Fixture();
        var held = Optional.of(AssetSnapshot.builder().stocks(List.of(holding(code, market))).build());
        when(f.snapshots.findLatestWithStocksByOwnerUserId(7L)).thenReturn(held);
        when(f.snapshots.findLatestWithStocks()).thenReturn(held);
        when(f.alerts.findDistinctStockCodeMarketByOwnerUserId(7L)).thenReturn(List.of());
        when(f.alerts.findDistinctStockCodeMarket()).thenReturn(List.of());
        if ("美股".equals(market)) {
            UsdTwdLiveRateService cachedFx = mock(UsdTwdLiveRateService.class);
            when(cachedFx.getCachedLiveRate()).thenReturn(new UsdTwdLiveRateService.LiveRate("USD/TWD", "USD", "TWD",
                    TW_DATE, new BigDecimal("30.9"), new BigDecimal("31"), "BANK_OF_TAIWAN", NOW, null, "ACTIVE", "LIVE"));
            f.context.setUsdTwdLiveRateService(cachedFx);
            var source = ExchangeRateSourceCapture.builder().id(1L).provider("FINMIND_TAIWAN_EXCHANGE_RATE")
                    .rateBasis("BANK_SPOT_SELLING").capturedAt(NOW.minusSeconds(1)).sourceSha256("a".repeat(64))
                    .sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date="
                            + TW_DATE.minusYears(2).minusDays(5) + "&end_date=" + US_DATE).build();
            List<ExchangeRateHistory> fxRows = new ArrayList<>();
            for (var point : Map.of(TW_DATE.minusYears(2), "30", TW_DATE.minusMonths(2), "33", US_DATE, "32").entrySet()) {
                var sell = new BigDecimal(point.getValue());
                var row = ExchangeRateHistory.builder().currency("USD").rateDate(point.getKey()).buyRate(sell.subtract(new BigDecimal("0.1")))
                        .sellRate(sell).sourceCapture(source).build();
                row.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha(source.getSourceSha256(), "USD", row.getRateDate(), row.getBuyRate(), sell));
                fxRows.add(row);
            }
            when(f.fxHistory.findByCurrencyInAndRateDateBetweenOrderByCurrencyAscRateDateAsc(any(), any(), any())).thenReturn(fxRows);
            when(f.fxHistory.findByCurrencyAndRateDateBetweenOrderByRateDateAsc(anyString(), any(), any())).thenReturn(fxRows);
        }
        LocalDate day = "台股".equals(market) ? TW_DATE : US_DATE;
        List<StockPriceHistory> rows = new ArrayList<>();
        var days = dates(day, 500);
        for (int i = 0; i < 500; i++) {
            BigDecimal close = i < 10 ? price.add(BigDecimal.valueOf(i)) : new BigDecimal("100");
            rows.add(StockPriceHistory.builder().stockCode(code).market(market).tradingDate(days.get(i))
                    .openPrice(close).highPrice(close.max(new BigDecimal("100")))
                    .lowPrice(close.subtract(BigDecimal.ONE)).closePrice(close).volume(1000L)
                    .closeSource("TWSE_MI_INDEX").build());
        }
        var key = new TradingRadarListBatchRepository.Key(code, market);
        doReturn(Map.of(key, rows)).when(f.batch).findRecentPrices(any(), anyInt());
        if (usCashEvent) {
            LocalDate ex = days.get(30);
            var event = StockDividendHistory.builder().id(1L).stockCode(code).market(market).canonicalDate(ex)
                    .exDividendDate(ex).cashDividend(BigDecimal.ONE).stockDividend(BigDecimal.ZERO)
                    .resolutionStatus("RESOLVED").sourceSnapshotId(1L).build();
            when(f.batch.findAdjustmentEvents(any(), anyMap())).thenReturn(Map.of(key, List.of(event)));
        }
        return f;
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
        final ExchangeRateHistoryRepository fxHistory = mock(ExchangeRateHistoryRepository.class);
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
            context = new TradingRadarMarketContextService(tw, us, fxHistory, news, calendar);
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
