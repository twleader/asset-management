package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;

/** Publication-only wire adaptation. Calculator trees and persisted history remain untouched. */
final class SrppReportWireNormalizer {
    private SrppReportWireNormalizer() {}

    static ArrayNode modules(ArrayNode original) {
        ArrayNode copy = original.deepCopy();
        for (JsonNode module : copy) {
            JsonNode legs = module.path("data").path("legs");
            if (!legs.isArray()) throw new SrppReportProblem("UPSTREAM_INVALID");
            for (JsonNode leg : legs) {
                JsonNode reasons = leg.path("blockingReasons");
                if (!leg.isObject() || !reasons.isArray()) throw new SrppReportProblem("UPSTREAM_INVALID");
                Set<String> seen = new HashSet<>();
                ArrayNode unique = JsonNodeFactory.instance.arrayNode();
                for (JsonNode reason : reasons) {
                    if (!reason.isTextual() || reason.textValue().isEmpty()) throw new SrppReportProblem("UPSTREAM_INVALID");
                    if (seen.add(reason.textValue())) unique.add(reason);
                }
                ((ObjectNode) leg).set("blockingReasons", unique);
            }
        }
        return copy;
    }
}
