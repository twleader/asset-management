package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 今日股市分析（Requirement 31 / Task 184）可設定的「分析寄送時間」：每個 {@code active=true} 列＝一個
 * 每台股交易日觸發時點，排程每分鐘 tick 比對現在 HH:mm 命中即重跑一次分析並寄一封。
 *
 * <p>全域單一排程設定——不分租戶、無 {@code owner_user_id}、無 {@code @Filter}，不受 {@code TenantFilterAspect}
 * owner 過濾（比照 {@link MarketAnalysisSetting}）。{@code send_time} 精確到分、DB 層 UNIQUE。
 */
@Entity
@Table(name = "market_analysis_send_time")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketAnalysisSendTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 寄送時點（HH:mm，Asia/Taipei）；每分鐘 tick 以「截到分」比對命中。 */
    @Column(name = "send_time", nullable = false, unique = true)
    private LocalTime sendTime;

    /** 是否啟用：false＝此時點不觸發（保留設定、可再開）。預設 true。 */
    @Column(name = "active", nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
