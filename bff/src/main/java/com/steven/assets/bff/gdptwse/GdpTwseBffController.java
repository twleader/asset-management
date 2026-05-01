package com.steven.assets.bff.gdptwse;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GdpTwseView 專屬 BFF（Requirement 18）。
 * 一次回傳近 N 年（預設 30）對齊好的 years / gdpPerCapitaUsd / twseYearEndClose。
 */
@RestController
@RequestMapping("/api/bff/gdp-twse")
@RequiredArgsConstructor
public class GdpTwseBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(defaultValue = "30") int years) {
        int currentYear = LocalDate.now().getYear();
        int since = currentYear - years + 1;

        Mono<List<Map<String, Object>>> gdp = businessServicesClient.get()
                .uri(uri -> uri.path("/api/taiwan-gdp").queryParam("since", since).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<List<Map<String, Object>>> twse = businessServicesClient.get()
                .uri(uri -> uri.path("/api/twse-year-end-index").queryParam("since", since).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        return Mono.zip(gdp, twse).map(tuple -> {
            TreeMap<Integer, Object> gdpByYear = new TreeMap<>();
            TreeMap<Integer, Object> twseByYear = new TreeMap<>();
            for (Map<String, Object> row : tuple.getT1()) {
                gdpByYear.put(((Number) row.get("year")).intValue(), row.get("gdpUsd"));
            }
            for (Map<String, Object> row : tuple.getT2()) {
                twseByYear.put(((Number) row.get("year")).intValue(), row.get("closePoint"));
            }
            // 以兩個 series 的年份聯集為 X 軸
            TreeMap<Integer, Boolean> allYears = new TreeMap<>();
            gdpByYear.keySet().forEach(y -> allYears.put(y, true));
            twseByYear.keySet().forEach(y -> allYears.put(y, true));

            List<Integer> yearsList = new ArrayList<>(allYears.keySet());
            List<Object> gdpList = new ArrayList<>();
            List<Object> twseList = new ArrayList<>();
            for (Integer y : yearsList) {
                gdpList.add(gdpByYear.get(y));
                twseList.add(twseByYear.get(y));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("years", yearsList);
            body.put("gdpPerCapitaUsd", gdpList);
            body.put("twseYearEndClose", twseList);
            return ResponseEntity.ok(body);
        });
    }

    /**
     * 並行觸發 GDP（IMF）+ 大盤年末收盤（TWSE FMTQIK）回補。
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refresh(
            @RequestParam(defaultValue = "30") int years) {
        int currentYear = LocalDate.now().getYear();
        int from = currentYear - years + 1;

        ParameterizedTypeReference<Map<String, Object>> mapRef = new ParameterizedTypeReference<>() {};

        Mono<Map<String, Object>> gdp = businessServicesClient.post()
                .uri("/api/taiwan-gdp/refresh-from-imf")
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        // 30 年 × ~0.8s sleep + http roundtrip → 預計 30~60s
        Mono<Map<String, Object>> twse = businessServicesClient.post()
                .uri(uri -> uri.path("/api/twse-year-end-index/refresh")
                        .queryParam("from", from).queryParam("to", currentYear).build())
                .retrieve().bodyToMono(mapRef)
                .timeout(Duration.ofSeconds(120))
                .onErrorResume(e -> Mono.just(Map.of("error", e.getMessage())));

        return Mono.zip(gdp, twse).map(tuple -> {
            Map<String, Object> body = new HashMap<>();
            body.put("gdp", tuple.getT1());
            body.put("twse", tuple.getT2());
            return ResponseEntity.ok(body);
        });
    }
}
