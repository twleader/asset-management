package com.steven.assets.controller;

import com.steven.assets.dto.*;
import com.steven.assets.service.*;
import com.steven.assets.service.port.FundMaintenancePort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FundNavControllerTransportTest {
    @Test
    void fundStatusMappingRetainsBadRequestConflictNotFoundAndSuccess200() throws Exception {
        var funds = mock(FundApplicationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FundNavController(funds,mock(FundMaintenanceService.class))).build();
        when(funds.createFund(any())).thenThrow(new FundApplicationService.InvalidFundCodeException());
        mvc.perform(post("/api/funds").contentType(MediaType.APPLICATION_JSON).content("{\"fundCode\":\" \"}"))
                .andExpect(status().isBadRequest());
        doThrow(new FundApplicationService.FundAlreadyExistsException("F01")).when(funds).createFund(any());
        mvc.perform(post("/api/funds").contentType(MediaType.APPLICATION_JSON).content("{\"fundCode\":\"F01\"}"))
                .andExpect(status().isConflict());
        when(funds.updateFund(any(),any())).thenThrow(new FundApplicationService.FundNotFoundException());
        when(funds.setActive(any(),any())).thenThrow(new FundApplicationService.FundNotFoundException());
        mvc.perform(put("/api/funds/F01").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/funds/F01/active").contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isNotFound());
        var dto = new FundDto("F01","name",null,null,"USD",null,null,null,null,true,null,null,null,null,null,null,null);
        doReturn(dto).when(funds).createFund(any());
        mvc.perform(post("/api/funds").contentType(MediaType.APPLICATION_JSON).content("{\"fundCode\":\"F01\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fundCode").value("F01"))
                .andExpect(jsonPath("$.latestNav").isEmpty()).andExpect(jsonPath("$.bankId").isEmpty());
    }
    @Test
    void httpAndApplicationDelegateToOnlyFourFixedMaintenanceCapabilities() throws Exception {
        List<String> calls = new ArrayList<>();
        var port = new FundMaintenancePort() {
            public Map<String,Object> refreshNav() { calls.add("nav"); return Map.of("success",2); }
            public Map<String,Object> refreshDividend() { calls.add("dividend"); return Map.of("success",3); }
            public Map<String,Object> backfillNav(int years) { calls.add("nav:"+years); return Map.of("fundOk",4); }
            public Map<String,Object> backfillDividend(int years) { calls.add("dividend:"+years); return Map.of("fundOk",5); }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new FundNavController(mock(FundApplicationService.class),new FundMaintenanceService(port))).build();
        mvc.perform(post("/api/fund-nav/refresh")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(2));
        mvc.perform(post("/api/fund-dividend/refresh")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(3));
        mvc.perform(post("/api/fund-nav/backfill")).andExpect(status().isOk()).andExpect(jsonPath("$.fundOk").value(4));
        mvc.perform(post("/api/fund-dividend/backfill").param("years","7")).andExpect(status().isOk()).andExpect(jsonPath("$.fundOk").value(5));
        assertThat(calls).containsExactly("nav","dividend","nav:10","dividend:7");
    }
}
