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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 回傳近 N 年（預設 30）對齊好的：
 *   - years
 *   - gdpPerCapitaUsd（台灣）/ koreaGdpPerCapitaUsd（韓國）
 *   - twseYearEndClose
 *   - taiwanGdpGrowthRate / koreaGdpGrowthRate（年增率 %）
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
        // 算年增率需要前一年資料，因此向 backend 多要一年
        int fetchSince = since - 1;

        Mono<List<Map<String, Object>>> gdp = fetchSeries("/api/taiwan-gdp", fetchSince);
        Mono<List<Map<String, Object>>> kor = fetchSeries("/api/korea-gdp", fetchSince);
        Mono<List<Map<String, Object>>> twse = fetchSeries("/api/twse-year-end-index", since);

        return Mono.zip(gdp, kor, twse).map(t -> {
            TreeMap<Integer, BigDecimal> twGdpAll = toMap(t.getT1(), "gdpUsd");
            TreeMap<Integer, BigDecimal> krGdpAll = toMap(t.getT2(), "gdpUsd");
            TreeMap<Integer, BigDecimal> twseClose = toMap(t.getT3(), "closePoint");

            // X 軸：since..currentYear 取所有 series 的聯集
            TreeMap<Integer, Boolean> yset = new TreeMap<>();
            twGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            krGdpAll.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });
            twseClose.keySet().forEach(y -> { if (y >= since && y <= currentYear) yset.put(y, true); });

            List<Integer> yearsList = new ArrayList<>(yset.keySet());
            List<Object> twGdp = new ArrayList<>();
            List<Object> krGdp = new ArrayList<>();
            List<Object> twseList = new ArrayList<>();
            List<Object> twGrowth = new ArrayList<>();
            List<Object> krGrowth = new ArrayList<>();
            for (Integer y : yearsList) {
                twGdp.add(twGdpAll.get(y));
                krGdp.add(krGdpAll.get(y));
                twseList.add(twseClose.get(y));
                twGrowth.add(growth(twGdpAll.get(y - 1), twGdpAll.get(y)));
                krGrowth.add(growth(krGdpAll.get(y - 1), krGdpAll.get(y)));
            }

            Map<String, Object> body = new HashMap<>();
            body.put("years", yearsList);
            body.put("gdpPerCapitaUsd", twGdp);
            body.put("koreaGdpPerCapitaUsd", krGdp);
            body.put("twseYearEndClose", twseList);
            body.put("taiwanGdpGrowthRate", twGrowth);
            body.put("koreaGdpGrowthRate", krGrowth);
            return ResponseEntity.ok(body);
        });
    }

    private Mono<List<Map<String, Object>>> fetchSeries(String path, int since) {
        return businessServicesClient.get()
                .uri(uri -> uri.path(path).queryParam("since", since).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());
    }

    private TreeMap<Integer, BigDecimal> toMap(List<Map<String, Object>> rows, String valueKey) {
        TreeMap<Integer, BigDecimal> m = new TreeMap<>();
        for (Map<String, Object> r : rows) {
            Object v = r.get(valueKey);
            if (v == null) continue;
            int y = ((Number) r.get("year")).intValue();
            m.put(y, new BigDecimal(v.toString()));
        }
        return m;
    }

    /** 年增率 %，無前年資料則 null。 */
    private BigDecimal growth(BigDecimal prev, BigDecimal curr) {
        if (prev == null || curr == null || prev.signum() == 0) return null;
        return curr.subtract(prev)
                .multiply(BigDecimal.valueOf(100))
                .divide(prev, 2, RoundingMode.HALF_UP);
    }

    /**
     * 並行觸發 TWN/KOR GDP（IMF）+ 大盤年末收盤（TWSE FMTQIK）回補。
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

        Mono<Map<String, Object>> kor = businessServicesClient.post()
                .uri("/api/korea-gdp/refresh-from-imf")
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

        return Mono.zip(gdp, kor, twse).map(tuple -> {
            Map<String, Object> body = new HashMap<>();
            body.put("gdp", tuple.getT1());
            body.put("korea", tuple.getT2());
            body.put("twse", tuple.getT3());
            return ResponseEntity.ok(body);
        });
    }
}
