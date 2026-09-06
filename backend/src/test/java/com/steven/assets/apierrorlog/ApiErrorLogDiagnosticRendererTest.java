package com.steven.assets.apierrorlog;

import com.steven.assets.integration.fubon.FubonConfigState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorLogDiagnosticRendererTest {
    @TempDir Path tempDir;

    @Test void retains_non_sensitive_diagnostic_but_replaces_runtime_and_key_value_secrets() {
        var renderer = new ApiErrorLogDiagnosticRenderer("runtime-secret");
        String trace = renderer.render(new IllegalStateException(
                "failed token=abc password: xyz runtime-secret {\"token\":\"json-sentinel\"} {\"authorization\":\"Bearer raw-body-sentinel\"}",
                new RuntimeException("useful cause")));
        assertTrue(trace.contains("IllegalStateException")); assertTrue(trace.contains("useful cause"));
        assertFalse(trace.contains("runtime-secret")); assertFalse(trace.contains("token=abc")); assertFalse(trace.contains("password: xyz"));
        assertFalse(trace.contains("json-sentinel")); assertFalse(trace.contains("raw-body-sentinel"));
    }

    @Test void all_business_runtime_secrets_are_exactly_redacted_even_without_a_key_name() throws Exception {
        Files.writeString(tempDir.resolve("internal-service-token"), "fubon-runtime-secret\n");
        var renderer = new ApiErrorLogDiagnosticRenderer("", "ingest-runtime-secret", "treasury-runtime-secret",
                "database-runtime-secret", "mail-runtime-secret", "anthropic-runtime-secret", "google-runtime-secret",
                new FubonConfigState("true", tempDir));
        String trace = renderer.render(new RuntimeException("useful diagnostic ingest-runtime-secret treasury-runtime-secret "
                + "database-runtime-secret mail-runtime-secret anthropic-runtime-secret google-runtime-secret fubon-runtime-secret"));
        assertTrue(trace.contains("useful diagnostic"));
        for (String secret : new String[] {"ingest-runtime-secret", "treasury-runtime-secret", "database-runtime-secret",
                "mail-runtime-secret", "anthropic-runtime-secret", "google-runtime-secret", "fubon-runtime-secret"}) {
            assertFalse(trace.contains(secret));
        }
    }
}
