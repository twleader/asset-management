package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.srpp.SrppJcs;
import java.util.Set;

/** Immutable, model-bound risk thresholds. Historical V1 and category maxima remain unchanged. */
public record SrppRiskRubricV2(int cautiousTotalScore,int defensiveTotalScore) {
    public static final String SCHEMA="SRPP_RISK_RUBRIC_V2";
    public SrppRiskRubricV2 {
        if(cautiousTotalScore<1||defensiveTotalScore>15||cautiousTotalScore>=defensiveTotalScore)throw new IllegalArgumentException("RISK_THRESHOLDS_INVALID");
    }
    public static SrppRiskRubricV2 fromModel(JsonNode model) {
        JsonNode p=model.path("execution_policy").path("risk_score_decision_policy");
        java.util.Set<String> keys=new java.util.HashSet<>();p.fieldNames().forEachRemaining(keys::add);
        if(!keys.equals(Set.of("rubric_schema","defensive_category_score","defensive_total_score","minimum_evaluated_categories","cautious_total_score"))||!SCHEMA.equals(p.path("rubric_schema").asText())||integer(p,"defensive_category_score")!=3||integer(p,"minimum_evaluated_categories")!=4)throw new IllegalArgumentException("RISK_RUBRIC_INVALID");
        JsonNode maxima=model.path("execution_policy").path("risk_score_category_maxima");
        for(String code:SrppRiskRubricV1.CATEGORY_CODES)if(integer(maxima,code)!=SrppRiskRubricV1.max(code))throw new IllegalArgumentException("RISK_CATEGORY_INVALID");
        return new SrppRiskRubricV2(integer(p,"cautious_total_score"),integer(p,"defensive_total_score"));
    }
    private static int integer(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isIntegralNumber()||!v.canConvertToInt())throw new IllegalArgumentException("RISK_THRESHOLD_NOT_INTEGER");return v.intValue();}
    public ObjectNode document(){JsonNodeFactory f=JsonNodeFactory.instance;ObjectNode root=f.objectNode();root.put("schema",SCHEMA);ArrayNode categories=root.putArray("categories");for(String code:SrppRiskRubricV1.CATEGORY_CODES)categories.addObject().put("code",code).put("max",SrppRiskRubricV1.max(code));root.putObject("decision").put("cautiousTotalScore",cautiousTotalScore).put("defensiveCategoryScore",3).put("defensiveTotalScore",defensiveTotalScore).put("minimumEvaluatedCategories",4);return root;}
    public String rubricSha256(){return SrppJcs.hash(document());}
}
