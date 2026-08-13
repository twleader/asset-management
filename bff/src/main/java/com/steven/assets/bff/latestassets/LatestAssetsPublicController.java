package com.steven.assets.bff.latestassets;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** 外部唯讀 latest-assets 的薄 HTTP 邊界。 */
@RestController
@RequestMapping("/api/assets/latest")
@RequiredArgsConstructor
public class LatestAssetsPublicController {
    private final LatestAssetsPublicService service;

    @GetMapping
    public Mono<ResponseEntity<byte[]>> getLatest() {
        return service.getLatest();
    }
}
