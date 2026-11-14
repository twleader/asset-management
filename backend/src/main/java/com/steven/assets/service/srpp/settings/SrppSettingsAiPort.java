package com.steven.assets.service.srpp.settings;
import com.fasterxml.jackson.databind.JsonNode;

public interface SrppSettingsAiPort {
    boolean available();
    String interpret(JsonNode frozenResult);
}
