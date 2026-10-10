package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.assertj.core.api.Assertions.assertThat;

/** Same frozen input compares current final actions with the added pure gate, without fabricated P&L. */
class RadarIntradayCandleAbReplayTest {
    @Test void frozenSourceFixtureComparesActionsAndExplicitlyLeavesPerformanceCostsAndHoldoutUnevaluated() throws Exception {
        var json = new ObjectMapper();
        byte[] bytes;
        try (var resource = getClass().getResourceAsStream("/radar-intraday-ab-v1.json")) { bytes = resource.readAllBytes(); }
        var fixture = json.readTree(bytes);
        var report = json.createObjectNode();
        report.put("fixtureId", fixture.get("fixtureId").asText());
        report.put("inputSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        for (String field : new String[]{"evidenceKind", "sourceTimeEvidence", "fees", "slippage", "independentHoldout", "missedProfitableOpportunities", "profitability"}) report.set(field, fixture.get(field));
        var rows = report.putArray("comparisons"); int changed = 0;
        for (var scenario : fixture.get("scenarios")) {
            var raw = fixture.get("capture");
            var candles = new java.util.ArrayList<RadarIntradayCandlePort.Candle>();
            for (var bar : raw.get("candles")) candles.add(new RadarIntradayCandlePort.Candle(
                    java.time.Instant.parse(bar.get("candleAt").asText()), new java.math.BigDecimal(bar.get("open").asText()),
                    new java.math.BigDecimal(bar.get("high").asText()), new java.math.BigDecimal(bar.get("low").asText()),
                    new java.math.BigDecimal(bar.get("close").asText()), bar.get("volume").asLong()));
            Boolean calendar = true;
            switch (scenario.get("patch").asText()) {
                case "MISSING_MINUTE_7" -> candles.remove(7);
                case "WEAK_MINUTE_11" -> { var bar = candles.get(11); candles.set(11, new RadarIntradayCandlePort.Candle(bar.candleAt(), bar.open(), bar.high(), new java.math.BigDecimal("108"), new java.math.BigDecimal("109"), bar.volume())); }
                case "ZERO_PRIOR_VOLUME" -> { for (int i=0;i<5;i++) { var bar=candles.get(i); candles.set(i,new RadarIntradayCandlePort.Candle(bar.candleAt(),bar.open(),bar.high(),bar.low(),bar.close(),0)); } }
                case "UNKNOWN_CALENDAR" -> calendar = null;
                default -> { }
            }
            var cap = scenario.get("patch").asText().equals("MISSING_CAPTURE") ? null
                    : new RadarIntradayCandlePort.Capture(raw.get("stockCode").asText(), raw.get("market").asText(),
                            raw.get("provider").asText(), raw.get("status").asText(), null,
                            java.time.LocalDate.parse(raw.get("sourceDate").asText()),
                            java.time.Instant.parse(raw.get("requestStartedAt").asText()),
                            java.time.Instant.parse(raw.get("capturedAt").asText()),
                            java.time.Instant.parse(raw.get("lastCompletedAt").asText()), candles);
            var confirmation = RadarIntradayCandlePolicy.evaluate("台股", TradingRadarRuleEngine.Horizon.SHORT,
                    java.time.Instant.parse(fixture.get("decisionInstant").asText()), calendar, cap);
            var before = new TradingRadarEvidenceGate.GatedActions(
                    TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                    TradingRadarRuleEngine.Action.valueOf(scenario.get("beforeShortAction").asText()),
                    TradingRadarRuleEngine.Action.ADD_CANDIDATE, java.util.List.of(), java.util.List.of(), java.util.List.of(),
                    TradingRadarRuleEngine.Action.BUY_CANDIDATE,
                    TradingRadarRuleEngine.Action.valueOf(scenario.get("candidateShortAction").asText()),
                    TradingRadarRuleEngine.Action.ADD_CANDIDATE);
            var after = RadarIntradayCandlePolicy.apply(before, confirmation, scenario.get("held").asBoolean());
            assertThat(confirmation.status()).isEqualTo(scenario.get("expectedStatus").asText());
            assertThat(after.shortAction().name()).isEqualTo(scenario.get("expectedShortAction").asText());
            assertThat(after.mediumAction()).isEqualTo(before.mediumAction()); assertThat(after.swingAction()).isEqualTo(before.swingAction());
            assertThat(after.candidateShortAction()).isEqualTo(before.candidateShortAction());
            if (before.shortAction()!=after.shortAction()) changed++;
            var row=rows.addObject();row.put("scenario",scenario.get("name").asText());row.put("before",before.shortAction().name());row.put("after",after.shortAction().name());row.put("candidateBefore",before.candidateShortAction().name());row.put("candidateAfter",after.candidateShortAction().name());row.put("confirmation",confirmation.status());row.put("reason",confirmation.reason());
        }
        report.put("changedShortActions", changed); report.put("unchangedMediumAndSwing", true);
        report.put("accuracyImprovementProven", false);
        assertThat(changed).isEqualTo(5);
        for(String field:new String[]{"fees","slippage","profitability","independentHoldout","missedProfitableOpportunities"}) assertThat(report.get(field).asText()).isEqualTo("NOT_EVALUATED");
        Path output=Path.of("target", "intraday-candle-ab-replay.json"); Files.createDirectories(output.getParent());
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
    }
}
