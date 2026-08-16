package com.steven.assets.dto;

import java.util.List;

/**
 * 資產配置建議（Requirement 32）成本控管設定 DTO：目前分析引擎（engine，Requirement 80 / Task 339）
 * + 模型 + 思考深度（effort）+ web 搜尋次數，及各自可選清單。比照 {@link MarketAnalysisSettingsDto}。
 *
 * <p>{@code engine=local} 時 {@code model}／{@code effort}／{@code webSearchMaxUses} 仍為有效的
 * {@code llm} 模式設定值，只是本次不生效；{@code engine=hybrid} 時 {@code webSearchMaxUses} 一律不生效
 * （該檔位強制不搜尋）——前端據此把對應下拉停用而非隱藏。</p>
 */
public record PortfolioAdviceSettingsDto(
        String engine,
        String model,
        String effort,
        Integer webSearchMaxUses,
        List<ModelOption> availableModels,
        List<EffortOption> availableEfforts,
        List<WebSearchOption> availableWebSearches,
        List<EngineOption> availableEngines
) {
    public record ModelOption(String id, String label) {}

    public record EffortOption(String id, String label) {}

    /** web 搜尋次數選項；{@code value=0} 表示關閉（僅依個人資產與條件）。 */
    public record WebSearchOption(Integer value, String label) {}

    /**
     * 分析引擎選項（Requirement 80 / Task 339）：{@code local}＝完全本機（免費）、
     * {@code hybrid}＝本機計算＋AI 撰寫敘述（省錢）、{@code llm}＝完整 AI 分析（含網路搜尋，最貴）。
     */
    public record EngineOption(String id, String label) {}
}
