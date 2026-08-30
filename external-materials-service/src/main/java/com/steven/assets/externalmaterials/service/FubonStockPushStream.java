package com.steven.assets.externalmaterials.service;

import java.util.function.Consumer;

public interface FubonStockPushStream {
    void start(Consumer<FubonMarketData.StockEvent> consumer);
    void stop();
    boolean isRunning();
}
