package com.steven.assets.controller;
import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.service.ExchangeRateExportScheduleService;
import com.steven.assets.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(ExchangeRateExportController.class)
class ExchangeRateExportControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ExchangeRateExportScheduleService service;
    @MockitoBean CurrentUserContext currentUserContext;
    ExchangeRateExportDto.SettingResponse response(){return new ExchangeRateExportDto.SettingResponse("/home/steven","GDriveOutput",List.of(
        new ExchangeRateExportDto.ScheduleItem(9L,"A",false,8,15,"input",17,null,null,false,null,null,null)),null);}
    static final String BODY="{\"name\":\"A\",\"enabled\":false,\"runHour\":8,\"runMinute\":15,\"outputSubpath\":\"input\",\"rangeMonths\":17}";
    @Test void getReturnsIndependentSchedulesAndCreateUpdateDeserializeCompleteDraft()throws Exception{
        when(service.getForCurrentUser()).thenReturn(response());
        mvc.perform(get("/api/exchange-rate-export/schedules")).andExpect(status().isOk()).andExpect(jsonPath("$.schedules[0].id").value(9)).andExpect(jsonPath("$.times").doesNotExist());
        when(service.createForCurrentUser(any())).thenReturn(response());
        mvc.perform(post("/api/exchange-rate-export/schedules").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk()).andExpect(jsonPath("$.schedules[0].name").value("A"));
        when(service.updateForCurrentUser(eq(9L),any())).thenReturn(response());
        mvc.perform(put("/api/exchange-rate-export/schedules/9").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk());
        var captured=org.mockito.ArgumentCaptor.forClass(ExchangeRateExportDto.ScheduleRequest.class);verify(service).updateForCurrentUser(eq(9L),captured.capture());
        assertThat(captured.getValue().runHour()).isEqualTo(8);assertThat(captured.getValue().runMinute()).isEqualTo(15);
        assertThat(captured.getValue().gdriveEnabled()).isNull();assertThat(captured.getValue().rangeMonths()).isEqualTo(17);
    }
    @Test void fractionalAndStringTimesAreRejectedBeforeServiceMutation()throws Exception{
        for(String value:List.of("8.5","\"8\"","true"))
            mvc.perform(post("/api/exchange-rate-export/schedules").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"runHour\":8","\"runHour\":"+value))).andExpect(status().isBadRequest());
        verify(service,never()).createForCurrentUser(any());
    }
    @Test void fractionalAndStringRangeAreRejectedBeforeServiceMutation()throws Exception{
        for(String value:List.of("17.5","\"17\"","true"))
            mvc.perform(post("/api/exchange-rate-export/schedules").contentType(MediaType.APPLICATION_JSON).content(BODY.replace("\"rangeMonths\":17","\"rangeMonths\":"+value))).andExpect(status().isBadRequest());
        verify(service,never()).createForCurrentUser(any());
    }
    @Test void deleteReturnsFullListAndRunNowKeepsSevenFields()throws Exception{
        when(service.deleteForCurrentUser(9L)).thenReturn(response());
        mvc.perform(delete("/api/exchange-rate-export/schedules/9")).andExpect(status().isOk()).andExpect(jsonPath("$.schedules").isArray());
        when(service.runNowForCurrentUser(9L)).thenReturn(new ExchangeRateExportDto.RunNowResponse("/x.xlsx",1,null,null,"/x.json",2,null));
        mvc.perform(post("/api/exchange-rate-export/schedules/9/run-now")).andExpect(status().isOk()).andExpect(jsonPath("$.path").value("/x.xlsx"))
                .andExpect(jsonPath("$.jsonPath").value("/x.json")).andExpect(jsonPath("$.jsonSizeBytes").value(2));
    }
    @Test void retiredSingletonRoutesAreGoneWithZeroServiceEffects()throws Exception{
        mvc.perform(get("/api/exchange-rate-export/schedule")).andExpect(status().isGone());
        mvc.perform(put("/api/exchange-rate-export/schedule").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isGone());
        mvc.perform(post("/api/exchange-rate-export/run-now")).andExpect(status().isGone());verify(service,never()).getForCurrentUser();
        verify(service,never()).createForCurrentUser(any());verify(service,never()).updateForCurrentUser(anyLong(),any());
        verify(service,never()).deleteForCurrentUser(anyLong());verify(service,never()).runNowForCurrentUser(anyLong());
    }
    @Test void foreignAndMissingAre404AndUnauthenticatedIs401()throws Exception{
        when(service.runNowForCurrentUser(9L)).thenThrow(new NoSuchElementException("missing"));
        mvc.perform(post("/api/exchange-rate-export/schedules/9/run-now")).andExpect(status().isNotFound());
        when(service.getForCurrentUser()).thenThrow(new UnauthenticatedException("missing"));
        mvc.perform(get("/api/exchange-rate-export/schedules")).andExpect(status().isUnauthorized());
    }
}
