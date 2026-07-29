package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 警示觸發歷史紀錄。每次警示條件成立時新增一筆，保留 30 天。
 *
 * 寫入時序：StockAlertService.evaluate 偵測到觸發 → 同步覆寫
 * StockAlert.last_triggered_* → INSERT 一筆 stock_alert_trigger。
 * Task 253 起另有群組路徑：evaluateGroup 偵測到所有成員條件同時成立 → 覆寫
 * StockAlertGroup.last_triggered_* → INSERT 一筆（{@code alert_id} 為 null、{@code group_id} 非空）。
 *
 * 5 個技術指標欄位（MA20 / MA60 / MA240 / K / D）皆無條件填寫，
 * 不論觸發類型為 PRICE / MA / KD，便於事後追蹤觸發當下完整技術面狀態。
 */
@Entity
@Table(name = "stock_alert_trigger", indexes = {
        @Index(name = "idx_stock_alert_trigger_alert_time", columnList = "alert_id, triggered_at DESC"),
        @Index(name = "idx_stock_alert_trigger_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlertTrigger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 觸發的獨立單一條件。
     *
     * <p>Task 253 起<b>可為 null</b>（複合條件群組觸發時）；{@code alert_id} 與 {@link #groupId} 恰好一個非空，
     * DB 以 {@code ck_sat_alert_xor_group} 保證。
     *
     * <p>此處<b>刻意不宣告 {@code nullable = false}</b>：DB 端已 DROP NOT NULL，entity 若仍宣告，
     * Hibernate 可能在 flush 前先擋下 {@code alertId = null} 的群組觸發列（是否真的擋取決於
     * {@code hibernate.check_nullability}，而本專案 application.yml 從未顯式設定該鍵、其預設又受 classpath 上的
     * Bean Validation 影響）——群組觸發寫不寫得進來，不押在一個沒人設定過的內部旗標上。
     */
    @Column(name = "alert_id")
    private Long alertId;

    /**
     * 觸發的複合條件群組（Task 253）。非空時本列代表<b>整個群組的一次 AND 觸發</b>，
     * 一次觸發只寫一筆（不是每個成員各寫一筆）——寫 N 筆會讓補發路徑
     * {@code AlertNotificationDispatcher.resendLastTradingDay} 把同一次 AND 觸發還原成 N 條獨立條件，
     * 文案與 live 寄出的合併 label 分歧。
     */
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    /**
     * 觸發時間，**存「該股市場」的牆鐘**（台股台北／美股紐約／英股倫敦），
     * 由 {@code StockAlertService.computeTriggeredAt()} 寫入。
     *
     * <p>Requirement 53「naive 欄位一律存台北牆鐘」的**唯二例外之一**（另一個是
     * {@code StockAlert.lastTriggeredAt}）。<b>不可納入時區校正 migration</b>。
     * 注意同一列的 {@code createdAt} 是 JVM 牆鐘，兩者相減依市場恆為 −8h／+4h／−1h，這是預期的。
     */
    @Column(name = "triggered_at", nullable = false)
    private LocalDateTime triggeredAt;

    @Column(precision = 10, scale = 4)
    private BigDecimal price;

    /** 月線 MA20 */
    @Column(name = "monthly_ma", precision = 10, scale = 4)
    private BigDecimal monthlyMa;

    /** 季線 MA60 */
    @Column(name = "quarterly_ma", precision = 10, scale = 4)
    private BigDecimal quarterlyMa;

    /** 年線 MA240 */
    @Column(name = "annual_ma", precision = 10, scale = 4)
    private BigDecimal annualMa;

    @Column(name = "k_value", precision = 10, scale = 4)
    private BigDecimal kValue;

    @Column(name = "d_value", precision = 10, scale = 4)
    private BigDecimal dValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
