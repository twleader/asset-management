package com.steven.assets.bff.portfolioadvice;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.steven.assets.bff.portfolioadvice.SrppSettingsDto.*;
import java.util.*;

/** Page-owned ordering, grouping and visible assumptions; canonical calculation evidence is untouched. */
final class SrppSettingsPresentation {
    private SrppSettingsPresentation() {}
    static final ObjectMapper JSON=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    static CenterResponse center(CenterResponse center) {
        List<JsonNode> ordered=new ArrayList<>(center.catalog().fields());ordered.sort(Comparator.comparingInt(n->n.path("order").asInt()));
        Map<String,List<JsonNode>> groups=new LinkedHashMap<>();for(JsonNode f:ordered)groups.computeIfAbsent(f.path("group").asText("其他設定"),k->new ArrayList<>()).add(f);
        List<FieldGroup> fieldGroups=groups.entrySet().stream().map(e->new FieldGroup(e.getKey(),e.getValue())).toList();
        return new CenterResponse(center.catalog(),center.current(),center.drafts(),center.simulations().stream().map(SrppSettingsPresentation::run).toList(),center.releases(),center.canPublish(),center.aiAvailable(),fieldGroups);
    }
    static RunResponse run(RunResponse r) {
        Presentation presentation=r.deterministicResult()==null?null:new Presentation(scenario(r.deterministicResult().result().baseline()),scenario(r.deterministicResult().result().scenario()));
        return new RunResponse(r.id(),r.draftId(),r.draftRevision(),r.mode(),r.status(),r.configSha256(),r.inputSha256(),r.resultSha256(),r.aiStatus(),r.aiInterpretation(),r.errorCode(),r.createdAt(),r.completedAt(),r.deterministicResult(),r.changes(),r.publishEligible(),r.blockedReasons(),presentation);
    }
    private record Definition(String key,String pointer,String label,String unit) {}
    private static final List<Definition> ASSUMPTIONS=List.of(
        new Definition("baseCurrency","/baseCurrency","金額基準",""),
        new Definition("purchasingPowerBaseDate","/purchasingPowerBaseDate","購買力基準日",""),
        new Definition("asOfDate","/asOfDate","試算基準日",""),
        new Definition("assetSnapshotDate","/assetSnapshotDate","資產快照日",""),
        new Definition("targetPriceComplete","/targetPriceComplete","價格是否完整符合目標交易日",""),
        new Definition("retirementDate","/retirementDate","退休日期",""),
        new Definition("planningAge","/planningAge","規劃終點年齡","歲"),
        new Definition("simulations","/simulations","模擬路徑數",""),
        new Definition("seed","/seed","亂數種子",""),
        new Definition("reserveExcluded","/reserveExcluded","排除緊急預備金",""),
        new Definition("protectedReserveRealTwd","/protectedReserveRealTwd","受保護緊急預備金","TWD"),
        new Definition("referenceInflation","/referenceInflation","購買力換算基準通膨率","fraction"),
        new Definition("scenarioInflationBounds.low","/scenarioInflationBounds/low","情境通膨下緣","fraction"),
        new Definition("scenarioInflationBounds.high","/scenarioInflationBounds/high","情境通膨上緣","fraction"),
        new Definition("equityRealCompoundPlanningTarget","/equityRealCompoundPlanningTarget","股票實質複合規劃目標","fraction"),
        new Definition("equityArithmeticPlanningMean","/equityArithmeticPlanningMean","股票算術規劃均值","fraction"),
        new Definition("bondNominalPlanningYield","/bondNominalPlanningYield","債券名目規劃收益率","fraction"),
        new Definition("bondReferenceRealArithmeticMean","/bondReferenceRealArithmeticMean","債券基準實質算術均值","fraction"),
        new Definition("cashReferenceRealArithmeticMean","/cashReferenceRealArithmeticMean","現金基準實質均值","fraction"),
        new Definition("equityVolatility","/equityVolatility","股票情境年波動率","fraction"),
        new Definition("bondVolatility","/bondVolatility","債券情境年波動率","fraction"),
        new Definition("fxVolatility","/fxVolatility","匯率年波動假設","fraction"),
        new Definition("fxVolatilitySource","/fxVolatilitySource","匯率波動來源與時點",""),
        new Definition("returnMeanSource","/returnMeanSource","報酬假設來源",""),
        new Definition("equityMeanConversion","/equityMeanConversion","股票均值換算規約",""));
    private static ScenarioPresentation scenario(ScenarioResponse s) {
        JsonNode assumptions=JSON.valueToTree(s.assumptions());List<AssumptionEntry> rows=new ArrayList<>();
        for(Definition d:ASSUMPTIONS){
            JsonNode value=assumptions.at(d.pointer());
            if(d.key().equals("returnMeanSource"))value=com.fasterxml.jackson.databind.node.TextNode.valueOf("本版 SRPP 的既有規劃假設；未重新取得本日市場報酬預估");
            if(d.key().equals("equityMeanConversion"))value=com.fasterxml.jackson.databind.node.TextNode.valueOf("採用波動拖累的近似換算；不保證模擬實現該複合報酬");
            if(!value.isMissingNode()&&!value.isNull())rows.add(new AssumptionEntry(d.key(),d.label(),value,d.unit()));
        }
        return new ScenarioPresentation(rows,s.modeledFields(),s.notModeledFields());
    }
}
