package com.steven.assets.bff.tradingradar.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** The compact row, full detail and market are one business evaluation, never BFF recomputation. */
public record TradingRadarStockEvaluationResponse(String ruleVersion, String actionPolicyVersion,
                                                 String generatedAt, JsonNode market,
                                                 JsonNode summary, JsonNode stock) {
    public TradingRadarStockEvaluationResponse {
        market = market.deepCopy();
        summary = summary.deepCopy();
        stock = stock.deepCopy();
    }

    @Override
    public JsonNode market() { return market.deepCopy(); }

    @Override
    public JsonNode summary() { return summary.deepCopy(); }

    @Override
    public JsonNode stock() { return stock.deepCopy(); }
}
