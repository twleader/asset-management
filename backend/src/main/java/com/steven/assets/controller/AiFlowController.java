package com.steven.assets.controller;

import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.service.aiflow.AiFlowService;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

/** 專屬管理頁的 business 讀源；HTTP入口只委派service。 */
@RestController
@RequestMapping("/api/ai-flow/runs")
public final class AiFlowController {
    private final AiFlowService service;
    public AiFlowController(AiFlowService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<RunList> list(@RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(query));
    }
    @GetMapping("/{runId}")
    public ResponseEntity<Detail> detail(@PathVariable String runId, @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(runId, query));
    }
    @GetMapping("/{runId}/nodes/{eventId}/tool-call")
    public ResponseEntity<ToolCallDetail> toolCall(@PathVariable String runId, @PathVariable String eventId,
                                                    @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.toolCall(runId, eventId, query));
    }
    @GetMapping("/{runId}/nodes/{eventId}/choice-set")
    public ResponseEntity<ChoiceSetDetail> choiceSet(@PathVariable String runId, @PathVariable String eventId,
                                                      @RequestParam MultiValueMap<String, String> query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.choiceSet(runId, eventId, query));
    }
}
