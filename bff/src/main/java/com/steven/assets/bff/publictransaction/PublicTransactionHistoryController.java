package com.steven.assets.bff.publictransaction;

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

/** Anonymous 9090 entry point; configured owner selection happens only in the service. */
@RestController
@RequestMapping("/api/public/transactions")
@RequiredArgsConstructor
public class PublicTransactionHistoryController {

    private final PublicTransactionHistoryService service;

    @GetMapping
    public Mono<ResponseEntity<JsonNode>> current(
            @RequestParam(required = false) List<String> year,
            @RequestParam(required = false) List<String> start,
            @RequestParam(required = false) List<String> end) {
        return service.current(year, start, end).map(PublicTransactionHistoryController::toResponse);
    }

    private static ResponseEntity<JsonNode> toResponse(PublicTransactionHistoryRelay relay) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(relay.body());
    }
}
