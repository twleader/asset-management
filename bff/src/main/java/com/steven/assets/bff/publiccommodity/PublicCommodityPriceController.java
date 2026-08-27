package com.steven.assets.bff.publiccommodity;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Exact anonymous route with a local no-query/no-body gate before any downstream subscription. */
@RestController
@RequestMapping(PublicCommodityPriceController.PATH)
@RequiredArgsConstructor
public class PublicCommodityPriceController {

    public static final String PATH = "/api/public/commodity-prices";

    private final PublicCommodityPriceService service;

    @GetMapping
    public Mono<ResponseEntity<CommodityPriceBatchResponse>> current(
            @RequestParam MultiValueMap<String, String> queryParameters,
            ServerHttpRequest request) {
        validateRequest(queryParameters, request.getHeaders());
        return service.current().map(body -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body));
    }

    private static void validateRequest(MultiValueMap<String, String> queryParameters, HttpHeaders headers) {
        if (queryParameters == null || !queryParameters.isEmpty()
                || headers.getContentLength() > 0
                || headers.containsKey(HttpHeaders.TRANSFER_ENCODING)) {
            throw new PublicCommodityPriceRequestException();
        }
    }
}
