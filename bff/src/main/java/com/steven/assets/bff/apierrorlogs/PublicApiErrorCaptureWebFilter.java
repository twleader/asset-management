package com.steven.assets.bff.apierrorlogs;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.time.*;

/**
 * Exact 14-route capture. A final 5xx is always recorded; a final 4xx is recorded only when this
 * request actually had a Throwable captured by an advice/resolver (i.e. it is not a controller
 * responding non-exceptionally with some 4xx). It never changes the public response.
 */
@Component
public class PublicApiErrorCaptureWebFilter implements WebFilter, Ordered {
    public static final String CAPTURE_ATTRIBUTE = PublicApiErrorCaptureWebFilter.class.getName() + ".capture";
    private static final String RECORDED_ATTRIBUTE = PublicApiErrorCaptureWebFilter.class.getName() + ".recorded";
    private final ApiErrorLogIngestClient ingest; private final ApiErrorLogDiagnosticRenderer renderer; private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public PublicApiErrorCaptureWebFilter(ApiErrorLogIngestClient ingest,ApiErrorLogDiagnosticRenderer renderer){this(ingest,renderer,Clock.systemUTC());}
    PublicApiErrorCaptureWebFilter(ApiErrorLogIngestClient ingest,ApiErrorLogDiagnosticRenderer renderer,Clock clock){this.ingest=ingest;this.renderer=renderer;this.clock=clock;}
    /** Exception advice fixes this pair at the producer boundary; the filter only consumes it after status is final. */
    public static void capture(ServerWebExchange exchange, Throwable throwable) {
        exchange.getAttributes().putIfAbsent(CAPTURE_ATTRIBUTE, new Capture(throwable, Instant.now()));
    }
    @Override public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain){
        OpenApiRouteCatalog.Operation op=OpenApiRouteCatalog.ROUTES.get(exchange.getRequest().getMethod().name()+" "+exchange.getRequest().getPath().value());
        if(op==null)return chain.filter(exchange);
        // An unhandled reactive error reaches this filter before WebFlux's exception resolver has
        // selected its final response status. Capture it now, but defer writing to response commit
        // so a caller error that the framework maps to 4xx remains a zero-row event.
        exchange.getResponse().beforeCommit(() -> { finalStatus(exchange, op); return Mono.empty(); });
        return chain.filter(exchange)
                .doOnSuccess(v -> finalStatus(exchange, op))
                .doOnError(error -> capture(exchange, error));
    }
    private void finalStatus(ServerWebExchange exchange, OpenApiRouteCatalog.Operation op) {
        HttpStatusCode status=exchange.getResponse().getStatusCode();
        if (status != null && status.is5xxServerError()) recordOnce(exchange, op, status.value());
        else if (status != null && status.is4xxClientError() && exchange.getAttributes().get(CAPTURE_ATTRIBUTE) != null) recordOnce(exchange, op, status.value());
        else exchange.getAttributes().remove(CAPTURE_ATTRIBUTE);
    }
    private void recordOnce(ServerWebExchange exchange, OpenApiRouteCatalog.Operation op, int httpStatus) {
        if (exchange.getAttributes().putIfAbsent(RECORDED_ATTRIBUTE, Boolean.TRUE) != null) return;
        Capture capture=(Capture) exchange.getAttributes().get(CAPTURE_ATTRIBUTE);
        Throwable failure=capture == null ? new IllegalStateException("Public API boundary failure operation="+op.key()+" status="+httpStatus) : capture.throwable();
        Instant occurredAt=capture == null ? clock.instant() : capture.occurredAt();
        ingest.ingest(op.key(),op.name(),renderer.message(failure),renderer.render(failure),occurredAt,httpStatus,null);
    }
    @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
    public record Capture(Throwable throwable, Instant occurredAt) {}
}
