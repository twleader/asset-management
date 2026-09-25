package com.steven.assets.srpp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Requirement 163／Task 452.1：已發布、不可變的 SRPP context package。
 *
 * <p>owner／日期／時段／規則包／generatedAt 與 {@code context_jcs} 內同義值重複，是「不可變證據＋查詢鍵」的
 * 刻意 denormalization。不加 {@code ownerFilter}：所有查詢方法都帶明確 ownerId 參數。
 */
@Entity
@Table(name = "srpp_context_package")
public class SrppContextPackage {
    @Id
    @Column(name = "package_id", nullable = false, updatable = false)
    private UUID packageId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;
    @Column(name = "trading_date", nullable = false, updatable = false)
    private LocalDate tradingDate;
    @Column(name = "slot", length = 5, nullable = false, updatable = false)
    private String slot;
    @Column(name = "policy_bundle_sha256", length = 64, nullable = false, updatable = false)
    private String policyBundleSha256;
    @Column(name = "generated_at", nullable = false, updatable = false)
    private Instant generatedAt;
    @Column(name = "context_jcs", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String contextJcs;

    protected SrppContextPackage() {}

    public SrppContextPackage(UUID packageId, Long ownerUserId, LocalDate tradingDate, String slot,
                              String policyBundleSha256, Instant generatedAt, String contextJcs) {
        this.packageId = packageId;
        this.ownerUserId = ownerUserId;
        this.tradingDate = tradingDate;
        this.slot = slot;
        this.policyBundleSha256 = policyBundleSha256;
        this.generatedAt = generatedAt;
        this.contextJcs = contextJcs;
    }

    public UUID getPackageId() { return packageId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public String getSlot() { return slot; }
    public String getPolicyBundleSha256() { return policyBundleSha256; }
    public Instant getGeneratedAt() { return generatedAt; }
    public String getContextJcs() { return contextJcs; }
}
