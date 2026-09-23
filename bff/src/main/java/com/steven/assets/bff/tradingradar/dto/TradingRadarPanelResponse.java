package com.steven.assets.bff.tradingradar.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** A complete browser panel; the defensive copy keeps its JSON tree immutable to callers. */
public record TradingRadarPanelResponse(String panel, String ruleVersion, String actionPolicyVersion,
                                        String generatedAt, JsonNode data) {
    public TradingRadarPanelResponse {
        data = data.deepCopy();
    }

    @Override
    public JsonNode data() {
        return data.deepCopy();
    }
}
