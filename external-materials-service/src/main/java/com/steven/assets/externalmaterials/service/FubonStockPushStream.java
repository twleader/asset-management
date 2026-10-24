package com.steven.assets.externalmaterials.service;

import java.util.function.Consumer;
import java.util.Set;

public interface FubonStockPushStream {
    void start(Consumer<FubonMarketData.StockEvent> consumer);
    void stop();
    boolean isRunning();
    default String recoveryIdentity() { return null; }
    default boolean isReadyFor(Set<String> desiredSymbols) { return false; }
}
