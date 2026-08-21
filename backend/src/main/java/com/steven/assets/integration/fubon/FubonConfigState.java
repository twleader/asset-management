package com.steven.assets.integration.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Lazy local feature/token state. Missing files never fail Spring startup. */
@Component
public class FubonConfigState {
    public enum State { DISABLED, READY, MISCONFIGURED }

    private final String enabledValue;
    private final Path sharedSecretDirectory;

    @Autowired
    public FubonConfigState(
            @Value("${fubon.enabled:false}") String enabledValue,
            @Value("${fubon.shared-secret-dir:/run/secrets/fubon/shared}") Path sharedSecretDirectory) {
        this.enabledValue = enabledValue;
        this.sharedSecretDirectory = sharedSecretDirectory;
    }

    FubonConfigState(boolean enabled, Path sharedSecretDirectory) {
        this(Boolean.toString(enabled), sharedSecretDirectory);
    }

    public Snapshot snapshot() {
        String normalizedEnabled = enabledValue == null
                ? "false"
                : enabledValue.trim().toLowerCase(Locale.ROOT);
        if (!"true".equals(normalizedEnabled) && !"false".equals(normalizedEnabled)) {
            return new Snapshot(State.MISCONFIGURED, null, "INVALID_ENABLED_FLAG");
        }
        if (!"true".equals(normalizedEnabled)) return new Snapshot(State.DISABLED, null, "DISABLED");
        Path tokenFile = sharedSecretDirectory.resolve("internal-service-token");
        try {
            if (Files.isSymbolicLink(tokenFile) || !Files.isRegularFile(tokenFile)) {
                return new Snapshot(State.MISCONFIGURED, null, "MISSING_SHARED_TOKEN");
            }
            String token = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
            if (token.isEmpty()) return new Snapshot(State.MISCONFIGURED, null, "MISSING_SHARED_TOKEN");
            return new Snapshot(State.READY, token, null);
        } catch (IOException | SecurityException exception) {
            return new Snapshot(State.MISCONFIGURED, null, "UNREADABLE_SHARED_TOKEN");
        }
    }

    public static final class Snapshot {
        private final State state;
        private final String token;
        private final String reason;

        Snapshot(State state, String token, String reason) {
            this.state = state;
            this.token = token;
            this.reason = reason;
        }

        public State state() { return state; }
        public String token() { return token; }
        public String reason() { return reason; }

        @Override
        public String toString() {
            return "Snapshot[state=" + state + ", reason=" + reason + "]";
        }
    }
}
