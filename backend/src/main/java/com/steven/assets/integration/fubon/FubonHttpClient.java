package com.steven.assets.integration.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/** Typed adapter client. Error bodies and exception text are intentionally never logged. */
@Component
public class FubonHttpClient implements FubonBrokerClient {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";

    private final WebClient client;
    private final FubonConfigState configState;
    private final Duration timeout;

    @Autowired
    public FubonHttpClient(
            @Value("${fubon.base-url:http://fubon-broker-service:8080}") String baseUrl,
            FubonConfigState configState,
            @Value("${fubon.timeout:30s}") Duration timeout) {
        this(WebClient.builder().baseUrl(baseUrl).build(), configState, timeout);
    }

    FubonHttpClient(WebClient client, FubonConfigState configState, Duration timeout) {
        this.client = client;
        this.configState = configState;
        this.timeout = timeout;
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.PortfolioResponse> readPortfolio() {
        FubonConfigState.Snapshot config = configState.snapshot();
        FubonDtos.CallResult<FubonDtos.PortfolioResponse> gate = gate(config);
        if (gate != null) return gate;
        try {
            FubonDtos.PortfolioResponse response = client.post()
                    .uri("/internal/portfolio/read")
                    .header(TOKEN_HEADER, config.token())
                    .bodyValue(new FubonDtos.PortfolioReadRequest(true))
                    .retrieve()
                    .bodyToMono(FubonDtos.PortfolioResponse.class)
                    .timeout(timeout)
                    .block();
            return response != null
                    ? FubonDtos.CallResult.success(response)
                    : FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            return FubonDtos.CallResult.failure(httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> readTwQuotes(List<String> codes) {
        FubonConfigState.Snapshot config = configState.snapshot();
        FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> gate = gate(config);
        if (gate != null) return gate;
        try {
            FubonDtos.QuoteBatchResponse response = client.post()
                    .uri("/internal/market-data/tw-quotes")
                    .header(TOKEN_HEADER, config.token())
                    .bodyValue(new FubonDtos.QuoteReadRequest(List.copyOf(codes), "INVENTORY"))
                    .retrieve()
                    .bodyToMono(FubonDtos.QuoteBatchResponse.class)
                    .timeout(timeout)
                    .block();
            return response != null
                    ? FubonDtos.CallResult.success(response)
                    : FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            return FubonDtos.CallResult.failure(httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.TradeBatchResponse> readFilledTrades(LocalDate start, LocalDate end) {
        FubonConfigState.Snapshot config = configState.snapshot();
        FubonDtos.CallResult<FubonDtos.TradeBatchResponse> gate = gate(config);
        if (gate != null) return gate;
        try {
            FubonDtos.TradeBatchResponse response = client.post()
                    .uri("/internal/trades/read")
                    .header(TOKEN_HEADER, config.token())
                    .bodyValue(new FubonDtos.TradeReadRequest(start.toString(), end.toString()))
                    .retrieve()
                    .bodyToMono(FubonDtos.TradeBatchResponse.class)
                    .timeout(timeout)
                    .block();
            return response != null
                    ? FubonDtos.CallResult.success(response)
                    : FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            return FubonDtos.CallResult.failure(httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    private <T> FubonDtos.CallResult<T> gate(FubonConfigState.Snapshot config) {
        return switch (config.state()) {
            case DISABLED -> FubonDtos.CallResult.failure("DISABLED");
            case MISCONFIGURED -> FubonDtos.CallResult.failure("MISCONFIGURED");
            case READY -> null;
        };
    }

    private String httpReason(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) return "ADAPTER_AUTH_REJECTED";
        if (status.is4xxClientError()) return "ADAPTER_4XX";
        if (status.is5xxServerError()) return "ADAPTER_5XX";
        return "ADAPTER_HTTP_FAILURE";
    }
}
