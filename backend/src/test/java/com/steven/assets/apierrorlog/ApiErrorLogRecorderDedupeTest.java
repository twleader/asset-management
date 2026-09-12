package com.steven.assets.apierrorlog;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Requirement 148: duplicate-key writes use the named no-op insert, null keys keep append semantics. */
class ApiErrorLogRecorderDedupeTest {

    @Test
    void nonNullDedupeKeyUsesNativeConflictNoOpPath() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        ApiErrorLogRecorder recorder = new ApiErrorLogRecorder(repository,
                new ApiErrorLogDiagnosticRenderer("secret-value"), transactionManager());

        recorder.record("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "secret-value header", "trace",
                Instant.parse("2026-09-12T00:00:00Z"), 502, "a".repeat(64));

        verify(repository).insertIgnoringDuplicateDedupeKey(eq("OPEN_API"), eq("OPEN_MARKET_INDEX"),
                eq("大盤指數"), eq("[REDACTED] header"), eq("trace"), any(Instant.class), eq(502),
                eq("a".repeat(64)));
        verify(repository, never()).save(any(ApiErrorLog.class));
    }

    @Test
    void nullDedupeKeyKeepsOrdinaryAppendPath() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        ApiErrorLogRecorder recorder = new ApiErrorLogRecorder(repository,
                new ApiErrorLogDiagnosticRenderer(""), transactionManager());

        recorder.record("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "header", "trace",
                Instant.parse("2026-09-12T00:00:00Z"), 502, null);

        verify(repository).save(any(ApiErrorLog.class));
        verify(repository, never()).insertIgnoringDuplicateDedupeKey(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
