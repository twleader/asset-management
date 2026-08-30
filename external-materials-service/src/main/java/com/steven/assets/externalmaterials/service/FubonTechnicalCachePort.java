package com.steven.assets.externalmaterials.service;

public interface FubonTechnicalCachePort {
    FubonTechnicalCache.Write write(FubonMarketData.TechnicalRead observation);
    FubonTechnicalCache.Read read(String symbol);
}
