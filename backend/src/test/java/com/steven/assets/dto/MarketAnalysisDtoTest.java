package com.steven.assets.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.DailyMarketAnalysis;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 357（Requirement 94）{@code MarketAnalysisDto.factorGroups} 的解析語意測試。
 *
 * <p>{@code parseFactorGroups} 與既有 {@code parse()}（失敗時回 {@code List.of()}）刻意不同：
 * 失敗或空白時回 {@code null}，代表「本次分析沒有分類資料」，與「有分類、剛好零命中」的
 * 空 {@code FactorGroups} 語意不同。</p>
 */
class MarketAnalysisDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static DailyMarketAnalysis rowWithFactorGroups(String json) {
        DailyMarketAnalysis row = new DailyMarketAnalysis();
        row.setAnalysisDate(LocalDate.of(2026, 8, 22));
        row.setStatus(DailyMarketAnalysis.STATUS_OK);
        row.setFactorGroups(json);
        return row;
    }

    @Test
    void from_returnsNullFactorGroups_whenColumnIsNull() {
        MarketAnalysisDto dto = MarketAnalysisDto.from(rowWithFactorGroups(null), mapper);
        assertNull(dto.factorGroups());
    }

    @Test
    void from_returnsNullFactorGroups_whenColumnIsBlank() {
        MarketAnalysisDto dto = MarketAnalysisDto.from(rowWithFactorGroups("   "), mapper);
        assertNull(dto.factorGroups());
    }

    @Test
    void from_returnsNullFactorGroups_whenJsonIsCorrupt() {
        MarketAnalysisDto dto = MarketAnalysisDto.from(rowWithFactorGroups("{not valid json"), mapper);
        assertNull(dto.factorGroups());
    }

    @Test
    void from_roundTripsFactorGroups_whenColumnHoldsValidJson() throws Exception {
        MarketAnalysisResult.FactorGroups groups = new MarketAnalysisResult.FactorGroups(
                List.of("台股收盤 47,625.50 點"), List.of("台股成交金額 12,000 億元"),
                List.of("費城半導體（SOX）最新交易日收 7,350.00"), List.of());
        String json = mapper.writeValueAsString(groups);

        MarketAnalysisDto dto = MarketAnalysisDto.from(rowWithFactorGroups(json), mapper);

        assertEquals(groups, dto.factorGroups());
    }

    @Test
    void none_hasNullFactorGroups() {
        assertNull(MarketAnalysisDto.none().factorGroups());
    }

    /**
     * 357.2c：LLM 路徑既有 system prompt schema 不含 {@code factorGroups} key；
     * Jackson 對缺 key 欄位須自動填 {@code null}（不得因此反序列化失敗）。
     */
    @Test
    void marketAnalysisResult_deserializesWithNullFactorGroups_whenLlmJsonOmitsTheKey() throws Exception {
        String llmJson = "{"
                + "\"bias\":\"BULLISH\",\"confidence\":70,\"summary\":\"s\","
                + "\"keyFactors\":[\"f1\"],\"newsHighlights\":[],"
                + "\"twContext\":\"tw\",\"usContext\":\"us\""
                + "}";

        MarketAnalysisResult r = mapper.readValue(llmJson, MarketAnalysisResult.class);

        assertNull(r.factorGroups());
        assertEquals("BULLISH", r.bias());
    }
}
