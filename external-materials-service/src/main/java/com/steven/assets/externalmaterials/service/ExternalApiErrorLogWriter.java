package com.steven.assets.externalmaterials.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

/** The sole Task 417 external cross-service DB exception: fixed-source parameterized INSERT only. */
@Slf4j @Component public class ExternalApiErrorLogWriter {
    private final JdbcTemplate jdbc; private final TransactionTemplate writes; private final ApiErrorLogDiagnosticRenderer renderer;
    public ExternalApiErrorLogWriter(JdbcTemplate jdbc,PlatformTransactionManager transactionManager,ApiErrorLogDiagnosticRenderer renderer){this.jdbc=jdbc;this.renderer=renderer;writes=new TransactionTemplate(transactionManager);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    public void record(String operationKey,String apiName,Throwable failure,Instant occurredAt){
        try { writes.executeWithoutResult(status -> jdbc.update("INSERT INTO api_error_log (source, operation_key, api_name, message_header, stack_trace, occurred_at) VALUES ('FUBON_API', ?, ?, ?, ?, ?)",operationKey,apiName,renderer.message(failure),renderer.render(failure),occurredAt)); }
        catch(RuntimeException rejected){log.warn("external API error log write failed operation={} error={}",operationKey,rejected.getClass().getSimpleName());}
    }
}
