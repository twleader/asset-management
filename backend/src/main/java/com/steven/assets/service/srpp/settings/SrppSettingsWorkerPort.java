package com.steven.assets.service.srpp.settings;
import com.fasterxml.jackson.databind.JsonNode;

public interface SrppSettingsWorkerPort {
    JsonNode catalog();
    JsonNode validate(JsonNode request);
    JsonNode simulate(JsonNode request);
}
