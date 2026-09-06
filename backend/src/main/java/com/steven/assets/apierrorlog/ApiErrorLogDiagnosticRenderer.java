package com.steven.assets.apierrorlog;

import com.steven.assets.integration.fubon.FubonConfigState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Renders throwable diagnostics without serialising HTTP bodies or retaining local secrets. */
@Component
public class ApiErrorLogDiagnosticRenderer {
    private static final Pattern SENSITIVE_KEY_VALUE = Pattern.compile(
            "(?i)([\\\"']?(?:access[ _-]?token|refresh[ _-]?token|token|client[ _-]?secret|secret|password|authorization|api[ _-]?key|certificate|account|identity)[\\\"']?\\s*(?:=|:)\\s*)([\\\"']?)(?:bearer\\s+)?([^\\s,;\\\"'}&]+)([\\\"']?)");
    private static final Pattern BEARER_VALUE = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;\\\"'}&]+");
    private final List<String> configuredSecrets;
    private final Supplier<List<String>> runtimeSecrets;

    /** Isolated tests can provide only deterministic sentinel values. */
    public ApiErrorLogDiagnosticRenderer(String configuredSecrets) {
        this(configuredSecrets, List::of);
    }

    /**
     * Secrets are read only when a diagnostic is rendered.  The Fubon snapshot performs no
     * broker request; it only reads the already-mounted local credential after its feature gate.
     */
    @Autowired
    public ApiErrorLogDiagnosticRenderer(
            @Value("${api-error-log.runtime-secrets:}") String configuredSecrets,
            @Value("${API_ERROR_LOG_INGEST_TOKEN:}") String ingestToken,
            @Value("${INTERNAL_TREASURY_TOKEN:}") String treasuryToken,
            @Value("${DB_PASSWORD:}") String databasePassword,
            @Value("${MAIL_PASSWORD:}") String mailPassword,
            @Value("${ANTHROPIC_API_KEY:}") String anthropicApiKey,
            @Value("${GOOGLE_CLIENT_SECRET:}") String googleClientSecret,
            FubonConfigState fubonConfigState) {
        this(configuredSecrets, () -> {
            List<String> values = new ArrayList<>(Arrays.asList(
                    ingestToken, treasuryToken, databasePassword, mailPassword, anthropicApiKey, googleClientSecret));
            FubonConfigState.Snapshot snapshot = fubonConfigState.snapshot();
            if (snapshot.state() == FubonConfigState.State.READY) values.add(snapshot.token());
            return values;
        });
    }

    private ApiErrorLogDiagnosticRenderer(String configuredSecrets, Supplier<List<String>> runtimeSecrets) {
        this.configuredSecrets = split(configuredSecrets);
        this.runtimeSecrets = runtimeSecrets;
    }
    public String message(Throwable throwable) { return sanitize(throwable == null ? "" : throwable.getMessage()); }
    public String render(Throwable throwable) {
        if (throwable == null) return "";
        StringBuilder out = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (!out.isEmpty()) out.append("Caused by: ");
            out.append(current.getClass().getName());
            String message = sanitize(current.getMessage());
            if (!message.isBlank()) out.append(": ").append(message);
            out.append('\n');
            for (StackTraceElement frame : current.getStackTrace()) out.append("\tat ").append(frame).append('\n');
        }
        return out.toString();
    }
    public String sanitize(String input) {
        String safe = input == null ? "" : input;
        for (String secret : allSecrets()) safe = safe.replace(secret, "[REDACTED]");
        safe = SENSITIVE_KEY_VALUE.matcher(safe).replaceAll("$1$2[REDACTED]$4");
        return BEARER_VALUE.matcher(safe).replaceAll("Bearer [REDACTED]");
    }

    private List<String> allSecrets() {
        List<String> values = new ArrayList<>(configuredSecrets);
        try { values.addAll(runtimeSecrets.get()); } catch (RuntimeException ignored) { /* logging must not fail the caller */ }
        return values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty())
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    private static List<String> split(String values) {
        return Arrays.stream((values == null ? "" : values).split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
    }
}
