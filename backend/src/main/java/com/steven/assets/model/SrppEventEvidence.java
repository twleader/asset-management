package com.steven.assets.model;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

/** 不可變的 SRPP 事件證據收據；只保存已驗證的 server-side capture 結果。 */
@Entity
@Table(name = "srpp_event_evidence", uniqueConstraints = @UniqueConstraint(name = "srpp_event_evidence_identity_uq", columnNames = {"owner_email", "trading_date", "slot", "analysis_profile"}))
public class SrppEventEvidence {
    @Id public UUID id;
    @Column(name = "owner_email", nullable = false, length = 320) public String ownerEmail;
    @Column(name = "trading_date", nullable = false) public LocalDate tradingDate;
    @Column(nullable = false, length = 5) public String slot;
    @Column(name = "analysis_profile", nullable = false, length = 24) public String analysisProfile;
    @Column(name = "policy_bundle_sha256", nullable = false, length = 64) public String policyBundleSha256;
    @Column(name = "swagger_sha256", nullable = false, length = 64) public String swaggerSha256;
    @Column(nullable = false, length = 16) public String status;
    @Column(name = "input_snapshot_sha256", nullable = false, length = 64) public String inputSnapshotSha256;
    @Column(name = "content_sha256", length = 64) public String contentSha256;
    @Column(name = "content_jcs", columnDefinition = "text") public String contentJcs;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "finalized_at") public Instant finalizedAt;
}
