package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 163／Task 452.8：context 組裝與不變量。 */
class SrppContextInvariantsTest {
    static final Instant CAPTURED = Instant.parse("2026-09-24T01:05:01Z");
    static final UUID PACKAGE = UUID.fromString("7e1fb982-4ae4-4e7e-baa5-c2c80bd93491");
    static final UUID OWNER_KEY = UUID.fromString("0b7c6f0e-3f59-4ac4-9d34-5d3a6f1f6d10");

    static SrppCapture capture(SupportedPolicy policy) {
        var response = SrppTestData.proposal().build();
        String revision = SrppSnapshotRevision.of(response.snapshot());
        return new SrppCapture(7L, policy, SrppTestData.DATE, "09:05", response, revision, CAPTURED, List.of(
                new SrppSourceEvidence("policy", "POLICY", "policy-x", CAPTURED, policy.registeredAt(),
                        SrppSourceCapture.policyBody(policy)),
                new SrppSourceEvidence("assets", "ASSETS", revision, CAPTURED, CAPTURED.minusSeconds(30), "{\"a\":1}"),
                new SrppSourceEvidence("calendar", "CALENDAR", "calendar-2026-09-24-open", CAPTURED, CAPTURED,
                        SrppSourceCapture.calendarBody(SrppTestData.DATE))));
    }

    static ObjectNode context() {
        SupportedPolicy policy = SrppTestData.policy(Map.of("CASH:TWD:DEMAND", "0.4", "CASH:TWD:TERM", "0.4",
                "STOCK:台股:0050", "0.2"));
        SrppCapture capture = capture(policy);
        ObjectNode modules = SrppModuleCalculator.calculate(capture.assets(), policy, SrppTestData.DATE).modules();
        return SrppContextAssembler.assemble(PACKAGE, CAPTURED.plusMillis(400), OWNER_KEY, capture, modules);
    }

    @Test
    void assembledContextHasExactFieldsTypesAndPasses() {
        ObjectNode context = context();
        List<String> fields = new java.util.ArrayList<>();
        context.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactly("packageId", "schemaVersion", "generatedAt", "dataCutoffAt", "tradingDate",
                "slot", "timezone", "ownerKey", "assetSnapshotId", "assetSnapshotDate", "policy", "coverage", "sources",
                "modules", "tradingAuthorized", "requiresOriginalDailyChecks");
        assertThat(context.get("assetSnapshotId").isTextual()).isTrue();
        assertThat(context.get("assetSnapshotId").asText()).isEqualTo("1");
        assertThat(context.get("generatedAt").asText()).isEqualTo("2026-09-24T09:05:01+08:00");
        assertThat(context.get("coverage").asText()).isEqualTo("PARTIAL");
        assertThat(context.get("ownerKey").asText()).isEqualTo(OWNER_KEY.toString());
        assertThat(context.at("/policy/scope").asText()).isEqualTo("LISTED_CALCULATIONS_ONLY");
        assertThat(context.at("/policy/formulaSetSha256").asText()).isEqualTo(SrppFormulaCatalogTest.GOLDEN_FORMULA_SET_SHA256);
        assertThat(context.get("sources")).extracting(n -> n.get("sourceId").asText())
                .containsExactly("assets", "calendar", "policy");
        List<String> sourceFields = new java.util.ArrayList<>();
        context.at("/sources/0").fieldNames().forEachRemaining(sourceFields::add);
        assertThat(sourceFields).containsExactly("sourceId", "kind", "state", "revision", "capturedAt", "dataAsOf",
                "bodySha256", "reasonCodes");
        assertThat(context.at("/sources/0/dataAsOf").asText()).isEqualTo("2026-09-24T09:04:31+08:00");
        assertThat(context.get("tradingAuthorized").asBoolean()).isFalse();
        assertThat(context.get("requiresOriginalDailyChecks").asBoolean()).isTrue();
        assertThatCode(() -> SrppContextInvariants.verify(context)).doesNotThrowAnyException();
        assertThat(SrppJcs.canonicalize(context)).doesNotContain(" :");
    }

    private static void violates(Consumer<ObjectNode> mutation) {
        ObjectNode context = context();
        mutation.accept(context);
        assertThatThrownBy(() -> SrppContextInvariants.verify(context)).isInstanceOf(SrppRejectedException.class)
                .hasMessageStartingWith("CONTEXT_INVARIANT_VIOLATION");
    }

    @Test
    void violationsAreRejected() {
        violates(c -> ((ObjectNode) c.at("/modules/assets/data/snapshotTotalAssets")).put("value", "5000000.0"));
        violates(c -> ((ObjectNode) c.at("/modules/assets/data/checks/0")).put("detailTwd", "01"));
        violates(c -> ((ArrayNode) c.at("/modules/allocation/sourceIds")).removeAll().add("policy").add("assets"));
        violates(c -> ((ArrayNode) c.at("/modules/assets/sourceIds")).removeAll().add("unknown"));
        violates(c -> ((ArrayNode) c.get("sources")).insert(0, c.get("sources").get(2).deepCopy()));
        violates(c -> ((ObjectNode) c.at("/sources/0")).put("dataAsOf", "2026-09-24T09:06:00+08:00"));
        violates(c -> c.put("dataCutoffAt", "2026-09-24T09:05:02+08:00"));
        violates(c -> c.put("coverage", "COMPLETE"));
        violates(c -> ((ObjectNode) c.at("/modules/funding")).putObject("data"));
        violates(c -> ((ArrayNode) c.at("/modules/assets/data/checks")).remove(7));
        violates(c -> ((ObjectNode) c.at("/modules/assets/data/checks/7")).put("name", "live_stocks"));
        violates(c -> {
            ArrayNode rows = (ArrayNode) c.at("/modules/allocation/data/rows");
            rows.add(rows.get(0).deepCopy());
        });
        violates(c -> {
            ArrayNode groups = (ArrayNode) c.at("/modules/assets/data/depositGroups");
            groups.add(groups.remove(0));
        });
        violates(c -> ((ObjectNode) c.at("/modules/allocation/data/rows/0/targetWeight"))
                .put("quality", "UNAVAILABLE"));
        violates(c -> {   // COMPLETE 模組不得含 UNAVAILABLE Metric（即使該 Metric 本身合法）
            ObjectNode target = (ObjectNode) c.at("/modules/allocation/data/rows/0/targetWeight");
            target.putNull("value");
            target.put("quality", "UNAVAILABLE");
            target.putArray("reasonCodes").add("TARGET_NOT_MAPPED");
            target.putArray("sourceIds");
        });
        violates(c -> ((ObjectNode) c.at("/modules/cashIncome")).putArray("reasonCodes"));
        violates(c -> c.put("tradingAuthorized", true));
    }
}
