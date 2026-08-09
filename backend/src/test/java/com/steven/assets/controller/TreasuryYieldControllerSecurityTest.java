package com.steven.assets.controller;

import com.steven.assets.dto.TreasuryYieldDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.TreasuryYieldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TreasuryYieldController.class)
class TreasuryYieldControllerSecurityTest {

    @Autowired MockMvc mvc;
    @MockBean TreasuryYieldService service;
    @MockBean CurrentUserContext currentUser;

    @BeforeEach
    void resetContext() {
        reset(currentUser, service);
    }

    @Test
    void anonymous查詢是401() throws Exception {
        when(currentUser.hasUser()).thenReturn(false);
        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 一般使用者refresh是403() throws Exception {
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.isAdmin()).thenReturn(false);
        mvc.perform(post("/internal/macro/treasury-yield/refresh").param("year", "2026"))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin可查詢與refresh() throws Exception {
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.isAdmin()).thenReturn(true);
        when(service.findByYear(2026)).thenReturn(List.of());
        when(service.refresh(2026)).thenReturn(new TreasuryYieldDto.RefreshSummary(2026, 1, 1, 0, 1, 0));

        mvc.perform(get("/internal/macro/treasury-yield").param("year", "2026"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/macro/treasury-yield/refresh").param("year", "2026"))
                .andExpect(status().isOk());
    }
}
