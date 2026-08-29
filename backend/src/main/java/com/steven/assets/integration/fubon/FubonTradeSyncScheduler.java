package com.steven.assets.integration.fubon;

import com.steven.assets.service.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed Taipei filled-trade sync schedule (Requirement 120 / Task 385).
 *
 * <p>Sibling of {@link FubonInventorySyncScheduler}: same cron cadence, same local single-flight
 * guard and tri-state calendar gate structure. Holds its own {@link #inFlight} guard, independent
 * of the inventory scheduler's — the two schedules never share it and never block each other.
 *
 * <p>The one deliberate difference from the inventory scheduler: {@link FubonTradeSyncService
 * #syncScheduledAfterCalendar} takes a second {@code dryRun} parameter (always {@code false} from
 * here), unlike the inventory service's single-parameter method of the same name.
 */
@Component
public class FubonTradeSyncScheduler {
    private final FubonConfigState configState;
    private final MarketDataService marketDataService;
    private final FubonTradeSyncService syncService;
    private final Clock clock;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    @Autowired
    public FubonTradeSyncScheduler(
            FubonConfigState configState,
            MarketDataService marketDataService,
            FubonTradeSyncService syncService) {
        this(configState, marketDataService, syncService, Clock.system(FubonTradeSyncService.TW_ZONE));
    }

    FubonTradeSyncScheduler(
            FubonConfigState configState,
            MarketDataService marketDataService,
            FubonTradeSyncService syncService,
            Clock clock) {
        this.configState = configState;
        this.marketDataService = marketDataService;
        this.syncService = syncService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 5,35 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTradeSync() {
        if (!inFlight.compareAndSet(false, true)) return;
        try {
            if (syncService.tradeSyncFeatureGate(false) != null) return;
            FubonConfigState.State state = configState.snapshot().state();
            if (state != FubonConfigState.State.READY) {
                syncService.localConfigOutcome(false, state);
                return;
            }
            LocalDate today = LocalDate.now(clock.withZone(FubonTradeSyncService.TW_ZONE));
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
            syncService.syncScheduledAfterCalendar(today, false);
        } finally {
            inFlight.set(false);
        }
    }
}
