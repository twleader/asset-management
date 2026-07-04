package com.steven.assets.bff.todaymarketanalysis;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TodayMarketAnalysisView（今日股市分析頁）專屬 BFF（Requirement 31）。
 * 一次聚合 business 的「當日分析」+「歷史」，前端只 render；重新分析走 POST /generate（限 ADMIN，見 SecurityConfig）。
 */
@RestController
@RequestMapping("/api/bff/today-market-analysis")
@RequiredArgsConstructor
public class TodayMarketAnalysisBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /** GET /api/bff/today-market-analysis?historyLimit=30 → { today, history, settings }。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(defaultValue = "30") int historyLimit) {
        Mono<Map<String, Object>> todayMono = businessServicesClient.get()
                .uri("/api/market-analysis/today")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        Mono<List<Map<String, Object>>> historyMono = businessServicesClient.get()
                .uri(uri -> uri.path("/api/market-analysis/history")
                        .queryParam("limit", historyLimit).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<Map<String, Object>> settingsMono = businessServicesClient.get()
                .uri("/api/market-analysis/settings")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        return Mono.zip(todayMono, historyMono, settingsMono).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("today", t.getT1());
            body.put("history", t.getT2());
            body.put("settings", t.getT3());
            return ResponseEntity.ok(body);
        });
    }

    /** POST /api/bff/today-market-analysis/generate → 轉發 business；限 ADMIN（SecurityConfig）。 */
    @PostMapping("/generate")
    public Mono<ResponseEntity<Map<String, Object>>> generate() {
        return businessServicesClient.post()
                .uri("/api/market-analysis/generate")
                .retrieve().bodyToMono(MAP)
                .timeout(Duration.ofSeconds(180))
                .map(ResponseEntity::ok);
    }

    /** PUT /api/bff/today-market-analysis/settings → 轉發 business 更新模型；限 ADMIN（SecurityConfig）。 */
    @PutMapping("/settings")
    public Mono<ResponseEntity<Map<String, Object>>> updateSettings(
            @RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/market-analysis/settings")
                .bodyValue(body == null ? Collections.emptyMap() : body)
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }
}
