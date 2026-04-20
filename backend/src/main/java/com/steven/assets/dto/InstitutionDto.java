package com.steven.assets.dto;

import jakarta.validation.constraints.NotBlank;

public class InstitutionDto {

    // ===== Bank =====

    public record BankResponse(
            Long id,
            String code,
            String displayName,
            String keywords,
            Boolean active
    ) {}

    public record CreateBankRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            String keywords
    ) {}

    public record UpdateBankRequest(
            @NotBlank String displayName,
            String keywords
    ) {}

    // ===== Broker =====

    public record BrokerResponse(
            Long id,
            String code,
            String displayName,
            String keywords,
            Boolean active
    ) {}

    public record CreateBrokerRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            String keywords
    ) {}

    public record UpdateBrokerRequest(
            @NotBlank String displayName,
            String keywords
    ) {}

    // ===== DepositType =====

    public record DepositTypeResponse(
            Long id,
            String code,
            String displayName,
            Integer sortOrder,
            Boolean active
    ) {}

    public record CreateDepositTypeRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    public record UpdateDepositTypeRequest(
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    // ===== MarketType =====

    public record MarketTypeResponse(
            Long id,
            String code,
            String displayName,
            Integer sortOrder,
            Boolean active
    ) {}

    public record CreateMarketTypeRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    public record UpdateMarketTypeRequest(
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    // ===== TransitFundType =====

    public record TransitFundTypeResponse(
            Long id,
            String code,
            String displayName,
            Boolean payable,
            Integer sortOrder,
            Boolean active
    ) {}

    public record CreateTransitFundTypeRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Boolean payable,
            Integer sortOrder
    ) {}

    public record UpdateTransitFundTypeRequest(
            @NotBlank String displayName,
            Boolean payable,
            Integer sortOrder
    ) {}
}
