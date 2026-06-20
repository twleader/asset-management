package com.steven.assets.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

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

    // ===== AssetClass（Requirement 25）=====

    public record AssetClassResponse(
            Long id,
            String code,
            String displayName,
            Integer sortOrder,
            Boolean active
    ) {}

    public record CreateAssetClassRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    public record UpdateAssetClassRequest(
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    /** 標的（stock 主檔）逐檔資產類別 + 股票風格 + 債券期別歸類列。 */
    public record SecurityResponse(
            String code,
            String market,
            String name,
            String assetClass,           // asset_class 人工 override（null = 未設定，依規則）
            String effectiveAssetClass,  // 實際生效資產類別（override 或規則）
            String source,               // OVERRIDE / RULE
            // Requirement 26：股票風格（僅 effectiveAssetClass=STOCK 時有值）
            String stockStyle,           // stock_style 人工 override（null = 依規則）
            String effectiveStockStyle,  // 實際生效風格（最新快照殖利率套規則），非股票則 null
            String styleSource,          // OVERRIDE / RULE / null（非股票）
            // Requirement 27：債券期別（僅 effectiveAssetClass=BOND 時有值）
            String bondTerm,             // bond_term 人工 override（null = 依規則）
            String effectiveBondTerm,    // 實際生效期別（依名稱年期套規則），非債券則 null
            String termSource            // OVERRIDE / RULE / null（非債券）
    ) {}

    public record SetSecurityAssetClassRequest(
            @NotBlank String code,
            @NotBlank String market,
            String assetClass            // null / 空白 = 清除 override，還原為規則
    ) {}

    public record SetSecurityStockStyleRequest(
            @NotBlank String code,
            @NotBlank String market,
            String stockStyle            // null / 空白 = 清除 override，還原為規則
    ) {}

    public record SetSecurityBondTermRequest(
            @NotBlank String code,
            @NotBlank String market,
            String bondTerm              // null / 空白 = 清除 override，還原為規則
    ) {}

    // ===== BondTerm（Requirement 27）=====

    public record BondTermResponse(
            Long id,
            String code,
            String displayName,
            Integer sortOrder,
            Boolean active
    ) {}

    public record CreateBondTermRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    public record UpdateBondTermRequest(
            @NotBlank String displayName,
            Integer sortOrder
    ) {}

    // ===== StockStyle（Requirement 26）=====

    public record StockStyleResponse(
            Long id,
            String code,
            String displayName,
            Integer sortOrder,
            Boolean active,
            BigDecimal dividendThreshold
    ) {}

    public record CreateStockStyleRequest(
            @NotBlank String code,
            @NotBlank String displayName,
            Integer sortOrder,
            BigDecimal dividendThreshold
    ) {}

    public record UpdateStockStyleRequest(
            @NotBlank String displayName,
            Integer sortOrder,
            BigDecimal dividendThreshold
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
