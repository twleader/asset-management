package com.steven.assets.bff.latestassets;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 外部唯讀 latest-assets 的薄 HTTP 邊界。 */
@RestController
@RequestMapping("/api/assets/latest")
@RequiredArgsConstructor
public class LatestAssetsPublicController {
    private final LatestAssetsPublicService service;

    /**
     * {@code email} 省略時 owner 為 configured-admin；帶入合法且 active 帳號的 email 時，
     * owner 改由該帳號決定（Requirement 140）。
     */
    @GetMapping
    public Mono<ResponseEntity<byte[]>> getLatest(@RequestParam(required = false) String email) {
        return service.getLatest(email);
    }
}
