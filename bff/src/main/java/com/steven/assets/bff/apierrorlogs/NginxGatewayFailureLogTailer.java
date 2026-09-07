package com.steven.assets.bff.apierrorlogs;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Captures Nginx-side connection failures that never reached the BFF application at all — e.g. the
 * gateway healthcheck hitting a 502 while {@code bff}/{@code business-services} are mid-recreate.
 * {@link PublicApiErrorCaptureWebFilter} is an in-process {@code WebFilter}; it is architecturally
 * blind to this class of failure because the request never entered WebFlux.
 *
 * <p>Every tick re-reads the whole file from byte zero. There is deliberately no persisted/cross-restart
 * cursor: correctness relies entirely on the {@code dedupe_key} partial unique index (Task 421 /
 * v1.125.0) rather than "only look at new bytes" cursor bookkeeping. That is exactly what makes a
 * BFF restart safe to capture — lines Nginx wrote while the BFF was offline must still be read on the
 * next tick after the BFF comes back, not skipped by a cursor that only looks forward.
 */
@Slf4j
@Component
public class NginxGatewayFailureLogTailer {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final String REQUEST_MARKER = "request: \"";

    private final Path errorLogPath;
    private final ApiErrorLogIngestClient ingest;
    private final ApiErrorLogDiagnosticRenderer renderer;

    public NginxGatewayFailureLogTailer(
            @Value("${api-error-log.nginx-error-log-path:/var/log/nginx-shared/error.log}") String errorLogPath,
            ApiErrorLogIngestClient ingest,
            ApiErrorLogDiagnosticRenderer renderer) {
        this.errorLogPath = Path.of(errorLogPath);
        this.ingest = ingest;
        this.renderer = renderer;
    }

    @Scheduled(fixedDelayString = "${api-error-log.nginx-tail-interval-ms:15000}")
    public void tail() {
        if (!Files.isRegularFile(errorLogPath)) {
            log.debug("Nginx gateway error log not mounted at {}; skipping this tick", errorLogPath);
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(errorLogPath, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            log.debug("Nginx gateway error log unreadable at {} error={}", errorLogPath, failure.getClass().getSimpleName());
            return;
        }
        for (String rawLine : lines) processLine(rawLine);
    }

    private void processLine(String rawLine) {
        String line = rawLine.trim();
        if (line.isEmpty() || !line.contains("[error]")) return;

        Instant occurredAt = parseTimestamp(line);
        if (occurredAt == null) return;

        MatchedRoute route = matchRoute(line);
        if (route == null) return;

        int httpStatus = line.contains("timed out") ? 504 : 502;
        String messageHeader = "Nginx 無法連線至上游服務 operation=" + route.operation.key() + " status=" + httpStatus;
        String stackTrace = renderer.sanitize(line);
        String dedupeKey = sha256Hex(line);

        ingest.ingest(route.operation.key(), route.operation.name(), messageHeader, stackTrace, occurredAt, httpStatus, dedupeKey);
    }

    private static Instant parseTimestamp(String line) {
        if (line.length() < 19) return null;
        try {
            LocalDateTime parsed = LocalDateTime.parse(line.substring(0, 19), TIMESTAMP);
            // Container system timezone is fixed UTC (see docker-compose.yml TZ note); never assume
            // the host/local timezone here.
            return parsed.toInstant(ZoneOffset.UTC);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static MatchedRoute matchRoute(String line) {
        int start = line.indexOf(REQUEST_MARKER);
        if (start < 0) return null;
        int contentStart = start + REQUEST_MARKER.length();
        int end = line.indexOf('"', contentStart);
        if (end < 0) return null;
        String requestLine = line.substring(contentStart, end);
        int firstSpace = requestLine.indexOf(' ');
        if (firstSpace < 0) return null;
        String method = requestLine.substring(0, firstSpace);
        String rest = requestLine.substring(firstSpace + 1);
        int secondSpace = rest.indexOf(' ');
        String path = secondSpace < 0 ? rest : rest.substring(0, secondSpace);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        OpenApiRouteCatalog.Operation op = OpenApiRouteCatalog.ROUTES.get(method + " " + path);
        return op == null ? null : new MatchedRoute(op);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record MatchedRoute(OpenApiRouteCatalog.Operation operation) {}
}
