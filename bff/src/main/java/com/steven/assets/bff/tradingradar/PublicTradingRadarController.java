package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Requirement 86 的匿名唯讀邊界；controller 只委派 configured-admin service。 */
@RestController
@RequestMapping("/api/public/trading-radar")
@RequiredArgsConstructor
public class PublicTradingRadarController {

    private final PublicTradingRadarService service;

    @GetMapping("/today")
    public Mono<ResponseEntity<JsonNode>> today() {
        return service.today().map(PublicTradingRadarController::toResponse);
    }

    @GetMapping("/stock")
    public Mono<ResponseEntity<JsonNode>> stock(
            @RequestParam(required = false) List<String> stockCode,
            @RequestParam(required = false) List<String> market) {
        return service.stock(stockCode, market).map(PublicTradingRadarController::toResponse);
    }

    private static ResponseEntity<JsonNode> toResponse(PublicTradingRadarRelay relay) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(relay.body());
    }
}
