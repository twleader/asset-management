package com.steven.assets.controller;

import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.service.aiflow.AiFlowService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 唯一寫入觀測資料的 exact ingress，不涉及金融producer。 */
@RestController
public final class SrppAiFlowController {
    private final AiFlowService service;
    public SrppAiFlowController(AiFlowService service) { this.service = service; }
    @PostMapping("/api/srpp/ai-flow/events")
    public ResponseEntity<Ack> ingest(@RequestHeader HttpHeaders headers, @RequestBody(required = false) String raw) {
        AppendResult result = service.ingest(headers.get("X-AI-Flow-Token"), headers.getFirst(HttpHeaders.CONTENT_TYPE), raw);
        return ResponseEntity.status(result.status()).cacheControl(CacheControl.noStore()).body(result.ack());
    }
}
