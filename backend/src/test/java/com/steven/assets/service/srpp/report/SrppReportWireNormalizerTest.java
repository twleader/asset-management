package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SrppReportWireNormalizerTest {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    @Test void keepsFirstOccurrencePerLegAndAllReceiptsWithoutMutatingCalculatorOutput() {
        ArrayNode original = F.arrayNode();
        ObjectNode module = original.addObject().put("status", "PARTIAL");
        module.putArray("missing").add("SOURCE_UNAVAILABLE");
        module.putArray("reasonCodes").add("SOURCE_UNAVAILABLE");
        ArrayNode legs = module.putObject("data").putArray("legs");
        for (int i=0; i<2; i++) {
            ObjectNode leg = legs.addObject().put("status", "BLOCKED").put("lots", 0).putNull("limitPrice").putNull("amount");
            leg.putArray("blockingReasons").add("B").add("A").add("B").add("C").add("A");
            leg.putArray("receipts").addObject().put("ruleId", "PENDING").put("status", "UNAVAILABLE").put("reason", "B");
            leg.withArray("receipts").addObject().put("ruleId", "DEDUPLICATION").put("status", "UNAVAILABLE").put("reason", "B");
        }
        ArrayNode before = original.deepCopy();
        ArrayNode normalized = SrppReportWireNormalizer.modules(original);
        assertThat(original).isEqualTo(before);
        for (int i=0; i<2; i++) {
            assertThat(normalized.path(0).path("data").path("legs").path(i).path("blockingReasons"))
                    .containsExactly(F.textNode("B"), F.textNode("A"), F.textNode("C"));
            ((ObjectNode)normalized.path(0).path("data").path("legs").path(i))
                    .set("blockingReasons", before.path(0).path("data").path("legs").path(i).path("blockingReasons"));
        }
        assertThat(normalized).isEqualTo(before);
    }

    @Test void malformedReasonValuesAreRejectedInsteadOfRepaired() {
        for (String json : new String[]{"null", "1", "\"\"", "{}", "[]"}) {
            ArrayNode modules = F.arrayNode();
            ObjectNode leg = modules.addObject().putObject("data").putArray("legs").addObject();
            leg.putArray("blockingReasons").add(read(json));
            assertThatThrownBy(() -> SrppReportWireNormalizer.modules(modules)).isInstanceOf(SrppReportProblem.class);
        }
        ArrayNode modules = F.arrayNode();modules.addObject().putObject("data").putArray("legs").addObject();
        assertThatThrownBy(() -> SrppReportWireNormalizer.modules(modules)).isInstanceOf(SrppReportProblem.class);
    }
    private static com.fasterxml.jackson.databind.JsonNode read(String json) { try {return SrppReportFixtures.MAPPER.readTree(json);} catch(Exception e) {throw new AssertionError(e);} }
}
