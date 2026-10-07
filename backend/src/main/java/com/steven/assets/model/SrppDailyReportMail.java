package com.steven.assets.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** 持久化 SMTP 副作用的唯一稽核帳；不是投資或交易資料。 */
@Entity
@Table(name = "srpp_daily_report_mail")
public class SrppDailyReportMail {
    @Id @Column(name = "idempotency_key", length = 200) public String idempotencyKey;
    @Column(name = "request_sha256", nullable = false, length = 64) public String requestSha256;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) public State state;
    @Column(name = "lease_id") public UUID leaseId;
    @Column(name = "lease_expires_at") public Instant leaseExpiresAt;
    @Column(name = "message_id", length = 998) public String messageId;
    @Column(name = "sent_at") public Instant sentAt;
    @Column(name = "from_address", length = 320) public String fromAddress;
    @Column(name = "to_address", length = 320) public String toAddress;
    @Column(length = 512) public String subject;
    @Column(name = "html_sha256", length = 64) public String htmlSha256;
    @Column(name = "text_sha256", length = 64) public String textSha256;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "updated_at", nullable = false) public Instant updatedAt;
    public enum State { PROCESSING, SUBMITTING, SENT, FAILED, OUTCOME_UNKNOWN }
}
