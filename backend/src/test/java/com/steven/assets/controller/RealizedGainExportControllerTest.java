package com.steven.assets.controller;

import com.steven.assets.dto.RealizedGainExportDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.RealizedGainExportScheduleService;
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

/**
 * {@link RealizedGainExportController} MockMvc 層測試（Requirement 39 / Task 196；多時間點 Requirement 73 / Task 331）。
 *
 * <p>驗證 GET/PUT/run-now 三端點的新 {@code times[]} 契約能正確經 Jackson 序列化／反序列化，
 * 而不僅止於 {@link com.steven.assets.service.RealizedGainExportScheduleServiceTest} 的 service 層直呼——
 * 那支測不到 controller {@code @RequestBody} 綁定是否正確吃到巢狀 {@code times[]}。main 上本 controller
 * 原本沒有專屬測試檔，本檔為新建（比照油價金價頁的 {@code CommodityExportControllerTest}）。
 */
@WebMvcTest(RealizedGainExportController.class)
class RealizedGainExportControllerTest {

    @Autowired MockMvc mvc;

    @MockBean RealizedGainExportScheduleService service;
    // WebConfig 屬 WebMvcConfigurer，會被 @WebMvcTest slice 載入 → 連帶需要 AdminGateInterceptor →
    // CurrentUserContext（@RequestScope，slice 不提供）。補上 mock 讓 context 能載入；本測試路徑
    // (/api/realized-gains/export/*) 不在攔截器 path patterns 內，故此 mock 不會被實際觸發。
    @MockBean CurrentUserContext currentUserContext;

    @Test
    void getSchedule_回傳200且times陣列完整序列化() throws Exception {
        var resp = RealizedGainExportDto.SettingResponse.builder()
                .enabled(true).outputSubpath("input")
                .times(List.of(
                        new RealizedGainExportDto.TimeResponse(1L, 8, 0, true, "2026-08-15 08:00:07", "xlsx 成功：/x"),
                        new RealizedGainExportDto.TimeResponse(2L, 12, 30, false, null, null)))
                .lastRunAt("2026-08-15 08:00:07").lastRunStatus("xlsx 成功：/x").baseDir("/home/steven")
                .gdriveEnabled(false).gdriveSubpath(null).gdriveRemote("GDriveOutput")
                .gdriveLastRunAt(null).gdriveLastStatus(null).gdriveSelfCheckWarning(null)
                .build();
        when(service.getForCurrentUser()).thenReturn(resp);

        mvc.perform(get("/api/realized-gains/export/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.times", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.times[0].runHour").value(8))
                .andExpect(jsonPath("$.times[0].runMinute").value(0))
                .andExpect(jsonPath("$.times[0].enabled").value(true))
                .andExpect(jsonPath("$.times[0].lastRunStatus").value("xlsx 成功：/x"))
                .andExpect(jsonPath("$.times[1].runHour").value(12))
                .andExpect(jsonPath("$.times[1].enabled").value(false))
                // 新契約不再有 top-level runHour/runMinute
                .andExpect(jsonPath("$.runHour").doesNotExist())
                .andExpect(jsonPath("$.runMinute").doesNotExist());
    }

    @Test
    void putSchedule_times陣列正確反序列化並轉送service() throws Exception {
        var resp = RealizedGainExportDto.SettingResponse.builder()
                .enabled(true).outputSubpath("input")
                .times(List.of(new RealizedGainExportDto.TimeResponse(1L, 8, 0, true, null, null)))
                .lastRunAt(null).lastRunStatus(null).baseDir("/home/steven")
                .gdriveEnabled(false).gdriveSubpath(null).gdriveRemote("GDriveOutput")
                .gdriveLastRunAt(null).gdriveLastStatus(null).gdriveSelfCheckWarning(null)
                .build();
        when(service.updateForCurrentUser(any())).thenReturn(resp);

        String body = """
                {"enabled":true,"outputSubpath":"input",
                 "times":[{"runHour":8,"runMinute":0,"enabled":true},{"runHour":20,"runMinute":30,"enabled":false}],
                 "gdriveEnabled":null,"gdriveSubpath":null}
                """;

        mvc.perform(put("/api/realized-gains/export/schedule")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.times[0].runHour").value(8));

        var captor = org.mockito.ArgumentCaptor.forClass(RealizedGainExportDto.SettingRequest.class);
        verify(service).updateForCurrentUser(captor.capture());
        RealizedGainExportDto.SettingRequest req = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(req.times()).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(req.times().get(0).runHour()).isEqualTo(8);
        org.assertj.core.api.Assertions.assertThat(req.times().get(1).runHour()).isEqualTo(20);
        org.assertj.core.api.Assertions.assertThat(req.times().get(1).runMinute()).isEqualTo(30);
        org.assertj.core.api.Assertions.assertThat(req.times().get(1).enabled()).isFalse();
    }

    @Test
    void runNow_回傳200且七欄雙格式契約不變() throws Exception {
        var resp = RealizedGainExportDto.RunNowResponse.builder()
                .path("/home/steven/input/已實現損益_1_20260815.xlsx").sizeBytes(1234L)
                .gdrivePath(null).gdriveStatus(null)
                .jsonPath("/home/steven/input/已實現損益_1_20260815.json").jsonSizeBytes(567L)
                .jsonGdrivePath(null)
                .build();
        when(service.runNowForCurrentUser()).thenReturn(resp);

        mvc.perform(post("/api/realized-gains/export/run-now"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("/home/steven/input/已實現損益_1_20260815.xlsx"))
                .andExpect(jsonPath("$.sizeBytes").value(1234))
                .andExpect(jsonPath("$.jsonPath").value("/home/steven/input/已實現損益_1_20260815.json"))
                .andExpect(jsonPath("$.jsonSizeBytes").value(567));
        verify(service).runNowForCurrentUser();
    }
}
