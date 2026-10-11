package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionFacts.Policy;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.service.srpp.decision.DailyDecisionCalculator;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure ports of current_cash_income, pre_retirement_cashflow and dated pension accounting.
 * Owner-specific source contracts are frozen inputs, never the model author's personal payroll.
 */
public final class SrppReportIncomePayrollCalculator {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final BigDecimal ZERO = BigDecimal.ZERO, ONE = BigDecimal.ONE;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final List<String> COMPONENTS = List.of("domesticDividend", "domesticInterest", "savingsInterest", "otherDomesticIncome", "overseasIncome", "usWithholding", "creditableForeignTax");
    private static final List<String> PAY_FIELDS = List.of("regular_salary", "bonus", "gross_pay", "labor_and_employment_insurance", "health_insurance", "voluntary_labor_pension", "income_tax_withholding", "supplemental_nhi_high_bonus", "total_payroll_deductions", "net_cash_after_payroll", "planned_living_and_capex_cost", "tax_included_in_living_budget", "investable_surplus");
    private SrppReportIncomePayrollCalculator() {}

    public static ObjectNode calculateIncome(Frozen frozen, Policy policy, JsonNode model, JsonNode extra) {
        var m = new SrppReportModuleCalculator.M("CASH_INCOME", "D139_SOURCE_TAX_NHI_ANNUAL_SETTLEMENT", List.of("assets", "tax", "payroll", "policy"));
        JsonNode snapshot = frozen.input().path("sources").path("assets").path("snapshot");
        JsonNode source = extra.path("tax");
        boolean taxVerified = bound(source, frozen);
        Map<String, BigDecimal> securityGross = new TreeMap<>();
        Map<String, JsonNode> securityProfiles = new TreeMap<>();
        BigDecimal gross = ZERO, termBalance = ZERO, termInterest = ZERO, sgov = ZERO;
        boolean grossComplete = true, sgovComplete = true;
        Set<String> zeroSymbols = new HashSet<>(policy.nonDistributing());
        model.path("execution_policy").path("strategic_buy_high_gate_d188").path("non_distributing_symbols").forEach(v -> zeroSymbols.add(v.asText()));
        List<JsonNode> deposits = new ArrayList<>();
        for (String category : List.of("stocks", "funds", "deposits")) {
            if (!snapshot.path(category).isArray()) { m.missing("ASSET_INCOME_ROWS_UNAVAILABLE:" + category); grossComplete = false; continue; }
            for (JsonNode row : snapshot.path(category)) {
                String id = row.path("id").asText();
                boolean deposit = category.equals("deposits");
                String symbol = row.path(category.equals("funds") ? "fundCode" : "stockCode").asText();
                String market = row.path("market").asText();
                boolean transit = deposit && (row.path("currency").asText().startsWith("TRANSIT_") || row.path("depositType").asText().startsWith("TRANSIT_"));
                boolean knownZero = !deposit && "台股".equals(market) && zeroSymbols.contains(symbol);
                BigDecimal value = transit || knownZero ? ZERO : optional(row.get(deposit ? "estimatedAnnualInterest" : "estimatedDividend"));
                if (value != null && value.signum() < 0) value = null;
                m.fact(deposit ? "depositGross" : category.equals("stocks") ? "stockGross" : "fundGross", id, value, "TWD");
                if (deposit && !transit && "TWD".equals(row.path("currency").asText()) && term(row)) {
                    BigDecimal balance = optional(row.get("amount"));
                    if (balance == null || balance.signum() < 0) { grossComplete = false; m.missing("TERM_DEPOSIT_BALANCE_UNVERIFIED:" + id); }
                    else termBalance = termBalance.add(balance);
                    if (value != null) termInterest = termInterest.add(value);
                }
                if (value == null) { grossComplete = false; if ("美股".equals(market) && "SGOV".equals(symbol)) sgovComplete = false; continue; }
                gross = gross.add(value);
                if (deposit) { if (!transit) deposits.add(row); }
                else {
                    String key = market + ":" + symbol;
                    securityGross.merge(key, value, BigDecimal::add);
                    if ("美股:SGOV".equals(key)) sgov = sgov.add(value);
                    if (!knownZero) securityProfiles.put(key, source.path("distributions").path(key));
                }
            }
        }
        BigDecimal locked = termBalance.signum() > 0 ? termInterest.multiply(policy.termFloor().multiply(DailyDecisionCalculator.inflation(policy, frozen.facts().date())).min(termBalance)).divide(termBalance, MC) : ZERO;
        m.fact("totalGross", null, grossComplete ? cents(gross) : null, "TWD");
        m.fact("nonSpendableInterest", null, grossComplete ? cents(locked) : null, "TWD");
        m.fact("spendableGross", null, grossComplete ? cents(gross.subtract(locked)) : null, "TWD");
        m.fact("spendableSgovGross", null, sgovComplete ? cents(sgov) : null, "TWD");
        m.receipt("SNAPSHOT_ANNUAL_ESTIMATE", "PASS", "ANNUAL_ESTIMATE_FROM_SNAPSHOT;EXACT_IS_ARITHMETIC_ON_FROZEN_ESTIMATES_NOT_REALIZED_INCOME", List.of("assets", "tax"));
        m.receipt("D193_RENEWAL", "PASS", "ACCRUED_TERM_INTEREST_REMAINS_TAXABLE_AND_PROTECTED_SHARE_IS_RENEWED", List.of("assets", "policy"));
        if (!taxVerified) {
            for (String name : List.of("taiwanIncomeTax", "usWithholding", "additionalBasicTax", "supplementaryNhi", "afterTaxCash", "annualSettlement")) m.fact(name, null, null, "TWD");
            m.missing("OWNER_BOUND_OFFICIAL_DISTRIBUTION_TAX_COMPOSITION_AND_ACTUAL_PAYROLL_UNAVAILABLE");
            return m.done();
        }
        try {
            JsonNode rules = source.path("rules"), filing = source.path("filing");
            validateRules(rules);
            Map<String, BigDecimal> totals = new TreeMap<>(); COMPONENTS.forEach(k -> totals.put(k, ZERO));
            BigDecimal nhiBase = ZERO;
            boolean compositionComplete = grossComplete, nhiComplete = grossComplete;
            for (var entry : securityGross.entrySet()) {
                String key = entry.getKey(); BigDecimal annual = entry.getValue();
                if (annual.signum() == 0) continue;
                JsonNode profile = securityProfiles.get(key);
                try {
                    if (profile == null || !profile.path("verified").asBoolean() || profile.path("source").asText().isBlank()) throw new Gap("OFFICIAL_DISTRIBUTION_COMPONENT_UNVERIFIED:" + key);
                    validateProfile(profile);
                    for (String component : COMPONENTS) {
                        String share = switch (component) { case "usWithholding" -> "cashWithholdingRate"; case "creditableForeignTax" -> "creditableForeignTaxRate"; default -> component + "Share"; };
                        BigDecimal v = fraction(profile.get(share));
                        BigDecimal amount = annual.multiply(v);
                        totals.merge(component, amount, BigDecimal::add);
                        m.fact(component, key, cents(amount), "TWD");
                    }
                } catch (Gap gap) { compositionComplete = false; nhiComplete = false; m.missing(gap.getMessage()); continue; }
                try { nhiBase = nhiBase.add(nhiFromAnnual(annual, profile.path("eventTemplate"), rules)); }
                catch (Gap gap) { nhiComplete = false; m.missing(gap.getMessage()+":"+key); }
            }
            for (JsonNode deposit : deposits) {
                String id = deposit.path("id").asText(); BigDecimal value = optional(deposit.get("estimatedAnnualInterest"));
                if (value == null) continue;
                // No foreign-currency interest classification is inferred from its TWD-equivalent amount.
                JsonNode profile = source.path("depositProfiles").path(id);
                if (value.signum() == 0) continue;
                if (!profile.path("verified").asBoolean()) { compositionComplete = false; nhiComplete = false; m.missing("DEPOSIT_TAX_CLASSIFICATION_UNVERIFIED:" + id); continue; }
                try { validateProfile(profile); } catch (Gap gap) { compositionComplete = false; nhiComplete = false; m.missing("DEPOSIT_TAX_CLASSIFICATION_UNVERIFIED:" + id); continue; }
                for (String component : COMPONENTS) {
                    String share = switch (component) { case "usWithholding" -> "cashWithholdingRate"; case "creditableForeignTax" -> "creditableForeignTaxRate"; default -> component + "Share"; };
                    BigDecimal amount = value.multiply(fraction(profile.get(share)));
                    totals.merge(component, amount, BigDecimal::add); m.fact(component, "deposit:" + id, cents(amount), "TWD");
                }
                try { nhiBase = nhiBase.add(nhiFromAnnual(value, profile.path("eventTemplate"), rules)); }
                catch (Gap gap) { nhiComplete = false; m.missing("DEPOSIT_PAYMENT_CADENCE_UNVERIFIED:" + id); }
            }
            for (String component : COMPONENTS) m.fact(component, null, compositionComplete ? cents(totals.get(component)) : null, "TWD");
            BigDecimal nhi = nhiComplete ? nhiBase.multiply(required(rules.get("nhiRate"))) : null;
            m.fact("nhiChargeableIncome", null, nhiComplete ? cents(nhiBase) : null, "TWD");
            m.fact("supplementaryNhi", null, nhi == null ? null : cents(nhi), "TWD");
            if (!compositionComplete) {
                for (String name : List.of("taiwanIncomeTax", "additionalBasicTax", "afterTaxCash", "annualSettlement")) m.fact(name, null, null, "TWD");
                return m.done();
            }
            ObjectNode args = F.objectNode();
            for (String component : List.of("domesticDividend", "domesticInterest", "savingsInterest", "overseasIncome", "creditableForeignTax")) put(args, component, totals.get(component));
            put(args, "otherDomesticIncome", totals.get("otherDomesticIncome").add(nonnegative(filing.get("otherDomesticIncome"))));
            put(args, "salaryIncome", nonnegative(filing.get("salaryIncome")));
            if (!filing.path("age70OrOlder").isBoolean()) throw new Gap("OWNER_TAX_AGE_UNVERIFIED");
            args.set("age70OrOlder", filing.path("age70OrOlder"));
            ObjectNode tax = annualTaiwanTax(args, rules);
            for (String method : List.of("inputs", "combined", "separate", "selected"))
                tax.path(method).properties().forEach(entry -> { if (entry.getValue().isTextual()) m.fact("taxCalculation", method + ":" + entry.getKey(), required(entry.getValue()), "TWD"); });
            m.receipt("D139_TAX_ELECTION", "PASS", tax.path("selectedMethod").asText()+";ANNUAL_ESTIMATE_FROM_SNAPSHOT;OWNER_ACTUAL_TAXABLE_SALARY_AND_PREPAYMENT_REMAIN_SEPARATE", List.of("tax", "payroll", "policy"));
            BigDecimal totalTax = required(tax.path("selected").get("taiwanTaxAfterAmtAndCredit")), amt = required(tax.path("selected").get("additionalBasicTax"));
            m.fact("taiwanIncomeTax", null, cents(totalTax.subtract(amt)), "TWD"); m.fact("additionalBasicTax", null, cents(amt), "TWD");
            m.fact("afterTaxCash", null, nhi == null ? null : cents(gross.subtract(locked).subtract(nhi).subtract(totals.get("usWithholding")).subtract(totalTax)), "TWD");
            int year = source.path("taxYear").asInt(-1); if (year < 1900 || year > 9998) throw new Gap("TAX_YEAR_UNVERIFIED");
            BigDecimal prepayments = nonnegative(filing.get("incomeTaxPrepayments"));
            m.fact("annualTaxPrepayments", Integer.toString(year), cents(prepayments), "TWD");
            m.fact("annualSettlement", LocalDate.of(year + 1, 5, 31).toString(), cents(prepayments.subtract(totalTax)), "TWD");
            m.receipt("ANNUAL_SETTLEMENT", "PASS", "POSITIVE_IS_REFUND_NEGATIVE_IS_ADDITIONAL_TAX;NHI_AND_FOREIGN_WITHHOLDING_SEPARATE;PREPAYMENTS_NOT_DEDUCTED_TWICE", List.of("tax", "payroll"));
            if (rules.path("taxYear").asInt() != year || !"OFFICIAL".equals(rules.path("status").asText())) m.missing("OFFICIAL_TAX_RULES_PENDING:" + year);
        } catch (Gap | DateTimeException gap) { m.missing(gap.getMessage()); }
        return m.done();
    }

    private static void validateProfile(JsonNode profile) {
        BigDecimal sum = ZERO;
        for(String key:List.of("domesticDividendShare","domesticInterestShare","otherDomesticIncomeShare","overseasIncomeShare")) sum=sum.add(fraction(profile.get(key)));
        if(sum.compareTo(ONE)>0 || fraction(profile.get("savingsInterestShare")).compareTo(fraction(profile.get("domesticInterestShare")))>0) throw new Gap("DISTRIBUTION_COMPONENT_SUM_INVALID");
        fraction(profile.get("cashWithholdingRate")); fraction(profile.get("creditableForeignTaxRate"));
    }

    /** All inputs and rule amounts are explicit, including known zero. Refunds remain negative tax. */
    public static ObjectNode annualTaiwanTax(JsonNode inputs, JsonNode rules) {
        validateRules(rules);
        BigDecimal dividend = nonnegative(inputs.get("domesticDividend")), interest = nonnegative(inputs.get("domesticInterest")), savings = nonnegative(inputs.get("savingsInterest")).min(interest), other = nonnegative(inputs.get("otherDomesticIncome")), salary = nonnegative(inputs.get("salaryIncome")), overseas = nonnegative(inputs.get("overseasIncome")), foreign = nonnegative(inputs.get("creditableForeignTax"));
        if (!inputs.path("age70OrOlder").isBoolean()) throw new Gap("OWNER_TAX_AGE_UNVERIFIED");
        BigDecimal exemption = required(rules.get(inputs.path("age70OrOlder").asBoolean() ? "exemptionAge70" : "exemption"));
        BigDecimal savingsDeduction = savings.min(required(rules.get("savingsDeductionCap"))), salaryDeduction = salary.min(required(rules.get("salaryDeductionCap")));
        BigDecimal deductions = exemption.add(required(rules.get("standardDeduction"))).add(savingsDeduction).add(salaryDeduction);
        BigDecimal combinedNet = dividend.add(interest).add(other).add(salary).subtract(deductions).max(ZERO);
        BigDecimal credit = dividend.multiply(required(rules.get("dividendCreditRate"))).min(required(rules.get("dividendCreditCap")));
        BigDecimal combinedProgressive = progressiveTax(combinedNet, rules), combinedOrdinary = combinedProgressive.subtract(credit);
        ObjectNode combined = amt(combinedNet, ZERO, combinedOrdinary, overseas, foreign, rules);
        put(combined, "ordinaryNetIncome", combinedNet); put(combined, "progressiveTaxBeforeCredit", combinedProgressive); put(combined, "dividendCredit", credit); put(combined, "ordinaryTaxAfterCredit", combinedOrdinary);
        put(combined, "taiwanTaxAfterAmtAndCredit", combinedOrdinary.add(required(combined.get("additionalBasicTax"))));
        BigDecimal separateNet = interest.add(other).add(salary).subtract(deductions).max(ZERO);
        BigDecimal separateProgressive = progressiveTax(separateNet, rules), separateDividend = dividend.multiply(required(rules.get("separateDividendRate"))), separateOrdinary = separateProgressive.add(separateDividend);
        ObjectNode separate = amt(separateNet, dividend, separateOrdinary, overseas, foreign, rules);
        put(separate, "ordinaryNetIncome", separateNet); put(separate, "progressiveTax", separateProgressive); put(separate, "separateDividendTax", separateDividend); put(separate, "dividendCredit", ZERO); put(separate, "ordinaryTaxAfterCredit", separateOrdinary);
        put(separate, "taiwanTaxAfterAmtAndCredit", separateOrdinary.add(required(separate.get("additionalBasicTax"))));
        boolean useCombined = required(combined.get("taiwanTaxAfterAmtAndCredit")).compareTo(required(separate.get("taiwanTaxAfterAmtAndCredit"))) <= 0;
        ObjectNode out = F.objectNode(); out.put("selectedMethod", useCombined ? "COMBINED_PROGRESSIVE_WITH_8_5_PERCENT_REFUNDABLE_CREDIT" : "SEPARATE_28_PERCENT"); out.set("selected", (useCombined ? combined : separate).deepCopy()); out.set("combined", combined); out.set("separate", separate);
        ObjectNode in = out.putObject("inputs");
        Map<String, BigDecimal> vals = new LinkedHashMap<>(); vals.put("domesticDividend", dividend); vals.put("domesticInterest", interest); vals.put("savingsInterest", savings); vals.put("otherDomesticIncome", other); vals.put("salaryIncome", salary); vals.put("overseasIncome", overseas); vals.put("creditableForeignTax", foreign); vals.put("personalExemption", exemption); vals.put("standardDeduction", required(rules.get("standardDeduction"))); vals.put("savingsDeduction", savingsDeduction); vals.put("salaryDeduction", salaryDeduction); vals.forEach((k,v) -> put(in,k,v)); in.put("age70OrOlder", inputs.path("age70OrOlder").asBoolean()); return out;
    }
    private static ObjectNode amt(BigDecimal net, BigDecimal separateDividend, BigDecimal ordinary, BigDecimal overseas, BigDecimal foreign, JsonNode rules) {
        BigDecimal included = overseas.compareTo(required(rules.get("overseasInclusionThreshold"))) >= 0 ? overseas : ZERO;
        BigDecimal basicIncome = net.add(separateDividend).add(included), basicTax = basicIncome.subtract(required(rules.get("basicIncomeThreshold"))).max(ZERO).multiply(required(rules.get("basicTaxRate"))), comparison = ordinary.max(ZERO), difference = basicTax.subtract(comparison).max(ZERO), limit = included.signum() > 0 ? difference : ZERO, used = foreign.min(limit);
        ObjectNode out = F.objectNode(); put(out,"overseasIncomeIncluded",included); put(out,"basicIncome",basicIncome); put(out,"basicTax",basicTax); put(out,"ordinaryTaxForComparison",comparison); put(out,"differenceBeforeForeignTaxCredit",difference); put(out,"foreignTaxCreditLimit",limit); put(out,"foreignTaxCredit",used); put(out,"additionalBasicTax",difference.subtract(used).max(ZERO)); return out;
    }
    public static BigDecimal progressiveTax(BigDecimal taxable, JsonNode rules) {
        BigDecimal income = taxable.max(ZERO), lower = ZERO, tax = ZERO;
        for (JsonNode bracket : rules.path("brackets")) {
            BigDecimal upper = optional(bracket.get("upper")), rate = fraction(bracket.get("rate"));
            if (income.compareTo(lower) <= 0) break;
            BigDecimal slice = (upper == null ? income : income.min(upper)).subtract(lower);
            if (slice.signum() > 0) tax = tax.add(slice.multiply(rate));
            if (upper == null) break; lower = upper;
        }
        return tax;
    }
    private static void validateRules(JsonNode rules) {
        JsonNode brackets = rules.path("brackets"); if (!brackets.isArray() || brackets.isEmpty()) throw new Gap("OFFICIAL_TAX_BRACKETS_UNVERIFIED");
        BigDecimal previous = ZERO;
        for (int i=0;i<brackets.size();i++) { JsonNode b=brackets.get(i); BigDecimal upper=optional(b.get("upper")); fraction(b.get("rate")); if (i==brackets.size()-1) { if(!b.path("upper").isNull()) throw new Gap("TAX_LAST_BRACKET_UNBOUNDED_REQUIRED"); } else { if(upper==null || upper.compareTo(previous)<=0) throw new Gap("TAX_BRACKET_ORDER_INVALID"); previous=upper; } }
        for(String key:List.of("exemption","exemptionAge70","standardDeduction","salaryDeductionCap","savingsDeductionCap","dividendCreditCap","overseasInclusionThreshold","basicIncomeThreshold","nhiThreshold","nhiCap")) nonnegative(rules.get(key));
        for(String key:List.of("dividendCreditRate","separateDividendRate","basicTaxRate","nhiRate")) fraction(rules.get(key));
    }
    /** Aggregate one beneficiary/security across brokers before applying each distinct payment. */
    public static BigDecimal nhiFromAnnual(BigDecimal annual, JsonNode events, JsonNode rules) {
        if(annual.signum()==0) return ZERO;
        if(!events.isArray() || events.isEmpty()) throw new Gap("OFFICIAL_PAYMENT_CADENCE_UNVERIFIED");
        BigDecimal sum=ZERO,base=ZERO; Set<String> ids=new HashSet<>();
        for(JsonNode e:events) { String id=e.path("paymentEventId").asText(); if(id.isBlank() || !ids.add(id)) throw new Gap("DUPLICATE_OR_MISSING_PAYMENT_EVENT_ID"); BigDecimal share=fraction(e.get("grossEventFraction")); sum=sum.add(share); BigDecimal charge=annual.multiply(share).multiply(fraction(e.get("nhiChargeableShare"))); if(charge.compareTo(required(rules.get("nhiThreshold")))>=0) base=base.add(charge.min(required(rules.get("nhiCap")))); }
        if(sum.subtract(ONE).abs().compareTo(new BigDecimal("0.00000001"))>0) throw new Gap("PAYMENT_EVENT_FRACTIONS_INVALID"); return base;
    }

    public static ObjectNode calculatePayroll(Frozen frozen, JsonNode model, JsonNode extra) {
        var m=new SrppReportModuleCalculator.M("PAYROLL_CASHFLOW","DATED_PAYROLL_INSURANCE_BONUS_BUDGET_AND_PENSION",List.of("payroll","tax","policy"));
        JsonNode source=extra.path("payroll");
        if(!bound(source,frozen)) { for(String name:List.of("grossSalary","withholding","voluntaryPension","takeHome","investableContribution")) m.fact(name,null,null,"TWD"); m.missing("OWNER_BOUND_ACTUAL_PAYROLL_AND_WITHHOLDING_UNAVAILABLE"); return m.done(); }
        if(source.path("applicable").isBoolean() && !source.path("applicable").asBoolean()) return m.notEvaluated("OWNER_PAYROLL_NOT_APPLICABLE");
        try {
            JsonNode cfg=source.path("config"); ObjectNode result=payrollLedger(cfg);
            m.fact("paymentCount",null,BigDecimal.valueOf(result.path("includedPaymentCount").asInt()),"COUNT");
            for(JsonNode row:result.path("ledger")) {
                String date=row.path("pay_date").asText();
                for(var e:row.properties()) if(e.getValue().isTextual() && optional(e.getValue())!=null) m.fact("payrollDetail",date+":"+e.getKey(),required(e.getValue()),Set.of("earned_month_fraction","insurance_month_fraction").contains(e.getKey())?"RATIO":"TWD");
                m.fact("grossSalary",date,required(row.get("gross_pay")),"TWD"); m.fact("withholding",date,required(row.get("income_tax_withholding")),"TWD"); m.fact("voluntaryPension",date,required(row.get("voluntary_labor_pension")),"TWD"); m.fact("takeHome",date,required(row.get("net_cash_after_payroll")),"TWD"); m.fact("investableContribution",date,required(row.get("investable_surplus")),"TWD");
                m.receipt("PAYROLL_DATE:"+date,"PASS",row.path("year_rule_status").asText(),List.of("payroll"));
                if(row.path("year_rule_status").asText().contains("pending") || row.path("bonus_events").toString().contains("projected")) m.missing("OFFICIAL_PAYROLL_SCHEDULE_PENDING:"+date);
            }
            result.path("calendarYearTotals").properties().forEach(year->year.getValue().properties().forEach(e->m.fact("calendarYearPayroll",year.getKey()+":"+e.getKey(),required(e.getValue()),"TWD")));
            JsonNode pension=source.path("pension");
            if(pension.path("verified").asBoolean()) {
                ObjectNode projection=pensionProjection(pension.path("config"),cfg); String receipt=projection.path("receiptDate").asText();
                projection.properties().forEach(e->{if(e.getValue().isTextual() && optional(e.getValue())!=null)m.fact("pensionReceipt",receipt+":"+e.getKey(),required(e.getValue()),"TWD");});
                m.receipt("LABOR_PENSION_APPLICATION:"+projection.path("applicationDate").asText(),"PASS","ONE_LOCKED_ACCOUNT_RECEIPT_ON:"+receipt+";SIMPLE_INTEREST_PLANNING_PROXY_NO_DUPLICATE_GUARANTEE",List.of("payroll","policy"));
                m.fact("pensionReceipt",receipt+":statutoryGuaranteeTopUp",null,"TWD"); m.missing("EXACT_LIFETIME_PENSION_GUARANTEE_HISTORY_UNAVAILABLE");
            } else m.missing("OWNER_PENSION_BALANCE_AND_ELIGIBILITY_UNAVAILABLE");
            JsonNode insurance=source.path("laborInsurance");
            if(insurance.path("verified").asBoolean()) {
                ObjectNode terms=laborInsuranceClaimTerms(date(insurance,"birthDate"),date(cfg,"retirement_date"),insurance.path("policy"));
                String claim=terms.path("claimDate").asText();
                terms.properties().forEach(e->{if(e.getValue().isTextual() && optional(e.getValue())!=null)m.fact("pensionReceipt",claim+":laborInsurance:"+e.getKey(),required(e.getValue()),e.getKey().equals("permanentAdjustment")?"RATIO":e.getKey().startsWith("months")?"COUNT":"TWD");});
                m.receipt("LABOR_INSURANCE_CLAIM:"+claim,"PASS","PERMANENT_MONTH_PRORATED_AGE_ADJUSTMENT;NO_PAYMENTS_BEFORE_END_OF_INSURED_EMPLOYMENT",List.of("payroll","policy"));
                if(!insurance.path("eligibilityVerified").asBoolean())m.missing("OFFICIAL_LABOR_INSURANCE_SERVICE_AND_HIGHEST_60_MONTH_WAGES_UNAVAILABLE");
            } else m.missing("OWNER_LABOR_INSURANCE_ELIGIBILITY_UNAVAILABLE");
            // Annual salary withholding is a prepayment. The CASH_INCOME module owns one settlement receipt.
            m.receipt("PAYROLL_TAX_PREPAYMENT","PASS","DATED_OWNER_PAYROLL_CONFIG_PROJECTION;ACTUAL_PAYMENT_REQUIRES_EMPLOYER_RECORD;CALENDAR_YEAR_WITHHOLDING_IS_PREPAYMENT;ANNUAL_SETTLEMENT_IN_CASH_INCOME;PENSION_LOCKED_UNTIL_ELIGIBILITY",List.of("payroll","tax"));
        } catch(Gap|DateTimeException|ArithmeticException invalid) {m.missing("ACTUAL_PAYROLL_CONFIG_UNVERIFIED:"+invalid.getMessage());}
        return m.done();
    }

    /** Same monthly dates, rounding and cumulative budgets as pre_retirement_cashflow.calculate. */
    public static ObjectNode payrollLedger(JsonNode cfg) {
        LocalDate asOf=date(cfg,"as_of_date"),retire=date(cfg,"retirement_date"); int payday=cfg.path("payday_day_of_month").asInt(-1);
        if(!retire.isAfter(asOf)||payday<1||payday>31)throw new Gap("PAYROLL_DATE_WINDOW_INVALID");
        JsonNode profile=cfg.path("employee_profile"),planned=cfg.path("planned_living_costs");
        BigDecimal withholdingRate=fraction(profile.path("income_tax_withholding").get("rate")),pensionRate=fraction(profile.path("voluntary_labor_pension").get("employee_rate"));
        int dependents=profile.path("nhi_dependents").asInt(-1);if(dependents<0)throw new Gap("NHI_DEPENDENTS_UNVERIFIED");
        BigDecimal living=nonnegative(planned.get("monthly_living_expense")),annualOther=ZERO;
        if(!planned.path("annual_prorated_costs").isObject())throw new Gap("PLANNED_COSTS_UNVERIFIED");
        for(JsonNode v:planned.path("annual_prorated_costs"))annualOther=annualOther.add(integer(nonnegative(v)));
        LocalDate last=retire.withDayOfMonth(payday);if(retire.isAfter(last))last=retire;
        List<LocalDate> dates=new ArrayList<>();
        for(YearMonth month=YearMonth.from(asOf);!month.isAfter(YearMonth.from(last));month=month.plusMonths(1)) {LocalDate p=month.atDay(payday);if(p.isAfter(asOf)&&!p.isAfter(last))dates.add(p);}
        if(dates.isEmpty())throw new Gap("NO_PAYDATES_IN_WINDOW");
        List<JsonNode> bonuses=bonusCalendar(cfg,asOf.getYear(),retire.getYear());
        Map<Integer,BigDecimal> cumulativeBonus=new TreeMap<>(),yearBudget=new TreeMap<>(),budgetUsed=new TreeMap<>();
        for(JsonNode event:bonusCalendar(cfg,asOf.getYear(),asOf.getYear())) {LocalDate paid=date(event,"pay_date");if(!paid.isAfter(asOf))cumulativeBonus.merge(paid.getYear(),integer(salaryOn(cfg,paid).multiply(nonnegative(event.get("months")))),BigDecimal::add);}
        for(LocalDate day:dates)yearBudget.merge(day.getYear(),living.multiply(earnedFraction(day,retire)),BigDecimal::add);
        Map<LocalDate,List<JsonNode>> byDate=new TreeMap<>();
        for(JsonNode event:bonuses){LocalDate paid=date(event,"pay_date");if(paid.isAfter(asOf)&&!paid.isAfter(retire)){if(!dates.contains(paid))throw new Gap("BONUS_NOT_MONTHLY_PAYDAY");byDate.computeIfAbsent(paid,k->new ArrayList<>()).add(event);}}
        ObjectNode out=F.objectNode();ArrayNode ledger=out.putArray("ledger");BigDecimal budgetAccrued=ZERO,budgetPaid=ZERO;
        for(LocalDate day:dates){BigDecimal salary=salaryOn(cfg,day),earned=earnedFraction(day,retire);boolean finalMonth=YearMonth.from(day).equals(YearMonth.from(retire));BigDecimal insuranceFraction=finalMonth&&retire.getDayOfMonth()<retire.lengthOfMonth()?BigDecimal.valueOf(Math.min(retire.getDayOfMonth(),30)).divide(new BigDecimal("30"),MC):ONE;
            ObjectNode rule=insuranceRule(cfg,day,salary),row=ledger.addObject();row.put("pay_date",day.toString());row.put("year_rule_status",rule.path("status").asText());put(row,"contractual_monthly_salary",salary);put(row,"earned_month_fraction",earned);put(row,"insurance_month_fraction",insuranceFraction);row.put("payment_after_retirement",day.isAfter(retire));
            ArrayNode rowBonuses=row.putArray("bonus_events");BigDecimal bonus=ZERO;
            for(JsonNode event:byDate.getOrDefault(day,List.of())){BigDecimal amount=integer(salary.multiply(nonnegative(event.get("months"))));bonus=bonus.add(amount);ObjectNode detail=event.deepCopy();put(detail,"amount",amount);rowBonuses.add(detail);}
            BigDecimal regular=integer(salary.multiply(earned)),gross=regular.add(bonus);
            BigDecimal labor=integer(nonnegative(rule.get("labor_insurance_wage")).multiply(fraction(rule.get("ordinary_labor_insurance_rate")).add(fraction(rule.get("employment_insurance_rate")))).multiply(fraction(rule.get("labor_and_employment_employee_share"))).multiply(insuranceFraction));
            BigDecimal health=!finalMonth||retire.getDayOfMonth()==retire.lengthOfMonth()?integer(nonnegative(rule.get("nhi_wage")).multiply(fraction(rule.get("nhi_rate"))).multiply(fraction(rule.get("nhi_employee_share"))).multiply(BigDecimal.valueOf(1L+dependents))):ZERO;
            BigDecimal pension=integer(nonnegative(rule.get("labor_pension_wage")).multiply(pensionRate).multiply(insuranceFraction)),tax=integer(gross.multiply(withholdingRate));
            BigDecimal previousBonus=cumulativeBonus.getOrDefault(day.getYear(),ZERO),cum=previousBonus.add(bonus);cumulativeBonus.put(day.getYear(),cum);
            BigDecimal highBase=bonus.min(cum.subtract(integer(nonnegative(rule.get("nhi_wage")).multiply(new BigDecimal("4")))).max(ZERO)),supplement=integer(highBase.multiply(fraction(rule.get("supplemental_nhi_rate"))));
            BigDecimal deductions=labor.add(health).add(pension).add(tax).add(supplement),net=gross.subtract(deductions);
            budgetAccrued=budgetAccrued.add(living.add(annualOther.divide(new BigDecimal("12"),MC)).multiply(earned));BigDecimal plannedCost=integer(budgetAccrued).subtract(budgetPaid);budgetPaid=budgetPaid.add(plannedCost);
            BigDecimal available=integer(yearBudget.get(day.getYear())).subtract(budgetUsed.getOrDefault(day.getYear(),ZERO)).max(ZERO),included=planned.path("all_taxes_included").asBoolean()?tax.add(supplement).min(available):ZERO;budgetUsed.merge(day.getYear(),included,BigDecimal::add);
            Map<String,BigDecimal> vals=new LinkedHashMap<>();vals.put("nhi_wage",required(rule.get("nhi_wage")));vals.put("labor_pension_wage",required(rule.get("labor_pension_wage")));vals.put("regular_salary",regular);vals.put("bonus",bonus);vals.put("gross_pay",gross);vals.put("labor_and_employment_insurance",labor);vals.put("health_insurance",health);vals.put("voluntary_labor_pension",pension);vals.put("income_tax_withholding",tax);vals.put("supplemental_nhi_high_bonus",supplement);vals.put("total_payroll_deductions",deductions);vals.put("net_cash_after_payroll",net);vals.put("planned_living_and_capex_cost",plannedCost);vals.put("tax_included_in_living_budget",included);vals.put("investable_surplus",net.subtract(plannedCost).add(included));vals.forEach((k,v)->put(row,k,v));
        }
        ObjectNode totals=out.putObject("totals"),years=out.putObject("calendarYearTotals");Set<Integer> yearSet=new TreeSet<>();dates.forEach(d->yearSet.add(d.getYear()));
        for(String key:PAY_FIELDS){BigDecimal sum=ZERO;for(JsonNode row:ledger)sum=sum.add(required(row.get(key)));put(totals,key,sum);for(int year:yearSet){ObjectNode yr=years.has(Integer.toString(year))?(ObjectNode)years.get(Integer.toString(year)):years.putObject(Integer.toString(year));BigDecimal ys=ZERO;for(JsonNode row:ledger)if(row.path("pay_date").asText().startsWith(year+"-"))ys=ys.add(required(row.get(key)));put(yr,key,ys);}}
        out.put("includedPaymentCount",ledger.size());out.put("asOfDate",asOf.toString());out.put("retirementDate",retire.toString());return out;
    }
    private static BigDecimal earnedFraction(LocalDate payday,LocalDate retire){return YearMonth.from(payday).equals(YearMonth.from(retire))?BigDecimal.valueOf(retire.getDayOfMonth()).divide(BigDecimal.valueOf(retire.lengthOfMonth()),MC):ONE;}
    private static BigDecimal salaryOn(JsonNode cfg,LocalDate day){JsonNode profile=cfg.path("employee_profile"),increase=profile.path("annual_salary_increase");BigDecimal salary=integer(nonnegative(profile.get("monthly_salary")));if(increase.isObject()){LocalDate first=date(increase,"first_increase_date");int count=Math.max(0,day.getYear()-first.getYear()+(MonthDay.from(day).compareTo(MonthDay.from(first))>=0?1:0));salary=salary.add(integer(nonnegative(increase.get("amount"))).multiply(BigDecimal.valueOf(count)));}return salary;}
    private static ObjectNode insuranceRule(JsonNode cfg,LocalDate day,BigDecimal salary){JsonNode years=cfg.path("social_insurance_by_year");int selected=-1;for(String key:years.properties().stream().map(Map.Entry::getKey).toList()){int year;try{year=Integer.parseInt(key);}catch(NumberFormatException e){throw new Gap("INSURANCE_YEAR_INVALID");}if(year<=day.getYear())selected=Math.max(selected,year);}if(selected<0)throw new Gap("INSURANCE_RULE_UNAVAILABLE:"+day.getYear());ObjectNode rule=years.path(Integer.toString(selected)).deepCopy();if(selected!=day.getYear())rule.put("status","projection_carries_"+selected+"_rules_pending_official_"+day.getYear());if(rule.path("status").asText().isBlank())throw new Gap("INSURANCE_RULE_STATUS_UNVERIFIED");for(String key:List.of("nhi_wage","labor_pension_wage")){JsonNode levels=cfg.path("wage_brackets_115").path(key);if(levels.isArray()){if(levels.isEmpty())throw new Gap("WAGE_BRACKETS_EMPTY");BigDecimal chosen=nonnegative(levels.get(levels.size()-1)),prev=ZERO;for(JsonNode l:levels){BigDecimal v=nonnegative(l);if(v.compareTo(prev)<=0)throw new Gap("WAGE_BRACKETS_ORDER_INVALID");prev=v;if(v.compareTo(salary)>=0){chosen=v;break;}}put(rule,key,chosen);}}return rule;}
    private static List<JsonNode> bonusCalendar(JsonNode cfg,int firstYear,int lastYear){if(!cfg.path("bonus_events").isArray())throw new Gap("BONUS_CALENDAR_UNVERIFIED");Map<String,JsonNode> out=new TreeMap<>();for(JsonNode event:cfg.path("bonus_events")){LocalDate d=date(event,"pay_date");String name=event.path("name").asText();if(name.isBlank())throw new Gap("BONUS_NAME_MISSING");out.put(d+":"+name,event.deepCopy());}for(int y=firstYear;y<=lastYear;y++){for(JsonNode rule:cfg.path("recurring_bonuses")){String name=rule.path("name").asText();final int year=y;boolean explicit=out.values().stream().anyMatch(e->name.equals(e.path("name").asText())&&e.path("pay_date").asText().startsWith(year+"-"));if(explicit)continue;int month=rule.path("month_by_year").path(Integer.toString(y)).asInt(rule.path("month").asInt());LocalDate day=LocalDate.of(y,month,cfg.path("payday_day_of_month").asInt());ObjectNode event=F.objectNode();event.put("name",name);event.put("pay_date",day.toString());event.set("months",rule.path("months"));event.put("timing_status","projected_pay_month;_employer_payment_calendar_required");out.put(day+":"+name,event);}}return new ArrayList<>(out.values());}

    /** One nominal locked-account receipt, with the original simple-interest timing basis. */
    public static ObjectNode pensionProjection(JsonNode pension,JsonNode payrollConfig){LocalDate asOf=date(pension,"balance_as_of_date"),retire=date(payrollConfig,"retirement_date"),eligible=date(pension,"eligible_date"),application=eligible.isAfter(retire.plusDays(1))?eligible:retire.plusDays(1);int lag=pension.path("receipt_lag_days").asInt(-1);if(lag<0||lag>366||application.isBefore(asOf))throw new Gap("PENSION_ELIGIBILITY_INVALID");LocalDate receipt=application.plusDays(lag);ObjectNode cfg=payrollConfig.deepCopy();cfg.put("as_of_date",asOf.toString());JsonNode ledger=payrollLedger(cfg).path("ledger");BigDecimal employerRate=fraction(pension.get("employer_contribution_rate")),rate=fraction(pension.get("investment_return_assumption")),opening=nonnegative(pension.get("benefit")),employer=ZERO,employee=ZERO,earnings=opening.multiply(rate).multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(asOf,application))).divide(new BigDecimal("365.25"),MC);for(JsonNode row:ledger){BigDecimal ec=required(row.get("regular_salary")).multiply(employerRate),pc=required(row.get("voluntary_labor_pension"));employer=employer.add(ec);employee=employee.add(pc);LocalDate payday=date(row,"pay_date"),earned=payday.withDayOfMonth(payday.lengthOfMonth());if(earned.isAfter(retire))earned=retire;earnings=earnings.add(ec.add(pc).multiply(rate).multiply(BigDecimal.valueOf(Math.max(0,ChronoUnit.DAYS.between(earned,application)))).divide(new BigDecimal("365.25"),MC));}ObjectNode out=F.objectNode();out.put("applicationDate",application.toString());out.put("receiptDate",receipt.toString());put(out,"openingNominal",opening);put(out,"employerContributions",cents(employer));put(out,"employeeContributions",employee);put(out,"projectedFutureEarnings",cents(earnings));put(out,"totalNominal",cents(opening.add(employer).add(employee).add(earnings)));out.putNull("statutoryGuaranteeTopUp");return out;}
    public static ObjectNode laborInsuranceClaimTerms(LocalDate birth,LocalDate retirement,JsonNode policy) {
        int minimum=policy.path("start_age").asInt(-1),normal=policy.path("normal_claim_age").asInt(-1),cap=policy.path("maximum_adjustment_years").asInt(-1);
        if(minimum<0||normal<minimum||cap<0||cap>100)throw new Gap("LABOR_INSURANCE_AGE_RULE_UNVERIFIED");
        LocalDate earliest=birth.plusYears(minimum),normalDate=birth.plusYears(normal),claim=earliest.isAfter(retirement.plusDays(1))?earliest:retirement.plusDays(1);
        long attained=(claim.getYear()-birth.getYear())*12L+claim.getMonthValue()-birth.getMonthValue()-(claim.getDayOfMonth()<birth.getDayOfMonth()?1:0),offset=Math.max(-cap*12L,Math.min(cap*12L,attained-normal*12L));
        BigDecimal adjustment=BigDecimal.valueOf(offset).multiply(fraction(policy.get("adjustment_per_year"))).divide(new BigDecimal("12"),MC);
        ObjectNode out=F.objectNode();out.put("earliestClaimDate",earliest.toString());out.put("normalClaimDate",normalDate.toString());out.put("claimDate",claim.toString());put(out,"monthsEarly",BigDecimal.valueOf(Math.max(0,-offset)));put(out,"monthsDelayed",BigDecimal.valueOf(Math.max(0,offset)));put(out,"permanentAdjustment",adjustment);put(out,"monthlyNominal",nonnegative(policy.get("base_monthly_nominal")).multiply(ONE.add(adjustment)));return out;
    }
    private static boolean bound(JsonNode n,Frozen f){return n.path("verified").asBoolean()&&n.path("ownerUserId").isIntegralNumber()&&n.path("ownerUserId").asLong(-1)==f.input().path("ownerUserId").asLong(-2)&&n.path("tradingDate").asText().equals(f.facts().date().toString());}
    private static boolean term(JsonNode row){return Set.of("TERM","TERM_DEPOSIT","定存","定期存款").contains(row.path("depositType").asText());}
    private static LocalDate date(JsonNode n,String key){try{return LocalDate.parse(n.path(key).asText());}catch(DateTimeException e){throw new Gap("DATE_UNVERIFIED:"+key);}}
    private static BigDecimal required(JsonNode n){BigDecimal v=optional(n);if(v==null)throw new Gap("NUMERIC_SOURCE_UNVERIFIED");return v;}
    private static BigDecimal nonnegative(JsonNode n){BigDecimal v=required(n);if(v.signum()<0)throw new Gap("NEGATIVE_SOURCE_AMOUNT");return v;}
    private static BigDecimal fraction(JsonNode n){BigDecimal v=nonnegative(n);if(v.compareTo(ONE)>0)throw new Gap("SOURCE_FRACTION_INVALID");return v;}
    private static BigDecimal optional(JsonNode n){if(n==null||(!n.isNumber()&&!n.isTextual()))return null;try{return new BigDecimal(n.asText());}catch(NumberFormatException e){return null;}}
    private static BigDecimal cents(BigDecimal n){return n.setScale(2,RoundingMode.HALF_UP);}
    private static BigDecimal integer(BigDecimal n){return n.setScale(0,RoundingMode.HALF_UP);}
    private static void put(ObjectNode n,String key,BigDecimal value){n.put(key,value.signum()==0?"0":value.stripTrailingZeros().toPlainString());}
    private static final class Gap extends IllegalArgumentException{Gap(String message){super(message);}}
}
