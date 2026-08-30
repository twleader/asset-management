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

class FubonSettlementSyncServiceTest {
    private final FubonConfigState config = mock(FubonConfigState.class);
    private final FubonBrokerClient client = mock(FubonBrokerClient.class);
    private final UserAdminService users = mock(UserAdminService.class);
    private final BrokerRepository brokers = mock(BrokerRepository.class);
    private final FubonSettlementOutcomeCounters counters = new FubonSettlementOutcomeCounters();
    private final FubonSettlementSyncService service = service(true);
    private FubonSettlementSyncService service(boolean enabled) {
        return new FubonSettlementSyncService(config, client, users, brokers, counters, enabled, CLOCK);
    }
    private void ready() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only", "READY"));
        when(users.configuredAdmin()).thenReturn(Optional.of(AppUser.builder().id(9L)
                .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()));
        when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder().code("fubon").active(true).build()));
    }
    @Test void disabledFeatureReadsNothingElse() {
        assertThat(service(false).syncScheduled()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_SYNC_DISABLED);
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
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.NO_OWNER);
        verifyNoInteractions(client, brokers);
    }
    @Test void inactiveBrokerStopsBeforeHttp() {
        ready(); when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder().active(false).build()));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.BROKER_MISSING);
        verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void validFutureDayRemainsUnverifiedAndAmountsAreNeverReturned(boolean dryRun) {
        ready(); when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));
        var result = service.syncManual(dryRun);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_SCOPE_UNVERIFIED);
        assertThat(result.reason()).isEqualTo("MISSING_SETTLEMENT_RANGE_CONTRACT");
        assertThat(result.payableAmount()).isNull(); assertThat(result.receivableAmount()).isNull();
        assertThat(counters.snapshot().get(FubonSettlementOutcome.SUCCESS)).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"", NO_DATA_ROW})
    void emptyObservationDoesNotClearAnyAmount(String rows) {
        ready(); when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(read(settlementJson(rows), FubonDtos.SettlementBatch.class)));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_SCOPE_UNVERIFIED);
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "same-day"})
    void ambiguousDaysAreNotSummed(String scenario) {
        String rows = "duplicate".equals(scenario) ? SETTLEMENT_ROW + "," + SETTLEMENT_ROW
                : SETTLEMENT_ROW.replace("2026-09-01", "2026-08-28");
        ready(); when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(read(settlementJson(rows), FubonDtos.SettlementBatch.class)));
        assertThat(service.syncManual(true).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_SETTLEMENT);
    }
    @Test void malformedAmountDoesNotReachUnverifiedSuccessfullyParsedState() {
        ready(); when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(read(
                settlementJson(SETTLEMENT_ROW.replace("\"buySettlement\":\"-1002\"", "\"buySettlement\":\"1002\"")), FubonDtos.SettlementBatch.class)));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_FAILED);
    }
    @Test void adapterFailuresAreSanitized() {
        ready(); when(client.readSettlement()).thenThrow(new IllegalArgumentException("raw-account-secret"));
        var result = service.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_FAILED);
        assertThat(result.reason()).doesNotContain("raw-account-secret");
    }
}
