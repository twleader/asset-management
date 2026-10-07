package com.steven.assets.service.srpp;

import com.steven.assets.repository.SrppDailyReportMailRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class SrppDailyReportMailRetentionScheduler {
    private final SrppDailyReportMailRepository repository;

    @Scheduled(cron = "${srpp.daily-report.retention-cron:0 17 3 * * *}", zone = "Asia/Taipei")
    @Transactional
    public void deleteExpired() {
        try {
            repository.deleteSentBefore(Instant.now().minusSeconds(7 * 24 * 60 * 60));
        } catch (RuntimeException e) {
            log.warn("SRPP mail retention failed type={}", e.getClass().getSimpleName());
        }
    }
}
