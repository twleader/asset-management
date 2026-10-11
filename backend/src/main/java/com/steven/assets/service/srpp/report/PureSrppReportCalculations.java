package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DecisionFacts.Policy;
import com.steven.assets.srpp.SupportedPolicy;
import org.springframework.stereotype.Component;

/** Outer adapter delegates to pure functions; owns no sources, persistence, or request identity. */
@Component
public class PureSrppReportCalculations implements SrppReportCalculationPort {
    public ArrayNode shared(Frozen input, Policy policy, JsonNode model, SupportedPolicy supported, JsonNode extra) { return SrppReportModuleCalculator.calculateShared(input,policy,model,supported,extra); }
    public ObjectNode core(Frozen input, JsonNode model, JsonNode state, String consumer) { return SrppReportModuleCalculator.calculateCore(input,model,state,consumer); }
    public ArrayNode compose(ArrayNode shared, ObjectNode core) { return SrppReportModuleCalculator.compose(shared,core); }
}
