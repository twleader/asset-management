package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.net.URI;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Request defaults, exact routes, token filters and the redacted response are checked together. */
class FubonAccountingControllerTest {
    private final FubonBankBalanceSyncService bank = mock(FubonBankBalanceSyncService.class);
    private final FubonSettlementSyncService settlement = mock(FubonSettlementSyncService.class);
    private final FubonRealizedGainSyncService realized = mock(FubonRealizedGainSyncService.class);
    private final FubonConfigState config = mock(FubonConfigState.class);
    private MockMvc mvc;
    private static final String PREFIX = "/internal/brokers/fubon/";

    @BeforeEach void setup() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only-token", "READY"));
        var mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new FubonBankBalanceSyncController(bank),
                        new FubonSettlementSyncController(settlement), new FubonRealizedGainSyncController(realized))
                .addFilters(new FubonBankBalanceInternalTokenFilter(config, new FubonBankBalanceOutcomeCounters(), mapper),
                        new FubonSettlementInternalTokenFilter(config, new FubonSettlementOutcomeCounters(), mapper),
                        new FubonRealizedGainInternalTokenFilter(config, new FubonRealizedGainOutcomeCounters(), mapper))
                .build();
        for (boolean dryRun : new boolean[]{true, false}) {
            when(bank.syncManual(dryRun)).thenReturn(new FubonBankBalanceSyncService.SyncResult(
                    dryRun ? FubonBankBalanceOutcome.DRY_RUN : FubonBankBalanceOutcome.SUCCESS, dryRun, "test-only",
                    dryRun ? null : new BigDecimal("0.00")));
            when(settlement.syncManual(dryRun)).thenReturn(new FubonSettlementSyncService.SyncResult(
                    FubonSettlementOutcome.SETTLEMENT_SCOPE_UNVERIFIED, dryRun, "MISSING_SETTLEMENT_RANGE_CONTRACT", null, null));
            when(realized.syncManual(dryRun)).thenReturn(new FubonRealizedGainSyncService.RealizedGainSyncResult(
                    FubonRealizedGainOutcome.IDENTITY_UNVERIFIED, dryRun, 1, 0, 0, 0, "IDENTITY_UNVERIFIED"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"bank-balance-sync", "settlement-sync", "realized-gain-sync"})
    void exactPostDefaultsToDryRunAndNeverLeaksAccountingObservations(String endpoint) throws Exception {
        mvc.perform(post(PREFIX + endpoint).header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.accountFingerprint").doesNotExist()).andExpect(jsonPath("$.account").doesNotExist())
                .andExpect(jsonPath("$.branchNo").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(jsonPath("$.rows").doesNotExist());
        verifyOnlyExpected(endpoint, true);
    }
    @ParameterizedTest @ValueSource(strings = {"bank-balance-sync", "settlement-sync", "realized-gain-sync"})
    void explicitFalseIsPassedThroughButInvalidBooleanOrRouteCannotInvokeService(String endpoint) throws Exception {
        mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", "false").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(false));
        verifyOnlyExpected(endpoint, false); clearInvocations(bank, settlement, realized);
        mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", "invalid").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(PREFIX + endpoint).header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(post(PREFIX + endpoint + "/extra").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isNotFound());
        mvc.perform(post(PREFIX + endpoint + ";v=1").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(bank, settlement, realized);
    }
    @ParameterizedTest @ValueSource(strings = {"bank-balance-sync", "settlement-sync", "realized-gain-sync"})
    void missingWrongOrDuplicatedTokensCannotInvokeService(String endpoint) throws Exception {
        mvc.perform(post(PREFIX + endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(post(PREFIX + endpoint).header(FubonHttpClient.TOKEN_HEADER, "wrong")).andExpect(status().isForbidden());
        mvc.perform(post(PREFIX + endpoint).header(FubonHttpClient.TOKEN_HEADER, "test-only-token", "test-only-token"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(bank, settlement, realized);
    }
    @ParameterizedTest @MethodSource("endpointTokens")
    void encodedPathAliasesAreRejectedWithoutServiceCalls(String endpoint, String token) throws Exception {
        for (String path : new String[]{"/internal/brokers/%66ubon/" + endpoint,
                "/%69nternal/brokers/fubon/" + endpoint,
                PREFIX + endpoint.replace("-sync", "-%73ync"),
                "/internal/brokers/%66ubon;v=1/" + endpoint}) {
            // The URI overload keeps percent escapes intact rather than encoding '%' again.
            var request = post(URI.create(path + "?dryRun=false"));
            if (token != null) request.header(FubonHttpClient.TOKEN_HEADER, token);
            var result = mvc.perform(request).andReturn();
            assertAll(() -> assertThat(result.getResponse().getStatus()).isEqualTo(404),
                    () -> verifyNoInteractions(bank, settlement, realized));
        }
        // A container-resolved servletPath cannot make a different raw URI an accepted route.
        var servletPathAlias = post(URI.create("/context" + PREFIX + endpoint + "?dryRun=false"))
                .contextPath("/context").servletPath(PREFIX + endpoint);
        if (token != null) servletPathAlias.header(FubonHttpClient.TOKEN_HEADER, token);
        mvc.perform(servletPathAlias).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        verifyNoInteractions(bank, settlement, realized);
    }
    private static Stream<Arguments> endpointTokens() {
        return Stream.of("bank-balance-sync", "settlement-sync", "realized-gain-sync")
                .flatMap(endpoint -> Stream.of(null, "wrong", "test-only-token")
                        .map(token -> Arguments.of(endpoint, token)));
    }
    @ParameterizedTest @ValueSource(strings = {"bank-balance-sync", "settlement-sync", "realized-gain-sync"})
    void onlyLiteralTrueOrFalseIsAcceptedBeforeBooleanBinding(String endpoint) throws Exception {
        mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", "true").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dryRun").value(true));
        verifyOnlyExpected(endpoint, true);
        clearInvocations(bank, settlement, realized);
        assertInvalidDryRunValues(endpoint);
        verifyNoInteractions(bank, settlement, realized);
    }
    @ParameterizedTest @ValueSource(strings = {"bank-balance-sync", "settlement-sync", "realized-gain-sync"})
    void duplicateAndUnknownParametersCannotInvokeService(String endpoint) throws Exception {
        assertAmbiguousOrUnknownParameters(endpoint);
        verifyNoInteractions(bank, settlement, realized);
    }
    @ParameterizedTest @ValueSource(strings = {"bank-balance-sync", "settlement-sync", "realized-gain-sync"})
    void unavailableResponsesUseTheSameValidatedDryRunContract(String endpoint) throws Exception {
        for (var state : new FubonConfigState.State[]{FubonConfigState.State.DISABLED, FubonConfigState.State.MISCONFIGURED}) {
            when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));
            mvc.perform(post(PREFIX + endpoint))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.dryRun").value(true));
            for (boolean dryRun : new boolean[]{true, false}) {
                mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", Boolean.toString(dryRun)))
                        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.dryRun").value(dryRun));
            }
            assertInvalidDryRunValues(endpoint);
            assertAmbiguousOrUnknownParameters(endpoint);
        }
        verifyNoInteractions(bank, settlement, realized);
    }
    @Test void successfulBankMoneyRemainsCanonicalStringWhileDtoUsesBigDecimal() throws Exception {
        mvc.perform(post(PREFIX + "bank-balance-sync").queryParam("dryRun", "false").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedAmount").isString())
                .andExpect(jsonPath("$.updatedAmount").value("0.00"));
    }
    @Test void financialPreflightNeverPretendsMoneyWasCommitted() throws Exception {
        mvc.perform(post(PREFIX + "settlement-sync").queryParam("dryRun", "false").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("SETTLEMENT_SCOPE_UNVERIFIED"))
                .andExpect(jsonPath("$.payableAmount").isEmpty()).andExpect(jsonPath("$.receivableAmount").isEmpty());
        mvc.perform(post(PREFIX + "realized-gain-sync").queryParam("dryRun", "false").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("IDENTITY_UNVERIFIED"))
                .andExpect(jsonPath("$.insertedCount").value(0)).andExpect(jsonPath("$.alreadyRepresentedCount").value(0))
                .andExpect(jsonPath("$.skippedExistingCount").doesNotExist());
    }
    private void assertInvalidDryRunValues(String endpoint) throws Exception {
        for (String value : new String[]{"0", "off", "no", "1", "yes", "on", "", "TRUE", "False", " false", "false "}) {
            mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", value).header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
        mvc.perform(post(PREFIX + endpoint + "?dryRun").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
    private void assertAmbiguousOrUnknownParameters(String endpoint) throws Exception {
        for (String[] values : new String[][]{{"true", "false"}, {"false", "false"}, {"true", "true"}}) {
            mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", values).header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }
        mvc.perform(post(PREFIX + endpoint).queryParam("unexpected", "value").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post(PREFIX + endpoint).queryParam("dryRun", "false").queryParam("account", "test-only")
                        .header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post(PREFIX + endpoint).queryParam("DryRun", "false").header(FubonHttpClient.TOKEN_HEADER, "test-only-token"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }
    private void verifyOnlyExpected(String endpoint, boolean dryRun) {
        switch (endpoint) {
            case "bank-balance-sync" -> { verify(bank).syncManual(dryRun); verifyNoInteractions(settlement, realized); }
            case "settlement-sync" -> { verify(settlement).syncManual(dryRun); verifyNoInteractions(bank, realized); }
            case "realized-gain-sync" -> { verify(realized).syncManual(dryRun); verifyNoInteractions(bank, settlement); }
            default -> throw new AssertionError(endpoint);
        }
    }
}
