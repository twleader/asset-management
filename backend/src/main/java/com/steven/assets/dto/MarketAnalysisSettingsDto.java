package com.steven.assets.dto;

import java.util.List;

/**
 * 今日股市分析（Requirement 31）設定 DTO：目前模型 + 可選模型清單。
 */
public record MarketAnalysisSettingsDto(
        String model,
        List<ModelOption> availableModels
) {
    public record ModelOption(String id, String label) {}
}
