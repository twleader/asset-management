package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.steven.assets.model.AppUser;
import com.steven.assets.service.UserAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

/** Source preflight has no ambient transaction; only the separate writer owns snapshot changes. */
@Service
@Slf4j
public class FubonBankBalanceSyncService {
    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final UserAdminService userAdminService;
    private final FubonBankBalanceWriter writer;
    private final FubonBankBalanceOutcomeCounters counters;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public FubonBankBalanceSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            UserAdminService userAdminService, FubonBankBalanceWriter writer,
            FubonBankBalanceOutcomeCounters counters,
            @Value("${fubon.bank-balance-sync-enabled:false}") boolean enabled) {
        this(configState, brokerClient, userAdminService, writer, counters, enabled, Clock.systemUTC());
    }

    FubonBankBalanceSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            UserAdminService userAdminService, FubonBankBalanceWriter writer,
            FubonBankBalanceOutcomeCounters counters, boolean enabled, Clock clock) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.userAdminService = userAdminService;
        this.writer = writer;
        this.counters = counters;
        this.enabled = enabled;
        this.clock = clock;
    }

    public FubonBankBalanceOutcome bankBalanceSyncFeatureGate() {
        return enabled ? null : recordOutcome(FubonBankBalanceOutcome.BANK_BALANCE_SYNC_DISABLED);
    }

    public FubonBankBalanceOutcome localConfigGate() {
        FubonBankBalanceOutcome feature = bankBalanceSyncFeatureGate();
        if (feature != null) return feature;
        return switch (configState.snapshot().state()) {
            case DISABLED -> recordOutcome(FubonBankBalanceOutcome.DISABLED);
            case MISCONFIGURED -> recordOutcome(FubonBankBalanceOutcome.MISCONFIGURED);
            case READY -> null;
        };
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FubonBankBalanceOutcome syncScheduled() { return syncShared(false).outcome(); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SyncResult syncManual(boolean dryRun) { return syncShared(dryRun); }

    private SyncResult syncShared(boolean dryRun) {
        FubonBankBalanceOutcome gate = localConfigGate();
        if (gate != null) return result(gate, dryRun, null);
        AppUser owner = userAdminService.configuredAdmin().orElse(null);
        if (owner == null || owner.getId() == null || !owner.isActive() || !owner.isAdmin()) {
            return recorded(FubonBankBalanceOutcome.NO_OWNER, dryRun, null);
        }
        FubonDtos.BankBalance observation;
        try {
            FubonDtos.CallResult<FubonDtos.BankBalance> call = brokerClient.readBankBalance();
            if (call == null || !call.success() || call.body() == null) {
                return recorded(FubonBankBalanceOutcome.BANK_BALANCE_FAILED, dryRun, null);
            }
            observation = call.body();
            FubonAccountingContract.validateBank(observation, clock);
            FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded("STALE_QUERY".equals(rejected.getMessage()) ? FubonBankBalanceOutcome.STALE_QUERY
                    : FubonBankBalanceOutcome.BANK_BALANCE_FAILED, dryRun, null);
        } catch (RuntimeException failure) {
            return recorded(FubonBankBalanceOutcome.BANK_BALANCE_FAILED, dryRun, null);
        }
        if (dryRun) return recorded(FubonBankBalanceOutcome.DRY_RUN, true, null);
        try {
            BigDecimal committedAmount = writer.write(owner.getId(), observation);
            return recorded(FubonBankBalanceOutcome.SUCCESS, false, committedAmount);
        } catch (FubonBankBalanceWriter.WriteRejected rejected) {
            return recorded(rejected.outcome(), false, null);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded("STALE_QUERY".equals(rejected.getMessage()) ? FubonBankBalanceOutcome.STALE_QUERY
                    : FubonBankBalanceOutcome.ROLLED_BACK, false, null);
        } catch (RuntimeException failure) {
            return recorded(FubonBankBalanceOutcome.ROLLED_BACK, false, null);
        }
    }

    private FubonBankBalanceOutcome recordOutcome(FubonBankBalanceOutcome outcome) {
        counters.increment(outcome);
        log.info("Fubon bank balance sync outcome={}", outcome);
        return outcome;
    }

    private SyncResult recorded(FubonBankBalanceOutcome outcome, boolean dryRun, BigDecimal amount) {
        return result(recordOutcome(outcome), dryRun, amount);
    }

    private SyncResult result(FubonBankBalanceOutcome outcome, boolean dryRun, BigDecimal amount) {
        String reason = switch (outcome) {
            case NO_OWNER -> "NO_ACTIVE_CONFIGURED_ADMIN";
            case BANK_BALANCE_FAILED -> "BANK_BALANCE_ADAPTER_FAILURE";
            case DRY_RUN -> "DRY_RUN_COMPLETE";
            default -> outcome.name();
        };
        return new SyncResult(outcome, dryRun, reason, amount);
    }

    public record SyncResult(FubonBankBalanceOutcome outcome, boolean dryRun, String reason,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal updatedAmount) {}
}
