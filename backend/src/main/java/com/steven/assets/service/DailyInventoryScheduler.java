package com.steven.assets.service;

import com.steven.assets.repository.AssetSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.List;

@Slf4j
@Service
public class DailyInventoryScheduler {
    private final DailyInventoryCalendarPort calendar;
    private final AssetSnapshotRepository snapshots;
    private final DailyInventoryCaptureService captures;
    private final Clock clock;
    private final DailyInventoryHistoryRepository history;
    @Autowired
    public DailyInventoryScheduler(DailyInventoryCalendarPort calendar, AssetSnapshotRepository snapshots,
                                   DailyInventoryCaptureService captures, DailyInventoryHistoryRepository history) {
        this(calendar, snapshots, captures, history, Clock.systemUTC());
    }
    DailyInventoryScheduler(DailyInventoryCalendarPort calendar, AssetSnapshotRepository snapshots,
                            DailyInventoryCaptureService captures, DailyInventoryHistoryRepository history, Clock clock) {
        this.calendar = calendar; this.snapshots = snapshots; this.captures = captures; this.history = history; this.clock = clock;
    }
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        try {
            int deleted = history.deleteExpired();
            if (deleted > 0) log.info("expired daily inventory purged headers={}", deleted);
        } catch (RuntimeException failure) {
            log.warn("daily inventory retention failed type={}", failure.getClass().getSimpleName());
        }
        for (String market : List.of("台股", "美股")) {
            try {
                var due = calendar.dueSession(market, clock.instant());
                if (due.isEmpty()) continue;
                for (Long owner : snapshots.findDistinctOwnerUserIds()) {
                    try {
                        if (captures.capture(owner, due.get()))
                            log.info("daily inventory saved owner={} market={} tradingDate={}", owner, market, due.get().tradingDate());
                    } catch (RuntimeException failure) {
                        log.warn("daily inventory capture failed owner={} market={} tradingDate={} type={}",
                                owner, market, due.get().tradingDate(), failure.getClass().getSimpleName());
                    }
                }
            } catch (RuntimeException failure) {
                log.warn("daily inventory calendar unavailable market={} type={}", market, failure.getClass().getSimpleName());
            }
        }
    }
}
