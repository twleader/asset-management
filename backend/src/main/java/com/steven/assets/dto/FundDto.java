package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;

public record FundDto(
            String fundCode,
            String fundName,
            Long bankId,
            String bankDisplayName,
            String currency,
            String site,
            String fundclearOrgCode,
            String fundclearFundCode,
            String fundclearClassCode,
            Boolean active,
            BigDecimal latestNav,
            LocalDate latestNavDate,
            BigDecimal latestFxRate,
            LocalDate latestFxDate,
            BigDecimal twdPerUnit,
            BigDecimal annualDividendPerUnitTwd,  // Requirement 20：近 12 個月加總 × FX
            Integer dividendMonthsCounted          // 統計用，前端可顯示「近 N 個月」
    ) {}
