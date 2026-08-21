package com.steven.assets.controller;

import com.steven.assets.service.TradingCalendarExportScheduleService;
import com.steven.assets.service.TradingCalendarExportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TradingCalendarExportControllerTest {

    @Test
    void illegalSubpathRemainsHttp400() throws Exception {
        TradingCalendarExportScheduleService schedule = mock(TradingCalendarExportScheduleService.class);
        when(schedule.runManualForCurrentUser("../escape"))
                .thenThrow(new IllegalArgumentException("輸出子路徑不可跳脫基底目錄"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TradingCalendarExportController(
                        mock(TradingCalendarExportService.class), schedule))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(post("/api/trading-calendar-export/run").param("subpath", "../escape"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("輸出子路徑不可跳脫基底目錄"));

        verify(schedule).runManualForCurrentUser("../escape");
    }
}
