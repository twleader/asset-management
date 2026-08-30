package com.steven.assets.externalmaterials.config;

import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Exact protected internal endpoints; path aliases, bodies and additional selectors are rejected. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class FubonScheduledMarketTokenFilter extends OncePerRequestFilter {
    private static final Map<String, String> ROUTES = Map.of(
            "/internal/dividend/fubon-sync", "POST",
            "/internal/technical-indicators/fubon-sync", "POST",
            "/internal/technical-indicators/fubon-cache", "GET");
    private final Supplier<FubonMarketConfigState> config;
    @Autowired
    public FubonScheduledMarketTokenFilter(ObjectProvider<FubonMarketConfigState> config) {
        this.config = config::getIfAvailable;
    }
    public FubonScheduledMarketTokenFilter(FubonMarketConfigState config) { this.config = () -> config; }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String raw = request.getRequestURI();
        if (raw == null) return true;
        String decoded;
        try { decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { decoded = raw; }
        String path = decoded;
        return ROUTES.keySet().stream().noneMatch(p -> raw.startsWith(p) || path.startsWith(p)
                || (request.getServletPath() != null && request.getServletPath().startsWith(p)));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!request.getMethod().equals(ROUTES.get(path))) { reject(response, 404, "NOT_FOUND"); return; }
        FubonMarketConfigState state = config.get();
        if (state == null) { reject(response, 503, "MISCONFIGURED"); return; }
        var access = state.snapshot();
        if (access.reason() != null) { reject(response, 503, access.reason()); return; }
        String token = request.getHeader("X-Internal-Service-Token");
        if (token == null || token.isBlank()) { reject(response, 401, "UNAUTHORIZED"); return; }
        if (request.getHeaders("X-Internal-Service-Token").asIterator().hasNext()) {
            var headers = request.getHeaders("X-Internal-Service-Token");
            headers.nextElement();
            if (headers.hasMoreElements()) { reject(response, 403, "FORBIDDEN"); return; }
        }
        if (!MessageDigest.isEqual(digest(access.token()), digest(token))) { reject(response, 403, "FORBIDDEN"); return; }
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null
                || request.getInputStream().read() != -1) { reject(response, 400, "INVALID_REQUEST"); return; }
        Set<String> allowed = "GET".equals(request.getMethod()) ? Set.of("symbol") : Set.of("dryRun");
        if (!allowed.containsAll(request.getParameterMap().keySet())
                || request.getParameterMap().values().stream().anyMatch(v -> v.length != 1)) {
            reject(response, 400, "INVALID_REQUEST"); return;
        }
        String dryRun = request.getParameter("dryRun");
        if (dryRun != null && !Set.of("true", "false").contains(dryRun)) {
            reject(response, 400, "INVALID_REQUEST"); return;
        }
        chain.doFilter(request, response);
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void reject(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"reason\":\"" + reason + "\"}");
    }
}
