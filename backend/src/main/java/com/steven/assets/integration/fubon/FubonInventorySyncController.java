package com.steven.assets.integration.fubon;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/brokers/fubon/inventory-sync")
@RequiredArgsConstructor
public class FubonInventorySyncController {
    private final FubonInventorySyncService syncService;

    @PostMapping
    public FubonDtos.SyncResponse sync(@RequestParam(defaultValue = "true") boolean dryRun) {
        return syncService.syncManual(dryRun);
    }
}
