package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SrppSettingsAuthorityValuesTest {
    final JsonNode model=SettingsCanonicalJson.parse("{\"labor_insurance\":{\"birth_date\":\"1970-10-15\"}}");
    final byte[] payroll="{\"employee_profile\":{\"monthly_salary\":127000}}".getBytes(StandardCharsets.UTF_8);
    ObjectNode catalog(String fields){return (ObjectNode)SettingsCanonicalJson.parse("{\"fields\":["+fields+"]}");}
    JsonNode manifest(byte[] bytes){return SettingsCanonicalJson.parse("{\"files\":{\"assumptions/pre_retirement_payroll.json\":\""+SettingsCanonicalJson.bytesHash(bytes)+"\"}}");}
    @Test void onlyVerifiedPayrollBytesAndExactCatalogPointerAreAuthoritative() {
        ObjectNode c=catalog("{\"id\":\"monthlySalary\",\"source\":\"payroll\",\"pointer\":\"/employee_profile/monthly_salary\"}");
        assertThat(SettingsAuthorityValues.extract(c,model,manifest(payroll),payroll).path("monthlySalary").intValue()).isEqualTo(127000);
        assertThatThrownBy(()->SettingsAuthorityValues.extract(c,model,manifest(payroll),null)).hasMessage("PAYROLL_SOURCE_MISMATCH");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(c,model,manifest(payroll),"{}".getBytes(StandardCharsets.UTF_8))).hasMessage("PAYROLL_SOURCE_MISMATCH");
        ((ObjectNode)c.path("fields").get(0)).put("pointer","/employee_profile");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(c,model,manifest(payroll),payroll)).hasMessage("PAYROLL_POINTER_INVALID");
        ((ObjectNode)c.path("fields").get(0)).put("pointer","/employee_profile/monthly_salary").put("id","unknown");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(c,model,manifest(payroll),payroll)).hasMessage("PAYROLL_POINTER_INVALID");
    }
    @Test void nullableRequiresBothExplicitDefaultNullAndCatalogPermission() {
        String nullable="{\"id\":\"laborPensionReceiptAmount\",\"pointer\":null,\"defaultAssumption\":null,\"nullable\":true}";
        ObjectNode c=catalog(nullable);assertThat(SettingsAuthorityValues.extract(c,model,manifest(payroll),null).path("laborPensionReceiptAmount").isNull()).isTrue();
        ((ObjectNode)c.path("fields").get(0)).put("nullable",false);
        assertThatThrownBy(()->SettingsAuthorityValues.extract(c,model,manifest(payroll),null)).hasMessage("AUTHORITY_VALUE_MISSING");
        ObjectNode missing=catalog(nullable);((ObjectNode)missing.path("fields").get(0)).remove("defaultAssumption");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(missing,model,manifest(payroll),null)).hasMessage("AUTHORITY_VALUE_MISSING");
        ObjectNode pointer=catalog("{\"id\":\"birthDate\",\"pointer\":\"/missing\",\"defaultAssumption\":null,\"nullable\":true}");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(pointer,model,manifest(payroll),null)).hasMessage("AUTHORITY_VALUE_MISSING");
    }
    @Test void unknownSourceAndDuplicateCatalogIdsFailClosed() {
        ObjectNode source=catalog("{\"id\":\"birthDate\",\"source\":\"external\",\"pointer\":\"/labor_insurance/birth_date\"}");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(source,model,manifest(payroll),payroll)).hasMessage("CATALOG_SOURCE_INVALID");
        ObjectNode duplicate=catalog("{\"id\":\"a\",\"defaultAssumption\":1},{\"id\":\"a\",\"defaultAssumption\":1}");
        assertThatThrownBy(()->SettingsAuthorityValues.extract(duplicate,model,manifest(payroll),payroll)).hasMessage("CATALOG_INVALID");
    }
}
