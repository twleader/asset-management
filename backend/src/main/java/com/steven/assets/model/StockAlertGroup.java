package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 複合條件警示群組（Requirement 16 / Task 253）：綁 2～5 個 {@link StockAlert} 成員，
 * <b>全部條件在同一次評估中同時成立</b>才觸發一次（單層 AND）。
 *
 * <p>與獨立單一條件的分工：一列 {@code stock_alert} 天然是 OR（任一達標各自寄信），
 * 本表則把數個條件收攏成一個判定單位。成員以 {@link StockAlert#getGroupId()} 反向指向本表，
 * 沿用本專案「以 Long id 顯式關聯」慣例，不映射 JPA 關聯。
 *
 * <p><b>觸發狀態只記在這一列。</b>24h cooldown 看群組（成員不各自 cooldown）、
 * {@code last_triggered_*} 五欄也只寫群組，成員那五欄永遠留 null——同一次 AND 觸發在兩處各留一份紀錄
 * 會讓警示頁／觀察頁／補發信三邊的文案各說各話。
 *
 * <p>刻意<b>不設 {@code logic_op} 欄位</b>：本任務只做 AND，只有一種運算子時該欄位是恆定值，
 * 違反本專案「不存可計算得出的衍生值」原則；日後真要支援 OR 再加 nullable 欄位。
 *
 * <p>Requirement 28（多租戶）：以 {@code ownerUserId} 隔離，比照 {@link StockAlert}；
 * {@code ownerFilter} 的 {@code @FilterDef} 定義於 package-info，此處只掛 {@code @Filter}。
 * 背景偵測 cron 無 request context、不啟用 owner filter，掃全體 active 群組、寄信給各群組自選的收件人。
 */
@Entity
@Table(name = "stock_alert_group")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlertGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(nullable = false, length = 20)
    private String market;

    /** 啟停以群組為單位；成員的 {@code active} 恆為 true，不個別啟停 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * 與獨立條件<b>共用同一個排序空間</b>（警示頁的混合清單依此升冪）。
     * 群組成員的 {@code displayOrder} 從本值起算（第 i 個成員 = 本值 + i），不參與此重排。
     */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    /** 最近一次「所有成員條件同時成立」的時點；24h cooldown 以此判定 */
    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    /** 觸發當下的股價 */
    @Column(name = "last_triggered_price")
    private BigDecimal lastTriggeredPrice;

    /** 觸發當下的均線值（取群組內第一個 MA_*_PCT 成員的 maPeriod 對應均線；無 MA 成員則不寫） */
    @Column(name = "last_triggered_ma_value")
    private BigDecimal lastTriggeredMaValue;

    /** 觸發當下的 K 值 */
    @Column(name = "last_triggered_kd_value")
    private BigDecimal lastTriggeredKdValue;

    /** 觸發當下的 D 值 */
    @Column(name = "last_triggered_d_value")
    private BigDecimal lastTriggeredDValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
