package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 資產配置建議（Requirement 32）成本控管設定：單列（{@code id=1}），存管理者選擇的分析引擎（engine，
 * Requirement 80 / Task 339）、分析模型、思考深度（effort）、web 搜尋次數。
 * 全域（不分租戶），比照 {@link MarketAnalysisSetting}。
 */
@Entity
@Table(name = "portfolio_advice_setting")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioAdviceSetting {

    /** 固定單列 id=1。 */
    public static final Integer SINGLETON_ID = 1;

    @Id
    private Integer id;

    /**
     * 分析引擎（Requirement 80 / Task 339）：{@code local}＝完全本機（零 API）、
     * {@code hybrid}＝本機計算＋AI 撰寫敘述、{@code llm}＝現行完整 AI 分析。預設 local。
     * {@code local} 時 {@code model}／{@code effort}／{@code webSearchMaxUses} 仍保留為 llm 模式的有效設定值，只是本次不生效。
     */
    @Column(name = "engine", length = 16, nullable = false)
    private String engine;

    /** 建議所用 Claude model id（如 claude-opus-4-8）。 */
    @Column(name = "model", length = 64, nullable = false)
    private String model;

    /** 思考深度 effort（low/medium/high）：越低越省 thinking 輸出 token。預設 medium。 */
    @Column(name = "effort", length = 16, nullable = false)
    private String effort;

    /** web 搜尋次數（web_search maxUses）：0＝關閉（僅依個人資產與條件）。預設 4。 */
    @Column(name = "web_search_max_uses", nullable = false)
    private Integer webSearchMaxUses;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
