package com.steven.assets.bff.fubonapi;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 富邦唯讀排程執行開關（Requirement 218／Task 612）：「富邦證 API」頁專屬 BFF，WebClient 轉呼 business
 * {@code /api/settings/fubon-schedule-switches}。{@code GET} 已登入者皆可；{@code PUT} 限 ADMIN（BFF SecurityConfig），
 * business 端另以 {@code CurrentUserContext.isAdmin()} 縱深防禦。刻意不做 onErrorReturn 降級：讀不到就讓錯誤浮上來，
 * 避免畫面把「讀取失敗」誤顯示成全部關閉。本類別不呼叫富邦、不新增 9090 路由。
 */
@RestController
@RequestMapping("/api/bff/fubon-api/schedule-switches")
@RequiredArgsConstructor
public class FubonScheduleSwitchBffController {

    private static final String BUSINESS_PATH = "/api/settings/fubon-schedule-switches";
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    private final WebClient businessServicesClient;

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> list() {
        return businessServicesClient.get().uri(BUSINESS_PATH)
                .retrieve().bodyToMono(LIST_MAP).map(ResponseEntity::ok);
    }

    /** body：{@code {"enabled": true|false}}。 */
    @PutMapping("/{scheduleKey}")
    public Mono<ResponseEntity<Map<String, Object>>> update(
            @PathVariable String scheduleKey, @RequestBody Map<String, Object> body) {
        if (body == null || !(body.get("enabled") instanceof Boolean enabled)) {
            // 缺值、null 或非 JSON boolean 不得被解讀成停用：直接 400，不呼叫 business
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 必須是 true 或 false"));
        }
        return businessServicesClient.put().uri(BUSINESS_PATH + "/{key}", scheduleKey)
                .bodyValue(Map.of("enabled", enabled))
                .retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }
}
