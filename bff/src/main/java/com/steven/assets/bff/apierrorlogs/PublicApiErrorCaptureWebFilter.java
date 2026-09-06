package com.steven.assets.bff.apierrorlogs;

import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import java.time.*;
import java.util.*;

/** Exact 13-route capture. It observes final 5xx only and cannot change the public response. */
@Component
public class PublicApiErrorCaptureWebFilter implements WebFilter, Ordered {
    private static final Map<String, Operation> ROUTES=Map.ofEntries(
      Map.entry("GET /api/quotes",new Operation("OPEN_QUOTES_LIST","即時報價清單")),Map.entry("GET /api/quotes/one",new Operation("OPEN_QUOTES_ONE","單一即時報價")),Map.entry("GET /api/public/market-index",new Operation("OPEN_MARKET_INDEX","大盤指數")),Map.entry("GET /api/assets/latest",new Operation("OPEN_LATEST_ASSETS","最新資產")),Map.entry("GET /api/public/exchange-rate/usd-twd",new Operation("OPEN_USD_TWD","美元兌台幣")),Map.entry("GET /api/public/market-analysis/today",new Operation("OPEN_MARKET_ANALYSIS_TODAY","今日市場分析")),Map.entry("GET /api/public/portfolio-advice/latest",new Operation("OPEN_PORTFOLIO_ADVICE_LATEST","最新資產配置建議")),Map.entry("GET /api/public/trading-radar/today",new Operation("OPEN_TRADING_RADAR_TODAY","今日交易雷達")),Map.entry("GET /api/public/trading-radar/stock",new Operation("OPEN_TRADING_RADAR_STOCK","單一標的交易雷達")),Map.entry("GET /api/public/transactions",new Operation("OPEN_TRANSACTIONS","公開交易紀錄")),Map.entry("GET /api/public/trading-calendar",new Operation("OPEN_TRADING_CALENDAR","交易日曆")),Map.entry("GET /api/public/commodity-prices",new Operation("OPEN_COMMODITY_PRICES","油價金價")),Map.entry("POST /api/public/crawler-data/rescan",new Operation("OPEN_CRAWLER_RESCAN","公開爬蟲重新掃描")));
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
        Operation op=ROUTES.get(exchange.getRequest().getMethod().name()+" "+exchange.getRequest().getPath().value());
        if(op==null)return chain.filter(exchange);
        // An unhandled reactive error reaches this filter before WebFlux's exception resolver has
        // selected its final response status. Capture it now, but defer writing to response commit
        // so a caller error that the framework maps to 4xx remains a zero-row event.
        exchange.getResponse().beforeCommit(() -> { finalStatus(exchange, op); return Mono.empty(); });
        return chain.filter(exchange)
                .doOnSuccess(v -> finalStatus(exchange, op))
                .doOnError(error -> capture(exchange, error));
    }
    private void finalStatus(ServerWebExchange exchange, Operation op) {
        if (exchange.getResponse().getStatusCode() != null && exchange.getResponse().getStatusCode().is5xxServerError()) recordOnce(exchange, op);
        else exchange.getAttributes().remove(CAPTURE_ATTRIBUTE);
    }
    private void recordOnce(ServerWebExchange exchange, Operation op) {
        if (exchange.getAttributes().putIfAbsent(RECORDED_ATTRIBUTE, Boolean.TRUE) != null) return;
        Capture capture=(Capture) exchange.getAttributes().get(CAPTURE_ATTRIBUTE);
        Throwable failure=capture == null ? new IllegalStateException("Public API boundary failure operation="+op.key+" status=5xx") : capture.throwable();
        Instant occurredAt=capture == null ? clock.instant() : capture.occurredAt();
        ingest.ingest(op.key,op.name,renderer.message(failure),renderer.render(failure),occurredAt);
    }
    @Override public int getOrder(){return Ordered.LOWEST_PRECEDENCE;}
    public record Capture(Throwable throwable, Instant occurredAt) {}
    private record Operation(String key,String name){}
}
