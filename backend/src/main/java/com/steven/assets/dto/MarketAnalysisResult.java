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
        String usContext,            // 美股近期走勢摘要
        FactorGroups factorGroups    // 分類分點（Requirement 95／Task 358）；LLM 路徑恆為 null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewsHighlight(
            String title,
            String source,
            String url,
            String publishedAt
    ) {}

    /**
     * 台股與美股訊號 fragment 依分類拆分（Requirement 95／Task 358）。
     * 本機規則引擎（{@link com.steven.assets.service.LocalMarketAnalysisEngine}）填入；
     * LLM 路徑不輸出此 key，Jackson 反序列化時自動為 {@code null}。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FactorGroups(
            List<String> twTechnical,
            List<String> twVolume,
            List<String> us,
            List<String> chip
    ) {}
}
