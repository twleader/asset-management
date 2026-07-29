package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 複合條件群組 ↔ 通知收件人多對多 join（Requirement 23 / Task 253）。
 * 每個 {@link StockAlertGroup} 可挑選 0 ~ N 位 {@link NotificationRecipient}，群組觸發時只寄給選定者。
 * 沿用本專案「以 Long id 顯式關聯」慣例（同 {@link StockAlertRecipient}），不映射 JPA 關聯，
 * 避免 lazy-init 與 dispatcher 取值複雜化。{@code (group_id, recipient_id)} 組合 unique。
 *
 * <p><b>刻意不掛 {@code @Filter}</b>：比照既有 {@link StockAlertRecipient}，join 表本身無 owner 欄位。
 * 租戶保護在寫入端完成——{@code replaceGroupRecipients} 先以 owner-filtered 的收件人白名單取交集，
 * 他人的 recipientId 一律略過；dispatcher 讀取端另以 {@code r.ownerUserId = g.ownerUserId} 做縱深防護
 * （背景 cron 無 request context，{@code ownerFilter} 不啟用）。
 */
@Entity
@Table(name = "stock_alert_group_recipient",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_stock_alert_group_recipient",
                columnNames = {"group_id", "recipient_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlertGroupRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;
}
