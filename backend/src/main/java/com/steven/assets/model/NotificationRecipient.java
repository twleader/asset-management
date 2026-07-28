package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * 警示觸發 Email 通知收件人（Requirement 23）。
 * email 寫入前正規化（trim + toLowerCase）。
 *
 * <p>Requirement 28（多租戶）：以 {@code ownerUserId} 隔離；唯一性改為每使用者唯一
 * 複合 {@code (owner_user_id, email)}。
 */
@Entity
@Table(name = "notification_recipient", uniqueConstraints = @UniqueConstraint(
        name = "uq_recipient_owner_email", columnNames = {"owner_user_id", "email"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * 是否接收「今日股市分析」每日 Email（Requirement 31 / Task 151）。
     * 與 {@link #active}（是否接收警示通知）各自獨立；預設訂閱（沿用既有收件人），可自行取消。
     */
    @Column(name = "receive_market_analysis", nullable = false)
    @Builder.Default
    private Boolean receiveMarketAnalysis = true;

    /**
     * 警示 digest email 是否夾帶 Google 日曆邀請（ics，Requirement 23 / Task 248）。
     * **僅 gmail.com / googlemail.com 網域可設為 true**（見 {@code NotificationRecipientService.isGmail}）。
     * 預設 false：日曆事件會實際寫進別人的日曆，屬明示同意才開的行為，不對既有收件人自動開啟。
     * 與 {@link #active}（收警示）、{@link #receiveMarketAnalysis}（收股市分析）各自獨立——本旗標只是
     * 「收警示信時額外夾帶邀請」的修飾，{@code active=false} 時本就不寄信，日曆自然也不會有事件。
     */
    @Column(name = "add_to_calendar", nullable = false)
    @Builder.Default
    private Boolean addToCalendar = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
