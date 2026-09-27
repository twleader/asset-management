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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AssetAllocationAdviceView（資產配置建議頁）專屬 BFF（Requirement 32）。
 *
 * <p>一次聚合 business 的「最新建議 + 歷史 + 理財條件 profile + 成本控管設定 + 目前資產配置」，前端只 render；
 * 產生建議走 {@code POST /generate}（LOCAL 同步終態；HYBRID／LLM 回 PROCESSING 並由背景完成，轉發 timeout 180s）；儲存條件走 {@code PUT /profile}；
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

    private record FetchResult<T>(T value, boolean failed) {}

    /** GET /api/bff/portfolio-advice?historyLimit=20 → { latest, history, profile, settings, currentAllocation }。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @RequestParam(defaultValue = "20") int historyLimit) {
        Mono<FetchResult<Map<String, Object>>> latestMono = fetch(
                businessServicesClient.get().uri("/api/portfolio-advice/latest"), MAP, Collections.emptyMap());

        Mono<FetchResult<List<Map<String, Object>>>> historyMono = fetch(businessServicesClient.get()
                .uri(uri -> uri.path("/api/portfolio-advice/history")
                        .queryParam("limit", historyLimit).build()), LIST_MAP, Collections.emptyList());

        Mono<FetchResult<Map<String, Object>>> profileMono = fetch(
                businessServicesClient.get().uri("/api/portfolio-advice/profile"), MAP, Collections.emptyMap());

        Mono<FetchResult<Map<String, Object>>> settingsMono = fetch(
                businessServicesClient.get().uri("/api/portfolio-advice/settings"), MAP, Collections.emptyMap());

        Mono<FetchResult<Map<String, Object>>> allocationMono = fetch(
                businessServicesClient.get().uri("/api/portfolio-advice/current-allocation"), MAP, Collections.emptyMap());

        Mono<FetchResult<Map<String, Object>>> projectionMono = fetch(
                businessServicesClient.get().uri("/api/portfolio-advice/projection"), MAP, Collections.emptyMap());

        return Mono.zip(latestMono, historyMono, profileMono, settingsMono, allocationMono, projectionMono).map(t -> {
            Map<String, Object> body = new HashMap<>();
            // Task 344.23(1)：再平衡明細的分組／分段／排序在 BFF 做完，前端只 render。
            // 必須先複製再 put——上方 latestMono 的 onErrorReturn 回的是不可變的 Collections.emptyMap()，
            // 直接對它 put 會拋 UnsupportedOperationException，把「200 ＋ 空資料」降級路徑變成 500。
            // 既有先例：SnapshotDetailBffController、DashboardBffController 皆複製後再 put。
            Map<String, Object> latest = new HashMap<>(t.getT1().value());
            latest.put("rebalanceGroups", RebalanceGrouper.group(t.getT1().value()));
            body.put("latest", latest);
            body.put("history", t.getT2().value());
            body.put("profile", t.getT3().value());
            body.put("settings", t.getT4().value());
            body.put("currentAllocation", t.getT5().value());
            body.put("projection", t.getT6().value());
            List<String> fetchErrors = new ArrayList<>();
            if (t.getT1().failed()) fetchErrors.add("latest");
            if (t.getT2().failed()) fetchErrors.add("history");
            if (t.getT3().failed()) fetchErrors.add("profile");
            if (t.getT4().failed()) fetchErrors.add("settings");
            if (t.getT5().failed()) fetchErrors.add("currentAllocation");
            if (t.getT6().failed()) fetchErrors.add("projection");
            body.put("fetchErrors", fetchErrors);
            return ResponseEntity.ok(body);
        });
    }

    /**
     * Downstream 的 HTTP、傳輸、解碼錯誤及成功但缺 response body 都只影響自己的聚合區塊。
     * 不把例外細節帶到 BFF 回應，讓前端可安全地依固定識別值保留既有資料。
     */
    private <T> Mono<FetchResult<T>> fetch(WebClient.RequestHeadersSpec<?> request,
                                            ParameterizedTypeReference<T> type,
                                            T fallback) {
        return request.retrieve().bodyToMono(type)
                .map(value -> new FetchResult<>(value, false))
                .switchIfEmpty(Mono.just(new FetchResult<>(fallback, true)))
                .onErrorReturn(new FetchResult<>(fallback, true));
    }

    /** POST /api/bff/portfolio-advice/generate → 轉發 business（LOCAL 同步終態；HYBRID／LLM 立即回 PROCESSING；180s timeout 為轉發上限）。 */
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
