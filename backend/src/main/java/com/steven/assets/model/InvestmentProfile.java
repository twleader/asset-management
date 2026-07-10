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
 * 資產配置建議（Requirement 32）——使用者理財條件 profile。
 *
 * <p>一使用者一列（{@code owner_user_id} 唯一），記住上次填的條件免重填。多租戶（Requirement 28）以
 * {@code ownerUserId} 隔離；{@code @Filter ownerFilter} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 於 repository 層啟用。by-id 存取另以 {@link com.steven.assets.security.TenantGuard} 把關。
 *
 * <p>{@code goals} 為理財目標「複選」的穩定 code 以逗號分隔（如 {@code RETIREMENT,PASSIVE_INCOME}）；
 * {@code riskTolerance} / {@code expectedAnnualReturn} 為單選 code。這些屬本頁表單詞彙、非跨域業務分類，
 * 比照市場分析 model/effort 以「服務層白名單」提供選項，不入 {@code /api/settings} 分類表。
 */
@Entity
@Table(name = "investment_profile", uniqueConstraints = @UniqueConstraint(
        name = "uq_investment_profile_owner", columnNames = "owner_user_id"))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestmentProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28）。 */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 生日。年齡＝生日與今天相距整年數，為衍生值，不入庫（正規化）；由前端／service 現算。 */
    @Column(name = "birth_date")
    private LocalDate birthDate;

    /** 預計投資年限（年）。 */
    @Column(name = "investment_horizon_years")
    private Integer investmentHorizonYears;

    /** 每月可投入金額（台幣）。退休後（{@code retirementDate} 之後）此定期投入視為 0。 */
    @Column(name = "monthly_investment", precision = 20, scale = 2)
    private BigDecimal monthlyInvestment;

    /**
     * 預計退休日期（整日）。退休前為「累積期」（每月投入有效），退休後為「守成／提領期」（每月投入歸零）。
     * 存為 DB {@code DATE}。累積年數／退休後年數為衍生值，不入庫，由 service 依此欄位與今天現算。
     */
    @Column(name = "retirement_date")
    private LocalDate retirementDate;

    /** 勞保年金月領金額（台幣）。為使用者填入之未來實際給付，不做通膨換算。 */
    @Column(name = "labor_insurance_monthly", precision = 20, scale = 2)
    private BigDecimal laborInsuranceMonthly;

    /** 勞保年金起領日期（整日；退休後現金流之起點）。 */
    @Column(name = "labor_insurance_start_date")
    private LocalDate laborInsuranceStartDate;

    /** 勞退一次領金額（台幣）。為使用者填入之未來實際給付，不做通膨換算。 */
    @Column(name = "labor_pension_lump_sum", precision = 20, scale = 2)
    private BigDecimal laborPensionLumpSum;

    /** 勞退領取日期（整日）。 */
    @Column(name = "labor_pension_claim_date")
    private LocalDate laborPensionClaimDate;

    /** 假設年通膨率（%）。供未來大筆花費「今日幣值 → 未來名目值」換算；null 時 service 以預設 2% 計。 */
    @Column(name = "assumed_annual_inflation_rate", precision = 5, scale = 2)
    private BigDecimal assumedAnnualInflationRate;

    /** 理財目標（複選 code，逗號分隔）。 */
    @Column(name = "goals", length = 300)
    private String goals;

    /** 可忍受風險（CONSERVATIVE / BALANCED / AGGRESSIVE）。 */
    @Column(name = "risk_tolerance", length = 20)
    private String riskTolerance;

    /** 獲利預期（年化報酬區間 code：LT3 / R3_6 / R6_10 / GT10）。 */
    @Column(name = "expected_annual_return", length = 20)
    private String expectedAnnualReturn;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
