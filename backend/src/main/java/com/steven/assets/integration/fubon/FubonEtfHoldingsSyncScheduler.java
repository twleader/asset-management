package com.steven.assets.integration.fubon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Fixed Taipei ETF-holdings sync schedule plus a missing-only lifecycle gap fill.
 *
 * <p>Deliberately thinner than its siblings {@link FubonInventorySyncScheduler} /
 * {@link FubonTradeSyncScheduler}: the feature flag, {@link FubonConfigState} gate and tri-state
 * trading-day gate all live inside {@link FubonEtfHoldingsSyncService#syncScheduled()} itself, not
 * here. Scheduled FULL and ApplicationReady STARTUP intents share a process-local, O(1), bounded
 * coalescing runner. FULL always has priority, while a trigger arriving after its bit was consumed
 * remains pending for the next drain iteration.
 */
@Component
@Slf4j
public class FubonEtfHoldingsSyncScheduler {
    static final String STARTUP_THREAD_NAME = "fubon-etf-holdings-startup";

    private final FubonEtfHoldingsSyncService syncService;
    private final Consumer<Runnable> backgroundStarter;
    private final AtomicBoolean startupSubmitted = new AtomicBoolean();
    private final Object stateLock = new Object();
    private boolean pendingStartup;
    private boolean pendingFull;
    private boolean running;

    @Autowired
    public FubonEtfHoldingsSyncScheduler(FubonEtfHoldingsSyncService syncService) {
        this(syncService, task -> Thread.ofVirtual().name(STARTUP_THREAD_NAME).start(task));
    }

    FubonEtfHoldingsSyncScheduler(FubonEtfHoldingsSyncService syncService,
            Consumer<Runnable> backgroundStarter) {
        this.syncService = syncService;
        this.backgroundStarter = backgroundStarter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!startupSubmitted.compareAndSet(false, true)) return;
        try {
            backgroundStarter.accept(() -> submit(Intent.STARTUP));
        } catch (RuntimeException exception) {
            log.warn("Fubon ETF holdings startup background thread outcome=FAILED reason=THREAD_START_FAILED");
        }
    }

    @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Taipei")
    public void syncMorning() {
        submit(Intent.FULL);
    }

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Taipei")
    public void syncAfternoon() {
        submit(Intent.FULL);
    }

    private void submit(Intent intent) {
        boolean becomeRunner = false;
        synchronized (stateLock) {
            if (intent == Intent.FULL) pendingFull = true;
            else pendingStartup = true;
            if (!running) {
                running = true;
                becomeRunner = true;
            }
        }
        if (becomeRunner) drain();
    }

    private void drain() {
        while (true) {
            Intent intent;
            synchronized (stateLock) {
                if (pendingFull) {
                    pendingFull = false;
                    intent = Intent.FULL;
                } else if (pendingStartup) {
                    pendingStartup = false;
                    intent = Intent.STARTUP;
                } else {
                    running = false;
                    return;
                }
            }
            try {
                if (intent == Intent.FULL) syncService.syncScheduled();
                else syncService.syncMissingOnStartup();
            } catch (RuntimeException exception) {
                log.warn("Fubon ETF holdings sync action outcome=FAILED intent={} reason=UNEXPECTED_FAILURE", intent);
            }
        }
    }

    private enum Intent { FULL, STARTUP }
}
