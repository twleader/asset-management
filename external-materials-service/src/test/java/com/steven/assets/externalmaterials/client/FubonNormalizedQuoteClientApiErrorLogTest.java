package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class FubonNormalizedQuoteClientApiErrorLogTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-06T00:00:00Z"), ZoneOffset.UTC);

    @TempDir Path tempDir;

    @Test void schema_reject_after_an_outbound_quote_call_is_recorded_once() throws Exception {
        Path token = tempDir.resolve("internal-service-token");
        Files.writeString(token, "fixture-token\n");
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonNormalizedQuoteClient client = new FubonNormalizedQuoteClient("http://adapter:8080", token.toString(),
                (uri, secret, body, timeout) -> new FubonNormalizedQuoteClient.RawResponse(200, "{}"), NOW, writer);

        var result = client.fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(FubonNormalizedQuoteClient.BatchStatus.INVALID_RESPONSE);
        verify(writer).record(eq("FUBON_TW_QUOTES_LIVE"), eq("盤中台股即時報價"), any(Throwable.class), eq(NOW.instant()));
    }

    @Test void adapter_reported_failure_outcome_is_recorded_once_without_storing_the_raw_row() throws Exception {
        Path token = tempDir.resolve("internal-service-token-outcome");
        Files.writeString(token, "fixture-token\n");
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonNormalizedQuoteClient client = new FubonNormalizedQuoteClient("http://adapter:8080", token.toString(),
                (uri, secret, body, timeout) -> new FubonNormalizedQuoteClient.RawResponse(200, failureOutcomeBody()), NOW, writer);

        var result = client.fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(FubonNormalizedQuoteClient.BatchStatus.PARTIAL_FAILURE);
        verify(writer).record(eq("FUBON_TW_QUOTES_LIVE"), eq("盤中台股即時報價"), any(Throwable.class), eq(NOW.instant()));
    }

    @Test void null_response_after_an_outbound_quote_call_is_recorded_once() throws Exception {
        Path token = tempDir.resolve("internal-service-token-null-response");
        Files.writeString(token, "fixture-token\n");
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonNormalizedQuoteClient client = new FubonNormalizedQuoteClient("http://adapter:8080", token.toString(),
                (uri, secret, body, timeout) -> null, NOW, writer);

        var result = client.fetch(List.of("2330"));

        assertThat(result.status()).isEqualTo(FubonNormalizedQuoteClient.BatchStatus.INVALID_RESPONSE);
        verify(writer).record(eq("FUBON_TW_QUOTES_LIVE"), eq("盤中台股即時報價"), any(Throwable.class), eq(NOW.instant()));
    }

    private static String failureOutcomeBody() {
        return """
                {"batchId":"fixture-batch","counters":{"DISABLED":0,"MISCONFIGURED":0,"CALENDAR_UNKNOWN":0,
                "ACCOUNTING_FAILED":0,"RECONCILE_FAILED":0,"QUOTE_FAILED":0,"NO_OWNER":0,"NO_TODAY_SNAPSHOT":0,
                "BROKER_MISSING":0,"DRY_RUN":0,"SUCCESS":0,"EMPTY_CLEARED":0,"ROLLED_BACK":0},"quotes":[
                {"stockCode":"2330","status":"FAILURE","reason":"BROKER_FAILED","quote":null}]}
                """;
    }
}
