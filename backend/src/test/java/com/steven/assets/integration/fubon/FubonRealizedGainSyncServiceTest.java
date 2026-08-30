package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FubonRealizedGainSyncServiceTest {
    private final FubonConfigState config = mock(FubonConfigState.class);
    private final FubonBrokerClient client = mock(FubonBrokerClient.class);
    private final UserAdminService users = mock(UserAdminService.class);
    private final BrokerRepository brokers = mock(BrokerRepository.class);
    private final FubonRealizedGainOutcomeCounters counters = new FubonRealizedGainOutcomeCounters();
    private final FubonRealizedGainSyncService service = service(true);
    private FubonRealizedGainSyncService service(boolean enabled) {
        return new FubonRealizedGainSyncService(config, client, users, brokers, counters, enabled, CLOCK);
    }
    private void ready() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only", "READY"));
        when(users.configuredAdmin()).thenReturn(Optional.of(AppUser.builder().id(9L)
                .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()));
        when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder().code("fubon").active(true).build()));
    }
    @Test void disabledFeatureReadsNothingElse() {
        assertThat(service(false).syncScheduled()).isEqualTo(FubonRealizedGainOutcome.REALIZED_GAIN_SYNC_DISABLED);
        verifyNoInteractions(config, client, users, brokers);
    }
    @ParameterizedTest @EnumSource(value = FubonConfigState.State.class, names = {"DISABLED", "MISCONFIGURED"})
    void configFailureReadsNoOwnerOrHttp(FubonConfigState.State state) {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));
        assertThat(service.syncManual(false).outcome().name()).isEqualTo(state.name());
        verifyNoInteractions(client, users, brokers);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "inactive", "nonadmin"})
    void invalidOwnerReadsNoBrokerOrHttp(String condition) {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only", "READY"));
        var user = AppUser.builder().id(9L).role("nonadmin".equals(condition) ? AppUser.ROLE_USER : AppUser.ROLE_ADMIN)
                .status("inactive".equals(condition) ? AppUser.STATUS_DISABLED : AppUser.STATUS_ACTIVE).build();
        when(users.configuredAdmin()).thenReturn("missing".equals(condition) ? Optional.empty() : Optional.of(user));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.NO_OWNER);
        verifyNoInteractions(client, brokers);
    }
    @Test void missingBrokerStopsBeforeHttp() {
        ready(); when(brokers.findByCode("fubon")).thenReturn(Optional.empty());
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.BROKER_MISSING);
        verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ordinaryParsedSaleCannotProveIdentityOrFinancialMapping(boolean dryRun) {
        ready(); when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(realized()));
        var result = service.syncManual(dryRun);
        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.IDENTITY_UNVERIFIED);
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isZero(); assertThat(result.alreadyRepresentedCount()).isZero();
        assertThat(result.skippedNameUnresolvedCount()).isZero();
        assertThat(counters.snapshot().get(FubonRealizedGainOutcome.SUCCESS)).isZero();
    }
    @Test void emptyRowsDoNotAuthorizeClearingOrWriting() {
        ready(); when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(read(realizedJson(""), FubonDtos.RealizedGainBatch.class)));
        var result = service.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.IDENTITY_UNVERIFIED);
        assertThat(result.rowCount()).isZero(); assertThat(result.insertedCount()).isZero();
    }
    @Test void bothPositiveProfitAndLossRemainAccountingUnverified() {
        ready(); when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(read(
                realizedJson(REALIZED_ROW.replace("\"realizedProfit\":\"0\"", "\"realizedProfit\":\"1\"")), FubonDtos.RealizedGainBatch.class)));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.ACCOUNTING_SEMANTICS_UNVERIFIED);
    }
    @Test void duplicateValueRowsRemainAmbiguousInsteadOfDeduping() {
        ready(); when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(read(
                realizedJson(REALIZED_ROW + "," + REALIZED_ROW), FubonDtos.RealizedGainBatch.class)));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.AMBIGUOUS_IDENTITY);
    }
    @Test void rawPricesCollidingAfterPersistencePrecisionAreAmbiguous() {
        String rows = REALIZED_ROW.replace("123.5", "123.50001") + "," + REALIZED_ROW.replace("123.5", "123.50002");
        ready(); when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(read(realizedJson(rows), FubonDtos.RealizedGainBatch.class)));
        assertThat(service.syncManual(true).outcome()).isEqualTo(FubonRealizedGainOutcome.AMBIGUOUS_IDENTITY);
    }
    @Test void adapterFailureDoesNotEchoProviderData() {
        ready(); when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.failure("raw-account-secret"));
        var result = service.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.REALIZED_GAIN_FAILED);
        assertThat(result.reason()).doesNotContain("raw-account-secret");
    }
}
