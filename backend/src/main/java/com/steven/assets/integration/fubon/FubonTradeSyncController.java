package com.steven.assets.integration.fubon;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual verification endpoint for the Fubon filled-trade sync (Requirement 120 / Task 385).
 *
 * <p>Protected by {@link FubonTradeInternalTokenFilter} (not the existing
 * {@link FubonInternalTokenFilter}, which does not recognize this path). Delegates every gate,
 * calendar and owner check to {@link FubonTradeSyncService#syncManual}; no business logic here.
 */
@RestController
@RequestMapping("/internal/brokers/fubon/trade-sync")
@RequiredArgsConstructor
public class FubonTradeSyncController {
    private final FubonTradeSyncService syncService;

    @PostMapping
    public FubonTradeSyncService.TradeSyncResult sync(@RequestParam(defaultValue = "true") boolean dryRun) {
        return syncService.syncManual(dryRun);
    }
}
