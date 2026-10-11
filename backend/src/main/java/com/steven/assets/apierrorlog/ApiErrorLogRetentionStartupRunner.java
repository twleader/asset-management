package com.steven.assets.apierrorlog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ApiErrorLogRetentionStartupRunner implements ApplicationRunner {
    private final ApiErrorLogRetentionScheduler scheduler;

    public ApiErrorLogRetentionStartupRunner(ApiErrorLogRetentionScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            scheduler.deleteExpired().ifPresent(deleted ->
                    log.info("API error log startup retention completed deleted={}", deleted));
        } catch (RuntimeException failure) {
            log.warn("API error log startup retention failed error={}", failure.getClass().getSimpleName());
        }
    }
}
