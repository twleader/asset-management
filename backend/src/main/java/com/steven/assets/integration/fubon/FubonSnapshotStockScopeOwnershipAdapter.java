package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.SnapshotStockScopeOwnership;
import com.steven.assets.service.SnapshotStockScopeOwnershipPort;
import com.steven.assets.service.SnapshotUpdateTarget;
import com.steven.assets.service.UserAdminService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Maps the actual Fubon inventory-writer capability and target to the ownership
 * of Taiwan/Fubon rows in one full snapshot PUT.
 *
 * <p>This adapter intentionally captures configuration once per invocation. A
 * complete PUT reuses the returned immutable decision, so it cannot observe a
 * different flag or target state while rebuilding individual holdings.
 */
@Service
public class FubonSnapshotStockScopeOwnershipAdapter implements SnapshotStockScopeOwnershipPort {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    private final FubonConfigState configState;
    private final UserAdminService userAdminService;
    private final AssetSnapshotRepository snapshotRepository;
    private final Clock clock;
    private final boolean inventorySyncEnabled;

    @Autowired
    public FubonSnapshotStockScopeOwnershipAdapter(
            FubonConfigState configState,
            UserAdminService userAdminService,
            AssetSnapshotRepository snapshotRepository,
            @Value("${fubon.inventory-sync-enabled:false}") boolean inventorySyncEnabled) {
        this(configState, userAdminService, snapshotRepository, Clock.system(TW_ZONE),
                inventorySyncEnabled);
    }

    FubonSnapshotStockScopeOwnershipAdapter(
            FubonConfigState configState,
            UserAdminService userAdminService,
            AssetSnapshotRepository snapshotRepository,
            Clock clock,
            boolean inventorySyncEnabled) {
        this.configState = configState;
        this.userAdminService = userAdminService;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
        this.inventorySyncEnabled = inventorySyncEnabled;
    }

    @Override
    public SnapshotStockScopeOwnership capture(SnapshotUpdateTarget target) {
        // Read Fubon config exactly once for this transaction-scoped decision.
        FubonConfigState.Snapshot config = configState.snapshot();
        if (config == null
                || config.state() != FubonConfigState.State.READY
                || !inventorySyncEnabled
                || !isCurrentWriterTarget(target)) {
            return SnapshotStockScopeOwnership.payloadOwned();
        }
        return SnapshotStockScopeOwnership.sourceOwnsFubonTw();
    }

    /** Mirrors FubonInventorySyncService preflight and FubonInventoryWriter target semantics. */
    private boolean isCurrentWriterTarget(SnapshotUpdateTarget target) {
        LocalDate today = LocalDate.now(clock.withZone(TW_ZONE));
        if (!today.equals(target.effectiveSnapshotDate())) return false;

        Optional<AppUser> configured = userAdminService.configuredAdmin();
        if (configured.isEmpty() || configured.get().getId() == null
                || !configured.get().isActive() || !configured.get().isAdmin()) {
            return false;
        }
        AppUser owner = configured.get();
        if (!owner.getId().equals(target.ownerUserId())) return false;

        return snapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(owner.getId())
                .filter(latest -> target.snapshotId().equals(latest.getId()))
                .map(AssetSnapshot::getSnapshotDate)
                .filter(target.effectiveSnapshotDate()::equals)
                .isPresent();
    }
}
