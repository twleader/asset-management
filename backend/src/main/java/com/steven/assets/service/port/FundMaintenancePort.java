package com.steven.assets.service.port;

import java.util.Map;

/** Fixed fund maintenance operations; no arbitrary URL or client dependency. */
public interface FundMaintenancePort {
    Map<String, Object> refreshNav();
    Map<String, Object> refreshDividend();
    Map<String, Object> backfillNav(int years);
    Map<String, Object> backfillDividend(int years);
}
