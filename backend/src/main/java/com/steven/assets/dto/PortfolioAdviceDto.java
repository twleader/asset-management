package com.steven.assets.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.PortfolioAdvice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/**
 * 資產配置建議（Requirement 32）——回前端的 DTO。
 * 由 {@link PortfolioAdvice} 組出，並把 {@code result_json} 解析回結構化建議、{@code goals} 轉陣列。
 */
public record PortfolioAdviceDto(
        Long id,
        String status,
        String model,
        Instant createdAt,
        Instant completedAt,
        String errorMessage,
        // 條件快照
        Integer age,
        Integer investmentHorizonYears,
        BigDecimal monthlyInvestment,
        List<String> goals,
        String riskTolerance,
        String expectedAnnualReturn,
        // 資產依據
        Long basedOnSnapshotId,
        LocalDate basedOnSnapshotDate,
        BigDecimal basedOnTotalAssets,
        // 解析後建議
        String summary,
        String riskAssessment,
        List<PortfolioAdviceResult.TargetAllocation> targetAllocation,
        List<PortfolioAdviceResult.Action> actions,
        List<String> warnings,
        List<PortfolioAdviceResult.Reference> references
) {

    public static PortfolioAdviceDto from(PortfolioAdvice e, ObjectMapper mapper) {
        PortfolioAdviceResult r = parse(e.getResultJson(), mapper);
        List<String> goals = (e.getGoals() == null || e.getGoals().isBlank())
                ? List.of()
                : Arrays.stream(e.getGoals().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new PortfolioAdviceDto(
                e.getId(),
                e.getStatus(),
                e.getModel(),
                e.getCreatedAt(),
                e.getCompletedAt(),
                e.getErrorMessage(),
                e.getAge(),
                e.getInvestmentHorizonYears(),
                e.getMonthlyInvestment(),
                goals,
                e.getRiskTolerance(),
                e.getExpectedAnnualReturn(),
                e.getBasedOnSnapshotId(),
                e.getBasedOnSnapshotDate(),
                e.getBasedOnTotalAssets(),
                r == null ? null : r.summary(),
                r == null ? null : r.riskAssessment(),
                r == null || r.targetAllocation() == null ? List.of() : r.targetAllocation(),
                r == null || r.actions() == null ? List.of() : r.actions(),
                r == null || r.warnings() == null ? List.of() : r.warnings(),
                r == null || r.references() == null ? List.of() : r.references());
    }

    /** 尚無任何建議時回傳的占位物件（status=NONE）。 */
    public static PortfolioAdviceDto none() {
        return new PortfolioAdviceDto(null, "NONE", null, null, null, null,
                null, null, null, List.of(), null, null,
                null, null, null,
                null, null, List.of(), List.of(), List.of(), List.of());
    }

    private static PortfolioAdviceResult parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readValue(json, PortfolioAdviceResult.class);
        } catch (Exception e) {
            return null;
        }
    }
}
