package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SrppReportIncomePayrollCalculatorTest {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private JsonNode golden() throws Exception { return JSON.readTree(getClass().getResourceAsStream("/srpp/report-income-payroll-golden.json")); }
    @Test void originalIncomeTaxAndNhiSameInputDifferential() throws Exception {
        JsonNode g=golden(); int count=0;
        for(JsonNode c:g.path("cases")) {
            if(c.path("kind").asText().equals("tax")) {
                JsonNode actual=SrppReportIncomePayrollCalculator.annualTaiwanTax(c.path("inputs"),g.path("rules"));
                assertThat(actual.path("selectedMethod").asText()).as(c.path("name").asText()).isEqualTo(c.path("expected").path("selected_method").asText());
                for(String section:List.of("inputs","combined","separate","selected")) for(var e:actual.path(section).properties()) {
                    JsonNode expected=c.path("expected").path(section).get(snake(e.getKey()));
                    assertThat(expected).as(c.path("name").asText()+":"+section+":"+e.getKey()).isNotNull();
                    if(e.getValue().isBoolean()) assertThat(e.getValue()).isEqualTo(expected); else close(e.getValue(),expected,"tax "+c.path("name").asText()+":"+section+":"+e.getKey(),"0.00000001");
                } count++;
            } else if(c.path("kind").asText().equals("nhi")) {
                assertThat(SrppReportIncomePayrollCalculator.nhiFromAnnual(number(c.get("annual")),c.path("events"),g.path("rules"))).isEqualByComparingTo(number(c.get("expected")));count++;
            }
        }
        assertThat(count).isEqualTo(13);
        JsonNode benchmark=SrppReportIncomePayrollCalculator.annualTaiwanTax(g.path("cases").get(0).path("inputs"),g.path("rules"));
        assertThat(benchmark.path("selected").path("taiwanTaxAfterAmtAndCredit").asText()).isEqualTo("-39850");
    }
    @Test void fullDatedPayrollEveryRowAndYearMatchesOriginalIncludingPartialMonthsAndBonusThreshold() throws Exception {
        int count=0;
        for(JsonNode c:golden().path("cases")) if(c.path("kind").asText().equals("payroll")) {
            JsonNode actual=SrppReportIncomePayrollCalculator.payrollLedger(c.path("config")),expected=c.path("expected");
            assertThat(actual.path("includedPaymentCount").asInt()).isEqualTo(expected.path("included_payment_count").asInt());
            assertThat(actual.path("ledger").size()).isEqualTo(expected.path("ledger").size());
            for(int i=0;i<actual.path("ledger").size();i++) {
                JsonNode row=actual.path("ledger").get(i),want=expected.path("ledger").get(i);
                for(var e:row.properties()) {
                    if(e.getKey().equals("bonus_events")) { assertThat(e.getValue().size()).isEqualTo(want.path("bonus_events").size()); for(int j=0;j<e.getValue().size();j++){assertThat(e.getValue().get(j).path("name")).isEqualTo(want.path("bonus_events").get(j).path("name"));close(e.getValue().get(j).path("amount"),want.path("bonus_events").get(j).path("amount"),"bonus amount","0");} }
                    else if(e.getValue().isBoolean()||Set.of("pay_date","year_rule_status").contains(e.getKey()))assertThat(e.getValue()).isEqualTo(want.get(e.getKey()));
                    else close(e.getValue(),want.get(e.getKey()),c.path("name").asText()+":"+i+":"+e.getKey(),"0.000000000001");
                }
            }
            for(var e:actual.path("totals").properties()) close(e.getValue(),expected.path("totals").get(e.getKey()),"total:"+e.getKey(),"0");
            for(var y:actual.path("calendarYearTotals").properties())for(var e:y.getValue().properties())close(e.getValue(),expected.path("calendar_year_totals").path(y.getKey()).get(e.getKey()),"year:"+y.getKey()+":"+e.getKey(),"0");
            count++;
        }
        assertThat(count).isEqualTo(6);
    }
    @Test void singlePensionApplicationAndReceiptMatchOriginalBeforeAndAfterAge60() throws Exception {
        int count=0;
        for(JsonNode c:golden().path("cases"))if(c.path("kind").asText().equals("pension")) {
            JsonNode actual=SrppReportIncomePayrollCalculator.pensionProjection(c.path("pension"),c.path("config")),expected=c.path("expected");
            assertThat(actual.path("applicationDate").asText()).isEqualTo(expected.path("application_date").asText());assertThat(actual.path("receiptDate").asText()).isEqualTo(expected.path("receipt_date").asText());
            Map<String,String> keys=Map.of("openingNominal","opening_nominal_twd","employerContributions","employer_contributions_nominal_twd","employeeContributions","employee_contributions_nominal_twd","projectedFutureEarnings","projected_future_earnings_nominal_twd","totalNominal","total_nominal_twd");
            keys.forEach((a,e)->close(actual.get(a),expected.get(e),"pension:"+a,"0.01"));assertThat(actual.path("statutoryGuaranteeTopUp").isNull()).isTrue();count++;
        }
        assertThat(count).isEqualTo(2);
    }
    @Test void verifiedZeroIncomeCompletesAndWrongOwnerOrMissingCadenceCannotBecomeZeroTax() throws Exception {
        var fixture=SrppReportModuleCalculatorTest.fixture(); JsonNode model=SrppReportModuleCalculatorTest.model();
        ObjectNode extra=JSON.createObjectNode();ObjectNode tax=extra.putObject("tax");tax.put("verified",true);tax.put("ownerUserId",7);tax.put("tradingDate",fixture.frozen().facts().date().toString());tax.put("taxYear",2026);tax.set("rules",golden().path("rules"));ObjectNode filing=tax.putObject("filing");filing.put("otherDomesticIncome","0");filing.put("salaryIncome","0");filing.put("incomeTaxPrepayments","0");filing.put("age70OrOlder",false);
        JsonNode complete=SrppReportIncomePayrollCalculator.calculateIncome(fixture.frozen(),fixture.policy(),model,extra);assertThat(complete.path("status").asText()).isEqualTo("COMPLETE");assertThat(fact(complete,"annualSettlement","2027-05-31")).isEqualByComparingTo("0");
        tax.put("ownerUserId",8);JsonNode missing=SrppReportIncomePayrollCalculator.calculateIncome(fixture.frozen(),fixture.policy(),model,extra);assertThat(missing.path("status").asText()).isEqualTo("PARTIAL");assertThat(missing.path("missing").toString()).contains("OWNER_BOUND_OFFICIAL");assertThat(value(missing,"afterTaxCash",null).isNull()).isTrue();
        tax.put("ownerUserId",7);
        ObjectNode input=fixture.frozen().input();((ObjectNode)input.path("sources").path("assets").path("snapshot").path("stocks").get(0)).put("estimatedDividend","30000");
        var frozen=new DecisionInputCapturePort.Frozen(fixture.frozen().facts(),input,fixture.frozen().sourceVector(),fixture.frozen().assetSnapshotId(),fixture.frozen().assetGeneratedAt());
        ObjectNode profile=tax.putObject("distributions").putObject("台股:0050");profile.put("verified",true);profile.put("source","ISOLATED_OFFICIAL_COMPONENT_NOTICE");for(String key:List.of("domesticDividendShare","domesticInterestShare","savingsInterestShare","otherDomesticIncomeShare","overseasIncomeShare","cashWithholdingRate","creditableForeignTaxRate"))profile.put(key,key.equals("domesticDividendShare")?"1":"0");
        JsonNode unknownCadence=SrppReportIncomePayrollCalculator.calculateIncome(frozen,fixture.policy(),model,extra);assertThat(unknownCadence.path("missing").toString()).contains("OFFICIAL_PAYMENT_CADENCE_UNVERIFIED");assertThat(value(unknownCadence,"supplementaryNhi",null).isNull()).isTrue();
    }
    @Test void fullOwnerBoundIncomeAggregatesBrokerPaymentsAnd2027SalarySettlesOnlyNetInMay2028() throws Exception {
        var fixture=SrppReportModuleCalculatorTest.fixture();ObjectNode input=fixture.frozen().input();ObjectNode snapshot=(ObjectNode)input.path("sources").path("assets").path("snapshot");ArrayNode holdings=(ArrayNode)snapshot.path("stocks");((ObjectNode)holdings.get(0)).put("estimatedDividend","15000");ObjectNode second=holdings.get(0).deepCopy();second.put("id",2);second.put("estimatedDividend","15000");holdings.add(second);
        var frozen=new DecisionInputCapturePort.Frozen(fixture.frozen().facts(),input,fixture.frozen().sourceVector(),1,fixture.frozen().assetGeneratedAt());ObjectNode extra=JSON.createObjectNode(),tax=extra.putObject("tax");tax.put("verified",true);tax.put("ownerUserId",7);tax.put("tradingDate",frozen.facts().date().toString());tax.put("taxYear",2027);ObjectNode rules=golden().path("rules").deepCopy();rules.put("taxYear",2027);tax.set("rules",rules);ObjectNode filing=tax.putObject("filing");filing.put("salaryIncome","1348500");filing.put("incomeTaxPrepayments","134850");filing.put("otherDomesticIncome","0");filing.put("age70OrOlder",false);
        ObjectNode profile=tax.putObject("distributions").putObject("台股:0050");profile.put("verified",true);profile.put("source","ISOLATED_OFFICIAL_NOTICE");for(String key:List.of("domesticDividendShare","domesticInterestShare","savingsInterestShare","otherDomesticIncomeShare","overseasIncomeShare","cashWithholdingRate","creditableForeignTaxRate"))profile.put(key,key.equals("domesticDividendShare")?"1":"0");ObjectNode event=profile.putArray("eventTemplate").addObject();event.put("paymentEventId","ISSUER:0050:2027-07-31");event.put("grossEventFraction","1");event.put("nhiChargeableShare","1");
        JsonNode result=SrppReportIncomePayrollCalculator.calculateIncome(frozen,fixture.policy(),SrppReportModuleCalculatorTest.model(),extra);assertThat(result.path("status").asText()).isEqualTo("COMPLETE");assertThat(fact(result,"supplementaryNhi",null)).isEqualByComparingTo("633");BigDecimal liability=fact(result,"taiwanIncomeTax",null).add(fact(result,"additionalBasicTax",null));assertThat(fact(result,"annualSettlement","2028-05-31")).isEqualByComparingTo(new BigDecimal("134850").subtract(liability));assertThat(fact(result,"afterTaxCash",null)).isEqualByComparingTo(new BigDecimal("30000").subtract(new BigDecimal("633")).subtract(liability));
    }
    @Test void ownerBoundPayrollRetainsCalculatedValuesAndExplicitProjectedInsuranceAndGuaranteeGaps() throws Exception {
        var fixture=SrppReportModuleCalculatorTest.fixture();ObjectNode extra=JSON.createObjectNode(),source=extra.putObject("payroll");source.put("verified",true);source.put("ownerUserId",7);source.put("tradingDate",fixture.frozen().facts().date().toString());source.set("config",golden().path("cases").get(13).path("config"));ObjectNode pension=source.putObject("pension");pension.put("verified",true);pension.set("config",golden().path("cases").get(19).path("pension"));JsonNode result=SrppReportIncomePayrollCalculator.calculatePayroll(fixture.frozen(),SrppReportModuleCalculatorTest.model(),extra);assertThat(result.path("status").asText()).isEqualTo("PARTIAL");assertThat(fact(result,"paymentCount",null)).isEqualByComparingTo("10");assertThat(result.path("missing").toString()).contains("OFFICIAL_PAYROLL_SCHEDULE_PENDING","EXACT_LIFETIME_PENSION_GUARANTEE_HISTORY_UNAVAILABLE");assertThat(fact(result,"calendarYearPayroll","2027:income_tax_withholding")).isEqualByComparingTo("134850");source.put("ownerUserId",8);assertThat(SrppReportIncomePayrollCalculator.calculatePayroll(fixture.frozen(),SrppReportModuleCalculatorTest.model(),extra).path("status").asText()).isEqualTo("UNAVAILABLE");source.put("ownerUserId",7);source.put("applicable",false);assertThat(SrppReportIncomePayrollCalculator.calculatePayroll(fixture.frozen(),SrppReportModuleCalculatorTest.model(),extra).path("status").asText()).isEqualTo("NOT_EVALUATED");
    }
    @Test void wholePortfolioCashIncomeMatchesOriginalIncludingSgovAndProtectedTermRenewal() throws Exception {
        JsonNode g=golden(),c=g.path("cases").get(21);var fixture=SrppReportModuleCalculatorTest.fixture();ObjectNode input=fixture.frozen().input(),snapshot=(ObjectNode)input.path("sources").path("assets").path("snapshot");ArrayNode stocks=snapshot.putArray("stocks");snapshot.putArray("funds");ArrayNode deposits=snapshot.putArray("deposits");
        ObjectNode extra=JSON.createObjectNode(),tax=extra.putObject("tax");tax.put("verified",true);tax.put("ownerUserId",7);tax.put("tradingDate",fixture.policy().baseDate().toString());tax.put("taxYear",2026);tax.set("rules",g.path("rules"));ObjectNode filing=tax.putObject("filing");filing.put("otherDomesticIncome","0");filing.put("salaryIncome","0");filing.put("incomeTaxPrepayments","0");filing.put("age70OrOlder",false);ObjectNode distributions=tax.putObject("distributions"),depositProfiles=tax.putObject("depositProfiles");
        int id=0;for(var e:c.path("profiles").properties()) {
            String code=e.getKey(),market=code.equals("0050")?"台股":"美股";JsonNode p=e.getValue();BigDecimal position=number(c.get("total")).multiply(number(c.path("weights").get(code))),annual=position.multiply(number(p.get("regular_cash_yield")));boolean deposit=code.equals("TWD_term_deposits");
            ObjectNode row=deposit?deposits.addObject():stocks.addObject();row.put("id",++id);row.put(deposit?"estimatedAnnualInterest":"estimatedDividend",annual.toPlainString());
            if(deposit){row.put("amount",position.toPlainString());row.put("currency","TWD");row.put("depositType","TERM");}else{row.put("stockCode",code);row.put("market",market);}
            ObjectNode profile=deposit?depositProfiles.putObject(Integer.toString(id)):distributions.putObject(market+":"+code);profile.put("verified",true);profile.put("source","ISOLATED_ORIGINAL_PORTFOLIO_COMPONENTS");
            for(String key:List.of("domesticDividendShare","domesticInterestShare","savingsInterestShare","otherDomesticIncomeShare","overseasIncomeShare"))profile.set(key,p.path(snake(key)));
            profile.set("cashWithholdingRate",p.path("cash_withholding_rate"));profile.set("creditableForeignTaxRate",p.path("creditable_foreign_tax_rate"));ArrayNode events=profile.putArray("eventTemplate");int eventId=0;for(JsonNode event:p.path("distribution_event_template")){ObjectNode target=events.addObject();target.put("paymentEventId",code+":"+(++eventId));target.set("grossEventFraction",event.path("gross_event_fraction"));target.set("nhiChargeableShare",event.path("nhi_chargeable_share"));}
        }
        var f=fixture.frozen().facts();var facts=new DecisionFacts.Input(fixture.policy().baseDate(),f.slot(),f.capturedAt(),f.sessions(),f.totalAssets(),f.totalDeposits(),f.termDeposits(),f.bondValue(),f.fx(),f.fxEvidence(),f.bearWindow(),f.symbols(),f.budget(),f.subaccounts(),f.emergencyValue());var frozen=new DecisionInputCapturePort.Frozen(facts,input,fixture.frozen().sourceVector(),1,fixture.frozen().assetGeneratedAt());JsonNode result=SrppReportIncomePayrollCalculator.calculateIncome(frozen,fixture.policy(),SrppReportModuleCalculatorTest.model(),extra),expected=c.path("expected");assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        Map<String,String> map=Map.of("totalGross","source_accrued_gross","nonSpendableInterest","permanent_term_deposit_interest_reinvested","spendableGross","gross","usWithholding","us_withholding","supplementaryNhi","supplementary","taiwanIncomeTax","domestic_tax","additionalBasicTax","overseas_amt","afterTaxCash","after_all_tax");map.forEach((actual,want)->assertThat(fact(result,actual,null).subtract(number(expected.get(want))).abs()).as(actual).isLessThanOrEqualTo(new BigDecimal("0.01")));assertThat(fact(result,"spendableSgovGross",null)).isEqualByComparingTo("40000");
    }
    @Test void laborInsuranceClaimDatesAndPermanentMonthlyAgeAdjustmentMatchOriginal() throws Exception {
        int count=0;for(JsonNode c:golden().path("cases"))if(c.path("kind").asText().equals("laborInsurance")) {
            JsonNode actual=SrppReportIncomePayrollCalculator.laborInsuranceClaimTerms(LocalDate.parse(c.path("birth").asText()),LocalDate.parse(c.path("retirement").asText()),c.path("policy")),expected=c.path("expected");
            for(var e:actual.properties()){JsonNode want=expected.path(snake(e.getKey()));if(e.getKey().endsWith("Date"))assertThat(e.getValue().asText()).isEqualTo(want.asText());else close(e.getValue(),want,e.getKey(),"0.000000001");}count++;
        }assertThat(count).isEqualTo(3);
    }
    @Test void malformedTaxAndPaymentRulesFailClosed() throws Exception {
        JsonNode g=golden();ObjectNode bad=g.path("rules").deepCopy();((ObjectNode)bad.path("brackets").get(0)).put("upper","-1");assertThatThrownBy(()->SrppReportIncomePayrollCalculator.annualTaiwanTax(g.path("cases").get(0).path("inputs"),bad)).isInstanceOf(IllegalArgumentException.class);ArrayNode events=JSON.createArrayNode();events.addObject().put("paymentEventId","same").put("grossEventFraction","0.5").put("nhiChargeableShare","1");events.add(events.get(0).deepCopy());assertThatThrownBy(()->SrppReportIncomePayrollCalculator.nhiFromAnnual(new BigDecimal("50000"),events,g.path("rules"))).isInstanceOf(IllegalArgumentException.class);
    }
    private static String snake(String s){return s.replaceAll("([a-z0-9])([A-Z])","$1_$2").replaceAll("([a-z])([0-9]+)","$1_$2").toLowerCase(Locale.ROOT);}
    private static BigDecimal number(JsonNode n){return new BigDecimal(n.asText());}
    private static void close(JsonNode actual,JsonNode expected,String label,String tolerance){assertThat(expected).as(label).isNotNull();assertThat(number(actual).subtract(number(expected)).abs()).as(label).isLessThanOrEqualTo(new BigDecimal(tolerance));}
    private static JsonNode value(JsonNode module,String name,String subject){for(JsonNode f:module.path("data").path("facts"))if(name.equals(f.path("name").asText())&&Objects.equals(subject,f.path("subject").isNull()?null:f.path("subject").asText()))return f.path("value");throw new AssertionError(name+":"+subject);}
    private static BigDecimal fact(JsonNode module,String name,String subject){return number(value(module,name,subject));}
}
