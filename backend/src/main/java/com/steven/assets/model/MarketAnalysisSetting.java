package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 今日股市分析（Requirement 31）設定：單列（{@code id=1}），存管理者於頁面選擇的分析模型。
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

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
