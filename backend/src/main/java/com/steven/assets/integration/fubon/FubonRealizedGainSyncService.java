package com.steven.assets.integration.fubon;

import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.fubon.FubonLocalNamePort;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@Slf4j
public class FubonRealizedGainSyncService {
    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final FubonSyncOwnerPort ownerPolicy;
    private final BrokerRepository brokerRepository;
    private final FubonLocalNamePort localNames;
    private final FubonRealizedGainWriter writer;
    private final FubonRealizedGainOutcomeCounters counters;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public FubonRealizedGainSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            FubonSyncOwnerPort ownerPolicy, BrokerRepository brokerRepository,
            FubonLocalNamePort localNames, FubonRealizedGainWriter writer,
            FubonRealizedGainOutcomeCounters counters,
            @Value("${fubon.realized-gain-sync-enabled:false}") boolean enabled) {
        this(configState, brokerClient, ownerPolicy, brokerRepository, localNames, writer, counters,
                enabled, Clock.systemUTC());
    }

    FubonRealizedGainSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            FubonSyncOwnerPort ownerPolicy, BrokerRepository brokerRepository,
            FubonLocalNamePort localNames, FubonRealizedGainWriter writer,
            FubonRealizedGainOutcomeCounters counters, boolean enabled, Clock clock) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.ownerPolicy = ownerPolicy;
        this.brokerRepository = brokerRepository;
        this.localNames = localNames;
        this.writer = writer;
        this.counters = counters;
        this.enabled = enabled;
        this.clock = clock;
    }

    public FubonRealizedGainOutcome realizedGainSyncFeatureGate() {
        return enabled ? null : recordOutcome(FubonRealizedGainOutcome.REALIZED_GAIN_SYNC_DISABLED);
    }

    public FubonRealizedGainOutcome localConfigGate() {
        FubonRealizedGainOutcome feature = realizedGainSyncFeatureGate();
        if (feature != null) return feature;
        return switch (configState.snapshot().state()) {
            case DISABLED -> recordOutcome(FubonRealizedGainOutcome.DISABLED);
            case MISCONFIGURED -> recordOutcome(FubonRealizedGainOutcome.MISCONFIGURED);
            case READY -> null;
        };
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FubonRealizedGainOutcome syncScheduled() {
        return syncShared(false).outcome();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RealizedGainSyncResult syncManual(boolean dryRun) {
        return syncShared(dryRun);
    }

    private RealizedGainSyncResult syncShared(boolean dryRun) {
        FubonRealizedGainOutcome gate = localConfigGate();
        if (gate != null) return result(gate, dryRun, 0, 0, 0);

        FubonSyncOwnerPort.Decision owner = ownerPolicy.preflight();
        if (!owner.allowed()) return recorded(ownerOutcome(owner.denial()), dryRun, 0, 0, 0);
        if (brokerRepository.findByCode("fubon").filter(b -> Boolean.TRUE.equals(b.getActive())).isEmpty()) {
            return recorded(FubonRealizedGainOutcome.BROKER_MISSING, dryRun, 0, 0, 0);
        }

        List<FubonRealizedGainWriter.PreparedGain> prepared;
        int rowCount;
        try {
            FubonDtos.CallResult<FubonDtos.RealizedGainBatch> call = brokerClient.readRealizedGains();
            if (call == null || !call.success() || call.body() == null) {
                return recorded(outcomeFor(call == null ? null : call.reason()), dryRun, 0, 0, 0);
            }
            FubonDtos.RealizedGainBatch observation = call.body();
            rowCount = observation.rows() == null ? 0 : observation.rows().size();
            FubonAccountingContract.validateRealized(observation, clock);
            FubonAccountingContract.fresh(observation.queryDate(), observation.observedAt(), clock);
            prepared = observation.rows().stream()
                    .map(row -> FubonRealizedGainWriter.prepare(row,
                            localNames.resolveTaiwanStockName(row.stockNo())))
                    .toList();
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded(outcomeFor(rejected.getMessage()), dryRun, 0, 0, 0);
        } catch (RuntimeException failure) {
            return recorded(FubonRealizedGainOutcome.REALIZED_GAIN_FAILED, dryRun, 0, 0, 0);
        }

        if (prepared.isEmpty()) return recorded(FubonRealizedGainOutcome.NO_NEW_GAINS, dryRun, rowCount, 0, 0);
        if (dryRun) return recorded(FubonRealizedGainOutcome.DRY_RUN, true, rowCount, 0, 0);
        try {
            FubonRealizedGainWriter.CommitResult committed = writer.write(owner.ownerId(), prepared);
            return recorded(FubonRealizedGainOutcome.SUCCESS, false, rowCount, committed.insertedCount(),
                    committed.alreadyRepresentedCount());
        } catch (FubonRealizedGainWriter.WriteRejected rejected) {
            return recorded(rejected.outcome(), false, rowCount, 0, 0);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded("STALE_QUERY".equals(rejected.getMessage()) ? FubonRealizedGainOutcome.STALE_QUERY
                    : FubonRealizedGainOutcome.ROLLED_BACK, false, rowCount, 0, 0);
        } catch (RuntimeException failure) {
            return recorded(FubonRealizedGainOutcome.ROLLED_BACK, false, rowCount, 0, 0);
        }
    }

    private static FubonRealizedGainOutcome ownerOutcome(FubonSyncOwnerPort.Denial denial) {
        return denial == FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED
                ? FubonRealizedGainOutcome.SYNC_OWNER_NOT_CONFIGURED : FubonRealizedGainOutcome.NO_OWNER;
    }

    private static FubonRealizedGainOutcome outcomeFor(String reason) {
        if ("ACCOUNT_BINDING_UNVERIFIED".equals(reason)) return FubonRealizedGainOutcome.ACCOUNT_BINDING_UNVERIFIED;
        if ("ACCOUNTING_SEMANTICS_UNVERIFIED".equals(reason)) {
            return FubonRealizedGainOutcome.ACCOUNTING_SEMANTICS_UNVERIFIED;
        }
        if ("STALE_QUERY".equals(reason)) return FubonRealizedGainOutcome.STALE_QUERY;
        return FubonRealizedGainOutcome.REALIZED_GAIN_FAILED;
    }

    private FubonRealizedGainOutcome recordOutcome(FubonRealizedGainOutcome outcome) {
        counters.increment(outcome);
        log.info("Fubon realized gain sync outcome={}", outcome);
        return outcome;
    }

    private RealizedGainSyncResult recorded(FubonRealizedGainOutcome outcome, boolean dryRun, int rowCount,
            int insertedCount, int alreadyRepresentedCount) {
        return result(recordOutcome(outcome), dryRun, rowCount, insertedCount, alreadyRepresentedCount);
    }

    private static RealizedGainSyncResult result(FubonRealizedGainOutcome outcome, boolean dryRun, int rowCount,
            int insertedCount, int alreadyRepresentedCount) {
        String reason = switch (outcome) {
            case NO_OWNER -> "NO_ACTIVE_CONFIGURED_ADMIN";
            case SYNC_OWNER_NOT_CONFIGURED -> "SYNC_OWNER_NOT_CONFIGURED";
            case REALIZED_GAIN_FAILED -> "REALIZED_GAIN_ADAPTER_FAILURE";
            case DRY_RUN -> "DRY_RUN_COMPLETE";
            default -> outcome.name();
        };
        return new RealizedGainSyncResult(outcome, dryRun, rowCount, insertedCount,
                alreadyRepresentedCount, 0, reason);
    }

    public record RealizedGainSyncResult(FubonRealizedGainOutcome outcome, boolean dryRun, int rowCount,
            int insertedCount, int alreadyRepresentedCount, int skippedNameUnresolvedCount, String reason) {}
}
