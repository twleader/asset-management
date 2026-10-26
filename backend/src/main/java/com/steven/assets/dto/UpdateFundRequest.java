package com.steven.assets.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.validation.constraints.NotBlank;

public record UpdateFundRequest(
            @NotBlank String fundName,
            Long bankId,
            @NotBlank String currency,
            @NotBlank String site,
            @NotBlank String fundclearOrgCode,
            @NotBlank String fundclearFundCode,
            @NotBlank String fundclearClassCode
    ) {}
