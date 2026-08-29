package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Independent token boundary for the trade sync endpoint (Requirement 120 / Task 385).
 *
 * <p>Deliberately its own test class, not folded into {@link FubonInternalBoundaryTest}, because
 * it exercises a different filter class with its own {@link FubonTradeOutcome} response shape.
 * {@link FubonInternalBoundaryTest} carries the regression that the *existing* (inventory)
 * filter still does not protect this new path.
 */
class FubonTradeInternalTokenFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledEndpointReturnsTyped503AndPreservesRequestedDryRunFalse() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.DISABLED, null, "DISABLED"));
        FubonTradeInternalTokenFilter filter = new FubonTradeInternalTokenFilter(
                config, new FubonTradeOutcomeCounters(), mapper);
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
        FubonTradeInternalTokenFilter filter = new FubonTradeInternalTokenFilter(
                config, new FubonTradeOutcomeCounters(), mapper);

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
        FubonTradeInternalTokenFilter filter = new FubonTradeInternalTokenFilter(
                config, new FubonTradeOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/portfolio/read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    /**
     * The new trade-sync filter must not protect the *existing* inventory-sync path either —
     * each filter is scoped to exactly one exact path, they never overlap.
     */
    @Test
    void filterDoesNotProtectTheExistingInventorySyncPath() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonTradeInternalTokenFilter filter = new FubonTradeInternalTokenFilter(
                config, new FubonTradeOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/brokers/fubon/inventory-sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    @Test
    void matrixVariantCannotReachMvcWithoutTheExactPathBoundary() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonTradeInternalTokenFilter filter = new FubonTradeInternalTokenFilter(
                config, new FubonTradeOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/brokers;v=1/fubon/trade-sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", FubonTradeInternalTokenFilter.PATH);
    }
}
