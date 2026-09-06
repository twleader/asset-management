package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;

@Service
@Slf4j
public class FubonSettlementSyncService {
    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final FubonSyncOwnerPort ownerPolicy;
    private final BrokerRepository brokerRepository;
    private final FubonSettlementWriter writer;
    private final FubonSettlementOutcomeCounters counters;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public FubonSettlementSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            FubonSyncOwnerPort ownerPolicy, BrokerRepository brokerRepository, FubonSettlementWriter writer,
            FubonSettlementOutcomeCounters counters,
            @Value("${fubon.settlement-sync-enabled:false}") boolean enabled) {
        this(configState, brokerClient, ownerPolicy, brokerRepository, writer, counters, enabled, Clock.systemUTC());
    }

    FubonSettlementSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            FubonSyncOwnerPort ownerPolicy, BrokerRepository brokerRepository, FubonSettlementWriter writer,
            FubonSettlementOutcomeCounters counters, boolean enabled, Clock clock) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.ownerPolicy = ownerPolicy;
        this.brokerRepository = brokerRepository;
        this.writer = writer;
        this.counters = counters;
        this.enabled = enabled;
        this.clock = clock;
    }

    public FubonSettlementOutcome settlementSyncFeatureGate() {
        return enabled ? null : recordOutcome(FubonSettlementOutcome.SETTLEMENT_SYNC_DISABLED);
    }

    public FubonSettlementOutcome localConfigGate() {
        FubonSettlementOutcome feature = settlementSyncFeatureGate();
        if (feature != null) return feature;
        return switch (configState.snapshot().state()) {
            case DISABLED -> recordOutcome(FubonSettlementOutcome.DISABLED);
            case MISCONFIGURED -> recordOutcome(FubonSettlementOutcome.MISCONFIGURED);
            case READY -> null;
        };
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FubonSettlementOutcome syncScheduled() {
        return syncShared(false).outcome();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SyncResult syncManual(boolean dryRun) {
        return syncShared(dryRun);
    }

    private SyncResult syncShared(boolean dryRun) {
        FubonSettlementOutcome gate = localConfigGate();
        if (gate != null) return result(gate, dryRun, null);

        FubonSyncOwnerPort.Decision owner = ownerPolicy.preflight();
        if (!owner.allowed()) return recorded(ownerOutcome(owner.denial()), dryRun, null);
        if (brokerRepository.findByCode("fubon").filter(b -> Boolean.TRUE.equals(b.getActive())).isEmpty()) {
            return recorded(FubonSettlementOutcome.BROKER_MISSING, dryRun, null);
        }

        FubonDtos.SettlementBatch observation;
        FubonAccountingContract.SettlementProjection projection;
        try {
            FubonDtos.CallResult<FubonDtos.SettlementBatch> call = brokerClient.readSettlement();
            if (call == null || !call.success() || call.body() == null) {
                return recorded(outcomeFor(call == null ? null : call.reason()), dryRun, null);
            }
            observation = call.body();
            FubonAccountingContract.validateSettlement(observation, clock);
            FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);
            projection = FubonAccountingContract.settlementProjection(observation);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded(outcomeFor(rejected.getMessage()), dryRun, null);
        } catch (RuntimeException failure) {
            return recorded(FubonSettlementOutcome.SETTLEMENT_FAILED, dryRun, null);
        }

        if (dryRun) return recorded(FubonSettlementOutcome.DRY_RUN, true, projection);
        try {
            FubonSettlementWriter.CommitResult committed = writer.write(owner.ownerId(), observation, projection);
            return recorded(FubonSettlementOutcome.SUCCESS, false,
                    new FubonAccountingContract.SettlementProjection(committed.payableAmount(),
                            committed.receivableAmount(), projection.futureRowCount()));
        } catch (FubonSettlementWriter.WriteRejected rejected) {
            return recorded(rejected.outcome(), false, null);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded("STALE_QUERY".equals(rejected.getMessage()) ? FubonSettlementOutcome.STALE_QUERY
                    : FubonSettlementOutcome.ROLLED_BACK, false, null);
        } catch (RuntimeException failure) {
            return recorded(FubonSettlementOutcome.ROLLED_BACK, false, null);
        }
    }

    private static FubonSettlementOutcome ownerOutcome(FubonSyncOwnerPort.Denial denial) {
        return denial == FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED
                ? FubonSettlementOutcome.SYNC_OWNER_NOT_CONFIGURED : FubonSettlementOutcome.NO_OWNER;
    }

    private static FubonSettlementOutcome outcomeFor(String reason) {
        if ("ACCOUNT_BINDING_UNVERIFIED".equals(reason)) return FubonSettlementOutcome.ACCOUNT_BINDING_UNVERIFIED;
        if ("AMBIGUOUS_SETTLEMENT".equals(reason)) return FubonSettlementOutcome.AMBIGUOUS_SETTLEMENT;
        if ("STALE_QUERY".equals(reason)) return FubonSettlementOutcome.STALE_QUERY;
        return FubonSettlementOutcome.SETTLEMENT_FAILED;
    }

    private FubonSettlementOutcome recordOutcome(FubonSettlementOutcome outcome) {
        counters.increment(outcome);
        log.info("Fubon settlement sync outcome={}", outcome);
        return outcome;
    }

    private SyncResult recorded(FubonSettlementOutcome outcome, boolean dryRun,
            FubonAccountingContract.SettlementProjection projection) {
        return result(recordOutcome(outcome), dryRun, projection);
    }

    private static SyncResult result(FubonSettlementOutcome outcome, boolean dryRun,
            FubonAccountingContract.SettlementProjection projection) {
        String reason = switch (outcome) {
            case NO_OWNER -> "NO_ACTIVE_CONFIGURED_ADMIN";
            case SYNC_OWNER_NOT_CONFIGURED -> "SYNC_OWNER_NOT_CONFIGURED";
            case SETTLEMENT_FAILED -> "SETTLEMENT_ADAPTER_FAILURE";
            case DRY_RUN -> "DRY_RUN_COMPLETE";
            default -> outcome.name();
        };
        return new SyncResult(outcome, dryRun, reason,
                projection == null ? null : projection.payableAmount(),
                projection == null ? null : projection.receivableAmount(),
                projection == null ? 0 : projection.futureRowCount());
    }

    public record SyncResult(FubonSettlementOutcome outcome, boolean dryRun, String reason,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal payableAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal receivableAmount,
            int rowCount) {
        public SyncResult(FubonSettlementOutcome outcome, boolean dryRun, String reason,
                BigDecimal payableAmount, BigDecimal receivableAmount) {
            this(outcome, dryRun, reason, payableAmount, receivableAmount, 0);
        }
    }
}
