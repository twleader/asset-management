package com.steven.assets.integration.fubon;

import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.fubon.FubonLocalNamePort;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FubonRealizedGainSyncServiceTest {
    private final FubonConfigState config = mock(FubonConfigState.class);
    private final FubonBrokerClient client = mock(FubonBrokerClient.class);
    private final FubonSyncOwnerPort owners = mock(FubonSyncOwnerPort.class);
    private final BrokerRepository brokers = mock(BrokerRepository.class);
    private final FubonLocalNamePort localNames = mock(FubonLocalNamePort.class);
    private final FubonRealizedGainWriter writer = mock(FubonRealizedGainWriter.class);
    private final FubonRealizedGainOutcomeCounters counters = new FubonRealizedGainOutcomeCounters();
    private final FubonRealizedGainSyncService service = service(true);

    private FubonRealizedGainSyncService service(boolean enabled) {
        return new FubonRealizedGainSyncService(config, client, owners, brokers, localNames, writer, counters,
                enabled, CLOCK);
    }

    private void ready() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "test-only", "READY"));
        when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(9L, null));
        when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder()
                .code("fubon").active(true).build()));
        when(localNames.resolveTaiwanStockName("2330")).thenReturn("台積電");
    }

    @Test
    void disabledFeatureReadsNothingElse() {
        assertThat(service(false).syncScheduled()).isEqualTo(FubonRealizedGainOutcome.REALIZED_GAIN_SYNC_DISABLED);
        verifyNoInteractions(config, client, owners, brokers, localNames, writer);
    }

    @ParameterizedTest
    @EnumSource(value = FubonConfigState.State.class, names = {"DISABLED", "MISCONFIGURED"})
    void configFailureReadsNoOwnerOrHttp(FubonConfigState.State state) {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));

        assertThat(service.syncManual(false).outcome().name()).isEqualTo(state.name());

        verifyNoInteractions(client, owners, brokers, localNames, writer);
    }

    @Test
    void dedicatedOwnerIsRequiredBeforeBrokerOrHttp() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(
                FubonConfigState.State.READY, "test-only", "READY"));
        when(owners.preflight()).thenReturn(new FubonSyncOwnerPort.Decision(null,
                FubonSyncOwnerPort.Denial.NO_OWNER));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.NO_OWNER);

        verifyNoInteractions(client, brokers, localNames, writer);
    }

    @Test
    void missingBrokerStopsBeforeHttp() {
        ready();
        when(brokers.findByCode("fubon")).thenReturn(Optional.empty());

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.BROKER_MISSING);

        verifyNoInteractions(client, localNames, writer);
    }

    @Test
    void dryRunValidatesAndMapsRowsWithoutWriterLockOrWrite() {
        ready();
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(realized()));

        FubonRealizedGainSyncService.RealizedGainSyncResult result = service.syncManual(true);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.DRY_RUN);
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isZero();
        verifyNoInteractions(writer);
    }

    @Test
    void nonDryRunDelegatesAppendOnlyCommitAfterReadOnlyValidation() {
        ready();
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(realized()));
        when(writer.write(eq(9L), any())).thenReturn(new FubonRealizedGainWriter.CommitResult(1, 0));

        FubonRealizedGainSyncService.RealizedGainSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.insertedCount()).isEqualTo(1);
        verify(writer).write(eq(9L), any());
    }

    @Test
    void explicitFalseOrMissingBindingNeverReachesNameLookupOrWriter() {
        ready();
        FubonDtos.RealizedGainBatch falseBinding = read(realizedJson(REALIZED_ROW)
                .replace("\"accountBindingExplicit\":true,", "\"accountBindingExplicit\":false,"),
                FubonDtos.RealizedGainBatch.class);
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(falseBinding));

        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.ACCOUNT_BINDING_UNVERIFIED);
        verifyNoInteractions(writer);
        verify(localNames, never()).resolveTaiwanStockName(any());

        reset(client);
        FubonDtos.RealizedGainBatch valid = realized();
        FubonDtos.RealizedGainBatch missingBinding = new FubonDtos.RealizedGainBatch(valid.queryDate(),
                valid.observedAt(), valid.accountFingerprint(), null, valid.rows());
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(missingBinding));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonRealizedGainOutcome.ACCOUNT_BINDING_UNVERIFIED);
        verifyNoInteractions(writer);
    }

    @Test
    void duplicateSourceRowsRetainMultiplicityForTheWriter() {
        ready();
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(
                read(realizedJson(REALIZED_ROW + "," + REALIZED_ROW), FubonDtos.RealizedGainBatch.class)));
        when(writer.write(eq(9L), any())).thenReturn(new FubonRealizedGainWriter.CommitResult(2, 0));

        FubonRealizedGainSyncService.RealizedGainSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.SUCCESS);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.insertedCount()).isEqualTo(2);
    }

    @Test
    void emptyRowsDoNotAuthorizeWriter() {
        ready();
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(
                read(realizedJson(""), FubonDtos.RealizedGainBatch.class)));

        FubonRealizedGainSyncService.RealizedGainSyncResult result = service.syncManual(false);

        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.NO_NEW_GAINS);
        assertThat(result.rowCount()).isZero();
        verifyNoInteractions(writer);
    }

    @Test
    void invalidAccountingAndAdapterFailuresAreSanitized() {
        ready();
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.success(read(
                realizedJson(REALIZED_ROW.replace("\"realizedProfit\":\"0\"", "\"realizedProfit\":\"1\"")),
                FubonDtos.RealizedGainBatch.class)));
        assertThat(service.syncManual(false).outcome())
                .isEqualTo(FubonRealizedGainOutcome.ACCOUNTING_SEMANTICS_UNVERIFIED);
        verifyNoInteractions(writer);

        reset(client);
        when(client.readRealizedGains()).thenReturn(FubonDtos.CallResult.failure("raw-account-secret"));
        FubonRealizedGainSyncService.RealizedGainSyncResult result = service.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonRealizedGainOutcome.REALIZED_GAIN_FAILED);
        assertThat(result.reason()).doesNotContain("raw-account-secret");
    }
}
