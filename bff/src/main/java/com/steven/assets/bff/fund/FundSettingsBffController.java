package com.steven.assets.bff.fund;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * FundSettingsView 專屬 BFF：銷售銀行下拉。
 *
 * <p>與 SnapshotFormView 的 lookups 同讀 business service {@code /api/settings/banks}
 * （同義欄位同一來源，避免不同頁銀行清單不一致），並在 BFF 端過濾 active（前端只 render）。
 * fund-settings 頁不再跨頁呼叫 snapshot-form 的 BFF。
 */
@RestController
@RequestMapping("/api/bff/fund-settings")
@RequiredArgsConstructor
public class FundSettingsBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    /**
     * GET /api/bff/fund-settings/bank-options
     * 銷售銀行下拉（已過濾 active=true）；每筆為 bank 主檔 map（含 id / displayName）。
     */
    @GetMapping("/bank-options")
    public Mono<ResponseEntity<List<Map<String, Object>>>> bankOptions() {
        return businessServicesClient.get()
                .uri("/api/settings/banks")
                .retrieve()
                .bodyToMono(LIST_MAP)
                .onErrorReturn(Collections.emptyList())
                .map(list -> list.stream()
                        .filter(m -> Boolean.TRUE.equals(m.get("active")))
                        .toList())
                .map(ResponseEntity::ok);
    }
}
