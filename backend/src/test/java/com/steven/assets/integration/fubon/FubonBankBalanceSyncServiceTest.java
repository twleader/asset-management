package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.service.UserAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static com.steven.assets.integration.fubon.FubonAccountingFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FubonBankBalanceSyncServiceTest {
    private final FubonConfigState config = mock(FubonConfigState.class);
    private final FubonBrokerClient client = mock(FubonBrokerClient.class);
    private final UserAdminService users = mock(UserAdminService.class);
    private final FubonBankBalanceWriter writer = mock(FubonBankBalanceWriter.class);
    private FubonBankBalanceOutcomeCounters counters;
    private FubonBankBalanceSyncService service;

    @BeforeEach void init() { counters = new FubonBankBalanceOutcomeCounters(); service = service(true); }
    private FubonBankBalanceSyncService service(boolean enabled) {
        return new FubonBankBalanceSyncService(config, client, users, writer, counters, enabled, CLOCK);
    }
    private void ready() {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(FubonConfigState.State.READY, "test-only", "READY"));
        when(users.configuredAdmin()).thenReturn(Optional.of(AppUser.builder().id(9L)
                .role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build()));
    }

    @Test void disabledFeaturePerformsNoOtherReads() {
        assertThat(service(false).syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.BANK_BALANCE_SYNC_DISABLED);
        verifyNoInteractions(config, client, users, writer);
    }
    @ParameterizedTest @EnumSource(value = FubonConfigState.State.class, names = {"DISABLED", "MISCONFIGURED"})
    void configFailurePerformsNoOwnerOrHttpReads(FubonConfigState.State state) {
        when(config.snapshot()).thenReturn(new FubonConfigState.Snapshot(state, null, state.name()));
        assertThat(service.syncManual(false).outcome().name()).isEqualTo(state.name());
        verifyNoInteractions(client, users, writer);
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "inactive", "nonadmin", "noid"})
    void invalidOwnerStopsBeforeHttp(String condition) {
        ready();
        var owner = AppUser.builder().id("noid".equals(condition) ? null : 9L)
                .role("nonadmin".equals(condition) ? AppUser.ROLE_USER : AppUser.ROLE_ADMIN)
                .status("inactive".equals(condition) ? AppUser.STATUS_DISABLED : AppUser.STATUS_ACTIVE).build();
        when(users.configuredAdmin()).thenReturn("missing".equals(condition) ? Optional.empty() : Optional.of(owner));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.NO_OWNER);
        verifyNoInteractions(client, writer);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void adapterFailureNeverEntersWriter(boolean dryRun) {
        ready(); when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.failure("sensitive-message"));
        var result = service.syncManual(dryRun);
        assertThat(result.outcome()).isEqualTo(FubonBankBalanceOutcome.BANK_BALANCE_FAILED);
        assertThat(result.reason()).doesNotContain("sensitive"); verifyNoInteractions(writer);
    }
    @Test void thrownAdapterFailureNeverEntersWriter() {
        ready(); when(client.readBankBalance()).thenThrow(new IllegalStateException("sensitive-message"));
        assertThat(service.syncManual(false).outcome()).isEqualTo(FubonBankBalanceOutcome.BANK_BALANCE_FAILED);
        verifyNoInteractions(writer);
    }
    @Test void dryRunValidatesZeroButDoesNotLockOrWrite() {
        ready(); when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("0")));
        var result = service.syncManual(true);
        assertThat(result.outcome()).isEqualTo(FubonBankBalanceOutcome.DRY_RUN);
        assertThat(result.updatedAmount()).isNull(); verifyNoInteractions(writer);
    }
    @ParameterizedTest @ValueSource(strings = {"1000000000000000000", "9999999999999999999.9"})
    void databasePrecisionOverflowIsRejectedBeforeWriterEvenInDryRun(String amount) {
        ready(); when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank(amount)));
        assertThat(service.syncManual(true).outcome()).isEqualTo(FubonBankBalanceOutcome.BANK_BALANCE_FAILED);
        verifyNoInteractions(writer);
    }
    @ParameterizedTest @ValueSource(strings = {"currency", "fingerprint", "future", "date", "stale", "negative"})
    void malformedOrExpiredObservationNeverEntersWriter(String invalid) {
        ready(); var base = bank("100");
        var body = new FubonDtos.BankBalance("date".equals(invalid) ? DATE.minusDays(1) : DATE,
                "future".equals(invalid) ? NOW.plusSeconds(1) : "stale".equals(invalid) ? NOW.minusSeconds(61) : base.observedAt(),
                "fingerprint".equals(invalid) ? "bad" : FINGERPRINT,
                "currency".equals(invalid) ? "USD" : "TWD",
                "negative".equals(invalid) ? CanonicalFubonDecimal.parseSigned("-1") : base.balance(), base.availableBalance());
        when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.success(body));
        assertThat(service.syncManual(false).outcome()).isEqualTo("stale".equals(invalid)
                ? FubonBankBalanceOutcome.STALE_QUERY : FubonBankBalanceOutcome.BANK_BALANCE_FAILED);
        verifyNoInteractions(writer);
    }
    @Test void successIsRecordedOnlyAfterWriterHasReturned() {
        ready(); var body = bank("1234"); when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.success(body));
        when(writer.write(9L, body)).thenAnswer(call -> {
            assertThat(counters.snapshot().get(FubonBankBalanceOutcome.SUCCESS)).isZero();
            return new BigDecimal("1234.00");
        });
        assertThat(service.syncManual(false).updatedAmount()).isEqualByComparingTo("1234.00");
        assertThat(counters.snapshot().get(FubonBankBalanceOutcome.SUCCESS)).isEqualTo(1L);
    }
    @Test void commitFailureCannotIncrementSuccessOrExposeAmount() {
        ready(); when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("1234")));
        when(writer.write(eq(9L), any())).thenThrow(new DataIntegrityViolationException("test-only-failure"));
        var result = service.syncManual(false);
        assertThat(result.outcome()).isEqualTo(FubonBankBalanceOutcome.ROLLED_BACK);
        assertThat(result.updatedAmount()).isNull();
        assertThat(counters.snapshot().get(FubonBankBalanceOutcome.SUCCESS)).isZero();
    }
    @ParameterizedTest @EnumSource(value = FubonBankBalanceOutcome.class,
            names = {"NO_SNAPSHOT", "BROKER_MISSING", "BANK_MISSING", "NO_OWNER", "AMBIGUOUS_TARGET"})
    void writerRejectionsRemainTyped(FubonBankBalanceOutcome outcome) {
        ready(); when(client.readBankBalance()).thenReturn(FubonDtos.CallResult.success(bank("1")));
        when(writer.write(eq(9L), any())).thenThrow(new FubonBankBalanceWriter.WriteRejected(outcome));
        assertThat(service.syncManual(false).outcome()).isEqualTo(outcome);
        assertThat(counters.snapshot().get(FubonBankBalanceOutcome.SUCCESS)).isZero();
    }
}
