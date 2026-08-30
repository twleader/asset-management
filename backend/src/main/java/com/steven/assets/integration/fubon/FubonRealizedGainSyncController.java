package com.steven.assets.integration.fubon;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual verification endpoint for the Fubon realized-gain sync (Requirement 130 / Task 395).
 *
 * <p>Protected by {@link FubonRealizedGainInternalTokenFilter} (not any other existing Fubon
 * internal token filter, none of which recognizes this path). Delegates every gate, owner and
 * write check to {@link FubonRealizedGainSyncService#syncManual}; no business logic here. The
 * response never includes individual realized-gain row detail (Requirement 130).
 */
@RestController
@RequestMapping("/internal/brokers/fubon/realized-gain-sync")
@RequiredArgsConstructor
public class FubonRealizedGainSyncController {
    private final FubonRealizedGainSyncService syncService;

    @PostMapping
    public FubonRealizedGainSyncService.RealizedGainSyncResult sync(
            @RequestParam(defaultValue = "true") boolean dryRun) {
        return syncService.syncManual(dryRun);
    }
}
