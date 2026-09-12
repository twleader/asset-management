package com.steven.assets.apierrorlog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

@Slf4j
@Service
public class ApiErrorLogRecorder {
    private final ApiErrorLogRepository repository;
    private final ApiErrorLogDiagnosticRenderer renderer;
    private final TransactionTemplate writes;
    public ApiErrorLogRecorder(ApiErrorLogRepository repository, ApiErrorLogDiagnosticRenderer renderer, PlatformTransactionManager transactionManager) { this.repository=repository; this.renderer=renderer; this.writes=new TransactionTemplate(transactionManager); this.writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); }
    public void record(String source, String operationKey, String apiName, String messageHeader, String stackTrace, Instant occurredAt, Integer httpStatus, String dedupeKey) {
        try {
            ApiErrorLogOperationCatalog.require(source, operationKey, apiName);
            // Validate and sanitize once before choosing the write path.  A non-null dedupe key
            // originates from the stable gateway-line digest; a repeated line is a successful,
            // silent no-op, while every other storage error still reaches this catch block.
            String safeHeader = renderer.sanitize(messageHeader);
            String safeTrace = renderer.sanitize(stackTrace);
            writes.executeWithoutResult(status -> {
                if (dedupeKey != null) {
                    repository.insertIgnoringDuplicateDedupeKey(source, operationKey, apiName,
                            safeHeader, safeTrace, occurredAt, httpStatus, dedupeKey);
                } else {
                    repository.save(new ApiErrorLog(source, operationKey, apiName,
                            safeHeader, safeTrace, occurredAt, httpStatus, null));
                }
            });
        } catch (RuntimeException failure) {
            log.warn("API error log recorder failed source={} operation={} error={}", source, operationKey, failure.getClass().getSimpleName());
        }
    }
    public void record(Throwable throwable, String source, String operationKey, String apiName, Instant occurredAt) {
        record(source, operationKey, apiName, renderer.message(throwable), renderer.render(throwable), occurredAt, null, null);
    }
}
