package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DecisionFacts.Policy;
import com.steven.assets.srpp.SupportedPolicy;

/** Injectable boundary around deterministic report functions, allowing real concurrency invocation assertions. */
public interface SrppReportCalculationPort {
    ArrayNode shared(Frozen input, Policy policy, JsonNode model, SupportedPolicy supported, JsonNode extra);
    ObjectNode core(Frozen input, JsonNode model, JsonNode state, String consumer);
    ArrayNode compose(ArrayNode shared, ObjectNode core);
}
