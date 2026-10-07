package com.steven.assets.model;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

/** 每個 owner/date/slot 只有一個決策收據，絕不作為交易授權。 */
@Entity
@Table(name = "srpp_decision_run", uniqueConstraints = @UniqueConstraint(name = "srpp_decision_run_identity_uq", columnNames = {"owner_email", "trading_date", "slot"}))
public class SrppDecisionRun {
    @Id public UUID id;
    @Column(name = "owner_email", nullable = false, length = 320) public String ownerEmail;
    @Column(name = "trading_date", nullable = false) public LocalDate tradingDate;
    @Column(nullable = false, length = 5) public String slot;
    @Column(name = "policy_bundle_sha256", nullable = false, length = 64) public String policyBundleSha256;
    @Column(name = "swagger_sha256", nullable = false, length = 64) public String swaggerSha256;
    @Column(nullable = false, length = 16) public String status;
    @Column(name = "input_snapshot_sha256", nullable = false, length = 64) public String inputSnapshotSha256;
    @Column(name = "decision_content_sha256", length = 64) public String decisionContentSha256;
    @Column(name = "content_jcs", columnDefinition = "text") public String contentJcs;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "finalized_at") public Instant finalizedAt;
}
