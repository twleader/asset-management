package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorLogDiagnosticRendererTest {
    @TempDir Path tempDir;

    @Test void preserves_stack_frames_without_exposing_secret_values() {
        var renderer = new ApiErrorLogDiagnosticRenderer("known-token");
        String trace = renderer.render(new RuntimeException("api-key=leak known-token {\"authorization\":\"Bearer raw-body-sentinel\"}"));
        assertTrue(trace.contains("RuntimeException")); assertTrue(trace.contains("at "));
        assertFalse(trace.contains("leak")); assertFalse(trace.contains("known-token")); assertFalse(trace.contains("raw-body-sentinel"));
    }

    @Test void all_external_runtime_secrets_are_redacted_even_without_a_key_name() throws Exception {
        Path token = tempDir.resolve("internal-service-token");
        Files.writeString(token, "fubon-runtime-secret\n");
        var renderer = new ApiErrorLogDiagnosticRenderer("", "treasury-runtime-secret", "postgres-runtime-secret", "finmind-runtime-secret",
                new FubonMarketConfigState("true", "http://adapter:8080", token.toString()));

        String trace = renderer.render(new RuntimeException("useful diagnostic treasury-runtime-secret postgres-runtime-secret finmind-runtime-secret fubon-runtime-secret"));

        assertTrue(trace.contains("useful diagnostic"));
        assertFalse(trace.contains("treasury-runtime-secret"));
        assertFalse(trace.contains("postgres-runtime-secret"));
        assertFalse(trace.contains("finmind-runtime-secret"));
        assertFalse(trace.contains("fubon-runtime-secret"));
    }
}
