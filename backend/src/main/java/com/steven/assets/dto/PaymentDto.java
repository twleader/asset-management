package com.steven.assets.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 代繳設定相關 DTO（Requirement 22）。
 */
public class PaymentDto {

    // ===== PaymentCategory =====

    public record CategoryResponse(
            Long id,
            String code,
            String displayName,
            Integer sortOrder,
            Boolean active
    ) {}

    public record CreateCategoryRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    public record UpdateCategoryRequest(
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    // ===== PaymentAccount =====

    public record AccountResponse(
            Long id,
            Long categoryId,
            String categoryCode,
            String categoryDisplayName,
            String itemName,
            String paymentAccount,
            String note,
            Integer sortOrder
    ) {}

    public record CreateAccountRequest(
            @NotNull Long categoryId,
            @NotBlank String itemName,
            String paymentAccount,
            String note,
            Integer sortOrder
    ) {}

    public record UpdateAccountRequest(
            @NotNull Long categoryId,
            @NotBlank String itemName,
            String paymentAccount,
            String note,
            Integer sortOrder
    ) {}
}
