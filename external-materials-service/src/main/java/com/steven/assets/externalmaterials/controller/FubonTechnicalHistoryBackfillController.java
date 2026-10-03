package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.FubonTechnicalHistoryBackfillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/technical-indicators/history-backfill-jobs")
public class FubonTechnicalHistoryBackfillController {
    private final FubonTechnicalHistoryBackfillService service;
    public FubonTechnicalHistoryBackfillController(FubonTechnicalHistoryBackfillService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<FubonTechnicalHistoryBackfillService.View> start() {
        return ResponseEntity.accepted().body(service.start());
    }

    @GetMapping("/{jobId}")
    public FubonTechnicalHistoryBackfillService.View get(@PathVariable String jobId) { return service.get(jobId); }
}
