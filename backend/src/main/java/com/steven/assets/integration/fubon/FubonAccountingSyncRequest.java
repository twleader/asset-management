package com.steven.assets.integration.fubon;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/** Exact-path and parameter boundaries for the three accounting endpoints, without global MVC changes. */
final class FubonAccountingSyncRequest {
    private FubonAccountingSyncRequest() {}

    static boolean targetsEndpoint(HttpServletRequest request, String endpoint) {
        String raw = request.getRequestURI();
        String decoded = raw;
        if (raw != null) {
            try {
                decoded = UriUtils.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidEscape) {
                // The servlet's resolved path must still be protected if decoding the URI fails.
            }
        }
        return withinEndpoint(raw, endpoint) || withinEndpoint(decoded, endpoint)
                || withinEndpoint(request.getServletPath(), endpoint);
    }

    private static boolean withinEndpoint(String path, String endpoint) {
        return path != null && path.replaceAll(";[^/]*", "").startsWith(endpoint);
    }

    static boolean hasValidParameters(HttpServletRequest request) {
        var parameters = request.getParameterMap();
        if (parameters.isEmpty()) {
            return true;
        }
        if (parameters.size() != 1 || !parameters.containsKey("dryRun")) {
            return false;
        }
        String[] values = parameters.get("dryRun");
        return values != null && values.length == 1
                && ("true".equals(values[0]) || "false".equals(values[0]));
    }

    /** Call only after validating parameters; omission defaults to a dry run. */
    static boolean dryRun(HttpServletRequest request) {
        return !"false".equals(request.getParameter("dryRun"));
    }
}
