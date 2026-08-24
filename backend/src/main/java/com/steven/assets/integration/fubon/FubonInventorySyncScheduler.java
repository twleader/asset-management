package com.steven.assets.integration.fubon;

import com.steven.assets.service.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed Taipei inventory schedule with a local single-flight and tri-state calendar gate. */
@Component
public class FubonInventorySyncScheduler {
    private final FubonConfigState configState;
    private final MarketDataService marketDataService;
    private final FubonInventorySyncService syncService;
    private final Clock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Autowired
    public FubonInventorySyncScheduler(
            FubonConfigState configState,
            MarketDataService marketDataService,
            FubonInventorySyncService syncService) {
        this(configState, marketDataService, syncService, Clock.system(FubonInventorySyncService.TW_ZONE));
    }

    FubonInventorySyncScheduler(
            FubonConfigState configState,
            MarketDataService marketDataService,
            FubonInventorySyncService syncService,
            Clock clock) {
        this.configState = configState;
        this.marketDataService = marketDataService;
        this.syncService = syncService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5,35 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledInventorySync() {
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            if (syncService.inventoryFeatureGate(false) != null) return;
            FubonConfigState.State state = configState.snapshot().state();
            if (state != FubonConfigState.State.READY) {
                syncService.localConfigOutcome(false, state);
                return;
            }
            LocalDate today = LocalDate.now(clock.withZone(FubonInventorySyncService.TW_ZONE));
            Optional<Boolean> tradingDay;
            try {
                tradingDay = marketDataService.isTwTradingDayKnown(today);
            } catch (RuntimeException exception) {
                tradingDay = Optional.empty();
            }
            if (!tradingDay.orElse(false)) {
                syncService.calendarUnknown(false);
                return;
            }
            syncService.syncScheduledAfterCalendar(today);
        } finally {
            inFlight.set(false);
        }
    }
}
