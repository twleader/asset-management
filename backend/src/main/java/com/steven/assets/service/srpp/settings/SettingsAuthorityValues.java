package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Extracts only catalog-authorized values from verified immutable source documents. */
final class SettingsAuthorityValues {
    private SettingsAuthorityValues() {}
    static final Map<String,String> PAYROLL_POINTERS=Map.of(
            "monthlySalary","/employee_profile/monthly_salary",
            "salaryPayday","/payday_day_of_month",
            "annualRaiseAmount","/employee_profile/annual_salary_increase/amount",
            "annualRaiseFirstDate","/employee_profile/annual_salary_increase/first_increase_date",
            "withholdingRate","/employee_profile/income_tax_withholding/rate",
            "selfPensionRate","/employee_profile/voluntary_labor_pension/employee_rate",
            "nhiDependents","/employee_profile/nhi_dependents",
            "preRetirementMonthlyExpense","/planned_living_costs/monthly_living_expense",
            "bonusEvents","/bonus_events",
            "recurringBonuses","/recurring_bonuses");

    static ObjectNode extract(JsonNode document,JsonNode model,JsonNode manifest,byte[] payrollBytes) {
        ObjectNode values=JsonNodeFactory.instance.objectNode();JsonNode payroll=null;
        if(!document.path("fields").isArray())throw new IllegalArgumentException("CATALOG_INVALID");
        for(JsonNode field:document.path("fields")) {
            String key=field.path("id").asText();String source=field.has("source")?field.path("source").asText():"model";
            if(key.isEmpty()||values.has(key))throw new IllegalArgumentException("CATALOG_INVALID");
            JsonNode root;
            if("payroll".equals(source)) {
                String pointer=PAYROLL_POINTERS.get(key);
                if(pointer==null||!field.path("pointer").isTextual()||!pointer.equals(field.path("pointer").asText()))
                    throw new IllegalArgumentException("PAYROLL_POINTER_INVALID");
                if(payroll==null) {
                    if(payrollBytes==null||!SettingsCanonicalJson.bytesHash(payrollBytes).equals(
                            manifest.path("files").path("assumptions/pre_retirement_payroll.json").asText()))
                        throw new IllegalArgumentException("PAYROLL_SOURCE_MISMATCH");
                    payroll=SettingsCanonicalJson.parse(new String(payrollBytes,StandardCharsets.UTF_8));
                }
                root=payroll;
            } else if("model".equals(source))root=model;
            else throw new IllegalArgumentException("CATALOG_SOURCE_INVALID");
            JsonNode value=field.path("pointer").isTextual()?root.at(field.path("pointer").asText()):field.path("defaultAssumption");
            boolean allowedNull=field.path("nullable").isBoolean()&&field.path("nullable").booleanValue()
                    &&field.path("pointer").isNull()&&field.has("defaultAssumption")&&field.path("defaultAssumption").isNull();
            if(value.isMissingNode()||value.isNull()&&!allowedNull)throw new IllegalArgumentException("AUTHORITY_VALUE_MISSING");
            values.set(key,value.deepCopy());
        }
        return values;
    }
}
