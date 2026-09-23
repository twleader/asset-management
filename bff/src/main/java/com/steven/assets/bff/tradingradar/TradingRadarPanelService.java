package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.tradingradar.dto.TradingRadarPanelResponse;
import com.steven.assets.bff.tradingradar.dto.TradingRadarRefreshJobResponse;
import com.steven.assets.bff.tradingradar.dto.TradingRadarStockEvaluationResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** One exact business call per browser method, using the existing tenant-aware client. */
@Service
public class TradingRadarPanelService {
    private static final Duration PANEL_BUDGET = Duration.ofSeconds(20);
    private static final Duration JOB_BUDGET = Duration.ofSeconds(5);
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9.\\-]{1,12}$");
    private static final Pattern MARKET = Pattern.compile("^[\\p{L}0-9]{1,10}$");
    private static final Set<Integer> PANEL_ERRORS = Set.of(401, 403, 404);
    private static final Set<Integer> EVALUATION_ERRORS = Set.of(400, 401, 403, 404);
    private static final Set<Integer> JOB_ERRORS = Set.of(400, 401, 403, 404, 503);
    private final WebClient businessServicesClient;
    private final Duration panelBudget;
    private final Duration jobBudget;

    @Autowired
    public TradingRadarPanelService(@Qualifier("businessServicesClient") WebClient businessServicesClient) {
        this(businessServicesClient, PANEL_BUDGET, JOB_BUDGET);
    }

    TradingRadarPanelService(WebClient client, Duration panelBudget, Duration jobBudget) {
        this.businessServicesClient = client;
        this.panelBudget = panelBudget;
        this.jobBudget = jobBudget;
    }

    public Mono<TradingRadarPanelResponse> twMarket() { return panel("tw-market", "台股"); }
    public Mono<TradingRadarPanelResponse> usMarket() { return panel("us-market", "美股"); }
    public Mono<TradingRadarPanelResponse> twStocks() { return panel("tw-stocks", "台股"); }
    public Mono<TradingRadarPanelResponse> usStocks() { return panel("us-stocks", "美股"); }
    public Mono<TradingRadarPanelResponse> publicInformation() { return panel("public-information", null); }

    private Mono<TradingRadarPanelResponse> panel(String name, String market) {
        return complete(read(businessServicesClient.get().uri("/api/trading-radar/panels/" + name))
                .map(body -> TradingRadarPayloadValidator.panel(body, name, market)), panelBudget, PANEL_ERRORS);
    }

    public Mono<TradingRadarStockEvaluationResponse> stockEvaluation(String stockCode, String market) {
        return Mono.defer(() -> {
            String code = selector(stockCode, CODE, "股票代號格式不合法").toUpperCase(Locale.ROOT);
            String selectedMarket = selector(market, MARKET, "市場別格式不合法");
            return complete(read(businessServicesClient.get().uri(uri -> uri
                            .path("/api/trading-radar/stock-evaluation")
                            .queryParam("stockCode", code).queryParam("market", selectedMarket).build()))
                    .map(body -> TradingRadarPayloadValidator.evaluation(body, code, selectedMarket)),
                    panelBudget, EVALUATION_ERRORS);
        });
    }

    public Mono<TradingRadarRefreshJobResponse> startRefreshJob() {
        // Deliberately no retry: an uncertain POST result must never create another provider job.
        return complete(read(businessServicesClient.post().uri("/api/trading-radar/refresh-jobs"))
                .map(body -> TradingRadarPayloadValidator.job(body, null)), jobBudget, JOB_ERRORS);
    }

    public Mono<TradingRadarRefreshJobResponse> refreshJob(String jobId) {
        return Mono.defer(() -> {
            String id = validJobId(jobId);
            return complete(read(businessServicesClient.get().uri("/api/trading-radar/refresh-jobs/{id}", id))
                    .map(body -> TradingRadarPayloadValidator.job(body, id)), jobBudget, JOB_ERRORS);
        });
    }

    private Mono<JsonNode> read(WebClient.RequestHeadersSpec<?> request) {
        // Parse decimals explicitly instead of letting the default JsonNode decoder turn them into doubles.
        return request.retrieve().bodyToMono(String.class)
                .switchIfEmpty(Mono.error(TradingRadarPayloadValidator.invalid()))
                .map(TradingRadarPayloadValidator::parse);
    }

    private <T> Mono<T> complete(Mono<T> source, Duration budget, Set<Integer> preservedErrors) {
        return source.timeout(budget).onErrorMap(error -> mapFailure(error, preservedErrors));
    }

    private static Throwable mapFailure(Throwable error, Set<Integer> preservedErrors) {
        int status = status(error);
        if (preservedErrors.contains(status)) return error;
        if (error instanceof ResponseStatusException && (status == 502 || status == 504)) return error;
        if (status == 408 || status == 504) return timedOut(error);
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof java.net.SocketTimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException
                    || cause instanceof io.netty.channel.ConnectTimeoutException) return timedOut(error);
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "交易雷達資料來源暫時無法使用", error);
    }

    private static int status(Throwable error) {
        return error instanceof WebClientResponseException response ? response.getStatusCode().value()
                : error instanceof ResponseStatusException response ? response.getStatusCode().value() : 0;
    }

    private static ResponseStatusException timedOut(Throwable cause) {
        return new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "交易雷達資料載入逾時", cause);
    }

    private static String selector(String raw, Pattern pattern, String reason) {
        if (raw == null || !pattern.matcher(raw.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
        }
        return raw.trim();
    }

    private static String validJobId(String raw) {
        try {
            String canonical = UUID.fromString(raw).toString();
            if (!canonical.equalsIgnoreCase(raw)) throw new IllegalArgumentException();
            return canonical;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更新工作編號格式不合法");
        }
    }
}
