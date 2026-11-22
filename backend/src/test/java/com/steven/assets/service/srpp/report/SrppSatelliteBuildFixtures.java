package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.service.srpp.decision.DecisionFacts.Policy;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SupportedPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.mockito.Mockito.*;

/** Task 623 enabled-bundle fixtures: the embedded policy plus the D-234 VT block, with test-only build dates covering DATE. */
public final class SrppSatelliteBuildFixtures {
    private SrppSatelliteBuildFixtures() {}

    /** D-234 cadence values exactly as the SRPP worktree authors them, except window/earliest dates moved to cover the fixture DATE. */
    public static JsonNode enabledModel() {
        ObjectNode model = (ObjectNode) AUTHORITY.model();
        ObjectNode cadence = (ObjectNode) model.path("execution_policy_overrides").path("global_equity_build_cadence_d141");
        ObjectNode lots = (ObjectNode) cadence.path("009826");
        lots.put("window", "2026-10-01 to 2027-06-30").put("earliest_buy_date", "2026-10-01").put("monthly_baseline_twd", 140000).put("monthly_cap_twd", 210000)
                .put("max_lots_per_day", 3).put("max_lots_per_week", 6).put("stop_at_weight", new BigDecimal("0.02"));
        ObjectNode vt = cadence.putObject("VT");
        vt.put("decision", "D-234").put("window", "2026-10-01 to 2027-06-30").put("earliest_buy_date", "2026-10-01").put("monthly_baseline_twd", 53333)
                .put("monthly_cap_twd", 80000).put("max_shares_per_day", 5).put("max_shares_per_week", 15).put("min_shares_per_order", 5)
                .put("whole_shares_only", true).put("stop_at_weight", new BigDecimal("0.025")).put("funding", "Cathay TWD sub-brokerage only.");
        cadence.put("fx_halving_threshold", new BigDecimal("33.0"));
        return model;
    }

    /** The current embedded policy without the D-234 VT block: the pre-D-234 (legacy) shape that must never read or freeze satellite sources. */
    public static JsonNode legacyModel() {
        ObjectNode model = (ObjectNode) AUTHORITY.model();
        ((ObjectNode) model.path("execution_policy_overrides").path("global_equity_build_cadence_d141")).remove("VT");
        return model;
    }

    /**
     * Exactly what SrppReportProducerScheduler.produce adds after the L0 capture when the bundle enables D-234: the real
     * SrppSatelliteBuildCapture over an empty ledger and an empty USD rate table, frozen by the scheduler's own
     * withSatelliteBuildSources. A model without the D-234 VT block returns the frame unchanged.
     */
    public static Frozen producerSatelliteSources(Frozen original, JsonNode model) {
        if (!SrppSatelliteBuildPolicy.enabled(model)) return original;
        ObjectNode input = original.input();
        var built = new SrppSatelliteBuildCapture(mock(AssetTransactionRepository.class), mock(ExchangeRateHistoryRepository.class))
                .capture(input.path("ownerUserId").asLong(), original.facts().date(), input.path("sources").path("policy").path("bundleHash").asText());
        return SrppReportProducerScheduler.withSatelliteBuildSources(original, built.fills(), built.mids());
    }

    public static ObjectNode book009826(Instant at) {
        ObjectNode book = JsonNodeFactory.instance.objectNode();
        book.put("stockCode", "009826").put("stockName", "fixture").put("market", "台股").put("supported", true).put("available", true).put("source", "FUBON_BOOKS")
                .put("sourceTime", at.toString()).put("fetchedAt", at.toString()).put("marketStatus", "OPEN").put("price", "10.05").put("previousClose", "10");
        ArrayNode levels = book.putArray("levels");
        for (int i = 0; i < 5; i++) levels.addObject().put("level", i + 1).put("bidPrice", SrppReportModuleCalculator.dec(new BigDecimal("10.04").subtract(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i)))))
                .put("bidVolumeLots", 10).put("askPrice", SrppReportModuleCalculator.dec(new BigDecimal("10.06").add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i))))).put("askVolumeLots", 10);
        return book;
    }

    /** Adds positive-result satellite sources (009826 3 lots at ASK1 10.06, VT 5 shares at ASK1 150) to any fixture frame. */
    public static Frozen withSatellite(Frozen original, Instant bookAt) {
        ObjectNode input = original.input().deepCopy(), vector = original.sourceVector().deepCopy();ObjectNode sources = input.withObject("sources");
        LocalDate date = original.facts().date();long owner = input.path("ownerUserId").asLong();String bundle = sources.path("policy").path("bundleHash").asText();
        ObjectNode tw = sources.putObject("market:台股:009826");tw.put("market", "台股").put("symbol", "009826");
        tw.putObject("quote").put("stockCode", "009826").put("market", "台股").put("tradingDate", date.toString()).put("price", "10.05");tw.set("book", book009826(bookAt));
        ObjectNode us = sources.putObject("market:美股:VT");us.put("market", "美股").put("symbol", "VT");
        us.putObject("quote").put("stockCode", "VT").put("market", "美股").put("tradingDate", date.minusDays(1).toString()).put("price", "149.9").put("sellPrice", "150");
        us.put("calendarVerified", true).putArray("completedSessions").add(date.minusDays(2).toString()).add(date.minusDays(1).toString());
        sources.putObject("fx").put("pair", "USD/TWD").put("date", date.toString()).put("buyRate", "32.4").put("sellRate", "32.5").put("quoteStatus", "LIVE");
        ExchangeRateHistory row = new ExchangeRateHistory();row.setCurrency("USD");row.setRateDate(date.withDayOfMonth(1));row.setBuyRate(new BigDecimal("32.3"));row.setSellRate(new BigDecimal("32.4"));
        sources.set(SrppSatelliteBuildCapture.MIDS, SrppSatelliteBuildCapture.midsSource(date, List.of(row)));
        sources.set(SrppSatelliteBuildCapture.FILLS, SrppSatelliteBuildCapture.fillsSource(owner, date, bundle, List.of()));
        for (String id : List.of("market:台股:009826", "market:美股:VT", "fx", SrppSatelliteBuildCapture.MIDS, SrppSatelliteBuildCapture.FILLS)) {
            String hash = SrppJcs.hash(sources.path(id));
            vector.putObject(id).put("sourceId", id).put("revision", hash).put("capturedAt", original.facts().capturedAt().toString()).put("sha256", hash).put("coverage", "COMPLETE").put("validation", "VERIFIED");
        }
        return new Frozen(original.facts(), input, vector, original.assetSnapshotId(), original.assetGeneratedAt());
    }

    /** Mutates a copy of the frozen sources and re-hashes every vector receipt. */
    public static Frozen mutate(Frozen original, java.util.function.Consumer<ObjectNode> change) {
        ObjectNode input = original.input(), vector = original.sourceVector();change.accept(input.withObject("sources"));
        input.path("sources").fields().forEachRemaining(e -> { if (vector.has(e.getKey())) ((ObjectNode) vector.path(e.getKey())).put("sha256", SrppJcs.hash(e.getValue())); });
        return new Frozen(original.facts(), input, vector, original.assetSnapshotId(), original.assetGeneratedAt());
    }

    public static Frozen withValidation(Frozen original, String id, String validation) {
        ObjectNode vector = original.sourceVector().deepCopy();((ObjectNode) vector.path(id)).put("validation", validation).put("coverage", "VERIFIED".equals(validation) ? "COMPLETE" : "NONE");
        return new Frozen(original.facts(), original.input().deepCopy(), vector, original.assetSnapshotId(), original.assetGeneratedAt());
    }

    /** The real publisher and validator over an in-memory repository; the calculation port evaluates the enabled model. */
    public static JsonNode publish(Frozen frozen, String slot, JsonNode model) { return publish(frozen, slot, model, extras()); }
    public static JsonNode publish(Frozen frozen, String slot, JsonNode model, JsonNode extra) {
        var repository = mock(SrppReportRepository.class);var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        SrppReportCalculationPort port = new SrppReportCalculationPort() {
            final PureSrppReportCalculations pure = new PureSrppReportCalculations();
            public ArrayNode shared(Frozen input, Policy policy, JsonNode ignored, SupportedPolicy supported, JsonNode extra) { return pure.shared(input, policy, model, supported, extra); }
            public ObjectNode core(Frozen input, JsonNode ignored, JsonNode state, String consumer) { return pure.core(input, model, state, consumer); }
            public ArrayNode compose(ArrayNode shared, ObjectNode core) { return pure.compose(shared, core); }
        };
        var publisher = new SrppReportPublisher(repository, AUTHORITY, port, MAPPER, manager, "sha256:" + "f".repeat(64));
        var scope = new SrppReportRepository.Scope(frozen.input().path("ownerUserId").asLong(), frozen.facts().date(), slot, supported().bundleHash(), SWAGGER, SrppReportModuleCalculator.VERSION);
        var saved = publisher.publish(scope, frozen, supported(), (ObjectNode) extra, states(scope.owner(), slot), frozen.facts().capturedAt());
        return SrppReportEnvelopeValidator.validate(saved.getFirst().content());
    }

    public static String content(Frozen frozen, String slot, JsonNode model) {
        return SrppJcs.canonicalize(publish(frozen, slot, model));
    }
    public static String content(Frozen frozen, String slot, JsonNode model, JsonNode extra) {
        return SrppJcs.canonicalize(publish(frozen, slot, model, extra));
    }

    public static JsonNode module(JsonNode envelope, String id) {
        for (JsonNode m : envelope.path("modules")) if (id.equals(m.path("moduleId").asText())) return m;
        throw new AssertionError(id);
    }

    public static JsonNode leg(JsonNode module, String symbol) {
        for (JsonNode leg : module.path("data").path("legs")) if (symbol.equals(leg.path("symbol").asText())) return leg;
        throw new AssertionError(symbol);
    }
}
