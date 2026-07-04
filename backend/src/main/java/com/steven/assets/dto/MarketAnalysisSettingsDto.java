package com.steven.assets.dto;

import java.util.List;

/**
 * 今日股市分析（Requirement 31）設定 DTO：目前模型 + 思考深度（effort）+ 新聞搜尋次數（web search）
 * + 每日自動分析開關（enabled），及各自可選清單。
 */
public record MarketAnalysisSettingsDto(
        String model,
        String effort,
        Integer webSearchMaxUses,
        Boolean enabled,
        List<ModelOption> availableModels,
        List<EffortOption> availableEfforts,
        List<WebSearchOption> availableWebSearches
) {
    public record ModelOption(String id, String label) {}

    public record EffortOption(String id, String label) {}

    /** 新聞搜尋次數選項；{@code value=0} 表示關閉（不使用 web_search）。 */
    public record WebSearchOption(Integer value, String label) {}
}
