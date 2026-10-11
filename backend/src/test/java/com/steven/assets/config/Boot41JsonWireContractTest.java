package com.steven.assets.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.security.CurrentUserContext;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 491: exercise configured MVC and the bare WebClient used by existing adapters. */
@WebMvcTest(Boot41JsonWireContractTest.WireController.class)
@Import(Boot41JsonWireContractTest.WireController.class)
class Boot41JsonWireContractTest {
    private static final String AMOUNT = "1234567890.123456789012345678";
    private static final String BODY = "{\"amount\":" + AMOUNT
            + ",\"unknown\":null,\"date\":\"2026-10-11\",\"observedAt\":\"2026-10-11T01:05:00Z\"}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean CurrentUserContext currentUserContext;

    @Test
    void configuredMvcPreservesExactDecimalNullAndIsoDates() throws Exception {
        String body = mvc.perform(get("/test/boot41/json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(BODY);
        assertThat(mapper.getClass().getPackageName()).startsWith("com.fasterxml.jackson");
        assertThat(org.springframework.util.ClassUtils.isPresent("tools.jackson.databind.json.JsonMapper",
                getClass().getClassLoader())).isFalse();
    }

    @Test
    void bareWebClientUsesJackson2ForActualHttpRequestAndResponse() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            WireValue value = WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .build().post().uri("/echo").bodyValue(new WireRequest(new BigDecimal(AMOUNT), null))
                    .retrieve().bodyToMono(WireValue.class)
                    .block(Duration.ofSeconds(5));
            assertThat(request.get()).isEqualTo("{\"amount\":" + AMOUNT + ",\"unknown\":null}");
            assertThat(contentType.get()).startsWith(MediaType.APPLICATION_JSON_VALUE);
            assertThat(value).isEqualTo(sample());
            assertThat(value.amount().toPlainString()).isEqualTo(AMOUNT);
        } finally {
            server.stop(0);
        }
    }

    static WireValue sample() {
        return new WireValue(new BigDecimal(AMOUNT), null, LocalDate.of(2026, 10, 11),
                Instant.parse("2026-10-11T01:05:00Z"));
    }

    record WireValue(BigDecimal amount, BigDecimal unknown, LocalDate date, Instant observedAt) {}
    record WireRequest(BigDecimal amount, BigDecimal unknown) {}

    @RestController
    static class WireController {
        @GetMapping("/test/boot41/json")
        WireValue get() { return sample(); }
    }
}
