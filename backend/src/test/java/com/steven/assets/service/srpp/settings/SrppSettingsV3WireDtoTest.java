package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.controller.SrppSettingsController;
import com.steven.assets.dto.SrppSettingsDto.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SrppSettingsV3WireDtoTest {
    JsonNode wire()throws Exception{return SettingsCanonicalJson.parse(new String(getClass().getResourceAsStream("/srpp/settings-result-wire-v3.json").readAllBytes(),StandardCharsets.UTF_8));}
    @Test void actualPythonProgressiveTaxSurvivesTypedDateMoneyAndRealHttpWithoutHashDrift()throws Exception {
        JsonNode original=wire();String hash=new String(getClass().getResourceAsStream("/srpp/settings-result-wire-v3.sha256").readAllBytes(),StandardCharsets.UTF_8).strip();
        assertThat(SettingsCanonicalJson.hash(original)).isEqualTo(hash);SrppSettingsService.validateResult(original);
        SimulationEnvelope typed=SettingsCanonicalJson.bind(original,SimulationEnvelope.class);
        assertThat(SettingsCanonicalJson.hash(SettingsCanonicalJson.tree(typed))).isEqualTo(hash);
        AnnualIncomeTax tax=typed.result().scenario().assumptions().cashflowSchedule().payroll().annualIncomeTax();
        assertThat(tax.scope()).isEqualTo("SALARY_ONLY_SINGLE_RESIDENT_PLANNING");assertThat(tax.ruleYear()).isEqualTo(2026);
        assertThat(tax.years().getFirst().settlementDate()).isEqualTo(LocalDate.of(2027,5,31));
        assertThat(tax.years().getFirst().brackets().getLast().upperNominalTwd()).isNull();
        assertThat(tax.years().getFirst().marginalRate()).isEqualTo(.2);
        assertThatThrownBy(()->tax.years().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->tax.years().getFirst().brackets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->tax.notes().clear()).isInstanceOf(UnsupportedOperationException.class);
        String id=UUID.randomUUID().toString();RunResponse run=new RunResponse(id,UUID.randomUUID().toString(),1,"LOCAL","SUCCEEDED",typed.configSha256(),typed.inputSha256(),hash,"NOT_REQUESTED",null,null,"2026-11-14T00:00:00Z","2026-11-14T00:00:00Z",typed,List.of(),false,List.of(),null);
        SrppSettingsService service=mock(SrppSettingsService.class);when(service.simulation(UUID.fromString(id))).thenReturn(run);
        ObjectMapper mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var mvc=MockMvcBuilders.standaloneSetup(new SrppSettingsController(service)).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        JsonNode http=SettingsCanonicalJson.parse(mvc.perform(get("/api/portfolio-advice/srpp/simulations/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("deterministicResult");
        assertThat(SettingsCanonicalJson.hash(http)).isEqualTo(hash);
        assertThat(http.at("/result/scenario/assumptions/cashflowSchedule/payroll/annualIncomeTax/years/0/settlementDate").textValue()).isEqualTo("2027-05-31");
    }
    @Test void taxMoneyKeepsTwentyThreeDigitPrecisionAndRejectsInvalidDateOrTreatment()throws Exception {
        ObjectNode source=(ObjectNode)wire();((ObjectNode)source.at("/result/scenario/assumptions/cashflowSchedule/payroll/annualIncomeTax/years/0")).put("grossSalaryNominalTwd","12345678901234567890123.45");
        JsonNode typed=SettingsCanonicalJson.tree(SettingsCanonicalJson.bind(source,SimulationEnvelope.class));
        assertThat(typed.at("/result/scenario/assumptions/cashflowSchedule/payroll/annualIncomeTax/years/0/grossSalaryNominalTwd").textValue()).isEqualTo("12345678901234567890123.45");
        for(String field:List.of("settlementDate","treatment")) {
            ObjectNode bad=(ObjectNode)wire();((ObjectNode)bad.at("/result/scenario/assumptions/cashflowSchedule/payroll/annualIncomeTax/years/0")).put(field,"INVALID");
            assertThatThrownBy(()->SettingsCanonicalJson.bind(bad,SimulationEnvelope.class)).isInstanceOf(IllegalArgumentException.class).hasMessage("JSON_DTO_INVALID");
        }
    }
}
