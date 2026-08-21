package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FubonInventorySyncControllerTest {
    private FubonInventorySyncService syncService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        syncService = mock(FubonInventorySyncService.class);
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "exact-token", null));
        FubonInternalTokenFilter filter = new FubonInternalTokenFilter(
                config, new FubonOutcomeCounters(), new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new FubonInventorySyncController(syncService))
                .addFilters(filter)
                .build();
    }

    @Test
    void exactPostDefaultsToDryRunTrue() throws Exception {
        when(syncService.syncManual(true)).thenReturn(response(true));

        mvc.perform(post(FubonInternalTokenFilter.PATH)
                        .header(FubonHttpClient.TOKEN_HEADER, "exact-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DRY_RUN"))
                .andExpect(jsonPath("$.dryRun").value(true));

        verify(syncService).syncManual(true);
    }

    @Test
    void explicitFalseReachesCommitModeButWrongMethodAndPathDoNot() throws Exception {
        when(syncService.syncManual(false)).thenReturn(response(false));

        mvc.perform(post(FubonInternalTokenFilter.PATH)
                        .queryParam("dryRun", "false")
                        .header(FubonHttpClient.TOKEN_HEADER, "exact-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false));
        mvc.perform(get(FubonInternalTokenFilter.PATH)
                        .header(FubonHttpClient.TOKEN_HEADER, "exact-token"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(FubonInternalTokenFilter.PATH + "/extra")
                        .header(FubonHttpClient.TOKEN_HEADER, "exact-token"))
                .andExpect(status().isNotFound());

        verify(syncService).syncManual(false);
    }

    private FubonDtos.SyncResponse response(boolean dryRun) {
        return new FubonDtos.SyncResponse(FubonOutcome.DRY_RUN, dryRun, "batch-1",
                1, 0, null, "DRY_RUN_COMPLETE", Map.of());
    }
}
