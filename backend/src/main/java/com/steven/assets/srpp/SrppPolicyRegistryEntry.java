package com.steven.assets.srpp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Requirement 163／Task 452.1：已登錄的 SRPP 規則包（不可 UPDATE；登錄只由離線 SQL 進行）。
 * 本表不屬於任何 owner，故不加 {@code ownerFilter}。
 */
@Entity
@Table(name = "srpp_policy_registry")
public class SrppPolicyRegistryEntry {
    @Id
    @Column(name = "policy_bundle_sha256", length = 64, nullable = false, updatable = false)
    private String policyBundleSha256;
    @Column(name = "formula_version", length = 64, nullable = false, updatable = false)
    private String formulaVersion;
    @Column(name = "policy_document", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String policyDocument;
    @Column(name = "formula_manifest", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String formulaManifest;
    @Column(name = "registered_at", nullable = false, updatable = false, insertable = false)
    private Instant registeredAt;

    protected SrppPolicyRegistryEntry() {}

    public SrppPolicyRegistryEntry(String policyBundleSha256, String formulaVersion, String policyDocument,
                                   String formulaManifest, Instant registeredAt) {
        this.policyBundleSha256 = policyBundleSha256;
        this.formulaVersion = formulaVersion;
        this.policyDocument = policyDocument;
        this.formulaManifest = formulaManifest;
        this.registeredAt = registeredAt;
    }

    public String getPolicyBundleSha256() { return policyBundleSha256; }
    public String getFormulaVersion() { return formulaVersion; }
    public String getPolicyDocument() { return policyDocument; }
    public String getFormulaManifest() { return formulaManifest; }
    public Instant getRegisteredAt() { return registeredAt; }
}
