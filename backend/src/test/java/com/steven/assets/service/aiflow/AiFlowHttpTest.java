package com.steven.assets.service.aiflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.controller.*;
import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.security.AiFlowProducerFilter;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.steven.assets.service.aiflow.AiFlowFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiFlowHttpTest {
    @Test void unauthWrongMethodAndDescendantNeverReachServiceAndProblemsAreClosed()throws Exception{
        var service=mock(AiFlowService.class);MockMvc mvc=mvc(service,new AiFlowCredentialService("c","h","r"));
        mvc.perform(post("/api/srpp/ai-flow/events").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REQUIRED"));
        mvc.perform(post("/api/srpp/ai-flow/events").header("X-AI-Flow-Token","r","r").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/srpp/ai-flow/events")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/srpp/ai-flow/events/child")).andExpect(status().isNotFound());
        mvc.perform(post("/api/srpp/ai-flow/events;x=1")).andExpect(status().isNotFound());
        for(String matrix:List.of("/api;foo/srpp/ai-flow/events","/api/srpp;foo/ai-flow/events","/api/srpp/ai-flow;foo/events"))
            mvc.perform(post(matrix).header("X-AI-Flow-Token","r").content("{}")).andExpect(status().isNotFound());
        String body=mvc.perform(post("/api/srpp/ai-flow/events").header("X-AI-Flow-Token","wrong")).andReturn().getResponse().getContentAsString();
        assertThat(JSON.readTree(body).properties()).hasSize(7);assertThat(body).doesNotContain("wrong","X-User","owner");verifyNoInteractions(service);
    }
    @Test void configurationAndSizeBoundariesRejectBeforeService()throws Exception{
        var service=mock(AiFlowService.class);MockMvc mvc=mvc(service,new AiFlowCredentialService("c","h",""));
        mvc.perform(post("/api/srpp/ai-flow/events").header("X-AI-Flow-Token","r")).andExpect(status().isServiceUnavailable());
        mvc=mvc(service,new AiFlowCredentialService("c","h","r"));mvc.perform(post("/api/srpp/ai-flow/events").header("X-AI-Flow-Token","r").content("x".repeat(256*1024+1))).andExpect(status().isPayloadTooLarge());verifyNoInteractions(service);
    }
    @Test void typedDtoRetainsEveryNullableKeyWithGlobalNonNullMapper()throws Exception{
        var service=mock(AiFlowService.class);var detail=AiFlowProjection.detail(stored(complete()),complete());when(service.detail(anyString(),any())).thenReturn(detail);
        MockMvc mvc=mvc(service,new AiFlowCredentialService("c","h","r"));String body=mvc.perform(get("/api/ai-flow/runs/"+RUN).param("date",DATE.toString()).param("consumer","Codex")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var root=JSON.readTree(body);assertThat(root.path("run").has("reportedOutcome")).isTrue();assertThat(root.path("run").get("reportedOutcome").isNull()).isTrue();
        assertThat(root.path("run").properties()).hasSize(22);assertThat(root.path("nodes").get(0).has("durationMillis")).isTrue();assertThat(root.path("nodes").get(0).properties()).hasSize(24);
        assertThat(root.path("nodes").get(0).has("toolCall")).isTrue();assertThat(root.path("nodes").get(0).get("toolCall").isNull()).isTrue();
        assertThat(root.path("nodes").get(0).path("evidenceRefs").get(0).has("hash")).isTrue();
        assertThat(root.path("run").path("reportDate").asText()).isEqualTo(DATE.toString());
        assertThat(root.path("run").path("startedAt").asText()).isEqualTo(instant(AT).toString());
        assertThat(root.path("nodes").get(0).path("occurredAt").asText()).isEqualTo(instant(AT).toString());
    }
    @Test void scopedToolCallReadIsNoStoreAndNeverUsesTheGeneralDetailShape() throws Exception {
        var service=mock(AiFlowService.class);
        var call=new ToolCall("exec",List.of("exec_command"),"{\"cmd\":\"date\"}",14,false,0,null);
        when(service.toolCall(eq(RUN.toString()),eq(id(2)),any())).thenReturn(new ToolCallDetail(RUN.toString(),id(2),call));
        MockMvc mvc=mvc(service,new AiFlowCredentialService("c","h","r"));
        mvc.perform(get("/api/ai-flow/runs/"+RUN+"/nodes/"+id(2)+"/tool-call").param("date",DATE.toString()).param("consumer","Codex"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.runId").value(RUN.toString())).andExpect(jsonPath("$.eventId").value(id(2)))
                .andExpect(jsonPath("$.toolCall.parameters").value("{\"cmd\":\"date\"}"));
    }
    static MockMvc mvc(AiFlowService service,AiFlowCredentialService credentials){
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return MockMvcBuilders.standaloneSetup(new AiFlowController(service),new SrppAiFlowController(service)).setControllerAdvice(new AiFlowExceptionAdvice(),new GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(java.nio.charset.StandardCharsets.UTF_8),new MappingJackson2HttpMessageConverter(mapper))
                .addFilters(new AiFlowProducerFilter(credentials)).build();
    }
}
