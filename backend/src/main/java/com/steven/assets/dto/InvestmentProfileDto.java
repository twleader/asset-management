package com.steven.assets.dto;

import com.steven.assets.model.InvestmentPlannedExpense;
import com.steven.assets.model.InvestmentProfile;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 資產配置建議（Requirement 32）——使用者理財條件 profile DTO（回前端，含各欄位可選清單供表單 render）。
 * {@code goals} 於 entity 以逗號分隔字串存，DTO 以陣列往返。日期以 ISO 字串（{@code YYYY-MM-DD}）往返。
 * 年齡不入庫、由前端依 {@code birthDate} 現算顯示（正規化）。
 */
public record InvestmentProfileDto(
        String birthDate,
        Integer investmentHorizonYears,
        BigDecimal monthlyInvestment,
        String retirementDate,
        BigDecimal laborInsuranceMonthly,
        String laborInsuranceStartDate,
        BigDecimal laborPensionLumpSum,
        String laborPensionClaimDate,
        BigDecimal assumedAnnualInflationRate,
        List<String> goals,
        String riskTolerance,
        String expectedAnnualReturn,
        List<PlannedExpense> plannedExpenses,
        List<Option> goalOptions,
        List<Option> riskOptions,
        List<Option> returnOptions
) {
    public record Option(String id, String label) {}

    /** 單筆大筆花費（amount 為今日幣值；未來名目值由前端依通膨率提示、後端組 prompt 時換算）。 */
    public record PlannedExpense(Long id, String expenseDate, String name, BigDecimal amount) {}

    /** 由 entity 組出（帶入可選清單與大筆花費清單）。 */
    public static InvestmentProfileDto from(
            InvestmentProfile e,
            List<InvestmentPlannedExpense> expenses,
            List<Option> goalOptions,
            List<Option> riskOptions,
            List<Option> returnOptions) {
        List<String> goals = (e == null || e.getGoals() == null || e.getGoals().isBlank())
                ? List.of()
                : Arrays.stream(e.getGoals().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<PlannedExpense> plannedExpenses = (expenses == null ? List.<InvestmentPlannedExpense>of() : expenses)
                .stream()
                .map(x -> new PlannedExpense(
                        x.getId(),
                        x.getExpenseDate() == null ? null : x.getExpenseDate().toString(),
                        x.getName(),
                        x.getAmount()))
                .toList();
        return new InvestmentProfileDto(
                (e == null || e.getBirthDate() == null) ? null : e.getBirthDate().toString(),
                e == null ? null : e.getInvestmentHorizonYears(),
                e == null ? null : e.getMonthlyInvestment(),
                (e == null || e.getRetirementDate() == null) ? null : e.getRetirementDate().toString(),
                e == null ? null : e.getLaborInsuranceMonthly(),
                (e == null || e.getLaborInsuranceStartDate() == null) ? null : e.getLaborInsuranceStartDate().toString(),
                e == null ? null : e.getLaborPensionLumpSum(),
                (e == null || e.getLaborPensionClaimDate() == null) ? null : e.getLaborPensionClaimDate().toString(),
                e == null ? null : e.getAssumedAnnualInflationRate(),
                goals,
                e == null ? null : e.getRiskTolerance(),
                e == null ? null : e.getExpectedAnnualReturn(),
                plannedExpenses,
                goalOptions,
                riskOptions,
                returnOptions);
    }
}
