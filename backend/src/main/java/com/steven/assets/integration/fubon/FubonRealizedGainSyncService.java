package com.steven.assets.integration.fubon;

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

/** Safe observation only: neither editable ledger labels nor a value tuple prove trade identity. */
@Service
@Slf4j
public class FubonRealizedGainSyncService {
    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final UserAdminService userAdminService;
    private final BrokerRepository brokerRepository;
    private final FubonRealizedGainOutcomeCounters counters;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public FubonRealizedGainSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            UserAdminService userAdminService, BrokerRepository brokerRepository,
            FubonRealizedGainOutcomeCounters counters,
            @Value("${fubon.realized-gain-sync-enabled:false}") boolean enabled) {
        this(configState, brokerClient, userAdminService, brokerRepository, counters, enabled, Clock.systemUTC());
    }

    FubonRealizedGainSyncService(FubonConfigState configState, FubonBrokerClient brokerClient,
            UserAdminService userAdminService, BrokerRepository brokerRepository,
            FubonRealizedGainOutcomeCounters counters, boolean enabled, Clock clock) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.userAdminService = userAdminService;
        this.brokerRepository = brokerRepository;
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
    public FubonRealizedGainOutcome syncScheduled() { return syncShared(false).outcome(); }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RealizedGainSyncResult syncManual(boolean dryRun) { return syncShared(dryRun); }

    private RealizedGainSyncResult syncShared(boolean dryRun) {
        FubonRealizedGainOutcome gate = localConfigGate();
        if (gate != null) return result(gate, dryRun, 0);
        AppUser owner = userAdminService.configuredAdmin().orElse(null);
        if (owner == null || owner.getId() == null || !owner.isActive() || !owner.isAdmin()) {
            return recorded(FubonRealizedGainOutcome.NO_OWNER, dryRun, 0);
        }
        if (brokerRepository.findByCode("fubon").filter(b -> Boolean.TRUE.equals(b.getActive())).isEmpty()) {
            return recorded(FubonRealizedGainOutcome.BROKER_MISSING, dryRun, 0);
        }
        int rowCount = 0;
        try {
            FubonDtos.CallResult<FubonDtos.RealizedGainBatch> call = brokerClient.readRealizedGains();
            if (call == null || !call.success() || call.body() == null) {
                return recorded(outcomeFor(call == null ? null : call.reason()), dryRun, 0);
            }
            rowCount = call.body().rows() == null ? 0 : call.body().rows().size();
            FubonAccountingContract.validateRealized(call.body(), clock);
        } catch (FubonAccountingContract.Rejected rejected) {
            return recorded(outcomeFor(rejected.getMessage()), dryRun, rowCount);
        } catch (RuntimeException failure) {
            return recorded(FubonRealizedGainOutcome.REALIZED_GAIN_FAILED, dryRun, 0);
        }
        // There is intentionally no gain repository, ledger lookup, lock, or financial writer here.
        return recorded(FubonRealizedGainOutcome.IDENTITY_UNVERIFIED, dryRun, rowCount);
    }

    private FubonRealizedGainOutcome outcomeFor(String reason) {
        if ("AMBIGUOUS_IDENTITY".equals(reason)) return FubonRealizedGainOutcome.AMBIGUOUS_IDENTITY;
        if ("ACCOUNTING_SEMANTICS_UNVERIFIED".equals(reason)) return FubonRealizedGainOutcome.ACCOUNTING_SEMANTICS_UNVERIFIED;
        return FubonRealizedGainOutcome.REALIZED_GAIN_FAILED;
    }

    private FubonRealizedGainOutcome recordOutcome(FubonRealizedGainOutcome outcome) {
        counters.increment(outcome);
        log.info("Fubon realized gain preflight outcome={}", outcome);
        return outcome;
    }

    private RealizedGainSyncResult recorded(FubonRealizedGainOutcome outcome, boolean dryRun, int rowCount) {
        return result(recordOutcome(outcome), dryRun, rowCount);
    }

    private RealizedGainSyncResult result(FubonRealizedGainOutcome outcome, boolean dryRun, int rowCount) {
        String reason = switch (outcome) {
            case NO_OWNER -> "NO_ACTIVE_CONFIGURED_ADMIN";
            case REALIZED_GAIN_FAILED -> "REALIZED_GAIN_ADAPTER_FAILURE";
            default -> outcome.name();
        };
        return new RealizedGainSyncResult(outcome, dryRun, rowCount, 0, 0, 0, reason);
    }

    public record RealizedGainSyncResult(FubonRealizedGainOutcome outcome, boolean dryRun, int rowCount,
            int insertedCount, int alreadyRepresentedCount, int skippedNameUnresolvedCount, String reason) {}
}
