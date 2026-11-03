package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class UsCoreSellCalculatorTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static JsonNode fixture() throws Exception { return M.readTree(UsCoreSellCalculatorTest.class.getResourceAsStream("/srpp/us-calculator-parity.json")); }
    @Test void authoritativePythonObservationAndTimingParity() throws Exception {
        JsonNode all = fixture();
        assertThat(UsCoreSellCalculator.watchPolicyFingerprint(all.path("model").path("execution_policy").path("core_sell_guard_d174"))).isEqualTo(all.path("watchPolicyFingerprint").asText());
        for (JsonNode test : all.path("core")) {
            ObjectNode actual = UsCoreSellCalculator.calculate(test.path("input"), all.path("model"), test.path("ledger"), "Codex");
            assertEquivalent(test.path("expected").path("results"), actual.path("results"), test.path("name").asText());
            assertEquivalent(test.path("stateAfter"), actual.path("stateAfter"), test.path("name").asText() + ".stateAfter");
            assertEquivalent(test.path("expected").path("watch_updates"), actual.path("watch_updates"), test.path("name").asText() + ".watch_updates");
        }
    }
    @Test void missingImportAndWrongConsumerCannotBecomeEmptyLedger() throws Exception {
        JsonNode all = fixture(), test = all.path("core").get(0);
        for (JsonNode ledger : Arrays.asList(null, M.readTree("{\"decision\":\"D-174\",\"consumer\":\"Claude\",\"states\":{}}"))) {
            ObjectNode actual = UsCoreSellCalculator.calculate(test.path("input"), all.path("model"), ledger, "Codex");
            assertThat(actual.path("coverage").asText()).isEqualTo("UNAVAILABLE"); assertThat(actual.path("stateAfter").isNull()).isTrue();
            assertThat(actual.path("results").get(0).path("timing_pass").asBoolean()).isFalse();
        }
    }
    @Test void originalPythonJsonWhitespaceAndFloatingPointIdentity() throws Exception {
        JsonNode value = M.readTree("{\"z\":1e-7,\"a\":[\"台灣\",1.0,10000000000000000.0,-0.0,true,null]}");
        assertThat(UsPythonJson.write(value)).isEqualTo("{\"a\": [\"台灣\", 1.0, 1e+16, -0.0, true, null], \"z\": 1e-07}");
    }
    @Test void wireDecimalStateRetainsOriginalObservation() throws Exception {
        JsonNode all = fixture(), test = all.path("core").get(0); ObjectNode ledger = test.path("ledger").deepCopy();
        ((ObjectNode)ledger.path("states").path("GOOGL")).put("high_water", "100");
        ObjectNode actual = UsCoreSellCalculator.calculate(test.path("input"), all.path("model"), ledger, "Codex");
        assertThat(actual.path("results").get(0).path("status").asText()).isEqualTo("CORE_SELL_READY");
        assertThat(actual.path("stateBefore").path("GOOGL").path("high_water").asText()).isEqualTo("100");
    }
    private static void assertEquivalent(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber()) { assertThat(actual.isNumber() || actual.isTextual()).as(path).isTrue(); assertThat(new java.math.BigDecimal(actual.asText())).as(path).isEqualByComparingTo(new java.math.BigDecimal(expected.asText())); return; }
        if (expected.isObject()) { Set<String> names = new TreeSet<>(); expected.fieldNames().forEachRemaining(names::add); Set<String> actualNames = new TreeSet<>(); actual.fieldNames().forEachRemaining(actualNames::add); assertThat(actualNames).as(path).isEqualTo(names); for (String key : names) assertEquivalent(expected.get(key), actual.get(key), path + "." + key); return; }
        if (expected.isArray()) { assertThat(actual.size()).as(path).isEqualTo(expected.size()); for (int i = 0; i < expected.size(); i++) assertEquivalent(expected.get(i), actual.get(i), path + "[" + i + "]"); return; }
        assertThat(actual).as(path).isEqualTo(expected);
    }
}
