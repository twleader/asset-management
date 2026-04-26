package com.steven.assets.bff.tradingcalendar;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TradingCalendarView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/trading-calendar")
@RequiredArgsConstructor
public class TradingCalendarBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/trading-calendar?year=YYYY
     * 一次回傳：休市日列表 + 即時市場開盤狀態。
     */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(required = false) Integer year) {
        int targetYear = year != null ? year : LocalDate.now().getYear();
        return Mono.zip(
                businessServicesClient.get()
                        .uri(uri -> uri.path("/api/market-data/holidays")
                                .queryParam("year", targetYear).build())
                        .retrieve().bodyToMono(LIST_MAP).onErrorReturn(Collections.emptyList()),
                businessServicesClient.get()
                        .uri("/api/market-data/market-status")
                        .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
        ).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("holidays", t.getT1());
            body.put("marketStatus", t.getT2());
            body.put("year", targetYear);
            return ResponseEntity.ok(body);
        });
    }

    @GetMapping("/market-status")
    public Mono<ResponseEntity<Map<String, Object>>> marketStatus() {
        return businessServicesClient.get()
                .uri("/api/market-data/market-status")
                .retrieve().bodyToMono(MAP).onErrorReturn(Collections.emptyMap())
                .map(ResponseEntity::ok);
    }
}
