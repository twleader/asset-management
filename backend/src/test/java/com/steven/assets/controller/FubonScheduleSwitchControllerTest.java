package com.steven.assets.controller;

import com.steven.assets.dto.FubonScheduleSwitchDto;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.FubonScheduleSwitchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FubonScheduleSwitchControllerTest {

    private final FubonScheduleSwitchService service = mock(FubonScheduleSwitchService.class);
    private final CurrentUserContext user = mock(CurrentUserContext.class);
    private final FubonScheduleSwitchController controller = new FubonScheduleSwitchController(service, user);

    @Test
    void listIsOpenToAnyLoggedInUser() {
        when(service.list()).thenReturn(List.of(new FubonScheduleSwitchDto("trade", true, Instant.EPOCH)));
        assertThat(controller.list()).hasSize(1);
    }

    @Test
    void nonAdminCannotUpdate() {
        when(user.isAdmin()).thenReturn(false);
        assertThatThrownBy(() -> controller.update("trade", java.util.Map.of("enabled", true)))
                .isInstanceOf(AdminRequiredException.class);
        verify(service, never()).setEnabled("trade", true);
    }

    @Test
    void anythingButAJsonBooleanIsRejectedWith400AndNeverReachesTheService() throws Exception {
        when(user.isAdmin()).thenReturn(true);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : new String[] {"{}", "{\"enabled\":null}", "{\"enabled\":\"true\"}", "{\"enabled\":\"false\"}",
                "{\"enabled\":0}", "{\"enabled\":1}", "{\"enabled\":[]}", "{\"enabled\":{}}", "[]", "\"x\"", "not json"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings/fubon-schedule-switches/trade")
                            .contentType("application/json").content(body))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        }
        verify(service, never()).setEnabled(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void validJsonBooleansAreForwardedUnchanged() throws Exception {
        when(user.isAdmin()).thenReturn(true);
        when(service.setEnabled("trade", true)).thenReturn(new FubonScheduleSwitchDto("trade", true, Instant.EPOCH));
        when(service.setEnabled("trade", false)).thenReturn(new FubonScheduleSwitchDto("trade", false, Instant.EPOCH));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        for (boolean value : new boolean[] {true, false}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/settings/fubon-schedule-switches/trade")
                            .contentType("application/json").content("{\"enabled\":" + value + "}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            verify(service).setEnabled("trade", value);
        }
    }

    @Test
    void adminUpdatesAndUnknownKeyPropagatesNotFound() {
        when(user.isAdmin()).thenReturn(true);
        var updated = new FubonScheduleSwitchDto("trade", false, Instant.EPOCH);
        when(service.setEnabled("trade", false)).thenReturn(updated);
        when(service.setEnabled("nope", true)).thenThrow(new NoSuchElementException("x"));
        assertThat(controller.update("trade", java.util.Map.of("enabled", false))).isEqualTo(updated);
        assertThatThrownBy(() -> controller.update("nope", java.util.Map.of("enabled", true)))
                .isInstanceOf(NoSuchElementException.class);
    }
}
