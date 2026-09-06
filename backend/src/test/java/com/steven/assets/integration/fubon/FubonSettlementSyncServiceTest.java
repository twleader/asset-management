package com.steven.assets.integration.fubon;

import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.Optional;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FubonSettlementSyncServiceTest {
    private final FubonConfigState config = mock(FubonConfigState.class);
    private final FubonBrokerClient client = mock(FubonBrokerClient.class);
    private final FubonSyncOwnerPort owners = mock(FubonSyncOwnerPort.class);
    private final BrokerRepository brokers = mock(BrokerRepository.class);
    private final FubonSettlementWriter writer = mock(FubonSettlementWriter.class);
    private final FubonSettlementOutcomeCounters counters = new FubonSettlementOutcomeCounters();
    private final FubonSettlementSyncService service = service(true);

    private FubonSettlementSyncService service(boolean enabled) {
        return new FubonSettlementSyncService(config, client, owners, brokers, writer, counters, enabled, CLOCK);
    }

    private void ready() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "test-only", "READY"));
        when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(9L, null));
        when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder()
                .code("fubon").active(true).build()));
    }

    @Test
    void disabledFeatureReadsNothingElse() {
        assertThat(service(false).syncScheduled()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_SYNC_DISABLED);
        verifyNoInteractions(config, client, owners, brokers, writer);
    }

    @ParameterizedTest
    @EnumSource(value = FubonConfigState.State.class, names = {"DISABLED", "MISCONFIGURED"})
    void configFailureReadsNoOwnerOrHttp(FubonConfigState.State state) {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));

        assertThat(service.syncManual(false).outcome().name()).isEqualTo(state.name());

        verifyNoInteractions(client, owners, brokers, writer);
    }

    @Test
    void dedicatedOwnerIsRequiredBeforeBrokerOrHttp() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "test-only", "READY"));
        when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(null,
                FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.SYNC_OWNER_NOT_CONFIGURED);

        verifyNoInteractions(client, brokers, writer);
    }

    @Test
    void inactiveBrokerStopsBeforeHttp() {
        ready();
        when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder().active(false).build()));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.BROKER_MISSING);

        verifyNoInteractions(client, writer);
    }

    @Test
    void dryRunValidatesFutureRowsAndReturnsCandidateWithoutWriterLockOrWrite() {
        ready();
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));

        FubonSettlementSyncService.SyncResult result = service.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.DRY_RUN);
        assertThat(result.payableAmount()).isEqualByComparingTo("-1002.00");
        assertThat(result.receivableAmount()).isEqualByComparingTo("0.00");
        assertThat(result.rowCount()).isEqualTo(1);
        verifyNoInteractions(writer);
    }

    @Test
    void nonDryRunDelegatesOnlyAfterReadOnlyValidationAndReportsSuccessAfterCommit() {
        ready();
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));
        when(writer.write(eq(9L), any(), any())).thenReturn(new FubonSettlementWriter.CommitResult(true,
                new BigDecimal("-1002.00"), new BigDecimal("0.00")));

        FubonSettlementSyncService.SyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isEqualByComparingTo("-1002.00");
        assertThat(result.rowCount()).isEqualTo(1);
        verify(writer).write(eq(9L), any(), any());
    }

    @Test
    void explicitFalseOrMissingBindingNeverReachesWriter() {
        ready();
        FubonDtos.SettlementBatch falseBinding = read(settlementJson(SETTLEMENT_ROW)
                .replace("\"accountBindingExplicit\":true,", "\"accountBindingExplicit\":false,"),
                FubonDtos.SettlementBatch.class);
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(falseBinding));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.ACCOUNT_BINDING_UNVERIFIED);
        verifyNoInteractions(writer);

        reset(client);
        FubonDtos.SettlementBatch valid = settlement();
        FubonDtos.SettlementBatch missingBinding = new FubonDtos.SettlementBatch(valid.queryDate(),
                valid.observedAt(), valid.accountFingerprint(), null, valid.coverageStatus(), valid.reason(),
                valid.details());
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(missingBinding));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.ACCOUNT_BINDING_UNVERIFIED);
        verifyNoInteractions(writer);
    }

    @Test
    void pastNonzeroSettlementNeverReachesWriter() {
        ready();
        String past = SETTLEMENT_ROW.replace("\"sourceQueryDate\":\"2026-08-28\"",
                        "\"sourceQueryDate\":\"2026-08-27\"")
                .replace("\"settlementDate\":\"2026-09-01\"", "\"settlementDate\":\"2026-08-27\"");
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(past), FubonDtos.SettlementBatch.class)));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_SETTLEMENT);
        verifyNoInteractions(writer);
    }

    @Test
    void zeroOrNoFutureDirectionsAreStillDelegatedWithoutClearingAnyTarget() {
        ready();
        String zeroFuture = SETTLEMENT_ROW.replace("\"buyValue\":\"1000\"", "\"buyValue\":\"0\"")
                .replace("\"buyFee\":\"2\"", "\"buyFee\":\"0\"")
                .replace("\"buySettlement\":\"-1002\"", "\"buySettlement\":\"0\"")
                .replace("\"totalBsValue\":\"1000\"", "\"totalBsValue\":\"0\"")
                .replace("\"totalFee\":\"2\"", "\"totalFee\":\"0\"")
                .replace("\"totalSettlementAmount\":\"-1002\"", "\"totalSettlementAmount\":\"0\"");
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(
                read(settlementJson(zeroFuture), FubonDtos.SettlementBatch.class)));
        when(writer.write(eq(9L), any(), any())).thenReturn(new FubonSettlementWriter.CommitResult(false,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2)));

        FubonSettlementSyncService.SyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SUCCESS);
        assertThat(result.payableAmount()).isZero();
        assertThat(result.receivableAmount()).isZero();
        verify(writer).write(eq(9L), any(), any());
    }

    @Test
    void writerRejectAndAdapterFailuresAreSanitized() {
        ready();
        when(client.readSettlement()).thenReturn(FubonDtos.CallResult.success(settlement()));
        when(writer.write(eq(9L), any(), any())).thenThrow(
                new FubonSettlementWriter.WriteRejected(FubonSettlementOutcome.AMBIGUOUS_TARGET));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonSettlementOutcome.AMBIGUOUS_TARGET);

        reset(client, writer);
        when(client.readSettlement()).thenThrow(new IllegalArgumentException("raw-account-secret"));
        FubonSettlementSyncService.SyncResult result = service.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonSettlementOutcome.SETTLEMENT_FAILED);
        assertThat(result.reason()).doesNotContain("raw-account-secret");
    }
}
