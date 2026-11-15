package com.steven.assets.service.srpp.settings;

import com.steven.assets.dto.SrppSettingsDto.AiExecution;

/** Only a sanitized protocol code and a validated, optional receipt cross this boundary. */
public final class SrppSettingsAiFailure extends RuntimeException {
    private final String code;
    private final AiExecution execution;
    public SrppSettingsAiFailure(String code,AiExecution execution){super("AI 文字解讀未完成");this.code=code;this.execution=execution;}
    public String code(){return code;}
    public AiExecution execution(){return execution;}
}
