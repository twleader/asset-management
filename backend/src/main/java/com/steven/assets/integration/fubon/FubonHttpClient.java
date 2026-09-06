package com.steven.assets.integration.fubon;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import com.steven.assets.apierrorlog.ApiErrorLogOperationCatalog;
import com.steven.assets.apierrorlog.ApiErrorLogRecorder;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Typed adapter client. Error bodies and exception text are intentionally never logged. */
@Component
public class FubonHttpClient implements FubonBrokerClient {
    static final String TOKEN_HEADER = "X-Internal-Service-Token";
    // A 50-ETF batch includes full constituent lists and can exceed WebClient's 256 KiB default.
    // Keep a finite ETF-only bound; portfolio/quotes/trades retain their existing codec limits.
    static final int ETF_MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    private final WebClient client;
    private final WebClient etfClient;
    private final WebClient accountingClient;
    private final WebClient tradeClient;
    private final Clock clock;
    private final FubonConfigState configState;
    private final Duration timeout;
    private final ApiErrorLogRecorder errorLogRecorder;

    @Autowired
    public FubonHttpClient(
            @Value("${fubon.base-url:http://fubon-broker-service:8080}") String baseUrl,
            FubonConfigState configState,
            @Value("${fubon.timeout:30s}") Duration timeout,
            ApiErrorLogRecorder errorLogRecorder) {
        this(WebClient.builder().baseUrl(baseUrl).build(), configState, timeout, Clock.systemUTC(), errorLogRecorder);
    }

    FubonHttpClient(WebClient client, FubonConfigState configState, Duration timeout) {
        this(client, configState, timeout, Clock.systemUTC());
    }

    FubonHttpClient(WebClient client, FubonConfigState configState, Duration timeout, Clock clock) {
        this(client, configState, timeout, clock, null);
    }

    FubonHttpClient(WebClient client, FubonConfigState configState, Duration timeout, Clock clock, ApiErrorLogRecorder errorLogRecorder) {
        this.client = client;
        this.clock = clock;
        this.accountingClient = client.mutate()
                .codecs(codecs -> codecs.defaultCodecs().jackson2JsonDecoder(
                        new Jackson2JsonDecoder(FubonAccountingJson.mapper())))
                .build();
        this.tradeClient = client.mutate()
                .codecs(codecs -> codecs.defaultCodecs().jackson2JsonDecoder(
                        new Jackson2JsonDecoder(FubonTradeJson.mapper())))
                .build();
        this.etfClient = client.mutate()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(ETF_MAX_RESPONSE_BYTES))
                .build();
        this.configState = configState;
        this.timeout = timeout;
        this.errorLogRecorder = errorLogRecorder;
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
            if (response != null) return FubonDtos.CallResult.success(response);
            recordSynthetic("FUBON_PORTFOLIO_READ", "EMPTY_RESPONSE", "/internal/portfolio/read");
            return FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            record("FUBON_PORTFOLIO_READ", exception);
            return FubonDtos.CallResult.failure(httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            record("FUBON_PORTFOLIO_READ", exception);
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> readTwQuotes(List<String> codes) {
        if (invalidCodes(codes)) return FubonDtos.CallResult.failure("INVALID_REQUEST");
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
            if (response != null) return FubonDtos.CallResult.success(response);
            recordSynthetic("FUBON_TW_QUOTES_INVENTORY", "EMPTY_RESPONSE", "/internal/market-data/tw-quotes");
            return FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            record("FUBON_TW_QUOTES_INVENTORY", exception);
            return FubonDtos.CallResult.failure(httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            record("FUBON_TW_QUOTES_INVENTORY", exception);
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.TradeBatchResponse> readFilledTrades(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) return FubonDtos.CallResult.failure("INVALID_REQUEST");
        FubonConfigState.Snapshot config = configState.snapshot();
        FubonDtos.CallResult<FubonDtos.TradeBatchResponse> gate = gate(config);
        if (gate != null) return gate;
        try {
            FubonDtos.TradeBatchResponse response = tradeClient.post()
                    .uri("/internal/trades/read")
                    .header(TOKEN_HEADER, config.token())
                    .bodyValue(new FubonDtos.TradeReadRequest(start.toString(), end.toString()))
                    .retrieve()
                    .bodyToMono(FubonDtos.TradeBatchResponse.class)
                    .timeout(timeout)
                    .block();
            if (response != null) return FubonDtos.CallResult.success(response);
            recordSynthetic("FUBON_FILLED_TRADES_READ", "EMPTY_RESPONSE", "/internal/trades/read");
            return FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            record("FUBON_FILLED_TRADES_READ", exception);
            return FubonDtos.CallResult.failure(exception.getStatusCode().is2xxSuccessful()
                    ? "TRANSPORT_OR_SCHEMA_FAILURE" : httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            record("FUBON_FILLED_TRADES_READ", exception);
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.EtfHoldingsBatchResponse> readEtfHoldings(List<String> codes) {
        if (invalidCodes(codes)) return FubonDtos.CallResult.failure("INVALID_REQUEST");
        FubonConfigState.Snapshot config = configState.snapshot();
        FubonDtos.CallResult<FubonDtos.EtfHoldingsBatchResponse> gate = gate(config);
        if (gate != null) return gate;
        try {
            FubonDtos.EtfHoldingsBatchResponse response = etfClient.post()
                    .uri("/internal/market-data/etf-holdings")
                    .header(TOKEN_HEADER, config.token())
                    .bodyValue(new FubonDtos.EtfHoldingsReadRequest(List.copyOf(codes)))
                    .retrieve()
                    .bodyToMono(FubonDtos.EtfHoldingsBatchResponse.class)
                    .timeout(timeout)
                    .block();
            if (response != null) return FubonDtos.CallResult.success(response);
            recordSynthetic("FUBON_ETF_HOLDINGS_READ", "EMPTY_RESPONSE", "/internal/market-data/etf-holdings");
            return FubonDtos.CallResult.failure("EMPTY_RESPONSE");
        } catch (WebClientResponseException exception) {
            record("FUBON_ETF_HOLDINGS_READ", exception);
            return FubonDtos.CallResult.failure(exception.getStatusCode().is2xxSuccessful()
                    ? "TRANSPORT_OR_SCHEMA_FAILURE" : httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            record("FUBON_ETF_HOLDINGS_READ", exception);
            return FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.BankBalance> readBankBalance() {
        return accountingRead("/internal/bank-balance/read", FubonDtos.BankBalance.class,
                body -> FubonAccountingContract.validateBank(body, clock));
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.SettlementBatch> readSettlement() {
        return accountingRead("/internal/settlement/read", FubonDtos.SettlementBatch.class,
                body -> FubonAccountingContract.validateSettlement(body, clock));
    }

    @Override
    public FubonDtos.CallResult<FubonDtos.RealizedGainBatch> readRealizedGains() {
        return accountingRead("/internal/realized-gains/read", FubonDtos.RealizedGainBatch.class,
                body -> FubonAccountingContract.validateRealized(body, clock));
    }

    private <T> FubonDtos.CallResult<T> accountingRead(String path, Class<T> responseType, Consumer<T> validate) {
        FubonConfigState.Snapshot config = configState.snapshot();
        FubonDtos.CallResult<T> gate = gate(config);
        if (gate != null) return gate;
        try {
            // These exact adapter routes have no request body, including no empty object.
            T response = accountingClient.post().uri(path).header(TOKEN_HEADER, config.token())
                    .retrieve().bodyToMono(responseType).timeout(timeout).block();
            if (response == null) { recordSynthetic(operationFor(path), "EMPTY_RESPONSE", path); return FubonDtos.CallResult.failure("EMPTY_RESPONSE"); }
            validate.accept(response);
            return FubonDtos.CallResult.success(response);
        } catch (FubonAccountingContract.Rejected exception) {
            record(operationFor(path), exception);
            return FubonDtos.CallResult.failure(exception.getMessage());
        } catch (WebClientResponseException exception) {
            record(operationFor(path), exception);
            return FubonDtos.CallResult.failure(exception.getStatusCode().is2xxSuccessful()
                    ? "TRANSPORT_OR_SCHEMA_FAILURE" : httpReason(exception.getStatusCode()));
        } catch (Exception exception) {
            record(operationFor(path), exception);
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

    private static boolean invalidCodes(List<String> codes) {
        return codes == null || codes.stream().anyMatch(Objects::isNull);
    }

    private String httpReason(HttpStatusCode status) {
        if (status.value() == 401 || status.value() == 403) return "ADAPTER_AUTH_REJECTED";
        if (status.is4xxClientError()) return "ADAPTER_4XX";
        if (status.is5xxServerError()) return "ADAPTER_5XX";
        return "ADAPTER_HTTP_FAILURE";
    }

    private String operationFor(String path) {
        return switch (path) { case "/internal/bank-balance/read" -> "FUBON_BANK_BALANCE_READ"; case "/internal/settlement/read" -> "FUBON_SETTLEMENT_READ"; case "/internal/realized-gains/read" -> "FUBON_REALIZED_GAINS_READ"; default -> throw new IllegalArgumentException("unknown Fubon accounting path"); };
    }
    private void record(String key, Throwable failure) {
        if (errorLogRecorder == null) return;
        ApiErrorLogOperationCatalog.Operation operation = ApiErrorLogOperationCatalog.OPERATIONS.stream().filter(o -> o.operationKey().equals(key)).findFirst().orElseThrow();
        errorLogRecorder.record(failure, ApiErrorLogOperationCatalog.FUBON_API, key, operation.apiName(), clock.instant());
    }
    private void recordSynthetic(String key, String reason, String path) {
        record(key, new IllegalStateException("Fubon broker invocation failed path=" + path + " reason=" + reason));
    }
}
