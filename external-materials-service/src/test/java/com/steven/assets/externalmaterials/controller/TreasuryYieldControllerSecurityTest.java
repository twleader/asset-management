package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.client.TreasuryYieldFetchClient;
import com.steven.assets.externalmaterials.config.TreasuryYieldAdminFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TreasuryYieldControllerSecurityTest {

    private static final String TOKEN = "test-only-treasury-service-token";

    private final TreasuryYieldFetchClient fetchClient = new TreasuryYieldFetchClient(new ObjectMapper()) {
        @Override
        public List<CurveBatch> fetchYear(int year) {
            return List.of();
        }
    };
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new TreasuryYieldController(fetchClient))
            .addFilters(new TreasuryYieldAdminFilter(TOKEN))
            .build();

    @Test
    void anonymous是401() throws Exception {
        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 偽造admin角色但沒有serviceToken仍是401() throws Exception {
        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 錯誤serviceToken是403() throws Exception {
        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026")
                        .header("X-Internal-Service-Token", "forged")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 正確serviceToken但非admin仍是403() throws Exception {
        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026")
                        .header("X-Internal-Service-Token", TOKEN)
                        .header("X-User-Role", "USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 正確serviceToken加admin可查詢() throws Exception {
        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026")
                        .header("X-Internal-Service-Token", TOKEN)
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }
}
