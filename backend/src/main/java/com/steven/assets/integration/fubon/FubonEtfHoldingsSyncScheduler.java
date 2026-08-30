package com.steven.assets.integration.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed Taipei ETF-holdings sync schedule (Requirement 123 / Task 389).
 *
 * <p>Deliberately thinner than its siblings {@link FubonInventorySyncScheduler} /
 * {@link FubonTradeSyncScheduler}: the feature flag, {@link FubonConfigState} gate and tri-state
 * trading-day gate all live inside {@link FubonEtfHoldingsSyncService#syncScheduled()} itself, not
 * here. The two {@code @Scheduled} entry points share one process-local {@link #inFlight} guard
 * purely as a defensive measure -- the two cron times (08:50 / 15:30) never actually collide.
 */
@Component
public class FubonEtfHoldingsSyncScheduler {
    private final FubonEtfHoldingsSyncService syncService;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Autowired
    public FubonEtfHoldingsSyncScheduler(FubonEtfHoldingsSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Taipei")
    public void syncMorning() {
        runIfNotInFlight();
    }

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Taipei")
    public void syncAfternoon() {
        runIfNotInFlight();
    }

    private void runIfNotInFlight() {
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            syncService.syncScheduled();
        } finally {
            inFlight.set(false);
        }
    }
}
