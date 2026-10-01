package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.Timestamp;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExternalApiErrorLogWriterTest {
    @Test void bindsOccurrenceTimeAsJdbcTimestamp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        var writer = new ExternalApiErrorLogWriter(jdbc, transactions, new ApiErrorLogDiagnosticRenderer(""));
        Instant occurredAt = Instant.parse("2026-10-01T04:00:00Z");

        writer.record("FUBON_HISTORICAL_INTRADAY_CANDLES_READ", "個股歷史分鐘K線查詢",
                new IllegalStateException("sanitized test"), occurredAt);

        verify(jdbc).update(contains("INSERT INTO api_error_log"), eq("FUBON_HISTORICAL_INTRADAY_CANDLES_READ"),
                eq("個股歷史分鐘K線查詢"), anyString(), anyString(), argThat(value ->
                        value instanceof Timestamp timestamp && timestamp.toInstant().equals(occurredAt)));
        verify(transactions).commit(any());
    }
}
