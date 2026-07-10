package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 資產配置建議（Requirement 32 / Task 164）——理財條件寫入命令物件（Controller 解析 body → Service）。
 *
 * <p>欄位變多後取代原先的長參數列。日期一律 {@link LocalDate}（整日）；勞保／勞退金額為使用者填入的
 * 未來實際給付（不做通膨換算）；{@code plannedExpenses} 為特定日期大筆花費清單（金額為今日幣值）。
 */
public record InvestmentProfileInput(
        LocalDate birthDate,
        Integer investmentHorizonYears,
        BigDecimal monthlyInvestment,
        LocalDate retirementDate,
        BigDecimal laborInsuranceMonthly,
        LocalDate laborInsuranceStartDate,
        BigDecimal laborPensionLumpSum,
        LocalDate laborPensionClaimDate,
        BigDecimal assumedAnnualInflationRate,
        List<String> goals,
        String riskTolerance,
        String expectedAnnualReturn,
        List<PlannedExpenseInput> plannedExpenses
) {
    /** 單筆大筆花費輸入（金額為填寫當下的今日幣值）。 */
    public record PlannedExpenseInput(
            LocalDate expenseDate,
            String name,
            BigDecimal amount
    ) {}
}
