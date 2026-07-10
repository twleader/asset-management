package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 資產配置建議（Requirement 32 / Task 164）——使用者「特定日期大筆花費」子項（一使用者多筆）。
 *
 * <p>多租戶（Requirement 28）以 {@code ownerUserId} 隔離；{@code @Filter ownerFilter} 由
 * {@link com.steven.assets.security.TenantFilterAspect} 於 repository 層啟用。
 *
 * <p>{@code amount} 為「填寫當下的今日幣值」原始值；**未來名目金額＝{@code amount × (1+r)^距花費日年數}
 * 為衍生值、不入庫**，由 service 依 {@code investment_profile.assumed_annual_inflation_rate} 現算後餵給 AI。
 * saveProfile 以「先刪 owner 全部再插入提交清單」replace（清單小、簡單穩健）。
 */
@Entity
@Table(name = "investment_planned_expense")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentPlannedExpense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28）。 */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 預計花費日期（整日）。 */
    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    /** 用途／名稱（如「買車」）。 */
    @Column(name = "name", length = 100)
    private String name;

    /** 金額（台幣，填寫當下的今日幣值）。未來名目值由 service 依通膨率現算、不入庫。 */
    @Column(name = "amount", precision = 20, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
