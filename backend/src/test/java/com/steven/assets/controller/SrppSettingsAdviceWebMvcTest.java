package com.steven.assets.controller;

import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.srpp.settings.SrppSettingsProblem;
import com.steven.assets.service.srpp.settings.SrppSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC application advice discovery, including the global Exception.class fallback. */
@WebMvcTest(SrppSettingsController.class)
@Import({GlobalExceptionHandler.class,SrppSettingsExceptionAdvice.class})
class SrppSettingsAdviceWebMvcTest {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @MockitoBean SrppSettingsService service;
    @MockitoBean CurrentUserContext currentUser;
    static final UUID ID=UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final String BASE="/api/portfolio-advice/srpp";

    @Test void missingIdentityRemains401WithScopedProblemInsteadOfGlobal500()throws Exception {
        assertThat(context.getBean(GlobalExceptionHandler.class)).isNotNull();
        when(service.center()).thenThrow(new SrppSettingsProblem(401,"UNAUTHENTICATED","請先登入有效帳號"));
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.message").value("請先登入有效帳號"))
                .andExpect(jsonPath("$.title").doesNotExist());
    }
    @Test void nonAdminPublicationRemains403WithItsOriginalCodeAndMessage()throws Exception {
        when(service.publish(eq(ID),anyString())).thenThrow(new SrppSettingsProblem(403,"ADMIN_REQUIRED","正式發布需要管理者權限"));
        mvc.perform(post(BASE+"/drafts/"+ID+"/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"))
                .andExpect(jsonPath("$.message").value("正式發布需要管理者權限")).andExpect(jsonPath("$.title").doesNotExist());
    }
    @Test void staleRevisionRemains409WithScopedConflictResponse()throws Exception {
        when(service.update(eq(ID),anyString())).thenThrow(new SrppSettingsProblem(409,"REVISION_CONFLICT","草稿已更新，請重新載入後再修改"));
        mvc.perform(put(BASE+"/drafts/"+ID).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("草稿已更新，請重新載入後再修改")).andExpect(jsonPath("$.title").doesNotExist());
    }
    @Test void unexpectedErrorsStillUseExistingGlobalFallback()throws Exception {
        when(service.center()).thenThrow(new IllegalStateException("unexpected-test-error"));
        mvc.perform(get(BASE)).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"));
    }
}
