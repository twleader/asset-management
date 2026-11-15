package com.steven.assets.service.srpp.settings;
import com.fasterxml.jackson.databind.JsonNode;
public interface SrppClaudeAuthPort { JsonNode call(String action,JsonNode body); }
