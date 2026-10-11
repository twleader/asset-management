package com.steven.assets.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.aiflow.AiFlowCredentialService;
import com.steven.assets.service.aiflow.AiFlowProblem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import jakarta.servlet.http.*;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/** 在request body解析及任何owner資料存取前拒絕未授權觀測請求。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AiFlowProducerFilter extends OncePerRequestFilter {
    private static final String PATH = "/api/srpp/ai-flow/events";
    private final Supplier<AiFlowCredentialService> credentials;
    private final ObjectMapper json = new ObjectMapper();
    @Autowired
    public AiFlowProducerFilter(ObjectProvider<AiFlowCredentialService> credentials) {
        this.credentials = () -> {
            AiFlowCredentialService available = credentials.getIfAvailable();
            if (available == null) throw new AiFlowProblem("CONFIGURATION_UNAVAILABLE");
            return available;
        };
    }
    public AiFlowProducerFilter(AiFlowCredentialService credentials) { this.credentials = () -> credentials; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        // MVC可能忽略任何segment的matrix參數；非canonical raw URI仍須在授權前404。
        String path;
        try { path = UriUtils.decode(request.getRequestURI(), StandardCharsets.UTF_8).replaceAll(";[^/]*", ""); }
        catch (IllegalArgumentException ignored) { path = request.getRequestURI(); }
        return !path.equals(PATH) && !path.startsWith(PATH + "/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body;
        try {
            if (!request.getRequestURI().equals(PATH)) throw new AiFlowProblem("NOT_FOUND");
            if (!request.getMethod().equals("POST")) throw new AiFlowProblem("METHOD_NOT_ALLOWED");
            var tokens = Collections.list(request.getHeaders("X-AI-Flow-Token"));
            if (tokens.size() != 1 || tokens.getFirst().isBlank()) throw new AiFlowProblem("TOKEN_REQUIRED");
            credentials.get().authenticate(tokens);
            if (request.getContentLengthLong() > 256 * 1024) throw new AiFlowProblem("PAYLOAD_TOO_LARGE");
            body = request.getInputStream().readNBytes(256 * 1024 + 1);
            if (body.length > 256 * 1024) throw new AiFlowProblem("PAYLOAD_TOO_LARGE");
        } catch (AiFlowProblem failure) {
            response.setStatus(failure.status()); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), failure.body(PATH));
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }
    /** chunked request 也只讀256KiB+1，避免認證後才發現無界body。 */
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return source.read(); }
                @Override public int read(byte[] bytes, int off, int len) { return source.read(bytes, off, len); }
                @Override public boolean isFinished() { return source.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("同步觀測入口"); }
            };
        }
    }
}
