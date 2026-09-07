package com.steven.assets.bff.apierrorlogs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 421／Requirement 143: {@link NginxGatewayFailureLogTailer} is the second OPEN_API producer,
 * reading Nginx's own {@code [error]}-level lines for connection failures that never reached the BFF
 * application at all. The fixture line below is copied verbatim from a real capture (see spec item
 * 421.7) — this test must not reformat it.
 */
class NginxGatewayFailureLogTailerTest {

    /** Copied verbatim from spec/tasks/t421_api_error_log_status_and_gateway_capture.md, item 421.7. */
    private static final String MARKET_INDEX_CONNECTION_REFUSED_LINE =
            "2026/09/07 01:48:59 [error] 30#30: *21507 connect() failed (111: Connection refused) while "
                    + "connecting to upstream, client: 127.0.0.1, server: _, request: \"GET /api/public/market-index "
                    + "HTTP/1.1\", upstream: \"http://172.18.0.4:8080/api/public/market-index\", host: \"127.0.0.1:9090\"";

    private static final String BUFFERED_WARN_LINE =
            "2026/09/07 01:49:00 [warn] 30#30: *21508 an upstream response is buffered to a temporary file "
                    + "/var/cache/nginx/proxy_temp/0/00/0000000000 while reading upstream, client: 127.0.0.1, "
                    + "server: _, request: \"GET /api/quotes HTTP/1.1\", upstream: \"http://172.18.0.3:8080/api/quotes\", "
                    + "host: \"127.0.0.1:9090\"";

    @Test
    void matchedErrorLineIngestsOnceWithOpenMarketIndexStatus502AndTheLinesStableSha256DedupeKey(@TempDir Path dir) throws Exception {
        Path log = writeLog(dir, MARKET_INDEX_CONNECTION_REFUSED_LINE);
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        NginxGatewayFailureLogTailer tailer = tailer(log, ingest);

        tailer.tail();

        String expectedDedupeKey = sha256Hex(MARKET_INDEX_CONNECTION_REFUSED_LINE);
        assertThat(expectedDedupeKey).hasSize(64);
        verify(ingest).ingest(eq("OPEN_MARKET_INDEX"), eq("大盤指數"), anyString(), anyString(),
                eq(Instant.parse("2026-09-07T01:48:59Z")), eq(502), eq(expectedDedupeKey));
        verifyNoMoreInteractions(ingest);
    }

    @Test
    void warnLevelLineIsSkippedNotFailed(@TempDir Path dir) throws IOException {
        Path log = writeLog(dir, BUFFERED_WARN_LINE);
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        NginxGatewayFailureLogTailer tailer = tailer(log, ingest);

        tailer.tail();

        verifyNoInteractions(ingest);
    }

    @Test
    void errorLineForARouteOutsideTheThirteenRouteAllowlistIsDiscarded(@TempDir Path dir) throws IOException {
        String line = "2026/09/07 01:48:59 [error] 30#30: *21507 connect() failed (111: Connection refused) while "
                + "connecting to upstream, client: 127.0.0.1, server: _, request: \"GET /api/not-catalogued "
                + "HTTP/1.1\", upstream: \"http://172.18.0.4:8080/api/not-catalogued\", host: \"127.0.0.1:9090\"";
        Path log = writeLog(dir, line);
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        NginxGatewayFailureLogTailer tailer = tailer(log, ingest);

        tailer.tail();

        verifyNoInteractions(ingest);
    }

    @Test
    void messageContainingTimedOutUsesHttpStatus504(@TempDir Path dir) throws IOException {
        String line = "2026/09/07 01:48:59 [error] 30#30: *21507 upstream timed out (110: Connection timed out) "
                + "while connecting to upstream, client: 127.0.0.1, server: _, request: \"GET /api/public/market-index "
                + "HTTP/1.1\", upstream: \"http://172.18.0.4:8080/api/public/market-index\", host: \"127.0.0.1:9090\"";
        Path log = writeLog(dir, line);
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        NginxGatewayFailureLogTailer tailer = tailer(log, ingest);

        tailer.tail();

        verify(ingest).ingest(eq("OPEN_MARKET_INDEX"), eq("大盤指數"), anyString(), anyString(), any(Instant.class), eq(504), anyString());
    }

    @Test
    void sameLineScannedByTwoConsecutiveTicksIngestsTwiceWithAnIdenticalDedupeKey(@TempDir Path dir) throws IOException {
        Path log = writeLog(dir, MARKET_INDEX_CONNECTION_REFUSED_LINE);
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        NginxGatewayFailureLogTailer tailer = tailer(log, ingest);

        tailer.tail();
        tailer.tail();

        ArgumentCaptor<String> dedupeKeys = ArgumentCaptor.forClass(String.class);
        verify(ingest, times(2)).ingest(eq("OPEN_MARKET_INDEX"), eq("大盤指數"), anyString(), anyString(),
                any(Instant.class), eq(502), dedupeKeys.capture());
        // Dedup itself is the DB unique index's job (see ApiErrorLogDedupeKeyPostgresTest); the tailer
        // only needs to keep producing the same, stable key every re-read of an unchanged file.
        assertThat(dedupeKeys.getAllValues()).hasSize(2);
        assertThat(dedupeKeys.getAllValues().get(0)).isEqualTo(dedupeKeys.getAllValues().get(1));
    }

    @Test
    void missingMountedFileDoesNotThrowAndIngestsNothing(@TempDir Path dir) {
        Path missing = dir.resolve("not-mounted").resolve("error.log");
        ApiErrorLogIngestClient ingest = mock(ApiErrorLogIngestClient.class);
        NginxGatewayFailureLogTailer tailer = tailer(missing, ingest);

        tailer.tail();

        verifyNoInteractions(ingest);
    }

    private static Path writeLog(Path dir, String... lines) throws IOException {
        Path log = dir.resolve("error.log");
        Files.writeString(log, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return log;
    }

    private static NginxGatewayFailureLogTailer tailer(Path log, ApiErrorLogIngestClient ingest) {
        return new NginxGatewayFailureLogTailer(log.toString(), ingest, new ApiErrorLogDiagnosticRenderer(""));
    }

    private static String sha256Hex(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
