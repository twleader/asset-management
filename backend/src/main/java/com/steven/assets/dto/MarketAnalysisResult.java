package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 今日股市分析（Requirement 31）——Claude 回傳 JSON 的解析目標。
 * 忽略未知欄位（模型可能多帶說明性欄位）；缺欄位為 null。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarketAnalysisResult(
        String bias,                 // BULLISH / BEARISH / NEUTRAL
        Integer confidence,          // 0..100
        String summary,              // 當日走向總結（繁中一段）
        List<String> keyFactors,     // 關鍵因素
        List<NewsHighlight> newsHighlights,
        String twContext,            // 台股近期走勢摘要
        String usContext             // 美股近期走勢摘要
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewsHighlight(
            String title,
            String source,
            String url,
            String publishedAt
    ) {}
}
