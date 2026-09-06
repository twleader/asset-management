package com.steven.assets.bff.apierrorlogs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiErrorLogDiagnosticRendererTest {
    @Test void raw_response_style_sentinel_is_not_kept_when_it_is_a_sensitive_value() {
        var renderer = new ApiErrorLogDiagnosticRenderer("", "ingest-runtime-secret", "google-runtime-secret");
        String result = renderer.render(new IllegalArgumentException(
                "{\"authorization\":\"Bearer raw-body-sentinel\",\"token\":\"json-sentinel\"} ingest-runtime-secret google-runtime-secret"));
        assertFalse(result.contains("raw-body-sentinel")); assertFalse(result.contains("json-sentinel"));
        assertFalse(result.contains("ingest-runtime-secret")); assertFalse(result.contains("google-runtime-secret"));
        assertTrue(result.contains("IllegalArgumentException"));
    }
}
