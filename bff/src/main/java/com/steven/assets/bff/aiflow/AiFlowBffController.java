package com.steven.assets.bff.aiflow;

import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** AI 判斷流程頁面專屬 ADMIN BFF。 */
@RestController
@RequestMapping("/api/bff/ai-flow")
public final class AiFlowBffController {
    private final AiFlowBffService service;
    AiFlowBffController(AiFlowBffService service){this.service=service;}
    @GetMapping("/runs") Mono<ResponseEntity<AiFlowDtos.RunList>> list(ServerHttpRequest request) {
        return service.list(request).map(AiFlowBffController::okay);
    }
    @GetMapping("/runs/{id}") Mono<ResponseEntity<AiFlowDtos.Detail>> detail(@PathVariable String id,ServerHttpRequest request) {
        return service.detail(id,request).map(AiFlowBffController::okay);
    }
    @GetMapping("/runs/{runId}/nodes/{eventId}/tool-call") Mono<ResponseEntity<AiFlowDtos.ToolCallDetail>> toolCall(
            @PathVariable String runId,@PathVariable String eventId,ServerHttpRequest request) {
        return service.toolCall(runId,eventId,request).map(AiFlowBffController::okay);
    }
    private static <T> ResponseEntity<T> okay(T dto) {return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(dto);}
}
