package com.steven.assets.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 交易雷達通知設定 ↔ 既有通知收件人的多對多 join。 */
@Entity
@Table(name = "trading_radar_notification_recipient",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trn_recipient",
                columnNames = {"setting_id", "recipient_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingRadarNotificationRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_id", nullable = false)
    private Long settingId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;
}
