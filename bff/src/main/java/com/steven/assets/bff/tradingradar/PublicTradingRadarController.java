package com.steven.assets.bff.tradingradar;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Requirement 86 的匿名唯讀邊界；controller 只委派 configured-admin service。 */
@RestController
@RequestMapping("/api/public/trading-radar/today")
@RequiredArgsConstructor
public class PublicTradingRadarController {

    private final PublicTradingRadarService service;

    @GetMapping
    public Mono<ResponseEntity<byte[]>> today() {
        return service.today().map(PublicTradingRadarController::toResponse);
    }

    private static ResponseEntity<byte[]> toResponse(PublicTradingRadarRelay relay) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(relay.statusCode());
        if (relay.contentType() != null) {
            response.header(HttpHeaders.CONTENT_TYPE, relay.contentType());
        }
        return response.body(relay.body());
    }
}
