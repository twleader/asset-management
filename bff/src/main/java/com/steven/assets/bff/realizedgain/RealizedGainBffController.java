package com.steven.assets.bff.realizedgain;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * RealizedGainView 專屬 BFF。
 * 同時整合 brokers 列表（前端下拉用），避免另呼叫 institutionApi。
 */
@RestController
@RequestMapping("/api/bff/realized-gain")
@RequiredArgsConstructor
public class RealizedGainBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    /** 列表 + 同時帶回 active brokers 供下拉選單用。 */
    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> getAll() {
        return Mono.zip(
                businessServicesClient.get().uri("/api/realized-gains").retrieve().bodyToMono(LIST_MAP),
                businessServicesClient.get().uri("/api/settings/brokers").retrieve().bodyToMono(LIST_MAP)
        ).map(t -> {
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("gains", t.getT1());
            body.put("brokers", t.getT2().stream()
                    .filter(b -> Boolean.TRUE.equals(b.get("active")))
                    .toList());
            return ResponseEntity.ok(body);
        });
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody Map<String, Object> body) {
        return businessServicesClient.post().uri("/api/realized-gains")
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable Long id,
                                                            @RequestBody Map<String, Object> body) {
        return businessServicesClient.put().uri("/api/realized-gains/{id}", id)
                .bodyValue(body).retrieve().bodyToMono(MAP).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return businessServicesClient.delete().uri("/api/realized-gains/{id}", id)
                .retrieve().toBodilessEntity().map(r -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> exportExcel() {
        return businessServicesClient.get()
                .uri("/api/realized-gains/export")
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .retrieve()
                .toEntity(byte[].class)
                .map(e -> {
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok();
                    e.getHeaders().forEach((k, v) -> v.forEach(val -> b.header(k, val)));
                    return b.body(e.getBody());
                });
    }
}
