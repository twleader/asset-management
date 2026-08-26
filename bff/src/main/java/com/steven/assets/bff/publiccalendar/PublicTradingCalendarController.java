package com.steven.assets.bff.publiccalendar;

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

/** Exact anonymous 9090 calendar route. It has no user, owner, account, or broker input. */
@RestController
@RequestMapping("/api/public/trading-calendar")
@RequiredArgsConstructor
public class PublicTradingCalendarController {

    private final PublicTradingCalendarService service;

    @GetMapping
    public Mono<ResponseEntity<JsonNode>> current(@RequestParam(required = false) List<String> year) {
        return service.current(year).map(PublicTradingCalendarController::toResponse);
    }

    private static ResponseEntity<JsonNode> toResponse(PublicTradingCalendarRelay relay) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(relay.body());
    }
}
