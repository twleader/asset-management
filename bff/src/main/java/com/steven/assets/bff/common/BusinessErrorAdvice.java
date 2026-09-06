package com.steven.assets.bff.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import com.steven.assets.bff.apierrorlogs.ApiErrorLogDiagnosticRenderer;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

/**
 * 把 business 回的錯誤<b>原樣</b>傳到前端（Requirement 51 / Task 243.5.5）。
 *
 * <p><b>為什麼需要這一支：</b>BFF 的 controller 以 {@code WebClient.retrieve()} 呼叫 business，
 * 非 2xx 會擲 {@link WebClientResponseException}。在此之前 BFF <b>完全沒有錯誤轉譯</b>
 * （{@code @ControllerAdvice}／{@code ErrorWebExceptionHandler}／{@code onStatus} 在 bff 全樹皆為 0），
 * 於是 business 精心寫的可讀訊息到了使用者眼前會變成一個沒有內容的 500。
 *
 * <p>而前端 {@code api/index.js} 只讀 {@code data.detail}——business 的 {@code GlobalExceptionHandler}
 * 回的正是 {@code ProblemDetail}。故這裡把「狀態碼 ＋ 原始 body」照抄回去，兩個具體受害路徑是：
 * <ul>
 *   <li>設定 {@code PUT} 的 <b>403</b>「Google Drive 同步僅限主要管理者啟用」</li>
 *   <li>{@code browse-gdrive} 的 <b>503</b>「rclone remote 未設定／授權失效」——這條若變成無訊息錯誤，
 *       使用者只會看到一棵展不開的空樹，無從得知是授權問題</li>
 * </ul>
 *
 * <p>對 Spring Cloud Gateway 的 route（如 {@code TradingRadarBffRoutes}）不生效也不需要：
 * 那是直接 proxy，狀態碼與 body 本來就原樣傳回。
 */
@Slf4j
@RestControllerAdvice
public class BusinessErrorAdvice {
    private final ApiErrorLogDiagnosticRenderer diagnosticRenderer;
    /** Compatibility constructor for existing isolated advice tests. */
    public BusinessErrorAdvice() { this(new ApiErrorLogDiagnosticRenderer("")); }
    @Autowired
    public BusinessErrorAdvice(ApiErrorLogDiagnosticRenderer diagnosticRenderer) { this.diagnosticRenderer = diagnosticRenderer; }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<String> handleBusinessError(WebClientResponseException ex, ServerWebExchange exchange) {
        capture(exchange, ex);
        String body = ex.getResponseBodyAsString(StandardCharsets.UTF_8);
        // The caller's existing relay contract remains unchanged, but an upstream raw body may contain secrets.
        log.warn("business 回應 status={} error={} diagnostic={}", ex.getStatusCode(), ex.getClass().getSimpleName(), diagnosticRenderer.render(ex));

        MediaType contentType = ex.getHeaders().getContentType();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatusCode());
        if (contentType != null) {
            builder.contentType(contentType);
        } else if (!body.isEmpty()) {
            // business 的 GlobalExceptionHandler 一律回 ProblemDetail
            builder.contentType(MediaType.APPLICATION_PROBLEM_JSON);
        }
        // 只帶狀態碼與 body：business 的 Content-Length 等標頭不轉發（body 可能被重新編碼）
        return builder.body(body);
    }
}
