package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** Strict validation at the private worker boundary; historical stored V1 results are not revalidated. */
final class SettingsResultValidator {
    private SettingsResultValidator() {}
    static boolean validV2(JsonNode envelope) {
        try {
            for(String side:Set.of("baseline","scenario")) {
                JsonNode result=envelope.path("result").path(side),a=result.path("assumptions");
                int paths=a.path("simulations").intValue();
                if(!a.path("simulations").isIntegralNumber()||paths<100||paths>100000
                        ||!date(a.path("birthDate"))||!date(a.path("asOfDate"))||!date(a.path("retirementDate")))return false;
                if(!cashflow(a.path("cashflowSchedule"))||!strategy(a.path("reserveStrategy"))
                        ||!audit(result.path("reserveStrategyAudit"),paths))return false;
            }
            return true;
        }catch(RuntimeException invalid){return false;}
    }
    private static boolean cashflow(JsonNode s) {
        JsonNode p=s.path("laborPension"),i=s.path("laborInsurance"),pay=s.path("payroll");
        if(!mode(p.path("mode"))||!date(p.path("receiptDate"))||!money(p.path("amountNominalTwd"),true)
                ||!p.path("included").isBoolean()||!mode(i.path("mode"))||!date(i.path("startDate"))
                ||!money(i.path("monthlyNominalTwd"),true)||!money(i.path("asOfMonthlyNominalTwd"),true)
                ||!Set.of("SCENARIO_CPI","REFERENCE_INFLATION_HISTORY_PROXY").contains(i.path("indexationBasis").asText()))return false;
        for(String name:Set.of("homeRepair","appliance")) {
            JsonNode r=s.path(name);
            if(!integer(r.path("firstYear"),1900,2200)||!"08-31".equals(r.path("eventMonthDay").asText())
                    ||!integer(r.path("intervalYears"),1,30)||!money(r.path("amountRealTwd"),true))return false;
        }
        if(!s.path("majorExpenses").isArray()||s.path("majorExpenses").size()>100)return false;
        for(JsonNode e:s.path("majorExpenses")) {
            if(!date(e.path("date"))||!text(e.path("name"))||!money(e.path("amountRealTwd"),true)
                    ||!e.path("included").isBoolean()||!e.has("exclusionReason"))return false;
            if(e.path("included").booleanValue()) {if(!e.path("exclusionReason").isNull())return false;}
            else if(!Set.of("AT_OR_BEFORE_AS_OF","AFTER_HORIZON").contains(e.path("exclusionReason").asText()))return false;
        }
        for(String key:Set.of("monthlySalaryNominalTwd","annualRaiseAmountNominalTwd","monthlyExpenseRealTwd"))
            if(!money(pay.path(key),true))return false;
        for(String key:Set.of("receivedByRetirementNominalTwd","receivableAfterRetirementNominalTwd"))if(!money(pay.path(key),false))return false;
        if(!date(pay.path("annualRaiseFirstDate"))||!integer(pay.path("payday"),1,28)||!integer(pay.path("nhiDependents"),0,5)
                ||!number(pay.path("withholdingRate"),0,1)||!number(pay.path("selfPensionRate"),0,.06)
                ||!pay.path("bonusEvents").isArray()||pay.path("bonusEvents").size()>100
                ||!pay.path("recurringBonuses").isArray()||pay.path("recurringBonuses").size()>32
                ||!pay.path("ledger").isArray()||pay.path("ledger").size()>3000)return false;
        for(JsonNode e:pay.path("bonusEvents"))if(!text(e.path("name"))||!date(e.path("pay_date"))||!number(e.path("months"),0,24)
                ||e.has("festival_date")&&!date(e.path("festival_date")))return false;
        for(JsonNode e:pay.path("recurringBonuses")) {
            if(!text(e.path("name"))||!integer(e.path("month"),1,12)||!number(e.path("months"),0,24))return false;
            if(e.has("month_by_year")) {
                JsonNode years=e.path("month_by_year");if(!years.isObject()||years.size()>100)return false;
                var fields=years.fields();while(fields.hasNext()) {var y=fields.next();if(!y.getKey().matches("[12][0-9]{3}")
                        ||Integer.parseInt(y.getKey())<1900||Integer.parseInt(y.getKey())>2200||!integer(y.getValue(),1,12))return false;}
            }
        }
        for(JsonNode row:pay.path("ledger")) {
            if(!date(row.path("payDate"))||!row.path("paymentAfterRetirement").isBoolean()||!row.path("includedAtRetirement").isBoolean()
                    ||!text(row.path("yearRuleStatus"))||!row.path("bonusEvents").isArray()||row.path("bonusEvents").size()>132)return false;
            for(String key:Set.of("contractualMonthlySalaryNominalTwd","regularSalaryNominalTwd","bonusNominalTwd","grossPayNominalTwd",
                    "laborInsuranceNominalTwd","healthInsuranceNominalTwd","selfPensionNominalTwd","withholdingNominalTwd",
                    "supplementalHealthNominalTwd","netCashNominalTwd"))if(!money(row.path(key),false))return false;
            for(JsonNode bonus:row.path("bonusEvents"))if(!text(bonus.path("name"))||!number(bonus.path("months"),0,24)
                    ||!money(bonus.path("amountNominalTwd"),true)||!bonus.has("festivalDate")||!bonus.has("timingStatus")
                    ||!bonus.path("festivalDate").isNull()&&!date(bonus.path("festivalDate"))
                    ||!bonus.path("timingStatus").isNull()&&!text(bonus.path("timingStatus")))return false;
        }
        return true;
    }
    private static boolean strategy(JsonNode s) {
        for(String key:Set.of("emergencyDepositReserveRealTwd","emergencyEtfReserveRealTwd","protectedReserveRealTwd",
                "livingReserveCeilingRealTwd","livingReserveFloorRealTwd","bearLivingDeploymentCapRealTwd","marketSpendingAdjustmentRealTwd"))
            if(!money(s.path(key),true))return false;
        if(!s.path("bullStrategyEnabled").isBoolean()||!s.path("bearStrategyEnabled").isBoolean()||!s.path("liquidityBranchesEnabled").isBoolean()
                ||!integer(s.path("tacticalWaitMonths"),1,24)||!"MONTHLY_SYNTHETIC_TOTAL_RETURN_PROXY".equals(s.path("signalBasis").asText()))return false;
        for(String key:Set.of("bearBondSaleMaxShare","minimumBondWeight","bearEquityCap","bullEquityFloor"))if(!number(s.path(key),0,1))return false;
        if(!s.path("bearLadder").isArray()||s.path("bearLadder").size()!=5||!s.path("bullLadder").isArray()||s.path("bullLadder").size()!=5)return false;
        for(JsonNode rung:s.path("bearLadder"))if(!number(rung.path("drawdown"),0,1)||!number(rung.path("share_of_window_budget"),0,1))return false;
        for(JsonNode rung:s.path("bullLadder"))if(!number(rung.path("gain_above_anchor"),0,1)
                ||!number(rung.path("pullback_from_high_watermark"),0,1)||!number(rung.path("share_of_window_budget"),0,1))return false;
        return true;
    }
    private static boolean audit(JsonNode a,int paths) {
        if(!quantiles(a.path("openingCashTransferRealTwd"))||!quantiles(a.path("openingLivingShortfallRealTwd"))
                ||!integer(a.path("monthUpdateCount"),0,1321)||!a.path("monthlyAudit").isArray()
                ||a.path("monthlyAudit").size()!=a.path("monthUpdateCount").intValue())return false;
        int months=a.path("monthUpdateCount").intValue();
        for(String key:Set.of("bearFillCount","bullFillCount"))if(!integer(a.path(key),0,(long)paths*months))return false;
        for(String key:Set.of("bearMaximumWindowFills","bullMaximumWindowFills"))if(!integer(a.path(key),0,5))return false;
        for(String key:Set.of("bearMaximumFirstBatchShare","bullMaximumFirstBatchShare"))if(!number(a.path(key),0,.10+1e-12))return false;
        LocalDate previous=null;
        for(JsonNode row:a.path("monthlyAudit")) {
            if(!date(row.path("date")))return false;
            LocalDate day=LocalDate.parse(row.path("date").textValue());
            if(day.getDayOfMonth()!=day.lengthOfMonth()||previous!=null&&!day.isAfter(previous))return false;
            previous=day;
            for(String key:Set.of("bullSignalPathCount","bearSignalPathCount","bullFillPathCount","bearFillPathCount"))if(!integer(row.path(key),0,paths))return false;
            for(String key:Set.of("bullEquitySoldRealTwd","bearCashDeployedRealTwd","bearBondsSoldRealTwd","livingRefillRealTwd","livingShortfallRealTwd"))if(!quantiles(row.path(key)))return false;
        }
        return true;
    }
    private static boolean quantiles(JsonNode q) {
        if(!money(q.path("p10"),true)||!money(q.path("p50"),true)||!money(q.path("p90"),true))return false;
        return new BigDecimal(q.path("p10").textValue()).compareTo(new BigDecimal(q.path("p50").textValue()))<=0
                &&new BigDecimal(q.path("p50").textValue()).compareTo(new BigDecimal(q.path("p90").textValue()))<=0;
    }
    private static boolean money(JsonNode n,boolean nonnegative) {
        return n.isTextual()&&n.textValue().matches("-?(0|[1-9][0-9]*)\\.[0-9]{2}")&&!n.textValue().equals("-0.00")
                &&(!nonnegative||!n.textValue().startsWith("-"));
    }
    private static boolean integer(JsonNode n,long low,long high){return n.isIntegralNumber()&&n.canConvertToLong()&&n.longValue()>=low&&n.longValue()<=high;}
    private static boolean number(JsonNode n,double low,double high){return n.isNumber()&&Double.isFinite(n.doubleValue())&&n.doubleValue()>=low&&n.doubleValue()<=high;}
    private static boolean date(JsonNode n){try{return n.isTextual()&&LocalDate.parse(n.textValue()).toString().equals(n.textValue());}catch(RuntimeException invalid){return false;}}
    private static boolean text(JsonNode n){return n.isTextual()&&!n.textValue().isBlank();}
    private static boolean mode(JsonNode n){return n.isTextual()&&Set.of("AUTO","MANUAL").contains(n.textValue());}
}
