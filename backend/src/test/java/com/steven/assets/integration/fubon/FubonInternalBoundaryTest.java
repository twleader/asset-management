package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.StockMasterService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FubonInternalBoundaryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void componentUsesItsExplicitValueInjectedConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(FubonConfigState.class);
            context.refresh();

            assertThat(context.getBean(FubonConfigState.class).snapshot().state())
                    .isEqualTo(FubonConfigState.State.DISABLED);
        }
    }

    @Test
    void productionComponentsWithTestConstructorsDeclareTheirInjectionConstructor() {
        assertThat(FubonHttpClient.class.getConstructors())
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .hasSize(1);
        assertThat(FubonInventorySyncScheduler.class.getConstructors())
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .hasSize(1);
        assertThat(FubonInventorySyncService.class.getConstructors())
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .hasSize(1);
        assertThat(StockMasterService.class.getConstructors())
                .filteredOn(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .hasSize(1);
    }

    @Test
    void configIsLazyAndSafeToRender(@TempDir Path directory) throws Exception {
        FubonConfigState state = new FubonConfigState(true, directory);
        assertThat(state.snapshot().state()).isEqualTo(FubonConfigState.State.MISCONFIGURED);

        Files.writeString(directory.resolve("internal-service-token"), "private-shared-token\n");
        FubonConfigState.Snapshot ready = state.snapshot();
        assertThat(ready.state()).isEqualTo(FubonConfigState.State.READY);
        assertThat(ready.toString()).doesNotContain("private-shared-token", directory.toString());
        assertThat(new FubonConfigState("not-a-boolean", directory).snapshot().state())
                .isEqualTo(FubonConfigState.State.MISCONFIGURED);
    }

    @Test
    void disabledEndpointReturnsTyped503AndPreservesRequestedDryRunFalse() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.DISABLED, null, "DISABLED"));
        FubonInternalTokenFilter filter = new FubonInternalTokenFilter(
                config, new FubonOutcomeCounters(), mapper);
        MockHttpServletRequest request = request();
        request.setParameter("dryRun", "false");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode body = mapper.readTree(response.getContentAsByteArray());
        assertThat(body.path("outcome").asText()).isEqualTo("DISABLED");
        assertThat(body.path("dryRun").asBoolean()).isFalse();
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void missingWrongAndDuplicateTokensFailButExactTokenPasses() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "correct-token", null));
        FubonInternalTokenFilter filter = new FubonInternalTokenFilter(
                config, new FubonOutcomeCounters(), mapper);

        MockHttpServletRequest missing = request();
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();
        filter.doFilter(missing, missingResponse, mock(FilterChain.class));
        assertThat(missingResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest wrong = request();
        wrong.addHeader(FubonHttpClient.TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        filter.doFilter(wrong, wrongResponse, mock(FilterChain.class));
        assertThat(wrongResponse.getStatus()).isEqualTo(403);

        MockHttpServletRequest duplicate = request();
        duplicate.addHeader(FubonHttpClient.TOKEN_HEADER, "correct-token");
        duplicate.addHeader(FubonHttpClient.TOKEN_HEADER, "correct-token");
        MockHttpServletResponse duplicateResponse = new MockHttpServletResponse();
        filter.doFilter(duplicate, duplicateResponse, mock(FilterChain.class));
        assertThat(duplicateResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest valid = request();
        valid.addHeader(FubonHttpClient.TOKEN_HEADER, "correct-token");
        MockHttpServletResponse validResponse = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(valid, validResponse, chain);
        verify(chain).doFilter(valid, validResponse);
    }

    @Test
    void filterIsExactPathAndDoesNotTouchOtherInternalRoutes() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonInternalTokenFilter filter = new FubonInternalTokenFilter(
                config, new FubonOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/portfolio/read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    @Test
    void matrixVariantCannotReachMvcWithoutTheExactPathBoundary() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonInternalTokenFilter filter = new FubonInternalTokenFilter(
                config, new FubonOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/brokers;v=1/fubon/inventory-sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", FubonInternalTokenFilter.PATH);
    }
}
