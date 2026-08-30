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

/**
 * Exact-path, constant-time service-token boundary for the bank balance sync endpoint
 * (Requirement 128 / Task 393).
 *
 * <p><b>Deliberately independent from {@link FubonInternalTokenFilter} and
 * {@link FubonTradeInternalTokenFilter}.</b> Both existing filters hard-code their own exact path
 * in {@code shouldNotFilter} and return {@code true} (i.e. "do not protect this request") for
 * every other path, including this endpoint's -- reusing either would leave
 * {@code /internal/brokers/fubon/bank-balance-sync} completely unauthenticated. This filter
 * mirrors their structure exactly; only the {@link #PATH} and the {@link FubonBankBalanceOutcome}
 * / {@link FubonBankBalanceOutcomeCounters} response shape differ.
 */
@Component
public class FubonBankBalanceInternalTokenFilter extends OncePerRequestFilter {
    static final String PATH = "/internal/brokers/fubon/bank-balance-sync";

    private final Optional<FubonConfigState> configState;
    private final Optional<FubonBankBalanceOutcomeCounters> counters;
    private final ObjectMapper objectMapper;

    @Autowired
    public FubonBankBalanceInternalTokenFilter(
            Optional<FubonConfigState> configState,
            Optional<FubonBankBalanceOutcomeCounters> counters,
            ObjectMapper objectMapper) {
        this.configState = configState;
        this.counters = counters;
        this.objectMapper = objectMapper;
    }

    FubonBankBalanceInternalTokenFilter(
            FubonConfigState configState,
            FubonBankBalanceOutcomeCounters counters,
            ObjectMapper objectMapper) {
        this(Optional.of(configState), Optional.of(counters), objectMapper);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !FubonAccountingSyncRequest.targetsEndpoint(request, PATH);
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
        if (!FubonAccountingSyncRequest.hasValidParameters(request)) {
            writeAuthError(response, HttpServletResponse.SC_BAD_REQUEST, "INVALID_REQUEST");
            return;
        }
        if (configState.isEmpty() || counters.isEmpty()) {
            writeAuthError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MISCONFIGURED");
            return;
        }
        FubonConfigState.Snapshot config = configState.orElseThrow().snapshot();
        if (config.state() == FubonConfigState.State.DISABLED) {
            writeUnavailable(request, response, FubonBankBalanceOutcome.DISABLED, "DISABLED");
            return;
        }
        if (config.state() != FubonConfigState.State.READY || config.token() == null) {
            writeUnavailable(request, response, FubonBankBalanceOutcome.MISCONFIGURED, "MISCONFIGURED");
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
            FubonBankBalanceOutcome outcome,
            String reason)
            throws IOException {
        FubonBankBalanceOutcomeCounters outcomeCounters = counters.orElseThrow();
        outcomeCounters.increment(outcome);
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new FubonBankBalanceSyncService.SyncResult(
                outcome, FubonAccountingSyncRequest.dryRun(request), reason, null));
    }

    private void writeAuthError(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), java.util.Map.of("error", reason));
    }

}
