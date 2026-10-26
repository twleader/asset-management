package com.steven.assets.externalmaterials.service.port;

import java.util.Set;

/** Narrow market capability implemented by the existing IO adapter. */
public interface StockUniversePort {
    void collectTwRadarCodes(Set<String> codes);
    void collectHeldStockCodes(Set<String> tw, Set<String> us, Set<String> uk);
    void collectAllStockCodes(Set<String> tw, Set<String> us, Set<String> uk);
}
