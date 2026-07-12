package com.steven.assets.dto;

import java.util.List;

/**
 * 今日股市分析（Requirement 31）設定 DTO：目前模型 + 思考深度（effort）
 * + 每日自動分析開關（enabled），及各自可選清單。
 * （Task 179 起新聞固定讀本地 {@code news_headline}，已移除新聞搜尋次數 web search 設定。）
 */
public record MarketAnalysisSettingsDto(
        String model,
        String effort,
        Boolean enabled,
        List<ModelOption> availableModels,
        List<EffortOption> availableEfforts
) {
    public record ModelOption(String id, String label) {}

    public record EffortOption(String id, String label) {}
}
