package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import com.steven.assets.srpp.*;
import org.springframework.stereotype.Component;
import java.util.*;

/** Bind the full authored model/manifest bytes and exact formal target mapping to the registry. */
@Component
public class SrppReportAuthority {
    private static final String REPORT_CALCULATIONS_ROUTE="/api/public/srpp/report-calculations";
    private static final Set<String> REPORT_CANDIDATE_FIELDS=Set.of("publication_status","daily_adoption_status","enabled_for_daily","report_slots","uses_legacy_api_slot_mapping","calculator_version","consumer_verifier","prerequisites","observations_pending","fallback_reason");
    private final DecisionPolicyAuthority decision;
    private final JsonNode model;
    private final String targetsHash;
    public SrppReportAuthority(DecisionPolicyAuthority decision) {
        this.decision = decision;
        try {
            model = SrppJcs.parseStrict(decision.modelBytes());
            verifyReportCandidate(model);
            targetsHash = SrppJcs.hash(SrppReportModuleCalculator.formalTargets(model));
        } catch (Exception invalid) { throw new IllegalStateException("Report authority verification failed", invalid); }
    }
    private static void verifyReportCandidate(JsonNode model) {
        JsonNode orchestrated=model.path("execution_policy").path("llm_orchestrated_api_d197"),routes=orchestrated.path("read_only_routes");
        boolean reportRoute=false;
        if(routes.isArray())for(JsonNode route:routes)if(route.isTextual()&&REPORT_CALCULATIONS_ROUTE.equals(route.textValue()))reportRoute=true;
        if(!reportRoute)throw new IllegalArgumentException("REPORT_ROUTE_UNSUPPORTED");
        JsonNode candidate=orchestrated.path("published_read_candidates").get(REPORT_CALCULATIONS_ROUTE);
        if(candidate==null||!candidate.isObject())throw new IllegalArgumentException("REPORT_CANDIDATE_INVALID");
        Set<String> fields=new HashSet<>();candidate.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(REPORT_CANDIDATE_FIELDS)||!text(candidate,"publication_status","PUBLISHED_SWAGGER_1_26_0")||!text(candidate,"daily_adoption_status","ENABLED_TRIAL_RUNTIME_PENDING")||!bool(candidate,"enabled_for_daily",true)||!strings(candidate,"report_slots",List.of("09:05","12:00"))||!bool(candidate,"uses_legacy_api_slot_mapping",false)||!text(candidate,"calculator_version",SrppReportModuleCalculator.VERSION)||!text(candidate,"consumer_verifier","scripts/verify_srpp_report_calculations.py")||!strings(candidate,"prerequisites",List.of("CURRENT_POLICY_REGISTRATION","INDEPENDENT_CONSUMER_VERIFIER"))||!strings(candidate,"observations_pending",List.of("SCOPED_SAME_INPUT_GOLDEN_PARITY","TRADING_DAY_RUNTIME_ACCEPTANCE"))||!text(candidate,"fallback_reason","REPORT_CALCULATION_SCOPE_UNAVAILABLE"))throw new IllegalArgumentException("REPORT_CANDIDATE_CONTRACT_INVALID");
    }
    private static boolean text(JsonNode node,String key,String expected){JsonNode value=node.get(key);return value!=null&&value.isTextual()&&expected.equals(value.textValue());}
    private static boolean bool(JsonNode node,String key,boolean expected){JsonNode value=node.get(key);return value!=null&&value.isBoolean()&&value.booleanValue()==expected;}
    private static boolean strings(JsonNode node,String key,List<String> expected){JsonNode value=node.get(key);if(value==null||!value.isArray()||value.size()!=expected.size())return false;for(int i=0;i<expected.size();i++)if(!value.get(i).isTextual()||!expected.get(i).equals(value.get(i).textValue()))return false;return true;}
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
