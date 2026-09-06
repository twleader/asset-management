package com.steven.assets.apierrorlog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j @Component
public class ApiErrorLogRetentionScheduler {
    private final ApiErrorLogRepository repository;
    public ApiErrorLogRetentionScheduler(ApiErrorLogRepository repository){this.repository=repository;}
    @Scheduled(cron="${api-error-log.retention-cron:0 15 3 * * *}", zone="Asia/Taipei")
    @Transactional public void deleteExpired(){ try { repository.deleteExpired(); } catch(RuntimeException failure){ log.warn("API error log retention failed error={}", failure.getClass().getSimpleName()); } }
}
