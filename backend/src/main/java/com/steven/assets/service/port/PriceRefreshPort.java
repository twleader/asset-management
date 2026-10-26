package com.steven.assets.service.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
public interface PriceRefreshPort {
    JsonNode refreshTradingRadarPrices();
    Map<String, Object> triggerRefresh();
}
