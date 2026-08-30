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
 * Independent token boundary for the bank balance sync endpoint (Requirement 128 / Task 393).
 *
 * <p>Deliberately its own test class, not folded into {@link FubonInternalBoundaryTest} or
 * {@link FubonTradeInternalTokenFilterTest}, because it exercises a different filter class with
 * its own {@link FubonBankBalanceOutcome} response shape. {@link FubonInternalBoundaryTest} and
 * {@link FubonTradeInternalTokenFilterTest} each carry the regression that the *existing* filters
 * still do not protect this new path; this class carries the mirror regression that the new
 * filter does not protect either existing path.
 */
class FubonBankBalanceInternalTokenFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledEndpointReturnsTyped503AndPreservesRequestedDryRunFalse() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.DISABLED, null, "DISABLED"));
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);
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
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);

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
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/portfolio/read");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    /**
     * The new bank-balance-sync filter must not protect either *existing* path either -- each
     * filter is scoped to exactly one exact path, they never overlap.
     */
    @Test
    void filterDoesNotProtectTheExistingInventoryOrTradeSyncPaths() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);

        for (String path : new String[] {
                "/internal/brokers/fubon/inventory-sync",
                "/internal/brokers/fubon/trade-sync",
        }) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = mock(FilterChain.class);

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
        verify(config, never()).snapshot();
    }

    /**
     * Regression guard for Requirement 129 / Task 394: the bank-balance-sync filter still does
     * not protect the new settlement-sync endpoint either.
     */
    @Test
    void filterDoesNotProtectTheNewSettlementSyncPath() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/brokers/fubon/settlement-sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    /**
     * Regression guard for Requirement 130 / Task 395: the bank-balance-sync filter still does
     * not protect the new realized-gain-sync endpoint either.
     */
    @Test
    void filterDoesNotProtectTheNewRealizedGainSyncPath() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/brokers/fubon/realized-gain-sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    @Test
    void matrixVariantCannotReachMvcWithoutTheExactPathBoundary() throws Exception {
        FubonConfigState config = mock(FubonConfigState.class);
        FubonBankBalanceInternalTokenFilter filter = new FubonBankBalanceInternalTokenFilter(
                config, new FubonBankBalanceOutcomeCounters(), mapper);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/internal/brokers;v=1/fubon/bank-balance-sync");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(404);
        verify(chain, never()).doFilter(request, response);
        verify(config, never()).snapshot();
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", FubonBankBalanceInternalTokenFilter.PATH);
    }
}
