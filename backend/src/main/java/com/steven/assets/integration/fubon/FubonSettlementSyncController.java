package com.steven.assets.integration.fubon;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual verification endpoint for the Fubon near-term settlement sync (Requirement 129 / Task 394).
 *
 * <p>Protected by {@link FubonSettlementInternalTokenFilter} (not any of the three existing
 * filters, none of which recognizes this path). Delegates every gate, owner and write check to
 * {@link FubonSettlementSyncService#syncManual}; no business logic here. The response never
 * includes the raw account number, branch code, or individual settlement-day detail rows
 * (Requirement 129).
 */
@RestController
@RequestMapping("/internal/brokers/fubon/settlement-sync")
@RequiredArgsConstructor
public class FubonSettlementSyncController {
    private final FubonSettlementSyncService syncService;

    @PostMapping
    public FubonSettlementSyncService.SyncResult sync(@RequestParam(defaultValue = "true") boolean dryRun) {
        return syncService.syncManual(dryRun);
    }
}
