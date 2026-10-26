package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.FubonMarketData;
import java.util.Set;
import java.util.function.Consumer;

public interface FubonStockPushStream {
    void start(Consumer<FubonMarketData.StockEvent> consumer);
    void stop();
    boolean isRunning();
    default String recoveryIdentity() { return null; }
    default boolean isReadyFor(Set<String> desiredSymbols) { return false; }
}
