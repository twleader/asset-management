package com.steven.assets.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.DailyMarketAnalysis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 今日股市分析（Requirement 31）——回前端的 DTO。
 * 由 {@link DailyMarketAnalysis} 組出，並把 JSON 字串欄位（keyFactors / newsHighlights）解析回陣列。
 */
public record MarketAnalysisDto(
        LocalDate analysisDate,
        String bias,
        Integer confidence,
        String summary,
        List<String> keyFactors,
        List<MarketAnalysisResult.NewsHighlight> newsHighlights,
        String twContext,
        String usContext,
        String model,
        String status,
        String errorMessage,
        Instant generatedAt
) {

    private static final TypeReference<List<String>> STR_LIST = new TypeReference<>() {};
    private static final TypeReference<List<MarketAnalysisResult.NewsHighlight>> NEWS_LIST =
            new TypeReference<>() {};

    public static MarketAnalysisDto from(DailyMarketAnalysis e, ObjectMapper mapper) {
        return new MarketAnalysisDto(
                e.getAnalysisDate(),
                e.getBias(),
                e.getConfidence(),
                e.getSummary(),
                parse(e.getKeyFactors(), STR_LIST, mapper),
                parse(e.getNewsHighlights(), NEWS_LIST, mapper),
                e.getTwContext(),
                e.getUsContext(),
                e.getModel(),
                e.getStatus(),
                e.getErrorMessage(),
                e.getGeneratedAt()
        );
    }

    /** 尚無任何分析時回傳的占位物件（status=NONE），前端據以顯示空狀態。 */
    public static MarketAnalysisDto none() {
        return new MarketAnalysisDto(null, null, null, null, List.of(), List.of(),
                null, null, null, "NONE", null, null);
    }

    private static <T> List<T> parse(String json, TypeReference<List<T>> type, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<T> list = mapper.readValue(json, type);
            return list != null ? list : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }
}
