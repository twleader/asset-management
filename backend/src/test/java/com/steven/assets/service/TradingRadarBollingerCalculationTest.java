package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TradingRadarBollingerCalculationTest {
    private final TradingRadarRuleEngine engine = new TradingRadarRuleEngine();
    private final RadarInputAssembler assembler = new RadarInputAssembler(
            new TechnicalIndicatorService(mock(StockPriceHistoryRepository.class), mock(PriceQueryService.class),
                    mock(TwseIndexDailyHistoryRepository.class), mock(UsIndexDailyHistoryRepository.class)),
            new DistributionAdjustedPriceService(), engine);

    @Test
    void penaltyIsBoundedAndOnlyAboveMiddleWithSmoothNarrowAndWideAttenuation() {
        assertThat(penalty("0.5", "10")).isZero();
        assertThat(penalty("-1", "10")).isZero();
        assertThat(penalty("1", "10")).isEqualTo(.25);
        assertThat(penalty("5", "10")).isEqualTo(.25);
        assertThat(penalty("1", "1")).isEqualTo(.125);
        assertThat(penalty("1", "40")).isEqualTo(.125);
        assertThat(penalty("1", "0")).isZero();
        assertThat(penalty("1", "-1")).isZero();
        assertThat(penalty("1", "2")).isCloseTo(penalty("1", "1.99999999"), offset());
        assertThat(penalty("1", "20")).isCloseTo(penalty("1", "20.00000001"), offset());
        List<String> risks = new ArrayList<>();
        assertThat(TradingRadarRuleEngine.bollingerAdjustedBias(null, observation("1", "10"), risks)).isNull();
        assertThat(risks).isEmpty();
        assertThat(TradingRadarRuleEngine.bollingerAdjustedBias(.2, null, risks)).isEqualTo(.2);
        assertThat(risks).anyMatch(value -> value.contains("資料不足"));
        risks.clear();
        assertThat(TradingRadarRuleEngine.bollingerAdjustedBias(-.9, observation("1", "10"), risks)).isEqualTo(-1);
        assertThat(risks).anyMatch(value -> value.contains("延伸扣分"));
    }

    @Test
    void productionUsesExistingBiasWeightAndCandidateRemainsExactlyUnchangedOnAllTracks() throws Exception {
        var prepared = assembler.assemble(series(300, 5), List.of(), false, 300, 241, new BigDecimal("110"));
        var original = input(prepared, null, true);
        var extended = input(prepared, observation("1", "10"), true);
        var baseline = engine.evaluateStock(original);
        var modified = engine.evaluateStock(extended);
        assertThat(modified.score()).isLessThan(baseline.score());
        assertThat(modified.shortScore()).isLessThan(baseline.shortScore());
        assertThat(modified.swingScore()).isLessThan(baseline.swingScore());
        assertThat(modified.risks()).anyMatch(value -> value.contains("布林") && value.contains("扣分"));
        assertThat(modified.shortRisks()).anyMatch(value -> value.contains("布林") && value.contains("扣分"));
        assertThat(modified.swingRisks()).anyMatch(value -> value.contains("布林") && value.contains("扣分"));
        assertThat(modified.reasons()).noneMatch(value -> value.contains("布林"));
        assertThat(TradingRadarRuleEngine.WEIGHT_TABLE.length).isEqualTo(23);
        assertThat(TradingRadarRuleEngine.WEIGHT_TABLE[7]).containsExactly(.06, .06, .06);
        assertThat(TradingRadarRuleEngine.SHORT_WEIGHT_SUM).isCloseTo(1, offset());
        assertThat(TradingRadarRuleEngine.SWING_WEIGHT_SUM).isCloseTo(1, offset());
        assertThat(TradingRadarRuleEngine.MEDIUM_WEIGHT_SUM).isCloseTo(1, offset());
        var parameters = RuleParameters.v13Candidate("BB_FROZEN_CHECK",
                new RuleParameters.ActionThresholds(75,55,40,25), new RuleParameters.ActionThresholds(75,55,40,25),
                new BigDecimal(".70"), new BigDecimal(".01"), new BigDecimal("2"), new BigDecimal("15"), Map.of());
        var context = new TradingRadarRuleEngine.CandidateContext(true, true, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, new BigDecimal(".02"));
        assertThat(engine.evaluateCandidate(extended, parameters, context))
                .isEqualTo(engine.evaluateCandidate(original, parameters, context));
        var below = input(prepared, observation("-1", "10"), true);
        var belowResult = engine.evaluateStock(below);
        assertThat(belowResult.score()).isEqualTo(baseline.score());
        assertThat(belowResult.action()).isEqualTo(baseline.action());
        // The final evidence gate still sees only candidate actions and horizon-local evidence.
        var gated = TradingRadarEvidenceGate.apply(modified.action(), modified.shortAction(), modified.swingAction(),
                true, null, null);
        assertThat(gated.mediumAction()).isNotEqualTo(TradingRadarRuleEngine.Action.BUY_CANDIDATE);
        assertThat(TradingRadarEvidenceGate.ACTION_POLICY_VERSION).isEqualTo("EVIDENCE_GATE_V1");
    }

    @Test
    void nullableSnapshotFieldRoundTripsAndHistoricalMissingFieldStaysNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RecordComponent[] fields = TradingRadarDto.StockDecision.class.getRecordComponents();
        Class<?>[] types = Arrays.stream(fields).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[fields.length];
        for (int i=0;i<fields.length;i++) {
            if (types[i]==boolean.class) values[i]=false;
            else if (types[i]==List.class) values[i]=List.of();
            else if (fields[i].getName().equals("bollinger")) values[i]=new TradingRadarDto.Bollinger(
                    "2026-09-15",20,2,bd("100"),bd("110"),bd("90"),bd(".8"),bd("20"));
        }
        var snapshot = TradingRadarDto.StockDecision.class.getDeclaredConstructor(types).newInstance(values);
        var node = mapper.valueToTree(snapshot);
        var current = mapper.treeToValue(node, TradingRadarDto.StockDecision.class);
        assertThat(current.bollinger()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(snapshot.bollinger());
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).remove("bollinger");
        assertThat(mapper.treeToValue(node, TradingRadarDto.StockDecision.class).bollinger()).isNull();
    }

    @Test
    void deterministicOfflineFixtureReplayRecordsThreeTrackScoreCandidateAndGatedChanges() throws Exception {
        int[] scores = new int[3], candidates = new int[3], finals = new int[3];
        int missing=0, samples=240;
        var csv = new StringBuilder("sample,track,oldScore,newScore,oldCandidate,newCandidate,oldFinal,newFinal\n");
        for (int sample=0;sample<samples;sample++) {
            var rows=series(sample % 20 == 0 ? 19 : 300, sample);
            var a=assembler.assemble(rows, List.of(), false, rows.size(),241, rows.getFirst().getClosePrice());
            var b=a.bollinger();
            if (b==null) missing++;
            boolean held=sample%2==0;
            var old=engine.evaluateStock(input(a,null,held));
            var now=engine.evaluateStock(input(a,b,held));
            var oldGate=TradingRadarEvidenceGate.apply(old.action(),old.shortAction(),old.swingAction(),held,null,null);
            var newGate=TradingRadarEvidenceGate.apply(now.action(),now.shortAction(),now.swingAction(),held,null,null);
            Integer[] oldScores={old.shortScore(),old.swingScore(),old.score()}, newScores={now.shortScore(),now.swingScore(),now.score()};
            Object[] oldActions={old.shortAction(),old.swingAction(),old.action()},newActions={now.shortAction(),now.swingAction(),now.action()};
            Object[] oldFinals={oldGate.shortAction(),oldGate.swingAction(),oldGate.mediumAction()},newFinals={newGate.shortAction(),newGate.swingAction(),newGate.mediumAction()};
            for(int track=0;track<3;track++) {
                if(!Objects.equals(oldScores[track],newScores[track]))scores[track]++;
                if(!Objects.equals(oldActions[track],newActions[track]))candidates[track]++;
                if(!Objects.equals(oldFinals[track],newFinals[track]))finals[track]++;
                csv.append(sample).append(',').append(List.of("SHORT","SWING","MEDIUM").get(track)).append(',')
                        .append(oldScores[track]).append(',').append(newScores[track]).append(',').append(oldActions[track]).append(',')
                        .append(newActions[track]).append(',').append(oldFinals[track]).append(',').append(newFinals[track]).append('\n');
                if (oldScores[track] == null) assertThat(newScores[track]).isNull();
                else assertThat(newScores[track]).isLessThanOrEqualTo(oldScores[track]);
            }
        }
        assertThat(Arrays.stream(scores).sum()).isPositive();
        Path dir=Path.of("target/t438-bollinger-replay"); Files.createDirectories(dir);
        Files.writeString(dir.resolve("observations.csv"),csv);
        var report=new LinkedHashMap<String,Object>();
        report.put("evidenceType","DETERMINISTIC_SYNTHETIC_OFFLINE_DIAGNOSTIC_NOT_PROFITABILITY_BACKTEST");
        report.put("samples",samples);report.put("trackOrder",List.of("SHORT","SWING","MEDIUM"));
        report.put("scoreChanged",scores);report.put("candidateChanged",candidates);report.put("finalChanged",finals);
        report.put("missingBollingerFallback",missing);report.put("ruleVersion",TradingRadarRuleEngine.RULE_VERSION);
        report.put("oldComparison","V19 original BIAS with Bollinger absent; V18 numerical score/action comparator");
        report.put("evidenceGate","Same missing-evidence gate on both; no real market accuracy or profit claim");
        Files.writeString(dir.resolve("summary.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private TradingRadarRuleEngine.StockInput input(RadarInputAssembler.Assembled a, TradingRadarRuleEngine.BollingerInput b, boolean held) {
        return new TradingRadarRuleEngine.StockInput(held,a.adjustedRowsDesc().getFirst().getClosePrice(),a.ruleChangePercent(),a.completedChangePercent(),
                assembler.indicators(a.indicators()),a.indicators().previousK(),a.indicators().previousD(),
                a.ma20Confirmation(),a.ma60Confirmation(),a.ma240Confirmation(),TradingRadarRuleEngine.InstrumentType.EQUITY,
                TradingRadarRuleEngine.MarketRegime.NEUTRAL,false,null,a.ma60BiasPercent(),a.ma60BiasPercentile(),a.ma240BiasPercent(),
                a.week52Position(),a.kdBandWidthPercent(),null,null,a.indicators().weeklyMa(),
                assembler.extendedIndicators(a.indicators().extended()),a.volumeRatio(),TradingRadarRuleEngine.FundamentalInput.NOT_APPLICABLE,
                a.dailyCandle(),a.weekly(),b);
    }
    private static List<StockPriceHistory> series(int count,int phase) {
        var rows=new ArrayList<StockPriceHistory>();
        for(int i=0;i<count;i++) {
            double price=100+8*Math.sin((phase-i)*.16)+.015*(count-i);
            BigDecimal close=BigDecimal.valueOf(price).setScale(4,java.math.RoundingMode.HALF_UP);
            rows.add(StockPriceHistory.builder().stockCode("2330").market("台股").tradingDate(LocalDate.of(2026,9,15).minusDays(i))
                    .closePrice(close).openPrice(close.subtract(bd(".2"))).highPrice(close.add(bd("1"))).lowPrice(close.subtract(bd("1")))
                    .volume(10000L+(phase%5)*1000L).build());
        }
        return rows;
    }
    private static double penalty(String pct,String width){ return TradingRadarRuleEngine.bollingerExtensionPenalty(observation(pct,width)); }
    private static TradingRadarRuleEngine.BollingerInput observation(String pct,String width){return new TradingRadarRuleEngine.BollingerInput(LocalDate.of(2026,9,15),20,2,bd("100"),bd("110"),bd("90"),bd(pct),bd(width));}
    private static BigDecimal bd(String value){return new BigDecimal(value);}
    private static org.assertj.core.data.Offset<Double> offset(){return org.assertj.core.data.Offset.offset(1E-8);}
}
