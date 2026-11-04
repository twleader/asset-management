package com.steven.assets.service;

import java.math.BigDecimal;
import java.util.Map;

/** Optional private mirror. DB remains authoritative; a read never refreshes or writes the cache. */
public interface HoldingCostCachePort {
    boolean publish(HoldingCostSnapshot committed);
    Map<Long, BigDecimal> readMatching(HoldingCostSnapshot database);

    static HoldingCostCachePort disabled() {
        return new HoldingCostCachePort() {
            public boolean publish(HoldingCostSnapshot committed) { return true; }
            public Map<Long, BigDecimal> readMatching(HoldingCostSnapshot database) { return Map.of(); }
        };
    }
}
