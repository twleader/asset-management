package com.steven.assets.dto;

import java.util.List;

/**
 * 資產配置建議（Requirement 32）成本控管設定 DTO：目前模型 + 思考深度（effort）+ web 搜尋次數，
 * 及各自可選清單。比照 {@link MarketAnalysisSettingsDto}。
 */
public record PortfolioAdviceSettingsDto(
        String model,
        String effort,
        Integer webSearchMaxUses,
        List<ModelOption> availableModels,
        List<EffortOption> availableEfforts,
        List<WebSearchOption> availableWebSearches
) {
    public record ModelOption(String id, String label) {}

    public record EffortOption(String id, String label) {}

    /** web 搜尋次數選項；{@code value=0} 表示關閉（僅依個人資產與條件）。 */
    public record WebSearchOption(Integer value, String label) {}
}
