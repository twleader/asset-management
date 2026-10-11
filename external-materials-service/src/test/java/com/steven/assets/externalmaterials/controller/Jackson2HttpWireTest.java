package com.steven.assets.externalmaterials.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the Boot-configured HTTP converter, including both response and request wire data. */
@WebMvcTest(Jackson2HttpWireTest.WireController.class)
@Import(Jackson2HttpWireTest.WireController.class)
class Jackson2HttpWireTest {

    private static final String JSON = "{\"price\":12345678901234567890.1234567890,"
            + "\"asOfDate\":\"2026-10-09\",\"unavailable\":null}";

    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerAdapter adapter;

    @Test
    void bootSelectsJackson2RatherThanJackson3HttpConverters() {
        assertThat(adapter.getMessageConverters())
                .anyMatch(MappingJackson2HttpMessageConverter.class::isInstance)
                .noneMatch(converter -> converter.getClass().getName()
                        .equals("org.springframework.http.converter.json.JacksonJsonHttpMessageConverter"));
    }

    @Test
    void responseKeepsDecimalPrecisionNullAndIsoDate() throws Exception {
        mvc.perform(get("/test/jackson2-wire"))
                .andExpect(status().isOk())
                .andExpect(content().string(JSON));
    }

    @Test
    void requestRoundTripKeepsDecimalPrecisionNullAndIsoDate() throws Exception {
        mvc.perform(post("/test/jackson2-wire").contentType(MediaType.APPLICATION_JSON).content(JSON))
                .andExpect(status().isOk())
                .andExpect(content().string(JSON));
    }

    record WireValue(BigDecimal price, LocalDate asOfDate, String unavailable) {}

    @RestController
    static class WireController {
        @GetMapping("/test/jackson2-wire")
        WireValue get() {
            return new WireValue(new BigDecimal("12345678901234567890.1234567890"),
                    LocalDate.of(2026, 10, 9), null);
        }

        @PostMapping("/test/jackson2-wire")
        WireValue echo(@RequestBody WireValue value) {
            return value;
        }
    }
}
