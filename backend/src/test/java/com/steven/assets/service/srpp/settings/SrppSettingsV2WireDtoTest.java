package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.controller.SrppSettingsController;
import com.steven.assets.dto.SrppSettingsDto.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SrppSettingsV2WireDtoTest {
    JsonNode wire()throws Exception{return SettingsCanonicalJson.parse(new String(getClass().getResourceAsStream("/srpp/settings-result-wire-v2.json").readAllBytes(),StandardCharsets.UTF_8));}
    @Test void actualPythonV2EvidenceSurvivesTypedRecordsGlobalNonNullAndHttpWithoutHashDrift()throws Exception {
        JsonNode original=wire();String pythonHash=new String(getClass().getResourceAsStream("/srpp/settings-result-wire-v2.sha256").readAllBytes(),StandardCharsets.UTF_8).strip();
        assertThat(SettingsCanonicalJson.hash(original)).isEqualTo(pythonHash);SrppSettingsService.validateResult(original);
        SimulationEnvelope typed=SettingsCanonicalJson.bind(original,SimulationEnvelope.class);
        assertThat(SettingsCanonicalJson.hash(SettingsCanonicalJson.tree(typed))).isEqualTo(pythonHash);
        assertThat(typed.result().scenario().assumptions().cashflowSchedule().laborInsurance().monthlyNominalTwd()).isEqualTo(new BigDecimal("16013.60"));
        assertThat(typed.result().scenario().assumptions().cashflowSchedule().payroll().bonusEvents().get(2).festivalDate()).isNull();
        assertThat(typed.result().scenario().assumptions().reserveStrategy().protectedReserveRealTwd()).isEqualTo(new BigDecimal("1600000.00"));
        assertThat(typed.result().scenario().reserveStrategyAudit().monthlyAudit()).hasSize(typed.result().scenario().reserveStrategyAudit().monthUpdateCount());
        assertThatThrownBy(()->typed.result().scenario().assumptions().cashflowSchedule().payroll().ledger().clear()).isInstanceOf(UnsupportedOperationException.class);
        String id=UUID.randomUUID().toString();RunResponse run=new RunResponse(id,UUID.randomUUID().toString(),1,"LOCAL","SUCCEEDED",typed.configSha256(),typed.inputSha256(),pythonHash,"NOT_REQUESTED",null,null,"2026-11-14T00:00:00Z","2026-11-14T00:00:00Z",typed,List.of(),false,List.of(),null);
        SrppSettingsService service=mock(SrppSettingsService.class);when(service.simulation(UUID.fromString(id))).thenReturn(run);
        ObjectMapper mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var mvc=MockMvcBuilders.standaloneSetup(new SrppSettingsController(service)).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        JsonNode http=SettingsCanonicalJson.parse(mvc.perform(get("/api/portfolio-advice/srpp/simulations/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("deterministicResult");
        assertThat(SettingsCanonicalJson.hash(http)).isEqualTo(pythonHash);
        assertThat(http.path("result").path("scenario").path("assumptions").path("cashflowSchedule").path("majorExpenses").get(0).has("exclusionReason")).isTrue();
    }
    @Test void invalidV2ScheduleMoneyCountsModesAndEvidenceAreRejected()throws Exception {
        List<Consumer<ObjectNode>> mutations=List.of(
            n->n.put("engineVersion","SRPP_SCENARIO_CASHFLOW_V1"),
            n->((ObjectNode)n.at("/result/scenario/assumptions")).remove("cashflowSchedule"),
            n->((ObjectNode)n.at("/result/scenario/assumptions/cashflowSchedule/laborPension")).put("amountNominalTwd",100.01),
            n->((ObjectNode)n.at("/result/scenario/assumptions/cashflowSchedule/laborInsurance")).put("monthlyNominalTwd","-0.00"),
            n->((ObjectNode)n.at("/result/scenario/assumptions/cashflowSchedule/laborInsurance")).put("indexationBasis","UNKNOWN"),
            n->((ObjectNode)n.at("/result/scenario/assumptions/reserveStrategy")).put("bearBondSaleMaxShare",2),
            n->((ObjectNode)n.at("/result/scenario/reserveStrategyAudit")).put("monthUpdateCount",100000),
            n->((ObjectNode)n.at("/result/scenario/reserveStrategyAudit/monthlyAudit/0")).put("bullSignalPathCount",100001),
            n->((ObjectNode)n.at("/result/scenario/reserveStrategyAudit/monthlyAudit/0/livingRefillRealTwd")).put("p10","100000000.00"));
        for(Consumer<ObjectNode> mutate:mutations) {
            ObjectNode bad=(ObjectNode)wire();mutate.accept(bad);
            assertThatThrownBy(()->SrppSettingsService.validateResult(bad)).isInstanceOf(SrppSettingsProblem.class).hasMessage("模擬結果格式無效");
        }
        ObjectNode unknown=(ObjectNode)wire();((ObjectNode)unknown.at("/result/scenario/assumptions/reserveStrategy")).put("unknown",true);
        assertThatThrownBy(()->SettingsCanonicalJson.bind(unknown,SimulationEnvelope.class)).hasMessage("JSON_DTO_INVALID");
    }
}
