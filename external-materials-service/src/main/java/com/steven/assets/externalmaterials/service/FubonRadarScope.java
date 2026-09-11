package com.steven.assets.externalmaterials.service;

import org.springframework.stereotype.Component;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reuses the single existing per-owner Taiwan radar query; never broadens on failure. */
@Component
public class FubonRadarScope {
    private final StockSourceQuery source;
    public FubonRadarScope(StockSourceQuery source) { this.source = source; }
    public List<String> current(int limit) {
        Set<String> codes = new LinkedHashSet<>();
        try { source.collectTwRadarCodes(codes); }
        catch (RuntimeException failure) { throw new FubonMarketData.Unavailable("RADAR_UNAVAILABLE"); }
        if (codes.stream().anyMatch(code -> !StockSourceQuery.isTaiwanRadarCode(code)))
            throw new FubonMarketData.Unavailable("RADAR_INVALID");
        if (codes.size() > limit) throw new FubonMarketData.Unavailable("SUBSCRIPTION_LIMIT");
        return codes.stream().sorted().toList();
    }
}
