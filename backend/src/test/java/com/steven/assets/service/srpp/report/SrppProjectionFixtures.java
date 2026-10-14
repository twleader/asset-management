package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.repository.SrppReportRepository.Package;
import com.steven.assets.repository.SrppReportRepository.Scope;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Builds genuine FINAL v1 envelopes, then replaces only their already-valid module legs for Task497 tests. */
final class SrppProjectionFixtures {
    private SrppProjectionFixtures() {}
    record Leg(String symbol, String market, String action, Integer lots, String units) {}

    static Package packageWith(List<Leg> legs) { return packageWith(legs, "91", AT.minusSeconds(30)); }

    static Package packageWithoutQuoteAmount(List<Leg> legs) { return mutateQuote(packageWith(legs), "amount", null); }

    static Package packageWith(List<Leg> legs, String k, Instant sourceCapturedAt) {
        SrppReportRepository repository = mock(SrppReportRepository.class);
        when(repository.base(any(), any())).thenReturn(Optional.empty());
        when(repository.packageForState(any(), any(), any())).thenReturn(Optional.empty());
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());

        Frozen original = frozen(7, AT, "09:05", "projection-source-r1");
        ObjectNode input = original.input();
        ObjectNode source = input.withObject("sources").putObject("market:台股:0050");
        source.put("market", "台股").put("symbol", "0050");
        ObjectNode quote = source.putObject("quote");
        quote.put("stockCode", "0050").put("market", "台股").put("price", "100").put("amount", "200").put("premiumDiscountPct", "1");
        ObjectNode radar = source.putObject("radar").putObject("stock");
        radar.put("week52Position", "0.5").put("kValue", k).put("dValue", "81");
        radar.putObject("extendedIndicators").put("rsi5", "80").put("bias10", "8").put("wr9", "20");

        ObjectNode vector = JsonNodeFactory.instance.objectNode();
        input.path("sources").fields().forEachRemaining(entry -> {
            ObjectNode item = vector.putObject(entry.getKey());
            item.put("sourceId", entry.getKey()).put("revision", "projection-source-r1")
                    .put("capturedAt", entry.getKey().equals("market:台股:0050") ? sourceCapturedAt.toString() : AT.toString())
                    .put("sha256", SrppJcs.hash(entry.getValue())).put("coverage", "COMPLETE").put("validation", "VERIFIED");
        });
        Frozen capture = new Frozen(original.facts(), input, vector, original.assetSnapshotId(), original.assetGeneratedAt());
        Scope scope = new Scope(7, DATE, "09:05", supported().bundleHash(), SWAGGER, SrppReportModuleCalculator.VERSION);
        SrppReportPublisher publisher = new SrppReportPublisher(repository, AUTHORITY, new PureSrppReportCalculations(), MAPPER, transactions, "projection-test");
        Package initial = publisher.publish(scope, capture, supported(), extras(), states(7, "09:05"), AT).stream().filter(item -> item.consumer().equals("Codex")).findFirst().orElseThrow();

        ObjectNode root = (ObjectNode) SrppJcs.parseStrict(initial.content());
        for (JsonNode module : root.path("modules")) ((ObjectNode) module.path("data")).set("legs", JsonNodeFactory.instance.arrayNode());
        ArrayNode target = (ArrayNode) root.path("modules").get(0).path("data").path("legs");
        for (Leg spec : legs) target.add(leg(spec));
        return rehash(initial, root);
    }

    static Package mutateLeg(Package initial, int index, String field, String value) {
        ObjectNode root = (ObjectNode) SrppJcs.parseStrict(initial.content());
        ((ObjectNode) root.path("modules").get(0).path("data").path("legs").get(index)).put(field, value);
        return rehash(initial, root);
    }

    /** Changes a sealed source and regenerates its vector/hash bindings; null removes an unsupported source field. */
    static Package mutateQuote(Package initial, String field, String value) {
        ObjectNode root = (ObjectNode) SrppJcs.parseStrict(initial.content());
        ObjectNode snapshot = (ObjectNode) root.path("inputSnapshot");
        ObjectNode input = (ObjectNode) SrppJcs.parseStrict(snapshot.path("inputBundleJcs").asText());
        ObjectNode source = (ObjectNode) input.path("sources").path("market:台股:0050");
        ObjectNode quote = (ObjectNode) source.path("quote");
        if (value == null) quote.remove(field); else quote.put(field, value);
        ((ObjectNode) snapshot.path("sourceVector").path("market:台股:0050")).put("sha256", SrppJcs.hash(source));
        return resealInput(initial, root, input);
    }

    static Package mutateRadarStock(Package initial, String field, String value) {
        ObjectNode root = (ObjectNode) SrppJcs.parseStrict(initial.content());
        ObjectNode snapshot = (ObjectNode) root.path("inputSnapshot");
        ObjectNode input = (ObjectNode) SrppJcs.parseStrict(snapshot.path("inputBundleJcs").asText());
        ObjectNode source = (ObjectNode) input.path("sources").path("market:台股:0050");
        source.withObject("radar").withObject("stock").put(field, value);
        ((ObjectNode) snapshot.path("sourceVector").path("market:台股:0050")).put("sha256", SrppJcs.hash(source));
        return resealInput(initial, root, input);
    }

    static Package mutateIdentity(Package initial, String field, String value) {
        ObjectNode root = (ObjectNode) SrppJcs.parseStrict(initial.content());
        ((ObjectNode) root.path("identity")).put(field, value);
        if (!"tradingDate".equals(field) && !"reportSlot".equals(field)) return rehash(initial, root);
        ObjectNode snapshot = (ObjectNode) root.path("inputSnapshot");
        ObjectNode input = (ObjectNode) SrppJcs.parseStrict(snapshot.path("inputBundleJcs").asText());
        String inputField = "tradingDate".equals(field) ? "tradingDate" : "slot";
        String stateField = "tradingDate".equals(field) ? "tradingDate" : "reportSlot";
        input.put(inputField, value);
        ((ObjectNode) input.path("stateBefore")).put(stateField, value);
        ObjectNode coreState = (ObjectNode) input.path("sources").path("core-state");
        coreState.put(stateField, value);
        ((ObjectNode) snapshot.path("sourceVector").path("core-state")).put("sha256", SrppJcs.hash(coreState));
        return resealInput(initial, root, input);
    }

    static Package mutateCalculatorVersion(Package initial, String value) {
        ObjectNode root = (ObjectNode) SrppJcs.parseStrict(initial.content());
        ((ObjectNode) root.path("authorityRevision")).put("calculatorVersion", value);
        ObjectNode snapshot = (ObjectNode) root.path("inputSnapshot");
        ObjectNode input = (ObjectNode) SrppJcs.parseStrict(snapshot.path("inputBundleJcs").asText());
        ((ObjectNode) input.path("authority")).put("calculatorVersion", value);
        return resealInput(initial, root, input);
    }

    private static ObjectNode leg(Leg spec) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("symbol", spec.symbol()).put("market", spec.market()).put("strategy", "PROJECTION_TEST")
                .put("status", "ACTIONABLE_FOR_REPORT").put("action", spec.action());
        if (spec.lots() == null) node.putNull("lots"); else node.put("lots", spec.lots());
        if (spec.units() == null) node.putNull("units"); else node.put("units", spec.units());
        node.put("limitPrice", "100").put("amount", "200");
        return node;
    }

    private static Package rehash(Package initial, ObjectNode root) {
        root.remove("contentSha256");
        root.put("contentSha256", SrppJcs.hash(root));
        String content = SrppJcs.canonicalize(root);
        SrppReportEnvelopeValidator.validate(content);
        return new Package(initial.id(), initial.sharedId(), initial.scope(), initial.consumer(), initial.stateHash(), root.path("inputSnapshot").path("inputSnapshotSha256").asText(), content, root.path("contentSha256").asText(), initial.publishedAt());
    }

    private static Package resealInput(Package initial, ObjectNode root, ObjectNode input) {
        ObjectNode snapshot = (ObjectNode) root.path("inputSnapshot");
        String inputJcs = SrppJcs.canonicalize(input);
        snapshot.put("inputBundleJcs", inputJcs).put("inputSnapshotSha256", SrppJcs.sha256Hex(inputJcs));
        return rehash(initial, root);
    }
}
