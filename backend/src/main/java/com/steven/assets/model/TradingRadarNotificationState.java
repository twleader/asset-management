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

import java.time.Instant;

/** 通知設定選定的狀態；label 由規則版本決定，不冗存。 */
@Entity
@Table(name = "trading_radar_notification_state",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trn_state",
                columnNames = {"setting_id", "state_type", "state_code"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingRadarNotificationState {

    public static final String TYPE_ACTION = "ACTION";
    public static final String TYPE_COUNTER_TREND = "COUNTER_TREND";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "setting_id", nullable = false)
    private Long settingId;

    @Column(name = "state_type", nullable = false, length = 30)
    private String stateType;

    @Column(name = "state_code", nullable = false, length = 50)
    private String stateCode;

    /** 該狀態上次放行派送（enqueue）的時間，非實際寄達時間；null 表示從未放行過（Task 301 通知冷卻）。 */
    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;
}
