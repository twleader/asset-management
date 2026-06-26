package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 警示 ↔ 通知收件人多對多 join（Requirement 23 / Task 125）。
 * 每條 {@link StockAlert} 可挑選 0 ~ N 位 {@link NotificationRecipient}，觸發時只寄給選定者。
 * 沿用本專案「以 Long id 顯式關聯」慣例（同 {@link StockAlertTrigger#getAlertId()}），不映射 JPA 關聯，
 * 避免 lazy-init 與 dispatcher 取值複雜化。{@code (alert_id, recipient_id)} 組合 unique。
 */
@Entity
@Table(name = "stock_alert_recipient",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_stock_alert_recipient",
                columnNames = {"alert_id", "recipient_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlertRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;
}
