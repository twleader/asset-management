package com.steven.assets.srpp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Requirement 163／Task 452.10：每日 03:25（Asia/Taipei）刪除 {@code trading_date < 今天 − retentionDays} 的 package
 * （evidence 由 FK cascade 刪除）；不刪 registry 與 owner key。retentionDays 小於 7 視為 7。
 */
@Slf4j
@Component
public class SrppContextRetentionScheduler {
    static final int MIN_RETENTION_DAYS = 7;

    private final SrppContextPackageRepository packages;
    private final int retentionDays;
    private final Clock clock;

    @Autowired
    public SrppContextRetentionScheduler(SrppContextPackageRepository packages,
                                         @Value("${srpp.retention-days:7}") int retentionDays) {
        this(packages, retentionDays, Clock.system(SrppTime.TW_ZONE));
    }

    SrppContextRetentionScheduler(SrppContextPackageRepository packages, int retentionDays, Clock clock) {
        this.packages = packages;
        this.retentionDays = Math.max(MIN_RETENTION_DAYS, retentionDays);
        this.clock = clock;
    }

    int retentionDays() {
        return retentionDays;
    }

    @Scheduled(cron = "${srpp.retention-cron:0 25 3 * * *}", zone = "Asia/Taipei")
    @Transactional
    public void purgeExpired() {
        LocalDate cutoff = LocalDate.now(clock.withZone(SrppTime.TW_ZONE)).minusDays(retentionDays);
        int deleted = packages.deleteTradingDateBefore(cutoff);
        if (deleted > 0) log.info("SRPP package 保留期清理 cutoff={} deleted={}", cutoff, deleted);
    }
}
