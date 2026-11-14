package com.steven.assets.service.srpp.settings;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.service.PortfolioAdviceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;

/** Optional text interpretation. It never supplies numbers to the calculator or changes settings. */
@Component
public class SrppSettingsAnthropicAi implements SrppSettingsAiPort {
    private final String key;
    private final PortfolioAdviceService advice;
    public SrppSettingsAnthropicAi(@Value("${anthropic.api-key:}") String key,PortfolioAdviceService advice){this.key=key;this.advice=advice;}
    public boolean available(){return key!=null&&!key.isBlank();}
    public String interpret(JsonNode result) {
        if(!available())throw new IllegalStateException("AI_NOT_CONFIGURED");
        var settings=advice.getSettings();
        OutputConfig.Effort effort=switch(settings.effort()==null?"":settings.effort()) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.MEDIUM;
        };
        var client=AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(45)).maxRetries(0).build();
        try {
            MessageCreateParams params=MessageCreateParams.builder().model(settings.model()).maxTokens(1800L)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(effort).build())
                    .system("你是退休規劃試算的文字解讀者。請用台灣繁體中文解釋提供的基準與情境差異、限制與風險。數值全部由系統計算，禁止自行計算、修改或新增數值。此為給定假設下的情境模型，非完整正式模型，存續率不代表報酬保證；日頻買賣與風險門檻未納退休路徑者只能解釋規則差異，不得聲稱已回測。不得引用未提供的行情、新聞或來源，不得下單、修改規則或宣稱正式生效。輸出簡短純文字，保留數值原精度。")
                    .addUserMessage(SettingsCanonicalJson.canonical(result)).build();
            Message message=client.messages().create(params);
            StringBuilder text=new StringBuilder();
            for(ContentBlock block:message.content())if(block.isText())text.append(block.asText().text());
            if(text.isEmpty())throw new IllegalStateException("AI_EMPTY_RESPONSE");
            return text.substring(0,Math.min(20000,text.length()));
        } finally {
            if(client instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}
        }
    }
}
