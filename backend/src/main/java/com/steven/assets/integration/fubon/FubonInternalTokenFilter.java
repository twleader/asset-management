package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/** Exact-path, constant-time service-token boundary for the inventory sync endpoint. */
@Component
public class FubonInternalTokenFilter extends OncePerRequestFilter {
    static final String PATH = "/internal/brokers/fubon/inventory-sync";

    private final Optional<FubonConfigState> configState;
    private final Optional<FubonOutcomeCounters> counters;
    private final ObjectMapper objectMapper;

    @Autowired
    public FubonInternalTokenFilter(
            Optional<FubonConfigState> configState,
            Optional<FubonOutcomeCounters> counters,
            ObjectMapper objectMapper) {
        this.configState = configState;
        this.counters = counters;
        this.objectMapper = objectMapper;
    }

    FubonInternalTokenFilter(
            FubonConfigState configState,
            FubonOutcomeCounters counters,
            ObjectMapper objectMapper) {
        this(Optional.of(configState), Optional.of(counters), objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !PATH.equals(uri) && !PATH.equals(uri.replaceAll(";[^/]*", ""));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!PATH.equals(request.getRequestURI())) {
            writeAuthError(response, HttpServletResponse.SC_NOT_FOUND, "NOT_FOUND");
            return;
        }
        if (configState.isEmpty() || counters.isEmpty()) {
            writeAuthError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MISCONFIGURED");
            return;
        }
        FubonConfigState.Snapshot config = configState.orElseThrow().snapshot();
        if (config.state() == FubonConfigState.State.DISABLED) {
            writeUnavailable(request, response, FubonOutcome.DISABLED, "DISABLED");
            return;
        }
        if (config.state() != FubonConfigState.State.READY || config.token() == null) {
            writeUnavailable(request, response, FubonOutcome.MISCONFIGURED, "MISCONFIGURED");
            return;
        }

        String provided = request.getHeader(FubonHttpClient.TOKEN_HEADER);
        if (provided == null || provided.isEmpty()
                || java.util.Collections.list(request.getHeaders(FubonHttpClient.TOKEN_HEADER)).size() != 1) {
            writeAuthError(response, HttpServletResponse.SC_UNAUTHORIZED, "TOKEN_REQUIRED");
            return;
        }
        if (!constantTimeEquals(config.token(), provided)) {
            writeAuthError(response, HttpServletResponse.SC_FORBIDDEN, "TOKEN_INVALID");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String provided) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] expectedDigest = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] providedDigest = digest.digest(provided.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expectedDigest, providedDigest);
        } catch (Exception impossible) {
            return false;
        }
    }

    private void writeUnavailable(
            HttpServletRequest request,
            HttpServletResponse response,
            FubonOutcome outcome,
            String reason)
            throws IOException {
        FubonOutcomeCounters outcomeCounters = counters.orElseThrow();
        outcomeCounters.increment(outcome);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new FubonDtos.SyncResponse(
                outcome, requestDryRun(request), null, 0, 0, null, reason, outcomeCounters.snapshot()));
    }

    private void writeAuthError(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), java.util.Map.of("error", reason));
    }

    private boolean requestDryRun(HttpServletRequest request) {
        return !"false".equalsIgnoreCase(request.getParameter("dryRun"));
    }
}
