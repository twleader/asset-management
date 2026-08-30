package com.steven.assets.integration.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed daily settlement bank balance sync schedule (Requirement 128 / Task 393): four fixed
 * Taipei times a day, every day including weekends/holidays -- deliberately not gated by
 * {@code MarketDataService.isTwTradingDayKnown} the way the inventory/trade sync schedules are,
 * because the settlement bank balance has nothing to do with the trading calendar.
 *
 * <p>Holds its own {@link #inFlight} single-flight guard, independent of the inventory and trade
 * sync schedulers' guards -- the three schedules never share it and never block each other.
 *
 * <p>Unlike {@link FubonTradeSyncScheduler} (which must resolve {@code today} and re-check the
 * calendar itself before it can call the service's date-parameterized method), this scheduler has
 * no extra state to resolve between the feature/config gate and the actual sync call, so it is a
 * thin delegate: {@link FubonBankBalanceSyncService#syncScheduled()} already performs the feature
 * flag and configState gate as the first step of its own shared internal flow and records the
 * matching outcome itself.
 */
@Component
public class FubonBankBalanceSyncScheduler {
    private final FubonBankBalanceSyncService syncService;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Autowired
    public FubonBankBalanceSyncScheduler(FubonBankBalanceSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Taipei")
    @Scheduled(cron = "0 0 14 * * *", zone = "Asia/Taipei")
    @Scheduled(cron = "0 0 22 * * *", zone = "Asia/Taipei")
    public void scheduledBankBalanceSync() {
        if (syncService.localConfigGate() != null) return;
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            syncService.syncScheduled();
        } finally {
            inFlight.set(false);
        }
    }
}
