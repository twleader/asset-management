package com.steven.assets.dto;

import com.steven.assets.model.InvestmentProfile;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 資產配置建議（Requirement 32）——使用者理財條件 profile DTO（回前端，含各欄位可選清單供表單 render）。
 * {@code goals} 於 entity 以逗號分隔字串存，DTO 以陣列往返。
 */
public record InvestmentProfileDto(
        Integer age,
        Integer investmentHorizonYears,
        BigDecimal monthlyInvestment,
        String retirementDate,
        List<String> goals,
        String riskTolerance,
        String expectedAnnualReturn,
        List<Option> goalOptions,
        List<Option> riskOptions,
        List<Option> returnOptions
) {
    public record Option(String id, String label) {}

    /** 由 entity 組出（帶入可選清單）。 */
    public static InvestmentProfileDto from(
            InvestmentProfile e,
            List<Option> goalOptions,
            List<Option> riskOptions,
            List<Option> returnOptions) {
        List<String> goals = (e == null || e.getGoals() == null || e.getGoals().isBlank())
                ? List.of()
                : Arrays.stream(e.getGoals().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new InvestmentProfileDto(
                e == null ? null : e.getAge(),
                e == null ? null : e.getInvestmentHorizonYears(),
                e == null ? null : e.getMonthlyInvestment(),
                (e == null || e.getRetirementDate() == null) ? null : e.getRetirementDate().toString(),
                goals,
                e == null ? null : e.getRiskTolerance(),
                e == null ? null : e.getExpectedAnnualReturn(),
                goalOptions,
                riskOptions,
                returnOptions);
    }
}
