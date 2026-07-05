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
 * 資產配置建議（Requirement 32）——單次產生的建議紀錄（歷次保存供回顧）。
 *
 * <p>多租戶（Requirement 28）以 {@code ownerUserId} 隔離（{@code @Filter ownerFilter}）。
 *
 * <p>條件快照（{@code age}/{@code investmentHorizonYears}/{@code monthlyInvestment}/{@code goals}/
 * {@code riskTolerance}/{@code expectedAnnualReturn}）與 {@code basedOnSnapshotDate}/{@code basedOnTotalAssets}
 * 為刻意 denormalize 的歷史快照：回顧時要能呈現「產生當下」的條件與資產依據，即使之後 profile 改動或快照刪除亦不失真
 * （比照 {@code realized_gain} 記錄成交當下券商名稱、{@code asset_snapshot} 匯總欄之歷史快照例外）。
 * {@code basedOnSnapshotId} 為正規化參照（指向依據的 {@code asset_snapshot}，不複製明細）。
 * {@code resultJson} 存 Claude 解析後的結構化建議 JSON（比照 {@code daily_market_analysis} 存 parsed JSON）。
 */
@Entity
@Table(name = "portfolio_advice")
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortfolioAdvice {

    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28）。 */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** OK / FAILED / NOT_CONFIGURED。 */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    /** 產生所用 Claude model id。 */
    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    // ===== 條件快照（產生當下的輸入）=====

    @Column(name = "age")
    private Integer age;

    @Column(name = "investment_horizon_years")
    private Integer investmentHorizonYears;

    @Column(name = "monthly_investment", precision = 20, scale = 2)
    private BigDecimal monthlyInvestment;

    @Column(name = "goals", length = 300)
    private String goals;

    @Column(name = "risk_tolerance", length = 20)
    private String riskTolerance;

    @Column(name = "expected_annual_return", length = 20)
    private String expectedAnnualReturn;

    // ===== 資產依據（產生當下）=====

    /** 依據的資產快照 id（正規化參照）。 */
    @Column(name = "based_on_snapshot_id")
    private Long basedOnSnapshotId;

    /** 依據快照日期（歷史快照，供回顧顯示）。 */
    @Column(name = "based_on_snapshot_date")
    private LocalDate basedOnSnapshotDate;

    /** 依據快照的資產總額（台幣，歷史快照，供回顧顯示）。 */
    @Column(name = "based_on_total_assets", precision = 20, scale = 2)
    private BigDecimal basedOnTotalAssets;

    // ===== 結果 =====

    /** Claude 原始回應（除錯用）。 */
    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    /** 解析後結構化建議 JSON（summary / riskAssessment / targetAllocation / actions / warnings / references）。 */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;
}
