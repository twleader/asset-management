package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Requirement 181／Task 481.9：不可變的 SRPP 事件證據 bundle（一列＝一次 capture 的收據）。
 *
 * <p>識別＝{@code (owner_user_id, trading_date, slot, analysis_profile, consumer, decision_id)}，資料庫具名 unique
 * {@value #IDENTITY_CONSTRAINT}；表上 {@code BEFORE UPDATE} trigger 擋任何更新。識別欄位與 {@code content_jcs.identity}
 * 重複是「不可變證據＋查詢鍵」的刻意 denormalization（同 {@code srpp_context_package}）；{@code request_sha256}
 * 因 request 不保存而必須留存。不另設 {@code content_sha256}／{@code rubric_sha256} 欄位；{@code content_jcs} 內的
 * {@code eventBundleContentSha256}（收據完整性自驗）與 {@code rubricSha256}（評分所用的 rubric 版本）屬收據本體的
 * 刻意 denormalization。
 *
 * <p>實作 {@link Persistable}：主鍵由應用端指定，必須以 persist（INSERT）而非 merge 寫入，讓唯一鍵衝突在
 * {@code saveAndFlush} 當下拋出。
 */
@Entity
@Table(name = "srpp_event_evidence_bundle", uniqueConstraints = @UniqueConstraint(
        name = SrppEventEvidenceBundle.IDENTITY_CONSTRAINT,
        columnNames = {"owner_user_id", "trading_date", "slot", "analysis_profile", "consumer", "decision_id"}))
public class SrppEventEvidenceBundle implements Persistable<UUID> {
    /** 識別 unique constraint 名稱；並行輸家判斷只認這一個 constraint。 */
    public static final String IDENTITY_CONSTRAINT = "srpp_event_evidence_bundle_identity_uq";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private Long ownerUserId;
    @Column(name = "trading_date", nullable = false, updatable = false)
    private LocalDate tradingDate;
    @Column(name = "slot", length = 5, nullable = false, updatable = false)
    private String slot;
    @Column(name = "analysis_profile", length = 24, nullable = false, updatable = false)
    private String analysisProfile;
    @Column(name = "consumer", length = 16, nullable = false, updatable = false)
    private String consumer;
    @Column(name = "decision_id", length = 100, nullable = false, updatable = false)
    private String decisionId;
    @Column(name = "policy_bundle_sha256", length = 64, nullable = false, updatable = false)
    private String policyBundleSha256;
    @Column(name = "swagger_sha256", length = 64, nullable = false, updatable = false)
    private String swaggerSha256;
    @Column(name = "request_sha256", length = 64, nullable = false, updatable = false)
    private String requestSha256;
    @Column(name = "content_jcs", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String contentJcs;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 只在記憶體標示「尚未寫入」；載入或寫入後即為 false。 */
    @Transient
    private boolean fresh = true;

    protected SrppEventEvidenceBundle() {}

    public SrppEventEvidenceBundle(UUID id, Long ownerUserId, LocalDate tradingDate, String slot,
                                   String analysisProfile, String consumer, String decisionId,
                                   String policyBundleSha256, String swaggerSha256, String requestSha256,
                                   String contentJcs, Instant createdAt) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.tradingDate = tradingDate;
        this.slot = slot;
        this.analysisProfile = analysisProfile;
        this.consumer = consumer;
        this.decisionId = decisionId;
        this.policyBundleSha256 = policyBundleSha256;
        this.swaggerSha256 = swaggerSha256;
        this.requestSha256 = requestSha256;
        this.contentJcs = contentJcs;
        this.createdAt = createdAt;
    }

    @PostLoad
    @PostPersist
    void markPersisted() { this.fresh = false; }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return fresh; }
    public Long getOwnerUserId() { return ownerUserId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public String getSlot() { return slot; }
    public String getAnalysisProfile() { return analysisProfile; }
    public String getConsumer() { return consumer; }
    public String getDecisionId() { return decisionId; }
    public String getPolicyBundleSha256() { return policyBundleSha256; }
    public String getSwaggerSha256() { return swaggerSha256; }
    public String getRequestSha256() { return requestSha256; }
    public String getContentJcs() { return contentJcs; }
    public Instant getCreatedAt() { return createdAt; }
}
