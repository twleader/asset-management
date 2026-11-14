package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.*;
import com.steven.assets.controller.SrppSettingsController;
import com.steven.assets.dto.SrppSettingsDto.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SrppSettingsWireDtoTest {
    static final String SHA="79d17b6863696707c5c0733c7c214fcbb7136aa78d8a25592697c5f7a3288f9f";
    JsonNode wire()throws Exception{return SettingsCanonicalJson.parse(new String(getClass().getResourceAsStream("/srpp/settings-result-wire.json").readAllBytes(),StandardCharsets.UTF_8));}
    @Test void actualPythonMoneyWireRoundTripsThroughTypedBigDecimalAndGlobalNonNullHttpWithExactHash()throws Exception {
        JsonNode original=wire();assertThat(SettingsCanonicalJson.hash(original)).isEqualTo(SHA);
        SimulationEnvelope typed=SettingsCanonicalJson.bind(original,SimulationEnvelope.class);
        assertThat(typed.result().scenario().terminalAssets().p50()).isEqualTo(new BigDecimal("10398753.31"));
        assertThat(typed.result().scenario().terminalAssets().p50().scale()).isEqualTo(2);
        assertThat(SettingsCanonicalJson.hash(SettingsCanonicalJson.tree(typed))).isEqualTo(SHA);
        String id=UUID.randomUUID().toString();RunResponse run=new RunResponse(id,UUID.randomUUID().toString(),1,"LOCAL","SUCCEEDED",typed.configSha256(),typed.inputSha256(),SHA,"NOT_REQUESTED",null,null,"2026-11-14T00:00:00Z","2026-11-14T00:00:00Z",typed,List.of(),false,List.of(),null);
        SrppSettingsService service=mock(SrppSettingsService.class);when(service.simulation(UUID.fromString(id))).thenReturn(run);
        ObjectMapper mapper=new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        var mvc=MockMvcBuilders.standaloneSetup(new SrppSettingsController(service)).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        String raw=mvc.perform(get("/api/portfolio-advice/srpp/simulations/"+id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode api=SettingsCanonicalJson.parse(raw).path("deterministicResult");assertThat(SettingsCanonicalJson.hash(api)).isEqualTo(SHA);
        assertThat(api.path("result").path("scenario").has("depletionAge")).isTrue();
        assertThat(api.path("result").path("scenario").path("percentilePaths").get(0).path("p50").isTextual()).isTrue();
        assertThat(api.path("result").path("scenario").path("successProbability").isNumber()).isTrue();
    }
    @Test void monetaryPrecisionBeyondDoubleIsRetainedAsDecimalStrings() {
        MoneyQuantiles money=new MoneyQuantiles(new BigDecimal("12345678901234567890123.01"),new BigDecimal("12345678901234567890123.02"),new BigDecimal("12345678901234567890123.03"));
        JsonNode wire=SettingsCanonicalJson.tree(money);assertThat(wire.path("p50").textValue()).isEqualTo("12345678901234567890123.02");
        assertThat(SettingsCanonicalJson.bind(wire,MoneyQuantiles.class).p90().subtract(money.p10())).isEqualByComparingTo("0.02");
    }
}
