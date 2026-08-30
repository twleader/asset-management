package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.FubonTechnicalIndicatorReadService;
import com.steven.assets.externalmaterials.service.FubonTechnicalIndicatorSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class FubonTechnicalIndicatorController {
    private final FubonTechnicalIndicatorSyncService sync;
    private final FubonTechnicalIndicatorReadService read;
    public FubonTechnicalIndicatorController(FubonTechnicalIndicatorSyncService sync, FubonTechnicalIndicatorReadService read) {
        this.sync = sync; this.read = read;
    }
    @PostMapping("/internal/technical-indicators/fubon-sync")
    public FubonTechnicalIndicatorSyncService.Result sync(@RequestParam(defaultValue = "true") boolean dryRun) {
        return sync.sync(dryRun);
    }
    @GetMapping("/internal/technical-indicators/fubon-cache")
    public ResponseEntity<FubonTechnicalIndicatorReadService.Result> read(@RequestParam String symbol) {
        var result = read.read(symbol);
        int status = switch (result.outcome()) {
            case "INVALID_REQUEST" -> 400;
            case "NOT_RADAR" -> 403;
            case "UNAVAILABLE", "CORRUPT_CACHE" -> 503;
            default -> 200;
        };
        return ResponseEntity.status(status).body(result);
    }
}
