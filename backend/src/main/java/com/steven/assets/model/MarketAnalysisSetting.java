package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 今日股市分析（Requirement 31）設定：單列（{@code id=1}），存管理者於頁面選擇的分析模型、
 * 思考深度（effort）、新聞搜尋次數（web search）與每日自動分析開關（enabled，皆成本控管）。
 * 比照 {@link BackupSetting} 單列慣例。全域（不分租戶）。
 */
@Entity
@Table(name = "market_analysis_setting")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarketAnalysisSetting {

    /** 固定單列 id=1。 */
    public static final Integer SINGLETON_ID = 1;

    @Id
    private Integer id;

    /** 分析所用 Claude model id（如 claude-opus-4-8）。 */
    @Column(name = "model", length = 64, nullable = false)
    private String model;

    /** 思考深度 effort（low/medium/high）：越低越省 thinking 輸出 token。成本控管，預設 medium。 */
    @Column(name = "effort", length = 16, nullable = false)
    private String effort;

    /** 新聞搜尋次數（web_search maxUses）：0＝關閉（純技術面）。越少越省 context 重複處理。預設 6。 */
    @Column(name = "web_search_max_uses", nullable = false)
    private Integer webSearchMaxUses;

    /** 每日自動分析開關：false＝07:30 cron／self-heal 跳過（零花費）；手動觸發不受此限。預設 true。 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
