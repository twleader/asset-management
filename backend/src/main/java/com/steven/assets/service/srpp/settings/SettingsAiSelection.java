package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.dto.SrppSettingsDto.AiSelection;

/** Pure closed-wire validation, independent from every execution adapter. */
final class SettingsAiSelection {
    private SettingsAiSelection() {}
    static AiSelection parse(JsonNode raw) {
        if(raw==null||!raw.isObject()||raw.size()!=4||!raw.has("toolId")||!raw.has("model")||!raw.has("effort")||!raw.has("catalogRevision"))throw problem();
        try {
            AiSelection value=SettingsCanonicalJson.bind(raw,AiSelection.class);
            if(!raw.path("toolId").isTextual()||!raw.path("model").isTextual()||!raw.path("catalogRevision").isTextual()
                    ||!raw.path("effort").isNull()&&!raw.path("effort").isTextual()
                    ||!value.toolId().matches("[a-z0-9][a-z0-9_-]{0,63}")
                    ||!value.model().matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}")
                    ||!value.catalogRevision().matches("[a-f0-9]{64}")
                    ||value.effort()!=null&&!value.effort().matches("[a-z][a-z0-9_-]{0,31}"))throw problem();
            return value;
        }catch(RuntimeException invalid){throw problem();}
    }
    private static SrppSettingsProblem problem(){return new SrppSettingsProblem(400,"AI_INVALID_SELECTION","請選擇有效的 AI 工具、模型與思考程度");}
}
