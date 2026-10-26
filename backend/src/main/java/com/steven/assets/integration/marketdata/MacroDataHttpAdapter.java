package com.steven.assets.integration.marketdata;

import com.steven.assets.model.marketdata.MacroSourceData.DailyOhlcDto;
import com.steven.assets.model.marketdata.MacroSourceData.TwseReturnIndexDto;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.steven.assets.model.marketdata.IndexIntradayPoint;
import com.steven.assets.service.port.MacroDataPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

@Component
public class MacroDataHttpAdapter implements MacroDataPort {
    private final WebClient client;
    public MacroDataHttpAdapter(@Value("${external-materials.base-url:http://external-materials-service:8080}") String externalUrl) {
        this.client = WebClient.builder().baseUrl(externalUrl).exchangeStrategies(
                ExchangeStrategies.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build()).build();
    }
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, BigDecimal> imf(String indicator, String country, int scale) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/imf")
                            .queryParam("indicator", indicator)
                            .queryParam("country", country)
                            .queryParam("scale", scale).build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, BigDecimal>>() {})
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, BigDecimal>> dgbas() {
        return client.get()
                    .uri("/internal/macro/dgbas")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Map<String, BigDecimal>>>() {})
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public DailyOhlcDto[] twseMonthly(YearMonth ym) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/twse-monthly")
                            .queryParam("year", ym.getYear())
                            .queryParam("month", ym.getMonthValue()).build())
                    .retrieve()
                    .bodyToMono(DailyOhlcDto[].class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public TwseReturnIndexDto twseReturnIndex(LocalDate date) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/twse-return-index")
                            .queryParam("date", date.toString()).build())
                    .retrieve()
                    .bodyToMono(TwseReturnIndexDto.class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public DailyOhlcDto[] usIndex(String code) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/us-index")
                            .queryParam("code", code).build())
                    .retrieve()
                    .bodyToMono(DailyOhlcDto[].class)
                    .block();
    }

    @Override
    @SuppressWarnings("unchecked")
    public IndexIntradayPoint[] indexIntraday(String market) {
        return client.get()
                    .uri(uriBuilder -> uriBuilder.path("/internal/macro/index-intraday")
                            .queryParam("market", market).build())
                    .retrieve()
                    .bodyToMono(IndexIntradayPoint[].class)
                    .block();
    }
}
