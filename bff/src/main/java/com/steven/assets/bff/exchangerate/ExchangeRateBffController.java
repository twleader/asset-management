package com.steven.assets.bff.exchangerate;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ExchangeRateView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/exchange-rate")
@RequiredArgsConstructor
public class ExchangeRateBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/exchange-rate?currency=USD
     * 先 trigger refresh 取得最新；再回傳近 10 年的歷史。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getHistory(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "USD") String currency) {
        Mono<Void> refresh = businessServicesClient.post()
                .uri(uri -> uri.path("/api/market-data/exchange-rate/refresh")
                        .queryParam("currency", currency).build())
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty());

        String today = LocalDate.now().toString();
        String tenYearsAgo = LocalDate.now().minusYears(10).toString();

        return refresh.then(businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-data/exchange-rate")
                        .queryParam("currency", currency)
                        .queryParam("start", tenYearsAgo)
                        .queryParam("end", today).build())
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(rates -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("rates", rates);
                    return ResponseEntity.ok(body);
                }));
    }

    @PostMapping("/backfill")
    public Mono<ResponseEntity<Map<String, Object>>> backfill(
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "USD") String currency,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String since) {
        return businessServicesClient.post()
                .uri(uri -> {
                    var u = uri.path("/api/market-data/exchange-rate/backfill-history")
                            .queryParam("currency", currency);
                    if (since != null) u.queryParam("since", since);
                    return u.build();
                })
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .map(ResponseEntity::ok);
    }
}
