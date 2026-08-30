package com.steven.assets.integration.fubon;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual verification endpoint for the Fubon settlement bank balance sync
 * (Requirement 128 / Task 393).
 *
 * <p>Protected by {@link FubonBankBalanceInternalTokenFilter} (not the existing
 * {@link FubonInternalTokenFilter} or {@link FubonTradeInternalTokenFilter}, neither of which
 * recognizes this path). Delegates every gate, owner and write check to
 * {@link FubonBankBalanceSyncService#syncManual}; no business logic here. The response never
 * includes the raw account number or branch code (Requirement 128).
 */
@RestController
@RequestMapping("/internal/brokers/fubon/bank-balance-sync")
@RequiredArgsConstructor
public class FubonBankBalanceSyncController {
    private final FubonBankBalanceSyncService syncService;

    @PostMapping
    public FubonBankBalanceSyncService.SyncResult sync(@RequestParam(defaultValue = "true") boolean dryRun) {
        return syncService.syncManual(dryRun);
    }
}
