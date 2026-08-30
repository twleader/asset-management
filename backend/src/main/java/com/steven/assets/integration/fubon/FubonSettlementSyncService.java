package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.UserAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.math.BigDecimal;

/** Read-only preflight: no approved rule establishes the completeness of the SDK's 3d range. */
@Service
@Slf4j
public class FubonSettlementSyncService {
    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final UserAdminService userAdminService;
    private final BrokerRepository brokerRepository;
    private final FubonSettlementOutcomeCounters counters;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public FubonSettlementSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            UserAdminService userAdminService, BrokerRepository brokerRepository,
            FubonSettlementOutcomeCounters counters, @Value("${fubon.settlement-sync-enabled:false}") boolean enabled) {
        this(configState, brokerClient, userAdminService, brokerRepository, counters, enabled, Clock.systemUTC());
    }

    FubonSettlementSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            UserAdminService userAdminService, BrokerRepository brokerRepository,
            FubonSettlementOutcomeCounters counters, boolean enabled, Clock clock) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.userAdminService = userAdminService;
        this.brokerRepository = brokerRepository;
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
    public FubonSettlementOutcome syncScheduled() { return syncShared(false).outcome(); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public SyncResult syncManual(boolean dryRun) { return syncShared(dryRun); }

    private SyncResult syncShared(boolean dryRun) {
        FubonSettlementOutcome gate = localConfigGate();
        if (gate != null) return result(gate, dryRun);
        AppUser owner = userAdminService.configuredAdmin().orElse(null);
        if (owner == null || owner.getId() == null || !owner.isActive() || !owner.isAdmin()) {
            return recorded(FubonSettlementOutcome.NO_OWNER, dryRun);
        }
        if (brokerRepository.findByCode("fubon").filter(b -> Boolean.TRUE.equals(b.getActive())).isEmpty()) {
            return recorded(FubonSettlementOutcome.BROKER_MISSING, dryRun);
        }
        try {
            FubonDtos.CallResult<FubonDtos.SettlementBatch> call = brokerClient.readSettlement();
            if (call == null || !call.success() || call.body() == null) {
                return recorded(outcomeFor(call == null ? null : call.reason()), dryRun);
            }
            FubonAccountingContract.validateSettlement(call.body(), clock);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded(outcomeFor(rejected.getMessage()), dryRun);
        } catch (RuntimeException failure) {
            return recorded(FubonSettlementOutcome.SETTLEMENT_FAILED, dryRun);
        }
        // Even an empty or valid future-day observation cannot prove a complete unsettled range.
        return recorded(FubonSettlementOutcome.SETTLEMENT_SCOPE_UNVERIFIED, dryRun);
    }

    private FubonSettlementOutcome outcomeFor(String reason) {
        if ("AMBIGUOUS_SETTLEMENT".equals(reason)) return FubonSettlementOutcome.AMBIGUOUS_SETTLEMENT;
        if ("SETTLEMENT_SCOPE_UNVERIFIED".equals(reason)) return FubonSettlementOutcome.SETTLEMENT_SCOPE_UNVERIFIED;
        return FubonSettlementOutcome.SETTLEMENT_FAILED;
    }

    private FubonSettlementOutcome recordOutcome(FubonSettlementOutcome outcome) {
        counters.increment(outcome);
        log.info("Fubon settlement preflight outcome={}", outcome);
        return outcome;
    }

    private SyncResult recorded(FubonSettlementOutcome outcome, boolean dryRun) {
        return result(recordOutcome(outcome), dryRun);
    }

    private SyncResult result(FubonSettlementOutcome outcome, boolean dryRun) {
        String reason = switch (outcome) {
            case NO_OWNER -> "NO_ACTIVE_CONFIGURED_ADMIN";
            case SETTLEMENT_FAILED -> "SETTLEMENT_ADAPTER_FAILURE";
            case SETTLEMENT_SCOPE_UNVERIFIED -> "MISSING_SETTLEMENT_RANGE_CONTRACT";
            default -> outcome.name();
        };
        return new SyncResult(outcome, dryRun, reason, null, null);
    }

    public record SyncResult(FubonSettlementOutcome outcome, boolean dryRun, String reason,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal payableAmount,
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal receivableAmount) {}
}
