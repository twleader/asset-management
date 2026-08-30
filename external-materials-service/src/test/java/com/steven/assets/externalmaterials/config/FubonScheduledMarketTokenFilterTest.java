package com.steven.assets.externalmaterials.config;

import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import com.steven.assets.externalmaterials.controller.FubonDividendSyncController;
import com.steven.assets.externalmaterials.controller.FubonTechnicalIndicatorController;
import com.steven.assets.externalmaterials.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FubonScheduledMarketTokenFilterTest {
    @TempDir Path temporary;
    final FubonDividendEvidenceSyncService dividend = mock(FubonDividendEvidenceSyncService.class);
    final FubonTechnicalIndicatorSyncService technical = mock(FubonTechnicalIndicatorSyncService.class);
    final FubonTechnicalIndicatorReadService reader = mock(FubonTechnicalIndicatorReadService.class);
    MockMvc mvc;
    @BeforeEach void setup() throws Exception {
        Path token = temporary.resolve("fake-token");
        Files.writeString(token, "fake-test-token");
        var config = new FubonMarketConfigState("true", "http://fake.invalid", token.toString());
        mvc = MockMvcBuilders.standaloneSetup(new FubonDividendSyncController(dividend),
                        new FubonTechnicalIndicatorController(technical, reader))
                .addFilters(new FubonScheduledMarketTokenFilter(config)).build();
    }
    @Test void missingAndWrongTokensRejectBeforeServiceAndPostDefaultsDryRun() throws Exception {
        mvc.perform(post("/internal/dividend/fubon-sync")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/dividend/fubon-sync").header("X-Internal-Service-Token", "bad")).andExpect(status().isForbidden());
        verifyNoInteractions(dividend, technical, reader);
        mvc.perform(post("/internal/dividend/fubon-sync").header("X-Internal-Service-Token", "fake-test-token")).andExpect(status().isOk());
        verify(dividend).sync(true);
    }
    @Test void pathAliasesOtherMethodsBodiesAndSelectorsAreRejected() throws Exception {
        for (String path : new String[]{"/internal/dividend/fubon-sync/", "/internal/dividend/fubon-sync;extra=1"}) {
            mvc.perform(post(path).header("X-Internal-Service-Token", "fake-test-token")).andExpect(status().isNotFound());
        }
        mvc.perform(get("/internal/dividend/fubon-sync").header("X-Internal-Service-Token", "fake-test-token")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/dividend/fubon-sync").header("X-Internal-Service-Token", "fake-test-token").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/technical-indicators/fubon-sync").header("X-Internal-Service-Token", "fake-test-token")
                .param("symbol", "2330")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/technical-indicators/fubon-sync").header("X-Internal-Service-Token", "fake-test-token")
                .param("dryRun", "yes")).andExpect(status().isBadRequest());
        mvc.perform(get("/internal/technical-indicators/fubon-cache").header("X-Internal-Service-Token", "fake-test-token")
                .param("symbol", "2330").param("refresh", "true")).andExpect(status().isBadRequest());
        verifyNoInteractions(dividend, technical, reader);
    }
    @Test void explicitWriteRequiresExactFalseAndDuplicateTokenOrQueryIsRejected() throws Exception {
        mvc.perform(post("/internal/technical-indicators/fubon-sync").header("X-Internal-Service-Token", "fake-test-token")
                .param("dryRun", "false")).andExpect(status().isOk());
        verify(technical).sync(false);
        clearInvocations(technical);
        mvc.perform(post("/internal/technical-indicators/fubon-sync").header("X-Internal-Service-Token", "fake-test-token", "fake-test-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/technical-indicators/fubon-sync").header("X-Internal-Service-Token", "fake-test-token")
                .param("dryRun", "false", "true")).andExpect(status().isBadRequest());
        verifyNoInteractions(technical);
    }
}
