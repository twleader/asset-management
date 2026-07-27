package com.steven.assets.bff.portfolioadvice;

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
 * AssetAllocationAdviceView（資產配置建議頁）專屬 BFF（Requirement 32）。
 *
 * <p>一次聚合 business 的「最新建議 + 歷史 + 理財條件 profile + 成本控管設定 + 目前資產配置」，前端只 render；
 * 產生建議走 {@code POST /generate}（同步、可能耗數十秒，timeout 180s）；儲存條件走 {@code PUT /profile}；
 * 調整成本設定走 {@code PUT /settings}（限 ADMIN，見 SecurityConfig）。下游呼叫由 WebClient 帶上 {@code X-User-*}
 * 租戶身分，business 端 owner-scoped。
 */
@RestController
@RequestMapping("/api/bff/portfolio-advice")
@RequiredArgsConstructor
public class PortfolioAdviceBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /** GET /api/bff/portfolio-advice?historyLimit=20 → { latest, history, profile, settings, currentAllocation }。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(defaultValue = "20") int historyLimit) {
        Mono<Map<String, Object>> latestMono = businessServicesClient.get()
                .uri("/api/portfolio-advice/latest")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        Mono<List<Map<String, Object>>> historyMono = businessServicesClient.get()
                .uri(uri -> uri.path("/api/portfolio-advice/history")
                        .queryParam("limit", historyLimit).build())
                .retrieve().bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList());

        Mono<Map<String, Object>> profileMono = businessServicesClient.get()
                .uri("/api/portfolio-advice/profile")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        Mono<Map<String, Object>> settingsMono = businessServicesClient.get()
                .uri("/api/portfolio-advice/settings")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        Mono<Map<String, Object>> allocationMono = businessServicesClient.get()
                .uri("/api/portfolio-advice/current-allocation")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        Mono<Map<String, Object>> projectionMono = businessServicesClient.get()
                .uri("/api/portfolio-advice/projection")
                .retrieve().bodyToMono(MAP)
                .onErrorReturn(Collections.emptyMap());

        return Mono.zip(latestMono, historyMono, profileMono, settingsMono, allocationMono, projectionMono).map(t -> {
            Map<String, Object> body = new HashMap<>();
            body.put("latest", t.getT1());
            body.put("history", t.getT2());
            body.put("profile", t.getT3());
            body.put("settings", t.getT4());
            body.put("currentAllocation", t.getT5());
            body.put("projection", t.getT6());
            return ResponseEntity.ok(body);
        });
    }

    /** POST /api/bff/portfolio-advice/generate → 轉發 business（非同步：business 立即回 PROCESSING 列；180s timeout 為保險上限）。 */
    @PostMapping("/generate")
    public Mono<ResponseEntity<Map<String, Object>>> generate(@RequestBody(required = false) Map<String, Object> body) {
        return businessServicesClient.post()
                .uri("/api/portfolio-advice/generate")
                .bodyValue(body == null ? Collections.emptyMap() : body)
                .retrieve().bodyToMono(MAP)
                .timeout(Duration.ofSeconds(180))
                .map(ResponseEntity::ok);
    }

    /** PUT /api/bff/portfolio-advice/profile → 轉發 business（儲存理財條件）。 */
    @PutMapping("/profile")
    public Mono<ResponseEntity<Map<String, Object>>> saveProfile(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/portfolio-advice/profile")
                .bodyValue(body == null ? Collections.emptyMap() : body)
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }

    /** PUT /api/bff/portfolio-advice/settings → 轉發 business（更新成本設定；限 ADMIN，見 SecurityConfig）。 */
    @PutMapping("/settings")
    public Mono<ResponseEntity<Map<String, Object>>> updateSettings(@RequestBody Map<String, Object> body) {
        return businessServicesClient.put()
                .uri("/api/portfolio-advice/settings")
                .bodyValue(body == null ? Collections.emptyMap() : body)
                .retrieve().bodyToMono(MAP)
                .map(ResponseEntity::ok);
    }
}
