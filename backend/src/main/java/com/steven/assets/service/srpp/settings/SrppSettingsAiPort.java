package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.dto.SrppSettingsDto.*;

/** Text-only interpretation boundary; calculators never consume this metadata or output. */
public interface SrppSettingsAiPort {
    AiCapabilities capabilities();
    void validateSelection(AiSelection selection);
    AiResult interpret(JsonNode frozenResult,AiSelection selection);
}
