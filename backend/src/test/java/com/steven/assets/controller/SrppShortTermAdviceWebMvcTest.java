package com.steven.assets.controller;

import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.srpp.settings.SrppSettingsProblem;
import com.steven.assets.service.srpp.settings.SrppShortTermService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SrppShortTermController.class)
@Import({GlobalExceptionHandler.class,SrppSettingsExceptionAdvice.class})
class SrppShortTermAdviceWebMvcTest {
    @Autowired MockMvc mvc;
    @MockitoBean SrppShortTermService service;
    @MockitoBean CurrentUserContext user;
    @Test void anonymousAndStaleDraftKeepTheirScopedStatusesInsteadOfGlobal500()throws Exception {
        when(service.list(null,null)).thenThrow(new SrppSettingsProblem(401,"UNAUTHENTICATED","請先登入有效帳號"));
        mvc.perform(get("/api/portfolio-advice/srpp/short-opportunities")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        when(service.list("draft","3")).thenThrow(new SrppSettingsProblem(409,"BASE_POLICY_CHANGED","來源已更新"));
        mvc.perform(get("/api/portfolio-advice/srpp/short-opportunities").param("draftId","draft").param("revision","3"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BASE_POLICY_CHANGED"));
    }
    @Test void unknownOwnerTargetRemains404()throws Exception {
        when(service.stock("0050","台股")).thenThrow(new SrppSettingsProblem(404,"NOT_FOUND","找不到標的"));
        mvc.perform(get("/api/portfolio-advice/srpp/short-opportunities/stock").param("stockCode","0050").param("market","台股"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
