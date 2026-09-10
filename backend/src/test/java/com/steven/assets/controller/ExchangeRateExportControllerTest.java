package com.steven.assets.controller;

import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.ExchangeRateExportScheduleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Requirement 145 / Task 423：controller JSON 只輸出 times[]，PUT 可讀巢狀時間。 */
@WebMvcTest(ExchangeRateExportController.class)
class ExchangeRateExportControllerTest {
    @Autowired MockMvc mvc;
    @MockBean ExchangeRateExportScheduleService service;
    @MockBean CurrentUserContext currentUserContext;

    private static ExchangeRateExportDto.SettingResponse response() {
        return ExchangeRateExportDto.SettingResponse.builder().enabled(true).outputSubpath("input").rangeMonths(60)
                .times(List.of(new ExchangeRateExportDto.TimeResponse(1L, 8, 0, true, "2026-09-11 08:00:00", "成功"),
                        new ExchangeRateExportDto.TimeResponse(2L, 18, 0, false, null, null)))
                .lastRunAt("2026-09-11 08:00:00").lastRunStatus("成功").baseDir("/home/steven")
                .gdriveEnabled(false).gdriveSubpath(null).gdriveRemote("GDriveOutput")
                .gdriveLastRunAt(null).gdriveLastStatus(null).gdriveSelfCheckWarning(null).build();
    }

    @Test
    void getSchedule_序列化times且不洩漏舊topLevel時間() throws Exception {
        when(service.getForCurrentUser()).thenReturn(response());
        mvc.perform(get("/api/exchange-rate-export/schedule"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.times", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.times[0].runHour").value(8)).andExpect(jsonPath("$.times[1].enabled").value(false))
                .andExpect(jsonPath("$.runHour").doesNotExist()).andExpect(jsonPath("$.runMinute").doesNotExist());
    }

    @Test
    void putSchedule_反序列化times並委派() throws Exception {
        when(service.updateForCurrentUser(any())).thenReturn(response());
        mvc.perform(put("/api/exchange-rate-export/schedule").contentType(MediaType.APPLICATION_JSON).content("""
                {"enabled":true,"outputSubpath":"input","rangeMonths":60,
                 "times":[{"runHour":8,"runMinute":0,"enabled":true},{"runHour":18,"runMinute":0,"enabled":false}]}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.times[1].runHour").value(18));
        var captor = org.mockito.ArgumentCaptor.forClass(ExchangeRateExportDto.SettingRequest.class);
        verify(service).updateForCurrentUser(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().times()).extracting(ExchangeRateExportDto.TimeRequest::runHour)
                .containsExactly(8, 18);
    }

    @Test
    void runNow_維持既有雙格式回應() throws Exception {
        when(service.runNowForCurrentUser()).thenReturn(ExchangeRateExportDto.RunNowResponse.builder()
                .path("/tmp/a.xlsx").sizeBytes(1).gdrivePath(null).gdriveStatus(null)
                .jsonPath("/tmp/a.json").jsonSizeBytes(2).jsonGdrivePath(null).build());
        mvc.perform(post("/api/exchange-rate-export/run-now")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonPath").value("/tmp/a.json")).andExpect(jsonPath("$.jsonSizeBytes").value(2));
    }
}
