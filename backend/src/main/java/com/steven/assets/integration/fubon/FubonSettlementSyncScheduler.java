package com.steven.assets.integration.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Four daily Taipei observations with an independent process-local overlap guard. */
@Component
public class FubonSettlementSyncScheduler {
    private final FubonSettlementSyncService syncService;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Autowired
    public FubonSettlementSyncScheduler(FubonSettlementSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    @Scheduled(cron = "0 45 13 * * *", zone = "Asia/Taipei")
    @Scheduled(cron = "0 30 19 * * *", zone = "Asia/Taipei")
    @Scheduled(cron = "0 0 22 * * *", zone = "Asia/Taipei")
    public void scheduledSettlementSync() {
        if (syncService.localConfigGate() != null) return;
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            syncService.syncScheduled();
        } finally {
            inFlight.set(false);
        }
    }
}
