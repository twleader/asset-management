package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.FubonMarketData;
import com.steven.assets.externalmaterials.model.marketdata.TaiwanRadarRules;
import com.steven.assets.externalmaterials.service.port.StockUniversePort;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Reuses the single existing per-owner Taiwan radar query; never broadens on failure. */
@Component
public class FubonRadarScope {
    private final StockUniversePort source;
    public FubonRadarScope(StockUniversePort source) { this.source = source; }
    public List<String> current(int limit) {
        Set<String> codes = new LinkedHashSet<>();
        try { source.collectTwRadarCodes(codes); }
        catch (RuntimeException failure) { throw new FubonMarketData.Unavailable("RADAR_UNAVAILABLE"); }
        if (codes.stream().anyMatch(code -> !TaiwanRadarRules.isTaiwanRadarCode(code)))
            throw new FubonMarketData.Unavailable("RADAR_INVALID");
        if (codes.size() > limit) throw new FubonMarketData.Unavailable(limit == 30 ? "RADAR_LIMIT_EXCEEDED" : "SUBSCRIPTION_LIMIT");
        return codes.stream().sorted().toList();
    }
}
