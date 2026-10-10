package com.steven.assets.externalmaterials.config;

import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import com.steven.assets.externalmaterials.controller.FubonIntradayCandleReadController;
import com.steven.assets.externalmaterials.service.FubonIntradayCandleReadService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FubonIntradayCandleReadTokenTest {
    @TempDir Path temporary;
    final FubonIntradayCandleReadService reader=mock(FubonIntradayCandleReadService.class);
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        Path token=temporary.resolve("token"); Files.writeString(token,"fake-token");
        mvc=MockMvcBuilders.standaloneSetup(new FubonIntradayCandleReadController(reader))
                .addFilters(new FubonScheduledMarketTokenFilter(new FubonMarketConfigState("true","http://fake.invalid",token.toString())))
                .build();
    }
    @Test void authenticationExactMethodPathAndSelectorChecksPrecedeReader() throws Exception {
        String route="/internal/market-data/intraday-candles/batch-read";
        mvc.perform(get(route)).andExpect(status().isUnauthorized());
        mvc.perform(get(route).header("X-Internal-Service-Token","wrong")).andExpect(status().isForbidden());
        mvc.perform(get(route).header("X-Internal-Service-Token","fake-token","second")).andExpect(status().isForbidden());
        for(String alias:new String[]{route+"/",route+";x=1"})
            mvc.perform(get(alias).header("X-Internal-Service-Token","fake-token")).andExpect(status().isNotFound());
        mvc.perform(post(route).header("X-Internal-Service-Token","fake-token")).andExpect(status().isNotFound());
        mvc.perform(get(route).header("X-Internal-Service-Token","fake-token").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(route).header("X-Internal-Service-Token","fake-token").param("refresh","true"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(route).header("X-Internal-Service-Token","fake-token").param("stockCodes","2330","2317"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reader);
        mvc.perform(get(route).header("X-Internal-Service-Token","fake-token").param("stockCodes","2330")
                .param("tradingDate","2026-10-09").param("asOf","2026-10-09T02:00:00Z")).andExpect(status().isOk());
        verify(reader).read("2330","2026-10-09","2026-10-09T02:00:00Z");
    }
}
