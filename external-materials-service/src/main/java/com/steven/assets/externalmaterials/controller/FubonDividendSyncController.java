package com.steven.assets.externalmaterials.controller;

import com.steven.assets.externalmaterials.service.FubonDividendEvidenceSyncService;
import org.springframework.web.bind.annotation.*;

@RestController
public class FubonDividendSyncController {
    private final FubonDividendEvidenceSyncService service;
    public FubonDividendSyncController(FubonDividendEvidenceSyncService service) { this.service = service; }
    @PostMapping("/internal/dividend/fubon-sync")
    public FubonDividendEvidenceSyncService.Result sync(@RequestParam(defaultValue = "true") boolean dryRun) {
        return service.sync(dryRun);
    }
}
