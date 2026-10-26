package com.steven.assets.integration.marketdata;

import com.steven.assets.model.marketdata.HistoricalSourceData.IntradayBarDto;
import com.steven.assets.model.marketdata.HistoricalSourceData.IntradaySourceSessionDto;
import com.steven.assets.model.marketdata.HistoricalSourceData.LiveRefreshDto;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.steven.assets.model.marketdata.IndexIntradayPoint;
import com.steven.assets.service.port.HistoricalDataPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

@Component
public class HistoricalDataHttpAdapter implements HistoricalDataPort {
    private final WebClient client;
    public HistoricalDataHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).build();
    }
    @Override
    @SuppressWarnings("unchecked")
    public IntradaySourceSessionDto intradaySession(String stockCode, String market, LocalDate date, boolean readOnly) {
        return client.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/internal/intraday-ticks")
                                .queryParam("code", stockCode)
                                .queryParam("market", market);
                        if (date != null) uriBuilder.queryParam("date", date.toString());
                        if (readOnly) uriBuilder.queryParam("readOnly", true);
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(IntradaySourceSessionDto.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public IntradayBarDto[] intraday5m(String stockCode, String market, int daysBack) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/intraday-5m")
                            .queryParam("code", stockCode)
                            .queryParam("market", market)
                            .queryParam("daysBack", daysBack).build())
                    .retrieve()
                    .bodyToMono(IntradayBarDto[].class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillStock(String stockCode, String market, LocalDate since, LocalDate until) {
        return client.post()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/internal/backfill/stock")
                                .queryParam("code", stockCode)
                                .queryParam("market", market)
                                .queryParam("since", since.toString());
                        if (until != null) uriBuilder.queryParam("until", until.toString());
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillAll() {
        return client.post()
                    .uri("/internal/backfill/all")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillExchangeRate(String currency, LocalDate since) {
        return client.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/backfill/exchange-rate")
                            .queryParam("currency", currency)
                            .queryParam("since", since.toString())
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillExchangeRateFrom(String currency, LocalDate since) {
        return client.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/backfill/exchange-rate-from")
                            .queryParam("currency", currency)
                            .queryParam("since", since.toString())
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillCommodity(String code, LocalDate since) {
        return client.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/backfill/commodity")
                            .queryParam("code", code)
                            .queryParam("since", since.toString())
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> backfillCommodityFrom(String code, LocalDate since) {
        return client.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/backfill/commodity-from")
                            .queryParam("code", code)
                            .queryParam("since", since.toString())
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void refreshExchangeRateBot(String currency) {
        client.post()
                    .uri(uriBuilder -> uriBuilder.path("/internal/exchange-rate/refresh-bot")
                            .queryParam("currency", currency).build())
                    .retrieve()
                    .toBodilessEntity()
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public LiveRefreshDto refreshCommodities() {
        return client.post()
                    .uri("/internal/commodity/live-refresh")
                    .retrieve()
                    .bodyToMono(LiveRefreshDto.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> stockName(String code, String market) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/stock-name")
                            .queryParam("code", code).queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .map(m -> {
                        @SuppressWarnings("unchecked")
                        Map<String, String> r = (Map<String, String>) m;
                        return r;
                    })
                    .block();
    }
}
