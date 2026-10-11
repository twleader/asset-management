package com.steven.assets.service.aiflow;

import com.steven.assets.repository.aiflow.AiFlowRepository;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 有界背景保留期限清理，不調度或等待任何金融流程。 */
@Service
public final class AiFlowRetentionService {
    private static final Logger LOG = LoggerFactory.getLogger(AiFlowRetentionService.class);
    private final AiFlowRepository repository;
    private final TransactionTemplate transaction;
    private final AtomicBoolean running = new AtomicBoolean();
    public AiFlowRetentionService(AiFlowRepository repository, PlatformTransactionManager manager) {
        this.repository = repository; transaction = new TransactionTemplate(manager); transaction.setTimeout(5);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { purge(); }
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Taipei")
    public void purge() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (int i = 0; i < 20; i++) {
                Integer count = transaction.execute(tx -> { repository.writeDeadline(); return repository.purgeExpired(500); });
                if (count == null || count < 500) break;
            }
            if (repository.expiredRemain()) LOG.info("AI流程過期清理尚有待處理批次，下輪繼續");
        } catch (RuntimeException failure) {
            LOG.warn("AI流程過期清理暫未完成 exception={}", failure.getClass().getSimpleName());
        } finally { running.set(false); }
    }
}
