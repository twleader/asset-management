package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.srpp.*;
import org.springframework.stereotype.Component;

/** Bind the full authored model/manifest bytes and exact formal target mapping to the registry. */
@Component
public class SrppReportAuthority {
    private final DecisionPolicyAuthority decision;
    private final JsonNode model;
    private final String targetsHash;
    public SrppReportAuthority(DecisionPolicyAuthority decision) {
        this.decision = decision;
        try {
            model = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(decision.modelBytes());
            if (!"D-201".equals(decision.policy().decision()) || !"2.49.0".equals(decision.policy().policyVersion()) || !"3.22.0".equals(decision.policy().modelVersion())) throw new IllegalArgumentException();
            targetsHash = SrppJcs.hash(SrppReportModuleCalculator.formalTargets(model));
        } catch (Exception invalid) { throw new IllegalStateException("Report authority verification failed", invalid); }
    }
    public boolean matches(SupportedPolicy policy) {
        if (policy == null || !decision.policy().bundleHash().equals(policy.bundleHash())) return false;
        try {
            JsonNode expected = SrppReportModuleCalculator.registryDocument(model);
            SrppPolicyDocument parsed = SrppPolicyDocument.parse(SrppJcs.canonicalize(expected));
            return SrppJcs.canonicalize(expected).equals(SrppJcs.canonicalize(policy.policyDocument())) && parsed.targets().equals(policy.targets());
        } catch (RuntimeException invalid) { return false; }
    }
    public JsonNode model() { return model.deepCopy(); }
    public DecisionPolicyAuthority decision() { return decision; }
    public String targetsHash() { return targetsHash; }
    public ObjectNode revision(String build) {
        ObjectNode n = JsonNodeFactory.instance.objectNode(); n.put("serviceBuild",build); n.put("databaseSchema","v1.150.0"); n.put("calculatorVersion",SrppReportModuleCalculator.VERSION); n.put("modelInputsSha256",decision.policy().modelHash()); n.put("policyManifestSha256",decision.policy().manifestHash()); n.put("targetAllocationSha256",targetsHash); return n;
    }
}
