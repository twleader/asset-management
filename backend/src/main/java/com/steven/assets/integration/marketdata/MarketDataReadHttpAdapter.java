package com.steven.assets.integration.marketdata;

import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.model.marketdata.MarketDataResults.DividendRateResult;
import com.steven.assets.model.marketdata.MarketDataResults.EtfHoldingsResult;
import com.steven.assets.model.marketdata.MarketDataResults.DividendHistoryResult;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.steven.assets.service.port.MarketDataReadPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

@Component
public class MarketDataReadHttpAdapter implements MarketDataReadPort {
    private final WebClient client;
    public MarketDataReadHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).build();
    }
    @Override
    @SuppressWarnings("unchecked")
    public QuoteDetailDto.Response quoteDetail(String stockCode, String market) {
        return client.get().uri(uriBuilder -> uriBuilder.path("/internal/quote-detail")
                            .queryParam("code", stockCode).queryParam("market", market).build())
                    .retrieve().bodyToMono(QuoteDetailDto.Response.class).timeout(Duration.ofSeconds(2)).block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public DividendRateResult dividendRate(String stockCode, String market) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/dividend-rate")
                            .queryParam("code", stockCode).queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(DividendRateResult.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public EtfHoldingsResult etfHoldings(String stockCode, String market) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/etf-holdings")
                            .queryParam("code", stockCode).queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(EtfHoldingsResult.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public DividendHistoryResult dividendHistory(String stockCode, String market, int years) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/dividend-history")
                            .queryParam("code", stockCode).queryParam("market", market)
                            .queryParam("years", years).build())
                    .retrieve()
                    .bodyToMono(DividendHistoryResult.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Boolean detectTwClosure() {
        return client.post()
                    .uri("/internal/tw-closure/detect")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .map(m -> Boolean.TRUE.equals(m.get("closedToday")))
                    .block(Duration.ofSeconds(20));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, String> twHolidays(int year) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/tw-holidays")
                            .queryParam("year", year).build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() {})
                    .block();
    }
}
