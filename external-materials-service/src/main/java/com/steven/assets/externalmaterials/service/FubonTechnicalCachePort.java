package com.steven.assets.externalmaterials.service;

public interface FubonTechnicalCachePort {
    FubonTechnicalCache.Write write(FubonMarketData.TechnicalRead observation);
    FubonTechnicalCache.Read read(String symbol);

    IntradayWrite writeIntraday(FubonIntradayTechnical.Bundle observation);
    String readIntradayCursor();
    void advanceIntradayCursor(String symbol);

    enum IntradayWrite { WRITTEN, REJECTED_STALE, FAILED }
}
