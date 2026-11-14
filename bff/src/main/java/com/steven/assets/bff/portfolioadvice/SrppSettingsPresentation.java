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
        new Definition("equityMeanConversion","/equityMeanConversion","股票均值換算規約",""),
        new Definition("birthDate","/birthDate","生日",""),
        new Definition("laborPension.mode","/cashflowSchedule/laborPension/mode","勞退一次領來源",""),
        new Definition("laborPension.receiptDate","/cashflowSchedule/laborPension/receiptDate","勞退實際入帳日（估計）",""),
        new Definition("laborPension.amountNominalTwd","/cashflowSchedule/laborPension/amountNominalTwd","勞退一次領名目金額","名目新臺幣元"),
        new Definition("laborPension.included","/cashflowSchedule/laborPension/included","本輪納入勞退入帳",""),
        new Definition("laborInsurance.mode","/cashflowSchedule/laborInsurance/mode","勞保月領來源",""),
        new Definition("laborInsurance.startDate","/cashflowSchedule/laborInsurance/startDate","勞保起領日",""),
        new Definition("laborInsurance.monthlyNominalTwd","/cashflowSchedule/laborInsurance/monthlyNominalTwd","勞保起領時名目月額","名目新臺幣元"),
        new Definition("laborInsurance.asOfMonthlyNominalTwd","/cashflowSchedule/laborInsurance/asOfMonthlyNominalTwd","本輪期初有效名目月額（尚未起領時為起領月額）","名目新臺幣元"),
        new Definition("laborInsurance.indexationBasis","/cashflowSchedule/laborInsurance/indexationBasis","勞保物價調整來源",""),
        new Definition("homeRepair.firstYear","/cashflowSchedule/homeRepair/firstYear","首次房屋修繕年份","年"),
        new Definition("homeRepair.eventMonthDay","/cashflowSchedule/homeRepair/eventMonthDay","房屋修繕日期規約",""),
        new Definition("homeRepair.intervalYears","/cashflowSchedule/homeRepair/intervalYears","房屋修繕間隔","年"),
        new Definition("homeRepair.amountRealTwd","/cashflowSchedule/homeRepair/amountRealTwd","每次房屋修繕費","2026年8月實質元"),
        new Definition("appliance.firstYear","/cashflowSchedule/appliance/firstYear","首次家電換新年份","年"),
        new Definition("appliance.eventMonthDay","/cashflowSchedule/appliance/eventMonthDay","家電換新日期規約",""),
        new Definition("appliance.intervalYears","/cashflowSchedule/appliance/intervalYears","家電換新間隔","年"),
        new Definition("appliance.amountRealTwd","/cashflowSchedule/appliance/amountRealTwd","每次家電換新費","2026年8月實質元"),
        new Definition("payroll.monthlySalaryNominalTwd","/cashflowSchedule/payroll/monthlySalaryNominalTwd","退休前基準月薪（調薪前）","名目新臺幣元"),
        new Definition("payroll.payday","/cashflowSchedule/payroll/payday","每月發薪日","日"),
        new Definition("payroll.annualRaiseAmountNominalTwd","/cashflowSchedule/payroll/annualRaiseAmountNominalTwd","每年調薪金額","名目新臺幣元"),
        new Definition("payroll.annualRaiseFirstDate","/cashflowSchedule/payroll/annualRaiseFirstDate","首次調薪日期",""),
        new Definition("payroll.withholdingRate","/cashflowSchedule/payroll/withholdingRate","薪資與獎金扣繳率（非最終所得稅）","fraction"),
        new Definition("payroll.selfPensionRate","/cashflowSchedule/payroll/selfPensionRate","自提勞退比例（未計入可支用現金）","fraction"),
        new Definition("payroll.nhiDependents","/cashflowSchedule/payroll/nhiDependents","健保眷屬人數","人"),
        new Definition("payroll.monthlyExpenseRealTwd","/cashflowSchedule/payroll/monthlyExpenseRealTwd","退休前每月生活支出（含稅務預算）","2026年8月實質元"),
        new Definition("payroll.receivedByRetirementNominalTwd","/cashflowSchedule/payroll/receivedByRetirementNominalTwd","預計於退休當日前入帳的淨薪資與獎金","名目新臺幣元"),
        new Definition("payroll.receivableAfterRetirementNominalTwd","/cashflowSchedule/payroll/receivableAfterRetirementNominalTwd","退休後才入帳的應收淨薪資","名目新臺幣元"),
        new Definition("reserves.emergencyDepositReserveRealTwd","/reserveStrategy/emergencyDepositReserveRealTwd","緊急備用金：定存部分","2026年8月實質元"),
        new Definition("reserves.emergencyEtfReserveRealTwd","/reserveStrategy/emergencyEtfReserveRealTwd","緊急備用金：00865B部分（聚合代理）","2026年8月實質元"),
        new Definition("reserves.livingReserveCeilingRealTwd","/reserveStrategy/livingReserveCeilingRealTwd","三年生活費準備金上限（既有資產內預留）","2026年8月實質元"),
        new Definition("reserves.livingReserveFloorRealTwd","/reserveStrategy/livingReserveFloorRealTwd","生活費準備金投資保護底線","2026年8月實質元"),
        new Definition("reserves.bearLivingDeploymentCapRealTwd","/reserveStrategy/bearLivingDeploymentCapRealTwd","熊市可動用生活費準備金上限","2026年8月實質元"),
        new Definition("strategy.bullStrategyEnabled","/reserveStrategy/bullStrategyEnabled","額外牛市分批獲利策略",""),
        new Definition("strategy.bearStrategyEnabled","/reserveStrategy/bearStrategyEnabled","額外熊市分批低接策略",""),
        new Definition("strategy.tacticalWaitMonths","/reserveStrategy/tacticalWaitMonths","配置策略等待月份","月"),
        new Definition("strategy.bearBondSaleMaxShare","/reserveStrategy/bearBondSaleMaxShare","熊市整個視窗可售債券比例上限","fraction"),
        new Definition("strategy.minimumBondWeight","/reserveStrategy/minimumBondWeight","熊市售債後債券配置下限","fraction"),
        new Definition("strategy.bearEquityCap","/reserveStrategy/bearEquityCap","熊市股票配置上限","fraction"),
        new Definition("strategy.bullEquityFloor","/reserveStrategy/bullEquityFloor","牛市售股後股票配置下限","fraction"),
        new Definition("strategy.marketSpendingAdjustmentRealTwd","/reserveStrategy/marketSpendingAdjustmentRealTwd","退休後牛熊市況年生活費增減幅度","2026年8月實質元"),
        new Definition("strategy.signalBasis","/reserveStrategy/signalBasis","牛熊訊號來源",""),
        new Definition("strategy.liquidityBranchesEnabled","/reserveStrategy/liquidityBranchesEnabled","基準市況生活費與生活準備金補足規則",""));
    private static ScenarioPresentation scenario(ScenarioResponse s) {
        JsonNode assumptions=JSON.valueToTree(s.assumptions());List<AssumptionEntry> rows=new ArrayList<>();
        for(Definition d:ASSUMPTIONS){
            JsonNode value=assumptions.at(d.pointer());
            if(d.key().equals("returnMeanSource"))value=com.fasterxml.jackson.databind.node.TextNode.valueOf("本版 SRPP 的既有規劃假設；未重新取得本日市場報酬預估");
            if(d.key().equals("equityMeanConversion"))value=com.fasterxml.jackson.databind.node.TextNode.valueOf("採用波動拖累的近似換算；不保證模擬實現該複合報酬");
            if(value.isTextual()&&d.key().endsWith(".mode"))value=com.fasterxml.jackson.databind.node.TextNode.valueOf(
                    "AUTO".equals(value.asText())?"依本版模型自動推估；個人資格與金額尚未核定":
                            d.key().equals("laborPension.mode")?"使用者指定實收額與入帳日；不再疊加帳戶餘額、提撥與收益":
                                    "使用者指定起領名目月額；不再套用提前請領折減");
            if(d.key().equals("laborInsurance.indexationBasis")&&!value.isMissingNode()&&!value.isNull())value=com.fasterxml.jackson.databind.node.TextNode.valueOf(
                    "REFERENCE_INFLATION_HISTORY_PROXY".equals(value.asText())?"以固定基準通膨初始化過往物價；未取得官方歷史CPI，不是核定月額":"依未來情境物價路徑；個人資格與金額尚未核定");
            if(d.key().equals("strategy.signalBasis")&&!value.isMissingNode()&&!value.isNull())value=com.fasterxml.jackson.databind.node.TextNode.valueOf(
                    "月頻合成股票報酬代理，至少11個完整月份暖機；不是每日MA200、當日雷達或買賣許可");
            if(!value.isMissingNode()&&!value.isNull())rows.add(new AssumptionEntry(d.key(),d.label(),value,d.unit()));
        }
        return new ScenarioPresentation(rows,s.modeledFields(),s.notModeledFields());
    }
}
