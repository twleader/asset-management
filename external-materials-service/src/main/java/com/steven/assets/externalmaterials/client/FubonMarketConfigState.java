package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonMarketAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.function.Function;

/** One shared lazy local config/token boundary. Never probes or logs a broker session. */
@Component
public class FubonMarketConfigState implements FubonMarketAccess {
    private final String enabled;
    private final String baseUrl;
    private final String tokenPath;
    private final Function<String, String> tokenReader;

    @Autowired
    public FubonMarketConfigState(@Value("${fubon.enabled:false}") String enabled,
            @Value("${fubon.base-url:http://fubon-broker-service:8080}") String baseUrl,
            @Value("${fubon.shared-token-path:/run/secrets/fubon/shared/internal-service-token}") String tokenPath) {
        this(enabled, baseUrl, tokenPath, FubonSharedTokenReader::read);
    }
    FubonMarketConfigState(String enabled, String baseUrl, String tokenPath, Function<String, String> reader) {
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.tokenPath = tokenPath;
        this.tokenReader = reader;
    }
    @Override public String unavailableReason() { return snapshot().reason(); }
    public Snapshot snapshot() {
        if ("false".equalsIgnoreCase(enabled)) return new Snapshot("DISABLED", null, null);
        if (!"true".equalsIgnoreCase(enabled)) return new Snapshot("MISCONFIGURED", null, null);
        URI base;
        try {
            base = URI.create(baseUrl);
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme()))
                    || base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                    || base.getRawFragment() != null
                    || !(base.getRawPath().isEmpty() || "/".equals(base.getRawPath())))
                return new Snapshot("MISCONFIGURED", null, null);
        } catch (RuntimeException invalid) { return new Snapshot("MISCONFIGURED", null, null); }
        String token;
        try { token = tokenReader.apply(tokenPath); }
        catch (RuntimeException unreadable) { return new Snapshot("MISCONFIGURED", null, null); }
        if (token == null || token.isBlank() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0)
            return new Snapshot("MISCONFIGURED", null, null);
        return new Snapshot(null, base, token);
    }
    public static final class Snapshot {
        private final String reason;
        private final URI base;
        private final String token;
        private Snapshot(String reason, URI base, String token) {
            this.reason = reason; this.base = base; this.token = token;
        }
        public String reason() { return reason; }
        public String token() { return token; }
        public URI endpoint(String path) {
            if (reason != null || path == null || !path.startsWith("/internal/"))
                throw new IllegalStateException("MISCONFIGURED");
            return base.resolve(path);
        }
        @Override public String toString() {
            return "FubonMarketConfigState[" + (reason == null ? "READY" : reason) + "]";
        }
    }
}
