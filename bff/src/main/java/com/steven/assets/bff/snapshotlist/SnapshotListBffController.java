package com.steven.assets.bff.snapshotlist;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * SnapshotListView 專屬 BFF。
 */
@RestController
@RequestMapping("/api/bff/snapshot-list")
@RequiredArgsConstructor
public class SnapshotListBffController {

    private final WebClient businessServicesClient;

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> getAll() {
        return businessServicesClient.get().uri("/api/snapshots")
                .retrieve().bodyToMono(LIST_MAP).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return businessServicesClient.delete().uri("/api/snapshots/{id}", id)
                .retrieve().toBodilessEntity().map(r -> ResponseEntity.noContent().<Void>build());
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<byte[]>> exportExcel() {
        return businessServicesClient.get()
                .uri("/api/snapshots/export")
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
